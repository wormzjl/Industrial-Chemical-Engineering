# Equipment reflection pass and per-shader switches: review

Batch `2026-11-18-equipment-reflections`, branch `claude/reflection-pass-20817f`, base `25a692b43` (main 0.35.1).
Owner, 2026-11-18: "Improve equipment surface effect, need some better metal reflections", "use shaders if
necessary", then from a software preview: "Build B, and make every custom shader in the mod configurable". Design by
the main thread; built and tested here. **Not seen in a client**: only a dev-client look can judge it.

## 1. Expected behaviour (physics, simplified)

A painted vessel is a dielectric clear coat over colour: it mirrors its surroundings weakly head-on and strongly at
grazing angles (Schlick: F = F0 + (1 - F0)(1 - N.V)^5, F0 = 0.05), white. Bare steel and metal jacketing are metals:
F0 about 0.55, the mirror image tinted by the metal. Outdoors the surroundings are the sky above the horizon and the
ground below it; the sun adds a highlight round its mirror direction; roughness widens it and blurs the horizon. At night
and in rain the sky darkens and the sun goes; under a roof there is no sky to mirror.

Numbers (the law's preview sky): paint head-on gains 0.675 x 0.05 = 0.034 (3 %) of full white, at 60 degrees from
the normal F = 0.08, at 85 degrees F = 0.66; bare pipe (grey 161) head-on gains 0.675 x 0.55 x 0.63 = 0.23.

## 2. The law and the table (one source)

`client/model/ReflectionClass` (the table) and `ReflectionLaw` (the law), no Minecraft class. The shader computes the
same expressions; every constant reaches it from Java (per vertex: F0, blur, power, strength, tint; per frame as
uniforms: sky colours, ground, powers, light, highlight sky range, fade, pull). The software renders and the LabPBR maps
read the same table.

| Class | Materials | F0 | Blur | Highlight n, s | Reflection colour | LabPBR R / G |
|---|---|---|---|---|---|---|
| PAINT | SHELL, HEAD, SUPPORT, HEATER, MACHINE, MOTOR, AC_HEADER, PUMP_CASTING, PUMP_VOLUTE, PUMP_FINS, PUMP_JOINT, MOTOR_FRAME, MOTOR_PLAIN, HANDRAIL | 0.05 | 0.30 | 120, 0.9 | white | 115 / 13 |
| STEEL | PIPE, FLANGE, BOLT, BOLT_FACE, SHAFT, STAINLESS, CARBON_STEEL, GRATING | 0.55 | 0.35 | 60, 1.6 | the sprite's mean (measured) | 104 / 230 (iron) |
| CLADDING | CLADDING | 0.55 | 0.18 | 120, 1.6 | the body paint | relief maps kept (232 aluminium) |

- `R = reflect(-V, N)`, smooth vertex normal, V per fragment. `sky(R, blur)`: above, the horizon (in game the fog
  colour) to the zenith (the level's sky colour) by `R.y^0.45`; below, near ground (0.42, 0.40, 0.36) to ground (0.25,
  0.23, 0.20) by `(-R.y)^0.5`, times the sky darkening (`getSkyDarken`, 0.2 to 1); joined by `smoothstep(-blur, blur, R.y)`.
- Highlight `max(R.L, 0)^n s`; L from the celestial angle, vanilla's sun at `(-sin a, cos a, 0)`; the moon opposite
  at 0.08 while the sun is down; eased in over the first 0.15 of height; times `1 - rain`; only where the sky light is
  over 10/15 (smoothstep 10/15 to 14/15).
- Output ADDED: `(sky F tint + highlight) x sky light x reflection_strength x range fade x (1 - fog share)`.
- **Difference from the preview:** additive cannot darken the base by `1 - F`. The base keeps up to `F` more than a full
  shade: 5 % for paint head-on, rising to the grazing rims (where the framebuffer clamps anyway).

Constants changed against the brief, with reasons:
- HANDRAIL is PAINT, not STEEL: its sprite is safety yellow (mean 214, 172, 36); a metal reflection tinted that yellow
  at F0 0.55 reads as gold.
- CLADDING's highlight n = 120 (the brief gave only paint and steel): its blur is half the steel's, and a lobe's width
  goes as `1/sqrt(n)`, so it takes the sharper of the two powers given; s = 1.6 as a metal.
- The highlight is not tinted by the metal (as the preview). Tinting it is physically right for metal and would darken it
  on the black nuts (66, 68, 72); owner option O4.

## 3. How it draws

`client/EquipmentReflectionRenderer`, at `AFTER_SOLID_BLOCKS` (the body renderer's and the moving parts' event):

- Structures within `reflection_range` + 48 blocks are grouped by 32-block square of their origin
  (`ReflectionMesh.region`, the body renderer's squares). One static `VertexBuffer` per square, merged again (one a
  frame, nearest in view first) when a member, its paint or its top sky light changes; squares beyond the range are
  closed. A square draws when it is in the frustum, its nearest point is inside the range and its largest member covers
  at least 8 px (`RenderLod`).
- Mesh: `ReflectionMesh`, the drawn body's fixed facets of a class (`ShapeRasterizer.renderBody`: no glass, no moving
  parts, no nozzles), tiled by their sprite period, in DefaultVertexFormat.BLOCK repacked: Color = tint and the sky light
  read once per block just outside the surface, UV0 = (F0, blur), UV2 = (n, 100 s), Normal. No texture and no relief
  normal map: the relief sprites' detail is a few millimetres and would need a sampler, tangents and atlas UVs for a
  change below a pixel beyond a few blocks; left out.
- State: blend ONE, ONE; depth test LEQUAL, depth write off; back faces culled as the shells are; no new target, no
  screen copy. Fog: the reflection fades by the share `linear_fog` mixes the terrain towards the fog colour. Range fade:
  full to 75 % of the range, nothing at it, per fragment (no pop at a square's edge).
- Shader `assets/icheme/shaders/core/equipment_reflection.{json,vsh,fsh}`, registered in `FxShaders` (BLOCK format).
  A compile failure leaves it null: the pass stays off and the shells keep the sheen. A failure inside the draw opens
  the pass's `FxGuard` (one warning) and meshes the chunks once more with the sheen.
- Iris with a pack: off (the path is COMPATIBLE); the pack lights the FLAT variant from the `_flat_s` maps, now also on
  the 21 plain sprites (section 7). Iris absent or known with no pack: on. Iris present but its bridge failed: off.
- Sodium and Embeddium without a pack: the plan's W0 notes and the body renderer and moving parts, which draw at the same
  event under Sodium today, say `AFTER_SOLID_BLOCKS` fires after their opaque terrain. Not checked in a client here.
- Distant Horizons: nothing on far silhouettes (they are DH's own boxes; the pass draws only within the range).
- Laid pipes and fittings (`FluidDeviceModel`) and nozzles: not in the pass (owner option O1).
- Moving parts (`MachineAnimation`): not in the pass. Their per-frame draw rotates the mesh, so the pass would need a
  second draw per part with a normal matrix and its own buffers: not a small change. They keep their baked sheen
  (`EquipmentShellModel.shade(polygon, corner)` still bakes it), so a turning motor fan or pump shaft looks as today.
  Owner option O2.

## 4. Depth: the pull, measured

The chunk mesh and the pass reach the depth buffer by different routes, so the same surface does not round to the same
depth. Rather than `glPolygonOffset` (its unit `r` is implementation-defined, and a fixed number of units cannot follow a
constant world-space error, which grows in depth steps as 1/z^2), the vertex shader moves each vertex towards the eye
along its view ray by `ReflectionDepth.pull(z, tan) = (8e-5 + z / (168 x 256))(1 + min(tan, 11.43)) + 2 z^2 / (0.05 x 2^24)` m:

- position rounding of both routes: Sodium 0.6 truncates chunk positions to 32/2^20 block (3.05e-5, read from
  `CompactChunkVertex` and `chunk_vertex.glsl` in the cached Sodium 0.6.13 jar) plus the float rounding of offsets, x 2;
- the rasterizer's 1/256 px vertex snap over the depth slope, at the fewest pixels per radian (480 lines, 110 degree FOV);
- one 24-bit depth step for each route (near plane 0.05).

`ReflectionDepthTest` rasterizes a plane through both routes in float as the shaders do (vanilla: float positions from
the section origin plus a float chunk offset, cut at every block; Sodium: 20-bit positions plus a float region offset;
the pass: whole facets from a 32-block corner plus a float offset), camera at (12345.7, 71.2, -9876.5), two view
directions, distances 1 to 128 m, incidence 0 to 85 degrees, 2,052 to 2,304 pixels a case:

| | Raw worst deficit (depth steps) | Pull |
|---|---|---|
| vanilla, 1 to 10 m | +35 (1 m, 85 deg), else at most +11 | 0.11 to 4.1 mm |
| Sodium, 1 to 2 m | +158 (1 m, 85 deg), +31 head-on at 1 m | 0.11 to 1.6 mm |
| both, 20 to 128 m | +1 or +2 | 1.5 to 77 mm |

With the pull, 0 failing pixels in all 180 cases. At 1 m head-on Sodium's 31 steps are 3.7e-5 m against a pull of 1.06e-4
m (2.9 times); far away one step against two. Bleed-through: what lies less than the pull behind a surface also gets
reflection, 0.26 mm at 5 m head-on, 24 mm at 96 m (a relief nut 0.4 mm proud and the shell under it both reflect: a
double add on the nut's few pixels).

## 5. Cost (counted; no timing)

Pass triangles by material (whole facets, first build; the tiled facets of the final build give the totals after the arrow):

| Drum 1.5 x 4 m | Chunk | Pass | | Pump (smallest) | Chunk | Pass |
|---|---|---|---|---|---|---|
| SHELL | 264 | 56 | | PUMP_CASTING | 782 | 750 |
| HEAD | 460 | 284 | | PUMP_VOLUTE | 140 | 120 |
| SUPPORT | 1108 | 220 (tiled 610) | | PUMP_FINS, PUMP_JOINT | 72 | 72 |
| PIPE, FLANGE | 402 | 96 | | PIPE, FLANGE | 224 | 84 |
| BOLT (nozzle flanges) | 720 | 0 | | BOLT, MACHINE (nozzles) | 304 | 0 |
| BOLT_FACE | 96 | 96 | | BOLT_FACE | 192 | 192 |
| others (no class) | 246 | 0 | | others (no class) | 266 | 0 |
| **total** | 3296 | 752 -> **1148** | | **total** | 1980 | 1218 -> **1238** |

Review plant (`CrudeUnitPlant.Layout.REVIEW`: column 2 m x 20 trays, condenser, drum, reflux pump, 3 pumps, 3 coolers):

| | Draw calls | Triangles |
|---|---|---|
| pass off | 0 | 0 |
| pass on | 3 (ten structures in three 32-block squares) | 20,160 (column 10,942) |

The chunk mesh's triangles are the same on and off (only its vertex colours change). Using `renderBody`'s tiled facets
instead of whole facets costs 32 % more triangles (15,236 whole) and keeps `ShapeRasterizer` untouched: a new method
there selected 113 more test classes, hold gates among them. A whole-facet accessor is a later saving.

## 6. The sheen: (a), with a fade

(a). The baked sheen is a fixed, view-independent band facing north and south; the pass's highlight follows the eye and
the sun. Both together would show two highlights in different places, and halving the pass (b) keeps the wrong one.
So while the pass draws, the shells bake `k = 0` (`ShellVariant.REFLECTED`) and keep their directional shade. A config
change moves the variant once: `EquipmentClient.renderConfigReloaded` re-meshes (`allChanged`), as for
`pbr_textures`; the body renderer releases its meshes on the same change; under a shader pack the variant is FLAT or
BAKED, never REFLECTED.

At the range edge a structure loses its reflection over the last quarter of the range (72 to 96 m by default) and shows
the plain shade without the sheen: on a round body facing north, at most 0.2 of brightness in the band (0.8 -> 1.0 with
the sheen), now 0.8. At 96 m a 3 m drum is about 24 px across (1080 lines, 70 degrees). Owner option O3.

## 7. LabPBR

`tools/pbr-textures/equipment_textures.py` now also writes `_flat`, `_flat_n` (flat) and `_flat_s` for the 21 plain
painted and bare steel sprites (a small extension: a stdlib PNG reader and one table). `_flat_s` R = 255(1 - sqrt(blur)),
G = 13 (paint) or 230 (iron); `ReflectionClassTest` checks every one against the table. The shell model JSON names them,
so under a pack with `pbr_textures` on the shells mirror by the pack's own reflections. The four relief sprites keep
their maps unchanged (the tool writes the same bytes). Atlas: 63 more files, 129 KB.

## 8. Every custom shader configurable

All in `[effects]` of `icheme-client.toml`; no key renamed; `mode` stays the master. Read in one place
(`ClientConfig.shader(FxShaderProgram)`), chosen by one pure function (`FxShaderProgram.path`).

| Shader | What it draws | Switch (default) | Fallback when off, failed or under a pack |
|---|---|---|---|
| `fx_flame` | flare, still and burner flames, GPU-animated | `flame_shader` (true) | `entityTranslucentEmissive` on the flame flipbook (COMPATIBLE) |
| `fx_plume` | smoke and white plumes, sun-lit | `smoke_shader` (true) | `entityTranslucentCull` on the smoke and plume sprites |
| `fx_haze` | heat shimmer over a screen copy | `heat_haze` (false, existing key) | none drawn: no compatible shimmer exists (as before) |
| `rendertype_wiring` | instrument wiring lit through its normal map | `wiring_shader` (true), with `pbr_textures` | `entityCutoutNoCull`, lit flat |
| `equipment_reflection` | sky, ground and sun mirrored on equipment | `reflections` (true), `reflection_strength` 0 to 2 (1), `reflection_range` 16 to 256 (96) | the baked sheen in the chunk mesh |

- `mode = OFF`: no flames, smoke, plumes or haze; wiring flat; reflections off (sheen). `COMPATIBLE`: every program on
  its fallback.
- A shader that failed to load now demotes only its own effect (before: any failure put every effect on COMPATIBLE).
  `FxPath.of(kind)` gives the frame's path per kind; `FxBatch` draws a demoted kind's run on the compatible path.
- Without a restart: all five switches, strength and range on the next frame; `reflections`, `mode` and a strength
  crossing 0 also re-mesh the chunks once (the sheen comes or goes). No switch needs a resource reload.
- No options screen: the mod registers no config screen (`IConfigScreenFactory`), so the keys are only in the file; no
  `en_us.json` lines were needed. Owner option O5.

## 9. Tests and runs

New: `ReflectionClassTest`, `ReflectionLawTest`, `ReflectionDepthTest`, `ReflectionMeshTest` (client/model),
`FxShaderProgramTest` (client/fx, also: every shader the mod ships has a program). Changed: `ShellVariantTest`
(REFLECTED), `EquipmentTexturesTest` (24 flat keys). Runs:
- the seven classes above: 28 tests, then `ReflectionMeshTest` again after its fix and after the move to `renderBody`; all pass;
- `affected.mjs select --since main` on the final code: 59 `test` and 5 `equipmentGates` classes; less the seven that
  had passed on what they load and `UnitTextTest` (brief): 52 + 4 classes through `record.init.gradle -PlaneTests`:
  350 + 57 tests, 0 failures.
No final lane, campaign, benchmark, timing or dev client.

Software renders (`tools/reflection-render`): `tmp/reflection-render/{drum,column,exchanger,pump}.png` and `sheet.png`,
each shape from azimuth 35 / 22 up and 215 / 8 up, today's sheen beside the pass.

## 10. Not verified

- Nothing has been seen in a client: the look, the pull against a real depth buffer, Sodium's and Embeddium's event
  order, the fog fade, a pack toggle switching the variant, the re-mesh on a config change, a shader compile on real
  drivers. The GLSL is not compiled by any test.
- Frame cost: counted only (section 5).

## 11. Owner options

- **O1 Laid pipes and fittings.** A: leave them out (built). B: add them: each pipe block is at least 8 facets
  (16 triangles a block, more at larger bores) and each flanged joint carries bolt prisms (a nozzle flange's bolts are
  240 to 720 triangles of the counts above); a plant with a few hundred pipe blocks would add roughly as many
  triangles as its structures. Nozzle necks need the cell's drawn size code, which the pass does not have.
- **O2 Moving parts.** A: keep their baked sheen (built). B: a second draw per part with a normal matrix.
- **O3 Range edge.** A: fade over the last quarter to the plain shade (built). B: keep a weaker baked sheen
  (k 0.1) beyond the range, which needs the variant by distance (a re-mesh as the player walks): not recommended.
- **O4 Metal highlight colour.** A: white (as the preview, built). B: tinted by the metal.
- **O5 Options screen.** A: keys in the file only (built). B: register NeoForge's config screen for the client config
  (every client key would appear, with `en_us.json` lines for each).
