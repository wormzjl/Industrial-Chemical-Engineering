# 2026-11-04-refinery-compounds

Purpose: the major refinery compounds to add to the thermo package, their data and sources, and a freezing point for every compound; the package rework (a package is a method plus a compound list) and island compound sets on the fluid network.

Status: In progress since 2026-11-04. Index: [../INDEX.md](../INDEX.md).

## Documents

- [REFINERY_COMPOUNDS_REVIEW.md](REFINERY_COMPOUNDS_REVIEW.md): physics, the catalog today, proposals P1 to P4 for the owner.
- [REFINERY_COMPOUNDS_DATA.md](REFINERY_COMPOUNDS_DATA.md): M, Tb, freezing point, Tc, Pc, omega, E-PPR78 groups and sources for 49 new compounds; freezing points for the 10 existing compounds and the 12 crude fractions.
- [PACKAGE_REWORK_PLAN.md](PACKAGE_REWORK_PLAN.md): the approved plan, work packages W1 to W8 (W7 replaced by V1 to V5) and decisions D1 to D6.
- [VARYING_COMPONENTS_REVIEW.md](VARYING_COMPONENTS_REVIEW.md): varying components on the fluid network, options A to D, work packages V1 to V5 and the owner's decisions.

## Code areas

- `science/material/MaterialCatalog` and `MaterialPresets`; `science/fluid/thermo/FluidDomain`; `runtime/fluid/FluidPresetCatalog` (W1).
- `src/main/resources/data/icheme/materials/` (new `methods` kind, `bases` removed, packages, components, assays, presets) and the neural registry and sidecar (W1).
- `MaterialCatalog`: one package builder for the loader, `restrict` (V1) and `resolve(Physics)`; `inferenceView` rebuilt as the lens (W2).
- `science/column/v3/V3NeuralRegistry`, `V3AnchorTransformerInitializer`; the regrouped sidecar at schema 3 and the registry's `pipelineSha256` (W2).

## History

- 2026-11-04: review and data tables written on `claude/refinery-compounds-thermos-252973` (9596aac11). Data read from the local CoolProp 8.0.0 fluid files (18 fluids), the CoolProp 8.0.0 fluid pages, the NIST WebBook phase-change pages and Cheméo (ChemSep v8.3 acentric factors), by two research subagents; benzene's CoolProp row re-read verbatim. No test run (documents only).
- 2026-11-04: package rework plan written and approved by the owner (PACKAGE_REWORK_PLAN.md, committed with W1).
- 2026-11-04: W1 (f079f0564): a package is a method plus a compound list; `methods` kind, component defaults and aliases, free assays, derived ranges. 613 targeted tests in 86 classes pass (test, columnGates, equipmentGates, holdGates by class). Decisions: the CDU17 fixture assay is renamed `icheme:tia_juana_light_kl1976`; the neural sidecar identity is re-pinned as a bridge until W2.
- 2026-11-04: varying-components review written (VARYING_COMPONENTS_REVIEW.md). The owner chose option B (island compound sets), cap 64 + water per island, lists that only grow while an island lives, the simulator on each sheet's own list; the k_ij route stays D3. B replaces the plan's W7.
- 2026-11-04: V1, restricted package views: `MaterialCatalog.restrict(packageId, ids)` builds the view by the loader's own code; it keeps the parent's column range and envelope, its identity names only its compounds' records. New `RestrictedPackagePhysicsTest`: bit for bit on every compared value (network: crude-island set, light ends, nitrogen alone; pilot: CO2 with nitrogen, light ends), no fix needed.
- 2026-11-04: W2, neural selection by compound list: sidecar schema 3 (`physics`: model, 19 compounds with datasets, pair libraries, missing pairs, water), `pipelineSha256` ccac06be; `bind` by active compounds, trained records and content; `samePhysics`, the zero flags, `referencePackage` and the crystal special case deleted. The network package with zero nitrogen now binds the model. Tests by class: test (17 classes, 113 tests), columnGates (8, 37), holdGates (FlowsheetColumnTest, 11): 161 tests, 0 failures.

## Open items

- Owner answers: P1 tiers, P3 crude fractions (a, b or c), P4 fusion enthalpy; plan Q4 (crude-fraction freezing point).
- Next work packages: W3 (simulator column on the sheet package; removes the door's nitrogen special case), W4 to W6, V2 to V5, W8.
- `research/convergence-review-2026-09-18/train_v2.py` still writes sidecar schema 2; `regrouped-model-provenance.json` keeps the training-time sidecar SHA (stale since W1).
- At build time: confirm n-hexadecane Tb (NIST gives 554 ± 10 K); re-read every copied digit from its source page; take Cp, liquid density and viscosity from the sources in the data file, section 5.
