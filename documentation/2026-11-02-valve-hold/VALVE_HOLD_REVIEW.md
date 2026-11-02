# Valve hold review

Reproduced on `main` in `RegulatingValveHoldTest`. The column review world had held both inlets at the first regulated slice.

## Expected behaviour

Generator, pipe, MASS_FLOW valve, pipe, void at 5 to 2.5 bar passes its target and the valve takes the drop. Steam at 0.75 kg/s
in 0.15 m runs at 21 and 41 m/s, crude at 19.9 kg/s in 0.25 m at 13 and 30 m/s: far under the 100 m/s cap, whose flows are 3.65
and 148 kg/s. A liquid product behaves the same, and a new set point on a running valve moves its flow, nothing else.

## What the code did

The 2:1 choked-ratio hypothesis is refuted: 5 to 4 bar held with the same residual. The stalled residual is `cap - target` at
every target and fluid tried (steam 3.65, nitrogen 5.57, crude 148 kg/s), so the upstream pipe stands on its cap.

1. A flow valve starts open. With a bar across it the line chokes: law flow 5.25 against a cap of 3.65. Over target, the valve
   turns to regulating, but the next pass carried those law flows. The law `q = q_law (1 + (q_law/q_cap)^8)^(-1/8)` is flat
   there, so the Newton goes 5.25, -75.9, 48, slope 1e-11, and stalls at every step size. The valve's head row falls from 37 to
   almost 0 meanwhile, so the line search takes the bad step. Crude passes at 5 kg/s and fails at 10: where it lands is chance.
2. A new target changed the pipe's identity, so a slice read as a new network: the carried point was dropped and the slice
   restarted cold through the open valve. A hot liquid falls to 176 kPa at the junction, under its bubble pressure at 546 K, and
   the liquid layout goes singular. This hid behind 1: no outlet had flowed before.

## What changed

`PassiveStepSolver`, start stage only; no choke law, the cap, supply pressures and line sizes stay.

- A flow valve entering regulation takes its law flows from the balance and keeps its seeds. The balance's seed is the delivered
  mixture: a junction still holding gas cannot be stated as the liquid arriving (tried, singular).
- The balance states the flow it gave a regulated or held valve, not its pipe's unthrottled law. A held valve's start had the
  same defect: pass 0 reached 90.7 kPa with -4.4 kg/s, and `main` escaped it by luck.
- `Identity.line()`: a flow valve's set point is not part of the line for the carried point, carried modes and the cold check.
  Certificates and workspace keys still read the full identity.

## Results

- `RegulatingValveHoldTest`, 4 tests: three fail with the regulation start disabled, one with the set point back in the identity.
- `./gradlew test`: 1807 tests, 0 failures. `slowGates` and the hold campaign not run (no merge asked).
- Review world on a merge of both branches: `RUNNING`, limit none, alpha 1.000 (before: LIMITED: feed, 0 %). Crude 165.8 and steam
  75 kmol/h, condenser -2470 kW, each duty booked as solved.

## Open: outlets still hold later

The outlets now flow for some slices, then hold at pass 0, singular: BOTTOMS at tick 1588, DRAW0 and DRAW1 1673, DRAW2 1734,
DISTILLATE 1754. Sour water and off-gas never hold. Unit two-slice cases pass, so this takes more slices. Lead, not proven: each
held junction holds a component washing out towards nothing (nitrogen 2.7e-8 of 25 mol, others near 5e-10). Left so, the column
fills its accounts and turns to LIMITED: outlet.

Proposal for the owner: a new task traces this from the world's states with `tools/valve-hold-probes`. Whether a fading trace
drops out of a junction is an assumption the physics does not force, so it comes back as options. Code guide: not updated, no merge asked.
