# NIST re-derivation review (O1, O2)

Batch `2026-11-04-open-thermo-data`, owner decision D-B option 2 of [DATA_LICENSE_REVIEW.md](../2026-11-04-refinery-compounds/DATA_LICENSE_REVIEW.md): every value taken from NIST Standard Reference Data (WebBook, JANAF) is re-derived from open implementations of the same correlations. Commits `5efcb7e39` (O1), `8fcf698d6` (O2), `2f052f28f` (chain-100). Tool: [`tools/open-thermo-data/`](../../tools/open-thermo-data/README.md).

## 1. Expected behaviour

The WebBook values for nitrogen, methane and water are themselves evaluations of the reference equations of state (Span et al. 2000, Setzmann and Wagner 1991, IAPWS-95) and of the IAPWS and Lemmon-Jacobsen transport correlations. CoolProp 8.0.0 implements the same equations. A fit to CoolProp should therefore land within the fit's own error of the old one: changes of order 0.01 % in Cp, 1e-7 in volumes, and no change in any hold or product. The one exception expected in advance is methane: the old tjl20 methane Cp came from the NIST Shomate (JANAF-based), not from the Setzmann-Wagner ideal-gas part, so it can move by the spread between those sources (1 to 2 % at high temperature).

## 2. What moved

| Quantity | Max change | rms | At 300 / 500 / 800 K (old -> new) |
|---|---|---|---|
| N2 ideal-gas Cp, 273..900 K | +0.020 % (518 K) | 0.012 % | 29.1255 / 29.5749 -> 29.5807 / 31.4389 -> 31.4341 |
| N2 held Cp below 273.16 K | 29.1112 -> 29.1163 (+0.018 %) | | worst 0.047 % against Span et al. down to 63.15 K |
| CH4 (tjl20) Cp, 293..900 K | +1.17 % (900 K) | 0.64 % | 35.675 -> 35.769 / 46.349 -> 46.504 / 62.929 -> 63.511 |
| H2O(g) Cp, 273..900 K | -0.038 % (843 K) | 0.022 % | 33.5974 -> 33.5912 / 35.2184 -> 35.2266 / 38.7365 -> 38.7228 |

- Water: h(298.15 K) offset 0.53 -> 0 J/mol (the Shomate F now uses ATcT -241.808 kJ/mol); worst enthalpy change 1.9 J/mol, entropy 0.002 J/(mol K). IAPWS-95 against CEA: 0.028 %.
- Methane sources: Setzmann-Wagner alpha0 agrees with the bundled spine to 0.015 %; CEA sits 1.0 % above it at 900 K; the old JANAF-based fit sat 2.2 % below CEA. Chosen: Setzmann-Wagner (same equation as the spine and the column's other properties). Fit errors: N2 0.011 %, CH4 0.025 %, H2O 0.054 %.
- N2 viscosity: the pilot record's CoolProp tables replace the NIST tables (old nodes agree 0.036 % liquid, 0.22 % vapour). The liquid table moves at most 0.34 % below 124 K and up to 10 % in the last kelvin below Tc (126.2 K), where the old table interpolated linearly in ln(mu) across the critical drop.
- Liquid calibration volumes (8 points): relative changes at most 1.3e-7 (N2 -1.3e-7, propane 1.6e-8, isobutane 1.1e-8, the rest below 1e-12); volume-translation shifts move at most 4.8e-12 m3/mol.
- Test references: water-vapour volumes and viscosities unchanged, compressibilities 0.021 % (the WebBook column was a pressure difference, the oracle is exact); N2 Psat(77.355 K) and saturated liquid density move 1.3e-7; nitrogen-cp-checks within 0.032 %.
- Column draw-volume targets (RegroupedCrudeTest): 15 targets moved 5e-8 to 1.2e-5 relative; re-captured.
- Compat corpus images that book an energy difference on load (each equal to n * dh_ig computed apart by `compat-energy.mjs`): `schema-1` -10.27 J, `island-sets` +616.61 J, `compound-basis` +437.65 J, `steam-v2` +126.97 J (on top of its steam residual). Amounts and temperatures are kept; the ledger books the difference.
- chain-100, exact replay before re-capture: 40 differences; max moles 8.5e-9 relative, temperature 8.6e-8 K, phase fraction 3.8e-10, flow 3.2e-6 relative (2.7e-8 kg/s, under the controller's 3.2e-8 kg/s allowance). All inside the declared gate. Re-captured (owner approved).
- 200-seed hold quick check against the refinery batch's final lane (`tmp/merge-lane/head`, seeds 1-200): cold random 197 clean (E1 85, E6 101, F 118 — the same three seeds and classes as the baseline), bundled random 200/200, bundled defaults 200/200, refused 0 everywhere. Seed lines differ only in replay flows of quiet islands (e.g. 3.9e-13 -> 4.9e-13 kg/s) and next-check ticks; seed 121 gains a non-blocking "Methane ideal-gas heat capacity extrapolated" notice (its trajectory now crosses the unchanged range edge).
- Default column preset (tia_juana), counters from the probe's control polls (the published diagnostics count only the final pass, 0 here): before 239,869 (learned start) / 236,993 (classical); after 219,620 / 219,616 (-7.3 %). Before O2 the learned start already fell back to the classical start on this machine ("neural budget exhausted", 121 ms against 100 ms). Products move at most 0.0022 % (overhead vapour), stage temperatures at most 0.0015 K.

## 3. What did not work, and what stays failing

- E6: the bundled regrouped model no longer binds (the changed property revisions enter the identity it is bound to). Failing: `V3BundledRegroupedModelTest` x3 (`theNetworkPackageWithZeroNitrogenBindsTheBundledModel`, `packagedWeightsAndDecoderMatchIndependentFloat64Fixtures`: NoSuchElementException; `aFourthSideDrawIsAValidInputTheBundledModelDeclines`: expected true was false) and `MaterialCatalogTest.aViscosityRevisionBumpMovesOnlyTheFluidIdentity` (neural predict empty). Sidecar not touched.
- `V3WaterPhaseQualificationTest.observerReceivesOnlyTheReleasedCertifiedWetProfile`: its fixture `science/column/v3/neural/tjl20-wet-boundary.json` is a wet certificate captured on the old data; on the new data the learned seed fails the audit (water dew point 3.45 > 1 at tray 2). A re-capture by bisecting the condenser temperature (`WetBoundaryCaptureProbe.java`) failed: the classical solve gives no free water anywhere in 332.95..334.95 K, so the fixture can only be re-made the way it was made (from the learned route), which needs the model (E6).

## 4. Proposals and owner decisions

1. Methane Cp source: Setzmann-Wagner alpha0 (chosen, least change, consistent with the spine) or CEA (1 % higher at 900 K). The 1.2 % move at 900 K is the old record's JANAF basis, not a fit error.
2. Nitrogen Cp fit pinned at 273.16 K (equality constraint) so the held value below meets D37/S4 (0.047 % < 0.05 %); a plain fit misses by 0.053 %.
3. Nitrogen viscosity reuses the pilot's CoolProp tables (same definition within 0.22 %) instead of a new table.
4. `STEAM_STANDARD_ENTROPY` stays 188.835 J/(mol K), cited as the CODATA key value (CEA gives 188.829).
5. The spine records lose the JANAF entropy uncertainty (informational field); spine and package revisions bumped for text changes only.
6. Test holdouts that were JANAF or WebBook values now read CoolProp, CEA or an RRHO estimate (CO2 below its triple point: RRHO at 100 K +0.004 %, CEA at 200 K +0.011 %).
7. The wet-boundary fixture: re-capture after O3/E6 with the new model, or replace the test's fixture with one made by the classical wet route.

## 5. Open items

- Test fixtures still carrying NIST copies: `src/test/resources/materials/cdu17-reference/.../water.json` (NIST Shomate), `src/test/resources/column/v3/tjl-reference-resources.json` (methane `nist-r1` copies).
- `tools/spine-join-measurement/build-spine-records.mjs` (JANAF entropies), `examples/Build-NitrogenFluidProperties.py` and `examples/Fetch-FluidSteamReferences.py` (WebBook fetchers), `tools/wp5-legacy-digest/`: O5. Manifests that hash `tools/neural/fit_methane.py` point at a removed file.
- `MaterialCatalogTest` and `V3BundledRegroupedModelTest` wait for E6; the final lane is not run.

Code guide: no change (comments only in Java; merge prep not done).
