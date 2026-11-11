# Minecraft time as the simulation step: review

Owner, 2026-11-11: "Evaluate possibility to use Minecraft time instead of real time as simulation delta T (keep 5s calculation interval as real time), check its impact (will too much changes in one dT lead to convergence issue?)". Code: `ff6f6892f` (0.26.0). Nothing in `src/main` changed.

## 1. Expected behaviour

- One Minecraft day is 24 000 ticks, 20 min of play and 24 h of game time: 72 process seconds per real second, 3.6 s per tick.
- The 100-tick (5 s) schedule then advances 360 s (6 min) of process time per slice; the adaptive cadence of 20 to 400 ticks is 72 s to 24 min.
- A 1000 m3 tank on a 100 m3/h line fills in 8.3 min of play instead of 10 h. Rates on screen (per hour) do not change. Hydraulics and pressure settle in seconds, so over 360 s they are at their steady state; levels of vessels with a 5 to 10 min holdup move by a whole vessel per slice.

## 2. What the code does

- Simulated time is online ticks / 20 (`IslandClock.Slice.seconds`, and about 40 more `/20.0` sites in `runtime/`). A slice is not one step: `PassiveIntervalSolver` takes backward-Euler substeps, each accepted only if no vessel's pressure or mass moved by more than 5 % (`STATE_CAP`), starting at 1 s, growing by at most 2x, capped at 20 s, at most 1024 attempts, all inside the 2 s wall budget.
- So a longer slice does not make one Newton harder. It makes more substeps: at least 18 per 360 s slice with the 20 s cap, where a 5 s slice takes 3.
- Everything exchanged between solves is explicit and one cycle old: the column cycle and its accounts, the control cadence (100 ticks), heat sides across two islands, drivers and electric stores. At 72x one cycle is 6 min of process time.

## 3. Measurement

Hold-fuzz quick check, seeds 1-200, 120 slices (10 min of process time at 1x, 12 h at 72x), 8 threads, alone. Probe: `tools/minecraft-time-delta/`. Forms: step settings (initial, cap) of 1 s / 20 s (A), 1 s / 1440 s (B), 72 s / 1440 s (C, both scaled).

| Form | Clean: bundled, cold, defaults | Newton iterations: bundled, cold, defaults |
|---|---|---|
| 1x | 198, 197, 200 | 131 782, 274 320, 10 902 |
| 72x A | 192, 199, 200 | 292 881, 336 993, 33 790 |
| 72x B | 192, 199, 200 | 202 037, 283 163, 18 172 |
| 72x C | 194, 198, 200 | 147 652, 290 542, 10 722 |

- Form C does 72 times the process time for 1.12, 1.06 and 0.98 times the Newton work: most islands reach rest or a steady flow, and a scaled step crosses the rest in the same number of steps. With the steps unscaled (A) the work is 2.2, 1.2 and 3.1 times.
- Holds of form C: bundled 15, 47, 77, 123 (wall deadline; they stand replayed alone on one thread), 118 (wall deadline; clean alone), 164 (substep limit: 507 accepted, 517 rejected on line-search stalls); cold 72 (non-finite matrix, class B) and 118 (wall deadline, held at 1x too). Held at 1x and clean at 72x: bundled 6 and 200, cold 85 and 101.
- The wall-deadline holds are not a substep count: seed 47 overran 2 s on a 4-tick slice with 72 step attempts in the whole world. Their mechanism is not traced (a held seed is replayed once).
- Not measured: the fuzz worlds hold tanks, pipes, valves, pumps, compressors, filters, generators and voids, and no column, controller, heat unit, shaft train or still.

## 4. Impact outside the island solve (from the code, not measured)

- **Level control** (`InventoryControl`, one cadence to recover the error): an inflow step of half the throughput, uncorrected for one 360 s sample, moves a 5 min drum by 60 % of its volume (0.8 % at 1x). Drums sized on real holdups overflow or run dry between samples.
- **Running column**: `INLET_AIM_SECONDS` 30, `BUFFER_SECONDS` 120 and `CONDENSER_HOLD_SECONDS` 60 are shorter than one 360 s cycle, so the column would starve as written. Stated in cycles (6, 24, 12) the accounts hold 36 min of feed and 2.4 h of products: 60 m3 and 240 m3 on a 100 m3/h column. The 22 s fence becomes 26 min of process time. `Stills` has the same windows (10 s, 30 s).
- **Power and wear**: a 1 MW motor draws 1.44 million FE per tick at 2.5 J/FE (20 000 now); stores sized at 40 s of rated draw hold 72 times less play time; 2 h of cavitation is 100 s of play, 10 min dry is 8 s.
- **Heat sides in two islands** read a partner 6 min old: right for flowing streams, a lag for a kettle pool.
- **Saves**: the scale is a setting, not a fact; contents and the online tick stay. Solved intervals carry their seconds (`IslandCertificate.rebuild` refuses a mismatch), so the state format bumps and state is rebuilt once.
- **The level's day time is the wrong clock**: sleeping skips up to 12 h of process time in one tick, `/time set` runs it backwards, `doDaylightCycle` stops it, and each dimension has its own. AGENTS.md also forbids offline advancement.

## 5. Proposal for the owner

1. **Feasible.** The island solver converges at 72x at about today's cost when its step settings scale with the time scale. The risk is in the explicit loops and in the play-time interfaces, not in the Newton.
2. **Clock**: a constant factor on online ticks (`fluid.timeScale`, 1 to 72, default 1), one conversion function for every `/20.0` site. Not the level's day time. The AGENTS.md line "Simulated time is online ticks at a nominal 20 TPS" changes with the decision.
3. **Options for the factor** (the physics does not force one):
   - A. 72 (the Minecraft day). Needs the column, still and control windows in cycles, level control inside the solve or drums sized for at least two cycles (12 min), and owner choices for FE, wear and fuel (process time or play time).
   - B. 12 (a day is 4 h of process; a slice is 1 min). The explicit loops stay inside usual 5 min holdups; tanks fill 12 times faster. Not measured.
   - C. Stay at 1.
4. **Before any default changes**: trace bundled 15 and 47 (wall deadline) and 164, and cold 72, in their own batch; one full campaign of the chosen factor; a column, heat and control check, which the fuzz does not cover.
