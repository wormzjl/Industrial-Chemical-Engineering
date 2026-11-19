package com.wormzjl.icheme.science.column.v3.linalg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class V3BandedPivotedSolverTest {
    @Test
    void subnormalRowsCanBeScaledWithoutOverflowingTheirReciprocals() {
        V3BandedMatrix matrix = new V3BandedMatrix(2, 0, 0);
        matrix.set(0, 0, Double.MIN_VALUE);
        matrix.set(1, 1, 2.0 * Double.MIN_VALUE);

        V3BandedPivotedSolver.Result.Success result = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, new double[] {Double.MIN_VALUE, 2.0 * Double.MIN_VALUE}));

        assertArrayEquals(new double[] {1.0, 1.0}, result.solution());
        assertEquals(0.0, result.backwardError());
        assertEquals(Double.MIN_VALUE, matrix.get(0, 0));
    }

    @Test
    void subnormalColumnScalingAndZeroSolutionUnscalingRemainFinite() {
        V3BandedMatrix matrix = new V3BandedMatrix(2, 1, 1);
        matrix.set(0, 0, 1.0); matrix.set(0, 1, Double.MIN_VALUE);
        matrix.set(1, 0, 1.0); matrix.set(1, 1, 2.0 * Double.MIN_VALUE);

        V3BandedPivotedSolver.Result.Success result = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, new double[] {1.0, 1.0}));

        assertArrayEquals(new double[] {1.0, 0.0}, result.solution());
        assertEquals(0.0, result.backwardError());
    }

    @Test
    void independentRowAndColumnScalesRecoverTheKnownSolution() {
        int[] rowExponents = {-300, 100, 0, -100, 300};
        int[] columnExponents = {250, -250, 150, -150, 0};
        double[] expected = {1.0, -2.0, 3.0, -4.0, 5.0};
        V3BandedMatrix matrix = new V3BandedMatrix(expected.length, expected.length - 1, expected.length - 1);
        double[] rightHandSide = new double[expected.length];
        for (int row = 0; row < expected.length; row++) {
            double total = 0.0;
            for (int column = 0; column < expected.length; column++) {
                double coefficient = row == column ? 8.0 : -1.0;
                matrix.set(row, column, Math.scalb(coefficient, rowExponents[row] + columnExponents[column]));
                total += coefficient * expected[column];
            }
            rightHandSide[row] = Math.scalb(total, rowExponents[row]);
        }

        V3BandedPivotedSolver.Result.Success result = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, rightHandSide));

        double[] solution = result.solution();
        for (int column = 0; column < solution.length; column++) {
            assertEquals(expected[column], Math.scalb(solution[column], columnExponents[column]), 1.0e-12);
        }
        assertTrue(result.backwardError() <= 1.0e-12);
    }

    @Test
    void unrepresentableScaledRightHandSideReturnsATypedFailure() {
        V3BandedMatrix matrix = new V3BandedMatrix(1, 0, 0);
        matrix.set(0, 0, Double.MIN_VALUE);

        V3BandedPivotedSolver.Result.Failure result = assertInstanceOf(V3BandedPivotedSolver.Result.Failure.class,
                V3BandedPivotedSolver.solve(matrix, new double[] {1.0}));

        assertEquals(V3BandedPivotedSolver.FailureCode.ILL_CONDITIONED, result.code());
    }

    @Test
    void pivotedTridiagonalCorrectionMatchesAnIndependentDensePartialPivotSolve() {
        V3BandedMatrix matrix = new V3BandedMatrix(3, 1, 1);
        matrix.set(0, 0, 0.0); matrix.set(0, 1, 2.0);
        matrix.set(1, 0, 3.0); matrix.set(1, 1, 4.0); matrix.set(1, 2, 5.0);
        matrix.set(2, 1, 6.0); matrix.set(2, 2, 7.0);
        double[] rightHandSide = {-4.0, 10.0, 9.0};

        V3BandedPivotedSolver.Result result = V3BandedPivotedSolver.solve(matrix, rightHandSide);

        V3BandedPivotedSolver.Result.Success success = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class, result);
        assertTrue(success.pivotSwaps() > 0);
        assertArrayEquals(densePivotedSolve(toDense(matrix), rightHandSide), success.solution(), 1.0e-11);
        assertTrue(success.backwardError() <= 1.0e-12);
    }

    /**
     * The elimination updates a row only out to the last column its pivot rows can hold (P8 of batch
     * 2026-11-13-codebase-review), also where partial pivoting swaps rows and so raises that extent: random bands that are
     * not diagonally dominant give the bits of the full-width elimination, in fewer updates.
     */
    @Test
    void pivotedBandsUpdateOnlyToTheirExtentAndKeepTheFullWidthBits() {
        Random random = new Random(0xB0B5L);
        var workspace = new V3BandedPivotedSolver.Workspace();
        int swaps = 0;
        for (int size = 6; size <= 40; size += 17) {
            V3BandedMatrix matrix = new V3BandedMatrix(size, 3, 2);
            for (int row = 0; row < size; row++)
                for (int column = Math.max(0, row - 3); column <= Math.min(size - 1, row + 2); column++)
                    matrix.set(row, column, random.nextDouble() * 2.0 - 1.0);
            double[] rightHandSide = multiply(matrix, vector(size, random));
            var result = V3BandedPivotedSolver.solve(matrix, rightHandSide, workspace);
            var success = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class, result, result::toString);
            swaps += success.pivotSwaps();
            assertBitsEqual(fullWidthReference(matrix, rightHandSide), success.solution());
        }
        assertTrue(swaps > 0, "the cases pivot");
        assertEquals(0, workspace.fullWidthSolves());
        assertTrue(workspace.updatedEntries() < workspace.fullWidthEntries(),
                workspace.updatedEntries() + " of " + workspace.fullWidthEntries());
    }

    @Test
    void swappingInAShortPivotKeepsTheDisplacedRowsLongerTail() {
        var matrix = new V3BandedMatrix(6, 2, 3);
        matrix.set(0, 1, 8.0);
        matrix.set(0, 2, 4.0);
        matrix.set(1, 0, 2.0);
        matrix.set(1, 1, 1.0);
        matrix.set(2, 0, 1.0);
        matrix.set(2, 2, 8.0);
        matrix.set(2, 3, 2.0);
        for (int row = 3; row < matrix.size(); row++) matrix.set(row, row, 4.0);
        double[] rightHandSide = {28.0, 4.0, 33.0, 16.0, 20.0, 24.0};

        var workspace = new V3BandedPivotedSolver.Workspace();
        var result = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, rightHandSide, workspace));
        assertBitsEqual(fullWidthReference(matrix, rightHandSide), result.solution());
        assertEquals(1, result.pivotSwaps());
        assertEquals(2, workspace.updatedEntries());
    }

    /**
     * A negative entry that row scaling takes to -0.0 (10 and -2^-1074 on one row) sends its solve through the full fill
     * width, which keeps the sign of that zero: the bits of the full-width elimination.
     */
    @Test
    void aNegativeZeroAfterScalingTakesTheFullWidthWithItsBits() {
        V3BandedMatrix matrix = new V3BandedMatrix(4, 1, 1);
        matrix.set(0, 0, 10.0); matrix.set(0, 1, -Double.MIN_VALUE);
        matrix.set(1, 0, 3.0); matrix.set(1, 1, 1.0); matrix.set(1, 2, -2.0);
        matrix.set(2, 1, 4.0); matrix.set(2, 2, 0.5); matrix.set(2, 3, 1.0);
        matrix.set(3, 2, -1.0); matrix.set(3, 3, 2.0);
        double[] rightHandSide = {10.0, -1.0, 0.0, 3.0};
        var workspace = new V3BandedPivotedSolver.Workspace();
        var success = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, rightHandSide, workspace));
        assertEquals(1, workspace.fullWidthSolves());
        assertEquals(workspace.fullWidthEntries(), workspace.updatedEntries());
        assertBitsEqual(fullWidthReference(matrix, rightHandSide), success.solution());
    }

    private static void assertBitsEqual(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            assertEquals(Double.doubleToRawLongBits(expected[i]), Double.doubleToRawLongBits(actual[i]), "entry " + i + ": " + expected[i] + " / " + actual[i]);
    }

    /**
     * The solver's arithmetic before P8, on a dense copy and in the same order: zeros stored as +0.0, rows scaled by their
     * largest entry, columns by theirs, partial pivoting within the lower bandwidth, every candidate row updated out to
     * {@code pivot + kl + ku}, back substitution over the same width, columns unscaled.
     */
    private static double[] fullWidthReference(V3BandedMatrix matrix, double[] rightHandSide) {
        int size = matrix.size(), lower = matrix.lowerBandwidth(), upper = matrix.upperBandwidth(), fill = lower + upper;
        double[][] a = new double[size][size];
        double[] b = rightHandSide.clone();
        for (int row = 0; row < size; row++)
            for (int column = Math.max(0, row - lower); column <= Math.min(size - 1, row + upper); column++) {
                double value = matrix.get(row, column);
                a[row][column] = value == 0.0 ? 0.0 : value;
            }
        for (int row = 0; row < size; row++) {
            double maximum = 0.0;
            for (int column = Math.max(0, row - lower); column <= Math.min(size - 1, row + upper); column++) maximum = Math.max(maximum, Math.abs(a[row][column]));
            if (maximum == 0.0) continue;
            double scale = 1.0 / maximum;
            for (int column = Math.max(0, row - lower); column <= Math.min(size - 1, row + upper); column++)
                a[row][column] = Double.isFinite(scale) ? a[row][column] * scale : a[row][column] / maximum;
            b[row] = Double.isFinite(scale) ? b[row] * scale : b[row] / maximum;
        }
        double[] divisors = new double[size];
        for (int row = 0; row < size; row++)
            for (int column = Math.max(0, row - lower); column <= Math.min(size - 1, row + upper); column++)
                divisors[column] = Math.max(divisors[column], Math.abs(a[row][column]));
        for (int column = 0; column < size; column++) if (divisors[column] == 0.0) divisors[column] = 1.0;
        for (int row = 0; row < size; row++)
            for (int column = Math.max(0, row - lower); column <= Math.min(size - 1, row + upper); column++) {
                double scale = 1.0 / divisors[column];
                a[row][column] = Double.isFinite(scale) ? a[row][column] * scale : a[row][column] / divisors[column];
            }
        for (int pivot = 0; pivot < size; pivot++) {
            int lastCandidate = Math.min(size - 1, pivot + lower), pivotRow = pivot;
            double magnitude = Math.abs(a[pivot][pivot]);
            for (int row = pivot + 1; row <= lastCandidate; row++)
                if (Math.abs(a[row][pivot]) > magnitude) {
                    magnitude = Math.abs(a[row][pivot]);
                    pivotRow = row;
                }
            int lastFilled = Math.min(size - 1, pivot + fill);
            if (pivotRow != pivot) {
                for (int column = pivot; column <= lastFilled; column++) {
                    double t = a[pivot][column];
                    a[pivot][column] = a[pivotRow][column];
                    a[pivotRow][column] = t;
                }
                double t = b[pivot];
                b[pivot] = b[pivotRow];
                b[pivotRow] = t;
            }
            double diagonal = a[pivot][pivot];
            for (int row = pivot + 1; row <= lastCandidate; row++) {
                double multiplier = a[row][pivot] / diagonal;
                if (multiplier == 0.0) continue;
                a[row][pivot] = multiplier;
                for (int column = pivot + 1; column <= lastFilled; column++) a[row][column] = a[row][column] - multiplier * a[pivot][column];
                b[row] -= multiplier * b[pivot];
            }
        }
        double[] solution = new double[size];
        for (int row = size - 1; row >= 0; row--) {
            double sum = b[row];
            for (int column = row + 1; column <= Math.min(size - 1, row + fill); column++) sum -= a[row][column] * solution[column];
            solution[row] = sum / a[row][row];
        }
        for (int column = 0; column < size; column++) {
            double scale = 1.0 / divisors[column];
            solution[column] = Double.isFinite(scale) ? solution[column] * scale : solution[column] / divisors[column];
        }
        return solution;
    }

    @Test
    void deterministicRandomDiagonallyDominantBandsMatchDenseOracleAndPreserveInputs() {
        Random random = new Random(0x5EEDBA5DL);
        for (int size = 4; size <= 32; size += 7) {
            V3BandedMatrix matrix = diagonallyDominantBand(size, 2, 3, random);
            double[] expected = vector(size, random);
            double[] rightHandSide = multiply(matrix, expected);
            double[][] originalMatrix = toDense(matrix);
            double[] originalRightHandSide = rightHandSide.clone();

            V3BandedPivotedSolver.Result result = V3BandedPivotedSolver.solve(matrix, rightHandSide);

            V3BandedPivotedSolver.Result.Success success = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class, result);
            assertArrayEquals(densePivotedSolve(originalMatrix, originalRightHandSide), success.solution(), 1.0e-9);
            assertArrayEquals(expected, success.solution(), 1.0e-9);
            assertTrue(success.backwardError() <= 1.0e-12);
            assertArrayEquals(originalRightHandSide, rightHandSide);
            assertDenseEquals(originalMatrix, toDense(matrix));
        }
    }

    @Test
    void singularBandReturnsTypedFailureWithoutProducingANumericSolution() {
        V3BandedMatrix matrix = new V3BandedMatrix(3, 1, 1);
        matrix.set(0, 0, 1.0);
        matrix.set(2, 2, 1.0);

        V3BandedPivotedSolver.Result result = V3BandedPivotedSolver.solve(matrix, new double[] {1.0, 0.0, 1.0});

        V3BandedPivotedSolver.Result.Failure failure = assertInstanceOf(V3BandedPivotedSolver.Result.Failure.class, result);
        assertEquals(V3BandedPivotedSolver.FailureCode.SINGULAR, failure.code());
        assertFalse(result instanceof V3BandedPivotedSolver.Result.Success);
    }

    @Test
    void matrixRejectsWritesOutsideDeclaredBand() {
        V3BandedMatrix matrix = new V3BandedMatrix(4, 1, 1);

        assertThrows(IllegalArgumentException.class, () -> matrix.set(0, 2, 1.0));
        assertThrows(IndexOutOfBoundsException.class, () -> matrix.get(-1, 0));
    }

    private static V3BandedMatrix diagonallyDominantBand(int size, int lowerBandwidth, int upperBandwidth, Random random) {
        V3BandedMatrix matrix = new V3BandedMatrix(size, lowerBandwidth, upperBandwidth);
        for (int row = 0; row < size; row++) {
            double offDiagonalMagnitude = 0.0;
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                if (column == row) continue;
                double value = random.nextDouble(-1.0, 1.0);
                matrix.set(row, column, value);
                offDiagonalMagnitude += Math.abs(value);
            }
            matrix.set(row, row, offDiagonalMagnitude + 1.0 + random.nextDouble());
        }
        return matrix;
    }

    private static double[] vector(int length, Random random) {
        double[] values = new double[length];
        for (int index = 0; index < length; index++) values[index] = random.nextDouble(-2.0, 2.0);
        return values;
    }

    private static double[] multiply(V3BandedMatrix matrix, double[] vector) {
        double[] result = new double[matrix.size()];
        for (int row = 0; row < matrix.size(); row++) {
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                result[row] += matrix.get(row, column) * vector[column];
            }
        }
        return result;
    }

    private static double[][] toDense(V3BandedMatrix matrix) {
        double[][] dense = new double[matrix.size()][matrix.size()];
        for (int row = 0; row < matrix.size(); row++) {
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                dense[row][column] = matrix.get(row, column);
            }
        }
        return dense;
    }

    private static double[] densePivotedSolve(double[][] matrix, double[] rightHandSide) {
        int size = rightHandSide.length;
        double[][] work = new double[size][size];
        double[] right = rightHandSide.clone();
        for (int row = 0; row < size; row++) work[row] = matrix[row].clone();
        for (int pivot = 0; pivot < size; pivot++) {
            int pivotRow = pivot;
            for (int row = pivot + 1; row < size; row++) {
                if (Math.abs(work[row][pivot]) > Math.abs(work[pivotRow][pivot])) pivotRow = row;
            }
            if (Math.abs(work[pivotRow][pivot]) <= 1.0e-14) throw new AssertionError("Independent dense fixture is singular");
            if (pivotRow != pivot) {
                double[] temporaryRow = work[pivot]; work[pivot] = work[pivotRow]; work[pivotRow] = temporaryRow;
                double temporaryRight = right[pivot]; right[pivot] = right[pivotRow]; right[pivotRow] = temporaryRight;
            }
            for (int row = pivot + 1; row < size; row++) {
                double multiplier = work[row][pivot] / work[pivot][pivot];
                for (int column = pivot + 1; column < size; column++) work[row][column] -= multiplier * work[pivot][column];
                right[row] -= multiplier * right[pivot];
            }
        }
        double[] result = new double[size];
        for (int row = size - 1; row >= 0; row--) {
            double sum = right[row];
            for (int column = row + 1; column < size; column++) sum -= work[row][column] * result[column];
            result[row] = sum / work[row][row];
        }
        return result;
    }

    private static void assertDenseEquals(double[][] expected, double[][] actual) {
        for (int row = 0; row < expected.length; row++) assertArrayEquals(expected[row], actual[row]);
    }

    // Merged from V3BandedWorkspaceTest.
    @Test
    void clearingShapeChangesAndFailedSolvesCannotLeakIntoLaterCorrectionsOrEarlierResults() {
        var workspace = new V3BandedPivotedSolver.Workspace();
        var matrix = workspace.clearedMatrix(3, 1, 1);
        matrix.set(0, 1, 2);
        matrix.set(1, 0, 3);
        matrix.set(1, 1, 4);
        matrix.set(1, 2, 5);
        matrix.set(2, 1, 6);
        matrix.set(2, 2, 7);
        var saved = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, new double[] {-4, 10, 9}, workspace));
        double[] savedSolution = saved.solution();
        assertSame(matrix, workspace.clearedMatrix(3, 1, 1));
        for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++) {
            assertEquals(0L, Double.doubleToRawLongBits(matrix.get(row, column)));
        }
        assertInstanceOf(V3BandedPivotedSolver.Result.Failure.class,
                V3BandedPivotedSolver.solve(matrix, new double[3], workspace));
        matrix.set(0, 0, 2);
        matrix.set(1, 1, 3);
        matrix.set(2, 2, 4);
        var next = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, new double[] {2, 6, 12}, workspace));
        assertArrayEquals(new double[] {1, 2, 3}, next.solution());
        assertArrayEquals(savedSolution, saved.solution());
        var diagonal = workspace.clearedMatrix(2, 0, 0);
        assertNotSame(matrix, diagonal);
        diagonal.set(0, 0, Double.MIN_VALUE);
        diagonal.set(1, 1, 2 * Double.MIN_VALUE);
        var subnormal = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(diagonal, new double[] {Double.MIN_VALUE, 2 * Double.MIN_VALUE}, workspace));
        assertArrayEquals(new double[] {1, 1}, subnormal.solution());
        assertArrayEquals(savedSolution, saved.solution());
    }
}
