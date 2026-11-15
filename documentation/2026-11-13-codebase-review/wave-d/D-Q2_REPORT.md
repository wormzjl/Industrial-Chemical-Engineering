# D-Q2 The kind drawings into their design files (E1): report

Batch D-Q2 of `WAVE_D_BRIEFS.md` (subagent, Opus high), branch `worktree-agent-acfb3792ceeb1c33d` after a fast-forward to `claude/codebase-review-refactor-8a85ef` at `a91f6f9fa`. Ten code commits, one per kind (the two fired heaters as a pair), then this report with the code guide. **Nothing compiled or run** (the brief: no Gradle, no JVM). Structure only: no arithmetic, statement order, saved name, enum constant, lang key or nozzle changed; no cell can move.

**Summary.** All eleven drawings left `ProcessUnit` (4,717 → 3,098 lines, 1,619 lines out). Each drawing is now its design's `build(ProcessUnit u)`, the way `CycloneDesign` and `StirredReactorDesign` hold theirs; the chimney's two forms and the pump's BB2 are private statics its `build` calls. Helpers only one kind uses moved with it as `private static` methods taking the unit first; helpers two kinds use stayed in the kit. Nothing stayed for a blocker.

**How it was moved.** `tmp/d-q2/move.mjs` (worktree scratch, not committed) tokenises the kit. For each moved member it copies the source text byte for byte and inserts only qualifiers:
- `u.` before the unit's fields and instance parts;
- `ProcessUnit.` before the kit's statics; `Mesh.` before `Mesh`'s statics (the kit reached them through `import static Mesh.*`);
- `u` as the first argument of a moved helper; `this` becomes `u`.

Nested types (`Solid`, `Motion`, `Ring`, `Breeching`, `FanStack`, `Compressor`, `Mesh.Frame`, `Mesh.Material`) and the kit's imports used by the moved code are single-type imports in the design. A local that has the name of a kit field stays unqualified. Each such case was read by hand: `top` (a private kit field) is a local or a parameter in every one; `shape` is `StillPlan.of`'s parameter. A call with a static and an instance overload resolves as javac's first phase does (`header(length)` is the static one).

Checks run on the committed result, all by `node` scripts in `tmp/d-q2/`:
1. `ordercheck.mjs`: every moved member's body in the kit at `a91f6f9fa` equals the body that now holds it, line for line and in order, after the qualifiers are removed. 36 members, 1,268 body lines, all identical.
2. `verify.mjs --range "a91f6f9fa HEAD"`: every line removed from any file is among the added lines after normalising. The only exceptions are the ten `build` signatures and delegations, ten closing braces, five emptied section headers, the javadoc links edited below and the new imports. Braces, parentheses and brackets balance in all 16 changed files.
3. `refcheck.mjs`: every `u.X`, `ProcessUnit.X` and `Mesh.X` in the 11 designs and in `ShapeRasterizer` names a member of that class that is not private, and is static where reached through the class.
4. Greps over `src/main`, `src/test`, `src/fluidGameTest`, `src/mcpCompat` and `tools/**/*.java`, for each moved name (`.name(`, `::name`, `ProcessUnit.name`, `#name`). The only hits left are comments and the kit copies in `tools/pressure-valve-accessory/integration/` and `tools/mover-equipment-arrows/baseline/` (not compiled). The drawing tools that `tools/equipment-drawings/export.init.gradle` compiles (`EngineeringExport`, `ProposalMeshes`, `ServiceRender`, `pump-sprites/probes/PumpRender`) and `tools/heat-gui-renders` name none of them.
5. No changed design names another design's file in its code (the `ProcessUnitTest.designsShareOnlyTheKit` rule, re-run as a script). Every import in the changed designs is used, and no simple name is imported twice. The kit's imports are all still used. No private member of the kit is left unused.
6. Every changed file is still CRLF, with no "no newline" change. `git diff --stat` shows only these files.

## Per kind

| Commit | Kind | Lines out of the kit | Moved | Kit pieces it reads that stay (shared) |
|---|---|---|---|---|
| `468338e69` | exchanger | 48 | `exchanger` → `ExchangerDesign.build` | `centre`, `sides`, `radial`, `round`, `saddle`, `saddleAt`, `saddleWidth` (the kettle and others) |
| `5ac256aa2` | kettle | 54 | `kettle` → `KettleDesign.build` | the same; `NECK`, `fluidPoint` |
| `875997e4b` | air cooler | 144 | `airCooler` → `AirCoolerDesign.build`; `plenum`, `RING_SIDES`, `spool` | `transition` (pump, stacks, cyclone), `manifold` (cabin heater), `header(double)`, `RING_OD`, `FanStack` (`UnitRatings`), `segment`, `box` |
| `13c3beb9e` | ejector | 82 | `ejector` → `EjectorDesign.build` | `POST`, `SMALL_EJECTOR`, `radialSide`, `box`, `round`; `ejectorLength` (datasheet, sizing) |
| `e19be1785` | pump (OH2, BB2) | 227 | `pump` → `PumpDesign.build`; `betweenBearingsPump`, `impeller`, `pumpShaft` | `pumpNozzle`, `baseplate`, `bracket`, `extrude`, `shaft`, `coupling`, `collar`, `shown`, `shaftRadius` (other machines); `scroll` (`FluidizedBedParts`, `GrateBoilerDesign`); `pumpSuction`, `OH2_MAX` (sizing, `PumpDesign`'s checks, `ProcessUnitTest`) |
| `0dd93cde1` | compressor | 230 | `compressor` → `CompressorDesign.build`; `distancePiece`, `steelTable`, `bottle` | `Compressor` record, `MAX_THROWS` (`UnitRatings`, the design's sizes, `ProcessUnitTest`); `plate`, `post`, `header(...)`, `baseplate`, `coupling`, `couplingHubRadius` |
| `a7cc74681` | vertical heater | about 146 | `verticalHeater` → `VerticalHeaterDesign.build`; `girder` | see the pair's row below |
| `a7cc74681` | cabin heater | about 207 | `cabinHeater` → `CabinHeaterDesign.build`; `AISLE` | the pair shares `casing`, `legDepth`, `leg`, `seam`, `knees`, `brace`, `door`, `burners`, `convection`, `panels`, `stack`, `stackTop`, `mouth`, `HEADER_BOX`, `MAX_STACK`, `BURNER_DROP`, `BRACE_TIER`, `GIRDER_HALF`, `Breeching`; `MAX_RADIANT_HEIGHT`, `MAX_CABIN_CELL`, `cabinCells`, `cabinWall` (the designs' sizes, `UnitRatings`) |
| `58ee9fa00` | flare stack | 43 (comment move inside the kit: 1) | `flareStack` → `FlareStackDesign.build`; `FLARE_PLINTH`, `FLARE_INLET` | `stack`, `mouth`, `radialSide` |
| `93569d0e0` | chimneys | 78 | `steelStack`, `brickChimney` (private statics, `build` calls them by form as before); `STACK_PLINTH`, `CHIMNEY_WALL`, `CHIMNEY_TAPER` | `stack`, `mouth`, `ringAccess`, `platform`, `door`, `band`, `fluidPoint` |
| `858cfaafe` | batch still | 359 | `batchStill` → `BatchStillDesign.build`; `StillPlan` (moved verbatim as a nested record); `stillSetting`, `stillSkirt`, `stillCharge`, `stillDrain`, `stillHelmet`, `stillCondenser`, `stillVapour`, `stillColumn`, `sightGlass` | `mouth` and `mark` (heaters, flare, chimneys, cooling tower, prime movers), `skirt`, `baseRing`, `pipe`, `line`, `post`, `box`, `round`, `outward`, `end`, `serviceHousing` |

Sum: 1,623 lines left the kit and 4 came in (the two visibility lines, the stackTop javadoc, one link), net −1,619. The designs gained 1,676 lines and lost 33. The added lines are mostly the qualifiers' line copies; the rest are imports and the helpers' signatures.

**Visibility changes**, all in the kit and all to package-private:
- `couplingHubRadius` (read by the kit's `coupling` and by the compressor);
- `GIRDER_HALF` (read by the vertical heater's `girder` and by the cabin heater).

No member became public. All designs share the kit's package, `science/equipment`, so no other visibility was needed. Moved instance helpers became `private static` in their design. Moved constants and `StillPlan` keep their package-private modifiers.

**Other edits (comments and qualifiers only):**
- `ShapeRasterizer.stillParts` reads `BatchStillDesign.StillPlan` (7 qualifiers and one javadoc link).
- One-word comment updates: `EquipmentStructure` line 732 (`world`, a qualifier in a comment) and `StillTray` line 25.
- The javadoc links to a moved drawing point to its new place: the 11 designs' class docs, `HeatSides` line 127, and `ProcessUnit` line 177 (`PumpDesign#betweenBearingsPump`). Links inside moved javadoc to members that stay are now `ProcessUnit#…`.
- Five kit section headers that the moves emptied are removed: exchanger, kettle, ejector, reciprocating compressor (its `shaft` and `collar` now sit under the pump's section) and chimneys. The batch still header is removed too: `mouth` and `mark` remain under it, so they now sit under the fired heaters' section.
- A pre-existing misplaced javadoc of `stackTop` stood over the flare's javadoc. It moved onto `stackTop`, and the flare's javadoc onto `flareStack` (comments only, in commit `58ee9fa00`).

**What stayed and why.** No drawing stayed: none needed a primitive made public across packages, and none forms a cycle. These kit members only one kind reads stay on purpose:
- `pumpSuction` and `OH2_MAX`: sizes read by sizing, by `PumpDesign`'s checks and by `ProcessUnitTest`.
- `ejectorLength`: read by the datasheet and sizing.
- `MAX_RADIANT_HEIGHT`, `MAX_CABIN_CELL`, `cabinCells`, `cabinWall`: the designs' size rules and `UnitRatings`.
- the `Compressor` record and `MAX_THROWS`: `UnitRatings` and the tests read them.

They are sizes, not drawing helpers. Moving them would change test and sizing call sites, which is outside this slice.

**`build.gradle`:** no line needed. `equipmentGates` takes `science/equipment/**`. The moved code reaches nothing the kit did not already reach: `science/math`, and `science/fluid/network/Flare*` and `Chimney*` by their fully qualified names. No new file, so `.gitignore`'s `design/` rule does not apply. `node tools/code-guide/map.mjs --readme`: the counts are unchanged, and `--check` passes.

**Code guide:** `equipment.md`, in the rows for `ProcessUnit`, the machines and the `*Design` files, and in the lines on `stillParts`, the chimney, the flare and the still. `HISTORY.md` is history and keeps its old names.

## Tests for the main thread

| Task | Classes | What it proves |
|---|---|---|
| `compileJava`, `compileTestJava`, `compileFluidGameTestJava`, `compileMcpCompatJava` | all | The qualifiers, the imports, the two visibility changes, `BatchStillDesign.StillPlan` |
| `equipmentGates --tests` | `EquipmentLayoutGoldenTest` | Every kind's layout table at every size, bit for bit: the drawings place the same cells in the same order |
| `equipmentGates --tests` | `ShapeRasterizerTest` | Cells, facet areas, nozzles on block faces, a heat unit's and the kettle's nodes (`fluidPoint`), bit for bit |
| `equipmentGates --tests` | `ProcessUnitTest` | Every size of every kind built, `designsShareOnlyTheKit` (no design names another's file), moving parts, decks and ladders walked, the still's fuel bore |
| `equipmentGates --tests` | `HeatSidesTest`, `CrossflowEquipmentTest`, `EffectMarksTest`, `EquipmentServicesTest`, `EquipmentShapeCompatTest`, `MachineRpmDesignTest`, `PortMarkingsTest` | Heat sides' surfaces, the marks (`flare_tip`, `stack_top_<i>`, the still's), the service boxes on the moved drawings, the compat corpus through the refresh, the machine designs' classes, the pump's and ejector's arrows |
| `test --tests` | `FlowsheetPaletteCoverageTest`, `SheetSizingTest`, `UnitSizingTest`, `CoolerSizingTest`, `StillSizingTest`, `TurbineSizingTest`, `CrossflowSizingTest`, `LimitTest`, `SizingCoverageTest`, `SizingPageTest` | The designs' sizes and the sizing ratings are unchanged (the design classes were edited) |
| `test --tests` | `StillModelTest`, `StillInsideFilmTest`, `StillRunFilmTest`, `TowerFilmsTest` | The still's model and films read `ShapeRasterizer.stillParts`, now on `BatchStillDesign.StillPlan` |
| `test --tests` | `LayeringTest`, `DeterministicMathSourceTest` | The designs gained no import across a layer; the moved code calls no banned `Math` function (moved unchanged) |

`UnitTextTest` and `LanguageFilesTest`: not needed, because no lang key changed (AGENTS.md leaves them for the translation commit). Fluid GameTests: not needed for this slice. Placement reads the same shapes and cells; `EquipmentLayoutGoldenTest` decides.

## Where compiling is not certain

Every point below was read, not compiled. None is a known error.

1. `AirCoolerDesign.java` line 129: `ProcessUnit.header(length)` with one argument. The kit has a static `header(double)` and an instance `header(double, double[]...)`. javac's first phase (no varargs) picks the static one, as the kit's own unqualified call did. D-Q1's `UnitRatings` makes the same call.
2. `CompressorDesign.java` lines 250 and 253, the two `u.header(…)` calls in `build`, each with five arguments: the instance varargs overload, as in the kit.
3. `BatchStillDesign.java` line 188: `StillPlan` is now a nested record of a class that extends `UnitDesign`. Its body names only its own members, its locals, `BatchStillDesign.potDiameter` (qualified, as before), `PipeSizes`, `Math`, `EquipmentShape`, and `Material` (now a single-type import). So no name resolves to `BatchStillDesign` or `UnitDesign` that resolved to `ProcessUnit` before. `ShapeRasterizer` (same package) reads it as `BatchStillDesign.StillPlan`. Both classes are package-private, as `ProcessUnit.StillPlan` was.
4. `ChimneyDesign.java` line 102, `build`: it calls the private statics `steelStack(u)` and `brickChimney(u)` from an instance method, the same calls as before with the unit as an argument.
5. `FlareStackDesign.java` line 80: the fully qualified `com.wormzjl.icheme.science.fluid.network.Flare.stackHeight` beside the file's own `import …Flare`. Both name the same class.
6. Single-type imports of the kit's package-private nested types (`ProcessUnit.Solid`, `Ring`, `Breeching`, `Compressor`) into same-package designs. `CompressorDesign` already wrote `ProcessUnit.Compressor.of` qualified; that still compiles beside the import.
7. Fields assigned through the unit (`u.fluidPoint`, `u.terminal`, `u.serviceHousing`) are non-final in the kit (lines 506, 571, 575).
