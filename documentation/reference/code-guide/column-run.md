# Column run: the running column in the engine (ports, accounts, the fence, utilities, power, saves, the tab)

Up: [code guide](README.md) · the split table, the cycle's arithmetic, conditions and bands:
[column-operation.md](column-operation.md)

## Read this when

- A column's status reads `NOT READY` or `STARTING`, or a device says `COLUMN: part of the column at …, running`.
- Start refuses, or a port stands in the wrong place; Stop or a dismantle leaves something behind.
- A running column's rates or accounts look wrong, or one of its boundary lines holds (the solver side of that:
  [fluid-step.md](fluid-step.md)).
- A running column reads `LIMITED: condenser`, `reboiler`, `pumparound` or `power`, or its cooling water flow, its
  water's outlet, its air fans or a utility's valve look wrong.
- A solve is late, applied at the wrong tick, or not asked again.
- You change a column unit's facts (`ColumnUnit`), its engine state, the operating point in a saved design
  (`ColumnOperatingPoint`), the Operation tab or the DCS's column page, or rebuild a review plant.

The island scheduler, the coupling and the certificates themselves: [runtime-engine.md](runtime-engine.md). The
exchanger's wall and the heat in transit: [heat-exchangers.md](heat-exchangers.md).

## What it does

While a column runs, the devices inside its boundary valves are **held**: out of the islands, their contents kept. Each
boundary valve's column side becomes a **port**: a VOID at each inlet and a GENERATOR at each outlet, on their own lane.
Each port has an **account** of moles and energy. Inlets book what arrives; every cycle (`CYCLE_TICKS` online ticks)
`ColumnOperation` takes the feed the accounts hold, lets `ColumnCycle.step` split it by the table, and credits the
product accounts. The fluid network never sees the column: it sees a mass target on each boundary valve, planned from
the accounts. Each side reads the other through a **fence** (`FENCE_TICKS`), so a column's products do not depend on when
an island's job completes. A solve's table applies at a fixed tick after it was asked, however long the worker took. The
condenser's cooling water, the reboiler's heating and each pumparound cooler's cooling take their duties through the
exchanger's heat in transit; the column sets a water valve's flow where the water side's flow station is complete; an
air condenser's fans run at the least speed that holds the duty; the members' fans and pumps draw power.

## Class map

Paths under `src/main/java/com/wormzjl/icheme/`. Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| [`runtime/fluid/ColumnOperation`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnOperation.java) | The runs, on the server thread, without Minecraft (`implements IslandCoordinator.Ports`): cycles, solves, plans, booking; one `Debt` per `Utility` (`CONDENSER`, `REBOILER`, `PUMPAROUND` j); `view`, `status`, `conditions`, `drop`, `feedRate`, `instrument`; the interfaces `Solves`, `Power`, `FeedPlans`; `compounds(device)`, `grow(submit)`; the constants `CYCLE_TICKS`, `FENCE_TICKS`, `INLET_AIM_CYCLES`, `BUFFER_CYCLES`, `CONDENSER_HOLD_CYCLES`, `SOLVE_SPACING`, `TRANSIENT_WAIT`, `APPLY_TICKS`, `KEEP_TICKS`, `APPROACH_KELVIN`, `TAKE_MARGIN`, `TRACE_KG`, `DIFFERENTIAL` | 1107 |
| [`runtime/fluid/ColumnUnit`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnUnit.java) | The facts of one column in the topology ledger: `Station`, `Totals`, `condensers`, `airSurface`, `Utilities`, `Members` (`NONE`, `CONDENSER`, `REFLUX_PUMP`, `pumparoundPump(j)`), `Probes`, `State`; `MAXIMUM_STATIONS`, `MAXIMUM_CONDENSERS`, `lost` | 195 |
| [`runtime/fluid/ColumnFacts`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnFacts.java) | The running columns' facts section (a `FactsSection`): the column units by identity | 79 |
| [`runtime/fluid/ColumnPower`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnPower.java) | `ColumnOperation.Power` over `ElectricStores` and `ShaftTrains`: fan sides on their store (`ElectricStores.member`), a casing's rated shaft power on its train (`ShaftTrains.held`), an in-line pump on its own store; `undriven` | 68 |
| [`runtime/assembly/ColumnStarts`](../../../src/main/java/com/wormzjl/icheme/runtime/assembly/ColumnStarts.java) | From a complete assembly report to a start request (`build`, `port`): ports, the condenser's water sides, the air condenser's surface, the reboiler's and pumparound coolers' heat sides, the fan sides and the pumps' movers | 149 |
| `runtime/fluid/FluidWorldAuthority` (`startColumn`, `stopColumn`, `editColumn`, `columnRemoved`, `columnView`, `columnCondenser`, `columnAlarms`, `columnLoop`, `columnInstrument`) | Start and Stop as one queued ledger event each; utility sides (`utilitySides`), held members; [runtime-registry.md](runtime-registry.md) | |
| [`science/fluid/network/PortPlan`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/PortPlan.java), `PassiveStepSolver.ports` | One slice's mass target on a port's valve; an outlet's generator stated at its product | 32 |
| [`science/equipment/ColumnOperatingPoint`](../../../src/main/java/com/wormzjl/icheme/science/equipment/ColumnOperatingPoint.java) | The design's solved input, a shape field of `ColumnTrays` (`operating`): package, assay, components, feed, pressures, condenser, reflux, reboiler, steam, draws, pumparounds; `of(input)`, `fits`, `input(trays, diameter)` | 123 |
| [`client/gui/common/OperationColumn`](../../../src/main/java/com/wormzjl/icheme/client/gui/common/OperationColumn.java) | The Operation page ([client-equipment-screens.md](client-equipment-screens.md)) | 465 |

## Main flow

1. **Start.** The tab sends `AssemblyNetwork.Action.START`, a queued command (`AssemblyPresentation.act`).
   `AssemblyService.start` calls `ColumnStarts.build`, which reads the assembly report and the operating point and names
   each port: its boundary valve, its cell and its estimated conditions. `FluidWorldAuthority.startColumn` does the
   rest as one queued ledger event.
2. **Hold.** The event walks from the column's nozzles to the boundary valves, marks each device on the way
   `withSuspendedBy(unit)`, adds one port device per station and saves a `ColumnUnit`. Stations keep their accounts
   across a restart; a restart keeps the player's set points if they are `compatible` with the design.
3. **First solve.** `ColumnOperation.refresh` makes a `Run` with status `STARTING`; the first cycle asks a START solve of
   the design (no seed), submitted through `Solves.submit` (`ProcessSolveServices.submitColumnUnit`,
   `ColumnUnitCommand(job, model, liquidSupplyScreenRatio)`). Nothing moves until the table applies.
4. **Run.** `due` → `cycle` (→ `ColumnCycle.step`) each cycle; `IslandCoordinator` calls `plans` at an island's
   dispatch (→ `PortPlan` → `PassiveStepSolver.ports`) and `booked` at its commit; `decide` (the `Ports` hook, called
   at each decision point after the shafts) runs `due`.
5. **Stop.** `ColumnOperation.stop` queues one event: the held devices are released and the ports go. The unit keeps
   running until that event commits (`committed`), so what its ports take and give to the last slice is booked. A Start
   still queued goes first; a stop already queued has nothing left to edit. `standing` asks whether a port is in the
   active registry or named by a queued event (`WorldTopologyLedger.queued`). Then the accounts stay on the unit, the
   unit forgets its engine state, and held vessels keep their contents.
6. **Removal.** Dismantling calls `AssemblyService.removed` and `dismantled`; `columnRemoved` marks the unit `leaving`
   and stops it; when the stop commits, the unit goes and the ledger books its accounts and solids as destroyed. A
   layout refresh calls `removed` alone and keeps the unit. Reverting a report stops the column too.

## Contracts

**Ports, stations and accounts.**

- `ColumnUnit` has `id`, `master`, `running`, `setpoints`, `stations`, `totals`, `condensers`, `state`, `leaving` and
  `airSurface`. Its id comes from the ledger's identity counter (`WorldTopologyLedger.reserve`), never a device's. A
  unit runs only if its stations have devices. `condensers` are the condenser's water sides, one per shell in
  parallel, at most `MAXIMUM_CONDENSERS`; `airSurface` is the bare tube of the air condenser's banks as Start found
  them (0: none).
- `Station`: `port`, `index`, `device` (0 while stopped, and for a station its design lost), `valve`, `account` (moles
  by component on the world axis, and energy) and `solids` (inert solids at that port; crystals melt on arrival).
- A port device is a VOID (inlet) or GENERATOR (outlet) in the cell on the valve's column side, on
  `PhysicalFluidTopology.PORT_LANE` (one past the rack places), facing the valve, with the valve's geometry and one BULK
  port; its spec's composition means nothing (`PortPlan` states an outlet's product each slice). A port stands in for
  the held pipe next to its valve; `across` finds a port at `next.withLane(PORT_LANE)` first.
- A held device joins nothing (`joins` is false if either end is `suspended()`); as built it still joins
  (`mechanicallyJoins`), so Stop needs no rebuild. A held pipe has no island and reads `COLUMN: part of the column at
  x, y, z, running`; a held vessel stands alone in a one-node island and keeps its contents.
- `ColumnStarts.port` maps the blueprint's `*_valve` slots to ports; a slot's index counts from 1 where a part repeats.
- Plans and bookings map the accounts (world list) to each island's own list by id (`PortAccounts.onIsland`,
  `onWorld`); `compounds(device)` is what an outlet port may deliver, and `grow(submit)` queues one registration edit
  per port whose account holds a compound its island lacks, so the island recompiles with it on the engine's schedule.

**The fence.** A column and its islands advance on the online clock but not together:

- A cycle at tick t waits until each inlet's island is through t, and each outlet's and the water side's through
  t − fence (`portsThrough`; `IslandCoordinator.through` is true for a committed, certified, held, parked or suspended
  island, and with no island). It sees an arrival only up to t: `available` is the account less later arrivals, and a
  span across t is shared by time.
- A slice starting at s reads a port's level as of s − fence (`Level` logs) and waits (`ready`) until the run has
  cycled past s − fence. An outlet's temperature is the table in force at the fence (`Run.tableAt`).
- A cycle already due runs from a booking; `schedule` also looks a cycle later, because an island that holds books
  nothing. A cycle's first tick is the next multiple of `CYCLE_TICKS`, staggered by the unit's id; it is scheduled on
  `IslandScheduler.Kind.COLUMN_CYCLE`. `booked` only records that a port's island booked while a cycle was due; a cycle
  never runs inside an island's materialisation. (The waits and wakes are being changed by R-ST4: WAVE_D_BRIEFS.)

**A cycle** (`cycle`): the set points in force are the accepted ones (`running`: the table's reference set points at the
unit's feed rate, or a control loop's plan below it, `feedRate`; the requested ones before any table). Room in each
product is the design feed rate × `bufferSeconds()` × its share per feed, less its account and what the outlet gave
after the fence (`Gift`, `givenSince`). Each utility's room, the air's most feed and the members' power (`powered`) go
into `ColumnCycle.Rooms`. After the step: accounts move, every inert solid that arrived by the tick moves to the
bottoms, a `Credit` is logged, totals grow, each utility is owed its duty (`owed`), new levels are logged, `publish` sets
conditions, status and phase, and `save` writes the state. A trip asks a solve on the attempted take: STEAM_RATIO where
rule B's steam is short, else the first band the state left, else CLOSURE.

**Plans** (`plans`, at an island's dispatch):

- Inlet (`inletPlan`): rate from the level as of the fence, aiming to keep `inletAimSeconds()` of the set rate:
  gain = clamp(1 + (aim − held)/aim, 0, 2), target = rate × gain × molar mass. An empty account meters at the design
  feed's molar mass (water for steam); with none the rate is 0 and the status says so.
- Outlet (`outletPlan`): `available` less credits after the fence; rate = produced × clamp(mass/aim, 0, 2), capped by
  mass/seconds × `TAKE_MARGIN`. An account holding `TRACE_KG` or less plans 0. Composition is the account's,
  temperature the table's.
- `PortPlan(node, valve, massTarget, temperature, moles, solids)`. `PassiveStepSolver.ports` runs after
  `sizeJunctionHoldups` and before `couple`: it restates an outlet's generator unless its shares match to 1e-12 and
  neither side holds solids, and sets the valve to `ControlValve(MASS_FLOW, …, COLUMN_SENSOR, …)`. A target within
  `VALVE_DEADBAND` of the standing one stays; a target of 0 always applies. A valve that bounds two columns takes the
  lower plan. `IslandCoordinator.sameControl` counts a planned regulator and the valve's own `FlowControl.Valve` of the
  same target pressure as one control.

**Booking** (`booked`, at island commit): an inlet's arrivals go into its account at once, logged as a `Span`; a crystal
melts there (`melted`) with its stored energy made true by its offset; an inert solid goes to the station's `solids`.
An outlet's account is debited exactly what the step gave (`PortAccounts.debit`); the difference between given and
debited energy goes to the condenser's total, owed to the cooling water if the port offered its product below the
table's `waterBelow` as of the fence, else to the air, in the totals alone. An overdraw logs `column status=OVERDRAWN`.

**Solves** (`request`, `applied`, `done`):

- One solve pends at a time. SETPOINT and START are urgent; others wait `SOLVE_SPACING` from the last. None is asked
  before `waitUntil` (an unfinished solve's wait), and none of a state inside the bands of a refused one at the same
  set points (`inside`), except SETPOINT.
- A solve's table applies at its trigger tick + `APPLY_TICKS`, however long the job took; the cycle at that tick waits
  for the outcome (`applied`). This is why results do not depend on the worker.
- A failed solve keeps the old table with the text `the <trigger> solve failed (<reason>), the table of N s ago kept`
  (before any table: `Refusal.FIRST_SOLVE`, NOT READY). A refused SETPOINT solve gives SETPOINT_REFUSED and keeps the
  accepted set points until Apply (`edited`) or a move away; any other refused solve stores the state as `Refused`; a
  refused STEAM_RATIO solve sets rule A. A transient failure (`Failed.transientFailure`: deadline, worker failure,
  shutdown) is asked once more after `TRANSIENT_WAIT`. A solve that gives a product the column has no nozzle for is
  refused (`missingStation`, `Refusal.NO_NOZZLE`).
- SETPOINT and START solve the requested set points; every other the accepted ones at the unit's feed rate. The feed
  taken is the last cycle's rates (`taken`); with no cycle, `designTaken` scales the design's feed. The model is the
  feed island's, taken on the engine thread at submission (a feed port in no island submits nothing; the next cycle
  tries again), so V3's basis is that island's compounds. A warm seed on another basis is dropped; the seed is not
  saved.

**The condenser.**

- Water sides: `ColumnBlueprint.waterCooled(condenser)` names the water-cooled slot (`CONDENSER` for an exchanger,
  `TRIM_CONDENSER` for air then water, none for an air cooler); `ColumnStarts` takes every unit of it and their sides;
  `startColumn` keeps each side outside the column whose partner is held inside, at most `MAXIMUM_CONDENSERS` (else
  Start is refused).
- The debt: each cycle the water's share of the duty is owed (`owe`) in equal shares to each water side with a partner:
  `coordinator.owe(side, partner, share)` adds to that pair's heat in transit and wakes the side a fence later.
- The read (`heatRead`, at dispatch): `Read.column(due, seconds, ceiling, heatRate)`, `due` the transit less what was
  owed after start − fence, `ceiling` the accepted condenser temperature less `APPROACH_KELVIN` (at least 1 K),
  `heatRate` the shell's share of the last cycle's water duty at or before start − fence (`waterRate`) plus
  excess/`condenserHoldSeconds()`. `ExchangerRelay.plan` gives the transit back over the slice, no more than keeps the
  outlet at the ceiling; the rest stays owed.
- The water valve: where the water side's control is `HeatUnit.Mode.COLUMN` and its flow station is complete, the
  coordinator adds `Operation.column(valve, WATER_RANGE)` to the read, and `PassiveStepSolver.couple` sets the feed
  valve's mass flow to heatRate/(h(T_out) − h(T_in)), T_out = min(T_in + 10 K, ceiling). Without a complete station
  the water flows as its line lets it.
- Room: `condenserHoldSeconds()` × |water duty per feed| × design feed rate, less the shells times the fullest shell's
  transit as of the fence, with what outlets booked after the fence left out (`Owed.booked`). A water side that does
  not take the heat fills the room and the step cuts the feed: `LIMITED: condenser`.
- Status: a cycle's status adds `; air fans N %, P kW` where it knows the fans, and `; cooling water F kg/s, out at
  T °C` where the column sets a valve (`water`, a presentation read). A held side with no column read reads
  `ExchangerRelay.Read.NONE` and moves no heat.

**The reboiler's and pumparound coolers' utilities.** `ColumnStarts` takes the heat sides of every unit in `REBOILER`
and each `pumparoundCooler(i)`; `startColumn` keeps them by the condenser's rule (`utilitySides`), at most 6 a duty
(`Refusal.TOO_MANY_UTILITY_UNITS`). Each cycle owes −reboiler and −pumparound j, shared equally by their sides; `room`
is the condenser's rule, signed (`Utility.sign`); a duty with no side has no bound. A short room gives HEATING_SHORT or
PUMPAROUND_SHORT: a feed cut, no solve. A pumparound cooler's side reads `Read.column` with its ceiling at the draw
tray's temperature less the approach; the reboiler's side reads `ExchangerRelay.Read.heating` with its floor at the
bottoms' temperature plus the approach. A kettle's tube side takes `HeatUnit.Mode.COLUMN`, and the column sets its feed
valve to heatRate/(h(inlet) − h(floor)).

**The members' power.** `ColumnStarts` collects the air condenser's fan sides and the movers of `REFLUX_PUMP` and each
`pumparoundPump(i)`; `startColumn` keeps those it holds (`ColumnUnit.Members`). While `energy.machinesNeedPower` is on,
a member pump whose casing's train has no driver refuses Start (`ColumnPower.undriven`, `Refusal.NO_DRIVER`). Each
cycle (`powered`) each fan side books its fans over the span that ran at the last speed and says whether it has power
(banks without power hold natural draught: `airFeed(powered, natural)`, NO_POWER of member 0); each pump books its rated
shaft power (`InlineMovers.ratedShaftPower`) times the last cycle's share; a pump without power gives NO_POWER with
share 0. With the setting off, or `Members.NONE`, nothing books.

**Time factor.** A cycle is `CYCLE_TICKS` online ticks and advances `cycleSeconds()` of process time; every per-cycle
rate divides by it. The windows are counts of cycles, so they answer in the same play time at any factor
(`inletAimSeconds()`, `bufferSeconds()`, `condenserHoldSeconds()`).

**Instruments.** `ColumnSolve.Solved` carries the accepted `V3ColumnInspection` to the apply tick; `ColumnOperation`
reduces it to `ColumnUnit.Probes` (top, bottom and reflux pressure and temperature, internal mass-per-feed
coefficients; the denominator the reference's `alpha` × design feed rate, feed water included). On a trip flows read 0
and pressures and temperatures stay the table's; `DIFFERENTIAL` (`COLUMN:differential`, channel
`differential_pressure`) reads the live drop. `FluidWorldAuthority.columnInstrument` is read-only.

**The tab.** `AssemblyNetwork.Host.view` adds `AssemblyService.operation(level, origin, refusal, opening)` unless the
column has no design; the view is `ColumnOperation.view` (a read of the last cycle), else the design's set points;
`NOT_READY` "a vacuum column cannot run yet" for a column with an ejector. Each station shows its rate, temperature,
pressure, fill and the valve's opening (`FluidNetwork.opening`, a stored read that advances no island); duties booked
against the table at the set feed (an air condenser adds a FANS duty); `Solve` the trigger, warm or cold, the age; the
run's `conditions`, `accepted` and `Trays`. Start, Stop and Apply are `OperationPayload` commands on the presentation
bucket; `AssemblyService.setpoints` clamps the feed to the design rate and refuses a wrong count;
`FluidWorldAuthority.editColumn` queues a set point and calls `ColumnOperation.edited`. A column that never ran gets a
stopped unit with no stations when its set points are applied. A DCS sends the same three actions over the data link
(`ColumnCommands`, [process-control.md](process-control.md)).

## Laws and constants

- Water valve: T_out = min(T_in + 10 K, ceiling); flow = heatRate/(h(T_out) − h(T_in)).
- Inlet gain clamp(1 + (aim − held)/aim, 0, 2); outlet rate produced × clamp(mass/aim, 0, 2).
- The constants are `ColumnOperation`'s (class map); `CYCLE_TICKS` and `FENCE_TICKS` are `PortAccounts`', shared with
  the stills.

## Formats

- **Facts** (`ColumnFacts`, `FluidFacts`): a registration's `suspendedBy`, and the column units by identity (id, master,
  running, set points, stations, totals, condenser water sides, leaving, `airSurface`, utilities, members). Append-only;
  a field's default is the behaviour before it (no utilities: the duties stay free until the next Start; no members:
  nothing draws). Corpora: `src/test/resources/compat/fluid/schema-6/`, `schema-8/`, `schema-9/`, `schema-21/`.
- **State** (`FluidFacts.topologyState`, `columnState`, on the world list): each running unit's `ColumnUnit.State` after
  the queued events (table, enthalpies, `tableTick`, `applied`, `solvedCondenser`, levels and credits, the water and air
  fields, the probes, the `Flood` record, `pumparoundDraw`). A state that does not validate is dropped and that unit
  solves again. On a rebuild, accounts and totals map onto the current axis, a removed component or solid is booked
  (`Restated`), `FluidFacts.settled` settles each unit on what stands, and `refresh` restores a run if the state's axis
  equals the current one and every product has a station.
- Not saved: arrival spans, the utilities' debts, the fans, the conditions, the refused set points and state, the
  advisories, the seed, the pending solve and the wakes. After a load each utility's heat rate starts from the table.
- **Shape**: `ColumnTrays.operating` (`ColumnDesignTags.writeOperating`, `ShapeRepair.SavedOperating.resolve`; an
  unreadable one repairs to none). Corpus `src/test/resources/compat/equipment/shapes-4.snbt`.
- **Protocol**: the assembly protocol's operation view (`AssemblyView.operation`, `OperationPayload`,
  `AssemblyNetwork.encodeOperation`, `decodeOperation`; the DCS page reads the same bytes). Every number:
  [FORMATS.md](../FORMATS.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| Conservation, the fence, plans, booking, solves' timing, rules A and B in the world, Stop and dismantle, utilities, power, reloads | `ColumnOperation` | `ColumnRunTest` (with `aColumnsProductsDoNotDependOnWhenItsIslandsJobsComplete`, `aHorizonPassedBeforeItsDeadlineFiresWithACycleDueBehindIt`, `aStoppedUnitWithNoStationsSurvivesTheFacts`) |
| Held devices, ports | `startColumn`, `PhysicalFluidTopology` | `ColumnSuspensionTest` |
| A boundary line's regulation | `PortPlan`, `PassiveStepSolver.ports` | `RegulatingValveHoldTest` ([fluid-step.md](fluid-step.md)) |
| Probes, the live drop | `ColumnUnit.Probes`, `drop` | `ColumnProbeTest` (`theLiveDropIsTheSolvedOneAtTheTablesFeedAndItsDryPartGoesWithTheSquare`) |
| Facts, shapes, the wire, the page | `ColumnFacts`, `ColumnUnit`, `OperationView`, `OperationColumn` | `FluidFactsCompatTest`, `EquipmentShapeCompatTest` (`equipmentGates`), `AssemblyNetworkCodecTest`, `AssemblyPresentationTest`, `OperationColumnTest` |
| DCS and loops | `ColumnCommands`, `ControlLaw.column`, `ControlPlans.columnFeed`, `feedRate` | `ControlSystemTest`, `BuiltInInputsTest` |
| In the game | the whole run | GameTests `ColumnOperationGameTests` (`aColumnRunsInItsPlantStopsAndLeaves`, `aDcsListsStartsAppliesStopsAndSeesABlockedProduct`, `aMenuReadAdvancesNoIsland`) |

Hand reviews, not gates: `ColumnOperationReviewWorld.aCrudeColumnRuns` and
`ColumnFailureModesReviewWorld.theFailureModesPlantRunsItsCases` (`runReviewWorldServer`, namespace `icheme_review`).
Off-line tools: `tools/column-operation/`, `tools/valve-hold-probes/`, `tools/column-failure-modes/`.

## Traps

- A column's cycle never runs on a packet or a menu: `ColumnOperation` is driven by the scheduler's due deadline and by
  island commits; `due` guards re-entry with `cycling`.
- The first cycle after a load can wait: a cycle needs its inlets through its tick.
- Never set a port's target from anything but `plans`: the fence is the only thing that keeps a run independent of job
  timing.
- A boundary valve's target changes each slice: identity comparisons that decide "same line" ignore a flow valve's
  target (`PassiveNetwork.Pipe.Identity.line`).
- `ScheduledTransfer` is not a port; it has no runtime producer.
- A vacuum column (one with an ejector) does not run: `ColumnStarts` and `AssemblyService.operation` refuse it.
- A running column is mechanically still joined: read `suspendedBy` first when you walk the registry.
- `portsThrough` materialises a certified island when the reader looks, and `request` drops a solve the service refuses
  (named in `wave-c/R-ST3_REPORT.md` as the same class as the still's fixed readings).
- `Refusal.NO_DRIVER` is an assumption the owner may veto (batch `2026-11-10-column-failure-modes`).
- Member designs carry the Standard and Low-dP crossflow choices through sizing, sent designs, Import and assembly
  matching; the running column's solve and duty contract do not depend on them, and a layout or type mismatch is an
  assembly issue.

**Snapshot:** `d6f721b94`, 2026-11-15.
