# Prime movers: liquid-fuel engines, gas turbines, generators to 300 MW

Batch 2026-11-10-prime-movers, branch `claude/prime-movers-4b7224`, cut from `claude/custom-shader-system-caff9e` at `841e765f2`. Owner, 2026-11-10: "Add combustion engine which worked on liquid fuels, and gas turbine for gas fuels; should support MW scale size (also make bigger generators if their size doesn't match)". Fixed by the owner: size "To 300 MW" (engines 0.5 to 20 MW at 1500, 750 and 500 rpm; gas turbines 1 to 300 MW at 3000 rpm; generators 20 to 300 MW); exhaust "Own stack + steam coil"; fuel gas "Real supply pressure". Revision 1: the plan, decisions P1 to P14.

## 1. Today

- Drivers: VFD motor, steam turbine (55 kW to 45 MW, `ShaftTrain.Drive.TURBINE`, internal admission governor), creative motor, Create coupling. `ShaftTrain.step` (governed turbine branch) already does what a fuelled driver needs: the next speed from the demand and the power its supply gave (`Measure.available`), a feed-forward `governorPower` at the set speed, `STEAM LIMITED` when short, zero speed with no supply, `OVERSPEED` latched at 1.10.
- Fuel: the fired heater's burners are a `VOID` at the tip back pressure behind a FUEL port, their fuel valve a `ControlValve` (`ValveSource.BURNER`) whose mass target is a slice input (`ShaftPlan.Kind.FUEL`); `FiredHeaters` books the fired heat `ṁ·LHV` (`Burners.perKilogram`) at the fuel island's commit and raises `NO FUEL`, `LOW FUEL`, `FUEL NOT COMBUSTIBLE`. The liquid-fired still uses the same void with `ValveSource.STILL`.
- Exhaust heat recovery: `ConvectionCoil` is a counterflow ε-NTU coil against flue gas of capacity rate `flue` (a slice input) entering at the fixed bridgewall.
- Generator: `MotorDesign(generator)`, IEC list to 20 MW (`MachineDuty.MAX_MOTOR`), poles 2/4/6/8, efficiency from the IE3 table (95.8 % from 200 kW up, 2-pole).
- FE: stores keep joules in `double`; the port rounds to `int` and `stored`/`capacity` already clamp at `Integer.MAX_VALUE` (section 9).

## 2. Efficiency against size and load (physics first)

Both families follow a **Willans line**: fuel heat `Q_f = Q_r (c₀ + (1 − c₀) x)`, `x = W/W_r` the load share, `Q_r = W_r/η_r`. So `η(x) = η_r x / (c₀ + (1 − c₀) x)`; at no load the set burns `c₀ Q_r` holding speed.

| | c₀ | η/η_r at 75 % | 50 % | 25 % | idle fuel |
|---|---|---|---|---|---|
| Engine (diesel, constant speed) | 0.10 | 0.97 | 0.91 | 0.77 | 10 % |
| Gas turbine (single shaft, constant speed) | 0.28 | 0.91 | 0.78 | 0.54 | 28 % |

Checks: medium-speed gensets lose 5 to 10 % of efficiency at 50 % load, about 20 % at 25 %; a heavy-duty GT's heat rate rises 25 to 30 % at 50 % load and about 80 % at 25 %.

Rated efficiency η_r (shaft, ISO 15 °C, LHV) comes from each model's catalogue line (section 6), not from a curve: engines 0.41 (0.5 MW high-speed) to 0.48 (20 MW medium-speed); gas turbines 0.25 (1.2 MW industrial) to 0.41 (50 MW aeroderivative), heavy-duty 0.33 (45 MW E-class) to 0.39 (300 MW F-class). Ambient is the game's fixed 25 °C: no ambient derate.

## 3. Fuel rate from the heating value

`ṁ_f = Q_f / LHV`, LHV per kg of the stream at the fuel valve (`Burners.perKilogram`, the material records' `combustion` sub-records), the same as the fired heater.

| Set | W_r | η_r | Q_f full load | Fuel | ṁ_f |
|---|---|---|---|---|---|
| HS-500 engine | 0.5 MW | 0.41 | 1.22 MW | diesel 42.8 MJ/kg | 0.028 kg/s (205 g/kWh) |
| MS-20000 engine | 20 MW | 0.48 | 41.7 MW | diesel | 0.97 kg/s (175 g/kWh) |
| IND-30 GT | 30 MW | 0.36 | 83.3 MW | methane 50.0 MJ/kg | 1.67 kg/s |
| HD-300 GT | 300 MW | 0.39 | 769 MW | methane | 15.4 kg/s |

Catalogue: medium-speed diesels 175 to 190 g/kWh, high-speed 200 to 215.

## 4. Air and exhaust

The engine and the GT draw their combustion air from the atmosphere inside the package (filter house drawn, no air port). Energy balance at every load: `Q_f = W + Q_exh + Q_cool + Q_loss`.

- Gas turbine: `Q_cool = 0`, `Q_loss = 0.02 Q_f` (casing, lube oil); `Q_exh = Q_f − W − Q_loss`.
- Engine: `Q_exh = 0.28 Q_f`, `Q_loss = 0.02 Q_f`, the radiator takes the rest, `Q_cool = Q_f − W − Q_exh − Q_loss` (section 5).
- Exhaust temperature `T_e` is the model's (section 6), held at every load; the exhaust flow follows the heat: `ṁ_e = Q_exh / (c_p,e (T_e − T_air))`, `c_p,e` 1.12 kJ/(kg K), `T_air` 25 °C (`Burners.AIR`). Air = `ṁ_e − ṁ_f`.

| Set | T_e | Q_exh | ṁ_e | Volume at T_e, 1 atm | Stack at 25 m/s |
|---|---|---|---|---|---|
| HS-500 engine | 480 °C | 0.34 MW | 0.67 kg/s | 1.5 m³/s | 0.28 m |
| MS-20000 engine | 340 °C | 11.7 MW | 33 kg/s | 59 m³/s | 1.7 m |
| IND-30 GT | 500 °C | 51.7 MW | 97 kg/s | 214 m³/s | 3.3 m |
| HD-300 GT | 600 °C | 454 MW | 705 kg/s | 1,750 m³/s | 9.4 m (7.1 m at a 100 °C stack) |

Checks: Wärtsilä 18V46 about 37 kg/s at 340 °C; 9F.05 simple cycle about 700 kg/s at 640 °C. The owner's "30 MW GT about 90 kg/s at 500 °C, about 200 m³/s" agrees. The exhaust is not a stream: no pipe reaches 0.8 m at 200 m³/s. Each package draws its own stack (diameter for 25 m/s, at most `EquipmentShape.MAX_DIAMETER` 10 m) and releases to the atmosphere with the flue smoke (`FxSources`, `Burners.Fuel` LIQUID for the engine's soot, GAS for the GT's clear hot plume). The page shows the exhaust flow, temperature and heat, and the stack temperature after the steam coil.

Part load, simplification S2: a real single-shaft GT holds its exhaust temperature to about 50 % load with its inlet guide vanes, then keeps its air flow and lets the temperature fall; an engine's exhaust cools a little. The game holds `T_e` and lets the flow follow the heat. The exhaust heat is right at every load; the steam coil's steam at part load is a few % high below 50 % GT load.

## 5. The engine's radiator

A built-in air-cooled radiator (jacket water, lube oil and charge-air cooler circuits as one) takes `Q_cool`: 29 % of the fuel at 0.5 MW, 22 % at 20 MW (0.35 and 9.2 MW at full load). Its fans move air across a 20 K rise at 250 Pa over a 60 % fan: fan power `Q_cool · 250 / (1.185 · 1005 · 20 · 0.6)` = 1.75 % of the radiator's duty: 6 kW at HS-500, 160 kW at MS-20000 (0.8 to 1.2 % of output; catalogue 1 to 3 %). The radiator is drawn as a bank beside or above the engine, sized by its duty. No coolant port, no ambient dependence. Where the fan power goes: P4.

## 6. The gas turbine's pressure, combustor and fuel supply

The compressor raises the air to the combustor pressure `P_c = PR · P_atm` (inlet and combustor losses inside the numbers). The fuel gas must enter the combustor through the fuel nozzles (their drop about 20 % of `P_c`) behind the control, stop and ratio valves; the supply must stand at `P_req = 1.4 P_c` at full fuel. The rule:

- The fuel inlet is a `VOID` standing at `P_c` (the engine's: `P_atm`), behind a fuel valve (a new `ValveSource`) whose mass target is a slice input from the governor, as the burners' is.
- The valve's capacity is sized so that it passes the full-load fuel wide open at `Δp = P_req − P_c` (GT: `0.4 P_c`; engine: 100 kPa, its injection pump inside the package). At a supply pressure `P_s` below `P_req` it passes at most `ṁ_r √((P_s − P_c)/(P_req − P_c))`: the set derates; at `P_s ≤ P_c` no fuel enters and the set stops.
- Status (on the page, the train's word): `LOW FUEL PRESSURE` when the fuel is short and `P_s < P_req`; `LOW FUEL` when the fuel is short at a sufficient pressure (the valve upstream throttled, the tank running dry); `NO FUEL` with none; `FUEL NOT COMBUSTIBLE` for a stream with no heating value; the wrong phase: P6.

| Model | PR | P_c, bar(a) | P_req, bar(a) / bar(g) |
|---|---|---|---|
| IND-1 | 7 | 7.1 | 9.9 / 8.9 |
| IND-5 | 12 | 12.2 | 17.0 / 16.0 |
| IND-15 | 17 | 17.2 | 24.1 / 23.1 |
| IND-30 | 20 | 20.3 | 28.4 / 27.4 |
| AE-35 | 24 | 24.3 | 34.0 / 33.0 |
| AE-50 | 31 | 31.4 | 44.0 / 43.0 |
| HD-45 | 12.5 | 12.7 | 17.7 / 16.7 |
| HD-130 | 13 | 13.2 | 18.4 / 17.4 |
| HD-300 | 18 | 18.2 | 25.5 / 24.5 (owner's "about 25 bar(g)") |
| every engine | | 1.01 | 2.0 / 1.0 |

Simplification S3: `P_c` is the design's at every load (real: it falls about 10 % at 50 % load on a single shaft with closed IGVs). A low-pressure gas needs a compressor the player builds (owner).

## 7. The models (P2) and the constructor category (P3)

Model = kind + rated power (the duty's `power`, unique within a kind); every other number is the model's catalogue line. Shaft output speed is the package's: GTs below heavy-duty run a built-in gearbox or free power turbine to 3000 rpm (owner: "at 3000 rpm").

| Model | Kind | W_r MW | rpm | η_r | T_e °C | Exhaust kg/s | Look |
|---|---|---|---|---|---|---|---|
| HS-500 | engine | 0.5 | 1500 | 0.41 | 480 | 0.67 | V12 high-speed set, radiator on its end |
| HS-1000 | engine | 1.0 | 1500 | 0.42 | 480 | 1.3 | V16 |
| HS-2000 | engine | 2.0 | 1500 | 0.435 | 470 | 2.6 | V20 |
| MS-5000 | engine | 5 | 750 | 0.455 | 360 | 8.2 | inline 9 medium-speed, radiator bank beside |
| MS-10000 | engine | 10 | 750 | 0.465 | 350 | 16.5 | V18 |
| MS-15000 | engine | 15 | 500 | 0.475 | 340 | 25 | V16 large-bore |
| MS-20000 | engine | 20 | 500 | 0.48 | 340 | 33 | V20 large-bore |
| IND-1 | gas turbine | 1.2 | 3000 | 0.25 | 500 | 6.6 | industrial package, enclosure |
| IND-5 | gas turbine | 5.7 | 3000 | 0.31 | 490 | 24 | |
| IND-15 | gas turbine | 15 | 3000 | 0.34 | 490 | 54 | |
| IND-30 | gas turbine | 30 | 3000 | 0.36 | 500 | 97 | |
| AE-35 | gas turbine | 35 | 3000 | 0.39 | 550 | 90 | aeroderivative, slim core in an enclosure |
| AE-50 | gas turbine | 50 | 3000 | 0.41 | 450 | 146 | |
| HD-45 | gas turbine | 45 | 3000 | 0.33 | 545 | 152 | heavy-duty frame, open casing, axial exhaust |
| HD-130 | gas turbine | 130 | 3000 | 0.35 | 540 | 406 | |
| HD-300 | gas turbine | 300 | 3000 | 0.39 | 600 | 705 | |

Every design carries the 20 % spare: the proposed generator is `ratingFor(1.2 W_r)` (P9); the constructor's sizing picks the smallest model with `W_r ≥ 1.2 ×` the load.

Constructor (P3, recommended A): a seventh category **Power**: Steam turbine (moved from Machines), Gas turbine, Combustion engine, Generator (moved). A design page has a Model selector (two a row, as the vacuum pumps'), shows W_r, rpm, η_r, fuel at full load, exhaust flow and temperature, and for the GT the required fuel pressure and the Steam coil switch; for the engine the radiator duty. Two new `EquipmentShape.Kind` (`COMBUSTION_ENGINE`, `GAS_TURBINE`), `ConstructorColumn.Equipment` and `ShaftNetwork.Kind` constants, appended.

Nozzles (one free block between nozzle pipe blocks): engine FUEL (DN 25 to 80 by flow); GT FUEL (DN 25 to 200); GT with the coil: WATER in at the coil's cold end, STEAM out at its hot end (DN by 25 m/s steam at 40 bar). Shaft end at the drive end (the generator end). Largest piece: HD-300 with its coil about 12 m (GT) + 8 m diffuser + 14 m coil duct + stack, 34 m long, 9 m wide, stack top 30 m: inside `MAX_TANGENT_LENGTH` 64 and the 16-block support; its 400 MW generator about 14 m on its own structure. Together about 50 m.

## 8. The governor

The prime movers reuse the turbine driver law (`ShaftTrain.Drive.TURBINE`, governed), its supply the fuel's:

- `available = W(Q_fired)`: the shaft power the fuel the last slice fired gives on the Willans line, `W = W_r (Q_fired/Q_r − c₀)/(1 − c₀)`, at least 0, less the radiator fans under P4 A.
- The feed-forward `governorPower` (the demand at the set speed) sets the fuel valve's target: `ṁ = Q_r (c₀ + (1 − c₀) governorPower/W_r) / LHV × MARGIN` (1.01, `Burners.MARGIN`), capped at the rated fuel `Q_r/LHV`. Before any fuel arrived, methane's or diesel's LHV (50 / 42.8 MJ/kg).
- Follows the requested speed (share 0.9 to 1.05 of rated, as the turbine's setting) and the shaft demand; a generator on the train takes its store's room as load (`ElectricStore.generatorLoad`), so the set follows the FE drawn.
- Short fuel: the train slows on `SpeedControl.balance` and says `LOW FUEL PRESSURE` or `LOW FUEL` in place of `STEAM LIMITED`; no fuel stops it (`NO FUEL`, speed 0). Over the rating: `POWER LIMITED`.
- Overspeed: `TRIP_SPEED` 1.10, latched, reset on the page (P11).
- Start: P10.

## 9. Generators to 300 MW (P9) and the FE store

- Ratings: the R20 list on from 20 MW: 22.4, 25, 28, 31.5, 35.5, 40, 45, 50, 56, 63, 71, 80, 90, 100, 112, 125, 140, 160, 180, 200, 224, 250, 280, 315, 355, 400 MW (P9 A) for the generator only; motors keep 20 MW.
- Poles and speed: 2 poles 3000 rpm (steam and gas turbines), 4 poles 1500 rpm (high-speed engines), 8 poles 750 rpm, 12 poles 500 rpm (medium-speed engines; 12 is appended to `MachineDuty.POLES`, saved as a number). A generator's design speed follows the driver it is proposed for.
- Efficiency (S1): IE3's 95.8 % is a motor table. Large synchronous generators run 97.5 % at 10 MW, 98.5 % at 100 MW, 98.8 % at 300 MW. Proposal: for a generator above 1 MW, `η = 0.958 + (0.988 − 0.958) · log(P/1 MW)/log(300)`, 0.970 at 10 MW, 0.982 at 100 MW. Effect: a 300 MW set gives 296.4 MW in place of 287.4 MW. Moves the existing generators' output above 1 MW by up to 2.4 % (state, not facts).
- Look: a 2-pole generator above 20 MW is drawn as a turbo-generator: a long cylindrical stator with end bells, its exciter on the far end, coolers on top, on a concrete table; below, today's large-generator box.
- FE arithmetic at 300 MW, `joulesPerFE` 2.5: output 1.2e8 FE/s = 6.0e6 FE a tick; the port's per-tick rate (`RATE` 2×) 1.2e7 FE, inside `int` (2.15e9). The 40 s buffer is 1.2e10 J = 4.8e9 FE, over `Integer.MAX_VALUE`: the store keeps `double` joules; `ElectricStores.stored` and `capacity` already clamp at `Integer.MAX_VALUE`, and `extract` takes at most the request. Build check: `PowerGrid`'s transfer loop and the conduit's per-tick sums in `long` or `double`; a test at 400 MW.

## 10. The steam coil (P8)

Optional on every GT (a design switch, shape field). A single counterflow coil in the exhaust duct between the turbine and the stack: water in at the cold end, steam out at the hot end, as the fired heater's convection coil. The gas side is internal: it enters at `T_e` with capacity rate `C_g = ṁ_e c_p,e` (a slice input from the GT's last fired slice), and leaves to the stack.

A coil designed to its pinch (simplification S4, no UA): the outlet enthalpy is the least of

1. the approach: `h(T_e − ΔT_ap)`, `ΔT_ap` 25 K (the superheater's hot end);
2. the pinch at every point of the water's curve: `h(T) + C_g (T_e − T − ΔT_p)/ṁ` over the curve's points from the inlet to the outlet (the bubble point's liquid end decides in practice), `ΔT_p` 10 K.

`Q = ṁ (h_out − h_in)`, at least 0; 0 at no flow or with the GT stopped. The stack leaves at `T_e − Q/C_g`.

IND-30 at full load, feed water 105 °C, steam at 40 bar (T_sat 250 °C): the gas reaches the evaporator's cold end at 260 °C; above the pinch it gives `108.6 kW/K × 240 K` = 26.1 MW; 2,127 kJ/kg from saturated liquid to 400 °C steam → 12.3 kg/s (44 t/h); the economizer takes 8.0 MW; the stack leaves at 187 °C (single-pressure HRSGs 150 to 190 °C). The steam into a condensing steam turbine gives about 10 MW: combined cycle 40 MW, 48 % (single-pressure combined cycles 46 to 50 %). HD-300: about 125 kg/s of 40 bar steam, about 100 MW from a 400 MW generator's steam turbine (the 45 MW steam turbine limit: two to three of them; P12).

Lowest stack temperature: the pinch from the feed water (`T_in + ΔT_p`); cold feed water cools the gas to 35 °C with no floor (P8 B: a 90 °C floor).

## 11. Saves, sound, simulator

- Fluid facts schema 19 → 20: `ShaftUnit` kinds `COMBUSTION_ENGINE`, `GAS_TURBINE` (by name), each naming its fuel inlet registration; the fuel void with the new `ValveSource` (by name); the coil as a `HEAT_SIDE` with a new `HeatSides.Unit`/`Side` (by name). Corpus image `compat/fluid/schema-20/`.
- Shape fields 12 → 13: the steam coil switch (default off, the old behaviour; there were no GTs); `shapes-13.snbt`. 12 poles accepted in `MachineDuty`.
- Checkpoint `FluidCheckpointCodec.VERSION` 45 → 46: the coil's heat law tag and the fuel valve source.
- Layout rows in `EquipmentLayoutGoldenTest`; drawings `update.ps1 -Check`, then `-Only COMBUSTION_ENGINE,GAS_TURBINE,GENERATOR`.
- Sound (P13): two synthesized loops in `tools/process-sounds/`: `engine_running` (firing pulses at `cylinders × rpm/120` Hz over a low rumble and turbo whistle) and `gas_turbine_running` (compressor whine plus broadband roar); pitch by rpm as the drivers' (`DriverSounds`).
- Simulator (P14): `FlowsheetCatalog` cases.

## 12. Decisions for the owner

| | Question | Options | Recommended |
|---|---|---|---|
| P1 | Efficiency laws | A: each model's catalogue η_r and a Willans line (c₀ 0.10 engine, 0.28 GT); B: a log curve η_r(W) per family | A |
| P2 | Models | the 7 engines and 9 gas turbines of section 7; or other sizes | section 7 |
| P3 | Constructor category | A: new **Power** category: steam turbine and generator moved there with the two new kinds; B: Power with the two new kinds only, steam turbine and generator stay in Machines | A |
| P4 | Engine radiator fans (1.75 % of the radiator duty, 0.8 to 1.2 % of output) | A: taken off the engine's shaft (the set's own auxiliary); B: an FE terminal on the radiator, NO POWER without it; C: free | A |
| P5 | Fuel supply rule | A: section 6 (void at P_c, valve sized for 0.4 P_c, √ derate, stop at P_c); B: no derate band: full fuel at P_s ≥ P_req, none below | A |
| P6 | Wrong fuel phase (gas at an engine, liquid at a GT) | A: it burns nothing, alarm `WRONG FUEL` (the fuel still enters the void); B: the valve stays shut on the wrong phase, same alarm | B |
| P7 | Exhaust simplifications | S2: T_e held at every load, flow follows the heat; c_p,e 1.12; losses 2 %; engine exhaust 28 % of the fuel | as stated |
| P8 | Steam coil | A: pinch-designed coil (ΔT_p 10 K, ΔT_ap 25 K, no UA), no stack floor; B: as A with a 90 °C stack floor; C: an ε-NTU coil with a UA like `ConvectionCoil` | A |
| P9 | Generator list | A: to 400 MW, so a 300 MW GT's proposed generator keeps the 20 % spare (1.2 × 300 = 360 → 400); B: to 300 MW, the HD-300's generator at 300 MW (no spare); plus S1 the large-generator efficiency (98.8 % at 300 MW) and the turbo look for 2-pole above 20 MW | A, with S1 and the turbo look |
| P10 | Start | A: self-starting when fuel is present (no starter, no purge); B: needs another driver to crank it | A |
| P11 | Overspeed | A: as the steam turbine's (armed at 110 %, latched; the internal governor catches a load rejection in the quasi-steady step, so it fires only on a set speed past it); B: also trip on a load rejection from above 50 % to below 5 % in one slice | A |
| P12 | Steam turbine size | A: leave its 45 MW limit (two or three for an HD-300's steam); B: extend it to 150 MW (casing to 5.5 m) | A |
| P13 | Sound | two synthesized loops, one candidate each; or three candidates each for the owner to pick | one each |
| P14 | Simulator | A: MODELLED units (fuel in, rated power, fuel from LHV, exhaust heat, the coil's water to steam); B: DRAWING_ONLY tiles now, the units in a follow-up | A |

### 12.1 The owner's answers (2026-11-10)

Owner: **"It shall not overspeed, and keep the model simple. Others as recommended"**.

- P11, neither A nor B: a prime mover never overspeeds. The governor never lets the speed pass the set speed; the speed setting tops out at the rated speed (share 1.0 at most, not the steam turbine's 1.05); a load loss cuts the fuel in the same quasi-steady step. No overspeed trip for these kinds: no `TRIP_SPEED` check, no latched trip, no reset, no `OVERSPEED` status. The steam turbine's trip stays as it is.
- "Keep the model simple", for the whole build: the simplest form that keeps conservation and these decisions; no new modes, switches or special cases; a part that needs more machinery than the plan states goes to the owner as a decision first.
- As recommended: P1 A, P2 (7 engines, 9 GTs), P3 A, P4 A, P5 A, P6 B, P7, P8 A, P9 A (to 400 MW, S1 efficiency, 12 poles, turbo look), P10 A, P12 A, P13 (one loop each), P14 A.

### 12.2 Later owner changes (2026-11-10)

- **P12 changed**: owner "max 300MW steam turbine should be enough", corrected to "300MW should be the combined duty I assume", then the owner's setting "HP=HP+MP+LP, MP=MP+LP, LP=LP. I assume this will make things simpler" ("ok"). One steam turbine unit, named by its inlet class (MP, HP, VHP), expands to its exhaust as `SteamTurbine` already does, to 300 MW. The efficiency and isentropic-drop laws are unchanged (the owner accepted that superheat pays more in game than the steam tables say). Above one casing's 45 MW the unit is drawn as tandem sections (12.3). Existing sizes keep their layout. No format changes.
- **P15 dropped** (owner's setting above): several steam turbines on one shaft were asked for, then replaced by the one-unit setting. One driver per shaft stays; `TWO_DRIVERS` is unchanged.

### 12.3 As built

- Shape fields: no bump. A prime mover's model is its size number (1 to N) and a gas turbine's coil its length (2), as the vacuum pumps. Facts schema 20, checkpoint 46, corpus image `compat/fluid/schema-20/`; the facts reader accepts 12 poles.
- Steam turbine sections (`SteamTurbineDesign.sections`), drawn only above 45 MW:
  - the front casing as today at 3 m: an HP casing for HP or VHP steam, an MP casing for MP steam;
  - an MP casing (1 Dc long, 0.9 Dc across) where HP or VHP steam expands below the MP class (17 bar);
  - an LP casing (hood 1.3 Dc across) where the exhaust lies below the LP header (4.5 bar): single-flow (1.2 Dc long) to 100 MW, double-flow (2 Dc) above it (game level: one last stage passes about 100 MW at 3,000 rpm);
  - a crossover from the front casing's exhaust flange over the sections; the exhaust nozzle up from the last casing's back end.
- Turbine powers: the R20 list from 55 kW, ending at 300 MW (between its 280 and 315 MW), `MachineDuty.TURBINE_POWERS`. A turbine's loop gain keeps its 55 kW to 45 MW range (larger units sound as the largest).
- Smoke: a prime mover's fuel void is watched as a fired heater's flue (`HeaterFlue`), its mark `stack_top_0`.
- Simulator: tiles `combustion_engine` and `gas_turbine` (Power category, the steam turbine moved in), `PrimeMoverUnit` (the fuel leaves as a flare's; the coil at its pinch, read at its inlet and bubble point), `PrimeMoverSizing`, limits `PRIME_MOVER_FUEL` and `PRIME_MOVER_SPARE`.
