# Vacuum-column experiment

Batch: [2026-10-28-vacuum-column](../../documentation/2026-10-28-vacuum-column/README.md). Production-code baseline: `79b853e0` (0.17.1).
Purpose: solve the shipped default CDU, preserve its complete bottoms composition, and try a downstream vacuum column through the production PR78/MESH solver and acceptance gates. No production equations or gates are changed.

## Run

From this worktree, with PowerShell and JDK 21:

```powershell
./tools/vacuum-column-test/run.ps1 -Phase cdu
./tools/vacuum-column-test/run.ps1 -Phase vdu -Settings tools/vacuum-column-test/wet-vdu.json
./tools/vacuum-column-test/export.ps1
```

`run.ps1` accepts `-JavaHome` and `-GsonJar`; defaults use this machine's installed JDK 21 and cached Gson 2.10.1. It compiles the reachable production science code with `javac --release 21`, runs without Minecraft/Gradle/native libraries, sets Java scratch paths inside `tmp/vacuum-test/`, and registers that folder in `tmp/INDEX.md`. Each solve has a cooperative 180 s safety deadline; no timing benchmark is performed. Scientific failures are recorded in JSON and are not converted to process-launch errors: read `success` and `failureCode`.

Entry points: `VacuumColumnTest.java` (accepted CDU export, direct component transfer, TP flashes, enthalpy-matched heater letdown and column attempts), `run.ps1` (compile/run), `export.ps1` (independent component checks and client-format exports).
`wet-vdu.json` is the chosen inlet-energy-consistent case. `use400CHeaterEnthalpy` overrides its nominal `feedTemperatureK` using 400 C at the source bottoms pressure, followed by an isenthalpic TP-root search at the nominal column feed pressure. The nominal pressure guess is flat 2 kPa, as the client template authors it. Any accepted hydraulic correction would change this pressure; its inlet energy would need checking again.
`dry-vdu.json` removes steam, draws and cooling and tests a hotter 400 C flash-zone inlet. `diagnostic-vdu.json` reduces that dry case to two interior stages. These are failure diagnostics, not recommended industrial configurations. An extra DRIFT_SPEC replay of the initial hot wet case returned the same four-stage failure as AGGRESSIVE.

## Outputs and use

All generated files stay under `tmp/vacuum-test/`: `cdu.json`, `feed.json`, each named VDU result, `vacuum-feed-mixture.json`, `vacuum-column-template.json`, `vacuum-feed.csv`, and `verification.json`.
The mixture export carries the existing package ID and every exact bottoms mole fraction, including traces and zeros. The column export carries the matching flow and operating values, and its name explicitly says UNCONVERGED.
To try them manually in a chosen dev game folder, copy the mixture JSON into `IChemE/mixturepreset/` and the column JSON into `IChemE/columnpreset/`; refresh the calculator library and load **both**. These files were exported and checked offline; no client/world import was tested and no game folder was modified.
The client counts the condenser and sump: ten interior stages appear as twelve trays; feed stage 8 appears as tray 9. Pumparounds are stage heat sinks in the existing model, without a separately solved circulation rate. The existing positive-diameter setting always uses sieve-tray hydraulics; it cannot be interpreted as a packed VDU.

## History

- 2026-10-28: generated the accepted default Tia Juana CDU bottoms, ran hot wet/dry and reduced-stage diagnostics, corrected the chosen inlet to a 400 C heater/isenthalpic-letdown basis, and verified/exported the final failed request. Source balance error < 1.61e-8 mol/s; all VDU attempts failed to publish. No Gradle suite, native build or in-game check; no production changes or source detachment.
- 2026-10-29: diagnosed the failure through observation-only temporary source copies, fresh production audits and independent PR/TP checks. Confirmed false two-phase startup states, handoff amplification and a package-range rejection wall; no production fix.

## Reproduce the startup diagnosis

After the original CDU and wet VDU outputs exist, run:

```powershell
./tools/vacuum-column-test/trace.ps1
./tools/vacuum-column-test/analyze.ps1
```

`trace.ps1` instruments copies of two source files under `tmp/vacuum-trace/source/`; it does not edit production sources. `VacuumTrace.java` only observes pass states, selected Newton iterates and line-search outcomes. Generated classes, a copy of the CDU input and all traces stay under `tmp/vacuum-trace/`, registered in `tmp/INDEX.md`. The script refuses to overwrite an existing `trace.jsonl`; preserve that capture before a new run.
`analyze.ps1` compiles `VacuumAnalyze.java` against unmodified production sources. It independently re-evaluates captured states, runs their full production audit, compares liquid/vapor roots and compositions, and TP-flashes their combined flows. It asserts that two startup states pass the existing audit despite three collapsed liquid/vapor pairs, checks two genuine two-phase controls, and verifies that tracing leaves the original terminal failure unchanged.
The analyzer pins capture sequence IDs 130, 131, 132, 1714 and 2384 for this baseline and instrumentation. They identify reproducible diagnostic observations, not persisted product identities. Changed numerical code or instrumentation requires deliberately selecting the corresponding new capture states; an assertion failure must be investigated rather than bypassed.
Results: `trace.jsonl`, `phase-analysis.csv` and `audit-analysis.txt`; see [VACUUM_STARTUP_REVIEW.md](../../documentation/2026-10-28-vacuum-column/VACUUM_STARTUP_REVIEW.md). Neither this offline probe nor its assertions are a Gradle gate or a proposed production implementation.
