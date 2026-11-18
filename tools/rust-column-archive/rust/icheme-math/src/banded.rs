//! The column's band LU: `linalg/V3BandedMatrix` and `linalg/V3BandedPivotedSolver` (the version with the P8
//! extent bound and the P9 split update loop of batch 2026-11-13-codebase-review), operation for operation.
//!
//! The matrix keeps Java's storage: `values[size * (kl + ku + 1)]`, entry (row, column) at
//! `row * (kl + ku + 1) + column - row + kl`. The factorization works in the LAPACK `dgbtrf` row layout of width
//! `2 kl + ku + 1`. Java's `Math.max`, `Math.min` and `Math.abs` are `crate::java`'s (NaN and signed zero as Java);
//! a negative zero entering the band becomes a positive zero (`copy_from`); `kl` and `ku` are the caller's.
//!
//! Java's exceptions become values: an argument Java rejects with `IllegalArgumentException` is a
//! [`BandError::InvalidInput`]; the two `IllegalStateException`s of the factorization are the outcomes
//! [`Outcome::NonFiniteEntry`] and [`Outcome::LostPivot`]. A buffer that cannot be reserved is
//! [`BandError::OutOfMemory`] (Rust would otherwise abort the process).

#![allow(clippy::needless_range_loop, reason = "the Java index loops, kept as written")]

use crate::java::{abs, max, min};

const PIVOT_TOLERANCE: f64 = 1.0e-13;
const ILL_CONDITIONED_PIVOT_RATIO: f64 = 1.0e-12;
const MAXIMUM_BACKWARD_ERROR: f64 = 1.0e-10;

/// Why a matrix or a solve was refused before any arithmetic.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BandError {
    /// What Java rejects with `IllegalArgumentException` (or `ArithmeticException` for an overflowing size).
    InvalidInput(&'static str),
    /// A buffer could not be reserved.
    OutOfMemory,
}

/// A vector of `length` zeros, reserved fallibly.
fn zeros(length: usize) -> Result<Vec<f64>, BandError> {
    let mut v = Vec::new();
    v.try_reserve_exact(length).map_err(|_| BandError::OutOfMemory)?;
    v.resize(length, 0.0);
    Ok(v)
}

/// A mutable scalar band matrix with fixed lower and upper bandwidths.
// java: V3BandedMatrix
#[derive(Clone, Debug)]
pub struct BandedMatrix {
    size: usize,
    lower_bandwidth: usize,
    upper_bandwidth: usize,
    values: Vec<f64>,
}

impl BandedMatrix {
    /// A zero matrix. Java refuses a size below 1 and an element count over `Integer.MAX_VALUE`.
    // java: V3BandedMatrix.<init>
    pub fn new(size: usize, lower_bandwidth: usize, upper_bandwidth: usize) -> Result<Self, BandError> {
        let length = Self::storage_length(size, lower_bandwidth, upper_bandwidth)?;
        Ok(Self { size, lower_bandwidth, upper_bandwidth, values: zeros(length)? })
    }

    /// The matrix over Java's storage array (`V3BandedMatrix.values`), every stored entry finite as `set` makes it.
    pub fn from_values(
        size: usize,
        lower_bandwidth: usize,
        upper_bandwidth: usize,
        values: Vec<f64>,
    ) -> Result<Self, BandError> {
        let length = Self::storage_length(size, lower_bandwidth, upper_bandwidth)?;
        if values.len() != length {
            return Err(BandError::InvalidInput("V3 band storage length differs from size * (kl + ku + 1)"));
        }
        if values.iter().any(|v| !v.is_finite()) {
            return Err(BandError::InvalidInput("V3 band matrix entries must be finite"));
        }
        Ok(Self { size, lower_bandwidth, upper_bandwidth, values })
    }

    /// `size * (kl + ku + 1)`, refused where Java's `Math.multiplyExact` on `int` would throw.
    pub fn storage_length(size: usize, lower_bandwidth: usize, upper_bandwidth: usize) -> Result<usize, BandError> {
        if size < 1 {
            return Err(BandError::InvalidInput(
                "V3 band matrix dimensions must be positive with nonnegative bandwidths",
            ));
        }
        let width = lower_bandwidth
            .checked_add(upper_bandwidth)
            .and_then(|w| w.checked_add(1))
            .filter(|&w| w <= i32::MAX as usize)
            .ok_or(BandError::InvalidInput("V3 band width exceeds the int range"))?;
        size.checked_mul(width)
            .filter(|&n| n <= i32::MAX as usize)
            .ok_or(BandError::InvalidInput("V3 band storage exceeds the int range"))
    }

    // java: V3BandedMatrix.size
    pub fn size(&self) -> usize {
        self.size
    }

    // java: V3BandedMatrix.lowerBandwidth
    pub fn lower_bandwidth(&self) -> usize {
        self.lower_bandwidth
    }

    // java: V3BandedMatrix.upperBandwidth
    pub fn upper_bandwidth(&self) -> usize {
        self.upper_bandwidth
    }

    /// Java's storage array.
    pub fn values(&self) -> &[f64] {
        &self.values
    }

    /// Sets an in-band entry; a non-finite value or an index outside the band is refused, as in Java.
    // java: V3BandedMatrix.set
    pub fn set(&mut self, row: usize, column: usize, value: f64) -> Result<(), BandError> {
        let index = self.index(row, column)?;
        if !value.is_finite() {
            return Err(BandError::InvalidInput("V3 band matrix entries must be finite"));
        }
        self.values[index] = value;
        Ok(())
    }

    /// The entry; 0.0 outside the band. Panics on an index outside the matrix, where Java throws.
    // java: V3BandedMatrix.get
    pub fn get(&self, row: usize, column: usize) -> f64 {
        assert!(row < self.size && column < self.size, "V3 band matrix index is outside the matrix");
        if !self.in_band(row, column) {
            return 0.0;
        }
        self.values[self.storage_index(row, column)]
    }

    // java: V3BandedMatrix.clear
    pub fn clear(&mut self) {
        self.values.fill(0.0);
    }

    // java: V3BandedMatrix.firstStoredColumn
    fn first_stored_column(&self, row: usize) -> usize {
        row.saturating_sub(self.lower_bandwidth)
    }

    // java: V3BandedMatrix.lastStoredColumn
    fn last_stored_column(&self, row: usize) -> usize {
        (self.size - 1).min(row.saturating_add(self.upper_bandwidth))
    }

    // java: V3BandedMatrix.index
    fn index(&self, row: usize, column: usize) -> Result<usize, BandError> {
        if row >= self.size || column >= self.size {
            return Err(BandError::InvalidInput("V3 band matrix index is outside the matrix"));
        }
        if !self.in_band(row, column) {
            return Err(BandError::InvalidInput("V3 matrix entry is outside the declared scalar bandwidth"));
        }
        Ok(self.storage_index(row, column))
    }

    // java: V3BandedMatrix.storageIndex
    fn storage_index(&self, row: usize, column: usize) -> usize {
        row * (self.lower_bandwidth + self.upper_bandwidth + 1) + self.lower_bandwidth + column - row
    }

    // java: V3BandedMatrix.inBand
    fn in_band(&self, row: usize, column: usize) -> bool {
        column + self.lower_bandwidth >= row && column <= row + self.upper_bandwidth
    }
}

/// How a solve ended. The numbering is the `counts[0]` of `nativeBandedSolve` (`src/main/rust/LAYOUT.md`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    /// `Result.Success`.
    Success = 0,
    /// `Failure(SINGULAR, "V3 band matrix contains no nonzero pivot")`.
    SingularZeroMatrix = 1,
    /// `Failure(ILL_CONDITIONED, "V3 banded LU scaling exceeds the finite numerical range")`.
    IllConditionedScaling = 2,
    /// `Failure(SINGULAR, "V3 banded LU encountered a zero or tiny pivot")`.
    SingularTinyPivot = 3,
    /// `Failure(ILL_CONDITIONED, "V3 banded LU pivot spread exceeds the configured conditioning guard")`.
    IllConditionedPivotSpread = 4,
    /// `Failure(BACKWARD_ERROR_EXCEEDED, "V3 banded LU correction does not satisfy the backward-error guard")`.
    BackwardErrorExceeded = 5,
    /// `IllegalStateException("V3 banded LU produced a non-finite entry")`.
    NonFiniteEntry = 6,
    /// `IllegalStateException("V3 banded LU lost a usable back-substitution pivot")`.
    LostPivot = 7,
}

/// The fields of Java's `Result` (`Success` or `Failure`), plus the P8 counters of one solve.
#[derive(Clone, Debug, PartialEq)]
pub struct SolveResult {
    pub outcome: Outcome,
    /// The solution on success; empty otherwise.
    pub solution: Vec<f64>,
    /// `Success.backwardError`; 0.0 for a failure.
    pub backward_error: f64,
    pub minimum_pivot_magnitude: f64,
    pub maximum_pivot_magnitude: f64,
    pub pivot_swaps: i32,
    pub pivot_growth: f64,
    /// This solve's scaled band held a -0.0 and took the full fill width (`Workspace.fullWidthSolves` += 1).
    pub full_width: bool,
    /// `Workspace.updatedEntries` added by this solve.
    pub updated_entries: i64,
    /// `Workspace.fullWidthEntries` added by this solve.
    pub full_width_entries: i64,
}

impl SolveResult {
    fn failure(outcome: Outcome, minimum: f64, maximum: f64, swaps: i32, growth: f64) -> Self {
        Self {
            outcome,
            solution: Vec::new(),
            backward_error: 0.0,
            minimum_pivot_magnitude: minimum,
            maximum_pivot_magnitude: maximum,
            pivot_swaps: swaps,
            pivot_growth: growth,
            full_width: false,
            updated_entries: 0,
            full_width_entries: 0,
        }
    }
}

/// Scratch storage for one serial solve at a time, kept between solves of the same shape.
// java: V3BandedPivotedSolver.Workspace
#[derive(Debug, Default)]
pub struct Workspace {
    work: Option<BandRows>,
    updated_entries: i64,
    full_width_entries: i64,
    full_width_solves: i32,
}

impl Workspace {
    pub fn new() -> Self {
        Self::default()
    }

    // java: V3BandedPivotedSolver.Workspace.updatedEntries
    pub fn updated_entries(&self) -> i64 {
        self.updated_entries
    }

    // java: V3BandedPivotedSolver.Workspace.fullWidthEntries
    pub fn full_width_entries(&self) -> i64 {
        self.full_width_entries
    }

    // java: V3BandedPivotedSolver.Workspace.fullWidthSolves
    pub fn full_width_solves(&self) -> i32 {
        self.full_width_solves
    }

    // java: V3BandedPivotedSolver.Workspace.factorization
    fn factorization(&mut self, input: &BandedMatrix) -> Result<&mut BandRows, BandError> {
        let reuse = matches!(&self.work, Some(w) if w.size == input.size
            && w.lower_bandwidth == input.lower_bandwidth && w.upper_bandwidth == input.upper_bandwidth);
        if reuse {
            let w = self.work.as_mut().unwrap();
            w.values.fill(0.0);
            w.column_divisors.fill(0.0);
        } else {
            self.work = None;
            self.work = Some(BandRows::new(input.size, input.lower_bandwidth, input.upper_bandwidth)?);
        }
        let w = self.work.as_mut().unwrap();
        w.copy_from(input);
        Ok(w)
    }
}

/// Solves `matrix x = right_hand_side` by the scaled band LU with partial pivoting inside the lower bandwidth.
// java: V3BandedPivotedSolver.solve
pub fn solve(
    matrix: &BandedMatrix,
    right_hand_side: &[f64],
    workspace: &mut Workspace,
) -> Result<SolveResult, BandError> {
    let mut rhs = copied_finite_right_hand_side(matrix, right_hand_side)?;
    let mut original_rhs = Vec::new();
    original_rhs.try_reserve_exact(rhs.len()).map_err(|_| BandError::OutOfMemory)?;
    original_rhs.extend_from_slice(&rhs);
    let work = workspace.factorization(matrix)?;
    let original_matrix_infinity_norm = matrix_infinity_norm(matrix);
    let original_rhs_infinity_norm = infinity_norm(&rhs);
    if original_matrix_infinity_norm == 0.0 {
        return Ok(SolveResult::failure(Outcome::SingularZeroMatrix, 0.0, 0.0, 0, 0.0));
    }
    if !original_matrix_infinity_norm.is_finite() || !scale_rows_and_columns(work, &mut rhs) {
        return Ok(SolveResult::failure(Outcome::IllConditionedScaling, 0.0, 0.0, 0, 0.0));
    }
    let initial_maximum = work.maximum_absolute_value();
    let mut maximum_during_factorization = initial_maximum;
    let mut minimum_pivot = f64::INFINITY;
    let mut maximum_pivot = 0.0;
    let mut pivot_swaps: i32 = 0;

    let size = work.size;
    let width = work.width;
    let lower_bandwidth = work.lower_bandwidth;
    let upper_bandwidth = work.upper_bandwidth;
    let fill_bandwidth = work.fill_bandwidth;
    // P8: the update stops at the last column any pivot row so far can hold a nonzero in (dgbtf2's ju). Exact,
    // because a -0.0 enters only by row scaling, a - b is -0.0 only for a = -0.0, past ju the pivot row holds the
    // +0.0 of unwritten slots (x - m * 0.0 is x), and skipped entries keep values the initial maximum already holds.
    // A band that holds a -0.0 after scaling takes the full width.
    let mut extent: isize = -1;
    let full_width = work.holds_negative_zero();
    let mut updated_entries: i64 = 0;
    let mut full_width_entries: i64 = 0;
    let band = &mut work.values;
    let mut failure: Option<SolveResult> = None;
    for pivot in 0..size {
        let last_candidate_row = (size - 1).min(pivot + lower_bandwidth);
        let pivot_base = pivot * width + lower_bandwidth - pivot;
        let mut pivot_row = pivot;
        let mut pivot_magnitude = abs(band[pivot_base + pivot]);
        for row in pivot + 1..=last_candidate_row {
            let candidate_magnitude = abs(band[row * width + lower_bandwidth - row + pivot]);
            if candidate_magnitude > pivot_magnitude {
                pivot_magnitude = candidate_magnitude;
                pivot_row = row;
            }
        }
        if !pivot_magnitude.is_finite() || pivot_magnitude <= PIVOT_TOLERANCE {
            failure = Some(SolveResult::failure(
                Outcome::SingularTinyPivot,
                finite_or_zero(minimum_pivot),
                maximum_pivot,
                pivot_swaps,
                pivot_growth(maximum_during_factorization, initial_maximum),
            ));
            break;
        }
        // Rows reaching this pivot hold nothing beyond this column, so it also bounds the fill they take on.
        let last_filled_column = (size - 1).min(pivot + fill_bandwidth);
        extent = extent.max((size - 1).min(pivot_row + upper_bandwidth) as isize);
        let last_updated_column = if full_width { last_filled_column } else { extent as usize };
        if pivot_row != pivot {
            swap_row_tails(band, width, lower_bandwidth, pivot, pivot_row, pivot, last_updated_column);
            rhs.swap(pivot, pivot_row);
            pivot_swaps += 1;
        }
        let diagonal = band[pivot_base + pivot];
        let absolute_diagonal = abs(diagonal);
        minimum_pivot = min(minimum_pivot, absolute_diagonal);
        maximum_pivot = max(maximum_pivot, absolute_diagonal);
        for row in pivot + 1..=last_candidate_row {
            let row_base = row * width + lower_bandwidth - row;
            let multiplier = band[row_base + pivot] / diagonal;
            if multiplier == 0.0 {
                continue;
            }
            band[row_base + pivot] = multiplier;
            updated_entries += (last_updated_column - pivot) as i64;
            full_width_entries += (last_filled_column - pivot) as i64;
            // Including the L multiplier preserves the former whole-matrix growth scan.
            maximum_during_factorization = max(maximum_during_factorization, abs(multiplier));
            // P9: the update alone, a - m * b with two roundings (never fused), then the growth scan.
            for column in pivot + 1..=last_updated_column {
                band[row_base + column] -= multiplier * band[pivot_base + column];
            }
            for column in pivot + 1..=last_updated_column {
                maximum_during_factorization = max(maximum_during_factorization, abs(band[row_base + column]));
            }
            rhs[row] -= multiplier * rhs[pivot];
        }
        if !maximum_during_factorization.is_finite() {
            failure = Some(SolveResult::failure(Outcome::NonFiniteEntry, 0.0, 0.0, 0, 0.0));
            break;
        }
    }
    workspace.updated_entries += updated_entries;
    workspace.full_width_entries += full_width_entries;
    if full_width {
        workspace.full_width_solves += 1;
    }
    let with_counters = |mut r: SolveResult| {
        r.full_width = full_width;
        r.updated_entries = updated_entries;
        r.full_width_entries = full_width_entries;
        r
    };
    if let Some(f) = failure {
        return Ok(with_counters(f));
    }
    let growth = pivot_growth(maximum_during_factorization, initial_maximum);
    if minimum_pivot / maximum_pivot < ILL_CONDITIONED_PIVOT_RATIO {
        return Ok(with_counters(SolveResult::failure(
            Outcome::IllConditionedPivotSpread,
            minimum_pivot,
            maximum_pivot,
            pivot_swaps,
            growth,
        )));
    }

    let work = workspace.work.as_ref().unwrap();
    let scaled_solution = match back_substitute(work, &rhs)? {
        Some(s) => s,
        None => return Ok(with_counters(SolveResult::failure(Outcome::LostPivot, 0.0, 0.0, 0, 0.0))),
    };
    let solution = work.unscale_columns(scaled_solution);
    let backward_error =
        backward_error(matrix, &solution, &original_rhs, original_matrix_infinity_norm, original_rhs_infinity_norm);
    if !backward_error.is_finite() || backward_error > MAXIMUM_BACKWARD_ERROR {
        return Ok(with_counters(SolveResult::failure(
            Outcome::BackwardErrorExceeded,
            minimum_pivot,
            maximum_pivot,
            pivot_swaps,
            growth,
        )));
    }
    Ok(with_counters(SolveResult {
        outcome: Outcome::Success,
        solution,
        backward_error,
        minimum_pivot_magnitude: minimum_pivot,
        maximum_pivot_magnitude: maximum_pivot,
        pivot_swaps,
        pivot_growth: growth,
        full_width: false,
        updated_entries: 0,
        full_width_entries: 0,
    }))
}

// java: V3BandedPivotedSolver.copiedFiniteRightHandSide
fn copied_finite_right_hand_side(matrix: &BandedMatrix, right_hand_side: &[f64]) -> Result<Vec<f64>, BandError> {
    if right_hand_side.len() != matrix.size {
        return Err(BandError::InvalidInput("V3 RHS dimension differs from the matrix"));
    }
    if right_hand_side.iter().any(|v| !v.is_finite()) {
        return Err(BandError::InvalidInput("V3 RHS must be finite"));
    }
    let mut copy = Vec::new();
    copy.try_reserve_exact(right_hand_side.len()).map_err(|_| BandError::OutOfMemory)?;
    copy.extend_from_slice(right_hand_side);
    Ok(copy)
}

// java: V3BandedPivotedSolver.scaleRowsAndColumns
fn scale_rows_and_columns(work: &mut BandRows, rhs: &mut [f64]) -> bool {
    for row in 0..work.size {
        let maximum = work.row_maximum(row);
        if maximum == 0.0 {
            continue;
        }
        let scale = 1.0 / maximum;
        work.divide_row(row, maximum);
        rhs[row] = if scale.is_finite() { rhs[row] * scale } else { rhs[row] / maximum };
        if !rhs[row].is_finite() {
            return false;
        }
    }
    work.scale_columns();
    true
}

/// The scaled solution, or `None` where Java throws "lost a usable back-substitution pivot".
// java: V3BandedPivotedSolver.backSubstitute
fn back_substitute(work: &BandRows, rhs: &[f64]) -> Result<Option<Vec<f64>>, BandError> {
    let mut solution = zeros(work.size)?;
    let band = &work.values;
    for row in (0..work.size).rev() {
        let mut sum = rhs[row];
        let row_base = row * work.width + work.lower_bandwidth - row;
        let last_filled_column = (work.size - 1).min(row + work.fill_bandwidth);
        for column in row + 1..=last_filled_column {
            sum -= band[row_base + column] * solution[column];
        }
        let diagonal = band[row_base + row];
        if !diagonal.is_finite() || abs(diagonal) <= PIVOT_TOLERANCE {
            return Ok(None);
        }
        solution[row] = sum / diagonal;
    }
    Ok(Some(solution))
}

// java: V3BandedPivotedSolver.backwardError
fn backward_error(
    matrix: &BandedMatrix,
    solution: &[f64],
    original_rhs: &[f64],
    matrix_infinity_norm: f64,
    rhs_infinity_norm: f64,
) -> f64 {
    let mut residual_infinity_norm = 0.0;
    for row in 0..matrix.size {
        let mut value = 0.0;
        for column in matrix.first_stored_column(row)..=matrix.last_stored_column(row) {
            value += matrix.get(row, column) * solution[column];
        }
        residual_infinity_norm = max(residual_infinity_norm, abs(value - original_rhs[row]));
    }
    let denominator = matrix_infinity_norm * infinity_norm(solution) + rhs_infinity_norm;
    if denominator == 0.0 { 0.0 } else { residual_infinity_norm / denominator }
}

// java: V3BandedPivotedSolver.infinityNorm(V3BandedMatrix)
fn matrix_infinity_norm(matrix: &BandedMatrix) -> f64 {
    let mut maximum = 0.0;
    for row in 0..matrix.size {
        let mut sum = 0.0;
        for column in matrix.first_stored_column(row)..=matrix.last_stored_column(row) {
            sum += abs(matrix.get(row, column));
        }
        maximum = max(maximum, sum);
    }
    maximum
}

// java: V3BandedPivotedSolver.infinityNorm(double[])
fn infinity_norm(values: &[f64]) -> f64 {
    let mut maximum = 0.0;
    for &value in values {
        maximum = max(maximum, abs(value));
    }
    maximum
}

// java: V3BandedPivotedSolver.pivotGrowth
fn pivot_growth(maximum_during_factorization: f64, initial_maximum: f64) -> f64 {
    if initial_maximum == 0.0 { 0.0 } else { maximum_during_factorization / initial_maximum }
}

// java: V3BandedPivotedSolver.finiteOrZero
fn finite_or_zero(value: f64) -> f64 {
    if value.is_finite() { value } else { 0.0 }
}

// java: V3BandedPivotedSolver.BandRows.swapRowTails
fn swap_row_tails(
    values: &mut [f64],
    width: usize,
    lower_bandwidth: usize,
    first: usize,
    second: usize,
    first_column: usize,
    last_column: usize,
) {
    let first_base = first * width + lower_bandwidth - first;
    let second_base = second * width + lower_bandwidth - second;
    for column in first_column..=last_column {
        values.swap(first_base + column, second_base + column);
    }
}

/// The working band in the LAPACK `dgbtrf` row layout: row r keeps columns r - kl to r + kl + ku at
/// `values[r * width + column - r + kl]`, width `2 kl + ku + 1`.
// java: V3BandedPivotedSolver.BandRows
#[derive(Debug)]
struct BandRows {
    size: usize,
    lower_bandwidth: usize,
    upper_bandwidth: usize,
    fill_bandwidth: usize,
    width: usize,
    values: Vec<f64>,
    column_divisors: Vec<f64>,
    column_scales: Vec<f64>,
}

impl BandRows {
    fn new(size: usize, lower_bandwidth: usize, upper_bandwidth: usize) -> Result<Self, BandError> {
        let width = lower_bandwidth
            .checked_mul(2)
            .and_then(|w| w.checked_add(upper_bandwidth))
            .and_then(|w| w.checked_add(1))
            .ok_or(BandError::InvalidInput("V3 factorization width exceeds the int range"))?;
        let length = size
            .checked_mul(width)
            .filter(|&n| n <= i32::MAX as usize)
            .ok_or(BandError::InvalidInput("V3 factorization storage exceeds the int range"))?;
        Ok(Self {
            size,
            lower_bandwidth,
            upper_bandwidth,
            fill_bandwidth: lower_bandwidth + upper_bandwidth,
            width,
            values: zeros(length)?,
            column_divisors: zeros(size)?,
            column_scales: zeros(size)?,
        })
    }

    // java: V3BandedPivotedSolver.BandRows.copyFrom
    fn copy_from(&mut self, matrix: &BandedMatrix) {
        for row in 0..matrix.size {
            let row_base = self.row_base(row);
            for column in matrix.first_stored_column(row)..=matrix.last_stored_column(row) {
                // A negative zero arrives as the positive zero the envelope reported for the entry it dropped.
                let value = matrix.get(row, column);
                self.values[row_base + column] = if value == 0.0 { 0.0 } else { value };
            }
        }
    }

    fn row_base(&self, row: usize) -> usize {
        row * self.width + self.lower_bandwidth - row
    }

    fn first_column(&self, row: usize) -> usize {
        row.saturating_sub(self.lower_bandwidth)
    }

    fn last_column(&self, row: usize) -> usize {
        (self.size - 1).min(row + self.upper_bandwidth)
    }

    // java: V3BandedPivotedSolver.BandRows.rowMaximum
    fn row_maximum(&self, row: usize) -> f64 {
        let mut maximum = 0.0;
        let row_base = self.row_base(row);
        for column in self.first_column(row)..=self.last_column(row) {
            maximum = max(maximum, abs(self.values[row_base + column]));
        }
        maximum
    }

    // java: V3BandedPivotedSolver.BandRows.divideRow
    fn divide_row(&mut self, row: usize, divisor: f64) {
        let scale = 1.0 / divisor;
        let row_base = self.row_base(row);
        for column in self.first_column(row)..=self.last_column(row) {
            // The quotient is bounded even when a subnormal divisor's reciprocal overflows.
            let v = self.values[row_base + column];
            self.values[row_base + column] = if scale.is_finite() { v * scale } else { v / divisor };
        }
    }

    // java: V3BandedPivotedSolver.BandRows.scaleColumns
    fn scale_columns(&mut self) {
        for row in 0..self.size {
            let row_base = self.row_base(row);
            for column in self.first_column(row)..=self.last_column(row) {
                self.column_divisors[column] = max(self.column_divisors[column], abs(self.values[row_base + column]));
            }
        }
        for column in 0..self.size {
            if self.column_divisors[column] == 0.0 {
                self.column_divisors[column] = 1.0;
            }
            self.column_scales[column] = 1.0 / self.column_divisors[column];
        }
        for row in 0..self.size {
            let row_base = self.row_base(row);
            for column in self.first_column(row)..=self.last_column(row) {
                let scale = self.column_scales[column];
                let v = self.values[row_base + column];
                self.values[row_base + column] =
                    if scale.is_finite() { v * scale } else { v / self.column_divisors[column] };
            }
        }
    }

    /// Whether a stored entry is -0.0 (only row scaling can make one, from a negative entry it scales to zero).
    // java: V3BandedPivotedSolver.BandRows.holdsNegativeZero
    fn holds_negative_zero(&self) -> bool {
        for row in 0..self.size {
            let row_base = self.row_base(row);
            for column in self.first_column(row)..=self.last_column(row) {
                if self.values[row_base + column].to_bits() == 0x8000_0000_0000_0000 {
                    return true;
                }
            }
        }
        false
    }

    // java: V3BandedPivotedSolver.BandRows.maximumAbsoluteValue
    fn maximum_absolute_value(&self) -> f64 {
        let mut maximum = 0.0;
        for row in 0..self.size {
            maximum = max(maximum, self.row_maximum(row));
        }
        maximum
    }

    // java: V3BandedPivotedSolver.BandRows.unscaleColumns
    fn unscale_columns(&self, scaled_solution: Vec<f64>) -> Vec<f64> {
        let mut solution = scaled_solution;
        for (column, value) in solution.iter_mut().enumerate() {
            let scale = 1.0 / self.column_divisors[column];
            *value = if scale.is_finite() { *value * scale } else { *value / self.column_divisors[column] };
        }
        solution
    }
}
