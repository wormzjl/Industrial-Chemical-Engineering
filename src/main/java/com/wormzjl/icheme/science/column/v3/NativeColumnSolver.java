package com.wormzjl.icheme.science.column.v3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Optional cross-platform JNI boundary for the native V3 solver. No executable is launched or required. */
final class NativeColumnSolver {
    private static final boolean AVAILABLE = load();

    private NativeColumnSolver() {}

    static boolean available() { return AVAILABLE; }

    static boolean thermoBatchEnabled() {
        return AVAILABLE && Boolean.getBoolean("icheme.native.thermo.batch");
    }

    static String version() { return AVAILABLE ? nativeVersion() : "java-fallback"; }

    static double smokeSolve() { return AVAILABLE ? nativeSmokeSolve() : Double.NaN; }

    static SolveResult solve(double[] variables, ResidualEvaluator evaluator, int maximumIterations,
            double residualTolerance, double stepTolerance) {
        if (!AVAILABLE) return SolveResult.unavailable();
        if (variables == null || evaluator == null) throw new IllegalArgumentException("native solve arguments are required");
        double[] metrics = nativeSolve(variables, maximumIterations, residualTolerance, stepTolerance, 1.0e-4,
                evaluator);
        return new SolveResult((int) metrics[0], (int) metrics[1], (int) metrics[2], metrics[3], metrics[4]);
    }

    static double[] pengRobinsonBatch(double[] criticalTemperatures, double[] criticalPressures,
            double[] acentricFactors, double[] binaryInteractions, double[] temperatures, double[] pressures,
            double[] liquidCompositions, double[] vaporCompositions) {
        if (!AVAILABLE) return null;
        return nativePrBatch(criticalTemperatures, criticalPressures, acentricFactors, binaryInteractions,
                temperatures, pressures, liquidCompositions, vaporCompositions);
    }

    /** One JNI transfer for PR values plus the forward temperature derivatives used by inside-out. */
    static double[] pengRobinsonBatchTemperatureDerivatives(double[] criticalTemperatures, double[] criticalPressures,
            double[] acentricFactors, double[] binaryInteractions, double[] temperatures, double[] pressures,
            double[] liquidCompositions, double[] vaporCompositions) {
        if (!AVAILABLE) return null;
        return nativePrBatchTemperatureDerivatives(criticalTemperatures, criticalPressures, acentricFactors,
                binaryInteractions, temperatures, pressures, liquidCompositions, vaporCompositions);
    }

    @FunctionalInterface
    interface ResidualEvaluator {
        void evaluate(double[] variables, double[] residuals);
    }

    record SolveResult(int status, int iterations, int residualEvaluations,
                       double maximumScaledResidual, double finalStepNorm) {
        static SolveResult unavailable() {
            return new SolveResult(-1, 0, 0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
    }

    private static native String nativeVersion();
    private static native double nativeSmokeSolve();
    private static native double[] nativeSolve(double[] variables, int maximumIterations,
            double residualTolerance, double stepTolerance, double armijo, ResidualEvaluator evaluator);
    private static native double[] nativePrBatch(double[] criticalTemperatures, double[] criticalPressures,
            double[] acentricFactors, double[] binaryInteractions, double[] temperatures, double[] pressures,
            double[] liquidCompositions, double[] vaporCompositions);
    private static native double[] nativePrBatchTemperatureDerivatives(double[] criticalTemperatures,
            double[] criticalPressures, double[] acentricFactors, double[] binaryInteractions, double[] temperatures,
            double[] pressures, double[] liquidCompositions, double[] vaporCompositions);

    private static boolean load() {
        try {
            String configured = System.getProperty("icheme.native.path", "").trim();
            if (!configured.isEmpty()) {
                Path path = Path.of(configured);
                if (Files.isDirectory(path)) {
                    path = findLibrary(path);
                }
                if (!Files.isRegularFile(path)) return false;
                System.load(path.toAbsolutePath().toString());
            } else {
                System.loadLibrary("icheme_column_solver");
            }
            // Loading alone is insufficient: a shared library can exist without the optional JNI adapter.
            nativeVersion();
            return true;
        } catch (UnsatisfiedLinkError unavailable) {
            return false;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static Path findLibrary(Path directory) {
        String mapped = System.mapLibraryName("icheme_column_solver");
        Path candidate = directory.resolve(mapped);
        if (Files.isRegularFile(candidate)) return candidate;
        // MinGW uses the Unix-style lib prefix for a Windows DLL.
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            candidate = directory.resolve("lib" + mapped);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return directory.resolve(mapped);
    }
}
