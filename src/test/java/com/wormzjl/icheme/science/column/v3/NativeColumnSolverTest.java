package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.wormzjl.icheme.science.column.v3.NativeColumnSolver.Ceiling;
import com.wormzjl.icheme.science.column.v3.linalg.RustNativeTables;
import com.wormzjl.icheme.science.column.v3.linalg.V3BandedMatrix;
import com.wormzjl.icheme.science.column.v3.linalg.V3BandedPivotedSolver;
import com.wormzjl.icheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The Rust column library {@code icheme_column} (batch 2026-11-13-codebase-review, Rust stage (a)): its handshake, its
 * band solve bit for bit against the Java body of {@link V3BandedPivotedSolver}, and a column that publishes the same
 * bits with the band solve in the library. Every test skips where no library is built (no cargo); where the build
 * packed one ({@code icheme.native.packaged}) or {@code icheme.native.path} names one, a library that does not load or
 * fails its handshake is a failure. {@code ./gradlew nativeSmoke} runs this class on {@code build/native/cargo/release}.
 *
 * <p>The tests switch the ceiling with the package-private hook {@link NativeColumnSolver#ceilingForTest}, so the Java
 * reference ({@code off}) and the library ({@code band}) run in one JVM; each restores the ceiling it found.</p>
 */
class NativeColumnSolverTest {
    /** A library must load where the build packed one or a path names one; elsewhere the test skips. */
    private static void assumeLibrary() {
        boolean expected = Boolean.getBoolean("icheme.native.packaged")
                || !System.getProperty("icheme.native.path", "").isBlank();
        if (expected) assertTrue(NativeColumnSolver.available(), "a Rust column library was expected: " + NativeColumnSolver.loadReport());
        assumeTrue(NativeColumnSolver.available(), "No Rust column library for " + NativeColumnSolver.platform()
                + " (" + NativeColumnSolver.loadReport() + "): build it with ./gradlew nativeBuild (needs cargo)");
    }

    private static <T> T withCeiling(Ceiling ceiling, Supplier<T> body) {
        Ceiling previous = NativeColumnSolver.ceilingForTest(ceiling);
        try {
            return body.get();
        } finally {
            NativeColumnSolver.ceilingForTest(previous);
        }
    }

    private static Object invokeNative(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = NativeColumnSolver.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    /** The three handshake steps the loader takes, each checked on its own: ABI, formulation token, self-test bits. */
    @Test
    void theLibraryPassesItsHandshake() throws Exception {
        assumeLibrary();
        assertEquals(NativeColumnSolver.ABI, invokeNative("nativeAbi", new Class<?>[0]));
        assertEquals(V3ColumnCalculator.FORMULATION_REVISION + ";" + V3ColumnCalculator.WET_ASSUMPTIONS_REVISION + ";"
                + V3ColumnCalculator.HEAT_ASSUMPTIONS_REVISION, invokeNative("nativeFormulation", new Class<?>[0]));
        double[] answers = new double[NativeColumnSolver.SELF_TEST_SLOTS];
        assertEquals(0, invokeNative("nativeSelfTest", new Class<?>[] {double[].class}, (Object) answers));
        double[] expected = withCeiling(Ceiling.OFF, NativeColumnSolver::expectedSelfTest);
        for (int slot = 0; slot < NativeColumnSolver.SELF_TEST_SLOTS; slot++) {
            assertEquals(Long.toHexString(Double.doubleToRawLongBits(expected[slot])),
                    Long.toHexString(Double.doubleToRawLongBits(answers[slot])), "self-test slot " + slot);
        }
        assertTrue(NativeColumnSolver.loadReport().startsWith("loaded: "), NativeColumnSolver.loadReport());
    }

    /** Everything one solve gives: outcome, swaps, evidence and solution bits, and the P8 counters of its workspace. */
    private record Solve(boolean refused, int outcome, int swaps, long[] evidence, long[] solution, String detail,
            int fullWidthSolves, long updatedEntries, long fullWidthEntries) {
        static Solve refusedInput() { return new Solve(true, -1, -1, null, null, null, 0, 0, 0); }

        /** The fields the layout defines for this outcome (1 and 2 carry no evidence; 6 and 7 throw). */
        String comparable() {
            if (refused) return "refused";
            StringBuilder sb = new StringBuilder().append(outcome).append(" counters ").append(fullWidthSolves)
                    .append(' ').append(updatedEntries).append(' ').append(fullWidthEntries);
            if (outcome == 0 || (outcome >= 3 && outcome <= 5)) {
                sb.append(" swaps ").append(swaps).append(" evidence");
                for (int k = outcome == 0 ? 0 : 1; k < 4; k++) sb.append(' ').append(Long.toHexString(evidence[k]));
            }
            if (outcome == 0) {
                sb.append(" solution");
                for (long bits : solution) sb.append(' ').append(Long.toHexString(bits));
            }
            if (detail != null) sb.append(" | ").append(detail);
            return sb.toString();
        }

        /** {@link #comparable()} without the Java message: the library returns codes, the hook adds the text. */
        String withoutDetail() {
            return new Solve(refused, outcome, swaps, evidence, solution, null, fullWidthSolves, updatedEntries,
                    fullWidthEntries).comparable();
        }
    }

    private static long[] bits(double... values) {
        long[] bits = new long[values.length];
        for (int k = 0; k < values.length; k++) bits[k] = Double.doubleToRawLongBits(values[k]);
        return bits;
    }

    /** One {@link V3BandedPivotedSolver#solve} of the case on a fresh workspace, at whatever ceiling is set. */
    private static Solve solverSolve(RustNativeTables.Case c) {
        V3BandedMatrix matrix = new V3BandedMatrix(c.size, c.kl, c.ku);
        try {
            for (int row = 0; row < c.size; row++) {
                for (int column = Math.max(0, row - c.kl); column <= Math.min(c.size - 1, row + c.ku); column++) {
                    matrix.set(row, column, c.band[c.index(row, column)]);
                }
            }
        } catch (IllegalArgumentException nonFinite) {
            return Solve.refusedInput();
        }
        V3BandedPivotedSolver.Workspace workspace = new V3BandedPivotedSolver.Workspace();
        V3BandedPivotedSolver.Result result;
        try {
            result = V3BandedPivotedSolver.solve(matrix, c.rhs, workspace);
        } catch (IllegalArgumentException nonFiniteRightHandSide) {
            return Solve.refusedInput();
        } catch (IllegalStateException thrown) {
            int outcome = thrown.getMessage().contains("non-finite entry") ? 6 : 7;
            return new Solve(false, outcome, -1, null, null, thrown.getMessage(), workspace.fullWidthSolves(),
                    workspace.updatedEntries(), workspace.fullWidthEntries());
        }
        if (result instanceof V3BandedPivotedSolver.Result.Success s) {
            return new Solve(false, 0, s.pivotSwaps(), bits(s.backwardError(), s.minimumPivotMagnitude(),
                    s.maximumPivotMagnitude(), s.pivotGrowth()), bits(s.solution()), null, workspace.fullWidthSolves(),
                    workspace.updatedEntries(), workspace.fullWidthEntries());
        }
        V3BandedPivotedSolver.Result.Failure f = (V3BandedPivotedSolver.Result.Failure) result;
        int outcome = switch (f.code()) {
            case SINGULAR -> f.detail().contains("no nonzero pivot") ? 1 : 3;
            case ILL_CONDITIONED -> f.detail().contains("scaling") ? 2 : 4;
            case BACKWARD_ERROR_EXCEEDED -> 5;
        };
        return new Solve(false, outcome, f.pivotSwaps(), bits(0.0, f.minimumPivotMagnitude(), f.maximumPivotMagnitude(),
                f.pivotGrowth()), null, f.detail(), workspace.fullWidthSolves(), workspace.updatedEntries(),
                workspace.fullWidthEntries());
    }

    /** One {@code nativeBandedSolve} of the case through {@link NativeColumnSolver#bandedSolve}; null on status 3. */
    private static Solve librarySolve(RustNativeTables.Case c, String where) {
        double[] solution = new double[c.size];
        double[] evidence = new double[4];
        int[] counts = new int[7];
        int status = NativeColumnSolver.bandedSolve(c.size, c.kl, c.ku, c.band.clone(), c.rhs.clone(), solution,
                evidence, counts);
        if (status == 3) return Solve.refusedInput();
        assertEquals(0, status, where + ": transport status");
        long updated = ((long) counts[4] << 32) | (counts[3] & 0xffffffffL);
        long full = ((long) counts[6] << 32) | (counts[5] & 0xffffffffL);
        return new Solve(false, counts[0], counts[1], bits(evidence), counts[0] == 0 ? bits(solution) : null, null,
                counts[2], updated, full);
    }

    /**
     * The 1 570 cases of {@code banded.txt} (RustNativeTables' generator: random, dominant, column-shaped block,
     * zero, singular, near-singular, ill-scaled, -0.0, Wilkinson up to size 1 026, non-finite), three ways: the Java
     * body (ceiling off), the library called directly, and the solver with the ceiling at band. All three give the
     * same bits and counters; a refused input (a non-finite entry or right-hand side) is status 3 in the library and an
     * {@code IllegalArgumentException} in Java, at either ceiling.
     */
    @Test
    void theLibrarySolvesEveryTableCaseWithTheJavaBits() {
        assumeLibrary();
        int[] outcomes = new int[9];
        int compared = 0;
        long carriedBefore = NativeColumnSolver.bandSolves();
        for (RustNativeTables.Spec spec : RustNativeTables.specs()) {
            RustNativeTables.Case c = spec.build();
            String where = spec.toString();
            Solve reference = withCeiling(Ceiling.OFF, () -> solverSolve(c));
            Solve direct = librarySolve(c, where);
            Solve hooked = withCeiling(Ceiling.BAND, () -> solverSolve(c));
            // The library answers in codes; the hook adds Java's messages, so the direct call compares without them.
            assertEquals(reference.withoutDetail(), direct.comparable(), where + ": the library called directly");
            assertEquals(reference.comparable(), hooked.comparable(), where + ": the solver with the ceiling at band");
            outcomes[reference.refused() ? 8 : reference.outcome()]++;
            compared++;
        }
        System.out.println("NativeColumnSolverTest band cases " + compared + ", outcomes 0..7 and refused "
                + Arrays.toString(outcomes) + ", library solves " + (NativeColumnSolver.bandSolves() - carriedBefore));
        assertEquals(1_570, compared);
        for (int outcome : new int[] {0, 1, 2, 3, 4, 5, 6, 8}) {
            assertTrue(outcomes[outcome] > 0, "the table no longer reaches outcome " + outcome + ": " + Arrays.toString(outcomes));
        }
    }

    /** A call whose arrays do not match its header is refused (status 3) before any index, and the library stays on. */
    @Test
    void theLibraryRefusesAMalformedCall() {
        assumeLibrary();
        int size = 4, kl = 1, ku = 1;
        double[] band = new double[size * (kl + ku + 1)];
        for (int row = 0; row < size; row++) band[row * (kl + ku + 1) + kl] = 4.0 + row;
        double[] rhs = {1.0, 2.0, 3.0, 4.0};
        assertEquals(3, call(size, kl, ku, new double[band.length - 1], rhs, new double[size], new double[4], new int[7]), "short band");
        assertEquals(3, call(size, kl, ku, null, rhs, new double[size], new double[4], new int[7]), "null band");
        assertEquals(3, call(size, kl, ku, band, null, new double[size], new double[4], new int[7]), "null rhs");
        assertEquals(3, call(size, kl, ku, band, rhs, null, new double[4], new int[7]), "null solution");
        assertEquals(3, call(size, kl, ku, band, rhs, new double[size], null, new int[7]), "null evidence");
        assertEquals(3, call(size, kl, ku, band, rhs, new double[size], new double[4], null), "null counts");
        assertEquals(3, call(size, -1, ku, band, rhs, new double[size], new double[4], new int[7]), "negative kl");
        assertEquals(3, call(size, kl, -1, band, rhs, new double[size], new double[4], new int[7]), "negative ku");
        assertEquals(3, call(0, kl, ku, new double[0], new double[0], new double[0], new double[4], new int[7]), "size 0");
        assertEquals(3, call(size, kl, ku, band, new double[size - 1], new double[size], new double[4], new int[7]), "short rhs");
        assertEquals(3, call(size, kl, ku, band, rhs, new double[size + 1], new double[4], new int[7]), "long solution");
        assertEquals(3, call(size, kl, ku, band, rhs, new double[size], new double[3], new int[7]), "short evidence");
        assertEquals(3, call(size, kl, ku, band, rhs, new double[size], new double[4], new int[6]), "short counts");
        double[] nan = band.clone();
        nan[kl] = Double.NaN;
        assertEquals(3, call(size, kl, ku, nan, rhs, new double[size], new double[4], new int[7]), "NaN entry");
        assertEquals(3, call(size, kl, ku, band, new double[] {1.0, Double.POSITIVE_INFINITY, 3.0, 4.0},
                new double[size], new double[4], new int[7]), "infinite rhs");
        double[] solution = new double[size];
        int[] counts = new int[7];
        assertEquals(0, call(size, kl, ku, band, rhs, solution, new double[4], counts), "a good call after the refusals");
        assertEquals(0, counts[0]);
        assertArrayEquals(new double[] {1.0 / 4.0, 2.0 / 5.0, 3.0 / 6.0, 4.0 / 7.0}, solution, 1.0e-15);
    }

    private static int call(int size, int kl, int ku, double[] band, double[] rhs, double[] solution, double[] evidence,
            int[] counts) {
        return NativeColumnSolver.bandedSolve(size, kl, ku, band, rhs, solution, evidence, counts);
    }

    /**
     * A four-tray dry crude column (a dry cold solve on the classical chain) publishes the same bits, products,
     * duties and diagnostics, with the ceiling off and at band, and the library carried band solves in the second.
     */
    @Test
    void aColumnPublishesTheSameBitsWithTheBandSolveInTheLibrary() {
        assumeLibrary();
        V3CrudeFeed crude = V3TestInputs.mediumCrudeThermo()
                .crudeFeed(V3TestInputs.MEDIUM_CRUDE_ASSAY);
        double[] flows = crude.moleFractions();
        for (int component = 0; component < flows.length; component++) flows[component] *= 2_610.7 * 1_000.0 / 3_600.0;
        V3ColumnInput input = V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 4, 3, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
        V3ColumnOutcome.Success reference = withCeiling(Ceiling.OFF,
                () -> assertInstanceOf(V3ColumnOutcome.Success.class, V3TestInputs.calculate(input)));
        long before = NativeColumnSolver.bandSolves();
        V3ColumnOutcome.Success rust = withCeiling(Ceiling.BAND,
                () -> assertInstanceOf(V3ColumnOutcome.Success.class, V3TestInputs.calculate(input)));
        long carried = NativeColumnSolver.bandSolves() - before;
        System.out.println("NativeColumnSolverTest column: the library carried " + carried + " band solves");
        assertTrue(carried > 0, "the library carried no band solve");
        assertEquals(published(reference), published(rust));
        assertEquals(String.valueOf(reference.diagnostics()), String.valueOf(rust.diagnostics()));
    }

    /** The published result as text: every double by its raw bits, the records by their own text (exact doubles). */
    private static String published(V3ColumnOutcome.Success success) {
        StringBuilder sb = new StringBuilder();
        for (V3ColumnStreamProperties stream : success.result().streams()) {
            sb.append(stream.streamId()).append(' ').append(stream.phase()).append(' ')
                    .append(Arrays.toString(bits(stream.molarFlowMolPerSecond(), stream.massFlowKgPerSecond(),
                            stream.temperatureKelvin(), stream.pressurePascal(), stream.vaporMoleFraction())));
            for (V3ColumnStreamProperties.ComponentFraction fraction : stream.moleFractions()) {
                sb.append(' ').append(fraction.componentId()).append('=')
                        .append(Arrays.toString(bits(fraction.moleFraction(), fraction.massFraction())));
            }
            sb.append('\n');
        }
        sb.append(success.result().dutyLedger()).append('\n').append(success.result().trayHydraulics()).append('\n')
                .append(success.result().convergenceEvidence()).append('\n').append(success.result().acceptanceAudit());
        return sb.toString();
    }
}
