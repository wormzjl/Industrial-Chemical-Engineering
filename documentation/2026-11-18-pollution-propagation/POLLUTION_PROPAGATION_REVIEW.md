# Pollution propagation: review

Batch `2026-11-18-pollution-propagation`, branch `claude/water-intake-outfall-0e90c8`. Commits: P1 `4841ec5cb` (GameTest seeds `4ebf4ee66`), bench `72993755a`, `a92f7d91f`, `d9b274413`, P2 speed `1e64ee2fd`, P2 memory and the two B items `4ee803129`, `41cf4a697`. Plan and numbers: [POLLUTION_PROPAGATION_PLAN.md](POLLUTION_PROPAGATION_PLAN.md).

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
- The server thread's sets and the ground step: both B items built on the main thread's call (below).
- **The save's compound entries** (41 bytes each, a tag of column, name and moles): A leave (built); B columnar arrays (a name table; column, name index and moles arrays: about 20 bytes an entry, 1.0 MB less at 16 384 columns with three compounds, before compression). A format change: schema 3, owner's call.

## P2, memory (owner: "pollution system ram usage matters as well, make sure you optimize them")

Method: `tools/pollution-bench/Retained.java` walks each object graph with reflection and sums the HotSpot 64-bit layout with compressed oops (a 12-byte header and the fields, an array 16 bytes and its elements, each rounded up to 8; an object reached twice counts once). Allocated bytes are the thread's allocation counter around one call. The service's caches need Minecraft, so before the change the tool built the same structure (`HashMap<Long, record(Cell, int)>`, a new `Cell` per read, as `PollutionService` did); after it, the real `CellCache` and `WaterCache`. The save: `PollutionStoreSizeTest` writes the store with `NbtIo`.

### Measured, per entry

| State | Before | After | Change |
|---|---|---|---|
| Air field (`PollutionField`) | 40.0 B a cube | 40.0 | none needed: two flat arrays |
| Cell cache (`PollutionService`, one entry per wanted cube) | 110 B | 16 B | `CellCache`: sorted key, cell and tick arrays; one shared `Cell` per (air count, open) |
| Water cache (one entry per wanted column) | 111 B | 13 B | `WaterCache`: sorted arrays |
| Ground field, 3 compounds a column, after a load | 232 B a column | 76 B | one `String` per compound name (a load read a new one per entry: 52 B each) |
| Ground snapshot | 12 B a column | 12 | none needed |
| Client map store at its cap | 16 B a column | 16 | none needed (4 096 columns, 66 kB) |
| Per pass, `core()` and `wanted()` | 247 B a wanted cube | 77 B | `KeySets`: sorted `long[]`, the face neighbours by merging shifted copies, no boxing, no general sort |
| Per pass, the air environment | 120 B a wanted cube | 29 B | `CellCache.snapshot`: an array environment over the wanted keys |
| Per pass, `groundWanted()` | 194 B a wanted column | 79 B | `KeySets.columnFaces` |
| Per pass, the ground environment | 280 B a wanted column | 42 B | `WaterCache.snapshot`, `GroundEnvironment.sorted` |
| Save, `icheme_pollution.dat` before compression | 40 B a cube, 16 B a column, 41 B a compound entry | the same | no format change (owner item above) |

### Worst case at the caps

8 192 cubes with every cube alone (57 344 wanted cubes), 16 384 ground columns with three compounds each (81 920 wanted columns), one watcher's map answer (4 096 columns):

| | Before | After |
|---|---|---|
| Retained: air field 0.33 MB, cell cache, ground field, ground snapshot 0.20 MB, water cache, client map 0.07 MB | 0.33 + 6.31 + 3.80 + 0.20 + 9.09 + 0.07 = 19.8 MB | 0.33 + 0.92 + 1.25 + 0.20 + 1.06 + 0.07 = 3.8 MB |
| Allocated each pass on the server thread (sets and environments) | 14.2 + 6.9 + 15.9 + 22.9 = 60 MB | 4.4 + 1.7 + 6.5 + 3.4 = 16 MB |

### Speed of the B items (60 s warm-up and 60 s measured, alone; before = the classes of `a1cfd5268` compiled apart)

| Case | Before steps/s | After steps/s | Before bytes/step | After bytes/step |
|---|---|---|---|---|
| ground-1000 | 2 130 | 10 062 | 0.69 M | 0.50 M |
| ground-4096 | 471 | 1 387 | 2.73 M | 2.02 M |
| ground-16384 | 55 | 279 | 11.81 M | 8.66 M |
| wanted-8192 (server thread: `core()` and `wanted()`) | 439 (2.28 ms) | 5 165 (0.19 ms) | 2.66 M | 0.83 M |

The ground cases use the Set-based `GroundEnvironment.of` in both runs; with the runtime's sorted environment the 16 384 case allocates 7.09 MB a step.

### Kept, and why bit-identical

- `KeySets`: the same keys in the same order as the `TreeSet`s (`KeySetsTest` against a `TreeSet` reference); the cell budget is spent in the same order (the core, then the rest, ascending).
- `CellCache` and `WaterCache`: the service's rules unchanged (`CellCacheTest`: budget order, fresh reads, an unloaded chunk keeps its read, unwanted reads forgotten); a pass reads the same cells, so the step gets the same environment.
- `GroundField.step` on sorted arrays: every sum and booking in the order of the `TreeMap` step (columns ascending, compounds by name, deposits sorted, transfers in visiting order, zero entries kept within the step as the map kept them). `GroundFieldDigestTest` holds the digest `abdc36913b706f53` taken from the `TreeMap` step (120 passes: spread, rain, water, unloaded columns, new columns, truncation and the cap).
- One `String` per name in `GroundField.of`: equal strings, no result moves. The ground ledger's `Book` finds a row by hash (the same row arrays).

### Rejected or left

- The ground step's copies per pass (the working arrays, each stage's rebuild, the output): 7 to 9 MB a pass at 16 384 columns, on the worker, garbage at once. Fewer copies would merge stages; not done.
- Interning the compound names globally: one `String` per name per field is enough (0.3 kB for six names).
- The map answer per watcher (64 kB at its cap) and the client store (66 kB): small.

## Tests

- New: `PollutionDriftTest` (8): one-step shares, centre of mass and the horizontal moments, a closed cube above and the top, the 1/7 cap, the settings' range, the hinted search, the two digests.
- New with the memory work: `KeySetsTest` (3), `CellCacheTest` (5), `GroundFieldDigestTest` (1), `PollutionStoreSizeTest` (1).
- Runs (classes from `affected.mjs select`, 4 workers): after P1, 161 classes, 577 tests, two failures (the moved references above), then `PollutionFieldTest` green (16); after P2, 24 classes, 115 tests green, then `PollutionDriftTest` green (8); after the memory work, 27 classes, 134 tests green, then 28 classes, 126 tests green. Fluid GameTests `pollution-memory-1`: three failures from P1 (below), `pollution-memory-2`: all 125 passed. No dev client was running; no run was held back.
- GameTests moved by P1 (they had not run after it): a pass now keeps 0.16 of an isolated cube instead of 0.28, so three seeds that stood just above a threshold after one pass fell below it. New seeds keep the old margins: the monitor 5 → 9 (1.337 → 1.375 after the pass), the effects test 3 → 5 a cube (1.60 → 1.53), the blast block 1.3 → 1.5 (the cube below 1.092 → 1.089, the faces 1.09 → 1.26).
