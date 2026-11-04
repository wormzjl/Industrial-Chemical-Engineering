# 2026-11-04-open-thermo-data

Purpose: thermo data under open terms. Generated light, medium, heavy and oil-sand crude grades replace the ExxonMobil and Tia Juana assays. The crude viscosity gets a new source. The NIST-derived values are re-derived from CoolProp, NASA CEA and IAPWS. Unused data is removed. This follows the decisions of [DATA_LICENSE_REVIEW.md](../2026-11-04-refinery-compounds/DATA_LICENSE_REVIEW.md).

Status: In progress since 2026-11-04, on `claude/refinery-compounds-thermos-252973` after the refinery-compounds batch. Index: [../INDEX.md](../INDEX.md).

## Documents

- [OPEN_DATA_PLAN.md](OPEN_DATA_PLAN.md): expected behaviour, the data today, work packages O1 to O6 and decisions E1 to E6.

## Code areas

- `src/main/resources/data/icheme/materials/`: assays, presets, networks, properties, water, transport and spine records; `assets/icheme/flowsheet_examples/crude_unit.json`.
- The neural registry and sidecar (read only until decision E6).

## History

- 2026-11-04: plan written; the owner gave E1 to E3.
- 2026-11-04: O1: the raw NIST copies left the tracked tree for the main checkout's `research/`: the 7 F4 WebBook TSV files (`tools/fluid-probes/f4/nist/` to `research/2026-09-23-fluid-followups/f4-nist/`), `tools/neural/fit_methane.py` (the NIST methane Shomate coefficients) and both `cea-holdouts.mjs` (JANAF and WebBook rows) to `research/2026-11-04-open-thermo-data/nist-copies/`; the F4 scripts, `tools/fluid-probes` READMEs, `methane-qualification.md` and `tools/spine-join-measurement/README.md` point there; `research/INDEX.md` patched row by row. No physics change; no test run (tools and documents only). `tools/wp5-legacy-digest` left to O5.
- 2026-11-04: O2: every NIST-derived value re-derived from open sources by `tools/open-thermo-data/` (CoolProp 8.0.0 alpha0 and equations of state, ATcT, IAPWS R12-08, NASA CEA for comparisons); forms unchanged, no Java change beyond comments. Records: nitrogen `fluid-nitrogen-coolprop-r4` (Cp fit pinned at 273.16 K, held 29.1112 to 29.1163, max change +0.020 %; viscosity = the pilot's CoolProp tables, liquid table up to 10 % in the last kelvin below Tc, 0.34 % below 124 K), tjl20 methane `tjl20-methane-coolprop-r2` (Setzmann-Wagner alpha0; Cp up to +1.17 % at 900 K), water `water-iapws95-shomate-r3` (Cp within 0.038 %, h(298.15) 0.53 to 0 J/mol), liquid calibration `coolprop-8.0.0-liquid-calibration-r1` (volumes within 1.3e-7), spine nitrogen/methane/ethane r3 and carbon dioxide r4 (JANAF texts and the entropy uncertainty removed), packages refinery-tier1-r2, crude-regrouped-r3 (tjl20, tjl20_nitrogen), pilot-cryogenic-p3-r3; crude_unit.json basis re-recorded. Tests: JANAF/WebBook holdouts replaced by CoolProp, CEA and RRHO references; compat corpus energies pinned (schema-1 -10.27 J, island sets 616.61 J, steam v2 +126.97 J, each equal to n*dh_ig computed apart); column draw-volume targets re-captured (moves up to 1.2e-5). Failing and left: E6 (V3BundledRegroupedModelTest x3, MaterialCatalogTest.aViscosityRevisionBumpMovesOnlyTheFluidIdentity: the model no longer binds) and V3WaterPhaseQualificationTest.observerReceivesOnlyTheReleasedCertifiedWetProfile (old-data wet certificate fixture; a re-capture failed). Review: `NIST_REDERIVATION_REVIEW.md` (later commit).

## Open items

- R1: open sources for crude viscosity and a generation method for the grades.
- R2: an inventory of unused data.
- E6: the neural model, after O3.
