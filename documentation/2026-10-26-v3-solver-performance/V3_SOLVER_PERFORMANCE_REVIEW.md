# V3 solver performance review

## Expected behaviour
A serial Newton solve should reuse its caller-owned thermodynamic scratch storage. A local block predictor should stop consuming work when its direction repeatedly fails the same Armijo acceptance gate; the full finite-difference Jacobian remains the authoritative fallback.

## Baseline
Measured from the unmodified checkout on 2026-10-26:

- Holland Example 3-2 V3 correction: 9–36 ms per case, accepted and oracle-equivalent.
- TJL19 wet three-pumparound cases: 1.0–1.8 s.
- Literature 40-tray cases: 3.08 s and 5.67 s.
- CDU17 30-tray over-specified three-draw research path: 16.65 s, ending in the same typed nonconvergence expected by the contract.

## Change
`V3SimultaneousColumnSolver` now binds one `V3ThermoWorkspace` to each serial Newton attempt. Residual, finite-difference and line-search calls reuse it. The solver also disables local-block predictor assembly after two consecutive Armijo rejections in one attempt; accepted predictors reset the counter. No equation, tolerance, step, audit or publication rule changed.

## Verification
Focused initializer, simultaneous-solver and calculator tests passed. Holland benchmark passed with all three cases accepted and oracle-equivalent. Wet pumparound, literature and side-draw slow gates passed. A full quick suite reached 1447 tests but had nine unrelated `IChemE`/NeoForge static-initialization failures in fluid compatibility and accessory tests.

## Notes
The over-specified research path remains expensive because it intentionally disables the request-only liquid-supply screen; production defaults reject that geometry before Newton work. The optimization targets valid backend solves and reduces repeated work on difficult attempts without changing the typed result.

## One-step Newton trial
The existing bounded solver was run with `maximumIterations = 1`, which performs one full finite-difference Newton correction and one Armijo-gated trial. A perturbed Holland Example 3-2 seed stopped at maximum scaled residual `1.99e-4` (required `1e-8`) after about 25 ms. A cold TJL19 crude seed stopped at `13.88` after about 55 ms. Both failed before the unchanged convergence and acceptance gates. One-step mode is therefore useful as a warm-start probe only; production must continue automatically with the existing multi-step path.

## Follow-up optimization
The production finite-difference builder now accepts the residual already evaluated at the start of the Newton iteration. This removes one duplicate full PR property pass per rebuilt Jacobian while retaining the same central probes and matrix values. A one-sided probe experiment was rejected: it reduced work but failed eight slow physical-path gates, so it is not part of the implementation.

## Boston-Sullivan inside-out trial
For a dry cold-start rung, a strict PR outer pass captures each tray phase's fugacity coefficients and enthalpy. A
temperature-only secant supplies local slopes, avoiding the full component derivative matrix. The inner MESH solve
reuses those affine K/H values and is limited to a small iteration budget and a 25 K trust region. The candidate is
then corrected with strict PR and the existing final Newton certificate; if it does not improve the strict residual,
the unchanged strict seed path is used. Wet trays, steam, side draws, pumparounds and continuation rungs stay on the
strict/local-block path. The production event labels the trial as `Boston-Sullivan inside-out` for measurement.

## Repeat-request strategies

`V3ColumnCalculator.calculateWithStrategy` exposes two repeat-request policies. `DRIFT_SPEC` keeps the complete
continuation chain and may use a previous accepted state after strict compatibility checks. `AGGRESSIVE` uses the
same strict fallback but shortens large (32+ stage) cold continuation to a 15-stage anchor followed by the requested
grid. On the 40-stage literature fixture this measured about 2.3--2.5 s versus about 3.6--4.0 s for the cold baseline,
with strict residuals below `4e-14` and a passing global energy audit. Accepted numerical seeds remain process-local;
they are never written to saved block facts, and incompatible seeds are discarded automatically.

## Warm correction follow-up

The aggressive repeat-request path now sends the warm state to the original untruncated problem exactly once; a
previous version prepared a truncated problem and then prepared it again, which forced the cold fallback. On the
same accepted state, strict audited corrections measured 0.56--0.79 s for the literature case, 0.09 s with the
halved top cooler, and 0.68 s for methane. All three returned through `warm/aggressive` and passed the existing
acceptance gates. Cold wet starts remain continuation-bound and are unchanged by this fix.

## Local C++ toolchain

The Windows development machine now has CMake and WinLibs MinGW-w64 UCRT installed through winget. `./gradlew
nativeBuild` successfully produced `build/native/libicheme_column_solver.dll`; the native target exports the C ABI
`icheme_column_solve`. The build emits a shared library only; CMake's private compiler-identification probe files are
build metadata and no solver executable is produced or launched.

## Native smoke lane

The optional `nativeSmoke` Gradle task now loads the shared library through JNI and solves a scalar `x² - 2 = 0`
probe through the exported C++ Newton kernel. `NativeColumnSolver` accepts either a shared-library path or directory
through `icheme.native.path`; without it, production remains on the Java fallback. The task passed on Windows with
`libicheme_column_solver.dll`, and no native solver process is started.

The JNI boundary also supports a Java residual callback for migration experiments, but the V3 production solver does
not enable it: every callback copies a full residual vector across JNI and the PR/MESH properties still execute in
Java. The Holland 3-2 calculation gate remained accepted after the native changes; its current Java V3 correction
measured 0.0096–0.0429 s from an oracle seed, while cold starts measured 0.14–0.83 s across the three split cases.
An opt-in native V3 predictor was tried against this gate and rejected because its alternate root failed the existing
acceptance audit; that experimental hook was removed, leaving the native ABI available without changing production
results.

## Batched PR outer pass

The native ABI now includes a row-major batch operation for classical Peng–Robinson phase fugacity coefficients,
residual enthalpy and compressibility. Boston–Sullivan's optional outer snapshot uses it with one JNI call per strict
outer refresh, while the existing Java inner MESH and final strict audit remain unchanged. Enable it only for a
measurement run with `-PnativeLibraryPath=build/native -PnativeThermoBatch=true`.

The Holland 3-2 benchmark with the batch pass remained audit-accepted. On this machine the three cases measured
0.273/0.629/0.155 s for cold starts and 0.0375/0.0108/0.0092 s from the oracle seed. The Java-only comparison was
0.300/0.655/0.129 s cold and 0.0416/0.0234/0.0100 s seeded. The small spread is expected for this small 11-stage
fixture; the batch path is intended to reduce repeated PR work on large multistage columns.

## Combined derivative batch

The native snapshot now returns the current PR phase values and forward temperature derivatives in one JNI call. It
removes the second Java-to-native array transfer and keeps the ideal-gas enthalpy derivative in the registered Java
package. The strict Java model and acceptance audit remain authoritative. On the same Holland fixture the measured
cold times were 0.2729/0.6233/0.1352 s and seeded corrections 0.0421/0.0243/0.0093 s; all three cases retained
`auditAccepted=true`. The small fixture is JNI-transfer dominated, so this change is expected to matter more for
larger columns than for the 11-stage benchmark.

Validation: `nativeBuild`, `nativeSmoke`, and focused V3 slow gates passed. No native solver executable is produced;
the C++ target remains a cross-platform shared library.

## Newton workspace allocation

Each serial Newton attempt now reuses one mutable `V3ThermoWorkspace` for residual probes, colored finite
differences, local blocks and line-search trials. Published fugacity and derivative records already copy their
arrays, so overwriting the workspace cannot change a retained residual or certificate. This removes repeated kernel
workspace allocation and reduces garbage-collection pressure; `compileJava` and the focused
`V3ColumnCalculatorTest` slow gate passed. A heap campaign was not run yet, so the exact peak reduction still needs
measurement on the 40-stage case.

## Aggressive interactive closure

The first interactive request is routed through `V3SolveStrategy.AGGRESSIVE`. Its bounded fast policy now uses the
admitted `1e-3` MESH closure instead of spending time on the final `1e-8` digits used by the strict/offline route.
The acceptance audit, conservation checks and final Newton-step gates remain active, and the closure is recorded in
the result certificate. On the 40-stage literature fixture the cold aggressive path measured 2.66 s and published a
passing certificate; the same path at the strict closure was about 3.0--3.8 s on this machine. Repeat requests using
the strict drift policy are unchanged. The aggressive first request also caps the repeated floor-support refresh at
one pass; strict/offline solves retain the historical three-pass cap. The focused V3 calculator, stage-continuation
and aggressive-strategy tests pass, with the aggressive fixture at about 3.25 s in the Gradle worker (excluding build
startup).

## Deadline regression on 42 stages

The in-game log showed a 42-stage feature request entering the 15-stage-to-target jump, failing its material-closed
fallback, and then spending the remaining deadline in repeated feature recovery. Its terminal result was
`DEADLINE_EXCEEDED` after 45 seconds. The aggressive continuation now inserts a 30-stage seed for requests of 38 or
more stages. The reproduced 42-stage case publishes an audited result in about 9.1 seconds, versus about 65 seconds
before the change. The strict schedule remains unchanged.
