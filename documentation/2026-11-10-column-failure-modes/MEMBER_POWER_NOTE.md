# Member power: how members are booked today, and the smallest change (stage 4, W5)

Written 2026-11-10 before the code of stage 4 ([EXECUTION_PLAN.md](EXECUTION_PLAN.md) section 6).

## Today

- **Stores.** `ElectricStores` keeps one store per powered unit, keyed by its registration: an air cooler's process side (kind `FANS`), an in-line mover (`INLINE`), a motor or generator shaft unit (`MOTOR`, `GENERATOR`), burners, a tower, a still. `sync` makes one for every active powered registration, held or not, so a running column's air-cooler banks and in-line pumps already have stores.
- **Who books.** Fans and in-line movers book at their island's commit (`ElectricStores.booked`, from the solved graph). A motor books at its train's step (`ShaftTrains.step` → `ElectricStores.draw`), from the shaft power its driven casings booked at their islands' commits (`ShaftTrains.booked` → `Run.power`). The cadence books a unit at rest at its last power, stops one that ran short, restarts one whose store refilled (`restart`), and hands the facts to the ledger.
- **A running column.** Its members are held (`Device.suspendedBy`): in no island, so nothing books. The column computes `r.fans` and `r.fanPower` for its status only. A held casing's train keeps the shaft power of its last island booking and books it at rest; a held in-line pump and a held fan bank book their last power at rest. Nothing stops when a store is empty.

## The smallest change

1. **Members, a fact.** The column must know which stores and casings are its members, and which member each is: a pumparound cooler may be an air cooler with fans whose duty the column does not run, so the held set cannot say it. `ColumnUnit.members` is appended at fluid facts schema 18: the air condenser's fan sides (the `CONDENSER` slot's air-cooler process sides), the reflux pump's mover registrations, each pumparound pump's. **Default: none**, a unit saved before it draws nothing for its members until its next Start (the behaviour before). Corpus image `src/test/resources/compat/fluid/schema-18/`. `ColumnStarts` collects them; `startColumn` keeps those it holds.
2. **The cycle books, then reads.** At each cycle, before the step, the column books the span that just ran (the last cycle's share) on each member, then reads whether the member has power for the next one:
   - fan side *i*: `ElectricStores.draw(i, fanDraw(heat_i, last fans), now)`, then `powered(i)`;
   - in-line pump: `draw(id, InlineMachines.electricPower(ratedShaftPower × last share), now)`, then `powered(id)`;
   - casing pump: `ShaftTrains.held(device, ratedShaftPower × last share)` puts the casing's shaft power into its train's `Run.power` and steps the train once at this tick (the guard `stepped == now` stays); the motor draws from its own store as it does now. It gives power when the train has no fault, its driver turns and nothing tripped (`NO_POWER` included).
   - `ratedShaftPower` is the in-line rating's shaft part (`InlineMachines.ratedDraw` split in two, its arithmetic unchanged): the law at its rated flow, speed 1, at 1000 kg/m³. A hydrocarbon at 700 kg/m³ books about 1.4× its shaft power; a game-level rating, no new physics.
3. **The step.** `ColumnCycle.Rooms` gains the member verdicts. Unpowered fan banks hold their surface at natural draught: the air's most feed is `S_powered / airArea[8] + S_unpowered / airArea[0]` (exact in the table's linear model); where that limits, the LIMIT is `NO_POWER` with member index 0 (the condenser) in place of `COOLING_SHORT`. An unpowered pump is `NO_POWER` with share 0 and its member index (1 the reflux pump, 2 + j pumparound j's). The status line reads `LIMITED: power, N %`; the alarm `NO POWER` exists. With every member powered the step is byte-identical.
4. **Restart.** No new path: the cadence's `restart` clears a refilled store; the next cycle reads it powered and the feed resumes.
5. **Config.** With `energy.machinesNeedPower` off the column books nothing on its members and reads every member powered: nothing changes.
6. **No driver at Start.** With it on, a member casing with no train driver (`ShaftTrains.driverOf` null) refuses Start: `Refusal.NO_DRIVER`, `the <member> has no driver` (step 4, owner may veto: one check in `startColumn`). An in-line pump carries its own motor.

No new unknown or equation in `PassiveStepSolver`; no island is touched; nothing runs on the tick.
