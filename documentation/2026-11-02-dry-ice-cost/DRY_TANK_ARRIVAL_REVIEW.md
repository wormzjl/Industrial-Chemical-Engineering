# Dry tank arrival review

Base 2a4590997. The owner chose to fix the arrival into a dry tank before the final lane ("Fix arrival first"): cold seeds 181 and 397 held since the junction tables ([JUNCTION_TABLES_REVIEW.md](JUNCTION_TABLES_REVIEW.md), Campaign). Not run: final lane, whole `slowGates`, GameTests, a full campaign. Code guide, CHANGELOG, version: wait for the owner's word.
## Expected behaviour
A tank that has never held fluid fills with what reaches it. LNG at 112 K let down to the tank's 1 atm is a liquid at its bubble point with a little vapour. The tank states that mixture at the enthalpy it brings (decision D54, the bag at the ambient; D26, its own split).
## What the code did
- The step starts from the rate point of the same interval. In 181 and 397 the feeding junction was stated under 1 atm at the job's start, so the rate solve closed the line to the dry tank, and the carried flow into it was 0. The start stage (`arrivingPhases`) therefore laid nothing out.
- The step's own Newton converged with 0.18 kg/s into the tank (the pipe's law at the rate point: 0.94 kg/s in 181, 2 g/s in 397).
- The booking (`filled`) then stated the delivered LNG with a TP flash at the tank's own 298 K, a vapour, and closed it from there. No closure from that layout reaches a liquid's enthalpy: the tank's temperature ran to 47 K, and every step size stalled at a residual of 0.14 to 1.5, down to 1e-21 s.
## What was built
`PassiveStepSolver`: a converged pass that delivers into a vessel it laid out dry calls the start stage's rule on its own law flows (`arrivingPhases(…, flows, vesselsOnly)`), and the pass is solved again from that layout, its flows carried. The start stage and the pass now state a first arrival the same way, at the enthalpy delivered. `filled` stays for a booking that fills a vessel the flows left under the bag's resolution. One form; no new rule or switch.
## Results
- Nine worlds: every checkpoint and final state unchanged; the pass rule does not run in them.
- 181 and 397: 0 holds (224 and 163 jobs, 12.7 M and 12.1 M checkpoints).
- A scratch form at the start stage (the law where the carried flow is 0) had cleared 181 only: 397's 2 g/s arrival is under the bag's resolution at the start, and the step's pass is where it is known.
## Gates
- New: `HoldClassWorldsTest.lngReachingAnEmptyTankFromAPassIsLaidOutFromWhatArrives`, seed 397's island in 12 placements. With the rule off it holds at tick 2654 on residual 0.155, as the seed did. With it on it keeps up.
- Passing on the final code: 227 tests in 39 classes: the named balance and crystal classes, every test class that names D54 or an empty tank, and `HoldClassWorldsTest`, `JunctionSeedingGateTest`, `ExtremeTopologyIslandTest` and `IslandSchedulerTest`. No expectation changed.
## Recheck of the affected seeds
Cold, 32 seeds under campaign conditions (8 workers): the 11 held, the 17 gained, 183, 555, 673, 768. 23 clean.
- 181, 397 and 792 are now clean.
- 85, 988, 752, 803, 101, 206, 446 and 118 hold as in the campaign.
- One new: 431. It held once, the same at the real and the raised budget, and was clean on the build before. Its first island fills three empty tanks; the pass rule fills the upper one (job 46), where the old code went through the booking. The histories then part, and job 50 stalls 1.8e-11 above convergence at every step size, with a slack tank's iterate at 91.6 K. The island goes on and the world ends FULL. Not traced further.
Bundled, the 8 held or budget seeds alone on one worker: 430, 431, 579, 698 and 241 hold 18, 8, 7, 37 and 13 times, as in the campaign; 6, 157 and 707 are clean. The 8-worker recheck put 431, 698 and 707 on the wall budget: contention among heavy seeds, not counted.
If the other 968 cold seeds stay as they were, cold random is 991 clean. The final lane's campaign decides it.
## Risks
- The pass rule runs at every converged pass with a dry vessel: a scan of its connections, and the flash only where something arrives.
- Warm worlds with a first fill reached only by a pass now state it at the delivered enthalpy, not from 298 K. The final lane's references can move there.
## Open
431's stall at 1.8e-11. 118's equation gate (batch open item 5). Tools: `tools/dry-ice-cost/campaign/` (`COLD_SEEDS`, `BUNDLED_SEEDS`), `carried/hook-t.pl` (the `EMPTY` line). Logs: main checkout, `tools/dry-ice-cost/logs/dry-tank-arrival-logs-2026-11-03.tar.gz`.
