# Fired heater fuel, combustion-air power and alarms: plan

Batch `2026-11-09-furnace-fuel-power`, branch `claude/furnace-fuel-power-requirements-f7a46e`. Status: decided 2026-11-09 (section 5); built on the branch, not merged.

## 1. Expected behaviour (physics first)

- Burners release Q_f = ṁ_fuel · LHV. The flue gas leaves the flame at T_flame (2073 K, `ConvectionCoil.FLAME`) and carries C_g = Q_f / (T_flame − T_air), T_air = 298 K.
- The radiant coil takes the gas from the flame to the bridgewall (1073 K): Q_r = C_g (T_flame − T_bw) = 0.563 Q_f. This is the C_g the convection coil already reads (`ConvectionCoil.flueOf`). So the fuel the current two-coil model burns is **Q_f = Q_r / 0.563**. No new constant; the coil laws stay as they are.
- At the design split (70/30, crude in at 250 °C) the absorbed duty is 0.805 Q_f: 80 % efficiency, stack about 370 °C. API 560 heaters without an air preheater run 75 to 85 %.
- Combustion air: about 0.34 kg per MJ for hydrocarbon fuels at stoichiometry, 0.40 kg/MJ at the 15 % excess air the flame temperature assumes, 0.34 m³/s per MW fired. A forced-draft fan over 1.5 kPa (burner registers and ducts) at 70 %: 0.72 kW per MW fired at the shaft, about **0.8 kW electric per MW fired** with its motor. A 10 MW (absorbed) heater fires 12.4 MW and draws about 10 kW.
- Fuel short: the burners fire what arrives. The radiant coil's most heat is min(design duty, 0.563 · ṁ_delivered · LHV); the convection coil follows by its flue as today. The outlet falls below the set point.
- Power lost: the fans stop, the flame safeguard trips the burners: no fire, no fuel taken, both coils pass the stream. They relight when the store holds one slice of rated draw again (the air cooler's restart rule).

## 2. What the code does now

`FiredCoil` fires to its set point up to `HeatSides.coilDuty` with fuel "another scope": heat enters free. The FUEL nozzle is drawn and placed but is not a fluid port. A fired heater has no FE store or power socket (`EquipmentServices.powered`, `PowerPorts.eligible`). The control system has statuses per controller, but no alarms.

## 3. Proposal

W1 Fuel port. The FUEL nozzle registers as the heater's burners: a third registration, a sink at the firebox (a void at burner pressure). The fuel's LHV comes from the thermochemistry records by Hess's law (products CO2, H2O gas, SO2, N2); a component without a record is inert. Each slice the heater asks for the fuel of its uncapped set-point demand (never the capped one, so a short slice cannot ratchet the demand down); what was delivered caps the next slice's radiant duty, as the flue already couples across islands.

W2 Power. The radiant coil's registration gets an electric store (kind BURNERS) rated 0.8 kW/MW of design fuel; its draw follows the fuel fired. The heater's service enclosure gets the FE socket (`EquipmentServices.powered`, `PowerPorts.eligible`). Unpowered: a dispatch plan sets the coil's firing limit to 0 (the `ShaftPlan.FANS` path the air cooler uses), and the fuel demand to 0.

W3 Alarms. A world alarm table in `runtime/control` rebuilt at the engine cadence (state, not facts): per host, code, severity, since-tick. Sources: fired heater `NO POWER`, `LOW FUEL` (delivered < 98 % of demand), `NO FUEL` (nothing delivered, or LHV 0), `NO FUEL LINE`; every powered unit's existing `NO POWER`. The DCS and controller publications carry the active alarms of the hosts on their bus; each alarm is also a built-in input channel (`Sensor.Kind.ALARM`, 1 active / 0 clear).

W4 Display. Fluid screen of a heater: fuel rate, fired duty, efficiency, fan power, alarms. Simulator: fired duty and fan power on the heater's report (fuel composition stays off the sheet).

W5 Saves. New facts: the burner registration (fluid facts schema bump, corpus image), store kind `BURNERS` (appended). Old heaters: see F3.

## 4. Owner decisions

- F1 fuel delivery: (a) built-in fuel valve in the burner sink, set by the heater (recommended); (b) the heater drives the player's control valve on its fuel line, as a turbine's governor, with a `NO FUEL VALVE` alarm without one.
- F2 heating value: (a) Hess's law from thermochemistry, its revisions joining `MaterialCatalog.fluidRevision` (recommended); (b) a fixed natural-gas LHV for any gas.
- F3 heaters placed before this batch: (a) run free (fuel and power unmetered) until a fuel line or FE first reaches them, as decision A73 did for fans (recommended); (b) need fuel and power at once.
- F4 alarms read by controllers: (a) listed and readable as inputs on DCS and controller screens (recommended); (b) also a trip action: a controller closes its valve while a chosen alarm is active.

## 5. Owner answers (2026-11-09) and what was built

- F1: (a) built-in fuel valve. The burners are a void at the FUEL nozzle's port cell (heat side `BURNER`, partner the radiant coil), at 2.013 bar abs (`Burners.PRESSURE`); the run into it compiles with the valve `ValveSource.BURNER` stated toward the firebox.
- F2: prebuilt LHV, stored in the material DB (owner: "This LHV value goes into the material DB"). `tools/heating-values` computes Hess's law on each record's thermochemistry and writes a `combustion` sub-record (`hess-lhv-r1`) into 45 records and the water. `MaterialCatalog.Combustion`, `heatingValues(package)`. Methane 802.6 kJ/mol, 50.0 MJ/kg; cuts 40 to 44 MJ/kg; inerts 0. In no identity: open item O1.
- F3: (b) old heaters need fuel and power at once. The refresh adds burners to a heater placed before this batch and keeps its coils' facts.
- F4: (a) alarms listed and readable. `FiredHeaters.Alarm` NO POWER, NO FUEL, LOW FUEL (fired < 98 % of asked), FUEL NOT COMBUSTIBLE; air coolers, induced-draft towers and motors raise NO POWER. Built-in input channels `alarm_<name>` (`Sensor.Kind.ALARM`, 1 active / 0 clear); DCS and controller pages list the active alarms of the hosts on their bus. No trip action.
- Coupling: `FiredHeaters` on the shaft-train hooks. At commit, the radiant coil's demand (never the capped heat) and the fuel fired (mass flow × LHV/kg); at dispatch, the valve's mass target (demand fuel × 1.01 / LHV) and the coil's limit (`FiredCoil.limit`, fired heat's radiant share over the duty, 0 unpowered). A moved demand or firing wakes the other island.
- Power: electric store kind `BURNERS`, 0.72 kW shaft per MW fired over a 4-pole fan motor; the FE socket on the heater's service enclosure.
- Saves: the burner registration is a fact (appended enums `Side.BURNER`, `ValveSource.BURNER`); `FiredCoil.limit` is state (checkpoint format 41).
