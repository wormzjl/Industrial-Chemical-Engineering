# Feed-step hold of the drum level loop (2026-11-11, owner "Diagnose first")

Diagnosis, then the owner's fix (section 6). One review-server run at factor 4: `LEVEL_LOOP_OPTS=-Dicheme.probe.failures=true bash tools/minecraft-time-delta/level-loop/run.sh 4`, with the row and failure probe `tools/minecraft-time-delta/patches/probe-feed-step.patch` applied for the run and reverted. Lines: `tools/minecraft-time-delta/results/feed-step-hold.txt`. Run: `LevelLoopStepProbe` at 4: both empty-drum cases held (2 of 2). One was "Newton line search stalled at residual 592.56; active-set pass=0", 44 rejections; the other 594.26, 43 rejections. Both one-step cases ran clean (level 25 to 58 %). The suite body (ramped) passed. At 4 the rule feed and the factor-4 feed are the same 198.1 kg/s, so the four cases are two pairs.

## 1. Expected behaviour
- Drum 7.95 m3, feed 198.1 kg/s (0.199 m3/s). One cadence is 20 s, so one slice of feed is 3.97 m3, half the drum.
- LIC-1 asks the outlet valve for `inflow + (volume - target) / cadence` (`InventoryControl.target`): dead-beat in one cadence.
- If the asked outflow is more than the drum holds plus its feed, the drum runs dry. A valve then cannot pass more than reaches it: it opens fully and passes the feed, 198 kg/s. The island goes on, and the next decision sees an empty drum.

## 2. What the code did
- **The demand.** The level valve was REGULATING at 592.8 kg/s, 3.0 times the feed. Its slice started with the drum about 0.14 m3 full (1.7 %): it was dry 0.35 s into the 20 s slice. The LIC would not ask for 0.59 m3/s from a 1.7 % drum on a current volume. The decision was made on an earlier, fuller drum. Arithmetic: with each decision acting one cadence late, dead-beat with a one-sample delay overshoots. From empty: 50 % full at 20 s, 100 % at 40 s, then 75 %, then 0 % at 80 s, while the valve still holds a demand of about 2 times the feed. This also explains finding 3 (the liquid-full drum). The one-cadence delay is inferred from the order of `ControlService.tick` (readings of the last publication, a law that the next dispatched slice takes). It is not timed in this run.
- **The solver at each step.** Pass 0 lays out the drum as a vessel, and the step empties it, so pass 0 fails. Pass 1 lays it out as a junction: inflow 198 against the valve's 593 out. The junction's continuity row carries the residual (155 to 352), and the line search stalls.
  - Steps are accepted only while 395 kg/s × step < the drum's mass. The mass halves with each accept: 0.56 kg, then 4.9 g. At 4.9 g the drum is DRY (phase 32) and its outlet closed. The valve's upstream junction then has 0 in and 593 out: continuity residual = the valve's flow, 592.56, at pass 0. Halving runs from 1e-4 s to 2e-10 s at a constant residual. It ends at the 20-consecutive-rejection limit, not the wall budget.
- **The defect.** No active-set pass releases a REGULATING valve whose supply has run dry. The infeasible row is the valve's own target.
- The earlier holds fit: residual 376 (run w) and 542 to 567 (runs g, l) are the level valve's regulated flows in kg/s at those holds.

## 3. Candidates, in numbers

- Ruled out:
  - the first step against the holdup: the hold comes 0.35 s into a slice, at steps of 1e-2 s and below;
  - the 0.2 s junction buffer: the row is a mass balance with zero supply;
  - gas becoming two-phase: the change is liquid to DRY;
  - the control edit mid-slice: one slice was 10 s, but the mechanism does not need the split.
- The step controller does not recover: it hits the 20-rejection limit.
- Parent's H1 (the port-throttle band), refuted by check 1:
  - the drum held 7.1e-5, then 6.2e-7, of its capacity, far below the band (0.005 to 0.01);
  - the band acts only on a mover's suction (`InletPhase.Supply.dry`), and this line has no mover;
  - the valve is REGULATING at pass 0, at 592.8 kg/s, above the line's frictionless gravity flow (531 kg/s).
- Check 2 (`worlds/DrumDrainTest`, factor 1, no control): an open outlet drains clean from empty and from 1 %; from 1 % it settles at 0.40 % with outflow = feed.
- Correction (2026-11-11): its regulated case held for another reason. Its first solve leaves the outlet junction at 300 Pa of gas with the open valve running in reverse, and the valve not REGULATING (an open question, below).

## 4. Why factor 1 ran clean

By structure, not by chance. **Rule feed (792 kg/s):** the same overshoot would ask for about 1.6 to 2.4 m3/s. The 0.25 m outlet passes about 0.8 m3/s (the factor-1 suite case stands at 58 % with the valve 87 % open). So the valve is limited by pressure, not REGULATING, and it drains the drum on its head. **Factor-4 feed at 1 (198 kg/s, cycle 5 s):** one cadence fills 12.5 %, below the 25 % setpoint, so there is no overshoot. The cause depends on the feed per cadence against the drum (50 % at the rule, at any factor) and on whether the overshoot demand is within the outlet valve's capacity. It does not depend on the factor itself. A wider outlet at factor 1 should hold the same way; this is not run.

## 5. Proposal (for the owner)

Options: (1) the solver releases a REGULATING valve on a dry supply; (2) the level law predicts the volume at the time its decision acts; (3) cap the asked outflow at `inflow + volume / cadence`. The owner chose 1 and 2; 2 became the first-decision fix (section 6).

## 6. Fix (owner "Options 1 and 2", then "Fix first decision")

- **Option 1** (`3a3c2c25d`: `InletPhase.starved`, `PassiveStepSolver.openStarved`, the carried start mode):
  - A regulating flow valve whose feeders hold no stock past the pass (dry ends, or vessels the pass runs dry, D54) starts open, or is solved again open in a failed pass. The converged open point regulates again above its target.
  - `StarvedRegulatingValveTest`: clean with the rule (the outlet averages 198.1 kg/s, the drum cycling between 2.2 and 3.5 %); holds without it.
- **Option 2** is not built. The delay is 0 or 1 cadence: a decision reaches the slice that starts at its reading, unless that slice was dispatched just before the control tick (a 52 % reading at 121195 reached only the slice from 121295).
- **First decision** (`0a5034904`, owner choice A): `InventoryControl.target` with no earlier sample (previous NaN) leaves out the balance term.
  - An outlet valve below its setpoint gets 0, against 0.667 m3/s before; an inlet valve gets the deficit over the horizon (1.441 m3 / 20 s = 0.072 m3/s).
  - A reading that has not advanced (elapsed 0, a sample kept) keeps the actuated flow. Taking 0 there closed the outlet after the 25 to 50 % setpoint edit (peak 78.45 %).
  - Results: suite at 4, 24.8 to 64.9 %; tool run at 72, 25.0 to 65.0 %; the un-ramped step cases at 4, 24.7 to 57.4 %; empty start at 4, 6.9 to 100 % (finding 3), so the fill and ramp stay in the test.
- **The 76.0 % peak after option 1 is not a regression.** The peak sits at cycle 1, the setpoint edit, with the same 0.076 m3/s target: cycle-1 levels were 45.5, 49.2 and 64.9 % in three runs, depending on where the edit lands in the island's lag. Two later runs on option 1's code peaked at 62.47 %. The 76.0 % run's cycle lines were overwritten.
- **Open** (not fixed): finding 3, and the cold start under a gas-filled drop leg (batch README). Finding 3 is not yet testable with a gas vent: with the vent on a 1 atm nitrogen generator, every rig case held in its setup, before the loops controlled ("Interval substep limit ... rejected=522", "Newton iteration limit at residual 5.77e-9", "line search stalled at residual 121692").