# Process simulator performance review (2026-11-04)

Owner, 2026-11-04: "bench the CPU, GPU, ram performance of current simulator implementation, optimize and consolidate as much as you can". This is the offline bench; the in-game frame times are in `FRAME_TIME_REVIEW.md`.

**Method.**
- `tools/simulator-bench/` at `a958cecd2`, on the bundled crude unit. JDK 21.0.11, `-Xmx4g`, G1, C++ column library loaded, no game running. One JVM a phase: 60 s warm-up, 60 s measured, JFR over the measured window.
- Counters: bytes the bench thread allocates; checkpoints (every flash and solver step calls one, so they stand in for a flash count); draw calls and quads, counted as `DrawCallTest` counts them; retained heap (used after a full GC, with and without).
- The machine was shared: another project's `geosim` held about 5 of 16 cores (CPU 17–34 %), and a JVM held 8 cores at the end of `size` (72–75 %). So the ranking goes by counters, and the CPU times stand next to that load.
- Out folder: `tmp/simulator-bench/baseline/`, with `histo-20/` for the caches.

## What it costs
| Phase | Wall / own CPU | Allocated | Counters |
|---|---|---|---|
| Run, cold (no results) | 7.40 / 7.18 s; +2.95 s VM threads | 6.54 GB | 3 passes, 854k checkpoints; T-101 99.3 % |
| Run, from the last steady run | 2.90 / 2.76 s; +1.98 s VM | 2.21 GB | 1 pass, 239k checkpoints; T-101 98.0 % |
| First Run in a fresh JVM | 9.0 / 8.8 s | 6.53 GB | JIT warm-up +1.6 s |
| Size all (load 72–75 %) | 8.91 / 8.43 s; +4.28 s VM | 7.27 GB | — |
| Quiet rating, after each steady Run | 41.6 / 36.6 ms | 10.7 MB | — |
| Edit: move step / feed entry | 0.090 / 0.089 ms | 215 / 217 kB | `SheetState.of` 0.040, `SheetLayout.of` 0.049 ms |
| Autosave, on the render thread | 0.56 ms; max 4.6 s (disk stall) | 163 kB | — |
| Open: 50 files + the example | 5.4 ms | 8.3 MB | rows keep 58 kB |
| Frame: Flowsheet idle / T-101 selected | 0.020 / 0.028 ms | 45 / 65 kB | 758 / 829 draw calls; 15,460 / 16,336 vertices |
| Frame: feed / column door / its Results | 0.048 / 0.052 / 0.038 ms | 119 / 134 / 88 kB | 348 / 446 / 338 calls; 6,408 / 4,500 / 4,972 vertices |
| Frame: Results / Sizing / Open | 0.007 / 0.083 / 0.004 ms | 18 / 177 / 6 kB | 485 / 545 / 247 calls; 3,084 / 8,292 / 7,808 vertices |

**GC.** During Run, G1 made 842 young collections in 66.6 s: 1.1 s of pauses, 12.6 a second. The VM threads' CPU is GC and JIT. The Flowsheet page's draws are 3356 fills, 74 texts and 103 sprites.

**Retained heap.** A run's `FlowsheetRun` holds 0.4 MB. The editor with its layout holds 140 kB, a rating 165 kB, the props caches 127 kB. 100 undo steps hold 18 to 312 kB.

**Shape caches.** Every Run on a new input leaves 12.4 MB: `double[]`, `Mesh$Facet`, `Mesh$Uv`, `ShapeRasterizer$Cell`. Twenty Runs on feeds 1 K apart, results dropped, grew the heap 12.4 MB a Run to 211 MB at the 17th. Then `ShapeRasterizer`'s 300,000-facet bound cleared it.

## Where it goes
- **Run and Size all are the column.** T-101 takes 98–99 % of Run. JFR on Run's thread: `V3BandedPivotedSolver.solve` 31.7 % (its right-hand-side copy and norms another 6–12 %), Peng-Robinson with `log` and `exp` 12 %, `V3MeshResidualEvaluator` 10 %, `V3DegreeOfFreedomLedger.augment` 4.9 %. The C++ library does only the Peng-Robinson batch; the Newton and banded solves are Java. Allocation comes from residual evaluation, coordinate decode, the Jacobian's empty blocks, banded matrices and fugacity results.
- **The column restarts cold.** Each Run compiles a new `ColumnUnit.Solve`, so its warm seed dies with the Run; only the tears carry over (`steady`). The column re-solves up to 3 times a pass (`FEED_ROUNDS`). A block's restored run is `last`, not `steady`, so the first Run after reopening takes 3 passes.
- **The rating rasterizes shapes.** Each new proposed column size is a new `EquipmentShape`. `NozzleBore.largest` rasterizes it to search its bores. The world's caches keep it: `ShapeRasterizer.GEOMETRIES`, and `ProcessUnit.BUILT`, `ColumnStructure.LAYOUTS` and `NozzleBore.BORES`, which hold up to 64 shapes each.
- **Frames cost draw calls, not CPU.** In game the screen costs +2.2 to +3.3 ms a frame, and its GPU time follows the draw count. The Flowsheet page's 758 calls are mostly 581 runs of fills that texts and sprites break. Results is the dearest in game but counts only 485 calls here, so this counter misses some of the game's draws. Doors are built twice a frame (`signature()` and the draw), and their params are read from JSON (`LinkedTreeMap.find` takes 18 % of a column door frame).
- JFR's allocation views show about 50 % `jdk.internal.org.objectweb.asm`: the recording instrumenting its own events. The per-call bytes exclude it.

## Proposals, by saving against risk
1. **Keep simulator shapes out of the world's shape caches.** Ask `NozzleBore` for a sheet's bores without caching the geometry, or keep a small cache of the simulator's own. Saves up to 211 MB of client heap and a rasterization on every new input, and stops evicting the world's shapes. Low effort. The bores are the same, so no result, format or pixel change.
2. **Start the first Run after reopening from the restored run.** Take it as the tears' start, as `steady` is. Measured as the warm Run: 7.4 → 2.9 s, 6.5 → 2.2 GB, 854k → 239k checkpoints. Low effort. It moves results within the loop's tolerance (another start, the same steady state): **an option for the owner**.
3. **Carry the column's warm seed from the last steady Run.** The warm Run's T-101 still takes 2.67 s from the learned seed; a warm column solve took 0.25–0.31 s in `ClientRunProbe`. Expect about 0.5–1 s a Run; to be measured. Medium effort; the seed is state kept in memory, never saved. It moves results within the column's tolerance: **an option for the owner**.
4. **Autosave off the render thread.** The sheet is immutable. A 4.6 s disk stall would freeze the game. Low effort. No result, format or pixel change.
5. **Calibrate the draw count, then cut it.** First count the game's own flushes in `GuiCanvas`, by page and by drawing site. Then batch the fills that texts and sprites break: 581 runs on the Flowsheet page. The look holds where nothing overlaps; check with render diffs, and any moved pixel goes to the owner. It works on the +2.2 to +3.3 ms a frame. Medium effort.
6. **Reuse the column's per-iteration arrays.** That is 6.5 GB a cold Run, 7.3 GB a Size all, and 12.6 GC pauses a second that also stop the render thread. Expect at least half the allocation gone. Results stay bit-identical if the arithmetic keeps its order; prove it with `columnGates` and the golden runs. Medium-high effort and risk: the column block shares the code.
7. **Build the door once a frame.** Hand `signature()` the door the draw uses, and read its params once. That saves about half of 0.05 ms and 134 kB a frame: small, but it consolidates. No result, format or pixel change.
8. **Split Size all by stage first.** Give it a `Progress` like Run's. Its 8.9 s ran under 72–75 % load, so replay it alone before any CPU-time claim.
9. **The banded solve, 30–40 % of Run, is for a column batch.** The C++ default gave no speedup (0.18.0), and earlier dead ends are recorded.

No action: Open, edits, undo, a run's own heap, the frames' CPU and allocation. The re-bench compares counters first and replays alone any phase a CPU-time choice rests on.
