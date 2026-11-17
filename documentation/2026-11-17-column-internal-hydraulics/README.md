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
- 2026-11-17: the owner answered O6 with a three-phase separator model: a drum gives clean oil and water only when properly sized, else oil in the water and water in the oil. `SEPARATOR_PLAN.md`: the ideal settler on the solute batch's settling speed (its plan 8.1 leaves the carried water out), Souders and Brown for the gas, the held receiver in the cycle, the sizer's rows; options S1 to S4. No code.
- 2026-11-17: the owner: the gas law works with a vertical drum as well, and the desalter is based on this model, different by its higher coalescence. Separator plan: law 3 for standing and lying vessels with a table (a standing 1.0 m drum on the whole overhead draws 26 % bulk, 1.5 m is clean); section 4.1, the desalter as this separator with its own drop size; S4 changed (the separator before the solute batch's WP6), S5 added (the salt against the water). No code.

## Documents

- [COLUMN_HYDRAULICS_PLAN.md](COLUMN_HYDRAULICS_PLAN.md)
- [SEPARATOR_PLAN.md](SEPARATOR_PLAN.md)

## Open items

- Owner decisions O5 (the boundary lines' drop, and the inlets), O7 (an exchanger side's internal bypass); O6 is answered by the separator plan.
- Separator plan: options S1 (the liquids' form), S2 (the amount carried), S3 (the gas), S4 (the order: before the solute batch's WP6), S5 (the desalter's salt against its water).
- The solute batch's plan (8.1, D9, section 10) is to rest on the separator plan's `Separation`; that batch's branch is not edited from here.
- The build waits for batch `2026-11-16-solute-reaction-system`'s column package (O3); state format 54 and facts schema 31 are reserved then.
- Work package 1b: whether a held member's rating is read against the design pressure (E11).
