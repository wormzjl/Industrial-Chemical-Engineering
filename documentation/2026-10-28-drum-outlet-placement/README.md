# 2026-10-28-drum-outlet-placement

Purpose: a lying boot drum's oil (organic) outlet leaves at the bottom of its front end, not mid-height on the front head (owner report 2026-10-28 with a screenshot of a 2 m x 6 m boot drum: "Horizontal drum organic outlet should on the bottom right, not on the right").

Status: Implemented 2026-10-28, merged to main without a version bump (owner: "merge no test no bump"; branch `claude/drum-outlet-placement-1df644`, base `ac572f12`). Index: [../INDEX.md](../INDEX.md).

## Physics and finding

- Expected: in a three-phase drum the oil spills over the baffle into the front compartment and drains through a bottom nozzle. The fluid model takes a port's height, so an outlet on the axis draws oil only once the level is at mid-drum; on the 2 x 6 drum (about 21 m3) about 10 m3 below it never drains.
- What the code did: `EquipmentShape.bootFor` checks the bottom outlet against a pipe's largest line (0.8 m, flange 1.0 m). On the 2 x 6 drum the only cell centre under the front end (the tangent line) leaves that flange 0.06 m from the front saddle, under the 0.15 m rule, so the outlet fell back to the head, at its lowest cell centre at least 0.3 m above the shell's bottom: the axis row on a 2 m drum.
- Sweep (all boot drums, `tools/drum-outlet-sweep`): 1219 boot drums, 1184 with the bottom outlet, 35 on the head; 8 of these have the cell beside the saddle within the head's flat bottom; 27 short drums have the saddle's block column under the head and keep the head outlet.

## Options shown to the owner (drawings in the session, 2026-10-28)

- A: facing down on the cell centre beside the saddle's block column, its largest line capped so the flange keeps 0.15 m from the saddle (0.5 m bore on the 2 x 6 drum). No new mechanism.
- B: facing down 3/32 m off the cell centre, full 0.8 m line; the off-centre face point of rack lanes extended to vessel nozzles, a transition pipe under it.
- Owner decision 2026-10-28: A. This approves the moved nozzle pipe block on the 8 drum sizes.

## Change

`bootFor` keeps today's cell where a full line fits; otherwise the cell beside the saddle's column, within 0.6 of the head's depth from the tangent line, with the largest bore in `NozzleBore.STEP`s whose flange (1.25 OD + 0.085 m) keeps the saddle clearance (`Boot.largest`). The nozzle's root and default size follow that line; `NozzleBore.largest` returns it for that outlet, so the laying and sizing tools refuse a larger line. Moved: 1.5x7, 2x6, 2.5x5, 3x4, 3.5x3, 5.5x7, 6x6, 6.5x5, each to a 0.5 m bore. Placed drums of these sizes refresh at load (new stamp); a pipe laid to the old head nozzle stays where it is and no longer meets the drum.

## Code areas

`science/equipment/EquipmentShape` (`Boot`, `bootFor`, the lying drum's `nozzles`), `science/equipment/NozzleBore.largest`. Tests: `ShapeRasterizerTest.aBootDrumsOilLeavesBetweenItsBootAndTheFrontHead`. Tool folder: [tools/drum-outlet-sweep](../../tools/drum-outlet-sweep/README.md). Code guide: [equipment.md](../reference/code-guide/equipment.md).

## History

- 2026-10-28: diagnosis, sweep and drawings of A and B; owner chose A. Commit `6588cdfe` (code and tests). Tests: `science.equipment.*`, `EquipmentRefreshTest`, `PipeAnchor*` (160, all pass; the layout golden table holds no boot drum, unchanged); quick suite `test` 1796/1796 on `6588cdfe`. `slowGates` not yet run (before a merge). No in-game check. Not merged, not pushed.
- 2026-10-28: merged to main on the owner's order "merge no test no bump": main (`f3fe505d`, device pages on big screens) merged into the branch (`75fbdaac`, CHANGELOG lines of both kept), then main fast-forwarded. No version bump; the CHANGELOG line stays under `[Unreleased]`. No test run after the merge of main; `slowGates` not run.

## Open items

- The 27 short boot drums still take the oil outlet on the front head, on the axis row up to 2.5 m across; an off-centre point could lower it (option B's mechanism). For the owner.
- The layout golden table records no boot drum, so it does not show this move.
