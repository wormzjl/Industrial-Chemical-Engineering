// fdlibm 5.3 s_floor.c, "@(#)s_floor.c 1.3 95/01/18", transliterated to Rust.
//
// ====================================================
// Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
//
// Developed at SunSoft, a Sun Microsystems, Inc. business.
// Permission to use, copy, modify, and distribute this
// software is freely granted, provided that this notice
// is preserved.
// ====================================================

use super::{from_words, hi, lo};

const HUGE: f64 = 1.0e300;

/// `floor(x)`: the largest integral value not greater than x, by bit manipulation (exact).
// java: StrictMath.floor
pub fn floor(x: f64) -> f64 {
    let mut i0: i32 = hi(x);
    let mut i1: u32 = lo(x);
    let j0: i32 = ((i0 >> 20) & 0x7ff) - 0x3ff;
    if j0 < 20 {
        if j0 < 0 {
            /* raise inexact if x != 0 */
            if HUGE + x > 0.0 {
                /* return 0*sign(x) if |x|<1 */
                if i0 >= 0 {
                    i0 = 0;
                    i1 = 0;
                } else if ((i0 & 0x7fffffff) as u32 | i1) != 0 {
                    i0 = 0xbff00000u32 as i32;
                    i1 = 0;
                }
            }
        } else {
            let i: u32 = 0x000fffffu32 >> j0;
            if ((i0 as u32 & i) | i1) == 0 {
                return x; /* x is integral */
            }
            if HUGE + x > 0.0 {
                /* raise inexact flag */
                if i0 < 0 {
                    i0 += 0x00100000 >> j0;
                }
                i0 &= !(i as i32);
                i1 = 0;
            }
        }
    } else if j0 > 51 {
        if j0 == 0x400 {
            return x + x; /* inf or NaN */
        } else {
            return x; /* x is integral */
        }
    } else {
        let i: u32 = 0xffffffffu32 >> (j0 - 20);
        if (i1 & i) == 0 {
            return x; /* x is integral */
        }
        if HUGE + x > 0.0 {
            /* raise inexact flag */
            if i0 < 0 {
                if j0 == 20 {
                    i0 += 1;
                } else {
                    let j: u32 = i1.wrapping_add(1u32 << (52 - j0));
                    if j < i1 {
                        i0 += 1; /* got a carry */
                    }
                    i1 = j;
                }
            }
            i1 &= !i;
        }
    }
    from_words(i0, i1)
}
