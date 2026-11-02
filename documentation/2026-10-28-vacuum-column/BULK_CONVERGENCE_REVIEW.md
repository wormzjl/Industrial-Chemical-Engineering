# Full-column and bulk convergence screen — 2026-10-29

Production `dba4ba45` on `codex/vacuum-column-test` (not merged); tools `3509f67a`; offline harness only, no further production changes.

## 1. Expected behaviour

- A complete cold column closes components, water and energy, passes independent physical admission, and represents an unsupplied top section with exactly zero liquid at zero external reflux.
- A convergence screen must distinguish accepted states, input/specification rejection, numerical failure and an exhausted time budget. An admitted random specification is not thereby proven feasible.

## 2. Experiment

- One fresh 40-stage default Tia Juana CDU supplies the complete bottoms composition. The reference VDU has 10 interior stages, two cuts, two pumparounds, bottom steam, positive-diameter hydraulics and zero reflux.
- A separate full-chain control tries to match a 400 C upstream heater at the published VDU feed pressure. This is kept separate from the fixed-inlet population.
- Population: 1,000 deterministic cold cases, seed `20261029`, four controls and interleaved near-design/broad/boundary distributions, eight workers, nominal 15 s cooperative solve budget. No accepted profile is reused between population cases.
- Near: 6–16 stages; feed 0.8–1.2 times source; inlet ±15 K; top 1.5–3 kPa; steam 0.5–1.5 wt%; cooling 20–30 MW times flow scale. Broad: 4–40 stages; feed 0.4–1.6; 320–410 C; 1–10 kPa; steam 0.2–3 wt%; cooling 5–50 MW times flow scale. Diameter, cuts, condenser T and composition also vary; full bounds and generation rules are in the tool README.
- Boundary variants include 64 stages, missing features, high recovery, small diameter, 20 kPa, high cooling and 5 wt% steam. Some exceed model/input limits. Synthetic composition tilts retain source zeros; they are not measured alternative assays. Every input has exactly zero external reflux; direct top-stage cooling may supply liquid there.
- Supplemental checks: every-component/water error <= `1e-6*max(1,HC feed mol/s)`, energy error <= `1e-5*largest boundary energy rate`, dry-gas beta >= `1-1e-6`; fresh production audit. These authored thresholds and ranges are not external design standards.

## 3. Results

All 1,000 unique inputs/results validated. **228/1,000 (22.8%)** pass production acceptance and supplemental checks; 229 production acceptances include one stricter balance-tolerance miss. No internal/harness errors.

| Distribution | Cases | Verified accepted | Numerical/phase failure | Time budget | Input/spec rejection | Extra-check failure |
|---|---:|---:|---:|---:|---:|---:|
| Control | 4 | 2 | 0 | 2 | 0 | 0 |
| Near | 398 | 147 | 145 | 106 | 0 | 0 |
| Broad | 398 | 63 | 134 | 200 | 0 | 1 |
| Boundary | 200 | 16 | 50 | 97 | 37 | 0 |
| Total | 1,000 | 228 | 329 | 405 | 37 | 1 |

- The fresh CDU accepts. At the original 355.429 C VDU inlet, the heater mismatch at the published 4.603 kPa feed-stage pressure is -1.418881 MW. Correcting the inlet to 359.820533 C returns NONCONVERGENCE with exhausted liquid at the second side draw. The full-chain heater/column control is not accepted; its failed state is not a physical product prediction.
- No timeout is counted as proof of infeasibility or ultimate nonconvergence. The screening success rate is conditional on these distributions, the AGGRESSIVE route, eight-worker load and the cooperative budget; elapsed values are not serial benchmarks.
- A production-accepted result can exceed a stricter supplementary tolerance; this is recorded separately from a production acceptance failure.
- Of 721 requests with a structurally dry top, 198 pass; accepted dry sections have exactly zero hydrocarbon liquid and pass independent gas flashes. The other accepted geometries can have top-stage cooling supplying liquid. Accepted sizes include twelve 30-stage cases and one 40-stage case; all eighteen 64-stage requests time out.
- Eight accepted points exceed the model's flood ratio 1: mathematical convergence is separate from operability. Largest verified component error is 1.36301e-4 mol/s and energy closure error 22.293 W. Case 62 retains its production certificate but exceeds the stricter component criterion (2.75771e-4 mol/s); its energy/phase checks pass.
- This does not establish broad VDU convergence: many inputs remain unresolved within the budget. Failure families (e.g. equilibrium and side-draw closure) are terminal evidence, not a proven common cause.
- Supplementary matched first 200 inputs: `494d3e45` accepts 0; updated original campaign accepts 51 under production audits, 50 with supplemental checks (one POSTCHECK), with 59 numerical, 82 budgets and eight rejections. The separate updated 200-case repeat overlapped an external test and is excluded; its `tmp/vacuum-after/RUN_CAVEAT.md` is retained. Ordinary positive-reflux CDU comparison is in `CDU_CONVERGENCE_REVIEW.md`.

## Reproduction and verification

- `tools/vacuum-column-test/screen.ps1` compiles/runs `VacuumBulkScreen.java`; `screen-report.py` validates all inputs/IDs and emits CSV, JSON, Markdown and optional PNG/SVG plots. Run instructions and concurrency ownership are in the tool README.
- Raw artifacts: registered `tmp/vacuum-screen/`; smoke qualification: `tmp/vacuum-screen-smoke/`. Original exports and earlier runs remain intact. Complete input/result rows and selected full snapshots are retained; no case is silently dropped.
- Eight named, bounded platform workers own separate property sessions and explicit catalog contexts; only the coordinator writes. Every future is observed; owned workers are cancelled/joined on interruption/failure. No other test/client/campaign was observed alongside the run after the launcher waited for the other lane.
- The eight-case smoke test and reporter passed before the full campaign. Production quick 1810 + slow 188 passed before this tooling-only task; they were not rerun alongside the campaign. Code guide: no change. No GUI/world validation or fluid-network campaign is claimed.
- The main numerical limits remain separate open work: general dry middle sections, packed-column pressure loss, heater/column coupling and convergence across the exploratory parameter envelope. Main/version are unchanged.
