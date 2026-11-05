# N6: swap the bundled model (prepared 2026-11-05, not run)

Runs after N4 qualifies a candidate trained on the r34 labels (after condenser carry-over). One commit adds the new model and removes the regrouped one (owner rule of this batch: the old model goes in the same change).

## Expected

- One bundled entry: the N4-qualified model, sidecar schema 5, physics of `icheme:tjl20_methane` at the labelling commit (`NeuralRetrainProbe.physics`), its formulation revisions (r34) and node-pressure bounds from its TRAIN rows.
- A column the model does not cover (another formulation, out of family, a crystal package) runs classical, exactly as today.

## Resources (`src/main/resources/data/icheme/neural/`, `-text` in `.gitattributes`: copy bytes, never re-save)

- Add `v3-column-transformer-open.payload.json`, `v3-column-transformer-open.sidecar.json` (from `export.py`), and `open-model-provenance.json`: model id, payload and sidecar SHA-256, feature and anchor revisions, parameters, the label journals and seeds, N4's numbers.
- `registry.json`: one entry, priority 100, the new paths, `pipelineSha256` = sha256(hex(sha256(payload)) + ":" + hex(sha256(sidecar))) (`TransformerArtifact.read`).
- Remove `v3-column-transformer-regrouped.payload.json`, `.sidecar.json` and `regrouped-model-provenance.json`.

## Code

- `V3NeuralRegistry.TransformerArtifact`: drop the schema-4 field set and the dual read; schema 5 only.
- `V3AnchorTransformerInitializer`: drop `compositionEdges` (field, validation, the edge branch of `compositionSupported`) and the "Unknown compounds need sidecar schema 5" check.
- `V3InitializationOptions.DEFAULT_BUDGET_POLLS`: set from N4's counters (anchor, inference and correction polls on the presets), replacing the provisional 150,000.
- Unchanged: `IChemE` preload (line 234), `ColumnUnit` (it reads `DEFAULT_BUDGET_POLLS`).

## Tests to update

- `V3BundledRegroupedModelTest`, renamed `V3BundledModelTest`: the float64 fixtures (`src/test/resources/science/column/v3/neural/regrouped-predictions.json`) re-made from the new payload by `NeuralRetrainProbe.features`; the zero-nitrogen binding; the trained-tolerance and left-out-values checks on the new physics; the fourth-draw decline.
- `V3NeuralRegistryTest.theArtifactReaderTakesSchemaFive`: read the bundled schema-5 pair directly; keep the schema-4 refusal.
- Check for moved pins: `V3ColumnFeedAndDrawLimitsTest` (the model still declines its input), `V3MethaneColumnTest` (the learned event), `MaterialCatalogTest` lines 57 and 162 (binding on the methane package), `CrystalPackageConsumersTest` (no model binds a crystal package), `FlowsheetColumnTest` line 134 and the holdGates sample-sizing classes and `FlowsheetExamplesTest` (the simulator's learned start).

## Gates

- columnGates on the changed classes; `test --tests MaterialCatalogTest`; holdGates on `FlowsheetColumnTest`, `SimulatorRunTest` and the sample-sizing classes. The final lane runs the rest once.
- Tool folders of earlier batches that name the regrouped files (`tools/open-thermo-data/sidecar-schema4.mjs`, `O7BindingProbe`, `o8/`, `tools/package-rework/`, `tools/process-simulator/` probes) stay as history; their READMEs say which commit they ran against.

## At merge

Code guide column-v3 chapter (the registry, schema 5, U1 lumping, the poll budget), CHANGELOG line, translations if any status text changed (none planned).
