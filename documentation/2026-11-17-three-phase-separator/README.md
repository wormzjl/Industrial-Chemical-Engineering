# Three-phase separator (2026-11-17)

**Status:** In progress since 2026-11-17 (packages 1 and 2 of 4 built), on branch `claude/column-testing-sizing-26b5b9`. Class: PHYSICS. Lanes: fluid laws,
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

## Documents

- [SEPARATOR_PLAN.md](SEPARATOR_PLAN.md)

## Open items

- The affected tests are not run: 318 of 523 test classes, 9 hold gates, 9 equipment gates and the solver regression.
  A world whose drums are small for their flows now carries, so a reference there may move. The hold-fuzz campaign on
  a world with an undersized drum (200 seeds, refused 0) is not run either. Both wait for the owner's approval of a
  sub-thread.
- Batch `2026-11-16-solute-reaction-system` holds the runtime lane: this batch added one line to `FluidWorldAuthority`
  (the `Separators` hook) and a component to `PassiveNetwork.Inventory`. That batch's plan is not edited from here; its
  desalter (WP6) takes its phase separation from this model and sets its own drop size.
- Owner's calls: whether a kettle and a still's pot take the gas law (plan section 5.1); a free water reflux input for
  the column solver (plan section 5).
- Package 3 (the held receiver) joins the hydraulics batch's cycle package. Package 4 (the sizer's rows, the drum
  page's readings) is not built.
