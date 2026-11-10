# Steam ejector as a flow element, and its sound

Batch 2026-11-10-steam-ejector-flow, on `claude/ecstatic-dubinsky-c24b8a` (built on `c05453b05` of `claude/custom-shader-system-caff9e`). Owner request 2026-11-10: "add sound for steam ejector as well"; owner option B: the ejector first becomes a real flow element, and its sound reads that flow, as a valve's does. Revision 2 (2026-11-10), after the owner's "Need a simplified model" and the choice **"Mover with a curve"**: suction to discharge on the mover row with a vendor-style capacity curve, motive steam drawn from the motive line, no new row type and no three-node row. Revision 1 (a passive motive passage through an equivalent throat, decisions E1 to E8) is in this file's history (commit 2cbdadb10).

## 1. Today

- `EquipmentShape.Kind.EJECTOR` is drawing only: `EquipmentShape.fluidDevice()` leaves it out, and a pipe laid to its MOTIVE, SUCTION or DISCHARGE nozzle is a dead end.
- `Noise.java`: ejectors make no noise. `ColumnStarts`: "a vacuum column cannot run yet".
- The simulator's set law `EjectorLaw` (S1): steam `m = K c(P_m) [G ln(P_d/P_s) + W ln(P_c/P_s)⁺]`, K = 1.6, G the load as air (√(29/M) per kg), W its water; a free Motive port supplies that need.

## 2. Physics (expected behaviour)

A steam ejector set takes a fixed motive steam flow through its nozzle throat (choked: proportional to the motive pressure), and with that steam it pulls a gas load from its suction and delivers load plus steam at its discharge. Its vendor curve, at the design motive and a discharge below its maximum discharge pressure (MDP), is the load it handles against its suction pressure: about straight in absolute pressure, rated load at the rated suction pressure, none at its blank-off (deepest) vacuum. The curve hardly reads the discharge until the MDP; above it the set breaks and the load stops (or backs). Higher motive pressure moves more steam and raises the MDP; no steam, no capacity. Overall the set is adiabatic: discharge enthalpy = load's + motive's, no shaft work.

### 2.1 The rated point, from the suction s

Rated suction pressure P_r = 10 kPa (a vacuum column's top), rated discharge P_dr = 120 kPa (`EjectorUnit.DISCHARGE_PRESSURE`), motive 10 bar. Rated load: air at 300 K through the suction at the speed u_r the simulator sizes ejectors at (`Limit.VACUUM_SPEED` at 0.1 bar, 60 m/s), so a sheet-sized ejector built in the world runs at its sheet duty. Rated steam = `EjectorLaw.steam` at that load: 1.6 ln 12 = 3.98 kg per kg of air.

| | E1 A (recommended): u_r = 60 m/s, the sizing limit | E1 B: u_r = 30 m/s |
|---|---|---|
| Small, s = 0.1 m: Q_r, load G_r (air) | 0.471 m³/s, 197 kg/h | 0.236 m³/s, 98 kg/h |
| Small: rated motive steam | **784 kg/h** | 392 kg/h |
| Large, s = 1 m: load G_r | 19.7 t/h | 9.9 t/h |
| Large: rated motive steam | **78 t/h** | 39 t/h |

The steam per kilogram of load is the law's in both; u_r only maps size to load. A real crude vacuum unit (5 to 10 t/h load, 10 to 25 t/h steam) needs about a 0.5 to 0.7 m suction under A.

### 2.2 The curve

Load handled (as air), kg/s, at suction pressure P_s and motive pressure P_m:

`G(P_s, P_m) = G_r f_m (P_s − P_0) / (P_r − P_0)`, the vendor's straight line, continuing above P_r.

- Deepest vacuum P_0 (E2): **A (recommended) 1 kPa**, a whole set of three stages with intercondensers; B 3 kPa (0.3 P_r).
- Motive: `f_m = (P_m / 1 MPa) / motiveFactor(P_m)`: the throat's steam rises with P_m, and `EjectorLaw.motiveFactor` gives the steam each kilogram of load needs. 0.22 at 3 bar, 0.41 at 5 bar, 1 at 10 bar, 1.62 at 15 bar. The MDP rises with the motive: `MDP = P_dr P_m / 1 MPa` (120 kPa at 10 bar, 60 kPa at 5 bar: on 5 bar a set cannot discharge to the atmosphere and breaks).
- Gas kind and temperature: the vendors' rule (a kilogram counts √(29/M) of air, and capacity ∝ √(1/T)) is one factor on the suction's own density, `G_actual = G √(ρ_s / ρ_air(P_s, 300 K))`. Water vapour: 0.79 kg per kg of air, 1.27 times its volume.

As the suction volume flow the mover row counts (P_0 = 1 kPa, air, 10 bar motive):

| P_s | 2 kPa | 5 kPa | 10 kPa | 20 kPa |
|---|---|---|---|---|
| G / G_r (mass) | 0.11 | 0.44 | 1 | 2.11 |
| Q / Q_r (volume) | 0.56 | 0.89 | 1 | 1.06 |

So a closed vessel on the suction is pulled down: at 5 kPa a small set still moves 0.42 m³/s, and the vessel stands near P_0 when only its leaks feed it.

### 2.3 The mover row carries it as it is

The curve is the positive-displacement law (`PositiveDisplacement`, form F2e) with a displacement and a relief rise that read the suction:

- displacement `Q_c = G_actual(P_s, P_m) / ρ_s`, suction volume flow;
- relief rise `MDP − P_s`: the relief line over its last tenth is the set's break, flat below it (slip 0.4 % per relief rise). At P_s ≥ MDP or Q_c = 0 the mover stands CLOSED, as a machine at no speed.

The row is the existing `MOVER_LAW` residual (flow form), on suction density and pressure only (decision D4); the Newton's finite-difference slopes take its suction-pressure dependence, as they take a compressor's rise limit `(r − 1) P_s`. No choke law: the suction, motive and discharge lines keep their 100 m/s caps. No mechanism: a curve, a duty and the law's steam, as in the simulator.

New code: a fourth `FlowControl.Mover`, `Ejector(ratedLoad, motivePressure, share)`, its rated load from the shape, its motive pressure and steam share slice inputs (as `Machine.speed` is). Its power is zero: the compression work comes in as the motive's enthalpy. It refuses a dry suction and solids, as a compressor.

### 2.4 Motive steam

The ejector registers three parts joined inside its casing, at the diffuser (a junction, as the topology makes where three runs meet; the batch still's pot, outlets and burners are the precedent for a unit of several devices):

1. the mover, SUCTION nozzle → junction;
2. the motive valve, MOTIVE nozzle → junction: a `ControlValve` of a new planned mass source `EJECTOR` (as `BURNER`, `FLARE`, `STILL`), its target set once per slice;
3. the diffuser run, junction → DISCHARGE nozzle.

Steam drawn (E3):

- **A (recommended): the nozzle's flow**, `ṁ_m = ṁ_r P_m / 1 MPa`, whatever the load: a real set uses its full steam at no load. It equals the law's need at the rated point. A set on a shut suction still blows its steam through, and still sounds.
- B: the law's rate for the load it pulled at the last slice, `EjectorLaw.steam(G, W, P_m, P_s, P_d, P_c)` (the owner's wording): the sheet's number at every load; no load, no steam, silent.

No motive steam: the slice plan reads the motive valve's committed vapour flow over its target, `share` (0 to 1); the capacity is `share` times the curve, so a shut motive line, a header below the MDP or condensate at the nozzle give no capacity. The motive pressure is the motive node's committed pressure (the player's own valve upstream throttles it). One slice of lag, as a turbine's governor.

Where it goes: load and motive mix adiabatically in the junction and leave by the discharge (10 bar saturated steam alone reaches 120 kPa at about 150 °C). With no discharge pipe, or a shut one, the junction fills to the motive pressure, the motive valve passes nothing, and the mover's reverse closure holds the suction: no flow, no sound. Revision 1's E3 (an unpiped discharge) is settled this way.

### 2.5 Motive control and old worlds

- E4 motive control: **A (recommended) always on** while steam stands at the motive nozzle; the player throttles or stops it with their own valve on the motive line. B: an on/off switch (a facts field `enabled`, default true).
- E5 old worlds: an ejector built before this batch registers at its first load and runs if piped. **A (recommended)** accept it (the change log names it); B register old ones switched off (needs E4 B).

### 2.6 The vacuum column (E6)

- **A (recommended): keep `ColumnStarts`' refusal**, and lift it in a batch of its own: the column's run must start at vacuum with the world ejector setting its top pressure, which needs its own start, seeding and gates.
- B: lift it now; this batch then also carries a vacuum column start test and its holds.

## 3. Sound

### 3.1 Level

The jet of motive steam makes the noise. A supersonic jet turns η of its jet power ½ ṁ U² into sound: Lighthill's 1e-4 M⁵ (M = U / 340 m/s, as `Noise.vent`), capped at η = 5e-3. U²/2 is the isentropic drop motive to discharge (`SteamTurbine.isentropicDrop`, k = 1.3). The casing and diffuser wall take TL = 35 dB.

`Lw = 10 log10(η ṁ_m Δh_is / 1 pW) − 35`, NaN without motive flow, at most `Noise.CAP` (120 dB). It reads the motive valve's committed flow and its end pressures.

| (E1 A, E3 A) | ṁ_m | Δh_is | U (M) | η | acoustic | Lw |
|---|---|---|---|---|---|---|
| Small, 10 bar to 1.2 bar | 0.218 kg/s | 326 kJ/kg | 807 m/s (2.4) | 5e-3 | 355 W | **110.5 dB** (≈ 99 dBA at 1 m) |
| Small, 5 bar | 0.109 kg/s | 228 kJ/kg | 675 m/s (2.0) | 3.1e-3 | 77 W | 104 dB |
| Large, 10 bar | 21.8 kg/s | 326 kJ/kg | 807 m/s | 5e-3 | 35 kW | 120 dB (cap; 130.5 uncapped) |

Under E1 B the small set is 107.5 dB. Real sets run 95 to 110 dBA near the diffuser without a silencer.

### 3.2 Loop and kind

- `Noise.Kind.EJECTOR`, appended at the end (the wire sends the ordinal; the watch protocol id follows the parent batch's rule). At the ejector's throat cell. E7 pitch: **A (recommended) fixed**; B by U / 807 m/s, 0.8 to 1.2.
- Candidates made like `tools/process-sounds/valve_candidates.py` (48 kHz mono, 4 s, periodic, RMS 0.09), new script `tools/process-sounds/ejector_candidates.py`, output to the main checkout's `research/2026-11-10-steam-ejector-flow/sounds/`: J1 a steady 2 to 8 kHz hiss (peak 4 kHz) over a 100 to 400 Hz diffuser rumble 12 dB under it; J2 brighter (peak 6 kHz) with a faint screech near 3 kHz; J3 rumble-forward with a slow 0.3 Hz swell. The owner picks one: `assets/icheme/sounds/ejector_jet.ogg`.

## 4. Saves and formats

- No shape field: the rated load follows from the suction.
- Facts: the ejector's registrations (its mover control saved by name, `ejector`; the motive valve's source `EJECTOR` by name, appended; the internal junction's devices) are new records, not changed fields. Facts schema 16 → 17 and a corpus image under `src/test/resources/compat/fluid/` with a registered ejector. Under E4 B, `enabled` defaults to true.
- Checkpoints (state: the slice inputs, modes) and the wire change freely.

## 5. The simulator

`FlowsheetCatalog`: EJECTOR is MODELLED (tile `ejector`); nothing to add. The world and the sheet agree at the rated point by construction (u_r, P_r, P_dr, 10 bar and the law's steam). Off it, under E3 A the world uses the nozzle's full steam where the sheet's free port supplies the need. E8: **A (recommended)** no sheet change now; B a tile reading of the world's capacity at the chosen suction size (the curve at the load's pressure) beside the load, with a lamp where the load exceeds it.

## 6. Work packages (phase 2)

1. Science: `FlowControl.Mover.Ejector` on `PositiveDisplacement` (curve, motive factor, MDP, density rule); `ProcessUnit.ejectorRating(s)`. Test: the rated point at 0.1 m (197 kg/h air at 10 kPa, 784 kg/h steam), the table of 2.2, CLOSED with no share or at P_s ≥ MDP.
2. Topology and runtime: `EquipmentShape.fluidDevice()`, the three parts registered and restored together (`EquipmentStructure`, `PhysicalFluidTopology`, `FluidFacts`), `ValveSource.EJECTOR`, the slice plan (motive pressure, share, target). Solver test: a closed air drum on the suction, 10 bar steam on the motive, a 1.2 bar void on the discharge: the drum falls toward P_0 along the curve, mass, energy and components closed; a shut motive gives no capacity; a shut discharge no flow.
3. Noise: `Noise.ejector`, `Noise.Kind.EJECTOR`, the case in `FluidWorldAuthority.islandNoise`, the client loop, `sounds.json`, the `en_us.json` subtitle. `NoiseTest`: 110.5 ± 1 dB small, the cap large, NaN without flow.
4. Sound candidates; the chosen loop to the assets.
5. Corpus image, the comments in `Noise` and `ColumnStarts`, code guide (`fluid-science.md`, `fluid-runtime.md`, `equipment.md`), batch README.

Tests: targeted `--tests` only (the new science and solver tests, `NoiseTest`, the facts corpus test, `FlowControlTest`, `ProcessUnitTest`), at most 4 workers, never while a game client runs.

## 7. Decisions for the owner

| | Question | Recommended |
|---|---|---|
| E1 | Rated suction speed | A: 60 m/s, the simulator's sizing limit (small 197 kg/h air on 784 kg/h steam) |
| E2 | Deepest vacuum P_0 | A: 1 kPa |
| E3 | Steam drawn | A: the nozzle's flow, ∝ P_m, whatever the load |
| E4 | Motive control | A: always on; the player's own valve on the motive line |
| E5 | Old worlds | A: old ejectors register and run if piped |
| E6 | Vacuum column start | A: keep the refusal; its own batch |
| E7 | Loop pitch | A: fixed |
| E8 | Simulator | A: no sheet change now |
| J | Loop candidate | owner listens to J1 to J3 (made in phase 2) |

Fixed, not options: P_r 10 kPa, P_dr 120 kPa, MDP ∝ P_m, the motive factor from `EjectorLaw`, the 100 m/s caps (no choke law), zero shaft power.
