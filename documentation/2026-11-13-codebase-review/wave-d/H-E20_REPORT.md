# H-E20 report: a cell's layout stamp becomes its design's declared layout revision

Brief: `WAVE_D_BRIEFS.md`, section H-E20 (EQUIPMENT_REVIEW row E20, SIMPLIFICATIONS_REVIEW row E20). Kind: structure; no
layout, nozzle, shape field or refresh action changes. Base: `fe6731f76` on `claude/codebase-review-refactor-8a85ef`.
No build ran (subagent rule); every point the compiler must confirm is listed at the end.

## Commits

| Commit | Step | What |
|---|---|---|
| `0d2e2209f` | 1 | `Sizes` gains `layoutRevision` (last component, 1 or more, a compact constructor refuses 0); the 32 designs pass `1`; `UnitDesign.layoutRevision()`; `ShapeRasterizer.DRUM_LAYOUT_REVISION`, `COLUMN_LAYOUT_REVISION` (1) and `layoutRevision(shape)`, read through the kind |
| `1c90fa44f` | 2 | The cell saves `LayoutRevision` and stops writing `Layout`; `ShapeRasterizer.stamp` renamed `legacyStamp` (the old saves' reader, and the table's digest); `EquipmentRefresh.STALE`, `placedRevision`, `legacyRevision`; every caller of the stamp follows |
| `881044c12` | 3 | `EquipmentLayoutGoldenTest`: the revision column; a digest change without a bump fails first |
| `fbcc585fd` | 4 | `EquipmentRefreshTest` on (legacy stamp, revision); GameTest `aCellReadsItsLayoutRevisionOrAnOldSavesStamp` |
| (last) | docs | Code guide (equipment chapter, README), this report |

## Where each design declares its revision

One place per design: the last argument of its `new Sizes(...)` in its constructor (the line after the `moves` flag),
in the 32 `UnitDesign` subclasses under `science/equipment/` (`AdsorberDesign` ... `VerticalHeaterDesign`). A class
that draws two kinds (`MotorDesign`, `CoolingTowerDesign`, `ChimneyDesign`, `VacuumPumpDesign`, `PrimeMoverDesign`,
`ConverterDesign`) has one revision for both: a bump of one refreshes the other's structures too (harmless, a no-op plan).
The drum and the column, which no design draws, declare theirs as `ShapeRasterizer` constants. The reader is
`ShapeRasterizer.layoutRevision(shape)`. `tmp/he20/check.mjs` confirms 32 designs, one `Sizes` call each, 13 arguments,
the last an integer literal of 1 or more, and no override.

## The tag and the four load cases

New tag `LayoutRevision` (int), written by `EquipmentShellBlockEntity.write` (save and update tag). `Layout` (long) is
read only (`LEGACY_LAYOUT`). The decision is pure, in `EquipmentRefresh`; the cell holds the current revision or `STALE`:

| Saved cell | On load | Then |
|---|---|---|
| New, `LayoutRevision` = design's, shape not repaired | current (`placedRevision`) | no refresh |
| New, any other value (0, older, newer), or its shape repaired on read | `STALE` | refresh, as before |
| Old, only `Layout`, design at revision 1, stamp = `legacyStamp(shape)` | current, revision 1 (`legacyRevision`) | no refresh; writes `LayoutRevision` at its next save |
| Old, another stamp, no stamp (before stamps), or design past revision 1 | `STALE` | refresh, as before |

So no placed structure refreshes at the switch, a structure an older layout placed still does, and after a design's
first bump its old cells are all stale. `stale()` keeps its form: unbound, outside the box, or a revision other than the
current one; `StructureRefresh`, `StructureRegistration` and `RegistryFirstGameTests` read only `stale()`, unchanged.

**Forced refresh.** The two places that set the stamp to 0 now set `EquipmentRefresh.STALE` (0, which no design can
declare: `Sizes` refuses it): `layoutIncomplete()` (a blocked refresh's cells) and the reset at the top of
`loadAdditional`. Saved, it reads back stale under the new tag.

**One decision beyond the brief (repairs).** The old stamp hashed the shape, so a cell whose shape `ShapeRepair` changed
on read was stale whenever the repair changed the layout. A revision does not see the shape, so a new-tag cell whose shape
needed a repair is stale whatever it saved (`placedRevision(shape, repaired, saved)`). Without it, a repair that grows a
layout would leave cells missing. Effect: a repair that keeps the layout (for example an operating point dropped) now
refreshes once, a no-op plan that rebinds the repaired shape; repairs happen only after a code change narrows a range.
The legacy path keeps the old rule exactly (its stamp is the repaired shape's). The owner may veto; the alternative is
to compare the saved box as well, which misses repairs that keep the box.

## The golden test

`line` keeps the `stamp` column as the test's digest of box, cells and ports (`legacyStamp`, the same function, so every
present digest is unchanged) and appends ` | revision N` last, only where N is not 1. A line without it reads 1. So the
checked-in table (`src/test/resources/compat/layouts/layouts.txt`, under `compat/`, which this task may not edit) parses
and passes unchanged: `check.mjs` reads its 139 lines with the test's own reader (`shapeOf`, `digestOf`, `revisionOf`),
139 distinct shapes, a hex digest each, revision 1 each. The test now fails first, naming the kind and the lines, when a
recorded shape's digest changes while its revision stays, or a revision goes back; it then deletes
`build/compat/layouts/layouts.txt` so no table without the bump can be copied. Any other change fails as before and
writes the new table. If the owner prefers the revision on every row, the main thread can append ` | revision 1` to the
table's lines and drop the `!= 1` in `line`; nothing else changes.

Not covered: the table records three sizes per kind (and the extra rows). A layout change confined to sizes between the
recorded ones (a band rule: manway sizes at shell D 1.25 m and 4 m, the tank and sphere stair sizes, a unit's size
thresholds) changes no recorded digest, and the test cannot ask for the bump. Widening the recorded sizes needs new table
rows under `compat/`, outside this task. A reviewer of a layout change still checks for a bump.

**When the main thread first runs it:** `EquipmentLayoutGoldenTest` must pass with no table change. A failure listing
`-`/`+` lines would mean `legacyStamp` differs from the old `stamp` (the body is unchanged, only renamed): stop and read
the diff. Do not run it with the bump check switched off (owner rule).

## Users of the stamp, edited or listed

Edited: `ShapeRasterizer` (rename, field `legacyStamp`), `EquipmentShellBlockEntity` (field 78, bind 337, zeroed 450 and
593, compared 467, read 637, written 654), `EquipmentRefresh` and `ShapeRepair` (comments), `EquipmentLayoutGoldenTest`,
`EquipmentRefreshTest`, `EquipmentShapeCompatTest` 284, `EquipmentCompatGameTests` (the blocked-refresh reload now
removes `LayoutRevision` and puts `Layout` 0), and three archived scripts that name the stamp:
`tools/simulator-sizing/archive/machine-rework/MotorCorpus.java`, `.../fixtures-prepare.py`,
`tools/simulator-sizing/archive/drive-ends/DriveEnds.java` (renamed; not compiled by the build).
Listed, not edited (not mine): `StructureRefresh.java` comments at 143, 159 ("zeros when saved before stamps", about the
box) and 367, 405 ("the cells written keep no stamp": now they hold `STALE`); `CHANGELOG.md` 559 (history).
`EquipmentDeconstruction.stamp` is another thing, untouched. `FORMATS.md` lists no cell tags; `AGENTS.md` does not name
the stamp: neither changed. The code guide's equipment chapter and README are updated; `map.mjs --readme` and `--check`
pass (counts unchanged).

## Tests for the main thread

| Task | Classes | Proves |
|---|---|---|
| `compileJava`, `compileTestJava`, `compileFluidGameTestJava` | all | the 32 `Sizes` calls, the rename, the new methods and tags |
| `equipmentGates --tests` | `EquipmentLayoutGoldenTest`, `EquipmentRefreshTest`, `EquipmentShapeCompatTest`, `ShapeRasterizerTest`, `ProcessUnitTest` | table unchanged and the bump check; the four read cases on the pure functions; every kind's revision; corpus reads as before; `UnitDesign`'s registry builds with the new `Sizes` |
| `test --tests` | `LayeringTest`, `FlowsheetPaletteCoverageTest` | `world` reads `science/equipment` only downward; the designs' registry builds in `test` |
| final lane, `runFluidGameTestServer` | `EquipmentCompatGameTests` (5) | below |

GameTest coverage: `aCellReadsItsLayoutRevisionOrAnOldSavesStamp` covers all four read cases on real block entities (new
current, new stale with another revision and with 0, legacy current with its own stamp and its rewrite to
`LayoutRevision` with no `Layout`, legacy stale with another stamp and with none); `aBlockedRefreshTriesAgainAtTheNextLoad`
covers a legacy stale cell refreshing in the world, `STALE` after a blocked refresh, and current cells once the refresh
completes. The other GameTests that place equipment (`RegistryFirstGameTests`, `AssemblyGameTests` and the like) read
`stale()` through placement and should pass unchanged.

## Not verified without a compiler

- `EquipmentShellBlockEntity` about 647: `tag.contains(LAYOUT_REVISION, net.minecraft.nbt.Tag.TAG_INT)` (fully qualified,
  as the file's `StillFuel` line does), `read.repairs()` is a `List<String>`.
- `EquipmentRefreshTest` `everyKindDeclaresOneLayoutRevision`: `new Sizes(1, 2, 1, ...)` from a test in the same package
  (the record is package-private); `int first = revisions.computeIfAbsent(...)` unboxing.
- `EquipmentCompatGameTests` new case: `loadWithComponents` does not alter the tag it reads (the restore at the end reuses
  `saved`, which was never passed to it before), `helper.fail(String)`.
- `check.mjs` (in `tmp/he20/`): braces, parentheses and brackets balance in all 45 changed files; no mixed line endings
  (the worktree is CRLF under `core.autocrlf`); no `ShapeRasterizer.stamp` left in `src/` or `tools/`.
