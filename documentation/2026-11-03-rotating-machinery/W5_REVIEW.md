# W5: electricity (FE), review

Commit `97ae9c6d8` (`W5_BUILD_PLAN.md`, owner decision D30). Built by an Opus medium subagent and reviewed here. Main `fb9607dad` was merged afterwards (`c2704213a`, AGENTS.md only).

## 1. What was built

- **Stores.** `science/machine/ElectricStore` is pure: capacity (40 s at rated draw), receive rate (2 × rated per tick), draw, restart at one slice of rated draw, a generator's load from its room, the ±5 % speed window, and IE3 efficiency per fan and for generators.
  - `runtime/fluid/ElectricStores` holds the live stores, booking, the cadence and the views.
  - Saved in fluid facts schema 11 in joules, with corpus image `compat/fluid/schema-11/`. A store missing from an older save reads unmetered (A73 option A).
- **Ports.** `TerminalEnergy` answers `Capabilities.EnergyStorage.BLOCK` only at a structure's terminal cell: receive-only for a consumer, extract-only for a generator. A charged generator pushes to its six neighbours each tick.
  - New terminal cells: the air cooler at its front corner leg foot, the induced tower at its basin rim's back corner. `layouts.txt` changed in terminal rows only (12 rows); no cell or nozzle pipe block moved.
- **Booking.** Fans book at their island's commit and replay; motors and generators at their train's step. A unit with no booking within a cadence books its last power on the 100-tick cadence.
- **Running short.** Unpowered reaches the solver as a dispatch input, never as a settings edit:
  - a motor stops with `NO_POWER`, which is not latched and not saved;
  - an air cooler's fans stop and it falls to natural draught, through `ShaftPlan.Kind.FANS` and a non-fact `fanLimit` in `AirCooled`;
  - an induced tower runs at speed 0 on its stack draught.
- **Config.** `energy.joulesPerFE` (2.5) and `energy.machinesNeedPower` (true) replace `ShaftTrains.JOULE_PER_FE`.
- **Presentation.** Status lines show `NO POWER`, `UNMETERED`, or `POWER: kW, FE/t, store %`. `ShaftTrains.View` carries the store; the `FluidView` wire records are unchanged until W7. No lang keys (the new translation rule, `34c1928fe`).

## 2. Found on the way: a W3 save crash (fixed)

1. **Expected.** A world with a running motor saves.
2. **What the code did.** A driver's state holds NaN for "no slice before yet", and an unregulated motor keeps NaN as its measure. `FluidFacts.topologyState` wrote both through `Writer.f64`, which refuses non-finite numbers, so the first save after a driver started crashed the server ("Nonfinite checkpoint number"). W3's GameTests ended before any save.
3. **Fix.** The record format already reads a missing trailing field as its default (NaN), so the writer stops before the first non-finite trailing value (`FluidFacts.shaftState`). No format bump was needed. `ShaftCouplingTest.aDriverStateWithNoSliceBeforeItSavesAndReadsBack` covers it.

## 3. Tests

- **New:** `ElectricStoreTest` (7: the plan's numbers, shortfall, generator room and speed window), `HeatUnitPowerTest` (3), `ShaftCouplingTest` (drain, `NO_POWER`, restart; the state round trip), `ShapeRasterizerTest` (terminal cells), and `ElectricGameTests`, three tests in three batches.
- **Affected set,** every test that loads a changed class or a shared harness: `test` 583, `equipmentGates` 98, `holdGates` 81, all pass.
- **Fluid GameTests:** 41 of 41 pass.
  - The first try crashed on the W3 save defect.
  - The second showed the three new tests sharing one batch (overlapping plant boxes, so cleanup never finished) and the cooler test without a line (no hydraulic boundary).
  - The agent fixed both in test code only.

## 4. Campaign

1000 seeds, run alone, on `97ae9c6d8`, against W3's run:
- cold random: 992 clean, the same held seeds;
- bundled random: 993 clean, the same held seeds plus seed 6, a wall-deadline hold (class F) that main's baseline holds too, so no replay is needed;
- bundled defaults: 1000 clean, identical counters.

No campaign world has an air cooler, tower or motor, so W5 changes none of them. The random distributions' counters move by under 0.1 % (bundled random's backtracks by 0.75 %, the size of W3's move). Wall times are 7 to 19 % higher with identical defaults counters: machine noise.

## 5. Readings taken by the build (A74 to A84, under D30)

- A74: the fan count is a `HeatUnit` fact in schema 11, set at build and restate; an old cooler gets one registration edit on its first load.
- A75: until then, fans = 0 means one motor for the bank.
- A76, A77: status lines instead of new view fields; no lang keys.
- A78: a store's `bookedTick` prevents double booking. On 20 s slices the cadence books part of a slice and the commit books the rest.
- A79: at load a unit is unpowered only if its metered store holds 0 J.
- A80: a column's internal air condenser draws no FE.
- A81: a new store starts unpowered, so a new unit does not run free for a slice.
- A82: a fan unit that runs short during a commit or replay stops at the next cadence, since a replay must wake nothing.
- A83: a generator's Δt_next is one cadence (5 s).
- A84: generators always fill their store; metering matters only for consumers.
- **Untested:** the generator's push to a neighbour; no receiver block exists in the GameTests. Extraction through the port is tested.

Code guide: no change yet. The fluid network, equipment and runtime chapters change at the merge refresh.
