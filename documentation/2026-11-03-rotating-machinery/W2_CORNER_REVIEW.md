# W2 corner review: the chosen law F2b, and a law with slip (F2e)

Batch `2026-11-03-rotating-machinery`, W2, 2026-11-04: `8c396c1e5` (D10, "Take F2b (A)"), `fc7f0b39f` (F2e). Basis: the 59
quick-suite classes that load the movers and the 58 hold gates, run alone; no quick check of F2e (F1, F2, F2b used three).

## 1. Expected behaviour

- A positive-displacement pump or compressor delivers its displacement less its slip (a few per cent at rated pressure for
  a reciprocating pump), never more at a positive rise. Its relief valve returns the whole delivery within about 10 %
  accumulation: at the relief rise the net flow is zero and the machine runs on. Dead-headed, the discharge stays there.
- Behind a flow valve that passes less, the pump climbs to the rise at which it delivers that flow; a valve that asks
  more than the pump gives opens fully. A positive-displacement pump primes itself: it moves gas out of its suction.

## 2. What the code did

- **F2b** (`8c396c1e5`): 13 of 415 fail. Its knee line covers every rise below 77 % of the limit, not only flows past the
  displacement as `W2_FORMS_REVIEW.md` said: 100.0 to 101.5 % of the set flow there (seven tests, `CompressorTest` 0.020278
  for 0.02 m³/s, `InletPhaseTest` 10.09 for 9.97 kg/s). Four fail on convergence; as a rise the law is steep at the set flow.
- **F2c** (the corner as a flow, `u = 1 − v^64`; `tmp/rotating-machinery/w2-tests/f2c.patch`): 15 of 420 and 1 hold gate
  fail. A vertical law is a prescribed flow: singular behind a flow valve, a closed valve or a velocity cap (nine tests, the
  failure `PumpAheadOfFlowValveTest` records for F1), and `v^64` stalls Newton on the relief side (three).
- **D10's gaps**, failing under F2b and F2e alike:
  1. `aRunningLineFollowsItsTargetPastThePumpsRate`: a valve target raised from 6 to 50 kg/s keeps the valve regulating for
     the first pass, which asks the 10 kg/s pump for five times its displacement: −1000 relief rises on any sloped law,
     where the thermo fails. F1's yield rule opened the valve.
  2. `CoolingTowerRuntimeTest`, second fan mode: the pump closes while priming and never reopens: its rise limit scales with
     its suction end's density (P1, D4), gas there, while its refusal reads the line's liquid supply. F1 pushed the gas through.

## 3. F2e: two lines and a rounded corner (`fc7f0b39f`)

- `u = smin(1 − s v, m (1 − v) + δ)`, `smin(x, y) = (x + y − √((x − y)² + ε²))/2`; slip s 0.4 % of the displacement per
  relief rise, relief line m = 10 (10 % accumulation), rounding ε 0.02, δ = ε²/(4 (1 − s)). Delivery: 99.9989 % at no
  rise, 99.80 % at half the limit, 98.8 % at 90 %, 50 % at 95 %, 10 % at 99 %, none at 100 %; never above the displacement.
- The row is a flow, `Δp_r (Q − Q(Δp))/Q_d`: constant flow derivative, head derivative between s and m, linear on each
  line; its inverse is a quadratic root. Slip alone stays above the status line's 99.5 %: "delivers N %" from 89 % of the limit.
- δ is needed: a closed mover reopens where its rise limit beats the demand by 0.01 Pa, so the law's no-flow rise must be
  the rise limit (`FlowControl.Mover#riseLimit`). Without δ it fell 1e-5 short (5 Pa at 5 bar): six tests cycled.
- Tests: 418 of 420 and 58 of 58 hold gates pass; the two that fail are D10's gaps. Restated: a dead-headed mover runs on
  its law with no net flow or stands closed (eight tests), the slip in two. Shutoff is approached exponentially: the
  three-tank chain certifies STEADY replaying 1.4e-6 kg/s; the gas transfers reach their limit in 0.7 s and rest.

## 4. Options for the owner

- **A. F2e** (recommended), with two start-stage proposals for D10's gaps:
  - A1. A flow valve starts a solve open when its target exceeds what its line passes fully open on the movers' laws
    (the balance's start flow), in place of F1's yield rule.
  - A2. A closed set-flow mover reopens on the density of its supply, the stream its refusal reads: it primes itself.
- **B. F2e with 2 % slip**, a reciprocating pump's: 99 % at half the limit; the status threshold moves to 98 %.
- **C. F2b as chosen**: restate the seven over-delivery tests; the two gaps and two F2b-only stalls stay open.
- Then one full campaign of the chosen law (1000 seeds), the turbine connection (plan 3.4), W3.
