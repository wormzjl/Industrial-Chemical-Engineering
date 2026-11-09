# Distance-based level of detail (LOD) rendering

Status: **Implemented 2026-11-09** (0.23.2), from `claude/lod-rendering-optimization-1ad1f9`. Stage A (L1), L2 = B3 (no body LOD), L3 closed, L4 measured; region meshes for low detail.

## Scope

Reduce client rendering cost with tiers that follow an object's size on screen: the per-frame instrument, cable and sight-glass renderers first (Stage A), then, if a measurement asks for it, a far tier for equipment bodies (Stage B). Drawing only: placement, collision, layouts, packets and saves do not change.

## Documents

- [LOD_RENDERING_PLAN.md](LOD_RENDERING_PLAN.md): pixel sizes of each detail by distance, the current renderers and their per-frame work, Stage A and the three body routes B1–B3, verification and the owner decisions.
- [STAGE_A_REVIEW.md](STAGE_A_REVIEW.md): Stage A as built: rules per renderer, two deviations from the plan, tests run and what is not verified.
- [FPS_PAIR_REVIEW.md](FPS_PAIR_REVIEW.md): the frame-time pair, baseline against Stage A, with the L2 proposal and the distance or view-depth options.
- [HEAVY_BENCH_REVIEW.md](HEAVY_BENCH_REVIEW.md): the heavy scene (50 columns, 200 drums) at normal and low detail: bodies are not the load; region meshes bring low detail level with normal.

## History

- 2026-11-09: plan written from `main` `b794e2d41` (0.22.0). Main finding: `InstrumentRenderer` draws every gauge within 120 m with no frustum test, six text strings per dial and a light lookup per vertex (estimate 10–20 ms a frame for 100 dials, not measured); static chunk meshes measured near zero frame time in the proof of concept. Code guide: no change.
- 2026-11-09: owner approved Stage A. `63454d60d` adds `RenderLod`, `lod_scale`, dial and transmitter tiers, frustum and size culls for instruments, cables and sight glasses, and `VertexBufferCache` for body, moving-part and tank meshes. Passed: `RenderLodTest` (5), `GaugeAccessoriesTest` (14). FPS and the in-game look not checked.
- 2026-11-09: FPS pair (owner request, plan L4) with `tools/lod-rendering/` (`fcf8ebc76`): 100 gauges, 40 drums, 10 columns at 1920 x 1080. Baseline 10.68 ms facing the scene and 10.65 ms facing away; Stage A 1.63 ms and 0.64 ms. The baseline spent about 10 ms a frame on 100 gauges. Proposed: L2 = B3; tier metric options A, B, C. Baseline scratch worktree removed after the run.
- 2026-11-09: owner decision on the tier metric: A, keep straight-line distance (tiers stay put when the player turns; screen-edge objects lose detail up to 1.8x early). No code change.
- 2026-11-09: heavy bench (owner: "bench harder") with `42dbfa1ba`: 50 columns, 200 drums, 100 gauges on Stage A. Normal detail 1.53 ms (GPU 0.67 ms); low detail 2.33 ms (GPU 0.97 ms). Proposed: L2 = B3 and close L3; `low_polygon_mode` per-structure cost options A or B.
- 2026-11-09: option B (owner: "optimize it now"): per-structure mesh handles and light in `EquipmentBodyRenderer`. Heavy low lane 2.28 ms against 2.33 ms: no gain, so reverted; patch archived. Likely cost: one draw per structure and no section culling.
- 2026-11-09: region meshes (owner: "try region meshes"): `EquipmentBodyRenderer` merges the bodies of each 32-block square into one mesh, one draw per square; light read once a second. Heavy low lane 1.55 ms (GPU 0.64 ms) against 2.33 ms; normal detail 1.53 ms. Kept. No test loads the class: compile only.
- 2026-11-09: owner decision L2 = B3: no distance tier for equipment bodies; L3 (FAR content) closed with it. `low_polygon_mode` stays the only body switch. No code change.
- 2026-11-09: merge preparation (owner: "merge", version 0.23.2). `8aeaa8d64` merges main 0.23.1 (INDEX rows kept on both sides); `e4d3d2d9b` drops `VertexBufferCache.size` (no caller); code guide `cef706408` (client-gui, instrument-accessories, equipment, cooling-towers, README); `ec78e91ba` mod_version and CHANGELOG. Final lane below.
- 2026-11-09: after the merge (owner: "yes, fix it"): `BoundedCpuSolveServiceTest.twoPhaseShutdown...` joins the worker thread (3 s) before asserting it ended. `ThreadPoolExecutor` reports terminated once its last worker leaves the worker set, while that thread still returns from `run()`. Its class passes 8 of 8. Merged as 0.23.3 (test only: no final lane, since nothing else changed since the 0.23.2 lane; code guide: no change).

## Code areas

`client/RenderLod`, `client/VertexBufferCache`, `client/InstrumentRenderer`, `client/TankRenderer`, `science/equipment/InstrumentGeometry`, `client/DataControlRenderer`, `client/SightGlassRenderer`, `client/MachineAnimation`, `client/EquipmentBodyRenderer`, `client/model/EquipmentShellModel`, `client/ClientConfig`, `science/equipment/RenderDetail`, `science/equipment/ShapeRasterizer` (`renderBody`, `chunkSurface`).

## Open items

- An in-game look at the switch points (owner-started).

## Merge

- Final lane, once, on `ec78e91ba` (`LANE_OUT=tmp/final-lane tools/merge-lane/lane.sh head test gametest campaign`): `test slowGates` 2983 tests, 1 failure; the 33 junction lines identical to the 0.23.1 lane (`main-gate-fixes`), floats included; `MIXED_GAS_COST` counters identical (newtonSolves 300, 163.4 MB). Fluid GameTests 73 of 73 (run id `lod-final-1109`). The campaign skipped itself: nothing it loads changed since `main`. chain-100 not run: it loads none of the changed code (client renderers, `ClientConfig`, `InstrumentGeometry`).
- The failure: `BoundedCpuSolveServiceTest.twoPhaseShutdownTerminalizesReadyWorkInterruptsRunningWorkAndLeaksNoWorker` line 333, `assertFalse(workerThread.isAlive())`. The batch changes nothing it loads; it passed in the 0.23.1 lane, and its class replayed alone passes 8 of 8. A race in the test: the worker counts down `interruptObserved` before its thread has exited. Main's own; open item.
- Tooling cleanup: `main-reach.pl` 0 unreachable; `VertexBufferCache.size` removed (no caller). Tool folder: `tools/lod-rendering/` (the FPS lane); the probe's `mode=lod` is in `tools/custom-equipment-rendering-poc/frame-time-probe/`. Scratch and the final lane archived in the main checkout at `tools/lod-rendering/logs/lod-rendering-scratch-logs-2026-11-09.tar.gz`; that final lane is the next merge's baseline.
- Code guide: client-gui, instrument-accessories, equipment, cooling-towers, README (snapshot `e4d3d2d9b`). No lang change: no translation commit.
