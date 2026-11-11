# Aqueous solvents

Status: **Planned** (2026-11-11). Branch `claude/ethanol-compound-matrix-4b318d`, cut from main `ff6f6892f` (0.26.0).

## Scope

Owner, 2026-11-11: add ethanol to the compound matrix, identify what it affects and propose a solution. Then: the task is infrastructure, because more compounds will follow; introduce binary interactions with water, only for solvents with a good water affinity; the design is the agent's call.

## Documents

- [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md): the physics, what the code does, what the addition touches, the first options (A to E) for ethanol with water.
- [AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md): the PR78 against NRTL measurement, decisions D1 to D8 (the free water becomes an aqueous phase with NRTL activity coefficients), work packages W1 to W6.

## History

- 2026-11-11: review written (15ddccd86). No code or data changed; no test run.
- 2026-11-11: owner direction (infrastructure, water affinity). Measured ethanol and water at 1 atm: PR78 at its best k_ij has no azeotrope, NRTL is within 0.25 K. Plan written; batch folder renamed from `2026-11-11-ethanol-compound`. No test run.

## Tools

- [tools/aqueous-solvents/](../../tools/aqueous-solvents/README.md): the bubble point comparison.

## Code areas

None yet. Named by the plan: `science/material/MaterialCatalog`, `science/thermo/phase/FluidTpEquilibrium` (`solveWet`), `science/fluid/thermo/NetworkPhaseEngine`, `science/fluid/network/StillColumn`, `tools/compound-import/`, `src/main/resources/data/icheme/materials/`.

## Open items

- W1 to W6 of the plan; none started.
- Owner: the creative-source ethanol preset, yes or no.
