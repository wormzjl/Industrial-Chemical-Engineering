# open-thermo-data

Purpose: re-derive the bundled values that came from NIST Standard Reference Data (the WebBook and JANAF) from open implementations of the same correlations, and measure how far each one moves. Owner decision D-B option 2 of `documentation/2026-11-04-refinery-compounds/DATA_LICENSE_REVIEW.md`.

Batch: `2026-11-04-open-thermo-data`, work packages O2 and O7 (O1 moved the raw NIST copies to the main checkout's `research/`). Report: `documentation/2026-11-04-open-thermo-data/NIST_REDERIVATION_REVIEW.md`.

## Sources

All are the main checkout's git-ignored copies under `research/2026-09-24-coolprop-low-temperature/sources/` (`MANIFEST.md` there gives revisions and hashes). Nothing is downloaded.

- CoolProp 8.0.0 fluid files (ae81610e, MIT): ideal-gas Helmholtz terms (`alpha0`), the reference equations of state, the nitrogen transport block.
- CoolProp master 9b35f538 `INFO.STANDARD_STATE` (MIT): the ATcT 1.220 formation enthalpies.
- NASA CEA `thermo.inp` (3f4441d2, Apache-2.0): 9-coefficient polynomials, for the comparisons.
- IAPWS R12-08 (2008) viscosity of water, transcribed in `lib.mjs` and checked against the release's table 4.
- The pilot nitrogen record's CoolProp 8.0.0 viscosity tables (`properties/pilot_nitrogen.json`).

## How to run

Node 22 from this folder; the Java probes through Gradle (one at a time, AGENTS.md).

1. `OpenThermoOracleProbe.java`: copy to `src/test/java/com/wormzjl/icheme/science/thermo/reference/`, run `./gradlew test --offline --tests com.wormzjl.icheme.science.thermo.reference.OpenThermoOracleProbe`, move it back. Writes `tmp/open-thermo-nist/oracle.json`: the CoolProp equations of state (Java Helmholtz oracle) at the 8 liquid calibration states, the 13 water-vapour reference points and the nitrogen test states.
2. `node check-transport.mjs`: the IAPWS 2008 and Lemmon-Jacobsen 2004 transcriptions against their published check values.
3. `node derive.mjs` prints the deltas; `node derive.mjs --write` writes `properties/nitrogen.json`, `properties/tjl20_methane.json` (and the override example), `water/water.json`, `transport/liquid_calibration.json` and the test references `fluid/reference/{nitrogen-cp-checks,water-vapor-checks,water-vapor-compression}.json`. Its report goes to `tmp/open-thermo-nist/derive-report.json`. Since O7 the methane Cp is fitted to the bundled reference spine (`spine/methane.json`, decision E16), and `node derive.mjs --methane-only [--write]` runs only that part (the rest is O2's one-shot and stops at its guards on the written records); its report goes to `tmp/open-thermo-o7/methane-cp-report.json`.
4. `node spine-checks.mjs` prints the open comparisons that replace the JANAF and WebBook holdouts; `node records.mjs --write` edits the spine and package records (texts, the removed entropy uncertainty, revisions).
5. `node compat-energy.mjs`: the energy each compat corpus image's load books for the data change, computed apart from the engine (reads the parent commit's records with git); `FluidFactsCompatTest` pins these.
6. `ColumnPresetProbe.java` (columnGates, package `science.column.v3`): the default column preset before (the parent commit's records) and after, from the learned start and the classical initializer; writes `tmp/open-thermo-nist/column-preset.json`.
7. `WetBoundaryCaptureProbe.java` (columnGates): an attempt to re-capture `science/column/v3/neural/tjl20-wet-boundary.json` by bisecting the condenser temperature on the classical solve. The classical route declines free water at every temperature tried (332.95 to 334.95 K), so it cannot reproduce the 2026-09-10 capture; kept as the record of that attempt.

## Entry points

`lib.mjs` (evaluators and fits), `derive.mjs`, `records.mjs`, `spine-checks.mjs`, `compat-energy.mjs`, `check-transport.mjs`, `OpenThermoOracleProbe.java`, `ColumnPresetProbe.java`, `WetBoundaryCaptureProbe.java`.

## History

- 2026-11-04: created for O2. The record forms are unchanged (no Java change); the nitrogen Cp fit is pinned at its 273.16 K value so that the held capacity below it is the reference's (D37/S4 within 0.05 %); the nitrogen viscosity reuses the pilot's CoolProp tables (same definition, node agreement 0.04 % liquid, 0.22 % vapour); methane uses the CoolProp Setzmann-Wagner alpha0 (0.015 % from the bundled spine).
- 2026-11-04: O7 step 1 (E16): `derive.mjs` fits the methane Cp to the reference spine as the spine joins its segments (Setzmann-Wagner alpha0 to 425 K, GERG-2008 ideal part above, D13), `--methane-only`; `tjl20-methane-spine-r3`.
