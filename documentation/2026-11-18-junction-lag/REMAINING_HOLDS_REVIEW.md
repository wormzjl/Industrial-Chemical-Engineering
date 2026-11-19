# Remaining fluid holds

2026-11-19 follow-up to the [junction qualification](JUNCTION_LAG_IMPLEMENTATION_REVIEW.md). The measurements below cover separate fixtures and captured requests. The final 6,000-tick saved plant had zero charge holds and delivered 292 positive feed bookings. Its greatest same-slice heat-delivery difference was 0.011365 K. Physics commit `5b34bff75` passed 195 selected tests.

The affected lane at `6d000cdde` covered 2,141 tests, the exact chain regression, 140 required server GameTests and 65 watched worlds. That lane observed 34 first holds against the earlier 35. The donor, phase and warm-regulation fixes followed the campaign. The watch-world count on the later solver is unmeasured.

## Cold junction precision

A 107 K LNG tank receives warmer, ethane-rich replenishment and feeds a vent through a previously gas-filled fitting. At about 285 kg/s in the 0.20 m line, a 50 µg circulating parcel makes the row weight `1 + Δt·Q/A` about 5.70 × 10⁹ for the first one-second step. Here `Q` is incoming mass flow and `A` is circulating mass. The dominant enthalpy residual was 7.19 × 10⁻⁷, corresponding to an unweighted gap of 1.26 × 10⁻¹⁶. The other rows were within 5.4 × 10⁻¹⁶. The original solver exhausted all 1,024 attempts after 1.7405 of 20 process seconds. Of 515 rejections, 22 came from the reconstruction equation gate.

When the only outstanding equations are mixing rows within four units in the last place, the solver gives the same step one retry with a larger circulating parcel backed by the same D46 stock. It returns reserve components and enthalpy in proportion. It checks the forecast and the booked transfer against the front bounds, including a 0.5 K temperature difference and a constituent difference within 0.1% of the reserve-to-stream change. The mixed equations, 10⁻⁹ Newton tolerance and 10⁻⁸ reconstruction gate still apply. Refinement after a failed retry and a subsequent job after cancellation both start from the original inventory.

The fixture completed all 20 seconds in five accepted substeps with zero controller rejections. It used 14,839 checkpoints and returned about 0.575 g of reserve to circulation, roughly two parts per million of first-step delivery. The independent whole-interval ledger discrepancies were 7.53 × 10⁻⁶ J and at most 5.39 × 10⁻¹⁰ mol. `JunctionPrecisionTest` preserves the original minted mass. It and nine existing cold balance, crystal and junction-front checks passed standalone. Canceling inside the speculative retry kept the initial inventory exact, and the same solver then completed the interval.

The archived cold 101 and 803 reports give pass-0 residuals near 10⁻⁹ but omit the failing equations and state. Their campaign status remains open.

## Head-limited pump startup

A 305 kPa water source and a 450 kPa-rise pump feed an empty raised tank through an 11 mm restriction. The discharge loss limits the flow to about 1.194 kg/s. The suction stands at 92.75 kPa, about 84 kPa above its cavitation floor, and the pump supplies 442.94 kPa of rise. The old start paired the balanced suction flow with a pump seed of 7.97 kg/s and zero rise. The trial pulled the suction pressure below its floor and switched modes. The fixture exhausted its refinements with nine singular Jacobians and eleven active-set cycles.

The balanced start now carries the pump's law flow and matching rise together. `PumpCavitationTest` holds the first accepted pump on its mover law, checks suction margin and rise, and verifies that water reaches the empty tank. The standalone run passed all 23 pump-cavitation, valve-capacity and pump-junction checks.

The vapour floor and clean-zone edge repeated exactly 26 of 52 pressure–enthalpy flashes for bulk subcooled water, 13 of 52 for propane, and 14 of 52 for liquid LPG. Those calls accounted for 50%, 23% and 21% of the respective threshold checkpoints. The projected liquid port samples produced zero exact matches because the edge reads a unit-volume sample. The nitrogen-blanketed water and true dense CO₂ cases also produced zero matches. The checkpoint trace is in `tmp/junction-lag/hold-followup/suction-ph/overlap.txt`.

## LNG pump at its suction limit

A five-second production request at committed tick 1,327 fed a pump from a boiling LNG tank. The suction junction initially held liquid. An incoming-stream estimate proposed a gas phase with 24% volume share. Newton then drove it down to approximately 5.5 × 10⁻⁶ at ordinary step sizes. The failed-phase check compared the iterate only with the committed liquid and missed the collapse of the newly proposed gas. A reconstruction of the dispatch exceeded four million checkpoints with only 28 substeps accepted.

The failed-phase check now applies the existing collapse criterion against the seed for phases introduced at a junction in that pass. The normal converged phase checks still decide the physical phase set. The exact dispatch then completed all five seconds in three accepts, zero rejects and 469,994 checkpoints. The average suction flow was 0.07357 kg/s. An independent regression covers a collapsing proposal, a persisting proposal, already committed vapor and component totals.

## Long blanket-drain request

The captured tick-454 state was exercised with an 80-process-second work-counted request. The earlier controller exhausted 1,024 attempts at 48.1091 seconds, with 513 accepts and 511 rejections. Repeatedly trying a failed larger step spent attempts needed to finish the interval. The follow-up retains a proven smaller size when it can cover the remaining interval and repeating the rejected growth could consume that margin. The step still goes through Newton, reconstruction and audit within the original attempt limits.

The final controller completed the 80-second request in 909 accepts and 61 rejections, using 1,592,766 checkpoints against 3,124,577 on the earlier refused request. On a separate 12.8-second dispatch from committed tick 390, an intermediate controller took 990 accepts and 34 rejections and stopped at 12.7899 s. The final controller paces retries of the same failed increase while the state advances. It completed that dispatch in 219 accepts, 18 rejections and 353,732 checkpoints. Both drain and level-loop fixtures passed together with a single worker at the stock deadline.

## Charge feed reopening

At saved charge commit 97417, the column asked for 19.7877 kg/s after a zero-flow endpoint. The stopped endpoint still carried opposite branch circulation and pump shutoff conditions. A positive column port plan now drops those rates for the first balance when reopening is feasible, establishes available flow on the open valve law, then regulates where the circuit can meet the target. The solve uses the admitted mass target, thermal and shaft inputs.

The exact four-second replay previously needed 12 accepted substeps, 11 rejections and 779,279 checkpoints. The guarded open-balance probe reached 19.7877 kg/s in one accepted substep, zero rejections and 289,285 checkpoints. `FlowControlTest` checks startup from both `CLOSED` and zero-flow `VALVE_OPEN` endpoints. The earlier server recovery is recorded in the [saved-plant qualification](JUNCTION_LAG_IMPLEMENTATION_REVIEW.md#final-saved-plant-result-2026-11-19).

## Reversible junction branches

Near zero reverse flow, a positive finite difference could cross zero and build the mixing derivative from two different donors. A manufactured nitrogen tank 80 K warmer than its junction supplied 0.25 mg/s in reverse. A 1 mg/s forward difference sampled tank inflow for only one quarter of its span. Both the block Jacobian and the colored finite-difference path now take their probe on the donor side used by the residual when the connection admits either direction. One-way outlets retain their existing probe. On the equal-level six-port mixed-gas case this retained 45 Newton solves under the strict bound of 64.

The post-campaign changes are `565069cd7` for flow reversal and `fd1837614` for provisional phases. All 170 focused tests passed at the latter commit, including both blanket fixtures under the stock deadline, the LNG pump at its suction limit, exact reconstruction, certificate replay, front delivery and 48 hold-gate methods. The 12-case mixed-gas total matched the reference of 395 Newton solves. The donor change corrects the measured derivative. Several archived held junctions were adjacent to near-zero flows. Their reports lack the failed iterate's donor sign and cannot establish that this change clears those worlds. The independent cold supply/junction/tank probe also reached a pressure-balance norm of 2.09 × 10⁻¹⁴ in both orientations with the original pipe law.

## Verification status

An emptying vessel can change its node kind between Newton passes. `c09476c1c` gives each pass its own copy of the node kinds, which keeps earlier workspace keys stable.

The integrated focused Gradle selection passed all 20 methods across the drain, level-loop, backward Euler, and sparse Newton tests. The affected lane reported 2,139 passes and two failures. The parallel blanket drain met the wall deadline at committed tick 358. The parallel level-loop startup met it with zero worker lag and the fitting installed after ten ticks. Chain-100 was bit-exact on all four reported quantities. All 33 junction lines, including counters, matched. All 140 required server GameTests passed.

| Watched worlds | Held before | Held on `6d000cdde` | Changes in first-hold outcome |
| --- | ---: | ---: | --- |
| Cold random, 27 | 7 | 7 | 919 completed. 183 reached a deadline and then exhausted numerical refinement on its solo replay. |
| Bundled random, 35 | 25 | 24 | 116 and 803 completed. 855 reached its deadline both in the campaign and alone. |
| Bundled defaults, 3 | 3 | 3 | 768 reached the substep cap in place of its earlier deadline. |
| Total, 65 | 35 | 34 | Three prior holds cleared and two previously clean worlds held. |

The lane captured the first failed state and equation for each numerical failure. Cold 101 and 803 ended on passive hydraulic rows, with residual magnitudes of 1.86 × 10⁻⁹ and 1.53 × 10⁻⁹. The largest residual for cold 181 at the last singular iterate was a passive hydraulic row. This identifies the largest equation mismatch, not the singular pivot. Bundled 853 and 453, 645, 847 and 768 ended on junction mixed-enthalpy rows. Bundled 665 ended on nitrogen phase equilibrium and 889 on a suction-limited pump hydraulic row.

The 6,000-tick run at `6d000cdde` used a fresh copy of the earlier saved plant. The final sample showed committed tick 96,977, nine ticks of lag, a running column at throughput fraction 1.0 and 86.6002 kg/s at the feed. All 284 distinct new feed bookings were positive. The largest coil-to-feed temperature difference at feed pressure was 0.010119 K over 295 matched windows. At the first fully firing endpoint, the coils and feed both booked 637.690217 kg. The two charge deadlines at committed ticks 91,797 and 96,287 recovered during observation. Shutdown recorded another charge deadline at tick 96,997, after the final observation, and a separate island-1370 deadline at tick 97,017.

A shorter instrumented observation captured a positive-feed charge timeout at committed tick 91,897. The four-second command at 146.13175 kg/s used 1,811,276 checkpoints, with no accepted substeps and seven rejections. Its two-second replacement kept the same commanded feed and accepted in one step with 71,252 checkpoints. The replacement included an updated recuperator wall and spent 90% of its slice sitting out that exchange, so an internal two-second trial in the failed request does not represent the replacement command. The complete exchanger curves, accepted input, stage counters and both dispatch envelopes are in `held-loop-replay/charge-final-capture-live/world-run/charge-live/`.

In the failed command the open pass already found a feasible stream at 157.13866 kg/s and 521.256 K. The old graph still held the feed node at 627.738 K. Rebalancing from that state to start regulation spent 128,308 checkpoints in the first trial alone. Commit `5b34bff75` carries the just-converged state into regulation when the flow-law coordinate remained well conditioned. Replaying the original four-second command under its original worker deadline delivered 146.13175 kg/s in one accepted step, zero rejections and 69,908 checkpoints. The independently heated two-gas regression checks the change into regulation, the requested flow, booked coil heat and the component and energy ledgers. All 195 selected tests passed on the integrated source.

The shutdown checkpoint saved charge tick 96,997 as held, with retry due at 97,527 online. An 800-tick reopen on `5b34bff75` produced 66 fresh bookings, all positive, and zero new holds. On its first observed fresh booking at 97,017 the valve fed 64.93769 kg/s while both furnaces were off. Relighting was observed at commit 97,037. The last observation was 98,317 committed, 58.17656 kg/s and the column running at full throughput.

The final 6,000-tick production run on `5b34bff75` recorded zero charge holds, and no island logged a hold during observation. The charge reached commit 96,957, seven ticks behind online. All 292 distinct new feed bookings were positive. The column finished at throughput fraction 1.0 and the final feed was 89.0268 kg/s. The maximum temperature difference at common pressure was 0.011365 K across 294 matched windows and 0.006824 K across the 171 fully firing endpoints. The first fully firing interval booked 637.690217 kg at the coils and at the feed. All 30 required tests in that server run passed. A separate island 1413 warning occurred after the observation at shutdown.

## Shutdown cancellation

The post-observation warning came from the column bottoms stream, island 1413, at the hot side of the crude-feed recuperator. Its saved checkpoint held the old committed tick 97,018 and a new retry date of 97,698 despite a last accepted flow of 57.86442 kg/s. The coordinator had cleared every uncommitted worker result and closed its group through the deadline path on normal stop.

Commit `0f4c08c41` detaches proposals before it cancels workers. It preserves the last committed graph, status, bookings and retry date. A completed group member whose result has not been committed is discarded with the rest of its group. Genuine holds keep their outstanding backoff. All 137 selected coordinator, clock, checkpoint, certificate and coupling tests passed, including late completion and restoration during a real retry.

On a disposable copy of the affected checkpoint, island 1413 recovered its previously saved retry and resumed committing bottoms intervals. At online tick 98,139 the real server stopping event intercepted its outstanding 98,118–98,138 slice. The coordinator published zero outcomes at stop. The saved island was `FULL` at 98,118 with zero retry delay. All 14 live committed ticks and retry dates stood across the stop event. Four already-certified islands then materialized their safe replay horizons at save. All 14 persisted with retry zero, with no `HELD` or `SOLVING` statuses. The original world's 50-file content manifest was unchanged.

Local reports are under `tmp/junction-lag/hold-followup/lane/head/`. The independent captures, final focused tests and solo reports are under `tmp/junction-lag/` in `extreme-turnover/`, `finite-draw/`, `hold-followup/`, `narrow-pump-active-set/` and `held-loop-replay/charge-hold-stage/`.
