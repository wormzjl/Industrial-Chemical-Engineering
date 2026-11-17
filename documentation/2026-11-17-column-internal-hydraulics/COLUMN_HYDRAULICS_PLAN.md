# Column internal hydraulics: plan (2026-11-17)

Class: PHYSICS (owner's option, with numbers). Lanes: plant (`science/plant/column`, `ColumnOperation`), flowsheet and
control (`runtime/assembly`), runtime (`FluidFacts`, `FluidWorldAuthority`), fluid step (one method of
`PassiveStepSolver`), client (status words). Nothing is coded but the probe of work package 1.

## 1. The gap

A running column holds every device inside its boundary valves out of the fluid network. `ColumnCycle.step` weighs the
feed, the steam, the products' room, each utility's room, flooding and the members' power. No term reads an internal
line's bore, a member pump's size, or where a boundary valve stands. The process simulator rates lines and pumps
(`LineSizing.rate`, `PumpSizing.rate`), but a rating is advice and does not reach the run. The owner (2026-11-17): a
plant whose internal lines and pumps are too small must not run as if they were right, an outlet must not give more
head than its design, the same holds for the exchanger structure, and the fence must leave nothing else to exploit.

## 2. Physics, in numbers, on the review plant

The review plant is the default template at 1/16: feed 44.4 mol/s (159.8 kmol/h), top 2.5 bar, condenser 1.94 MW. The
numbers are solved (`tools/column-hydraulics/ReviewPlantProbe.java`: the plant's sheet run and sized, steady flows, the
world's `PipeResistance` law for every drop).

The world's own laws, which the network applies to every pipe and pump outside the fence:
- a pipe passes at most `density x area x velocityLimit` (100 m/s or the acoustic bound) and loses
  `PipeResistance.pressureDrop`; a column of fluid weighs `density x g x height`;
- a pump moves at most its rated flow and adds at most its head. A casing's rated flow is liquid at 3 m/s in its
  discharge (`UnitRatings.pumpFlow`: 21.2 m3/h at 0.05 m, 84.8 at 0.10 m), its head 100 m of water (205 m of the
  review plant's condensate).

### 2.1 Overhead vapour line (column top, condenser, receiver)

3.32 kg/s of vapour, 0.648 m3/s at 2.5 bar, 5.12 kg/m3. The flow is fixed by the split table, so a line that passes
less cuts the feed. The drop's share is the square root of the budget (0.25 bar, option O1 A) over the drop.

| Bore | Speed | Drop over 20 m | Share the 100 m/s cap allows | Share the 10 % budget allows |
|---|---|---|---|---|
| 0.20 m (sized) | 20.6 m/s | 0.016 bar | 100 % | 100 % |
| 0.15 m | 36.7 m/s | 0.070 bar | 100 % | 100 % |
| 0.10 m | 82.6 m/s | 0.57 bar | 100 % | 66 % |
| 0.075 m | 147 m/s | 2.6 bar | 68 % | 31 % |
| 0.05 m (as built) | 330 m/s | 21.4 bar | 30 % | 11 % |

The speed cap alone is lenient: at the cap a 0.05 m line would lose eight times the column's whole pressure.

### 2.2 Reflux loop (receiver, pump, tee, column top)

The pump moves the whole condensate, 15.1 m3/h (reflux 12.2, distillate 2.9): its flow is fixed by the table. The
0.05 m casing (21.2 m3/h) runs at 71 %. A casing whose rated flow is under the condensate cuts the feed in proportion.
Head: the loop needs 41 m (the lift to the top tray, the sized lines, the valve). A 0.05 m receiver line loses 0.19 bar
over 30 m (3 m). Even a 0.025 m line (6.8 bar, 104 m) stays under the casing's 205 m. Liquid lines bind late.

### 2.3 Pumparound loops

The column model has no circulation flow: it has the duty. The simulator derives a circulation from an assumed 60 K
fall across the cooler (`DesignBasis.paFall`, `BlockSizing.circulation`), and `PUMP_RATED` reads against that. In a
real plant a smaller pump circulates less and the liquid returns colder; the duty stays until the return temperature
meets the cooler's cold end.

| Pumparound | Duty | Needed at a 60 K fall | Pump | Fall at the pump's rated flow |
|---|---|---|---|---|
| 1 (draw tray 10) | 818 kW | 32.7 m3/h | 0.05 m, 21.2 m3/h | 93 K |
| 2 (draw tray 18) | 1313 kW | 46.5 m3/h | 0.10 m, 84.8 m3/h | 33 K |
| 3 (draw tray 28) | 827 kW | 27.7 m3/h | 0.05 m, 21.2 m3/h | 78 K |

The side draws leave at 150, 227 and 276 C. With cooling water at 30 C a 93 K fall is feasible, so the small
pumparound pumps of the review plant would not cut the duty. They would where the fall the pump forces exceeds the draw
temperature less the return floor.

The lines: pumparound 1's 32.7 m3/h in a 0.05 m line runs at 4.6 m/s and loses 0.88 bar over 30 m (13 m of head);
the pump has it. The sized 0.15 m line loses 0.003 bar. Liquid speed never nears the cap (707 m3/h at 0.05 m).

### 2.4 What follows

- The first proposal (lines by speed alone, pumps by rated capacity with 20 % spare) was wrong on both counts: speed
  never binds a liquid line, and a pumparound pump's size sets the fall, not the duty.
- In the review plant as built, a real plant would be limited by the overhead line (11 % of feed) and by nothing else.

### 2.5 Boundary ports: head, elevation and length

A port is a void (inlet) or a generator (outlet) in the cell on its boundary valve's column side, stated at the design's
stage pressure (`PortConditions.estimate`, fixed at Start) wherever that valve stands. The pipes between the nozzle and
the valve are held: their height and their length count for nothing. The network beyond the port has gravity and
friction. `startColumn` holds up to `COLUMN_LIMIT` (4096) devices.

- Expected: the fluid at the valve has the nozzle's pressure less its own column up to the valve and less the line's
  drop: `P = P_design - density x g x (z_valve - z_source) - drop`.
- What the code does, bottoms (822 kg/m3, 2.66 bar, 8.1 kPa a metre): a valve 29 m up (the column's height) still gives
  2.66 bar, a free 2.3 bar; the design pressure lifts this liquid 33 m at most. A draw (680 kg/m3) whose valve stands
  at grade, 20 m under its nozzle, gives 1.3 bar less than a real line would.
- Length: a bottoms line of 0.05 m loses 2.5 bar per 100 m at 26.6 m3/h. Held, it loses nothing over any length, and
  loses no heat: a boundary valve a thousand blocks away is a free pipeline.
- Inlets: a liquid feed whose valve stands under its nozzle is lifted for nothing; the void takes it at the stage's
  pressure.

### 2.6 The exchanger structure

Checked in the code: the exchanger's structure holds nothing. Its tees, valves, flowmeters and both sides stay in the
network. A side is a connection with its passage's friction (`HeatSides`, heat-exchangers.md: tubes of 14.8 mm in two
passes, the shell's crossflow), and its outlet's junction stands at the outlet nozzle's height
(`PhysicalFluidTopology`, the side's `OUTLET` port's cell). A control valve is a throttle: it regulates forward only and
adds no work (fluid-laws.md). So a standalone exchanger gives no free head and passes no more than its lines let it.

What does apply: the exchangers a running column holds (its condenser, each pumparound cooler's process side, a kettle's
shell). Held, their passage drop and their nozzles' heights count for nothing, as the lines'. Section 3 puts each held
side's drop and height into its loop.

### 2.7 What else the fence lets a player take

| # | What | State | Answer |
|---|---|---|---|
| E1 | A boundary line of any length or height carries with no drop, no lift and no heat loss (2.5) | open | law 4 |
| E2 | A liquid feed lifted to its nozzle for nothing (2.5) | open | law 4 |
| E3 | Internal lines and member pumps of any size (2.1 to 2.3) | open | laws 1 to 3 |
| E4 | Held exchangers' drop and height (2.6) | open | laws 1 to 3 |
| E5 | A small pump books little power: a pump books its rated shaft power times the cycle's share, whatever it would have to move | open | closed by laws 2 and 3: a pump moves at most its rated flow |
| E6 | A receiver of any size: the product buffers are 24 cycles of make (`BUFFER_CYCLES`), not the drum's volume, and the check takes any lying drum with a boot | open | batch `2026-11-17-three-phase-separator` |
| E7 | An exchanger side with no lines regulates by its internal bypass: no tee, valve or flowmeter, no drop | by design (heat-units.md) | kept (O7 A) |
| E8 | A held device's wall: `Integrity.idle` says a held device is not idle, so it wears | closed | none |
| E9 | The condenser's, coolers' and reboiler's surface: each duty passes through its utility side's room | closed | none (`COOLING_SHORT`, `PUMPAROUND_SHORT`, `HEATING_SHORT`) |
| E10 | The column's diameter and trays: flooding by the design's own trays | closed | none (`FLOODING`) |
| E11 | A held member's pressure rating against the design pressure | not checked here | work package 1b reads `Integrity` for held vessels |
| E12 | Two columns on one valve: an outlet's generator against an inlet's void | closed with law 4 | the valve is a throttle; the lower plan binds |

## 3. Laws

One new LIMIT, `HYDRAULICS` (status word `hydraulics`, the index a loop: 0 overhead, 1 reflux, 2 + j pumparound j; the
text names what binds: a line, a pump or a cooler). Each loop gives the share of the set feed it allows; the lowest
binds, as every limit does. At one table every internal flow is proportional to the feed taken, as the flood fraction is.
A loop's drop is its held pipes' (their bores and lengths, parallel branches sharing the flow) and its held exchanger
sides' passages, by the world's own laws.

1. **Overhead** (decided, O1 A). Share = the least of the speed cap's share and the share whose drop over the line and
   the condenser's process side meets 10 % of the top's absolute pressure.
2. **Reflux.** Share = the least of pump rated flow over condensate flow, and the share whose rise (the lift from the
   receiver to the top tray, the loop's drop, the valve) meets the pump's head. The world's pump law, no curve.
3. **Pumparound** (decided, O2 A). Circulation = the pumps' rated flow (parallel pumps add), less where the loop's drop
   and lift pass the head. The fall follows from the duty. Share = the least of 1 and (the draw temperature less the
   return floor) over the fall at the set feed; the floor is the cooler's utility inlet temperature plus the approach
   the cooler side already uses.
4. **Boundary ports** (decided, O4 A and O5 A). Each slice an outlet's pressure is the design pressure less the
   product's own column up to the port and less the held line's drop at the port's flow. The lift is from its source:
   the column's nozzle for a draw and the bottoms; the receiver's nozzles for the off-gas, the sour water and the
   distillate, whose reflux pump adds nothing. A port under its source gains the column. An inlet's void stands at
   the stage's pressure plus the feed's column up to its nozzle and the held line's drop: the feed must arrive with
   that. Start refuses (`Refusal.OUTLET_TOO_HIGH`, naming the outlet and both heights) where the lift passes
   `P_design / (density x g)`.
5. Not modelled: suction boiling and NPSH, two-phase flow in the condenser's outlet, heat lost by held pipes.

## 4. Work packages

1. **Numbers** (done 2026-11-17). `tools/column-hydraulics/ReviewPlantProbe.java`; section 2's tables.
   1b. `Integrity` for held vessels and pipes: whether a held member's rating is read against the design pressure (E11).
2. **The table's streams.** `SplitTable` gains `internal` (`Internal`: overhead, condensate, reflux, and per pumparound
   the draw liquid with its heat capacity and temperature; each as kg per mole of feed with its density, viscosity and
   speed limit), built in `ColumnSolve.run` from the V3 result, `Internal.NONE` at a table without it. State: fluid
   checkpoint state format 54 (53 is reserved); an old state is dropped and rebuilt.
3. **The plant's lines.** `ColumnStarts` walks each loop and each boundary line with `AssemblyWalker` (`PortGraph.run`
   from the column's nozzles, the pumps' and the exchangers' nozzles) and hands `startColumn` a `ColumnUnit.Lines`: per
   loop and per port, its pipes' and held sides' device ids with the number of parallel ways at each, and each loop's
   lift. Bores, lengths and passages are read from the registry at each cycle, so a resize acts at once. Facts: fluid
   facts schema 31 (30 is reserved); `Lines.NONE` is the behaviour before, so a column started before this runs
   unlimited until its next Start; a corpus image under `src/test/resources/compat/`.
4. **The cycle.** `ColumnCycle.Rooms` gains `hydraulics` (a share per loop, with its text); `ColumnCycle.step` adds
   `HYDRAULICS` beside `FLOODING`. `ColumnOperation.cycle` computes the shares from `Internal`, `Lines`, the members'
   movers and the registry (pure arithmetic in a new `science/plant/column/LoopHydraulics`). `Condition.HYDRAULICS`;
   `ColumnStatus` words; `en_us.json`.
5. **Port head.** `ColumnUnit.Station` gains `lift`, m (schema 31 with package 3; 0 is the behaviour before), set by
   `ColumnStarts` from the port's cell and its source nozzle; `PortPlan` gains `lift` and `drop`;
   `PassiveStepSolver.ports` states the generator at the port's pressure less `density x GRAVITY x lift` and the drop (an inlet's void at the
   stage's pressure plus both), the density the stated product's at the port's pressure (one evaluation, no iteration).
   `Refusal.OUTLET_TOO_HIGH` in `ColumnStarts.build`. `PassiveStepSolver` is the fluid step lane's hot file: one method.
6. **The review world** is rebuilt with the fixed builder; its report shows no `HYDRAULICS`. A second plant with a
   0.05 m overhead reads `LIMITED: hydraulics, 11 %`.

Tests: `ColumnCycleTest` (the share binds, is the lowest, the text), a new `LoopHydraulicsTest` (each law against the
numbers of section 2), `ColumnRunTest` (a started column with a narrow overhead is limited; `Lines.NONE` is as before,
bit for bit), `RegulatingValveHoldTest` (a lift of 0 is as before, bit for bit; a lift of 10 m lowers the generator by
the hand number), the Start GameTest (the refusal), the facts round trip and corpus gate. Classes for each run come from
`tools/merge-lane/affected.mjs select`.

## 5. Risks and what moves

- No reference moves for a plant with `Lines.NONE`, or with lines at their sized bores and ports at their nozzles'
  height (shares of 1 add no condition; a lift of 0 changes no pressure).
- `FluidFacts`, `FluidWorldAuthority`, `ColumnOperation` and `PassiveStepSolver` are hot files; batch
  `2026-11-16-solute-reaction-system` holds facts schema 30 and plans to add a column unit's parts. Decided (O3): this
  batch builds after that batch's column package lands, and reserves state format 54 and facts schema 31 then.
- A vacuum design's overhead may bind at once under the 10 % budget; the vacuum drop rule (5 %) may fit it better.
  Checked in package 2 on the vacuum preset.

## 6. Owner decisions

All taken 2026-11-17:
- O1 A: the overhead's budget is 10 % of the top's absolute pressure.
- O2 A: the pumparound's return floor is the utility's inlet temperature plus the approach.
- O3: this batch builds after the solute batch's column package (WP7); state format 54 and facts schema 31 are
  reserved then.
- O4 A and O5 A: law 4, for outlets and inlets, with the held line's height and drop.
- O6: answered with a three-phase separator model, batch `2026-11-17-three-phase-separator`
  ([SEPARATOR_PLAN.md](../2026-11-17-three-phase-separator/SEPARATOR_PLAN.md)); its law for a column's held receiver
  joins this batch's package 4.
- O7 A: an exchanger side's internal bypass stays.
