# sight-glass-frames

A frame strip of a sight glass's bubbles (batch `2026-11-18-equipment-reflections`, part 3): a 0.1 m window at gas
share 200/255, five runs (horizontal 0.05, 0.5 and 2 m/s; vertical up and down 0.5 m/s), 8 frames each, 2 ticks apart,
on one sheet. The motion is the production law (`SightGlassGeometry.velocity`, `Travel`, `bubbles`). The shading of a
bubble is a copy, in Java, of `bubbleShade` and `main` in `assets/icheme/shaders/core/sight_glass.fsh`: change both
together. Full light, no fog, the liquid colour `0xC8A17A40`. Only a dev client shows the real look.

## Run

From the repository root, after `./gradlew compileJava` (no Gradle task of its own; plain `java`, Minecraft-free):

```
java -cp build/classes/java/main tools/sight-glass-frames/SightGlassFrames.java tmp/sight-glass-frames/sheet.png
```

The sheet is git-ignored scratch; keep it under `tmp/`.

## Files

- `SightGlassFrames.java`: the runs, the frames and the copied shading.

## History

- 2026-11-18: written for part 3 of the batch (review section 12).
