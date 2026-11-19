package com.wormzjl.icheme.science.fluid;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.math.DeterministicPow;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/**
 * {@link DeterministicMath#strictPow} (the allocation-free fdlibm transliteration) must be bitwise equal to
 * {@code StrictMath.pow}, the specified fdlibm result. {@link DeterministicMath#pow} ({@link DeterministicPow}, the
 * table-driven kernel of batch 2026-09-27-jdk-determinism) must stay within 1 ulp of it, equal it bit for bit on every
 * special case, return the pinned bit patterns below (a JVM that computes any of them differently fails here), carry
 * tables equal to their exact regeneration, and allocate nothing. The long forms are
 * {@code tools/jdk-determinism/src/PowEquivalence.java} and {@code tools/jdk-determinism/perf/src/FastDetCheck.java}.
 */
class DeterministicMathTest {
    private static long state = 0x2026_0927L;

    private static long next() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double logUniform(int eLo, int eHi) {
        int e = eLo + (int) Long.remainderUnsigned(next(), eHi - eLo + 1);
        return Double.longBitsToDouble(((long) (e + 1023) << 52) | (next() >>> 12));
    }

    private static final double[] SPECIAL = {0.0, -0.0, 1.0, -1.0, 2.0, -2.0, 0.5, -0.5, 1.5, -1.5, 3.0, -3.0, 0.25,
        1e-300, -1e-300, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, Math.nextUp(1.0), Math.nextDown(1.0),
        0x1p31, -0x1p31, 0x1p53, 0x1p64, -0x1p64, 1023.0, 1024.0, 1075.0, -1074.0, -1075.0, 10.0, 0.1, 708.0, -708.0};

    private static void assertStrict(double x, double y) {
        double a = DeterministicMath.strictPow(x, y), b = StrictMath.pow(x, y);
        if (Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b) && !(Double.isNaN(a) && Double.isNaN(b))) {
            fail("strictPow(" + Double.toHexString(x) + ", " + Double.toHexString(y) + "): "
                    + Double.toHexString(a) + ", StrictMath " + Double.toHexString(b));
        }
    }

    /** pow within 1 ulp of StrictMath.pow, same NaN/infinity/zero/sign class; bitwise outside the kernel's domain. */
    private static void assertClose(double x, double y) {
        double a = DeterministicMath.pow(x, y), b = StrictMath.pow(x, y);
        long ba = Double.doubleToRawLongBits(a), bb = Double.doubleToRawLongBits(b);
        if (ba == bb || Double.isNaN(a) && Double.isNaN(b)) return;
        boolean kernel = x >= Double.MIN_NORMAL && x < Double.POSITIVE_INFINITY && y != 0 && Math.abs(y) < 0x1p30;
        String where = "pow(" + Double.toHexString(x) + ", " + Double.toHexString(y) + "): " + Double.toHexString(a)
                + ", StrictMath " + Double.toHexString(b);
        assertTrue(kernel, "outside the kernel domain pow must be StrictMath bit for bit: " + where);
        assertTrue(Double.isFinite(a) && Double.isFinite(b) && (ba < 0) == (bb < 0) && a != 0 && b != 0, where);
        assertTrue(Math.abs(ba - bb) <= 1, "more than 1 ulp from StrictMath: " + where);
    }

    @Test void strictPowIsBitwiseStrictMathOnSpecialValues() {
        for (double x : SPECIAL) for (double y : SPECIAL) assertStrict(x, y);
    }

    @Test void strictPowIsBitwiseStrictMathOnSampledInputs() {
        for (int i = 0; i < 200_000; i++) {
            assertStrict(Double.longBitsToDouble(next()), Double.longBitsToDouble(next())); // every double class
            assertStrict(-logUniform(-30, 30), (double) (next() % 200) * ((next() & 1) == 0 ? 1.0 : 0.5)); // negative bases
            assertStrict(1.0 + ((next() >>> 11) * 0x1p-53 - 0.5) * 0x1p-18, logUniform(28, 70)); // near 1, huge y
            assertStrict(logUniform(-1, 1), (next() & 1) == 0 ? 1000 + (next() % 150) : -1000 - (next() % 150)); // o/uflow
            assertStrict(logUniform(-47, 24), ((next() >>> 11) * 0x1p-53 - 0.5) * 8); // thermodynamic ranges
        }
    }

    @Test void powIsStrictMathOnSpecialValuesAndWithinOneUlpElsewhere() {
        for (double x : SPECIAL) for (double y : SPECIAL) assertClose(x, y);
        for (int i = 0; i < 200_000; i++) {
            assertClose(Double.longBitsToDouble(next()), Double.longBitsToDouble(next())); // every double class
            assertClose(-logUniform(-30, 30), (double) (next() % 200) * ((next() & 1) == 0 ? 1.0 : 0.5)); // negative bases
            assertClose(1.0 + ((next() >>> 11) * 0x1p-53 - 0.5) * 0x1p-18, logUniform(0, 12)); // near 1, large y
            assertClose(logUniform(-1, 1), (next() & 1) == 0 ? 1000 + (next() % 150) : -1000 - (next() % 150)); // o/uflow
            assertClose(logUniform(-47, 24), ((next() >>> 11) * 0x1p-53 - 0.5) * 8); // thermodynamic ranges
            assertClose(logUniform(10, 23), 0.9); // Reynolds^0.9 of the pipe friction factor
        }
    }

    /**
     * Fixed inputs and the bits every JVM must return for them (recorded on HotSpot 21.0.11 and Zulu 25 on Windows,
     * the same under -Xint and C1 only): the pipe friction factor's Re^0.9, the viscosity power sums, the water Watson
     * term, the slurry exponent, a base near 1 with a large exponent, a tiny base, one fdlibm fallback (overflow), and
     * four inputs where the kernel's result is 1 ulp from StrictMath's (so the kernel, not fdlibm, is what is pinned).
     */
    @Test void powReturnsThePinnedBitPatterns() {
        double[][] cases = {
            {2000.100152946676, 0.9, 0x1.d3a53252f1c57p9}, {5362496.447100324, 0.9, 0x1.160430ca56324p20},
            {1.2771666666666666, -40.0, 0x1.d7d4da77a7faep-15}, {0.8438333333333333, -19.6, 0x1.be288f8d6bc3p4},
            {1.273776583706278, 0.38, 0x1.18a81d1184e4fp0}, {0.5, -1.55, 0x1.76ce51f6a0bb8p1},
            {0.9999999, 1e6, 0x1.cf46d976dea4p-1}, {1e-300, 0.5, 0x1.a2fe76a3f9475p-499}, {7.5, 2.0, 0x1.c2p5},
            {1e300, 2.0, Double.POSITIVE_INFINITY},
            {0x1.c5fdb0fa2a0f8p-28, -0x1.ba7aebaacf3ap-3, 0x1.d4316f261492fp5}, // StrictMath ...493p5
            {0x1.add83aa04e3aap19, 0.9, 0x1.b56dc53e8e5bbp17},                    // StrictMath ...5bap17
            {0x1.fdd4a72f2ad05p-1, -0x1.5861730db3b1bp11, 0x1.d5f4f27e51671p16},  // StrictMath ...670p16
            {0x1.9feac6d809e36p0, -0.5, 0x1.91af955106c3fp-1},                    // StrictMath ...c4p-1
        };
        for (double[] c : cases) {
            double got = DeterministicMath.pow(c[0], c[1]);
            assertEquals(Double.doubleToRawLongBits(c[2]), Double.doubleToRawLongBits(got),
                    "pow(" + c[0] + ", " + c[1] + ") = " + Double.toHexString(got) + ", pinned " + Double.toHexString(c[2]));
        }
    }

    /** The kernel's tables are the correctly rounded values of their exact definitions (BigDecimal series here). */
    @Test void powTablesEqualTheirExactRegeneration() {
        MathContext mc = new MathContext(60, RoundingMode.HALF_EVEN);
        BigDecimal ln2 = atanh2(BigDecimal.ONE.divide(BigDecimal.valueOf(3), mc), mc);
        double[][] t = DeterministicPow.tables();
        double ln2Hi = Double.longBitsToDouble(Double.doubleToRawLongBits(ln2.doubleValue()) & ~0xfffL);
        BigDecimal l = ln2.divide(BigDecimal.valueOf(128), mc);
        double lHi = Double.longBitsToDouble(Double.doubleToRawLongBits(l.doubleValue()) & ~0xfffffL);
        double[] constants = {ln2Hi, ln2.subtract(new BigDecimal(ln2Hi), mc).doubleValue(), lHi,
            l.subtract(new BigDecimal(lHi), mc).doubleValue(), BigDecimal.valueOf(128).divide(ln2, mc).doubleValue()};
        assertArrayEquals(constants, t[5]);
        for (int j = 0; j < 128; j++) {
            BigDecimal e = exp(ln2.multiply(BigDecimal.valueOf(j)).divide(BigDecimal.valueOf(128), mc), mc);
            BigDecimal f = BigDecimal.ONE.add(BigDecimal.valueOf(j).divide(BigDecimal.valueOf(128), mc));
            BigDecimal lf = j == 0 ? BigDecimal.ZERO : atanh2(f.subtract(BigDecimal.ONE).divide(f.add(BigDecimal.ONE), mc), mc);
            double eHi = e.doubleValue(), lfHi = lf.doubleValue();
            assertEquals(eHi, t[0][j], "2^(j/128) " + j);
            assertEquals(e.subtract(new BigDecimal(eHi), mc).doubleValue(), t[1][j], "2^(j/128) low part " + j);
            assertEquals(BigDecimal.ONE.divide(f, mc).doubleValue(), t[2][j], "1/F " + j);
            assertEquals(lfHi, t[3][j], "ln F " + j);
            assertEquals(lf.subtract(new BigDecimal(lfHi), mc).doubleValue(), t[4][j], "ln F low part " + j);
        }
    }

    /** 2 atanh(z) = ln((1+z)/(1-z)) by its series, |z| <= 1/3. */
    private static BigDecimal atanh2(BigDecimal z, MathContext mc) {
        BigDecimal z2 = z.multiply(z, mc), term = z, sum = BigDecimal.ZERO, eps = BigDecimal.ONE.movePointLeft(58);
        for (int k = 0; k < 400; k++) {
            BigDecimal add = term.divide(BigDecimal.valueOf(2L * k + 1), mc);
            sum = sum.add(add, mc);
            if (add.abs().compareTo(eps) < 0) break;
            term = term.multiply(z2, mc);
        }
        return sum.multiply(BigDecimal.valueOf(2), mc);
    }

    private static BigDecimal exp(BigDecimal x, MathContext mc) {
        BigDecimal sum = BigDecimal.ONE, term = BigDecimal.ONE, eps = BigDecimal.ONE.movePointLeft(58);
        for (int k = 1; k < 400; k++) {
            term = term.multiply(x, mc).divide(BigDecimal.valueOf(k), mc);
            sum = sum.add(term, mc);
            if (term.abs().compareTo(eps) < 0) break;
        }
        return sum;
    }

    /**
     * {@code atan} and {@code tanh} (added for the solid CO2 Gibbs function and the methane spine's cosh-type terms) are
     * {@code StrictMath} bit for bit on the special values and on sampled inputs, and return the fdlibm bits pinned below
     * (recorded from {@code StrictMath} on HotSpot 21.0.11): a JVM that computed any of them differently fails here.
     */
    @Test void atanAndTanhAreStrictMathBitForBit() {
        for (double x : SPECIAL) {
            assertSameBits(StrictMath.atan(x), DeterministicMath.atan(x), "atan(" + Double.toHexString(x) + ")");
            assertSameBits(StrictMath.tanh(x), DeterministicMath.tanh(x), "tanh(" + Double.toHexString(x) + ")");
        }
        for (int i = 0; i < 200_000; i++) {
            double any = Double.longBitsToDouble(next()); // every double class
            double small = ((next() & 1) == 0 ? 1 : -1) * logUniform(-30, 6); // the thermodynamic arguments
            for (double x : new double[] {any, small}) {
                assertSameBits(StrictMath.atan(x), DeterministicMath.atan(x), "atan(" + Double.toHexString(x) + ")");
                assertSameBits(StrictMath.tanh(x), DeterministicMath.tanh(x), "tanh(" + Double.toHexString(x) + ")");
            }
        }
        double[][] atan = {{0.5, 0x1.dac670561bb4fp-2}, {1.0, 0x1.921fb54442d18p-1}, {2.0, 0x1.1b6e192ebbe44p0},
            {-3.75, -0x1.4f68dea672618p0}, {1e-3, 0x1.0624d77516e16p-10}};
        double[][] tanh = {{0.5, 0x1.d9353d7568af3p-2}, {1.0, 0x1.85efab514f394p-1}, {2.0, 0x1.ed9505e1bc3d4p-1},
            {-0.125, -0x1.fd5992bc4b834p-4}, {1e-3, 0x1.0624d77516ce2p-10}};
        for (double[] c : atan) assertSameBits(c[1], DeterministicMath.atan(c[0]), "pinned atan(" + c[0] + ")");
        for (double[] c : tanh) assertSameBits(c[1], DeterministicMath.tanh(c[0]), "pinned tanh(" + c[0] + ")");
    }

    private static void assertSameBits(double expected, double actual, String where) {
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual) && !(Double.isNaN(expected) && Double.isNaN(actual))) {
            fail(where + ": " + Double.toHexString(actual) + ", expected " + Double.toHexString(expected));
        }
    }

    @Test void powAllocatesNothing() {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        double sink = 0;
        for (int i = 0; i < 20_000; i++) {
            sink += DeterministicMath.pow(1.0 + i * 1e-3, 0.37) + DeterministicMath.strictPow(1.0 + i * 1e-3, 0.37); // warm up
        }
        long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 200_000; i++) {
            sink += DeterministicMath.pow(1.0 + i * 1e-3, 0.37) + DeterministicMath.strictPow(1.0 + i * 1e-3, 0.37);
        }
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        assertTrue(sink > 0);
        // StrictMath.pow on JDK 21 to 27 allocates 96 bytes per call (19 MB here); the two ports allocate none.
        assertTrue(allocated < 64 * 1024, allocated + " bytes allocated by 200,000 pow and strictPow calls");
    }
}
