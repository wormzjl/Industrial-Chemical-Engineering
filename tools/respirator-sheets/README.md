# respirator-sheets

Batch `2026-11-18-respirator`. Software renders of the respirator's look options for the owner, and the placeholder textures the mod ships until the owner picks.

## How to run

```
node tools/respirator-sheets/sheets.mjs tmp/respirator/sheets              # option sheet, icons, iso views
node tools/respirator-sheets/sheets.mjs tmp/respirator/sheets --textures   # also writes the placeholder textures
```

Run from the worktree root. No dependencies (Node 22). Output only under `tmp/<name>/`; the sheets are images and are not tracked.

## Entry points

- `sheets.mjs`: the head form and the three masks (A half mask with twin cartridges, B full face with one front canister, C full face with twin cartridges) as boxes and cylinders; a z-buffered orthographic renderer with outlines; 16 x 16 icons of each option; the worn texture (64 x 32 outer armour layer, option B flat on the head). `--textures` writes `textures/item/respirator.png`, `respirator_filter.png` and `textures/models/armor/respirator_layer_1.png` (option B).
- `png.mjs`: an RGBA PNG writer, a canvas and a 5 x 7 font.

No people in any image: the mask sits on a grey head form (owner rule, 2026-11-18).

## History

- 2026-11-18: first sheet, options A, B and C; placeholder textures of option B.
