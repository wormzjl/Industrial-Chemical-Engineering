# Chimney: research and plan

Batch `2026-11-10-chimney`, branch `claude/chimney-b7484c`, cut from `claude/custom-shader-system-caff9e` at `6e95356b5` (it reuses that batch's stack plume, smoke, haze and the VENT sound). Status: plan, owner decisions open (section 4); not built.

Owner request, 2026-11-10: "Add a chimney structure that releases gas (and potentially particulates) to the atmosphere". Shape "Steek+brick": a self-supporting steel stack on a plinth (inlet 1 m up, ladder, platform near the top) and a tapered brick or concrete chimney with a steel liner. Size "Scalable": height and diameter in the constructor; the calculator sizes the diameter from the design flow at an exit velocity with the 20 % spare, and the height from the tallest nearby structure. Particulates "Release and show": booked as removed, named on the status line in kg/h, drawn as grey-to-black smoke.

## 1. Research (proportionate)

- **Exit velocity.** Downwash into the stack's own wake starts when the exit velocity is below about 1.5 × the wind speed at the top ([ASHRAE HVAC Applications ch. 46](https://www.ashrae.org/File%20Library/Technical%20Resources/ASHRAE%20Handbook/I-P_A19_Ch46.pdf); [White and Stein 1990](https://research.engineering.ucdavis.edu/wind/wp-content/uploads/sites/17/2014/03/white-stein-1990.pdf)). With a design wind of 5 m/s at 30 m that is 7.5 m/s. Fan-driven steel stacks are designed at about 15 to 25 m/s (practice; [CR4 thread](https://cr4.globalspec.com/thread/103810/Exit-Velocity-from-Chimney-of-Thermal-Power-Plant) gives 8 to 25 m/s, unsourced); above about 30 m/s the exit loss ½ρv² (0.35 kPa at 30 m/s, 0.785 kg/m³) is the stack's main pressure cost. No standard range exists; we take a window, section 3.2.
- **Height.** Good engineering practice (GEP) height H_g = H_b + 1.5 L, H_b the nearby structure's height, L the lesser of its height and projected width (40 CFR 51.100(ii), as adopted in [Nevada NAC 445B.083](https://www.law.cornell.edu/regulations/nevada/NAC-445B-083); [EPA downwash presentation](https://gaftp.epa.gov/Air/aqmg/SCRAM/conferences/2012_10th_Conference_On_Air_Quality_Modeling/Presentations/3-3-EPA10thConferenceAQModeling-BuildingDownwash_SchulmanScire.pdf)). With L = H_b this is the old 2.5 × rule.
- **Draft.** Δp = (ρ_air − ρ_gas) g H. Air at 298 K: 1.185 kg/m³; flue gas (M 0.029) at 450 K: 0.785 kg/m³. 50 m: 0.196 kPa of draft; the gas column alone weighs 0.385 kPa, the air column 0.581 kPa.
- **Stack losses at 20 m/s, D 2 m, H 50 m, 450 K.** ½ρv² = 157 Pa; friction f L/D ≈ 0.015 × 25 → 59 Pa; entry 0.5 → 79 Pa; exit 1.0 → 157 Pa. Total about 0.30 kPa, the same order as the draft.
- **Smoke opacity.** Beer–Lambert across the plume: opacity τ = 1 − exp(−k C D), C the solids mass concentration at the exit (g/m³), D the exit diameter, k the specific extinction, about 1 m²/g for fine ash (soot 3 to 8, coarse fly ash 0.1 to 0.5). Ringelmann 1 to 5 ≈ 20 to 100 % opacity.
- **Steel and brick (practice).** Self-supporting steel: slenderness H/D up to about 30 (above it guys or a derrick, not chosen); unlined carbon steel to about 400 °C gas. Brick or concrete windshield with a steel liner: tapered shell, foot outer diameter about 1.6 × the top's, liner to about 600 °C; used for tall, hot, large flows.

## 2. What the code has

- The flare's tip: a `VOID` boundary node at its inlet cell (`FluidWorldAuthority.placeFlare`), one-way (no flow out of a void, `PassiveStepSolver` pipe direction rule), at its spec pressure 101,325 Pa; its run carries the tip limit (`ValveSource.FLARE`). A passive void (control `FlowControl.Passive`) is already valid.
- Reservoir nodes carry an elevation (block y); a pipe's row already carries its column's static head (`columnDensity · g · Δz`), friction and a minor loss (`PipeResistance.Geometry.minorLoss`).
- Every open boundary is at `PhaseLayout.AMBIENT_PRESSURE` 101,325 Pa at any height: open vessels' wall rows, void specs, the flare, the cooling tower's air (its own draft is internal to `TowerTransfer`).
- `BoundaryTransfer.Kind` VENT and SPILL (open vessels), ATMOSPHERE (tower water loss). `SolidInventory` moves with gas; the flare blocks solids.
- Effects: `HeaterSource` (white plume by the mixing line, grey smoke by soot index, haze by excess temperature) on the `HEATERS` watch; `Noise.Kind.VENT` with a velocity from mass flow, density and area.
- Fired heaters' flue gas is not a network stream (they keep their own stack). A chimney takes real pipe gas: FCC `FLUE_GAS`, gasifier and boiler vents, any gas line.

## 3. Proposal

### 3.1 How the gas leaves (Q1)

The tip is a passive `VOID` at the top cell's elevation; the run from the inlet port to it carries the stack's geometry (length H, diameter D, minor loss 1.5: entry 0.5 + exit 1.0). The solver then gives the static column, friction and exit loss with no new law.

- **A. Constant atmosphere (today).** The tip is at 101.325 kPa. The inlet must stand at 101.325 + ρ_gas g H + losses: 102.0 kPa for the 50 m case. No draft; a hot stack pushes as hard as a cold one. A source near atmospheric pressure (an open vessel's line, a low-pressure vent) never climbs a tall chimney. Deletes nothing, moves no reference.
- **B. Atmosphere falls with height at every open boundary.** p_atm(y) = 101,325 − ρ_air g (y − 63), ρ_air 1.185 kg/m³ (298.15 K): 11.6 Pa per block, +1.48 kPa at y −64, −2.98 kPa at y 320. Applied to every node open to the air: void sinks that release to the atmosphere (chimney and flare tips, read from their own cell at registration) and open vessels' surfaces (floating-roof tanks, tower basins, still pots: their slack branch reads p_atm at their own surface). Closed slack bags (D54) keep 101,325: their ambient is a wall model, not an opening. The 50 m case: inlet at 101.43 kPa (draft 0.20 kPa against 0.30 kPa losses). Effect elsewhere: an open tank's surface moves by 12 Pa per block of height (1.2 mm water); a 32 m flare's tip by −0.37 kPa. Risk: references with open vessels or flares away from y 63 move by these amounts (fixtures and GameTests re-read in the final lane).
- **Recommendation: B.** It is the physics, it makes a chimney draw, and its effect on other equipment is below a kilopascal.

### 3.2 Sizing (Q2)

- Diameter: the exit area for 1.2 × the design volume flow at a design exit velocity v_d (owner rule: 20 % spare). Window: Should 7.5 ≤ v ≤ 30 m/s at the design flow (downwash floor at 1.5 × 5 m/s; exit-loss ceiling); Must v ≤ 100 m/s (the cap). v_d 20 m/s steel, 12 m/s brick (lower losses for tall, large flows).
- Height: H = max(10 m, 2.5 H_b) (GEP with L = H_b). The simulator has a field "nearby structure height" H_b (default 15 m → 37.5 m). Option: the constructor also offers the world's H_b, the tallest block above the base within 32 blocks of the footprint (heightmap read on placement; hills count, as they do for downwash). Recommendation: field in the simulator plus the constructor's hint.
- Steel: top D 0.4 to 3 m in 0.1 m steps, H 10 to 60 m with H ≤ 30 D. Brick: liner D 1 to 8 m in 0.25 m steps, H 30 to 150 m; foot outer 1.6 × top outer. Constructor fields: diameter, height (the existing `diameter` and `tangentLength`).
- Example: 10 kg/s at 450 K (12.7 m³/s) → 15.3 m³/s at 20 m/s → D 1.0 m steel; H_b 15 m → 37.5 m.

### 3.3 Steel against brick (Q3)

| | Steel stack | Brick chimney |
|---|---|---|
| Form | straight shell on a 2 m plinth, inlet 1.5 m centre, ladder, platform 2 m below the tip | tapered brick/concrete shell, steel liner, inlet through the base, ladder, top platform |
| Gas temperature warning | 400 °C | 600 °C |
| Range | D 0.4–3 m, H 10–60 m | D 1–8 m, H 30–150 m |

A gas above the warning shows `HOT STACK` (a notice, no hold, no damage model). Options: **A.** two kinds `STEEL_STACK`, `BRICK_CHIMNEY` (new enum constants only, saved by name, no shape field, no corpus image), one design class, one engine model, one simulator tile with a form choice; **B.** one kind with a form shape field (shape fields 12, a corpus image). **Recommendation: A.**

### 3.4 Particulates (Q4)

Solids pass with the gas into the tip void. Its boundary transfer is booked with kind ATMOSPHERE, gas and solids apart; the ledger totals them as removed. Status line: `VENTING 12.4 t/h, solids 3.2 kg/h`. Smoke: opacity τ = 1 − exp(−k C D) with one k = 1 m²/g for every solid (C at exit conditions). Smoke darkness and density follow τ: grey at τ 0.2, black at τ 1. Examples: 50 mg/m³, D 2 m: τ 0.10 (faint grey); 1 g/m³: 0.86 (near black). Option: k per solid material (needs a data field; not recommended).

### 3.5 Condensate (Q5)

Expected: gas below its water dew point condenses on the liner and drains at the base. Options: **A.** leave it out: liquid that forms or arrives leaves through the tip, booked as released, with a `WET STACK` notice (recommended: no new node). **B.** a base drain: the base becomes a small reservoir with a `CONDENSATE` nozzle and the void above it (a drum-like node; more model and layout).

### 3.6 Limits (Q6)

- Over the rating: no built-in limit; the 100 m/s cap holds as on every stream, and the stack's losses (∝ v²) push back. Status `ABOVE RATING` when the exit velocity passes 30 m/s; data alarm `alarm_over_capacity`.
- Inlet below the tip's atmosphere plus column (a cold or starved chimney): the void is one-way, so the flow stops; status `NO DRAFT`. Options: **A.** no backflow (recommended); **B.** air enters at the tip (an ambient-air generator at the tip, flow down the stack; new boundary).
- Liquid arriving: as 3.5 A; it passes with `WET STACK`. No holds (none of these is a capability limit).

### 3.7 Sound (Q7)

`Noise.Kind.VENT` at the tip, gain from the exit velocity (as the open-tank vent), from the same watch as the effects.

### 3.8 Effects (Q8)

A new watch `CHIMNEYS` on the 5 s presentation bucket, on the client's request (watch starts and stops with the client; no process work on the client): mass flow, exit temperature, water share (kg/kg dry gas), solids flow, opacity. A `ChimneySource` reuses `PlumeModel`: white plume by the mixing line from the water share, smoke with darkness τ, haze when the exit is above the ambient (full at +300 K). Part mark `stack_top`. Fluid protocol number up (wire format, no compatibility).

### 3.9 Data port (Q9)

On the plinth's side (steel) or the base (brick), as the flare's. Built-in inputs: `flow_rate` (kg/s), `solids_rate` (kg/s), `venting` (1 or 0); alarms `alarm_over_capacity`, `alarm_wet_stack`, `alarm_no_draft`.

### 3.10 Process simulator (Q10)

`FlowsheetCatalog`: both kinds MODELLED by one `ChimneyUnit` (a sink: draft, losses, inlet pressure needed, exit velocity, solids kg/h, opacity; warnings as 3.6). One tile `chimney` with a form choice, one inlet port rule (gas), a lang line. `ChimneySizing`: diameter by 3.2 with Auto and the 20 % spare; limits CHIMNEY_VELOCITY (Should window), CHIMNEY_CAP (Must 100 m/s), CHIMNEY_HEIGHT (Should ≥ GEP).

## 4. Owner decisions

- C1 Atmosphere: A constant; **B falling with height at every open boundary (recommended)**.
- C2 Height rule: **2.5 H_b with a simulator field and a constructor hint from the world (recommended)**; or the field only.
- C3 Forms: **two kinds (recommended)**; or one kind with a form field.
- C4 Opacity: **one k = 1 m²/g (recommended)**; or k per solid.
- C5 Condensate: **leave out, WET STACK notice (recommended)**; or a base drain.
- C6 Starved inlet: **no backflow, NO DRAFT (recommended)**; or air enters at the tip.
- C7 Exit velocity: design 20 m/s steel, 12 m/s brick, Should window 7.5–30 m/s (recommended); or other numbers.

## 5. Owner answers (2026-11-10)

Owner: "All recommended". C1 B, C2 A, C3 A, C4 A, C5 A, C6 A, C7 as proposed (20 m/s steel, 12 m/s brick at 1.2 × the design flow; window 7.5 to 30 m/s; ABOVE RATING over 30 m/s).

Parent's notes with the answers: build C1 B as written, closed slack bags at 101,325 Pa; the bag exception is an open item (a bag also stands in the open air). The references that C1 moves are listed with their old and new values (section 7) and only those are re-recorded.
