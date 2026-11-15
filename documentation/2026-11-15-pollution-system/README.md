# 2026-11-15 pollution system

Status: In progress since 2026-11-15. D1 to D6 decided; WP1 and WP2 in work. Branch `claude/pollution-system-a8e213`, cut from `main` at `e9edc974b` (0.31.0).

Purpose: air pollution in 16 × 16 × 16 cubes: toxic, combustible, corrosive and particulate hazard indices from what the fluid network releases, spreading and decaying on the 5 s engine, with effects on players and mobs and vapour cloud explosions at flame sources.

Lane: new packages `science/pollution` and `runtime/pollution`. Hot files touched, each in one work package: `MaterialCatalog` (WP1), `ProcessSolveJobs` (WP3), `ShaftTrains` and `FluidWorldAuthority` (WP4), `EquipmentStructure` and the equipment blocks (WP6). Class: new feature; no reference moves.

## History

- 2026-11-15, plan: survey of the release paths (`BoundaryTransfer` kinds, chimneys, flares), the material records (`flammability` exists, no model reads it), the explosion handling of equipment (ignored today) and the store patterns; the standard (NIOSH IDLH, GHS H314, LFL with Le Chatelier); [POLLUTION_PLAN.md](POLLUTION_PLAN.md). No code, no test run.

- 2026-11-15, owner: "also needs to be crash-proof when world corruption happens". Plan section 8: a damaged store is set aside and the dimension starts empty, a damaged cube is dropped alone, a run-time failure keeps the last field and at worst turns pollution off; the world is never refused for it.

- 2026-11-15, owner: "Yes, start". D1 to D6 decided as recommended (breach and repair; torches, candles and campfires ignite; a spill's volatile share; four channels per cube; half-lives 5 and 60 process minutes; no asphyxiants). WP1 (hazard sub-record, data, `HazardStandard`) and WP2 (`PollutionField`, ledger, `ExplosionLaw`) started as two Opus medium subagents in this worktree, on separate files, no builds.

- 2026-11-15, owner: "Pipe does not release under normal circumstance, unless they are destroyed by explosion (they don't get a damage meter for the sake of simplicity)". Plan S5 and section 7: a pipe releases its contents only when a blast destroys it, and a blast destroys it outright; breaking or dismantling by a player releases nothing (read by the main thread to hold for vessels too). S5 moves from WP4 to WP6.

## Documents

- [POLLUTION_PLAN.md](POLLUTION_PLAN.md): the standard, the release sources, the field and its step, the schedule, effects, ignition and blasts, saves, work packages WP1 to WP7, owner decisions.

## Open items

- WP1 and WP2: the main thread builds, runs their tests and commits.
- Approval of the implementation threads for WP3 to WP6.
- Three points to verify in the code before their package builds: where a removed device's contents are booked (S5), whether fuel voids have a flue stream (S8), where a safety valve's run ends (S9).
- The IDLH values of the plan's table are drafts; WP1 reads each from its NIOSH page.
