# Column internal hydraulics (2026-11-17)

**Status:** Planned since 2026-11-17, on branch `claude/column-testing-sizing-26b5b9`. Class: PHYSICS. Lanes: plant,
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

## Documents

- [COLUMN_HYDRAULICS_PLAN.md](COLUMN_HYDRAULICS_PLAN.md)

## Open items

- Owner decisions O1 (the overhead's drop budget), O2 (the pumparound's return floor), O4 (the outlet ports' head), O3 (the order against batch
  `2026-11-16-solute-reaction-system` in the hot files, and the reservation of state format 54 and facts schema 31).
- The plan's overhead flow and pressure drops are estimates; work package 1 solves them.
