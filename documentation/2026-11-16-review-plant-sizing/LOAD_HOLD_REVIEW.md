# The crude island's round-deadline hold: review (2026-11-17)

Diagnosis only; nothing under `src/main` changed. Branch `claude/start-hold-7f00cb` after the merge of `fa33d0241`.
Evidence: the main thread's client log of `review-plant-drive-flow` (copied to `tmp/load-hold/client-latest.log`), and
the save's fluid checkpoint, loaded and run by `tools/load-hold/LoadHoldScratchTest.java` (counters, no timing).

## Summary

**Expected.** Island 1402 is the crude charge line: a generator at 150 kPa, P-101, four exchanger sides, two fired
coils, the column-sensor flow valve, the column's inlet (22 nodes, 22 connections, 20 compounds). Between two jobs
only small things move: the column's feed target, the pump's speed, the heat sides' reads. A job that starts from the
committed interval costs about 20 to 32 residual evaluations and 760 flashes, measured on the saved state. That fits
easily within the 2 s budget, and a 1-tick slice should never fail.

**What the code did.** P-101's drive regulates the pump's flow (`ShaftTrain.Mode.REGULATE_FLOW`, the drive-flow build).
`ShaftTrains.plans` gives each job the drive's current speed share (`ShaftTrains.java:677`), and `ShaftPlan.apply` writes
it into the pump's `FlowControl.Machine` (`ShaftPlan.java:136`). That speed is part of the connection's identity
(`PassiveNetwork.Pipe.Identity`, the `control` field). `Identity.line()` excuses a flow valve's set point and a turbine's
target, but not a machine's speed (`PassiveNetwork.java:298-311`). So whenever the speed differs from the committed
interval's, `replayStart` finds `sameLines` false (`PassiveIntervalSolver.java:474-476`). The job then starts with no
carried point: from the balance, as a fresh solver does. On this island that costs 100 to 500 times more:

| Start of the job (island 1402, 5.6 s slice) | residual evaluations | flashes | passes | result |
|---|---|---|---|---|
| committed interval, same speed (state at 9961 / at 13400) | 20 / 32 | 755 / 775 | 2 / 2 | accepted |
| same graph, fresh solver (no committed interval) | 2659 / 3960 | 6985 / 7829 | 22 / 31 | accepted, 1 rejected substep at 9961 |
| committed interval, pump speed x1.001 (the shaft deadband) | 4015 / 6431 | 5831 / 14717 | 20 / 61 | accepted (2 rejected at 13400) |
| committed interval, pump speed x1.01 | 8158 / 5450 | 18087 / 7061 | 34 / 31 | accepted after 5 rejected substeps at 9961 |
| committed interval, pump speed x1.05 | 10872 / 2728 | 39834 / 7860 | 79 / 33 | accepted after 13 rejected substeps at 9961 |
| fresh, 0.2 s slice (one tick at factor 4) | 1380 / 3550 | 4643 / 11144 | 11 / 42 | a 1-tick slice is no cheaper |

In the save before the last (pack 3, generation 30, committed 9961, status "HELD: round deadline") P-101 stands at
speed **0.686** with 154.63 kg/s. The column's valve regulates at the column's target of 154.63 kg/s, while the drive
holds a set flow of 0.19357 m³/s, about 160 kg/s. The valve keeps the flow below the set flow, so at each decision the
drive raises the speed, by up to 0.2 of rated (`ShaftTrain.regulated`, 216-226). In the last save (committed 13400)
the speed is exactly **1.0**, the drive's top: the speed no longer moves, and every job continues cheaply.

**The answer, in order of the log:** while the drive climbed from about 0.69 to 1.0, every dispatched job carried a
new speed, lost the committed start, and ran the balance-start path (thousands of residual evaluations, a long
active-set climb, rejected substeps). Some of those solves finished within 2 s and were accepted (9609 to 9769 to 9929
to 9961). The rest passed 2 s and closed as "round deadline". After a hold nothing books, so the drive takes no
decision, and every retry repeats the same costly solve at the same new speed. A shorter slice does not help: the cost
lies in the start, not the interval. The retries spread out (`IslandCoordinator.hold`, 806-847) until one fitted
within 2 s. Once the drive reached 1.0 the island ran normally. The reload at 22:47:39 put the island back at 9961
with the drive's new speed, so the hold repeated at 22:47:51.

## Questions

1. **"round deadline"** (`IslandCoordinator.missing`, 751-755): the island's job had no terminal result when its round
   closed, 2 s (`wallBudgetMilliseconds`) after admission (724), or its result arrived later than that (740). The
   worker's own check throws at the same 2 s (`ProcessSolveJobs` 64-71), so this is a solve that ran 2 s or longer;
   its late answer arrives after the round has closed. A hold commits nothing. The island gets a fresh `RetainedSolver`,
   its slice halves down to 1 tick, then its retries are deferred by `cadence << min(streak, 6)` (the log's growing
   gaps). The next job replays the committed interval (713; `RetainedSolver` 64). The checkpoint keeps `lastResult`,
   so after a load the island continues the same way. S2 restores `carry` inside one solve only, so it plays no part.
   **Correction to my first message:** workers=1 adds no queue time. A fluid job is admitted only onto a free worker
   (`MinecraftFluidRuntime` 28) on the reserved lane, which never queues (`BoundedCpuSolveService` 124-127, 155-167).
2. **9609 to 9961, then stuck:** the drive's climb above. An accepted slice books, the drive steps, the next job has
   another speed. Whether a fresh-path solve passes 2 s depends on its counters (2700 to 11000 residual evaluations,
   and the trial at speed 0.999 from 0.686 failed outright after 14818 residual evaluations and 41315 flashes, its
   substep refinement exhausted) and on the machine's load. The 1-tick floor cannot
   lower that cost.
3. **At the deadline** the solve is on the balance-start path. The balance starts the junctions. Then come 20 to 80
   active-set passes with 1200 to 5500 Newton backtracks and 120 to 870 Jacobian builds, and 1 to 13 rejected
   substeps halving the step (reasons: line search stalled, a non-finite matrix, a phase/device cycle). It is not
   E1's pair cycling; the pump stays on `MOVER_LAW` in every trial. Property calls make up the cost: 90 000 to 570 000
   state calls against 2 900 carried.
4. **Fresh against carried:** a 100- to 500-fold difference (table). The hold needs the carried start to be lost. In
   the world loop (`EventPathWorld`, no drive) the 9961 state advances 28 ticks per job at 20 residual evaluations,
   each job accepted.
5. **workers=1:** not a cause (question 1). Note: a result's lateness is judged when the server thread drains it
   (740), so a slow server tick can make a quick result late. The log shows no such case.

**Reproduction.** (a), a server on the saved world, is not possible: `runReviewWorldServer` builds the plants in
GameTest structures and opens no save (`build.gradle` 130-137). (b) was used: the copied save loads EXACT through
`FluidSavedData.read` into `EventPathWorld` at factor 4. Island 1402 at 9961 was decoded from pack 3 with the final
core's string table. Not reproduced: the client's wall clock (the test's coordinator clock is constant), the drive
train (the test world has no shafts, so the speed is fixed), and the column's port plans (`ports` empty). Measured
instead: the cost of one job under each speed step, as tabled.

## Options

**A (proposed): the committed point carries across a planned speed.** `Identity.line()` (`PassiveNetwork.java:308`)
treats a `Machine`'s speed as it treats a flow valve's set point (speed taken out of the line, as `ShaftPlan.planned` /
`Machine.sameButSpeed` already decide for a candidate), and likewise a compressor's displacement and a governor valve's
target (`ShaftPlan.planned`'s other cases). Physics: the affinity laws move the root by the speed step; at 0.2 of
rated the flow changes by up to 20 %, a step the pass loop takes from the committed modes today when a flow valve's
target moves as much (StartHoldTest's crossing). *Changes:* one method and its callers' meaning ("same lines" = same
structure, plans aside). *Removes:* the balance-start path for every driven machine whose speed moves, and the
round-deadline holds it causes. *Could move:* carried-start counters (pass counts) in tests with a speed change:
`ShaftCouplingTest`, the rotating-machinery and drive-flow tests, any `HoldClassWorldsTest` world with a train. Results
should stay within tolerance (same roots), but the pass counts will move. *Test:* a `LoadHoldTest` built from this
probe's line. The 9961 job at speed x1.001, x1.01 and x1.2 is accepted from the committed interval, under 60
residual evaluations at x1.001 (today 4015). A drive in `REGULATE_FLOW` behind a regulating valve climbs to its top
with no hold in `EventPathWorld`.

**B: a deadband on the planned speed.** `ShaftPlan.apply` leaves the machine's speed unchanged within
`ShaftTrain.SPEED_DEADBAND` (1e-3), as it does for fuel and governor targets. *Removes:* the identity changes smaller
than 0.1 %. It does not fix the hold: a 0.1 % step still costs 4015 here, and a regulating drive steps up to 20 %. A
complement to A at most.

**C: the drive regulates against a valve that holds the same flow.** The drive's set flow (160 kg/s) above the
column's target (154.6 kg/s) makes the drive climb to its top while the valve throttles. That is the rig's set-up, not
the solver's defect. Changing it (the set flow at the column's target, or the valve fully open) would shorten the
climb but would not stop a drive's speed change from costing a balance start. A rig question for the owner after A.

**Not proposed:** a longer wall budget (it moves the edge, not the cost); a carried start on any changed control (a
changed flow resistance, a new connection or a heat side's shape are real structure changes).

## Small finding: which outlet "Limited: outlet" means

`OperationColumn.status` (`client/gui/common/OperationColumn.java:216-220`) formats `status.limited` with the limit's
name and the feed share only. The binding condition already carries the outlet: `ColumnCycle` 299 sets
`Condition.Active(PRODUCT_FULL, alphaRoom, fullest, "")`, `fullest` being the product index (and 415 the bottoms).
`OperationColumn.member` (301-310) already turns it into "Product %s" for the conditions table. Form: append
`member(binding, t)` when `binding.index() >= 0` ("Limited: outlet (Product 2), 63 % of the feed"), with an `en_us`
key change for the batch's translation commit. Which outlet it was here cannot be told from the save: the column's
conditions are recomputed each cycle and not stored in the fluid checkpoint.

## Not verified

The client's wall times (not recorded, by the rules); the drive's mode read from the save (inferred from the pump's
set flow 0.19357 m³/s and the drive-flow build on this branch); the drive's speed between 9961 and 13400; any island
besides 1402.

## Built (2026-11-17)

Owner: "you need to fix the control system"; "Yes, build both" (option A and the drive's trim law), with the outlet text.
Branch `claude/start-hold-7f00cb` after the merge of `claude/column-testing-sizing-26b5b9` (`e2ebe75e8`, merge `f3839a262`).

| Commit | What it does |
|---|---|
| `a333e7b55` A | `PassiveNetwork.Pipe.Identity.sameLine` (298-305): beside a flow valve's set point and a column's boundary plan, a shaft train's plan on a mover leaves the line the same, by the existing predicate `ShaftPlan.planned` (a machine's speed through `Machine.sameButSpeed`, a frame's displacement, a governor valve's target, a failed pump's switch). `replayStart`'s `sameLines` now holds across a drive's speed step, and the job continues from the committed interval. `LoadHoldTest`. |
| `58d4d5f34` trim | The drive trims the valve (owner D4 of the pump-valve work). `ShaftTrains.booked` gives a machine's duty its booked speed share and the drop of a flow valve (mass or volume flow, any sensor, not pressure-controlled) that regulated in the slice and is in the pump's series (`ControlConstraints.inSeries`, now public), while the measured flow stands at or below the set flow (`seriesValveDrop`: the valve's `PipeObservation.valveDrop`, Pa, its node side against its line side, at the slice's end). `Duty.trimSpeed`: `SpeedControl.requiredSpeed(law, Q_measured, (rise - drop)/(1 - VALVE_TRIM_MARGIN), ...)` capped at the booked speed, the rise being the pump connection's own `endpointHeads` (ρ g H, Pa). At the decision a finite trim sets the request (`requestedRpm`, the path `driver.atSpeed` takes), at most `LARGEST_STEP` down, never up, a move inside `SPEED_DEADBAND` held, and the flow secant is skipped for that decision (`Measure.measured` NaN, which `ShaftTrain.regulated` 219 already reads as "the set speed"; `run.measured` and the saved `previousMeasured` stay the flow). No other place decides the speed; `ShaftTrain` and `SpeedControl.regulate` are unchanged but the constant `SpeedControl.VALVE_TRIM_MARGIN = 0.05`. |
| `04bc5bb7b` outlet | `OperationColumn.status` (216-223): a binding condition with a member index reads `status.limited_member`, "Limited: outlet (Product 3), 45 % of the feed", the member named by `member()` as in the conditions table. New key in `en_us.json` only. The width gate of `OperationColumnTest` sweeps every limit with a two-digit member and rule A's note; every line fits at every width. |

**Readers of `sameLine` / `sameLines`** and what they now treat as the same line across a planned speed: the interval
start (`PassiveIntervalSolver.replayStart` 474: the committed interval carries); the carried point and the backward Euler
warm flags (`PassiveStepSolver` 282, 422, 1762); the opening memory and the one-way closure seeding (`OneWayClosures`
209); a replaced island carrying its junction states and interval (`TopologyChange` 111, `PassiveNetwork.withJunctionStates`
118). Each is a warm start only. A certificate and a STEADY replay do not read `sameLines`: a speed change past the
train's deadband wakes the driven island (`ShaftTrains.decide` → `IslandCoordinator.shaftsMoved` → `toWake`), and
`ExchangerCoupling` ends its certificate there. Candidates are still checked by the full control (`IslandCoordinator.sameControl`,
which already excused `ShaftPlan.planned`); workspace keys read the full identity.

**Measured, option A** (counters; flows and pressures of `LoadHoldTest` equal to the fresh solve's within 1e-6 relative
and 1 Pa):

| Job, 5.6 s slice, from the committed interval | before | after |
|---|---|---|
| 9961 state, pump speed x1.001 | 4015 residual evaluations | 20 |
| 9961 state, x1.01 | 8158, 5 rejected substeps | 21 |
| 9961 state, x1.2 | (x1.05: 10872, 13 rejected) | 165 |
| final save (speed 1.0), x1.001 | 6431 | 1224 (17 passes, 1 rejected: a speed above the drive's top, which no real drive reaches) |
| final save, x1.01 / x1.2 | 5450 / not run | 119 / 75 |
| `LoadHoldTest` (StartHoldTest's line, 1 s), x1.001 / x1.01 / x1.2 | fresh path: 100 / 100 / 111 | 4 / 6 / 10 |

`LoadHoldTest` uses StartHoldTest's charge line, not the saved 9961 state: a saved checkpoint loads exactly only under
its own state format, which the solute batch's merge renumbers, so a test on it would stop loading. The 9961 numbers
come from `tools/load-hold/` on the copied save.

**Measured, the trim** (`ShaftCouplingTest`, the brief's pump `CentrifugalPump(0.01 m³/s, 50 m, 0.7)` at 2950 rpm, a
void 31.92 m above the supply, a valve holding 8.81 L/s (8.784 kg/s), set flow 15 L/s): start-up to 0.885 with the valve
throttling 70.7 kPa (0.185 of the rise); the next decision 0.8304, rise 328.8 kPa (33.60 m), drop 16.44 kPa (1.68 m),
drop/rise 0.0500; held for every later decision. Target raised to 12 L/s: the valve opens (9.79 kg/s), the secant
raises the speed to 0.935, the valve regulates, the trim settles at 0.888 with drop/rise 0.0500. Set flow 6 L/s below a
15 L/s target: the drive holds its set flow, the valve open. No hold in either.

**On paper at 9961** (the first live result of island 1402 from the 9961 state, the speed held at 0.686): flow 154.63
kg/s = 0.17881 m³/s, under the set flow 0.19357; P-101's rise 642.3 kPa (75.7 m); the column valve's drop 270.1 kPa,
0.42 of the rise. The trim asks for 0.558, a step of −0.128, inside one decision; the drive then holds. From the final
save's state (speed 1.0, the valve dropping 1094.8 kPa, 0.74 of the rise) it asks for 0.564: three decisions of at most
0.2. The probe world has no shafts, so the law runs in `ShaftCouplingTest`, not on the saved island.

**Not built, reported: `REGULATE_PRESSURE`.** A pressure-regulating drive meets the same conflict behind a valve that
holds the same pressure lower than the drive's set pressure (a back-pressure or pressure-source control valve on its
discharge): the drive measures a pressure under its set point at every decision and climbs to its top while the valve
throttles. The flow trim does not cover it.

**Tests** (`affected.mjs select --since f3839a262` after `testClasses`; one run of the picks through
`record.init.gradle`, `--max-workers=4`; LanguageFilesTest and UnitTextTest left out): test 401 classes, 2378 tests,
1 failure, the expected `SizingPageTest.theDoorFitsInEveryViewForEveryItemInChinese`; holdGates 94 of 94;
equipmentGates 76 of 76; columnGates up to date. `OperationColumnTest` 10 of 10 and `ShaftCouplingTest` 12 of 12 run
whole (their left-out methods included). `fluidSolverRegression` (due: `PassiveNetwork` changed): chain-100 exact, max deviation 0 in moles, temperature, phase fraction and flow.

**Moved references.** `OperationColumnTest.theStatusReadsTheBindingCondition`: rule A's line "Limited: steam, 30 % of
the feed, every input cut" now reads "Limited: steam (Steam 1), 30 % of the feed, every input cut" (the binding steam
inlet has index 0). No flow or pressure moved. Pass counts in tests with a speed change were not compared; they are
expected to fall where a speed step used to start from the balance (`ShaftCouplingTest`, the drive-flow and
rotating-machinery tests).

**Line ranges in files the solute batch also edits** (`claude/solute-reaction-system-01ef6c`): `ShaftTrains` 145-147,
152-155, 168-170, 174, 192-204 (the `Duty` fields, constructor, `machine`, `frame`, `trimSpeed`), 793-794 (the booking),
857-874 (`seriesValveDrop`), 921, 931-941 (the decision), 948 (the measure); `PassiveNetwork` 298-305 (`sameLine`);
`en_us.json` one key after `status.limited`.

**Not verified:** the review world (the main thread runs it); fluid GameTests and the campaign, which `affected.mjs`
marks due (`ShaftTrains` changed) and which belong to the final lane.
