# Column failure modes and control: execution plan

Written 2026-11-10 for Opus execution, one stage per sub-thread, in order. Each executor reads sections 1 and 2 and its own stage. Background: [COLUMN_FAILURE_MODES_REVIEW.md](COLUMN_FAILURE_MODES_REVIEW.md) (findings F1 to F12) and [COLUMN_FAILURE_MODES_PLAN.md](COLUMN_FAILURE_MODES_PLAN.md) (the design). Where this file and the plan differ, this file is right.

## 1. Decisions in force

The owner took every recommended option on 2026-11-10 ("commence as recommended") and added the test world.

| # | Decision |
|---|---|
| D1 | A short reboiler or pumparound utility cuts the feed (LIMIT). No solve. |
| D2 | Flooding limits the feed. |
| D3 | Steam keeps rule B, then rule A. |
| D4 | A control loop may set a column's feed rate only. |
| D5 | The operating pressure is not a set point. |
| D6 | A stale table raises OFF TABLE and keeps running; the trip stays at 50 %. |
| D7 | Member power is in this batch, after the utilities. |
| D8 | One batch, in the order of the stages below. |
| D9 | Dropped by the owner on 2026-11-10 ("if too expensive just use the original plan"): no downcomer backup (W3.4). Flooding is Fair's fraction alone. The stage 2 draft moved the crude sample's column from 8 m to 10 m. |
| D10 | A test world with a full-size column, tested by script at the end and left for the owner's manual test (stage 5). |

Changes against the plan, decided here to keep the work small:

- V3's advisories arrive as one NOTICE, `ADVISORY`, with V3's own bounded texts. No constant per advisory, unless V3 gives a typed source.
- No per-port `level` channel (plan 4.4): an account has no geometry for a level loop. The tab shows the fill.
- The column page is on the DCS only. The column's own screen is its Operation tab.
- Requested set points stay the unit's fact. The cycle runs on the accepted ones, `table.reference().setpoints()`, with the requested feed rate. The engine never rewrites a set point.

## 2. Rules for every stage

- **Worktree and branch.** Work in `D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-failure-mode-control-db2da5` on `claude/distillation-failure-mode-control-db2da5`. One writer at a time. No push, no merge, no merge preparation, no final lane: those wait for the owner.
- **Read first.** `AGENTS.md` whole; `documentation/reference/code-guide/column-operation.md`; for stage 1 and 2 also `wired-control-power.md` and `process-control.md`; for stage 3 `heat-exchangers.md`; for stage 4 `rotating-machinery.md`; for stage 5 `verification.md` and `machine-assemblies.md`.
- **AGENTS.md sections that bind this work:** "Saves and data formats" (facts append-only, a default equal to the old behaviour, a corpus image at every facts change), "Engine schedule and player-facing updates" (a view is a read; commands are queued; nothing on the tick), "Physics and defects", "Gradle, measurement and verification" with "No repeated tests", "GUI and in-game checks", "Translations" (only `en_us.json`), "Git and worktrees".
- **GUI.** Before any change under `client/gui/**`, read `.claude/skills/gui-creation/SKILL.md` and the reference it names. Judge by gates and the software renders of `tools/heat-gui-renders/`. Start no dev client.
- **Gradle.** Targeted runs only: `./gradlew test --tests '<class>' --max-workers 4` (and `columnGates`, `equipmentGates` with `--tests` where a class lives there). At most two Gradle invocations on the machine; never while a dev client runs. Do not run `test`, `slowGates` or `check` whole. Do not run `LanguageFilesTest` or `UnitTextTest`.
- **Invariants.** These stay green after every stage: `ColumnCycleTest`, `ColumnSolveTest`, `ColumnRunTest` (above all `aColumnsProductsDoNotDependOnWhenItsIslandsJobsComplete` and the conservation tests), `ColumnSuspensionTest`, `RegulatingValveHoldTest`, `FluidFactsCompatTest`. A test whose expectation the stage changes on purpose is edited and named in the report.
- **Status words.** Keep the engine's existing status text for existing cases, byte for byte: `RUNNING`, `STARTING: the first solve`, `LIMITED: feed|steam|outlet|condenser, N %`, `UPSET: …`, `RE-SOLVING: <trigger>; …`, `NOT READY: …`, the fans' and water's suffixes. New cases add words.
- **Commits.** One per work package, explicit paths, message ending `Co-Authored-By: Claude <noreply@anthropic.com>`. Before the last commit add one history line for the stage to this batch's `README.md`.
- **Stop and report, do not improvise,** when: a fact field would change meaning or leave; a conservation test fails for a cause outside the stage's code; the physics needs an assumption this file does not state; a step needs a new unknown or equation in `PassiveStepSolver`; a check needs the game running.
- **Report.** At most 50 lines: commits (hash, subject), each test command with its counts, what differs from this plan and why, open questions.

## 3. Stage 1: the condition record, the solve policy, the alarms

Goal: one typed record of what a column's cycle found; fewer solves after a failure; column alarms on the control system. No fact changes.

**W1. Condition record**

1. New `science/column/operation/Condition.java`: an enum with a `Rank` (`NOT_READY`, `NOTICE`, `LIMIT`, `OFF_TABLE`, `TRIP`, in rising order) and a status word. Constants: `FEED_SHORT`, `STEAM_SHORT`, `PRODUCT_FULL`, `COOLING_SHORT`, `HEATING_SHORT`, `PUMPAROUND_SHORT`, `FLOODING`, `NO_POWER` (LIMIT); `OFF_TABLE`, `SOLVE_REFUSED` (OFF_TABLE); `CLOSURE` (TRIP); `SETPOINT_REFUSED`, `ADVISORY`, `FEED_WATER_ROUTED`, `UNMETERED` (NOTICE). A record `Condition.Active(condition, value, index, text)`: the share or the band value, the port or member index (−1: none), a bounded free text (≤ 256, empty where the constant says it all).
2. `ColumnCycle.Step` carries `List<Active> conditions` and `binding()` (the LIMIT with the lowest share, or the TRIP). `ColumnCycle.Limit` and `Step.trip` leave; every reader moves to the list.
3. `ColumnOperation.Run` keeps the last cycle's full list: the step's, plus the solve's (`OFF_TABLE` with its trigger while a solve pends, `SOLVE_REFUSED`, `SETPOINT_REFUSED`) and the notices that are `Run.note` and `Run.unmetered` today. Those two fields leave.
4. One pure formatter in `science/column/operation` makes the status line from the phase and the list; `ColumnOperation.cycle` lines 552 to 562 call it. The phase is the highest rank present (`OperationView.Phase` unchanged).
5. `OperationView`: `limit`, `ruleA` and `failed` leave; `conditions` comes. `AssemblyNetwork`'s codec follows and the assembly protocol steps up one. `OperationColumn.status` reads the binding condition; lang keys in `en_us.json` only.
6. Start refusals: one enum `Refusal` in `science/column/operation` holds every text of `ColumnStarts`, `FluidWorldAuthority.startColumn` and `ColumnOperation` (the `NOT READY:` cases). The methods keep returning the text.
7. `ColumnOperation.instrument` answers on a trip: flow channels 0, pressures and temperatures from the table's probes (today: null, which reads `NO SIGNAL`).

**W2. Solve policy**

1. The cycle takes steam ratios, and `ColumnSolve.Job` for a drift solve takes its set points, from the accepted set points (`table.reference().setpoints()`), with the unit's feed rate. Only a SETPOINT or START solve reads the unit's requested set points.
2. A failed SETPOINT solve: remember the refused set points and the reason in the run (memory, not saved); raise `SETPOINT_REFUSED`; ask no SETPOINT solve while the unit's set points equal the refused ones. `OperationView` carries the accepted set points beside the requested ones where they differ.
3. A failed drift solve: remember the refused state (a `SplitTable.Reference` built from the `Taken`); raise `SOLVE_REFUSED`; ask no solve while `DriftBands` finds the state taken inside the bands of the refused reference (add an overload of `check` on a `Reference`).
4. `ColumnSolve.Failed` gains `transientFailure`: true for a deadline, a full queue, a worker exception, a shutdown (find where `ProcessSolveServices.submitColumnUnit` and `Solves.submit` make them). A transient failure retries once after 600 ticks and is not a refusal.
5. A failed START: `NOT READY: <reason>`, the refused state kept as in 3; a new START solve when the feed leaves its bands, or after Stop and Start.
6. Rule A: set by a refused STEAM_RATIO solve as today. It clears when a cycle's steam is no longer short, or when the shortage leaves the bands of the refused state (then rule B tries once more).
7. `RETRY_TICKS`, `Run.failures` and the retry ladder leave.

**W6.1 Alarms**

1. `EquipmentAlarm`: add `LOW_FEED("LOW FEED")`, `LOW_STEAM("LOW STEAM")`, `HEATING_LIMITED("HEATING LIMITED")`, `OFF_TABLE("OFF TABLE")`, `UPSET("UPSET")`, and `COLUMN = [UPSET, OFF_TABLE, LOW_FEED, LOW_STEAM, BLOCKED, COOLING_LIMITED, HEATING_LIMITED, FLOODING, NO_POWER]`. The map from `Condition` to alarm lives in `runtime/fluid` (science imports no runtime class): FEED_SHORT→LOW_FEED, STEAM_SHORT→LOW_STEAM, PRODUCT_FULL→BLOCKED, COOLING_SHORT and PUMPAROUND_SHORT→COOLING_LIMITED, HEATING_SHORT→HEATING_LIMITED, FLOODING, NO_POWER, SOLVE_REFUSED→OFF_TABLE, CLOSURE→UPSET.
2. `FluidWorldAuthority.columnAlarms(master)`: a read of the run's last list. `BuiltInInputs.alarms` adds the channels for `Kind.COLUMN`; `raised` answers them from the binding's origin.

**Tests.** New or extended: `ColumnCycleTest` (the list and the binding for each existing limit and the trip), `ColumnRunTest` (a refused set point asks one solve and no second; the cycle keeps the accepted steam ratio; a refused drift state is not solved again inside its bands and is solved once outside; a transient failure retries once; channels answer on a trip), `AssemblyNetworkCodecTest`, `OperationColumnTest`, and a control test that a blocked product raises `alarm_blocked` on the column's host and in `ControlPublication`'s alarm list. Run each changed or new class and the invariants of section 2.

**Done:** the tests above green; `git grep "RETRY_TICKS\|ColumnCycle.Limit"` empty in `src/main`; three commits (W1, W2, W6.1).

## 4. Stage 2: flooding, the Operation tab, the DCS, the feed-rate loop

**W3. Flooding** (plan section 3a is the specification: items W3.1 to W3.4, tests T1 to T10 and the acceptance line. The steps here place them.)

1. W3.1: `ColumnSolve.solve` reads `success.result().trayHydraulics()`. `SplitTable` gains `floodPerFeed` (the solve's `maximumFloodFraction` over its feed rate in mol/s; 0: unknown, no limit), `floodTray`, `floodVapourLimited`, and for W3.3 the solved total dry drop and liquid head. `ColumnCycle.step`: share = 1 / (`floodPerFeed` × set feed rate); the lowest share binds as `FLOODING`, its value the share, its index the tray. T4 decides the linear rule: over 5 %, keep the flood fraction at two throughputs in the table and interpolate (no solve).
2. W3.2: a new NOTICE `HIGH_LOAD` from 85 % of flood at the rate taken; `alarm_flooding` only while the limit binds.
3. W3.3: the live pressure drop, dry × (alpha / the table's alpha)² + liquid, in `ColumnUnit.Probes`, on the tab and as the built-in channel `differential_pressure`. Port pressures do not move.
4. W3.4 (D9): downcomer backup in `V3TrayHydraulics` as a second measure; the flood fraction is the worse of the two; the free water's entrainment limit keeps Fair's fraction. It is arithmetic on an accepted state: if it moves any solved state or acceptance check, stop and report. List every sizing or example reading it moves (old, new) in the report, for the owner. Its tests load widely: run `V3TrayHydraulicsTest`, the `ColumnSizing` and sheet-sizing test classes, `FlowsheetExamplesTest` and the hold-gate sample-sizing classes by `--tests`.
5. `ColumnSolve.Solved` gains `advisories` (V3's bounded advisory texts, at most 4), which become `ADVISORY` notices.
6. State: `FluidFacts.columnState` carries the new table fields; an older state is dropped and solved again (T8).
7. Two fixes found in stage 1, in `ColumnCycle.step` and `ColumnOperation`: (a) every share is a share of the set demand: today the outlet's room is weighed against the feed available, so a short feed with a tight outlet can overfill a buffer; weigh it against a full take at the set demand, with a test (T3 covers flood and feed; add outlet and feed). (b) The water's heat-read ceiling and status use the accepted condenser temperature (`table.reference().setpoints()`), not the requested one.
8. A LIMIT is listed whenever its share is below 1, binding or not, and raises its alarm: that is intended.

**W7. Operation tab** (gui-creation skill; prototype sheets in `tools/heat-gui-renders/OperationPrototypes.java`)

1. A conditions table on the glass: rank colour, word, value, member. The status line keeps its place. The live pressure drop and the flood fraction of the worst tray beside the duties.
2. Beside each ratio entry its absolute value at the set feed: reboiler and pumparounds in MW, steam in kg/h, draws in kmol/h (client units).
3. Where requested and accepted set points differ: the accepted value and the refusal's reason on the entry.
4. Entries before the first run: `FluidWorldAuthority.editColumn` makes a stopped `ColumnUnit` (no stations, the design's set points changed by the edit) where the column has none; `startColumn` already keeps a stopped unit's set points when `compatible`. If `ColumnUnit` or `FluidFacts` refuses a stopped unit without stations, stop and report.
5. Gates: `OperationColumnTest` (no text cut at every width the page takes; the new rows), software renders in both themes, attached to the report as paths.

**W6.2 Column page on the DCS**

1. The DCS lists every column whose data socket its bus reaches (`ControlHosts`: `columnCells`, `hosts.wired`). Selecting one shows the Operation door: `OperationColumn.rows` and its glass, the same definition as the tab.
2. View: the cached `OperationView` (`FluidWorldAuthority.columnView`), sent on the control publication to menus that selected a column, replayed on open. Encode it with the assembly codec's view functions; do not write a second encoding.
3. Commands: a `ControlNetwork` payload (column host, START, STOP or APPLY, values) to `ControlService`, queued like a controller edit, checked for the data link, then `AssemblyService.start`, `stop` or `setpoints` at the binding's origin. The control protocol steps up one.
4. Tests: a control test that a DCS reaching a column lists it, that START, APPLY and STOP reach the unit, and that a DCS with no link is refused with `NO DATA LINK`.

**W6.3 Feed-rate loop**

1. A controller whose output is a column's host, or the feed boundary valve of a running column, is a column loop. `ControlLaw.decide` handles it before the `MISSING DEVICE` and `OUTPUT RESERVED` returns, as a cadenced output (its sensor may stand on another island).
2. FLOW sensor: planned feed = target mass flow over the feed port's molar mass. LEVEL sensor on a vessel upstream with action OUTLET: the volume flow of `InventoryControl.target`, as the valve's LEVEL case, turned to mass with the feed valve's pipe reading and to mol/s with the same molar mass. Other kinds: `SELECT FLOW OR LEVEL`.
3. `ControlPlans` keeps `columnFeed(unit)`. `ColumnOperation.cycle` and the inlet's plan in `plans` use `min(set feed rate, planned)` through `Setpoints.withFeedRate`; the saved set point does not change. No plan (controller off, held, no link): the set rate.
4. Tests: a `ControlLoopGameTests` case or a `ColumnRunTest` case: a flow loop holds the feed at its target below the set rate; switching the loop off returns the set rate; the unit's saved set points are unchanged.

**Done:** T1 to T9 green and T10 with its moved readings listed; the other tests green; commits W3.1 to W3.3 with the two fixes, W3.4, W7, W6.2, W6.3, in that order. If the stage cannot finish all of them, commit what is whole and report what is left.

## 5. Stage 3: reboiler and pumparound utilities (W4)

Goal: every duty of a running column is owed to a utility through the heat in transit, as the condenser's water is. This is the stage with a facts change.

1. **Find the sides.** `ColumnStarts.build` collects, as it does for the condenser, the heat sides of the `REBOILER` slot's units and of each `pa_cooler(i)` slot's units. `startColumn` keeps of each unit the side outside the column whose partner is inside.
2. **Facts.** `ColumnUnit` gains `utilities`: for the reboiler and for each pumparound, its sides (at most 6 each). Append-only in `FluidFacts` at the next facts schema, default none: a unit saved before the field keeps its duties free until its next Start. Add the corpus image under `src/test/resources/compat/fluid/` and its case in `FluidFactsCompatTest`.
3. **The table.** `SplitTable` gains each pumparound's draw-tray temperature (from the inspection); the bottoms product's temperature is there.
4. **The cycle.** `ColumnCycle.step` takes rooms in one record (condenser water, reboiler, each pumparound, the air's most feed). Each share is room over the duty of a full take; the conditions are `COOLING_SHORT`, `HEATING_SHORT`, `PUMPAROUND_SHORT` with its index. With no side for a duty, its room is unbounded (today's behaviour).
5. **The debt.** After a cycle, `ColumnOperation` owes each duty to its sides in equal shares (`coordinator.owe`): a pumparound's as the condenser's water (the side takes heat; ceiling = the draw-tray temperature less `APPROACH_KELVIN`); the reboiler's with the other sign (the side gives heat; floor = the bottoms temperature plus `APPROACH_KELVIN`). Room = `CONDENSER_HOLD_SECONDS` × |duty per feed| × design rate less the transit as of the fence, the rule of the condenser's room.
6. **The relay.** `ExchangerRelay.Read.column` and `ExchangerRelay.plan` take the heating case: a side that gives the transit over the slice, no more than keeps its outlet at the floor. `HeatUnit.Mode.COLUMN` extends to a kettle's bundle; its valve law is heat rate over (h(inlet) − h(floor)). If this needs a new unknown or equation in `PassiveStepSolver`, stop and report.
7. **Books.** With a utility, the reboiler's and pumparounds' heat is exchanged with the utility's island, not created or destroyed in the totals; the conservation tests must close with it.
8. **Rigs.** A plant whose coolers have no cooling water now reads `LIMITED: pumparound`. Give the shared rig of `ColumnOperationReviewWorld` and `ColumnOperationGameTests` water lines on its pumparound coolers, and `ColumnRunTest`'s plants where they start through `startColumn`.
9. Tests: `ColumnCycleTest` (each room), `ColumnRunTest` (a short reboiler utility limits and raises HEATING LIMITED; its heat closes against the utility's island; a short pumparound cooler limits; a unit saved without `utilities` runs as before), `FluidFactsCompatTest`, `ColumnOperationGameTests` under `runFluidGameTestServer -PfluidGameTestRunId=<a fresh id>` (a used id keeps a failed run's leftovers).

**Done:** tests green; commits: facts and Start, cycle and debt, relay, rigs.

## 6. Stage 4: member power (W5)

1. **Write the note first.** `MEMBER_POWER_NOTE.md` in this folder, at most 40 lines: how a held mover's driver and an air cooler's fan store are booked today (`ShaftTrains`, `ElectricStores`, `MachineView`), and the smallest change that books a column's members. Then build it.
2. **Fans.** Each cycle the column draws `fanPower × cycle seconds` from its air cooler members' stores. Stores empty: fan step 0, the feed limited to what natural draught holds (`airSurface / airArea[0]`), condition `NO_POWER` with the condenser's index.
3. **Pumps.** The reflux pump and each pumparound pump book their design shaft power × the cycle's share on their own train or store. A driver that cannot give it: the share is 0 and the condition is `NO_POWER` with the member's index. Under config `energy.machinesNeedPower`; with it off, nothing changes.
4. **No driver at all.** With `machinesNeedPower` on, a member pump with no driver refuses Start: `NOT READY: the <member> has no driver`. This is logged here as a decision the owner may veto.
4a. **The kettle's COLUMN mode on its heat screen** (left by stage 3): the heat screen offers the COLUMN mode for a kettle's tube side as it does for a condenser's water side, with hint and line texts that say "utility", not "water", where the side heats (gui-creation skill; `en_us.json` only; the heat GUI gates and a software render).
5. **Rigs.** Give the shared rig motors and a creative FE source, or set `machinesNeedPower` off in the plants that are not about power.
6. Tests: `ColumnRunTest` (fans without power limit to natural draught; a reflux pump without power stops the feed and raises NO POWER; power back resumes), `ColumnOperationGameTests` (fresh run id).

**Done:** the note; tests green; commits: fans, pumps, rigs.

## 7. Stage 5: the test world (W8)

Goal: one world with a full-size column plant, tested by script, then left running for the owner.

1. **Class.** `src/fluidGameTest/.../gametest/ColumnFailureModesReviewWorld.java`, `@GameTestHolder("icheme_review")`, batch `review-failure-modes`, run only by `runReviewWorldServer`. Reuse `ColumnOperationReviewWorld.Rig` by parameter (scale, condenser, pumps), do not copy it.
2. **Plant A, the crude column at full size.** The network's default column preset at scale 1 (about 8 m across, 40 trays, three draws, three pumparounds, sump steam), condenser `AIR_THEN_WATER`: air cooler banks, a water trim, the receiver, a reflux pump and three pumparound pumps each with a motor, three pumparound coolers on cooling water. Lines sized so no stream passes 100 m/s.
3. **Supplies, each behind a named shutoff valve.** Feed (a hot generator into a surge drum with a level sensor, then the feed valve), stripping steam, cooling water (one header, a branch and a valve for the trim and for each cooler), each product to a void at the atmosphere. A creative FE source with one conduit run for the pumps' motors and one for the fans. Names by `DeviceName` (`FEED SHUTOFF`, `CW TRIM`, `PA1 CW`, `FANS POWER`, …).
4. **Control.** A DCS console wired to the column's data socket and to one local controller; the controller on the surge drum's level sensor with the column as its output (the feed-rate loop), switched off at the start.
5. **Plant B, a reboiled column.** The smallest reboiled design the unit tests already solve (name it in the report), with a kettle on a hot utility line behind a shutoff valve, its own feed generator and voids. It is the test piece for HEATING LIMITED.
6. **Script.** After both columns read `RUNNING`, run each case, wait for the expected condition, log `review_failure case=<n> expected=<word> got=<words> share=<alpha> ticks=<t>`, restore, wait for `RUNNING`:

   | # | Action | Expected |
   |---|---|---|
   | 1 | none | RUNNING, no alarm |
   | 2 | close `FEED SHUTOFF` | LOW FEED |
   | 3 | close the steam valve | LOW STEAM (rule B, a STEAM_RATIO solve; rule A if it is refused) |
   | 4 | close one product valve | BLOCKED after the buffer fills |
   | 5 | close `CW TRIM` | COOLING LIMITED |
   | 6 | break `FANS POWER` | NO POWER, the feed at natural draught |
   | 7 | break the reflux pump's power | NO POWER, share 0 |
   | 8 | close `PA1 CW` | COOLING LIMITED, pumparound 1 |
   | 9 | apply a reflux ratio that floods | FLOODING, the feed cut |
   | 10 | apply a set point V3 refuses | SETPOINT REFUSED, exactly one solve asked |
   | 11 | DCS: STOP, START, APPLY the design's set points | the unit follows; a DCS without the cable is refused |
   | 12 | switch the level loop on, halve the surge drum's supply | the feed follows; the drum's level holds |
   | 13 | plant B: close the hot utility valve | HEATING LIMITED |

   A case that does not reach its expectation in its time fails the run with its log line; the plant stays standing.
7. **Gate-size case.** One test in `ColumnOperationGameTests` (`icheme_fluid_test`, a fresh `fluidGameTestRunId`): a blocked product raises `alarm_blocked` on a DCS menu's publication.
8. **Hand-over.** Run `./gradlew runReviewWorldServer` (alone; about an hour of game time at 20 TPS). Copy `run/review-world/world` to `run/saves/column-failure-modes` in this worktree. Write `MANUAL_TEST.md` in this folder: the launch command for this worktree (the owner starts the client), where the player spawns, each named valve, cable and screen with its coordinates (the rig logs them), the table above as actions for a player with the expected status and alarm and the time each takes, and how to restore. Add `tools/INDEX.md` or `verification.md` lines only where a tool or a lane changed.

**Done:** the script's 13 lines all as expected (attach them); the world in `run/saves/column-failure-modes`; `MANUAL_TEST.md`; commits: rig parameters, the world class, the gate case, the documents.

## 8. After stage 5

The main thread reads each report and checks the diff and the test results before the next stage starts. After stage 5 the owner tests by hand. The final lane, the translation commit, the code guide and the merge wait for the owner's word (`merge-prep` skill).
