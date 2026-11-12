# The column's learned start after the batch: qualify, then retrain

Batch 2026-11-11-aqueous-solvents. Owner, 2026-11-12: "did retrain of the column initializer happen? you should use a
thread to do that"; choice: "Qualify, then retrain". Sub-thread: new, Opus medium, its own worktree, 8 workers,
reports every 50 minutes, at once on a stop, and at the end.

## 1. Where it stands
- No retrain in this batch (owner rule of 2026-11-11, README open items: measure in W5, retrain only where the
  start stops helping). The bundled model is `icheme:v3-column-open-r34-u1-v2` of batch 2026-11-05-neural-retrain
  (`src/main/resources/data/icheme/neural/v3-column-transformer-open.payload.json`, `.sidecar.json`).
- W5 measured it on 5 presets only: accepted, the same Newton iterations as the classical start. `medium_crude`
  moved by +0.14 % overhead, 0.075 K, duties under 0.03 %; a dry list with no gas is bit for bit. The model's node
  temperature error is 3.2 K, so the crude physics moved far less than the model's own error.
- After W5, W6 put Ethanol in `icheme:refinery` (r4). Whether the model still binds every crude request is not
  measured beyond the lane's `V3BundledModelTest`.
- Ethanol and water columns have no network (`lumpMap` has no ethanol): out of scope here.

## 2. Phase Q: qualify v2 on today's column (no Python, no download)
Tools: `tools/neural-retrain/` (README, `qualify.mjs`, `NeuralRetrainProbe.java`). Scratch: `tmp/neural-requalify/`
with a row in `tmp/INDEX.md`. Read first: that README, the N4 entries of
`documentation/2026-11-05-neural-retrain/README.md`, AGENTS.md "Gradle, measurement and verification".
1. `node tools/neural-retrain/qualify.mjs design tmp/neural-requalify/design 20261121` (a seed no training or
   earlier holdout used: 20261106, 07, 11 to 14 are consumed). It writes `cdu.jsonl` (40 grid, 12 blends, 300
   holdout) and `vdu.jsonl` (100).
2. Copy `NeuralRetrainProbe.java` to `src/test/java/com/wormzjl/icheme/science/column/v3/` (never committed there).
   If it no longer compiles on this branch (test fixtures now address compounds by id), fix the tracked copy in
   `tools/neural-retrain/` and commit that.
3. Classical arm: `NR_DESIGN=tmp/neural-requalify/design/cdu.jsonl NR_OUT=tmp/neural-requalify/classical-cdu
   NR_WORKERS=8 ./gradlew columnGates --offline --rerun --tests "*NeuralRetrainProbe.labels" --max-workers=8`
   (CURRENT_ONLY, 4 M poll cap). `--rerun` is right here: the `NR_*` variables are not task inputs.
4. VDU rows: `NR_CDU_LABELS` on the r34 CDU validation journal of
   `research/2026-11-05-neural-retrain/labels-r34.tar.gz` in the main checkout (extract with `tar --force-local`
   into `tmp/neural-requalify/r34/`); then the same run on `vdu.jsonl`.
5. Learned arm: the same two runs with `NR_PAYLOAD` and `NR_SIDECAR` on the bundled files, the default budget
   (300,000 polls), into `learned-cdu` and `learned-vdu`.
6. `node tools/neural-retrain/qualify.mjs compare <classical> <learned> icheme:v3-column-open-r34-u1-v2 <out.json>`
   for CDU and VDU; `node tools/neural-retrain/tally.mjs <dir>` for the yield and failure classes.

## 3. The reference and the rule
v2 on the r34 column (2026-11-08, seed 20261114, 300k): 280 of 300 CDU holdout rows admitted; 75.4 % of them
labelled on the learned route at a median 0.064 of the classical polls; 31 of 93 classical failures rescued; 0
labels lost; VDU 24 of 66 admitted on the learned route, 7 rescued; classical CDU yield 79 % (N2).

The start has lost ground when any of these holds (two standard errors of the reference, 280 rows):
- admitted CDU holdout rows under 260 (the model binds fewer requests);
- learned-route share under 70 %;
- median poll ratio above 0.10;
- rescued under 23 % of the classical failures;
- any label lost.

## 4. Stops and what follows
- Not lost: write `NEURAL_REQUALIFY_REVIEW.md` (at most 50 lines: the table beside the reference, classes, the
  five criteria), a README history line, commit on the sub-thread's branch, final report. No retrain.
- Lost by binding (admission near zero, or `TrainedPhysics.matches` refusing): a defect of identity, not of
  training. Stop and report the refusing line; do not retrain.
- Lost otherwise: stop and report the numbers. The retrain (N2 relabel on today's column, N3, two N3b rounds, N4 on
  a new seed, the N6 swap) needs the Python environment of the tool's README, which is a download: the owner's
  go-ahead first. The main thread writes that phase's steps then.
- Also a stop: a hold or failure class the r34 tallies do not have; a probe change that would alter what a label is.

## 5. Rules for the thread
- Gradle: this is the machine's campaign lane (8 workers). The main thread runs filtered tests in the batch
  worktree at 4 workers or fewer. No whole `test`, `slowGates` or `check`; no final lane; no GameTests.
- Cost is judged by polls. No times recorded. `_JAVA_OPTIONS=-Xmx8g` if a journal needs it.
- Pitfalls: a consumed holdout is not blind again; the probe journals every row, so a stopped run resumes by
  design row id only if the probe says so (read its header); `LNN_FIRST` falls back to classical, so a lost label
  is a defect, not noise.
- No edit of `src/main` or of the bundled model in phase Q. No push, merge or version bump.
