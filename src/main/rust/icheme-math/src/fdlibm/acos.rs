// fdlibm 5.3 e_acos.c, "@(#)e_acos.c 1.3 95/01/18", transliterated to Rust.
//
// ====================================================
// Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
//
// Developed at SunSoft, a Sun Microsystems, Inc. business.
// Permission to use, copy, modify, and distribute this
// software is freely granted, provided that this notice
// is preserved.
// ====================================================

use super::{hi, lo, with_lo};

const ONE: f64 = 1.00000000000000000000e+00; /* 0x3FF00000, 0x00000000 */
const PI: f64 = 3.14159265358979311600e+00; /* 0x400921FB, 0x54442D18 */
const PIO2_HI: f64 = 1.57079632679489655800e+00; /* 0x3FF921FB, 0x54442D18 */
const PIO2_LO: f64 = 6.12323399573676603587e-17; /* 0x3C91A626, 0x33145C07 */
const PS0: f64 = 1.66666666666666657415e-01; /* 0x3FC55555, 0x55555555 */
const PS1: f64 = -3.25565818622400915405e-01; /* 0xBFD4D612, 0x03EB6F7D */
const PS2: f64 = 2.01212532134862925881e-01; /* 0x3FC9C155, 0x0E884455 */
const PS3: f64 = -4.00555345006794114027e-02; /* 0xBFA48228, 0xB5688F3B */
const PS4: f64 = 7.91534994289814532176e-04; /* 0x3F49EFE0, 0x7501B288 */
const PS5: f64 = 3.47933107596021167570e-05; /* 0x3F023DE1, 0x0DFDF709 */
const QS1: f64 = -2.40339491173441421878e+00; /* 0xC0033A27, 0x1C8A2D4B */
const QS2: f64 = 2.02094576023350569471e+00; /* 0x40002AE5, 0x9C598AC8 */
const QS3: f64 = -6.88283971605453293030e-01; /* 0xBFE6066C, 0x1B8D0159 */
const QS4: f64 = 7.70381505559019352791e-02; /* 0x3FB3B8C5, 0xB12E9282 */

/// `__ieee754_acos(x)`: the arc cosine, in [0, pi].
// java: StrictMath.acos
pub fn acos(x: f64) -> f64 {
    let hx: i32 = hi(x);
    let ix: i32 = hx & 0x7fffffff;
    if ix >= 0x3ff00000 {
        /* |x| >= 1 */
        if ((ix - 0x3ff00000) as u32 | lo(x)) == 0 {
            /* |x|==1 */
            if hx > 0 {
                return 0.0; /* acos(1) = 0  */
            } else {
                return PI + 2.0 * PIO2_LO; /* acos(-1)= pi */
            }
        }
        return (x - x) / (x - x); /* acos(|x|>1) is NaN */
    }
    if ix < 0x3fe00000 {
        /* |x| < 0.5 */
        if ix <= 0x3c600000 {
            return PIO2_HI + PIO2_LO; /*if|x|<2**-57*/
        }
        let z = x * x;
        let p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
        let q = ONE + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
        let r = p / q;
        PIO2_HI - (x - (PIO2_LO - x * r))
    } else if hx < 0 {
        /* x < -0.5 */
        let z = (ONE + x) * 0.5;
        let p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
        let q = ONE + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
        let s = z.sqrt();
        let r = p / q;
        let w = r * s - PIO2_LO;
        PI - 2.0 * (s + w)
    } else {
        /* x > 0.5 */
        let z = (ONE - x) * 0.5;
        let s = z.sqrt();
        let df = with_lo(s, 0);
        let c = (z - df * df) / (s + df);
        let p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
        let q = ONE + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
        let r = p / q;
        let w = r * s + c;
        2.0 * (df + w)
    }
}
