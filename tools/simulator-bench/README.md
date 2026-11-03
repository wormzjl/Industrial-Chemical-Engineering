# simulator-bench

Purpose: measure what the process simulator costs a client on the bundled crude unit: CPU, allocation, retained heap, and draw calls as the GPU's proxy. It ranks optimizations; it is not a gate.

Batch: `2026-11-02-process-simulator` (`PERFORMANCE_REVIEW.md`). Owner, 2026-11-04: "bench the CPU, GPU, ram performance of current simulator implementation, optimize and consolidate as much as you can".

## How to run

From the worktree root, in PowerShell, after `./gradlew compileJava processResources`:

```powershell
powershell -NoProfile -File tools\simulator-bench\bench.ps1 -Out tmp\simulator-bench\<name>
```

`-Phases "run,frame:idle"` picks phases (default: all, about 45 minutes). `-Warm` and `-Measure` set the windows in seconds (default 60 and 60, the owner's rule: one pair per claim). `-NoWait` fails instead of waiting for a busy machine.

The script runs each phase alone. Before each phase it checks the machine and waits while one of these runs, in any worktree:

- a Minecraft client JVM;
- a Gradle build (its wrapper client);
- another bench;
- any JVM that uses more than a tenth of a core over 3 s. Gradle's daemons and compiler workers stay alive between builds, so they count only while they work.

It also waits while free RAM is under 4 GB. It logs free RAM, CPU load, what else runs and the five top CPU users of any process to `machine.txt`. A process that is no JVM (another project's simulator) does not stop a phase: judge an overlapped run by its counters (allocated bytes, checkpoints, draw calls, retained heap), and replay a phase alone before a choice rests on its CPU time.

Each phase runs in its own JVM: JDK 21 (the game's runtime), `-Xmx4g`, the default G1. The class path holds the mod's classes and resources, gson, and the merged NeoForge jar. The merged jar is there only so that `KitDoor`, which names game widget classes, loads; nothing of the game runs.

Output, per phase, in the `-Out` folder:

- `<phase>.txt`: the numbers;
- `<phase>.log`: everything printed;
- `<name>.jfr`: a recording of the measured window only;
- `<name>.views.txt`: `jfr view` hot-methods, allocation-by-site, gc and thread-cpu-load.

## Entry points

- `SimulatorBench.java <phase> <out folder>`: one file, run by the JDK's source launcher. `-Dwarm`, `-Dmeasure`.
- `bench.ps1`: the driver above.
- `alloc-sites.js <recording.jfr> [rows] [jdk folder]`: allocation weight by the first mod frame (with its line) and the allocated class, from the recording's `jdk.ObjectAllocationSample` events. JFR's own threads are left out; the views' `allocation-by-site` counts JFR's class instrumentation, about half of the sampled weight.

Phases:

- `run`: Run from no results, as `SimulatorRunner`'s job does it: `FlowsheetEngine.run` with no last run, then `SheetSizing.rate` when steady. It also gives the first Run in a fresh JVM, wall and CPU time by unit (from one `Progress.calculation` to the next), and checkpoints by unit. Every flash and solver step calls the checkpoint, so it stands in for a flash count; no flash counter exists.
- `run-warm`: the same, started from the last steady run, as a second Run is.
- `size`: Size all (`Proposal.sizeAll`) on the run's result, then a quiet rating (`SheetSizing.rate`) of the sized sheet.
- `frame:<page>`: one frame of the screen's own render path on a counting canvas. The pages are `idle`, `selected` (T-101), `feed` (CRUDE's door), `column`, `column-results` (T-101's door, Results tab), `results`, `sizing` and `open` (50 files and the example).
- `edit:<op>`: one edit with the next frame's state and layout:
  - `move`: a coalesced drag step of P-101;
  - `feed`: a feed entry, a new undo step each time;
  - `state`: `SheetState.of` alone;
  - `layout`: `SheetLayout.of` alone;
  - `save`: the autosave, `FlowsheetFile.save`.
- `open`: one 10 kB sheet parsed, then Open's listing of 50 such files with the bundled example, and its rows' heap.
- `size-stages`: Size all split as `Proposal.sizeAll` runs it: the proposal pass (a column on Auto tries its diameters), the applied copy, the re-run warm from the run where the sizes move it, and the rating. CPU, wall, bytes and checkpoints by stage, the mean of `-Drounds` rounds (default 3) after 2; the re-run's passes and units. Counters, not a timing claim; not in the default list.
- `ram`: retained heap, used after a full GC with and without, of:
  - the catalog;
  - the network thermo;
  - the sheet;
  - an editor;
  - a run's `FlowsheetRun`;
  - `SheetState`;
  - a rating;
  - the props caches;
  - 100 undo steps of three kinds.
- `bits`: every value a cold Run, a warm Run from it and Size all leave (streams, reports, loops, the sized sheet's file text), written exactly to `results.txt`, with no timing and the steps' seconds left out. A change that must keep results bit-identical diffs this file before and after. The checkpoint counts on its first lines vary from run to run on the same code; they are not results. For the "before", compile the base commit's versions of the changed files into a folder and put it first on the class path.
- `histo`: what one held cold Run's `FlowsheetRun` adds to the heap, by class, from the VM's class histogram with and without it. Then `-Druns` Runs (default 4) on feeds 1 K apart, results dropped, and what they leave by class. Each Run goes on its own thread, as `SimulatorRunner` starts one a job; `-Dsame=true` keeps them on the bench's thread, where `NetworkPhaseEngine`'s per-instance `ThreadLocal` workspaces stay about 270 kB a Run. Not in the default list; it takes no timing.

What a frame covers: the screen's per-frame work.

- `ProcessSimulatorScreen.render`: `context()` with its cached state, the shown door built once and `signature(x, shown)` on it (since `78a779996`, P6; before it, `signature()` built the door a second time), and `SimulatorPage.draw`, or `DoorFrame.back` with the kit's `placeAll` and `draw`, or `FileColumns.draw`.
- The widgets that `super.render` draws, through the same `Skin` calls: frame keys, canvas keys, the theme key, door tabs and footers, glass slots, and the kit's fields and buttons in view.

What a frame leaves out:

- the game's own cost per fill and glyph in `GuiGraphics`;
- `Font` widths: a fixed 6 px font stands in;
- `Component.translatable`: a map lookup and `String.format` stand in;
- the hover tooltip;
- GPU time.

Draw calls follow `GuiCanvas`'s count through the kit's `HeldDraws`, as `DrawCallTest` does: a frame is one batch, its fills go out in runs while its texts and sprites wait until something would cover them, and the line gives the fill runs, the text runs and the sprites. Quads are fills, glyphs other than spaces, and sprites; vertices are 4 a quad. The frame-time and GPU check in the game is a separate session.

## History

- 2026-11-04: written for the batch's performance review. Smoke-checked with 0.3 s windows; the review's numbers come from a run with the owner's 60 and 60 s windows at the commit the review names.
- 2026-11-04: baseline at `a958cecd2`, all 18 phases, beside another project's simulator (17–34 % CPU): `PERFORMANCE_REVIEW.md`. Then `histo`, with `-Druns=20`, found the shape caches: 12.4 MB a Run on a new input, up to 211 MB. The driver now logs the top CPU users of any process.
- 2026-11-04: after `3e39dbc39`, `histo -Druns=20` on runner threads holds 0.4 MB, flat.
- 2026-11-04: after `58da4714f` (the no-keep search on `NozzleBore.proposed` only), `histo -Druns=20`: +0.51 MB after one Run, +0.76 MB after 20, of which 0.31 MB GC filler (`FillerElement`) and 0.35 MB the phase's own start histogram (1,946 class rows); no geometry held. The slow rise is NozzleBore's bore cache, 2 shapes a Run, cleared whole at 64.
- 2026-11-04: `size-stages` added (P8). The crude unit's Size all: proposal pass 1.58 s CPU, 1.71 GB, 280k checkpoints (24.6 %); applied copy 0; re-run 4.81 s, 5.46 GB, 887k checkpoints (74.9 %), 2 passes, T-101 4.7 s and 773k checkpoints from the learned seed; rating 31 ms, 10 MB. The machine was shared (CPU beside the load).
- 2026-11-04: the frame counts through `HeldDraws` and draws in one batch, as the screen does since P5 (`1ed994e46`). One frame each, no timing: idle 758 to 124 draw calls (11 fill runs, 10 text runs, 103 sprites), Results 485 to 12, the column door 10, its Results tab 10.
