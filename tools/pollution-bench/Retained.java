import com.wormzjl.icheme.science.pollution.*;
import com.wormzjl.icheme.science.pollution.Environment.Cell;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Retained bytes of the pollution system's live state, and the bytes a server pass allocates around the step. The size
 * of an object graph is summed by walking it with reflection and the HotSpot 64-bit layout with compressed oops and
 * class pointers: an object is a 12-byte header plus its fields (8 for long and double, 4 for int, float and a
 * reference, 2 for short and char, 1 for byte and boolean), an array a 16-byte header plus its elements, each rounded up
 * to 8 bytes; an object reached twice counts once. Usage, after compileJava:
 *
 * <pre>java --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED
 *     -cp build/classes/java/main tools/pollution-bench/Retained.java</pre>
 *
 * The runtime's caches live in classes that need Minecraft ({@code PollutionService}); where the class is not
 * Minecraft-free the tool builds the same structure (named "as built by") with the same element types.
 */
public class Retained {
    static long size(Object... roots) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object> work = new ArrayDeque<>();
        for (Object r : roots) if (r != null) work.push(r);
        long total = 0;
        while (!work.isEmpty()) {
            Object o = work.pop();
            if (seen.put(o, Boolean.TRUE) != null) continue;
            Class<?> c = o.getClass();
            if (o instanceof Class || o instanceof ClassLoader || o instanceof Thread) continue;
            if (c.isArray()) {
                Class<?> e = c.getComponentType();
                int n = Array.getLength(o);
                total += align(16 + (long) n * width(e));
                if (!e.isPrimitive()) for (int i = 0; i < n; i++) { Object v = Array.get(o, i); if (v != null) work.push(v); }
                continue;
            }
            long bytes = 12;
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    bytes += width(f.getType());
                    if (!f.getType().isPrimitive()) {
                        try {
                            f.setAccessible(true);
                            Object v = f.get(o);
                            if (v != null) work.push(v);
                        } catch (RuntimeException | IllegalAccessException inaccessible) {
                            // A JDK field this run did not open: its object is not counted.
                        }
                    }
                }
            }
            total += align(bytes);
        }
        return total;
    }

    static int width(Class<?> t) {
        if (t == long.class || t == double.class) return 8;
        if (t == int.class || t == float.class) return 4;
        if (t == short.class || t == char.class) return 2;
        if (t == byte.class || t == boolean.class) return 1;
        return 4;
    }

    static long align(long b) {
        return (b + 7) & ~7L;
    }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    static void row(String what, long count, String unit, long bytes) {
        System.out.printf("| %s | %,d %s | %,d | %.1f |%n", what, count, unit, bytes, count == 0 ? 0.0 : (double) bytes / count);
    }

    /** As built by PollutionService (2026-11-18): the cell cache's entry. */
    record Cached(Cell cell, int tick) {
    }

    /** As built by PollutionService: the water column cache's entry. */
    record CachedWater(boolean water, int tick) {
    }

    /** A ground field of {@code n} columns (a square), three compounds each from six names, as a load leaves it. */
    static GroundField ground(int n) {
        String[] names = {"Ammonia", "Benzene", "Hydrogen sulfide", "Phenol", "crude_pc08", "Sulfur dioxide"};
        int side = (int) Math.ceil(Math.sqrt(n));
        long[] columns = new long[n];
        int k = 0;
        for (int x = 0; x < side && k < n; x++) for (int z = 0; z < side && k < n; z++) columns[k++] = MapColumns.column(x - side / 2, z - side / 2);
        Arrays.sort(columns);
        double[] amounts = new double[n];
        List<GroundField.Entry> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            amounts[i] = GroundField.GROUND_REFERENCE * (0.01 + (i % 37) / 40.0);
            String[] three = {names[i % 6], names[(i + 2) % 6], names[(i + 4) % 6]};
            Arrays.sort(three);
            // A load reads each name from its tag: a new String per entry.
            for (String s : three) entries.add(new GroundField.Entry(columns[i], new String(s.toCharArray()), 10.0 + i));
        }
        return GroundField.of(columns, amounts, entries);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("| State | Count | Retained bytes | Bytes each |");
        System.out.println("|---|---|---|---|");
        for (String c : List.of("air-512", "air-4096", "air-8192")) {
            Bench.Case k = Bench.airCase(c);
            PollutionField field = k.field();
            row("PollutionField " + c, field.size(), "cubes", size(field));
            var engine = new com.wormzjl.icheme.runtime.pollution.PollutionEngine();
            engine.restore(field, PollutionLedger.EMPTY, List.of());
            row("PollutionEngine (field, ledgers, empty ground) " + c, field.size(), "cubes", size(engine));
            long before = allocated();
            Object core = engine.core();
            Object wanted = core instanceof long[] cc ? KeySets.cubeFaces(cc) : engine.wanted();
            long setBytes = allocated() - before;
            if (core instanceof long[] coreKeys) {
                // The service's cache since 2026-11-18: sorted parallel arrays, shared cells.
                var cells = new com.wormzjl.icheme.runtime.pollution.CellCache();
                var world = new com.wormzjl.icheme.runtime.pollution.CellCache.World() {
                    public boolean chunkLoaded(long cube) { return true; }
                    public Cell read(long cube) {
                        Cell c = k.environment().cell(cube);
                        return com.wormzjl.icheme.runtime.pollution.CellCache.cell((int) Math.round(c.airShare() * 4096), c.open());
                    }
                };
                cells.snapshot(coreKeys, (long[]) wanted, 0, 1200, Integer.MAX_VALUE, world, false, -4, 19);
                row("core and wanted, allocated per pass " + c, ((long[]) wanted).length, "wanted cubes", setBytes);
                row("CellCache " + c, cells.size(), "cubes", size(cells));
                long b2 = allocated();
                Object env = cells.snapshot(coreKeys, (long[]) wanted, 1, 1200, Integer.MAX_VALUE, world, false, -4, 19);
                row("CellCache.snapshot, allocated per pass " + c, ((long[]) wanted).length, "cubes", allocated() - b2);
                if (env.hashCode() == 0) System.out.print("");
                continue;
            }
            int wantedCount = wanted instanceof Collection<?> w ? w.size() : Array.getLength(wanted);
            row("core() and wanted(), allocated per pass " + c, wantedCount, "wanted cubes", setBytes);
            // The cell cache as built by PollutionService: one entry per wanted, loaded cube, a new Cell per read.
            Map<Long, Cached> cache = new HashMap<>();
            Iterable<Long> keys = wanted instanceof Collection<?> ? castLongs((Collection<?>) wanted) : longs((long[]) wanted);
            for (long key : keys) cache.put(key, new Cached(new Cell(k.environment().cell(key).airShare(), true, true), 1));
            row("cell cache as built by PollutionService " + c, cache.size(), "cubes", size(cache));
            before = allocated();
            Map<Long, Cell> environment = new HashMap<>();
            for (long key : keys) environment.put(key, cache.get(key).cell());
            Object env = new MapEnvironment(environment, Cell.UNLOADED, false, -4, 19);
            row("environment map, allocated per pass " + c, environment.size(), "cubes", allocated() - before);
            if (env.hashCode() == 0) System.out.print("");
        }
        for (int n : new int[] {1000, 16384}) {
            GroundField g = ground(n);
            row("GroundField (3 compounds a column, as loaded) " + n, n, "columns", size(g));
            row("  of which compound names", (long) n * 3, "entries", size((Object) namesOf(g)) - align(16 + 4L * n * 3));
            GroundSnapshot snap = snapshotOf(g);
            row("GroundSnapshot " + n, n, "columns", size(snap));
            var engine = new com.wormzjl.icheme.runtime.pollution.PollutionEngine();
            engine.restore(PollutionField.EMPTY, PollutionLedger.EMPTY, List.of(), g, GroundLedger.EMPTY, List.of());
            long before = allocated();
            Object wanted = engine.groundWanted();
            int wantedCount = wanted instanceof Collection<?> w ? w.size() : Array.getLength(wanted);
            row("groundWanted(), allocated per pass " + n, wantedCount, "columns", allocated() - before);
            if (wanted instanceof long[] wantedColumns) {
                var water = new com.wormzjl.icheme.runtime.pollution.WaterCache();
                var world = new com.wormzjl.icheme.runtime.pollution.WaterCache.World() {
                    public boolean chunkLoaded(long column) { return true; }
                    public boolean water(long column) { return false; }
                };
                water.snapshot(wantedColumns, 0, 1200, Integer.MAX_VALUE, world, false);
                row("WaterCache " + n, water.size(), "columns", size(water));
                long b2 = allocated();
                Object genv = water.snapshot(wantedColumns, 1, 1200, Integer.MAX_VALUE, world, false);
                row("WaterCache.snapshot, allocated per pass " + n, wantedColumns.length, "columns", allocated() - b2);
                if (genv.hashCode() == 0) System.out.print("");
                continue;
            }
            Map<Long, CachedWater> water = new HashMap<>();
            Iterable<Long> keys = wanted instanceof Collection<?> ? castLongs((Collection<?>) wanted) : longs((long[]) wanted);
            for (long key : keys) water.put(key, new CachedWater(false, 1));
            row("water cache as built by PollutionService " + n, water.size(), "columns", size(water));
            before = allocated();
            Set<Long> loaded = new HashSet<>();
            for (long key : keys) loaded.add(key);
            Object genv = GroundEnvironment.of(loaded, new HashSet<>(), false);
            row("ground environment sets, allocated per pass " + n, loaded.size(), "columns", allocated() - before);
            if (genv.hashCode() == 0) System.out.print("");
        }
        long[] mapColumns = new long[MapColumns.MAXIMUM_ENTRIES];
        int[] mapAir = new int[MapColumns.MAXIMUM_ENTRIES], mapGround = new int[MapColumns.MAXIMUM_ENTRIES];
        row("client PollutionMapStore at the answer's cap", MapColumns.MAXIMUM_ENTRIES, "columns", size(mapColumns, mapAir, mapGround));
    }

    static Iterable<Long> castLongs(Collection<?> c) {
        List<Long> out = new ArrayList<>();
        for (Object o : c) out.add((Long) o);
        return out;
    }

    static Iterable<Long> longs(long[] a) {
        List<Long> out = new ArrayList<>(a.length);
        for (long v : a) out.add(v);
        return out;
    }

    static String[] namesOf(GroundField g) {
        List<String> out = new ArrayList<>();
        for (GroundField.Entry e : g.entries()) out.add(e.name());
        // The field's own name references, as it holds them.
        try {
            Field f = GroundField.class.getDeclaredField("entryNames");
            f.setAccessible(true);
            return (String[]) f.get(g);
        } catch (ReflectiveOperationException e) {
            return out.toArray(new String[0]);
        }
    }

    static GroundSnapshot snapshotOf(GroundField g) throws Exception {
        for (var m : GroundSnapshot.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == GroundSnapshot.class && m.getParameterCount() == 2) {
                return (GroundSnapshot) m.invoke(null, g, GroundSettings.DEFAULT);
            }
        }
        throw new IllegalStateException("no GroundSnapshot factory");
    }
}
