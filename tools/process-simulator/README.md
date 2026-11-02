# process-simulator

Purpose: prove on the shipped science that a client can run the process simulator, and supply the real numbers its design sheets show.

Batch: `2026-11-02-process-simulator` (`PROCESS_SIMULATOR_PLAN.md`, sections 1 and 2).

## How to run

From the worktree root, in PowerShell, after `./gradlew compileJava processResources`. The class path holds the mod's classes, its resources and gson, and no Minecraft classes, as a client's plain background thread would see the science:

```powershell
$g = "<gradle cache>\com.google.code.gson\gson\2.10.1\b3add478d4382b78ea20b1671390a858002feb6c\gson-2.10.1.jar"
java "-Dfile.encoding=UTF-8" -cp "build\classes\java\main;build\resources\main;$g" "tools\process-simulator\ClientRunProbe.java"
java "-Dfile.encoding=UTF-8" -cp "build\classes\java\main;build\resources\main;$g" "tools\process-simulator\SampleFlowsheetProbe.java"
```

Git Bash mangles the `;` class path; use PowerShell.

## Entry points

- `ClientRunProbe.java`: times the catalog, the neural bind, the default column (cold, three runs), a warm column at feed +1 %, +2 K, and TP and PH flashes; re-reads the column's balance in the stream thermo and prints the gap.
- `SampleFlowsheetProbe.java`: the sheets' crude unit solved by hand in the order the plan's solver would take: P-101, then the loop E-101, H-101, V-101, T-101 torn at the bottoms, then P-102, E-102. Prints each pass, the 16 streams, the units' duties, the closures, the column's preset and tray profile, the phase splits and the compositions.

## Results, 2026-11-02, at `79b853e0`

Catalog 220 ms, bind 187 ms, column cold 1.7 to 2.5 s, warm 0.25 to 0.31 s, TP flash 0.06 to 0.4 ms, PH flash 0.3 to 0.5 ms. The sample: 2 passes, 3.3 to 5.1 s from a cold JVM on one thread, mass closed, heat gap 0.001 MW of 211 MW, 10 MB held afterwards.

## History

- 2026-11-02: written for the batch's design phase. The numbers in `tools/heat-gui-renders/SimulatorPrototypes.java` are copied from `SampleFlowsheetProbe`'s output; a change in the sample changes both.
