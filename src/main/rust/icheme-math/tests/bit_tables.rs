//! Every function of icheme-math against the bit tables generated from Java
//! (`src/test/java/com/wormzjl/icheme/science/math/RustBitTables.java`, tracked under `tests/data/`). Each row is
//! compared bit for bit; the first difference stops the test with its file, line, arguments and both bit patterns.
//!
//! NaN results: Java specifies only that the result is NaN, and so does Rust (the sign and payload of a NaN produced
//! by arithmetic are not specified, RFC 3514). Java itself returns different NaN bits for the same call (the
//! interpreter and the intrinsics of `Math.max` and `Math.fma`; `FdlibmPow` and `StrictMath.pow` differ in three NaN
//! payloads in the pow table). A NaN in the table therefore requires a NaN, of any bits; every other value requires
//! the same 64 bits. Each test prints how many NaN rows also had the same bits.

use std::fs;
use std::path::{Path, PathBuf};

use icheme_math::java::{self, ClampError};
use icheme_math::{acos, cbrt, cos, exp, expm1, fma, log, pow, strict_pow};

/// At least this many rows per function: the special values, the branch boundaries and 5 000 random arguments.
const MINIMUM_ROWS: usize = 5_000;

struct Row {
    file: String,
    line: usize,
    tokens: Vec<String>,
}

fn data_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests").join("data")
}

/// The rows of table `name` (`name.txt`, or `name-1.txt`, `name-2.txt`, ... in order), comments skipped.
fn rows(name: &str) -> Vec<Row> {
    let mut files: Vec<(usize, PathBuf, String)> = Vec::new();
    let prefix = format!("{name}-");
    for entry in fs::read_dir(data_dir()).expect("tests/data is readable") {
        let path = entry.expect("a directory entry").path();
        let file = path.file_name().unwrap().to_string_lossy().into_owned();
        if file == format!("{name}.txt") {
            files.push((0, path, file));
        } else if let Some(rest) = file.strip_prefix(&prefix)
            && let Some(number) = rest.strip_suffix(".txt")
            && !number.is_empty()
            && number.bytes().all(|b| b.is_ascii_digit())
        {
            files.push((number.parse().unwrap(), path, file));
        }
    }
    assert!(!files.is_empty(), "no table {name} in {}", data_dir().display());
    files.sort();
    let mut out = Vec::new();
    for (_, path, file) in files {
        let text = fs::read_to_string(&path).expect("a table is readable");
        for (index, line) in text.lines().enumerate() {
            let line_text = line.trim();
            if line_text.is_empty() || line_text.starts_with('#') {
                continue;
            }
            out.push(Row {
                file: file.clone(),
                line: index + 1,
                tokens: line_text.split_whitespace().map(str::to_owned).collect(),
            });
        }
    }
    out
}

fn bits(token: &str) -> u64 {
    u64::from_str_radix(token, 16).unwrap_or_else(|_| panic!("not a hexadecimal token: {token}"))
}

fn double(token: &str) -> f64 {
    f64::from_bits(bits(token))
}

fn int(token: &str) -> i32 {
    u32::from_str_radix(token, 16).unwrap_or_else(|_| panic!("not an int token: {token}")) as i32
}

/// Counts of one table.
struct Tally {
    name: &'static str,
    rows: usize,
    values: usize,
    nan: usize,
    nan_same_bits: usize,
}

impl Tally {
    fn new(name: &'static str) -> Self {
        Self { name, rows: 0, values: 0, nan: 0, nan_same_bits: 0 }
    }

    /// Java's double `expected` (raw bits) against Rust's `actual`; stops at the first difference.
    fn double(&mut self, row: &Row, what: &str, args: &str, expected: u64, actual: f64) {
        self.values += 1;
        let e = f64::from_bits(expected);
        let equal = if e.is_nan() {
            self.nan += 1;
            if actual.to_bits() == expected {
                self.nan_same_bits += 1;
            }
            actual.is_nan()
        } else {
            actual.to_bits() == expected
        };
        if !equal {
            panic!(
                "{}: first difference at {}:{}: {what}({args}): java {expected:016x} ({e:e}), rust {:016x} ({actual:e})",
                self.name,
                row.file,
                row.line,
                actual.to_bits()
            );
        }
    }

    /// Java's integer result against Rust's.
    fn integer(&mut self, row: &Row, what: &str, args: &str, expected: i64, actual: i64) {
        self.values += 1;
        if expected != actual {
            panic!(
                "{}: first difference at {}:{}: {what}({args}): java {expected} ({expected:#x}), rust {actual} ({actual:#x})",
                self.name, row.file, row.line
            );
        }
    }

    fn row(&mut self) {
        self.rows += 1;
    }

    fn finish(self, minimum_rows: usize) {
        println!(
            "{}: {} rows, {} values bit-equal; {} NaN results, {} of them with Java's NaN bits",
            self.name, self.rows, self.values, self.nan, self.nan_same_bits
        );
        assert!(self.rows >= minimum_rows, "{}: {} rows, expected at least {minimum_rows}", self.name, self.rows);
    }
}

fn show(x: f64) -> String {
    format!("{:016x} = {x:e}", x.to_bits())
}

fn unary(name: &'static str, f: fn(f64) -> f64) {
    let mut tally = Tally::new(name);
    for row in rows(name) {
        assert_eq!(row.tokens.len(), 2, "{name}: {}:{} has {} columns", row.file, row.line, row.tokens.len());
        let x = double(&row.tokens[0]);
        tally.double(&row, name, &show(x), bits(&row.tokens[1]), f(x));
        tally.row();
    }
    tally.finish(MINIMUM_ROWS);
}

#[test]
fn log_is_strict_math_log() {
    unary("log", log);
}

#[test]
fn exp_is_strict_math_exp() {
    unary("exp", exp);
}

#[test]
fn expm1_is_strict_math_expm1() {
    unary("expm1", expm1);
}

#[test]
fn cbrt_is_strict_math_cbrt() {
    unary("cbrt", cbrt);
}

#[test]
fn cos_is_strict_math_cos() {
    unary("cos", cos);
}

#[test]
fn acos_is_strict_math_acos() {
    unary("acos", acos);
}

#[test]
fn pow_is_deterministic_pow_and_strict_pow_is_fdlibm_pow() {
    let mut tally = Tally::new("pow");
    for row in rows("pow") {
        assert_eq!(row.tokens.len(), 4);
        let x = double(&row.tokens[0]);
        let y = double(&row.tokens[1]);
        let args = format!("{}, {}", show(x), show(y));
        tally.double(&row, "pow", &args, bits(&row.tokens[2]), pow(x, y));
        tally.double(&row, "strict_pow", &args, bits(&row.tokens[3]), strict_pow(x, y));
        tally.row();
    }
    tally.finish(MINIMUM_ROWS);
}

#[test]
fn pow_tables_are_the_java_tables() {
    let tables = icheme_math::pow::tables();
    let mut tally = Tally::new("pow_tables");
    let mut counts = [0usize; 6];
    for row in rows("pow_tables") {
        assert_eq!(row.tokens.len(), 3);
        let t: usize = row.tokens[0].parse().unwrap();
        let i: usize = row.tokens[1].parse().unwrap();
        let value = tables[t].get(i).copied().unwrap_or_else(|| panic!("table {t} has no entry {i}"));
        tally.double(&row, "table", &format!("{t}, {i}"), bits(&row.tokens[2]), value);
        counts[t] += 1;
        tally.row();
    }
    for (t, table) in tables.iter().enumerate() {
        assert_eq!(counts[t], table.len(), "table {t}: Java has {} entries, Rust {}", counts[t], table.len());
    }
    tally.finish(5 * 128 + 5);
}

#[test]
fn fma_is_math_fma() {
    let mut tally = Tally::new("fma");
    for row in rows("fma") {
        assert_eq!(row.tokens.len(), 4);
        let a = double(&row.tokens[0]);
        let b = double(&row.tokens[1]);
        let c = double(&row.tokens[2]);
        let args = format!("{}, {}, {}", show(a), show(b), show(c));
        tally.double(&row, "fma", &args, bits(&row.tokens[3]), fma::fma(a, b, c));
        tally.row();
    }
    tally.finish(MINIMUM_ROWS);
}

#[test]
fn max_min_and_compare_are_java() {
    let mut tally = Tally::new("max_min");
    for row in rows("max_min") {
        assert_eq!(row.tokens.len(), 5);
        let a = double(&row.tokens[0]);
        let b = double(&row.tokens[1]);
        let args = format!("{}, {}", show(a), show(b));
        tally.double(&row, "max", &args, bits(&row.tokens[2]), java::max(a, b));
        tally.double(&row, "min", &args, bits(&row.tokens[3]), java::min(a, b));
        tally.integer(&row, "compare", &args, int(&row.tokens[4]) as i64, java::double_compare(a, b) as i64);
        tally.row();
    }
    tally.finish(MINIMUM_ROWS);
}

#[test]
fn abs_floor_ulp_rint_round_and_casts_are_java() {
    let mut tally = Tally::new("java_unary");
    for row in rows("java_unary") {
        assert_eq!(row.tokens.len(), 8);
        let x = double(&row.tokens[0]);
        let args = show(x);
        tally.double(&row, "abs", &args, bits(&row.tokens[1]), java::abs(x));
        tally.double(&row, "floor", &args, bits(&row.tokens[2]), java::floor(x));
        tally.double(&row, "ulp", &args, bits(&row.tokens[3]), java::ulp(x));
        tally.double(&row, "rint", &args, bits(&row.tokens[4]), java::rint(x));
        tally.integer(&row, "round", &args, bits(&row.tokens[5]) as i64, java::round(x));
        tally.integer(&row, "to_int", &args, int(&row.tokens[6]) as i64, java::to_int(x) as i64);
        tally.integer(&row, "to_long", &args, bits(&row.tokens[7]) as i64, java::to_long(x));
        tally.row();
    }
    tally.finish(MINIMUM_ROWS);
}

#[test]
fn scalb_is_math_scalb() {
    let mut tally = Tally::new("scalb");
    for row in rows("scalb") {
        assert_eq!(row.tokens.len(), 3);
        let x = double(&row.tokens[0]);
        let n = int(&row.tokens[1]);
        tally.double(&row, "scalb", &format!("{}, {n}", show(x)), bits(&row.tokens[2]), java::scalb(x, n));
        tally.row();
    }
    tally.finish(MINIMUM_ROWS);
}

#[test]
fn clamp_is_math_clamp() {
    let mut tally = Tally::new("clamp");
    for row in rows("clamp") {
        assert_eq!(row.tokens.len(), 4);
        let v = double(&row.tokens[0]);
        let lo = double(&row.tokens[1]);
        let hi = double(&row.tokens[2]);
        let args = format!("{}, {}, {}", show(v), show(lo), show(hi));
        match (row.tokens[3].as_str(), java::clamp(v, lo, hi)) {
            ("iae", Err(_)) => tally.values += 1,
            ("iae", Ok(r)) => panic!("clamp: {}:{}: clamp({args}): java throws, rust {}", row.file, row.line, show(r)),
            (_, Err(e)) => {
                panic!("clamp: {}:{}: clamp({args}): java {}, rust {e:?}", row.file, row.line, row.tokens[3])
            }
            (expected, Ok(r)) => tally.double(&row, "clamp", &args, bits(expected), r),
        }
        tally.row();
    }
    tally.finish(MINIMUM_ROWS);
}

#[test]
fn clamp_long_to_int_is_math_clamp() {
    let mut tally = Tally::new("clamp_int");
    for row in rows("clamp_int") {
        assert_eq!(row.tokens.len(), 4);
        let v = bits(&row.tokens[0]) as i64;
        let lo = int(&row.tokens[1]);
        let hi = int(&row.tokens[2]);
        let args = format!("{v}, {lo}, {hi}");
        match (row.tokens[3].as_str(), java::clamp_long_to_int(v, lo, hi)) {
            ("iae", Err(ClampError::MinAboveMax)) => tally.values += 1,
            ("iae", r) => panic!("clamp_int: {}:{}: clamp({args}): java throws, rust {r:?}", row.file, row.line),
            (_, Err(e)) => {
                panic!("clamp_int: {}:{}: clamp({args}): java {}, rust {e:?}", row.file, row.line, row.tokens[3])
            }
            (expected, Ok(r)) => tally.integer(&row, "clamp", &args, int(expected) as i64, r as i64),
        }
        tally.row();
    }
    tally.finish(MINIMUM_ROWS);
}

#[test]
fn stream_sum_is_double_stream_sum() {
    let mut tally = Tally::new("sum");
    let mut elements = 0usize;
    for row in rows("sum") {
        let n = int(&row.tokens[0]) as usize;
        assert_eq!(row.tokens.len(), n + 2, "sum: {}:{} has {} columns", row.file, row.line, row.tokens.len());
        let values: Vec<f64> = row.tokens[1..=n].iter().map(|t| double(t)).collect();
        let args = values.iter().map(|&v| show(v)).collect::<Vec<_>>().join(", ");
        tally.double(&row, "stream_sum", &args, bits(&row.tokens[n + 1]), java::stream_sum(&values));
        elements += n;
        tally.row();
    }
    println!("sum: {elements} elements");
    assert!(elements >= MINIMUM_ROWS, "sum: {elements} elements, expected at least {MINIMUM_ROWS}");
    tally.finish(1);
}
