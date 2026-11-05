# 2026-11-05-neural-retrain

Purpose: retrain the V3 column's learned start on open data (the four generated crude grades and their blends) and today's column (wet condenser, steam, pumparounds, side draws, flow-dependent tray pressure drop), with E7's principal-composition check, and remove the stale regrouped model in the same change. Owner decision E26 of [OPEN_DATA_PLAN.md](../2026-11-04-open-thermo-data/OPEN_DATA_PLAN.md).

Status: In progress since 2026-11-05, on `claude/neural-retrain-open-data-712a75`, which merges into `claude/refinery-compounds-thermos-252973`. Plan revision 2 waits for the owner; N0 done; no Gradle (lane 2 passed to O11). Index: [../INDEX.md](../INDEX.md).

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

- The VDU text after "VDU specific data (" (D8 follows case A until then).
- Open checks V0 (the encoding and anchor on the VDU top), M1 (timing, alone).
- N1 pilot of record: needs a Gradle lane from the base session. N2 waits for the base branch commit of F1b and fix (a) (E30) and a sync with the base branch.
- Open measurement M1: time of bind, anchor and inference on the four presets.

## History (continued)

- 2026-11-05, N0: venv (torch 2.10.0+cu128, numpy 2.3.5) in `tmp/neural-retrain/venv`. `tools/neural-retrain/model.py` matches the Java forward pass on the four presets: worst 7.3e-7 relative, 88,852 parameters.
- 2026-11-05, N1: `design.mjs` and `NeuralRetrainProbe` written. The pilot was stopped at 203 of 300 rows when lane 2 passed to O11: 126 strict (62 %), 38 DRY_SUPERSATURATED, 27 stalls at 30 stages, 10 with steam above the feed rate.
- 2026-11-05, plan revision 2: the owner's four changes (5 % unknowns, a VDU set, sense checks, two re-iteration rounds), relayed by the base session. NEURAL_RETRAIN_DATA.md sections 5 to 8 added; D3 and D5 revised; D6 to D8 added.
- 2026-11-05: E30 recorded. The owner: "freezing isn't part of this work ... you will not be training CDU/VDU models based on freezing conditions"; the frozen-tray check is dropped. Revision 2 approved: "Go with recommended" (D3, D5, D6, D7 = A; D8 follows case A until the VDU text arrives).
- 2026-11-05, N1 revision 2 (no Gradle): `design.mjs` now has CDU feed temperatures of at most 643 K, injection of 1 to 3 off-axis compounds (U(0, 5) mol %) on 2,070 of the 6,000 TRAIN rows, and 1,500 + 150 VDU rows (pilot 75) on CDU-label bottoms. `NeuralRetrainProbe` realises injected rows on `icheme:refinery` and VDU rows on the case A layout; it applies the input and state sense checks of section 7, records the native anchor's availability on VDU labels (V0) and writes `label` = strict and sensible. U0 answered from the code. Not yet compiled: it needs a Gradle lane.
- 2026-11-05, N1 pilot of record (lane 1, 4 workers, counters only): CDU 148 of 300 labels (49 %), the injected rows solving like the others (53 of 108); VDU 20 of 74 (27 %), V0 answered (anchor 20 of 20). DRY_SUPERSATURATED takes 35 % of the CDU rows, a coverage hole under D7 A. The VDU losses are mostly depleted side draws (30) from the cut sizing. The overflash upper bound was dropped (the presets run 0.44 to 1.12). Plan section 3 has the numbers.
