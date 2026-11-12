# Ethanol in the compound list: what it affects, and a proposal

Batch [2026-11-11-aqueous-solvents](README.md), 2026-11-11, at main ff6f6892f (0.26.0). No code or data changed. Section 4's step 2 is settled by [AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md) (option B, as infrastructure). "Compound matrix" is read here as the network package `icheme:refinery` (45 compounds and water) with its PR78 pair matrix.

## 1. Expected behaviour (physics)

- Ethanol: Tb 351.4 K, Tc 514.7 K, Pc 6.27 MPa, omega 0.644, melts at 159 K, 789 kg/m3 at 20 C, flammable 3.3 to 19 vol %, lower heating value 1278 kJ/mol from the gas (27.7 MJ/kg; 26.8 MJ/kg from the liquid).
- With water it is one liquid at every strength. At 101.325 kPa the bubble point runs from 100 C (water) down to the azeotrope, 78.15 C at 89.4 mol % (95.6 wt %). A 10 % ABV wash (3.3 mol %) boils at about 93 C and its first vapour holds about 25 mol % ethanol.
- With hydrocarbons it is strongly non-ideal: azeotropes with n-hexane (58.7 C), benzene (68 C), cyclohexane (64.9 C) and toluene (76.7 C); 10 % in gasoline raises the vapour pressure by about 7 kPa. Water pulls it out of gasoline into a lower layer.

## 2. What the code does now

- No alcohol is in the catalog. The 2026-11-04 review put ethanol in Tier 3 (no E-PPR78 group); the batch still's decision B5 took A, "ethanol and water needs an activity model, its own batch".
- Water is never on the PR78 axis. Network (`FluidTpEquilibrium.solveWet`), V3 column (`V3WaterProperties`, free water) and the still (`StillColumn` on `HydrocarbonModel`) all use the free-water rule: nothing dissolves in water, water dissolves in nothing. Ammonia and SO2 are already in the package under that rule; `PhaseContract.forNetworkPackage` declares no water-chemistry species.
- So ethanol added as a plain PR78 compound gives, with water: two liquid layers (ethanol floating), a boil at 69.5 C at 1 atm (where 70.8 kPa + 30.5 kPa meet the pressure), a vapour of 70 mol % ethanol at any strength, then a jump to 100 C when the ethanol layer is gone. Against section 1: 8.7 to 23 K too cold, and no change of the distillate with the charge.
- Dry, PR78 is usable: omega pins the vapour pressure near Tb, and the volume anchor at Tr = 0.7 sets the density. Pairs absent from a library are zero (`missing_interactions: zero`), which loses the hydrocarbon azeotropes.

## 3. What adding it touches

| Area | Effect | Work |
|---|---|---|
| Records | `components/ethanol.json`, `properties/chemsep_ethanol.json` (PR78, Cp fit, viscosity, `freezing`, `thermochemistry`, `flammability`, `combustion`) | `tools/compound-import` with a new `selection-ethanol.json` (DWSIM is installed here), `tools/heating-values` |
| Pairs, volume anchor | In records of their own (`dwsim_refinery_pr78_ethanol`, `chemsep_ethanol_liquid_reference`), named by the method `crude_pr78` | As CO2 (O11): no view without ethanol moves its identity |
| Package | `refinery.json` gains `Ethanol`; revision `refinery-tier1-r3` to r4; world list 45 to 46 (limit 99) | One line and the revision |
| Index, names | `materials-index.json` 119 to 123 files; `material.icheme.ethanol` in `en_us.json`; optional fluid preset and `networks/default.json` entry for the creative source | Other languages at the translation commit |
| Saved worlds | Basis compared by compound ids; islands without ethanol keep their view identity, so nothing restates. No facts-schema change, no corpus image | Check with `FluidFactsCompatTest` |
| Automatic consumers | Simulator feed lists (`SheetCompounds`); fuel for fired heaters, the still's liquid heat, engines, gas turbines, the flare (heating value and formula) | None |
| V3 neural seed | Ethanol is not in the sidecar's `lumpMap`: a column feed holding it is `UNAVAILABLE` and starts from the classical seed | None (no retrain proposed) |
| Tests and guide | `MaterialThermochemistryTest` (45, 46, 40), `RefineryImportTest`, `MaterialCatalogTest`, `RestrictedPackagePhysicsTest`; counts in `thermo-materials.md` and MATERIALS.md | Edit the pins; run those classes |
| Water (section 2) | Every wet ethanol state is wrong in temperature, vapour and layers: vessels, sight glasses, phase ports, the still, the column | Section 4 |

## 4. Proposal for the owner

**Step 1 (recommended now): the data import.** Everything in section 3 but the last row: ethanol as a PR78 compound with its DWSIM/DECHEMA pairs, own pair and anchor records, package r4. Dry ethanol (fuel, gasoline blending) then works. Open: a creative-source preset, yes or no.

**Step 2: ethanol with water.** The physics does not force one answer:

- **A. Leave the free-water rule**, with a non-blocking status notice "ethanol with water: treated as immiscible". No code beyond the notice. The still's iconic charge stays wrong by the numbers of section 2.
- **B (recommended). An aqueous solution phase inside the free-water rule.** The water phase holds water and the compounds a record declares water-miscible (ethanol first). Their fugacity there is x γ p_sat with NRTL γ from the `nrtl` pair records the catalog already parses (`NrtlPair`; no solver reads them today). Gas side unchanged; hydrocarbons still never enter the water. Ideal mixing for volume and enthalpy (excess volume up to 3.5 %, excess enthalpy up to 0.8 kJ/mol, neglected). Expected: the azeotrope within about 0.2 K and 1 mol %. Touches `solveWet` with its PH and UV, `FreeWaterModel`, `NetworkPhaseEngine`, the phase layout's water phase (now with a composition), `StillColumn`. The V3 column stays on free water and warns. This is the activity model B5 named: its own batch, a hold-fuzz campaign, and your sign-off against "do not add new reference models".
- **C. The same γ model in the still only.** Rejected: the pot is a network vessel, so the network would show two layers at 69.5 C while the still distils at 93 C. An isolated patch.
- **D. Water on the PR78 axis with an ethanol/water k_ij.** Rejected: two water models (IF97 beside PR78), and the column, ledger and steam assume water off the axis.
- **E. Declare ethanol a water-chemistry species.** Every wet ethanol island holds `HELD (phases)`. Honest, unplayable for a wash.

Decisions asked: step 1 go and the preset; step 2 A, B or E; with B, whether ammonia and SO2 join the miscible list later (not proposed here).
