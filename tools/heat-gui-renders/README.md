# heat-gui-renders

Purpose: software renders of the heat units' Control-tab flowsheet, so its layout is judged without a game client (owner: enough client testing).

Batch: `2026-10-27-heat-exchanger-design`, the heat GUI task (`HEAT_GUI_PLAN.md` section 4), branch `claude/heat-gui-redesign-abb1db`.

## How to run

From the worktree root, after `./gradlew compileJava`:

```bash
java -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/HeatDiagramRender.java src/main/resources/assets/icheme/lang/en_us.json tmp/heat-gui-renders build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar
```

The third argument is the Minecraft resources jar that ModDevGradle unpacks (it defaults to that path). The tool draws text in the game's bitmap font from that jar, so its widths, wraps and cuts are the game's; without the jar a sans font stands in.

It writes to `tmp/heat-gui-renders/`:

- one sheet per case, the light panel (door 3a) beside the dark panel (door 3c), as the Control page opens: the exchanger under six line designs, the air cooler as editor and reader, the fired heater, the kettle's tubes and its pool;
- a `-scrolled` sheet where the columns are taller than their view;
- `drawings.png`: four units' flowsheets alone at their design size.

It prints each case's tallest column against its 128 px view.

The other screens' prototypes (`GuiPrototypes.java`, batch 2026-10-28-gui-toolkit, `GUI_REVIEW.md`):

```bash
java -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/GuiPrototypes.java src/main/resources/assets/icheme/lang/en_us.json tmp/gui-prototypes
```

It writes one sheet per screen to `tmp/gui-prototypes/`, both doors side by side: the fluid device pages (pump, generator, valve, tank), the column calculator, the assembly, the equipment constructor, the pipeline tool, the gauge and the vessel datasheet.

## Entry points

- `HeatDiagramRender.java`: a single-file Java program (Java2D, headless). It lays the page out as `FluidDeviceScreen` does and draws it with the shipped code: `PanelSkin` for the page and the widgets, `HeatControlColumn` in layout C, `HeatFlowsheet`. Only the page's order of drawing and the illustrative readings are its own. Line parts cycle teal, yellow and red to show the legend's colours.
- The English texts come from `en_us.json`; a key without a line is drawn as `?` and the key.
- `GuiPrototypes.java`: proposed screens drawn through the same kit (`Column`, `PanelSkin`) and `HeatDiagramRender`'s font and canvas (the multi-file source launcher, JDK 22 or later). Its layouts are proposals for the owner, not the screens' code, except the fluid device pages: their door column is the shipped `DeviceColumn` at `FluidGuiLayout.side`, shown as opened and scrolled to its end, and the tool prints each column's height.

## History

- 2026-10-28: written for the heat GUI task. First as SVG; changed to PNG because the in-app browser cannot open local files and PNGs can be read directly. Utility lines drawn after the parts, as the screen does (the kettle's bundle and weir lie inside the drum). Font 9 px, the game font's height; the kettle's bundle moved under the drum's label.
- 2026-10-28, batch 2026-10-28-gui-toolkit: renders whole Control pages at 464 by 254 px (as shown and scrolled to the end) through the shipped code: `HeatFlowsheet` and `HeatControlColumn` on the GUI kit (`Column`, `ScrollView`, `FlowsheetPainter`) on a Java2D canvas. Its own copy of the drawing code is gone. It runs with only the mod's classes, which checks that those classes hold no Minecraft classes.
- 2026-10-28, batch 2026-10-28-gui-toolkit: styles A to D for the owner (`GUI_TOOLKIT_PLAN.md` section 7). B, C and D are row transforms in the tool (`compact`, `split`, `cards`) over the rows `HeatControlColumn` builds, on `Column.Metrics`. The style the owner picks moves into `HeatControlColumn`; then the other styles leave the tool.
- 2026-10-28, batch 2026-10-28-gui-toolkit: industrial looks (`GUI_TOOLKIT_PLAN.md` section 8). `Look` extends the kit's `Skin` with the page, the title, the widgets, the drawing's well and the dividers. `PLAIN` is today's look. `INDUSTRIAL` holds four looks: an ISA-101 HMI, classic SCADA, a control panel door and a dark control room. The chosen look moves into the kit; the others leave the tool.
- 2026-10-28, batch 2026-10-28-gui-toolkit: look 3 refined (`GUI_TOOLKIT_PLAN.md` section 9). `Panel` draws a control panel door: legend plates, engraved text, lit push buttons with chrome rings, pilot lights, 7-segment LED meters, an LED bar graph, a message display, the HMI screen and a latch. `PANELS` holds four doors. The pages carry the server's titles (vessel and side).
- 2026-10-28, batch 2026-10-28-gui-toolkit: the light and dark doors shipped (`GUI_TOOLKIT_PLAN.md` section 10). The tool draws both doors through the kit's `PanelSkin` in layout C, in the game's bitmap font, and the drawings at their design size. The styles A to D, the four industrial looks and the four doors left it.
- 2026-10-28, batch 2026-10-28-gui-toolkit: function groups (`GUI_TOOLKIT_PLAN.md` section 11). The tool builds the column with `HeatControlColumn.columns(input, texts)`, latches the switch's position from `Column.Button.latched`, and draws no view time.
- 2026-10-28, batch 2026-10-28-gui-toolkit: `GuiPrototypes.java`, the other screens under the panel rules (`GUI_REVIEW.md`); `HeatDiagramRender`'s font, canvas and helpers are package-private for it. Both tools draw the footer's unit keys as display keys on their strip and the theme key as its contrast sign (`GUI_TOOLKIT_PLAN.md` section 12).
- 2026-10-28, batch 2026-10-28-gui-toolkit: the prototypes draw entries as LCDs and readings (`Door.reading`, `Column.Reading`) as LED meters; option sheets for the display keys and the fields came and went with the owner's choices (`GUI_TOOLKIT_PLAN.md` sections 12 and 13).
- 2026-10-28, batch 2026-10-28-gui-toolkit: the device pages (pump, generator, valve, tank, and a new pipe page) draw the shipped `DeviceColumn` through a `ScrollView` at the screen's geometry, opened and scrolled to the end (`Prototype.scrolls`, `Door.end`); a column's display key draws as one. `HeatDiagramRender.tr` writes a line with no arguments as it stands, so "Volume %" no longer throws (`GUI_TOOLKIT_PLAN.md` section 14).
- 2026-10-28, batch 2026-10-28-gui-toolkit: the small dialogs draw the shipped `DialogColumns` (`GUI_TOOLKIT_PLAN.md` section 15): the gauge, a thermometer with its warning line, the pipe laying tool with its palette, and two datasheets (a drum; an air cooler with moving parts, a blocked-cells warning and wrapped nozzle roles). The glass-lines option sheet (A indent, B bands, C rules) came and went with the owner's pick, B. Each panel is as tall as its screen sizes it (`dialogHeight`); the footer comes from `DialogColumns.footer`.
