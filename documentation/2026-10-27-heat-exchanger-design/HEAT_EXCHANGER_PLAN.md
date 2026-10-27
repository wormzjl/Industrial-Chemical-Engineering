# Heat exchanger system: plan

Batch `2026-10-27-heat-exchanger-design`. Base `995dbd9c` (main, 0.15.0). Status: Planned 2026-10-27. Revision 2 (the same day) takes in the owner's answers to Q1 to Q9 (section 11). Nothing is implemented.

Evidence:

- [HX_COUPLING_STUDY_REVIEW.md](HX_COUPLING_STUDY_REVIEW.md): the first study (cell count, coupling schemes, holds, controller).
- [HX_FILMS_STUDY_REVIEW.md](HX_FILMS_STUDY_REVIEW.md): the per-phase film study of revision 2 (lag, coupling forms, stability map).
- Harnesses: `tools/heat-exchanger-design/`.

Open owner questions Q10 to Q13: section 12.

Owner request 2026-10-27:

1. Every piece of equipment has a UA that scales with its size. Take U constant at a reasonable value.
2. Built-in control, so that exchangers do not add convergence trouble to the fluid solver: a fixed input ratio through an internal control valve, a target hot or cold side temperature, or what else?
3. Input and output buffers that decouple the fluid networks on both sides, with the pressure relayed through a calculated pressure drop? A lag in the response is acceptable.

Short answers (with the owner's answers):

1. **UA from size.** UA = U × the tube surface the datasheet already shows (75 D² L m²). 1/U = 1/h_hot + 1/h_cold, with one film coefficient per phase: liquid 2000, boiling or condensing 5000, gas 100 W/m²K. The films are approximated so that they add no lag: a property of the side's enthalpy curve, not of the slice start. Section 4.
2. **Control.** A controller that acts between slices, never inside the Newton: an internal bypass to an outlet temperature, a duty, or a manual share. A flow ratio, and the bypass as real lines, come with the exchanger assembly (section 7). Section 6.
3. **Buffers.** Decouple the heat, not the mass. Each side stays a pass-through in its own island with exact hydraulics. The two sides exchange only energy, through the exchanger's wall, about one slice late. Section 5.

## 1. Expected behaviour (the plant first)

A shell-and-tube exchanger passes two streams that never mix. Each side is a flow passage: the network sets its flow, and the passage takes a pressure drop as a pipe does (30 to 70 kPa for a liquid at design velocity). Heat flows through the tube walls from the hot side to the cold side: q = U (T_hot − T_cold) per m².

- **U** (overall, W/m²K, typical design ranges; Sinnott, *Chemical Engineering Design*, table 12.1):

  | Service | U |
  |---|---|
  | Water/water | 800-1500 |
  | Light oil/water | 350-900 |
  | Steam on water | 1500-4000 |
  | Steam on organics | 500-1000 |
  | Organic vapour condensing on water | 700-1000 |
  | Gas/water | 20-300 |
  | Gas/gas | 10-50 |

  Each side's film sets most of it: liquids 1000 to 5000, condensing or boiling 2000 to 10 000, gases 20 to 300 W/m²K.
- **Steady state**: Q = ε C_min (T_hot,in − T_cold,in), with NTU = UA / C_min and C = ṁ c_p. Counterflow:
  - balanced flows (Cr = 1): ε = 0.50, 0.67 and 0.83 at NTU 1, 2 and 5;
  - a condensing or boiling side (Cr = 0): ε = 0.63, 0.86 and 0.99.
- **The drawn exchanger is a 1-2 shell** (TEMA AES, one shell pass, two tube passes). At Cr = 1 it reaches 0.46, 0.56 and 0.59 at NTU 1, 2 and 5. The owner chose counterflow for every exchanger for now (Q3). Counterflow overstates the drawn shell's duty by 8 %, 20 % and 42 % there.
- **Phase change**: a condensing or boiling stream holds its temperature while it takes latent heat, and its film is two to fifty times a gas's. A constant c_p fails there; the stream's enthalpy-temperature curve handles both the plateau and the film.
- **Dynamics**: the steel stores heat (12 kJ/K per m²). An outlet settles within 1 K of a 30 K inlet step in:

  | Pair | Settle to 1 K |
  |---|---|
  | Liquid/liquid | 36 s |
  | Gas/liquid | 66 s (gas side), 138 s (liquid side) |
  | Gas/gas | 6 min |
  | Condensing/liquid | 7 s |

  These are the study's numbers for the 450 m² exchanger. Weak gas films charge the steel slowly. Real gas exchangers take minutes too.
- **Control in real plants**:
  - A utility exchanger throttles its utility (cooling water, steam) on the process outlet temperature.
  - A process/process exchanger moves a bypass, a three-way valve or two valves, on one side.
  - An air cooler switches its fans, changes their speed or pitch, or closes louvres.
  - Ratio control of the two flows is rare on an exchanger: it fixes Cr, not a temperature. Where it exists it is a ratio station of two flowmeters and a flow valve.

**Example used throughout**: shell 1 m, tubes 6 m: 75 × 1² × 6 = 450 m².

- **Water on both sides**: U 1000 (liquid/liquid), UA 450 kW/K. Water 50 kg/s each side (C = 209 kW/K, NTU 2.15), hot in 150 °C, cold in 30 °C.
- **Counterflow**: ε 0.68, Q 17.1 MW, hot out 68 °C, cold out 112 °C (liquid under pressure). A hot inlet step to 180 °C gives 78 and 132 °C (the 8-cell model: 78.35 and 131.65 °C).
- **As the drawn 1-2 shell** (for reference): ε 0.56, Q 14.1 MW.

## 2. What the code does today

- **Drawn only.** The exchanger, kettle, air cooler and both fired heaters are drawn; nothing registers them in the fluid network. Their datasheets already state:
  - the tube surface (`ProcessUnit.tubeSurface` = 75 D² L);
  - the air cooler bay's face (`bundleFace`);
  - the fired heaters' duty (`heaterDuty`).
- **No heat transfer.** A pipe owns no fluid. The one energy a connection adds is a mover's shaft work, in three places:
  - `PassiveStepSolver.Equations.nodeAccumulate` adds it to the receiving node's energy row;
  - `ConservativeTransport` books it (`pumpWork`);
  - `checkConservation` audits it.

  Exchanger heat goes the same three ways.
- **The heat law exists as a study.** The pipe thermal-loss study (batch `2026-09-24-pipe-thermal-loss`, concluded, not merged) built it: a stream relaxing towards a wall temperature along its enthalpy-temperature curve (`PipeHeatExchange`, patch in `tools/pipe-thermal-loss/`).
  - Exactly zero heat at zero flow; latent heat exact.
  - A fixed 17-point curve stayed within 7.1 % of a 513-knot reference over 561 cases, condensation included. An adaptive 9 to 30-point curve stayed within 0.05 %.
  - Re-solving the pressure drop with the cooled properties was not reliable, so the hydraulics keep the inlet stream's properties.
- **Buffers were tried and removed.** Equipment as island separators with built-in buffers was the 2026-09-15 engine design. It was built as the causal module system (buffered transfers, pending deliveries, time fences) and removed on 2026-10-15 (`944a79f6e`, consolidation item F1), because nothing in the game produced a module.
- **Islands advance in slices** of their own adaptive cadence (20 to 400 ticks). They commit on the server thread, and certify STEADY when their intervals repeat.
- **Valves and assemblies exist.** Process control (0.14.1) gives control valves with a pressure or a flow module, regulated ideally in the Newton, and flowmeters on pipes. The multi-machine framework (0.15.0) gives assemblies: a master's blueprint of slots and links, Validate structure, reports as state, and reverts without polling (code guide `machine-assemblies.md`).

## 3. Owner decisions (2026-10-27)

| Question | Answer | Where |
|---|---|---|
| Q1 coupling | B, a wall account between islands | section 5 |
| Q2 U | A film per phase, approximated so that it adds no lag | section 4 |
| Q3 arrangement | Counterflow only, for now | sections 1, 4 |
| Q4 wall | Steel only, 12 kJ/K per m² (revisited by Q10) | section 4 |
| Q5 utilities | Real fluid on both sides; no implicit utilities | sections 5, 8 |
| Q6 control | Between slices, dead-beat, with the modes of section 6 and the low limit | section 6 |
| Q7 ratio control | A multi-structure with real lines (the bypass line first) and valves, as its own work package | section 7 |
| Q8 order | As recommended, adapted to Q5 (Q12) | section 10 |
| Q9 old builds | An exchanger built before this batch registers when its chunk loads | section 9 |

## 4. UA from size, a film per phase

**Surface** (unchanged):

| Kind | Surface |
|---|---|
| EXCHANGER | A = 75 D² L; shell 0.5 to 2.5 m, tubes 2 to 12 m: 37.5 to 5625 m² |
| KETTLE | A = 75 D_b² L; bundle 0.5 to 2 m, tubes 2 to 10 m: 37.5 to 3000 m² |
| AIR_COOLER | Per bay, 5 m² of bare tube per m² of face (four rows of 1" finned tubes). Face 2.0 m² (a 1.5 × 3 m bay) to 12.2 m² (3 × 6 m) |

**Films** (each includes its share of the wall and fouling): liquid 2000, boiling or condensing 5000, gas 100 W/m²K. Then 1/U = 1/h_hot + 1/h_cold:

| Pair | U (W/m²K) | Design range | UA, smallest to largest exchanger |
|---|---|---|---|
| liquid/liquid | 1000 | 800-1500 (water), 350-900 (oil/water) | 37.5 kW/K to 5.6 MW/K |
| condensing/liquid, liquid/boiling | 1430 | 1500-4000 (steam/water), 700-1000 (organic condensers) | 54 kW/K to 8.0 MW/K |
| condensing/boiling (kettle) | 2500 | 1000-1500 (steam/aqueous), 900-1200 (steam/organics) | kettle: 94 kW/K to 7.5 MW/K |
| gas/liquid, gas/condensing | 95 to 98 | 20-300 | 3.6 kW/K to 550 kW/K |
| gas/gas | 50 | 10-50 | 1.9 kW/K to 281 kW/K |

The pairs land inside or near the design ranges. Two are high: organic condensers at 1430 and kettles at 2500, about 1.5 to 2 times typical. Water films at 2000 sit low (real 5000 and more). One value per phase is the approximation the owner asked for. Fluid by fluid films are a later option, not part of this batch.

**The approximations that keep the films free of lag:**

1. **Phase only.** No velocity, Reynolds number, property or geometry correlation: one value per phase.
2. **On the curve, not at the slice start.** Each point of the side's enthalpy-temperature curve also carries its film, from the vapour fraction there, linear between points.
   - The curve is the one the heat law already builds (17 to 30 points, at the side's composition and pressure).
   - The blend: liquid to boiling or condensing over a vapour fraction of 0 to 0.05, and to gas over 0.95 to 1.
   - The kernel integrates dh/dA = −h_film(h) (T(h) − T_wall) / ṁ exactly per segment, as it integrates T(h) today.
   - A stream that starts to condense halfway through the exchanger gets the condensing film there, in the same Newton.
   - The film is continuous along the curve: no new unknown, no switch, no mode.
3. **The other side's film** reaches a side only through the coupling record (section 5), never through its Newton.
4. **The wall** stores the dynamics. Its time constant C/(h_hot + h_cold):

   | Pair | C/(h_hot + h_cold) |
   |---|---|
   | condensing/liquid | 1.7 s |
   | liquid/liquid | 3 s |
   | gas/liquid | 5.7 s |
   | gas/gas | 60 s |

   With weak films the streams charge the steel slowly: gas/gas settles in 6 minutes, gas/liquid in 2.3 minutes. This lag is physics, and the wall's weight is its only lever: a quarter of the steel (3 kJ/K per m²) gives 1.5 and 0.6 minutes, with no steady state moving (review, part 5c). Owner question Q10.

**Air cooler.**

- Tube side: the phase film on 5 m² of bare tube per m² of face.
- Air side: the gas film, 100 W/m²K, on 50 m² of finned surface per m² of face (fin ratio 10): 5 kW/K per m² of face.
- Overall, per m² of face: 3.3 kW/K (liquid), 4.2 (condensing), 0.45 (gas).
- The fans move air at 3 m/s, 3.5 kW/K per m² of face. Every tube cell meets fresh air, so the process stream sees ambient air through UA_eff = C_air (1 − e^(−UA/C_air)): 2.15 (liquid), 2.44 (condensing) or 0.42 (gas) kW/K per m² of face.
- The largest bay, process at 85 °C in 25 °C air: 1.6 MW liquid, 1.8 MW condensing, 0.3 MW gas.
- Fans off: natural draught, the air's capacity and film to a quarter.
- The air is the unit's own (no nozzles), not a utility (Q11).

**Fired heaters**: the datasheet duty (`heaterDuty`) caps the firing. The fuel is the unit's own (Q11).

**What the films change against a constant U of 500:**

- Gas coolers are no longer 2 to 25 times too strong.
- Water/water is about right.
- Condensers and kettles move 1.4 to 2.5 times more heat for a given size.

## 5. Coupling the two sides (owner Q1: B)

Physics: the hot fluid heats the tube wall, and the wall heats the cold fluid. The wall stores heat, so each side feels the other through it, seconds to minutes late. The design keeps exactly that: the islands exchange only energy, through the wall, never mass or pressure.

### 5.1 Each side is a connection in its own island

Each side runs between its inlet and outlet nozzles (`PortMarkings` already marks them IN and OUT).

- **Hydraulics**: a passage law like a pipe's, for parallel channels, on the inlet stream's properties. Pressure is not relayed: the island's own solve passes it through the side, as through a pipe.
  - Tube side: 630 D² tubes per pass of 14.8 mm bore, length 2 L, plus 4 velocity heads per pass for entry, return and exit. Water at 1.5 m/s in the example: about 35 kPa.
  - Shell side: an equivalent passage of 18 mm hydraulic diameter and 0.1 D² crossflow area, one shell diameter per baffle crossing, with baffles 0.4 D apart (length 2.5 L).
- **Heat**: the exchanger has 8 wall cells in counterflow (Q3). The hot side passes cells 1 to 8, the cold side 8 to 1.
  - Each stream relaxes towards each cell's temperature T_k over the cell's eighth of the surface, on its curve with its film (section 4).
  - N = 8 is within 0.9 % of exact counterflow up to NTU 5 at Cr 0.5, and within 2.1 % at Cr 1.
- **Fixed for the slice**: the curve (built on the worker at the slice start from the donor's composition and pressure, at both ends for a reversal), the wall profile T_1 to T_8, and the bypass share β (section 6).
  - Inside the Newton the heat is a pure function of the connection's flow and the donor's enthalpy: no new unknown, no new mode, no switch inside a residual.
  - It is C1 in the flow, exactly 0 at no flow, and bounded by the approach to the wall.
- **Where it enters**: as a mover's shaft work does:
  - the receiving node's energy row;
  - the reconstruction (a heat term per side, beside `pumpWork`);
  - the audit.

  Every reader of a delivered enthalpy takes it too: the balanced start (`BalanceNet.seeded`, `mixed`), the arriving-phase seeds (`arrivingPhases`), the rate solve and the gauges' pipe observation.

Both sides always carry real fluid (Q5). A side with no pipe takes no heat.

### 5.2 The wall account between the two sides

The 8 wall cells are the exchanger's own state, a heat account. Each side books into it the heat it gave or took over each committed slice, so energy is exact by construction. A side's slice reads a wall profile fixed for the slice:

1. **As of its start tick.** The other side's heat booked past that tick is taken out.
   - If the other side lags, its heat not yet booked up to the tick is added at the rate of its open slice, which is fixed when that slice was dispatched, for at most one of its slices. After that, a lagging side counts as stopped.
   - A certified other side is first materialised to the tick (its replay books its certified heat).
2. **Towards the joint steady wall.** Each cell heads for T_ss,k: the exchanger's steady wall with both streams marched through the 8 cells, on their curves with their films. The own side uses its inlet state and flow now; the other side its inlet state, flow and curve from its last commit.
3. **The slice mean.** T̄_k = T_ss,k + (T_k − T_ss,k)(1 − e^(−x)) / x, with x = (G_own + G_other) Δt / C_cell. G is each side's conductance to the cell; x can be large, and the form stays exact.
4. **Clamped** between the two inlet temperatures. The account itself may stand outside them for a moment while heat is in transit; no side ever reads that.

**Why these four steps** (review of revision 2):

- **The as-of read.** Without it the outlets settle 1.5 to 52 K off the steady state.
- **The slice mean.** Without it the wall diverges at 20 s slices.
- **The open-slice rate** (instead of the lagging side's last booked rate). It removes an overshoot when the two islands run unequal slices: the long-slice side relaxes the wall as if alone, while the short-slice side keeps correcting it. A condensing/liquid exchanger at 20/5 s slices settles in 59 s instead of 254 s, and in 39 s with the joint steady wall as well (the physics alone: 7 s).
- **The joint steady wall** (instead of each cell's mean of the two streams' last temperatures). It removes the instability of strong films on two finite streams. The cell-local form fails for liquid/liquid at low flow and lighter walls, and for a condensing mixture with a glide against water even with the full steel wall. The joint form settles everywhere on the map (x up to 20.7).
- **Its cost**: transients 1.5 to 2 times larger at 20 s slices (liquid/liquid worst 16 K against 12 K), and a slow sub-kelvin tail at very high NTU (0.12 K after 1000 s at NTU 10.8).
- **Always**: the exact 8-cell steady state, and energy closing to 1.2e-5 J or better.

The same rule serves an exchanger whose sides share one island, such as a feed/effluent loop: the loop closes across slices, not inside the Newton.

**Wake.** A commit can move the profile the other side would read by more than 0.05 K from the one its certificate holds. Then that certificate is discarded (`inputsChanged`). A side with no flow takes no heat and needs no wake.

**Threads and determinism.**

- The command carries the as-of account and the other side's record: its inlet state, flow and curve; its conductances; its open slice's rate (arithmetic, server thread).
- The worker first builds the curve, the joint steady wall and the profile, then runs the controller and the Newton.
- The first output of a side's job is its open slice's rate. A partner's dispatch that needs it waits for it (microseconds after the job starts), so no result depends on thread timing.
- The commit books the heats (arithmetic, server thread). No process calculation runs on the tick.

**Initial wall**: a newly registered exchanger's cells stand at the ambient 25 °C. The first flow charges them, as it charges real steel.

### 5.3 The rejected options (owner Q1)

- **A, one island.** It merges about seven networks in a crude preheat train into one Newton, and a hold anywhere holds all of them.
- **C, the owner's material buffers with a pressure relay.**
  - Four buffers of about 1 m³ per exchanger, and 30 to 50 s of transport delay per side (3 to 5 minutes through six exchangers in series).
  - The network no longer sets the flow; a level-controlled transfer does.
  - The relay is circular: a buffer below capacity stands at the ambient pressure in this model, and a full one moves 1 bar per 46 mL of water in 1 m³.
  - The cross-island ledger removed on 2026-10-15 would come back.

## 6. Built-in control (owner Q6)

Where the convergence risk is, and what removes it:

1. Cross-side coupling inside a Newton: section 5 removes it.
2. Controller modes inside a Newton (regulating and saturated active sets, as the control valves have): a controller that acts between slices has none.
3. Phase changes the heat causes at the outlet: this is physics. A target outlet state keeps a stream away from a boundary the player did not ask for; nothing removes it in general (section 12).

| Mode | It holds | |
|---|---|---|
| Manual bypass share | a share β of one side passes outside the cells | built in |
| Outlet temperature by bypass | the hot or cold outlet at T_set | built in; the default |
| Duty | Q_set, at most what the exchanger can do | built in; the kettle's and the fired heater's natural mode; the hook for a column that takes over its exchangers (W9) |
| Fan, firing | the outlet temperature or the duty | the same mechanism, named after the unit (air cooler, fired heater) |
| Low limit (freeze guard) | the cooled outlet stays above T_min | built in as a limit, not a target: against ice, CO2 crystals and wax downstream |
| Flow ratio | cold flow = r × hot flow | not built in: the exchanger assembly's ratio station (section 7) |

**Mechanism.**

- At each slice start of the controlled side's island, on the worker, the controller finds β*: the bypass share whose mixed outlet meets the target, or whose heat meets the duty. It searches by bisection on the side's own heat law against the slice's profile, at the committed inlet state and flow; the response is monotone in β.
- The controller sets β = β* (dead-beat), and β stays fixed for the slice.
- β scales the flow that passes the cells, (1 − β) ṁ; the rest joins at the outlet. The hydraulics do not see β: the internal three-way valve keeps the side's resistance.
- When β* falls outside 0 to 1, the controller stops at the limit, and the status reads "limited".
- On a kettle's heating side, the bypass means steam that leaves uncondensed. A real steam valve is the assembly's (section 7).

**A target temperature becomes a target enthalpy** at the outlet pressure and the composition of the slice start, so a target on a boiling or condensing plateau stays well posed.

**Evidence** (first study): dead-beat held the cold outlet at 90 °C within 0.1 K 24 s after a 30 K hot-inlet step at 5 s slices (worst 0.3 K), and 79 s after it at 20 s slices (1.4 K). The controller sits on the same profile in revision 2.

## 7. The exchanger assembly: real lines and valves (owner Q7, work package W8)

Physics: in a plant an exchanger's control is piping. A bypass line runs around one side with a control valve (often with block valves either side of the exchanger: the three-valve manifold). A second valve in the exchanger line makes a split range, the two-valve form of a three-way valve. A ratio station is a flowmeter on each feed and a flow valve on the controlled one. The pressure drop across the exchanger is what drives the bypass. At low flow there is too little to bypass much, and the control saturates.

**The assembly** reuses the multi-machine framework, with the exchanger as the master:

- **Its design** is the control chosen on the exchanger's screen, per side: none, outlet temperature by bypass, duty by bypass, or ratio. It is a fact on the exchanger. The blueprint follows it, as `ColumnBlueprint` follows `ColumnComponents`.
- **Slots** (per controlled side):

  | Slot | What it is | Required |
  |---|---|---|
  | `inlet_tee` | a tee before the side's inlet nozzle | for the bypass modes |
  | `bypass_valve` | a control valve with a flow module on the bypass line | for the bypass modes |
  | `outlet_tee` | a tee after the outlet nozzle, where the bypass rejoins | for the bypass modes |
  | `pass_valve` | a control valve between the inlet tee and the nozzle (split range) | optional |
  | ratio station | a flow valve and a flowmeter on the controlled side's feed, and a flowmeter on the reference side's feed | for ratio |

  Block valves (shutoffs) are OTHER fittings and pass the line on, as today.
- **Validate structure**, the report as state (`icheme_assemblies`), revert without polling, the laying tool's marks, and the parts drawing all come as the column's do. New: a FLOWMETER element in the walk, since the walk reads fittings today but not the sensors installed on pipes.
- **Operation**, when the report is complete:
  - The exchanger's controller works the real valves instead of its internal bypass, whose β then stays 0 on that side.
  - The outer loop runs between slices, dead-beat as in section 6. For temperature and duty it turns the target into a bypass mass flow. For ratio, the controlled flow is r × the reference flowmeter's accepted reading: one slice late across islands, at the next slice inside one island.
  - The inner loop is the valve's existing flow regulation in the Newton (`ControlValve` flow module, its active set).
  - **The target is a slice input**, carried in the island's command like β, not a registry edit: a registry edit per slice would recompile the island.
- **Not complete** (a part missing, or reverted after an edit): the valves hold their last accepted targets, as the data-system contract holds a broken signal's last opening. The screen says so.
- **Convergence**: each controlled side adds two junctions and a regulating valve, elements the solver already handles. The temperature loop stays outside the Newton.
- **Saves**: the per-side control (mode by name, side, target, ratio) is a fact on the exchanger's registration (fluid facts schema 5). The report is state.

## 8. Player view

- **Build**: the constructor, as today. A complete exchanger, kettle or air cooler registers one fluid device per side, as a constructed mover does: an inlet and an outlet port at the side's nozzles (W2).
- **Real fluid on both sides** (Q5): a side heats or cools only what flows through it. There is no utility picker. Cooling water is water from a tank, pumped through the exchanger and back through an air cooler (the sink). Steam is water boiled in a fired heater (the source). A generator block serves as either in testing.
- **Kettle**: its shell is a vessel (a reservoir with FEED, VAPOUR_OUTLET and LIQUID_OUTLET ports, like a drum). The heat enters the vessel's energy row (W7). The heating medium in the tubes is a side.
- **Screen** (engine-owned: replay on open, updates at the island's bucket):
  - each side's inlet and outlet temperature, pressure, flow, phase and film;
  - duty, U, UA, NTU, effectiveness, approach;
  - the wall profile (as of the side that is behind);
  - the control (mode, side, target, β, limited).

  Notices: "other side held: counted as stopped", "control limited", "assembly not complete: valves hold". The drawing-only datasheet stays a page. With an assembly, a Structure tab as the column's.

## 9. Saves (AGENTS.md "Saves and data formats")

- **Facts** (append-only; fluid facts schema 4 → 5):
  - the exchanger's registration: kind by name, sides and ports;
  - the control per side: mode and side by name, target, ratio;
  - the wall account's cell energies, which are energy the world holds.

  A missing field reads its old behaviour: control off, cells at the ambient temperature. Corpus image under `src/test/resources/compat/fluid/schema-5/`.
- **State** (rebuilt freely): the per-side records for the as-of read (inlet state, flow, curve, conductances, open slice's rate), β, the curves, the assembly report.
- A removed exchanger takes its account's energy with it, booked as a removed vessel's contents are.
- **Old builds** (Q9): an exchanger built before this batch registers when its chunk loads, with its cells at the ambient temperature and control off. A side with pipes at both nozzles takes part at once; a side without them takes no heat.

## 10. Work packages and gates

The owner's order (Q8) put the utility exchangers first because they need no coupling. Real fluid on both sides (Q5) removes them, so the two-sided coupling moves up next to the device. The air cooler and the fired heater, the plant's heat sink and source without utilities, come before the kettle (owner question Q12).

| Package | Content | Gates |
|---|---|---|
| W1 Heat law | `PipeHeatExchange` back from `tools/pipe-thermal-loss/prototype.patch`; the film column on the curve and its blend; the side kernel (8 counterflow cells, β); the passage law; the heat in rows, reconstruction, audit, balanced start, seeds, rate solve | kernel vs the analytic exponential and latent knots, with a film step along the curve; zero and reversed flow; the audit with heat; a junction that boils from heat (water at 5 bar heated past 152 °C); nothing moves without exchangers: chain-100 exact, the 33 junction lines |
| W2 Device | registration of both sides as connections; facts schema 5 and its corpus; the screen; old builds | facts round trip, corpus gate; one in-game check of the screen |
| W3 Two-sided coupling | account store, as-of read with the open slice's rate, the joint steady wall on curves, slice mean, clamp, cap, wake, certificate signature, the dispatch wait | GameTests: two islands reach the 8-cell steady state at equal and unequal cadences; a held partner; a shared-island feed/effluent loop; a condensing mixture against water at 20 s slices (the stability case); energy ledger exact |
| W4 Control | the controller between slices, its modes, the low limit, statuses | an outlet held after an inlet step on a test rig |
| W5 Air cooler | fans, the finned air side, UA_eff, fan power | outlet vs ambient at fans on and off |
| W6 Fired heaters | firing duty, capped | outlet at duty; steam raised from water |
| W7 Kettle | the pool as a vessel side, the heating medium a side | boil-up = duty / latent heat at the pool's pressure |
| W8 Exchanger assembly | blueprint per control mode, FLOWMETER in the walk, Validate structure, Operation through the real valves, targets as slice inputs | matcher cases on `TestGraph`; GameTests: a built bypass validates complete; an outlet held through the real bypass valve after an inlet step; a ratio held after the reference flow steps; a removed bypass pipe reverts the report and the valves hold |
| W9 Column operation hook | a validated column assembly sets its condenser's, reboiler's and pumparound coolers' duty targets | with the column's operation stage |

Final lane before the merge: `test slowGates`, chain-100 exact, fluid GameTests, the campaign. Worlds without exchangers must not move.

## 11. Simplifications and what they change

| Simplification | Effect in numbers | Deletes | Risk |
|---|---|---|---|
| A film per phase (2000, 5000, 100 W/m²K) | pairs land inside or near design U; water films low (real 5000+), organic condensers and kettles 1.5 to 2 times high | film correlations (velocity, properties, geometry) | a fluid-by-fluid table later |
| The film on the curve, by vapour fraction | a stream that starts to condense gets the condensing film where it does; desuperheating on a wet wall keeps the gas film (it condenses on a wall below the dew point in reality) | a slice-start phase, a film mode | a desuperheating condenser weak in its first cells (not measured; W1 can compare) |
| Counterflow for the drawn 1-2 shell (Q3) | duty +8 %, +20 %, +42 % at NTU 1, 2, 5 (Cr 1); the example +21 % | the 4 × 2 cell arrangement | large shells cross temperatures they could not |
| No fluid held inside the passages | the wall alone stores heat | passage inventory nodes | none at steady state |
| The joint steady wall in the profile | transients 1.5 to 2 times the cell-local form's at 20 s slices; a slow sub-kelvin tail at NTU above 10 | a cadence cap or stability guard | none at steady state |
| Inlet-stream hydraulics through a condensing or boiling side | the ΔP of a condensing side reads the vapour's properties | an axial pressure profile | the 2026-09-24 study: a correction is not reliable |
| Internal bypass neutral to the hydraulics | a side's ΔP does not change with β | a bypass line in the network | none; the assembly's bypass (W8) is the real line |

## 12. Owner questions

Answered on 2026-10-27: Q1 to Q9 (section 3).

- **Q10 Wall weight.** The owner asked that the films add no lag. With the steel wall (Q4, 12 kJ/K per m²) gas exchangers settle in 6 minutes (gas/gas) and 2.3 minutes (gas/liquid). A quarter of it (3 kJ/K per m²) gives 1.5 and 0.6 minutes (the coupled worst case: 2.0 and 1.0 minutes) and liquid/liquid 1 minute. It moves no steady state and stays stable with the joint steady wall. Recommendation: the quarter wall, as the lag approximation. Or keep the steel?
- **Q11 Air and fuel.** I read Q5 as concerning the two piped sides. The air cooler's air (no nozzles, the fans' own) and a fired heater's fuel (its datasheet duty) stay inside the units. Agreed?
- **Q12 Order.** W1 heat law, W2 device, W3 two-sided coupling, W4 control, W5 air cooler, W6 fired heaters, W7 kettle, W8 assembly, W9 column hook (section 10). Agreed?
- **Q13 Two bypasses.** When an assembly is complete, its real valves take over and the internal bypass stays at 0 on that side. Keep the internal bypass for exchangers without an assembly (recommended: a simple build still controls), or allow control only through an assembly (one mechanism)?

## 13. Risks

- **Phase change at the outlet.** Heat that boils or condenses a stream does it at the side's outlet junction, as a letdown valve's flash does. It goes through the same arriving-phase and flash seeds that held islands in the past (OD4, the cold and bundled seeds). W1 adds a junction-boil gate, and the campaign counts holds in worlds with exchangers.
- **The joint steady wall on curves.** The study marched streams of constant capacity; W3 solves the 8-cell steady wall on two phase-change curves, a fixed point. Its convergence needs a gate (a condensing mixture against water) and a bounded iteration count. Where the iteration stops short, the profile is still a valid wall: energy stays exact, and only the transient changes.
- **Determinism.** The as-of read depends on simulated time only while both islands keep up. A side that is behind (debt or a hold) is extrapolated, so its partner's result depends on when it caught up, within the one-slice cap. The open slice's rate is fixed by the slice's own inputs, and the dispatch wait keeps thread timing out.
- **A missed wake** leaves a certified island at a stale duty until its certificate horizon.
- **Cost.**
  - One curve per side per slice (17 to 30 flashes, of the order of milliseconds).
  - The joint steady wall: tens to hundreds of 16-cell marches, microseconds each.
  - The kernel costs microseconds per residual.
  - The assembly adds two junctions and a regulating valve per controlled side.
- **The column's operation** (next stage of the multi-machine framework) may want the column's computed duties to drive these exchangers, or the exchangers to bound the column. W9 keeps the duty mode as the hook; the choice belongs to that stage.
