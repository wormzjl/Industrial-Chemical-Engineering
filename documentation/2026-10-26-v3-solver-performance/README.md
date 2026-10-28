# V3 solver performance

This batch measures and optimizes the V3 distillation-column Newton path against the 2026-10-26 checkout baseline.

Status: Implemented 2026-10-27 (merged with upstream main in `762f0e07`).

History:
- Baseline measured Holland, TJL19 wet, literature and CDU17 side-draw paths.
- Added serial PR workspace reuse and adaptive local-block predictor disablement.
- Tried one full Newton correction on Holland and TJL19 seeds; both correctly fell back because the residual gate was not met.
- Focused solver regressions pass; full quick suite has nine unrelated NeoForge `IChemE` static-initialization failures.
- Added `DRIFT_SPEC` and `AGGRESSIVE` repeat-request policies with a process-local accepted-state cache; strict
  fallback remains available when a warm seed is incompatible or fails certification.
- Combined the native inside-out PR snapshot and its temperature secant into one JNI batch transfer; native smoke,
  focused V3 slow gates and the Holland audit benchmark pass.
- Reused one mutable thermodynamic workspace across each serial Newton attempt; compile and the focused calculator
  slow gate pass, reducing per-probe allocation pressure without changing result objects or audit arithmetic.
- The aggressive interactive cold route now uses the admitted 1e-3 closure after the existing acceptance audit; the
  literature fixture measured 2.66 s with a passing certificate.
- The aggressive route now caps the repeated floor-support refresh at one pass; strict/offline solves retain the
  three-pass cap. The literature fixture still publishes a passing certificate and the focused regression lane passes.
- Large feature requests (38 stages and above) now use an intermediate 30-stage seed; the reproduced 42-stage case
  that previously spent the full 45 s deadline now uses a 15-30-42 continuation and
  publishes in about 9 s on the fixture, with the same acceptance audit.
- Merged upstream `main` at `eb0af66a`, resolving the creative-tab, assembly registration, design persistence and
  language-file conflicts while retaining the solver changes; focused aggressive-strategy tests pass.

Documents:
- `V3_SOLVER_PERFORMANCE_REVIEW.md`

Code areas:
- `src/main/java/com/wormzjl/icheme/science/column/v3/V3SimultaneousColumnSolver.java`

Open items:
- Repeat the full slow campaign with both strategy reports when a wall-time measurement lane is available.
