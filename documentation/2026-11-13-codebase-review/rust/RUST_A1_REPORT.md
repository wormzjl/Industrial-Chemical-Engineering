# Rust stage (a), part 1: the workspace and `icheme-math` (report)

Branch `claude/review-rust-a1-math-5ced47`, 2026-11-13. Plan: `RUST_COLUMN_PORT_PLAN.md` sections 4, 5 (stage (a)), 6, 10.

## Summary

1. **Built.** A Cargo workspace at `src/main/rust/` (Rust 1.99.1 pinned, clippy bans, cargo limited to 4 jobs with its
   output in `build/native/cargo`) and the crate `icheme-math`: no dependency, `#![forbid(unsafe_code)]`, the fdlibm
   5.3 functions the column calls, `DeterministicPow` with its tables and `FdlibmPow` behind it, a software
   correctly rounded `fma`, and the `java.lang.Math` semantics with the `DoubleStream.sum()` compensation. No Gradle
   ran; no file outside `src/main/rust/`, the generator and this report changed (plus the research folder and its
   index row in the main checkout).
2. **Proof.** `RustBitTables.java` (test tree, run with `javac`/`java` of JDK 21.0.11, no Gradle) writes 15 bit
   tables, 24 files, 17.1 MB, each under 0.9 MB: 312 735 rows. `cargo test`: 15 tests passed, 0 failed, in the
   test (debug, overflow checks on) and the release profile. Every non-NaN value is bit-equal; see item 4 for NaN.
3. **Lint.** `cargo clippy --all-targets -- -D warnings`: clean (dev and release). `cargo fmt --check`: clean.
   The ban works: a scratch crate calling `ln`, `mul_add`, `max`, `powi`, `cos`, `floor` gets six
   `disallowed_methods` errors. The fdlibm module allows `excessive_precision`, `approx_constant`, `eq_op` and
   `needless_late_init` (the C's 21-digit literals, its own pi and ln 2, its `(x - x) / (x - x)`); all 106 decimal
   constants with a hex comment parse to exactly those bits (`tmp/rust-a1/check-constants.js`).
4. **Decision for the main thread: NaN bits.** The test requires a NaN where Java has a NaN, of any bits; all
   other values require the same 64 bits. Of 6 386 NaN results, 6 347 also have Java's bits; 39 do not, by two
   mechanisms, both outside what either language specifies:
   - signalling NaN input to `cbrt` (28 rows) and `floor` (3 rows): the JDK's Java port returns the argument
     unchanged (`cbrt(0x7ff0000000000001)` = `0x7ff0000000000001`); the fdlibm C returns `x + x`, quieted
     (`0x7ff8000000000001`), and so does the transliteration;
   - `fma(±0, ±inf, NaN)` (8 rows): Java's fallback returns `Double.NaN` (`0x7ff8000000000000`), the software fma
     computes `a * b + c` and x86 gives the default NaN of `0 * inf` (`0xfff8000000000000`). Java is not
     consistent here either: `Math.fma` and `Math.max` are intrinsics in compiled code, and `FdlibmPow` and
     `StrictMath.pow` differ in 3 NaN payloads of the pow table (both Java).
   Options: **A (taken, recommended)** NaN equals NaN, as now; **B** copy the JDK's `return x` into `cbrt` and
   `floor` (leaves the C) and return a canonical NaN from `fma` for NaN operands; B still cannot match Java where
   Java's interpreter and compiled code disagree. The column never reads NaN bits.
5. **Coverage** of the table run (`-C instrument-coverage`): 94.8 % of lines. Every unreached line is unreachable
   from the column's calls: `kernel_rem_pio2`'s `prec` 0 and 3 cases and `rem_pio2`'s own small-argument and
   NaN guards (`cos` handles both first), the C's "generate inexact" fall-throughs (`huge + x > 1` false), the
   compile-time branches of the hex-literal parser, and `fma`'s `drop >= 128` guard (bit 0 weighs at least
   2^-1199, so drop is at most 125).
6. **Downloaded.** From `https://netlib.org/fdlibm/` (`www.netlib.org` answers 301 there), 16 files into the main
   checkout's `research/2026-11-13-codebase-review/fdlibm-5.3/` with SHA-256 in its `MANIFEST.md`, and a row in
   `research/INDEX.md` (patched in place): `readme`, `fdlibm.h`, `e_log.c`, `e_exp.c`, `s_expm1.c`, `s_cbrt.c`,
   `s_cos.c`, `k_cos.c`, `k_sin.c`, `e_rem_pio2.c`, `k_rem_pio2.c`, `e_acos.c`, `e_pow.c`, `s_scalbn.c`,
   `s_floor.c`, `s_copysign.c`. With rustup: toolchain `1.99.1-x86_64-pc-windows-msvc` (profile minimal) with
   `clippy`, `rustfmt` and `llvm-tools` (the installed `stable` had no clippy). rustup updated itself from 1.29.2
   to 1.29.3 during that install (its default; not asked for).
7. **Read, not ported.** The JDK 21 sources of `Math`, `StrictMath`, `Collectors`, `DoublePipeline`, for the
   semantics of `sum`, `clamp`, `scalb`, `round`, `ulp`. The fdlibm files come from the C; `java.rs` is written
   from the specification.
8. **Not done.** `log1p` (3 calls in `V3FactorizedNeuralFeatures`) and `log10` (1 call, `V3ColumnCalculator`
   2626) are called by the column package but are outside the plan's list (section 6 names them for later).
   `Math.ceil`, `copySign`, `floorMod`, `addExact`, `multiplyExact` (one or two integer-context calls each) are
   not wrapped. Part 2 (JNI crate, Gradle tasks, C++ removal, the agent-guard line for cargo, pitfall 18) is not
   started, as briefed. The batch `README.md` and `documentation/INDEX.md` are left to the main thread.
9. **Commits.** One commit on this branch: the workspace, the generator, the tables and this report.

## Files

| File | Lines | What |
|---|---|---|
| `src/main/rust/Cargo.toml` | 24 | workspace, lints (`unsafe_code` forbid, `disallowed_methods` deny), release profile of section 6 |
| `src/main/rust/Cargo.lock` | 7 | no dependency |
| `src/main/rust/rust-toolchain.toml` | 8 | 1.99.1, clippy, rustfmt, the Windows target |
| `src/main/rust/.cargo/config.toml` | 5 | `jobs = 4`, `target-dir = build/native/cargo` |
| `src/main/rust/clippy.toml` | 39 | the 14 banned methods of section 6, plus `max`, `min`, `clamp`, `floor`, `ceil`, `round`, `round_ties_even`, `trunc`, `log`, `log10`, `log2`, `ln_1p`, `exp2`, `asin`, `atan2`, `sin_cos`, `tanh`, `cosh` |
| `src/main/rust/rustfmt.toml` | 3 | width 120, as the Java |
| `src/main/rust/README.md` | 65 | what, build and test, the ten rules, licences |
| `icheme-math/Cargo.toml` | 13 | `license = "MIT AND SunPro"` |
| `icheme-math/src/lib.rs` | 73 | `DeterministicMath` twins |
| `icheme-math/src/fdlibm/mod.rs` | 68 | word helpers (`__HI`, `__LO`) |
| `icheme-math/src/fdlibm/{log,exp,expm1,cbrt,acos}.rs` | 92, 100, 136, 68, 77 | `e_log.c`, `e_exp.c`, `s_expm1.c`, `s_cbrt.c`, `e_acos.c` |
| `icheme-math/src/fdlibm/cos.rs` | 104 | `s_cos.c`, `k_cos.c`, `k_sin.c` |
| `icheme-math/src/fdlibm/rem_pio2.rs` | 460 | `e_rem_pio2.c`, `k_rem_pio2.c` |
| `icheme-math/src/fdlibm/{scalbn,floor}.rs` | 67, 77 | `s_scalbn.c`, `s_copysign.c`, `s_floor.c` (called by `k_rem_pio2`) |
| `icheme-math/src/fdlibm/pow.rs` | 337 | `FdlibmPow` (`e_pow.c`) |
| `icheme-math/src/pow.rs` | 823 | `DeterministicPow`, a `const fn` reading the Java hex literals, the five tables (copied by `tmp/rust-a1/pow-tables.js`) |
| `icheme-math/src/fma.rs` | 122 | software fma and its proof |
| `icheme-math/src/java.rs` | 223 | `Math` semantics, `Double.compare`, casts, `StreamSum`, `stream_sum` |
| `icheme-math/tests/bit_tables.rs` | 344 | the 15 table tests |
| `icheme-math/tests/data/` | 24 files + `.gitattributes` | the tables, LF pinned |
| `src/test/java/.../science/math/RustBitTables.java` | 838 | the generator |

## The software fma

Non-finite operands, a zero factor and a zero addend reduce to ordinary operations that are exact before their one
rounding (`a b + c` with an infinite or NaN factor; `±0 + c`; `c` when `c` is infinite and `a b` finite; `a b` when
`c` is zero). Otherwise the product `ma mb 2^(ea + eb)` (106 bits) is exact in a `u128`; the larger-magnitude operand
is shifted so its top bit is bit 125, the other aligned to it with the dropped bits replaced by a sticky 1 in bit 0,
the two added or subtracted, and the sum rounded once (53 bits or the 2^-1074 grid, ties to even). It is correctly
rounded: with no dropped bit the sum is exact; bits drop only when the sum is at least 2^125 - 2^106, so the
rounding point is at bit 72 or above, while the larger operand's 20 low bits are zero and the sticky 1 makes the
computed sum odd, never on a rounding boundary and on the same side of every boundary as the exact sum.

## Tables (rows; random arguments from SplitMix64, seed `0x1c4e3e2026111300`)

| Table | Java | Rows | Random | Arguments beyond the specials |
|---|---|---|---|---|
| `log` | `StrictMath.log` | 22 897 | 20 000 | high words 0x00100000, 0x7ff00000, 0x3ff00000, 0x3fe00000; the mantissa words 0x6a09c, 0xffffe, 0x6147a, 0x6b851 (and neighbours) at 7 exponents; all 2 098 powers of two; 1 within 64 ulps |
| `exp` | `StrictMath.exp` | 23 988 | 20 000 | high words 0x40862E42, 0x3fd62e42, 0x3FF0A2B2, 0x3e300000; both thresholds within 16 ulps; the k = -1021 switch; multiples of ln2/2 |
| `expm1` | `StrictMath.expm1` | 21 649 | 20 000 | 0x4043687A, 0x40862E42, 0x3fd62e42, 0x3FF0A2B2, 0x3c900000; k = 1 with x < -0.25, k = 20, 56, -2 |
| `cbrt` | `StrictMath.cbrt` | 23 616 | 20 000 | ± cubes 1..1000, ± 2^(3k), 2 000 subnormals, negatives throughout |
| `cos` | `StrictMath.cos` | 26 951 | 20 000 | k_cos/k_sin/rem_pio2 high words, all 32 `npio2_hw`, n pi/2 for n to 64 within 4 ulps, 2^19..2^1023, the Kahan-McDonald hardest reduction case |
| `acos` | `StrictMath.acos` | 20 370 | 20 000 | ±1 within 32 ulps, ±0.5 within 16, 2^-57 |
| `pow` | `DeterministicPow.pow`, `FdlibmPow.pow` (= `StrictMath.pow`) | 24 930 | 20 000 | 38 x by 47 y specials (the Javadoc cases), the fdlibm 5.3 bug pair, \|y\| > 2^31 and 2^64 near x = 1, sqrt(3/2) and sqrt(3), m near 2, results near 2^1024 and 2^-1075 |
| `pow_tables` | `DeterministicPow.tables()` | 645 | - | every table entry and constant |
| `fma` | `Math.fma` | 37 261 | 25 000 | 21^3 specials, ties and sticky bits at half an ulp of c, cancellations, exact short results, subnormal results, the kernel's three forms |
| `max_min` | `Math.max`, `min`, `Double.compare` | 22 304 | 20 000 | 48^2 special pairs, ±a pairs |
| `java_unary` | `Math.abs`, `floor`, `ulp`, `rint`, `round`, `(int)`, `(long)` | 21 028 | 20 000 | k and k + 0.5 for k in -40..40, 0.49999999999999994, 2^31, 2^52, 2^53, 2^62, 2^63, 2^64 within ulps |
| `scalb` | `Math.scalb` | 22 064 | 20 000 | n in ±{0..2, 52..54, 511..513, 1022..1024, 1074, 1075, 2046, 2098..2100, 50000, 50001}, `Integer.MIN/MAX_VALUE`; 2 000 results at the subnormal edge |
| `clamp` | `Math.clamp(double, double, double)` | 20 816 | 20 000 | 17 bound pairs incl. NaN, (+0, -0), min > max (throws) |
| `clamp_int` | `Math.clamp(long, int, int)` | 20 104 | 20 000 | long extremes, min > max (throws) |
| `sum` | `DoubleStream.of(v).sum()` | 4 112 arrays | 24 101 elements | empty, infinities of one and both signs, NaN, overflow, `{1e100, 1, -1e100}`, signed zeros, cancellations |

## Reproduce

```text
cd src/main/rust
cargo test                 # and cargo test --release
cargo clippy --all-targets -- -D warnings
```

Regenerate the tables (only when a Java math function changes) with the two commands in the class comment of
`RustBitTables.java`; with the fixed seed the output is identical on the same JDK.
