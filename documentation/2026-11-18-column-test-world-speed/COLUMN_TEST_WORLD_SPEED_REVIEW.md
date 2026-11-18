# Column test world speed: review

## What was slow

A new "IChemE column test" world blocks the server's start while it builds the crude unit (`ColumnTestWorld.started`). In the dev client's log of 2026-11-18 the build took 93 s from the spawn area to `column_test_world built`. The breakdown:

- 13 s sizing (`CrudeUnitPlant.review`: flowsheet runs and Size all).
- 74 s `Rig.layout`. A timed run of `ColumnTestWorldReviewWorld` split it:

| Part | Time |
|---|---|
| Line search (`PlantBuilder.route`, least cost with `BEND_COST`) | 72.2 s |
| Structures placed (14) | 1.0 s |
| Pipes placed (1341 devices) | 0.3 s |
| `Rig.finish` (sizes, valves, supplies) | 0.4 s |

The search is a Dijkstra over (block, heading) in the plant's box, which is 150 × 45 × 135 blocks. Over all lines it visited 22.3 million states, at about 3 µs each:
- Each state is a record in two `HashMap`s and a `PriorityQueue`.
- Each neighbour's `free` check makes one `getBlockState` and up to 13 `HashSet<BlockPos>` lookups.

## Change

`RouteSearch` (new, `testworld/`) runs the same two searches on a flat grid over the box and one cell round it:
- Each cell's state (in the box, air, a device, a nozzle's pipe block) is read once per search into a byte array. The level, the devices and the nozzles do not change during a search.
- The states are ints in arrays: cost, previous state, and the visited cells of the breadth-first search.
- The least-cost search's heap sifts as `java.util.PriorityQueue` does with a cost comparator. States of equal cost therefore leave in the same order. The breadth-first search keeps the FIFO order and the `Direction` order.

As a result, both searches visit the same states in the same order as before and lay the same lines. `PlantBuilder.route` calls `RouteSearch`. The old `straightRoute` and the old breadth-first loop are removed.

## Check

- One `runReviewWorldServer -PreviewBatch=review-column-test-world` run, with a temporary harness that computed every line with the old and the new least-cost search and threw on any difference. No difference. The old search took 87 s, the new one 3.4 s. All 5 required tests passed, and the column passed its first solve.
- A second run of the same kind also compared the old breadth-first search with `RouteSearch.shortest` on every line of the review plant. No difference in either search, and all 5 required tests passed.
- `affected.mjs select --since HEAD`: no unit test or gate loads the changed files. The GameTest step is due in the final lane.

No result moves: the laid plant is the same.

## Not changed

- The 13 s sizing runs every time a world is created. It depends only on the material catalog, so it could run once per catalog revision, off the server thread, before the first world is created. This is a separate change, for the owner to decide.
- The start after the build (52 s of play to the first solve) is the plant warming up on the engine's schedule, and the player can already play. Not a build cost.
