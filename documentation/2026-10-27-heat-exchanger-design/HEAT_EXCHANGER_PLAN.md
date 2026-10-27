# Heat exchanger system: plan

Batch `2026-10-27-heat-exchanger-design`. Base `995dbd9c` (main, 0.15.0). Status: In progress since 2026-10-27. Revision 2 (the same day) takes in the owner's answers to Q1 to Q9; revision 3 (the same day) the answers to Q10 to Q13 (section 3); revision 4 (the same day) the plan review. W1 (the heat law) is built, with three changes recorded in section 10.

Evidence:

- [HX_COUPLING_STUDY_REVIEW.md](HX_COUPLING_STUDY_REVIEW.md): the first study (cell count, coupling schemes, holds, controller).
- [HX_FILMS_STUDY_REVIEW.md](HX_FILMS_STUDY_REVIEW.md): the per-phase film study of revision 2 (lag, coupling forms, stability map).
- [HX_ZERO_WALL_STUDY_REVIEW.md](HX_ZERO_WALL_STUDY_REVIEW.md): revision 3, the wall without heat (the relay of heat in transit, the give-back split, holds, controller).
- [HEAT_EXCHANGER_PLAN_REVIEW.md](HEAT_EXCHANGER_PLAN_REVIEW.md): the review of revision 3 against the code (22 findings, taken into revision 4).
- Harnesses: `tools/heat-exchanger-design/`.

Open: the form of the coupling's timing after study W3a (section 5.2). The owner answered Q14 on 2026-10-27: the one-sided units go before W3a.

Owner request 2026-10-27:

1. Every piece of equipment has a UA that scales with its size. Take U constant at a reasonable value.
2. Built-in control, so that exchangers do not add convergence trouble to the fluid solver: a fixed input ratio through an internal control valve, a target hot or cold side temperature, or what else?
3. Input and output buffers that decouple the fluid networks on both sides, with the pressure relayed through a calculated pressure drop? A lag in the response is acceptable.

Short answers (with the owner's answers):

1. **UA from size.** UA = U × the tube surface the datasheet already shows (75 D² L m²). 1/U = 1/h_hot + 1/h_cold, with one film coefficient per phase: liquid 2000, boiling or condensing 5000, gas 100 W/m²K. The films are approximated so that they add no lag: a property of the side's enthalpy curve, not of the slice start. Section 4.
2. **Control.** A controller that acts between slices, never inside the Newton: an internal bypass to an outlet temperature, a duty, or a manual share. A flow ratio, and the bypass as real lines, come with the exchanger assembly (section 7). Section 6.
3. **Buffers.** Decouple the heat, not the mass. Each side stays a pass-through in its own island with exact hydraulics. The two sides exchange only energy, one slice late; the heat they book differently waits in a small transit account and goes back within the next slice. Section 5.

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
- **Dynamics**: real steel stores heat (12 kJ/K per m²). In the 450 m² exchanger an outlet settles within 1 K of a 30 K inlet step in 36 s (liquid/liquid), 66 and 138 s (gas/liquid), 6 min (gas/gas) and 7 s (condensing/liquid). The owner dropped this lag (Q10): the wall holds no heat, and the exchanger is quasi-steady. Its outlets follow its inlets at once, and the only lag left is the coupling's, one slice (section 5).
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

  From there the work reaches `PassiveIntervalSolver.Result.pumpWorkJoule`: the certificate summary and its replay (`IslandCertificate`) and the checkpoint state (`FluidCheckpointCodec`, state format 22). Exchanger heat rides the boundary transfers instead, as a new kind (section 5.1).
- **Devices.** A registered device belongs to one island. Only reservoirs and movers carry ports today (`PhysicalFluidTopology.Device`), and an equipment structure binds one fluid identity (`EquipmentStructure`).
- **The heat law exists as a study.** The pipe thermal-loss study (batch `2026-09-24-pipe-thermal-loss`, concluded, not merged) built it: a stream relaxing towards a wall temperature along its enthalpy-temperature curve (`PipeHeatExchange`, patch in `tools/pipe-thermal-loss/`).
  - Exactly zero heat at zero flow; latent heat exact.
  - A fixed 17-point curve stayed within 7.1 % of a 513-knot reference over 561 cases, condensation included. An adaptive 9 to 30-point curve stayed within 0.05 %.
  - Re-solving the pressure drop with the cooled properties was not reliable, so the hydraulics keep the inlet stream's properties.
  - The patch predates the rename (`com.wormzjl.createcheme`), throws for an enthalpy or a wall outside its curve, and holds the kernel only; the curve builder is in the study harness (`PipeThermalStudy.Sampler`). It places knots in T and bisects a phase change to 1e-5 K, so no knot lies inside a pure component's plateau.
- **Buffers were tried and removed.** Equipment as island separators with built-in buffers was the 2026-09-15 engine design. It was built as the causal module system (buffered transfers, pending deliveries, time fences) and removed on 2026-10-15 (`944a79f6e`, consolidation item F1), because nothing in the game produced a module.
- **Islands advance in slices** of their own adaptive cadence (20 to 400 ticks). A slice `[committed, due]` is offered only once online time reaches `due` (`IslandClock.nextSlice`). A worker solves it from a command with no live world and no callback, and the server thread commits it, up to the 2 s hard budget later. An island certifies STEADY when its intervals repeat; a certificate ends at a topology replacement, a property reload or its horizon (`revalidate`). `inputsChanged` retries a held island and does not end a certificate.
- **Valves and assemblies exist.** Process control (0.14.1) gives control valves with a pressure or a flow module, regulated ideally in the Newton, and flowmeters on pipes. The multi-machine framework (0.15.0) gives assemblies: a master's blueprint of slots and links, Validate structure, reports as state, and reverts without polling (code guide `machine-assemblies.md`).

## 3. Owner decisions (2026-10-27)

| Question | Answer | Where |
|---|---|---|
| Q1 coupling | B, an account between islands (with Q10, the heat in transit) | section 5 |
| Q2 U | A film per phase, approximated so that it adds no lag | section 4 |
| Q3 arrangement | Counterflow only, for now | sections 1, 4 |
| Q4 wall | Steel only, 12 kJ/K per m² (superseded by Q10) | |
| Q5 utilities | Real fluid on both sides; no implicit utilities | sections 5, 8 |
| Q6 control | Between slices, dead-beat, with the modes of section 6 and the low limit | section 6 |
| Q7 ratio control | A multi-structure with real lines (the bypass line first) and valves, as its own work package | section 7 |
| Q8 order | As recommended, adapted to Q5 (Q12) | section 10 |
| Q9 old builds | An exchanger built before this batch registers when its chunk loads | section 9 |
| Q10 wall weight | No vessel heat capacity: the exchanger is quasi-steady | sections 1, 4, 5 |
| Q11 air and fuel | The air cooler's air enters at a constant 25 °C, for now. A fired heater heats to a set temperature; when its duty falls short it fires at the maximum and shows an alert. Fuel is another scope | sections 4, 6 |
| Q12 order | W1 heat law, W2 device, W3 coupling, W4 control, W5 air cooler, W6 fired heaters, W7 kettle, W8 assembly, W9 column hook | section 10 |
| Q13 two bypasses | Keep the internal bypass for exchangers without an assembly; a complete assembly's valves take over | sections 6, 7 |
| Q14 order after the review | The one-sided air cooler (W5) and fired heater (W6) before the timing study and the coupling: W1, W2, W5, W6, W3a, W3, W4, W7, W8, W9 | section 10 |

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
   - Knots at a vapour fraction of 0.05 and 0.95 are placed along h between the bubble and dew knots (h is linear in the vapour fraction there). Without them a pure component's plateau, which has no inner knot, would take a film running from 2000 to 100 across its latent heat (mean about 1050) instead of 5000.
   - The kernel integrates dh/dA = −h_film(h) (T(h) − T_wall) / ṁ exactly per segment. With both factors linear in h on a segment this is a partial-fraction integral, with its degenerate branches (a constant film, a constant temperature, a common root).
   - A stream that starts to condense halfway through the exchanger gets the condensing film there, in the same Newton.
   - The film is continuous along the curve: no new unknown, no switch, no mode.
   - **The curve's span**: from the side's inlet to the other side's inlet temperature as of the slice start, with a margin of 10 K each way. Beyond it the curve extrapolates with continuous value and slope, and the island's status line carries a data-range notice (AGENTS.md "Thermo validity"). No enthalpy or wall temperature raises an exception inside a residual.
3. **The other side's film** reaches a side only through the coupling record (section 5), never through its Newton.
4. **No wall heat** (Q10). With steel, weak films charge the wall slowly (gas/gas 6 minutes); without it a gas exchanger answers as fast as a water one, one slice late. No steady state moves.

**Air cooler.**

- Tube side: the phase film on 5 m² of bare tube per m² of face.
- Air side: the gas film, 100 W/m²K, on 50 m² of finned surface per m² of face (fin ratio 10): 5 kW/K per m² of face.
- Overall, per m² of face: 3.3 kW/K (liquid), 4.2 (condensing), 0.45 (gas).
- The fans move air at 3 m/s, 3.5 kW/K per m² of face. Every tube cell meets fresh air, so the process stream sees ambient air through UA_eff = C_air (1 − e^(−UA/C_air)): 2.15 (liquid), 2.44 (condensing) or 0.42 (gas) kW/K per m² of face.
- The largest bay, process at 85 °C in 25 °C air: 1.6 MW liquid, 1.8 MW condensing, 0.3 MW gas.
- Fans off: natural draught, the air's capacity and film to a quarter.
- Fan power is a datasheet and ledger line only: the network has no electricity to draw it from.
- The air is the unit's own (no nozzles), not a utility. It enters at a constant 25 °C (Q11), for now: no weather, biome or altitude. The heat it carries away leaves the network and is booked in the ledger as the cooler's duty.

**Fired heaters** (Q11): heat the process stream to a set temperature, at most the datasheet duty (`heaterDuty`).

- The law, per slice: heat = β ṁ (h_set − h_in). h_set is the set temperature's enthalpy at the outlet pressure and the slice start's composition (a set temperature at a pure component's saturation temperature names saturated vapour: a heater boils); h_in is the arriving enthalpy. β is fixed for the slice:
  - 1 when the duty suffices: the outlet reaches the set temperature exactly;
  - Q_max / (ṁ (h_set − h_in)) at the slice start when it does not: the heater fires at its maximum, and the status shows the alert "duty insufficient: outlet X °C below the set point";
  - 0 when the stream arrives at or above the set point: the heater does not fire.
- Inside the Newton the heat is linear in the flow, exactly 0 at no flow, and never carries the outlet past the set point while β is 1. No new unknown and no switch: β changes only between slices.
- The heat enters the network from outside and is booked in the ledger as the heater's duty, as a mover's shaft work is. The fuel (its stream, its flue gas, its cost) is another scope: the heater fires without fuel for now.

**What the films change against a constant U of 500:**

- Gas coolers are no longer 2 to 25 times too strong.
- Water/water is about right.
- Condensers have 2.9 times the U and kettles 5 times. The duty grows less where the approach limits it: 1.45 times for a condenser on the example's 50 kg/s of water (NTU 1.1 at U 500), and the full 5 times for a kettle (both sides isothermal).

## 5. Coupling the two sides (owner Q1: B)

Physics: the hot fluid heats the tube wall, and the wall heats the cold fluid. Without wall heat (Q10) each side feels the other at once. Two islands can only feel each other one slice late. The design keeps the islands apart: they exchange only energy, never mass or pressure, and the heat their slices book differently waits in transit until it goes back.

### 5.1 Each side is a connection in its own island

Each side runs between its inlet and outlet nozzles (`PortMarkings` already marks them IN and OUT, and pairs them). Each side is its own device: a new device kind with an IN and an OUT port, owned by the island its pipes join. The two sides are tied by one pair record (section 9).

- **Hydraulics**: a passage law like a pipe's, for parallel channels, on the inlet stream's properties. Pressure is not relayed: the island's own solve passes it through the side, as through a pipe.
  - Tube side: 630 D² tubes per pass of 14.8 mm bore, length 2 L, plus 4 velocity heads per pass for entry, return and exit. The example's 50 kg/s of water runs at 0.46 m/s and loses about 4 kPa; at the design velocity of 1.5 m/s (163 kg/s) about 35 kPa.
  - Shell side: an equivalent passage of 18 mm hydraulic diameter and 0.1 D² crossflow area, one shell diameter per baffle crossing, with baffles 0.4 D apart (length 2.5 L).
- **Heat**: the exchanger has 8 wall cells in counterflow (Q3). The hot side passes cells 1 to 8, the cold side 8 to 1.
  - Each stream relaxes towards each cell's temperature T_k over the cell's eighth of the surface, on its curve with its film (section 4).
  - N = 8 is within 0.9 % of exact counterflow up to NTU 5 at Cr 0.5, and within 2.1 % at Cr 1.
- **Fixed for the slice**: the curve (built on the worker at the slice start from the stream the donor end's port draws and the donor's pressure, at both ends for a reversal; a drum's liquid draw has its own bubble point, not the drum's whole mixture's), the wall profile T_1 to T_8 (section 5.2), the give-back's enthalpy shift (section 5.2), and the bypass share β (section 6).
  - Inside the Newton the heat is a pure function of the connection's flow and the donor's enthalpy: no new unknown, no new mode, no switch inside a residual.
  - It is C1 in the flow, exactly 0 at no flow, and bounded by the approach to the wall.
  - A reversed side passes the cells in the other order against the same profile: parallel flow for that slice, the profile having been computed for the dispatch direction.
- **Where it enters**: as a mover's shaft work does:
  - the receiving node's energy row;
  - the reconstruction: a boundary transfer of a new kind `HEAT` at the connection's id, energy only, in the step's external energy;
  - the audit, through that external energy;
  - the interval result's boundaries, and through them the certificate summary and replay and the checkpoint state. The codec writes the kind as a checked ordinal, so the appended kind leaves state format 22 as it is.

  Every reader of a delivered enthalpy takes it too: the balanced start (`BalanceNet.seeded`, `mixed`), the junction start mix and the arriving-phase seeds (`arrivingPhases`), each with the temperature off the same curve; the rate solve reads the same rows. The gauges' pipe observation keeps the donor's temperature until W2 gives the side a screen.

Both sides always carry real fluid (Q5). A side with no pipe takes no heat.

### 5.2 The heat in transit between the two sides (the relay)

The wall holds no heat (Q10), so its profile is not a state: it is the quasi-steady wall of the moment. What the two sides book differently, while each still sees the other as it was, is the exchanger's one fact beyond its registration, the **heat in transit**: one energy per exchanger. Each side books its slice's heat into it, so energy is exact by construction.

**Timing in the game.** A slice `[t0, t1]` is offered only once online time reaches t1, solved on a worker without callbacks, and committed up to the 2 s hard budget later (section 2). The harness's part 7 planned each slice at t0 and read a lagging partner at the rate of its open slice, fixed when that slice was dispatched, with a dispatch wait. In the game a long-cadence partner has no open slice for up to a whole cadence, and a wait would be process work on the server thread. So how a side reads its partner is decided by a timing study first (W3a: harness part 8 with dispatch at the slice end and a job latency of 0 to 40 ticks). It compares three forms, and the owner chooses:

- **A, as of the slice start**: the partner's record at t0; a partner not yet committed through t0 is extrapolated at the heat rate of its last committed slice (carried in its commit record), for at most one of its slices.
- **B, the partner's history within the slice**: a slice is dispatched after t1, so the partner's records committed within `[t0, t1]` exist and are used piece by piece; A covers the rest.
- **C, a coupling fence**: a slice waits until its partner has committed through t0 (a readiness condition on the server, no worker wait; a certified partner is materialised). This is deterministic, and the fast side runs up to one partner cadence behind online time. The side furthest behind can always go, so the fence cannot deadlock.

The steps that do not depend on the choice:

1. **The other side as read**: its inlet state, flow, curve and conductances.
   - It counts as **stopped** only in an explicit state: no island, held, phase-parked or suspended (`IslandCoordinator`), never from lag, since job latency alone delays every commit of a long-cadence partner. Over the spans in which either side counted the other as stopped, neither side moves heat; the other side reads those spans from the record and sits them out too.
   - A certified other side is first materialised to the tick (its replay books its certified heat).
2. **Towards the quasi-steady wall.** The profile T_1 to T_8 is the exchanger's steady wall with both streams marched through the 8 cells, on their curves with their films: the own side at its inlet state and flow now, the other side as read. The slice heads straight for it; no mean over the slice, no clamp. On curves each cell's temperature is a root (the two sides' heats equal) inside each sweep: tolerance 0.01 K, a sweep cap, and a profile stopped short is still a valid wall (energy stays exact).
3. **The transit as read**: the other side's heat booked past the read tick taken out, a lagging side's heat added as the chosen form says.
4. **The give-back.** The side takes its share of the transit within its slice. The share is its slice's heat capacity against the two: W_own Δt_own / (W_own Δt_own + W_other Δt_other), with W the capacity rate through the cells. Both outlets then shift by the same temperature, whatever the slices; a condensing or boiling side (W unbounded) takes all of it at its constant temperature.
   - **In the Newton** it is an enthalpy shift Δh on the side's flow, fixed for the slice: the heat is ṁ Δh, linear in the flow and 0 at no flow. Δh = share / (ṁ₀ Δt) with ṁ₀ the flow at dispatch, clamped there so the outlet at the dispatch state stays between the two inlet temperatures.
   - The commit books what the flow actually carried; the rest stays in transit. A side without flow takes none.

**Why these steps** (reviews of revision 3):

- **The as-of read and a lagging side's extrapolation** (from revision 2). Without them the outlets settle off the steady state, and unequal slices overshoot. Part 7 measured the open-slice rate, which the game does not have; W3a measures the forms above.
- **Planned before the slice's own result.** The harness's earlier parts planned each slice at its commit and so read the partner's end-of-slice state; those results were optimistic at equal slices.
- **The slice-heat split**, against conductance and capacity rate: the smallest give-back in every case (liquid/liquid 50 kg/s 12 K against 19 K) and the best controller. The cost: some unequal-slice rows settle in 59 s instead of 44 s.
- **The clamp and the stopped rule.** They keep a give-back into a small stream, and the catch-up after a hold, between the inlet temperatures.
- **The results** (part 7, dispatch at the slice start, no latency; W3a repeats them with the game's timing): the exact 8-cell steady state everywhere; heat left in transit 2e-5 J or less (49 J after a 600 s hold). Within 1 K in two slices at equal slices (1, 9, 39 s at 1, 5, 20 s), 24 to 59 s at unequal ones, 139 s at worst after a 5× flow cut. The give-back moves an outlet by 0 (condensing) to 15.5 K for one slice, and up to 77.5 K after a hold.
- **What Q10 deleted**: the wall state, its stability limit (x = G Δt / C), the slice mean, the wall clamp, the cell-local against joint choice, the initial wall.

The same rule serves an exchanger whose sides share one island, such as a feed/effluent loop: the loop closes across slices, not inside the Newton.

**Wake.** A commit can move the record the other side's certificate holds (inlet temperature by more than 0.05 K, flow by more than 0.1 %; thresholds W3 measures), or leave it a give-back that would move its outlet by more than 0.05 K. Then the other side's certificate is discarded through a new coordinator entry: a `revalidate`-like discard without a horizon, then `reconsider` (`inputsChanged` does not end a certificate). A side with no flow takes no heat and needs no wake.

**Threads and determinism.**

- The command carries the transit as read and the other side's record: its inlet state, flow and curve; its conductances; its last committed heat rate; its stopped spans (arithmetic, server thread).
- The worker first builds the curve and the quasi-steady wall, then runs the controller and the Newton. It never waits on another job.
- The commit books the heats (arithmetic, server thread). No process calculation runs on the tick.
- With forms A and B a side's result depends on how far its partner had committed when it was dispatched, which depends on job times, within one partner slice. Form C removes that dependence at the cost of lag. The runtime already lets wall time shape slices (a failed budget retries a shorter slice).

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
3. Phase changes the heat causes at the outlet: this is physics. A target outlet state keeps a stream away from a boundary the player did not ask for; nothing removes it in general (section 13).

| Mode | It holds | |
|---|---|---|
| Manual bypass share | a share β of one side passes outside the cells | built in |
| Outlet temperature by bypass | the hot or cold outlet at T_set | built in; the default |
| Duty | Q_set, at most what the exchanger can do | built in; the kettle's and the fired heater's natural mode; the hook for a column that takes over its exchangers (W9) |
| Fan | the air cooler's outlet temperature or duty | the same mechanism, the fans' share in place of β |
| Firing | the fired heater's outlet at its set temperature, at most its duty | the heater's own law (section 4), with the alert "duty insufficient" |
| Low limit (freeze guard) | the cooled outlet stays above T_min | built in as a limit, not a target: against ice, CO2 crystals and wax downstream |
| Flow ratio | cold flow = r × hot flow | not built in: the exchanger assembly's ratio station (section 7) |

**Mechanism.**

- At each slice start of the controlled side's island, on the worker, the controller finds β*: the bypass share whose mixed outlet meets the target, or whose heat meets the duty. It searches by bisection on the side's own heat law against the slice's profile, at the committed inlet state and flow; the response is monotone in β.
- The controller sets β = β* (dead-beat), and β stays fixed for the slice.
- β scales the flow that passes the cells, (1 − β) ṁ; the rest joins at the outlet. The hydraulics do not see β: the internal three-way valve keeps the side's resistance.
- When β* falls outside 0 to 1, the controller stops at the limit, and the status reads "limited".
- The controller does not take the planned give-back into its target. Measured: that closes a loop through the transit and settles 4 to 5 times slower at 20/5 s slices.
- On a kettle's heating side, the bypass means steam that leaves uncondensed. A real steam valve is the assembly's (section 7).
- The internal bypass serves every exchanger without a complete assembly (Q13), so a simple build still controls. A complete assembly's real valves take over (section 7).

**A target temperature becomes a target enthalpy** at the outlet pressure and the composition of the slice start. A temperature at a pure component's saturation temperature names a range of enthalpies. It is read as saturated liquid when the controlled side is cooled, and as saturated vapour when it is heated. A target vapour fraction names any other point on the plateau.

**Evidence** (revision 3, the wall without heat): cold outlet held at 90 °C by the bypass, hot inlet +30 K. The first slice after the step shows the uncontrolled response, 12.7 to 14.7 K: nothing damps a step, and the controller is one slice late. The outlet is within 0.1 K after 7 to 8 slices (7, 39 and 159 s at 1, 5 and 20 s slices; 69 to 139 s at unequal slices), exact at the end. The stiff pair stays within 0.96 K.

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
  - **The target is a slice input**, carried in the island's command like β, not a registry edit: a registry edit per slice would recompile the island. On the worker it overrides the valve's `massTarget` in a command map. The valve's target is part of the graph identity in the certificate digest (`IslandCertificate.graphIdentity`), so the digest leaves the override out, and a moved target wakes a certified island through the wake entry of section 5.2.
- **Not complete** (a part missing, or reverted after an edit): the valves hold their last accepted targets, as the data-system contract holds a broken signal's last opening. The screen says so.
- **Convergence**: each controlled side adds two junctions and a regulating valve, elements the solver already handles. The temperature loop stays outside the Newton.
- **Saves**: the per-side control (mode by name, side, target, ratio) is a fact on the pair record (fluid facts schema 5, section 9). The report is state.

## 8. Player view

- **Build**: the constructor, as today. A complete exchanger, kettle, air cooler or fired heater registers one fluid device per side, as a constructed mover registers its device: an inlet and an outlet port at the side's nozzles, plus the pair record that ties the sides (W2). An air cooler and a fired heater have one side.
- **Real fluid on both sides** (Q5): a side heats or cools only what flows through it. There is no utility picker. Cooling water is water from a tank, pumped through the exchanger and back through an air cooler (the sink). Steam is water boiled in a fired heater (the source). A generator block serves as either in testing.
- **Kettle**: its shell is a vessel (a reservoir with FEED, VAPOUR_OUTLET and LIQUID_OUTLET ports, like a drum). The heating medium in the tubes is a side. The pool is the side's partner: its record is the pool's temperature (isothermal, W unbounded in the give-back split), and its heat, give-back included, enters the vessel's energy row in the vessel's island (W7).
- **Screen** (engine-owned: replay on open, updates at the island's bucket):
  - each side's inlet and outlet temperature, pressure, flow, phase and film;
  - duty, U, UA, NTU, effectiveness, approach;
  - the wall profile and the heat in transit;
  - the control (mode, side, target, β, limited).

  Notices: "other side held: counted as stopped", "control limited", "duty insufficient" (fired heater), "assembly not complete: valves hold". The drawing-only datasheet stays a page. With an assembly, a Structure tab as the column's.

## 9. Saves (AGENTS.md "Saves and data formats")

- **Facts** (append-only; fluid facts schema 4 → 5):
  - each side's registration: a side device of a new kind by name, its IN and OUT ports;
  - one pair record per unit in the topology unit's facts (not in either island's, since the two sides may sit in two islands):
    - the side ids;
    - the control per side: mode and side by name, target, ratio;
    - a fired heater's set temperature;
    - the heat in transit (one energy per unit), which is energy the world holds.

  A missing field reads its old behaviour: control off, no heat in transit. W2 writes every field the later packages read (control, set temperature, transit), so the schema changes once and one corpus image covers it: `src/test/resources/compat/fluid/schema-5/`, captured in W2. A package that still has to add a field adds its own image.
- **State** (rebuilt freely): the per-side records for the partner read (inlet state, flow, curve, conductances, last committed heat rate, stopped spans), β, the curves, the assembly report. The interval result carries the heat as `HEAT` boundary transfers; checkpoint state format 22 stays (section 5.1).
- A removed unit takes its heat in transit with it, booked in the ledger as a removed vessel's contents are. A side removed while the other runs: the transit is booked at the removal, and a running slice whose plan still names it books none of it.
- **Old builds** (Q9): an exchanger built before this batch registers when its chunk loads, with control off. A side with pipes at both nozzles takes part at once; a side without them takes no heat.

## 10. Work packages and gates

The owner's order (Q8) put the utility exchangers first because they need no coupling. Real fluid on both sides (Q5) removes them, so the two-sided coupling moves up next to the device. The air cooler and the fired heater, the plant's heat sink and source without utilities, come before the kettle. The owner agreed this order (Q12). Revision 4 adds W3a, the timing study W3 waits for. The owner put the one-sided units before it (Q14): W1, W2, W5, W6, W3a, W3, W4, W7, W8, W9.

| Package | Content | Gates |
|---|---|---|
| W1 Heat law | port and extend the kernel of `tools/pipe-thermal-loss/prototype.patch` (renamed package; the curve builder from the study harness); the film on the curve with knots at a vapour fraction of 0.05 and 0.95 and the exact per-segment integral (partial fractions, degenerate branches); the curve's span and C1 extrapolation beyond it with a notice; the side kernel (8 counterflow cells against a wall profile fixed for the slice, β, reversed flow); the side as a component of `PassiveNetwork.Pipe` beside a passive control; the heat in rows, reconstruction, audit, balanced start, seeds, rate solve; the heat as `HEAT` boundary transfers through the interval result, the certificate summary and replay and the checkpoint state (format 22 kept) | kernel vs the analytic exponential and latent knots, with a film step along the curve; a pure condensing stream against water gives U 1430 within 1 %; a donor enthalpy that leaves the span converges; zero and reversed flow; the audit with heat; a junction that boils from heat (water at 10 bar heated past 180 °C); nothing moves without exchangers: chain-100 exact, the 33 junction lines |
| W2 Device | the side device kind (IN and OUT ports), the pair record in the topology unit's facts with every later field, the structure binding both sides and its refresh; the passage law (a channel count on the section geometry, read by the codec and the certificate signature); facts schema 5 and its corpus; the screen; old builds. After W2 alone a two-sided exchanger moves no heat (no partner read) | facts round trip, corpus gate; one in-game check of the screen |
| W3a Timing study | harness part 8: dispatch at the slice end, job latency 0 to 40 ticks, adaptive cadence; forms A, B and C of section 5.2 on the part 7 cases and holds; owner choice | a review of at most 50 lines |
| W3 Two-sided coupling | the transit store, the partner read in the chosen form, stopped from explicit states, the quasi-steady wall on curves (tolerance, sweep cap), the give-back as an enthalpy shift (slice-heat split, clamp at dispatch), wake through a new discard entry, certificate signature | GameTests: two islands reach the 8-cell steady state at equal and unequal cadences; a held partner; a shared-island feed/effluent loop; a condensing mixture against water at 20 s slices; the give-back at zero flow books 0 J; the transit returns to 0; energy ledger exact; the wall solve's cost in the campaign's wall-time class |
| W4 Control | the controller between slices, its modes, the low limit, statuses | an outlet held after an inlet step on a test rig |
| W5 Air cooler | fans, the finned air side, UA_eff, air at a constant 25 °C; fan power as a datasheet and ledger line | outlet vs 25 °C air at fans on and off |
| W6 Fired heaters | the set-temperature law (β per slice), the duty cap, the alert; no fuel | outlet at the set temperature; outlet and alert when the duty falls short; no firing above the set point; steam raised from water (set point above saturation) |
| W7 Kettle | the pool as the side's vessel partner (its record, the give-back into the vessel's energy row), the heating medium a side | boil-up = duty / latent heat at the pool's pressure |
| W8 Exchanger assembly | blueprint per control mode, FLOWMETER in the walk, Validate structure, Operation through the real valves, targets as slice inputs (command override, kept out of the certificate digest) | matcher cases on `TestGraph`; GameTests: a built bypass validates complete; an outlet held through the real bypass valve after an inlet step; a ratio held after the reference flow steps; a removed bypass pipe reverts the report and the valves hold |
| W9 Column operation hook | a validated column assembly sets its condenser's, reboiler's and pumparound coolers' duty targets | with the column's operation stage |

W1 as built (2026-10-27), three changes from the plan of revision 4, each reversible:

- The side is a component of `PassiveNetwork.Pipe` (`HeatSide`) beside a passive control, not a `FlowControl` kind, so every mode rule stays as it is.
- The heat is booked as `HEAT` boundary transfers (section 5.1), not as a new interval-result field. The audit, the certificate replay and the codec read it with no format change.
- The passage law moves to W2. A channel count on the section geometry reaches the codec and the certificate signature, and the device states the side's geometry.

Left open by W1, for W2's screen: the extrapolation notice on the island's status line (the kernel reports it, `HeatCurve.extrapolated`) and the gauges' outlet temperature.

W2 as built (2026-10-27), as designed after mapping the device code:

- **Device.** A new device kind `HEAT_SIDE` (by name) with ports of two new roles, `INLET` and `OUTLET`: at least one of each (an air cooler has several inlets). Its node is its first inlet's cell, as a mover's is its suction cell.
- **Compile.** As an inline filter: the inlet runs end at the device's node, the outlet runs move to a new junction at the outlet port, and a passive pipe of the side's passage joins the two. That pipe carries the `HeatSide` once a package supplies a wall (W5, W6, W3); after W2 it carries none and moves no heat.
- **Passage law.** `PipeResistance.Geometry` gains a channel count (default 1): the drop of one channel at the flow divided by the count, the area and volume of all channels. Exchanger tube side 630 D² channels per pass of 14.8 mm, 2 L, K 8 (two passes); shell side 0.1 D² / (π 0.018² / 4) channels of 18 mm, 2.5 L. The count reaches the facts' geometry record, the checkpoint state (format 22 → 23), the certificate's graph identity and the sections' merge key.
- **Pair record.** Not a separate list: each side's registration carries one appended record: the unit kind, the side's role, its area, its partner's id (0 for a one-sided unit), its control (mode by name, target, low limit, manual share) and its heat in transit. Registrations are the topology unit's facts, world-wide, and a unit's sides are placed, edited and removed in one ledger event, so the pair stays whole. Facts schema 4 → 5, one corpus image.
- **Structure.** A unit registers one node per side (each side's first inlet cell). `register`, `restoreFluidBinding`, `unregister` and the refresh loop over the nodes; a shell cell binds the registration at its own cell, as now. W2 registers the exchanger (tube and shell sides); the air cooler (W5), the fired heaters (W6, two coils in series) and the kettle (W7) add theirs.
- **Screen.** A side opens the fluid device screen of its own registration, titled with its side ("Heat exchanger: tube side"), with an "Other side" button that opens the partner's. The structure opens its first side. A side has no player edit until W4: the server refuses one, as a reservoir's.
- **Old builds.** An exchanger placed before W2 registers its sides when it next loads whole, as a new build does (owner Q9).
- **Moved to W5.** The one in-game check of the screen, the extrapolation notice and the outlet temperature: W5's air cooler is the first side that moves heat, so one check judges them together.

W5 as built (2026-10-27):

- **Side.** An air cooler bank registers one side, PROCESS, between its process nozzles, over 5 m² of bare tube per m² of face and bay. Its passage: each bay's tubes in one pass (5 (w − 0.22) / (π 0.0254) tubes of 21.2 mm bore), the trains in parallel, the bays of a train in series, 1.5 velocity heads per bay. A 3 × 6 m bay has 174 tubes and loses about 10 kPa of water at 1.5 m/s.
- **Heat law.** The side's `HeatSide` is a flat wall of air at 25 °C (one cell) under the effective films of section 4: UA_eff / A per phase, from the process film on the bare tube in series with the air film on the fins and the air's capacity rate. This needs no new kernel and no new unknown: the W1 march is exact against a flat wall. The compile attaches it to the side's passage (`HeatUnit.heatSide`). An exchanger's side gets none until W3.
- **Fans.** The control record's `share` is the manual setting of the side's actuator: an exchanger side's bypass, an air cooler's fans (1 running, 0 standing still: natural draught, a quarter of the air and its film). `Mode.BYPASS` duplicated "no controller with a manual share" and is removed (no save held it). A new air cooler's fans run. The screen switches them like a mover's power, then Apply. Fan power, 1 kW per m² of face at full speed and the cube of the speed below it, is a datasheet and screen line only. It heats the air, not the stream, so it is not a fluid energy term.
- **Screen.** A side shows its inlet and outlet temperature, the duty from the interval's `HEAT` boundaries, and an air cooler's fans and fan power (`FluidView.Heat`).
- **State format 24.** The checkpoint carries a connection's `HeatSide`, so a restored island moves the heat its graph did (format 23 dropped it).
- **Holdup lag.** The outlet junction holds half the tubes' liquid, so the outlet settles over about the tubes' residence time (a 3 × 6 m bay of water at 13.5 kg/s: 180 kg, a lag of 14 s). That is the physics of the tube contents.
- **Found in passing.** The menu edit rebuilt the device without a rack lane's width and face points, so a pipe laid on a rack could not change its diameter from its menu ("Invalid rack lane"). The edit now changes only what the menu edits (withers).

Final lane before the merge: `test slowGates`, chain-100 exact, fluid GameTests, the campaign. Worlds without exchangers must not move.

## 11. Simplifications and what they change

| Simplification | Effect in numbers | Deletes | Risk |
|---|---|---|---|
| A film per phase (2000, 5000, 100 W/m²K) | pairs land inside or near design U; water films low (real 5000+), organic condensers and kettles 1.5 to 2 times high | film correlations (velocity, properties, geometry) | a fluid-by-fluid table later |
| The film on the curve, by vapour fraction | a stream that starts to condense gets the condensing film where it does; desuperheating on a wet wall keeps the gas film (it condenses on a wall below the dew point in reality) | a slice-start phase, a film mode | a desuperheating condenser weak in its first cells (not measured; W1 can compare) |
| Counterflow for the drawn 1-2 shell (Q3) | duty +8 %, +20 %, +42 % at NTU 1, 2, 5 (Cr 1); the example +21 % | the 4 × 2 cell arrangement | large shells cross temperatures they could not |
| No fluid held inside the passages, no wall heat (Q10) | outlets follow inlets at once; a real exchanger lags 36 s (liquid/liquid) to 6 min (gas/gas) on its steel | passage inventory nodes, the wall state, the slice mean, the wall clamp, its stability limit, the initial wall | an inlet step reaches the other side one slice late and gives back once: 0 to 15.5 K on an outlet for one slice, 24 K after a 5× flow cut |
| The quasi-steady wall as the profile | exact at steady state; within 1 K in two slices at equal slices | the cell-local against joint wall forms | none at steady state |
| Air at a constant 25 °C (Q11) | a cooler on an 85 °C stream moves 25 % less heat in 40 °C air and 33 % more in 5 °C air | weather, biome and altitude reads | none; a biome temperature can come later |
| A fired heater without fuel (Q11) | the duty is free: no fuel stream, flue gas or stack loss | the combustion side | none; another scope adds the fuel |
| Inlet-stream hydraulics through a condensing or boiling side | the ΔP of a condensing side reads the vapour's properties | an axial pressure profile | the 2026-09-24 study: a correction is not reliable |
| Internal bypass neutral to the hydraulics | a side's ΔP does not change with β | a bypass line in the network | none; the assembly's bypass (W8) is the real line |

## 12. Owner questions

Answered on 2026-10-27: Q1 to Q14 (section 3).

Decided in revision 3 without a question (reversible; the review gives the numbers): the give-back split by slice heat, chosen among three forms; the controller without the give-back in its target.

- **Q14 Order after the review** (answered 2026-10-27: yes). The air cooler (W5) and the fired heater (W6) have one side and a known partner, so they need no partner read. Order: W1, W2, W5, W6, W3a, W3, W4, W7, W8, W9.
- **Q15 The curve's extrapolation notice** (W5, open). Section 4 asks for a status-line notice when a side's march leaves its curve's span. The span is a sampling window (the side's inlet and wall temperatures, 10 K beyond each) that the next step samples again around the new state. The property model's own data-range notices still report every node state that leaves a data range. An air cooler's march approaches its flat wall and never leaves the span. Recommended: no notice for the curve's span; W3 measures how often a two-sided wall leaves it.
- **After W3a**: the coupling's timing form, A, B or C (section 5.2), with the study's numbers.

## 13. Risks

- **Phase change at the outlet.** Heat that boils or condenses a stream does it at the side's outlet junction, as a letdown valve's flash does. It goes through the same arriving-phase and flash seeds that held islands in the past (OD4, the cold and bundled seeds). W1 adds a junction-boil gate, and the campaign counts holds in worlds with exchangers.
- **The give-back slice.** For one slice after a change, an outlet moves by up to 15.5 K (24 K after a 5× flow cut, 77.5 K after a hold), within the inlet temperatures. A stream near its bubble point can flash for that slice; it goes through the outlet seeds above, and W3's gates watch it.
- **The fired heater inside a slice.** Its outlet never passes the set point (h_in + β (h_set − h_in)). A duty-limited heater exceeds its duty in proportion when its flow rises inside a slice, and a stream that comes to arrive above the set point inside a slice is cooled towards it. Both last until the next slice sets β again; the ledger books what happened.
- **The quasi-steady wall on curves.** The study marched streams of constant capacity; W3 solves the 8-cell steady wall on two phase-change curves, a fixed point with a root per cell inside each sweep. Its convergence needs a gate (a condensing mixture against water), a tolerance (0.01 K) and a sweep cap. Where the iteration stops short, the profile is still a valid wall: energy stays exact, and only the transient changes.
- **Determinism.** Forms A and B of section 5.2 make a side's result depend on how far its partner had committed at the dispatch, which depends on job times, within one partner slice. Form C does not. W3a measures the size of that dependence.
- **A missed wake** leaves a certified island at a stale duty until its certificate horizon.
- **Cost.**
  - One curve per side per slice (17 to 30 flashes, of the order of milliseconds).
  - The quasi-steady wall: tens to hundreds of sweeps, each with a root per cell (about 40 kernel calls): from milliseconds to tens of milliseconds per side per slice, not measured. W3 adds a cost gate.
  - The kernel costs microseconds per residual.
  - The assembly adds two junctions and a regulating valve per controlled side.
- **The column's operation** (next stage of the multi-machine framework) may want the column's computed duties to drive these exchangers, or the exchangers to bound the column. W9 keeps the duty mode as the hook; the choice belongs to that stage.
