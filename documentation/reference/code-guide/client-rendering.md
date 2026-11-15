# Client: equipment drawing, world-space displays and chunk models

Up: [code guide](README.md). The shapes drawn here: [equipment-geometry.md](equipment-geometry.md) and [equipment-designs.md](equipment-designs.md). Effects, process sounds, hand-valve turns and far silhouettes: [effects.md](effects.md). The laying tool's client: [pipe-laying.md](pipe-laying.md). The model and sound rules and the owner's taste: the `models-and-sfx` skill ([SKILL.md](../../../.claude/skills/models-and-sfx/SKILL.md)).

## Read this when

- You change how shell cells, pipes, pipe fittings, racks, conduits or shafts are meshed, tinted or lit, or a painted line's colours.
- You change a world-space display: an open tank's roof, rolling ladder and level glass, a sight glass's liquid and bubbles, a gauge dial, a nameplate, the cable glands, a cooling tower basin's water.
- You change the constructor's ghost, the vessel outline, units' moving parts, the coarse bodies at Low detail, or the detail tiers by screen size.
- A structure draws a hole at a chunk edge, a moving part stands still, a ghost or outline is wrong, or frame time rises with many structures.

## What it does

Equipment is drawn mostly in the chunk mesh. A shell cell's model (`EquipmentShellModel`) bakes the quads of its `ShapeRasterizer.CellKey` once; body cells share a key per texture class. Pipes and fittings (`FluidDeviceModel`), rack cells (`PipeRackModel`), conduits (`ConduitModel`) and shafts (`ShaftModel`) mesh from their block entities' model data. Paint goes in through the block tint, so a model bakes once per painted mask, not per colour. What moves or follows the process is drawn per frame by client renderers: units' moving parts (`MachineAnimation`), an open tank's roof and glass (`TankRenderer`), a sight glass's liquid (`SightGlassRenderer`), gauge dials (`InstrumentRenderer`), coarse whole-structure bodies (`EquipmentBodyRenderer`). Renderers that follow the process **watch** the nearest devices (one request a second, at most `FluidNetwork.MAXIMUM_WATCHED` of a kind) and draw what the server publishes on its bucket; they compute no process state and nothing is sent unasked. Detail drops with an object's size on the screen (`RenderLod`).

## Class map

Paths under [`src/main/java/com/wormzjl/icheme/client/`](../../../src/main/java/com/wormzjl/icheme/client/). Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| **Chunk models (`model/`)** | | |
| `EquipmentShellModel` | A shell cell's chunk model: quads per `CellKey` from `ShapeRasterizer.fixedSurface`, moving parts left out, baked shade and sheen, paint channel as tint index, `Layers` (solid, glass, all), flow arrows baked in; `ATLAS`; shared with the line models: `bake`, `paintedLines`, `painted`, `lineTints` | 305 |
| `FluidDeviceModel` | A pipe or fitting: key = block state + `FluidDeviceBlockEntity.Drawn` (bore, faces, flanges, spool, transition offsets) + a `Look` (fitting, paint mask); polygons from `LineGeometry` and the fitting geometries | 183 |
| `PipeRackModel` | A rack cell: the frame and each lane on painted sprites, tint index 2 × option + channel; key `Look` | 147 |
| `ConduitModel` | A data or FE conduit block's model from its cable facts | 171 |
| `ShaftModel` | The immutable hexagonal shaft model (`ShaftGeometry`) | 147 |
| `PolygonBake` | No Minecraft class: convex polygons to coloured triangle fans (positions, normals, sprite UVs, shade × paint tint); used by `EquipmentBodyRenderer.body` and the film actors | 61 |
| **Equipment client** | | |
| `EquipmentClient` | Registers the shell, fluid-device and rack geometry loaders; the block tints (`registerBlockColours`: paint channel, line paints, lane paints); the vessel outline in place of per-cell boxes (`highlight`); `openConstructor`, `openVessel`; `renderConfigReloaded` | 117 |
| `EquipmentGhost` | The ghost of every loaded constructor's design within `RANGE` until its vessel stands (the open screen's draft instead), blocked cells of the focused ghost outlined red, rechecked every 10 ticks; `blockedCount` | 237 |
| `EquipmentPaints` | The constructor's paint, remembered per account on the client | 54 |
| `EquipmentBodyRenderer` | Coarse equipment bodies (and the natural tower shells) drawn whole: bodies whose origins share a 32-block square merged into one static mesh, one call per square in view; cached by shape, paint and detail | 205 |
| `MachineAnimation` | Units' moving parts (`ShapeRasterizer.moving`) within `RANGE`, turned or slid by `ProcessUnit.Motion`; a constructed mover only while `MoverSounds.running`; `toggle`, `running` (the datasheet's switch, no re-mesh) | 268 |
| `RotationAnimation` | Client-owned shaft speed snapshots and continuous angles on `VisualRotation`, watched through `ClientWatch` ([effects.md](effects.md)) | 246 |
| `VertexBufferCache` | Uploaded meshes by key (render thread): past its soft size closes only meshes no frame drew for a second, past its hard size the least recently drawn | 69 |
| `RenderLod` | Drawing only, no Minecraft class: pixels an object covers and its detail tier (`tier`, `HYSTERESIS`, `Tiers`, `View`; `LEAST_SCALE`, `MOST_SCALE`) | 99 |
| `Outlines` | World outlines by slot (box, line, block cluster) in one colour and width, with a fade and a glide; used by the ghost and the laying tool | 250 |
| **World-space displays** | | |
| `TankRenderer` | An open tank's roof (floats on the liquid), rolling ladder and level glass from its published `TankDisplay.Glide`; watches tanks within `WATCH_RANGE`; GPU buffers in a `VertexBufferCache`; `renderGlass` after block entities | 365 |
| `SightGlassRenderer` | A watched sight glass's liquid colour and bubble rings from the published gas share and flow direction within `RANGE`; metal and glass stay chunk-baked | 152 |
| `LiquidRenderer` | Shared translucent liquid drawing for tank gauges and open basins | 29 |
| `CoolingTowerBasinRenderer` | A tower basin's water surface at its published opacity ([cooling-towers.md](cooling-towers.md)) | 51 |
| `InstrumentRenderer` | Gauge dials, transmitters, wiring (`icheme:rendertype_wiring` on the shader path), placement ghosts and the one-line hover; watches the nearest gauges; `identities` ([instrument-accessories.md](instrument-accessories.md)) | 705 |
| `NameplateRenderer` | A named structure's plate where `Nameplate.of` puts it (worked out once per shape, off the render thread) and a one-block device's tag (`Nameplate.tag`), within `RANGE`, on the side it faces | 138 |
| `DataControlRenderer` | Data and power cable glands, leads and rack cables (structure only, no readings), in view; ports gathered every `HOST_TICKS` | 413 |
| **Owned by other chapters** | | |
| `MoverSounds`, `DriverSounds` | The movers' and drivers' running loops through the one sound budget ([effects.md](effects.md)) | 117, 135 |
| `ValveTurns`, `ValveTurnRenderer` | A hand valve's turn and its drawing ([effects.md](effects.md)) | 138, 164 |
| `PipeLayingClient`, `CableLayingClient`, `LineToolSession`, `LayingToolAim`, `LineToolMarkers` | The laying tools' client ([pipe-laying.md](pipe-laying.md)) | 411, 602, 365, 150, 80 |
| `AssemblyToolClient`, `PortColours` | The laying tool's view of column assemblies and the port colours ([assembly-service.md](assembly-service.md)) | 157, 55 |

## Main flow

1. A shell cell arrives as a block update with its update tag; `EquipmentShellBlockEntity` hands its key as model data ([equipment-structure.md](equipment-structure.md)); `EquipmentShellModel` returns the cached `Layers` for the key (empty without model data), and only cells holding glass ask for the translucent pass.
2. A pipe's update tag carries its bore, faces, transition offsets and the line's paints; a late data packet re-requests the model data and rebuilds the section. A fitting is part of the pipe's block state, so fitting, toggling or removing one re-meshes the section.
3. Each frame after solid blocks: `MachineAnimation` draws moving parts of units within range from their cached buffers; `EquipmentBodyRenderer` draws coarse bodies by region; `TankRenderer`, `SightGlassRenderer`, `InstrumentRenderer`, `NameplateRenderer`, `DataControlRenderer` draw what is in view, in detail tiers by `RenderLod`.
4. Once a second each watching renderer asks the server to watch its nearest devices and release the rest; the replies (`accept`) come on the device's bucket. Logout clears the caches (`IChemEClient`).
5. While a constructor is loaded, `EquipmentGhost` draws its design until the vessel stands, and marks the cells it cannot take.

## Contracts

- **Shell cells are chunk-meshed** (`RenderType.solid()`), never by a block-entity renderer. No cell reads another cell or chunk.
- **Moving facets** are always left out of the chunk mesh; `MachineAnimation` draws them, at rest while a mover is stopped or the datasheet's switch is off. The switch re-meshes no chunk, holds for the whole client and is not saved.
- **Paint**: a painted quad's tint index is its `Paint` channel; `EquipmentClient` scales the colour so the neutral sprite shows the paint itself (`Paint.tint`). A laid line's pipe and flanges take its two paints through the tint (`EquipmentShellModel.lineTints`); bare steel draws on the old sprites.
- **Outline**: the selection box of any shell or interior cell is the whole vessel's silhouette from the camera plus fixed edges (`ShapeRasterizer.outline`).
- **Glass** (a tank gauge's sleeve) bakes in the translucent pass; the liquid bands behind it are drawn after block entities by `TankRenderer.renderGlass`. A sight glass's metal and glass are baked; the renderer adds only liquid and bubbles.
- **Flow arrows** on every kind's IN and OUT nozzle necks are baked with the shell (`PortMarkings.surface`); no renderer draws them.
- **Detail**: `ClientConfig.renderDetail` (low polygon mode) is drawing only; mixed-detail clients share the server's facts, collision and layout. `EquipmentClient.renderConfigReloaded` reloads resources only when drawing detail changes. Each renderer keeps its old range as the upper bound of its tiers.
- **Watches**: a renderer watches only what it draws, asks once a second, and drops what left; it never infers process state. A tank with no display draws its roof on its legs and an empty glass.
- **Distant Horizons**: without model data a shell cell's model is empty and its particle sprite gives its colour.
- **Threads**: models bake on chunk-meshing workers with the profile they captured; renderers and caches run on the render thread; `Nameplate.of` runs on the background executor.

## Laws and constants

- `RenderLod`: an object of size s at distance d covers k·s/d pixels, k = (framebuffer height / 2) · the projection's m11 divided by `lod_scale`; a tier is lost at a threshold and regained only past `HYSTERESIS`.
- Ranges: `MachineAnimation.RANGE`, `EquipmentGhost.RANGE`, `NameplateRenderer.RANGE`, `SightGlassRenderer.RANGE`, `TankRenderer.WATCH_RANGE` (inside the server's `FluidNetwork.WATCH_RANGE`); `DataControlRenderer.HOST_TICKS`.
- `ProcessUnit.MOTION_PERIOD` holds whole turns of every motion speed ([equipment-designs.md](equipment-designs.md)).

## Formats

Models read the block entities' update tags (fields in [equipment-structure.md](equipment-structure.md)); watch requests and displays are on `NetworkProtocol.VERSION` ([FORMATS.md](../FORMATS.md)). The client config's `equipment` section holds `low_polygon_mode`, `lod_scale` and `paint_schemes` ([client-kit.md](client-kit.md)).

## Tests and gates

| Test | Proves |
|---|---|
| `client/RenderLodTest` | Detail tiers and their switch distances |
| `client/model/PolygonBakeTest` | `PolygonBake` bit for bit against the loops it replaced |
| `science/equipment/PaintTest` (`equipmentGates`) | The tint shows the paint on the neutral sprite |
| `ShapeRasterizerTest.outlineFollowsTheViewer` (`equipmentGates`) | The vessel outline |
| `ProcessUnitTest.movingPartsAreTheStillOnesSetGoing` (`equipmentGates`) | Moving parts are the still parts set going (geometry) |
| `science/equipment/LineGeometryTest`, `runtime/fluid/PipelineFittingTest` | Pipe and fitting meshes |
| `science/equipment/RenderDetailTest` | Low detail keeps layout, collision and ports |
| `runtime/fluid/OpenTankRuntimeTest`, `PipePresentationTest` | The tank display and its watchers, the sight-glass sample, mover sound gain |

Drawing itself (ghosts, animation, renderers, tints on racks) has no automated test: software renders (`tools/equipment-drawings/`, the `models-and-sfx` skill) and at most one in-game check the owner asks for. Frame time is measured only in a performance task (`tools/lod-rendering/`).

## Traps

- A committed check-valve reversal or shutoff toggle reaches the pipe model only when the engine applies it.
- The ghost draws as `entityTranslucentCull` on the block atlas, so it survives a shader pack.
- `EquipmentBodyRenderer` rebuilds its region map only when the hash of its structures (origin, shape, paint, light) or their count changes, or after `release`; it merges at most one changed square a frame, its last mesh standing meanwhile.
- `DataControlRenderer` shows a port placed or removed up to `HOST_TICKS` late.

**Snapshot:** `d6f721b94`, 2026-11-15.
