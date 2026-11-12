# Small crude tower candidates (P4, decision D4)

Each candidate runs once on the crude unit scaled to 1/64 (39.96 kmol/h, about 10 m³/h), through the simulator's run
and Size all (`tools/tutorial-column`). Datasheets: `tmp/tutorial-renders/p4/` of this worktree (`column-A.png`,
`column-B.png`, `column-C.png`, `columns-compare.png`); templates, sheets and result files in `tmp/tutorial-p4/`.

| | A: 42 trays scaled | B: 20 trays | C, variation 1: 14 trays |
|---|---|---|---|
| Diameter at 1.0 m | shell refused (no nozzle row for draw 1) | floods 81.5 % of the 80 % limit (yellow) | green, 78 % flooding, 9 % headroom |
| Size all diameter | 1.5 m (flooding 31 %) | 1.5 m (34 %) | 1.0 m (73 %) |
| Height | 34 blocks (30 m shell) | 21 blocks (17 m shell) | 17 blocks (13 m shell) |
| Products, mass % | naphtha 5.4, kerosene 11.2, diesel 14.1, gas oil 6.6, bottoms 60.9 | naphtha 7.0, kerosene 11.8, diesel 14.5, bottoms 65.2 | naphtha 7.0, kerosene + diesel 26.3, bottoms 65.2 |
| Gaps, K (5 % less 95 %) | −38, −47, −67, −23 | −35, −48 (A −47), +10 | −19, +13 |
| Fired heater / condenser / pumparounds | 1.14 / 0.49 / 0.74 MW in 3 | 1.21 / 0.62 / 0.74 MW in 2 | 1.18 / 0.62 / 0.74 MW in 1 |
| Steam | 335 kg/h | 371 kg/h | 371 kg/h |
| Not green | 11 units, 2 lines, 1 path | 8 units, 1 line, 1 path | 7 units, 1 path |
| Start, counters | learned start declined (1 poll); classical start 597k polls; Newton 4 | declined; 183k; Newton 3 | declined; 100k; Newton 3 |
| 16 × 16 plot | fits (142 blocks of footprint) | fits (136) | fits (127) |
| Build: units, pipe ends, utilities (steam, fuel, cooling water, FE) | 15, 51, 4 | 13, 44, 4 | 11, 37, 4 |

Choices for B and C:
- Draws: B takes A's kerosene and diesel rates on trays 6 and 11. These are A's draw trays 11 and 19, with 4 trays per section instead of 7 to 9. C takes both rates together on tray 6.
- Pumparounds: in B, 10 to 8 between the draws (A's PA 1 + PA 2) and 13 to 12 under the diesel draw (A's PA 3). A 1.0 m shell has no nozzle row on 14 or 15 (`ShapeCheck -Dsearch=true`). C takes all of A's 0.740 MW in one pumparound.
- Feed: 16 in B and 11 in C, with 3 and 2 stripping trays. A 1.0 m shell needs the pumparound draw 2 trays above the feed.
- Steam: A's 0.0614 kg per kg of bottoms, on A's residue plus its gas oil.

Variations: C as asked does not converge. The draw on 6 asks 15.54 kmol/h of the 14.07 kmol/h liquid that reaches the tray: the draw wall, with only the condenser's reflux above. The fresh audit rejects it. Variation 1 moves the pumparound to draw from 7 and return on 5, above the draw, and converges. No second variation was needed.

Acceptance:
- Audit and plot: all three pass.
- Lamps: all three fail, on the same three causes:
  1. At 10 m³/h every pump runs at 9 to 61 % of the smallest pump's best flow (`PUMP_RATED`, 70 to 120 %).
  2. Every shell exchanger has 3 to 13 times the surface it needs (`EXCHANGER_OVER_NEED`, at most +100 %). Causes 1 and 2 are limits of the catalogue.
  3. Path V-101 to T-101 is red (−0.02 to −0.10 bar): V-101's 2.80 bar does not carry the feed through the 0.1 m transfer line that the feed nozzle allows.
- Diameter: A and B fail at 1.0 m; C passes.
- Gaps: A and C pass. B fails by 1.2 K (kerosene to diesel −47.9 K against A's −46.7 K).
- What the world supplies: all three pass.
  - Steam: a fired heater raises steam from water in the world (`HeatSideTopologyTest.aFiredCoilRaisesSteamAboveSaturation`), with vanilla water through the Fluid converter. That needs one more heater and pump, not in the plot. The boilers (grate, fluidized bed) are shape only and are not needed.
  - Fuel: the off-gas, 127 to 157 kg/h, against about 135 kg/h for both heaters. Cooling water: a cooling tower. Power: FE.

Recommendation: C, variation 1. It alone stands at 1.0 m with Size all's agreement; it is the shortest (17 blocks), has the fewest units and pipe ends, and both its gaps beat A's ("simpler where the numbers allow"). Its middle cut is kerosene and diesel together: the guide names it a middle distillate. B can follow as a second unit at 1.5 m.

Open for the owner (D4):
1. Which candidate.
2. The yellow lamps at 10 m³/h: a smaller pump and exchanger in the catalogue (production code), or a larger unit (1.5 m, about 90 kmol/h, which still leaves the reflux pump yellow).
3. V-101 set at about 3.0 bar on the small sheet.

Not right, seen on the way:
- The 8 m `medium_crude` preset leaves 61 % of the crude in its bottoms and makes 5 % naphtha. A textbook atmospheric tower leaves 47 % (gas oil and residue).
- The learned start declines A after 1 poll, although A is intensively the same as the 8 m tower that it accepts.
