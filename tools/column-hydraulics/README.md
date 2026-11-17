# column-hydraulics

Purpose: the numbers of a running column's internal streams, for the limits of batch `2026-11-17-column-internal-hydraulics`
(`COLUMN_HYDRAULICS_PLAN.md`, work package 1).

## How to run

From the worktree root, in PowerShell, after `./gradlew compileJava processResources`. The class path holds the mod's
classes, its resources, gson and the EJML jars of the Gradle cache (`modules-2\files-2.1\org.ejml`), and no Minecraft classes:

```powershell
java "-Dfile.encoding=UTF-8" -cp "build\classes\java\main;build\resources\main;<gson jar>;<ejml jars>" "tools\column-hydraulics\ReviewPlantProbe.java"
```

## Entry points

- `ReviewPlantProbe.java`: the review plant (the network's default column template at 1/16, as
  `ColumnOperationReviewWorld.sizing` builds its sheet), run and sized by Size all. Prints each stream, each line's steady
  flow, density, viscosity and speed limit, its speed and its drop over 20 and 30 m at each bore from 0.025 to 0.25 m, and
  each member pump's load against the casings' rated flows.

## Results, 2026-11-17

Feed 44.4 mol/s, top 2.5 bar. Overhead 0.648 m3/s at 5.12 kg/m3: 330 m/s and 21.4 bar over 20 m at 0.05 m, 20.6 m/s and
0.016 bar at the sized 0.20 m. Condensate 15.1 m3/h; pumparounds 32.7, 46.5 and 27.7 m3/h at a 60 K fall. Bottoms
26.6 m3/h at 822 kg/m3 and 2.66 bar. The plan's section 2 holds the tables.
