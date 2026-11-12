# P5 part 1: the equipment pages and chapter 5 "Crude unit" (`unit`, after `simulator`)

Part 2 (building and starting the unit, chapters `build` and `start`): P5_PART2_BUILD_AND_START.md.

## What was built

- **Pages** (`tutorial/pages.json`, `Pages`, `PageFile`): one per equipment item, 41 (the 40 kinds and
  `pressure_control_valve`, header only). The item's "open the guide" and a map symbol open it. A page shows its
  picture, its JEI lines (`EquipmentInfo.pageKeys`), then keys: its film or tour, "Next in the unit" on the nine unit
  pages, and Back to where the player came from. The file refuses unknown fields, kinds, films and tours.
- **Unit chain:** converter, tank, pump, exchanger, vertical heater, control valve, column, drum, air cooler. Films
  `unit_converter` (2 stops), `unit_tank`, `unit_pump`, `unit_exchanger`, `unit_heater`, `unit_drum`,
  `unit_air_cooler` (3 stops, the vessels cut open at stop 2), `small_tower` for the column; bodies at the
  constructor's default sizes (`ReferenceShapes`).
- **Steps:** `whole` film `crude_unit` (5 stops along the sheet's streams; H-102 since stage B), `map` card `unit_map`
  (`small_crude_unit.json` drawn by the simulator's `SheetPainter`; a symbol opens its kind's page), `tower` page
  `equipment_column` read to its end, `next`. Pumparound is named once, at `tower`. Title "Crude unit" (the longer
  title did not fit the 312-px window's key).
- **Outside the tutorial packages:** `EquipmentInfoItem.openGuide` opens the kind's page; the step media `page:<id>`.

## Corrections after the parent's check

1. **Picture first** (`PagePicture`): the film's first stop, else the kind's reference body from the icon's view; above
   the lines at both sizes, at most half the room over the keys; built when the page first shows, drawn by `SceneView`.
2. **Unit films:** each stop frames its body at 92 by 88 % of the glass; stop 2 closes in on what the caption names.
   Dots carry a size (`Track.Dots.size`, default unchanged). Tubes, flames, oil pools and a floating roof show inside.
3. **Map tags** (`UnitMap.tags`): each tag takes the cheapest free place round its symbol, never on another tag or a
   symbol. Stage B: the cost adds 4 per pixel of distance, and the order that gives the cheapest whole layout stands
   (the base order, then each unit first), so H-101's tag sits by its symbol at both guide sizes.
4. **Wording:** one new term per step; "cooling loop" is the plain name; the term preheat is gone.

## Gates and renders

- Part 1: one run, `--tests`, 4 workers, `science.tutorial.*`, `client.gui.tutorial.*`, DrawCallTest, SimulatorTourTest:
  135 tests; three failures fixed and their classes rerun once.
- Corrections: UnitFilmsTest (60 % fill, 2-px dots, cut films show insides), UnitMapCardTest (no overlap at 464 and
  312), GuidePageTest, DrawCallTest, GuideFileTest: 13 classes, 75 tests, all pass.
- Not run: LanguageFilesTest, UnitTextTest (translation commit).
- Renders in `tmp/tutorial-renders/p5/`: `crude_unit-*`, `unit_*-*`, `pages-1..6`, `pages-dark`, `card-unit_map`,
  `guide-unit`.
