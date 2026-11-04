# D15 holds review: bundled random 575, cold random 181 (active-set cycle)

Commit `93159d42d` (D15). Both seeds hold with "Phase/device active-set cycle" ×20. Traced alone with a temporary tracer (`tmp/rotating-machinery/w2-tests/trace-d15.js`), then two counterfactuals (`probe-d15-p.js`, `probe-d15-c.js`). Outputs: `tmp/rotating-machinery/quick/D15-trace-{575,181}`, `-575-probeP` (seeds 575 and 896), `-181-probeP`, `-181-probePC`. Five replays. The source is restored.

## 0. The campaign of `93159d42d` (1000 seeds, three distributions, 8 workers, alone)

| Distribution | D14 (`ffde40d7f`) | D15 | Change |
|---|---|---|---|
| cold random | 991 | 991 | 181 E6 → E7 (active-set cycle) |
| bundled random | 993 | 993 | 896 gone; 575 back as E7 |
| bundled defaults | 1000 | 1000 | none |

Cold counters within 4 % of D14; bundled random backtracks +26 %, Jacobian builds +7 % (the cycling seeds retry). Refused: 0. Before it, the affected tests (98 classes, the hold gates) pass.

## 1. Expected behaviour

- **575 (island 44, pump e4, 0.01 m³/s, 500 kPa).** Crude at 302 K reaches the pump through junction n4 → 1 cm line → n5. n4 is one liquid at 223.6 to 241.2 kPa. Its bubble pressure (the floor) is 124.18 kPa, so the available NPSH at no flow is 99 to 117 kPa (12 to 14 m of crude at 859 kg/m³). The setting asks 8.6 kg/s, which is 127 m/s in a 1 cm line. The line cannot give that. Bernoulli gives at most about 1 kg/s at 100 kPa, before friction. So the pump runs **on its suction limit**, not closed.
- **181 (island 29, pump e3, 0.01 m³/s, 1000 kPa).** LNG at 112.06 K drains from the vented tank through junction n4. With the drain flowing, n4 stands 400 Pa above the 101 323.6 Pa floor (9 cm of LNG at 455 kg/m³). That NPSH passes at most about 0.05 kg/s through the 1 cm line (D14 measured 0.029 kg/s), against a target of 4.55 kg/s. With the drain stopped, n4 stands at 101 325.0 Pa, its own bubble point, and the NPSH is 0. The pump is on its suction limit with a trickle, or closed. The difference to the island is 0.03 kg/s.

## 2. What the code did

One mechanism in both seeds. The cycle is CLOSED → MOVER_LAW → CLOSED, in the same pass key:
1. The balance (`supplierHead` on its own pressures, with the pump on its law: in 181 it pulls n4 down to −20 kPa of head) or the carry starts the pump CLOSED.
2. The closed pass converges with the supplier at its no-flow pressure (575: suctionHead +117 kPa, rise − demand 418 kPa; 181: +400 Pa, 260 kPa). `nextMode` reopens the pump: CLOSED → MOVER_LAW.
3. On its law the Newton fails (line search stalled at 0.28 to 9 051), because the law pulls the suction to 1 Pa and the **supplier itself** down to 7 to 73 kPa (575) or 14 to 28 kPa (181).
4. `cavitates` reads `suctionHead` on that failed iterate. The supplier stands below the floor (−51 to −117 kPa of head), so it puts the pump on CLOSED, not on PUMP_SUCTION_LIMIT. That is the key of pass 0, and the loop throws a cycle. At D14 the same branch went to the suction limit, and 575 was clean.

The two disagreeing rules are therefore D15's vapour-lock test, read on a point where the pump's own law has drained the supplier (the failed iterate, and the balance with the pump on its law), and the reopen test, read on the closed converged point. The floor does not move: 124 183.30 Pa and 101 323.62 Pa in every pass. suctionHead never flips across the band at a converged point. The boiling-supplier branch is never taken (n4 is "O" in both). The trace-vapour stall of 181 does not drive this cycle: no pass reaches the suction limit.

- **Probe P** (the vapour-lock test reads the supplier at no flow, on the step's start states (`startHead`), in `supplierHead`, `cavitates` and `nextMode`'s cavitating branch; the reopen test is unchanged): 575 is clean (island 44 FULL), and 896 stays clean (STEADY 52.76 kg/s). 181 goes back to D14's form: on its suction limit, the anchored n6 holds trace vapour (GO), and Newton stops at the iteration limit at 2.0e-8 at every substep.
- **Probe P + C** (bubble point bisected with PH at the liquid's own enthalpy): the floor rises 0.9 Pa, from 101 324.09 to 101 324.998 (101 325.06 against 101 324.99 on the start supplier). Tick 1078 now passes, so the trace-vapour stall is gone. But at tick 1080 the step starts after a closed step with n4 at 101 325.0000, exactly its own floor (startHead = 0, vapour-locked). The rate solve's converged closed point puts n4 back at 101 724 Pa (+399 Pa), `nextMode` reopens, and the cycle returns: the start-state test against the converged-point reopen test.

## 3. Proposals for the owner

The physics forces one point: whether a pump is vapour-locked depends on its supplier at no flow. It is not decided on a point the pump's own law has drained. Within one solve that answer is one fact. Options:
- **A. One decision per solve (recommended).** suctionHead is read once, on the step's start states, the same states as the floor (A13). It is a solve constant: ≤ band → CLOSED for the whole solve (carry, balance, `cavitates`, `nextMode`, reopen included); > band → never closed for vapour lock, only for a reverse flow, with `cavitates` and `nextMode` going to PUMP_SUCTION_LIMIT. The hysteresis is the reopen band. It deletes `supplierHead` (its balance-pressure branch), the `Equations` overload of suctionHead and the three per-point readings. It risks a pump kept closed for one step while its supplier recovers. In 181 that is 0.03 kg/s for one step. Probe P is this without the reopen part, so the reopen part is untested.
- **B. Probe P as tested** (no-flow reading for close, converged point for reopen). It clears 575 and keeps 896. 181 still cycles once C is applied, as shown above, so B alone is not enough.
- **C. Floor at the liquid's own enthalpy** (D14 option C, PH bisection, like `waterFloor`). It is needed under A or B for 181's trace-vapour stall: the probe cleared that tick. It deletes `waterFloor`'s separate Newton step only if water joins it, which was not probed. It risks PH equilibria cost (about 24 per supplier state, cached) and moving `PumpCavitationTest`'s numbers.
- Recommended: A + C, then the quick check and one full campaign. 575 and 896 are cleared by P. 181 clears only if A's per-solve constant holds; that is not yet replayed.

Code guide: no change (review only).
