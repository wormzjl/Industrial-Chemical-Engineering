# 2026-10-28-native-column-default

Purpose: use the C++ column library by default, with the Java path wherever the library is missing (owner request 2026-10-28: "Use C++ solver as default").

Status: In progress since 2026-10-28 (branch `claude/distillation-column-integration-fef62f`, base `ac572f12`, beside the column operation plan); the owner chose option A (keep the C++ default) on 2026-10-29. Index: [../INDEX.md](../INDEX.md).

## Documents

- [NATIVE_DEFAULT_REVIEW.md](NATIVE_DEFAULT_REVIEW.md): what the C++ library covers, the change, the checks, what the default changes in numbers (no speed, last-digit results), a side finding (non-separating columns pass the audit) and options A to D.
- Tool folder: [tools/native-column-default/](../../tools/native-column-default/README.md), the five probes behind section 5 of the review.

## Code areas

`src/main/cpp` (static runtime, one output file name and folder for every toolchain, README), `build.gradle` (CMake from `-PnativeCmake`, the PATH or Visual Studio; `nativeResources` packs the library into the jar's `natives/<os>-<arch>/`), `science/column/v3/NativeColumnSolver` (loads the jar's library by default, version check, log line, the outer pass on by default), `NativeColumnSolverTest`, code guide `column-v3.md` and `README.md`.

## History

- 2026-10-28 (`9c5eb01f`): the C++ library is built by `processResources` when CMake is found, packed into the jar and loaded by default; the C++ Peng-Robinson outer pass of the inside-out trial runs unless `-Dicheme.native.thermo.batch=false`. Checks: column quick tests 405/405, column slow gates 99/99 with the library loaded; `NativeColumnSolverTest` 3/3 with the pass on and off; the jar copies and reuses the library. The C++ pass equals the Java kernel to 5e-14 in ln(phi); a separating crude column publishes the same products to 7e-13 relative and takes the same time. The C++ Newton stays a test probe (not wired; slower than the Java banded Newton). Code guide: `column-v3.md` (class row, owner row, the determinism exception) and `README.md` (the exception).
- 2026-10-29: owner decision: option A of the review, the C++ library stays the default with the Java path as fallback. No code change.
- 2026-10-29: owner decision on the side finding: the PC03/PC10 column with its feed below the bubble point behaves as it should (it simulates what could happen). No task.

## Open items

- A jar carries only its build machine's library (Windows x86-64 here); Linux and macOS run the Java path.
