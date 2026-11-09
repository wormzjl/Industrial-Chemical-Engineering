# Distance-based level of detail (LOD) rendering

Status: **In progress since 2026-11-09** on `claude/lod-rendering-optimization-1ad1f9`. Stage A implemented (`63454d60d`, owner approved L1); L2–L4 open; not merged.

## Scope

Reduce client rendering cost with tiers that follow an object's size on screen: the per-frame instrument, cable and sight-glass renderers first (Stage A), then, if a measurement asks for it, a far tier for equipment bodies (Stage B). Drawing only: placement, collision, layouts, packets and saves do not change.

## Documents

- [LOD_RENDERING_PLAN.md](LOD_RENDERING_PLAN.md): pixel sizes of each detail by distance, the current renderers and their per-frame work, Stage A and the three body routes B1–B3, verification and the owner decisions.
- [STAGE_A_REVIEW.md](STAGE_A_REVIEW.md): Stage A as built: rules per renderer, two deviations from the plan, tests run and what is not verified.

## History

- 2026-11-09: plan written from `main` `b794e2d41` (0.22.0). Main finding: `InstrumentRenderer` draws every gauge within 120 m with no frustum test, six text strings per dial and a light lookup per vertex (estimate 10–20 ms a frame for 100 dials, not measured); static chunk meshes measured near zero frame time in the proof of concept. Code guide: no change.
- 2026-11-09: owner approved Stage A. `63454d60d` adds `RenderLod`, `lod_scale`, dial and transmitter tiers, frustum and size culls for instruments, cables and sight glasses, and `VertexBufferCache` for body, moving-part and tank meshes. Passed: `RenderLodTest` (5), `GaugeAccessoriesTest` (14). FPS and the in-game look not checked.

## Code areas

`client/RenderLod`, `client/VertexBufferCache`, `client/InstrumentRenderer`, `client/TankRenderer`, `science/equipment/InstrumentGeometry`, `client/DataControlRenderer`, `client/SightGlassRenderer`, `client/MachineAnimation`, `client/EquipmentBodyRenderer`, `client/model/EquipmentShellModel`, `client/ClientConfig`, `science/equipment/RenderDetail`, `science/equipment/ShapeRasterizer` (`renderBody`, `chunkSurface`).

## Open items

- L2–L4 in the plan (body route, FAR content, FPS pair).
- An in-game look at the switch points (owner-started).
- The FPS pair needs the owner to start the client.
