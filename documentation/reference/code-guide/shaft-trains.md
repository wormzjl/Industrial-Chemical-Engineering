# Shaft trains: drivers, electric stores, machine health, pages and the Create coupling in the world

Up: [code guide](README.md). The laws these run on (curves, NPSH, wear rates, drivers' steps): [rotating-machinery.md](rotating-machinery.md).
The islands, checkpoints and buckets: [runtime-engine.md](runtime-engine.md), [runtime-saves.md](runtime-saves.md),
[runtime-presentation.md](runtime-presentation.md); the movers' rows: [fluid-laws.md](fluid-laws.md); the machine shapes:
[equipment-designs.md](equipment-designs.md), their structures: [equipment-structure.md](equipment-structure.md); the
screens: [client-screens.md](client-screens.md); FE cables and control: [wired-control-power.md](wired-control-power.md).

## Read this when

- A casing stands while its driver runs, a train stands with `NO DRIVER`, `TWO DRIVERS` or `GEAR LOOP`, or a part's page
  reads `LOOSE` or `HELD`.
- A motor, steam turbine, generator, gearbox, creative motor, prime mover or Create coupling does not turn, says
  `TRIPPED: NO POWER`, `NO ROTATION`, `NO FUEL` or another status word at the wrong time.
- A unit's FE port takes or gives the wrong energy, an air cooler or induced tower says `NO POWER` or `UNMETERED`, or a
  config key under `energy`, `create` or `machines.wear` misbehaves.
- A pump, a dry screw, a centrifugal compressor or a steam turbine loses health, fails (`FAILED`), or a repair kit is
  refused.
- A machine's page, a casing's Drive group, a pump's Suction and Health groups or its curve show the wrong numbers.
- A running column's pumps or fans book the wrong power, or Start refuses a column whose machines have no driver.
- You add a driver kind, a shaft part or a Create-facing class.

## What it does

Machines and drivers are joined by **shaft blocks** into **trains** (`ShaftNetwork.compile`): one driver per train,
gearboxes join lines. Parts change by queued events (`ShaftTrains.Event`), applied at the tick drain. At an island's
dispatch the engine sends each driven machine its speed share, a reciprocating frame its displacement, a governor valve
its target, a fan unit its power limit and a failed machine its closure as `ShaftPlan`s. At a commit the trains record
each machine's shaft power; at the coordinator's decision points each train that booked steps its driver once
(`ShaftTrain.step`). Drivers, fans and generators have **electric stores** in joules behind an FE port at their terminal
box (`ElectricStores`, `TerminalEnergy`). Machines with a fault keep a **health** fact that their faults wear down
(`MachineHealth`); a repair kit restores it. With Create installed, a **Create coupling** or a native Create shaft drives
a train at Create's speed up to the stress its network spares (`compat/create`). Nothing here runs on the tick loop.

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/`.

| Class | Role | Lines |
|---|---|---|
| [`science/fluid/network/ShaftPlan`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ShaftPlan.java) | A slice input keyed by device identity: `Kind` SPEED (share of rated), DISPLACEMENT, GOVERNOR, FANS, FAILED (0 closes, 1 reopens), BURNERS, FUEL, FLARE, EXHAUST; `apply(graph, plans)`, `planned` | 151 |
| [`runtime/fluid/ShaftTrains`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ShaftTrains.java) | The world's trains, `IslandCoordinator.Shafts`: `plans` (at dispatch, reads), `booked` (at commit and replay, records), `decide` (one driver step per train per tick for the trains that booked); `Event` Put, Remove, Configure, Reset, Governor, CreateInput, Hold (at most `MAXIMUM_EVENTS`), `apply` at the tick drain; `held` (a column's member), `failures`, `view`, `unitOf`, `driverOf`, `drawnSize`, `forget`, `time()`; `STEAM_K` | 1297 |
| [`runtime/fluid/ShaftUnit`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ShaftUnit.java) | A part as saved: id, kind, position, axis, `End`s, fluid device, rated power, design speed, poles, drive, `Settings(running, mode, setPoint, powerLimit, ratio)`, trip; `State(speed, previousSpeed, previousMeasured)` in the topology state; `held`: engine state, in the topology state's held list, never a fact | 90 |
| [`runtime/fluid/ElectricStores`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ElectricStores.java) | One store per powered unit (motor, generator, air cooler side, induced tower basin): `receive`/`extract` (FE now, nothing wakes), `draw`/`give` (at a train's step), `booked` (fans and in-line movers at commit), `member` (a column's member), the cadence every `CADENCE` ticks (rest booking, restart, facts to the ledger); `fanDraw`, `towerDraw`, `feJoules`; `Settings(joulesPerFE, needPower)` | 607 |
| [`runtime/fluid/MachineHealth`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/MachineHealth.java) | Health per machine device (a fact only below 1): `faults(control)`, `worn`, `pump(registration)`, `booked`, `cadence`, `plans` (FAILED), `queue`/`apply` (a `Repair`); each device's standing fault and its intensity (state); `status` words; `GAS_RATIO` | 432 |
| [`runtime/fluid/PrimeMovers`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/PrimeMovers.java) | Driven by `ShaftTrains`, on the server thread: the heat fired from the fuel void's inflow times its LHV (`Burners.perKilogram`), the governor's fuel target and a gas turbine's coil gas; status words | 232 |
| [`runtime/fluid/ColumnPower`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnPower.java) | A running column's members' power: a casing (`ShaftTrains.unitOf`) to its train, an in-line pump to its own store; `undriven` | 68 |
| [`runtime/fluid/MachineView`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/MachineView.java), [`MachinePresentation`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/MachinePresentation.java) | One part's page view (status, speed, shaft power, electric power and FE/t, settings, trip, rating, store share and seconds left, governor valve, gearbox ratio, turbine steam, `Coupling`, `Fuel`); sessions: replay on open, the buckets after, one unanswered edit per session | 129, 101 |
| `world/level/block/ShaftBlock`, `ShaftParts`, `ShaftCommand`, `CreativeMotorBlock` | The `shaft` block (drawn size from the train's torque, `ProcessUnit.shaftPixels`; before its train draws it `ProcessUnit.SHAFT_DEFAULT_SIZE`; no block entity); the queued part events; `/icheme shaft` (operators: list, settings, start, stop, reset, ratio); the `creative_motor` block | 81, 150, 93, 97 |
| `world/level/block/entity/TerminalEnergy` | The FE capability on a structure's terminal cell, every face; consumers receive, a generator extracts | 90 |
| `world/item/RepairKitItem` | `repair_kit`: queues `MachineHealth.Repair` on a mover casing cell, a steam turbine cell or an in-line device; consumed only when accepted | 98 |
| [`network/MachineNetwork`](../../../src/main/java/com/wormzjl/icheme/network/MachineNetwork.java), `world/inventory/MachineMenu` | `machine_state`, `machine_edit` (checked at receipt, queued as a shaft event), `machine_open_driver` (a casing's or turbine's page opens its driver's), `machine_datasheet` | 425, 124 |
| `client/gui/screens/inventory/MachineScreen`, `client/gui/common/MachineColumn`, `PumpCurve`, `MotorCurve` | The machine page and its door; the pump's curve (H(Q), NPSH3(Q), 4 × NPSH3 dashed, NPSHA); the motor's curve | 400, 283, 201, 72 |
| `client/DriverSounds`, `client/MoverSounds` | The drivers' and movers' loops on their watches, handed to the one sound budget ([effects.md](effects.md)); the plunger pump has its own loop (`MoverSounds.casingLoop`) | 135, 126 |
| `compat/create/*` (`CreateCompat`, `CreateCouplingBlock`, `CreateCouplingBlockEntity`, `CreateCouplingRenderer`, `CreateCompatClient`, `CreateShaftBlock`, `CreateShaftBlockEntity`, `CreateBridgeBlockEntity`) | The only package that names a Create, Ponder, Flywheel or Catnip type; `IChemE` calls `CreateCompat.register` only when `create` is loaded. The coupling is a stress consumer; `CreateShaftBlock` is the industrial shaft with a native Create endpoint (`CreateBridgeBlockEntity`): kinetic callbacks queue observations, engine decisions publish a finite shaft capacity | 56 to 188 |

## Main flow

1. A placed structure with shaft ends, a shaft block, a creative motor or a Create coupling queues a `Put`
   (`ShaftParts`); a removal a `Remove`. They apply at the tick drain (`ShaftTrains.apply`), and only then do trains
   recompile (`ShaftNetwork.compile`). A train is state; its key is its lowest part identity.
2. At an island's dispatch, `ShaftTrains.plans` gives each driven machine its speed share (from the driver's speed and
   the gear ratios), a frame its displacement, a governor valve its mass target, a fan unit its limit and a failed
   machine its closure, in one map per dispatch in a fixed order (fans, burners, prime movers, flares, shares and
   governors, then health). `ShaftPlan.apply` writes them into the graph.
3. The worker solves every mover on its law. At the commit `booked` records each machine's shaft power and a turbine's
   `SHAFT` transfer; fan units and in-line movers book their draw (`ElectricStores.booked`); machines book their wear
   (`MachineHealth.booked`).
4. At the decision points `decide` steps each train that booked: demand is the driven machines' booked shaft power over
   their gears; `ShaftTrain.step` gives the next speed, trip and status. A motor draws from its store
   (`ElectricStores.draw`; an empty store gives `NO_POWER` and steps again stopped), a generator gives into its own
   (`give`, within `SPEED_WINDOW` of its rated speed). A speed past the deadband, a trip, a start or a reset wakes the
   driven islands (`IslandCoordinator.shaftsMoved`).
5. On the presentation cadence a resting unit books its last power, a stopped unit whose store holds its restart share
   restarts, a machine at 0 health fails, and the stores' and health facts go to the ledger. Pages and the Drive, Power,
   Suction and Health groups read what was decided; no read solves.

## Contracts

**Plans and booking.**

- A casing's speed is not a fact: `FlowControl.Machine.speed` is the train's slice input; a connection that differs from
  the dispatched one only by its plan is the same control (`ShaftPlan.planned`); a change of speed reaches a certified
  island only through `shaftsMoved`.
- `plans` holds a train's `Run` (power, output and duty as arrays aligned with `train.driven()`); `Duty` solves each
  speed share on the step's first read; `publish` after `decide` or `held` walks only the stepped trains' parts. The units
  are `WorldTopologyLedger.shafts()`, a read-only live view, with a cell index (`shaftAt`).
- What the driver pays is `InlineMovers.shaftPower(mover, massFlow, head, density, pressure, kappa, wetShare)`, the
  solve's own function at the slice's average flow, the booked head and the suction's heat capacity ratio and wet share
  (`PassiveStepSolver.suctionKappa`, `wetShare`): a machine its law's shaft power over the wet share; a set-flow pump its
  ideal work over `InlineMachines.SET_FLOW_PUMP_EFFICIENCY`; any other mover its power. `ShaftTrains.booked` (machines,
  compressors) and `ElectricStores.booked` (in-line movers) charge it. Electricity in = work to the fluid + a pump's
  hydraulic loss + a compressor's recycle work below surge + the motor's and drive's losses (`Motor.electricPower`,
  `InlineMachines.electricPower`).
- A failed machine (health 0) gets a `FAILED 0` plan: a machine at no speed, a set-flow pump switched off, a turbine's
  admission shut, its settings kept. `ShaftTrains.failures` merges the health plans into the trains': a health
  `FAILED 1` (a repaired pump back on) never replaces a `FAILED 0` already planned (an unpowered in-line pump's close), a
  health `FAILED 0` replaces anything. After a repair a turbine's `GOVERNOR` plan opens it again.

**Electricity.**

- 1 FE carries `energy.joulesPerFE` × the time factor J of process energy (`ElectricStores.feJoules`), so a machine draws
  the same FE per tick at any factor; stores keep joules, so a config change keeps the energy they hold. Draws and
  bookings integrate `ticks × time.tickSeconds()`. `energy.machinesNeedPower` false books nothing and stops nothing.
- Powered units: a motor or generator (shaft unit), an air cooler's process side (its `HeatUnit.fanCount` fans, one
  `FAN_POLES` motor each), an induced-draft tower's basin (one store for every cell's fan). Terminal cells:
  `ProcessUnit.terminal` (a motor's terminal box; the air cooler's and the tower's basin rim).
- Store windows are counts of cycles (`ElectricStore`), the same play time at any factor; a motor's store keeps its own
  buffer and restart share (`MOTOR_BUFFER_PERIODS`, `MOTOR_RESTART_PERIODS`). A motor also needs a live FE source:
  losing its cable or source stops it at the next engine decision even if stored joules remain (`PowerNetwork`,
  `PowerGrid` check the source without moving power). A non-motor unit saved without a store keeps `UNMETERED`.
- An empty store stops a motor (`TRIPPED: NO POWER`, not latched) and holds a fan unit's fans at `fanLimit` 0
  (`AirCooled.withFanLimit`, `CoolingTower.withFanLimit`): the louvers stay open and the air falls to natural draught.
  The fan limit is a dispatch input, never a fact.
- A generator's load is the room its store has left (`generatorLoad`), so a full store sheds load.
- A new in-line mover takes a metered, empty store and stands closed with NO POWER until it receives FE.

**Health and repair** (`MachineHealth`).

- `faults(control)`: a centrifugal pump CAVITATION, DRY, LOW_FLOW; another liquid mover CAVITATION, DRY; a dry screw
  LIQUID; a centrifugal compressor (casing or in-line) WET_GAS; a steam turbine WET_EXHAUST. `booked` reads each fault's
  intensity at the committed slice: `PipeObservation.npshRatio`, `dry`, the screw's liquid share, for LOW_FLOW the
  committed flow over the suction's density against the rated flow at the speed share, for WET_GAS
  `PipeObservation.condensedShare`, for WET_EXHAUST the condensed mass share of the turbine's own exhaust (one PH
  equilibrium at the exhaust pressure and the inlet enthalpy less the shaft work, `PassiveStepSolver.exhaustMoisture`, less the
  onset's rise at the row's second-end pressure, `Wear.wetExcess`; no
  read where it does not answer). Booked over the slice's process time (`MachineHealth.book`).
- A repair kit on any mover casing (`Kind.mover()`), a steam turbine or an in-line device's pipe block queues
  `MachineHealth.Repair`: every device with a health record (`worn`) is repaired, another answers `NOT_A_PUMP`. A failure
  and a repair wake the island. `machines.wear.enabled` false books nothing and closes nothing.
- Status words, in priority on the message display (`FluidWorldAuthority.withHealth`): `FAILED` > `LIQUID` >
  `LOW FLOW` > `CAVITATION` > `GAS` > `WET GAS` > `WET EXHAUST` (raw words, no lang key). On the casing's shaft row
  (`ShaftTrains.view`) `FAILED` replaces every train word; a fault word replaces only the train's own run word.

**Readings and sounds.** `FluidView.Pump` (NPSH available and required, gas share, health, `lifePlaySeconds`, a casing's
`Curve`); the device screen's Suction and Health groups and `PumpCurve`; a compressor's and a turbine's health on their
page, their NPSH rows "—". `MoverState` carries the speed share, a pump's crackle and churn bytes and an in-line pump's
rpm; loops `pump_cavitation`, `pump_gas` over the casing loop; drivers play `motor_running`, `generator_running`,
`turbine_running`, `engine_running`, `engine_running_medium` (`UnitDesign.mediumSpeedEngine`), `gas_turbine_running`
(`DriverSounds`, watched by `driver_watch`/`driver_state`); pitch is rpm over the loop's authored rpm
(`UnitDesign.PUMP_LOOP_RPM`, `MOTOR_LOOP_RPM`).

**Prime movers** (`PrimeMovers`). A target past the valve deadband wakes the fuel island. Status words `NO FUEL`,
`WRONG FUEL`, `FUEL NOT COMBUSTIBLE`, `LOW FUEL PRESSURE`, `LOW FUEL`. State only: a load starts every set unfired and it
lights itself. The control valve source `ENGINE`; the heat unit `PRIME_MOVER` with side `EXHAUST`
([heat-exchangers.md](heat-exchangers.md)).

**A running column's members** (only under `energy.machinesNeedPower`). A running column holds its pumps and air-cooler
fan sides, so no island commit books them; the column books them at each cycle ([column-run.md](column-run.md)):
`ShaftTrains.held(device, shaftPower)` stands for a held casing's island booking and steps its train at once, at most
once a tick (true when the driver turns with no fault and no trip); `ElectricStores.member(id, power, to)` draws for a
fan side or an in-line pump, then reports `powered`; `InlineMovers.ratedShaftPower` is a held pump's power at its rated
flow on the design fluids (`ratedDraw` its electric power), booked times the cycle's share of the set feed;
`ColumnPower.undriven` finds a casing whose train has no driver, which Start refuses.

**Registry first.** A chunk load queues its shaft parts (`WorldTopologyLedger.shaftsIn`). The re-alignment
(`RegistryRealignment`) places a lost shaft block on its unit's axis at its drawn size (`ShaftTrains.drawnSize`), a
creative motor facing its end, a Create coupling with its shaft side at its end (only with Create:
`ShaftParts.couplingState`), and turns one standing another way back. A part whose cell another block holds is held (a
queued `Hold`): it leaves its train, which splits there, its page says HELD, and its identity joins the topology state's
held list; a held structure holds its unit (`StructureRealignment`). A player's placement in the cell rebuilds the part.
`ShaftBlock`, `CreativeMotorBlock` and `CreateCouplingBlock` queue nothing while `FluidDeviceBlock.quiet()`. A world
opened without Create forgets its coupling units (`ShaftTrains.forget`).

## Laws and constants

The laws are in [rotating-machinery.md](rotating-machinery.md). Here: `ShaftTrains.MAXIMUM_EVENTS`, `STEAM_K`;
`ElectricStores.CADENCE`; `MachineHealth.GAS_RATIO`; `ProcessUnit.SHAFT_DEFAULT_SIZE`. Config (`icheme-common.toml`,
read by `IChemE.joulesPerFE`, `machinesNeedPower`, `stressPerFE`, `wearSettings`): `energy.joulesPerFE`,
`energy.machinesNeedPower`, `create.stressPerFE`, `machines.wear.enabled`, `machines.wear.cavitationProcessHours`,
`machines.wear.dryProcessMinutes` (process time).

## Formats

- Facts ([FORMATS.md](../FORMATS.md#saves), `FluidFacts.SCHEMA`; the machine section `MachineFacts`,
  [runtime-saves.md](runtime-saves.md)): the shaft units, the electric stores, an air cooler side's fan count and the
  machines' health below 1; `ShaftTrain.Mode` and `Trip` by name. Corpus images under `src/test/resources/compat/fluid/`.
- State: a driver's `ShaftUnit.State` and the held list in the topology state; trains, plans, standing faults are not
  saved.
- Wire on `NetworkProtocol.VERSION` ([FORMATS.md](../FORMATS.md#wire-protocols)): the machine pages, the mover and
  driver watches, a casing's `FluidView.Drive`, a fan unit's `FluidView.Power`, a pump's `FluidView.Pump`.
- Create is optional (`neoforge.mods.toml` `optional`); Create, Ponder, Flywheel, Registrate and KubeJS are
  `compileOnly` plus `optionalModRuntime`; `-PwithoutCreate` drops them from the dev, test and GameTest runtimes
  ([verification.md](verification.md)).

## Tests and gates

| Rule | Owner | Test |
|---|---|---|
| A driver's speed, trip, status in the world | `ShaftTrains.decide` | `ShaftCouplingTest`, GameTest `ShaftTrainGameTests` |
| A mover's row, cavitation, gas, turbine connection, plans | `FlowControl`, `PassiveStepSolver`, `ShaftPlan` | `MachineMoverTest`, `TwoPhaseMoverTest`, `SteamTurbineConnectionTest`, `ShaftPlanTest`, `PumpCavitationTest` |
| FE stores, NO POWER, generator | `ElectricStores`, `TerminalEnergy` | `HeatUnitPowerTest`, GameTest `ElectricGameTests` |
| The time factor on stores and wear | `ElectricStores`, `MachineHealth` | `TimeFactorElectricTest`, `MachineHealthTest` |
| Health, failure, repair | `MachineHealth`, `RepairKitItem` | `MachineHealthTest`, `MoverHealthStatusTest`, GameTest `PumpHealthGameTests` |
| What a driver pays for its machines | `InlineMovers.shaftPower`, `ShaftTrains.booked`, `ElectricStores.booked` | `ShaftPowerBookingTest` |
| Create coupling | `compat/create/*` | GameTest `CreateCouplingGameTests` (with and without Create) |
| Saved shaft units, stores, health | `FluidFacts`, `WorldTopologyLedger` | `FluidFactsCompatTest`, `FluidCheckpointCodecTest` |
| Page and group data | `MachineView`, `MachinePresentation`, `MachineNetwork`, `MachineColumn`, `PumpCurve` | `MachineColumnTest`, `PumpCurveTest`, `DeviceColumnTest`, `FluidPacketCodecTest` |

## Traps

- **Nothing outside `compat/create` may name a Create type**: a pack without Create must load (`client/Outlines`
  replaced Catnip's outliner for that reason).
- **A train is state**: its key, its run and its plans are recompiled; only the units are facts.
- **A held casing books through the column**, not its island: reading `ShaftTrains.booked` alone misses a running
  column's pumps.
- A part whose cell another block holds leaves its train and splits it: a train can lose its driver to a held part and
  stand with `NO DRIVER` until the cell is cleared.

**Snapshot:** `410c37fcb`, 2026-11-16.
