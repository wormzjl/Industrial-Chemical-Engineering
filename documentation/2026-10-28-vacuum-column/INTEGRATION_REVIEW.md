# Integration into column-operation — 2026-10-29

Target: `claude/column-operation-5c3005` at `61955af2`. Source: `codex/vacuum-column-test` at `2331e649` (production `dba4ba45`, tools `3509f67a`). Main/version are outside this merge.

## Expected behaviour

The operating column retains its scheduling, ports, accounts and default native PR outer pass while acquiring the tested dry upper stages, phase-admission checks and caloric continuation. The merge retains every batch's documentation and leaves unresolved scientific/operation findings recorded.

## Integration

- Prepared on `codex/column-vacuum-integration` in the vacuum task's own worktree, based on the exact target head; merge both histories without rewriting either source branch.
- Production and test sources merged cleanly. Conflicts were limited to `CHANGELOG.md` and the code-guide README/column-v3 snapshot summaries; retain both native-default and vacuum changes. Index rows from both branches survive.
- The guide identifies the combined source snapshots (`61955af2` and `dba4ba45`), preserves native-default ownership and determinism notes, and includes the dry-stage contracts. Existing Java/native fallback choices remain in place.
- Investigation harnesses stay under `tools/vacuum-column-test/`; trimmed a trailing blank line in its fixture probe. No product probe, Gradle switch or gate-suite change was introduced.

## Verification

- `./gradlew.bat test`: **1839 tests**, zero failures/errors/skips. `./gradlew.bat test slowGates`: quick suite up to date and **188 slow tests**, zero failures/errors/skips. Read both XML result sets and retained `gates-summary.json`.
- CMake built the packaged Windows x86-64 C++ library; the quick result records loading it from `build/resources/main/natives/windows-x86_64/`. All three `NativeColumnSolverTest` and seven `V3VacuumStartupTest` cases pass. The native default remained enabled.
- Run artifacts stay in indexed `tmp/column-merge/`. The existing 1,000-case VDU/CDU screens and 200-case revision comparison remain at their reported revisions; no repeat campaign or new in-game check is claimed.
- The detected main-checkout dev client had exited before tests began; the owner authorized closing it for these suites. No other worktree's process or saved world was changed.

## Delivery and limits

- Delivered merge **`baca269b`** to `claude/column-operation-5c3005` by guarded fast-forward from the clean `61955af2` checkout. Both source/target ancestries and the clean result were verified; no reset or discarded work.
- This is a feature-branch integration. Keep the batch In progress until it reaches main; no version bump or release heading.
- Existing limits remain: broad VDU convergence, heater-pressure/enthalpy coupling, general dry middle sections, packing hydraulics; column-operation's recorded fluid-valve holds and open in-game gates are not resolved by this merge.
- Preserve all unmerged scratch outputs and downloaded tools. No worktree or branch cleanup was requested.
