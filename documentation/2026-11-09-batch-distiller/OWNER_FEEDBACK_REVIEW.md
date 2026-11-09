# Owner feedback on the built still (2026-11-09)

The owner reviewed the drawings (run `tmp/equipment-drawings/20261109-135451-f050c6/`) and gave four items. Built on the
main thread (owner: "Here").

## 1. One top condenser, with and without a column

- Expected: vapour rises to a condenser above the pot and the condensate drains by gravity. Without trays, liquid
  returned to the pot does not enrich the distillate (the pot is the one stage, y = K·x_pot); it only cuts it to V/(R+1).
- Found: a worm in a wooden tub on a pot still, an overhead condenser on a column. In the engine, a pot still's valve was
  read three ways: `Stills.submit` solved it at reflux 2, `Stills.arrive` sent all condensate out, and the page showed 0.
- Owner chose A, no valve on a pot still. `BatchStill.Design.refluxes()` is the one rule for the law, `Stills.reflux`,
  `refluxes` and `view`; the `stages()==0` branch in `arrive` is gone. `ProcessUnit.stillCondenser` draws the same
  condenser over the helmet or the column. The worm tub and `WOOD` are gone. The water and vent nozzles of a condenser
  pot still moved up to the condenser, as the question stated.
- Owner, on the renders: the diagonal strut looked wrong. The condenser now stands on a post with a cradle at each end, and
  the reflux line falls straight from the valve into the column's front.

## 2. Textures (`tools/still-sprites`)

- Found: vanilla `copper_block`, `bricks`, `anvil` and `spruce_planks`.
- Built: baked sprites tiled by the metre: riveted copper sheet, drawn copper for lines (`COPPER_LINE`), brick at a 62.5 mm
  course, cast iron.
- Owner: one pot finish per heater tier, with the condenser to match (`StillPlan.finish`): riveted copper (solid), welded
  carbon steel (liquid, "iron-ish"), welded brushed stainless (electric). The helmet, column and lines stay copper.
- Renders: task `renderStills` (`render.init.gradle`), output in `tmp/still-sprites/`.

## 3. Data port and fuel connection

- Owner: only the electric still had a data port and its fittings; a fired still's box sat flush in the brick.
- Built: the fired still's junction box stands in front of the setting, left of the door, on brackets to the brick,
  centred on the front row (the gate wants the gland centred on its block). Its conduit runs over the coping to a
  thermowell in the pot.
- The liquid fuel flange moved from the front to the back face's right column, (2,0,3). The +x face would touch a
  no-condenser still's vapour pipe (`everyNozzleLeavesAFreeBlockForItsPipe`).
- Owner, later: the fuel may go on the −x face the data box left. Open: on stills up to 750 L, the charge pipe at (−1,1,1)
  touches every row-0 position there. The fuel fits only if the charge moves up a row (a nozzle move).

## 4. Pot volume against the drawing

- The drawn pot holds the design volume to within −1 to +3 %; the engine's node has the same volume and height. Open:
  the owner will recheck.

## Tests

- Passed:
  - quick suite: `StillRuntimeTest`, `FluidFactsCompatTest`, `BatchStillTest`, `StillHeadTest`, `BatchStillUnitTest`;
  - equipmentGates: the 23 affected classes, then the five geometry classes after the fixes.
- Layout table re-recorded; only still rows changed.
- `UnitTextTest` fails on the flare stack's datasheet widths (417 > 411 px), outside this change.
