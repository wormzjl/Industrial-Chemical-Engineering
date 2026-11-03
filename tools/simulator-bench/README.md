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

Phases:

- `run`: Run from no results, as `SimulatorRunner`'s job does it: `FlowsheetEngine.run` with no last run, then `SheetSizing.rate` when steady. It also gives the first Run in a fresh JVM, wall and CPU time by unit (from one `Progress.calculation` to the next), and checkpoints by unit. Every flash and solver step calls the checkpoint, so it stands in for a flash count; no flash counter exists.
- `run-warm`: the same, started from the last steady run, as a second Run is.
- `size`: Size all (`SheetSizing.sizeAll`) on the run's result, then a quiet rating (`SheetSizing.rate`) of the sized sheet.
- `frame:<page>`: one frame of the screen's own render path on a counting canvas. The pages are `idle`, `selected` (T-101), `feed` (CRUDE's door), `column`, `column-results` (T-101's door, Results tab), `results`, `sizing` and `open` (50 files and the example).
- `edit:<op>`: one edit with the next frame's state and layout:
  - `move`: a coalesced drag step of P-101;
  - `feed`: a feed entry, a new undo step each time;
  - `state`: `SheetState.of` alone;
  - `layout`: `SheetLayout.of` alone;
  - `save`: the autosave, `FlowsheetFile.save`.
- `open`: one 10 kB sheet parsed, then Open's listing of 50 such files with the bundled example, and its rows' heap.
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
- `histo`: what one held cold Run's `FlowsheetRun` adds to the heap, by class, from the VM's class histogram with and without it. Then `-Druns` Runs (default 4) on feeds 1 K apart, results dropped, and what they leave by class. Each Run goes on its own thread, as `SimulatorRunner` starts one a job; `-Dsame=true` keeps them on the bench's thread, where `NetworkPhaseEngine`'s per-instance `ThreadLocal` workspaces stay about 270 kB a Run. Not in the default list; it takes no timing.

What a frame covers: the screen's per-frame work.

- `ProcessSimulatorScreen.render`: `context()` with its cached state, `signature()`, which builds a door again, the shown door built again, and `SimulatorPage.draw`, or `DoorFrame.back` with the kit's `placeAll` and `draw`, or `FileColumns.draw`.
- The widgets that `super.render` draws, through the same `Skin` calls: frame keys, canvas keys, the theme key, door tabs and footers, glass slots, and the kit's fields and buttons in view.

What a frame leaves out:

- the game's own cost per fill and glyph in `GuiGraphics`;
- `Font` widths: a fixed 6 px font stands in;
- `Component.translatable`: a map lookup and `String.format` stand in;
- the hover tooltip;
- GPU time.

Draw calls follow `DrawCallTest`'s count: a batch of fills is one call, a text is one, a sprite is one. Quads are fills, glyphs other than spaces, and sprites; vertices are 4 a quad. The frame-time and GPU check in the game is a separate session.

## History

- 2026-11-04: written for the batch's performance review. Smoke-checked with 0.3 s windows; the review's numbers come from a run with the owner's 60 and 60 s windows at the commit the review names.
- 2026-11-04: baseline at `a958cecd2`, all 18 phases, beside another project's simulator (17–34 % CPU): `PERFORMANCE_REVIEW.md`. Then `histo`, with `-Druns=20`, found the shape caches: 12.4 MB a Run on a new input, up to 211 MB. The driver now logs the top CPU users of any process.
- 2026-11-04: after `3e39dbc39`, `histo -Druns=20` on runner threads holds 0.4 MB, flat.
