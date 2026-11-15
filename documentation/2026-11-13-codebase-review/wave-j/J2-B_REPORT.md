# J2-B report: client-gui, equipment and verification chapters to the template

Scope J2-B of [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), section J2, on `claude/codebase-review-refactor-8a85ef` at `d6f721b94`. Documents only: no Gradle, no JVM. Commits: `50de02760` (client-gui), `a69f7913c` (equipment), `c801815d4` (verification), and this report.

## Chapters made

| Old chapter (KB) | New chapter | KB | Covers |
|---|---|---|---|
| `client-gui.md` (133, removed) | `client-kit.md` | 17.6 | `client/gui/common/kit/**`, the shared frame and widgets (`FluidGuiLayout`, `TitleStrip`, `NameEntry`, `ProcessUi`, `DisplayUnit`, …), `ClientConfig`, `IChemEClient`, `src/mcpCompat` |
| | `client-screens.md` | 16.8 | `FluidDeviceScreen` with `DeviceColumn`, `DeviceMeters`, the converter's and still's doors and drafts, the heat Control tab, `MachineScreen`, the gauge and pipe tool dialogs, `ControlPanelScreen` |
| | `client-equipment-screens.md` | 15.3 | Constructor, datasheet, assembly and Operation page, `CalculatorColumn`, templates and `V3EditorDraft` (the simulator's screen: a pointer to `process-simulator.md`) |
| | `client-rendering.md` | 13.0 | `client/` root renderers and `client/model/**`; sounds, valve turns and the laying tools listed with pointers to `effects.md`, `pipe-laying.md`, `machine-assemblies.md` |
| `equipment.md` (131, removed) | `equipment-geometry.md` | 16.0 | `EquipmentShape`, `ShapeRasterizer`, `Mesh`, nozzles, paint, ports, `HeatSides`, names, pipes, fittings, instruments, service housings |
| | `equipment-designs.md` | 16.1 | `UnitDesign`, `Sizes`, `ProcessUnit`, `design/UnitRatings`, `MachineDuty`, columns, storage, every kind's design file |
| | `equipment-structure.md` | 18.9 | `EquipmentStructure`, `StructureRegistration`, `StructureRefresh`, `StructureRealignment`, `Positions`, the blocks and block entities, `ShapeRepair`, `EquipmentRefresh`, `EquipmentNetwork`, `EquipmentChunkMixin`, `MachineRatings` |
| `verification.md` (120, kept) | `verification.md` | 13.6 | Gradle tasks, gate groups, `tools/merge-lane/` (every file in its class map), which check decides what |
| | `verification-tests.md` | 13.1 | The test map by package, the structural gates (`LayeringTest`, `FormatsTableTest`, `EngineDigestTest`, `DeterministicMathSourceTest`), the corpus with each folder's reader, the GameTest classes |
| | `verification-campaigns.md` | 9.6 | chain-100, printed and junction lines, hold fuzz, quick check, campaign, classifier and hold classes, determinism |

Each chapter: Read this when, What it does, Class map (lines per class), Main flow, Contracts, Laws and constants, Formats (a link to `FORMATS.md`), Tests and gates, Traps, Snapshot. No lane result is named as current.

## Moved to HISTORY.md

One block at its end, `## From the chapters of scope J2-B`, 247 lines (59 client-gui, 40 equipment, 148 verification): a list of the history sentences cut from tables, then the batch-appended sections and dated paragraphs as they stood (their `##` headings demoted to `####`). No other part of `HISTORY.md` changed (the diff of each commit adds lines at the end only).

## Corrected against the code (main cases)

- The final lane: the old text called `lane.sh` a git-ignored per-batch script running the full `test`; it is the tracked `tools/merge-lane/lane.sh` with the load-set selection of `affected.mjs`.
- Protocol numbers (`equipment-23`, `fluid-24`, `mover-4`, `assembly-6`, …) replaced by `NetworkProtocol.VERSION` and the `FORMATS.md` rows.
- Shape fields: `EquipmentShapeCompatTest.SHAPE_FIELDS` is 12 (the chapter said 8); the corpus runs `shapes-1` to `shapes-12`.
- `ShapeRepair`'s alias maps: `KIND_ALIASES` and `FORM_ALIASES` hold `CONDENSING_TURBINE` (one passage said all empty).
- `ConstructorColumn.TITLE` is 100 (the chapter gave 122 in two places); `ConstructorPayload` now carries the tower bank, the duty, the exchanger type and the still options; `Deconstruct` and `DeconstructAnswer` added.
- `fluidSolverRegression`'s `relative` mode reads as `declared`.
- The `minecraft-mcp` skill is not in this tree's `.claude/skills/`.
- Dated counts dropped (test counts, layout-table rows, GameTest totals, lang key counts); the corpus table names each folder's reader as the sources do (`CrossflowEquipmentTest` reads `compat/constructor/` and `crossflow-pipes.txt`, `LineContractTest` `compat/line-pieces/`, `DcsConsoleGameTests` `compat/control-blocks/`).
- One invented constant of my own draft (`PrimeMoverDesign.STACK_SPEED`) was caught by the constant check and cut.

## Checks (scripts in the worktree's `tmp/j2b/`, git-ignored)

- `node tmp/j2b/check.mjs`: per chapter size, links and anchors, every backticked class (and `Class.member`, NBT literal and ALL_CAPS constant) resolved in the sources, every module file in the class map, each row's line count equal to the file's. Result: 10 chapters, 152 links, 1,259 identifiers, 0 failures; all under 40 KB (largest 18.9 KB).
- `node tmp/j2b/union.mjs`: 221 files in scope (`client/**` but `fx`, `gui/flowsheet`, `gui/tutorial`; `science/equipment/**`; the equipment files of `world/level/block/**`), 0 missing from the class maps.
- `node tools/code-guide/map.mjs --check`: exit 0 (README counts current).
- `node tmp/j2b/links.mjs [--all]`: the links to the old names, each with a proposed target.

## For the main thread (not edited here)

README routing rows and code map (proposed targets):
- Code map rows: `science/equipment/**` → [equipment-geometry.md], [equipment-designs.md], [equipment-structure.md] (`PipeRoute`, `RackGeometry`: pipe-laying.md); `world/.../Equipment*`, `network/EquipmentNetwork` → [equipment-structure.md]; `client/**` → [client-kit.md], [client-screens.md], [client-equipment-screens.md], [client-rendering.md]; `build.gradle`, `src/test`, … → [verification.md], [verification-tests.md], [verification-campaigns.md].
- Routing: "A vessel, column or unit looks, collides …" → equipment-geometry.md (shape, collision, nozzles), equipment-designs.md (a kind, a column's design), equipment-structure.md (constructor, after an update); screens client-equipment-screens.md, models client-rendering.md. "A world's fluid network loads … corpus gates" → verification-tests.md. "comes back by itself after a chunk load" → equipment-structure.md. "Check parts … the column's design" → equipment-designs.md. "Screen layout, units, dropdowns, presets" → client-kit.md (presets: client-equipment-screens.md). "Which test or gate proves my change; preparing a merge" → verification.md. Level 1 "Equipment" paragraph → equipment-structure.md.
- Reading path "Equipment" stays valid.

Links to retarget, live documents (`node tmp/j2b/links.mjs`, 146 occurrences; the README counted above): `.claude/skills/gui-creation/references/recipes.md:174` client-gui → client-screens; chapters `column-operation.md:45,230` → client-equipment-screens; `column-v3.md:10,225,227,241,271` → equipment-designs, `:275` → client-equipment-screens (the column door); `cooling-towers.md:43` → equipment-structure (Import and names); `fluid-runtime.md:15,40,129,132,265,441,456,498` → equipment-structure, `:470` → equipment-designs, `:20,40` → client-screens, `:33` → client-kit, `:159` → client-rendering; `fluid-science.md:156` → equipment-structure; `heat-exchangers.md:15,68` → equipment-geometry (`HeatSides`), `:15,48,131,245` → client-screens; `instrument-accessories.md:11,28,72` → equipment-geometry, `:11,81` → client-rendering, `:26` → client-screens; `machine-assemblies.md:16,215` → equipment-designs, `:48` → equipment-structure and client-equipment-screens, `:278` → equipment-geometry (`PortMarkings`); `pipe-laying.md:3` → equipment-geometry and client-rendering; `process-simulator.md:12,177,178` → client-kit (the kit, `Canvas.batch`, `HeldDraws`), `:127` → equipment-designs; `rotating-machinery.md:14` → equipment-designs and client-screens, `:132,178` → equipment-structure; `thermo-materials.md:318` → equipment-designs; `wired-control-power.md:57` → client-equipment-screens (`OperationColumn`); links to `verification.md` (`equilibrium-engine.md:499`, `fluid-science.md:422`, `rotating-machinery.md:118`, `thermo-materials.md:57`) stand, or → verification-tests.md where they mean a test class. No link uses an anchor of the three old chapters.
- The review batch's own documents (`BRIEFS.md`, `INVENTORY.md`, `README.md`, `WAVE_D_BRIEFS.md`, `wave-d/*`, `wave-j/J2_REPORT.md`) and 60 older batch folders (240 occurrences in 72 files with `--all`) name the old chapters as records; they may stay.

Stale texts outside the scope: the `gui-creation` skill's description and its kit section still name "the column calculator" as a screen (it is the simulator's column door, `CalculatorColumn`); none of the plan 2.5 items lay in these three chapters.

## Left

The main thread's integration above; the README's `client/**` row stays unmapped by `map.mjs` (its counts follow no form).
