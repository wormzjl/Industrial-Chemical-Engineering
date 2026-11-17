# Three-phase separator (2026-11-17)

**Status:** In progress since 2026-11-17 (packages 1 and 2 built and verified; package 4 half built, one owner decision open), on branch `claude/column-testing-sizing-26b5b9`. Class: PHYSICS. Lanes: fluid laws,
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

## Documents

- [SEPARATOR_PLAN.md](SEPARATOR_PLAN.md)

## Open items

- The plan's hold-fuzz campaign on a world with an undersized drum is not run: the harness has no such world. The watch
  seeds ran instead (history).
- Batch `2026-11-16-solute-reaction-system` holds the runtime lane: this batch added one line to `FluidWorldAuthority`
  (the `Separators` hook) and a component to `PassiveNetwork.Inventory`. That batch's plan is not edited from here; its
  desalter (WP6) takes its phase separation from this model and sets its own drop size.
- Owner's calls: whether a kettle and a still's pot take the gas law (plan section 5.1); a free water reflux input for
  the column solver (plan section 5).
- **A moved result, the owner's decision.** `CrudeUnitSampleTest$ColumnSizingSampleTest.theReceiversBootSetsIt`: Auto's
  receiver for the sample crude unit moves from a 4 m by 10 m booted drum (142 m3) to 5 m by 13 m (288 m3), because a
  settling row is not met at 4 by 10. The cause is the drop size, not the code: a 0.1 mm water drop falls 5 mm/s, so it
  needs 400 s through the 2 m of oil in a half-full 4 m drum, and the 5 minute hold gives 300 s. Every drum of 3 m or
  more sized on the hold alone fails the row. Options: (A) keep 0.1 mm, Auto grows large receivers and this reference
  moves; (B) the design drops of separator practice, 0.5 mm of water in oil and 0.2 mm of oil in water (Arnold and
  Stewart, from memory, not checked against the book): about 40 and 9.5 mm/s, a drum sized on its hold is clean, and
  section 3's small-drum examples carry far less (the 0.5 m drum's boot reads about 1.25, clean); (C) keep 0.1 mm and
  rank the rows so they light the lamp without driving Auto's size. The failing test stands on the branch until then.
- The drum page's readings: option A or B of the prototype; then `FluidView.Tank` carries `carried` to the client (a wire
  payload change in the runtime lane, which batch `2026-11-16-solute-reaction-system` holds).
- Package 3 (the held receiver) joins the hydraulics batch's cycle package. The rest of package 4 after the sizer:
  the 330 other classes the selector names for it are not run.
