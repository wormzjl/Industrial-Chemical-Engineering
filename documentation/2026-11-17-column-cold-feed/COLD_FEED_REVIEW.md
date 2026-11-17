# A running column on a cold feed: feasibility (2026-11-17)

Asked by the owner after the full-scale review plant's column stayed in UPSET at start: "if i'm running a cold feed into
it, it should route them to the bottom (and maybe get heated up by steam a bit) instead of holding. PA should only work
if there is something in these stages", then "Perform feasibility study first". No mod code was changed. The numbers
are from `tools/column-cold-feed/ColdFeedProbe.java` on the network's default column at full scale (feed 710.3 mol/s
at 638.2 K, top 250 kPa, steam 330.7 mol/s at 533 K, 40 trays, feed stage 37, condenser at 332.15 K, reflux ratio 4.17,
no reboiler, three draws, three pumparounds of 47.3 MW together).

## 1. Summary

Feasible, in three parts that fit the present structure (one solve gives a split table; a cycle applies it):

| Part | What | Covers | Solver work |
|---|---|---|---|
| T0 | A cycle with no table that fits passes its feed to the bottoms as it is | any feed, at once | none |
| T1 | A solve that fails at the design's draws and pumparounds is tried at a share of them | here 460 K to 575 K | 1 to 4 solves a table |
| T2 | Below the tray solver's reach, one flash of feed and steam gives the table | here under about 455 K | 2 flashes |

T0 and T1 answer the request. T2 only refines T0 (steam's heat, light ends overhead) and can wait.

## 2. Expected (physics)

- A feed that makes little vapour runs down the stripping trays to the bottoms. Trays above the feed get liquid only
  from condensing vapour, so with little vapour the draws and pumparounds have little or nothing to take.
- Stripping steam: above water's boiling point at the tray (about 400 K at 2.5 bar) it stays vapour, warms the liquid
  by about 4 K here (330.7 mol/s cooling 133 K against 710 mol/s of crude at about 500 J/(mol K)) and leaves overhead
  with the lightest ends. Below it the steam condenses into the liquid and warms it by up to about 35 K (12.5 MW).
- A real unit is started this way: cold circulation, the heater lit, the column coming up, the pumparounds last.

## 3. What the code does

- `ColumnCycle.step` applies the table of the last solve to whatever the feed account holds. The first table (START) is
  solved at the design's feed, 638 K (`ColumnOperation`: with no cycle yet, `designTaken`).
- A cold feed priced through that table needs heat: the closed condenser duty is positive, `CLOSURE` trips with "the
  condenser would heat the overhead" (`ColumnCycle.java:358`) and the cycle takes nothing (`idle`).
- The trip asks a solve at the taken state. It fails (section 4), the state is stored as `Refused`, and no solve is
  asked again while the state stays inside its bands. The feed account is full, its valve closes, nothing displaces the
  cold feed: a deadlock. In the review plant (`tmp/review-plant-fix/build-2.log`) the heaters reach 638 K at t = 1524,
  and the column still holds the crude that passed before they were lit (t = 925: "LOW FUEL", outlets 301 to 321 K).

## 4. What the tray solver does with a cold feed (measured)

Cold solves, the design's specifications kept. "x s": draws and pumparound duties at s of the design; "bare": none.

| Feed, K | design | x 0.5 | x 0.25 | x 0.01 | bare |
|---|---|---|---|---|---|
| 638 | ok | | | ok | fails |
| 600 | ok | ok | | ok | fails |
| 575 | ok | ok | ok | | ok |
| 550 | fails: draw 1 over the tray's liquid | ok | ok | ok | ok |
| 525 | fails: the same | ok | ok | | fails |
| 500 | fails: pumparounds 47.3 MW over the base condenser duty 46.5 MW | | ok | ok | ok |
| 490 to 470 | fails: the same (44.3, 41.6, 38.4 MW) | | ok | | ok |
| 460 | fails: stall at 4 stages | | ok | | ok |
| 450 | fails: stall at 4 stages | | | fails | fails |
| 400 to 300 | infeasible: pumparounds over the heat available (39.3, 22.0, 6.7 MW) | | | fails: audit, liquid-only condenser | fails |

- The failures at the design are the owner's point: the draws and pumparound duties are fixed per mole of feed, and a
  cool feed leaves less liquid and less heat on those trays than they ask.
- With them cut, V3 solves from 460 K up. At 460 K and a quarter share: bottoms 517 mol/s at 437 K, draws 36, 33 and
  13 mol/s, distillate 29, off-gas 89, condenser 17.3 MW against the design's 33.8 MW.
- Empty lists are less robust than a small share (638, 600 and 525 K fail bare): a ladder uses shares, not removal.
- Under about 455 K nothing solves: no vapour reaches the condenser.
- Work, in the solver's polls: a normal solve 0.24 million; a successful cut solve 0.11 to 1.6 million; a failed
  design attempt 0.1 million where V3's own bound stops it (470 to 500 K), 0 where the input check does (400 K down),
  2.8 to 2.9 million at 525 and 550 K. A blind ladder there costs about 12 normal solves a table.

## 5. Proposal

**T0, the pass.** Where a cycle would trip on "the condenser would heat the overhead", or runs on a refused state, it
takes its feed and books all of it to `BOTTOMS` at the feed's own energy; it takes no steam and owes no duty. Mass,
components and energy are exact by construction, no property call runs on the server thread, and no solve is needed.
The status says the column passes its feed cold. The simplification: the steam's heating and stripping are left out,
4 K above 400 K and up to about 35 K below it; T2 restores them.

**T1, the turndown solve.** `ColumnSolve.run` tries the accepted set points, then shares of the draws and pumparound
duties (for example 0.5, 0.25, 0.05), and keeps the largest that solves. The table carries the share; the cycle,
the limits, the bands and the ports stay as they are. A notice says the draws and pumparounds run at that share. V3's
own bound (pumparound cooling under the base condenser duty) gives the first share to try, so the ladder is not blind.

**T2, the flash table** (later, if wanted): under the solver's reach, feed and steam flashed at the feed tray, the
vapour condensed at the receiver: bottoms, off-gas, distillate, sour water, no draws.

## 6. What it touches

| Area | Change |
|---|---|
| `science/plant/column/ColumnCycle` | T0: the trip's cycle passes the feed instead of `idle` |
| `science/plant/column/ColumnSolve`, `SplitTable` | T1: the ladder, the share in the table and its reference |
| `science/plant/column/DriftBands`, `Condition`, `ColumnStatus` | a notice for each mode; a wider enthalpy band while turned down (today 1 K, a solve every 30 s) |
| `runtime/fluid/ColumnOperation` | a refused state no longer holds the feed; a SETPOINT compare that reads the set points, not the share; the table's new field in the checkpoint state (state format 53 is this branch's, not merged yet) |
| `runtime/fluid/EquipmentAlarm`, the Operation tab, `en_us.json` | the two notices |
| Tests | `ColumnCycleTest`, `ColumnSolveTest`, `ColumnRunTest`; the review plant's start as the in-world check |

No saved fact changes. The sheet's column block shares `ColumnSolve.calculate`, not `run`: it is not touched.

## 7. Risks and open points

- **A heat-integrated unit can settle turned down.** With the crude preheated by the pumparounds, a cut pumparound
  share gives a cooler feed, which keeps the share cut. The heater alone must lift the crude: here 66 MW installed
  against 47 MW of missing pumparound heat at the design rate. The way out is the real one: start at a lower feed rate
  (the set point exists) and raise it. Not measured: where the review plant settles.
- **Solver work during warm-up.** A feed rising from 460 K to 638 K crosses the 1 K enthalpy band about 180 times. A
  band of about 10 K while turned down makes that about 18 tables.
- **Steps between modes.** The products step when the mode changes; a hysteresis on the share is needed so a column
  near an edge does not alternate.
- **Draw products off their set points.** At a quarter share the first draw leaves at 361 K, a naphtha, not a
  kerosene. It is what a real column gives; the notice must say it.
- **Not checked:** T2's flash against V3 at the 455 to 460 K edge (the probe has no flash); the same sweep on another
  crude or at 1/16; the effect on `ColumnRunTest`'s references (a table solved at the design is unchanged by
  construction, since the ladder starts at share 1).

## 8. Size

Three packages, each for an Opus thread: T0 with its tests (standard); T1 with the table's share, the bands and the
engine side (hard: solver calls and engine state); the notices, alarms and tab texts (standard). T2 is one more hard
package. A detailed plan comes before any brief.

## 9. The pumparound's duty from its flow and temperatures (owner, 2026-11-17)

The owner: "the PA duty comes from it flowrate and temperature, if we keep the design pumping rate, and simulate
temperature impact then we should be able to model the actual exchanged heat during startup". This replaces T1's
share ladder for the pumparounds: the duty is no longer a set point, it is what the cooler exchanges.

**The law.** Each pumparound circulates at its design rate F (the pump's). The hot side enters the cooler at the draw
tray's temperature and leaves at T_draw − Q / (F cp); the cooler exchanges Q against its cold side as any exchanger
does, so Q ≤ F cp (T_draw − T_cold,in − approach). The cycle books the Q exchanged, and where it leaves the table's
duty by a band (as a short steam does under rule B) a solve is asked at the exchanged duties. A cold tray gives a
small Q; a hot tray over a cold crude gives a large one, which cools the tray: the loop is stable.

**Measured** (pumparound duties alone at a share, the draws at the design, cold solves):

| Feed, K | Pumparounds x | Outcome | Draw trays, K | Condenser, MW | Bottoms, mol/s |
|---|---|---|---|---|---|
| 638 | 1 (design) | ok | 428, 505, 556 | 33.8 | 227 |
| 638 | 0.75 | ok | 453, 519, 575 | 43.2 | 195 |
| 638 | 0.5 | ok | 466, 536, 583 | 53.5 | 171 |
| 638 | 0.25 | ok | 480, 551, 601 | 64.3 | 145 |
| 638 | 0.01 | ok | 498, 565, 608 | 75.7 | 117 |
| 600 | 0.75 to 0.01 | ok, all four | 419 to 476 (first draw) | 29.4 to 61.5 | 236 to 152 |
| 575 | 0.75 to 0.01 | ok, all four | 398 to 465 (first draw) | 20.9 to 54.1 | 266 to 170 |

- V3 solves every duty from 1 % to 100 % of the design at a warm feed, with the draws kept. The draw trays warm by up
  to 70 K as the pumparounds are cut, and the condenser takes the heat they leave (33.8 to 75.7 MW at 638 K).
- At the design, a 60 K fall gives F cp of 218, 350 and 221 kW/K (13.1, 21.0 and 13.2 MW). With the first pumparound
  cut and a 300 K crude on the cold side, the heat it could give is 218 kW/K x (498 − 300 − 10) K = 41 MW, three times
  its design duty: at start the limit is the cooler's surface, not the flow, and the duty rises fast once the column
  is hot.
- The condenser must take up to 2.2 times its design duty while the pumparounds are cold; where it cannot, the column
  is limited by its condenser (`COOLING_SHORT`), which is what a real unit sees.

**What it changes in the proposal.** T0 stays (a feed no table fits passes to the bottoms). T1 becomes: the
pumparound duties follow the coolers (a band and a solve, no ladder); the draws keep a share rule for a feed under
about 575 K, where the design's draws exceed the trays' liquid. The table gains each pumparound's F cp (the draw
liquid's heat capacity from the solve, the pump's design rate). `PUMPAROUND_SHORT` no longer cuts the feed.

**Not read yet:** how the held cooler's side computes its transfer (`ExchangerRelay`, `Read.column`): today it gives
the owed heat up to a ceiling at the draw temperature less the approach, with no hot-side flow. Whether the finite
hot side goes there, or the loop becomes a real stream of the island, is for the plan.
