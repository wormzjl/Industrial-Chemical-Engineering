# Pollution propagation: review

Batch `2026-11-18-pollution-propagation`, branch `claude/water-intake-outfall-0e90c8`. Commits: P1 `4841ec5cb`, bench `72993755a` and `a92f7d91f`, P2 `1e64ee2fd`. Plan and numbers: [POLLUTION_PROPAGATION_PLAN.md](POLLUTION_PROPAGATION_PLAN.md).

## Summary

- Polluted air tends to rise. Each open, loaded cube drifts `r · min(φa, φb) / φa` of its amounts to the cube above per pass, `r = min(1/7, 0.12 · dt / 20 s)`, on top of the six-face exchange, which is unchanged. The horizontal spread is the same as before (owner).
- One step from one cube: above 0.24, each side and below 0.12, the source 0.16 (`PollutionDriftTest`, exact to 1e-12).
- Steady plume of 1 mol/s H2S: the source 0.895 → 0.594, a side neighbour 0.490 → 0.368, above 0.490 → 0.665, below 0.490 → 0.333; the centre of mass 2.5 cubes (40 m) above the source.
- `riseShare` (`PollutionSettings`, last field; config `pollution.riseShare`, 0 to 0.5, default 0.12). At 0 the field is bit for bit the field of before the drift: the stored digest `6450680631e3021f` was taken from the compiled classes of `65fee5618`.
- `PollutionField.step` is 1.3 to 3.1 times faster with the same bits. The default digest, the rise-0 digest and the bench's three air digests are unchanged.
- Conservation: the drift moves amounts between cubes or books them `dissipated` at the top; every ledger test closes to 1e-9 or better.

## Moved references

| Reference | Old | New | Bound | Why |
|---|---|---|---|---|
| `PollutionFieldTest.theTopAndTheBottomAreSinks`, toxic at the top | 0.12 × amount | 0.24 × amount | 1e-12 | The top face takes its face share and the drift; the bottom (combustible) stays 0.12 |
| `PollutionFieldTest.theSteadyH2sPlumeMatchesThePlanNumbers` | default settings | riseShare 0, same bounds (0.8 to 1.0, 0.4 to 0.6, 0.1 to 0.2) | as before | The plan's section 4 numbers are those of the field without the drift; held exactly |
| new `theSteadyH2sPlumeRisesWithTheDrift` | | 0.594, 0.368, 0.665, 0.333 | ± 0.01 | The plume with the drift |

## Before and after (P2)

Timed runs of 60 s warm-up and 60 s measured, alone on the machine, `tools/pollution-bench/Bench.java`. "Before" is P1's step (`4841ec5cb`).

| Case | Before steps/s | After steps/s | Ratio | Before bytes/step | After bytes/step |
|---|---|---|---|---|---|
| air-512 (512 cubes, front 473) | 13 649 | 18 218 | 1.33 | 395 k | 328 k |
| air-4096 (front 1 802) | 932 | 2 914 | 3.13 | 2.14 M | 1.90 M |
| air-8192 (front 3 126, back to the cap of 8 192) | 324 | 703 | 2.17 | 4.57 M | 4.61 M |
| ground-4096 (`GroundField.step`, not changed) | 483 | | | 2.73 M | |
| wanted-8192 (server thread, not changed) | 439 | | | 2.66 M | |

At 8 192 cubes a step takes 1.4 ms on the worker, every 5 s of play.

### Kept

| Change | Profile before (samples, air-8192) | Bit-identical because |
|---|---|---|
| The front's slot map: an open-addressing `long → int` map, sized from the cube count, in place of `HashMap<Long, Integer>` | `HashMap$TreeNode.getTreeNode` 194: CubeKey's packing makes `Long.hashCode` collide, so the map's buckets turned into trees | The map only finds a slot; entries are recorded in the visiting order |
| The front's arrays sized from the cube count | growth by doubling from 64 | Sizes only |
| Neighbour search from the last neighbour's position per face (`PollutionField.search`: outward, then by halves), also for the cube below in settling | `binarySearch0` 203 | It returns what `Arrays.binarySearch` returns (`theHintedSearchAnswersAsBinarySearch`) |
| The cap's order: a merge sort of parallel arrays (peak, key, position) in place of a boxed `Integer[]` TimSort with a comparator | `TimSort` 589, the comparator 58 | The order (peak, then key) is total, the keys distinct, so every sort gives the same order, and the dropped cubes are booked in it |
| `Work.add`'s order: the rank of each added key in a sorted `long[]` copy, in place of a boxed sort | part of `Work.add` | Same total order on distinct keys |

### Rejected or left

- A quickselect of the cap's dropped cubes: the dropped cubes must still be booked in sorted order, and the parallel-array sort already brought the cap below the exchange.
- Fewer merges in `Work.add` (it copies the arrays up to three times a step: releases, front, settling): the stages read positions after each other's adds; changing that changes the structure, for about 10 % of a step.
- Cell lookups: one per cube and one per front slot a step (`counts`: 985, 5 919 and 11 318 for the three cases). Nothing is looked up twice.
- `GroundField.step` (2.07 ms a pass for 4 096 columns): its spread and compound moves go through `TreeMap` and `ArrayList` per column. Out of the brief (the air step); an owner item below.

## Counters around the runtime (from the code, not measured in a world)

- Cells built per pass (`PollutionService.environment`): every cube of `wanted()` (the field and its six faces, about 3 to 7 times the cubes) gets a map entry each pass; a section is read again only after 60 s (`CELL_TICKS` 1 200), at most 256 reads a pass (`CELL_BUDGET`).
- The server thread builds `core()` and `wanted()` as `TreeSet<Long>` each pass; at 8 192 cubes that is 2.3 ms (`wanted-8192`), once every 5 s, before the cell map. `wanted()` builds `core()` a second time.
- Entity searches per world pass (1 s, `PollutionEffects`): one `getEntitiesOfClass` per polluted cube served, at most 256 (`CUBE_BUDGET`); one per ground column with water exposure, at most 256; the ground's block picks at most 8 per column.

## Owner items

- **Which channels drift**: A all four (built); B the gases only, particulate not (it settles; a one-line test of the channel in the drift loop).
- **The server thread's sets** (2.3 ms every 5 s at the cap): A leave (built); B build `wanted` from the field's sorted keys as primitive arrays and once per pass (an estimated 5 to 10 times less; no change to any result).
- **The ground step** (2 ms a pass at 4 096 columns, on the worker): A leave (built); B flat arrays for the spread and the compound moves (bit-identical with the same summation order).

## Tests

- New: `PollutionDriftTest` (8): one-step shares, centre of mass and the horizontal moments, a closed cube above and the top, the 1/7 cap, the settings' range, the hinted search, the two digests.
- Runs (classes from `affected.mjs select`, 4 workers): after P1, 161 classes, 577 tests, two failures (the moved references above), then `PollutionFieldTest` green (16); after P2, 24 classes, 115 tests green, then `PollutionDriftTest` green (8). No dev client was running; no run was held back.
