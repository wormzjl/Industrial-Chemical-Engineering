# Pressure safety valve: build review

Branch `claude/pressure-safety-valve-e38517`: `c919a0ec9` engine, `439a22868` accessory, `db6a8f34c` GUI, `aae56cf0a` tests, then the side outlet facing the player, drawings and documents. Plan: [PRESSURE_SAFETY_VALVE_PLAN.md](PRESSURE_SAFETY_VALVE_PLAN.md).

## 1. Expected behaviour

A spring valve holds the guarded pressure at or below its set pressure: shut below it, and at it passing what the vessel takes in beyond what it gives out, to the atmosphere (open outlet) or into the pipe (piped). A full vessel relieves liquid (D2). Example: a 2.6 m³ drum set at 3 bar on a 5 bar nitrogen feed stands at 3 bar and passes 1.11 kg/s. A discharge line that cannot carry that flow under the 100 m/s cap builds back pressure, and the vessel rises above its set pressure, as behind a real undersized tail pipe.

## 2. What the code does

- The run out of a drum's `RELIEF` port, or a pipe's outlet face, carries `FlowControl.PressureValve` at the set pressure (D1), stated from the guarded node: no new law or unknown. An open outlet is a 1 m, 0.1 m stub to a built-in void at the ambient, which books the release. A run into a valved sink (flare tip, firebox, blanketed vessel) or a driven end splits at a middle junction. A pipe with a PSV stays a junction node.
- Facts: `FluidDeviceSpec.relief`, `reliefFace` (fluid facts 23); a second fitting per host (accessory facts 6); corpus images for both. Set pressure: ambient + 10 kPa to + 10 MPa, default + 300 kPa (D3), above a blanketing valve's vent pressure (set × 1.05); `FluidDeviceSpec.withValves` sets both at once.
- Drum: on reservation F2 (a lying drum's crown front end; a 3 m standing drum's next free roof cell; none on a 1.5 m standing drum), no taller than the top nozzles. The outlet leaves the body to the side (owner: "connection should point to side"), toward the player who fits it (owner: "outlet should face the user when placed"); the face is the relief port's, kept on a structure refresh; a taken side cell refuses the fit. On a 5.5 m lying drum the crown fills every side cell at the flange: the valve stands a cell higher on a longer inlet.
- Pipe: the PSV is the pipe's only fitting; it stands up (on a standing pipe, sideways); its outlet faces the player on a free horizontal side across the pipe.
- GUI: the drum's Accessory page has a group per valve (PSV: set pressure entry or meter, relieving rate, discharge Air or Piped); a pipe with a PSV opens the valve's own one-tab screen. Renders in `tmp/psv/gui/`.

## 3. Measured

| Case | Result |
|---|---|
| Gas 5 bar, set 3 bar, open | P = 300000 Pa; relief 1.1105 kg/s, feed 1.1109 |
| Water 6 bar, fills | level 1.0; 53.4 kg/s of liquid; P = 300000 Pa |
| Piped 0.15 m to a second drum | 300000 Pa; second 146 kPa; 1.094 kg/s; one island |
| Piped through 0.05 m | 472 kPa: the line chokes at about 0.3 kg/s (back pressure) |
| Feed 2 bar | 200044 Pa; relief 0; `STEADY` |
| On a pipe | junction 300000 Pa; drum behind 300054 Pa; 1.111 kg/s |
| Ten drums, one 0.4 m header (D5) | one island, all at 300000 Pa, 11.11 kg/s; 21 commits, 81 accepted + 7 rejected substeps (4.2 per commit); 248 ms for 80 s of play |
| GameTest: methane 5 bar to a 0.3 m flare | 401325 Pa (= set); 0.612 kg/s relieved = flared; piped |

D5 holds. Tests passing: ReliefValveWorld 6, FluidFactsCompat 20, PhysicalFluidTopology 34, PhysicalRegistry 11, FluidCheckpointStore 9, AccessoryStoreCompat 8, DeviceColumn 10, FluidPacketCodec 26, PipePresentation 14, FluidGuiConsistency 6, EquipmentServices 9, InstrumentWiring 5, ShapeRasterizer PSV gates 2, ReliefFlareGameTests 1 (alone: `tools/psv-flare/run.sh`).

## 4. Open: an undersized flare holds the island (D6, owner)

**Expected.** The tip passes at most its limit (rated heat, stable-flame velocity); above it back pressure builds and the vessel rises over its set pressure.
**Code.** On a 0.1 m tip the tip's mass-target valve binds (methane 0.45 kg/s; nitrogen 8e-4 kg/s, an inert's low stable velocity) while the PSV regulates: two active regulators around the zero-volume middle junction leave its pressure undetermined, "Singular Newton Jacobian", HELD. A tip that passes the load converges (the GameTest).
**Options.** A (recommended): a lag, as the owner allowed: such a valve opens to a share planned each solver step from the guarded pressure, proportional from set to set + 10 % (a real valve's accumulation); one active regulator remains. B: keep only the sink's law on that run; loses regulation below the tip limit. C: refuse a flare below the relief load; blocks a real case.

## 5. Other open items

- No in-game check. The fluid GameTest runs in the final lane. Translations: `en_us.json` only.
- Drawings show the default outlet direction (away from the drum's middle); in game the player picks it. The owner judges the iso views.
