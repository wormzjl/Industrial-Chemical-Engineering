package com.wormzjl.icheme.science.column.v3;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * The C++ column library through JNI. It is used by default; the Java path runs wherever it is missing.
 *
 * <p>The library comes from {@code icheme.native.path} when that is set, else from the jar's
 * {@code natives/<os>-<arch>/} folder, else from the JVM's library path. A library without the JNI adapter or of
 * another version is refused. No executable is launched.</p>
 */
final class NativeColumnSolver {
    private static final String LIBRARY = "icheme_column_solver";
    private static final String VERSION = "icheme-column-solver-cpp17";
    private static final boolean AVAILABLE = load();

    private NativeColumnSolver() {}

    static boolean available() { return AVAILABLE; }

    /**
     * The C++ Peng-Robinson outer pass of the inside-out trial: on with the library, off with
     * {@code -Dicheme.native.thermo.batch=false}.
     */
    static boolean thermoBatchEnabled() {
        return AVAILABLE && Boolean.parseBoolean(System.getProperty("icheme.native.thermo.batch", "true").trim());
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

    /** {@code <os>-<arch>}, the jar folder the build packs this platform's library into. */
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String system = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos"
                : os.startsWith("linux") ? "linux" : os.replace(' ', '_');
        String machine = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
        return system + "-" + machine;
    }

    private static boolean load() {
        String configured = System.getProperty("icheme.native.path", "").trim();
        String source = configured.isEmpty() ? "the jar" : configured;
        try {
            Path path = configured.isEmpty() ? bundledLibrary() : Path.of(configured);
            if (path != null && Files.isDirectory(path)) path = path.resolve(System.mapLibraryName(LIBRARY));
            if (path != null) {
                source = path.toAbsolutePath().toString();
                System.load(source);
            } else {
                source = "java.library.path";
                System.loadLibrary(LIBRARY);
            }
            // Loading alone is insufficient: a shared library can lack the JNI adapter or be another version.
            String version = nativeVersion();
            if (!VERSION.equals(version)) throw new IOException("version " + version + ", expected " + VERSION);
        } catch (UnsatisfiedLinkError | IOException | RuntimeException unavailable) {
            String reason = String.valueOf(unavailable.getMessage());
            if (reason.length() > 160) reason = reason.substring(0, 160) + "...";
            log("No C++ column library for " + platform() + " from " + source + " (" + reason
                    + "); the column runs on its Java path");
            return false;
        }
        log("C++ column library " + VERSION + " loaded from " + source);
        return true;
    }

    /** This platform's library from the jar as a file: in place from a folder, else copied into the game folder. */
    private static Path bundledLibrary() throws IOException {
        String name = System.mapLibraryName(LIBRARY);
        URL resource = NativeColumnSolver.class.getResource("/natives/" + platform() + "/" + name);
        if (resource == null) return null;
        if ("file".equals(resource.getProtocol())) {
            try {
                return Path.of(resource.toURI());
            } catch (URISyntaxException | IllegalArgumentException notAFile) {
                // Copied below like a library inside a jar.
            }
        }
        byte[] bytes;
        try (InputStream in = resource.openStream()) {
            bytes = in.readAllBytes();
        }
        Path directory = Path.of(System.getProperty("user.dir", ".")).resolve("IChemE").resolve("natives");
        Path target = directory.resolve(name);
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) return target;
        Files.createDirectories(directory);
        Path copy = Files.createTempFile(directory, LIBRARY, ".part");
        Files.write(copy, bytes);
        try {
            return Files.move(copy, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException inUse) {
            // Another running game holds an older copy (Windows locks a loaded library): load this copy instead.
            return copy;
        }
    }

    private static void log(String message) {
        System.getLogger(NativeColumnSolver.class.getName()).log(System.Logger.Level.INFO, message);
    }
}
