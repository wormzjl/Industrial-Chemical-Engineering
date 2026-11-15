# Equipment visual review

Date: 2026-11-15. Branch `claude/equipment-visual-design-d4e22c`, source `e9edc974b` (0.31.0).

The owner asked how to make the implemented equipment look better and said it looks bare. This review lists what to
fix, by kind, ordered by how much each fix helps. Nothing in the game changed.

## Method

- One run of `tools/equipment-drawings/update.ps1` for every kind at its reference sizes: 81 isometric views.
- Contact sheets of the views and of the 16 main sprites, in the main checkout:
  `research/2026-11-15-equipment-visual-design/review/iso-1.png` to `iso-6.png`, `sprites.png`.
- Polygon counts per kind and material from the run's `geometry/*.json`.
- Read against the family cues in `.claude/skills/models-and-sfx/references/model-cues.md` and the skill's rules.

Limits:

- The drawing tool renders neutral grey, without the game's sprites and paint. Findings marked "sprite" need a sprite
  render or an in-game look before they are acted on.
- Only the reference sizes were drawn, at one heading. No dev client was started.

## Owner rules and scope from this review (2026-11-15)

The rules are in the `models-and-sfx` skill.

- No weld seams ("these should not be visually presented"). Insulation band straps on cladding stay ("Keep straps").
  The owner was asked about the straps only; the panel joints already in the cladding sprites are left as they are.
- No level gauge on a pressure vessel ("you don't put a level gauge like that on a pressure vessel"; then "No
  gauge"). The level sensor item stays the only level device.
- A manway sits on the vessel's axis ("Manhole should be on the axis normally"). The two sketches drew it off the
  centreline, the second one only to make room for a label. That was wrong.
- Detail goes into normal textures, not polygons ("Take advantage of the normal texture system to implement details
  without increasing polygon too much, keep performance in mind and decrease poly when you can").
- Scope: the boilers and gasifiers belong to another work (`FLUIDIZED_BED_BOILER`, `GRATE_BOILER`,
  `FLUIDIZED_BED_GASIFIER`, `FIXED_BED_GASIFIER`). This batch leaves them out.
- Owner findings: the furnace platforms look unreal (K14); the HP and MP steam turbine casings need improvement (K15).

## Summary

1. Most of the bareness is in the plain pressure vessels: the vertical drum, the lying drums, the exchangers and the
   kettle. They are a shell, heads, supports and nozzles, and nothing else.
2. Detail is in polygons today, and most of it is small. Bolt heads are the largest material on 18 of the 61 meshes
   in scope, and more than half of the vacuum pump, kettle and centrifugal compressor meshes. Handrails are the next largest (P1, P2).
3. The normal texture system exists for the instrument wiring only (`rendertype_wiring`, LabPBR `_n` and `_s` from
   one height field, `tools/pbr-textures/`). Shell cells are chunk-meshed, where our shader cannot run. The owner's
   decision X5 A of 2026-11-10 already says how chunk sprites take relief: baked into the albedo without a shader
   pack, a flat albedo with `_n` and `_s` under a pack. No shell sprite uses it yet.
4. Two findings break rules the skill already states: flat black rectangles on the large motor and generator (G5),
   and stacks as wide as their package on the gas turbines (K7).
5. `vessel_shell.png` draws plate courses with seam lines, which the owner's rule forbids (G2).

Proposed order:

1. The relief sprite route for shells (X5 A): one height field gives the baked albedo and the `_n`, `_s` pair.
2. P1 on that route: bolts move from polygons into the flange sprites. G2 and G5 in the same pass.
3. The vessel package: K1 to K4 with G3, trim as relief sprites where it has no silhouette.
4. K15, then K14, each as a cue-by-cue redraw shown to the owner as drawings first.
5. G1, G4, P2 to P4.
6. The layout-changing items (K7, K8, K10) after the owner approves them.

## Detail in normal textures, fewer polygons

Counts are polygons at the reference size, normal detail.

| Id | Finding | Counts | Fix | Risk |
|---|---|---|---|---|
| P1 | Every bolt head is a polygon solid. | `CABIN_HEATER` 4,318 of 12,118; `VERTICAL_HEATER` 2,908 of 10,094; `EBULLATED_BED_REACTOR` 2,588; `COLUMN` 1,920; `LIQUID_RING_PUMP` 920 of 1,544; `CENTRIFUGAL_COMPRESSOR` 888 of 1,537; `DRY_SCREW_PUMP` 824 of 1,325; `PUMP_BB2` 816 of 1,786; both exchangers 768 of 1,940; `KETTLE` 576 of 1,057 | A bolt circle in the flange sprite's relief. Keep polygon bolts only where they make the silhouette at close range. | Bolts lose their outline edge-on. |
| P2 | Handrails, cages and ladders are tube solids. | `FCC_CONVERTER` 4,745; `COLUMN` 4,722; `EBULLATED_BED_REACTOR` 3,840; `SPHERE` 3,456; `FLOATING_ROOF_TANK` 3,142; `VERTICAL_HEATER` 2,884; `STEEL_STACK` 2,820 of 4,022 | Fewer sides per tube, or one cut-out quad per bay. Owner to choose on drawings. | A cut-out quad is flat seen edge-on. |
| P3 | Heads and domes are tessellated finely. | `GAS_CONVERTER_LARGEST` 9,216 of 15,706; `COLUMN` 5,408; `SPHERE` 2,888; drums 1,352 | Fewer rings on large heads; the smooth normals already hide the facets. | The silhouette shows facets if cut too far. |
| P4 | Fins, panel frames and ribs are polygons. | `GAS_CONVERTER_LARGEST` steelwork 4,686; `COMBUSTION_ENGINE_LARGEST` 3,244; `GENERATOR_TURBO` 2,116; `AIR_COOLER` rings 1,032 | Relief sprites for fins, louvres and panel frames. | None seen. |
| P5 | New trim would add polygons. | none yet | Band straps, reinforcing pads, nameplates and anchor bolts as relief in the sprite. Polygons only for parts with a silhouette: manway, davit, lugs, saddle ribs. | None. |

## Findings for every kind

| Id | Finding | Fix | Layout or save effect |
|---|---|---|---|
| G1 | `EquipmentShellModel` bakes directional shade and turns ambient occlusion off. Nozzles, saddles and platforms do not darken where they meet the shell. | Bake a crevice term into the vertex colour beside the shade, computed from the shape. No polygons added. | None. Body cells share one key, so the term cannot read neighbouring cells. |
| G2 | Sprites come in 16, 32, 64 and 128 px. `vessel_shell` draws plate courses with seams. | One texel density. Remove the seams from `vessel_shell`. Cladding keeps its panel joints and gets band straps. | None. |
| G3 | Saddles are solid slabs on `LENGTHWISE`, `LENGTHWISE_BOOT`, both exchangers and `KETTLE`. | A wear plate, a web with ribs, a base plate and a plinth, as one `ProcessUnit` part. | None if the part stays in the saddle's cells. |
| G4 | The concrete plinth is the largest part of `MOTOR_SMALL`, `GENERATOR_SMALL`, `PUMP_OH2`, `PUMP_BB2`, `DRY_SCREW_PUMP`, `LIQUID_RING_PUMP` and `CENTRIFUGAL_COMPRESSOR`, and it is a blank box. | A chamfered top edge as geometry; the drain rim, anchor bolts and grout line as relief. Keep the height: it sets the shaft height. | None. |
| G5 | `MOTOR_LARGE` and `GENERATOR_LARGE` show three flat black rectangles each. The skill forbids them ("no black windows"). | Louvre bars or cooler fins as a relief sprite on those faces. | None. |

G6 of the first version (a finer near tier for round bodies) is withdrawn: it adds polygons.

## Findings by kind

| Id | Kind | Finding | Fix | Layout or save effect |
|---|---|---|---|---|
| K1 | `VERTICAL` | A cylinder on a skirt with small nozzles. No manway, lugs or access. | Trim: a manway with davit on the centreline, lifting lugs, a fireproofed skirt with its opening; pads, nameplate, straps and anchor bolts as relief. Option: top platform and caged ladder. | Trim: none. Platform and ladder add cells. |
| K2 | `LENGTHWISE`, `LENGTHWISE_BOOT` | The same, lying down, on slab saddles. | G3 saddles, a manway on the centreline, lugs, vent and drain stubs; the rest as relief. Option: a walkway on large sizes. | Trim: none. Walkway adds cells. |
| K3 | `EXCHANGER_STANDARD`, `EXCHANGER_CROSSFLOW` | One girth flange, a plain rear head, slab saddles. | Girth flanges and a tube sheet at both ends, a channel cover with its bolts in relief (P1), G3 saddles, vent and drain stubs, lugs. | None. |
| K4 | `KETTLE` | As K3; the enlarged shell and the cone are there. | As K3, plus a manway on the large shell's centreline. | None. |
| K5 | `AIR_COOLER` | A flat slab on four legs from above; the fan rings show only from below. Sprite. | Header boxes with plugs at both ends, the finned bundle visible from above as relief, a walkway with a ladder. | Walkway adds cells. |
| K6 | `FLUID_CONVERTER`, `GAS_CONVERTER` (reference and largest) | A plain tank beside a blank tiled cube. The smallest sizes read well. | Stiffener ribs, a door and a vent cowl on the cube as relief (P4); a stair or ladder and a roof rail on the tank. | Stair adds cells. |
| K7 | `GAS_TURBINE`, `GAS_TURBINE_IND`, `GAS_TURBINE_COIL` | The stack is as wide as the package. The skill calls this a silo. | A slender stack two to three times the package height on a transition piece, stiffening rings, a rim. | Changes cells. Needs the owner's approval. |
| K8 | `CABIN_HEATER`, `CABIN_HEATER_SINGLE` | The stack is about 0.4 of the box height. | A taller stack with a damper and a platform near the top. | Changes cells. Needs the owner's approval. |
| K9 | `INDUCED_DRAFT_COOLING_TOWER` | Blank casing walls. Sprite: `tower_louver` and `tower_panel` may already cover this. | Nothing if the sprite render shows louvres; else a louvre relief. | None. |
| K10 | `FLARE_STACK` | A bare pole with a small drum at its foot. | Ladder and two platforms, guy wires or a derrick, a visible tip with pilots. | Adds cells. |
| K11 | `BRICK_CHIMNEY` | A smooth cone under a platform. Sprite. | Corbel rings, a ladder with hoops, a clean-out door. | Ladder adds cells. |
| K12 | `EJECTOR` | A small body inside a heavy frame. | A larger motive nozzle chest and diffuser, a lighter frame. | Frame change moves cells. |
| K13 | `CENTRIFUGAL_COMPRESSOR` | A barrel alone on a slab. | A lube-oil console and a seal-gas panel on the baseplate. | None if inside the plinth footprint. |
| K14 | `CABIN_HEATER`, `CABIN_HEATER_SINGLE` (owner: "furnace platform looks unreal") | Two solid decks as wide as roofs hang off the convection section with no brackets. A third walkway runs along one long wall only. Long caged ladders rise from grade to each. Agent's reading of what is unreal; the owner has not confirmed it. | Narrow grating walkways (about 1 m) on knee brackets off the casing buttresses, at the levels a real heater is worked from: the burners, the peep doors, the convection header boxes, the stack damper. One stair or ladder run with landings at one end. | Changes cells. Needs the owner's approval. |
| K15 | `STEAM_TURBINE_TANDEM_80`, `STEAM_TURBINE_TANDEM_300` (owner: "HP MP steam turbine model need improvement") | The HP casing is a tapered barrel with one joint flange. The MP casing is a plain arch on a box with no joint flange, bolting or steam inlets. The valve rack stands off the front like a fence, with no steam leads to the casing. Bearing pedestals are plain boxes. Agent's reading; the owner has not confirmed it. | A cue-by-cue redraw of the HP and MP sections: horizontal joint flanges with relief bolting on both, the steam chest on the HP casing with leads above and below, reheat inlets with intercept valves on the MP, shaped bearing pedestals. Drawings to the owner first. Superseded in part on 2026-11-15: the owner made the turbine a combined HP+MP+LP machine at every size, with an LP class ([STEAM_TURBINE_SECTIONS_PLAN.md](STEAM_TURBINE_SECTIONS_PLAN.md)); no reheat inlets. | The plan's T4 lists what moves. |

## Kinds that need nothing now

- Already detailed (apart from P1 to P4): `COLUMN`, `SPHERE`, the tanks, `BATCH_STILL`, `STEAM_TURBINE`,
  `COMBUSTION_ENGINE`, `COMPRESSOR`, `PUMP_OH2`, `PUMP_BB2`, `LIQUID_RING_PUMP`, `STIRRED_REACTOR`,
  `TRICKLE_BED_REACTOR`, `EBULLATED_BED_REACTOR`, `ADSORBER`, `CYCLONE`, `STEEL_STACK`, `VERTICAL_HEATER`,
  `NATURAL_DRAFT_COOLING_TOWER`.
- Left alone by the owner's rule on big structures: `FCC_CONVERTER`, `DELAYED_COKER`.
- Out of this batch: the boilers and gasifiers.

## Not weld seams

The thin lines on the drums, the sphere and the exchangers in the drawings are the instrument conduits between the
sensor heads and the data socket. They are in the game today.

## Open for the owner

1. Trim only (K1 to K4), or access steel on drums as a constructor option that defaults to off?
2. K7, K8, K10, K14: approve the layout changes, or leave these kinds as they are?
3. P2: handrails with fewer sides, or cut-out quads?
4. K14 and K15: are the agent's readings of what is wrong right?
