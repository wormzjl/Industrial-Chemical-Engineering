# W3a timing study: how an exchanger side reads its partner (review)

Batch 2026-10-27-heat-exchanger-design, plan section 5.2, 2026-10-27. Harness part 8: `tools/heat-exchanger-design/HxCouplingSim.java timing` and its stored `results-timing.txt`.

## Expected behaviour

- **Physics.** The wall holds no heat (Q10), so the outlets follow the inlets at once; the quasi-steady exchanger is the reference. The relay should reach it exactly, with energy exact, whatever the job times.
- **Game timing.** A slice is offered once online time reaches its end, and dispatched then. Its job commits 0 to 40 ticks later, capped at half the cadence so that a side behind can catch up. Cadences are 1 to 20 s, fixed or with a load wobble (×1.25, ×0.9). A side counts as stopped only while it is held.
- **Coverage.** Part 7's 12 cases × 6 slicings (one adaptive) × 3 forms. Each runs at latency 0, at 40 and at five random seeds: 1512 runs. Part 7's holds run to 1500 s, and part 7's controller.

## What each form did

| | A, as of t0 | B, history inside the slice | C, a fence |
|---|---|---|---|
| Settle to 1 K, mean / worst | 69 / 259 s | 45 / 239 s | 34 / 79 s |
| Max error / tail | 38.4 / 0.004 K | 41.2 / 0.003 K | 38.4 / 0.000 K |
| Outlet spread from job times alone | 7.2 K | 1.0 K | 0 |
| Debt (online minus committed), worst | 22 s | 22 s | 24 s |
| Recovered after a 60 s hold (online) | 20 to 202 s | 20 to 202 s | 6 to 42 s |
| Recovered after a 600 s hold | 68 to 600 s | 68 to 600 s | 60 to 300 s |
| Controller, last outside 0.1 K, worst / mean | 359 / 109 s | 319 / 102 s | 139 / 64 s |
| Heat in transit at the end | up to 3.8e4 J | up to 3.8e4 J | up to 2e-5 J |

1. **Equal fixed slices.** All three forms give part 7's numbers (1, 9 and 39 s). The forms differ only at unequal and adaptive slices.
2. **A extrapolates from stale data.** It extrapolates a lagging partner at the rate of the partner's last committed slice, which is a whole partner slice old. Part 7 used the open slice's rate, which the game does not have. So unequal slices settle 3 to 4 times slower than part 7: case A at 5/20 s takes 159 s against 44 and 59 s.
3. **B helps only the slow side.** The slow side sees the fast partner's commits inside its slice: case A's cold side at 5/20 s settles in 4 s. The fast side gains nothing. B's piecewise walls overshoot the controller: 2.6 K against 1.0 K (case C at 5/20 s).
4. **A and B depend on job times.** With an adaptive cadence, the job times decide whether a partner has committed through t0 by the dispatch. A's outlets then differ by up to 7.2 K between runs that differ only in job times; B's by up to 1.0 K. C gives the same bits in every run: it reads only records at or before t0, and those are fixed once the partner is past t0.
5. **C reproduces part 7 exactly at fixed slices** (44 and 59 s at 5/20 s). Behind the fence the as-of read is exact and needs no extrapolation.
6. **Holds.** After a hold, the partner is behind by the hold's length:
   - under A and B the running side goes on without heat, its give-back clamping its outlet to its inlet, while the partner chases a moving target;
   - under C the running side waits, so the partner catches up to a fixed target, 1.1 to 9 times sooner;
   - the cost: the waiting side's readings hold their last values meanwhile (up to 300 s of debt after a 600 s hold).
7. **A stopped-by-lag rule is not needed.** I measured a rule that counts a partner as stopped while it lags by more than one of its slices plus the 2 s budget. It never fired in normal running (0 in 1512 runs), and it did not change the recovery (601 against 600 s, 542 against 562 s).
8. **A model artefact.** A side whose jobs take as long as its cadence only keeps pace, and never works off a hold's debt. This holds in every form. In the game the clock's adaptive cadence removes it; the harness caps latency at half the cadence.

## Proposal (owner Q16)

**Form C, the coupling fence.**

- It is deterministic and energy-exact.
- It settles fastest, controls fastest and recovers fastest from holds.
- It is the simplest: no extrapolation, no last-rate record, no stopped-by-lag rule. A command carries the partner's record at t0 and the heat in transit as of t0.
- **In the game.** `IslandClock.nextSlice` already blocks on a causal fence at the committed tick. A side whose partner's committed tick is behind its own gets that fence, and the partner's commit wakes it. A certified partner is materialised to the tick (`IslandClock.rest`).
- **No deadlock.** The side furthest behind can always go, in any graph of exchangers. A feed/effluent loop inside one island is its own partner.
- **Cost.**
  - A fast side runs up to one partner cadence behind online time: 19 s against 7 s for a 5 s side with a 20 s partner.
  - After a partner's hold, the waiting side's readings hold until the partner has caught up.
- **Alternative: B,** if screens must never wait. It settles about 30 % slower on average (worst 239 s), and its outlets vary by up to 1 K with job times.
