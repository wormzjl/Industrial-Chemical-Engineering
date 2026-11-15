# D-K3 One client watch and one polygon bake: C11, C15

Batch `2026-11-13-codebase-review`, wave D, brief "D-K3" in [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md). Subagent, Opus high, 2026-11-14. Structure only. Nothing here is compiled or run (no Gradle, no JVM); the checks are by reading and by node scripts (braces, line endings, Minecraft-free imports).

## Summary

- **C11.** `client/fx/state/ClientWatch<K, S>` (69 lines, no Minecraft class) is `CellWatch`'s watch without the game. It holds the watched keys and their states, and runs the request of the nearest `maximum` keys in the caller's order: a key is asked for again while its first state has not arrived, and the rest are released. It also has `forget` (it returns the dropped keys for the caller to release) and `reset(release)` at a level change. The caller keeps the range, the order of nearest, the period and the connection checks, because these differ by copy (table 1).
- Three watches now use it: `CellWatch` (its five instances in `ProcessStates`), `DriverSounds` and `RotationAnimation`. Four copies stay as they are: `MoverSounds`, `TankRenderer`, `SightGlassRenderer` and `InstrumentRenderer`. None of the four asks again for a lost first state (the C12 defect), and each has more differences (table 1). Moving them onto `ClientWatch` is the C12 fix, a DEFECT change for the owner (CLIENT_REVIEW open question 5). It is not done here.
- **C15.** `client/model/PolygonBake` (61 lines, no Minecraft class) is `EquipmentBodyRenderer.body`'s loop. It makes triangle fans with float positions and normals and sprite UVs through an `Atlas` (`EquipmentShellModel.ATLAS`, built once). The colour is a `Shade` function times the paint tint of the material's channel. `EquipmentBodyRenderer.body` and `SceneView.upload` now bake through it. `EquipmentShellModel.bake` (quads) and `EquipmentGhost.quads` (quads) stay (table 2).
- **Tests.** `ClientWatchTest` (126 lines) and `PolygonBakeTest` (174 lines). `PolygonBakeTest` runs without a Minecraft bootstrap: the sprite and the shade are the parameters the game supplies. The test compares the two old loops, kept in the test as the references, bit for bit with the new path on a real drum body (`ShapeRasterizer.renderBody`, LOW) plus an unpainted pentagon.
- **Lines.** Main code: C11 removed 61 lines and added 40 in the three users, plus `ClientWatch`. C15 removed 44 lines and added 36, plus `PolygonBake`. The review estimated about 200 and about 80 lines removed. That holds only if every copy moves, and the table shows that most of them differ.
- **Requests to the server.** Nothing is added or dropped. Each instance's requests are the same lists, in the same order and at the same moments. The only differences are calls with two empty lists, which every request method drops before it sends (`FluidNetwork.request`, `requestDrivers`, `RotationNetwork.request` all start with `if(!watch.isEmpty()||!unwatch.isEmpty())`).
- **Allocations.** No per-frame path changed.
  - Per tick: the request runs once a second. It allocates what it allocated before, except in `RotationAnimation`: a `LinkedHashSet` replaces a `HashSet`, and an `ArrayList` replaces a stream's `toList`.
  - Per bake: a body bake builds one capturing lambda (the shape). It runs only on a `BODIES` cache miss in a region merge (at most one merge a frame), and it already allocated a `double[3]` a vertex and an `int[3]` a triangle. A film actor's upload now fills four arrays first. It runs once for each polygon list, not each frame.
  - The four request functions are built once: one lambda for each `CellWatch` at construction, and static method references.
- **A tool edited.** `tools/custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java` read `CellWatch.watched` by reflection. It now reads `CellWatch.watch`, then `ClientWatch.watched` (in the C11 commit).

## Table 1: the watches (C11)

| copy | key | range, blocks | max | period | payload | order of nearest | asks again | level change | differs from `CellWatch` in | outcome |
|---|---|---|---|---|---|---|---|---|---|---|
| `CellWatch` (FLARES, STILLS, TOWERS, HEATERS, CHIMNEYS) | `Target` (fluid cell, identity) | `MOVER_RANGE`−8 = 88 (flares), 48 (stills), `WATCH_RANGE`−8 = 120 (the rest) | 64 | 20 ticks, in `ProcessStates`, skipped while paused | `FluidNetwork.Watch` kinds | distance, then identity | yes | releases all if connected and any watched; drops other-level cells | (the reference) | uses `ClientWatch` |
| `DriverSounds` | `BlockPos` origin | 88, measured at the controller cell | 64 | 20 ticks, own counter, no pause check | `driver_watch` | origin distance, then `asLong` | yes | same rule | range at the cell but order at the origin (the caller's; kept); skips an empty request (the wire drops it anyway); forget drops the watched origins with no tracked controller cell left and releases them without a connection check (the caller's; kept) | **instance** |
| `RotationAnimation` | `BlockPos` (shafts and live machine origins) | `RotationNetwork.RANGE`−8 = 88 | `RotationNetwork.MAXIMUM_WATCHED` 256 | 20 ticks, no pause check | `RotationNetwork.Watch` | distance, then `asLong` | yes | releases when connected even if nothing is watched (empty: dropped by the wire) | stream `sorted().limit()` in place of sort-and-take (same order: the comparator is total); `WATCHED.addAll(near)` in place of `addAll(watch)` (same set, and a `HashSet` add of a present key changes no order); candidates built anew each period with `alignClocks` (the caller's; kept) | **instance** |
| `MoverSounds` | `Target` (fluid cell, or an in-line pump's pipe block) | 88 | 64 | 20 ticks, no pause check | `Watch.MOVERS` | distance, then identity | **no**: only new targets | releases even if empty (dropped by the wire) | no asking again (C12); `accept` also checks `valid` (the block entity there still has that target); `forget` drops by position whether or not the cell was tracked | stays |
| `TankRenderer` | `BlockPos` node cell, **no identity** | `WATCH_RANGE`−8 = 120 | 64 | 20 ticks, own counter, no level check | `TankWatch` | distance only; ties keep `clientTankCells` order | **no** | **none** (cleared only at logout) | no identity, no tie-break, no asking again, no level reset (C12); skips an empty request; sets and clears `FloatingRoofs` glides; no `track`/`forget` (reads `clientTankCells`) | stays |
| `SightGlassRenderer` | `Target` (pipe, identity) | 120 | 64 | 20 ticks | `Watch.SIGHT_GLASSES` | distance, then position `asLong` (not identity) | **no** | `track(List.of())` when connected (releases all), else `clear()`; clears `SURFACES` | no asking again (C12); tie by position; `accept` checks block state and identity; candidates from `clientSightGlasses`, no tracked cells; `forget(BlockPos)` drops by position | stays |
| `InstrumentRenderer` | `InstrumentTarget` (position, key, instance) | 120, at the mount's centre | literal 64 | `++ticks%20`: first request at tick 20, not 0 | `InstrumentWatch` | distance only; the wanted set is a `HashSet`, so the watch list goes in hash order, not nearest first | **no** | `clear()` with **no release request** | watch order; no tie-break; no asking again; no release at a level change; no connection check in the tick; a `RECEIVED` map beside the states | stays |

## Table 2: the bakes (C15)

| copy | fan | sink | position | UV | normal | shade | colour | outcome |
|---|---|---|---|---|---|---|---|---|
| `EquipmentBodyRenderer.body` | triangles 0, k, k+1 | float and int arrays | `(float)p` | material sprite `getU((float)uv)` | `(float)n` | `(int)min(255, shade(polygon, c) × towerStain(shape, p))` | paint tint by channel (white without one): `0xFF000000\|shade*(t>>16&255)/255<<16\|…` | **is `PolygonBake.triangles`** (its shade is a lambda over `shape`) |
| `SceneView.upload` | triangles 0, k, k+1 | `POSITION_TEX_COLOR` builder: `addVertex`, `setUv`, `setColor(int)` | same | same | none | `shade(polygon, c)` | `Paint.DEFAULT` tints, same packing | **through `PolygonBake`**: its buffer is filled from the bake's arrays, the same floats and ints in the same order; the ByteBufferBuilder now opens only when every sprite is present (the result is null as before) |
| `EquipmentShellModel.bake` | quads 0, f, f+1, min(f+2, n−1) (the last vertex repeats) | a `QuadBakingVertexConsumer` per quad, `BakedQuad` | same | same | same | `shade(polygon, c)` | a **tint index** for the block colour, and a fixed material colour × shade as `setColor(r, g, b, 255)`; the face direction from the quad's Newell sum | stays: quads, a tint index and per-quad state |
| `EquipmentGhost.quads` | quads (the last vertex repeats) | `float[9]` a vertex | `(float)(x + p)`: the cell offset is added in double before the cast | one sprite for every material | `(float)n` | none | none; the channel goes to the shader as a float | stays |

Also found by grep, outside the brief's list and left alone:
- `TankRenderer.upload` and `MachineAnimation.build` are the same triangle fan into a `BLOCK` builder with light and normal. Tank's `setColor(r, g, b, 255)` gets the same bytes as `PolygonBake`'s colours. MachineAnimation's `setColor(shade, shade, shade, 255)` equals a white tint exactly, because `shade*255/255 == shade` for 0 to 255. Both could bake through `PolygonBake`. That is a follow-up.
- `InstrumentRenderer.corners`, `PipeLayingClient` and `SightGlassRenderer` are quad fans with their own sinks (v over `PERIOD`, the pose, fixed colours).

## Tests for the main thread

Compile every source set first: `./gradlew compileJava compileTestJava compileMcpCompatJava compileFluidGameTestJava`. No test loads an edited class except the two new tests (grep of `src/test`, `src/fluidGameTest`, `src/mcpCompat`). `DrawCallTest` names `SceneView` only in a comment.

| run | proves |
|---|---|
| `./gradlew test --tests com.wormzjl.icheme.client.fx.state.ClientWatchTest` | the nearest N in order with the tie-break, the watch and release differences, a lost first state asked again, an unwatched state ignored, forget, reset with and without a connection, clear |
| `./gradlew test --tests com.wormzjl.icheme.client.model.PolygonBakeTest` | `PolygonBake.triangles` gives the old body loop's arrays and the old film upload's vertex stream bit for bit; a missing sprite gives null in all three |
| `./gradlew test --tests com.wormzjl.icheme.LayeringTest` | the new `client` classes break no layer rule (the brief names it; nothing of `science` or common code names a `client` class) |
| `./gradlew equipmentGates --tests com.wormzjl.icheme.science.equipment.EquipmentLayoutGoldenTest` | named by the brief; nothing it loads changed, so it should stand up to date (no geometry or layout code was touched) |

## What the owner should look at in the game

These are not provable by a gate:
1. **Drivers.** Walk up to a motor, a generator, a steam turbine, a combustion engine and a gas turbine (within about 88 blocks). Each loop starts as before. Walk away and the loop stops. Start and stop a prime mover: its start or stop one-shot plays on the speed crossing zero. Place a new driver: its loop starts within a second or two (the watch asks again).
2. **Rotation.** Shafts, pump and motor couplings, and a gearbox's two ends turn at their published speed. A freshly placed machine starts turning within a second or two. Leave by a portal and come back: everything turns again.
3. **Process effects.** Flare flames, still fires, cooling tower plumes, fired heater and prime mover flues, and chimney smoke appear as before. The F3 `STATES` counter is as before.
4. **Bodies at Low detail and natural cooling towers.** The colours, the paint and the tower's weathering streaks look the same. **Field guide films:** the actors look the same with the default paint.
5. **Unchanged.** Mover sounds, open tanks, sight glasses, instruments, the constructor ghost and chunk shells. A difference there would point to something else.

## Not verified without a compiler (file and line)

- `client/fx/state/CellWatch.java:43`: the diamond `new ClientWatch<>(…, (in,out)->FluidNetwork.request(kind,in,out))`, inferred from the field type `ClientWatch<Target,S>`.
- `client/DriverSounds.java:35`: `DriverSounds::request` as `ClientWatch.Requests<BlockPos>` in a static initializer. `:43`: `forget(o->shell(o)==null)` inside the scope of the pattern variable `shell` (the method call already had this form before the change). `:54`: `WATCH.accept(origin, share)` boxes `double` into `Double`; `before` is a `Double`.
- `client/RotationAnimation.java:36-37`: `RotationNetwork::request` (one overload).
- `client/model/EquipmentShellModel.java:80-84`: the anonymous `PolygonBake.Atlas<TextureAtlasSprite>`. It calls `EquipmentShellModel.sprite` by its qualified name, so it does not call its own `sprite`.
- `client/model/PolygonBake.java:18,44`: the placement of the `@Nullable` annotation (JetBrains, on a type variable and before `public static <T>`).
- `client/EquipmentBodyRenderer.java:198`: the block lambda as `PolygonBake.Shade` (it returns `int`).
- `client/gui/tutorial/SceneView.java:220`: `EquipmentShellModel::shade` as `PolygonBake.Shade`.
- `src/test/.../client/model/PolygonBakeTest.java:34`: an anonymous class with a diamond over a private nested record. The test also assumes that a LOW drum body has a painted material (shell or head; asserted).
