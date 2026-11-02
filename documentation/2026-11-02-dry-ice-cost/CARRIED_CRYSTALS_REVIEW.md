# Carried crystals review: mode (a), then form G

Base 59b69d873. Plan: [CARRIED_CRYSTALS_PLAN.md](CARRIED_CRYSTALS_PLAN.md). Option A below is the orchestrating session's reversible decision; the owner decides between A, B and C. Not run: campaign, final lane, `slowGates`, GameTests. Code guide, CHANGELOG, version: wait for the owner's word.
## Expected behaviour
LNG let down to 1 atm is vapour, liquid and frost. At step level (D23) that is vapour and one fluid liquid, with no crystal booked. PR78 has no frost, so below the triple point it continues them as three fluid phases: V, a CO2-lean liquid L1 and a near-pure CO2 liquid L2. A depositing pipe closes by settling: below the deposition velocity a particle takes D/v_s to reach the wall.
## What the code did
Every step flash ran the crystal competition, 5 to 24 times a fluid flash, and `adopt` dissolved the crystal back. Each DEPOSITION closure was searched to 1 us. Worst job today: 1312 to 3062 ms.
## What was built
- Mode (a) as planned: `FLUID_CARRYING_CRYSTALS`, a `TrialFilter`, the three shapes; kernel work about 1/5 of the competition. Form G as planned: `PassiveIntervalSolver.settlesWithin`. Solid plan lines 79 and 93 amended.
- Design change (A). The plan's continuous V-L family is false at higher CO2: L1 saturates near 15 % (113.2 K, 101 kPa, 3 % CO2), both products are cut, and the plan's fallback does not close it. Added: a gap statement, a dense trial that is a supersaturated non-stand-in liquid with CO2 taken out is no phase (`crystalLean`, parameter-free: the CO2 lost exceeds what the other species together lose), and `beyondStandIn`, the stand-in held out, the rest split alone, the stand-in returned. N2-rich beside ethane-rich liquid stays the `LIQUID_LIQUID` hold.
- Removed: the arrival site and `adopt`'s crystal branches. `adopt` moves no number (768: 20606552 checkpoints both); the arrival site moves worlds, in their favour. The `adopt` reason on the 90 K floor moved to `carriedSplit`. The density tables stay fluid-only (`fluidPH`), today's code.
- `singleRoot` is `LIQUID` in both one-liquid branches: all nine worlds keep their checkpoints, and no difference on 18805 single-phase grid states, so no test pins it.
## Options
Worst ms / holds on 244; 907. A: (a) + G with fluid-only tables, 750 / 0; 984 / 19. B: the plan's form, no G: 750 / 12; 3234 / 10, with 651 at 5875 ms. C: carried tables: 12281 / 20; 1156 / 10 without G, and 1312 / 14; 703 / 10 with G. C swaps the dead world: 907 lives (tanks 371 + 26 kg either way), 244 stalls at slice 2745, job 84 of 181. Recommended: A.
## Nine worlds
Worst job CPU ms / holds / jobs over 2 s, serial, nothing else running. (a) has no G and an earlier form of `crystalLean`. Last column: the largest job of (a) + G.

| Seed | Today | (b+) + G | (a) | (a) + G | M checkpoints |
|---|---|---|---|---|---|
| 651 | 2328 / 0 / 1 | 1562 / 0 | 1812 / 0 / 0 | 516 / 0 / 0 | 1.7 |
| 768 | 1312 / 0 / 0 | 312 / 0 | 266 / 0 / 0 | 219 / 0 / 0 | 0.5 |
| 673 | 1672 / 0 / 0 | 344 / 0 | 438 / 0 / 0 | 422 / 0 / 0 | 0.5 |
| 183 | 1812 / 0 / 0 | 578 / 0 | 438 / 0 / 0 | 359 / 0 / 0 | 0.8 |
| 683 | 2828 / 13 | 328 / 0 | 391 / 0 / 0 | 234 / 0 / 0 | 0.4 |
| 244 | 1781 / 0 / 0 | 2562 / 0 | 375 / 0 / 0 | 750 / 0 / 0 | 1.8 |
| 475 | 2344 / 0 / 2 | 453 / 0 | 312 / 0 / 0 | 312 / 0 / 0 | 0.5 |
| 855 | 3062 / 9 / 9 | 797 / 0 | 391 / 9 / 0 | 391 / 0 / 0 | 0.6 |
| 907 | 2906 / 0 / 77 | 938 / 10 | 562 / 19 / 0 | 984 / 19 / 0 | 1.5 |

Read checkpoints, not ms: 651's job 95, 1.69 M checkpoints, took 750 ms on one build and 484 on the next, with no checkpoint changed. Every worst job is under 1 s; 651 and 244 keep 2.7x at 750 ms. G clears 855's holds, first at job 109, as on (b+): 243 jobs, 0 holds.
907 is dead, not cheap. Today's 0 is a raised-budget number: at the real 2 s budget it stops at job 175, slice 2891 (2223 ms wall). (a) and (b+) + G first hold at job 110, island 37, earlier than that. (b+) + G recovers and ends at 371 + 105.8 kg; today 371 + 36.5 kg; (a) + G ends at 1.1 kg, 298 K in both tanks: they never filled.
Final states, (a) + G against today: 651, 673, 244, 475 within about 1 %. 768: 177 + 365 kg against 194 + 355, (b+) + G 183 + 359; (a) alone gave 419 + 458, so closure timing moved it and G restores the regime. 183: 448 kPa against 542, (b+) + G 446. 683: 454 + 453 kg, equal to (b+) + G; `683-raised` holds 13 times and stops at 128 jobs, so 683 has no finished today run to compare with. 855 runs past today's stop, no reference after job 124.
Refusals left: tally, none carried in TP, PH or UV over the nine worlds (907 ends held); the fluid-only tables refuse by design (up to 12987 in 244). Probes: LNG 101 kPa 0/31, 0.14 % 0/35, 2.8 % at 541 kPa 2/51, 40 % at 1 MPa 8/41.
## Gate expectations changed, with physics
- `CrystalDepositionIslandTest`: flashTP at 95 K, 2 MPa is `LIQUID_LIQUID`, not `CRYSTAL_PHASES`: a step competes no crystal, and neither liquid is crystal-rich. The refresh's hold stays.
- `PrecipitatedSolidsTest` frosted tank: the gas stays one vapour until the refresh; no stand-in, no own split.
- `PrecipitatedSolidsTest` two LNG tanks (not named by the plan, same cause): the vapour proposal came only from the competition freezing CO2. Carried, the tank is its one liquid, 903 and 280 Pa above its own bubble pressure; nothing to refuse.
- `NetworkPhaseEngineTest`: the state at 186 K answers V + L, its third phase was the stand-in. The hold stays tested on the fluid answer. `adopt` calls use `stepCompetition`.
- `SolidClosureFeasibilityTest`: 10.5 ms becomes D/v_s (located 0.05 s, tight 0.0025 s); the 2e-6 kg mass bound gains what a flow at the deposition velocity carries in the lateness. Filter and viscosity cases unchanged.
- Unchanged and passing with their bounds: `CrystalFlushBudgetTest` (10000) and the other 12 named classes. New: `CarriedCrystalsTest`, 10 tests; a refresh's UV and PH equal today's to 15 digits.
## Risks in the plan
A closure picks a regime: yes on (a) alone, restored by G. 651's margin 2.7x. 244's job 57 no longer the worst. 683's stall at 0.354 gone. 855's 0.1059 gone under G, not explained. Between frost line and metastable dew line a gas stays one vapour: `PrecipitatedSolidsTest` and `LiquidCo2LetDownTest` pass, no run to a floor seen.
## Proposals for the owner (nothing built)
1. 907's start stall. Expected: LNG at 154.8 K, 2.6 MPa, 2.66 % CO2 has its bubble point at 1.25 MPa and dew point at 0.44 MPa, so the liquid flashes at the first junction below 1.25 MPa and the junctions settle two-phase (today 137 to 399 kPa). Code: the competition's crystal heats the mint by about 2 K and states it two-phase; without it the mint at 1.35 MPa is rightly liquid, and `BalanceNet` builds tables only for a junction that holds both phases. The next pass drops four junctions to 1 atm as liquid, 40 K superheated; `phaseCorrection` flashes one, and Newton stalls on its outlet (residual 146, a neighbour at 2 Pa). The fluid-only table refusals add 9 holds that (b+) does not have. Options: (i) tables for a liquid junction whose hull reaches its bubble pressure, recommended, the balance is where a flashing junction's density belongs; (ii) flash a whole superheated run in one pass; (iii) C, no.
2. PH steps. Expected: CH4 with 40 % CO2 at 1 MPa has a three-phase point at 151.39 K, where H rises 2900 J/mol at constant T, like boiling (2.8 % at 541 kPa: 370 J/mol at 139.01 K). Code: carried PH refuses those targets ("lies in a step of H(T)") and the adapter bisects; the competition's `binaryThreePhase` has no carried twin. No world meets them. Options: (i) leave it, recommended until a world does; (ii) a lever rule in `closedPh`.
## Open
Why carried tables hold 244 (rows of junctions with vapour and near-pure CO2 at 1 atm; not traced further). Tools: `tools/dry-ice-cost/carried/`. Logs: main checkout, `tools/dry-ice-cost/logs/carried-crystals-logs-2026-11-03.tar.gz`.
