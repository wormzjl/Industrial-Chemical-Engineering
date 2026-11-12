# The column's learned start on today's column: phase Q

Plan: [NEURAL_REQUALIFY_PLAN.md](NEURAL_REQUALIFY_PLAN.md). Model `icheme:v3-column-open-r34-u1-v2`, bundled files unchanged. Branch
`claude/neural-requalify-365a44`, cut from `5155febda`. Fresh holdout, seed 20261121: 40 preset rows, 12 blends, 300 CDU and 100 VDU
holdout rows. VDU feeds from the r34 CDU validation journal. CURRENT_ONLY (4 M poll cap) and LNN_FIRST (300,000 polls), 8 workers. Journals in `tmp/neural-requalify/`.

**Verdict: not lost. The start binds as before and does better on the learned route. No retrain.** N4's (a) (80 %, ratio 0.5
or less) now passes; (b) (half rescued) still fails, as in r34. A failed learned attempt costs at most 300,002 polls (r34 the same).

## Measured beside the reference (v2 on the r34 column, seed 20261114, 300k)

| Group | Quantity | r34 reference | Today |
|---|---|---|---|
| CDU holdout | admitted of 300 | 280 | 276 |
| | learned-route labels / share of admitted | 211 / 75.4 % | 248 / 89.9 % |
| | median polls, learned route over classical | 0.064 | 0.074 |
| | labels classical / LNN_FIRST; rescued; lost | 239 / 258; 19; 0 | 248 / 268; 20; 0 |
| Grid (40) / blends (12) | admitted; learned share | 36, 12; 66.7 %, 66.7 % | 36, 12; 88.9 %, 91.7 % |
| VDU holdout | admitted of 100; learned-route labels | 66; 24 | 90; 29 |
| | labels classical / LNN_FIRST; rescued; lost | 34 / 41; 7; 0 | 30 / 42; 12; 0 |
| All groups | rescued of classical failures | 31 of 93 (33 %) | 35 of 81 (43 %) |
| CDU, all 352 | labels classical / LNN_FIRST; their median polls | 280 / 304; 618,802 / 43,193 | 292 / 315; 502,861 / 38,311 |

## Failure classes (`tally.mjs`), all 352 CDU or 100 VDU rows
| Journal | r34 reference | Today |
|---|---|---|
| classical CDU | DRY_SUPERSAT 37, D 32, D:pollCap 2, depletedDraw 1 | DRY_SUPERSAT 43, D 11, D:pollCap 2, depletedDraw 4 |
| LNN_FIRST CDU | DRY_SUPERSAT 35, D 12, D:pollCap 1 | DRY_SUPERSAT 30, D 6, depletedDraw 1 |
| classical VDU | depletedDraw 34, D:pollCap 31, DRY_SUPERSAT 1 | depletedDraw 68, D:pollCap 1, VANISHING_PRODUCT 1 |
| LNN_FIRST VDU | depletedDraw 29, D:pollCap 29, DRY_SUPERSAT 1 | depletedDraw 56, D:pollCap 1, VANISHING_PRODUCT 1 |

DRY_SUPERSAT is `notStrict:WATER_DRY_SUPERSATURATED`. Every class is in the r34 tallies (`sense:VANISHING_PRODUCT`: r34 VDU validation 1,
train 15), so no stop. VDU failures move from the poll cap to depleted draws (a sense tag; it can overlap the other classes).

## The five criteria (plan section 3)

| Criterion | Limit | Today | Verdict |
|---|---|---|---|
| admitted CDU holdout rows | at least 260 | 276 | pass |
| learned-route share of admitted | at least 70 % | 89.9 % | pass |
| median poll ratio | at most 0.10 | 0.074 | pass |
| rescued of classical failures | at least 23 % | 43 % (35 of 81) | pass |
| labels lost | 0 | 0 in every group | pass |

## Where the plan was wrong

- "31 of 93 rescued" is the total of all four groups (65 of the 93 failures are VDU rows), not a CDU-holdout number; scored so here.
- Rescued counts rows with a learned label and no classical label; failures count only rows the classical solve did not accept. A
  converged WATER_DRY_SUPERSATURATED row is no failure but can be rescued (CDU holdout: 20 rescued of 10 failures). Passes either way.
- The VDU learned-route share (32 %, r34 36 %) is in no criterion. The probe needed no fix for W6's compound ids.
