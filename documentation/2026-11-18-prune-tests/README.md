# Pruning tests that guard no behaviour (2026-11-18)

Status: Implemented 2026-11-18. Branch `claude/prune-tests-97440b`, base `7d7a64e72` (main 0.35.2).

The owner ran `/prune-tests` (main thread only, owner 2026-11-18): delete the tests that guard the past, nothing, or the
implementation, and duplicates. This pass is a grep sweep of `src/test` and `src/fluidGameTest` (815 files); it did not
read every test.

## History

- 2026-11-18: sweep and prune (`7b9ba01c4`): 4 tests and 4 assertions deleted, no `src/main` change. `testClasses`
  compiles; the two classes with an edited test pass (`PollutionDataTest` 11, `RespiratorDataTest` 6; 0 failures). A
  removed test needs only the compile (`AGENTS.md`). No final lane: the change reaches only tests (the owner's rule for a
  test-only batch, 2026-11-16). Merge to `main` (owner, 2026-11-18: "Merge into main").

## Documents

- [PRUNE_TESTS_REVIEW.md](PRUNE_TESTS_REVIEW.md): what was deleted and why, what was kept and why, what the sweep did not cover.

## Code areas

- `src/test/java/com/wormzjl/icheme/world/level/block/LineContractTest.java`, `client/LineToolSessionTest.java`,
  `client/gui/flowsheet/SimulatorBlockTest.java`, `runtime/pollution/RespiratorDataTest.java`, `runtime/pollution/PollutionDataTest.java`.
