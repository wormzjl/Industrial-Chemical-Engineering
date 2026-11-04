# W2 campaign review: D13 (taut-vessel row weights, E5 (a) start)

Batch `2026-11-03-rotating-machinery`, W2, 2026-11-04, commit `8343a4bd0` (owner decisions D12, D13). One full campaign
(1000 seeds, three distributions, 8 workers, alone), against the 0.19.2 baseline (F1) and the F2e campaign (`2a2ed1a3c`).
Before it, the affected tests: 98 quick-suite classes and the hold gates pass (`D12_TESTS_REVIEW.md` for the withdrawn clip).

## 1. Expected behaviour

- A dead-headed pump runs its curve to zero flow at its shutoff, and its check valve holds a higher discharge. A tank
  full of liquid reaches the shutoff pressure and stays there: no hold, and no work once the line is at rest.
- A pump fed through a line that passes less than its set flow cavitates and delivers what the line gives at the
  liquid's vapour pressure.

## 2. The campaign

| Distribution | Base clean | F2e clean | D13 clean | New against base | Gone against base |
|---|---|---|---|---|---|
| cold random | 991 | 992 | 992 | none | 431 |
| bundled random | 993 | 992 | 993 | 575, 952 (E5) | 6, 301 |
| bundled defaults | 1000 | 998 | 1000 | none | none |

- **E8 is gone.** 563 and 896 run clean. In bundled defaults the implicit solves fall from 181,014 to 26,788 and the
  step attempts from 178,426 to 24,285: the dead-headed tanks had been refining the step. Seed time is 78.2 s → 40.6 s.
- **Bundled random.** 6 (F, the budget class) is clean. 241 changes class F → E6 (Newton iteration limit, an old
  class: 431 is E6 in all three runs). Newton iterations −15 %, seed time −26 %.
- **Cold random.** Same holds as F2e. Counters are within 1 % (newton solves +0.5 %), except backtracks +6.7 % and
  Jacobian builds +1.4 %: the weighted norm costs a few more line-search steps.
- Refused registrations: 0 in every run.

## 3. E5 (575, 952): the start seed changes nothing

Both seeds hold at the same ticks with the same residual (952: 8.506 on the pump's law row) as in the F2e campaign.
In 952 the stalled point has the 1 cm suction line at its cap (3.04 kg/s) and the pump on its law at 4.8 kg/s,
with its suction junction at the 1 Pa floor, as liquid. The start seed caps only the start. The Newton walks to that
point from any start, because nothing in the equations stops a liquid's suction pressure below its vapour pressure.
The suction limit (`PUMP_SUCTION_LIMIT`, D59 (4)) reads only the vapour pressure of the water a pump draws, and this
pump draws crude.

## 4. Options for the owner

- **(b) Recommended: a vapour-pressure suction floor for every liquid** (D59 (4) widened). The suction limit reads the
  bubble pressure of the stream the pump draws, whatever it is. A starved pump then delivers what the line gives at
  that pressure. Cost: one bubble-point evaluation per starved pump per mode test.
- (c) A flatter relief line (lower m). This does not stop the floor, it only moves where the Newton meets it.
- (d) Leave E5 as a known hold.
- Whatever the choice, E5 (a) has no measured effect, so I propose deleting it (`suctionCap`). 6 and 241 moved with
  the weights or with E5 (a); this run cannot separate the two.

Code guide: no change yet (the merge refresh covers W2).
