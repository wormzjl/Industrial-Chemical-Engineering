# Time factor build: W1-W6 (2026-11-11)

Plan: [TIME_SCALE_PLAN.md](TIME_SCALE_PLAN.md). Commits `c7a0e4ddd` (W1), `6a3642338` (W2), `ad0c21634` (W3), `a64fcc57d` (W4), `93985cfa5` (W5); W6 below. Reports in this worktree's `tmp/time-scale/` (git-ignored); tool `tools/minecraft-time-delta/` (`factor.sh`, `worlds/`).

## 1. Factor 1 is the engine's old behaviour

- W1: 218 tests in 22 classes pass, chain-100 exact (largest deviation 0), the 33 junction lines equal the 0.26.0 lane (the ms and allocation counters aside). W2: chain-100 exact, junction lines equal.
- Quick check at factor 1 (seeds 1-200) against the evaluation's `b1`: every seed ends at the same tick with the same holds, except bundled 6, a wall-deadline hold in `b1` that runs clean now (timing).
- One owner-decided change at factor 1: wear lives are 8 h and 40 min of process time (T3), four times the old online lives.

## 2. Quick checks, seeds 1-200, 8 workers, alone (clean of 200)

| Factor | bundled | cold | defaults | Held seeds |
|---|---|---|---|---|
| 1 | 199 | 197 | 200 | bundled 200 (E5); cold 85 E1, 101 E6, 118 F |
| 4 | 197 | 196 | 200 | bundled 47 F, 118 F, 145 E9; cold 85 E1, 101 E5, 118 F, 181 E2 |
| 72 | 197 | 197 | 200 | bundled 15, 47, 118 (F); cold 6 F, 118 F, 72 B |

At 72 the built code holds the same seeds as the evaluation's "holdup only" form `h72` (OPTION_A_REVIEW.md).

## 3. Campaign at factor 4 (1000 seeds, all distributions, 8 workers, alone) against the 0.26.0 lane

| Distribution | clean (base, head) | Newton iterations (base, head) | New holds | Fixed holds |
|---|---|---|---|---|
| cold-random | 991, 991 | 1 609 077, 2 002 081 | 431 B, 589 B, 181 E2 | 988 E1, 206 E6, 446 E6 |
| bundled-random | 990, 990 | 878 057, 860 192 | 301 B, 145 E9, 47 F, 118 F, 678 F | 430 E10, 200 E5, 696 E5, 698 E8, 514 E9 |
| bundled-defaults | 1000, 1000 | 64 037, 62 242 | none | none |

- Moved: cold 101 E6 to E5, bundled 579 E10 to F. The three new budget-class holds (47, 118, 678), replayed once alone in one run: all clean (47 takes 36 s alone).
- The two smallest new non-budget holds, from the campaign's lines (not replayed): bundled 145 "Invalid inventory volume/energy/level height" (E9, as in `b72`); cold 181 "Singular Newton Jacobian" in a phase/device active-set cycle, 75 kg of crystals. The B holds belong to the remnant-floor batch. No hold class is new to the fuzz.

## 4. Headless worlds, 1 h of process time (`tools/minecraft-time-delta/worlds/`, JUnit over the coordinator)

- **Running column** (`ColumnRunTest`'s rig, a 0.5 mol/s nitrogen feed, its product into a closed tank). No hold at 4 or 72. Every component and the energy close (`closes`).
  - At 4: 316 mol taken; 240 mol in the outlet account (its buffer, 24 cycles = 8 min of production); 76 mol in the tank, which then stands at the port's 2.5 bar and limits the column ("LIMITED: outlet", the rig's closed tank). At 72: 720 mol taken (0.5 mol/s once the 6-cycle start had passed, 30 s of play); nothing given. The outlet buffer is 24 cycles = 2.4 h of production at 72, so the first 2.4 h of process time give nothing downstream (T6 kept the cycle counts).
- **Heat pair in two islands** (`ExchangerCouplingTest`'s water pair, cadences 100/100 and 20/400, certificates off and on, at 1, 4 and 72).
  - Energy closes in every case (`assertLedger`), no hold. The outlets are at the countercurrent steady state within 0.05 K in 11 of 12 cases; the exception is 72 at 20/400 with certificates: hot outlet 324.5 K against 340.1 K after 1 h.
  - Traced: the hot island is certified before the cold island's first 400-tick slice, which is 24 min of process time at 72. The relay gives back the transit (3.4e9 J, one cold slice of duty) as an oscillation damped about 0.27 per cold slice. It is within 0.1 K after about 140 s of play (2.8 h of process time). At factor 1 the same lag is 20 s of process time.
- **Level loop on a drum** (GameTest `LevelLoopGameTests` at the config's 4; at 72 `tools/minecraft-time-delta/level-loop/run.sh 72`, alone on the review server with its own config): a lying 1.5 m by 4 m drum (7.95 m3) holding two cycles of its feed (198 kg/s at 4, 11.0 kg/s at 72), LIC-1 on its outlet valve on the cadence, FIC-1 on the feed; from 25 % to the 50 % setpoint, the feed +50 % after 15 of 30 cycles. Passes at both: no hold; level 24.8 to 62.5 % at 4 and 25.1 to 65.1 % at 72, back at 50 % about 5 cycles after the step; moles and energy close to round-off over the run (3e-10 of 1.6e7 mol, 1e-6 J of 7e11 J). Two cycles suffice. The suite case takes about 1 s (the server run 43 s, 89 tests).
- Found while building it (the test works round them; none fixed): (1) the full feed into an empty drum, or the feed raised in one step, holds the island at 4 (line search stalled, residual 376 to 567, active-set pass 0; 3 of 12 runs); at factor 1 (`run.sh 1`, probe `LevelLoopStepProbe`, one run) all four un-ramped cases ran clean: empty and one step (a tenth to the full feed at 25 %), each at the rule feed (792 kg/s) and at the factor-4 feed (198 kg/s). One clean run against 3 of 12 does not clear factor 1; the empty cases were watched only 2 cycles, through the fill to 25 %. At 1 the rule feed is more than the 0.25 m outlet passes at 25 %: the level stands at 58 %, the valve 87 % open, and the suite case never starts (no hold). (2) A vertical drum's `level_body` mount is blocked by its own vapour nozzle and shell. (3) A liquid-full drum cannot drain: the vent void gives no gas back (4 runs at 4 and 72). (4) Moles the setup leaves outside the island's book: 9.7 (4, every run), 65 (72); at 1, 0 in the suite setup and in 3 probe setups, 7.2 in one (the 20 kg/s fill). Not proportional to the feed; it grows with the factor and varies run to run at 1. A topology replacement keeps each vessel's stock exactly (`IslandCoordinator.topology`, `sameContents`), so these are likely stock that the setup's edits construct in, or move into, the drum's island, which the book counts without a boundary (not traced further).

## 5. Fluid GameTests at the default factor 4

`runFluidGameTestServer` with the config's default `timeScale = 4`, fresh run id: all 88 pass (27.6 s). The first run (`timescale4`) crashed in `FluidCheckpointGameTests`: its restarted coordinator was built without the factor ("An island model on time factor 4 in a coordinator on 1"); fixed in the test (`.withTime(options.time())`). Production builds its coordinator with the factor.

## 6. Owner options (decided 2026-11-11, "Keep both": 1a and 2a)

1. The column's outlet buffer at high factors (T6). (a) Keep the cycle counts: 2.4 h of production is held before the first give at 72. (b) Cap the buffer in process time, for example 10 min at any factor: the same play time as today at 4, a shorter start at 72.
2. The coupling lag between heat sides on unequal cadences. It is one slice of the slower side, and it grows with the factor. (a) Accept: it settles in about 6 slower slices. (b) Do not certify a heat side before its partner has booked its first exchange: this removes the startup swing, and the steady replay is unchanged.
3. `OpenVessel.RELEASE_TIME` (1 s) is not scaled. It is shorter than any step at every factor, so this has no effect at game level.
