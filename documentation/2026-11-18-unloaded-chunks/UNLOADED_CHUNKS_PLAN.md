# Unloaded chunks: plan

Owner, 2026-11-18: "everything should function in unloaded chunks".

Owner decisions on this plan require existing plants to work after a cold restart before a player visits, a metered motor to stop on lost live supply while retaining its charge, solid-fired stills and pollution-triggered plant damage to continue, and external corrosion to follow its configuration. Beacons must clear and re-arm while unloaded. The implementation reuses the existing saved structure geometry and operating point for C1. The new fluid fact is the solid still's item inventory.

## 1. What happens today

Measured, the owner's test save (`run/saves/New World`), run on the headless review server, no player:

- The burners of both heaters went `NO POWER` at tick 2000 and 2100. They stayed out to the end (tick 6000), and the column went UPSET.
- In the same run with every plant chunk force-loaded (`tmp/column-soak/save3`) there was no `NO POWER` at all.

The fluid engine runs every island whatever the chunks do. The parts below go through block entities, capabilities or block states. They stop, or decide nothing, when the chunk is not loaded. All of these were read in code:

| # | Part | Where | Effect in an unloaded chunk |
|---|---|---|---|
| A1 | Creative power source | `CreativePowerBlockEntity.tick` pushes FE to adjacent capabilities each tick | The source stops. Each consumer runs on its store, then `NO POWER`. This is the save's failure: the save holds two creative sources, at 48, -59, 44 and 89, -59, 44 (read from its region file). They sit in chunks 3, 2 and 5, 2, outside the spawn chunk radius of 2 (`level.dat`). |
| A2 | FE over power cables | `PowerGrid.tick` → `PowerNetwork.energy` (`hasChunkAt`, `getCapability`) | No transfer to or from any terminal whose chunk is not loaded, the mod's own generators and consumers included. |
| A3 | A generator straight into a neighbour | `FluidWorldAuthority` ~1700 (`electric.charged()`, `isLoaded`, `getCapability`) | The generator's output stops; its store fills. |
| A4 | Motor supply check | `PowerNetwork.supplied` (`hasChunkAt`, `EquipmentStructure.at`) | It returns false, so `ElectricStores.disconnect` stops a motor that still has a supply. |
| C1 | Column design read | `FluidWorldAuthority.columnDesign` / `columnCondenser` → `columnVessel` (`isLoaded`) | `ColumnOperation.request` gets no design. With no table it shows `SOLVE_REFUSED: no operating point`. With a table it can never solve again (no new set point, drift or start). The condenser reads as `EXCHANGER` whatever was built. |
| C2 | Column member control inputs | `ControlHosts.refreshColumnMembers` (`hasChunkAt` per member) | A member in an unloaded chunk leaves the rebuilt input list while the column's own cell is loaded. |
| C3 | Motor-to-controller binding | `FluidWorldAuthority` ~1665, every 100 ticks (`hasChunkAt`) | It does not re-bind. Whether a saved binding still drives the motor is not checked yet (step 1). |
| C4 | Alarm beacons | `ControlAlarms.run` (`hasChunkAt`) | It does not decide. Whether any control reads a beacon's state is not checked yet (step 1). |

These need a player nearby and are fine as they are: displays and views (`Displays`, `ViewBuilder`, `present`, `drawn`), instrument fitting (`InstrumentService` pending commits), assembly, weeping marks, item drops, breach checks, the Create coupling's rpm output.

Foreign blocks cannot run in an unloaded chunk: another mod's FE source, a Create kinetic source. Their block entities do not tick. That is outside the mod (decision D1).

## 2. Design

The rule: whatever the mod owns runs from saved facts and engine state, never from a block entity, a capability or a block state. A chunk read only refreshes those facts while it is loaded.

### 2.1 Power (A1 to A4)

1. **The power wiring is a fact the engine reads.** The controls facts already hold every power port: id, kind, owner position, face, and the standing cables. That is everything `PowerGrid` needs except the stores themselves. Each mod terminal's power port gets its unit id in `DataPort.host`. Power ports write 0 there today, so this follows the field's meaning for every other kind. `PowerNetwork.refresh` sets it when the terminal loads. A port saved with host 0 resolves as now (loaded only) until its block next loads. Whether every older save needs that load, or whether the host can be filled from the engine's own data at world start, is checked in step 1.
2. **An engine store per mod terminal.** `PowerGrid`'s resolver (`PowerNetwork.energy`) first asks `ElectricStores` for a port with a host. That gives a receive-only adapter for a consumer and an extract-only one for a generator, over `ElectricStores.receive` / `extract` (already rate- and capacity-bound). No level access. Foreign terminals keep the capability path, and only while loaded.
3. **The creative source is an engine fact.** Its port is `POWER_OUTPUT` with the source's position. Its engine adapter is an unlimited extract-only store, as the block is now. The block entity's per-tick push (A1) goes. The source reaches a consumer two ways:
   - on a cable circuit, through `PowerGrid` (item 2);
   - beside a terminal, through a new engine adjacency: the source's position is one face from the terminal's port owner. This replaces the per-tick capability push and the A4 neighbour scan.
4. **`PowerGrid.tick` stays on the server tick,** as now. It moves FE only, no process state, and the server ticks without players. With items 2 and 3 it no longer skips unloaded terminals.
5. **A3:** a generator's neighbour beside its terminal resolves the same way. It is a mod terminal (engine store) or a foreign one (capability, loaded only).
6. **A4:** `supplied(unit)` asks the grid and the adjacency for the unit's port. No `EquipmentStructure` read.

Conservation: every joule still moves store to store, within the stores' rates and capacities. The creative source is the only unbounded one, as today.

### 2.2 Columns (C1, C2)

7. **The column's design becomes a fact on the column unit.** The player sets the trays' operating point (the calculator's apply), and the built diameter and condenser follow. `ColumnUnit` gets an appended field: the `V3ColumnInput` of the design as built, with its condenser and a declared revision. It is written when the shell's trays or operating point change, and on the first load of a shell whose unit has none. `columnDesign` / `columnCondenser` read the fact and only fall back to the shell when it is absent. Absent means the behaviour before the field existed (`rules/saves.md`). The column facts schema goes up by one, reserved through `COLLABORATION.md`, with its `FORMATS.md` row.
8. **C2:** `refreshColumnMembers` keeps a member's last inputs while that member's chunk is not loaded, instead of dropping them. A member is dropped only when its stored assembly report no longer lists it.

### 2.3 Checks first (C3, C4)

9. **Step 1 of the build** reads whether a saved motor binding acts without the re-bind, and whether any control or interlock reads an alarm beacon's block state. Either one that does gets the same treatment: a fact the engine holds, refreshed while the chunk is loaded. Anything display-only stays as it is.

## 3. Decisions for the owner

- **D1, foreign sources in an unloaded chunk.**
  - A (recommended): they stop, and a consumer fed only by them runs on its store and then shows `NO POWER`. Nothing is made from nothing.
  - B: the engine keeps feeding the last rate it saw. That makes energy without the source burning anything.
- **D2, the column design fact.** It is a saved-format change, the column facts schema plus one. The alternative is to read the design from the chunk's saved data without loading it, and that is far more code.

## 4. Steps

1. Read C3 and C4 (item 9). Read how `DataPort.host` is used for power ports today, and confirm it is 0 everywhere.
2. Power: items 1 to 6, in `PowerNetwork`, `PowerGrid`, `PowerPorts`, `ElectricStores`, `CreativePowerBlockEntity`, and `FluidWorldAuthority` (A3 and the `supply` predicate).
3. Columns: items 7 and 8, in `ColumnUnit` with its codec, `FluidWorldAuthority.columnDesign` / `columnCondenser`, the calculator apply path and `ControlHosts`.
4. Tests (classes picked with `node tools/merge-lane/affected.mjs select`):
   - `ElectricStores`: a consumer fed by an engine creative port is never short, and its store refills at its rate;
   - `PowerGrid`: two mod terminals on a circuit move FE with the resolver returning null for every capability (the unloaded case);
   - `ColumnOperation`: a unit with a design fact solves with `design()` from the fact and no shell;
   - a column facts round trip: a unit saved before the field reads back with no design and behaves as before;
   - one fluid GameTest: a burner beside a creative source, its chunk unloaded (`setChunkForced` false and no player), still powered after 3 store lengths.
5. The owner's save rerun on the review server with no forced chunks (`tools/column-soak`, save batch): no `NO POWER`, and the column keeps its table and solves.
6. Docs:
   - code guide (power and columns chapters);
   - `FORMATS.md` row;
   - CHANGELOG;
   - this batch's README.

## 5. Not in this plan

- Any change to the island scheduler or certificates. They already run without chunks.
- The `LOW FUEL` seen in the same save, the cold-feed batch's work.
  - Measured: from tick 4800 the fuel target stayed at 0.899 kg/s while the coil's firing was 0.91 to 0.95.
  - Read in code: `FiredHeaters.booked` compares each demand with the last booking's (WAKE 1 %).
  - Candidate, not verified: a demand that rises in steps under 1 % never wakes the fuel island.
