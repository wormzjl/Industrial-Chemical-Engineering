# Time factor: implementation plan

Owner, 2026-11-11: Minecraft time as the simulation step, the 5 s calculation interval kept in real time; "Holdup only" (the junction holdup scales with the factor, no tolerance change); "Make the time scale configurable, 4x as default, 72x max; equipment wear should be in line with scale, adjust the default rate if too fast; others as recommended". Basis: [TIME_SCALE_REVIEW.md](TIME_SCALE_REVIEW.md), [HOLD_STUDY_REVIEW.md](HOLD_STUDY_REVIEW.md), [OPTION_A_REVIEW.md](OPTION_A_REVIEW.md). Code: `ff6f6892f` (0.26.0). Nothing is implemented yet.

## 1. What changes, in one rule

Process time = online ticks x factor / 20. The factor is one server setting, `fluid.timeScale`, 1 to 72, default 4, read at server start. Everything scheduled stays in ticks (slices, buckets, fences, cadence, the 2 s wall budget). Everything physical is process seconds. The engine's own default (tests, harnesses, no config) stays 1, where every result is bit-identical to today, so the recorded references stand; the harnesses take the factor as a switch.

At the default 4: a slice is 20 s of process time, the solver's steps 4 s to 80 s, the junction buffer 0.2 s, a control sample 20 s, the column's accounts 2 min of feed and 8 min of products.

## 2. Work packages

| WP | What | Where | Proof |
|---|---|---|---|
| W1 | One conversion, `SimulationTime.seconds(ticks)` and `.factor()`, in place of the 42 `/20.0` and `TICK_SECONDS` sites; the setting; the factor in the model revision key (`time=`, beside `max=`) so a changed factor rebuilds state from the facts | `IslandClock`, `IslandCertificate`, `IslandCoordinator`, `ColumnOperation`, `Stills`, `FiredHeaters`, `PrimeMovers`, `ShaftTrains`, `PumpHealth`, `ElectricStores`, `ElectricStore`, `ControlLaw`, `IChemE`, `FluidThermodynamics.revision` | factor 1: chain-100 exact, the 33 junction lines, the quick check equal to the 0.26.0 lane; a source test that fails on a new `/20.0` in `runtime/` |
| W2 | Solver time constants times the factor: the interval's first step (1 s), its step cap (20 s), `HOLDUP_TAU` (0.05 s). No tolerance change | `PassiveIntervalSolver.Settings`, `PassiveStepSolver`, `PassiveNetwork.sizeJunctionHoldups` | quick check at 4 (not yet measured) and at 72 (measured: bundled 197, cold 197, defaults 200) |
| W3 | Windows that are counts of cycles, stated as cycles: the column's inlet aim (6), outlet buffer (24) and condenser hold (12); the still's hold (2) and refill (6); the electric stores' buffers (in slices) | `ColumnOperation`, `Stills`, `ElectricStore` | factor 1: `columnGates`, the still and store tests unchanged; factor 4 and 72: the column review world starts, runs and stops |
| W4 | Play-time interfaces by section 3: FE at `joulesPerFE` x factor; wear on process time with the new default lives; fuel on process time (no change beyond W1) | `ElectricStores`, `Wear`, `PumpHealth`, `IChemE` config | one test per choice at factors 4 and 72 |
| W5 | Screens and status lines say which time they show: rates stay per process hour; "since", "next check at" and solve ages in play seconds; a pump's remaining life in play time | `IslandCoordinator` status, `OperationView`, lang `en_us.json` | software renders; `LanguageFilesTest` at the translation commit |
| W6 | Measurement: one campaign at 4 (1000 seeds, 8 workers), the quick check at 72 on the final code; the running column, a heat pair in two islands and a level loop on a drum of two cycles, each for 1 h of process time at 4 and at 72; the fluid GameTests at the default 4 | `tools/minecraft-time-delta/`, the fluid GameTests | the campaign against the 0.26.0 lane by class; the three worlds hold nothing and conserve |
| W7 | The rule text and the guide | `AGENTS.md` ("Simulated time is online ticks times `fluid.timeScale` at a nominal 20 TPS"), code guide `fluid-runtime.md`, `fluid-science.md`, `column-operation.md` | - |

Order: W1, W2, then the quick checks; W3; W4; W5; W6 last, on the final code. Each WP is its own commit; W1 alone is behaviour-free.

## 3. Owner decisions (2026-11-11)

| # | Question | Decision |
|---|---|---|
| T1 | Factor | Configurable, 1 to 72, default 4. An existing world keeps its contents and runs 4 times faster |
| T2 | FE | The play-time rate is kept: a joule costs 1 / (`joulesPerFE` x factor) FE (a 1 MW motor stays 20 000 FE/tick; 10 J/FE at 4, 180 J/FE at 72) |
| T3 | Pump wear | Process time, in line with the factor. Unadjusted, a dry pump would fail in 2.5 min of play at 4 (30 publications): too fast to reach it. New default lives 8 h (cavitation peak) and 40 min (dry) of process time: 2 h and 10 min of play at 4, as today; 27 min, 2.2 min at 12; 6.7 min, 33 s at 72. New keys `machines.wear.cavitationProcessHours`, `dryProcessMinutes`, so a stored 2 h / 10 min does not carry over as a 4 times shorter life |
| T4 | Fuel | Process time, as physics: a line's fuel and a solid item's heat are conserved; a still burns 4 times the items per play minute at 4 |
| T5 | Level control | Stays on the control cadence (a 20 s sample at 4, 6 min at 72); the simulator's sizing states that a controlled drum holds two cycles. Inside the solve only as its own batch, if W6's drum overflows |
| T6 | The column's accounts | The cycle counts are kept (the same play-time response as today); revisited on W6's column run |

## 4. Saves and formats

- The factor is a setting, not a fact: no facts-schema change, no corpus image. Contents, the ledger, the online tick and a pump's health stay.
- State follows the revision key: a world opened at another factor rebuilds its islands from the facts once (junction buffers are re-sized by the mint's rule, D46; certificates and solved intervals are dropped).
- No wire protocol changes; a status line's wording does (W5).

## 5. Known holds at 72 (not part of this plan)

Bundled 15 (an equation-gate miss on a fugacity row of a two-phase crude junction), 47 (liquid drying out in a gas tank), cold 72 (non-finite fugacity derivative at 111 K), cold 6 and 183 (new under the larger buffer, not traced). Each is one world of 200. Nothing is measured at 4 yet; W2's quick check is the first number.

## 6. Not in scope

A choke law, tolerance changes (option A is dropped; 1e-8 / 1e-7 stays an option in OPTION_A_REVIEW.md), the level loop inside the solve, the level's day clock as the time source, merge preparation (on the owner's word).
