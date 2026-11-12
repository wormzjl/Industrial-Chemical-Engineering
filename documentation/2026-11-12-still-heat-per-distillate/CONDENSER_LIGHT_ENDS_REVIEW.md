# The still's condenser on a vapour with light ends: review

Set-up (probe, branch `claude/still-on-aqueous-fdba0c` at d908aa617, W4 column with the per-mole law): 500 L solid pot still, built-in condenser (1.92 m²), no column, 60 % full of the generator's medium crude flashed at 25 °C (259.5 kg, 1,153.7 mol), firing 1 (32.0 kW), design water at 25 °C (3,841 W/K), `BatchStill.step` at about 1 % of the charge a step. Nothing built.

## 1. Expected behaviour
- The condenser is a partial condenser. Its outlet is no colder than the water plus an approach, T_out = 30 °C here, and no hotter than the head vapour. At T_out the head vapour is an equilibrium: the liquid goes to the spout, the gas to the vent, the heat is V (H1 − h_out) with h_out both phases'.
- The engine's TP flash of the head vapour at 30 °C (the reference) gives a liquid share that rises smoothly with the pot: 0 at 25 °C (y: CH4 0.47, C2 0.23, C3 0.16), 0.095 at 78 °C (liquid 0.092 kg/mol), 0.464 at 104 °C, 1 at 249 °C. The gas vents with no alarm; COOLING LIMITED only where V (H1 − h_out) exceeds the capacity.
- Correction to the brief: at 247 °C the split is right. The head vapour's bubble point is a true one (169.8 °C, the liquid and vapour roots apart) and 69,414 J/mol is its heat to that bubble point. The 37,500 J/mol it was compared with is the pot balance's heat a mole, a different quantity. Subcooled to 30 °C it would be 115,250 J/mol.

## 2. What the code does
- The condensate is the bubble point of the head vapour y, searched from T_pot − 1 (`StillColumn.single`, line 457, through `boiling` → `bubble`). A still with trays does the same for stage 0 in the iteration (`bubble(x[j], t[j], y[j])`, line 360, j = 0) and, with water, in `decanter` (line 621).
- Trivial root: where y has no liquid root at 1 atm, `model.phase(LIQUID)` returns the vapour root (v_L / v_V = 1.000, every ln φ_L − ln φ_V = 0), so ln Σ K x = 0 at the start, and `bubble` returns its start as the bubble point (line 694: |f| < 1e-11). The probe shows this at 25.0 to 49.6 °C: T_cond = T_pot − 1, H1 − h0 = 56 to 76 J/mol.
- COOLING_LIMITED with no liquid comes from `BatchStill.boil` line 410. The capacity is taken at the mean (T_head + T_cond) / 2, and `condenserCapacity` (line 143) returns 0 where that mean is at or under the water. So `condensed` = 0, `liquidShare` = 0, and everything vents (line 415). The probe goes through three regimes, each wrong:
  1. 25.0 to 25.3 °C: trivial root, mean ≤ 25 °C: COOLING_LIMITED, all vents. The outcome is right only by accident.
  2. 25.8 to 49.6 °C: trivial root, mean > 25 °C. The capacity (0.24 to 20 kW) exceeds a need of 0.6 to 1.6 W, so DISTILLING sends C1 to C3 gas to the spout as liquid (0.64 % of the charge's moles).
  3. 57.1 to 78.3 °C: a true bubble point far under the water (−130.7, −69.1, −47.2 °C), mean −65 to +16 °C: capacity 0, COOLING_LIMITED, 2.55 % of the moles vent, the 9.5 % that would condense at 30 °C among them. From 90.4 °C the bubble point is still −24.3 °C, but the mean is 33 °C. Capacity 6.8 kW > need 3.3 kW: the whole vapour "condenses" to a −24 °C liquid. At 104 °C the split takes 39,070 J/mol to a 0.1 °C liquid; the reference takes 22,860 to 30 °C with 46 % liquid.
- These are steps (DISTILLING gas, then COOLING_LIMITED, then total condensation), and the vented share depends on the step size: 2.56 % of the moles at the finer steps, 3.67 % at the coarser.
- The world (read, not run): `StillHead.divide` (line 145) sends a component to the spout if its critical temperature is above the condensate's temperature (`Stills` line 336). At a −131 °C condensate, everything but N2 goes to the spout, the opposite of the sheet.

## 3. Options (numbers at the three states; reference = engine flash at T_out = min(T_head, 30 °C))
| State (pot) | Today: T_cond, to spout, J/mol | Reference: liquid share, J/mol | a: engine flash at T_out | b: Tc cut + bubble | c: partial condenser at max(T_b, T_out) |
|---|---|---|---|---|---|
| 25.0 °C | 24.0 °C (trivial), 0 (capacity 0), 56 | 0 (T_out = T_head), 0 | = ref | 52.6 % kept, T_b −73.1 °C: capacity 0, all vents | = ref (f at T_out has no liquid root: all gas) |
| 78.3 °C | −47.2 °C, 0 (COOLING_LIMITED), 35,623 | 0.0954 at 30 °C, 7,924 | = ref | 100 % kept, −47.2 °C: as today | = ref (own flash 0.09544, 7,924) |
| 249.5 °C | 169.8 °C, 1, 69,414 | 1, 115,250 at 30 °C; 69,414 at T_b | 1, 115,250 (subcooled) | as today | 169.8 °C, 1, 69,414 (unchanged) |

- **a. Engine TP flash at T_out.** One rule: the outlet is always T_out, subcooled. Cost: 1 engine flash a solve, 1 an iteration with trays; a dry pot goes from 0 flashes to 1 or more (W4 gate D7). It moves every reference: subcooled reflux, hD, the condenser's duty (+66 % at 249 °C), the naphtha statuses.
- **b. Refuse the trivial root, cut the components with Tc ≤ T_out, take the rest's bubble point.** It fails on the physics: the bubble point stays under the water (−73, −47, −24 °C), so the rest still all vents or all "condenses". Refusing the root alone makes `single` fail ("no bubble point of the condensate") at 25 to 50 °C. Not proposed.
- **c (recommended). Partial condenser at max(T_b, T_out) on the column's own phases.** f = ln Σ K y at T_out. If y has no liquid root there, or f ≥ 0, the outlet is T_out, stated by a Rachford-Rice on the PR phases already in `StillColumn`. Otherwise the bubble point is bracketed in [T_out, T_head] (no trivial root there: a trial with no liquid root counts as above). The value is continuous at T_b = T_out.
  - Probe: the own flash equals the engine's to the printed digits at both states, in 14 phase evaluations at 78 °C and 2 where Wilson's K already decide.
  - Cost: about one bubble search's evaluations, 0 engine flashes on a dry pot. A wet head vapour uses one engine flash at T_out in W4's `decanter` (it already splits there).
  - It deletes: the condensable rule by critical temperature in `StillHead.divide`, `StillHead.critical`, `Solved.critical`, `WATER_CRITICAL` and `Stills.critical`/`criticals`; `BatchStill.step`'s one `liquidShare` for every component becomes the split's share per component.
  - It needs: a T_out input to `solve` (the water's temperature plus an approach: owner's number, 5 K proposed); the partial condenser's gas as a vent stream in `Split`, as W4's D3 shares already carry, s_i = ρ (1 − β) x_i / y_i.
  - Risks: early crude steps move (gas to the vent, 57 to 107 °C partial liquid instead of none or all); the tutorial's chapter 2 numbers move; the crude gate's 3.04 % vented moves. Dry heads with T_b ≥ T_out (naphtha on 15 °C water, the wash, wet naphtha) stay bit for bit only if the bracket returns today's root: a gate must show it.

## 4. Recommendation
Option c with a 5 K approach. It puts the condenser on the physics of section 1, reuses W4's per-component shares, keeps a dry pot at 0 engine flashes, deletes the critical-temperature rule, and leaves every head that condenses above its water unmoved.
