# Vacuum-column experiment and regression tools

Batch: [2026-10-28-vacuum-column](../../documentation/2026-10-28-vacuum-column/README.md). Original baseline: `79b853e0` (0.17.1); production fix: `dba4ba45`.
Purpose: transfer every component of the accepted default CDU bottoms into a vacuum column, test zero external reflux with dry upper stages, and independently examine the historical false-phase startup states.

## Run and export

From this worktree, with PowerShell and JDK 21:

```powershell
./tools/vacuum-column-test/run.ps1 -Phase cdu
./tools/vacuum-column-test/run.ps1 -Phase vdu -Settings tools/vacuum-column-test/zero-reflux-vdu.json
./tools/vacuum-column-test/export.ps1
```

For exact replay of the reported feed, retain the original accepted `tmp/vacuum-test/cdu.json` and run only the VDU and export steps. `run.ps1` accepts `-JavaHome` and `-GsonJar`; defaults use this machine's installed JDK 21 and cached Gson 2.10.1. It compiles reachable production science with `javac --release 21`, without Minecraft, Gradle or native libraries. Scratch/classes/results go in registered `tmp/vacuum-test/`. Each solve has a cooperative 180 s safety deadline. A scientific failure is JSON data, so inspect `success` and `failureCode` even when the process exits normally.

`zero-reflux-vdu.json` is the accepted test configuration: 10 interior stages, feed stage 8, top 2 kPa(a), 15 m diameter, organic reflux exactly zero, 1 wt% bottom steam, 10%/25% molar side cuts, and 10/15 MW pumparound cooling. The duties are authored test settings; the earlier 1/2 MW trial did not converge. They are not source-reported refinery specifications.

The fixed column inlet is 355.429 C, calculated by isenthalpic letdown of a nominal 400 C heater outlet at the original CDU-bottoms pressure to a flat 2 kPa feed-pressure guess. Hydraulic correction raises actual feed-stage pressure to about 4.603 kPa; the inlet temperature stays fixed, so this is not an enthalpy-matched outer heater/column calculation. The model has sieve-tray hydraulics, not packing. Pumparounds are stage heat sinks without separately solved circulation streams. General dry middle sections and experimentally qualified product cuts remain outside this test.

Generated files: `cdu.json`, `feed.json`, `zero-reflux-vdu.json`, `vacuum-feed-mixture.json`, `vacuum-column-template.json`, `vacuum-feed.csv`, and `verification.json`. The verification checks every transferred component, both CDU and VDU product balances, and the acceptance certificate. The result includes independent steam-adjusted hydrocarbon TP flashes of dry gas nodes. AGGRESSIVE publication used closure `0.001`; read the actual certificate, not just the residual magnitude.

For a manual client import, copy the mixture JSON into a chosen game folder's `IChemE/mixturepreset/` and the column JSON into `IChemE/columnpreset/`, refresh the library, and load **both**. Successful exports say `[OFFLINE ACCEPTED]`; failed exports say `[UNCONVERGED]`. No game folder was modified and no client/world import was tested. Ten interior stages display as twelve trays; feed stage 8 displays as tray 9. Stage 1 (display tray 2) is the exactly dry top contact stage; node 0 is the external condenser.

`wet-vdu.json`, `dry-vdu.json` and `diagnostic-vdu.json` preserve the original failed requests. Do not interpret their failed states as product yields. Existing old outputs are retained alongside the new result.

## Historical trace and current audit

The historical observation-only capture was made at `494d3e45` with the original wet request. Its sequence IDs 130, 131, 132, 1714 and 2384 are specific to that code and instrumentation. On the retained capture:

```powershell
./tools/vacuum-column-test/analyze.ps1
```

`VacuumAnalyze.java` re-evaluates roots, combined-flow TP flashes, MESH residuals and the full production audit. The current default asserts that both captured false-phase startup states are rejected, while three collapsed pairs and two genuine two-phase controls retain their independent evidence. It writes `phase-analysis-rejected.csv` and `audit-analysis-rejected.txt` under `tmp/vacuum-trace/`, preserving the original analysis. The historical analyzer at `494d3e45` expects acceptance instead; that version and its captured data reproduce the old admission defect. This analysis does not claim that the changed solver follows the old trajectory.

To capture a new trajectory, use `trace.ps1 -Settings <case.json> -OutputName <short-name>`. It instruments copies of two source files under that registered `tmp/<short-name>/source/`, refuses to overwrite `trace.jsonl`, and never edits production sources. New captures require deliberately selecting their own observations; do not apply the old pinned sequence IDs to them.

## Fixture qualification probe

`BinaryPhaseProbe.java` / `probe.ps1` are offline investigation tools, not production code or a Gradle gate. They need compiled main/test classes (`./gradlew.bat testClasses`). Modes `current`, `pot` and `learned` record the small fixture grids used to distinguish genuine positive phase states from formerly accepted collapsed roots. Outputs/classes stay in registered `tmp/vacuum-gates/`.

```powershell
./tools/vacuum-column-test/probe.ps1 -Mode current
./tools/vacuum-column-test/probe.ps1 -Mode baseline -SourceRoot <own-baseline-worktree-at-494d3e45>
```

`baseline` uses the old test helper through reflection to capture states and actual convergence certificates. It requires the exact historical head. The checked-in `src/test/resources/column/invalid-binary-roots.json` preserves that original capture; current gate tests freshly re-evaluate it and verify that a numerical certificate cannot bypass phase rejection. Positive hot-start/recovery fixtures use live boilup and retain all conservation and acceptance gates.

## Bulk cold-start screen

```powershell
./tools/vacuum-column-test/screen.ps1 -Count 1000 -Workers 8 -Seconds 15 -OutputName vacuum-screen
python -B tools/vacuum-column-test/screen-report.py tmp/vacuum-screen --plot
```

Use the configured/bundled Python for the second command; `--plot` needs Matplotlib and NumPy. Set `MPLCONFIGDIR` to this run's `tmp/<name>/mpl-cache/` if the environment does not already confine its caches. Without `--plot`, the reporter needs only Python's standard library.

`VacuumBulkScreen.java` first freshly solves the 40-stage default CDU, transfers its exact bottoms, and attempts a 400 C heater/VDU pressure-matching outer loop. The actual conditioning outcome is retained even if it fails. The population is centered on the original fixed-temperature, zero-reflux design; it does not silently treat an unmatched heater as a closed full-chain solution. Optional accepted conditioning cold/warm checks are recorded only when the outer loop matches within the authored 100 W criterion.

The 1000-case population consists of four explicit controls and interleaved near-design, broad and boundary distributions. Each case has two potential side cuts and two potential cooling zones, bottom steam, sieve-tray hydraulics and **exactly zero external reflux**. Every population call supplies a null warm seed. Near cases vary stage count 6–16, flow 0.8–1.2 times source, inlet T ±15 K, top 1.5–3 kPa, diameter 14–15 m, steam 0.5–1.5 wt%, cut fractions 0.08–0.12/0.20–0.30 and cooling 20–30 MW times flow scale. Broad cases vary 4–40 stages, flow 0.4–1.6, inlet 320–410 C, top 1–10 kPa, diameter 10–15 m, steam 0.2–3 wt%, cuts 0.03–0.25/0.10–0.40 and cooling 5–50 MW times flow scale. Synthetic composition tilts preserve every exact-zero source component; these are not measured alternative crude assays. Boundary cases override one broad-case setting, including 64 stages, absent features, high recovery, small diameter, 20 kPa, 60 MW cooling, 5 wt% steam or a 300 C inlet. Some intentionally exceed input/model capability bounds.

The bounds are authored test choices, not recommended refinery design ranges. Top liquid is exactly zero where no feed/cooling return supplies the upper section; a cooling return at contact stage 1 can wet that stage even with zero external reflux. All accepted outcomes get fresh audits, every-component/water/energy boundary checks and independent dry-gas flashes. Extra screening tolerances are component/water error <= `1e-6*max(1,HC feed mol/s)`, energy error <= `1e-5*largest boundary energy rate`, and dry-gas beta >= `1-1e-6`. A `POSTCHECK` outcome can therefore retain its production acceptance certificate while exceeding a stricter screening criterion.

Eight named platform workers own separate solve/property workspaces and bounded cooperative deadlines. At most eight futures are outstanding; an explicit bounded executor/completion queue and a single coordinator own submission, results and output. All futures are consumed; failure/interruption cancels outstanding futures and shuts down the owned executor. The material catalog is immutable for the run and explicitly installed/restored per task. No shared thermodynamic workspace or worker file writes exist. The launcher refuses to start while another test/client/campaign is detected. Run no other campaign/test/client alongside it.

Outputs remain in the registered run folder: complete deterministic `inputs.jsonl`, completed `cases.jsonl`, `manifest.json`, progress/summary, selected full outcome snapshots, fresh `cdu.json` and heater-step records. The launcher refuses to overwrite an existing case file. The report validates every completed input against its frozen manifest, checks zero reflux and flattens `cases.csv`, `analysis.json`, `SCREEN_RESULTS.md` and optional PNG/SVG charts. Time budgets are censored outcomes; they do not establish physical infeasibility or ultimate nonconvergence. Elapsed values are under eight-worker load and are not serial performance benchmarks.

## Full CDU revision comparison

Use the accepted fresh CDU input from the bulk run to generate a separate positive-reflux CDU population. These commands reproduce the reported layout; choose fresh short output names when retaining an earlier run. Keep the old revision in your own clean worktree (`494d3e45` for the reported comparison).

```powershell
python -B tools/vacuum-column-test/cdu-inputs.py tmp/vacuum-screen/cdu.json tmp/cdu-inputs
./tools/vacuum-column-test/matched.ps1 -SourceRoot ../vacuum-baseline-scratch -OutputName cdu-before -InputsFile tmp/cdu-inputs/inputs.jsonl -Count 200 -Workers 8 -Seconds 15
./tools/vacuum-column-test/matched.ps1 -SourceRoot . -OutputName cdu-after -InputsFile tmp/cdu-inputs/inputs.jsonl -Count 1000 -Workers 8 -Seconds 15
python -B tools/vacuum-column-test/compare-report.py tmp/cdu-before tmp/cdu-after --plot
```

The generator registers its named `tmp/` output and refuses to replace `inputs.jsonl`. It preserves the source package, component order and exact-zero components. Four controls use the exact 40-stage preset, inlet ±1 K and +5% flow. Near cases retain 40 stages and composition, vary flow 0.8–1.2, inlet ±10 K, top 225–275 kPa(a), reflux 4.17 ±5%, condenser ±3 K, diameter `8*sqrt(flow scale)` times 0.95–1.05, and steam/cut/cooling rates times 0.9–1.1 and flow scale. Broad cases use 20/30/40/50/60 stages, flow 0.5–1.5, inlet 330–400 C, top 150–350 kPa(a), reflux 2–6, condenser 40–80 C, diameter `8*sqrt(flow scale)` times 0.85–1.15, steam factor 0.5–1.5, cut/cooling factors 0.7–1.3 and synthetic composition tilt ±0.15. Features scale with flow and stage positions scale with stage count. Boundary cases override one broad setting: 64 stages, reflux 0.5/8, absent steam, top 100/500 kPa, cooling factor 1.8, cut factor 1.7 or inlet 305/425 C. Every case retains positive reflux, three side draws and three pumparounds; all have zero reboiler duty. These authored bounds are exploratory rather than validated operating envelopes.

`VacuumMatchedScreen.java` uses production APIs shared by both revisions. It reconstructs each frozen request, calls AGGRESSIVE with no warm seed, and retains raw production acceptance, diagnostics and every accepted inspection/stream/duty/hydraulic result. Its bounded workers and catalog ownership follow the bulk runner's pattern. `matched.ps1` compiles against `-SourceRoot` but keeps classes and outputs in this worktree's registered `tmp/<OutputName>/`; `version.json` records the source revision. A full campaign uses eight workers; `-Workers 1` is for isolated budget replay. Run sequentially and keep other tests/clients/campaigns idle.

`compare-report.py` verifies unique IDs, completeness, every result's frozen input, cross-revision input equality, matching worker/budget settings and positive reflux. It reports each version's own production acceptance without retroactively replacing the old auditor. Its common supplemental check sums every hydrocarbon component and water at the boundary (tolerance `1e-6*max(1,HC feed mol/s)`). Energy/phase admission remains the production audit; no separate CDU energy-reference check is claimed. All accepted production audit checks must pass. Flood fractions above one are reported separately from mathematical acceptance. Outputs are `comparison.json`, `paired.csv`, `CDU_COMPARISON.md` and optional PNG/SVG. The full updated population and matched subset remain separate.

For a new budget-class loss, put the exact original JSONL row(s) into an indexed `tmp/<name>/inputs.jsonl`, then run `matched.ps1 -SourceRoot . -OutputName <replay-name> -InputsFile <file> -Count <rows> -Workers 1 -Seconds 60`. Pass that folder with `compare-report.py ... --replay <replay-folder>`; the reporter checks exact request equality and keeps different-load replay counts separate. The reported case 144 replay is in `tmp/cdu-budget-replay/`. If another process starts mid-run, retain a `RUN_LOAD_NOTE.md` in the run folder; the reporter carries it into the generated report. Timing is not a speed comparison under unequal load.

The recorded old/new heads are `494d3e45` and `9c0ce21f` (production `dba4ba45`). See the batch's CDU review for counts, the server overlap and replay; the separate VDU repeat's `RUN_CAVEAT.md` explains why that repeat is excluded from the primary comparison. No solver campaign outputs are tracked. Plotting can use a workspace-local Matplotlib/NumPy install with `PYTHONPATH` and `MPLCONFIGDIR` under indexed `tmp/vacuum-plot/`; the installation and wheels used for these figures are retained there. Both reporters work without plotting dependencies when `--plot` is omitted.

## Entry points and history

- `VacuumColumnTest.java`, `run.ps1`: accepted CDU, direct component transfer, heater-basis calculation, feed flashes, column solve and inspection.
- `export.ps1`: component checks and client-format exports; `VacuumTrace.java`, `trace.ps1`: observation-only instrumentation; `VacuumAnalyze.java`, `analyze.ps1`: independent captured-state checks; `BinaryPhaseProbe.java`, `probe.ps1`: fixture qualification.
- `VacuumBulkScreen.java`, `screen.ps1`, `screen-report.py`: fresh CDU/full-chain control and cold zero-reflux VDU population; `cdu-inputs.py`: frozen full positive-reflux CDU cases; `VacuumMatchedScreen.java`, `matched.ps1`, `compare-report.py`: revision-neutral cold replay, paired/full reports and isolated budget replay.
- 2026-10-28: exact default Tia Juana CDU-bottoms feed and failed wet/dry/reduced-stage trials; no production changes.
- 2026-10-29: startup diagnosis (`494d3e45`), then zero-reflux/dry-stage and phase-admission fixes (`dba4ba45`), accepted vacuum retest and reusable exports. One-off fixture code remains here, not in production source or a new Gradle switch. No source was detached from a tracked product path.
- 2026-10-29: added the full-chain conditioning control and reproducible bulk cold-start screen/report tools. Population results and the separate coupling failure are recorded in the batch's bulk review; production equations are unchanged.
- 2026-10-29: added the full positive-reflux CDU population, frozen-input revision comparison and isolated budget replay; matched acceptance 129→131/200 and full updated acceptance 682/1,000. Findings and load limits are in the CDU review. No production source was detached and no gate suite changed.
