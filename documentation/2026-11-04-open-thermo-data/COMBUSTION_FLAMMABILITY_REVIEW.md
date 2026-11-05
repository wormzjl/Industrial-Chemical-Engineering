# Combustion heat, flammability limits and flash point (R3, for O9 / E22)

Batch [2026-11-04-open-thermo-data](README.md), 2026-11-05. Research only: no code, no Gradle. Scripts and tables: `tmp/combustion-data/` (`combustion.mjs`, `flash.mjs`, `eccc-flash.cjs`, `flammability-data.json`, `coverage.tsv`). Code guide: no change.

## 1. Expected behaviour

- Net heat of combustion (LHV) at 298.15 K: fuel + O2 → CO2 + H2O(g) + SO2 + N2; by Hess, LHV = ΔHf(fuel) − Σ ν ΔHf(products). HHV = LHV + n_H2O × 43.99 kJ/mol (water's latent heat at 298.15 K). A liquid fuel releases ΔHvap less than its vapour; the game's enthalpy (ideal gas at 298.15 K = 0, plus the PR78 departure) carries that difference itself, so the stored value is the gas-basis LHV.
- Flammability limits (LFL, UFL; vol% in air): mixtures by Le Chatelier, 1/LFL = Σ y_i/LFL_i.
- Flash point: the liquid's equilibrium vapour reaches the LFL. Pure: Psat(T_F) = LFL × 101.325 kPa. Mixture (Liaw, γ = 1): Σ x_i Psat_i(T_F) / (P × LFL_i) = 1. Any composition gets a flash point from the LFL and the vapour pressures the game already has.

## 2. What the data holds now

- No property record has a formation enthalpy, heat of combustion, LFL or flash point. The world list (`icheme:refinery`, 44 + water) has 29 combustible defined compounds and 12 cuts; O2, SO2, N2, CO2 and water do not burn.
- ChemSep (`tmp/dwsim-data`) gives LHV and ΔHf for all 29. Hess against ChemSep's own ΔHf: 17 agree within 0.03 %. 11 compounds that are liquid at 298 K differ by −0.77 to −1.50 %, which equals their ΔHvap (n-hexane 31.7 vs 32.4 kJ/mol, benzene 33.4 vs 34.4): ChemSep's LHV is on a liquid basis for them, so this is a different basis, not an error. COS differs by −0.66 % (3.6 kJ/mol), unexplained. Recommended: gas-basis Hess values from ChemSep ΔHf for all 29.
- Cuts: no formula. Correlations checked against 177 ChemSep C5+ hydrocarbons (LHV error, bias / mean absolute error (AAD) / max):
  - F1, Cragoe 1929 (NBS Misc. Pub. 97, SG only): −0.3 / 0.5 / 1.3 % for paraffins and naphthenes; +2.5 / 2.8 / 5.5 % for aromatics. Smooth over all 12 cuts: 44.20 → 40.04 MJ/kg, H 15.3 → 10.2 wt%.
  - F2, Riazi C/H weight ratio (Tb, SG) + Channiwala-Parikh: 0.0 / 0.7 / 3.3 % on petroleum-like compounds. Its heavy branch switches at MW 300 and drops H from 13.0 to 10.5 wt% between pc07 and pc08. Rejected.
  - The ECCC database holds no heat of combustion, so no whole-crude validation is possible.
- Grade LHV (F1 cuts + ChemSep light ends; no sulfur species): light 42.87, medium 42.30, heavy 41.57, dilbit 41.97 MJ/kg. The specified sulfur would lower these by 0.1, 0.5, 1.0 and 1.2 MJ/kg.
- LFL and UFL (`flammability-data.json`), 29/29 combustible compounds: 18 from the NIOSH Pocket Guide (fetched); isopentane from CAMEO, which cites NTP 1992; 10 from Bureau of Mines Bulletin 627 (H2, CH4, C2H6, COS, ethylene, propylene, 4 butenes), entered from memory and not yet verified.
  - LFL estimates on the 22 hydrocarbons: Jones (0.55 × stoichiometric) +7.6 % bias, 9.0 % AAD; Burgess-Wheeler (LFL × LHV = 4319 vol%·kJ/mol, median of the set) 0.2 % bias, 7.5 % AAD, max 23 %. Both fail for H2, H2S, CO, NH3 and COS (up to +347 %), which all have measured values. Jones UFL (3.5 × stoichiometric): 25 % AAD, so do not estimate a UFL.
- Flash point, 11 liquids with NIOSH/NTP closed-cup values: PR78 Psat + measured LFL gives −2.2 K bias, 4.3 K AAD, max 9.2 K (benzene). ChemSep eq. 101 Psat gives 3.9 K AAD, so PR78 adds little error. With Jones LFL: 3.8 K AAD; with Burgess-Wheeler: 4.7 K. Closed-cup repeatability is a few K.
- Grades, Raoult + Le Chatelier (Burgess-Wheeler cut LFL from the F1 LHV; Wilson K above Tc):
  - As generated, they are live crudes. Methane and ethane give flash points of −121, −117, −103 and −65 °C (light, medium, heavy, dilbit), below their 1 atm bubble points of −16, −7, 24 and 37 °C. That is the correct physics for a live crude.
  - Without C1-C4: −14, −9, −5 and −32 °C.
  - ECCC fresh (W0) dead crudes, ASTM D7094: API 30-40 from −23 to +27 °C (most at or below 0); API 19-22 from −23 to +41 °C; Cold Lake / WCS dilbits from −16 to −5 °C. The match is rough; the dilbit runs low because of its pc01/pc02 diluent.

## 3. Proposal (owner chooses)

- Where the values live:
  - **W1 (recommended):** an optional `combustion` sub-record on each property record, with its own `revision`, like `freezing`. It sits in no identity (`scientificRevision`, `fluidRevision`, spine), so no world restates; an edit bumps only the sub-record.
  - W2: a new record kind keyed by component. It leaves the 48 records untouched, but adds a second lookup and an ambiguity for compounds with several datasets (Methane has 3).
  - W3: the cut correlation in the method record. It puts a correlation in `src/main` and touches a record whose physics fields form an identity. Not recommended.
- What is stored:
  - **S1 (recommended):** `lhv_j_per_mol` (gas basis, Hess), `water_formed_mol_per_mol`, `lfl_volume_fraction`, `ufl_volume_fraction` (absent for cuts), with source and `estimated`. The import tool precomputes the cut values (F1, Burgess-Wheeler) and marks them estimated. Computed by the game: HHV, mixture LHV, mixture LFL, flash point.
  - S2: store ideal-gas ΔHf and the element counts (C, H, N, O, S) instead; the cuts get CHx from Cragoe's H%. The game would also compute air demand and SO2, and later reaction enthalpies. Cost: more fields, and estimated formulas for the cuts.
  - S3: also store measured flash points. Only 11 of 44 compounds have one, it is useless for any mixture, and the computed value is within 4 K. Not recommended.
- What reads them:
  - (a) Parser and validation only. Lowest cost, but no player value (the `freezing` precedent).
  - **(b) (recommended):** the fluid device screen and the simulator's stream table show LHV (MJ/kg), the flash point of the liquid (°C) and the vapour's LFL. The engine computes them for its scheduled snapshot, never on GUI open or on the tick loop. The simulator computes them on its client run.
  - (c) Use: a fuel stream for the fired heater (today it fires to a set point with no fuel; `HeatUnit` FIRING), or a product flash point as a column spec. Each is a feature batch of its own.
- Save formats: no facts field changes. Material data is identified by declared revisions, and this sub-record is in none. Snapshot and stream-table fields are wire formats, which need no compatibility work. No corpus image.
- Simplifications (for approval): LFL temperature dependence neglected (≤5 % at −50 °C, under 1 K of flash point); γ = 1 in Liaw; the cuts' sulfur ignored, as in the cut physics.
- Sources and terms:
  - ChemSep: Artistic-2.0, the existing notice.
  - NIOSH: "in the public domain, and may be freely used or reproduced" (cdc.gov/other/agencymaterials.html).
  - US Government works, Bulletin 627 and Cragoe 1929: "not available for any work of the United States Government" (law.cornell.edu/uscode/text/17/105).
  - CAMEO: terms page 404, unconfirmed; its value is NTP's. ECCC: OGL-Canada. Riazi, Channiwala-Parikh, Jones, Burgess-Wheeler, Liaw: methods only.
- Download to approve: Bulletin 627 PDF, https://stacks.cdc.gov/view/cdc/235822/cdc_235822_DS1.pdf, 8,584,508 bytes, to verify the 10 values entered from memory.
Owner, 2026-11-05 (E23): download Bulletin 627; readers (a), data only; storage S1 in W1; the three simplifications approved.
