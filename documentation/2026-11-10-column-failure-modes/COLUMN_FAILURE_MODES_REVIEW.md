# Column failure modes and control: review

Snapshot `79d0a6254` (main 0.24.0), 2026-11-10. Read: `science/column/operation/**`, `runtime/fluid/ColumnOperation`, `ColumnStarts`, `FluidWorldAuthority.startColumn`/`editColumn`/`controlReserved`, `runtime/control/**`, `EquipmentAlarm`, `OperationColumn`, `V3TrayHydraulicsSummary`, `ColumnBlueprint`. No code changed, no Gradle run. The proposal is in [COLUMN_FAILURE_MODES_PLAN.md](COLUMN_FAILURE_MODES_PLAN.md).

## 1. Expected behaviour

A continuous column at steady state fails in these ways. Each has one first-order effect:

| Group | Failure | Real effect (simplified) |
|---|---|---|
| Supply | Feed short | Products fall in proportion. Sieve trays weep below about half the design vapour load (turndown 2:1). |
| Supply | Stripping steam short | Less lift: light ends stay in the bottoms and the lowest draw. |
| Supply | Feed composition or temperature moves | Product yields and temperatures move (1 K or 0.5 % of composition moves a product 1 to 2.5 %, C1 review). |
| Heat out | Condenser short (water, fans) | Less condensed. The top pressure rises to the relief valve, or the operator cuts the feed. |
| Heat out | Reflux lost (pump) | The top dries; the overhead goes heavy within minutes. |
| Heat out | Pumparound lost (pump or cooler) | Its heat goes up the column: the condenser and the trays above take it, to flooding. |
| Heat in | Reboiler heat short (steam, fuel) | Boil-up falls; the bottoms keeps the light ends. |
| Hydraulics | Vapour or liquid traffic over the tray limit | Flooding at 100 % of Fair's limit: pressure drop climbs, separation collapses. Design point is 80 to 85 %. |
| Hydraulics | Liquid short below a large draw | Dry trays (the draw wall). |
| Removal | A product line blocked or full | Its level rises, then the feed must be cut. |
| Pressure | Off-gas blocked, condenser lost | Overpressure to the relief and the flare. |
| Utilities | Power lost to pumps and fans | The same as the loss of what they move. |
| Model | No steady state for the inputs | A real column would cycle or flood; the game has no answer. |

A game-level column needs one rule for all of them: what the player sees, what the column does, and what it costs the engine.

## 2. What the code does

Seven separate mechanisms answer these, each with its own words and its own consequence:

| # | Mechanism | Where | Words | Consequence |
|---|---|---|---|---|
| 1 | Start refusals | `ColumnStarts.build` (5), `FluidWorldAuthority.startColumn` (11), `ColumnOperation` (3: no operating point, a failed first solve, a product with no nozzle) | `NOT READY: <free text>` | No start. Kept per server until the next press, not saved. |
| 2 | Throughput limits | `ColumnCycle.Limit` FEED, STEAM, OUTLET, CONDENSER | `LIMITED: <limit>, N %` | The cycle takes a share `alpha` of the set feed. |
| 3 | Trip | `ColumnCycle.step` (2 strings) | `UPSET: <text>` | The cycle takes nothing; a CLOSURE solve is asked. |
| 4 | Drift bands | `DriftBands.Trigger` (8) | `RE-SOLVING: <trigger>` | A V3 solve; on failure the old table stays with a note. |
| 5 | Rules A and B | `ColumnOperation.cycle`, `Run.ruleA` | `, inputs cut together (rule A)` | Steam only: follow the short steam (B), cut together after a failed solve (A). |
| 6 | Notes | `Run.note`, `Run.unmetered`, log `OVERDRAWN` | free text after `;` | None. |
| 7 | V3 advisories | `V3AcceptanceAuditor`, `V3TrayHydraulics.floodingWarning` | `Warning: …` in the simulator | None in the world: `ColumnSolve.Solved` does not carry them. |

The status line is these strings joined in a fixed order (`ColumnOperation.cycle`, lines 552 to 562). `OperationView` repeats part of it as `phase`, `limit`, `ruleA`, `resolving`, `failed`.

Other equipment uses a different pattern: a fired heater, a flare and a batch still each have a list of `EquipmentAlarm` constants, read by the control system as `alarm_<name>` channels and listed on the DCS. The column has none.

## 3. Coverage

| Failure | Covered | How, or what is missing |
|---|---|---|
| Feed short | Yes | `LIMITED: feed`. No turndown floor (owner Q8). |
| Steam short | Yes | Rule B, then A. |
| Feed composition, enthalpy | Yes | COMPONENT, COMPOSITION, ENTHALPY bands. |
| Product blocked | Yes | `LIMITED: outlet`, the 120 s buffer. |
| Condenser water short | Yes | `LIMITED: condenser`, the 60 s hold. |
| Air condenser face short | Yes | `SplitTable.airFeed`. |
| Air fans without power | **No** | Fan power is shown, never drawn (`ColumnOperation` line 289: "for the electricity network to come"). |
| Reflux pump, pumparound pumps | **No** | Held devices: they need no driver and cannot fail. |
| Reboiler heat short | **No** | The kettle's bundle reads `ExchangerRelay.Read.NONE`. The duty is booked from nothing (`ColumnCycle.step`: `reboiler = reboilerPerFeed * fed`). |
| Pumparound cooling short | **No** | The same: the cooler moves no heat, the duty leaves in the totals. |
| Flooding | **No** | Each solve computes `maximumFloodFraction`; the world drops it. The simulator has the lamps. |
| Dry trays, draw wall | Partly | The solve fails; the old table stays (finding F6). |
| Free water on trays, data range | **No** | Advisories dropped (mechanism 7). |
| Overpressure, relief | **No** | Ports stay at the design's estimate (`PortConditions`); a blocked off-gas is `LIMITED: outlet`. |
| Vacuum column | **No** | "a vacuum column cannot run yet". |
| No steady state | Partly | Trip only when the closure departs 50 %; findings F6 and F7. |
| Data link or sensor lost | Partly | A controller says `NO DATA LINK`; the column has nothing to lose, because nothing controls it. |

## 4. Findings

- **F1. Seven vocabularies, no shared record.** A reader of the status (the tab, a controller, a test) parses free text. Nothing says which condition binds, which are only advice, or what each does.
- **F2. No column alarm on the control system.** `BuiltInInputs.alarms` has no case for `Kind.COLUMN`. On a trip `ColumnOperation.instrument` returns null (`r.last.trip()!=null`), so every column channel on the DCS goes to `NO SIGNAL` at the moment the operator needs it.
- **F3. Flooding has no consequence in the world.** The feed is capped at the design rate, but the reflux ratio, steam, reboiler and pumparound set points are not. Raising reflux from 2 to 3 raises the top vapour by (3+1)/(2+1) = 1.33; a column designed at 80 % then stands at 107 % and nothing happens.
- **F4. Reboiler and pumparound duties are free.** Only the condenser is coupled to a utility (heat in transit to its water side, the air's face). A reboiler set point of any size is honoured with no steam or fuel. This is the deferred stage B of the utilities (heat plan W9).
- **F5. Member power is free.** See the coverage table.
- **F6. A failed solve retries without end.** `RETRY_TICKS` is 600, 1200, 2400, then 2400 forever (`applied`, line 389). A set point V3 cannot solve stays in force as a fact, so `intensivelyDiffers` asks a SETPOINT solve every 120 s for as long as the column runs: 30 failed solves an hour, each up to its budget (cost not measured here). A failed START does the same with the cold route. The tab then shows set points that the products do not follow.
- **F7. A stale table has no consequence between 3 % and 50 %.** Outside its bands with a failed solve, the column keeps splitting by the old table. The only stop is `TRIP_DEPARTURE` (closure 50 %). In between there is a note in a hover.
- **F8. V3's advisories do not reach the running column.** Water dew point, carry-over, continued properties and flooding are published for the simulator's run only.
- **F9. Pressure is fixed and unseen.** No overpressure state. A short condenser cuts the feed (the plan's alternative, a warmer condenser, is an open owner item of batch 2026-10-28-column-operation). The operating pressure is not a set point.
- **F10. The DCS cannot operate a column.** Start, Stop and Apply exist only as `AssemblyNetwork.OperationPayload` from the Operation tab. A controller whose output is a boundary valve gets `OUTPUT RESERVED` (`controlReserved`: `COLUMN_SENSOR`). So a feed tank's level cannot be held through a column's feed valve, and no loop can move a column set point.
- **F11. Set points are hard to enter.** All but the feed rate are per mole of feed (kJ/mol, mol/mol). No absolute value is shown, no bound, and a column that never ran shows meters, not entries (C7 open item).
- **F12. Not proposed here, recorded.** No turndown floor (owner Q8). Vacuum columns do not run. The relief path to a flare is not modelled.
