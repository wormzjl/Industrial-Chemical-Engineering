package com.wormzjl.icheme.science.math;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.DoubleStream;

/**
 * Writes the bit tables that prove the Rust crate {@code icheme-math} (src/main/rust/icheme-math) bitwise equal to the
 * Java mathematics (batch 2026-11-13-codebase-review, Rust stage (a) part 1). One table per function; each row holds
 * the raw bits of the arguments and of Java's result in lowercase hexadecimal (double and long: 16 digits, int: 8),
 * "iae" where Java throws {@code IllegalArgumentException}. A table longer than 900 000 bytes is split into
 * {@code name-1.txt}, {@code name-2.txt}, ... Lines starting with '#' are comments. The Rust test
 * ({@code icheme-math/tests/bit_tables.rs}) reads them and compares bit for bit.
 *
 * <p>Arguments: the special values (zeros, ones, infinities, NaNs, subnormals, the extreme normals), the branch
 * boundaries of each fdlibm routine (the high-word constants of its source, with neighbouring words), and 5 000
 * pseudo-random arguments per function ({@link #RANDOM_ROWS}) from SplitMix64 with a fixed seed, spread over the exponent
 * range or over the function's domain.
 *
 * <p>Run without Gradle (JDK 21), from the repository root:
 * <pre>
 * javac -d tmp/rust-a1/classes src/main/java/com/wormzjl/icheme/science/math/DeterministicMath.java \
 *     src/main/java/com/wormzjl/icheme/science/math/DeterministicPow.java \
 *     src/main/java/com/wormzjl/icheme/science/math/FdlibmPow.java \
 *     src/test/java/com/wormzjl/icheme/science/math/RustBitTables.java
 * java -cp tmp/rust-a1/classes com.wormzjl.icheme.science.math.RustBitTables src/main/rust/icheme-math/tests/data
 * </pre>
 */
public final class RustBitTables {
    private RustBitTables() {
    }

    private static final long SEED = 0x1C4E3E2026111300L;
    /** Random rows per table: the loop counts below are written for 20 000 and scaled to this by r(). */
    private static final int RANDOM_ROWS = 5_000;
    private static final int MAX_FILE_BYTES = 900_000;
    private static long state = SEED;

    private static final double NAN = Double.longBitsToDouble(0x7ff8000000000000L);
    private static final double[] SPECIAL = {
        0.0, -0.0, 1.0, -1.0, 2.0, -2.0, 0.5, -0.5, 3.0, -3.0, 10.0, -10.0, 0.1, -0.1,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        NAN, Double.longBitsToDouble(0xfff8000000000000L), Double.longBitsToDouble(0x7ff8000000000001L),
        Double.longBitsToDouble(0x7ff0000000000001L), Double.longBitsToDouble(0xfff7ffffffffffffL),
        Double.MIN_VALUE, -Double.MIN_VALUE, 2 * Double.MIN_VALUE, Double.longBitsToDouble(0x000fffffffffffffL),
        -Double.longBitsToDouble(0x000fffffffffffffL), Double.longBitsToDouble(0x0008000000000000L),
        Double.MIN_NORMAL, -Double.MIN_NORMAL, Math.nextUp(Double.MIN_NORMAL),
        Double.MAX_VALUE, -Double.MAX_VALUE, Math.nextDown(Double.MAX_VALUE),
        Math.nextUp(1.0), Math.nextDown(1.0), -Math.nextUp(1.0), -Math.nextDown(1.0),
        Math.PI, -Math.PI, Math.E, 1e-300, -1e-300, 1e300, -1e300, 0x1p-1022, 0x1p1023, 0x1p-537, 0x1p512,
    };

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: RustBitTables <output directory>");
        }
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        unary(out, "log", "StrictMath.log (DeterministicMath.log), fdlibm e_log.c", logArguments(), DeterministicMath::log);
        unary(out, "exp", "StrictMath.exp (DeterministicMath.exp), fdlibm e_exp.c", expArguments(), DeterministicMath::exp);
        unary(out, "expm1", "StrictMath.expm1 (DeterministicMath.expm1), fdlibm s_expm1.c", expm1Arguments(),
            DeterministicMath::expm1);
        unary(out, "cbrt", "StrictMath.cbrt (DeterministicMath.cbrt), fdlibm s_cbrt.c", cbrtArguments(),
            DeterministicMath::cbrt);
        unary(out, "cos", "StrictMath.cos (DeterministicMath.cos), fdlibm s_cos.c k_cos.c k_sin.c e_rem_pio2.c "
            + "k_rem_pio2.c", cosArguments(), DeterministicMath::cos);
        unary(out, "acos", "StrictMath.acos (DeterministicMath.acos), fdlibm e_acos.c", acosArguments(),
            DeterministicMath::acos);
        pow(out);
        powTables(out);
        fma(out);
        maxMin(out);
        javaUnary(out);
        scalb(out);
        clamp(out);
        clampInt(out);
        sum(out);
    }

    // ---- the generator ----

    private static int r(int rowsAtTwentyThousand) {
        return rowsAtTwentyThousand * RANDOM_ROWS / 20_000;
    }

    private static long next() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit() {
        return (next() >>> 11) * 0x1.0p-53;
    }

    private static double uniform(double a, double b) {
        return a + (b - a) * unit();
    }

    /** Inclusive. */
    private static int intIn(int lo, int hi) {
        return lo + (int) Long.remainderUnsigned(next(), (long) hi - lo + 1L);
    }

    private static boolean coin() {
        return (next() & 1) != 0;
    }

    /** Exponent uniform in [eLo, eHi] (below -1022: subnormal), random 52-bit mantissa, random sign if signed. */
    private static double spread(int eLo, int eHi, boolean signed) {
        int e = intIn(eLo, eHi);
        double m = Double.longBitsToDouble(0x3ff0000000000000L | (next() & 0x000fffffffffffffL));
        double v = Math.scalb(m, e);
        return signed && coin() ? -v : v;
    }

    private static double anyBits() {
        return Double.longBitsToDouble(next());
    }

    private static double words(int hi, int lo) {
        return Double.longBitsToDouble(((long) hi << 32) | (lo & 0xffffffffL));
    }

    /** Doubles whose high word is hi - 1, hi, hi + 1, with several low words, and their negatives if signed. */
    private static void aroundHigh(List<double[]> list, int hi, boolean signed) {
        int[] lows = {0, 1, 0x7fffffff, 0x80000000, 0xfffffffe, 0xffffffff, (int) next()};
        for (int d = -1; d <= 1; d++) {
            for (int lo : lows) {
                double v = words(hi + d, lo);
                list.add(new double[] {v});
                if (signed) {
                    list.add(new double[] {-v});
                }
            }
        }
    }

    /** v and its neighbours within k ulps. */
    private static void aroundValue(List<double[]> list, double v, int k) {
        list.add(new double[] {v});
        double up = v, down = v;
        for (int i = 0; i < k; i++) {
            up = Math.nextUp(up);
            down = Math.nextDown(down);
            list.add(new double[] {up});
            list.add(new double[] {down});
        }
    }

    private static List<double[]> specials() {
        List<double[]> list = new ArrayList<>();
        for (double s : SPECIAL) {
            list.add(new double[] {s});
        }
        return list;
    }

    // ---- arguments of the fdlibm functions ----

    private static List<double[]> logArguments() {
        List<double[]> list = specials();
        for (int hi : new int[] {0x00100000, 0x7ff00000, 0x3ff00000, 0x3fe00000}) {
            aroundHigh(list, hi, true);
        }
        // the mantissa-word boundaries of e_log.c: sqrt(2) split (hx + 0x95f64), |f| < 2^-20, i = hx - 0x6147a, j = 0x6b851 - hx
        int[] mantissaWords = {0x6a09b, 0x6a09c, 0x6a09d, 0xffffd, 0xffffe, 0xfffff, 0x00000, 0x00001, 0x00002,
            0x6147a - 1, 0x6147a, 0x6147a + 1, 0x6b851 - 1, 0x6b851, 0x6b851 + 1};
        int[] exponents = {1, 2, 1022, 1023, 1024, 1025, 2046};
        int[] lows = {0, 1, 0xffffffff, (int) next()};
        for (int e : exponents) {
            for (int m : mantissaWords) {
                for (int lo : lows) {
                    list.add(new double[] {words((e << 20) | m, lo)});
                }
            }
        }
        for (int k = -1074; k <= 1023; k++) {
            list.add(new double[] {Math.scalb(1.0, k)});
        }
        aroundValue(list, 1.0, 64);
        aroundValue(list, 2.0, 8);
        aroundValue(list, Math.sqrt(2.0), 8);
        for (int i = 0; i < r(10_000); i++) {
            list.add(new double[] {spread(-1074, 1023, false)});
        }
        for (int i = 0; i < r(5_000); i++) {
            list.add(new double[] {uniform(0.5, 2.0)});
        }
        for (int i = 0; i < r(3_000); i++) {
            list.add(new double[] {1.0 + spread(-60, -20, true)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {anyBits()});
        }
        return list;
    }

    private static List<double[]> expArguments() {
        List<double[]> list = specials();
        for (int hi : new int[] {0x40862E42, 0x3fd62e42, 0x3FF0A2B2, 0x3e300000, 0x40874910, 0x7ff00000}) {
            aroundHigh(list, hi, true);
        }
        aroundValue(list, 7.09782712893383973096e+02, 16); // o_threshold
        aroundValue(list, -7.45133219101941108420e+02, 16); // u_threshold
        aroundValue(list, -1021 * 0.6931471805599453, 8); // k = -1021, the subnormal-result switch
        aroundValue(list, -708.3964185322641, 8); // exp gives MIN_NORMAL
        for (int k = -1100; k <= 1030; k++) {
            list.add(new double[] {k * 0.34657359027997264}); // multiples of ln2/2
        }
        for (int k = -746; k <= 710; k++) {
            list.add(new double[] {k});
        }
        for (int i = 0; i < r(12_000); i++) {
            list.add(new double[] {uniform(-746.0, 710.0)});
        }
        for (int i = 0; i < r(6_000); i++) {
            list.add(new double[] {spread(-60, 10, true)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {anyBits()});
        }
        return list;
    }

    private static List<double[]> expm1Arguments() {
        List<double[]> list = specials();
        for (int hi : new int[] {0x4043687A, 0x40862E42, 0x3fd62e42, 0x3FF0A2B2, 0x3c900000, 0x7ff00000}) {
            aroundHigh(list, hi, true);
        }
        aroundValue(list, 7.09782712893383973096e+02, 16);
        aroundValue(list, 0.6931471805599453 - 0.25, 8); // k = 1 with x < -0.25 after the reduction
        aroundValue(list, 20 * 0.6931471805599453, 8); // k = 20
        aroundValue(list, 19.5 * 0.6931471805599453, 8);
        aroundValue(list, 56.5 * 0.6931471805599453, 8); // k = 56 / 57
        aroundValue(list, -1.5 * 0.6931471805599453, 8); // k = -1 / -2
        for (int k = -200; k <= 1030; k++) {
            list.add(new double[] {k * 0.34657359027997264});
        }
        for (int i = 0; i < r(10_000); i++) {
            list.add(new double[] {uniform(-40.0, 45.0)});
        }
        for (int i = 0; i < r(6_000); i++) {
            list.add(new double[] {spread(-60, 9, true)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {uniform(-750.0, 712.0)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {anyBits()});
        }
        return list;
    }

    private static List<double[]> cbrtArguments() {
        List<double[]> list = specials();
        for (int hi : new int[] {0x00100000, 0x7ff00000, 0x3ff00000, 0x00000001}) {
            aroundHigh(list, hi, true);
        }
        for (int k = 1; k <= 1000; k++) {
            double c = (double) k * k * k;
            list.add(new double[] {c});
            list.add(new double[] {-c});
        }
        for (int k = -358; k <= 341; k++) {
            list.add(new double[] {Math.scalb(1.0, 3 * k)});
            list.add(new double[] {-Math.scalb(1.0, 3 * k + 1)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {spread(-1074, -1023, true)}); // subnormals
        }
        for (int i = 0; i < r(14_000); i++) {
            list.add(new double[] {spread(-1074, 1023, true)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {uniform(-10.0, 10.0)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {anyBits()});
        }
        return list;
    }

    private static List<double[]> cosArguments() {
        List<double[]> list = specials();
        // s_cos.c, k_cos.c, k_sin.c, e_rem_pio2.c boundaries
        for (int hi : new int[] {0x3fe921fb, 0x3e400000, 0x3FD33333, 0x3fe90000, 0x4002d97c, 0x3ff921fb, 0x413921fb,
            0x7ff00000, 0x3e300000}) {
            aroundHigh(list, hi, true);
        }
        int[] npio2Hw = {0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB,
            0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB,
            0x403921FB, 0x403AB41B, 0x403C463A, 0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C,
            0x4042D97C, 0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB,
            0x404921FB};
        for (int hi : npio2Hw) {
            aroundHigh(list, hi, true);
        }
        for (int n = 1; n <= 64; n++) {
            aroundValue(list, n * (Math.PI / 2), 4);
            aroundValue(list, -n * (Math.PI / 2), 4);
        }
        for (int n = 1; n <= 2000; n++) {
            list.add(new double[] {n * (Math.PI / 2) * intIn(1, 1 << 20)});
        }
        for (int k = 19; k <= 1023; k++) {
            list.add(new double[] {Math.scalb(1.0, k)});
            list.add(new double[] {-Math.nextDown(Math.scalb(1.0, k))});
        }
        // Kahan and McDonald's hardest case of the reduction by pi/2, and neighbours
        aroundValue(list, 6381956970095103.0 * 0x1p797, 4);
        aroundValue(list, 5.0e22, 2);
        aroundValue(list, 1.0e22, 2);
        for (int i = 0; i < r(4_000); i++) {
            list.add(new double[] {uniform(-1.0, 1.0)});
        }
        for (int i = 0; i < r(4_000); i++) {
            list.add(new double[] {uniform(-10.0, 10.0)});
        }
        for (int i = 0; i < r(4_000); i++) {
            list.add(new double[] {spread(-30, 20, true)});
        }
        for (int i = 0; i < r(4_000); i++) {
            list.add(new double[] {spread(20, 1023, true)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {uniform(-1e6, 1e6)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {anyBits()});
        }
        return list;
    }

    private static List<double[]> acosArguments() {
        List<double[]> list = specials();
        for (int hi : new int[] {0x3ff00000, 0x3fe00000, 0x3c600000}) {
            aroundHigh(list, hi, true);
        }
        aroundValue(list, 1.0, 32);
        aroundValue(list, -1.0, 32);
        aroundValue(list, 0.5, 16);
        aroundValue(list, -0.5, 16);
        for (int i = 0; i < r(10_000); i++) {
            list.add(new double[] {uniform(-1.0, 1.0)});
        }
        for (int i = 0; i < r(4_000); i++) {
            list.add(new double[] {spread(-70, -1, true)});
        }
        for (int i = 0; i < r(4_000); i++) {
            double d = spread(-60, -2, false);
            list.add(new double[] {coin() ? 1.0 - d : d - 1.0});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {anyBits()});
        }
        return list;
    }

    private interface Unary {
        double apply(double x);
    }

    private static void unary(Path out, String name, String java, List<double[]> arguments, Unary f) throws IOException {
        Table t = new Table(name, java, "x result");
        for (double[] a : arguments) {
            t.row(d(a[0]) + " " + d(f.apply(a[0])));
        }
        t.write(out);
    }

    // ---- pow ----

    private static void pow(Path out) throws IOException {
        List<double[]> list = new ArrayList<>();
        double[] xs = {0.0, -0.0, 1.0, -1.0, 2.0, -2.0, 3.0, -3.0, 0.5, -0.5, 1.5, -1.5, 10.0, -10.0,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, NAN, Double.longBitsToDouble(0xfff8000000000001L),
            Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL, -Double.MIN_NORMAL, Double.MAX_VALUE,
            -Double.MAX_VALUE, Math.nextUp(1.0), Math.nextDown(1.0), -Math.nextUp(1.0), -Math.nextDown(1.0),
            1e-300, -1e-300, 1e300, -1e300, Math.E, 0x1.fffp0, 0x1.fffp-1, 1.0 + 0x1p-20, 1.0 - 0x1p-21,
            Double.longBitsToDouble(0x000fffffffffffffL)};
        double[] ys = {0.0, -0.0, 1.0, -1.0, 2.0, -2.0, 3.0, -3.0, 0.5, -0.5, 1.5, -1.5, 10.0, -10.0,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, NAN, Double.longBitsToDouble(0x7ff0000000000002L),
            Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            0x1p53, -0x1p53, 0x1p53 + 2, 0x1p52 + 1, -(0x1p52 + 1), 0x1p52 + 0.5, 0x1p31, 0x1p31 + 1, -(0x1p31 + 1),
            0x1p64, 0x1p64 + 0x1p12, -0x1p64, 0x1p21 + 1, 0x1p20 + 1, -(0x1p20 + 1), 0x1p20 + 0.5, 0x1p30, -0x1p30,
            Math.nextDown(0x1p30), 1023.0, 1024.0, -1074.0, -1075.0, 4.5035996273704970e+15, 1.0 / 3};
        for (double x : xs) {
            for (double y : ys) {
                list.add(new double[] {x, y});
            }
        }
        // fdlibm 5.3 readme, bug 1: x very close to -1 and y very large
        list.add(new double[] {-1.0000000000000002e+00, 4.5035996273704970e+15});
        list.add(new double[] {-9.9999999999999978e-01, 4.5035996273704970e+15});
        // |y| > 2^31 and 2^64 with x near 1 (ix around 0x3fefffff / 0x3ff00000)
        for (int i = 0; i < 400; i++) {
            double x = words(0x3fefffff + intIn(-1, 2), (int) next());
            double y = spread(31, 70, true);
            list.add(new double[] {x, y});
            list.add(new double[] {-x, Math.rint(y)});
        }
        // the interval boundaries of e_pow.c: sqrt(3/2) (0x3988E) and sqrt(3) (0xBB67A); subnormal x
        for (int m : new int[] {0x3988E, 0x3988F, 0x3988D, 0xBB67A, 0xBB679, 0xBB67B, 0xfffff, 0x0}) {
            for (int i = 0; i < 20; i++) {
                int e = intIn(1, 2046);
                list.add(new double[] {words((e << 20) | m, (int) next()), uniform(-3.0, 3.0)});
            }
        }
        // DeterministicPow: j == 128 (m near 2), k == 0, p near 708, results near overflow and subnormal
        for (int i = 0; i < 200; i++) {
            double x = Math.scalb(uniform(2.0 - 0x1p-8, 2.0), intIn(-20, 20) - 1);
            list.add(new double[] {x, uniform(-50.0, 50.0)});
            list.add(new double[] {uniform(1.0, 2.0), uniform(-500.0, 500.0)});
        }
        for (double y : new double[] {1021.0, 1022.0, 1023.0, 1023.5, 1023.9999999999999, 1024.0, -1022.0, -1023.0,
            -1073.0, -1074.0, -1074.5, -1075.0, -1076.0}) {
            list.add(new double[] {2.0, y});
            list.add(new double[] {0.5, y});
            list.add(new double[] {Math.nextUp(2.0), y});
            list.add(new double[] {Math.nextDown(2.0), y});
        }
        for (double t : new double[] {708.0, -708.0, 709.0, -709.0, 709.782712893384, -745.1332191019411}) {
            for (double x : new double[] {Math.E, 2.0, 10.0, 0.1, 1e10}) {
                double y = t / DeterministicMath.log(x);
                list.add(new double[] {x, y});
                list.add(new double[] {x, Math.nextUp(y)});
                list.add(new double[] {x, Math.nextDown(y)});
            }
        }
        for (int x = 1; x <= 20; x++) {
            for (int y = -20; y <= 20; y++) {
                list.add(new double[] {x, y});
                list.add(new double[] {-x, y});
            }
        }
        // random
        for (int i = 0; i < r(8_000); i++) {
            list.add(new double[] {spread(-100, 100, false), uniform(-30.0, 30.0)});
        }
        for (int i = 0; i < r(4_000); i++) {
            list.add(new double[] {uniform(0.5, 2.0), uniform(-1000.0, 1000.0)});
        }
        for (int i = 0; i < r(3_000); i++) {
            double x = spread(-1074, 1023, false);
            double y = uniform(-760.0, 720.0) / DeterministicMath.log(x);
            list.add(new double[] {x, y});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {-spread(-10, 10, false), intIn(-60, 60)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {spread(-1074, 1023, false), spread(-60, 40, true)});
        }
        for (int i = 0; i < r(1_000); i++) {
            list.add(new double[] {anyBits(), coin() ? anyBits() : uniform(-10.0, 10.0)});
        }
        Table t = new Table("pow", "DeterministicMath.pow (DeterministicPow) and DeterministicMath.strictPow (FdlibmPow, "
            + "checked equal to StrictMath.pow here)", "x y deterministic_pow strict_pow");
        int nanDiffs = 0;
        for (double[] a : list) {
            double strict = FdlibmPow.pow(a[0], a[1]);
            double jdk = StrictMath.pow(a[0], a[1]);
            if (Double.doubleToRawLongBits(strict) != Double.doubleToRawLongBits(jdk)) {
                if (Double.isNaN(strict) && Double.isNaN(jdk)) {
                    nanDiffs++;
                } else {
                    throw new IllegalStateException("FdlibmPow differs from StrictMath.pow at " + d(a[0]) + " " + d(a[1]));
                }
            }
            t.row(d(a[0]) + " " + d(a[1]) + " " + d(DeterministicPow.pow(a[0], a[1])) + " " + d(strict));
        }
        t.comment("FdlibmPow equals StrictMath.pow on every row (NaN payload differences: " + nanDiffs + ")");
        t.write(out);
    }

    private static void powTables(Path out) throws IOException {
        Table t = new Table("pow_tables", "DeterministicPow.tables(): EXP_HI, EXP_LO, LOG_INV, LOG_HI, LOG_LO, "
            + "{LN2_HI, LN2_LO, L_HI, L_LO, INV_L}", "table index value");
        double[][] tables = DeterministicPow.tables();
        for (int i = 0; i < tables.length; i++) {
            for (int j = 0; j < tables[i].length; j++) {
                t.row(i + " " + j + " " + d(tables[i][j]));
            }
        }
        t.write(out);
    }

    // ---- fma ----

    private static void fma(Path out) throws IOException {
        List<double[]> list = new ArrayList<>();
        double[] s = {0.0, -0.0, 1.0, -1.0, 3.0, -3.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, NAN,
            Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_NORMAL,
            -Double.MIN_NORMAL, 0x1p-537, -0x1p-537, 0x1p512, -0x1p512, Math.nextUp(1.0), -Math.nextUp(1.0)};
        for (double a : s) {
            for (double b : s) {
                for (double c : s) {
                    list.add(new double[] {a, b, c});
                }
            }
        }
        // ties and sticky bits: a*b = ulp(c)/2 * (1 + k 2^-52)
        for (int i = 0; i < 1_000; i++) {
            double c = spread(-1000, 1000, true);
            double half = Math.ulp(c) / 2;
            for (double b : new double[] {1.0, Math.nextUp(1.0), Math.nextDown(1.0)}) {
                list.add(new double[] {half, coin() ? b : -b, c});
            }
        }
        for (int i = 0; i < r(6_000); i++) {
            list.add(new double[] {spread(-60, 60, true), spread(-60, 60, true), spread(-60, 60, true)});
        }
        for (int i = 0; i < r(4_000); i++) {
            double a = spread(-60, 60, true), b = spread(-60, 60, true);
            list.add(new double[] {a, b, -(a * b) * (1.0 + spread(-60, -1, true))});
        }
        for (int i = 0; i < r(3_000); i++) {
            double a = spread(-500, 500, true), b = spread(-500, 500, true);
            list.add(new double[] {a, b, -(a * b)});
        }
        for (int i = 0; i < 1_000; i++) { // few-bit factors: a b - fl(a b) is short and exact, no rounding at all
            double a = Math.scalb(1.0 + intIn(1, 1 << 12) * 0x1p-52, intIn(-40, 40));
            double b = Math.scalb(1.0 + intIn(1, 1 << 12) * 0x1p-52, intIn(-40, 40));
            list.add(new double[] {coin() ? a : -a, b, -(a * b)});
        }
        for (int i = 0; i < r(3_000); i++) {
            list.add(new double[] {spread(-1074, 1023, true), spread(-1074, 1023, true), spread(-1074, 1023, true)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {spread(-560, -500, true), spread(-560, -500, true), spread(-1074, -1000, true)});
        }
        for (int i = 0; i < r(2_000); i++) { // the kernel's forms: fma(fma(3, z, 2 c2), z, c1), fma(c2, c2, -3 c1)
            double z = uniform(0.0, 2.0), c2 = uniform(-3.0, 1.0), c1 = uniform(-2.0, 2.0);
            list.add(new double[] {3.0, z, 2.0 * c2});
            list.add(new double[] {Math.fma(3.0, z, 2.0 * c2), z, c1});
            list.add(new double[] {c2, c2, -3.0 * c1});
        }
        Table t = new Table("fma", "Math.fma (double)", "a b c result");
        for (double[] a : list) {
            t.row(d(a[0]) + " " + d(a[1]) + " " + d(a[2]) + " " + d(Math.fma(a[0], a[1], a[2])));
        }
        t.write(out);
    }

    // ---- Math semantics ----

    private static void maxMin(Path out) throws IOException {
        List<double[]> list = new ArrayList<>();
        for (double a : SPECIAL) {
            for (double b : SPECIAL) {
                list.add(new double[] {a, b});
            }
        }
        for (int i = 0; i < r(10_000); i++) {
            list.add(new double[] {spread(-1074, 1023, true), spread(-1074, 1023, true)});
        }
        for (int i = 0; i < r(5_000); i++) {
            double a = spread(-30, 30, true);
            list.add(new double[] {a, coin() ? -a : a});
        }
        for (int i = 0; i < r(5_000); i++) {
            list.add(new double[] {anyBits(), coin() ? anyBits() : uniform(-1.0, 1.0)});
        }
        Table t = new Table("max_min", "Math.max (double), Math.min (double), Double.compare", "a b max min compare(int)");
        for (double[] a : list) {
            t.row(d(a[0]) + " " + d(a[1]) + " " + d(Math.max(a[0], a[1])) + " " + d(Math.min(a[0], a[1])) + " "
                + i(Double.compare(a[0], a[1])));
        }
        t.write(out);
    }

    private static void javaUnary(Path out) throws IOException {
        List<double[]> list = specials();
        for (int k = -40; k <= 40; k++) {
            aroundValue(list, k + 0.5, 2);
            aroundValue(list, k, 2);
        }
        list.add(new double[] {0.49999999999999994});
        list.add(new double[] {-0.49999999999999994});
        for (double v : new double[] {0x1p52, 0x1p53, 0x1p31, 0x1p63, 0x1p64, 0x1p62}) {
            aroundValue(list, v, 4);
            aroundValue(list, -v, 4);
            aroundValue(list, v + 0.5, 2);
            aroundValue(list, -v - 0.5, 2);
        }
        for (int i = 0; i < r(6_000); i++) {
            list.add(new double[] {spread(-1074, 1023, true)});
        }
        for (int i = 0; i < r(6_000); i++) {
            list.add(new double[] {uniform(-100.0, 100.0)});
        }
        for (int i = 0; i < r(4_000); i++) {
            double k = Math.rint(spread(0, 52, true));
            list.add(new double[] {k + 0.5});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {spread(29, 66, true)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {anyBits()});
        }
        Table t = new Table("java_unary", "Math.abs, Math.floor, Math.ulp, Math.rint (double); Math.round (double -> "
            + "long); the casts (int) and (long)", "x abs floor ulp rint round to_int to_long");
        for (double[] a : list) {
            double x = a[0];
            t.row(d(x) + " " + d(Math.abs(x)) + " " + d(Math.floor(x)) + " " + d(Math.ulp(x)) + " " + d(Math.rint(x))
                + " " + l(Math.round(x)) + " " + i((int) x) + " " + l((long) x));
        }
        t.write(out);
    }

    private static void scalb(Path out) throws IOException {
        List<Object[]> list = new ArrayList<>();
        int[] ns = {0, 1, -1, 2, -2, 52, -52, 53, -53, 54, -54, 511, -511, 512, -512, 513, -513, 1022, -1022, 1023,
            -1023, 1024, -1024, 1074, -1074, 1075, -1075, 2046, -2046, 2098, -2098, 2099, -2099, 2100, -2100, 50000,
            -50000, 50001, -50001, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1};
        for (double x : SPECIAL) {
            for (int n : ns) {
                list.add(new Object[] {x, n});
            }
        }
        for (int i = 0; i < r(16_000); i++) {
            list.add(new Object[] {spread(-1074, 1023, true), intIn(-2200, 2200)});
        }
        for (int i = 0; i < r(2_000); i++) { // results near the subnormal range: double rounding would show here
            double x = spread(-60, 60, true);
            list.add(new Object[] {x, -1022 - Math.getExponent(x) - intIn(-2, 54)});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new Object[] {anyBits(), (int) next()});
        }
        Table t = new Table("scalb", "Math.scalb (double, int)", "x n result");
        for (Object[] a : list) {
            double x = (Double) a[0];
            int n = (Integer) a[1];
            t.row(d(x) + " " + i(n) + " " + d(Math.scalb(x, n)));
        }
        t.write(out);
    }

    private static void clamp(Path out) throws IOException {
        List<double[]> list = new ArrayList<>();
        double[][] bounds = {{-1.0, 1.0}, {0.0, 0.0}, {-0.0, 0.0}, {0.0, -0.0}, {-0.0, -0.0}, {NAN, 1.0}, {1.0, NAN},
            {NAN, NAN}, {2.0, 1.0}, {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}, {1.0, 1.0},
            {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY}, {Double.MIN_VALUE, Double.MAX_VALUE},
            {-Double.MAX_VALUE, -Double.MIN_VALUE}, {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY},
            {-0.0, Double.MIN_VALUE}, {-Double.MIN_VALUE, 0.0}};
        for (double v : SPECIAL) {
            for (double[] b : bounds) {
                list.add(new double[] {v, b[0], b[1]});
            }
        }
        for (int i = 0; i < r(16_000); i++) {
            double p = spread(-40, 40, true), q = spread(-40, 40, true);
            double lo = Math.min(p, q), hi = Math.max(p, q);
            double v = coin() ? uniform(lo - (hi - lo), hi + (hi - lo)) : spread(-40, 40, true);
            list.add(new double[] {v, lo, hi});
        }
        for (int i = 0; i < r(2_000); i++) {
            double p = spread(-40, 40, true);
            list.add(new double[] {coin() ? p : -p, p, p});
        }
        for (int i = 0; i < r(2_000); i++) {
            list.add(new double[] {anyBits(), anyBits(), anyBits()});
        }
        Table t = new Table("clamp", "Math.clamp (double, double, double); iae: IllegalArgumentException",
            "value min max result");
        for (double[] a : list) {
            String r;
            try {
                r = d(Math.clamp(a[0], a[1], a[2]));
            } catch (IllegalArgumentException e) {
                r = "iae";
            }
            t.row(d(a[0]) + " " + d(a[1]) + " " + d(a[2]) + " " + r);
        }
        t.write(out);
    }

    private static void clampInt(Path out) throws IOException {
        List<long[]> list = new ArrayList<>();
        long[] values = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE,
            Integer.MIN_VALUE - 1L, Integer.MAX_VALUE + 1L, 5, -5, 6, -6};
        int[][] bounds = {{0, 0}, {-5, 5}, {Integer.MIN_VALUE, Integer.MAX_VALUE}, {5, -5}, {Integer.MAX_VALUE,
            Integer.MAX_VALUE}, {Integer.MIN_VALUE, Integer.MIN_VALUE}, {1, 0}, {0, 1}};
        for (long v : values) {
            for (int[] b : bounds) {
                list.add(new long[] {v, b[0], b[1]});
            }
        }
        for (int i = 0; i < r(20_000); i++) {
            int p = coin() ? (int) next() : intIn(-100, 100), q = coin() ? (int) next() : intIn(-100, 100);
            long v = coin() ? next() : (coin() ? intIn(-200, 200) : (int) next());
            boolean ordered = (next() & 15) != 0;
            list.add(new long[] {v, ordered ? Math.min(p, q) : p, ordered ? Math.max(p, q) : q});
        }
        Table t = new Table("clamp_int", "Math.clamp (long, int, int) -> int; iae: IllegalArgumentException",
            "value(long) min(int) max(int) result(int)");
        for (long[] a : list) {
            String r;
            try {
                r = i(Math.clamp(a[0], (int) a[1], (int) a[2]));
            } catch (IllegalArgumentException e) {
                r = "iae";
            }
            t.row(l(a[0]) + " " + i((int) a[1]) + " " + i((int) a[2]) + " " + r);
        }
        t.write(out);
    }

    private static void sum(Path out) throws IOException {
        List<double[]> list = new ArrayList<>();
        list.add(new double[] {});
        for (double s : SPECIAL) {
            list.add(new double[] {s});
        }
        double inf = Double.POSITIVE_INFINITY, max = Double.MAX_VALUE;
        double[][] fixed = {{inf, inf}, {inf, -inf}, {-inf, -inf}, {NAN, 1.0}, {max, max}, {max, max, -max},
            {-max, -max}, {1e100, 1.0, -1e100}, {-0.0}, {-0.0, -0.0}, {0.0, -0.0}, {-0.0, 0.0},
            {0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1}, {1.0, 0x1p-53, 0x1p-53}, {0x1p-53, 0x1p-53, 1.0},
            {1.0, 1e-16, -1.0}, {inf, 1.0, -inf}, {max, inf}, {1.0, NAN, inf}};
        for (double[] f : fixed) {
            list.add(f);
        }
        int elements = 0;
        while (elements < r(24_000)) {
            int n = intIn(0, 12);
            double[] v = new double[n];
            int mode = intIn(0, 9);
            for (int k = 0; k < n; k++) {
                if (mode == 0 && k > 0 && coin()) {
                    v[k] = -v[intIn(0, k - 1)] * (1.0 + spread(-55, -40, true)); // cancellation
                } else if (mode == 1) {
                    v[k] = uniform(-1.0, 1.0);
                } else if (mode == 2 && (next() & 31) == 0) {
                    v[k] = (next() & 3) == 0 ? NAN : (coin() ? inf : -inf);
                } else if (mode == 3) {
                    v[k] = spread(1000, 1023, true); // overflow of the running sum
                } else {
                    v[k] = spread(-30, 30, true);
                }
            }
            list.add(v);
            elements += n;
        }
        Table t = new Table("sum", "DoubleStream.of(values).sum() (sequential; Collectors.sumWithCompensation, "
            + "computeFinalSum)", "n value_1 ... value_n result");
        for (double[] v : list) {
            StringBuilder sb = new StringBuilder();
            sb.append(i(v.length));
            for (double x : v) {
                sb.append(' ').append(d(x));
            }
            sb.append(' ').append(d(DoubleStream.of(v).sum()));
            t.row(sb.toString());
        }
        t.write(out);
    }

    // ---- output ----

    private static String hex(long bits, int digits) {
        String s = Long.toHexString(bits);
        if (digits == 8) {
            s = Long.toHexString(bits & 0xffffffffL);
        }
        return "0".repeat(digits - s.length()) + s;
    }

    private static String d(double x) {
        return hex(Double.doubleToRawLongBits(x), 16);
    }

    private static String l(long x) {
        return hex(x, 16);
    }

    private static String i(int x) {
        return hex(x, 8);
    }

    private static final class Table {
        private final String name;
        private final List<String> header = new ArrayList<>();
        private final List<String> rows = new ArrayList<>();

        Table(String name, String java, String columns) {
            this.name = name;
            header.add("# icheme-math bit table '" + name + "', generated by "
                + "src/test/java/com/wormzjl/icheme/science/math/RustBitTables.java");
            header.add("# java: " + java);
            header.add("# columns: " + columns + " (raw bits, hex)");
            header.add("# jdk: " + System.getProperty("java.vendor") + " " + System.getProperty("java.version")
                + ", seed 0x" + Long.toHexString(SEED));
        }

        void comment(String text) {
            header.add("# " + text);
        }

        void row(String line) {
            rows.add(line);
        }

        void write(Path out) throws IOException {
            header.add("# rows: " + rows.size());
            List<List<String>> parts = new ArrayList<>();
            List<String> part = new ArrayList<>();
            long bytes = 0;
            for (String r : rows) {
                if (bytes + r.length() + 1 > MAX_FILE_BYTES - 2_000 && !part.isEmpty()) {
                    parts.add(part);
                    part = new ArrayList<>();
                    bytes = 0;
                }
                part.add(r);
                bytes += r.length() + 1;
            }
            parts.add(part);
            for (int p = 0; p < parts.size(); p++) {
                String file = parts.size() == 1 ? name + ".txt" : name + "-" + (p + 1) + ".txt";
                StringBuilder sb = new StringBuilder();
                for (String h : header) {
                    sb.append(h).append('\n');
                }
                if (parts.size() > 1) {
                    sb.append("# part ").append(p + 1).append(" of ").append(parts.size()).append('\n');
                }
                for (String r : parts.get(p)) {
                    sb.append(r).append('\n');
                }
                Files.writeString(out.resolve(file), sb.toString(), StandardCharsets.US_ASCII);
            }
            System.out.println(name + ": " + rows.size() + " rows in " + parts.size() + " file(s)");
        }
    }
}
