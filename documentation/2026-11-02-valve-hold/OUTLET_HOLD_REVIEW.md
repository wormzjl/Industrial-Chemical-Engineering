# Outlet holds: a closure row that cannot see its trace

Batch `2026-11-02-valve-hold`. Diagnosed 2026-11-02 on a scratch merge of `claude/valve-hold` and the column branch. The owner chose option A on 2026-11-02; c39e2bcd3 applies it with the door fix.

## 1. Expected behaviour

Each product line starts full of its idle nitrogen. The product pushes it out in seconds: the junction's share falls by 1e-3 to 1e-6 each 5 s job.
A trace in a liquid moves no pressure and starts no flash, so nothing physical can stop the line.

## 2. What the code did

A junction has one mixing row per component except the last in the layout's order. Only the closure row, `sum(n)/scale-1`, states that last share.
With water in the junction the last is water. Without water it is nitrogen (index 19), whatever it holds.

The Jacobian uses forward differences with a step of 1e-6 of the unknown. The closure's sum stands near 1, where a double resolves 2.2e-16, so below 1.1e-10 the step is lost.
The held matrices show it. Closure row, unknown and secant: 1.96e-10 gives 1.13, 5.9e-10 gives 1.123, 1.17e-9 gives 0.947, which are 1, 3 and 5 rounding units over the step. Below that the secant is 0.
Nitrogen's column is then all zeros, and the matrix is singular at every step size: "Substep refinement exhausted". Just above it the column is noise: "Interval substep limit", accepted 504, rejected 520.
Each line held at its job start: DRAW0 3.2e-12 (tick 1673), DRAW1 2.5e-12 (1673), DRAW2 3.1e-12 (1734), BOTTOMS 9.8e-14 (1588), DISTILLATE 2.3e-12 (1754).

**Carried point or balance:** every one of the 20 failing records carried its point (`carried` 10 to 24, `balance` 0, `rate` 0). This is not defect 2.

**The leads,** from each held job's own snapshot and committed interval, through `RetainedSolver`, ten jobs from two plants in one world:

| Replay | Passed |
|---|---|
| Unchanged | 0 of 10 |
| 20 K colder, junction and generator | 1 of 10 |
| Nitrogen removed | 10 of 10 |
| Nitrogen raised to 1e-8 | 4 of 10 (it moves the crossing, it does not remove it) |

The trace decides; the bubble point does not. The controls agree: sour water's nitrogen fell 9e-13, 1e-15, 1e-18, 1e-21, 1e-24, then 0, and off-gas's the same way. Neither held, because their closure sits on water.

## 3. A door my start fix left open

Correction, 2026-11-02 (merge code review, finding 6): when this was written the door stayed open in production. `sameLines` in `replayStart` alone had no effect, because a hand-set target arrives as a topology event and the replacement compared strictly. 56b083702 closes it at the island replacement, `withJunctionStates` and `replayStart`.

`RegulatingValveHoldTest` and the probes ran one solver with no `replayStart`, so "unit two-slice cases pass" proved nothing. With `RetainedSolver`, and a new target and composition each slice, the gate failed.
`replayStart` still compared the strict identity, so a new target in the graph lost the committed interval and restarted cold: the same defect 2, 546 K liquid at 176 kPa. A column escapes it, because `ports` sets its target after the replay. A hand-set valve does not.
A new case runs nine slices, past the trace level. Scratch results: both changes below, 5 of 5 pass. No bulk closure: the new case fails as the world did (carried, nitrogen 1.9e-11, zero column). Set point back in the identity: 2 fail.

## 4. Options and decision

Physics forces none of these; each is numerics. The door fix is not optional: it finishes defect 2 (`sameLines` in `PassiveIntervalSolver.replayStart`).

| Option | Change | Measured | Risk |
|---|---|---|---|
| A, chosen | The closure row reads the junction's largest component; every trace keeps its own mixing row | World, bulk closure alone: 54 jobs, nine lines, no hold, nitrogen to exactly 0 by job 26. Quick suite with the door fix: 1845 green | Touches every dry junction: `slowGates`, the final lane and a campaign must show it |
| B | Floor the difference step of the closure's unknown near 1e-8 | Not run | The share stays 1 minus the rest, rounding noise at 1e-16; it cuts against the measured comment on `differenceStepScale` |
| C | Drop a junction trace under 1e-10, ledger booked | Nitrogen-removed replay: 10 of 10 | A new threshold rule for a problem of arithmetic |

Open, separate: a cold start seeds a junction with the delivered mixture. A component its stock holds and the mixture lacks gets x = 0 and a step of 1e-36, also a zero column. The door fix keeps running lines away from it; a cold start can still reach it.
Results with option A and the door fix: branch quick suite 1808 green, `RegulatingValveHoldTest` on the production path with 5 tests. Clean merge of both branches: review world no hold, RUNNING, limit NONE at tick 2776, each stream and duty on plan; quick suite 1846 green. `slowGates`, the final lane and a campaign not run. Tools: `tools/valve-hold-probes`. Logs: main checkout's `tools/valve-hold-probes/logs/`. Code guide: no change, no merge asked.
