// fdlibm 5.3 s_cbrt.c, "@(#)s_cbrt.c 1.3 95/01/18", transliterated to Rust.
//
// ====================================================
// Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
//
// Developed at SunSoft, a Sun Microsystems, Inc. business.
// Permission to use, copy, modify, and distribute this
// software is freely granted, provided that this notice
// is preserved.
// ====================================================

use super::{hi, lo, with_hi, with_lo};

const B1: u32 = 715094163; /* B1 = (682-0.03306235651)*2**20 */
const B2: u32 = 696219795; /* B2 = (664-0.03306235651)*2**20 */

const C: f64 = 5.42857142857142815906e-01; /* 19/35     = 0x3FE15F15, 0xF15F15F1 */
const D: f64 = -7.05306122448979611050e-01; /* -864/1225 = 0xBFE691DE, 0x2532C834 */
const E: f64 = 1.41428571428571436819e+00; /* 99/70     = 0x3FF6A0EA, 0x0EA0EA0F */
const F: f64 = 1.60714285714285720630e+00; /* 45/28     = 0x3FF9B6DB, 0x6DB6DB6E */
const G: f64 = 3.57142857142857150787e-01; /* 5/14      = 0x3FD6DB6D, 0xB6DB6DB7 */

/// `cbrt(x)`: the cube root.
// java: StrictMath.cbrt
pub fn cbrt(x: f64) -> f64 {
    let mut x = x;
    let mut t: f64 = 0.0;

    let mut hx: i32 = hi(x); /* high word of x */
    let sign: u32 = hx as u32 & 0x80000000; /* sign= sign(x) */
    hx ^= sign as i32;
    if hx >= 0x7ff00000 {
        return x + x; /* cbrt(NaN,INF) is itself */
    }
    if (hx as u32 | lo(x)) == 0 {
        return x; /* cbrt(0) is itself */
    }

    x = with_hi(x, hx); /* x <- |x| */
    /* rough cbrt to 5 bits */
    if hx < 0x00100000 {
        /* subnormal number */
        t = with_hi(t, 0x43500000); /* set t= 2**54 */
        t *= x;
        t = with_hi(t, hi(t) / 3 + B2 as i32);
    } else {
        t = with_hi(t, hx / 3 + B1 as i32);
    }

    /* new cbrt to 23 bits, may be implemented in single precision */
    let mut r = t * t / x;
    let mut s = C + r * t;
    t *= G + F / (s + E + D / s);

    /* chopped to 20 bits and make it larger than cbrt(x) */
    t = with_lo(t, 0);
    t = with_hi(t, hi(t) + 0x00000001);

    /* one step newton iteration to 53 bits with error less than 0.667 ulps */
    s = t * t; /* t*t is exact */
    r = x / s;
    let w = t + t;
    r = (r - t) / (w + r); /* r-s is exact */
    t += t * r;

    /* retore the sign bit */
    with_hi(t, hi(t) | sign as i32)
}
