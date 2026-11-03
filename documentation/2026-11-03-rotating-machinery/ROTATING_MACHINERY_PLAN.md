# Rotating machinery: plan

Batch `2026-11-03-rotating-machinery`. Base `744ef4513` (main, 0.19.3). Branch `claude/realistic-pump-compressor-6fdfd9`. Status: Planned, revision 2 (2026-11-03) with the owner's answers to Q1 to Q10 and the revised Create rate (section 13, [DECISION_LOG.md](DECISION_LOG.md)). Implementation starts with W1.

Evidence: [MACHINE_RESEARCH_REVIEW.md](MACHINE_RESEARCH_REVIEW.md) (engineering and Minecraft conventions, with sources). Worked numbers: `tmp/rotating-machinery/examples.js`, `curves.js` and `curves2.js` (they move to `tools/rotating-machinery/` at the cleanup).

Owner request 2026-11-03:

1. A realistic pump and compressor model: research what it takes and whether it makes fluid-network convergence worse.
2. Pump and compressor casings separate from their motors, as structures of their own.
3. Mechanical shafts, drawn with a diameter that scales with the equipment.
4. A motor structure, and a reworked steam turbine (today: too much unnecessary detail, bad scaling), both easy to join to a pump or compressor.
5. Motors and turbines specified by their duty in the equipment design.
6. Electricity (FE/RF) drawn by motors and air coolers through an RF port, with a researched, configurable FE to watt ratio.
7. A creative motor with a set speed and power.
8. A real motor and turbine control system.
9. Support in the process simulator (work in progress).
10. Create's rotation as an optional power source (Create no longer required), a researched, configurable conversion rule, shafts that carry real production speeds, and an industrial gearbox that links them to Create's speeds.

Owner note the same day: "The model shouldn't be too deep into the details, remember it's for chemical engineers." Every model here stays at the level a process simulator shows: a curve, an efficiency, a duty, a speed, a steam rate. Mechanical numbers (shaft diameter, motor frame) only size the drawings.

Short answers:

1. **Convergence.** A pump curve is a smooth law that falls with flow. It replaces today's switch between "hold the set flow" and "hold the head limit", which is where the old pump holds came from. The drivers stay outside the Newton: they set a speed once per slice, as the heat units' controllers do. So the realistic model should not make convergence worse; one change needs care, the compressor's hotter discharge (section 4.4). The 200-seed quick check decides it before anything else is built.
2. **Casings and drivers.** A pump, compressor or turbine casing ends at a shaft coupling. A motor, turbine, creative motor or gearbox joins it through shaft blocks. Old placed pumps and compressors rebuild as casings and wait for a driver (owner D4, section 10).
3. **Shafts.** A shaft block on one axis. Drawn diameter from the train's torque, three times the real one so it shows: 2 to 8 px (section 5.3).
4. **Motor and turbine.** A motor drawn from its rated power (frame grows with the fourth root of the power, as today's drawn motors). One steam turbine kind for mechanical drive: casing, governor and trip valves, bearings, coupling. The generator, gear, exciter, cooler and condenser go (section 6).
5. **Duty.** The constructor asks a motor for its rated power, speed class and drive (fixed or variable speed); a turbine for its rated power, inlet steam and exhaust pressure. A pump asks its rated flow and head, a compressor its rated flow and head or ratio.
6. **Electricity.** An RF port cell (a terminal box) on every powered structure. The engine books the energy each slice. Default 2.5 J per FE (Mekanism's joule), tied to Create's stress so that FE and stress units trade at one rate (owner): a 10 to 100 kW motor draws 200 to 2,000 FE/t; 1 to 10 MW (20,000 to 200,000 FE/t) is late game. Configurable; section 7.
7. **Creative motor.** A block with a set speed and a power limit.
8. **Control.** Start and stop, a speed set point or a regulated flow or pressure for variable-speed drives, protection trips (overload, no power, no steam). Section 9.
9. **Simulator.** The same Minecraft-free machine classes serve the simulator's units; the work lands after both batches merge (section 11, Q9).
10. **Create.** Optional dependency. A Create coupling block brings Create rotation into an industrial train; the gearbox raises its speed up to 64 times. One FE per tick trades for 34.13 stress units (su), the rate of Create's electricity addons, so 1 su = 1.46 W at 2.5 J per FE: the water wheel's 256 su is 0.38 kW, the large wheel's 512 su 0.75 kW, the middle of real stream wheels of their sizes. Any round trip between FE and Create returns only its efficiencies (section 8).

## 1. Expected behaviour (the plant first)

### 1.1 A centrifugal pump on its line

A process pump rated 100 m³/h at 80 m, hydrocarbon at 800 kg/m³, 2-pole motor (2950 rpm), 72 % at its best efficiency point:

- Pressure rise 628 kPa. Hydraulic power 17.4 kW, shaft power 24.2 kW. Motor 30 kW (the next standard size above 24.2 × 1.2, the owner's 20 % spare).
- The curve rises continuously to shutoff: 97 m (121 %) at no flow, 63 m at 150 % flow. API 610 asks for a curve that rises to shutoff, by at least 10 %. Shaft power at shutoff 8.5 kW (35 %); trapped liquid then takes all of it as heat, so pumps have a minimum flow (about 25 to 40 % of the best efficiency flow).
- On a line with 30 m static head it runs at its rated point at full speed. To hold 70 % flow, a variable-speed drive turns it at 81 % speed; at 90 % speed it gives 85 % flow.
- Behind a closed valve it holds 97 m of head and moves nothing.
- Flow is set by the line: the pump finds the point where its curve meets the line's resistance. A flow set point needs a variable-speed drive or a control valve.

### 1.2 A compressor

A centrifugal compressor on natural gas (M = 18, k = 1.27) from 1 bar and 300 K to 3 bar, 3.5 kg/s (4.85 m³/s at suction), polytropic efficiency 0.78:

- Polytropic head 177.5 kJ/kg (five or six impellers at 30 to 40 kJ/kg each), gas power 796 kW, discharge 405 K (+105 K).
- Today's model books the ideal isothermal work: 533 kW and +70 K. It gives the gas a third less energy than a real machine, and a discharge 35 K too cold.
- Speed about 12,600 rpm (impeller 0.45 m at a 300 m/s tip). A 2-pole motor (2985 rpm) drives it through a 4.2:1 step-up gear; the motor is 1 MW.
- Near its peak head, about 75 % of rated flow at a given speed, the machine surges. Plants recycle cooled discharge gas to the suction to keep the machine's own flow 10 to 15 % above that line.

A reciprocating compressor delivers its displacement whatever the discharge pressure, less the re-expansion of its clearance gas (volumetric efficiency 0.84 to 0.94 at ratios 2 to 4 with 8 % clearance). A relief valve protects each stage.

### 1.3 Drivers and the shaft

- **Motor.** Speed fixed by its poles: 2950, 1475 or 985 rpm at 50 Hz (slip changes flow by less than 1 %). Efficiency 93 % at 30 kW, about 96 % at 1 MW (IE3). A variable-speed drive turns it from about 10 to 100 % speed and costs about 3 % efficiency.
- **Steam turbine.** A mechanical-drive turbine from 40 bar and 400 °C to 4 bar has an isentropic drop of 528 kJ/kg. At 65 % efficiency it uses 10.5 kg of steam per kWh (single-stage machines about 15, multistage about 9): 0.07 kg/s for the 24 kW pump. Its governor holds the set speed while it has steam; with too little steam, the speed falls until the load matches. Its overspeed trip acts at 110 %.
- **Gearbox.** A speed ratio and about 98 % efficiency.
- **Shaft.** Carries power at the driver's speed. Torque is power over speed: the 24 kW pump shaft carries 78 N·m at 2950 rpm, a 1 MW motor 3.2 kN·m. Their diameters from torque are 22 mm and 74 mm: less than 2 px on a 16 px block.

### 1.4 Electricity and Create

- A motor draws its shaft power over its efficiency: the 24 kW pump takes 26 kW, the compressor's 830 kW shaft 865 kW.
- Other mods' machines draw 20 to 400 FE/t (Mekanism's crusher 20, digital miner 400); generators give 20 to 200 FE/t early, a few thousand mid game, 10⁵ and more late.
- Create's stress unit behaves as power: a network's capacity is Σ capacity × rpm, its load Σ impact × rpm. Gearing changes speed, not power. An overstressed network stops whole. Create runs at most 256 rpm (its config; faster breaks blocks).
- Create's water wheel has paddles about 1 block from its axis (a 2 m wheel) and turns when water flows past its rim: a stream (undershot) wheel. A real 2 m stream wheel gives 0.2 to 0.6 kW (measured: 219 W at 0.31 m³/s, 0.6 kW in a 1.2 m/s channel). The large wheel fills 3 × 3 blocks (about 4 to 5 m); a real one in a stream gives 0.6 to 1.4 kW, several kW only when fed from above. Create gives them 256 and 512 su.

## 2. What the code does today

**Fluid solver** (`PassiveStepSolver`, `FlowControl`, code guide `fluid-science.md`):

- A mover has two unknowns, a flow and a head, and one row per mode: `PUMP_TARGET` (suction volume flow = set flow), `PUMP_HEAD_LIMIT` (head = rise limit), `PUMP_SUCTION_LIMIT` (suction pressure = vapour pressure), `CLOSED`, `INLET_WRONG_PHASE`. The pass loop switches modes one at a time and carries the limits from slice to slice.
- Rise limit: pump `maxAddedPressure × ρ_s / 997.05` (a head in metres of the reference water); compressor `(r_max − 1) P_s`.
- Rules that exist for this switch: R1 to R4 of the pump-shutoff fix, the sealed-pocket relief `yieldPrescribed`, the `atShutoff` latch, the own-end dead head, the head-limit start flow, `carriedMoverLimit`, the filter cake law suspended at target.
- Energy: no efficiency. A pump books `q × head`; a compressor the isothermal work `q (P_s/ρ_s) ln(1 + head/P_s)`. Work is clipped at zero: nothing can take work out of the fluid.
- Controls: set flow (≤ 10 m³/s), added pressure (≤ 2 MPa) or ratio (1.01 to 10), and a power switch on constructed movers. Defaults: pump 0.01 m³/s and 500 kPa, compressor 0.05 m³/s and ratio 3.
- The machinery we can reuse: a flow valve regulates inside the solve with its target kept out of the line's identity (a new target keeps the warm start). `couple` sets a valve's target once per slice from a partner island's read. Heat units couple two islands with a fence, a read at dispatch and a booking at commit, one slice late.

**Equipment** (`ProcessUnit`, `*Design`):

- The pump (OH2 up to 0.4 m discharge, BB1 above), the reciprocating compressor and the centrifugal compressor each draw their motor (and the centrifugal compressor its step-up gear and lube console) in the same structure. The pump's size is its discharge only; its motor is drawn for a fixed 100 m head at 70 %.
- No size reaches the fluid network: "dimensions impose no hydraulic limits". A 0.05 m pump moves 10 m³/s if told to.
- Both steam turbines draw only. Power and speed depend on the casing diameter alone. The gear, generator and exciter take 48 to 70 % of the train's length.
- Moving parts turn at fixed shown speeds (pump 2 rev/s, fan 1.5, crank 1); the in-line movers do not move.

**Power**: no energy is consumed anywhere. The air cooler shows its fan power (1 kW per m² of face at full fans) with the hint "The network has no electricity to draw it from yet".

**Create**: required in `neoforge.mods.toml`, but no production class imports Create. Create's bundled Catnip `Outliner` draws the constructor ghost and the laying tool's outline (`EquipmentGhost`, `PipeLayingClient`); two block models use Create textures; one GameTest uses `BlockMovementChecks`. KubeJS is also required and used nowhere.

**Simulator branch** (`claude/in-game-process-simulator-93dd9a`, not merged): pump and compressor tiles use the same isothermal work without efficiency; the steam turbine tile is drawn only; no drivers.

## 3. Machine laws in the fluid network

Every law is one smooth row that falls with flow, written on the same two unknowns a mover has today (flow and head). Speed `s` (share of rated speed) is a number fixed for the slice. Surge, minimum flow, the NPSH margin, overload and overspeed are notices on the status line or trips between slices, never holds.

### 3.1 Centrifugal pump

`H(Q, s) = H_r (1.21 s² − 0.05 q s − 0.15 q|q| − 0.01 q)` with `q = Q/Q_r`, Q the suction volume flow. The pressure rise is `ρ_s g H`, so the density scaling the rise limit has today stays.

- Falls with flow at every speed: slope ≤ −0.01 H_r/Q_r, also at 10 % speed. That is the condition for one solution in any network of rising resistances (Wetter 2013); EPANET and Modelica Buildings add such a linear term for the same reason. Runout at 2.65 Q_r.
- Shaft power `P = P_r (0.35 s³ + 0.65 q s²)`: finite everywhere (no division by an efficiency that is zero at shutoff), best efficiency at the rated point, 85 % of the best at half flow, 89 % at 150 %.
- The fluid gets the ideal work, as today. The losses would warm the liquid by 0.15 K at the rated point (simplification S1).
- Cavitation keeps today's suction limit (`PUMP_SUCTION_LIMIT`, vapour pressure floor). The NPSH margin is shown, not modelled (S2).
- The machine is one-way: a reverse drive closes it, as the discharge check valve on every real pump does (today's reverse closure).
- Off or tripped: `CLOSED`, as today.

### 3.2 Centrifugal compressor

Head against suction volume flow, per speed line: `H_p/H_r = s² (0.645 + 1.244 x − 0.889 x²)` with `x = q/s`. Surge at `x = 0.75`: below it, the anti-surge recycle keeps the machine's own flow at the surge line. The net law continues as a straight line with the curve's slope there (−0.089), so it still falls and stays smooth.

- Pressure ratio from the polytropic head: `r = (1 + H_p ρ_s / (σ P_s))^σ` with `σ = η_p k/(k − 1)`. k is taken once per slice from the suction mixture's ideal-gas heat capacity (a fixed σ = 3.5 is within 3 % at ratio 2.4 but 16 % off for propane at ratio 5).
- The gas gets the polytropic gas power `ṁ H_p / η_p`; the PH flash gives the discharge temperature. The recycle's own power is spent in the drive and its heat leaves through the recycle cooler.
- η_p constant at the design value (S3).

### 3.3 Positive displacement: reciprocating compressor, legacy and in-line movers

A positive-displacement machine delivers `Q_d s` until its relief valve lifts. As a law: a smooth corner `(Q/(Q_d s))^m + (Δp/Δp_relief)^m = 1` with m = 16, the family of today's velocity cap. It passes 98.7 % of its displacement at 90 % of the relief pressure and nothing at the relief pressure. A reciprocating compressor's relief is its stage ratio limit.

This is today's "set flow up to a head limit" written as one smooth row instead of a mode switch (form F2 in section 4.2).

### 3.4 Steam turbine

The turbine is a connection in the steam island:

- **Governor valve.** A flow valve whose mass target the engine sets once per slice from the shaft's demand: `ṁ = P_demand / (η_is Δh_is)` (the `couple` precedent). Fully open, the turbine passes what its own resistance allows at the inlet and exhaust pressures; that is its maximum power.
- **Work out.** `W = ṁ η_is Δh_is`, with `Δh_is = k/(k − 1) (P_in/ρ_in) (1 − (P_out/P_in)^((k−1)/k))` on the inlet's real `P/ρ` (524 kJ/kg against the steam tables' 528 for 40 bar, 400 °C to 4 bar). The exhaust takes `h_in − W/ṁ`, and the PH flash gives its temperature and wetness. Work leaving the fluid is new: today work is clipped at zero.
- η_is from the design (S4).

## 4. Speed, flow control and convergence

### 4.1 Who sets the speed

The shaft's driver (section 5) sets `s` once per slice. A fixed-speed motor runs at 1. A variable-speed drive, a turbine or the creative motor runs at its set speed, or regulates a flow or pressure on its machine. A train driven through a Create coupling runs at the Create speed times its gearbox ratio.

A regulated flow uses the machine's own law between slices:

- positive displacement: `s = Q_set / Q_d` (exact, no feedback);
- centrifugal: the affinity first guess `s' = s Q_set/Q`, then a secant on the last two slices, damped, clamped to the drive's range. It settles in two to four slices (10 to 20 s at the 5 s cadence).

A driver short of power (section 5.2) lowers its speed by the damped affinity step `s' = s (P_avail/P_demand)^(1/6)`. For a load whose power goes as `s^m`, each slice multiplies the error by `1 − m/6`: it halves it for the cube law and converges for any m below 12. The plain cube-root step diverges for m ≥ 6, which a static head can give.

This is the heat units' pattern: a controller between slices, never inside the Newton (heat batch, owner Q6).

### 4.2 Forms to measure (owner Q1)

- **F1.** The curve law (3.1, 3.2) for new centrifugal machines only. Positive-displacement, legacy and in-line movers keep today's mode switch. Nothing is deleted.
- **F2 (recommended if it measures no worse).** F1 plus the smooth corner law (3.3) for every positive-displacement, legacy and in-line mover. `PUMP_TARGET` and `PUMP_HEAD_LIMIT` go, with R2 to R4, `yieldPrescribed` for movers, the `atShutoff` latch, the head-limit start flow and `carriedMoverLimit`. Movers keep `CLOSED`, `INLET_WRONG_PHASE`, `PUMP_SUCTION_LIMIT` and the reverse closure.
- A third form, speed regulated inside the Newton (today's rows with the curve as the limit), keeps every switch and every rule; not proposed.

### 4.3 What it does to convergence

Removed or eased:

- The mode switch that caused the pump-shutoff holds (R1 to R4), the sealed-pocket singularity (hold F8) and the head-limit start walks: one law, no switch (F2; F1 only for new machines).
- Parallel machines at their limit: two constant head limits feeding one node leave the split undetermined; falling curves fix it.
- `BalanceNet.runFlow` assumes the law's loss rises strictly with flow. Loss minus a falling curve still rises strictly; a constant rise was the special case.

Added:

- A quadratic (pump) or power-law (compressor) term in the row: smooth and monotone. Mild.
- A slice or two of controller transient before an island rests (more solves per world; cost measured by counters).
- The PD corner law's curvature near relief: like the velocity cap, which already runs as a smooth law.
- Not in the Newton: drivers, speeds, steam demand, FE. All are read at slice start and booked at commit.

### 4.4 The change that needs care: compressor energy

Booking polytropic instead of isothermal work makes a compressor's discharge 35 K hotter at ratio 3 (+50 % energy). Downstream vessels run hotter and lighter; worlds near a phase boundary move. This is a physics correction, not a convergence change, but it moves the GameTest prints, the campaign's worlds with compressors and `CompressorTest` (nitrogen at ratio 3 from 298 K: +93 ± 5 K today, about +143 K at η_p 0.8).

### 4.5 Measurement (AGENTS.md, lean)

1. The quick check, 200 seeds, all distributions: base, F1, F2. The hold-fuzz harness gains a centrifugal variant of its in-line movers on the same seeds (a rated point drawn from the set flow and limit).
2. One full campaign (1000 seeds, 8 workers) for the chosen form. Replay alone only new budget-class holds.
3. The final lane once, at the end of the batch.

chain-100 and the junction lines hold no movers; they stay unless shared code moves them.

## 5. Shaft trains

### 5.1 What a train is

Shaft blocks and machine shaft ends joined along one axis. A gearbox joins two trains at a ratio. One train has one driver (motor, turbine, creative motor or a Create coupling) and one or more driven casings.

### 5.2 How the engine runs it

Quasi-steady, once per slice: no inertia (a rotor runs up in 1 to 10 s, about one slice).

- The driven islands read the train's speed at dispatch, as a heat side reads its partner. They book their shaft power at commit.
- The train's demand is the sum of its machines' last booked powers over the gear efficiencies.
- The driver answers at its next slice:
  - a motor at or under its rating runs; above it, a fixed-speed motor trips (`OVERLOAD`) and a variable-speed drive lowers its speed until the demand fits (current limit);
  - a turbine raises its governor target, and when it is short of steam its speed falls by the damped affinity step (section 4.1);
  - a creative motor holds its speed up to its power limit, then lowers it the same way;
  - a Create coupling takes at most its Create network's headroom; short of it, the train lowers its speed the same way, and the Create network never overstresses (section 8).
- A speed change, a trip or a start is a dependency change: it wakes the islands it drives. An island at rest stays at rest while its speed holds.

A pump driven by a turbine in the same island (a boiler feed pump on its own steam) still couples one slice late.

### 5.3 Speeds and drawing

- Speeds up to 20,000 rpm. A gearbox ratio between 1:20 and 20:1 (Create input: up to 1:64, so 256 rpm reaches 16,384 rpm).
- Shown speed: real speeds alias at 60 fps (3000 rpm is 50 rev/s and shows as −600 rpm; 3600 rpm stands still). The drawing turns at `min(real, 120 rpm)`, 2 rev/s like today's pumps; the screens give the real speed. The client integrates the angle with an eased speed, so the 5 s updates do not make it jump.
- Drawn diameter: three times the torque diameter `d = (16 T/(π 40 MPa))^(1/3)`, rounded to whole pixels, 2 to 8 px. The 24 kW pump train shows 2 px, a 1 MW motor 4 px, a 10 MW compressor at 9000 rpm 5 px, the Create side of a 1 MW gearbox (37 kN·m at 256 rpm) 8 px. Couplings and guards are drawn round it, inside the block.

## 6. Equipment

Every new model keeps one free block between nozzle pipe blocks (`ProcessUnitTest.pipesApart`), and the layout table records it.

- **Pump casing.** OH2 and BB1 without the motor, the motor's baseplate half and its coupling half. The constructor asks rated flow, rated head and speed class; the discharge comes from the flow at 3 m/s as today, the impeller from the head and speed. A shaft end with a half coupling and guard faces the driver.
- **Centrifugal compressor casing.** Without its step-up gear, motor and motor guard. The lube console stays (it belongs to the machine). Rated flow and polytropic head or ratio; impellers and speed follow (30 to 40 kJ/kg per impeller, about 300 m/s tip speed).
- **Reciprocating compressor frame.** Without its synchronous motor. Bore and throws as today; speed from the stroke as today.
- **Motor.** Rated power from the IEC list (0.75 kW to 20 MW), speed class (2, 4, 6 or 8 poles), drive (fixed or variable speed). Low voltage TEFC up to 400 kW (fins, cowl, fan, terminal box, feet); above, today's large motor (stator, top-hat cooler). Size from `motorDiameter(P)` as today. The terminal box is the RF port.
- **Steam turbine (one kind).** Rated power, inlet pressure and temperature class, exhaust pressure (back pressure or vacuum). Speed and casing follow. Parts: casing (HP end, barrel, exhaust), governor valve with actuator, trip-and-throttle valve, front and rear pedestals, coupling and guard, base. The generator, gear, exciter, TEWAC cooler, oil pump and sentinel valve go. A condensing turbine is the same casing with a vacuum exhaust; the player builds the condenser from an exchanger (owner Q5).
- **Industrial gearbox.** Parallel-shaft, two shaft ends, a ratio, about 98 % efficiency. With Create installed, a Create coupling on its slow end brings Create's rotation in (section 8).
- **Creative motor.** One block: a set speed and a power limit, no electricity.
- **Shaft block.** One axis; joins shaft ends and other shaft blocks; carries no fluid.
- **Air cooler.** One cell of today's footprint becomes the RF port (terminal box); the footprint does not grow.

## 7. Electricity (FE)

- **RF port.** A structure cell with the NeoForge block capability `Capabilities.EnergyStorage.BLOCK` (receive only). Other mods push FE into it every tick; that is their protocol, not process work. It fills a buffer of two slices at full rating at the longest cadence (2 × 20 s), and takes up to twice the rated draw per tick.
- **Booking.** The engine takes `P_elec Δt / (J per FE)` at each slice commit of the machine's train, and on the presentation cadence for a train whose islands rest. `P_elec = P_shaft / η_motor`, with η from the IE3 table, less 3 % for a variable-speed drive. Booking per period avoids rounding small loads to whole FE per tick. A buffer that runs out trips the motor (`NO POWER`) at the next slice. It restarts by itself once its buffer holds one slice of its draw (owner D6, A2); an `OVERLOAD` trip waits for the player's reset.
- **Air cooler.** Fan power (today's formula) over η_motor. Without FE the fans stop and the louvers give natural draught (0.25); a regulating cooler reports `LIMITED`.
- **Ratio.** Default 2.5 J per FE (Mekanism's joule), config `energy.joulesPerFE`; the Create rate follows it (section 8). The 24 kW pump draws 520 FE/t; the 865 kW compressor motor 17,300 FE/t; a 10 MW machine 200,000 FE/t. For scale: Mekanism's laser takes 4,000 FE/t, its nucleosynthesizer 40,000; late generators give 10⁵ FE/t and more.
- `energy.machinesNeedPower` (default true): false lets a pack without energy mods run motors and fans free.

## 8. Create (optional)

- **Packaging.** `neoforge.mods.toml`: Create `optional`. Gradle: Create, Ponder and Flywheel `compileOnly` with a dev runtime. Replace Catnip's `Outliner` with our own outline drawing in `EquipmentGhost` and `PipeLayingClient`. Own textures for the constructor and the interior cell. The GameTest that calls `BlockMovementChecks` runs only with Create. The `data/create` tag stays (inert without Create).
- **Compat module.** `compat/create/**` holds one block, the Create coupling: a Create `KineticBlockEntity` on one face, an industrial shaft end on the other, at Create's speed. It registers only when `ModList.isLoaded("create")`, behind one guarded entry point; no other class names a Create type. The industrial gearbox stays our own block in every pack. A world opened without Create loses the couplings, as it loses every Create block.
- **Power rule (owner: "FE needs to match SU to prevent loopholes").** One FE per tick trades for 34.13 su, the rate both Create electricity addons use (Crafts & Additions and New Age: 16,384 su for 480 FE/t); config `create.stressPerFE`, default 34.13. A stress unit is then worth `20 × (J per FE) / 34.13` W: 1.46 W at 2.5 J per FE. There is no separate watt rate. So any round trip FE → shaft → Create → FE, through our machines or the addons', returns only the product of its efficiencies, in either direction. A pack that changes the addons' rate sets ours to match.
- Real wheels agree (owner 2026-11-03: "Create duty a bit overrated, research about actual water wheel duty"; research review section 5): water wheel 256 su = 0.38 kW (a real 2 m stream wheel gives 0.2 to 0.6 kW), large water wheel 512 su = 0.75 kW (a real 4 to 5 m stream wheel 0.6 to 1.4 kW).
- What else it gives: hand crank 0.38 kW (a strong person's short effort); windmill 0.75 to 12 kW; steam engine 24 kW per boiler level, 4.4 % of the boiler's 543 kW of heat (Watt-era engines ran at 3 to 5 %); a level-18 boiler 432 kW; creative motor at 256 rpm 6.1 MW. The 24 kW pump needs about 16,900 su (gearbox included): 66 water wheels or one boiler level.
- **Draw.** The coupling's stress impact follows the train's demand each slice: `impact = P_demand / (W per su × |rpm|)` (100 kW at 256 rpm: 267 su/rpm), registered through `BlockStressValues`. It reads the network's capacity and load (`updateFromNetwork`) and never takes more than the headroom: short of headroom, the train is power limited as a turbine short of steam, and the player's Create network does not stop.
- **Speeds.** Create runs at most 256 rpm (its config); the gearbox raises it up to 64 times, to 16,384 rpm. Industrial shafts are not Create kinetics, so Create's cap never applies to them.
- Industrial driving Create (a turbine turning Create machines) is later work (owner D8). It uses the same rate; its Create side must stay below Create's maximum speed.

## 9. Controls and screens

Prototypes first (`gui-creation` skill), owner picks, then build.

- **Motor.** Off/On. Drive: fixed or variable speed. Variable: Speed (%), or Regulate (the driven machine's flow, or its discharge pressure). Status: `RUNNING`, `STOPPED`, `TRIPPED: OVERLOAD`, `TRIPPED: NO POWER`. Readings: speed, shaft power, electric power, FE/t, buffer.
- **Turbine.** Trip and Reset. Speed set or Regulate, as the motor. Status: `RUNNING`, `STEAM LIMITED`, `TRIPPED`. Readings: steam flow, inlet and exhaust pressure, power, speed.
- **Gearbox.** Ratio. Readings: both speeds, power.
- **Create coupling.** Readings: Create speed, stress drawn, the network's headroom.
- **Creative motor.** Speed, power limit, Regulate as the motor.
- **Pump and compressor.** The set flow (or discharge pressure) stays on the machine's page, where it is today; the driver follows it when it regulates. A fixed-speed train shows "flow follows the line"; a casing with no driver on its shaft shows `NO DRIVER` and stands closed. Readings: flow, head, speed, power, efficiency; the curve with the operating point.
- Every new key in all five lang files (AGENTS.md Translations); status words stay in English.

## 10. Saves (AGENTS.md "Saves and data formats")

- Fluid facts schema 8: the new device kinds, the machine's design point and law kind, the driver's settings, the FE buffer's content (energy the player put in), the gearbox ratio, the creative motor's set points. Corpus image `compat/fluid/schema-8`.
- Equipment shape fields 6: the pump's and compressor's design point, the motor's and turbine's duty. An old pump's missing design point takes today's datasheet values (A3). Corpus image `compat/equipment/shapes-6.snbt`.
- No backward compatibility for constructed movers (owner D4): old placed pumps and compressors rebuild to the casing layout through the layout refresh, without their drawn motor (and gear); they stand closed with `NO DRIVER` until the player joins a driver. Their registrations, identity and settings stay; the set flow is still the set flow.
- Old steam turbines (drawing only) rebuild to the new layout; the owner approves the moved nozzle pipe blocks (owner D5).
- State format bump for the new modes; state is rebuilt.

## 11. Process simulator (owner Q9)

The simulator's units call the same Minecraft-free classes (`science/machine/**`): pump curve and power, compressor head, ratio and gas power, turbine steam rate, motor sizes. Sizing proposes a motor or turbine with the owner's 20 % spare. The turbine tile gets its law. The simulator branch is open and another thread owns it, so the work lands after both merge.

## 12. Work packages and gates

| WP | Content | Gate |
|---|---|---|
| W0 | Owner answers; GUI option sheets for the new screens and constructor rows | owner picks |
| W1 | `science/machine/**`: curves, heads, power, steam rate, sizes; tests | unit tests |
| W2 | Fluid laws (3.1 to 3.4), form F1 or F2, energy booking, turbine connection; harness variant | quick check, then one campaign |
| W3 | Shaft trains: blocks, registry, per-slice driver logic, island coupling, trips, the generator; facts schema 8 | unit tests, GameTests, corpus |
| W4 | Equipment: casings, motor, turbine, gearbox, creative motor, generator, shaft drawing; constructor rows; shape fields 6 | layout table, `pipesApart`, renders, owner picks |
| W5 | RF port, buffers, booking, auto restart, generator output; air cooler and cooling-tower fans; config | unit tests, GameTest |
| W6a | Create and KubeJS optional: packaging, own outline drawing, own textures, the GameTest | build and GameTests without Create and with it |
| W6b | The Create coupling (after W3) | GameTest with Create |
| W7 | Screens and controls, lang | gates, renders |
| W8 | Simulator support (after both merge) | simulator tests |
| W9 | Final lane, code guide, tooling cleanup, CHANGELOG, version (minor) — only when the owner says so | final lane |

W4 and W6a touch neither the solver nor each other; they run as their own sessions beside W1 and W2. W4 defines the shape fields (the design points and duties); W1 takes plain numbers; W3 joins them at registration.

## 13. Owner questions

Answered 2026-11-03: "Q4 no backward compat, Q6 auto start, Others as recommended". So Q1 to Q3, Q5 and Q7 to Q9 take the recommended option; Q4 takes (b); Q6 takes (b); Q10 joins the batch whole (reading A1). Then, on Q7: "Create duty a bit overrated, research about actual water wheel duty", and "FE needs to match SU to prevent loopholes": 2.5 J per FE, with one FE per tick trading for 34.13 su as in Create's electricity addons, so 1 su = 1.46 W, which matches real stream wheels (D7 revised). Decisions D1 to D9 and A1 to A4: [DECISION_LOG.md](DECISION_LOG.md). The questions as asked:

- **Q1. Law form.** F2 (smooth laws for every mover, mode switch deleted) if the quick check measures it no worse than F1, else F1. Recommended: measure both, take F2 if no worse.
- **Q2. Flow control.** Between slices (recommended, the heat-unit pattern; the set flow arrives in 10 to 20 s) or inside the Newton (immediate, keeps every switch).
- **Q3. In-line movers.** (a) They stay utility movers: set flow up to the added pressure or ratio (the corner law under F2), an integral motor, no FE (recommended); (b) the same, drawing FE through the pipe's free faces; (c) they retire, and only constructed machines move fluid.
- **Q4. Old constructed movers.** (a) Keep their drawn motor and run free (`INTEGRAL`, recommended, no fact lost); (b) rebuild as casings without a driver (they stop until the player adds one).
- **Q5. Turbines.** (a) One kind; old condensing turbines rebuild to it and their drawn condenser goes (owner approves the moved nozzles); (b) keep both kinds, slimmed.
- **Q6. After a power loss.** (a) The motor stays tripped until the player restarts it (recommended, as a real motor starter); (b) it restarts when FE returns.
- **Q7. Ratios.** (a) 10 J per FE and 5.86 W per su, at parity (recommended); (b) Mekanism's 2.5 J per FE, with the parity 1.47 W per su (a 0.38 kW water wheel); (c) 10 W per su, the best match to real wheels, with 17.1 J per FE.
- **Q8. Create direction.** (a) Create drives industrial shafts only (recommended first); (b) also industrial drives Create.
- **Q9. Simulator timing.** (a) After both batches merge (recommended); (b) on the simulator branch now.
- **Q10. Not asked, proposed.** A generator (shaft to FE) so a turbine can power motors without other mods; cooling-tower fans on the same RF port; KubeJS made optional (it is required but unused).

## 14. Simplifications (with their effect)

- **S1.** Pump losses not booked into the liquid: 0.15 K at the rated point, 0.3 K at half flow. At shutoff a real pump heats its trapped liquid with all its power (45 kW into 50 kg of water: 13 K a minute); here it adds nothing, and a `MIN FLOW` notice shows below about 30 % of rated flow.
- **S2.** No NPSH required curve: the suction limit stays at the vapour pressure; the screen shows the margin.
- **S3.** Constant polytropic efficiency along a speed line: the real one falls about 5 points toward surge and choke.
- **S4.** Constant turbine efficiency at part load; no-load steam (10 to 15 % of full) ignored.
- **S5.** No slip, no inertia, no torque-speed curve: flow 0.9 % low at 60 % load on the example line; speed changes land within one slice.
- **S6.** No viscosity correction of pump curves (heavy oil above about 100 cSt loses head and efficiency).
- **S7.** Drawn shaft diameter three times the torque diameter, 2 to 8 px.

## 15. Risks

- The compressor energy change moves thermo-sensitive worlds (4.4). Read with the campaign's classifier.
- The controller transient keeps islands awake for a few slices; cost by counters.
- Create optional: any missed Create or Catnip class load crashes a client without Create. Gate: a build and GameTest run with Create absent.
- The old turbines' nozzle moves need the owner's approval.
- Scope: ten requests in one batch. W2 decides the route before W3 to W9.
