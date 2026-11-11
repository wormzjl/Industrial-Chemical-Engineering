# W3a: the equilibrium engine with water in the equation of state

Batch [2026-11-11-aqueous-solvents](README.md), 2026-11-11, at dca788b79 + W2b (31459075f). Parent: [AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md) (M2, M6, section 4). The engine is `science/thermo/phase/` ([code guide](../reference/code-guide/equilibrium-engine.md)); its only production caller is the fluid network.

## 1. Expected

- At T and P a mixture settles where each compound has one chemical potential in every phase that holds it: a gas, up to two liquids, and pure crystals. A phase is absent exactly when the mole fractions it would take from those potentials sum to less than one.
- Water is a compound like the others: it dissolves a little in oil (5e-4 in n-hexane at 25 C), oil a little in it, gases by their Henry constants, ethanol in every proportion.
- Ice is water's crystal. It forms where water's potential in the fluid exceeds the ice's, so a solute lowers the freezing point (10 % ethanol by mass: about -4.5 C).
- A binary with three phases at a fixed pressure has one temperature (water and n-hexane at 1 atm: 61.6 C); the heat moves the amounts, not the temperature.

## 2. What the code does

- Water is outside the cubic (`WaterParticipation.SEPARATE_FREE_WATER`): a pure liquid from Region 1, steam as a second-virial gas sharing the hydrocarbon vapour's volume (Dalton), each hydrocarbon branch at its own pressure (`solveWet`, `PartialPressureClosure`, the per-branch stability test). Nothing dissolves either way.
- The hydrocarbon side has two fluid phases; a split with an unstable product is `UNSTABLE_PRODUCT` (a hold in the network).
- Ice is water's own rule (`FreeWaterIce`): a melting line of pure water, in every answer, beside any crystal competition.
- Nine named steps answer what TP leaves undetermined; five of them are water's (`wetCoexistence`, `waterBoiling`, `waterFreezing`, `freezingAtVolume`, `waterTriplePoint`).
- About 1,000 of the engine's 6,065 lines are the free-water rule (the spans of its methods; an estimate).

## 3. Proposal

### D1. Contract and basis

- `WaterParticipation.IN_EQUATION_OF_STATE`: water is a component of the fluid family. The request basis keeps its order (the other compounds, then `Water`), so requests do not change.
- A contract of this kind is built from a package through `DerivedPairs` (W2b). Its pilot is the test overlay's `pilot_aqueous`.
- During W3a both kinds exist: the network's packages stay on the free-water rule until W3b moves the step solver. The free-water code is deleted in W3b's last step, so the batch merges with one rule.

### D2. Two books in a phase state

Plan M2 keeps equilibrium on the cubic and takes water's own enthalpy, volume and entropy from IF97. That needs one explicit rule, or code will mix them:

- **The equilibrium book** is the cubic alone: ln φ, the Gibbs energy, and the h and s that give its slopes. Stability, the flash, the Newton finish, crystal anchors and onsets read only this book. It is self-consistent (g = h − T s), as today.
- **The balance book** adds water's share: `EquilibriumResult.enthalpy()`, `internalEnergy()`, `volume()`, the phases' volumes and entropies, and so the targets of the PH and UV searches. `PhaseState` gains the share as its own fields (h, s, v and their T and P derivatives); nothing adds it to g.
- The share is M2's: in a phase of v/b below 2.6, n_w (Region 1 − the cubic's pure liquid water) at max(P, saturation), faded from 350 C to the critical temperature; above v/b 3.6, (n_w²/n) P (IF97's second virial coefficient − the cubic's); a C2 blend between. Derivatives are analytic: Region 1's own (`WaterRegion1` already gives c_p, dv/dT, dv/dp), the pure-water cubic's, and the weight's through the phase's dv/dT and dv/dp.
- Water's volume translation is zero; the share is its density correction.
- A crystal of a compound with a share carries the liquid's share at its anchor as a constant, so melting it releases the record's heat of fusion on the balance book. For ice that is +5.9 kJ/kg.
- New fluid family id `pr78_huron_vidal_water_share_v1`; `CubicPhaseEvaluator` takes a prebuilt kernel (`TranslatedPengRobinson.residualOnly` on a kernel, not on a k matrix).

### D3. Three fluid phases at T and P

- Slots: vapour, liquid, second liquid. One system for all three (Michelsen's multiphase Rachford-Rice as a convex minimum over the phase fractions β ≥ 0): its optimality conditions are section 1's statement, β_k ≥ 0, 1 − Σ_i x_ik ≥ 0, product zero. No phase is added or removed by a test; the system states which are present.
- Outer loop as today: substitution on ln K with the eigenvalue extrapolation, then the Newton finish on the Gibbs energy over the mole numbers of the phases present (the existing Cholesky code, two blocks instead of one).
- Seeds: the Wilson vapour and liquid as today; the second liquid from pure water where the feed holds water, else from the polar compound of the largest amount. A list without a polar compound keeps today's two-slot path and cost bit for bit.
- The tangent-plane test stays as the audit of every product, with one added trial for lists with water (a water-rich trial; the pure-component trials stop at 8 compounds). An audit that fails reseeds the absent slot from its stationary trial, once; a second failure is `UNSTABLE_PRODUCT` as today.
- A substitution step whose phase the kernel refuses (the Huron-Vidal attraction can turn negative for a trial composition) is halved toward the last accepted composition, at most 8 times: a rejected trial, never a hold.
- New classification `VAPOR_LIQUID_LIQUID`. Two liquids are listed lighter first, as `LIQUID_LIQUID` does; the engine does not name one "aqueous" (a threshold on the water fraction would flip). W3b decides what a boot draws: the denser liquid.
- Cost: an absent slot still costs one phase evaluation per substitution step while its row is watched. It is dropped after 3 steps with Σ x below 0.9 and re-entered only by the audit. Counters decide the numbers (D7).

### D4. PH and UV

- The searches are over TP answers on the balance book and need no change of form.
- `wetCoexistence` becomes the three-phase step of any two-compound feed (the invariant point of D3 at the pressure, compositions from its solution), merged with `binaryThreePhase`.
- Pure water's steps are the generic ones: `pureCoexistence`, `pureCrystalStep`, `pureTriplePoint`. `waterFreezing`, `freezingAtVolume` and `waterTriplePoint` go.
- `waterBoiling` (water boiling beside an oil with almost no vapour pressure) stays as the same rule on the new phases: the three-phase range of such a feed is narrower than the search's resolution.

### D5. Ice

- Ice is the crystal of water (`crystals/ice_ih.json` exists), anchored like CO2-I at its triple point on the cubic's pure liquid. It competes under the crystal competition and is carried in a step (the network already carries ice as a liquid stand-in between refreshes, D23).
- Freezing-point depression and the melting line follow from the potentials; `FreeWaterIce` goes.
- Owner decision 6 A stands (ice and another crystal coexist), so the deposit solve takes two pure crystals at once. Today that is `CRYSTAL_PHASES`; it is one more unknown and one more row of the same kind.

### D6. Identity and grades

- `ThermoIdentity` keeps four parts: the package revision now includes the derivation's identity (`DerivedPairs.Result.identity`), the family is D2's, the water part is `water-in-eos-v1:` with the water dataset's and Region 1's revisions.
- The steam's qualified partial pressure (`vaporPartialPressureLimit`) stays the notice it is: above 250 C on saturation the latent heat is 2 to 13 % off.

### D7. Gates (all on `pilot_aqueous`, plus one cut)

| Gate | Bound |
|---|---|
| Pure water: saturation pressure against Wagner-Pruss; liquid density and enthalpy against Region 1; latent heat against IF97 | 0.6 %; 0.01 % up to 300 C; 0.1 % at 100 C, 1 % at 200 C |
| Water and n-hexane at 101.325 kPa: three phases at one temperature | 61.4 C within 0.5 K; vapour 0.79 hexane within 0.02 |
| Ethanol 0.2, water 0.4, n-hexane 0.4 at 101.325 kPa: three phases | bubble point 57.0 C within 1 K |
| Ethanol and water: one liquid at every composition, bubble points | W2b's bounds |
| CO2 dissolved in water and in ethanol at TP | W2b's bounds |
| 10 % ethanol by mass in water: ice onset | -3.5 to -5.5 C |
| PH and UV round trips (T, P to H or U, V and back) over the list, one, two and three phases, and through each step | 1e-6 of N R T, as `SPECIFICATION_TOLERANCE` |
| Conservation of every compound in every answer | rounding |
| H(T) at fixed P through a label flip of a one-root phase with water | no jump above 1e-9 of N R T |
| Derivatives of the share | finite differences, 1e-6 relative |
| Every existing engine, kernel and network test | unchanged, none re-recorded |
| Kernel evaluations per TP answer | dry lists: equal to today; the pilot list: recorded as W3b's baseline |

## 4. Steps

1. Evaluator: the kernel from `DerivedPairs`, the share's fields and derivatives, the family id (D1, D2).
2. TP: the three-slot system, the audit trial, the refused-step rule, the classification (D3).
3. PH and UV: the balance book in the searches, the merged three-phase step, pure water on the generic steps (D4).
4. Ice as a crystal; two crystals at once (D5).
5. Identity, grades, the gates of D7.

Files: `science/thermo/phase/**`, `science/fluid/thermo/TranslatedPengRobinson` (a constructor on a kernel), `science/thermo/TangentPlaneStability` (one trial), `science/material/` (contract wiring only), tests beside them, the pilot overlay. Not the network's step solver, not `PhaseLayout`, not main packages: W3b.

## 5. Risks and what is left out

- D2's two books are a rule a later change can break silently; a test states it (g of a state never moves with the share; the aggregates do).
- The convex form guarantees a unique phase split for fixed K values only; the outer loop can still stall near a three-phase critical end point. Those states go to the critical band's typed failure, as two-phase ones do.
- Two crystals at once is new code in the deposit solve.
- Not in W3a: the network's layout and step solver, deleting the free-water code, ethanol and the cubic water in main data, the kernel's sums over polar compounds only, Region 2 in full for the gas share.
- A simplification I did not take: one generic "split any closed bracket by the lever" in place of the named steps. It would delete several hundred lines, and it would also hide a model discontinuity as a step (the wet crude false root of 2026-11-02 was that). It needs its own review.

## 6. Result (2026-11-11)

Built by a sub-thread in six commits, 96945878b to 5830adcdc. Filtered run: 40 test classes, 213 tests, pass; every existing engine, kernel, qualification and network-engine test unchanged, none re-recorded. The full suite and the final lane have not run.

| Gate (D7) | Measured | Bound |
|---|---|---|
| Saturation pressure against IF97 Region 4, 0.01 to 300 C | 0.29 % worst | 0.6 % |
| Liquid density and enthalpy against Region 1 to 300 C | 0.0000 % | 0.01 % |
| Latent heat at 100 C, 200 C | 0.029 %, 0.79 % | 0.1 %, 1 % |
| Water and n-hexane, 1 atm | 61.14 C, vapour 0.794 hexane | 61.4 C within 0.5 K, 0.79 within 0.02 |
| Ethanol 0.2, water 0.4, n-hexane 0.4, 1 atm | 56.79 C, three fluid phases | 57.0 C within 1 K |
| Ethanol and water | one liquid; mean dT 0.233 K, dy 0.0038 | 0.25 K, 0.008 |
| CO2 in water, in ethanol (25 C, 1 atm) | 1.0011 of Henry, 1.036 of the reference | 5 %, a factor 1.3 |
| Ice onset, 10 % ethanol by mass | -4.03 C (pure water 273.1525 K) | -3.5 to -5.5 C |
| PH and UV round trips, 57 answers | within 1e-6 N R T | 1e-6 N R T |
| H(T) through a label flip (water 0.9, methane 0.1, 40 MPa) | 2.5e-15 N R T | 1e-9 |
| Share derivatives | 2.6e-7 relative | 1e-6 |

Kernel evaluations per TP answer on the pilot list (W3b's baseline): dry methane and n-hexane 87; water and n-hexane 117; ethanol and water at 80 C 52; the ternary at 58 C 282, at its bubble point 783; water and a crude cut 120. A list without a polar compound takes the two-slot path as before.

Deviations from sections 3 and 4:
- D5: two crystals form by alternating the two tested single-crystal solves to 1e-12, not by one more unknown in one solve.
- D5: ice keeps its record's volume on the balance book and takes the liquid's volume gap on the equilibrium book; without it the melting line had the wrong sign.
- D4: a UV search that closes on a jump with an end from an inexact volume solve restarts once with exact solves (failure path only).
- D7: the saturation gate reads IF97 Region 4, an independent reference.

Open, for W3b:
- A crystal beside two liquids or three fluid phases is still the `CRYSTAL_PHASES` hold. Ice under oil over water is that case, and today's free-water rule answers it: it must be closed before the network leaves the free-water rule.
- The ternary at its bubble point costs 783 kernel evaluations; the absent-slot rule and the seeds are where to look.
- Water 0.95 with CO2 0.05 at 30 MPa and 616 K answers three fluid phases; not checked against data.
- The UV restart rule may also matter for narrow-boiling hydrocarbon binaries.
