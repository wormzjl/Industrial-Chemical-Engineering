# Flare stack: research and plan

Batch `2026-11-09-flare-stack`, branch `claude/furnace-fuel-power-requirements-f7a46e` (it reads the `combustion` sub-records of batch 2026-11-09-furnace-fuel-power). Status: decided 2026-11-09 (section 5); not built.

Owner request, 2026-11-09: a scalable flare stack whose capacity follows its size (research first); flared gas needs a fuel value; gas without one can be flared with gas that has one while the combustion temperature holds; on the data network with its status and a flow sensor; solids block it.

## 1. Research (real flares, simplified)

- **Combustion holds by heating value.** US rules state the flame condition as a floor on the net (lower) heating value of the gas burnt, per standard m³ (20 °C, 1 atm, 0.02404 m³/mol): 7.45 MJ/scm for an unassisted flare, 11.2 MJ/scm for steam- or air-assisted ([40 CFR 60.18](https://www.law.cornell.edu/cfr/text/40/60.18)). Refinery flares need 10.1 MJ/scm (270 Btu/scf) in the combustion zone for 98 % destruction ([40 CFR 63.670](https://www.law.cornell.edu/cfr/text/40/63.670)). The floor stands for flame temperature: an inert diluent (N2, CO2, steam) lowers the mixture's heat per volume and so its flame temperature, until the flame cannot sustain.
- On our material data (LHV per mole, the `combustion` sub-records): 7.45 MJ/scm = **179 kJ/mol** of vent gas. Methane 802.6 kJ/mol = 33.4 MJ/scm, so methane carries nitrogen down to 22 % methane; hydrogen 10.1 MJ/scm and CO 11.8 MJ/scm pass alone; N2, CO2, water 0.
- **Tip velocity limits capacity.** A flame on a fast jet lifts off and blows out. 40 CFR 60.18: below 18.3 m/s always stable; above it up to V_max, log10 V_max = (H + 28.8) / 31.7 (H in MJ/scm), at most 122 m/s. Methane: 92 m/s; a 7.45 MJ/scm mixture: 18.3 m/s. API 521 practice: Mach 0.2 continuous, about Mach 0.5 short emergency ([epcland flare design](https://epcland.com/flare-system-design/)); for methane Mach 0.2 is 90 m/s, the same as V_max.
- **Height limits capacity by radiation.** API 521 simple method: distance D = sqrt(F · Q / (4π K)) from the flame centre, F the radiated fraction (0.15 to 0.30; 0.2 for methane), K the allowed flux (1.58 kW/m² for continuous exposure of personnel at grade). Flame length L = exp(0.4562 ln Q − 5.3603) m, Q in W (worksheet form of the API 521 figure, [Scribd flare worksheet](https://www.scribd.com/doc/186208477/Flare-Modeling-Parameters)). Vertical flame, no wind: stack height H = D − L/2.
- **Numbers.** 200 MW (4 kg/s methane): D 45 m, L 29 m, H 31 m; tip at 92 m/s: 0.3 m. 20 MW: H 10 m, tip 0.1 m. 1 GW: H 70 m, tip 0.7 m.
- **Solids and liquids.** Real flares sit behind a knock-out drum: liquid carry-over rains burning droplets, solids plug the tip and its seal.

## 2. What the code has

Fired-heater burners (this branch) already: a void sink with a built-in valve, LHV per kg from the material DB, alarms read by controllers, built-in input channels. Equipment kinds are scaled on the block grid by diameter and length (`EquipmentShape`), drawn from parts (`UnitDesign`), with a layout table and corpus images. Every stream has the 100 m/s cap (owner decision 2026-10-19).

## 3. Proposal

- W1 Structure: `EquipmentShape.Kind.FLARE_STACK`, a vertical stack (riser, tip, ladder and platform at the tip, guy or derrick base by height) with one gas inlet nozzle at its base. Size per X3. The layout table, refresh and corpus images follow the equipment rules.
- W2 Flare node: the inlet registers a void at atmospheric pressure behind the tip (as the burners). Each slice reads the vent gas it received: its NHV (Σ y·LHV / 0.02404 m³/mol), its tip velocity, its heat release Q = ṁ · LHV/kg, and any solids. Status: FLARING, NO FLOW, FLAME OUT (NHV below the floor, X1), OVER CAPACITY (tip velocity above max(18.3 m/s, V_max), or Q above the radiation rating), BLOCKED (solids). What each status does to the flow: X2, X4, X5.
- W3 Data network: built-in inputs `flare_status` (state), `flare_flow` (mass flow), `flare_heat` (Q), `flare_nhv`; alarms FLAME OUT, OVER CAPACITY, BLOCKED in the alarm list.
- W4 Page and simulator: flow, heat release, NHV against its floor, tip velocity against its limit, rating; the simulator gets a MODELLED flare sink with the same checks (`FlowsheetCatalog`, palette tile, port rule, lang line).
- W5 Working animation (owner, 2026-11-09: "It needs working animation as well"): the client watches the nearest flares as it watches movers (one request a second, the server publishes status and heat release on its bucket; no process work on the client). FLARING: a flame at the tip whose length follows the heat release (the flame-length correlation, scaled to the stack), flickering, with flame and smoke particles; the pilot always shows a small flame. FLAME OUT: a pale plume of unburnt gas, no flame. NO FLOW, BLOCKED: the pilot only.
- Out of scope: assisted flares (steam, air), pilots and purge gas (pilots assumed lit, no fuel booked), smoke, wind.

## 4. Owner decisions

- X1 Combustion floor: (a) unassisted 7.45 MJ/scm (179 kJ/mol) (recommended); (b) refinery 10.1 MJ/scm; (c) adiabatic flame temperature of the gas burnt with air above about 1500 K (needs product heat capacities; more model).
- X2 Gas that cannot hold the flame: (a) the flare stops taking it (its inlet closes, FLAME OUT) (recommended: nothing unburnt leaves); (b) it vents unburnt with the FLAME OUT alarm (the real failure).
- X3 Size: (a) two dimensions, tip diameter and stack height; the rating is the smaller of the tip rating and the radiation rating (recommended); (b) one size, tip and height matched to one rating.
- X4 Above capacity: (a) the flare passes at most its rating (a built-in limit, OVER CAPACITY) (recommended); (b) it passes all with the alarm.
- X5 Solids: (a) blocked while the gas arriving carries solids, clears by itself (recommended); (b) latched until the player clears the tip.

## 5. Owner answers (2026-11-09)

- X1: (c) flame temperature. X1b: 1700 K. The gas burns while its stoichiometric adiabatic flame temperature with air at 298 K is at least 1700 K: the release Σ n·LHV heats CO2, H2O, SO2 and N2 (from the formula in each record's `thermochemistry`) and the air's N2, on the products' ideal-gas enthalpies from the material DB. Derivation: methane's inerting limits in Bulletin 627 (about 6 N2 : 1 CH4, 3.2 CO2 : 1 CH4) both give about 1700 K; the 7.45 MJ/scm floor gives about 1900 K. One threshold for every fuel is a simplification (hydrogen's real limit flame temperature is lower).
- X2: (b) gas below the threshold vents unburnt, FLAME OUT alarm; the ledger books it as vented, not burnt.
- X3: (b) one size; tip diameter and stack height matched to one rating (tip at 92 m/s methane, height by the 1.58 kW/m² radiation rule).
- X4: (a) a built-in limit passes at most the rating; OVER CAPACITY while it holds back.
- X5: (a) blocked while the gas arriving carries solids; clears by itself.
- Animation: W5.

## 6. As built (2026-11-09, uncommitted)

- Law (`science/fluid/network/Flare`): NHV per standard m³ (0.02404 m³/mol), stoichiometric flame temperature from the records' formulas (C, H, N, O, S) with the products' enthalpy rises to 1700 K as JANAF constants (not the material DB's ideal-gas functions: the criterion only needs the rise to the one threshold), stable velocity max(18.3, min(122, 10^((NHV+28.8)/31.7))) m/s, rating = methane at its stable velocity through the tip, stack height by 1.58 kW/m² at the base. `Flare.gas` gives a gas's heat, NHV, flame, tip density and limit; the engine and the simulator share it.
- Structure (`FlareStackDesign`, `ProcessUnit.flareStack`): tip 0.1 to 0.6 m in 0.05 m steps (stack 10 to 65 m; 0.3 m: 212 MW, 32 m), tapered foot to 3 m, riser, windshield, three pilots. The gas inlet (`Role.FLARE_GAS`, front face) stands at 4 m, over the foot, so a pipe's largest neck clears it (plan said "at its base"). No ladder or platform yet.
- Engine (`runtime/fluid/Flares`): the tip is a void behind the inlet with a built-in valve (`ValveSource.FLARE`); at dispatch its mass target is the limit (0 while solids arrive), at commit its status: NO FLOW, FLARING, FLAME OUT, OVER CAPACITY (flow at 99 % of the limit), BLOCKED. Burnt and vented gas both leave through the void; the heat released is reported on the page and the network, not booked in the ledger (X2's "booked as vented" is not built).
- Data network (`BuiltInInputs`): `flow_rate` (mass flow), `flaring` (1 or 0), alarms `alarm_flame_out`, `alarm_over_capacity`, `alarm_blocked` (`EquipmentAlarm`, shared with the heaters). Plan W3's `flare_heat` and `flare_nhv` inputs are left out; they stand on the page.
- Page (`FluidDeviceScreen`): Heat released (MW, rating in the hover), Heating value (MJ/m³), Flame (against 1700 K), Most flow; the status word on the display.
- Animation (`client/FlareFlames`, packets `flare_watch`, `flare_state` on `flare-1`): published on the 5 s presentation cadence; flame particles along the flame length of the heat released, smoke over it, pilots always, a white plume on FLAME OUT.
- Simulator: tile `flare` (MODELLED, `FlareUnit`): door Tip, readings heat, NHV, flame, most flow; warnings FLAME OUT, over capacity, solids. Sizing `FlareSizing` (tip on the grid, Auto with the 20 % spare; limits FLARE_FLAME, FLARE_CAPACITY, FLARE_SPARE), because `SizingCoverageTest` requires a size class for a modelled kind. Symbol baked into `symbols.png`.
- Saves: new enum constants only (`Kind.FLARE_STACK`, `Role.FLARE_GAS`, `ValveSource.FLARE`), saved by name; no field changed, so no corpus image.
