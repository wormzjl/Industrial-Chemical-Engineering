# Process simulator: the units (`science/flowsheet/unit`)

Up: [code guide](README.md) · the sheet and its run: [process-simulator.md](process-simulator.md) · sizing:
[simulator-sizing.md](simulator-sizing.md)

## Read this when

- A unit's balance, readings or door are wrong; a unit refuses (a note `…refused…`) or warns.
- The column on the sheet disagrees with the world's column, a utility feed's flow is wrong, or the column's water goes
  the wrong way (steam, distillate, on the axis).
- A batch still on the sheet gives the wrong cuts, or a still refuses its charge.
- A mover's power, a turbine's work, an ejector's or vacuum pump's duty, a flare's or chimney's readings look wrong.
- You add a unit model or a door row.

## What it does

Each tile of the sheet has a `UnitModel` here: its ports (the roles its calculations read), its reversible passages, and
the calculations a wired unit gives the run. Each unit closes its own mass and energy balance on the world's own laws
where the world has the device: the pump's and compressor's closures, the steam turbine's shaft power, the ejector's
correlation, the vacuum pump's curve, the flare's and chimney's laws, the prime mover's Willans line, the batch still's
law. The column is one BLOCK calculation that calls the world's V3 solver, with one calculation per heat side. A heat
side whose inlet is a sheet feed of water alone sets that feed's flow, as a plant's controller opens its valve.

## Class map

**`science/flowsheet/unit/`** ([source](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/)). Lines at the
snapshot. "pp" = package-private.

| Class | Role | Lines |
|---|---|---|
| [`ColumnUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/ColumnUnit.java) | The column as one block: its ports a pure function of its door (`FEED`, `OVERHEAD`, `DISTILLATE`, `WATER`, `BOTTOMS`, the draw, steam and pumparound ports: `drawPort`, `steamPort`, `pumparoundIn`, `pumparoundOut`), `add` (a mole fraction onto the axis; an id the axis lacks fails the column), `catalogFor(thermo, waterOnAxis)`, `SCREEN` (the liquid-supply screen ratio the sheet's column solves at: V3's default) | 194 |
| `ColumnBlockSolve` (pp) | The block's own solve: the feed flashed to the feed tray's solved pressure (`FEED_ROUNDS`), water by `ColumnWater.onAxis`, the products back through the world's flash, the condenser duty as the closure on its own streams (`ColumnSolve.closure`), the steam CHECK; keeps the last state and leaves it on its report | 406 |
| [`ColumnDoor`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/ColumnDoor.java) | The column's door as a sheet holds it (record; `of`): the calculator's Column page less the feed; nested `Draw`, `Steam`, `Pumparound`, the vacuum choice | 305 |
| `ColumnSides` (pp) | The sheet sides of the column's heat pairs: the condenser's, the kettle's and each pumparound cooler's utility side, a regulated side's feed and its rules | 148 |
| [`ExchangerUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/ExchangerUnit.java) | An exchanger or a kettle reboiler, target Outlet, Duty or Area; an outlet or a duty names a side | 184 |
| [`ExchangerRating`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/ExchangerRating.java) | A counterflow exchanger on the world's two heat curves: the surface a duty needs and the duty a surface gives | 119 |
| [`CoolerUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/CoolerUnit.java) | Target Outlet or Duty, the coolant in at 25 °C; Air or Water is a sizing type only | 88 |
| [`FiredHeaterUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/FiredHeaterUnit.java) | Target Outlet or Duty, with a duty limit (the fuel stays off the sheet); series or apart coils | 104 |
| [`MoverUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/MoverUnit.java) | A pump or a compressor on the world's closures (`FlowControl.Pump`, `Compressor`), target Outlet or Rise; the proposed driver (`driver`, `POLES`) | 98 |
| [`TurbineUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/TurbineUnit.java) | A steam turbine: steam in, exhaust out at the door's pressure (`EXHAUST_PRESSURE` by default), shaft power out of the sheet by `FlowControl.Turbine.shaftPower` | 72 |
| [`EjectorUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/EjectorUnit.java) | A steam ejector set as one tile: ports `SUCTION`, `MOTIVE`, `DISCHARGE`; `DISCHARGE_PRESSURE`, `MOTIVE_PRESSURE`, `STEAM_SHARE`; the suction's gas by `EjectorLaw.gasAsAir`; `motive` (also the column's vacuum rule) | 113 |
| [`VacuumPumpUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/VacuumPumpUnit.java) | A vacuum pump on the world's `VacuumPump` law for a type and a model, a set discharge; `pick` gives a column's pump | 133 |
| [`PrimeMoverUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/PrimeMoverUnit.java) | A combustion engine or a gas turbine on the world's law: the fuel leaves through the combustor, its heat gives the shaft power on the model's Willans line; a gas turbine's steam coil (`ExhaustCoil`) | 147 |
| [`FlareUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/FlareUnit.java) | A flare stack: the stream leaves through the flame; the door sets the tip | 82 |
| [`ChimneyUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/ChimneyUnit.java) | A chimney: the stream leaves to the atmosphere through the stack (`Chimney.draft`) | 70 |
| [`BatchStillUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/BatchStillUnit.java) | A batch still as the steady sheet sees it: one batch a cycle, each cut, the vent and the residue as cycle-averaged flows | 334 |
| [`StillBatch`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/StillBatch.java) | One batch, start to end, on the world's law (`BatchStill.step`): the recorded points, `Finish` (`SETTLED` among them) | 262 |
| [`VesselUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/VesselUnit.java) | A drum or a storage tank: a flash with one outlet per phase, heat none, temperature or duty | 80 |
| [`ValveUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/ValveUnit.java) | A control valve, isenthalpic to a set outlet or a set drop | 35 |
| [`MixerUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/MixerUnit.java) | Adds amounts and enthalpy at the lowest inlet pressure, warning if they differ | 56 |
| [`SplitterUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/SplitterUnit.java) | Every outlet at the inlet's state; each row of outlets has its own id, which names its port | 82 |
| [`FilterUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/FilterUnit.java) | The solids leave the sheet as cake, the fluid goes on | 33 |

## Main flow

The column block, the one unit with more than one calculation:

1. `ColumnUnit.calculations`: the BLOCK (`ColumnBlockSolve`), one calculation per heat side (`ColumnSides`), the
   utility feeds' calculations, the steam CHECK.
2. `ColumnBlockSolve.solve` flashes the feed at its own enthalpy to the feed tray's solved pressure (V3 has no feed
   pressure); its `run` decides water by `ColumnWater.onAxis` on the flashed feed and builds the V3 input from the door and the feed.
3. The running column's solve, `ColumnSolve.calculate(input, warm, catalog, ColumnUnit.SCREEN, ColdStart.LEARNED,
   checkpoint)` on `catalogFor(thermo, waterOnAxis)`: warm from the last seed where `ColumnSolve.warm` keeps it, else
   (or where the warm pass does not answer) the learned start with the bundled model at the strict closure.
4. The products come back through the world's flash at V3's temperature and pressure. The condenser's duty is
   `ColumnSolve.closure` on the block's own streams (the flashed feed, the steam supplies, the ejector's motive steam
   and its free water, the reboiler and the stage heats), so the block's heat balance closes; V3's own duty stays on the
   display. The duty reaches its side by a link.
5. The block leaves its seed on its report (`Report.Warm`), so the next run from this steady one starts there.

## Contracts

- **The column against the world.** The block calls the running column's own solve (`ColumnSolve.calculate`), with its
  own cold start (`LEARNED`; the world's is `CLASSICAL`) and at V3's default screen ratio, on the sheet model's view
  of the sheet's compounds with or without water (`catalogFor`: `FlowThermo.catalog()` is the list without water, the
  model appends it). On the axis the whole feed enters V3 on the list with water; a steam row with a flow fails
  `column.steam_on_axis`; a solved liquid that would settle warns `column.two_liquids` and
  `column.two_liquids_distillate`. Off the axis the feed's water is a steam feed and an aqueous liquid fails
  `column.liquid_water`. The sizing trials (`ColumnSizing`, the same call) and the warm seed's check take the same
  catalog; a warm seed on the other list is dropped, and `ColumnSolve.warm` drops a seed with a wet tray
  ([column-operation.md](column-operation.md)).
- **Heat sides.** A pumparound or the reboiler has its duty on the door, so what passes through its cold side depends on
  nothing the column solves; the condenser's duty is an answer. Each pair is checked once the sheet stands, for heat that
  cannot flow that way. An air-then-water condenser has the exchanger's pair on its water trim: the water takes the
  trim's share (`ColumnSolve.trim`), the air the rest.
- **Utility feeds.** A heat side whose inlet is a sheet feed of water alone sets that feed's flow: cooling water to carry
  the duty to 5 K under the process cold end, 10 K above its inlet at most (`HeatSides.coolingWaterOutlet`); steam to
  leave as saturated condensate; stripping steam at its row's rate through an isenthalpic inlet valve to the stage
  pressure, its supply read against the stage by a CHECK once the sheet stands (`column.steam_pressure`, an error).
- **Vacuum.** Where the top is under ambient pressure the column's `Vacuum` choice puts an ejector set on the vapour
  line (its load what the condenser cannot condense; its Motive port takes the ejector tile's rule; the steam condenses
  with the overhead and joins the condenser's duty) or a vacuum pump on the receiver's vapour outlet, taking only the
  vent, its model picked by `VacuumPumpUnit.pick`. V3 solves at its own pressures: the ejector moves no product's
  pressure.
- **Movers** (`MoverUnit`): a pump's shaft power is the ideal work over the best efficiency of a centrifugal pump rated
  at the tile's duty (`CentrifugalPump.rated`, Corripio); a compressor's is the world's closure's, the polytropic work;
  all of it heats the discharge. Readings `power`, `hydraulic` (a pump), `efficiency`, `head`, and the proposed driver,
  a `POLES`-pole IE3 motor rated by `Motor.ratingFor` (readings `motor`, `motor_efficiency`, `electric`). A pump warns
  `pump.bubble_point` where its liquid boils 0.1 % under its pressure; one dense fluid has no bubble point, so never.
- **Turbine** (`TurbineUnit`): η is `SteamTurbine.typicalEfficiency` of a turbine rated at its own power; an exhaust at or
  above the inlet is refused.
- **Ejector** (`EjectorUnit`): the world's `SteamEjector` uses the sheet's correlation, so a sheet-sized set runs its
  sheet duty; the sheet and the world count the suction's vapour as air by one `EjectorLaw.gasAsAir`.
- **Vacuum pump** (`VacuumPumpUnit`): the world's law for a type and a model (0: the smallest that holds the load with
  the spare), a set discharge; readings `power`, `capacity`, `load`, `model`, `rise` and the motor; fails at blank-off.
- **Prime mover** (`PrimeMoverUnit`): warns at the wrong phase, over the rated fuel and a low supply; a gas turbine's
  free pair is its steam coil, read at the water's inlet and its bubble point.
- **Flare, chimney.** `FlareUnit` reports the heat released, the heating value, the flame temperature and the most the
  tip passes (`Flare.gas`), and warns at FLAME OUT, over capacity and solids. `ChimneyUnit`'s door sets the form, exit
  diameter, height and nearby structure's height; it reads exit velocity, draft, friction, entry and exit losses, the
  inlet pressure needed, solids and opacity, and warns NO DRAFT, ABOVE RATING, HOT STACK and a stack under GEP height.
- **Batch still** (`BatchStillUnit`, `StillBatch`): one batch a cycle on the world's law (`BatchStill`, the still's
  column on the sheet model's `NetworkStageThermo`). The charge is on the column's basis, the sheet's axis with water
  last. A charge whose liquid stays one boils; one that settles into two liquids at the pot's start is refused
  (`still.water`, `StillColumn.twoLiquids`); a pot that settles during the batch ends it (`StillBatch.Finish.SETTLED`,
  `still.settles`), the rest residue. A recorded point's composition is what reaches the cut: the condenser's liquid,
  none while a partial condenser vents its gas alone. One fill threshold, the law's `BatchStill.FILL`: the sizing's
  yellow and the run's `still.full` warning both read it; a charge above the pot's volume fails `still.overfull`. The
  door's Batch tab draws the run's `Report.Series`.
- **Vessels** (`VesselUnit`): a drum stands at its inlet's pressure; a storage liquid a roof stores stands at ambient and
  its dissolved gas leaves by the Gas outlet, any other at the higher of ambient and its bubble pressure, never above
  the inlet.
- **Refusals.** Each unit refuses what the world's device refuses, as a `FlowsheetFailure` whose note key the solver
  shows.

## Laws and constants

- Mixer: amounts and enthalpy add; the outlet at the lowest inlet pressure.
- Valve: isenthalpic. Splitter: the inlet's state on every outlet.
- Cooler coolant in at 25 °C; cooling water out at min(T_in + 10 K, process cold end − 5 K).
- `TurbineUnit.EXHAUST_PRESSURE`, `EjectorUnit.DISCHARGE_PRESSURE` (`SteamEjector.RATED_DISCHARGE`),
  `EjectorUnit.MOTIVE_PRESSURE` (`SteamEjector.RATED_MOTIVE`), `MoverUnit.POLES`.

## Formats

A unit's settings are the file's `Params` ([process-simulator.md](process-simulator.md)); a run's reports are results
(state). Numbers: [FORMATS.md](../FORMATS.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| A unit's balance or door | `unit/*`, `UnitTypes` | `FlowsheetUnitsTest` (every unit closes mass and energy), `FlowsheetColumnDoorTest`, `EjectorTest` |
| The column on the sheet against the world's solver | `ColumnUnit`, `ColumnBlockSolve`, `ColumnDoor` | `FlowsheetColumnTest`, `ColumnEjectorTest` (both `holdGates`) |
| A utility feed's flow | `FlowsheetCompiler`, `WiredUnit.Utilities`, `ColumnSides` | `UtilityRegulationTest` (`holdGates`) |
| The column with water | `ColumnBlockSolve`, `ColumnWater` | `FlowsheetAqueousColumnTest` (`theEthanolRectifierExampleRunsOnTheNetworkPackage`, on the bundled catalog) |
| The batch still | `BatchStillUnit`, `StillBatch` | `BatchStillUnitTest` (`aNaphthaWithItsDissolvedWaterIsOneLiquidAndBoils`) |
| The release pressures a storage vessel reads | `VesselUnit`, `FlowThermo` | `ReleasePressureTest` |

## Traps

- `ColumnBlockSolve` keeps the last state, so each pass starts from it; a Stop drops it (`FlowsheetRun.cold`), and the
  next run's column takes the learned start.
- `Report.Start.LAST` is reported for any warm seed the block holds, also a wet one that `ColumnSolve.warm` then drops.
- A heat side is a calculation of its own: heating the feed of the same column from a pumparound is no loop.
- `CoolerUnit`'s Air or Water is a sizing type only; the run's law is the same for both.
- `TopologyCompiler.Kind.TURBINE` maps to the turbine tile; `MOTOR`, `GENERATOR` and `GEARBOX` stand on it as
  DRAWING_ONLY and size nothing (`FlowsheetCatalog.DRIVER`). The diverter valve is the splitter.

**Snapshot:** `9195b5161`, 2026-11-16.
