# respirator-sheets

Batch `2026-11-18-respirator`. Software renders of the respirator's look options for the owner, and the built option C: the game's worn model code, its texture, the item icons and a sheet of the built model.

## How to run

```
node tools/respirator-sheets/sheets.mjs tmp/respirator/sheets           # the option sheet (A, B, C), icons, iso views
node tools/respirator-sheets/model.mjs tmp/respirator/built             # the built model's sheet only
node tools/respirator-sheets/model.mjs tmp/respirator/built --write     # also writes RespiratorGeometry.java, the texture and both icons
```

Run from the worktree root. No dependencies (Node 22). Output only under `tmp/<name>/`; the sheets are images and are not tracked.

## Entry points

- `sheets.mjs`: the head form and the three option masks as boxes and cylinders; a z-buffered orthographic renderer with outlines; hand-drawn 16 x 16 option icons. Preview only; it writes nothing into the mod.
- `model.mjs`: the one geometry of the built mask (ModelPart units: y down, face towards -z; whole-pixel cubes; part pivots and rotations), its box-UV texture atlas (64 x 64) and painting, the Java writer of `client/RespiratorGeometry.java`, and a textured renderer that follows ModelPart (pivot, then Z, Y, X rotation; box UV faces) and the entity renderer's flip. The sheet and the two item icons are rendered from it. Change the mask here and run `--write`; never edit `RespiratorGeometry.java` or the texture by hand.
- `png.mjs`: an RGBA PNG writer, a canvas and a 5 x 7 font.

No people in any image: the mask sits on a grey head form (owner rule, 2026-11-18).

## History

- 2026-11-18: first sheet, options A, B and C; placeholder textures of option B.
- 2026-11-18: option C built (owner's pick): `model.mjs` writes the worn model, its texture and the icons; the icons are renders of the model (owner: "icon does not look like the model"); `sheets.mjs` no longer writes into the mod.
