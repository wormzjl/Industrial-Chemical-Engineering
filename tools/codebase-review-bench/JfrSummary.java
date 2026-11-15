import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * B1's JFR reader (batch 2026-11-13-codebase-review, PROFILE_REVIEW.md). One recording in, one text summary out:
 * <ul>
 * <li>CPU: {@code jdk.ExecutionSample} counts by top frame, grouped by class (self time) and by method;</li>
 * <li>the time-share table: each sample to one category (see {@link #category});</li>
 * <li>allocation: {@code jdk.ObjectAllocationSample} weight by the first mod frame (class.method:line) and the allocated class;</li>
 * <li>GC: {@code jdk.GarbageCollection} count and summed duration; the window's length.</li>
 * </ul>
 * Options: {@code -Dfrom=<epoch ms>} and {@code -Dto=<epoch ms>} keep only events inside a window; {@code -Dthreads=<regex>}
 * keeps samples whose thread name matches (CPU and allocation); {@code -Drows=20}.
 * <pre>java tools/codebase-review-bench/JfrSummary.java &lt;recording.jfr&gt; [out.txt]</pre>
 */
public final class JfrSummary {
    static final long FROM = Long.getLong("from", Long.MIN_VALUE), TO = Long.getLong("to", Long.MAX_VALUE);
    static final Pattern THREADS = Pattern.compile(System.getProperty("threads", ".*"));
    static final int ROWS = Integer.getInteger("rows", 20);

    /** The JDK frames that are bookkeeping when they are the top frame: collections, streams, boxing, records, lambdas. */
    static final Pattern BOOKKEEPING = Pattern.compile("^(java\\.util\\.(?!concurrent\\.locks)[A-Za-z$.]*|java\\.lang\\.(Long|Integer|Double|Boolean|Short|Byte|Character)|java\\.lang\\.invoke\\..*|java\\.lang\\.runtime\\..*|java\\.lang\\.(String|StringBuilder|AbstractStringBuilder|StringLatin1|StringUTF16|StringConcatHelper|Record|Enum|Class)|jdk\\.internal\\.util\\..*)$");
    /** Mod categories, first match wins, tested on the first mod frame's class (simple name) and method. */
    static final List<Map.Entry<String, Pattern>> RULES = List.of(
            Map.entry("linear algebra", Pattern.compile("(?i).*(BandedPivoted|SparseLu|Lu[A-Z]|LuSolver|Linalg|Banded|Factor|DenseLu|Gauss|Pivot|Matrix|SparseNewton\\.solveLinear).*")),
            Map.entry("flash", Pattern.compile("(?i).*(Flash|TpEquilibrium|TangentPlane|Stability|PhaseEngine|NetworkPhase|RachfordRice|Equilibrium|Isenthalpic|PhSolve|UvSolve|Saturation|BubblePoint|DewPoint|PhaseIdentification|PhaseSplit).*")),
            Map.entry("thermo kernel", Pattern.compile("(?i).*(PengRobinson|Kernel|Cubic|Mixing|IdealGas|Water(Region|Properties|Steam|Share)|Excess|Virial|Fugacity|Departure|Thermo|Hydrocarbon|SpinePackage|Viscosity|Henry|Crystal|Solid(Phase|Inventory)|Eos|Property|Properties).*")),
            Map.entry("machine trains", Pattern.compile(".*(ShaftTrains|ShaftUnit|ShaftNetwork|ShaftTrain\\b|ElectricStore|Motor\\.|Gearbox|PrimeMover|PumpHealth|Wear\\.).*")),
            Map.entry("DOF ledger (bookkeeping)", Pattern.compile(".*V3DegreeOfFreedomLedger.*")),
            Map.entry("residual and Jacobian", Pattern.compile("(?i).*(Residual|Jacobian|Equation|Newton|PhaseLayout|Stage(Model|Equations)|Assembl|PassiveStep|PassiveInterval|Polish|Junction|Conservative|Transport|PipeResistance|Hydraul|Rung|Ladder|Continuation|InsideOut|Outer|Inner|Mesh|Trays?\\b|V3Column|Machine|Pump|Compressor|ShaftTrain|ShaftNetwork|Law).*")));

    static String classOf(RecordedFrame f) {
        return f.getMethod().getType().getName();
    }

    static boolean mod(RecordedFrame f) {
        return classOf(f).startsWith("com.wormzjl.");
    }

    static String simple(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }

    /** One sample's category. A JDK bookkeeping top frame is bookkeeping; otherwise the first mod frame's class and method decide. */
    static String category(RecordedStackTrace stack) {
        List<RecordedFrame> frames = stack.getFrames();
        if (frames.isEmpty()) return "other";
        RecordedFrame top = frames.getFirst();
        if (!mod(top) && BOOKKEEPING.matcher(classOf(top)).matches()) return "bookkeeping";
        for (RecordedFrame f : frames) {
            if (!mod(f)) continue;
            String cls = simple(classOf(f));
            if (cls.startsWith("DeterministicMath") || cls.startsWith("DeterministicPow") || cls.startsWith("FastMath")) continue;
            String key = cls + "." + f.getMethod().getName();
            for (var rule : RULES) if (rule.getValue().matcher(key).matches()) return rule.getKey();
            return "other mod (" + topLevel(cls) + ")";
        }
        return "other (JDK/game)";
    }

    static String topLevel(String cls) {
        int i = cls.indexOf('$');
        return i < 0 ? cls : cls.substring(0, i);
    }

    static boolean inWindow(RecordedEvent e) {
        long t = e.getStartTime().toEpochMilli();
        return t >= FROM && t <= TO;
    }

    static String threadName(RecordedEvent e, String field) {
        try {
            var t = e.getThread(field);
            return t == null ? "" : String.valueOf(t.getJavaName());
        } catch (Exception x) {
            return "";
        }
    }

    static <K> void add(Map<K, Double> map, K key, double v) {
        map.merge(key, v, Double::sum);
    }

    static List<Map.Entry<String, Double>> top(Map<String, Double> map, int n) {
        var list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list.subList(0, Math.min(n, list.size()));
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        Map<String, Double> byClass = new HashMap<>(), byMethod = new HashMap<>(), byCategory = new LinkedHashMap<>(), byThread = new HashMap<>();
        Map<String, Double> allocSite = new HashMap<>(), allocClass = new HashMap<>(), allocSiteClass = new HashMap<>(), allocThread = new HashMap<>();
        Map<String, Double> otherDetail = new HashMap<>();
        double jfrWeight = 0;
        double samples = 0, allocWeight = 0, gcCount = 0, gcMillis = 0, pauseMillis = 0;
        Instant first = null, last = null;
        double heapAfterMax = 0;
        try (var file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent e = file.readEvent();
                if (!inWindow(e)) continue;
                Instant t = e.getStartTime();
                if (first == null || t.isBefore(first)) first = t;
                if (last == null || t.isAfter(last)) last = t;
                switch (e.getEventType().getName()) {
                    case "jdk.ExecutionSample" -> {
                        String thread = threadName(e, "sampledThread");
                        if (!THREADS.matcher(thread).matches()) continue;
                        RecordedStackTrace stack = e.getStackTrace();
                        if (stack == null || stack.getFrames().isEmpty()) continue;
                        samples++;
                        RecordedFrame top = stack.getFrames().getFirst();
                        add(byClass, topLevel(classOf(top)), 1);
                        add(byMethod, classOf(top) + "." + top.getMethod().getName(), 1);
                        add(byThread, thread.replaceAll("\\d+", "#"), 1);
                        String cat = category(stack);
                        add(byCategory, cat, 1);
                        if (cat.startsWith("other")) add(otherDetail, simple(topLevel(classOf(top))) + " <- " + firstMod(stack), 1);
                    }
                    case "jdk.ObjectAllocationSample" -> {
                        String thread = threadName(e, "eventThread");
                        if (!THREADS.matcher(thread).matches()) continue;
                        double w = e.getLong("weight");
                        String type = e.getClass("objectClass") == null ? "?" : e.getClass("objectClass").getName();
                        RecordedStackTrace stack = e.getStackTrace();
                        // JFR's own start-up (its event instrumentation) carries the weight of the warm-up before it: left out.
                        if (stack != null && stack.getFrames().stream().anyMatch(fr -> classOf(fr).startsWith("jdk.jfr."))) { jfrWeight += w; continue; }
                        allocWeight += w;
                        String site = stack == null ? "?" : firstMod(stack);
                        add(allocSite, site, w);
                        add(allocClass, type, w);
                        add(allocSiteClass, site + "  [" + simple(type) + "]", w);
                        add(allocThread, thread.replaceAll("\\d+", "#"), w);
                    }
                    case "jdk.GarbageCollection" -> {
                        gcCount++;
                        gcMillis += e.getDuration().toNanos() / 1e6;
                        pauseMillis += e.getDuration("sumOfPauses").toNanos() / 1e6;
                    }
                    case "jdk.GCHeapSummary" -> {
                        if ("After GC".equals(e.getString("when"))) heapAfterMax = Math.max(heapAfterMax, e.getLong("heapUsed"));
                    }
                    default -> {}
                }
            }
        }
        double window = first == null ? 0 : (last.toEpochMilli() - first.toEpochMilli()) / 1000.0;
        var out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "recording %s%nwindow %.1f s (%s .. %s), thread filter %s%n", path.getFileName(), window, first, last, THREADS));
        out.append(String.format(Locale.ROOT, "CPU samples %.0f%nGC %.0f collections, %.0f ms total, %.0f ms pauses (%.2f %% of the window), max heap after GC %.1f MB%n",
                samples, gcCount, gcMillis, pauseMillis, window == 0 ? 0 : 100 * pauseMillis / 1000 / window, heapAfterMax / 1e6));
        out.append(String.format(Locale.ROOT, "allocation sample weight %.2f GB (%.0f MB/s), JFR start-up samples left out %.2f GB%n", allocWeight / 1e9, window == 0 ? 0 : allocWeight / 1e6 / window, jfrWeight / 1e9));
        section(out, "Threads by CPU samples", byThread, samples, "%");
        section(out, "Time-share categories", byCategory, samples, "%");
        section(out, "Top classes by self samples", byClass, samples, "%");
        section(out, "Top methods by self samples", byMethod, samples, "%");
        section(out, "'other' samples: top class <- first mod frame", otherDetail, samples, "%");
        section(out, "Allocation by first mod frame", allocSite, allocWeight, "MB");
        section(out, "Allocation by class", allocClass, allocWeight, "MB");
        section(out, "Allocation by site and class", allocSiteClass, allocWeight, "MB");
        section(out, "Allocation by thread", allocThread, allocWeight, "MB");
        System.out.print(out);
        if (args.length > 1) Files.writeString(Path.of(args[1]), out);
    }

    static String firstMod(RecordedStackTrace stack) {
        for (RecordedFrame f : stack.getFrames()) {
            if (!mod(f)) continue;
            return simple(classOf(f)) + "." + f.getMethod().getName() + ":" + f.getLineNumber();
        }
        RecordedFrame top = stack.getFrames().isEmpty() ? null : stack.getFrames().getFirst();
        return top == null ? "?" : "(no mod frame) " + classOf(top) + "." + top.getMethod().getName();
    }

    static void section(StringBuilder out, String title, Map<String, Double> map, double total, String unit) {
        out.append(String.format(Locale.ROOT, "%n## %s%n", title));
        int n = title.startsWith("Time-share") ? 100 : ROWS;
        for (var e : top(map, n)) {
            double share = total == 0 ? 0 : 100 * e.getValue() / total;
            if (unit.equals("MB")) out.append(String.format(Locale.ROOT, "%6.2f %%  %10.1f MB  %s%n", share, e.getValue() / 1e6, e.getKey()));
            else out.append(String.format(Locale.ROOT, "%6.2f %%  %8.0f  %s%n", share, e.getValue(), e.getKey()));
        }
    }
}
