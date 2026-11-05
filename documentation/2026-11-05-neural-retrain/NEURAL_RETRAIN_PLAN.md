# Neural retrain plan: the V3 column's learned start on open data (2026-11-05)

Owner decision E26 of [OPEN_DATA_PLAN.md](../2026-11-04-open-thermo-data/OPEN_DATA_PLAN.md); evidence O8 ([NEURAL_GRADES_REVIEW.md](../2026-11-04-open-thermo-data/NEURAL_GRADES_REVIEW.md)) and O3 ([CRUDE_GRADES_APPLY_REVIEW.md](../2026-11-04-open-thermo-data/CRUDE_GRADES_APPLY_REVIEW.md)). Plan only: no training, data generation or `src/` edit before the owner approves it.

## 1. Expected behaviour

- Where it runs: only the process simulator's cold column solve, `ColumnUnit.solve` (client, `START` = LNN_FIRST, 16 Newton iterations, 2,000 ms), when no warm seed fits; the sizing trials start warm. A running column (`ColumnSolve.run`) uses its table seed or the fast classical start, never the model. So the model decides the first run of each sheet's column, not the server.
- A useful seed sits inside Newton's basin: tray temperatures within about 10 K of the solution, the top carrying off-gas (about 27 mol/s on the default 40-tray tower) and condenser free water (about 330 mol/s from 331 mol/s of stripping steam), branch TWO_PHASE. Newton then closes in at most 16 iterations: about 8k polls per iteration on the medium grade, so at most about 130k polls.
- The classical start costs 316k (medium), 549k (light), 552k (heavy) and 1,325k (dilbit) polls, 1.9 to 8.0 s, and stalls at stage 30 on the medium grade at 5 of 10 feed temperatures (622 to 644 K). An accepted seed should cut polls by half or more, and rescue some of those stalls.
- A failed learned attempt only costs its probe, then the classical start runs; LNN_FIRST can lose time, never a solution.
- Composition check (E7): the four grades are four vertices, so their blends fill a 3-simplex. Three principal components of the training compositions hold all the blend variance: in-plane distance measures "inside the blend family", out-of-plane residual measures "a different crude". Feeds off the family (Tia Juana, nitrogen package, CO2 islands) are refused before inference, at no cost.

## 2. What exists now

- Model `regrouped-seed-17041-epoch-160`: anchor Transformer, 88,852 parameters, 19 components, payload 1.85 MB, sidecar schema 4 (`compositionEdges` = Tia Juana + five ExxonMobil assays; `globalMin`/`globalMax`, 73 features; physics block of `TrainedPhysics`, E8/E9/E18). Registry `pipelineSha256` pins the artifact files (integrity, not material identity).
- Stale (O8): accepted 0 of 39 runs, Tia Juana included; labels were dry-equilibrium-qualified (`known_limit` in the provenance), so the seed has no off-gas and no condenser free water; tray 1 is 36 to 125 K too hot on the grades. Its sidecar stores ExxonMobil compositions (E6), and its weights were fitted on them, so no continuation from them: from scratch only.
- Wall clock: `neuralBudgetMilliseconds` cuts LNN_FIRST to 100 ms (`LARGE_WET_NEURAL_PROBE_MILLISECONDS`) at 32 or more stages with any draw, steam or PA, which is every crude preset; the 2,000 ms learned budget is also wall time. O7: 109 ms spent, 0 Newton steps. Inference (forward pass about 3 to 4 M multiply-adds, under 10 ms) is small next to bind (201-point grids) plus `NativeAnchor.build`; unmeasured (open measurement M1).
- Reusable: the encoding (`V3GeneralNeuralFeatures`, `V3FactorizedNeuralFeatures`: up to 3 draws, 4 PA, 2 steam, 2 to 64 trays; condenser water follows from the TWO_PHASE split, so no layout change); `TrainedPhysics`; the registry loader; `tools/neural/` (`train_transformer.py`, `prepare_transformer_data.py`, `export_transformer.py`, `V3GeneralTrainingProbe.java`, `requirements-transformer.txt`: torch 2.10.0+cu128); the training guide ([V4_TRANSFORMER_TRAINING_GUIDE.md](../2026-09-10-v4-neural-initializer/V4_TRANSFORMER_TRAINING_GUIDE.md) sections 4 to 10); `tools/training-curation/`; O8's probe `tools/open-thermo-data/o8/` (the qualification harness).
- Lost: `research/crude-regrouping/training/` (train.py, basis.json, presets.json, RegroupingFeatures.java, training.init.gradle) and the torch venv. `research/convergence-review-2026-09-18/train_v2.py` survives but imports the lost folder. GPU: RTX 4070 Ti, 12 GB.

## 3. Proposal

| WP | Inputs -> outputs | Measurement (at most three forms) | Gates |
|---|---|---|---|
| N0 environment | `requirements-transformer.txt`, uv cpython 3.12 -> venv in `tmp/neural-retrain/venv`; training folder rebuilt in `tools/neural-retrain/` (train, basis, design, Java feature twin as a test-source probe) | none | Python twins equal Java features and targets on 50 old-format rows; parameter count `76,616 + 644c` |
| N1 design | the 19-compound basis and the four grades ([NEURAL_RETRAIN_DATA.md](NEURAL_RETRAIN_DATA.md) section 1), the presets, the column-operation and simulator-door ranges -> request design (JSON): Dirichlet(0.5) blends of the 4 grades, then each compound x lognormal(0, 0.25) and renormalised (guide 4.3; one third at pure grades and pairs), feed T, top P, reflux, condenser T, steam, PA duties, draw rates, the constructor's stage counts and feed trays, diameter (flow-dependent dP on) | M1: inference, bind and anchor time on the 4 presets (one columnGates run, 10 repeats) | design audit and 5 % pilot (guide section 5): yield and wall time per label |
| N2 labels | design -> classical labels on the current V3 code (wet condenser, steam, PA, draws, hydraulics), plus the anchors and features; cleaning rules in [NEURAL_RETRAIN_DATA.md](NEURAL_RETRAIN_DATA.md) section 3 | pilot decides; then one campaign | every label passes `prepare_transformer_data.strict`; dedupe, quarantine, failure classes A to D; no Tia Juana or ExxonMobil composition in the set |
| N3 train | labels -> checkpoints | F1: current architecture from scratch (3 seeds, 160 epochs, export 120/160). F2: half width (d = 32): the composition space is a 3-simplex. F3: F1 with the flow-dependent dP terms as node features, only if F1 misses the hydraulic rows | validation scaled starting residual, tray-1 temperature error, off-gas and condenser-water error; pick one form |
| N4 qualify | chosen checkpoint -> O8 harness rerun: 4 grades x 10 feed T (622 to 644 K), 12 grade-to-grade blends (w = 0.25, 0.5, 0.75), fresh holdout of 300; arms LNN_FIRST and CURRENT_ONLY | polls, Newton, accepted, rescued, lost | earns its place if (a) accepted on at least 80 % of in-family holdout with median polls at most half of classical, or (b) rescues at least half of the classical stalls; and a failed attempt costs at most the probe budget |
| N5 integrate | sidecar schema 5 (mean, 3-PC basis, training compositions, box), registry row, poll budget -> `src/main` | E7 check on Tia Juana (refused), the grades and blends (admitted), a CO2/N2 feed (refused) | `V3BundledRegroupedModelTest` successor, `V3NeuralRegistryTest`, `V3NeuralInitializationTest`, `V3LearnedRecoveryTest`, `CrystalPackageConsumersTest`; columnGates on the changed classes only |
| N6 remove old | same commit as N5: delete the regrouped payload, sidecar and provenance; drop `compositionEdges` reading | `compileTestJava` plus the tests above | `git grep` finds no ExxonMobil or Tia Juana composition in `src/main/resources/data/icheme/neural/` |
| N7 cleanup | probes, design and training scripts -> `tools/neural-retrain/` with README and `tools/INDEX.md` row; labels, checkpoints, journals -> main checkout `research/2026-11-05-neural-retrain/` | none | `perl tools/merge-lane/main-reach.pl .` lists nothing new; code guide `column-v3.md` updated |

Compute estimate (from O8's stored counters, 165 polls/ms, mean classical 3.8 s):

- Requests: about 6,000 training (the measured 30 to 35 % strict yield of guide 4.5 -> about 2,000 labels), 550 validation, 300 holdout; pilot 300. The pilot's yield sets the final count.
- N2: 7,150 x 3.8 s = 27,200 CPU-s -> about 1.5 h on 8 workers; about 3 h on 4 workers (second Gradle lane).
- N3: about 2 to 3 min per seed on the GPU; 3 seeds x at most 3 forms -> at most 30 min.
- N4: (40 + 12 + 300) x 2 arms = 704 solves -> about 10 to 15 min on 8 workers. N1 pilot and M1: under 10 min.
- Total about 2.5 to 3.5 h of machine time on 8 workers; N2 and N4 are campaigns: 8 workers only when no other campaign or suite runs, else 4 workers on the second lane.

## 4. Owner decisions

Decided 2026-11-05: "Approve all A" (D1 to D5 = A), and "Yes, download" for the N0 packages (torch 2.10.0+cu128, numpy 2.3.5, torch's PyPI dependencies).

- D1, order against O10 (the classical stall). If O10 makes the classical start robust, the seed is worth only its poll saving on a sheet's first column solve (about 2 to 8 s once per sheet run, since warm seeds cover the rest). A label is a converged state, so an O10 fix that leaves the formulation revisions alone does not stale the labels; a formulation change does.
  - A: N0 and N1 now; N2 after O10's diagnosis states whether it changes a formulation revision. **Recommended.**
  - B: everything now; relabel if O10 changes a revision (about 1 h lost).
  - C: wait for O10's fix.
- D2, the wall-clock cap. A: replace both learned budgets (100 ms and 2,000 ms) with a control-poll budget, about 150k polls (16 Newton iterations on the medium grade plus the anchor), machine-independent. **Recommended.** B: Newton iteration cap only (16), no time or poll limit. C: keep 100 ms (fails "a seed's use must not depend on the machine's speed").
- D3, envelope. The axis is the 19 compounds of `tjl20_methane`; a sour grade or a new compound changes it and must be decided before N2 ([NEURAL_RETRAIN_DATA.md](NEURAL_RETRAIN_DATA.md) section 1).
  - A: the 4 grades, their blends with the lognormal perturbation, the column-operation ranges, the constructor's stage counts. **Recommended.** A column fed with another column's product (residue to a vacuum column, naphtha to a stabiliser) lies far off the 4-grade plane: E7 refuses it and it runs classical.
  - B: A plus product-stream feeds by the guide's global composition rule, served by the box alone in that region. Cost: about 2,000 more requests for about 700 labels (the regrouped campaign added 689), about 30 min more labelling. Measured value there: neural-first 145 -> 148 on the ring holdout; off the ring it decides whether the model works at all (33 against 107 neural-only).
  - C: A plus nitrogen in the feed (another package axis: a second model). Feeds with CO2 stay refused (E25).
- D4, if N4 fails both (a) and (b): A: ship N6 alone (classical start only, O8's E6 recommendation; saves the failed probe). **Recommended.** B: one more round of N3 forms.
- D5, E7 thresholds: O8's form C plus box A, with limits taken from the trained spread (guide 9.2: the envelope must not exceed the labels). In-plane: inside the hull of the four grades, widened by the 99th percentile of the perturbation's in-plane shift. Out-of-plane: the 99th percentile of the TRAIN residuals. O8's 0.3 RMS radius is only a ceiling. Box with 10 % span margin. Measured in N5. **Recommended.**

## 5. Branch and merge

- This branch (`claude/neural-retrain-open-data-712a75`) starts at the base branch's `25865edf1`. It merges into `claude/refinery-compounds-thermos-252973`, not `main`; the base branch's single final lane then covers it. Before N2, sync with the base branch so the labels use its latest column code and component axis; a compound added to the column package after N2 means new labels.
- Saves: the model is no save format; a stored column seed is state (dropped on mismatch). No translation keys expected.
- Risks: the 3-simplex is narrow, so in-game mixtures with water, gases or other fluids are refused and run classical; label yield on dilbit (coupled ramp, 1.3 M polls) and in the medium stall band may be low; Gradle and campaign slots are shared with the base session's two lanes.
