# Pressure safety valve: build review

Branch `claude/pressure-safety-valve-e38517`: `c919a0ec9` engine, `439a22868` accessory, `db6a8f34c` GUI, `aae56cf0a` tests, `09e106041` side outlet facing the player, drawings and documents, `1a1965d4c` separate valve groups, then D6 (the owner's direction, section 4). Plan: [PRESSURE_SAFETY_VALVE_PLAN.md](PRESSURE_SAFETY_VALVE_PLAN.md).

## 1. Expected behaviour

A spring valve holds the guarded pressure at or below its set pressure: shut below it, and at it passing what the vessel takes in beyond what it gives out, to the atmosphere (open outlet) or into the pipe (piped). A full vessel relieves liquid (D2). Example: a 2.6 m³ drum set at 3 bar on a 5 bar nitrogen feed stands at 3 bar and passes 1.11 kg/s. A discharge line that cannot carry that flow under the 100 m/s cap builds back pressure, and the vessel rises above its set pressure, as behind a real undersized tail pipe.

## 2. What the code does

- The run out of a drum's `RELIEF` port, or a pipe's outlet face, carries `FlowControl.PressureValve` at the set pressure (D1), stated from the guarded node: no new law or unknown. An open outlet is a 1 m, 0.1 m stub to a built-in void at the ambient, which books the release. A run into a flare is one line, valve, pipe and tip bore (D6); into a firebox, a blanketed vessel or a driven end it splits at a middle junction. A pipe with a PSV stays a junction node.
- Facts: `FluidDeviceSpec.relief`, `reliefFace` (fluid facts 23); a second fitting per host (accessory facts 6); corpus images for both. Set pressure: ambient + 10 kPa to + 10 MPa, default + 300 kPa (D3), above a blanketing valve's vent pressure (set × 1.05).
- Drum: on reservation F2, no taller than the top nozzles; the outlet leaves the body to the side, toward the player who fits it (owner); a taken side cell refuses the fit; on a 5.5 m lying drum it stands a cell higher. Pipe: the PSV is its only fitting, standing up, its outlet across the pipe.
- GUI: the drum's Accessory page has one framed group per valve, "Blanketing valve" and "Safety valve" (owner: "in obvious different groups"); a pipe with a PSV opens the valve's own screen. Renders in `tmp/psv/gui/`.

## 3. Measured (unchanged by D6)

| Case | Result |
|---|---|
| Gas 5 bar, set 3 bar, open | P = 300000 Pa; relief 1.1105 kg/s, feed 1.1109 |
| Water 6 bar, fills | level 1.0; 53.4 kg/s of liquid; P = 300000 Pa |
| Piped 0.15 m to a second drum / on a pipe | 300000 Pa, second 146 kPa, 1.094 kg/s / junction 300000, drum 300054 Pa |
| Feed 2 bar | 200044 Pa; relief 0; `STEADY` |
| Ten drums, one 0.4 m header (D5) | one island at 300000 Pa, 11.11 kg/s; 81 + 7 substeps over 21 commits (4.2 per commit, as before) |

## 4. D6: the flare's flow follows its back pressure (owner direction)

**Owner:** "Flare flowrate should vary with backpressure, and increasing backpressure will eventually lead to dysfunctional PSV". **Physics:** a flare tip is an opening to the atmosphere, not a regulator; it passes what the pressure behind it drives. The relief run has one regulator, the valve: at set while line and tip carry the load; as back pressure grows the valve opens wide, then the drum rises above set.
**Code.** The flare's tip valve (`ValveSource.FLARE`, name kept) is a bare line while open (`ControlValve.bare`), shut only while solids arrive (X5); the tip's bore is the run's last section, as a chimney's stack; a relief run into a flare is one line, no junction (split, both halves of one bore met the velocity cap together and held at tick 63). A2 is deleted: `ReliefValves`, the lifted state, checkpoint state 48 (back to 47). No facts change.
**X4 as status only (the orchestrator's call, for the owner).** Faster than the stable velocity (`Flare.stableVelocity`, on the gas expanded to 1 atm) the flame lifts off: FLAME OUT, no heat, the gas leaves unburnt; it relights below. Over the rated heat it burns on: OVER CAPACITY. "Most flow" reads "Stable flow".
**Curve** (drum 2.7 m³ set 3 bar, 5 bar feed through 0.1 m, 0.15 m relief line; `ReliefValveWorldTest`). The line law uses the drum's gas, so a choked tip shows as the 100 m/s cap at the drum's density.

| Gas, tip | Drum P | Relief | Valve outlet | Valve | Exit velocity (stable) |
|---|---|---|---|---|---|
| Methane 0.30 m | 300000 Pa | 2.568 kg/s | 102.5 kPa | throttling | 55 m/s (91.6): flaring |
| Methane 0.15 m | 300000 Pa | 2.568 kg/s | 103.0 kPa | throttling | 221 m/s (91.6): flame out |
| Methane 0.10 m | 491381 Pa | 2.523 kg/s | 491.4 kPa | wide open | 490 m/s: flame out |
| Nitrogen 0.30 / 0.15 / 0.10 m | 300000 / 300000 / 486749 Pa | 4.444 / 4.444 / 4.326 kg/s | 103.3 / 104.3 / 486.7 kPa | throttling, throttling, wide open | does not burn: flame out |

Step 5 → 7 bar (0.15 m tip): 2.568 → 3.610 kg/s, drum 300000 → 311538 Pa (wide open), no hold. Ramp 5 → 7 → 5 bar: back to 300000 Pa and 2.568 kg/s, no hold. GameTest (5 bar methane, 0.15 m discharge): 0.3 m tip 401325 Pa (= set), 0.612 kg/s, FLARING (unchanged); 0.1 m tip at set, 0.593 kg/s, FLAME OUT (blown off; before D6 the cap held it at 0.40); nitrogen 0.1 m at set, 0.936 kg/s, FLAME OUT.
**Moved expectations.** `FlareRuntimeTest`: a 10 bar supply into a 0.1 m tip passes 5.15 kg/s and blows the flame off (was held at its 0.47 limit, OVER CAPACITY); propane at 103 kPa burns on over its rating (62 MW of 23.6); the lean 1 : 4 mix runs 95 Pa over the atmosphere (at 101.8 kPa it now drives 29 m/s and blows off).
**Open.** A relief run into a firebox's fuel valve, a blanketed vessel or a set-flow mover keeps two regulators around a junction (no fix). A relief run straight into a flare has no X5 block.

## 5. Other open items

- No in-game check. Fluid GameTests run in the final lane. Translations: `en_us.json` only. The owner judges the drawings and screens.
