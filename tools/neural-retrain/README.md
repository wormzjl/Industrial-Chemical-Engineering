# neural-retrain

Purpose: rebuild the training pipeline of the V3 column's learned start on open data (the four generated crude grades), batch [2026-11-05-neural-retrain](../../documentation/2026-11-05-neural-retrain/README.md). It replaces the lost `research/crude-regrouping/training/` folder. Plan: [NEURAL_RETRAIN_PLAN.md](../../documentation/2026-11-05-neural-retrain/NEURAL_RETRAIN_PLAN.md); data rules: [NEURAL_RETRAIN_DATA.md](../../documentation/2026-11-05-neural-retrain/NEURAL_RETRAIN_DATA.md).

## Environment (N0)

Python 3.12 venv in the worktree's `tmp/neural-retrain/venv`, with the uv cache in `tmp/neural-retrain/uv-cache` (both git-ignored):

    export UV_CACHE_DIR=$PWD/tmp/neural-retrain/uv-cache
    uv venv tmp/neural-retrain/venv --python 3.12
    uv pip install --python tmp/neural-retrain/venv/Scripts/python.exe --no-deps --index-url https://download.pytorch.org/whl/cu128 "torch==2.10.0+cu128"
    uv pip install --python tmp/neural-retrain/venv/Scripts/python.exe --index-url https://pypi.org/simple numpy==2.3.5 filelock "typing-extensions>=4.10" setuptools "sympy>=1.13.3" networkx jinja2 fsspec

Installed 2026-11-05: torch 2.10.0+cu128 (CUDA on the RTX 4070 Ti), numpy 2.3.5, filelock 4.2.9, fsspec 2026.9.0, jinja2 3.1.6, markupsafe 3.0.4, mpmath 1.3.0, networkx 3.7, setuptools 84.2.1, sympy 1.14.0, typing-extensions 4.16.0. Run Python with `-I`.

## Entry points

- `presets/`: the four grade column presets. `column_medium_crude.json` is the one in `src/main`, except its label. The light, heavy and dilbit presets are O3's drafts (`tmp/open-thermo-o3/presets/` of batch 2026-11-04-open-thermo-data), which `src/main` does not ship.
- `design.mjs` (N1): `node tools/neural-retrain/design.mjs tmp/neural-retrain/design [seed]`. Writes `train.jsonl` (6,000), `validation.jsonl` (550), `pilot.jsonl` (every 20th TRAIN row, 300) and `panel.jsonl` (the four presets):
  - Dirichlet(0.5) blends of the grades, then lognormal(0, 0.25) per compound;
  - one third pure grades or pairs;
  - 5 % with C1-C2 zeroed;
  - the stage layouts 40/32/48/24/56;
  - the operating factors of NEURAL_RETRAIN_DATA.md section 2.
- `NeuralRetrainProbe.java` (columnGates, package `science.column.v3`). Copy it to `src/test/java/com/wormzjl/icheme/science/column/v3/`, run it, then move it back.
  - `labels`: realises the design rows, screens them (request-only admission at 0.30, sigma >= 0.66, the degenerate family), solves CURRENT_ONLY under a 4 M control-poll cap, and journals every row with its strict evidence, profile and targets. Run: `NR_DESIGN=... NR_OUT=... NR_WORKERS=4 ./gradlew columnGates --offline --tests "*NeuralRetrainProbe.labels" --max-workers=4`.
  - `features`: the bundled model's raw inputs and outputs, the parity reference. Run with `NR_OUT=tmp/neural-retrain/parity`, optionally `NR_CASES` (a labels journal) and `NR_LIMIT`.
- `model.py`: the Python twin of `V3AnchorTransformerInitializer`, with state-dict names equal to the payload's. Parity check: `python -I tools/neural-retrain/model.py <payload> <features.jsonl>`.
- `relabel.mjs`: re-derives `sense` and `label` (the overflash criterion) of a journal written before that window changed: `node tools/neural-retrain/relabel.mjs <dir>`.
- `NeuralRetrainProbe.export` (N2 -> N3): for every label of `NR_LABELS` (journals joined by `;`), the axis-projected model inputs (U1 lumping by nearest normal boiling point), the native anchor on the label's branch, U2's three off-axis bands, targets and phase fractions. Injected rows run on `catalog.restrict(icheme:refinery, their compounds)`, as the simulator's sheet view does (O11 put CO2-I in that package).
- `train.py` (N3): `python -I tools/neural-retrain/train.py <features.jsonl> <out> [--seed] [--epochs] [--width] [--u2]`; the guide's recipe and loss; normalisation from TRAIN only.
- `export.py`: a checkpoint as the payload and a schema-4 sidecar the Java reader loads; `NeuralRetrainProbe.features` with `NR_PAYLOAD`/`NR_SIDECAR` then `model.py` checks parity.
- `tally.mjs`: yield table, failure classes A to D, sense criteria and the VDU anchor count of a labels journal. Run: `node tools/neural-retrain/tally.mjs <dir>`.

## History

- 2026-11-05, N0: venv installed. Parity of `model.py` against the bundled payload on the four presets: worst raw output 7.3e-7 relative, logits 4.7e-7 (the Java GELU's erf approximation), 88,852 parameters. Java exports the features, so Python needs no feature twin.
- 2026-11-05, N1: design written. The 300-row pilot on Gradle lane 2 (4 workers) was stopped at 203 rows when the lane passed to O11 (journal in `tmp/neural-retrain/pilot/`). It is not the pilot of record: the design changes with plan revision 2.
- 2026-11-05, revision 2: design windows, injection and VDU rows; the probe's sense checks, `icheme:refinery` realisation, VDU realisation and V0 anchor record. Not yet compiled (no Gradle lane).
- 2026-11-05, pilot of record: `relabel.mjs` (re-derives the overflash criterion of an earlier journal); the probe's overflash check keeps its lower bound only. CDU 148 of 300 labels, VDU 20 of 74.
- 2026-11-05, VDU rerun: cuts sized on their own pumparound (`VDU_CUT_SHARE`, `VDU_LATENT`), cooling 1.0 to 1.6x, steam 0.5 to 1.5x in `design.mjs`: 25 of 75 labels.
- 2026-11-05, VDU form 3: dry top (top return on tray 2 or lower), `VDU_TOP_WET` state check: 28 of 75 labels.
- 2026-11-05, pipeline end to end (smoke, pilot labels only): export 112 rows (anchor 112 of 112; 64 rows of the pre-O11 injected basis skipped); `train.py` 160 epochs in 8 s on the GPU (96 train, 16 validation: temperature MAE 9.6 K); `export.py` payload read by the Java loader; Java against Python 5.3e-7. The smoke model puts tray 1 of the light preset at 377 K (old model 414 K, classical 351 to 371 K). Not a candidate: 96 rows, pre-E30 code.
- 2026-11-05, injected rows after O11: the first 20 injected pilot rows on sheet views of `icheme:refinery` (20 to 22 compounds each): 11 labels, as before O11 (11 of the same 20); 6 DRY_SUPERSATURATED, 2 poll caps, 1 class D.
