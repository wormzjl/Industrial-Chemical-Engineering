# D14 holds review: bundled random 896, cold random 181

Commit `e9313e1db` (D14). Both seeds were clean at `a8accb372` and hold at D14. Traced alone with a temporary tracer (`tmp/rotating-machinery/w2-tests/trace-d14.js`), then with one counterfactual (`probe-d14-b.js`). Outputs: `tmp/rotating-machinery/quick/D14-trace-{896,181}` and `D14-trace-{896,181}-probeB`. The source is restored.

## 0. The campaign of `e9313e1db` (1000 seeds, three distributions, 8 workers, alone)

| Distribution | Base | D13 (`a8accb372`) | D14 | New against D13 | Gone against D13 |
|---|---|---|---|---|---|
| cold random | 991 | 992 | 991 | 181 (E6) | none |
| bundled random | 993 | 993 | 993 | 896 (E5), 6 (F, a baseline hold) | 575, 952 (E5) |
| bundled defaults | 1000 | 1000 | 1000 | none | none |

Counters are within 2 % of D13 (bundled random backtracks +2.5 %); bundled defaults' are identical, while its seed time
rose 22 %: machine load, so seed times do not compare. Refused registrations: 0. Before it, the affected tests (98
classes, the hold gates) pass, with `PumpRateWarmStartTest`'s 2 m³/s pump restated to its suction limit.

## 1. Expected behaviour

- **896.** A crude generator (346 K, 282 kPa) drains about 52.8 kg/s through a tee `n0` and a 10 cm line into a void at 101 kPa. The engine's TP equilibrium gives the crude a bubble pressure of 214.78 kPa at 346 K. Below it (0.99 of it) the crude is two-phase with a vapour volume share of 0.0085; above it (1.01) it is liquid. The stream starts to flash at the tee, so `n0` sits at its bubble point (214.7 to 214.9 kPa). A pump (0.01 m³/s, 500 kPa) draws from `n0` through a 1 cm line. Its available NPSH is about 0 (148 Pa at the step's start, and less after the elevation head). It is vapour-locked and delivers about 0. The main stream is the same as before: STEADY at 52.76 kg/s.
- **181.** LNG boils in a vented tank at 112.06 K and 1 atm. Its bubble pressure is 101 324 Pa: below it (0.99) the vapour volume share is 0.775; above it (1.01) it is liquid. A pump (0.01 m³/s ≈ 4.2 kg/s, 1000 kPa) draws through junction `n4` (101 580 Pa, so 256 Pa of NPSH) and a 1 cm line, and returns the LNG to a junction at 251 kPa. The line gives about 0.03 kg/s at the floor, so the pump cavitates and passes that.

## 2. What the code did

Both floors are correct. They are true bubble points, not a liquid-liquid split. They do not flip between solves: 214 691.6 → 214 776.8 Pa, and 101 325.0 → 101 324.1 Pa, while the walk's end junction moves from its minted seed to the balance's seed. No pumps are in series. The modes do not cycle: no pass converges, so every failure keeps `PUMP_SUCTION_LIMIT`. The deleted E5 (a) start seed is not the cause (probe B clears both seeds without it). In both seeds the walk ends at a flowing junction that already stands at its own bubble point. `BalanceNet.solve` finds the pump's law suction below the floor (896: 192.5 kPa; 181: 70.8 to 73.7 kPa) and puts it on its suction limit. It anchors the suction junction at the floor and prescribes what the line delivers there: `inflowExcept` = 0 in both seeds. The pump then stands on its limit with no flow.
- **896 (singular, stalled).** Every pass, at every substep down to 9.5e-8 s, stalls at residual 14.2 on the tee's mass row. Inflow is 46.9 kg/s and the liquid's capped outflow to the void is 60.85 kg/s. Only a flashing tee below 214.78 kPa balances. But then the pump's suction, held at 214.78 kPa, stands above its supplier. The line reverses, and the pump draws backwards out of an empty tank (19 K state; head −2.3e23 Pa at dt 0.05 s). So no root exists: the floor stands above its supplier's operating point. Before D14 this supplier, a one-phase liquid, gave no floor.
- **181 (iteration limit).** Pass 0 reaches residual 2.0e-8 on a node row against the 1e-9 tolerance, and stops there at every substep. The anchored suction junction is laid out with gas and oil (GO), with a vapour volume share of 1.7e-4. It is held 0.1 Pa above the bubble point at the supplier's start temperature. The liquid it receives has been throttled by 256 Pa, which warms it by about 1e-4 K (the Joule-Thomson effect in a liquid) and raises its bubble pressure by about 1 Pa (dP/dT ≈ 8 kPa/K). So the junction holds a vanishing vapour phase. This is the defect that `waterFloor` already states and fixes for water.
- **Probe B** (the balance closes a pump whose line gives nothing at its floor, in place of anchoring it). Both seeds are clean. 896 is back to STEADY 52.76 kg/s. 181 is FULL, with 43.3 kg of crystals against 43.4 kg at `a8accb372`. On the way, 181 has 46 failed Newtons: the pass loop reopens the pump (CLOSED → MOVER_LAW on rise against demand) and it cavitates again. 181 took 8.4 s alone, against 5.0 s in the D13 campaign. That is wall time, not judged.

## 3. Proposals for the owner

The physics forces one point: a pump whose suction line delivers nothing at its floor (NPSH available ≤ 0) passes nothing. How to state it is an option:
- **A. Mode (balance start and pass loop).** The balance puts such a pump on CLOSED (vapour-locked), as probe B did. The pass loop's CLOSED → MOVER_LAW test also requires the supplier's end pressure to stand above the floor. It deletes the anchored zero-flow state and the reverse drive. It risks a CLOSED/MOVER_LAW/SUCTION_LIMIT cycle without the reopen test (181's 46 failures). Partly tested (probe B, without the reopen test).
- **B. Row (`case PUMP_SUCTION_LIMIT`, `vapourPressure`, the balance anchor).** The floor is min(bubble, the supplier's trial end pressure), so the suction never stands above its supplier and the flow goes to zero as the supplier flashes. One mode, no new transition. It risks a kink in the row where the supplier crosses its bubble point (a non-smooth Newton). Untested.
- **C. Floor evaluation (`bubblePressure`, for 181's trace vapour).** Bisect the bubble point at the liquid's own enthalpy, as `waterFloor` does for water, so the anchored junction receives one liquid. Water and hydrocarbons get one statement, and `waterFloor`'s separate Newton step is deleted. It risks the cost of PH equilibria in place of TP (cached once per supplier state), and it moves `PumpCavitationTest`'s water numbers. Untested. Probe B clears 181 without it, so C is a robustness item.
- Recommended: A with its reopen test (the pump law's own stage), then the quick check. C only if a hold of 181's form remains.

Code guide: no change (review only).
