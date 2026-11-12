# Gas blanketing valve: review

Batch `2026-11-11-gas-blanketing`, branch `claude/gas-vent-hold` (cut from `8f91ccd11`, the drum-operation-holds tip). Owner, 2026-11-11: "for vertical & horizontal drums"; "Keep the drum pressure to set value, pressure too high -> release fluid (gas), pressure too low -> introduce gas"; "need to be connected to a gas source through piping, vent to atmosphere"; "an optional accessory"; "a dedicated accessory page"; "placed on top of drums"; "you can potentially introduce a lag".

## 1. Expected behaviour

- A closed drum's gas space changes with its liquid. Draining with no gas source pulls a vacuum until the outflow stops (defect 4 of the drum batch: a rigid full drum cannot drain). Filling compresses the gas until the feed stalls.
- A blanketing valve holds the gas space near a set pressure: it admits supply gas below the set pressure and vents to atmosphere above it. Draining 0.2 m3/s at 121 kPa takes about 0.27 kg/s of nitrogen; filling at that rate vents the same.

## 2. What the code does now

- **Law** (`GasBlanket`). The drum stays an ordinary closed vessel (slack bag, then rigid). Its set pressure is a fact (`FluidDeviceSpec.blanket`, ambient + 5 kPa to ambient + 1 MPa, default ambient + 20 kPa).
  - Pad: the run into the drum's `BLANKET` port carries a planned valve. At each solver step it targets the gas the drum lacks at the set pressure (over γ = 1.4, closed over a 1 s response time) plus the gas for the room the liquid vacated at the last step's rate.
  - Vent: above set × 1.05 the drum gives its excess gas to the atmosphere over the same 1 s, booked as a `VENT` boundary.
  - Both are start-state terms: no new solver unknown, no new mode. The response time is the owner's lag.
- **Where**: the pad is planned in the interval solver's per-step stage (beside the fired coil's firing), not once per slice. Per slice the pad lagged a whole slice: at factor 4 a slice moved half the rig's drum, and the pressure swung 64 to 164 kPa.
- **Accessory**: item "Gas blanketing valve", fitted on a drum's first top flange (F1; F2 stays the PSV's), removed with the wrench. Owner on the first drawing: "this location is not good and it's too big". Now a slim nozzle with the body half way up, no taller than the drum's own top nozzles; the inlet is its top flange, so the supply pipe stands in the cell above. A lying drum reserves F1 at the rear end of its crown and F2 at the front end, outside the nozzles and the probe row. A standing drum 1.5 m across has one roof cell, its nozzle's, and takes no valve.
- **Page**: the drum screen shows an "Accessory" tab while fitted: set pressure (entry, Apply), drum pressure, the pad and vent pressures.
- **Saved**: fluid facts schema 22 (set pressure, port role), state format 47, accessory facts schema 5; corpus images `compat/fluid/schema-22/`, `compat/accessories/schema-5.snbt`.

## 3. Measured

| Case | Result |
|---|---|
| 1 m3 drum, 150 kPa set, placed empty on 500 kPa nitrogen | fills to 149 999.96 Pa, no vent, island rests |
| Same drum at 300 kPa, no supply | vents to 157.49 kPa (the vent pressure), 44.4 mol booked |
| Same drum, 80 % water, drained 0.02 m3/s | lowest 145.3 kPa while draining (per slice without forecast: 117.7) |
| Same drum full of water | drains (the pad opens on a 1 % room) |
| World: 20 m3 drum on a two-pipe 5 bar line | 134 999.98 Pa of 135 000, "STEADY: no flow"; valve removed keeps contents |
| Level loop, factor 4, blanketed, standard start | passes: 121.3 to 171.8 kPa, level 25.0 to 89.9 %, 50.00 % at the step and the end, residual 8.1e-10 mol of 1.6e7 (before the valve moved: to 148.6 kPa) |
| Level loop, factor 4, blanketed, from empty on the design feed | **holds** (below) |

Gates run: 18 changed-class test classes (194 tests), 5 equipment gates (59 tests, with the supply-pipe gate in `ShapeRasterizerTest`), `GasBlanketTest` 4, `BlanketValveWorldTest` 1. Not run: the final lane (chain-100 exact, the 33 junction lines, the campaign, the GameTest suites); the plan stage is a no-op on a graph without a blanketing valve.

## 4. Found on the way

- The GameTest server crashed on the first run: the drum's view had no face for the supply connection (`TankOutlets`, "No compiled face"). The run into a valved sink never recorded its tank faces; fixed for both ends, which also covers a tank feeding burners directly.
- `FluidCheckpointStoreTest` assumed a tank's unit is under 1000 bytes; the new field made it 1007. Its limit is now 1100. The rig's feed ramp sent a step before the panel had taken the last one (a stale revision, refused; the feed stayed at 70 %). It now waits for the panel's revision. The rig lays its supply line as the pipe tool does.

## 5. Open

1. **Direct start holds.** Blanketed drum, empty, full design feed, 50 % setpoint, no ramp: the level controller reads nothing for a cadence, the drum overfills to the feed pressure (387 kPa), then drains wide open at 0.40 m3/s with the pad holding 121 kPa, and the next slice holds: "Newton line search stalled", 131 attempts, "Phase/device active-set cycle" 26. With the per-slice pad the same run passed twice, the drum under vacuum. Not diagnosed: no probe run. It is the drain the gas-vent hold (drum batch, defect 1) also has: a gas-fed drum emptying through the 6 m leg to a void. Log: `tmp/gas-blanket/level-loop-4x2-blanket-direct-held.txt`. **Closed 2026-11-11** ([valve follow-ups](../2026-11-11-valve-followups/VALVE_FOLLOWUPS_REVIEW.md), item 1): not reproduced on `178d7e2f4`; gated by `level-loop-blanket-direct` and `BlanketDrainWorldTest`.
2. **Vent lag on a fast fill.** The vent is a 1 s start-state release: the run reached 171.8 kPa (148.6 on another phase of the ramp) against a 127.4 kPa vent pressure. A forecast as the pad's needs the liquid's rate inside the step solver's release terms. Owner option. **Closed 2026-11-11** ([valve follow-ups](../2026-11-11-valve-followups/VALVE_FOLLOWUPS_REVIEW.md), item 2): one law for pad and vent with the forecast; fast fill 157.52 kPa against 157.50, level loop 121.3 to 127.4 kPa.
3. The item icon is the control valve's. The accessory tab is absent on a drum that already shows four tabs. On a 4 m lying drum the inlet's pipe cell stands beside the rear nozzle's: a pipe placed by hand there joins the two lines, a laid line does not.
4. No in-game check: the ghost on the flange, the wrench, the pipe joint at the inlet.

## 6. Proposal

- Keep the standard-start blanketed loop as the gate (`level-loop-blanket`).
- Treat item 1 with defect 1 of the drum batch, on a probe run of this rig (`-Dicheme.levelLoop.blanket=true -Dicheme.levelLoop.direct=true`): it now reproduces at factor 4 in three minutes.
