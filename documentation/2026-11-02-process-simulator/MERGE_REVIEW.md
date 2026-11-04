# Merge review: process simulator (2026-11-03)

Owner, 2026-11-03: "do the cleanup and code guide first". The final lane, the CHANGELOG version entry and the version bump come with merge prep. The branch carries main 0.19.3 (`b56de1f4f`).

## Tooling cleanup

AGENTS.md, Tooling cleanup, over everything the batch adds to `main` (`git diff main HEAD` at `dab55f0c1`, under `src` and `build.gradle`: 175 files added, 146 changed, 9 renamed, 28 removed).

How it was searched:
- `src/main`: `perl tools/merge-lane/main-reach.pl .` lists the classes the game cannot reach from its entry points. Before the cleanup: 525 classes, 2 unreachable. After: 523, 0.
- Added `src/main` lines with test hooks, probes or prints, read by hand: none (one comment names the probe a heater's coils match).
- `src/test` and `src/fluidGameTest`, every added or changed file:
  - `@Test` methods with no assertion (a script over the bodies): 5, each calling a helper that asserts (`SimulatorRunTest.same`, `RangeContinuationGateTest.valueAndSlope`, `PilotCryogenicCatalogTest.refused`). None only prints.
  - Tests that print: 15 classes print their numbers for the batch's documents beside their assertions (`ColumnRunTest`'s C1b and U2 lines, `ColumnSolveTest.theCondensersUtilitiesOnTheDefaultColumn`, the sizing sample tests). Each holds a rule: for example, the U2 condenser test asserts that hotter water takes more flow, that Auto's spare leaves the fans under full speed, and the cube law of fan power. They stay.
  - Non-test classes added: `SheetFixture`, the sample sheet that 15 tests read. It stays.
  - `ColumnRunTest.probeU2Islands` is not on the branch. The U2 islands are held by `aCondensersWaterValveCarriesItsDutyOverTheWatersRise`, `aShortWaterValveLimitsTheColumn` and `anAirCondensersFansHoldItsDutyAndAShortFaceLimitsTheColumn`, which all assert.
- Build: `build.gradle` changes only `slowGateClasses` (the eight simulator classes). No run configuration or Gradle switch serves a probe.

What moved:

| Class | From | To | Why |
|---|---|---|---|
| `FlowsheetCatalog` | `src/main/.../client/gui/flowsheet/` | `src/test/.../client/gui/flowsheet/` | Only `FlowsheetPaletteCoverageTest` reads it. Its switches without a default make a new kind fail `compileTestJava`, which `test` runs first. AGENTS.md's New equipment rule names the class, not its source set. |
| `SizingCoverage` | `src/main/.../science/flowsheet/sizing/` | `src/test/.../science/flowsheet/sizing/` | Only `SizingCoverageTest` reads it, the same pattern. |

This follows 0.19.1's precedent (`0c88691e5`, the Holland classes): code that only tests use leaves `src/main`, and its gate stays. Nothing went to `tools/`. No test was removed, so the suite neither widens nor narrows.

Tool folders. Every probe of the batch was written in `tools/` and none was tracked under `src`, so no README needs a re-attach patch:
- [`tools/process-simulator/`](../../tools/process-simulator/README.md): the plain-JVM probes (client run, flash sweep, loop passes, sample flowsheet, sizing sample, stop gap, ejector), the one-time tests (`FeedValveAtInletProbeTest`, `YellowNoiseProbeTest`, `FlashProbeTest`, copied into `src/test` to run), and the vacuum-set law. All eleven files are named in its README.
- [`tools/device-names/`](../../tools/device-names/README.md): the nameplate placement probe and the plate renders.
- [`tools/heat-gui-renders/`](../../tools/heat-gui-renders/README.md), in use: the batch added `SimulatorPrototypes`, `SimulatorRender`, `SimulatorSizing`, `SimulatorIcons`, `SymbolAtlas` and `BlockOptions`, and its README names each.
- `tools/localization/`: the glossary gains the simulator's terms (the 0.19.3 merge).
- `tools/INDEX.md` has the rows for `process-simulator/`, `device-names/` and `heat-gui-renders/` (batch named).

Runs (AGENTS.md, No repeated tests):
- `compileJava`, `compileTestJava`, `compileFluidGameTestJava`: pass. `FlowsheetCatalog.class` is in the test output only.
- The tests that use the moved classes: `FlowsheetPaletteCoverageTest` 5/5, `SizingCoverageTest` 3/3, `ConstructorColumnTest` 8/8 (it reads `SizingCoverageTest.samples()`).
- `tmp/` is outside the cleanup. Its folders go after the merge (user scratch rules).

## Code guide

AGENTS.md, Code guide. Every touched chapter's snapshot line names `6bf2205bf`, the batch's last code commit at the cleanup, dated 2026-11-03; `client-gui.md` and `process-simulator.md` name `0a04faffb`, the draw-call batch after it. Two threads wrote, on separate files. No Gradle run: documents only.

This thread:
- `process-simulator.md` (new): the class map of `science/flowsheet` (core, units, sizing), `client/gui/flowsheet` and the block side; a run's flow on the runner thread; calculations, links and utility feeds; sizing, lamps, the 20 % spare and Size all; the block's store, holder, replay, files and autosave; Send to block and Import; the formats and their corpus images; traps; plan 3.10's rule (New equipment joins the simulator, `FlowsheetCatalog` and `SizingCoverage` in `src/test`); the gates.
- `README.md`: the overview's simulator bullet and header line, the code-map row (and the old calculator row rewritten as the column's wire and worker row), routing, a reading path, the batch's section, open items 4 and 5 (calculator parts closed), the snapshot line, the plan 3.10 rule under Maintaining. File counts: 525 main, 431 test, 25 GameTest files.
- `column-v3.md`: the calculator block, menu, screen, packets, presentation, editor catalog and preset class are gone (W8); the two callers (`ColumnUnit.solve` on the simulator's thread, `ColumnSolve.run` on the workers); `ColumnWire` and Save design on the simulator; `V3ColumnTemplate` (W5); four bundled packages; the removed tests named.
- `thermo-materials.md`: the five per-crude packages and their column presets removed by W5, their assays on `tjl20_methane` at `crude-regrouped-r2`, three column presets, 84 indexed files, `TJL20_PHYSICS`, adding a crude feed.
- `verification.md`: `holdGates` with twelve classes, `ProcessSimulatorGameTests` on `runColumnGameTestServer`, the `science/flowsheet` and `client/gui/flowsheet` test rows, the network and block rows, the corpus images (`compat/flowsheet`, `compat/simulator`, `compat/names`, `compat/presets`, fluid schemas 8 and 9, shapes 6 and 7), the GameTests the calculator took with it, the batch line.

The s3b-member-counts thread (`7d3708d41` and the fixes commit after it): `client-gui.md`, `column-operation.md`, `cooling-towers.md`, `equipment.md`, `fluid-runtime.md`, `fluid-science.md`, `heat-exchangers.md`, `machine-assemblies.md`; `pipe-laying.md`, whose assembly-marks row named the deleted `AssemblyPortColoursTest` (now `AssemblyDiagramTest.everyPortHasItsOwnColourForTheLayingTool`).

The draw-call batch (`0a04faffb` and its documents commit, the owner's "A: batch the loops"): `client-gui.md` (`Canvas.batch` and `GuiCanvas.batch` in the kit rows, `FlowsheetPainter`'s batched shapes, the draw-call note with what is batched and what is not, a change-table row with `DrawCallTest` and `-Dcount`) and `process-simulator.md` (`SheetPainter`'s batches, `DrawCallTest` in the screen's row and the tests).

Not touched, no code of theirs changed in this batch: `equilibrium-engine.md`, `instrument-accessories.md`. Not touched, no change: `process-control.md`. S1 (`9a3b9cfd5`) added three constants to `FlowControl` (`MAXIMUM_VOLUME_FLOW` 10 m³/s, `MAXIMUM_ADDED_PRESSURE` 2 MPa, `MAXIMUM_PRESSURE_RATIO` 10), so the sizing table reads the world's mover limits. The constants are on `FlowControl.Mover`, `Pump` and `Compressor`; the chapter documents only `FlowControl.ControlValve`, and `fluid-runtime.md` and `fluid-science.md` already name all three, so it needs none.

## Merge preparation (2026-11-04)

Owner, 2026-11-04: "prepare for merge". Main has no commit the branch lacks (merge base `744ef4513`, main 0.19.3); the branch is 198 commits ahead. Code at `fc0ae90e1`.

Cleanup, again over what came after `dab55f0c1` (81 commits: the screen's key groups, the door and reading fixes, the P, T key, the member tree, P1/P2 warm start, the draw-call work): `main-reach.pl` 540 classes, 0 unreachable; no print, probe or TODO added under `src/main`; every added `tools/` folder (`process-simulator`, `device-names`, `simulator-bench`, `heat-gui-renders`, `localization`, `custom-equipment-rendering-poc`) has its README and its `tools/INDEX.md` row; `git ls-files -i -c --exclude-standard` is empty. Code guide: README counts current (543 main, 443 test, 25 GameTest files); `process-simulator.md` at `a5e1304e2`, `client-gui.md` at `d8244a143`.

Final lane, `bash tools/merge-lane/lane.sh head` at `fc0ae90e1`, alone on the machine (no other JVM, 19.6 GB free). Logs: `tools/merge-lane/logs/process-simulator-final-lane-logs-2026-11-04.tar.gz` (main checkout, local). Bases, no base lane run:
- Campaign: main's own, the dry-ice drawn-arrival campaign at `e7a3a7f2b` ([DRAWN_ARRIVAL_REVIEW.md](../2026-11-02-dry-ice-cost/DRAWN_ARRIVAL_REVIEW.md)); nothing the campaign loads changed between it and main.
- Gates, junction lines, chain-100, fluid GameTests: the dry-ice final lane at `803970ac1`. Main after it took the drawn arrival (`PassiveStepSolver`, whose gates passed with no expectation changed), the `SolidTransportAcceptanceTest` bound and 0.19.3's lang files and text tests.

| Step | Head | Base |
|---|---|---|
| `test slowGates` (test, holdGates, columnGates, equipmentGates) | 2641 tests, 0 failures, 365 s | 2108 tests, 1 failure (the `SolidTransportAcceptanceTest` bound, fixed before 0.19.2) |
| Junction lines | 33, the same values (only `ms`, `bytes`, `allocatedMB` differ) | 33 |
| chain-100 exact | max deviation 0 in every field | 0 |
| Fluid GameTests | 37/37 | 39/39 |
| Simulator GameTests (`runColumnGameTestServer`, outside the lane) | 11/11 | none |
| Campaign cold random | 992, `refused=0` | 992 |
| Campaign bundled random | 994, `refused=0`; 995 with seed 6 replayed alone | 995 |
| Campaign bundled defaults | 1000 | 1000 |

- Held seeds as main's, each in its class: cold 85, 988 (E1), 752, 803 (E5), 101, 206, 446 (E6), 118 (F); bundled 430, 579 (E10), 431 (E6), 698 (E8), 241 (F). Bundled seed 6 held twice in the campaign (class F, 27 s under eight workers), not in main's: replayed alone (AGENTS.md, a new budget-class hold), it runs clean in 16 s. No new hold.
- Fluid GameTests 37 against 39: W8 removed the calculator block and with it `ChunkLoadGameTests.aCalculatorRemovedWhileItCalculatesCancelsItsSolve` and `ColumnRegroupingGameTests.unreadableColumnDataIsRefusedAndReplacedByAFreshInput`; the simulator's own eleven run on `runColumnGameTestServer`.
- Printed lines of the classes on both sides, with timestamps, timings, hash codes and allocation removed: the assembly store's state format reads 3 (was 2; a state format, AGENTS.md); `FluidCheckpointStoreTest`'s core file is 21 bytes larger (403242), the batch's facts additions; `FluidFactsCompatTest` and `ColumnRunTest` print lines for the batch's new images and tests. Four log4j `Error writing to RandomAccessFile logs/latest.log` traces under `AssemblyStoreTest` are the logger's, after its stream closed; the tests pass.

Version: a new feature batch, so a minor bump, proposed 0.19.3 → 0.20.0, for the owner to choose. The owner chose it, 2026-11-04: "0.20.0, merge". The merge turns the `[Unreleased]` line into the version heading and bumps `mod_version`; it is not pushed.

Open after the merge (batch README): the column door's pump selector goes to the rotating machinery branch (owner); E readings on the 46 px LED meters stay as text (owner); nothing checked in game since the owner's manual test at `bd146c029`.
