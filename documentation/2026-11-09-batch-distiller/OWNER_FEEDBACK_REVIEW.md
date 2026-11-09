# Owner feedback on the built still (2026-11-09)

The owner's items on the drawings (run `tmp/equipment-drawings/20261109-135451-f050c6/`) and in game; main thread.

## 1. One top condenser, with and without a column

- Expected: vapour rises to a condenser above the pot and the condensate drains by gravity. Without trays, liquid
  returned to the pot does not enrich the distillate (the pot is the one stage, y = K·x_pot); it only cuts it to V/(R+1).
- Found: a worm in a wooden tub on a pot still, an overhead condenser on a column. In the engine, a pot still's valve was
  read three ways: `Stills.submit` solved it at reflux 2, `Stills.arrive` sent all condensate out, and the page showed 0.
- Owner chose A, no valve on a pot still. `BatchStill.Design.refluxes()` is the one rule for the law, `Stills.reflux`,
  `refluxes` and `view`; the `stages()==0` branch in `arrive` is gone. `ProcessUnit.stillCondenser` draws the same
  condenser over the helmet or the column. The worm tub and `WOOD` are gone. The water and vent nozzles of a condenser
  pot still moved up to the condenser, as the question stated.
- Owner, on the renders: the diagonal strut, then the outboard posts, looked wrong. The shell rests on two saddles,
  each on a steel H-frame (a leg either side, a cross beam under the saddle); the reflux line runs square (owner).

## 2. Textures (`tools/still-sprites`)

- Found: vanilla `copper_block`, `bricks`, `anvil` and `spruce_planks`.
- Built: baked sprites tiled by the metre: riveted copper sheet, drawn copper for lines (`COPPER_LINE`), brick at a 62.5 mm
  course, cast iron. Renders: task `renderStills` (`render.init.gradle`), in `tmp/still-sprites/`.
- Owner: one finish per heater tier for every process vessel and line (`StillPlan.finish`, `line`): riveted copper and
  drawn copper (solid), welded carbon steel and steel pipe (liquid, "iron-ish"), welded brushed stainless and stainless
  tube (electric). Cooling water and fuel stay plant pipe.

## 3. Data port and faces

- Owner: only the electric still has a data box and port, so only the electric still takes a controller. A fired still
  has none (`EquipmentServices`); its firing and reflux are set by hand. Found in game: `ControlHosts` gave every
  heat registration an actuator port. Now a shell with no data socket gets none; an older port goes at its next bind.
- Owner, then: charge on the front, fuel on the right, drain on the back, condenser on the left (seen from the firing
  door; the right is −x). The burner is the fuel connection on the right wall at ground level, at most 50 mm
  (`NozzleBore`; owner: a small pipe connector is enough). No gun at the door. Entries square to the wall (owner).
- Owner, in game (2026-11-10): the electric still's FE socket faced the skirt; every still one block wider (owner's
  sketch): 4 × 3, the condenser in the left column, a free column, the pot on the third column's centre, the right
  column free. Charge and drain run straight on the pot's line; the terminal box stands in the right column's centre,
  data socket to the front, FE to the back; the 2000 L skirt is held 7 mm round its pot so both open on free blocks.

## 4. Pot volume against the drawing

- The drawn pot holds the design volume within −1 to +3 %, as does the engine's node. Open: the owner will recheck.

## Tests

- Passed:
  - quick suite: `StillRuntimeTest`, `FluidFactsCompatTest`, `BatchStillTest`, `StillHeadTest`, `BatchStillUnitTest`;
  - equipmentGates: the 23 affected classes, then the eight geometry classes after each layout change; the burner's
    bore: `StillSizingTest`, `SheetSizingTest`, `LineSizingTest`, `SizingCoverageTest`. Layout table: still rows only.
- `UnitTextTest` fails on the flare stack's datasheet widths (417 > 411 px), outside this change.
