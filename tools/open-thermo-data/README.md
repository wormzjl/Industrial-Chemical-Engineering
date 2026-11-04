# open-thermo-data

Purpose: re-derive the bundled values that came from NIST Standard Reference Data (the WebBook and JANAF) from open implementations of the same correlations, and measure how far each one moves. Owner decision D-B option 2 of `documentation/2026-11-04-refinery-compounds/DATA_LICENSE_REVIEW.md`.

Batch: `2026-11-04-open-thermo-data`, work packages O2, O4 and O7 (O1 moved the raw NIST copies to the main checkout's `research/`). Reports: `documentation/2026-11-04-open-thermo-data/NIST_REDERIVATION_REVIEW.md`, `CUT_VISCOSITY_REVIEW.md` (O4).

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
7. `node sidecar-schema4.mjs [--write]` (from the worktree root, O7 step 2): the regrouped model's sidecar to schema 4. Reads the records the schema-3 block named at git 03e7b6138 (each checked against its named revision), writes only the values the binding compares (`TrainedPhysics`: PR78 constants, ideal-gas fits, non-zero pairs, the water's saturation, Watson and Shomate values, the trained range 298.15 K to the trained feed maximum 765.77 K), and recomputes the registry's `pipelineSha256`.
8. `O7BindingProbe.java` (columnGates): the distance of each compound and the water from the trained values, whether the bundled model binds the default preset, and that preset from the learned start and the classical one by control polls; writes `tmp/open-thermo-o7/binding.json`.
9. `WetBoundaryCaptureProbe.java` (columnGates): an attempt to re-capture `science/column/v3/neural/tjl20-wet-boundary.json` by bisecting the condenser temperature on the classical solve. The classical route declines free water at every temperature tried (332.95 to 334.95 K), so it cannot reproduce the 2026-09-10 capture; kept as the record of that attempt. O7 found why: it solved the bundled 19-compound package, while the test reads the fixture under `TjlReferenceExtension` (the frozen 20-compound TJL20 reference with the bundled water), and the fixture was made by a parametric boundary continuation, not by a classical solve.
10. `WetBoundaryTeacherProbe.java` (columnGates, O7 step 3): re-makes the fixture the way it was made, a port of `tools/neural/V3WetBoundaryTeacher.java` under `TjlReferenceExtension`: classical solve at 338.15 K, 1 mol/s of free water held on tray 1 while the condenser outlet is bisected to tray 1's water saturation, then the release as a fixed seed through LNN_ONLY/PREDICTED_WET, kept only as a WET_EQUILIBRIUM certificate. No neural model takes part. Writes `tmp/open-thermo-o7/tjl20-wet-boundary.json` (copy it over the fixture) and `wet-boundary.json` (the trace and the water dew point margin per tray).
11. `O4ViscosityProbe.java` (columnGates, package `science.column.v3.thermo`, O4): the whole-crude liquid viscosity of the bundled assays at 15, 40 and 100 C through `MixtureViscosity` on `icheme:tjl20_methane`, with the pair factor exp(delta) (read from the package's correction by reflection, so the same file runs on both sides of O4) and each cut's pure value. Run on the parent commit with `-Do4.label=before` and on O4 with `-Do4.label=after`; writes `tmp/open-thermo-o4/viscosity-<label>.json`.
12. `O4MixtureEquivalenceProbe.java` (test, package `science.material`, O4): the one-term `LiquidMixtureCorrection` against the four-coefficient form it replaced, copied verbatim from 50d6c506f, at [G, 0, 0, 0] with every old participant's descriptor {1, 0, 0}; the old participation is read from the parent commit's property records (`git show 50d6c506f:src/main/resources/data/icheme/materials/properties/<record>` into `tmp/open-thermo-o4/head-properties/`). Every bundled package, its assays, single compounds and 20,000 random mixtures each at random 150 to 1200 K, compared by raw bits; writes `tmp/open-thermo-o4/equivalence.txt`.
13. `O4SoftPins.mjs` with `O4Soft.java` (O4): `node tools/open-thermo-data/O4SoftPins.mjs on <test files>` from the worktree root turns the files' `assertEquals` into a non-throwing `O4Soft.eq` that prints `PIN` lines (caller line, message, pinned value, value now, ok or MOVED), saving the originals to `tmp/open-thermo-o4/soft-originals/`; run the tests once, collect the `PIN` lines from the test results, then `node tools/open-thermo-data/O4SoftPins.mjs off` restores them. O4 used it on the three sizing sample tests to get every moved value in one run.

## Entry points

`lib.mjs` (evaluators and fits), `derive.mjs`, `records.mjs`, `spine-checks.mjs`, `compat-energy.mjs`, `check-transport.mjs`, `OpenThermoOracleProbe.java`, `ColumnPresetProbe.java`, `WetBoundaryCaptureProbe.java`, `sidecar-schema4.mjs`, `O7BindingProbe.java`, `WetBoundaryTeacherProbe.java`, `O4ViscosityProbe.java`, `O4MixtureEquivalenceProbe.java`, `O4SoftPins.mjs`, `O4Soft.java`.

## History

- 2026-11-04: created for O2. The record forms are unchanged (no Java change); the nitrogen Cp fit is pinned at its 273.16 K value so that the held capacity below it is the reference's (D37/S4 within 0.05 %); the nitrogen viscosity reuses the pilot's CoolProp tables (same definition, node agreement 0.04 % liquid, 0.22 % vapour); methane uses the CoolProp Setzmann-Wagner alpha0 (0.015 % from the bundled spine).
- 2026-11-04: O7 step 1 (E16): `derive.mjs` fits the methane Cp to the reference spine as the spine joins its segments (Setzmann-Wagner alpha0 to 425 K, GERG-2008 ideal part above, D13), `--methane-only`; `tjl20-methane-spine-r3`.
- 2026-11-04: O7 step 2 (E8, E9): `sidecar-schema4.mjs` writes the trained physics into the sidecar as values; `O7BindingProbe.java` measures the distances, the binding and the column counters.
- 2026-11-04: O7 step 3 (O2 decision 7): `WetBoundaryTeacherProbe.java` re-made `tjl20-wet-boundary.json` (condenser outlet 333.953903 to 333.952610 K).
- 2026-11-04: O4 (E12, E14): `O4ViscosityProbe.java` measures the whole-crude viscosity before and after; `O4MixtureEquivalenceProbe.java` proved the one-term mixture term equal to the old form bit for bit (100,132 mixtures on 5 packages, 0 differing) before the old form was deleted.
