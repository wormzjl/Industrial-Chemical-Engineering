# W2 campaign review: law F2e with start rules A1 and A2

Batch `2026-11-03-rotating-machinery`, W2, 2026-11-04, commit `bd89826a3` (owner decision D11, readings A9 and A10). One
full campaign (1000 seeds, three distributions, 8 workers, alone) against the 0.19.2 baseline (F1); held seeds traced alone.

## 1. Expected behaviour

- A positive-displacement pump delivers its displacement less its slip up to its relief rise. Dead-headed, it stands at
  its relief with no net flow. It primes itself. Starved by its suction line, it cavitates and passes what the line gives.
- A flow valve whose target exceeds what a set-flow mover in its line moves stands open; the mover sets the flow.

## 2. What the code does (`bd89826a3`)

- A1 (A9): a valve carried in regulating starts open above the lowest set flow in its junction-balance line; the running
  line follows 6, 50, 5, 0, 4 and 50 kg/s.
- A2 (A10): reopening alone on the supply's density cycled: a generator at 997.18 kg/m³ behind a junction at 997.17 is
  5 Pa at 5 bar against a 0.01 Pa band. The relief rise now reads the supply's density in the law and the mode tests;
  the cooling-tower pump primes. The start balance stays on the suction stream: on the supply's relief an LNG pump fed
  by a 1 cm line started liquid at its 124 kPa bubble point and every step size stalled (`HoldClassWorldsTest`).
- The 59 mover classes (415 tests) and the 58 hold gates pass.

## 3. The campaign

| Distribution | Base clean | F2e clean | New holds | Gone |
|---|---|---|---|---|
| cold random | 991 | 992 | none | 431 |
| bundled random | 993 | 992 | 575, 952 (E5, line search) | 301 |
| bundled defaults | 1000 | 998 | 563, 896 (E8, substep limit) | none |

Seed time: cold +9.8 %, bundled random +7.3 %, bundled defaults +97 % (seed 563 alone 30 s); the baseline has no counters.

- **896** (E8, 22 blocks): a pump fills a closed 1 m³ tank with water until it is liquid-full, then dead-heads it. On
  F2e's relief line 0.23 L of water lifts the tank 500 kPa (time constant 2.3 ms). Every step fails the reconstruction
  gate at 3.9e-8 (gate 1e-8); 516 of 1024 attempts are rejected and the interval runs out. F1's shutoff latch closed the
  pump there; D10 deleted it.
- **952** (E5, 23 blocks): a 0.01 m³/s pump draws light crude from a 390 kPa generator through a 1 cm line, which gives
  3.0 kg/s. Pass 0 takes the pump to 4.8 kg/s and its suction junction to the 1 Pa floor, as liquid; the line search
  stalls at every step size. A root exists at about 47 kPa and 2.85 kg/s on the relief line. F1 started such a pump on
  its flat head limit when its target exceeded a suction link's velocity cap (`suctionCapped`, deleted by D10).
- On the head basis (A10's law reverted) both replays hold the same. The four new holds come from the law, not A1 or A2.
  575 and 563 share their classes and are not traced.

## 4. Options for the owner

- E8: (a) a set-flow mover whose relief returns its whole delivery at a converged pass closes, and reopens by the existing
  margin rule, which the lift δ makes agree at the relief: F1's latch restated for the law. (b) Leave the hold.
- E5: (a) a set-flow mover whose displacement exceeds a suction link's cap starts at that cap on its law: a start seed,
  not a mode (F1's `suctionCapped` restated, like A1). (b) A bubble-pressure suction floor for every liquid (D59 (4), K6
  widened): a bubble-point evaluation per starved pump. (c) A flatter relief line (lower m).
- Recommended: E8 (a) and E5 (a), then the affected tests and a new full campaign. Not proposed: a relief as a pressure.
