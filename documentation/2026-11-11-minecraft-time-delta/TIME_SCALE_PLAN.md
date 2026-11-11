# Time factor: implementation plan

Owner, 2026-11-11: Minecraft time as the simulation step, the 5 s calculation interval kept in real time; "Holdup only" (the junction holdup scales with the factor, no tolerance change). Basis: [TIME_SCALE_REVIEW.md](TIME_SCALE_REVIEW.md), [HOLD_STUDY_REVIEW.md](HOLD_STUDY_REVIEW.md), [OPTION_A_REVIEW.md](OPTION_A_REVIEW.md). Code: `ff6f6892f` (0.26.0). Nothing is implemented yet.

## 1. What changes, in one rule

Process time = online ticks x factor / 20. The factor is one server setting, `fluid.timeScale`, 1 to 72, read at server start. Everything scheduled stays in ticks (slices, buckets, fences, cadence, the 2 s wall budget). Everything physical is process seconds. At factor 1 every result is bit-identical to today.

## 2. Work packages

| WP | What | Where | Proof |
|---|---|---|---|
| W1 | One conversion, `SimulationTime.seconds(ticks)` and `.factor()`, in place of the 42 `/20.0` and `TICK_SECONDS` sites; the setting; the factor in the model revision key (`time=`, beside `max=`) so a changed factor rebuilds state from the facts | `IslandClock`, `IslandCertificate`, `IslandCoordinator`, `ColumnOperation`, `Stills`, `FiredHeaters`, `PrimeMovers`, `ShaftTrains`, `PumpHealth`, `ElectricStores`, `ElectricStore`, `ControlLaw`, `IChemE`, `FluidThermodynamics.revision` | factor 1: chain-100 exact, the 33 junction lines, the quick check equal to the 0.26.0 lane; a source test that fails on a new `/20.0` in `runtime/` |
| W2 | Solver time constants times the factor: the interval's first step (1 s), its step cap (20 s), `HOLDUP_TAU` (0.05 s). No tolerance change | `PassiveIntervalSolver.Settings`, `PassiveStepSolver`, `PassiveNetwork.sizeJunctionHoldups` | quick check at 72: bundled 197, cold 197, defaults 200 (measured, form "holdup 3.6 s") |
| W3 | Windows that are counts of cycles, stated as cycles: the column's inlet aim (6), outlet buffer (24) and condenser hold (12); the still's hold (2) and refill (6); the electric stores' buffers (in slices) | `ColumnOperation`, `Stills`, `ElectricStore` | factor 1: `columnGates`, the still and store tests unchanged; factor 72: the column review world starts, runs and stops |
| W4 | Play-time interfaces, by the owner's choices in section 3: FE, wear, fuel | `ElectricStores`, `PumpHealth`, `Wear`, `Stills`, `FiredHeaters`, `PrimeMovers`, config | one test per choice at factor 72 |
| W5 | Screens and status lines say which time they show: rates stay per process hour; "since", "next check at" and solve ages in play seconds | `IslandCoordinator` status, `OperationView`, lang `en_us.json` | software renders; `LanguageFilesTest` at the translation commit |
| W6 | Measurement at 72: one campaign (1000 seeds, 8 workers); the running column, a heat pair in two islands and a level loop on a 5 min drum, each for 1 h of process time | `tools/minecraft-time-delta/`, the fluid GameTests | the campaign against the 0.26.0 lane by class; the three worlds hold nothing and conserve |
| W7 | The rule text and the guide | `AGENTS.md` ("Simulated time is online ticks times `fluid.timeScale` at a nominal 20 TPS"), code guide `fluid-runtime.md`, `fluid-science.md`, `column-operation.md` | - |

Order: W1, W2, then the quick check; W3; W4 and W5 after the owner's choices; W6 last, on the final code. Each WP is its own commit; W1 alone is behaviour-free.

## 3. Owner decisions (the physics forces none)

| # | Question | Options | Recommendation |
|---|---|---|---|
| T1 | Default factor | A: 1, the owner sets 72 per world. B: 72 for every world, existing ones included (contents kept, plants run 72 times faster) | A until W6 has passed, then the owner flips it in its own merge |
| T2 | FE | A: play-time rate kept, the exchange rate times the factor (a 1 MW motor stays 20 000 FE/tick; 180 J/FE at 72). B: process joules at 2.5 J/FE (1.44 million FE/tick) | A: other mods' generators stay usable |
| T3 | Pump wear (2 h cavitation, 10 min dry) | A: play time. B: process time (100 s and 8 s of play at 72) | A: it is a game balance number |
| T4 | Fuel | A: process time, as physics (a line's fuel and a solid item's heat are conserved; a still burns 72 times the items per play minute). B: an item's heat times the factor | A: fuel from a line cannot be scaled without breaking conservation |
| T5 | Level control at a 6 min sample | A: leave the loop on the control cadence and state in the simulator's sizing that a controlled drum holds two cycles (12 min). B: the level loop inside the island solve | A, and B as its own batch only if W6's drum overflows |
| T6 | The column's accounts (36 min of feed, 2.4 h of products at 72) | A: keep the cycle counts (the same play-time response as today). B: shorten them at a factor above 1 | A, revisited on W6's column run |

## 4. Saves and formats

- The factor is a setting, not a fact: no facts-schema change, no corpus image. Contents, the ledger and the online tick stay.
- State follows the revision key: a world opened at another factor rebuilds its islands from the facts once (junction buffers are re-sized by the mint's rule, D46; certificates and solved intervals are dropped).
- No wire protocol changes; a status line's wording does (W5).

## 5. Known holds at 72 (not part of this plan)

Bundled 15 (an equation-gate miss on a fugacity row of a two-phase crude junction), 47 (liquid drying out in a gas tank), cold 72 (non-finite fugacity derivative at 111 K), cold 6 and 183 (new under the larger buffer, not traced). Each is one world of 200; W6's campaign states their classes at 1000 seeds.

## 6. Not in scope

A choke law, tolerance changes (option A is dropped; 1e-8 / 1e-7 stays an option in OPTION_A_REVIEW.md), the level loop inside the solve (T5 B), the level's day clock as the time source.
