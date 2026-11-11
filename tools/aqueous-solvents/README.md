# aqueous-solvents

Purpose: offline checks for the aqueous phase (water with water-miscible solvents).

Batch: [2026-11-11-aqueous-solvents](../../documentation/2026-11-11-aqueous-solvents/README.md). Status: In use since 2026-11-11. Index: [../INDEX.md](../INDEX.md).

## How to run

From the repository root (Node 22, no dependencies, about 10 s):

```
node tools/aqueous-solvents/ethanol-water-vle.mjs
node tools/aqueous-solvents/pr-huron-vidal.mjs
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'tools/compound-import/Extract-DwsimData.ps1' -OutDir tmp/dwsim-groups -Files unifac.txt,unifac_ip.txt,unifac_ll_ip.txt,modfac.txt,modfac_ip.txt,NIST-MODFAC_RiQi.txt,NIST-MODFAC_IP.txt,chemsep1.xml"
node tools/aqueous-solvents/group-derived-pairs.mjs tmp/dwsim-groups
node tools/aqueous-solvents/gas-in-organics.mjs src/test/resources/materials/pilot-cryogenic/data/icheme/materials/group_interactions/eppr78_2022.json
node tools/aqueous-solvents/cubic-water.mjs
node tools/aqueous-solvents/water-share.mjs src/main/java/com/wormzjl/icheme/science/fluid/thermo/WaterRegion1.java
```

## Entry points

| File | Role |
|---|---|
| `ethanol-water-vle.mjs` | Bubble points of ethanol and water at 101.325 kPa against the 15 points of Perry's table: PR78 with classical mixing (k_ij scanned from -0.30 to 0.20), NRTL with Antoine pressures, and NRTL with ethanol's reference fugacity from PR78's own saturation (plan decision D2). Prints the errors, the azeotrope, a 10 % ABV wash and each point |
| `pr-huron-vidal.mjs` | One model for every phase: PR78 with water on the cubic (a Mathias-Copeman alpha fitted to the Wagner and Pruss saturation line, 0 to 200 C) and the Huron-Vidal mixing rule with the b-weighted NRTL. Prints water's saturation, heat of vaporization and liquid heat capacity; the pair fitted at 1 atm with constant energies and its azeotrope at other pressures; the pair with energies linear in T fitted also to NRTL bubble points at 20 and 300 kPa. About 1 min |
| `group-derived-pairs.mjs` | No authored pair parameters: reads DWSIM's modified UNIFAC (Dortmund) tables, derives each pair's Huron-Vidal energies (and, in its last section, alpha) from the compounds' group counts through the equation of state, and checks ethanol and water (Perry's table, 20 and 300 kPa), the ethanol and n-hexane azeotrope and miscibility, water and n-hexane, and a ternary's two liquids. Also prints the group model placed directly inside the rule (residual form and MHV1); a water-hydrocarbon block of the table fitted to n-hexane and benzene at 25 C (n-octane predicted); with it, two liquids and the boiling of two liquids together (three fluid phases: water with n-hexane, and the ternary with ethanol); the classical rule as the alpha 0 case; CO2 and N2 in water from their IAPWS Henry constants. A second argument `nist` reads the NIST refit of the table (`NIST-MODFAC_IP.txt`). About 4 min |
| `gas-in-organics.mjs` | N2, CH4 and CO2 in n-hexane and in ethanol at 25 C and 101.325 kPa of the gas, PR78 with the classical rule: k_ij zero, the k_ij(T) of E-PPR78 from group counts (the repository's table), the authored library's values, the k_ij each measured value needs, and for ethanol the k_ij of the gas with its hydrocarbon homomorph (propane) |
| `cubic-water.mjs` | Water as a PR78 compound: a Mathias-Copeman alpha fitted 0 to 350 C and one constant volume shift, against IAPWS-95 saturation densities, heat of vaporization and liquid heat capacity at 25, 100, 200 and 300 C, and steam at 500 C and 10 MPa. About 20 s |
| `water-share.mjs` | Prototype of the plan's M2. Reads Region 1's table from `WaterRegion1.java`; holds Region 2 (checked against the release's test values). Matches a Mathias-Copeman alpha to the Wagner-Pruss line; compares steam (cubic alone, with IF97's second virial coefficient, today's virial steam) and pure water at saturation against IF97; prints the sizes of the two shares; scans isobars, isotherms and a CO2 and water mixture for jumps and wrong-signed slopes. A second argument sets the number of alpha coefficients (default 3); `RATIO_DENSE` and `RATIO_GAS` set the weight's v/b window (2.6, 3.6). About 1 s |
| `dortmund-values.mjs` | The pinned values of `AqueousSolventDataTest`: ln gamma of the Dortmund model from the bundled table record (checked against DWSIM's files), the block refit, CO2's Henry constant. `node tools/aqueous-solvents/dortmund-values.mjs tmp/dwsim-groups`, under 1 s |
| `probes/IslandTime.java` | Java timing probe: time per kernel evaluation on an N-compound sublist of the refinery list, today's constant pairs against the derived kernel (water-free and wet phase). Compile against `build/classes/java/main`, `build/classes/java/test`, `src/test/resources`, `build/resources/main` and gson, in package `science.thermo.phase`; `IslandTime today|waterfree|wet N [warm measure]`. Run alone. |
| `probes/PairSizes.java` | Java probe, same class path: the refinery list's derived pairs by source, the E-PPR78 pairs' size at 150 to 700 K and how many a size threshold keeps. |

## History

- 2026-11-11: written. PR78 at its best k_ij (-0.10): AAD 1.56 K, no azeotrope. NRTL: AAD 0.25 K, azeotrope 0.897 at 78.17 C. See AQUEOUS_SOLVENTS_PLAN.md section 2.
- 2026-11-11: `pr-huron-vidal.mjs`. Water's saturation within 0.012 %; the pair at 1 atm 0.10 K with constant energies (wrong azeotrope trend with pressure), 0.18 K with energies linear in T (azeotrope 0.958 at 20 kPa, 0.860 at 300 kPa; NRTL 0.928, 0.868). See the plan's section 2.
- 2026-11-11: `group-derived-pairs.mjs`. Energies and alpha derived from group counts: ethanol and water 0.12 K, azeotrope 0.906 (0.928 at 20 kPa, 0.878 at 300 kPa); ethanol and n-hexane azeotrope 0.318 at 59.8 C, one liquid at 25 C; water and n-hexane two liquids, 15 to 60 times too soluble (the table's own values). The group model directly inside the rule: 0.72 K, azeotrope 0.864, a false ethanol and n-hexane split. See the plan's section 2.
- 2026-11-11: the NIST table has the published CH2-H2O values too (no gain for water with alkanes). Water-hydrocarbon block fitted to n-hexane: n-octane predicted 6.5e-8 and 5.7e-4 (measured 1.0e-7, 5.6e-4). Derived model with it: water and n-hexane boil together at 61.4 C with 0.791 hexane in the vapour (61.6 C, 0.79); the ternary with ethanol at 57.0 C (56.0 C). Classical rule equals the alpha 0 case within 8.9e-16 in ln phi. CO2 and N2 in water at 25 C and 1 atm from their Henry constants: 6.0e-4, 1.2e-5; CO2 at 5 MPa 2.8e-2 (about 2.0e-2 measured).
- 2026-11-11: `gas-in-organics.mjs`, `cubic-water.mjs`. Gases in n-hexane, ratio to measured: zero pairs 1.47, 1.17, 2.07; E-PPR78 1.32, 1.05, 1.19; the authored library 1.04, 1.02, 1.20. In ethanol: zero 0.81, 1.03, 2.22; the homomorph rule 0.85, 0.93, 0.86. Cubic water: saturation within 0.54 % to 350 C; at 300 C liquid density -13.2 %, heat of vaporization +3.3 %, liquid heat capacity +21.8 %; steam at 500 C and 10 MPa -0.4 % in enthalpy.
- 2026-11-11: `water-share.mjs`. Alpha 0.91976, -0.33844, 0.33431: saturation within 0.54 % of Wagner-Pruss (0.07 % at 100 C); 5 coefficients 0.21 % and worse below 0 C. Heat of vaporization against IF97, with the share / today: 0.03 / 0.03 % at 100 C, 0.79 / 0.93 % at 200 C, 5.0 / 8.5 % at 300 C. Steam at 500 C and 10 MPa: +1.4 kJ/kg (today +7.6, cubic alone -15.2). No isolated jump in any scan; c_p and dv/dT positive, dv/dp negative.
- 2026-11-11: `gas-in-organics.mjs`, last section. The homomorph's groups with ethanol's own a and b: 2.66, 2.08, 1.16 times measured (with propane's own constants 0.85, 0.93, 0.86).
- 2026-11-11: `probes/`. Kernel time per evaluation, today / derived water-free / derived wet: 12 compounds 128 / 414 / 566 ns; 24 compounds 284 / 1720 / 1749 ns. The refinery list's 713 E-PPR78 pairs: median largest |k| 0.029, 550 at or above 0.01; |k| above 1 in 10 pairs at 150 K and 7 at 700 K (ethylene with isobutene -5.9e4 at 700 K, oxygen with p-xylene 500 at 150 K). See W3B_NETWORK_PLAN.md section 8.
