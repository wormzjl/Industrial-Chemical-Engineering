# Unused data review (O5 inventory, research R2)

Batch [2026-11-04-open-thermo-data](README.md), plan [OPEN_DATA_PLAN.md](OPEN_DATA_PLAN.md) O5. Date: 2026-11-04. Read-only inventory at `03e7b6138`; nothing removed. Code guide: no change.

## 1. Method and the "used" rule

- **Game loader.** `MaterialReloadListener` scans every `data/*/materials/**.json` (`listResources("materials")`). `MaterialCatalog.bundled()` reads the same files through `materials-index.json`, and `verifyMaterialIndex` fails the build if the index and the folder differ. Every file is therefore parsed and validated. A record is **used by the game** only when the network (`networks/default.json`: package `icheme:refinery`, default column preset `tia_juana`, five fluid presets) reaches it.
- **What the game reaches.**
  - `refinery` extends `tjl20_methane_nitrogen`, which extends `tjl20_methane`. Method `crude_pr78`: libraries `tjl20` and `dwsim_refinery_pr78`, water `icheme:water`, which names the crystal `ice_ih`.
  - The game reads only the **default** column preset (`ColumnTemplates`, `ProcessSimulatorScreen`). The other column presets are only validated at load.
  - The demo slurry preset (`FluidPresetCatalog`) reads `solids/demo_particle`.
- **Other readers.**
  - The client libraries `assets/icheme/{columnpreset,mixturepreset}/index.json` (`V3ClientPresets`), and `flowsheet_examples/index.json` (`FlowsheetExamples`).
  - The neural model, through `neural/registry.json` (`V3NeuralRegistry`).
- **Tests and tools.**
  - Test readers: grep of `src/test/java` by file name, stem, folder and record id. All 159 test resource files have a reader (map in `tmp/unused-data/test-resources.txt`).
  - Tool readers: grep of `tools/`. Value matches: `git grep` of distinctive assay numbers.
- **Saves.**
  - A fluid preset sets its composition into the device (`FluidDeviceScreen` copies `moleFractions`). No preset id is written to `FluidFacts` or `FluidDeviceSpec` (grep). **Uncertain**: only these two classes were searched.
  - Saved column points name package and assay ids as plain names, and a missing one still starts (`ColumnSolveTest.aSavedPointOnARemovedPackageStarts`).
  - Simulator sheets (`crude_unit.json` names `icheme:tia_juana_light_methane`) are a no-compatibility format.
  - So removing an assay needs no alias. The fluid preset aliases planned in O3 are cheap and harmless, but they may not be needed.
- Rules (AGENTS.md):
  - **R-main**: `src/main` holds only what the game reaches.
  - **R-test**: data that only tests read goes to `src/test`.
  - **R-res**: large or raw outputs go to `research/`.
  - **R-XOM**: ExxonMobil data leaves the tree (DATA_LICENSE_REVIEW 3.1).
  - **R-fact**: a fact named by saves needs an alias.

## 2. `src/main/resources/data/icheme/materials/` (132 files)

| Path or record | What it is | Read by | Now / after O3 | Action | Rule |
|---|---|---|---|---|---|
| `assays/wti_light_export`, `assays/cold_lake_blend` | ExxonMobil assays (yields, metadata, PDF URL and SHA-256) | game (fluid presets 3, 4); tests: FluidPropertyCoverage, FluidThermodynamics, PumpRateWarmStart, FluidAppearance, RegroupedCrude, FluidDeviceSpecDomain | used / replaced | delete at O3 | R-XOM |
| `assays/upper_zakum`, `bonga`, `dalia` | ExxonMobil assays | tests only: FluidPropertyCoverage, RegroupedCrude (Dalia), MaterialCatalog | unused / unused | delete now (tests rewritten in the same commit) | R-XOM |
| `presets/fluid_3`, `fluid_4` | WTI and Cold Lake fluid presets | game | used / replaced | delete at O3; alias optional (section 1) | R-XOM, R-fact |
| `assays/tjl20` (`tia_juana_light_methane`) | Tia Juana, Ledezma-Martinez 2019 (Watkins 1979) | game (fluid_2, `tia_juana` preset, example sheet); about 40 test classes | used / test-only | at O3, move to a test overlay | R-test |
| `assays/tjl19` (`tia_juana_light`), `packages/tjl19` (`tjl19_dwsim`), `presets/column_literature` (hidden) | literature column case | tests only: V3TestInputs, V3 column tests, RegroupedCrude | test-only / test-only | move to `src/test` (overlay) | R-test |
| `presets/column_tia_juana_nitrogen` | second visible column preset | tests only: ColumnSolve, V3ClientPresets, V3ColumnFeedAndDrawLimits | test-only / test-only | move to `src/test`, or regenerate on a grade | R-test |
| `presets/column_tia_juana` | default column preset | game | used / changed | O3 moves it to a grade | - |
| `packages/refinery`, `tjl20`, `tjl20_nitrogen`; `methods/crude_pr78`; `interactions/tjl20`; `networks/default`; `presets/fluid_0`, `fluid_1` | the network package chain | game | used | keep | - |
| `methods/crude_pr78` `liquid_mixture` (`dalia-global-r1`) | 4 coefficients fitted to six Dalia bulk viscosities | game | used / used | replace at O4 | R-XOM |
| `properties/crude_pc01-12` `viscosity.liquid` (`crude-regrouped-r1-dalia`) and `liquid_mixture_descriptor` | Dalia Walther curves (608-node log table per cut); per-cut descriptors from the same fit | game | used / used | replace at O4; the rest of each record stays (E5) | R-XOM |
| `properties/crude_pc01-12` (other fields), `chemsep_*` (24), `tjl19_*` (6), `nitrogen`, `tjl20_methane` | cut and compound datasets | game | used | keep (O2 changes nitrogen and methane) | - |
| `interactions/dwsim_refinery_pr78`: 8 CarbonDioxide pairs | DECHEMA pairs for a compound that no crude_pr78 package holds | none (no test names them) | unused | owner choice: keep dormant, or delete with a revision bump (**uncertain**: whether the bump moves refinery identities) | R-main |
| `components/carbon_dioxide`, `properties/pilot_*` (4), `methods/pilot_eppr78`, `packages/pilot_cryogenic`, `interactions/pilot_cryogenic`, `group_interactions/eppr78_2022`, `spine/*` (4), `crystals/carbon_dioxide_i` | pilot cryogenic package (15 files) | tests only: PilotCryogenicTestCatalog plus about 35 classes (G3*, G4*, G5*, Crystal*, SpineNetworkPath, ...) | test-only / test-only | owner decision: move to `src/test/resources/materials/pilot-cryogenic/` (the overlay exists), or keep as a package ready to enable | R-main / R-test |
| `crystals/ice_ih`, `water/water` | IAPWS ice and water | game | used | keep (O2 changes the water Shomate) | - |
| `transport/liquid_calibration` | 8 NIST liquid points (light ends, N2) | game | used | keep (O2 re-derives) | - |
| `transport/chemsep_liquid_reference`, `transport/dissolved_viscosity` | ChemSep Tr = 0.7 points; DWSIM solute curves | game | used | keep | - |
| `solids/demo_particle` | fictional inert solid | game (demo slurry) | used | keep | - |
| other `components/*` (45) | names and appearance | game | used | keep | - |

## 3. Other `src/main` data

| Path | What it is | Read by | Now / after O3 | Action | Rule |
|---|---|---|---|---|---|
| `assets/icheme/mixturepreset/{bonga,cold_lake_blend,dalia,upper_zakum,wti_light_export}.json` | ExxonMobil compositions (mole amounts) with design flows | game (client library); tests: V3ClientPresets | used / replaced | delete at O3 with their `index.json` rows | R-XOM |
| `assets/icheme/columnpreset/` (same 5 crudes) | column templates: design feed and draw flows sized from each assay | game; tests: ColumnTemplates, V3ColumnTemplate, ConstructorColumn | used / replaced | delete at O3 (**uncertain** whether flows sized from an assay count as its data; conservatively yes) | R-XOM |
| `columnpreset/{tia_juana,pot}`, `mixturepreset/tia_juana*` (3) | Tia Juana templates and mixtures | game | used / O3 | O3 moves them to the grades | - |
| `lang/*.json` keys `column_preset.*` and `column_template.*` for the 5 crudes | names only | game | used / removed | remove with O3: en_us in the batch, the rest in the translation commit | - |
| `flowsheet_examples/crude_unit.json`, `index.json` | example sheet; names the Tia Juana assay and the `dalia` revision tokens | game (FlowsheetExamples) | used / changed | rewrite at O3 and O4 | - |
| `neural/registry.json`, `.payload.json` (1.85 MB), `.sidecar.json` | column model | game (V3NeuralRegistry); tests: V3BundledRegroupedModel, V3NeuralRegistry | used / unbound after O2 and O3 | **E6.** The sidecar's `compositionEdges` hold the exact mole compositions of all five ExxonMobil assays (6 segments TJL-WTI-UZ-Bonga-Dalia-CLB-TJL; matched to 1e-16). The weights were trained on those crudes ("six crudes and adjacent convex blends"). Remove, or retrain on the grades. Keeping the model for retraining is not possible in the tree. | R-XOM |
| `neural/regrouped-model-provenance.json` | training provenance | none (two documents only) | unused | move to the model's tool folder, or delete with the model (E6) | R-main |

## 4. `src/test/resources/` (159 files, all read)

| Path | What it is | Read by | Action | Rule |
|---|---|---|---|---|
| `materials/crude-assay-conversion-fixture.json` | mole fractions of all 5 ExxonMobil assays (and TJL) | MaterialPresetsTest | rebuild on the grades at O3 | R-XOM |
| `materials/column-preset-inputs.json`, `column-draw-volume-targets.json` | per-crude column inputs and draw volumes, all 5 crudes | MaterialPresetsTest, RegroupedCrudeTest | rebuild at O3 | R-XOM |
| `science/column/v3/neural/regrouped-predictions.json` (164 kB) | model inputs and outputs, including Bonga and Upper Zakum feeds | V3BundledRegroupedModelTest | goes with E6 | R-XOM |
| `science/column/v3/regrouped-authored-feature-recovery.json` | a Dalia column input | V3LearnedRecoveryTest | rebuild on a grade | R-XOM |
| `compat/presets/mixture-schema-1.json`, `column-schema-2.json` | client preset format images, with Bonga amounts and flows | V3ClientPresetsTest, V3ColumnTemplateTest | replace the values with a grade, same schema (client presets are a no-compatibility format) | R-XOM |
| `compat/fluid/*/pack-1.bin` (12 images) | facts and state corpus | FluidFactsCompatTest (by folder) | keep: they hold only revision tokens such as `crude-regrouped-r1-dalia`, not values | append-only rule |
| `fluid/reference/{nitrogen-cp-checks,water-vapor-checks,water-vapor-compression}.json` | NIST checks | NitrogenInitializationTest, FluidPropertyCoverageTest | O2 | - |
| `materials/cdu17-reference/**` (40), `materials/pilot-cryogenic/**` (5) | test overlays | Cdu17TestCatalog, PilotCryogenicTestCatalog | keep; they are the model for the R-test moves above | - |
| `science/thermo/solid-co2-holdouts/*.tsv`, `column/v3/holland-example-3-2.json` | journal and textbook tables | G4*, G5Support, HollandExample32* | decision D-C, not O5 | - |
| the other files (CoolProp with its LICENSE, DWSIM viscosity API, GERG, compat, column, fluid fixtures) | | each has its reader | keep | - |

ExxonMobil values inline in test code:

- `RegroupedCrudeTest`: the observed Dalia viscosities 0.0423 and 0.489 Pa s, and `crudeFeed("icheme:dalia")`.
- `ColumnTemplatesTest`, `V3ClientPresetsTest`: the Bonga design flow 2650.277 kmol/h.
- Names only: `ConstructorColumnTest`, `FluidPropertyCoverageTest`, `FluidThermodynamicsTest`, `PumpRateWarmStartTest`, `FluidAppearanceTest` (the tint is the project's own estimate), `FluidDeviceSpecDomainTest`, `ColumnSolveTest`, `MaterialCatalogTest`, `LegacyNetworkPathPinTest` (tokens).

## 5. `tools/`

| Path | What it is | Read by | Action | Rule |
|---|---|---|---|---|
| `wp5-legacy-digest/` (323 files, 5.0 MB) | `git archive 959ea0d src/main`: the 5 ExxonMobil assays with URLs and SHA-256, their packages, presets and sidecar, and the NIST nitrogen | none (concluded 2026-09-25; LegacyNetworkPathPinTest pins its digest, not its files) | delete the copy; the README keeps the `git archive 959ea0d src/main` recipe | R-XOM, duplicate |
| `fluid-world-inspector/world-inspection.json`, `wti-pumping-rate-hold/snapshot.json` | world dumps holding the exact WTI composition | none | delete, or strip the composition | R-XOM |
| `fluid-probes/f4/nist/` | raw WebBook output | - | already moved to `research/2026-09-23-fluid-followups/f4-nist/` (O1 done) | R-res |
| `unified-thermo-port-m1/diffs/wp7-data.diff`, `package-rework/migrate-data.mjs`, `wp7-network-integration/records.js`, `unified-thermo-port-ice/edit/*.js`, `test-archive-2026-10-21/` | package and preset ids of the old crude packages; no amounts, URLs or SHA-256 (grep) | none | keep; names only | - |
| `column-operation/results/c1-sweep-native.tsv`, `junction-holdup-prototype/run132-reports/M1-*.json`, `jdk-determinism/captures/*`, `heat-gui-renders/*Prototypes.java`, `process-simulator/*` | solver results on these feeds; crude names | none | keep (**uncertain**: project results computed on ExxonMobil inputs, without assay values) | owner |
| about 60 gate-output folders (`TEST-*.xml`, `binary/output.bin`) | test names with `Dalia` or `Bonga` | none | keep for O5 | - |
| `neural/retired/`, `benchmark-population/`, `transformer-promotion/` | older models and training inputs (latent mixture factors) | none | no ExxonMobil values found (one numeric collision checked); **uncertain** for blends stored as factors | - |

Outside O5: tools/ is 542 MB tracked, with 79 files over 1 MB (232 MB) and about 7,200 gate-output XML/bin files (149 MB). That is an owner item (R-res).

## 6. `documentation/` and `examples/`

| Path | What it holds | Action | Rule |
|---|---|---|---|
| `2026-09-17-crude-regrouping/CRUDE_REGROUPING_PLAN_REVIEW.md` | quoted Dalia assay facts: 370 C+ residue 528 g/mol and 963.2 kg/m3; six bulk viscosity points; PC13 shares per crude; per-crude ratios to the assay-reported viscosities; Dalia fit coefficients | redact the values, keep the reasoning | R-XOM |
| `2026-09-18-tray-pressure-drop/TRAY_PRESSURE_METHOD_REVIEW.md`, `CRUDE_REGROUPING_DATA_GENERATION_REVIEW.md`, `2026-10-24-wti-pumping-rate-hold/*`, code guide `thermo-materials.md`, and about 45 others | crude names with project solver results or timings | keep (names only) | - |
| `DATA_LICENSE_REVIEW.md`, `OPEN_DATA_PLAN.md` | names; URL of the ExxonMobil terms page | keep (the record of the decision) | - |
| `REFINERY_COMPOUNDS_DATA.md`, P3 documents | NIST values (licence review family 15) | O2 / N1, not O5 | - |
| `examples/material-override/.../tjl20_methane.json` | copy of the NIST methane record | MaterialPresetsTest (by path); update with O2 | - |
| `examples/Build-NitrogenFluidProperties.py`, `Fetch-FluidSteamReferences.py` | WebBook fetchers that made the nitrogen and steam values | no build task; O2 replaces them by the open generator | - |
| `examples/` (other 4 scripts) | benchmarks, models, hydraulic references | no third-party data | keep (check only) | - |

## 7. Removal lists

**Safe now (no game path; tests rewritten in the same commit):**

- `assays/upper_zakum`, `bonga`, `dalia`.
- `tools/wp5-legacy-digest/` copy (README recipe kept).
- The two WTI world dumps in tools.
- The value redaction in CRUDE_REGROUPING_PLAN_REVIEW.md.
- `neural/regrouped-model-provenance.json` out of `src/main`.

**Moves to `src/test` (now; owner decision for the pilot):**

- `tjl19` package, `tjl19` assay and `column_literature`.
- `column_tia_juana_nitrogen`.
- The 15 pilot cryogenic files.

**Wait for O3:**

- `assays/wti_light_export`, `cold_lake_blend`; `presets/fluid_3`, `fluid_4`.
- The 10 client crude presets, their index rows and lang keys.
- `assays/tjl20` to a test overlay.
- The test fixtures of section 4 and the inline test values.

**Wait for O4:** the crude_pc liquid viscosity tables, the descriptors and the `liquid_mixture` correction, with the example sheet tokens.

**Wait for E6:** the payload, the sidecar (ExxonMobil compositions), `regrouped-predictions.json`, and the provenance file if the model goes.

**Owner items, not acted on:**

- Git history holds every ExxonMobil value, URL and PDF hash.
- The main checkout's `research/crude-assay-sources/` (PDFs, text, page images) and `research/viscosity-temperature-plots/` are local and git-ignored. They are outside the tree, and the site terms allow one personal copy (interpretation).
- The 8 dormant CO2 pairs.
- The size of `tools/`.
