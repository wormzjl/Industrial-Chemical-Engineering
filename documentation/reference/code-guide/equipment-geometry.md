# Equipment: shapes, cells, collision and the drawn fittings

Up: [code guide](README.md). The kinds' designs (units, columns, storage, machines): [equipment-designs.md](equipment-designs.md). Placement in the world, saves, refresh and the constructor's packets: [equipment-structure.md](equipment-structure.md). The client models that draw these shapes: [client-rendering.md](client-rendering.md). Model and sound rules: the `models-and-sfx` skill ([SKILL.md](../../../.claude/skills/models-and-sfx/SKILL.md)).

## Read this when

- A vessel, column or unit is shaped, collided with or climbed wrong, or a pipe does not meet a nozzle.
- A tool refuses a pipe at a column's or a plunger pump's nozzle as too large (the nozzle's largest pipe, `NozzleBore`).
- You change cell keys, nozzle codes, the 1/8-block solids, ports, the fluid node cells, the outline, the flow arrows (`PortMarkings`), paint channels or the Low detail drawing.
- You change the drawn shape of a pipe or a fitting (valves, sight glass, Y-strainer, in-line movers), a gauge dial, a drum's blanketing or safety valve, a tank's level gauge, the service housings, sockets or instrument wiring.
- You change which heat sides a unit has (`HeatSides`), where a nameplate sits, or the name rule.

## What it does

`science/equipment` is Minecraft-free and deterministic. An `EquipmentShape` is a vessel's record: its kind, sizes, frame, heading, support, nozzles and the kind's own fields. `ShapeRasterizer` turns a shape into what the world needs, one block cell at a time: the layout of shell and interior cells, a **cell key** per shell cell (what its model and collision are built from), the surface polygons clipped to each cell, the 1/8-block solids, the climb zones, the ports and fluid node cells, the moving parts and the outline. Every surface polygon is clipped to its own cell, so a chunk bakes each shell cell like a normal block and no cell reads another. `Mesh` holds the planar convex facets with a material (a paint channel or none) and a moving-part index. The same package draws pipes, fittings, dials, the drums' valves and the service housings, and states the rules that are not drawing: a name, a nameplate's place, a nozzle's largest pipe, which way a stream passes a nozzle, a unit's heat sides.

## Class map

Paths under [`src/main/java/com/wormzjl/icheme/science/equipment/`](../../../src/main/java/com/wormzjl/icheme/science/equipment/). Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| **Shape and cells** | | |
| `EquipmentShape` | A vessel's record: `Kind` (`unit`, `storage`, `designed`, `reservoir`, `mover`), diameter, tangent length, `Orientation`, `Heading` front, support, `ColumnTrays`, drum `Connection`s, `Bank`, boot (`Boot`, `bootFor`), `outlet`/`outlets`, `TowerBank`, `MachineDuty`, still options, the material of construction (`Metallurgy`, `withMaterial`; not part of the layout), `ShaftEnd`s; validation; the frame and `transform`; `nozzles` (`Nozzle`, `Role`, `PortClass`); `clashes`; volume, area, `levelHeight`, `fluidGeometry`; `fluidDevice` (also by kind); `MAX_VOLUME`, `MAX_SUPPORT` | 1383 |
| `ShapeRasterizer` | Shape to `Layout` (shell and interior `Cell`s), `CellKey`, `surface`/`fixedSurface`, `solid` (`Box`es), `ports` (`Port`), `nozzleCells`, `fluidCell`, `fluidCells`, `outline`, `climbable`, `hides`, `moving`, `floating` (`Floating`), `skylight`, `marks`, `stillParts`, `forgePort`, `tankGauge`, `blanketValve`, `reliefValve`, `reliefValveOnPipe`, `layoutRevision`, `legacyStamp`; the bounded `Geometry` cache; `serverOnly` | 2055 |
| `Mesh` | Planar convex facets with a `Material` and a part index; primitives (prisms, frustums, rings, discs, boxes, elbows, spheres, hoops, tori); quarter-turn `Transform`; the cut into one cell's polygons; `headHeight` | 1186 |
| `Part` | Blender OBJ parts from `assets/icheme/parts/`, flat triangle pairs merged into quads | 195 |
| `PipeSizes` | Drawn OD from bore, flanges, Class 150 bolting, `code` (the nozzle code), `drawn`, `MIN_OUTSIDE`, `MAX_OUTSIDE`, `MAX_BORE` | 110 |
| `NozzleBore` | The largest pipe a nozzle takes: `largest`, `fits`, `proposed`, `clear`; `STEP`, `SURFACE`; cached, never saved | 149 |
| `Paint` | Body and structure colours, the RAL swatches, `DEFAULT`, `tint`, a laid line's single colours or `BARE`, `line` | 104 |
| `VesselPlacement` | The box origin from the constructor (`forward`, `right`, `up`); `Form`; `front` | 109 |
| `PortMarkings` | Which way a stream passes each nozzle (`Flow` IN, OUT, NONE; `flow(kind, role)`), the pass-through `partner`, and the word-free arrows (`surface`, `fitting`, `fittingOverhanging`; `SHORTEST`) | 337 |
| `BoundedCache` | The one cache of the equipment: a count and a weight bound, second-chance eviction, hits without a monitor | 165 |
| `RenderDetail` | `NORMAL` or `LOW`: drawing only | 19 |
| `HeatSides` | A unit's heat sides: `unit(kind)` (`Unit`), `of(shape)` (`Definition`, `Side`), `pool`, `kettleShell`, `kettleBundle`, the passages, the air (`AIR`, `NATURAL_DRAUGHT`, `fanPower`), the shared cooling numbers (`AIR_SPLIT_TEMPERATURE`, `airSplit`, `TOWER_WATER_TEMPERATURE`, `WATER_RANGE`, `WATER_APPROACH`, `coolingWaterOutlet`), `coilDuty`, `CONVECTION_U`, `heatSide` | 251 |
| `DeviceName` | A placed device's name rule: `clean`, `MAX`, `NONE`, `valid`, `titled` | 50 |
| `Nameplate` | Where a named structure's plate sits (`of`) and a one-block device's tag (`tag`); `EYE`, `SIZES`, `STAND_OFF`, `CLEAR`, `LIFT` | 141 |
| **Pipes, fittings, instruments** | | |
| `LineGeometry` | One fluid-device block's drawn shape: runs, elbows, tees (off-centre with fitted sweeps), reducers, flanges with a seam, valve, strainer (`filterPolygons`), `fittingEnd`, `overhanging`, `SPOOL`; transition pipes (`Key.offsets`, `OFFSET`, `transition`) | 846 |
| `ValveGeometry` | Check, shutoff (with `shutoffLever`, `shutoffStem`, `shutoffPivot`), pressure valve castings; the diverter's actuator apart (`diverterActuator`, `diverterAxis`) | 188 |
| `InlineMoverGeometry` | In-line pump and geared compressor; `supports`, `boundaryJoints` | 249 |
| `IntakeGeometry`, `HopperGeometry` | A gas intake's louvred filter house under a rain hood; a liquid hopper's square funnel on a short body; each with flanged stubs to its joined faces (`FluidDeviceModel`, `"body"`) | 55, 75 |
| `SightGlassGeometry` | Round-window housing by bore; `supports`, `towards`, `window`, `bubbles` | 168 |
| `BlanketValveGeometry` | A drum's gas blanketing valve on its reservation: `of`, `bounds`, `face` | 114 |
| `ReliefValveGeometry` | A pressure safety valve on a drum or a pipe (`onPipe`): `of`, `face` | 185 |
| `TankGaugeGeometry` | The level gauge's proportions (`CHAMBER_RADIUS`, `BAND_HALF`, `GLASS_HALF`), `draw`, `bounds`, `bandFaces`, `markerFaces` | 129 |
| `GaugePlace` | A level gauge's place, kind-neutral | 30 |
| `InstrumentGeometry` | Dial housing, needle, process connectors; adapts `EquipmentInstruments` for mounts | 112 |
| `EquipmentInstruments` | The probe locations and the manual or built-in policy, shared by placement and drawing | 585 |
| `InstrumentWiring` | The instruments' wiring finish (roots, drops, surface runs); no network node | 766 |
| `EquipmentServices` | Service housings: enclosure, brackets, DATA and FE datums and solids (`necks`, `fitPad`, `serviceHousing`) | 806 |
| `ServiceSurface` | Queries on the native facets for mounting; never a bounding box | 201 |
| `SurfaceSection`, `SurfaceJoints` | Sparse cross-section and facet graphs the wiring and mounts follow | 212, 228 |
| `SocketGeometry` | Data and FE sockets: the datum a line enters at and the gland drawn there | 91 |
| `ShaftGeometry` | The six-sided steel profile of shaft blocks and machines' shafts | 58 |
| `VisualRotation` | A presentation clock for rotation: readable angle with a continuous response to rpm | 53 |
| **Owned by other chapters** | | |
| `PipeRoute`, `RackGeometry`, `LineSelection` | The laying tool's route, the rack cell, pipe picking ([pipe-laying.md](pipe-laying.md)) | 670, 1175, 47 |
| `ConduitGeometry`, `CableMedium`, `DcsGeometry` | Conduits, cable media, the DCS console ([wired-control-power.md](wired-control-power.md)) | 352, 27, 162 |
| `CoolingTowerBasin` | A tower basin's surface mesh ([cooling-towers.md](cooling-towers.md)) | 32 |

## Main flow

1. A design (`UnitDesign.of(kind)`, [equipment-designs.md](equipment-designs.md)) builds the shape's mesh in its own frame; `ProcessUnit.of` caches it for the shape facing north.
2. `ShapeRasterizer` builds a `Geometry` once per shape (bounded cache): `transform` turns the frame to `front` and shifts the box to start at 0; the cells are sampled into shell and interior; each nozzle's cells, the ports and the fluid node cells are found; the surface is cut into each cell.
3. The world reads `layout`, `key`, `solid`, `ports`, `fluidCells`, `climbable` and `layoutRevision` ([equipment-structure.md](equipment-structure.md)); the client reads `fixedSurface`, `moving`, `floating` and `outline` ([client-rendering.md](client-rendering.md)).
4. A pipe's model key (`LineGeometry.Key`) gives its polygons, with the fitting geometries for a fitted pipe.

## Contracts

- **Frames.** 1 block = 1 m. A vessel is built in its own frame, axis along +y; the front (liquid outlet, skirt opening) is frame −z; a lying drum's frame x is up, y its axis. `width` is odd, so the axis and every nozzle's centre line run through cell centres, and a flange face lies on a block face. The origin is the box's lower north-west corner. The **controller** is a role: the lowest shell cell, then westmost, then northmost.
- **Cell keys.** `CellKey(shape, x, y, z, nozzles)` is the model's and the collision's key. A body cell (wholly in the shell or skirt, clear of every opening, support, ladder column and nozzle) replaces its axial coordinate with `BODY − class` (eight classes: skirt or shell, four course phases), so callers build each class once.
- **Nozzle codes.** A key holds a 7-bit code per nozzle reaching the cell (`PipeSizes.code` of the drawn OD; 0 = unconnected, drawn at its default size under a blind flange); at most `KEYED_NOZZLES` reach one cell. A nozzle's cells are those of its largest size, so the layout never depends on what is connected.
- **The largest pipe** (`NozzleBore`): `PipeSizes.MAX_BORE` for every kind but a column and a plunger pump (and a boot drum's oil outlet beside its saddle, `EquipmentShape.Boot`); on a column or a plunger pump, the largest bore in `STEP`s whose neck stands clear of the rest of the unit beyond `SURFACE` from the vessel, never less than the design pipe. `proposed` gives the same for the simulator's sizing without keeping the geometry.
- **Collision.** `ShapeRasterizer.solid(key)` samples the cell at 8³ voxel centres (`SOLID_RESOLUTION`) and greedy-merges them; both sides compute the same shape; a wall a player must meet is at least one voxel thick (`Access.RAIL_BAND`, `RUNG_FACE`, `NARROW_BAND`). Climb zones (`climbable`) are shell cells, drawn or not.
- **Light.** Shell cells pass light; interior cells are opaque, but `skylight` vessels (a skirt that shows its inside) and a tank's interior pass it ([equipment-structure.md](equipment-structure.md)).
- **Ports and nodes.** A port is a nozzle's flange-face cell, its outward face, its class and role. `fluidCells` gives one node per registration: a heat unit's sides at their first inlets' port cells, then a kettle's pool node.
- **Flow arrows** (`PortMarkings`): every kind's IN and OUT necks carry one band of four arrows pointing with `flow(kind, role)`, stopped where the neck is hidden (`hides`); a tank's or sphere's ports carry none. Drawing only: the network enforces no direction; the assemblies read the same `flow`.
- **Determinism.** All trigonometry goes through `DeterministicMath`, so layouts, stamps and solids are bitwise equal on client and server and on every JDK (`DeterministicMathSourceTest` scans this package).
- **Server only.** `serverOnly()` (dedicated server) drops facets once laid out; layout, keys, ports, solids and revisions stay; `surface`, `fixedSurface` and `moving` throw.
- **Low detail** (`RenderDetail.LOW`) changes drawing only: facts, collision, layout, nozzle and shaft positions are those of Normal.
- **Names.** `DeviceName.clean` drops formatting codes and control characters, trims and cuts to `MAX` code points; empty is no name; two devices may share one. A nameplate fits where its corners stand within `STAND_OFF` of the surface and `CLEAR` before it holds no solid or climb zone; drawn, never a cell.
- **Valves on drums** have no cells: they are drawn, and the host's registration takes their inlet and outlet as its `BLANKET` and `RELIEF` ports ([instrument-accessories.md](instrument-accessories.md)).

## Laws and constants

- Shell facets: `max(12, 4⌈k π D⌉)`, k = 0.75 for a drum, 1 for a column; heads facets/4 bands; a unit `ShapeRasterizer.MIN_UNIT_FACETS` and 3 bands (`ShapeRasterizer`).
- Pipe OD = 1.1 × bore within `PipeSizes.MIN_OUTSIDE` and `MAX_OUTSIDE`, so a flange stays in one block; codes in 1/64 m steps (`PipeSizes`).
- A flange face stands at least `STAND_OUT` beyond the surface (`EquipmentShape`).
- `Paint.tint` scales a neutral sprite by 255/`SPRITE_VALUE` (`Paint`).
- Caches (`BoundedCache`): the geometry caches of `ShapeRasterizer`, `ProcessUnit`, `NozzleBore`, `ColumnStructure`, the world's collision and roof caches and the client renderers' caches all use it; a bound of N holds N entries; a null is not kept.
- An 80 000-quad ceiling for one unit at its largest size is the drawing budget; no test pins it (offline sweep `tools/new-units/`).

## Formats

The shape's saved fields, the shell cell NBT and the layout revision are in [equipment-structure.md](equipment-structure.md); revisions and numbers in [FORMATS.md](../FORMATS.md). Nothing in this package is read from disk but the OBJ parts.

## Tests and gates

In `equipmentGates` (`src/test/java/com/wormzjl/icheme/science/equipment/`; run `equipmentGates --tests '*Name*'`):

| Test | Proves |
|---|---|
| `ShapeRasterizerTest` | Grid refusal; cell pieces add up to the faceted area in their cells; body keys repeat; interior sealed; solid matches the volume; fluid node a shell cell; controller order; turning the front; nozzles on block faces; ports by position; nozzle sizes by pipe; outline; a heat unit's nodes; the tank and sphere; the roof and ladder apart; the skylight; the drum valves and a pipe's safety valve; the server view equals the drawing side's |
| `LineGeometryTest` | Pipe pieces in their cell, watertight, outer faces only; reducers, flanges, blind flanges; sight-glass necks; transition pipes and their rims |
| `PortMarkingsTest` | Flow per role, pass-through partners, every arrow on its neck pointing with the stream |
| `PaintTest`, `VesselPlacementTest`, `BoundedCacheTest` | Paint and tint; where a build lands; eviction keeps a used entry |
| `DeviceNameTest`, `NameplateTest` | The name rule; plates clear of nozzles and ladders, turning with the structure, a tag towards the eye |
| `HeatSidesTest` | Each unit's sides, surfaces and passages |
| `EquipmentServicesTest`, `InstrumentWiringTest`, `EffectMarksTest`, `RenderDetailTest` | Service housings reach their sockets; wiring; effect marks; Low detail keeps the facts |
| `ProcessUnitTest` | Every neck at its own largest pipe clears; a column's nozzle takes the largest pipe its place clears ([equipment-designs.md](equipment-designs.md)) |
| `PipeRouteTest`, `PipeRackGeometryTest` | The route and the rack ([pipe-laying.md](pipe-laying.md)) |
| `science/fluid/DeterministicMathSourceTest` (`test`) | No non-deterministic math in `science/` |

## Traps

- `CellKey` coordinates are not always cell coordinates: a body cell's axial coordinate is negative.
- `Kind.unit()` is `ordinal() >= EXCHANGER` and `designed()` is `ordinal() >= TANK`: a new vessel of revolution goes before `TANK`, a new storage vessel between `SPHERE` and `EXCHANGER`, a new unit after it.
- `EquipmentShape.diameter`, `tangentLength` and `support` are a unit's own sizes (a bay's width, a compressor's throws, a machine's power); only a drum and a column read them as a vessel's.
- A 10th nozzle reaching one cell throws when the geometry is built.
- Fittings, dials and a tank's roof are not baked from a cell key alone: fittings from the pipe's state and drawn geometry, the roof, ladder, level bands, sight-glass liquid and needles per frame ([client-rendering.md](client-rendering.md)).
- `NozzleBore.proposed` keeps no geometry in the cache: proposed sizes are shapes no world places.

**Snapshot:** `9195b5161`, 2026-11-16.
