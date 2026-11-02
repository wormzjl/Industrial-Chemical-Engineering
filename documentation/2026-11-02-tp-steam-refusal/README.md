# 2026-11-02-tp-steam-refusal

Purpose: make the TP flash answer one hydrocarbon beside steam, a refusal in `FluidTpEquilibrium` that the process simulator batch found (flash finding 3 in `tools/process-simulator/README.md` on `claude/in-game-process-simulator-93dd9a`), and let PH and UV close that feed's true step.

Status: Implemented 2026-11-02, released as 0.18.1 with `2026-11-02-ph-false-root` (branch `claude/focused-raman-3bbde7`). Not pushed. Index: [../INDEX.md](../INDEX.md).

## Documents

- [TP_STEAM_REFUSAL_REVIEW.md](TP_STEAM_REFUSAL_REVIEW.md): the physics, what the code did, the fix, the numbers that moved and the options.

## Code areas

- `science/thermo/phase/FluidTpEquilibrium.java`: `solveWet`, `solvePh`, `closedPh`, `closedInner`, `wetCoexistence`, `coexistenceTemperature`.
- `NetworkPhaseEngineTest`: three gates.
- Code guide: `equilibrium-engine.md`.
- Tools: `tools/ph-false-root/` (probe modes `refusals`, `purestep`, `tpcost` and others, `cmp.pl`, `moved.pl`, `proof.sh`, the lane).

## History

- 2026-11-02: census on 18 feeds: the refusal only on one-hydrocarbon feeds, 64 of 612 states; the near-critical and 120 K refusals are other mechanisms. Form T1 (closure in `solveWet`): every refused state answers, and targets in the step stay typed. Form T2 adds PH at the true step, through `wetCoexistence`. The sweeps moved no other feed's TP state.
- 2026-11-02: code `9b62a7ee`, gate `60e13ca5`. Each rule disabled and its gate run alone.
- 2026-11-02: final lane at `60e13ca5` against the lane at `be052e63`: 1997 tests, 8540 printed and 33 junction lines with the same values, chain-100 exact, 36 GameTests, campaign 972, 994, 1000 clean, refused 0, `phaseRefusals` -12.7 %. The campaign shared the machine; the two new budget holds, cold seeds 183 and 768, ran clean alone with main's end states. The commit after the lane holds the documents and the tool folder.
- 2026-11-02: owner's decision: "Merge as 0.18.1", options 1 and 3 of the review; option 2 not built. Main 0.18.0 brought in (`e5eca1a2`, conflicts only in `CHANGELOG.md` and the two indexes; its 68 commits touch neither thermo package). On the merged tree `test` 1883 and `slowGates` 188, no failures. Not pushed.

## Open items

- Near-pure and clean-cut PH targets inside a step stay typed: option 2 of the review, not chosen.
- Flash finding 2 (PH under the melting line) stays open.
- On the branch before this batch and unchanged: the critical band at 550 to 700 K from 30 bar, no two-phase start for a heavy cut at 120 K, cold UV on stiff two-liquid states, and UV on water-rich gas with water ("a step of U(T)").
- `FlowThermo` on the simulator branch searches along the TP flash; it can return to PH now. That batch decides.
