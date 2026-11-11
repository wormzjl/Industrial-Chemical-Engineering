# Aqueous solvents: the infrastructure plan

Batch [2026-11-11-aqueous-solvents](README.md), 2026-11-11, at main ff6f6892f (0.26.0). The first review is [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md). This is the plan's third form; section 7 lists what the earlier forms were and why each left.

## 1. Owner direction (2026-11-11)

- The task is infrastructure: more water-miscible compounds will follow ethanol. The design is the agent's call.
- "The main problem is how you deal with mixtures, also water-ethanol need to work with both the still and distillation column."
- A hold where three fluid phases meet "is not real and needs to be resolved".
- "Individual neural networks will be developed for different combo": the column's learned start is not this batch's.
- "The main problem is mixture LLE, I don't like adding binary interaction parameters for each compounds as this will be almost impossible to work with in a game. Some simplification using polarity or use UNIQUAC estimation? suggest".

## 2. Measurements

Scripts in `tools/aqueous-solvents/`. Ethanol and water against the 15 points of Perry's table at 101.325 kPa; the azeotrope at 20 and 300 kPa against the temperature-dependent NRTL.

| Model | Pair data | AAD T, K | AAD y | Azeotrope, 101 kPa | 20 / 300 kPa |
|---|---|---|---|---|---|
| Measured (NRTL at the other pressures) | | | | 0.894 at 78.15 C | 0.928 / 0.868 |
| PR78, classical rule | best k_ij | 1.56 | 0.020 | none | |
| PR78, Huron-Vidal rule | two energies fitted at 1 atm | 0.10 | 0.003 | 0.910 | 0.834 / 0.976: wrong trend |
| PR78, Huron-Vidal rule | energies linear in T, fitted | 0.18 | 0.004 | 0.906 | 0.958 / 0.860 |
| Group model inside the rule (MHV1) | none | 0.72 | 0.009 | 0.864 | 0.868 / 0.876: flat |
| **PR78, Huron-Vidal rule** | **none: derived from group counts** | **0.12** | **0.004** | **0.906 at 78.1 C** | **0.928 / 0.878** |

The last row, on the other checks (nothing fitted to them):

| Check | Derived model | Measured |
|---|---|---|
| 10 % ABV wash | 92.5 C, y 0.258 | about 93 C, y about 0.25 |
| Ethanol and n-hexane azeotrope, 1 atm | 0.318 at 59.8 C | 0.332 at 58.7 C |
| Ethanol and n-hexane at 25 C | one liquid | one liquid |
| Water and n-hexane at 25 C | two liquids; hexane in water 1.4e-4, water in hexane 9.1e-3 | 2.5e-6, 5.3e-4 |
| Ethanol 0.2, water 0.4, n-hexane 0.4 at 25 C | two liquids; 88 % of the ethanol in the aqueous one | (group model: 94 %) |

Water on the cubic with a Mathias-Copeman alpha: saturation within 0.012 % from 0 to 200 C; heat of vaporization +0.7 % at 100 C; liquid heat capacity within 1.5 %.

Not measured, estimated by hand: a polarity model (Hansen parameters in a regular solution) gives 7.5 mol % water in hexane (measured 0.05) and an activity coefficient of water in ethanol of 1.5 (measured 2.5). It has no term for hydrogen bonds' asymmetry.

## 3. The design

- **M1. One equation of state, one mixing rule, in the one kernel.** `PengRobinsonKernel` gains the Huron-Vidal rule: a/b = Σ x_i a_i/b_i − g^E/Λ, g^E the NRTL form with local compositions weighted by b. A pair without polar data enters with α = 0 and energies set from its k_ij: exactly today's classical rule. A mixture with no polar pair keeps today's plan and numbers bit for bit.
- **M2. Water is a cubic component where a polar compound is.** A view (an island's list, a sheet's, a column's) that holds a polar compound carries `Water` on the cubic. A view without one keeps the free-water rule and IF97.
- **M3. A compound carries group counts, never pair parameters.** Each dataset lists its modified UNIFAC (Dortmund) groups; ChemSep's records already hold them, so the import writes them. A crude cut is CH2 groups by its molar mass. A compound is *polar* when it has a group outside the hydrocarbon ones.
- **M4. Pairs are derived, not authored.** For a pair with a polar compound, the catalog derives three numbers when a view first needs it: the two energies and α, by least squares on both activity coefficients of the group model across the composition range at 25 C, the energies again at 100 C (linear in T between). The fit runs through the equation of state itself, so the rule's own distortion is fitted out (the "inside the rule" row of section 2 is what happens otherwise). A derived pair has as many liquids as the group model. Hydrocarbon pairs stay on their k_ij. The group table is one record (`group_interactions`, as E-PPR78's), imported from DWSIM's `modfac.txt` and `modfac_ip.txt`.
- **M5. A per-compound alpha.** `models.pr78.alpha` (Mathias-Copeman); absent, PR78's own, the same arithmetic.
- **M6. Three fluid phases.** The engine answers a vapour and two liquids: where a product of the two-phase split is unstable, a third phase starts from the stability test's stationary point, and the three are solved by the multiphase Rachford-Rice with the engine's second-order finish. The network's vessel already has three layers (gas, liquid, water); in a polar view the third is a cubic liquid with a composition.
- **M7. Column start.** V3 takes the classical seed in a polar view until the owner's networks for each combination exist.

Why groups and not the two alternatives asked about: UNIQUAC needs two fitted numbers per pair, and UNIFAC is its estimate from groups, which is M3 and M4. Polarity alone fails on water (section 2).

## 4. What each consumer needs

| Consumer | Today | With M1 to M7 |
|---|---|---|
| V3 column | `V3ThermoModel` on the kernel; water outside the axis | In a polar view water is an ordinary component; no free-water channel there; live steam is a water feed. A tray with two liquids warns, as a wet tray does today |
| Batch still | `StillColumn`: K from `HydrocarbonModel`'s ln φ | The same code on a polar view |
| Engine | Free-water rule; two fluid phases | Water in the equation of state for a polar view; three fluid phases (M6), with its PH and UV |
| Network | `PhaseLayout`: per-compound liquid and vapour, and two water unknowns | A second liquid with per-compound unknowns in a polar view's vessels; phase ports, sight glass, boots on it |
| Islands | Each on its own list | A list's first polar compound restates the island: amounts and temperature kept, the energy difference booked |
| Simulator | Sheet's own list | The same views |

## 5. Work packages

| | Package | Proves it |
|---|---|---|
| W1 | Kernel: per-compound alpha; the Huron-Vidal plan with analytic dZ, d ln φ/dT, d ln φ/dn, residual enthalpy | Finite-difference oracle; equal to the classical rule at α = 0 within 1e-12; existing kernel tests unchanged |
| W2 | Catalog: group counts on datasets, the group table record, the derivation (M4) through `DeterministicMath`, water's cubic dataset, a view's water treatment, identities by declared revisions (the table's, the datasets', the derivation's own); the import writes groups | A gate on section 2's two tables; `MaterialCatalogTest`; older identities unmoved |
| W3a | Engine: water in the equation of state; three fluid phases in TP, PH and UV | New three-phase cases against the offline script; `FluidTpEquilibriumP3Test`; campaign counters |
| W3b | Network: the second liquid in `PhaseLayout` and the step solver, the restatement at a list's first polar compound, ports and displays | `NetworkPhaseEngineTest`, fluid GameTests, the hold-fuzz campaign |
| W4 | Still on a polar view | `BatchStillTest`: a 10 % wash starts near 92.5 C with y near 0.26 |
| W5 | V3 column on a polar view: package, feed flash, classical seed, the simulator's unit and sizing, the running column | An ethanol and water rectifier against an offline McCabe-Thiele on the same model |
| W6 | Ethanol: records with groups, preset, names, heating value | Section 3 of the first review |

Order: W1 and W2 first (disjoint files, no behaviour changes). W3b gets its own design document before code: it changes the step solver's unknowns.

Risks and limits:
- Water and hydrocarbons: the Dortmund table makes them too soluble in each other (15 to 60 times, section 2). Both layers still read as pure in the game. The remedy, if wanted, is in the group table (five group pairs), not per compound.
- The derivation is a small fit at load; it must be deterministic and bounded, and a pair it cannot fit is refused by name.
- The booking at a list's first polar compound is up to 0.7 % of water's heat of vaporization at 100 C.
- Gases (N2, CO2, H2S, H2) have no published Dortmund groups here: their pairs with polar compounds stay on k_ij.

## 6. Adding a compound afterwards

One compound record and one dataset with its PR78 constants and group counts, both written by `tools/compound-import` from ChemSep. No pair record, no fit, no code.

## 7. Earlier forms

- First (6acf83051): the free water as an aqueous phase with NRTL activity coefficients beside PR78. It cannot serve the V3 column, which has one liquid on the cubic axis.
- Second (885360f2b): this design with authored pair records (`huron_vidal`), fitted per pair by a tool, and a hold at three fluid phases. The owner refused both.
