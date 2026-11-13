// fdlibm 5.3 e_log.c, "@(#)e_log.c 1.3 95/01/18", transliterated to Rust.
//
// ====================================================
// Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
//
// Developed at SunSoft, a Sun Microsystems, Inc. business.
// Permission to use, copy, modify, and distribute this
// software is freely granted, provided that this notice
// is preserved.
// ====================================================

use super::{hi, lo, with_hi};

const LN2_HI: f64 = 6.93147180369123816490e-01; /* 3fe62e42 fee00000 */
const LN2_LO: f64 = 1.90821492927058770002e-10; /* 3dea39ef 35793c76 */
const TWO54: f64 = 1.80143985094819840000e+16; /* 43500000 00000000 */
const LG1: f64 = 6.666666666666735130e-01; /* 3FE55555 55555593 */
const LG2: f64 = 3.999999999940941908e-01; /* 3FD99999 9997FA04 */
const LG3: f64 = 2.857142874366239149e-01; /* 3FD24924 94229359 */
const LG4: f64 = 2.222219843214978396e-01; /* 3FCC71C5 1D8E78AF */
const LG5: f64 = 1.818357216161805012e-01; /* 3FC74664 96CB03DE */
const LG6: f64 = 1.531383769920937332e-01; /* 3FC39A09 D078C69F */
const LG7: f64 = 1.479819860511658591e-01; /* 3FC2F112 DF3E5244 */

const ZERO: f64 = 0.0;

/// `__ieee754_log(x)`: the natural logarithm.
// java: StrictMath.log
pub fn log(x: f64) -> f64 {
    let mut x = x;
    let mut hx: i32 = hi(x); /* high word of x */
    let lx: u32 = lo(x); /* low  word of x */

    let mut k: i32 = 0;
    if hx < 0x00100000 {
        /* x < 2**-1022  */
        if ((hx & 0x7fffffff) as u32 | lx) == 0 {
            return -TWO54 / ZERO; /* log(+-0)=-inf */
        }
        if hx < 0 {
            return (x - x) / ZERO; /* log(-#) = NaN */
        }
        k -= 54;
        x *= TWO54; /* subnormal number, scale up x */
        hx = hi(x); /* high word of x */
    }
    if hx >= 0x7ff00000 {
        return x + x;
    }
    k += (hx >> 20) - 1023;
    hx &= 0x000fffff;
    let mut i: i32 = (hx + 0x95f64) & 0x100000;
    x = with_hi(x, hx | (i ^ 0x3ff00000)); /* normalize x or x/2 */
    k += i >> 20;
    let f = x - 1.0;
    if (0x000fffff & (2 + hx)) < 3 {
        /* |f| < 2**-20 */
        if f == ZERO {
            if k == 0 {
                return ZERO;
            } else {
                let dk = k as f64;
                return dk * LN2_HI + dk * LN2_LO;
            }
        }
        let r = f * f * (0.5 - 0.33333333333333333 * f);
        if k == 0 {
            return f - r;
        } else {
            let dk = k as f64;
            return dk * LN2_HI - ((r - dk * LN2_LO) - f);
        }
    }
    let s = f / (2.0 + f);
    let dk = k as f64;
    let z = s * s;
    i = hx - 0x6147a;
    let w = z * z;
    let j: i32 = 0x6b851 - hx;
    let t1 = w * (LG2 + w * (LG4 + w * LG6));
    let t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)));
    i |= j;
    let r = t2 + t1;
    if i > 0 {
        let hfsq = 0.5 * f * f;
        if k == 0 { f - (hfsq - s * (hfsq + r)) } else { dk * LN2_HI - ((hfsq - (s * (hfsq + r) + dk * LN2_LO)) - f) }
    } else if k == 0 {
        f - s * (f - r)
    } else {
        dk * LN2_HI - ((s * (f - r) - dk * LN2_LO) - f)
    }
}
