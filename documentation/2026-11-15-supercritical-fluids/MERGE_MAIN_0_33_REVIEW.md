# Merge of main 0.33.0 into the supercritical batch

Batch [2026-11-15-supercritical-fluids](README.md). Sub-thread work, 2026-11-16. Owner, 2026-11-16: "uh merge main
first". Local `main` `66a87f9d5` (0.33.0: the pollution system, fluid interfaces and equipment visual design batches, and
this batch's reservation rows in `FORMATS.md`) merged into `claude/supercritical-fluid-support-d0ffa4` (`cdc05e565`).
Merge base `b41f7f9c7`.

## Summary

- Merge commit `dfc9d9e55` (parents `cdc05e565`, `66a87f9d5`). Three conflicts, all in documents, as the trial merge
  showed; none in `science/fluid/**` or `science/thermo/**`. Main's numbers stand: `mod_version=0.33.0`, fluid facts
  schema 27, checkpoint state format 50, protocol `icheme-3`. No version bump. The reserved rows (schema 28, checkpoint
  51, `icheme-4`) are untouched, for S8.
- Material records: 33 changed on both sides, 332 revision scopes read. Every token was changed by one side only, in a
  scope where the other side changed no values. No new token (section 2).
- One rule was broken by the merge without a conflict: S3 (owner decision O10 A) gave every bundled dataset a
  `critical_volume` sub-record; main's five rare gases (W1 of the fluid interfaces batch) have none. Fixed in its own
  commit with S3's tool, `critical-volume.mjs`, on ChemSep's values (section 3).
- Compile: `compileJava` and `compileTestJava` clean on the merge, no fix needed. Main's new code calls none of the
  signatures this branch changed, except the test `SteamTurbineTest`, which reads `InletPhase.bulk(...).condensedMassShare()`
  on steam (not dense: no change; section 4).
- `build.gradle`, `en_us.json` and the client and sizing files merged with disjoint hunks; both sides' content is present
  (section 5). Main changed nothing under `src/main/rust`.
- Main's new readers of a phase slot: the burst-pipe release (`FluidWorldAuthority.releaseContents`, `BurstPipes.phase`)
  splits a vessel's or pipe's contents by slot. A dense fluid in the liquid slot spills as a liquid, and only its
  volatile share goes to the air. Report only (section 6).
- Engine digest, record mode on the merge: 429 requests on each side, the same list; one line differs (313,
  `tjl20_methane_nitrogen` TP 350 K 12 MPa, CH4), and only by the grade token `EXTRAPOLATED` to
  `ESTIMATED_DECLARED_ERROR`: S3's known move (plan section 2b, point 17). Main's 24 re-recorded lines (W1) stand as main's (section 7).
- Runs on the merge: `test` 373 classes, 2,362 tests, 1 failed (the critical-volume check, section 3); `columnGates` 75
  classes, 466 tests; `equipmentGates` 3, 21; `holdGates` 10 (14 suites), 136; none failed. After the fix: 30 classes the
  fix can move (identities, examples, rare gases, viscosity), 289 tests, none failed (section 8).
- Left for the final lane: re-record `engine-digest.txt` once; the translation commit (`LanguageFilesTest`,
  `UnitTextTest` not run here); chain-100, the GameTests and the campaign as the lane selects them.

## 1. Conflicts

| File | Resolution |
|---|---|
| `documentation/INDEX.md` | Every row of both sides: this batch's row, then main's three (fluid interfaces, equipment visual design, pollution system). |
| `documentation/reference/code-guide/client-screens.md` | This branch's `DeviceColumn` (352 lines), `DeviceMeters` (332) and `DenseLabel` (113) rows; main's `ConverterDoor` row (132 lines, the liquid hopper's `plant`). The line counts are those of the merged files. |
| `tools/compound-import/README.md` | Both history lines: main's W1 (the rare gases), then this batch's S3 (the critical volumes). |

Scripts: `tmp/supercritical-merge/resolve.js`.

## 2. Material revision tokens

Rule (AGENTS.md, "Saves and data formats"): the scope of a token is the object that declares `revision`, without the
sub-objects that declare their own. For each of the 33 files the script `tmp/supercritical-merge/tokens.js` reads the base,
main, this branch and the merged file, and compares per scope the token and the values.

| Scope | Files | Base → main | Base → ours | Merged | Why nothing to do |
|---|---|---|---|---|---|
| record (`$`) | 29 | unchanged | unchanged | base token | neither side changed it or its values |
| record (`$`): `chemsep_carbon_dioxide`, `chemsep_n_hexane`, `chemsep_toluene` | 3 | `chemsep-8.3-dwsim-10.2.5-r1` | `...-r2` | `...-r2` | ours only (S3: `fluid_domain` range and evidence); main changed no value in the record's own scope |
| record (`$`): `tjl19_propane` | 1 | `tjl19-dwsim-10.2.3-r1` | `...-r2` | `...-r2` | same |
| `/hazard` | 33 | new (`npg-idlh-r1` 20, `npg-idlh-naphtha-r1` 12, `aegl3-60min-r1` 1) | absent | main's | main only (the pollution batch's `Hazard` block, its own revision) |
| `/critical_volume` | 33 | absent | new (`chemsep-8.3-vc-r1` 21, `rackett-standard-density-r1` 12) | ours | ours only (S3) |
| `/viscosity/liquid`, `/viscosity/vapor`, `/freezing`, `/thermochemistry`, `/combustion`, `/flammability`, `/group_counts/*`, `/henry_in_water`, `/homomorph` | 33 each (where present) | unchanged | unchanged | base | neither side changed them |

No scope has a token changed by both sides, a token changed by one side with values changed by the other, or values
changed without a token. Full table: `tmp/supercritical-merge/tokens.txt` (332 rows).

## 3. The rare gases' critical volumes

Expected: S3 (O10 A) made `critical_volume` a sub-record of every bundled dataset, read by the dense-fluid residual
viscosity (`MixtureViscosity`); `MaterialCatalogTest.aCriticalVolumeSubRecordParsesFallsBackToTheCubicsAndMovesOnlyTheFluidIdentity`
asserts it, with a count of 47.

What the merge did: main added `chemsep_argon`, `chemsep_helium`, `chemsep_krypton`, `chemsep_neon` and `chemsep_xenon`
(52 datasets). Without the sub-record, the property falls back to the cubic's `3.9514 x 0.07780 R Tc / Pc`, 2 to 7 % above
ChemSep's value. The test fails on the first rare gas.

Fix (commit after the merge): `node tools/compound-import/critical-volume.mjs tmp/dwsim-data src/test/resources/science/thermo/coolprop <copy of the five records> <out> --write`,
the same tool and source as S3, on a copy that holds only the five records (the tool refuses a record that already has one).

| Dataset | ChemSep Vc, cm3/mol | Cubic's, cm3/mol | Ratio |
|---|---|---|---|
| argon | 74.57 | 78.73 | 0.947 |
| helium | 57.30 | 58.44 | 0.981 |
| krypton | 91.20 | 97.31 | 0.937 |
| neon | 41.70 | 42.78 | 0.975 |
| xenon | 118.00 | 126.81 | 0.931 |

The ratios lie inside S3's range (0.746 to 0.993). The sub-record has its own revision `chemsep-8.3-vc-r1`; the records'
own revisions do not change. The fluid identity of every package that holds a rare gas moves (its `;critical-volume=`
segment gains five entries); no column identity moves. The test's count is 52, its message "31 ChemSep datasets".
Effect on results: the residual viscosity at reduced density near 0; air at ambient (argon 0.93 %, neon 18 ppm) moves by
less than the last printed digit.
The identity bound holds: `ColumnSolveTest` (its wide package, more than 64 compounds) and `MaterialCatalogTest` pass
after the fix, and `FlowsheetExamplesTest` passes with the examples unchanged (their basis token is the network package restricted to
the compounds the sheet feeds, `SheetCompounds`, `RevisionNotice.tokens`; main listed the rare gases in the examples' component
lists, but no sheet feeds one).

## 4. Signatures this branch changed

Searched in main's added lines (`git diff b41f7f9c7 main -- src`): `InletPhase.Supply`, `PumpSizing.Load`,
`SolveConstants.bubbleShares`, `ReleasePressure.lowestHeld`, `liquidLikeWeight`, `pumpLiquidLikeWeight`, `State.dense()`,
`State.reducedDensity()`. Main calls none of them in `src/main`. One caller in `src/test`:

- `SteamTurbineTest.java:68` reads `InletPhase.bulk(model, state).condensedMassShare()`, a turbine exhaust's wetness on
  water at or below the LP header (subcritical). For a dense fluid this branch reads `dropletMassShare()` (0); on steam the
  two are equal. No change.
- `MachineHealth.java:224` (main, W5 wet exhaust) reads `PipeObservation.condensedShare`, which `MoverSuction` fills
  from `dropletMassShare()` on this branch: 0 for one dense fluid, as S4b wants.

## 5. Silent merges read by hand

- `build.gradle`: the column group keeps `science/thermo/PhaseTraits*` and `PhaseIdentification*`; the equipment group
  has main's `alarm_beacon` models; the fluid GameTest namespaces (`icheme_fluid_test,icheme_dense_test`) stand; main's
  FTB repositories and compile-only dependencies are in.
- `en_us.json`: valid JSON, 4,667 keys, none twice; every key of main (4,657) and of this branch (4,524) present, and
  every text either side changed is that side's (`tmp/supercritical-merge/lang.js`).
- `DeviceMeters.java`: main's hunk (the `ANALYSER` sensor kind, merged lines 193 to 202) and this branch's (`denseRow`,
  `withDense`, lines 25 to 50 and 253 to 300) do not touch.
- `FluidDeviceScreen.java`: main's `hopper()`, `fixed()` and the hopper's lead rows (lines 117 to 141, 732); this
  branch's `deviceMeters()` with `denseRow` (lines 708 to 723). The merged `deviceInput` has both.
- `Limit.java`: this branch's `PUMP_DENSITY` (line 127) and main's `TURBINE_EXHAUST` (line 290) are separate enum entries.
- `GuiPrototypes.java`: main's `alarmBeacons()` and this branch's `dense()` are both in `prototypes()`.
- Native code: `git diff --stat b41f7f9c7..main -- src/main/rust` is empty.

## 6. Main's new readers of a phase slot (report only)

| Reader | What it reads | Against a dense fluid |
|---|---|---|
| `runtime/fluid/FluidWorldAuthority.java:1018-1019` (`releaseContents`, pollution W-burst) | a reservoir's `vaporAmountsView` against liquid and aqueous: gas share per compound | a dense fluid in the liquid slot (δ > 1) is booked as liquid |
| `FluidWorldAuthority.java:1031-1032` | a pipe's `phaseMoles()[2]` (vapour) against `[0]`, `[1]` | the same, for a pipe's contents |
| `runtime/fluid/Releases.java:255-259` (`contents`) | the liquid moles scaled by `HazardStandard.volatileMoles` | a dense CO2 or N2 at ambient flashes wholly to gas (and dry ice for CO2); booked as liquid, only its volatile share goes to the air |
| `runtime/pollution/BurstPipes.java:71-76` (`phase`) | gas mass against liquid mass | the burst block reads LIQUID for a dense fluid in the liquid slot |
| `runtime/fluid/MachineHealth.java:224` | `condensedShare` (see section 4) | 0 for one dense fluid: consistent |
| `science/fluid/network/Air.java`, `Placement.java:116` (the gas intake) | the ambient air at 101.325 kPa | never dense |

The proposal for the main thread: the release could split by the flash at ambient (the release's own state), not by the
slot; or use `State.dense()` and book a dense fluid as gas. Not done here.

## 7. Engine digest

`EngineDigestTest` ran once, in its record mode (`ICHEME_ENGINE_DIGEST_RECORD=true`), inside the `test` invocation on the
merge commit; `build/engine-digest.txt` was compared with the merged reference by `tmp/supercritical-merge/digest.js` and
kept as `tmp/supercritical-merge/engine-digest-recorded.txt`. The reference was not touched.

| | Reference (merged) | Recorded |
|---|---|---|
| Requests | 429 | 429 |
| Same request list | yes | |
| Differing lines | | 1 (line 313) |
| Of them, the grade token only | | 1 |
| Other differences | | 0 |

The reference merged both sides' changes without a conflict: main's W1 (`ea8666171`, the rare gases in the network
package) re-recorded 24 lines, this branch's S1 (`734c8b395`) 2. The recording on the merged code reproduces all of
them, so neither side's lines interact. The final lane re-records line 313 once.

## 8. Runs

`node tools/merge-lane/affected.mjs select` (`tmp/supercritical-merge/affected.txt`): `test` 374 of 501 classes,
`holdGates` all 10, `columnGates` 76 of 89, `equipmentGates` 4 of 31; `fluidSolverRegression` and the GameTests due (not
run: the final lane's). Left out: `LanguageFilesTest`, `UnitTextTest` (the translation commit), `NativeColumnSolverTest`
(build.gradle excludes it from `columnGates`; `nativeSmoke`'s, and main changed no Rust). `EngineDigestTest` ran once,
inside the `test` invocation, in record mode. One invocation per task, `--tests`, 4 workers (`tmp/supercritical-merge/runs.sh`).

| Group | Classes | Tests | Failed |
|---|---|---|---|
| `test` (merge, `dfc9d9e55`) | 373 (289 filters, 10 of them packages) | 2,362 | 1: `MaterialCatalogTest.aCriticalVolumeSubRecordParsesFallsBackToTheCubicsAndMovesOnlyTheFluidIdentity` (line 243, `chemsep_argon` has no `critical_volume`) |
| `columnGates` | 75 | 466 | 0 |
| `equipmentGates` | 3 | 21 | 0 |
| `holdGates` | 10 (14 suites with the nested ones) | 136 | 0 |
| `test` (fix, `0f4230144`) | 30 | 289 | 0 |

The fix's classes: `MaterialCatalogTest`, `ColumnSolveTest`, `FlowsheetExamplesTest`, `RareGasPropertiesTest`,
`GasIntakeTest`, `SheetCompoundsTest`, `FluidFactsCompatTest`, `FluidPropertyReloadGuardTest`, `ThermoIdentityTest`,
`MaterialFluidDataTest`, `RestrictedPackagePhysicsTest`, `SpineNetworkPathTest`, `SpinePackageEvaluatorTest`,
`MixtureViscosityTest`, `G3F11SupercriticalRegionTest`, `DataRangeContinuationTest`, `SimulatorRunnerTest`,
`FlowsheetUnitsTest`, `ProcessOutletTest`, `SheetSizingTest`, `ServiceTest`, `RestResolutionTest`,
`RangeContinuationGateTest`, `AqueousSolventDataTest`, `MaterialHazardTest`, `MaterialPresetsTest`,
`MaterialThermochemistryTest`, `PilotCryogenicCatalogTest`, `RefineryCarbonDioxideCatalogTest`, `RefineryImportTest`:
the classes that pin a fluid identity or read the critical volume. `affected.mjs select --since dfc9d9e55..HEAD`
names 380 (every class that loads the catalog reads the properties folder); those the fix could not move are the final
lane's, which selects them.

## 9. Left for the final lane

- Re-record `src/test/resources/thermo/engine-digest.txt` once (line 313).
- The translation commit, then `LanguageFilesTest` and `UnitTextTest` (main's and this branch's new keys).
- The classes the lane selects (`affected.mjs`), chain-100 (`fluidSolverRegression`, due: `V3PengRobinsonThermo` and
  the material records changed), the fluid GameTests (due), `nativeSmoke` where a library is built, the campaign when
  its inputs changed.
- For the main thread: the burst-pipe release reads the slot (section 6).
