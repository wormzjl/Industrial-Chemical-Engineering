# Equipment: the kinds' designs, columns, storage and machines

Up: [code guide](README.md). Shapes, cells and collision: [equipment-geometry.md](equipment-geometry.md). Placement, saves, refresh and the constructor's server side: [equipment-structure.md](equipment-structure.md). The machines' laws: [rotating-machinery.md](rotating-machinery.md); heat sides: [heat-exchangers.md](heat-exchangers.md); the plant round a column: [machine-assemblies.md](machine-assemblies.md); a new kind joins the process simulator: [process-simulator.md](process-simulator.md) and AGENTS.md "New equipment". Before you build, change or review a model, read the `models-and-sfx` skill ([SKILL.md](../../../.claude/skills/models-and-sfx/SKILL.md)).

## Read this when

- You add a unit kind, change a kind's sizes, rows, datasheet lines, moving parts, drawing or layout revision.
- A pump, compressor, steam turbine, motor, generator or gearbox has the wrong size for its design point (`MachineDuty`), its shaft end does not meet a shaft block, or its terminal cell is misplaced.
- You change a column's trays, component design, member counts, operating point, where a side nozzle stands or which side it faces, its platforms and ladders.
- You change a floating-roof tank's or a sphere's proportions, ports or access, a converter's tanks, a still's options, a flare's tip or a chimney.
- You change a datasheet number or a sizing rating (`UnitRatings`), or what Import lands (`SentDesign`).

## What it does

Every kind but the drum and the column has one design, a `UnitDesign`, found by `UnitDesign.of(kind)`. A design states its constant sizes, constructor rows and moving parts in one `Sizes` record, its declared layout revision with them; it overrides only what it computes, and its `build` draws the unit with the shared kit, `ProcessUnit`. A design uses the kit, never another design's file. Machines carry a design point (`MachineDuty`), a fact of their shape. A column is laid out by its trays (`ColumnTrays`) and dressed by `ColumnStructure` (cladding, platforms, ladders, davit, process nozzles); its component design (`ColumnComponents`) says what its plant needs, and `ColumnDesign` is what a process simulator saves or sends. `UnitRatings` holds the datasheet and sizing ratings the kit, the designs, `HeatSides`, `UnitDesign` and the sizing classes share.

## Class map

Paths under [`src/main/java/com/wormzjl/icheme/science/equipment/`](../../../src/main/java/com/wormzjl/icheme/science/equipment/). Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| **The design framework** | | |
| `UnitDesign` | One kind's sizes, rows, datasheet `Line`s, `moves`, `outlets`, `build`, `held`, `layoutRevision`; the `Kind` to design map (`of`); `soundGain`, `soundPitch`, `driver`; a machine's design point (`hasDuty`, `duty`, `size`, `length`, `step`, `check`) and ratings (`ratedRpm`, `designSpeed`, `ratedPower`, `pumpRating`, `compressorRating`, `vacuumPump`, `primeMoverModel`, `steamCoil`, `coilSurface`, `coilPassage`, `displacement`) | 525 |
| `Sizes` | A design's constant sizes, rows, moving parts and `layoutRevision`; `COMPUTED` where the design overrides | 45 |
| `ProcessUnit` | The shared drawing kit (solids, nozzles, pipes, motors, decks, ladders, stairs, wound stairs, stair towers, tables, the machines' pieces, `shaftEnds`, `terminal`, `serviceCase`, `loadShaft`, `damper`, `openSkirt`, the heaters' parts); `minSupport`, `defaultSupport`; `Bank`, `Fans`, `Motion`, `MOTION_PERIOD`, `Floating`; `of` caches built units | 3098 |
| `UnitRatings` | `design/`: the datasheet and sizing ratings: `tubeSurface`, `kettleShell`, `bundleFace`, `fans`, `fanDiameter`, `coverage`, `pumpFlow`, `pumpPower`, `pumpDischarge`, `stroke`, `compressorSpeed`, `compressorPower`, `displacement`, `heaterDuty` | 135 |
| `MachineDuty` | A rotating machine's design point (flow, head, polytropic head, power, poles, `Drive`, `Steam`, exhaust); `NONE`; `POWERS`, `MOTOR_POWERS`, `EXHAUSTS`; `speed` | 184 |
| `ConstructorDefaults` | Starting sizes for a new constructor choice | 36 |
| `SentDesign` | A unit's design as Send to block stores it: `tag`, `member`, `count`, `shape`, `column`; `MAX`, `MAX_TAG`, `MAX_COUNT` | 38 |
| `ExchangerType` | The exchanger configuration catalog (Standard, Low-dP crossflow) shared by construction, sizing and the heat sides | 71 |
| **Columns** | | |
| `ColumnTrays` | Trays at the solver's spacing, feed, draws, steam, pumparounds; `components`, `turns` (`Turn`, `SIDE_ROLES`, `MAX_TURNS`), `operating`; `bottomSpace`, `tangentLength`, `trayHeight`; `of(V3ColumnInput)`; `encode`/`decode`, `encodeTurns`/`decodeTurns` | 249 |
| `ColumnComponents` | A column's component design: `Condenser` (AIR_COOLER, EXCHANGER, AIR_THEN_WATER), reflux `Pump`, pumparound pumps, `Vacuum`, `ejector`, `kettle`, `Units` (member counts, `MAX_UNITS`), `Choices`, `resolve`, `named` | 290 |
| `ColumnDesign` | A design a simulator's column saved or sent: trays with components, operating point, diameter (`builtDiameter`); `layout`, `of`, `misfit`, `SideNozzle`, `sides` | 126 |
| `ColumnOperatingPoint` | The input a design was solved at, a fact of the shape: `of`, `fits`, `input`; `MAX_ID` | 123 |
| `ColumnStructure` | Cladding, platforms at manways, caged ladders, davit; the process nozzles in their stages' bands on their home sides, turning (`Rows`, `order`), `unplaced`, `sides`, `reboiled`; `plan` (`Rooms`); `davitTop`; `LAYOUTS` | 800 |
| `Access` | Decks, rails, caged ladders and rests (`climbs`, `MAX_CLIMB`, `WALK_THROUGH`), `WoundStair`, player clearances (`PLAYER`, `MIN_DECK`) | 724 |
| `Manway` | A manway's neck, flange, cover and davit | 92 |
| **Storage and converters** | | |
| `TankDesign` | Floating-roof tank on a ringwall: fill height = shell less `FREEBOARD`, wind girder, walkway, stair (`STAIR_SIZE`), the gauge's place, fixed nozzles; `Plan` | 431 |
| `SphereDesign` | Storage sphere on legs: wound stair (`STAIR_SIZE`), fixed VAPOUR and LIQUID ports | 310 |
| `TankPlacement` | The fixed placement frames of the supported tank grid | 75 |
| `ConverterDesign` | Fluid and gas converters: two tanks, the transfer line and cabinet; `rated` (`TURNOVER`), `serviceHousing`, `reliefFoot`, `blanketFoot`, `gaugePlace` | 309 |
| `TowerBank`, `CoolingTowerDesign` | A tower's cell bank and both cooling towers ([cooling-towers.md](cooling-towers.md)) | 17, 210 |
| **Heat units** | | |
| `ExchangerDesign`, `KettleDesign`, `AirCoolerDesign` | TEMA AES exchanger, kettle reboiler, air cooler bank of bays (1.5 to 6 m by 2 to 12 m; two bundles above 3 m) | 91, 94, 281 |
| `VerticalHeaterDesign`, `CabinHeaterDesign` | API 560 fired heaters | 203, 258 |
| **Machines** | | |
| `PumpDesign`, `CompressorDesign`, `CentrifugalCompressorDesign` | Casings by design point: an OH2 or BB2 pump, an API 618 frame, an API 617 barrel | 319, 296, 228 |
| `SteamTurbineDesign`, `SteamTurbineTrain` | The one steam turbine kind (`front`, `sections`, `drops`, `DEFAULT_STEAM`, `CONDENSING_EXHAUST`, `DRAFT_STEAM`, `DRAFT_EXHAUST`) and its drawing (stepped barrels, `hood`, `pedestal`, the LP casing) | 404, 578 |
| `MotorDesign`, `GearboxDesign` | Motor and generator (terminal box = FE port); a parallel-shaft gearbox | 223, 161 |
| `VacuumPumpDesign`, `EjectorDesign` | Liquid ring and dry screw vacuum pumps (models from `VacuumPump`); a steam ejector | 286, 119 |
| `PrimeMoverDesign` | A combustion engine or a gas turbine package (models from `PrimeMover`), its own exhaust stack and coil | 563 |
| **Other units** | | |
| `BatchStillDesign`, `BatchStillOptions` | A batch still (`StillPlan`, `FUEL_BORE`) and its options, a fact of its shape | 475, 107 |
| `FlareStackDesign`, `ChimneyDesign` | An elevated flare by its tip; a steel stack or a brick chimney | 81, 159 |
| `CycloneDesign`, `AdsorberDesign`, `StirredReactorDesign` | Cyclones, adsorber beds, a stirred tank reactor | 496, 492, 469 |
| `TrickleBedReactorDesign`, `EbullatedBedReactorDesign`, `HydroprocessingParts` | The hydroprocessing reactors and their shared parts | 298, 456, 194 |
| `FccConverterDesign`, `FccConverterLayout`, `FccConverterBuilder` | The FCC converter: its numbers (`fits`) and its drawing | 74, 273, 620 |
| `DelayedCokerDesign`, `DelayedCokerPlan`, `DelayedCokerDrums`, `DelayedCokerDerrick`, `DelayedCokerStructure` | The delayed coker's sizes and layout, drums, derricks, frame | 60, 181, 296, 194, 111 |
| `GrateBoilerDesign`, `FixedBedGasifierDesign` | A grate boiler; a Lurgi fixed-bed gasifier | 814, 695 |
| `FluidizedBedBoilerDesign`, `FluidizedBedBoilerLayout`, `FluidizedBedGasifierDesign`, `FluidizedBedGasifierLayout`, `FluidizedBedParts` | The fluidized beds and their shared parts | 102, 760, 94, 610, 167 |

## Main flow

1. The constructor's rows come from the design (`UnitDesign` sizes and rows; `ConstructorDefaults` for a new choice).
2. `EquipmentShape` validates the sizes against the design's grid; a machine's sizes follow its design point (`UnitDesign.size`, `length`, `duty`).
3. `ProcessUnit.of(shape)` calls the design's `build` on the kit for the shape facing north and caches it; `ShapeRasterizer` lays it out ([equipment-geometry.md](equipment-geometry.md)).
4. A column: `ColumnTrays` gives its height and tray heights, `ColumnStructure.plan` its nozzles, decks and ladders; a design from the simulator arrives as `ColumnDesign` (checked by `of`, refused at Build by `misfit`).
5. The datasheet reads the design's `datasheet` lines and `UnitRatings`; the engine reads a machine's ratings through `UnitDesign` and `MachineRatings` ([equipment-structure.md](equipment-structure.md)).

## Contracts

- **One design per kind, the kit shared.** A kit piece two designs need goes into `ProcessUnit`, not into another design's file (`ProcessUnitTest.designsShareOnlyTheKit` scans the design files).
- **`Sizes` has no defaults.** Every design states every field; a value that depends on the sizes or on the design's own form stays an override, and its field holds `Sizes.COMPUTED`, `null` or `false`, never read. Its arguments are literals and compile-time constants (and `MachineDuty.MOTOR_POWERS`), since it is built while `UnitDesign`'s registry fills.
- **Layout revision.** `Sizes.layoutRevision` is 1 or more; a change of the design's box, cells or nozzles at any size raises it (a drum's and a column's are `ShapeRasterizer` constants). A placed structure whose cells another revision placed is rebuilt on load ([equipment-structure.md](equipment-structure.md)).
- **The built unit has no heading.** `ProcessUnit.of` builds for `shape.withFront(NORTH)`; no design reads `front`, so the four headings share one entry.
- **Machines.** Shaft ends join shaft blocks; a terminal cell carries the FE port. A pump's and a centrifugal compressor's law come from their design point; a gearbox has two ends.
- **Columns.** Each side nozzle stands in its stage's band (an inlet over its tray, an outlet under it) on its home side (feed and bottoms the front; reflux and pumparounds the right; vapour return, draws and steam the left), then turns to the 90° sides, then the opposite, before it changes row; a turned nozzle tries only its side. A column keeps its pipe blocks a free block apart (`Nozzle.crowds`); other kinds' nozzle pipes may touch. A column with components drops the vapour return unless the design has a kettle (`reboiled`); a column without components is an old column and has no assembly.
- **Component design**: the receiver (a lying drum with a boot), the reflux line and the control valves are always required and carry no field; the ejector is required below ambient top pressure, the kettle with a reboiler duty above 0; member counts run 1 to `ColumnComponents.MAX_UNITS` on each heat-transfer step; the pumps and the receiver take no count.
- **A sent design's count is not built**: the constructor builds one; the player builds `count` in parallel, and the assembly checks that number.
- **Storage**: a tank registers open (vents, overflows at its fill height); a sphere is a closed reservoir with a drum's physics.
- **Climbs** rest at most `Access.MAX_CLIMB` apart, columns and units alike (`Access.climbs` splits a longer one evenly).

## Laws and constants

- Column height: top space, the trays at the solver's spacing, and the sump (`ColumnTrays.bottomSpace`: the level stack and the vapour return to the next whole metre, or D/2 where more), in whole metres (`tangentLength`).
- Ratings (`UnitRatings`): tube surface 75 D² L; a pump's flow at 3 m/s in the discharge and its power at `PUMP_HEAD` and `PUMP_EFFICIENCY` or its duty; a frame's speed at `PISTON_SPEED`; a fired heater's duty from `RADIANT_FLUX` and `RADIANT_SHARE`.
- Sound (`UnitDesign.soundGain`): a running loop's gain over the size range times the speed share to `SPEED_EXPONENT`; `soundPitch` from the rpm over the loop's (`PUMP_LOOP_RPM`, `MOTOR_LOOP_RPM`).
- Air cooler bays (`AirCoolerDesign`, `UnitRatings`): face = `finWidth` (bay - 0.22 m, - 0.12 m more with two bundles above `ONE_BUNDLE` 3 m) × (length - 1 - header); 1 to 3 fans (`fans`); a width's lengths run from `minLength` to `maxLength`, every one with fans over `FAN_COVERAGE` (1.5 m to 6 m long, 2 m to 9, 2.5 m to 10, 3 m and wider to `MAX_BAY_LENGTH` 12); least support max(3, ceil(0.6 D + 0.2 + 0.4 D + 0.35 min(D, 0.9))), default max(4, least); a bay longer than `POST_SPAN` 6 m has a post line at its middle; a two-bundle bay's posts and beams are `WIDE_POST` 0.3 m; fans above `SPEED_FAN` 2.35 m turn at its tip speed (`fanSpeed`).
- Motion (`ProcessUnit`): `FAN_SPEED`, `PUMP_SPEED`, `CRANK_SPEED`; `MOTION_PERIOD` holds whole turns of every speed. Kinds whose `moves` is true have parts the client turns ([client-rendering.md](client-rendering.md)).
- `ConverterDesign.rated` is one tank's volume in `TURNOVER` seconds.
- Machine design point lists: `MachineDuty.POWERS`, `MOTOR_POWERS` (IEC), `EXHAUSTS`; `speed` is 50 Hz synchronous.
- Machine size limits (kW in the code): `MachineDuty.MAX_GENERATOR` 400 MW (`GENERATOR_POWERS`); `MachineDuty.MAX_TURBINE` 300 MW (`TURBINE_POWERS`, from 55 kW), one casing to `SteamTurbineDesign.SINGLE_CASING` 45 MW and tandem casings above. A turbine expands straight through its sections (`SteamTurbineDesign.front`, `sections`; batch 2026-11-15-equipment-visual-design): HP or VHP steam through HP, MP and LP, MP through MP and LP, LP (`MachineDuty.Steam.LP`, 4.5 bar) through LP, to an exhaust of `MachineDuty.TURBINE_EXHAUSTS` (10 to 350 kPa, below the LP header); a saved back pressure keeps loading with fewer sections by the same rule. Up to 45 MW the sections are barrels of one casing (`SteamTurbineTrain.barrelStart`, each as long as its share of `drops`); above it, separate casings on one shaft joined by crossover legs. An LP-class turbine above 45 MW has no HP steam chest or trip-and-throttle valve: a single flow is an inlet belt and a casing flaring to 0.6 Dc with the exhaust hood (`SteamTurbineTrain.hood`: a ribbed box under an arched cover) at the rear only, a double flow the hood over its whole length; one inlet riser with a butterfly valve on the shaft line and a short exhaust neck on the hood's top (`SteamTurbineDesign.lpNozzles`). A unit's cells are the cells its facets reach, so a drawing change can move a layout row although no solid changed; a two-pole generator above `ProcessUnit.TURBO_GENERATOR` 20 MW is drawn as a turbo-generator.

## Formats

The shape fields a design reads (trays, components, turns, operating point, bank, duty, still options, vacuum) are saved facts read through `ShapeRepair` ([equipment-structure.md](equipment-structure.md)); the shape-field revision and its corpus are rows of [FORMATS.md](../FORMATS.md). The layout table `src/test/resources/compat/layouts/layouts.txt` records what reference shapes place.

## Tests and gates

In `equipmentGates` unless noted:

| Test | Proves |
|---|---|
| `ProcessUnitTest` | Every size of every kind on its grid (`everySize`); nozzles face their fluid and each neck clears at its own largest pipe; free pipe blocks; moving parts are the still parts set going; piston rods hide their ends; decks walked and ladders climbed, a player (`PlayerWalk`) gets off at each landing; rests within `MAX_CLIMB`; the kit pieces; `designsShareOnlyTheKit` |
| `ColumnStructureTest` | Platforms, fenced decks, alternating ladders, side nozzles in their bands on their home sides or turned, unplaced nozzles listed, decks and davit clear of the nozzles' rooms, the vapour return with a kettle, trays from a calculator |
| `AirCoolerBayTest` | The larger bays rate as AIR_COOLER_BAY_PLAN.md says; every bay to 3 by 6 m keeps its face, support and fan speed; every listed bay builds, its fans in whole turns |
| `ColumnComponentsTest` | What a design requires; member counts in range; the trim count; a design whose nozzle finds no row is not saved |
| `EquipmentLayoutGoldenTest` | Every recorded layout equals `layouts.txt`; a changed layout whose revision did not rise fails first, naming the kind |
| `EquipmentRefreshTest` | Every kind declares one layout revision, 1 or more |
| `UnitTextTest` | Every kind's constructor and datasheet lines fit, in every language |
| `SteamTurbineSectionsTest`, `MachineRpmDesignTest`, `ConstructorDefaultsTest`, `CrossflowEquipmentTest` | Turbine sections; machine speeds; starting sizes; crossflow exchangers and their layouts |
| `CoolingTowerGeometryTest` | The towers ([cooling-towers.md](cooling-towers.md)) |
| `FlowsheetPaletteCoverageTest` (`test`) | Every kind has a simulator tile, port rule and lang line (AGENTS.md, "New equipment") |

Offline: `tools/new-units/` (sweeps every size through the walk, climb and pipe checks, quad counts), `tools/equipment-drawings/` (`update.ps1 -Check`, then `-Only <kinds>`; AGENTS.md).

## Traps

- A layout change fails `EquipmentLayoutGoldenTest` until its design's layout revision is raised and `layouts.txt` is updated (the test writes the new table under `build/compat/layouts/`); a moved nozzle pipe block needs the owner's approval (AGENTS.md).
- The layout table records three sizes per kind: a layout change confined to sizes between them is not caught.
- The simulator's Save design checks nozzles on the least skirt, the constructor on its own: a design saved fine can be refused at Build.
- A turned nozzle with no free row on its side leaves the column unbuildable until the turn is undone (Save design knows no turns).
- `withTrays` and `withFeed` drop a column's components; `withComponents(null)` drops its operating point.
- New kinds join the simulator (`FlowsheetCatalog`), the reference items and pages (`EquipmentInfoCoverageTest`, `GuideCoverageTest`, [tutorial.md](tutorial.md)); a renamed kind needs a `ShapeRepair` alias and an item alias.

**Snapshot:** `d6f721b94`, 2026-11-15.
