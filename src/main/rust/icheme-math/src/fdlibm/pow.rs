// fdlibm 5.3 e_pow.c, "@(#)e_pow.c 1.5 04/04/22 SMI", as transliterated in the Java twin FdlibmPow (constant tables
// hoisted out of the call), transliterated to Rust operation for operation.
//
// ====================================================
// Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.
//
// Permission to use, copy, modify, and distribute this
// software is freely granted, provided that this notice
// is preserved.
// ====================================================

use super::{hi, lo, scalbn};

const BP: [f64; 2] = [1.0, 1.5];
const DP_H: [f64; 2] = [0.0, 5.84962487220764160156e-01]; // 0x3FE2B803, 0x40000000
const DP_L: [f64; 2] = [0.0, 1.35003920212974897128e-08]; // 0x3E4CFDEB, 0x43CFD006
const ZERO: f64 = 0.0;
const ONE: f64 = 1.0;
const TWO: f64 = 2.0;
const TWO53: f64 = 9007199254740992.0; // 0x43400000, 0x00000000
const HUGE: f64 = 1.0e300;
const TINY: f64 = 1.0e-300;
// poly coefs for (3/2)*(log(x)-2s-2/3*s**3
const L1: f64 = 5.99999999999994648725e-01; // 0x3FE33333, 0x33333303
const L2: f64 = 4.28571428578550184252e-01; // 0x3FDB6DB6, 0xDB6FABFF
const L3: f64 = 3.33333329818377432918e-01; // 0x3FD55555, 0x518F264D
const L4: f64 = 2.72728123808534006489e-01; // 0x3FD17460, 0xA91D4101
const L5: f64 = 2.30660745775561754067e-01; // 0x3FCD864A, 0x93C9DB65
const L6: f64 = 2.06975017800338417784e-01; // 0x3FCA7E28, 0x4A454EEF
const P1: f64 = 1.66666666666666019037e-01; // 0x3FC55555, 0x5555553E
const P2: f64 = -2.77777777770155933842e-03; // 0xBF66C16C, 0x16BEBD93
const P3: f64 = 6.61375632143793436117e-05; // 0x3F11566A, 0xAF25DE2C
const P4: f64 = -1.65339022054652515390e-06; // 0xBEBBBD41, 0xC5D26BF1
const P5: f64 = 4.13813679705723846039e-08; // 0x3E663769, 0x72BEA4D0
const LG2: f64 = 6.93147180559945286227e-01; // 0x3FE62E42, 0xFEFA39EF
const LG2_H: f64 = 6.93147182464599609375e-01; // 0x3FE62E43, 0x00000000
const LG2_L: f64 = -1.90465429995776804525e-09; // 0xBE205C61, 0x0CA86C39
const OVT: f64 = 8.0085662595372944372e-0017; // -(1024-log2(ovfl+.5ulp))
const CP: f64 = 9.61796693925975554329e-01; // 0x3FEEC709, 0xDC3A03FD =2/(3ln2)
const CP_H: f64 = 9.61796700954437255859e-01; // 0x3FEEC709, 0xE0000000 =(float)cp
const CP_L: f64 = -7.02846165095275826516e-09; // 0xBE3E2FE0, 0x145B01F5 =tail of cp_h
const IVLN2: f64 = 1.44269504088896338700e+00; // 0x3FF71547, 0x652B82FE =1/ln2
const IVLN2_H: f64 = 1.44269502162933349609e+00; // 0x3FF71547, 0x60000000 =24b 1/ln2
const IVLN2_L: f64 = 1.92596299112661746887e-08; // 0x3E54AE0B, 0xF85DDF44 =1/ln2 tail

/// `__LO(x) = 0`.
#[inline(always)]
fn clear_lo(x: f64) -> f64 {
    f64::from_bits(x.to_bits() & 0xFFFF_FFFF_0000_0000)
}

/// `__HI(x) = high`, keeping the low word.
#[inline(always)]
fn with_hi(x: f64, high: i32) -> f64 {
    super::with_hi(x, high)
}

/// `Math.abs`: clears the sign bit.
#[inline(always)]
fn abs(x: f64) -> f64 {
    f64::from_bits(x.to_bits() & 0x7FFF_FFFF_FFFF_FFFF)
}

/// `__ieee754_pow(x, y)`: x to the power y, bitwise `StrictMath.pow`.
// java: FdlibmPow.pow
pub fn pow(x: f64, y: f64) -> f64 {
    let mut z: f64;
    let mut ax: f64;
    let z_h: f64;
    let z_l: f64;
    let mut p_h: f64;
    let mut p_l: f64;
    let mut t1: f64;
    let t2: f64;
    let mut r: f64;
    let mut s: f64;
    let mut t: f64;
    let mut u: f64;
    let mut v: f64;
    let w: f64;
    let mut i: i32;
    let mut j: i32;
    let mut k: i32;
    let mut yisint: i32;
    let mut n: i32;
    let mut ix: i32;

    let hx: i32 = hi(x);
    let lx: i32 = lo(x) as i32;
    let hy: i32 = hi(y);
    let ly: i32 = lo(y) as i32;
    ix = hx & 0x7fffffff;
    let iy: i32 = hy & 0x7fffffff;

    // y==zero: x**0 = 1
    if (iy | ly) == 0 {
        return ONE;
    }

    // +-NaN return x+y
    if ix > 0x7ff00000 || ((ix == 0x7ff00000) && (lx != 0)) || iy > 0x7ff00000 || ((iy == 0x7ff00000) && (ly != 0)) {
        return x + y;
    }

    // determine if y is an odd int when x < 0: 0 not an integer, 1 odd, 2 even
    yisint = 0;
    if hx < 0 {
        if iy >= 0x43400000 {
            yisint = 2; // even integer y
        } else if iy >= 0x3ff00000 {
            k = (iy >> 20) - 0x3ff; // exponent
            if k > 20 {
                j = ((ly as u32) >> (52 - k)) as i32;
                if (j << (52 - k)) == ly {
                    yisint = 2 - (j & 1);
                }
            } else if ly == 0 {
                j = iy >> (20 - k);
                if (j << (20 - k)) == iy {
                    yisint = 2 - (j & 1);
                }
            }
        }
    }

    // special value of y
    if ly == 0 {
        if iy == 0x7ff00000 {
            // y is +-inf
            if ((ix - 0x3ff00000) | lx) == 0 {
                return y - y; // inf**+-1 is NaN
            } else if ix >= 0x3ff00000 {
                // (|x|>1)**+-inf = inf,0
                return if hy >= 0 { y } else { ZERO };
            } else {
                // (|x|<1)**-,+inf = inf,0
                return if hy < 0 { -y } else { ZERO };
            }
        }
        if iy == 0x3ff00000 {
            // y is +-1
            if hy < 0 {
                return ONE / x;
            } else {
                return x;
            }
        }
        if hy == 0x40000000 {
            return x * x; // y is 2
        }
        if hy == 0x3fe00000 {
            // y is 0.5
            if hx >= 0 {
                // x >= +0
                return x.sqrt();
            }
        }
    }

    ax = abs(x);
    // special value of x
    if lx == 0 && (ix == 0x7ff00000 || ix == 0 || ix == 0x3ff00000) {
        z = ax; // x is +-0,+-inf,+-1
        if hy < 0 {
            z = ONE / z; // z = (1/|x|)
        }
        if hx < 0 {
            if ((ix - 0x3ff00000) | yisint) == 0 {
                z = (z - z) / (z - z); // (-1)**non-int is NaN
            } else if yisint == 1 {
                z = -z; // (x<0)**odd = -(|x|**odd)
            }
        }
        return z;
    }

    n = (hx >> 31) + 1;

    // (x<0)**(non-int) is NaN
    if (n | yisint) == 0 {
        return (x - x) / (x - x);
    }

    s = ONE; // s (sign of result -ve**odd) = -1 else = 1
    if (n | (yisint - 1)) == 0 {
        s = -ONE; // (-ve)**(odd int)
    }

    // |y| is huge
    if iy > 0x41e00000 {
        // if |y| > 2**31
        if iy > 0x43f00000 {
            // if |y| > 2**64, must o/uflow
            if ix <= 0x3fefffff {
                return if hy < 0 { HUGE * HUGE } else { TINY * TINY };
            }
            if ix >= 0x3ff00000 {
                return if hy > 0 { HUGE * HUGE } else { TINY * TINY };
            }
        }
        // over/underflow if x is not close to one
        if ix < 0x3fefffff {
            return if hy < 0 { s * HUGE * HUGE } else { s * TINY * TINY };
        }
        if ix > 0x3ff00000 {
            return if hy > 0 { s * HUGE * HUGE } else { s * TINY * TINY };
        }
        // now |1-x| is tiny <= 2**-20, suffice to compute log(x) by x-x^2/2+x^3/3-x^4/4
        t = ax - ONE; // t has 20 trailing zeros
        let w = (t * t) * (0.5 - t * (0.3333333333333333333333 - t * 0.25));
        u = IVLN2_H * t; // ivln2_h has 21 sig. bits
        v = t * IVLN2_L - w * IVLN2;
        t1 = u + v;
        t1 = clear_lo(t1);
        t2 = v - (t1 - u);
    } else {
        let mut s2: f64;
        let s_h: f64;
        let s_l: f64;
        let mut t_h: f64;
        let mut t_l: f64;
        n = 0;
        // take care subnormal number
        if ix < 0x00100000 {
            ax *= TWO53;
            n -= 53;
            ix = hi(ax);
        }
        n += (ix >> 20) - 0x3ff;
        j = ix & 0x000fffff;
        // determine interval
        ix = j | 0x3ff00000; // normalize ix
        if j <= 0x3988E {
            k = 0; // |x|<sqrt(3/2)
        } else if j < 0xBB67A {
            k = 1; // |x|<sqrt(3)
        } else {
            k = 0;
            n += 1;
            ix -= 0x00100000;
        }
        ax = with_hi(ax, ix);
        let kk = k as usize;

        // compute ss = s_h+s_l = (x-1)/(x+1) or (x-1.5)/(x+1.5)
        u = ax - BP[kk]; // bp[0]=1.0, bp[1]=1.5
        v = ONE / (ax + BP[kk]);
        let ss = u * v;
        s_h = clear_lo(ss);
        // t_h=ax+bp[k] High
        t_h = ZERO;
        t_h = with_hi(t_h, ((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18));
        t_l = ax - (t_h - BP[kk]);
        s_l = v * ((u - s_h * t_h) - s_h * t_l);
        // compute log(ax)
        s2 = ss * ss;
        r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))));
        r += s_l * (s_h + ss);
        s2 = s_h * s_h;
        t_h = 3.0 + s2 + r;
        t_h = clear_lo(t_h);
        t_l = r - ((t_h - 3.0) - s2);
        // u+v = ss*(1+...)
        u = s_h * t_h;
        v = s_l * t_h + t_l * ss;
        // 2/(3log2)*(ss+...)
        p_h = u + v;
        p_h = clear_lo(p_h);
        p_l = v - (p_h - u);
        z_h = CP_H * p_h; // cp_h+cp_l = 2/(3*log2)
        z_l = CP_L * p_h + p_l * CP + DP_L[kk];
        // log2(ax) = (ss+..)*2/(3*log2) = n + dp_h + z_h + z_l
        t = n as f64;
        t1 = ((z_h + z_l) + DP_H[kk]) + t;
        t1 = clear_lo(t1);
        t2 = z_l - (((t1 - t) - DP_H[kk]) - z_h);
    }

    // split up y into y1+y2 and compute (y1+y2)*(t1+t2)
    let y1 = clear_lo(y);
    p_l = (y - y1) * t1 + y * t2;
    p_h = y1 * t1;
    z = p_l + p_h;
    j = hi(z);
    i = lo(z) as i32;
    if j >= 0x40900000 {
        // z >= 1024
        if (j.wrapping_sub(0x40900000) | i) != 0 {
            // if z > 1024
            return s * HUGE * HUGE; // overflow
        } else if p_l + OVT > z - p_h {
            return s * HUGE * HUGE; // overflow
        }
    } else if (j & 0x7fffffff) >= 0x4090cc00 {
        // z <= -1075
        if (j.wrapping_sub(0xc090cc00u32 as i32) | i) != 0 {
            // z < -1075
            return s * TINY * TINY; // underflow
        } else if p_l <= z - p_h {
            return s * TINY * TINY; // underflow
        }
    }
    // compute 2**(p_h+p_l)
    i = j & 0x7fffffff;
    k = (i >> 20) - 0x3ff;
    n = 0;
    if i > 0x3fe00000 {
        // if |z| > 0.5, set n = [z+0.5]
        n = j + (0x00100000 >> (k + 1));
        k = ((n & 0x7fffffff) >> 20) - 0x3ff; // new k for n
        t = ZERO;
        t = with_hi(t, n & !(0x000fffff >> k));
        n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
        if j < 0 {
            n = -n;
        }
        p_h -= t;
    }
    t = p_l + p_h;
    t = clear_lo(t);
    u = t * LG2_H;
    v = (p_l - (t - p_h)) * LG2 + t * LG2_L;
    z = u + v;
    w = v - (z - u);
    t = z * z;
    t1 = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
    r = (z * t1) / (t1 - TWO) - (w + z * w);
    z = ONE - (r - z);
    j = hi(z);
    j = j.wrapping_add(n << 20);
    if (j >> 20) <= 0 {
        z = scalbn(z, n); // subnormal output (Java: Math.scalb, the same correctly rounded z 2^n)
    } else {
        z = with_hi(z, hi(z).wrapping_add(n << 20));
    }
    s * z
}
