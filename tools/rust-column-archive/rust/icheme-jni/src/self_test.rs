//! `nativeSelfTest`'s known answers: 256 doubles that Java recomputes with its own code and compares bit for bit, so
//! a library that does not give Java's bits on this processor and operating system is refused (plan section 8,
//! "Loader and handshake" item 4). The Java twin is `RustNativeTables.selfTestValues()` (test tree), which part 2b
//! moves into `NativeColumnSolver`; `tests/data/self_test.txt` holds its output.
//!
//! | Slots | Values |
//! |---|---|
//! | 0..7, 8..15, 16..23, 24..31, 32..39, 40..47 | `log`, `exp`, `expm1`, `cbrt`, `cos`, `acos` at eight fixed arguments each |
//! | 48..63 | `Math.fma` at sixteen triples (ties, sticky bits, cancellation, subnormal and overflowing products) |
//! | 64..79, 80..95 | `DeterministicMath.pow`, `strictPow` at sixteen pairs |
//! | 96..103 | the solution of one band solve (size 8, kl 2, ku 1) |
//! | 104..107 | its backward error, minimum pivot, maximum pivot, pivot growth |
//! | 108, 109 | its outcome (0) and pivot swaps, as doubles; 110..127 are 0.0 |
//! | 128..191 | reserved: one kernel evaluation on a fixed three-component mixture (stage (b)); 0.0 until then |
//! | 192..255 | reserved: one derivative evaluation (stage (b)); 0.0 until then |

use icheme_math::banded::{self, BandedMatrix, Workspace};
use icheme_math::{acos, cbrt, cos, exp, expm1, fma, log, pow, strict_pow};

use crate::layout::SELF_TEST_SLOTS;

/// 2^e for a normal exponent.
const fn p2(e: i32) -> f64 {
    f64::from_bits(((e + 1023) as u64) << 52)
}

/// The 256 known answers; panics only if the fixed band solve stops succeeding (a defect).
// java: RustNativeTables.selfTestValues
#[allow(clippy::needless_range_loop, reason = "the Java loops, kept as written")]
pub fn self_test_values() -> [f64; SELF_TEST_SLOTS] {
    let mut out = [0.0f64; SELF_TEST_SLOTS];
    let subnormal_2_to_minus_1060 = f64::from_bits(1 << 14);
    let log_arguments = [0.5, 1.0 + p2(-30), 2.0, 1e-300, 1e300, subnormal_2_to_minus_1060, 7.0, 1234.5678];
    let exp_arguments = [-745.1, -700.5, -1.0, -1e-10, 0.3, 1.0, 50.5, 709.7];
    let expm1_arguments = [-40.0, -1.0, -0.3, -1e-12, 1e-8, 0.4, 13.9, 300.0];
    let cbrt_arguments = [-27.5, -1e-310, 1e-300, 0.001, 2.0, 1e10, 3.3e200, f64::MAX];
    let cos_arguments = [0.0, 0.5, 0.785, 2.0, -3.5, 1e6, 1e22, 6381956970095103.0 * p2(797)];
    let acos_arguments = [-1.0, -0.9, -0.5, -1e-20, 0.3, 0.5, 0.99, 1.0];
    for i in 0..8 {
        out[i] = log(log_arguments[i]);
        out[8 + i] = exp(exp_arguments[i]);
        out[16 + i] = expm1(expm1_arguments[i]);
        out[24 + i] = cbrt(cbrt_arguments[i]);
        out[32 + i] = cos(cos_arguments[i]);
        out[40 + i] = acos(acos_arguments[i]);
    }
    let next_up_one = f64::from_bits(0x3FF0_0000_0000_0001);
    let fma_arguments: [[f64; 3]; 16] = [
        [p2(-27), p2(-27) * (1.0 + p2(-52)), 1.0],
        [p2(-27), p2(-27), 1.0],
        [3.0, 0.1, -0.3],
        [1.0 + p2(-52), 1.0 + p2(-52), -(1.0 + p2(-51))],
        [p2(-540), p2(-540), f64::from_bits(1 << 4)], // 2^-1070
        [-2.5, 4.0, 10.0],
        [f64::MAX, 2.0, -f64::MAX],
        [0.1, 0.1, -0.01],
        [1e308, 10.0, -1e308],
        [-0.0, 0.0, 0.0],
        [1.5, 2.0 / 3.0, -1.0],
        [7.0, 1.0 / 7.0, -1.0],
        [1e-200, 1e-200, 1e-300],
        [123.456, 789.012, 0.5],
        [-1e10, 1e-10, 1.0],
        [2.0, next_up_one, -2.0],
    ];
    for (i, [a, b, c]) in fma_arguments.iter().enumerate() {
        out[48 + i] = fma::fma(*a, *b, *c);
    }
    let math_e = f64::from_bits(0x4005_BF0A_8B14_5769); // Math.E
    let pow_arguments: [[f64; 2]; 16] = [
        [2.0, 0.5],
        [10.0, -3.0],
        [0.9, 1000.0],
        [1.0 + p2(-40), 1e12],
        [3.7, 2.5],
        [-2.0, 3.0],
        [-2.0, 0.5],
        [0.0, -1.0],
        [1e-300, 0.25],
        [7.25, -9.75],
        [1.5, 1700.0],
        [0.5, 1074.5],
        [2.0, 1023.5],
        [123.0, 1.0 / 3.0],
        [math_e, 708.5],
        [1.0000001, 7.0e9],
    ];
    for (i, [x, y]) in pow_arguments.iter().enumerate() {
        out[64 + i] = pow(*x, *y);
        out[80 + i] = strict_pow(*x, *y);
    }
    // One band solve: size 8, kl 2, ku 1, a(r, r) = 4 + r, a(r, c) = 1 / (1 + r + 2 c) off the diagonal, b(r) = 1 + r / 8.
    let (size, kl, ku) = (8usize, 2usize, 1usize);
    let mut matrix = BandedMatrix::new(size, kl, ku).expect("a fixed shape");
    let mut rhs = [0.0f64; 8];
    for row in 0..size {
        for column in row.saturating_sub(kl)..=(size - 1).min(row + ku) {
            let value = if column == row { 4.0 + row as f64 } else { 1.0 / (1 + row + 2 * column) as f64 };
            matrix.set(row, column, value).expect("an in-band finite entry");
        }
        rhs[row] = 1.0 + row as f64 / 8.0;
    }
    let result = banded::solve(&matrix, &rhs, &mut Workspace::new()).expect("a valid fixed system");
    assert_eq!(result.outcome, banded::Outcome::Success, "the self-test band system solves");
    out[96..104].copy_from_slice(&result.solution);
    out[104] = result.backward_error;
    out[105] = result.minimum_pivot_magnitude;
    out[106] = result.maximum_pivot_magnitude;
    out[107] = result.pivot_growth;
    out[108] = 0.0;
    out[109] = result.pivot_swaps as f64;
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn self_test_is_the_java_known_answers() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/data/self_test.txt");
        let text = std::fs::read_to_string(&path).expect("tests/data/self_test.txt is readable");
        let values = self_test_values();
        let mut slots = 0;
        for line in text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')) {
            let (slot, bits) = line.split_once(' ').expect("slot value");
            let slot: usize = slot.parse().unwrap();
            let expected = u64::from_str_radix(bits, 16).unwrap();
            assert_eq!(
                values[slot].to_bits(),
                expected,
                "slot {slot}: java {expected:016x}, rust {:016x}",
                values[slot].to_bits()
            );
            slots += 1;
        }
        assert_eq!(slots, SELF_TEST_SLOTS);
    }
}
