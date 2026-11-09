# Crude grades and cut viscosity review (O3, O4)

Batch [2026-11-04-open-thermo-data](README.md), work packages O3 and O4 of [OPEN_DATA_PLAN.md](OPEN_DATA_PLAN.md). Research only: no code or data changed. Scripts and outputs: `tmp/crude-grades/` (`lib.js`, `cuts.js`, `grades.js`, `variants.js`, `fitb.js`, `densk.js`, `mix.js`, `refit.js`, each with a `*-out.txt`). Tags: **[E]** established (code, data, source or computation), **[I]** interpretation.

## 1. Expected behaviour

- A crude is a boiling-point distribution plus a chemistry. Its API gravity follows from both: the TBP curve (how much boils where) and the density of each boiling range, which is set by its Watson K (paraffinic about 12.2, naphthenic or aromatic about 11.2). A heavy oil-sand crude is heavy because its TBP is heavy **and** its cuts are dense. [I, textbook physics]
- Usual classes (API): light above 31.1, medium 22.3 to 31.1, heavy 10 to 22.3, extra heavy below 10; sweet below 0.5 % S ([API gravity](https://en.wikipedia.org/wiki/API_gravity)). [E]
- Whole-crude viscosity at 15 C spans four decades: SCO 4, Alberta sweet blend 6, ANS 10, Arabian Heavy 50, WCS 226, Cold Lake Blend 360, rail bitumen 690,000 mPa s (ECCC records in section 4). [E]
- A log mole-fraction average (Arrhenius, Grunberg-Nissan with no interaction) underpredicts size-asymmetric hydrocarbon mixtures, so a positive pair term is physical. [I]

## 2. What the code does now

- **Assays** [E]: a composition over 7 light ends and `crude_pc01..12`, mass or mole basis; ExxonMobil assays carry a provenance block and an exponential tail above 590 C (`assays/wti_light_export.json`). `tjl20.json` is mole basis with 0.5 mol % methane.
- **Cuts** [E]: NBP 331 to 1095 K, SG 0.712 to 1.050, Watson K 11.6 to 12.0 (TJL characterisation). Basis API range 67.1 (all pc01) to 3.2 (all pc12).
- **Cut liquid viscosity** [E]: 608-node `log_table`, 293.15..900 K, revision `crude-regrouped-r1-dalia`. `crude_pc10`, `pc11` and `pc12` carry the **same** table (1.7e6 mPa s at 313 K, 2.5e3 at 373 K): the Dalia 550 C+ residue is unresolved.
- **Mixture rule** [E] (`MixtureViscosity.liquid`): mu = exp(sum_i x_i ln mu_i + delta), x mole fractions over every liquid component. A light end above its table and above its Tc takes its dilute vapour viscosity (methane always, ethane above 305 K); every value is capped at 1e12 Pa s.
- **delta** [E] (`LiquidMixtureCorrection.logCorrection`, `methods/crude_pr78.json`, `dalia-global-r1`): with v_i = x_i p_i (participation), h_i = v_i r_i (residue retention), e_i (activation slope), fa = (sum v)^2 - sum v^2, fh likewise for h, ea and eh their slope-weighted forms, q = 1000 (1/min(T, 373.15) - 1/323.15):
  delta = 0.7585 (fa - fh) + 0.4572 fh + 0.08246 (ea - eh) q + 0.05052 eh q. Light ends do not participate; all 12 cuts do; r = 0 for pc01..06, 0.16 for pc07, 1 for pc08..12.
- **Size of delta** [E, `mix.js`]: exp(delta) = 1.55 to 2.11 at 15 C on every grade tested (light 1.74, medium 1.84, heavy 2.00, dilbit 1.55, TJL20 1.86, Dalia 2.07). It is in effect one uniform factor of about 1.8; the c0 term alone carries 81 to 92 % of it.
- **DWSIM** [E, source `DanWBR/dwsim` branch `windows`]: without assay viscosities, `DistCurves.cs` sets PF_v1, PF_v2 from Abbott 1971 at 37.8 and 98.9 C (`Visc37_Abbott`, `Visc98_Abbott`); `AUX_LIQVISCi` returns `oilvisc_twu` (two-point ASTM D341 Walther) times density, falling back to Letsou-Stiel on NaN; `AUX_LIQVISCm` mixes by `LiquidViscosity_MixingRule`, default `MoleAverage` (linear in mu; log and inverted-mass options exist). The 13-cut export (`research/viscosity-tools/raw/dwsim-viscosity-export.json`) has PF_v2 equal to Abbott exactly; PF_v1 is 0.74 to 0.76 of Abbott (cause not found). TJL_PC11 has PF_v1 = 97.9 m2/s and PC12/PC13 near 1e-18: Abbott's denominator (API + 50.36 - 4.78 Kw) crosses zero at API about 6.7, so it fails for pc11 and pc12 [E].

## 3. Proposal: grades (O3)

### 3.1 Method

- C6+ part: Riazi 1989 TBP distribution, mass basis, x(T) = 1 - exp(-(B/A) ((T - T0)/T0)^B), T0 = N-pentane NBP (309.2 K) or a grade's own initial boiling point. Each cut takes the mass between the midpoints of neighbouring NBPs: bins 309 | 357 | 408 | 456 | 505 | 553 | 602 | 657 | 726 | 817 | 917 | 1030 | inf K. [E, `grades.js`]
- Light ends: one C1-C5 total, split by mass 1/4/15/10/22/21/27 % (C1, C2, C3, iC4, nC4, iC5, nC5); condensate 0/1/6/7/16/30/40 %. The split is an assumption. [I]
- Density: ideal volume additivity of the 60 F standard densities; 15 C by API MPMS 11.1 crude expansion. Two specifications (API, 565 C+ share) fix A and B.
- Check [E, `fitb.js`]: Riazi B fitted to open ECCC TBP curves is 1.71 (Bakken), 1.23 (ANS), 1.60 (WCS), 1.38 (CLB), 2.20 (SCO).

### 3.2 Feasibility: the fixed cut densities

- Open ECCC TBP curves put on the shared cuts read [E, `densk.js`]: Bakken 35.8 (measured 40.3), ANS 33.6 (32.2), SCO 37.2 (34.6), WCS 27.0 (21.5), Cold Lake Blend 27.7 (22.5). To read their measured API, the cuts above 235 C would need density x0.954 for Bakken (Kw 12.25) and x1.047 for WCS and CLB (Kw 11.15).
- So the basis is right for medium crudes, about 5 API heavy for paraffinic light crudes, and 5 to 7 API light for heavy Canadian crudes. [E for the numbers; I: the cause is the single Watson K of the TJL cuts]
- Light and medium: API and residue are both met, with B = 1.71 and 1.76, inside the ECCC range. [E]
- Heavy at API 20 with 33 % 565 C+: B = 4.2 and 0.9 % naphtha, not a crude shape. With B = 1.5, API 20 needs 49.7 % 565 C+, and API 22.3 needs 42.9 %. [E]
- Raw bitumen at API 8.5 with 52 % 565 C+: infeasible for every B in 0.3..6 (best 15.9). API 8.5 alone at B = 1.5 needs 83 % 565 C+, 74 % of it in pc12. [E]
- Dilbit: real-shape bitumen (T0 473 K, B 1.5, 50 % 565 C+) reads API 16.1; 76 % of it with 24 % condensate (API 70) reads **API 29.0**, with 38 % 565 C+ (CLB: 40 %). Forcing API 21.5 needs about 65 % 565 C+. [E]

### 3.3 Oil-sand grade [I]

| Form | Real API, S | Real TBP | On the shared cuts | Viscosity, 15 C (ECCC) |
|---|---|---|---|---|
| Dilbit (bitumen + about 25 % condensate), e.g. CLB EC02712, WCS EC02709 | 21.5-22.5; 3.5 % S (WCS) | dumbbell: 15 % below 100 C, 40 % above 565 C | shape fits, API reads 27-29 | 226-360 mPa s |
| Synthetic crude, SCO EC02695 | 34.6; about 0.1-0.2 % S | narrow, end point 484 C, no residue | fits (37.2), but a near copy of light and medium | 4 mPa s |
| Raw bitumen (rail bitumen EC03126, API 8.3) | 8; 4.9 % S | about 50 % above 565 C | infeasible | 690,000 mPa s, does not flow at ambient |

Recommended: **dilbit**. It is what oil-sand crude is shipped and refined as, and its bimodal TBP is a real distillation case. It is a blend of two generated distributions.

### 3.4 Recommended grades (shape first: TBP and residue met, API as the cuts give it)

| | Light | Medium | Heavy | Oil sand (dilbit) |
|---|---|---|---|---|
| Spec: API / C1-C5 / 565 C+ / S (metadata) | 40 / 4 % / 4 % / 0.2 % | 31 / 2.5 % / 18 % / 1.5 % | 21.5 / 1 % / 45 % / 3.0 % | 21.5 real / 6 % / 38 % / 3.7 % |
| Riazi A, B (T0) | 1.354, 1.714 | 2.676, 1.759 | 4.256, 1.5 | bitumen 1.466, 1.5 (473 K) + condensate 0.204, 0.913 |
| API on the cuts (density 15 C, kg/m3) | 40.0 (824) | 31.0 (870) | 21.6 (923) | 29.0 (881) |
| TBP mass %: C1-C4 / naphtha C5-175 / kerosene 175-235 / diesel 235-343 / VGO 343-565 / residue 565+ | 2.1 / 28.3 / 16.6 / 25.5 / 23.5 / 4.0 | 1.3 / 15.7 / 11.0 / 21.1 / 33.0 / 18.0 | 0.5 / 10.4 / 6.5 / 12.7 / 24.9 / 45.0 | 1.8 / 20.1 / 2.7 / 11.0 / 26.3 / 38.0 |
| Open references | Bakken EC01969 (40.3, 0.1 % S), ASMB #5 EC00512 (36.8) | ANS EC02713 (32.2, 0.9 % S) | WCS EC02709 (21.5, 3.5 % S) | CLB EC02712 (22.5), WCS |

Composition, mass % (scripts `grades.js`, `variants.js`; sums 100):

| Component | Light | Medium | Heavy | Dilbit |
|---|---|---|---|---|
| Methane / Ethane / Propane | 0.04 / 0.16 / 0.60 | 0.03 / 0.10 / 0.38 | 0.01 / 0.04 / 0.15 | 0.00 / 0.06 / 0.36 |
| Isobutane / N-butane | 0.40 / 0.88 | 0.25 / 0.55 | 0.10 / 0.22 | 0.42 / 0.96 |
| Isopentane / N-pentane | 0.84 / 1.08 | 0.53 / 0.68 | 0.21 / 0.27 | 1.80 / 2.40 |
| pc01 / pc02 / pc03 | 4.90 / 10.75 / 12.91 | 2.41 / 5.78 / 7.64 | 2.13 / 3.95 / 4.71 | 10.09 / 4.19 / 1.86 |
| pc04 / pc05 / pc06 | 13.51 / 12.71 / 10.98 | 8.94 / 9.56 / 9.55 | 5.29 / 5.64 / 5.78 | 2.24 / 4.39 / 5.30 |
| pc07 / pc08 / pc09 | 9.82 / 8.86 / 6.61 | 10.13 / 11.46 / 11.81 | 6.47 / 8.06 / 9.83 | 6.47 / 8.51 / 10.63 |
| pc10 / pc11 / pc12 | 3.24 / 1.28 / 0.43 | 8.92 / 6.00 / 5.30 | 9.63 / 9.28 / 28.24 | 10.29 / 9.46 / 20.58 |

- pc12's bin is open above 1030 K (757 C), so a broad heavy tail collects there (28 % in the heavy grade). [E]
- Cut-level yields (each cut assigned by its NBP) differ from the TBP yields by 1 to 6 points; for example, the heavy grade's residue is pc10..12 = 47 %. [E]

### 3.5 Options for the owner (O3)

1. **Shape first** (table above): TBP and residue are met; heavy reads 21.6; the dilbit reads 29.0 against a real 21.5. No new components. **Recommended.**
2. API first: every API is met. Heavy has no naphtha (B 4.2), and the dilbit needs about 65 % residue: wrong column yields.
3. Structural: a second, dense cut family (Kw about 11.15; pc05+ density x1.047) for heavy and oil sand, so that API and TBP both hold. This revises E5, adds up to 8 components to the packages (column cost), and needs Tc, Pc, omega, Cp and viscosity for each. It is the physics fix if the oil-sand density must read true.

## 4. Proposal: viscosity (O4)

### 4.1 Cut candidates (`cuts.js`): ratio to the current Dalia table

| Cut (NBP K) | Dalia mPa s, 313 / 373 K | Twu 1985 + D341, 313 / 373 | Abbott 1971 + D341 | DWSIM export (Abbott path) |
|---|---|---|---|---|
| pc01 (331) | 0.27 / 0.20 | 1.04 / 0.88 | 1.09 / 0.90 | 0.84 / 0.89 |
| pc02 (383) | 0.37 / 0.26 | 1.15 / 0.99 | 1.18 / 0.93 | 0.91 / 0.92 |
| pc03 (432) | 0.72 / 0.39 | 0.91 / 0.92 | 0.96 / 0.87 | 0.74 / 0.88 |
| pc04 (480) | 1.25 / 0.59 | 0.87 / 0.90 | 0.93 / 0.91 | 0.71 / 0.91 |
| pc05 (529) | 2.23 / 0.88 | 0.91 / 0.92 | 0.95 / 0.97 | 0.73 / 0.98 |
| pc06 (578) | 4.75 / 1.43 | 0.86 / 0.90 | 0.88 / 0.99 | 0.67 / 1.00 |
| pc07 (627) | 13.1 / 2.62 | 0.70 / 0.81 | 0.70 / 0.92 | 0.53 / 0.93 |
| pc08 (687) | 55.5 / 5.97 | 0.54 / 0.72 | 0.53 / 0.81 | no matching cut |
| pc09 (766) | 540 / 22.1 | 0.44 / 0.60 | 0.44 / 0.61 | no matching cut |
| pc10 (868) | 1.7e6 / 2.5e3 | 0.0068 / 0.040 | 0.02 / 0.03 | no matching cut |
| pc11 (965) | same as pc10 | 3.1 / 0.55 | 6e6 / 0.92 (singular) | fails (97.9 m2/s) |
| pc12 (1095) | same as pc10 | 1.6e7 / 180 | fails (negative denominator) | fails |

- Twu: n-alkane reference plus an SG correction. Its check values are close for pure n-C7, n-C10, n-C16 and toluene at 100 F (0.52, 1.01, 3.07, 0.60 cSt). [E for the computation; I for the comparison values] Twu is finite and monotone over 293..900 K for every cut; at 900 K it gives 0.13..0.38 cSt. pc11 and pc12 (SG at or above 1.0) are extrapolations: 9e8 and 4e18 cSt at 293 K; the second exceeds the 1e12 Pa s cap. [E]
- Terms [I]: Twu 1985 (Ind. Eng. Chem. Process Des. Dev. 24, 1287) and Abbott, Kaufmann and Domash 1971 (Can. J. Chem. Eng. 49, 379) are published equations (licence family 12: formulas are not protected as such). ASTM D341's text is copyrighted, but the Walther form is public. DWSIM is GPL-3.0, and its output is not GPL (licence review, family 2).
- Open datasets: ECCC's [Crude Oil and Petroleum Product Database](https://open.canada.ca/data/en/dataset/53c38f91-35c8-49a6-a437-b311703db8c5) is under the Open Government Licence - Canada (attribution) [E]. It holds whole oils only (viscosity and density at 0 and 15 C, SIMDIS, SARA), with no cut viscosities, so it can check or fit mixtures but not cut tables [E]. NOAA ADIOS mirrors it (EC records); `noaa-oil-data` states no licence and also holds ExxonMobil-derived records, so cite ECCC, not ADIOS. [E]

### 4.2 Mixture (`mix.js`, `refit.js`): whole grade, mPa s at 15 C / 40 C

| Grade | Current (Dalia + delta) | (a) Twu, log-mole, delta = 0 | (b) Twu, Refutas mass VBN | (c) Twu, log-mole, one-term open refit | Open reference (ECCC, 40 C by D341 from 0 and 15 C [I]) |
|---|---|---|---|---|---|
| Light | 3.85 / 2.12 | 1.94 / 1.11 | 3.00 / 1.73 | 3.5 / 2.0 | ASMB #5 6.0 / 1.6 |
| Medium | 17.7 / 6.89 | 7.99 / 3.39 | 17.8 / 7.24 | 15.1 / 6.4 | ANS 10.0 / 4.8 |
| Heavy | 108 / 28.0 | 98.6 / 29.7 | 857 / 168 | 200 / 60 | WCS 226 / 55 |
| Dilbit | 12.9 / 5.01 | 11.0 / 4.52 | 72.9 / 22.7 | 17 / 7.1 | CLB 360 / 103 |

- Refutas: VBN = 14.534 ln ln(nu + 0.8) + 10.975, averaged by mass. It needs nu > 0.2 cSt, so light ends are clipped at 0.25 cSt. [I]
- (c) is delta = G (x_cut^2 - sum x_i^2) over the cuts, the c0 term alone, with **G = 0.878**. It is fitted by least squares to six open points: ASMB, ANS and SCO at 0 and 15 C, each TBP put on the cuts. With Twu cuts, measured/predicted is 0.55 to 1.39; the current 4-coefficient delta gives 0.55 to 1.37 on the same points. On the four grades, exp(0.878 pair) = 1.81, 1.89, 2.03 and 1.57, against the current 1.74, 1.84, 2.00 and 1.55 at 15 C. [E]
- No rule fixes the dilbit: on the real CLB and WCS TBPs, every form gives 0.04 to 0.17 of the measured value. The cause is the cut data (light-crude cuts, too light and too thin in the residue), not the mixing rule; option 3.5-3 addresses it. [E for the numbers, I for the cause]

### 4.3 Is `LiquidMixtureCorrection` still needed?

- Removal, as a small physics simplification:
  - Effect: every crude's liquid viscosity falls by a factor 1.55 to 2.11 (15 C) and 1.37 to 1.65 (100 C). Against the open records with Twu cuts, the ratios drop to 0.29 to 0.76.
  - Deletes: `LiquidMixtureCorrection.java` (63 lines), its catalog plumbing, `liquid_mixture` in `crude_pr78.json`, 12 `liquid_mixture_descriptor` blocks and `LiquidMixtureCorrectionTest`.
  - Risks: laminar pressure drop halves (turbulent drop moves about 15 to 20 %), sizing results and viscosity references move, the light-crude underprediction worsens, and it gives up the size-asymmetry term the physics supports. It breaks no conservation law.
  - Not recommended. [I]
- Refit, recommended: keep the term and drop it to one open coefficient (G = 0.878, ECCC OGL attribution). This deletes c1..c3, the residue-retention and activation-slope descriptors, `reference_kelvin` and `slope_cap_kelvin`, and moves the whole-grade viscosity, relative to the current delta, by +1 to +5 % at 15 C, +5 to +9 % at 40 C and up to +14 % at 100 C, because the one term has no temperature slope. [E for the numbers]

## 5. Recommendation and owner decisions

| # | Question | Options | Recommended |
|---|---|---|---|
| O3-1 | Generation form | shape first / API first / dense second family | shape first (3.5-1); a dense family later if heavy and oil-sand density must read true |
| O3-2 | Oil-sand grade | dilbit / SCO / raw bitumen | dilbit = 76 % generated bitumen + 24 % condensate |
| O3-3 | Light-end split and whether the light grade keeps 0.04 % methane | as 3.1 / other | as 3.1 (assumption, owner's choice) |
| O4-1 | Cut viscosity source | Twu 1985 / Abbott (DWSIM) / DWSIM export / keep Dalia | Twu 1985 + D341 for all 12 cuts, mu = nu x rho(T), new revision; DWSIM and Abbott fail on pc11 and pc12 |
| O4-2 | pc11, pc12 above Twu's range | Twu as is (capped at 1e12 Pa s) / hold a residue-class line | Twu as is; the heavy grade's viscosity moves x2 with this choice (98.6 vs 208 mPa s uncapped) |
| O4-3 | Mixture correction | remove / one-term open refit / 4-term refit | one-term open refit G = 0.878 |
| O4-4 | Data download | ECCC `ECCC_Petroleum_Products_Database-2021-01-22.EN.csv` and the Analytical Methods PDF (2021-01-13), [ECCC Data Mart](https://data-donnees.ec.gc.ca/data/substances/scientificknowledge/spills-technology-databases/environment-canada-crude-oil-and-petroleum-product-database/?lang=en), OGL-Canada, size not shown | needs permission. The values used here were read from the ADIOS API pages (EC01969, EC00512, EC02713, EC00519, EC02709, EC02712, EC02695, EC03126); a refit for production would read the CSV |

- Open items: the dilbit viscosity (20 times low on any rule, until option 3.5-3); the unexplained PF_v1 offset in the DWSIM export; Bakken EC01969's 44 mPa s at 15 C is not reviewed and was left out of the fit. [I]
- Code guide: no change (research only).

## 6. Production data (E10-E14)

**Generator.** [`tools/crude-grades/`](../../tools/crude-grades/README.md) has six entry points and a README. It writes to `tmp/crude-grades/out/` and nothing under `src/`.

**Cut tables** [E]: 12 blocks in the schema of `crude_pc05.json`, revision `twu-1985-d341-r1`.
- The game reads a `log_table` value as dynamic viscosity in Pa s: ln mu linear between the 1 K nodes, an Andrade continuation below 293.15 K, the last value held above 900 K.
- So mu = nu(Twu 1985 + D341) x rho(T). rho is the Rackett density (Spencer-Danner, Yamada-Gunn Z_RA from omega), anchored at each record's standard density and held above Tc. It is within 0.1 % of section 4's rule at 313 K and holds up to 900 K.
- The ratios to Dalia match table 4.1. crude_pc12 reaches the 1e12 Pa s cap at 14 nodes (293-306 K).

**G refit** [E] (`g-fit.md` lists every record):
- Selection: of 106 fresh samples, 74 are out, leaving 58 points on 32 crudes. Out: refined products and non-crudes (24), no boiling curve (26), ASTM D2887 curves (21; they end at 538 C and give percentages of the eluted part), truncated D7169 curves reading more than 10 API light (2), no viscosity above the pour point (1). Points at or below the pour point are dropped.
- The CSV has no review flag. ADIOS's "Not Reviewed" is on every EC record, so no record was dropped for it.

| Fit (ln mu residual) | G | mean | rms (factor) | within x2 |
|---|---|---|---|---|
| Full set, least squares | **1.346** | +0.11 | 1.20 (x3.3) | 26/58 |
| Full set at the review's 0.878, and at 0 | 0.878, 0 | +0.46, +1.11 | 1.25 (x3.5), 1.57 (x4.8) | 26, 24 |
| Representable subset (|dAPI| <= 3, 13 crudes); same subset at 1.346 | 1.096 | +0.02; -0.18 | 0.64 (x1.9); 0.67 (x1.9) | 17/23; 16/23 |

**Records:**
- Assays: `icheme:light_crude`, `medium_crude`, `heavy_crude` and `oil_sand_dilbit`; mass basis, revision `generated-r1`, generated provenance, compositions as in table 3.4.
- `liquid_mixture`: proposed `cut_pair_v1`, G = 1.3464 over crude_pc01..12, revision `eccc-cut-pair-r1`. The current Java gives exactly the same delta with coefficients [G, 0, 0, 0] and every cut descriptor set to {1, 0, 0} [E, algebra].
- Presets (drafts, **need a solver check**):
  - The feed has the same standard volume as tia_juana (660 m3/h).
  - Draws on trays 11/19/29 are 80 % of pc02+03 / pc04+05 / pc06. On Tia Juana this rule gives 511/407/156 kmol/h, against the template's 491/515/165.
  - Each draw keeps the pumparound above it, scaled by its cuts' mass. Steam scales with the pc07+ mass.

| Grade | API (rho15) | Feed kmol/h | Draws kmol/h | PA MW | mu 15/40 C, Twu + G | Current rule | ECCC, mu 15/40 C (40 C by D341 from 0 and 15 C [I]) |
|---|---|---|---|---|---|---|---|
| Light | 40.0 (825) | 3284 | 870/644/200 | 21.9/28.2/14.4 | 4.8/2.8 | 3.8/2.1 | ASMB #5 6.0/1.6; #4 8.0/2.3 |
| Medium | 31.0 (870) | 2557 | 518/476/184 | 13.1/21.0/13.2 | 21.1/9.0 | 17.7/6.9 | ANS 2015 10/4.7; 2002 12/5.3; 2012 14/7.2 |
| Heavy | 21.6 (924) | 1898 | 357/299/118 | 9.0/13.2/8.5 | 291/88 | 108/28 | Hebron 2018 577/75; WCS 226/54 |
| Dilbit | 29.0 (881) | 2537 | 250/168/103 | 6.0/7.6/7.4 | 22/9.0 | 13/5.0 | CLB 2015 360/102; WCS 226/54 |

**Owner decisions:**
1. G = 1.346 (full set, E14) or 1.096 (representable subset). The subset value lowers every grade by 12-18 % (exp(-0.25 x pair fraction 0.5-0.8)), and the medium grade, now 1.5-2 times ANS, moves closer.
2. The light, medium and heavy grades carry 0.01-0.04 % methane, so they apply only to packages that hold Methane (tjl20, not tjl19).
3. The light grade's heavy-naphtha draw is 26 % of the feed with a 21.9 MW pumparound; the solver check must confirm its liquid supply.
4. The E11 dilbit limits stand: it reads API 29, and its viscosity is 16 times low.
