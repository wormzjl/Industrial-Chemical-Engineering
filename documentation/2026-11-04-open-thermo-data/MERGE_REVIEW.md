# Merge review: 0.23.0

Branch `claude/refinery-compounds-thermos-252973`, batches `2026-11-04-refinery-compounds`, `2026-11-04-open-thermo-data` and `2026-11-05-neural-retrain` (fast-forwarded in at `d54fe0fa0`). The owner, 2026-11-08 (E35): "Merge main in (Recommended)", not a rebase, so the cited commit ids stay valid; version 0.23.0; subagents for the translations and the code guide (Opus, high).

## The merge of main and its follow-ups

- Main `b794e2d41` (0.22.0) merged in `0d0499f11`: 13 conflicts, both sides' rules kept. Gate lists united; `FluidCheckpointCodec.VERSION` 40 (a state format); fluid facts schema main's 14; indexes and CHANGELOG row by row. `FluidFactsCompatTest` and `EquipmentShapeCompatTest` pass on the merged code.
- `fcde043f3`: 21 re-pins for main's D42 (a pump's shaft power heats its discharge: the crude reaches E-101 0.08 K warmer, E-101 569 m², H-101 73.06 MW), for Auto's single crossflow condenser shell, and `PumpRateWarmStartTest`'s source at 150 kPa (main's NPSH3 floor).
- E36 (`6e5c4efae`, [COLD_FURNACE_STALL_REVIEW.md](COLD_FURNACE_STALL_REVIEW.md)): the cold furnace pin failed in V3's 30-stage rung. Form S2 releases a free point that sank below its floor to the support refresh: furnace 7 of 7 duties, medium grid 10/10 (8/10), polls 2,715k (5,904k), roots unchanged. Runs: columnGates v3 541/541 after the resume test moved to steam ×1.5, holdGates 64/64, `FlowsheetExamplesTest` 6/6.
- Tooling cleanup `f4130065f`: `main-reach.pl` reaches 671 of 671 main classes; `MaterialCatalog.freezingPoint`, `thermochemistry` and `flammability` (test callers only) moved to the test helper `SubRecords`. Tool folders: `tools/compound-import/`, `tools/package-rework/`, `tools/crude-grades/`, `tools/open-thermo-data/` (E36's probes in `e36/`), `tools/neural-retrain/`.
- Translations `2cd2a4a5d` (40 keys added, 56 removed in each of zh_cn, fr_fr, es_es, ja_jp). Code guide `06052fc6e` (eleven chapters at `f4130065f`; column-v3 and README at `6e5c4efae`). `mod_version` 0.23.0 and the CHANGELOG lines: `e079e9688`. MATERIALS.md brought to the 0.23.0 data (W8); no test reads it.

## Final lane (`e079e9688`, 2026-11-09)

`LANE_OUT=tmp/final-lane bash tools/merge-lane/lane.sh head`. Main's baseline: the simulator-sizing head lane at `891c8acf5` (archive `tools/simulator-sizing/logs/simulator-sizing-final-logs-2026-11-07.tar.gz`). Three PlateTectonics jobs used about 4.5 of the 16 cores during the gates and the campaign.

- `test slowGates`: 2978 tests, 5 failures.
  - Ours, fixed in `5daeda5f3`: `DeterministicMathSourceTest` (`TrainedPhysics` called `Math.exp` and `Math.pow`) and `MaterialPresetsTest` (the example datapack lacked O9's sub-records). Re-run: those 2 classes, `GroupContributionInteractionsTest`, and columnGates `V3BundledModelTest`, `V3NeuralRegistryTest`, `CrystalPackageConsumersTest` (21/21): all pass.
  - Main's, the same on `b794e2d41` (scratch worktree, removed): `PaintTest` (`SERVICE_CASE` is body-painted since `616fe7a1c`, after main's lane), `ProcessUnitTest.everyShaftEndMeetsAFreeBlockForItsShaft` (the generator's terminal box at its shaft end) and `.everyNozzleNeckAtItsLargestStandsClear` (two drums).
- chain-100 exact: 0 deviation.
- Fluid GameTests: 2 of 73 fail, the same on main: `aColumnRunsInItsPlantStopsAndLeaves` ("No room for the PUMP's creative motor"), then `registeredPipesAndReservoirsKeepSimulatingAcrossUnloadedChunks`, which waits for the first test's plant to be cleaned up.
- Junction lines: integer counters identical on 29 of 33; three lines differ by one Newton solve; `MIXED_GAS_COST` 300 solves (299) and 163 MB (94 MB; the 45-compound world list gave 161 MB at V5).
- Campaign, 1000 seeds at 8 workers, against main:
  - Cold random 992 (992) and defaults 1000 (1000): the same hold sets; Newton solves +0.1 % and +0.4 %.
  - Bundled random 989 (993), 990 after the replay; Newton solves 183.8k (166.7k, +10 %). Replayed alone: 678 clean (load), 431 its baseline class E6, 941 still F (wall deadline). Seed 6 (F on main) is clean.
  - New holds: 514, E9 (a reservoir holds 6.7e-322 kg of nitrogen; the Wilke sum underflows to 0 and `MixtureViscosity.vapor` refuses 20 trials); 696 and 200, E5 (line search stalls; 696 pumps methane gas, which D42 heats from 304 K to 396 K); 941, F.
- `git ls-files -i -c --exclude-standard`: empty.

## Open for the owner

- S2 (E36): validate, or revert it (one commit).
- Main's five failures: fix them on this branch before the merge, or leave them to a main follow-up.
- Seed 514's underflow: (A) mix on mole fractions always (every vapour viscosity moves in its last digits: chain-100 and the junction lines re-record, one campaign); (B) form mole fractions only when the amounts underflow (bit-identical elsewhere); (C) leave it held.
- The bundled-random cost and holds (+10 % solves; seeds 200, 696, 941): accept, or trace further.
