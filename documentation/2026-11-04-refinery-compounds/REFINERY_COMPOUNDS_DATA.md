# Refinery compounds: pure-component data and sources

Batch [2026-11-04-refinery-compounds](README.md). Companion of [REFINERY_COMPOUNDS_REVIEW.md](REFINERY_COMPOUNDS_REVIEW.md). Collected 2026-11-04. The tables give the constants a `properties` record and a `spine` record need first (M, Tb, Tc, Pc, omega, the E-PPR78 groups) and the freezing point the review proposes. The ideal-gas Cp, liquid density reference and viscosity tables are taken at build time, from the sources in section 5.

**Freezing point column (Tf).** For a compound with a reference equation of state this is its triple point (TP), the equation's own lower end and the anchor a crystal record uses. Otherwise it is the NIST normal melting temperature at 101.325 kPa (Tfus), with the NIST triple point in brackets where NIST lists one. For organics the two differ by 0.01 to 0.1 K. CO2 is the exception: its triple point lies at 518 kPa, and at 101.325 kPa it sublimes at 194.7 K.

Units: K, MPa, g/mol. "E-PPR78" gives the group counts of scheme `eppr78-2022` (`Eppr78Groups`); "none" means the scheme has no group for it, so its pairs need a constant k_ij or another model.

## 1. Compounds already in the catalog (freezing point only)

| Component id | Tf K | Kind | Source (CoolProp 8.0.0 file at ae81610e, local copy) |
|---|---|---|---|
| Methane | 90.6941 | TP | Setzmann and Wagner, JPCRD 1991 |
| Ethane | 90.368 | TP | Bücker and Wagner, JPCRD 2006 |
| Propane | 85.525 | TP | Lemmon, McLinden and Wagner, JCED 2009 |
| Isobutane | 113.73 | TP | Bücker and Wagner, JPCRD 2006B |
| N-butane | 134.895 | TP | Bücker and Wagner, JPCRD 2006B |
| Isopentane | 112.65 | TP | Lemmon and Span, JCED 2006 |
| N-pentane | 143.47 | TP | Thol et al., FPE 2019 |
| Nitrogen | 63.151 | TP | Span et al., JPCRD 2000 |
| CarbonDioxide | 216.592 at 0.51796 MPa | TP (sublimes at 194.7 K at 1 atm) | Span and Wagner, JPCRD 1996; matches the `crystal_carbon_dioxide_i` anchor |
| Water | 273.16 at 611.655 Pa | TP (normal melting 273.15) | IAPWS-95 (Wagner and Pruss, JPCRD 2002); matches the water model's `triple_point` and `crystal_ice_ih` |

Crude pseudo-components `crude_pc01`..`crude_pc12` have no measured freezing point: each is a mixture that freezes over a range. The estimate below is the melting point of the n-paraffin with the fraction's molar mass, Riazi and Al-Sahhaf, Ind. Eng. Chem. Res. 34 (1995) 4145, `Tm = 397 - exp(6.5096 - 0.14187 M^0.47)`, valid for C5..C40 (M about 70..565). It is an upper bound, the temperature at which the fraction's wax would melt. A real fraction holds 20 to 40 % n-paraffins and pours far lower: at pc04, a kerosene cut, the bound is 254 K, while jet fuel freezes below 233 K.

| Fraction | M g/mol | NBP K | SG | Tm n-paraffin K | Note |
|---|---|---|---|---|---|
| crude_pc01 | 81.06 | 331.4 | 0.712 | 177.8 | |
| crude_pc02 | 105.52 | 383.5 | 0.757 | 207.9 | |
| crude_pc03 | 131.60 | 431.8 | 0.792 | 232.4 | |
| crude_pc04 | 161.72 | 480.2 | 0.821 | 254.3 | |
| crude_pc05 | 197.10 | 529.1 | 0.847 | 274.3 | |
| crude_pc06 | 238.33 | 577.9 | 0.870 | 292.3 | |
| crude_pc07 | 286.51 | 626.7 | 0.891 | 308.5 | |
| crude_pc08 | 358.17 | 687.0 | 0.914 | 326.3 | |
| crude_pc09 | 475.43 | 766.0 | 0.941 | 345.7 | |
| crude_pc10 | 678.40 | 868.2 | 0.972 | 364.8 | M above C40: extrapolated |
| crude_pc11 | 937.77 | 965.0 | 1.004 | 377.5 | M above C40; SG > 1, mostly aromatic/resin: bound not meaningful |
| crude_pc12 | 1474.83 | 1095.1 | 1.051 | 388.6 | same as pc11 |

M, NBP and SG are taken from the bundled `properties/crude_pcNN.json` (revision `crude-regrouped-r1`).

## 2. New compounds with a CoolProp reference EOS

These values come from the CoolProp 8.0.0 fluid pages (`coolprop.org/fluid_properties/fluids/<Name>.html`), and for the first eight from the local fluid files in `research/2026-09-24-coolprop-low-temperature/sources/coolprop/`. Tb and Tfus come from NIST WebBook phase-change pages (`webbook.nist.gov/cgi/cbook.cgi?ID=C<CAS>&Mask=4`). Tc and Pc are the EOS critical point, rounded. "Tb —" means the build reads it from CoolProp (its pressure-1-atm saturation).

| Compound | CAS | M | Tb | Tf (kind) | NIST Tfus | Tc | Pc | omega | E-PPR78 | EOS reference |
|---|---|---|---|---|---|---|---|---|---|---|
| Hydrogen | 1333-74-0 | 2.01588 | — | 13.957 TP | — | 33.145 | 1.2964 | -0.219 | H2 | Leachman et al., JPCRD 2009 |
| Hydrogen sulfide | 7783-06-4 | 34.08088 | — | 187.7 TP | — | 373.1 | 9.000 | 0.1005 | H2S | Lemmon and Span, JCED 2006 |
| Carbon monoxide | 630-08-0 | 28.0101 | — | 68.16 TP | — | 132.86 | 3.494 | 0.0497 | CO | Lemmon and Span, JCED 2006 |
| Oxygen | 7782-44-7 | 31.9988 | — | 54.361 TP | — | 154.581 | 5.043 | 0.0222 | O2 | Schmidt and Wagner 1985 / Stewart et al. 1991 |
| Argon | 7440-37-1 | 39.948 | — | 83.806 TP | — | 150.687 | 4.863 | -0.00219 | Ar | Tegeler et al., JPCRD 1999 |
| Ammonia | 7664-41-7 | 17.03052 | — | 195.49 TP | — | 405.56 | 11.3634 | 0.2557 | NH3 | Gao et al., JPCRD 2020 |
| Ethylene | 74-85-1 | 28.05376 | — | 103.989 TP | — | 282.35 | 5.0418 | 0.0866 | C2H4 | Smukala et al., JPCRD 2000 |
| Propylene | 115-07-1 | 42.07974 | — | 87.953 TP | — | 364.211 | 4.555 | 0.146 | CH3 1, CH2_alkenic 2 | Lemmon et al. 2013 |
| 1-Butene | 106-98-9 | 56.10632 | 266.8 | 87.80 TP | (no row; ΔHfus at 87.81) | 419.290 | 4.0057 | 0.1919 | CH3 1, CH2 1, CH2_alkenic 2 | Lemmon and Ihmels, FPE 2005 |
| Isobutene | 115-11-7 | 56.10632 | 266.7 | 132.4 TP | 132.38 | 418.090 | 4.0157 | 0.1926 | CH3 2, CH2_alkenic 1, C_alkenic 1 | Lemmon and Ihmels 2005 |
| cis-2-Butene | 590-18-1 | 56.10632 | 276.84 | 134.3 TP | 133.85 (TP 134.26) | 435.750 | 4.2360 | 0.2024 | CH3 2, CH2_alkenic 2 | Lemmon and Ihmels 2005 |
| trans-2-Butene | 624-64-6 | 56.10632 | 274.2 | 167.6 TP | 167.35 (TP 167.62) | 428.611 | 4.0191 | 0.2101 | CH3 2, CH2_alkenic 2 | Lemmon and Ihmels 2005 |
| Neopentane | 463-82-1 | 72.14878 | 282.6 | 256.6 TP | 255 ± 3 (TP 256.76) | 433.740 | 3.1963 | 0.1961 | CH3 4, C 1 | Lemmon and Span 2006 |
| n-Hexane | 110-54-3 | 86.17536 | 341.9 | 177.83 TP | 178 ± 1 | 507.82 | 3.0441 | 0.3003 | CH3 2, CH2 4 | Thol et al., FPE 2019 |
| 2-Methylpentane | 107-83-5 | 86.17536 | 334 | 119.6 TP | 120 ± 7 (TP 119.55) | 497.701 | 3.0427 | 0.2797 | CH3 3, CH2 2, CH 1 | Lemmon and Span 2006 |
| n-Heptane | 142-82-5 | 100.202 | 371.5 | 182.55 TP | 182.6 | 541.226 | 2.7738 | 0.349 | CH3 2, CH2 5 | Span and Wagner, IJT 2003 |
| n-Octane | 111-65-9 | 114.229 | 398.7 | 216.37 TP | 216.3 | 568.74 | 2.4836 | 0.3975 | CH3 2, CH2 6 | Beckmüller et al., IJT 2019 |
| n-Nonane | 111-84-2 | 128.2551 | 423.8 | 219.7 TP | 219.5 | 594.548 | 2.2819 | 0.4433 | CH3 2, CH2 7 | Lemmon and Span 2006 |
| n-Decane | 124-18-5 | 142.28168 | 447.2 | 243.5 TP | 243.3 | 617.699 | 2.1013 | 0.4884 | CH3 2, CH2 8 | Lemmon and Span 2006 |
| n-Dodecane | 112-40-3 | 170.33484 | 489 ± 2 | 263.6 TP | 263.5 | 658.100 | 1.8176 | 0.5742 | CH3 2, CH2 10 | Lemmon and Huber, Energy Fuels 2004 |
| Cyclopentane | 287-92-3 | 70.1329 | 322.4 | 179.70 TP | 179.2 (TP 179.71) | 511.720 | 4.5828 | 0.2019 | CH2_cyclic 5 | Gedanitz et al., JCED 2015 |
| Cyclohexane | 110-82-7 | 84.15948 | 353.9 | 279.47 TP | 279.6 | 553.600 | 4.0805 | 0.2093 | CH2_cyclic 6 | Zhou et al., JPCRD 2014 |
| Benzene | 71-43-2 | 78.1118 | 353.3 | 278.674 TP | 278.64 | 562.020 | 4.9063 | 0.2108 | CHaro 6 | Thol et al., HTHP 2012 |
| Toluene | 108-88-3 | 92.13842 | 383.8 | 178.0 TP | 178.1 | 591.749 | 4.1263 | 0.2657 | CHaro 5, Caro 1, CH3 1 | Lemmon and Span 2006 |
| Ethylbenzene | 100-41-4 | 106.165 | 409.3 | 178.20 TP | 179 ± 2 (TP 178.15) | 617.12 | 3.6224 | 0.304 | CHaro 5, Caro 1, CH2 1, CH3 1 | Zhou et al., JPCRD 2012 |
| o-Xylene | 95-47-6 | 106.165 | 417 ± 2 | 247.985 TP | 248 ± 2 (TP 247.8) | 630.259 | 3.7375 | 0.312 | CHaro 4, Caro 2, CH3 2 | Zhou et al. 2012 |
| m-Xylene | 108-38-3 | 106.165 | 412.3 | 225.3 TP | 225 ± 4 (TP 219.6, 1930) | 616.89 | 3.5346 | 0.326 | CHaro 4, Caro 2, CH3 2 | Zhou et al. 2012 |
| p-Xylene | 106-42-3 | 106.165 | 411.4 | 286.40 TP | 286.3 | 616.168 | 3.5315 | 0.324 | CHaro 4, Caro 2, CH3 2 | Zhou et al. 2012 |
| Methanol | 67-56-1 | 32.04216 | 337.8 | 175.61 TP | 176 ± 1 (TP 175.5) | 513.380 | 8.2159 | 0.5649 | none | de Reuck and Craven, IUPAC 1993 |
| Ethanol | 64-17-5 | 46.06844 | 351.5 | 159.10 TP | 159 ± 2 | 514.709 | 6.2679 | 0.644 | none | Schroeder et al., JPCRD 2014 |
| Sulfur dioxide | 7446-09-5 | 64.0638 | — | 197.7 TP | 200.75 (TP 197.64) | 430.64 | 7.8866 | 0.2561 | SO2 | Gao et al., JCED 2016 |
| Carbonyl sulfide | 463-58-1 | 60.0751 | 222.9 | 134.3 TP | (no row; ΔHfus at 134.33) | 378.770 | 6.3688 | 0.0978 | COS | Lemmon and Span 2006 |
| Hydrogen chloride | 7647-01-0 | 36.4609 | — | 159.07 TP | 161.15 ± 2 | 324.680 | 8.3135 | 0.1297 | none | Thol et al., JCED 2018 |
| Dimethyl ether | 115-10-6 | 46.06844 | 248.2 | 131.66 TP | 135.2 ± 2 (TP 131.66) | 400.378 | 5.3367 | 0.196 | none | Wu et al., JPCRD 2011 |

## 3. New compounds without a reference EOS (PR78 from critical constants)

Tc, Pc, Tb, Tfus and Ttriple come from the NIST WebBook phase-change pages, except † (ChemSep v8.3 or the IUPAC critical-property review, part 11). omega is ChemSep v8.3 (DIPPR-derived) as quoted by Cheméo (`chemeo.com/search?q=<CAS>`), cross-checked against PSRK rev. IV, Passut and Danner 1973 and Yaws (Caleb Bell's `chemicals` data files).

| Compound | CAS | M | Tb | Tf (kind) | Tc | Pc | omega | omega spread | E-PPR78 |
|---|---|---|---|---|---|---|---|---|---|
| 1,3-Butadiene | 106-99-0 | 54.0904 | 268.6 | 164.24 TP | 425 ± 1 | 4.32 | 0.1950 | 0.181..0.195 | CH2_alkenic 4 |
| Isooctane (2,2,4-TMP) | 540-84-1 | 114.2285 | 372.4 | 165.76 TP | 543.9 | 2.57 | 0.3040 | 0.303..0.304 | CH3 5, CH2 1, CH 1, C 1 |
| Methylcyclopentane | 96-37-7 | 84.1595 | 345.0 | 130.5 TP | 532.7 | 3.79 | 0.2270 | 0.227..0.240 | CH3 1, CH2_cyclic 4, CH_cyclic 1 |
| Methylcyclohexane | 108-87-2 | 98.1861 | 374.0 | 146.58 TP | 572.1 (NIST avg 573 ± 2) | 3.48 | 0.2350 | 0.233..0.248 | CH3 1, CH2_cyclic 5, CH_cyclic 1 |
| n-Hexadecane | 544-76-3 | 226.4412 | 554 ± 10 (check) | 291.3 TP | 722 ± 4 | 1.40 | 0.7260 | 0.717..0.742 | CH3 2, CH2 14 |
| Naphthalene | 91-20-3 | 128.1705 | 490 ± 5 | 353.39 TP | 748 ± 6 | 4.1 | 0.3040 | 0.302..0.304 | CHaro 8, Cfused_aromatic 2 |
| 1-Methylnaphthalene | 90-12-0 | 142.1971 | 515 ± 7 | 242.66 TP | 772 | 3.6 | 0.3480 | 0.310..0.348 | CHaro 7, Caro 1, Cfused_aromatic 2, CH3 1 |
| Thiophene | 110-02-1 | 84.140 | 357.3 | 235.02 TP | 579.4 | 5.70 | 0.1860 | 0.186..0.200 | none |
| MTBE | 1634-04-4 | 88.1482 | 328.2 | 164.56 TP | 496.4 (497.1 Ambrose 1974) | 3.397 | 0.2661 | 0.266..0.269 | none |
| Methanethiol | 74-93-1 | 48.107 | 279.1 | 150.16 TP | 469.9 | 7.23 | 0.1500 | 0.149..0.158 | CH3 1, SH 1 |
| Ethanethiol | 75-08-1 | 62.134 | 309 ± 1 | 125.25 TP (Tfus 126.08) | 499 | 5.49 | 0.1890 | 0.186..0.191 | CH3 1, CH2 1, SH 1 |
| Dimethyl sulfide | 75-18-3 | 62.134 | 311 ± 3 | 174.85 TP | 503.0 | 5.53 | 0.1900 | 0.190..0.194 | none |
| MEA | 141-43-5 | 61.0831 | 444.0 | 283.46 Tfus | 671.4 (decomposes) | 8.03 | 0.5508 | 0.447..0.568 | none |
| DEA | 111-42-2 | 105.1356 | 544.2 | 301.1 Tfus | †736.6 | †4.72 (4.27..4.72) | 0.9915 | 0.953..1.046 | none |
| MDEA | 105-59-9 | 119.1622 | 520.2 | 252.15 Tfus (Cheméo) | †741.9 | †4.16 | 1.1649 | — | none |

## 4. Values to confirm at build time

- n-Hexadecane Tb: NIST gives only a 10-value average, 554 ± 10 K. Take a TRC or DIPPR value before use (not read here).
- m-Xylene: NIST's one triple point (219.6 K, 1930) is 5.7 K below CoolProp's 225.3 K and NIST's own Tfus. Keep CoolProp's value.
- SO2, HCl, DME: the old NIST Tfus values (1910 to 1946) are 2 to 3.5 K above the triple point. Keep the EOS triple point.
- Methyl mercaptan, ethyl mercaptan and DMS: Tc and Pc rest on one 1924 source (Berthoud and Brum). Thiophene's Pc rests on one 1956 value.
- DEA's Pc spreads from 3.27 to 4.72 MPa across sources. The amines decompose near Tc, so every one of their critical constants is an extrapolation.
- All web values were read through a page summarizer. Benzene's CoolProp row (Tc 562.0196911, Pc 4906288.78 Pa, omega 0.2108369733, TP 278.674 K) was re-read verbatim and agrees. Re-read every digit the build copies.

## 5. Sources for the remaining record fields

| Field | Source |
|---|---|
| Ideal-gas Cp, formation enthalpy, standard entropy (spine `nasa7`/`nasa9`) | Burcat, Ruscic and Goos, Extended Third Millennium Ideal Gas Thermochemical Database (NASA 7-term); Active Thermochemical Tables (ATcT) for formation enthalpies (CoolProp commit 9b35f538 carries them per fluid; the local `research/2026-09-24-coolprop-low-temperature/sources/coolprop/hformation-atct-9b35f538.json` holds the 18 local fluids); NIST WebBook Shomate fits for the small gases |
| Ideal-gas Cp (`shifted_polynomial_5`, non-spine packages) | Fitted to the same Burcat or CoolProp CP0 data, 298.15..900 K, as the bundled records are |
| Liquid volume reference, `volume_translation` | CoolProp saturated liquid for section 2; DIPPR 105 liquid density (ChemSep) for section 3 |
| Viscosity tables | CoolProp transport models where present (section 2); NIST TRC or Viswanath et al., Viscosity of Liquids (2007) for the rest |
| Fusion enthalpy (needed by any freeze-out model) | NIST WebBook phase-change pages, "Enthalpy of fusion" rows (same pages as above); CoolProp melting lines where present |
