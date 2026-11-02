# Sizing stage plan

Batch [2026-11-02-process-simulator](README.md). Status: criteria and drawings for the owner's review (sheets 24 to 27). Nothing here is built. It follows [PROCESS_SIMULATOR_PLAN.md](PROCESS_SIMULATOR_PLAN.md).

## 1. Expected behaviour

A converged flowsheet says what flows, and at what state. It does not say how big anything is. To build it, every unit and line needs a size that the constructor can place and the world can run, at a flow above the steady one, with drops that stay inside what the sheet assumed.

So the player finishes the flowsheet, opens the Sizing tab and presses Size all. The stage reads the steady streams and gives each unit and line the smallest size that meets its limits. It says what limits each size, and where a limit cannot be met. Then Send to block hands the sizes to the constructor.

The sample shows it, at 110 % of the steady flow:

- 16 units and 31 lines sized. The units carry 98 limits. The 42 Musts are all met. Of the 56 Shoulds one is missed: E-102's tube speed, 0.59 m/s against 0.60.
- The column stays at the door's 8.0 m: flood 71.3 % at design flow, and 7.5 m gives 80.9 %.
- H-101 fits only a cabin heater: 86.2 MW needed, 54.9 MW the largest vertical, 87.6 MW in a cabin 11.0 × 41 m.
- The heaviest lines reach the pipe tool's limit: S-7 and the overhead take the largest bore, 0.80 m.
- The pressure paths close: P-101 loses 3.66 of its 9.00 bar, and the residue line 0.51 of 0.99 bar with no pump.

Every number comes from `tools/process-simulator/SizingProbe.java`, on the shipped laws. It writes [sizing-sample.tsv](../../tools/process-simulator/sizing-sample.tsv), and the sheets draw that table.

## 2. What exists

The mod already knows what each size does; only the choice is missing.

| Item | What a size is | What it holds, in code |
|---|---|---|
| Pipe | Any bore to 0.8 m (`PipeSizes.MAX_BORE`) | `PipeResistance`: homogeneous Darcy-Weisbach; 100 m/s cap (`DEFAULT_MAXIMUM_VELOCITY`) |
| Exchanger | Shell 0.5 to 2.5 m, step 0.25; tubes from 3 D to 12 m | `tubeSurface` 75 D² L; `HeatSides.tubePassage`, `shellPassage`; world films 2000 liquid, 5000 two-phase, 100 gas W/m²K |
| Kettle | Bundle 0.5 to 2 m, tubes to 10 m | Shell 1.6 bundles (`kettleShell`) |
| Air cooler | Bay 1.5 to 3 by 2 to 6 m; bank ≤ 6 parallel, ≤ 4 series | 5 m² bare per m² face, 430 W/m²K bare, air 25 °C; `airCoolerPassage`, `fanPower` |
| Fired heater | Vertical D 2.5 to 12, to 20 m; cabin W 4 to 12, L to 48 m | `heaterDuty`: 35 kW/m², 70 % radiant, at most 54.9 and 112.7 MW; `heaterPassage`: 8 MW a pass |
| Pump | Discharge 0.05 to 0.6 m | `pumpFlow`: 3 m/s in the discharge, a datasheet value. The world's limits are the controls': 10 m³/s, 2 MPa of water head, 2 % vapour by volume at the suction |
| Compressor | Piston bore 0.15 to 1.2 m, 2 to 10 throws; turbo impeller 0.3 to 1.5 m, 2 to 9 | `displacement`; the world's law is ideal isothermal work, ratio 1.01 to 10, 1 % condensed by mass |
| Drum | D 0.5 to 10 m, step 0.5; T/T to 64 m; boot from `bootFor` | Height by `minimumBootSupport` |
| Column | D 0.5 to 15 m, to 64 trays | Fixed trays: `V3TrayHydraulics`, spacing 0.6 m, downcomer 10 %, Fair flood; `NozzleBore.largest` |
| Tank, sphere | Tank D 4 to 40, shell 4 to 24 m; sphere D 4 to 24 m | `FluidDeviceSpec.MAX_VOLUME` 30000 m³ |
| Valve | No Cv in the world | A drop between two pressures |

## 3. Proposal

### 3.1 The stage

Commercial simulators work in the same order: converge with simple blocks, size from the converged rates, rate the sizes against limits, and re-simulate if the rated values differ (note, section 12). Here it is one tab.

1. Sizing is off until the sheet reaches a steady state, with a hover saying why.
2. Size all runs on the client, in the run's background thread; Stop reaches it. Lines and units are arithmetic. A column runs the real solver at each trial diameter.
3. Sizing moves no process number, with one exception: a new column diameter moves the tray drops, so Size all runs the sheet again and rates once more.
4. Each item is Auto or Fixed. Auto takes the smallest size that meets every limit. Fixed keeps the player's size and rates it against the same limits. So one stage sizes and rates.
5. A later run rates every size again when it ends, with no trial columns, so the lamps stay true. Sizes change only on Size all, or by the player's hand.
6. Undo takes back a Size all as one step.

A lamp gives the state. Green meets every limit. Yellow misses a Should: outside good practice, and it still runs. Red misses a Must: the world refuses it, its own model says it fails, or the constructor cannot build it. A red row stops Send to block. A yellow row does not.

### 3.2 Design basis

Three entries in the tab's Design group, for the whole sheet.

| Entry | Default | Why | Source |
|---|---|---|---|
| Design flow | 110 % of steady | Rated 110 % of normal; +10 % for surge and reflux. Every limit applies at this flow | [ET], [MARG], [BRAN] |
| Line length | 50 m with fittings | Only the Pressure view reads it; each line can hold its own | This plan |
| PA fall | 60 K | The column model has no circulation; a fall across the cooler gives it | This plan, unsourced |

### 3.3 Lines

Every line: bore ≤ 0.80 m a run, speed ≤ 100 m/s, bore ≤ what its nozzle takes (`NozzleBore.largest`). These are Musts, all from the world.

The service comes from the stream, and the player may change it. Water above 95 mole % of a liquid is Water. A stream above 95 % vapour by mass is Gas, or Steam if its gas is water. Above 0.05 % vapour it is Two-phase. A liquid leaving a tray is Draw. One that feeds a pump is Suction: boiling if within 0.3 bar of its bubble pressure, cold otherwise. The rest is Liquid.

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
| Surface | Must | ≥ Q / (U LMTD), world films | World |
| Hot end, cold end | Must | > 0 K | World: counterflow |
| Least approach | Should | ≥ 10 K | [BLOG] |
| Tube speed, liquid | Should | 1 to 3 m/s | [BLOG] |
| Tube speed, gas | Should | 9 to 30 m/s | [BLOG] |
| Shell speed, liquid | Should | 0.3 to 2 m/s, twice the source's 1 (D14) | [SCR-HX] |
| Shell speed, vapour | Should | ≤ 30 m/s; 70 m/s under vacuum | [SCR-HX] |
| Drop, liquid side | Should | ≤ 0.7 bar | [BLOG] |
| Drop, condensing or gas side | Should | ≤ 0.2 bar | [SCR-HX], [BLOG] |
| Drop, boiling side | Should | ≤ 0.1 bar | [BLOG] |
| Surface over the need | Should | ≤ 100 % | This plan |
| Cooling water out | Should | ≤ 45 °C | [BLOG] |
| Kettle flux | Should | ≤ 38 kW/m² | [ET] |

The source gives a condensing side 0.1 bar and a gas side 0.2 bar [SCR-HX]. A condenser takes the gas row here, because the world's law prices its whole shell as inlet vapour.

**Air coolers**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Bare surface | Must | Holds the UA at air 25 °C | World |
| Outlet above air | Should | ≥ 15 K | [HALL-R] |
| Tube speed, liquid | Should | 0.6 to 2.4 m/s | [CHECALC] |
| Tube speed, gas | Should | 1.5 to 15 m/s | [CHECALC] |
| Tube drop | Should | ≤ 0.7 bar | [WEAK] |

**Fired heaters**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Duty held | Must | `heaterDuty` ≥ design duty | World |
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
| Kind by vapour pressure | Should | Floating roof at 5.2 to 76.6 kPa, sphere above | [CFR], [KC] |

**Valves and ejectors**

| Limit | Kind | Value | Source |
|---|---|---|---|
| Valve drop | Should | ≥ 20 % of the path, ≥ 0.7 bar | [HALL-V] |
| Ejector compression a stage | Should | ≤ 7 | [SK], [EJ-X] |
| Ejector stages by suction | Should | 1 above 100 mbar, 2 above 13, 3 above 1.3 | [WEAK], [EJ-X] |

A valve takes its line's bore: the world's valve has no Cv.

### 3.5 Selection

Auto takes the smallest size that meets every limit, Must and Should. Where none does, it takes the smallest that meets the Musts and comes nearest the Shoulds, and the row stays yellow. Where no Must can be met, the row is red.

| Item | Auto varies, in this order |
|---|---|
| Line | Bore through the usual sizes, then parallel runs to 4, with a yellow lamp over one |
| Exchanger | Fewest shells to 6, then least surface, under twice the need. Without the cap the sample's condenser takes 2 × 1.75 × 6 m, 220 % over the need, where it now takes 3 × 1.25 × 4 m, 63 % over |
| Air cooler | Least face |
| Heater | Least duty that holds, either kind |
| Pump | Smallest discharge whose rated flow fits the window |
| Column | Diameter in steps of 0.5 m, real solves |
| Drum, tank | Least volume |

### 3.6 Blocks and pressure paths

A block is one row with its members under it, as its door builds them: condenser, receiver, reflux pump, pumparound coolers and pumps. The door's pumparound has no circulation, so its pump, lines and cooler take it from PA fall here. Its cold side's duty stays the door's.

A pressure path runs from a machine's outlet to the next pressure the sheet sets. The door's drops were the player's estimates. The Pressure view adds up the sized ones at design flow, with static head where nozzle heights give it. A negative spare is red. A positive spare is what a valve takes. The tool finds the paths from the sheet.

### 3.7 Hand-off

Send to block stores each unit's design on the block as an `EquipmentShape`, as Save design does for a column today. The constructor's Import lists them all, each with its tag and count, and lands the chosen one on the open draft. The protocol changes, and needs no compatibility. The Build view's line list stays with the player, who lays each line at its bore with the pipe tool. Nothing places itself.

### 3.8 Screen

Sheets 24 to 27. One tab with four views: Units, Lines, Pressure, Build. The left door holds the Design group and the selected item's Auto and Fixed keys and sizes. The glass holds the table, the limits of the selected item and the path's stair. Close, Size all (the confirming key), Send to block, Undo and Redo sit in the footer.

### 3.9 Formats

Sizes, Auto or Fixed, are facts of the sheet: new fields with defaults, under the sheet's own rule (main plan, section 3.9). Lamps, values and paths are state, rebuilt when the tab opens. An old sheet opens with every item on Auto and no size yet.

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
| PA 1, 2, 3 coolers | Shell 1.00 m, tubes 3 m each | Surface 225 of 224 and 223 m²; PA 3 tube speed 1.99 m/s |
| PA 1, 2, 3 pumps | Discharge 0.25, 0.30, 0.25 m, 13 to 14 m | 110, 93, 78 % of rated |
| P-102 | Discharge 0.15 m, 51 m | 71 % of rated |
| E-102 | 5 × 4 bays of 2.0 × 6 m | Tube speed 0.59 m/s, at least 0.60: yellow |

Three results went against what the sheet's author would guess.

- The boot's 5 min, not the oil's, sets the receiver: D 3.0 m holds the oil, and the water needs D 4.0 m.
- H-101's door took 7.60 bar, and its coils lose 1.95 bar at design flow. So 5.34 bar stays spare, and P-101's outlet could fall to 8.66 bar.
- P-102's warning clears. The run warns because a tray draw leaves at its bubble point and the sheet knows no heights. Here the draw nozzle's 21 m of static head and the steam's share of the tray pressure give 31 m of NPSH.

## 5. Work packages

They follow W1 to W9 of the main plan. Each ends with the quick suite and a commit.

| WP | What | Gates |
|---|---|---|
| S1 | `science/flowsheet/sizing`: the limits as one table with source tags, services, line sizing | Each service at both sides of its limit; the sample's lines equal the probe's |
| S2 | Units: pump, exchanger, kettle, air cooler, heater, compressor, drum, tank, valve, ejector; one rule both sizes and rates | Per class: the size holds, one step down fails, Fixed reports the same limits |
| S3 | Column and members: trial diameters in the run thread, re-run on a new diameter, circulation, member heads | Stop reaches a trial; the sample's flood at each diameter; no process number moves but a column's |
| S4 | Pressure paths, the tab and its four views, lamps, Auto and Fixed, formats | Column-builder gates; sheets rendered from shipped code; a corpus image; an old sheet opens |
| S5 | Send to block, the constructor's list, the coverage gate: every equipment kind declares its sizing or says why it has none; code guide chapter | Import gate; coverage gate |

## 6. Decisions for the owner

| # | Decision | Recommended | Why |
|---|---|---|---|
| D13 | Basis defaults | Design flow 110 %, Line length 50 m, PA fall 60 K | The flow is sourced. The other two are mine, and the player can change them |
| D14 | The Must and Should split, and shell speed | As section 3: red only for what the world refuses or its model fails, yellow for practice. Shell speed 2 m/s, not the source's 1 | The world's film does not depend on speed, and for heavy oil its U is 2 to 4 times real, so a shell of the right surface runs fast. At 1 m/s PA 1's cooler takes 3 shells, 380 m² for 225; PA 3's takes 469 m²; PA 2's has no fit. The gain would be in vibration and erosion, which the world has no law for |
| D15 | Auto's rule | Smallest that meets every limit; exchangers by fewest shells under twice the need | The cap is mine, not a source's. It avoids a surface 3 times the need |
| D16 | Counts | A count on one row for parallel shells and runs; no spares | The world has no failure model. No source gave a rule for parallel pumps |
| D17 | Hand-off | Option A: Send to block stores every unit, Import lists them, lines stay a list | B: columns only, as today. Then a player types every other size into the constructor by hand |

### Simplifications to validate

| Simplification | Effect | Risk |
|---|---|---|
| World films for every U | E-101 needs 444 m². At U 400 W/m²K, inside the note's 150 to 1200, a real one needs about 1100 m² | Units look small to an engineer. They match the world, so a built plant does what the sheet says |
| No fouling allowance | None in the world | Same |
| Static head only from nozzle heights | Others count 0 | Safe side for NPSH |
| A pump's head from its sheet rise | P-102's 51 m ignores its 21 m of static gain | Oversizes the datasheet only |
| One Line length | The Pressure view only | A long line can set its own |
| Fixed tray geometry | Weir load below | None: the world's law prices it |

## 7. Left out, and why

- **Weir load**: 140 m³/h per m on the sample against 89 [KIST]. The world's tray is single-pass at any diameter, where a real 8 m tray has several passes. No size fixes it without a far larger column, and the V3 law already prices it as liquid head.
- **Heater mass flux**: 949 kg/s·m² against 1220 to 1710 [FH-DECK]. It follows from 8 MW a pass, so no size changes it. A pass of about 12 MW would reach it: a model question, not a sizing one.
- **Nozzle ρv² [TEMA], tube vibration, fan coverage, face speed**: the world has no law that reads them; the bay fixes the last two.
- **Mach, noise, flare and relief lines**: the world has no choke law (owner decision: 100 m/s cap) and no relief.
- **Slurries**: the flowsheet's streams carry no solids on day one. Durand's speed [DUR] is the rule when they do.
- **Coking, flue gas, stack**: no combustion model.
- **Mechanical design and cost**: wall thickness, wind, price. Out of the game's scope.

## 8. Risks

- **Secondary sources**: most values come from excerpts and rule lists, and the standards themselves were not read. Each stays tagged in the limits table, so one can change without touching code around it.
- **Yellow noise**: a lamp that is always yellow means nothing. The sample has one of 56; other sheets may have more. S2 runs the crude presets through the rules before the values freeze.
- **Trial cost**: a warm column took about 0.7 s on the sample, so six trials take about 4 s. It runs in the run's thread, so nothing blocks.
- **Condensing drop**: the world's law prices a shell at its inlet vapour, so a condenser's drop reads high. It sets the sample's condenser at 3 shells.
- **A column at a far diameter**: a trial can fail to solve. It counts as no fit, and the search moves on.
