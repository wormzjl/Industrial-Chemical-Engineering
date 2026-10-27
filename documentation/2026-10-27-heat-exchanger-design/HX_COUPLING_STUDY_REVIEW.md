# Exchanger coupling study: review

Date 2026-10-27. Batch `2026-10-27-heat-exchanger-design`. Harnesses and full output: [`tools/heat-exchanger-design/`](../../tools/heat-exchanger-design/README.md). Offline only; no game code.

Revision 2 (the same day) supersedes two conclusions below:

- The owner chose counterflow over the 1-2 arrangement (Q3).
- With films per phase, the coupling reads a lagging side's open slice and heads for the joint steady wall. The as-of read with the slice mean alone fails at strong films. See [HX_FILMS_STUDY_REVIEW.md](HX_FILMS_STUDY_REVIEW.md).

**Question.** The two sides of an exchanger usually sit in different islands, which advance in slices of their own cadence (1 to 20 s). Can they exchange heat through the exchanger's wall, with exact energy, without merging the islands or a joint Newton?

**Model.** N wall cells between two zero-inventory streams; each stream relaxes towards each cell's wall over `g = 2 UA / N` (equal films). The 1 m x 6 m exchanger: 450 m², U 500 W/m²K, UA 225 kW/K. Wall: steel only, 12 kJ/K per m² (C/(4 UA) = 6 s), or steel plus water holdup, 48 kJ/K per m² (24 s). Each slice keeps one wall profile and books its heat into the wall account at its end. Reference: both streams and the wall integrated together (RK4, 0.01 s).

**Cell count** (steady state, N-cell counterflow vs exact): N = 8 within 0.9 % up to NTU 5 at Cr 0.5 and 2.1 % at NTU 5, Cr 1; N = 4 within 7.3 %. The drawn exchanger is a 1-2 shell (two tube passes): 4 positions x 2 passes (8 cells) match the exact TEMA E formula within 2.3 % (M = 8: 0.7 %). Counterflow overstates that shell by 8 % at NTU 1, 20 % at NTU 2 and 42 % at NTU 5 (Cr 1).

**Coupling schemes**, hot inlet 150 to 180 °C, water 50 kg/s each side, steel-only wall (max |outlet error| from the step on / over the last 100 s, K; hot, cold):

| Slices (hot/cold) | Frozen wall (account at commit) | Slice mean | As-of read + slice mean |
|---|---|---|---|
| 5/5 s | 9.1, 13.1 / 8.6, 8.6 | 6.3, 11.9 / 6.1, 6.1 | 1.3, 2.6 / 0.00, 0.00 |
| 20/20 s | 51.8, 66.5 / 51.7, 51.7 | 17.3, 29.5 / 16.5, 16.5 | 4.7, 6.4 / 0.00, 0.00 |
| 5/20 s | 52.1, 36.0 / 52.1, 22.7 | 33.7, 27.0 / 33.7, 13.3 | 2.3, 7.2 / 0.00, 0.00 |
| 20/5 s | 26.0, 55.7 / 22.7, 52.1 | 15.5, 34.9 / 13.3, 33.7 | 4.6, 10.0 / 0.00, 0.00 |

- The frozen wall read in simulated time (the first run) diverged at 20 s slices (x = (G_h + G_c) Δt / C_cell = 2.9 to 3.3 > 2, explicit Euler); read at commit it stays bounded but settles 1.6 to 52 K off. The slice mean alone removes the divergence, not the offset. Only the as-of read with the slice mean reaches the exact 8-cell steady state for every slicing.
- Same result for a 5x cold-flow cut (worst transient 20 K at 20 s slices, then 0.00), a stiff 1.5 MW/K pair (worst 3.2 K) and the wall with water holdup (worst 1.5 K).
- Energy: the account closes to 1e-4 J or better in every run (exact by construction).
- The account's own temperature may stand outside the two inlet temperatures while heat is in transit (up to 15 K here); the profile a side reads is clamped to them.

**Holds.** Hot island held 60 s, then catching up (8 slices a tick): as-of read, 0.4 to 10 K worst, 0.00 K tail. Held 600 s: extrapolating the held side's heat without a limit drains the account by about 1750 K worth (heat the held side has not delivered); with the extrapolation capped at one of its slices the account stays within 8 to 152 K, the running side sees the held one as stopped after one slice (its outlet then departs up to 78 K from a reference where both keep flowing, by design), both sides afterwards agree the exchanger moved little heat during the hold, and the outlets return to the steady state a few wall time constants after the catch-up (0.02 to 0.23 K tail at 5 and 20 s slices). At 1 s slices the harness catches up only 7 s a tick, so the 600 s backlog takes about 86 s and that row's tail window (800 to 900 s) still holds the recovery: 10 K at 800 s, 0.4 K at 850 s, 0.01 K at 900 s (`hold1s`).

**Control.** Cold outlet at 90 °C by a bypass share decided at each cold slice start (as-of read), hot inlet +30 K: dead-beat worst 0.3 K and within 0.1 K after 24 s (5 s slices), 1.4 K and 79 s (20 s), 1.4 K and 99 s (5/20); half-damped 3.3 K and 124 s, 7.8 K and 259 s. Stiff pair at 40 °C: dead-beat worst 0.25 K.

**Conclusion.** Couple the sides through a wall account, read as of each slice's start tick, profile = the predicted slice mean, clamped to the inlet temperatures, a lagging side extrapolated for at most one of its slices. Model the drawn 1-2 arrangement with 4 x 2 cells. Use a dead-beat controller between slices. Not measured here: phase change in the islands' Newton, solver cost (W1 and W4 of the plan measure them).

**Code guide:** no change (no code changed). **Tooling:** `tools/heat-exchanger-design/`.
