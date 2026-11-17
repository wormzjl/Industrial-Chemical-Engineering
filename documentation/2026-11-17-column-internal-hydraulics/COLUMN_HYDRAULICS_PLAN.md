# Column internal hydraulics: plan (2026-11-17)

Class: PHYSICS (owner's option, with numbers). Lanes: plant (`science/plant/column`, `ColumnOperation`), flowsheet and
control (`runtime/assembly`), runtime (`FluidFacts`, `FluidWorldAuthority`), client (one status word). Nothing is coded.

## 1. The gap

A running column holds every device inside its boundary valves out of the fluid network. `ColumnCycle.step` weighs the
feed, the steam, the products' room, each utility's room, flooding and the members' power. No term reads an internal
line's bore or a member pump's size. The process simulator rates both (`LineSizing.rate`, `PumpSizing.rate`), but a
rating is advice and does not reach the run. The owner (2026-11-17): a plant whose internal lines and pumps are too
small must not run as if they were right.

## 2. Physics, per loop, on the review plant

The review plant is the default template at 1/16: feed 159.8 kmol/h, condenser 1.94 MW, sour water 73.5 kmol/h. The pump
flows below are exact (the sizing log's `PUMP_RATED` readings, taken off the 1.10 design flow). The overhead's flow and
every pressure drop are estimates (about 30 %) from the duties; work package 1 replaces them with solved numbers.

The world's own laws, which the network applies to every pipe and pump outside the fence:
- a pipe passes at most `density x area x velocityLimit` (100 m/s or the acoustic bound) and loses
  `PipeResistance.pressureDrop`;
- a pump (`FlowControl.Pump`) moves its set volume flow and adds at most its set pressure. A casing's rated flow is
  liquid at 3 m/s in its discharge (`UnitRatings.pumpFlow`: 21.2 m3/h at 0.05 m, 84.8 at 0.10 m), its head 100 m.

### 2.1 Overhead vapour line (column top, condenser, receiver)

About 57 mol/s of vapour (the steam and the naphtha), 1.1 m3/s at 1.7 bar and 400 K, 3.6 kg/m3.

| Bore | Speed | Drop over 20 m | Share the 100 m/s cap allows | Share a 10 % drop budget allows |
|---|---|---|---|---|
| 0.20 m (sized) | 36 m/s | 0.04 bar | 100 % | 100 % |
| 0.15 m | 63 m/s | 0.16 bar | 100 % | 100 % |
| 0.10 m | 143 m/s | 1.2 bar | 70 % | 38 % |
| 0.05 m (as built) | 570 m/s | 38 bar | 18 % | 7 % |

The flow is fixed by the split table, so a line that passes less cuts the feed. The speed cap alone is lenient: at the
cap a 0.05 m line would lose more than the column's whole pressure. The drop is what binds in a real plant.

### 2.2 Reflux loop (receiver, pump, tee, column top)

The pump must move the whole condensate (reflux and distillate): its flow is fixed by the table. A pump whose rated flow
is under it cuts the feed in proportion. The review plant's 0.05 m pump is inside its window (14.8 to 25.4 m3/h), so it
does not bind. Head: at 20 m3/h a 0.05 m line loses about 1.3 kPa/m, 0.4 bar over 30 m, against 100 m of head (about
7 bar): the pump takes it. A 0.025 m line would lose about 40 kPa/m, 12 bar over 30 m: share about 75 %.

### 2.3 Pumparound loops

The column model has no circulation flow: it has the duty. The simulator derives a circulation from an assumed 60 K
fall across the cooler (`DesignBasis.paFall`, `BlockSizing.circulation`), and `PUMP_RATED` reads against that. In a
real plant a smaller pump circulates less and the liquid returns colder; the duty stays until the return temperature
meets the cooler's cold end.

| Pumparound | Needed at a 60 K fall | Pump | Fall at the pump's rated flow |
|---|---|---|---|
| 1 | 32.7 m3/h | 0.05 m, 21.2 m3/h (170 % at design flow) | 93 K |
| 2 | 46.5 m3/h | 0.10 m, 84.8 m3/h (60 %) | 33 K |
| 3 | 27.6 m3/h | 0.05 m, 21.2 m3/h (143 %) | 78 K |

With cooling water at 30 C and draws from about 150 C upward, a 93 K fall is feasible. So the small pumparound pumps of
the review plant would not cut the duty in a real plant. They would where the fall the pump forces exceeds the draw
temperature less the water's inlet temperature and the approach.

The lines: at 36 m3/h a 0.05 m line runs at 5.1 m/s and loses about 4 kPa/m, 1.2 bar over 30 m. A 100 m pump takes it.
The sized 0.15 m line loses 0.5 kPa over the same 30 m. Liquid speed never nears the 100 m/s cap (707 m3/h at 0.05 m).

### 2.4 What follows

- The first proposal (lines by speed alone, pumps by rated capacity with 20 % spare) is wrong on both counts: speed
  never binds a liquid line, and a pumparound pump's size sets the fall, not the duty.
- In the review plant as built, a real plant would be limited by the overhead line (7 to 18 % of feed) and by nothing
  else. The 0.05 m liquid lines cost head the pumps have.

### 2.5 Outlet ports: head and elevation (owner, 2026-11-17)

An outlet's port is a generator in the cell on its boundary valve's column side, stated at the design's stage pressure
(`PortConditions.estimate`, fixed at Start) wherever that valve stands. The pipes between the nozzle and the valve are
held, so the liquid column between them counts for nothing. The network beyond the port has gravity
(`PassiveStepSolver.GRAVITY`, the nodes' elevations); the fence has none.

- Expected: the liquid at the valve has the nozzle's pressure less its own column up to the valve,
  `P = P_design - density x g x (z_valve - z_source)`. A bottoms liquid of 700 kg/m3 loses 6.9 kPa a metre.
- What the code does: a bottoms valve placed 29 m up (the review column's height) still gives the design 2.0 bar: 2.0 bar
  of head from nothing, the bottoms' whole absolute pressure. A draw valve placed at grade, 20 m under its nozzle, gives
  1.4 bar less than a real line would.
- So a product can be delivered higher than its design pressure lifts it, with no pump.

## 3. Proposed laws

One new LIMIT, `HYDRAULICS` (status word `hydraulics`, the index a loop: 0 overhead, 1 reflux, 2 + j pumparound j; the
text names what binds: a line or a pump). Each loop gives the share of the set feed it allows; the lowest binds, as
every limit does. At one table every internal flow is proportional to the feed taken, as the flood fraction is.

1. **Overhead.** Share = the least of the speed cap's share and the share whose drop over the built line (its pipes'
   bores and lengths, parallel branches sharing the flow) meets the budget. Owner option O1: the budget.
   - A (recommended): 10 % of the top's absolute pressure, twice the simulator's `VACUUM_DROP`, a round rule at a
     simulator's depth.
   - B: the speed cap alone (lenient: 0.10 m passes 70 %).
   - C: the design's own condenser drop (`condenser_drop`), 0 on most designs, so most lines would bind.
2. **Reflux.** Share = the least of pump rated flow over condensate flow, and the share whose rise (lift, the lines'
   drop, the valve) meets the pump's head. The world's pump law, no curve.
3. **Pumparound.** Circulation = the pumps' rated flow (parallel pumps add), less where the lines' drop passes the head.
   The fall follows from the duty. Share = the least of 1 and (the draw temperature less the return floor) over the
   fall at the set feed. Owner option O2: the return floor.
   - A (recommended): the cooler's utility inlet temperature plus the approach the cooler side already uses
     (`Read.column`'s ceiling rule, mirrored at the cold end).
   - B: no floor: a pumparound pump never binds, only its head against the lines.
4. **Outlet ports.** Owner option O4.
   - A (recommended): each slice the port's pressure is the design pressure less the product's own column,
     `density x g x lift`, the lift the port's cell above its source (the column's nozzle for a draw and the bottoms; the
     receiver's nozzles for the off-gas, the sour water and the distillate, whose reflux pump adds nothing: the design value
     is the top's pressure). The density is the product's at the port. A valve under its source gains the column, as a
     real line does. Start refuses (`Refusal.OUTLET_TOO_HIGH`, naming the outlet and both heights) where the lift passes
     the head the design pressure gives at the design's liquid, `P_design / (density x g)`: the port's pressure would not
     be positive. Inlets keep their rule.
   - B: a check alone: Start refuses a liquid outlet whose valve stands above its source; no pressure change. Simpler,
     but a valve under its nozzle still misses its real head, and a riser of one block is refused.
5. Not modelled: suction boiling and NPSH (the receiver and the draw trays stand above their pumps), two-phase flow in
   the condenser's outlet, the condenser's shell-side drop (O1's budget covers the line alone).

## 4. Work packages

1. **Numbers.** A plain-JVM probe in `tools/column-hydraulics/` on the review plant's sheet (`ColumnOperationReviewWorld.sizing`
   as its model): each internal stream's mass flow, density, viscosity and speed limit per mole of feed; this plan's
   tables with solved values. Done: the tables replaced, the estimate note removed.
2. **The table's streams.** `SplitTable` gains `internal` (`Internal`: overhead, condensate, reflux, and per pumparound
   the draw liquid's density, viscosity, heat capacity and draw temperature; each as kg per mole of feed with its
   density, viscosity and speed limit), built in `ColumnSolve.run` from the V3 result, `Internal.NONE` at a table
   without it. State: fluid checkpoint state format 54 (53 is reserved); an old state is dropped and rebuilt.
3. **The plant's lines.** `ColumnStarts` walks each loop with `AssemblyWalker` (`PortGraph.run` from the column's
   vapour outlet, the pumps' and coolers' nozzles) and hands `startColumn` a `ColumnUnit.Lines`: per loop, its pipes'
   device ids with the number of parallel ways at each. Bores and lengths are read from the registry at each cycle, so
   a resize acts at once. Facts: fluid facts schema 31 (30 is reserved); `Lines.NONE` is the behaviour before, so a
   column started before this runs unlimited until its next Start; a corpus image under `src/test/resources/compat/`.
4. **The cycle.** `ColumnCycle.Rooms` gains `hydraulics` (a share per loop, with its text); `ColumnCycle.step` adds
   `HYDRAULICS` beside `FLOODING`. `ColumnOperation.cycle` computes the shares from `Internal`, `Lines`, the members'
   movers and the registry (pure arithmetic in a new `science/plant/column/LoopHydraulics`). `Condition.HYDRAULICS`;
   `ColumnStatus` words; `en_us.json`.
5. **Outlet head** (O4 A). `ColumnUnit.Station` gains `lift`, m (facts schema 31 with package 3; 0 is the behaviour
   before), set by `ColumnStarts` from the port's cell and its source nozzle (the walk of package 3 gives the source);
   `PortPlan` gains `lift`; `PassiveStepSolver.ports` states the generator at the port's pressure less
   `density x GRAVITY x lift`, the density the stated product's at the port's pressure (one evaluation, no iteration).
   `Refusal.OUTLET_TOO_HIGH` in `ColumnStarts.build`. Tests: `RegulatingValveHoldTest` (a lift of 0 is as before,
   bit for bit; a lift of 10 m lowers the generator by the hand number), `ColumnStartsTest` or the Start GameTest (the
   refusal). `PassiveStepSolver` is the fluid step lane's hot file: one method, stated here.
6. **The review world** is rebuilt with the fixed builder; its report shows no `HYDRAULICS`. A second plant with a
   0.05 m overhead reads `LIMITED: hydraulics`.

Tests: `ColumnCycleTest` (the share binds, is the lowest, the text), a new `LoopHydraulicsTest` (each law against hand
numbers of section 2), `ColumnRunTest` (a started column with a narrow overhead is limited; `Lines.NONE` is as before,
bit for bit), the facts round trip and corpus gate. Classes for each run come from `tools/merge-lane/affected.mjs select`.

## 5. Risks and what moves

- No reference moves for a plant with `Lines.NONE` or lines at their sized bores (shares of 1 add no condition).
- `FluidFacts`, `FluidWorldAuthority` and `ColumnOperation` are hot files; batch `2026-11-16-solute-reaction-system`
  holds facts schema 30 and plans to add a column unit's parts. The two batches must not overlap in those files:
  this batch starts its packages 3 and 4 after that batch's column package lands, or takes them first by the owner's word.
- A design with a tight overhead (vacuum columns) may bind at once under O1 A; the vacuum drop rule (5 %) may fit them
  better. Checked in package 1 on the vacuum preset.

## 6. Owner decisions

- O1: the overhead's drop budget (A, B or C).
- O2: the pumparound's return floor (A or B).
- O4: the outlet ports' head (A or B).
- O3: the order against the solute batch in the hot files, and the reservation of state format 54 and facts schema 31
  on `main` (a documentation-only commit).
