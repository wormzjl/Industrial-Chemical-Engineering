# Merge preparation: CHANGELOG, code guide, tooling cleanup

2026-11-12, subagent (Opus medium), on `b55053e87` (main 0.28.0 merged in). Documents and tools only; no Gradle run,
no commit. The version bump and the translation commit are not part of this task.

## CHANGELOG
Under `## [Unreleased]`: one `### Changed` line for the batch (state format 50, facts schema 25, chain-100 again) and
two `### Fixed` lines for defects main has: the open vessel below y = 63 (`9b1fc2b1f`) and the blanketed level loop
drum's cold-start hold (`4f2c922ec`, `f8f634ebd`). No version heading; `gradle.properties` unchanged.

## Code guide
- `fluid-runtime.md`: facts schema 25 (24 is main's converters; corpus `schema-25/`), state format 50 and
  `PackageKey(packageId, compounds, wet)` in the codec and facts rows and the envelope section; `IslandSets` (water
  named only where the island holds some); snapshot `b55053e87`.
- `verification.md`: the schema-25 image; a section of the batch's gates (16 new test classes, 2 helpers, the two
  `columnGates` list entries); chain-100's re-records up to `e616970c9`; snapshot `b55053e87`.
- `fluid-science.md`: `initialModes` (a flow valve set to no flow starts closed), `stockSeeds` (D54), `stocked` in
  `arrivingPhases`, the gas pinned at a port only when the liquid fills the vapour space (`2aebdcd3c`); state format 49
  and schema 25 in the old-saves line (it said 48 and 22); snapshot `f8f634ebd`.
- `cooling-towers.md`: a section for the tower on any liquid (stream, nozzle, fill, `Result.losses`); the stale
  `liquidWater` gate and priming paragraph replaced; `exhaust` reads the fill's own air; snapshot `ab6b90d42`.
- `column-v3.md`: the water record is r4 (`e1b9a3dd0`), not r3; the state line names water on the axis; snapshot `213b1b1e2`.
- `client-gui.md`: the "Water" label at 99 % (`DeviceColumn.aqueousIsWater`), `tower_other_losses`, `StreamProps` on
  three slots; snapshot `1059ece75`.
- `rotating-machinery.md`: `GasEntrainment.CLEAN_ONSETS`, `cleanShare`, `PumpHealth.GAS_RATIO`; snapshot `e3238449d`.
- `process-simulator.md`: run results write a stream's three slots with `FORMAT` still 3; snapshot `1bd0b4236`.
- `README.md`: the batch's summary, the engine row (20 files; water in the cubic, no free-water rule), the equilibrium
  flow line; snapshot `b55053e87`.
- Snapshot only (content current): `equilibrium-engine.md` `274e06ac1`, `thermo-materials.md` `ef4ed4e04`,
  `column-operation.md` `a7321ef64`. Main's 44cad3da1 snapshot lines stay beside the batch's.
- Code guide: no change in `heat-exchangers`, `equipment`, `machine-assemblies`, `instrument-accessories`,
  `process-control`, `wired-control-power`, `pipe-laying`, `effects`: the batch's code there is the three-slot state
  API only (`Ejectors`, `FiredHeaters`), read, not checked line by line.

## Tooling cleanup
- `perl tools/merge-lane/main-reach.pl .`: the 3 unreached classes (`DhBridge`, `DhGroups`, `IrisBridge`) predate the
  batch; it added none.
- `tools/aqueous-solvents/`: the README's entry points now name every file (the probes, the baselines,
  `kernel-count.mjs`); the `tools/INDEX.md` row is four fields with a shorter purpose.
- New `tools/aqueous-solvents/drum-hold/`: `probe-on2.js`, `probe-on3.js`, `probe-on5.js`, `probe-codec.js`, `toggle.js`
  from `tmp/final-lane/fix/` (paths and undo notes changed to the tool folder). Each checked on a copy of the
  `b55053e87` sources: the probe patch applies and every patched site is found.
- Left in `tmp/final-lane/fix/` (scratch): `fix3.js`, `fix4.js` (withdrawn), the applied fix scripts, the logs,
  `held-graph.b64`, `held-world/`, `merge-resolve.js`, `PassiveStepSolver.fix1.java`, the diffs. The batch folder holds
  no script, patch or file over 1 MB.
- Test sources the batch added: 16 test classes, 2 helpers; each runs in `test` or `columnGates`. Nothing to move.
- Found, not changed (`src/`): `SimulatorResults` writes a stream as three slots since `58376e618`, `FORMAT` still 3: a
  result stored by main is misread or fails into the caught branch (dropped). `FORMAT` 4 would drop it cleanly.

Tests the cleanup can affect: none (documents and tools only); no test run is needed.
