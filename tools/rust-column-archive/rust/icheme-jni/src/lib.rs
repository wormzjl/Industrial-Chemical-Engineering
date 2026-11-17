//! `icheme_column`: the JNI library of the column solver port (batch 2026-11-13-codebase-review, Rust part 2a).
//!
//! The exports of plan section 3 that exist now: the handshake (`nativeAbi`, `nativeFormulation`, `nativeSelfTest`)
//! and the first export that carries work, `nativeBandedSolve` (the profile puts 52 to 63 % of a column solve in
//! `V3BandedPivotedSolver`). The Java declarations are in `NativeColumnSolver`
//! (`com.wormzjl.icheme.science.column.v3`); the layouts are in `src/main/rust/LAYOUT.md`.
//!
//! Every export runs its body in `catch_unwind` (a panic is status 6, never an unwinding across the boundary),
//! checks the floating-point environment (status 7), checks every length before any index (status 3), reserves
//! its buffers fallibly (status 5), copies arrays in and out by region and checks for a pending exception after
//! each copy. This crate is the only one with `unsafe`: the `JNIEnv` calls in `env.rs` and the export signatures.

pub mod env;
pub mod layout;
pub mod self_test;

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni_sys::{JNIEnv, jclass, jdoubleArray, jint, jintArray, jstring};

use crate::env::Env;
use crate::layout::{BandedShape, COUNTS_LENGTH, EVIDENCE_LENGTH, SELF_TEST_SLOTS, Status};

/// Runs `body`, mapping a panic to `Status::Panic` and an error to its status.
fn guarded(body: impl FnOnce() -> Result<(), Status>) -> jint {
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(Ok(())) => Status::Ok as jint,
        Ok(Err(status)) => status as jint,
        Err(_) => Status::Panic as jint,
    }
}

/// A vector of `length` zeros, reserved fallibly.
fn reserved(length: usize) -> Result<Vec<f64>, Status> {
    let mut v = Vec::new();
    v.try_reserve_exact(length).map_err(|_| Status::OutOfMemory)?;
    v.resize(length, 0.0);
    Ok(v)
}

/// `private static native int nativeAbi()`: the layout and signature generation, [`layout::ABI`].
///
/// # Safety
/// Called by the JVM only.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_nativeAbi(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jint {
    layout::ABI
}

/// `private static native String nativeFormulation()`: [`layout::FORMULATION`]; null if the string cannot be made.
///
/// # Safety
/// Called by the JVM only, with the `JNIEnv` of the calling thread.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_nativeFormulation(
    env: *mut JNIEnv,
    _class: jclass,
) -> jstring {
    let made = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the JVM passes this thread's env.
        unsafe { Env::new(env) }.map(|e| e.new_string(layout::FORMULATION))
    }));
    match made {
        Ok(Some(s)) => s,
        _ => std::ptr::null_mut(),
    }
}

/// `private static native int nativeSelfTest(double[] out)`: fills the 256 known answers of
/// [`self_test::self_test_values`]; `out` must hold exactly 256 doubles.
///
/// # Safety
/// Called by the JVM only, with the `JNIEnv` of the calling thread and the method's own array argument.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_nativeSelfTest(
    env: *mut JNIEnv,
    _class: jclass,
    out: jdoubleArray,
) -> jint {
    guarded(|| {
        // SAFETY: the JVM passes this thread's env.
        let env = unsafe { Env::new(env) }.ok_or(Status::BadLayout)?;
        if !layout::fp_environment_ok() {
            return Err(Status::FpEnvironment);
        }
        // SAFETY: `out` is this call's double[] argument (or null, refused).
        if unsafe { env.array_length(out) }? != SELF_TEST_SLOTS {
            return Err(Status::BadLayout);
        }
        let values = self_test::self_test_values();
        // SAFETY: `out` holds exactly SELF_TEST_SLOTS doubles (checked above).
        unsafe { env.set_doubles(out, &values) }
    })
}

/// `private static native int nativeBandedSolve(int size, int kl, int ku, double[] band, double[] rhs,
/// double[] solution, double[] evidence, int[] counts)`: one `V3BandedPivotedSolver.solve` on `V3BandedMatrix`'s
/// storage. Writes `solution` on success only, `evidence` (4) and `counts` (7) always (`LAYOUT.md`).
///
/// # Safety
/// Called by the JVM only, with the `JNIEnv` of the calling thread and the method's own array arguments.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)] // the Java signature
pub unsafe extern "system" fn Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_nativeBandedSolve(
    env: *mut JNIEnv,
    _class: jclass,
    size: jint,
    kl: jint,
    ku: jint,
    band: jdoubleArray,
    rhs: jdoubleArray,
    solution: jdoubleArray,
    evidence: jdoubleArray,
    counts: jintArray,
) -> jint {
    guarded(|| {
        // SAFETY: the JVM passes this thread's env.
        let env = unsafe { Env::new(env) }.ok_or(Status::BadLayout)?;
        if !layout::fp_environment_ok() {
            return Err(Status::FpEnvironment);
        }
        // SAFETY: each of the five arrays is this call's own argument of the declared type, or null (refused).
        let lengths = unsafe {
            (
                env.array_length(band)?,
                env.array_length(rhs)?,
                env.array_length(solution)?,
                env.array_length(evidence)?,
                env.array_length(counts)?,
            )
        };
        let shape: BandedShape =
            layout::check_banded_layout(size, kl, ku, lengths.0, lengths.1, lengths.2, lengths.3, lengths.4)?;
        let mut band_values = reserved(shape.band_length)?;
        let mut rhs_values = reserved(shape.size)?;
        // SAFETY: the arrays hold exactly these lengths (checked by check_banded_layout).
        unsafe {
            env.get_doubles(band, &mut band_values)?;
            env.get_doubles(rhs, &mut rhs_values)?;
        }
        let outputs = layout::banded_solve(shape, band_values, &rhs_values)?;
        debug_assert_eq!((outputs.evidence.len(), outputs.counts.len()), (EVIDENCE_LENGTH, COUNTS_LENGTH));
        // SAFETY: solution holds `size`, evidence 4 and counts 7 elements (checked by check_banded_layout).
        unsafe {
            if let Some(x) = &outputs.solution {
                env.set_doubles(solution, x)?;
            }
            env.set_doubles(evidence, &outputs.evidence)?;
            env.set_ints(counts, &outputs.counts)?;
        }
        Ok(())
    })
}
