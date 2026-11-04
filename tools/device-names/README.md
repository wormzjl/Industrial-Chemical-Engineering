# device-names

Purpose: the probe and the software render of the nameplate placement (`Nameplate.of`), and the name-entry option prototypes. Batch `documentation/2026-11-02-process-simulator/` (`DEVICE_NAMES_PLAN.md`, task N1).

Nothing here was detached from a tracked path: both files were written for this task and never sat in `src/`. The name-entry options (`name-a`, `name-b`, `name-b-dialog`, `name-c`) lived in `../heat-gui-renders/GuiPrototypes.java` until the owner picked A.

## Running

From the worktree root, after `./gradlew compileJava` (the science tree imports nothing from Minecraft):

```
javac -proc:none -encoding UTF-8 -cp build/classes/java/main -d tmp/n1/probe tools/device-names/PlateProbe.java
java -cp "tmp/n1/probe;build/classes/java/main;src/main/resources" com.wormzjl.icheme.science.equipment.PlateProbe > tmp/n1/probe.txt
javac -proc:none -encoding UTF-8 -cp build/classes/java/main -d tmp/n1/probe tools/device-names/PlateRender.java
java -Djava.awt.headless=true -cp "tmp/n1/probe;build/classes/java/main;src/main/resources" com.wormzjl.icheme.science.equipment.PlateRender tmp/n1/plates-close.png 40 close
```

## Entry points

- `PlateProbe.java`: every shape of `EquipmentLayoutGoldenTest`'s list (each kind at its least, middle and largest size, facing north): the plate's side, size, centre and the time `Nameplate.of` took, or "none".
- `PlateRender.java` (adapted from `../column-sump/ColumnRender.java`): software render of shapes with their plate drawn in the three looks P1 (light plate), P2 (stencil), P3 (dark plate). Args `out.png pxPerMetre [close]`; `close` draws a 4 by 3 m window round each plate.

## History

- 2026-11-03: written for N1. Probe: 77 golden shapes take a front plate, 5 none (`DRUM 0.5`, `EXCHANGER 0.5`, `EJECTOR 0.05`, `EJECTOR 0.45`, `CYCLONE 0.3`), 2 to 180 ms a shape. Renders sent to the owner: `research/2026-11-02-process-simulator/renders-n1/plates-close.png`, `plates-overview.png`.
- 2026-11-03: the owner picked A and P1 ("A and P1"); the option prototypes left `../heat-gui-renders/GuiPrototypes.java` and the shipped screens' renders are `renders-n1/shipped-*.png`. P1 is what `NameplateRenderer` draws; `PlateRender.java` keeps P2 and P3 as the record.
