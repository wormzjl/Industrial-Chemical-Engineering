# Plant coupling review (S8): where the column and the network meet

Base `5f61d3139`, read-only review, 2026-11-13. Paths are relative to `src/main/java/com/wormzjl/icheme/`. Read closely: `science/column/operation` (ColumnStatus, OperationView and Refusal skimmed only), `runtime/fluid/ColumnOperation`, `ColumnUnit`, `ColumnPower`, `Stills`, `StillUnit`, `science/fluid/network/StillColumn`, `StillHead`, `BatchStill`, `PortPlan`, `science/flowsheet/unit/ColumnUnit`, and these parts of `IslandCoordinator`: 236-320, 450-470, 590-626, 1060-1267. Signatures only: V3, `PassiveStepSolver` (2553-2602 read), `FluidFacts`, `ColumnBlockSolve`, `ProcessSolveServices` 420-490, `FluidWorldAuthority` 140-210.

## 1. Summary

A running column is a split table (one V3 solve) that a 5 s cycle applies to port accounts. The network sees each port as a void or a generator behind a boundary valve with a mass target (`PortPlan`), and books what crosses into the accounts. Duties go to the utility sides through the exchangers' heat in transit. A batch still uses the same port hooks, but its own column solver (`StillColumn`) lives in the fluid package and runs on the network's thermo.

**Five largest problems**

1. **The column's energy books use the network's thermo.** `ColumnSolve.solve` prices every product with network flashes (`partial`, ColumnSolve 430-450) and makes the condenser duty a closure on the network basis (264-299). It also inverts the feed temperature by a secant on network flashes (410-422), and marches the air condenser on `HeatCurve` (374). So a change to the network kernel, water model or pairs moves the column's duties, its CLOSURE band and its trips. This is the deepest correlation, and REVIEW_PLAN section 1 does not list it.
2. **A second column solver sits in the fluid package.** `StillColumn` (1 292 lines), `StillHead` and `BatchStill` are a Wang-Henke column on the network's `HydrocarbonModel` and `FluidThermodynamics.flashTP`. `Stills` and `StillUnit` use the column's `ColumnCycle.Account` as a fact type (Stills 3, StillUnit 3).
3. **Two near-copies of the unit-port machinery.** `ColumnOperation` and `Stills` each carry their own fence and cycle constants, basis maps, outlet plan, outlet debit with an OVERDRAWN log, the "water always" compound rule, transit owing and the column-type heat read (about 150 duplicated lines, finding C6).
4. **`science/column/operation` imports fluid, equipment and material.** It uses `HeatCurve`, `FluidThermodynamics`, `HeatSides`, `ColumnComponents`, `ColumnOperatingPoint`, `ColumnTrays` and `DerivedPairs`. V3 receives its catalog through a ThreadLocal (`MaterialRuntime.with`, ColumnSolve 162).
5. **The sheet and the world solve one design by two routes.** The sheet's block: warm solve, then cold LNN_FIRST with the bundled network, at the default screen ratio, with V3's own condenser duty plus an `energy_gap` reading (flowsheet ColumnUnit 203-209, ColumnBlockSolve 169). The world: AGGRESSIVE with the seed, the configured screen ratio (FluidWorldAuthority 166), and the network closure (ColumnSolve 248, 299). One design can solve on the sheet and be refused in the world, and the duties differ by the basis gap.

**Ten highest-value changes (gain over cost)**

1. C6: one `PortAccounts` and one `UtilityDebt` for columns and stills. About -150 lines, results bitwise identical.
2. C1: move product pricing and closure out of `ColumnSolve` into a plant `ColumnPricing`. The column job then reads no network thermo. Bitwise identical.
3. C4: feed water above water's Tc reads "below steam". A one-line defect fix (supercritical).
4. C2: price products at their specific enthalpy, one flash per product instead of (m+1). The shift is 0 at the reference state and at most about 0.3 % of the condenser duty at the band edge (estimate), returned exactly at the outlet booking.
5. C17: move `StillColumn` to `science/column/still` behind a `StageThermo` port. A move only, bitwise identical.
6. C13: reverse the equipment imports of `column/operation`: the factories go up into equipment, the condenser kind and the air laws go down into plant.
7. C5: one `ColumnJob` (input, route, screen, pricing) for the sheet and the world.
8. C16: pass the catalog to V3 explicitly, not through the ThreadLocal. This is a prerequisite of the Rust port.
9. C14: drop the five legacy `SplitTable` constructors. The table is state, and a mismatch rebuilds it by a solve.
10. C12: `ColumnWater.settling`'s one engine flash per tray, for a notice only.

## 2. Module map

Today's files and lines:

| Area | Files (lines) |
|---|---|
| Cycle arithmetic, pure | `ColumnCycle` 367, `Condition` 113, `DriftBands` 109, `Setpoints` 78, `Port` 19, `ColumnStatus` 61, `OperationView` 120, `Refusal` 48 |
| V3 adapter and pricing, mixed | `ColumnSolve` 476, `SplitTable` 463, `ColumnWater` 118, `PortConditions` 35 |
| Server units | `ColumnOperation` 1 117, `ColumnUnit` 195 (facts), `ColumnPower` 65, `Stills` 569, `StillUnit` 52 (facts) |
| Still science, in fluid | `StillColumn` 1 292, `StillHead` 158, `BatchStill` 596 |
| Network boundary input | `PortPlan` 32, `PassiveStepSolver.ports` 2563-2597 |
| Sheet | `flowsheet/unit/ColumnUnit` 211 (+ `ColumnBlockSolve`, `BatchStillUnit`, `StillBatch`) |

Proposed sub-modules. `science/plant/**` is the only place where column and fluid types meet:

| Module | Moves in | Imports allowed |
|---|---|---|
| `science/column/job` | `ColumnSolve` 168-250 (V3 input from set points and the take), `SplitTable.of`/`port` 350-437, `ColumnWater.onAxis` | column/v3, material |
| `science/column/still` | `StillColumn`; the solve part of `StillHead.run` 69-90 | a `StageThermo` interface of its own, math |
| `science/plant/port` | `Account` (ColumnCycle 34-79); `PortAccounts` (ColumnOperation 858-921 and 941-989, Stills 273-294, 382-392 and 542-553); `FENCE_TICKS` and `CYCLE_TICKS` | fluid (`PortPlan`, `SolidInventory`) |
| `science/plant/heat` | `UtilityDebt` (ColumnOperation 91-116 and 995-1112, Stills 394-427); the condenser kind (`ColumnComponents.Condenser`); the air and approach laws now in `HeatSides` | fluid laws |
| `science/plant/column` | `ColumnCycle`, `Condition`, `DriftBands`, `Setpoints`, `Port`, `ColumnStatus`, `OperationView`, `Refusal`, `SplitTable` (record only); `ColumnPricing` (ColumnSolve 259-325 and 327-461), `ColumnWater.settling` | column/job, fluid/thermo, plant/* |
| `science/plant/still` | `BatchStill` laws, `StillHead.divide`/`liquidShare`, the `StageThermo` adapter on `HydrocarbonModel` | column/still, fluid/thermo |
| `runtime/plant` | `ColumnOperation`, `Stills`, `ColumnPower`, `ColumnUnit`, `StillUnit` | the above, runtime/fluid |

Saved names do not move: `Port` is saved by name, and the facts records keep their fields (FluidFacts 555-558, 736-764).

Imports that cross the wrong way today:

- `column/operation` → `fluid/network.HeatCurve` and `fluid/thermo.FluidThermodynamics` (ColumnSolve 16-17, ColumnWater 4).
- `column/operation` → `equipment` (ColumnSolve 14-15, SplitTable 7, PortConditions 3-4, Setpoints 3).
- `column/operation` → `material.MaterialRuntime` (ThreadLocal) and `DerivedPairs`.
- `fluid/network` holds a column solver (StillColumn 57). `fluid/network.StillColumn` → `fluid/thermo.HydrocarbonModel` internals (108, 126-131).
- `runtime/fluid.Stills` and `StillUnit` → `column/operation.ColumnCycle.Account`.
- `runtime/fluid.ColumnOperation` → `fluid/network.FlowControl` internals (323-325), `equipment.HeatSides` (94, 383, 728), `column/v3.V3ColumnInspection` node indices (397).
- `fluid/network` (PassiveNetwork 298, ControlConstraints 125, PassiveStepSolver 2590) knows a column-worked valve by `PortPlan.COLUMN_SENSOR`.

## 3. Findings

### 3.1 Every meeting point (brief item 1)

| # | Caller → callee | Data that crosses | Thread |
|---|---|---|---|
| M1 | `IslandCoordinator.pump` 602 → `Ports.ready` (ColumnOperation 817, Stills 250) | fence: `nextCycle` against the slice start less 440; the still calls `coordinator.through` (materialises) | server |
| M2 | pump 612 → `Ports.dispatch` (Stills 159) | the pot `Pool` firing and head; the burners' valve target | server |
| M3 | `reads` 1138 → `Ports.heatRead` (ColumnOperation 1096, Stills 416) | `ExchangerRelay.Read.column`/`heating`: transit due, ceiling or floor, heat rate; the still records `paid` and `transit` here | server |
| M4 | pump 613 → `Ports.plans` (ColumnOperation 826, Stills 261) → `FluidIslandCommand` → `PassiveIntervalSolver.solve` 89 → `PassiveStepSolver.ports` 2563 | `PortPlan`: valve mass target; an outlet's composition, temperature and solids → generator state (2 flashTP), `ControlValve(MASS_FLOW, COLUMN_SENSOR)` | server → worker |
| M5 | commit → `IslandCoordinator.booked` 1158 → `Ports.booked` (ColumnOperation 923, Stills 296) | the whole `PassiveIntervalSolver.Result`: EXCHANGE, VENT, SPILL and HEAT boundaries, `averageMassFlows`, the graph | server |
| M6 | decision points 570, 620, 697 → `Ports.decide` (ColumnOperation 939) → `due`/`cycle` | none (the cycle runs) | server |
| M7 | ColumnOperation → coordinator `schedule` 501, `through` 639/642, `portsMoved` 511, `owe` 1082, `transitAsOf` 1057, `operation` 320, `presentation().graph()` 323/326, `model().components()` 908/915, `time`, `now` | ticks, transits (J), the valve operation, the island's compound list | server |
| M8 | Stills → coordinator `owe` 398, `controlsMoved` 346/400/462/527, `sideTemperature` 450 | condenser heat (J), wakes, the water's inlet temperature | server |
| M9 | `ColumnOperation.request` 585 → `Solves.submit` (FluidWorldAuthority 160-166) → `ColumnUnitCommand` (ProcessSolveServices 448-452) → `ColumnSolve.run` | job (V3 input, set points, take, seed, condenser kind) plus the feed island's `FluidThermodynamics`; V3 inside `MaterialRuntime.with`; network flashTP for feed T, steam T, partial enthalpies, trim, air area, settling | server → shared worker pool → server (`done` 601) |
| M10 | `Stills.submit` 441 → `StillCommand` (ProcessSolveServices 470-474) → `StillHead.run` → `StillColumn` | pot moles, T, P, trays, reflux, warm profile, outlet floor; the pot island's model | server → worker → server (`completed` 455) |
| M11 | `ColumnPower` → `ElectricStores.member`, `ShaftTrains.held`/`unitOf`/`driverOf`, `InlineMachines` | fan and pump power each cycle | server |
| M12 | `FluidFacts` 555-764 ↔ `ColumnUnit`, `SplitTable`, `Account`, `StillUnit` | facts (accounts, totals, set points) and state (table, enthalpies, windows) | save/load |
| M13 | equipment `ColumnOperatingPoint` → `Setpoints.of` 34, `PortConditions.estimate` 21 | the design's point | server, at Start |
| M14 | sheet: `ColumnBlockSolve` → `ColumnSolve.trim` 228, `ColumnWater` 244-249; `BatchStillUnit` 160, `StillBatch` 170 → `StillColumn`, `BatchStill.step` | sheet thermo | client tool |
| M15 | `PassiveNetwork` 298, `ControlConstraints` 125 → `PortPlan.COLUMN_SENSOR` | "a valve the column works" | worker |

### 3.2 The running column's contract (brief item 2)

Mass is clean. A plan is a mass target and a generator state. The account is debited exactly what the island booked, component by component (ColumnOperation 955-962), and the split rows sum to 1 (SplitTable 168). Energy is exact by construction: the difference between booked energy and the account's share goes to the condenser totals (960) and, below `waterBelow`, to the water (963). The fence (`FENCE_TICKS` 440) makes the results independent of the island timing.

What leaks through the contract:

- **L1:** the energy basis of the table (C1).
- **L2:** the status line reads network valve laws (C7).
- **L3:** side-identity arithmetic (C8).
- **L4:** `COLUMN_SENSOR` in network control code (C24).
- **L5:** column semantics grow the island's compound list ("Water always", 900-905, 887-894). This one is physical (steam reaches every product) and should stay, but in plant.
- **L6:** the column books into the exchanger transit map as if it were a side (IslandCoordinator 1254, key `min(side, partner)`).
- **L7:** the `Ports` hooks receive whole solver results and graphs (C23).

### 3.3 Findings table

| id | file:line, symbol | what it is | proposed change | class | lines | risk | proof |
|---|---|---|---|---|---|---|---|
| C1 | column/operation/ColumnSolve.java:259-325 `solve` | Products priced and the condenser closed with network flashes inside the column job | Move pricing, closure, `trim` and `airArea` to plant `ColumnPricing`. The job returns V3 recoveries, T, P, duties and hydraulics | STRUCTURE | ~150 moved | none | ColumnSolveTest, ColumnRunTest bitwise |
| C2 | ColumnSolve.java:430-450 `partial` | (m+1) flashTP per product: 65 × 8 = 520 at 64 compounds and 8 products | Specific enthalpy at the product's T and P, 1 flash per product. Error is Σ(x_i - x_i^s)(h_i - h̄) n: 0 at the reference composition. At the composition band edge (L1 0.005, DriftBands 16) with a liquid h_i spread of about 80 kJ/mol (estimate), at most about 0.2 kJ/mol of product, about 0.3 % of a 60 kJ/mol-feed condenser; 10× under the CLOSURE band (3 %, DriftBands 24). Returned exactly at the outlet booking (ColumnOperation 958-963) | PHYSICS | -25 | a draw with a large relative composition move: check the largest departure on the fixtures | ColumnSolveTest: a band-edge case; ColumnRunTest totals exact |
| C3 | ColumnSolve.java:37-40, 299 | Owner option beyond C2: price with V3's own product enthalpies. The basis gap ("a few tenths of a percent of the throughput's enthalpy", "several percent of a small condenser") moves from the cycle closure to the outlet booking difference, which lands at least 440 ticks later | Remove every network flash from the column job except the feed temperature | PHYSICS | -60 | AIR_THEN_WATER sends a booking difference to water or air by product temperature (SplitTable 305), so the gap shifts between them | ColumnRunTest totals; a fixture of the delayed load |
| C4 | ColumnSolve.java:218 | `saturationPressure` is +∞ at T ≥ 647.1 K (FluidThermodynamics 674), so the water of a feed above water's Tc (a hot wet vacuum feed, 670-690 K) reads "below steam" and follows the distillate | `superheated = t - SUPERHEAT >= Tc_water ‖ psat(t - 5) < p` | DEFECT (supercritical) | +1 | moves only those feeds | ColumnSolveTest: wet feed at 680 K |
| C5 | flowsheet/unit/ColumnUnit.java:203-209; ColumnSolve.java:248; ColumnBlockSolve:169 | Two solve routes, two screens, two energy closures for one design | One `ColumnJob` (route: warm then cold-neural; one screen; one pricing) for the block and the world | PHYSICS | -40 | the sheet's references move by the basis gap | ColumnBlockTest, ColumnSolveTest |
| C6 | runtime ColumnOperation 40-42, 858-921, 941-966, 1077-1112 vs Stills 49-55, 273-294, 382-403, 416-427, 542-553 | Duplicated port and transit arithmetic | `PortAccounts` + `UtilityDebt` in plant, used by both | STRUCTURE | ~150 removed | none | ColumnRunTest, StillRuntimeTest bitwise |
| C7 | ColumnOperation.java:316-331 `water` | The status line walks the island's presentation graph for `ControlValve`/`MASS_FLOW` pipes | Read the target the column set (the `PortPlan`, or the coordinator's `Operation`) | STRUCTURE | -8 | status text only | ColumnRunTest status lines |
| C8 | ColumnOperation.java:1110; Stills.java:308 | `x - sideIdentity(0)` by hand | `PhysicalFluidTopology.physicalOf(side)` beside `sideIdentity` (504) | STRUCTURE | 0 | none | compile, StillRuntimeTest |
| C9 | ColumnOperation.java:596, 620 | water index `weights.length-1`; 793 and ColumnSolve use `indexOf(WATER)` | One index from the axis | STRUCTURE (latent defect) | 0 | none while the network axis ends in water | ColumnRunTest |
| C10 | Stills.java:257 `ready` | `coordinator.through` materialises (IslandCoordinator 465) inside the dispatcher's readiness predicate (602); the column's `ready` reads only its own state (817-825) | A committed-tick read, or the check in `decide` as the column does | DEFECT (risk) | ~5 | a still whose outlet island is certified | StillRuntimeTest: an outlet on a certified island |
| C11 | Stills.java:154 `sync` | Lookups rebuild only when the still count changes | Explicit refresh only (FluidWorldAuthority 938, 984, 2816) | STRUCTURE | -2 | none found | StillRuntimeTest |
| C12 | column/operation/ColumnWater.java:56-88 `settling` | One engine three-phase flash per wet tray on every accepted solve, for a notice | The distillate and the trays V3 already flags, or V3's own stability (S5) | PERF | -15 | notice wording | ColumnWaterTest |
| C13 | ColumnSolve 14-15, SplitTable 7 and 242, PortConditions 3-4, Setpoints 3, ColumnOperation 72 | column/operation → equipment | `Setpoints.of`/`PortConditions.estimate` move to equipment; `Condenser` kind, `WATER_APPROACH`, `airFilms`, `airSplit`, `fanPower` move to plant/heat or fluid laws (S4) | STRUCTURE | ~60 moved | none | compile, ColumnSolveTest |
| C14 | SplitTable.java:181-249 | Five constructors for "a table before X existed"; FluidFacts 763-764 uses two | One constructor; an old state fails validation and the column solves once on load | STRUCTURE | -50 | one START solve per running column on the first load | new corpus image, ColumnRunTest |
| C15 | SplitTable.java:423-437; ColumnSolve 321, 343; ColumnOperation 397 | V3 stream ids as strings and node indices (0 condenser, 1 top, last sump) outside V3 | A typed product list and named nodes on V3's result (S5) | STRUCTURE | 0 | none | ColumnSolveTest |
| C16 | ColumnSolve.java:162; flowsheet ColumnUnit:205; MaterialRuntime 10 | V3 reads its catalog through a ThreadLocal | The catalog in V3's thermo port (Rust needs an explicit input) | STRUCTURE | ~10 | none | ColumnSolveTest, V3 digests |
| C17 | fluid/network/StillColumn.java:57-1292 | A column solver in fluid, on `HydrocarbonModel.phase/prepare` (701-702, 929-937, 1163-1207) and `flashTP` for wet liquids (863, 914); per-solve state in fields (93-102) | `science/column/still` over `StageThermo` (lnφ_L, lnφ_V, h, v at T, P, x; a TP split of a wet liquid); fields into a solve context | STRUCTURE | 1 292 moved | none | StillModelTest, StillHeadTest, BatchStillTest bitwise |
| C18 | StillColumn 989-1006, 1273-1285; V3FeedFlash 84, V3TruncatedFlash 122, V3AcceptanceAuditor 964, V3ColumnInitializer 391-436 (signatures) | Four Rachford-Rice solvers, two tridiagonal sweeps, two bubble updates | A shared stage kernel in S5's math crate, after the port | STRUCTURE (last-digit moves where the RR algorithm changes) | ~60 | digests move at about 1e-15 | StillModelTest, V3 digests |
| C19 | BatchStill.java:549 | "pot lost its bubble point" throws `IllegalStateException` in the sheet's still step: a hold by exception, for example on a charge whose light end boils under the 60 K box (StillColumn 78) | End the step at BOIL DRY with the pot's state, or a failed split | DEFECT | +3 | sheet only | BatchStillTest: hydrogen-rich charge |
| C20 | PassiveStepSolver.java:2575-2577 `ports` (S3's file) | Two flashTP per moved outlet plan; the second only rescales the moles to the volume | Scale the first state (TP flash is homogeneous in the moles) | PERF (round-off) | -2 | solids and energy scaling in `State` | ColumnRunTest, StillRuntimeTest |
| C21 | runtime/fluid/ColumnPower.java:45 `pump` | A member pump books rated shaft power × the cycle's feed share; a reflux-ratio change does not move it | Share = the table's reflux mass per feed (`Probes.refluxPerFeed`, ColumnUnit 188) over the design's | PHYSICS (owner option) | +5 | member power numbers move with R | ColumnRunTest power booking |
| C22 | ColumnOperation.java:431 `instrument` | Each COLUMN_PORT sample builds the whole view, including the water status walk | Read the stream from `r.last` and the table | PERF | -3 | none | ColumnProbeTest |
| C23 | FluidWorldAuthority:192 `Ports.both` nesting; IslandCoordinator 246-287 | Four unit hooks; every commit scans every boundary, every plan every reservoir, per hook | One plant hook with a per-island index of port and side ids; a narrow booking view instead of `PassiveIntervalSolver.Result` | PERF | ~20 | none | campaign counters |
| C24 | PassiveNetwork 298; ControlConstraints 125; PassiveStepSolver 2590 | The network's control code tests a UUID for "worked by a column" | A `PORT` valve source or a flag on the law | STRUCTURE | 0 | none | fluid gates |
| C25 | ColumnOperation.java:550 vs 385 | play seconds as `ticks/20` in one line and `SimulationTime.playSeconds` in the other | One helper | STRUCTURE | 0 | text only | none needed |

### 3.4 StillColumn against V3 (brief item 3)

**What it solves.** A quasi-steady rectifier in pot mode: stages 0 (condenser) .. N (trays) .. N+1 (pot, liquid fixed). The rates are per mole of distillate. Each iteration solves one tridiagonal balance per component (Thomas, 418-437), then each stage's bubble point (1130-1158). The flows run on constant molar overflow until a stage moves less than 1 K, then come from Wang-Henke's energy balances (1103-1123). Around this sit a three-phase decanter (oil first), a partial condenser with an outlet floor, a short-reflux secant and a pot-still fallback.

**Thermo calls.**

- `HydrocarbonModel.prepare` and `.phase(T, P, x, Root.LIQUID|VAPOR)`, for ln φ, H and V. These are explicit root requests: subcritical.
- `FluidThermodynamics.flashTP` with `holdsPhase` and `labelAmountsView`, for wet liquids only (863-878, 914-917).
- `StillHead.run`: one flashTP for the returned liquid (105).
- One solve per `SOLVE_TICKS` (100) per boiling still (Stills 51).

| Option | Cost | Result shift |
|---|---|---|
| (a) Keep the model; move it to `science/column/still` over a `StageThermo` port the network adapter implements (C17) | Package move and an interface of about 100 lines | None: bitwise identical. The still stays on the network's property identity, which is right for a unit simulated inside an island |
| (b) Rebuild on V3's stage model | V3 needs a pot mode (bottom liquid fixed, vapour up, no reboiler), a single pressure, the oil-first miscible decanter, a partial condenser with an outlet floor, a closed valve, and 2 to 8 trays. A V3 warm solve every 5 s per boiling still replaces one tridiagonal sweep | K values on the same kernel move little. The enthalpy basis moves by the V3-to-network gap (ColumnSolve 37-38). This moves `energyPerDistillate` and so the distil rate by the same order. Every still reference moves |
| (c) A shared stage kernel (Thomas, bubble point, Rachford-Rice, Wilson, energy-balance flows) behind `StageThermo`, used by `StillColumn` and V3's initializer and flash helpers | About 60 lines of shared code, and V3 must adopt the port | Zero for the still; last digits where V3's RR variant changes (C18) |

Recommendation: (a) now, (c) as part of S5's Rust math crate. (b) costs most and moves every still reference, and the owner's de-correlation goal does not need it.

### 3.5 Accuracy trades that loosen the coupling (brief item 5)

Each trade is deterministic, and conservation stays exact because the outlet booking returns every difference.

| Trade | Expected shift |
|---|---|
| T1 = C2: specific instead of partial molar enthalpies | 0 at reference, at most about 0.3 % of the condenser per cycle at the band edge (estimate). About 90 % fewer post-solve flashes |
| T2 = C3: V3-basis pricing | A few tenths of a percent of the throughput's enthalpy, moved from the cycle to the outlet booking, delayed at least 440 ticks. The run totals are identical |
| T3: the feed temperature from the inlet's booked state instead of a network secant (ColumnSolve 186-189) | 0 for a steady feed; the mixing error of the account's windows otherwise. Needs the void's booked temperature in the span (not in `BoundaryTransfer` as read here; to check with S3) |
| T4: `ColumnWater.settling` on the distillate only (C12) | No numeric shift; the notice names fewer trays |
| T5 = C21: pump power by reflux flow | Power only, no process result |

### 3.6 Subcritical assumptions in scope (for S1)

- ColumnSolve 218 (C4).
- StillColumn 78 (box 60 to 1400 K), 699-707 (`liquidRoot`), 1161-1198 (root requests and the bubble method).
- BatchStill 549 (C19).
- Stills 68 and 472 (`LATENT_CEILING`, water's 1 atm latent heat).
- SplitTable 385-387 (water without a solved row condenses with the distillate).
- ColumnWater 100 (OIL and AQUEOUS labels).

## 4. Open questions for the main thread

1. Pricing: C2 alone (small shift) or C2 with C3 (larger shift, the column job free of network thermo)?
2. Do the air, approach and fan laws now in `HeatSides` go to `plant/heat` or to the fluid laws (S4)? `column/operation` needs them either way.
3. Sheet against world (C5): which energy closure becomes the single one, the network closure or V3's duty plus the gap?
4. StillColumn: confirm (a) now and (c) with the Rust math crate, not (b).
5. C21: owner option on member pump power by reflux flow.
6. C14: accept one START solve per running column on the first load after the change?
7. C4: should feed water above 647 K enter as a steam feed at the feed tray, as below Tc when superheated?
8. Should the `Ports` hooks get a narrow booking view (boundary transfers by node) instead of `PassiveIntervalSolver.Result` and the graph (C23)? This touches S6's runtime split.
9. Contradiction with REVIEW_PLAN section 1, item 4: `StillColumn` is not "on `PengRobinsonKernel` directly". It uses `HydrocarbonModel` (the network adapter) and `FluidThermodynamics.flashTP`, and imports the kernel only for `Root`. So it moves with the network's references, not with the column's.
10. Refinement of REVIEW_PLAN section 2: the running column needs more than "K-values, enthalpies" from the thermo core. It needs TP flashes for pricing, feed temperature, trim, air area and settling. Either those move to the plant layer (C1), or the column job needs both ports.
