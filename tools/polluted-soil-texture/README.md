# Polluted soil texture

Purpose: draw the polluted soil block's 16 × 16 texture and the option sheet it was picked from. Batch: [`2026-11-18-ground-pollution`](../../documentation/2026-11-18-ground-pollution/README.md) (G4b).

## How to run

From the worktree root (node, no dependencies):

```
node tools/polluted-soil-texture/make.js tmp/soil-textures src/main/resources/assets/icheme/textures/block/polluted_soil.png
```

## Entry points

- `make.js <out folder> [texture path]`: writes options A (grey ash), B (brown with a yellow-green crust) and C (dark with an oily violet sheen) and `polluted_soil_options.png` (dirt, then A, B, C, each 3 × 3 tiles at ×8) to the folder; writes option C to the texture path. A fixed seed: the same bytes every run.

## History

- 2026-11-18: made for G4b; option A stood in as the placeholder. The owner picked C ("C: dark oily"), installed.
