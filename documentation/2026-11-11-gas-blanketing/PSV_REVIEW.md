# Pressure safety valve: build review

Branch `claude/pressure-safety-valve-e38517`: `c919a0ec9` engine, `439a22868` accessory, `db6a8f34c` GUI, `aae56cf0a` tests, `09e106041` the side outlet facing the player, drawings and documents, then D6 form A2. Plan: [PRESSURE_SAFETY_VALVE_PLAN.md](PRESSURE_SAFETY_VALVE_PLAN.md).

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
| GameTest: 0.1 m flare, methane | 492818 Pa (set 401325); 0.400 kg/s = the tip's limit; lifted, no hold |
| GameTest: 0.1 m flare, nitrogen | 486891 Pa; 0.427 kg/s = the tip's limit; lifted, no hold |
| Tip 0.3 kg/s (world test, feed 1.11) | 498232 Pa; relief 0.300; lifted once |
| Tip 2 kg/s / load at 95 % of the limit | 300000 Pa; never lifted |
| Load at 103 % of the limit | 307453 Pa; relief 1.0777 = limit; lifted once |

D5 holds. Tests passing: ReliefValveWorld 10 (1 disabled, section 4), FluidCheckpointCodec 13, FluidCheckpointStore 9, IslandCertificate 23, BlanketValveWorld 1, FluidFactsCompat 20, PhysicalFluidTopology 34, PhysicalRegistry 11, AccessoryStoreCompat 8, DeviceColumn 10, FluidPacketCodec 26, PipePresentation 14, FluidGuiConsistency 6, EquipmentServices 9, InstrumentWiring 5, ShapeRasterizer PSV gates 2, ReliefFlareGameTests 3 (alone: `tools/psv-flare/run.sh`).

## 4. D6, owner decision A2: an undersized flare

**Expected.** The tip passes at most its limit; above it back pressure builds and the vessel rises over its set pressure.
**Before.** The tip's mass-target valve and the PSV both regulated the zero-volume middle junction: "Singular Newton Jacobian", HELD.
**Code (A2, owner: "Yes, keep it").** A run into a valved sink carries `PressureValve.relief(set)`. `ReliefValves.plan` (each step, after `GasBlanket.plan`, from the step's start flows) lifts the valve wide open (a held opening of 1, no pressure control) while the far valve's limit L binds: inflow to the guarded node ≥ 0.98 L and P ≥ 0.98 set. It reseats only below set, with inflow and its own flow under 0.98 L. One regulator is active at a time. State only: checkpoint state format 48, no fact change.
**Not A.** A proportional lift (set to set + 10 %) moved every run: the gas drum swung around 303.6 kPa, a full drum and the header held, a 2 kg/s tip held.
**Limits.** A load crossing L on a ramp lifts cleanly but holds on the way down (tick 6007): each edit rebuilds the run regulating while the tip still binds (test disabled with this reason). An abrupt step across a binding tip holds at its first step. A load in [0.98 L, L) would lift and reseat on a long period (not tested). Driven ends keep the plain regulator; firebox and pad sinks are covered by the same rule but not tested.

## 5. Other open items

- No in-game check. The fluid GameTests run in the final lane. Translations: `en_us.json` only.
- Drawings show the default outlet direction (away from the drum's middle); in game the player picks it. The owner judges the iso views and the screens.
