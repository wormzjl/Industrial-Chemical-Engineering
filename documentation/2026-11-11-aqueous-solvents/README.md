# Aqueous solvents

Status: **Planned** (2026-11-11). Branch `claude/ethanol-compound-matrix-4b318d`, cut from main `ff6f6892f` (0.26.0).

## Scope

Owner, 2026-11-11: add ethanol to the compound matrix, identify what it affects and propose a solution. Then: the task is infrastructure, because more compounds will follow; introduce binary interactions with water, only for solvents with a good water affinity; the design is the agent's call.

## Documents

- [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md): the physics, what the code does, what the addition touches, the first options (A to E) for ethanol with water.
- [AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md): the measurements, the design (M1 to M7: the Huron-Vidal mixing rule in the PR78 kernel, one water in the equation of state with its own share of enthalpy and volume from IF97, and the free-water rule removed, group counts per compound with every pair derived at load, three fluid phases from the physics, gas solubility in water, hydrocarbons and polar organics), the speed design, work packages W1 to W6.

## History

- 2026-11-11: review written (15ddccd86). No code or data changed; no test run.
- 2026-11-11: owner direction (infrastructure, water affinity). Measured ethanol and water at 1 atm: PR78 at its best k_ij has no azeotrope, NRTL is within 0.25 K. Plan written; batch folder renamed from `2026-11-11-ethanol-compound`. No test run.
- 2026-11-11: owner: "The main problem is how you deal with mixtures, also water-ethanol need to work with both the still and distillation column." The aqueous-phase design (D1 to D8) cannot serve the V3 column and is withdrawn. Measured PR78 with the Huron-Vidal rule and water on the cubic: 0.18 K and y 0.004 at 1 atm with energies linear in T. Plan rewritten (M1 to M5). No test run.
- 2026-11-11: owner: the three-phase hold "is not real and needs to be resolved"; no binary parameters per compound ("polarity or UNIQUAC estimation? suggest"); neural networks per combination later. Measured pairs derived from modified UNIFAC (Dortmund) group counts through the equation of state: ethanol and water 0.12 K with the right pressure trend, the ethanol and n-hexane azeotrope within 1.1 K, oil and water two liquids. Plan's third form (M3, M4, M6). No test run.
- 2026-11-11: owner: three phases "physics-first"; water and hydrocarbon solubility "needs to be dealt with"; gas solubility to be evaluated. Measured: a water-hydrocarbon block fitted to n-hexane predicts n-octane within a factor 1.5; water and n-hexane boil together at 61.4 C (measured 61.6 C); the ternary with ethanol at 57.0 C (56.0 C); CO2 and N2 in water from their Henry constants (the classical rule: 6.5 and 90 times too insoluble). Plan's fourth form (sections 4 and 5). No test run.
- 2026-11-11: owner: "How about solubility in organics?"; "I'm open to breaking changes to the thermo engine. It needs to be fast and accurate with all the additional loads." Measured gases at 25 C: in n-hexane E-PPR78 group counts give 1.05 to 1.32 times the measured solubility (zero pairs 1.17 to 2.07; the authored library 1.02 to 1.20); in ethanol the homomorph rule 0.85 to 0.93. Measured water on the cubic to 300 C: saturation 0.54 %, heat of vaporization 3.3 %, liquid density -13 %, liquid heat capacity +22 %. Plan's fifth form: one water in the equation of state with an IF97 share, the free-water rule removed, pairs for nonpolar compounds from E-PPR78, the speed design (section 6). No test run.
- 2026-11-11: owner: "Prototype first". `water-share.mjs`: equilibrium from the cubic alone; water's share of enthalpy and volume from Region 1 in a liquid-like phase and from IF97's second virial coefficient at water's partial pressure in a gas-like one, weighted by the phase's own v/b. Pure liquid water is Region 1 up to 350 C; the heat of vaporization is 0.03 % off at 100 C, 0.79 % at 200 C, 5.0 % at 300 C (today 0.03, 0.93, 8.5 %); no jump on 14 isobars, 16 isotherms and a CO2 and water mixture through its critical region. The form first written (a Gibbs energy difference per label) is replaced: it jumps at a label flip. Pure water's saturation pressure moves from Wagner-Pruss by up to 0.54 %. No test run.
- 2026-11-11: owner approved the W1 and W2 subagents (Opus medium). W2 split: W2a (data, group tables, activity model, import; started with W1) and W2b (the derivation, after W1, because it runs through the kernel's new rule).

## Tools

- [tools/aqueous-solvents/](../../tools/aqueous-solvents/README.md): the bubble point comparisons, the derived pairs, gases in organic liquids, water on the cubic, water's share.

## Code areas

None yet. Named by the plan: `science/thermo/PengRobinsonKernel`, `science/material/MaterialCatalog`, `science/thermo/phase/` (`WaterParticipation`, `PhaseContract`), `science/fluid/thermo/`, `science/fluid/network/StillColumn`, `science/column/v3/thermo/`, `tools/compound-import/`, `src/main/resources/data/icheme/materials/`.

## Open items

- W1 and W2a: subagents started 2026-11-11; their diffs are uncommitted until checked. W2b to W6 not started.
- Henry coefficients (W2a) were typed from memory of IAPWS G7-04: check against the guideline before W2b uses them.
- Owner: the creative-source ethanol preset, yes or no.
- Water's share (plan M2): proven offline for pure water and one mixture path; its derivatives, entropy, ice below 0 C and cost are W3a's.
- Saturated steam above 250 C (heat of vaporization 2 to 13 % off; today 3 to 33 %): Region 2 in full at water's partial pressure would close it; not in this batch.
- Owner: hydrogen pairs stay zero (rule of 2026-11-04) or take their E-PPR78 values; retire the authored libraries when the column's networks are retrained, or keep them.
- CO2 in water at 5 MPa is 40 % high: a second per-gas number, evaluated in W2.
- The water-hydrocarbon block's anchors and their temperature dependence: literature data to source in W2.
