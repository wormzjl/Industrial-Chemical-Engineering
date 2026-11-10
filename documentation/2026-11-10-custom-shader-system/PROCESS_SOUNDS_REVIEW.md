# Process sounds beyond the flare and the still: review

2026-11-10, owner: "for sounds, think about what other equipment can have them, including the valves; also think about update performance". Plan to be approved before any work.

## 1. What makes noise in a real plant (sound power Lw, re 1 pW)

| Source | When it sounds | Character | Loop |
|---|---|---|---|
| Valve, gas or steam (control, pressure, shutoff partly open) | Stream power at the vena contracta W = ṁ u²/2. Acoustic efficiency is about 1e-4 subsonic and up to 1e-2 choked (P2/P1 below about 0.5; IEC 60534-8-3). Steam at 1 kg/s, 10 → 3 bar: W ≈ 100 kW, ≈ 140 dB inside, ≈ 85–100 dB outside after 40–55 dB of pipe wall. A 10 % drop: ≈ 55–65 dB outside, near silent | hiss peaking at 1–4 kHz, a scream when choked | new `valve_gas`, pitch up when choked |
| Valve, liquid | Silent unless it cavitates or flashes: σ = (P1 − Pv)/ΔP below about 2 | gravel crackle | reuse `pump_cavitation` |
| Liquid line stopped fast (shutoff closed, check valve slam) | Joukowsky ΔP = ρ a Δv: water at 2 m/s stopped dead gives ≈ 24 bar | one knock | new one-shot `pipe_knock`, on a published flow stop |
| Open vessel or tank vent (`OpenVessel.VENT_SPEED` 100 m/s) | jet noise grows as u⁸ (Lighthill): loud at 100 m/s, nothing at 10 m/s | hiss | `valve_gas`, lower pitch |
| Air cooler fans | blade pass rpm/60 × blades (300 rpm × 4 = 20 Hz) plus broadband; ≈ 95–100 dB per fan at full speed, falling as 50 log10(speed) (fan law) | low whoosh | new `fan_whoosh`, pitch ∝ speed |
| Induced-draft tower | its fans, plus falling water at the air inlets (500 Hz–4 kHz), rising as 10 log10(water flow) | rain roar | `fan_whoosh` + new `water_fall` |
| Natural-draft tower | falling water only, the loudest thing at its base | rain roar | `water_fall` |
| Fired heaters | burners as firing; forced-draft fan | roar, whoosh | reuse `burner_roar`, `fan_whoosh` |
| Gearbox | gear mesh at teeth × rpm/60 (30 teeth at 1,500 rpm = 750 Hz) | whine | new `gear_whine`, pitch ∝ rpm |
| Kettle reboiler | boiling pool, muffled by the shell | low rumble | reuse `pot_boil`, low gain |
| Steam ejector (if it has a live state in the world) | supersonic motive jet, 100+ dB | steady roar | `valve_gas`, choked pitch |
| Drums, columns, tanks, spheres, exchangers, pipes | enclosed, or flow noise only at high velocity | — | none; one loop per pipe block would cost the most for the least |

The server already has what valves need in each accepted `PipeObservation`: both pressures, mass flow, density, gas ratio and the losses per segment. Vapour pressure for σ comes from the island's model, once per valve per publication.

## 2. Update performance

- **One noise watch, culled on the server.** Once a second the client asks for the noise sources within 48 blocks. The server answers from the last published snapshot, with no solver run: at most 16 entries (position, kind, level in dB, pitch), the loudest first. Levels are computed once per presentation publication (about every 5 s) per island and kept by chunk. That is about 200 bytes a second per client, whatever the number of valves. It replaces per-device watches for these sources.
- **One client budget for every IChemE loop** (`FxSounds`). At most 12 loops play at once, chosen by level at the listener (Lw − 20 log10 r). The choice is redone 4 times a second, not every tick, with 3 dB of hysteresis so loops do not flap. Sources of one kind within 6 m merge into one loop at their loudness-weighted centre, levels summed by energy (10 log10 Σ 10^(L/10)). Each playing loop's `tick()` only eases gain and pitch toward targets cached at the last update, with no world or map lookups.
- **Why this matters.** OpenAL offers about 255 sources shared by every mod. Minecraft ticks every playing tickable sound 20 times a second. Sound Physics Remastered raycasts each playing source. An uncapped valve-per-loop design would scale with the size of the plant.
- **Levels to volume.** Minecraft's linear attenuation reaches 16 × volume blocks. A documented map from Lw to volume (for example 100 dB → 1, 16 blocks; 120 dB → 3, 48 blocks; 90 dB → 0.3, 5 blocks), and easing over 2 s between publications.

## 3. Owner decisions

**Decided 2026-11-10.** Owner: "For valve I think most noise comes from decompression, so scale its volume based on dP will be sufficient, others as recommended." So a valve sounds only while it passes flow, and its level follows its pressure drop alone: Lw = 70 + 20 log10(ΔP / 20 kPa) dB, silent below 20 kPa, capped at 120 dB (about 6.3 MPa); 1 MPa gives about 104 dB. The loop is picked by phase (gas or steam: `valve_gas`; liquid: the `pump_cavitation` crackle), with no choked-pitch or cavitation-number rule. S1 A for everything else, S2 A, S3 A, S4 A. The fx-noise agent (Opus, medium) is approved to start after phase 1.

- **S1 Scope.** A: everything in table 1 (recommended). B: A without the water-hammer knock and the kettle. C: valves only.
- **S2 Data.** A: one noise watch with server-side culling to the 16 loudest (recommended). B: one watch per kind, as the movers have now.
- **S3 Budget.** A: 12 loops at once, 6 m merge radius (recommended). B: other numbers.
- **S4 Existing sounds.** A: the mover and driver loops (pumps, compressors, motors, turbines) join the same budget; their watches stay as they are (recommended). B: leave them outside the budget.

## 4. Work, if approved

A fresh agent `fx-noise` (Opus, medium) after phase 1: the server noise levels and watch, `FxSounds` with the budget, the new loops (`valve_gas`, `fan_whoosh`, `water_fall`, `gear_whine`, `pipe_knock`) in `tools/process-sounds/`, and tests (levels against the numbers above, budget choice, merging, hysteresis). It needs the owner's yes, as every agent does.
