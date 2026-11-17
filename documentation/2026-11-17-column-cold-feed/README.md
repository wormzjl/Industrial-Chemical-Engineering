# Column on a cold feed (2026-11-17)

**Status:** Planned 2026-11-17 (feasibility study and plan), on branch `claude/column-testing-sizing-26b5b9`; nothing built. Class:
PHYSICS. Lanes: column operation (plant layer and engine).

A running column that gets a feed too cold for its solved table trips and holds the feed, and where the hot feed
stands behind the cold one it never recovers. The owner asked that a cold feed runs to the bottoms and that draws and
pumparounds work only as far as their trays hold liquid.

## History

- 2026-11-17: the full-scale review plant's column stayed in UPSET at start (batch `2026-11-16-review-plant-sizing`).
  The owner: "if i'm running a cold feed into it, it should route them to the bottom (and maybe get heated up by steam
  a bit) instead of holding. PA should only work if there is something in these stages", then "Perform feasibility
  study first". The study: [COLD_FEED_REVIEW.md](COLD_FEED_REVIEW.md), on `tools/column-cold-feed/ColdFeedProbe.java`
  (the tray solver run directly at feeds from 638 K to 300 K, three sweeps). Result: feasible as a pass to the bottoms
  with no solve (T0) and a turndown solve at a share of the draws and pumparounds (T1), which V3 solves from 460 K up;
  a flash table below that (T2) can wait. No mod code, no test run.
- 2026-11-17: the owner: "the PA duty comes from it flowrate and temperature, if we keep the design pumping rate, and
  simulate temperature impact then we should be able to model the actual exchanged heat during startup". The probe run
  with the pumparound duties alone cut (`-DdrawShare=1`): V3 solves every duty from 1 % to 100 % at feeds of 575 to
  638 K with the draws kept. The review's section 9: the duty follows the cooler, a band and a solve, no ladder.
- 2026-11-17: the owner: "Yes, plan it". [COLD_FEED_PLAN.md](COLD_FEED_PLAN.md): the pass (a tripping cycle whose
  condenser would heat, or whose state stands refused, books its feed to the bottoms), the pumparound's duty capped by
  its cooler with a 5 % band and a solve at a relaxed duty, the cooler's wall law against the pumparound's own stream,
  the texts; a draw share later. The probe's duty loop (`-Diterate`) on the real solver at 638 K, from 1 % of the
  design duties: undamped it overshoots (20.3, 10.5, 14.5 MW on the first) and settles in about 7 solves; with a
  relaxation of 0.5 it settles in 4 without overshoot; both end at 13.1, 21.0 and 13.2 MW. No mod code, no test run.
- 2026-11-17: package 0 built (commit `c4cc8ca79`), found by the build thread before package 1: a column's product
  generator stood at the table's temperature, and the difference to its account went to the condenser totals. Now
  `PortPlan` carries the account's enthalpy per mole as of the fence and `PassiveStepSolver.ports` stands the generator
  at the PH equilibrium's temperature (the planned one where it does not converge); a generator within ten PH
  tolerances of its plan keeps its graph. Basis checked: an account's energy and a generator's booking are both the
  fluid's enthalpy on the network's datum (solids are their own inventory; an inlet's crystals are melted into the feed
  before the split). Tests: `FlowControlTest` 14/14 (new: an outlet at an enthalpy), `ColumnRunTest` 48/48 of the
  selected methods. Moved reference: `anAirThenWaterCondenserDividesItsBookingDifferencesByTemperature`, the outlet's
  booking difference at 330 K from -35166.6 J to 0.0 J and at 305 K from 1048.5 J to 0.0 J (bound: the PH tolerance);
  its assertion now asks for 0 within 1e-6 of the cycles' duty. The rig's table prices its product 1000 J/mol under the
  feed (about 306 K for its nitrogen) while naming 330 K: the rig's product now leaves at about 306 K. A table V3 solves
  prices each product at its own temperature, so a real column's products stay at the table's temperatures.
- 2026-11-17: package 1 built (commit `69726bddb`): the pass. `ColumnCycle.step` takes `refused`; a trip passes where
  the condenser would heat or the state stands refused, else holds; `Condition.PASSING` (a notice; alarm UPSET), the
  status line `PASSING: <text>; the feed leaves as bottoms` (phase RUNNING), the English line. A passing cycle's solve
  is asked of the state it was given (feed and steam at its ratio), the closure's where no band was left: a pass took no
  steam, and its own state would ask a steam solve. The air fans stand while passing. Tests: `ColumnCycleTest` 19/19
  (the heating-trip case now passes the feed: before, it tripped and took nothing; new: the bounds, a refused state, a
  thousand cycles of a cold and hot feed), `ColumnRunTest` 49/49 of the selected methods (new: a 300 K feed on a 340 K
  table passes for 3000 ticks with the bottoms at 300 K within 1 K and every mole and joule in the bottoms, exact; at
  340 K it runs). No other reference moved.
- 2026-11-17: package 2 built (commit `6f6dbe148`): the pumparound's duty follows its cooler. The cycle books each
  pumparound's set-point duty (L2: the duty asked is the set point, decision D2) up to its cooler's room; the plan's
  package text read "table per feed", which cannot climb back once a table is relaxed (the booked duty never exceeds the
  table's), so the set point is asked, which equals the table's at every design point. `PUMPAROUND_SHORT` is a NOTICE
  ("pumparound N's cooler takes X % of its duty, the condenser the rest"), the trip compares the condenser with the
  solved duty plus what the pumparounds left, `DriftBands.PUMPAROUND` = 0.05 (`Trigger.PUMPAROUND`, after the steam
  ratio), `ColumnOperation.relaxed` solves at `at + 0.5 (booked - at)` (capped by the set point, at least 1e-6 of it;
  a duty inside the band keeps the duty the table was solved at, so a design point solves as before), and no
  PUMPAROUND solve is asked where the relaxed duties are the table's own. `ColumnSolve.Job` carries the duties;
  `SplitTable.Reference.pumparounds` keeps them beside the set points (a refused state's: the duties it booked). A
  pumparound cooler's hold is of its set-point duty, so a relaxed table keeps its cooler's hold. State format 53: the
  reference record gains the duties; a state without them is read as solved at its set points (exactly what it was),
  not dropped as the plan said (the saves rule: a new field defaults to the behaviour before it). Tests:
  `ColumnCycleTest` 20/20, `ColumnSolveTest` 4/4 selected (new: at half the duties the draw trays go from 417, 494, 556
  to 452, 524, 587 K and the condenser from -56351 to -78571 J/mol of feed, closure exact), `ColumnRunTest` 50/50
  selected (new: a cooler on a narrow water line taking 49 % of its duty: solves at 370, 282, 239, 218, 207, 202 J/mol
  of feed, settled 2.7 % from the 196 it takes after six solves, the feed kept). Moved references (the PUMPAROUND_SHORT
  cases): `eachUtilitysRoomBoundsItsOwnDuty` room for 0.4 of the duty: before alpha 0.4, PUMPAROUND_SHORT binding; now
  alpha 1, 0.4 of the duty booked, a notice; with the reboiler at 0.6 and the condenser at 0.7: before the
  pumparound's 0.4 bound, now the reboiler's 0.6. `aShortPumparoundCoolerLimitsTheColumn` (renamed
  `aShortPumparoundCoolerKeepsTheFeed`): before no feed after the water's loss, LIMITED: pumparound; now 25 mol in
  50 s (the set 0.5 mol/s), RUNNING with the notice at 0 %, COOLING LIMITED. No design-point reference moved.

## Documents

- [COLD_FEED_REVIEW.md](COLD_FEED_REVIEW.md)
- [COLD_FEED_PLAN.md](COLD_FEED_PLAN.md)

## Code areas (when built)

`science/plant/column` (`ColumnCycle`, `ColumnSolve`, `SplitTable`, `DriftBands`, `Condition`, `ColumnStatus`),
`runtime/fluid/ColumnOperation`, `EquipmentAlarm`, the Operation tab.

## Open items

- The owner's go-ahead for the build: packages 1 to 4 of the plan as Opus threads; decisions D1 to D6 of the plan are
  the main thread's and open to the owner.
- Not measured: where the heat-integrated review plant settles when its pumparounds are cut; T2's flash against V3
  at the 455 to 460 K edge; another crude.
