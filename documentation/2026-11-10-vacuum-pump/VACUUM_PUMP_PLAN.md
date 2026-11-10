# Vacuum pump casings for the shaft trains

Batch 2026-11-10-vacuum-pump, branch `claude/vacuum-pump-bcd40b`, cut from `claude/custom-shader-system-caff9e` at `6e95356b5` (steam ejector flow, `Limit.VACUUM_SPEED`, rigid reservoirs). Owner, 2026-11-10: "Add a vacuum pump head to rotating machines"; "Both types & different models, in sub-category"; column assembly "yes, but this should be placed on the drum vapor outlet". Revision 1: the plan, decisions V1 to V9 for the owner.

## 1. Today

- Shaft-driven casings: `PUMP` and `CENTRIFUGAL_COMPRESSOR` run a `MachineLaw` on `FlowControl.Machine` at the train's speed share (`ShaftPlan` SPEED); the reciprocating `COMPRESSOR` frame runs `FlowControl.Compressor` on `PositiveDisplacement` (form F2e) at a DISPLACEMENT plan. `ShaftNetwork.Kind.CASING` covers every driven casing.
- `FlowControl.Ejector` already puts a suction-pressure-dependent capacity on the F2e row: displacement `Q(P_s)`, relief rise `P_dmax − P_s`, 1e-12 m³/s when the capacity is gone. A vacuum pump is the same row with a shaft.
- Column assembly: `ColumnComponents.ejectorRequired(top < 1 atm)`; the ejector sits at the column's VAPOUR_OUTLET, then the condenser, the receiver with its water boot, the offgas valve on the receiver's VAPOUR_OUTLET. `ColumnStarts` refuses a vacuum column ("a vacuum column cannot run yet", E6 A of the ejector batch). The sheet (`ColumnBlockSolve.ejector`) already loads the ejector with the **vent** (V3's vapour product at the condenser) and sends its steam to the free water.
- Rigid reservoirs: a closed vessel that was once taut keeps its volume and can stand below the ambient. A receiver drum on a vacuum pump therefore holds a vacuum once it has been full (of gas at 1 atm counts).

## 2. The curves (physics first)

A vacuum pump moves a suction volume per turn; its capacity falls to zero at its blank-off pressure. Capacity ∝ speed. Both laws are curves, not mechanisms (owner rule).

### 2.1 Liquid ring

The rotor slings a water ring that seals the cells. The gas in each cell is saturated with the ring's water vapour, so the dry-gas share of the displaced volume is `1 − P_v/P_s`; at `P_s = P_v(T_ring)` the cells fill with flashing water only (blank-off; below it the ring cavitates). One stage at a high pressure ratio loses more than two stages in series.

Capacity, m³/s at suction: `S = S_n s φ(P_s)`, with `φ = (1 − P_v/P_s)^n`, `n = 2` single stage, `n = 1` two stage, `P_v` the water's vapour pressure at the ring temperature (V2). The standard capacity-correction factor for a seal temperature other than the vendors' 15 °C basis is the ratio `φ(P_s, T)/φ(P_s, 15 °C)`; the form gives it, no table.

| P_s | 101 kPa | 50 | 20 | 10 | 5 | 4 | 3.17 |
|---|---|---|---|---|---|---|---|
| φ single, 25 °C ring (P_v 3.17 kPa) | 0.94 | 0.88 | 0.71 | 0.47 | 0.13 | 0.04 | 0 |
| φ two stage, 25 °C | 0.97 | 0.94 | 0.84 | 0.68 | 0.37 | 0.21 | 0 |
| correction 25 °C vs 15 °C, single / two stage | 0.97 / 0.98 | | 0.83 / 0.91 | 0.68 / 0.82 | 0.31 / 0.56 | | |

Vendors quote about 20 % capacity lost for +5 K at 53 mbar; the form gives 28 % (two stage) to 48 % (single): harsher, because it leaves out the condensation bonus a saturated load gets. Game level: accepted.

Shaft power: `P = P_f s³ + p_s S ln(P_d/p_s)/η_iso`, the ring's friction (∝ s³, as any impeller's) plus the near-isothermal compression work over `η_iso = 0.45`. The work peaks at `p_s = P_d/e` ≈ 37 kPa, where vendor power curves peak. Per m³/h of `S_n`: compression at most 21 W (two stage) / 19 W (single), friction `P_f` 9 W (single) / 12 W (two stage, two impellers): about 30 / 33 W per m³/h at the peak. Check: 500 m³/h single → 15 kW shaft, `Motor.ratingFor` 18.5 kW; the 2BE1-152 (510 m³/h) ships on 18.5 kW.

The gas leaves at its suction temperature (the ring keeps the compression isothermal): no work is booked into the gas. All shaft power goes to the seal water as heat: `ṁ_seal = P/(c_p ΔT)` at a 5 K rise, 0.72 kg/s (2.6 m³/h) for 15 kW; vendors give 1.5 to 3 m³/h at that size.

### 2.2 Dry screw

Two screws mesh without a liquid; the clearances leak back a little more as the ratio grows. Capacity is flat from 1 atm to about 1 mbar, the ultimate about 0.01 mbar (Busch COBRA DS/DX/NC leaflets).

`S = S_n s (1 − P_u/P_s)`, `P_u` = 1 Pa. At 1 kPa φ = 0.999. In the world it is flat: below about 0.65 kPa the pipes' 100 m/s cap holds the line before the pump does (ejector batch, section 1a).

Shaft power, vendor form: `P = P_r [0.6 s² + 0.4 s (p_s/P_d)^0.3]`: rated at 1 atm intake (the built-in volume ratio over-compresses there), 80 % at 100 mbar, 70 % at 1 mbar, 63 % at the ultimate (COBRA DS 2141: 5.0 kW at ultimate on a 7.9 kW motor). `P_r` = 19 W per m³/h (DX 0650: 650 m³/h on 15 kW; DS 0600: 600 m³/h on 15 kW).

Discharge temperature: the screw runs hot, its jacket takes most of the heat. The gas is booked `T_d = T_s [1 + 0.6 (1 − p_s/P_d)]`, at most the shaft power: 27 °C in at 1 atm → 27 °C; at 50 kPa → 117 °C; deep vacuum → 207 °C (catalogue gas discharge 150 to 250 °C). Booked from the suction's `P/ρ` and `k` only: `ṁ k/(k−1) (P_s/ρ_s) · 0.6 (1 − p_s/P_d)`. The rest of the shaft power leaves through the jacket, as a pump's loss above the ideal work does today (S1).

### 2.3 Speed, discharge, the row

- Speed: capacity ∝ s, the compression work ∝ the flow, friction ∝ s³ (ring) or s² (screw). Blank-off does not move with speed (simplification; a slow ring is thinner).
- Discharge: both hold at most `P_dmax` = 130 kPa (vendors: 1.2 to 1.4 bar a), the F2e relief rise `P_dmax − P_s` with the ejector's smooth floor. One constant for both types.
- The row: a new law `VacuumPump` in `science/machine` (permitted by `MachineLaw`), carried by the existing `FlowControl.Machine` at the train's SPEED plan, its row the F2e flow form (`PositiveDisplacement` with displacement `S_n φ(P_s)`, as `FlowControl.Ejector` builds it). `Machine.lawResidual` asks the law for its row, so the head form stays for the centrifugal laws. No new mover type, no new plan kind, no new limit. Gas mover: `TopologyCompiler.Kind.COMPRESSOR`, `ShaftNetwork.Kind.CASING`: no new constant there.

## 3. Seal water for the liquid ring (V2)

| | A: utility nozzle | B (recommended): internal seal |
|---|---|---|
| What | SEAL inlet nozzle; water through a planned valve (as the ejector's motive) into an internal junction, out with the gas at the discharge; the player adds a separator drum | the ring is internal at a fixed 25 °C; no water enters or leaves the network |
| Blank-off | `P_v` of the junction's mixture: cold water deepens the vacuum | 3.17 kPa (the owner's 3.3 kPa) |
| Cost | one more junction node and valve per pump; a two-phase discharge; a separator in every layout; a seal port in the sheet and the column assembly | none: two-port casing, one row |
| Condensable load | condenses into the ring (real) | passes as gas |
| Without seal water | capacity 0, and wear as a pump running dry | cannot happen |

B is a constitutive statement in place of a mechanism; the shaft power still reports the seal water's heat and flow on the page (text, not a stream).

## 4. The models (V3)

Rated speed share 1 at the design speed; motor by `Motor.ratingFor` (shaft maximum × 1.2, next IEC rating). Model = kind + rotor diameter (+ stages): no new shape field.

| Model | Type | S_n m³/h | Design rpm | Shaft max kW | Motor kW | Blank-off |
|---|---|---|---|---|---|---|
| LR1-150 | liquid ring, 1 stage | 150 | 1450 | 4.3 | 5.5 | 3.2 kPa |
| LR1-500 | 1 stage | 500 | 1450 | 14.3 | 18.5 | 3.2 kPa |
| LR1-1500 | 1 stage | 1500 | 980 | 42.8 | 55 | 3.2 kPa |
| LR1-5000 | 1 stage | 5000 | 590 | 143 | 200 | 3.2 kPa |
| LR2-150 | liquid ring, 2 stage | 150 | 1450 | 5.0 | 7.5 | 3.2 kPa |
| LR2-500 | 2 stage | 500 | 1450 | 16.6 | 22 | 3.2 kPa |
| LR2-1500 | 2 stage | 1500 | 980 | 49.6 | 75 | 3.2 kPa |
| DS-100 | dry screw | 100 | 2950 | 1.9 | 3 | 1 Pa |
| DS-300 | dry screw | 300 | 2950 | 5.7 | 7.5 | 1 Pa |
| DS-650 | dry screw | 650 | 2950 | 12.4 | 15 | 1 Pa |
| DS-2500 | dry screw | 2500 | 2950 | 47.5 | 75 | 1 Pa |

Catalogue ranges: liquid ring 25 to 22,500 m³/h (2BE1: 100 to 22,500), single and two stage; dry screw 100 to 3,200 m³/h. Real screws turn 3,600 to 6,000 rpm on a VFD; the game's motors stop at 3,000 rpm, so their design speed is 2950 and `S_n` is the catalogue's. The suction and discharge bores take 25 m/s at `S_n` (the vendors' flange sizes), rounded up to the next pipe size from 0.05 to 0.4 m; `VACUUM_SPEED` stays the simulator's line limit (built: shaft maxima are the law's scan of 400 suction pressures). Sizing (simulator, constructor's proposal): the smallest model with `S(P_s) ≥ 1.2 × load` (the 20 % spare).

## 5. The sub-category (V4)

- **A (recommended):** a sixth constructor category **Vacuum**: Steam ejector (moved from Machines), Liquid ring pump, Dry screw pump. A pump's design page has a Model selector (its models, two a row) and shows S_n, blank-off, design rpm, shaft power and the proposed motor. The simulator palette gets the same category: `ejector`, `liquid_ring_pump`, `dry_screw_pump`. Two new `EquipmentShape.Kind` and `ConstructorColumn.Equipment` constants, appended.
- B: as A, but the ejector stays in Machines and Vacuum holds the two pumps.
- C: every model its own button in a Vacuum category (12 rows: the page scrolls).

## 6. The column assembly (V5)

Physics: a vacuum pump cannot swallow the column's whole overhead (mostly stripping steam: tens of thousands of m³/h at 5 kPa); the condenser must take the steam and the condensables first, and the pump takes the non-condensable vent. So the pump sits on the receiver's VAPOUR_OUTLET, after the condenser (the owner's placement). The ejector stays on the column's VAPOUR_OUTLET: its motive steam condenses in the condenser (the sheet books it into the free water and the condenser's duty), so it agrees with the sheet and with real crude vacuum units (the first ejector stage ahead of the precondenser). Confirmed, no conflict with the owner's rule.

The vacuum choice in the design: `ColumnComponents.Vacuum` EJECTOR, LIQUID_RING, DRY_SCREW, saved by name in the column's design tags (`Vacuum`), default EJECTOR where `Ejector` is true (shape-field image under `src/test/resources/compat/`).

With a pump:
- Overhead: column VAPOUR_OUTLET → condenser directly. The condenser's duty loses the ejector steam's heat; the boot water loses its steam (both by the sheet's own numbers).
- Offgas: receiver VAPOUR_OUTLET → **vacuum pump** (slot `vacuum_pump`) → offgas valve → outside.
- The receiver is now under vacuum (top pressure less the condenser's drop). The water boot cannot drain to the atmosphere by gravity without a 10 m barometric leg. **A (recommended):** a `water_pump` slot (an in-line pump, as the reflux pump's form) before the water valve. B: no slot; the player's problem.
- The reflux pump's suction is at the drum's bubble point: its NPSH is the drum's height (rigid drum, existing rules). No change, a note on the page.
- `ColumnStarts`' refusal of a vacuum column stays (E6 A); this batch makes Check parts and the sheet right.

## 7. Limits (V6)

- Below blank-off: capacity → 0 smoothly (`φ` clipped at 0, the 1e-12 floor); the suction stops falling. No hold.
- Liquid at a dry screw: the compressor rule (liquid takes its own volume of the displacement); no refusal. **A (recommended):** no wear. B: wear as a pump running dry while the liquid share exceeds the trace.
- Liquid ring without seal water: impossible under V2 B. Under V2 A: capacity 0 and wear.
- Driver overload: the proposed motor holds the curve's maximum with 20 % spare; a smaller driver gives the existing `CURRENT LIMITED` / `POWER LIMITED` slow-down, a legacy fixed motor `TRIPPED: OVERLOAD`. Nothing new.
- A dry suction or solids refuse, as at a compressor.

## 8. Sound (V7)

- **A (recommended):** reuse: liquid ring → `pump_running` (water churn), dry screw → `compressor_running`, pitch by rpm as today.
- B: a new screw whine loop (`tools/process-sounds/`, three candidates for the owner to pick).

## 9. Process simulator (V8)

- `FlowsheetCatalog`: both kinds MODELLED. A unit `VacuumPumpUnit` (tiles `liquid_ring_pump`, `dry_screw_pump`): one suction, one discharge at a set discharge pressure (default 105 kPa), the law's shaft power and discharge temperature, a type and model setting (model `auto` sizes by the 20 % spare), a lamp when the suction is at or below blank-off or the load exceeds the model. Port rules: SUCTION in, DISCHARGE out; lang lines for the tiles, the model names and the lamp.
- The column door: the vacuum choice (ejector / liquid ring / dry screw); with a pump the block reports the pump's power and model in place of the motive steam.
- **A (recommended):** as above. B: DRAWING_ONLY tiles now, the unit in its own batch.

## 10. Saves

- Two `EquipmentShape.Kind` constants, appended (by name). No shape field for the pumps (kind, diameter, stages in `tangentLength` as the frame's throws). The column's `Vacuum` design field: append-only, default from `Ejector`, a corpus image.
- Fluid facts: the casing's control is the existing machine control with a new law tag by name; registrations are new records. Facts schema bump and a corpus image if the law tag is saved in facts (checked in build).
- Layout: two-port casing, one free block between the nozzle pipe blocks, shaft end at the drive end; rows in `EquipmentLayoutGoldenTest`; drawings through `tools/equipment-drawings/update.ps1`.

## 11. Work packages (after the decisions)

1. Science: `VacuumPump` law (both types), `Machine.lawResidual` through the law; `VacuumPumpTest` (the φ tables, power peak at 37 kPa, 15 kW at LR1-500, discharge temperatures, the speed scaling).
2. Equipment: the two kinds, models, layouts, `machineControl`, drawings, golden rows, constructor Vacuum category and model selector, lang.
3. Runtime: registration as a casing; a solver test: a closed rigid air drum pulled down by an LR1-150 at full speed to about 3.2 kPa, mass and energy closed; a DS-100 to the pipe's cap.
4. Column assembly: `Vacuum` choice, blueprint slots, `AssemblyDiagram`, corpus image, `ColumnBlueprintTest`.
5. Simulator: `VacuumPumpUnit`, tiles, sizing, `FlowsheetCatalog`.
6. Docs: code guide (`rotating-machinery.md`, `machine-assemblies.md`, `equipment.md`, `process-simulator.md`), README.

Tests: only the classes that load the changes, with `--tests` (owner): the new tests, the machine-law and mover tests touched, `FlowsheetPaletteCoverageTest`, `SizingCoverageTest`, the column-assembly tests, and in `equipmentGates` `EquipmentLayoutGoldenTest`, `ProcessUnitTest`, `ShapeRasterizerTest`, `EquipmentShapeCompatTest`.

## 12. Decisions for the owner

| | Question | Recommended |
|---|---|---|
| V1 | Curves: LR `(1 − P_v/P_s)^n` (n 2 / 1), screw `1 − 1 Pa/P_s`; power friction + isothermal / vendor form | as section 2 |
| V2 | Seal water | B: internal seal at 25 °C, blank-off 3.17 kPa |
| V3 | Models | the 11 of section 4 |
| V4 | Sub-category | A: a Vacuum category with the ejector and both pumps, a Model selector |
| V5 | Column: receiver under vacuum, its water boot | A: a `water_pump` slot before the water valve |
| V6 | Liquid at a dry screw | A: no wear |
| V7 | Sound | A: reuse `pump_running` / `compressor_running` |
| V8 | Simulator | A: MODELLED unit, column door vacuum choice |
| V9 | Screw discharge temperature | `T_s [1 + 0.6 (1 − p_s/P_d)]` (207 °C at deep vacuum); alternative: none (isothermal) |

### 12.1 The owner's answers (2026-11-10)

Owner: **"V6 B with noise, others as recommended"**. V1, V2 B, V3, V4 A, V5 A, V7 A, V8 A, V9 as recommended. V6 B: liquid at a dry screw wears it as a pump running dry does (`Wear`, `PumpHealth`), and makes a noise: a layer over the screw's running loop whose loudness follows the liquid share, as a pump's crackle and churn bytes do (`MoverState`, `MoverSounds`), on an existing layer sound.

### 12.2 As built (2026-11-10)

- One palette tile `vacuum_pump` in the Vacuum category, beside the ejector; its door chooses the type (one- or two-stage ring, dry screw), and the model is a number (0 sizes it). The constructor sets the model with the size stepper (1 to 4; a two-stage ring 1 to 3) and the stages with the length stepper.
- The column's `Vacuum` field (shape fields 12) is saved by name only where it is not the ejector. `LIQUID_RING` takes the smallest single-stage model that holds the vent with its spare, else the two-stage one's. The column block's solve reads `vacuum_model`, `vacuum_stages`, `vacuum_power` and `vacuum_capacity`; its sizing has a `vacuum_pump` member. The vent product stays at the receiver's pressure in the simulator, and the pump's heat is not booked on it.
- In the world a vacuum column still refuses to start ("a vacuum column cannot run yet", `ColumnStarts`); the assembly lays the pump and the water pump.

Sources (proportionate): Busch COBRA DS/DX product leaflets (pfeiffer-vacuum.com); 2BE1 distributor listings (bts.net.ua, sealing.com.ua); SIHI and Kinney liquid ring catalogues (15 °C seal-water basis, correction curves); Ryans and Roper, *Process Vacuum System Design and Operation* (1986).
