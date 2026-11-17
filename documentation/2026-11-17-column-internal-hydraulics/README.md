# Column internal hydraulics (2026-11-17)

**Status:** Planned since 2026-11-17 (work package 1 done), on branch `claude/column-testing-sizing-26b5b9`. Class: PHYSICS. Lanes: plant,
flowsheet and control, runtime, client.

A running column does not read its internal lines' bores or its member pumps' sizes: a plant built too small runs as if
it were right. The owner asked for limits in the cycle (2026-11-17). The plan gives the physics per loop with numbers for
the review plant, the proposed laws, the work packages and the owner's decisions.

## History

- 2026-11-17: the plan. Its numbers changed the first proposal: a liquid line never nears the speed cap, and a pumparound
  pump's size sets the fall across its cooler, not the duty. In the review plant as built only the 0.05 m overhead line
  would limit a real plant (7 to 18 % of feed). No code.
- 2026-11-17: the owner added the outlets' head. An outlet port gives the design stage pressure wherever its boundary
  valve stands, so a valve 29 m up a riser still gives 2.0 bar. Plan sections 2.5 and 3 (law 4), work package 5, option O4.
  No code.
- 2026-11-17: the owner took O1 A, O2 A, O4 A and O3 (build after the solute batch's column package). Work package 1: `tools/column-hydraulics/ReviewPlantProbe.java` solved the plan's numbers (overhead 0.648 m3/s; the 0.05 m overhead passes 11 % under the 10 % budget, 30 % under the speed cap alone).
- 2026-11-17: the owner asked the same of the exchanger structure and for the fence's other exploits. The exchanger structure holds nothing (its sides have friction and their nozzles' heights in the network); the exchangers a column holds join its loops. Plan 2.6, 2.7 (E1 to E12), options O5 to O7.

## Documents

- [COLUMN_HYDRAULICS_PLAN.md](COLUMN_HYDRAULICS_PLAN.md)

## Open items

- Owner decisions O5 (the boundary lines' drop, and the inlets), O6 (the receiver's size), O7 (an exchanger side's internal bypass).
- The build waits for batch `2026-11-16-solute-reaction-system`'s column package (O3); state format 54 and facts schema 31 are reserved then.
- Work package 1b: whether a held member's rating is read against the design pressure (E11).
