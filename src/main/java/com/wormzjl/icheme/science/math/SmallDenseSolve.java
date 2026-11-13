package com.wormzjl.icheme.science.math;

/**
 * Small dense linear algebra on row-major {@code double[]} blocks, without allocation except {@link #solve3}'s result:
 * the in-place lower Cholesky factor and its two substitutions, the factor of a diagonally shifted matrix, Cramer's rule
 * for three unknowns and Gaussian elimination with partial pivoting. Moved operation for operation from the stability
 * test and the equilibrium engine (S12 of batch 2026-11-13-codebase-review), so every result keeps its bits.
 */
public final class SmallDenseSolve {
    private SmallDenseSolve() {}

    /** In-place lower Cholesky factor of the leading {@code m x m} block; false unless positive definite. */
    public static boolean cholesky(double[] a, int m) {
        for (int j = 0; j < m; j++) {
            double pivot = a[j * m + j];
            for (int k = 0; k < j; k++) pivot -= a[j * m + k] * a[j * m + k];
            if (!(pivot > 0.0) || !Double.isFinite(pivot)) return false;
            double root = Math.sqrt(pivot);
            a[j * m + j] = root;
            for (int i = j + 1; i < m; i++) {
                double value = a[i * m + j];
                for (int k = 0; k < j; k++) value -= a[i * m + k] * a[j * m + k];
                a[i * m + j] = value / root;
            }
        }
        return true;
    }

    /** Solves {@code L L^T x = rhs} in place on the factor {@link #cholesky} left. */
    public static void solveCholesky(double[] factor, int m, double[] rhs) {
        for (int i = 0; i < m; i++) {
            double value = rhs[i];
            for (int k = 0; k < i; k++) value -= factor[i * m + k] * rhs[k];
            rhs[i] = value / factor[i * m + i];
        }
        for (int i = m - 1; i >= 0; i--) {
            double value = rhs[i];
            for (int k = i + 1; k < m; k++) value -= factor[k * m + i] * rhs[k];
            rhs[i] = value / factor[i * m + i];
        }
    }

    /** Lower Cholesky factor of {@code a + mu I} into {@code factor} ({@link #cholesky}); false unless positive definite. */
    public static boolean factorShifted(double[] a, int m, double mu, double[] factor) {
        for (int j = 0; j < m; j++) {
            for (int i = j; i < m; i++) factor[i * m + j] = a[i * m + j] + (i == j ? mu : 0.0);
        }
        return cholesky(factor, m);
    }

    /** Solves the 3x3 system {@code a x = b} ({@code a} row-major) by Cramer's rule; null when it is singular. */
    public static double[] solve3(double[] a, double[] b) {
        double det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6]);
        if (!(Math.abs(det) > 0.0) || !Double.isFinite(det)) return null;
        double x0 = (b[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (b[1] * a[8] - a[5] * b[2]) + a[2] * (b[1] * a[7] - a[4] * b[2])) / det;
        double x1 = (a[0] * (b[1] * a[8] - a[5] * b[2]) - b[0] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * b[2] - b[1] * a[6])) / det;
        double x2 = (a[0] * (a[4] * b[2] - b[1] * a[7]) - a[1] * (a[3] * b[2] - b[1] * a[6]) + b[0] * (a[3] * a[7] - a[4] * a[6])) / det;
        if (!Double.isFinite(x0) || !Double.isFinite(x1) || !Double.isFinite(x2)) return null;
        return new double[] {x0, x1, x2};
    }

    /** Solves {@code a x = b} in place ({@code a} row-major {@code n x n}, overwritten) by partial pivoting; false when singular. */
    public static boolean solvePivoted(double[] a, double[] b, int n) {
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) if (Math.abs(a[n * row + col]) > Math.abs(a[n * pivot + col])) pivot = row;
            double top = a[n * pivot + col];
            if (!(Math.abs(top) > 0.0) || !Double.isFinite(top)) return false;
            if (pivot != col) {
                for (int k = 0; k < n; k++) {
                    double swap = a[n * col + k];
                    a[n * col + k] = a[n * pivot + k];
                    a[n * pivot + k] = swap;
                }
                double swap = b[col];
                b[col] = b[pivot];
                b[pivot] = swap;
            }
            for (int row = col + 1; row < n; row++) {
                double factor = a[n * row + col] / top;
                if (factor == 0.0) continue;
                for (int k = col; k < n; k++) a[n * row + k] -= factor * a[n * col + k];
                b[row] -= factor * b[col];
            }
        }
        for (int row = n - 1; row >= 0; row--) {
            double sum = b[row];
            for (int k = row + 1; k < n; k++) sum -= a[n * row + k] * b[k];
            b[row] = sum / a[n * row + row];
            if (!Double.isFinite(b[row])) return false;
        }
        return true;
    }
}
