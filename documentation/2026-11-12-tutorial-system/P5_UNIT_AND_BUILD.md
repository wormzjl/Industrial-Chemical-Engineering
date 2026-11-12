# P5: chapter 5 "Crude unit" and the build facts for chapter 6

## Part 1: the equipment pages and chapter 5 (`unit`, after `simulator`)

- **Pages** (`assets/icheme/tutorial/pages.json`, `Pages`, `PageFile`): one page per equipment item, 41 in all (the 40
  kinds and `pressure_control_valve`, which has no body: header only). The item's "open the guide" and a map symbol open
  it; its lines are the JEI lines (`EquipmentInfo.pageKeys`). A page shows its icon and name, the lines, then keys:
  the film where one exists, the tour (`equipment_batch_still`: `fluid_device.still`), "Next in the unit" on the nine
  unit pages, and Back, which returns to the page, card or step the player came from (closes the guide when empty).
  The file refuses unknown fields, unknown kinds, films and tours, and a unit list that names a page twice or none.
- **Unit chain:** converter, tank, pump, exchanger, vertical heater, control valve, column, drum, air cooler. Page films:
  `unit_converter` (2 stops), `unit_tank`, `unit_pump`, `unit_exchanger`, `unit_heater`, `unit_drum`, `unit_air_cooler`
  (3 stops; tank, exchanger, heater and drum cut open at stop 2), and `small_tower` for the column. Bodies are the
  constructor's default sizes (`ReferenceShapes`, shared with the icon tool).
- **Steps:** `whole` film `crude_unit` (5 stops: converter and P-101; the preheat through the pumparound cooler and
  E-101; H-101 and V-101; T-101's products; the product tanks, the residue through E-101 and E-102). Its pipes follow
  the sheet's streams. `map` card `unit_map`: `small_crude_unit.json` drawn by the simulator's own `SheetPainter`, each
  unit a place that opens its kind's page (H-101 the vertical heater, E-102 the air cooler: the types Size all gives);
  done once a page was opened. `tower` page `equipment_column`, done when read to its end. `next` one sentence.
  Terms preheat and pumparound (at `unit/whole`).
- **Changes outside the tutorial packages:** `EquipmentInfoItem.openGuide` opens the kind's page;
  `HeatDiagramRender.tinted` keeps an item sprite's colours (render tool). The guide step media `page:<id>`.
- The chapter's title is "Crude unit": "The crude unit" did not fit the door's key in the 312-px window.

### Gates

New: UnitFilmsTest (2), UnitMapCardTest (1). Extended: GuidePageTest (6: every page inside the glass, uncut),
GuideCoverageTest (9: every kind has a page, the chain visits each of the unit's kinds once, in the file's order),
GuideFileTest (10: the page step, the pages file's refusals, every new film built with no Minecraft class),
DrawCallTest (8: a page within the busiest chapter page, the map within 360), EquipmentInfoCoverageTest (6),
TutorialTextTest (6: page keys and film labels are short names), StillInsideFilmTest (5: the map card drawn in the
Minecraft-free loader). One run, `--tests`, 4 workers: `science.tutorial.*`, `client.gui.tutorial.*`, DrawCallTest,
SimulatorTourTest, 135 tests; three failures fixed (the map's fills outside a batch, its client imports, the chapter
title's width), and the classes they touch rerun once each. Not run: LanguageFilesTest, UnitTextTest.

### Renders (`tmp/tutorial-renders/p5/`)

`crude_unit-*` and `unit_*-*` (each stop, sheet, small window), `pages-1..6` (all 41 pages, light, at 464 by 254 and
in the 320 by 240 window, eight a sheet, the unit's first), `pages-dark` (column, valve), `card-unit_map` (nothing pressed, H-101 pressed, small dark), `guide-unit`. Fixed from them:
slanted pipe legs, cuts that did not open the bodies, the heater coil outside its shell, the drum's oil box, bodies too
small, key rows in the small window, map tags that met.
