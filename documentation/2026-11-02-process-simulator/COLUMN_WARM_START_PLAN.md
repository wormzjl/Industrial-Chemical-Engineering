# P2: the column's warm start kept in client memory (paper design)

PERFORMANCE_REVIEW.md, proposal 3. Not built: it waits for the owner's answer on P1 and P2. It changes results in their last digits (see Effect).

## Expected behaviour

- A distillation column whose feed and settings moved a little since the last steady Run is near its old profile. Newton from that profile needs a few steps; the learned seed needs the whole homotopy.
- Today `FlowsheetCompiler` builds a new `ColumnUnit.Solve` each Run. Its `warm` seed, `feedTray` and steam `stages` pressures die with the Run; only the tears carry over (`SimulatorRunner.steady`).
- Measured on the crude unit at a958cecd2 (bench `size-stages`): the Size all re-run solves T-101 from the learned seed, 4.7 s and 773k checkpoints of its 4.81 s. The trials have already solved T-101 at the chosen diameter.

## Where the seed lives

- A `ColumnSeeds` map in `SimulatorRunner`, beside `steady`: unit id → `Seed(key, V3NeuralSeed warm, double feedTray, Map<Integer, Double> stages)`. About 20 kB a column.
- It is client memory only. It is not in `FlowsheetFile`, not in the block's stored results, and not on the wire. `restore` leaves it empty, so the first Run after opening the screen starts cold, as now.
- `key` holds what makes the seed valid:
  - the column's `Params` (the door, so trays, feed stage, specs and diameter);
  - the package id and its `scientificRevision`;
  - the component axis.
- Flow:
  - `FlowsheetEngine.run` takes the map, read-only, and hands each `ColumnUnit.Solve` its entry when the key matches.
  - A Run that ends STEADY returns each Solve's last seed. The runner swaps in the new map with `steady`, as one step.
- Size all: `ColumnSizing.Trials` keeps a seed per diameter (a map in place of the one `seed` field). Size all puts the chosen diameter's seed into the map under the sized door's key, so its re-run starts T-101 warm.

## Fallback

- Keep `ColumnUnit.solve` as it is: a warm attempt (`calculateWithStrategy`, AGGRESSIVE). If that is not a Success, the learned start runs (`calculate` with the bundled model).
- A failed warm attempt costs its own iterations, then the cold path runs. The result is the cold one, and the report shows `Start.LEARNED`.
- No new mode and no new switch.

## What drops the seed

- **Stop**: `stop()` clears the map. A stopped Run publishes nothing (as `steady` today).
- **An edit to the column**: its `Params` change, the key no longer matches, and that column starts learned. The next steady Run replaces the entry. Edits to other units keep it.
- **A package revision change**: the key holds `scientificRevision`, so every entry stops matching.
- **Other ends**: a FAILED or NOT_STEADY Run leaves the map as it was. Closing the screen drops the runner and the map.

## Effect on results (owner decision)

- A warm solve converges to the same column within the solver's closure tolerance, from a different start. Its last digits differ from a cold solve's.
- Two Runs of one sheet can then print different last digits, depending on what ran before.
- Alternative: run warm only inside Size all (the trial seed), never between Runs. Run results then stay history-free.

## Code it touches

- `science/flowsheet`: FlowsheetEngine, FlowsheetCompiler, unit/ColumnUnit (`Solve`) and sizing/ColumnSizing (`Trials`, seeds by diameter).
- `client/gui/flowsheet/SimulatorRunner`.
- Tests:
  - a warm Run of the crude unit stays STEADY, with streams within the tolerance of the cold Run;
  - each drop rule clears its entry;
  - `bits` shows the cold Run unchanged.
