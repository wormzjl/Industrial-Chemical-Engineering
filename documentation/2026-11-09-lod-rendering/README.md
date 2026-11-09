# Distance-based level of detail (LOD) rendering

Status: **In progress since 2026-11-09** on `claude/lod-rendering-optimization-1ad1f9`. Stage A implemented (`63454d60d`, owner approved L1); L2–L4 open; not merged.

## Scope

Reduce client rendering cost with tiers that follow an object's size on screen: the per-frame instrument, cable and sight-glass renderers first (Stage A), then, if a measurement asks for it, a far tier for equipment bodies (Stage B). Drawing only: placement, collision, layouts, packets and saves do not change.

## Documents

- [LOD_RENDERING_PLAN.md](LOD_RENDERING_PLAN.md): pixel sizes of each detail by distance, the current renderers and their per-frame work, Stage A and the three body routes B1–B3, verification and the owner decisions.
- [STAGE_A_REVIEW.md](STAGE_A_REVIEW.md): Stage A as built: rules per renderer, two deviations from the plan, tests run and what is not verified.
- [FPS_PAIR_REVIEW.md](FPS_PAIR_REVIEW.md): the frame-time pair, baseline against Stage A, with the L2 proposal and the distance or view-depth options.

## History

- 2026-11-09: plan written from `main` `b794e2d41` (0.22.0). Main finding: `InstrumentRenderer` draws every gauge within 120 m with no frustum test, six text strings per dial and a light lookup per vertex (estimate 10–20 ms a frame for 100 dials, not measured); static chunk meshes measured near zero frame time in the proof of concept. Code guide: no change.
- 2026-11-09: owner approved Stage A. `63454d60d` adds `RenderLod`, `lod_scale`, dial and transmitter tiers, frustum and size culls for instruments, cables and sight glasses, and `VertexBufferCache` for body, moving-part and tank meshes. Passed: `RenderLodTest` (5), `GaugeAccessoriesTest` (14). FPS and the in-game look not checked.
- 2026-11-09: FPS pair (owner request, plan L4) with `tools/lod-rendering/` (`fcf8ebc76`): 100 gauges, 40 drums, 10 columns at 1920 x 1080. Baseline 10.68 ms facing the scene and 10.65 ms facing away; Stage A 1.63 ms and 0.64 ms. The baseline spent about 10 ms a frame on 100 gauges. Proposed: L2 = B3; tier metric options A, B, C. Baseline scratch worktree removed after the run.

## Code areas

`client/RenderLod`, `client/VertexBufferCache`, `client/InstrumentRenderer`, `client/TankRenderer`, `science/equipment/InstrumentGeometry`, `client/DataControlRenderer`, `client/SightGlassRenderer`, `client/MachineAnimation`, `client/EquipmentBodyRenderer`, `client/model/EquipmentShellModel`, `client/ClientConfig`, `science/equipment/RenderDetail`, `science/equipment/ShapeRasterizer` (`renderBody`, `chunkSurface`).

## Open items

- L2 (body route; proposed B3) and L3 (FAR content, only with B1 or B2).
- An in-game look at the switch points (owner-started).
- Tier metric: distance (now), view depth, or distance with 1.5x lower thresholds (FPS_PAIR_REVIEW.md).
