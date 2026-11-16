# The fluid runtime's units: columns, stills, converters, ejectors, fired equipment, shafts

Up: [code guide](README.md). Beside it: [runtime-engine.md](runtime-engine.md) (the `Ports` and `Shafts` hooks), [runtime-registry.md](runtime-registry.md). The units' own logic: [column-operation.md](column-operation.md) (the running column), [shaft-trains.md](shaft-trains.md) (shafts, drivers, stores, wear), [heat-exchangers.md](heat-exchangers.md) (heat units), [effects.md](effects.md) (the published effects).

<!-- j2a-packages: runtime/fluid/ColumnOperation.java runtime/fluid/ColumnUnit.java runtime/fluid/ColumnPower.java runtime/fluid/Stills.java runtime/fluid/StillUnit.java runtime/fluid/Converters.java runtime/fluid/ConverterUnit.java runtime/fluid/Ejectors.java runtime/fluid/Chimneys.java runtime/fluid/Flares.java runtime/fluid/FiredHeaters.java runtime/fluid/PrimeMovers.java runtime/fluid/ShaftTrains.java runtime/fluid/ShaftUnit.java runtime/fluid/ElectricStores.java runtime/fluid/MachineHealth.java runtime/fluid/HeatUnit.java runtime/fluid/EquipmentAlarm.java -->

## Read this when

- A running column, a batch still, a fluid converter, a steam ejector, a fired heater, a flare, a chimney or a prime mover meets its islands wrongly: its ports' plans, its bookings at commit, its wakes, its status or alarms.
- A still's water, outlets, pot or burners depend on when the workers finish, or a still's column job is late or lost.
- A converter does not move Forge fluid, or says `NO_RULE`, `OFF_SPEC`, `SOURCE_EMPTY` or `DESTINATION_FULL`.
- You add a unit that plans valves at dispatch and books at commit.

## What it does

These classes are the plant units as the runtime holds them: each keeps its facts (a `*Unit` record in the ledger) and plans its islands at dispatch and books them at commit through one of the coordinator's two hooks. The `Ports` hook (`IslandCoordinator.Ports`) joins the running columns (`ColumnOperation`), the stills (`Stills`), the ejectors (`Ejectors`) and the converters (`Converters`) with `Ports.both`; the `Shafts` hook joins the shaft trains (`ShaftTrains`) with the fired heaters, flares, chimneys and prime movers on the trains' booking hook. A unit reads another island's values as of its own slice start less a fence, waits for the writers through that tick, and wakes its islands at fixed ticks, so its results do not depend on when workers complete. Only `ColumnOperation`, `Stills`, `Converters`, `ShaftTrains`, `ElectricStores` and `MachineHealth` hold facts; the others are state rebuilt from the graph.

## Class map

Under `src/main/java/com/wormzjl/icheme/runtime/fluid/`. Lines at `d6f721b94`.

| Class | Role | Lines |
|---|---|---|
| [`ColumnOperation`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnOperation.java) | A running column on the `Ports` hook: refresh, cycles, conditions, utilities' debts, members' power, feed plans, the trays' and wall's wear with its repair and clean ([integrity.md](integrity.md)); `FENCE_TICKS`, `APPLY_TICKS`, `KEEP_TICKS` ([column-operation.md](column-operation.md)) | 1288 |
| [`ColumnUnit`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnUnit.java) | A column unit's facts: stations, set points, condensers (`MAXIMUM_CONDENSERS`), `airSurface`, utilities, members; `State`, `Probes` | 210 |
| [`ColumnPower`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnPower.java) | The members' power through the topology, the electric stores and the shafts | 68 |
| [`Stills`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Stills.java) | The batch stills on the `Ports` hook: the pot, outlets, water side and burners, their fenced logs (`Run`), the column jobs, the wakes, `statusLine`; `FENCE_TICKS`, `APPLY_TICKS`, `KEEP_TICKS`, `RESPONSE_TICKS`, `HOLD_CYCLES`, `REFILL_CYCLES` | 809 |
| [`StillUnit`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/StillUnit.java) | A still's facts: devices, design, head and vent accounts, fire, firing, reflux, totals | 52 |
| [`Converters`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Converters.java) | The fluid converters on the `Ports` hook: forward and reverse plans, bookings, reservations, `Status`, `Rate`, `Settings`, `fill`, `grow`, `tank`; `RESERVATION_TICKS` | 467 |
| [`ConverterUnit`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ConverterUnit.java) | A converter's facts: vessel, drain and fill ports and their valves, closed, Forge tank, account, mode, rate, deadband, thresholds; `hopper` (a liquid hopper: its fill port is its identity) | 80 |
| [`Ejectors`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Ejectors.java) | The steam ejectors on the `Ports` hook: the motive valve's target and the delivered share | 124 |
| [`FiredHeaters`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FiredHeaters.java) | A radiant coil's demand at commit; the fuel valve's target and the coil's limit at dispatch | 225 |
| [`Flares`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Flares.java) | A flare's tip plan at dispatch; what it received, burnt and released at commit | 144 |
| [`Chimneys`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Chimneys.java) | A chimney's span readings (flow, solids, gas temperature, exit velocity, opacity, status) from each committed or replayed span | 96 |
| [`PrimeMovers`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/PrimeMovers.java) | The fuel and exhaust coupling between a combustion engine's or gas turbine's train, its fuel island and its steam coil's island | 232 |
| [`ShaftTrains`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ShaftTrains.java) | The `Shafts` hook: shaft trains and their drivers (`plans`, `booked`, `decide`, `held`), `Event`, `Hold` ([shaft-trains.md](shaft-trains.md)) | 1317 |
| [`ShaftUnit`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ShaftUnit.java) | A shaft unit's facts and `Settings`; its driver's `State` | 90 |
| [`ElectricStores`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ElectricStores.java) | Motors', generators' and fans' electric stores and FE ports: `draw`, `member`, `plans` | 607 |
| [`MachineHealth`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/MachineHealth.java) | Pump and machine wear in process time (`book`, `worn`, `status`) and repair; a control valve's trim (`trim`, `trimStatus`) and the `SOLIDS` fault ([shaft-trains.md](shaft-trains.md), [integrity.md](integrity.md)) | 621 |
| [`HeatUnit`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/HeatUnit.java) | A heat side's facts on its registration: unit, side, area, partner, `Control`, `transitJoule`, `Mode`; `heatSide()`, `controller()`, `lines()` | 136 |
| [`EquipmentAlarm`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/EquipmentAlarm.java) | The units' alarms with their texts (power, fuel, flare, still, draught, column, converter); `of(Condition)`, `column(conditions)` | 78 |

## Main flow

Each unit on the coordinator's hooks ([runtime-engine.md](runtime-engine.md)):

1. **Ready**: `Ports.ready` holds an island's dispatch until its readers' fenced values exist (a column's run cycled past the slice start less the fence; a still's pot, water and outlets each behind the others by a fence).
2. **Dispatch**: `Ports.plans` gives the slice's `PortPlan`s (a column's or still's outlet stream and valve targets, an ejector's motive target, a converter's fill or drain); `Shafts.plans` gives the `ShaftPlan`s (speeds, governors, fans, fuel, burners, flare tips, exhausts, failed pumps).
3. **Commit or replay**: `Ports.booked` and `Shafts.booked` record what the island booked (the column's accounts, a still's owes and heats, a converter's mB, a flare's burn, a coil's demand, a chimney's span).
4. **Decision point**: `shafts.decide()`, then `ports.decide()`: a cycle that fell due runs here (never inside a materialisation); held jobs are asked again; wakes are scheduled at their own ticks.
5. **Presentation**: each unit's view and alarms at the bucket (`FluidWorldAuthority.columnView`, `converterView`, the still page in `ViewBuilder`), its effects on the cadence ([runtime-presentation.md](runtime-presentation.md)).

## Contracts

- **Reads behind the fence** (the stills, as the running column's; `science/plant/port/PortAccounts`, [column-operation.md](column-operation.md)). Every value one island reads of another is dated, and a reader takes it as of its slice start less `FENCE_TICKS`, through which it waits for the islands that write it (`Stills.ready`). The water side reads the heat due (the transit less the owes dated after it, carried in the owes' tick order so its last bit does not depend on the order of commits), the pot's heat booked for the slice before the fence and the column answer applying by then. An outlet plans from its account, the answer and the condenser's share as of its fence. The pot's commit takes the condenser's share and its job's outlet floor from the water's reads before its slice's fence. A held, parked, suspended or missing island is through; a certified one as far as its certificate reaches, without being materialised (`IslandCoordinator.reached`). The logs (`Run`: owes by tick, heats, reads, answers, shares, fires) are state, kept for a fence and `KEEP_TICKS` beyond their reader; a load starts empty, so the first reads take the whole saved transit as due.
- **Job cadence by ticks.** A still's column job is asked only at the commit of a pot slice that starts at or after the last job's apply tick (`APPLY_TICKS` after the commit that asked); that slice's dispatch waited for the answer, so whether a job is asked depends on ticks only. A job the solve service refuses (at capacity) is held (`Run.heldJob`) and asked again at each decision point with the same apply tick; the pot's slice that needs the answer waits for it. `RESPONSE_TICKS` (a controller's correction cadence) is the slowest path, a liquid still's firing.
- **The fire by ticks.** A liquid still's burners book their fire, dated by the end of the span that fired it (`Run.fires`); the pot takes the fire in force at its slice start less the fence; the burners read the pot's answer (`solvedAt`) and its cut alarms (OVERHEAT, BOIL DRY) as of their own fence (`fenced`). NO FUEL on a liquid still means the burners' span in force at the fence asked fuel and burned none (`missingAt`), so a lighting still shows no alarm. A still that solves a column keeps its fire out until the first answer (`waits`): no heat, fuel or draw; its head returns the headspace gas; it reads HEATING.
- **Wakes at their own ticks** (`IslandCoordinator.movedAt`), each a fence after the value changes: the water at an owe, the pot at an answer's apply tick and at a fire lit or put out, the outlets at an arrival, an answer or a share change, the burners at an answer or a cut change. A certified still island is materialised only at its wakes, its horizon, a fence or a save. With certificates on, an idle outlet and idle burners are woken a fence after the change.
- **Converters.** Settings are a queued engine event (`Converters.Settings`, `queue`, applied by `applyQueued` on the engine's tick); `FluidWorldAuthority.converterSettings` clamps the rate to the rated flow (`EquipmentShape.converterRate`) and the deadband and queues; a packet handler or a DCS command never applies them at once. A Forge-side call (`ConverterPort`) moves mB in the facts and wakes the island, never solves; a fluid no rule names is refused. A load returns every account to its Forge tank in whole mB.
- **A unit's settings and accounts are facts; its plans are state.** A refresh moves a still's, a converter's or a column's devices in place, keeping identities, facts and contents (`StructureRegistration`).
- **The tutorial's criteria** ([tutorial.md](tutorial.md)): `FluidNetwork.deliver` fires `still_state` with the still's view each time a menu receives a publication or the open replay; the still settings edit fires `still_stopped` when it sets a firing still's fire off, read against the view (a plain read: no presentation read materialises an island); `FluidDeviceBlock` fires `diverter_switched` on a hand turn.
- **A running column's entries** on the authority ([column-operation.md](column-operation.md)): `startColumn` takes a `ColumnStart` (the condenser shells' sides, the reboiler's and pumparound coolers' sides, the members, the air condenser's surface), keeps the utility sides and the members it holds, and returns `Refusal` texts; `editColumn` on a column that never ran saves a stopped unit with no stations; `columnAlarms`, `columnLoop`, `columnFeedMolarMass`, `columnFeedValve` serve the control channels. The authority wires `ColumnPower(topology, electric, shafts)` and the control service's feed plans into the column operation.
- **A held device of a running column** is not gone: `ElectricStores.member` books a held fan side's or in-line pump's power from its last booking; `ShaftTrains.held` lets a held casing's shaft power stand for its island's booking.

## Laws and constants

**Still in the world** (`Stills`, `StillUnit`; in full in [batch-still.md](batch-still.md)): the pot is a reservoir with a fired pool (`Pool.fired`); its heat at dispatch is the firing × the rating × what the fuel or power delivers (0 at OVERHEAT and BOIL DRY; with a column, held at the flood boil-up). The column's job (`StillHead`) runs on the worker as a `StillCommand`. The distillate and vent outlets are generators carrying the still's outlet valve (`ValveSource.STILL`); the condenser's water side couples as a heat side. Solid fuel burns from the slot (`SolidFuelCatalog`), liquid fuel through the burners of the FUEL void, power from the terminal's store. The job carries the condenser's outlet floor: the water side's inlet temperature as its last read before the queuing slice's start less the fence (`IslandCoordinator.sideTemperature`) plus `BatchStill.OUTLET_APPROACH`; with no water read, the total condenser. The fuel's heating value is on no energy account: the pot's HEAT boundary books what it took, the burners' void the fuel's mass and fluid energy.

**The still's law and column** (`science/plant/still`, `science/column/still`): the pot's balance per mole of distillate, the condenser, `StillColumn`, `StillHead` and the water in [batch-still.md](batch-still.md).

**Converters** (`Converters`): forward (Forge to IChemE), the fill port's generator draws whole mB from the Forge tank into an account at the rule's stream and liquid density (`ConversionLaws.perLitre`, a flash once per rule revision and model list, a dry and a wet model keyed apart); reverse, the drain port's void takes the drawn phase (`drawnPhase`: the gas at a vapour nozzle, else the heavier liquid) at its own density, its litres join a remainder and whole mB go into the tank. A booking moves only mB in the facts (`drained`, `givenIn`); a plan reserves Forge room (`reserved`), replaced by the next plan and lapsing `RESERVATION_TICKS` after its slice. The drain leaves a heel of 1 % of the vessel volume (at least 1 L). Balance compares the two fills with a deadband (`fill`). A forward rule naming a compound the island does not carry is `NO_RULE` and asks its island to grow (`growth`, as a column outlet does) where the package carries the rule's compounds. Registration (`placeConverter`, from `StructureRegistration.registerConverter`): the process vessel at its lowest inside cell (open for `FLUID_CONVERTER`, closed for `GAS_CONVERTER`) and two hidden ports in the vessel's port lane, each a void (drain) or generator (fill) with its own `ValveSource.CONVERTER` valve. A liquid hopper (`placeHopper`, `Placement.hopper`) is a converter's fill port alone: a generator block at lane 0 with the converter's valve, at `Placement.HOPPER_PRESSURE`, its facts `ConverterUnit.hopper` (`fill == id`, no drain, `TO_ICHEME` at `HOPPER_RATE`); its plan has no vessel to fill, its Forge tank takes only a fluid a liquid forward rule names, it has no settings or alarms and no DCS listing.

**Ejectors** (`Ejectors`, on the island's own model): at commit each set's motive valve row (steam below 300 kg/m³ flowing forward) against the target it had; at dispatch the valve's target (`EjectorLaw.steam` for the load the full curve holds at the suction node's state) and `Ejector.withMotive(P_m, share)`, share = got / asked, kept within `VALVE_DEADBAND`. One slice of lag; not saved. `PhysicalFluidTopology.compile` gives a set two junctions of its own: its mover `ejectorIdentity(id)` from its node to the discharge junction, its motive valve `motiveIdentity(id)` from the motive junction to the same.

**Fired heaters** (`FiredHeaters`, on the shaft-train hooks): at a radiant coil's commit or replay its demand, the heat that holds its set point at its design duty; at dispatch the fuel valve's mass target (`ShaftPlan.Kind.FUEL`) and the coil's limit (`BURNERS`) from what the fuel line delivered and what the fans' store holds. **Flares** (`Flares`): the plan is open, or shut while solids arrive; at booking the flame holds while the gas burns and leaves no faster than `Flare.stableVelocity`; it reads every run into its tip, a safety valve's included. **Chimneys** (`Chimneys`): a passive one-port VOID at its stack's top off the port lane; the form is held in the device facing. **Prime movers** (`PrimeMovers`): the fuel enters a void behind a planned mass valve (`ValveSource.ENGINE`, `ShaftPlan.Kind.FUEL`); the coil registers as heat unit `PRIME_MOVER`.

**Heat units** (`HeatUnit.Mode`: OFF, OUTLET_TEMPERATURE, DUTY, FIRING, RATIO, COLUMN, saved by name). COLUMN is the water side of a column's condenser or a kettle's tube side (`kettleTubes`): it asks for FEED lines, a valve and a flowmeter on its feed; until they are complete the water flows as its line lets it.

**Time** ([fluid-interval.md](fluid-interval.md), `SimulationTime`): in process time are the ports' and shafts' bookings, pump wear (`MachineHealth.book`), the stills' and columns' windows as counts of cycles (`Stills.HOLD_CYCLES`, `REFILL_CYCLES`), the electric stores (one FE carries `joulesPerFE × factor` J).

| Constant | Meaning | File |
|---|---|---|
| `FENCE_TICKS` | the reads' fence (`PortAccounts.FENCE_TICKS`) | `Stills`, `ColumnOperation` |
| `APPLY_TICKS`, `KEEP_TICKS` | a job's apply tick after its commit; how long a log is kept | `Stills`, `ColumnOperation` |
| `RESPONSE_TICKS`, `HOLD_CYCLES`, `REFILL_CYCLES` | a controller's correction cadence; the still's windows | `Stills` |
| `RESERVATION_TICKS` | a converter plan's Forge reservation | `Converters` |
| `MAXIMUM_CONDENSERS` | a column's condenser water sides | `ColumnUnit` |
| `OUTLET_APPROACH`, `TRIAL_SHARE`, `TOTAL_CONDENSER` | the still's condenser and bracket | `BatchStill`, `StillColumn` |

## Formats

`ColumnUnit`, `StillUnit`, `ConverterUnit`, `ShaftUnit`, the electric stores, the pump health and `HeatUnit` are facts in their sections ([runtime-saves.md](runtime-saves.md): `ColumnFacts`, `StillFacts`, `ConverterFacts`, `MachineFacts`, `RegistryFacts`); `ColumnUnit.State`, `ColumnUnit.Probes` and `ShaftUnit.State` are topology state. The still's logs, the converters' plans and the ejectors' targets are not saved. Numbers in [FORMATS.md](../FORMATS.md).

## Tests and gates

| Rule | Test |
|---|---|
| The still's reads, fire and jobs by ticks | `StillRuntimeTest` (`aStillsWaterDoesNotDependOnWhenItsIslandsJobsComplete`, `aLiquidStillsFireAndJobsDoNotDependOnWhenItsIslandsJobsAndSolvesComplete`, `aLiquidStillThatIsLightingShowsNoFuelAlarm`, `aCertifiedStillIslandIsMaterialisedAtTicksThatDoNotDependOnWhenItsIslandsJobsComplete`, `aColumnJobTheSolveServiceRefusesIsAskedAgainForTheSameSlice`) |
| The still's law and column | [batch-still.md](batch-still.md) (`BatchStillTest`, `StillHeadTest`); the still's drawing: `StillModelTest` |
| A running column's hold, ports, facts, utilities | `ColumnRunTest`, `ColumnSuspensionTest`, `ColumnSolveTest`, GameTests `ColumnOperationGameTests` |
| Fired heaters' fuel | `FiredHeaterFuelTest` |
| Controllers on the units | `ControlSystemTest` |
| Port accounts | `PortAccountsTest` |
| Facts of the units | `FluidFactsCompatTest` |
| Ejectors, chimneys, prime movers | `SteamEjectorConnectionTest`, `ChimneyTest`, [shaft-trains.md](shaft-trains.md) |

## Traps

- **A unit never solves on the tick.** Its plans and bookings run at dispatch, commit and decision points; its column jobs run on the workers.
- **A still woken by a dated change shows the coupling's status text** ("an exchanger's other side changed").
- **The running column and the coupling date theirs as the still does**: `IslandCoordinator.through` materialises a certified island to the cycle's tick, `ExchangerCoupling.reads` to the slice start; the column's wakes and the coupling's (`ExchangerCoupling.dated`) act as of their ticks through `movedAt`; a refused column solve is held and asked again (`Run.heldJob`) ([column-run.md](column-run.md), [heat-units.md](heat-units.md)). A reader that needs no replay uses `reached`.
- **`NO_DRIVER`**: `startColumn` refuses a column whose machines have no driver while `energy.machinesNeedPower` is on.

**Snapshot:** `c004a4c57`, 2026-11-16.
