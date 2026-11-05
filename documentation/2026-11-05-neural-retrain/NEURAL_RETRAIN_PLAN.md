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

## 3. Proposal (revision 2, 2026-11-05: the owner's four changes)

The owner asked for four changes: "tolerate up to ~5% unknowns", "a set of VDU specific data (" (the line breaks off; text pending), "a 'does this condition makes sense' step", and "a re-iteration step ... repeat 2 times". Detail: [NEURAL_RETRAIN_DATA.md](NEURAL_RETRAIN_DATA.md) sections 5 to 8.

| WP | Inputs -> outputs | Measurement (at most three forms) | Gates |
|---|---|---|---|
| N0 environment | venv in `tmp/neural-retrain/venv` (done); `tools/neural-retrain/` with the Python twin `model.py` and the Java probe | none | done 2026-11-05: Python forward pass equals Java on the 4 presets, worst 7.3e-7 relative; 88,852 parameters. Java exports the features, so Python needs no feature twin |
| N1 design | basis, grades, presets -> CDU design (Dirichlet(0.5) blends x lognormal(0, 0.25)); injection of up to 5 mol % world-list compounds into a share of rows (section 5); VDU design on CDU-label bottoms (section 6; owner text pending); generation sense windows (section 7) | U0: which package and compound list a column with off-axis compounds solves on; V0: whether the encoding and the anchor carry the VDU's dry zero-reflux top at 1 to 8 kPa; M1 (timing, runs alone) | design audit, then the 5 % pilot, after the owner approves revision 2 |
| N2 labels | design -> classical labels on the current V3 code, CDU and VDU | pilot decides the count | strict rule; sense checks on the converged state, counts per criterion (section 7); dedupe, quarantine, classes A to D; open-data check |
| N3 train v1 | labels -> checkpoints | unknowns forms (D6): U1 volatility lumping; U2 U1 plus off-axis volatility channels; U3 property-descriptor tokens, only if D6 says so | validation starting residual, tray-1 T, off-gas, condenser water, on rows with and without injected compounds |
| N3b re-iteration | v1 -> round 1 -> v2 -> round 2 -> v3 (section 8): LNN_FIRST on TRAIN inputs classical did not solve, plus fresh requests in thin cells; strict native results only | coverage per cell and per class (heat gate, VDU, stall band) and cost, after each round | a network output is never a label; fresh holdout per promotion |
| N4 qualify | v3 (or the best round) -> O8 harness (4 grades x 10 feed T, 12 blends), the VDU case-A population, fresh holdout of 300 CDU + 100 VDU; LNN_FIRST against CURRENT_ONLY | polls, Newton, accepted, rescued | (a) accepted on at least 80 % of in-family holdout at half the classical polls or fewer, or (b) rescues at least half of the classical stalls; a failed attempt costs at most the poll budget |
| N5 integrate | sidecar schema 5: per-family E7 check (D5), off-axis limit, lump map; registry row; poll budget (D2) | E7 on Tia Juana (refused), grades, blends, VDU feeds, 5 % injected feeds (admitted), 10 % injected (refused) | the neural tests of revision 1; columnGates on the changed classes only |
| N6 remove old, N7 cleanup | as revision 1 | as revision 1 | as revision 1 |

Pilot of record (2026-11-05, Gradle lane 1, 4 workers; journals in `tmp/neural-retrain/pilot-r2/` and `pilot-vdu/`):

- CDU, 300 rows: 148 labels (49 %), 53 of them from the 108 injected rows (U0 measured: the classical start solves feeds with off-axis compounds as well as the rest). 105 accepted but DRY_SUPERSATURATED (35 %, not labels under D7 A); 34 class D, 8 depleted draws, 5 poll caps; no sense failure after the overflash fix. Supersaturation grows as the feed cools (610 to 630 K: 30 to 56 % of accepted rows; 640 K: 5 %), as the top pressure rises (300 kPa: 70 %) and with more steam. Cost: 1.60 M polls per label (median label 0.58 M).
- VDU, 75 rows on the CDU labels' bottoms: 20 labels (27 %; the vacuum batch measured 23 %). 30 depleted side draws, 19 poll caps, 5 class D, 1 sense exclusion (steam to feed). V0: the native anchor builds on 20 of 20 VDU labels, all on the LIQUID_ONLY branch. Cost: 8.7 M polls per label (median label 27k: bimodal).
- Overflash: the presets run 0.44 to 1.12 mol of liquid above the feed per mol of feed, so the 15 % upper bound would refuse the game's own operating points. The check keeps the lower bound only (a dry wash zone fails); `relabel.mjs` re-derived the CDU journal.
- Partial run before revision 2 (203 rows, stopped): 126 strict, 38 DRY_SUPERSATURATED.

Compute estimate (8 workers, 165 polls/ms; the pilot of record replaces these numbers):

- N2 CDU: about 3,500 TRAIN requests at the partial pilot's 62 % (about 6,000 at the guide's 33 %), 550 validation: 0.8 to 1.5 h. VDU: about 1,500 requests at the measured 23 % (228 of 1,000 cold cases) -> about 350 labels, about 0.5 h.
- N3: 3 seeds x at most 3 forms x 3 models (v1, v2, v3): about 1.5 h on the GPU.
- N3b: per round about 2,500 unsolved inputs plus 1,000 thin-cell requests at the 150k-poll budget, then classical fallback where it fails: about 0.7 h per round, 1.5 h for two.
- N4: three holdouts (one per promotion) x 400 x 2 arms: about 0.5 h. Total about 5 to 6 h of machine time.

## 4. Owner decisions

Decided 2026-11-05: "Approve all A" (D1 to D5 = A) and the N0 download. D1, D2 and D4 stand. Revision 2 replaces D3 and D5 and adds D6 to D8:

Decided 2026-11-05 (revision 2), the owner's words: "Go with recommended": D3 A, D5 A, D6 A, D7 A; D8: case A until the VDU text arrives.

D1 after O10 (reported 2026-11-05, base branch `f53a6b4ba`): the stall fix F1b changes no formulation revision; the energy-gap fix (a) bumps the wet revision. N2 waits for the owner's gap choice, and after (a) also for its landing and a sync with the base branch. E30 (2026-11-05, "Land both"): F1b and fix (a) land on the base branch; N2 starts after that commit and a sync. Freezing is out of scope (owner, 2026-11-05).

- D3, envelope. A: the 4 grades and their blends (lognormal perturbation); up to 5 mol % injected from the world list (CO2 excluded while E25 refuses it); the VDU set on CDU-label bottoms; the column-operation ranges; the constructor's stage counts. **Recommended.** B: A without injection (the unknowns rule then acts at inference only, untrained). Product streams other than the CDU bottoms (naphtha to a stabiliser) stay out and run classical.
- D5, E7 check per family. A: two families, CDU feed (the 3-PC plane of the grades) and VDU feed (PCA of the labels' bottoms), each with limits from the trained spread; off-axis share at most 5 %, the known part renormalised and checked; the box with 10 % span margin. **Recommended.** B: one PCA over both families.
- D6, the unknowns structure (section 5). A: compare U1 and U2 in N3, keep the better; U3 not built. **Recommended.** B: build U3 as the third form (a feature-revision change and a new encoder: about 2 days more). C: U1 alone.
- D7, accepted DRY_SUPERSATURATED solves (19 % of the partial pilot). A: not labels (guide 6.0): the tray holds water above its dew point, so the profile teaches a phase state the equilibrium does not have. **Recommended.** B: labels, as the game publishes them with a warning (owner rule of the free-water batch).
- D8, the VDU set: placeholder for the owner's text after "VDU specific data (". Until it arrives, section 6 follows case A.

## 5. Branch and merge

- This branch (`claude/neural-retrain-open-data-712a75`) starts at the base branch's `25865edf1`. It merges into `claude/refinery-compounds-thermos-252973`, not `main`; the base branch's single final lane then covers it. Before N2, sync with the base branch; a compound added to the column package after N2 means new labels.
- Saves: the model is no save format; a stored column seed is state. No translation keys expected.
- Risks: off-axis compounds need a column package that holds them (U0); the VDU yield is low (23 %) and its thermo has two known limits (section 6); re-iteration can bias coverage toward what v1 already solves, so thin cells get fresh requests; Gradle and campaign slots are shared with the base session.
