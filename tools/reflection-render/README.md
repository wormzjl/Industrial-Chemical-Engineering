# reflection-render

Software renders of the equipment reflection pass (batch `2026-11-18-equipment-reflections`): a drum (2 x 6 m), a
column (2 m, 12 trays), an exchanger (1.5 m) and the smallest pump, each from two views (azimuth 35 degrees, 22 up;
azimuth 215 degrees, 8 up), side by side as the chunk mesh draws them today (directional shade plus the baked sheen 0.2)
and with the pass (shade without the sheen plus `ReflectionLaw.added`). The renderer uses the production table and law
(`client/model/ReflectionClass`, `ReflectionLaw`) and the drawn body (`ShapeRasterizer.renderBody`), so the picture and
the shader share one source.

Simplifications: each material in its sprite's mean colour (painted ones in the default paint, RAL 9006 / RAL 7031), no
textures, full lightmap and sky light, no fog, the law's preview sky (`ZENITH`, `HORIZON`) at full daylight, the sun at
44 degrees in the east (`SUN_ANGLE` -0.8 rad), no nozzles (the pass draws none). Only a dev client shows the real look.

## Run

From the repository root (one Gradle invocation; it compiles `src/main` and this tool, starts no client):

```
./gradlew --init-script tools/reflection-render/render.init.gradle renderReflections --no-configuration-cache --max-workers=4
```

Writes `tmp/reflection-render/<drum|column|exchanger|pump>.png` (four panels each) and `sheet.png` (all four rows);
`-PreflectionRenderOut=<folder>` writes elsewhere. The images are git-ignored scratch; keep them under `tmp/`.

## Files

- `ReflectionRender.java`: the scenes, a z-buffer rasterizer with perspective-correct normals, the two looks.
- `render.init.gradle`: the `reflectionRenderTool` source set and the `renderReflections` task.

## History

- 2026-11-18: written for the reflection pass review (`REFLECTION_PASS_REVIEW.md`).
