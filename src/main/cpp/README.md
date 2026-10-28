# C++ column library

`icheme_column_solver` is a C++17 shared library behind a C ABI and a JNI adapter: a DLL on Windows, an SO on Linux,
a DYLIB on macOS. It is never an executable. The column uses it by default and runs on its Java path wherever the
library is missing.

## What runs in C++

- `icheme_column_pr_batch_temperature_derivatives`: the strict Peng-Robinson outer pass of the Boston-Sullivan
  inside-out trial (fugacity coefficients, residual enthalpy, compressibility and their one-sided temperature
  slopes for every tray in one call). The trial runs on the first rung of a cold solve of a dry column with no side
  draws, steam or pumparounds, at most two passes per solve. `NativeColumnSolverTest` holds the pass to the Java
  kernel's values.
- Everything else stays in Java: the MESH residual, the Jacobian, the Newton solve and the acceptance audit. The
  strict Java model publishes every result.
- `icheme_column_solve` (a dense finite-difference Newton with a residual callback) and `icheme_column_pr_batch` are
  probes for the test only.

## Build and packaging

`processResources` builds the library and packs it into the jar under `natives/<os>-<arch>/`
(`nativeConfigure`, `nativeBuild`, `nativeResources`). Gradle takes CMake from `-PnativeCmake`, the PATH, or the
copy Visual Studio (Build Tools included) carries beside MSVC. Without CMake the jar carries no library and a
message says so. The runtime is linked statically, so the library needs only the operating system.

By hand:

```text
cmake -S src/main/cpp -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --config Release
```

A jar carries the library of the machine that built it. Other platforms run the Java path.

## Loading

`NativeColumnSolver` loads, in order: the file or folder `icheme.native.path` names; the jar's library for this
platform (in place from a folder, else copied once to `<game folder>/IChemE/natives/`); `icheme_column_solver` on
`java.library.path`. A library without the JNI adapter, or whose version is not the one Java expects, is refused.
The log says which library loaded, or why none did.

Switches: `-Dicheme.native.thermo.batch=false` keeps the library loaded and runs the Java outer pass (Gradle
`-PnativeThermoBatch=false`); `-Dicheme.native.path=<file or folder>` loads another build (Gradle
`-PnativeLibraryPath=...`). `./gradlew nativeSmoke` loads `build/native` directly and runs the checks.
