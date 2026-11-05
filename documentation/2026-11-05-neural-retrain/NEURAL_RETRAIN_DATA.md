# Neural retrain: basis, data generation and cleaning (2026-11-05)

Companion to [NEURAL_RETRAIN_PLAN.md](NEURAL_RETRAIN_PLAN.md), work packages N1 to N2 and N5. The owner asked: "list the basis components (materials that can come out of crude) and more details on data generation & cleaning method". The rules come from the [training guide](../2026-09-10-v4-neural-initializer/V4_TRANSFORMER_TRAINING_GUIDE.md) sections 4 to 7 and 9.2, and from `tools/neural/prepare_transformer_data.py` (`strict`). Facts checked at `546ae8027`.

## 1. Basis

- The composition axis is the column package `icheme:tjl20_methane`. It has 19 compounds, which are global features x17 to x35, in this order: Methane, Ethane, Propane, Isobutane, N-butane, Isopentane, N-pentane, then the cuts `crude_pc01` to `crude_pc12`. The axis is unchanged from the current model, so the encoding and the parameter count (88,852) stay.
- Water is not on the axis. It enters as the steam inputs and appears only in the free-water targets (interior trays, plus the condenser through the TWO_PHASE split).
- The four grades (`materials/assays/`, mass basis):

| grade | compounds held | C1 to C5, mass % |
|---|---|---|
| light_crude | 19 | 4.0 |
| medium_crude | 19 | 2.5 |
| heavy_crude | 19 | 1.0 |
| oil_sand_dilbit | 18 (no methane) | 6.0 |

- None of the other 25 world-list compounds is in any grade: N2, and the 24 of Tier 1, H2S and both mercaptans among them. CO2 (O11) is in no grade either. A feed that holds any of them is outside the axis, so the model declines it and the classical start runs.
- **A sour grade, or any compound added to `tjl20_methane`, changes the axis.** The model then needs new labels and the "widen, then continue" route (guide section 8.1). That choice must be made before N2. Today the plan assumes the 19-compound axis.

## 2. Data generation (N1, N2)

- Compositions (guide 4.3):
  - Draw blend weights over the four grades, w ~ Dirichlet(0.5). This gives pure grades, pairs and many-way blends.
  - Mix: z = sum of w_f z_f. Then z_i *= lognormal(0, 0.25) and renormalise. The perturbation leaves the blend plane on purpose. On the regrouped basis, the off-ring holdout gave neural-only 33 with ring-only training, 107 with global training, and 109 for classical (guide 13.2).
  - With a small probability, zero a light-end group exactly (the C1-C2 pair). A compound absent from every contributing grade stays exactly zero, as methane does in dilbit.
  - About one third of the requests sit at the pure grades and their pairwise blends, the commonest game inputs. Each request records its weights and zero mask.
- Operating factors: feed temperature, top pressure, reflux, condenser temperature, steam rate and temperature, PA duties and trays, draw trays and rates, the stage counts and feed trays of the constructor's templates, and the diameter (flow-dependent pressure drop on). The ranges are those the column-operation controls and the simulator's column doors allow, plus a usage neighbourhood around each grade's preset (guide 4.2).
- Screens at generation, never by a solve (guide 4.4):
  - `requestOnlyAdmission(input, 0.30)`, the liquid-supply screen;
  - the energy-limited draw screen sigma >= 0.66 (worst sigma re-measured, since the preset set changed);
  - the reader's envelope (`supported`);
  - the structurally degenerate family: no steam, zero reboiler duty and trays below the feed (guide 6.1);
  - disjointness by `generalized_design.digest` from every earlier request file.
- Labelling: `CURRENT_ONLY` on the current V3 code. Every input is journalled, failures included. A network prediction is never a label.
- Yield and size: the measured strict classical yield is 30 to 35 % (guide 4.5). The packaged model got 306 strict labels from 1,152 requests (27 %); the full-cell holdout got 106 of 312 (34 %). The earlier 65 % in the plan had no measured source and is withdrawn. For about 2,000 labels (at least 250 per grade) N2 needs about 6,000 TRAIN requests. The 5 % pilot (about 300) decides the final count.

## 3. Cleaning (N2 to N3)

- A strict label passes `prepare_transformer_data.strict`:
  - success with `DRY_EQUILIBRIUM` or `WET_EQUILIBRIUM`; `DRY_SUPERSATURATED` and advisory successes are not labels;
  - the seed's canonical input equals the request;
  - every acceptance-audit check passed;
  - a real final Newton step at closure 1e-8, linear backward error at most 1e-12, largest log-flow change at most 1e-8, temperature-step ratio at most 1.
- Deduplicate by canonical input hash. TRAIN is disjoint from validation, the usage panel and every holdout (asserted).
- One profile per input, by fixed priority. Material root disagreements go to quarantine and are never averaged.
- Never drop a row by residual, iterations, time, solver path, model error or any holdout outcome. Redundancy is measured with `tools/training-curation/curate.py` before anything moves to reserve.
- Never delete a never-solved TRAIN input. It stays in the journal as an acquisition target.
- Report the failures by class (guide 6.3): A structurally degenerate, B path-dependent heat gate, C request-only typed infeasible, D open. Headline rates use B + D. Liquid-depleted side draws are their own class, outside the latency denominators.
- Freeze (guide 7):
  - normalisation statistics from strict TRAIN labels only, column-balanced (1 / nodes);
  - anchors from the production `V3NativeAnchor` for every legal branch;
  - validation is the validation fold of the same design;
  - the usage panel is the four presets plus about a dozen neighbours each;
  - the holdout is a new seed of the full generator, drawn after the candidate is frozen and never solved before; a consumed holdout is not blind again.
- Open-data check: no Tia Juana or ExxonMobil composition in the requests, the labels or the sidecar. Every request is compared with the Tia Juana overlay (`src/test/resources/materials/tia-juana/`) and with the five ExxonMobil compositions of the current sidecar's `compositionEdges`, read from git before N6 deletes them. Both checks must find nothing; a hit fails the set.

## 4. Envelope and the E7 check (N5, D5)

- The envelope must not exceed the labels (guide 9.2). `globalMin`/`globalMax` come from the solvable families' requests and are checked against each factor's labelled range.
- E7: mean and 3-PC basis of the strict TRAIN compositions, stored in sidecar schema 5 with those compositions. The thresholds come from the trained spread:
  - in-plane: the feed's projection lies inside the hull of the four grades in PC space, widened by the 99th percentile of the in-plane shift that the lognormal perturbation gave the TRAIN compositions;
  - the out-of-plane limit is the 99th percentile of the TRAIN out-of-plane residuals.
  - Both are measured in N5. O8's 0.3 RMS radius is a ceiling, not the value.
- The per-compound box (feature x17 to x35, 10 % span margin) stays as the second gate.
