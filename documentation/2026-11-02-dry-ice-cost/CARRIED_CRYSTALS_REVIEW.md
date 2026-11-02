# Carried crystals review: mode (a), then form G

Base 59b69d873. Plan: [CARRIED_CRYSTALS_PLAN.md](CARRIED_CRYSTALS_PLAN.md). The design change below (option A) is a reversible decision of the orchestrating session; the owner has not yet decided on it. Not run: campaign, final lane, `slowGates`, GameTests. Code guide: waits for the owner's word, with CHANGELOG and version.
## Expected behaviour
LNG let down to 1 atm is vapour, liquid and frost. At step level (D23) that is vapour and one fluid liquid, with no crystal booked. PR78 has no frost, so below the triple point it continues them as three fluid phases: V, a CO2-lean liquid L1 and a near-pure CO2 liquid L2. The closure of a depositing pipe is a settling process: below the deposition velocity a particle takes D/v_s to reach the wall.
## What the code did
Every step flash ran the crystal competition, 5 to 24 times a fluid flash, and `adopt` dissolved the crystal back. Each DEPOSITION closure was searched to 1 us. Worst job today: 1312 to 3062 ms on eight worlds.
## What was built
- Mode (a) as planned: `FLUID_CARRYING_CRYSTALS`, a `TrialFilter`, the three shapes. Kernel work about 1/5 of the competition.
- Design change. The plan's claim of a continuous V-L family is false at higher CO2: L1 saturates near 15 % CO2 (113.2 K, 101 kPa, 3 %), both products are cut, and the plan's fallback does not close it (L1 cuts the vapour's plane too). Added: (1) a gap statement in the filter, a dense trial that is a supersaturated non-stand-in liquid with CO2 taken out is no phase (`crystalLean`); (2) `beyondStandIn`, the stand-in held out, the rest split alone, the stand-in returned to the liquid.
- `crystalLean` is parameter-free: the CO2 lost exceeds what the other species together lose. The first form, richer in every other species, left a THREE_PHASES hold on the spine grid (120 to 135 K, 1 atm, 11 % CO2, 11 % ethane) over trace nitrogen. N2-rich beside ethane-rich liquid stays the `LIQUID_LIQUID` hold.
- Removals: the arrival site and `adopt`'s crystal branches, the `crystal` flag and `sublimation` with them. 768 moves no number (20606552 checkpoints both). The reason recorded at `adopt` (90 K floor) moved to `carriedSplit`.
- Not removed: the density tables stay fluid-only (`fluidPH`). Each site alone, on 244 / 907 (worst ms / holds): arrival carried 375 / 0, 562 / 19; tables carried 17344 / 19, 1406 / 10; both 12281 / 20, 1156 / 10. Carried tables hold 244 on the rows of junctions with vapour and near-pure CO2 at 1 atm; the tables change the start that leads there. Not traced further.
- Form G: `PassiveIntervalSolver.settlesWithin`, as planned. Solid plan lines 79 and 93 amended.
## Nine worlds
Worst job CPU ms / holds / jobs over 2 s, serial, nothing else running. (a) is the form before the `crystalLean` change; it moved no world's result beyond 244's total (0.5 %).

| Seed | Today | (b+) + G | (a) | (a) + G | (a) + G, M checkpoints of the worst job |
|---|---|---|---|---|---|
| 651 | 2328 / 0 / 1 | 1562 / 0 | 1812 / 0 / 0 | 750 / 0 / 0 | 1.7 |
| 768 | 1312 / 0 / 0 | 312 / 0 | 266 / 0 / 0 | 297 / 0 / 0 | 0.3 |
| 673 | 1672 / 0 / 0 | 344 / 0 | 438 / 0 / 0 | 703 / 0 / 0 | 0.5 |
| 183 | 1812 / 0 / 0 | 578 / 0 | 438 / 0 / 0 | 484 / 0 / 0 | 0.6 |
| 683 | 2828 / 13 | 328 / 0 | 391 / 0 / 0 | 266 / 0 / 0 | 0.4 |
| 244 | 1781 / 0 / 0 | 2562 / 0 | 375 / 0 / 0 | 750 / 0 / 0 | 1.7 |
| 475 | 2344 / 0 / 2 | 453 / 0 | 312 / 0 / 0 | 297 / 0 / 0 | 0.5 |
| 855 | 3062 / 9 / 9 | 797 / 0 | 391 / 9 / 0 | 391 / 0 / 0 | 0.6 |
| 907 | 2906 / 0 / 77 | 938 / 10 | 562 / 19 / 0 | 953 / 19 / 0 | 1.5 |

Same work differs up to 1.7x between runs (907: 562 and 969 ms on equal checkpoints), so read checkpoints. No job over 2 s; every worst job under 1 s (2.1x margin at worst). 907's number is a held island, not a cure.

Refusals left: tally on the final form, none carried in TP, PH or UV over the nine worlds (907 ends held). The fluid-only tables refuse by design (up to 12987 in 244). Probes: LNG 101 kPa 0/31, 0.14 % 0/35, 2.8 % at 541 kPa 2/51 (step about 370 J/mol at 139.01 K), 40 % at 1 MPa 8/41 (a real three-phase step of 2900 J/mol at 151.39 K, no carried twin of `binaryThreePhase`).

Final states against today: 651, 673, 244, 475 within about 1 %. 768 tanks 177 + 365 kg against 194 + 355 (-9 %, +3 %); (a) alone gave 419 + 458, so G returned the regime. 183 tank 43:9 448 kPa against 542, (b+) 435. 683 tank 27 453 kg against 203 (against the `late` prototype; today holds). 855 now runs on past its holds, so it has no reference after them.
## Gate expectations changed, with physics
- `CrystalDepositionIslandTest`: flashTP at 95 K, 2 MPa is `LIQUID_LIQUID`, not `CRYSTAL_PHASES`. A step competes no crystal, and the two liquids are neither crystal-rich. The refresh's hold stays.
- `PrecipitatedSolidsTest` frosted tank: the gas stays one vapour until the refresh; no stand-in proposed, no own split.
- `PrecipitatedSolidsTest` two LNG tanks (not named by the plan, same cause): the vapour proposal came only from the competition freezing CO2. Carried, the tank is its one liquid, 903 and 280 Pa above its own bubble pressure, and nothing needs refusing. Seed 48's storm loses its cause.
- `NetworkPhaseEngineTest`: the state at 186 K answers V + L; its third phase was the stand-in. The hold stays tested on the fluid answer. `adopt` calls use `stepCompetition`.
- `SolidClosureFeasibilityTest`: 10.5 ms becomes D/v_s (located 0.05 s, tight 0.0025 s). Mass bound 2e-6 kg gains what a flow at the deposition velocity carries in the lateness (1.65e-4 kg moved). Filter and viscosity cases unchanged.
- Unchanged and passing: `CrystalFlushBudgetTest` at bound 10000, `CrystalIslandRuntimeTest`, `G3F7CarbonDioxideInLiquidMethaneTest`, `GasSolidEquilibriumTest`, `DataRangeContinuationTest`, `TangentPlaneStabilityTest`, `FluidTpEquilibriumTest`, `FluidTpEquilibriumPhUvTest`, `LiquidCo2LetDownTest`, `WaterIntoCryogenicMethaneTest`, `PumpCavitationTest`, `SpineNetworkPathTest`, `NearPureCrystalStepTest`. New: `CarriedCrystalsTest` (10 tests; a refresh's UV and PH equal today's to 15 digits).
## Risks in the plan
- A closure can pick a regime: yes (768 on (a), 683). Closure order is path-dependent; mass is conserved.
- 907: traced. Its first 10 holds are (b+)'s stall. Today the competition's crystal heats the mint by about 2 K and states junctions two-phase; without it they start subcooled, fall to 1 atm superheated, and flashing one node stalls Newton on edge 0->3 (residual 146). The other 9 are the same chain; carried tables clear them at 244's price. Physics: the liquid flashes at the first junction below its bubble point, about 1 MPa. The fix belongs in the balance start or the one-node proposal.
- 651's margin 2.7x. 244's job 57 no longer the worst, 2.7x. 683's stall at 0.354 is gone. 855's stall at 0.1059 is gone under G, not explained.
- Between frost line and metastable dew line a gas stays one vapour: `PrecipitatedSolidsTest` and `LiquidCo2LetDownTest` pass; no run to a floor seen.
## Open
1. 907's start stall (above). 2. PH steps at 40 % and 2.8 % CO2. 3. Why carried tables hold 244. 4. Campaign and final lane, then CHANGELOG, version, code guide.

Tools: `tools/dry-ice-cost/carried/`. Logs: main checkout, `tools/dry-ice-cost/logs/carried-crystals-logs-2026-11-03.tar.gz`.
