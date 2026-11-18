# Ground pollution: plan

Batch `2026-11-18-ground-pollution`, on `claude/water-intake-outfall-0e90c8` after the water intake and outfall. Approved by the owner ("Yes, queue it", 2026-11-18).

## The owner's request

"store ground pollution on a chunk basis instead of 16block cubes, and expand its function". Functions: the map and the meter, a dirty water intake, spread to the neighbours, world effects. Removal: slow decay.

## The model

- One entry per chunk column (`MapColumns.column(x, z)`), per dimension. An entry holds four channel amounts (toxic, combustible, corrosive, particulate, the air field's units) and a compound inventory (moles by compound name).
- The index of a channel is its amount over `GROUND_REFERENCE`.
- Deposits come from two places:
  - the air field's settling at its floor (`PollutionField.settle` now returns the deposits by column, inside the pass);
  - the spill law (`Releases.spill`, through `Sink.deposit` and `PollutionService.deposit`): the waste outfall, an open spill, a destroyed device's liquid; its non-volatile part, with its solids.
- One pass, every engine pollution pass, in this order: land, spread, decay, truncate, cap.
- Ledger per channel and per compound: deposited = stored + decayed + drawn + capped.

## Constants

| Constant | Value | Where | Basis |
|---|---|---|---|
| `GROUND_REFERENCE` | 100 × `CUBE_AIR_MOLES` = 1.674e7 | `GroundField` | A column's ground stands at index 1 when it holds what 100 cubes of air at index 1 settle |
| `nominalSeconds` | 20 s | `GroundSettings` | The air field's nominal pass |
| `spreadShare` | 5e-4 per face and 20 s | `GroundSettings` | Slow seepage: a steady source's next column reaches about 0.21 of the source |
| `MAXIMUM_FACE_SHARE` | 0.2 | `GroundSettings` | Stability bound for four faces |
| `wetSpread` | 4 | `GroundSettings` | Rain moves the ground faster |
| `waterSpread` | 4 | `GroundSettings` | A face where either column's surface is mostly water |
| `halfLifeSeconds` | 14 400 s (4 h) | `GroundSettings` | Slow decay |
| `washout` | 4 | `GroundSettings` | Rain divides the half-life |
| `cutoff` | 1e-4 index | `GroundSettings` | Truncation, booked as decayed |
| `maximumColumns` | 16 384 | `GroundSettings` | Cap; the lowest peak index leaves first, booked as capped |
| `CELL_BUDGET` | 256 columns a pass | `PollutionService` | Surface water reads, cached |
| Surface water | at least 9 of 16 samples | `PollutionService.surfaceWater` | "Mostly water" |
| `GROWTH_LIMIT` | 0.3 (`Exposure.MEDIUM`) | `GroundEffects` | Growth stops at or above this worst index |
| Snapshot byte | steps of 0.02, 255 = 5.1 or more | `MapColumns.quantise` | The air map's byte |
| W (G5) | 256 × 4 × 55 500 = 5.68e7 mol | `GroundField.draw` argument | The water of a column 4 m deep |

## Steps

- G1: `GroundField`, `GroundSettings`, `GroundLedger`, `GroundEnvironment`; `PollutionField.settle` returns its deposits.
- G2: the engine pass carries the ground; `PollutionStore` schema 2 (append-only); the published snapshot `GroundSnapshot` (column → 4 bytes); one spill law in `Releases`.
- G3: the map answer carries the ground bytes (display only, no protocol number); the layer (`PollutionMapStore.Layer`); the meter's ground lines; the ledger command's ground lines.
- G4: growth (`GroundGrowth`, config `pollution.groundGrowth`) and water (`PollutionEffects.water`, config `pollution.groundWater`). Tint: options to the main thread.
- G5: the dirty water intake: the draw law is built (`GroundField.draw`); the delivery is held (stop rule, see the review).
