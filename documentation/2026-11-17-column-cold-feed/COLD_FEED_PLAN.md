# A running column on a cold feed: plan (2026-11-17)

Owner: "Yes, plan it", after `COLD_FEED_REVIEW.md` (the feasibility study, sections 1 to 9). Nothing is built. Each
package is for an Opus thread; the main thread reviews. Class: PHYSICS. Read first: `AGENTS.md`, `rules/physics.md`,
`rules/measurement.md`, the code guide's `column-operation.md` and `column-run.md`, the review.

## 1. What the owner asked

1. A cold feed runs to the bottoms instead of being held ("it should route them to the bottom ... instead of holding").
2. A pumparound works only as far as its trays and its cooler allow ("PA should only work if there is something in
   these stages"; "the PA duty comes from it flowrate and temperature, if we keep the design pumping rate, and simulate
   temperature impact then we should be able to model the actual exchanged heat during startup").

## 2. Physics, in numbers

Default column at full scale: feed 710.3 mol/s at 638.2 K, pumparounds 13.1, 21.0 and 13.2 MW, condenser 33.8 MW.

- **The cold slug.** The review plant's charge line and coils hold about 17 m³ of cold crude, 85 s of design flow. A
  column that passes it sends 85 s of feed to the bottoms and then runs on the hot crude behind it.
- **A pumparound's duty against its draw tray.** With the duties cut to 1 % the draw trays stand at 498, 565 and 608 K
  (design 428, 505, 556 K): about −5.4 K per MW on the first.
- **The duty loop** (`tools/column-cold-feed/ColdFeedProbe.java -Diterate`, coolers of fixed conductance 102, 128 and
  85 kW/K over cold inlets of 300, 340 and 400 K, from 1 % of the design duties at a 638 K feed):

  | Solve | Undamped: solved at, MW | exchanged, MW | Relaxation 0.5: solved at, MW | exchanged, MW |
  |---|---|---|---|---|
  | 0 | 0.13, 0.21, 0.13 | 20.3, 28.8, 17.7 | 0.13, 0.21, 0.13 | 20.3, 28.8, 17.7 |
  | 1 | 20.3, 28.8, 17.7 | 10.5, 17.9, 12.2 | 10.2, 14.5, 8.9 | 16.1, 23.2, 14.8 |
  | 2 | 10.5, 17.9, 12.2 | 14.5, 21.9, 14.7 | 13.1, 18.8, 11.9 | 13.9, 21.6, 14.0 |
  | 4 | 12.5, 20.6, 12.8 | 13.4, 21.2, 13.5 | 13.4, 20.7, 13.1 | 13.1, 21.1, 13.3 |
  | 6 | 12.9, 20.9, 13.1 | 13.2, 21.1, 13.3 | 13.2, 20.9, 13.2 | 13.1, 21.0, 13.2 |

  Undamped it overshoots and settles in about 7 solves (gain about −0.45); with a relaxation of 0.5 it settles without
  overshoot in 4. Both end at the design.
- **The condenser** takes what the pumparounds leave: 75.7 MW at 1 % of their duties against 33.8 MW.

## 3. Laws

- **L1, the pass.** A cycle that trips passes its feed to `BOTTOMS` at the feed's own energy where the closed
  condenser would heat the overhead, or where the state's solve stands refused. It takes no steam, owes no duty, and
  its condenser duty is 0. Bound by the feed held and by the bottoms' room, as any cycle. Otherwise a trip holds as
  today (a solve is on its way).
- **L2, a pumparound's duty.** Its set point (per mole of feed) is the duty asked. A cycle books
  `min(asked, what its cooler's sides can still take)`: the room the engine already computes (`ColumnOperation.room`).
  A short room no longer cuts the feed.
- **L3, the closure.** The condenser closes the balance as today. The trip compares it with the solved duty plus what
  the pumparounds did not take, so a short pumparound alone never trips.
- **L4, the band and the solve.** Where a pumparound's booked duty per feed leaves the table's by more than
  `DriftBands.PUMPAROUND` = 0.05 of the larger, a `PUMPAROUND` solve is asked at the accepted set points with each
  pumparound's duty at `table + 0.5 (booked − table)`, never above the set point, never 0 (V3 refuses a zero duty:
  at least 1e-6 of the set point).
- **L5, the cooler** (package 3). A pumparound cooler's side takes the owed heat up to two bounds: its outlet under
  the draw tray's temperature less the approach (today's), and what its wall moves from the pumparound's own stream:
  the draw liquid entering at the draw temperature at the design circulation, `|asked duty at the design feed| /
  PA_FALL / cp` kg/s with `PA_FALL` = 60 K (the sizer's `DesignBasis.paFall`), through the unit's real surface
  (`ExchangerRelay.wall`).

## 4. Decisions

Taken by the main thread; the owner can overrule each.

| | Decision | Effect in numbers |
|---|---|---|
| D1 | The pass takes no steam | The bottoms leave up to 4 K (steam vapour) or 35 K (steam condensing, feed under 400 K) cooler than a real column's |
| D2 | The set point stays the duty asked; the cooler caps it | At the design nothing moves: every existing reference is exact |
| D3 | The circulation is the sizer's, a 60 K fall at the design duty | 218, 350, 221 kW/K here; the hydraulics batch replaces it by the built pump's flow |
| D4 | Relaxation 0.5, band 5 % | 4 solves from a cold start (section 2); under 1 % residual |
| D5 | The draws keep their set points; a feed whose solve fails at them passes (L1) | Feeds between 460 and 575 K pass to the bottoms though V3 could solve them at cut draws: a draw share rule is package 5, later |
| D6 | The pumparound's stream is not saved: after a load a column asks one solve before its coolers read a stream | About 10 s on today's bound alone |

## 5. Packages

Each: code and tests in one commit, documents in a second. Tests named by `node tools/merge-lane/affected.mjs select`
after `./gradlew testClasses --max-workers=4`; not run: `check`, `build`, the final lane, `LanguageFilesTest`,
`UnitTextTest`, the `fluidSolverRegression`, GameTest and campaign steps.

### Package 0, an outlet gives its account's energy (found by the build thread, 2026-11-17). Hard.

Found before package 1: `ColumnOperation.outletPlan` plans every product generator at the table's product temperature,
`PassiveStepSolver.ports` flashes it at that temperature, and `credit` books `joule − debited` to the condenser
totals. A passed 300 K slug would leave the bottoms at the table's bottoms temperature (about 150 MW over the 85 s
slug, the thread's estimate), the heat coming from a ledger line and not from the world. Law L0: a column's product
leaves at its account's energy.

- `PortPlan`: an optional specific enthalpy (J/mol; none: by temperature, as today). `outletPlan` gives the account's
  energy over its moles, both as of the fence, the fluid part alone (as `PortAccounts.debit` shares it).
- `PassiveStepSolver.ports`: where it is set, `equilibriumPH` (the table's temperature as the first guess) in place
  of `flashTP`, on the worker. A flash that does not converge falls back to the temperature (a rejected trial, never
  a hold). Without the enthalpy: bit for bit today's. The lines are outside the solute batch's changes to this hot
  file (checked: its hunks are at 132, 1113, 1293 to 1347, 1487, 1608 and 1922 to 1965; `ports` is at 1547).
- `credit` and the water rule stay: the residual, now the flash's tolerance, is still booked, so the ledger is exact.
- Stills and Converters keep their temperature plans.
- Ruled out: the enthalpy only while the account is off the table (a special case); booking the difference and
  testing the account alone (the slug would be heated from nothing).
- Tests: a `ports` case (a plan with an enthalpy gives a generator at that enthalpy; without it the state is today's
  bit for bit); `ColumnRunTest`'s steady cases, each moved reference with old and new values and its bound (expected:
  product temperatures within the flash's tolerance, the booked difference toward 0).

### Package 1, the pass (L1). Standard.

- `science/plant/column/Condition`: `PASSING` (rank NOTICE, word "passing").
- `ColumnCycle.step` (the overload with `Rooms`): a parameter `boolean refused`. At the trip (line 357 on): where
  `condenser > 0`, or `refused`, return `pass(...)` instead of `idle(...)`: `alpha` = min(1, feed held / demand,
  bottoms room / demand); `feed` = that share of the account; every steam account empty; every product empty but the
  table's `BOTTOMS` (found by `Port.BOTTOMS`), which gets the taken feed's moles and its energy; reboiler 0,
  pumparounds 0, condenser 0, solved condenser 0; conditions: `PASSING` with the trip's text, and `FEED_SHORT` or
  `PRODUCT_FULL` where they bound. `Step.tripped()` is then false. The older overloads pass `false`.
- `runtime/fluid/ColumnOperation.cycle` (line 827 on): hands `refused` = `r.refused != null && inside(...)`. Nothing
  else: the drift check, the request and `Refused` work as they do, so a refused cold state passes until the state
  leaves its bands and a solve succeeds.
- `ColumnStatus`: the phase of a passing cycle is RUNNING with the line `PASSING: <text>; the feed leaves as bottoms`.
  `EquipmentAlarm.of`: `PASSING` maps to `UPSET` (the column makes no products). The Operation tab's key in
  `OperationColumn`, the English lines.
- Tests: `ColumnCycleTest` (a 300 K feed on a 638 K table passes: moles and energy of the feed equal the bottoms',
  nothing else moves, exact; a full bottoms bounds it; a refused state passes a non-heating trip; a non-refused
  non-heating trip holds as before; a thousand cycles of a feed alternating cold and hot close components and
  energy). `ColumnRunTest`: a column started on a cold feed delivers bottoms at the feed's temperature within 1 K,
  then runs when the feed turns hot, with no latch.
- Pitfalls: the bottoms outlet must emit the account's energy, not the table's bottoms temperature: check
  `PortAccounts.debit` and the outlet's booking, and say so in the report. The product order is the table's. A pass
  with the steam valve open fills the steam account: its valve closes, which is intended.

### Package 2, the pumparound's duty (L2 to L4). Hard.

- `ColumnCycle.step`: remove the `PUMPAROUND_SHORT` limit (lines 300 to 306). After `fed` is known: `pa[j] =
  max(set point per feed x fed, −max(0, rooms.pumparound(j)))` (column sign, negative; the set point, not the
  table's relaxed duty, or the loop could never climb back: corrected in the build); where capped, a NOTICE
  `PUMPAROUND_SHORT` (its rank moves from LIMIT to NOTICE; the constant, its word and its alarm stay) with the share
  taken. The trip compares `condenser` with `solved + Σ (table duty − booked duty)`.
- `DriftBands`: `PUMPAROUND` = 0.05; `Trigger.PUMPAROUND`, checked after `STEAM_RATIO`; `check` reads the step's
  pumparounds per fed mole against the table's.
- `ColumnSolve.Job`/`run`: the job carries the pumparound duties per feed to solve at (the engine's relaxed values);
  `run` uses them in place of `setpoints.pumparoundsPerFeed()` (line 352). `SplitTable.Reference` carries them, so
  `Setpoints.intensivelyDiffers` keeps reading the set points and no SETPOINT solve loops.
- `ColumnOperation.request`: for every trigger but START and SETPOINT, each duty = `table + 0.5 (booked − table)`,
  capped by the set point, floored at 1e-6 of it; START and SETPOINT solve at the set points. A refused `PUMPAROUND`
  solve stores the state as any refused solve does.
- State: the table's reference gains the solved pumparound duties; an older state without them reads as solved at
  its set points, which is what it was (corrected in the build: nothing is dropped; state format 53 is this branch's
  own; `FORMATS.md` row in the same commit).
- Tests: `ColumnCycleTest` (a room of half the duty books half, takes the whole feed, closes energy; the trip does
  not fire on a short pumparound alone; the design case is bit for bit as before); `ColumnSolveTest` (a table solved
  at half the pumparound duties: draw temperatures up, condenser per feed up, the closure exact); `ColumnRunTest` (a
  pumparound cooler whose cold side cannot take the duty: the column keeps its feed, reads the notice, re-solves, and
  settles within 5 % in at most 6 solves; no reference of an existing case moves).
- Pitfalls: `ColumnCycleTest`'s present PUMPAROUND_SHORT cases change meaning: rewrite them, state old and new. The
  condenser's water room is still read at the table's water duty per feed, so a condenser asked for more than its
  water takes cuts the feed (`COOLING_SHORT`): intended, say it in the notice. Rule A and B for steam are untouched.

### Package 3, the cooler's stream (L5). Hard.

- `ColumnSolve.run`: per pumparound, `HeatCurve.sample` of the draw tray's liquid (V3's inspection node of the draw
  tray, as `airArea` reads the overhead at line 493) at its pressure from 300 K to the draw temperature, default
  films; the `Solved` outcome carries the curves beside the table (memory only, D6).
- `ExchangerRelay.Read.column`: an optional `Stream` of the partner; `ExchangerRelay.column` bounds the shift by the
  heat `wall(own stream, partner stream, start)` moves over the slice, beside the ceiling. Null keeps today's law
  (the condenser's water, the reboiler).
- `ColumnOperation.heatRead` (line 1267): for a pumparound side with a curve, the stream: inlet at
  `curve.enthalpyAt(draw temperature)`, mass flow from D3, the held side's `HeatSide` as its law, counterflow.
- Tests: `ExchangerRelayTest` (a column read with a stream: an oversized surface gives the ceiling's result; an
  undersized one gives UA x LMTD within 2 %; no stream: bit for bit today's); `ColumnRunTest` (the review plant's
  first cooler at 300 K crude takes its set duty; with a tenth of its surface it takes less and the column settles
  as in package 2).
- Pitfall: a slice must not depend on when the job runs: every read is as of the fence, as today's.

### Package 4, texts and the tab. Standard.

The Operation tab shows each pumparound's duty asked and taken; the notices' English lines; the code guide's
`column-operation.md` and `column-run.md`; `CHANGELOG` at the merge.

### Package 5, a draw share (later, the owner's word). Hard.

A solve that fails at the set draws is tried at a share of them (review, section 5, T1). Not in this build.

### The check in the world

After packages 1 to 3: `./gradlew runReviewWorldServer -PreviewBatch=review-operation`: the full-scale crude unit
reads PASSING for about 85 s, then RUNNING, with no bypass in the rig.

## 6. Ruled out

- Marching the trays in time: the column stays one solve and a table.
- A share ladder for the pumparounds: their duty follows the cooler (section 2's loop).
- Circulating mass inside V3 (`V3PumparoundSpec` stays a duty): a solver feature of its own.
- A flash table under the solver's reach (review T2): the pass covers it; later if wanted.
- A start-up bypass in the review rig: the pass removes its cause.

## 7. Done

Packages 1 to 4 committed; the selected classes pass; every moved reference listed with old and new values (expected:
`ColumnCycleTest`'s PUMPAROUND_SHORT cases and nothing at a design point); the review world starts without a bypass.
