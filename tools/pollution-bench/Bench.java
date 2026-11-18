import com.wormzjl.icheme.science.pollution.*;
import com.wormzjl.icheme.science.pollution.Environment.Cell;
import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * Times PollutionField.step on fixed fields of about 500, 4000 and 8192 cubes, and GroundField.step on 4096 columns.
 * Each step starts from the same field, so the work per step is constant. Usage (from the worktree root, after
 * compileJava):
 *
 * <pre>java -cp build/classes/java/main tools/pollution-bench/Bench.java &lt;case&gt; [warmup s] [measure s]</pre>
 *
 * case: air-512, air-4096, air-8192, ground-4096, or counts (one step of every air case, with the environment calls
 * counted, no timing). Prints steps/s and allocated bytes per step.
 */
public class Bench {
    /** A mixed environment like the digest's, over the whole block, with an open fallback; counts its cell calls. */
    static final class Counting implements Environment {
        final Map<Long, Cell> cells;
        long calls;

        Counting(Map<Long, Cell> cells) {
            this.cells = cells;
        }

        public Cell cell(long key) {
            calls++;
            Cell c = cells.get(key);
            return c != null ? c : OPEN;
        }

        public boolean raining() {
            return false;
        }

        public int minimumY() {
            return -4;
        }

        public int maximumY() {
            return 19;
        }
    }

    static final Cell OPEN = new Cell(1, true, true);

    record Case(PollutionField field, Counting environment, List<Release> releases) {}

    /** A block of nx × ny × nz cubes, every cube above the cutoff, so the front around it is live. */
    static Case air(int nx, int ny, int nz) {
        Random random = new Random(11);
        Map<Long, Cell> cells = new HashMap<>();
        int channels = Channel.values().length;
        long[] keys = new long[nx * ny * nz];
        int n = 0;
        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    long key = CubeKey.of(x - nx / 2, y - 2, z - nz / 2);
                    keys[n++] = key;
                    double u = random.nextDouble();
                    cells.put(key, u < 0.8 ? OPEN : u < 0.92 ? new Cell(1, false, true) : u < 0.97 ? new Cell(0.4, false, true) : new Cell(0, false, true));
                }
            }
        }
        Arrays.sort(keys);
        double[] amounts = new double[keys.length * channels];
        for (int i = 0; i < keys.length; i++) {
            double r = Math.abs(CubeKey.x(keys[i])) + Math.abs(CubeKey.y(keys[i]) - 2) + Math.abs(CubeKey.z(keys[i]));
            double index = 5.0 / (1.0 + 0.1 * r * r) + 0.01;
            for (int c = 0; c < channels; c++) {
                amounts[i * channels + c] = (c == 1 && (i & 3) != 0) ? 0.0 : index * PollutionField.CUBE_AIR_MOLES * (0.5 + 0.2 * c);
            }
        }
        List<Release> releases = List.of(new Release(CubeKey.of(0, 2, 0), Channel.TOXIC, 2e5),
                new Release(CubeKey.of(1, 2, 0), Channel.PARTICULATE, 5e4));
        return new Case(PollutionField.of(keys, amounts), new Counting(cells), releases);
    }

    static Case airCase(String name) {
        return switch (name) {
            case "air-512" -> air(8, 8, 8);
            case "air-4096" -> air(16, 16, 16);
            case "air-8192" -> air(32, 16, 16);
            default -> throw new IllegalArgumentException(name);
        };
    }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    public static void main(String[] args) {
        String name = args[0];
        double warmup = args.length > 1 ? Double.parseDouble(args[1]) : 60;
        double measure = args.length > 2 ? Double.parseDouble(args[2]) : 60;
        PollutionSettings settings = PollutionSettings.DEFAULT;
        if (name.equals("counts")) {
            for (String c : List.of("air-512", "air-4096", "air-8192")) {
                Case k = airCase(c);
                k.environment().calls = 0;
                PollutionField.Result r = PollutionField.step(k.field(), PollutionLedger.EMPTY, k.releases(), k.environment(), 20.0, settings);
                System.out.printf("%s: cubes %d -> %d, cell calls per step %d%n", c, k.field().size(), r.field().size(), k.environment().calls);
            }
            return;
        }
        Runnable step;
        int size;
        if (name.startsWith("air-")) {
            Case k = airCase(name);
            size = k.field().size();
            step = () -> PollutionField.step(k.field(), PollutionLedger.EMPTY, k.releases(), k.environment(), 20.0, settings);
        } else if (name.equals("ground-4096")) {
            long[] columns = new long[4096];
            double[] amounts = new double[4096];
            List<GroundField.Entry> entries = new ArrayList<>();
            int n = 0;
            for (int x = -32; x < 32; x++) {
                for (int z = -32; z < 32; z++) {
                    columns[n++] = MapColumns.column(x, z);
                }
            }
            Arrays.sort(columns);
            for (int i = 0; i < columns.length; i++) {
                amounts[i] = GroundField.GROUND_REFERENCE * (0.05 + (i % 97) / 50.0);
                if (i % 3 == 0) {
                    entries.add(new GroundField.Entry(columns[i], "Hydrogen sulfide", 1e4 + i));
                }
            }
            GroundField field = GroundField.of(columns, amounts, entries);
            GroundEnvironment environment = GroundEnvironment.of(null, Set.of(columns[100], columns[2000]), false);
            List<GroundField.Deposit> deposits = List.of(GroundField.Deposit.of(columns[2048], 5e5));
            size = field.size();
            step = () -> GroundField.step(field, GroundLedger.EMPTY, deposits, environment, 20.0, GroundSettings.DEFAULT);
        } else {
            throw new IllegalArgumentException(name);
        }
        run(step, warmup);
        long bytes0 = allocated();
        long t0 = System.nanoTime();
        long steps = 0;
        long end = t0 + (long) (measure * 1e9);
        while (System.nanoTime() < end) {
            step.run();
            steps++;
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        long bytes = allocated() - bytes0;
        System.out.printf("%s: size %d, %d steps in %.1f s, %.1f steps/s, %.0f bytes/step%n", name, size, steps, seconds,
                steps / seconds, (double) bytes / steps);
    }

    static void run(Runnable step, double seconds) {
        long end = System.nanoTime() + (long) (seconds * 1e9);
        while (System.nanoTime() < end) {
            step.run();
        }
    }
}
