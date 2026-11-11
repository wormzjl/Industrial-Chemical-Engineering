# Aqueous solvents: the infrastructure plan

Batch [2026-11-11-aqueous-solvents](README.md), 2026-11-11, at main ff6f6892f (0.26.0). The first review is [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md). This is the plan's second form; section 6 says what the first was and why it left.

## 1. Owner direction (2026-11-11)

- The task is infrastructure: more water-miscible compounds will follow ethanol. Binary interactions with water, only for solvents with a good water affinity. The design is the agent's call.
- "The main problem is how you deal with mixtures, also water-ethanol need to work with both the still and distillation column." Read as: the equation of state is not the problem, its mixing rule is; and one model must serve the still and the V3 column.

## 2. Measurement

`tools/aqueous-solvents/`: bubble points of ethanol and water against the 15 points of Perry's table at 101.325 kPa, and the azeotrope at 20 and 300 kPa against the temperature-dependent NRTL.

| Model | AAD T, K (max) | AAD y (max) | Azeotrope, 101 kPa | 10 % ABV wash | Azeotrope, 20 / 300 kPa |
|---|---|---|---|---|---|
| Measured (NRTL at the other pressures) | | | 0.894 at 78.15 C | about 93 C, y 0.25 | 0.928 / 0.868 |
| PR78, classical rule, best k_ij (-0.10) | 1.56 (2.34) | 0.020 (0.053) | none | 92.2 C, y 0.31 | |
| NRTL activity, ideal vapour (first plan) | 0.25 (0.86) | 0.007 (0.014) | 0.897 at 78.17 C | 92.6 C, y 0.26 | 0.929 / 0.868 |
| PR78, Huron-Vidal rule, constant energies | 0.10 (0.47) | 0.003 (0.008) | 0.910 at 78.17 C | 93.0 C, y 0.25 | 0.834 / 0.976: wrong trend |
| PR78, Huron-Vidal rule, energies linear in T | 0.18 (0.76) | 0.004 (0.008) | 0.906 at 78.15 C | 92.6 C, y 0.26 | 0.958 / 0.860 |

Water on the cubic, with a Mathias-Copeman alpha fitted to its saturation line from 0 to 200 C: saturation within 0.012 % (PR78's own alpha: up to 20.7 %); heat of vaporization +0.1 % at 25 C, +0.7 % at 100 C, +2.2 % at 200 C; liquid heat capacity 74.2 to 75.2 J/mol K (measured 75.3 to 75.9).

Conclusions: PR78 fails only by its classical mixing rule. With the Huron-Vidal rule it is better at 1 atm than the activity model. The pair's energies must depend on temperature and be fitted over the working range. The azeotrope's composition is then within 1.2 mol % at 1 atm and 3 mol % at 20 kPa, where the curve is nearly flat (y within 0.011).

## 3. How mixtures are dealt with

- **M1. One equation of state, one mixing rule, in the one kernel.** `PengRobinsonKernel` gains the Huron-Vidal rule: a/b = Σ x_i a_i/b_i − g^E/Λ, g^E the NRTL form with local compositions weighted by b. A pair without a polar record enters with α = 0 and energies set from its k_ij; the rule is then exactly today's classical rule (Huron and Vidal 1979). The classical rule becomes the special case, and a mixture with no polar pair keeps today's plan and its numbers bit for bit.
- **M2. Water is a cubic component where a solvent is.** A view (an island's list, a sheet's, a column's) that holds a compound with a water pair record carries `Water` on the cubic, with the alpha of section 2. A view without one keeps the free-water rule and the IF97 water: crude, steam, cooling water and every reference stay as they are.
- **M3. "Good water affinity" is a record.** A compound mixes with water when a polar pair record names it with water. Hydrocarbons in such a view meet water through a classical k_ij; the engine's two-liquid answer separates them.
- **M4. Pair records.** `interactions` of model `huron_vidal`: two energies linear in T and α. `nrtl` leaves the method models (it never had a solver). A fit tool makes a pair from DWSIM's `nrtl.dat` and measured tables over 20 to 300 kPa, so a later solvent is data.
- **M5. A per-compound alpha.** `models.pr78.alpha` (Mathias-Copeman, three coefficients); absent, it is PR78's own, the same arithmetic.

## 4. What each consumer needs

| Consumer | Today | With M1 to M5 |
|---|---|---|
| V3 column | `V3ThermoModel` on the kernel; water outside the axis (steam profile, free water) | A solvent view gives it water as an ordinary component and the kernel's rule: no free-water channel in that view; live steam is a water feed. Classical seed (the neural model does not bind) |
| Batch still | `StillColumn`: K from `HydrocarbonModel`'s ln φ | The same code on a solvent view |
| Network engine | Free-water rule; two fluid phases at most | A third `WaterParticipation`: water in the equation of state; the fluid flash as it is (vapour and liquid, or two liquids) |
| Islands | Each on its own list | A list's first solvent restates the island: amounts and temperature kept, the energy difference booked (cubic water against IF97: section 2). Column ports carry moles at a temperature, so a stream keeps it |
| Simulator | Sheet's own list | The same views; flash units and the column unit follow |

## 5. Work packages

| | Package | Proves it |
|---|---|---|
| W1 | Kernel: per-compound alpha; the Huron-Vidal plan with analytic dZ, d ln φ/dT, d ln φ/dn and residual enthalpy | Finite-difference oracle; equal to the classical rule at α = 0 within 1e-12; existing kernel tests unchanged; a gate on section 2's table |
| W2 | Catalog: `huron_vidal` pair records, `alpha`, water's cubic dataset, a view's water participation, identities, M4's removal; the fit tool | `MaterialCatalogTest`, `RestrictedPackagePhysicsTest`; older identities unmoved |
| W3 | Engine and network: water in the equation of state for a solvent view, the restatement at a list's first solvent, phase names, the three-phase status | `FluidTpEquilibriumP3Test`, `NetworkPhaseEngineTest`, fluid GameTests, campaign counters |
| W4 | Still on a solvent view | `BatchStillTest`: a 10 % wash starts near 92.6 C with y near 0.26; the small column strengthens it toward the azeotrope |
| W5 | V3 column on a solvent view: package, feed flash, seed, the simulator's column unit and sizing, the running column | An ethanol and water rectifier against an offline McCabe-Thiele on the same model |
| W6 | Ethanol: records, hydrocarbon pairs, the fitted water pair, preset, names, heating value | Section 3 of the first review |

Order: W1 and W2 first (disjoint files, no behaviour changes), then W3, W4 and W5, then W6.

Risks and limits:
- Three fluid phases (a boiling organic liquid over an aqueous one) are not answered: the engine has two. A solvent island there holds with a status; a later batch.
- V3's classical seed is made for crude; a narrow-boiling azeotropic pair may need its own start (W5).
- Two water treatments, chosen by the list; the booking at a list's first solvent is up to 0.7 % of water's heat of vaporization at 100 C.
- A pair fitted at one pressure extrapolates with the wrong trend (section 2); the fit tool refuses a fit without points at two pressures.

## 6. The first form of this plan

Decisions D1 to D8 (6acf83051) kept water off the cubic and made the free water an aqueous phase with NRTL activity coefficients. It fits the network and cannot serve the V3 column: V3 has one liquid on the cubic axis and water outside it (18 classes read `V3WaterProperties`), so an aqueous tray liquid would be a second column model. Withdrawn on the owner's second message.
