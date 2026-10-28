# heat-gui-renders

Purpose: software renders of the heat units' Control-tab flowsheet, so its layout is judged without a game client (owner: enough client testing).

Batch: `2026-10-27-heat-exchanger-design`, the heat GUI task (`HEAT_GUI_PLAN.md` section 4), branch `claude/heat-gui-redesign-abb1db`.

## How to run

From the worktree root, after `./gradlew compileJava`:

```bash
java -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/HeatDiagramRender.java src/main/resources/assets/icheme/lang/en_us.json tmp/heat-gui-renders
```

It writes 22 PNGs to `tmp/heat-gui-renders/`: eleven Control pages (the exchanger under six line designs, the air cooler as editor and reader, the fired heater, the kettle's tubes and its pool), each as it opens and scrolled to the end (`-scrolled`).

## Entry points

- `HeatDiagramRender.java`: a single-file Java program (Java2D, headless). It lays the page out as `FluidDeviceScreen` does and draws it with the shipped code; only the page frame (title, tabs, status, footer) and the game's widgets (plain boxes) are its own. Line parts cycle teal, yellow and red to show the legend's colours.
- The English texts come from `en_us.json`; a key without a line is drawn as `?` and the key.

## History

- 2026-10-28: written for the heat GUI task. First as SVG; changed to PNG because the in-app browser cannot open local files and PNGs can be read directly. Utility lines drawn after the parts, as the screen does (the kettle's bundle and weir lie inside the drum). Font 9 px, the game font's height; the kettle's bundle moved under the drum's label.
- 2026-10-28, batch 2026-10-28-gui-toolkit: renders whole Control pages at 464 by 254 px (as shown and scrolled to the end) through the shipped code: `HeatFlowsheet` and `HeatControlColumn` on the GUI kit (`Column`, `ScrollView`, `FlowsheetPainter`) on a Java2D canvas. Its own copy of the drawing code is gone. It runs with only the mod's classes, which checks that those classes hold no Minecraft classes.
