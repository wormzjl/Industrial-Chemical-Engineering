# Equipment reflections (2026-11-18)

Status: In progress since 2026-11-18: built and tested on branch `claude/reflection-pass-20817f` (base `25a692b43`,
main 0.35.1); not seen in a client; not merged.

Owner, 2026-11-18: "Improve equipment surface effect, need some better metal reflections", "use shaders if
necessary"; from the main thread's software preview: "Build B, and make every custom shader in the mod configurable".
B is a view-dependent reflection pass in the mod's own shader over the chunk-meshed equipment.

## History

- 2026-11-18: the pass and the switches built (commits on the branch; the main thread designed it, an Opus sub-thread
  built it). Sheen decision (a): the shells bake no sheen while the pass draws (`ShellVariant.REFLECTED`), with a fade
  over the last quarter of the range. Depth pull measured in `ReflectionDepthTest` (0 failing pixels in 180 cases).
  21 plain sprites got LabPBR `_flat` maps. Selected tests on the final code: 52 + 4 classes, 407 tests, 0 failures;
  the seven new or changed classes before them. `REFLECTION_PASS_REVIEW.md`.
- 2026-11-18, part 3 (owner: "Improve sight glass bubble animation, it should be moving with the fluid direction"): bubbles carried by the published speed with the rise on vertical runs, a stratified horizontal run, stretch with speed, phase kept by integration; the `sight_glass` shader and its switch; the speed byte on the sight glass display (protocol number pending); `SightGlassMotionTest`; `tools/sight-glass-frames/`; review section 12.

## Documents

- [REFLECTION_PASS_REVIEW.md](REFLECTION_PASS_REVIEW.md): the law and table, how it draws, the depth pull and its
  measurement, counts, the sheen decision, the shader and switch table, tests, what is not verified, owner options O1 to O5.

## Code areas

`client/EquipmentReflectionRenderer`, `client/model/ReflectionClass`, `ReflectionLaw`, `ReflectionDepth`,
`ReflectionMesh`, `ShellVariant` (REFLECTED), `EquipmentShellModel` (sheen by variant), `EquipmentBodyRenderer`;
`client/fx/FxShaderProgram`, `FxShaders`, `FxPath` (per kind), `FxBatch`; `InstrumentRenderer` (wiring switch);
`ClientConfig` (`[effects]` keys); part 3: `science/equipment/SightGlassGeometry` (the motion law), `client/SightGlassRenderer`, `runtime/fluid/PipePresentation` (`SightGlass.speed`, `flow`), `network/FluidNetwork` (the speed byte), `FxRenderTypes.SIGHT_GLASS`, `assets/icheme/shaders/core/sight_glass.*`, `tools/sight-glass-frames/`; `assets/icheme/shaders/core/equipment_reflection.*`; the shell model JSON's flat keys
and 63 sprite files; `tools/pbr-textures/equipment_textures.py`; `tools/reflection-render/`.

## Open items

- Part 3 (sight glass bubbles): the wire protocol number. The rules give `icheme-9`, to be reserved on main; `NetworkProtocol#VERSION` stays `icheme-7` and FORMATS.md unchanged until the parent says so.
- A dev-client look at the sight glass bubbles (owner's call).

- A dev-client look (owner's call): the look in daylight, at night and in rain, near and at the range edge, with Sodium,
  with a pack (the `_flat_s` maps).
- Owner options O1 (laid pipes), O2 (moving parts), O3 (range edge), O4 (metal highlight colour), O5 (options screen).
- A whole-facet accessor in `ShapeRasterizer` would cut the pass's triangles by about a quarter (section 5).
