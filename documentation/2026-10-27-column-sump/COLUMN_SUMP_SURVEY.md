# Column sump: engineering references

Batch `2026-10-27-column-sump` · 2026-10-27 · the height from a tray column's bottom tangent line to its bottom tray, for the game's `ColumnTrays.bottomSpace`. Tags: **[checked]** fetched and read in this task; **[agent]** read by the research subagent (opus) from the fetched page; **[snippet]** seen only in a search result (the page is behind a bot check); **[derived]** computed here.

## 1. The stack under the bottom tray

| Item, bottom up | Value | Source |
|---|---|---|
| Low liquid level (LLL) over the bottom tangent | 0.15-0.3 m | stripper drawing (section 2): lowest level tap 0'-6" [checked] |
| LLL to high level (HLL): residence time | 5-15 min HLL-LLL; Kister: 1 min (thermosiphon, no level control) to 5-7 min (product to a remote unit) | eng-tips 159905, 340445 [snippet] |
| Level rise per minute | 0.05-0.5 m/min, about 0.2 typical: a liquid flux per m² of column area, so the same at every diameter | [derived] from Mukherjee's 15-70 m³/h per m of weir [agent] |
| HLL to the reboiler return's bottom | ≥ 0.3 m | Mukherjee, Chem. Eng. 2005, https://www.scribd.com/doc/35917282/Tray-Column-Design [agent] |
| Return's top to the bottom tray | ≥ 0.40-0.45 m; never tangential | same [agent] |
| Reboiler return size | the largest side nozzle, 0.13-0.17 D (16" on 2.44 m; two 24" on 4.72 m) | sections 2 and 3 |
| Whole bottom allowance, ~0.9 m (3 ft) tower | 6 ft (1.83 m) "for liquid level and reboiler return"; 4 ft at the top | Walas via https://www.rccostello.com/helpfulhints.html [checked] |
| Whole bottom allowance, generic | 3 m "bottoms surge capacity"; 1.27 m at the top | Perry's 9th ed. via BioSTEAM `compute_tower_height`, https://biosteam.readthedocs.io/en/latest/_modules/biosteam/units/design_tools/column_design.html [checked] |
| Forum rule | 0.06·N_trays + 2.0 m (3.8 m at 30 trays, D 1.4 m); return ≥ 1 tray spacing under the bottom tray, ≥ 1 ft over the highest level | https://www.cheresources.com/invision/topic/30422-height-of-distillation-column-de-butanizer/ [agent] |

The mod's 1.2 m top space (`ColumnTrays.TOP_SPACE`) is the top half of the same rule set; its bottom half is 3 m, not the 2 m the column had.

## 2. Drawings and dimensioned examples

1. **Stripper, piping-design course** (vertical vessel orientation), https://pipingdesigners.com/tools-training/section-8c-vertical-vessel-orientation [checked]. ID 8'-0" (2.44 m), skirt 20 ft, 35 single-pass trays; bottom tray 35'-10" (10.9 m) over the bottom tangent; reboiler return N2 16" at 29'-3" (8.9 m); gauge bridle and transmitter taps 0'-6" and 25'-0"; manway M1 24" at 2'-0"; bottoms 10" and reboiler feed 14" through the bottom head. A vertical thermosiphon (42" shell, 24 ft tubes) needs its top tubesheet at the high level, hence the 4.5 D sump. The page's own elevations for B and N1 do not reconcile with the tangent (a dropped sign).
2. **Kister, Chem. Eng. Aug 2017, deethanizer, Fig. 2**, https://www.chemengonline.com/reboiler-circuit-debottleneck-no-hardware-changes/ [agent]. D 15'-6" (4.72 m), preferential baffle, two 24" returns about 0.6 m over the baffle; stalled level 3.0-3.7 m over the tangent.
3. **Sloley, Zygula, Kolmetz, AIChE 2001, Figs 9-11 and 28**, https://www.klmtechgroup.com/PDF/Articles/articles/TroubleshootingPracticerevkk.pdf [agent]. Once-through and recirculating thermosiphon bottoms; a level at the return entrains liquid onto the bottom tray; hold the level 15-30 cm under the return.
4. **Tower elevation example**, https://www.pipingengineer.org/distillation-tower-elevation-and-support/ [agent]. D 1.2 m, T/T 18.3 m, 2:1 heads, straight skirt; NPSH 1.8 m, operator clearance 2.1 m set the elevation.
5. **FRI/Kister AFPM webinar 2020**, https://www.afpm.org/system/files/docs/events/ReboilerCircuitsPresentation_4-30-2020.pdf [agent]. About 20 bottom arrangements without dimensions: preferential baffle, trapout, kettle, constant-head baffle.
6. **OGJ case** [snippet]: 6'-6" (2.0 m) from the tangent line to the return's centre line was too little head. https://www.ogj.com/home/article/17242112/technology-simple-engineering-changes-fix-product-recovery-problems

## 3. Wider or narrower bottoms

- A sump wider than the trays is rare: one anecdote (30 min holdup, split by a baffle) [snippet, eng-tips 413389]; the usual answer to surge is a separate drum. Not adopted.
- Narrower bottoms are common in hot residue service, to cut residence time: crude towers' stripping shroud (PTQ Aug 2021, https://www.digitalrefining.com/article/1002647/maximising-stripping-section-performance [agent]); vacuum tower boots about 0.5 D [snippet]. The mod already draws one diameter per column; a boot is a separate design question.

## 4. Seen from outside at the sump

Level bridle or gauge spanning LLL-HLL; the reboiler return, the largest side nozzle; the reboiler draw and bottoms leaving through the bottom head inside the skirt; a 24" manway near the tangent; stripping steam between the liquid and the bottom tray (crude, vacuum) [agent].

## 5. Turned into the game's rule

Stack [derived]: LLL 0.3 + span 1.5 (about 7 min at 0.2 m/min) + 0.3 + the game's return (`returnOd` = 0.27 D to the next 5 cm, at most 0.8 m) + 0.45 = 2.55 m + return (2.7 to 3.35 m), to the next whole metre so the bottom tray stands on a block face: 3 m for D ≤ 1.5, 4 m for D 2 to 8. Half the diameter where that is more (D > 8): a wide column's 2:1 head holds πD³/24 by itself (67 m³ at D 8, about 15 min of a 100 kbbl/d crude unit's residue [derived]), so physics asks for no more, and the earlier rule keeps those columns' nozzles in place. The research subagent's looks-driven alternative, max(3, 2 + D/2), is shown in `figures/sump-before-after.png` and was not taken.
