# Equipment visual review

Date: 2026-11-15. Branch `claude/equipment-visual-design-d4e22c`, source `e9edc974b` (0.31.0).

The owner asked how to make the implemented equipment look better and said it looks bare. This review lists what to
fix, by kind, ordered by how much each fix helps. Nothing in the game changed.

## Method

- One run of `tools/equipment-drawings/update.ps1` for every kind at its reference sizes: 81 isometric views.
- Contact sheets of the views and of the 16 main sprites, in the main checkout:
  `research/2026-11-15-equipment-visual-design/review/iso-1.png` to `iso-6.png`, `sprites.png`.
- Read against the family cues in `.claude/skills/models-and-sfx/references/model-cues.md` and the skill's rules.

Limits:

- The drawing tool renders neutral grey, without the game's sprites and paint. Findings marked "sprite" need a sprite
  render or an in-game look before they are acted on.
- Only the reference sizes were drawn, at one heading. No dev client was started.

## Owner rule from this review

Both are in the `models-and-sfx` skill.

- No weld seams (2026-11-15: "these should not be visually presented").
- No glass-tube level gauge on a pressure vessel (2026-11-15, on the first sketch: "you don't put a level gauge like
  that on a pressure vessel"). The sketch drew a bare glass tube on a bridle. Asked what replaces it, the owner
  chose "No gauge": no level gauge is drawn on a pressure vessel, and the level sensor item stays the only level
  device.

## Summary

1. Most of the bareness is in the plain pressure vessels: the vertical drum, the lying drums, the exchangers and the
   kettle. They are a shell, heads, supports and nozzles, and nothing else. The tall structures, the stills, the
   spheres, the tanks, the turbines and the engines already carry platforms, ladders and appendages.
2. Three findings cover many kinds at once: no contact shading (G1), slab saddles (G3), and blank concrete plinths
   that are larger than the machine on them (G4).
3. Two findings break rules the skill already states: flat black rectangles on the large motor and generator (G5),
   and stacks as wide as their package on the gas turbines (K7).
4. `vessel_shell.png` draws plate courses with seam lines, which the new owner rule forbids (G2).

Proposed order: G5, G2, then the vessel package (K1 to K4 with G3), G1, G4, then the layout-changing items (K7, K8,
K10) after the owner approves them.

## Findings for every kind

| Id | Finding | Fix | Layout or save effect |
|---|---|---|---|
| G1 | `EquipmentShellModel` bakes directional shade and turns ambient occlusion off. Nozzles, saddles and platforms do not darken where they meet the shell. | Bake a crevice term into the vertex colour beside the shade, computed from the shape. | None. Body cells share one key, so the term cannot read neighbouring cells. |
| G2 | Sprites come in 16, 32, 64 and 128 px. `vessel_shell` draws plate courses with seams; `equipment_cladding`, `painted_cladding` and `painted_heater` draw panel joints. | One texel density. Remove the seams from `vessel_shell`. Owner to say whether cladding panel joints fall under the same rule. | None. |
| G3 | Saddles are solid slabs on `LENGTHWISE`, `LENGTHWISE_BOOT`, both exchangers and `KETTLE`. | A wear plate, a web with ribs, a base plate and a plinth, as one `ProcessUnit` part. | None if the part stays in the saddle's cells. |
| G4 | The concrete plinth is the largest part of `MOTOR_SMALL`, `GENERATOR_SMALL`, `PUMP_OH2`, `PUMP_BB2`, `DRY_SCREW_PUMP`, `LIQUID_RING_PUMP` and `CENTRIFUGAL_COMPRESSOR`, and it is a blank box. | Chamfered top edge, a baseplate drain rim, anchor bolts, a grout line. Keep the height: it sets the shaft height. | None. |
| G5 | `MOTOR_LARGE` and `GENERATOR_LARGE` show three flat black rectangles each. The skill forbids them ("no black windows"). | Louvre bars or cooler fins in those openings. | None. |
| G6 | Round bodies show 12 facets and nozzle flanges are octagons at close range. | A finer near tier in `RenderLod` for round bodies and flanges. | None. Frame time needs a performance task. |

## Findings by kind

| Id | Kind | Finding | Fix | Layout or save effect |
|---|---|---|---|---|
| K1 | `VERTICAL` | A cylinder on a skirt with small nozzles. No manway, lugs, bridle or access. | Trim: manway with davit, reinforcing pads, lifting lugs, nameplate bracket, fireproofed skirt with its opening, base ring and anchor bolts. Option: top platform and caged ladder. | Trim: none. Platform and ladder add cells. |
| K2 | `LENGTHWISE`, `LENGTHWISE_BOOT` | The same, lying down, on slab saddles. | G3 saddles, manway, pads, lugs, nameplate, vent and drain stubs. Option: a walkway on large sizes. | Trim: none. Walkway adds cells. |
| K3 | `EXCHANGER_STANDARD`, `EXCHANGER_CROSSFLOW` | One girth flange, a plain rear head, slab saddles. | Girth flanges and a tube sheet at both ends, a bolted channel cover, G3 saddles, vent and drain stubs, lugs. | None. |
| K4 | `KETTLE` | As K3; the enlarged shell and the cone are there. | As K3, plus a manway on the large shell. | None. |
| K5 | `AIR_COOLER` | A flat slab on four legs from above; the fan rings show only from below. Sprite. | Header boxes with plugs at both ends, the finned bundle visible from above, a walkway with a ladder. | Walkway adds cells. |
| K6 | `FLUID_CONVERTER`, `GAS_CONVERTER` (reference and largest) | A plain tank beside a blank tiled cube. The smallest sizes read well. | Stiffener ribs, a door, a vent cowl and cable trays on the cube; a stair or ladder and a roof rail on the tank. | Stair adds cells. |
| K7 | `GAS_TURBINE`, `GAS_TURBINE_IND`, `GAS_TURBINE_COIL` | The stack is as wide as the package. The skill calls this a silo. | A slender stack two to three times the package height on a transition piece, stiffening rings, a rim. | Changes cells. Needs the owner's approval. |
| K8 | `CABIN_HEATER`, `CABIN_HEATER_SINGLE` | The stack is about 0.4 of the box height. | A taller stack with a damper and a platform near the top. | Changes cells. Needs the owner's approval. |
| K9 | `INDUCED_DRAFT_COOLING_TOWER` | Blank casing walls. Sprite: `tower_louver` and `tower_panel` may already cover this. | Louvre band at the air inlet as geometry if the sprite render shows blank walls. | None. |
| K10 | `FLARE_STACK` | A bare pole with a small drum at its foot. | Ladder and two platforms, guy wires or a derrick, a visible tip with pilots. | Adds cells. |
| K11 | `BRICK_CHIMNEY` | A smooth cone under a platform. Sprite. | Corbel rings, a ladder with hoops, a clean-out door. | Ladder adds cells. |
| K12 | `EJECTOR` | A small body inside a heavy frame. | A larger motive nozzle chest and diffuser, a lighter frame. | Frame change moves cells. |
| K13 | `CENTRIFUGAL_COMPRESSOR` | A barrel alone on a slab. | A lube-oil console and a seal-gas panel on the baseplate. | None if inside the plinth footprint. |

## Kinds that need nothing now

- Already detailed: `COLUMN`, `SPHERE`, the tanks, `BATCH_STILL`, `STEAM_TURBINE` and its tandems, `COMBUSTION_ENGINE`,
  `COMPRESSOR`, `PUMP_OH2`, `PUMP_BB2` (apart from G4), `LIQUID_RING_PUMP`, `STIRRED_REACTOR`, `TRICKLE_BED_REACTOR`,
  `EBULLATED_BED_REACTOR`, `ADSORBER`, `CYCLONE`, `STEEL_STACK`, `VERTICAL_HEATER`, `NATURAL_DRAFT_COOLING_TOWER`.
- Left alone by the owner's rule on big structures: `FCC_CONVERTER`, `DELAYED_COKER`, the gasifiers, the boilers.
  No defect was seen on them at this scale.

## Not weld seams

The thin lines on the drums, the sphere and the exchangers in the drawings are the instrument conduits between the
sensor heads and the data socket. They are in the game today.

## Open for the owner

1. Do insulation band straps and cladding panel joints fall under the no-seams rule?
2. Trim only (K1 to K4), or access steel on drums as a constructor option that defaults to off?
3. K7, K8, K10: approve the layout changes, or leave these kinds as they are?