# S3 data review: pressure ranges, critical volume, the dense-fluid residual viscosity

Batch [2026-11-15-supercritical-fluids](README.md), stage S3 of [SUPERCRITICAL_PLAN.md](SUPERCRITICAL_PLAN.md) (sections 2a,
2b), owner decisions O4 A (ranges), O10 A (critical volume per dataset) and O5 A (the Jossi-Stiel-Thodos residual). Sub-thread
work, 2026-11-15.

## Summary

- Nine datasets widen `fluid_domain.pressure_max_pascal`, each with an evidence sentence and its record's revision bumped
  (table 1). The network package `icheme:refinery` envelope (the hull of its members) rises from 10 to 30 MPa.
- Every bundled dataset (47) gains a `critical_volume` sub-record with its own revision (table 2). `Property.criticalMolarVolume()`
  reads it, and falls back to the cubic's `3.9513730355914 x 0.07780 R Tc / Pc` where a record has none.
- `MixtureViscosity` gains `residual`, `jossiStielThodos` and `dense` (= `vapor` + `residual`). No caller uses them yet: S2
  wires them.
- The tests are green (last section). The engine digest does not move: no file of the engine or its digest test changed.
- Not done: the cuts' ranges, the network's use of `dense`, the liquids M8 cannot reach (last section).

## 1. Pressure ranges (O4 A)

The evidence is the S0 measurements, re-read in S3's first run of `G3F11SupercriticalRegionTest` (M1, M5, M8) against
CoolProp's reference equations. "Region R" is Tr 1.0-1.2 x Pr 1.5-3, declared at 11 % density and 13 % cp. The liquid
column is M8's compressed liquid against G3F1's bounds.

| Dataset | Max before | Max now | Revision | Density / cp outside the band and R | In R | Liquid |
|---|---|---|---|---|---|---|
| chemsep_carbon_dioxide | 2 MPa | 30 MPa | chemsep-8.3-dwsim-10.2.5-r1 → r2 | 5 / 9 % | 8.7 / 10.6 % | to 30 MPa |
| nitrogen | 10 MPa | 30 MPa | fluid-nitrogen-coolprop-r4 → r5 | 3.4 / 6.4 % | 6.9 / 11.6 % | to 30 MPa |
| tjl20_methane | 10 MPa | 30 MPa | tjl20-methane-spine-r3 → r4 | 3.2 / 6.4 % | 6.8 / 10.0 % | none above its 293.15 K floor |
| tjl19_ethane | 2 MPa | 30 MPa | tjl19-dwsim-10.2.3-r1 → r2 | 3.4 / 4.0 % | 9.7 / 11.0 % | 293.15-305.3 K not measured |
| tjl19_propane | 2 MPa | 30 MPa | tjl19-dwsim-10.2.3-r1 → r2 | 3.5 / 2.8 % | 10.2 / 8.4 % | to 30 MPa |
| chemsep_hydrogen | 2 MPa | 30 MPa | chemsep-8.3-dwsim-10.2.5-r1 → r2 | from 273 K: 1.4 / 1.1 % | — | — |
| chemsep_ethylene | 2 MPa | 20 MPa | chemsep-8.3-dwsim-10.2.5-r1 → r2 | 5.0 / 4.9 % | 10.9 / 12.1 % | to 20 MPa, cold cp 14 % |
| chemsep_n_hexane | 2 MPa | 4.57 MPa | chemsep-8.3-dwsim-10.2.5-r1 → r2 | 7 / 12 % to 4.57 MPa | — | to 20 MPa |
| chemsep_toluene | 2 MPa | 6.19 MPa | chemsep-8.3-dwsim-10.2.5-r1 → r2 | 7 / 12 % to 6.19 MPa | — | to 30 MPa |

Notes, each written into the record's evidence sentence:

- Hydrogen below 273 K is not qualified: cp is up to +28 % at 34-50 K (a quantum fluid).
- Ethylene's cp is declared at 13 % at the edge of the band's box 1 (-12.1 % at 310.6 K and 7.56 MPa). Its cold liquid cp is
  declared at 14 % (141 K: -12.2 % at 2 MPa to -13.2 % at 20 MPa).
- n-hexane ends at the last state within 7 %: the first state beyond is -7.27 % density at 518.0 K and 6.09 MPa. Toluene ends
  the same way: -8.09 % at 603.6 K and 8.25 MPa.
- Correction to S0: the qualification review says ethylene "misses once". It also has -8.8 % density at 310.6 K and 10 MPa
  (Tr 1.10, Pr 1.98). That state lies in region R and inside R's declared 11 %, so the 20 MPa range stands.

The ranges change only `pressure_max_pascal`, the evidence text and the revision. The S3 edit script checks that every
other key reads back equal.

## 2. Critical volume per dataset (O10 A)

The sub-record is `{"volume_m3_per_mol", "estimated", "source", "revision"}`. It is written by
`tools/compound-import/critical-volume.mjs` from the local DWSIM 10.2.5 extract (`tmp/dwsim-data`) and the CoolProp fluid
files the tests already carry. Sources by revision:

- `chemsep-8.3-vc-r1`: ChemSep v8.3 `CriticalVolume`. Used for the 26 ChemSep records and the 6 tjl19 records (matched by
  the CAS in `selection-regression.json`).
- `coolprop-8.0.0-critical-r1`: 1 / the critical molar density of the CoolProp file. Used for nitrogen and tjl20_methane.
- `iapws-95-critical-r1`: the molar mass over 322 kg/m3. Used for cubic_water.
- `rackett-standard-density-r1` (estimated): `Vc = Z_RA R Tc / Pc`. Z_RA reproduces the cut's own standard liquid density
  in the modified Rackett equation (Spencer and Danner, J. Chem. Eng. Data 17 (1972) 236). Used for the 12 cuts.

The ratio column is the dataset value over the cubic's `3.9513730355914 x 0.07780 R Tc / Pc`. It runs from 0.746 (water) to
0.993 (hydrogen); for the cuts it runs from 0.776 to 0.886. A PR78 cubic over-predicts Vc by 10-25 % for most compounds, so
the residual on the cubic's Vc would be biased high. M4 shows this: +28 % worst on the cubic Vc, -15.9 / +12.7 % on the
dataset Vc.

| Dataset | Vc cm3/mol | Cubic cm3/mol | Ratio | Revision | Estimated |
|---|---|---|---|---|---|
| chemsep_ammonia | 72.47 | 91.27 | 0.794 | chemsep-8.3-vc-r1 | no |
| chemsep_benzene | 256.00 | 293.48 | 0.872 | chemsep-8.3-vc-r1 | no |
| chemsep_butadiene | 221.00 | 251.46 | 0.879 | chemsep-8.3-vc-r1 | no |
| chemsep_butene_1 | 240.80 | 266.73 | 0.903 | chemsep-8.3-vc-r1 | no |
| chemsep_carbon_dioxide | 94.00 | 105.32 | 0.893 | chemsep-8.3-vc-r1 | no |
| chemsep_carbon_monoxide | 93.10 | 97.19 | 0.958 | chemsep-8.3-vc-r1 | no |
| chemsep_carbonyl_sulfide | 140.00 | 152.01 | 0.921 | chemsep-8.3-vc-r1 | no |
| chemsep_cis_2_butene | 233.80 | 264.40 | 0.884 | chemsep-8.3-vc-r1 | no |
| chemsep_cyclohexane | 308.00 | 347.35 | 0.887 | chemsep-8.3-vc-r1 | no |
| chemsep_ethanol | 167.00 | 213.66 | 0.782 | chemsep-8.3-vc-r1 | no |
| chemsep_ethyl_mercaptan | 207.00 | 232.32 | 0.891 | chemsep-8.3-vc-r1 | no |
| chemsep_ethylbenzene | 374.00 | 437.08 | 0.856 | chemsep-8.3-vc-r1 | no |
| chemsep_ethylene | 131.10 | 143.16 | 0.916 | chemsep-8.3-vc-r1 | no |
| chemsep_hydrogen | 64.15 | 64.61 | 0.993 | chemsep-8.3-vc-r1 | no |
| chemsep_hydrogen_sulfide | 98.50 | 106.52 | 0.925 | chemsep-8.3-vc-r1 | no |
| chemsep_isobutene | 238.80 | 267.04 | 0.894 | chemsep-8.3-vc-r1 | no |
| chemsep_m_xylene | 375.00 | 445.37 | 0.842 | chemsep-8.3-vc-r1 | no |
| chemsep_methyl_mercaptan | 145.00 | 166.16 | 0.873 | chemsep-8.3-vc-r1 | no |
| chemsep_n_hexane | 368.00 | 428.90 | 0.858 | chemsep-8.3-vc-r1 | no |
| chemsep_o_xylene | 370.00 | 431.69 | 0.857 | chemsep-8.3-vc-r1 | no |
| chemsep_oxygen | 73.37 | 78.35 | 0.936 | chemsep-8.3-vc-r1 | no |
| chemsep_p_xylene | 378.00 | 448.59 | 0.843 | chemsep-8.3-vc-r1 | no |
| chemsep_propylene | 185.00 | 202.73 | 0.913 | chemsep-8.3-vc-r1 | no |
| chemsep_sulfur_dioxide | 122.00 | 139.65 | 0.874 | chemsep-8.3-vc-r1 | no |
| chemsep_toluene | 316.00 | 368.19 | 0.858 | chemsep-8.3-vc-r1 | no |
| chemsep_trans_2_butene | 237.70 | 267.20 | 0.890 | chemsep-8.3-vc-r1 | no |
| crude_pc01 | 316.94 | 357.53 | 0.886 | rackett-standard-density-r1 | yes |
| crude_pc02 | 410.68 | 473.22 | 0.868 | rackett-standard-density-r1 | yes |
| crude_pc03 | 512.91 | 605.11 | 0.848 | rackett-standard-density-r1 | yes |
| crude_pc04 | 632.32 | 763.11 | 0.829 | rackett-standard-density-r1 | yes |
| crude_pc05 | 772.98 | 951.94 | 0.812 | rackett-standard-density-r1 | yes |
| crude_pc06 | 936.31 | 1172.59 | 0.798 | rackett-standard-density-r1 | yes |
| crude_pc07 | 1125.39 | 1427.98 | 0.788 | rackett-standard-density-r1 | yes |
| crude_pc08 | 1401.68 | 1797.26 | 0.780 | rackett-standard-density-r1 | yes |
| crude_pc09 | 1839.51 | 2371.45 | 0.776 | rackett-standard-density-r1 | yes |
| crude_pc10 | 2559.84 | 3280.01 | 0.780 | rackett-standard-density-r1 | yes |
| crude_pc11 | 3382.36 | 4225.71 | 0.800 | rackett-standard-density-r1 | yes |
| crude_pc12 | 4766.98 | 5484.34 | 0.869 | rackett-standard-density-r1 | yes |
| cubic_water | 55.95 | 74.96 | 0.746 | iapws-95-critical-r1 | no |
| fluid_nitrogen | 89.41 | 94.98 | 0.941 | coolprop-8.0.0-critical-r1 | no |
| tjl19_ethane | 145.50 | 160.18 | 0.908 | chemsep-8.3-vc-r1 | no |
| tjl19_isobutane | 262.70 | 286.39 | 0.917 | chemsep-8.3-vc-r1 | no |
| tjl19_isopentane | 308.30 | 348.05 | 0.886 | chemsep-8.3-vc-r1 | no |
| tjl19_n_butane | 255.00 | 286.25 | 0.891 | chemsep-8.3-vc-r1 | no |
| tjl19_n_pentane | 311.00 | 356.25 | 0.873 | chemsep-8.3-vc-r1 | no |
| tjl19_propane | 200.00 | 222.53 | 0.899 | chemsep-8.3-vc-r1 | no |
| tjl20_methane | 98.63 | 105.91 | 0.931 | coolprop-8.0.0-critical-r1 | no |

## 3. The dense-fluid residual viscosity (O5 A)

`MixtureViscosity.jossiStielThodos(rho_r, Tc, Pc, M)` uses the Lohrenz-Bray-Clark statement:

- `xi = Tc^(1/6) M^(-1/2) Pc^(-2/3)` (K, g/mol, atm);
- `f = 0.1023 + 0.023364 r + 0.058533 r^2 - 0.040758 r^3 + 0.0093324 r^4`;
- residual = `(f^4 - f(0)^4) / xi` cP.

`residual` applies it on mole-fraction pseudo-critical constants, with `rho_r = rho x sum(x_i Vc_i)` and each dataset's
critical volume. The water slot takes its dataset where the package names water; otherwise it takes the water model's
constants with the cubic Vc.

Zero-density difference: the published form subtracts 1e-4, but f(0)^4 = 0.1023^4 = 1.0952e-4. The published form
therefore leaves 9.52e-6 / xi cP at zero density. For CO2 (xi = 0.0224) that is 0.42 µPa s, 3 % of its 15 µPa s dilute
viscosity at 300 K. With `f(0)^4` the residual is exactly zero at zero density, so `dense` is continuous with `vapor`.

Measured with the declared bounds:

- `MixtureViscosityTest.theDenseResidualIsZeroAtZeroDensityIncreasingAndWithinItsDeclaredError`: 60 states, declared 16 %,
  worst -15.92 % (n-propane, 388.38 K, 30 MPa).
- `G3F11SupercriticalRegionTest` M4 gains the column "dense (dataset v_c) / ref", declared at 16 %. JST on the reference
  rho_c is -15.9 / +11.1 % and on the cubic Vc +28.0 %. These are held at 16 % and 29 % as before, and now use the
  production function.

## 4. Identities that move, and what they invalidate

| Identity | Moves for | Effect |
|---|---|---|
| The 9 record revisions (table 1) | the ranges | listed in each package identity that names them |
| `scientificRevision` of `icheme:refinery`, `icheme:tjl20_methane`, `icheme:tjl20_methane_nitrogen` | the record revisions | the column's caches and warm starts keyed on it drop (state); `V3IdentityTest` green |
| `fluidRevision` of every package with a member that has a critical volume (all three) | the new `;critical-volume=id@rev,...` segment | the fluid checkpoint's package records no longer match, so a world's islands restate from their facts on load (no fact is discarded; `FluidFactsCompatTest` 22/22) |
| `MixtureViscosity.revision` gains `:dense-residual-jst-v1` | the new term | part of `FluidThermodynamics.thermodynamicRevision`; the same restate |
| Process-simulator sheets holding a compound whose revision moved | `RevisionNotice.dropsResults` | the sheet's results drop and the sheet stays (`SheetCompoundsTest` green) |

The column's `physicsContent` does not include `criticalVolume`, so a critical-volume edit moves only `fluidRevision`
(`MaterialCatalogTest.aCriticalVolumeSubRecordParsesFallsBackToTheCubicsAndMovesOnlyTheFluidIdentity`). No saved sheet, no
saved design and no facts row is refused. `FORMATS.md` has no row for the material data, so no row changes.

`examples/material-override/.../tjl20_methane.json` mirrors the bundled record, with only its density, source and revision
edited (`MaterialPresetsTest`). It was regenerated from the r4 record and its revision is now
`tjl20-methane-spine-r4-example-density`. This file is outside the brief's listed scope; the test requires the change.

### The identity bound (S3 fix, found by S2b's run)

The new `;critical-volume=` segment made the fluid identity longer. `ColumnSolveTest.aNetworkPackageAbove64CompoundsSolvesOnItsFeedIslandsList`
builds a wide package (the bundled one plus clones, more than 64 compounds) whose identity is 19,182 characters, above the
old `MaterialCatalog.MAX_IDENTITY` of 16,384, and failed at load. S3's own run did not select `ColumnSolveTest`.

| Package | Compounds | Fluid identity before S3 | After S3 | Critical-volume segment |
|---|---|---|---|---|
| `icheme:refinery` | 46 | 8,024 | 10,086 | 2,062 |
| `icheme:tjl20_methane_nitrogen` | 20 | 3,301 | 4,207 | 906 |
| `icheme:tjl20_methane` | 19 | 3,131 | 3,988 | 857 |

"Before" is the identity now minus its segment; the record revisions S3 bumped changed a few characters each, not counted.

Why 21,000 (the main thread's option A1): the bound that matters is the fluid checkpoint's `PropertyRevision`, an NBT
string of at most 65,535 UTF-8 bytes. It carries `FluidThermodynamics.thermodynamicRevision`, which repeats the fluid
identity about three times: on `icheme:refinery` the hydrocarbon model's revision is 19,888 characters, the viscosity's
10,339, the whole 30,244 on an identity of 10,086. 21,000 x 3 plus the tags stays under 65,535, and admits a network package
of about 95 compounds at about 220 characters each (about 74 under the old bound once the segment joined).

The fix, commit "S3 fix: identity bound":

- `MaterialCatalog.MAX_IDENTITY = 21000`, its javadoc giving the NBT reason and these numbers.
- `FluidThermodynamics` refuses a model whose `thermodynamicRevision` is above
  `MAX_THERMODYNAMIC_REVISION_BYTES = 65535` UTF-8 bytes, at the model, not at the first save. `ColumnSolveTest`'s wide
  package asserts that its revision fits.
- `IslandCertificate.Signature`'s javadoc said the property revision carries the identity twice; it says about three times,
  with the figures. `MAXIMUM_PROPERTY_REVISION` is kept.

Two open owner items:

1. The thermodynamic revision states the identity about three times (the hydrocarbon model's revision twice, the
   viscosity's once). Stated once, the NBT wall would lift to about 290 compounds.
2. The process simulator's menu opening data (`ProcessSimulatorMenu.Opening.write`, sent with `writeUtf` through
   `openMenu`) carries the package tokens: 18,281 characters today, about 14,500 before S3. Its limit is near 32,600 bytes,
   not confirmed in the NeoForge source. At the identity's rate of about 220 characters per added compound it bites at a
   network package of about 110 compounds, before the NBT wall. No serverbound packet carries a whole identity (`SheetPart` sends chunks).

### The bundled simulator examples (a follow-up the main thread asked for)

`FlowsheetExamplesTest.eachExampleIsTheCurrentFormatOnTheCurrentPackage` requires each example's `basis.revision` to equal
`RevisionNotice.data(sheet, catalog)`: the `fluidRevision` of the network package restricted to the sheet's compounds.
S3 moved that identity in all three examples:

| Example | Why its identity moved |
|---|---|
| `crude_unit.json`, `small_crude_unit.json` | tjl20_methane r3 to r4; tjl19_ethane and tjl19_propane r1 to r2; the new `;critical-volume=` segment |
| `ethanol_rectifier.json` | the `;critical-volume=icheme:chemsep_ethanol@chemsep-8.3-vc-r1` segment only; ethanol's own record did not move |

All three were re-recorded by the method of commit 1bd0b4236: read, stamp, write. The probe is
`tmp/supercritical-s3/probe/ReStampExample.java` (scratch): `FlowsheetFile.read`, then `RevisionNotice.stamped` on
`MaterialCatalog.bundled()`, then `FlowsheetFile.write`. Each file's line ends were kept.

- In each file only the `basis.revision` line changed. A JSON comparison of every other value is equal.
- `small_crude_unit.json` was written by another route, so the writer also replaces its `=` escapes with `=` (the
  same string) and its final newline.
- No example stores results; the keys are format, basis, units, streams and settings. No result value could move.

Test fixtures that state an old identity:

| File | Loaded by | Must it state the current identity? |
|---|---|---|
| `src/test/resources/column/v3/tjl-reference-resources.json` | `V3TjlReferenceCatalog` (`RegroupedCrudeTest`, `TjlReferenceExtension`: `V3TraceFloorSupportTest`, `V3WaterPhaseQualificationTest`) | No. It is the frozen independent oracle with its own `test:tjl_*` records, which keep their revisions on purpose |
| `src/test/resources/science/column/v3/neural/tjl20-wet-boundary.json` | `V3WaterPhaseQualificationTest` | No. The test reads only its `seed` and `input` and takes the identity from the running catalog. The stored string names the oracle's `test:tjl_*` records |
| `src/test/resources/materials/tia-juana/flowsheet/crude_unit.json` | `SheetFixture.tiaJuanaSample` (`UtilityRegulationTest`) | No. No test compares its basis revision; it is an input sheet that is run |

Nothing else under `src/main/resources` or `src/test/resources` holds an old token of the nine records or a fluid identity
without `;critical-volume=`. The scan left out `materials/properties/` and the compat corpus; the corpus states old
identities on purpose and is not edited.

## 5. Tests

`./gradlew test --tests <24 classes> columnGates --tests "*V3IdentityTest" --max-workers 4`: 171 tests, 1 failure
(`MaterialPresetsTest`, the example above). After the example was regenerated, `--tests "*MaterialPresetsTest"`: 6/6.
The 24 classes are MaterialCatalogTest, MixtureViscosityTest, ViscosityTest, G3F11SupercriticalRegionTest,
G3F9DataRangeBoundaryTest, RangeContinuationGateTest, DataRangeContinuationTest, FluidDataRangeIslandTest,
FluidPropertyCoverageTest, SheetCompoundsTest, FluidFactsCompatTest, ThermoIdentityTest, FluidNitrogenCryogenicTest,
DirectLiquidContinuityTest, PilotCryogenicCatalogTest, RefineryCarbonDioxideCatalogTest, RefineryImportTest,
RestrictedPackagePhysicsTest, MaterialFluidDataTest, MaterialPresetsTest, FluidPropertyReloadGuardTest,
AqueousSolventDataTest, NetworkPhaseEngineTest and SimulatorRunnerTest.

After the examples were re-recorded, two more targeted runs covered every class that reads the examples or the fixtures
above and had not run since S3's changes:

- `test --tests FlowsheetExamplesTest --tests UtilityRegulationTest columnGates --tests V3WaterPhaseQualificationTest
  --tests V3TraceFloorSupportTest --tests RegroupedCrudeTest`: 16 tests, green. FlowsheetExamplesTest ran 6, the three
  column classes 3, 2 and 5. UtilityRegulationTest is a slow gate, so `test` skipped it.
- `test --tests` DoorUnitsTest, DrawCallTest, OpenDeleteTest, SheetConditionTest, SheetGesturesTest, SimulatorTourTest,
  SizingPageTest and FlowsheetAqueousColumnTest: 69 tests, green.
- `holdGates --tests` CrudeUnitSampleTest (its five nested groups), UtilityRegulationTest, SmallCrudeUnitTest and
  SmallUnitRecordingTest: 57 tests, green. These are the sample gates that the re-record message names.

Test edits:

- `SheetCompoundsTest` reads nitrogen's revision from the record instead of a hard-coded r4.
- `FluidNitrogenCryogenicTest`: the envelope maximum is 30 MPa.
- `MaterialCatalogTest`: the identity test rebuilds the new segment.
- Found by S2b's run and fixed with the identity bound: `FluidPacketCodecTest` and `FluidDeviceSpecDomainTest` stated the
  old 10 MPa envelope maximum. The codec test's out-of-envelope pressure is now 32 MPa (was 12 MPa) and its message names
  3.0E7; the domain test's message names 3.0E7 (was 1.0E7).

After the identity bound: `test --tests` ColumnSolveTest, MaterialCatalogTest, FluidThermodynamicsTest,
IslandCertificateTest, FluidPacketCodecTest and FluidDeviceSpecDomainTest, `--max-workers 4`: 83 tests, green
(11, 15, 4, 23, 27, 3).

Not run: the whole suite, the lane, the campaign and `equipmentGates`. The fuzz campaigns do not cover the new 10-30 MPa
window (limits review, last section).

## 6. Not done

- The crude cuts keep their 2 MPa ranges: O4 gives them no surrogate evidence, and their constants are estimates.
- The network does not call `dense`; S2 wires the callers. Until then the dense term changes no result.
- Not measured: ethane's liquid at 293.15-305.3 K, any liquid between Tr 0.95 and Tc, and mixtures at pressure.
