# P3: equipment reference items and JEI pages

Built 2026-11-12 on `claude/tutorial-system-chem-mod-950ca9` (plan section 3.4; owner D8, O1 reference only). Commits
a917bf05e (items, tab), a122ccb00 and 2f6a50f8a (icons, tool; 64 px on the owner's word), 8fe18f508 (lines, JEI),
a69441950 (gate), and this report.

## Built
- `science/tutorial/equipment/EquipmentInfo` (no Minecraft type): item ids `equipment_<kind>`, name keys, line keys,
  `SHAPE_ONLY`, `SIMULATOR_SHAPE`, `ITEMS_THAT_NEED`, page keys. `world/item/EquipmentInfoItem`: stack 1, tooltip
  (serves, the fourth line in gold, "Reference item: it builds nothing." in grey); use opens the Field Guide through
  `openGuide(kind)`, where P5 opens the kind's page. `ModItems.EQUIPMENT_INFO`: one registration a kind, a loop over
  `Kind.values()`. Tab `icheme:equipment_guide` after the main tab, `Kind` order, the still's icon. No recipe, no loot.
- `compat/jei/IChemEJeiPlugin` (`@JeiPlugin`): `addIngredientInfo` for every `icheme` item: the kind's lines, or the
  item's `serves` (and `needs`). Named by nothing in the mod. JEI 19.25 has `addIngredientInfo(ItemLike, Component...)`.
- 38 item models, 38 icons (64 by 64), `tools/tutorial-renders/IconRender.java`, task `renderTutorialIcons`.
- `en_us.json`: 114 equipment lines, 46 item lines, 5 shared keys (tab, drum name, three shared lines).

## Decisions
1. Names: the constructor's name where one entry is the kind (TANK reads "Floating-roof tank"); DRUM gets
   `item.icheme.equipment_drum` "Drum" (two constructor entries). No existing key changed.
2. Two fourth lines, not one. The catalog's DRAWING_ONLY holds 16 kinds, but 5 work in the world: the two cooling
   towers (reservoirs that cool water) and motor, generator, gearbox (shaft trains). "Shape only for now." on them
   would be false, so they read "Works in the world; the simulator shows its shape only." The gate checks that the
   two lists together equal the catalog's DRAWING_ONLY.
3. Shape-only (11): STIRRED_REACTOR, ADSORBER, TRICKLE_BED_REACTOR, EBULLATED_BED_REACTOR, CYCLONE, FCC_CONVERTER,
   DELAYED_COKER, FLUIDIZED_BED_BOILER, FLUIDIZED_BED_GASIFIER, GRATE_BOILER, FIXED_BED_GASIFIER.
4. `needs` lines for items only where they need something: in-line pump and compressor (power), controller and DCS
   (data cables), gas blanketing valve (gas supply), Create coupling (Create).
5. Icons: constructor default size, the whole drawing (`renderPreview`), one view from the south-east 30 degrees up,
   cut-out pixels, a dark rim. Kinds over 3.5 times taller than wide show their top part (flare stack, steel stack,
   brick chimney). The kettle is turned 90 degrees: its model reads as the exchanger from the same view.
6. Item ids are registry names in saves: a `Kind` renamed later needs `ITEMS.addAlias` from its old item id.

## Tests (filtered, all pass)
EquipmentInfoCoverageTest 5 (new), TutorialTextTest 4 (one added: 14 words, one sentence, no unmarked professional
word, over the equipment and item lines), GuideCoverageTest 6. The gate fails with `drum.place` removed (one test run,
then restored). DcsGeometryTest reads only `dcs.json`, unchanged: not run. Not run: LanguageFilesTest, UnitTextTest.

## Renders
`tmp/tutorial-renders/p3/icons-sheet.png`: every kind at 4x and at 1x on a dark and a light slot, with its name.

## Owner's in-game check (when convenient)
The Equipment guide tab after the main tab, 38 items in order; a tooltip (the still; a shape-only kind; a cooling
tower); right click opens the Field Guide; with JEI, the information page of an equipment item and of the pipe laying
tool; without JEI, the mod loads; the icons at GUI scale 2 and 3.

## Open
- Icons alike at 1x: exchanger and kettle (told apart by direction only), grate boiler and fixed-bed gasifier (both
  tall frames). Their models are the cause; big structures wait for their rework.
- Text review of the 160 lines by the owner.
