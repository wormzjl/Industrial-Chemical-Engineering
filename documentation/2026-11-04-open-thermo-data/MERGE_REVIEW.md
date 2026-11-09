# Merge review: 0.23.0

Branch `claude/refinery-compounds-thermos-252973`, three batches: `2026-11-04-refinery-compounds`, `2026-11-04-open-thermo-data` and `2026-11-05-neural-retrain` (its branch merged here by a fast-forward to `d54fe0fa0`). The owner, 2026-11-08: "Merge main in (Recommended)", not a rebase, so the tested commits and the commit ids the documents cite stay valid; version "0.23.0 (Recommended)"; subagents "Translations (Opus, high), Code guide (Opus, high)" (decision E35).

## The merge of main

- Main `b794e2d41` (0.22.0) merged in `0d0499f11`. 13 files conflicted; the resolution keeps both sides' rules:
  - `build.gradle`: the union of both gate lists.
  - `FluidCheckpointCodec.VERSION` 40 (main 39, the branch 31: max + 1; a state format, so the state rebuilds). The fluid facts schema stays main's 14: the branch changed restatement, not the facts' layout.
  - `FluidWorldAuthority`: main's control facts and cadence lines, then the branch's world model and column growth.
  - `ColumnDoor`: the branch's components with main's `Exchangers` (the old constructor takes `Exchangers.STANDARD`).
  - Tests: main's structure where main changed a law (pumps refuse no gas since request 11; the crossflow condenser), re-pinned on the merged code.
  - `documentation/INDEX.md`, `tools/INDEX.md`, `CHANGELOG.md`: every row of both sides.
- Compat gates on the merged code: `FluidFactsCompatTest` and `EquipmentShapeCompatTest` pass (main's schema-10 to -14 images with the branch's restatement, the branch's compound-basis and island-sets images with main's reader).

## After the merge (`fcde043f3`)

A soft-assert run (every mismatch recorded in one run) found 21 mismatches in five classes.

- Main's D42 (batch 2026-11-03-rotating-machinery): a pump's shaft power is the ideal work over its best efficiency, and all of it heats the discharge. The crude reaches E-101 0.08 K warmer: E-101 needs 569 m² (568), H-101 fires 73.06 MW (73.08), the furnace-limit sheet asks 98.04 MW (98.08).
- On the medium crude, Auto's single crossflow condenser shell is 2.00 × 6 m on 509 m² (2.25 × 7 m on 781 m² on Tia Juana), and the widest single standard shell now meets the gas drop.
- `PumpRateWarmStartTest`: from 1 atm, 1 m below the pump, the heavy crude reaches the suction at some 92 kPa, under the 99 kPa its bubble point (68.6 kPa) and main's NPSH3 floor ask. The pump stood on its suction limit at every rate. The source stands at 150 kPa, as `PumpJunctionStartupTest`'s does: the law at 1 m³/s, the limit at 2.
- Runs: holdGates `ColumnSizingSampleTest` 15/15, `SizeAllSampleTest` 7/7, `UnitSizingSampleTest` 11/11, `FlowsheetColumnTest` 9/10; test `PumpRateWarmStartTest` 2/2.
- `FlowsheetColumnTest.aFurnaceOnADutyClosesColdAsFromAFirstEstimate` failed: see the next section.

## The cold furnace sheet (E36, `6e5c4efae`)

- On the merged code, the pinned cold first pass (H-101 at 70.4 MW) failed. A probe across 69.9 to 70.9 MW solved 3 of 7 duties. Every failure stalled in V3's 30-stage rung: the heavy tails sank below their floor, and a damped descent over the dead coordinates settled 46 K hot (see [COLD_FURNACE_STALL_REVIEW.md](COLD_FURNACE_STALL_REVIEW.md)). Main's D42 only moved 70.4 MW across a basin edge. The owner: "Fix V3 first".
- Fix, form S2 in `V3SimultaneousColumnSolver`: when the Newton step fails, a free point that sank below its floor releases the attempt to the support refresh.
- Results: furnace 7 of 7; medium grid 10/10 (8/10), Tia Juana 10/10; polls 2,715k (5,904k) and 3,174k (5,523k); roots unchanged to 4 decimals. The owner validates the form before the merge.
- Runs (lane 2, `--tests`, on `6e5c4efae`'s code):
  - columnGates `science.column.v3.*` 83 classes, 541 tests: 540 pass.
    - The one failure was `V3StageContinuationTest`'s resume test: the half-steam case now closes on its first sweep.
    - The resume's contract moved to steam ×1.5, the one case of E30's five that still reaches it (`O10FixResumeProbe`).
    - Re-run: `V3StageContinuationTest` 11/11.
  - holdGates 8 column classes, 64/64, with `FlowsheetColumnTest.aFurnaceOnADutyClosesColdAsFromAFirstEstimate` passing.
  - test `FlowsheetExamplesTest` 6/6.

## Tooling cleanup (`f4130065f`)

- `perl tools/merge-lane/main-reach.pl .`: 671 of 671 main classes reached. By method, `MaterialCatalog.freezingPoint`, `thermochemistry` and `flammability` had only test callers. They and their maps are removed; the loader still validates every sub-record; the test helper `SubRecords` reads them. Runs: `MaterialCatalogTest` 14/14, `MaterialThermochemistryTest` 4/4.
- Tool folders of the three batches: `tools/compound-import/`, `tools/package-rework/`, `tools/crude-grades/`, `tools/open-thermo-data/` (E36's probes in `e36/`), `tools/neural-retrain/`. E36's scratch code left the tree before `6e5c4efae`; its probes were never committed to `src/`.

## Translations, code guide, version

- Translations: `2cd2a4a5d` (40 keys added, 0 rewritten, 56 removed in zh_cn, fr_fr, es_es and ja_jp).
- Code guide: `06052fc6e`, eleven chapters at `f4130065f` (thermo-materials, fluid-runtime, fluid-science, equilibrium-engine, column-v3, column-operation, process-simulator, client-gui, heat-exchangers, verification, README). The column-v3 chapter and the README are now at `6e5c4efae` (E36's release).
- `mod_version` 0.23.0 (minor: new compounds and data, a model batch).

## Final lane

(pending)
