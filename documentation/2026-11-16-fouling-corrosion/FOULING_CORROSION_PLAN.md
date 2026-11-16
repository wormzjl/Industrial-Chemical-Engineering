# Fouling, corrosion and erosion system: plan (revision 12)

Batch `2026-11-16-fouling-corrosion`, branch `claude/fouling-corrosion-system-ba0275`, cut from `main` at `f305ac6b0` (0.32.0), rebased on `main` at `66a87f9d5` (0.33.0) on 2026-11-16 (the owner's word). Status: plan, no code.

Revision 12 (owner, 2026-11-16: "merge and go with recommended. Show the HX's overall UA in its GUI", then "Start it", decision D16): a heat side's page reads the unit's overall UA and what its deposits leave of the clean UA, in place of "Film left"; the halved end deposits and the pool's still-liquid film stay (sections 12, 15, 17).

Revision 11 (owner, 2026-11-16: "Use C then, same to the viscosity, it can tolerate lag", decision D15): the liquid film follows the liquid's thermal conductivity and heat capacity as well as its viscosity, against water at 20 °C and 5000 W/m²K (sections 3.2, 12, 15).

Revision 10 (owner, 2026-11-16: "Yes, halve them", decision D14): the placeholder's end deposits are half the TEMA anchors, the deposit step 5e-5 and `FOULED` from 0.0005 (sections 4, 12, 15).

Revision 9 (owner, 2026-11-16: "build it now", decision D13): a liquid's film follows its viscosity at every sampled point of the heat curve, so a deposit weighs on heavy oil as it does in a real plant (sections 12, 15).

Revision 8 (owner, 2026-11-16: "10x", decision D12): corrosion and fouling ten times slower by default, the corrosion year 200 process hours and the fouling time constant 100 (sections 6.5, 8, 12, 13.1, 15).

Revision 7 (owner, 2026-11-16: "when column handles solids, add both fouling and corrosion to it depending on the solid fraction"): a running column's trays foul and its wall is lost with the solid share of what it takes (section 13.1, WP8).

Revision 6 (owner, 2026-11-16: "repairs needs to be performed with the constructor"): constructed equipment is repaired by a Repair key in the Equipment Constructor, not by the repair kit (sections 9, 10, 14, 17, 18).

Revision 5 (owner, 2026-11-16: "how you deal with the combined P, T effect?"): the hydrogen limit falls with hydrogen pressure and the oxygen limit falls with temperature (sections 5 and 6.2).

Revision 4 (owner, 2026-11-16: "how about hot hydrogen service and hp oxygen service?"): two mechanisms added, hydrogen attack (M10) and oxygen fire (M11), each a limit per material (sections 3.1, 5, 6, 7).

Revision 3 (owner, 2026-11-16): a column stops only when a compound solidifies inside it; a frozen feed melts as today (section 13, WP7).

Revision 2 (owner, 2026-11-16): four materials instead of eight, one per structure; particulate erosion, with control valves; columns pass inert particles to the bottoms and stop only for freezing. Changed sections: 1, 4, 5, 6.3, 7, 9, 12 to 18.

## 1. The request (owner, 2026-11-16)

- Fouling raises pressure drop and lowers the heat transfer coefficient. The fouling mechanism comes later, with the reaction module.
- Corrosion leads to leaks and breakdown, and interacts with the pollution system. Sounds: propose first, not heavy.
- Broken equipment is repaired only when idle and empty. Equipment damaged by an explosion cannot be repaired; it is deconstructed.
- Probably machines only: every pipe section would cost too much CPU.
- All current process equipment is carbon steel. Add the other common refinery materials, and add them to the constructor.
- Corrosive pollution adds to equipment corrosion.
- Research the corrosion types and rates of a refinery as a matrix. Add a material selection tab to the process simulator.
- Performance over realism and accuracy.

After the first plan:

- "Need a smaller list of materials, keep in mind this is not for full realism."
- "Allow distillation column to handle inert particles and pass particulates to bottom output, only stop when something freezes."
- "Add particulate erosion to equipment, especially control valves."
- "How about hot hydrogen service and hp oxygen service?"
- "Repairs needs to be performed with the constructor."
- "When column handles solids, add both fouling and corrosion to it depending on the solid fraction."

## 2. What exists today

| Piece | Where | What it does | Gap |
|---|---|---|---|
| Machine wear | `science/machine/Wear`, `runtime/fluid/MachineHealth` | Health 1 to 0 per mover and turbine from six faults; `FAILED` closes it; one repair kit restores it, running or not | No wall, no chemistry, no solids fault |
| Blast breach | `runtime/pollution/Breaches`, `FluidDeviceSpec.breached` (facts schema 26) | A blast opens a closed vessel (vents, spills) and fails a machine; a repair kit closes it | Repairable; the owner now wants it final |
| Corrosive air | `science/pollution/Channel.CORROSIVE`, `PollutionService.index` | A cube's corrosive index | Nothing reads it for equipment |
| Heat films | `HeatCurve.Films.DEFAULT` (2000, 5000, 100 W/m²K) | Constant, "with its share of the wall and fouling" | No fouling state |
| Sulfur | assays: `sulfur_mass_percent`, metadata only | H2S, mercaptans, COS, SO2 are real compounds | The crude cuts carry no sulfur and no acid number |
| Solids | `SolidInventory`, `SlurryTransport`, `SolidMobility`, `PipeTransfer.Stream.solids` | Inert particles ride the liquid, settle, deposit, clog filters | They wear nothing |
| Running column | `ColumnOperation` | An inert solid waits at its inlet and a cycle moves it to the bottoms; a crystal melts into the inlet's account as it arrives | Never stops for freezing |
| Simulator column | `ColumnBlockSolve.solve`, line 119 | Refuses every feed that holds solids (`column.solids`: "This column takes no solids.") | The owner wants inert particles to pass |
| Control valve | `FlowControl.ControlValve`; `PipeObservation.valveDrop`, `density` | Regulates; its row reports its drop | No wear |
| Construction | `EquipmentStructure`, the constructor | Free: no items are consumed | An alloy has no price (owner: cost index only) |

## 3. Physics, in numbers

### 3.1 Corrosion

A wall loses thickness at a rate set by its material and the fluid on it. Design adds a corrosion allowance (CA): 3 mm on carbon and chrome steel, 1.5 mm on stainless and nickel alloy. A rate of 0.1 mm/y or less is acceptable (30 years on 3 mm). When the allowance is gone the wall no longer holds its pressure.

| Service | Carbon steel | Right material |
|---|---|---|
| Crude at 370 °C, 0.6 wt% S | 1.3 mm/y: 2.3 years | Chrome steel 0.08 mm/y; stainless 0.013 |
| Heavy crude at 350 °C, acid number 3 | 1.5 mm/y: 2 years | Stainless 0.03 mm/y |
| Sour water, H2S 1 bar, 60 °C | 0.3 mm/y: 10 years | Stainless 0.003 mm/y |
| Wet CO2, 1 bar, 60 °C | 1.4 mm/y | Stainless 0.007 mm/y |
| Open cooling water, 35 °C | 0.3 mm/y: 10 years | Stainless |
| Hydrogen at 30 bar, 300 °C | 3.3 mm/y equivalent: under a year | Chrome steel to 524 °C at 30 bar; stainless above |
| Hydrogen at 30 bar with H2S, 370 °C | 1.3 mm/y or more | Stainless (chrome steel 0.8 mm/y) |
| Oxygen at 10 bar | burns through in minutes | Stainless to 14 bar; nickel alloy to 69 bar |
| Dry hydrocarbon gas, clean liquid, steam | 0 | Carbon steel |

### 3.2 Fouling

A deposit of resistance `Rf` (m²K/W) sits in series with a side's film: `h' = h / (1 + h·Rf)`. Its thickness `δ = Rf·k_d` (`k_d` = 1 W/mK) narrows the passage: at the same flow the drop rises by `(d / (d − 2δ))⁵`.

| Rf (m²K/W) | Liquid film 2000 | Two-phase 5000 | Gas 100 | δ | Drop, 20 mm passage |
|---|---|---|---|---|---|
| 0 | 2000 | 5000 | 100 | 0 | × 1.00 |
| 0.0002 | 1429 | 2500 | 98 | 0.2 mm | × 1.11 |
| 0.0005 | 1000 | 1429 | 95 | 0.5 mm | × 1.29 |
| 0.001 | 667 | 833 | 91 | 1 mm | × 1.69 |
| 0.002 (cap) | 400 | 455 | 83 | 2 mm | × 3.05 |

Since D15 the reference liquid film is 5000 W/m²K (water at 20 °C), so a clean warm-water side reads the two-phase column; the 2000 column stands for a light oil, which D15 gives about 1460 W/m²K.

### 3.3 Erosion

Particles cut metal in proportion to their mass flow and to their speed to the power 2.6 (DNV RP O501 for steel). Speed decides almost everything:

| Place | Speed | Relative loss, same solids |
|---|---|---|
| Vessel | about 0 | 0 |
| Exchanger tube, line | 2 m/s | 1 |
| Control valve trim at a 1 bar drop, liquid of 800 kg/m³ | 16 m/s | 220 |
| Control valve trim at a 5 bar drop | 35 m/s | 1 700 |
| Pump impeller tip | 30 m/s | 1 100 |

So a throttling valve wears first, a pump second, a tube slowly, a vessel not at all. The trim speed of a valve is `v = sqrt(2 Δp / ρ)` from its own drop.

## 4. Scope and CPU

**Decided (D1): constructed equipment, and control valves for erosion.** Pipes, other fittings and one-block tanks neither corrode, foul nor erode (the owner's pollution decision of 2026-11-15: "pipes get no damage meter, for the sake of simplicity").

| Device | Corrosion | Erosion | Fouling |
|---|---|---|---|
| Closed vessel of a structure (drum, sphere, column shell, kettle shell, converter gas tank) | wall | none (no speed) | |
| Heat side (exchanger, kettle tubes, air cooler, fired coils) | wall | wall, by its passage speed | `Rf` |
| Machine with a health record (pump and compressor casings, vacuum pumps, steam turbine; in-line pumps) | wall, casings only | health: a new `SOLIDS` fault | |
| Control valve (`FlowControl.ControlValve`, a pipe fitting) | | trim health | |

Not in this batch: open structures (floating-roof tanks, cooling towers, chimneys, flare stacks; their failure is a ground spill), drawing-only kinds (reactors, cyclones: with the reaction module), pressure regulators and hand valves.

Cost, per device and per cadence (5 s), on the server thread, as `MachineHealth` books today:

- One digest of its node's last committed state: three dot products over the compound list (at most about 50 compounds).
- Nine closed-form corrosion laws and one erosion law on the digest; one table row for its material.
- Erosion is read only on islands that hold or are fed solids (`SolidMobility.monitored`); every other island skips it with one test.
- Nothing in the solver. Nothing per slice: a commit only stores a reference to the island's last result.
- Bound: at most 256 devices evaluated per cadence, round-robin; a device not evaluated keeps its last rate. About 1 µs per device, so 0.3 ms per 5 s at the bound.

The solver sees a change only when a wall or a trim fails (once) or a fouling level steps (steps of 5e-5 since D14: at most 23 to the highest end value, 0.00115, 40 to the cap).

## 5. Materials of construction

Named `Metallurgy` in code, because "material" already means a compound (`MaterialCatalog`). An enum saved by name; append-only.

| Constant | Shown as | Stands for | CA | Limit (°C) | Cost index | Its place |
|---|---|---|---|---|---|---|
| `CARBON_STEEL` | Carbon steel | the default; every structure today | 3 mm | 540 | 1 | Dry and clean service |
| `CHROME_STEEL` | Chrome steel | 9Cr-1Mo | 3 mm | 650 | 2 | Hot sulfur oil; heater tubes |
| `STAINLESS_STEEL` | Stainless steel | 316L | 1.5 mm | 815 | 4 | Wet acid gas, naphthenic acid, hot H2S |
| `NICKEL_ALLOY` | Nickel alloy | Alloy 625 | 1.5 mm | 980 | 15 | Everything, at a price |

Service limits (section 6.2, M10 and M11). Each is a line in pressure and temperature, not one number.

Hot hydrogen, from `p_H2` = 7 bar: `T_H = max(T_floor, T_7 − s · log10(p_H2 / 7 bar))`.

| Material | `T_7` | `s` (K per decade) | `T_floor` | at 7 bar | at 30 bar | at 100 bar | at 200 bar |
|---|---|---|---|---|---|---|---|
| Carbon steel | 260 °C | 45 | 205 °C | 260 °C | 232 °C | 208 °C | 205 °C |
| Chrome steel | 600 °C | 120 | 450 °C | 600 °C | 524 °C | 461 °C | 450 °C |
| Stainless steel, nickel alloy | none | | | no limit | | | |

Oxygen: `p_limit = max(0.3 bar, p_150 · 2^(−(T − 150 °C)/100 K))` above 150 °C, `p_150` below it. The floor of 0.3 bar keeps air at atmospheric pressure free at every temperature (hot air is M8's).

| Material | `p_150` | at 250 °C | at 350 °C |
|---|---|---|---|
| Carbon steel, chrome steel | 2 bar (air to about 10 bar) | 1 bar | 0.5 bar |
| Stainless steel | 14 bar | 7 bar | 3.5 bar |
| Nickel alloy | 69 bar | 35 bar | 17 bar |

**Decided (D2, revision 2): one material per structure.** One dropdown in the constructor, one shape field. Every device of the structure reads it; an exchanger is rated on the worse of its two streams.

**Decided (O2, owner 2026-11-16): the cost index only.** Construction stays free in this batch; the index is shown in the simulator and on the datasheet, and a later survival-supplies batch charges it.

## 6. The corrosion matrix

Sources for the forms and anchors: API RP 571 (damage mechanisms), API RP 581 Part 2 Annex B (thinning rate tables), API RP 939-C and the modified McConomy curves (sulfidation; the 1986 revision divides the 1963 curves by 2.5; scatter about one order of magnitude), API RP 932-B (ammonium bisulfide), de Waard–Milliams 1975 (CO2), API Std 530 (tube metal temperature limits), ISO 9223 (atmospheres), DNV RP O501 (erosion), API RP 941 (the Nelson curves for hydrogen), Couper–Gorman (H2/H2S), EIGA Doc 13 and CGA G-4.4 (oxygen: exemption pressures per alloy). The numbers below are game-level fits to those anchors, not the tables themselves.

### 6.1 The digest of a device's fluid

From the device's node (a vessel: its own; a heat side or machine: the hotter end node), at its last committed state:

| Symbol | Meaning | From |
|---|---|---|
| `T` | temperature, K | the state |
| `wet` | a free aqueous liquid is present | the state's aqueous slot above a trace |
| `oil` | a hydrocarbon liquid is present | the state's liquid slot above a trace |
| `S` | sulfur of the oil, wt% | Σ mass × sulfur share over the liquid's compounds; H2S, mercaptans and COS by their formulas, the crude cuts by their `corrosion` sub-record |
| `A` | acid number of the oil, mg KOH/g | mass-weighted `corrosion.acid_number` |
| `p_i` | `y_i · P`, bar, for H2S, CO2, SO2, NH3, O2, H2 | overall mole fractions; the compounds found by formula, as `Releases.so2Reference` finds SO2 |

### 6.2 Mechanisms (carbon steel rate, mm/y)

| # | Mechanism | Acts when | Carbon steel rate |
|---|---|---|---|
| M1 | Sulfidation | `T` ≥ 503 K and (`oil` with `S` > 0, or `p_H2S` > 0) | `1.3 · exp(−8698 (1/T − 1/643)) · (S_e/0.6)^0.4`, `S_e = clamp(max(S, 60 p_H2S), 0, 5)`. 260 °C: 0.08; 288 °C: 0.18; 370 °C: 1.3; 400 °C: 2.4 |
| M2 | Naphthenic acid | `oil`, `A` ≥ 0.5, 493 K ≤ `T` ≤ 673 K | `0.5 · min(A, 6) · clamp((T − 493)/130, 0, 1)`. Acid number 3 at 350 °C: 1.5 |
| M3 | Sour water (acidic) | `wet`, `p_H2S` > 0, `T` < 423 K | `min(1.5, 0.3 · p_H2S^0.5)` |
| M4 | Wet CO2 | `wet`, `p_CO2` > 0 | `min(5, 0.3 · 10^(5.8 − 1710/min(T, 353) + 0.67 log10 p_CO2))`. 1 bar, 60 °C: 1.4 |
| M5 | Ammonium bisulfide | `wet`, `p_NH3` > 0 and `p_H2S` > 0 | `min(5, 2 · min(p_NH3, p_H2S)^0.5)` |
| M6 | Acid condensate | `wet`, `p_SO2` > 0 | `min(5, (p_SO2/0.001)^0.5)` |
| M7 | Aerated water | `wet`, `p_O2` > 0 | `0.25 · min(2, p_O2/0.21) · 2^((min(T, 353) − 298)/30)` |
| M8 | Overheating | `T` above the material's limit | `0.25 · 2^((T − T_limit)/25)`, for the material itself |
| M9 | Corrosive air (outside) | the corrosive index `I` of the structure's cube ≥ 0.01 | `0.5 · min(I, 5)` |
| M10 | Hydrogen attack | `p_H2` ≥ 7 bar and `T` above the material's hydrogen limit `T_H(p_H2)` (section 5) | `0.5 · 2^((T − T_H)/25)`, for the material itself. Carbon steel at 30 bar: 250 °C 0.8, 300 °C 3.3; at 100 bar and 250 °C: 1.6 |
| M11 | Oxygen fire | `p_O2` above the material's oxygen limit `p_limit(T)` (section 5) | `50 · p_O2/p_limit`, for the material itself: the wall is gone in minutes of play |

The inside rate is the largest of M1 to M8, M10 and M11 (the governing mechanism, as API 581 takes it); the status names it.

M10 and M11 are not thinning in a real plant. Hydrogen attack is cracking after an incubation time, and an oxygen fire is an ignition. Both are booked as an equivalent wall loss so that one wall record and one failure path serve every mechanism. Pressure and temperature act together in every mechanism. M1 to M7 are each a pressure term (a partial pressure `y · P`) times a temperature term (Arrhenius in M1, a ramp in M2, de Waard–Milliams in M4, a doubling per 30 K in M7). M10 is the Nelson curve as a line: the limit temperature falls with the logarithm of the hydrogen pressure, down to a floor; its steep part below 7 bar is a cut-off. M11 is the exemption pressure, halved for every 100 K above 150 °C. The oxygen rule does not read speed or particles. The total is the inside rate plus M9 plus erosion (section 8).

### 6.3 Material factors (rate = carbon steel rate × factor)

| Mechanism | Carbon steel | Chrome steel | Stainless steel | Nickel alloy |
|---|---|---|---|---|
| M1 Sulfidation | 1 | 0.06 | 0.01 | 0.01 |
| M1 with `p_H2` ≥ 7 bar (H2/H2S) | 1 | 0.6 | 0.01 | 0.01 |
| M2 Naphthenic acid | 1 | 0.6 | 0.02 | 0.01 |
| M3 Sour water | 1 | 1 | 0.01 | 0.005 |
| M4 Wet CO2 | 1 | 0.3 | 0.005 | 0.002 |
| M5 Ammonium bisulfide | 1 | 1 | 0.2 | 0.02 |
| M6 Acid condensate | 1 | 1 | 0.1 | 0.02 |
| M7 Aerated water | 1 | 0.6 | 0.005 | 0.002 |
| M9 Corrosive air | 1 | 0.6 | 0.01 | 0.005 |
| Erosion (section 8) | 1 | 0.8 | 0.5 | 0.4 |

Each material keeps a case only it answers at the lowest cost: chrome steel for hot sulfur oil, stainless for acids, wet acid gas, hot hydrogen with H2S and oxygen to 14 bar, nickel alloy for oxygen above that, for ammonium bisulfide and acid condensate above 0.5 mm/y on carbon steel.

Later, with their species: HCl overhead corrosion and chloride cracking, amine and caustic cracking.

### 6.4 Data

- `corrosion` sub-record on the twelve crude cut datasets: `sulfur_mass_percent`, `acid_number`, `fouling`, `estimated`, `source`, `revision`. In no identity, as `hazard` is. Sulfur and the acid number rise with the cut. All marked estimated.
- **Decided (D9, after WP1's fit): the cuts' values as fitted; the assays' `sulfur_mass_percent` stays display metadata.** One set of twelve values cannot reproduce the four assays within 20 %: the cuts are shared, the heavy crude holds more of every heavy cut than the dilbit, and their specifications are 3 and 3.74 wt%. As fitted (`tools/fouling-corrosion/fit-cuts.mjs`, revision `corrosion-fit-r1`): sulfur 0.02 wt% for cuts 1 to 7, then 0.057, 1.2, 3, 4.7 and 6; acid number 0 to cut 9, then 0.72, 2.5 and 4.5. Whole crudes: sulfur 0.28 (light, specification 0.2), 1.03 (medium, 1.5), 2.55 (heavy, 3), 2.13 (dilbit, 3.74); acid number 0.08, 0.45, 1.57, 1.24. The sulfidation rate goes with sulfur to the power 0.4, so the largest miss (dilbit, −43 %) moves its rate by −20 %; the acid targets were this plan's own estimates. Matching each assay would need sulfur as a carried species, which the network does not have.
- Real compounds need no record: their sulfur is in their formula.

### 6.5 Time

**Decided (D3): one corrosion year is 20 process hours** (5 hours of play at `fluid.timeScale` 4), config `corrosion.yearProcessHours`. Loss booked: `rate × Δt_process / year`.

**Decided (D12, owner 2026-11-16: "10x"): one corrosion year is 200 process hours** (50 hours of play at `fluid.timeScale` 4), ten times D3's 20 h; the fouling time constant is 100 process hours, ten times O1's 10 h (section 12). Why: at D3's and O1's pace a carbon steel wall in hot crude was through after 11.5 hours of play and a clean exchanger fouled in 2.5, too fast for a plant to be built and run. Erosion of walls and valve trims is booked per corrosion year, so it slows with it. Only the two defaults change (`WallState.DEFAULT_YEAR_PROCESS_HOURS`, `FoulingLaw.DEFAULT_TIME_PROCESS_HOURS`); no rate law, factor, asymptote, cap, saved field or format. Saved loss and deposits are amounts and stand; they advance more slowly. The pure-law gates (`FoulingAndWallTest`, `ErosionLawTest`) still check D3's rows at an explicit 20 h; each life below is ten times theirs.

| Rate | Carbon steel wall (3 mm) lasts (D12, 200 h year) |
|---|---|
| 0.1 mm/y (acceptable) | 6000 process hours: 1500 hours of play |
| 0.3 mm/y | 2000 process hours: 500 hours of play |
| 1.3 mm/y (crude at 370 °C) | 460 process hours: 115 hours of play |
| 5 mm/y (the cap of most rows) | 120 process hours: 30 hours of play |

## 7. The wall: state, failure, pollution

One record per corroding device: `loss` (mm, a fact), `rate` and `mechanism` (state). `u = loss / CA`.

| `u` | Word | Effect |
|---|---|---|
| < 0.5 | none | |
| 0.5 to 0.9 | `THIN WALL` | Status line; the Integrity group's meter |
| 0.9 to 1 | `WEEPING` | Drip particles and the weep sound at the structure. No fluid leaves |
| ≥ 1 | `LEAKING` (vessel, heat side), `FAILED` (machine) | The device fails by the failure its kind has today |

**Decided (D4): a wall that is through fails by the existing failures; no new solver law.**

- Closed vessel: `FluidDeviceSpec.breached` is set through the registry's respecify edit, as a blast sets it. The vessel stands open: it vents to the atmosphere and spills by the open-vessel law, and `Releases` already turns that into pollution. This is the leak.
- Machine: health 0 through `MachineHealth.breach`; its `FAILED` plan closes it.
- Heat side: its connection is closed (the registry edit a shut fitting uses) and its holdup is released once through `FluidWorldAuthority.releaseContents`, as a burst pipe's is. Status `LEAKING: tube leak`.

- A wall lost to an oxygen fire (M11 governing at the failure) also counts as one of the plant's flames for the next pollution pass (`FluidWorldAuthority.flames`), so a flammable cube round it ignites by the existing rule.

A graded pinhole leak (an orifice row on the node) is not built: it would add a boundary law to the step for a small effect.

## 8. Erosion by particles

One law, `ErosionLaw`, on what a connection carried in its last committed slice: the solid mass share `φ` of the stream (`PipeTransfer.Stream.solids` over its mass) and a speed `v`.

`E = 1 mm/y × (φ / 0.01) × (v / 10 m/s)^2.6`, on carbon steel; other materials by the Erosion row of 6.3. Crystals do not count: they melt. Every inert solid counts alike (a solid record holds no hardness; particle size is not read).

| Device | Speed `v` | Books into | At 1 wt% solids |
|---|---|---|---|
| Heat side | mass flow over density and the passage's section area | its wall, added to corrosion | 2 m/s: 0.015 mm/y; 5 m/s: 0.16 mm/y |
| Control valve | `sqrt(2 Δp / ρ)` from its row's `valveDrop` and `density` | its trim health | below |
| Pump (casing or in-line), liquid ring pump | none: a `Wear.Fault.SOLIDS` of the erosion class, intensity `clamp((φ − 0.001)/0.05, 0, 1)` | its health | 0.18 of the erosion life: 44 process hours |
| Vessel | 0 | | none |

A compressor and a dry screw already refuse solids (`InletPhase.decide`).

**Control valve trim.** A new health record per control valve, 1 (new) to 0, kept by `MachineHealth` beside the pumps' (a fact below 1). Rate per process second: `(φ / 0.01) × (v / 30)^2.6 / (year)`, the corrosion year of 200 process hours (D12; 20 h under D3, a tenth of each life below).

| Drop (liquid of 800 kg/m³) | Trim speed | Life at 1 wt% | Life at 5 wt% |
|---|---|---|---|
| 0.2 bar | 7 m/s | 8600 process hours | 1720 |
| 1 bar | 16 m/s | 1050 process hours | 210 |
| 5 bar | 35 m/s | 130 process hours | 26 |
| 20 bar | 71 m/s | 21 process hours | 4 |

- From health 0.25: `ERODED TRIM` on its status line.
- At 0: `TRIM FAILED`. The valve no longer throttles: it stands as an unconfigured control valve does (`ControlValve.unconfigured()`, open), its settings kept. The loop it served loses control; nothing leaks. A valve that the engine plans (a burner's, a column port's) fails the same way, so its unit sees an uncontrolled line; a column's port valve failing trips the column by its existing closure check.
- Repair: a repair kit on the valve's pipe block while no flow passes it (section 10).
- The gas of a gas valve gives the same law with its own density; a gas line carries no solids (they ride the liquid), so it reads 0.

**Decided (D5): a failed trim fails open, not shut.** Erosion widens a trim. It also keeps the failure inside the existing control law: no new mode.

## 9. Blast damage is final

`Breaches.breach` additionally marks every device of the structure `wrecked` (a fact). A wrecked device keeps the effect it has today (vessel open, machine failed; a heat side closed as in section 7), refuses the constructor's Repair (`WRECKED: deconstruct and rebuild`) and is removed only by the constructor's deconstruction.

A vessel breached before this batch (no `wrecked` field) reads as not wrecked: it stays repairable. That is the behaviour before the field existed.

## 10. Repair and cleaning: idle and empty

**Decided (D8, owner 2026-11-16): constructed equipment is repaired with the Equipment Constructor.** The constructor's group that names the nearest structure on its line (today "Deconstruction") gets a `Repair` key beside `Deconstruct`. The server selects the target again, checks reach and build rights as Deconstruct does, and queues one engine event for the structure; the answer comes on the engine schedule as a chat line and on the group's status row. It is accepted only when every device of the structure is:

- **idle**: no flow through it in its last committed slice (|mass flow| below the solver's trace), a machine at no speed;
- **empty**: a vessel holds less than 1 % of its volume as liquid and stands within 0.2 bar of the atmosphere; a heat side, a machine or a valve has no inventory, so idle is enough.

Then: wall loss 0, health 1, breach closed, a failed heat side opened. Otherwise the reply names what is missing (`NOT IDLE`, `NOT EMPTY`, `WRECKED`, `NOTHING TO REPAIR`). Repair costs no item, as building costs none (O2); a later survival-supplies batch may charge it.

The repair kit (owner, 2026-11-16: "Keep the kit"):

- On a cell of constructed equipment it is refused and kept: "Repair this with the Equipment Constructor." This ends today's rules: a kit on a pump or compressor casing, on a steam turbine, or on a breached vessel.
- It stays the repair of what the constructor does not build: an in-line pump or compressor and a control valve's trim, both pipe blocks. It is accepted only while no flow passes the device.

Cleaning: a `Clean` key on a heat unit's page queues an event; same idle-and-empty rule; no item; fouling of both sides to 0.

## 11. Corrosive air

At the cadence the service reads `PollutionService.index(dimension, origin, CORROSIVE)` of the cube that holds the structure's origin: one read of the installed field per structure, never a chunk load. M9 adds to every device of the structure. Off with `pollution.enabled` or `corrosion.external` false.

## 12. Fouling

- State: `Rf` per heat side, a fact, 0 to 0.002 m²K/W.
- Effect: `HeatCurve.Films.fouled(Rf)` (section 3.2) and the passage's section diameter less `2 Rf k_d`.
- The graph changes only in steps of 0.00005 m²K/W (D14; 0.0001 before): when a side's level changes, one queued respecify edit gives it its new films and diameter. Between steps the solver's inputs are bit for bit the same.
- Sizing: the simulator's exchanger, cooler and heater rows size on a design `Rf` per side (the service's asymptote), so a unit sent to the block meets its duty dirty. This closes the two `SIZING_PLAN.md` rows. Since the halved end deposits (D14) the crude sample's E-101 needs 1.60 times its clean surface on the design deposit (1034 against 647 m²; 2.20 under D13's full anchors, 2.36 on the fixed film), E-102 1.11 times (1129 against 1016 m²; 1.22, 1.30 before) and the column condenser 1.26 times (640 against 509 m²; 1.50 before). Since the conductive film (D15) E-101 needs 1.46 times (1235 against 847 m²) and E-102 1.10 times (1313 against 1199 m²).
- Words: `FOULED` from `Rf` ≥ 0.0005 (D14: the reference 2000 W/m²K liquid film down to a half; 0.001, a third, before); the heat page shows `Rf`, the unit's overall UA and the UA left behind both sides' deposits (D16).

**Decided (O1, owner 2026-11-16): a placeholder grows `Rf` until the reaction module exists.** Kern–Seaton, `dRf/dt = (Rf∞ − Rf)/τ` while the side flows, `τ` = 100 process hours (config; D12, 10 under O1: 25 hours of play at time factor 4 to 63 % of `Rf∞`), `Rf∞` from the digest: half the anchor (D14, `FoulingLaw.PLACEHOLDER_SHARE`); the anchors stay in the code and the datasets at their TEMA values:

| Stream on the side | Anchor (m²K/W) | Game's `Rf∞` (m²K/W), half | Source |
|---|---|---|---|
| Gas, steam, light clean liquid | 0 | 0 | |
| Water (`wet`, mostly aqueous) | 0.00035; 0.0007 above 50 °C | 0.000175; 0.00035 above 50 °C | TEMA tower water 0.00018 to 0.00035 |
| Crude and heavy oil below 260 °C | 0.0005 | 0.00025 | TEMA crude 0.0004 to 0.0012 by temperature and speed |
| Crude and heavy oil from 260 °C | 0.0009 | 0.00045 | |
| Residue (heaviest two cuts above half the liquid) | 0.0018 | 0.0009 | TEMA fuel oil 0.0009, vacuum residue 0.0018 |
| Any liquid carrying solids | + `0.02 φ`, at most + 0.0005 | + `0.01 φ`, at most + 0.00025 | particulate fouling; 1 wt% adds 0.0002 to the anchor |

"Heavy" is read from the per-cut `fouling` number of the `corrosion` sub-record. The reaction module later replaces this rate source and nothing else.

## 13. Columns and particles

Expected: a distillation column is a liquid path from its feed tray to its sump. Inert particles ride the liquid down the trays and leave with the bottoms; they change no equilibrium. A column stops for solids only when something freezes on its trays.

Today (section 2): the running column does the first part. The simulator's column refuses every solid. Neither stops for freezing: the running column melts an arriving crystal into its account, and the V3 solve has no crystal equations.

Proposal:

1. **Simulator column** (`ColumnBlockSolve.solve`): the feed's inert solids (`solids().withoutCrystals()`) go to the bottoms stream unchanged in amount; they enter at the feed temperature and leave at the bottoms temperature, and that sensible heat (`Σ m c_p (T_bottoms − T_feed)`) is added to the reboiler duty. A column with no bottoms product refuses as today. The same holds for a steam row's and a pumparound return's solids.
2. **A frozen feed is not a stop.** A crystal that arrives melts into the feed, in both columns: the running column as today (`ColumnOperation.melted`), the simulator column the same way (the crystal's moles join its compound, the stream's enthalpy already holds the crystal's).
3. **Solidification inside the column is the stop.** After a solve, each product is flashed on the network's thermo already (`ColumnSolve`, one flash per product). A product whose flash holds a crystal phase means a compound solidifies in the column: the top product is its coldest liquid, the bottoms its heaviest. The simulator refuses the run with `column.frozen` ("<compound> solidifies in this column at its <product>."). The running column raises a new `Condition.FROZEN` of rank `TRIP`: the cycle takes nothing, as `CLOSURE` does, until a solve of the state taken shows no crystal.
4. `column.solids` and its lang line are removed.

**Decided (D6, owner 2026-11-16: "only when solidification happens inside column").** A column stops for solids only when a compound solidifies inside it. Arriving crystals and inert particles never stop it.

Nothing in the V3 solver changes: particles are not on its axis.

### 13.1 A column that carries solids fouls and wears

Expected: particles that ride the liquid down a column settle on its trays and in its downcomers, so the trays pass less vapour and liquid before they flood; and they scour the shell and the trays (erosion, and corrosion under the deposit). Both go with how much solid the column takes.

Today: a column is not a fluid device (`EquipmentShape.fluidDevice()` is false for `COLUMN`), so the wall records of section 7 do not reach it, and nothing fouls it.

Proposal (**D10**, the owner's request; the numbers the agent's):

- `φ`: the solid mass share of what a cycle took, the inert solids it moved to the bottoms over the feed mass it took. Both are in the cycle already.
- **Tray fouling** `f`, 0 to 1, a fact of the column unit. While solids pass: `df/dt = (f∞ − f)/τ`, `f∞ = min(1, φ/0.02)`, `τ` the fouling time (100 process hours, D12; 10 before). It never falls by itself. Effect: the trays' capacity is `1 − 0.4 f`, so the flood fraction the cycle reads is divided by it; `HIGH LOAD` and then `FLOODING` (the feed taken is limited) come earlier. At 1 wt% solids the column settles at 80 % of its clean capacity; from 2 wt% at 60 %. Word: `TRAYS FOULED` from `f` ≥ 0.5.
- **Wall loss**, mm, a fact of the column unit, on the column's material (its shape's `material`, taken at Start): a solids term `min(5, 0.5 · φ/0.01)` mm/y times the material's erosion factor (1 wt%: 0.5 mm/y on carbon steel, 0.25 on stainless), plus the matrix of section 6 on the bottoms liquid of the installed table (its composition and temperature, the column's pressure), computed once per installed table. A column without solids therefore corrodes as a vessel holding its bottoms would.
- Stages as section 7 (`THIN WALL`, `WEEPING`). Through: a new `Condition.LEAKING` of rank `TRIP`; the column takes nothing until it is repaired.
- Repair and cleaning: the constructor's Repair on a stopped column sets its wall loss to 0; Clean on a stopped column sets `f` to 0. A wrecked column (blast) is deconstructed, as every structure. On the column's Operation page (layout B, owner 2026-11-16) the wear is a table on the glass after the last solve and Clean a footer key between Close and Apply; the door keeps Control and Set points.
- The constructor shows the Material choice for a column too (WP3 left it out because a column is no fluid device).
- Not built: a release to the air from a leaking column (it holds accounts, not an inventory the open-vessel law can spill).

## 14. Constructor and simulator

- Constructor, the group of the structure in front: `Repair` beside `Deconstruct`; its target line also shows the structure's state (wall left, `LEAKING`, `FAILED`, `WRECKED`) from the last published snapshot.
- Constructor, DESIGN page: one `Material` dropdown. The datasheet names it and the CA. Equipment shape fields 13: `material`, read by name in `ShapeRepair`, default `CARBON_STEEL`.
- Device pages: an Integrity group (wall left as a meter, the rate in mm/y, the governing mechanism, the life left in hours of play) beside Health; a control valve's page a Trim meter; the words of sections 7 to 10.
- Simulator: a fifth tab, `METALLURGY` (label "Materials"). One row per unit: its service (temperature, governing mechanism, erosion where a stream carries solids), the rate of the chosen material with a lamp (green ≤ 0.1 mm/y, amber ≤ 0.5, red above), Auto or Manual choice as Sizing has, the cost index. Auto takes the cheapest material that is green, else the lowest rate. A control valve row shows its trim life at the sheet's drop and solids. It runs `CorrosionLaw` and `ErosionLaw` on the sheet's streams on the client; nothing on the server. Send to block carries the choice in the shape. Sheet file format 3.
- Tutorial: one Field Guide page and the tab's help tour (with the tab's work package).

## 15. Decisions

Taken by the agent (reversible until the first code commit that uses them): D1 constructed equipment, and control valves for erosion; D2 one material per structure; D3 the time scale; D4 failure by the existing failures; D5 a failed trim fails open; D7 hydrogen attack and oxygen fire as limits per material, booked as wall loss; D6 (the owner's) a column stops only when a compound solidifies inside it; the matrix and erosion numbers; repair and cleaning rules as section 10.

The owner's, 2026-11-16:

- **O1** Fouling growth before the reaction module: the Kern–Seaton placeholder.
- **O2** Alloy price: the cost index only; no items in this batch.
- **O3** Sounds: set A, the weep loop only.
- **O4** Format numbers reserved on `main` (`ab6298d03`); the delegations approved one by one.
- D8: repairs of constructed equipment with the constructor; the repair kit kept for in-line pumps and compressors and control valve trims.
- D11 (owner, 2026-11-16: "Yes, 0.06"): chrome steel's sulfidation factor is 0.06, not 0.12. At 0.12 a crude heater at 365 °C read 0.19 mm/y on chrome steel, over the 0.1 mm/y the Materials tab calls green, so Auto took stainless; at 0.06 chrome steel stays green to about 380 °C at 0.6 wt% sulfur (0.14 at 400 °C).
- D12 (owner, 2026-11-16: "10x"; section 6.5): ten times slower by default. The corrosion year is 200 process hours (D3's 20 h), the fouling time constant 100 process hours (O1's 10 h); the column's trays use the same constant. A carbon steel wall in crude at 370 °C is through after 460 process hours, 115 hours of play, where it was 11.5.
- D13 (owner, 2026-11-16: "build it now"; section 12): a liquid's film follows its viscosity. In turbulent flow at one mass flow and bore Dittus–Boelter's Nu = 0.023 Re^0.8 Pr^0.4 gives the film μ^-0.4: the liquid film is the reference 2000 W/m²K times φ = min(1, (μ_ref/μ)^0.4), μ_ref = 1 mPa·s (water at 20 °C; it and every thinner liquid keep 2000), never below the gas film (100 W/m²K, reached at 1.79 Pa·s). 3 mPa·s gives 1289 W/m²K, 10 mPa·s 796, 30 mPa·s 513. It is a resistance in series with the film, as a deposit is: `R_μ = (1/φ − 1)/2000`, at most 0.0095 m²K/W (`HeatCurve.viscous`), so a fouled viscous liquid's film is `1/(1/2000 + Rf + R_μ)`, and an air cooler takes it inside its tubes, before the air. The viscosity is the network's liquid viscosity at each sampled point of the heat curve where the liquid film has weight (the curve keeps each knot's `R_μ`, so a re-filming reads no flash); the boiling or condensing film and the gas film stay. Why: with one film for every liquid a heavy-oil exchanger had a clean coefficient near 1000 W/m²K where a real one has 300 to 500, so the same TEMA deposit weighed two to three times heavier than in a real plant.
- D14 (owner, 2026-11-16: "Yes, halve them"; section 12): the placeholder's end deposits are half the TEMA anchors (`FoulingLaw.PLACEHOLDER_SHARE` 0.5; the anchors, the crude cuts' fouling numbers and every revision stay). Why: the game's liquid film for a thin hydrocarbon is 2000 W/m²K, about twice a real one (an oil conducts heat about five times worse than water), so a TEMA deposit weighed about twice as heavy against it as in a real plant; half the deposit gives a real plant's loss of duty and design factor. With it the deposit step is 5e-5 m²K/W (1e-4), so a side reaches its end value in as many steps as before (water 0.000175 in 3, crude 0.00025 in 5; at most 23 to the highest, 0.00115, and 40 to the cap 0.002, which stays), and `FOULED` shows from 0.0005 (0.001), where the reference liquid film is down to a half. The crude sample's design factors (clean need, dirty need before and after): E-101 646.8, 1422.1, 1034.4 m², 2.20 to 1.60; E-102 1016.1, 1243.4, 1129.3 m², 1.22 to 1.11; the condenser 509, 766, 640 m², 1.50 to 1.26. The smaller units lose more: P-101's path 3.52 bar, not 2.66, so S-6 stands at 11.44 bar after sizing, not 12.28. A saved deposit above a side's new end value stays a fact: while the side flows it eases down towards the end value with the same time constant (a crude side saved at 0.0004 reads 0.000399 after one process hour, 0.000305 after 100), never by a jump; an idle side keeps it.
- D15 (owner, 2026-11-16: "Use C then, same to the viscosity, it can tolerate lag"; sections 3.2, 12): the liquid film follows the liquid's thermal conductivity and heat capacity as well as its viscosity. Dittus–Boelter at one mass flow and bore gives h ∝ k^0.6 cp^0.4 μ^-0.4 (density left out): the liquid film is 5000 W/m²K, the reference liquid's, water at 20 °C (k 0.6 W/mK, cp 4180 J/kgK, μ 1 mPa·s), times φ = (k/0.6)^0.6 (cp/4180)^0.4 (1 mPa·s/μ)^0.4, at most 1, never below the gas film. As a resistance `R = (1/φ − 1)/5000`, at most 1/100 − 1/5000 m²K/W (`HeatCurve.liquidResistance`), in series as D13's was; `Films.DEFAULT` is (5000, 5000, 100). Warm water keeps 5000 (water near 0 °C falls below it, by its viscosity); a 1 mPa·s light oil (0.126 W/mK, 2000 J/kgK) gets 1459; a 0.3 mPa·s hot oil (0.091, 2900) 2255; a 50 mPa·s oil (0.110, 2300) 298. The properties, at each sampled point of the curve where the liquid film has weight, no lag and no extra flash: μ as D13 reads it; k from the temperature alone, over the same slots weighted by slot volume, water's (a quadratic fit to saturated liquid water from the NIST WebBook, IAPWS 2011, within 2.1 % over 0 to 300 °C, held at its end values outside) for the phase labelled aqueous and for one fluid at least half water by moles (a dense water too), the API Technical Data Book's petroleum rule `k = 0.164 − 1.277e-4 T` (at least 0.05 W/mK) for every other liquid; cp as Δh/ΔT of the curve's own chord that is all one liquid, at least 0.05 K long, centred where it can; a boiling point (0 < x < 0.05) takes the nearest point's, a curve with none the reference. A kettle's pool under liquid alone keeps 2000 (`Pool.STILL_LIQUID_FILM`), as does the share of a clean film the heat panel shows behind a deposit (`FluidView.Heat.SHOWN_LIQUID_FILM`); both are open to the owner. Why: one 2000 film for every liquid was 1.5 times too high for a light oil and 2.5 times too low for water. Crude sample, with D14: E-101 847 m² clean (647), 1235 dirty (1034), a 1.50 × 9 m shell (1.25 × 11 m); E-102 1199 clean (1016), 1313 dirty (1129), six trains of four 2.0 × 9 m bays; the condenser's cooling water lowers its need to 424 m² (640). D14's reason, a thin hydrocarbon's 2000 W/m²K film about twice a real one, no longer holds since D15 (a light oil's film is about 1460 W/m²K): whether the end deposits stay halved is the owner's.
- D16 (owner, 2026-11-16: "merge and go with recommended. Show the HX's overall UA in its GUI", then "Start it"; sections 12, 17): the heat page reads the unit's overall conductance. Over the exchanger's cells `UA = Σ_k A_k / (1/h_own,k + 1/h_other,k)`, each `h` the film that side's march meets in cell k, its curve's film at the cell's mean specific enthalpy, so the liquid film resistance (D13, D15) and the deposit are in it; the wall holds no heat and no resistance; the bypass changes the flow through the cells, not the surface, and the sit-out the slice's heat, not the UA. The clean UA is the same sum on both curves re-filmed without their deposits (`HeatCurve.Films` record the deposit they hold, `Films.clean`). A kettle: its tubes' film against the pool's (`Bath.film`). An air cooler: the bank's effective film (process film in series with the air, `Films.Outer`) over eight equal pieces of its surface. None for a fired coil, a convection coil, a side against a column, a side whose partner is not read or counted stopped, and a step with no flow. Each side computes it where its slice is planned, from both curves it already has, with no flash (`ExchangerRelay.conductance`, in `PassiveStepSolver.couple`; an air cooler's in `PassiveStepSolver.plan`, a controlled one on its planning curve, another on a curve kept without a flash); it rides on the planned law (`HeatSide.conductance`, `AirCooled.conductance`) as a reading outside its equality, so a steady plant keeps its graph (a reading within 1e-4 of the last stands), and in state format 52, so a certified island's page reads it after a load. Expected: two clean water streams at 20 to 60 °C give U = 2500 W/m²K; with `rf` on each side U = 1/(2/5000 + 2 rf). The device page's UA meter in kW/K stands after Duty; the Deposit group's "UA left" is UA over clean UA in %, the empty reading without both; a kettle's pool page reads its tube side's. "Film left" and `FluidView.Heat.SHOWN_LIQUID_FILM` are gone. With it, two owner decisions on D15's open items: the halved end deposits (D14) stay on the D15 films (`FoulingLaw.PLACEHOLDER_SHARE`; E-101 then needs 1.46 times its clean surface on the design deposit); a kettle's pool under liquid alone keeps 2000 W/m²K (`Pool.STILL_LIQUID_FILM`): its liquid stands, it does not flow past the tubes. Ruled out: UA from duty over a log-mean temperature difference; a UA line in the process simulator.
- D10 (section 13.1): a column that carries solids fouls its trays and loses wall with the solid share; the numbers are the agent's, open to the owner.
- Revision 2: a smaller material list (four); columns pass inert particles and stop only for freezing; particulate erosion, control valves first.

## 16. Sounds

| Event | Sound | New file |
|---|---|---|
| `WEEPING` wall | `wall_weep`: a thin hiss with irregular drips, heard to about 8 blocks, in the `FxSounds` loop budget | 1 loop |
| Wall fails | The existing `pipe_burst_gas`, `pipe_burst_liquid`, `pipe_burst_empty`, chosen by what the device held | none |
| Repair accepted | The repair kit's sound of today, played at the structure | none |
| Eroding valve | None new: a valve that throttles a slurry keeps its `valve_liquid` loop | none |

**Owner, 2026-11-16: set A** (the loop only). Three synthesised candidates of `wall_weep` go to the owner once the build starts (`models-and-sfx` skill).

## 17. Formats and config

| Format | Now | This batch |
|---|---|---|
| Fluid facts schema | 27; 28 reserved by `2026-11-15-supercritical-fluids` | 29: per device `wallLoss`, `fouling`, `wrecked` in `MachineFacts`, each written only when not at its default; a control valve's trim health in the health map that exists |
| Column facts | in the fluid facts | under schema 29: a column unit's tray fouling, wall loss and material (WP8), each with the default of a clean carbon steel column; `FROZEN` and `LEAKING` are conditions, state |
| Equipment shape fields | 12 | 13: `material` |
| Wire protocol | `icheme-3`; `icheme-4` reserved by `2026-11-15-supercritical-fluids` | `icheme-5`: views carry integrity and trim, a heat side's view its deposit and the unit's UA and clean UA (D16); the constructor payload carries the material; `Repair` and its answer beside `Deconstruct` |
| Simulator sheet file | 2 | 3 |
| Material data | | `corrosion` sub-record with its own revision, in no identity |

Reservation (COLLABORATION.md section 3): a documentation-only commit on `main` adding the rows "reserved by 2026-11-16-fouling-corrosion" to `FORMATS.md`. It needs the owner's word.

Corpus images: `compat/fluid/` for schema 29, `compat/equipment/shapes-13.snbt`.

Config: `corrosion.enabled`, `corrosion.yearProcessHours` (20), `corrosion.external`, `erosion.enabled`, `fouling.enabled`, `fouling.asymptoteProcessHours` (10). With a switch off its rate is 0 and nothing fails from it; blast wrecking stays under `pollution.equipmentBreach`; a pump's `SOLIDS` fault and a valve's trim follow `machines.wear.enabled` too.

## 18. Work packages

About 4 500 lines of production code: four stages, each leaving `main` working (COLLABORATION.md section 4).

| Stage | WP | Content | Lane, hot files | After |
|---|---|---|---|---|
| A | WP1 | `science/integrity/` (new, pure): `Metallurgy`, `CorrosionDigest`, `CorrosionLaw`, `ErosionLaw`, `FoulingLaw`, `WallState`; `Wear.Fault.SOLIDS`; `MaterialCatalog.Corrosion` and the data; tests | thermo, machines: `MaterialCatalog` | |
| A | WP2 | `runtime/fluid/Integrity` beside `MachineHealth`: booking at the cadence, failures, `wrecked`, the repair and clean events, corrosive air, the valves' trim and the pumps' `SOLIDS` fault in `MachineHealth`, facts schema 29, `/icheme integrity`, corpus, GameTests | runtime, machines: `FluidFacts`, `FluidWorldAuthority`, `ShaftTrains`; `Breaches`, `RepairKitItem` | WP1 |
| A | WP3 | Shape fields 13, `ShapeRepair`, the constructor's dropdown, the datasheet. The registration that hands a device its material is a join step after WP2 and WP3 merge | equipment, client: `EquipmentShape`, `EquipmentStructure` | WP1; beside WP2 |
| A | WP4 | The constructor's Repair key (`EquipmentNetwork.Repair`, target selection as `EquipmentDeconstruction.target`, calling WP2's `queueStructureRepair`); Integrity group and Trim meter on the device pages, status words, drip particles, the weep loop | client; runtime presentation | WP2, WP3 |
| B | WP5 | Fouling: `Films.fouled`, the passage diameter, the level steps, cleaning, the heat page, sizing on design `Rf` | fluid laws, flowsheet: `SheetSizing` | stage A |
| C | WP6 | Simulator `METALLURGY` tab, Auto and Manual, Send to block, sheet file 3, the tour and guide page | flowsheet, client | WP1, WP3 |
| D | WP7 | Columns and particles (section 13): `ColumnBlockSolve`, `ColumnSolve`'s product flash, `Condition.FROZEN` in `ColumnOperation`'s solve handling; tests | plant, flowsheet: `ColumnOperation` | none: it can run first, beside WP1 |
| E | WP8 | Column fouling and wall loss from solids (section 13.1): `ColumnUnit` facts, `ColumnCycle`'s flood fraction, `ColumnOperation` booking, `Condition.LEAKING`, Start takes the material, repair and clean of a stopped column, the constructor's Material row for columns | plant, runtime: `ColumnOperation`, `ColumnFacts` | WP2, WP3 |
| each | | Code guide chapter `integrity.md`, `FORMATS.md` rows, README history, final lane, translation commit at the merge | documents | |

WP7 touches no file of the others and no format, so it can merge on its own as a small batch. WP2 and WP3 write disjoint files and can run as two threads at once; so can WP5 and WP6. Each work package gets a full brief (files, methods, numbers, tests, pitfalls) when it starts.

### WP1 in detail

New package `src/main/java/com/wormzjl/icheme/science/integrity/`, pure (no Minecraft types, `DeterministicMath` for `exp`, `pow`, `log10`; `DeterministicMathSourceTest` bans the `Math` ones).

- `Metallurgy`: the four constants of section 5 with `allowance()` (mm), `limit()` (K), `hydrogenLimit(pH2)` (K, infinite for none and below 7 bar), `oxygenLimit(temperature)` (bar), `cost()`, `factor(Mechanism, boolean hydrogen)` from section 6.3; `byName(String)` with an alias map (empty), default `CARBON_STEEL`.
- `Mechanism`: `SULFIDATION`, `NAPHTHENIC_ACID`, `SOUR_WATER`, `WET_CO2`, `AMMONIUM_BISULFIDE`, `ACID_CONDENSATE`, `AERATED_WATER`, `OVERHEATING`, `CORROSIVE_AIR`, `HYDROGEN_ATTACK`, `OXYGEN_FIRE`, `EROSION`.
- `CorrosionStandard`: built once per compound list, as `HazardStandard` is: per compound its molar mass, sulfur mass share (formula, else the sub-record), acid number, fouling number, and the indices of H2S, CO2, SO2, NH3, O2, H2 and water found by formula. `digest(moles, temperature, pressure, oilMoles, aqueousMoles)` returns a `CorrosionDigest` record (section 6.1). No allocation beyond the record.
- `CorrosionLaw`: `rate(Mechanism, CorrosionDigest)` for carbon steel (section 6.2), `inside(Metallurgy, CorrosionDigest)` returning the governing mechanism and rate, `outside(Metallurgy, index)`. Every rate finite and ≥ 0 for any finite digest; NaN inputs give 0.
- `ErosionLaw` (section 8): `wall(Metallurgy, solidShare, speed)` in mm/y; `trimSpeed(drop, density)`; `trim(solidShare, speed)` in health per process second at the caller's `yearProcessHours` (200 h by default, D12); `solidsIntensity(solidShare)` for the pump fault. 0 for a share or speed that is 0, negative or NaN.
- `Wear.Fault.SOLIDS` (`FaultClass.EROSION`), intensity `ErosionLaw.solidsIntensity`. `MachineHealth` reads it in WP2; in WP1 only the enum constant, its `intensity` case and `WearTest` rows. No existing fault's numbers move.
- `FoulingLaw`: `asymptote(CorrosionDigest, solidShare)`, `step(rf, asymptote, dt, tau)`, `level(rf)` (steps of 5e-5 since D14, 1e-4 before; cap 0.002), `thickness(rf)`. `fouled(Films, rf)` belongs to `HeatCurve` (WP5).
- `WallState`: `stage(loss, allowance)` to `SOUND`, `THIN`, `WEEPING`, `THROUGH`; `lifeSeconds(loss, allowance, rate, yearProcessHours)`.
- `MaterialCatalog`: record `Corrosion(sulfurMassPercent, acidNumber, fouling, estimated, source, revision)`, `readCorrosion(JsonObject)` beside `readHazard`, a map by record key, `corrosion(packageId, componentId)`, and the per-package arrays in conserved order as `toxicReferences` gives them. The twelve crude cut datasets get the sub-record.
- Tests (`src/test/java/.../science/integrity/`, and `MaterialCorrosionTest` beside `MaterialHazardTest`): each anchor of sections 3.1, 6.2 and 8 to 1 %; every factor row; monotone in temperature, in the driving pressure, in solid share and in speed; the four assays' sulfur within 20 % of their specification; `LayeringTest` and `DeterministicMathSourceTest` still pass.
- Commands: `./gradlew test --tests "com.wormzjl.icheme.science.integrity.*" --tests "*MaterialCorrosionTest" --tests "*MaterialHazardTest" --tests "*MaterialCatalogTest" --tests "*WearTest" --tests "*LayeringTest" --tests "*DeterministicMathSourceTest"`. The gates that load the material data (`columnGates`, `equipmentGates`) are the final lane's.
- Done: the tests pass; no class outside the new package, `Wear` and `MaterialCatalog` changed; no reference moved (the sub-record is in no identity; `SOLIDS` is read by nothing yet).
- Pitfalls: a compound list restricted to an island's view has other indices than the world's, so the standard is built per list, by name; the water is the last conserved entry and has no dataset record; a package without the crude cuts gives `S` = 0 and no error; a `switch` over `Wear.Fault` elsewhere (`MachineHealth.booked`) must compile with the new constant, so it gets a `SOLIDS -> 0` case there until WP2.

### WP7 in detail

- `science/flowsheet/unit/ColumnBlockSolve.solve`: replace line 119's refusal by: `var inert = feed.solids().withoutCrystals();` and the feed's crystals melted into its amounts (each population's mass over its compound's molar mass, as `ColumnOperation.melted` does; the feed's enthalpy is the stream's own, crystals included). Carry `inert` (plus the steam rows' and returns') to the bottoms stream where the products are written (`write`), with `thermo.atTemperature(..., solids)` at the bottoms' state so the stream's enthalpy includes them; add `Σ m c_p (T_bottoms − T_in)` to the reboiler duty the unit reports. A column with no bottoms keeps a refusal (`column.solids.no_bottoms`).
- `science/plant/column/ColumnSolve` (the one flash per product, about line 360): when a product's flash holds a crystal phase, the result carries a typed finding "frozen" naming product and compound. The simulator maps it to `column.frozen`; the running column to `Condition.FROZEN`.
- `science/plant/column/Condition`: `FROZEN(Rank.TRIP, "frozen")`, appended. `ColumnStatus` words it as a trip.
- `runtime/fluid/ColumnOperation`, where a finished solve is taken (`refresh` and the path that sets `SOLVE_REFUSED`): a frozen finding sets `FROZEN` on the unit's cycle state instead of installing the table or keeping the old one running; `cycle` takes nothing while it stands (the path `CLOSURE` uses); the next solve of the state taken without a finding clears it and installs its table. `credit` and `melted` do not change. State, not a fact.
- Lang (`en_us.json` only): `column.frozen`, `column.solids.no_bottoms`; remove `column.solids`.
- Tests: `ColumnBlockSolveTest` or the class that holds the unit's refusals (a feed with inert particles gives them at the bottoms, mass exact, duty higher by the sensible heat; a feed with ice runs, its ice counted as its compound, energy exact; a column whose top product is below a compound's freezing line refuses with `column.frozen`); a `ColumnOperation` runtime test (a solve with a frozen finding trips, a later clean solve clears; an arriving crystal alone trips nothing); the existing solids-to-bottoms GameTest still passes.
- Selection of test classes: `node tools/merge-lane/affected.mjs` on the changed files (a column change reaches `FlowsheetExamplesTest` and `ColumnSolveTest` indirectly).
- Pitfalls: a slurry line slower than its deposition speed plugs, so a test's lines are sized; the frozen case needs a package view with a crystal (water ice or CO2) and a top product cold enough, for example a CO2-rich overhead below 216 K; the product flash already runs, so no extra flash is added; the code guide says the column takes only package views without crystals, so the finding comes from the network thermo's product flash, never from the column's package; trays between the products are not flashed (no added cost), which can miss a solid that forms only mid-column.

## 19. Risks

- `MaterialCatalog`, `FluidFacts`, `FluidWorldAuthority`, `EquipmentShape`, `SheetSizing` and `ColumnOperation` are hot files. The branches `claude/fluid-interfaces-*`, `claude/supercritical-*` and `claude/fluid-step-jacobian` exist; whether any holds these lanes is the owner's to say.
- A heat side closed by a failure must not be reopened by its own built-in control or by a layout refresh (`StructureRegistration` must carry it, as it carries `breached`).
- The repair-rule change is visible to existing worlds: a constructed pump, compressor, turbine or breached vessel no longer takes a repair kit; it is stopped, emptied and repaired from a constructor within reach (16 blocks on its line). An in-line pump must be stopped before its kit is accepted.
- A trim that fails open can overfill or depressure what its loop held. That is the intended consequence; the status line and the DCS alarm name the valve.
- D6 makes a running column trip where it ran on before, when a product of its solve would hold a crystal. A feed with ice or dry ice alone changes nothing.
- The matrix and erosion numbers are fits. A change of a factor is a data edit in one table and moves no solver reference.
