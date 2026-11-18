//! The band LU (`icheme_math::banded`) against `tests/data/banded.txt`, written by the Java generator
//! `src/test/java/com/wormzjl/icheme/science/column/v3/linalg/RustNativeTables.java` from `V3BandedPivotedSolver`.
//!
//! A row names a case (kind, seed, three parameters); this file rebuilds the same matrix and right-hand side with the
//! twin of the Java case generator (SplitMix64 and exact arithmetic), checks the shape and the input fingerprint (so a
//! generator difference is reported as one), then solves and compares every output: the outcome, the pivot swaps,
//! the P8 counters, the four evidence doubles and the solution, all bit for bit.

#![allow(clippy::needless_range_loop, reason = "the twin of the Java generator's index loops")]

use std::fs;
use std::path::Path;

use icheme_math::banded::{self, BandError, BandedMatrix, Outcome, Workspace};

const RANDOM: i32 = 0;
const DOMINANT: i32 = 1;
const BLOCK: i32 = 2;
const ZERO: i32 = 3;
const ZERO_COLUMN: i32 = 4;
const DUPLICATE_ROW: i32 = 5;
const NEAR_SINGULAR: i32 = 6;
const ILL_SCALING: i32 = 7;
const NEGATIVE_ZERO: i32 = 8;
const WILKINSON: i32 = 9;
const NON_FINITE: i32 = 10;
const RHS_NON_FINITE: i32 = 11;

/// `RustNativeTables.Rng`.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    fn unit(&mut self) -> f64 {
        (self.next() >> 11) as f64 * f64::from_bits(0x3CA0_0000_0000_0000) // 2^-53
    }

    fn int_in(&mut self, lo: i32, hi: i32) -> i32 {
        let span = (hi as i64 - lo as i64 + 1) as u64;
        lo + (self.next() % span) as i32
    }

    fn coin(&mut self) -> bool {
        self.next() & 1 != 0
    }

    fn entry(&mut self, e_lo: i32, e_hi: i32) -> f64 {
        let e = self.int_in(e_lo, e_hi);
        let v = (1.0 + self.unit()) * f64::from_bits(((e + 1023) as u64) << 52);
        if self.coin() { -v } else { v }
    }
}

/// `RustNativeTables.Case`.
struct Case {
    size: i32,
    kl: i32,
    ku: i32,
    band: Vec<f64>,
    rhs: Vec<f64>,
}

impl Case {
    fn shaped(size: i32, kl: i32, ku: i32) -> Self {
        Self { size, kl, ku, band: vec![0.0; (size * (kl + ku + 1)) as usize], rhs: vec![0.0; size as usize] }
    }

    fn index(&self, row: i32, column: i32) -> usize {
        (row * (self.kl + self.ku + 1) + column - row + self.kl) as usize
    }

    fn in_band(&self, row: i32, column: i32) -> bool {
        column >= row - self.kl && column <= row + self.ku && column >= 0 && column < self.size
    }

    fn first(&self, row: i32) -> i32 {
        0.max(row - self.kl)
    }

    fn last(&self, row: i32) -> i32 {
        (self.size - 1).min(row + self.ku)
    }

    fn fingerprint(&self) -> u64 {
        let mut sum = 0u64;
        for v in self.band.iter().chain(self.rhs.iter()) {
            sum = sum.wrapping_add(v.to_bits());
        }
        sum
    }
}

fn random_band(r: &mut Rng, c: &mut Case, dominant: bool) {
    for row in 0..c.size {
        for column in c.first(row)..=c.last(row) {
            let v = if dominant && column == row {
                r.entry(4, 8)
            } else if r.next() & 3 == 0 {
                0.0
            } else if dominant {
                r.entry(-8, 0)
            } else {
                r.entry(-8, 8)
            };
            let i = c.index(row, column);
            c.band[i] = v;
        }
    }
}

fn random_rhs(r: &mut Rng, c: &mut Case, e_lo: i32, e_hi: i32) {
    for row in 0..c.size as usize {
        c.rhs[row] = if r.next() & 7 == 0 { 0.0 } else { r.entry(e_lo, e_hi) };
    }
}

/// `RustNativeTables.build`.
fn build(kind: i32, seed: u64, p1: i32, p2: i32, p3: i32) -> Case {
    let mut r = Rng(seed);
    let bad = [f64::NAN, f64::INFINITY, f64::NEG_INFINITY];
    match kind {
        RANDOM | DOMINANT | ZERO | ZERO_COLUMN | DUPLICATE_ROW | ILL_SCALING | NEGATIVE_ZERO | NON_FINITE
        | RHS_NON_FINITE => {
            let mut c = Case::shaped(p1, p2, p3);
            if kind != ZERO {
                random_band(&mut r, &mut c, kind != RANDOM);
            }
            random_rhs(&mut r, &mut c, -8, 8);
            match kind {
                ZERO_COLUMN => {
                    let column = r.int_in(0, c.size - 1);
                    for row in 0..c.size {
                        if c.in_band(row, column) {
                            let i = c.index(row, column);
                            c.band[i] = 0.0;
                        }
                    }
                }
                DUPLICATE_ROW => {
                    let row = r.int_in(0, c.size - 2);
                    for column in 0..c.size {
                        if !c.in_band(row + 1, column) {
                            continue;
                        }
                        let v = if c.in_band(row, column) { c.band[c.index(row, column)] } else { 0.0 };
                        let i = c.index(row + 1, column);
                        c.band[i] = v;
                    }
                }
                ILL_SCALING => {
                    let row = r.int_in(0, c.size - 1);
                    let subnormal = r.coin();
                    for column in 0..c.size {
                        if !c.in_band(row, column) {
                            continue;
                        }
                        let v =
                            if subnormal { f64::from_bits(1) * r.int_in(1, 1000) as f64 } else { r.entry(1020, 1023) };
                        let i = c.index(row, column);
                        c.band[i] = v;
                    }
                    if subnormal {
                        c.rhs[row as usize] = r.entry(900, 1000);
                    }
                }
                NEGATIVE_ZERO => {
                    for _ in 0..3 {
                        let row = r.int_in(0, c.size - 1);
                        let column = c.first(row) + r.int_in(0, c.last(row) - c.first(row));
                        let i = c.index(row, column);
                        c.band[i] = -0.0;
                    }
                    for _ in 0..2 {
                        let row = r.int_in(0, c.size - 1);
                        let column = c.first(row) + r.int_in(0, c.last(row) - c.first(row));
                        if column != row {
                            let i = c.index(row, column);
                            c.band[i] = -f64::from_bits(1) * r.int_in(1, 4) as f64;
                        }
                    }
                }
                NON_FINITE => {
                    let row = r.int_in(0, c.size - 1);
                    let i = c.index(row, row);
                    c.band[i] = bad[r.int_in(0, 2) as usize];
                }
                RHS_NON_FINITE => {
                    let row = r.int_in(0, c.size - 1) as usize;
                    c.rhs[row] = bad[r.int_in(0, 2) as usize];
                }
                _ => {}
            }
            c
        }
        NEAR_SINGULAR => {
            let mut c = Case::shaped(p1, p2, p3);
            for row in 0..c.size {
                let i = c.index(row, row);
                c.band[i] = r.entry(-2, 2);
            }
            let row = r.int_in(0, c.size - 2);
            let delta = r.int_in(1, 40) as f64 * f64::from_bits(0x3D20_0000_0000_0000); // 2^-45
            let (a, b, d, e) =
                (c.index(row, row), c.index(row, row + 1), c.index(row + 1, row), c.index(row + 1, row + 1));
            c.band[a] = 1.0;
            c.band[b] = 1.0;
            c.band[d] = 1.0;
            c.band[e] = 1.0 + delta;
            random_rhs(&mut r, &mut c, -8, 8);
            c
        }
        BLOCK => {
            let (nodes, block) = (p1, p2);
            let mut sizes = vec![0i32; nodes as usize];
            let mut starts = vec![0i32; nodes as usize + 1];
            for n in 0..nodes as usize {
                sizes[n] = block - r.int_in(0, 2.min(block - 1));
                starts[n + 1] = starts[n] + sizes[n];
            }
            let size = starts[nodes as usize];
            let mut entries: Vec<(i32, i32, f64)> = Vec::new();
            let (mut kl, mut ku) = (0i32, 0i32);
            let mut row_exponents = vec![0i32; size as usize];
            for n in 0..nodes {
                let first = starts[0.max(n - 1) as usize];
                let last = starts[nodes.min(n + 2) as usize] - 1;
                for row in starts[n as usize]..starts[n as usize + 1] {
                    let exponent = r.int_in(-20, 20);
                    row_exponents[row as usize] = exponent;
                    for column in first..=last {
                        let v = if column == row {
                            r.entry(exponent, exponent + 2)
                        } else if r.next() & 7 == 0 {
                            0.0
                        } else {
                            r.entry(exponent - 12, exponent)
                        };
                        if v != 0.0 {
                            kl = kl.max(row - column);
                            ku = ku.max(column - row);
                            entries.push((row, column, v));
                        }
                    }
                }
            }
            let mut c = Case::shaped(size, kl, ku);
            for (row, column, v) in entries {
                let i = c.index(row, column);
                c.band[i] = v;
            }
            for row in 0..size as usize {
                let exponent = row_exponents[row];
                c.rhs[row] = if r.next() & 7 == 0 { 0.0 } else { r.entry(exponent - 6, exponent + 6) };
            }
            c
        }
        WILKINSON => {
            let n = p1;
            let perturbed = p2 != 0;
            let mut c = Case::shaped(n, n - 1, n - 1);
            for row in 0..n {
                for column in 0..n {
                    let mut v = if column == row || column == n - 1 {
                        1.0
                    } else if column < row {
                        -1.0
                    } else {
                        0.0
                    };
                    if v != 0.0 && perturbed {
                        v *= 1.0 + r.unit() * f64::from_bits(0x3EB0_0000_0000_0000); // 2^-20
                    }
                    let i = c.index(row, column);
                    c.band[i] = v;
                }
            }
            random_rhs(&mut r, &mut c, -4, 4);
            c
        }
        _ => panic!("kind {kind}"),
    }
}

fn hex(token: &str) -> u64 {
    u64::from_str_radix(token, 16).unwrap_or_else(|_| panic!("not a hexadecimal token: {token}"))
}

fn same(name: &str, at: &str, expected: &str, actual: f64) {
    let e = f64::from_bits(hex(expected));
    let equal = if e.is_nan() { actual.is_nan() } else { actual.to_bits() == e.to_bits() };
    assert!(equal, "{at}: {name}: java {expected} ({e:e}), rust {:016x} ({actual:e})", actual.to_bits());
}

#[test]
fn band_lu_is_v3_banded_pivoted_solver() {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests").join("data").join("banded.txt");
    let text = fs::read_to_string(&path).expect("tests/data/banded.txt is readable");
    let mut rows = 0;
    let mut outcomes = [0usize; 9];
    let mut solution_values = 0usize;
    for (index, line) in text.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let at = format!("banded.txt:{}", index + 1);
        let parts: Vec<&str> = line.split(" | ").collect();
        let case: Vec<&str> = parts[0].split(' ').collect();
        let (kind, seed): (i32, u64) = (case[0].parse().unwrap(), hex(case[1]));
        let p: Vec<i32> = case[2..5].iter().map(|t| t.parse().unwrap()).collect();
        let c = build(kind, seed, p[0], p[1], p[2]);
        let shape: Vec<&str> = parts[1].split(' ').collect();
        assert_eq!(
            (c.size, c.kl, c.ku, c.fingerprint()),
            (shape[0].parse().unwrap(), shape[1].parse().unwrap(), shape[2].parse().unwrap(), hex(shape[3])),
            "{at}: the case generator twin differs from Java's (size, kl, ku, input fingerprint)"
        );
        let (size, kl, ku) = (c.size as usize, c.kl as usize, c.ku as usize);
        let solved = BandedMatrix::from_values(size, kl, ku, c.band.clone())
            .and_then(|matrix| banded::solve(&matrix, &c.rhs, &mut Workspace::new()));
        rows += 1;
        let result = match solved {
            Err(BandError::InvalidInput(_)) => {
                assert_eq!(parts[2], "iae", "{at}: rust refuses the input, java {}", parts[2]);
                outcomes[8] += 1;
                continue;
            }
            Err(e) => panic!("{at}: {e:?}"),
            Ok(result) => result,
        };
        let head: Vec<&str> = parts[2].split(' ').collect();
        assert_ne!(head[0], "iae", "{at}: java refuses the input, rust solves it: {:?}", result.outcome);
        let code: usize = head[0].parse().unwrap();
        assert_eq!(result.outcome as usize, code, "{at}: outcome");
        outcomes[code] += 1;
        if head[1] != "-" {
            assert_eq!(result.pivot_swaps.to_string(), head[1], "{at}: pivot swaps");
        }
        assert_eq!(
            (result.full_width as i32, result.updated_entries, result.full_width_entries),
            (head[2].parse().unwrap(), head[3].parse().unwrap(), head[4].parse().unwrap()),
            "{at}: P8 counters (full width, updated entries, full-width entries)"
        );
        if code == Outcome::NonFiniteEntry as usize || code == Outcome::LostPivot as usize {
            continue; // Java throws: no evidence
        }
        let evidence: Vec<&str> = parts[3].split(' ').collect();
        same("backward error", &at, evidence[0], result.backward_error);
        same("minimum pivot", &at, evidence[1], result.minimum_pivot_magnitude);
        same("maximum pivot", &at, evidence[2], result.maximum_pivot_magnitude);
        same("pivot growth", &at, evidence[3], result.pivot_growth);
        let solution: Vec<&str> =
            parts.get(4).map(|s| s.split(' ').filter(|t| !t.is_empty()).collect()).unwrap_or_default();
        assert_eq!(solution.len(), result.solution.len(), "{at}: solution length");
        for (k, (expected, &actual)) in solution.iter().zip(result.solution.iter()).enumerate() {
            same(&format!("solution[{k}]"), &at, expected, actual);
        }
        solution_values += solution.len();
    }
    println!("banded: {rows} cases bit-equal, {solution_values} solution values; outcomes 0..7, iae: {outcomes:?}");
    for code in 0..7 {
        assert!(outcomes[code] > 0, "outcome {code} is never exercised");
    }
    assert!(outcomes[8] > 0, "no refused input");
}

#[test]
fn workspace_is_reused_with_the_same_results() {
    // Two shapes in turn through one workspace: the reused factorization storage gives the fresh results.
    let first = build(DOMINANT, 7, 12, 3, 2);
    let second = build(BLOCK, 11, 4, 5, 0);
    let mut shared = Workspace::new();
    for c in [&first, &second, &first, &first, &second] {
        let matrix = BandedMatrix::from_values(c.size as usize, c.kl as usize, c.ku as usize, c.band.clone()).unwrap();
        let reused = banded::solve(&matrix, &c.rhs, &mut shared).unwrap();
        let fresh = banded::solve(&matrix, &c.rhs, &mut Workspace::new()).unwrap();
        assert_eq!(reused, fresh);
    }
    assert!(shared.updated_entries() > 0 && shared.updated_entries() <= shared.full_width_entries());
}
