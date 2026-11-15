# Column operation: the split table, the cycle, conditions and solves (the plant layer)

Up: [code guide](README.md) · the running column in the engine (ports, accounts, fence, utilities, power, saves, the
tab): [column-run.md](column-run.md)

## Read this when

- A running column's status reads `LIMITED`, `UPSET` or `RE-SOLVING`, or a column alarm (`alarm_upset`,
  `alarm_off_table`, …) is raised, and you need to know which condition bound and why.
- A running column's product split, duties per feed, flood fraction or live drop look wrong.
- A solve was asked, refused or not asked again, and you need the drift band or the solve policy behind it.
- Where a running column's feed water goes (steam, distillate, on the axis), or a note says two liquids would settle.
- You change the split table, the cycle, a condition, a drift band, rule A or B, or the set points.

Not here: the tray solver ([column-v3.md](column-v3.md)), the parts check ([machine-assemblies.md](machine-assemblies.md)),
the condenser's wall ([heat-exchangers.md](heat-exchangers.md)), the island scheduler ([runtime-engine.md](runtime-engine.md)).

## What it does

A real column takes feed and steam and gives products at steady state after hours of holdup. The game does not march
its trays. One V3 solve gives a **split table**: the share of each component that leaves by each product, the duties
per mole of feed, and each product's state. Every cycle a running column takes the feed its inlet accounts hold, splits
it by the table and closes the energy on the condenser. Each limit (a short feed, steam or utility, a full product,
flooding, a member without power) allows a share of the set feed, and the lowest share binds; a condenser that would
depart too far from its solved duty trips the cycle. When the feed, the steam or the set points leave their **drift
bands**, a new solve is asked. This chapter is the plant layer, `science/plant/column` and `science/plant/port`: the
arithmetic, Minecraft-free and engine-free. The engine side, which holds the accounts, times the cycles and the solves
and plans the ports, is `runtime/fluid/ColumnOperation` ([column-run.md](column-run.md)).

## Class map

**`science/plant/column/`** ([source](../../../src/main/java/com/wormzjl/icheme/science/plant/column/)) and
**`science/plant/port/`**. Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| [`Port`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/Port.java) | `FEED`, `STEAM`, `OFF_GAS`, `SOUR_WATER`, `DISTILLATE`, `DRAW`, `BOTTOMS`; saved by name; `inlet()`, `gas()` | 19 |
| [`Setpoints`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/Setpoints.java) | The player's numbers, a fact: design and set feed rate, condenser temperature, reflux ratio; reboiler, steam, draws and pumparounds per mole of feed; `of(point)`, `withFeedRate` (capped at the design rate), `intensivelyDiffers`, `compatible` | 78 |
| [`PortConditions`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/PortConditions.java) | A port's pressure and temperature estimated from the design (`estimate`) | 35 |
| [`SplitTable`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/SplitTable.java) | The table: `axis`, `products` (`Product`: port, index, recovery by component, temperature, pressure), `reboilerPerFeed`, `pumparoundPerFeed`, `condenserPerFeed`, `reference` (`Reference`), `held`, `condenserWaterPerFeed`, `waterBelow`, `airArea` (`FAN_STEPS` fan speeds), `flood` (`Flood`, `NONE`), `pumparoundDraw`; `of`, `split`, `perFeed`, `product`, `waterShare`, `waterTakes`, `airFeed`, `fans`, `withFlood`, `withPumparoundDraw`, `drawTemperature`, `bottomsTemperature`; `WATER_ALWAYS`, `WATER_NEVER` | 463 |
| [`ColumnCycle`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/ColumnCycle.java) | One cycle as arithmetic: `Account`, `Rooms` (what each utility can still pass, the air's most feed, unpowered fans and pumps; `NONE`), `Step` (`conditions`, `binding`, `tripped`), `step`; `TRIP_DEPARTURE` | 367 |
| [`Condition`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/Condition.java) | One constant per failure, each in one `Rank` (`NOT_READY`, `NOTICE`, `LIMIT`, `OFF_TABLE`, `TRIP`) with its status word; `Active` (condition, value, index, text); `binding`, `find`; `HIGH_LOAD_FRACTION`, `MAX_TEXT`, `RULE_A` | 113 |
| [`ColumnStatus`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/ColumnStatus.java) | `phase` and `line` from a condition list: one pure formatter | 61 |
| [`Refusal`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/Refusal.java) | Every Start and first-solve refusal as a constant with its text (`text(parameters)`): `FIRST_SOLVE`, `NO_NOZZLE`, `TOO_MANY_UTILITY_UNITS`, `NO_DRIVER` among them | 48 |
| [`DriftBands`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/DriftBands.java) | The bands and `Trigger`; `check` (a table's, and a reference's without the closure); `reference` (a refused state as a reference) | 109 |
| [`ColumnSolve`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/ColumnSolve.java) | The worker's job: `Job`, `Taken`, `Steam`, sealed `Outcome` (`Solved` with `advisories`, `MAX_ADVISORIES`; `Failed` with `transientFailure`); `run`; `calculate` (the one V3 solve of a column's input, for the running column and the sheet's column block alike) with its `ColdStart` (`CLASSICAL`, `LEARNED`); `warm` (the seed screen); `closure` (the condenser duty as the energy closure on one basis); `trim` (an air-then-water condenser's water share), `airArea`; `SUPERHEAT` | 525 |
| [`ColumnWater`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/ColumnWater.java) | Where a column's feed water goes and whether its liquids settle, the one rule of the running column and the sheet's column block: `onAxis` (`SHARE`), `settling` (`TRACE`, `Settling`) | 118 |
| [`OperationView`](../../../src/main/java/com/wormzjl/icheme/science/plant/column/OperationView.java) | What the Operation tab shows: `Phase`, `conditions`, `accepted`, `Stream`, `Duty` (`DutyKind`), `Solve`, `Trays`, `idle`, `binding`, `ruleA`, `refused` | 120 |
| [`PortAccounts`](../../../src/main/java/com/wormzjl/icheme/science/plant/port/PortAccounts.java) | The port arithmetic the column and the batch stills share: `CYCLE_TICKS`, `FENCE_TICKS`, `onIsland`, `onWorld`, `mass`, `less`, `idle`, `debit` (with `Overdrawn`), `keep` | 85 |

## Main flow

**One solve** (on a worker, asked by `ColumnOperation.request`):

1. `ColumnSolve.run(job, model, screen, checkpoint)`: `ColumnWater.onAxis` decides the list (with water, or without:
   `model.on(compounds, false)`); a V3 input with the design's trays and the set ratios times the feed taken;
   `ColumnSolve.calculate(input, seed, catalog, screen, ColdStart.CLASSICAL, checkpoint)`: `warm` keeps the last solve's
   seed only on the same basis and with no wet tray (a START solve has none); `V3ColumnCalculator.calculateWithStrategy`
   (`AGGRESSIVE`) under the catalog, warm from that seed, else V3's own classical cold chain.
2. `SplitTable.of` maps the V3 streams to ports and prices each product; `trim`, `airArea`, the `Flood` record and the
   pumparound draw temperatures complete it; `ColumnWater.settling` adds its notes.
3. The `Outcome` (`Solved` or `Failed`) returns to the engine, which applies the table at its fixed tick.

**One cycle** (`ColumnCycle.step`, called by the engine's `cycle`):

1. The engine hands in what the accounts hold, the set points in force, each product's room, each utility's room
   (`Rooms`) and the members' power.
2. `step` lists every LIMIT that holds with the share of the set feed it allows: `FEED_SHORT`, `PRODUCT_FULL`,
   `COOLING_SHORT` (the water's room; the air's most feed, or `NO_POWER` where some banks have none), `NO_POWER` of
   each unpowered pump, `HEATING_SHORT`, `PUMPAROUND_SHORT`, `FLOODING`, `STEAM_SHORT` under rule A. The binding one
   sets `alpha`.
3. It takes the steam by rule A or B, splits feed and steam by the table, and closes the condenser duty: energy out
   less energy in, less the reboiler and pumparounds at the table's ratios.
4. It trips (`CLOSURE`, takes nothing) where the condenser would heat the overhead or depart more than
   `TRIP_DEPARTURE` from the solved duty; otherwise it adds `HIGH_LOAD` where the worst tray stands at
   `HIGH_LOAD_FRACTION` of flood or more.
5. `ColumnStatus.phase` and `line` make the status from the step's conditions.

## Contracts

- **The table.** `SplitTable.of` maps `overhead_vapor` to `OFF_GAS`, `distillate_liquid` to `DISTILLATE`, `free_water`
  to `SOUR_WATER`, `bottoms_liquid` to `BOTTOMS`, `side_liquid_tray_N` to `DRAW`. Each component's row sums to 1 within
  1e-12. A component without solved feed borrows the row of the component nearest in normal boiling point; unsolved
  water goes to the distillate; on the axis water is a solved compound. The table, its reference and the products'
  prices stay on the world list, mapped by id.
- **Product prices.** `condenserPerFeed` is the closure on the network's energy basis (`ColumnSolve.closure`: out less
  in, the reboiler and the pumparounds), not V3's (V3's own duty stays as `solvedCondenser`). Each product is priced by one flash: its enthalpy per mass at its solved temperature, pressure
  and composition, times each compound's molar mass. A cycle prices a product as Σ n_i h_i, so no property call runs on
  the server thread; the sum is exact at the solved composition, and off it the price is out by the compounds'
  differences in enthalpy per mass (`ColumnSolveTest` prints and bounds it); the cycle books the difference at the
  outlets.
- **The condenser's shares** (the job carries the design's `ColumnComponents.Condenser`):

  | Condenser | `condenserWaterPerFeed` | `waterBelow` | `airArea` |
  |---|---|---|---|
  | EXCHANGER | the whole duty | `WATER_ALWAYS` | empty |
  | AIR_COOLER | 0 | `WATER_NEVER` | top tray to the receiver |
  | AIR_THEN_WATER | `trim`: the overhead from the split to the receiver | `HeatSides.AIR_SPLIT_TEMPERATURE` | top tray to the split |

  `trim`: the top tray's vapour with its water vapour (V3's inspection node 1), flashed at the condenser's pressure; the
  split is `HeatSides.airSplit(top, receiver)`, never below the receiver nor above the top vapour; the trim never
  exceeds the whole duty. `airArea`: the overhead's curve (`HeatCurve.sample`) marched against 25 °C air with
  `HeatSides.airFilms` at each fan step from natural draught to full; none where the overhead already arrives at the
  air's outlet, `Double.MAX_VALUE` where the outlet is at or below 25 °C.
- **Flooding.** `Flood` is V3's `V3TrayHydraulicsSummary` reduced to the worst tray's flood fraction per mol/s of feed,
  the tray, whether vapour limits it, the total dry drop and liquid head; `Flood.NONE` at a prescribed drop. At one
  table the flood fraction is proportional to the feed taken, so `FLOODING` allows 1/(fraction at the set feed). It is
  Fair's flood fraction alone: no downcomer backup check. The live drop is `Flood.drop(ratio)`: the dry part by the
  square of the feed ratio, the liquid head as solved; port pressures do not move with it.
- **Feed water** (`ColumnWater`). On the axis where the feed holds a compound that names a homomorph above `SHARE` of
  its moles (a hydrocarbon never does): the column runs on the island's list with water, and a taken steam with a rate
  is refused (`STEAM_WITH_WATER_ON_AXIS`). Off the axis, feed water enters as steam at the feed tray if it is at least
  `SUPERHEAT` above its boiling point there and a steam slot is free (`steamAtFeedTray` applies V3's own steam admission:
  Wagner-Pruss at the injection pressure, the feed temperature inside the water data), else it follows the distillate
  with a note. After an accepted solve on the axis, `settling` flashes each tray's liquid and the distillate where water
  and a non-polar compound each pass `TRACE`; a liquid that would settle is a note (`two liquids on trays …`), never a
  failure. A seed with wet trays is dropped. A feed near the share may flip between the two rules from one solve to the
  next.
- **Conditions.** One list of `Condition.Active` per cycle and solve; the status line, the Operation tab and the
  column's alarm channels all read it. LIMITs: `FEED_SHORT`, `STEAM_SHORT`, `PRODUCT_FULL`, `COOLING_SHORT`,
  `HEATING_SHORT`, `PUMPAROUND_SHORT`, `FLOODING`, `NO_POWER`. OFF_TABLE: `OFF_TABLE` (a solve pending, its trigger as
  text), `SOLVE_REFUSED`. TRIP: `CLOSURE`. NOTICEs: `SETPOINT_REFUSED`, `ADVISORY` (V3's advisory texts, at most
  `MAX_ADVISORIES`), `FEED_WATER_ROUTED`, `UNMETERED`, `HIGH_LOAD`. `Condition.binding` takes the TRIP, else the LIMIT
  with the lowest share (the first of equal ones). No limit asks a solve. Engine state, saved nowhere.
- **Status words** (`ColumnStatus`): STARTING or NOT READY before a table; UPSET for a TRIP, LIMITED for a LIMIT, else
  RUNNING; `LIMITED: <word>, N %` (the binding condition's word: `condenser`, `reboiler`, `flooding`, `power`, …),
  `RE-SOLVING: <trigger>; …`, then the refusals and notices. Start refusals are said after `NOT READY: `.
- **Rule B** (the default) takes what steam there is; `STEAM_SHORT` with share 1, so it binds only where nothing else
  does, and the drifting steam ratio asks a STEAM_RATIO solve. **Rule A** (set by a refused STEAM_RATIO solve): steam
  limits the whole take, feed and steam cut together at the table's ratio (`RULE_A`). A successful STEAM_RATIO solve
  clears it; so does a cycle, tried first under rule B, whose steam is no longer short or whose shortage left the
  refused state's bands.
- **Set points.** `Setpoints.intensivelyDiffers` decides a SETPOINT solve; a change of feed rate alone is not one,
  because the column's equations are homogeneous in their extensive inputs (only THROUGHPUT reads the rate).

## Laws and constants

`DriftBands.check`, in this order, on what a cycle took against the table's `Reference`:

| Trigger | Band (constant) |
|---|---|
| COMPONENT | a share over `NEW_COMPONENT`, in the feed or a steam, of a component whose row the table borrowed |
| COMPOSITION | the feed's shares over `COMPOSITION` from the reference, summed |
| ENTHALPY | the feed's specific enthalpy over `ENTHALPY_KELVIN` of its own slope from the reference |
| STEAM_RATIO | a steam's ratio over `STEAM_RATIO` from the solved one, relative to the larger of solved and set |
| THROUGHPUT | the fed share of the design rate over `THROUGHPUT` from the table's `alpha` |
| CLOSURE | the closed condenser duty over `CLOSURE` from the solved one |

The second form, `check(reference, held, step, demand)`, checks the first five against any reference without the
closure: it decides whether a state stands inside a refused state's bands. `SETPOINT` comes first in the engine's
cycle; `START` is the first solve.

- A port's pressure (`PortConditions.estimate`): V3's stage pressure, top pressure plus (j − 1) stage drops for tray
  j, the sump at the bottom tray's, the condenser's ports at the top pressure; at the feed's, the steam's or the
  condenser's temperature. Only a product's temperature comes from a table.
- Fans (`SplitTable.fans(airSurface, feed)`): the least fan speed whose surface per feed (`airArea`, linear between the
  steps) the built banks reach, 0 where natural draught holds; their power `HeatSides.fanPower`, by the cube of the
  speed; `airFeed(airSurface)`, the surface over the full-speed area, is the most feed full fans hold.
- Port arithmetic (`PortAccounts`): `CYCLE_TICKS` online ticks a cycle, `FENCE_TICKS` the fence both units read; an
  outlet's debit takes its energy by share and reports an overdraw.

## Formats

`Port` and the set points are saved by the engine's facts; the table is engine state ([column-run.md](column-run.md)).
Numbers: [FORMATS.md](../FORMATS.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| What a cycle takes, a limit, a rule, the trip, the bands, the split's sum | `ColumnCycle`, `DriftBands` | `ColumnCycleTest` (components and energy close over a thousand cycles of drifting feed; rules A and B; a full buffer; the trip; every limit listed and the lowest binding; each utility's room bounds its own duty; flooding against a short feed; a booking difference to the water below the split) |
| A table, its streams, prices, shares, flooding | `SplitTable.of`, `ColumnSolve.run`, `trim`, `airArea` | `ColumnSolveTest` (a START table closes a cycle on the network's basis; half the feed at a prescribed drop gives the same table; a saved point on a removed package starts; the trim rule; the air-then-water water share at receivers of 45, 38 and 30 °C; the pricing bound; `theFloodPerFeedIsLinearInTheThroughputBand`) |
| Feed water, settling | `ColumnWater` | `ColumnWaterTest`, `ColumnSolveTest` |
| A port's conditions | `PortConditions` | `PortConditionsTest` |
| The shared port arithmetic | `PortAccounts` | `PortAccountsTest` (the lists' mapping, the mass, an account as of the fence, the debit, a dated log's window, the two constants) |
| A new condition, its rank, word or alarm | `Condition`, `ColumnStatus`, `EquipmentAlarm.of`; the tab's key in `OperationColumn` | `OperationColumnTest`, `BuiltInInputsTest` |
| Everything in the running world | the engine | `ColumnRunTest` ([column-run.md](column-run.md)) |

## Traps

- `ColumnSolve.SUPERHEAT` is public because the simulator's sizing `Limit` reads it; `ColumnSolve.trim`, `calculate`
  and `closure` are called by the sheet's column block too (`ColumnBlockSolve`; `calculate` also by `ColumnSizing`'s
  trials). The sheet's column block and the running column read the same `ColumnWater` rule.
- A table's `condenserPerFeed` is not V3's condenser duty: it is the closure on the network's energy basis.
- `Setpoints.withFeedRate` caps at the design rate: there is no turndown floor, and no feed above the design.
- `DriftBands.check` without the closure is for refused states only; a running table's check includes it.
- The sheet and the world share one solve but not their cold start: `ColdStart.CLASSICAL` here (V3's fast classical
  first solve at the gameplay closure), `LEARNED` on the sheet (the learned start at the strict closure). A cold result
  of the same input can differ between them within the gameplay closure.
- The sheet's feed water still goes its own way: an aqueous liquid in the feed is refused there, and its water joins V3
  as steam at the feed tray with no superheat test, where this solve lets water V3 does not admit follow the distillate.

**Snapshot:** `9d6d0d82e`, 2026-11-15.
