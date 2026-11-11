# Aqueous solvents

Status: **Planned** (2026-11-11). Branch `claude/ethanol-compound-matrix-4b318d`, cut from main `ff6f6892f` (0.26.0).

## Scope

Owner, 2026-11-11: add ethanol to the compound matrix, identify what it affects and propose a solution. Then: the task is infrastructure, because more compounds will follow; introduce binary interactions with water, only for solvents with a good water affinity; the design is the agent's call.

## Documents

- [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md): the physics, what the code does, what the addition touches, the first options (A to E) for ethanol with water.
- [AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md): the measurements, the design (M1 to M7: the Huron-Vidal mixing rule in the PR78 kernel, water on the cubic where a polar compound is, group counts per compound with pairs derived at load, three fluid phases from the physics, gas solubility), work packages W1 to W6.

## History

- 2026-11-11: review written (15ddccd86). No code or data changed; no test run.
- 2026-11-11: owner direction (infrastructure, water affinity). Measured ethanol and water at 1 atm: PR78 at its best k_ij has no azeotrope, NRTL is within 0.25 K. Plan written; batch folder renamed from `2026-11-11-ethanol-compound`. No test run.
- 2026-11-11: owner: "The main problem is how you deal with mixtures, also water-ethanol need to work with both the still and distillation column." The aqueous-phase design (D1 to D8) cannot serve the V3 column and is withdrawn. Measured PR78 with the Huron-Vidal rule and water on the cubic: 0.18 K and y 0.004 at 1 atm with energies linear in T. Plan rewritten (M1 to M5). No test run.
- 2026-11-11: owner: the three-phase hold "is not real and needs to be resolved"; no binary parameters per compound ("polarity or UNIQUAC estimation? suggest"); neural networks per combination later. Measured pairs derived from modified UNIFAC (Dortmund) group counts through the equation of state: ethanol and water 0.12 K with the right pressure trend, the ethanol and n-hexane azeotrope within 1.1 K, oil and water two liquids. Plan's third form (M3, M4, M6). No test run.
- 2026-11-11: owner: three phases "physics-first"; water and hydrocarbon solubility "needs to be dealt with"; gas solubility to be evaluated. Measured: a water-hydrocarbon block fitted to n-hexane predicts n-octane within a factor 1.5; water and n-hexane boil together at 61.4 C (measured 61.6 C); the ternary with ethanol at 57.0 C (56.0 C); CO2 and N2 in water from their Henry constants (the classical rule: 6.5 and 90 times too insoluble). Plan's fourth form (sections 4 and 5). No test run.

## Tools

- [tools/aqueous-solvents/](../../tools/aqueous-solvents/README.md): the bubble point comparisons.

## Code areas

None yet. Named by the plan: `science/thermo/PengRobinsonKernel`, `science/material/MaterialCatalog`, `science/thermo/phase/` (`WaterParticipation`, `PhaseContract`), `science/fluid/thermo/`, `science/fluid/network/StillColumn`, `science/column/v3/thermo/`, `tools/compound-import/`, `src/main/resources/data/icheme/materials/`.

## Open items

- W1 to W6 of the plan; none started.
- Owner: the creative-source ethanol preset, yes or no.
- Gas solubility in lists on the free-water rule (crude, steam): proposed as the next batch, by moving those lists onto water on the cubic.
- The water-hydrocarbon block's anchors and their temperature dependence: literature data to source in W2.
