# A flow valve's change that holds its island: review (2026-11-17)

Asked by the owner after build 7 of the review plant ("Yes, derive it"): the crude line's island was HELD for about
210 s at the column's start, and in build 6 the bottoms island was HELD to the end. No mod code was changed. The runs
are `tools/valve-hold/SeriesHoldScratchTest.java` (a scratch test, copied into `src/test` for one run and removed),
outputs in `tmp/solver-hold/run-1.log` to `run-5.log`.

The rig in small: a supply at 200 kPa, a centrifugal pump (rated 20 L/s at 50 m; water), an optional player's flow
valve ("charge"), an optional parallel pair of runs, a column-sensor flow valve ("inlet") into a void at 200 kPa, bore
0.1 m. The open line passes 51.0 kg/s (the pump's runout against the line).

## 1. Expected (physics)

A flow valve holds its target while the line can pass it. Asked for more, it stands wide open and the line carries
what it can: here 51.0 kg/s. Two flow valves in series carry one flow, the lower target's. None of this depends on
what the line did a moment before.

## 2. What the code does

One step's solve picks each valve's mode (open, regulating, closed) by an active set: it solves with the modes it
starts from, then changes the modes the solution contradicts, and solves again. A regulating valve becomes open only
after a solve with it regulating has converged.

- A solve starts either *carried* (the modes, heads and flows of the island's committed interval,
  `PassiveIntervalSolver.replayStart`) or *fresh* (`DeviceModes.initialModes`: every flow valve open; the balance
  start). A carried regulating valve stays regulating (`PassiveStepSolver`, lines 418 to 441), with two exceptions
  that start it open: a target above a set-flow pump's bound (decision D11) and a starved supply.
- A failed solve is retried at a smaller time step ("Substep refinement exhausted"), which cannot help: the failure is
  in the modes, not in the step. The island is then HELD.

## 3. Measured

In the world (an edit of the inlet's target, 400 ticks each):

| Case | Line | Change of the inlet's target, kg/s | Outcome |
|---|---|---|---|
| I | pump, one valve | closed to 1000 | open at 51.0 |
| J, K | pump, one valve | 3 to 30; 3 to 50 | regulates |
| L, P, Q | pump, one valve | 3 to 60; 30 to 60; 3 to 60 after standing open | HELD: line search stalled, pass 0 |
| M | pump, one valve | 3, 20, 40, 50, then 60 | regulates to 50; HELD at 60 |
| N | pump, one valve, supply 600 kPa | 3 to 60 | regulates (the supply drives 60 through the pump) |
| O | no pump, supply 600 kPa, one valve | 3 to 1000 | HELD: residual 221, pass 0 |
| C | pump, pair, one valve | 3 to 1000 | HELD, pass 1 |
| E | pump, pair, charge 5 | closed to 10 | charge regulates at 5 |
| F | pump, pair, charge 5 | 3 to 4.9 | inlet regulates |
| A, G | pump, pair, charge 5 | 3 to 10; 3 to 5.1 | HELD: residual 14.93, pass 1 |
| H | pump, pair, charge 30 | 3 to 40 | HELD: residual 14.40, pass 1 |
| B, D | pump, charge 1000, with and without the pair | 3 to 1000 | HELD: singular Jacobian, pass 1 |

The second target solved directly on the island's graph, from the committed interval and by a fresh solver:

| Case | Carried | Fresh |
|---|---|---|
| A (charge 5, pair: 3 to 10), H (charge 30: 3 to 40), J (3 to 30) | ok | ok |
| A2 (charge 5, pair: closed to 10), at 0.1, 1, 5 and 20 s | ok | fails: residual 14.932, pass 1 |
| B, C (3 to 1000), L (3 to 60), O (no pump: 3 to 1000) | fails | ok: open at 51.0, 51.2, 172.2 |

## 4. Mechanisms

**M1, a target the line cannot pass (derived).** A carried regulating valve whose new target is above what the line
passes is solved as regulating. That problem has no solution with the valve at most wide open, so the Newton stalls,
no pass converges, and the mode never becomes open (L, M, O, P, Q, B, C). It needs no pump and no second valve. The
fresh start solves each, because it starts the valve open. This is what a player gets who sets a flow valve above its
line's capacity while it runs, and what a column's port valve gets when its plan asks more than its line passes: the
likely cause of build 6's bottoms hold (the pass asked 3 times the design bottoms of an unpumped line), not shown.

**M2, regulating again from the open line (located, not derived).** With a pump on its curve and a parallel pair, a
start with both valves open converges on the open line (51 kg/s), and the next pass, with the charge valve
regulating, stalls at a residual of 14.93 (14.40 for a target of 30): the same number as build 7's small
reproduction and the world's cases A, G and H. It does not depend on the inlet's target or on the interval's length;
it does not happen without the pair or without the pump. Which rows carry the residual is not known: the solver has
no trace of its iterations.

**Neither start is safe.** The carried start fails on M1 and the fresh start on M2; in every case tried one of the
two solves.

## 5. Not done, not known

- The Newton iterations of M2 (which equations stall, and why the pair matters).
- Which start the world used in cases A, G and H (the direct replay of A solves from both).
- Build 6's bottoms island and build 7's crude island replayed against M1 and M2.
- A fix. Candidates, none tried: (a) on a failed solve, the other start before the step is refined (covers every case
  measured; one rule, on the failure path only; it does not explain M2); (b) M1 at its root: a regulating valve whose
  solve fails is tried open, as D11 does for a set-flow pump; (c) M2 at its root, once its iterations are read.
