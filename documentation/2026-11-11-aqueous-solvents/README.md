# Aqueous solvents

Status: **In progress since 2026-11-11**. Branch `claude/ethanol-compound-matrix-4b318d`, cut from main `ff6f6892f` (0.26.0).

## Scope

Owner, 2026-11-11: add ethanol to the compound matrix, identify what it affects and propose a solution. Then: the task is infrastructure, because more compounds will follow; introduce binary interactions with water, only for solvents with a good water affinity; the design is the agent's call.

## Documents

- [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md): the physics, what the code does, what the addition touches, the first options (A to E) for ethanol with water.
- [AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md): the measurements, the design (M1 to M7: the Huron-Vidal mixing rule in the PR78 kernel, one water in the equation of state with its own share of enthalpy and volume from IF97, and the free-water rule removed, group counts per compound with every pair derived at load, three fluid phases from the physics, gas solubility in water, hydrocarbons and polar organics), the speed design, work packages W1 to W6.
- [W3A_ENGINE_PLAN.md](W3A_ENGINE_PLAN.md): the engine with water in the equation of state: the two books of a phase state (equilibrium on the cubic, balances with water's share), three fluid phases as one system, ice as a crystal, the gates.

## History

- 2026-11-11: review written (15ddccd86). No code or data changed; no test run.
- 2026-11-11: owner direction (infrastructure, water affinity). Measured ethanol and water at 1 atm: PR78 at its best k_ij has no azeotrope, NRTL is within 0.25 K. Plan written; batch folder renamed from `2026-11-11-ethanol-compound`. No test run.
- 2026-11-11: owner: "The main problem is how you deal with mixtures, also water-ethanol need to work with both the still and distillation column." The aqueous-phase design (D1 to D8) cannot serve the V3 column and is withdrawn. Measured PR78 with the Huron-Vidal rule and water on the cubic: 0.18 K and y 0.004 at 1 atm with energies linear in T. Plan rewritten (M1 to M5). No test run.
- 2026-11-11: owner: the three-phase hold "is not real and needs to be resolved"; no binary parameters per compound ("polarity or UNIQUAC estimation? suggest"); neural networks per combination later. Measured pairs derived from modified UNIFAC (Dortmund) group counts through the equation of state: ethanol and water 0.12 K with the right pressure trend, the ethanol and n-hexane azeotrope within 1.1 K, oil and water two liquids. Plan's third form (M3, M4, M6). No test run.
- 2026-11-11: owner: three phases "physics-first"; water and hydrocarbon solubility "needs to be dealt with"; gas solubility to be evaluated. Measured: a water-hydrocarbon block fitted to n-hexane predicts n-octane within a factor 1.5; water and n-hexane boil together at 61.4 C (measured 61.6 C); the ternary with ethanol at 57.0 C (56.0 C); CO2 and N2 in water from their Henry constants (the classical rule: 6.5 and 90 times too insoluble). Plan's fourth form (sections 4 and 5). No test run.
- 2026-11-11: owner: "How about solubility in organics?"; "I'm open to breaking changes to the thermo engine. It needs to be fast and accurate with all the additional loads." Measured gases at 25 C: in n-hexane E-PPR78 group counts give 1.05 to 1.32 times the measured solubility (zero pairs 1.17 to 2.07; the authored library 1.02 to 1.20); in ethanol the homomorph rule 0.85 to 0.93. Measured water on the cubic to 300 C: saturation 0.54 %, heat of vaporization 3.3 %, liquid density -13 %, liquid heat capacity +22 %. Plan's fifth form: one water in the equation of state with an IF97 share, the free-water rule removed, pairs for nonpolar compounds from E-PPR78, the speed design (section 6). No test run.
- 2026-11-11: owner: "Prototype first". `water-share.mjs`: equilibrium from the cubic alone; water's share of enthalpy and volume from Region 1 in a liquid-like phase and from IF97's second virial coefficient at water's partial pressure in a gas-like one, weighted by the phase's own v/b. Pure liquid water is Region 1 up to 350 C; the heat of vaporization is 0.03 % off at 100 C, 0.79 % at 200 C, 5.0 % at 300 C (today 0.03, 0.93, 8.5 %); no jump on 14 isobars, 16 isotherms and a CO2 and water mixture through its critical region. The form first written (a Gibbs energy difference per label) is replaced: it jumps at a label flip. Pure water's saturation pressure moves from Wagner-Pruss by up to 0.54 %. No test run.
- 2026-11-11: owner approved the W1 and W2 subagents (Opus medium). W2 split: W2a (data, group tables, activity model, import; started with W1) and W2b (the derivation, after W1, because it runs through the kernel's new rule).
- 2026-11-11: W1 (kernel): `PengRobinsonKernel` gains a Mathias-Copeman alpha per compound and the `HURON_VIDAL` plan (`HuronVidalExcess`), analytic derivatives, dense sums; the existing plans untouched. W2a (catalog data): `group_counts`, `henry_in_water` and `models.pr78.alpha` on datasets, in no identity; the Dortmund table with the water-hydrocarbon block and `ModifiedUnifac`; `HenryConstant`; the E-PPR78 table moved from the pilot overlay to main; 33 datasets got counts, 8 gases Henry coefficients (checked against Table 2 of IAPWS G7-04). Gate: 14 test classes, 92 tests, pass (`test --tests`, 4 workers, second lane). Owner on the kernel's kept constructors and plans: "no need for backward compat".
- 2026-11-11: owner approved the W2b subagent (Opus medium). Measured for its brief: the homomorph rule needs the homomorph's own critical constants (with its groups but ethanol's constants: N2 2.66, CH4 2.08, CO2 1.16 times measured), so a polar compound's dataset names a bundled homomorph.
- 2026-11-11: W2b (derivation): `DerivedPairs.derive(catalog, package)` builds a list's Huron-Vidal kernel with one source per pair (library, hydrogen zero, Henry, Dortmund activity fit, homomorph, E-PPR78, missing = zero and listed); the kernel's classical pairs take k(T) (a `PairInteractions` model, or linear between two temperatures); `homomorph` on datasets; counts authored for cyclohexane, propylene, butene-1, isobutene, butadiene (E-PPR78) and ethylene (Dortmund); a pilot list in the test overlay `pilot-aqueous` (cubic water, ethanol). Through the Java kernel: ethanol and water mean |dT| 0.233 K, |dy| 0.0038, azeotrope 0.896; ethanol and n-hexane one liquid, azeotrope 0.314; water and n-hexane 2.10e-6 and 9.46e-4; CO2 and N2 in water within 0.1 % of their Henry constants; N2, CH4, CO2 in ethanol within 5 % of the offline values. Deriving the refinery list with water (46 compounds, 1035 pairs): 0.93 s, 58 fits; 138 pairs have no source (E-PPR78 has no value for CO, O2, NH3, COS, SO2 and SH against some groups) and stay zero as today. Gate: 16 test classes, 109 tests, pass (`test --tests`, 4 workers, second lane).
- 2026-11-11: W3a design written (W3A_ENGINE_PLAN.md). Decided: equilibrium and crystal anchors read the cubic alone, the balances add water's share; the three-slot system is Michelsen's convex multiphase Rachford-Rice, whose optimality conditions are the presence rows; ice is water's crystal and two crystals may form at once (owner decision 6 A kept); the network stays on the free-water rule until W3b, which deletes it. No code, no test run.

## Tools

- [tools/aqueous-solvents/](../../tools/aqueous-solvents/README.md): the bubble point comparisons, the derived pairs, gases in organic liquids, water on the cubic, water's share.

## Code areas

Changed so far: `science/thermo/PengRobinsonKernel`, `HuronVidalExcess`; `science/material/MaterialCatalog`, `ModifiedUnifac`, `HenryConstant`; `data/icheme/materials/properties`, `group_interactions`; `tools/compound-import`. Named by the plan: `science/thermo/PengRobinsonKernel`, `science/material/MaterialCatalog`, `science/thermo/phase/` (`WaterParticipation`, `PhaseContract`), `science/fluid/thermo/`, `science/fluid/network/StillColumn`, `science/column/v3/thermo/`, `tools/compound-import/`, `src/main/resources/data/icheme/materials/`.

## Open items

- W3a to W6 not started. `columnGates` and `equipmentGates` load the changed material data and have not run; they are the final lane's.
- Kernel: one rule for every list (the owner needs no backward compatibility): collapse the classical plans into the Huron-Vidal form once its sums run over polar compounds only and W3a can count the cost.
- Kernel: a large positive excess energy can make the mixture attraction negative (the root selection then throws); W3a's rejected-trial handling.
- Derivation: a pair with one liquid where the group model has two is kept with a notice (methyl mercaptan with three crude cuts); 138 refinery pairs have no E-PPR78 value and stay zero; ethanol and water's mean |dT| 0.233 K is close to its 0.25 K bound (offline 0.12 K with the earlier water alpha).
- Ethanol and the cubic water dataset are in the test overlay only; they move to main data with W3a and W6.
- Ethanol's and methanol's PPR78 group 41 has no E-PPR78 name: as planned, a polar compound takes the homomorph rule.
- The block's anchor solubilities are still from memory (the record says so).
- Licence: the E-PPR78 table now ships in the jar; its supporting-information file states no licence (DATA_LICENSE_REVIEW.md item for a release).
- Owner: the creative-source ethanol preset, yes or no.
- Water's share (plan M2): proven offline for pure water and one mixture path; its derivatives, entropy, ice below 0 C and cost are W3a's.
- Saturated steam above 250 C (heat of vaporization 2 to 13 % off; today 3 to 33 %): Region 2 in full at water's partial pressure would close it; not in this batch.
- Owner: hydrogen pairs stay zero (rule of 2026-11-04) or take their E-PPR78 values; retire the authored libraries when the column's networks are retrained, or keep them.
- CO2 in water at 5 MPa is 40 % high: a second per-gas number, evaluated in W2.
- The water-hydrocarbon block's anchors and their temperature dependence: literature data to source in W2.
