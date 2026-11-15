# Material catalog: records, packages, identities and reload

Up: [code guide](README.md)

## Read this when

- You add a compound, a crude assay, a method, a package or a preset, or a datapack override fails to load.
- You change a property record (PR78 constants, ideal-gas Cp, viscosity, the liquid-mixture term, the water model), a
  reference spine, a crystal record, a group-interaction matrix or an E-PPR78 `rule`, a `volume_translation`, or the
  water's `ice`.
- You edit a record and need to know which revision to bump, and which identities move with it: column results,
  neural seeds, fluid worlds.
- A pair of the network package has an unexpected value (`DerivedPairs`), or a neural model does not bind
  (`TrainedPhysics`).

The PR78 arithmetic and the water formulas are in [thermo-kernel.md](thermo-kernel.md); phase answers in
[equilibrium-engine.md](equilibrium-engine.md); the network's use of the property models in
[fluid-science.md](fluid-science.md).

## What it does

A *material record* is authored JSON under `data/<ns>/materials/<kind>/`: identity and label (`components`), one
property dataset per species (`properties`, with optional `freezing`, `thermochemistry`, `flammability` and `combustion`
sub-records), pair libraries (`interactions`, with an optional E-PPR78 `rule`), group-interaction tables
(`group_interactions`), reference spines (`spine`), pure crystals (`crystals`), a water model with its ice (`water`), a
*method* (`methods`: the model, the ideal-gas source, the pair libraries in priority order, the missing-pair policy, the
water model, the column's pressure range, the liquid-mixture term), and a *package*: a method plus an ordered compound
list, each compound on its default dataset unless the package names another, and the crystals it admits. Assays are
compositions by compound; presets give operating starting points; `transport`, `solids`, `fuels` and `networks` complete
the 15 kinds. `MaterialReloadListener` reads the winning resource set, `MaterialCatalog.parse` validates it as one
transaction into an immutable SI catalog whose identities are composed from each record's declared revision (nothing is
hashed), and `MaterialRuntime` publishes it atomically and pins it per calculation. A package may be viewed on a subset
of its compounds (`restrict`), and the fluid network takes its package with water as a compound of the cubic
(`withWater`), every pair derived from the compounds' own data (`DerivedPairs`). Past its data range every material
model continues, value and slope continuous.

## Class map

**`science/material/`** ([source](../../../src/main/java/com/wormzjl/icheme/science/material/)). Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| [`MaterialCatalog`](../../../src/main/java/com/wormzjl/icheme/science/material/MaterialCatalog.java) | Parser, validator and immutable store of the 15 kinds. Records `Property`, `Pr78` (with `MathiasCopeman`), `Validity`, `Freezing` (`CRYSTAL_ANCHOR_TOLERANCE`), `Thermochemistry` (`ELEMENTS`), `Flammability`, `Combustion`, `Water` (with `Ice`), `Assay`, `Package`, `NrtlPair`, `GroupCounts`, `Homomorph`, `FluidAppearance`, nested `FluidData`; private `Method`, `Entry`, `Records`, `Definition`, `build`. Views and lenses: `restrict`, `withWater` (`CUBIC_WATER`), `inferenceView`, `lens`. Identities: `fluidRevision`, `spineRevision`, `propertyRevision` (`MAX_IDENTITY`). Data reads: `requireSpine`, `volumeTranslation`, `viscosity`, `liquidMixture`, `fluidValidity`, `groupCounts`, `henryInWater`, `homomorph`, `modifiedUnifacTables`, `heatingValues`, `formulas`, `conservedComponents`; `bundled()` | 1445 |
| [`DerivedPairs`](../../../src/main/java/com/wormzjl/icheme/science/material/DerivedPairs.java) | The PR78 kernel of a compound list with every pair derived from the compounds' data: one source per unordered pair, the first that applies (`LIBRARY`, `HYDROGEN`, `HENRY`, `ACTIVITY`, then the others of its class comment), the Huron-Vidal excess and the Mathias-Copeman coefficients; `derive`, `Result` (with `identity`); the activity fits on a parallel first pass | 822 |
| [`ModifiedUnifac`](../../../src/main/java/com/wormzjl/icheme/science/material/ModifiedUnifac.java) | The modified UNIFAC (Dortmund) table and the activity coefficients it gives from `group_counts`; its water-hydrocarbon block | 337 |
| [`HenryConstant`](../../../src/main/java/com/wormzjl/icheme/science/material/HenryConstant.java) | A gas's `henry_in_water` sub-record, the IAPWS G7-04 form on the water's Wagner-Pruss line | 55 |
| [`WaterRecords`](../../../src/main/java/com/wormzjl/icheme/science/material/WaterRecords.java) | `constants(MaterialCatalog.Water)`: the water record as the water module's `WaterConstants` (no cache) | 24 |
| [`ReferenceSpine`](../../../src/main/java/com/wormzjl/icheme/science/material/ReferenceSpine.java) | Kind `spine`, an `IdealGasFunction`: 1 to 16 contiguous segments (`helmholtz_ideal_terms`, `nasa9`, `nasa7`) covering 298.15 K, h(298.15 K) the formation enthalpy and s(298.15 K, 1e5 Pa) the standard entropy chained across joins; the E-PPR78 `groups` and the `coverage`; `GAS_CONSTANT`, `CEA_GAS_CONSTANT`, `DEFAULT_MAXIMUM_CP_STEP_FRACTION` | 532 |
| [`CoefficientIdealGas`](../../../src/main/java/com/wormzjl/icheme/science/material/CoefficientIdealGas.java) | A `shifted_polynomial_5` fit as an `IdealGasFunction` on the network's sensible datum; Cp held past the fit's range | 106 |
| [`CrystalReference`](../../../src/main/java/com/wormzjl/icheme/science/material/CrystalReference.java) | Kind `crystals`: model `Kind.SEGMENTS` or `Kind.JAEGER_SPAN_2012`, anchored at the triple point to the fluid family's liquid; `usable()`, `missing()`; Cp, dv/dT, dv/dP held past its ranges | 540 |
| [`JaegerSpanGibbsFunction`](../../../src/main/java/com/wormzjl/icheme/science/material/JaegerSpanGibbsFunction.java) | The CO2-I Gibbs function (Jäger and Span 2012); g0, g1 from the anchor (`integrationConstants`) | 259 |
| [`GroupInteractionMatrix`](../../../src/main/java/com/wormzjl/icheme/science/material/GroupInteractionMatrix.java) | Kind `group_interactions`: symmetric A_kl, B_kl in MPa (`UNITS`), at most 64 groups | 119 |
| [`GroupContributionInteractions`](../../../src/main/java/com/wormzjl/icheme/science/material/GroupContributionInteractions.java) | The E-PPR78 pairs of one package, resolved at load (`resolve`, `RULE_EPPR78`, `REFERENCE_TEMPERATURE`); a pair with an explicit constant is kept out | 233 |
| [`Eppr78Groups`](../../../src/main/java/com/wormzjl/icheme/science/material/Eppr78Groups.java) | The 40 groups of scheme `SCHEME` and their sources | 116 |
| [`VolumeTranslation`](../../../src/main/java/com/wormzjl/icheme/science/material/VolumeTranslation.java) | A property's `volume_translation` (`TYPE`): the saturated-liquid anchor at a reduced temperature; `shift` = v_ref − v_PR,L on a one-component kernel | 72 |
| [`CoefficientTranslations`](../../../src/main/java/com/wormzjl/icheme/science/material/CoefficientTranslations.java) | The volume translations of a package without a spine (`of`) and `pureLiquidVolume` on the kernel's `Root.LIQUID` root (`pureKernel`) | 72 |
| [`SpinePackageData`](../../../src/main/java/com/wormzjl/icheme/science/material/SpinePackageData.java) | A spine package's components, pairs, translations, ideal gas and formation datum as numbers (`formationEnthalpies`, `formationRevision`); read by the network's `HydrocarbonModel` and the engine's `PackageBinding` | 149 |
| [`MaterialCoverage`](../../../src/main/java/com/wormzjl/icheme/science/material/MaterialCoverage.java) | A grade (`qualified`, `estimated_declared_error`, `research_only`, `unavailable`) and its evidence; required on spine and crystal records | 41 |
| [`CrystalPhasesUnavailable`](../../../src/main/java/com/wormzjl/icheme/science/material/CrystalPhasesUnavailable.java), [`IdealGasFitUnavailable`](../../../src/main/java/com/wormzjl/icheme/science/material/IdealGasFitUnavailable.java) | Typed refusals: a consumer without crystal equations given a package with crystals; a consumer of the fits given a spine package | 37, 29 |
| [`TrainedPhysics`](../../../src/main/java/com/wormzjl/icheme/science/material/TrainedPhysics.java) | The phase physics a neural model was trained on, as values (`read`); `compare`, `matches` on the request's active compounds (`RELATIVE_TOLERANCE`, `GRID_INTERVALS`, `PAIR_TOLERANCE`); nested `Water` | 269 |
| [`JsonFields`](../../../src/main/java/com/wormzjl/icheme/science/material/JsonFields.java) | The one reader of typed JSON fields (`MAX_TEXT`); `revision`, `token`; every message starts with the field label | 93 |
| [`MaterialAxis`](../../../src/main/java/com/wormzjl/icheme/science/material/MaterialAxis.java) | Ordered identity axis, `MAX_CONSERVED_COMPONENTS`; `project` refuses to drop nonzero material | 40 |
| [`MaterialRuntime`](../../../src/main/java/com/wormzjl/icheme/science/material/MaterialRuntime.java) | Volatile published catalog and a ThreadLocal pinned (catalog, package) context: `publish`, `active`, `current`, `with`, `pinned`, `water`, `reset` | 31 |
| [`MaterialPresets`](../../../src/main/java/com/wormzjl/icheme/science/material/MaterialPresets.java) | Column and fluid presets, the `networks` record, assay to mole fractions (`moleFractions`); a column preset as plain numbers (`Specifications`, `Draw`, `Steam`, `Pumparound`); `MAX_PRESETS` | 116 |
| [`MaterialName`](../../../src/main/java/com/wormzjl/icheme/science/material/MaterialName.java) | Label descriptor: id, translation key, fallback, kind, NBP cut bounds | 44 |
| [`ViscosityCorrelation`](../../../src/main/java/com/wormzjl/icheme/science/material/ViscosityCorrelation.java) | Pure-phase mu(T) (`ANDRADE`, `SUTHERLAND`, `POWER_SUM`, `LOG_TABLE`), continued past its range, capped at `MAXIMUM_VISCOSITY` | 127 |
| [`LiquidMixtureCorrection`](../../../src/main/java/com/wormzjl/icheme/science/material/LiquidMixtureCorrection.java) | A method's `cut_pair_v1` term (`Rule`): delta = G((Σ_C x)² − Σ_C x²) added to Σ x ln mu | 74 |
| [`SolidMaterialCatalog`](../../../src/main/java/com/wormzjl/icheme/science/material/SolidMaterialCatalog.java) | `solids` records, apart from the fluid axis: `SolidMaterial` (U = cp(T − 298.15 K)) and `Crystal` (a crystal formed from the fluid, u = c(T − 298.15) + u0) | 67 |
| [`SolidFuelCatalog`](../../../src/main/java/com/wormzjl/icheme/science/material/SolidFuelCatalog.java) | Kind `fuels`: an item's or tag's fuel, its heat in a firebox = mass × lower heating value, mass = bulk density over the 9 items of a cubic metre; `energy` for an item without a record | 104 |

**`material/`** (the Minecraft adapter)

| Class | Role | Lines |
|---|---|---|
| [`MaterialReloadListener`](../../../src/main/java/com/wormzjl/icheme/material/MaterialReloadListener.java) | prepare = read every `materials/**.json` + `parse` + the column's preset check (`V3ColumnPresets.validate`); apply = `MaterialRuntime.publish`; registered in `IChemE` | |
| [`ConversionReloadListener`](../../../src/main/java/com/wormzjl/icheme/material/ConversionReloadListener.java) | Reads `data/<ns>/icheme/fluid_conversion/**/*.json` after the materials, on the catalog they published; a rule whose fluid's mod is absent is skipped, a refused rule logged alone. The rules: [fluid-science.md](fluid-science.md) | |

## Main flow

1. **Datapack JSON.** Minecraft's resource manager picks the highest-priority file at each path; a replacement is a
   whole record. Bundled files are in [`src/main/resources/data/icheme/materials/`](../../../src/main/resources/data/icheme/materials/);
   [`materials-index.json`](../../../src/main/resources/materials-index.json) lists all 130 and serves `bundled()` only.
2. **`MaterialReloadListener.prepare`** keys every file as `data/<ns>/<path>`, calls `MaterialCatalog.parse`, then
   `V3ColumnPresets.validate` (each column preset's input and the column's problem check inside
   `MaterialRuntime.with`, a refusal named `<file>: <reason>`). A throw aborts the reload before `apply`. The parser
   imports no solver type.
3. **`MaterialCatalog.parse`** groups records by kind, refuses duplicate ids per kind and an unknown kind (`bases` with a
   hint), then validates in order: components (default datasets, catalog-wide aliases), spines, crystals,
   group-interaction tables, properties with their sub-records, water, interactions, methods, transport, assays,
   packages (compound lists after `extends`, then `build`: datasets, pairs, spines, crystals, assays, envelope,
   identities), presets, solids, fuels and the network record. It returns only a fully valid catalog.
4. **`apply`** calls `MaterialRuntime.publish` (a volatile write). `IChemE.onServerStopped` calls `reset()`.
5. **Consumers.**
   - Column: `V3CatalogPropertyPackage` copies a `Package` into its components; it refuses a non-`pr78` package, a
     package with crystals (`CrystalPhasesUnavailable`) and a spine package (`IdealGasFitUnavailable`)
     ([column-v3.md](column-v3.md)). Its kernel is derived like the network's.
   - Network: `FluidThermodynamics.on` runs each island on the network package restricted to its compounds
     (`restrict`), with water in the cubic (`withWater`) and pairs from `DerivedPairs`; a spine package takes
     `SpinePackageData` ([fluid-science.md](fluid-science.md)).
   - Engine: `PackageBinding` is its one reader of the catalog ([equilibrium-engine.md](equilibrium-engine.md)).
   - Process simulator: a sheet runs on the network package restricted to its feeds' compounds (`SheetCompounds`) and
     shows `RevisionNotice` when the data differ from the identity its file holds ([process-simulator.md](process-simulator.md)).
   - Server: `ProcessSolveServices` pins the catalog per request; `FluidWorldAuthority.refreshProperties` compares the
     active catalog to the world's and holds islands on a change.

## Contracts

**Validation rules across records** (all in `parse` unless named):

- A package names `method`, `components` (an id, or `{id, properties, spine}`), optional `extends` (the parent's
  compounds first; cycles refused), `crystals` and `advisory_evidence`; a field of the method or a dataset is refused;
  1 to 99 distinct compounds. A compound without a dataset named by the package or as its default is refused.
- The column range is the intersection of the datasets' ranges at the method's pressures (a view keeps its parent's);
  the fluid envelope is the hull of their `fluid_domain`s and the water's triple point.
- The method's pair libraries are read in order: the first that names a pair gives it. Under `missing_interactions:
  error` every pair must be given; at most one library carries a `rule`, and only a method with `ideal_gas: spine`
  takes one; a `rule` needs model `pr78` and type `eppr78`.
- Under a spine method each compound takes its spine record, with a molar mass within 1e-4 relative of the dataset's;
  `ideal_gas_cp` is absent under a spine method and required under a `fit` method (one ideal-gas source per species).
- Every assay is held by some package (one that has all its non-zero compounds); an assay's `package` field is refused.
- A `freezing` sub-record agrees with every crystal of its compound within `Freezing.CRYSTAL_ANCHOR_TOLERANCE`; a water
  `ice` names a usable crystal of `Water` anchored at the water's triple point; with a `networks` record, the network
  package's water model must carry `ice` (refused last, naming the water file and the field).
- Package `crystals` name package components, without duplicates. Crystal and solid ids differ.
- `solids` and `fuels` records read through `JsonFields`; an error names the file and the field.

**Identities, not hashes** (AGENTS.md "Saves and data formats"). Each record declares its `revision`, a token
(`JsonFields.revision`). An edit of a record bumps that record's revision; every identity that names the record moves
with it. Neither a package record's nor a method record's own `revision` is in any identity: the identity lists the
method's physics fields, the datasets and the libraries, so a package edit alone, a compound added to a package, or a
library added for other compounds moves no identity of a view without them. Assays and the `freezing`,
`thermochemistry`, `flammability` and `combustion` sub-records are in no identity.

| Identity | Composed of, in order | Used for |
|---|---|---|
| `Package.scientificRevision()` | `method=model:<m>,ideal-gas:<fit\|spine>,missing-interactions:<policy>,pressure-pascal:<min>..<max>[,liquid-mixture:<rev>];properties=<dataset>@<rev>,…;interactions=<library>@<rev>,… (libraries that give a pair among its compounds)[;group-interactions=<table>@<rev>];water=<id>@<rev>` | the column's `datasetRevision`, the simulator's tokens and warm starts, the sheet file's data identity |
| `fluidRevision(pkg)` | the scientific revision, `;viscosity=<record>/<phase>@<rev>,…`, `;ice=<crystal>@<rev>`, `;fluid-data=` + `FluidData.revision(held)` (each transport record with an entry for a held compound or the water, `type@revision`, sorted) | the fluid world basis, the network's model revision, the viscosity cache, the package part of `ThermoIdentity` |
| `spineRevision(pkg)` | `spine:`, each spine `<id>@<rev>` in component order, each crystal `<id>@<rev>`; "" without either | the engine's `ThermoIdentity`, `SpinePackageData.formationRevision` |

- A view's identities name only its compounds' records, datasets in axis order; reordering a view's compounds moves
  them. Every identity is at most `MAX_IDENTITY` characters; `parse` refuses a package whose fluid identity is longer.
  A viscosity-only edit moves `fluidRevision` and leaves the column's identity alone.
- `physicsContent(pkg, axis)` (package-private) is canonical JSON of the model, the axis's constants, the k sub-matrix,
  the NRTL pairs, the water's numbers and the resolved E-PPR78 terms. Tests compare by it; no production code does.
- **The neural check binds by value.** `TrainedPhysics.matches` on the request's view of its active compounds: scalars
  and functions of T within `RELATIVE_TOLERANCE` on `GRID_INTERVALS` + 1 points, pairs within `PAIR_TOLERANCE`; no id,
  revision or package id takes part. `inferenceView(pkg, active, trained)` is the lens of one inference: the request's
  records for its active compounds, pairs between two active compounds from the request and the others from the
  trained values; it keeps the request's column range, water and identity and is never published. It is empty when the
  values differ, when the view resolves a pair by an E-PPR78 rule, or when a compound has no PR78 record with a fit; a
  view with a crystal is refused.
- **One transaction.** `parse` sees the whole winning set and returns a complete catalog or throws an
  `IllegalArgumentException` prefixed with the resource path; nothing is published on failure, so the last valid
  catalog stays active. `MaterialRuntime.with(catalog, packageId, action)` pins a context and restores the enclosing
  one in `finally`; `active()` returns `bundled()` before the first publish.
- **Water in the cubic.** `withWater(packageId)` gives the network its package with Water on `CUBIC_WATER` after the
  package's compounds and the water's ice among its crystals; no package record names it, so the column, the presets and
  the package's own identity are unchanged. It returns the package itself where it names Water, where it is a lens, or
  where its method takes the ideal gas from spines (there is no water spine: such a network stays dry). `lens` is true
  for a package without a definition, which keeps its own matrix.
- **Derived pairs.** One source per pair, the first that applies: `LIBRARY` (a classical pair of the package's
  libraries), `HYDROGEN` (k_ij = 0), `HENRY` (water with a gas that carries `henry_in_water`: a classical k_ij at 298.15
  and 373.15 K that gives the Henry constant), `ACTIVITY` (two liquids with modified UNIFAC counts, one a solvent:
  NRTL-form alpha and two energies fitted to the group model at 298.15 and 373.15 K), then the later sources of the
  class comment. `Result.identity` enters the derived contract's revision. A pseudo-cut takes paraffinic group counts
  only in a scheme whose table the catalog holds. `derive` fits the activity pairs the cache lacks on a parallel first
  pass, one task per key, each with its own kernel and workspace, so the doubles do not depend on the thread; a fit that
  throws stays uncached and the pair loop throws it. The cost is paid when the whole-list model builds: world start,
  `/reload`, the client's first simulator open.

## Laws and constants

- Assay to mole fractions (`MaterialPresets.moleFractions`): mole as is; mass / M; standard liquid volume ×
  `volume_scale / amount_total` × rho / M; then normalised. `V3CatalogPropertyPackage.crudeFeed` has its own copy.
- Heating values: `Combustion` is the lower heating value (J/mol; products CO2, water vapour, SO2, N2) built offline by
  `tools/heating-values/`; `heatingValues(packageId)` in the network's conserved order (0: burns as an inert);
  `formulas` the atoms of `FORMULA_ELEMENTS`.
- Liquid mixture: `crude_pr78`'s G on `crude_pc01..12` (`LiquidMixtureCorrection.Rule`); a method without the term
  has none.

| Limit | Where |
|---|---|
| Compounds per package 1..99; axis at most `MaterialAxis.MAX_CONSERVED_COMPONENTS` with water; an island 64 and water (`IslandSets.MAXIMUM_COMPOUNDS`) | `MaterialCatalog.build` |
| Text fields non-blank, at most `JsonFields.MAX_TEXT` | `JsonFields` |
| A declared revision: `[A-Za-z0-9][A-Za-z0-9_.-]{0,63}` | `JsonFields.revision`, `token` |
| A composed identity: `MAX_IDENTITY` characters | `MaterialCatalog.parse` |
| Presets: `MaterialPresets.MAX_PRESETS` per family, one `networks` record; column preset stages 2..64 | `MaterialPresets.read` |
| Cp reference exactly 298.15 K; a fit's lower end in (record min, 298.15] K | `parse` |
| Spine segments 1..16 containing 298.15 K; a Cp step at a join at most `maximum_cp_step_fraction` | `ReferenceSpine` |
| Group table 1..64 groups, MPa | `GroupInteractionMatrix` |
| Volume anchor: Tr in (0, 1), T = Tr·Tc within 1e-9 relative | `VolumeTranslation.read` |
| Viscosity: power sum 1..16 terms, table 2..2048 nodes, cap `MAXIMUM_VISCOSITY` | `ViscosityCorrelation` |
| Solid reference 298.15 K | `SolidMaterialCatalog.SolidMaterial.REFERENCE_TEMPERATURE` |

**Bundled data** (all `icheme:`). Packages, all on the method `crude_pr78` (`crude-pr78-r3`): `tjl20_methane` (methane,
six light ends, the 12 cuts; the column presets' package), `tjl20_methane_nitrogen` (with Nitrogen) and the network
package `refinery` (`refinery-tier1-r4`: the 24 Tier 1 compounds, CarbonDioxide, Ethanol, the CO2-I crystal; 46
compounds, the world list with water 47). `FluidMaterialCatalog.NETWORK_PACKAGE` is `icheme:refinery`; the runtime
reads the network record (`networkPackage(catalog)`). The column refuses `refinery` itself (its crystal) and runs on
views without CO2. Record counts: components 47, properties 47 (`cubic_water` among them: water's record for the
cubic, critical constants, a Mathias-Copeman alpha, group counts), interactions 3, methods 1, group_interactions 2
(`eppr78_2022`, `modified_unifac_dortmund`), crystals 2 (`crystal_carbon_dioxide_i`, `crystal_ice_ih`), water 1
(`water-iapws95-shomate-r4`, with `datum_vaporization_enthalpy`), packages 3, assays 4 (the generated grades of
`tools/crude-grades/`), presets 9 (one column preset, `medium_crude`, and eight fluid ones), networks 1, transport 4,
solids 1, fuels 5. The game ships no spine, no E-PPR78 package; the kinds and their code stay. Field reference:
[MATERIALS.md](../../../MATERIALS.md) (this chapter and the code win where they differ).

**Test overlays** (outside `data/` on the test classpath): `src/test/resources/materials/tia-juana/`
(`TiaJuanaTestCatalog`, `TiaJuanaExtension`: the TJL20 assay, the column presets `tia_juana`, the literature case
`tjl19_dwsim`), `pilot-cryogenic/` (`PilotCryogenicTestCatalog`: method `pilot_eppr78` with `ideal_gas: spine` and
`missing_interactions: error`, four spines, the E-PPR78 table, CO2-I) and `PilotAqueousTestCatalog` (`pilot_aqueous`).

## Formats

The catalog saves nothing itself. Its identities are what saved state is keyed on: the world basis, the column's
staleness, the sheet file. Numbers: [FORMATS.md](../FORMATS.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| A compound or dataset | `materials/components`, `properties`, a package's `components`, `materials-index.json`; `tools/compound-import/` | `MaterialCatalogTest`, `MaterialPresetsTest`, `MaterialAuthoringTest`, `RefineryImportTest`, `RestrictedPackagePhysicsTest`, `EthanolMainDataTest` |
| A crude feed | `materials/assays`, `tools/crude-grades/` | `MaterialPresetsTest`, `RegroupedCrudeTest` |
| A method | `materials/methods`, `MaterialCatalog.Method` | `MaterialCatalogTest`, `LiquidMixtureCorrectionTest` |
| Sub-records | `MaterialCatalog.readFreezing`, `readThermochemistry`, `readFlammability`, `readCombustion`; tests read them through `SubRecords` | `MaterialCatalogTest` (`freezingSubRecordsAreInNoIdentity`, `aFreezingSubRecordIsValidatedAndAgreesWithItsCrystalAnchor`), `MaterialThermochemistryTest` |
| A revision or an identity | `MaterialCatalog` (`build`, `Method.identity`, `physicsContent`), `JsonFields.revision`, `FluidData.revision` | `MaterialCatalogTest` (`everyPackageIdentityListsTheDeclaredRevisionOfEachRecordItNames`, `aViscosityRevisionBumpMovesOnlyTheFluidIdentity`, `aPackageRecordEditAloneMovesNoIdentity`, `aDatasetRevisionBumpMovesEveryViewThatHoldsItsCompoundAndNoOther`), `PilotCryogenicCatalogTest`, `MaterialFluidDataTest`, `FluidFactsCompatTest` |
| A validation rule | `parse`, a record class's `read`, `JsonFields` | `MaterialCatalogTest.invalidReloadCannotPublishPartialCatalog`, `MaterialFluidDataTest` (`aNetworkWaterModelWithoutIceFailsLoadingWithItsFileAndField`, `aMalformedSolidRecordFailsWithItsResourceAndField`) |
| Derived pairs, the water's records | `DerivedPairs`, `ModifiedUnifac`, `HenryConstant`, `WaterRecords` | `DerivedPairsTest` (`theParallelFitsEqualEachPairFittedAlone`), `AqueousSolventDataTest`, `PengRobinsonKernelHuronVidalTest` |
| Spines, crystals, E-PPR78 | `ReferenceSpine`, `CrystalReference`, `JaegerSpanGibbsFunction`, `GroupContributionInteractions` | `ReferenceSpineTest`, `MethaneSpineTest`, `CrystalReferenceTest`, `JaegerSpanGibbsFunctionTest`, `GroupContributionInteractionsTest`, `PilotCryogenicCatalogTest`, `RefineryCarbonDioxideCatalogTest` |
| Volume calibration | `CoefficientTranslations`, `VolumeTranslation`, `SpinePackageData`, `transport/liquid_calibration.json` | `PilotVolumeAnchorTest`, `MaterialFluidDataTest`, `HydrocarbonModelTest` |
| A package one consumer cannot use | `CrystalPhasesUnavailable`, `IdealGasFitUnavailable` | `CrystalPackageConsumersTest`, `SpineNetworkPathTest` |
| The neural check | `TrainedPhysics`, `inferenceView` | `V3NeuralRegistryTest`, `V3BundledModelTest` |
| Viscosity, liquid mixing, appearance | `ViscosityCorrelation`, `LiquidMixtureCorrection`, `FluidAppearance` | `ViscosityTest`, `LiquidMixtureCorrectionTest`, `MixtureViscosityTest`, `FluidAppearanceTest` |
| Reload and publication | `MaterialReloadListener`, `MaterialRuntime` | `MaterialCatalogTest.runningContextKeepsBothPrAndWaterAcrossPublicationAndRestoresAfterFailure`; GameTest `MaterialReloadGameTests` (`materialReloadIsAtomic`, `columnPresetsLoadWithRevisionChecks`) |

Helpers: `MaterialTestBasis`, `Cdu17TestCatalog`, `SubRecords`, and the catalog extensions `Cdu17FixtureExtension`,
`TjlReferenceExtension`, `TiaJuanaExtension` on `CatalogScopedExtension`. `verifyMaterialIndex` (a build check before
`processResources`) fails a bundled file missing from `materials-index.json`.

## Traps

- **An edit without a revision bump moves nothing.** No identity reads a record's content, so a changed number under
  the same revision keeps column results current and restates no world. The neural check is the one content
  comparison: it reads values, so a changed number can unbind a model whatever its revision.
- A compound's default dataset is the one every package takes unless its entry names another: changing it moves every
  package and view that holds the compound.
- `restrict` keeps the package id: callers that cache by model key on the compound list (`FluidThermodynamics.on`,
  `FluidCheckpointCodec.PackageKey`).
- For an E-PPR78 pair, `Package.interactions()` holds 0.0; read `groupContributions()`.
- The `shifted_polynomial_5` polynomial is evaluated in four places: the column's `V3PropertyComponent`, the network's
  `TranslatedPengRobinson`, `CoefficientIdealGas` (engine and crystals) and `TrainedPhysics`. The fit's lower end
  (`idealGasMinimumTemperature`) is read by the network only.
- `fluid_domain` is outside the neural check; its property record's revision covers it.
- `MaterialRuntime.water()` without a pinned context reads the water of `icheme:tjl20_methane`.
- The sub-records have no accessor in `src/main` but `Combustion`: tests read the others through `SubRecords`.
- `missing_interactions: zero` means an absent pair is k = 0. Every hydrogen pair is zero by rule.
- Only a package's `crystals` list competes (`refinery`: CO2-I, anchored to the fit family's own CO2 liquid). Ice Ih
  comes into every network package through `Water.ice`.
- An NRTL package parses, but the column refuses it.
- Water's alpha function (`cubic_water`) leaves the cubic's saturation temperature up to 0.43 K off Wagner-Pruss near
  100 to 200 bar (an open data item).
- [`examples/material-override`](../../../examples/material-override/) is the bundled `tjl20_methane` record with only its
  density, `source` and `revision` changed (`MaterialPresetsTest` pins it); on a running world the reload guard holds
  the network (`HELD: property data changed`), as designed.
- `FluidAppearance` is in no identity, so editing it holds no world. The 12 crude colours are a precomputed palette
  (`tools/sight-glass-bubbles/palette.py`, main checkout), not an optical model.
- Ethanol's activity fits cost about four times water's, because a pair the group model gives one liquid also scans
  `SCAN_POINTS` points per trial (`DerivedPairs.Context.shape`, `oneLiquid`); a list without ethanol pays nothing.

**Snapshot:** `d6f721b94`, 2026-11-15.
