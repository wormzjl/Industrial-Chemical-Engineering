# C1 measurement review (column operation)

Batch `2026-10-28-column-operation`, WP C1 (plan section 14). 2026-10-29, branch `claude/column-operation-5c3005` at a4397868 + the bench. One sweep alone on the machine, C++ outer pass: 3 cold bases, 60 warm solves, 45 s. Bench and TSV: `tools/column-operation/`. No product code.

**Designs** (all `tjl20_methane_nitrogen`, 40 trays, 8 m, hydraulics on): *dry* = preset `tia_juana_nitrogen`; *wet* = the same at a 310 K condenser and reflux 8 (owner hint); *kettle* = no steam, reboiler 15 MW (25 MW does not converge cold). Cold bases: 1.7, 3.5 and 2.4 s. "Error" below = the base table (its recoveries, product T and P) applied to the new input, against a new solve: the largest relative product flow error, and the condenser duty closed on the table's products.

## 1. Homogeneity (fact 1)

- Expected: exact at a prescribed drop; with hydraulics the sump moves with the tray loads.
- Prescribed 0.7 kPa drop, alpha 0.5 and 1.2: the scaled table equals the new solve to 6e-13 (flows), 0.000 K, condenser duty ratio 1.000. Fact 1 holds exactly.
- With hydraulics, alpha 0.5: flow error 1.0 to 1.2 %; sump -2.9 / -4.9 / -7.2 kPa and -0.1 / -0.5 / -0.8 K (kettle / dry / wet); Qc/(alpha Qc0) 1.006 to 1.012. Alpha 1.2: 0.6 %, +1.5 to +4.6 kPa, +0.06 to +0.5 K. Half the plan's estimate (-10 kPa, -1.4 K).
- Band: keep |alpha - alpha_ref| > 0.2 (error about 0.5 %).

## 2. The table against drift (fact 2 corrected)

- Expected (plan): recoveries drift slowly, the table errs in the second order.
- Measured: the error is first order. Feed T +-2 K: flow error 2.2 to 4.9 %, product T 1.4 to 3.2 K, condenser closure 0.1 to 3.7 %. +-10 K: 11 to 31 %. Composition 1 % toward WTI or Cold Lake (sum |dz| = 0.4 to 0.6 %): 0.5 to 1.8 %; 5 % (sum |dz| 1.9 to 2.8 %): 2.6 to 10 %.
- Physics: the feed's enthalpy sets its vapour fraction in the flash zone, and with it the cut points. Components far from a cut keep recoveries near 1 or 0; the products near a cut consist of the components whose recoveries move, linearly in the drift. Conservation is exact anyway (by construction); only the product split lags.
- The kettle is worst on enthalpy: its condenser is small (12.6 MW), so 2 K of feed (0.8 MW) is 6 % of it (Qc/Qc0 0.95 to 1.05).
- Proposed bands (section 6), for a product error of about 2 % or less:

| Trigger | Plan | Proposed | Error at the band |
|---|---|---|---|
| Feed composition, sum \|z - z_ref\| | 1 % | 0.5 % | 0.5 to 1.8 % |
| Feed specific enthalpy | 2 K | 1 K | 1.1 to 2.5 % |
| Steam ratio (rule B) | 2 % | 2 % | 0.7 to 1.1 % (linear from -10 %) |
| Throughput | 0.2 | 0.2 | about 0.5 % |
| Energy closure, booked Qc vs alpha x solved | 5 % | 3 % | (kettle: 2.4 % at 1 K) |

- Affordable: a triggered solve costs 0.05 to 0.57 s; at most one per 30 s is at most 2 % of one worker.

## 3. Warm solves

- 60 of 60 accepted on the warm route (`warm/aggressive`, 0 to 7 Newton iterations), none fell back to cold. Times: dry 0.17 to 0.57 s, wet 0.17 to 0.52 s, kettle 0.05 to 0.20 s.
- Wet (Q10): the owner's cold condenser with high reflux puts tray 2 15.6 to 30 K below its water dew point in 8 of 8 variants. V3 publishes it as a dry tray with the dew-point warning (owner decision); no modelled free-water tray formed in 15 network-package variants. Those columns warm-start like dry ones. Q10: nothing to extend.
- C5 detail: a seed with modelled free water is refused by the warm route, and `calculateWithStrategy` with a seed then runs the strict cold chain, not the fast first solve. C5 passes no seed for such a table.

## 4. Rule B's cost: decision B

- 12 solves (steam short 10/25/50 % on dry and wet; condenser +5 and +10 K on all three): all accepted warm. Median 0.33 s, 95th percentile 0.52 s (bar 1.5 s and 5 s).
- **Decision: rule B, with rule A where a solve fails** (owner Q1). C6 implements it.
- The rules differ in numbers: steam short 50 % gives products 21 to 28 % off the scaled table and a 13 to 15 K hotter bottoms; condenser +10 K: 43 to 53 % (distillate to overhead vapour).

## 5. S2 or S3 (Q6)

- S3 exists to rescue failing warm solves of duty-specified (kettle) designs. C1 saw none: 23 of 23 kettle warm solves accepted, feed T +-10 K and condenser +10 K included. S3 is not needed.
- Recommended: S1 in this batch; S2 (D/F or B/F as a player choice) later, as its own solver batch, only if the owner wants product-rate control.

**Limits.** Product enthalpies by the V3 package (PR78 flash, Shomate water), which closes the solves' own balances to 0.01 to 0.2 %; the game books them with the network model (plan section 16). One timing run; no Java-digit rerun.
