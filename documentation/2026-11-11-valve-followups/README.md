# 2026-11-11 valve follow-ups

Status: in progress since 2026-11-11. Branch `claude/valve-followups`, cut from `main` at `324ce9284` (0.27.0).

Purpose: the items left open when 0.27.0 merged (owner, 2026-11-11: "fix the issues").

## Items

| # | Item | Source | State |
|---|---|---|---|
| 1 | A blanketed drum started empty on the design feed holds ("Newton line search stalled", active-set cycle) | [GAS_BLANKET_REVIEW.md](../2026-11-11-gas-blanketing/GAS_BLANKET_REVIEW.md) section 5, item 1 | open |
| 2 | The blanketing valve's vent lags a fast fill (171.8 kPa against a 127.4 kPa vent pressure) | same, item 2 | open |
| 3 | A relief run into a firebox's fuel valve, a blanketed vessel or a set-flow mover keeps two regulators around a junction and can hold | [PSV_REVIEW.md](../2026-11-11-gas-blanketing/PSV_REVIEW.md) section 4 | open |
| 4 | A relief run straight into a flare has no solids (X5) block | same | open |
| 5 | The lane's campaign runs at time factor 1; the game's default is 4 | [FINAL_LANE_REVIEW.md](../2026-11-11-gas-blanketing/FINAL_LANE_REVIEW.md) | open |
| 6 | `AGENTS.md` said simulated time is "a nominal 20 TPS" (W7 of the time factor plan) | [TIME_SCALE_PLAN.md](../2026-11-11-minecraft-time-delta/TIME_SCALE_PLAN.md) | done |
| 7 | No in-game check of either valve or of the flare's back pressure | both reviews | the owner starts it |

## History

- 2026-11-11: batch opened. `AGENTS.md`: simulated time is online ticks at a nominal 20 TPS, times the time factor (`fluid.timeScale`, default 4). No test run (a document).

## Documents

- None yet. The review goes to `VALVE_FOLLOWUPS_REVIEW.md`.

## Code areas

- Expected: `science/fluid/network` (`GasBlanket`, `OpenVessel`, `PassiveIntervalSolver`, `ControlConstraints`, `PassiveStepSolver`, `FlowControl`), `runtime/fluid` (`PhysicalFluidTopology`, `Flares`), `tools/unified-thermo-port-forms` and `tools/merge-lane` (the campaign's factor).

## Open items

- Items 1 to 5 and 7 above.
- Not in this batch unless the owner asks: the valve item's icon, the Accessory tab on a drum that already shows four tabs, a hand-placed supply pipe beside the rear nozzle's on a 4 m lying drum, no valve on a 1.5 m standing drum.
