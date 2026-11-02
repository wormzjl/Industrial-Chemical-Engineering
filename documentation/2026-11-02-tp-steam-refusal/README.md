# 2026-11-02-tp-steam-refusal

Purpose: make the TP flash answer one hydrocarbon beside steam, a refusal in `FluidTpEquilibrium` that the process simulator batch found (flash finding 3 in `tools/process-simulator/README.md` on `claude/in-game-process-simulator-93dd9a`), and let PH and UV close that feed's true step.

Status: In progress since 2026-11-02 on `claude/focused-raman-3bbde7`, on top of `2026-11-02-ph-false-root`. Built and measured. The merge waits for the owner. Not merged, not pushed. Index: [../INDEX.md](../INDEX.md).

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

## Open items

- Merge, push and version wait for the owner, with the PH false root batch on the same branch. The proposal is 0.18.1.
- Near-pure and clean-cut PH targets inside a step stay typed: option 2 of the review.
- Flash finding 2 (PH under the melting line) stays open.
- On the branch before this batch and unchanged: the critical band at 550 to 700 K from 30 bar, no two-phase start for a heavy cut at 120 K, cold UV on stiff two-liquid states, and UV on water-rich gas with water ("a step of U(T)").
- `FlowThermo` on the simulator branch searches along the TP flash; after this merge it can return to PH. That batch decides.
