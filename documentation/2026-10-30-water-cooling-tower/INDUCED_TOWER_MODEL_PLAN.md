# Fan tower model plan

Built 2026-11-02 on `claude/codex-water-cooling-tower-review-09c170`, the decisions taken as recommended, with baked sprites.

## Expected: what a real cell looks like
The game's tower is induced draft: the fan sits on top and pulls air up through the fill. Forced draft puts the fan low at the side, blowing into a sealed plenum, and is used on small factory units ([Marley MCW](https://spxcooling.com/wp-content/uploads/MCW-TS-24-1.pdf), [Evapco LSTE](https://www.evapco.com/products/cooling-towers-factory-assembled/lste-cooling-tower)). Large towers are mostly induced ([patent 12055352](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12055352)). Photo captions mix the two: [this drawing](https://commons.wikimedia.org/wiki/File:Industrial_Cooling_and_Pipeline_Facility.png) is captioned forced draft but shows a top fan, so this plan models induced draft.

Reference views: [long concrete bank, Marbach](https://commons.wikimedia.org/wiki/File:Kraftwerk_Marbach_III_K%C3%BChler.jpg), [two-row bank, Neckarwestheim](https://commons.wikimedia.org/wiki/File:Zellenk%C3%BChler_GKN-1.jpg), [low bank, Malzenice](https://commons.wikimedia.org/wiki/File:MalzeniceCCPP.JPG), [rooftop package cells](https://commons.wikimedia.org/wiki/File:Induced_Draft_Cooling_Tower_for_HVAC.jpg), [louvers and casing up close](https://commons.wikimedia.org/wiki/File:Cooling_Tower_Fill_Material.jpg).

| Reference | Plan, m | Height, m | Other |
|---|---|---|---|
| [Marley NC8402](https://www.lenntech.com/Data-sheets/Marley-TECH-NC-12-L.pdf) | 2.6 × 4.3 | 3.1 | factory cell |
| [Evapco AT 112-418](https://www.equipt.com/listings/137388-used-2010-evapco-at-112-418-cooling-towers-in-california) | 3.7 × 5.5 | 5.0 | 3.5 m fan on the [AT12-182](https://genemco.com/cdn/shop/files/HDSC190evapcocoolingtower.pdf); louvers 1.2 m by 0.8 m |
| [BHEL RCC tender](https://tenders.bhel.com/sites/default/files/t116-details-of-cooling-towers-1489036519.pdf) | 5 × 11 (as read) | 6.5 to deck + 2.1 stack | 0.9 m fill |
| [Fluor tower](https://dochub.sasri.org.za/bitstreams/13618c16-7c1d-44d1-bdc9-719884ce683b/download) | 14.6 square | 15.8 to stack top | width about equals height |
| Field cells ([patent 5811035](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/5811035)) | 11 to 18 square | | fans to 11–12 m |
Stacks run 2–5.5 m, about 0.3 to 0.45 fan diameters ([listing](https://zhonghuacooling.en.made-in-china.com/product/HwbfaJTjsqRQ/China-Cooling-Tower-Fan-Stack.html), [12055352](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12055352)). Fan rings have an eased inlet ([Marley F400](https://spxcooling.com/wp-content/uploads/F400-13.pdf)). Air enters through louvered faces that hold the spray in ([Evapco](https://www.evapco.eu/media/257289)). Most of these numbers come from search snippets and listings, not read tables; the Marley PDFs could not be opened here. Treat them as proportions, not ratings.

## What the code does
`CoolingTowerDesign.bank` draws a plain box on four legs over a 2 m tray. The side of the box has no air inlet detail, the fan sits in a 1 m cowl under a guard, and the fill is 2–6 m (half the width by default, about twice a real film fill). A 4 m cell is 10 m tall, 2.5 times its width; real cells are 1 to 1.5. Width stops at 8 m, a third row has no face for air to enter, and the hot water pipe is a bare stub. `height == fillDepth + 8` and the literals 4, 5.5 and 7 hold the layout.

## Proposal
**Dimensions, in `CoolingTower`, one formula.** Rim 1.5; inlet `max(2, ceil(.3 w))`; fill 1–3 m, default 2 (the calibration point, so `TowerTransfer`'s fit stays); spray and drift zone 1; plenum `max(1, round(.2 w))`; stack `.28 w`; fan `.6 w`, which leaves 1.17 m of deck round the bell at 8 m (owner: room to walk round it). The stack follows the cell, not the fan: its height is the fans-off draft column. Water depth stays two thirds of the rim, so 1 m.

| Width w | 8 | 10 | 12 | 14 |
|---|---|---|---|---|
| Height today | 12 | – | – | – |
| Deck, new | 9.5 | 9.5 | 10.5 | 12.5 |
| Top of stack, new | 11.7 | 12.3 | 13.9 | 16.4 |
| Height / width | 1.5 | 1.2 | 1.2 | 1.2 |

- **Shell, drawn whole.** Reuse the natural tower's path (`ShapeRasterizer.towerShell`, `NaturalTowerRenderer`): one cached mesh of whole panels about 4 m wide, instead of plates cut at every block. The casing comes down to the rim. Louvers, ribs and seams are sprites baked from a height model (`tools/cooling-towers/relief/`). Normal maps were tried there: the game's solid shader never reads `Normal`, and a custom normal-map shader drew the same picture, about 10% lighter on east and west faces, so sprites are baked. Neither changes the silhouette. Long faces take louvers; end walls of a bank are plain; a single cell takes four louver faces.
- **Top.** A 16-sided stack in two bands: a bell mouth at the deck and a straight throat. A new `towerFan` with no guard: 5 flat blades and a plain hub, on a gearbox and two beams inside the stack. No motor, shaft or gearbox outside it (owner, 2026-11-02).
- **Deck and access.** A flat deck with a rail round its whole edge, cheap as whole facets. One caged ladder up the end wall to the deck's corner, where the stack leaves room to step off. A rest hangs off the wall on brackets, with no posts (owner: "the ladder need improvement").
- **Water.** The hot water nozzle as drawn today, moved to the spray zone. No spool or elbow.
- **Inside: only what the stack shows (owner, 2026-11-02).** One closed shell, outer faces only. Under the fan the bore runs down to one flat floor whose sprite shows the eliminators and spray headers (owner: "show some stuff below the fan"). No fill, distributor, legs or fan-cell basin water; the heights stay in the model. Walls collide as walls: one box per cell would fill it with blocks.
- **Budget (owner: the first one was too much).** Stack, shell and fan: at most 200 polygons per cell, held by a gate (194 at worst, 14 m). Whole towers, measured: 8 m cell 1387 against 5343 before, 3 × 2 bank 2093 against 18967, 14 m cell 1639. Risk: the whole tower takes one light level, as a natural tower does.
## Effect on the model
Merkel constants stay. `distributorHeight` falls from `5.5 + fill` to the new spray level, so the pump lifts less (w = 8, default fill: 9.5 to 7.5 m). The fans-off draft reads `height − distributorHeight`, which rises from 2.5 to 4.2 m at 8 m. Measured against the marched fill on every cell left: within 0.11 K at the default fill, 0.17 K at worst. Tower references move; a layout table, `shapes-4.snbt` and the schema-6 image update. The tower is unreleased, so no alias work.

## Decisions, taken as recommended
1. **Width: decided, 8–14 m** (owner, 2026-11-02: limit the smallest size rather than shrink the fan). A `.6 w` fan leaves the walkway from 7.5 m up. Factory cells of 2.6–5.5 m drop out. Bank size must be checked: 6 × 14 m is 84 m long.
2. **Rows** 1–2 (recommended; matches the Marbach and Neckarwestheim banks) and columns 1–8. A third row has no louver face.
3. **Hot water nozzle** moves in height with the spray level, same face and x. `AGENTS.md` asks your approval for a moved nozzle pipe block.
4. **Rim 1.5 m** for both tower kinds (recommended, one constant), or natural towers stay at 2 m.
5. **Fan power.** 20 Pa gives 0.68 kW/MW at design; a [guide](https://chem-casts.com/knowledge/cooling-tower-fan-power-calculation-the-definitive-guide) gives 12–30 kW/MW (indicative: its tables disagree). Option: multiply `FAN_PRESSURE` and the air resistance by 10. Fan power rises tenfold, fans-on air stays, fans-off air falls to about a third. Left out unless you ask.
6. **Forced draft** as its own kind: not recommended now. It adds a side fan and a sealed plenum for a small unit, with the same fill.

## Build order
W1 dimensions and validation. W2 stack and fan. W3 closed shell on the whole-panel path, louver texture, deck and access. W4 nozzle. Show renders and polygon counts of one 8 m cell, one 14 m cell and a 3 × 2 bank after W3, before gates. Then `EquipmentLayoutGoldenTest`, `CoolingTowerGeometryTest` with the polygon budget, the corpus and the suites. No client check.
