package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.linalg.V3BandedMatrix;
import com.wormzjl.icheme.science.column.v3.linalg.V3BandedPivotedSolver;
import com.wormzjl.icheme.science.math.DeterministicMath;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The Rust column library {@code icheme_column} through JNI (batch 2026-11-13-codebase-review, Rust stage (a); the
 * layouts and status codes are in {@code src/main/rust/LAYOUT.md}). Java is the reference and the fallback: every
 * request the library refuses, and every request above the ceiling, runs on the Java path.
 *
 * <p>The ceiling {@code -Dicheme.native.column} names the highest layer the library carries: {@code off} (the
 * default: the library is not even loaded) or {@code band} (the band LU of {@link V3BandedPivotedSolver}). Later
 * stages add {@code kernel}, {@code attempt} and {@code solve}; until then those names, like any other, read as
 * {@code off} with one log line.</p>
 *
 * <p>The library comes from {@code icheme.native.path} (a file, or a folder holding it) when that is set, else from
 * the jar's {@code natives/<os>-<arch>/} folder (in place from a folder, else copied once into
 * {@code <game folder>/IChemE/natives/} under a name that carries the ABI), else from the JVM's library path. It is
 * used only after the handshake: {@code nativeAbi() == ABI}, {@code nativeFormulation()} equal to
 * {@link #FORMULATION}, and every filled slot of {@code nativeSelfTest} equal in raw bits to
 * {@link #expectedSelfTest()}. Any refusal logs one line and the Java path runs. No executable is launched.</p>
 */
public final class NativeColumnSolver {
    static final String LIBRARY = "icheme_column";
    /** The layout and signature generation; {@code icheme-jni/src/layout.rs} {@code ABI} must equal it. */
    public static final int ABI = 1;
    /** The equations the library must compute; compared with the library's own token at load. */
    static final String FORMULATION = V3ColumnLabels.FORMULATION_REVISION + ";"
            + V3ColumnLabels.WET_ASSUMPTIONS_REVISION + ";" + V3ColumnLabels.HEAT_ASSUMPTIONS_REVISION;
    static final String CEILING_PROPERTY = "icheme.native.column";
    /** {@code nativeSelfTest}'s output length, and the slots Java checks: 128 to 255 are reserved for stage (b). */
    static final int SELF_TEST_SLOTS = 256, SELF_TEST_CHECKED = 128;
    /** {@link #bandedSolve}'s answer when no library is in use; the library's own statuses are 0 to 7. */
    public static final int UNAVAILABLE = -1;
    static final int OK = 0, BAD_LAYOUT = 3, OUT_OF_MEMORY = 5, PANIC = 6, FP_ENVIRONMENT = 7;

    /** The layers the library may carry, lowest first. */
    enum Ceiling { OFF, BAND }

    private static volatile Ceiling ceiling = ceiling(System.getProperty(CEILING_PROPERTY, "off"));
    /** Set by a panic (status 6): the library stays loaded but carries nothing more this session. */
    private static volatile boolean switchedOff;
    /** The statuses already logged, one bit each, so each logs once per session. */
    private static final AtomicInteger LOGGED = new AtomicInteger();
    private static final LongAdder BAND_SOLVES = new LongAdder();

    /** Loads the library once, on first use, on whichever thread asks first (class initialization is thread safe). */
    private static final class Library {
        private static String report = "not loaded";
        static final boolean LOADED = load();

        private Library() {}
    }

    private NativeColumnSolver() {}

    /** Whether the library loaded and passed the handshake (whatever the ceiling). */
    static boolean available() { return Library.LOADED; }

    /** Where the library came from, or why it was refused. */
    static String loadReport() {
        boolean loaded = Library.LOADED;
        return (loaded ? "loaded: " : "refused: ") + Library.report;
    }

    /** One line for logs and tools: the path the column runs on. */
    static String version() {
        if (ceiling == Ceiling.OFF) return "java (icheme.native.column=off)";
        if (!Library.LOADED || switchedOff) return "java (no Rust column library)";
        return "rust " + LIBRARY + " ABI " + ABI + ", ceiling " + ceiling.name().toLowerCase(Locale.ROOT);
    }

    /** Whether {@link V3BandedPivotedSolver} hands its solves to the library: the ceiling is at least {@code band}. */
    public static boolean bandedSolveEnabled() {
        // The ceiling first, so the default (off) never loads the library. The loader's own self-test solves a band
        // system while Library initializes; on that thread LOADED reads false, so that solve runs on Java.
        return ceiling.compareTo(Ceiling.BAND) >= 0 && !switchedOff && Library.LOADED;
    }

    /**
     * One {@code nativeBandedSolve} ({@code LAYOUT.md}): {@code band} is {@code V3BandedMatrix}'s storage, the
     * outputs are written as the layout says. Returns the transport status, or {@link #UNAVAILABLE} when no library
     * is in use. A panic (6) switches the library off for the session; 3, 5, 7 and any unexpected status log once.
     * The ceiling is not consulted: the caller decides.
     */
    public static int bandedSolve(int size, int lowerBandwidth, int upperBandwidth, double[] band, double[] rhs,
            double[] solution, double[] evidence, int[] counts) {
        if (switchedOff || !Library.LOADED) return UNAVAILABLE;
        int status;
        try {
            status = nativeBandedSolve(size, lowerBandwidth, upperBandwidth, band, rhs, solution, evidence, counts);
        } catch (LinkageError missing) {
            switchedOff = true;
            log("The Rust column library has no usable nativeBandedSolve (" + missing + "); the column runs on its Java path");
            return UNAVAILABLE;
        }
        if (status == OK) {
            BAND_SOLVES.increment();
            return status;
        }
        if (status == PANIC) switchedOff = true;
        logOnce(status, switch (status) {
            case BAD_LAYOUT -> "refused a band solve's layout (status 3); that solve ran on Java";
            case OUT_OF_MEMORY -> "could not reserve a band solve's buffers (status 5); that solve ran on Java";
            case PANIC -> "panicked in a band solve (status 6); the library is off for this session";
            case FP_ENVIRONMENT -> "found subnormals flushed on a solver thread (status 7); that thread's solves run on Java";
            default -> "answered a band solve with status " + status + "; that solve ran on Java";
        });
        return status;
    }

    /** Band solves the library carried in this JVM (status 0). */
    static long bandSolves() { return BAND_SOLVES.sum(); }

    static Ceiling ceiling() { return ceiling; }

    /** Test hook: sets this JVM's ceiling and returns the previous one; production reads only the property. */
    static Ceiling ceilingForTest(Ceiling next) {
        Ceiling previous = ceiling;
        ceiling = java.util.Objects.requireNonNull(next, "next");
        return previous;
    }

    private static Ceiling ceiling(String configured) {
        String value = configured == null ? "off" : configured.trim().toLowerCase(Locale.ROOT);
        for (Ceiling known : Ceiling.values()) if (known.name().toLowerCase(Locale.ROOT).equals(value)) return known;
        log("Unknown " + CEILING_PROPERTY + "=" + configured + " (this build knows off and band); the column runs on its Java path");
        return Ceiling.OFF;
    }

    private static native int nativeAbi();
    private static native String nativeFormulation();
    private static native int nativeSelfTest(double[] out);
    private static native int nativeBandedSolve(int size, int kl, int ku, double[] band, double[] rhs,
            double[] solution, double[] evidence, int[] counts);

    /**
     * The known answers of {@code nativeSelfTest} ({@code LAYOUT.md}), computed by the Java functions the library
     * ports: slots 0 to 109 filled, 110 to 255 zero (128 to 255 are reserved for stage (b)). The band solve in slots
     * 96 to 109 runs through {@link V3BandedPivotedSolver}: on the Java path while the loader runs and whenever the
     * ceiling is below {@code band}. The generator {@code RustNativeTables} writes the same vector for
     * {@code icheme-jni/tests/data/self_test.txt}.
     */
    public static double[] expectedSelfTest() {
        double[] out = new double[SELF_TEST_SLOTS];
        double[] logArguments = {0.5, 1.0 + 0x1p-30, 2.0, 1e-300, 1e300, 0x1p-1060, 7.0, 1234.5678};
        double[] expArguments = {-745.1, -700.5, -1.0, -1e-10, 0.3, 1.0, 50.5, 709.7};
        double[] expm1Arguments = {-40.0, -1.0, -0.3, -1e-12, 1e-8, 0.4, 13.9, 300.0};
        double[] cbrtArguments = {-27.5, -1e-310, 1e-300, 0.001, 2.0, 1e10, 3.3e200, Double.MAX_VALUE};
        double[] cosArguments = {0.0, 0.5, 0.785, 2.0, -3.5, 1e6, 1e22, 6381956970095103.0 * 0x1p797};
        double[] acosArguments = {-1.0, -0.9, -0.5, -1e-20, 0.3, 0.5, 0.99, 1.0};
        for (int i = 0; i < 8; i++) {
            out[i] = DeterministicMath.log(logArguments[i]);
            out[8 + i] = DeterministicMath.exp(expArguments[i]);
            out[16 + i] = DeterministicMath.expm1(expm1Arguments[i]);
            out[24 + i] = DeterministicMath.cbrt(cbrtArguments[i]);
            out[32 + i] = DeterministicMath.cos(cosArguments[i]);
            out[40 + i] = DeterministicMath.acos(acosArguments[i]);
        }
        double[][] fma = {{0x1p-27, 0x1p-27 * (1.0 + 0x1p-52), 1.0}, {0x1p-27, 0x1p-27, 1.0}, {3.0, 0.1, -0.3},
            {1.0 + 0x1p-52, 1.0 + 0x1p-52, -(1.0 + 0x1p-51)}, {0x1p-540, 0x1p-540, 0x1p-1070}, {-2.5, 4.0, 10.0},
            {Double.MAX_VALUE, 2.0, -Double.MAX_VALUE}, {0.1, 0.1, -0.01}, {1e308, 10.0, -1e308}, {-0.0, 0.0, 0.0},
            {1.5, 2.0 / 3.0, -1.0}, {7.0, 1.0 / 7.0, -1.0}, {1e-200, 1e-200, 1e-300}, {123.456, 789.012, 0.5},
            {-1e10, 1e-10, 1.0}, {2.0, Math.nextUp(1.0), -2.0}};
        for (int i = 0; i < 16; i++) out[48 + i] = Math.fma(fma[i][0], fma[i][1], fma[i][2]);
        double[][] pow = {{2.0, 0.5}, {10.0, -3.0}, {0.9, 1000.0}, {1.0 + 0x1p-40, 1e12}, {3.7, 2.5}, {-2.0, 3.0},
            {-2.0, 0.5}, {0.0, -1.0}, {1e-300, 0.25}, {7.25, -9.75}, {1.5, 1700.0}, {0.5, 1074.5}, {2.0, 1023.5},
            {123.0, 1.0 / 3.0}, {Math.E, 708.5}, {1.0000001, 7.0e9}};
        for (int i = 0; i < 16; i++) {
            out[64 + i] = DeterministicMath.pow(pow[i][0], pow[i][1]);
            out[80 + i] = DeterministicMath.strictPow(pow[i][0], pow[i][1]);
        }
        // One band solve: size 8, kl 2, ku 1, a(r, r) = 4 + r, a(r, c) = 1 / (1 + r + 2 c) off the diagonal,
        // b(r) = 1 + r / 8.
        int size = 8, kl = 2, ku = 1;
        V3BandedMatrix matrix = new V3BandedMatrix(size, kl, ku);
        double[] rhs = new double[size];
        for (int row = 0; row < size; row++) {
            for (int column = Math.max(0, row - kl); column <= Math.min(size - 1, row + ku); column++) {
                matrix.set(row, column, column == row ? 4.0 + row : 1.0 / (1 + row + 2 * column));
            }
            rhs[row] = 1.0 + row / 8.0;
        }
        V3BandedPivotedSolver.Result.Success s = (V3BandedPivotedSolver.Result.Success) V3BandedPivotedSolver.solve(matrix, rhs);
        double[] x = s.solution();
        System.arraycopy(x, 0, out, 96, size);
        out[104] = s.backwardError();
        out[105] = s.minimumPivotMagnitude();
        out[106] = s.maximumPivotMagnitude();
        out[107] = s.pivotGrowth();
        out[108] = 0.0; // outcome Success
        out[109] = s.pivotSwaps();
        return out;
    }

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
            // Loading alone is not enough: the library must lay out, compute and round as this Java does.
            int abi = nativeAbi();
            if (abi != ABI) throw new IOException("ABI " + abi + ", expected " + ABI);
            String formulation = nativeFormulation();
            if (!FORMULATION.equals(formulation)) {
                throw new IOException("formulation " + formulation + ", expected " + FORMULATION);
            }
            double[] answers = new double[SELF_TEST_SLOTS];
            int status = nativeSelfTest(answers);
            if (status != OK) throw new IOException("self-test status " + status);
            double[] expected = expectedSelfTest();
            for (int slot = 0; slot < SELF_TEST_CHECKED; slot++) {
                if (Double.doubleToRawLongBits(answers[slot]) != Double.doubleToRawLongBits(expected[slot])) {
                    throw new IOException("self-test slot " + slot + " is " + answers[slot] + " ("
                            + Long.toHexString(Double.doubleToRawLongBits(answers[slot])) + "), Java gives "
                            + expected[slot] + " (" + Long.toHexString(Double.doubleToRawLongBits(expected[slot])) + ")");
                }
            }
        } catch (LinkageError | IOException | RuntimeException unavailable) {
            String reason = String.valueOf(unavailable.getMessage());
            if (reason.length() > 200) reason = reason.substring(0, 200) + "...";
            Library.report = platform() + " from " + source + ": " + reason;
            log("No Rust column library for " + platform() + " from " + source + " (" + reason
                    + "); the column runs on its Java path");
            return false;
        }
        Library.report = source;
        log("Rust column library ABI " + ABI + " loaded from " + source + "; ceiling "
                + ceiling.name().toLowerCase(Locale.ROOT));
        return true;
    }

    /** This platform's library from the jar as a file: in place from a folder, else copied into the game folder. */
    private static Path bundledLibrary() throws IOException {
        URL resource = NativeColumnSolver.class.getResource("/natives/" + platform() + "/" + System.mapLibraryName(LIBRARY));
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
        // The copy's name carries the ABI, so a library of another ABI (or the old C++ one) is never picked up.
        Path directory = Path.of(System.getProperty("user.dir", ".")).resolve("IChemE").resolve("natives");
        Path target = directory.resolve(System.mapLibraryName(LIBRARY + "-" + ABI));
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) return target;
        Files.createDirectories(directory);
        Path copy = Files.createTempFile(directory, LIBRARY + "-" + ABI, ".part");
        Files.write(copy, bytes);
        try {
            return Files.move(copy, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException inUse) {
            // Another running game holds an older copy (Windows locks a loaded library): load this copy instead.
            return copy;
        }
    }

    private static void logOnce(int status, String what) {
        int bit = 1 << Math.floorMod(status, 31);
        if ((LOGGED.getAndUpdate(seen -> seen | bit) & bit) == 0) log("The Rust column library " + what);
    }

    private static void log(String message) {
        System.getLogger(NativeColumnSolver.class.getName()).log(System.Logger.Level.INFO, message);
    }
}
