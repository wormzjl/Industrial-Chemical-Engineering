# Supercritical fluids: qualification of the bundled network model (S0)

Status: measurement, 2026-11-15, on branch `claude/supercritical-fluid-support-d0ffa4` (base `e9edc974b`, main 0.31.0).
No production code changed. Test: `G3F11SupercriticalRegionTest` (8 tests), with the oracle's
`HelmholtzReferenceParityTest` (26 tests); both green. Plan: [SUPERCRITICAL_PLAN.md](SUPERCRITICAL_PLAN.md), stage S0.

## Summary

**Expected.** Above Tc and Pc a pure fluid is one phase; PR78 with a constant translation should give density within
about 6 % and cp within about 7 % away from the critical point (the pilot's 10 MPa numbers), worse near it. A dilute-gas
viscosity is far too low at liquid-like density. A mixture split near its critical point has compositions close
together; its densities need not be.

**What the bundled network model does** (the game's catalog, `icheme:refinery` on the derived cubic):

1. Pure fluids, Tr 1.02-2 (and 300, 500, 700 K above 2 Tc) to 30 MPa, outside the band boxes: CO2, N2, CH4, C2H6 and
   C3H8 keep density within 5.6 % and cp within 11 %; the G3 bounds (7 % density, 12 % cp) hold to 30 MPa. Hydrogen
   misses cp only at 34-50 K (to +28 %); at T >= 273 K it holds to 30 MPa. Ethylene misses once, cp -12.1 % at
   310.6 K and 7.6 MPa on the edge of box 1 (as nitrogen's -10.6 % in G3); every other state holds to 30 MPa. The
   isothermal acoustic bound is within 15 % outside the band (hydrogen below 50 K: 30 %). Inside the boxes: density to -13 %, cp to -20 %.
2. Water above Tc (wet list, the bare cubic): -30 % density, +36 % cp at 650 K and 25 MPa; density within 5 % from
   700 K; cp -16 to -19 % at 700 K, within 6 % from 800 K; the departure 550-750 J/mol low at 800-900 K.
3. M2 (O6): only water has a Mathias-Copeman alpha. Its cp step at Tc is +0.78 J/(mol K) (1.5 %) at Pr 0.5 and +5.94
   (4.4 %) at Pr 2, equal to the algebra to three decimals; at Pr 1, 3.3 J/(mol K) on a cp that diverges there.
4. M3 (O3): 9 of 36 isotherms of four binaries reach a split with a tie line below 0.1; the others leave the envelope at
   a bubble or dew point with a tie line of 0.11-1.7, or show no split on the 10 % pressure scan. At the first short
   tie line the products still differ up to 2.4 times in density: one fluid there is off by -2.7 to +17 % in density
   and -187 to +353 J/mol in enthalpy. No request failed.
5. M4 (O5): the dilute viscosity is 13-96 % of CoolProp's (CO2 at 319 K, 10 MPa: 49 %). Dilute plus Jossi-Stiel-Thodos
   is within -16/+8 % (p95 11 %) on the reference critical density, -3/+28 % (p95 17 %) on the cubic's `v_c = 3.9514 b`.
6. M5 (pc01-pc04 surrogates): PR78 on each fluid's own constants, translated at 288.7 K: density within 5.7 % to
   30 MPa for n-hexane, n-heptane, toluene (omega 0.27-0.35); +7.1 % n-decane (0.49) and +9.5 % n-dodecane (0.57) at
   30 MPa: the error grows with omega. The bundled n-hexane and toluene datasets: -7.3 % and -9.0 % near Tr 1.02-1.15.
7. M6 (O8): today's gas-cut pairs are **not zero**. `DerivedPairs` (r7) gives each gas with Henry data an E-PPR78 pair
   with every cut (paraffinic counts by molar mass, at 298.15 K): CO2 0.10-0.12, N2 0.007-0.15, H2S 0.01-0.06, CH4
   0.02-0.06; hydrogen 0 (owner rule). The data review's P5 premise holds for the method record only. Sensitivity:
   CO2 in the liquid at 320 K, 2 MPa is 0.196 today, 0.293 at k_ij 0, 0.160 at 0.15; H2S at 320 K, 2 MPa splits only
   from 0.05. The oil in the gas moves by less than 10 % except at 10 MPa.
8. M6 (O9): 224 pairs of real compounds with E-PPR78 counts are zero today: 197 `NONPOLAR` (15 with |k(300 K)| >= 0.05;
   ethylene/isobutene -0.21 at 300 K, -368 at 500 K) and 27 `HYDROGEN` (0.28-0.47). 118 pairs have no E-PPR78 value.
9. M7: three binaries against CoolProp's mixture model to 30 MPa: one phase at all 105 single-phase states, density
   within -5.0 % (p95 2.8 %).

**Proposals** (the owner's to validate):

- O4 A: `pressure_max_pascal` 30 MPa for CO2, N2, CH4, C2H6, C3H8, H2 and C2H4 (C2H4 with 13 % cp declared at the edge of
  box 1); n-hexane and toluene at their measured 4.6 and 6.2 MPa; water stays (S6). Compressed liquids above 2 MPa are
  not measured here: confirm them before raising a range that liquids use.
- O3: A costs up to 17 % density at a tie line of 0.1. Take B, or A with a smaller tie line; S1 states the gap.
- O5 A with O10 A (the reference critical volume): -16/+8 %; on the cubic's own volume +28 %.
- O6 A: leave the step (water only, 1.5-4.4 % of cp) and declare it.
- O8: restate the options against today's derived E-PPR78 pairs. O9: E-PPR78 at 300 K held constant, as the derivation
  does for gases; its effect on mixtures is not measured.

## Decision sizes

| Decision | Measure | Size |
|---|---|---|
| O3 (merge a split with tie line < 0.1) | one-fluid error at the first tie line below 0.1, 9 isotherms of 4 binaries | density -2.7 to +17 %, enthalpy -187 to +353 J/mol |
| O4 (pressure ranges) | highest grid pressure with density <= 7 % and cp <= 12 % outside the band | 30 MPa: CO2, N2, CH4, C2H6, C3H8, H2 (T >= 273 K); C2H4 6.05 MPa strict, 30 MPa but one box-edge state at -12.1 %; N-hexane 4.57, toluene 6.19 MPa; surrogates pc01-pc02 30, pc03 20, pc04 10 MPa |
| O5 (dense viscosity) | ratio to CoolProp, 60 states, Tr 1.05-1.5, 5-30 MPa | dilute 0.13-0.96; JST on the reference rho_c -16/+8 % (p95 11 %); on the cubic v_c -3/+28 % (p95 17 %) |
| O6 (Mathias-Copeman step) | cp step at Tc, water only | +0.78 J/(mol K) (1.5 %) at Pr 0.5, +5.94 (4.4 %) at Pr 2, 3.3 at Pr 1 on a diverging cp |
| O8 (gas-cut pairs) | today's derived pairs; gas mole fraction in the liquid at k_ij 0 and 0.15 | today E-PPR78 0.007-0.15 (H2 0); CO2 at 320 K, 2 MPa: 0.196 today, 0.293 at 0, 0.160 at 0.15; H2S at 320 K, 2 MPa: phase count changes |
| O9 (missing pairs by E-PPR78) | pairs at zero today, counts on both | 197 NONPOLAR (15 above 0.05 at 300 K, largest 0.21), 27 HYDROGEN (0.28-0.47, owner rule), 118 without an E-PPR78 value |

## Not measured, and uncertain

- The error of estimating a cut's Tc, Pc and acentric factor (M5 measures the model at a surrogate's own constants).
- Compressed (subcritical) liquids above 2 MPa of the datasets O4 would raise; the effect of O9's pairs on mixtures;
  the bubble pressure of a crude with 1 % gas (the plan's form of M6; the brief's equal-amount TP form was run).
- M3's 10 % coarse pressure scan can miss a two-phase window narrower than 10 % ("no split" rows).
- The engine of M3, M6 and M7 is the network's own, built as `NetworkPhaseEngine.of` builds it (the model's evaluator on
  its derived contract). `PackageBinding.equilibrium(bundled, icheme:refinery)`, which the brief names, takes the dry
  path: the package lists no water; the network adds it through `MaterialCatalog.withWater`.
- The Jossi-Stiel-Thodos coefficients of the brief (1.0230, 0.23364, 0.58533, -0.40758, 0.093324, mu in cP, `+ 1e-4`)
  are the published ones times ten; with cP and `+ 1e-4` the residual would not vanish at zero density. The test uses
  the statement of Lohrenz, Bray and Clark (J. Pet. Technol. 16 (1964) 1171): 0.1023, 0.023364, 0.058533, -0.040758,
  0.0093324, mu in cP, `xi = Tc^(1/6) M^(-1/2) Pc^(-2/3)` in K, g/mol, atm (after Jossi, Stiel and Thodos, AIChE J. 8
  (1962) 59). Checked against an LBC summary (pkirkham.github.io/analysis/viscosity-lbc) and the polykin documentation of
  `MUVPC_Jossi` (hugomvale.github.io/polykin), not against the 1962 paper itself.
- n-heptane's fluid file gives Tc 540.13 K and Pc 2.736 MPa (`STATES.critical`, used for the grid); CoolProp 8 reports
  541.23 K and 2.774 MPa for the same equation. Band boxes are taken on the model's own Tc and Pc, the grid on the
  oracle's.
- The M6 crude is the medium crude preset (`icheme:medium_crude`, one of the four `AmbientCrudePresetTest` loops over);
  "oil" is the sum of the twelve cuts.
- Hydrogen below 50 K is a quantum fluid; PR78 is not expected to hold there.

## Oracle and fixtures

- Seven CoolProp fluid files from revision `ae81610e` (n-Propane, Ethylene, n-Hexane, n-Heptane, Toluene, n-Decane,
  n-Dodecane; SHA-256 in `src/test/resources/science/thermo/coolprop/README.md`). n-Heptane's ideal part is
  `IdealGasHelmholtzCP0AlyLee`, added to `IdealHelmholtz` in CoolProp's own conversion (a CP0PolyT constant and two
  generalized Planck-Einstein terms); its parity holds at 1e-10 on every EOS row, h and s included.
- `parity-<Fluid>.json` for the seven, and the supercritical grid as rows of source `supercritical` for the five earlier
  fluids but water (`parity-Water.json` byte-identical, earlier rows unchanged). Every EOS row, density row and
  saturation row but the superancillary Psat holds at 1e-10; the PT-flash and superancillary tolerances were re-set by
  the test's 1-2-5 rule (worst: PT h 1.9e-9 for N2 at 145 K and 30 MPa; PT fugacity coefficient 1.0e-7 for liquid
  toluene at 275 K and 10 kPa; superancillary Psat 5.7e-4 at propane's triple point, 1.7e-4 Pa).
- `viscosity-reference.json` (60 states) and `mixture-density-reference.json` (108 states, 3 two-phase in CoolProp),
  from `tools/coolprop-parity/generate_supercritical.py`.
- Every summary's worst value is held at its first-run value rounded up to the next whole unit
  (`G3F11SupercriticalRegionTest.DECLARED`); the test also asserts finite values, one root above Tc, no `INTERNAL`
  answer, and that its E-PPR78 matches the derivation's 110 E-PPR78 pairs (worst 5e-6, the printed precision).

## Tables

Deviations are model minus reference, in percent unless stated; "worst / p95" is the worst signed value and the 95th
percentile of the absolute values, with the worst's state. Per-state rows are in the test output
(`build/test-results/test/TEST-com.wormzjl.icheme.science.thermo.qualification.G3F11SupercriticalRegionTest.xml`, lines
`G3 F11 M1 <fluid>`, `G3 F11 M5 <fluid>`, `G3 F11 M7`).

### M1 pure fluids against the oracle

| Fluid | Region | n | rho % worst / p95 | cp % worst / p95 | sound % worst / p95 | h K worst / p95 | h J/mol worst / p95 |
|---|---|---|---|---|---|---|---|
| CO2 | outside the band | 78 | -5.57 / +4.68 (349.7 K 14.75 MPa) | -10.56 / +6.34 (349.7 K 14.75 MPa) | -13.41 / +12.79 (310.2 K 20.00 MPa) | -3.84 / +3.69 (456.2 K 11.07 MPa) | -226 / +209 (395.4 K 11.07 MPa) |
| CO2 | inside the band | 21 | -12.99 / +11.19 (310.2 K 8.85 MPa) | -20.31 / +11.36 (319.3 K 10.00 MPa) | +19.16 / +12.57 (319.3 K 10.00 MPa) | +2.69 / +2.63 (310.2 K 10.00 MPa) | +703 / +534 (310.2 K 8.85 MPa) |
| N2 | outside the band | 96 | -3.36 / +2.68 (164.0 K 13.58 MPa) | -6.40 / +4.06 (164.0 K 10.00 MPa) | -10.84 / +8.77 (128.7 K 10.00 MPa) | -1.85 / +1.70 (189.3 K 5.09 MPa) | -76 / +64 (189.3 K 6.79 MPa) |
| N2 | inside the band | 25 | -10.99 / +8.87 (128.7 K 4.07 MPa) | -13.77 / +11.60 (138.8 K 5.09 MPa) | -10.96 / +10.05 (128.7 K 6.79 MPa) | +1.55 / +1.40 (128.7 K 5.09 MPa) | +259 / +193 (128.7 K 4.07 MPa) |
| CH4 | outside the band | 74 | -4.03 / +2.94 (194.4 K 10.00 MPa) | +9.03 / +6.16 (194.4 K 10.00 MPa) | -11.77 / +10.42 (194.4 K 10.00 MPa) | -2.44 / +2.27 (285.8 K 6.90 MPa) | +169 / +110 (194.4 K 10.00 MPa) |
| CH4 | inside the band | 25 | -11.01 / +8.81 (194.4 K 5.52 MPa) | -12.97 / +10.58 (209.6 K 6.90 MPa) | -11.60 / +10.77 (194.4 K 9.20 MPa) | +2.60 / +2.40 (194.4 K 6.90 MPa) | +418 / +318 (194.4 K 5.52 MPa) |
| C2H6 | outside the band | 74 | -5.27 / +4.96 (311.4 K 9.74 MPa) | -11.02 / +6.54 (335.9 K 7.31 MPa) | -13.37 / +12.78 (311.4 K 14.62 MPa) | +2.50 / +2.21 (311.4 K 9.74 MPa) | +287 / +197 (311.4 K 9.74 MPa) |
| C2H6 | inside the band | 14 | -12.12 / +12.12 (311.4 K 5.85 MPa) | -9.54 / +9.54 (351.1 K 9.74 MPa) | -9.97 / +9.97 (311.4 K 7.31 MPa) | +3.20 / +3.20 (311.4 K 7.31 MPa) | +717 / +717 (311.4 K 5.85 MPa) |
| H2 | outside the band | 106 | -8.82 / +8.65 (43.1 K 30.00 MPa) | +28.39 / +20.27 (33.8 K 30.00 MPa) | +30.43 / +21.88 (33.8 K 30.00 MPa) | +4.15 / +3.10 (49.7 K 30.00 MPa) | +97 / +69 (66.3 K 30.00 MPa) |
| H2 | inside the band | 15 | -10.33 / +10.33 (33.8 K 1.56 MPa) | -19.82 / +19.82 (33.8 K 1.30 MPa) | +12.88 / +12.88 (36.5 K 1.94 MPa) | +1.60 / +1.60 (36.5 K 3.89 MPa) | +82 / +82 (33.8 K 1.56 MPa) |
| C3H8 | outside the band | 63 | -5.55 / +4.40 (377.3 K 8.50 MPa) | -8.37 / +5.03 (406.9 K 6.38 MPa) | -14.54 / +13.71 (377.3 K 12.75 MPa) | +2.13 / +1.75 (377.3 K 8.50 MPa) | +335 / +229 (377.3 K 8.50 MPa) |
| C3H8 | inside the band | 14 | -12.72 / +12.72 (377.3 K 5.10 MPa) | -8.01 / +8.01 (388.4 K 5.10 MPa) | -11.58 / +11.58 (388.4 K 10.00 MPa) | +3.02 / +3.02 (377.3 K 6.38 MPa) | +906 / +906 (377.3 K 5.10 MPa) |
| C2H4 | outside the band | 64 | -8.80 / +6.44 (310.6 K 10.00 MPa) | -12.12 / +7.32 (310.6 K 7.56 MPa) | -10.97 / +10.48 (288.0 K 15.13 MPa) | +2.44 / +2.35 (288.0 K 10.08 MPa) | +318 / +172 (310.6 K 10.00 MPa) |
| C2H4 | inside the band | 13 | -13.10 / +13.10 (288.0 K 6.05 MPa) | -10.39 / +10.39 (324.7 K 10.08 MPa) | -10.78 / +10.78 (288.0 K 10.00 MPa) | +3.10 / +3.10 (288.0 K 7.56 MPa) | +638 / +638 (288.0 K 6.05 MPa) |

Recommended range: the highest grid pressure below the first state beyond 7 % density or 12 % cp, outside the band.

| Fluid | Highest P (MPa) with rho <= 7 % and cp <= 12 % outside the band | First state beyond (outside the band) | Same at T >= 273.15 K | First state beyond at T >= 273.15 K |
|---|---|---|---|---|
| CO2 | 30.00 | none to the grid's top | 30.00 | none to the grid's top |
| N2 | 30.00 | none to the grid's top | 30.00 | none to the grid's top |
| CH4 | 30.00 | none to the grid's top | 30.00 | none to the grid's top |
| C2H6 | 30.00 | none to the grid's top | 30.00 | none to the grid's top |
| H2 | 1.56 | 38.1 K 1.94 MPa rho +1.61 % cp -12.06 % | 30.00 | none to the grid's top |
| C3H8 | 30.00 | none to the grid's top | 30.00 | none to the grid's top |
| C2H4 | 6.05 | 310.6 K 7.56 MPa rho -2.79 % cp -12.12 % | 6.05 | 310.6 K 7.56 MPa rho -2.79 % cp -12.12 % |

Water on the wet list (the bare cubic above Tc) against IAPWS-95:

| T K | P MPa | Z ref | rho % | h J/mol | h K | cp % | sound % |
|---|---|---|---|---|---|---|---|
| 650 | 22.1 | 0.393 | -7.05 | +2102 | +3.46 | -25.24 | +13.27 |
| 650 | 25.0 | 0.170 | -29.99 | +3473 | +12.28 | +36.05 | +8.63 |
| 650 | 30.0 | 0.182 | -27.81 | +2605 | +15.85 | +32.09 | +3.24 |
| 700 | 22.1 | 0.677 | +1.04 | +130 | +1.34 | -16.08 | +1.39 |
| 700 | 25.0 | 0.619 | -0.09 | +260 | +2.18 | -16.67 | +3.80 |
| 700 | 30.0 | 0.504 | -4.79 | +681 | +3.65 | -19.42 | +14.17 |
| 800 | 22.1 | 0.838 | +1.13 | -548 | -9.45 | -3.99 | +0.19 |
| 800 | 25.0 | 0.815 | +0.82 | -578 | -9.35 | -4.54 | +0.94 |
| 800 | 30.0 | 0.774 | -0.09 | -594 | -8.58 | -5.68 | +2.83 |
| 900 | 22.1 | 0.906 | +0.34 | -632 | -12.61 | +0.19 | +0.49 |
| 900 | 25.0 | 0.893 | +0.12 | -684 | -13.22 | +0.08 | +0.95 |
| 900 | 30.0 | 0.872 | -0.43 | -753 | -13.75 | -0.24 | +1.97 |

### M2 the cp step at Tc (Mathias-Copeman)

| Compound | c1, c2, c3 | Pr | cp(Tc-1e-3) J/(mol K) | cp(Tc+1e-3) | raw step | step extrapolated to Tc | step % | algebra T da''/(2 sqrt2 b) ln(..) | a_c c2/Tc^2 Pa m6/(mol2 K2) |
|---|---|---|---|---|---|---|---|---|---|
| Water | 0.91976, -0.33844, 0.33431 | 0.5 | 53.089 | 53.869 | +0.780 | +0.780 | +1.47 | +0.780 | -4.849e-07 |
| Water | 0.91976, -0.33844, 0.33431 | 1 | 27367.662 | 22765.938 | -4601.725 | +405.991 | +1.48 | +3.303 | -4.849e-07 |
| Water | 0.91976, -0.33844, 0.33431 | 2 | 134.130 | 140.076 | +5.946 | +5.944 | +4.43 | +5.944 | -4.849e-07 |

At Pr 1 the state is the cubic's own critical point, where cp diverges: the extrapolated column reads the divergence;
the step held there is the algebra's.

### M3 the gap of a merged split

| Binary (light/heavy) | z light | T K | Outcome | P MPa | tie line | rho light / heavy mol/m3 | h light / heavy J/mol | light fraction | one fluid rho % | one fluid h J/mol |
|---|---|---|---|---|---|---|---|---|---|---|
| Methane/Ethane | 0.25 | 219.25 | split ends (SINGLE_FLUID) at 2.217 MPa, last tie line 1.2747 | - | - | - | - | - | - | - |
| Methane/Ethane | 0.25 | 247.94 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| Methane/Ethane | 0.25 | 276.63 | tie line below 0.1 | 5.333 | 0.0968 | 4414 / 10692 | -4684 / -9271 | 0.102 | +10.35 | -167 |
| Methane/Ethane | 0.50 | 219.25 | split ends (SINGLE_FLUID) at 4.007 MPa, last tie line 0.8738 | - | - | - | - | - | - | - |
| Methane/Ethane | 0.50 | 247.94 | split ends (SINGLE_FLUID) at 6.140 MPa, last tie line 0.1683 | - | - | - | - | - | - | - |
| Methane/Ethane | 0.50 | 276.63 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| Methane/Ethane | 0.75 | 219.25 | split ends (SINGLE_FLUID) at 5.808 MPa, last tie line 0.1325 | - | - | - | - | - | - | - |
| Methane/Ethane | 0.75 | 247.94 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| Methane/Ethane | 0.75 | 276.63 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| Methane/CarbonDioxide | 0.25 | 218.98 | split ends (SINGLE_FLUID) at 4.278 MPa, last tie line 1.7129 | - | - | - | - | - | - | - |
| Methane/CarbonDioxide | 0.25 | 247.39 | split ends (SINGLE_FLUID) at 6.201 MPa, last tie line 0.5306 | - | - | - | - | - | - | - |
| Methane/CarbonDioxide | 0.25 | 275.80 | tie line below 0.1 | 7.808 | 0.0927 | 7189 / 15103 | -5326 / -9538 | 0.298 | +14.17 | -157 |
| Methane/CarbonDioxide | 0.50 | 218.98 | split ends (SINGLE_FLUID) at 5.751 MPa, last tie line 0.6601 | - | - | - | - | - | - | - |
| Methane/CarbonDioxide | 0.50 | 247.39 | tie line below 0.1 | 8.091 | 0.0796 | 9589 / 15406 | -6615 / -9337 | 0.275 | +5.96 | -67 |
| Methane/CarbonDioxide | 0.50 | 275.80 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| Methane/CarbonDioxide | 0.75 | 218.98 | tie line below 0.1 | 6.326 | 0.0716 | 8642 / 15110 | -6776 / -9286 | 0.515 | +12.52 | -187 |
| Methane/CarbonDioxide | 0.75 | 247.39 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| Methane/CarbonDioxide | 0.75 | 275.80 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| Nitrogen/Methane | 0.25 | 142.29 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| Nitrogen/Methane | 0.25 | 158.38 | split ends (SINGLE_FLUID) at 3.397 MPa, last tie line 0.2353 | - | - | - | - | - | - | - |
| Nitrogen/Methane | 0.25 | 174.47 | tie line below 0.1 | 4.477 | 0.0559 | 6433 / 14184 | -6637 / -8996 | 0.610 | -2.70 | +353 |
| Nitrogen/Methane | 0.50 | 142.29 | split ends (SINGLE_FLUID) at 3.246 MPa, last tie line 0.3435 | - | - | - | - | - | - | - |
| Nitrogen/Methane | 0.50 | 158.38 | tie line below 0.1 | 4.451 | 0.0952 | 6927 / 15451 | -6723 / -9123 | 0.533 | +16.96 | -66 |
| Nitrogen/Methane | 0.50 | 174.47 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| Nitrogen/Methane | 0.75 | 142.29 | tie line below 0.1 | 3.850 | 0.0983 | 6545 / 16420 | -6715 / -9172 | 0.738 | -1.00 | +270 |
| Nitrogen/Methane | 0.75 | 158.38 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| Nitrogen/Methane | 0.75 | 174.47 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| CarbonDioxide/Propane | 0.25 | 320.62 | split ends (SINGLE_FLUID) at 3.606 MPa, last tie line 0.2535 | - | - | - | - | - | - | - |
| CarbonDioxide/Propane | 0.25 | 337.02 | split ends (SINGLE_FLUID) at 4.567 MPa, last tie line 0.1132 | - | - | - | - | - | - | - |
| CarbonDioxide/Propane | 0.25 | 353.42 | tie line below 0.1 | 4.925 | 0.0292 | 3722 / 6654 | -2010 / -5598 | 0.920 | -0.97 | +146 |
| CarbonDioxide/Propane | 0.50 | 320.62 | split ends (SINGLE_FLUID) at 5.472 MPa, last tie line 0.1125 | - | - | - | - | - | - | - |
| CarbonDioxide/Propane | 0.50 | 337.02 | tie line below 0.1 | 5.959 | 0.0225 | 4890 / 7386 | -3727 / -6308 | 0.950 | -0.05 | +36 |
| CarbonDioxide/Propane | 0.50 | 353.42 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| CarbonDioxide/Propane | 0.75 | 320.62 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| CarbonDioxide/Propane | 0.75 | 337.02 | no split 0.5-40 MPa | - | - | - | - | - | - | - |
| CarbonDioxide/Propane | 0.75 | 353.42 | no split 0.5-40 MPa | - | - | - | - | - | - | - |

### M4 viscosity

| Fluid | Tr | P MPa | rho_ref / rho_c,ref | delta (model rho, cubic v_c) | mu_ref uPa s | dilute model / ref | model dilute / CoolProp dilute | JST (ref rho_c) / ref | JST (cubic v_c) / ref |
|---|---|---|---|---|---|---|---|---|---|
| CarbonDioxide | 1.05 | 5 | 0.230 | 0.263 | 17.24 | 0.929 | 1.006 | 1.054 | 1.074 |
| CarbonDioxide | 1.05 | 10 | 0.995 | 1.029 | 32.96 | 0.486 | 1.006 | 1.083 | 1.120 |
| CarbonDioxide | 1.05 | 20 | 1.724 | 1.839 | 73.13 | 0.219 | 1.006 | 0.956 | 1.067 |
| CarbonDioxide | 1.05 | 30 | 1.894 | 2.086 | 89.35 | 0.179 | 1.006 | 0.924 | 1.148 |
| CarbonDioxide | 1.20 | 5 | 0.179 | 0.202 | 19.11 | 0.949 | 1.005 | 1.034 | 1.046 |
| CarbonDioxide | 1.20 | 10 | 0.428 | 0.482 | 21.86 | 0.830 | 1.005 | 1.051 | 1.095 |
| CarbonDioxide | 1.20 | 20 | 1.119 | 1.173 | 39.72 | 0.456 | 1.005 | 1.066 | 1.120 |
| CarbonDioxide | 1.20 | 30 | 1.488 | 1.591 | 57.98 | 0.313 | 1.005 | 1.011 | 1.104 |
| CarbonDioxide | 1.50 | 5 | 0.131 | 0.147 | 22.93 | 0.963 | 1.000 | 1.016 | 1.022 |
| CarbonDioxide | 1.50 | 10 | 0.276 | 0.309 | 24.24 | 0.910 | 1.000 | 1.020 | 1.037 |
| CarbonDioxide | 1.50 | 20 | 0.593 | 0.650 | 28.89 | 0.764 | 1.000 | 1.043 | 1.089 |
| CarbonDioxide | 1.50 | 30 | 0.895 | 0.966 | 36.17 | 0.610 | 1.000 | 1.060 | 1.126 |
| Nitrogen | 1.05 | 5 | 1.296 | 1.253 | 25.49 | 0.352 | 1.000 | 1.032 | 0.993 |
| Nitrogen | 1.05 | 10 | 1.771 | 1.824 | 43.70 | 0.206 | 1.000 | 0.924 | 0.972 |
| Nitrogen | 1.05 | 20 | 2.032 | 2.161 | 59.40 | 0.151 | 1.000 | 0.892 | 1.050 |
| Nitrogen | 1.05 | 30 | 2.174 | 2.334 | 71.01 | 0.127 | 1.000 | 0.894 | 1.136 |
| Nitrogen | 1.20 | 5 | 0.522 | 0.563 | 13.68 | 0.741 | 1.000 | 1.004 | 1.038 |
| Nitrogen | 1.20 | 10 | 1.301 | 1.317 | 26.77 | 0.378 | 1.000 | 1.031 | 1.045 |
| Nitrogen | 1.20 | 20 | 1.773 | 1.866 | 44.51 | 0.228 | 1.000 | 0.935 | 1.022 |
| Nitrogen | 1.20 | 30 | 1.972 | 2.110 | 55.62 | 0.182 | 1.000 | 0.912 | 1.069 |
| Nitrogen | 1.50 | 5 | 0.324 | 0.348 | 14.18 | 0.867 | 1.000 | 0.994 | 1.007 |
| Nitrogen | 1.50 | 10 | 0.710 | 0.748 | 17.95 | 0.685 | 1.000 | 1.019 | 1.050 |
| Nitrogen | 1.50 | 20 | 1.295 | 1.350 | 28.69 | 0.429 | 1.000 | 1.032 | 1.080 |
| Nitrogen | 1.50 | 30 | 1.594 | 1.690 | 38.35 | 0.321 | 1.000 | 0.982 | 1.063 |
| Methane | 1.05 | 5 | 0.538 | 0.594 | 10.91 | 0.730 | 1.014 | 1.030 | 1.082 |
| Methane | 1.05 | 10 | 1.635 | 1.666 | 29.29 | 0.272 | 1.014 | 1.058 | 1.089 |
| Methane | 1.05 | 20 | 1.929 | 2.060 | 40.76 | 0.195 | 1.014 | 1.008 | 1.168 |
| Methane | 1.05 | 30 | 2.074 | 2.249 | 48.41 | 0.165 | 1.014 | 1.001 | 1.267 |
| Methane | 1.20 | 5 | 0.338 | 0.372 | 10.39 | 0.858 | 1.005 | 1.017 | 1.039 |
| Methane | 1.20 | 10 | 0.960 | 1.000 | 16.48 | 0.541 | 1.005 | 1.074 | 1.113 |
| Methane | 1.20 | 20 | 1.612 | 1.690 | 29.69 | 0.300 | 1.005 | 1.056 | 1.129 |
| Methane | 1.20 | 30 | 1.837 | 1.971 | 37.75 | 0.236 | 1.005 | 1.017 | 1.165 |
| Methane | 1.50 | 5 | 0.230 | 0.250 | 11.78 | 0.913 | 0.997 | 1.000 | 1.008 |
| Methane | 1.50 | 10 | 0.505 | 0.546 | 13.67 | 0.786 | 0.997 | 1.003 | 1.032 |
| Methane | 1.50 | 20 | 1.048 | 1.103 | 19.77 | 0.544 | 0.997 | 1.063 | 1.113 |
| Methane | 1.50 | 30 | 1.389 | 1.472 | 26.16 | 0.411 | 0.997 | 1.061 | 1.133 |
| Ethane | 1.05 | 5 | 0.459 | 0.518 | 13.52 | 0.744 | 1.008 | 0.993 | 1.044 |
| Ethane | 1.05 | 10 | 1.586 | 1.631 | 40.00 | 0.252 | 1.008 | 0.965 | 1.005 |
| Ethane | 1.05 | 20 | 1.895 | 2.065 | 56.39 | 0.178 | 1.008 | 0.917 | 1.110 |
| Ethane | 1.05 | 30 | 2.039 | 2.269 | 67.30 | 0.149 | 1.008 | 0.901 | 1.227 |
| Ethane | 1.20 | 5 | 0.304 | 0.340 | 13.34 | 0.852 | 1.008 | 0.993 | 1.016 |
| Ethane | 1.20 | 10 | 0.834 | 0.894 | 20.46 | 0.556 | 1.008 | 0.996 | 1.054 |
| Ethane | 1.20 | 20 | 1.532 | 1.631 | 39.24 | 0.290 | 1.008 | 0.971 | 1.059 |
| Ethane | 1.20 | 30 | 1.771 | 1.943 | 50.47 | 0.225 | 1.008 | 0.933 | 1.105 |
| Ethane | 1.50 | 5 | 0.209 | 0.232 | 15.09 | 0.918 | 1.010 | 0.998 | 1.008 |
| Ethane | 1.50 | 10 | 0.453 | 0.500 | 17.54 | 0.790 | 1.010 | 0.978 | 1.008 |
| Ethane | 1.50 | 20 | 0.946 | 1.017 | 25.37 | 0.546 | 1.010 | 0.990 | 1.051 |
| Ethane | 1.50 | 30 | 1.284 | 1.391 | 33.71 | 0.411 | 1.010 | 0.987 | 1.076 |
| n-Propane | 1.05 | 5 | 0.627 | 0.707 | 16.29 | 0.644 | 0.998 | 1.008 | 1.089 |
| n-Propane | 1.05 | 10 | 1.652 | 1.741 | 47.55 | 0.221 | 0.998 | 0.917 | 0.996 |
| n-Propane | 1.05 | 20 | 1.935 | 2.158 | 66.91 | 0.157 | 0.998 | 0.860 | 1.124 |
| n-Propane | 1.05 | 30 | 2.075 | 2.363 | 80.05 | 0.131 | 0.998 | 0.844 | 1.283 |
| n-Propane | 1.20 | 5 | 0.357 | 0.405 | 14.43 | 0.820 | 0.994 | 0.994 | 1.027 |
| n-Propane | 1.20 | 10 | 0.975 | 1.038 | 24.41 | 0.485 | 0.994 | 1.006 | 1.067 |
| n-Propane | 1.20 | 20 | 1.595 | 1.739 | 45.80 | 0.258 | 0.994 | 0.933 | 1.062 |
| n-Propane | 1.20 | 30 | 1.818 | 2.045 | 59.06 | 0.200 | 0.994 | 0.886 | 1.126 |
| n-Propane | 1.50 | 5 | 0.239 | 0.268 | 16.22 | 0.892 | 0.994 | 0.984 | 0.998 |
| n-Propane | 1.50 | 10 | 0.516 | 0.572 | 19.38 | 0.746 | 0.994 | 0.968 | 1.008 |
| n-Propane | 1.50 | 20 | 1.033 | 1.124 | 28.12 | 0.514 | 0.994 | 1.015 | 1.097 |
| n-Propane | 1.50 | 30 | 1.356 | 1.501 | 36.99 | 0.391 | 0.994 | 1.010 | 1.136 |

### M5 surrogates of the crude cuts

| Reference | Cut | omega | Tc K | Pc MPa | translation cm3/mol | n | rho % worst / p95 | residual cp (% of cp) worst / p95 | h K worst / p95 | Highest P MPa (7 % / 12 %) |
|---|---|---|---|---|---|---|---|---|---|---|
| n-Hexane | pc01 | 0.3003 | 507.82 | 3.044 | 1.85 | 25 | -5.74 / +4.94 (584.0 K 10.00 MPa) | +2.16 / +1.99 (518.0 K 9.13 MPa) | +1.18 / +1.12 (584.0 K 10.00 MPa) | 30.00 |
| n-Heptane | pc02 | 0.3490 | 540.13 | 2.736 | -2.12 | 16 | -5.17 / +5.17 (594.1 K 10.00 MPa) | +2.42 / +2.42 (550.9 K 8.21 MPa) | -1.74 / +1.74 (550.9 K 30.00 MPa) | 30.00 |
| Toluene | pc02 | 0.2657 | 591.75 | 4.126 | -0.14 | 22 | -5.32 / +5.10 (603.6 K 10.00 MPa) | +4.41 / +3.16 (603.6 K 10.00 MPa) | -2.59 / +2.46 (603.6 K 30.00 MPa) | 30.00 |
| n-Decane | pc03 | 0.4884 | 617.70 | 2.103 | -16.81 | 11 | +7.11 / +7.11 (630.1 K 30.00 MPa) | +2.95 / +2.95 (630.1 K 6.31 MPa) | -1.70 / +1.70 (648.6 K 30.00 MPa) | 20.00 |
| n-Dodecane | pc04 | 0.5742 | 658.10 | 1.817 | -29.16 | 11 | +9.54 / +9.54 (691.0 K 30.00 MPa) | +1.92 / +1.92 (671.3 K 5.45 MPa) | -2.37 / +2.37 (691.0 K 30.00 MPa) | 10.00 |

The bundled datasets on the same grid (above the model's Tc, outside the band):

| Dataset | n | rho % worst / p95 | cp % worst / p95 | h K worst / p95 | Highest P MPa (7 % / 12 %) | First state beyond |
|---|---|---|---|---|---|---|
| N-hexane | 33 | -7.27 / +6.36 (518.0 K 6.09 MPa) | -5.31 / +2.83 (558.6 K 4.57 MPa) | +2.00 / +1.38 (518.0 K 6.09 MPa) | 4.57 | 518.0 K 6.09 MPa rho -7.27 % |
| Toluene | 26 | -9.02 / +8.09 (680.5 K 12.38 MPa) | +5.56 / +4.03 (603.6 K 8.25 MPa) | -2.34 / +2.23 (603.6 K 30.00 MPa) | 6.19 | 603.6 K 8.25 MPa rho -8.09 % |

### M6 gas-cut pairs (a sensitivity, not data)

Today's pairs:

| Gas | Source of its pairs with pc01..pc12 | k_ij pc01 / pc06 / pc12 |
|---|---|---|
| CarbonDioxide | {EPPR78=12} | k_ij 0.11787 (E-PPR78 at 298.15 K) / k_ij 0.10237 (E-PPR78 at 298.15 K) / k_ij 0.11428 (E-PPR78 at 298.15 K) |
| Nitrogen | {EPPR78=12} | k_ij 0.00739 (E-PPR78 at 298.15 K) / k_ij 0.13754 (E-PPR78 at 298.15 K) / k_ij 0.14913 (E-PPR78 at 298.15 K) |
| HydrogenSulfide | {EPPR78=12} | k_ij 0.06089 (E-PPR78 at 298.15 K) / k_ij 0.00988 (E-PPR78 at 298.15 K) / k_ij 0.02374 (E-PPR78 at 298.15 K) |
| Hydrogen | {HYDROGEN=12} | k_ij 0 / k_ij 0 / k_ij 0 |
| Methane | {EPPR78=12} | k_ij 0.02046 (E-PPR78 at 298.15 K) / k_ij 0.05397 (E-PPR78 at 298.15 K) / k_ij 0.06444 (E-PPR78 at 298.15 K) |

Medium crude with an equal amount of one gas, TP; every gas-cut pair at the value named:

| Gas | T K | P MPa | x_gas in liquid: today / 0 / 0.05 / 0.10 / 0.15 | y_oil (cuts) in gas: today / 0 / 0.05 / 0.10 / 0.15 |
|---|---|---|---|---|
| CarbonDioxide | 320 | 0.5 | 0.0507 / 0.0778 / 0.0630 / 0.0510 / 0.0412 | 1.32e-02 / 1.31e-02 / 1.32e-02 / 1.32e-02 / 1.32e-02 |
| CarbonDioxide | 320 | 2 | 0.1956 / 0.2934 / 0.2413 / 0.1969 / 0.1597 | 4.38e-03 / 4.13e-03 / 4.30e-03 / 4.40e-03 / 4.45e-03 |
| CarbonDioxide | 320 | 10 | 1 phase / 1 phase / 1 phase / 1 phase / 1 phase | 1 phase / 1 phase / 1 phase / 1 phase / 1 phase |
| CarbonDioxide | 450 | 0.5 | 0.0182 / 0.0228 / 0.0204 / 0.0182 / 0.0163 | 1.62e-01 / 1.62e-01 / 1.62e-01 / 1.62e-01 / 1.62e-01 |
| CarbonDioxide | 450 | 2 | 0.0787 / 0.0982 / 0.0880 / 0.0789 / 0.0706 | 7.85e-02 / 7.93e-02 / 7.90e-02 / 7.87e-02 / 7.83e-02 |
| CarbonDioxide | 450 | 10 | 0.3537 / 0.4322 / 0.3925 / 0.3545 / 0.3189 | 3.94e-02 / 4.18e-02 / 4.10e-02 / 4.00e-02 / 3.87e-02 |
| CarbonDioxide | 600 | 0.5 | 0.0112 / 0.0125 / 0.0118 / 0.0111 / 0.0105 | 3.63e-01 / 3.63e-01 / 3.63e-01 / 3.63e-01 / 3.63e-01 |
| CarbonDioxide | 600 | 2 | 0.0522 / 0.0583 / 0.0551 / 0.0521 / 0.0493 | 2.76e-01 / 2.77e-01 / 2.76e-01 / 2.76e-01 / 2.76e-01 |
| CarbonDioxide | 600 | 10 | 0.2817 / 0.3124 / 0.2967 / 0.2816 / 0.2670 | 1.68e-01 / 1.75e-01 / 1.72e-01 / 1.69e-01 / 1.66e-01 |
| Nitrogen | 320 | 0.5 | 0.0068 / 0.0089 / 0.0080 / 0.0073 / 0.0066 | 1.31e-02 / 1.31e-02 / 1.31e-02 / 1.30e-02 / 1.30e-02 |
| Nitrogen | 320 | 2 | 0.0274 / 0.0357 / 0.0324 / 0.0293 / 0.0265 | 4.21e-03 / 4.22e-03 / 4.17e-03 / 4.12e-03 / 4.07e-03 |
| Nitrogen | 320 | 10 | 0.1224 / 0.1598 / 0.1447 / 0.1310 / 0.1186 | 2.18e-03 / 2.20e-03 / 2.08e-03 / 1.96e-03 / 1.85e-03 |
| Nitrogen | 450 | 0.5 | 0.0061 / 0.0070 / 0.0067 / 0.0064 / 0.0060 | 1.60e-01 / 1.61e-01 / 1.60e-01 / 1.60e-01 / 1.60e-01 |
| Nitrogen | 450 | 2 | 0.0271 / 0.0311 / 0.0296 / 0.0282 / 0.0268 | 7.51e-02 / 7.53e-02 / 7.51e-02 / 7.48e-02 / 7.45e-02 |
| Nitrogen | 450 | 10 | 0.1334 / 0.1529 / 0.1455 / 0.1384 / 0.1317 | 2.90e-02 / 2.93e-02 / 2.87e-02 / 2.81e-02 / 2.75e-02 |
| Nitrogen | 600 | 0.5 | 0.0060 / 0.0066 / 0.0064 / 0.0063 / 0.0061 | 3.62e-01 / 3.62e-01 / 3.62e-01 / 3.62e-01 / 3.62e-01 |
| Nitrogen | 600 | 2 | 0.0292 / 0.0315 / 0.0308 / 0.0300 / 0.0293 | 2.71e-01 / 2.72e-01 / 2.72e-01 / 2.71e-01 / 2.71e-01 |
| Nitrogen | 600 | 10 | 0.1655 / 0.1773 / 0.1731 / 0.1691 / 0.1651 | 1.43e-01 / 1.45e-01 / 1.44e-01 / 1.42e-01 / 1.41e-01 |
| HydrogenSulfide | 320 | 0.5 | 0.1587 / 0.1659 / 0.1303 / 0.1017 / 0.0792 | 1.23e-02 / 1.22e-02 / 1.25e-02 / 1.28e-02 / 1.29e-02 |
| HydrogenSulfide | 320 | 2 | 1 phase / 1 phase / 0.4974 / 0.4014 / 0.3135 | 1 phase / 1 phase / 3.12e-03 / 3.60e-03 / 3.96e-03 |
| HydrogenSulfide | 320 | 10 | 1 phase / 1 phase / 1 phase / 1 phase / 1 phase | 1 phase / 1 phase / 1 phase / 1 phase / 1 phase |
| HydrogenSulfide | 450 | 0.5 | 0.0389 / 0.0395 / 0.0343 / 0.0299 / 0.0260 | 1.63e-01 / 1.63e-01 / 1.62e-01 / 1.62e-01 / 1.62e-01 |
| HydrogenSulfide | 450 | 2 | 0.1634 / 0.1668 / 0.1462 / 0.1278 / 0.1116 | 7.86e-02 / 7.88e-02 / 7.89e-02 / 7.88e-02 / 7.87e-02 |
| HydrogenSulfide | 450 | 10 | 1 phase / 1 phase / 1 phase / 1 phase / 0.4804 | 1 phase / 1 phase / 1 phase / 1 phase / 4.40e-02 |
| HydrogenSulfide | 600 | 0.5 | 0.0188 / 0.0186 / 0.0172 / 0.0159 / 0.0147 | 3.63e-01 / 3.63e-01 / 3.63e-01 / 3.63e-01 / 3.63e-01 |
| HydrogenSulfide | 600 | 2 | 0.0850 / 0.0850 / 0.0789 / 0.0732 / 0.0679 | 2.79e-01 / 2.79e-01 / 2.79e-01 / 2.78e-01 / 2.78e-01 |
| HydrogenSulfide | 600 | 10 | 0.4275 / 0.4304 / 0.4048 / 0.3795 / 0.3548 | 1.88e-01 / 1.91e-01 / 1.87e-01 / 1.84e-01 / 1.80e-01 |
| Hydrogen | 320 | 0.5 | 0.0052 / 0.0052 / 0.0050 / 0.0047 / 0.0045 | 1.28e-02 / 1.28e-02 / 1.28e-02 / 1.28e-02 / 1.27e-02 |
| Hydrogen | 320 | 2 | 0.0213 / 0.0213 / 0.0202 / 0.0191 / 0.0182 | 3.80e-03 / 3.80e-03 / 3.78e-03 / 3.75e-03 / 3.73e-03 |
| Hydrogen | 320 | 10 | 0.0996 / 0.0996 / 0.0944 / 0.0896 / 0.0849 | 1.34e-03 / 1.34e-03 / 1.29e-03 / 1.25e-03 / 1.21e-03 |
| Hydrogen | 450 | 0.5 | 0.0060 / 0.0060 / 0.0058 / 0.0056 / 0.0054 | 1.60e-01 / 1.60e-01 / 1.60e-01 / 1.60e-01 / 1.60e-01 |
| Hydrogen | 450 | 2 | 0.0266 / 0.0266 / 0.0258 / 0.0250 / 0.0242 | 7.34e-02 / 7.34e-02 / 7.32e-02 / 7.31e-02 / 7.29e-02 |
| Hydrogen | 450 | 10 | 0.1324 / 0.1324 / 0.1282 / 0.1243 / 0.1204 | 2.51e-02 / 2.51e-02 / 2.47e-02 / 2.44e-02 / 2.40e-02 |
| Hydrogen | 600 | 0.5 | 0.0066 / 0.0066 / 0.0064 / 0.0063 / 0.0062 | 3.62e-01 / 3.62e-01 / 3.62e-01 / 3.62e-01 / 3.62e-01 |
| Hydrogen | 600 | 2 | 0.0315 / 0.0315 / 0.0309 / 0.0303 / 0.0298 | 2.71e-01 / 2.71e-01 / 2.71e-01 / 2.70e-01 / 2.70e-01 |
| Hydrogen | 600 | 10 | 0.1774 / 0.1774 / 0.1742 / 0.1710 / 0.1679 | 1.39e-01 / 1.39e-01 / 1.38e-01 / 1.38e-01 / 1.37e-01 |
| Methane | 320 | 0.5 | 0.0194 / 0.0228 / 0.0196 / 0.0168 / 0.0144 | 1.33e-02 / 1.33e-02 / 1.33e-02 / 1.33e-02 / 1.32e-02 |
| Methane | 320 | 2 | 0.0768 / 0.0898 / 0.0773 / 0.0664 / 0.0571 | 4.56e-03 / 4.56e-03 / 4.52e-03 / 4.46e-03 / 4.41e-03 |
| Methane | 320 | 10 | 0.3116 / 0.3623 / 0.3137 / 0.2703 / 0.2322 | 3.69e-03 / 3.72e-03 / 3.51e-03 / 3.28e-03 / 3.03e-03 |
| Methane | 450 | 0.5 | 0.0116 / 0.0127 / 0.0117 / 0.0107 / 0.0099 | 1.61e-01 / 1.62e-01 / 1.61e-01 / 1.61e-01 / 1.61e-01 |
| Methane | 450 | 2 | 0.0510 / 0.0556 / 0.0512 / 0.0472 / 0.0435 | 7.78e-02 / 7.80e-02 / 7.77e-02 / 7.73e-02 / 7.69e-02 |
| Methane | 450 | 10 | 0.2413 / 0.2624 / 0.2424 / 0.2236 / 0.2061 | 3.63e-02 / 3.68e-02 / 3.57e-02 / 3.47e-02 / 3.36e-02 |
| Methane | 600 | 0.5 | 0.0091 / 0.0096 / 0.0092 / 0.0088 / 0.0084 | 3.62e-01 / 3.62e-01 / 3.62e-01 / 3.62e-01 / 3.62e-01 |
| Methane | 600 | 2 | 0.0432 / 0.0454 / 0.0434 / 0.0416 / 0.0398 | 2.75e-01 / 2.75e-01 / 2.75e-01 / 2.74e-01 / 2.74e-01 |
| Methane | 600 | 10 | 0.2383 / 0.2494 / 0.2392 / 0.2294 / 0.2199 | 1.62e-01 / 1.63e-01 / 1.61e-01 / 1.59e-01 / 1.56e-01 |

Decision O9: pairs at zero today whose compounds both have E-PPR78 counts.

| Pair | E-PPR78 k_ij 300 K | E-PPR78 k_ij 500 K |
|---|---|---|
| Ethylene / Isobutene | -0.2093 | -368.4592 |
| Propane / SulfurDioxide | +0.1435 | +0.3279 |
| Isobutane / SulfurDioxide | +0.0991 | +0.0530 |
| SulfurDioxide / Cis-2-butene | +0.0978 | +0.2044 |
| N-butane / SulfurDioxide | +0.0963 | +0.2653 |
| SulfurDioxide / Trans-2-butene | +0.0952 | +0.2030 |
| Propane / EthylMercaptan | +0.0764 | +0.0408 |
| Isopentane / SulfurDioxide | +0.0712 | +0.0592 |
| O-xylene / EthylMercaptan | +0.0668 | +0.2565 |
| M-xylene / EthylMercaptan | +0.0657 | +0.2611 |

| Pair | E-PPR78 k_ij 300 K | E-PPR78 k_ij 500 K |
|---|---|---|
| Hydrogen / Isobutene | +0.4708 | +0.5331 |
| Hydrogen / P-xylene | +0.3860 | +0.4985 |
| Hydrogen / M-xylene | +0.3763 | +0.4908 |
| Hydrogen / HydrogenSulfide | -0.3571 | -0.4701 |
| Hydrogen / Ethylbenzene | +0.3477 | +0.5631 |
| Hydrogen / O-xylene | +0.3350 | +0.4451 |
| Hydrogen / Toluene | +0.3250 | +0.5569 |
| Methane / Hydrogen | +0.3234 | +0.6506 |
| Hydrogen / Ammonia | +0.2835 | +0.5679 |
| Isobutane / Hydrogen | +0.2799 | +0.4302 |

### M7 mixtures against CoolProp

105 single-phase reference states, all one phase in the engine: density worst -4.95 % (methane/ethane z 0.25, 300 K,
10 MPa), p95 2.79 %. The three states CoolProp finds two-phase (methane/CO2 z 0.25 and 0.5, methane/ethane z 0.5, all at
250 K and 5 MPa) are not scored.
