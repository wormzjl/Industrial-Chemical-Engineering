# unified-thermo-port-test-world

Purpose: the owner's in-game test world for the unified thermo port (crystals as solids, slack vessels, the pilot
cryogenic package on the network). It is prepared files-only for one `runMcpClient` session driven through the
minecraft-mcp bridge.
- Batch `2026-09-24-coolprop-low-temperature`, branch `claude/unified-thermo-port`, prepared 2026-10-16, updated
  2026-10-17 against `87c106f6`.
- The run sheet, with every row's physics and expected numbers and the findings F1-F6, is the batch's `TEST_WORLD_PLAN.md`.
- Expected numbers: form A + D33 at `c9e7c128` (the expected form) and form A at `40a44732`. Rows the plan marks
  "(updated 2026-10-17: range work / ice)" are restated for `87c106f6`: rows 1, 6, 8, 9, 10 and the new row 12.
- Nothing here was ever in a tracked path.

The session needs D32's fix: a pipe joining a lone generator or void retires its island of one. Without it the second
save crashes (M4 open item 1). It is on the branch (`15220a07`). The plan's §5 has the 2026-10-16 bisect.
- F1 (row D23-1 held at once on `c9e7c128`) was introduced by `40a44732` (D31 B) and is gone from `2ab43f23` (form 4)
  on. Since the range work (`a169fa85`, `085676ea`), leaving a data range never holds.
- F2 (R9's pump never started) predates the forms (`76e87740`). Its fix A (`ed2cdff4`) is on the branch; R9 was not
  re-run.
- Hold texts: before the session "HELD (thermo domain): " becomes "HELD (phases): " and the log's
  `status=THERMO_DOMAIN` becomes `status=PHASE_HOLD`. The rows' `log` steps grep the new text (`rows-bisect/` keeps the
  old one, for the commits it was run on).

| Path | What | How to use |
|---|---|---|
| `world-template/` (git-ignored; the copy is in the main checkout's `research/tools/unified-thermo-port-test-world/world-template/`) | Fresh copy of `tools/fluid-in-game-rig/template/bench-template` (void superflat, creative, commands on, chunks x 16..111 and z 16..143 force-loaded, no fluid data), with `datapacks/p6_pilot` from `tools/p6-pilot-acceptance/datapack/`. The datapack holds the network package `createcheme:pilot_cryogenic`, column preset `tia_juana`, and fluid presets water, nitrogen, methane, ethane and carbon dioxide; it is the same file set as `src/test/resources/materials/pilot-cryogenic` | With no client running: `cp -r world-template <worktree>/run/mcp-client/saves/<new folder>`, a new folder for every session |
| `mixturepreset/pilot_*.json` | Eight client mixtures, schema 1, kind "mixture", package `createcheme:pilot_cryogenic`, basis mole: the E2 set, liquid CO2, LNG with 0.5 % CO2, and CH4 + 30 % CO2 | Copy into `<worktree>/run/mcp-client/CreateChemE/mixturepreset/`. The generator lists them after the six network presets, sorted by file name, as "<name> · Custom" |
| `mcp/bridge.js`, `mcp/Flatten.java` | The 0.8.0 checks' bridge driver and alpha flattener, verbatim from `tools/fluid-tank-column-gui-fixes/scripts/` | `node mcp/bridge.js <cmd> '<json>'` |
| `mcp/gen.js` | The device screen, driven by label (details below the table) | `node mcp/gen.js selftest`, `dump`, `labels`, `click <label>`, `field <label> <value>`, `mixtures`, `mixture "<label>"`, `open x y z KIND`, `configure '<json>'`, `read '<json>'` |
| `mcp/fixtures/` | The 0.8.0 in-game check's widget dumps (GUI scale 1, 854x480) | read by `gen.js selftest` |
| `mcp/row.js` | The row driver (details below the table) | `node mcp/row.js open-world <folder>`, `node mcp/row.js setup`, then `MC_WORLD=<folder> node mcp/row.js rows/NN-*.json [--from k] [--to k] [--out dir] [--dry]` |
| `mcp/check-rows.js` | Offline audit of the row files: cell clashes, the joins the game will make, contacts between rows, where the player stands for every screen | `node mcp/check-rows.js [dir]` (prints "no problems") |
| `rows/NN-*.json` | The 12 scenario rows in session order: 13 E4 rows and 2 D23 rows in rows 1-11, then row 12 (`R10-fence`, acceptAtFence inside a network) | see the plan |
| `rows-rig/` | Rig-only variants: D23-1 with a 5 cm let-down pipe (F1 is not caused by the 4 mm pipe); `R2-tail.json` (row 2 plus +300 s and +600 s reads, 2026-10-18); `R1-h4.json` (row 10 up to its ice-a read, then the `h4` trace, 2026-10-18) | rig only |
| `index-lane/compile.sh`, `run.sh`, `M3Run.java`, `condense.js` | The javac lane outside Gradle (details below the table) | `bash index-lane/compile.sh <label> [commit]`, `PROBES_ONLY=1 bash index-lane/compile.sh <label>`, `PATCH="<patch files>" EXTRA_PROBES="<java files>" bash index-lane/compile.sh <label> <commit>`, `bash index-lane/run.sh <label> <selector> -D...` |
| `index-lane/probes-trace/M9CheckTrace.java` | Consumer of the temporary M9 hook (`FluidThermodynamics.M9_CHECK`, every phase check: shrinking, excluded, proposed and refused bits, the state, `from`, the TP answer, the TP seed, the own split, the seed and the verdict). Package `science.fluid.thermo`; prints only while `M5RowTrace.ON` | pass with `EXTRA_PROBES` on a label compiled with the M9 patch (`tools/unified-thermo-port-m5/trace/patches/`) |
| `logs/2026-10-18-session/` | The rig re-runs at `00ee8385` and `3db5e5d3` (D40), the attribution runs at six older commits, row 5's bisect (`r8-bisect/`), the stopped in-game session (`d40/`), and the H1-H4 traces (`TEST_WORLD_RIG_HOLDS_TRACE.md`); the large trace logs are packed in `h-traces.tar.gz` | `tar --force-local -xzf logs/2026-10-18-session/h-traces.tar.gz` |
| `index-lane/probes/TestWorldRigTest.java` | The rows replayed on the engine path the game runs (`PhysicalRegistry`, `IslandCoordinator`, 20 ticks per online second, game defaults, the driver's step times). Each read prints what the screen shows. An edit the game's handler would refuse prints its "Not applied" reason. Package `com.wormzjl.createcheme.runtime.fluid` | `bash index-lane/run.sh <label> com.wormzjl.createcheme.runtime.fluid.TestWorldRigTest "-DtestWorld.rows=$(cygpath -m rows);$(cygpath -m rows-rig)" -DtestWorld.presets=$(cygpath -m mixturepreset) [-DtestWorld.only=R3]` |
| `index-lane/probes/TestWorldEngineProbeTest.java` | Equilibrium numbers of the rows' feeds: the generator's TP state, and the PH answer of its stream at the tanks' pressures with the crystal competing | `bash index-lane/run.sh <label> com.wormzjl.createcheme.science.fluid.network.TestWorldEngineProbeTest` |
| `logs/testworld-logs.tar.gz` | Rig logs (A + D33: four runs, the last one final; form A: one run), two engine logs, and condensed views | `tar --force-local -xzf logs/testworld-logs.tar.gz` |
| `index-lane/onejvm.sh`, `OneRun.java` | F1/F2 bisect (2026-10-16): the index lane in one JVM per commit. The commit is exported with `git archive`, then main and the probes are compiled with javac in-process and the selector runs through M3Run, all in one `-Xshare:off -Xmx6g` JVM. A compiled main is reused per export. The script refuses while a dev client runs | `bash index-lane/onejvm.sh <label> <commit> <probe dir, or probe files separated by commas> <selector> -D...` |
| `index-lane/probes/TestWorldTrace.java` | The traces behind the rig's `trace` row step. `f1`: the tank's committed state, its arrival, the refresh, phase-check verdicts on vapour-only states, PH answers, and steps from both states. `f2`: the pump junction, the mint's fixed point, the slice's inlet decision with and without closures, and the t0 rate solves with their deposition test. `h4` (2026-10-18): a taut vessel's crystal test (`VesselCrystals.settle` and `crystalsAtVolume` warm and cold), the UV search's steps where the M10 hook is patched in, the free water's stand-in and ice enthalpies, and V(P) by the engine's TP answer | row step `{"op":"trace","kind":"f1", "f2" or "h4","id":"<block>"}`; the probe list is `index-lane/probes/TestWorldRigTest.java,index-lane/probes/TestWorldTrace.java` |
| `index-lane/probes-main/MainSlurryRigTest.java` | The rig reduced to main 0.8.0's API, used for F2 on main with water and 100 µm demo particles | `bash index-lane/onejvm.sh main ffeafe8a index-lane/probes-main/MainSlurryRigTest.java,index-lane/probes/TestWorldTrace.java com.wormzjl.createcheme.runtime.fluid.MainSlurryRigTest -DtestWorld.rows=rows-main [-DtestWorld.only=F2-main]` |
| `rows-bisect/`, `rows-main/` | The bisect rows: F1 is D23-1 at 5 cm with an `f1` trace; F2 is R2-R7-R9 with `f2` traces, plus its mint variant; main's water-slurry row, and its methane variant, which main's package refuses. `rows-bisect/mkrows.js` derives the F2 mint row (run with node from this folder) | `-DtestWorld.rows=rows-bisect -DtestWorld.presets=mixturepreset -DtestWorld.only=F1-D23-1-5cm,F2-R9` |
| `logs/bisect-logs.tar.gz` | The eight bisect runs' logs (76e87740, 91a2c14a, 40a44732, 2ab43f23, ffeafe8a); findings in the plan's §5 | `tar --force-local -xzf logs/bisect-logs.tar.gz` |

`gen.js`:
- The bridge reports no widget text: `enumerate_widgets` gives class and box, and `get_screen_buttons` gives "" or "_".
- So `gen.js` keeps a label table restated from `FluidDeviceScreen.rebuild()` and `en_us.json`. It predicts the open screen's widgets, and resolves a label only when the live list equals the prediction exactly. Otherwise it stops and prints the list.
- It handles generator mixtures by choice label (the list is seeked through its scrollbar), rail fields by label, "Apply changes", the phase tabs, and "Connections by face".
- It clicks only while a screen is open: a compat click with no screen attacks a block.
- The selftest covers 33 recorded screens and 30 generated ones, 63 pass.

`row.js`:
- Places blocks by chat (`/setblock`), configures devices through `gen.js`, and waits on the engine's schedule.
- Screenshots the read views and greps the game log since a mark.
- Breaks blocks: destroy, a screenshot, each dropped stack's data printed to chat, then the drops cleared.
- Reloads the world: pause, Save and Quit, world list search, Play. Each step is checked by the screen's class.

Index lane:
- Adapted from `tools/unified-thermo-port-m3/scripts/`.
- `git archive <commit>` exports sources and resources into `/tmp/testworld-<label>/`, then javac and JUnit run through M3Run, each JVM with -Xmx6g.
- Both scripts refuse while a Minecraft dev client runs.
- At `87c106f6` the probes were checked against the API by reading only; they were not compiled.
- 2026-10-18, against `482264fc` (read only, not compiled): `TestWorldRigTest` already builds movers without an
  efficiency (S6). `TestWorldTrace` now reaches the step and rate solves by reflection, because S2 deleted
  `PassiveStepSolver.Acceptance`. Its reflective `phaseCheck` also takes D38 C's `from` argument. One file still compiles
  from main 0.8.0 to the port's head; before this change the probe compile failed at `482264fc`.
  - Rows 10 and 12 are restated from the gates and the code, not replayed.
  - Before the session: compile the branch head (`bash index-lane/compile.sh head 95e4325d` when written), then run the
    rig with `-DtestWorld.only=R1,R10-fence`.
- 2026-10-18, rig re-run at `00ee8385` and the H1-H4 traces (compiled and run):
  - `TestWorldRigTest.mover` builds a pump or compressor by reflection (two-argument record from `9445106a` on, three
    with efficiency 1.0 before), so the probes compile from `87c106f6` to HEAD.
  - `compile.sh`: `PATCH="<files>"` applies temporary hooks to the export only (`git apply -p1` in `/tmp/testworld-<label>`;
    the worktree is never patched); `EXTRA_PROBES` adds trace consumers. A `PROBES_ONLY=1` recompile of a trace label
    needs the same `EXTRA_PROBES` again, since it deletes the test classes.
  - Rig switches: `-DtestWorld.m5=ROW@from-to` (seconds; the M5/M9 consumers trace the slices that start in the window,
    at most `-DtestWorld.m5Max`), `-DtestWorld.history=ROW@from-to` (a HIST line per slice: status, detail, node states
    at the slice start, flows and modes; one window per row), `-Dm5.passLines`, `-Dm9.lines`, `-Dm10.lines`.
  - Labels used: `head` (`00ee8385`), `pre-d39` (`482264fc`), `at-26090bf0`, `at-65036f2b`, `at-eec02758`,
    `at-9445106a`, `base-87c1` (`87c106f6`); traced: `trace9-head`, `trace9-pre`, `trace9-2609` (M5 + M8 + M9),
    `h4-head` (M10). `c9e7c128` is read from `logs/testworld-logs.tar.gz` (`rig-ad33.log`).
- 2026-10-18, rig re-run at `3db5e5d3` (D40; label `d40`, 4 threads) and the in-game session:
  - Logs in `logs/2026-10-18-session/`: `compile-d40.txt`, `rig-d40-3db5e5d3.log` with `rig-d40.condensed.txt`, against
    `rig-at-88817099.log` and `rig-all-00ee8385.log`; `r8-bisect/` dates row 5's hold (H5) to `87c106f6`.
  - The session ran on save `utp-test-world-20261018-d40` (`runMcpClient-d40-gradle.log`; `d40/` holds the screenshots,
    row logs and scripts). The owner stopped it after rows 1 and 2 ("skip the client test for now"). The save is no
    longer fresh (rows 1 and 2 and row 3's first generator): a later session copies `world-template/` again.
  - Driver changes in `mcp/row.js` and `mcp/gen.js`: the world-search box is found by its position, since the bridge
    does not list it; a newly placed device's screen is awaited until it is editable (`FluidDeviceMenu.clientData` is
    null until the first bucket); "Connections by face" is clicked again if its page does not come up.

Row changes from the E4 table, each forced by the current design (reasons in the plan):
- R3 uses CO2 at 220 K: at 210 K the generator holds a stand-in liquid.
- R4 has a third leg to an empty tank so that J2 is a junction, and its cold tank is charged from 110 K.
- R13 lets down through a 4 mm pipe into a tank held at 0.3 MPa by the valve on its outlet.
- R2-R7-R9 is a vented methane pool fed CO2 through a 1 cm pipe.
- D23-1 lets down through a 4 mm pipe.
- D23-2 (b) fills, cuts the feed, and vents before its bottom outlet is placed.
- R10 shows the typed CRYSTAL_PHASES refusal. A generator refuses an unsupported phase set, so acceptAtFence is the
  new row 12 (`R10-fence`, 2026-10-17). Liquid ethane and liquid nitrogen mix in a tank at 95 K into two liquids, which
  is a phase hold inside a network; then the nitrogen generator is edited on the held island.
- R1 (2026-10-17): its water hold is gone with the ice (WP4). It now reads the ice, the deposition on the water line and
  the washout. It also carries WP3's GUI check: a 260 K water generator puts "; outside data range: liquid water
  extrapolated below 273.16 K at generator at 20, 101, 98" on the island's status line.
- R12 breaks the D23-2 slush tank.

Session set-up (no client running, one Gradle lane):
1. Write `guiScale:1` into `<worktree>/run/mcp-client/options.txt`. At 854x480 every device column fits without scrolling the rail; `gen.js` also works at scale 2. Keep `pauseOnLostFocus:false`.
2. Copy the world template and the presets.
3. Start `runMcpClient` with the port's build: `bash tools/unified-thermo-port-m1/gradle-locked.sh <log> runMcpClient`.
4. Run `node mcp/bridge.js wait-ready`, then `node mcp/row.js open-world <folder>` and `node mcp/row.js setup`.
5. Run the rows in order with `MC_WORLD=<folder>`.

The agents' no-desktop-input rule holds throughout: every input goes through the bridge.

## Rename to IChemE (2026-10-21)

Batch `2026-10-21-rename-icheme`: the files this folder runs against the current tree use the package `com.wormzjl.icheme` and the namespace `icheme` (mod rename, `8033aed8`). Patches, diffs and probes tied to an older commit keep `createcheme`: they re-attach to that commit. The absolute folder path `D:/Minecraft/Modding/1.21/CreateChemE` is unchanged. A worktree copy of this folder (for example the lane worktree `coolprop-multiphase-thermo-37f6b0`) is refreshed from here before its next run.

The datapack `createcheme_bench` keeps its folder and namespace: the template world's `level.dat` enables it by that name. Its functions and `p6_pilot` (now `data/icheme/`) use `icheme:` ids. The template world's own region files still hold `createcheme:` blocks, which the renamed mod does not load: regenerate the world (fresh world, AGENTS.md) before its next in-game session.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: The owner's test world for the unified thermo port: world template (bench-template plus the `p6_pilot` datapack), eight pilot client mixtures, MCP helpers (`bridge.js`, `gen.js` that clicks by predicted label, `row.js`, `check-rows.js`), eleven row files, engine-path row replays on the index lane, logs

Batch: 2026-09-24-coolprop-low-temperature

Status: Prepared 2026-10-16. Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`; `mcp/`; `rows/`

### History

- Prepared 2026-10-16 (run sheet `TEST_WORLD_PLAN.md`)
- rig re-runs at `00ee8385` (holds H1 to H4, `TEST_WORLD_RIG_HOLDS_TRACE.md`) and `3db5e5d3` (H1 to H3 gone, H4 and H5 held) 2026-10-18
- in-game session stopped by the owner after rows 1 and 2 (2026-10-18)
- owner 2026-10-19: rows 3 to 12 and rig rows 5 and 10 run after the merge

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 47 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/unified-thermo-port-test-world/`, at the same relative paths.
