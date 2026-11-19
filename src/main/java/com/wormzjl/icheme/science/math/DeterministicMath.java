package com.wormzjl.icheme.science.math;

/**
 * The one entry point of the science code for elementary functions that are not correctly rounded (batch
 * 2026-09-27-jdk-determinism). Every result is bitwise identical on every Java runtime, operating system and CPU.
 *
 * <p>Why: {@code + - * /}, {@code Math.sqrt} and {@code Math.fma} are correctly rounded by the JLS, so they are identical
 * everywhere and may be used directly. {@code java.lang.Math.log/exp/pow/cbrt/cos/...} only promise 1 ulp (log10: 1 ulp,
 * measured 2): HotSpot replaces them with CPU intrinsics whose results differ from OpenJ9's, from the Graal JIT's and
 * between JDK releases (JDK 25 added a {@code cbrt} intrinsic), and on GraalVM CE 25 even between the interpreter and the
 * compiled code of the same JVM. {@code java.lang.StrictMath} is specified to return the fdlibm 5.3 results bit for bit;
 * since JDK 21 it is plain Java bytecode (no native code), so it cannot vary by platform.
 *
 * <p>Every method here except {@link #pow} returns exactly what {@code StrictMath} returns. {@link #strictPow} does not
 * call {@code StrictMath.pow} either: the JDK 21 to 27 implementation ({@code java.lang.FdLibm.Pow}) allocates three
 * {@code double[]} constant tables per call (96 bytes) and cost the mixed-gas junction cases twice their time;
 * {@link FdlibmPow} is a transliteration of the same fdlibm {@code e_pow.c} with the tables hoisted, bitwise identical
 * to {@code StrictMath.pow} ({@code DeterministicMathTest} and {@code tools/jdk-determinism/src/PowEquivalence.java}).
 * {@link #pow} is {@link DeterministicPow}, a table-driven kernel in plain double arithmetic that costs half of
 * {@link FdlibmPow} and is as deterministic, within 1 ulp of fdlibm (owner decision D4 of the batch: slightly
 * different last bits are acceptable as long as every JVM computes the same ones).
 *
 * <p>{@code DeterministicMathSourceTest} fails when fluid code calls a non-correctly-rounded {@code Math} or
 * {@code StrictMath} function instead of this class.
 */
public final class DeterministicMath {
    private DeterministicMath() {
    }

    public static double log(double x) {
        return StrictMath.log(x);
    }

    public static double log10(double x) {
        return StrictMath.log10(x);
    }

    public static double log1p(double x) {
        return StrictMath.log1p(x);
    }

    public static double exp(double x) {
        return StrictMath.exp(x);
    }

    public static double expm1(double x) {
        return StrictMath.expm1(x);
    }

    public static double cbrt(double x) {
        return StrictMath.cbrt(x);
    }

    /** The Euclidean norm of two components, without intermediate overflow or underflow. */
    public static double hypot(double x, double y) {
        return StrictMath.hypot(x, y);
    }

    /** fdlibm cosine, with allocation-free argument reduction over the range of the cubic roots. */
    public static double cos(double x) {
        return Math.abs(x) <= FdlibmCos.MAX_ARGUMENT ? FdlibmCos.cos(x) : StrictMath.cos(x);
    }

    public static double sin(double x) {
        return StrictMath.sin(x);
    }

    public static double acos(double x) {
        return StrictMath.acos(x);
    }

    public static double asin(double x) {
        return StrictMath.asin(x);
    }

    public static double atan2(double y, double x) {
        return StrictMath.atan2(y, x);
    }

    /** The arctangent; the solid CO2 Gibbs function's lattice terms (Jaeger and Span 2012) call it. */
    public static double atan(double x) {
        return StrictMath.atan(x);
    }

    /** The hyperbolic tangent; the GERG-2008 cosh-type ideal-gas terms of the methane reference spine call it. */
    public static double tanh(double x) {
        return StrictMath.tanh(x);
    }

    /**
     * x to the power y by {@link DeterministicPow}: the same bits on every JVM, within 1 ulp of {@code StrictMath.pow}
     * (about 0.5 ulp of the exact value), {@code StrictMath.pow} bit for bit on every special case, without allocation.
     */
    public static double pow(double x, double y) {
        return DeterministicPow.pow(x, y);
    }

    /** x to the power y, bitwise equal to {@code StrictMath.pow(x, y)} (fdlibm), without allocation. */
    public static double strictPow(double x, double y) {
        return FdlibmPow.pow(x, y);
    }
}
