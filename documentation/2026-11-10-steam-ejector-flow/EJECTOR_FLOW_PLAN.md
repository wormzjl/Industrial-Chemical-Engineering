# Steam ejector as a flow element, and its sound

Batch 2026-11-10-steam-ejector-flow, on `claude/ecstatic-dubinsky-c24b8a` (built on `c05453b05` of `claude/custom-shader-system-caff9e`). Owner request 2026-11-10: "add sound for steam ejector as well"; owner option B: the ejector first becomes a real flow element, and its sound reads that flow, as a valve's does.

## 1. Today

- `EquipmentShape.Kind.EJECTOR` is drawing only. `EquipmentShape.fluidDevice()` leaves it out, so its MOTIVE, SUCTION and DISCHARGE nozzles join no network: a pipe laid to one is a dead end.
- `Noise.java`: "Steam ejectors have no live state in the world, so they make none".
- `ColumnStarts`: "a vacuum column cannot run yet".
- The simulator models the set with `EjectorLaw` (S1): steam need `m = K c(P_m) [G ln(P_d/P_s) + W ln(P_c/P_s)⁺]`, a free Motive port supplies exactly that need.

## 2. Physics (expected behaviour)

### 2.1 Motive flow

The motive nozzle is a converging-diverging nozzle. Steam at P_m chokes in its throat once the discharge is below the critical ratio (0.546 at k = 1.3, so any discharge under 5.5 bar on 10 bar steam). The choked flow is

`ṁ = A_t Ψ √(P_m ρ_m)`, Ψ = √k (2/(k+1))^((k+1)/(2(k−1))) = 0.667 at k = 1.3.

At 10 bar saturated steam (ρ = 5.15 kg/m³) that is ṁ = 1.46e-3 A_t P_m (SI, kg/s with m² and Pa): Napier's rule. (The brief's "1.46e-6" holds for kPa and mm².) The flow does not depend on the discharge pressure, nor on the suction load.

### 2.2 The game's form of it: the 100 m/s cap, no choke law

The owner's rule of 2026-10-19 (no choke law, the 100 m/s cap for every stream) holds here too. The cap already chokes: a connection carries at most ρ A_min · 100 m/s, independent of the downstream pressure. For an ideal gas at fixed temperature both ρ and √(Pρ) scale with P, so the cap through an **equivalent throat** of area

`A_eq = A_t Ψ √(P/ρ)_design / 100 = 2.94 A_t` (10 bar saturated steam, √(P/ρ) = 441 m/s)

gives the choked flow with no new law. Error against the true choked flow, from the √(P/ρ) the cap leaves out:

| Motive steam | ρ, kg/m³ | P/ρ, kJ/kg | cap / true |
|---|---|---|---|
| 3 bar saturated | 1.65 | 182 | +3 % |
| 10 bar saturated (design) | 5.15 | 194 | 0 |
| 40 bar saturated | 20.1 | 199 | −1 % |
| 10 bar, 300 °C | 3.88 | 258 | −13 % |

Below the critical ratio nothing changes. Above it (discharge over 5.5 bar on 10 bar steam) the line's own friction through the throat section takes over and the flow falls toward zero as P_d → P_m, roughly as a nozzle's does. A liquid slug at the motive (condensate) passes at ρ_l · 100 · A_eq, about 7 times what a real nozzle passes at the same drop (Bernoulli 42 m/s through A_t); it is rare and short, and it conserves everything.

The solver needs **no new row, mode or law**: the ejector compiles as a passive passage from its motive port to its discharge port, its throat a section of the run (`Pipe.minimumArea()` picks it), as a heat side's passage or an inline filter compiles. `ConservativeTransport` books it as any passive run: the steam leaves the throat and arrives at the discharge isenthalpically (the jet's kinetic energy is dissipated in the diffuser), which is what the simulator's adiabatic mix does too.

### 2.3 Throat size from the unit's only size, its suction s

| | E1 A (recommended): d_t = 0.035 s | E1 B: d_t = 0.05 s |
|---|---|---|
| Diffuser throat (drawn, 0.35 s) / nozzle throat area | 100 (a deep-vacuum first stage) | 49 (a 10 to 20 kPa stage) |
| Small set, s = 0.1 m: d_t, d_eq | 3.5 mm, 6.0 mm | 5.0 mm, 8.6 mm |
| Small set: steam on 10 bar | 0.0139 kg/s = **50 kg/h** | 0.0287 kg/s = 103 kg/h |
| Large set, s = 1 m | 1.39 kg/s = **5.0 t/h** | 10.3 t/h |
| Smallest, s = 0.05 m | 12.5 kg/h | 26 kg/h |

Both lie in vendors' ranges (a crude vacuum unit's sets take 5 to 25 t/h on 1 to 2 m suctions). A is the brief's number. The motive pipe (bore 0.3 s) caps at 0.36 kg/s at s = 0.1 m and the discharge (0.75 s at 1.2 bar) at 0.31 kg/s, so the throat governs at every size.

### 2.4 Where the steam goes

- Discharge piped to a vessel, an exchanger or a void: the steam goes there at the motive's enthalpy (10 bar saturated to 1.2 bar: superheated, about 150 °C).
- Discharge closed or dead-ended (a closed valve, a blind line): the line fills to the motive pressure and the flow stops; no sound.
- **No discharge pipe** (E3):
  - **A (recommended)**: no flow, as a steam turbine with an unpiped exhaust. Fewest special cases.
  - B: an unpiped discharge vents to the atmosphere through a built-in sink, as a flare tip is (the motive steam booked out at the ambient). Real atmospheric-discharge ejectors exist; it adds a sink and a boundary kind.
- A reverse drive (discharge above motive) passes back through the open nozzle, as through any passive run (no check valve).

### 2.5 Suction entrainment (E4)

The suction load follows from the motive flow by inverting `EjectorLaw`: G = ṁ_m / (K c(P_m) ln(P_d/P_s)). In the solver that is a row on the suction connection that reads a third node (the motive's state) and diverges as P_s → P_d; it also needs the column start at vacuum (`ColumnStarts`), the trace-water lift to P_c and new seeding at vacuum.

- **A (recommended): later**, a batch of its own with the vacuum column start. Now the SUCTION nozzle stays decorative (a pipe on it is a dead end, as a turbine's oil-cooler nozzles are).
- B: now. Roughly three times this batch, and it touches the Newton's sparsity and the start stage.

### 2.6 Motive control (E5)

- **A (recommended): always open** while steam stands at the motive nozzle. Real ejectors run at full motive (throttled motive makes them break), and the player throttles or stops it with an ordinary control or cutoff valve on the motive line. No new field.
- B: an on/off switch on the ejector (a new facts field `enabled`, default true).

### 2.7 Old worlds (E6)

An ejector built before this batch has no registration; at its first load `restoreFluidBinding` registers it, and if its motive is piped to steam it starts passing steam. No fact is lost; the plant's behaviour changes.

- **A (recommended)**: accept it (the change log names it).
- B: register old ejectors switched off (needs E5 B).

## 3. Sound

### 3.1 Level

A supersonic jet turns a share η of its jet power ½ ṁ U² into sound: Lighthill's 1e-4 M⁵ (M = U / 340 m/s, as `Noise.vent`), capped at η = 5e-3 for supersonic jets. U² / 2 is the isentropic drop motive to discharge, `SteamTurbine.isentropicDrop` (reused). The casing and diffuser wall take a transmission loss TL = 35 dB (30 to 40).

`Lw = 10 log10(η ṁ Δh_is / 1 pW) − 35`, NaN without flow, at most `Noise.CAP` (120 dB).

| | ṁ | Δh_is (10 → 1.2 bar, k 1.3) | U | η | acoustic | Lw |
|---|---|---|---|---|---|---|
| Small (s 0.1 m) | 0.0139 kg/s | 326 kJ/kg | 807 m/s (M 2.4) | 5e-3 (cap; 1e-4 M⁵ = 7.5e-3) | 22.6 W | **98.5 dB** (≈ 88 dBA at 1 m) |
| Large (s 1 m) | 1.39 kg/s | 326 kJ/kg | 807 m/s | 5e-3 | 2.26 kW | 118.5 dB (≈ the cap) |
| Small on 3 bar | 0.0045 kg/s | 151 kJ/kg | 549 m/s (M 1.6) | 1.1e-3 | 0.73 W | 84 dB |

k: the noise reads 1.3 (steam); a reading of the stream's own k gains nothing audible.

### 3.2 Loop and kind

- `Noise.Kind.EJECTOR`, appended at the end (the wire sends the ordinal; the noise watch protocol id follows the parent's rule for an appended kind).
- Placed at the ejector's throat cell; pitch 1 (E7 A, recommended) or by U / 807 m/s clamped 0.8 to 1.2 (E7 B).
- Candidates, made like `tools/process-sounds/valve_candidates.py` (48 kHz mono, 4 s, periodic, RMS 0.09), in a new script `tools/process-sounds/ejector_candidates.py`, output to the main checkout's `research/2026-11-10-steam-ejector-flow/sounds/`:
  - J1: a steady 2 to 8 kHz supersonic hiss (peak 4 kHz) over a 100 to 400 Hz diffuser rumble 12 dB under it.
  - J2: J1 with a brighter hiss (peak 6 kHz) and a faint screech tone near 3 kHz.
  - J3: rumble-forward: the diffuser rumble equal to the hiss, with a slow (0.3 Hz) swell.
  The owner picks one; it becomes `assets/icheme/sounds/ejector_jet.ogg`.

## 4. Saves and formats

- No shape field changes (the throat follows from the suction).
- The registration's kind `EJECTOR` is a new `TopologyCompiler.Kind` constant, saved by name, appended. Its device geometry (the throat section) is an existing fact field. No facts field is added under E5 A; under E5 B, `enabled` defaults to true.
- A corpus image `src/test/resources/compat/fluid/` with a registered ejector pins its round trip (and, under E5 B, a facts schema bump 16 → 17).
- Checkpoint (state) and wire formats change freely.

## 5. The simulator

- `FlowsheetCatalog`: EJECTOR is already MODELLED (tile `ejector`); `FlowsheetPaletteCoverageTest` needs nothing new.
- The laws differ: the sheet's free Motive port supplies the law's need for the load; the world's throat passes its fixed choked flow whatever the load. They agree where the need equals the throat's flow. E8:
  - **A (recommended)**: no sheet change now; the entrainment batch (E4) makes them agree by construction (the world then reads the same law, inverted).
  - B: a sheet reading of what the world's throat passes at the door's motive pressure, beside the need, with a yellow lamp where the need exceeds it.

## 6. Work packages (phase 2, after the decisions)

1. Science: `ProcessUnit.ejectorThroat(s)` and the equivalent throat section; unit test (50 kg/h at 0.1 m on 10 bar saturated, ±3 %).
2. Topology and runtime: `TopologyCompiler.Kind.EJECTOR` (a passive passage from motive to discharge), `EquipmentShape.fluidDevice()`, `EquipmentStructure` registration and restore, `PhysicalFluidTopology` validation, `FluidFacts` name. Solver test: 10 bar steam generator → ejector → 1.2 bar void passes the throat's flow (±5 %), mass and energy closed (isenthalpic); a closed discharge passes nothing.
3. Noise: `Noise.ejector`, `Noise.Kind.EJECTOR`, the case in `FluidWorldAuthority.islandNoise`, the client loop, `sounds.json`, the `en_us.json` subtitle. `NoiseTest`: 98.5 ± 1 dB small, the cap large, NaN without flow.
4. Sound candidates (section 3.2); the chosen loop to the assets.
5. Corpus image, `Noise` and `ColumnStarts` comments, code guide (`fluid-science.md`, `fluid-runtime.md`), batch README.

Tests: targeted `--tests` only (`ProcessUnitTest`, the new solver test, `NoiseTest`, `FluidFactsCompatTest`/corpus, `EquipmentLayoutGoldenTest` unchanged), at most 4 workers, never while a game client runs.

## 7. Decisions for the owner

| | Question | Recommended |
|---|---|---|
| E1 | Throat size | A: d_t = 0.035 s (50 kg/h at 0.1 m, 5 t/h at 1 m) |
| E3 | Unpiped discharge | A: no flow |
| E4 | Suction entrainment | A: later, with the vacuum column start |
| E5 | Motive control | A: always open; the player's own valve on the motive line |
| E6 | Old worlds | A: old ejectors register and pass steam if piped |
| E7 | Loop pitch | A: fixed |
| E8 | Simulator | A: no sheet change now |
| J | Loop candidate | owner listens to J1 to J3 (made after the decisions) |

Forced by an owner rule, not an option: the motive flow is the connection's 100 m/s cap through the equivalent throat (2026-10-19: no choke law).
