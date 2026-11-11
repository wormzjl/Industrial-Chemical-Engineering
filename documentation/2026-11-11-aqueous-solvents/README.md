# Aqueous solvents

Status: **Planned** (2026-11-11). Branch `claude/ethanol-compound-matrix-4b318d`, cut from main `ff6f6892f` (0.26.0).

## Scope

Owner, 2026-11-11: add ethanol to the compound matrix, identify what it affects and propose a solution. Then: the task is infrastructure, because more compounds will follow; introduce binary interactions with water, only for solvents with a good water affinity; the design is the agent's call.

## Documents

- [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md): the physics, what the code does, what the addition touches, the first options (A to E) for ethanol with water.
- [AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md): the measurements, how mixtures are dealt with (M1 to M5: the Huron-Vidal mixing rule in the PR78 kernel, water on the cubic where a solvent is), what the column, the still and the network need, work packages W1 to W6.

## History

- 2026-11-11: review written (15ddccd86). No code or data changed; no test run.
- 2026-11-11: owner direction (infrastructure, water affinity). Measured ethanol and water at 1 atm: PR78 at its best k_ij has no azeotrope, NRTL is within 0.25 K. Plan written; batch folder renamed from `2026-11-11-ethanol-compound`. No test run.

- 2026-11-11: owner: "The main problem is how you deal with mixtures, also water-ethanol need to work with both the still and distillation column." The aqueous-phase design (D1 to D8) cannot serve the V3 column and is withdrawn. Measured PR78 with the Huron-Vidal rule and water on the cubic: 0.18 K and y 0.004 at 1 atm with energies linear in T. Plan rewritten (M1 to M5). No test run.

## Tools

- [tools/aqueous-solvents/](../../tools/aqueous-solvents/README.md): the bubble point comparisons.

## Code areas

None yet. Named by the plan: `science/thermo/PengRobinsonKernel`, `science/material/MaterialCatalog`, `science/thermo/phase/` (`WaterParticipation`, `PhaseContract`), `science/fluid/thermo/`, `science/fluid/network/StillColumn`, `science/column/v3/thermo/`, `tools/compound-import/`, `src/main/resources/data/icheme/materials/`.

## Open items

- W1 to W6 of the plan; none started.
- Owner: the creative-source ethanol preset, yes or no.
