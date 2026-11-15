# J2-C report: six code guide chapters to the template, under 40 KB each

Scope J2-C of WAVE_D_BRIEFS "J2", on `claude/codebase-review-refactor-8a85ef` at `d6f721b94`. Documents only: no file
under `src/`, `tools/`, `build.gradle` or `.claude/` changed; no Gradle, JVM or cargo run. One commit per old chapter
(`00cfd7d29`, `759ffb5a6`, `d96cc9c53`, `94c567cfa`, `4567374fc`, `62da2d807`) and this report.

## Chapters made (bytes with CRLF; packages covered)

| Old chapter (KB) | New chapters | Covers |
|---|---|---|
| `thermo-materials.md` (84, removed) | `materials.md` 30.7, `thermo-kernel.md` 23.2 | `science/material/**`, `material/`; `science/thermo/*.java`, `science/thermo/water/**`, `science/math/**` |
| `equilibrium-engine.md` (64) | `equilibrium-engine.md` 28.0, `equilibrium-crystals-searches.md` 14.6 | `science/thermo/phase/**` (request, contract, result, dry core, flashes, carried crystals; crystals, onsets, PH/UV searches, balance steps) |
| `column-v3.md` (68) | `column-v3.md` 32.4, `column-v3-solver.md` 21.3 | the calculator's route, seeds, audit, wire and workers; the problem, ledger, residual, Jacobian, Newton, band LU, `v3/thermo`, `src/main/rust`, `science/column/still` |
| `column-operation.md` (65) | `column-operation.md` 18.0, `column-run.md` 26.6 | `science/plant/column/**`, `science/plant/port/**`; `runtime/fluid/ColumnOperation`, `ColumnUnit`, `ColumnFacts`, `ColumnPower`, `ColumnStarts` |
| `process-simulator.md` (64) | `process-simulator.md` 20.4, `simulator-units.md` 15.5, `simulator-sizing.md` 17.1, `simulator-screen.md` 17.1 | `science/flowsheet/*.java` with the block and packets; `unit/**`; `sizing/**`; `client/gui/flowsheet/**` and the screen |
| `heat-exchangers.md` (57) | `heat-exchangers.md` 20.0, `heat-units.md` 19.7 | the heat laws, `HeatCurve`, `ExchangerRelay`, `HeatControl`, `Pool`, crossflow, `HeatSides`, `ExchangerType`; `HeatUnit`, `ExchangerCoupling`, `HeatPresentation`, the lines, the client's `Heat*` |

`HISTORY.md`: one block at its end, `## From the chapters of scope J2-C` (lines 541 to 621, 81 lines), six sub-blocks of
kept text: moves and splits (C1b, S01, D-C1..D-C4, D-R2, D-S6, C4a, C4b), removed rules and keys, owner and batch
provenance, format numbers at earlier snapshots, measured costs, test counts, and the stale names found.

## Corrected against the code (main cases)

- The PR78 kernel has one mixing rule (Huron-Vidal, `HuronVidalExcess`); the old chapter's three `Mixing` plans do not
  exist. `RootCoalescence` and `ThermoRefusal` (D-E6) are documented as the kernel's refusals.
- `HeatCurve` bisects a boundary to `BOUNDARY_KELVIN` (1e-3 K, L-F11), not 1e-5 K.
- `Equations.sideHeat` → `StepEquations.sideHeat`; the coupling's section of `IslandCoordinator` → `ExchangerCoupling`;
  `ColumnOperation.waterSides` → `ColumnUnit.condensers` read through `heatRead`; `UnitRatings.RADIANT_FLUX`'s owner.
- `V3ColumnStreamProperties.MAX_STREAMS` is 4 plus `MAX_SIDE_DRAWS` (the old text added them again).
- Stale names cut: `UnitSizing`, `SizingChoice` (crossflow sizing is `ExchangerSizing` over `ExchangerType.CATALOG`),
  `PilotSolventTestCatalog`, `FlowsheetEngine.rate` (rating is `SheetSizing.rate` from the runner), `WindowScale`,
  the bootstrap link to `CreateChemE.java` (now `IChemE.java`), `HydrocarbonModel.idealGasFitRange`.
- New files mapped: `ColumnFacts` (D-R5), `ProcessSolveJobs` (D-R2), `SmallDenseSolve`, `PhaseTraits` (I-1),
  `CrossflowHeat`, `HeatRouting`, `ExchangerType`, `ExhaustCoil`, `ColumnWater`, `Members`, the still's column.
- Format numbers replaced by the constant's name and a link to `FORMATS.md`.

## Checks (scripts in `tmp/j2c/`, run from the repository root)

- `check.mjs` (size under 40,000 bytes; every relative link and anchor resolves; every backticked class-like
  identifier resolves to a type in `src/` or `tools/`, and for `A.b` the member `b` appears in `A`'s file; with `--map`,
  every file of the named packages appears in a class map): 14 chapters, 0 problems; 468 links; 4,477 code spans
  scanned; class maps cover 334 files (material 28 + 2, thermo 14 + 8 + 4, phase 30, column 110, plant 12 + 1,
  flowsheet 87, client flowsheet 38), 0 missing. A skip list in the script holds NBT keys, JDK types and names.
- `files.mjs` (files, lines and first javadoc sentence per package); `finish.mjs` (CRLF, the HISTORY block).
- `node tools/code-guide/map.mjs --check`: exit 0 (README untouched).

## Left for the main thread

- README routing rows and reading paths, the retargeted links and anchors: listed in this scope's hand-back message.
- Two packages changing now, described from the code at `d6f721b94`: `science/plant/column` with
  `science/flowsheet/unit/ColumnUnit` (H-C5: `column-operation.md` Traps and Main flow, `simulator-units.md` Contracts,
  `column-v3.md` Main flow step 1) and `runtime/fluid/ColumnOperation`'s waits and wakes (R-ST4: `column-run.md`
  "The fence" and Traps, `heat-units.md` Traps).
- `still/StillColumn` is mapped in `column-v3-solver.md` and documented in `fluid-science.md` (J2-A's scope).
