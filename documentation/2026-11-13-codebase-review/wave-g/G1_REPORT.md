# G1 part 1: one health for every machine, wear modes N5 and N1

Branch `claude/review-wave-a3-machines-294ed8`, with `claude/codebase-review-refactor-8a85ef` merged in (`3c8c0dcd5`, clean). Brief: `WAVE_G_BRIEFS.md`, section G1.

Status: three items committed. **No test has run yet**; the lane is held.

| Item | Commit | Files | Tests |
|---|---|---|---|
| 1, M-12 | `396c651ef` | `PumpHealth` renamed `MachineHealth` (`git mv`), `Wear`; renamed callers in `FluidWorldAuthority` (the named lines), `ShaftTrains` (field, setter), `RepairKitItem`, `FluidFactsCompatTest`, `ShaftCouplingTest` | `WearTest.oneRateRuleOverTheFaultTable` (new), `MachineHealthTest` (standing fault), all existing expectations unchanged |
| 2, N5 | `fb398b46d` | `VacuumPump` (`liquidIntensity`, `liquidNoise`), `Wear` | `WearTest.aDryScrewsLiquidWearFollowsItsNoise`, `VacuumPumpTest.theLiquidNoiseKeepsItsBytes` (new) |
| 3, N1 | `b04de810e` | `Wear` (`LOW_FLOW`, `lowFlow`), `MachineHealth` (faults, booking, word) | `WearTest.lowFlowErodesBelowThirtyPercentOfRatedFlow`; `MachineHealthTest`: dead-headed (also at rest), 15 % with cavitation, stopped and set-flow (new) |

## What changed

- **M-12.** `Wear` gains `FaultClass` and `Fault`, each fault with a class and an intensity. One rule: the largest intensity of a class counts, and the two classes add. The bits are unchanged: `rate(sigma, dry, settings)` gives today's two terms in today's order, `0.0/x` where today adds `0`, and `1.0/x` where today has `1/x`. `WearTest` compares the raw bits over a grid.
  - `MachineHealth` reads a device's faults from its control: a liquid mover reads `CAVITATION` and `DRY`, a dry screw reads `LIQUID`. The screw's branch is now a table read.
  - Each device keeps its standing fault and its intensity. They are state, never saved.
  - The status words and their tests, the sound bytes, and the health facts are unchanged.
- **N5.** A dry screw's `LIQUID` intensity is the noise ramp: `liquidIntensity`, 0 up to 1 %, then `liquid / 10 %`, at most 1. `liquidNoise` keeps its bytes.
- **N1.** `LOW_FLOW`, in the erosion class: `((0.3 - q_s) / 0.3)^2`, where `q_s = Q / (Q_r s)`.
  - Premise check: the observation row's `density` is the drawn stream's (`drawn(...).density`), not the suction's. So `Q` is the committed endpoint flow over the suction node's density from `result.graph()`, as in `ShaftTrains.booked`.
  - It applies to a running `Machine` on a `CentrifugalPump` law. Word: `LOW FLOW`, after `LIQUID` and before `CAVITATION`. There is no lang key: the words are raw.

## Values that move

- **N5:** a dry screw's life between 1 % and 10 % liquid by volume goes from 40 min to `40 min x 0.1 / liquid`: 400 min just above 1 %, 80 min at 5 %. Nothing moves at or below 1 % or at or above 10 %. No existing test expectation moves; no test booked a screw in that range.
- **N1:** a centrifugal pump below 30 % of its rated flow now wears: 8 h of process time dead-headed, 32 h at 15 %. Its device page and its shaft part's row say `LOW FLOW`.

## References (read, no run)

- **Campaign:** runs on `EventPathWorld`, which installs no `MachineHealth`, so no pump wears or fails.
- **chain-100 and the junction lines:** solver-level runs (`SolverRegressionHarness`, `PassiveIntervalSolver`), with no health.
- **Fluid GameTests:** the pump casings run for minutes of play. 8 h of process time at factor 4 is 144,000 ticks, past every timeout. `PumpHealthGameTests` checks the word `CAVITATION`; its pump runs at its rated flow into a tank, so `LOW FLOW` should not show. No status assertion on a casing compares the whole line. No reference moves, so no decision request was needed.

## CHANGELOG lines

- The two wear lives in the config now mean the erosion life (`cavitationHours`) and the seizure life (`dryMinutes`) of every machine. Within each class the worst fault counts, and the two classes add.
- A dry screw vacuum pump wears in proportion to the liquid it takes, from 1 % (400 min of process time) to 10 % of its suction volume (40 min, as before). Its liquid noise is unchanged.
- New: a centrifugal pump run below 30 % of its rated flow recirculates and wears: 8 h of process time against a closed discharge, 32 h at 15 %. Its page says LOW FLOW. To fix it, open the discharge, add a minimum-flow recycle, or switch the pump off.

## Not done, outside this lane

- `WorldTopologyLedger` line 292 still names `PumpHealth`, in a line comment.
- The `en_us.json` health hint lists only cavitation and running dry as causes. It has no status key, so it was left alone.

## Lane plan (after "lane free")

1. `compileJava compileTestJava` once.
2. `test --tests` `MachineHealthTest`, `WearTest`, `MoverHealthStatusTest`, `VacuumPumpTest`, `FluidFactsCompatTest`, `ShaftCouplingTest`, `--max-workers 4`. The last two only for the rename.
