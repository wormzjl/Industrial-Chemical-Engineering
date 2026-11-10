# Column failure modes and control: plan

Status: accepted 2026-11-10: the owner took the recommended option of every decision in section 7 and added a test world. The build order, the briefs and the changes of detail are in [EXECUTION_PLAN.md](EXECUTION_PLAN.md). Design only: no code, no Gradle run. Findings F1 to F12 are in [COLUMN_FAILURE_MODES_REVIEW.md](COLUMN_FAILURE_MODES_REVIEW.md). Owner decisions are in section 7.

## 1. One rule

Every failure of a running column is a **condition**: one enum constant with a class, a number and (where it has one) a port or member. A cycle yields the set of conditions that hold and the one that binds. The status line, the Operation tab, the alarm channels and the DCS all read that set. There are five classes, each with one consequence:

| Class | Consequence | Engine cost | Replaces |
|---|---|---|---|
| NOT READY | The column does not start. | None. | The start refusal strings (19 in three classes). |
| NOTICE | Nothing. Said on the status line. | None. | `Run.note`, `Run.unmetered`, the dropped V3 advisories. |
| LIMIT | The cycle takes a share `alpha` of the set feed. The table stays valid, because the column's equations are homogeneous in the extensive inputs. | Arithmetic. No solve. | `ColumnCycle.Limit`. |
| OFF TABLE | The state taken is outside the table's bands. One solve is asked; the old table runs meanwhile. | At most one V3 solve per 30 s (`SOLVE_SPACING`, unchanged). | `DriftBands.Trigger`, `Run.failed`. |
| TRIP | The cycle takes nothing. It clears itself when a cycle closes again. | None. | The two trip strings. |

This is the existing structure with its gaps closed, not a new layer: LIMIT is today's `Limit`, OFF TABLE is today's drift, TRIP is today's `UPSET`. The phase of `OperationView` is the highest class present.

**LIMIT is the default answer to every shortage.** A short supply, utility or capacity cuts the feed until it fits. This is what an ideal operator does, it conserves mass and energy by construction, and it costs no solve in either solver. The fluid network sees only what it sees today: a lower mass target on the boundary valves.

## 2. The conditions

| Condition | Class | Test, on data the cycle already holds | Alarm word |
|---|---|---|---|
| `FEED_SHORT` | LIMIT | feed account < demand (today) | LOW FEED |
| `STEAM_SHORT` | LIMIT or OFF TABLE | steam account < ratio × feed (today, rules A and B; decision D3) | LOW STEAM |
| `PRODUCT_FULL` | LIMIT | a product's room < its make (today) | BLOCKED |
| `COOLING_SHORT` | LIMIT | condenser water room or air face (today) | COOLING LIMITED |
| `HEATING_SHORT` | LIMIT | reboiler utility room < duty (new, W4) | HEATING LIMITED |
| `PUMPAROUND_SHORT` | LIMIT | a pumparound cooler's room < duty (new, W4) | COOLING LIMITED |
| `FLOODING` | LIMIT | `alpha` × table's flood per feed > 1 (new, W3) | FLOODING |
| `NO_POWER` | LIMIT | a member's electric store empty (new, W5) | NO POWER |
| `OFF_TABLE` | OFF TABLE | a drift band left; carries the trigger | (none while it solves) |
| `SOLVE_REFUSED` | OFF TABLE | the solve of the state taken failed; the old table runs | OFF TABLE |
| `SETPOINT_REFUSED` | NOTICE | the solve of the applied set points failed; the accepted ones stay (W2) | (none) |
| `CLOSURE` | TRIP | closure departs 50 %, or the condenser would heat (today) | UPSET |
| `WET_TRAY`, `CARRY_OVER`, `EXTRAPOLATED`, `FEED_WATER_ROUTED`, `UNMETERED` | NOTICE | carried from the accepted solve or the plan | (none) |

Nine alarm words: four are `EquipmentAlarm` constants that exist (BLOCKED, COOLING_LIMITED, FLOODING, NO_POWER), five are new (LOW_FEED, LOW_STEAM, HEATING_LIMITED, OFF_TABLE, UPSET).

## 3. Work packages

Each goes in the stage that owns it and removes what it makes unnecessary.

- **W1. The condition record.** `science/column/operation/Condition` (enum, saved nowhere: state). `ColumnCycle.Step` carries the set and the binding one; `ColumnOperation.cycle` adds the ones it knows (solve, notes). One formatter makes the status line. Removes: the string assembly of `cycle` (lines 552 to 562), `ColumnCycle.Limit`, `Step.trip` as text, `Run.note`, `Run.unmetered`, `OperationView.limit`/`ruleA`/`failed`. Start refusals become codes with a parameter. `ColumnOperation.instrument` keeps answering on a trip: flows 0, pressures and temperatures of the table (F2).
- **W2. Solve policy (F6, F7).** Lowers the solver load.
  - Set points are *requested* and *accepted*. Apply asks one solve. Accepted: they take force with the table. Failed: the accepted ones stay, `SETPOINT_REFUSED` says why until the next Apply. No retry. The tab shows both where they differ.
  - A failed drift solve remembers the state it refused. No solve is asked while the state taken stays inside the drift bands of a refused state. A failure of the worker (deadline, full queue, shutdown) is not a refusal: one retry after 600 ticks.
  - A failed START: `NOT READY`, one retry when the feed state leaves the bands of the refused one, or on a Start press.
  - Removes: `RETRY_TICKS`, `Run.failures`, `Run.retryAt`. Worst case today: 30 failed solves an hour for a column. After: one per distinct state.
- **W3. Flooding from the table (F3).** Fair's flood fraction is vapour velocity over a limit that depends only on intensive quantities, so at one table it is proportional to the feed taken (`ColumnSizing` scales it by its design flow the same way). `SplitTable` gains `floodPerFeed` = the solve's `maximumFloodFraction` over its feed rate, and its worst tray. The cycle: `alphaFlood = 1/(floodPerFeed × set rate)`. One multiplication, no solve. It can bind only after the operator raises reflux, steam, reboiler or a pumparound, since the feed is capped at the design rate; the THROUGHPUT band (0.2) bounds the error of the linear rule to the tray pressure drop's effect on vapour density.
- **W4. Reboiler and pumparound utilities (F4).** The condenser's mechanism, generalised: every duty of the column is owed to the partner side of its exchanger through the heat in transit (`IslandCoordinator.owe`, `ExchangerRelay.Read.column`), with a hold of 60 s. Room = hold × |duty per feed| × design rate − transit as of the fence. `ColumnCycle.step` takes one room per duty in place of `condenserRoom`. The kettle's tube side and each pumparound cooler's utility side stay in the network as the condenser's water side does; `HeatUnit.Mode.COLUMN` sets their valve where the station is complete; it is extended from an exchanger to a kettle's bundle, and for a heating utility the bound is a floor (the bottoms temperature plus the approach) in place of the water's ceiling. No new row in `PassiveStepSolver`: a column read is a slice input that exists. The reboiler heat is then conserved against a utility in place of booked from nothing.
- **W5. Member power (F5).** The air fans (`fanPower`, computed today), the reflux pump and the pumparound pumps (their design shaft power × `alpha`) draw from an electric store per member, as a fired heater's burners do (`ElectricStores`, kind `BURNERS`). An empty store: `NO_POWER`, `alpha` 0 for a pump, for the fans the feed their banks hold at natural draught (`airArea[0]`, in the table today). Under config `energy.machinesNeedPower`.
- **W6. Control system.** Section 4.
- **W7. Operation tab.** Section 5.

## 3a. Flooding: what W3 builds and how it is tested

A real flooded column does not settle: liquid accumulates, the pressure drop climbs steeply, separation collapses, and the operator cuts the vapour load. The tray model computes the flood fraction beside the pressure drop and feeds nothing back; the running column drops it. W3 builds the operator's answer and the signs that lead to it, all outside the V3 solve and the fluid network (the hydraulics run on an accepted state; the running column works from its table).

| Item | What | Where | Cost |
|---|---|---|---|
| W3.1 | Flood limit: the feed share is capped so the worst tray stays at or below 100 % | `SplitTable.floodPerFeed`, `ColumnCycle.step` | One multiplication per cycle |
| W3.2 | Notice `HIGH_LOAD` from 85 % of flood; condition and alarm `FLOODING` where the limit binds | `Condition`, `EquipmentAlarm.COLUMN` | A comparison |
| W3.3 | A live column pressure drop: the table keeps the solved total dry drop and liquid head; a cycle shows dry × (alpha / alpha of the table)² + liquid. On the tab, and a control-system channel `differential_pressure`. Port pressures do not move. | `SplitTable`, `ColumnUnit.Probes`, `BuiltInInputs` | Arithmetic |
| W3.4 (decision D9) | Downcomer backup as a second flood measure; the worse of the two is the flood fraction. The free water's entrainment limit keeps Fair's fraction. | `V3TrayHydraulics.terms` | Post-solve arithmetic; it moves the simulator's flood readings |

Not built: a steep pressure-drop rise past flood inside the tray model (it feeds the one warm correction, whose fixed point would stop contracting, and columns that solve today would fail); tray holdup, cycling, hydrocarbon carry-over (no steady-state table holds them); degraded separation past flood (an invented blend law; the alternative of D2).

**Tests.** Each is the least run that decides its item; all by `--tests`, none on a dev client.

| # | Test | Proves | Class |
|---|---|---|---|
| T1 | A table at 80 % of flood at the design feed: a cycle at the set feed takes all of it and raises no condition | The limit does not bind in the design case | `ColumnCycleTest` |
| T2 | The same table with `floodPerFeed` × 1.34 (reflux 2 to 3): alpha = 1/1.07, limit FLOODING, components and energy close | W3.1, conservation | `ColumnCycleTest` |
| T3 | Flood and a short feed together: the lower alpha binds and names its condition | One binding condition | `ColumnCycleTest` |
| T4 | The linear rule against the solver: the default crude column solved at 60, 80 and 100 % of the design feed; `maximumFloodFraction` over the feed rate agrees within a stated band (expected a few percent, from the tray pressure drop's effect on vapour density inside the THROUGHPUT band of 0.2). Printed, then pinned | W3.1's one assumption, in numbers | `ColumnSolveTest` (three solves, one run) |
| T5 | A running column whose reflux set point is raised until the solve gives over 100 %: the status reads the limit, the feed taken falls to the cap, lowering the set point lifts it; no solve beyond the two set-point solves | W3.1 end to end, no extra solver load | `ColumnRunTest` |
| T6 | The live pressure drop equals the solved one at the table's alpha and falls with alpha² in its dry part at half the feed | W3.3 | `ColumnProbeTest` |
| T7 | `HIGH_LOAD` at 85 to 100 %, `alarm_flooding` 1 only while the limit binds, the channels readable during a limit | W3.2 on the control system | `ControlSystemTest` |
| T8 | A state without `floodPerFeed` (an older state format) is dropped and solved again; a restored state limits as before the save | State rule | `ColumnRunTest` (reload case) |
| T9 | The gate with its rule disabled: T2 fails when the flood cap is removed | The gate bites | T2 alone, once |
| T10 (with W3.4) | Backup height on a hand-computed tray; a liquid-limited tray reports backup as its flood; `FlowsheetExamplesTest` and the sizing gates with their new flood readings listed for the owner | W3.4 and the references it moves | `V3TrayHydraulicsTest`, the sizing gates |

Acceptance: T1 to T9 green; T4's band is the number the owner accepts for the linear rule (if it is over 5 %, the table keeps the flood fraction at two throughputs and interpolates, still without a solve). No campaign: no fluid solver input changes. The final lane runs once for the batch.

## 4. Control system

1. **Alarms.** `EquipmentAlarm.COLUMN` lists the words of section 2. `BuiltInInputs.alarms` adds them for `Kind.COLUMN`; `raised` reads the column's cached condition set (`FluidWorldAuthority.columnConditions`, a read). The DCS alarm list (`ControlPublication.alarms`) then shows them with no change. Cost: a set lookup at the 100-tick cadence.
2. **A column page on the controller and the DCS.** A column has a data socket and a control identity today (`ControlHosts.bindColumn`). A panel whose bus reaches a column lists it beside the controllers. The page is the Operation door (`OperationColumn.rows` and its glass, one definition, two screens): Start, Stop, the set points, the conditions. Commands go from `ControlNetwork` to the same `AssemblyService.start`/`stop`/`setpoints` the tab calls, as queued commands, and need the data link as a controller's edit does. The view is the cached `OperationView` on the scheduled publication; opening replays it. No second path to the engine, no second copy of the set points.
3. **Loops on a column.** A controller whose output is a column's feed boundary valve, or the column itself, sets the column's **feed rate** in place of `OUTPUT RESERVED`: a flow sensor holds the feed at its target, a level sensor on the feed tank holds the tank (`InventoryControl`, as for a valve). It is a plan over the saved set point (`ControlPlans`), not an edit of the fact: the cycle takes the lower of the set rate and the planned one. The feed rate costs no solve. Intensive set points (reflux, steam, duties, temperature) are not loop outputs in this plan: every change of one is a V3 solve (decision D4).
4. **Measurements.** The existing channels (top, bottom, reflux, ports) stay. Each port gains a `level` channel in percent: its account's fill, which the tab shows today as "held".

## 5. Adjustable parameters

One `Setpoints` record, edited from the Operation tab and the column page of a controller or DCS:

| Parameter | Today | Proposed |
|---|---|---|
| Run switch | Tab | Tab, controller, DCS |
| Feed rate (0 to the design rate) | Tab | Tab, controller, DCS, a loop (4.3) |
| Condenser temperature, reflux ratio | Tab | Tab, controller, DCS |
| Reboiler duty, steam, draws, pumparound duties | Tab, per mole of feed only | The same three doors. Each entry shows its absolute value at the set feed beside the ratio (MW, kg/h, kmol/h); the ratio stays the fact (owner Q2). |
| Top pressure | Fixed by the design | Decision D5 |
| Before the first run | Meters | Entries; the unit is made at the first Apply or Start (C7 open item) |
| A refused set point | Stays in force, retried without end | Shown as refused beside the accepted one (W2) |

Not adjustable, on purpose: tray count, feed tray, diameter (the design), the bands, holds and buffers (constants), rule A or B (no mode switch).

## 6. Cost, saves, tests

- **Column solver.** No new trigger in W1 to W3, W5, W6. W2 removes solves. W4 adds none under option U1.
- **Fluid network.** No new unknown or equation. W4 adds a column read on heat sides that are in their islands today.
- **Server thread.** Per column and cycle: a few comparisons per port and duty. Alarms and pages read cached sets.
- **Facts.** W4: the utility sides of the reboiler and the pumparound coolers, appended to `ColumnUnit` as `condensers` was (next facts schema, default none = the duty free as before, a corpus image). D5: one appended set point. W5: the members' stores, saved as the burners' are. Nothing else is a fact.
- **State.** `SplitTable.floodPerFeed` and the condition set: a state format step; an old state is dropped and solved again.
- **Wire.** `assembly-4` and `control-3` step up. No compatibility work.
- **Tests.** `ColumnCycleTest` (each LIMIT, the flood rule), `ColumnRunTest` (a refused set point reverts and asks no second solve; a refused state is not solved again; a short reboiler limits and its heat closes against the utility), `ControlSystemTest` and `ControlLoopGameTests` (alarm channels, the column page, a level loop on the feed rate), `OperationColumnTest`, `FluidFactsCompatTest` with the new corpus. GUI by the `gui-creation` skill: gates and software renders, no dev client.
- **Order.** W1, W2, W6.1 (pure engine and one list), then W3, W7, W6.2 to W6.4, then W4, W5.

## 7. Owner decisions

| # | Decision | Options (recommended first) |
|---|---|---|
| D1 | A short reboiler or pumparound utility | **U1** cut the feed together (LIMIT; no solve). U2 take the duty delivered as the new specification and solve (as rule B does for steam; one solve per crossing, then U1 if it fails). U2 is closer to a real column, which keeps its feed and goes off its products. |
| D2 | Flooding | **Limit** the feed (as the batch still caps its boil-up at its flood factor). Or a NOTICE only, as the simulator's run (the owner's earlier choice for the calculator). Or degraded separation: the table blended toward a one-stage flash split past 100 % (products off their cuts, no feed cut; an invented law). Section 3a. |
| D9 (dropped 2026-11-10: Fair's fraction alone) | Downcomer backup as a second flood measure (W3.4) | **Yes**, with the moved sizing readings listed for approval. Or keep Fair's fraction alone. |
| D3 | Steam's rule B | **Keep** (owner Q1). Or one rule for every input: cut together at once. That deletes `ruleA`, the STEAM_RATIO retry coupling and one status suffix; its effect on products under a steam shortage needs one run of `tools/column-operation` before it is proposed in numbers. |
| D4 | Loop outputs on a column | **Feed rate only.** Or also the intensive set points, through a deadband of the drift band and `SOLVE_SPACING`: at most one solve per 30 s per column. |
| D5 | Operating pressure as a set point | **No** in this batch: it moves every port's pressure (a ledger event per port) and the flood limit. Or yes, within the design's rating. |
| D6 | A stale table (F7) | **Alarm OFF TABLE and keep running** until the closure trips at 50 %. Or lower the trip to a tighter departure after a refused solve. |
| D7 | Member power (W5) | **In this batch**, after W4. Or its own batch with the electricity network. |
| D8 | Scope | **W1 to W7 as one batch** in the order of section 6. Or W1, W2, W6.1 first as a small batch (no fact changes), the rest after. |
