# Dynamic settling: the band law on real numbers

Batch: [`2026-11-17-dynamic-settling`](../../documentation/2026-11-17-dynamic-settling/README.md). A planning check, no
mod code: `band.mjs` runs the plan's band law (section 4, L2) on the review plant's receiver and prints the numbers of
the plan's section 3.

```
node tools/dynamic-settling/band.mjs
```

It prints: the two drop speeds; the steady carried share against today's law `(1 − N) r` at slices of 5 s and 20 s;
the time a layer filled turbid needs to clear, with and without Richardson and Zaki's swarm factor; a feed that stops;
the same slices in 1, 4, 16 and 64 parts; a 0.2 mm bubble's rise.

The script's drop speed repeats `Separation.settling` (Stokes with Schiller and Naumann's correction, five passes).
