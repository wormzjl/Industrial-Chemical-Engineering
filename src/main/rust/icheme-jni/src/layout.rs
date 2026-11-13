//! The pure part of the boundary: the ABI number, the formulation token, the transport status, the layout checks
//! and the packing of `nativeBandedSolve` (`src/main/rust/LAYOUT.md`). No JNI here, so `cargo test` covers it.

use icheme_math::banded::{self, BandError, BandedMatrix, Outcome, Workspace};

/// Rises with any change of a block layout or a signature; `NativeColumnSolver.ABI` must equal it.
pub const ABI: i32 = 1;

/// The Java formulation this library computes: `V3ColumnCalculator.FORMULATION_REVISION`, `WET_ASSUMPTIONS_REVISION`
/// and `HEAT_ASSUMPTIONS_REVISION`, joined by `;`. Written in the source, not passed in at build time, so a library
/// whose equations lag the Java ones is refused; Java compares the bytes. The test `formulation_is_the_java_token`
/// reads the Java constants and fails when they move without this line.
pub const FORMULATION: &std::ffi::CStr =
    c"v3-dry-mesh-r24-flash-trace-phase-r1-derived-pairs-r1;v3-wet-assumptions-r3;v3-heat-assumptions-r1";

/// `nativeSelfTest`'s output length.
pub const SELF_TEST_SLOTS: usize = 256;
/// `nativeBandedSolve`'s `evidence`: backward error, minimum pivot, maximum pivot, pivot growth.
pub const EVIDENCE_LENGTH: usize = 4;
/// `nativeBandedSolve`'s `counts`: outcome, pivot swaps, full width, updated entries (low, high), full-width entries
/// (low, high).
pub const COUNTS_LENGTH: usize = 7;

/// The transport status every export returns (plan section 3).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum Status {
    Ok = 0,
    Cancelled = 1,
    Deadline = 2,
    BadLayout = 3,
    UnsupportedPackage = 4,
    OutOfMemory = 5,
    Panic = 6,
    FpEnvironment = 7,
}

impl From<BandError> for Status {
    fn from(error: BandError) -> Self {
        match error {
            BandError::InvalidInput(_) => Status::BadLayout,
            BandError::OutOfMemory => Status::OutOfMemory,
        }
    }
}

/// Whether this thread computes with subnormals: `f64::MIN_POSITIVE / 2` is not flushed to zero (FTZ) and a
/// subnormal operand is not read as zero (DAZ). Another native library may have set either on a JVM thread.
pub fn fp_environment_ok() -> bool {
    let half = std::hint::black_box(f64::MIN_POSITIVE) / std::hint::black_box(2.0);
    let read = std::hint::black_box(f64::from_bits(1)) * std::hint::black_box(1.0);
    half != 0.0 && read != 0.0
}

/// The checked lengths of one `nativeBandedSolve` call.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BandedShape {
    pub size: usize,
    pub lower_bandwidth: usize,
    pub upper_bandwidth: usize,
    pub band_length: usize,
}

/// Every length of a `nativeBandedSolve` call against the header, before any index is used: `size >= 1`,
/// `kl, ku >= 0`, `band = size (kl + ku + 1)` within the int range, `rhs = solution = size`, `evidence = 4`,
/// `counts = 7`.
#[allow(clippy::too_many_arguments)] // the call's own parameter list
pub fn check_banded_layout(
    size: i32,
    lower_bandwidth: i32,
    upper_bandwidth: i32,
    band_length: usize,
    rhs_length: usize,
    solution_length: usize,
    evidence_length: usize,
    counts_length: usize,
) -> Result<BandedShape, Status> {
    if size < 1 || lower_bandwidth < 0 || upper_bandwidth < 0 {
        return Err(Status::BadLayout);
    }
    let (size, kl, ku) = (size as usize, lower_bandwidth as usize, upper_bandwidth as usize);
    let expected = BandedMatrix::storage_length(size, kl, ku).map_err(|_| Status::BadLayout)?;
    if band_length != expected
        || rhs_length != size
        || solution_length != size
        || evidence_length != EVIDENCE_LENGTH
        || counts_length != COUNTS_LENGTH
    {
        return Err(Status::BadLayout);
    }
    Ok(BandedShape { size, lower_bandwidth: kl, upper_bandwidth: ku, band_length: expected })
}

/// What `nativeBandedSolve` writes back: the solution (on success only), the evidence and the counts.
#[derive(Clone, Debug, PartialEq)]
pub struct BandedOutputs {
    pub solution: Option<Vec<f64>>,
    pub evidence: [f64; EVIDENCE_LENGTH],
    pub counts: [i32; COUNTS_LENGTH],
}

/// A long as (low, high) 32-bit halves; Java reads `((long) high << 32) | (low & 0xffffffffL)`.
pub fn split_long(value: i64) -> (i32, i32) {
    (value as i32, (value >> 32) as i32)
}

/// One band solve on copied-in arrays, packed for the copy-out. `band` is `V3BandedMatrix`'s storage.
// java: V3BandedPivotedSolver.solve
pub fn banded_solve(shape: BandedShape, band: Vec<f64>, rhs: &[f64]) -> Result<BandedOutputs, Status> {
    let matrix = BandedMatrix::from_values(shape.size, shape.lower_bandwidth, shape.upper_bandwidth, band)?;
    let result = banded::solve(&matrix, rhs, &mut Workspace::new())?;
    let (updated_low, updated_high) = split_long(result.updated_entries);
    let (full_low, full_high) = split_long(result.full_width_entries);
    Ok(BandedOutputs {
        solution: if result.outcome == Outcome::Success { Some(result.solution) } else { None },
        evidence: [
            result.backward_error,
            result.minimum_pivot_magnitude,
            result.maximum_pivot_magnitude,
            result.pivot_growth,
        ],
        counts: [
            result.outcome as i32,
            result.pivot_swaps,
            result.full_width as i32,
            updated_low,
            updated_high,
            full_low,
            full_high,
        ],
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shape(size: i32, kl: i32, ku: i32) -> Result<BandedShape, Status> {
        let n = (size.max(0) * (kl.max(0) + ku.max(0) + 1)) as usize;
        check_banded_layout(size, kl, ku, n, size.max(0) as usize, size.max(0) as usize, 4, 7)
    }

    #[test]
    fn layout_checks_every_length_against_the_header() {
        assert_eq!(shape(8, 2, 1).unwrap().band_length, 32);
        assert_eq!(shape(0, 1, 1), Err(Status::BadLayout));
        assert_eq!(shape(4, -1, 1), Err(Status::BadLayout));
        assert_eq!(shape(4, 1, -1), Err(Status::BadLayout));
        assert_eq!(check_banded_layout(4, 1, 1, 11, 4, 4, 4, 7), Err(Status::BadLayout)); // band
        assert_eq!(check_banded_layout(4, 1, 1, 12, 3, 4, 4, 7), Err(Status::BadLayout)); // rhs
        assert_eq!(check_banded_layout(4, 1, 1, 12, 4, 5, 4, 7), Err(Status::BadLayout)); // solution
        assert_eq!(check_banded_layout(4, 1, 1, 12, 4, 4, 3, 7), Err(Status::BadLayout)); // evidence
        assert_eq!(check_banded_layout(4, 1, 1, 12, 4, 4, 4, 6), Err(Status::BadLayout)); // counts
        // size (kl + ku + 1) beyond the int range, as Java's multiplyExact refuses it
        assert_eq!(check_banded_layout(70_000, 20_000, 20_000, 0, 70_000, 70_000, 4, 7), Err(Status::BadLayout));
    }

    #[test]
    fn longs_split_into_the_halves_java_joins() {
        for value in [0i64, 1, -1, 359_489_024, i64::MAX, i64::MIN, 0x1_2345_6789] {
            let (low, high) = split_long(value);
            assert_eq!(((high as i64) << 32) | (low as i64 & 0xffff_ffff), value);
        }
    }

    #[test]
    fn packing_carries_the_solver_result() {
        let s = shape(3, 1, 1).unwrap();
        // tridiagonal [4 1 0; 1 4 1; 0 1 4] in V3BandedMatrix storage: row r at r*3 + c - r + 1
        let band = vec![0.0, 4.0, 1.0, 1.0, 4.0, 1.0, 1.0, 4.0, 0.0];
        let rhs = [1.0, 2.0, 3.0];
        let out = banded_solve(s, band.clone(), &rhs).unwrap();
        let direct =
            banded::solve(&BandedMatrix::from_values(3, 1, 1, band).unwrap(), &rhs, &mut Workspace::new()).unwrap();
        assert_eq!(out.solution.as_deref(), Some(direct.solution.as_slice()));
        assert_eq!(out.evidence[0].to_bits(), direct.backward_error.to_bits());
        assert_eq!(out.counts[0], 0);
        assert_eq!(out.counts[1], direct.pivot_swaps);
        // a zero matrix is a failure with no solution
        let zero = banded_solve(s, vec![0.0; 9], &rhs).unwrap();
        assert_eq!((zero.solution, zero.counts[0]), (None, Outcome::SingularZeroMatrix as i32));
        // a non-finite entry is a layout violation: Java's V3BandedMatrix never holds one
        let mut bad = vec![0.0; 9];
        bad[1] = f64::NAN;
        assert_eq!(banded_solve(s, bad, &rhs), Err(Status::BadLayout));
        assert_eq!(banded_solve(s, vec![1.0; 9], &[1.0, f64::INFINITY, 1.0]), Err(Status::BadLayout));
    }

    #[test]
    fn this_thread_keeps_subnormals() {
        assert!(fp_environment_ok());
    }

    #[test]
    fn formulation_is_the_java_token() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../java/com/wormzjl/icheme/science/column/v3/V3ColumnCalculator.java");
        let source = std::fs::read_to_string(&path).expect("V3ColumnCalculator.java is readable");
        let constant = |name: &str| {
            let start = source.find(&format!("String {name} = \"")).unwrap_or_else(|| panic!("{name} not found"));
            let rest = &source[start + name.len() + 11..];
            rest[..rest.find('"').unwrap()].to_owned()
        };
        let java = format!(
            "{};{};{}",
            constant("FORMULATION_REVISION"),
            constant("WET_ASSUMPTIONS_REVISION"),
            constant("HEAT_ASSUMPTIONS_REVISION")
        );
        assert_eq!(FORMULATION.to_str().unwrap(), java, "the Java formulation moved: port the change, then this token");
    }
}
