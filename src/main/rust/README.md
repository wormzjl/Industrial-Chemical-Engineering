# Rust workspace: the column solver port

This workspace ports the V3 column solver to Rust, with the Java path kept as the reference and the fallback
(`documentation/2026-11-13-codebase-review/RUST_COLUMN_PORT_PLAN.md`). A ported function gives exactly Java's bits:
the same IEEE operations in the same order on the same elementary-function bits. A difference is a port defect, not a
tolerance.

Two crates. `icheme-math` (no dependency, `#![forbid(unsafe_code)]`):

| Module | Java twin | Source |
|---|---|---|
| crate root | `DeterministicMath` (`log`, `exp`, `expm1`, `cbrt`, `cos`, `acos`, `pow`, `strictPow`) | the modules below |
| `fdlibm` | `StrictMath` (fdlibm 5.3) and `FdlibmPow` | the fdlibm 5.3 C files of `https://netlib.org/fdlibm/`, transliterated; the Sun notice heads each file |
| `pow` | `DeterministicPow` and its five tables | the Java class; the tables are its hexadecimal literals, verbatim |
| `fma` | `Math.fma` | a software fused multiply-add, correctly rounded (the proof is at the top of `fma.rs`) |
| `java` | `Math.abs`, `max`, `min`, `floor`, `rint`, `round`, `ulp`, `scalb`, `clamp`, `Double.compare`, the `(int)` and `(long)` casts, `DoubleStream.sum()` | the Java specification |
| `banded` | `linalg/V3BandedMatrix`, `linalg/V3BandedPivotedSolver` (with P8 and P9) | the Java classes, operation for operation |

`icheme-jni` builds the `cdylib` `icheme_column` (`icheme_column.dll` here), the only crate with `unsafe`: the JNI
exports `nativeAbi`, `nativeFormulation`, `nativeSelfTest` and `nativeBandedSolve`, each behind `catch_unwind`, a
floating-point environment check and length checks. Its one dependency, `jni-sys` 0.3.0 (declarations of `jni.h`, MIT
or Apache-2.0), is pinned in `Cargo.lock` and vendored in `vendor/`. The layouts and status codes are in
[`LAYOUT.md`](LAYOUT.md).

Every function with a Java twin carries `// java: <Class>.<method>` and keeps the Java name in snake case.

## Build and test

From this folder (`src/main/rust`). `rust-toolchain.toml` pins Rust 1.99.1 with clippy and rustfmt;
`.cargo/config.toml` limits cargo to 4 jobs, puts its output in the worktree's `build/native/cargo`, reads crates
from `vendor/` only and links the C runtime statically on Windows.

```text
cargo test --locked --offline                    # the bit tables, the band LU, the JNI crate
cargo test --locked --offline --release          # the same in the release profile
cargo clippy --locked --offline --all-targets -- -D warnings
cargo fmt --check
cargo build --locked --offline --release -p icheme-jni   # build/native/cargo/release/icheme_column.dll
```

`cargo test` reads `icheme-math/tests/data/*.txt`: one table per function (split into `name-1.txt`, `name-2.txt` when
over 900 kB), each row the raw bits of the arguments and of Java's result in hexadecimal. It compares every row and
stops at the first difference with its file, line, arguments and both bit patterns. A NaN in a table requires a NaN
of any bits: neither Java nor Rust specifies the bits of a NaN made by arithmetic, and Java itself returns different
NaN bits for the same call (interpreter and intrinsics). Every other value requires the same 64 bits.

The tables come from `src/test/java/com/wormzjl/icheme/science/math/RustBitTables.java`, run without Gradle with
JDK 21 (the commands are in its class comment). `banded.txt` and `icheme-jni/tests/data/self_test.txt` come from
`src/test/java/com/wormzjl/icheme/science/column/v3/linalg/RustNativeTables.java`: a band row names a case that
`tests/banded_tables.rs` rebuilds with the twin of the Java case generator, checks by an input fingerprint, solves
and compares output by output. Regenerate the tables only when the Java functions they prove change.

## The determinism rules (plan section 6)

1. No fused multiply-add by contraction: rustc does not contract `a * b + c`; set no `target-cpu` and no `+fma`.
2. `icheme_math::fma::fma` only where Java calls `Math.fma`; `f64::mul_add` is banned (it calls the C library).
3. No platform libm: `clippy.toml` bans `f64::{ln, exp, exp_m1, powf, powi, cbrt, sin, cos, acos, tan, atan, sinh,
   mul_add, hypot}`, and also `max`, `min`, `clamp` (Java semantics differ) and `floor`, `ceil`, `round`, `trunc`,
   `round_ties_even` (libm calls on baseline x86-64). `f64::sqrt` and `f64::abs` are allowed.
4. No fast-math and no reordering: every loop and every sum keeps the Java order; `DoubleStream.sum()` is
   `java::stream_sum`, never a plain loop.
5. Java semantics, not Rust defaults: `java::max`/`min` (NaN, -0.0 below +0.0), `java::round` (half up),
   `java::clamp` (an error where Java throws), `java::ulp`, `java::scalb`; Rust's `as` casts are Java's casts.
6. One thread per solve: no thread, no mutable static, no `thread_local`, no global cache; every function re-entrant.
7. The floating-point environment is checked at every export: flush-to-zero or denormals-are-zero set by another
   library on the thread returns status 7 and the request runs on Java.
8. The wall clock enters only through the deadline of the control buffer (part 2).
9. Integer arithmetic that Java lets wrap uses `wrapping_*`; the release profile sets `overflow-checks = false`.
10. Release profile: `opt-level = 3`, `codegen-units = 1`, `panic = "unwind"`, no CPU dispatch, one binary per target.

## Licences

The workspace is MIT, as the mod. The fdlibm transliterations carry the Sun Microsystems notice of their C files
(free use, copy, modification and distribution, the notice preserved; SPDX `SunPro`), so `icheme-math` is
`MIT AND SunPro`; `icheme-jni` is MIT and links `jni-sys` (MIT or Apache-2.0). The fdlibm files are transliterated from the C, not from the JDK's Java port (another licence);
`java.rs` is written from the Java specification, with the order of operations of `DoubleStream.sum()` that the
bit tables require.
