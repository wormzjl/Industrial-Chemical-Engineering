# B-E1 Equipment geometry: report

Thread wave-a2 (Opus medium), branch `claude/review-wave-a2-saves-96a998`, after merging the review branch (fast-forward to 85a6fa749). Items E12, E11, E13 of `EQUIPMENT_REVIEW.md`. No value changes: a cache changes when a value is built, never the value.

## E12: the built-unit cache keyed by the shape facing north (746547294)
- Premise checked: the only readers of `front` in `science/equipment` are `EquipmentShape.transform()` (1040), `Nameplate.of` and `ShapeRepair`. None is called from `ProcessUnit` or any design (`u.shape` reads only sizes, support, duty, bank, tower and still options, exchanger type, facets).
- `ProcessUnit.of` builds the unit for `shape.withFront(NORTH)`. The cached unit's `shape` faces north; its drawing does not read the front.
- Counter: cache entries per shape across the four headings: 4 -> 1.
- Test: `ProcessUnitTest.everyHeadingSharesTheUnitBuiltFacingNorth`. For one shape of every unit kind at each heading, the cached unit faces north, and its drawing equals, as raw bits (positions, shading, normals, materials, texture axes, parts), a fresh build of the turned shape.

## E11: a geometry draws its unit once (28619784d)
- `Geometry` draws the designed unit once at its detail and includes that mesh. A floating-roof tank keeps the same drawing for `floating()` (roof and ladder), released once cut, instead of drawing a third time. The server path is unchanged (a server never calls `floating()`).
- Counter, whole draws of a designed unit per shape (4 headings x NORMAL and LOW): before 4 frame builds + 8 drawings (tank: + 8) = 12 (tank 20); after E12 + E11, 1 + 8 = 9 (tank 9). Per geometry: 1 drawing (tank 2 -> 1), plus the frame build shared by 8 geometries instead of 2.
- Not done: "one build" per geometry in the strict sense. That needs the cached frame unit to keep its NORMAL mesh, so a NORMAL geometry reuses it. The cost is holding up to 64 unit meshes in `ProcessUnit`'s cache beside the geometries' own copies. Left as an option for the main thread.

## E13: one BoundedCache (6185b52b4, 0adfa7819), all 22 caches
- `science/equipment/BoundedCache`: least recently used dropped past a count bound and an optional weight bound. One build per key at a time (`ConcurrentHashMap.computeIfAbsent`). Its monitor guards only the LRU order; values are removed after the monitor is released, so a builder holding a map bin may still read the cache (no deadlock with eviction). A null is not kept. The newest entry stays even when it alone exceeds the weight.
- 22 caches moved. Readers by thread, and the old form:
  - `ShapeRasterizer` GEOMETRIES (64, 300,000 facets), LOW_GEOMETRIES (16, 300,000 facets), PIPE_RELIEF (64): chunk-build workers, render thread, server thread. Old form: ConcurrentHashMap.
  - `Geometry.wiring` (16): render thread. Old form: ConcurrentHashMap.
  - `ProcessUnit.BUILT` (64), `NozzleBore.BORES` (64), `ColumnStructure.LAYOUTS` (64): server thread, chunk workers, render thread. Old form: ConcurrentHashMap. The bores' lazily filled array is unchanged.
  - Client, the 11 of CLIENT_REVIEW C22: `DataControlRenderer` PORTS, CABLES, LEADS, BAKED (2048 each); `InstrumentRenderer` PLANS (1024, block-entity keys equal by identity as before), WIRING (256), CONNECTORS (2048); `SightGlassRenderer` (256), `CoolingTowerBasinRenderer` (64), `ValveTurnRenderer` (64), `NameplateRenderer` PLATES (1024). All on the render thread only. Old form: HashMap or IdentityHashMap; now thread safe.
  - The detail-switch and session clears stay as they were.
- Bounds: a cache that cleared at `size() > N` held N + 1 entries; it now holds N.
- Counter: rebuilds when a 65th shape enters view: 65 -> 1 (`BoundedCacheTest.aSixtyFifthKeyDropsOnlyTheLeastRecentlyUsed`). The geometry miss no longer sums the facets of all cached geometries (O(1) weight).
- World, the four E13 names (main thread's decision A, commit 0adfa7819), cache fields and eviction only: `EquipmentStructure` SHAPES (4096), `FloatingRoofs` RIMS (4096; the lazily filled step array unchanged) and JOINS (4096; keys equal by shape identity, as before), `LineShapes` (2048). Their readers are the server thread (collision, selection) and the client main thread (outline, client collision), plus chunk-build workers where Minecraft asks a block's shape off-thread. Old form: ConcurrentHashMap. `BoundedCache` keeps that thread safety, with one build per key.
- Not in E13's list, left as found: the client model caches (`EquipmentShellModel`, `FluidDeviceModel`, `PipeRackModel`, `ConduitModel`), `RenderLod.Tiers`, `runtime` `Silhouettes` and `InstrumentHosts`.

## Tests (pending "lane free"), each once
- `./gradlew compileJava compileTestJava --max-workers 4`
- `./gradlew equipmentGates --tests com.wormzjl.icheme.science.equipment.EquipmentLayoutGoldenTest --tests com.wormzjl.icheme.science.equipment.ShapeRasterizerTest --tests com.wormzjl.icheme.science.equipment.ProcessUnitTest --tests com.wormzjl.icheme.science.equipment.EquipmentShapeCompatTest --tests com.wormzjl.icheme.science.equipment.BoundedCacheTest --max-workers 4`
- No test class loads the six client renderers; their change is compile-checked only. No class in this run list loads the four world caches either: they too are compile-checked only.

Results: pending.
