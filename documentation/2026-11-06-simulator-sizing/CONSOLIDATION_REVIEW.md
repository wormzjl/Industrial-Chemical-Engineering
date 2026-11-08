# Consolidation review: `codex/simulator-sizing` (2026-11-08)

Owner request: "Review codex/simulator-sizing, make it maintainable and consolidate the codes, remove unnecessary tests".
Reviewed `codex/simulator-sizing` at `3107ecc90` (code as `050cd1098`) against `main` `f1db3a892`: 85 commits, 832 files,
+39.6k lines. The changes are on `claude/simulator-sizing-refactor-6bacab`, on top of it; nothing is merged or pushed.

## Findings

1. Layout: the 114 Java files the branch adds were dense. Of 7,960 lines, 367 ran past 200 characters (the longest
   1,441) and 1,135 held more than one statement.
2. One socket lived in three classes: the gland mesh (`DataPortGeometry`), its FE colours (`PowerPortGeometry`) and the
   datum lookups (`DataSocketGeometry`). Six call sites moved a datum to world space by hand; two built a fitting key.
3. Four cabinet blocks each turned their boxes to the four facings with their own switch.
4. `PipeLaying` held only messages of the shared `LineLaying` framework, `LineToolFeedback` one HUD method; the two line
   clients drew marks by two protocols. Four methods had no callers.
5. Stale rules: rule 9 of the gui-creation skill, `fit-and-scale.md`, `recipes.md` and a `FluidDeviceScreen` comment
   still told screens to call `WindowScale`, which `5b84b9ec6` removed on the owner's instruction of 2026-11-07
   ("Preserve the player's GUI scale across IChemE screens"). An agent following the skill would add it back.
6. Tests: 141 of the 144 methods the branch adds meet `2026-10-21-test-review/CRITERIA.md`; a duplicate, a
   probe-count pin, a tautology after the fix of finding 2 and five prints do not. Kept although they read as pins:
   the source-text guards of `LineContractTest`, `LineToolSessionTest` and `KitLayoutTest` (K3: owner instructions of
   2026-11-07) and `LineInteractionTest`'s `assertSame`, the only check of a one-ended piece's facing completion (K6).

## What changed

| Commit | Change |
|---|---|
| `658861333` | google-java-format 1.22.0 `--aosp` on the 114 added files only (85 main, 22 test, 7 GameTest): 20,956 lines, none over 107 characters. `javap -c -p -constants` is identical for all 3125 classes. |
| `df24eb6ac` | `SocketGeometry` (one `Socket`, one gland, an orange ring for FE); `DataPorts.at`, `DataPorts.fittingKey`; `HorizontalShapes` for console, bench, controller and DCS; closed-form `DcsGeometry.turned`; `PipeLaying` into `LineLaying`, `LineToolFeedback` into `LineToolSession.routeHud`; one marker frame (`LineToolMarkers.begin`, `box`, `line`, `end`); one `CableMedium.OUTSIDE`; dead code out. 38 files, +424/−531; 5 classes removed, 2 added. |
| `da982f268`, `2414c4e3a` | Archived `ControlSystemTest.removingOneCable…` (A2) and `PowerGridTest.anUnpoweredBus…` (A3), trimmed `ConduitGeometryTest.fixedPowerDiameter…` to its mesh checks (A3), removed 5 prints. `tools/test-archive-2026-11-08/` holds the sources and a checked re-attach patch. |
| docs commit | GUI skill, the `FluidDeviceScreen` comment, tool READMEs, this batch; code guide `equipment.md`, `pipe-laying.md`, `wired-control-power.md`, `verification.md` (snapshot `da982f268`). |

## Verification

- All four source sets compile after each commit. No dev client was open; at most one other Gradle lane ran.
- `test` and `equipmentGates` on `da982f268`, 4 workers, 18 class filters (every test that names a changed class, reads
  changed source text or was edited): 150 tests, 0 failures. Focused GameTests in a fresh world: 32 of 32 methods of
  `ConduitLaying`, `DcsConsole`, `PipeLaying`, `CreativePower`, `ProcessControl` and `EquipmentProtection` pass.
- The formatter commit needs no run: its bytecode is identical. Not checked: client-only drawing (socket glands in
  `DataControlRenderer`, line-tool marks); it calls the same geometry, and a look in game is the owner's choice.

## Owner options (not done)

1. `tools/simulator-sizing/archive/` keeps 188 scratch `.py` codemod and audit scripts; they could be deleted.
2. `ControlService` (1,031 lines; `tick` about 150, `control` about 125) could be split by duty.
3. The formatter cut some `//` comments into fragments; a reflow, checked by bytecode, would join them.
4. Files the branch modifies but did not add stay dense, so the unmerged refinery and save-corruption branches still
   merge; format them after those land. `codex/simulator-sizing` should take no more commits, or rebase onto this.
5. Left as they are: the rack picking shared by `CableLayingClient` and `PipeLayingClient`, the three line-piece holders
   (shape rebuild and update flags differ), the `MachineAnimation` and `TankRenderer` buffers.
