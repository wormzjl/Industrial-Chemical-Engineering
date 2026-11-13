# J2 report: the code map tool, LayeringTest, FormatsTableTest, stale guide texts

Package J2 of wave J (DOCS_AND_COLLABORATION_PLAN 2.3 to 2.5), on top of `claude/codebase-review-refactor-8a85ef` (`b474a0b75`). No Gradle, no JVM: both tests are written, not run.

| Item | Commit | What it does |
|---|---|---|
| 1 | `a1c2ca191` | `tools/code-guide/map.mjs` (+ README, `tools/INDEX.md` row); README module counts rewritten by `--readme` |
| 2 | `eb2aed10f` | `src/test/java/com/wormzjl/icheme/LayeringTest.java`: 15 top-level rules |
| 3 | `9c461f6d0` | `src/test/java/com/wormzjl/icheme/FormatsTableTest.java`; FORMATS.md names its constants `Class#FIELD` |
| 4 | `5d4a8d3c7` | Stale texts in `process-control.md`, `equipment.md`, `cooling-towers.md`, `verification.md` |

**Map (item 1).** 874 files, 210,980 lines in `src/main/java` (57 packages at depth 3). `--readme` changed nine numbers only (`unit/` 16→22, `sizing/` 30→35, `client/gui/flowsheet/` 36→38, `science/fluid/**` 71→76, `runtime/fluid/` 49→50, `science/equipment/**` 97→100, `client/fx/**` 45→37, `science/column/operation/**` 11→12, `compat/create/**` 5→8); `--check` then exits 0. The README's module table has no count column: the counts are the numbers in parentheses after a path; the mapping is at the top of `map.mjs`. Not mapped and left alone: the `client/**` row ("89 ... besides two sub-trees": which other client sub-trees the 89 left out is unclear), and the Level 2 sentence "861 Java files at 3063488da" (not a table cell). The README holds no line counts; the script prints them. The thermo and column moves running now will move `science/fluid/**`, `science/material/`, `science/thermo/phase/**` and `science/column/v3/**`: run `--readme` after they merge.

**Layer matrix (imports; qualified references in brackets: code lines that name another layer's type by full name with no import).** Rows import from columns.

| from \ to | root | client | compat | material | network | registry | runtime | science | world |
|---|---|---|---|---|---|---|---|---|---|
| root | . | 0 [1] | 0 [1] | 0 [2] | 2 [8] | 4 [1] | 2 [19] | 4 [15] | 0 [3] |
| client | 34 [1] | . | 0 | 0 | 28 [8] | 7 [2] | 27 [29] | 477 [162] | 68 [25] |
| compat | 3 | 0 | . | 0 | 1 | 1 [1] | 1 | 2 | 6 [3] |
| material | 1 | 0 | 0 | . | 0 | 0 | 0 | 6 | 0 |
| mixin | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |
| network | 10 | 1 [20] | 0 | 0 | . | 0 [1] | 21 [35] | 63 [37] | 21 [7] |
| registry | 5 | 0 | 0 [1] | 0 | 0 | . | 1 [6] | 0 [15] | 6 [75] |
| runtime | 8 [8] | 0 | 0 | 0 | 1 [88] | 1 | . | 266 [556] | 35 [44] |
| science | 0 | 0 | 0 | 0 | 0 | 0 | 0 | . | 0 |
| world | 9 [2] | 1 [21] | 0 | 0 | 5 [3] | 31 [7] | 74 [60] | 105 [118] | . |

(`mixin` has no column: nothing references it.) The qualified references are about as many as the import lines between layers, so both tests and the script count both, with comments and strings stripped.

**LayeringTest rules (item 2), all with 0 violations today:** science free of runtime, world, network, client, registry, compat, mixin and the mod class; material free of runtime, world, network, client; runtime free of client; registry free of client; compat free of client. Each is a named `Rule` with a one-line reason; the class comment says a rule never leaves the test. A second test checks every rule names a layer that exists. Failure message: rule, reason, `file:line`, the line. The rules are inside the top-level layers only; nothing about `science/thermo`, `science/material`, `science/fluid/thermo` or `science/column`.

**Rules that do not hold yet** (in `map.mjs` `FORBIDDEN`, not in the test):
- runtime → network, 89 in 5 files: `FluidWorldAuthority` 83, `ProcessSolveServices` 2, `ControlPublication` 2, `InstrumentService` 1, `TutorialTriggers` 1.
- runtime → world, 79 in 19 files: `FluidWorldAuthority` 40, `ControlService` 5, `DataPorts` 5, `InstrumentService` 4, `InstrumentHosts` 3, `Sensor` 3, 2 each in `AssemblyService`, `ExchangerLines`, `ControlHosts`, `ShaftTrains`, `PowerNetwork`, `PowerPorts`, 1 each in `ColumnStarts`, `LevelGround`, `ControlPublication`, `FluidFacts`, `WorldTopologyLedger`, `AccessoryFacts`, `Instrument`.
- network → client, 21 in 7 files: `FluidNetwork` 11, `EquipmentNetwork` 4, `AssemblyNetwork` 2, `LineNetwork`, `RotationNetwork`, `SimulatorResults`, `TutorialNetwork` 1 each.
- world → client (CODEBASE_REVIEW 3.1, E17), 22 in 7 files: `FluidDeviceBlockEntity` 7, `EquipmentShellBlockEntity` 6, `FluidDeviceBlock` 3, `ControlPanelBlockEntity` 3, `EquipmentInfoItem`, `FieldGuideItem`, `EquipmentConstructorBlock` 1 each.

**FormatsTableTest (item 3).** Reflection (`Class.forName` on the full name found from the simple name under `src/main/java` and `src/test/java`, `getDeclaredField`, `setAccessible`): many constants are package-private. The table now names each constant as one `` `Class#FIELD` `` in its "Declared at" cell (21 cells, one sentence added to the intro; no number changed). Checked: 31 of 32 rows. 20 rows by field (25 fields, the four two-field rows split at ", "), the shape-field row by the test constant `EquipmentShapeCompatTest#SHAPE_FIELDS` (a comment says why), 9 inline protocol strings as literals in their files, and two digest tags (`icheme-fluid-checkpoint-envelope-1`, `icheme-certificate-graph-identity-10`). Not checked: "Native formulation token" (its value is a description; `NativeColumnSolverTest` checks it). A row that names nothing fails with its text; fewer than 25 checked rows fails as a changed layout. **Every stated value matches the code** (checked by a node pass over the source text, `tmp/j2/formats-sim.mjs`). Finding, not fixed: the six inline `FluidNetwork.java` line numbers are one line high (855, 862, 892, 898, 904, 908; now 854, 861, 891, 897, 903, 907). The test does not check line numbers.

**Stale texts (item 4).** `process-control.md` "formats are 14/4/3" (today 25/6/4) and `cooling-towers.md` `fluid-21` (today `fluid-24`) point to FORMATS.md. `equipment.md` `equipment-21` (today `equipment-23`) in four places: the read-this line, the class-map row, the wire section (its 16/17/18 history kept) and the exchanger paragraph. Its line 263 already names `equipment-23` correctly and is left as it is. `verification.md`: the `LegacyNetworkPathPinTest` bullet removed (the test was deleted in `41203a903`, aqueous solvents, with no successor), plus its two other mentions (the test-map row, the jdk-determinism line). The `891c8acf5` baseline sentence now says the baseline is the archived final lane of the code on `main` (`tools/merge-lane/README.md`).

**For the main thread.**
- Run: `./gradlew test --tests "*.LayeringTest" --tests "*.FormatsTableTest"`. Nothing else is touched in production or test code.
- Points where I was not sure it compiles or runs:
  - `(Iterable<Path>) stream::iterator` casts in both tests.
  - `FormatsTableTest` initializes classes such as `FluidNetwork`, `EquipmentNetwork` and `EquipmentShapeCompatTest` (its static fields build a `ColumnOperatingPoint`). Existing quick-suite codec tests already load these classes.
  - Both tests read `documentation/` and `src/main/java` relative to the project directory, as other tests do with `src/main/...`. If `affected.mjs` does not record file reads, a change to FORMATS.md alone will not select `FormatsTableTest`.
- Not done: the history line in the batch README and the INDEX row status (main thread's checks first); the class-map header per chapter (plan 2.4: not asked).
