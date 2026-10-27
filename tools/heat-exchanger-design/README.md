# heat-exchanger-design

Purpose: offline study harnesses behind the heat exchanger design (batch [`2026-10-27-heat-exchanger-design`](../../documentation/2026-10-27-heat-exchanger-design/README.md)). They decide how the two sides of an exchanger couple when they sit in different fluid islands, how many wall cells the heat law needs, and how a TEMA E shell with two tube passes compares with counterflow. No game code, no Gradle.

## How to run

Java 21 or later, from this folder:

```bash
java HxCouplingSim.java > results-coupling.txt
java HxCouplingSim.java hold1s > results-hold-1s.txt
java OneTwoShell.java > results-one-two-shell.txt
```

Each runs in about a second. The stored `results-*.txt` came from these exact sources on 2026-10-27 (JDK 21.0.11; the same bytes on Zulu 25.0.4).

## Entry points

- `HxCouplingSim.java`: a counterflow exchanger of N wall cells between two zero-inventory streams (each stream relaxes towards each cell's wall, `out = Tw + (in - Tw) exp(-g/W)`, `g = 2 UA / N` per side).
  - Part 1: the N-cell model's steady effectiveness against the exact counterflow formula (N = 1 to 16).
  - Part 2: the two streams as two islands advancing in slices (1, 5, 20 s; equal and unequal), against a fine-step coupled reference (RK4, 0.01 s). Schemes: `FROZEN` (wall account at the slice start), `AVERAGED` (predicted slice mean), `ASOF` (account read as of the slice start, then the slice mean), `CAPPED` (`ASOF` with a lagging side's heat extrapolated for at most one of its slices). Cases: the 1 m x 6 m exchanger (450 m², U 500 W/m²K) with water 50 kg/s each side, a 5x cold-flow cut, and a stiff 1.5 MW/K pair, steel-only wall (12 kJ/K per m²) and steel plus water holdup (48 kJ/K per m²).
  - Part 3: a cold-outlet temperature controller (a bypass share set at each slice start, dead-beat and half-damped).
  - Parts 4 and 4b: the hot island held 60 s and 600 s, then catching up.
  - `hold1s` (argument): Part 4b's 1 s `CAPPED` run, both outlets every 10 s from 680 s. The harness clock ticks once a simulated second and a held island catches up 8 slices a tick, so at 1 s slices the 600 s backlog takes about 86 s; the 10 K tail of that row in `results-coupling.txt` is this recovery (0.4 K at 850 s, 0.01 K at 900 s).
- `OneTwoShell.java`: a 1-2 shell (one shell pass, two tube passes, as `ProcessUnit.exchanger` draws it) as M axial positions x 2 passes of wall cells, against the exact TEMA E formula and against counterflow.

## History

- 2026-10-27: written for the design batch; results summarised in `documentation/2026-10-27-heat-exchanger-design/HX_COUPLING_STUDY_REVIEW.md`. The one-off probe that explained Part 4b's 1 s tail became the `hold1s` mode (default output unchanged, byte for byte).
