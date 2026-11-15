# J2-I report: the code guide's integration after the J2 split

Scope J2-I (documents only; no Gradle, no JVM, no file under `src/` changed). Branch fast-forwarded to
`claude/codebase-review-refactor-8a85ef` at `9d6d0d82e`. Commits, one per step:

| Step | Commit | What |
|---|---|---|
| 1 | `bd74aaaac` | README: code map rows, routing table and reading paths on the split chapters; nothing dated |
| 2 | `536a93cd4` | Every link to the five removed chapters retargeted by topic |
| 3 | `c39b680f7` | The lines H-C5 and R-ST4 made wrong |
| 4 | `f362fc5e7` | `machine-assemblies.md` and `rotating-machinery.md` split and to the template |
| 5 | `c4c4053fb` | The seven small chapters cut of history, format numbers and stale names |
| 6 | `e8b0b03ed` | `batch-still.md` |

## README

138 lines, 31.1 KB; `node tools/code-guide/map.mjs --readme` then `--check`: counts current, no unmapped row. The code
map has one row per chapter (or per module of a chapter), in the forms `map.mjs` reads; rows that share a package say
"(the same)". The reading paths name `ColumnSolve.calculate` with each caller's cold start. Removed as dated: the Create
and NeoForge versions, "since batch …", the file totals at a commit, the snapshot line.

## Links (checker `tmp/j2i/links.mjs`: every relative link of the live documents resolves to a file, every `#anchor` to a heading; `research/` resolved in the main checkout)

- Step 2: 75 in the guide's chapters, 76 in `HISTORY.md` (each to the chapter that now holds the topic), 4 in
  `documentation/2026-11-02-process-simulator/README.md` (line 160 `#ports-and-passages` → `#contracts`; the neighbouring
  161 and 166 `client-gui.md` → `client-kit.md`, `simulator-screen.md`; 179 `equipment.md` → `equipment-geometry.md`), the
  `gui-creation` recipe's chapter name. None in `AGENTS.md`, `documentation/reference/*.md`, `documentation/README.md`,
  `INDEX.md` files or `tools/**/README.md`. `process-control.md`'s omitted-trace sentence (stale since W3b) was cut, not
  retargeted.
- Step 4: 21 links into the split chapters retargeted to `assembly-service.md` or `shaft-trains.md`; step 6: 5 to
  `batch-still.md`.
- Result: 293 files, 2,097 relative links, **0 dangling links, 0 dangling anchors**.
- Left as records: plain mentions of the old names in `documentation/reference/README.md` lines 21 and 23 (history
  lines), batch folders and `CHANGELOG.md`.

## The two code changes

- **H-C5** (`ColumnSolve.calculate(input, seed, catalog, screen, ColdStart, checkpoint)`, `warm`, `closure`):
  `column-operation.md` (class map row, Main flow step 1, the closure in Contracts, Traps: the two cold starts and the
  sheet's feed water), `simulator-units.md` (class map: `ColumnUnit.SCREEN`, `ColumnBlockSolve`'s closure; Main flow 2 to
  4; Contracts; Trap: `Report.Start.LAST` on a wet seed), `column-v3.md` (the one entry and two callers; Main flow step 1;
  "every solve at the exact closure" corrected: `OFF_FAST` for an `AGGRESSIVE` request with no seed),
  `simulator-sizing.md` (`ColumnSizing`), README reading paths.
- **R-ST4**: `column-run.md` (the fence: `through` to the tick asked, the dated wakes through `movedAt`, `higherKey`, the
  held solve `Run.heldJob`/`resend`; Traps), `heat-units.md` (the fence a pure read with `wokenBefore`, `reads` to the
  slice start, `ExchangerCoupling.dated` and `flushWakes`; Traps), `runtime-engine.md` (certified island, coupled
  islands, a running column), `heat-exchangers.md` (Main flow step 1), `runtime-units.md` (the still's trap, now true of
  the column and the coupling).

## Chapters made or cut down (bytes, CRLF)

| Chapter | KB | Covers |
|---|---|---|
| `machine-assemblies.md` | 19.5 | the check: `science/assembly`, `AssemblyWalker`, `LevelGround` |
| `assembly-service.md` (new) | 22.5 | `runtime/assembly` service, store, presentation, watch, Start, lines; `AssemblyNetwork`, menu, screen, client |
| `rotating-machinery.md` | 18.3 | the laws, `science/machine` |
| `shaft-trains.md` (new) | 21.4 | `ShaftPlan`, `ShaftTrains`, stores, health, prime movers, pages, `compat/create` |
| `batch-still.md` (new) | 17.1 | `science/plant/still`, `science/column/still`, `Stills`, `StillUnit`, `StillFacts`, the sheet's still |

Every chapter of the guide is under 40 KB (largest `fluid-step.md`, 34.7 KB). Corrected against the code on the way:
the `CONFIGURATION` slot state (missing), the vacuum refusal on `ColumnComponents.ejector`, `ShaftPlan`'s nine kinds, the
native Create shaft (`CreateShaftBlock`, `CreateBridgeBlockEntity`), `StructureRealignment.holdShaft` (not
`EquipmentStructure`), the vacuum pumps' constants by name, a stale "shown, not booked" trap dropped.

## Cut to `HISTORY.md`

One block at its end, `## From the chapters of scope J2-I` (264 lines): for the two split chapters, a typed list of the
cut provenance, numbers and protocols, then their batch-appended sections verbatim (`####`); for the seven small
chapters, the cut sentences. No other part of `HISTORY.md` changed beyond step 2's links.

## The seven small chapters

All seven keep their old section form (none is on the template; the edits were cuts and fixes): `cooling-towers.md`,
`effects.md`, `instrument-accessories.md`, `pipe-laying.md`, `process-control.md`, `tutorial.md`, `wired-control-power.md`.
Fixed names: `StepEquations.kept`, the tower display in `Displays` (not `FluidWorldAuthority.publishTowers`),
`Instrument.Fitting` read by `Store.legacy` (not `Legacy`), the five `installPipe*` methods, `PipeRackBlockEntity.FORMAT`;
`process-control.md`'s paragraph on `ControlBinding` (no such class) cut. The three format numbers the batch README names
were already gone; the others (state formats, facts schemas, `fluid-21`, `equipment-23`, `control-4`, `lines-1`, `rack-3`, …)
are now the constant's name with a link to `FORMATS.md`.

## Not settled

- Not carried into any chapter (kept verbatim in `HISTORY.md` for their owners): the generator and turbine size limits
  (`MachineDuty.MAX_GENERATOR`, `MAX_TURBINE`, `ProcessUnit.TURBO_GENERATOR`), which belong to `equipment-designs.md`.
- The identifier checker (`tmp/j2i/idents.mjs`) has no skip list: over the whole guide it flags 108 names, NBT keys,
  formula symbols and JDK, Minecraft and Create types (e.g. `runtime-saves.md` line 66's tag names), none in the chapters
  this scope wrote. It does not check lower-case members beyond `Class.member`.
- The `gui-creation` skill still names "the column calculator" as a screen (outside "links and chapter names").
- Snapshot lines: set to `9d6d0d82e` only on the chapters this scope edited for the code changes or wrote; the seven small
  chapters keep theirs.
