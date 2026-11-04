# Frame-time review: the process simulator in game (batch 2026-11-02)

One in-game session, 2026-11-04 19:15–19:26Z, on the current code `a958cecd2` (`e3f4ef21c` is docs only), after s1's bench ended. Probe: `tools/custom-equipment-rendering-poc/frame-time-probe/` (GPU timestamp ring, simulator autopilot), attached uncommitted to `src/mcpCompat` for the run and removed after. Lane: `runMcpClient`, 1920 × 1080, `JAVA_OPTS=-Xshare:off`, background mode, no desktop input; `simulator-lane/` holds its `autopilot.properties` and `options.txt`. Logs: the main checkout's `tools/custom-equipment-rendering-poc/logs/frame-time-sim-logs-2026-11-04.tar.gz`; screenshots: `research/2026-11-02-process-simulator/shots/frame-time/`.

## Method

- A fresh flat creative world, `simulator-fps-2026-11-04`, made by the autopilot for this check (refused if a save of that name exists). Noon, no weather or mobs, a fixed camera, one process simulator block.
- Cases, each 60 s warm-up then 60 s measured: `none` (the world, no screen), then the block opened, the bundled crude unit loaded and fitted, Run pressed and waited for until steady (7 s) plus 5 s, so no run's CPU falls in a window; `idle`; `pan` (2 px a frame back and forth; the screenshot shows the sheet moved); `door` (T-101's door, which is the column's own screen, the world drawn behind it); `results` (the Results tab, streams view).
- GPU: four `GL_TIMESTAMP` counters a frame (frame start, screen start, screen end after a `GuiGraphics.flush()`, frame end) in a ring of 8 frames, read when available. No sample was missed in any case. CPU screen: `nanoTime` between `ScreenEvent.Render.Pre` and `Post`.
- Frames are not capped: `maxFps:260` is vanilla's "Unlimited", and vsync is off. So frame ms is the frame's whole cost.

## Results (ms, p50 / p95)

| Case | FPS | Frame | Frame over `none` | GPU frame | GPU frame over `none` | GPU screen | CPU screen |
| --- | --- | --- | --- | --- | --- | --- | --- |
| none | 2634 | 0.34 / 0.53 | – | 0.11 / 0.11 | – | – | – |
| idle | 299 | 3.22 / 4.14 | +2.88 | 2.37 / 3.13 | +2.26 | 2.20 / 2.98 | 2.77 / 3.06 |
| pan | 300 | 3.22 / 4.06 | +2.88 | 2.37 / 3.14 | +2.26 | 2.20 / 2.99 | 2.77 / 3.06 |
| door (T-101) | 381 | 2.53 / 3.28 | +2.19 | 1.74 / 2.29 | +1.63 | 1.60 / 2.14 | 2.10 / 2.33 |
| results | 268 | 3.60 / 4.43 | +3.26 | 2.81 / 3.43 | +2.70 | 2.64 / 3.25 | 3.14 / 3.48 |

p99 frame: 0.88, 4.77, 4.72, 3.74, 5.31 ms; max 16.95 ms (`none`, a world hitch), otherwise at most 9.36 ms.

## Machine load (each window)

System CPU 26–31 % of 16 logical cores. Top users each window: the client JVM 2.2–2.6 cores, one `geosim.exe` 1.11–1.13 cores, music and chat apps under 0.2. No Gradle build, no bench and no other Minecraft client ran. The other geosim processes the orchestrator saw were below 0.05 core during the session.

## Reading

1. The screen costs 2.2–2.7 GPU ms and 2.1–3.1 CPU ms a frame on this machine (RTX 4070 Ti, GL 4.6), about 1/6 of a 60 FPS budget. Results is the dearest (+3.3 ms), the column door the cheapest (+2.2 ms).
2. GPU screen time tracks CPU screen time (about 0.5 ms below it in every case). A GPU timestamp span counts the GPU's idle gaps while it waits for the CPU's submits. With one draw per fill, text or blit (about 3,400 a frame on this page), the span therefore measures the submit stream more than the shading. GPU ms falls with the draw count, so the draw-call counters are the right measure for judging the optimizations.
3. Panning costs nothing over idle: the screen redraws everything every frame, moved or not.
4. The session judges only this machine. The headroom is smaller on a weaker GPU or driver, where each draw costs more.

## Defect found on the way (not fixed)

- D2: T-101's column screen shows "Top pressure 250.0 bar". The bundled sheet has `top_pressure: 250000.0` Pa, which is 2.5 bar (screenshot `fps-sim-door.png`). The screen looks like it shows the kPa number under a bar label. To check in thread B.

Code guide: no change (the probe lives in `tools/`).
