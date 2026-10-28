# heat-gui-renders

Purpose: software renders of the heat units' Control-tab flowsheet, so its layout is judged without a game client (owner: enough client testing).

Batch: `2026-10-27-heat-exchanger-design`, the heat GUI task (`HEAT_GUI_PLAN.md` section 4), branch `claude/heat-gui-redesign-abb1db`.

## How to run

From the worktree root, after `./gradlew compileJava`:

```bash
java -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/HeatDiagramRender.java src/main/resources/assets/icheme/lang/en_us.json tmp/heat-gui-renders
```

It writes nine PNGs to `tmp/heat-gui-renders/`: the exchanger's tube side under six line designs (none, tube bypass, shell bypass, both bypasses, ratio, bypass with ratio), the air cooler, the fired heater and the kettle.

## Entry points

- `HeatDiagramRender.java`: a single-file Java program (Java2D, headless). It draws the records of `client/gui/common/HeatDiagram` (the same parts, wires, pills, tags and nozzles the screen draws) at twice the design grid, with the game's palette, a 9 px sans font and illustrative readings. Part states cycle teal, yellow and red to show the legend's colours.
- The English texts come from `en_us.json`; a key without a line is drawn as `?` and the key.

## History

- 2026-10-28: written for the heat GUI task. First as SVG; changed to PNG because the in-app browser cannot open local files and PNGs can be read directly. Utility lines drawn after the parts, as the screen does (the kettle's bundle and weir lie inside the drum). Font 9 px, the game font's height; the kettle's bundle moved under the drum's label.
