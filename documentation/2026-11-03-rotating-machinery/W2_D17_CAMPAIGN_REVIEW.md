# W2 campaign review: D17 (a pump's suction limit never above its supplier)

Batch `2026-11-03-rotating-machinery`, W2, 2026-11-04, commit `9449ecce2` (owner decision D17). One full campaign (1000
seeds, three distributions, 8 workers, alone). Before it, the affected tests (98 classes, the hold gates) pass.

## 1. The campaign

| Distribution | Base (0.19.2) | D16 (`7b46b3b05`) | D17 | New against base |
|---|---|---|---|---|
| cold random | 991 | 992 | 992 | none |
| bundled random | 993 | 994 | 994 | 896 (E5) |
| bundled defaults | 1000 | 1000 | 1000 | none |

- The held seeds are the same as D16's. Counters move within 1 % except bundled random's backtracks (+7.3 %) and
  Jacobian builds (+2.2 %), from the wider pattern where a pump stands on its suction limit. Refused registrations: 0.

## 2. Bundled 896

1. **Expected behaviour** (`D14_HOLDS_REVIEW.md`). The tee n0 falls to its bubble point (214.78 kPa) within the
   step; the pump's suction follows it; the pump passes about nothing; the island is STEADY at 52.76 kg/s.
2. **What the code does now.** D17 moves 896 most of the way: holds 10 → 3, no singular Jacobian, and the island ends
   STEADY at 52.76 kg/s (at D16 it ended held). The three holds are the first attempts at tick 2138 to 2238, the step
   where the tee first reaches its bubble point: "Newton line search stalled at residual 14.19; active-set pass=0",
   on the tee's mass row as before. The balance still first anchors a newly limited pump at the floor (its mode
   decision reads the floor alone, D16) and only then follows the supplier within four rounds; the Newton starts from
   that point. Not traced at D17: this is a reading of the build report and the unchanged residual.
3. **Proposals for the owner.**
   - **(a) Recommended: accept.** 896 holds three intervals and recovers to the right steady state; the campaign is
     992/994/1000 against the baseline's 991/993/1000. Close W2's solver work here and go on with W2's close-out.
   - (b) One more trace of 896's tick 2138 (one replay alone), then a fix in the balance's first anchor (the lesser
     value from the first round). It may fix 896; it adds a round's work for every newly limited pump.

Open items unchanged: water's floor joining the PH bisection; the vapour-locked status line.

Code guide: no change yet (the merge refresh covers W2).
