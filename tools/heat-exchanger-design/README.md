# heat-exchanger-design

Purpose: offline study harnesses behind the heat exchanger design (batch [`2026-10-27-heat-exchanger-design`](../../documentation/2026-10-27-heat-exchanger-design/README.md)). They decide:

- how the two sides of an exchanger couple when they sit in different fluid islands;
- how many wall cells the heat law needs;
- how a TEMA E shell with two tube passes compares with counterflow;
- how a film coefficient per phase changes the lag and the coupling's stability;
- how the sides couple when the wall holds no heat (a relay of the heat in transit);
- how a side reads its partner on the game's clock (dispatch after the slice end, job latency, adaptive cadence).

No game code, no Gradle, except `review-probe/`: the owner-test probe of the review world, detached from the tracked paths at the batch's tooling cleanup (`f20ceefa`). Its own [README](review-probe/README.md) says how to re-attach and run it.

## How to run

Java 21 or later, from this folder:

```bash
java HxCouplingSim.java > results-coupling.txt
java HxCouplingSim.java hold1s > results-hold-1s.txt
java HxCouplingSim.java lowflow > results-low-flow.txt
java HxCouplingSim.java timing > results-timing.txt
java OneTwoShell.java > results-one-two-shell.txt
```

The default run takes about 4 s, `timing` about 5 s, the other modes about a second each. The stored `results-*.txt` came from these exact sources on 2026-10-27, on JDK 21.0.11 and the same bytes on Zulu 25.0.4.

## Entry points

`HxCouplingSim.java` models a counterflow exchanger of N wall cells between two zero-inventory streams. Each stream relaxes towards each cell's wall, `out = Tw + (in - Tw) exp(-g/W)`, with `g` a side's film conductance `h A / N`; equal films are `2 UA / N` each.

- **Part 1**: the N-cell model's steady effectiveness against the exact counterflow formula (N = 1 to 16).
- **Part 2**: the two streams as two islands advancing in slices (1, 5 and 20 s; equal and unequal), against a fine-step coupled reference (RK4, 0.01 s).
  - Schemes:
    - `FROZEN`: the wall account at the slice start.
    - `AVERAGED`: the predicted slice mean.
    - `ASOF`: the account read as of the slice start, then the slice mean.
    - `CAPPED`: `ASOF` with a lagging side's heat extrapolated for at most one of its slices.
  - Cases: the 1 m × 6 m exchanger (450 m², U 500 W/m²K) with water 50 kg/s each side, a 5× cold-flow cut, and a stiff 1.5 MW/K pair. Walls: steel only (12 kJ/K per m²), and steel plus water holdup (48 kJ/K per m²).
- **Part 3**: a cold-outlet temperature controller: a bypass share set at each slice start, dead-beat and half-damped.
- **Parts 4 and 4b**: the hot island held 60 s and 600 s, then catching up.
- **Part 5** (revision 2): a film per phase (liquid 2000, condensing 5000, gas 100 W/m²K).
  - Cases: liquid/liquid, gas/liquid, gas/gas and condensing/liquid, plus the four part 2 cases.
  - Schemes: `CAPPED`, `PREDICTED` (a lagging side's heat as G(θ − T) over the gap; rejected), `INFLIGHT` (at the rate of its open slice, fixed at dispatch) and `JOINT` (`INFLIGHT`, with each cell heading for the exchanger's steady wall with both streams marched).
  - Reported: max error, tail, settle to 1 K and to 0.1 K, energy.
  - 5b: the holds again. 5c: the wall's heat capacity at 1, ½ and ¼ against the physical lag, and both forms at a quarter wall.
- **Part 6**: a stability map, `INFLIGHT` against `JOINT`. Liquid/liquid at 10 to 200 kg/s with the wall at 1 to ½, and a condensing mixture with a glide (film 5000, 100 to 2000 kW/K) against water, at 20/20, 5/20 and 20/5 s.
- **Part 7** (revision 3, owner Q10: no wall heat): the relay. Each slice, planned at its dispatch from the record history (`Rec`, `Plan`, `RelaySide`), heads for the quasi-steady wall; the account holds only the heat in transit, which each side gives back within its slice, clamped between the inlet temperatures; a side lagging more than one slice counts as stopped (no heat for either side over those spans). Against the quasi-steady exchanger (`truthQuasi`); the part 2, 5 and 6 cases.
  - Reported: max error, tail, settle to 1 K, the largest give-back shift, the heat in transit at the end.
  - 7b: the holds. 7c: the dead-beat controller. 7d: three give-back splits (`Split`: conductance, capacity rate, slice heat; `SPLIT` picks the one parts 7 to 7c run).
  - Parts 2 to 6 plan a slice at its commit, from the other side's latest record, so their equal-slice results are optimistic. Part 7 corrects that.
- **Part 8, `timing`** (argument; revision 4, study W3a): part 7's relay on the game's clock (`GameSide`, `game`). A slice is offered once online time reaches its end (20 ticks a second) and dispatched then. Its job commits 0 to 40 ticks later, capped at half the cadence. One job per side is in flight. Cadences are fixed or wobble with load (`WOBBLE`). A side is stopped only while held (it drops its job).
  - Forms (`Form`): A reads the partner as of the slice start, extrapolating a lagging partner at its last committed slice's rate. B cuts the slice at the partner's commits inside it. C waits until the partner has committed through the slice start.
  - Each row runs at latency 0, at 40 and at five seeds; it reports the spread between seeds (the dependence on job times) and the debt (online minus committed time).
  - 8b: the holds to 1500 s, with recovery in online time; `LAG_STOPS` compares a stopped-by-lag rule with the plan's rule. 8c: the controller. 8d: a summary per form.
- **`hold1s`** (argument): Part 4b's 1 s `CAPPED` run, both outlets every 10 s from 680 s. The harness clock ticks once a simulated second, and a held island catches up 8 slices a tick. At 1 s slices the 600 s backlog therefore takes about 86 s, and the 10 K tail of that row is this recovery (0.4 K at 850 s, 0.01 K at 900 s).
- **`lowflow`** (argument): liquid/liquid at 10 kg/s (NTU 10.8), 20/20 s slices, to 3000 s. JOINT's only part 6 rows above 0.05 K are a slow tail, not a cycle.

`OneTwoShell.java` models a 1-2 shell (one shell pass, two tube passes, as `ProcessUnit.exchanger` draws it) as M axial positions × 2 passes of wall cells, against the exact TEMA E formula and against counterflow.

## History

- 2026-10-27: written for the design batch; results summarised in `documentation/2026-10-27-heat-exchanger-design/HX_COUPLING_STUDY_REVIEW.md`. The one-off probe that explained Part 4b's 1 s tail became the `hold1s` mode (default output unchanged, byte for byte).
- 2026-10-27, revision 2 (owner answers): per-side films (`Case.uaHot`, `uaCold`; parts 1 to 4 byte-identical), parts 5 to 6, the `PREDICTED`, `INFLIGHT` and `JOINT` schemes, and the `lowflow` mode (from a one-off probe). Summary: `documentation/2026-10-27-heat-exchanger-design/HX_FILMS_STUDY_REVIEW.md`.
- 2026-10-27, revision 3 (owner Q10 to Q13): part 7 (the relay, planned at dispatch, three give-back splits, holds, controller; the controller's give-back offset was measured and removed). Parts 1 to 6 and both modes byte-identical. Summary: `documentation/2026-10-27-heat-exchanger-design/HX_ZERO_WALL_STUDY_REVIEW.md`.
- 2026-10-27, revision 4 (study W3a): part 8 and the `timing` mode. Parts 1 to 7 and both older modes are byte-identical. Summary: `documentation/2026-10-27-heat-exchanger-design/HX_TIMING_STUDY_REVIEW.md`.
- 2026-10-28: `review-probe/`, the owner-test probe of the review world (`OWNER_TEST_REVIEW.md`), detached from `build.gradle` and the fluid GameTest sources in `f20ceefa`.
