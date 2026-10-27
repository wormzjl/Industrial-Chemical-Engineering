# A wall without heat: the coupling as a relay (review)

Date 2026-10-27. Batch `2026-10-27-heat-exchanger-design`, revision 3 (owner answer Q10: no vessel heat capacity). Harness and full output: [`tools/heat-exchanger-design/`](../../tools/heat-exchanger-design/README.md) (parts 7, 7b, 7c, 7d). Offline only; no game code. Earlier studies: [HX_COUPLING_STUDY_REVIEW.md](HX_COUPLING_STUDY_REVIEW.md), [HX_FILMS_STUDY_REVIEW.md](HX_FILMS_STUDY_REVIEW.md).

**Physics.** With no wall heat and no stream inventory, the exchanger is a function of its inlets: the outlets follow an inlet step at once. Two islands can only learn of each other's changes a slice late. The remaining design question is where the heat goes that the two sides booked differently while their views differed.

**Model.** As revision 2: counterflow, 8 cells, the 450 m² exchanger, films per phase. Reference: the quasi-steady exchanger (each second, the 8-cell steady wall with that second's inlets). Cases A, B, C of the first study, the four film pairs, a condensing glide against water (400 and 2000 kW/K), liquid/liquid at 10, 25 and 200 kg/s. Hot inlet +30 K at 60 s (B: cold flow 50 → 10 kg/s). Slices 1/1, 5/5, 20/20, 5/20, 20/5 s (hot/cold).

**The relay** (part 7):

1. At its dispatch, a slice heads for the quasi-steady wall: both streams marched, the own inlet now, the other side as of the dispatch tick.
2. The account holds only the heat in transit, what one side booked and the other not (yet). It is read as of the slice start, with a lagging side's open slice at its dispatched rate, for at most one of its slices.
3. Each side gives back its share of the transit within its slice. The give-back is clamped so the side's outlet stays between the two inlet temperatures; the rest waits.
4. A side that lags more than one of its slices counts as stopped. Over those spans neither side moves heat.

**Timing finding.** Parts 2 to 6 plan each slice when it commits, from the other side's latest record. At equal slices the side that commits second then sees the other's end-of-slice state, which a real dispatch does not have, so their equal-slice results are optimistic. Part 7 plans at dispatch from the record history. Q10 retires the wall forms those parts compared.

**The give-back split** (part 7d, worst over the five slicings; controller: worst |T − target| and the last second outside 0.1 K):

| Split | Give-back shift | Settle 1 K | Controller A / C / L/L |
|---|---|---|---|
| Conductance (the cell's heat law) | 3.7 to 36 K; condensing 7.0 K | 39 to 239 s | 20.5 K 159 s / 2.9 K 99 s / 15.0 K 139 s |
| Capacity rate W | 0 to 36 K; gas/liquid at 5/20 s 18.5 K | 19 to 139 s | 18.4 K 159 s / 1.4 K 79 s / 14.0 K 119 s |
| Slice heat W Δt | 0 to 23.7 K | 19 to 139 s | 14.7 K 159 s / 0.96 K 79 s / 13.2 K 119 s |

Chosen: slice heat. Both outlets shift by the same temperature at any slices, and a condensing or boiling side takes all of it at its constant temperature. The cost: settle 44 → 59 s in some unequal rows, because the slow side takes the larger share.

**Results, slice heat** (part 7):

- Steady state exact in every row. Heat left in transit at the end: 2e-5 J or less.
- The first slice after a step shows the step itself (A, cold side: 15.5 K). The side learns of the other's step one slice late.
- Settle within 1 K: two slices at equal slices (1, 9 and 39 s at 1, 5 and 20 s); 24 to 59 s at unequal slices. Case B: 6, 34 and 139 s equal, 59 to 99 s unequal (the clamp holds the cold outlet below the hot inlet, so the transit drains over several slices).
- Give-back, one slice: 0 for condensing/liquid; 2.7 to 2.9 K for the glide at 2000 kW/K; 6.2 to 15.5 K for liquid/liquid (200 to 10 kg/s); 23.7 K for case B (clamped).
- Against revision 2 with the steel wall (worst settle 79 s liquid/liquid, 379 s gas/gas), every pair now settles within 59 s at the usual slicings.

**Holds** (part 7b, case A, hot island held 100 to 160 s and 100 to 700 s). After one slice the running side counts the held one as stopped, and the outlets show no exchange: 77.5 K off a reference in which both keep flowing (by design). After the release the give-back is 15.5 to 77.5 K, within the inlet temperatures. Tails 0.05 K or less; transit left 49 J or less.

**Controller** (part 7c, dead-beat on the quasi-steady wall). The first slice after the step shows the uncontrolled response (A 12.7 to 14.7 K, C 0.9 K, L/L 11.4 to 13.2 K): no wall damps a step, and the controller is one slice late. Within 0.1 K after 7 to 8 slices (A: 7, 39 and 159 s at 1, 5 and 20 s; unequal 69 to 139 s); end values exact.

Rejected: taking the planned give-back into the target (tried with the capacity split). It closes a loop through the transit, since each move of β books new transit at the lagging side's next commit. At 20/5 s it settles in 344 s (A) and 424 s (L/L), against 89 s for both without it.

**Conclusion.**

- Q10 deletes the wall state, its stability limit (x = G Δt / C), the slice mean, the clamped wall and the cell-local/joint choice. The profile is the quasi-steady wall.
- The coupling becomes a relay of the heat in transit, exact in energy by construction. The lag left is one slice plus one give-back slice. With the slice-heat split the give-back is one number per exchanger (the per-cell transit sums).
- Not measured: the quasi-steady wall on phase-change curves, heat inside the islands' Newton, and cost (W1, W3).

**Code guide:** no change (no code changed). **Tooling:** `tools/heat-exchanger-design/` (harness part 7).
