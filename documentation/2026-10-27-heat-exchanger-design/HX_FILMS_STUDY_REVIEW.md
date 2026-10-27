# Per-phase films and the coupling: review

Date 2026-10-27. Batch `2026-10-27-heat-exchanger-design`, revision 2 (owner answers Q2, Q3, Q4). Harness and full output: [`tools/heat-exchanger-design/`](../../tools/heat-exchanger-design/README.md) (parts 5, 5b, 5c, 6; modes `lowflow`, `hold1s`). Offline only; no game code. The first study: [HX_COUPLING_STUDY_REVIEW.md](HX_COUPLING_STUDY_REVIEW.md).

**Question.** The owner chose a film coefficient per phase (Q2), counterflow (Q3) and the steel-only wall (Q4), and asked that the films add no lag. Do the coupling's results still hold when films range from 100 to 5000 W/m²K, and where is the lag?

**Model.** As the first study, counterflow, 8 cells, the 450 m² exchanger, steel 12 kJ/K per m². Films: liquid 2000, condensing 5000, gas 100 W/m²K; 1/U = 1/h_hot + 1/h_cold. Cases: liquid/liquid (U 1000, water 50 kg/s each side), gas/liquid (U 95, gas 20 kg/s, water 10 kg/s), gas/gas (U 50, gas 20 kg/s each side), condensing/liquid (U 1430, isothermal hot side). Hot inlet +30 K at 60 s. Settle: the last time an outlet was more than 1 K off its new steady state.

**Two kinds of lag.**

- **Physics (the wall).** The reference (both sides and the wall integrated together) settles in 36 s (liquid/liquid), 66 and 138 s (gas/liquid, hot and cold), 356 s (gas/gas), 7 s (condensing/liquid). The streams hold no inventory, so the settle time scales with the wall: half the wall halves it, a quarter (3 kJ/K per m²) gives 9, 16 and 34, 90 s (part 5c).
- **The coupling (unequal slices).** With the first study's scheme (CAPPED), a fast wall and unequal slices overshoot: the long-slice side relaxes the wall as if alone while the short-slice side keeps correcting it. Condensing/liquid at 20/5 s settles in 254 s, against 7 s for the physics; liquid/liquid at 5/20 s in 119 s against 36 s.

**Three forms compared** (part 5, all eight cases, five slicings):

| Form | What the reading side adds for a lagging side | Result |
|---|---|---|
| PREDICTED | G (θ − T) on the account, over the gap | Rejected: an explicit step over a whole slice; never settles at unequal slices (tails up to 57 K) |
| INFLIGHT | the rate of the lagging side's open slice, fixed when that slice was dispatched | Same as CAPPED at equal slices; at unequal slices settles 39 to 59 s where CAPPED took 99 to 254 s; tails ≤ 0.01 K |
| JOINT | INFLIGHT, and each cell heads for the exchanger's steady wall with both streams marched (the other side's inlet and flow from its last commit) | Stable on the whole map (below); transients 1.5 to 2 times INFLIGHT's at 20 s slices |

**Stability (part 6).** Both streams finite and both films strong is the hard case. INFLIGHT fails (the outlets never settle; over the last 100 s they stay 0.6 to 82 K off) for liquid/liquid at x = (G_hot + G_cold) Δt / C_cell from 4.6 (10 kg/s) to 12.5 (200 kg/s), and for a condensing mixture with a glide (film 5000, 400 to 2000 kW/K) against water **with the full steel wall** at x 8.6 and 10.4. JOINT settles in every row of the map, the half wall included (x up to 20.7). Its only rows above 0.05 K are liquid/liquid at 10 kg/s (NTU 10.8), 20/20 s, at every wall weight (0.10 to 0.23 K): a slow tail, 0.45 K at 400 s, 0.12 K at 1000 s, 0 by 2800 s (`lowflow`), not a cycle.

**JOINT in numbers** (part 5, worst over the slicings, steel wall; reference in brackets): settle 79 s for liquid/liquid (36 s), 99 and 179 s for gas/liquid (66 and 138 s), 379 s for gas/gas (356 s), 39 s for condensing/liquid (7 s); the first study's cases 19 to 164 s (INFLIGHT 19 to 90 s). Worst transient 16 K for liquid/liquid (INFLIGHT 12 K). With a quarter wall: 59, 59, 119 and 44 s, all stable (INFLIGHT diverges there for liquid/liquid and gas/liquid). Holds (5b): 60 s holds as INFLIGHT; after a 600 s hold the 800 to 900 s window still holds up to 2.4 K of recovery (INFLIGHT 0.02 K). Energy closes to 1.2e-5 J or better in every run.

**Conclusion.**

- Keep the as-of read and the slice mean. Read a lagging side's open slice at its dispatched rate (INFLIGHT). Let each cell head for the joint steady wall (JOINT).
- JOINT needs no cadence cap and no guard.
- The physical lag is the wall's. Lightening the wall is the one lever that shortens it without moving a steady state (owner question Q10 in the plan).
- Not measured here: phase change inside the islands' Newton, solver cost, and the joint steady-wall solve on phase-change curves (W1 and W3 of the plan).

**Code guide:** no change (no code changed). **Tooling:** `tools/heat-exchanger-design/` (harness parts 5 to 6, modes `lowflow`, `hold1s`).
