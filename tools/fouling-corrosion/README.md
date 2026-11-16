# fouling-corrosion

Offline fit of the `corrosion` sub-record of the twelve crude cuts (`crude_pc01` to `crude_pc12`), batch
`2026-11-16-fouling-corrosion`, WP1 (plan section 6.4).

## Run

From the repository root:

```bash
node tools/fouling-corrosion/fit-cuts.mjs
```

It reads the four assays under `src/main/resources/data/icheme/materials/assays/` and prints the twelve sulfur values
(wt%) and acid numbers (mg KOH/g) and each assay's residual. It writes nothing: copy the values into the datasets'
`corrosion` sub-records by hand and bump that sub-record's `revision`.

## Method

One set of twelve values serves the four assays: non-decreasing with the cut, the lightest cut at 0.02 wt% sulfur and
0 acid, the residue between 4.5 and 6 wt% (acid 3.5 to 4.5). The script minimises the largest relative residual
(log-sum-exp smoothing, projected gradient), deterministic, then rounds to two significant figures.

## Result (2026-11-16, `corrosion-fit-r1`)

| Cut | 01-07 | 08 | 09 | 10 | 11 | 12 |
|---|---|---|---|---|---|---|
| Sulfur, wt% | 0.02 | 0.057 | 1.2 | 3 | 4.7 | 6 |
| Acid number | 0 | 0 | 0 | 0.72 | 2.5 | 4.5 |
| Fouling, m²K/W | 0 (01-05), 0.0005 (06-07) | 0.0005 | 0.0005 | 0.0005 | 0.0018 | 0.0018 |

| Assay | Sulfur spec | Fit | Residual | Acid target | Fit | Residual |
|---|---|---|---|---|---|---|
| light_crude | 0.2 | 0.283 | +41 % | 0.1 | 0.075 | −25 % |
| medium_crude | 1.5 | 1.027 | −32 % | 0.3 | 0.453 | +51 % |
| heavy_crude | 3 | 2.549 | −15 % | 1.5 | 1.572 | +5 % |
| oil_sand_dilbit | 3.74 | 2.127 | −43 % | 2.5 | 1.237 | −51 % |

The plan's 20 % cannot be met. The heavy crude's cut distribution dominates the dilbit's at every cut (more of each
heaviest tail), so any non-decreasing per-cut value gives the dilbit less than the heavy crude, against specifications
of 3.74 and 3 wt% (and acid 2.5 against 1.5). The light and medium crudes pull the other way: 0.2 against 1.5 wt% asks
for sulfur in the residue only. With the residue free up to 10 wt% the best fit is 21 % (residue 9.1 wt%).

The fouling numbers are not fitted: TEMA rows (light cuts 0, crude and heavy 0.0005, residue 0.0018); the law raises
the 0.0005 class to 0.0009 from 260 °C (`FoulingLaw.asymptote`).
