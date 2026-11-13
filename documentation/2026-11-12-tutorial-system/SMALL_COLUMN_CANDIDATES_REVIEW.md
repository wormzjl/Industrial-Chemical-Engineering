# Small crude tower candidates (P4, decision D4)

Each candidate runs on the crude unit scaled to 1/64 (39.96 kmol/h, about 10 m³/h) through the simulator's run and Size all (`tools/tutorial-column`). Datasheets: this worktree's `tmp/tutorial-renders/p4/` (`column-A/B/C.png`, `columns-compare.png`); templates, sheets, results in `tmp/tutorial-p4/`. B and C are aimed at the barrel (owner's targets); A stays the 42-tray preset / 64.

| | A: 42 trays scaled | B: 20 trays | C, variation 1: 14 trays |
|---|---|---|---|
| Diameter | 1.0 m refused: no free row for draw 1 and its pumparound's draw on one tray; Size all 1.5 m | 1.0 m, flooding 74 % of the 80 % limit | 1.0 m, 72 % |
| Height | 34 blocks | 21 blocks | 17 blocks |
| Mass % (barrel %) | overhead 7.2 (18), kero 11.2 (11), diesel 14.1 (24), gas oil 6.6 (28), bottoms 60.9 (19) | overhead 17.5 (18), kero 11.1 (11), diesel 23.6 (24), bottoms 47.8 (47) | overhead 18.2 (18), middle 34.9 (35), bottoms 46.9 (47) |
| Gaps, K | −38, −47, −67, −23 | −47, −58, −14 (fails: A −38, −47) | −22, −6 (pass) |
| Reflux; heater outlet; heater / condenser / PA, MW | 4.17; 365 °C; 1.05 / 0.49 / 0.74 in 3 | 1.5; 390 °C; 1.35 / 0.71 / 0.59 in 2 | 1.5; 390 °C; 1.35 / 0.74 / 0.59 in 1 |
| Steam; fuel | 335 kg/h; off-gas covers 1.64 MW fired | 259 kg/h; off-gas 0.56 of 1.94 MW, + 122 kg/h bottoms | 259 kg/h; off-gas 0.52 of 1.94 MW, + 124 kg/h bottoms |
| Real fails after Size all | S-6 two-phase 2.1 m/s (< 3); pa 2 return liquid drop (load 1.03) | S-6 2.7 m/s; OFFGAS drop 0.124 bar/100 m (< 0.11) | S-6 2.7 m/s |
| Larger than needed (yellow) | 11 items; all green from 301 kmol/h, 3.0 m | 8; from 956 kmol/h, 5.0 m (pa 2 cooler, 0.107 MW) | 6; from 195 kmol/h, 2.5 m |
| Start, polls; plot | learned declined (1); classical 237k, warm 470k; fits, 142 blocks | declined; 365k, 10k; fits, 133 | declined; 215k, 8k; fits, 124 |

A's missing naphtha goes to the kerosene draw: of the crude's naphtha (20 to 180 °C) the overhead takes 35 %, kerosene 58 %.

V-101 (sheet choice, all three): 3.20 bar (was 2.80). Path V-101 to T-101 green with its 20 % spare: spare 0.34, 0.38, 0.39 bar (A, B, C). Cost: no pump head (P-101 stays at 70.7 m; it discharges at a fixed 14 bar and V-101 lets down from there). A valve is isenthalpic, so the feed's enthalpy and its flash on the feed tray are unchanged; the 0.1 m transfer line runs 0.4 bar higher with less vapour. The feed reaches the column at 346 °C (A), 372 °C (B, C).

Reaching the targets (14 recorded solves each, `tools/tutorial-column/tuning.json`; no layout variation):
- At A's reflux 4.17 the overhead stays at 7 to 11 %, even at a 410 °C heater outlet with the pumparounds halved: the vapour reaching the top is the feed's heat less the pumparounds', and distillate = vapour / (R + 1). Reflux 1.5 (twice the overhead, the same top vapour) gives 18 %.
- Heater outlet 390 °C: H-101 sized green (43 % headroom). Its limit raised to 4 MW: the scaled 1.41 MW capped t6 to t4's result.
- Limits: pumparounds at a quarter of A's duty dry the draw trays (draw wall, no solve). A 430 °C outlet stalls the column's continuation (no solve). 410 °C at reflux 1.5 overshoots: bottoms 36 to 40 %.
- C as first asked hits the draw wall (15.54 kmol/h asked of 14.07 kmol/h liquid on tray 6). Variation 1, PA 7 to 5 above the draw, converges. For B and C each pumparound draws on a tray of its own, checked against the 1.0 m shell before solving.

Yellow lamps "larger than needed" (owner's options, nothing built). Feed and diameter at which the smallest size turns green (diameter √(factor) m, up to 0.5 m), for C:
- P-101 at 54 % of best flow: 52 kmol/h, 1.5 m. Reflux pump 26 %: 108 kmol/h, 2.0 m. P-102 22 %: 128 kmol/h, 2.0 m.
- Condenser 4.7 times the surface needed: 92 kmol/h, 1.5 m. E-101 6.6 times: 132 kmol/h, 2.0 m. PA cooler 9.8 times: 195 kmol/h, 2.5 m.
- So the smallest all-green C is about 195 kmol/h (4.9 times the tutorial's feed), a 2.5 m tower. The plot was not checked at that size.
- Against that, a catalogue that makes the 40 kmol/h C green:
  - Pumps (rated flow = 3 m/s in the discharge; the smallest, 0.05 m, is 21.2 m³/h): a 0.04 m pump (13.6 m³/h) for P-101's 11.4 m³/h, and a 0.025 m pump (5.3 m³/h) for the reflux (5.5) and P-102 (4.7). A's 2.0 m³/h P-102 would need about 0.018 m.
  - Exchangers (surface 75 d² L; the smallest, 0.5 m × 2 m, is 37.5 m²): C needs 3.8 (PA cooler), 5.7 (E-101) and 8.0 m² (condenser). A 0.25 m shell gives 4.7 m² with 1 m tubes up to 11.7 m² with 2.5 m tubes. Its drops were not checked.

Recommendation: C, variation 1. It meets all three targets within 0.5 points, stands at 1.0 m with Size all's agreement, is the shortest (17 blocks), has the fewest units (23 lines), and both gaps beat A's. Its one real fail, S-6, is shared by all three. B now also stands at 1.0 m, but its gaps fail against A's and its off-gas line is short of its drop limit.

Open for the owner (D4):
1. Which candidate.
2. The yellow "larger than needed" lamps: smaller pumps and shells in the catalogue (production code), a larger tutorial unit (about 195 kmol/h, 2.5 m), or keeping them yellow with a tutorial note.
3. Fuel for B and C: the off-gas covers only 27 to 29 %. The rest is about 3 % of the bottoms through a burner (it takes liquid fuel). That needs a bottoms line to the heaters, which the sheet does not draw.

Not right, seen on the way:
- S-6 (H-101 to V-101): Size all keeps a 0.1 m bore at 2.7 m/s against the 3 m/s minimum. By the area ratio, 0.075 m reads 4.8 m/s. Why Size all does not take it is not traced.
- The 8 m `medium_crude` preset leaves 61 % of the crude in its bottoms and 5 % naphtha (barrel: 47 % gas oil and residue, 17 % naphtha).
- The learned start declines all three after 1 poll. A is intensively the same as the 8 m tower that it accepts.
