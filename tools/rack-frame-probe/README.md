# rack-frame-probe (batch 2026-11-03-save-corruption)

Purpose: counts how often a rack lane's registered face points leave its rack cell's frame open. Every rack frame (4 facings × 6 shapes), every drawn bore of the batch's table, every lane option and every pair of arms that frame takes: the lane's key is its option, width, arms, size code and face points. A key that more than one frame gives is ambiguous.

It decided W5 step 5 (REGISTRY_FIRST_PLAN.md 3.1): 544 of 1,376 lane keys fit more than one frame, 228 of them frames of different shapes (a straight cell and a tee's leg). So the frame is a fact: fluid facts schema 9, then 15 after the main merge; cable lanes in `ControlFacts` format 4 (W5c C2).

- `ambiguity.jsh`: the probe, a jshell script on the compiled main classes (`RackGeometry`, `PipeSizes`). Gradle-free; it reads no world.

Run (Git Bash, from the worktree root, after `./gradlew compileJava`):

```bash
jshell --class-path build/classes/java/main tools/rack-frame-probe/ambiguity.jsh
```

It prints `lane keys=1376 ambiguous=544 ambiguousAcrossShapes=228` and up to eight examples (`option/width/arms/code/points -> [facing:shape, ...]`).

History:
- 2026-11-04: written in `tmp/rack-frame/` for W5 step 5.
- 2026-11-09: moved here at the batch's tooling cleanup; same counts on the merging branch.
