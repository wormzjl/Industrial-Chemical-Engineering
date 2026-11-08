# Equipment protection and deconstruction review

- Status: implemented on `codex/simulator-sizing`, code `efa22cfe`, translations `f13019d1`, 2026-11-07; prepared for 0.22.0, not merged.
- Expected behaviour: direct breaking of constructed equipment requires Creative. Survival uses the equipment constructor to deconstruct the whole unit before it.
- Previous behaviour: replacing any shell/interior cell triggered whole-structure dismantling; ordinary removal APIs could therefore destroy equipment.

## Implementation

- `EquipmentProtection` and common `EquipmentChunkMixin` reject unauthorized chunk-state writes before mutation. Covers normal world removal/replacement APIs, including commands and mechanical breakers. Both equipment cell kinds also reject entity/explosion destruction and retain piston blocking, with negative hardness and high blast resistance.
- Exact-cell authority is package-confined, level/thread-specific and restored in `finally`. Creative removal and existing verified placement/layout/retirement/removal paths use it. Other blocks are unaffected; clients still receive authoritative updates.
- Constructor choice/design pages show Deconstruction: nearest physical target on its right/up placement line within 16 blocks, name, origin, count, action and result. Target selection does not depend on the selected equipment draft.
- Server validates current constructor/reach/build rights, exact target origin/shape and every loaded cell’s interaction/break permission before removal. Stale/repeated requests cannot remove the next unit. Reply request IDs isolate pending/reopened screens.
- Existing dismantling invalidates assemblies/shaft registrations and queues fluid removal. It performs no process solve. Pipes/cables and unrelated blocks remain; Survival recovers installed accessories. Contents leave through the existing removal ledger.
- Equipment protocol becomes `equipment-21`. No saved fact/shape field, fluid format, geometry or nozzle position changes; no corpus addition is required.

## Verification

- Seven new real-server GameTests pass: shell/interior world/chunk writes, Survival/Creative, explosion/entity/piston resistance, all four constructor headings, mismatched draft kind, stale/denied/repeated requests, thread/exception scope and reply codec.
- Affected prior world coverage: 51 methods, initially 48 pass; two old-save fixtures needed authorized fixture edits/cleanup, then their three-method refresh/cleanup follow-up passed. Combined coverage resolves all 58 paths. No player world or dev client used.
- Constructor/codec JUnit: 23 pass; after adding long-coordinate/error wrapping, all 10 constructor checks pass again. Five final checks pass: LanguageFilesTest (2), DeterministicMathSourceTest (2), UnitTextTest (1).
- Light/dark constructor software views inspected at the top and scrolled end. Target text and button fit; extreme coordinates and long names/errors are covered by the layout test.
- Four locales match all 2933 ordered keys/arguments; button widths fit. The eight prior Chinese capitalization/equivalent-wording audit notices remain reviewed and unchanged.
- Reachability: 641/641 unique production class names, zero unreachable; no test helpers packaged. Jar metadata/config declares the required common mixin.
- Release jar: `build/libs/icheme-0.22.0.jar`, 8,551,991 bytes; SHA-256 `1af24443a67e460961caa5278e4957826d39d05ab672b0ca3488a6874dd8b32f`.
- Evidence: worktree `tmp/equipment-protection/`; canonical main `research/2026-11-06-simulator-sizing/evidence/equipment-protection/` holds the jar/manifest/figure, with compressed logs at main `tools/simulator-sizing/logs/simulator-sizing-protection-logs-2026-11-07.tar.gz`. Earlier failures/originals remain. Retained tools: `tools/heat-gui-renders/`, `tools/simulator-sizing/`; no test-only production code added.
- Existing scientific lane, campaign and chain references stand: no physics changed and no second full lane ran. Main remains `f1db3a892`; owner live acceptance and merge instruction remain pending.
