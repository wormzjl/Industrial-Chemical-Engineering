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

AGENTS.md, Code guide. Every touched chapter's snapshot line names `6bf2205bf`, the batch's last code commit, dated 2026-11-03. Two threads wrote, on separate files. No Gradle run: documents only.

This thread:
- `process-simulator.md` (new): the class map of `science/flowsheet` (core, units, sizing), `client/gui/flowsheet` and the block side; a run's flow on the runner thread; calculations, links and utility feeds; sizing, lamps, the 20 % spare and Size all; the block's store, holder, replay, files and autosave; Send to block and Import; the formats and their corpus images; traps; plan 3.10's rule (New equipment joins the simulator, `FlowsheetCatalog` and `SizingCoverage` in `src/test`); the gates.
- `README.md`: the overview's simulator bullet and header line, the code-map row (and the old calculator row rewritten as the column's wire and worker row), routing, a reading path, the batch's section, open items 4 and 5 (calculator parts closed), the snapshot line, the plan 3.10 rule under Maintaining. File counts: 525 main, 431 test, 25 GameTest files.
- `column-v3.md`: the calculator block, menu, screen, packets, presentation, editor catalog and preset class are gone (W8); the two callers (`ColumnUnit.solve` on the simulator's thread, `ColumnSolve.run` on the workers); `ColumnWire` and Save design on the simulator; `V3ColumnTemplate` (W5); four bundled packages; the removed tests named.
- `thermo-materials.md`: the five per-crude packages and their column presets removed by W5, their assays on `tjl20_methane` at `crude-regrouped-r2`, three column presets, 84 indexed files, `TJL20_PHYSICS`, adding a crude feed.
- `verification.md`: `holdGates` with twelve classes, `ProcessSimulatorGameTests` on `runColumnGameTestServer`, the `science/flowsheet` and `client/gui/flowsheet` test rows, the network and block rows, the corpus images (`compat/flowsheet`, `compat/simulator`, `compat/names`, `compat/presets`, fluid schemas 8 and 9, shapes 6 and 7), the GameTests the calculator took with it, the batch line.

The s3b-member-counts thread (`7d3708d41`): `client-gui.md`, `column-operation.md`, `cooling-towers.md`, `equipment.md`, `fluid-runtime.md`, `fluid-science.md`, `heat-exchangers.md`, `machine-assemblies.md`.

Not touched, no code of theirs changed in this batch: `equilibrium-engine.md`, `instrument-accessories.md`, `pipe-laying.md`. Not touched, open: `process-control.md`. S1 (`9a3b9cfd5`) added three constants to `FlowControl` (`MAXIMUM_VOLUME_FLOW` 10 m³/s, `MAXIMUM_ADDED_PRESSURE` 2 MPa, `MAXIMUM_PRESSURE_RATIO` 10), so the sizing table reads the world's mover limits. The chapter's owner thread decides whether it needs a line.
