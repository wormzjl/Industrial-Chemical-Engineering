package com.wormzjl.icheme.science.column.v3.linalg;

import com.wormzjl.icheme.science.column.v3.NativeColumnSolver;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the Java side of the Rust port's band LU and self-test proofs (batch 2026-11-13-codebase-review, Rust part
 * 2a): {@code banded.txt} for {@code icheme-math/tests/banded_tables.rs} and {@code self_test.txt} for the
 * {@code icheme-jni} crate.
 *
 * <p>Band cases are regenerated, not stored: a row names the case (kind, seed, three parameters), and both this class
 * and the Rust test build the same matrix and right-hand side from SplitMix64 and exact arithmetic (a mantissa in
 * [1, 2) times a power of two). The row then holds the shape, a fingerprint of the inputs (the wrapping sum of the raw
 * bits of the band storage and the right-hand side, so a generator difference shows as itself), and every output of
 * {@link V3BandedPivotedSolver#solve}: the outcome (0 success, 1 to 5 the failures by site, 6 and 7 the two
 * {@code IllegalStateException}s, {@code iae} an {@code IllegalArgumentException}), the pivot swaps, the P8
 * counters of the workspace for this solve (or {@code -} for a solver without them), backward error, minimum and
 * maximum pivot, pivot growth, and the solution on success.
 *
 * <p>The known-answer vector of {@code nativeSelfTest} ({@code src/main/rust/LAYOUT.md}) is
 * {@link NativeColumnSolver#expectedSelfTest()}, which the loader compares bit for bit with the library's; this class
 * writes it out. {@link #specs()} gives the band cases to {@code NativeColumnSolverTest}, which replays them through
 * the library.
 *
 * <p>Run without Gradle (JDK 21), from the repository root, on the classes of a Gradle build ({@code compileJava
 * compileTestJava}) and with no {@code icheme.native.column} property, so every solve runs on the Java path. To prove
 * another solver version, compile it with {@code -cp build/classes/java/main} into a folder under {@code tmp/} and put
 * that folder first on the class path:
 * <pre>
 * java -cp build/classes/java/main;build/classes/java/test com.wormzjl.icheme.science.column.v3.linalg.RustNativeTables \
 *     src/main/rust/icheme-math/tests/data src/main/rust/icheme-jni/tests/data "label of the solver version"
 * </pre>
 */
public final class RustNativeTables {
    private RustNativeTables() {
    }

    private static final long SEED = 0x1C4E3E2026111302L;

    public static final int RANDOM = 0, DOMINANT = 1, BLOCK = 2, ZERO = 3, ZERO_COLUMN = 4, DUPLICATE_ROW = 5,
        NEAR_SINGULAR = 6, ILL_SCALING = 7, NEGATIVE_ZERO = 8, WILKINSON = 9, NON_FINITE = 10, RHS_NON_FINITE = 11;

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: RustNativeTables <banded dir> <self-test dir> <solver label>");
        }
        banded(Path.of(args[0]), args[2]);
        selfTest(Path.of(args[1]));
    }

    // ---- the case generator (the Rust twin is icheme-math/tests/banded_tables.rs) ----

    static final class Rng {
        private long state;

        Rng(long seed) {
            state = seed;
        }

        long next() {
            long z = (state += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        double unit() {
            return (next() >>> 11) * 0x1.0p-53;
        }

        /** Inclusive. */
        int intIn(int lo, int hi) {
            return lo + (int) Long.remainderUnsigned(next(), (long) hi - lo + 1L);
        }

        boolean coin() {
            return (next() & 1) != 0;
        }

        /** (1 + u) 2^e with e uniform in [eLo, eHi] (normal range), random sign; exact. */
        double entry(int eLo, int eHi) {
            int e = intIn(eLo, eHi);
            double v = (1.0 + unit()) * Double.longBitsToDouble((long) (e + 1023) << 52);
            return coin() ? -v : v;
        }
    }

    /** One case: the band storage as {@code V3BandedMatrix} lays it out, and the right-hand side. */
    public static final class Case {
        public int size, kl, ku;
        public double[] band, rhs;

        public int index(int row, int column) {
            return row * (kl + ku + 1) + column - row + kl;
        }

        public boolean inBand(int row, int column) {
            return column >= row - kl && column <= row + ku && column >= 0 && column < size;
        }

        long fingerprint() {
            long sum = 0;
            for (double v : band) sum += Double.doubleToRawLongBits(v);
            for (double v : rhs) sum += Double.doubleToRawLongBits(v);
            return sum;
        }
    }

    private static Case shaped(int size, int kl, int ku) {
        Case c = new Case();
        c.size = size;
        c.kl = kl;
        c.ku = ku;
        c.band = new double[size * (kl + ku + 1)];
        c.rhs = new double[size];
        return c;
    }

    private static void randomBand(Rng r, Case c, boolean dominant) {
        for (int row = 0; row < c.size; row++) {
            for (int column = Math.max(0, row - c.kl); column <= Math.min(c.size - 1, row + c.ku); column++) {
                double v;
                if (dominant && column == row) {
                    v = r.entry(4, 8);
                } else if ((r.next() & 3) == 0) {
                    v = 0.0;
                } else {
                    v = dominant ? r.entry(-8, 0) : r.entry(-8, 8);
                }
                c.band[c.index(row, column)] = v;
            }
        }
    }

    private static void randomRhs(Rng r, Case c, int eLo, int eHi) {
        for (int row = 0; row < c.size; row++) c.rhs[row] = (r.next() & 7) == 0 ? 0.0 : r.entry(eLo, eHi);
    }

    static Case build(int kind, long seed, int p1, int p2, int p3) {
        Rng r = new Rng(seed);
        switch (kind) {
            case RANDOM, DOMINANT, ZERO, ZERO_COLUMN, DUPLICATE_ROW, ILL_SCALING, NEGATIVE_ZERO, NON_FINITE,
                RHS_NON_FINITE -> {
                Case c = shaped(p1, p2, p3);
                if (kind != ZERO) randomBand(r, c, kind != RANDOM);
                randomRhs(r, c, -8, 8);
                switch (kind) {
                    case ZERO_COLUMN -> {
                        int column = r.intIn(0, c.size - 1);
                        for (int row = 0; row < c.size; row++) if (c.inBand(row, column)) c.band[c.index(row, column)] = 0.0;
                    }
                    case DUPLICATE_ROW -> {
                        int row = r.intIn(0, c.size - 2);
                        for (int column = 0; column < c.size; column++) {
                            if (!c.inBand(row + 1, column)) continue;
                            c.band[c.index(row + 1, column)] = c.inBand(row, column) ? c.band[c.index(row, column)] : 0.0;
                        }
                    }
                    case ILL_SCALING -> {
                        int row = r.intIn(0, c.size - 1);
                        boolean subnormal = r.coin();
                        for (int column = 0; column < c.size; column++) {
                            if (!c.inBand(row, column)) continue;
                            c.band[c.index(row, column)] = subnormal ? Double.MIN_VALUE * r.intIn(1, 1000) : r.entry(1020, 1023);
                        }
                        if (subnormal) c.rhs[row] = r.entry(900, 1000);
                    }
                    case NEGATIVE_ZERO -> {
                        for (int k = 0; k < 3; k++) {
                            int row = r.intIn(0, c.size - 1);
                            int column = Math.max(0, row - c.kl) + r.intIn(0, Math.min(c.size - 1, row + c.ku) - Math.max(0, row - c.kl));
                            c.band[c.index(row, column)] = -0.0;
                        }
                        for (int k = 0; k < 2; k++) {
                            int row = r.intIn(0, c.size - 1);
                            int column = Math.max(0, row - c.kl) + r.intIn(0, Math.min(c.size - 1, row + c.ku) - Math.max(0, row - c.kl));
                            if (column != row) c.band[c.index(row, column)] = -Double.MIN_VALUE * r.intIn(1, 4);
                        }
                    }
                    case NON_FINITE -> {
                        int row = r.intIn(0, c.size - 1);
                        double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
                        c.band[c.index(row, row)] = bad[r.intIn(0, 2)];
                    }
                    case RHS_NON_FINITE -> {
                        double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
                        c.rhs[r.intIn(0, c.size - 1)] = bad[r.intIn(0, 2)];
                    }
                    default -> {
                    }
                }
                return c;
            }
            case NEAR_SINGULAR -> {
                Case c = shaped(p1, p2, p3);
                for (int row = 0; row < c.size; row++) c.band[c.index(row, row)] = r.entry(-2, 2);
                int row = r.intIn(0, c.size - 2);
                double delta = r.intIn(1, 40) * 0x1p-45;
                c.band[c.index(row, row)] = 1.0;
                c.band[c.index(row, row + 1)] = 1.0;
                c.band[c.index(row + 1, row)] = 1.0;
                c.band[c.index(row + 1, row + 1)] = 1.0 + delta;
                randomRhs(r, c, -8, 8);
                return c;
            }
            case BLOCK -> {
                int nodes = p1, block = p2;
                int[] sizes = new int[nodes];
                int[] starts = new int[nodes + 1];
                for (int n = 0; n < nodes; n++) {
                    sizes[n] = block - r.intIn(0, Math.min(2, block - 1));
                    starts[n + 1] = starts[n] + sizes[n];
                }
                int size = starts[nodes];
                List<double[]> entries = new ArrayList<>();
                int kl = 0, ku = 0;
                double[] rowExponents = new double[size];
                for (int n = 0; n < nodes; n++) {
                    int first = starts[Math.max(0, n - 1)], last = starts[Math.min(nodes, n + 2)] - 1;
                    for (int row = starts[n]; row < starts[n + 1]; row++) {
                        int exponent = r.intIn(-20, 20);
                        rowExponents[row] = exponent;
                        for (int column = first; column <= last; column++) {
                            double v;
                            if (column == row) {
                                v = r.entry(exponent, exponent + 2);
                            } else if ((r.next() & 7) == 0) {
                                v = 0.0;
                            } else {
                                v = r.entry(exponent - 12, exponent);
                            }
                            if (v != 0.0) {
                                kl = Math.max(kl, row - column);
                                ku = Math.max(ku, column - row);
                                entries.add(new double[] {row, column, v});
                            }
                        }
                    }
                }
                Case c = shaped(size, kl, ku);
                for (double[] e : entries) c.band[c.index((int) e[0], (int) e[1])] = e[2];
                for (int row = 0; row < size; row++) {
                    int exponent = (int) rowExponents[row];
                    c.rhs[row] = (r.next() & 7) == 0 ? 0.0 : r.entry(exponent - 6, exponent + 6);
                }
                return c;
            }
            case WILKINSON -> {
                int n = p1;
                boolean perturbed = p2 != 0;
                Case c = shaped(n, n - 1, n - 1);
                for (int row = 0; row < n; row++) {
                    for (int column = 0; column < n; column++) {
                        double v = column == row || column == n - 1 ? 1.0 : column < row ? -1.0 : 0.0;
                        if (v != 0.0 && perturbed) v *= 1.0 + r.unit() * 0x1p-20;
                        c.band[c.index(row, column)] = v;
                    }
                }
                randomRhs(r, c, -4, 4);
                return c;
            }
            default -> throw new IllegalArgumentException("kind " + kind);
        }
    }

    // ---- the band table ----

    /** One row of the band table: the case's kind, seed and three parameters; {@link #build()} makes the case. */
    public record Spec(int kind, long seed, int p1, int p2, int p3) {
        public Case build() {
            return RustNativeTables.build(kind, seed, p1, p2, p3);
        }
    }

    /** The cases of {@code banded.txt}, in its order (the parameters are drawn before each case's seed). */
    public static List<Spec> specs() {
        Rng master = new Rng(SEED);
        List<Spec> specs = new ArrayList<>();
        java.util.function.BiConsumer<Integer, int[]> add =
            (kind, p) -> specs.add(new Spec(kind, master.next(), p[0], p[1], p[2]));
        for (int i = 0; i < 500; i++) add.accept(RANDOM, new int[] {master.intIn(1, 24), master.intIn(0, 7), master.intIn(0, 7)});
        for (int i = 0; i < 500; i++) add.accept(DOMINANT, new int[] {master.intIn(1, 24), master.intIn(0, 7), master.intIn(0, 7)});
        for (int i = 0; i < 20; i++) add.accept(DOMINANT, new int[] {master.intIn(1, 6), master.intIn(6, 30), master.intIn(6, 30)});
        add.accept(DOMINANT, new int[] {1, 0, 0});
        add.accept(DOMINANT, new int[] {7, 0, 0});
        for (int i = 0; i < 150; i++) add.accept(BLOCK, new int[] {master.intIn(2, 12), master.intIn(3, 12), 0});
        for (int[] large : new int[][] {{5, 41}, {42, 41}, {20, 21}, {4, 129}, {8, 129}}) add.accept(BLOCK, new int[] {large[0], large[1], 0});
        for (int i = 0; i < 10; i++) add.accept(ZERO, new int[] {master.intIn(1, 10), master.intIn(0, 3), master.intIn(0, 3)});
        for (int i = 0; i < 40; i++) add.accept(ZERO_COLUMN, new int[] {master.intIn(1, 16), master.intIn(0, 4), master.intIn(0, 4)});
        for (int i = 0; i < 40; i++) add.accept(DUPLICATE_ROW, new int[] {master.intIn(2, 16), master.intIn(1, 4), master.intIn(1, 4)});
        for (int i = 0; i < 120; i++) add.accept(NEAR_SINGULAR, new int[] {master.intIn(2, 12), master.intIn(1, 4), master.intIn(1, 4)});
        for (int i = 0; i < 40; i++) add.accept(ILL_SCALING, new int[] {master.intIn(1, 16), master.intIn(0, 4), master.intIn(0, 4)});
        for (int i = 0; i < 80; i++) add.accept(NEGATIVE_ZERO, new int[] {master.intIn(2, 16), master.intIn(1, 4), master.intIn(1, 4)});
        for (int i = 0; i < 20; i++) add.accept(NON_FINITE, new int[] {master.intIn(1, 12), master.intIn(0, 3), master.intIn(0, 3)});
        for (int i = 0; i < 20; i++) add.accept(RHS_NON_FINITE, new int[] {master.intIn(1, 12), master.intIn(0, 3), master.intIn(0, 3)});
        for (int n : new int[] {2, 5, 10, 20, 30, 35, 38, 39, 40, 45, 50}) {
            add.accept(WILKINSON, new int[] {n, 0, 0});
            add.accept(WILKINSON, new int[] {n, 1, 0});
        }
        add.accept(WILKINSON, new int[] {1026, 0, 0}); // growth 2^1025: a non-finite entry
        return specs;
    }

    private static void banded(Path out, String label) throws IOException {
        List<Spec> specs = specs();
        StringBuilder sb = new StringBuilder();
        sb.append("# icheme-math band LU table, generated by src/test/java/com/wormzjl/icheme/science/column/v3/linalg/RustNativeTables.java\n");
        sb.append("# java: V3BandedPivotedSolver.solve (").append(label).append("), V3BandedMatrix\n");
        sb.append("# columns: kind seed p1 p2 p3 | size kl ku fingerprint | outcome swaps full_width updated full_entries | ")
            .append("backward_error min_pivot max_pivot growth | solution... (hex bits; outcome 0..7 or iae)\n");
        sb.append("# jdk: ").append(System.getProperty("java.vendor")).append(' ').append(System.getProperty("java.version"))
            .append(", seed 0x").append(Long.toHexString(SEED)).append('\n');
        int[] outcomes = new int[9];
        for (Spec spec : specs) {
            Case c = spec.build();
            sb.append(spec.kind()).append(' ').append(hex(spec.seed())).append(' ').append(spec.p1()).append(' ')
                .append(spec.p2()).append(' ').append(spec.p3()).append(" | ").append(c.size).append(' ').append(c.kl).append(' ').append(c.ku).append(' ')
                .append(hex(c.fingerprint())).append(" | ");
            sb.append(solveRow(c, outcomes)).append('\n');
        }
        sb.append("# outcomes 0..7, iae: ");
        for (int k = 0; k < 9; k++) sb.append(outcomes[k]).append(k < 8 ? " " : "\n");
        Files.writeString(out.resolve("banded.txt"), sb.toString(), StandardCharsets.US_ASCII);
        System.out.print("banded: " + specs.size() + " cases; outcomes 0..7, iae:");
        for (int k = 0; k < 9; k++) System.out.print(" " + outcomes[k]);
        System.out.println(" (" + sb.length() + " bytes)");
    }

    private static String solveRow(Case c, int[] outcomes) {
        V3BandedMatrix matrix = new V3BandedMatrix(c.size, c.kl, c.ku);
        V3BandedPivotedSolver.Workspace workspace = new V3BandedPivotedSolver.Workspace();
        try {
            for (int row = 0; row < c.size; row++) {
                for (int column = Math.max(0, row - c.kl); column <= Math.min(c.size - 1, row + c.ku); column++) {
                    matrix.set(row, column, c.band[c.index(row, column)]);
                }
            }
        } catch (IllegalArgumentException e) {
            outcomes[8]++;
            return "iae";
        }
        V3BandedPivotedSolver.Result result;
        try {
            result = V3BandedPivotedSolver.solve(matrix, c.rhs, workspace);
        } catch (IllegalArgumentException e) {
            outcomes[8]++;
            return "iae";
        } catch (IllegalStateException e) {
            int code = e.getMessage().contains("non-finite entry") ? 6 : e.getMessage().contains("back-substitution") ? 7 : -1;
            if (code < 0) throw e;
            outcomes[code]++;
            return code + " - " + counters(workspace); // Java throws: no Result, no swap count
        }
        StringBuilder sb = new StringBuilder();
        if (result instanceof V3BandedPivotedSolver.Result.Success s) {
            outcomes[0]++;
            sb.append("0 ").append(s.pivotSwaps()).append(' ').append(counters(workspace)).append(" | ")
                .append(d(s.backwardError())).append(' ').append(d(s.minimumPivotMagnitude())).append(' ')
                .append(d(s.maximumPivotMagnitude())).append(' ').append(d(s.pivotGrowth())).append(" |");
            for (double v : s.solution()) sb.append(' ').append(d(v));
        } else {
            V3BandedPivotedSolver.Result.Failure f = (V3BandedPivotedSolver.Result.Failure) result;
            int code = switch (f.code()) {
                case SINGULAR -> f.detail().contains("no nonzero pivot") ? 1 : 3;
                case ILL_CONDITIONED -> f.detail().contains("scaling") ? 2 : 4;
                case BACKWARD_ERROR_EXCEEDED -> 5;
            };
            outcomes[code]++;
            sb.append(code).append(' ').append(f.pivotSwaps()).append(' ').append(counters(workspace)).append(" | ")
                .append(d(0.0)).append(' ').append(d(f.minimumPivotMagnitude())).append(' ')
                .append(d(f.maximumPivotMagnitude())).append(' ').append(d(f.pivotGrowth())).append(" |");
        }
        return sb.toString();
    }

    /** "full_width updated full_entries" of this solve's fresh workspace, or "- - -" for a solver without them. */
    private static String counters(V3BandedPivotedSolver.Workspace workspace) {
        try {
            Method solves = workspace.getClass().getMethod("fullWidthSolves");
            Method updated = workspace.getClass().getMethod("updatedEntries");
            Method full = workspace.getClass().getMethod("fullWidthEntries");
            return solves.invoke(workspace) + " " + updated.invoke(workspace) + " " + full.invoke(workspace);
        } catch (ReflectiveOperationException e) {
            return "- - -";
        }
    }

    // ---- the self-test ----

    private static void selfTest(Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# nativeSelfTest known answers, generated by NativeColumnSolver.expectedSelfTest(); columns: slot value (hex bits)\n");
        sb.append("# jdk: ").append(System.getProperty("java.vendor")).append(' ').append(System.getProperty("java.version")).append('\n');
        double[] values = NativeColumnSolver.expectedSelfTest();
        for (int i = 0; i < values.length; i++) sb.append(i).append(' ').append(d(values[i])).append('\n');
        Files.createDirectories(out);
        Files.writeString(out.resolve("self_test.txt"), sb.toString(), StandardCharsets.US_ASCII);
        System.out.println("self_test: 256 slots");
    }

    private static String hex(long bits) {
        String s = Long.toHexString(bits);
        return "0".repeat(16 - s.length()) + s;
    }

    private static String d(double x) {
        return hex(Double.doubleToRawLongBits(x));
    }
}
