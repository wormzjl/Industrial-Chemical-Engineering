# Heavy bench: does body LOD pay?

- Status: measured 2026-11-09 (owner: "bench harder", for L2). Build: Stage A (`d9db08214`, the code of `fa214e43b`). Lane: `tools/lod-rendering/` (`42dbfa1ba`), `heavy` scene, normal and low detail.
- Setup as `FPS_PAIR_REVIEW.md` (vanilla, 1920 × 1080, RTX 4070 Ti, unlimited frame rate, render distance 12). Scene: 50 columns (D 4 m, 30 trays), 200 drums (D 2.5 × 8 m) and the 100 gauges, all placed and installed, seen from 15 m up. By diameter, height and count, about 8 to 10 times the first scene's vessel surface (estimated, triangles not counted).
- The question: B1 and B2 would draw distant bodies with fewer triangles, as one whole mesh per structure. `low_polygon_mode` already does both, for every distance: 35–80 % fewer body triangles, drawn by `EquipmentBodyRenderer`. So the gap between normal and low detail bounds what body LOD could save.

## Results (view: facing the field)

| Detail | Mean FPS | p50 ms | p95 ms | p99 ms | GPU frame p50 / p95 ms | System CPU |
|---|---|---|---|---|---|---|
| normal (chunk meshes) | 589.8 | 1.53 | 2.64 | 3.48 | 0.67 / 1.47 | 39 % |
| low (whole meshes, fewer triangles) | 380.7 | 2.33 | 4.23 | 5.63 | 0.97 / 1.82 | 45 % |

- Away windows (facing empty ground): low 1.01 ms at p50. The normal lane's away window is void: a python job took 10.8 cores during it (99 % system CPU). It is not replayed: the decision rests on the view windows.
- Both view windows had some background load (another process at 1.3 cores in the normal one). The gap, 0.8 ms or 52 %, is five times the 10 % spread seen between repeated lanes.

## Reading

1. Expected, if bodies were the load: frame time grows with the vessel geometry, the GPU frame approaches the frame time, and fewer triangles help.
2. Measured: with 8 to 10 times the vessels, the frame stays at 1.53 ms (the first scene: 1.63 ms, nearer gauges). The GPU works 0.67 ms of it: the frame is limited by the render thread, not by triangles. The low-detail path is slower, 2.33 ms, with a slower GPU frame too (0.97 ms). Whole meshes give up the chunk renderer's batching and section culling, and `EquipmentBodyRenderer` does work per structure each frame: a light lookup, a cache key holding the whole `EquipmentShape` (hashed on every lookup), and one draw. Not profiled; the 0.8 ms over 250 structures is about 3 µs each.
3. So on this GPU no body LOD built on the whole-mesh path can pay: the triangles it removes cost under 0.7 ms of GPU time, off the critical path, and the path itself costs more CPU.

## Proposals for the owner

- L2: **B3, no body LOD.** Close L3 (FAR content) with it.
- Found in passing: `low_polygon_mode` makes this scene slower, not faster, on this machine (+0.8 ms). Two options:
  - A. Leave it: it still cuts GPU triangles for weak GPUs, the case it was made for (not measurable here: no weak GPU on this machine).
  - B. Cut the per-structure cost of `EquipmentBodyRenderer`: key its meshes by structure position with a cheap identity in place of the whole shape, and look up light only when a structure's mesh is rebuilt. Then one heavy low lane checks it.
- Caveat: an integrated GPU is about 15 to 20 times slower. This scene's 0.67 ms GPU frame would be about 10–13 ms there, where triangles would matter. The decision holds for a discrete GPU. A weak-GPU lane, run by the owner on such a machine, would settle the rest.

Logs: main checkout `tools/lod-rendering/logs/lod-heavy-bench-logs-2026-11-09.tar.gz`; shots `research/2026-11-09-lod-rendering/shots/fps-heavy-*-view.png`. Code guide: no change.

## Option B tried (owner: "Optimize it now")

- Built: each structure kept its mesh handle, light (read once a second), box and a reused matrix; a frame compared by reference and hashed no shape; equal structures bound a shared mesh once.
- Heavy low lane on it (`heavy-low2`): view 387.6 FPS, p50 2.28 ms (2.33 before), GPU 0.95 ms (0.97); away 0.90 ms (1.01). System CPU 63 % (two other processes at 1.2 and 0.6 cores). No gain beyond the noise: the shape hashing was not the cost.
- The likely cost (not profiled): low detail adds one draw call and a uniform upload per structure on top of the chunk meshes, about 2–3 µs each in the driver (0.5–0.75 ms for 250), and draws each whole body without the chunk renderer's section culling (GPU 0.95 against 0.67 ms).
- Reverted: the change added a cache layer and a one-second light lag for no measured gain. The patch is in the logs archive (`lod-low-cache-logs-2026-11-09.tar.gz`, `body-cache.patch`, against `536ac69ec`).
- What would help: merge the low-detail bodies into a few region meshes (one draw per region, as the chunk renderer batches), or accept that low detail trades CPU for GPU and suits weak GPUs only. Neither is measured.
