//! A software fused multiply-add: `a * b + c` with one rounding, as `Math.fma` (IEEE 754 fusedMultiplyAdd, round to
//! nearest, ties to even). `f64::mul_add` is not used: without the `fma` target feature it calls the C library.
//!
//! Algorithm. Non-finite operands, a zero factor and a zero addend reduce to ordinary operations whose results are
//! exact before their one rounding (see the cases in [`fma`]). Otherwise all three are finite and nonzero; each is an
//! integer significand times a power of two (`m 2^e`, `m < 2^53`), so the product is exactly `ma mb 2^(ea + eb)` with
//! `ma mb < 2^106`, held in a `u128`. The operand of larger magnitude is shifted left until its top bit is bit 125;
//! the other is aligned to it by shifting right, and if that drops nonzero bits they are replaced by a sticky 1 in
//! bit 0. The two are added or subtracted in the `u128` (no overflow: both are below 2^126) and the sum is rounded
//! once to 53 bits, or to the subnormal grid 2^-1074, ties to even; a carry out of the rounding and overflow to
//! infinity follow from the bit layout.
//!
//! Why this is correctly rounded. Without a dropped bit the sum is exact, so the one rounding is the IEEE rounding of
//! the exact value. Bits drop only when the smaller operand's top bit is below bit 106 while the larger's is at bit
//! 125, so the sum is at least 2^125 - 2^106 and the rounding point is at bit 72 or above. The larger operand's low 20
//! bits are zero (it was shifted left by at least 20), so with the sticky 1 the computed sum is odd: it lies strictly
//! between the same two multiples of 2 as the exact sum, which itself lies strictly between two consecutive integers,
//! and is never a multiple of 2. Every rounding boundary (a multiple of 2^71 or more) is even, so the computed sum and
//! the exact sum are on the same side of every boundary and round to the same double.

/// `a * b + c` rounded once.
// java: Math.fma
pub fn fma(a: f64, b: f64, c: f64) -> f64 {
    if !a.is_finite() || !b.is_finite() || c.is_nan() {
        // a or b infinite or NaN: the product is exact (infinite, or NaN for 0 * inf), and so is its sum with c
        // (an infinity, NaN, or inf - inf = NaN); a NaN c gives NaN.
        return a * b + c;
    }
    if a == 0.0 || b == 0.0 {
        // The exact product is a signed zero; zero + c is exact, with IEEE's sign rule for a zero sum.
        return a * b + c;
    }
    if c.is_infinite() {
        // a b is finite (even if its rounding would overflow), so the sum is c.
        return c;
    }
    if c == 0.0 {
        // a b is not zero, so the sign of a zero c does not matter: the result is a b rounded once.
        return a * b;
    }

    let (sa, ma, ea) = split(a);
    let (sb, mb, eb) = split(b);
    let (sc, mc, ec) = split(c);
    let product_negative = sa != sb;
    let mp: u128 = (ma as u128) * (mb as u128);
    let ep: i32 = ea + eb;
    let mc: u128 = mc as u128;

    // The operand with the higher top bit is "big".
    let top_p = ep + bit_length(mp) - 1;
    let top_c = ec + bit_length(mc) - 1;
    let (big_neg, big_m, big_e, small_neg, small_m, small_e) =
        if top_p >= top_c { (product_negative, mp, ep, sc, mc, ec) } else { (sc, mc, ec, product_negative, mp, ep) };
    let shift_big = 125 - (bit_length(big_m) - 1); // at least 125 - 105 = 20
    let big = big_m << shift_big;
    let lsb_e = big_e - shift_big; // the weight of bit 0
    let d = small_e - lsb_e;
    let small: u128 = if d >= 0 {
        small_m << d // fits: the small top bit is at most bit 125
    } else if -d >= 128 {
        1 // all bits dropped, small_m is not zero
    } else {
        let s = (-d) as u32;
        let kept = small_m >> s;
        let dropped = small_m & ((1u128 << s) - 1);
        kept | (dropped != 0) as u128
    };

    let (negative, sum) = if big_neg == small_neg {
        (big_neg, big + small)
    } else if big >= small {
        (big_neg, big - small)
    } else {
        (small_neg, small - big)
    };
    if sum == 0 {
        return 0.0; // exact cancellation: +0 in round to nearest
    }
    round(negative, sum, lsb_e)
}

/// (negative, m, e) with |x| = m 2^e, m < 2^53, for finite nonzero x.
fn split(x: f64) -> (bool, u64, i32) {
    let bits = x.to_bits();
    let negative = (bits >> 63) != 0;
    let biased = ((bits >> 52) & 0x7ff) as i32;
    let fraction = bits & 0x000f_ffff_ffff_ffff;
    if biased == 0 { (negative, fraction, -1074) } else { (negative, fraction | (1u64 << 52), biased - 1075) }
}

fn bit_length(m: u128) -> i32 {
    128 - m.leading_zeros() as i32
}

/// The double nearest to (-1)^negative m 2^e, ties to even; m > 0.
fn round(negative: bool, m: u128, e: i32) -> f64 {
    let length = bit_length(m);
    // Bits to drop: down to 53 significant bits, or down to the subnormal grid 2^-1074, whichever drops more.
    let drop = (length - 53).max(-1074 - e);
    let (q, lsb_e): (u128, i32) = if drop <= 0 {
        (m << (-drop), e + drop)
    } else if drop >= 128 {
        // Not reached from fma (bit 0 weighs at least 2^-1199, so drop <= 125); kept so no shift exceeds 127.
        // m < 2^127 <= 2^(drop - 1): below half of the smallest step, rounds to zero.
        (0, e + drop)
    } else {
        let s = drop as u32;
        let mut q = m >> s;
        let rest = m & ((1u128 << s) - 1);
        let half = 1u128 << (s - 1);
        if rest > half || (rest == half && (q & 1) == 1) {
            q += 1;
        }
        (q, e + drop)
    };
    // q 2^lsb_e with q <= 2^53 and lsb_e >= -1074: the encoding is (lsb_e + 1074) << 52 plus q, which also carries a
    // rounded-up q = 2^53 into the exponent and encodes q < 2^52 at lsb_e = -1074 as a subnormal.
    let encoded: u128 = (((lsb_e + 1074) as u128) << 52) + q;
    let magnitude = if encoded >= 0x7ff0_0000_0000_0000 { f64::INFINITY } else { f64::from_bits(encoded as u64) };
    if negative { -magnitude } else { magnitude }
}
