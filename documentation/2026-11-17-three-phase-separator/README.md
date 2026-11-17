# Three-phase separator (2026-11-17)

**Status:** Planned since 2026-11-17, on branch `claude/column-testing-sizing-26b5b9`. Class: PHYSICS. Lanes: fluid laws,
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

## Documents

- [SEPARATOR_PLAN.md](SEPARATOR_PLAN.md)

## Open items

- The build starts after batch `2026-11-16-solute-reaction-system`'s WP2 and before its desalter (WP6), which takes its
  phase separation from this model. That batch's plan is not edited from here.
- Law 6's simplification (the reflux's carried water) is confirmed with the owner when its package starts.
