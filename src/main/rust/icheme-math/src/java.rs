//! `java.lang.Math` semantics where they differ from Rust's defaults, and the compensated sum of
//! `DoubleStream.sum()`. Written from the Java specification; `tests/bit_tables.rs` checks each against the JDK.
//!
//! Rust's `as` casts from f64 to an integer already are Java's `(int)` and `(long)`: truncation toward zero,
//! saturation at the type's bounds, NaN to 0. [`to_int`] and [`to_long`] name them for the anchors and the tables.

use crate::fdlibm;

const SIGN_BIT: u64 = 0x8000_0000_0000_0000;

/// `Math.abs(double)`: the sign bit cleared (NaN stays NaN, -0.0 becomes +0.0).
// java: Math.abs
#[inline]
pub fn abs(a: f64) -> f64 {
    f64::from_bits(a.to_bits() & !SIGN_BIT)
}

/// `Math.max(double, double)`: NaN if either is NaN; -0.0 below +0.0.
// java: Math.max
#[inline]
pub fn max(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && a.to_bits() == SIGN_BIT {
        return b; // max(-0.0, +-0.0) is the other operand
    }
    if a >= b { a } else { b }
}

/// `Math.min(double, double)`: NaN if either is NaN; -0.0 below +0.0.
// java: Math.min
#[inline]
pub fn min(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && b.to_bits() == SIGN_BIT {
        return b; // min(+-0.0, -0.0) is -0.0
    }
    if a <= b { a } else { b }
}

/// `Math.floor(double)` (= `StrictMath.floor`): exact, signed zeros kept (floor(-0.5) is -1.0, floor(-0.0) -0.0).
// java: Math.floor
#[inline]
pub fn floor(a: f64) -> f64 {
    fdlibm::floor(a)
}

/// `Math.rint(double)`: the nearest integer, ties to even, the sign kept (rint(-0.4) is -0.0).
// java: Math.rint
pub fn rint(a: f64) -> f64 {
    const TWO52: f64 = 4503599627370496.0;
    let sign = fdlibm::copysign(1.0, a);
    let mut a = abs(a);
    if a < TWO52 {
        a = (TWO52 + a) - TWO52; // the addition rounds to an integer; the subtraction is exact
    }
    sign * a
}

/// `Math.round(double)`: floor(a + 1/2) computed exactly, saturated to the long range; NaN gives 0.
// java: Math.round
pub fn round(a: f64) -> i64 {
    if a.is_nan() {
        return 0;
    }
    let f = floor(a);
    // a - floor(a) is exact where it decides (Sterbenz, or a fraction above one half), and is 0 for |a| >= 2^52.
    let r = if a - f >= 0.5 { f + 1.0 } else { f };
    r as i64
}

/// `Math.ulp(double)`: the distance to the next double of larger magnitude; NaN gives NaN, an infinity +inf, zero
/// and subnormals `Double.MIN_VALUE`, `Double.MAX_VALUE` 2^971.
// java: Math.ulp
pub fn ulp(d: f64) -> f64 {
    let exponent = ((d.to_bits() >> 52) & 0x7ff) as i32 - 1023;
    match exponent {
        1024 => abs(d),
        -1023 => f64::from_bits(1),
        _ => {
            let e = exponent - 52;
            if e >= -1022 { f64::from_bits(((e + 1023) as u64) << 52) } else { f64::from_bits(1u64 << (e + 1074)) }
        }
    }
}

/// `Math.scalb(double, int)`: d 2^n rounded once. Java scales by powers of two in an order that rounds at most once,
/// so its result is the correctly rounded d 2^n for every n; fdlibm's `scalbn` computes the same correctly rounded
/// value (exact exponent arithmetic, one multiplication for a subnormal result, a guard for |n| > 50000), so this is
/// `scalbn`. The `scalb` table checks it, extreme n included.
// java: Math.scalb
#[inline]
pub fn scalb(d: f64, n: i32) -> f64 {
    fdlibm::scalbn(d, n)
}

/// The `IllegalArgumentException` cases of `Math.clamp`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ClampError {
    /// min is NaN.
    MinIsNaN,
    /// max is NaN.
    MaxIsNaN,
    /// min > max (for doubles in `Double.compare` order: +0.0 > -0.0).
    MinAboveMax,
}

/// `Math.clamp(double, double, double)`: `min(max, max(value, min))`; the error where Java throws
/// `IllegalArgumentException` (a NaN bound, or min after max in `Double.compare` order). A NaN value gives NaN.
// java: Math.clamp
#[allow(clippy::neg_cmp_op_on_partial_ord, reason = "Java's condition: NaN bounds are unordered")]
pub fn clamp(value: f64, min: f64, max: f64) -> Result<f64, ClampError> {
    if !(min < max) {
        if min.is_nan() {
            return Err(ClampError::MinIsNaN);
        }
        if max.is_nan() {
            return Err(ClampError::MaxIsNaN);
        }
        if double_compare(min, max) > 0 {
            return Err(ClampError::MinAboveMax);
        }
    }
    Ok(self::min(max, self::max(value, min)))
}

/// `Math.clamp(long, int, int)`: the long value clamped into [min, max], as an int; the error where Java throws.
// java: Math.clamp
pub fn clamp_long_to_int(value: i64, min: i32, max: i32) -> Result<i32, ClampError> {
    if min > max {
        return Err(ClampError::MinAboveMax);
    }
    Ok(value.clamp(min as i64, max as i64) as i32)
}

/// `Double.compare(double, double)`: the total order -inf < ... < -0.0 < +0.0 < ... < +inf < NaN, all NaNs equal.
// java: Double.compare
pub fn double_compare(a: f64, b: f64) -> i32 {
    if a < b {
        return -1;
    }
    if a > b {
        return 1;
    }
    // Equal, a signed zero pair, or a NaN: compare Double.doubleToLongBits (every NaN collapsed to one pattern).
    let ka = long_bits(a);
    let kb = long_bits(b);
    if ka == kb {
        0
    } else if ka < kb {
        -1
    } else {
        1
    }
}

/// `Double.doubleToLongBits`: the raw bits, every NaN as 0x7ff8000000000000.
fn long_bits(a: f64) -> i64 {
    if a.is_nan() { 0x7ff8_0000_0000_0000 } else { a.to_bits() as i64 }
}

/// The `(int)` cast of a double: truncation toward zero, saturation, NaN to 0 (Rust's `as`).
// java: (int) cast
#[inline]
pub fn to_int(d: f64) -> i32 {
    d as i32
}

/// The `(long)` cast of a double: truncation toward zero, saturation, NaN to 0 (Rust's `as`).
// java: (long) cast
#[inline]
pub fn to_long(d: f64) -> i64 {
    d as i64
}

/// The running state of a sequential `DoubleStream.sum()`: Kahan summation with the compensation stored negated,
/// plus the simple sum that restores a same-signed infinity.
#[derive(Clone, Copy, Debug, Default)]
pub struct StreamSum {
    high: f64,
    negated_low: f64,
    simple: f64,
}

impl StreamSum {
    /// An empty sum (its value is +0.0).
    pub const fn new() -> Self {
        Self { high: 0.0, negated_low: 0.0, simple: 0.0 }
    }

    /// Adds one element, in stream order: `Collectors.sumWithCompensation(ll, d); ll[2] += d`.
    // java: Collectors.sumWithCompensation
    #[inline]
    pub fn add(&mut self, value: f64) {
        let tmp = value - self.negated_low;
        let sum = self.high;
        let velvel = sum + tmp; // Little wolf of rounding error
        self.negated_low = (velvel - sum) - tmp;
        self.high = velvel;
        self.simple += value;
    }

    /// The sum: `Collectors.computeFinalSum`. The compensated total, unless it is a spurious NaN from same-signed
    /// infinities, then the simple sum's infinity.
    // java: Collectors.computeFinalSum
    pub fn sum(&self) -> f64 {
        let tmp = self.high - self.negated_low;
        if tmp.is_nan() && self.simple.is_infinite() { self.simple } else { tmp }
    }
}

/// `DoubleStream.of(values).sum()` for a sequential stream, values in order.
// java: DoublePipeline.sum
pub fn stream_sum(values: &[f64]) -> f64 {
    let mut s = StreamSum::new();
    for &v in values {
        s.add(v);
    }
    s.sum()
}
