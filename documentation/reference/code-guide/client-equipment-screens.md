# Client: the constructor, the datasheet, the assembly screen and the column door

Up: [code guide](README.md). The kit: [client-kit.md](client-kit.md). The server side of the constructor, the names and Import: [equipment-structure.md](equipment-structure.md); the shapes they draw: [equipment-geometry.md](equipment-geometry.md), [equipment-designs.md](equipment-designs.md). Assemblies: [machine-assemblies.md](machine-assemblies.md), [assembly-service.md](assembly-service.md); the running column: [column-operation.md](column-operation.md); the process simulator, whose column door this chapter's `CalculatorColumn` is: [process-simulator.md](process-simulator.md). GUI rules: the `gui-creation` skill ([SKILL.md](../../../.claude/skills/gui-creation/SKILL.md)).

## Read this when

- You change the equipment constructor's screen: its pages, rows, the kinds offered, the Import and Templates pages, nozzles, paint, Build, the deconstruction group.
- You change a vessel's or unit's datasheet (`VesselScreen`), its blocked-cells line or its motion switch.
- You change a column's assembly screen (Structure and Operation tabs, Check parts, Open unit, a column's name), the Operation page's set points and tables, or the DCS column page's rows.
- You change the column door (`CalculatorColumn`: its pages, Equipment page, member counts, drawing, plots), the column and stream templates, or how a template becomes a column input (`V3EditorDraft`).
- A constructor will not build, an import lands wrong, a template loads at the wrong feed, or a column entry shows a rounded value.

## What it does

The **equipment constructor** screen is client-only and edits a local draft: the player picks what to build, steps its sizes, places its nozzles, paints it and sees its ghost in the world; the draft is sent when the screen closes, and with a build request on Build. Import asks the server for the designs the nearest process simulator holds and lands the chosen one on the draft; Templates builds a column template at its design feed. The **datasheet** (`VesselScreen`) shows a structure's geometry from the cells the client already has and asks for nothing; a structure with a fluid device shows the same sheet as its fluid screen's Datasheet page. A column's **assembly screen** shows the built plant against its design (Structure) and, once complete, its running state and set points (Operation). The **column door** (`CalculatorColumn`) is the process simulator's column editor: pages, the component and member-count choices, Save design, and the column drawing. Column and stream **templates** are JSON libraries the simulator, the constructor and the fluid generator read.

## Class map

Paths under [`src/main/java/com/wormzjl/icheme/client/gui/`](../../../src/main/java/com/wormzjl/icheme/client/gui/). Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| **Screens** | | |
| `EquipmentConstructorScreen` | `screens/`: the door at the left (`ConstructorColumn`, a `KitDoor`), the world and the ghost beside it; pages CHOOSE, DESIGN, TEMPLATES, IMPORTS, CONNECTIONS, NOZZLES, PAINT; `listImports`, `listed`, `answered`, `land`; sends the draft on `removed` when changed and with Build | 1123 |
| `VesselScreen` | `screens/`: a column's or unit's datasheet on a door, title strip with the structure's name (an entry for a player who may name it), Size group and motion switch (`DialogColumns.vessel`); static `sheet(vessel)` builds the glass's `DialogColumns.Sheet`, a fluid device's material line (`DialogColumns.material`) among its notes | 334 |
| `AssemblyScreen` | `screens/inventory/`: a column's assembly screen on `AssemblyColumn` and `OperationColumn`: the block drawing and parts list, Check parts, Open unit, Datasheet; the name entry (`nameBox`, `saveName`); the Operation page (`operationView`, `entry`, `fits`, `glassView`) | 685 |
| `ColumnTemplates` | `screens/inventory/`: the client's templates: `listed`, `streams`, `saveColumn`, `saveStream`, `deleteColumn`, `load` (the constructor's Templates page), `choices` | 157 |
| `V3EditorDraft` | `screens/inventory/`: keyed text over a `V3ColumnInput` with exact values (`assemble`, `unchanged`), `loadColumn`, `loadColumnAtDesignFeed`; nested `ColumnInputDraft`, `ColumnScalarDraft`, `SideDrawDraft`, `SteamFeedDraft`, `PumparoundDraft` | 426 |
| `V3CompositionDraft` | `screens/inventory/`: binds a `RelativeComposition` to the source `V3ColumnInput` | 30 |
| `V3ClientPresets` | `screens/inventory/`: bundled and custom JSON libraries (`Column` with its `V3ColumnTemplate`, `Mixture` with optional solids and conditions, `streamJson`), `Library` with its `Problem`s; `MAX_FILES`, `MAX_BYTES` | 173 |
| `ProcessSimulatorScreen` | `screens/inventory/`: the process simulator's screen ([process-simulator.md](process-simulator.md)) | 1149 |
| `SimulatorBlockHost` | `screens/inventory/`: a simulator screen open on a block: the runner and the block's store ([process-simulator.md](process-simulator.md)) | 152 |
| **Builders (`common`, no Minecraft classes)** | | |
| `ConstructorColumn` | The constructor's door, `PANEL` wide: the pages' rows (`choose`, `design` (with `material`, the Material selector), `templates`, `imports`, `connections`, `nozzles`, `paint`), each `Page` with its footer; `listed` (an Import line), `label`, `title` (cut to `TITLE`), `withRemoval` (the Deconstruction group) | 701 |
| `AssemblyColumn` | The assembly door: Structure and Operation tabs, the status once, Check with Check parts and its summary, Selected with Open unit; `footerButtons`; the glass split between the block and the parts list (`split`, `DRAWING_W`, `DRAWING_H`) | 291 |
| `AssemblyDiagram` | A column assembly's parts (one per blueprint slot) and its ports in a fixed order (slot, role, ordinal, partner), which the laying tool's marks and port colours read | 182 |
| `OperationColumn` | The Operation page and the DCS column page: `HEIGHT`, `Units`, `Input`; `rows`, `fields`, `values`, `parse`, `applies`, `footerButtons` (Close, Clean where the column has a unit, Apply), `status`; the glass's `tables` (`ports`, `duties`, `solve`, `wear`, `conditions`, `absolutes`), `glass`, `height` | 493 |
| `CalculatorColumn` | The column door (the simulator's): `Page` (COLUMN, FEED, RESULTS, EQUIPMENT), `View`, `Metric`; `column`, `feed`, `results`, `equipment` (condenser kind and counts, kettle, coolers, `pumpDuties`, `PumpDuty`), `status`, `footer`, `door`, the frame (`width`, `height`, `split`), the drawing (`pitch`, `drawingHeight`, `drawing`, `Hit`) | 1191 |

`AssemblyBlock` (the built column's drawing, `client/gui/flowsheet/`) is in [process-simulator.md](process-simulator.md) and [assembly-service.md](assembly-service.md); `ColumnDiagram` and `TitleStrip` in [client-kit.md](client-kit.md).

## Main flow

**Constructor**:

1. The constructor block opens the screen on the client (`EquipmentClient.openConstructor`); the server hears nothing until a save, an import or a build.
2. CHOOSE (categories and kinds; the current kind latched and still acting) leads to DESIGN (sizes as `Setting` steppers, Shift steps by five; a column's diameter, trays and feed are its import's, shown, not stepped; the Material selector, four latched keys each with its cost index, for a form whose structure registers a fluid device (`EquipmentShape.fluidDevice(kind)`), kept across pages and form switches; nozzles; its `ColumnDiagram`). Each page's rows come from `ConstructorColumn`; the widgets rebuild only when the page's signature changes. The draft's vessel drives the ghost and the message display (`EquipmentGhost.blockedCount`, [client-rendering.md](client-rendering.md)).
3. Import sends the draft (`KEEP`), then `EquipmentNetwork.sendImportList`; the IMPORTS page lists the answer (`listed`), or the status says why there is none. A press sends the draft and `sendImportChoice(position, index, sent, label)` and returns to DESIGN; `answered` loads the server's design whole (form, sizes, type, name) or shows the refusal until the next edit. Templates sends the draft, then a template payload (`ColumnTemplates.load`, an air cooler and standard pumps).
4. Build sends the design with `BUILD` and closes; it is active only where the design builds (a column with its import, no clash, no unplaced side nozzle). The server answers in chat ([equipment-structure.md](equipment-structure.md)).
5. The Deconstruction group (`withRemoval`) names the nearest structure on the constructor's line, followed once a second; Deconstruct sends its origin, shape and a request id, and the screen accepts only its own answer.

**Using a cell** is decided on the server: a column (or a unit its report holds) opens the assembly screen; a structure with a fluid device opens its fluid screen with the Datasheet page; any other unit gets its datasheet (`AssemblyNetwork.datasheet`, opened by `EquipmentClient.openVessel` as `VesselScreen`).

**Assembly Operation page**: on a complete column whose view carries an operation, the door lays out `OperationColumn.rows`; set points are entries kept until Apply; a command is sent once and shown as queued until the answer; the glass holds the port, duty, solve, integrity (the column's wear, layout B), conditions and set-feed tables in the client's units; the footer reads Close, Clean (while the column stands) and Apply; without an operation the tab shows a note.

## Contracts

- **The constructor holds the only copy of the draft.** Closing without a change sends nothing; any change is sent on close, so Escape loses nothing. The screen closes when its constructor is gone. An import replaces the whole draft when the constructor's synced design changes. The imported column is the server's: the screen shows it and never sends it, so a column draft without an import builds nothing; the place, paint and nozzle faces stay the player's.
- **The constructor's text fits its door**: the message line, a stepped row's label in two lines, the kind's name before Change within `ConstructorColumn.TITLE`, a plate; `UnitTextTest` measures every kind's lines in every language. A new kind needs its `gui.icheme.equipment_constructor.*` and `gui.icheme.vessel.*` keys.
- **The datasheet reads only the client's cells**: geometry, never process state. While layout cells hold no part of the vessel on the client, its glass leads with "N cells blocked by other blocks …" (`gui.icheme.vessel.blocked`); a cell in a chunk the client has not loaded counts as blocked.
- **Names**: the device, assembly and datasheet screens hold the name's entry (`NameEntry`); Apply or Enter sends `EquipmentNetwork.rename`; the server checks the player and the reach ([equipment-structure.md](equipment-structure.md)).
- **Column entries keep exact values**: `V3EditorDraft` stores one-decimal text plus the exact value; `assemble` returns its base unchanged when nothing is dirty and keeps the base value of every unchanged field, so display rounding never reaches the solver. A template loads on the feed the draft holds (flows and duties scaled) or at its own design feed (`loadColumnAtDesignFeed`, what the constructor builds); the feed and the tray drop `s8` stay the draft's.
- **Templates**: bundled lists in `assets/icheme/columnpreset/index.json` and `mixturepreset/index.json`; custom files in `<gameDir>/IChemE/{columnpreset,mixturepreset}/`, at most `MAX_FILES` files of `MAX_BYTES` each, read only when a screen asks; saves use `CREATE_NEW` and add `_1`, `_2`, so nothing is overwritten; a delete reaches only a player's own file; neither files nor paths cross the wire. A broken file is a `Problem`, the rest still load.
- **Column template file** (`kind` "column", `schema_version` 2 or 3 read, 3 written): `name`, `translation_key`, `tray_count`, `feed_tray`, `design_feed_kmol_h`, `top_temperature_kelvin`, `reboiler_duty_mw`, `reflux_ratio`, `top_pressure_bar`, `diameter_m`, `draws`, `steam`, `pumparounds`; no feed and no package (`V3ColumnTemplate`). **Stream file** (`kind` "mixture", `schema_version` 1): `basis`, `components`, optional `temperature_kelvin`, `pressure_bar`, `flow_kmol_h`, `solids`; a `package` is ignored.

## Laws and constants

`V3EditorDraft`'s keys, which a template loads into:

| Key | Shown | Sent as |
|---|---|---|
| `s0` feed flow | kmol/h | mol/s (÷ 3.6), rescaling the component vector |
| `s1`, `s4`, `t*T` | °C or K | K |
| `s2` tray count | total trays, condenser and reboiler included | stage count = `s2` − 2 |
| `s3`, `d*stage`, `t*stage`, `c*draw/return` | public tray number (1 = condenser) | internal number − 1 |
| `s5` reboiler duty | MW | W |
| `s6` reflux | ratio | ratio |
| `s7` top pressure | bar | Pa |
| `s8` stage drop | no field | the base input's value |
| `s9` diameter | m, above 0, at most `V3ColumnInput.MAX_COLUMN_DIAMETER_METRES` | m |
| `c*duty` | positive MW of cooling | negative W (`PumparoundDraft`) |

- Side draws blank or zero are off, their total below the feed (`SideDrawDraft`); steam not above the feed (`SteamFeedDraft`); steam on the bottom tray follows the tray count.
- The column door's frame is the window less 16 px within `CalculatorColumn.width` and `height`; the tray pitch is `CalculatorColumn.pitch`, never below 3 GUI px, and a taller drawing scrolls.

## Formats

The constructor, Import, template and rename payloads are on `NetworkProtocol.VERSION` ([FORMATS.md](../FORMATS.md), "Wire protocols"). Template files are client files with the schemas above (their old schemas in `compat/presets/`, read by `V3ClientPresetsTest` and `V3ColumnTemplateTest`).

## Tests and gates

In `test` (`src/test/java/com/wormzjl/icheme/client/gui/`):

| Test | Proves |
|---|---|
| `common/ConstructorColumnTest` | Pages and rows at the door's width and less its bar, every form's Import key, the Import page, an imported design's name and the title that puts it first |
| `common/AssemblyColumnTest` | The parts list, the glass's split, a naming footer's Apply |
| `common/AssemblyDiagramTest` | One part per slot, every text's English line, every port its own colour, the ports a line meets |
| `common/OperationColumnTest` | Door and glass at every width, the switch and Apply, set points sent in SI, the tables, the conditions, the status's binding condition, a never-run column taking set points, the DCS column page |
| `common/CalculatorColumnTest` | The frame at every width and height, the pages, the status order, the drawing that fits and scrolls with trays clickable, the Equipment page |
| `common/DialogColumnsTest` | The datasheet's door and glass ([client-screens.md](client-screens.md)) |
| `screens/inventory/V3EditorDraftTest`, `V3ColumnInputDraftTest` | Exact identity, no rounding of untouched inputs, the scalar, side-draw and steam rows |
| `screens/inventory/V3ClientPresetsTest`, `ColumnTemplatesTest` | Bundled counts, old schemas, mixture solids and conditions, every crude template builds at its design feed, a delete takes only a player's own file |
| `science/equipment/UnitTextTest` (`equipmentGates`) | Every kind's constructor and datasheet lines fit, in every language |
| `network/EquipmentNetworkCodecTest` | The constructor's payloads ([equipment-structure.md](equipment-structure.md)) |

The ghost, the blocked-cells line, Enter in a name entry and the Operation page's look have no automated test: renders (`GuiPrototypes`, including `equipment-constructor-removal`) or a check the owner asks for.

## Traps

- Side-draw slots on the client (`d0..d3`, the drawing's marks, the preset reader) loop to `V3ColumnInput.MAX_SIDE_DRAWS`; change the limit there.
- Column tray numbers on screen are public numbers (internal + 1); the total includes condenser and reboiler.
- `ConstructorPayload.form` is the `VesselPlacement.Form` ordinal on the wire ([equipment-structure.md](equipment-structure.md)).
- A focused entry on the assembly screen takes its keys first (`ProcessUi.textKeyPressed`), so a typed E does not close it.
- The Operation page's glass scrolls: at the design size it does not fit one view once a column has tray data or conditions.

**Snapshot:** `9195b5161`, 2026-11-16.
