# Merge preparation of the review branch (after wave A)

Branch `claude/codebase-review-refactor-8a85ef` at e1b082cc1 (code at 4563176e1), against `main` 259d22a2c (0.30.0). Covers what merged after wave A's preparation (fd7786fee): wave B (B-T1, B-S1, B-S2, B-R1, B-M1, B-E1, B-C1, B-K1), C1a, G0, G1 part 1, Rust stage (a) parts 1, 2a and 2b, the level law fix and the wave A GameTest fixes. Documents only: no Gradle, cargo or JVM run, no source or build file changed. The final lane for this code is the second run of [wave-b/FINAL_LANE_REPORT.md](wave-b/FINAL_LANE_REPORT.md) (green at 4563176e1).

**Owner, 2026-11-15: "this whole work will merge together".** The branch goes to `main` in one merge. Read every "in a merge of its own" alternative below as not chosen: the sections per unit stay as the record of each unit's preparation, and the whole branch takes one version (0.31.0 proposed) and one changelog section.

## Version proposal (the owner chooses)

- **Proposed: one merge as 0.31.0 (minor).** By the CHANGELOG's Version rule a merge that holds a feature, a solver or model change or new content is minor. This unit holds all three: a new wear mode (low flow of centrifugal pumps) and the fault table, a model change of the electricity a driver pays (G0), the water module (a stated last-digit shift of wet states), the Rust column library replacing the C++ one, and the level law. The wave A fixes ride along. Needs: nothing more than what stands (the second final lane at 4563176e1, this preparation, no translation commit).
- **Alternative: wave A alone first as 0.30.1 (patch), then the rest as 0.31.0.** The wave A release candidate is the local branch `claude/codebase-review-wave-a` (39c22313e: the lane's code 0f0993162 plus documents). Its own final lane was not green (two `MaterialCatalogTest` expectations, five GameTests). It needs the test-only fixes cherry-picked (`fd8f1a5da`; `68bcad7a9` to `97f86225e`, fix 2's helper first), one final lane on that code, and its CHANGELOG cut to the wave A lines (the Fixed entry and the NO POWER line, which is M-04, a wave A fix). The later 0.31.0 then needs a final lane of its own against 0.30.1 (the 4563176e1 lane was read against 0.30.0). Cost: a second lane and a split CHANGELOG; gain: the fixes reach `main` without the play changes.
- `gradle.properties` is not edited and `[Unreleased]` stays unreleased.

## Translation

`git diff --stat 259d22a2c..HEAD -- src/main/resources/assets/icheme/lang` is empty: no lang file changed. No translation commit needed. (The words LOW FLOW and the other status words are raw strings, no lang key: G1_REPORT.)

## Formats

`FORMATS.md` read against the code. No facts schema, state format, protocol or shape field constant changed in `src/main/java` between 259d22a2c and HEAD (a diff search for the format constants finds none). The one new section, "Native library", is right: `NativeColumnSolver.ABI` = 1 at line 38, `FORMULATION` at line 40; their twins in `src/main/rust/icheme-jni/src/layout.rs` lines 7 and 13. Result: no change.

## Code guide

Chapters changed (snapshot `4563176e1`): `fluid-science` (`UpLookingLu`, `SparseLuSolver.Storage`, the prepared refill, what a step holds and reuses, `WaterRegion1` as a facade), `equilibrium-engine` (the water module, `SubstitutionAcceleration`, `SmallDenseSolve`, the shift tables, `cubicPressure`, `WaterShare`'s delegation), `thermo-materials` (the kernel's root selection, `differentiatePrepared`, the prepared excess ratios and non-partner lists, the power table, `HenryConstant`, `WaterRecords`), `column-v3` (the ledger on int tables, the factorization counts, the feed flash kept, P8 and P9 in the band solver, `V3WaterProperties` on the module; the old "the network calls `V3WaterProperties`" was wrong after C1a and is corrected), `fluid-runtime` (the two worker lanes, `ViewIndex` and the bucket's reads, the display publish slot, the settled transit's order, the fences, the wear config keys, which still named the pre-2026-11-11 keys; `stored` deleted), `rotating-machinery` (`MachineHealth`, the fault table and rate rule, LOW FLOW, the dry screw's ramp, `InlineMachines.shaftPower` and the three stated losses, `ratedShaftPower` on the design fluids, the live shaft view, NO POWER of an in-line mover), `equipment` (`BoundedCache`, the unit cached facing north, the body regions on a hash), `client-gui` (the frame batch of five screens, `FrameBatchEvents`, `GuiCanvas.of`, the kept door column, the data ports every 5 ticks), `verification` (the gate groups' water module, the water tests), `process-control` (snapshot only: its level-law text was written with 4563176e1), `README` (code map counts, `MachineHealth`, the water module). `README.md` is 112 lines. The Rust and native-smoke text was already right (9294f6b98).

## Tooling cleanup (listed, nothing moved)

`perl tools/merge-lane/main-reach.pl .`: 4 classes reached only by tests, all older than this batch (`DhBridge`, `DhGroups`, `IrisBridge`, `IChemEJeiPlugin`: optional-mod bridges loaded by name). None of this batch.

| Item | What reads it | Recommendation |
|---|---|---|
| `UpLookingLu` process-wide `LongAdder`s `COLUMNS`, `SEARCHES` (`columnsFactored`, `reachSearches`) | Only `UpLookingLuParityTest.printsTheLuCounters`, which prints them (no assertion; the gate asserts the instance's `searches()`). Two adds per factorization, always on | Fold into `SolverDiagnostics` (counted only under `ENABLED`, as the other solver counters) or delete with the printed line. No value moves either way; not a merge blocker |
| `SparseLuSolver.observer` (package-private, volatile, null outside the test) | The parity gate `UpLookingLuParityTest` (its step matrices) | Stays: a gate reads it and it costs one null read per factorization |
| `V3SolveScratch` factorization counts and `factorizationEvent` | The published solver event; `V3BandFactorizationCountTest` | Stays: product diagnostics |
| `V3BandedPivotedSolver.Workspace` P8 counters (`updatedEntries`, `fullWidthEntries`, `fullWidthSolves`) | `V3BandedPivotedSolverTest`, `RustNativeTables`, `NativeColumnSolverTest`; the Rust library returns them in its counts array (the JNI layout) | Stays: part of the native layout and its gate |
| `FluidThermodynamics.Prepared.workspacesBuilt` (`LongAdder`) | `PreparedRefillTest` (asserts), `PassiveStepSolverTest` (prints) | Stays (a gate asserts it); could join `SolverDiagnostics` as above |
| `HeldDraws.textNow` | The kit's draw count: `FrameBatchDrawsTest`, `HeldDrawsTest` | Stays: part of the batch's accounting |
| `ControlService.LevelDecision`, `levelDecision(panel)` | `LevelLoopGameTests` (the GameTest gate's record of each decision) | Stays: a GameTest reads it; it is a read that changes nothing |
| `NativeColumnSolver.ceilingForTest`, `bandSolves`, `loadReport` | `NativeColumnSolverTest` (`nativeSmoke`) | Stays |
| `tools/native-column-default/` | Nothing: its probes set `icheme.native.thermo.batch` (no effect now) and `probes/Check.java` calls the removed `thermoBatchEnabled` and looks for `icheme_column_solver.dll` | Marked Superseded 2026-11-13 in `tools/INDEX.md`; add a superseded note to its README at the merge (the folder stays as the record of the 0.18.0 qualification) |
| `tools/column-operation/README.md` line 17 (`-PnativeThermoBatch=false`), `probes/ColumnOperationC1BenchTest.java` line 373 (`thermoBatchEnabled()`) | Nothing; the probe would not compile if copied in | Note in the README that the switch is gone and the probe's file name now always takes the Java path; or replace the call by `"java"` |
| `tools/codebase-review-bench/ColumnBench.java` line 100 | The bench's header line prints the dead property | Print `icheme.native.column` instead (harmless as is) |

No CHANGELOG line for detached code: nothing was detached.

## Unit 2 (added by the main thread, 2026-11-13)

Unit 2 is the review branch after the marker `claude/codebase-review-unit1`, to the marker `claude/codebase-review-unit2`: 261 files, +4,955 −2,626. Its final lane is green ([UNIT2_FINAL_LANE_REPORT.md](UNIT2_FINAL_LANE_REPORT.md)). Nothing is merged to `main`.

- **Version proposal (the owner chooses).** Units 1 and 2 in one merge: 0.31.0, as proposed above (unit 2 adds features of the same kind: wet gas and wet exhaust wear, the gearbox limit). Unit 2 in a merge of its own after unit 1: 0.32.0 (minor: new wear modes and a changed model of an under-rated gearbox). `gradle.properties` is not edited.
- **Translation.** One commit, `c1ad97269`: eight keys in four languages follow `en_us.json`; `LanguageFilesTest` and `UnitTextTest` pass in the lane.
- **Formats.** No facts schema, state format, protocol or shape field changed (`FormatsTableTest`, 31 rows of `FORMATS.md` against the code; `FluidFactsCompatTest` 22). Health facts now exist for a centrifugal compressor and a steam turbine under the same field.
- **Code guide.** Updated in each merge; `node tools/code-guide/map.mjs --check` reports the README's counts current.
- **CHANGELOG.** `[Unreleased]` gains: wet gas, wet exhaust and the gearbox limit (Added, Changed), the trips, the inside-out pass removed, EJML out of the jar, the layers and their tests.
- **Tooling.** `tools/native-column-default/` is removed with E0-4; `tools/code-guide/` is new; `ColumnBench` prints the Rust ceiling. `perl tools/merge-lane/main-reach.pl .`: the same four optional-mod bridges as before, none of this batch. `git ls-files -i -c --exclude-standard` is empty. Left: `V3TestInputs.cdu17BoilingBinary` has no caller (test tree).
- **Changes of play in unit 2, for the owner's reading before a merge:** a centrifugal compressor on a wet suction and a steam turbine on saturated inlet steam now wear and can fail; a gearbox below its train's power now holds the train at its rating (GEAR LIMITED), where it passed any power; the wet exhaust numbers are set on the solve's turbine law ([wave-g/G1_PART2_REPORT.md](wave-g/G1_PART2_REPORT.md)).

## Unit 3 (added by the main thread, 2026-11-13)

Unit 3 is the review branch after the marker `claude/codebase-review-unit2`, to the marker `claude/codebase-review-unit3`: 270 files, +14,978 −9,631 (233 of them source files). Its final lane is green ([UNIT3_FINAL_LANE_REPORT.md](UNIT3_FINAL_LANE_REPORT.md)). Nothing is merged to `main`.

- **Version proposal (the owner chooses).** Units 1 to 3 in one merge: 0.31.0, as proposed above (unit 3 adds no feature: file splits and two defect fixes). Unit 3 in a merge of its own after the others: a patch (0.31.1 or 0.32.1): the still's fixes change what a player sees of a batch still, but they are fixes. `gradle.properties` is not changed.
- **Translation.** None: no language file changed in unit 3.
- **Formats.** No facts schema, state format, protocol or shape field changed (`FormatsTableTest`; `FluidFactsCompatTest` 22). The still's new logs and the worker records are memory only.
- **Code guide.** Updated in each merge; `node tools/code-guide/map.mjs --check` reports the README's counts current.
- **CHANGELOG.** `[Unreleased]` gains: the still's two fixes and the engine's (Fixed), the file splits and the code guide (Changed).
- **Tooling.** Nothing left `src/main` and nothing new is in it: `perl tools/merge-lane/main-reach.pl .` reports the same four optional-mod bridges as before. `git ls-files -i -c --exclude-standard` is empty (`.gitignore` gained an exception for `science/equipment/design/`, which the unanchored `design/` rule hid). 62 archived probe files under `tools/` still call the device record's removed constructors and about 20 name moved rating methods; nothing compiles them.
- **Changes of play in unit 3, for the owner's reading before a merge:** a batch still's cooling water takes the condenser duty one fence (22 s of play) later, and the heat in transit holds that much more; its column is solved every 200 ticks on every machine (a fast one gave 100); a liquid-fired pot follows its fuel a fence later, lights about 44 s of play after its first column answer and shows NO FUEL only where its burners asked fuel and burned none; a controller on a still corrects every 1280 ticks (840 before). Conservation is exact in all of them. Decisions taken under the owner's grant, for the owner to confirm or overrule: STILL_HEAT_READ_REVIEW option B, R-ST2's cadence by ticks, the one response time for every still (a per-heater time is the alternative).
- **Not looked at in the game:** a batch still after the two fixes; the items listed for units 1 and 2.

## Unit 4 (added by the main thread, 2026-11-14)

Unit 4 is the review branch after the marker `claude/codebase-review-unit3`, to the marker `claude/codebase-review-unit4`. Its final lane is green ([UNIT4_FINAL_LANE_REPORT.md](UNIT4_FINAL_LANE_REPORT.md)). Nothing is merged to `main`.

- **Version proposal (the owner chooses).** Units 1 to 4 in one merge: 0.31.0, as proposed above (unit 4 adds no feature). Unit 4 in a merge of its own after the others: a patch. `gradle.properties` is not changed.
- **Translation.** None: no language file changed in unit 4.
- **Formats.** No facts schema, state format or shape field changed. One wire change: every registrar reads `NetworkProtocol.VERSION` (`icheme-1`), so a client and a server of different builds are refused at login; `FORMATS.md` keeps one row per registrar, each naming that constant (`FormatsTableTest`).
- **Code guide.** Updated in each merge; `node tools/code-guide/map.mjs --check` reports the README's counts current.
- **CHANGELOG.** `[Unreleased]` gains: the file splits and the code guide (Changed), the one protocol version (Changed, written with D-F1).
- **Tooling.** Nothing left `src/main` and nothing new is in it. `git ls-files -i -c --exclude-standard` is empty. `build.gradle`'s `columnGates` list gained `network/Wire*` (column tests load the wire helper through the column's codecs). An archived probe (`tools/custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java`) reads display fields by reflection that no longer exist; nothing compiles or runs it.
- **Changes a player or a server owner can see:** client and server must be the same build (true of any protocol bump before; now of any payload change). Nothing else.
- **Decisions left to the owner:** the sheet's core and sizing packages still import each other (the sizing facts are built on sealed sizing types): accept and pin it in a test, or move the facts to a `flowsheet/facts` package ([wave-d/D-F1_REPORT.md](wave-d/D-F1_REPORT.md), section 1). The designs' `Sizes` record holds a placeholder for each value a design computes in an override (44 of them); a record of only the three always-constant fields is the stricter alternative. The report file of D-Q3 was not written by its subagent, which said its harness refused report files; the main thread did not write it in its place.
- **Not looked at in the game:** a watched tank, sight glass, mover, flare, still, tower, heater and chimney (the watch and replay paths have no automated test); the machine page's title keys; the items listed for units 1 to 3.

## Unit 5 (added by the main thread, 2026-11-15)

Unit 5 is the review branch after the marker `claude/codebase-review-unit4`, to the marker `claude/codebase-review-unit5`. Its final lane is green ([UNIT5_FINAL_LANE_REPORT.md](UNIT5_FINAL_LANE_REPORT.md)). Nothing is merged to `main`; by the owner's decision of 2026-11-15 the branch merges whole, after unit 6.

- **Version.** Part of the one merge: 0.31.0 proposed. `gradle.properties` is not changed.
- **Translation.** None: no language file changed in unit 5.
- **Formats.** No facts schema, state format number or shape field changed. A rest certificate's third signature field holds the declared replay revision in the 32 bytes that held a digest (state format 50 stays; an older digest reads as another revision). An equipment cell writes the tag `LayoutRevision` and no longer writes `Layout`, which it still reads (block-entity state, not a fact). `FORMATS.md` names the replay revision.
- **Code guide.** Updated in each merge; `node tools/code-guide/map.mjs --check` reports the README's counts current. Unit 6 restructures the chapters.
- **CHANGELOG.** `[Unreleased]` gains: the structure of unit 5 and the two declared revisions (Changed); the column's product pricing, the heat curve's boundary, the sheet's compressor discharge and ejector load, the step's allocation (Changed); a tank's Accessory tab and the batch still beside islands at rest (Fixed).
- **Tooling.** Nothing left `src/main` and nothing new is in it. `git ls-files -i -c --exclude-standard` is empty. `build.gradle`: `ThermoRefusal` joined two gate groups' class lists. Three archived scripts under `tools/simulator-sizing/archive/` and one probe under `tools/custom-equipment-rendering-poc/` were renamed to follow moved names; nothing compiles them.
- **Changes a player or a server owner can see:** a batch still beside resting islands gives distillate again and lights its burners (it did neither once its outlet or burner island had come to rest); a still waits for the solve service where it skipped a column solve under load; a tank's Accessory tab opens; more compressors read a yellow discharge lamp on the simulator (175 degC where 137 degC was read at ratio 3); a wet ejector suction needs less steam on the simulator; an older world's rest certificates are rebuilt once.
- **Decisions the main thread took, for the owner to overrule:** products priced by mass, not by the review's price per mole (measured 966 against 144 J/mol at the composition band's edge); P-09 (per-solver tables) taken back out for want of a measurement; a new equipment cell whose shape was repaired on read counts as stale (one refresh that changes nothing); the refused still job waits where it was skipped.
- **Not looked at in the game:** the device screen's pages at a wide and at the smallest window, a still's and a converter's Apply, the Control tab's Choice rows; clicks, drags, the wheel and typed names on the constructor, gauge, machine, pipeline tool, vessel, control panel and assembly screens; driver loops, shafts turning after a portal, flames and plumes, bodies at Low detail, the field guide's film actors; a tank's Accessory tab with a blanketing or a safety valve; a refitted safety valve; a batch still left idle and restarted; a placed structure after a reload on an older save.
