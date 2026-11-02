# PH and UV false root on wet oil: review

Batch `2026-11-02-ph-false-root`, branch `claude/focused-raman-3bbde7`, code `5e9fd2b9`, `be052e63` on main `3ffeb051`. Not merged. Tools: `tools/ph-false-root/`. Code guide: `equilibrium-engine.md`.

## 1. Expected behaviour

Hydrocarbon liquid, free water and a gas at P: steam holds p_w(T), hydrocarbon vapour holds the rest. Near water's saturation T_sat that rest goes to zero, the gas grows, and it takes all the water before T_sat. A feed with a volatile hydrocarbon therefore has no step of H(T) at T_sat. Its water leaves over a continuous three-phase zone that ends y R T²/L under T_sat, with y the hydrocarbon share of the gas: 28 K × y at 1 bar, 89 K × y at 60 bar. Water boils alone, in a step at T_sat, only where y is next to zero. Two things bound y: the liquid's vapour pressure, P_b/P, and the amount, n_HC/(n_HC + steam).

## 2. What the code did

`solvePh` and its UV twin called the close stage as soon as a bracket's ends read `condensedStep`: no gas at one end, all steam at the other. Any wet hydrocarbon feed reads so on a wide bracket. `waterBoiling` then answered at T_sat with no hydrocarbon vapour. Its steam fraction comes from the target, so the enthalpy matched and nothing flagged it. Main's worst PH miss on the sweep: 20.2 K.

| TP state | Flash | main | this branch |
|---|---|---|---|
| Crude + 0.2 water, 2 bar, 376 to 382 K, 12.3 to 14.0 mol hydrocarbon vapour per 100 | PH | 393.36 K, no vapour | own T, own vapour |
| Crude + 0.2 water, 30 bar, 500 K, 8.2 mol | PH | 507.00 K, no vapour | own T, own vapour |
| Crude + 0.01 water, 5 bar, 393 K, 1.17 mol | UV | 393.31 K, 2.0 bar, no vapour | own T and P, own vapour |

## 3. Proposal, built

One check in `waterBoiling`, the stage that claims the water boils alone: it answers null where `hydrocarbonGasShare`, the lesser of Σ x_i φ_i and n_HC/(n_HC + steam), exceeds `BOILS_ALONE_SHARE` = 1e-3. Null already means "the search goes on", so PH and UV share the check, and no kernel call is added. The search code and `condensedStep` do not change. The fix removes no special case: pure water and stiff liquids still close early, and no caller outside the engine reads `coexistenceFromBalance`.

Forms: A (the brief's, no early close with hydrocarbons) and B (a TP trial under T_sat) lost 4 to 9 targets of a clean heavy cut, whose step is real and can be narrower than the bracket; A also took cold UV on it from 35 to 146 TP answers. C (vapour pressure only) lost water holding a trace of oil. D is C with the amount bound. That is four forms, one over the rule's three, and C had its own campaign: D came from reviewing C.

| Sweep, main → branch (306 round trips, 60 targets per feed) | PH misses | UV misses | Balance answers | Largest move |
|---|---|---|---|---|
| Crude + water at 4 ratios; middle cut + water | 25 → 0 | 2 → 0 | 77 → 0 | 11.9 K, 14.5 mol |
| Residue + water | 1 → 0 | 0 → 1 (stiff liquid: 3556 Pa for 5000 Pa, same volume) | 22 → 0 | 1.7 K, 0.46 mol |
| Heavy cut + 1e-3 and 1e-2 methane | 5 → 0 | 3 → 0 | 25 → 0 | 2.9 K, 0.74 mol |
| Clean heavy cut, pure water | 1 → 1 (2.6 mK, inside the bound) | 0 → 0 | 74 → 74 | none |

Water with a trace of crude, steam fraction 0.5: main's balance sat 1.2 to 3.2 K over the true T at 30 mol water per mol crude and 0.05 to 0.15 K at 1000; the search answers both now. At 1e4 the balance stays, 5 to 16 mK off, 2 TP answers against 24. Cost: a right PH answer takes 8 to 11 TP answers where the false one took 2; sweep means rise by at most 0.3, at the step crude + 5 water goes 5.0 → 13.9, and cold UV on residue + water 32 → 83.

| World references (one lane, at `be052e63`) | main `3ffeb051` | branch |
|---|---|---|
| `test slowGates` | 1991, 0 failed | 1994, 0 failed (3 new gates) |
| 8540 printed, 33 junction lines, `MIXED_GAS_COST` newtonSolves 299 | | same values; only times and heap sizes differ |
| chain-100 exact, fluid GameTests | | deviation 0, 36 of 36 |
| Campaign clean (cold, random, defaults); holds; refused | 975, 993, 1000; 363; 0 | 974, 993, 1000; 362; 0 |
| Worlds with another end state; newtonIterations (cold, random) | | 4 of 3000, all with wall-deadline holds; +0.16 %, +0.29 % |

Cold seed 118, the one new budget hold (one wall deadline while another test JVM ran), replayed alone: clean, 48.7 s, end state equal to main's, so cold stands at 975. The other three worlds hold wall deadlines on main too and move from run to run: seed 683 went 7 → 9 holds in form C's campaign, 7 → 13 here. Gates in `NetworkPhaseEngineTest`: `wetCrudeComesBackWithItsHydrocarbonVapour`, `aHeavyCutsWaterBoilsAloneUntilTheLiquidHasAVapourPressure`, `waterWithATraceOfCrudeBoilsAlone`. All three fail with the check removed; the last fails without the amount bound. Saved formats: no change. A saved node at a false root moves to the true state at its next solve, with the same energy and volume.

## 4. Options for the owner

1. Keep the bound at 1e-3 (recommended). The physics does not force it: it is the smallest measured value that loses no target. Where it applies T is at most 30 mK off at 1 bar, 90 mK at 60 bar, and T(H) jumps by that much where the check flips.
2. 1e-4: a tenth of those errors, and 2 clean heavy cut targets at 30 and 60 bar become typed failures.
3. No bound, not built: a step at the wet bubble point and a lever rule between a closed bracket's ends, about 40 lines. It deletes the hydrocarbon branch of `waterBoiling` and answers main's "lies in a step" failures. It needs its own lane.
4. Whichever is chosen: 5 of 50 targets on a pure hydrocarbon with water change from a false answer to a typed failure until flash finding 3 is fixed. Version: patch, 0.18.1. Main is now 0.18.0; no source file of the fix overlaps, and only `CHANGELOG.md` and the two indexes conflict.
