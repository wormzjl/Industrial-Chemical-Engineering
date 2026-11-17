# Client: the GUI kit, the shared frame and the client settings

Up: [code guide](README.md). The screens built on the kit: [client-screens.md](client-screens.md) (fluid devices, heat units, machines, dialogs, controllers) and [client-equipment-screens.md](client-equipment-screens.md) (constructor, datasheet, assembly, the column door). Drawing in the world: [client-rendering.md](client-rendering.md). The owner's GUI rules, the build recipes and the render workflow are in the `gui-creation` skill ([SKILL.md](../../../.claude/skills/gui-creation/SKILL.md)); this chapter does not repeat them.

## Read this when

- You change the GUI kit (`client/gui/common/kit`): rows, alignment, groups, scrolling, the light or dark panel, a widget's look, draw-call batching, the shared input order of the kit screens.
- You change a part of the frame every device or machine page shares (`FluidGuiLayout`: the title keys, tabs, door view, footer, display keys, the Control tab's slots) or the title strip and its name entry.
- You change a display unit (temperature, pressure, flow time basis), the composition basis, the panel theme or another client setting (`ClientConfig`, `icheme-client.toml`).
- You change number formatting, the component search, the composition editor, the paint swatch or the column schematic two screens share.
- A screen loses typed text on a rebuild, a typed E closes it, a hover or a click lands on the wrong row, or a door scrolls wrong.
- You add a translation key, or a screen shows a raw key.
- You must check a GUI in the dev client through the MCP bridge.

## What it does

Every IChemE screen is a control-panel door drawn through one kit. A Minecraft-free builder turns a screen's state into `Column.Row`s; `Column.place` lays them out top to bottom in one pass from the width, and one placement answers the drawing, the widgets' slots, the clicks and the hovers. The screen keeps a game widget at each slot (`KitDoor.keep`), places the rows again each frame for their texts, and moves the kept widgets when the view scrolls, so a scroll rebuilds nothing. A `Skin` draws the rows: `Skin.PLAIN`, or the light and dark panel doors of `PanelSkin`. The kit draws on a `Canvas`, which is the game's `GuiGraphics` in the game (`GuiCanvas`) and an image in the render tools, so the gates and `tools/heat-gui-renders/` place and draw what the screen draws. Loops of fills draw inside `Canvas.batch`, one draw call in the game. The theme and the display units are client settings shared by every screen; they send nothing, and the wire keeps SI values. Every screen keeps the player's GUI scale.

## Class map

Paths under [`src/main/java/com/wormzjl/icheme/client/`](../../../src/main/java/com/wormzjl/icheme/client/) unless noted. Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| **The kit (`gui/common/kit`)** | | |
| `Column` | Rows placed top to bottom from the width (`place`, `Metrics.DEFAULT` or `COMPACT`): `Group`, `Line`, `Paragraph`, `Choice`, `Field`, `Wide`, `Reading`, `Setting`, `Buttons` of `Button` (`latched`, `soft`), `Bar`, `Split`, `Gauge` (a labelled bar of one share, its value and unit in a meter's box and unit places, a group's bars from one x to one x), `Gap`; a `Placed` row keeps its rect, slots, texts, hover and mark (a gauge's bar); `slots`, `action`, `hover`, `draw`; `BOX`, `UNIT`, `STEP`, `INSET` | 518 |
| `Rect` | A rectangle in GUI pixels | 33 |
| `Measure` | Text width, cut and wrap: the game font (`GuiCanvas.measure`), `Measure.fixed(6)` in tests and tools; `LINE` | 61 |
| `Canvas` | Where the kit draws: fills, lines, text, clip, `sprite`, `batch` | 51 |
| `Sprite` | A rectangle of a texture by name, place and texture size | 8 |
| `Palette` | The instrument palette (ARGB) and the assembly report's teal, yellow and red | 15 |
| `Format` | `Format.number`: one decimal, three significant figures below 0.05 | 14 |
| `HeldDraws` | What a batch holds back (texts and sprites with their boxes) so its fills go out in one run, and the draw calls that makes, counted as 1.21.1 `GuiGraphics` makes them | 201 |
| `Skin` | How a screen and its rows draw: page, title, status, buttons (`Press` `REST`, `OFF`, `CURRENT`, `PRIMARY`, `DUE`), display keys (`softKey`, `softKeys`), `recess`, `field`, `meter`, the glass (`well`), groups, marks, bars, splits, scroll bars, `Icon`; `Skin.PLAIN` | 258 |
| `PanelSkin` | The light door (`PanelSkin.LIGHT`, RAL 7035, red LED meters) and the dark door (`.DARK`, RAL 7016, amber meters): engraved plates, 7-segment LCD entries (`segments`), LED meters, IEC 60073 button colours | 389 |
| `ScrollView` | A viewport over a taller column: an offset and its own 8 px bar; input only while shown and inside | 120 |
| `KitWidgets` | Game widgets kept at their slots in a scrolled column, shown only wholly inside | 39 |
| `KitDoor` | A door's columns in one view with one bar (`layout`, `place`, `placeAll`), the kept widgets (`keep`), `draw`, `action` (the `Choice` under a door point), `wheel`, `press`, `drag`, `release`, static `hint` | 200 |
| `DoorInput` | Static input order shared by kit screens: `press` (the tour, `SkinField.clickOutside`, the door), `wheel`, `overlay` (the tour, else the hint); its `Overlay` is what a tour implements | 51 |
| `EditFocus` | `ends`: a press outside the focused entry ends its edit, as Enter does (Minecraft-free) | 18 |
| `GuiCanvas` | The canvas on `GuiGraphics` with the game font: scissor, linear sprite blits, `batch` held as `HeldDraws` says, `release`, `drawOut`, `of(g, font)` one per frame, `measure` | 237 |
| `FrameBatchEvents` | Calls `GuiCanvas.drawOut` first on the container screen's background and foreground events and `ScreenEvent.BackgroundRendered`, so another mod's draw inside `render` lies over the screen's | 37 |
| `SkinButton` | The game's button drawn by a skin: `of(spec, slot, action, skin)`, `latched`, `primary`, `due`, `soft`, `icon`, `sign` | 90 |
| `SkinField` | The game's `EditBox` drawn by a skin (box and resting value; the game's text and cursor while focused): `onEnter`, `onCommit`, static `clickOutside` | 107 |
| `FlowsheetPainter` | Flowsheet drawing on a design grid (the assembly block, the heat Control tab, the simulator's sheet): `fit`, lines with arrows, `head`, vessels, shells, valves, labels in a scaled font (`MIN_FONT`) drawn only where they fit (`tag`, `place`); `Look` | 365 |
| **Shared frame and widgets (`gui/common`; `DisplayUnit` in `science/display`)** | | |
| `FluidGuiLayout` | The device and machine pages' frame, Minecraft-free: `imageWidth`, `imageHeight`, `titleKeys`, `DOOR_TOP`, `DOOR_FOOT`, `doorView`, `fits`, `footer`, `softKeys`, `side`, `tabs`, `control` (`ControlSlots`, `SPLIT_MIN`) | 78 |
| `TitleStrip` | A screen's title row, Minecraft-free: the kind's plate, the name's place (`NAME`, `NAME_MIN`), `text`, `textAt`, the theme and help keys (`keys`, `keysWidth`, `placeKeys`) | 78 |
| `NameEntry` | The name's entry of the device, assembly and datasheet screens: `seed`, `follow`, `renames`, `box` (Enter saves), `forget`, `draw` | 107 |
| `ProcessUi` | `number`, `pressure`, `materialName`, `Label`, `button`, `restoreCursor`, `textKeyPressed`, `tableRow`; `NumericDraft` (short text over an exact SI value in a display unit), `ProcessScroll` (a draggable row scroll bar) | 135 |
| `DisplayUnit` | `display`/`si`; `PressureUnit` (Pa, kPa, bar, MPa), `TemperatureUnit` (`CELSIUS`, `KELVIN`), `FlowRateUnit` (per second, minute, hour; `next`) | 56 |
| `ComponentDropdown` | A search `EditBox` with a bounded overlay list; typing never rebuilds the screen | 97 |
| `ComponentSearch` | Deterministic ranking of component ids and names: exact, prefix, substring, typo distance, subsequence | 66 |
| `RelativeComposition` | Relative amounts per component on a mole or mass basis, full precision until edited | 84 |
| `PaintSwatch` | One palette colour as a button, outlined white while edited (the constructor's paint page, the pipe tools) | 56 |
| `ColumnDiagram` | The column schematic of the column door and the constructor on the kit's `Canvas`: shell (`HALF`), trays, feed, draw, pumparound and steam marks (`MARK`) | 59 |
| **Client root** | | |
| `ClientConfig` | `icheme-client.toml` (sections `gui`, `units`, `equipment`, `effects`): `theme`, `skin`, `toggleTheme`, the units and `basis`, `streamConditions`, `guideHud`, `renderDetail`, `lodScale`, `lodView`, `operationUnits`, the effects switches, `equipmentPaint`, `rememberEquipmentPaint` | 213 |
| `IChemEClient` | Registers the menu screens, the guide's reload listener, key and HUD layer; clears client caches on logout | 71 |
| `TourAnchors`, `DeviceTour`, `ScreenTours`, `AssemblyTour` | Where the help tours' stops stand on each screen's doors ([tutorial.md](tutorial.md)) | 60, 112, 129, 49 |

The dev-client bridge mod is [`src/mcpCompat`](../../../src/mcpCompat/java/com/wormzjl/icheme/mcpcompat/) (not shipped): `McpCompatibilityMod`, and the mixins `ScreenInputMixin` (the bridge's click, type and paste to the focused widget), `HotkeyMixin` (Ctrl+A in the focused `EditBox`), `WindowHandleMixin` (the window's own GLFW handle), `BackgroundCursorMixin` (no cursor grab while the window is in the background), `UseItemMixin`, `MinecraftInputInvoker`; its `neoforge.mods.toml` requires `mcpmod` `[0.4.28,0.5.0)` (the mixin targets kept their v0.4.18 signatures).

## Main flow

A kit screen, from open to close:

1. `init` builds the rows from the screen's state (a builder's `rows`/`columns`), lays out the door (`KitDoor.layout`, `placeAll`) and keeps a widget at each slot (`KitDoor.keep`: a `SkinField` for a `Field` or `Wide`, a `SkinButton` for a `Button` or a `Setting`'s steppers).
2. Each frame: `GuiCanvas.of(g, font)`, the rows placed again for their texts (equal rows and measure return the same columns, `placeAll`), `KitDoor.draw` through the `Skin`, the hover's hint; screens that batch their frame draw it in one `GuiCanvas` batch, and `FrameBatchEvents` sends out what it holds at the frame's end.
3. Input: `DoorInput.press` (the tour, then `SkinField.clickOutside`, then the door's bar) and the screen's own handlers; `KitDoor.action` for a `Choice`; `DoorInput.wheel` scrolls the view (`KitWidgets` moves the kept widgets); keys go first to a focused entry (`ProcessUi.textKeyPressed`).
4. A display key or the theme key changes `ClientConfig` (saved at once) and redraws; a screen's Apply sends SI values through its own server path ([client-screens.md](client-screens.md)).
5. A change of the page's signature (rows with widgets, footer, states) rebuilds the widgets; `rebuild` restores focus and cursor by editor key.
6. `removed` ends the screen; nothing changes the GUI scale.

## Contracts

- **Builders are Minecraft-free.** The rows, the frame (`FluidGuiLayout`, `TitleStrip`) and the drawings with hit tests hold no Minecraft class, so tests and the render tools use them. `KitDoor`, `KitWidgets`, `GuiCanvas`, `SkinButton`, `SkinField`, `NameEntry` hold game classes and belong to the screens.
- **One placement serves everything.** Drawing, slots, `Column.action` and `Column.hover` come from the same placed column; a group's plate hover says more, and sentences go to hovers, never on the door.
- **Widgets come first.** Rows with widgets stand before rows whose texts follow the plant, so a widget never moves while values change; the builders' gates test it.
- **One key per widget.** Two widgets with one key in a placed column throw (`Column.slots`); keys are unique across a door's columns.
- **The GUI scale is the player's.** No screen sets it; `KitLayoutTest` fails on a `setGuiScale` call. A door that does not fit scrolls.
- **Display units are presentation.** `ClientConfig`'s units, basis and theme are kept for every screen in `icheme-client.toml`; the keys convert the drafts and the shown values and send nothing. Before the config loads, `ClientConfig` reads its defaults and its setters do nothing.
- **Drafts keep exact values.** `ProcessUi.NumericDraft` holds a short text over the exact SI value: untouched text returns the exact bits across any number of unit changes; a value below 1 shows 4 significant digits; a blank stays blank. `RelativeComposition` normalises on use, and its basis toggle converts through the molecular weights.
- **Draw calls.** In 1.21.1 each unmanaged `GuiGraphics` fill or text flushes and a blit draws at once. A loop of fills draws inside `Canvas.batch` (one call); `HeldDraws` holds texts and sprites until a fill or text would cover them, a clip changes or the batch ends, so every pixel lands as the call order would.
- **Text alignment.** Text origins snap to physical pixels independently of the Unicode font choice; dynamic texts take the same path as static labels.
- **Threads.** Screens run on the client thread; they draw what the server sent and compute no process state (AGENTS.md, "Engine schedule").

## Laws and constants

- Layout: `Column.BOX` (an entry's or meter's box) and `Column.UNIT` (the unit after it) put every box and unit of a column in line; `Column.STEP` (a stepper); `Column.INSET` (a group's inset); `ScrollView` takes a 30 px wheel step.
- The device frame (`FluidGuiLayout`): `imageWidth` clamps the window to its range, `side(width)` is the door column's right edge, `DOOR_TOP` and `DOOR_FOOT` bound the door's view, `footer` shrinks Close and Apply before the four display keys (`softKeys`), `control` splits the Control tab into two columns where the right keeps `SPLIT_MIN`.
- The title strip: the plate at the left, the name's place `TitleStrip.NAME` wide (less where the keys need room; none below `NAME_MIN`), the name cut to `DeviceName.MAX` letters ([equipment-geometry.md](equipment-geometry.md)).
- `ProcessUi.pressure` rounds to 0.1 Pa and prints kPa through `PressureUnit.KILOPASCAL`; every `PressureUnit` keeps the 0.1 Pa resolution. The wire carries kelvin.
- `ComponentSearch`: NFKD, lower case, punctuation dropped; bands exact, prefix, substring, typo (Damerau distance 1 for 3 to 5 letters, 2 from 6), subsequence; an empty query keeps catalogue order.
- `FlowsheetPainter.MIN_FONT` is the smallest label scale; a label that does not fit is not drawn.

## Formats

The client config `icheme-client.toml` is a client file, never sent. No saved or wire format is owned here; the payloads the screens send are in [FORMATS.md](../FORMATS.md) under the protocol row of their network class.

## Tests and gates

All in `test` (`src/test/java/com/wormzjl/icheme/client/`); run with `--tests`:

| Test | Proves |
|---|---|
| `KitLayoutTest` | The kit's rules: rows in order and apart, a `Wide` row across the width, no text cut, hovers and clicks from one placement, a scroll clamped and a hidden view inert, a widget shown only wholly inside, groups and plates, labels all beside or all above, boxes and units in line, two columns in one view, equal rows give the same column (`equalRowsGiveTheSameColumn`), no `setGuiScale` |
| `FlowsheetPainterTest` | The grid fitted and centred, a label only where it fits, marks inside their box |
| `HeldDrawsTest` | A batch lays every pixel as the call order would and counts its draws by kind |
| `EditFocusTest` | A press outside the focused entry ends its edit; one on it keeps it |
| `FluidGuiConsistencyTest` | Unit switches keep untouched SI values; every pressure unit's resolution and label; the phase bases; footer, key tray, tabs and the Control tab's columns at every width |
| `TitleStripTest`, `NameEntryTest` | The plate, the name's place and the keys apart at every window; a reader's name cut to its place; the entry's follow and rename |
| `ProcessPresentationTest` | The basis switch, the Celsius default, untouched values kept exact |
| `LanguageFilesTest` | Every lang file holds en_us's keys, no key twice, the same arguments Minecraft fills (run after the translation commit, AGENTS.md "Translations") |
| `DrawCallTest` (in `gui/flowsheet`), `FrameBatchDrawsTest` | Draw calls counted as the game makes them; a door's frame in one batch in both themes |

The look is judged on renders of `tools/heat-gui-renders/` (`GuiPrototypes`, `HeatDiagramRender`; `-Dcount` prints each page's draw calls), as the `gui-creation` skill says. `ComponentSearch` and `ProcessUi.textKeyPressed` have no test.

**Dev-client checks.** AGENTS.md, "GUI and in-game checks": only on the owner's request, through the `runMcpClient` lane ([verification.md](verification.md)) with the bridge jar tracked in `tools/minecraft-mcp-bridge/` (`prepareMcpControlMod` copies the file `build.gradle` names into `run/mcp-client/mods/` and removes other bridge jars); `-PmcpClientWidth`/`-PmcpClientHeight` size the window without desktop input. The `mcpClient` run sets `mcp.background_input=true`, `mcp.remote_control=true` (bridge 0.4.27+ refuses a remote `enter_control_mode` without it) and `mcp.screenshot.dir` to the worktree's `tmp/` (0.4.28 writes `screenshot_to_file` only there). The procedure is the `minecraft-mcp` skill ([SKILL.md](../../../.claude/skills/minecraft-mcp/SKILL.md)).

## Traps

- `rebuild` clears every widget; a new widget must be registered under a stable editor key, or a rebuild loses its focus. `ComponentDropdown` stays expanded across a rebuild only for the key `search`, and a screen routes its `popupClick`, `popupScroll`, `popupDrag` and `popupRelease` before its own handling.
- A focused text field takes every key but Escape and Tab (`ProcessUi.textKeyPressed`); every container screen with an entry routes keys through it first, or a typed E closes the screen.
- `ProcessUi.restoreCursor` runs every frame and releases a grabbed mouse.
- Tests measure with `Measure.fixed(6)`; the game font is narrower for i, l, t and k. A close fit is judged on a render.
- `GuiCanvas.release` must run before anything draws past the canvas (as `SkinField` does before the game draws its focused text), or held texts land under it.
- Translations: only `lang/en_us.json` changes during a batch; the other four files follow in the translation commit (AGENTS.md, `translation-commit` skill). A missing key shows the raw key; the builders' `everyText…HasItsEnglishLine` gates catch it for builder texts, nothing for a screen's own `tr` calls. Status words the server builds (`LIMITED`, `LOW LIMIT`, `DUTY INSUFFICIENT`), data-range notices and hold reasons arrive as English text with no lang key.

**Snapshot:** `549b9faab`, 2026-11-17.
