# Column test world: faster build

Branch `claude/column-test-world-perf-96ee33`. The owner found the creation of a column test world slow.

## Result

The plant's build on a new world went from 74 s to about 5 s of the server's start. The lines it lays are the same, block for block.

| Part of a new world's start | Before | After |
|---|---|---|
| Sizing (flowsheet runs and Size all) | 13 s | 13 s (not changed) |
| Line search | 72 s | 3.4 s |
| Structures, pipes, valves, supplies | 1.7 s | 1.7 s |
| The start on the tick (valves to first solve, while the player plays) | 52 s | not changed |

Details and the check: [COLUMN_TEST_WORLD_SPEED_REVIEW.md](COLUMN_TEST_WORLD_SPEED_REVIEW.md).

## History

- 2026-11-18: build timed (the line search 72 of 74 s, 22.3 million search states); `RouteSearch` built; every line of the review plant compared with the old searches in two GameTest runs (least-cost and breadth-first, no difference); the old search removed.
