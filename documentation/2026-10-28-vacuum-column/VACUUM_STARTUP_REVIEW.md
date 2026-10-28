# Vacuum startup failure diagnosis — 2026-10-29

## 1. Expected behaviour

- The authored case feeds 212.0794 mol/s (339.6424 t/h) of exact CDU bottoms to a 2 kPa column, with 52.3695 mol/s steam and a 400 C upstream heater. Its independent inlet flash is two-phase. Feasibility and yields still require the complete requested-state solve.
- At equilibrium, each nonzero liquid/vapor pair must represent distinct physical phases. Assigning the same single liquid root and composition to two flow labels does not create vapor. Equal fugacity residuals alone cannot distinguish this trivial solution from real VLE.
- A startup seed may violate balances temporarily, but an auxiliary column must pass physical checks before it seeds a larger grid. Package fit edges must not become solver temperature walls (AGENTS.md, Thermo validity).

## 2. What the code did instead

- Baseline production code `79b853e0`, harness `8d511f71`. Observation-only copies of `V3ColumnCalculator` and `V3SimultaneousColumnSolver` reproduce the exact original failure summary and maximum residual `6.510834110239748`.
- The requested **2 kPa wet column is never reached**. `solvePressureContinuation` first strips steam, side products and pumparounds, substitutes **1.87893 MW** boilup, raises pressure to **150 kPa**, and attempts the 4→8→10-stage dry startup column. Its feed enthalpy becomes 229.401 kJ/mol, versus 289.430 kJ/mol in the authored nominal vacuum feed.
- **Confirmed defect: interior phase validity is missing from acceptance.** The four-stage startup state passes a fresh production audit, although stages 1–3 have virtually identical liquid/vapor compositions and the same single dense-liquid PR root. Independent production TP flashes return **zero vapor** at each of those stages. A second accepted startup branch repeats the defect.

| Four-stage startup node | T (K) | Claimed vapor (mol/s) | max absolute x−y | Z liquid / vapor | Independent vapor fraction |
|---|---:|---:|---:|---|---:|
| 1 | 545.3623 | 299.1843 | 5.17e-10 | 0.0202651 / 0.0202651 | 0 |
| 2 | 546.9352 | 11607.4665 | 3.79e-9 | 0.0202241 / 0.0202241 | 0 |
| 3 | 564.0869 | 1034.6031 | 6.01e-9 | 0.0198005 / 0.0198005 | 0 |

- The same capture's bottom two nodes are valid two-phase controls: independent vapor fractions 0.001424 and 0.545156, with liquid/vapor Z separated by >0.8. A test must distinguish physical phase identity; demanding three PR roots universally would be incorrect.
- `PengRobinsonKernel.selectRoot` legitimately returns the only root for either phase label. `V3AcceptanceAuditor.audit` checks equilibrium residuals on every tray but independently verifies phase identity/split **only at the condenser**. That combination admits the false interior split; the EOS root-selection arithmetic itself is not shown to be wrong.
- **Handoff amplification:** interpolation of the accepted four-stage state to eight stages gives a maximum absolute tray energy defect of **1.28536 GW**. The unconditional material/bubble-point projection increases it to **2.88414 GW** and peak vapor traffic to **80441 mol/s**, about 379 times the feed. `projectedSeedOrPrevious` accepts the projection based on positive/log-coordinate feasibility, without requiring a better energy or full-residual seed.
- Newton moves to a stalled state with a node at **900 K**, leaving a stage-2 energy defect of **+138.081 MW**. Even tiny subsequent trials are rejected by `PengRobinsonKernel.requirePackageState`, whose package range ends at 900 K. This is a separate violation of the no-range-holds rule, and explains the terminal rejection wall; lifting it alone is not demonstrated to repair the bad phase/seed.
- The original accepted CDU does not show this identical-composition signature: its minimum maximum-component x−y distance over flowing interior/sump pairs is 0.128686. This is a narrow check, not a replacement audit of the upstream CDU.
- Sieve trays versus packing remain a separate modelling limitation. Hydraulic correction is never reached, so it did not cause this observed failure. The evidence also does not establish that the requested draw rates/duties are feasible.

## 3. Proposal for owner validation

- Fix phase admission in the existing column thermodynamic/acceptance layer: reject collapsed same-phase splits on interior stages and recover with a physically valid phase branch/seed. Use the existing PR/TP-flash machinery; do not equate one cubic root with invalidity in general.
- Rework the affected continuation handoff so it carries a physical accepted anchor and compares a projected candidate's full residual/energy defect before selecting it. Qualify continuation to the actual vacuum request, including its wet/side-draw/cooling ramp.
- Replace fit-range walls with the repository's continuous property continuation and numerical-box policy, qualified separately from phase admission. Do not treat a hotter stalled state as a valid operating result.
- Evidence/reproducer: [tools/vacuum-column-test](../../tools/vacuum-column-test/README.md), `trace.ps1` then `analyze.ps1`; generated `tmp/vacuum-trace/trace.jsonl`, `phase-analysis.csv`, `audit-analysis.txt`. Fresh audits accept both invalid startup states; three collapsed pairs and two valid controls verified; instrumentation leaves the terminal outcome unchanged. No production fix, Gradle suite or in-game check; code guide: no change.
