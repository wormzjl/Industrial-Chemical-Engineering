# The batch still: its law, its column, its head and the running still

Up: [code guide](README.md). The other units on the same hooks (columns, converters, ejectors, fired equipment):
[runtime-units.md](runtime-units.md); the coordinator's fence, wakes and certificates: [runtime-engine.md](runtime-engine.md);
the still on the process simulator's sheet: [simulator-units.md](simulator-units.md); the still's shape and options:
[equipment-designs.md](equipment-designs.md); its door: [client-screens.md](client-screens.md).

## Read this when

- A still's pot heats, boils or distils at the wrong rate, its cuts have the wrong composition, or its energy does not
  close.
- A still reads the wrong status: IDLE, HEATING, BOILING, DISTILLING, NO FUEL, OVERHEAT, BOIL DRY, FLOODING, COOLING
  LIMITED, or WATER ON THE TRAYS.
- The condenser condenses too much or too little, the vent takes the wrong gas, or the reflux is wrong.
- The still's column does not solve, runs as a pot still, or a two-liquid top or pot settles wrongly.
- A still's water, outlets, pot or burners depend on when the workers finish, or its column job is late or lost.

## What it does

A batch still is a fired pot under a short column and a condenser. Physically the heat into the pot first warms its
liquid to its bubble point; there it splits between the pot's warming (its bubble point rises as it strips) and the
boil-up. The column is quasi-steady next to the pot (its holdup is small): at each moment a steady rectifier with the pot
held at its composition, the reflux ratio R from the valve. In that pot mode every flow is proportional to the distillate
D, so the column solves per mole of distillate, and the pot's energy balance sets how many moles a step distils. The law
(`BatchStill`) is the heater, the pot's balance, the condenser and the statuses; the column (`StillColumn`) is the
bubble-point method with its energy-balance flow update on its own thermo interface (`StageThermo`, implemented on the
island's model by `NetworkStageThermo`). In the world `StillHead` runs the column on the engine's worker and divides what
reaches the head; `Stills` holds each still's pot, outlets, water side and burners on the coordinator's `Ports` hook,
reading every value of another island behind a fence and waking its islands at fixed ticks. The sheet runs one batch a
cycle on the same law (`StillBatch`).

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/`.

| Class | Role | Lines |
|---|---|---|
| [`science/plant/still/BatchStill`](../../../src/main/java/com/wormzjl/icheme/science/plant/still/BatchStill.java) | The law: `Heater` (SOLID, LIQUID, ELECTRIC), the pot's sizes (`potDiameter`, `heatedArea`, `rating`, `condenserArea`, `designWaterFlow`, `columnDiameter`, `fFactor`), `Cooling`, `condenserCapacity`, `outlet`, `effectiveReflux`; `Design` (`refluxes`), `Inputs`, `Pot`, `Status`, `Step`; `step(column, design, pot, inputs, dt)` | 597 |
| [`science/plant/still/StillHead`](../../../src/main/java/com/wormzjl/icheme/science/plant/still/StillHead.java) | The head in the running world: `Job`, `Outcome` (`Solved` with `head`, `profile`, `liquidShares`, `settlingTrays`, `waterOnTrays`, `potStill`; `Failed`); `run(job, model, checkpoint)` on the worker; `liquidShare`, `divide` → `Division` on the server | 160 |
| [`science/plant/still/NetworkStageThermo`](../../../src/main/java/com/wormzjl/icheme/science/plant/still/NetworkStageThermo.java) | `StageThermo` on an island's model: the network's phase evaluator (`HydrocarbonModel`) and, for a liquid that holds water, the engine's TP split (`FluidThermodynamics.flashTP`); the basis is the model's conserved basis where it carries water (water last) | 139 |
| [`science/column/still/StillColumn`](../../../src/main/java/com/wormzjl/icheme/science/column/still/StillColumn.java) | The column: `solve`, `Profile`, `Split` (`energyPerDistillate`, `liquidShares`, `flashes`, `twoLiquids`), `Bubble`; `TOLERANCE`, `MAX_ITERATIONS`, `STALL_ITERATIONS`, `SWITCH_KELVIN`, `LAYER_ROUNDS`, `MAX_STAGES`, `CLOSED_REFLUX`, `TOTAL_CONDENSER`, `POT_STILL` | 1260 |
| [`science/column/still/StageThermo`](../../../src/main/java/com/wormzjl/icheme/science/column/still/StageThermo.java) | What the column reads of its thermo: the basis's constants, a phase's H and V, ln φ at a prepared temperature, a wet liquid's TP split (`water`, `flash`) | 68 |
| [`runtime/fluid/Stills`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Stills.java) | The stills on the `Ports` hook: the pot, outlets, water side and burners, their fenced logs (`Run`), the column jobs (`Solves`), the wakes, `View`, `statusLine`; `FENCE_TICKS`, `APPLY_TICKS`, `CYCLE_TICKS`, `KEEP_TICKS`, `RESPONSE_TICKS`, `HOLD_CYCLES`, `REFILL_CYCLES`, `APPROACH_KELVIN` | 809 |
| [`runtime/fluid/StillUnit`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/StillUnit.java) | A still's facts: devices, design, head and vent accounts, fire, firing, reflux, totals | 52 |
| [`runtime/fluid/StillFacts`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/StillFacts.java) | The stills' facts section, by their pots' identities ([runtime-saves.md](runtime-saves.md)) | 47 |
| `runtime/ProcessSolveJobs.StillCommand` | The column job as a worker command (`StillHead.Job`, the island's model) | |
| [`science/flowsheet/unit/StillBatch`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/StillBatch.java), [`BatchStillUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/unit/BatchStillUnit.java) | One batch, start to end, on `BatchStill.step` (`Finish`, `SETTLED` among them); the sheet's still as cycle-averaged flows ([simulator-units.md](simulator-units.md)) | 262, 334 |

## Main flow

**One step of the law** (`BatchStill.step`, on the pot's island or the sheet's batch):

1. The heat into the pot: firing × the rating × what the fuel or power delivers, by the heater's efficiency (0 at
   OVERHEAT and BOIL DRY; with a column, held at the flood boil-up: FLOODING).
2. Below the bubble point the heat warms the pot. At it, `boiled` finds the moles distilled δ that close the pot's
   balance, its bracket seeded on the balance's own slope at `TRIAL_SHARE` of the most the step can boil.
3. The distillate's composition and e come from the column's split at the step start (explicit); the pot ends on its
   bubble point. The `Step` carries the pot, the status and alarms, the heat, the warming, the fuel, the distillate and
   vent with their enthalpies, the condenser's duty, the head temperature, the F-factor and the column's answer.

**The still in the world** (`Stills`, on the `Ports` hook, [runtime-units.md](runtime-units.md)):

1. **Ready**: the pot's, water side's and outlets' islands each wait until the others are through their slice start less
   `FENCE_TICKS` (`Stills.ready`).
2. **Dispatch**: the pot is a reservoir with a fired pool (`Pool.fired`) and the column's answer as its `Pool.Head`; the
   distillate and vent outlets are generators carrying the still's outlet valve (`ValveSource.STILL`); the condenser's
   water side couples as a heat side.
3. **Commit**: the pot's slice books its heat (`HEAT`), the outlets their arrivals, the water side its owes. At the
   commit of a pot slice that starts at or after the last job's apply tick, a column job (`StillHead.Job`, with the
   condenser's outlet floor) is asked as a `StillCommand`; its answer applies `APPLY_TICKS` after that commit.
4. **Server**: `StillHead.divide` splits what reached the head into distillate and vent by the answer's liquid shares and
   the reflux.

## Contracts

**The pot's balance.** A step closes the pot's energy exactly: `Q dt = N1 h(x1, Tb(x1)) − N0 h0 + δ e`, δ the moles
distilled and e = `StillColumn.Split.energyPerDistillate`, the energy that leaves the pot and the column per mole of
distillate, the top envelope's `(R + 1) H1 − R hR` (H1 the head vapour's enthalpy at the top tray's temperature, hR the
reflux's); with no column or no reflux (one stage) the bubble vapour's H; a closed valve: per mole of top vapour. No heat
per mole is taken as e less the pot liquid's enthalpy. No heat is lost to ambient: the setting's losses are inside the
heater's efficiency. The step's ledger: heat = the pot's enthalpy change + the distillate's + the vent's + the condenser's
duty.

**The condenser.** U = `CONDENSER_U`, its area sized for the full boil-up plus `EXCESS` at `DESIGN_LMTD`. It condenses at
most ε C_w (T_c − T_w), ε = 1 − exp(−U A / C_w) (`condenserCapacity`); short of that it condenses the share φ of the top
vapour, the column runs at the effective reflux φ R / (R + 1 − φ R) (`effectiveReflux`) and the rest leaves by the vent:
COOLING LIMITED. The outlet temperature is max(T_b, min(T_head, T_water + `OUTLET_APPROACH`)) (`BatchStill.outlet`); with
no water read `StillColumn.TOTAL_CONDENSER` condenses the top vapour whole. Below the bubble point it is partial: each
component leaves as liquid by its share (`Split.liquidShares`), the gas to the vent at its own molar enthalpy; the reflux
is drawn from the liquid only.

**The column** (`StillColumn`). Stages 0 the total condenser, 1..N the trays, N + 1 the pot. Per iteration one tridiagonal
balance per component on fixed K values (Thomas), then each stage's temperature at its bubble point. The flows start on
constant molar overflow (L = R D, V = (R + 1) D) until no stage moves `SWITCH_KELVIN` in an iteration, then come from the
top envelopes' energy balances (Wang and Henke): with D = 1, V(j+1) = ((R + 1)(H1 − h0) + h0 − hj) / (H(j+1) − hj),
Lj = V(j+1) − 1. A short reflux the trays cannot carry runs them dry, the pot's single stage returning all its liquid.

**Water.** Where the model carries water the column's basis is the model's conserved basis, water last, one more
component of every stage. Two liquids are stated with the engine's TP split only where a liquid holds water
(`Split.flashes`): the pot once a solve, the condensate once an iteration; a settling liquid's heterogeneous bubble point
by a bracketed secant on the oil layer's ln Σ K x (at most `LAYER_ROUNDS` splits). A condensate of two layers refluxes
its oil layer first: the reflux takes ρo = min(1, R / O) of the oil and ρa = max(0, (R − O) / A) of the aqueous layer.
With one liquid the arithmetic is the one-liquid column's, row for row. The trays hold one liquid: at convergence a tray
whose liquid would settle is reported (`StillHead.Solved.settlingTrays`, WATER ON THE TRAYS), never a failure; a
two-liquid top that does not converge fails with its reason and the pot's single stage stands, never a hold.

**The head** (`StillHead`). `run`: the column on the pot's island's own model, warm from its last profile, at the
effective reflux; trays that do not solve run as the pot's single stage, their failure the notice (`Solved.potStill`).
The answer is the pot's `Pool.Head`: for each component the share of the pot's vapour that leaves the head, d_i / v_i;
the rest returns to the pot at its own enthalpy. A component the column carries no vapour of, or a list without water for
water, has share 1; a closed valve returns every component (share 0). `divide`: what reached the head goes to the
distillate or the vent by the liquid shares, then `liquidShare` of the liquid to the distillate; the energy goes with the
moles. The flood check's F-factor is per watt of the condenser's duty.

**The still in the world** (`Stills`).

- **Reads behind the fence.** Every value one island reads of another is dated, and a reader takes it as of its slice
  start less `FENCE_TICKS`, through which it waits for the islands that write it. The water side reads the heat due (the
  transit less the owes dated after it, in the owes' tick order); the pot's heat booked for the slice before the fence and
  the column answer applying by then. An outlet plans from its account, the answer and the condenser's share as of its
  fence. A held, parked, suspended or missing island is through; a certified one as far as its certificate reaches,
  without being materialised (`IslandCoordinator.reached`). The logs (`Run`) are state, kept for a fence and
  `KEEP_TICKS` beyond their reader; a load starts empty, so the first reads take the whole saved transit as due.
- **Jobs by ticks.** A job is asked only at the commit of a pot slice that starts at or after the last job's apply tick;
  that slice's dispatch waited for the answer, so whether a job is asked depends on ticks only. A job the service refuses
  is held (`Run.heldJob`) and asked again at each decision point with the same apply tick. The job carries the
  condenser's outlet floor: the water side's inlet temperature as its last read before the queuing slice's start less the
  fence (`IslandCoordinator.sideTemperature`) plus `OUTLET_APPROACH`; with no water read, the total condenser.
- **The fire by ticks.** A liquid still's burners book their fire dated by the end of the span that fired it
  (`Run.fires`); the pot takes the fire in force at its slice start less the fence; the burners read the pot's answer and
  its cut alarms (OVERHEAT, BOIL DRY) as of their own fence. NO FUEL means the burners' span in force at the fence asked
  fuel and burned none, so a lighting still shows no alarm. A still that solves a column keeps its fire out until the
  first answer (`waits`): no heat, fuel or draw; its head returns the headspace gas; it reads HEATING.
- **Wakes at their own ticks** (`IslandCoordinator.movedAt`), each a fence after the value changes: the water at an owe,
  the pot at an answer's apply tick and at a fire lit or put out, the outlets at an arrival, an answer or a share change,
  the burners at an answer or a cut change. A certified still island is materialised only at its wakes, its horizon, a
  fence or a save.
- **Fuel.** Solid fuel burns from the slot (`SolidFuelCatalog`), liquid fuel through the burners of the FUEL void, power
  from the terminal's store (`electricPerTick`). The fuel's heating value is on no energy account: the pot's HEAT
  boundary books what it took, the burners' void the fuel's mass and fluid energy.

## Laws and constants

- `BatchStill`: `FLUX` and `AREA_FACTOR` (the heated surface and the rating), `CONDENSER_U`, `DESIGN_LMTD`, `EXCESS`,
  `DESIGN_WATER_RISE`, `OUTLET_APPROACH`, `DESIGN_F_FACTOR`, `DESIGN_LATENT`, `DESIGN_VAPOUR_DENSITY`, `FLOOD_F_FACTOR`,
  `OVERHEAT`, `DRY_SHARE`, `FILL` (the sheet's fill threshold), `JOULES_PER_BURN_TICK`, `MIN_STAGES`; the heaters'
  efficiencies in `Heater`.
- `StillColumn`: the iteration constants in the class map; `TOTAL_CONDENSER`.
- `Stills`: `FENCE_TICKS` (`PortAccounts.FENCE_TICKS`), `APPLY_TICKS`, `CYCLE_TICKS`, `KEEP_TICKS`, `RESPONSE_TICKS` (a
  controller's correction cadence, the slowest path: a liquid still's firing), `HOLD_CYCLES`, `REFILL_CYCLES` (windows as
  counts of cycles, the same play time at any time factor).

## Formats

A still's settings and accounts are facts (`StillUnit` in `StillFacts`, [runtime-saves.md](runtime-saves.md); numbers in
[FORMATS.md](../FORMATS.md#saves)); the logs, the answers and the wakes are state and not saved. A refresh moves a still's devices in place, keeping identities, facts and contents
(`StructureRegistration`).

## Tests and gates

| Rule | Owner | Test |
|---|---|---|
| The law, the condenser, the statuses; a crude pot still to its limit | `BatchStill`, `StillColumn` | `BatchStillTest` (with `aMediumCrudePotStillRunsToItsLimit`) |
| The head's job and division | `StillHead` | `StillHeadTest` |
| The reads, fire and jobs by ticks | `Stills` | `StillRuntimeTest` (`aStillsWaterDoesNotDependOnWhenItsIslandsJobsComplete`, `aLiquidStillsFireAndJobsDoNotDependOnWhenItsIslandsJobsAndSolvesComplete`, `aLiquidStillThatIsLightingShowsNoFuelAlarm`, `aCertifiedStillIslandIsMaterialisedAtTicksThatDoNotDependOnWhenItsIslandsJobsComplete`, `aColumnJobTheSolveServiceRefusesIsAskedAgainForTheSameSlice`) |
| The still on the sheet | `BatchStillUnit`, `StillBatch` | `BatchStillUnitTest`, `StillSizingTest` |

All under `test`: `--tests 'com.wormzjl.icheme.science.fluid.network.BatchStillTest' --tests 'com.wormzjl.icheme.science.fluid.network.StillHeadTest' --tests 'com.wormzjl.icheme.runtime.fluid.StillRuntimeTest'`.

## Traps

- **The tests of the law are in `science/fluid/network`**, not beside the classes in `science/plant/still`.
- **No heat per mole as e less h(pot)**: on a wide-boiling charge that difference of two mixtures falls through zero while
  the pot still distils.
- **The column is quasi-steady**: its holdup is taken as small next to the pot's, so its trays hold no stock.
- **A still woken by a dated change** shows the coupling's status text ("an exchanger's other side changed").
- `Stills` and `ColumnOperation` share the fence and the cycle (`PortAccounts.FENCE_TICKS`, `CYCLE_TICKS`): a change there changes both.

**Snapshot:** `9d6d0d82e`, 2026-11-15.
