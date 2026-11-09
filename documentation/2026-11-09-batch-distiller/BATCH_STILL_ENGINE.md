# Batch still: the engine, the fuel and the data network (W3 to W5)

Batch 2026-11-09-batch-distiller, phase 2. Written on `claude/still-engine-546883` from 45bab2f46; fixed, finished
(O1, O2, O9 as option A) and tested on the main thread, 2026-11-09. The law is [BATCH_STILL_LAW.md](BATCH_STILL_LAW.md); the structure is
[BATCH_STILL_STRUCTURE.md](BATCH_STILL_STRUCTURE.md).

## 1. What the still is in the fluid engine

| Part | Device | Engine object |
|---|---|---|
| Pot | `RESERVOIR` with `HeatUnit(STILL, POT, heated area, 0)` | a fired pool (`Pool.fired`): heat W set per slice, no partner |
| Condenser water | `HEAT_SIDE` with `HeatUnit(STILL, WATER, condenser area, pot)` | a coupled side: the coordinator's heat in transit with the pot |
| DISTILLATE, VENT | `GENERATOR` with `ControlValve.still()`, one BULK port | a port plan: the account's moles at the condensate's (or head's) temperature |
| Burners (LIQUID) | `VOID` with `ControlValve.still()` at FUEL | the fuel valve at the firing's fuel rate, as the fired heaters' |
| Facts | `StillUnit` (fluid facts schema 15) | devices, design, head and vent accounts, fire, firing, reflux, totals |

The water side is registered first, so its identity is the lower: the pair's heat in transit is saved on it.

## 2. Physics, per slice

1. **Heat.** heat = firing × rating × available. Available: SOLID, the burning item's heat × 0.45 over the slice's ask;
   LIQUID, what the burners fired × 0.55 over the ask; ELECTRIC, 1 when the store holds power. 0 at OVERHEAT (623 K) and at
   BOIL DRY (liquid ≤ 5 % of the pot). With a column, the boil-up is held at F = 1.8 Pa^0.5 (FLOODING).
2. **Pot.** A closed pot (no condenser) boils out of its VAPOUR nozzle like a kettle. An open pot (with the condenser)
   releases what its surface boils up through its head (`Pool.Head`): a share of each component goes up, the rest returns
   as liquid at the column's bottom-tray enthalpy, inside the pot's own step.
3. **Column.** `StillHead.run` on the worker: `StillColumn` on the pot island's model, warm from its last profile, at the
   effective reflux. The answer applies 100 ticks after the commit that asked, so the slices do not depend on the worker's
   timing. Water is outside the column's basis and goes up whole.
4. **Head accounts.** What the pot's head vents (VENT/SPILL boundaries) is divided at the pot's commit: components with
   Tc ≤ T_condensate go to the vent account; of the rest the share φ(R_eff + 1)/(R + 1) goes to the distillate account.
5. **Outlets.** Each outlet slice gives what its account held 440 ticks before its start, over at most 10 s. The energy
   the gift lacks against the account is the condenser's duty: it is owed to the water side (`coordinator.owe`), or to the air.
6. **Condenser share φ.** 1 while the heat owed stays within 10 s of the pot's heat; else the share the water took over
   the last window (COOLING LIMITED). The column then runs at `BatchStill.effectiveReflux(R, φ)`.

Conservation: every mole is in the pot, an account or a gift (`StillRuntimeTest`); every joule is booked (pot HEAT, the
accounts' energy, the water's transit or the condenser's total).

## 3. Fuel (W4)

- **SOLID.** The pot's shell block entity has a one-slot `IItemHandler` capability on every cell (hoppers feed it). An
  item is valid when `SolidFuelCatalog` or its burn time gives it energy (`burnTicks × energy.joulesPerBurnTick`). The
  engine takes the next item when the fire holds less than 30 s of its firing. The heat left in the burning item is a
  fact (`StillUnit.burning`); the slot's stack is saved in the block entity (`StillFuel`). Breaking the structure drops
  the slot; the burning heat is lost.
- **LIQUID.** Fuel at FUEL through the burner valve; its heat from the `combustion` records (`Burners.perKilogram`).
- **ELECTRIC.** A store keyed by the pot (`ElectricStores` kind STILL), rated draw = rating / 0.9; NO POWER when empty.
- NO FUEL (NO POWER for electric) when the fire is out with a firing set.

## 4. Data network (W5)

Outputs (built-in channels on the pot): `pot_temperature`, `head_temperature` (unavailable until the first column answer),
`pot_level` (m, with % and the level height), `distillate_flow_rate` (kg/s), `distilling` (1/0), and one alarm channel each
for NO POWER, NO FUEL, OVERHEAT, BOIL DRY, FLOODING, COOLING LIMITED. Inputs: `FluidWorldAuthority.stillSettings(pot,
firing, reflux)` is the player's entry (a fact; an engine event that wakes the islands).

**Controllers (O1).** A local controller (or a DCS on one) may take the still's pot as its output; its action picks the
handle: INLET the firing, OUTLET the reflux valve (a still with a column only). Either holds the distillate flow a FLOW
sensor reads at the target (kg/s, or m³/s where the input has it). Boiling steadily, D = V / (R + 1) and V follows the
heat, so the law is the mass balance: the firing scales by target / measured (clamped 0 to 1), R + 1 by measured / target.
Before any distillate: full fire, the valve open; a target of 0 puts the fire out or closes the valve. One correction per
`Stills.RESPONSE_TICKS` (840 ticks, 42 s: the next solve and its answer, the outlet's fence and hold), so a change shows
in the reading before the next; a new target corrects at once. The setpoints are state (`ControlPlans`): the pot reads
them over its settings while the controller runs, and its settings resume when it stops. A firing and a reflux controller
on one pot do not conflict; two on one handle do (OUTPUT CONFLICT). The sensor may be on another island.

## 5. Diverter valve

`TopologyCompiler.Kind.DIVERTER_VALVE`: one inlet (its facing) and three outlets (straight, then the two side faces). It
compiles as a pipe through the open pair: the outlet is its joints, a fact (fluid facts schema 2 field). Placed open
straight through; an empty-hand use turns it to the next outlet (an engine edit, on the engine's schedule).

**Data input (O2).** The diverter is a data host: its socket stands on a face its pipes leave free (on top for a level
inlet; `DataPorts`). A controller with the diverter as its output switches it on any measurement: the straight outlet
while the reading stands below the target, the first side outlet at or above it. Each switch is the same engine edit as
the hand's, so the valve stays where the controller last set it when the controller stops. Two diverters in a line give
three cuts (for example on the head temperature); the second side outlet is set by hand. The sensor may be on another
island (the head's temperature switches the distillate line's diverter).

**Refresh (O9).** A still whose layout moved keeps its registrations (decision D7): one ledger event moves its pot, water
side, outlets and burners to their new cells with their new ports (the pot also takes its new volume, level height,
opening and geometry, the water side its new passage and surface); each keeps its identity, its facts and what it holds.
A device the layout adds registers; one it no longer has leaves. Before, the pot and its contents were removed and the
old outlets and burners, which stand at nozzle cells, not nodes, stayed registered.

## 6. Owner decisions (options)

| # | Question | Built | Other options |
|---|---|---|---|
| O1 | DCS inputs: firing and reflux | **A** (owner): the pot as a controller output, firing or reflux by action, holding a distillate flow (section 4) | B: reuse the valve output on the outlets; a head-temperature reflux law (its gain is not set by the physics) |
| O2 | Diverter data input | **A** (owner): a switch at the target, straight below, first side at or above (section 5) | a second threshold for the second side outlet |
| O3 | Burning heat on break | lost | A: refund as a dropped item of equal energy |
| O4 | Solid fuel in an unloaded chunk | no refill (NO FUEL) | A: move the slot into the fluid facts (the engine reads it anywhere) |
| O5 | Non-condensable rule | Tc ≤ T_condensate vents whole | A: a dew-point split of the head vapour |
| O6 | Outlet pressure | 150 kPa generators | A: the pot's pressure plus the head's static head |
| O7 | Blocked outlets | accounts grow; firing not cut | A: OVERPRESSURE when an account holds over 60 s of boil-up |
| O8 | Water-side fence | none (the transit absorbs it) | A: the outlet fence on the water side too |
| O9 | Refresh with a moved layout | **A** (owner): the still's devices move in place, contents and identities kept (section 5) | |
| O10 | Closed-pot DISTILLING | vapour out ≥ 10 % of the heat over water's latent heat | A: a bubble-point test |

## 7. Code areas

`runtime/fluid/Stills.java`, `StillUnit.java`, `PhysicalFluidTopology` (still devices, the diverter), `WorldTopologyLedger`
(stills; `withBasis` now keeps shafts, stores, health and stills), `FluidFacts` (schema 15; the facts rebuild carries the
stills), `FluidCheckpointCodec` (state 42: the pool head), `IslandCoordinator.Ports.dispatch`, `ElectricStores`,
`EquipmentAlarm`, `science/fluid/network/StillHead.java`, `Pool`, `PassiveStepSolver`, `FlowControl` (STILL source),
`runtime/ProcessSolveServices` (still solves), `world/level/block/EquipmentStructure` (register, restore, drop),
`EquipmentShellBlockEntity` (the slot), `FluidDeviceBlock` (diverter use), `runtime/control/BuiltInInputs`; for O1 and
O2 `ControlLaw`, `ControlService`, `ControlPlans`, `ControlPublication`, `ControlHosts`, `DataPorts`,
`InstrumentHosts`; for O9 `FluidWorldAuthority.placeStill(devices, former)`.
