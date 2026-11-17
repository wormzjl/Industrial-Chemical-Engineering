// fdlibm 5.3 e_rem_pio2.c and k_rem_pio2.c, "@(#)e_rem_pio2.c 1.4 95/01/18", "@(#)k_rem_pio2.c 1.3 95/01/18",
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

use super::{floor, from_words, hi, lo, scalbn};

/*
 * Table of constants for 2/pi, 396 Hex digits (476 decimal) of 2/pi
 */
const TWO_OVER_PI: [i32; 66] = [
    0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62, //
    0x95993C, 0x439041, 0xFE5163, 0xABDEBB, 0xC561B7, 0x246E3A, //
    0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129, //
    0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41, //
    0x3991D6, 0x398353, 0x39F49C, 0x845F8B, 0xBDF928, 0x3B1FF8, //
    0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF, //
    0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5, //
    0xF17B3D, 0x0739F7, 0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08, //
    0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3, //
    0x91615E, 0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880, //
    0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B, //
];

const NPIO2_HW: [i32; 32] = [
    0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, //
    0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C, //
    0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A, //
    0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C, //
    0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB, //
    0x404858EB, 0x404921FB, //
];

/*
 * invpio2:  53 bits of 2/pi
 * pio2_1:   first  33 bit of pi/2
 * pio2_1t:  pi/2 - pio2_1
 * pio2_2:   second 33 bit of pi/2
 * pio2_2t:  pi/2 - (pio2_1+pio2_2)
 * pio2_3:   third  33 bit of pi/2
 * pio2_3t:  pi/2 - (pio2_1+pio2_2+pio2_3)
 */
const ZERO: f64 = 0.00000000000000000000e+00; /* 0x00000000, 0x00000000 */
const HALF: f64 = 5.00000000000000000000e-01; /* 0x3FE00000, 0x00000000 */
const TWO24: f64 = 1.67772160000000000000e+07; /* 0x41700000, 0x00000000 */
const INVPIO2: f64 = 6.36619772367581382433e-01; /* 0x3FE45F30, 0x6DC9C883 */
const PIO2_1: f64 = 1.57079632673412561417e+00; /* 0x3FF921FB, 0x54400000 */
const PIO2_1T: f64 = 6.07710050650619224932e-11; /* 0x3DD0B461, 0x1A626331 */
const PIO2_2: f64 = 6.07710050630396597660e-11; /* 0x3DD0B461, 0x1A600000 */
const PIO2_2T: f64 = 2.02226624879595063154e-21; /* 0x3BA3198A, 0x2E037073 */
const PIO2_3: f64 = 2.02226624871116645580e-21; /* 0x3BA3198A, 0x2E000000 */
const PIO2_3T: f64 = 8.47842766036889956997e-32; /* 0x397B839A, 0x252049C1 */

/// `__ieee754_rem_pio2(x, y)`: y[0] + y[1] = x - n pi/2 with |y| <= pi/4; returns n.
// java: StrictMath (FdLibm.RemPio2), called by StrictMath.cos
pub fn rem_pio2(x: f64, y: &mut [f64; 2]) -> i32 {
    let mut z: f64;
    let mut w: f64;
    let mut t: f64;
    let mut r: f64;
    let mut tx = [0.0f64; 3];

    let hx: i32 = hi(x); /* high word of x */
    let ix: i32 = hx & 0x7fffffff;
    if ix <= 0x3fe921fb {
        /* |x| ~<= pi/4 , no need for reduction */
        y[0] = x;
        y[1] = 0.0;
        return 0;
    }
    if ix < 0x4002d97c {
        /* |x| < 3pi/4, special case with n=+-1 */
        if hx > 0 {
            z = x - PIO2_1;
            if ix != 0x3ff921fb {
                /* 33+53 bit pi is good enough */
                y[0] = z - PIO2_1T;
                y[1] = (z - y[0]) - PIO2_1T;
            } else {
                /* near pi/2, use 33+33+53 bit pi */
                z -= PIO2_2;
                y[0] = z - PIO2_2T;
                y[1] = (z - y[0]) - PIO2_2T;
            }
            return 1;
        } else {
            /* negative x */
            z = x + PIO2_1;
            if ix != 0x3ff921fb {
                /* 33+53 bit pi is good enough */
                y[0] = z + PIO2_1T;
                y[1] = (z - y[0]) + PIO2_1T;
            } else {
                /* near pi/2, use 33+33+53 bit pi */
                z += PIO2_2;
                y[0] = z + PIO2_2T;
                y[1] = (z - y[0]) + PIO2_2T;
            }
            return -1;
        }
    }
    if ix <= 0x413921fb {
        /* |x| ~<= 2^19*(pi/2), medium size */
        t = fabs(x);
        let n: i32 = (t * INVPIO2 + HALF) as i32;
        let fn_ = n as f64;
        r = t - fn_ * PIO2_1;
        w = fn_ * PIO2_1T; /* 1st round good to 85 bit */
        if n < 32 && ix != NPIO2_HW[(n - 1) as usize] {
            y[0] = r - w; /* quick check no cancellation */
        } else {
            let j: i32 = ix >> 20;
            y[0] = r - w;
            let mut i: i32 = j - ((hi(y[0]) >> 20) & 0x7ff);
            if i > 16 {
                /* 2nd iteration needed, good to 118 */
                t = r;
                w = fn_ * PIO2_2;
                r = t - w;
                w = fn_ * PIO2_2T - ((t - r) - w);
                y[0] = r - w;
                i = j - ((hi(y[0]) >> 20) & 0x7ff);
                if i > 49 {
                    /* 3rd iteration need, 151 bits acc */
                    t = r; /* will cover all possible cases */
                    w = fn_ * PIO2_3;
                    r = t - w;
                    w = fn_ * PIO2_3T - ((t - r) - w);
                    y[0] = r - w;
                }
            }
        }
        y[1] = (r - y[0]) - w;
        if hx < 0 {
            y[0] = -y[0];
            y[1] = -y[1];
            return -n;
        } else {
            return n;
        }
    }
    /*
     * all other (large) arguments
     */
    if ix >= 0x7ff00000 {
        /* x is inf or NaN */
        y[0] = x - x;
        y[1] = y[0];
        return 0;
    }
    /* set z = scalbn(|x|,ilogb(x)-23) */
    let e0: i32 = (ix >> 20) - 1046; /* e0 = ilogb(z)-23; */
    z = from_words(ix - (e0 << 20), lo(x));
    for item in tx.iter_mut().take(2) {
        *item = (z as i32) as f64;
        z = (z - *item) * TWO24;
    }
    tx[2] = z;
    let mut nx: usize = 3;
    while tx[nx - 1] == ZERO {
        nx -= 1; /* skip zero term */
    }
    let n = kernel_rem_pio2(&tx, y, e0, nx as i32, 2, &TWO_OVER_PI);
    if hx < 0 {
        y[0] = -y[0];
        y[1] = -y[1];
        return -n;
    }
    n
}

/// `fabs(x)` of fdlibm: clears the sign bit.
#[inline(always)]
fn fabs(x: f64) -> f64 {
    f64::from_bits(x.to_bits() & 0x7fff_ffff_ffff_ffff)
}

// k_rem_pio2.c
const INIT_JK: [i32; 4] = [2, 3, 4, 6]; /* initial value for jk */

const PIO2: [f64; 8] = [
    1.57079625129699707031e+00, /* 0x3FF921FB, 0x40000000 */
    7.54978941586159635335e-08, /* 0x3E74442D, 0x00000000 */
    5.39030252995776476554e-15, /* 0x3CF84698, 0x80000000 */
    3.28200341580791294123e-22, /* 0x3B78CC51, 0x60000000 */
    1.27065575308067607349e-29, /* 0x39F01B83, 0x80000000 */
    1.22933308981111328932e-36, /* 0x387A2520, 0x40000000 */
    2.73370053816464559624e-44, /* 0x36E38222, 0x80000000 */
    2.16741683877804819444e-51, /* 0x3569F31D, 0x00000000 */
];

const ONE: f64 = 1.0;
const TWON24: f64 = 5.96046447753906250000e-08; /* 0x3E700000, 0x00000000 */

/// `__kernel_rem_pio2(x, y, e0, nx, prec, ipio2)`: the last three bits of N and the fraction of x 2/pi, for x
/// given as nx 24-bit pieces x[i] 2^(e0 - 24 i); see the method notes of k_rem_pio2.c.
fn kernel_rem_pio2(x: &[f64], y: &mut [f64], e0: i32, nx: i32, prec: i32, ipio2: &[i32]) -> i32 {
    let mut iq = [0i32; 20];
    let mut f = [0.0f64; 20];
    let mut fq = [0.0f64; 20];
    let mut q = [0.0f64; 20];
    let mut z: f64;
    let mut fw: f64;
    let mut n: i32;
    let mut ih: i32;
    let mut i: i32;
    let mut j: i32;
    let mut k: i32;

    /* initialize jk*/
    let jk: i32 = INIT_JK[prec as usize];
    let jp: i32 = jk;

    /* determine jx,jv,q0, note that 3>q0 */
    let jx: i32 = nx - 1;
    let mut jv: i32 = (e0 - 3) / 24;
    if jv < 0 {
        jv = 0;
    }
    let mut q0: i32 = e0 - 24 * (jv + 1);

    /* set up f[0] to f[jx+jk] where f[jx+jk] = ipio2[jv+jk] */
    j = jv - jx;
    let m: i32 = jx + jk;
    i = 0;
    while i <= m {
        f[i as usize] = if j < 0 { ZERO } else { ipio2[j as usize] as f64 };
        i += 1;
        j += 1;
    }

    /* compute q[0],q[1],...q[jk] */
    i = 0;
    while i <= jk {
        fw = 0.0;
        j = 0;
        while j <= jx {
            fw += x[j as usize] * f[(jx + i - j) as usize];
            j += 1;
        }
        q[i as usize] = fw;
        i += 1;
    }

    let mut jz: i32 = jk;
    loop {
        // recompute:
        /* distill q[] into iq[] reversingly */
        i = 0;
        j = jz;
        z = q[jz as usize];
        while j > 0 {
            fw = ((TWON24 * z) as i32) as f64;
            iq[i as usize] = (z - TWO24 * fw) as i32;
            z = q[(j - 1) as usize] + fw;
            i += 1;
            j -= 1;
        }

        /* compute n */
        z = scalbn(z, q0); /* actual value of z */
        z -= 8.0 * floor(z * 0.125); /* trim off integer >= 8 */
        n = z as i32;
        z -= n as f64;
        ih = 0;
        if q0 > 0 {
            /* need iq[jz-1] to determine n */
            i = iq[(jz - 1) as usize] >> (24 - q0);
            n += i;
            iq[(jz - 1) as usize] -= i << (24 - q0);
            ih = iq[(jz - 1) as usize] >> (23 - q0);
        } else if q0 == 0 {
            ih = iq[(jz - 1) as usize] >> 23;
        } else if z >= 0.5 {
            ih = 2;
        }

        if ih > 0 {
            /* q > 0.5 */
            n += 1;
            let mut carry: i32 = 0;
            i = 0;
            while i < jz {
                /* compute 1-q */
                j = iq[i as usize];
                if carry == 0 {
                    if j != 0 {
                        carry = 1;
                        iq[i as usize] = 0x1000000 - j;
                    }
                } else {
                    iq[i as usize] = 0xffffff - j;
                }
                i += 1;
            }
            if q0 > 0 {
                /* rare case: chance is 1 in 12 */
                match q0 {
                    1 => iq[(jz - 1) as usize] &= 0x7fffff,
                    2 => iq[(jz - 1) as usize] &= 0x3fffff,
                    _ => {}
                }
            }
            if ih == 2 {
                z = ONE - z;
                if carry != 0 {
                    z -= scalbn(ONE, q0);
                }
            }
        }

        /* check if recomputation is needed */
        if z == ZERO {
            j = 0;
            i = jz - 1;
            while i >= jk {
                j |= iq[i as usize];
                i -= 1;
            }
            if j == 0 {
                /* need recomputation */
                k = 1;
                while iq[(jk - k) as usize] == 0 {
                    k += 1; /* k = no. of terms needed */
                }

                i = jz + 1;
                while i <= jz + k {
                    /* add q[jz+1] to q[jz+k] */
                    f[(jx + i) as usize] = ipio2[(jv + i) as usize] as f64;
                    fw = 0.0;
                    j = 0;
                    while j <= jx {
                        fw += x[j as usize] * f[(jx + i - j) as usize];
                        j += 1;
                    }
                    q[i as usize] = fw;
                    i += 1;
                }
                jz += k;
                continue; // goto recompute
            }
        }
        break;
    }

    /* chop off zero terms */
    if z == 0.0 {
        jz -= 1;
        q0 -= 24;
        while iq[jz as usize] == 0 {
            jz -= 1;
            q0 -= 24;
        }
    } else {
        /* break z into 24-bit if necessary */
        z = scalbn(z, -q0);
        if z >= TWO24 {
            fw = ((TWON24 * z) as i32) as f64;
            iq[jz as usize] = (z - TWO24 * fw) as i32;
            jz += 1;
            q0 += 24;
            iq[jz as usize] = fw as i32;
        } else {
            iq[jz as usize] = z as i32;
        }
    }

    /* convert integer "bit" chunk to floating-point value */
    fw = scalbn(ONE, q0);
    i = jz;
    while i >= 0 {
        q[i as usize] = fw * (iq[i as usize] as f64);
        fw *= TWON24;
        i -= 1;
    }

    /* compute PIo2[0,...,jp]*q[jz,...,0] */
    i = jz;
    while i >= 0 {
        fw = 0.0;
        k = 0;
        while k <= jp && k <= jz - i {
            fw += PIO2[k as usize] * q[(i + k) as usize];
            k += 1;
        }
        fq[(jz - i) as usize] = fw;
        i -= 1;
    }

    /* compress fq[] into y[] */
    match prec {
        0 => {
            fw = 0.0;
            i = jz;
            while i >= 0 {
                fw += fq[i as usize];
                i -= 1;
            }
            y[0] = if ih == 0 { fw } else { -fw };
        }
        1 | 2 => {
            fw = 0.0;
            i = jz;
            while i >= 0 {
                fw += fq[i as usize];
                i -= 1;
            }
            y[0] = if ih == 0 { fw } else { -fw };
            fw = fq[0] - fw;
            i = 1;
            while i <= jz {
                fw += fq[i as usize];
                i += 1;
            }
            y[1] = if ih == 0 { fw } else { -fw };
        }
        3 => {
            /* painful */
            i = jz;
            while i > 0 {
                fw = fq[(i - 1) as usize] + fq[i as usize];
                fq[i as usize] += fq[(i - 1) as usize] - fw;
                fq[(i - 1) as usize] = fw;
                i -= 1;
            }
            i = jz;
            while i > 1 {
                fw = fq[(i - 1) as usize] + fq[i as usize];
                fq[i as usize] += fq[(i - 1) as usize] - fw;
                fq[(i - 1) as usize] = fw;
                i -= 1;
            }
            fw = 0.0;
            i = jz;
            while i >= 2 {
                fw += fq[i as usize];
                i -= 1;
            }
            if ih == 0 {
                y[0] = fq[0];
                y[1] = fq[1];
                y[2] = fw;
            } else {
                y[0] = -fq[0];
                y[1] = -fq[1];
                y[2] = -fw;
            }
        }
        _ => {}
    }
    n & 7
}
