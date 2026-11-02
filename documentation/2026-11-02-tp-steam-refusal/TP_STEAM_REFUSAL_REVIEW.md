# TP flash refusing one hydrocarbon with steam: review

Batch `2026-11-02-tp-steam-refusal`, branch `claude/focused-raman-3bbde7`, code `9b62a7ee`, `60e13ca5` on `a93859b3`, the false root fix. Released as 0.18.1. Tools: `tools/ph-false-root/`. Code guide: `equilibrium-engine.md`.

## 1. Expected behaviour

One hydrocarbon beside water is the textbook steam distillation. The two liquids exert their vapour pressures side by side, whatever their amounts, so a gas appears at T_3, where p_sat + P_v = P. Above T_3 the gas takes all of the water if it is short: hydrocarbon at P_v, steam at P - P_v under p_sat, n_V = n_w P_v/(P - P_v), the rest of the hydrocarbon liquid, up to the dew point. With water in excess the hydrocarbon liquid goes first. At T_3 all three phases stand together, and H(T) steps there by the heat of what the gas takes: the pair's one true step. Middle cut `crude_pc03` with 0.3 mol water per mol: T_3 = 368.35 K at 1 bar, 4.4 K under water's boiling; 535.16 K at 60 bar, 13.6 K under. At 370 K and 1 bar: 15.7 kPa of hydrocarbon, 84.3 kPa of steam (p_sat 90 kPa), so 4.29 mol vapour beside 23.08 mol steam per 100 mol. The flash now gives 4.28. The step at 1 bar is 1.46 MJ per 130 mol.

## 2. What the code did

With the water short, `solveWet` makes all of it steam and bisects pc for pc + p_w(V_gas(pc)) = P. One hydrocarbon has no gas volume between all of it and none: V_gas(pc) steps at P_v, there is no root, and the bracket closed on P_v and refused an ordinary state: "the hydrocarbon partial pressure closing the gas volume was not found (pc 15686 Pa, p_w 100000 Pa at 100000 Pa)". A mixture does the same where the vapour fraction needed lies under the flash's resolution, about 5e-9: a heavy oil with a trace of the next cut, at a stripper's temperature. The sweep refused 64 of 612 states on two one-hydrocarbon feeds, 320 to 700 K, 5 kPa to 100 bar. The finding's other refusals are other mechanisms and stay: the critical band at 550 to 700 K from 30 bar (crude, middle cut, and the residue's 2 of 216), and no two-phase start for a heavy cut at 120 K.

PH could not close the true step either: `closedPh` handled a pure hydrocarbon only when dry, and `wetCoexistence`, UV only, gave up where the gas took all the water. Of 505 targets across the step, 186 ended on the refusal, 35 in "a step of H(T)", 5 in 100 TP answers.

## 3. Proposal, built

- `solveWet`: the bracket decides. Where it closes on a step, the steam's own volume at P - pc closes the gas: the vapour of the step's open end, scaled to that volume, the rest liquid. Exact for one hydrocarbon, first order in β < 5e-9 for a mixture. An empty gas at the 1e-6 Pa floor stays the answer for an oil with no vapour pressure to speak of.
- `wetCoexistence`: the energy balance beside the volume balance, up to where the water or the hydrocarbon liquid runs out. `coexistenceTemperature` solves the wet condition; dry answers are unchanged. PH's `!w.wet` gates and the method's bail-out on all the water go.
- `closedPh`, `closedInner`: at a `condensedStep`, `waterBoiling` first (a heavy oil's P_c goes to nothing at T_sat, which the temperature search cannot resolve), then `wetCoexistence`.

Forms: T1 (the closure alone) left every target in the step typed: 30 of 60. T2 is the build. Two forms. TP cost at a step: 56 µs for its 70 halvings, against 1.4 ms for a wet crude state, so the halvings stay.

| Sweep, `a93859b3` → fix | Measure | Before | After |
|---|---|---|---|
| 306 TP states, 18 feeds | refused on the rule | 64 | 0, none lost |
| | PH misses over 1e-5 K; PH fails | 4, 2.6 mK; 0 | the same 4; 0 |
| 20 near-pure feeds (traces 0 to 1e-2) | refused on the rule; left, all critical band | 416; 56 | 0; 56 |
| Targets at T_sat ± 12 K: middle cut; heavy oil | fails; TP answers | 47 of 50, 31.1; 2 of 60, 14.0 | 0 of 60, 6.1; 0 of 60, 8.5 |
| 6 hydrocarbons, 4 water ratios, 6 P, across the step | PH fails | 246 of 505 | 9 of 660, critical band at 60 bar |
| Clean cuts at low water, crude | PH fails | 50 of 570 | 23 of 570 |

TP moved on 16 states, all a pure heavy oil: 0 → 1e-9 to 6e-6 mol vapour, at most 1.5 J of 8.5e7. Of the step sweep's 651 answers 637 come back from UV; 11 of the other 14 are water-rich gas with water, failing as they did. Cold UV moves within two classes already on main: stiff two-liquid states ("a step of U(T)" 2e-9 K wide, 16 lost, 33 gained on the 18 feeds; 41 lost, 127 gained near-pure) and near-pure states in the critical band. PH on near-pure: 6 fails → 1, in the same band.

| World references (one lane, at `60e13ca5`) | lane at `be052e63` | branch |
|---|---|---|
| `test slowGates` | 1994, 0 failed | 1997, 0 failed (3 new gates) |
| 8540 printed, 33 junction lines, `MIXED_GAS_COST` newtonSolves 299; chain-100 exact; fluid GameTests | | same values, only times and heap sizes differ; deviation 0; 36 of 36 |
| Campaign clean (cold, random, defaults); holds; refused | 974, 993, 1000; 362; 0 | 972, 994, 1000; 390; 0 |
| Cold campaign: `phaseRefusals`, `newtonIterations` | 65541, 3112688 | 57221 (-12.7 %), 3079840 (-1.1 %) |

The campaign shared the machine with another session's runs (seed time +18 %). Its two new budget holds, cold seeds 183 and 768, ran clean alone with main's end states, so cold stands at 974 here too. All nine cold worlds with another end state hold wall deadlines and move from run to run: seed 475 ended at 4.8 kg of crystal under load and 34.0 kg alone, main's being 34.3; seed 431 has ended at 79.5, 79.8, 80.0 and 80.8 kg.

Gates in `NetworkPhaseEngineTest`, failing before on the refusal: `aPureHydrocarbonDistilsWithItsSteam`, `aPureHydrocarbonBoilsWithItsWaterAtOneTemperature`, `aHeavyOilBesideSteamStaysLiquid`. Each rule was disabled and its gate run alone: without the water's end PH puts a 320 K state at 303 K, the kind of false root the previous batch removed; without the PH branch the target is "a step of H(T)"; without the bracket deciding, H(T) of a heavy oil steps at 700 K. Saved formats: no change.

## 4. Options for the owner

1. Keep as built, recommended. The physics forces all of it for one hydrocarbon; the mixture's closure holds to the flash's own resolution.
2. Near-pure and clean-cut targets in a step stay typed, not built: 30 of 60 at trace 1e-9 with 0.3 water, 23 of 570 on clean cuts. Their zone is narrower than the temperature search resolves, and the answer needs the lever rule, option 3 of `PH_FALSE_ROOT_REVIEW.md`. TP and round trips on these feeds are fine; only a target in the step is refused.
3. Version: both batches on this branch as one patch, 0.18.1. The same conflicts as before: `CHANGELOG.md` and the two indexes.
