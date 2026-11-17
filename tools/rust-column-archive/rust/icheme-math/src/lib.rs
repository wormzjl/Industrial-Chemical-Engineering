//! The Java mathematics of the IChemE column solver, bit for bit (batch 2026-11-13-codebase-review, Rust stage (a)).
//!
//! The functions at the crate root are the twins of `com.wormzjl.icheme.science.math.DeterministicMath`: every result
//! has exactly the bits Java computes (same IEEE operations in the same order on the same elementary-function bits).
//! [`fdlibm`] holds the fdlibm 5.3 transliterations (`StrictMath`), [`pow`] `DeterministicPow`, [`fma`] a software
//! fused multiply-add (`Math.fma`), [`java`] the `java.lang.Math` semantics and the `DoubleStream.sum()` compensation,
//! [`banded`] the column's band LU (`V3BandedMatrix`, `V3BandedPivotedSolver`).
//! `tests/bit_tables.rs` compares every function with tables generated from Java.
//!
//! The rules (`src/main/rust/README.md`): no platform libm (`clippy.toml`), no fused multiply-add except [`fma::fma`]
//! where Java calls `Math.fma`, no reordering of sums, no unsafe code, no threads or mutable statics.

#![forbid(unsafe_code)]

pub mod banded;
pub mod fdlibm;
pub mod fma;
pub mod java;
pub mod pow;

/// The natural logarithm.
// java: DeterministicMath.log
#[inline]
pub fn log(x: f64) -> f64 {
    fdlibm::log(x)
}

/// e to the power x.
// java: DeterministicMath.exp
#[inline]
pub fn exp(x: f64) -> f64 {
    fdlibm::exp(x)
}

/// e to the power x, minus 1.
// java: DeterministicMath.expm1
#[inline]
pub fn expm1(x: f64) -> f64 {
    fdlibm::expm1(x)
}

/// The cube root.
// java: DeterministicMath.cbrt
#[inline]
pub fn cbrt(x: f64) -> f64 {
    fdlibm::cbrt(x)
}

/// The cosine, x in radians.
// java: DeterministicMath.cos
#[inline]
pub fn cos(x: f64) -> f64 {
    fdlibm::cos(x)
}

/// The arc cosine.
// java: DeterministicMath.acos
#[inline]
pub fn acos(x: f64) -> f64 {
    fdlibm::acos(x)
}

/// x to the power y by `DeterministicPow` (within 1 ulp of fdlibm, the same bits on every JVM).
// java: DeterministicMath.pow
#[inline]
pub fn pow(x: f64, y: f64) -> f64 {
    pow::pow(x, y)
}

/// x to the power y, bitwise `StrictMath.pow` (fdlibm e_pow.c through `FdlibmPow`).
// java: DeterministicMath.strictPow
#[inline]
pub fn strict_pow(x: f64, y: f64) -> f64 {
    fdlibm::pow(x, y)
}
