# Distance-based level of detail (LOD) for equipment and instruments

- Status: Planned 2026-11-09. Read from `main` `b794e2d41` (0.22.0). Owner decisions L1–L4 open. No code changed.
- Owner request 2026-11-09: reduce rendering stress, maybe with low-poly models and no instruments beyond a distance.

## 1. Expected behaviour, in pixels

A feature of size s at distance d covers k·s/d pixels; k = H / (2 tan(FOV/2)) = 771 px at 1080p and FOV 70°. A detail that covers less than 1 px buys nothing.

| Detail | Size | 1 px at | Readable (6 px) at |
|---|---|---|---|
| Dial scale labels and unit label (glyph line 9 × 0.0025 m) | 0.0225 m | 17 m | 2.9 m |
| Sensor ID text (9 × 0.0013 m) | 0.0117 m | 9 m | 1.5 m |
| Sensor symbol P/T/F/L (9 × 0.011 m) | 0.099 m | 76 m | 13 m |
| Dial face (2 × `RADIUS` 0.17 m); 8 px at 33 m, 64 px at 4 m | 0.34 m | 262 m | — |
| Flange nut, handrail tube | ~0.05 m | 39 m | — |
| D 6 m shell outline error, sagitta R(1 − cos π/n): normal n ≈ 57 / LOW 24 / 12 / 8 sides | 0.005 / 0.026 / 0.10 / 0.23 m | 3.5 / 20 / 79 / 176 m | — |

So past about 40 m nuts, rails and every text are below a pixel, and a 12-sided D 6 m shell looks the same as the normal one past about 80 m. A static chunk mesh costs no CPU per frame: the proof of concept measured 20 columns 6 × 40 m at 0 ms (vanilla, Sodium) and +0.06 ms (Iris), with the frame waiting on the buffer swap (`2026-10-15-custom-equipment-rendering/CUSTOM_EQUIPMENT_POC_REVIEW.md`). The per-frame renderers cost CPU on every frame for every object in range, seen or not.

## 2. What the code does

| Renderer | Range | Frustum test | Work per frame |
|---|---|---|---|
| `InstrumentRenderer` | 120 m, every host | **none** | Per pipe dial: 261 housing + 54–126 connector + 1 needle quads (~1,400 vertices) and 6 text strings (~25 glyphs). Per transmitter: 35 quads and 2 strings. `quad()` calls `LevelRenderer.getLightColor` for each vertex (4 per quad, same point) and makes 4 `Vec3`s. Equipment wiring: `instrumentWiring` lookup and `putBulkData` per host. `InstrumentHosts.nearby` rebuilds every host and its mount lists. |
| `DataControlRenderer` | 96 m ports, racks | none | Immediate-mode glands, leads and rack cables |
| `SightGlassRenderer` | 128 m | yes | Immediate-mode liquid and bubbles |
| `MachineAnimation` | 96 m | yes | One VBO draw per moving part; key holds the light; at 256 buffers `release()` drops all of them |
| `EquipmentBodyRenderer` (LOW mode, cooling towers) | unlimited | yes | One VBO per structure; LRU of 64 keyed with the light: with more than 64 structures in view, a mesh is closed and rebuilt on the render thread every frame |
| `TankRenderer` 192 m, `NameplateRenderer` 32 m | — | yes | VBO roof, plates |
| Chunk mesh (NORMAL) | render distance | chunk culling | None per frame. `low_polygon_mode` is one global switch with a resource reload; no tier depends on distance. |

Estimate, not measured: 100 pipe dials in range make about 140,000 light lookups per frame, 10–20 ms at 70–150 ns each. That alone holds a 144 FPS client near 40 FPS. The instruments, not the polygons, are the likely stress.

## 3. Proposal

**Stage A: per-frame renderers (no visible change near the player).**
- A1. One client helper `RenderLod`: k from the window height and FOV each frame; tier of an object from its screen size; 15 % hysteresis per object; client config `lod_scale` (default 1.0, 0.25–4) multiplies every threshold. It replaces each renderer's own `RANGE` constant.
- A2. Instruments: frustum test per host. Dial tiers by face size on screen: ≥ 48 px full; 12–48 px housing and needle, no text, no connectors; 3–12 px a 12-sided disc with its needle (~16 quads); < 3 px not drawn. Transmitters: symbol text to 13 m, ID to 4 m. One light lookup per instrument. Equipment wiring cached per host while its install set holds. Host list rebuilt on the 20-tick watch, not per frame.
- A3. Cables, ports and sight glasses: frustum test and the same screen-size cull.
- A4. Caches: evict only meshes not drawn in the last second (body, moving parts), so a full view never rebuilds every frame.

**Stage B: equipment bodies (decision L2).** A `RenderDetail.FAR` tier: 12 sides, 4 head bands, no nuts, rails, rungs or grating (plates instead). Drawing only, as `RenderDetail` is now.
- B1. Chunk near, whole mesh far. Near stays as today (per-block light, AO, shader-pack shadows). Past the switch distance a client flag in the cell's `ModelData` empties the body from the chunk model and `EquipmentBodyRenderer` draws LOW or FAR. Cost: one section re-mesh per structure at each crossing (cached quads; hysteresis keeps crossings rare). Risk: a few frames drawn twice at a crossing; the `ModelData` path under Sodium needs a check.
- B2. Whole mesh at every distance (LOW mode's split, extended to NORMAL). No re-mesh. Near loses per-block light and AO (one light sample per structure, as LOW and the towers have now). Whole-mesh draws are probably absent from the Iris shadow pass (to check).
- B3. No body LOD: keep `low_polygon_mode` as it is.
- Recommended: A first, then measure; B1 only if that measurement shows vertex-bound frames. Nozzles and markings stay in the chunk mesh in every option.

## 4. Verification

- Gates: `RenderLodTest` (tiers, hysteresis, `lod_scale`); for FAR, the existing envelope and watertight checks of `ShapeRasterizer` (inside the physical envelope, finite UVs, ports unchanged). Placement, collision, layout stamps, packets and saves are untouched: no corpus image, no format change.
- Counters offline: quads and light lookups per frame for a fixed scene, before and after A.
- One FPS pair, started by the owner (AGENTS.md): a refinery scene (10 columns, 40 drums, 100 dials), vanilla and Sodium lanes, 60 s warm-up + 60 s, before and after A, with a 30 s JFR sample of the render thread (`tools/custom-equipment-rendering-poc/` FPS lane).

## 5. Owner decisions

- L1. Stage A tiers and `lod_scale` as above.
- L2. Body route: B1, B2 or B3.
- L3. What FAR drops (list above), and whether LOW and FAR thresholds follow section 1 (1 px outline error).
- L4. The FPS pair: scene, lanes, and whether to run it before Stage A as a baseline.
