# Constructor ghost rendering

Client lane, PERF. In progress since 2026-11-18 on `codex/constructor-ghost-rendering`.

## History

- 2026-11-18 — Cached the sorted translucent draw buffer in `EquipmentGhost` and culled previews outside the view. The collision scan reuses a position for clear cells and the controller lookup reuses the layout’s controller cell.

## Rendering

All previews in view share one buffer so intersecting ghosts sort together. The buffer records the camera-relative positions used by the entity shader for fog. A camera translation, pose change, change of visible designs or paint, sort mode change, atlas restitch or detail change causes a fresh upload. The last preview leaving range releases the GPU buffer and its native scratch storage. Layout and shape meshes retain their bounded caches.

## Verification

An offline capture compared the renderer with `98254db2c` using the game’s quad builder, vertex format, pose transforms and translucent sorter. It captured the payload at the upload boundary with a substitute graphics backend. All defined vertex attributes and sorted indices matched for a column, a translated camera, overlapping painted drum and column previews under a rotated pose, a replacement sprite, Low detail, and an induced-draft tower.

| Capture | Before | After |
|---|---:|---:|
| Normal detail 3 m column, 20 trays, vertices on first upload | 45,144 | 45,144 |
| Vertex and index bytes on first upload | 1,760,616 | 1,760,616 |
| Further uploads in 60 stationary frames | 60 | 0 |
| Draws in those 60 frames | 60 | 60 |
| Draws with the whole column outside the view | 1 per frame | 0 |

The final head lane compiled the renderer and the test sources. Its coverage map selected zero test classes. Live frame time and shader-pack appearance remain unmeasured. Moving the camera still incurs vertex packing, sorting and upload for the previews in view.
