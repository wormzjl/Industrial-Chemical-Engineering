// fdlibm 5.3 e_exp.c, "@(#)e_exp.c 1.6 04/04/22", transliterated to Rust.
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
const HALF: [f64; 2] = [0.5, -0.5];
const HUGE: f64 = 1.0e+300;
const TWOM1000: f64 = 9.33263618503218878990e-302; /* 2**-1000=0x01700000,0*/
const O_THRESHOLD: f64 = 7.09782712893383973096e+02; /* 0x40862E42, 0xFEFA39EF */
const U_THRESHOLD: f64 = -7.45133219101941108420e+02; /* 0xc0874910, 0xD52D3051 */
const LN2HI: [f64; 2] = [
    6.93147180369123816490e-01,  /* 0x3fe62e42, 0xfee00000 */
    -6.93147180369123816490e-01, /* 0xbfe62e42, 0xfee00000 */
];
const LN2LO: [f64; 2] = [
    1.90821492927058770002e-10,  /* 0x3dea39ef, 0x35793c76 */
    -1.90821492927058770002e-10, /* 0xbdea39ef, 0x35793c76 */
];
const INVLN2: f64 = 1.44269504088896338700e+00; /* 0x3ff71547, 0x652b82fe */
const P1: f64 = 1.66666666666666019037e-01; /* 0x3FC55555, 0x5555553E */
const P2: f64 = -2.77777777770155933842e-03; /* 0xBF66C16C, 0x16BEBD93 */
const P3: f64 = 6.61375632143793436117e-05; /* 0x3F11566A, 0xAF25DE2C */
const P4: f64 = -1.65339022054652515390e-06; /* 0xBEBBBD41, 0xC5D26BF1 */
const P5: f64 = 4.13813679705723846039e-08; /* 0x3E663769, 0x72BEA4D0 */

/// `__ieee754_exp(x)`: e to the power x.
// java: StrictMath.exp
pub fn exp(x: f64) -> f64 {
    let mut x = x;
    let mut hi_ = 0.0;
    let mut lo_ = 0.0;
    let mut k: i32 = 0;

    let mut hx: u32 = hi(x) as u32; /* high word of x */
    let xsb: usize = ((hx >> 31) & 1) as usize; /* sign bit of x */
    hx &= 0x7fffffff; /* high word of |x| */

    /* filter out non-finite argument */
    if hx >= 0x40862E42 {
        /* if |x|>=709.78... */
        if hx >= 0x7ff00000 {
            if ((hx & 0xfffff) | lo(x)) != 0 {
                return x + x; /* NaN */
            } else {
                return if xsb == 0 { x } else { 0.0 }; /* exp(+-inf)={inf,0} */
            }
        }
        if x > O_THRESHOLD {
            return HUGE * HUGE; /* overflow */
        }
        if x < U_THRESHOLD {
            return TWOM1000 * TWOM1000; /* underflow */
        }
    }

    /* argument reduction */
    if hx > 0x3fd62e42 {
        /* if  |x| > 0.5 ln2 */
        if hx < 0x3FF0A2B2 {
            /* and |x| < 1.5 ln2 */
            hi_ = x - LN2HI[xsb];
            lo_ = LN2LO[xsb];
            k = 1 - xsb as i32 - xsb as i32;
        } else {
            k = (INVLN2 * x + HALF[xsb]) as i32;
            let t = k as f64;
            hi_ = x - t * LN2HI[0]; /* t*ln2HI is exact here */
            lo_ = t * LN2LO[0];
        }
        x = hi_ - lo_;
    } else if hx < 0x3e300000 {
        /* when |x|<2**-28 */
        if HUGE + x > ONE {
            return ONE + x; /* trigger inexact */
        }
    } else {
        k = 0;
    }

    /* x is now in primary range */
    let t = x * x;
    let c = x - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
    if k == 0 {
        return ONE - ((x * c) / (c - 2.0) - x);
    }
    let y = ONE - ((lo_ - (x * c) / (2.0 - c)) - hi_);
    if k >= -1021 {
        with_hi(y, hi(y).wrapping_add(k << 20)) /* add k to y's exponent */
    } else {
        with_hi(y, hi(y).wrapping_add((k + 1000) << 20)) * TWOM1000 /* add k to y's exponent */
    }
}
