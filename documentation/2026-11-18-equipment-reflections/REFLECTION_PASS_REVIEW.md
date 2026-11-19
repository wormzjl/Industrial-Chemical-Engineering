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
| `sight_glass` | a sight glass's bubbles, round, clipped to the glass (section 12) | `sight_glass_shader` (true) | elliptic rings on `entityTranslucentCull`, shrunk at the rim |

- `mode = OFF`: no flames, smoke, plumes or haze; wiring flat; reflections off (sheen). `COMPATIBLE`: every program on
  its fallback.
- A shader that failed to load now demotes only its own effect (before: any failure put every effect on COMPATIBLE).
  `FxPath.of(kind)` gives the frame's path per kind; `FxBatch` draws a demoted kind's run on the compatible path.
- Without a restart: all six switches, strength and range on the next frame; `reflections`, `mode` and a strength
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

## 12. Part 3: sight glass bubbles carried by the flow

Owner, 2026-11-18: "Improve sight glass bubble animation, it should be moving with the fluid direction. Use shader as
well if necessary".

**Expected behaviour.** Gas bubbles of a few millimetres are carried at about the liquid's speed and rise through it at
about 0.25 m/s. In a horizontal run at low speed the gas rides the top of the pipe (stratified); at high speed it is
dispersed across it. In a vertical run the bubbles spread evenly; flowing up they go faster than the liquid, flowing
down slower, and in slow downward liquid they still rise. Fast bubbles look stretched along the flow.

**Before.** Bubbles grew and shrank in place along a fixed path at a fixed period (65 to 120 ticks), whatever the speed:
only the direction followed the flow.

**The sample (server).** `PipePresentation.SightGlass` gains `speed`, m/s: the `velocityMetresPerSecond` of the very
connection `flowDirection` picks (`PipePresentation.flow` returns both; `flowDirection` reads it), 0 with no direction.
Display data only, on the existing publication. On the wire: one unsigned byte after the direction, in steps of
0.02 m/s, 0 to 5.1 m/s (faster reads 5.1; the fastest crossing is reached at 0.25 m/s in a 0.1 m window and 0.75 m/s in
a 0.3 m one). The record holds the speed already rounded, so a sample equals its round trip. (My first note to the
parent said 0.05 m/s steps to 12.75 m/s; 0.02 m/s keeps the slow end, where the crossing time changes most.)
**No protocol number.** The payload changed without a number by the owner's rule for display-only data ("these kind
of visual changes doesn't need a number"): `NetworkProtocol#VERSION` stays `icheme-7`, nothing is reserved, and
FORMATS.md is unchanged (its sight glass row names the registrar, not the bytes).

**The law (`SightGlassGeometry`, Minecraft-free).** Bubble speed along the flow
v = max(0, u) + 0.25·rise (rise +1 up, -1 down, 0 horizontal), held between a crossing of the window diameter D in
6 s (in the rise's direction where flow and rise cancel) and one in 0.4 s. Crossing times, s (negative: rising against
the flow):

| Window | Run | 0.02 m/s | 0.2 m/s | 1 m/s | 3 m/s |
|---|---|---|---|---|---|
| 0.1 m | horizontal | 5.0 | 0.50 | 0.40 | 0.40 |
| 0.3 m | horizontal | 6.0 (floor) | 1.50 | 0.40 | 0.40 |
| 0.1 m | vertical up | 0.40 | 0.40 | 0.40 | 0.40 |
| 0.3 m | vertical up | 1.11 | 0.67 | 0.40 | 0.40 |
| 0.1 m | vertical down | -0.43 | -2.0 | 0.40 | 0.40 |
| 0.3 m | vertical down | -1.30 | -6.0 | 0.40 | 0.40 |

In a 0.1 m window every speed from 0.25 m/s up reads the same: the 0.4 s clamp of the brief. Each bubble keeps its
radius (0.08 to 0.14 window radii), enters fully outside one edge and leaves past the other, then waits a gap of its
own (0 to 1.6 radii) before it comes again. Across the flow: in a horizontal run seen from the side, from 0.45 radii
above the axis to the top at the slowest crossing, from -0.40 at the fastest (stratified to dispersed); otherwise even
across the window. Stretch along the flow 1 at the slowest crossing to 2.5 at the fastest, linear in 1/T. Count from the
gas share, at most 8, as before.

**Phase.** Integration, not a blend: `SightGlassGeometry.Travel` holds the distance travelled at a game tick and the
speed since; a new speed continues from where the bubbles are (`retarget`), so no bubble jumps. A blend of two phases
would slide every bubble backwards or forwards during the blend. Each cycle's path length is fixed (it uses the
longest stretch), so a new speed never rescales the phase. Only the look (stretch, the stratified band) eases over 20
ticks. Game ticks plus the partial tick: a paused game stops it. A first sight starts as if the glass had always run at
its speed, so two clients that see the same sample draw the same bubbles; after a speed step each continues its own.
A reversed direction starts again.

**Drawing.** Stage unchanged (AFTER_BLOCK_ENTITIES); bubbles only in LOD tier 0, as before.
- SHADER path, `icheme:sight_glass` (`NEW_ENTITY`): one quad per bubble and window, at most 8 per window, drawn after
  the liquid's batch (a second render type from the shared buffer would end the liquid's mid-way). UV0 the offset in
  the bubble's radius, UV1 the window position ×10000, the raw normal the flow and the stretch. The fragment discards
  outside the ellipse and outside the round glass (0.97 radii, soft over 0.02); a soft pale rim, a white highlight up
  and to the left in the window's axes, 10 % of the rim colour through the centre so the liquid shows. Translucent
  blend, depth test, no depth write, both faces, lightmap and fog.
- Compatible path (switch off, COMPATIBLE, OFF, a pack, a failed compile): today's 12-segment rings, now elliptic along
  the flow and moved by the same law, shrunk only within their own extent of the rim (`fallbackScale`).
- Found in passing and fixed: the rear window's rings were wound clockwise (its mirrored x), so the cull state of
  `entityTranslucentCull` dropped them seen from outside; the rear window now emits its corners in the reverse order.
  Not checked in a client.
- Switch: `sight_glass_shader` in `[effects]`, default true (table of section 8).

**Tests.** New `SightGlassMotionTest` (equipmentGates): the crossing table above; the floor, the clamp and the rise
bound for 4 diameters × 3 runs × 0–5 m/s; constant radius and edge-to-edge crossing; the stratified band slow and fast
and the vertical spread; stretch 1 to 2.5; a 0.2 → 2 m/s step keeps every bubble within 0.01 radii and eases the look
in one second; fallback rings inside the glass for 3 diameters, 4 speeds, both bands. `PipePresentationTest`: the round
trip with speeds 0, 0.02, 1.5, 5.1 in every direction, the speed byte's rounding and clamp, a speed with no direction
rejected; the sample's speed is the picked connection's (the fastest mapped one, reversed, not an unmapped faster one);
the six directions through the three window axes on the new law (both windows agree, each bubble advances along the
run); fallback containment. `FxShaderProgramTest`: the new program, key and shipped shader. Runs:
- `affected.mjs select --since cdff6f8f2` (the parts 1–2 commit): 183 `test`, 5 `holdGates`, 2 `equipmentGates`
  classes (`PipePresentation` reaches most of `runtime/fluid`). Run once: 1045 tests with 1 failure and 18 with 2,
  all three in the new assertions (a zero-size ring hidden outside the glass, a stretch case not at the 6 s floor);
  hold gates 87 tests, 0 failures.
- After the fix: the two fixed classes and the 32 classes that read the shader folder (its `.fsh` changed during the
  first run): 33 + 1 classes, 244 + 7 tests, 0 failures.

**Frame strip.** `tools/sight-glass-frames/` (production law; the shading a stated copy of the GLSL):
`tmp/sight-glass-frames/sheet.png`, a 0.1 m window at gas share 200/255, horizontal 0.05, 0.5 and 2 m/s, vertical up
and down 0.5 m/s, 8 frames 2 ticks apart. It shows the slow horizontal bubbles in the top half and round, the fast ones
stretched and lower, the vertical ones stretched upright; the 0.5 and 2 m/s rows are the same (the clamp).

**Not verified.** No client: the GLSL is compiled by no test; the highlight's size, the rim's softness and the depth
against the baked glass are unseen.

## 13. Part 4: a grey gloss, not a mirror; structure pipe runs left plain

Owner, from a client on 0.35.2: a large column "looks really weird", "looks better from above but very weird from below",
"too shiny"; the fired heater's crossover the same; "platforms are too shiny as well"; the crossover "needs to be in-line
with pipes" (choice: structure pipe runs out of the pass); then "it doesn't need to reflect color" and "doesn't need to be
real reflection".

**What the code did.** The pass added `sky(R) F tint` on top of the full base. Metal had F0 0.55: a clad wall seen 40
degrees from below got 0.62 + 0.49 = 1.11 in blue, clamped white-blue; from above it mirrored the darker ground. The
jacketing was the sharpest class (blur 0.18, highlight 120 / 1.6): the flashing rings' tops showed as white lines and the
head and the heater's crossovers carried a hard sun spot. Grating was bare steel (F0 0.55, 60 / 1.6). Pipe runs that are
part of a structure (a heater's crossover, a manifold, an overhead) took the pass while laid pipes beside them do not.

**Change (the final form).**
- Law (`ReflectionLaw`): a grey sheen, `added = (surround F + highlight) x sky light x strength x fade`, the same in every
  channel; `surround` is the luma of the fog colour (0.947 for the preview horizon; it follows time of day and weather);
  `kept = 1 - F x the same factors`. The shader writes `F x scale` as alpha; blend ONE, ONE_MINUS_SRC_ALPHA on colour,
  ZERO, ONE on alpha. Removed: the sky and ground gradient (`sky`, `ZENITH`, `NEAR_GROUND`, `GROUND`, the powers), the
  per-vertex reflection colour (`ReflectionMesh.tints`, `ReflectionClass.tint`, `tinted`, the measured albedos), the paint
  in the mesh cache key, and the shader's `Zenith`, `NearGround`, `Ground`, `Powers` uniforms.
- Table: PAINT 0.05, 120 / 0.9 (unchanged); STEEL 0.20, 60 / 0.8; CLADDING 0.20, 30 / 0.5; new GALVANISED (GRATING)
  0.10, 15 / 0.2. A larger share greys dark metal (a nut, base 0.26, would rise to 0.58 at F0 0.55) and washes out a light
  jacket. `blur` stays only for the LabPBR maps; GALVANISED's 0.50 gives smoothness 75 (`equipment_grating_flat_s.png`
  regenerated, the only map that differed; F0 id 230, LabPBR has no zinc).
- Pipe runs: `Mesh.Facet.run`, set by `ProcessUnit.line`, `pipe`, `elbow` and `reducer`; `ShapeRasterizer.renderBody(shape,
  detail, false)` leaves them out and `ReflectionMesh.polygons` uses it, so they show the plain shade as laid pipes do.
- Numbers: a clad wall (base 0.615) seen 40 degrees from below or above: 0.615 x 0.799 + 0.947 x 0.201 = 0.682, the
  same both ways (`ReflectionLawTest.theSheenIsTheSameFromAboveAndBelow`); paint head-on gains 0.047.
- `tools/reflection-render`: the composite `base x kept + added`; a 4 m, 30-tray column; the second view from 25 degrees
  below; pipe runs drawn without the pass. The pass panels now sit close to the plain shade with a soft grey rim.

**Not verified.** No client look yet; the GLSL is compiled by no test. Tests: the selection (`affected.mjs select`) runs
once the look is accepted.
