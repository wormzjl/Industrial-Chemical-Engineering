# Consolidation review: `codex/simulator-sizing` (2026-11-08)

Owner request: "Review codex/simulator-sizing, make it maintainable and consolidate the codes, remove unnecessary tests".
Reviewed `3107ecc90` (code as `050cd1098`) against `main` `f1db3a892`: 85 commits, 832 files, +39.6k lines. Changes on
`claude/simulator-sizing-refactor-6bacab`, not merged or pushed; scripts in `tools/simulator-sizing/consolidation/`.

## Findings

1. Layout: of 7,960 lines in the 114 added Java files, 367 passed 200 characters, 1,135 held more than one statement.
2. One socket lived in three classes (`DataPortGeometry`, `PowerPortGeometry`, `DataSocketGeometry`); six call sites
   moved a datum to world space by hand. Four cabinet blocks each turned their boxes with their own switch.
3. `PipeLaying` held only messages of `LineLaying`, `LineToolFeedback` one HUD method; the line clients drew marks by
   two protocols. `ControlService` (1,031 lines) held five duties. Four methods had no callers.
4. Stale rules: gui-creation rule 9, `fit-and-scale.md`, `recipes.md` and a `FluidDeviceScreen` comment still told
   screens to call `WindowScale`, removed in `5b84b9ec6` by the owner's rule of 2026-11-07: keep the player's GUI scale.
5. Tests: 141 of the 144 methods the branch adds meet `2026-10-21-test-review/CRITERIA.md`; a duplicate, a probe-count
   pin, a tautology after the fix of finding 2 and five prints do not. Kept although they read as pins: the
   source-text guards of `LineContractTest`, `LineToolSessionTest` and `KitLayoutTest` (K3: owner instructions of
   2026-11-07) and `LineInteractionTest`'s `assertSame`, the only check of a one-ended piece's facing completion (K6).

## What changed

| Commit | Change |
|---|---|
| `658861333` | google-java-format 1.22.0 `--aosp` on the 114 added files only (85 main, 22 test, 7 GameTest): 20,956 lines, none over 107 characters (`density.js`). `javap -c -p -constants` is identical for all 3125 classes (`javap-dump.sh`). |
| `df24eb6ac` | `SocketGeometry` (one `Socket`, one gland, an orange ring for FE); `DataPorts.at`, `DataPorts.fittingKey`; `HorizontalShapes` for console, bench, controller and DCS; closed-form `DcsGeometry.turned`; `PipeLaying` into `LineLaying`, `LineToolFeedback` into `LineToolSession.routeHud`; one marker frame (`LineToolMarkers.begin`, `box`, `line`, `end`); one `CableMedium.OUTSIDE`; dead code out. 38 files, +424/−531; 5 classes removed, 2 added. |
| `da982f268`, `2414c4e3a` | Archived `ControlSystemTest.removingOneCable…` (A2) and `PowerGridTest.anUnpoweredBus…` (A3), trimmed `ConduitGeometryTest.fixedPowerDiameter…` to its mesh checks (A3), removed 5 prints. `tools/test-archive-2026-11-08/` holds the sources and a checked re-attach patch. |
| `181ddcabe` | GUI skill, the `FluidDeviceScreen` comment, tool READMEs, this batch; code guide `equipment.md`, `pipe-laying.md`, `wired-control-power.md`, `verification.md`. |
| `3efffdb2d` | `ControlService` split by duty (owner: "Yes, split it"): `ControlHosts` (ports, bindings, aliases, wiring queries, inputs), `ControlLaw` (one controller's decision, returned as a `Decision`), `ControlPlans` (the setpoints `apply` puts on a slice), `ControlPublication` (watched menus, replay cache, snapshot). It keeps the public API, wiring registration, edit queue and cadence: 1,031 → 311 lines. |
| `e4749cdb6` | `ControlLoopGameTests` (owner: "Yes, add it"): a local controller, wired by cable facts to a flowmeter and an unconfigured valve on a nitrogen line, takes a target of half the free flow through its menu; the cadence decides CONTROLLING, the flow settles on the target with the valve's saved law unchanged, and the open menu receives status, answer and reading; switched off, the free flow returns. In a fresh world it passes in about 600 of 4000 ticks; with `ControlPlans.apply` returning its graph unchanged it times out at the target (0.319 kg/s against 0.159). |

## Verification

- `main`, `test` and GameTest sources compile after each code commit (`mcpCompat` imports none of the mod's classes).
- `test` and `equipmentGates` on `da982f268`, 4 workers, 18 class filters (every test that names a changed class, reads
  changed source text or was edited): 150 tests, 0 failures. Focused GameTests in a fresh world: 32 of 32 methods of
  `ConduitLaying`, `DcsConsole`, `PipeLaying`, `CreativePower`, `ProcessControl` and `EquipmentProtection` pass.
- The split: `move-check.js` finds 20 of 25 methods token-identical; the rest and the cadence (helpers inlined) differ
  only by delegation, the `Decision` value, two dead locals and an unused parameter. `DeterministicMathSourceTest` and
  22 focused GameTests of `ConduitLaying`, `DcsConsole`, `ProcessControl`, `CreativePower` and one exchanger pass.
- No dev client was open; at most one other Gradle lane ran. Not seen in game: socket glands and line-tool marks.

## Owner options (not done)

1. `tools/simulator-sizing/archive/` keeps 188 scratch `.py` codemod and audit scripts; they could be deleted.
2. The formatter cut some `//` comments into fragments; a reflow, checked by bytecode, would join them.
3. Files the branch modifies but did not add stay dense, so the unmerged refinery and save-corruption branches still
   merge; format them after those land. `codex/simulator-sizing` should take no more commits, or rebase onto this.
4. Left as they are: the rack picking shared by the two line clients, the three line-piece holders (shape rebuild and
   update flags differ), the `MachineAnimation` and `TankRenderer` buffers.
