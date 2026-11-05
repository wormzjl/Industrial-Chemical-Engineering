# 2026-11-05-neural-retrain

Purpose: retrain the V3 column's learned start on open data (the four generated crude grades and their blends) and today's column (wet condenser, steam, pumparounds, side draws, flow-dependent tray pressure drop), with E7's principal-composition check, and remove the stale regrouped model in the same change. Owner decision E26 of [OPEN_DATA_PLAN.md](../2026-11-04-open-thermo-data/OPEN_DATA_PLAN.md).

Status: Planned (2026-11-05), on `claude/neural-retrain-open-data-712a75`, which merges into `claude/refinery-compounds-thermos-252973`. Waiting for the owner's approval of the plan. Index: [../INDEX.md](../INDEX.md).

## Documents

- [NEURAL_RETRAIN_PLAN.md](NEURAL_RETRAIN_PLAN.md): expected behaviour, what exists and what was lost, work packages N0 to N7, the compute estimate, owner decisions D1 to D5, the branch relation.

## History

- 2026-11-05: plan written (no training, no data generation, no `src/` edit, no Gradle run). Facts checked in the code at `25865edf1`: the only caller of the learned start is `ColumnUnit.solve`; the 100 ms cap `LARGE_WET_NEURAL_PROBE_MILLISECONDS`; sidecar schema 4 with six `compositionEdges`.

## Code areas

- `science/column/v3/`: `V3ColumnCalculator` (learned route, `neuralBudgetMilliseconds`), `V3NeuralRegistry`, `V3AnchorTransformerInitializer`, `V3GeneralNeuralFeatures`, `V3FactorizedNeuralFeatures`, `V3NeuralSeed`; `science/material/TrainedPhysics.java`; `science/flowsheet/unit/ColumnUnit.java`; `src/main/resources/data/icheme/neural/`.

## Open items

- Owner decisions D1 to D5 of the plan.
- Open measurement M1: time of bind, anchor and inference on the four presets.
