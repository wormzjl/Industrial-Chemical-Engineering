# multi-machine-framework

Purpose: the probes, sweeps, bridge drivers, in-game evidence and logs of the multi-machine framework's first stage, the distillation column assembly. The batch is `documentation/2026-10-26-multi-machine-framework/`: plan `MULTI_MACHINE_FRAMEWORK_PLAN.md`, reviews `COLUMN_NOZZLE_ROWS_REVIEW.md`, `PROCESS_CONTROL_ADAPTATION_REVIEW.md`, `NOZZLE_ROOM_GATE_REVIEW.md` and `MERGE_REVIEW.md`. Branch `claude/distillation-column-framework-922ec9`, tip `ce71b45c` before the merge, merged to `main` as 0.15.0.

Nothing here was detached from a tracked path. Every probe ran from the worktree's scratch `tmp/` and was never committed, so no re-attach patch applies. The W5 probe `src/test/java/probe/PlantProbeTest.java` was a temporary JUnit class, deleted before `9e56ddab`; only its output is kept (`w4/probe.log` in the archive). The review world's builder stays in the code: `ColumnReviewWorld` in the `fluidGameTest` source set, run by the documented Gradle task `runReviewWorldServer` (not a gate).

## Compiling and running the probes

The probes are single Java files. Most are in package `com.wormzjl.icheme.science.equipment`, so they read the package-private layout code. The batch compiled them with javac (JDK 21, `C:/Program Files/Java/jdk-21.0.11/bin`) outside Gradle, because a dev client held `build/classes` for long stretches.

Run everything from the worktree root. First run one Gradle build (`./gradlew compileTestJava` or `test`), so that `build/classes/java/main` and `test`, `build/resources/main`, `build/moddev/clientLegacyClasspath.txt` and the merged NeoForge jar exist. Then, with an indexed scratch folder `tmp/<name>/`:

```
node tools/multi-machine-framework/classpath/cp.js            # writes tmp/line-resize/cp.txt (path fixed in the script)
CP="$(cat tmp/line-resize/cp.txt)"
javac -proc:none -encoding UTF-8 -cp "$CP" -d tmp/<name>/classes <Probe>.java
java -cp "tmp/<name>/classes;$CP" com.wormzjl.icheme.science.equipment.<Probe> > tmp/<name>/out.txt
```

The classpath uses `;` (Windows); keep it quoted in Git Bash. W1 to W6 and Q9 ran before `cp.js` existed: they used `build/classes/java/main;build/classes/java/test` directly (`NeckProbe`'s header gives its run line). The scripts keep the `tmp/` paths they ran with. None of the probes was recompiled after its own commit; the commit each one ran against is given below.

## classpath/

- `cp.js` (was `tmp/line-resize/cp.js`): writes `tmp/line-resize/cp.txt`. The entries are `build/classes/java/main` and `test`, `build/resources/main`, `build/moddev/artifacts/neoforge-21.1.219-merged.jar` (the version is fixed in the script), JUnit Jupiter API (the version from `gradle.properties`), apiguardian 1.1.2, opentest4j 1.3.0 and annotations 24.1.0 from the Gradle cache, then every line of `build/moddev/clientLegacyClasspath.txt`. Written for the line resize (`61d6dd2e`), whose changed sources were compiled into `tmp/line-resize/classes` while the owner's client ran.
- `args-steam-port.js` (was `tmp/steam-port/args.js`): javac argument files from `cp.txt`, without `build/classes/java/test` and with forward slashes (an argument file reads a backslash as an escape): `tmp/steam-port/javac-main.args` (output `tmp/steam-port/main-classes`) and `javac-test.args`. F3 compiled the whole main tree with `javac @tmp/steam-port/javac-main.args @tmp/steam-port/main-sources.txt`, so the sweeps ran on the working tree. `main-sources.txt` listed every tracked `src/main/java` source except `client/EquipmentGhost.java`: `git ls-tree -r --name-only HEAD src/main/java | grep '\.java$' | grep -v EquipmentGhost`.
- `args-w7.js` (was `tmp/w7/args.js`): `tmp/w7/cp.args` (javac) and `tmp/w7/run.args` (java, `tmp/w7/classes` first) from `cp.txt`. Use: `javac @tmp/w7/cp.args -d tmp/w7/classes <file>`, then `java @tmp/w7/run.args <class>`.

An older Gradle-free lane, `../p6-pilot-acceptance/run.sh`, compiles the science sources only, without the Minecraft classpath. It is a relative, not the original of these scripts.

## mcp-bridge/

Drivers of the langyo/minecraft-mod-mcp bridge in the `runMcpClient` lane (the pinned jar is in `../minecraft-mcp-bridge/`). They served the W6, review-world, F3 and W7 checks. They need Node and `npx` (the npm package `minecraft-mod-mcp`), and a running MCP client.

- `mcp.js` (was `tmp/w4/mcp.js`): one tool call per process through the npm bridge's stdio MCP server: `node mcp.js <tool> '<json arguments>'`. It prints the tool's text and exits 1 on an error; time-out 120 s. One call per process, because the bridge answered piped calls out of order. `../pipe-laying-ingame/bridge.js` takes another route: the mod's own HTTP endpoint.
- `cmd.sh` (was `tmp/w4/cmd.sh`): one chat command, `bash cmd.sh "/tp @s 0 -60 0"` (open_chat, paste_text, Enter). It calls `node tmp/w4/mcp.js`.
- `shot.sh` (was `tmp/review-world/shot.sh`): `bash shot.sh <name>` saves `tmp/review-world/shots/<name>.png` and a flattened `<name>-f.png`. `Flatten` was compiled into `tmp/review-world/flatten/`.
- `Flatten.java` (was `tmp/w4/Flatten.java`): `java Flatten.java in.png out.png` writes an opaque copy (bridge screenshots carry alpha 0). It is a variant of `../mcp-gui-helpers/Flatten.java`, which rewrites the files in place with the same pixel rule.
- `list.js` (was `tmp/w7/list.js`): the bridge's tool names with short descriptions.
- `list-click-tools.js` (was `tmp/steam-port/check/list.js`): the names, descriptions and input schemas of the click, overlay_click, use_item, set_view_angle and double_click tools (F3 check). Its first comment lines are copied from `mcp.js`; it calls `tools/list`.

## test-count/

`count.js` (was `tmp/q9/count.js`): `node count.js build/test-results/test [name part ...]`. It totals tests, failures, errors and skipped over the JUnit XML files of a folder, and prints one line for each file whose name holds a given part. The batch history's test counts from Q9 (a) on came from it.

## unplaced-nozzles/

Finding F1 widened (W1) and Q9 (a).

- `w1-unplaced.txt` (was `tmp/w1/unplaced.txt`): the W1 grid, d 1, 2, 3, 4, 6 and 8 m by 1, 2, 4, 6 and 10 trays by plain, sump steam, draw and sump steam (90 designs). It lists the 51 designs that leave a nozzle unplaced, with the nozzles. It ran on the W1 working tree, committed as `6648144f`. The probe's own source was not kept.
- `Q9Probe.java`: the same grid before (no component design) and after Q9 (a) (a kettle only on the plain column). It ran on `20f12268`'s code (main sources compiled into `tmp/q9/classes` before the commit). `q9-probe.txt`: 90 cases, 51 before (as in W1), 35 after. It uses `ColumnStructure.unplaced` returning `ColumnStructure.Unplaced`, which F3 replaced with `ColumnDesign.SideNozzle`, so it compiles from `20f12268` to `61d6dd2e` only.

## w3-arrows/

W3's direction arrows (`9a6269a5`). Every file reads `EquipmentLayoutGoldenTest.shapes()` (the layout table's shapes), so the classpath needs `build/classes/java/test`.

- `NeckProbe.java`: the measurement before the change. For each of the layout table's 376 flow-marked nozzles and each arrow side, the clear run along the neck. Output `neck.txt`: 87 nozzles show less than 0.28 m, 4 less than 0.10 m; the shortest 25 at the end.
- `WhyShort.java`, `WhyShort2.java`: what stops the clear run on the shortest necks (a column's access structure; the 0.5 m column's reflux).
- `BandReport.java`: what the band rule draws at the design size. Output `bands.txt`: 376 marked, 372 banded, 4 with none.
- `SizeProbe.java`: the arrows at five drawn sizes (codes 4, 13, 26, 38, 51; 1/16 to 0.8 m): no arrow vertex on hidden neck (1,880 cases).
- `CycloneProbe.java`: the cyclone feed at 0.59 m: which vertex was hidden, and by what.
- `ArrowRender.java`: the software renderer of the arrows: the real shell cell polygons, the mod's sprites, the game's shade, culled back faces, a depth buffer. It is adapted from `../mover-equipment-arrows/MoverPortRender.java` (itself from `../mover-port-markings/`); the original stays there. Args: `out.png before|after KIND:index ...`, the index into the layout table's shapes of that kind (drums 0-2 standing, 3-5 lying). Run it from the worktree root: it reads `src/main/resources/assets/icheme/`. `before` draws the removed `MoverPortMarkings`: compile `git show 4676a9a8:src/main/java/com/wormzjl/icheme/science/equipment/MoverPortMarkings.java` into its own classes folder and put that on the classpath. The sheets are the batch's `renders-w3/`. The spec lists of each sheet were not recorded; each tile's caption names its shape.

`NeckProbe` and `WhyShort*` need only `PortMarkings.flow`, so they also compile at `4676a9a8`; the others need `PortMarkings.band` and `surface` from `9a6269a5`. `PortMarkings` and `ShapeRasterizer` did not change from `9a6269a5` to `7f03b072`; `Mesh` gained a few lines from main's pipe-laying merge.

## f3-nozzle-rows/

Finding F3 and its fix, `ef0ae447` (`COLUMN_NOZZLE_ROWS_REVIEW.md`). Compile `RowSweep` with `NewSweep` (it reads `RowSweep.trays`).

- `SteamProbe.java`: the review column (2 m, 20 trays): stage heights, platforms, ladders, davit and each nozzle's height. Ran at `61d6dd2e`.
- `RowSweep.java`: 126 designs (d 1 to 8 m by 5 to 40 trays by three configurations): how far the old row search moved each side nozzle from its stage, and the moves to the wrong side of the stage. It ran at `61d6dd2e` three times: as built, with `patches/a1-platforms-yield.patch`, and with `patches/a2-platforms-ladders-yield.patch`. `results/sweep.txt` holds the three runs in that order: 97, 41 and 39 designs with a nozzle moved past two trays or more.
- `patches/a1-platforms-yield.patch`, `a2-platforms-ladders-yield.patch`: the row search without the platforms (a1), and without the platforms and ladders (a2). Apply each to `git show 61d6dd2e:src/main/java/com/wormzjl/icheme/science/equipment/ColumnStructure.java` with `patch`, compile the result into its own classes folder, and put that folder first on the run classpath. Both round-trip to the scratch copies (line endings aside).
- `patches/soft-walk.patch`: the F3 code in mid-task, the "soft walk" variant: the earlier `cutHalf` (the neck's angle by `asin`, no rail bar), a FEED trace line and `Math` where the commit has `DeterministicMath`. It is a patch against `ef0ae447`. Its sweep is `results/soft-sweep.txt` (13 designs with a deck walk cut between ladders).
- `NewSweep.java`: the new layout over 315 designs (d 0.5 to 15 m by 1 to 64 trays by four configurations): refusals and why, nozzles off their band, turns, ladders and davits moved, cage, deck and mast clashes, walks parted, pipe cells. `results/new-sweep.txt` to `new-sweep-6.txt` are its runs during the task, in order. `new-sweep-6.txt` is the committed code: 103 refused, 0 walks parted. `ColumnStructure` did not change from `ef0ae447` to `7f03b072`.
- `FeedProbe.java`: why a 0.5 m column's feed finds no row. `PipeProbe.java`: what stands in the 0.5 m test column's reflux pipe cell. `TestProbe.java`: the side nozzles, platforms and ladders of `ColumnStructureTest`'s columns under the new layout (`results/test-probe.txt`). All three ran on the working tree committed as `ef0ae447`.

## nozzle-bore/

The final lane's slow-gate failure and its fix, `67343274` (`NOZZLE_ROOM_GATE_REVIEW.md`): F3 sized a nozzle's room by its design bore, and the gate checked a pipe's largest. The probes ran at `7f03b072` (`ColumnStructure` unchanged to `ce71b45c`), compiled with `args-w7.js`'s `cp.args` into `tmp/f3-reflux/classes`. `RoomSweep`, `NeckWhy`, `ShiftProbe` and `BoreProbe` compile together with `../f3-nozzle-rows/NewSweep.java` and `RowSweep.java`, and read the test classes (`ProcessUnitTest.NozzleRoom`, `everySize`, `ShapeRasterizerTest.shapes`).

- `RefluxProbe.java`: what the gate's two failing necks meet, ring by ring (`results/probe.txt`: the column's access structure).
- `RoomSweep.java`: F3's 315 designs and the gate's columns: each refusal, or every nozzle's base and facing, then the gate's failures. Run it twice, as built and with `patches/rooms-at-largest.patch` (option A) compiled into its own classes folder first on the classpath, then diff (`results/sweep-current.txt`, `sweep-patch.txt`). `rooms-at-largest.js` is the edit that made the patched copy.
- `NeckWhy.java`: the failing necks by diameter, role and part met (`results/why-current.txt`: 89 access, 28 15 m top outlets in their head; `why-patch.txt`: the head ones only).
- `ShiftProbe.java`: the largest clear pipe on the centre line and with an off-centre shift in the nozzle's cell, under the off-centre limit (`results/shift-current.txt`: mean 0.647 to 0.662 m).
- `BoreProbe.java`: every column nozzle's `NozzleBore.largest` over the gate's columns and the sweep, and whether the test's own rule finds it clear (`results/bore-probe.txt`, from before the `MAX_BORE` fix for necks designed at the largest; it ran with `src/main/java/.../NozzleBore.java` compiled alongside).
- `SmallColumn.java` (default package): which small 0.5 m designs place which nozzles, and their limits; it chose the GameTest's column.
- `results/new-sweep-current.txt`, `new-sweep-patch.txt`: `../f3-nozzle-rows/NewSweep` on both layouts (no walk parted either way).

## review-world/

- `nbt.js` (was `tmp/review-world/nbt.js`): a small gzip NBT reader and writer for `level.dat`. `node nbt.js dump <level.dat> [path]` prints it. `node nbt.js review <level.dat> "<name>"` sets `LevelName` and turns off the experimental features and packs (`bundle`, `trade_rebalance`), in place. Use: `./gradlew runReviewWorldServer`, then `node nbt.js review run/review-world/world/level.dat "IChemE column review"`, then copy `run/review-world/world` to `run/saves/<name>` (for `runClient`) or into the MCP client's saves. `../fluid-in-game-rig/level-allow-commands.js` is a relative that patches one tag only.
- `level.dat.orig`: the GameTest server's `level.dat` before the edit (`5d7ffb8c`), a test input for `dump`.
- `PortProbe.java`: the box sizes and nozzle pipe blocks of the review plant's parts (the column, a lying drum on supports 2, 4 and 5, the ejector, air cooler, kettle, exchanger and pump), used to lay out `ColumnReviewWorld`. It ran at `5d7ffb8c`, and again at `11ff43ed` through `args-w7.js` for the W7 check.

## merge-docs/

The merge's edits to shared files in the main checkout, where the Edit and Write tools are blocked (was `tmp/merge-docs/`).

- `patch.js`: `node patch.js <spec.json>` patches one file in place. The spec is `{ "file": "<absolute path>", "edits": [ { "old": "...", "new": "..." } ] }`, with LF in the spec. Each `old` must occur exactly once, or nothing is written. The file's CRLF or LF is kept. Use it for `documentation/INDEX.md` and `tools/INDEX.md` rows, so another session's rows are never lost by a whole-file copy.
- The specs this merge ran: `index-row.json` (the batch row, In progress), `tools-row.json` (this folder's row), `index-implemented.json` (the row at the merge).
- Worktree-side scripts, same rule (abort on an anchor not found exactly once): `guide-counts.js` (the staged code guide's measured counts and `process-control.md`'s schema line), `readme-lane.js` and `readme-merged.js` (the batch README at the final lane and at the merge).

## ingame-checks/

Flattened screenshots (854 x 480) of the four bridge-lane checks, and the datapacks that set two of them up. Put a datapack folder in a world's `datapacks/` and run `/function <name>:setup`.

- `w6/`, at `e5f08792` (the calculator's default 8 m design built through Components, Save design, Import and Build): `19-components-f` (the Components page), `26-gui-f` (the column GUI on a right-click), `28-checked-f` (after Validate: 1 connected, 0 misplaced, 20 missing), `29-hover-f` (a selected part framed with its row), `30-datasheet-f` (Datasheet). The cut list names are the defect that `ee3b6399` fixed.
- `review-world/`, at `5d7ffb8c`: `02-worlds-f` (the world lists as creative, without the experimental warning) and `06-spawn-f` (joined at the set spawn, game menu open).
- `f3/`, at `ef0ae447`: `ConstructorSnbt.java` (default package; it needs the Minecraft classpath, `tmp/steam-port/main-classes` first) prints a constructor's block-entity SNBT with the review column imported. That SNBT is in the datapack `f3check/` (in the save "IChemE column review"): its setup places the constructor 3 blocks south of the player. `05-nozzles-f` (the Nozzles page, reflux on its automatic heading), `07-steam-f` (sump steam, Auto: Front), `13-kept-steam-f` (after a turn and a reopen: Right, turned by you 1).
- `w7/`, at `11ff43ed`, then the fix `23d8a19d`. The world "IChemE W7 check" is a copy of the review world. Its datapack `w7check/` removes the feed valve, sets a glass block, puts the player in front of the feed nozzle and gives the pipe laying tool (that world's coordinates); the plant was then validated. `05-validated-f` (16 connected, 1 missing), `06-tool-f` (taken up with a number key: no frame, no line; defect 1), `07-scroll-f` (taken up with the mouse wheel: the frame, and the long label off the screen; defect 2), `14-key-f` (after `23d8a19d`: the frame and the short label).

## logs/

`multi-machine-framework-logs-2026-10-27.tar.gz` (71 KB; 48 logs, 0.5 MB unpacked). Each log is under its scratch folder's name:

- `w3/`: compile, the affected tests, the arrow gate's rule-off run (`mutant.log`: every arrow forced outward, the gate fails), the quick suite.
- `w4/`: the W4 to W6 compiles and quick suites, the W5 unit tests, the W5 probe (`probe.log`: the short columns that refuse), fluid GameTests w5a to w5c and w6a, the W6 tests, the W6 client log (`client-w6.log`).
- `q9/`: the Q9 (a) and F2 quick suites, F2's failing-first run (`f2-before.log`), fluid GameTests q9a.
- `review-world/`: the two `runReviewWorldServer` builds (`build-1.log` at `5d7ffb8c`, `build-2.log` after F3), the client log.
- `line-resize/`: the javac check and the quick suite.
- `steam-port/`: javac, the quick suite, the layout table and compat re-records (`records.log`), fluid GameTests f3a, the F3 client log.
- `pc-adapt/`: the affected tests, the scratch merge's unit tests and fluid GameTests.
- `merge-0141/`: the merge's compile, compat tests, quick suite and rack-lane tests; the W7 compile, tests and quick suite.
- `w7/`: the three client logs and the fix's tests.

Eight empty logs were left out (six in `w1/`, two in `w4/`).

`final-lane-logs-2026-10-27.tar.gz` (1.5 MB; 652 entries): the merge's final lane, run alone with the main checkout's `lane.ps1` and `quick.ps1` (copies at the archive's root, with the campaign init scripts `cold/` and `bundled/`).

- The first lane at `7f03b072`: `first-lane-stdout.txt`, `first-final-gates.log`, `first-final-results/` (`slowGates` 187, 1 failure: `ProcessUnitTest.everyNozzleNeckAtItsLargestStandsClear`).
- The lane at `ce71b45c`: `lane-stdout.txt`, `final-gates.log`, `final-results/` (`test` 1643, `slowGates` 188, 0 failures), `final-chain-100.log`, `final-gametests.log` (34/34), the three campaign reports and logs.
- The comparison against 0.14.1's merge lane (`../process-control/logs/merge-lane-logs-2026-10-27.tar.gz`): `final-printed.txt` (made with `../process-control/merge-lane/printed.pl`), `final-junction.txt` (33 lines, identical), `printed.diff`, and `printed-norm.diff` (timings removed: 57 lines).
- `replay833-*`: cold seed 833, replayed alone, matches the baseline record.
- `archive-stage/`: option C's checks from `tmp/f3-reflux` (`c-unit.log`, `c-gate.log`, `c-gametest.log` with the failing FEED test, `c-gametest2.log` 34/34).

## Left out

- Compiled classes (`classes/`, `a1-classes/`, `a2-classes/`, `soft-classes/`, `new-classes/`, `main-classes/`, `before-classes/`, `flatten/`) and generated classpath files (`cp.txt`, `cp.args`, `run.args`, `javac-*.args`, `java.args`, `main-sources.txt`): the scripts above rebuild them.
- `tmp/w7/src/`: `Minecraft.java` and `MultiPlayerGameMode.java` from the NeoForge sources jar, read to find W7's defect 1.
- `tmp/w3/before-src/` (identical to `git show 4676a9a8:` of `MoverPortMarkings.java`) and `tmp/w3/renders/` (identical to the batch's `renders-w3/`).
- `tmp/w4/AssemblyGameTests.head`: a draft of the W5 GameTests, replaced by `9e56ddab`.
- Code-edit scripts whose results are commits: `steam-port/` `cut-angle.js`, `rooms.js`, `screen.js`, `sides.js`, `tests.js`, `turns-*.js`, `walkway.js` and `section.java` (`ef0ae447`); `pc-adapt/facts-merge.js` (`e69cec50`) and `w7-network.js` (`11ff43ed`); `w3/test-sizes.js` (`9a6269a5`). Doc-edit scripts: `docs*.js`, `legend.js`, `readme-w6.js`, `lang-w6.js`.
- The other screenshots of the four checks, and the worlds in the MCP client's saves.

## History

- 2026-10-27: created at the merge of 0.15.0 (batch `2026-10-26-multi-machine-framework`, tip `7f03b072`), from the worktree's `tmp/` folders `w1`, `w3`, `w4`, `q9`, `review-world`, `line-resize`, `steam-port`, `pc-adapt`, `merge-0141` and `w7`; `nozzle-bore/` from `f3-reflux` (the final lane's gate failure); `logs/final-lane-logs-2026-10-27.tar.gz` from `final-lane` at `ce71b45c`; `merge-docs/` at the merge (main fast-forwarded to `ce71b45c`, 0.15.0).

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 14 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/multi-machine-framework/`, at the same relative paths.
