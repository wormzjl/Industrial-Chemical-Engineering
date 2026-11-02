# 2026-11-02-ph-false-root

Purpose: fix a false root of the world's PH flash on wet oil, and its UV twin, in `FluidTpEquilibrium`. The process simulator batch found it (flash finding 1 in `tools/process-simulator/README.md` on `claude/in-game-process-simulator-93dd9a`), and it lands apart from that batch.

Status: Implemented 2026-11-02, released as 0.18.1 with `2026-11-02-tp-steam-refusal` (branch `claude/focused-raman-3bbde7`); the owner kept the bound at 1e-3. Not pushed. Index: [../INDEX.md](../INDEX.md).

## Documents

- [PH_FALSE_ROOT_REVIEW.md](PH_FALSE_ROOT_REVIEW.md): the physics, what the code did, the fix, the numbers that moved and the options.

## Code areas

- `science/thermo/phase/FluidTpEquilibrium.java`: `waterBoiling`, `hydrocarbonGasShare`, `BOILS_ALONE_SHARE`.
- `NetworkPhaseEngineTest`: three gates.
- Code guide: `equilibrium-engine.md`.
- Tools: `tools/ph-false-root/` (probe, sweep comparison, lane, campaign comparison).

## History

- 2026-11-02: reproduced on main: 393.36 K for 376 to 384 K at 2 bar, 507.00 K for 492.5 to 502.5 K at 30 bar. Forms A, B and C swept with `PhProbe`, the threshold of C from 1e-6 to 1e-3. Form C1 committed `5e9fd2b9`; its campaign was clean against main's baseline at `3ffeb051`.
- 2026-11-02: self-review found C1 sends water holding a trace of oil to the search, at 15 to 31 TP answers and with new closures. Form D bounds the share by the hydrocarbons in the balance's steam, `be052e63`. Sweeps equal C1's; trace feeds recover main's cheap answers from 1e4 mol water per mol oil.
- 2026-11-02: final lane at `be052e63`: 1994 tests, 8540 printed and 33 junction lines with the same values, chain-100 exact, 36 GameTests, campaign 974, 993, 1000 clean, refused 0. The one new budget hold, cold seed 118, ran clean alone with main's end state. The commit after the lane changes two comments in the code; it holds the documents and the tool folder.
- 2026-11-02: owner's decision on the review's options: option 1 ("Keep 1e-3").
- 2026-11-02: released as 0.18.1 with `2026-11-02-tp-steam-refusal`, on the owner's "Merge as 0.18.1". On the merged tree `test` 1883 and `slowGates` 188, no failures.

## Open items

- Flash finding 2 (PH under the melting line) stays open. Finding 3 (TP refusing a heavy cut with steam), which the 5 typed failures on a pure hydrocarbon with water showed, is fixed: batch [`2026-11-02-tp-steam-refusal`](../2026-11-02-tp-steam-refusal/README.md).
- On main and unchanged: "lies in a step" closures at 30 to 60 bar on a clean cut, and 9 failures of a middle cut near its critical band at 60 bar.
- `FlowThermo` on the simulator branch searches along the TP flash because of this defect. It can return to PH now; that batch decides.
