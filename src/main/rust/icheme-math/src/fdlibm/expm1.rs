// fdlibm 5.3 s_expm1.c, "@(#)s_expm1.c 1.5 04/04/22", transliterated to Rust.
//
// ====================================================
// Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.
//
// Permission to use, copy, modify, and distribute this
// software is freely granted, provided that this notice
// is preserved.
// ====================================================

use super::{hi, lo, with_hi};

const ONE: f64 = 1.0;
const HUGE: f64 = 1.0e+300;
const TINY: f64 = 1.0e-300;
const O_THRESHOLD: f64 = 7.09782712893383973096e+02; /* 0x40862E42, 0xFEFA39EF */
const LN2_HI: f64 = 6.93147180369123816490e-01; /* 0x3fe62e42, 0xfee00000 */
const LN2_LO: f64 = 1.90821492927058770002e-10; /* 0x3dea39ef, 0x35793c76 */
const INVLN2: f64 = 1.44269504088896338700e+00; /* 0x3ff71547, 0x652b82fe */
/* scaled coefficients related to expm1 */
const Q1: f64 = -3.33333333333331316428e-02; /* BFA11111 111110F4 */
const Q2: f64 = 1.58730158725481460165e-03; /* 3F5A01A0 19FE5585 */
const Q3: f64 = -7.93650757867487942473e-05; /* BF14CE19 9EAADBB7 */
const Q4: f64 = 4.00821782732936239552e-06; /* 3ED0CFCA 86E65239 */
const Q5: f64 = -2.01099218183624371326e-07; /* BE8AFDB7 6E09C32D */

/// `expm1(x)`: e to the power x, minus 1.
// java: StrictMath.expm1
pub fn expm1(x: f64) -> f64 {
    let mut x = x;
    let k: i32;
    let c: f64;

    let mut hx: u32 = hi(x) as u32; /* high word of x */
    let xsb: u32 = hx & 0x80000000; /* sign bit of x */
    hx &= 0x7fffffff; /* high word of |x| */

    /* filter out huge and non-finite argument */
    if hx >= 0x4043687A {
        /* if |x|>=56*ln2 */
        if hx >= 0x40862E42 {
            /* if |x|>=709.78... */
            if hx >= 0x7ff00000 {
                if ((hx & 0xfffff) | lo(x)) != 0 {
                    return x + x; /* NaN */
                } else {
                    return if xsb == 0 { x } else { -1.0 }; /* exp(+-inf)={inf,-1} */
                }
            }
            if x > O_THRESHOLD {
                return HUGE * HUGE; /* overflow */
            }
        }
        if xsb != 0 {
            /* x < -56*ln2, return -1.0 with inexact */
            if x + TINY < 0.0 {
                /* raise inexact */
                return TINY - ONE; /* return -1 */
            }
        }
    }

    /* argument reduction */
    if hx > 0x3fd62e42 {
        /* if  |x| > 0.5 ln2 */
        let hi_;
        let lo_;
        if hx < 0x3FF0A2B2 {
            /* and |x| < 1.5 ln2 */
            if xsb == 0 {
                hi_ = x - LN2_HI;
                lo_ = LN2_LO;
                k = 1;
            } else {
                hi_ = x + LN2_HI;
                lo_ = -LN2_LO;
                k = -1;
            }
        } else {
            k = (INVLN2 * x + if xsb == 0 { 0.5 } else { -0.5 }) as i32;
            let t = k as f64;
            hi_ = x - t * LN2_HI; /* t*ln2_hi is exact here */
            lo_ = t * LN2_LO;
        }
        x = hi_ - lo_;
        c = (hi_ - x) - lo_;
    } else if hx < 0x3c900000 {
        /* when |x|<2**-54, return x */
        let t = HUGE + x; /* return x with inexact flags when x!=0 */
        return x - (t - (HUGE + x));
    } else {
        k = 0;
        c = 0.0; /* not read: c is 0 when k is 0 */
    }

    /* x is now in primary range */
    let hfx = 0.5 * x;
    let hxs = x * hfx;
    let r1 = ONE + hxs * (Q1 + hxs * (Q2 + hxs * (Q3 + hxs * (Q4 + hxs * Q5))));
    let mut t = 3.0 - r1 * hfx;
    let mut e = hxs * ((r1 - t) / (6.0 - x * t));
    if k == 0 {
        return x - (x * e - hxs); /* c is 0 */
    }
    e = x * (e - c) - c;
    e -= hxs;
    if k == -1 {
        return 0.5 * (x - e) - 0.5;
    }
    if k == 1 {
        if x < -0.25 {
            return -2.0 * (e - (x + 0.5));
        } else {
            return ONE + 2.0 * (x - e);
        }
    }
    let mut y;
    if k <= -2 || k > 56 {
        /* suffice to return exp(x)-1 */
        y = ONE - (e - x);
        y = with_hi(y, hi(y).wrapping_add(k << 20)); /* add k to y's exponent */
        return y - ONE;
    }
    t = ONE;
    if k < 20 {
        t = with_hi(t, 0x3ff00000 - (0x200000 >> k)); /* t=1-2^-k */
        y = t - (e - x);
        y = with_hi(y, hi(y).wrapping_add(k << 20)); /* add k to y's exponent */
    } else {
        t = with_hi(t, (0x3ff - k) << 20); /* 2^-k */
        y = x - (e + t);
        y += ONE;
        y = with_hi(y, hi(y).wrapping_add(k << 20)); /* add k to y's exponent */
    }
    y
}
