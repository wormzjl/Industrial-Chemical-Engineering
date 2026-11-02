# Merge review: column operation with its neighbours (2026-11-02)

Batches in this merge: `2026-10-28-column-operation`, `2026-11-02-valve-hold`, `2026-10-28-native-column-default`, the vacuum column test. Branch `claude/column-operation-5c3005`. Version 0.18.0 on the owner's word to merge. Main moves by the orchestrator; nothing is pushed.

## Tool folders

- `tools/column-operation/`: the C1 bench, its results and the probes.
- `tools/native-column-default/`: the native library probes.
- `tools/vacuum-column-test/`: the vacuum column runs and their analysis.
- `tools/valve-hold-probes/`: the hold trace, the dump patch and the final lane.

## Gates

- Final lane: [FINAL_LANE_REVIEW.md](../2026-11-02-valve-hold/FINAL_LANE_REVIEW.md), on this branch at 90d3341fa, code as of 321b8d1d3. It replaces the lane of a89294fef. Base main 3ffeb0519; its `src/main` has not moved.
- Gates after the cleanup: `test slowGates` 2065 tests, 0 failures; chain-100 deviation 0; fluid GameTests 38 of 38; campaign 975 / 993 / 1000 against 975 / 993 / 1000 once six budget holds are replayed, `refused=0`. The review round added no solver work on the traced worlds.
- Review round: [MERGE_CODE_REVIEW.md](MERGE_CODE_REVIEW.md), one disposition per finding. Affected tests only, by the lean rule: 516 tests over the column, facts, checkpoint, island, valve and control classes on 88600d64d; fluid GameTests 38 of 38 on 0ada03253, before finding 1. Finding 5's energy on 27faf5da9: 253 tests over the column, facts and checkpoint classes.

## Formats

- Fluid facts schema 6, unreleased: `suspendedBy`, the column units, `condenser`, `leaving`, a station's `solids`. Corpus `compat/fluid/schema-6/` regenerated: a nonzero `condenser`, a station with solids.
- State format 29: `ColumnUnit.State` in the topology unit. A credit's solids and a gift are not saved.
- Shape fields 4: the operating point on a column design. Corpus `shapes-4.snbt`.
- Wire: the column design payload gained the operating point: wire schema 18, protocol "14". No compatibility work is owed.

## Open items for the owner

1. Dry ice cases: cold LNG and CO2-ice worlds sit near the 2 s wall budget and hold on a slow machine: seeds 183, 555, 673, 768. Alone, one worker, main against head: 183 147 s and 118 s, 555 153 s and 150 s, 673 58 s and 58 s, 768 111 s and 110 s, 0 holds on both. Identical work on 183 took 115 to 219 s today. The owner takes it to another task.

Findings 1 and 5 are the owner's, decided and built, "Lower target wins" and option A: `MERGE_CODE_REVIEW.md`. Version: 0.18.0, a new feature and a solver batch.

Decided by the orchestrator; the owner can overrule them:

- Condenser approach: left at 5 K. At 30 C supply it leaves 24 K of a 27 K rise and the column limits after about 5.5 min: water too short to take the duty should limit the column. The review plant's water is 25 C.
- Wire protocol: bumped, the payload changed.
- Vacuum column outputs, 261 MB, stay in the codex worktree's `tmp/` with no log archive. That is not this worktree; the `tmp/` cleanup rule covers them after the merge.

## At the merge

- `sync_with_base_branch` refused (the pinned origin), so main came in by a plain merge, 8aca08f99. Only `documentation/INDEX.md` conflicted, and every row of both sides stays.
- Main's delta holds no code: `git diff 321b8d1d3 HEAD -- src build.gradle` is empty, so the lane stands. `documentation/` holds no file over 1 MB.
- `git ls-files -i -c --exclude-standard` is empty.
- `ColumnOperationGameTests` runs in the fluid lane; the review plant stays a hand review (`runReviewWorldServer`).

## Lower, not in this merge

- Finding 16: `EquipmentStructure.at` may sync-load a neighbouring chunk; cycles keep running during a property suspension.
- Plan against code, from the guide: `ScheduledTransfer` has no producer; port pressures stay the `PortConditions` estimate; `Read.column` gives back instead of W9/DUTY; the warm seed is not saved.
- Cold 431 and 475 may move with findings 7(a) and 7(b): the lane's campaign reads them.

Code guide: `column-operation` (new), `fluid-science`, `fluid-runtime`, `verification`, `equipment`, `machine-assemblies`.
