# W10a: two-phase pump and compressor laws, review

Commit `5516c976f` (owner decisions D19 to D21, plan section 3.6). Built by an Opus medium subagent and reviewed here.

## 1. What changed

- **Refusals removed.** The pumps' vapour refusal (2 % by volume) and the compressors' condensed refusal (1 % by mass) are gone, with their hysteresis and reasons. A dry suction (D58) still refuses. A compressor's solids above the trace still refuse; a solid population is not a fluid that the homogeneous law can move.
- **Centrifugal pump.** Head and efficiency are multiplied by `M(α) = 1/(1 + (α/1.6 α_T)^4)`, with Turpin's onset `α_T(p_s)` (`GasEntrainment`).
  - The agent scaled the head only. Because rated shaft power derives from the head, that also cut the shaft power by M. I changed it so the efficiency scales too: shaft power stays on the mixture, and η is η·M, as shown to the owner.
  - Gas lock is not a new mode. Where M times the shutoff head falls below the line's lift, the curve meets zero flow and the check valve (D13) closes the pump.
- **Set-flow movers.** Their law counts the suction mixture's volume, so a pump delivers (1 − α) of its displacement as liquid.
- **Wet centrifugal compressor.** `η_wet = η_p (1 − 0.5 w_l)` on the homogeneous mixture. The transport books the same mover power as the energy rows (`ConservativeTransport.MoverWork`).
- **NPSH3 floor.** The floor is `p_v + 0.85 ρ_l g NPSH3(Q, s)` (`Npsh`), inside D17's lesser-of. It applies in the row, the mode tests and the balance (`BalanceNet.cavitationFloor`).
- **D21.** An in-line pump's NPSH3 design is its set flow at the speed that keeps NPSH3 at 3.3 m, at most 2950 rpm (`Npsh.forDuty`).
- **Readings once per solve.** Every reading is taken once per solve on the start states (`PassiveStepSolver.readSuctions`), keyed by the identity of the control.
- **Rated speed.** `CentrifugalPump` carries its rated speed, so the checkpoint state format goes to 33 (state only).

## 2. Tests

- **Affected set, run once on a free machine:** 103 classes, 639 tests; hold gates 67 of 67; fluid GameTests 39 of 39.
- **The first run failed 2 tests** in `PumpRateWarmStartTest`.
  - At D19's 2-pole speed, NPSH3 is 47 m at 1.5 m³/s, so every in-line pump above about 0.17 m³/s cavitated from a full atmospheric tank. The owner chose D21 A.
  - With D21, the warm-start test's lawful start of 1.5 m³/s still cavitates. Its 0.3 m suction line runs at 21 m/s and leaves less than 3.3 m of NPSH. The start was restated to 1 m³/s.
- **Restated for the removed refusals:** InletPhaseTest, CompressorTest, VelocityClampTest, FluidCheckpointCodecTest, PumpBottomPortDrainTest, PumpCavitationTest, PipePresentationTest, and the GameTest batch `fluid-pump-gas`.
- **Added:** TwoPhaseMoverTest, GasEntrainmentTest, NpshTest, and cases in CentrifugalPumpTest and CentrifugalCompressorTest.

## 3. Full campaign (1000 seeds, all distributions, 8 workers, run alone)

| | base 0.19.2 | D17 | W10a |
|---|---|---|---|
| cold random | 991 | 992 | 991 |
| bundled random | 993 | 994 | 993 |
| bundled defaults | 1000 | 1000 | 1000 |

- **New holds:** cold 6 (class F). Replayed alone, it is clean: a wall-clock artefact. Bundled 6 (F) is a baseline hold that D17 had cleared. Bundled 431 moved from class E6 to class F; replayed alone it still holds, with the same active-set cycle as in the baseline. No new mechanism.
- **Counters against D17:**
  - Random distributions: within 1 %.
  - Bundled defaults: Newton iterations +5.5 %, backtracks +16 % (17,658 to 20,470), Jacobian builds +5.5 %.
- **Wall time against D17:**
  - Cold +27 %, bundled random +23 %. Both are inside the D13 to D17 spread (1.20 to 1.56 M ms, and 287 to 397 k ms).
  - Bundled defaults +54 % (40.9 to 62.9 k ms), above the earlier maximum of 49.7 k. With the counters about 5 % up, the rest is per-solve overhead. `readSuctions` walks each mover's suction twice per solve (`InletPhase.supply`, then `InletPhase.liquidDensity`, which repeats the walk).

## 4. Options for the owner

- **O1, one suction walk.** Return the liquid density with the supply from one walk. This is code only, no physics change. Measure it with one bundled-defaults pair.
- **O2, the wet compressor ratio.** The ratio reads the mixture density, which overstates a very wet suction: about 31 against 1.6 for pure condensate at 50 bar and 50 kJ/kg. The exact homogeneous form, `H = σ(1−w)P v_g (r^(1/σ) − 1) + w v_l P (r − 1)`, is an option. Today's 1 % refusal kept such states out; the random worlds have none.
- **O3, the vapour lock.** It reads the bubble floor. A pump drawing straight from a fixed generator, with no suction line and NPSHA below its shutoff NPSH3, has no root. Only test graphs build this, since in game a pipe always sits between. The option is for the lock to read the NPSH3 floor at shutoff.

Code guide: not updated yet. The fluid network chapter (mover laws, refusals, suction floor) and the machine chapter (GasEntrainment, Npsh) change at the merge refresh.
