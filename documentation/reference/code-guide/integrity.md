# Integrity: corrosion, erosion and fouling laws

Up: [code guide](README.md)

## Read this when

- A wall's corrosion rate, its governing mechanism or its life looks wrong for its material and fluid.
- A hydrogen or oxygen service limit, or a material factor, needs a number.
- A valve trim, a heat-side wall or a pump erodes too fast or not at all; the `SOLIDS` wear fault.
- A heat side's fouling deposit, its asymptote or its level steps.
- A crude cut's sulfur, acid number or fouling number (`corrosion` sub-record).

## What it does

`science/integrity/` holds the pure laws (their numbers and sources:
`documentation/2026-11-16-fouling-corrosion/FOULING_CORROSION_PLAN.md`): no Minecraft types, no state. A
`CorrosionStandard` built per compound list turns a node's state into a `CorrosionDigest` (temperature, wet and oil
flags, the oil's sulfur, acid and fouling numbers, partial pressures of H2S, CO2, SO2, NH3, O2, H2). `CorrosionLaw`
gives a wall's inside rate on it for one of four materials (`Metallurgy`) and the outside rate from the corrosive air
index; `ErosionLaw` gives particle erosion; `FoulingLaw` the Kern–Seaton deposit; `WallState` the stage and life.

`runtime/fluid/Integrity` books them on the walls of constructed equipment: a device a structure registers with its
nozzle ports (never a one-block device, a pipe or a port lane) that is a closed vessel, a heat side or a machine that
wears. Each has a wall loss (mm), a fouling deposit (m²K/W) and `wrecked` as facts; its rates are state. A wall through
fails by the failure its kind already has; a blast wrecks the structure for good; the Equipment Constructor's repair
and cleaning are queued engine events, accepted only while every device is idle and every vessel empty. A control
valve's trim and a mover's `SOLIDS` fault wear the machine health of [shaft-trains.md](shaft-trains.md).

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/`.

| Class | Role |
|---|---|
| `science/integrity/Metallurgy` | The four materials (saved by name, append-only, `byName` defaults to carbon steel): allowance, overheating limit, cost index, factors of table 6.3, the hydrogen and oxygen limit lines |
| `science/integrity/Mechanism` | M1 to M11 and erosion; the status names the governing one |
| `science/integrity/CorrosionDigest` | The digest record (plan 6.1) |
| `science/integrity/CorrosionStandards` | The one builder of a package's standards per compound list, by name (`of`, `on`): the world's walls (`FluidWorldAuthority.corrosionStandards`) and the simulator's Materials tab read the same compounds the same way |
| `science/flowsheet/metallurgy/SheetMetallurgy` | The process simulator's Materials tab: the same laws on a solved sheet's streams, on the client ([simulator-sizing.md](simulator-sizing.md)) |
| `science/integrity/CorrosionStandard` | Per list: molar masses, sulfur (formula, else the sub-record), acid and fouling numbers, the gases found by exact formula; `digest(...)` |
| `science/integrity/CorrosionLaw` | M1 to M7 on carbon steel (`rate`), the material's M8, M10, M11, the inside rate (`inside`), M9 (`outside`) |
| `science/integrity/ErosionLaw` | Wall erosion, trim speed and trim wear, the pump's solids intensity |
| `science/integrity/FoulingLaw` | `Rf∞` from the digest (`PLACEHOLDER_SHARE`, half the TEMA anchor), the exact exponential step, levels of 5e-5, thickness |
| `science/integrity/WallState` | `SOUND`, `THIN`, `WEEPING`, `THROUGH` at u = 0.5, 0.9, 1; the life in process seconds |
| `science/machine/Wear.Fault.SOLIDS` | The pump's erosion-class fault; its intensity is `ErosionLaw.solidsIntensity` |
| `science/material/MaterialCatalog.Corrosion` | The `corrosion` sub-record; `corrosion(...)`, `sulfurShares`, `acidNumbers`, `foulingNumbers` |
| `runtime/fluid/Integrity` | The walls: `kind`/`constructed` (which devices), `booked` (keeps an island's last result), `cadence`, `wreck`, `queueRepair`/`queueClean`/`apply`, `Settings`, reads `wall`, `fouling`, `foulingLevel`, `wrecked`, `kindOf`, `status`, `fires`; `set` for the command and the tests |
| `runtime/fluid/WorldTopologyLedger.IntegrityFacts`, `IntegritySection` | The facts: one entry per device away from new (loss, fouling, wrecked), the last facts section |
| `runtime/fluid/FluidDeviceSpec.metallurgy` | A device's material, a fact (carbon steel by default) |
| `runtime/fluid/FluidWorldAuthority` | Builds `Integrity` with its `IntegrityHost` (close = breach, open = repair, release = `releaseContents`, the corrosive air index, `corrosionStandards`); `queueStructureRepair`, `queueClean`, `closable`; drains the events at the tick, runs the cadence after the health's |
| `runtime/fluid/PhysicalRegistry.closedSides` | A breached heat side's passage compiles with fitting mask 3 (closed both ways) |
| `runtime/fluid/MachineHealth` | `trim`, `tracked`, `solidShare`; trims and `SOLIDS` in `booked`; `ShaftPlan.trim` in `plans`; `trimStatus`; `renew`; a kit only while `idle` |
| `science/fluid/network/ShaftPlan.Kind.TRIM` | A failed trim stands as `ControlValve.unconfigured()`; a repaired one takes its registered control back |
| `runtime/pollution/Breaches` | Wrecks every device of a blasted structure; heat sides close and release once |
| `runtime/pollution/IntegrityCommands` | `/icheme integrity get`, `set <loss> <fouling> <wrecked>`, `clear`, by `id <device>` or `at <pos>` |
| `world/item/RepairKitItem` | Refused on constructed equipment (`USE_CONSTRUCTOR`); queues a `MachineHealth.Repair` on an in-line pump's, compressor's or control valve's pipe block |
| `world/level/block/EquipmentRepair` | The constructor's repair of the structure on its line: `repair` (its node devices to `queueStructureRepair`; a column to `FluidWorldAuthority.queueColumnRepair`; another structure with no fluid device answers `NOTHING_TO_REPAIR`), `key` (answer text), `state`, `column` and `words` (worst used allowance and `THIN WALL`, `WEEPING`, `LEAKING`, `FAILED`, `WRECKED`, a column's `TRAYS FOULED`) |
| `science/integrity/ColumnWear`, `runtime/fluid/ColumnOperation` | A running column's trays and wall: see [Columns](#columns) |
| `world/level/block/EquipmentDeconstruction.checked` | The removal group's shared check (reach, build rights, target origin and stamp, loaded and interactable cells; break events only for a removal) |
| `network/EquipmentNetwork.Repair`, `RepairAnswer`, `StructureState`, `StructureStateAnswer` | The Repair key's request and its answer from the event's reply (dropped when the player left); the state, a read answered at once |
| `client/gui/common/ConstructorColumn.Removal`, `EquipmentConstructorScreen` | Repair beside Deconstruct; the state line after the keys; asks the state when the target changes and after a repair's answer |
| `runtime/fluid/FluidView.Integrity`, `FluidView.Trim`, `ViewBuilder.wall`, `trim` | The page's wall (used share, rate, mechanism, life in play seconds, material) and a trimmed control valve's trim (health, life) |
| `client/gui/common/DeviceMeters.wall`, `trim`, `DeviceColumn.integrity`, `trim` | The Integrity and Trim groups after the Phases (their names wrap, so they stand below the basis key); names under `gui.icheme.mechanism.*` and `gui.icheme.metallurgy.*` |
| `Integrity.weeps`, `Host.weeping`, `EquipmentShellBlockEntity.weeping` | A weeping wall marks its node's shell cell at every cadence (the cell sends its update tag only when its flag changes; never saved) |
| `client/WallWeeps` | Drips from the structure's lowest shell cells (one per 10 ticks a structure, at most 16 a tick) and the `wall_weep` loop through `FxSounds`, both within 8 blocks |
| `science/fluid/network/HeatCurve.Films.fouled`, `PipeResistance.Geometry.narrowed` | The deposit's effect: each film `h/(1 + h Rf)`; the passage less twice `FoulingLaw.thickness`, at least half its bore; the same instance at 0 |
| `science/equipment/HeatSides.heatSide(..., fouling)`, `HeatUnit.heatSide(double)` | The fouled law of a side: an exchanger's, a kettle's tubes', a still's water side's films; an air cooler's `AirCooled.Air.process`; a convection coil's `1/U' = 1/U + Rf`; a radiant or exhaust coil's heat reads no film (passage only) |
| `runtime/fluid/PhysicalRegistry.fouling`, `PhysicalFluidTopology.compile(..., fouling)` | The levels the compile reads, from the ledger's facts (`FoulingLaw.level`; none with `fouling.enabled` off); a fouled side's pipe carries the fouled law and the narrowed passage |
| `Integrity.flushAndRestate`, `Host.restate`, `FluidWorldAuthority.restate` | When a side's level moves (cadence, cleaning, `set`): the facts to the ledger, then the side's registration again, unchanged, at revision + 1, which recompiles its island |
| `FluidWorldAuthority.queueUnitClean`, `network/FluidNetwork.CleanPayload`, `HeatControlColumn.deposit` | The heat page's Deposit group (fouling, UA left: the unit's UA over its clean UA) and Clean: the side and its partner heat side queued; the answer a chat line (`message.icheme.heat.clean.*`); `FluidView.Heat.fouling`, `ua`, `uaClean`; `Integrity.fouled` says `FOULED` from 0.0005 m²K/W |

## Flow

1. Every commit or replay reaches `ShaftTrains`, which hands the island's result to `MachineHealth.booked` (wear of
   movers and trims) and `Integrity.booked` (keeps the result and its end tick; no arithmetic).
2. At the cadence (`MachineHealth.CADENCE`), after the health's: discovery sweeps at most 4096 registrations; every record
   books `rate × Δt / year` and a flowing heat side its deposit (`FoulingLaw.step`), Δt in process seconds at the time
   factor. One round-robin evaluates up to 256 records whose island booked a new span: the digest of the vessel's node or
   the hotter end of a heat side's passage or a machine's connection, the inside rate and a heat side's erosion on an island
   that moved solids. Another round-robin samples corrosive air (`PollutionService.measuredIndex`, `CORROSIVE`) for up to
   256 registered walls regardless of their islands' bookings or their chunks' presence. Both `corrosion.enabled` and
   `corrosion.external` must be on to book outside wear. A wall through fails
   once: a vessel's spec is breached, a heat side's spec is breached (its passage compiles closed) and its holdup released,
   a machine's health is breached. The facts go to the ledger.
3. A repair or cleaning is queued (`queueStructureRepair`, `queueClean`) and applied at the tick drain: `WRECKED`,
   `NOTHING_TO_REPAIR`, `NOT_IDLE` (a connection at the device's node, or a heat side's passage, moved more than 1e-10
   kg/s over the island's last slice, or a mover of it runs), `NOT_EMPTY` (a vessel with 1 % of its volume liquid or
   more than 0.2 bar from the ambient), else `REPAIRED` (loss 0, breach closed, machine health 1) or `CLEANED`.

4. The player side: the constructor's Repair re-checks the structure (`EquipmentDeconstruction.checked`, no break
   events) and queues one event (`EquipmentRepair.repair`); the reply sends `RepairAnswer` and plays the smithing table sound at
   the structure on `REPAIRED`. `StructureState` reads `Integrity.wall`, `wrecked`, `MachineHealth.failed` and the breach
   of the structure's node devices. `ViewBuilder` adds `FluidView.integrity` and `trim` to every view it builds; their words
   stay on the status line, said once. At each cadence and after a repair, `Integrity.weeps` tells the host each weeping
   wall; the shell cell carries `Weeping` on its update tag to the clients that track it, and `WallWeeps` drips and plays.
5. The deposit's effect: the graph sees a side's deposit only in whole steps of 5e-5 m²K/W. Each record keeps the
   level its graph was compiled with (state; on a load, the facts' level, which the restored graph and its pending events
   carry). After the cadence, a cleaning or a `set`, `flushAndRestate` hands the facts to the ledger, then queues one
   `restate` for each side whose level moved; the registry's next compile of that island reads the level from the ledger
   (`PhysicalRegistry.fouling`). Between steps nothing the solver reads changes; a clean side compiles as before.

## Columns

A distillation column is no fluid device: its `ColumnUnit` carries its own wear as facts (`trayFouling` 0 to 1,
`wallLoss` mm, `material`, taken from its shape at Start by `ColumnStarts`), and `ColumnOperation` books them in the
cycle's own ledger update, beside the totals (plan section 13.1; numbers in `ColumnWear`).

1. `φ` = the inert solids the cycle moved to the bottoms over the feed mass it took plus those solids.
2. Fouling: `f` approaches `min(1, φ/0.02)` with `fouling.asymptoteProcessHours`; it never falls by itself. The cycle's
   `Rooms.trays` is `1 − 0.4 f`: `ColumnCycle.step` divides both flood fractions (the FLOODING share and HIGH LOAD) by it;
   1 leaves the cycle as before, bit for bit. `TRAYS_FOULED` (notice) from `f` ≥ 0.5.
3. Wall: rate = corrosion part (`CorrosionLaw.inside` on the bottoms of the installed table: the bottoms' recoveries of the
   table's feed, its temperature, the bottom pressure; water in the aqueous slot, the rest oil; computed once per installed
   table, `Run.corroded`) + solids part (`min(5, 0.5 φ/0.01)` × the erosion factor). Booked as `rate × dt / year` for
   each cycle that ran. Stages by `WallState`: `THIN_WALL`, `WEEPING` (notices); through, `LEAKING` (TRIP, derived
   from the saved loss at every cycle while corrosion or erosion is on): the inlets close and no solve is asked.
4. Repair (the constructor) and Clean (the Operation page's footer key, `AssemblyNetwork.Action.CLEAN`) are queued on
   `ColumnOperation` (`queueRepair`, `queueClean`, `apply` at the tick drain): `NOT_IDLE` while the unit runs, else
   wall 0 (`REPAIRED`) or fouling 0 (`CLEANED`), or `NOTHING_TO_REPAIR`. A stopped unit keeps its record, so the facts
   survive a Stop. The view (`OperationView.Wear`) carries the wear; `OperationColumn.wear` draws it as a table on the
   glass, and `OperationColumn.footerButtons` places Clean on the footer.

Tests: `ColumnWearTest` (the numbers), `ColumnCycleTest` (capacity 1 exact, 0.8 floods at 80 %), `ColumnRunTest`
(solids foul and limit the feed; corrosion alone without solids; LEAKING, repair and clean refused running, accepted
stopped, the column runs again), `FluidFactsCompatTest` (the facts, older images, an unknown material refused),
`OperationColumnTest`, `ConstructorColumnTest`. Not built: a leaking column's release to the air; blasts on columns.

## Formats

- Fluid facts schema 29: `FluidDeviceSpec.metallurgy` (by name) and the `IntegritySection` (see
  [runtime-saves.md](runtime-saves.md) and [FORMATS.md](../FORMATS.md)). Rates, mechanisms, asymptotes and the cursor
  are state and are not saved; so is the level a side's graph carries (the level is read from the facts).
- Fluid checkpoint state format 52: an air cooler's law (tag 3) carries its process films; a heat side's films (tag 1) and
  the cooler's process films are followed by the deposit they hold, and each of the two laws ends with the unit's UA as
  last planned (a flag, the UA, the clean UA). A narrowed passage is the pipe's own geometry.
- Config: `corrosion.enabled`, `corrosion.yearProcessHours` (200), `corrosion.external`, `erosion.enabled`,
  `fouling.enabled`, `fouling.asymptoteProcessHours` (100). The two defaults live once, in
  `WallState.DEFAULT_YEAR_PROCESS_HOURS` and `FoulingLaw.DEFAULT_TIME_PROCESS_HOURS`. A switch off stops its part of the rate at once and nothing
  fails while corrosion and erosion are both off.

## Laws and constants

- Rates in mm/y; one corrosion year is `corrosion.yearProcessHours` (200 process hours by default). Every rate is finite, 0 for NaN
  or negative inputs, at most `CorrosionLaw.MAX_RATE` (1e4 mm/y).
- M1 sulfidation `1.3 exp(−8698 (1/T − 1/643)) (S_e/0.6)^0.4` from 503 K; M2 naphthenic acid; M3 to M7 the wet
  mechanisms, each a partial-pressure term times a temperature term. Material factors multiply M1 to M7 and M9;
  `p_H2` ≥ 7 bar lifts chrome steel's sulfidation factor from 0.06 to 0.6.
- M8 `0.25 · 2^((T − limit)/25)`; M10 `0.5 · 2^((T − T_H)/25)` above `max(T_floor, T_7 − s log10(p/7))`; M11
  `50 p_O2 / p_limit` above `max(0.3, p_150 2^(−(T − 150 °C)/100))`.
- Erosion `(φ/0.01) (v/10)^2.6` mm/y; a trim `(φ/0.01) (v/30)^2.6` health per corrosion year, `v = sqrt(2Δp/ρ)`; a pump
  `clamp((φ − 0.001)/0.05, 0, 1)`.
- Fouling cap 0.002 m²K/W, step 5e-5, `FOULED` from 0.0005, deposit conductivity 1 W/mK; the end value `Rf∞` is half the
  TEMA anchor (`PLACEHOLDER_SHARE`): water 0.000175 (0.00035 above 50 °C), oil half its anchor, which is the cuts'
  number, from 260 °C `max(f, min(1.8 f, 0.0009))`, plus half of `min(0.0005, 0.02 φ)` for solids; at most 0.00115.

## Tests and gates

`src/test/java/com/wormzjl/icheme/science/integrity/` (`MetallurgyTest`, `CorrosionLawTest`, `ErosionLawTest`,
`FoulingAndWallTest`, `CorrosionStandardTest`) hold every anchor of plan sections 3.1, 5, 6.2 and 8 (to 1 %, or the
formula's own value where the plan rounds further), the factor table, monotony and the NaN guards.
`MaterialCorrosionTest` holds the twelve records, the assays, refusals and the no-identity check; `WearTest` the
`SOLIDS` row; `LayeringTest` keeps `science/integrity` below the machine, network, column, plant, sheet and equipment
packages. `runtime/fluid/IntegrityTest` holds the runtime (rates at the time factor, the 256 bound and the round-robin,
failures once by kind, wrecked, idle-and-empty, cleaning, corrosive air, the switches); `MachineHealthTest` the trims;
`HeatSideTopologyTest` the closed passage; `IntegrityGameTests` a corroded drum repaired once drained and a failed trim
standing open, and the constructor's repair path mending a weeping drum (the cell's flag set, then cleared); `PollutionBreachGameTests` a
wrecked drum; `PumpHealthGameTests` the kit refused on a casing. `IntegrityTest` also holds the weep calls; `EquipmentRepairTest`
the answers' lines and the words; `EquipmentNetworkCodecTest` the four payloads; `DeviceMetersTest` the wall and trim readings
and their JSON; `DeviceColumnTest` and `ConstructorColumnTest` the groups and the Repair key at every width.
`FoulingEffectTest` the films, the passage and each law against plan section 3.2's table; `HeatSideTopologyTest` the fouled
compile and the levels from the facts; `IntegrityTest` one restate per step after the facts, none on a restore, the switch;
`SheetSizingTest` a dirty service's design deposit; `IntegrityGameTests` a fouled air cooler moving less heat until cleaned.

## Traps

- Build a `CorrosionStandard` per compound list: an island's view has other indices than the world's.
- The water is the last conserved entry and has no dataset record; its arrays read 0.
- A compound with sulfur in its formula (H2S, mercaptans, COS) takes the formula's sulfur, never a sub-record.
- The digest's `wet` and `oil` are flags: a side with both reads the oil's fouling row.
- `Metallurgy` and `WallState.Stage` are saved by name later: append, never rename.
- The crude cuts' sulfur cannot meet all four assays within 20 % (the heavy crude's cuts dominate the dilbit's); the
  fit's residuals are in `tools/fouling-corrosion/README.md`.
- Facts are keyed by device identity. A layout refresh keeps the identity (`world.edit`), so the walls follow; a respec
  that rebuilds a `FluidDeviceSpec` from its fields must carry `metallurgy` as it carries `breached`.
- An island keeps the controls its last slice solved with: a failed trim's island holds the unconfigured valve until the
  `TRIM` plan carrying the registered control restores it.
- A heat side is a connection, not a node: its idle test reads its passage (`sideIdentity`), not a node's pipes.
- A failure is applied once per record (`failed`), whatever the timing of its queued edit; a wall set below through and
  through again fails again.
- `Integrity.idle` and `MachineHealth.idle` read the island's stored last result; nothing solves.
- The Repair packet handler only queues; send the answer from the reply and check the player's connection there (the player may
  have left before the engine's drain).
- The weep flag is never saved: a loaded cell learns it at the next cadence, because `Integrity.weeps` tells the host every
  weeping wall each time and the cell sends only a change.
- `FluidView.Integrity` shadows the runtime `Integrity` class inside `FluidView`; name the runtime class in full there.
- A restored island runs on its checkpoint's graph, not on the load's compile: every fouled law must round-trip through
  the checkpoint (`FluidCheckpointCodec`), as the air cooler's process films do (state format 52).
- `Placement.edit` returns null for an unchanged registration: a recompile for a compile-only input goes through
  `FluidWorldAuthority.restate`, the flowmeter's way.

**Snapshot:** `c004a4c57`, 2026-11-16.
