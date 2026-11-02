# Merge review: column operation with its neighbours (2026-11-02)

Batches in this merge: `2026-10-28-column-operation`, `2026-11-02-valve-hold`, `2026-10-28-native-column-default`, the vacuum column test. Branch `claude/column-operation-5c3005`. Nothing is merged, pushed or bumped: the owner gives the word and the version.

## Tool folders

- `tools/column-operation/`: the C1 bench, its results and the probes.
- `tools/native-column-default/`: the native library probes.
- `tools/vacuum-column-test/`: the vacuum column runs and their analysis.
- `tools/valve-hold-probes/`: the hold trace, the dump patch and the final lane.

## Gates

- Final lane: [FINAL_LANE_REVIEW.md](../2026-11-02-valve-hold/FINAL_LANE_REVIEW.md), a scratch merge at a89294fef. Superseded: solver code changed in the review round, so one lane reruns on the final commit and replaces it.
- Gates after the cleanup: to fill in from that lane.
- Review round: [MERGE_CODE_REVIEW.md](MERGE_CODE_REVIEW.md), one disposition per finding. Affected tests only, by the lean rule: 516 tests over the column, facts, checkpoint, island, valve and control classes on 88600d64d; fluid GameTests 38 of 38 on 0ada03253, before finding 1. Finding 5's energy on 27faf5da9: 253 tests over the column, facts and checkpoint classes.

## Formats

- Fluid facts schema 6, unreleased: `suspendedBy`, the column units, `condenser`, `leaving`, a station's `solids`. Corpus `compat/fluid/schema-6/` regenerated: a nonzero `condenser`, a station with solids.
- State format 29: `ColumnUnit.State` in the topology unit. A credit's solids and a gift are not saved.
- Shape fields 4: the operating point on a column design. Corpus `shapes-4.snbt`.
- Wire: the column design payload gained the operating point with no bump of wire schema 17 or protocol "13". No compatibility work is owed; see item 2.

## Open items for the owner

1. Condenser approach: at 30 C supply the 5 K approach leaves 24 K of a 27 K rise, and the column limits after about 5.5 min. The review plant's water is 25 C. Change the approach, the water rate, or leave it.
2. Wire protocol: bump schema 17 and protocol "13" at this merge, or not. Proposal: bump, since the payload changed.
3. Version: `mod_version` stays. Proposal: 0.18.0, a new feature and a solver batch.
4. Vacuum column outputs, 261 MB, sit in the codex worktree's `tmp/` with no log archive. Keep or drop them.

Findings 1 and 5 are decided and built, "Lower target wins" and option A: `MERGE_CODE_REVIEW.md`.

## At the merge

- `sync_with_base_branch` refused (the pinned origin). As told, main was not merged in here; only `documentation/INDEX.md` conflicts, and every row of both sides stays.
- `documentation/` on this branch's head holds 51 files over 1 MB, none added here; main holds none, so they clear at the merge.
- `git ls-files -i -c --exclude-standard` is empty.
- `ColumnOperationGameTests` runs in the fluid lane; the review plant stays a hand review (`runReviewWorldServer`).

## Lower, not in this merge

- Finding 16: `EquipmentStructure.at` may sync-load a neighbouring chunk; cycles keep running during a property suspension.
- Plan against code, from the guide: `ScheduledTransfer` has no producer; port pressures stay the `PortConditions` estimate; `Read.column` gives back instead of W9/DUTY; the warm seed is not saved; `DriftBands.Trigger.PACKAGE` is unused; `PortConditions` uses top + stage × drop where the plan has (j − 1) × drop.
- Stale comments: `AssemblyScreen` placeholder, `Device.withSuspendedBy` parameter, `ColumnUnit.MAXIMUM_STATIONS`, `Port` "constant-pressure buffers", `ColumnOperation` "specific enthalpy".
- Two broken `tools/` links in `verification.md` predate this batch.
- Cold 431 and 475 may move with findings 7(a) and 7(b): the lane's campaign reads them.

Code guide: `column-operation` (new), `fluid-science`, `fluid-runtime`, `verification`, `equipment`, `machine-assemblies`.
