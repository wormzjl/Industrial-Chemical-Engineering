# codebase-review-bench

Purpose: package B1 of the code base review: a CPU, allocation and heap profile, with JFR, of the fluid network step, the V3 column (cold and warm) and 20 shaft trains. It is the Java baseline the Rust column port is measured against. Not a gate.

Batch: `documentation/2026-11-13-codebase-review/` (`PROFILE_REVIEW.md`, brief B1 in the main checkout's `research/2026-11-13-codebase-review/BRIEFS.md`).

## How to run

Every timed run runs alone (AGENTS.md: a performance task). Before each, from the worktree root:

```powershell
powershell -NoProfile -File tools\codebase-review-bench\check-machine.ps1
```

It prints every JVM with its CPU use over 3 s and exits 1 when free RAM is under 8 GB, a Gradle build or a Minecraft client runs, or a JVM uses more than a tenth of a core.

Build once (not timed): `./gradlew jar compileTestJava compileFluidGameTestJava processTestResources`.

### Fluid (the paced server benchmark)

```text
./gradlew fluidServerBenchmark -PfluidBenchmarkRunId=<fresh id> -PfluidStressProfile=true -PfluidBenchmarkMemory=true [-PfluidBenchmarkProfile=mixed100]
```

`-PfluidStressProfile=true` makes `FluidServerBenchmark` record JFR ("profile" settings) from the fixture's start to the report: `build/reports/fluid/M9/<id>/stress.jfr` beside `report.json`. Copy both to `tmp/codebase-review-bench/<run>/`. The default profile `one` (one island of 100 reservoirs) warms 2,400 ticks and measures until 200 intervals; once its island certifies at rest the count stops and the run ends only at the GameTest timeout (B1 stopped it at 33 min). Use a timed profile (`mixed100`, `stress100`, ...): 60 s + 60 s on 100 islands. `report.json` gives `measuredStartEpochMillis` for `JfrSummary.java -Dfrom`. Heap histograms of the game JVM: `jcmd <pid> GC.class_histogram` after the window (see `PROFILE_REVIEW.md` for when they were taken).

### Column

```powershell
powershell -NoProfile -File tools/codebase-review-bench/run.ps1 ColumnBench.java cold:medium tmp/codebase-review-bench/column
```

`run.ps1` runs a source program of this folder with JDK 21 on `build/classes/java/{main,test}`, their resources, gson and EJML from the Gradle cache, `-Xmx4g`; `-JvmArgs "-Dwarm=60 -Dmeasure=60"` adds JVM options.

Phases `cold:medium`, `cold:literature`, `warm:medium`, `warm:literature`, each in its own JVM. Cold is `ColumnUnit.solve(input, null, ...)`, the block's cold route (learned start, then the classical chain). Warm is one cold base solve, then `ColumnUnit.solve(input × 1.02, baseSeed, ...)` again and again: every extensive input (feed, steam, draws, pumparound duties) times 1.02. `medium` is the `medium_crude` stream under its tower template (`V3TestInputs.crude`); `literature` is the `literature_tia_juana` preset. The column runs on its Java path (the Rust library's ceiling `icheme.native.column` is off by default). Output: `<phase>.txt`, `<phase>.jfr`.

### Machines

```powershell
powershell -NoProfile -File tools/codebase-review-bench/run.ps1 MachineBench.java tmp/codebase-review-bench/machines-cert-true
powershell -NoProfile -File tools/codebase-review-bench/run.ps1 MachineBench.java tmp/codebase-review-bench/machines-cert-false -JvmArgs "-Dcertificates=false"
```

20 trains (motor, shaft, gearbox on Auto, shaft, pump casing), each on its own one-pipe island, on the JUnit rig of `ShaftCouplingTest`/`PumpHealthTest` with `ElectricStores` set to need no power. Ticks run back to back. `-Dtrains`, `-Dwarm`, `-Dmeasure`, `-Dcertificates=false` (every island solves every cadence). Output: `machines.txt`, `machines.jfr`, `histo-before.txt`, `histo-after.txt`.

### Warm route probe

`run.ps1 WarmProbe.java tmp/codebase-review-bench/warm-probe`: per case, the cold base seed's free water, then the +2 % input by four routes (the simulator's warm route, the strategy entry with and without the seed, the learned cold route) with path, Newton iterations, closure and checkpoint count. Counters only.

### Reading a recording

```powershell
& "C:\Program Files\Java\jdk-21.0.11\bin\java.exe" -Dthreads=<regex> -Dfrom=<epoch ms> -Dto=<epoch ms> tools\codebase-review-bench\JfrSummary.java <recording.jfr> <summary.txt>
```

CPU samples by top frame (class and method), the time-share categories (rules in the class: a JDK collection, stream, boxing or string top frame is bookkeeping; otherwise the first mod frame's class and method name decide linear algebra, flash, thermo kernel, residual and Jacobian, or other), allocation sample weight by first mod frame and class, GC pauses. Allocation samples inside JFR's own start-up are left out: such a sample weighs every byte the thread allocated before the recording (the whole warm-up). With them out, the weight matched ThreadMXBean to 0.1 % in B1.

`tables.mjs <summary.txt> [categories|classes|methods|sites|siteclass|allocclass ...]` turns a summary into Markdown tables; `histo.mjs <after> [before|-] [rows] [prefix]` reads class histograms (a delta with two files) and totals the mod classes by package.

## Entry points

- `check-machine.ps1`: the machine check above.
- `ColumnBench.java <phase> <out>`: JDK source launcher; `-Dwarm`, `-Dmeasure` (default 60, 60).
- `MachineBench.java <out>`: JDK source launcher; `-Dtrains`, `-Dwarm`, `-Dmeasure`.
- `JfrSummary.java <jfr> [out]`: JDK source launcher; `-Dthreads`, `-Dfrom`, `-Dto`, `-Drows`.
- `WarmProbe.java <out>`: JDK source launcher (ColumnBench's cases copied in: the launcher compiles one file).
- `run.ps1 <program> <args> [-JvmArgs "..."]`: the class path above.
- `tables.mjs`, `histo.mjs`: Node readers for the review's tables.

## History

- 2026-11-13: written for B1; the numbers of `PROFILE_REVIEW.md` come from the runs it names, under `tmp/codebase-review-bench/` of the bench worktree.
