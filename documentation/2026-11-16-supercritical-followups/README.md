# Supercritical follow-ups

Status: **Implemented** 2026-11-16 (0.34.1): packages P1 to P4, the merge preparation, one final lane and the lane's two fixes. Branch `claude/supercritical-fluid-support-d0ffa4`, base `dfbdc583c` (main 0.34.0).

Purpose: the open owner items of [2026-11-15-supercritical-fluids](../2026-11-15-supercritical-fluids/README.md), on the
owner's "fix them": the plunger pump's clearance loss and work, one dense fluid in the level, sight glass, release,
line service and device pages, the column's feed flash by a stability test, the plunger pump's own sound.

## History

- 2026-11-16: the plan, by the main thread: 17 items sorted (13 fixed in four packages, two kept as they are with the
  reason, one offered, one outside the list). No code changed. [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md).
- 2026-11-16: P3 (items 14, 15), branch `claude/hungry-nash-656c11`: the feed flash by a stability test, form D (F3 with
  the vapour rule; main thread decision, open to the owner's veto). Dense grid: 10,602 answered, 0 refused (F1 898, F2
  891), 0 wrong one-phase, moved only the 181; column vs engine splits within 9.4e-11 in ln K; crude unchanged. Filtered
  run: 12 classes, 34 tests, 0 failures. [P3_COLUMN_FEED_REVIEW.md](P3_COLUMN_FEED_REVIEW.md).
- 2026-11-16: P3 tightening (main thread): rule D reads the engine's split by the engine's own lighter phase, not on the
  forced vapour root (CO2/pc03 at 220 K was split as vapour-liquid, now one liquid as before); dense grid unchanged;
  12 classes, 35 tests, 0 failures. [P3_COLUMN_FEED_REVIEW.md](P3_COLUMN_FEED_REVIEW.md).
- 2026-11-16: P1, sub-thread (branch `claude/gallant-leavitt-f29f1e`): the plunger pump's clearance loss and isentropic work
  on the suction's isentropic exponent (`NetworkStreams.isentropicExponent`, `suctionKappa`), the run and sizing of a
  plunger-typed Pump tile (`Passages.pumpType`). Six reference rows within 0.35 % of η_v. Stop decisions: exponent by
  differences (1 A), a pump with no type runs centrifugal (2, narrowed), the view and transport read `suctionKappa` (3).
  [P1_PLUNGER_REVIEW.md](P1_PLUNGER_REVIEW.md).
- 2026-11-16: P4, sub-thread: the plunger pump's own loop `plunger_pump_running` (candidate A of three, valve knocks over a 15 Hz ripple and a crank and gearbox rumble, 300 rpm, 4 s, RMS 0.0918, floor 13.4 dB down), picked by `MoverSounds.casingLoop`; pitch law and clamp kept; subtitle reuses `pump_running`, no lang change. Filtered run: 5 classes, 27 tests, 0 failures. [P4_SOUND_REVIEW.md](P4_SOUND_REVIEW.md).
- 2026-11-16: P2, sub-thread (branch `claude/quirky-yonath-d0c746`), without the wire field (point 1, item 6 open): one dense fluid reads by its liquid-like weight `w` (smoothstep of δ 0.5 to 1) in the level (`v_vap` counts `w`; CO2 at 310 K reads 0, 0.104, 0.648, 1, 1 at δ 0.4, 0.6, 0.8, 1.0005, 1.6), the sight glass, the burst (split by `w`, the volatile share heard as gas: dense CO2 hisses, a dense heavy cut spills), the simulator's line service (liquid rows above δ 0.5), the pump's head share on a two-phase supply, a per-row hover on the limits table (suction density), the "Dense" colour blended by `w`, and the Model box's capacity. Server reads `w` from the island snapshot (`FluidWorldAuthority.denseWeight`), no solve. One assertion moved (head share 59.6 % at α 0.896). Filtered runs: 179 classes, 1,375 tests, 0 failures. [P2_READINGS_REVIEW.md](P2_READINGS_REVIEW.md).
- 2026-11-16: merge preparation, sub-thread (branch `claude/jolly-jang-846fbd`): `main` merged in (`adb413b4d`, one
  FORMATS.md reservation row); the CHANGELOG entry under [Unreleased], no version heading; the translation commit (two keys
  in zh_cn, fr_fr, es_es, ja_jp; `check.js` OK, 4,694 keys each); the code guide checked against the code (16 stale line
  counts, three history notes removed, 15 snapshot lines to `410c37fcb`); FORMATS.md: no row; `main-reach.pl` names no
  class of this batch; the `tools/INDEX.md` rows of `supercritical-probes/` and `machine-sounds/` name the batch. One run,
  the lang commit's selection (`affected.mjs select --since 132614789..e1446cfba`): `test` 69 classes, 482 tests;
  `holdGates` 8 classes, 57 tests; `columnGates` 1 class, 2 tests (from cache); `equipmentGates` `UnitTextTest`, 1 test;
  0 failures. `LanguageFilesTest` among them.
- 2026-11-16: the final lane, sub-thread, once at `9b1dd2bd6` (`lane.sh head`): **not clean**. 3,964 tests, 2 failed
  (`DeterministicMathSourceTest`: `PlungerPump` calls `Math.log1p`/`expm1`; `ColumnWaterTest.aLiquidThatWouldSettleIsANote`:
  the feed flash refuses a wet unstable feed); `EngineDigestTest` passes on its reference; chain-100 exact; GameTests 114 of
  114; junction lines 33 of 33 identical; campaign 0 refusals, four new wall-deadline holds (cold 6, bundled 47, 59, 707)
  all clean on their one replay; 8 unannounced moved results (the W5 aqueous sheet STEADY → FAILED, three ethanol flows or
  shares in the 4th to 5th digit, one packed size, three allocation counters). [FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md).
- 2026-11-16: the lane fixes, sub-thread (branch `claude/confident-elion-faef91`): `PlungerPump` on `DeterministicMath`
  (no value can move); the feed flash reads the kind of instability first (rule B, the main thread's choice on the stop:
  a liquid trial gave a wet 320 K distillate 10 % "vapour" with 39 % water) and never refuses at the Wilson endpoint; the
  `TWO_LIQUIDS` refusal is gone. Grids: dense and coarse equal to form D to the bit, the 207-state dry scan equal to the
  lane, the 570-state wet grid 0 frozen answers refused and 0 frozen splits one phase. The hexane column and the W5 sheet
  back to 0.34.0 (STEADY, two warnings); the three wash moves are the feed's small vapour fraction (β 7.67e-5); the packed
  size moves with stored wall-clock seconds. Runs: 167 classes, 1,437 tests, one rerun class, and the 44 column-gate classes
  once more on the owner's word to read their printed lines (262 tests, equal to the lane's), 0 failures on the final code.
  [LANE_FIXES_REVIEW.md](LANE_FIXES_REVIEW.md).
- 2026-11-16: the owner's decisions after the packages, and the merge, by the main thread. "Approve all" (merge preparation,
  the final lane, version 0.34.1); "auto merge" (merge without a further question on a clean lane); the lane was not clean
  (2 failures, 8 unannounced moved results), so the owner was asked: "Approve, then merge" (the fix thread, then the merge if
  the tests the fixes reach pass and nothing else moves unannounced). The fixes' reached tests pass (1,437 tests, 0 failures
  on the final code); of the 36 classes that print, only the expected lines differ from the lane's. Not compared: the printed
  lines of 44 column-gate classes (their result files were replaced by a filtered run; all 44 passed). The main thread's
  judgment: they cannot have moved, because the lane's column-gate lines equalled the 0.34.0 baseline and rule B changes the
  lane's flash only where it returns to the 0.34.0 answer. No second lane, GameTest or campaign ran after the fixes: the
  plunger substitution is bit for bit (`Math.log1p` and `expm1` are `StrictMath`'s) and the column fix does not reach the
  fluid network. Version 0.34.1 (the CHANGELOG heading, `mod_version`, this status, the index row) and the merge to `main`.
  Afterwards the fix thread ran the 44 classes once more (262 tests, 0 failures): their printed lines equal the lane's and
  0.34.0's line for line (`dffd7a19b`).

## Documents

- [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md): the items, the common rules, packages P1 to P4, the order.
- [P3_COLUMN_FEED_REVIEW.md](P3_COLUMN_FEED_REVIEW.md): P3, the feed flash by a stability test: forms, moved answers, the two-liquid limit.
- [P1_PLUNGER_REVIEW.md](P1_PLUNGER_REVIEW.md): P1, the plunger pump's clearance and work: reference rows, moved values, runs.
- [P4_SOUND_REVIEW.md](P4_SOUND_REVIEW.md): P4, the plunger pump's own sound: the real machine, the recipe, levels against the skill, the candidates.
- [P2_READINGS_REVIEW.md](P2_READINGS_REVIEW.md): P2, one dense fluid in the level, sight glass, burst, line service and device pages: carbon dioxide at 310 K before and after, each point, runs.
- [FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md): the final lane at `9b1dd2bd6`: selection, failures, moved results, campaign, GameTests.
- [LANE_FIXES_REVIEW.md](LANE_FIXES_REVIEW.md): the two failures fixed, rule B, the wet grid, the three moves, the printed lines.

## Code areas

- P1: `science/machine/PlungerPump` (and `PositiveDisplacement.share`), `NetworkStreams.isentropicExponent`, the solve's `suctionKappa` (read by `ConservativeTransport` and `ViewBuilder` too), the simulator's `MoverUnit`, `PumpSizing`, `Passages` and `SizedPassages`.
- P2: `runtime/fluid/DenseReadings` (new), `FluidWorldAuthority.denseWeight` and `releaseContents`, `ViewBuilder.denseWeight`, `FluidView.Tank.level`, `PipePresentation.sightGlass`, `Displays`, `Releases.volatileMoles`; the level readers `BuiltInInputs`, `ControlLaw`, `Sensor`, `InstrumentService`; `Service`; `client/gui/common` `DenseLabel`, `DeviceMeters`, `DeviceColumn`, `FluidDeviceScreen`; `client/gui/flowsheet` `StreamProps`, `Door`, `SizingPage`, `SizingGlass`, `SimulatorPage`; `en_us.json` (two keys); `tools/heat-gui-renders/GuiPrototypes` (the two-phase pump page). No `network` change.
- P3: `science/column/v3/thermo` (`V3FeedFlash`, new `V3FeedEngine`, the PR session and package), `build.gradle`'s column gate group.
- P4: `client/MoverSounds.casingLoop`, `ModBlocks`, `sounds.json`, `plunger_pump_running.ogg`, `tools/machine-sounds/plunger_candidates.py`.

## Open items

- Item 6, the client's catalog on a datapack server: the "Dense" label, its colour and the head share follow the client's data. The owner answered "Not now" to the reservation of protocol `icheme-4`, then "Yes, run P2" without the wire field.
- P3, for the owner: a feed that splits into two hydrocarbon liquids is answered as one liquid at the Wilson endpoint
  (about 1.1 kJ/mol). Closed by batch 2026-11-16-cdu17-fixture-removal: the cold Tia Juana crude's heavy-cut second liquid
  was the test-only cdu17 package's extrapolated PC12; the package is removed and the medium crude is one liquid when cold.
- Lane fixes: at the Wilson endpoint a failed restart keeps the endpoint's liquid (the hexane column's feed at 345 K and
  1 atm, while the same feed at 100 kPa splits β 0.336), and Wilson's K put some wet feeds whose own root is a vapour at the
  liquid endpoint; a wet feed towards a vapour that substitution does not converge stays refused off the endpoint (the
  engine takes no water).
- P2: a non-dense liquid that flashes on release (liquid propane) is still heard as a liquid spill (an owner option).
- An in-game check of the plunger pump (constructor, model, sound) and of the dense readings, offered at the end.
- Kept, open to the owner's veto: Auto's rule (item 2); "Dense" in the aqueous slot (item 13).
- zh_cn: seven older lines whose English changed only in case or wording (`gui.icheme.vessel.port.*`, `fluid.throughput`,
  `fluid.net_flow`, the natural draft tower) keep their translations, which still fit; `stale-translations.js` lists them.
