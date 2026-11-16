# Fouling and corrosion system: plan

Batch `2026-11-16-fouling-corrosion`, branch `claude/fouling-corrosion-system-ba0275`, cut from `main` at `f305ac6b0` (0.32.0). Status: plan, no code.

## 1. The request (owner, 2026-11-16)

- Fouling raises pressure drop and lowers the heat transfer coefficient. The fouling mechanism comes later, with the reaction module.
- Corrosion leads to leaks and breakdown, and interacts with the pollution system. Sounds: propose first, not heavy.
- Broken equipment is repaired only when idle and empty. Equipment damaged by an explosion cannot be repaired; it is deconstructed.
- Probably machines only: every pipe section would cost too much CPU.
- All current process equipment is carbon steel. Add the other common refinery materials, and add them to the constructor.
- Corrosive pollution adds to equipment corrosion.
- Research the corrosion types and rates of a refinery as a matrix. Add a material selection tab to the process simulator.
- Performance over realism and accuracy.

## 2. What exists today

| Piece | Where | What it does | Gap |
|---|---|---|---|
| Machine wear | `science/machine/Wear`, `runtime/fluid/MachineHealth` | Health 1 to 0 per mover and turbine from six faults; `FAILED` closes it; one repair kit restores it, running or not | No wall, no chemistry |
| Blast breach | `runtime/pollution/Breaches`, `FluidDeviceSpec.breached` (facts schema 26) | A blast opens a closed vessel (vents, spills) and fails a machine; a repair kit closes it | Repairable; the owner now wants it final |
| Corrosive air | `science/pollution/Channel.CORROSIVE`, `PollutionService.index` | A cube's corrosive index (SO2, NH3, H314 compounds over their IDLH) | Nothing reads it for equipment (plan note of the pollution batch) |
| Heat films | `HeatCurve.Films.DEFAULT` (2000, 5000, 100 W/m²K) | Each film "with its share of the wall and fouling", constant | No fouling state |
| Sizing | `SIZING_PLAN.md` rows "No fouling allowance", "fouling and erosion have no law in the world" | | To be closed here |
| Sulfur | assays: `sulfur_mass_percent`, "metadata only; no sulfur species are carried" | H2S, mercaptans, COS, SO2 are real compounds | The crude cuts carry no sulfur and no acid number |
| Construction | `EquipmentStructure`, the constructor | Free: no items are consumed | An alloy has no price today |

## 3. Physics, in numbers

### 3.1 Corrosion

A wall loses thickness at a rate set by its material and the fluid on it. Design adds a corrosion allowance (CA): 3 mm on carbon and low-alloy steel, 1.5 mm on 12Cr and higher alloys. A rate of 0.1 mm/y or less is acceptable (30 years on 3 mm). When the allowance is gone the wall no longer holds its pressure: a vessel leaks, a tube bundle leaks, a casing fails.

Steady states the player should meet:

| Service | Carbon steel | Right material |
|---|---|---|
| Crude at 370 °C, 0.6 wt% S | 1.3 mm/y: 2.3 years | 9Cr 0.16 mm/y; 12Cr 0.05 mm/y |
| Heavy crude at 350 °C, acid number 3 | 1.5 mm/y: 2 years | 316L 0.03 mm/y |
| Sour water, H2S 1 bar, 60 °C | 0.3 mm/y: 10 years | 304L 0.006 mm/y |
| Wet CO2, 1 bar, 60 °C | 1.4 mm/y | 12Cr 0.03 mm/y |
| Open cooling water, 35 °C | 0.3 mm/y: 10 years | 304L |
| Dry hydrocarbon gas, clean liquid, steam | 0 | carbon steel |

### 3.2 Fouling

A deposit of resistance `Rf` (m²K/W) sits in series with a side's film: `h' = h / (1 + h·Rf)`. Its thickness `δ = Rf·k_d` (`k_d` = 1 W/mK) narrows the passage: at the same flow the drop rises by `(d / (d − 2δ))⁵`.

| Rf (m²K/W) | Liquid film 2000 | Two-phase 5000 | Gas 100 | δ | Drop, 20 mm passage |
|---|---|---|---|---|---|
| 0 | 2000 | 5000 | 100 | 0 | × 1.00 |
| 0.0002 | 1429 | 2500 | 98 | 0.2 mm | × 1.11 |
| 0.0005 | 1000 | 1429 | 95 | 0.5 mm | × 1.29 |
| 0.001 | 667 | 833 | 91 | 1 mm | × 1.69 |
| 0.002 (cap) | 400 | 455 | 83 | 2 mm | × 3.05 |

A liquid–liquid exchanger with 0.0005 on both sides falls from U = 1000 to 500 W/m²K.

## 4. Scope and CPU

**Decided (D1): constructed equipment only.** Pipes, fittings, valves and one-block tanks do not corrode or foul. This follows the owner's pollution decision of 2026-11-15 ("pipes get no damage meter, for the sake of simplicity").

Corroding devices: every fluid device of a constructed structure that is a closed vessel (drum, sphere, column shell, kettle shell, converter gas tank), a heat side (exchanger, kettle tubes, air cooler, fired coils) or a machine with a health record (pump and compressor casings, vacuum pumps, steam turbine). Fouling devices: heat sides only.

Not in this batch: open structures (floating-roof tanks, cooling towers, chimneys, flare stacks; their failure is a ground spill, which the pollution batch left for later), drawing-only kinds (reactors: with the reaction module), in-line pumps and compressors (pipe blocks).

Cost, per device and per cadence (5 s), on the server thread, as `MachineHealth` books today:

- One digest of its node's last committed state: three dot products over the compound list (at most about 50 compounds).
- Nine closed-form laws on the digest, one table row for its material.
- Nothing in the solver. Nothing per slice: a commit only stores a reference to the island's last result.
- Bound: at most 256 devices evaluated per cadence, round-robin; a device not evaluated keeps its last rate. About 1 µs per device, so 0.3 ms per 5 s at the bound.

The solver sees a change only when a wall fails (once) or a fouling level steps (at most 20 steps per side between cleanings).

## 5. Materials of construction

Named `Metallurgy` in code, because "material" already means a compound (`MaterialCatalog`). An enum saved by name; append-only.

| Constant | Material | CA | Limit (°C) | Cost index | Its place |
|---|---|---|---|---|---|
| `CARBON_STEEL` | Carbon steel (default, every structure today) | 3 mm | 540 | 1.0 | Dry and clean service |
| `CR5` | 5Cr-½Mo | 3 mm | 620 | 1.8 | Hot sulfur oil, moderate |
| `CR9` | 9Cr-1Mo | 3 mm | 650 | 2.3 | Hot sulfur oil; heater tubes |
| `CR12` | 12Cr (410) | 1.5 mm | 700 | 2.5 | Hot sulfur oil, wet CO2 |
| `SS304L` | 304L stainless | 1.5 mm | 815 | 3.0 | Wet acid gas, hot H2/H2S |
| `SS316L` | 316L stainless | 1.5 mm | 815 | 4.0 | Naphthenic acid |
| `DUPLEX_2205` | Duplex 2205 | 1.5 mm | 315 | 5.0 | Ammonium bisulfide, acid condensate |
| `ALLOY_625` | Alloy 625 | 1.5 mm | 980 | 20 | Everything, at a price |

Left out until their mechanism exists: Alloy 400 and titanium (HCl overheads, sea water), 1¼Cr-½Mo and 2¼Cr-1Mo (hydrogen attack), copper alloys, cladding.

**Decided (D2): one material per fluid device.** A structure has a body material (`material`) and, where it has tubes or coils (exchanger, kettle, air cooler, fired heaters), a tube material (`tubeMaterial`, default the body's). The shell-side device reads `material`, the tube-side device `tubeMaterial`. A tube wall is counted on its tube-side stream only.

**Open (O2): the price of an alloy.** Construction is free today, so nothing stops Alloy 625 everywhere. See section 13.

## 6. The corrosion matrix

Sources for the forms and anchors: API RP 571 (damage mechanisms), API RP 581 Part 2 Annex B (thinning rate tables), API RP 939-C and the modified McConomy curves (sulfidation; the 1986 revision divides the 1963 curves by 2.5; scatter about one order of magnitude), Couper–Gorman (H2/H2S), API RP 932-B (ammonium bisulfide), de Waard–Milliams 1975 (CO2), API Std 530 (tube metal temperature limits), ISO 9223 (atmospheres). The numbers below are game-level fits to those anchors, not the tables themselves.

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

The inside rate is the largest of M1 to M8 (the governing mechanism, as API 581 takes it); the status names it. The total is the inside rate plus M9.

### 6.3 Material factors (rate = carbon steel rate × factor)

| Mechanism | CS | 5Cr | 9Cr | 12Cr | 304L | 316L | 2205 | 625 |
|---|---|---|---|---|---|---|---|---|
| M1 Sulfidation | 1 | 0.35 | 0.12 | 0.04 | 0.01 | 0.01 | 0.01 | 0.01 |
| M1 with `p_H2` > 5 bar | 1 | 0.8 | 0.8 | 0.2 | 0.02 | 0.02 | 0.02 | 0.01 |
| M2 Naphthenic acid | 1 | 0.8 | 0.6 | 0.5 | 0.2 | 0.02 | 0.05 | 0.01 |
| M3 Sour water | 1 | 1 | 1 | 0.3 | 0.02 | 0.01 | 0.01 | 0.005 |
| M4 Wet CO2 | 1 | 0.6 | 0.3 | 0.02 | 0.005 | 0.005 | 0.005 | 0.002 |
| M5 Ammonium bisulfide | 1 | 1 | 1 | 0.8 | 0.3 | 0.2 | 0.05 | 0.02 |
| M6 Acid condensate | 1 | 1 | 1 | 0.8 | 0.3 | 0.1 | 0.05 | 0.02 |
| M7 Aerated water | 1 | 0.8 | 0.6 | 0.1 | 0.005 | 0.005 | 0.005 | 0.002 |
| M9 Corrosive air | 1 | 0.8 | 0.6 | 0.1 | 0.01 | 0.01 | 0.01 | 0.005 |

Later, with their species: HCl overhead corrosion and chloride cracking (a desalter and salts), high-temperature hydrogen attack (hydroprocessing reactors), amine and caustic cracking, polythionic acid, erosion by velocity.

### 6.4 Data

- `corrosion` sub-record on the twelve crude cut datasets: `sulfur_mass_percent`, `acid_number`, `estimated`, `source`, `revision`. In no identity, as `hazard` is. Sulfur rises with the cut (0.02 wt% for the lightest to about 5 wt% for the residue) so that the four assays reproduce their `sulfur_mass_percent` within 20 %; the acid number rises the same way and gives about 0.1 (light), 0.3 (medium), 1.5 (heavy) and 2.5 (dilbit) mg KOH/g on the whole crudes. All marked estimated.
- Real compounds need no record: their sulfur is in their formula.

### 6.5 Time

**Decided (D3): one corrosion year is 20 process hours** (5 hours of play at `fluid.timeScale` 4), config `corrosion.yearProcessHours`. Loss booked: `rate × Δt_process / 20 h`.

| Rate | Carbon steel wall (3 mm) lasts |
|---|---|
| 0.1 mm/y (acceptable) | 600 process hours: 150 hours of play |
| 0.3 mm/y | 200 process hours: 50 hours of play |
| 1.3 mm/y (crude at 370 °C) | 46 process hours: 11.5 hours of play |
| 5 mm/y (the cap of most rows) | 12 process hours: 3 hours of play |

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

A graded pinhole leak (an orifice row on the node) is not built: it would add a boundary law to the step for a small effect.

## 8. Blast damage is final

`Breaches.breach` additionally marks every device of the structure `wrecked` (a fact). A wrecked device:

- keeps the effect it has today (vessel open, machine failed; a heat side closed as in section 7);
- refuses the repair kit: `WRECKED: deconstruct and rebuild`;
- is removed only by the constructor's deconstruction.

A vessel breached before this batch (schema 26, no `wrecked`) reads as not wrecked: it stays repairable. That is the behaviour before the field existed.

## 9. Repair and cleaning: idle and empty

A repair kit on a structure cell queues one event for the structure (as today). It is accepted only when every device of the structure is:

- **idle**: no flow through it in its last committed slice (|mass flow| below the solver's trace), a machine at no speed;
- **empty**: a vessel holds less than 1 % of its volume as liquid and stands within 0.2 bar of the atmosphere; a heat side or a machine has no inventory, so idle is enough.

Then: wall loss 0, health 1, breach closed, a failed heat side opened. Otherwise the reply names what is missing (`NOT IDLE`, `NOT EMPTY`, `WRECKED`) and the kit is kept. This also holds for a worn machine that still runs: today's rule (a kit repairs a running pump) ends.

Cleaning: a `Clean` key on a heat unit's page queues an event; same idle-and-empty rule; no item; fouling of both sides to 0.

## 10. Corrosive air

At the cadence the service reads `PollutionService.index(dimension, origin, CORROSIVE)` of the cube that holds the structure's origin: one read of the installed field per structure, never a chunk load. M9 adds to every device of the structure. Off with `pollution.enabled` or `corrosion.external` false.

A corroded vessel that leaks SO2 or H2S raises the index round its neighbours: the loop the owner asked for.

## 11. Fouling

- State: `Rf` per heat side, a fact, 0 to 0.002 m²K/W.
- Effect: `HeatCurve.Films.fouled(Rf)` (section 3.2) and the passage's section diameter less `2 Rf k_d`.
- The graph changes only in steps of 0.0001 m²K/W: when a side's level changes, one queued respecify edit gives it its new films and diameter. Between steps the solver's inputs are bit for bit the same.
- Sizing: the simulator's exchanger, cooler and heater rows size on a design `Rf` per side (the service's asymptote), so a unit sent to the block meets its duty dirty. This closes the two `SIZING_PLAN.md` rows.
- Words: `FOULED` from `Rf` ≥ 0.001; the heat page shows `Rf` and the clean-to-now duty ratio.

**Open (O1): what grows `Rf` before the reaction module exists.** Recommended: a placeholder, Kern–Seaton, `dRf/dt = (Rf∞ − Rf)/τ` while the side flows, `τ` = 10 process hours (config), `Rf∞` from the digest:

| Stream on the side | `Rf∞` (m²K/W) | Anchor |
|---|---|---|
| Gas, steam, light clean liquid | 0 | |
| Water (`wet`, mostly aqueous) | 0.00035; 0.0007 above 50 °C | TEMA tower water 0.00018 to 0.00035 |
| Crude and heavy oil below 260 °C | 0.0005 | TEMA crude 0.0004 to 0.0012 by temperature and speed |
| Crude and heavy oil from 260 °C | 0.0009 | |
| Residue (heaviest two cuts above half the liquid) | 0.0018 | TEMA fuel oil 0.0009, vacuum residue 0.0018 |

"Heavy" is read from a per-cut `fouling` number in the same `corrosion` sub-record. The reaction module later replaces this rate source and nothing else. The other choice is effect only: `Rf` moves by command and test alone until then.

## 12. Constructor and simulator

- Constructor, DESIGN page: a `Material` dropdown, and `Tube material` on kinds with tubes. The datasheet names both and the CA. Equipment shape fields 13: `material`, `tubeMaterial`, read by name in `ShapeRepair`, default `CARBON_STEEL`.
- Device pages: an Integrity group (wall left as a meter, the rate in mm/y, the governing mechanism, the life left in hours of play) beside Health; the words of sections 7 to 9.
- Simulator: a fifth tab, `METALLURGY` (label "Materials"). One row per unit side: its service (temperature, governing mechanism), the rate of the chosen material with a lamp (green ≤ 0.1 mm/y, amber ≤ 0.5, red above), Auto or Manual choice as Sizing has, the cost index. Auto takes the cheapest material that is green, else the lowest rate. It runs `CorrosionLaw` on the sheet's streams on the client; nothing on the server. Send to block carries the choice in the shape. Sheet file format 3.
- Tutorial: one Field Guide page and the tab's help tour (with the tab's work package).

## 13. Decisions

Taken (the agent's, reversible until the first code commit that uses them): D1 equipment only; D2 one material per device, body and tubes; D3 the time scale; D4 failure by the existing failures; the eight materials; the matrix numbers; repair and cleaning rules as section 9.

For the owner:

- **O1** Fouling growth before the reaction module: the Kern–Seaton placeholder (recommended), or effect only.
- **O2** Alloy price: (a) none in this batch: the cost index is shown in the simulator and on the datasheet, and a later survival-supplies batch charges it (recommended; survival supplies is already an open item of the tutorial batch); (b) alloy plates as items now, consumed by the constructor in survival.
- **O3** Sounds (section 14).
- **O4** Format numbers on `main` (section 15) and the build's delegations (section 16).

## 14. Sounds (proposal; nothing made yet)

| Event | Proposal | New file |
|---|---|---|
| `WEEPING` wall | `wall_weep`: a thin hiss with irregular drips, heard to about 8 blocks, in the `FxSounds` loop budget | 1 loop |
| Wall fails | The existing `pipe_burst_gas`, `pipe_burst_liquid`, `pipe_burst_empty`, chosen by what the device held | none |
| Repair accepted | The repair kit's sound of today | none |
| Cleaning accepted | `exchanger_clean`: a two-second water-jet burst | 1 one-shot (optional) |

Set A: the loop only. Set B: the loop and the cleaning one-shot. After the owner picks, three synthesised candidates per new sound go to the owner (`models-and-sfx` skill).

## 15. Formats and config

| Format | Now | This batch |
|---|---|---|
| Fluid facts schema | 26 | 27: per device `wallLoss`, `fouling`, `wrecked` in `MachineFacts`, each written only when not at its default |
| Equipment shape fields | 12 | 13: `material`, `tubeMaterial` |
| Wire protocol | `icheme-2` | `icheme-3`: views carry integrity; the constructor payload carries the materials |
| Simulator sheet file | 2 | 3 |
| Material data | | `corrosion` sub-record with its own revision, in no identity |

Reservation (COLLABORATION.md section 3): a documentation-only commit on `main` adding the rows "reserved by 2026-11-16-fouling-corrosion" to `FORMATS.md`. It needs the owner's word, as every commit on `main` does.

Corpus images: `compat/fluid/` for schema 27, `compat/equipment/shapes-13.snbt`.

Config: `corrosion.enabled`, `corrosion.yearProcessHours` (20), `corrosion.external`, `fouling.enabled`, `fouling.asymptoteProcessHours` (10). With `corrosion.enabled` false nothing is booked and nothing fails; blast wrecking stays under `pollution.equipmentBreach`.

## 16. Work packages

About 4 000 lines of production code: three stages, each leaving `main` working (COLLABORATION.md section 4).

| Stage | WP | Content | Lane, hot files | After |
|---|---|---|---|---|
| A | WP1 | `science/integrity/` (new, pure): `Metallurgy`, `CorrosionDigest`, `CorrosionLaw`, `FoulingLaw`, `WallState`; `MaterialCatalog.Corrosion` and the data; tests | thermo: `MaterialCatalog` | |
| A | WP2 | `runtime/fluid/Integrity` beside `MachineHealth`: booking at the cadence, failures, `wrecked`, the repair and clean events, corrosive air, facts schema 27, `/icheme integrity`, corpus, GameTests | runtime, machines: `FluidFacts`, `FluidWorldAuthority`, `ShaftTrains`; `Breaches`, `RepairKitItem` | WP1 |
| A | WP3 | Shape fields 13, `ShapeRepair`, the constructor's dropdowns, the datasheet, the registration that hands a device its material | equipment, client: `EquipmentShape`, `EquipmentStructure` | WP1; beside WP2 |
| A | WP4 | Integrity group on the device pages, status words, drip particles, the weep loop | client; runtime presentation | WP2, WP3 |
| B | WP5 | Fouling: `Films.fouled`, the passage diameter, the level steps, cleaning, the heat page, sizing on design `Rf` | fluid laws, flowsheet: `SheetSizing` | stage A |
| C | WP6 | Simulator `METALLURGY` tab, Auto and Manual, Send to block, sheet file 3, the tour and guide page | flowsheet, client | WP1, WP3 |
| each | | Code guide chapter `integrity.md`, `FORMATS.md` rows, README history, final lane, translation commit at the merge | documents | |

WP2 and WP3 write disjoint files and can run as two threads at once; so can WP5 and WP6. Each work package gets a full brief (files, methods, numbers, tests, pitfalls) when it starts.

### WP1 in detail

New package `src/main/java/com/wormzjl/icheme/science/integrity/`, pure (no Minecraft types, `DeterministicMath` for `exp`, `pow`, `log10`; `DeterministicMathSourceTest` bans the `Math` ones).

- `Metallurgy`: the eight constants of section 5 with `allowance()` (mm), `limit()` (K), `cost()`, `factor(Mechanism, boolean hydrogen)` from section 6.3; `byName(String)` with an alias map (empty), default `CARBON_STEEL`.
- `Mechanism`: `SULFIDATION`, `NAPHTHENIC_ACID`, `SOUR_WATER`, `WET_CO2`, `AMMONIUM_BISULFIDE`, `ACID_CONDENSATE`, `AERATED_WATER`, `OVERHEATING`, `CORROSIVE_AIR`.
- `CorrosionStandard`: built once per compound list, as `HazardStandard` is: per compound its molar mass, sulfur mass share (formula, else the sub-record), acid number, fouling number, and the indices of H2S, CO2, SO2, NH3, O2, H2 and water found by formula. `digest(moles, temperature, pressure, oilMoles, aqueousMoles)` returns a `CorrosionDigest` record (section 6.1). No allocation beyond the record.
- `CorrosionLaw`: `rate(Mechanism, CorrosionDigest)` for carbon steel (section 6.2), `inside(Metallurgy, CorrosionDigest)` returning the governing mechanism and rate, `outside(Metallurgy, index)`. Every rate finite and ≥ 0 for any finite digest; NaN inputs give 0.
- `FoulingLaw`: `fouled(HeatCurve.Films, rf)` is not here (it belongs to `HeatCurve`, WP5); here only `asymptote(CorrosionDigest)`, `step(rf, asymptote, dt, tau)`, `level(rf)` (steps of 1e-4, cap 0.002), `thickness(rf)`.
- `WallState`: `stage(loss, allowance)` to `SOUND`, `THIN`, `WEEPING`, `THROUGH`; `lifeSeconds(loss, allowance, rate, yearProcessHours)`.
- `MaterialCatalog`: record `Corrosion(sulfurMassPercent, acidNumber, fouling, estimated, source, revision)`, `readCorrosion(JsonObject)` beside `readHazard`, a map by record key, `corrosion(packageId, componentId)`, and the per-package arrays in conserved order as `toxicReferences` gives them. The twelve crude cut datasets get the sub-record.
- Tests (`src/test/java/.../science/integrity/`, and `MaterialCorrosionTest` beside `MaterialHazardTest`): each anchor of sections 3.1 and 6.2 to 1 %; every factor row; monotone in temperature and in the driving pressure; the four assays' sulfur within 20 % of their specification; `LayeringTest` and `DeterministicMathSourceTest` still pass.
- Commands: `./gradlew test --tests "com.wormzjl.icheme.science.integrity.*" --tests "*MaterialCorrosionTest" --tests "*MaterialHazardTest" --tests "*MaterialCatalogTest" --tests "*LayeringTest" --tests "*DeterministicMathSourceTest"`. The gates that load the material data (`columnGates`, `equipmentGates`) are the final lane's.
- Done: the tests pass; no class outside the new package and `MaterialCatalog` changed; no reference moved (the sub-record is in no identity).
- Pitfalls: a compound list restricted to an island's view has other indices than the world's, so the standard is built per list, by name; the water is the last conserved entry and has no dataset record; a package without the crude cuts gives `S` = 0 and no error.

## 17. Risks

- `MaterialCatalog`, `FluidFacts`, `FluidWorldAuthority`, `EquipmentShape` and `SheetSizing` are hot files. The branches `claude/fluid-interfaces-*`, `claude/supercritical-*` and `claude/fluid-step-jacobian` exist; whether any holds these lanes is the owner's to say.
- A heat side closed by a failure must not be reopened by its own built-in control or by a layout refresh (`StructureRegistration` must carry it, as it carries `breached`).
- A repair-rule change is visible to existing worlds: a worn pump must now be stopped before its kit is accepted.
- The matrix numbers are fits. A change of a factor is a data edit in one table and moves no solver reference.
