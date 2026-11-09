# O10 review: the medium grade's classical stall and the block energy gap (2026-11-05)

Work package O10, owner decision E24. Code at `afd832af1`; no fix landed. Probes and the scratch patch: `tools/open-thermo-data/o10/` (`O10StallProbe`, `O10GridProbe`, `O10GapProbe`, `o10-instrumentation-and-forms.patch`). Runs and traces: the session worktree's `tmp/open-thermo-o10/`.

## 1. Expected behaviour

- From 622 to 644 K the feed vaporizes more and the answer moves smoothly. Warm continuation from the converged 638.15 K state (wet set kept; `existence.txt`) converges at all 10 points in at most 4 Newton iterations. Every change is monotone: distillate 6.43 to 10.48 kg/s, bottoms 100.80 to 93.79 kg/s, condenser 26.9 to 36.3 MW, draw trays about 0.45 K warmer per K of feed. Every failing temperature has a solution, and the classical successes agree with it to 0.01 kg/s: the defect is the classical path, not physics.
- No tray flow falls hundreds of orders below its neighbours in one Newton correction; a point below the support floor (1e-10 of its feed) is not an unknown. Each model closes its own energy; for the same T, P and amounts, the two models differ only by how their property models disagree.

## 2. What the code did

Stall (traces `trace-medium_crude-636.log` and `-638.15.log`):
- It stalls in the 30-stage dry surrogate: no draws, steam replaced by a 12.5 MW reboiler, no pumparounds, feed tray 28. Free water plays no part (no wet trays), and no tray is dry.
- The seed interpolated from 15 stages starts at a scaled residual of 9.2 (PC16 equilibrium on tray 24), and the local-block steps crawl (2^-10 to 2^-14).
- At 636 K, iteration 16 accepts a step of 9.8e-4 that takes PC11 liquid on tray 2 to 5.9e-210 mol/s; its neighbours are at 1e-8 to 1e-14. Armijo accepts the step because that point is a small share of the merit.
- From then on the coordinate's FD column (L dF/dL) is exactly 0, and the LU reports SINGULAR (pivot 67) in iterations 17 to 67. Damped Gauss-Newton creeps to a non-root stationary point at 1.38e-3 (the energy row of tray 14). After the refresh drops the dead point, the next attempt and the bubble-point recovery exhaust their line search at 1.375e-3.
- 638.15 K falls into the same trap (PC10 vapour on tray 1 at 8.5e-17, SINGULAR in iterations 23 to 35), but there the creep happens to lift the point, and Newton converges at iteration 61. Which temperature escapes is chance: a basin effect, hence the non-monotone failures. This is lead (1), the trace spike, in a new form. The cause is a log-flow coordinate that falls below its floor inside a frozen-support solve, not the row scaling fixed in 2026-09.

Gap (`gap.txt`: sheet `alone`, feed at its tray and let down from 1 MPa; V3's own closure 0.0 kW, so the per-stream sum reproduces the gap exactly). Each row is simulator minus V3, signed as it adds to the gap, in kW:

| Term | med, tray | med, 1 MPa | TJL, tray | TJL, 1 MPa |
| --- | --- | --- | --- | --- |
| steam in (331 mol/s, 533 K, 2.5 bar): -136 J/mol | -45.0 | -45.0 | -45.3 | -45.3 |
| free water out (327 mol/s, 332 K): -285 J/mol | +93.3 | +93.0 | +94.5 | +94.3 |
| overhead (water vapour and light ends) | +7.8 | +11.1 | +6.1 | +10.0 |
| feed and hydrocarbon products, net (each within 0.015 %, 6 kW) | +1.4 | 0.0 | +0.9 | -0.1 |
| gap / condenser duty | 57.5 / 34.2 MW = 1.7e-3 | 59.1 / 27.0 MW = 2.2e-3 | 56.2 / 41.6 MW = 1.4e-3 | 58.9 / 36.1 MW = 1.6e-3 |

- A model-basis difference, not a conservation defect. It is water: the network's steam is an IF97 second-virial gas, h_res = p(B - T dB/dT) = -136 J/mol at 533 K and 2.5 bar, while V3's steam is an ideal gas; and the two water models price liquid water at 332 K 285 J/mol apart. The hydrocarbons share `crude_pr78`. The gap is 56 to 59 kW on both grades. It does not grow on the medium grade; the ratio grows because the medium grade's condenser duty is smaller (less distillate).

## 3. Proposal (the owner chooses)

Stall. The forms act in the Newton stage on a point (a stage component, with its retained phases moved together). Classical start; grids of 10 points from 622 to 644 K; O3's light, heavy and dilbit presets; numbers are control polls.

| Form | Medium grid | TJL grid | light / heavy / dilbit | Adds |
| --- | --- | --- | --- | --- |
| baseline | 5/10, 6,206k | 10/10, 6,624k | ok 549k / ok 551k / ok 1,325k | - |
| F1: hold a sinking point at its floor (projection) | 10/10, 54,140k | 10/10, 58,748k | ok 6,122k / 7,241k / 6,935k | projection; every rung's first attempt runs 128 held iterations |
| F2: fraction-to-boundary step | 8/10, 46,030k | 10/10, 60,081k | ok / ok / FAIL | step cap |
| F3: end the solve when a point sinks | 5/10, 2,026k | 10/10, 2,808k | ok 506k / 1,299k / 793k | exits from cold seeds use up the 3 refreshes |
| F1b: F1, released to the refresh after 8 held iterations that do not halve the residual | 10/10, 3,445k | 10/10, 3,228k | ok 735k / 1,179k / 248k | projection; release; full-budget refresh after a release |

- A release after 3 held iterations gave 6/10 and failed 638.15 K. F1b on 6 columnGates classes: 40 of 44 tests pass. Of the 4 failing tests in `V3StageContinuationTest`, 3 call one frozen-support solve and expect it to converge (F1b hands that solve back to the refresh). The fourth, the wet half-steam case, now converges without the full-budget steam resume that was built for its zero pivot.
- Recommendation: F1b, in `V3SimultaneousColumnSolver.armijoTrial`, with the release seam in `solveSingleProblem`. It applies the support's own rule inside the solve, passes everything above, and uses fewer polls than the baseline on both grids. It may delete the full-budget steam resume (not verified). Open: the 3 test contracts, the hold-fuzz quick check, the release window (8 iterations; the stall stop uses 12).

Gap (the 2e-3 rule fails only with the let-down):
- (a) One water model: V3 takes the network's virial steam and liquid-water functions. The gap falls to the hydrocarbon net, about 1 kW. The column moves slightly (45 kW less steam heat), and the wet revision is bumped.
- (b) The block books the water-basis term as a named reading, and the gates test the rest. (c) Scale the rule to the steam's latent heat.
- Recommendation: (a). It removes the cause.
