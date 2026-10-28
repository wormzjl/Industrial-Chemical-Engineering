# Column sump enlargement: review

Batch `2026-10-27-column-sump` · 2026-10-27 · owner request: "Enlarge the bottom sump of the distillation column so it looks more real, find some engineering drawings for reference". References: [COLUMN_SUMP_SURVEY.md](COLUMN_SUMP_SURVEY.md). Pictures: [figures/sump-before-after.png](figures/sump-before-after.png) (front, one scale), `sump-before-iso.png`, `sump-after-iso.png`.

## 1. Expected behaviour

Under the bottom tray a column holds its bottoms liquid and takes the reboiler's vapour back above it. Bottom up: low level about 0.3 m over the tangent; a level span for 5 to 10 min of bottoms (a liquid flux per m² of column, so 1 to 2 m at any diameter); 0.3 m to the return nozzle; the nozzle; 0.45 m to the bottom tray. That is 2.7 to 3.4 m on small and medium columns. Perry's (via BioSTEAM) allows 3 m for bottoms surge beside the 1.2 m top space the mod already uses; Walas 1.8 m on a 0.9 m tower; a 2.44 m stripper with a vertical thermosiphon 10.9 m. A wide column's 2:1 head holds minutes of bottoms by itself (67 m³ at D 8), so its sump needs no more height.

## 2. What the code did

`ColumnTrays.bottomSpace` was max(2 m, D/2): a 2 m column had its bottom tray 2 m over the tangent, its reboiler return 1.5 m over it, its lowest platform almost on the skirt. That is below every reference for D under 4 m. The tight sump also left short columns without a row for sump steam or the vapour return: 13 and 5 of NewSweep's 315 designs were refused for them.

## 3. Change (branch `claude/distillation-sump-enlargement-59e8e7`)

`bottomSpace` is the stack, named in `ColumnTrays` (`LOW_LEVEL` 0.3, `LEVEL_SPAN` 1.5, `UNDER_RETURN` 0.3, `OVER_RETURN` 0.45 m) plus the vapour return's design size (`ColumnStructure.returnOd`, 0.27 D to the next 5 cm, at most 0.8 m; `place` uses it too), to the next whole metre, or D/2 where that is more. Sump: 3 m to D 1.5 (was 2), 4 m for D 2 to 8 (was 2 to 4), D/2 beyond (unchanged). The stage bands, the platform rule and the ladders follow the trays, which stand on the sump. No save format changes: a column's length is derived from its trays and diameter at every load (`ShapeRepair` builds it with `EquipmentShape.column`), a column is not a fluid vessel, and the GUIs place nozzles by tray number.

Whole metres, not half: nozzles stand on whole cell rows. A half-metre sump moves the 0.6 m tray grid half a block against the rows, so crowded designs re-solve their rows: `ColumnStructureTest`'s crowded 4 m design (draws on trays 20 and 21) lost draw 4 at a 3.5 m sump. A whole-metre sump keeps the bottom tray on a block face, as the 2 m sump had it; up to D 4 the trayed section is the old layout moved up whole blocks.

## 4. Measurements (Gradle-free probes, `tools/column-sump/`)

- NewSweep (315 designs, D 0.5 to 15, 1 to 64 trays, four configurations): refused 103 → 93; per design, 0 newly refused, 10 newly built (sump steam and vapour returns that now find a row). Nozzles more than a row from their process height 1 → 1 (the same 15 m, 1-tray case). Ladder cages in a nozzle's room 1 → 2: both on 1 m columns refused before (the allowed slim case; no cage in a pipe block); the old 1 m clash is gone. Decks in a room 0, davit masts 0, walks parted 0, pipe cells taken 0.
- Designs built both ways (212, 1,306 horizontal nozzles): every draw, feed, pumparound, reflux, return and steam nozzle moves by exactly the sump's growth; 77 vapour returns rise one block less (lower in their band); the bottoms through the skirt does not move; one side change (a 1 m, 1-tray vapour return, right → front).
- Forms compared on the quick sweep (`results/sweep-rule-*.txt`): 2.5 + 0.3 D and 3 + 0.4 D (the second puts the structure in one pipe cell and one davit mast in a nozzle's room); the stack at half metres (above); the research's looks-driven max(3, 2 + D/2) is drawn in the figure, not swept.

## 5. Placed columns: nozzle pipe blocks move (owner approval)

Every placed column under 8 m refreshes to a taller layout on load (`EquipmentRefresh`): its sump grows 0.25 to 2 m, its shell (whole metres) up to 2 m, and every nozzle but the bottoms rises with it, so a pipe joined at the old height parts and must be re-laid. Layout table: the 0.5 m column's row re-recorded (T/T 15 → 16 m, box height 21 → 22, top outlet, reflux, feed and vapour return one block up); the 8 m and 15 m rows unchanged. Owner decision 2026-10-27: "The nozzle moves on placed columns are approved."

## 6. Options for the owner

- (A, built; owner decision 2026-10-27: "Keep option A") The stack to the next metre: 3 or 4 m up to D 8, wider columns unchanged.
- (B) Taller everywhere, for looks: max(3, 2 + D/2) to whole metres (D 4 → 4 m, D 8 → 6 m, D 15 → 10 m); physics does not ask for it, and it moves the wide columns' nozzles too. One line in `bottomSpace`, a table re-record.

## 7. Tests changed and gates

- `EquipmentShapeCompatTest.everyCorpusShapeReadsAsItWasSaved` compared a column's whole shape text, its tangent length included. That length is no fact (derived at every load, the saved tag never read for a column), so the gate now takes it from the current rule; the corpus images are unchanged and still read with no repair.
- `ColumnStructureTest.theVapourReturnComesWithAKettle`: the sump steam no longer shares the return's row; it stands a row over it, under the bottom tray, still turned to the front (on its home side its pipe would crowd the return's). The stripped column's steam takes that row on its home side.
- Affected classes (`ColumnStructureTest`, `EquipmentShapeCompatTest`, `EquipmentLayoutGoldenTest`, `ColumnComponentsTest`, `ColumnBlueprintTest`, `AssemblyMatcherTest`, `PortMarkingsTest`): 62/62.
- On `a0ab6b50`'s tree: `./gradlew test slowGates` 1643/1643 and 188/188; `runFluidGameTestServer` 34/34 on a fresh run id (`sump1`), with the assembly and pipe-laying GameTests that build columns. The first full run, on the half-metre form, failed 4: the crowded design in two tests, and the two tests above.

Code guide: `equipment.md` (`ColumnTrays` row). Tool folder: `tools/column-sump/`. No in-game check (drawing and layout only: judged by the sweep, the layout table and software renders).

## 8. Merge (owner: "merge", 2026-10-27)

Main had moved to `293145bb` (the skirt skylight batch, merged without a version bump). Merged into the branch at `6789283d`: conflicts only in shared rows, both sides kept (CHANGELOG `[Unreleased]`, the two INDEX files, the equipment chapter's snapshot line); the layout table matches the merged code (regenerated offline). Release prep: `mod_version` 0.15.1 (the proposed patch), CHANGELOG `[0.15.1]` holding both batches, code guide README and equipment chapter at `6789283d`, statuses Implemented / Concluded. Tooling: `tools/column-sump/` (already on the branch). Gates on the release tree (after a PlateTectonics timing benchmark had finished; its CLI sweeps still ran): `./gradlew test slowGates` 1644/1644 and 188/188; fluid GameTests 34/35 on run id `sump2`, then 35/35 on a fresh `sump3`. The failure was the skirt batch's `EquipmentCompatGameTests.aSkirtsFloorTakesTheSkylightAboveTheDrum`, at its "old shadow" step (floor skylight still 15): its 400-tick wait ran out within a second of fast GameTest ticks while the CPU was loaded, before the light engine caught up. It touches no column code; the test is timing-dependent (open item).
