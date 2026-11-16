**No defect found.** S2b/S2c (the dense-fluid laws and the slot rule, `e55bdf53a`..`97591600f`) moved all three printed values. They meet main 0.31.1's solver path at P5. Case 2 and case 3 move at rounding level. Case 1 takes the other of two step paths, and that path is main 0.31.0's own. Each move is inside the solver's own bound. No ordinary state's physics changed.

# The three moved printed results: trace by stage

## Summary

- Task: the owner's "Trace first" of 2026-11-16, for the final lane's unannounced moves 1 to 3 (`FINAL_LANE_REVIEW.md`). No code or test changed. Five Gradle runs, each of the three classes at one commit, in the scratch worktree `.claude/worktrees/supercritical-trace-scratch`. Logs and result XML: `tmp/supercritical-trace/<point>/` (git-ignored).
- The brief assumed that main's side never moved these lines. That holds from 0.31.1 to 0.33.0: the 0.31.1, 0.32.0 and 0.33.0 lanes print the same values. It does not hold before 0.31.1. The batch forks from main 0.31.0 (`e9edc974b`). P1 to P4 sit on 0.31.0, and no 0.31.0 lane output survives. Run 4 was therefore the fork point itself. It shows that 0.31.1 (the fluid step cost batch) moved all three lines. Case 1 moved from 7 substeps and 10 rejected to 8 and 11. That batch's table of five lines beyond the Newton tolerance does not list it.
- Checked, not repeated: main has no source change since the baseline lane (`git diff 953635400 main -- src` is empty). The three tests and `fluid/support/` are unchanged from the fork to the head. The P5 merge is exact: each side's patch through the merge has the same `git patch-id` as before it, and there is no remerge diff.
- S3's raised pressure ranges reach one more place than the brief lists: `CrystalOnsets.java:116`, the start of a fixed-temperature onset pressure search, reads the envelope's maximum. Its only entry, `FluidTpEquilibrium.depositionPressure`, has no caller in `src/main`. It has no effect, and P1 = P2 confirms it.
- Bisection (table below):
  - Fork = P1 = P2 on every printed digit. S0, S1, S3 and S2a moved nothing.
  - P4 moves cases 2 and 3 against 0.31.0. Case 1 is the same at print.
  - P5 (P4 + main 0.31.1) equals the head on every printed digit of all three. S4, S4b, S5, the column item, the merges of main and S8 moved nothing.
- Bracket left: S2b (`e55bdf53a`) or S2c (`97591600f`). P3 was not run (five-run limit).
- The changed arithmetic (read from the diffs):
  - `NetworkStreams.slotViscosity` (S2b) gives the vapour slot `MixtureViscosity.dense`, Wilke plus the JST residual from reduced density 0.25. It gives a liquid slot whose root is on a supercritical isotherm the dense law instead of the log-mixing rule.
  - `FluidThermodynamics.placed` (S2c) places a one-fluid answer on a supercritical isotherm by `v_c/v >= 1` instead of by the engine's label. The two rules differ only at `v_c/v` 0.6 to 1.
  - S2b's dense velocity limit, `k` and film act only on one dense fluid.
  - Each branch is bit for bit for a gas below `v_c/v` 0.25, a subcritical liquid, and a one-fluid answer off a supercritical isotherm.
- Which states reach a changed branch (by reading; not traced):
  - Case 2's world holds real dense mixtures: methane-CO2 in the warm junction at about 4.2 MPa, between the 165 K LNG and the 312 K header. A 50/50 mixture at 230 K has `v_c/v` about 0.35, so the residual applies. This is the physics S2b and S2c were built for.
  - In cases 1 and 3 every converged state is ordinary:
    - case 1: liquid methane at Tr 0.78, its vapour at `v_c/v` 0.10;
    - case 3: nitrogen at `v_c/v` 0.004, liquid water.
    The changed branches are then reached only by Newton trial iterates and rejected attempts. Examples are a liquid methane slot tried above 190.6 K, or a gas tried at a few times its pressure. Both worlds reject attempts for line-search stalls, iteration limits and a singular Jacobian. Such a trial changes the Newton path, so the converged iterate moves inside `NEWTON_TOLERANCE`.
  - The runs agree. On 0.31.0 (P4), case 1 is unchanged at print and case 3 moves by 1 ulp in flow. On 0.31.1's path the same perturbation flips one accept or reject in case 1's interval 6. The flip lands on the path 0.31.0 printed.
- Case 1, `WaterIntoCryogenicMethaneTest` WP4 gate (c), interval 6:
  - Expected: water at 1 MPa enters the vessel on a drive of only 3.5 kPa (996.5 kPa inside) and freezes. Its fusion heat, about 6.0 kJ/mol, boils methane at about 8.2 kJ/mol. The boiling methane holds the vessel on its saturation line: 148.919 K at 996.5 kPa.
  - What the code did: 8 accepted substeps (11 rejected) on main 0.31.1 to 0.33.0; 7 (10) on the batch, as on main 0.31.0. In the interval, about 7.9 mol of water entered on the 8-step path and 7.45 mol on the 7-step path. The 0.4 mol asked about is water that did not enter: the inflow integrates a 3.5 kPa drive over another sequence of steps. The stand-in fell by 4.5 mol and the ice rose by 4.08 mol (3.07 mol froze in the interval on the 8-step path, 7.15 mol on the 7-step path).
  - Why T, P and methane stay: the net extra heat is about 4.08 × 6.0 − 0.42 × 12 ≈ 19 kJ. It boils about 2.4 mol of methane, 2e-4 of the 13,777 mol liquid. That is below the 5 printed digits, and the vapour vents to the void. T and P stay pinned on the methane's boiling line.
  - Bound: backward Euler is first order. A step is accepted while every vessel's relative pressure and mass change stays within `PassiveIntervalSolver.STATE_CAP` = 0.05; no time-error tolerance applies. The move is 1.8e-4 (inflow) and 1.7e-3 (frozen) of the vessel's 2,345 mol of water, inside one step's 5 %. It is the same size and kind as 0.31.1's own move of this line.
- Case 2, `HoldClassWorldsTest` `MELT_GATE seed=778 tankLine=1`:
  - Expected: the tank is back at rest. Its line creeps at 0.80 g/s over the last interval while the header blows 2.39 kg/s through the junction to air.
  - What the code did: the creep moved by 1.66e-12 kg/s (2.1e-9 relative). The batch's laws act on the junction's real dense states during the transient.
  - Bound: `PassiveStepSolver.NEWTON_TOLERANCE` = 1e-9 relative. The solid events read a flow within it of the island's largest flow (2.39 kg/s) as roundoff, a band of 2.4e-9 kg/s. The move is 1/1400 of that band. The test asserts < 1e-3 kg/s.
- Case 3, `PumpBottomPortDrainTest` `F1_SLICE` 0.01, slices 4 and 5:
  - Expected: the tank has drained to 85 mg of water in nitrogen at 100.3 kPa. The pump's control is closed.
  - What the code did: the slice-4 flow equals the baseline's to the bit. The trace condensed moved by 3.8e-17 kg (4.4e-13 relative), tankP by 3e-11 Pa (2 ulp), the vapour share by 1 ulp.
  - Bound: `NEWTON_TOLERANCE` 1e-9. The move is rounding, four orders inside.
- Options for the owner:
  - **A (recommended):** accept the three moves as announced by this review: S2b/S2c, rounding and one step-path split, each inside its bound. Nothing is re-recorded; none is asserted.
  - **B:** trace before accepting. One instrumented run of the three worlds at P5 counts where each dense branch acts, and whether on a converged state or on a trial. If converged ordinary states use one, change B1 restores the old arithmetic: `slotViscosity` takes the dense law only for a state's one fluid slot (`slot == oneFluidSlot() && dense()`). B1 costs S2b's rule for the liquid slots of two-phase mixtures on a supercritical isotherm. It is not made here.
- Move 4 of the final lane (the `G4F2` grade label) was not in this brief.

## Runs

Case 1: water mol / ice kg / substeps accepted-rejected. Case 2: flow kg/s. Case 3: slice 4 flow kg/s and condensed kg; slice 5 tankP Pa and vapour share. "0.31.0" and "baseline" are the references: the fork run, and the 0.31.1 / 0.32.0 / 0.33.0 lanes, which are identical.

| Point | Commit | Case 1 | Case 2 | Case 3 | Against its own main |
|---|---|---|---|---|---|
| baseline lanes (the 0.31.1 lane at `95a12e4dc`, the 0.32.0 lane, the 0.33.0 lane at `953635400`) | — | 1.3208e3 / 18.452169 / 8-11 | 7.973097600898987E-4 | 1.2825509620602296, 8.517753050284969E-5; 100293.98055282679, 0.999999914600962 | reference |
| run 4: fork, main 0.31.0 | `e9edc974b` | 1.3163e3 / 18.525634 / 7-10 | 7.973097603499142E-4 | 1.282550962060352, 8.517753050300665E-5; 100293.98055282676, 0.999999914600962 | reference for P1 to P4 |
| run 3: P1 (S0, S1) | `734c8b395` | = 0.31.0 | = 0.31.0 | = 0.31.0 | same |
| run 2: P2 (S3, S2a) | `1b0fc7770` | = 0.31.0 | = 0.31.0 | = 0.31.0 | same |
| run 1: P4 (S2b, S2c) | `97591600f` | = 0.31.0 | 7.973097617729718E-4 | 1.2825509620603521, 8.517753050300206E-5; 100293.98055282679, 0.999999914600962 | cases 2, 3 moved (S2b or S2c) |
| run 5: P5 (merge of main 0.31.1) | `8bac3e6e7` | 1.3163e3 / 18.525634 / 7-10 | 7.973097617461125E-4 | 1.2825509620602296, 8.517753050288751E-5; 100293.98055282676, 0.9999999146009619 | all three moved; = head |
| head (final lane) | `a599419dd` | = P5 | = P5 | = P5 | P6 to P12 moved nothing |
