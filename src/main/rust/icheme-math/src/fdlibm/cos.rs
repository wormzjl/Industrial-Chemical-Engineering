// fdlibm 5.3 s_cos.c, k_cos.c and k_sin.c, "@(#)s_cos.c 1.3 95/01/18", "@(#)k_cos.c 1.3 95/01/18",
// "@(#)k_sin.c 1.3 95/01/18", transliterated to Rust.
//
// ====================================================
// Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
//
// Developed at SunSoft, a Sun Microsystems, Inc. business.
// Permission to use, copy, modify, and distribute this
// software is freely granted, provided that this notice
// is preserved.
// ====================================================

use super::rem_pio2::rem_pio2;
use super::{from_words, hi};

/// `cos(x)`: the cosine, x in radians.
// java: StrictMath.cos
pub fn cos(x: f64) -> f64 {
    let mut y = [0.0f64; 2];
    let z = 0.0;

    /* High word of x. */
    let mut ix: i32 = hi(x);

    /* |x| ~< pi/4 */
    ix &= 0x7fffffff;
    if ix <= 0x3fe921fb {
        kernel_cos(x, z)
    }
    /* cos(Inf or NaN) is NaN */
    else if ix >= 0x7ff00000 {
        x - x
    }
    /* argument reduction needed */
    else {
        let n = rem_pio2(x, &mut y);
        match n & 3 {
            0 => kernel_cos(y[0], y[1]),
            1 => -kernel_sin(y[0], y[1], 1),
            2 => -kernel_cos(y[0], y[1]),
            _ => kernel_sin(y[0], y[1], 1),
        }
    }
}

// k_cos.c
const ONE: f64 = 1.00000000000000000000e+00; /* 0x3FF00000, 0x00000000 */
const C1: f64 = 4.16666666666666019037e-02; /* 0x3FA55555, 0x5555554C */
const C2: f64 = -1.38888888888741095749e-03; /* 0xBF56C16C, 0x16C15177 */
const C3: f64 = 2.48015872894767294178e-05; /* 0x3EFA01A0, 0x19CB1590 */
const C4: f64 = -2.75573143513906633035e-07; /* 0xBE927E4F, 0x809C52AD */
const C5: f64 = 2.08757232129817482790e-09; /* 0x3E21EE9E, 0xBDB4B1C4 */
const C6: f64 = -1.13596475577881948265e-11; /* 0xBDA8FAE9, 0xBE8838D4 */

/// `__kernel_cos(x, y)`: the cosine of x + y on [-pi/4, pi/4], y the tail of x.
fn kernel_cos(x: f64, y: f64) -> f64 {
    let ix: i32 = hi(x) & 0x7fffffff; /* ix = |x|'s high word*/
    if ix < 0x3e400000 {
        /* if x < 2**27 */
        if (x as i32) == 0 {
            return ONE; /* generate inexact */
        }
    }
    let z = x * x;
    let r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
    if ix < 0x3FD33333 {
        /* if |x| < 0.3 */
        ONE - (0.5 * z - (z * r - x * y))
    } else {
        let qx = if ix > 0x3fe90000 {
            /* x > 0.78125 */
            0.28125
        } else {
            from_words(ix - 0x00200000, 0) /* x/4 */
        };
        let hz = 0.5 * z - qx;
        let a = ONE - qx;
        a - (hz - (z * r - x * y))
    }
}

// k_sin.c
const HALF: f64 = 5.00000000000000000000e-01; /* 0x3FE00000, 0x00000000 */
const S1: f64 = -1.66666666666666324348e-01; /* 0xBFC55555, 0x55555549 */
const S2: f64 = 8.33333333332248946124e-03; /* 0x3F811111, 0x1110F8A6 */
const S3: f64 = -1.98412698298579493134e-04; /* 0xBF2A01A0, 0x19C161D5 */
const S4: f64 = 2.75573137070700676789e-06; /* 0x3EC71DE3, 0x57B1FE7D */
const S5: f64 = -2.50507602534068634195e-08; /* 0xBE5AE5E6, 0x8A2B9CEB */
const S6: f64 = 1.58969099521155010221e-10; /* 0x3DE5D93A, 0x5ACFD57C */

/// `__kernel_sin(x, y, iy)`: the sine of x + y on [-pi/4, pi/4]; iy = 0 if y is zero.
fn kernel_sin(x: f64, y: f64, iy: i32) -> f64 {
    let ix: i32 = hi(x) & 0x7fffffff; /* high word of x */
    if ix < 0x3e400000 {
        /* |x| < 2**-27 */
        if (x as i32) == 0 {
            return x; /* generate inexact */
        }
    }
    let z = x * x;
    let v = z * x;
    let r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
    if iy == 0 { x + v * (S1 + z * r) } else { x - ((z * (HALF * y - v * r) - y) - v * S1) }
}
