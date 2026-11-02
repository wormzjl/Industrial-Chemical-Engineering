# Cooling tower branch review and consolidation

Reviewed `codex/water-cooling-tower` at `a2e9a478e`; the work is on `claude/codex-water-cooling-tower-review-09c170`. The source branch is untouched. Code guide: `cooling-towers.md` updated. Tool folder: `tools/cooling-towers/correlation/`.

## Expected behaviour

A tower is an open basin under a spray. A 16 m² cell fed 86.7 kg/s at 317 K with its fan on leaves about 308 K, evaporates about 1.2 kg/s and rejects about 3.4 MW. A loop must start by itself: the line's gas leaves through the atmospheric inlet, as from a real line. A wrong liquid closes the inlet. One 5 s interval has a few milliseconds per fill, because a fill runs in every Newton residual.

## What the code did, and the change

| # | Found | Measured | Change |
|---|---|---|---|
| 1 | The marched fill cost about 75 ms a call (×18 in target mode) | every island hit `HELD: wall deadline`; a pumped loop stayed held at tick 21 | Direct correlations (below); 60 ms to 0.01 ms; `TowerTransfer` 175 to 64 lines |
| 2 | A loop built with an empty basin deadlocked: seed nitrogen at the suction junction failed the water check | pump closed forever; a plain tank in the same loop primes | The check judges the stream's liquid; a gas-only junction primes through when its feeders give water (`InletPhase.priming`, sharing the `held` walk) |
| 3 | Exact-zero purity failed in any loop: recirculated water keeps a nitrogen trace | `WATER_ONLY` while running | Gas passes to the basin vent; the fill counts liquid water only |
| 4 | A vented trace tripped the 1e-8 gate: a junction omits it, the basin row resolved it | permanent hold at tick 247 | `Equations.kept`: a gas the vent takes whole scales as one the node does not hold |
| 5 | Pumped hydrocarbon held the island: closure set `boundaryClosed` without stopping the pump | `Newton line search stalled` | `closeBoundary` in one rule, `refusedSpray` |
| 6 | One-way ends were a residual row override | no failure reproduced | Moved into `boundaryAllowed` (`againstTower`) |

Every loop scenario now runs at `held=0`: built empty, fan speed 0.1 s for 2947 ticks (was a permanent hold); built full, 0.1 s (was 2.6 s); target mode, 0.1 s (was 11.6 s). Overlapping a campaign, so these times are indicative.

Also consolidated: `Rates` and `Result` lost unused fields (state format 30 is unreleased); one quad builder in `EquipmentGhost`; shared bank limits; unused `ProcessUnit.bar` overload and two language keys removed. Tower tests go from 39 to 30: 12 that repeated others are removed, and the calibration test becomes a correlation table; added are the loop in each fan mode, hydrocarbon closure and air breaks. The six tower rows of the layout table are refreshed for the outlet move the owner directed, and the natural row of `shapes-4.snbt` (unreleased) for the 1.3 height rule: both gates were red on the branch, the second unnoticed because the height commit ran no Gradle tests.

## Correlation against the marched fill (1840 points)

| Class | Outlet RMS / worst, K | Air RMS | Evaporation RMS |
|---|---|---|---|
| Design, 1–3 kg/(s m²), 303–323 K | 0.74 / 2.1 | 10.9% | 15.2% |
| Fans on, 1–5 kg/(s m²), ≥ 303 K | 1.24 / 4.2 | 2.6% | 7.9% |
| Natural, 1–5 kg/(s m²), ≥ 303 K | 2.36 / 6.0 | 19.6% | 11.9% |
| Hot, 338–353 K | 1.95 / 6.0 | 13.4% | 10.8% |
| Dribble, ≤ 0.2 kg/(s m²) | 0.30 / 1.9 | 65% | 26% |

The running loop reads 307.87 K, 3383 kW and 1.158 kg/s against the marched 307.73 K, 3412 kW and 1.203 kg/s. Mass and energy stay conserved: the rates are booked once, and the balance uses the network's own enthalpies. Air errors are worst with the fan off at low load, where the flow is small. References moved: tower outlet temperatures by the amounts above, and fan speed for a set point.

## For the owner

1. **Model form.** Correlation (done): no searches, 0.01 ms, errors above. Merkel rating with one root (`MerkelTowerTransfer.java.txt`): 0.1 K at design, 0.5 K at 80 °C, 0.06 ms, about 70 more lines. Recommendation: keep the correlation unless natural towers at 80 °C matter.
2. **Gas at the spray.** The accepted plan said pure liquid water only. Gas now passes and vents, which is what lets a loop prime. Methane in the water vents the same way.
3. **Certification.** `IslandCertificate.rate()` omits `ATMOSPHERE`, so an evaporating tower never certifies STEADY, and a wet basin never reaches REST (surface time constant about 13 days). One line fixes the first.
4. **Spill and rim.** Water spills at 4/3 m under a 2 m rim, so the rim's clamp is unreachable.
5. **Fan animation.** `motionSeconds*=fanSpeed` scales the absolute phase, so the fan jumps on a speed change. Cosmetic.
6. **Inlet height.** `distributorHeight()` and the snapped nozzle differ up to 0.4 m on natural towers (about 4 kPa).
7. **GUI gates.** No `DeviceColumnTest` case for the tower column. Long labels need a render.

Not run here: the final lane (chain-100, fluid GameTests, campaign), and `verify.ps1` never has been. `equipment_concrete.png` is shared with all equipment. The merge may collide on schema 6, state 30 and protocol numbers with the column operation branch. `tools/cooling-towers/native.init.gradle` is undocumented. No merge, push, version bump or client check.

## Gates

Quick suite 1835 of 1835. Slow gates 187 of 188, overlapping another session: the one failure was wall-deadline holds in `aClosureNothingMovesCarriesAcrossTheIntervalStartInATankDrainingToAVoid`, which has no tower and passes replayed alone.
