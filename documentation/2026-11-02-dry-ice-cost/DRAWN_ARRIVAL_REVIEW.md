# Drawn arrival review

Base 1bf1a1334. The owner chose proposal A of [FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md) ("Build A") for bundled seed 301, then a stop before the merge. Code guide, CHANGELOG, version and merge wait for the owner's word.
## Expected behaviour
A full tank of 357 K water overflows at 0.55 kg/s up a 1 cm line into a dry tank at 1 atm. Water at 357 K and 1 atm is subcooled, so the dry tank fills with liquid water. A nitrogen bubble of 1.2e-22 m3 at the full tank's top port carries nothing measurable.
## What the code did
The pass rule (`DRY_TANK_ARRIVAL_REVIEW.md`) priced each delivery at the leading phase of the donor's port at a vanishing flow. Here that phase was the bubble. So 77 g of water was laid out as 0.081 m3 of nitrogen vapour. The next pass delivered water into a vapour-only layout and ran the tank to the 10 K floor (16 holds).
## What was built
`PassiveStepSolver.arrivingPhases` takes the pass's phase draws (`Equations.draws`, decision D11). These are the phase rates the reconstruction books.
- A connection the pass drew phases on delivers its drawn stream (`drawnStream`). Each phase's fluid amounts per kilogram and its specific enthalpy are weighted by the phase's mass flow. Each is read on the state the booking reads: the end state, or the start state for a pinned phase.
- The start stage has no draws, so it keeps the end stream at a vanishing flow.
- `endMoles`, `endMass` and `endSpecificEnthalpy` now call per-phase helpers (`fluidMoles`, `fluidMass`, `fluidSpecificEnthalpy`), which the drawn stream shares. Their arithmetic is unchanged.
- One rule; no switch or threshold.
## Results
- Seed 301 alone: 0 holds, 778 ms (16 holds before; 847 ms with the arrival rule off).
- Campaign, 1000 seeds, 8 workers, alone on the machine, `refused=0` everywhere:

| Set | Clean | Lane | Held, or gone against the lane |
|---|---|---|---|
| Cold random | 992 | 991 | 431 gone. 85, 988 (E1); 752, 803 (E5); 101, 206, 446 (E6); 118 (F) |
| Bundled random | 995 | 993 | 301 and 6 gone. 430, 579 (E10); 431 (E6); 698 (E8); 241 (F, held in every campaign) |
| Bundled defaults | 1000 | 1000 | |

  No new held seed and no new budget class, so nothing to replay. Seed time: cold 1301 s (lane 1378 s), bundled 329 s (361 s).
- The nine worlds, 181 and 397: 0 holds, seed times within 0.5 to 1.1x of the lane's.
## Gates
On the final code:
- 23 `test` classes that place dry or empty vessels or reach the arrival: 119 tests.
- `holdGates`: `HoldClassWorldsTest`, `JunctionSeedingGateTest`, `ExtremeTopologyIslandTest`, `IslandSchedulerTest`; 67 tests.
- The fluid GameTests: 39 of 39.

All pass and no expectation changed. No new gate: seed 301's world needs 23 placements and a compressor, and the campaign holds it.
## Risks
- The start stage still prices a first fill at a port's leading phase. A donor with a trace phase can mislead it, as it did the pass. No campaign seed shows this.
- The lane's printed lines were not read again. The rule changes only passes that lay out a dry vessel, and the gates that print them pass.
## Logs
Main checkout, `tools/dry-ice-cost/logs/drawn-arrival-logs-2026-11-03.tar.gz`.
