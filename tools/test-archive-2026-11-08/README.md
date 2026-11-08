# Test archive 2026-11-08

Purpose: the test code removed by the consolidation review of `codex/simulator-sizing`, with source copies and a
re-attach patch. The review read the 144 test methods the branch added against the bar of
`documentation/2026-10-21-test-review/CRITERIA.md`. A test stays when it is the only guard of conservation, a format,
an owner rule, a reference, determinism or a core contract. The review is
`documentation/2026-11-06-simulator-sizing/CONSOLIDATION_REVIEW.md`.

Batch: `2026-11-06-simulator-sizing`.

Removing commit: `da982f268` (branch `claude/simulator-sizing-refactor-6bacab`, parent `df24eb6ac`).

## Contents

- `sources/`: the 7 files the commit changed under `src/`, as they were at `da982f268^`.
- `reattach-src.patch`: `git diff da982f268 da982f268^ -- src`; restores all of it.

## How to run: re-attach

From a worktree root at `da982f268` or a descendant that has not touched these files:

    git apply --check tools/test-archive-2026-11-08/reattach-src.patch && git apply tools/test-archive-2026-11-08/reattach-src.patch

To bring back one method, copy it from `sources/` (or `git show da982f268^:<path>`); the two agree.

## What was archived

| Class | Item | Criterion | Kept by |
|---|---|---|---|
| `ControlSystemTest` | `removingOneCableBreaksTheBusButKeepsRegisteredPorts` | A2 | `operatorVisibilityContainsOnlyHostsOnItsDataBus` (same cable removed, bus empty, port kept), `wiringIndexFollowsCableEditsWithoutLosingPortFacts` |
| `PowerGridTest` | `anUnpoweredBusDoesNotProbeEveryLoadForEveryGenerator` | A3 | Lost: the FE probe call counts (32 extracts, 0 receive probes on an unpowered bus). It pinned the probe order of the cable-network cache (`beb5d9ba3`), not a result |
| `ConduitGeometryTest` | `fixedPowerDiameterRetainsThePipeFaceCoordinates`, trimmed and renamed `everyMediumBuildsWellFormedStraightElbowAndTeeMeshes` | A3 | Since `df24eb6ac` both media read `CableMedium.OUTSIDE`, so the diameter and face-point equalities hold by construction; `branchingAnOffsetLineKeepsEveryBuiltFaceConnectedToItsBody` still fails if the 0.125 m size changes (its 0.0625 m radius). The mesh checks stay |
| `ConduitGeometryTest` | `SERVICE_PORT_SWEEP` print and its three counters | A1 | Print only; the assertions stay |
| `RenderDetailTest` | `render_detail` print | A1 | Print only |
| `CrossflowHeatTest` | `CROSSFLOW_SWEEP` print and its three counters | A1 | Print only; the 2.5 % grid assertion stays |
| `CrossflowSizingTest` | `CROSSFLOW_REQUIRED` print | A1 | Print only |
| `EquipmentServicesTest` | `native_instrument_finish_profiles` print and its counter | A1 | Print only |

## Kept although they read like pins

- `LineContractTest.domainAdaptersCannotRegainCommandOrRouteOwnership`, `LineToolSessionTest.nativeAdaptersCannot…`:
  source-text guards. Each is the only guard of the owner's binding instruction of 2026-11-07 that data and power use
  the pipe placement framework end to end (K3).
- `KitLayoutTest.productionScreensNeverOverrideThePlayerGuiScale`: a source-text guard of the owner's instruction of
  2026-11-07 to preserve the player's GUI scale (K3).
- `LineInteractionTest.pipeAndConduitShapesShareTheSameCachedEnvelope`: its `assertSame` is the only check that a
  one-ended piece's collision completes along its facing (`LineSelection.joints`) (K6).

## History

- 2026-11-08: created by `claude/simulator-sizing-refactor-6bacab` (removing commit `da982f268`).
