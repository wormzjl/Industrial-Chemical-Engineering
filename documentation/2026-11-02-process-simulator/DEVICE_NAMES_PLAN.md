# Device names (N1)

The owner asked "Make sure that equipment names can be customized", chose "Yes, every device" on 2026-11-03, and added
"Give them a nameplate on the model as well". The sheet names its units already (`SheetEditor.rename`). This plan
covers the world: every placed device carries a name the player chooses, its screens put the name first, a structure
built from an imported design takes the design's tag, and a named device shows the name on a plate on its model.

## 1. What can be named

Every placed device that opens a screen:

- Every equipment structure, by `EquipmentShape.Kind`: DRUM, COLUMN, TANK, SPHERE, EXCHANGER, KETTLE, AIR_COOLER,
  EJECTOR, PUMP, COMPRESSOR, VERTICAL_HEATER, CABIN_HEATER, STIRRED_REACTOR, ADSORBER, TRICKLE_BED_REACTOR,
  EBULLATED_BED_REACTOR, CENTRIFUGAL_COMPRESSOR, STEAM_TURBINE, CONDENSING_TURBINE, CYCLONE, FCC_CONVERTER,
  DELAYED_COKER, FLUIDIZED_BED_BOILER, FLUIDIZED_BED_GASIFIER, GRATE_BOILER, FIXED_BED_GASIFIER,
  INDUCED_DRAFT_COOLING_TOWER, NATURAL_DRAFT_COOLING_TOWER. The fluid ones (reservoirs, movers, heat units) open
  `FluidDeviceScreen`, a column `AssemblyScreen`, the others their datasheet (`VesselScreen`).
- Every one-block device that opens `FluidDeviceScreen`: the reservoir, the generator, the void and the in-line filter
  blocks, and a pipe with a pump, a compressor, a pressure control valve, a filter or mounted sensors (a meter run).
  A plain pipe and a shutoff valve open no screen (a shutoff only toggles) and take no name.

## 2. Where a name is saved

A name is a fact the player chose, drawn on the model, as paint is. It is saved where paint is saved:

- A structure: on every shell cell, NBT `Name`, beside `Paint` (`EquipmentShellBlockEntity.write`). Every cell holds the
  same name, as every cell holds the same paint; a refresh after a layout change carries it as it carries paint
  (`EquipmentStructure.refresh`). Both sides of an exchanger read it from their cells, so the name is the structure's.
- A one-block device: on its block entity, NBT `Name` (`FluidDeviceBlockEntity`), beside `Paint`.
- Absent or empty: no name, today's behaviour. Written only when not empty, so a save from before reads unnamed, and
  a world saved by this build loads in an older one with the name ignored.
- Read through one Minecraft-free rule, `DeviceName` (science/equipment): at most 32 characters, trimmed, no `§`
  formatting codes and no control characters. A saved value that breaks the rule is cleaned and logged
  (`equipment_compat ... repair=name`), never refused: a name is not worth a structure.

Not chosen: the fluid facts (`WorldTopologyLedger.Registration`). Only fluid devices are registered there (columns,
drums of no network and drawing-only units are not), a name would bump the device's revision and its island's
compile, and the client would still need a copy beside paint to draw the plate. One place, the block entity, serves
the save, the screens and the plate.

## 3. A rename

1. The screen sends `RenamePayload(position, name)` (`EquipmentNetwork`, protocol `equipment-17`; wire formats need no
   compatibility work).
2. The server handler checks, as an edit does: the player may build and interact there, stands within reach of the
   device (a structure's reach adds its largest size, as `EquipmentShellBlockEntity.open` does), the block is a shell
   cell of a placed structure or a device that opens a screen, and the text is at most 32 characters after
   `DeviceName.clean`. Anything else is dropped with a log line.
3. It writes the name on every shell cell of the structure (or on the device) and sends the cells' block updates.
4. Clients get it with the block entity's update tag, as paint; open screens read it from the client's cells on the
   next frame, and the plate redraws.

The name is not process state: the rename queues no engine event, changes no registration and forces no process
update, so AGENTS.md's request-only rule (GUI and process data) does not apply; the name rides the render data, as
the owner's addendum says. A packet handler that writes a drawing fact is what the laying tool's paint already does.

## 4. From Import to the built structure

- The constructor's `Design` gains a component `name` (default empty), saved in the design's NBT as `Name` and sent
  with `writeDesign`, so a constructor saved before reads unnamed.
- The Import page lists a design as "tag" or "tag member" (`ConstructorColumn.listed`). `ImportChoice` carries that
  text without the count; the server cleans it and `Design.landed` takes it. A hand edit keeps the name, as it keeps
  the imported column (`keepingColumn`); a new import replaces it. The constructor's title shows it first.
- Build passes `design.name()` to `EquipmentStructure.place`, which writes it on every cell with the paint.
- A design for N units in parallel (`SentDesign.count`): the constructor builds one at a time, and each built unit
  takes the same name. Plant-style suffixes (E-101A, E-101B) are an option for the owner, not built.
- A structure built by hand, with no import, starts unnamed.

## 5. Titles

One rule, `DeviceName.titled(name, title)`: "name · title" when named, else the title alone. Line
`gui.icheme.device.named` = "%s · %s".

- `FluidDeviceScreen`: "E-101 · Heat exchanger: tube side", "P-101 · Pump". The screen keeps the kind title it was
  opened with and reads the name from the client's block at `menu.position()` each frame.
- `AssemblyScreen` (a column): "T-101 · Distillation column"; its member list names a named member first.
- `VesselScreen` (the datasheet): "D-101 · Drum".
- The constructor's title: the design's name before the kind.
- The server's build message `built_unit` names the built structure by its name when it has one.

## 6. The name entry (open look, owner's pick)

Rendered with `GuiPrototypes`, two to four forms, on the device screen; the same form goes on `AssemblyScreen` and
`VesselScreen`:

- A: a Name group first on the door (an LCD entry across the column), sent by Apply with the controls' edit.
- B: a Rename key beside the title, opening a small dialog with the entry, Save and Cancel.
- C: the title itself, an entry the player clicks to edit; Enter saves, Escape cancels.

Until the pick, the facts, the rename path, the import default and the titles are built; the entry is built after.

## 7. The nameplate

Plant practice: a tag plate or a stencil on the shell at about eye height, on the side that faces the access way;
pumps and valves carry a small tag.

- Drawn, not placed: no block, no cell, no layout change, so `EquipmentLayoutGoldenTest` and the refresh rules are
  untouched. An empty name draws nothing.
- A structure: `Nameplate.of(shape)` (Minecraft-free, science/equipment) gives the plate's centre, its outward normal
  (a heading) and its size in the structure's box. The search starts on the side the front heading faces, 1.6 m above
  the base or skirt (`support`), clamped to the body; it marches in from the box face along the heading to the solid
  (`ShapeRasterizer.inside`), and takes the first spot whose four corners lie on the shell within 5 cm and are clear of
  nozzles (`hides`, `nozzleCells`) and ladders (`climbs`). Failing that it tries 0.5 m up and down, then left and
  right, then the other three sides. A structure with no clear spot draws no plate (gate counts them).
- Size: 0.9 by 0.25 m for a structure, at most a third of the body's width; text scaled to fill 80 % of the plate's
  width, at most 0.16 m tall: 32 characters at the smallest still read at 5 blocks, 6-character tags at 15.
- A one-block device: a 0.36 by 0.1 m tag on the casing's side face, perpendicular to the pipe (for a vertical pipe,
  its north face), from the device's drawn bounding box.
- Drawing: a client renderer on `RenderLevelStageEvent` (as `InstrumentRenderer`, `SightGlassRenderer`): it walks a
  weak set of named structures (registered by their controller cell) and devices, culls by frustum and by distance
  (text within 32 blocks, nothing beyond), draws one quad for the plate and the text with `Font.drawInBatch` at the
  block's light. Cost: about 2 + 2 × characters quads a plate; 200 named devices in range is under 15 000 quads a frame,
  culled first.
- Look, the owner's pick, rendered with the software renders (a working `ColumnRender`-style tool drawing the real
  model polygons, the plate's text rasterised from AWT glyphs onto it):
  - P1: a white plate with black text, framed;
  - P2: a stencil, the text painted straight on the shell in black, no plate;
  - P3: a dark plate with white text.
  The text in the game cannot be judged on a software render alone; one in-game check is offered to the owner.

## 8. Gates

- `DeviceNameTest`: the rule (32 characters, trim, `§`, control characters, empty clears) and `titled`.
- `DeviceNameCompatTest`: corpus `compat/equipment/names-1.snbt` (a cell and a device tag: named, unnamed, saved
  before names, a name that breaks the rule) read through the block entities' static readers and written back.
- `EquipmentNetworkCodecTest`: `RenamePayload` round trip; `ImportChoice` with its name; the design's name through
  `writeDesign` / `readDesign` (a constructor saved before reads unnamed).
- The rename's checks (length, characters, reach, a block that takes no name) on the handler's Minecraft-free part.
- The import default: `Design.landed` takes the name, a hand edit keeps it, a new import replaces it.
- Titles: `DeviceName.titled` on each screen's title builder; the constructor's title.
- `NameplateTest`: for every shape of `EquipmentLayoutGoldenTest.shapes()`, the plate is on the model's faces, clear
  of nozzles and ladders, faces the front where it can; no plate for an empty name; the name in the update tag.
- The name entry's builder gates (gui-creation skill) once the form is picked.

## 9. Lines

English and Chinese: `gui.icheme.device.named`, the entry's plate, hint and keys, the rename's refusals. Their
fr/es/ja lines go in the README's merge note.
