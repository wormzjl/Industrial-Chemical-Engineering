# Full CDU convergence and revision comparison — 2026-10-29

Before: `494d3e45`. After: `9c0ce21f` (production fix `dba4ba45`). Tools: `3509f67a`. Branch `codex/vacuum-column-test`, not merged; this task adds offline tools and reports only.

## 1. Expected behaviour

The dry-stage/phase-admission fix must also preserve ordinary positive-reflux CDU solutions. A full CDU must close its component and water boundaries and pass its production energy/equilibrium audits. Random specifications are not automatically feasible; exhausted budgets remain unresolved.

## 2. Experiment

- Fresh shipped Tia Juana input: 40 interior stages, feed stage 37 at 365 C, top 250 kPa(a), diameter 8 m, reflux 4.17, condenser 59 C, zero reboiler duty, bottom steam, three side draws and three pumparounds. All generated cases retain positive reflux and three draws/cooling zones.
- Freeze 1,000 cold inputs with seed `20261029`. Four controls use the exact preset, inlet +1/-1 K and +5% flow. Near cases retain 40 stages and the source composition; broad/boundary cases vary stage count, load, composition, temperature, pressure, reflux, steam, cuts and cooling. Full authored ranges are in the tool README; they are not refinery design standards.
- Compare the first 200 identical requests on both revisions: eight workers, AGGRESSIVE cold route, null warm seed, 15 s cooperative allowance. Run all 1,000 only on the chosen updated form. Compile each revision's production science/resources; one common harness records each revision's own acceptance audit.
- Validate every completed input against its frozen row and every matched request across revisions. Independently sum all hydrocarbon components and water in accepted product streams; supplementary tolerance `1e-6*max(1,HC feed mol/s)`. Production audits cover energy and phase admission; no separate CDU energy-reference calculation is claimed.

## 3. Results

| Matched 200 CDU cases | Before | After |
|---|---:|---:|
| Production accepted | 129 (64.5%) | 131 (65.5%) |
| Numerical/linear failure | 18 | 18 |
| Time budget | 53 | 51 |
| Input/spec rejection or internal/harness error | 0 | 0 |

- All four controls pass both revisions. Near acceptance is 60→61/78; broad 56→56/78; boundary 9→10/40. There are 128 preserved acceptances, three gains (171, 174, 194) and one formerly accepted case that reaches the budget (144); no formerly accepted case becomes an explicit numerical failure.
- Case 144 is a 60-stage, 305 C boundary case. Its exact frozen request accepts alone with one worker and a 60 s allowance in **11.199 s**, maximum scaled residual **3.33e-14**. This resolves the sole lost raw acceptance; its different-load replay is not blended into the original 131/200 count.
- The new 1,000-case population accepts **682 (68.2%)**; 81 numerical/linear failures and 237 budget outcomes; no input rejection or internal/harness errors.

| Updated population | Cases | Accepted | Numerical/linear | Time budget |
|---|---:|---:|---:|---:|
| Control | 4 | 4 | 0 | 0 |
| Near | 398 | 342 (85.9%) | 8 | 48 |
| Broad | 398 | 270 | 32 | 96 |
| Boundary | 200 | 66 | 41 | 93 |

- All 682 accepted outcomes pass the common component/water criterion. Largest component error: 2.42226e-4 mol/s; water error: 5.68435e-14 mol/s. Thirty-three accepted points exceed model flood fraction 1, so mathematical convergence does not establish operability.
- Conclusion: CDU convergence is broadly preserved, with a small increase in matched acceptance. This sample does not establish a general convergence or speed improvement; the broader VDU population remains much harder and the heater-coupling control still fails.

## 4. Limits and reproduction

- Per the owner's instruction, waited for the other review-world server to finish. A short server restart later overlapped the updated run, observed around completed cases 125–225 and gone by 303. `tmp/cdu-after/RUN_LOAD_NOTE.md` records it. Nominal budgets match; load does not, so no speed claim is supported. Timeouts are censored, not proven infeasibility.
- Reproduce with `cdu-inputs.py`, `matched.ps1` on each source root and `compare-report.py --replay ... --plot`; commands and ownership are in `tools/vacuum-column-test/README.md`. Frozen inputs: `tmp/cdu-inputs/`; old/new results: `tmp/cdu-before/`, `tmp/cdu-after/`; isolated replay: `tmp/cdu-budget-input/`, `tmp/cdu-budget-replay/`.
- Reports: `tmp/cdu-after/comparison.json`, `paired.csv`, `CDU_COMPARISON.md`, `comparison.png/svg`; raw accepted inspections, streams, duties and hydraulic results remain in `cases.jsonl`. Complete counts and inputs validate; the plot was visually checked.
- No production changes since the passing 1810 quick + 188 slow tests. These offline campaigns/report checks add no Gradle gates. Code guide: no change. No GUI/world validation or main/version change; all unmerged run outputs and plotting downloads are retained.
