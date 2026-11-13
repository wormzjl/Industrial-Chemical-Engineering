# B-M1 Machines: report

Branch `claude/review-wave-a3-machines-294ed8`, with `claude/codebase-review-refactor-8a85ef` merged in (`85a6fa749`, clean). Brief: `WAVE_B_BRIEFS.md`, section B-M1.

Status: four items committed. M-18 is left out of this round (main thread's decision, option C). **No test has run yet**; the lane is held.

| Item | Commit | Files | Tests |
|---|---|---|---|
| M-09 | `f62274b95` | `WorldTopologyLedger` (shaft accessors, cell index), `ShaftTrains` (`publish`, `at`) | `WorldTopologyLedgerTest.theShaftUnitsAreALiveViewWithAnIndexByCell` (new), `ShaftCouplingTest` |
| M-08 | `ffa7f610a` | `ShaftTrains` (`plans`, `failures`) | `ShaftCouplingTest.aFailedPumpsCasingShowsFailed` (precedence added), `ShaftPlanTest`, `PumpHealthTest` |
| M-10 | `7bf283963` | `ShaftTrains` (`Run`) | `ShaftCouplingTest` |
| M-11 | `3c86364e5` | `ShaftTrains` (`Duty`) | `ShaftCouplingTest.aRegulatingMotorRunsItsPumpAtTheSetFlow` (new) |
| M-18 | not done (option C) | `Ejectors` | none |

## What changed

- **M-09.** `shafts()` returned `Map.copyOf` on every read, which was 92 % of the certified trains' bytes. It now returns one read-only live view. A new cell index, `shaftAt`, replaces the scan in the duplicate-cell check and in `ShaftTrains.at`. The index follows a put, a rebuild at another cell, a removal, and a load (the constructor; it is in the brief's "position index" scope).
  - `publish` after `decide` or `held` walks only the stepped trains' parts. A step that changes what `publish` reads always returns true and publishes at once, so an unstepped train would wake nothing. Events and compiles keep the full walk. The prime movers' section stays a full walk, because coil gas moves at bookings.
  - Every use of `shafts()` is order-free: sets, maps by key, sorted lists, or counts.
- **M-08.** One `out` map per dispatch, filled in the old merge order: fans, burners, prime movers, flares, shares and governors, then health with the M-04 rule. Every source only puts, so the result is the same. The health's map is one reused field. An empty result is still `Map.of()`.
- **M-10.** `Run.power`, `output` and `duties` are arrays aligned with `train.driven()`. A member's slot is its first entry there, so a repeated member reads one value, as the map did. A unit outside `driven` (a casing on a faulted train) stores nothing and reads 0. Its share is 0, so its booked power was already 0; only its page's shaft power could differ.
- **M-11.** The row names the wrong member: it says to compute only "for the first driven member". The code reads the duty of the single *linked* member (`candidates.getFirst()`, `step`), which need not be first. So `Duty` keeps the booking's inputs and solves each speed share on the step's first read. The values and their timing are the same, also across a mode change.
  - Counter: with a driver in SPEED mode, `requiredSpeed` (a 40-step bisection) runs 0 times per machine per commit instead of 2. That covers all 20 trains of the bench rig: 6,260 renewal solves certified, 353,340 awake.

## M-18: left out (decision: option C)

The row's premise fails. `Ejectors.booked` is one map for every island. "Retain the identities seen at dispatch" would drop the other islands' ejectors at each dispatch, and that changes their next slice.
- **A, per-owner pruning:** each entry records the island that last booked it, and that island's dispatch drops its own unseen entries. It differs only when an ejector moves to another island and the old island dispatches before the new one books it: one slice at share 1 instead of the stale share.
- **B, prune on removal:** this needs a removal hook from `FluidWorldAuthority` (B-R1's file).
- **C, leave it:** a few bytes per removed ejector.

The main thread chose C for this round. A is not bit for bit in that corner, and B needs a hook in another thread's file. B is the right form: do it with the removal hook when wave G restructures the machines' health and fault table.

## Counter: bytes per 100 ticks, 20 certified trains

- Before: 62 B a tick, about 6.2 kB per 100 ticks (PROFILE_REVIEW, `machines-cert-true`); 92 % of it is `shafts()`.
- After: to be read after "lane free" from `MachineBench` (`-Dwarm=10 -Dmeasure=10`), bytes only, no time.

## Lane plan (after "lane free")

1. `compileJava compileTestJava` once.
2. `test --tests` `ShaftCouplingTest`, `ShaftPlanTest`, `PumpHealthTest`, `WorldTopologyLedgerTest` `--max-workers 4`.
