# Cooling tower branch review and consolidation

Reviewed `codex/water-cooling-tower` at `a2e9a478e`; the work is on `claude/codex-water-cooling-tower-review-09c170`. The source branch is untouched. Code guide: `cooling-towers.md` updated. Tool folder: `tools/cooling-towers/fill-forms/`.

## Expected behaviour

A tower is an open basin under a spray. A 16 m² cell fed 86.7 kg/s at 317 K with its fan on leaves about 308 K, evaporates about 1.2 kg/s and rejects about 3.4 MW. A loop must start by itself: the line's gas leaves through the atmospheric inlet, as from a real line. A wrong liquid closes the inlet. One 5 s interval has a few milliseconds per fill, because a fill runs in every Newton residual.

## What the code did, and the change

| # | Found | Measured | Change |
|---|---|---|---|
| 1 | The marched fill cost about 75 ms a call (×18 in target mode) | every island hit `HELD: wall deadline`; a pumped loop stayed held at tick 21 | Merkel rating (below); 60 ms to 0.05 ms; no sweep along the fill |
| 2 | A loop built with an empty basin deadlocked: seed nitrogen at the suction junction failed the water check | pump closed forever; a plain tank in the same loop primes | The check judges the stream's liquid; a gas-only junction primes through when its feeders give water (`InletPhase.priming`, sharing the `held` walk) |
| 3 | Exact-zero purity failed in any loop: recirculated water keeps a nitrogen trace | `WATER_ONLY` while running | Gas passes to the basin vent; the fill counts liquid water only |
| 4 | A vented trace tripped the 1e-8 gate: a junction omits it, the basin row resolved it | permanent hold at tick 247 | `Equations.kept`: a gas the vent takes whole scales as one the node does not hold |
| 5 | Pumped hydrocarbon held the island: closure set `boundaryClosed` without stopping the pump | `Newton line search stalled` | `closeBoundary` in one rule, `refusedSpray` |
| 6 | One-way ends were a residual row override | no failure reproduced | Moved into `boundaryAllowed` (`againstTower`) |

Every loop scenario now runs at `held=0`: built empty, fan speed 0.6 s for 2947 ticks (was a permanent hold); built empty, target mode 2.2 s; built full, 0.1 s (was 2.6 s). Overlapping a campaign, so these times are indicative.

Also consolidated: `Rates` and `Result` lost unused fields (state format 30 is unreleased); one quad builder in `EquipmentGhost`; shared bank limits; unused `ProcessUnit.bar` overload and two language keys removed. Tower tests go from 39 to 31: 12 that repeated others are removed; added are the marched table, the loop in each fan mode, hydrocarbon closure and air breaks. The six tower rows of the layout table are refreshed for the outlet move the owner directed, and the natural row of `shapes-4.snbt` (unreleased) for the 1.3 height rule: both gates were red on the branch, the second unnoticed because the height commit ran no Gradle tests.

## Fill forms against the marched fill (1840 points)

The owner first asked for correlations, then chose Merkel on these numbers. Outlet error in K, RMS / worst.

| Class | Merkel | Correlation | Merkel air RMS | Merkel evaporation RMS |
|---|---|---|---|---|
| Design, 1–3 kg/(s m²), 303–323 K | 0.15 / 0.54 | 0.74 / 2.1 | 1.6% | 2.9% |
| Fans on, 1–5 kg/(s m²), ≥ 303 K | 0.29 / 0.84 | 1.24 / 4.2 | 0.5% | 2.3% |
| Natural, 1–5 kg/(s m²), ≥ 303 K | 0.25 / 0.62 | 2.36 / 6.0 | 2.3% | 1.4% |
| Hot, 338–353 K | 0.42 / 1.4 | 1.95 / 6.0 | 1.7% | 1.8% |
| Dribble, ≤ 0.2 kg/(s m²) | 0.92 / 1.6 | 0.30 / 1.9 | 83% | 45% |

A fill costs 0.05 ms in Merkel, 0.01 ms as a correlation. The running loop reads 307.86 K, 3383 kW and 1.184 kg/s against the marched 307.73 K, 3412 kW and 1.203 kg/s. Mass and energy stay conserved: the rates are booked once, and the balance uses the network's own enthalpies. The dribble class is the one loss: at 1–10% of design flow the marched fill humidified the air past water at the wet bulb, and Merkel's enthalpy balance does not see that. Its worst point overall is 7.3 K at 278 K feed, 0.2 kg/(s m²). References moved: tower outlets by the amounts above.

## For the owner

1. **Dribble evaporation.** Merkel reads about 45% RMS low below 0.2 kg/(s m²). A humidification term like the correlation's would fix it; left out for simplicity.
2. **Gas at the spray: decided.** The owner kept this 2026-11-02: gas in a spray stream passes to the basin vent, which is what lets a loop prime, replacing the plan's pure liquid water. Methane in the water vents the same way.
3. **Certification: fixed.** `IslandCertificate.rate()` counts `ATMOSPHERE`, so an evaporating tower can certify STEADY. A wet basin still never reaches REST (surface time constant about 13 days).
4. **Small items, left.** Water spills at 4/3 m under a 2 m rim, so the rim's clamp is unreachable. `distributorHeight()` and the snapped nozzle differ up to 0.4 m on natural towers (about 4 kPa).
5. **GUI gates: fixed.** The tower's control moved to `DeviceColumn` with a gate at every width, and the fan integrates its own phase, so a speed change cannot jump it.

Not run here: the final lane (chain-100, fluid GameTests, campaign), and `verify.ps1` never has been. `equipment_concrete.png` is shared with all equipment. The merge may collide on schema 6, state 30 and protocol numbers with the column operation branch. `tools/cooling-towers/native.init.gradle` is undocumented. No merge, push, version bump or client check.

## Gates

Quick suite 1836 of 1836. Slow gates 188 of 188 at one fork. With `-PtestForks=4` one wall-budgeted gate held, which is not a defect.
