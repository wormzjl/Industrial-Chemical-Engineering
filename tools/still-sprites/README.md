# Batch still sprites

Batch: `documentation/2026-11-09-batch-distiller/` (owner feedback, 2026-11-09: "the texture needs major improvement").

Purpose: bake the batch still's six finishes, which replace the vanilla block textures the still first used (`copper_block`,
`bricks`, `anvil`, `spruce_planks`). Each sprite tiles by the metre (`Mesh.Material` period 1), so its tiles meet at block
faces. The block shader reads no normals, so the hammer marks, lap seams, rivets and brick edges are painted in, lit from
above. The still's finishes are unpainted, so the sprites carry their own colours. The output is the same on every run (a
fixed hash, no random numbers).

| Sprite (texture name) | Material | Part |
|---|---|---|
| `still_copper` | `COPPER` | hammered sheet, 64 px a metre: dimples on a staggered lattice, a riveted lap seam every 0.5 m round and every 1 m up: the helmet and the column, and a solid-fired still's pot and condenser |
| `still_carbon_steel` | `CARBON_STEEL` | carbon steel, 64 px a metre: a blue-grey mill-scale mottle, sparse rust, a welded seam every 0.5 m round and every 1 m up: a liquid-fired still's pot and condenser |
| `still_stainless` | `STAINLESS` | brushed stainless steel, 64 px a metre: streaks round the vessel, welded seams with a straw-to-blue heat tint: an electric still's pot and condenser |
| `still_copper_line` | `COPPER_LINE` | drawn copper, 32 px a metre: the lines, the swan neck and the sight glasses' collars |
| `still_brick` | `BRICK` | common brick in running bond, 64 px a metre: 16 courses of 62.5 mm, 250 mm bricks, each its own fired tone: the setting and the flue |
| `still_iron` | `IRON` | sand-cast iron, 32 px a metre, sparse rust: the doors, the valve and its hand wheel |

## How to run

From the worktree root:

```bash
java -Djava.awt.headless=true tools/still-sprites/StillSprites.java src/main/resources/assets/icheme/textures/block
```

Software renders of six still variants (pot stills with each heater, a small liquid-fired one, two columns, a still with no
condenser), with the mod's sprites, into an indexed `tmp/` folder:

```bash
./gradlew --offline --init-script tools/still-sprites/render.init.gradle renderStills -PstillRenderOut=tmp/still-sprites
```

The init script compiles `tools/pump-sprites/probes/PumpRender.java` and runs it with `-Dstill=HEATER,CONDENSER,STAGES`
(the still's options) and the views iso, iso2, front and left. It changes no source set of the build.

## Entry points

- `StillSprites.java`: `copper()`, `carbonSteel()`, `stainless()`, `copperLine()`, `brick()`, `iron()`; `main(folder)` writes the six PNGs.
- `render.init.gradle`: task `renderStills` (one `renderStill-<variant>` per variant).

## History

- 2026-11-09, batch `2026-11-09-batch-distiller`: first version. The `WOOD` finish went with the worm tub (one top
  condenser for every still); the lines got their own `COPPER_LINE` finish, so the sheet's seams stay on the vessels.
  Owner, the same day: a pot finish per heater tier (copper, carbon steel, stainless), the condenser in its pot's finish.
