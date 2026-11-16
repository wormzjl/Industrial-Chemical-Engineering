# Process simulator: sizing, lamps, Auto, Size all, Send to block and Import

Up: [code guide](README.md) · the sheet and its run: [process-simulator.md](process-simulator.md) · the units:
[simulator-units.md](simulator-units.md) · the Sizing page: [simulator-screen.md](simulator-screen.md)

## Read this when

- A Sizing row or lamp is wrong, or an Auto size or Size all picks the wrong equipment.
- A pressure path or a line size disagrees with a hand calculation.
- A pump's NPSH, a tank's roof, a compressor's discharge temperature or an ejector's capacity reads wrong.
- Send to block or the constructor's Import misbehaves, or a sent design will not land.
- A new equipment kind must name its size class (`SizingCoverage`).

## What it does

Sizing turns a steady run into real equipment. `SheetSizing.rate` rates every unit and line of the sheet against the
`Limit` rows (red Must, yellow Should, green), on the same view of the sheet's compounds the run used. A unit's sizer
gives one `UnitSize` per buildable size, a `rate` function and an `auto` search; Auto (`Selection`) proposes the smallest
size that rates well with the owner's 20 % spare, and the player may keep a Fixed size instead. `Proposal.sizeAll`
applies every Auto size to a copy of the sheet and re-runs once, warm, where a size moves the run. Pressure paths run
from each set pressure through every line and unit side to its taker. `SheetSizing.designs` is the list Send to block
hands to the block, from which the equipment constructor imports.

## Class map

**`science/flowsheet/sizing/`** ([source](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/)). Lines at
the snapshot. "pp" = package-private.

| Class | Role | Lines |
|---|---|---|
| [`SheetSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/SheetSizing.java) | The whole sheet sized and rated, in the physics' order: `rate` (cheap, no trial columns), `pass`, `designs`, the entries and results, `PumparoundMember` | 666 |
| [`Proposal`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/Proposal.java) | Size all: `sizeAll` (every Auto size on a copy, its column doors and member counts with it, one warm re-run where a size moves the run), `applied`, `pruned` | 145 |
| `BlockSizing` (pp) | One sizing's column and its block: the column at its diameter, then its members and their lines; the first trial warm from the run's `Report.Warm` | 240 |
| `PathWalk` (pp), [`PressurePath`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/PressurePath.java) | The pressure paths from each stream that starts one; one path: available, lost, spare (bar), `spare()` | 195, 75 |
| [`SizedPassages`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/SizedPassages.java) | A sized unit's passages: the world's own law of each at flow times a stream's rate, read by the sizers and the run alike | 227 |
| [`Limit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/Limit.java) | Every rated row with its bound, unit and source (enum; `Kind` MUST, SHOULD, RULE; `Rank` CLOSEST, QUIET, PAID); the name is the stable id; a value changes here and nowhere else | 388 |
| [`Bound`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/Bound.java), [`Lamp`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/Lamp.java) | A row's ceiling, floor or band (one number, `Step` steps along an axis, or given); a row's state, NONE < GREEN < YELLOW < RED | 121, 13 |
| [`Rating`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/Rating.java), [`Reading`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/Reading.java) | What one size does against its limits; one limit read on one size | 87, 41 |
| [`Selection`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/Selection.java) | Auto: a search over sizes calling the rate function a Fixed size is rated by; a failed trial is a rating, not an exception | 66 |
| [`UnitSize`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/UnitSize.java), [`UnitType`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/UnitType.java) | A unit's size, Auto's or the player's (`shape`: what the constructor can build); what a tile with one function is built as | 56, 33 |
| [`SizingFacts`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/SizingFacts.java), [`DesignBasis`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/DesignBasis.java) | What the player keeps of sizing (facts of the sheet): the design basis, each item's mode, type and size; the basis's three entries (`DEFAULT`: 1.10 design flow, 50 m line length, 60 K pumparound fall) | 166, 23 |
| [`LineSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/LineSizing.java), [`Service`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/Service.java) | A line's bore and runs (`rate`, `auto`), its drop on the network's viscosity; what a line carries, which picks its rows (the stream gives it, the player may choose) | 188, 108 |
| [`ColumnSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/ColumnSizing.java) | A column's diameter in the world's half metres, each rated by the real solver (`ColumnSolve.calculate`, the sheet's `ColdStart.LEARNED`) | 216 |
| [`PumpSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/PumpSizing.java) | The smallest discharge whose rated flow fits the window; `Duty` (flow, head, shaft power, NPSH3, proposed motor), `Load` (`npsh`, `edge`, `boils`, `reducedDensity`) | 181 |
| [`CompressorSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/CompressorSizing.java) | The type by its inlet flow, then the smallest frame; stages here alone; the discharge one stage's polytropic estimate on the world's exponent | 135 |
| [`TurbineSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/TurbineSizing.java) | A steam turbine's design point, rated power, inlet steam class and exhaust | 103 |
| [`ExchangerSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/ExchangerSizing.java) | An exchanger or a kettle on `ExchangerRating`, over shells, shell sizes, tubes and the `ExchangerType` catalog (a kettle Standard only) | 216 |
| [`AirCoolerSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/AirCoolerSizing.java), [`CoolerSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/CoolerSizing.java) | An air cooler, the least face within its drop, `fans`; coolers on air and tower water | 161, 96 |
| [`HeaterSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/HeaterSizing.java) | A fired heater: a vertical while the largest vertical holds the design duty with the spare, else a cabin | 98 |
| [`DrumSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/DrumSizing.java), [`TankSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/TankSizing.java) | A drum (lying with a boot for a second liquid); a storage tile (floating roof, fixed roof or sphere by the storage pressure) | 151, 126 |
| [`ValveSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/ValveSizing.java) | A control valve on its inlet line's bore | 47 |
| [`EjectorSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/EjectorSizing.java), [`VacuumPumpSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/VacuumPumpSizing.java) | The suction bore whose world set holds the spared load; the type, then the smallest model whose capacity at the suction holds it | 99, 81 |
| [`FlareSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/FlareSizing.java), [`ChimneySizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/ChimneySizing.java) | The tip that passes the design flow; the exit diameter and the height on the constructor's grid | 87, 92 |
| [`PrimeMoverSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/PrimeMoverSizing.java), [`StillSizing`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/sizing/StillSizing.java) | The smallest engine or turbine model whose rated fuel holds the fuel heat; a batch still's pot from the constructor's list, with its heater, worm and column | 78, 138 |

Outside the package: [`science/equipment/SentDesign`](../../../src/main/java/com/wormzjl/icheme/science/equipment/SentDesign.java)
(one sent unit design: tag, member, count, shape, column; 38 lines) and
[`world/level/block/entity/SentDesignTags`](../../../src/main/java/com/wormzjl/icheme/world/level/block/entity/SentDesignTags.java)
(its NBT, `wrap` and `unwrap` the packets' list; 107 lines).

## Main flow

1. **Rate** (`SheetSizing.rate(sheet, run, thermo)`, on the runner's thread after a steady run, or quietly 10 ticks after
   the last edit): the units that read no line first (a column with its members, `BlockSizing`), then lines
   (`LineSizing`), pressure paths (`PathWalk`), and pump NPSH and valve bores last; each item rated by its sizer's `rate`
   at its Fixed or Auto size; lamps from the rows.
2. **Auto** (`Selection`): the sizer's sizes in order, those holding the need with `Limit.AUTO_SPARE` first, the smallest
   that rates well.
3. **Size all** (`Proposal.sizeAll`): every Auto size applied to a copy of the sheet; where a size moves the run (a
   column's diameter, sized drops) one warm re-run; a run that is not steady sizes nothing; the result is one undoable
   edit.
4. **Send to block**: `SheetSizing.designs` gives one `SentDesign` per sized unit (valves left out, at most 64), sent as
   `simulator_send_designs`; the server takes the list whole from the holder and replaces the earlier one.
5. **Import**: the constructor asks the nearest simulator within `EquipmentNetwork.IMPORT_REACH`
   (`EquipmentConstructorBlockEntity.simulator`) for its list: the saved column design first, then the sent designs; the
   player picks one and the constructor lands it ([equipment-structure.md](equipment-structure.md)).

## Contracts

- **Lamps.** MUST is red when broken, SHOULD yellow, RULE picks a type or a service and lights nothing. A unit's lamp is
  its worst row; a sheet's is its worst unit. The lamps read the bare need, so a sized unit is green with room. Lamps
  are state, never saved.
- **The spare.** `Limit.AUTO_SPARE` (RULE, at least 20 %, the owner's "always have enough excess") is what Auto adds to
  exchanger, kettle and air-cooler surface, heater duty, type and cabin count, and compressor displacement.
  `PATH_EXCESS` and `EJECTOR_MOTIVE_SPARE` (SHOULD, 20 %) are spares on a path and on motive steam.
- **One rule.** Auto calls the same `rate` a Fixed size calls, so an Auto size is never rated by another rule.
- **Paths.** A `PressurePath` runs from a set pressure through every step to its taker; `spare()` feeds the heater's and
  the valve's sizing; `PATH_SPARE` (MUST, at least 0 bar) is red where a supply cannot reach its taker, a steam row's
  tray included.
- **Columns.** `ColumnSizing` tries half-metre diameters with real solves; a trial that does not solve is red on
  `COLUMN_SOLVES`. A column with a vacuum pump has a `vacuum_pump` member in `BlockSizing`.
- **Pumps** (`PumpSizing`): NPSH available over the liquid's p_v, or over zero pressure where it has none;
  `PUMP_NPSH_WORLD` against the greater of the clean zone's edge and 0.85 NPSH3 (`ReleasePressure.limit` as heads over
  p_v; no row where neither exists), `PUMP_NPSH` against NPSH3 plus its margin. A drum liquid saturated with gas has the
  head over its vapour pressure. On one dense suction (`State.dense()`) `PUMP_DENSITY` (SHOULD) reads its reduced density
  against 0.86, the lightest suction pumps run on carbon dioxide; no other suction reads it.
- **Release pressures** (`FlowThermo`, the world's rule on its own trial, a TP flash at the stream's temperature, from
  100 Pa to a ratio of 1.0005, no cache): `vapourPressure` (p_v, the compounds above their critical temperature left
  out), `zoneEdge` (E: at most the clean share of vapour), `storagePressure` (as much vapour as liquid by volume) and
  `roofStores` (storage pressure at most `ROOF_STORAGE`, which `Limit.ROOF_BUBBLE` reads). `TankSizing.Load` carries
  `storage` beside `bubble`: the type and both roof rows read the storage pressure, a sphere's design the bubble
  pressure; where the search finds none the tile reads its bubble pressure.
- **Compressors** (`CompressorSizing`): the world's law is one isothermal step, so stages live here alone; the discharge
  is one stage's polytropic estimate on the world's exponent (`CentrifugalCompressor.sigma` at its default efficiency,
  the heat capacity ratio as the network reads it), cooled back to the suction between stages. `COMPRESSOR_LIQUID` is a
  SHOULD row: a wet suction is compressed. A dense suction's liquid share is the world's, `100 w` on its liquid-like
  weight inside reduced densities 0.5 to 1.5. Its discharge exponent blends the ideal-gas one to the solved streams',
  `ln(T_out/T_in)/ln(ratio)`, by `w` (`CompressorSizing.Load.blended`): the ideal one to the bit at `w = 0`.
- **Dense suctions**: one dense fluid has no bubble pressure, and at its own temperature it never flashes, so the
  sheet's isothermal trial gives it no edge; into a pump it is `SUCTION_COLD` (`Service.of`). The world's isenthalpic
  E can still exist (carbon dioxide at 320 K and 15 MPa: 6.52 MPa). Its gas or liquid class is its slot's, the weight's
  midpoint.
- **Turbines** (`TurbineSizing`): rated power from `MachineDuty.POWERS` with `Motor.SPARE`, the lowest steam class whose
  header holds the inlet, the nearest exhaust of `MachineDuty.TURBINE_EXHAUSTS` (below the LP header); rows `TURBINE_SPARE`, `TURBINE_INLET`,
  `TURBINE_EXHAUST` (a tile exhausting at or above the LP header is red: no turbine is built for back pressure).
- **Ejectors** (`EjectorSizing`): a bore rated on the world's set at the sheet's motive pressure (`Load.motivePressure`;
  `Size.capacity` = `SteamEjector.capacity` with all its steam); Auto takes the smallest bore whose capacity holds the
  spared load; `VACUUM_SPEED` is smooth below its edge (`Bound.Step` with an exponent), its reading v(P_s) times load
  over capacity.
- **Exchangers** (`ExchangerSizing`): the candidates span the `ExchangerType` catalog (Standard and the crossflow
  types; a kettle has no crossflow shell). A crossflow candidate's surface comes from its own routing; an unreachable
  duty is a failed rating. The type travels with the size into sent designs, Import and column member choices.
- **Air coolers** (`AirCoolerSizing.fans`): the fan speed (0..1) that holds the load on the chosen face; power goes
  with the cube of the speed; `Sized.fanPower()` is the sheet's total.
- **Stills** (`StillSizing`): the pot from the constructor's list; the heater at 25 kW/m², the worm at the full boil-up
  plus 20 % at 40 K, the column at an F-factor of 1.5 Pa^0.5; one fill threshold, `BatchStill.FILL`.
- **Chimneys** (`ChimneySizing`): the smallest diameter passing `AUTO_SPARE` over the design flow at the form's velocity,
  within 7.5 to 30 m/s and the 100 m/s cap; the GEP height (2.5 times the structure's, at least 10 m) within the form's
  range. **Flares**: the tip that passes the design flow with the spare. **Prime movers** and **vacuum pumps**: the
  smallest model that holds, spare first.
- **Saved names.** `SizingFactsFile` saves each kind's size by name (`STEAM_TURBINE`, `CHIMNEY`, `VACUUM_PUMP`,
  `PRIME_MOVER` among them): a new kind is a new name, no format change. Member keys are `Members`' saved names.

## Laws and constants

Every limit's value is a `Limit` constant (its `Bound`, its unit, its source text); `DesignBasis.DEFAULT` holds the
design flow factor, the line length and the pumparound fall. The physics of each law is the world's
([fluid-laws.md](fluid-laws.md), [rotating-machinery.md](rotating-machinery.md),
[heat-exchangers.md](heat-exchangers.md)); sizing reads it through `SizedPassages` and the units' own models.

## Formats

Sizing facts are facts of the sheet (`SizingFactsFile`, in the flowsheet file); sent designs are facts of the block
(`SentDesignTags`; corpus `compat/simulator/designs-<n>.snbt`). Numbers: [FORMATS.md](../FORMATS.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| A row or lamp | `Limit`, the unit's sizer | `LimitTest`, `UnitSizingTest`, `CoolerSizingTest`, `TurbineSizingTest`, `SheetSizingTest` (the compressor's exponent, the ejector's motive pressure) |
| Crossflow candidates | `ExchangerSizing` | `CrossflowSizingTest` |
| Lines and paths | `LineSizing`, `Service`, `PressurePath`, `PathWalk` | `LineSizingTest`, `ServiceTest` |
| The release pressures | `FlowThermo` | `ReleasePressureTest` (the sheet's p_v and E within 1 % of the world's PH readings) |
| The sample sheet | the whole stage | `CrudeUnitSampleTest` (`holdGates`: nested `SimulatorRunTest`, `LineSizingSampleTest`, `UnitSizingSampleTest`, `ColumnSizingSampleTest`, `SizeAllSampleTest`; filter `CrudeUnitSampleTest$*`), `SmallCrudeUnitTest` (`holdGates`) |
| Sent designs and Import | `SheetSizing.designs`, `SentDesign`, `SentDesignTags`, `EquipmentNetwork` | `SentDesignTagsTest` (the corpus), `ProcessSimulatorGameTests.aConstructorImportsTheNearestSimulatorsDesign` |
| A new kind's size class | `SizingCoverage` (`src/test`) | `SizingCoverageTest` |

## Traps

- `SheetSizing.rate` runs no trial columns; Size all and the column's own rows do.
- The Sizing tab re-rates quietly: a lamp can change after an edit without a Run.
- The type of an exchanger (Standard or crossflow) is a sizing choice, not another tile.
- A sent list replaces the earlier one whole; the constructor imports from the nearest simulator only.

**Snapshot:** `d6f721b94`, 2026-11-15.
