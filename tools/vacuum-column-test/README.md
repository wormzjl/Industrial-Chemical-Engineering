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

## Entry points and history

- `VacuumColumnTest.java`, `run.ps1`: accepted CDU, direct component transfer, heater-basis calculation, feed flashes, column solve and inspection.
- `export.ps1`: component checks and client-format exports; `VacuumTrace.java`, `trace.ps1`: observation-only instrumentation; `VacuumAnalyze.java`, `analyze.ps1`: independent captured-state checks; `BinaryPhaseProbe.java`, `probe.ps1`: fixture qualification.
- 2026-10-28: exact default Tia Juana CDU-bottoms feed and failed wet/dry/reduced-stage trials; no production changes.
- 2026-10-29: startup diagnosis (`494d3e45`), then zero-reflux/dry-stage and phase-admission fixes (`dba4ba45`), accepted vacuum retest and reusable exports. One-off fixture code remains here, not in production source or a new Gradle switch. No source was detached from a tracked product path.
