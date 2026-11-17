# Three-phase separator (2026-11-17)

**Status:** In progress since 2026-11-17 (packages 1 and 2 built and verified; package 4's sizer built; the reflux-water trial merged in), on branch `claude/column-testing-sizing-26b5b9`. Class: PHYSICS. Lanes: fluid laws,
fluid step, runtime, plant, flowsheet, client.

A drum of the network gives clean gas, oil and water at any size and flow. The owner (2026-11-17): a drum gives well
separated water and oil only when properly sized, else some oil in the water and some water in the oil; the gas law works
with a vertical drum as well; the desalter is based on this model, with a higher coalescence; salt has nothing to do with
phase carry-over.

## History

- 2026-11-17: the plan, written in batch `2026-11-17-column-internal-hydraulics` as the answer to its option O6 and moved
  here. Ideal settler per liquid layer (`N = v_s A / Q`, drops of 0.1 mm, 1.3 mm in a powered desalter), Souders and
  Brown for the gas in standing and lying vessels, a slice input to `PhaseDraw`, the column's held receiver, the sizer's
  rows. The owner took S1 A, S2 A, S3 A and S4 A. No code.
- 2026-11-17: the owner moved the build here and now ("Do the separator job here"), ahead of the solute batch's WP2.
- 2026-11-17: package 1 (`72476637e`). `Separation` (the settling speed, a layer's settling number, what an outlet
  carries, the gas limit standing and lying), `VesselGeometry.planArea`, `vapourSection`, `lying`. `SeparationTest`,
  6 tests on the plan's numbers.
- 2026-11-17: package 2. The slice input `Inventory.carried`, `Separation.plan` from the last slice's transfers by
  phase, the `Separators` dispatch hook, the carried shares in `StepEquations.phaseRates`. `SeparationDrawTest`, 5
  tests: a vessel carrying nothing draws clean; 0.05 m3 of water per m3 of oil and 0.2 of oil per m3 of water to 0.2 %;
  the gas outlet's bulk share to 0.5 %; every component's ledger to 1e-9; a carried phase stops at what the vessel
  held; a 0.5 m drum fed oil and water carries oil in its water on the next slice and a sized drum stays clean.
  Run: `SeparationTest`, `SeparationDrawTest`, `ProcessOutletTest` (21 tests, 0 failed). Not run: the other 315 test
  classes and 18 gate classes the change can reach (`affected.mjs select`), and the hold-fuzz campaign.
- 2026-11-17: the owner's question on a reflux with water: the column solver takes no free water in its reflux (plan
  section 5). Law 6's simplification stands; a free water reflux input is its own batch.
- 2026-11-17: package 2 verified by a sub-thread on `24e9867b7` (no source changed). The selector's classes: 2145 tests
  (test 318 classes, hold gates, equipment gates), 1 failure, a wall-deadline hold of `BlanketDrainWorldTest` under machine
  load that passed its one replay alone with the baseline's lines; chain-100 exact, deviation 0; the 27 watch seeds,
  `refused=0`, no new hold (seed 241 moved E6 to F under load and returned to E6 alone). No reference moved.
- 2026-11-17: package 4, the sizer (`7bc29657d`). `Separation.numbers`; `DrumSizing.Load`
  carries the liquids' densities and viscosities; rows `DRUM_SETTLE_WATER` and `DRUM_SETTLE_OIL` (SHOULD, at least 1) read a
  booted drum at design flow, half full of oil over a full boot; the gas rows read `Separation.GAS_K`. `DrumSettlingTest`
  (4). Of the 50 flowsheet and sizing classes the selector names, 510 tests ran and 1 failed: a moved result (open items).
- 2026-11-17: the drum page's readings, prototype only: `GuiPrototypes.separator`, two sheets (A, three meters at the end
  of Readings; B, a Carry-over group of their own), and their English lines. Not built: the owner picks.
- 2026-11-17: the trial (owner: "try the reflux with water"), on branch `claude/rust-archive-reflux-water-762f04`:
  [REFLUX_WATER_REVIEW.md](REFLUX_WATER_REVIEW.md). `V3ColumnInput#refluxFreeWaterMolPerSecond` (`R_w`, default 0) is a
  liquid water feed to tray one; the drum returns it from the water that arrives and decants the rest. It converges at
  0, 8 % and 26 % of the decanted water on the medium crude wet column and the review plant, and the audit passes.
  Finding: with the reboiler duty fixed the condenser duty does not rise (within 0.03 %); the distillate falls 0.5 to
  2.3 %. `V3RefluxFreeWaterTest` (5 tests), `tools/column-hydraulics/RefluxWaterProbe.java`. Open: the owner's choice
  between E31 carry-over and falling water for the returned water (review section 4, options A and B). Tests:
  `affected.mjs select --since c6f60e2aa..HEAD`, run as the lane's test step builds it (`-PlaneTests`,
  `left-out.txt` honoured, `--max-workers=4`): `test` 153 classes (994 tests), `holdGates` 6 (59), `columnGates` 69
  (381), `equipmentGates` 7 (64). One failure, `MaterialPresetsTest.allFourInputsRemainTheQualificationInputs`: it
  compares the record's fields, through Gson, with the captured fixture `materials/column-preset-inputs.json`, which
  lacked the new component. The fixture gains `"refluxFreeWaterMolPerSecond": 0.0` on its four inputs (no value
  moves; only this test reads it), and the class then passes (6 tests). No production code serialises the record.
  No other reference moved: every column gate that loads the changed classes passes as recorded.
- 2026-11-17: the top tray under returned water. The owner chose "Fall to tray 2" (option B). It was tried and
  rejected: on the review plant tray one could not hold free water (d ln(ratio)/dF of 3e-8 per kmol/h at 8 %, 1.2e-8
  to 2.0e-8 at 26 %). The solver published it dry and supersaturated, 2.4 K and 12.3 K below its water dew point.
  Without a liquid route out of the top there is no wet steady state. The owner then decided A: "Keep the carry-over".
  `7bfbc3e42` stands, and B is not committed. Option C gives the same water state as B; option D (a top-temperature
  margin above the dew point, about 14 K) is open and not taken. Law 6's note in the plan is corrected: the cost is
  lost distillate and a cooler tray one, not condenser duty. Documents only; no test run.
- 2026-11-17: branch `claude/rust-archive-reflux-water-762f04` merged into `claude/column-testing-sizing-26b5b9` (owner:
  "Yes, merge it"). One conflict, in this file; both sides kept. On the merged tree: it compiles, and
  `V3RefluxFreeWaterTest` (5) and `DrumSettlingTest` (4) pass. Not run on the merged tree: the classes that load both
  package 4's sizer and the trial's column classes; each side passed them apart.
- 2026-11-17: the drops (owner: "B: 0.5 / 0.2 mm"). `Separation.DROPS`: a water drop of 0.5 mm in the oil (43.7 mm/s
  in the review plant's naphtha), an oil drop of 0.2 mm in the water (10.4 mm/s); `Separation.Drops` replaces the one
  drop size in `numbers` and `of`. Moved: the plan's section 3 table (the 0.5 m drum is now clean; the examples that
  carry are a 1 m drum at 4 times the flow and the sized drum at 16 and 128 times), `SeparationTest` and
  `DrumSettlingTest` with it. Returned to its value before package 4: Auto's receiver of the sample crude unit, 4 m by
  10 m (`CrudeUnitSampleTest` passes unchanged). Run on the merged tree: the 50 flowsheet and sizing classes of
  package 4's selection with `SeparationTest`, `SeparationDrawTest` and `ProcessOutletTest`, 56 classes, 531 tests,
  0 failed. Not run: the other classes that load `Separation`. A larger drop only raises a settling number, so a
  vessel that carried nothing at 0.1 mm (package 2's verification: no reference moved) carries nothing now.
- 2026-11-17: the drum page's Carry-over group (owner: "B: own group"), the column's part. `DeviceColumn.Input`
  carries a vessel's carry-over meters (`withCarried`; empty for any other device), `rows` puts them in a group right
  under Readings, `DeviceMeters.carried` builds them in percent by volume, one for each outlet the law acts on.
  `GuiPrototypes.separator` draws the shipped builder (a lying drum with a boot; a standing drum, the gas's meter
  alone); both sheets looked at. Run: the 77 classes `affected.mjs select --since` names for the commit, without
  `LanguageFilesTest` (500 tests, 0 failed). No payload changes: the screen does not set the meters yet (open items).
- 2026-11-17: the owner: "delegate all implementation to opus". From here this session plans, briefs and reviews.
- 2026-11-17: the Carry-over group's data (`bd16ff83c`, an Opus sub-thread), protocol `icheme-7`. `Separation.acts`
  (which laws act on a vessel; `plan` uses the same rule), `FluidView.CarryOver` on `FluidView.Tank.carried` (a copy
  of the inventory's slice input, null where no law acts), `TankOutlets.tank(model, graph, node, result)` from
  `ViewBuilder`, `DeviceMeters.carried()` set by `FluidDeviceScreen`; `NetworkProtocol` and `FORMATS.md` at
  `icheme-7`; three version asserts follow the number. Run: the classes `affected.mjs select` names, without
  `LanguageFilesTest` and `UnitTextTest`: `test` 375 classes 2215 tests, `holdGates` 12 classes 92 tests,
  `equipmentGates` 22 classes 180 tests, 0 failed; `columnGates` (`V3RefluxFreeWaterTest`) up to date, its stored
  pass stands. No result moved. Both separator sheets rendered from the shipped builder and looked at.
- 2026-11-17: the owner asked for a gas-liquid sizing requirement on a standing drum, then: "This is not only about
  setting rule, but what happens if rules are not followed", and "add an optional demister". Proposed, not built (open
  items): a height factor on a standing vessel's gas limit, and a mesh pad as a design option. Documents only.
- 2026-11-17: the owner on the order of the two branches: "this branch will land first so solute worktree needs to
  incorporate work here". The files batch `2026-11-16-solute-reaction-system` changed no longer block this branch;
  that batch merges this one and resolves the conflicts (five files in a trial merge today). Documents only.
- 2026-11-17: the gas space law (`e394260ab`, an Opus sub-thread; owner: "what happens if rules are not followed").
  `Separation.GAS_SPACE` 1.524 m, `GAS_SPACE_EXPONENT` 0.56, `gasSpaceFactor`, `gasLimit(geometry, level, ...)`: a
  standing vessel's gas limit times (gas space / 1.524 m)^0.56, at most 1; a liquid-full standing vessel's gas outlet
  draws the bulk. A lying drum is unchanged bit for bit. `DrumSizing`'s gas rows read the world's law at half full:
  0.607 for a 0.5 m by 1 m drum, 0.844 for 0.5 by 2, 0.895 for 1 by 2, 1 from 3 m long up (the brief's 0.608 and
  0.845 were rounded; the hand values are these). Tests: `SeparationTest` +1, `DrumSettlingTest` +1. Run:
  `affected.mjs select --since 7e3ac017e`, without `LanguageFilesTest`, `UnitTextTest` and the
  `fluidSolverRegression`, GameTest and campaign steps: `test` 334 classes, 2064 tests, 1 failure (the new test's
  0.845, corrected; the class then passed, 5 tests); `holdGates` 13 classes, 95 tests, 0 failed; `columnGates` and
  `equipmentGates` up to date. No reference moved: every other selected test passed as recorded
  (`UnitSizingTest.aDrumTakesItsTypesLeastVolume`, 1.5 m by 7 m, among them). Not run: the steps named above.
- 2026-11-17: the demister (`4fd766c56`, the same sub-thread; owner: "add an optional demister"). A drum may carry a mesh
  pad: `VesselGeometry.demister` (a drum only), `Separation.DEMISTER` = 2 inside the one `gasLimit` formula (GPSA's
  0.107 m/s; the gas space factor still applies); `EquipmentShape.demister` (`Kind.DRUM` only, no cell); the
  constructor's design and the shell (tag `Demister`, written only when true); the constructor payload (`icheme-7`
  kept); fluid facts schema 30 and state format 53 append it to a vessel geometry; corpus images `compat/fluid/schema-30`
  and `compat/equipment/shapes-14`; `FORMATS.md` rows. The constructor's drum page (Nozzles) gains a Demister group
  (Off, On, latched; standing and lying); the datasheet a Demister line; `DrumSizing.Size.demister` (Auto's lists stay
  pad-free) with a Demister key on the Sizing page for a Fixed drum size, saved by `SizingFactsFile` only when true
  (the sheet file's row, format 2, no new number). Sheets `equipment-constructor-nozzles` (no pad),
  `-nozzles-demister` and `-nozzles-lying-demister` rendered and looked at. Run: `affected.mjs select --since
  1bb9f9b18`, same exclusions: `test` 409 classes, 2411 tests, 3 failures; `holdGates` 13 classes, 95 tests, 0 failed;
  `equipmentGates` 22 classes, 182 tests, 0 failed (`EquipmentLayoutGoldenTest` unchanged); `columnGates` up to date.
  The failures: `FormatsTableTest` read the table before its rows were updated, and `FluidCheckpointCodecTest`'s byte
  budget (moved: five devices' facts 2,085 to 2,091 bytes, one byte a vessel geometry; bound 2,090 to 2,095, as every
  schema step has done); both passed on their rerun (1 and 16 tests). `SizingPageTest.theDoorFitsInEveryViewForEveryItemInChinese`
  fails until the translation commit: the two new Sizing-page keys have no Chinese line (`zh_cn.json` is not edited
  during a batch). Moved, test only: `ConstructorColumnTest`'s Nozzles slot list gains `demister:off`, `demister:on`;
  `EquipmentNetworkCodecTest`'s unknown-material case steps over the payload's new last byte. No sizing reference and
  no Auto proposal moved.

## Documents

- [SEPARATOR_PLAN.md](SEPARATOR_PLAN.md)
- [REFLUX_WATER_REVIEW.md](REFLUX_WATER_REVIEW.md): the trial of a reflux that returns free water to tray one

## Open items

- The plan's hold-fuzz campaign on a world with an undersized drum is not run: the harness has no such world. The watch
  seeds ran instead (history).
- Option D of `REFLUX_WATER_REVIEW.md` (a top-temperature margin above the water dew point as a sizing or operating
  advisory): kept for later (owner, 2026-11-17: "Keep for later"). To build once top-tray water has a consequence in
  the game; the margin of about 14 K is not checked against a source.
- The returned water's production form: the cycle's input, the ratio form, the mode with Water on the basis, the
  sizing and editor rebuilds that drop it, tray one's liquid load (`REFLUX_WATER_REVIEW.md`, section 5).
- Batch `2026-11-16-solute-reaction-system` holds the runtime lane: this batch added one line to `FluidWorldAuthority`
  (the `Separators` hook) and a component to `PassiveNetwork.Inventory`. That batch's plan is not edited from here; its
  desalter (WP6) takes its phase separation from this model and sets its own drop size.
- Owner's call: whether a kettle and a still's pot take the gas law (plan section 5.1).
- Gas space height: built (history). Left: the two assumptions (the gas space is half API 12J's vessel height; the
  factor is extrapolated under 5 ft) are not checked against a primary copy of the table; the law reaches every
  standing closed vessel (spheres, closed cylinders, prisms), not drums alone.
- Demister: built (history), with the owner's defaults: no pressure drop (about 0.1 to 0.25 kPa, from memory; it
  matters only in vacuum service), no fouling, Auto never proposes one. Left: the translation commit gives the
  Sizing page's two new keys their Chinese lines; batch `2026-11-16-solute-reaction-system` takes facts schema 31,
  state format 54 and `icheme-8` when it merges this branch (`main`, `6e1f94195`).
- The Carry-over group's data is built (`icheme-7`, history). Left: the selector names `fluidSolverRegression`, the
  GameTest step and the campaign step for that commit; none ran (they are the final lane's). `FluidDeviceScreen`,
  `NetworkProtocol` and `FORMATS.md` have one-line edits that batch `2026-11-16-solute-reaction-system` (`icheme-6`)
  also changes: the second batch to merge takes the next number.
- Package 3 (the held receiver) joins the hydraulics batch's cycle package. The rest of package 4 after the sizer:
  the 330 other classes the selector names for it are not run (history: the drops).
