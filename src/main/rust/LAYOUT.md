# icheme_column: the JNI layout

ABI **1** (`icheme-jni/src/layout.rs`, `ABI`; `NativeColumnSolver.ABI` must equal it). The number rises with any
change of a layout or a signature below. Library: `icheme_column` (`icheme_column.dll`, `libicheme_column.so`,
`libicheme_column.dylib`). Symbols: `Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_<name>`, each
`(JNIEnv*, jclass, ...)`.

## Transport status (every `int` export)

| Code | Name | Java's action |
|---|---|---|
| 0 | `OK` | read the outputs |
| 1, 2 | `CANCELLED`, `DEADLINE` | (no call returns them yet) `control.checkpoint()`, which throws |
| 3 | `BAD_LAYOUT` | a null array, a length or a shape against the header, a non-finite input, a pending exception after a region copy: log once, Java path |
| 4 | `UNSUPPORTED_PACKAGE` | (none yet) Java path |
| 5 | `OUT_OF_MEMORY` | a buffer could not be reserved: Java path |
| 6 | `PANIC` | a Rust panic caught at the boundary: Java path, library off for the session |
| 7 | `FP_ENVIRONMENT` | this thread flushes subnormals (FTZ) or reads them as zero (DAZ): Java path |

Arrays are copied in with `Get<Type>ArrayRegion` and out with `Set<Type>ArrayRegion`; no critical arrays. Every
length is checked before any index is used.

## `int nativeAbi()`

Returns the ABI number.

## `String nativeFormulation()`

The bytes of `V3ColumnCalculator.FORMULATION_REVISION + ";" + WET_ASSUMPTIONS_REVISION + ";" +
HEAT_ASSUMPTIONS_REVISION`, as compiled into the library (today
`v3-dry-mesh-r24-flash-trace-phase-r1-derived-pairs-r1;v3-wet-assumptions-r3;v3-heat-assumptions-r1`). Java
builds the same string and compares with `equals`; null (the JVM could not make the string) is a mismatch.

## `int nativeSelfTest(double[] out)`

`out.length == 256`. Fills the known answers of `icheme-jni/src/self_test.rs`; Java computes the same with
`RustNativeTables.selfTestValues()` (to move into `NativeColumnSolver`) and compares raw bits slot by slot.

| Slots | Values |
|---|---|
| 0..47 | `log`, `exp`, `expm1`, `cbrt`, `cos`, `acos`, eight fixed arguments each (in that order, 8 slots per function) |
| 48..63 | `Math.fma`, sixteen triples |
| 64..79, 80..95 | `DeterministicMath.pow`, `strictPow`, sixteen pairs |
| 96..103 | the solution of the fixed band solve: size 8, kl 2, ku 1, a(r, r) = 4 + r, a(r, c) = 1 / (1 + r + 2 c), b(r) = 1 + r / 8 |
| 104..107 | its backward error, minimum pivot, maximum pivot, pivot growth |
| 108, 109 | its outcome (0.0) and pivot swaps; 110..127 are 0.0 |
| 128..191 | reserved for one kernel evaluation (stage (b)); 0.0, Java skips them |
| 192..255 | reserved for one derivative evaluation (stage (b)); 0.0, Java skips them |

## `int nativeBandedSolve(int size, int kl, int ku, double[] band, double[] rhs, double[] solution, double[] evidence, int[] counts)`

One `V3BandedPivotedSolver.solve(matrix, rhs, workspace)` with a fresh workspace.

| Argument | Length | Contents |
|---|---|---|
| `size`, `kl`, `ku` | | `size >= 1`, `kl, ku >= 0`, `size (kl + ku + 1) <= Integer.MAX_VALUE` |
| `band` | `size (kl + ku + 1)` | `V3BandedMatrix.values`: entry (r, c) at `r (kl + ku + 1) + c - r + kl`, every entry finite |
| `rhs` | `size` | finite (Java checks it first and throws `IllegalArgumentException` as now) |
| `solution` | `size` | out, written only when `counts[0] == 0` |
| `evidence` | 4 | out: `[0]` backward error (0.0 on a failure), `[1]` minimum pivot magnitude, `[2]` maximum pivot magnitude, `[3]` pivot growth |
| `counts` | 7 | out: `[0]` outcome, `[1]` pivot swaps, `[2]` 1 if the scaled band held a -0.0 (the full-width solve of P8), `[3]`, `[4]` updated entries (low, high 32 bits), `[5]`, `[6]` full-width entries (low, high) |

Outcome `counts[0]`, with what Java returns or throws for it:

| Outcome | Java |
|---|---|
| 0 | `new Result.Success(solution, evidence[0], evidence[1], evidence[2], counts[1], evidence[3])` |
| 1 | `new Result.Failure(SINGULAR, "V3 band matrix contains no nonzero pivot", 0.0, 0.0, 0, 0.0)` |
| 2 | `new Result.Failure(ILL_CONDITIONED, "V3 banded LU scaling exceeds the finite numerical range", 0.0, 0.0, 0, 0.0)` |
| 3 | `new Result.Failure(SINGULAR, "V3 banded LU encountered a zero or tiny pivot", evidence[1], evidence[2], counts[1], evidence[3])` |
| 4 | `new Result.Failure(ILL_CONDITIONED, "V3 banded LU pivot spread exceeds the configured conditioning guard", evidence[1], evidence[2], counts[1], evidence[3])` |
| 5 | `new Result.Failure(BACKWARD_ERROR_EXCEEDED, "V3 banded LU correction does not satisfy the backward-error guard", evidence[1], evidence[2], counts[1], evidence[3])` |
| 6 | `throw new IllegalStateException("V3 banded LU produced a non-finite entry")` |
| 7 | `throw new IllegalStateException("V3 banded LU lost a usable back-substitution pivot")` |

In every outcome the workspace adds `counts[2]` to `fullWidthSolves` and the two longs to `updatedEntries` and
`fullWidthEntries`, as the Java loop does before it returns or throws. A long is
`((long) high << 32) | (low & 0xffffffffL)`.
