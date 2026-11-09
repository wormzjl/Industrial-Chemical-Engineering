# Combustion data apply: forms S1 and S2 (O9, decisions E23 and E34)

Batch [2026-11-04-open-thermo-data](README.md), 2026-11-08. Design: [COMBUSTION_FLAMMABILITY_REVIEW.md](COMBUSTION_FLAMMABILITY_REVIEW.md) (R3), E23, and the owner's request of 2026-11-08 to build S1 and S2 and compare them. Code guide: no change until the merge. Scratch: `tmp/o9-combustion/` (`s1.patch`, `s2.patch`, `compare.txt`, each run's result files).

Status: the owner chose S2 on 2026-11-08 (E34), committed in `f9ff89dec`. Cut heats kept on Cragoe's basis (A); the limit gaps ship as built. S1 was never committed; it stays in `tmp/o9-combustion/s1.patch` until the batch's scratch is cleaned up.

## 1. Expected behaviour

- Each compound or cut that burns has its net heat at 298.15 K on the gas basis, the water it forms, and its LFL and UFL (no UFL for cuts). Example, methane: 802.6 kJ/mol (50.0 MJ/kg), 2 mol of water, 5 to 15 vol%. The values are data only: optional sub-records with their own revisions, read by nothing and in no identity. No world restates.
- Both forms are on the gas basis. The game's enthalpy (ideal gas at 298.15 K = 0, plus the PR78 departure) covers a liquid fuel's phase in either form, so the basis does not tell them apart. Stoichiometry and reuse do.

## 2. What the data held before

- No record held a heat, a formation enthalpy or a limit. R3's scratch held the values; its limits came from NIOSH (18), CAMEO (1) and memory (10). The world list `icheme:refinery` holds 45 compounds plus water (R3 counted 44 before CO2): 29 combustible compounds, 12 cuts, and O2, SO2, N2, CO2.

## 3. The two forms

- **S1**, `combustion` on 41 datasets (`chemsep-hess-npg-b627-r1`, cuts `cragoe-1929-burgess-wheeler-r1`): stores `lhv_j_per_mol` (Hess on ChemSep, done by the tool), `water_formed_mol_per_mol`, and optional LFL/UFL; one source and flag for the heat and one for the limits. Code: `MaterialCatalog.Combustion`, `combustion(...)`, `crude-grades/combustion.mjs`, test `MaterialCombustionTest`.
- **S2**, two sub-records:
  - `thermochemistry` on 45 datasets and the water model (`chemsep-8.3-hf-r1`, cuts `cragoe-1929-chx-r1`): `formation_enthalpy_j_per_mol` (ideal gas, 298.15 K; O2 and N2 are 0) and `elements` (C, H, N, O, S), whose molar mass the tools and the data gate check against the record's (not the loader: three existing tests edit molar masses, and a dataset edit must not be refused for a data-only field). Cuts: CHx from Cragoe's hydrogen at the cut's molar mass, and ΔHf back-computed so that Hess gives Cragoe's heat; both estimated.
  - `flammability` on 40 (`npg-b627-r1`, cuts `burgess-wheeler-r1`): LFL and the optional UFL.
  - Names: `thermochemistry` because a formation enthalpy and a formula serve any reaction, not only combustion; `flammability` apart, because its sources and corrections are separate and must not move the data a reaction reader keys on. Code: `Thermochemistry`, `Flammability`, their accessors (`thermochemistry` also for the water), `crude-grades/thermochemistry.mjs`, test `MaterialThermochemistryTest`.
- Shared by both forms: `chemsep.mjs` reads `HeatOfFormation`, `import.mjs` writes either form, and `insert-subrecord.mjs` writes the wrappers into the records, keeping the bytes (`--replace` re-runs wrote nothing). The limits (section 4) are the same in both forms.

## 4. Bulletin 627 verification (Table A-1, printed pp. 114-115, read from the page images)

| Compound | From memory (vol%) | Bulletin 627 | Page | Shipped |
|---|---|---|---|---|
| H2, CH4, C2H6, ethylene, propylene, isobutylene | 4.0/75, 5.0/15.0, 3.0/12.4, 2.7/36, 2.4/11, 1.8/9.6 | identical | 115 | same |
| Butene-1 | 1.7 / 9.7 | 1.6 / 10 | 114 | **changed** |
| Cis- and trans-2-butene ("Butene-2", one entry) | 1.8 / 9.7 | 1.7 / 9.7 | 114 | **LFL changed** |
| Carbonyl sulfide | 12 / 29 | no entry | - | **no limits** |
| Isopentane (was CAMEO) | 1.4 / 7.6 | 1.4 / none | 115 | **UFL dropped** |

## 5. Comparison (`tools/compound-import/compare-combustion-forms.mjs`, offline)

- Numbers: S1's stored LHV equals S2's Hess LHV on all 41 records, difference 0 J/mol (cuts too, bit for bit). Water formed and limits are identical.
- What only S2 gives: O2 demand, and the CO2, SO2, H2O and N2 formed per mole (CH4 2 O2; NH3 0.75 O2 and 0.5 N2; COS 1.5 O2, 1 CO2 and 1 SO2; pc12 148 O2). It also gives ΔHf for the products and inerts and for later reactions (hydrotreating, Claus, reformer), so a fuel-fed heater (reader (c)) can do its air and flue balance from the catalog alone. S1 gives the heat and the water only.
- Cost: S1 has 5 to 9 fields on 41 records. S2 has 5 to 7 (`thermochemistry`) on 46 records and 4 to 5 (`flammability`) on 40. Lines added, S1 / S2: src/main/java 59 / 101; data 434 / 727; tests 178 / 215; tools 269 / 313.
- What S2 risks: a cut's ΔHf has meaning only with the CO2 and H2O values it was back-computed on, so a change to those records means re-running the tool, or the cut heats move (the tool checks the bundled values). The water record holds ChemSep's −241814 J/mol while its Shomate constants use ATcT's −241808 (6 J/mol, 0.002 %). A cut's formula is fractional and estimated.
- What S1 risks: a later reaction or air-balance reader needs a second data set with formulas and ΔHf, which overlaps S1's heats.
- **Recommendation: S2.** The heats are the same, it adds the stoichiometry, and it is the reusable base for later readers, for about 40 more Java lines and 5 more records. S1 only if no reader beyond the heat is ever planned.
- Cut basis, decided (A) by E34: Cragoe is fitted to liquid fuels, so a cut's heat sits 0.3 to 1 % below the gas basis, which is inside Cragoe's error. It is kept as built; (B) would have added each cut's PR78 ΔHvap at 298 K in the tool.

## 6. Sources, terms, tests

- Sources and terms: ChemSep (Artistic-2.0, copyright line in each source text); NIOSH (public domain); Bulletin 627 and Cragoe 1929 (US Government works); Burgess-Wheeler (a method). No CAMEO anywhere. `DATA_LICENSE_REVIEW.md` rows 1 and 16 record them for S2. The PDF already sat in the main checkout's `research/.../usbm-bulletin-627/`, identical, with its index row.
- Tests on lane 2 (4 workers, beside the retrain's relabel on lane 1), each form on its own patch, with `MaterialCatalogTest` 14 and `RefineryImportTest` 3: S2's `MaterialThermochemistryTest` 4 pass (1 min, whole test tree compiled); S1's `MaterialCombustionTest` 4 pass (27 s). The subagent's plain-javac run of S1 is not counted.
- At merge prep: `thermochemistry(...)` and `flammability(...)`, like W5b's `freezingPoint(...)`, have only test callers. The rule that `src/main` holds only code the game reaches needs one decision for the three.
