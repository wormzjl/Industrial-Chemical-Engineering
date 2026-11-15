# Heat units in the world: registration, the coupling across islands, control lines, saves and the screen

Up: [code guide](README.md) · the heat laws, the curve, the relay and the controller:
[heat-exchangers.md](heat-exchangers.md)

## Read this when

- Two coupled islands wake each other, never certify, or one waits for the other (the coupling fence).
- A side's status says `LINES NOT CHECKED`, `LINES NOT COMPLETE` or `LINES COMPLETE`, or a real bypass or feed valve does
  not regulate.
- A heat unit registers, refreshes or removes wrongly, or its heat in transit is lost or doubled across a save.
- An air cooler reads ` / NO POWER` or ` / UNMETERED`, or a control mode is refused.
- The Control tab or the stream switch shows the wrong actuator, effort, limit, port or line.
- You rebuild the heat review world (`HeatExchangerReviewWorld`).

Islands, certificates, checkpoints and packets in general: [runtime-engine.md](runtime-engine.md), [runtime-saves.md](runtime-saves.md), [runtime-presentation.md](runtime-presentation.md). The walk and the matcher
that check an exchanger's lines: [machine-assemblies.md](machine-assemblies.md). A running column's condenser and
utilities: [column-run.md](column-run.md). The Control tab's layout: [client-screens.md](client-screens.md).

## What it does

Each passage of a heat unit is a fluid device of kind `HEAT_SIDE` whose registration carries a `HeatUnit` (its unit and
side, area, partner, control, heat in transit, fan count, exchanger type); a kettle's shell is a closed vessel whose
inventory carries the pool. A side compiles as an inline filter does: its passage is one pipe (`sideIdentity`) between
its inlet node and a junction at its outlet, and that pipe carries its heat law. The two sides of an exchanger may sit in
two islands; they exchange only energy, through the relay: each side plans against its partner's record as of its slice
start (the fence), and what the two sides booked differently waits as the exchanger's heat in transit, a fact on the
pair. `ExchangerCoupling` holds the fence, the reads, the booking, the wakes and the transit. An exchanger whose control
lines are complete works a real control valve; a running column's water side in the COLUMN mode lets the column set its
feed valve's flow.

## Class map

Paths under `src/main/java/com/wormzjl/icheme/`. Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| [`runtime/fluid/HeatUnit`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/HeatUnit.java) | A side's facts on its registration (`PhysicalFluidTopology.Device.heat`): `unit`, `side`, `area`, `partner`, `Control(mode, target, lowLimit, share)`, `transitJoule`, `fanCount` (at most `MAXIMUM_FANS`), the exchanger type (`withExchangerType`); `Mode` OFF, OUTLET_TEMPERATURE, DUTY, FIRING, RATIO, COLUMN; `FIRING` (the default firing control); `heatSide()`, `controller()`, `controllable()`, `lines()`, `pool()`, `fans()`, `burners()`, `kettleTubes` | 134 |
| [`runtime/fluid/ExchangerCoupling`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ExchangerCoupling.java) | The coupling of sides across islands, on the coordinator's thread: `Coupled` (relay state per side, its booked spans, at most 64), `SideKind` EXCHANGER, RADIANT, CONVECTION; `coupledSides`, `partners`, `coupledThrough` (the fence), `reads`, `booked`, `moved`, `flushWakes`, `operations`, `settledTransit`, `owe`, `transitAsOf`; `WAKE_TEMPERATURE`, `WAKE_FLOW`, `WAKE_TRANSIT`, `KEEP_UNREAD_TICKS` | 277 |
| [`runtime/fluid/HeatPresentation`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/HeatPresentation.java) | Completes a side's `FluidView.Heat` where its view is built: the actuator (`FluidView.Heat.ACTUATORS`) and its effort, the controller's limit, every port of the unit with its stream, an exchanger's lines part by part | 199 |
| `runtime/fluid/FluidWorldAuthority` (heat parts) | `placeUnit(sides, vessel)` and `removeUnit(ids)` (one ledger event each, the pair whole; `UnitSide`, `UnitVessel`); a fired heater's coil edit moves its partner in the same event; `Lines` (`Status` NOT_VALIDATED, CHECKED, UNMETERED, COMPLETE), `lines`, `operate`, `holdValves`; `controlStatus`, `linesStatus`; the transit loaded from and written to the facts ([runtime-saves.md](runtime-saves.md)) | |
| `runtime/fluid/IslandCoordinator` | Holds the relay states and the side index; calls `ExchangerCoupling` from its steps (`sameControl` accepts a planned valve target) ([runtime-engine.md](runtime-engine.md)) | |
| [`science/assembly/ExchangerBlueprint`](../../../src/main/java/com/wormzjl/icheme/science/assembly/ExchangerBlueprint.java) | `Lines` NONE, BYPASS, RATIO, FEED; `Design(tube, shell)`; slots `<side>_inlet_tee`, `_bypass_valve`, `_outlet_tee`, `_feed_valve`, `_meter`, `_feed_meter`; `operated(side, lines)`; `of(design)` | 148 |
| [`runtime/assembly/ExchangerLines`](../../../src/main/java/com/wormzjl/icheme/runtime/assembly/ExchangerLines.java) | `design` (from the sides' registered controls), `publish` (a report as each side's `Lines`, with the valve its control works) | 94 |
| [`client/gui/common/HeatControlColumn`](../../../src/main/java/com/wormzjl/icheme/client/gui/common/HeatControlColumn.java), [`HeatDiagram`](../../../src/main/java/com/wormzjl/icheme/client/gui/common/HeatDiagram.java), [`HeatFlowsheet`](../../../src/main/java/com/wormzjl/icheme/client/gui/common/HeatFlowsheet.java) | The Control tab (`targets`, `columnTexts`), the unit's flowsheet on a design grid with its lines' slots, the stream switch ([client-screens.md](client-screens.md)) | 258, 380, 358 |

## Main flow

1. **Build.** A whole exchanger, kettle, air cooler, fired heater or gas turbine registers through
   `EquipmentStructure` ([equipment-structure.md](equipment-structure.md)): one `UnitSide` per `HeatSides.of(shape)` entry at its first
   inlet's cell (`ShapeRasterizer.fluidCells`), its nozzles as `INLET` and `OUTLET` ports, its passage geometry and a new
   `HeatUnit`; a kettle adds its shell as a `UnitVessel`. `FluidWorldAuthority.placeUnit` places them in one ledger event
   and names the partners. A refresh moves or restates the sides with their identities, partner, control and transit; a
   removal takes all of them in one event (`removeUnit`).
2. **Compile.** `PhysicalFluidTopology.compile` treats a `HEAT_SIDE` as an inline filter: its inlet runs end at its node,
   its outlet runs leave from a new junction at its first outlet port's height, and one passive pipe with the id
   `sideIdentity(id)` carries its passage (`PipeResistance.Geometry` with its channel count) and
   `withHeat(heat.heatSide())`. The nozzles are 1 m stubs at the line's bore. An exchanger side without a partner has no
   law. A kettle's shell compiles as a reservoir whose `Inventory.pool` is a `Pool` keyed `sideIdentity(shell)`,
   partnered with `sideIdentity(tubes)`. `PassiveNetwork.sizeJunctionHoldups` skips a heat side's passage: it holds no
   fluid, so its ends are sized on their lines.
3. **The fence.** `ExchangerCoupling.coupledThrough` is a readiness condition of the coordinator (in `reconsider`, the
   pump's predicate and `verify`): each of the island's coupled sides' partners has committed through the island's
   committed tick, or is certified (materialised at dispatch), or is stopped (held, phase-parked or suspended: never
   from lag), or has no island, or is in this island. The side furthest behind can always go, so the fence cannot
   deadlock; a fast side runs up to one partner cadence behind online time.
4. **The reads.** At dispatch `reads(island, slice)` gives each coupled side its `ExchangerRelay.Read` (the partner as of
   the slice start: the later of its two records at or before that tick, after a load only the current one), carried by
   `FluidIslandCommand` to the worker. A running column's water and utility sides take the column's read first
   (`Ports.heatRead`), with the side's own operation added (`withOperation`).
5. **The worker** plans and steps the laws ([heat-exchangers.md](heat-exchangers.md)).
6. **Commit.** At a commit or a certificate replay, `booked` takes each side's heat (its `HEAT` boundaries) out of the
   pair's transit, keeps the span for its partner's as-of reads, records the sit-out, and moves the side's record to the
   span's end with its heat rate. `moved` decides whether a certified partner must solve again; `flushWakes` ends those
   certificates at the engine's next decision point, never inside a presentation read (counter
   `FluidRuntimeDiagnostics.couplingWakes`).
7. **Presentation.** At the side's bucket the authority builds its view: the status line prefixed with the lines'
   status, the controller's limit and, for a radiant coil short of its set point, `DUTY INSUFFICIENT`;
   `HeatPresentation.complete` adds the actuator, its effort, the ports and the lines. Opening the screen replays the
   cached view.

## Contracts

- **The heat in transit** is one energy per exchanger, positive when owed to the streams, held by pair key
  (`sideIdentity` of the side with the lower registration id). The booked transit is what the coordinator and the facts
  hold; the settled transit (`settledTransit`) adds both sides' heat booked past the side further behind. The energy
  ledger balances on the booked one; the steady state, the wake and "returns to 0" read the settled one. Fired heater
  coils share no transit: their heat comes from fuel; only the convection coil waits at the fence.
- **Wakes** (`moved`): a commit wakes the partner's certificate when its stream's temperature moved more than
  `WAKE_TEMPERATURE`, its flow more than `WAKE_FLOW` relative, or the settled transit grew past `WAKE_TRANSIT` of the
  partner's last slice heat plus 1 J; a partner's bypass that moved more than `WAKE_FLOW`, and a pool whose film moved
  more than that share, count as its record moving. A radiant coil wakes its convection coil when its heat rate moved
  more than `WAKE_FLOW` plus 1 W. After a load a certified partner's first record is adopted as its baseline, so a load
  wakes nothing. A side whose partner has no island prunes its booked spans at end − `KEEP_UNREAD_TICKS`.
- **Determinism.** The fence makes a side's result independent of job times; the command carries numbers only; the
  worker builds both curves.
- **Control modes** (`HeatUnit.Mode`):

  | Mode | Sides | Controller | Actuator (effort 0 → 1) |
  |---|---|---|---|
  | OFF | controllable sides | none (MANUAL with a low limit) | the manual `share` holds |
  | OUTLET_TEMPERATURE, DUTY | exchanger sides, kettle tubes, air cooler | at `target` K or W | exchanger: bypass fully open → shut; air cooler: louvers shut → full fans |
  | FIRING | fired heater coils only (required there) | none: the coil's own law | burners (`share`), set point `target` |
  | RATIO | exchanger sides only | none: the feed valve holds `target` × the other side's flow | the feed valve; holds nothing until the lines are complete |
  | COLUMN | exchanger sides and a kettle's tubes | none: a running column sets the feed valve's flow | the feed valve; until the lines are complete the water flows as its line lets it |

  RATIO and COLUMN keep the side's bypass at its manual share. `HeatUnit` refuses a fired heater coil without a FIRING
  set point, FIRING outside a fired heater, and RATIO or COLUMN outside an exchanger (or a kettle's tubes for COLUMN).
  Defaults: a new side is OFF; an air cooler's fans run (`share` 1); a fired heater fires to `HeatUnit.FIRING`'s set
  point with its burners lit. A set point must lie inside the package's temperature envelope
  (`FluidDomain.requireInputTemperature`).
- **The lines** (`HeatUnit.lines`): OUTLET_TEMPERATURE or DUTY asks for a bypass (inlet tee, control valve, outlet tee),
  RATIO for a feed valve and a flowmeter on the other side's feed, COLUMN (`Lines.FEED`) for a feed valve and a
  flowmeter beyond it on the same feed (`<side>_feed_meter`). Check lines sends `fluid_check_lines`
  (`FluidNetwork.LinesPayload`); the exchanger is validated at the next server tick (`AssemblyService.validate`);
  `ExchangerLines.publish` hands each side its `FluidWorldAuthority.Lines`: NOT_VALIDATED, CHECKED (with the parts not in
  place), UNMETERED (complete, but the valve carries no flowmeter, so it stands open as a bare valve), COMPLETE (with the
  valve's registration).
- **Operation** (`FluidWorldAuthority.operate` → `ExchangerCoupling.operations`): a complete side's read carries its
  `Operation`. At the slice start `couple` plans the valve's mass flow target: a bypass valve takes the controller's
  bypass share of the side's and the bypass's flows together (the side's own bypass stays shut); a feed valve takes the
  ratio times the other side's flow as read; a COLUMN side's feed valve takes the flow the column asks
  (heatRate/(h(T_out) − h(T_in)), [column-run.md](column-run.md)). A target within `VALVE_DEADBAND` of the valve's stands,
  so a steady plant keeps its graph and certifies. A side whose operation changed wakes its island. Not complete: a
  bypass control works the internal bypass; a ratio holds nothing; a COLUMN side's water flows as its line lets it. A
  report that reverts leaves its valve at its last planned target (`holdValves`). A flowmeter installed or removed
  reverts a report that read it (`AssemblyService.metered`); a side whose control asks for other lines drops its
  exchanger's report (`AssemblyService.redesigned`). A chunk load's check of a stored report publishes the lines again.
- **Status lines**, prefixed to the island's status in this order: the lines (`LINES NOT CHECKED: …`,
  `LINES NOT COMPLETE: n parts not in place; …`, `LINES NOT COMPLETE: the bypass (feed) valve needs its flowmeter to
  regulate; …`, `LINES COMPLETE: …`, with the fallback after `;`), then `LIMITED: the bypass is fully open` / `closed`,
  `LIMITED: the louvers are shut` / `the fans run at full speed`, `LOW LIMIT: the outlet is held at %.1f K`, and for a
  radiant coil at its maximum `DUTY INSUFFICIENT: outlet %.1f K below the set point`. The client reads the two numbers by
  these formats. An air cooler's line ends ` / NO POWER` while its store is empty (fans held, natural draught) or
  ` / UNMETERED` until its port first receives FE; its Control page shows the Power group (`FluidView.Power`).
- **Instruments.** Both exchanger types carry four equipment-owned temperature probe roots on the nozzle necks
  (`EquipmentInstruments`, `InstrumentWiring`); each heat side's fluid id aliases to that physical host for a
  controller; manual exchanger sensor placement is disabled.

## Laws and constants

The heat laws and their constants: [heat-exchangers.md](heat-exchangers.md). Here: the wake thresholds and
`KEEP_UNREAD_TICKS` (`ExchangerCoupling`), `HeatUnit.MAXIMUM_FANS`, `VALVE_DEADBAND`.

## Formats

- **Facts** (`FluidFacts`): a geometry record's channel count (default 1); a registration's optional heat unit (unit and
  side by name, area, partner, control: mode by name, target, low limit, share; `transitJoule`; an air cooler side's
  fan count; the exchanger type, missing read as Standard); the world's electric stores. Only the lower-id side of a pair
  holds the transit (`WorldTopologyLedger.Snapshot.withHeatTransits`; read back by `heatTransits` into the coordinator
  when the authority starts). A removed unit books its transit as destroyed energy (`WorldTopologyLedger.removedHeat`).
  Corpora under `src/test/resources/compat/fluid/`.
- **State** (the checkpoint, rebuilt freely): a geometry's channel count, a pipe's heat side and its law tagged (none,
  heat side, fired coil, air cooler, convection coil, exhaust coil), a side's partner, active share, controller and
  limit, an air cooler's draught, a node's pool, the wall cells. The relay's records, booked spans and wake queue are not
  saved: after a load a side's record is rebuilt from its graph and last result, its last span from the interval's
  `HEAT` boundaries.
- **Certificates**: a replay holds each heat law's facts and slice inputs and each pool fixed; a change of them while the
  island runs ends the certificate. A code change to what a replay holds fixed bumps `IslandCertificate.REPLAY_REVISION`.
- **Wire**: `FluidView.Heat` (unit, side, paired, outlet temperature, heat, fans and fan power, bypass, burners, set point
  and most heat; the actuator, effort, limit, ports and lines), `FluidView.PipeInfo.reading`,
  `FluidNetwork.Controls.heat`, `fluid_open_partner` (`PartnerPayload`: the Other side button opens the partner's own
  cell, nothing queued) and `fluid_check_lines` (`LinesPayload`); no compatibility work. Every number:
  [FORMATS.md](../FORMATS.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| The coupling: fence, reads, booking, wake, transit, loads, certify, the kettle, the real valves, coils across islands | `ExchangerCoupling`, `PassiveStepSolver.couple`, `RetainedSolver`, `FluidIslandCommand` | `ExchangerCouplingTest` (a test rig with the real coordinator, solver and codec; the wall's cost is gated here, not in the campaign) |
| A side's registration, compile, outlet junction; the air cooler and the coils in the world | `PhysicalFluidTopology` (`HEAT_SIDE`, `sideIdentity`), `placeUnit`, `removeUnit` | `HeatSideTopologyTest`, `HeatUnitPowerTest`, GameTest `ElectricGameTests.anAirCoolerWithoutPowerHoldsNaturalDraughtUntilItsPortIsFilled` |
| The lines | `ExchangerBlueprint`, `ExchangerLines`, `AssemblyMatcher`, `lines`, `operate` | `ExchangerBlueprintTest`, GameTest `AssemblyGameTests.anExchangersBypassValidatesAndARemovedPipeReverts` |
| A running column's condenser and utilities | `ColumnOperation.heatRead`, `owe`; `ExchangerCoupling.owe` | `ColumnRunTest` ([column-run.md](column-run.md)) |
| Facts, state | `HeatUnit`, `FluidFacts`, `FluidCheckpointCodec` | `FluidFactsCompatTest`, `FluidCheckpointCodecTest` |
| What the screen receives and shows | `HeatPresentation`, `FluidView.Heat`, `HeatControlColumn`, `HeatDiagram` | `HeatPresentationTest`, `FluidPacketCodecTest`, `HeatControlColumnTest` (`theColumnsModeSetsNothingAndShowsTheFlowItsColumnSets`, `aKettlesTubesOfferTheColumnsModeAsAReboilersUtility`), `HeatDiagramTest` |

Review world (not a gate): `HeatExchangerReviewWorld.theHeatUnitsStandRunning` (namespace `icheme_review`, run by
`runReviewWorldServer`): five units on water lines, their status, outlet and duty logged as `review_heat` lines. Offline
studies: `tools/heat-exchanger-design/`.

## Traps

- **Only the `HeatUnit` control and the transit are facts**; every law input is planned.
- **The booked and the settled transit differ** by the heat the side ahead booked past the other's committed tick: a wake
  that read the booked one ping-ponged; a ledger check that reads the settled one is off by one slice's heat.
- **A heat side's passage sizes no junction**: sizing its ends on every tube at the cap flow held half a bundle at the
  outlet and held the fired heater.
- **A bare control valve stands open**: a complete bypass whose valve has no flowmeter is UNMETERED and does not operate.
- A pipe gauge downstream of a side reads the outlet junction; a gauge on the unit itself reads its inlet node.
- `ExchangerCoupling.reads` materialises a certified island when the reader looks, and the coupling's wakes materialise
  to now (`wave-c/R-ST3_REPORT.md`; being changed by R-ST4).
- Not built: the split-range pass valve, a LIMITED status for a saturated real valve, operating a valve outside the
  side's own island.

**Snapshot:** `d6f721b94`, 2026-11-15.
