# Junction tables review: the balance reads a flashing junction's density

Base 2ab4690e9. The owner chose option A of [CARRIED_CRYSTALS_REVIEW.md](CARRIED_CRYSTALS_REVIEW.md) and its proposal 1 (i), liquid junction tables, built in this session. B and C are rejected. **(i) alone holds cold seed 651, so the fix also carries a two-phase table point the fluid phases refuse. That part went beyond what the owner approved and changes A's statement that the tables are fluid-only; the owner kept it (2026-11-03).** Campaign below. Not run: final lane, whole `slowGates`, GameTests. Code guide, CHANGELOG, version: wait for the owner's word.
## Expected behaviour
A 5 cm line opens LNG at 150 K and 2.77 MPa (bubble near 1.1 MPa, 2.7 % CO2) to a void through a junction. The generator's cap is 80 kg/s at 100 m/s. The outlet's cap is density x 100 m/s x area at the junction's own pressure, and the density falls about 60 times between the bubble pressure and 1 atm. So the junction stands where its density carries what arrives: a little under the bubble pressure. The next junction, feeding a 10 cm vent, stands lower, at the density that carries its share. The solved world on the fix agrees, three slices on: 892 kPa and 239 kg/m3 at the first junction, 392 kPa and 41 kg/m3 at the second.
## What the code did
`BalanceNet` reads a table of density against the junction's pressure only for a junction stated two-phase, and only if the fluid-only flash answers all five points. Everything else keeps its stated density at every pressure.
- 907 (the owner's item): the mint at 1.35 MPa is liquid, so four junctions fell to 1 atm still liquid, 40 K superheated, and flashing them one at a time stalled Newton.
- 651's job 88, with liquid tables only: round 0 put the first junction at 980 kPa (337 kg/m3, right) and the second at 972 kPa, because the second has no table: stated 7 kg/m3 at 108 kPa, fluid-only refused at the hull's 93 kPa. In round 1 both are two-phase at 980 kPa and table-less, since the fluid phases refuse their 93 kPa point. The first read 337 kg/m3 at every pressure and fell to 225 kPa, where its true 17 kg/m3 carries a twentieth of the flow. Newton had to climb back through the bubble point, and stalled at residual 56 down to a 1e-7 s step: 7 holds, the world stops at job 96.
- Without any new table the same job's balance also reaches 205 kPa: the 0.05, 0.025 and 0.0125 s attempts fail and 6.25 ms converges. So job 88 is a stale-density defect that liquid tables exposed, not one they made.
## What was built
`PassiveStepSolver.BalanceNet`, two rules, one statement: a junction reads its density at its own pressure wherever it flashes.
1. A liquid junction has a table from the hull's low end up to its stated pressure, built with the step's flash, stopped at the first point that holds no vapour. From that point up (`flashFrom`) it reads its stated data, so a junction that stays liquid balances bit for bit. If the lowest point is already liquid there is no table: one flash, cached.
2. A two-phase junction reads the fluid phases first, as today. A point they refuse falls back to the carried flash, where it used to drop the whole table.
Rule 2 is the narrow form of C: 244's measured reason on that site binds and stays in the comment, and the points the fluid phases answer keep their numbers. 244 stays clean, so 244's stall under C sits at a point the fluid phases answer, which narrows the open item.
## Forms
At most three: t1 (rule 1 only), t2 (rules 1 and 2), and no third was needed.

| Seed | (a) + G: worst ms / holds / total M | t1 | t2, built |
|---|---|---|---|
| 651 | 516 / 0 / 126.9 | 750 / **7** / 7.5, stops at job 96 | 531 / 0 / 109.8 |
| 768 | 219 / 0 / 21.6 | 234 / 0 / 30.7 | 266 / 0 / 30.7 |
| 673 | 422 / 0 / 27.5 | 750 / 0 / 56.6 | 297 / 0 / 67.9 |
| 183 | 359 / 0 / 87.9 | 328 / 0 / 89.1 | 422 / 0 / 135.0 |
| 683 | 234 / 0 / 8.1 | 422 / 0 / 35.7 | 438 / 0 / 35.7 |
| 244 | 750 / 0 / 186.1 | 625 / 0 / 186.3 | 609 / 0 / 218.1 |
| 475 | 312 / 0 / 49.1 | 234 / 0 / 62.3 | 250 / 0 / 67.4 |
| 855 | 391 / 0 / 16.2 | 94 / 0 / 16.1 | 109 / 0 / 26.9 |
| 907 | 984 / **19** / 14.5, dead | 375 / 0 / 133.1 | 812 / 0 / 362.8 |

All nine on t2: 0 holds, no job over 2 s, serial at the raised budget. Worst job 907's 812 ms, 3.2 M checkpoints: 2.5x under the budget, 1.3x after the measured 1.9x machine spread. A fresh build of the final code gives 651 and 855 their t2 checkpoints exactly.
Final states against (a) + G: 651, 768, 673, 683, 475, 855 within 3 Pa, 244 within 2 %. 183 505 kPa, 13.5 kg (a11 448 kPa, 12.5 kg; today 542 kPa). 907 lives: tanks 371.0 + 30.2 kg against today's 371.0 + 36.5, 473 against 386 kPa. No world changed regime.
## Cost
The budget is per job, so this does not block, but run totals rise 1.2 to 4.4x (651 falls 13 %), and 907 runs 25x as a live world. Rule 1 alone is 683's and 768's rise; rule 2 is 183's, 855's and most of 907's. A table is keyed by the state, so a junction whose state changes rebuilds it at every balance, up to five flashes; a fluid-only refusal costs 12469 kernel evaluations on top. Not split further. If the campaign shows a new budget hold, look here first.
## Gates
New: `BalanceTablesTest`, 3 tests. A liquid junction under a 1.35 MPa line stands at 965 kPa (726 kPa with the rule off, superheated). A liquid over the whole hull has no table. A two-phase junction at 1 atm stands at 1546 kPa (2766 kPa with the rule off). With both rules off, all three fail.
Passing on the final code, 134 tests in 21 classes: `BalanceTablesTest`, `JunctionStartFormTest`, `PassiveStepSolverTest`, `HoldClassWorldsTest`, `JunctionSeedingGateTest`, `ExtremeTopologyIslandTest`, `CarriedCrystalsTest`, `CrystalFlushBudgetTest`, and the 13 others that reach the balance. No expectation changed. The printed junction lines of the final lane were not read.
## Campaign
At eb9ad31cb, 1000 seeds, 8 workers, alone on the machine, `refused=0` on every seed. Cold random 989 clean (975 on the base lane), seed time 1281 s against 2592. Bundled random 994 (992 and 993), no new held seed, 157 and 707 gone. Bundled defaults 1000.
Cold, against the base lane: 17 held seeds gone (6, 29, 110, 244, 430, 431, 475, 651, 683, 787, 788, 833, 855, 907, 919, 957, 994). 8 hold on both sides; 792 and 206 first hold earlier (ticks 1112 against 1399, 2584 against 3284). 3 are new; all three are clean with no tables, so the tables made them.
- 181 and 397 (class E5), liquid tables: clean with rule 1 off, held with rule 2 off. A tank that has never held fluid fills from a junction the table now holds at its bubble pressure, just above the tank's 1 atm. Expected: the tank is laid out for what arrives. Code: `arrivingPhases` reads the rate point carried into the step, whose flow into the dry tank is 0 while the pipe's law gives 0.94 kg/s (181). The tank stays laid out dry, Newton delivers 0.18 kg/s, and the fluid has no liquid slot: the tank's temperature runs to 47 K and every step down to 1e-21 s stalls. In 397 the junction stands within 10 Pa of the tank and the law gives 2 g/s.
- 118 (class F), carried fallback: clean with rule 2 off, held with rule 1 off. Replayed alone at the real budget it holds 12 times from job 110; at a raised budget, the interval limit: 505 steps accepted, 518 refused by the equation gate, 0.14 of 0.8 s advanced. This is open item 5 of the batch, the junction solid row. The column lane also held 118 by budget.
Scratch prototype, not on the branch (`carried/hook-p.pl`): a dry vessel's arrival reads the law where the carried flow is 0. 181 clean, 651 and 907 unchanged to the checkpoint, 397 and 118 unchanged. So the arrival is the right site for 181, and 397 needs a form that also keeps a very small arrival.
## Risks
- Reach. Rule 1 acts in any world with a hydrocarbon liquid junction whose hull reaches its bubble pressure, warm ones too, so the final lane can move references. Rule 2 acts only where the fluid phases refuse, in the cold CO2 window.
- The table has five points in ln P and the bubble point is no breakpoint: 651's 363 kg/m3 at 980 kPa against a true 337. A start needs no closer.
- A two-phase table can mix fluid-only and carried points, so it can step where the source changes. Nine worlds pass on it; no gate pins it.
## Open
Why 244 holds on carried points the fluid phases answer. The arrival into a dry tank (181, 397): proposal for the owner, nothing built. 118's equation gate. Tools: `tools/dry-ice-cost/carried/` (`hook-t.pl`, `hook-p.pl`, `traceT.sh`, `t1-form.patch`), `tools/dry-ice-cost/campaign/`. Logs: main checkout, `tools/dry-ice-cost/logs/`, `junction-tables-logs-2026-11-03.tar.gz` and `campaign-logs-2026-11-03.tar.gz`.
