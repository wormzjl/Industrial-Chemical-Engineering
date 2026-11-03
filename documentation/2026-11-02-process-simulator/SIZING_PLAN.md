# Sizing stage plan

Batch [2026-11-02-process-simulator](README.md). Status: the owner approved these criteria on 2026-11-02 and the simplifications on 2026-11-03 (section 6). Drawings: sheets 24 to 28. S1 is built; the rest is not. It follows [PROCESS_SIMULATOR_PLAN.md](PROCESS_SIMULATOR_PLAN.md).

## 1. Expected behaviour

A converged flowsheet says what flows, and at what state. It does not say how big anything is. To build it, every unit and line needs a size that the constructor can place and the world can run, at a flow above the steady one, with drops that stay inside what the sheet assumed.

So the player finishes the flowsheet, opens the Sizing tab and presses Size all. The stage reads the steady streams and gives each unit and line the smallest size that meets its limits. It says what limits each size, and where a limit cannot be met. Then Send to block hands the sizes to the constructor.

The flowsheet holds functions: one tile for a fired heater, one for a storage tank. This stage proposes each tile's type (section 3.10).

The sample shows it, at 110 % of the steady flow:

- 16 units and 31 lines sized. The units carry 89 limits. The 44 Musts and the 45 Shoulds are all met.
- The column stays at the door's 8.0 m: flood 71.3 % at design flow, and 7.5 m gives 80.9 %.
- H-101's tile takes the cabin type: 86.2 MW needed, 54.9 MW the largest vertical, 87.6 MW in a cabin 11.0 × 41 m.
- The heaviest lines reach the pipe tool's limit: S-7 and the overhead take the largest bore, 0.80 m.
- The pressure paths close: P-101 loses 3.66 of its 9.00 bar, and the residue line 0.47 of 0.99 bar with no pump.

Every number comes from `tools/process-simulator/SizingProbe.java`, on the shipped laws. It writes [sizing-sample.tsv](../../tools/process-simulator/sizing-sample.tsv), and the sheets draw that table.

## 2. What exists

The mod already knows what each size does; only the choice is missing.

| Item | What a size is | What it holds, in code |
|---|---|---|
| Pipe | Any bore to 0.8 m (`PipeSizes.MAX_BORE`) | `PipeResistance`: homogeneous Darcy-Weisbach; 100 m/s cap (`DEFAULT_MAXIMUM_VELOCITY`) |
| Exchanger | Shell 0.5 to 2.5 m, step 0.25; tubes from 3 D to 12 m | `tubeSurface` 75 D² L; world films 2000 liquid, 5000 two-phase, 100 gas W/m²K; `HeatSides.tubePassage`, `shellPassage`, so each side's drop follows its flow through `PipeResistance` (section 3.11) |
| Kettle | Bundle 0.5 to 2 m, tubes to 10 m | Shell 1.6 bundles (`kettleShell`) |
| Air cooler | Bay 1.5 to 3 by 2 to 6 m; bank ≤ 6 parallel, ≤ 4 series | 5 m² bare per m² face, 430 W/m²K bare, air 25 °C; `airCoolerPassage`, `fanPower` |
| Fired heater | Vertical D 2.5 to 12, to 20 m; cabin W 4 to 12, L to 48 m | `heaterDuty`: 35 kW/m², 70 % radiant, at most 54.9 and 112.7 MW; `heaterPassage`: 8 MW a pass |
| Pump | Discharge 0.05 to 0.6 m | `pumpFlow`: 3 m/s in the discharge, a datasheet value. The world's limits are the controls': 10 m³/s, 2 MPa of water head, 2 % vapour by volume at the suction |
| Compressor | Piston bore 0.15 to 1.2 m, 2 to 10 throws; turbo impeller 0.3 to 1.5 m, 2 to 9 | `displacement` to 163000 m³/h, a turbo's inlet 7000 to 176000 m³/h; the world's law is the same for both: ideal isothermal work, ratio 1.01 to 10, 1 % condensed by mass |
| Drum | D 0.5 to 10 m, step 0.5; T/T to 64 m; boot from `bootFor` | Height by `minimumBootSupport` |
| Column | D 0.5 to 15 m, to 64 trays | Fixed trays: `V3TrayHydraulics`, spacing 0.6 m, downcomer 10 %, Fair flood; `NozzleBore.largest` |
| Tank, sphere | Tank D 4 to 40, shell 4 to 24 m; sphere D 4 to 24 m | A tank is an `OpenVessel`: ambient pressure, and any gas vents. A sphere is a closed drum with no pressure limit. `FluidDeviceSpec.MAX_VOLUME` 30000 m³ |
| Valve | No Cv in the world | A drop between two pressures |
| Ejector | Suction 0.05 to 1 m | Drawn only: the world has no law for it (section 3.12) |

## 3. Proposal

### 3.1 The stage

Commercial simulators work in the same order: converge with simple blocks, size from the converged rates, rate the sizes against limits, and re-simulate if the rated values differ (note, section 12). Here it is one tab.

1. Sizing is off until the sheet reaches a steady state, with a hover saying why.
2. Size all runs on the client, in the run's background thread; Stop reaches it. Lines and units are arithmetic. A column runs the real solver at each trial diameter.
3. Sizing moves the run in two places: a new column diameter moves the tray drops, and a sized unit's drops follow its flow where the door's were typed (section 3.11). So Size all runs the sheet once more and rates again. It does not size a second time.
4. Each item is Auto or Fixed. Auto takes the proposed type and the smallest size that meets every limit. Fixed keeps the player's type and size, and rates them against the same limits. So one stage sizes and rates.
5. A later run rates every size again when it ends, with no trial columns, so the lamps stay true. Sizes change only on Size all, or by the player's hand.
6. Undo takes back a Size all as one step.

A lamp gives the state. Green meets every limit. Yellow misses a Should: outside good practice, and it still runs. Red misses a Must: the world refuses it, its own model says it fails, or the constructor cannot build it. A red row stops Send to block. A yellow row does not.

### 3.2 Design basis

Three entries in the tab's Design group, for the whole sheet.

| Entry | Default | Why | Source |
|---|---|---|---|
| Design flow | 110 % of steady | Rated 110 % of normal; +10 % for surge and reflux. Every limit applies at this flow | [ET], [MARG], [BRAN] |
| Line length | 50 m with fittings | The Pressure view reads it, and so does Vacuum vapour's drop, which is a share of the pressure over the line; each line can hold its own | This plan |
| PA fall | 60 K | The column model has no circulation; a fall across the cooler gives it | This plan, unsourced |

### 3.3 Lines

Every line: bore ≤ 0.80 m a run, speed ≤ the world's `velocityLimit` at the line's state (the lesser of 100 m/s and the isothermal acoustic bound), bore ≤ what its nozzle takes (`NozzleBore.largest`). These are Musts, all from the world.

A line with solids has one Must more, from the same law the world's connection reads:

| Limit | Kind | Value | Source |
|---|---|---|---|
| Solids carried | Must | `SolidMobility.check` allows it: the liquid's speed above the solids' deposition speed, a bore wider than the largest solid, a slurry that is not packed, a liquid that is not immobile. The last two refuse at any size | World: `SolidMobility` |

The service comes from the stream, and the player may change it. A stream above 95 % vapour by mass is Gas, or Steam if its gas is water. Above 0.05 % vapour it is Two-phase. A liquid leaving a tray is Draw. One that feeds a pump is Suction: boiling if within 0.3 bar of its bubble pressure, cold otherwise. Those two protect the column's head and the pump, whatever the liquid. Of the rest, water above 95 mole % of the liquid is Water, and anything else is Liquid.

Each service's limits are Shoulds.

| Service | Speed, m/s | Drop, bar/100 m | ρv², kg/m·s² | Source |
|---|---|---|---|---|
| Suction, boiling | 1.0 under 250 mm; 1.25 to 500; 1.5 above | 0.10 | | [KARM]; [LINEG] |
| Suction, cold | 1.5 under 250 mm; 1.8 above | 0.10 | | [KARM]; [LINEG] |
| Draw | 0.6 to 50 mm; 0.9 above | 0.10 | | [KARM]; [LINEG] |
| Liquid, pumped | 3 | 0.45 | | [KARM], [THAK]; [ET] |
| Water | 3 | 0.45 | | [KARM], [LELE]; [ET] |
| Gas | 60, 40, 30, 20 at ≤ 0.1, ≤ 1, ≤ 5, > 5 barg | 0.11 | 6000 under 20 barg, 7500, 10000, 15000 over 80 | [KARM]; [CHER] |
| Steam, saturated | 40 | 0.2 | 15000 | [SPX]; [KARM] |
| Steam, superheated | 60 | 0.2 | 15000 | [SPX]; [KARM] |
| Vacuum vapour | 90, 75, 60 at < 0.01, < 0.05, > 0.05 bara | 5 % of the absolute pressure over the line | | [KARM]; [BRAN] |
| Two-phase | At least 3 | | 10000 | [A14E]; [KARM] |

API RP 14E's erosional speed needs no row of its own: its C 100 is ρv² ≤ 14600, the same limit in another form.

### 3.4 Units

Tags are entries of the note, [SIZING_SOURCES.md](../../research/2026-11-02-process-simulator/sizing/SIZING_SOURCES.md), which gives each value's provenance. No standard was read in full, so values quoted from them are leads. "World" names a law or a limit in the mod's code.

**Pumps**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Flow | Must | ≤ 36000 m³/h | World: `FluidNetwork.Controls` |
| Head | Must | ≤ 204 m | World: 2 MPa at water reference |
| NPSH available | Must | > 0 | World: `PUMP_SUCTION_LIMIT` |
| NPSH available | Should | ≥ 1.0 m | [HI-961] |
| Flow to rated | Should | 70 to 120 % | [API610] |

NPSH available takes the source pressure, the static head from the nozzle's height to grade, the suction line's drop and the bubble pressure from the world's own flash. The world's pump has no NPSH required, so the 1 m margin stands alone.

A block's member pumps have no door: their head is the loop's lift, friction and a 0.7 bar valve.

**Compressors**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Ratio | Must | ≤ 10 | World: `FluidNetwork.Controls` |
| Liquid at the suction | Must | ≤ 1 % by mass | World |
| Displacement | Should | ≥ design flow | World: `displacement` |
| Ratio a stage | Should | ≤ 3.5 piston, ≤ 3 turbo | [PEREZ] |
| Discharge temperature | Should | ≤ 150 °C, adiabatic estimate | [WEAK]; 135 °C with hydrogen [API618] |

**Exchangers**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Surface | Must | ≥ what the design duty needs with the world's films (section 3.11) | World |
| Hot end, cold end | Must | > 0 K | World: counterflow |
| Least approach | Should | ≥ 10 K | [BLOG] |
| Drop, liquid side | Should | ≤ 0.7 bar | [BLOG] |
| Drop, condensing or gas side | Should | ≤ 0.2 bar | [SCR-HX], [BLOG] |
| Drop, boiling side | Should | ≤ 0.1 bar | [BLOG] |
| Surface over the need | Should | ≤ 100 % | This plan |
| Cooling water out | Should | ≤ 45 °C | [BLOG] |
| Kettle flux | Should | ≤ 38 kW/m² | [ET] |

The source gives a condensing side 0.1 bar and a gas side 0.2 bar [SCR-HX]. A condenser takes the gas row here, because the world's law prices its whole shell as inlet vapour.

Speeds have no row: no law in the world reads a tube's or a shell's speed, and the drops bound them (section 3.11).

**Air coolers**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Bare surface | Must | Holds the design duty at air 25 °C | World |
| Outlet above air | Should | ≥ 15 K | [HALL-R] |
| Tube drop | Should | ≤ 0.7 bar | [WEAK] |

**Fired heaters**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Duty held | Must | `heaterDuty` ≥ design duty | World |
| Duty on a vertical | Must | ≤ 54.9 MW, the largest | World |
| Duty on a cabin | Should | ≥ 23 MW | [FH-TYPE] |
| Pressure spare on its path | Must | ≥ 0 | World |

The world fixes the radiant flux at 35 kW/m², inside the 32 to 38 of crude heaters [GARG], [FH-DECK], so no size changes it.

**Columns**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Flood at design flow | Must | ≤ 100 % | World: `V3TrayHydraulics` |
| Flood at design flow | Should | ≤ 80 % | [NPTEL], [KIST] |
| Diameter, trays | Must | ≤ 15 m, ≤ 64 | World |
| Widest line on a nozzle | Must | ≤ `NozzleBore.largest` | World |
| Downcomer speed | Should | ≤ 0.18 m/s | [KIST] |
| Tray drop | Should | ≤ 1.0 kPa | [TRAY-X] |
| Sump hold to half | Should | ≥ 5 min | [ET] |
| Height to diameter | Should | ≤ 30 | [ROT] |

**Drums**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Diameter, T/T, boot | Must | ≤ 10 m, ≤ 64 m, `bootFor` has room | World |
| Hold to half full | Should | ≥ the drum's entry: 5 min | [BLOG], [COST] |
| Length to diameter | Should | 2.5 to 5 lying; ≥ 2 standing | [BLOG]; [TOW] |
| Gas speed | Should | ≤ K √((ρL − ρV)/ρV), K 0.053 m/s standing, 1.25 times that lying | [GPSA], half the padded value, as the world's drum has no pad; [FLU] |
| Boot hold | Should | ≥ 5 min | [REDBAG] |

**Tanks and spheres**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Volume | Must | ≤ 30000 m³ | World |
| Diameter, shell | Must | The catalogue's | World |
| Hold | Should | ≥ the tank's entry: 24 h | This plan; real plants hold 2 to 15 days [PARK] |
| Working share | Should | ≤ 85 % | [REDDY] |
| Bubble pressure, floating roof | Must | ≤ ambient, 1.013 bar. Above it the world's tank boils and vents its lights | World: `OpenVessel` |
| Bubble pressure, floating roof | Should | ≤ 0.766 bar | [CFR], [KC] |
| Design pressure, sphere | Should | ≤ 20 bar, at 1.1 times the bubble pressure | [API-P] |

**Valves and ejectors**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Valve drop | Should | ≥ 20 % of the path, ≥ 0.7 bar | [HALL-V] |
| Ejector compression a stage | Should | ≤ 7 | [SK], [EJ-X] |
| Ejector stages by suction | Should | 1 above 100 mbar, 2 above 13, 3 above 1.3 | [WEAK], [EJ-X] |

A valve takes its line's bore: the world's valve has no Cv. Section 3.12 gives the ejector's law.

### 3.5 Selection

Auto takes the smallest size that meets every limit, Must and Should. Where none does, it takes the smallest that meets the Musts and comes nearest the Shoulds, and the row stays yellow. Where no Must can be met, the row is red.

| Item | Auto varies, in this order |
|---|---|
| Line | Bore through the usual sizes, then parallel runs to 4, with a yellow lamp over one |
| Exchanger | Fewest shells to 6, then least surface, under twice the need and within both drops. Without the cap the sample's condenser takes 2 × 1.75 × 6 m, 220 % over the need, where it now takes 3 × 1.25 × 4 m, 63 % over |
| Air cooler | Least face within its drop |
| Heater | The type by duty, then the least duty that holds |
| Compressor | The type by inlet flow, then the smallest frame |
| Pump | Smallest discharge whose rated flow fits the window |
| Column | Diameter in steps of 0.5 m, real solves |
| Drum, tank | The type, then the least volume |

### 3.6 Blocks and pressure paths

A block is one row with its members under it, as its door builds them: condenser, receiver, reflux pump, pumparound coolers and pumps. The door's pumparound has no circulation, so its pump, lines and cooler take it from PA fall here. Its cold side's duty stays the door's.

A pressure path runs from a machine's outlet to the next pressure the sheet sets. The door's drops were the player's estimates. The Pressure view adds up the sized ones at design flow, with static head where nozzle heights give it. A negative spare is red. A positive spare is what a valve takes. The tool finds the paths from the sheet.

### 3.7 Hand-off

Send to block stores each unit's design on the block as an `EquipmentShape`, as Save design does for a column today. The constructor's Import lists them all, each with its tag and count, and lands the chosen one on the open draft. The protocol changes, and needs no compatibility. The Build view's line list stays with the player, who lays each line at its bore with the pipe tool. Nothing places itself.

### 3.8 Screen

Sheets 24 to 27. One tab with four views: Units, Lines, Pressure, Build. The left door holds the Design group and the selected item's Auto and Fixed keys and sizes. The glass holds the table, the limits of the selected item and the path's stair. Close, Size all (the confirming key), Send to block, Undo and Redo sit in the footer.

### 3.9 Formats

Sizes, Auto or Fixed, are facts of the sheet: new fields with defaults, under the sheet's own rule (main plan, section 3.9). Lamps, values and paths are state, rebuilt when the tab opens. An old sheet opens with every item on Auto and no size yet. A tile's type is a fact of the same kind: no type means Auto.

### 3.10 Types

Equipment with the same function is one tile (owner, 2026-11-02). The flowsheet says what a unit does, and its type depends on numbers only the run gives. So the player draws a heater, and this stage proposes which heater.

A tile runs with no type, because the world's law is the same for its types: `HeatSides.of` gives a vertical and a cabin the same two coils, and both compressors the same isothermal law. A storage tile holds its liquid at the higher of ambient and its own bubble pressure. That is what a floating roof does below ambient and what a sphere does above it.

| Tile | Types | Proposal | Source |
|---|---|---|---|
| Fired heater | Vertical, cabin | Vertical while the largest vertical, 54.9 MW, holds the design duty; cabin above. Above the largest cabin, 112.7 MW, a count of cabins | [FH-TYPE], World |
| Storage tank | Floating roof, sphere | Floating roof while the liquid's bubble pressure at the tile's temperature is at most 0.766 bar; sphere above | [CFR], [KC] |
| Drum | Standing, lying | Lying with a boot for a second liquid; lying if its hold would need L/D over 5; standing otherwise | [SCR-VES], [TOW] |
| Compressor | Piston, turbo | Piston to 15000 m³/h at the inlet; turbo above. Under 7000 m³/h no turbo frame exists | [COMP-X], [GALL], World |
| Steam turbine | Back-pressure, condensing | Condensing if the exhaust is below ambient. Unlit | Physics |

The owner named tanks and heaters, with these reasons: a sphere is built for higher pressure than a floating roof, a box heater for higher duty than a cylinder. Drums, compressors and turbines follow the same rule (D20).

On Auto the proposal stands with its size. A key keeps the player's own type, and the row is rated as a Fixed size is: section 3.4 holds each type's limits. The symbol on the sheet follows the type. Until then it is the tile's. Send to block stores the type with the size, so the constructor's Import names the real equipment.

### 3.11 Exchanger design and drops

An exchanger's surface follows from its duty, and its drops follow from its flows. A side loses about the square of its flow, so a drop typed at one flow is wrong at another.

**What the code does.** Every side already has a flow-based law. It compiles to a `PipeResistance` passage, as a pipe does, so the world's drop follows the flow. The flowsheet tile does not: its door's drops are typed, and the run never reads the law.

| Side | Law | Against the textbook |
|---|---|---|
| Tube | Darcy-Weisbach on the tubes, plus 4 velocity heads a pass | The textbook's form |
| Shell | Darcy-Weisbach on an 18 mm passage of 0.1 D² | Kern's drop on that geometry is 10 times the law's at the 0.4 D the code's comment names: E-101 1.53 bar against 0.15, the condenser 1.93 against 0.18 [KERN] |

The reason is the friction factor: a pipe's, where Kern uses a tube bank's. But the law's drop equals Kern's at baffles 0.92 to 0.93 D apart, on all five exchangers, inside the 0.2 to 1.0 D of practice [BAF]. So the shipped law prices a wide-baffle shell. At that pitch the real shell speed is 0.49 to 0.84 m/s on the sample's liquids, inside the source's 0.3 to 1 m/s [SCR-HX]. The 2 m/s of this plan's first draft was the same shell seen through the wrong flow area. D18 gives the options.

**Proposal.**

1. The door's target is Outlet, Duty or Area. Area replaces UA: the world's U comes from its films by phase, so area is what the world takes.
2. The surface a duty needs comes from the world's own kernel, `HeatCurve` and `HeatSide`, zone by zone. The probe uses one LMTD, which holds for the sample's sensible and condensing duties.
3. A unit's drops are the player's estimates until it has a size. From then on the run takes its law at the run's flow, and the door shows them as meters (sheet 28). The same holds for an air cooler and a heater's coils. D19.
4. Surface and drops decide an exchanger. Speeds have no row.

The feedback is real. H-101's door typed 7.60 bar, and its coils lose 1.95 at design flow. At the run's flow this path's units lose about 2.8 bar, where the doors typed 9.00. So S-6 rises from 5.00 to about 11.2 bar, and less crude flashes in the coil at 365 °C: 54 mole % vapour, not 71. H-101 falls from 78.37 to 74.25 MW and T-101's feed from 359.5 to 350.5 °C. The run still closes in 2 passes. The Pressure view shows the cure: 5.34 bar of P-101's rise is spare.

### 3.12 Ejector

An ejector trades steam for compression, with no moving part. The more it must compress, the more steam each kilogram of load takes. A light gas takes more than a heavy one. The steam leaves with the load, so whatever comes next must condense it.

The law, for the steam per kg of load, with pressures in kPa, the load's T in °C and M its molar mass:

`Ra = 0.296 P_d^1.19 / P_s^1.04 (P_m / P_s)^0.015 PCF / TCF × √(18.015 / M)`

`PCF = 3e-7 P_m² − 9e-4 P_m + 1.6101`, `TCF = 2e-8 T² − 6e-4 T + 1.0047`

- It is El-Dessouky's correlation [WT-EJ], fitted on water with Ra below 4, compression above 1.89, motive 100 to 3500 kPa. Outside that it runs on smoothly with a notice, as every law here does.
- PCF stays at its least above 1500 kPa, with zero slope at the edge. The polynomial turns up there, doubling the steam at 3500 kPa, which no ejector does.
- √(18.015 / M) is the rule behind the vendors' dry-air load. Against air it gives their 0.26, 0.74 and 1.23 for hydrogen, methane and CO₂ [EJ-VEND].
- The discharge is load plus motive, mixed adiabatically and flashed at P_d, so mass, energy and components close.

At 1000 kPa motive, to 120 kPa, with the load at 50 °C:

| Suction, kPa | Ratio | Steam per kg: water | Air | M 100 |
|---|---|---|---|---|
| 50 | 2.4 | 1.64 | 1.29 | 0.69 |
| 30 | 4.0 | 2.80 | 2.21 | 1.19 |
| 20 | 6.0 | 4.30 | 3.39 | 1.82 |
| 12 | 10.0 | 7.37 | 5.81 | 3.13 |

A jet law in the world compressor's isothermal heads was tried first. It has one fitted number and no range, and it failed: 68 % mean error over the correlation's range, and 3.6 kg/kg where the correlation needs 5.5 at a ratio of 7.7.

- **Tile.** Suction in, Motive in, Discharge out, lit by W10, the last work package. The door sets the discharge pressure. A free motive port is a utility at the door's motive pressure: the law gives its flow. A wired port's stream must cover that flow, or the row is red. A wired port warns if the stream is not mostly water.
- **Column block.** Its Motive steam port takes the same rule, and the steam's heat joins the condenser's duty. 5000 kg/h of M 100 at 12 kPa, to 120 kPa on 10 bar steam, takes 15640 kg/h and adds 9.6 MW.
- **One tile is one stage**, as the column's auxiliaries plan chose. A deep vacuum stays yellow on the block, and on the sheet a chain of tiles with condensers between goes further.
- **Sizing.** The suction bore takes the Vacuum vapour row of section 3.3, within `EjectorDesign`'s 0.05 to 1 m. Section 3.4 holds the stage limits.
- **The world.** Vacuum columns do not run there until column operation's stage C (its Q11), which needs a device with three ports. This law lives in `science/`, so that stage reads it. D21.

## 4. The sample's result

| Item | Size | Closest limit |
|---|---|---|
| P-101 | Discharge 0.30 m, 71 m | 96 % of rated |
| E-101 | Shell 1.00 m, tubes 6 m | Surface 450 of 444 m² |
| H-101 | Cabin 11.0 × 41 m | Duty 87.6 of 86.2 MW |
| V-101 | Bore 0.70 m | Drop 2.28 bar, at least 1.19 |
| T-101 | D 8.0 m, 40 trays, 29.8 m tall | Flood 71.3 %, at most 80 |
| Condenser | 3 × shell 1.25 m, tubes 4 m | Shell drop 0.18 bar, at most 0.20 |
| Receiver | D 4.0 × T/T 10 m, boot | Boot hold 7.0 min, at least 5 |
| Reflux pump | Discharge 0.20 m, 45 m | 119 % of rated |
| PA 1, 2, 3 coolers | Shell 1.00 m, tubes 3 m each | Surface 225 of 224 and 223 m²; a 0.75 m shell would cost PA 3 over 0.7 bar in its tubes |
| PA 1, 2, 3 pumps | Discharge 0.25, 0.30, 0.25 m, 13 to 14 m | 110, 93, 78 % of rated |
| P-102 | Discharge 0.15 m, 51 m | 71 % of rated |
| E-102 | 5 × 3 bays of 2.5 × 6 m | Bare surface 752 of 741 m² |

Three results went against what the sheet's author would guess.

- The boot's 5 min, not the oil's, sets the receiver: D 3.0 m holds the oil, and the water needs D 4.0 m.
- H-101's door took 7.60 bar, and its coils lose 1.95 bar at design flow. So 5.34 bar stays spare, for a valve or a smaller rise on P-101. A lower rise needs a new run, as the coils lose more at a lower pressure. Section 3.11 gives what the run moves once it reads these drops.
- P-102 has room, as the run already shows. W7 measured its bubble pressure at 191.5 kPa, 74.2 % of the stream's pressure, because the tray's vapour is 23.9 % steam, so the run gives no warning. Here the draw nozzle's 21 m of static head and the steam's share of the tray pressure give 31 m of NPSH.

The sample has no storage tile, so this what-if gives each product one, at a 24 h hold and an 85 % working share:

| Tile holds | °C | Bubble, bar | Proposed | Size |
|---|---|---|---|---|
| NAPHTHA, as it leaves | 59.0 | 2.299 | Sphere | D 17 m |
| KERO, cooled | 40.0 | 0.099 | Floating roof | D 18 × 11 m |
| DIESEL, cooled | 40.0 | 0.027 | Floating roof | D 20 × 12 m |
| AGO, cooled | 40.0 | 0.016 | Floating roof | D 15 × 9 m |
| RESIDUE | 70.0 | under 0.015 | Floating roof | D 30 × 18 m |
| KERO, as it leaves its tray | 140.1 | 1.839 | Sphere | D 18 m |

The naphtha is unstabilised, so its lights need a sphere until a stabiliser takes them out. The kerosene shows why a tile reads its own temperature: a hot draw would boil off under a floating roof, and a cooler fixes it.

## 5. Work packages

They follow W1 to W9 of the main plan. Each ends with the tests it can affect and a commit.

| WP | What | Gates |
|---|---|---|
| S1 | `science/flowsheet/sizing`: the limits as one table with source tags, services, line sizing | Each service at both sides of its limit; the sample's lines equal the probe's |
| S2 | Units: pump, exchanger, kettle, air cooler, heater, compressor, drum, tank, valve; one rule both sizes and rates; the type proposals; the surface from `HeatCurve` | Per class: the size holds, one step down fails, Fixed reports the same limits; each tile's proposal on both sides of its limit; the door's surface against the world's at one duty |
| S3 | Column and members: trial diameters in the run thread, re-run on a new diameter, circulation, member heads | Stop reaches a trial; the sample's flood at each diameter; no process number moves but a column's |
| S4 | Pressure paths, the tab and its four views, lamps, Auto, Fixed and type keys, the run on sized drops, formats | Column-builder gates; sheets rendered from shipped code; a corpus image; an old sheet opens; a sized unit's drop follows a changed flow |
| S5 | Send to block, the constructor's list, the coverage gate: every equipment kind declares its sizing or says why it has none; code guide chapter | Import gate; coverage gate |

The ejector is W10 of the main plan, after S5.

## 6. Decisions

The owner approved every row as written on 2026-11-02.

| # | Decision | Answer | Why |
|---|---|---|---|
| D13 | Basis defaults | Design flow 110 %, Line length 50 m, PA fall 60 K | The flow is sourced. The other two are mine, and the player can change them |
| D14 | The Must and Should split | As section 3: red only for what the world refuses or its model fails, yellow for practice. Exchangers and air coolers go by surface and drops, with no speed rows | No law in the world reads their speeds. With the rows, the shell's speed needed 2 m/s, twice the source's, and E-102 stayed yellow at 0.59 of 0.60 m/s, a floor with no law behind it |
| D15 | Auto's rule | Smallest that meets every limit; exchangers by fewest shells under twice the need | The cap is mine, not a source's. It avoids a surface 3 times the need |
| D16 | Counts | A count on one row for parallel shells and runs; no spares | The world has no failure model. No source gave a rule for parallel pumps |
| D17 | Hand-off | Option A: Send to block stores every unit, Import lists them, lines stay a list | B: columns only, as today. Then a player types every other size into the constructor by hand |
| D18 | The shell side's drop | A: keep the shipped law and correct its comment: it prices baffles about 0.93 D apart. No world change | B prices a real tube bank at 0.4 D, and needs a shape field for wide baffles on vapour shells, or the condenser takes 15000 m². The sample then has PA 1 2 × 0.75 × 3 m, 253 m²; PA 2 3 × 0.75 × 3 m, 380 m²; E-101 1.25 × 4 m, 469 m²; the condenser as now. It costs a world law change and re-recorded heat tests, and the world's film gives nothing back for the narrower baffles |
| D19 | Drops in the run | Once a unit has a size, the run takes its law at the run's flow; a typed drop is an estimate until then | The other way keeps typed drops, with only the Pressure view reading the law. Then the sheet is wrong by what section 3.11 measures: 4.1 MW on H-101, 8.9 K on T-101's feed |
| D20 | One tile for the other same-function pairs | Yes: Drum, Compressor and Steam turbine as well, 15 lit tiles | You named tanks and heaters. The same reasons hold for these: one law, a type that numbers decide |
| D21 | The ejector's scope | W10: the law, the tile, the block's port, its sizing, one stage. The world's device stays in column operation's stage C | Vacuum columns do not run in the world until that stage, and a three-port device needs its own engine work |

### Simplifications

The owner accepted all ten as written on 2026-11-03.

| Simplification | Effect | Risk |
|---|---|---|
| World films for every U | E-101 needs 444 m². At U 400 W/m²K, inside the note's 150 to 1200, a real one needs about 1100 m² | Units look small to an engineer. They match the world, so a built plant does what the sheet says |
| No fouling allowance | None in the world | Same |
| Static head only from nozzle heights | Others count 0 | Safe side for NPSH |
| A pump's head from its sheet rise | P-102's 51 m ignores its 21 m of static gain | Oversizes the datasheet only |
| One Line length | The Pressure view and Vacuum vapour's drop | A long line can set its own |
| Fixed tray geometry | Weir load below | None: the world's law prices it |
| A wide-baffle shell | Drops 10 times under Kern's at 0.4 D, equal at 0.93 D | Real U falls with wider baffles; the world's does not |
| One LMTD in the probe | None on the sample's duties | The code uses `HeatCurve`, so a boiling or gas side reads true there |
| One ejector stage | Steam for a ratio of 10: 3.13 kg/kg at M 100 | A real deep vacuum has two or three stages with condensers |
| √M for a load | Vendors' factors to 2 digits on hydrogen, methane and CO₂ | Fitted on none of the heavy vapours a column sends |

## 7. Left out, and why

- **Weir load**: 140 m³/h per m on the sample against 89 [KIST]. The world's tray is single-pass at any diameter, where a real 8 m tray has several passes. No size fixes it without a far larger column, and the V3 law already prices it as liquid head.
- **Heater mass flux**: 949 kg/s·m² against 1220 to 1710 [FH-DECK]. It follows from 8 MW a pass, so no size changes it. A pass of about 12 MW would reach it: a model question, not a sizing one.
- **Nozzle ρv² [TEMA], tube vibration, fan coverage, face speed**: the world has no law that reads them; the bay fixes the last two.
- **Exchanger and air cooler speeds**: fouling and erosion have no law in the world, the film does not read speed, and the drops bound speed from above.
- **Mach, noise, flare and relief lines**: the world has no choke law (owner decision: 100 m/s cap) and no relief.
- **Durand's slurry speed** [DUR]: streams do carry solids, and section 3.3's Must reads the world's own `SolidMobility`, so a second speed has no law behind it.
- **Coking, flue gas, stack**: no combustion model.
- **Mechanical design and cost**: wall thickness, wind, price. Out of the game's scope.

## 8. Risks

- **Secondary sources**: most values come from excerpts and rule lists, and the standards themselves were not read. Each stays tagged in the limits table, so one can change without touching code around it.
- **Yellow noise**: a lamp that is always yellow means nothing. The sample has none of 45; other sheets may have more. S2 runs the crude presets through the rules before the values freeze.
- **Weak type thresholds**: 23 MW for a cabin, 15000 m³/h for a piston and 20 bar for a sphere come from decks, blogs and a forum, not a standard. 0.766 bar is the only one from a rule (40 CFR 60). Each is one row of the limits table.
- **Over-pressure after a re-run**: Size all does not change P-101. A sheet with a large spare runs at a high coil pressure until the player lowers the pump.
- **Kern as the yardstick**: the method is approximate on leakage and bypassing, and its friction form here rests on memory plus one quoted example (the note, section 13).
- **Trial cost**: a warm column took about 0.7 s on the sample, so six trials take about 4 s. It runs in the run's thread, so nothing blocks.
- **Condensing drop**: the world's law prices a shell at its inlet vapour, so a condenser's drop reads high. It sets the sample's condenser at 3 shells.
- **A column at a far diameter**: a trial can fail to solve. It counts as no fit, and the search moves on.
