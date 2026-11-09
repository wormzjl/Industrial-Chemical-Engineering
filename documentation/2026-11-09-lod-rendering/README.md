# Distance-based level of detail (LOD) rendering

Status: **Planned** 2026-11-09, on `claude/lod-rendering-optimization-1ad1f9`. Owner decisions L1–L4 open; no code changed.

## Scope

Reduce client rendering cost with tiers that follow an object's size on screen: the per-frame instrument, cable and sight-glass renderers first (Stage A), then, if a measurement asks for it, a far tier for equipment bodies (Stage B). Drawing only: placement, collision, layouts, packets and saves do not change.

## Documents

- [LOD_RENDERING_PLAN.md](LOD_RENDERING_PLAN.md): pixel sizes of each detail by distance, the current renderers and their per-frame work, Stage A and the three body routes B1–B3, verification and the owner decisions.

## History

- 2026-11-09: plan written from `main` `b794e2d41` (0.22.0). Main finding: `InstrumentRenderer` draws every gauge within 120 m with no frustum test, six text strings per dial and a light lookup per vertex (estimate 10–20 ms a frame for 100 dials, not measured); static chunk meshes measured near zero frame time in the proof of concept. Code guide: no change.

## Code areas

`client/InstrumentRenderer`, `client/DataControlRenderer`, `client/SightGlassRenderer`, `client/MachineAnimation`, `client/EquipmentBodyRenderer`, `client/model/EquipmentShellModel`, `client/ClientConfig`, `science/equipment/RenderDetail`, `science/equipment/ShapeRasterizer` (`renderBody`, `chunkSurface`).

## Open items

- L1–L4 in the plan.
- The FPS pair needs the owner to start the client.
