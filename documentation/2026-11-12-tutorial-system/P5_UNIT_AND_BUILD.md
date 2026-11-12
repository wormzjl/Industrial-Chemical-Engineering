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
  Term pumparound at `unit/tower` (corrections below).
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

### Corrections after the parent's check of part 1

1. **Picture first.** A page opens on its picture (`PagePicture`): the film's first stop where it has a film, else the
   kind's reference body from the icon's view (south-east, 30 degrees up), centred on its projected extent; the valve
   its item's valve geometry. The picture sits above the lines at both sizes, in the films' glass shape, at most half
   the room over the keys; the lines scroll under it, the picture stays. Built when the page first shows (no film body
   before), drawn by the screen's `SceneView`; Watch opens the film at its first stop (`FilmScreen.fromFirstStop`).
   DrawCallTest counts the picture's draws inside the page budget. The item icon on the page is gone.
2. **Unit films.** Each stop frames its body at 92 % by 88 % of the glass, the yaw turned to the body's long side;
   stop 2 closes in on what the caption names (the shell's axis, the drum's oil, the heater's tubes). Dots carry a size
   (`Track.Dots.size`, default the old 0.035 m; behaviour unchanged for other films), scaled to 2.4 px at the inside
   stop. Exchanger: a tube bundle on the nozzles' axis, crude dots in the tubes, residue dots in the shell. Heater:
   14 wall tubes and 12 top-box tubes in shaft grey, the flames on the floor. Drum: oil pools along the cut shell, gas
   dots above, water in the boot. Tank: the roof ring floats on the liquid. No `ShapeRasterizer` change.
3. **Map tags** (`UnitMap.tags`): each tag takes the cheapest free place round its symbol (rings below and above,
   shifted and flush, then the sides), fewest choices first; never on another tag or a symbol, few line pixels crossed.
   Checked at both guide sizes and two card areas; no unit moved in the example file.
4. **Wording.** `whole` only says to watch the crude cross the unit; "cooling loop" is the plain name and labels use
   it; pumparound is given once, at `tower`; the term preheat is gone. Pump stop 2: in at the front (its axial suction).

Gates: UnitFilmsTest (each stop's body fills 60 % of the glass's width or height; dots at least 2 px at the inside stop;
cut films show tubes, a roof or liquids), UnitMapCardTest (no tag on a tag or a symbol, guide card at 464 and 312),
GuidePageTest (the picture inside the well, above the lines, in the glass's shape), DrawCallTest (the picture's draws),
GuideFileTest (every page has a picture). One run, `--tests`, 4 workers, with the classes that load `Track`,
`DotMotion` and `UnitMap`: 13 classes, 75 tests, all pass. Renders again in `tmp/tutorial-renders/p5/`.

## Part 2, stage A: what building and starting the unit asks for (facts only; no code changed)

### The assembly and Import

- `ColumnBlueprint.of(trays)` for T-101 (14 trays, exchanger condenser, no reboiler, one steam, one draw, one
  pumparound). Slots in report order: `column`, `condenser` (an exchanger, overhead on its SHELL side), `receiver` (a
  lying drum with a boot), `offgas_valve`, `water_valve` (boot drain), `reflux_pump`, `reflux_tee`, `reflux_valve`,
  `distillate_valve`, `feed_valve` (the sheet's V-101), `steam_valve` 1, `draw_valve` 1, `pa_pump` 1, `pa_cooler` 1,
  `bottoms_valve`. Links: overhead, condensate, offgas, water and oil (through the tee) at the top; feed and steam walked
  upstream (their valves point into the column); draw 1; pumparound 1 (pump and cooler in either order, the loop on the
  cooler's shell, its tubes a utility); bottoms (no kettle). A line leaving the plant ends at a terminal, a branch, or
  an open end 16 pipe blocks past its valve. Check parts reports COMPLETE (all teal) or CHECKED.
- In the world the sheet's pumparound cooler takes the crude in its tubes: P-101 into the cooler's tubes, out to
  E-101's tubes. E-101, H-101, P-101, P-102 and E-102 stand outside the column's boundary valves.
- Send to block (`SheetSizing.designs`) stores each sized unit: P-101, E-101, H-101 (vertical heater), T-101 (column with
  its operating point), E-102 (air cooler), P-102, and T-101's members (condenser, receiver, reflux pump, the pumparound's
  pump and cooler); valves are left out (the pipe tool lays them). Tanks and the converter are not on the sheet.
- Import (constructor within 16 blocks of the simulator) lists the saved column design first, then the sent designs.
  One choice lands on the constructor's draft (`Design.landed`): a column at its diameter, the least skirt, bottoms to
  the front; a unit at its form, sizes and support; a drum with its boot and standard connections. Placement, paint
  and turns stay the player's. One Build makes one structure.

### Utilities and supplies in survival

| Need | Sheet | Who supplies it in survival |
|---|---|---|
| Crude | CRUDE, 8,968 kg/h | Fluid converter on `#c:crude_oil` (TFMG, PneumaticCraft) or `ad_astra:oil`; none without those mods (O2) |
| Stripping steam | 14.37 kmol/h (259 kg/h), 533.15 K, tray 14 | **No in-mod source.** Boilers are drawing only; `mekanism:steam` converts out, not in. Possible: water from a converter (`minecraft:water`) through a fired heater's coil |
| Heater fuel | not on the sheet (the simulator's heater has no fuel port) | H-101's FUEL nozzle (burners, `FiredHeaters`): a combustible product, e.g. off-gas (27 % of the firing) or naphtha; LOW/NO FUEL alarms |
| Cooling water | CWS/CWR on the condenser | Converter on `minecraft:water` (fill at 500 kPa), back to a drain converter (accepts 278 to 333 K) or a void |
| Power | none | With `energy.machinesNeedPower` (default on): 4 casing pumps need a driver (motor on FE, steam turbine, combustion engine or gas turbine, Create coupling; `Refusal.NO_DRIVER` for the reflux and pumparound pumps), the burners' fans and E-102's fans need FE |
| Products | OFFGAS, NAPHTHA, WATER, MIDDLE, RESIDUE | Gas to a flare stack (or a fuel line); naphtha, middle and residue to floating-roof tanks; boot water to a drain converter. MIDDLE has no cooler on the sheet |

### Refusals and conditions, in code order

- Start (`ColumnStarts.build`): no designed column; no operating point; vacuum column; assembly not complete; a
  boundary valve not registered. Then `FluidWorldAuthority.startColumn`: world closed; already running; a nozzle's pipe
  not registered; a generator or void inside; a device held inside; more than the device limit; two ports share a
  place; a boundary valve missing; a port's place taken; more than 6 condenser shells; more than 6 utility units a
  duty; NO_DRIVER; ports that do not fit their lines. First solve: no operating point; a product with no nozzle; the
  solve's own refusal (NOT READY).
- Running (`ColumnCycle.step`, each a share of the set feed, the lowest binds): FEED_SHORT, PRODUCT_FULL,
  COOLING_SHORT (condenser water), NO_POWER (each unpowered member pump, share 0), PUMPAROUND_SHORT (the crude through
  the cooler's tubes takes the loop's 0.59 MW: no crude flow, no pumparound), FLOODING, STEAM_SHORT. TRIP: CLOSURE.
- Steam short: rule B takes the whole feed (`LIMITED: steam`) and asks a STEAM_RATIO solve; if that solve is refused,
  rule A cuts feed and steam together, so with no steam at all the take falls to 0. Whether V3 solves T-101 with no
  steam is not known without a run.

### A headless proof

- `ColumnOperationReviewWorld.rig(helper, Options)` builds a column plant from any `V3ColumnInput` (feed, steam and
  water from generators, products to voids, pumps on FE motors or creative motors), validates it COMPLETE, starts it.
  `ColumnOperationGameTests.aColumnRunsInItsPlantStopsAndLeaves` runs it to RUNNING at the design feed (timeout
  20,000 ticks). A new GameTest with the `small_crude` template proves T-101's Check parts and Start: one fluid GameTest
  run, in the final lane. No builder exists for the survival supplies (converter, the heater on fuel, steam from water,
  the crude through the pumparound cooler); a rig for them is a new file of the review world's size (about 600 lines).

### Server points for criteria

- `EquipmentNetwork`: Build (`equipment_built`, kind and column, exists) and the Import choice's answer (`imported`).
- `AssemblyNetwork`: the view sent to the open screen (`send`): status COMPLETE after Check parts, the Operation
  view's phase (RUNNING, LIMITED with its condition); START accepted in `operate`.
- `FluidNetwork` view to a subscribed player (as `stillShown`): a converter's fill, a heat side's outlet temperature
  and burners, a tank's level. Each fires on the engine's schedule, from cached data.
