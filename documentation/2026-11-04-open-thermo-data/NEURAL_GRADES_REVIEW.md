# O8 review: the existing neural model on the generated crude grades (2026-11-05)

Work package O8 of [OPEN_DATA_PLAN.md](OPEN_DATA_PLAN.md), for decisions E6 and E7. No code change; code guide: no change. Probe `O8NeuralGradesProbe.java` (columnGates, three runs on Gradle lane 2) and the Node scripts `distances.mjs`, `relate.mjs` are in `tmp/open-thermo-o8/` with their outputs (`grades.json`, `grid.json`, `blends.json`, `seeds.json`, `distances.json`).

## 1. Expected behaviour

- A learned seed interpolates its training set. Near the ring (the six crudes and the blends between neighbours), on the same column, its profile is close to the solution. Newton then closes it in a few iterations, for fewer polls than the classical stage continuation. Far from the ring, the seed gets worse and the start falls back.
- On a 40-stage crude tower with stripping steam, the top of the solution has two features the seed must carry: off-gas (light ends cannot stay liquid at 332 K and 250 kPa) and free water in the condenser (the steam condenses there: 331 mol/s on Tia Juana).
- A composition check must refuse a feed whose light ends or principal cuts are outside what the model saw. Assay names must not take part (E7).

## 2. What the code did

Method: a test copy of the sidecar with one composition edge from Tia Juana to each grade and the global bounds lifted. The learned route runs as LNN_ONLY with a 60 s budget, so the Newton caps decide and the 100 ms large-wet cap of LNN_FIRST does not apply (`src/main` untouched). A run on the unforced bundled model gives the same polls (524,014): the forcing changes no prediction. Classical counters for (a) and (b) are O3's stored ones. All runs are at 638.15 K unless stated.

| feed | classical (polls) | learned: polls, Newton, scaled residual start to end |
|---|---|---|
| Tia Juana (on the ring) | ok 219,616 | FAIL 524,014, 42, 4.11 to 0.047 |
| light / medium | ok 549,497 / 316,325 | FAIL 48,655, 8, 10.1 to 9.98 / FAIL 122,830, 8, 11.4 to 11.2 |
| heavy / dilbit | ok 551,509 / 1,325,207 | FAIL 126,889, 8, 8.90 to 8.89 / FAIL 96,127, 8, 18.0 to 16.5 |
| medium, 10 temperatures 622 to 644 K | ok 5 (207k to 976k); FAIL 5 (631.28, 634, 636, 640, 644 K) at stage 30 | FAIL 10 of 10: 67k to 129k, 8 to 9, start 8.4 to 11.6 |
| Tia Juana, the same 10 temperatures | ok 10 of 10 | FAIL 10 of 10: 218k to 524k, 8 to 42, start 0.10 to 4.11, end 0.044 to 0.067 |
| 12 blends, Tia Juana to each grade, w = 0.25, 0.5, 0.75 (standard volume; draws, steam and PA duties linear in w) | ok 10 (197k to 1.64M); FAIL medium 0.75 (1.76M) and heavy 0.5 (307k), both at stage 30 | FAIL 12 of 12: 107k to 319k, 8 to 25, start 1.0 to 16.9, end 0.041 to 8.8 |

- The learned start was accepted 0 times in 39 runs, including the 10 runs on its own training point. Every failure is INITIALIZATION_FAILURE on `lnn/requested-state`, so the game falls back to the classical start. The learned start rescued 0 of the 7 cases where classical fails.
- In the game, the failed learned attempt adds 48k to 524k polls before the classical start. Its inference alone takes about the 100 ms cap: O7 measured 0 Newton iterations on Tia Juana. So on these columns the model never takes a Newton step, not even on Tia Juana.
- The seeds (`seeds.json`, against O3's classical profiles):
  - Tia Juana: no off-gas (0 against 27 mol/s), no condenser free water (0 against 331 mol/s), tray 2 at 357 K (classical 377 K), bottoms at 607 K (classical 576 K). The correction stalls at 0.045 on the methane balance of tray 1.
  - The grades: tray 1 is 36 to 125 K too hot (407 to 476 K against 351 to 371 K), with no free water. The model was not trained on this wet condenser lane, as the comment on `LARGE_WET_NEURAL_PROBE_MILLISECONDS` says.

Distances from the ring, for each grade (light, medium, heavy, dilbit):

- Edge L-infinity (the gate's form, limit 1e-8): 0.037, 0.017, 0.052, 0.119.
- Principal cuts, L-infinity, mole basis. The cuts are C1-C4; C5 to pc03 (normal boiling point up to 432 K); pc04-05; pc06-07; pc08 and heavier (the column's five products):
  - against the ring: 0.061, 0.008, 0.056, 0.099;
  - against the convex hull of the six crudes: 0.039, 0.0075, 0.021, 0.067.
- PCA: the first 3 components of the trained compositions, which hold 97.5 % of their variance. Distances are in units of the ring's RMS radius, 0.097 mole fraction, within the 3-component plane and out of it:
  - the grades: 0.63/0.42, 0.13/0.35, 0.18/0.71, 1.66/0.88;
  - Tia Juana: 0/0.08.
- The model's own per-compound box (feed fractions x17 to x35 of `globalMin`/`globalMax`) refuses every grade and every blend, w = 0.25 included. The grades carry 0.17 to 0.88 mol % ethane; the most the model was trained on is 0.12 mol %.
- Relation to the result: no form separates a case where the seed helps from one where it does not, because it helps nowhere. A distance only ranks how bad the seed is. Spearman rho with the starting residual, n = 17: PCA out-of-plane 0.69, box count 0.67, hull of cuts 0.63, edge 0.47, cuts against the ring 0.44.

## 3. Proposal (owner)

- E6: the model does not help on the grades, on the blends, or on its own Tia Juana ring with the current wet formulation. Its sidecar also stores the ExxonMobil compositions. **Recommendation: remove it now.** The classical start then runs alone, with no failed attempt in front of it: 0.05 to 0.5 M polls less per request when the probe budget is lifted, and none at the 100 ms cap. Retrain on open data (the grades and their blends, off-gas and condenser free water in the targets) only if a later batch wants a learned seed.
- E7 applies to a retrained model. The thresholds are relative to that model's own training set, which its sidecar stores.
  - A. Per-compound box only. Remove the edges; keep the existing `globalMin`/`globalMax` with a 10 % span margin. Applied to the current ring, A admits only Tia Juana and refuses all 4 grades and all 12 blends. It needs no new code.
  - B. Principal cuts: the 5 cut fractions, within the convex hull of the training crudes, at most 0.01 L-infinity. B admits medium, the medium blends, light 0.25, heavy 0.25 and 0.5, and dilbit 0.25 and 0.5. Its weakness: it admits medium, where the seed was among the worst (start 11.4). Adding the C1-C2 share as a sixth cut refuses everything here, as A does (ethane).
  - C. Principal components: the first 3 PCs of the training compositions. In-plane distance to the training set and out-of-plane residual each at most 0.3 ring radius (0.03 mole fraction). C admits the ring and the 0.25 blends of light, medium and heavy, plus medium 0.75. It refuses all 4 grades. It ranks seed quality best, and it follows the owner's words "principal-composition checked".
  - **Recommendation: C, together with the box A.** Store the mean, the PC basis and the training compositions in the sidecar, not assay names.
- O3's open choice: (b), rely on the learned seed, is ruled out. On the medium grade the learned seed fails at all 10 temperatures, including the 5 where the classical start converges. The classical start also stalls at stage 30 on 2 of the 12 blends, so the fragility is not specific to the medium grade's exact composition. **Recommendation: (a)**, diagnose the stage-continuation stall, with (c) as the fallback.
