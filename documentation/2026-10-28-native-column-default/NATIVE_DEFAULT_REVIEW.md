# Native column default review (2026-10-28)

Owner request: "Use C++ solver as default". Branch `claude/distillation-column-integration-fef62f`, base `ac572f12`.

## 1. Expected behaviour

The column uses the C++ library without a switch wherever it can load, and the Java path elsewhere. A default must not change what the column publishes beyond rounding.

## 2. What the code had

- C++ covered one piece: the strict PR outer pass of the Boston-Sullivan inside-out trial (`V3MeshResidualEvaluator.captureThermoSnapshot`). The trial runs on the first rung of a cold solve of a dry column (no side draws, steam, pumparounds or wet trays; 4 stages or more; seed residual above 5e-2), at most two passes per solve. The MESH residual, Jacobian, Newton and audit are Java.
- The C++ Newton (`icheme_column_solve`) is a dense finite-difference Newton through a JNI residual callback: n + 1 residual evaluations per Jacobian, against the Java solver's banded coloured differences. It is not wired, and a V3 predictor built on it failed the audit (batch 2026-10-26).
- Off by default: two switches, no library in the ordinary build or the jar. Gradle called a bare `cmake` (not on this machine's PATH), MSVC put the DLL in a `Release` folder, MinGW named it `libicheme_column_solver.dll`, and the DLL needed the VC++ runtime.

## 3. Change

- CMake: static runtime (the DLL needs only `KERNEL32`); one file name and folder for every toolchain.
- Gradle: CMake from `-PnativeCmake`, the PATH or Visual Studio's own copy; `nativeResources` packs the library into the jar under `natives/<os>-<arch>/`. Without CMake the build says so and packs nothing.
- `NativeColumnSolver`: loads `icheme.native.path`, else the jar's library (in place from a folder, else copied once to `<game folder>/IChemE/natives/`), else `java.library.path`. It refuses another version and logs which library loaded. The C++ pass is on unless `-Dicheme.native.thermo.batch=false`.
- `NativeColumnSolverTest`: the library loads where the build packed it; the C++ pass gives the Java kernel's values; a separating crude column publishes the same products with either pass.

## 4. Checks

- Column quick tests 405/405 and column slow gates 99/99 with the library loaded (both JVMs logged the load; the slow one from a cold solve). `NativeColumnSolverTest` 3/3, also with `-PnativeThermoBatch=false`.
- The built jar: the library is copied to `IChemE/natives/` and reused on a second start.
- C++ minus Java PR, 17 components, 3 trays, both phases: ln(phi) 5.0e-14, H 2.6e-10 J/mol, Z 1.4e-16.

## 5. What the default changes, in numbers (probes in `tools/native-column-default/`)

- Speed: none. Dry Tia Juana crude column, 4 to 30 trays: C++ 120 to 277 ms, Java 108 to 275 ms per cold solve (one run each). The C++ pass is at most two calls per cold solve; the time is in the Java Newton.
- Results on a separating column: the same products to 7e-13 relative or better, but not bit-identical. "Bit-identical on every JVM" now holds per library build; a machine without the library (Linux, macOS, a jar built without CMake) publishes the Java digits.
- Degenerate inputs: on the PC03/PC10 binary (section 6) the root the solve reaches depends on rounding, so the two passes publish different columns (6 trays, 2 MW reboiler: D = 25.3 against 42.0 mol/s) or one of them fails.

## 6. Side finding (not this task)

- Expected: PC03 (Tc 569 K, 123 g/mol) and PC10 (Tc 869 K, 382 g/mol) split sharply in 2 to 30 trays at reflux 2.
- The code: every published success on that binary has distillate and bottoms at the feed's 50/50 (both phases the same, no split), and the audit accepts it, also for the nominal pilot that `V3ColdStartTest` requires to succeed. The Java path that ships does the same.
- Proposal: its own task. The audit refuses a column whose phases do not split; the pilot gates move to a separating case.

## 7. Options for the owner

Owner decision 2026-10-29: A.

- A (as built, recommended): keep the C++ default. Cost: last-digit differences between machines with and without the library.
- B: back to opt-in. Java stays the default and bit-identical everywhere; the C++ pass stays behind a switch.
- C: A plus a bit-identical C++ pass: port `DeterministicMath` (log, cbrt, acos, cos) and the kernel's root repair to C++, and gate the snapshot bit for bit.
- D: a C++ solver: the MESH residual, Jacobian and Newton in C++. A planned batch of its own; expect at most the JIT-to-C++ ratio on the property passes, not an order of magnitude.
