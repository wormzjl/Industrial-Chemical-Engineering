# Aqueous solvents: the infrastructure plan

Batch [2026-11-11-aqueous-solvents](README.md), 2026-11-11, at main ff6f6892f (0.26.0). The first review is [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md).

## 1. Owner direction (2026-11-11)

- The task is infrastructure, not one compound: more water-miscible compounds will follow ethanol.
- Introduce binary interactions with water, only for solvents with a good water affinity.
- Questions: does the PR equation of state work for water and ethanol? Is NRTL needed?
- The design is the agent's call; decisions D1 to D8 below are taken on that word and are reversible until W3 lands.

## 2. Measurement

`tools/aqueous-solvents/ethanol-water-vle.mjs`: bubble points of ethanol and water at 101.325 kPa against the 15 points of Perry's table.

| Model | AAD T, K (max) | AAD y (max) | Azeotrope | 10 % ABV wash (x 0.033) |
|---|---|---|---|---|
| Measured | | | 0.894 at 78.15 C | about 93 C, y about 0.25 |
| PR78, k_ij = 0 | 14.5 (42.0) | 0.201 (0.701) | 0.587 at 73.97 C | 48.3 C, y 0.90 |
| PR78, best k_ij (-0.10) | 1.56 (2.34) | 0.020 (0.053) | none | 92.2 C, y 0.31 |
| NRTL, Antoine pressures | 0.25 (0.86) | 0.007 (0.014) | 0.897 at 78.17 C | 92.6 C, y 0.26 |
| NRTL, ethanol's reference from PR78 (D2) | 0.24 (0.83) | 0.007 (0.014) | 0.898 at 78.13 C | 92.6 C, y 0.26 |

- PR78 with its one k_ij fitted has no azeotrope: a column on it makes 100 % ethanol. It also puts water on the cubic, where pure water boils at 96.4 kPa at 100 C (-4.9 %) and 2.68 kPa at 25 C (-15 %).
- So: PR78 does not work for this pair, and an activity model is needed, for the liquid that holds the water only.

## 3. Decisions

- **D1.** Water stays off the cubic axis, on its own model (IF97, virial steam, ice). Gas and organic liquid stay PR78.
- **D2.** The free water becomes the *aqueous phase*: water and every compound that has a water pair record. In it, water's fugacity is x_w γ_w p_sat and a solvent's is x_i γ_i f_i, f_i the PR78 fugacity of the pure liquid solvent at (T, P). Pure solvent is then exactly the PR78 liquid, and pure water exactly today's rule. A solvent sits in the aqueous liquid, the organic liquid and the gas at one fugacity.
- **D3.** γ from NRTL. It builds a mixture of several solvents from binary pairs, the catalog already parses its pair records (`NrtlPair`, read by no solver), and the import tool already extracts DWSIM's `nrtl.dat`.
- **D4.** "Good water affinity" means: miscible with water in every proportion, not reacting with it, below its critical temperature. Ethanol now; methanol, isopropanol, acetone and the glycols fit. Out: partly miscible solvents (butanol: the organic layer would hold no water), ammonia, H2S, CO2, SO2 and the amines (ionic), acetic acid (dimers in the gas).
- **D5.** The aqueous phase's volume and enthalpy mix ideally. Neglected: excess volume up to 3.5 %, excess enthalpy up to 0.8 kJ/mol (2 % of the heat of vaporization).
- **D6.** A state without a solvent runs today's arithmetic and keeps its identity (`separate-free-water-v3`); only a view that holds a solvent takes the new one. No reference moves.
- **D7.** `nrtl` leaves the list of method models (it never had a solver); its pair records become the method's `aqueous` libraries. A pair's temperature range is a data range with a notice, not a load refusal.
- **D8.** The V3 column is not in this batch: a feed with a solvent and water solves on the free-water rule and warns.

## 4. Work packages

| | Package | Proves it |
|---|---|---|
| W1 | Catalog: method field `aqueous` (NRTL libraries), `Package.aqueous()` (solvents and pairs, restricted with a view), identities, D7; `tools/compound-import` reads `nrtl.dat` | `MaterialCatalogTest`, `RestrictedPackagePhysicsTest`; older identities unmoved |
| W2 | `science/thermo/phase/AqueousSolution`: γ and its derivatives in x and T through `DeterministicMath`, the PR78 reference fugacity, several solvents | New gate on section 2's table: AAD T under 0.5 K, azeotrope 0.89 to 0.90 |
| W3 | Engine: `solveWet`, its PH and UV, `solveWetWithCrystals` on the aqueous phase; ice against a_w p_sat | `FluidTpEquilibriumP3Test`, `FreeWaterIceTest`, new cases; campaign counters |
| W4 | Network: the aqueous phase with a composition in the phase layout, phase ports, sight glass and status notices | `NetworkPhaseEngineTest`, fluid GameTests |
| W5 | Batch still on the aqueous K values; the simulator's flash units; D8's warning | `BatchStillTest`: a 10 % wash distils near 93 C, y near 0.26 |
| W6 | Ethanol: records, pairs with hydrocarbons, the water pair, preset, names, heating value | Section 3 of the first review |

W1 and W2 change no behaviour and can start at once. W3 is the batch's risk: an outer loop on the aqueous composition round the closure of `solveWet`, judged by the campaign's counters and holds.

## 5. What comes with it

- Antifreeze: ice competes against a_w p_sat, so a solvent lowers the freezing point with no new rule.
- Ethanol, gasoline and water: ethanol divides between the organic and the aqueous liquid by its fugacity.
- Each later solvent is data only: a compound record and its water pair.
