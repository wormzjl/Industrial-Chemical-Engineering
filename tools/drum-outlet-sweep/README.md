# drum-outlet-sweep

Purpose: replicate `EquipmentShape.bootFor`'s oil-outlet placement for every lying boot drum (D 0.5 to 10 m, T/T 1 to 64 m) and list the drums whose outlet falls back to the front head, with options A (cell centre, capped largest line) and B (off-centre point). Batch: [2026-10-28-drum-outlet-placement](../../documentation/2026-10-28-drum-outlet-placement/README.md).

Run: `node tools/drum-outlet-sweep/sweep.js` (Node.js, no dependencies). Prints the fallback count per diameter, the 2 x 6 drum's row and the drums option A moves.

Entry point: `sweep.js`. It mirrors the Java constants by hand (`SADDLE_WIDTH`, `SADDLE_POSITION`, `PipeSizes.flange`); re-check them before reuse.

History: 2026-10-28 written for the diagnosis; extended with option A as built (8 drums move, 0.5 m bore each).
