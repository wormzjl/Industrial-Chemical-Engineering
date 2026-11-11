# Aqueous solvents: the infrastructure plan

Batch [2026-11-11-aqueous-solvents](README.md), 2026-11-11, at main ff6f6892f (0.26.0). The first review is [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md). This is the plan's fifth form; section 9 lists the earlier ones and why each left.

## 1. Owner direction (2026-11-11)

- The task is infrastructure: more water-miscible compounds will follow ethanol. The design is the agent's call.
- Water and ethanol must work in the still and in the distillation column.
- No binary interaction parameters per compound.
- Three fluid phases "solved physics-first"; no hold.
- Water and hydrocarbon mutual solubility "needs to be dealt with".
- Gas solubility in liquid "needs to be added"; "How about solubility in organics?"
- "I'm open to breaking changes to the thermo engine. It needs to be fast and accurate with all the additional loads."
- Networks for the column's start will be developed per combination; not this batch's.

## 2. Measurements

Scripts in `tools/aqueous-solvents/`; DWSIM's tables in `tmp/dwsim-groups/`. Nothing is fitted to the data it is compared with, except where "fitted" is written. Solubility values and steam-table values are quoted from memory of the standard sources; W2 sources them.

Ethanol and water, 15 points of Perry's table at 101.325 kPa:

| Model | Pair data | AAD T, K | AAD y | Azeotrope | At 20 / 300 kPa |
|---|---|---|---|---|---|
| Measured (NRTL at the other pressures) | | | | 0.894 at 78.15 C | 0.928 / 0.868 |
| PR78, classical rule | best k_ij | 1.56 | 0.020 | none | |
| Group model inside the rule (MHV1) | none | 0.72 | 0.009 | 0.864 | 0.868 / 0.876 |
| **PR78, Huron-Vidal rule, pairs derived from group counts** | **none** | **0.12** | **0.004** | **0.906 at 78.1 C** | **0.928 / 0.878** |

Liquids and three phases (derived model):

| Check | Model | Measured |
|---|---|---|
| Ethanol and n-hexane azeotrope, 1 atm; at 25 C | 0.318 at 59.8 C; one liquid | 0.332 at 58.7 C; one liquid |
| Water with n-hexane at 25 C, table as published (hexane in water, water in hexane) | 1.4e-4, 9.1e-3 | 2.5e-6, 5.3e-4 |
| The same with the water-hydrocarbon block (fitted to n-hexane) | 2.1e-6, 9.5e-4 | 2.5e-6, 5.3e-4 |
| Water with n-octane, group model with that block (predicted) | 6.5e-8, 5.7e-4 | 1.0e-7, 5.6e-4 |
| Water and n-hexane boiling together, 1 atm | 61.4 C, vapour 0.791 hexane | 61.6 C, 0.79 |
| Ethanol 0.2, water 0.4, n-hexane 0.4 boiling, 1 atm | 57.0 C | ternary azeotrope 56.0 C |

Gases at 25 C and 101.325 kPa of the gas (mole fraction dissolved; ratio to measured):

| Gas in | k_ij = 0 | Authored library | Derived | Source of the derived pair |
|---|---|---|---|---|
| Water: CO2, N2 | 0.15, 0.011 | none | 1.0, 1.0 by construction | the gas's Henry constant (CO2 at 5 MPa: 1.4) |
| n-Hexane: N2, CH4, CO2 | 1.47, 1.17, 2.07 | 1.04, 1.02, 1.20 | 1.32, 1.05, 1.19 | E-PPR78 group counts |
| Ethanol: N2, CH4, CO2 | 0.81, 1.03, 2.22 | none | 0.85, 0.93, 0.86 | E-PPR78 on the liquid's hydrocarbon homomorph (ethanol as propane) |

The libraries give 76 of the refinery list's 990 pairs; the others are zero today (every pair of a crude cut among them). E-PPR78 on ethanol's alkyl groups alone, with no homomorph: 2.73, 2.11, 1.49.

Water as a PR78 compound, error against IF97 (Region 1 liquid, Region 2 steam; `water-share.mjs`, whose two tables reproduce the release's test values to 9 digits). The alpha is Mathias-Copeman with 3 coefficients matched to the Wagner-Pruss line from 0 to 370 C: within 0.54 % (0.07 % at 100 C, 0.02 K). "Share" is M2; "today" is Wagner-Pruss, Region 1 and the virial steam.

| Pure water at saturation | 25 C | 100 C | 200 C | 300 C |
|---|---|---|---|---|
| Liquid density: cubic alone (no volume shift) / with the share | -15 % / 0.000 % | -17 % / 0.000 % | -18 % / 0.000 % | -22 % / 0.008 % |
| Heat of vaporization: cubic alone | 0.23 % | 0.60 % | 2.2 % | 3.3 % |
| Heat of vaporization: with the share | 0.01 % | 0.03 % | 0.79 % | 5.0 % |
| Heat of vaporization: today | 0.01 % | 0.03 % | 0.93 % | 8.5 % |

| Steam enthalpy error, kJ/kg | Saturated 200 C | Saturated 300 C | 400 C, 4 MPa | 500 C, 10 MPa | 600 C, 20 MPa |
|---|---|---|---|---|---|
| Cubic alone | 42.7 | 102.3 | -0.6 | -15.2 | -32.9 |
| With the share | 15.2 | 70.5 | 1.0 | 1.4 | 7.1 |
| Today | 18.0 | 119.0 | 3.7 | 7.6 | 11.8 |

Continuity of the share (0 isolated jumps in every scan, the same count as the cubic alone): pure water on 14 isobars from 0.1 to 40 MPa (c_p positive throughout, lowest 1.97 kJ/(kg K)) and 16 isotherms from 25 to 500 C (dv/dp negative throughout); CO2 with 2 to 70 % water as one fluid phase through its critical region at 8 to 25 MPa, where the phase's label flips (c_p and dv/dT positive). The classical rule equals the Huron-Vidal rule at α = 0 within 8.9e-16 in ln φ.

## 3. The design

- **M1. One equation of state, one mixing rule, in the one kernel.** `PengRobinsonKernel` gains the Huron-Vidal rule: a/b = Σ x_i a_i/b_i − g^E/Λ, g^E the NRTL form with local compositions weighted by b. A pair with α = 0 and energies from its k_ij is the classical rule, so one rule serves every pair.
- **M2. One water, in the equation of state.** Water is a compound of the cubic in every list where it meets another compound. The free-water rule leaves the engine (`solveWet` and its closure, `wetCoexistence`, `waterBoiling`, the free water's ice: ice becomes a crystal of water like dry ice of CO2). Section 2 shows the cubic alone is not good enough for water's own enthalpy and volume, so water's own share of them (and of entropy) keeps IF97, and no list changes model when a trace enters it:
  - Equilibrium is the cubic's alone, with water's alpha matched to the Wagner-Pruss line. No fugacity term.
  - A liquid-like phase adds n_w W (Region 1 − the cubic's pure liquid water) at T and max(P, saturation). Pure liquid water is Region 1 exactly up to 350 C; from there to the critical temperature the share fades to zero, value and slope continuous.
  - A gas-like phase adds (n_w²/n)(1 − W) P (IF97's second virial coefficient − the cubic's): the water-water term, at water's partial pressure, so water vapour dilute in a gas is untouched. The steam is better than today's at every measured state.
  - W is a smooth function of the phase's own v/b: 1 below 2.6 (pure liquid water is at most 2.28 up to 350 C), 0 above 3.6 (the cubic's critical volume is 3.95). It follows the state, not the label, so a label flip changes nothing.
  The prototype (`water-share.mjs`) replaced the form first written here, a Gibbs energy difference per phase label: that form jumps wherever a one-root state changes label, and a vapour term in T and P alone scales with the total pressure (water in a gas at 10 MPa and 25 C: its fugacity about 30 times off, from the two second virial coefficients, -1214 and -372 cm3/mol). Saturated steam above 250 C stays the second virial's limit (heat of vaporization 2 to 13 % off; today 3 to 33 %); Region 2 in full at water's partial pressure would close it and is not in this batch.
- **M3. A compound carries its own data only.** Modified UNIFAC (Dortmund) group counts and E-PPR78 group counts (ChemSep's records hold both; the import writes them); a crude cut is paraffinic groups by its molar mass; a gas carries its Henry constant in water (IAPWS G7-04: H2, N2, O2, CO, CO2, H2S, CH4, C2H6).
- **M4. Pairs are derived, never authored.** When a list first needs a pair, the catalog derives it through the equation of state from one source:
  - two compounds without polar groups (gases and hydrocarbons): k_ij(T) from E-PPR78, the code and table the repository already has;
  - a polar compound with anything: the Dortmund group model's activity coefficients across the composition range, at 25 and 100 C, into two energies and α;
  - a gas with a polar organic: E-PPR78 on the liquid's homomorph;
  - water with a hydrocarbon: the group model on a water-hydrocarbon block fitted once to measured mutual solubility;
  - water with a gas: the gas's Henry constant.
  A derived pair has as many liquids as its source. The three authored libraries stay and win where they give a pair (section 2: as good as or better than the derived value); derivation fills the pairs that are zero today. Hydrogen pairs stay zero (owner rule, 2026-11-04) unless the owner lifts it.
- **M5. A per-compound alpha.** `models.pr78.alpha` (Mathias-Copeman); absent, PR78's own.
- **M6. Three fluid phases, from the physics** (section 4), in the engine and in the network's vessel layout.
- **M7. Column.** V3 takes water on its axis in a list with a polar compound (W5). Its crude columns keep their own free water: that is the column solver, not the thermo engine, and its ports carry moles at a temperature.

## 4. Three fluid phases

1. **Expected.** A vessel at T and P settles where each compound has one fugacity in every phase that holds it. At most three fluid phases, with fixed identities: gas, organic liquid, aqueous liquid. A phase is absent exactly when it could not form: the mole fractions it would take from the common fugacities sum to less than one. By the phase rule two liquids of a binary boil at one temperature (water and n-hexane at 1 atm: 61.6 C, vapour 79 % hexane), and with a third compound over a range.
2. **What the code does.** The free-water rule is this statement with the aqueous liquid pure. For anything else the engine has two fluid phases and fails where a third would form.
3. **Proposal.** One system for the three named phases, each with its presence row (amount ≥ 0, saturation ≤ 1, product zero): the free-water test written for a phase with a composition, and the same kind of row as the vessel's wall. It replaces the free-water rule instead of standing beside it. PH and UV keep the boiling step they have for one hydrocarbon beside water. The stability test stays as the audit. The network's layout carries the aqueous layer as per-compound unknowns with that row.

## 5. Gas solubility

With M2 and M4 it needs no mechanism: a dissolved gas is a compound of a liquid. In water: from the gas's Henry constant. In hydrocarbons: E-PPR78 instead of today's zeros. In polar organics: the homomorph rule. All in this batch, in every list, because no list keeps the free-water rule. Limit measured: CO2 in water at 5 MPa is 40 % high; a second per-gas number (its partial volume in water) would close it, to be evaluated in W2.

## 6. Speed

- Kernel: τ and G of each derived pair depend on T only and are made once per temperature; the composition sums run over polar compounds only, O(n × polar). A list without a derived pair runs the classical branch at today's cost.
- Engine: the free-water rule's unsaturated case is a closure of up to 70 trials, each a hydrocarbon flash; the three-phase system is one solve. The three-phase rows cost nothing while the third phase is absent.
- Water's share: one Region 1 evaluation and one pure-water root of the cubic per (T, P), kept with the prepared temperature as Region 1 is today; the second virial coefficient is per temperature.
- Catalog: a pair is derived once per data revision and kept; a list derives only its own pairs.
- Gates: the existing cost gates and campaign counters must not rise on hydrocarbon worlds; wet worlds are judged by kernel calls against today's; one new benchmark world with a polar list (60 s warm-up, 60 s measured, alone).

## 7. Work packages

| | Package | Proves it |
|---|---|---|
| W1 | Kernel: per-compound alpha; the Huron-Vidal plan with analytic dZ, d ln φ/dT, d ln φ/dn, residual enthalpy. First cut: dense sums over the list, the existing plans untouched bit for bit; the sums over polar compounds only (section 6) follow once W3a can count them | Finite-difference oracle; the classical rule at α = 0; kernel cost counters |
| W2a | Catalog data: group counts, `models.pr78.alpha` and Henry coefficients on datasets, the group tables (Dortmund with its block; E-PPR78 moved from the test overlay), the Dortmund activity model and the Henry evaluator through `DeterministicMath`; the import writes groups. No package identity changes | The new fields' parse and refusal tests; the activity model against values the offline script prints; `MaterialCatalogTest` |
| W2b | Catalog derivation (M4), after W1: pairs through the kernel, water's cubic dataset, identities by declared revisions, literature values for the block's anchors and the gas checks | A gate on section 2's tables |
| W3a | Engine: water in the equation of state with its share of enthalpy, volume and entropy (M2) and their analytic derivatives, the three-phase system in TP, PH and UV, ice as a crystal; the free-water rule and the steam's Dalton volume removed | Section 2's three-phase points and water tables as gates; pure liquid water equal to Region 1; the engine's tests re-stated; campaign |
| W3b | Network: the aqueous layer in `PhaseLayout` and the step solver, ports and displays; fluid state format bump; every wet reference re-recorded once. Its own design document first | `NetworkPhaseEngineTest`, fluid GameTests, chain-100 re-captured, the hold-fuzz campaign as the new baseline |
| W4 | Still | `BatchStillTest`: a 10 % wash starts near 92.5 C with y near 0.26 |
| W5 | V3 column with water on its axis: package, feed flash, classical seed, the simulator's unit and sizing, the running column; a tray with two liquids warns | An ethanol and water rectifier against an offline McCabe-Thiele on the same model |
| W6 | Ethanol: records with groups, preset, names, heating value | Section 3 of the first review |

Order: W1 and W2a first (disjoint files), then W2b, which needs both. Saves: contents are facts and keep their amounts and temperature; the engine restates energy and pressure and books the difference (AGENTS.md); no facts field changes.

Risks: W3 rewrites the wet side of the engine and the vessel layout, so the hold-fuzz campaign starts from a new baseline; water's share is proven offline for pure water and one mixture path only (its derivatives, entropy, ice below 0 C and cost are W3a's), and pure water's saturation pressure moves from Wagner-Pruss by up to 0.54 %; the derivation is a small fit at load (deterministic, bounded; a pair it cannot fit is refused by name); E-PPR78 instead of zero pairs moves every result with a gas beside a crude cut, so the column's references and its start networks move once.

## 8. Adding a compound afterwards

One compound record and one dataset with its PR78 constants and group counts (a gas: its Henry coefficients), written by `tools/compound-import`. No pair record, no fit, no code.

## 9. Earlier forms

- First (6acf83051): the free water as an aqueous phase with NRTL beside PR78. It cannot serve the V3 column.
- Second (885360f2b): authored, fitted pair records and a hold at three fluid phases. The owner refused both.
- Third (10522c4fc): pairs derived from groups; a third phase added on instability; oil and water too soluble; gases on k_ij.
- Fifth as first written (ad8dcf751): water's share as a Gibbs energy difference per phase label. The prototype replaced it (M2).
- Fourth (e5d9750f9): three phases from the physics, the water-hydrocarbon block, gases in water; two water treatments chosen by the list, and gases in organics and in free-water lists left out. The owner then allowed breaking changes and asked for gases in organics.
