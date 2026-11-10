# fx-textures

Bakes the effect textures that the COMPATIBLE path samples under a shader pack (batch `2026-11-10-custom-shader-system`, plan sections 4.1 and 4.5; `FxRenderTypes`, `FxImmediateSink`). The noise and shapes follow the SHADER path's `fx_flame.fsh` and `fx_plume.fsh`.

| File | Size | Content |
|---|---|---|
| `flame_flipbook.png` | 64 × 1024 | 8 frames of 64 × 128, top to bottom, tip at each frame's top; near-white albedo (the vertex colour gives the hue); the frames loop |
| `flame_flipbook_s.png` | 64 × 1024 | LabPBR 1.3 specular: R smoothness 0, G F0 10, B 0, A emission 0 to 254 over the flame's body |
| `smoke.png`, `plume.png` | 128 × 128 | four soft puffs in a 2 × 2 grid, white albedo lit from above |

## Run

Python standard library only (`zlib`, `struct`); the output is the same on every run. `python` in Git Bash is a stub, so use the bundled runtime:

```sh
"C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe" -I tools/fx-textures/make_textures.py tmp/fx-effects/textures
cp tmp/fx-effects/textures/*.png src/main/resources/assets/icheme/textures/fx/
```

Any Python 3.8 or later works the same: `python -I tools/fx-textures/make_textures.py <output folder>`.
