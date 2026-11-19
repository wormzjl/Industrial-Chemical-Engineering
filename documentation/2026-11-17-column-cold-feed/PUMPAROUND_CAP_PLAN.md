# Package 6: the pumparounds capped by the column's own heat (plan, 2026-11-18)

Owner, 2026-11-18, on the default column test world: "seems like feed flowrate control is having trobles", then, of
the options, "B only": cap each pumparound by the heat the feed actually brings, so a cold feed runs the column lean
instead of tripping it. No change to the test world's start.

## 1. What happens (measured, `tools/column-soak`, review batches `review-column-soak-feed` and `-burners`)

- The column starts 10 s after the plant (`CrudeUnitPlant.warmed`: the charge line is a dead end until the feed opens,
  so it never reads hot; `HOT_TICKS` = 200). The two heaters fire what their coils ask (fuel 98 to 101 % of asked, 74 %
  of full fire), but the coils' oil holdup warms: heater outlet 481 K at tick 1000, 592 K at 1200, 598 K at 1500,
  627 K at 2000 (set point 638 K).
- Every solve of that cold feed at the set-point pumparounds fails: at 28 % of the feed, `INFEASIBLE_SPECIFICATION`
  13.20 MW over the 2.065 MW available; later `NONCONVERGENCE` 47.34 MW over the 44.85 MW base condenser duty
  (`V3FeatureRamp.requireCoolingBelowBaseCondenserDuty`). The review's sweep found the same edge: at 500 K the design's
  47.3 MW over 46.5 MW, at a quarter share every feed from 460 K up solves (COLD_FEED_REVIEW.md section 4).
- `ColumnOperation.relaxed` never lowers the duties: it moves toward the duty the cycle booked, and the cycle books the
  set point up to the cooler's room. Here the coolers' cold sides are the cold crude, so their room is the whole duty.
- So the table stays the old one. The cycle books 47 MW of pumparounds against it, the condenser would heat, and the
  column passes the feed to the bottoms (`PASSING`) while the state stands refused. Once the warming feed leaves the
  refused state's bands, the cycle trips on the closure ("the condenser duty departs 94 % from the solved one",
  tick 1900), holds, the inlet plan writes no demand (`levels(..., false)`), the feed valve shuts and the charge pump's
  trim drives it to its lowest speed. The feed stops for about 25 s, then ramps back. In a 20-minute soak this happened
  three times, then the plant ran steady.

## 2. Expected (physics)

A pumparound circulates at its pump's rate; its duty is F cp (T_draw - T_return). A cold feed brings less vapour, the
draw trays run cooler and the pumparounds take less. The column can hand a pumparound at most the heat its condenser
would otherwise take: the pumparounds take over the condenser's duty, never more, and some vapour must still reach the
condenser for reflux. The feed is never held for it.

## 3. Change

**L1, the solve caps the duties where V3 says they are over the column's heat** (`science/plant/column/ColumnSolve`,
the run that builds `V3PumparoundSpec` from `job.pumparounds()`, about line 420).
- Where the outcome is a cooling-bound failure, solve again at lower duties, in this order, stopping at the first
  success: the table's own duties of the job (`Job` gains nothing: the request passes the table's
  `reference().pumparounds()` as a second array, or null at a start), then 0.5, 0.25, 0.1 and 0.01 of the asked duties.
  Never below `ColumnOperation.PUMPAROUND_FLOOR` of the set point (V3 refuses a pumparound of no duty).
- A cooling-bound failure is: `INFEASIBLE_SPECIFICATION` from `V3Admission.staticCoolingAdmission`, or `NONCONVERGENCE`
  whose summary carries `PathDependentHeatBound.PATH_DEPENDENT_BOUND_SUFFIX` (the base-condenser bound and the
  condensation cap). Expose one public predicate in `science/column/v3` (for example
  `V3ColumnOutcome.Failure.coolingBound()`), so the plant layer does not parse text. Any other failure is returned
  as now.
- The table records the duties it solved at (`SplitTable.Reference.pumparounds`, already saved since state format 53):
  no format change. The solve's note says the duties were capped ("pumparounds at N % of their duty: the column's heat").
- Cost: the review measured a failed attempt that V3's bound stops at about 0.1 million polls, a cut solve 0.11 to
  1.6 million. At most five more attempts, only on a failure that today ends the solve.

**L2, the cycle books no pumparound duty the column cannot give** (`ColumnCycle.step`, the pumparound loop at line 358).
- Today each pumparound books its set point up to its cooler's room. Add the column's side: the pumparounds together
  book at most the table's own duties plus `PUMPAROUND_TAKEOVER` (0.9) of the table's condenser duty at the feed taken,
  the excess over the table's duties scaled down evenly. The overhead keeps at least 10 % of its solved duty.
- At a design point (the table solved at the set points) the excess is 0 and nothing changes. With a capped table the
  cycle books more than the table (up to the take-over), the band (`DriftBands.PUMPAROUND`) asks a solve and
  `relaxed` moves the duties half-way up; L1 caps it again where the column still cannot. The duties climb back as
  the feed warms, through the drift solves the warming asks anyway.
- A capped booking is a NOTICE, as a short cooler is: `PUMPAROUND_SHORT` with its own text ("pumparound N takes X % of
  its duty: the column's heat"), so the Operation tab's conditions table shows it with no new condition.

## 4. Numbers to expect

- The run of section 1: from about tick 600 the solves succeed at a share (460 K and up, per the review), the column
  RUNs instead of PASSING, and no closure trip holds the feed. The feed valve throttles about the column's rate, the
  pump stays on its flow set point.
- A cold table with the pumparounds at 25 % (11.8 MW) and its condenser at about 17 MW: the cycle books at most
  11.8 + 0.9 x 17 = 27 MW, the condenser keeps 1.7 MW: no pass. `relaxed` then asks 11.8 + 0.5 (27 - 11.8) = 19.4 MW.
- At the design (638 K, the set points): every reference unchanged.

## 5. Ruled out

- A start-up at reduced feed in the test world (option A): the owner chose B only.
- Parsing the failure text in the plant layer: the predicate lives in V3.
- Capping before the solve by the static bound alone: the failures here are the base-condenser bound, which only a
  solve can measure.
- A saved flag for "capped": L2 needs none, it reads the table's own condenser and duties.

## 6. Pitfalls

- `ColumnCycleTest` and `ColumnRunTest` cases that book a set-point duty above a table's (the PUMPAROUND_SHORT cases of
  package 2) may move: state each moved reference with its old and new value.
- The cap must never touch the reboiler or the draws. Draws over a tray's liquid under about 575 K are package 5
  (waiting for the owner).
- Conservation: the condenser still closes the balance (`condenser = out - in - reboiler - paTotal`), exact.
- The closure trip can still hold a feed that warms faster than the solves follow (`SOLVE_SPACING`). Measure it in the
  check; do not widen `TRIP_DEPARTURE` here.

## 7. Tests (main thread, after the code)

- `ColumnSolveTest`: a 500 K feed on the design solves at a share instead of failing, the table's duties at that share,
  closure exact; a 638 K feed solves at the set points as before.
- `ColumnCycleTest`: a capped table books at most its duties plus 0.9 of its condenser, a NOTICE, no pass; a design
  table books the set points.
- Then `node tools/merge-lane/affected.mjs select` after `testClasses`, run with `--tests`.
- Check: `tools/column-soak`, batch `review-column-soak-feed` (about 2 minutes): no CLOSURE trip, the feed target never 0.
