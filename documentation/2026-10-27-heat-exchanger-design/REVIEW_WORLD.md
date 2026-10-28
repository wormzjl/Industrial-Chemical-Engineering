# Heat exchanger review world (2026-10-28)

The owner's own test of W1 to W8. The world is `run/saves/IChemE heat exchanger review` in worktree `research-folder-reorganize-b8443b`. Open it with `./gradlew runClient` from that worktree: single player, creative, cheats on. To rebuild it: `./gradlew runReviewWorldServer` (it writes `run/review-world/world`), then `node tools/multi-machine-framework/review-world/nbt.js review run/review-world/world/level.dat "IChemE heat exchanger review"`, then copy the result into `run/saves/`. The builder is `HeatExchangerReviewWorld` (fluidGameTest, namespace `icheme_review`, not a gate). The same world also holds the column review plant, at another place.

You spawn at noon, facing east. The five units stand in a row 8 blocks to your left, 18 blocks apart. Every fluid is water. Every line ends at a generator or a void. The values below are from the build, after about 40 s of running.

| # | Unit | Lines | Control | At the build |
|---|---|---|---|---|
| 1 | Exchanger, internal bypass | tubes 90 °C, 2 bar → 1 atm; shell 20 °C, 1.5 bar → 1 atm | shell outlet 30 °C | shell 30.00 °C, 806 kW; tubes out 53.4 °C |
| 2 | Exchanger with a real bypass (W8) | the same; shell bypass: inlet tee, 0.1 m line with a control valve, outlet tee | shell outlet 30 °C | LINES NOT CHECKED; shell 29.8 °C, 305 kW (the bare valve stands open, the internal bypass is shut) |
| 3 | Air cooler, largest size | 85 °C, 2 bar → 1 atm (22.5 kg/s) | outlet 80 °C, all bays | 79.9 °C, 473 kW, fans part-way |
| 4 | Vertical fired heater | pump at 0.6 L/s from 10 bar, 50 °C → convection → radiant → 10 bar | radiant outlet 200 °C (firing) | 0.59 kg/s; convection out 180.6 °C (610 kW, the flue gas law F1), radiant out 200.0 °C (1075 kW) |
| 5 | Kettle, 1 m bundle | tubes 150 °C, 6 bar → 5 bar; feed 99 °C from a generator at 1 atm, 1 m over the shell floor; vapour through a 0.15 m line to 1 atm | tube duty 0.5 MW | duty 499.8 kW; tubes out 132.6 °C; pool 100.0 °C, 101.4 kPa, 258 kg |

## What to check

1. **Unit 1 (W1 to W4).** Open the shell side's screen. Change the set point, for example to 35 °C. The outlet follows on the engine's schedule, about every 5 s. Switch to the duty mode and give a duty.
2. **Unit 2 (W8), the assembly path.**
   - On the shell side's screen, press Check lines. Expected: COMPLETE, but the bypass valve "needs its flowmeter to regulate".
   - Fit a flowmeter on the bypass valve's pipe, then press Check lines again. Expected: COMPLETE. The real valve now takes the bypass share, the internal bypass stays shut, and the mixed outlet holds 30 °C.
   - Break a bypass pipe. Expected: the status goes back to not validated, and the internal bypass controls again.
   - The world has no ratio station; the solver gate covers RATIO.
3. **Unit 3 (W5, W4).** Lower the set point to 70 °C. Expected: LIMITED, the fans at full speed, about 76.6 °C (349.7 K in a build at that set point). Switch the fans off: the outlet rises toward 85 °C.
4. **Unit 4 (W6).** Change the firing target. Both coils show the notice "outside data range: water vapour partial pressure extrapolated above 250 kPa". It is the water data's own range notice (AGENTS.md "Thermo validity"), which stays; the curve-span notice is gone (Q15).
5. **Unit 5 (W7).** Change the tube duty. The pool boils at about 100 °C. Its level stands near the feed generator's height. If you remove the feed generator, the pool boils down to EMPTY in about 20 minutes (258 kg at 0.22 kg/s).
6. Save, leave and reload the world. Every unit keeps its control, and unit 2 keeps its checked lines (a chunk-load check publishes them again).

## Not in the world

W9 (deferred: the column's operation stage does not exist), a ratio station, a split-range pass valve, and the heaters' fuel nozzle (Q11).
