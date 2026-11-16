# Equipment: structures in the world, their saves, refresh and the constructor's server side

Up: [code guide](README.md). Shapes and cells: [equipment-geometry.md](equipment-geometry.md); the kinds' designs: [equipment-designs.md](equipment-designs.md); the screens: [client-equipment-screens.md](client-equipment-screens.md); the models: [client-rendering.md](client-rendering.md). A structure's fluid devices on the engine's side: [runtime-registry.md](runtime-registry.md); assemblies: [assembly-service.md](assembly-service.md); shaft trains and FE ports: [shaft-trains.md](shaft-trains.md).

## Read this when

- The constructor will not build, Import lands the wrong design or none, a template builds wrong, or Deconstruct refuses.
- A placed structure changed, was repaired, rebuilt or removed after an update or a load; a log shows `equipment_compat`, `equipment_refresh`, `equipment_restate` or `equipment_realign`.
- You rename, remove or add a `Kind`, `Form`, `Heading` or `Orientation` constant or a shape field; you change the shell cell's or the constructor's NBT.
- A reservoir's, mover's, heat side's, still's or converter's registration (ports, volume, level height, open flag) is wrong after a build, a load or a refresh.
- A device's name is lost, refused or not drawn; a floating roof does not carry the player; a structure can be broken where it should not.

## What it does

A constructor build places a structure whole, as plain blocks: **shell** cells, which carry the shape and draw their own piece of the surface, and invisible **interior** cells. `EquipmentStructure` is the entry point: placement, lookup, collision, names, nozzles, dismantling and the debug command; beside it `StructureRegistration` registers the structure's devices with the fluid world by kind, `StructureRefresh` brings a loaded structure to the current layout (and removes retired kinds, restates interior cells), and `StructureRealignment` rebuilds a structure to the registry's record. A saved shape is a fact (AGENTS.md, "Saves and data formats"): it is read by name onto the current rules and repaired where they refuse it (`ShapeRepair`). `EquipmentNetwork` carries the constructor's packets, Import from the nearest process simulator, column templates, renames, deconstruction and the far silhouettes. Only a Creative player breaks a cell, which dismantles the structure; `EquipmentProtection` and `EquipmentChunkMixin` refuse every other write to a structure's cells.

## Class map

Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| **World (`world/level/block`)** | | |
| `EquipmentStructure` | The entry points: `place` (with the name every shell cell takes), `rename`, `name`, `at`, `find`, `collision`, `heatSides`, `movers`, `sideIdentities`, `heldAt`, `refreshNozzles`, `refreshNozzlesAt`, `dismantle`, `record`, `registerCommands`; forwarders to the three classes beside it (`restoreFluidBinding`, `restateLater`, `refreshLater`, `removeRetiredLater`, `realignLater`, `onLevelTick`); `FIND_STEPS` | 574 |
| `StructureRegistration` | A structure's devices by kind: `register` (reservoir, mover, turbine, heat unit with its sides as partners, still, converter, flare, chimney, prime mover), `restoreFluidBinding`, `reregister` (one queued edit, identities kept), `unregister`, `converterDevices`; a machine's law from `MachineRatings` | 663 |
| `StructureRefresh` | After a load: the STALE, RETIRED and RESTATE queues served by `onLevelTick` once a region is `ready`; `refresh`, `removeRetired`, `restate`, `restatePaint`; `survey`, `write`, `settle`, `waitForLight`, `loaded`, `corner` | 459 |
| `StructureRealignment` | The registry first for structures: `realignLater`, `serveRealignment`, `realign`, `stands`, `members`, `holdShaft` | 174 |
| `Positions` | `of(level, at)`: a block's fluid-topology position (no lane), for every world file | 16 |
| `EquipmentShellBlock` | A shell cell: transparent, collision from its key (a full block while unbound), `isLadder`, `neighborChanged` to the nozzles; use decided on the server (the column's assembly screen, else the structure's own menu or datasheet) | 197 |
| `EquipmentInteriorBlock` | An interior cell: an invisible full cube with no data; `OPEN` (a tank's), `SKYLIGHT` (a skirt that shows its inside) | 146 |
| `EquipmentConstructorBlock` | The constructor bench: `FACING` names the build direction; opens the screen on the client | 109 |
| `EquipmentDeconstruction` | `target`: the nearest loaded structure on the constructor's line, independent of the draft | 143 |
| `EquipmentProtection` | Exact-cell, level and thread authority for lifecycle edits and Creative removal; no permission outlives an operation | 61 |
| `FloatingRoofs` | A level's floating roofs: open cells collide up to the roof (`standing`), `carry` lifts what stands on a rising roof, `ladder`, `climb` | 413 |
| **Block entities (`world/level/block/entity`)** | | |
| `EquipmentShellBlockEntity` | One shell cell: shape, cell, nozzle codes, paint, name (`name`, `rename`, `bind`), layout revision and box (`layoutIncomplete`); `readShape`, `saved`, `writeShape`; a retired cell's `retired` kind and `retiredData`; the node cell's fluid identity; a tank's roof level; the gauges' metadata; `largestBore`, `largestBoreBeside` | 788 |
| `EquipmentConstructorBlockEntity` | The constructor's `Design` (with `column`, `turns`, `name`; `buildable`, `imported`, `landed`, `keepingColumn`, `withName`, `withTurns`, `fresh`), `vessel`, `apply`, `simulator`, `readDesign`, `writeDesign` | 465 |
| `ColumnDesignTags` | A column design's NBT by name (trays, diameter, components, member counts, turns, operating point): `write`, `read`, `writeComponents`, `writeTurns`, `writeOperating`, `saved`, `savedTurns`, `savedOperating` | 228 |
| `SentDesignTags` | The designs Send to block stores on a simulator: `write`, `read` (a `Read` of designs, unread entries kept, repairs), `whole` | 107 |
| `DeviceNames` | A device's `Name` tag: `KEY`, `read` (cleaned and logged, never refused) | 37 |
| `LoadedEquipment` | The structures of one client level with a cell loaded (for the body renderer) | 65 |
| `ModelledBlockEntity` | One update lifecycle for chunk-baked geometry (equipment, pipes, rack lanes, conduits) | 55 |
| **Saves and refresh (`science/equipment`)** | | |
| `ShapeRepair` | A saved shape onto the current rules: `Saved`, `Read`, the alias maps (`KIND_ALIASES`, `FORM_ALIASES`, `HEADING_ALIASES`, `ORIENTATION_ALIASES`, `ROLE_ALIASES`, `DRIVE_ALIASES`, `STEAM_ALIASES`, `SPLIT_ALIASES`, `EXCHANGER_TYPE_ALIASES`), `read`, `kind`, `form`, `exchangerType`, `SavedComponents`, `SavedTurn`, `SavedDuty`, `turns`; never throws | 465 |
| `EquipmentRefresh` | The refresh plan: `Holding`, `Plan`, `anchor` within `REACH`; which saved cell is current (`placedRevision`, `legacyRevision`, `STALE`) | 158 |
| **Others** | | |
| `EquipmentNetwork` | `network/`: the payloads (`ConstructorPayload` with its `Action`, `ImportList`, `ImportListAnswer`, `ImportChoice`, `ImportAnswer`, `TemplatePayload`, `Rename`, `Deconstruct`, `DeconstructAnswer`, `SilhouetteWatch`, `SilhouetteBatch`), admission and chat replies; `IMPORT_REACH`, `MAX_TEXT` | 644 |
| `EquipmentChunkMixin` | `mixin/`: refuses a `LevelChunk.setBlockState` on a structure cell outside `EquipmentProtection`'s authority | 34 |
| `MachineRatings` | `science/plant/machine/`: a placed machine's law from its shape (`machineControl`, `ejectorControl`) | 50 |

## Main flow

**Build** (`ConstructorPayload` with `BUILD`):

1. `EquipmentNetwork` checks the player (loaded, within reach, may build, not a spectator, may interact), rebuilds the `Design` (its constructor validates the shape) and keeps the constructor's imported column and name (`keepingColumn`: a packet never sets them).
2. A column without an import is refused (`import_first`), one whose side nozzle finds no row on the constructor's own skirt too (`ColumnDesign.misfit`, `misfit`); so is a build with side-connection clashes.
3. `EquipmentStructure.place` checks every cell (loaded, inside the build height, replaceable) before setting any; the first blocked cell is named and nothing is placed. Cells are set with client updates and no neighbour shape updates; each shell cell binds its shape, cell, paint, name, layout revision and box.
4. `StructureRegistration.register` queues the structure's devices in one ledger edit: a drum, tank or sphere at its lowest inside cell with a port per nozzle (a tank open); a pump or compressor at its suction nozzle with SUCTION and DISCHARGE ports, its device kind its law's (`MachineRatings.machineControl`: a liquid mover, a centrifugal or a plunger pump, is a `PUMP`, else a `COMPRESSOR`); a steam turbine likewise; a heat unit's sides (`HeatSides.of`) at their first inlets, a kettle's shell as a vessel, all as partners in one `placeUnit`; a still, converter, flare, chimney or prime mover by its own rule. Structures with shaft ends queue their shaft part, one with a terminal cell its FE port. Pipes in front of the ports update; the nozzles take the pipes' sizes (`refreshNozzles`).
5. `AssemblyService.placed` runs; the chat answers (`built`, `built_named`, blocked, clash, invalid).

**Import**: after a `KEEP` of the draft, `ImportList` reads the nearest `ProcessSimulatorBlockEntity` within `IMPORT_REACH` (its saved column design first, untagged, then what Send to block stored) and is always answered with an `ImportListAnswer`; `ImportChoice` lands the chosen design on the draft (`Design.landed`) if the listed index still holds that tag and member (else `import_changed`), answered with an `ImportAnswer` (the design's tags, or why there is none). `TemplatePayload` makes a column design with `ColumnDesign.of` (an air cooler and standard pumps) and is answered the same way.

**Load** (server):

1. A shell cell's `onLoad` reads its shape through `ShapeRepair`; a stale cell queues `refreshLater`, a retired kind `removeRetiredLater`, a controller with closed interior cells `restateLater`; a chunk load queues `realignLater` for structures whose box it touches; the controller restores its fluid binding (`restoreFluidBinding`).
2. `onLevelTick` serves at most one vessel a tick (retired first, then stale, then a restate), the first whose region is loaded and block-ticking, so block changes reach watching players. `StructureRealignment.serveRealignment` runs after the refresh.
3. `refresh`: survey the region, `EquipmentRefresh.anchor` picks the shift, the plan clears dropped cells and places missing ones where the world is free, the cells are rebound (paint and name kept), light is waited for, the registration moves with one queued edit (`reregister`).

**Use**: `EquipmentShellBlock` decides on the server: a column, or a unit a column's report holds, opens the column's assembly screen; a structure with a fluid device opens its fluid menu at its first fluid cell; any other unit sends its datasheet. The fluid debugger opens the device's own screen.

## Contracts

- **Cells.** Every surface polygon is clipped to its cell; a chunk bakes each shell cell from its key alone. Collision is the key's solid at 1/8 block; both sides compute the same shape (`EquipmentStructure.collision`).
- **Saves keep facts** (AGENTS.md). A shape, its paint, nozzle sizes and name are facts. Shape fields are append-only, read by name; a missing field reads its default (the behaviour before it existed); a refused shape climbs the repair ladder (sizes snapped into range, support, connections, outlet, boot, bank, then the kind's default size with the saved front), each repair named in the log. A kind name this build does not have is **retired**: the cell stays unbound, writes its tag back unchanged, and the whole structure is removed on load, registration and contents included, never another block. A component, turn, operating point, member count or exchanger type that does not read is repaired away or to its default and named.
- **Layout refresh** (AGENTS.md). A cell saves the layout revision it was placed by (`LayoutRevision`) and its box; it is current when the revision is its design's and its shape needed no repair (`EquipmentRefresh.placedRevision`); a cell saved before revisions holds only its FNV stamp (`Layout`) and is current while its design is at revision 1 and the stamp equals `ShapeRasterizer.legacyStamp` (`legacyRevision`). Anything else holds `STALE`. The refresh keeps the origin, paint, name, nozzle sizes and fluid identity; picks the shift that keeps most nozzles on a pipe, then most cells; never overwrites a `PIPE` or `OTHER` block; and with blocked cells writes every cell `STALE`, so the next load tries again.
- **Registry first.** The registry records each structure by origin (`record`: shape, paint, name). A structure whose cells stand as its record says is left as it is; any other is rebuilt to its record where the record puts it; a cell another block holds is left out, and the structure's registrations and shaft part are held until a later load finds it whole (`equipment_realign`).
- **Registration** moves with a structure: one queued edit (`Device.withPlacement`) keeps each device's identity and controls; the registry keeps a vessel's contents in its new geometry. A heat unit's sides register, restate and leave together; half a pair never stands.
- **Names** are drawing facts on every shell cell (and on one-block devices that open a screen): `rename` writes all cells or none, only while all are loaded (`rename_unloaded`); the text is cleaned (`DeviceName.clean`); no engine event; a refresh keeps it. A packet never sets the constructor's name: only an import does, and the untagged column design clears it.
- **Protection.** Shell and interior blocks have negative hardness, high blast resistance, refuse pistons and explosions; layout refresh, retirement and restate use the same scoped authority. Deconstruct reselects its target on the server, checks reach, build rights, loaded cells and every cell's break permission before removing anything, and reuses `dismantle` (accessories refunded, contents leave through the removal ledger).
- **Floating roofs** stand where the tank's published level puts them (`TankDisplay.Glide`, the same height on server and client at the same tick); before the first display at the node cell's saved roof level, then a glide over one bucket.
- **Threads.** All of this runs on the server thread; `EquipmentStructure.dismantling` is a static flag so cells removed by a dismantle, refresh or retirement start no removal of their own.

## Laws and constants

- `EquipmentNetwork.IMPORT_REACH`: the simulator an import reads; `MAX_TEXT`: a name or tag on the wire.
- `EquipmentRefresh.REACH`: the largest refresh shift; the refresh region is the larger box widened by `REACH` + 1 along x and z and 1 below and above.
- `EquipmentStructure.FIND_STEPS`: the walk `find` (and the retired removal's interior walk) takes east, then south, over interior cells.
- A vessel over `FluidDeviceSpec.MAX_VOLUME` is logged and not registered; a reservoir has at most `MAXIMUM_PORTS` ports.

## Formats

The shell cell's NBT holds `Kind`, `Diameter`, `TangentLength`, `Orientation`, `Front`, `Support`, the kind's fields (`Trays` with `Components` and `Turns` and `Operating`, `Connections`, `Boot`, `Outlet`, `Bank`, `TowerBank`, `Duty`, still options, `Vacuum`), `Cell`, `Nozzles`, `Paint`, `Name`, `LayoutRevision` (an old save's `Layout` read only), `Box`; the node cell's fluid identity; a tank's `RoofLevel`; the gauges' tags. Enums by lower-case name. The constructor's NBT holds its form, sizes, offsets, the kind fields, `Column`, `Turns`, `Name`, `Duty`. The shape-field revision, the corpus images and the protocol are rows of [FORMATS.md](../FORMATS.md). The corpus: `compat/equipment/shapes-<n>.snbt` (and `repairs-exchanger-types.snbt`), `compat/constructor/`, `compat/simulator/designs-<n>.snbt`, `compat/names/names-1.snbt`, the layout table `compat/layouts/layouts.txt` ([verification-tests.md](verification-tests.md)).

## Tests and gates

| Test | Task | Proves |
|---|---|---|
| `EquipmentShapeCompatTest` | `equipmentGates` | Every kind round-trips with no repair; repairs named one at a time; an unknown kind retired; 3000 random saved values never throw; every `shapes-*.snbt` image reads as saved (a missing image of the current revision is written under `build/compat/equipment/` and fails); a constructor builds a column only from its import and keeps every form's fields |
| `EquipmentRefreshTest` | `equipmentGates` | Revisions and legacy stamps; an older layout brought current; the body stays when the box shrinks; another block never overwritten; nozzles kept on their pipes first |
| `EquipmentLayoutGoldenTest` | `equipmentGates` | The layout table |
| `network/EquipmentNetworkCodecTest` | `test` | The payloads cross; the server keeps its column; an import lands on the draft sent; every proposed size lands as the equipment it is; names; renames |
| `world/level/block/entity/SentDesignTagsTest`, `DeviceNamesCompatTest` | `test` | Sent designs and names read as saved, every image |
| `world/level/block/FloatingRoofsTest` | `test` | The server's roof where the client draws it, the glide, the reload, the collision, the rolling ladder |
| `runtime/fluid/DrumReservoirTest`, `VesselRebuildTest`, `HeatSideTopologyTest` | `test` | Registration by nozzles, a rebuilt or moved device keeps its identity and contents, a heat unit's sides |
| `EquipmentCompatGameTests` | GameTests | The four load cases of a cell's revision or stamp, the skylight restate, a retired kind removed whole, a blocked refresh retried |
| `EquipmentProtectionGameTests` | GameTests | Removal paths, explosions, pistons, scoped authority, deconstruction targets and replies |
| `RegistryFirstGameTests`, `AssemblyGameTests`, `ProcessSimulatorGameTests` | GameTests | A lost or reshaped structure set back; a click on a member opens its column; the constructor imports the nearest simulator's design |

## Traps

- `ConstructorPayload.form` is the `VesselPlacement.Form` ordinal on the wire, and a turn's `EquipmentShape.Role` and the column wire's component kinds are ordinals too; NBT stores names.
- Renaming or removing a `Kind` without a `ShapeRepair.KIND_ALIASES` entry retires it: every placed structure of it is removed whole on load. A `Form`, `Heading` or `Orientation` constant needs its alias too.
- A new shape field needs a default equal to the behaviour before it, read in `ShapeRepair.read`, a raised `SHAPE_FIELDS` in `EquipmentShapeCompatTest` and a new corpus image.
- A pipe block in a cell the new layout needs is left standing; the structure works without that cell and retries at every load.
- `place` with fewer rows than the box builds part of a vessel and registers nothing (debug only). The `column` debug command's diameter stops below a column's largest.
- A cell whose shape was repaired on read is stale, so a repair that keeps the layout costs one refresh that changes nothing.
- Columns and the units that are neither movers, heat units nor the other registered kinds are drawing only. A mover's auxiliary nozzles and a heat unit's nozzles that are not a side's inlet or outlet do not join the network.
- The constructor bench's model faces the placing player, while `FACING` still names the build direction.

**Snapshot:** `9195b5161`, 2026-11-16.
