# 2026-11-15 pollution system

Status: Planned 2026-11-15. The plan waits for the owner's decisions D1 to D6; nothing is built. Branch `claude/pollution-system-a8e213`, cut from `main` at `e9edc974b` (0.31.0).

Purpose: air pollution in 16 × 16 × 16 cubes: toxic, combustible, corrosive and particulate hazard indices from what the fluid network releases, spreading and decaying on the 5 s engine, with effects on players and mobs and vapour cloud explosions at flame sources.

Lane: new packages `science/pollution` and `runtime/pollution`. Hot files touched, each in one work package: `MaterialCatalog` (WP1), `ProcessSolveJobs` (WP3), `ShaftTrains` and `FluidWorldAuthority` (WP4), `EquipmentStructure` and the equipment blocks (WP6). Class: new feature; no reference moves.

## History

- 2026-11-15, plan: survey of the release paths (`BoundaryTransfer` kinds, chimneys, flares), the material records (`flammability` exists, no model reads it), the explosion handling of equipment (ignored today) and the store patterns; the standard (NIOSH IDLH, GHS H314, LFL with Le Chatelier); [POLLUTION_PLAN.md](POLLUTION_PLAN.md). No code, no test run.

- 2026-11-15, owner: "also needs to be crash-proof when world corruption happens". Plan section 8: a damaged store is set aside and the dimension starts empty, a damaged cube is dropped alone, a run-time failure keeps the last field and at worst turns pollution off; the world is never refused for it.

## Documents

- [POLLUTION_PLAN.md](POLLUTION_PLAN.md): the standard, the release sources, the field and its step, the schedule, effects, ignition and blasts, saves, work packages WP1 to WP7, owner decisions.

## Open items

- Owner decisions D1 to D6 (plan, section 10).
- Approval of the implementation threads (WP1 to WP6).
- Three points to verify in the code before their package builds: where a removed device's contents are booked (S5), whether fuel voids have a flue stream (S8), where a safety valve's run ends (S9).
- The IDLH values of the plan's table are drafts; WP1 reads each from its NIOSH page.
