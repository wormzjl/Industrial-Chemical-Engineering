# heat-gui-renders

Purpose: software renders of the heat units' Control-tab flowsheet, so its layout is judged without a game client (owner: enough client testing).

Batch: `2026-10-27-heat-exchanger-design`, the heat GUI task (`HEAT_GUI_PLAN.md` section 4), branch `claude/heat-gui-redesign-abb1db`; then `2026-10-28-gui-toolkit` (`GuiPrototypes.java`, the kit's renders). Status: in use since 2026-10-28; it draws the shipped screen code, so it stays as a check of later GUI work.

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

The process simulator's design sheets (`SimulatorPrototypes.java`, batch 2026-11-02-process-simulator, numbers from `tools/process-simulator/SampleFlowsheetProbe.java` and `LoopProbe.java`; finished sheets go to the main checkout's `research/2026-11-02-process-simulator/renders/`):

```bash
java -Dfile.encoding=UTF-8 -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/SimulatorPrototypes.java src/main/resources/assets/icheme/lang/en_us.json tmp/sim-prototypes
```

The built simulator screen (`SimulatorRender.java`, batch 2026-11-02-process-simulator): sheets 01 to 27 from shipped code on a real `FlowsheetEngine` run, one file per door. It needs the resources and gson on the class path, so run it from PowerShell (Git Bash mangles `;`), after `./gradlew compileJava processResources`; `$g` is the gson jar in the Gradle cache. Finished sheets go to the main checkout's `research/2026-11-02-process-simulator/renders-w6/`:

```bash
java -Dfile.encoding=UTF-8 -Duser.language=en -Djava.awt.headless=true -cp "build\classes\java\main;build\resources\main;$g" tools\heat-gui-renders\SimulatorRender.java src\main\resources\assets\icheme\lang\en_us.json tmp\sim-render
```

The symbol atlas (`SymbolAtlas.java`) is committed baked; run it again only when `SimulatorIcons` changes, and `SimulatorFrameTest` holds its size to `Symbol`:

```bash
java -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/SymbolAtlas.java src/main/resources/assets/icheme/textures/gui/flowsheet/symbols.png
```

## Entry points

- `HeatDiagramRender.java`: a single-file Java program (Java2D, headless). It lays the page out as `FluidDeviceScreen` does and draws it with the shipped code: `PanelSkin` for the page and the widgets, `HeatControlColumn` in layout C, `HeatFlowsheet`. Only the page's order of drawing and the illustrative readings are its own. Line parts cycle teal, yellow and red to show the legend's colours.
- The English texts come from `en_us.json`; a key without a line is drawn as `?` and the key.
- `GuiPrototypes.java`: proposed screens drawn through the same kit (`Column`, `PanelSkin`) and `HeatDiagramRender`'s font and canvas (the multi-file source launcher, JDK 22 or later). Its layouts are proposals for the owner, not the screens' code, except the fluid device pages: their door column is the shipped `DeviceColumn` at `FluidGuiLayout.side`, at the scale a window takes for them (`WindowScale`, `-Dwindow`), shown as opened and scrolled to its end where it scrolls, and the tool prints each column's height.
- `OperationPrototypes.java`: the column's Operation tab (batch 2026-10-28-column-operation, C7): the shipped `OperationColumn` on illustrative data, at the 1920 by 1080 window's size and a narrow window's.
- `SimulatorPrototypes.java`: the proposed simulator screens, 30 sheets, through the same kit, `GuiPrototypes` and `HeatDiagramRender`. The approved look; W6 built what its sheets 01 to 21 show, and the sizing, template and loop sheets wait for their work packages.
- `SimulatorRender.java`: the built screen. `Scene` holds an editor, view and gestures as the screen does, `screen` and `door` draw through `SimulatorPage`, `DoorFrame` and `SheetPainter`, and only the hover box is its own, because the game draws that. It runs the sample, a sheet of vessels and a sheet with a removed unit.
- `SymbolAtlas.java`: bakes `SimulatorIcons`' shapes into `textures/gui/flowsheet/symbols.png`, the layers `Symbol` names.
- `SimulatorIcons.java`: the simulator's equipment symbols as vector shapes, in three styles, drawn at any size.
- `SimulatorSizing.java`: the Sizing tab. Its numbers come from `tools/process-simulator/sizing-sample.tsv`, which `SizingProbe` writes, so a sheet cannot drift from the probe.

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
- 2026-10-28, batch 2026-10-28-gui-toolkit: the equipment constructor (choose, design, lying drum, column, nozzles, paint), the assembly screen (checked; a part selected with a check queued) and the column calculator (464, 380 and 800 px wide, the Results page, the columns scrolled to their end) draw the shipped `ConstructorColumn`, `AssemblyColumn` with `AssemblyFlowsheet`, and `CalculatorColumn` (`GUI_TOOLKIT_PLAN.md` sections 16 to 18).
- 2026-10-28, batch 2026-10-28-gui-toolkit: an option sheet drew the calculator's Results page in the same 1600 by 840 screen px as today, zoomed in whole steps (A), stretched (B) and zoomed to fill (C); it went with the owner's pick, A on every screen (`WindowScale`, `GUI_TOOLKIT_PLAN.md` section 19). The calculator's two wide renders are now its 533 by 280 door, what a 1600 by 840 window shows.
- 2026-10-28, batch 2026-10-28-gui-toolkit: the fluid device pages are drawn at the scale a window takes for them (`devicePage`, `-Dwindow=WIDTHxHEIGHT`, 1920x1080 by default; it prints the scale, the design size's and the minimum), and a valve in a 1366 by 768 window, where it scrolls. An option sheet drew a pump and a valve in a 2560 by 1440 window under three scale rules; it went with the owner's pick, (b) one scale for every device screen (`GUI_TOOLKIT_PLAN.md` section 20).
- 2026-10-28, batch 2026-10-28-gui-toolkit: a compressor's page (`fluid-compressor`, its pressure ratio with the pressure change on hover). An option sheet drew it trimmed three ways at 2560 by 1440; it went with the owner's pick, A (`GUI_TOOLKIT_PLAN.md` section 20).
- 2026-10-28, batch 2026-10-28-constructor-templates: the constructor's column design page with Import and Templates (`equipment-constructor-column-design`) and its Templates page (`equipment-constructor-templates`); a latched key is drawn latched whether it acts or not, as `SkinButton` draws it (also in `HeatDiagramRender`).
- 2026-10-29, batch 2026-10-28-column-operation: `OperationPrototypes.java`, the column's Operation tab in two page sizes (`operation-a-design-size`, `operation-b-taller-page`), called from `GuiPrototypes.prototypes()`; `-Donly=<file prefix>` draws only the prototypes whose file starts with it.
- 2026-10-29, batch 2026-10-28-column-operation: the owner chose option B; `OperationPrototypes.java` now draws the shipped `OperationColumn` (`operation-1920x1080`, `operation-narrow-largest`).
- 2026-11-02, batch 2026-11-02-process-simulator: `SimulatorPrototypes.java`, 18 design sheets of the in-game process simulator, for the owner to approve before any build. Its numbers are copied from `tools/process-simulator/SampleFlowsheetProbe.java`.
- 2026-11-02, batch 2026-11-02-process-simulator: the owner's decisions redrew the main screen and the selection panel, with the reconnect page. `SimulatorIcons.java`: vector symbols in three options. `SimulatorSizing.java`: the Sizing tab, sheets 24 to 27, drawn from `SizingProbe`'s table. 29 sheets. `HeatDiagramRender.Java2dCanvas.graphics()` gives them the canvas's `Graphics2D`.
- 2026-11-02, batch 2026-11-02-process-simulator: one palette tile for each function, sheet 28 for the type proposals and an exchanger's door after sizing. 30 sheets.
- 2026-11-03, batch 2026-11-02-process-simulator: W6. `SimulatorRender.java` draws the built screen from shipped code, `SymbolAtlas.java` bakes its symbols, and `GuiPrototypes.java` passes `TowerBank.ONE`, which the cooling tower merge asked for. The source launcher compiles tool files lazily, so a stale tool file fails only when it is reached.
- 2026-11-03, batch 2026-11-02-process-simulator: W7. `SimulatorRender.java` gains an edit refused during a run, the template lists (13), Solve alone on a real `SimulatorRunner` (22) and the data notice (23); hosts that can run light the column door's keys.
- 2026-11-03, batch 2026-11-02-process-simulator: W8. `SimulatorRender.java` takes the block's title; its host can speak (24, what a block says); 08 gains Paste, 09 is the tank's Copy, 13 the template saves, 17 an autosave, 25 the unsaved prompt and Save as. `GuiPrototypes.java` names the block's new key.
- 2026-11-03, batch 2026-11-02-process-simulator: the column block (D23). `SimulatorRender.java` places the sample round T-101's frame and fits every scene; 26 draws what the door can ask of the block, on no streams, and 27 its tones from real runs: not solved, a condenser too warm, a pressure that fails. `BlockOptions.java`, moved from `tmp/`, drew `renders/options.png` and `option-c-rearranged.png`: run it on the same class path as `SimulatorRender.java`, with the output file as its argument, and any second argument for the rearranged sheet alone.
- 2026-11-03, batch 2026-11-02-process-simulator: W9. `SimulatorIcons.java` draws the cooling tower. `SimulatorRender.java` gains the ring on a selected column (02), the Heat tiles (03), Delete off on a bundled template (13) and the four rows of the changes dialog (17). The texts file is an argument, so a Chinese run takes `zh_cn` laid over `en_us`, as the game falls back: `HeatDiagramRender.GameFont` draws a glyph its sheets lack in a sans font at a 10 px advance, not in the unifont.
- 2026-11-03, batch 2026-11-02-process-simulator: S4b. `SimulatorRender.java` draws the Sizing tab (`-Donly=sizing`): a real Size all of the sample, the four views, a Fixed size rated red, a run that is not steady, the type keys, the exchanger's door before and after sizing, and Size all mid-run.
- 2026-11-03, batch 2026-11-02-process-simulator: W10b. `SimulatorRender.java -Donly=ejector` draws the same sheets on the vacuum set's law S1; sheets 01, 02 and 05 (the selection panel, the doors and the Sizing rows) went to `renders-w10b/`.
- 2026-11-03, batch 2026-11-02-process-simulator: W10. `SimulatorRender.java -Donly=ejector` draws the ejector on a sheet of its own. It draws the lit tile with its Motive port free, at 1.1 and at 0.9 times the need, and their doors. It draws the column block's Motive port at a 30 kPa top with each condenser, and the column door's Ejector group. It draws the Sizing rows after a real Size all. It prints the need and each size's closest row.
- 2026-11-03, batch 2026-11-02-process-simulator: S5. `SimulatorRender.java` draws Send to block (`-Donly=send`) after one and two real Size alls of the sample, with its hover, and prints every red row of both; the constructor's Import page with the designs Send to block stores, Import on the choice page, and the condenser landed on the draft. `GuiPrototypes.java` passes the choice page's reach.
- 2026-11-03, batch 2026-11-02-process-simulator: S3b, member counts. `GuiPrototypes.java` gains `assembly-member-counts` (three condenser shells asked for and two found, two kettles, a PA cooler shell of another size); `SimulatorRender.java` with `-Donly=member-counts` draws only 28 (the column door's Equipment page with counts) and 29 (the block's "×N"), after the sample's run, and passes the sheet's sizing facts, which S4a added to `Flowsheet`'s constructor. The gson jar on its class path is 2.10.1: 2.9.1 lacks `JsonObject.isEmpty`. Sheets in the main checkout's `research/2026-11-02-process-simulator/renders-s3b/`.
- 2026-11-03, batch 2026-11-02-process-simulator: C1, the air-then-water condenser. `GuiPrototypes.java` gains `column-calculator-air-then-water` (464 and 380 wide: the Equipment page with its third key, Banks and Trim shells), `assembly-air-then-water` (the door, the trim selected) and `assembly-air-then-water-drawing` (the drawing alone at its 680 by 724 design size, with an ejector). The calculator door takes the Equipment page; its glass stays blank there. Sheets in the main checkout's `research/2026-11-02-process-simulator/renders-c1/`.
- 2026-11-03, batch 2026-11-02-process-simulator: C3, the cooler model's screens. `SimulatorRender.java -Donly=c3` draws the air-then-water column (sheet 01), its door's Equipment page (02), the results' units and log (03), a feed whose flow its column sets (04) and the Sizing page's lines beside the picked Cooler and column (05); the doors' columns now clip to their view, as the game's scroll view does. `HeatDiagramRender.java` gains `exchanger-feed` and `exchanger-feed-both` (the `FEED` lines and the Column key), `OperationPrototypes.java` a fans duty row. `CoolerOptions.java` draws the water cooler symbol's options A to D from `SimulatorIcons.waterCooler` (`tmp/c3/cooler-options.png`). Chinese: give the tools `en_us.json` and `zh_cn.json` concatenated, in that order (the later key wins). Sheets in `research/2026-11-02-process-simulator/renders-c3/`.
