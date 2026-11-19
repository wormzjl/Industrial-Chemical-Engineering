package com.wormzjl.icheme.science.math;

/**
 * fdlibm 5.3 cosine for |x| at most 4.25 radians, covering the trigonometric cubic roots.
 * Transliterated from s_cos.c, e_rem_pio2.c, k_cos.c and k_sin.c (netlib.org/fdlibm).
 * The reduced angle and its correction stay in two local doubles. Keep the fdlibm
 * arithmetic and high-word comparisons to preserve the bits returned by StrictMath.
 *
 * <pre>
 * ====================================================
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 * </pre>
 */
final class FdlibmCos {
    private FdlibmCos() {}

    static final double MAX_ARGUMENT = 4.25;

    private static final double INV_PIO2 = 0x1.45f306dc9c883p-1;
    private static final double PIO2_1 = 0x1.921fb544p0;
    private static final double PIO2_1T = 0x1.0b4611a626331p-34;
    private static final double PIO2_2 = 0x1.0b4611a6p-34;
    private static final double PIO2_2T = 0x1.3198a2e037073p-69;
    private static final double PIO2_3 = 0x1.3198a2ep-69;
    private static final double PIO2_3T = 0x1.b839a252049c1p-104;

    private static final double C1 = 0x1.555555555554cp-5;
    private static final double C2 = -0x1.6c16c16c15177p-10;
    private static final double C3 = 0x1.a01a019cb159p-16;
    private static final double C4 = -0x1.27e4f809c52adp-22;
    private static final double C5 = 0x1.1ee9ebdb4b1c4p-29;
    private static final double C6 = -0x1.8fae9be8838d4p-37;

    private static final double S1 = -0x1.5555555555549p-3;
    private static final double S2 = 0x1.111111110f8a6p-7;
    private static final double S3 = -0x1.a01a019c161d5p-13;
    private static final double S4 = 0x1.71de357b1fe7dp-19;
    private static final double S5 = -0x1.ae5e68a2b9cebp-26;
    private static final double S6 = 0x1.5d93a5acfd57cp-33;

    static double cos(double x) {
        int ix = highWord(x) & 0x7fff_ffff;
        if (ix <= 0x3fe9_21fb) return cosKernel(x, 0); // about π/4
        if (ix < 0x4002_d97c) { // about 3π/4: subtract exactly one signed multiple of π/2
            double sign = x < 0 ? -1 : 1;
            double remainder = x - sign * PIO2_1;
            double correction = sign * PIO2_1T;
            if (ix == 0x3ff9_21fb) { // protect cancellation near π/2
                remainder -= sign * PIO2_2;
                correction = sign * PIO2_2T;
            }
            double head = remainder - correction;
            double tail = (remainder - head) - correction;
            return -sign * sinKernel(head, tail);
        }

        double positive = Math.abs(x);
        int quadrants = (int) (positive * INV_PIO2 + .5);
        double remainder = positive - quadrants * PIO2_1;
        double correction = quadrants * PIO2_1T;
        double head = remainder - correction;
        // Only π from fdlibm's table of multiples of π/2 lies in this part of the supported range.
        if (ix == 0x4009_21fb && lostExponentBits(ix, head) > 16) {
            double previous = remainder;
            double chunk = quadrants * PIO2_2;
            remainder = previous - chunk;
            correction = quadrants * PIO2_2T - ((previous - remainder) - chunk);
            head = remainder - correction;
            if (lostExponentBits(ix, head) > 49) {
                previous = remainder;
                chunk = quadrants * PIO2_3;
                remainder = previous - chunk;
                correction = quadrants * PIO2_3T - ((previous - remainder) - chunk);
                head = remainder - correction;
            }
        }
        double tail = (remainder - head) - correction;
        if (x < 0) {
            head = -head;
            tail = -tail;
            quadrants = -quadrants;
        }
        return switch (quadrants & 3) {
            case 0 -> cosKernel(head, tail);
            case 1 -> -sinKernel(head, tail);
            case 2 -> -cosKernel(head, tail);
            default -> sinKernel(head, tail);
        };
    }

    private static double cosKernel(double x, double tail) {
        int ix = highWord(x) & 0x7fff_ffff;
        if (ix < 0x3e40_0000) return 1; // |x| < 2^-27
        double z = x * x;
        double polynomial = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
        if (ix < 0x3fd3_3333) return 1 - (.5 * z - (z * polynomial - x * tail));
        // The low word of |x|/4 is cleared so the leading subtractions are exact.
        double quarter = ix > 0x3fe9_0000 ? .28125 : Double.longBitsToDouble((long) (ix - 0x0020_0000) << 32);
        return (1 - quarter) - ((.5 * z - quarter) - (z * polynomial - x * tail));
    }

    private static double sinKernel(double x, double tail) {
        if ((highWord(x) & 0x7fff_ffff) < 0x3e40_0000) return x;
        double z = x * x;
        double v = z * x;
        double polynomial = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
        return x - ((z * (.5 * tail - v * polynomial) - tail) - v * S1);
    }

    private static int lostExponentBits(int original, double remainder) {
        return (original >>> 20) - ((highWord(remainder) >>> 20) & 0x7ff);
    }

    private static int highWord(double x) {
        return (int) (Double.doubleToRawLongBits(x) >>> 32);
    }
}
