// fdlibm 5.3 s_scalbn.c and s_copysign.c, "@(#)s_scalbn.c 1.3 95/01/18", "@(#)s_copysign.c 1.3 95/01/18",
// transliterated to Rust.
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

const TWO54: f64 = 1.80143985094819840000e+16; /* 0x43500000, 0x00000000 */
const TWOM54: f64 = 5.55111512312578270212e-17; /* 0x3C900000, 0x00000000 */
const HUGE: f64 = 1.0e+300;
const TINY: f64 = 1.0e-300;

/// `scalbn(x, n)`: x 2^n, rounded once (exact unless the result is subnormal, overflows or underflows). The C's
/// `k + n` may overflow an `int` for |n| near 2^31; the source guards it with the |n| > 50000 tests, and the sum
/// wraps here as it does in the C.
pub fn scalbn(x: f64, n: i32) -> f64 {
    let mut x = x;
    let mut hx: i32 = hi(x);
    let lx: u32 = lo(x);
    let mut k: i32 = (hx & 0x7ff00000) >> 20; /* extract exponent */
    if k == 0 {
        /* 0 or subnormal x */
        if (lx | (hx & 0x7fffffff) as u32) == 0 {
            return x; /* +-0 */
        }
        x *= TWO54;
        hx = hi(x);
        k = ((hx & 0x7ff00000) >> 20) - 54;
        if n < -50000 {
            return TINY * x; /*underflow*/
        }
    }
    if k == 0x7ff {
        return x + x; /* NaN or Inf */
    }
    k = k.wrapping_add(n);
    if k > 0x7fe {
        return HUGE * copysign(HUGE, x); /* overflow  */
    }
    if k > 0 {
        /* normal result */
        return with_hi(x, (hx & 0x800fffffu32 as i32) | (k << 20));
    }
    if k <= -54 {
        if n > 50000 {
            /* in case integer overflow in n+k */
            return HUGE * copysign(HUGE, x); /*overflow*/
        } else {
            return TINY * copysign(TINY, x); /*underflow*/
        }
    }
    k += 54; /* subnormal result */
    x = with_hi(x, (hx & 0x800fffffu32 as i32) | (k << 20));
    x * TWOM54
}

/// `copysign(x, y)`: x with the sign bit of y.
pub fn copysign(x: f64, y: f64) -> f64 {
    with_hi(x, (hi(x) & 0x7fffffff) | (hi(y) & 0x80000000u32 as i32))
}
