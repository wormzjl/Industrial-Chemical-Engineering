# Heat exchanger system: plan

Batch `2026-10-27-heat-exchanger-design`. Base `995dbd9c` (main, 0.15.0). Status: Planned 2026-10-27. This is the design for the owner to validate; nothing is implemented. Evidence for the coupling and the cell model: [HX_COUPLING_STUDY_REVIEW.md](HX_COUPLING_STUDY_REVIEW.md) (harnesses in `tools/heat-exchanger-design/`). Owner questions: section 11.

Owner request 2026-10-27:

1. Every piece of equipment has a UA that scales with its size. Take U constant at a reasonable value.
2. Built-in control, so that exchangers do not add convergence trouble to the fluid solver: a fixed input ratio through an internal control valve, a target hot or cold side temperature, or what else?
3. Input and output buffers that decouple the fluid networks on both sides, with the pressure relayed through a calculated pressure drop? A lag in the response is acceptable.

Short answers:

1. U = 500 W/m²K on the tube surface the datasheet already shows (75 D² L m²). Section 4.
2. A controller that acts between slices, never inside the Newton: outlet temperature by bypass (the default), duty, or a manual bypass share. Not the flow ratio. Section 6.
3. Decouple the heat, not the mass. Each side stays a pass-through in its own island with exact hydraulics; the two sides exchange only energy, through the exchanger's wall, with a lag of one slice. Material buffers with a pressure relay are possible but turn the exchanger into a pump station. Section 5.

## 1. Expected behaviour (the plant first)

A shell-and-tube exchanger passes two streams that never mix. Each side is a flow passage: the network sets its flow and the passage takes a pressure drop, as a pipe does (30 to 70 kPa for a liquid at design velocity). Heat flows through the tube walls from the hot side to the cold side, q = U (T_hot − T_cold) per m².

- **U** (overall, W/m²K, typical design ranges, Sinnott, *Chemical Engineering Design*, table 12.1): water/water 800-1500; light oil/water 350-900; light oil/light oil 100-400; steam on organics 500-1000; organic vapour condensing on water 700-1000; gas/water 20-300; gas/gas 10-50.
- **Steady state**: Q = ε C_min (T_hot,in − T_cold,in), NTU = UA / C_min, C = ṁ c_p. Counterflow at balanced flows (Cr = 1): ε = 0.50, 0.67, 0.83 at NTU 1, 2, 5. A condensing or boiling side (Cr = 0): 0.63, 0.86, 0.99.
- **The drawn exchanger is a 1-2 shell** (TEMA AES, one shell pass, two tube passes; `ProcessUnit.exchanger`). At Cr = 1 it reaches 0.46, 0.56, 0.59 at NTU 1, 2, 5: a large 1-2 shell cannot cross temperatures much. Treating it as counterflow overstates its duty by 8 %, 20 % and 42 % there.
- **Phase change**: a condensing or boiling stream holds its temperature while it takes latent heat. A constant c_p fails there; the stream's enthalpy-temperature curve handles it.
- **Dynamics**: the steel and the fluid inside store heat. Example below: steel 5.4 MJ/K, water inside 16 MJ/K. An outlet follows an inlet step within C/UA = 24 s (steel only) to about 100 s (with the water). Real exchangers respond in tens of seconds to minutes.
- **Control in real plants**: a utility exchanger throttles its utility (cooling water, steam) on the process outlet temperature; a process/process exchanger moves a bypass (a three-way valve) on one side; an air cooler switches fans, changes their speed or pitch, or closes louvres. Ratio control of the two flows is rare on an exchanger: it fixes Cr, not a temperature.

**Example used throughout**: shell 1 m, tubes 6 m: 75 × 1² × 6 = 450 m², UA = 225 kW/K at U 500. Water 50 kg/s each side (C = 209 kW/K, NTU 1.08), hot in 150 °C, cold in 30 °C. As the drawn 1-2 shell: ε 0.48, Q 11.9 MW, hot out 93 °C, cold out 87 °C; a hot inlet step to 180 °C gives 108.6 and 101.4 °C. (As counterflow: ε 0.52, Q 13.0 MW, hot out 88 °C, cold out 92 °C.)

## 2. What the code does today

- The exchanger, kettle, air cooler and both fired heaters are drawn only; nothing registers them in the fluid network. Their datasheets already state the tube surface (`ProcessUnit.tubeSurface` = 75 D² L), the air cooler bay's face (`bundleFace`) and the fired heaters' duty (`heaterDuty`).
- The network has no heat transfer. A pipe owns no fluid. The one energy a connection adds is a mover's shaft work: `PassiveStepSolver.Equations.nodeAccumulate` adds it to the receiving node's energy row, `ConservativeTransport` books it (`pumpWork`) and `checkConservation` audits it. Exchanger heat goes the same three ways.
- The pipe thermal-loss study (batch `2026-09-24-pipe-thermal-loss`, concluded, not merged) built the heat law needed here: a stream relaxing towards a wall temperature along its enthalpy-temperature curve (`PipeHeatExchange`, patch in `tools/pipe-thermal-loss/`). Exactly zero heat at zero flow; latent heat exact. A fixed 17-point curve stayed within 7.1 % of a 513-knot reference over 561 cases, condensation included; an adaptive 9 to 30-point curve within 0.05 %. It also found that re-solving the pressure drop with the cooled properties is not reliable, so the hydraulics keep the inlet stream's properties.
- Equipment as island separators with built-in buffers was the 2026-09-15 engine design. It was built as the causal module system (buffered transfers, pending deliveries, time fences) and removed on 2026-10-15 (`944a79f6e`, consolidation item F1), because nothing in the game produced a module.
- Islands advance in slices of their own adaptive cadence (20 to 400 ticks), commit on the server thread, and certify STEADY when their intervals repeat.

## 3. Three decisions

1. UA from size (section 4).
2. How the two sides couple (section 5): A one island, B a wall account between islands (recommended), C the owner's material buffers.
3. Control (section 6).

## 4. UA from size, U constant

| Kind | Surface or rule | U | UA over the size range |
|---|---|---|---|
| EXCHANGER | A = 75 D² L, shell 0.5-2.5 m, tubes 2-12 m: 37.5 to 5625 m² | 500 W/m²K | 19 kW/K to 2.8 MW/K |
| KETTLE | A = 75 D_b² L, bundle 0.5-2 m, tubes 2-10 m: 37.5 to 3000 m² | 500 W/m²K | 19 kW/K to 1.5 MW/K |
| AIR_COOLER | per bay, bare tube 5 m² per m² of face (four rows of 1" finned tubes): face 2.0 m² (1.5 × 3 m bay) to 12.2 m² (3 × 6 m) | 500 W/m²K on the bare tube: 2.5 kW/K per m² of face | per bay 5 to 31 kW/K; see the air below |
| VERTICAL_HEATER, CABIN_HEATER | the datasheet duty (`heaterDuty`) caps the firing | none | W7, optional |

- **Why 500 W/m²K**: the middle of liquid hydrocarbon coolers, steam heaters and organic condensers, and the value the standalone-units plan sized the exchanger with (D 1.25 m × 6 m condenses 7 MW at 20 K).
- **Air cooler**: the fans move air at a 3 m/s face velocity, 3.5 kW/K per m² of face. Every tube cell meets fresh air, so the process stream sees ambient air through UA_eff = C_air (1 − e^(−UA/C_air)) = 1.8 kW/K per m² of face. The largest bay: 22 kW/K, 1.3 MW for a process at 85 °C in 25 °C air (the standalone-units plan: about 1.5 MW). Fans off: natural draught, C_air and UA to a quarter (W6).
- **Wall thermal mass** (two-sided exchangers only, section 5): steel 12 kJ/K per m² of surface (3/4" BWG 14 tubes, 14.7 kg/m², × 1.6 for shell, tube sheets and channel). The fluid inside is not counted: the passages hold no inventory. C/UA = 24 s and C/(4 UA) = 6 s at U 500, the same for every size.

What a constant U does, in numbers:

- **Gas service** (real U 20-300): too conductive by 2 to 25 times. A gas cooler sized for a real NTU of 1 (ε 0.63 at Cr 0) reaches NTU 5 (ε 0.99): 57 % more duty. A small exchanger does the work of a large one.
- **Water/water** (real U 1000-1500): at a game NTU of 1 the duty is 25 to 33 % low.
- **Option U2** (owner): 1/U = 1/h_hot + 1/h_cold, with a film coefficient per phase of the side at the slice start (liquid 2000, condensing or boiling 5000, gas 100 W/m²K). One small table; gas exchangers become realistic. Recommendation: constant U first, as asked; U2 later if gas coolers look too strong in play.

## 5. Coupling the two sides

### 5.1 Option A: one island

The exchanger joins both sides into one island; the heat law reads both sides' current states in the same Newton.

- For: exact, no lag, no new state; the least science code.
- Against: heat integration merges plants. A crude preheat train of 6 to 8 exchangers against the pumparounds and products joins the feed line, three pumparound loops and three product lines: about seven networks become one Newton. A hold anywhere holds all of them. The Newton and its LU fill grow with the sum. A phase change on one side moves the other side's duty inside the same pass loop. A feed/effluent exchanger closes an energy loop inside the Newton (a real reactor loop can have several steady states).

### 5.2 Option B: heat law in each island, wall account between them (recommended)

Physics: the hot fluid heats the tube wall and the wall heats the cold fluid. The wall stores heat, so each side feels the other through it, tens of seconds late. B keeps exactly that: the islands exchange only energy, through the wall, never mass or pressure.

**Each side is a connection in its own island**, between its inlet and outlet nozzles (`PortMarkings` already marks them IN and OUT):

- **Hydraulics**: a passage law like a pipe's, for parallel channels, on the inlet stream's properties. Tube side: 630 D² tubes per pass of 14.8 mm bore, length 2 L, plus 4 velocity heads per pass for entry, return and exit (water at 1.5 m/s in the example: about 35 kPa). Shell side: an equivalent passage of 18 mm hydraulic diameter and 0.1 D² crossflow area, one shell diameter per baffle crossing with baffles 0.4 D apart (length 2.5 L). Pressure is not relayed: the island's own solve passes it through the side, as through a pipe.
- **Heat**: the stream passes the exchanger's wall cells in its own order and relaxes towards each cell's temperature T_k over the cell's share of UA_side = 2 UA (equal films): dh/d(UA) = −(T(h) − T_k) / ṁ, integrated exactly on the side's enthalpy-temperature curve (the 2026-09-24 kernel). The exchanger has 8 wall cells in the drawn 1-2 arrangement: 4 positions along the shell × 2 tube passes. The tubes pass positions 1→4 on pass 1 and 4→1 on pass 2; the shell passes 4→1 and meets both passes' cells at each position. This matches the exact 1-2 formula within 2.3 % (review).
- **Fixed for the slice**: the curve (built on the worker at the slice start from the donor's composition and pressure, at both ends for a reversal), the wall profile T_1..T_8 and the bypass share β (section 6). Inside the Newton the heat is a pure function of the connection's flow and the donor's enthalpy: no new unknown, no new mode, no switch inside a residual. It is C1 in the flow, exactly 0 at no flow, and bounded by the approach to the wall: |Q| ≤ |ṁ (h_in − h(T_wall))|.
- **Where it enters**: as a mover's shaft work: the receiving node's energy row, the reconstruction (a heat term per side beside `pumpWork`) and the audit. Every reader of a delivered enthalpy takes it too: the balanced start (`BalanceNet.seeded`, `mixed`), the arriving-phase seeds (`arrivingPhases`), the rate solve and the gauges' pipe observation.

**Utility exchangers** (one side piped): the wall profile is the utility's temperature, constant. One island, no account, no lag, an exact steady state. Utilities: cooling water 30 °C; LP, MP and HP steam condensing at 148, 184 and 252 °C; refrigerant −40 °C; air (air cooler) at the ambient 25 °C through UA_eff. The screen reports what the utility delivered: steam kg/h from the duty and the latent heat, cooling water m³/h at a 10 K rise, fan kW.

**Two-sided exchangers** (both sides piped): the 8 wall cells are the exchanger's own state, a heat account. Each side books into it the heat it gave or took over each committed slice, so energy is exact by construction. A side's slice reads a wall profile fixed for the slice:

1. **As of its start tick.** The other side's heat booked past that tick is taken out; its heat not yet booked up to the tick is added at its last rate, for at most one of its slices (after that a lagging side counts as stopped). A certified other side is first materialised to the tick (its replay books its certified heat).
2. **The slice mean.** Each cell relaxes towards T_eq = (G_s θ_s + G_o θ_o) / (G_s + G_o) under both sides' conductances G (own: from its committed state; other: from its last commit) and stream temperatures θ. The profile is the cell's mean over the slice: T̄ = T_eq + (T_k − T_eq)(1 − e^(−x)) / x, x = (G_s + G_o) Δt / C_cell.
3. **Clamped** between the two inlet temperatures. The account itself may stand outside them for a moment while heat is in transit; no side ever reads that.

The same rule serves an exchanger whose sides share one island, such as a feed/effluent loop: the loop closes across slices, not inside the Newton.

**Wake.** When a commit moves the profile that the other side would read by more than 0.05 K from the one its certificate holds, that certificate is discarded (`inputsChanged`). A side with no flow takes no heat and needs no wake.

**Threads.** The command carries the as-of account and the other side's record (arithmetic, server thread). The worker builds the curve and the profile and runs the controller. The commit books the heats (arithmetic, server thread). No process calculation runs on the tick.

**Evidence** (review; steel-only wall, the harder case): with slices of 1, 5 and 20 s, equal or not (5/20, 20/5), every outlet reached the exact 8-cell steady state (0.00 K over the last 100 s) and energy closed to 1e-4 J. After a 30 K inlet step the outlets trailed the coupled reference by at most 1.3 to 2.6 K with 5 s slices, and by up to 10 K when one side ran 20 s slices. Without the as-of read the outlets settled 1.5 to 52 K off the steady state; without the slice mean the frozen wall diverged at 20 s slices. (The study's cells are counterflow; the arrangement does not change the coupling.)

### 5.3 Option C: material buffers with a pressure relay (owner's proposal)

Each side gets an inlet buffer (a vessel in the upstream island) and an outlet buffer (a vessel in the downstream island). Once per slice the exchanger moves the inlet buffer's material to the outlet buffer, heated or cooled by a standalone calculation outside every Newton. The outlet buffer stands at the inlet pressure less the calculated pressure drop.

Physics: a real exchanger has no buffers; pressure and flow pass through it at once. Buffers turn it into a pump station: two drums and a transfer between them. In numbers (50 kg/s of water, 5 s cadence, buffers of 3 to 5 cadences of residence, the sizing rule of the 2026-09-15 design):

- Four buffers of 0.75 to 1.25 m³ per exchanger. Each side gains 30 to 50 s of transport delay; six exchangers in series on a crude line, 3 to 5 minutes.
- The network no longer sets the flow; a transfer rule does (pass what arrives: a level controller on the inlet buffer). A valve closed downstream fills the outlet buffer first (15 to 25 s), then the inlet buffer, and only then does the upstream pump see it. Small buffers hunt; the 2026-09-15 design warned of stop/start cycling.
- The relay is circular. The outlet buffer's pressure is the inlet buffer's less ΔP, and the inlet buffer's pressure must come from somewhere: a buffer below capacity stands at the ambient 101 325 Pa in this model (the wall row's slack branch), and a full liquid buffer moves 1 bar per 46 mL in 1 m³ of water. Both buffers need a new pressure-held wall law, and one of them a pressure anchor that real plants do not have.
- Up to four islands per exchanger, with material in transit between them: the cross-island ledger removed on 2026-10-15 (pending deliveries, reservations, time fences, exactly-once delivery) returns.
- For: no heat and no phase change inside any Newton; the product arrives in a vessel, which flashes robustly.

### 5.4 Comparison

| | A: one island | B: wall account | C: buffers and relay |
|---|---|---|---|
| Hydraulics through the exchanger | exact | exact | a transfer; pressure relayed one slice late |
| Heat coupling | exact, no lag | one slice late plus the wall (6 to 24 s); exact steady state | one transfer plus the buffers' residence (30 to 50 s a side) |
| Energy | exact | exact (account) | exact (ledger) |
| Islands | merged across heat integration | unchanged | up to four more per exchanger |
| New inside the Newton | the heat law across both sides; feed/effluent loops | a bounded heat term per side: no unknown, no mode | nothing |
| Phase change caused by heat | inside the coupled Newton | at the side's outlet junction or vessel | in the outlet buffer |
| A hold on one side | holds both | the other side runs on; the held one counts as stopped after one slice | the buffers absorb it, then the transfer stops |
| New infrastructure | a thermal join in the topology | exchanger device, account store, as-of read, wake | cross-island ledger, pressure-held buffers, transfer control |

Recommendation: **B**, utility exchangers first (one island, no account), two-sided ones next. C fits a unit meant to be a hydraulic break (a buffered heater); it is not a heat exchanger.

## 6. Built-in control

Where the convergence risk is, and what removes it:

1. Cross-side coupling inside a Newton: B removes it.
2. Controller modes inside a Newton (regulating and saturated active sets, as the control valves have): a controller that acts between slices has none.
3. Phase changes the heat causes at the outlet: this is physics. A target outlet state keeps a stream away from a boundary the player did not ask for; nothing removes it in general (section 12).

| Option | It holds | Verdict |
|---|---|---|
| Flow ratio through an internal control valve (owner) | cold flow = r × hot flow | Not built in. It holds Cr, not a temperature, throttles a line (one more valve mode in the Newton) and needs the other island's flow, one slice late. It is a general ratio station: process control, as a flow valve whose target follows another flowmeter (with the cable contract of [DATA_SYSTEM_CONTRACT.md](../2026-10-26-process-control/DATA_SYSTEM_CONTRACT.md)). |
| Manual bypass share | a share β of one side passes outside | Built in. |
| Outlet temperature by bypass (owner) | the hot or cold outlet at T_set | Built in; the default for process/process. |
| Duty | Q_set, at most what the exchanger can do | Built in. Well posed through phase change; the kettle's and the fired heater's natural mode; the hook for a column that takes over its exchangers (multi-machine request, item 4). |
| Utility valve, fan speed, firing | the outlet temperature or the duty | The same mechanism, named after the unit. |
| Low limit (freeze guard) | the cooled outlet stays above T_min | Option: a limit, not a target, against ice, CO2 crystals and wax downstream. |

**Mechanism.** At each slice start of the controlled side's island, on the worker: β* = the bypass share whose mixed outlet meets the target (or whose heat meets the duty), found by bisection on the side's own heat law against the slice's profile at the committed inlet state and flow; the response is monotone in β. The controller sets β = β* (dead-beat). β stays fixed for the slice. It scales the flow that passes the cells, (1 − β) ṁ, and the rest joins at the outlet. The hydraulics do not see β: the three-way valve keeps the side's resistance. When β* falls outside 0 to 1 the controller stops at the limit, and the status reads "limited".

**A target temperature becomes a target enthalpy** at the outlet pressure and the composition of the slice start, so a target on a boiling or condensing plateau stays well posed.

**Evidence** (review): dead-beat held the cold outlet at 90 °C within 0.1 K 24 s after a 30 K hot-inlet step at 5 s slices (worst deviation 0.3 K), 79 s at 20 s slices (1.4 K). A 0.5 damping was slower and strayed further (3.3 K and 7.8 K).

**Not recommended: ideal regulation inside the Newton** (as `ControlValve` does). No lag, but a regulating or saturated active set per exchanger, and a temperature target on a plateau has no unique duty there.

## 7. Player view

- **Build**: the constructor, as today. A complete exchanger, kettle or air cooler registers one fluid device per side, as a constructed mover does: an inlet and an outlet port at the side's nozzles (W2).
- **Process or utility**: a side with a pipe at both nozzles is a process side. An exchanger with one process side takes the utility chosen on its screen for the other side. A new build defaults to cooling water (exchanger), LP steam (kettle) or air (air cooler); an exchanger built before this batch takes none (no heat) until the player chooses.
- **Kettle**: its shell is a vessel (a reservoir with FEED, VAPOUR_OUTLET and LIQUID_OUTLET ports, like a drum), and the heat enters the vessel's energy row (W5). The heating medium in the tubes is a side or a utility.
- **Screen** (engine-owned: replay on open, updates at the island's bucket): each side's inlet and outlet temperature, pressure, flow and phase; duty, UA, NTU, effectiveness, approach; the wall profile (as of the side that is behind); the control (mode, side, target, β, limited); the utility delivered. Notices: "other side held: counted as stopped", "control limited". The drawing-only datasheet stays a page.

## 8. Saves (AGENTS.md "Saves and data formats")

- **Facts** (append-only; fluid facts schema 4 → 5): the exchanger's registration (kind by name, sides and ports, utility by name, control mode and side by name, target); the wall account's cell energies, which are energy the world holds. A missing field reads its old behaviour: no utility, control off. Corpus image under `src/test/resources/compat/fluid/schema-5/`.
- **State** (rebuilt freely): the per-side records and history for the as-of read, β, the curves.
- A removed exchanger takes its account's energy with it, booked as a removed vessel's contents are.

## 9. Work packages and gates

| Package | Content | Gates |
|---|---|---|
| W1 Heat law | `PipeHeatExchange` back from `tools/pipe-thermal-loss/prototype.patch`; the side kernel (cells, order, β); the passage law; the heat in rows, reconstruction, audit, balanced start, seeds, rate solve | kernel vs the analytic exponential and latent knots; zero and reversed flow; the audit with heat; a junction that boils from heat (water at 5 bar heated past 152 °C); nothing moves without exchangers: chain-100 exact, the 33 junction lines |
| W2 Device | registration of sides as connections; the utility choice; facts schema 5 and its corpus; the screen | facts round trip, corpus gate; one in-game check of the screen |
| W3 Control | the controller between slices, its modes and statuses | an outlet held after an inlet step on a test rig |
| W4 Two-sided | account store, as-of read, slice mean, clamp, cap, wake, certificate signature | GameTests: two islands reach the 8-cell steady state; a held partner; a shared-island feed/effluent loop; energy ledger exact |
| W5 Kettle | the pool as a vessel side | boil-up = duty / latent heat at the pool's pressure |
| W6 Air cooler | fans switch, UA_eff, fan power | outlet vs ambient at fans on and off |
| W7 (optional) Fired heaters | firing duty, capped | |
| W8 (column operation stage) | a validated column assembly sets its condenser's, reboiler's and pumparound coolers' duty targets | with the column's operation |

Final lane before the merge: `test slowGates`, chain-100 exact, fluid GameTests, the campaign. Worlds without exchangers must not move.

## 10. Simplifications and what they change

| Simplification | Effect in numbers | Deletes | Risk |
|---|---|---|---|
| U constant at 500 W/m²K | gas exchangers 2 to 25 times too conductive; water/water 25 to 33 % low at NTU 1 | film correlations | gas coolers too small (option U2) |
| No fluid held inside the passages | the wall responds within 6 to 24 s instead of about 100 s with the water inside | passage inventory nodes | none at steady state |
| Equal films (UA_side = 2 UA) | the wall sits halfway; the duty unchanged | per-side film data | the wall's display only |
| 8 cells in the 1-2 arrangement | within 2.3 % of the exact 1-2 formula up to NTU 5 | exact shell integration | none |
| Inlet-stream hydraulics through a condensing or boiling side | the ΔP of a condensing side reads the vapour's properties | an axial pressure profile | the 2026-09-24 study: a correction is not reliable |
| Isothermal, unlimited utilities | the cooling water's rise is ignored: ε +3 % at NTU 2, Cr 0.17 | utility networks | none; a piped utility side is the real thing |
| Bypass neutral to the hydraulics | a side's ΔP does not change with β | a bypass line in the network | none |

## 11. Owner questions

- **Q1 Coupling**: B (recommended), A or C?
- **Q2 U**: constant 500 W/m²K for the exchanger and the kettle, and 2.5 kW/K per m² of face with 3.5 kW/K per m² of air for the air cooler; or option U2 (film per phase)?
- **Q3 Arrangement**: the drawn 1-2 shell (recommended), or counterflow for every exchanger (simpler to explain, up to 42 % more duty)?
- **Q4 Wall mass**: steel only, 12 kJ/K per m² (recommended), or with a nominal liquid inside, 48 kJ/K per m² (slower, about 100 s)?
- **Q5 Utilities**: implicit utilities on an unpiped side (the list in 5.2), or real fluid on both sides always?
- **Q6 Control**: between slices, dead-beat (recommended), with the modes of section 6 and the low limit; or ideal regulation inside the Newton?
- **Q7 Ratio control**: in process control later (recommended), or in the exchanger?
- **Q8 Order**: W1 to W3 (utility exchangers) first, then W4 (two-sided), W5, W6?
- **Q9 Old builds**: an exchanger built before this batch registers when its chunk loads; with one piped side it takes no utility until the player picks one, with two it starts to exchange heat. Agreed?

## 12. Risks

- **Phase change at the outlet.** Heat that boils or condenses a stream does it at the side's outlet junction, as a letdown valve's flash does, through the same arriving-phase and flash seeds that held islands in the past (OD4, the cold and bundled seeds). W1 adds a junction-boil gate, and the campaign counts holds in worlds with exchangers.
- **Determinism.** The as-of read depends on simulated time only while both islands keep up. A side that is behind (debt or a hold) is extrapolated, so its partner's result depends on when it caught up, within the one-slice cap.
- **A missed wake** leaves a certified island at a stale duty until its certificate horizon.
- **Cost.** One curve per side per slice (17 to 30 flashes, of the order of milliseconds); the kernel costs microseconds per residual.
- **The column's operation** (next stage of the multi-machine framework) may want the column's computed duties to drive these exchangers, or the exchangers to bound the column. W8 keeps the duty mode as the hook; the choice belongs to that stage.
