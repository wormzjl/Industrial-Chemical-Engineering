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

Chapters this thread wrote or updated (snapshot at the cleanup commit):
- `process-simulator.md` (new).
- `README.md`: the chapter row, the code map, the snapshot line.
- `thermo-materials.md`: the crude packages W5 removed, and plan 3.10's rule.
- `column-v3.md`: what this batch changed in the V3 package.
- `verification.md`: the simulator's gate classes, the corpus images, `ProcessSimulatorGameTests`.

Chapters of the s3b-member-counts thread: listed when the orchestrator passes them.
