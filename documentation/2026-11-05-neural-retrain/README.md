# 2026-11-05-neural-retrain

Purpose: retrain the V3 column's learned start on open data (the four generated crude grades and their blends) and today's column (wet condenser, steam, pumparounds, side draws, flow-dependent tray pressure drop), with E7's principal-composition check, and remove the stale regrouped model in the same change. Owner decision E26 of [OPEN_DATA_PLAN.md](../2026-11-04-open-thermo-data/OPEN_DATA_PLAN.md).

Status: In progress since 2026-11-05, on `claude/neural-retrain-open-data-712a75`, which merges into `claude/refinery-compounds-thermos-252973`. Plan approved; N0 and N1 running, N2 waits for O10. Index: [../INDEX.md](../INDEX.md).

## Documents

- [NEURAL_RETRAIN_PLAN.md](NEURAL_RETRAIN_PLAN.md): expected behaviour, what exists and what was lost, work packages N0 to N7, the compute estimate, owner decisions D1 to D5, the branch relation.
- [NEURAL_RETRAIN_DATA.md](NEURAL_RETRAIN_DATA.md): the 19-compound basis and the grades, the data generation (composition sampling, factors, screens, yield) and the cleaning rules, the E7 thresholds from the trained spread.

## History

- 2026-11-05: plan written (no training, no data generation, no `src/` edit, no Gradle run). Facts checked in the code at `25865edf1`: the only caller of the learned start is `ColumnUnit.solve`; the 100 ms cap `LARGE_WET_NEURAL_PROBE_MILLISECONDS`; sidecar schema 4 with six `compositionEdges`.
- 2026-11-05: owner question on the basis and the data method: NEURAL_RETRAIN_DATA.md added; N2 re-based at the measured 30 to 35 % yield (about 6,000 TRAIN requests, 1.5 h); lognormal perturbation in N1; D3 option B (product-stream feeds); D5 limits from the trained spread.
- 2026-11-05: owner decisions, relayed by the base session: "Approve all A" (D1 to D5 = option A of the plan at `138b0024e`); "Yes, download" for torch-2.10.0+cu128-cp312-cp312-win_amd64.whl (2,867,409,626 bytes, download.pytorch.org/whl/cu128), numpy-2.3.5-cp312-cp312-win_amd64.whl (12,782,922 bytes, PyPI) and torch's small PyPI dependencies (about 12 MB); any other download needs the owner's OK. Order (D1 A): N0 and N1 now, N2 after O10 states whether its fix changes a formulation revision; sync with the base branch before N2. Lane 2 only (at most 4 workers, no timing); M1 waits until the machine is free.

## Code areas

- `science/column/v3/`: `V3ColumnCalculator` (learned route, `neuralBudgetMilliseconds`), `V3NeuralRegistry`, `V3AnchorTransformerInitializer`, `V3GeneralNeuralFeatures`, `V3FactorizedNeuralFeatures`, `V3NeuralSeed`; `science/material/TrainedPhysics.java`; `science/flowsheet/unit/ColumnUnit.java`; `src/main/resources/data/icheme/neural/`.

## Open items

- N2 waits for O10; M1 waits for a free machine.
- Open measurement M1: time of bind, anchor and inference on the four presets.
