# Package rework: compounds first, a package is a compound list

Batch [2026-11-04-refinery-compounds](README.md), 2026-11-04, base main 84f739baa (0.20.0). Owner request: "Current package system is not flexible for more compounds and need to improve. Column neuro model selection should also be based on a compound list, not a defined package. Do the fundamental rework if necessary. I have Aspen and dwsim on my PC which can serve as a data reference, prefer dwsim if ok."

## 1. Expected behaviour

- A process simulator (DWSIM, Aspen) keeps three things apart:
  - a compound database: one set of pure-component data per compound;
  - a binary-parameter database: one value per compound pair and model;
  - property methods (PR, NRTL): the rules that combine them.
- A flowsheet picks any compound list and one method. A pair with no data takes the method's default, which is zero for PR in DWSIM.
- PR78 mixing sums run over the species present. A compound with zero amount adds nothing to a, b, Z or the ln φ of the others, so the physics on a compound subset does not depend on which other compounds a package lists.
- The column's learned seed has 19 trained slots and no compound property among its features. Its prediction depends on those compounds' physics and on the feed. The package name plays no part. A zero slot is native to the encoding, and `V3NeuralRegistryTest` shows the extra-zero and missing-zero cases reproduce the base prediction.

## 2. What the code does now

- **Compounds:** 22 compounds have 24 `properties` records. Methane, ethane, nitrogen and CO2 have one record per package family.
- **Packages:** a package is an ordered axis (`bases`) with positional property records. It also names its own interactions record, water model, column range, `fluid_domain`, liquid-mixture correction and aliases.
  - `tjl19` and `tjl20` hold the same 11 k_ij pairs under two ids.
  - Every package's authored `fluid_domain` equals the hull of its members' domains and the water triple point.
  - All aliases are empty.
- **Assays:** they are keyed `package/id`. `tia_juana_light_methane` exists twice with identical amounts, and all eight assays use `amounts_by_component`. A fluid preset needs `requireSharedFluidPhysics` against the network.
- **Network:** one literal package id. `FlowsheetEngine` refuses another package. `FluidMaterialCatalog.resolveNetworkPackage` refuses a saved basis whose package id changed ("use a fresh development world").
- **Neural:** the sidecar pins `tjl20_methane`'s whole identity string, so an edit to that package record alone turns the model off.
  - `V3NeuralRegistry.bind` compares the package's full list with the model's 19, and both zero flags are false. So the network package (20 compounds, N2 = 0) never gets a seed.
  - The simulator works around this: `ColumnBlockSolve.input` takes the column door's `tjl20_methane` because "the learned start declines nitrogen".
- **Saves:** facts hold compound ids beside positional vectors. A changed basis restates a world through the FACTS route, with amounts mapped by id and a new compound starting at 0. State is positional and rebuilt. No facts-schema change is needed.

## 3. Proposal

### 3.1 Data model

| Kind | After the rework |
|---|---|
| `components` | Compound identity, as today, plus `properties` (its default dataset id), `aliases` (old ids that resolve to it, catalog-wide; replaces the empty per-package aliases) and optional `identifiers` (CAS, ChemSep index, formula; in no identity). |
| `properties` | A dataset of one compound, as today, plus an optional `freezing` sub-record with its own `revision` (section 3.6). |
| `methods` (new) | A property method. Fields: `model` (pr78/nrtl); `ideal_gas` (`fit` or `spine`); `interactions` (ordered pair-library ids, the first library naming a pair wins); `missing_interactions` (zero/error); `water_model`; the column pressure range; `liquid_mixture`; `advisory_evidence`; `revision`. |
| `interactions` | A pair library, as today, no longer tied to one package. Pairs outside a package are skipped. Pairs naming an unknown compound are refused. |
| `packages` | A compound list on a method: `method`, `components` (ids, or `{id, properties, spine}` to override a default), optional `extends` (another package, whose components come first), `crystals`, `advisory_evidence`, `revision`. |
| `bases` | Removed (`extends` replaces them). |
| `assays` | Keyed by id alone; `amounts_by_component` only. An assay applies to every package that holds its non-zero compounds. Its appearance is keyed by id. |
| `presets` | A column preset names a package and an assay. A fluid preset names a `component` or an `assay`, always on the network package; `requireSharedFluidPhysics` goes. |

**Derived, no longer authored:**
- A package's column temperature range: the intersection of its datasets' ranges.
- Its `fluid_domain`: the hull of its datasets' domains and the water triple point. This reproduces all four bundled envelopes exactly.
- Its pressure admission range: the method's.

The engines keep reading the resolved `MaterialCatalog.Package` view (components, properties, k_ij matrix, water, ranges, spines, crystals). The column, fluid network and engine code do not change in W1.

### 3.2 Identity

- `scientificRevision` = `<package>@<rev>;method=<id>@<rev>;properties=…;interactions=<lib>@<rev>,…;[group-interactions=…;]water=…`.
  - Only libraries that resolve at least one pair on the package's axis are listed, so a library added for new compounds does not move the crude packages.
- `fluidRevision` and `spineRevision` stay as they are, on the resolved view.
- Each package record changes form, so its `revision` is bumped. The network's fluid identity therefore moves once, and every world restates once through FACTS.
- Re-pinned: `LegacyNetworkPathPinTest` and `PilotCryogenicCatalogTest`. The physics on every axis is unchanged: a gate compares `samePhysics` content before and after for the four packages.

### 3.3 Neural selection by compound list

- Sidecar schema 3: `packageId`, `propertyRevision` and the two zero flags give way to `physics`. It lists the model's model type (pr78), each of its 19 compounds with the dataset `id@revision` it was trained on, the pair libraries `id@revision`, `missing_interactions` and the water `id@revision`.
  - The registry's `pipelineSha256` is re-computed. The payload bytes are unchanged (`V3BundledRegroupedModelTest` keeps its pin).
- `bind`:
  1. Build the model's reference view from those records in the live catalog. A missing record, or a revision different from the sidecar's, turns the model off (stale).
  2. Take the request's active compounds A (non-zero feed, as in `V3ActiveComponentBasis`). A must be a subset of the model list M.
  3. The request package's physics on A must equal the reference view's, compared by content (`samePhysics` on resolved views, not on catalog ids).
  4. The lens takes A's data from the request and M \ A from the reference.
  - The composition-edge, topology and global-bound gates are unchanged.
- **Effect:** the network package (N2 = 0) and any future package holding the 19 crude compounds with the same data get the seed. An edit to a package record alone no longer turns the model off. A changed crude dataset still does.
- `ColumnBlockSolve` then always solves on the sheet's package. The door's `package` parameter and its "declines nitrogen" special case go, and the physics on the shared axis is identical.

### 3.4 Network and saves

- The network is named by `networks/default.json` as today.
- The world basis check compares compound ids and the fluid identity, not the package id.
  - A FACTS restate maps amounts by id onto the current network package, whatever package id the save names.
  - A compound the network no longer holds is booked as removed, as today.
- Fix the inferred failure at `FluidFacts.java:578-580`: a changed state format together with a changed basis in a world with no islands.
- One corpus image with a renamed network package proves the path. Facts schema 9 is unchanged.

### 3.5 Compound import from DWSIM (owner: "prefer dwsim")

- DWSIM 10.2.5 embeds the ChemSep v8.3 database: 558 compounds, Artistic License 2.0, credited per record. Its fields include CAS, SMILES, Tc, Pc, ω, Tb, melting point, triple point, heat of fusion, formation data, DIPPR correlations and PPR78 groups.
- It also embeds DWSIM's PR k_ij table (`kij_pr.dat`, the source of the frozen `tjl20` pairs) and the DECHEMA PR pairs (`pr_ip.dat`, 155 pairs among the review's compounds).
- Tool `tools/compound-import/`:
  - `Extract-DwsimData.ps1` copies the embedded resources into `tmp/dwsim-data/`. It loads the assembly reflection-only and runs no DWSIM code.
  - `import.mjs` writes `components`, `properties` and calibration and pair records from a selection list, then prints a cross-check against REFINERY_COMPOUNDS_DATA.md.
- Conversions:
  - Ideal-gas Cp (ChemSep eq. 16, 100 or 107): least-squares `shifted_polynomial_5` fit, reported fit error.
  - Viscosity (eq. 101 / 102): sampled into `log_table`s.
  - Liquid volume reference: the saturated liquid at Tr = 0.7 from eq. 105 and eq. 101, as a `liquid_volume_reference_points` record, like the bundled NIST points. A compound with Tc below 288.7 K never takes its hypothetical 60 °F density.
- Existing datasets keep their numbers: the light ends are already ChemSep values, and methane and nitrogen are CoolProp/NIST.

### 3.6 Freezing point on every compound

- `properties.freezing`: `normal_melting_kelvin`, optional `triple_point_kelvin` and `triple_point_pascal`, optional `fusion_enthalpy_j_per_mol`, `estimated`, `source`, `revision`.
- It follows the viscosity rule: own revision, in no identity until a model reads it. So it moves no world and no neural model.
- It never feeds a range or a hold (AGENTS.md thermo validity).
- The catalog checks that a compound with a crystal record agrees with that record's anchor within 0.005 K.
- Values: ChemSep for every compound. The exceptions are CO2 (216.592 K, the CO2-I anchor, Span and Wagner) and water, whose water model already holds 273.16 K and its ice.
- Crude fractions: owner question Q4 below.

### 3.7 New compounds

- Imported by the tool once W1-W5 stand, with lang lines in all five files (AGENTS.md Translations).
- A compound reaches the game only through the network package: the simulator sheet and the world column both run on it. So the tier choice is a network choice (Q2).
- The axis cap stays 64 + water. Cost scales with width: dense node arrays and O(n + pairs) per EOS call.
- Exact-zero compounds leave every result bitwise unchanged. This is checked by the 33 junction lines, chain-100 (re-captured at the new width) and the campaign counters in the final lane, plus one timing run alone.

## 4. Work packages

| WP | Content | Tests run (No repeated tests) |
|---|---|---|
| W1 | Catalog: `methods`, package = method + list, component defaults and aliases, pair libraries, free assays, derived ranges; data and test resources moved (incl. `tjl-reference-resources.json`, the pilot and cdu17 datapacks) | material and preset tests, the re-pinned identity gates, a before/after `samePhysics` gate |
| W2 | Neural: sidecar schema 3, bind by active set, reference view, lens; re-pin | `V3NeuralRegistryTest`, `V3BundledRegroupedModelTest`, `V3RegroupedTransformerTest`, `MaterialCatalogTest` |
| W3 | Simulator column on the sheet package; `Report.Warm` and `RevisionNotice` on the resolved identity | flowsheet column tests, `ColumnBlockSolve` users |
| W4 | World basis by compound ids; renamed-network corpus image; no-island fix | `FluidFactsCompatTest`, `WorldTopologyLedgerTest`, `FluidCheckpointStore` tests |
| W5 | Import tool; `freezing` on all datasets | catalog parse test, the tool's own cross-check |
| W6 | New compounds per Q2/Q3, lang lines | catalog, `LanguageFilesTest`, `UnitTextTest` |
| W7 | Replaced by V1-V5 of [VARYING_COMPONENTS_REVIEW.md](VARYING_COMPONENTS_REVIEW.md) (owner, 2026-11-04) | see the review, section 4 |
| W8 | MATERIALS.md, code guide (thermo-materials, column-v3, fluid-science, process-simulator), tooling cleanup, batch README | none |

## 5. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | Sections 3.1-3.4 as written (package = method + list; neural by active compounds and content; simulator column on the sheet package; save basis by compound ids) | Q1 |
| D2 | Network compounds after the rework | Decided 2026-11-04: option B of [VARYING_COMPONENTS_REVIEW.md](VARYING_COMPONENTS_REVIEW.md), island compound sets |
| D3 | k_ij for pairs with a new compound: DWSIM (`kij_pr.dat`, else the DECHEMA value whose range is nearest 300-600 K), zero where none; existing pairs unchanged | Q3; refined 2026-11-04 (owner): Methane/H2S 0.08125 from ChemSep's 2009 table (`pr.ipd`, Reamer et al. data), because `kij_pr.dat`'s 0.1356 equals DECHEMA's Methane/SO2 value; every hydrogen pair zero (the narrow DECHEMA fits, e.g. H2/Toluene -0.51, would put H2 into the oil) |
| D4 | Crude-fraction freezing point | Q4: n-paraffin bound (Riazi and Al-Sahhaf 1995), estimated |
| D4b | Carbonyl sulfide constants | Decided 2026-11-04 (owner): Tc, Pc and acentric factor from CoolProp (Tc 378.77 K); ChemSep's record is inconsistent (Tc 375.15 K, its correlations run to 378.8 K); ChemSep correlations otherwise |
| D5 | Freezing sub-record under the viscosity rule (3.6); AGENTS.md gains "a freezing-point edit bumps the freezing point's own `revision`" | with Q1 |
| D6 | New compound ids: DWSIM name without spaces, capitalised; a leading locant moves behind (`Butene-1`), or the trade name (`Isooctane`, `Butadiene`); existing ids unchanged (they are facts) | reversible until merge |
| D7 | How identities name the method: by its physics fields (model, ideal-gas form, its other settings, the liquid-mixture correction's own revision), beside the pair libraries that give a pair on the view; the method record's own revision is in no identity. Adding a library for new compounds (W6's `dwsim_refinery_pr78` on `crude_pr78`) then moves no crude package, island, corpus image or neural model, as 3.2 promises; the `method=<id>@<revision>` token would have moved them all. The `island-sets` corpus test asserts EXACT on a fresh save of the build, not on the stored image | Decided 2026-11-04 (owner): "By physics fields"; applied in W6 |
| D8 | Where Tier 1 goes: a new package `icheme:refinery` (method `crude_pr78`, extends `icheme:tjl20_methane_nitrogen`, the 24 compounds); the network preset names it. The column presets and the calculator keep their 19- and 20-compound packages; a world loads its compounds by id (W4) | Orchestrator, 2026-11-04; reversible until merge |

## 6. Risks

- About 100 test classes name a package id. The ids stay, so most do not change. Tests that build packages from raw JSON move to the new schema.
- A wider network costs time per EOS call. W7 lands only with its measurement.
- The DECHEMA pair values are fitted over narrow ranges. Zero for missing pairs is DWSIM's own default. H2 and H2S solubility in oil is sensitive to k_ij, so their pairs with the crude fractions are a known simplification at k_ij = 0.
