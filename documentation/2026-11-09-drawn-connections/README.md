# 2026-11-09-drawn-connections

Purpose: every drawn pipe joint is a joint of the network. The block state is a projection of the registry through the as-built join rule of `PhysicalFluidTopology`. The batch started from the owner's question whether a pipe or cable can look joined and not be, after version changes.

Status: Implemented 2026-11-09, released as 0.23.4 (branch `claude/drawn-connections`, from main `f1697692c`, main 0.23.3 merged in). Not pushed. Index: [../INDEX.md](../INDEX.md).

## Documents

- [DRAWN_CONNECTIONS_REVIEW.md](DRAWN_CONNECTIONS_REVIEW.md): the four splits found, the fix, the owner decisions and the verification.

## Code areas

- `runtime/fluid/PhysicalFluidTopology.java`: `builtFaces`.
- `runtime/fluid/FluidWorldAuthority.java`: `drawnFaces`, `placedFaces`.
- `world/level/block/FluidDeviceBlock.java`: `connectedState`, `withFaces`, `getStateForPlacement`, `updateShape`, `InlineMover.of`; the block-state join rules removed.
- `world/level/block/entity/FluidDeviceBlockEntity.java`: `refreshGeometry` (the fitting from `hydraulicKind`, the faces for every kind).
- `world/level/block/entity/PipeRackBlockEntity.java`: `refreshDrawn`.
- Tests: `PhysicalFluidTopologyTest` (four new), `ChunkLoadGameTests` (D1 rewritten), `DrawnJointGameTests` (new).
- Code guide: `fluid-runtime.md`, `pipe-laying.md`, `verification.md`, `equipment.md`.
- Tools: none created; the focused GameTest runner is `tools/simulator-sizing/`'s.

## History

- 2026-11-09: the owner's question; the audit found four splits (review section 2). The owner chose option A, structural, on the main thread.
- 2026-11-09: option A, `8680edbb6`. Targeted runs: two unit classes and 11 focused GameTests passed (review section 5).
- 2026-11-09: the owner's "merge and run only affected test, no full tests". Main 0.23.3 merged into the branch (`fc8f928e5`; conflicts only in `CHANGELOG.md` and `documentation/INDEX.md`, every entry and row kept). On the merged tree the same runs passed again: `PhysicalFluidTopologyTest` 33, `PipeLineCompatTest` 8, 11 of 11 GameTests. No final lane, on the owner's word. Code guide updated; merged into main as 0.23.4 (patch: fixes only).

## Open items

- Owner decisions 1 to 3 in the review: ghost registrations, the legacy `Pump` law for a block placed in a pump state, client prediction at placement.
- An in-game check only on the owner's request.
