# C2 Material free of the solvers: report

Batch C2 of `WAVE_C_BRIEFS.md` (subagent, Opus high), branch `worktree-agent-a40cf38e3d892505c` after a fast-forward to `claude/codebase-review-refactor-8a85ef` at `1ea6a2fed`. **Nothing compiled or run** (the brief: no Gradle, no JVM); every changed file was re-read and every changed symbol grepped in `src/main`, `src/test`, `src/fluidGameTest` and `src/mcpCompat`. Structure only: no result, format, lang or data file changes.

## Commits (in order)

| Item | Commit | What moved |
|---|---|---|
| 1 (F4) | `0cedca613` | `MaterialPresets.Column` holds plain records (`Specifications`, `Draw(tray, rate)`, `Steam(stage, rate, temperature)`, `Pumparound(returnTray, drawTray, duty, split name)`). New `science/column/v3/V3ColumnPresets`: `input(preset, catalog)` (the old `Column.input` arithmetic, statement for statement: mole fractions, volume per mole, rate, the same `V3ColumnInput` constructor call) and `matches(descriptor, input)` (no caller, kept as the brief asks). 42 calls in 26 files (2 in `src/main`: `ColumnTemplates`, `ProcessSimulatorScreen`) change their call, not their arguments; `V3MethaneColumnTest` reads `MaterialPresets.Draw::rate` for `V3SideDrawSpec::molarFlowMolPerSecond`. `equipmentGates` gains `science/column/v3/V3ColumnPresets*` (its helper `V3TestInputs.methaneCduInput`, read by `ColumnComponentsTest`, reaches the builder). |
| 2 (F5) | `36b0c9d83` | The column's check leaves `MaterialCatalog.parse` for `V3ColumnPresets.validate(catalog)`; `MaterialPresets.columnOrigins()` (id to file, in read order) lets it name the file. Called in `MaterialReloadListener.prepare` after `parse`, before `apply` publishes. |
| 3 (F7, L-F8) | `dd3ff0b5d` | `SolidMaterialCatalog.validate(SolidInventory)` becomes `SolidInventory.validate(SolidMaterialCatalog)` in `science/fluid/state`: same loop, same text. |
| 4 | `a1c330bd7` | Six `material-below-*` rules appended to `LayeringTest.PACKAGE_RULES`; the guide README's `column/v3` count 64 to 65 (`map.mjs --readme`). |
| 1 (follow-up) | `8046ac9e8` | A space after a comma in four call sites (the edit tool had trimmed it). |

Code guide in the same commits: `column-v3.md` (a `V3ColumnPresets` row), `thermo-materials.md` (the `MaterialPresets`, `SolidMaterialCatalog` and `MaterialReloadListener` rows, the flow diagram, main-flow steps 2 and 3), `fluid-science.md` (the `SolidInventory` row). No CHANGELOG line.

## Where a catalog becomes current, and the checks there

- **Data-pack reload** (`MaterialReloadListener.prepare`): `parse`, then `V3ColumnPresets.validate`. A throw in `prepare` aborts the reload before `apply`, as a parse refusal did: a refused preset still refuses the reload, and the last good catalog stays published.
- **Bundled catalog** (`MaterialCatalog.bundled()`, in `src/main` used only by `MaterialRuntime.active()` as the fallback before a publish: the client of a dedicated server, tools, tests): the column's check no longer runs when it loads; material cannot call the column. `MaterialPresetsTest.bundledColumnPresetsPassTheColumnCheck` (new) runs it on the bundled catalog, so a shipped preset the column refuses fails the quick suite. Not moved: an eager check at mod start (would load the bundled catalog at start-up; not asked for).
- **Lens and view catalogs** (`restrict`, `inferenceView`): built from a parsed catalog, never parsed; the check never ran on them.
- **Saved solids** (item 3): the check stays where it was called: `FluidPropertyReloadGuard.validateSolids` (world open) and `PhysicalRegistry`'s boundary initialisation; neither is a catalog load. The brief's "called where item 2's validation is called" does not apply: `SolidMaterialCatalog.parse` never ran it.

## Refusal texts and where they are raised now

Every text is unchanged and carries the same `<preset file>: ` prefix (`checked()` wrapped it at parse; `validate` wraps it the same way). No test pins any of them (grep); a player sees only Minecraft's generic reload failure, the text goes to the log.

| Text | Was raised | Now raised |
|---|---|---|
| `Condenser outlet temperature must be finite and positive`; `Organic reflux ratio ...` / `Reboiler duty must be finite and nonnegative` | parse (V3 record constructors) | `V3ColumnPresets.input`, run by `validate` |
| `V3 side draws require ...`, `V3 steam feeds require ...`, `V3 pumparounds require 1 <= returnTray <= drawTray; ...`, `V3 pumparound duty must be finite and nonzero` | parse | same |
| `No enum constant com.wormzjl.icheme.science.column.v3.V3PumparoundSpec.Split.<x>` | parse (`Split.valueOf`) | same (the name is kept as text in the preset) |
| `operating.columnDiameterMetres: not above 0 and at most 15.0 m` | parse (`MaterialPresets.read`) | same (needs the column's constant) |
| every `V3ColumnProblemResolver.validateInput` text | parse, after the presets | `validate` |
| `package/assay`, field errors, feed volume, `operating: outside package or column domain`, limits, network | parse | parse (unchanged) |

Order: one fault refuses with the same message. A pack with two faults, one the column's and one the parser's (a later preset, the network record, the water's ice), now names the parser's first. Not avoidable without the parser importing the column.

## Tests for the main thread

| Task | Classes (`--tests`) | Proves |
|---|---|---|
| `test` | `com.wormzjl.icheme.LayeringTest` | the six rules hold |
| `test` | `science.material.MaterialPresetsTest`, `MaterialCatalogTest`, `FluidAppearanceTest`, `ViscosityTest`, `MaterialFluidDataTest` | presets parse; the refusal through `validate`; the bundled check; other parse refusals unchanged |
| `test` | `runtime.fluid.SolidRuntimeTest`, `FluidPropertyReloadGuardTest`, `PhysicalRegistryTest`, `ColumnRunTest` | item 3; reload guard messages; `ColumnRunTest.DESIGN` |
| `test` | `client.gui.screens.inventory.V3ClientPresetsTest`, `ColumnTemplatesTest`, `client.gui.flowsheet.SimulatorRunnerTest`, `SimulatorColumnsTest`, `ColumnBlockTest`, `FlowsheetExamplesTest`, `network.EquipmentNetworkCodecTest`, `science.flowsheet.FlowsheetColumnDoorTest`, `FlowsheetFileTest`, `SheetCompoundsTest`, `FlowsheetAqueousColumnTest`, `science.fluid.network.PipeTransferTest`, `science.plant.column.ColumnSolveTest` | callers: the input equal to before |
| `columnGates` | `V3ColumnFeedAndDrawLimitsTest`, `V3GradePresetCarryOverTest`, `V3MethaneColumnTest`, `V3LiteraturePresetTest`, `V3TrayHydraulicsColumnTest` | the preset inputs and `MaterialPresets.Draw::rate` |
| `equipmentGates` | `science.equipment.ColumnComponentsTest` | the new `build.gradle` line |
| `holdGates` | `science.flowsheet.FlowsheetColumnTest`, `ColumnEjectorTest` | the two changed slow callers |
| `compileFluidGameTestJava` (with `test`) | `ColumnFailureModesReviewWorld`, `ColumnOperationReviewWorld`, `ProcessSimulatorGameTests`, `TutorialUnitGameTests` | the GameTest sources compile |

`MaterialPresetsTest.allFourInputsRemainTheQualificationInputs` pins the built inputs to 1e-12 against a fixture; the arithmetic is the same, so they are expected bit for bit. No reference moves.

## Unsure points

- Compile: `V3ColumnPresets.validate`'s `MaterialRuntime.with(..., () -> { ...; return null; })` (Supplier inferred as `Object`, as the old call in `MaterialCatalog`); `assertDoesNotThrow(() -> V3ColumnPresets.validate(bundled))` (void, so `Executable`); the new record names `Steam`, `Draw` inside `MaterialPresets` (no clash with `com.google.gson.*` or `java.util.*` found).
- `SolidMaterialCatalog`'s doc `{@link SolidInventory}` became `{@code}` (the import is gone); no doclint in `build.gradle`.
- Merges: the closing `));` of `PACKAGE_RULES`, the README's `column/v3` count (re-run `map.mjs --readme`) and `equipmentGates`' list in `build.gradle` may meet the other wave-C branches.
- Not done here: the batch README history line (the main thread writes it).
