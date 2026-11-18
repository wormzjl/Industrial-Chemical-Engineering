//! fdlibm 5.3, transliterated from the C sources of `https://netlib.org/fdlibm/` (kept in the main checkout's
//! `research/2026-11-13-codebase-review/fdlibm-5.3/`), operation for operation. `java.lang.StrictMath` is specified to
//! return the fdlibm 5.3 results, so each function here returns `StrictMath`'s bits; `tests/bit_tables.rs` checks it.
//!
//! Each file keeps the Sun Microsystems notice of the C files it comes from. The C's `__HI(x)` and `__LO(x)` word
//! access is the helpers below: `hi` is the signed high word (`int`), `lo` the unsigned low word.

// A transliteration keeps the C as written: its 21-digit decimal constants (each parses to the bits in its hex
// comment), its own values of pi, ln 2 and 1/ln 2, its NaN-making (x - x) / (x - x), and its declarations at the top.
#![allow(
    clippy::excessive_precision,
    clippy::approx_constant,
    clippy::eq_op,
    clippy::needless_late_init,
    reason = "the fdlibm C source, operation for operation"
)]

mod acos;
mod cbrt;
mod cos;
mod exp;
mod expm1;
mod floor;
mod log;
mod pow;
mod rem_pio2;
mod scalbn;

pub use acos::acos;
pub use cbrt::cbrt;
pub use cos::cos;
pub use exp::exp;
pub use expm1::expm1;
pub use floor::floor;
pub use log::log;
pub use pow::pow;
pub use rem_pio2::rem_pio2;
pub use scalbn::{copysign, scalbn};

/// `__HI(x)`: the high 32 bits, as the C's signed `int`.
#[inline(always)]
pub(crate) const fn hi(x: f64) -> i32 {
    (x.to_bits() >> 32) as i32
}

/// `__LO(x)`: the low 32 bits, unsigned.
#[inline(always)]
pub(crate) const fn lo(x: f64) -> u32 {
    x.to_bits() as u32
}

/// `__HI(x) = high`, keeping the low word.
#[inline(always)]
pub(crate) const fn with_hi(x: f64, high: i32) -> f64 {
    f64::from_bits(((high as u32 as u64) << 32) | (x.to_bits() & 0xffff_ffff))
}

/// `__LO(x) = low`, keeping the high word.
#[inline(always)]
pub(crate) const fn with_lo(x: f64, low: u32) -> f64 {
    f64::from_bits((x.to_bits() & 0xffff_ffff_0000_0000) | low as u64)
}

/// The double of high word `high` and low word `low`.
#[inline(always)]
pub(crate) const fn from_words(high: i32, low: u32) -> f64 {
    f64::from_bits(((high as u32 as u64) << 32) | low as u64)
}
