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

- Built: per-structure mesh handles, light read once a second, no shape hashing. Heavy low lane (`heavy-low2`): p50 2.28 ms (2.33 before), GPU 0.95 ms, away 0.90 ms (1.01); system CPU 63 %. No gain: the hashing was not the cost. Supposed instead: one draw call and uniform upload per structure, 2–3 µs each in the driver.
- Reverted; patch in `lod-low-cache-logs-2026-11-09.tar.gz` (`body-cache.patch`, against `536ac69ec`).

## Region meshes (owner: "Try region meshes")

- Built: `EquipmentBodyRenderer` merges the bodies whose origins share a 32-block square into one static mesh, positioned from the square's corner, and draws each square in view with one call. A body's triangles (shaded, tinted, atlas UVs) are cached by shape, paint and detail, 32 bodies at most. A frame compares each square's members by identity (origin, shape, paint, light) and merges at most one changed square, one in view first; meanwhile its last mesh stands. Each structure's light is read once a second, the structures spread over the second.
- Heavy low lane (`heavy-low3`), against the same scene before:

| Build | Mean FPS | p50 ms | p95 ms | GPU p50 / p95 ms | Away p50 ms |
|---|---|---|---|---|---|
| normal detail (chunk meshes) | 589.8 | 1.53 | 2.64 | 0.67 / 1.47 | void |
| low, one mesh per structure | 380.7 | 2.33 | 4.23 | 0.97 / 1.82 | 1.01 |
| low, region meshes | 578.3 | 1.55 | 2.74 | 0.64 / 1.53 | 0.78 |

- System CPU 41 % (another process at 1.3 cores; normal lane 39 %). The view shot is identical to the per-structure build's: every body is drawn. The 0.8 ms was the per-structure draw calls, as supposed. Low detail now costs what normal detail costs on this GPU, and keeps its 35–80 % fewer body triangles for weak GPUs. Kept. Option A/B of the proposals above is settled by this build.
- Trade-offs: a light change shows up to about 1 s late; a change to one structure merges its whole square again (one square per frame, not timed); a square is culled as a whole. Normal-detail cooling-tower shells take the same path (about one tower per square: no change expected). Logs: `lod-region-mesh-logs-2026-11-09.tar.gz` (with `region-mesh.patch`, against `6fa179364`); shot `fps-heavy-low3-view.png`. Code guide: `cooling-towers.md` and `equipment.md` describe one mesh per structure; update at the merge.
