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
| `EquipmentShellModel` | A shell cell's chunk model: quads per (`ShellVariant`, `CellKey`) from `ShapeRasterizer.fixedSurface`, moving parts left out, baked shade and sheen (BAKED) or flat sprites and unshaded colour (FLAT), paint channel as tint index, `Layers` (solid, glass, all), flow arrows baked in; `ATLAS`, `sprite` (current variant); shared with the line models: `bake`, `paintedLines`, `painted`, `lineTints` | 350 |
| `ShellVariant` | No Minecraft class: BAKED, FLAT or REFLECTED (`of(pbrTextures, packInUse, reflections)`; REFLECTED bakes the shade without the sheen while the reflection pass draws, `sheen()`), the cache `Key`, the flat sprites' fallback (`flatSprites`) | 62 |
| `ReflectionClass`, `ReflectionLaw`, `ReflectionDepth`, `ReflectionMesh` | No Minecraft class: the gloss pass's one table (PAINT, STEEL, CLADDING, GALVANISED: F0, highlight, LabPBR values), its law (a grey sheen of the horizon's brightness by Fresnel, the base `kept` by 1 - F, highlight, sun and moon, range fade; no mirror image, no colour), the depth pull towards the eye, a structure's mesh from `ShapeRasterizer.renderBody` without its pipe runs (`Mesh.Facet.run`) and the 32-block `region` | 78, 102, 49, 84 |
| `FluidDeviceModel` | A pipe or fitting: key = block state + `FluidDeviceBlockEntity.Drawn` (bore, faces, flanges, spool, transition offsets) + a `Look` (fitting, paint mask); polygons from `LineGeometry` and the fitting geometries; a generator block's own `"body"` (`intake`, `hopper`) from `IntakeGeometry`, `HopperGeometry` | 188 |
| `PipeRackModel` | A rack cell: the frame and each lane on painted sprites, tint index 2 × option + channel; key `Look` | 147 |
| `ConduitModel` | A data or FE conduit block's model from its cable facts | 171 |
| `ShaftModel` | The immutable hexagonal shaft model (`ShaftGeometry`) | 147 |
| `PolygonBake` | No Minecraft class: convex polygons to coloured triangle fans (positions, normals, sprite UVs, shade × paint tint); used by `EquipmentBodyRenderer.body` and the film actors | 61 |
| **Equipment client** | | |
| `EquipmentClient` | Registers the shell, fluid-device and rack geometry loaders; the block tints (`registerBlockColours`: paint channel, line paints, lane paints); the vessel outline in place of per-cell boxes (`highlight`); `openConstructor`, `openVessel`; `renderConfigReloaded` (detail: reload; shell variant moved: re-mesh) | 120 |
| `EquipmentGhost` | The ghost of every loaded constructor's design within `RANGE` until its vessel stands (the open screen's draft instead), blocked cells of the focused ghost outlined red, rechecked every 10 ticks; `blockedCount` | 237 |
| `EquipmentPaints` | The constructor's paint, remembered per account on the client | 54 |
| `EquipmentBodyRenderer` | Coarse equipment bodies (and the natural tower shells) drawn whole: bodies whose origins share a 32-block square merged into one static mesh, one call per square in view; cached by shape, paint and detail; baked with the sheen only for a `ShellVariant.sheen()` variant | 207 |
| `EquipmentReflectionRenderer` | The gloss pass (`icheme:equipment_reflection`, [effects.md](effects.md)): the glossy facets of structures within `reflection_range` merged per 32-block square, drawn after the solid blocks (blend ONE, ONE_MINUS_SRC_ALPHA: the base keeps 1 - F), LEQUAL without depth write, pulled by `ReflectionDepth.pull`, faded over the last quarter of the range and with the fog; `active()` (no pack, its switch, strength above 0, shader loaded, its `GUARD` closed); `release` | 412 |
| `MachineAnimation` | Units' moving parts (`ShapeRasterizer.moving`) within `RANGE`, turned or slid by `ProcessUnit.Motion`; a constructed mover only while `MoverSounds.running`; `toggle`, `running` (the datasheet's switch, no re-mesh) | 268 |
| `RotationAnimation` | Client-owned shaft speed snapshots and continuous angles on `VisualRotation`, watched through `ClientWatch` ([effects.md](effects.md)) | 246 |
| `VertexBufferCache` | Uploaded meshes by key (render thread): past its soft size closes only meshes no frame drew for a second, past its hard size the least recently drawn | 69 |
| `RenderLod` | Drawing only, no Minecraft class: pixels an object covers and its detail tier (`tier`, `HYSTERESIS`, `Tiers`, `View`; `LEAST_SCALE`, `MOST_SCALE`) | 99 |
| `Outlines` | World outlines by slot (box, line, block cluster) in one colour and width, with a fade and a glide; used by the ghost and the laying tool | 250 |
| **World-space displays** | | |
| `TankRenderer` | An open tank's roof (floats on the liquid), rolling ladder and level glass from its published `TankDisplay.Glide`; watches tanks within `WATCH_RANGE`; GPU buffers in a `VertexBufferCache`; `renderGlass` after block entities | 365 |
| `SightGlassRenderer` | A watched sight glass's liquid colour and its bubbles, carried by the published speed and direction (`SightGlassGeometry.velocity`, `Travel`, `bubbles`) within `RANGE`: one quad each on `icheme:sight_glass` (switch `sight_glass_shader`), else elliptic rings shrunk at the rim; metal and glass stay chunk-baked | 230 |
| `LiquidRenderer` | Shared translucent liquid drawing for tank gauges and open basins | 29 |
| `CoolingTowerBasinRenderer` | A tower basin's water surface at its published opacity ([cooling-towers.md](cooling-towers.md)) | 51 |
| `InstrumentRenderer` | Gauge dials, transmitters, wiring (`icheme:rendertype_wiring` on the shader path), placement ghosts and the one-line hover; watches the nearest gauges; `identities` ([instrument-accessories.md](instrument-accessories.md)) | 705 |
| `NameplateRenderer` | A named structure's plate where `Nameplate.of` puts it (worked out once per shape, off the render thread) and a one-block device's tag (`Nameplate.tag`), within `RANGE`, on the side it faces | 138 |
| `DataControlRenderer` | Data and power cable glands, leads and rack cables (structure only, no readings), in view; ports gathered every `HOST_TICKS` | 413 |
| **Owned by other chapters** | | |
| `MoverSounds`, `DriverSounds` | The movers' and drivers' running loops through the one sound budget ([effects.md](effects.md)); a casing's loop by kind (`MoverSounds.casingLoop`: the plunger pump's own `plunger_pump_running`, authored at 300 rpm; a compressing mover's `compressor_running`; else `pump_running`; test `MoverSoundsTest`) | 126, 135 |
| `ValveTurns`, `ValveTurnRenderer` | A hand valve's turn and its drawing ([effects.md](effects.md)) | 138, 164 |
| `PipeLayingClient`, `CableLayingClient`, `LineToolSession`, `LayingToolAim`, `LineToolMarkers` | The laying tools' client ([pipe-laying.md](pipe-laying.md)) | 411, 602, 365, 150, 80 |
| `AssemblyToolClient`, `PortColours` | The laying tool's view of column assemblies and the port colours ([assembly-service.md](assembly-service.md)) | 157, 55 |

## Main flow

1. A shell cell arrives as a block update with its update tag; `EquipmentShellBlockEntity` hands its key as model data ([equipment-structure.md](equipment-structure.md)); `EquipmentShellModel` returns the cached `Layers` for the key (empty without model data), and only cells holding glass ask for the translucent pass.
2. A pipe's update tag carries its bore, faces, transition offsets and the line's paints; a late data packet re-requests the model data and rebuilds the section. A fitting is part of the pipe's block state, so fitting, toggling or removing one re-meshes the section.
3. Each frame after solid blocks: `MachineAnimation` draws moving parts of units within range from their cached buffers; `EquipmentBodyRenderer` draws coarse bodies by region; `EquipmentReflectionRenderer` adds the reflections by region; `TankRenderer`, `SightGlassRenderer`, `InstrumentRenderer`, `NameplateRenderer`, `DataControlRenderer` draw what is in view, in detail tiers by `RenderLod`.
4. Once a second each watching renderer asks the server to watch its nearest devices and release the rest; the replies (`accept`) come on the device's bucket. Logout clears the caches (`IChemEClient`).
5. While a constructor is loaded, `EquipmentGhost` draws its design until the vessel stands, and marks the cells it cannot take.

## Contracts

- **Shell cells are chunk-meshed** (`RenderType.solid()`), never by a block-entity renderer. No cell reads another cell or chunk.
- **Moving facets** are always left out of the chunk mesh; `MachineAnimation` draws them, at rest while a mover is stopped or the datasheet's switch is off. The switch re-meshes no chunk, holds for the whole client and is not saved.
- **Paint**: a painted quad's tint index is its `Paint` channel; `EquipmentClient` scales the colour so the neutral sprite shows the paint itself (`Paint.tint`). A laid line's pipe and flanges take its two paints through the tint (`EquipmentShellModel.lineTints`); bare steel draws on the old sprites.
- **Outline**: the selection box of any shell or interior cell is the whole vessel's silhouette from the camera plus fixed edges (`ShapeRasterizer.outline`).
- **Glass** (a tank gauge's sleeve) bakes in the translucent pass; the liquid bands behind it are drawn after block entities by `TankRenderer.renderGlass`. A sight glass's metal and glass are baked; the renderer adds only liquid and bubbles.
- **Sight glass bubbles.** The sample carries the gas share, the direction and the speed (m/s, 0.02 steps). `SightGlassGeometry` (Minecraft-free) turns them into motion: bubble speed = liquid speed + 0.25 m/s of rise on a vertical run, a window crossing held between 6 s and 0.4 s; at most 8 bubbles, each of fixed size, crossing edge to edge; a horizontal run seen from the side keeps them high at low speed; stretch 1 to 2.5 with speed. `Travel` integrates the distance in game ticks, so a new speed never makes a bubble jump and a paused game stops them; the look eases over 20 ticks. Bubbles draw only in LOD tier 0, after the liquid's batch on the shader path (a second render type from the shared buffer would end the liquid's batch).
- **Flow arrows** on every kind's IN and OUT nozzle necks are baked with the shell (`PortMarkings.surface`); no renderer draws them.
- **Reflections** (batch `2026-11-18-equipment-reflections`): while `EquipmentReflectionRenderer.active()`, shells bake REFLECTED (shade, no sheen) and the pass adds a view-dependent sky, ground and sun on the materials of `ReflectionClass`; moving parts, nozzles and laid lines keep their baked look. A change of `reflections`, `mode` or a strength crossing 0 re-meshes once (`renderConfigReloaded`, `variantChanged`); a draw failure opens the pass's guard and re-meshes once with the sheen.
- **Relief sprites, two variants** (decision X5 A; `2026-11-15-equipment-visual-design`, `RELIEF_SPRITES_PLAN.md`): detail too small for polygons (nuts, louvres, cladding straps) is relief in a sprite (`tools/pbr-textures/equipment_textures.py`). A shell cell bakes BAKED (the relief lit into the albedo, today's shade and sheen in the vertex colour) unless a shader pack is in use and `pbr_textures` is on; then FLAT: the `<material>_flat` sprite of the shell model JSON (the baked one where none), vertex colour 255 and `setShade(true)`, so the pack lights it from the flat sprite's `_n` and `_s`. The variant is read once per cell bake and keys the quad cache. A baked sprite has no `_n`. The line models (`FluidDeviceModel`, `PipeRackModel`, ...) bake only BAKED. `EquipmentClient.renderConfigReloaded` re-meshes chunks when a `pbr_textures` change moves the variant; a shader pack toggle relies on Iris re-meshing the chunks (it changes the chunk vertex format), else F3+T.
- **Nuts** below `Mesh.SOLID_NUT` (60 mm across their flats) are one flush `BOLT_FACE` hexagon 0.4 mm proud at every detail (`Mesh.reliefNut`) on an equipment's own joints (covers, channels, casing joints, manways); pipe flanges keep prisms at normal detail: laid lines and fittings (`RELIEF_NUTS_ON_LINES` false) and nozzle flanges (`Mesh.flangeBolts`, `flangeNut`). Larger nuts are prisms everywhere at normal detail.
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
| `client/model/ShellVariantTest` | The shell variant's choice (REFLECTED included), its cache key and the flat sprites' fallback |
| `client/model/ReflectionClassTest`, `ReflectionLawTest`, `ReflectionDepthTest`, `ReflectionMeshTest` | The gloss table and the LabPBR maps; the law against hand numbers; the depth pull on a float rasterization of both routes; the pass's mesh content and draw calls |
| `client/fx/EquipmentTexturesTest` | The relief sprites: sizes, LabPBR channels, no `_n` beside a baked sprite, baked mean colours kept, painted sprites neutral, the model's flat keys |
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

**Snapshot:** `410c37fcb`, 2026-11-16; reflection pass rows 2026-11-18 (branch `claude/reflection-pass-20817f`).
