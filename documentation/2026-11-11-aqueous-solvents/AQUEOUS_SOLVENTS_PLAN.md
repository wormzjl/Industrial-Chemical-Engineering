# Aqueous solvents: the infrastructure plan

Batch [2026-11-11-aqueous-solvents](README.md), 2026-11-11, at main ff6f6892f (0.26.0). The first review is [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md). This is the plan's fourth form; section 8 lists the earlier ones and why each left.

## 1. Owner direction (2026-11-11)

- The task is infrastructure: more water-miscible compounds will follow ethanol. The design is the agent's call.
- "The main problem is how you deal with mixtures, also water-ethanol need to work with both the still and distillation column."
- No binary interaction parameters per compound ("almost impossible to work with in a game"); polarity or a UNIQUAC estimate.
- Three fluid phases: a hold "is not real"; "this needs to be solved physics-first".
- Water and hydrocarbon mutual solubility "can cause issues and needs to be dealt with".
- "Gas solubility in liquid needs to be added. Evaluate if this can be done in current work package."
- Networks for the column's start will be developed per combination; not this batch's.

## 2. Measurements

`tools/aqueous-solvents/group-derived-pairs.mjs` (DWSIM's tables in `tmp/dwsim-groups/`); earlier rows from the other two scripts. Nothing below is fitted to the data it is compared with, except where "fitted" is written.

Ethanol and water, 15 points of Perry's table at 101.325 kPa:

| Model | Pair data | AAD T, K | AAD y | Azeotrope | At 20 / 300 kPa |
|---|---|---|---|---|---|
| Measured (NRTL at the other pressures) | | | | 0.894 at 78.15 C | 0.928 / 0.868 |
| PR78, classical rule | best k_ij | 1.56 | 0.020 | none | |
| Group model inside the rule (MHV1) | none | 0.72 | 0.009 | 0.864 | 0.868 / 0.876 |
| **PR78, Huron-Vidal rule, pairs derived from group counts** | **none** | **0.12** | **0.004** | **0.906 at 78.1 C** | **0.928 / 0.878** |

The derived model on the other checks:

| Check | Derived model | Measured |
|---|---|---|
| Ethanol and n-hexane azeotrope, 1 atm; at 25 C | 0.318 at 59.8 C; one liquid | 0.332 at 58.7 C; one liquid |
| Water with n-hexane at 25 C, the table as published (hexane in water, water in hexane) | 1.4e-4, 9.1e-3 | 2.5e-6, 5.3e-4 |
| The same with the water-hydrocarbon block (fitted to n-hexane) | 2.1e-6, 9.5e-4 | 2.5e-6, 5.3e-4 |
| Water with n-octane, group model with that block (predicted) | 6.5e-8, 5.7e-4 | 1.0e-7, 5.6e-4 |
| Water and n-hexane boiling together, 1 atm (three phases) | 61.4 C, vapour 0.791 hexane | 61.6 C, 0.79 |
| Ethanol 0.2, water 0.4, n-hexane 0.4 boiling, 1 atm (three phases) | 57.0 C; vapour 0.207, 0.142, 0.650 | ternary azeotrope 56.0 C; 0.184, 0.118, 0.698 |
| CO2 in water, 25 C, 1 atm; classical rule with k_ij 0 | 9e-5 (Henry constant 6.5 times too high) | 5.9e-4 |
| CO2 in water from its Henry constant: 25 C at 1 atm; at 5 MPa; 60 C at 5 MPa | 6.0e-4; 2.8e-2; 1.5e-2 | 5.9e-4; about 2.0e-2; about 1.2e-2 |
| N2 in water, 25 C, 1 atm: classical k_ij 0; from its Henry constant | 1.3e-7; 1.2e-5 | 1.2e-5 |
| Classical rule against the Huron-Vidal rule at α = 0 | ln φ equal within 8.9e-16 | |

Water on the cubic (Mathias-Copeman alpha): saturation within 0.012 % from 0 to 200 C; heat of vaporization +0.7 % at 100 C; liquid heat capacity within 1.5 %.

## 3. The design

- **M1. One equation of state, one mixing rule, in the one kernel.** `PengRobinsonKernel` gains the Huron-Vidal rule: a/b = Σ x_i a_i/b_i − g^E/Λ, g^E the NRTL form with local compositions weighted by b. A pair without derived data enters with α = 0 and energies set from its k_ij: today's classical rule, to rounding (section 2). A mixture with no derived pair keeps today's plan and numbers bit for bit.
- **M2. Water is a cubic component where a polar compound is.** A list (an island's, a sheet's, a column's) that holds a polar compound carries `Water` on the cubic. A list without one keeps the free-water rule and IF97.
- **M3. A compound carries its own data only.** Its modified UNIFAC (Dortmund) group counts, which ChemSep's records hold and the import writes; a crude cut is CH2 groups by its molar mass. A gas also carries its Henry constant in water (three coefficients, IAPWS G7-04: H2, N2, O2, CO, CO2, H2S, CH4, C2H6). A compound is *polar* when it has a group outside the hydrocarbon ones.
- **M4. Pairs are derived, never authored.** When a list first needs a pair, the catalog derives its energies and α through the equation of state itself, from one of three sources:
  - a polar compound with anything: the group model's two activity coefficients across the composition range, at 25 and 100 C;
  - water with a hydrocarbon: the same group model on a *water-hydrocarbon block* of the table (the published CH2-H2O values serve alcohols and make oil and water 15 to 60 times too soluble; the block is fitted once to measured mutual solubility of a few anchors);
  - water with a gas: the gas's Henry constant.
  A derived pair has as many liquids as its source. Hydrocarbon pairs stay on their k_ij. The group table is one record (`group_interactions`), from DWSIM's `modfac.txt` and `modfac_ip.txt`, with the block.
- **M5. A per-compound alpha.** `models.pr78.alpha` (Mathias-Copeman); absent, PR78's own, the same arithmetic.
- **M6. Three fluid phases, from the physics** (section 4).
- **M7. Column start.** The classical seed in a polar list, until the owner's networks exist.

## 4. Three fluid phases

1. **Expected.** A vessel at T and P settles where each compound has one fugacity in every phase that holds it. There are at most three fluid phases with fixed identities: the gas, the organic liquid, the aqueous liquid (the one richer in water). A phase is absent exactly when it could not form: the mole fractions it would take from the common fugacities sum to less than one. The phase rule then gives the behaviour: two liquids of a binary boil at one temperature (water and n-hexane at 1 atm: 61.6 C, vapour 79 % hexane, until one liquid is gone); with a third compound they boil over a range (from 56.0 C with ethanol).
2. **What the code does.** For water beside hydrocarbons the free-water rule is this statement with the aqueous liquid pure: water is present when its partial pressure reaches p_sat, absent below it (`solveWet`, `wetCoexistence`). For anything else the engine has two fluid phases, tests each product, and fails where a third would form. The second form of this plan added a phase on that failure: a search, not a statement.
3. **Proposal.** One system for the three named phases, each with its presence row: amount ≥ 0, saturation ≤ 1, and their product zero. It is the free-water rule's test written for a phase with a composition, and the same kind of row as the vessel's wall. The engine solves it for TP; PH and UV keep the boiling step they already have for one hydrocarbon beside water. The stability test stays as the audit of the answer. The network's layout carries the aqueous layer as per-compound unknowns with that row. The derived model lands on the measured three-phase points (section 2: 61.4 C, 57.0 C).

## 5. Gas solubility: evaluation

- **In a polar list: yes, in this batch.** It needs no mechanism of its own: a gas's pair with water is one more source in M4 (W2), and the dissolved gas is a compound of the aqueous liquid (W3). Without it the classical rule leaves CO2 6.5 times and N2 90 times too insoluble.
- **In gas and hydrocarbon liquids:** unchanged; PR78 with k_ij already dissolves gases there.
- **In a polar organic liquid (gas in ethanol):** stays on k_ij in this batch; the Dortmund table as shipped has no gas groups.
- **In a list on the free-water rule (crude, steam): not in this batch.** Nothing dissolves in free water. A Henry term added to that rule would be a patch on a special case; the structural answer is to move those lists onto water on the cubic, which moves every wet reference and the hold-fuzz baseline. Proposed as the next batch, decided on this one's campaign.
- Limit measured: CO2 at 5 MPa is 40 % too soluble (the gas's partial volume in water); a second per-gas number would close it. To be evaluated in W2.

## 6. Work packages

| | Package | Proves it |
|---|---|---|
| W1 | Kernel: per-compound alpha; the Huron-Vidal plan with analytic dZ, d ln φ/dT, d ln φ/dn, residual enthalpy | Finite-difference oracle; equal to the classical rule at α = 0; existing kernel tests unchanged |
| W2 | Catalog: group counts and Henry coefficients on datasets, the group table with its block (anchors sourced from the literature: alkanes, an olefin, a naphthene, aromatics, at two temperatures), the derivation (M4) through `DeterministicMath`, water's cubic dataset, a list's water treatment, identities by declared revisions; the import writes groups | A gate on section 2's tables; `MaterialCatalogTest`; older identities unmoved |
| W3a | Engine: water in the equation of state; the three-phase system of section 4 in TP, PH and UV | The three-phase points of section 2; `FluidTpEquilibriumP3Test`; campaign counters |
| W3b | Network: the aqueous layer in `PhaseLayout` and the step solver, the restatement at a list's first polar compound, ports and displays. Its own design document first | `NetworkPhaseEngineTest`, fluid GameTests, the hold-fuzz campaign |
| W4 | Still on a polar list | `BatchStillTest`: a 10 % wash starts near 92.5 C with y near 0.26 |
| W5 | V3 column on a polar list: package, feed flash, classical seed, the simulator's unit and sizing, the running column; a tray with two liquids warns | An ethanol and water rectifier against an offline McCabe-Thiele on the same model |
| W6 | Ethanol: records with groups, preset, names, heating value | Section 3 of the first review |

Order: W1 and W2 first (disjoint files, no behaviour changes).

Risks: the derivation is a small fit at load (deterministic, bounded; a pair it cannot fit is refused by name); the block's anchors and temperature dependence are data to source, not yet in hand (section 2 used n-hexane and benzene at 25 C from memory); the booking at a list's first polar compound is up to 0.7 % of water's heat of vaporization at 100 C.

## 7. Adding a compound afterwards

One compound record and one dataset with its PR78 constants and group counts (a gas: its Henry coefficients), written by `tools/compound-import`. No pair record, no fit, no code.

## 8. Earlier forms

- First (6acf83051): the free water as an aqueous phase with NRTL activity coefficients beside PR78. It cannot serve the V3 column.
- Second (885360f2b): this rule with authored, fitted pair records and a hold at three fluid phases. The owner refused both.
- Third (10522c4fc): pairs derived from groups; a third phase added where a product is unstable; water and hydrocarbons left 15 to 60 times too soluble; gases on k_ij. The owner asked for the physics first, the solubility fixed, and gas solubility evaluated.
