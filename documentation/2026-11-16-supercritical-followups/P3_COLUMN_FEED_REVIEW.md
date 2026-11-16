# P3: the column's feed flash by a stability test (items 14, 15)

Batch `2026-11-16-supercritical-followups`, package P3 of [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md) section 5. Branch
`claude/hungry-nash-656c11`, base `3a48e08b4`. Owner options O1 B and O2 of
[COLUMN_FEED_REVIEW.md](../2026-11-15-supercritical-fluids/COLUMN_FEED_REVIEW.md). Form built: **D** (F3 with the
vapour rule, the main thread's decision, open to the owner's veto).

## Summary

1. **Expected.** A feed is one phase exactly where it is stable against a second phase (Michelsen's tangent-plane test).
   Where it is unstable it splits, and the split is the one the equilibrium engine finds on the same PR78 kernel and
   derived pairs. The column is a vapour-liquid stage model: the split it can carry is a vapour (or a supercritical
   fluid, which goes up the column as the vapour does) over a liquid. Two liquids are outside it, as free water is.
2. **What the code did.** `V3FeedFlash` classified a feed by the path of its iterates. At the Wilson endpoint (no
   two-phase bracket from Wilson's K) it answered one phase at iteration 0 without a test: 181 real splits of the
   review's dense grid were answered as one liquid (177 methane/heavy-cut feeds, 4 CO2/CH4 feeds with 0.4 to 6 %
   vapour). Where the iteration lost its root or ran out, it answered one phase only on a collapse of `|ln K|`: 102
   stable feeds stayed refused, and so did the 721 real splits that successive substitution does not converge.
3. **What was done (form D).** The flash asks `TangentPlaneStability` on the column's own kernel wherever it has no
   answer of its own: at the Wilson endpoint, and where the root is lost or the iterations run out. Stable: the
   endpoint's phase, or one phase by the label rule already there (owner O2 A), unchanged. Unstable: the iteration
   starts (or restarts once) from the test's stationary phase, `ln K_i = ±(ln W_i - ln z_i)`; a feed that still has no
   split takes the engine's split (`V3FeedEngine`: `FluidTpEquilibrium` on the derived evaluator, fluid phases only,
   open domain), its enthalpies on the column's roots. A split is taken only where its lighter phase is the column's
   vapour: reduced density below 1 (the slot rule), or on a supercritical isotherm of its own composition
   (`PhaseTraits.supercriticalIsotherm()`). Two liquids keep the endpoint's one liquid at the Wilson endpoint (old answer,
   to the bit) and are refused, naming two liquids, elsewhere. The collapse rule and `TRIVIAL_LOG_K` are deleted.
4. **Measured** (`tools/supercritical-probes/p3`, each form once on each grid; counts and counters only). On the dense
   grid's 10,602 states every form answers no real split as one phase, moves no frozen answer but the 181, and keeps
   every answer of the D1 collapse rule to the bit. F1 refuses 898, F2 891, F3 and D none. D is F3 on both grids: every
   split there has a vapour-like lighter phase (the methane-rich phase of the 177 is at reduced density 1.1 to 1.6 but on
   a supercritical isotherm). D differs from F3 only on the cold Tia Juana crude (below), where it keeps the old answer.
5. **Stop checks.** Column split against engine split on 2,082 dense states where both answer: `|Δ ln K| ≤ 9.4e-11`,
   `|Δβ| ≤ 7.8e-11`, inside the flash's step tolerance (1e-10). Wrong one-phase against the engine's `VAPOR_LIQUID`: 0.
   Moves outside the 181: 0. The crude states equal the frozen flash. No stop.
6. **Moved answers** (table below): the 181 one liquid → two phases; the 102 refused → one phase; the 721 refused → two
   phases. Bound: the engine's `FUGACITY_TOLERANCE` (1e-10) or the column's `LOG_K_TOLERANCE` (1e-10); each phase's
   shares close the feed's to 1e-12 in the tests.
7. **Cost.** Dense grid: 0.86 stability tests per feed (9,108 for 10,602 states; 22.7 kernel evaluations per test),
   0.084 engine calls per feed (891). Per column solve (temporary counters, removed): the literature CDU 12 flashes, 7
   stability tests, 0 tests on a failed iteration, 0 engine calls; the methane CDU 23, 1, 0, 0.
8. **Known limit, for the owner.** A feed that splits into two hydrocarbon liquids is still answered as one liquid at
   the Wilson endpoint. Its enthalpy is then off by the split's, about 1.1 to 1.2 kJ/mol on the cold crude.
9. **Native twin.** None: `src/main/rust` holds no flash code.
10. **Outside `science/column/v3`.** `build.gradle`: the column gate group loads `science/thermo/phase`,
    `TangentPlaneStability`, `SubstitutionAcceleration`, `NumericalBox`, `TranslatedCubic` (else `columnGates` misses
    the engine's classes). `ColumnFeedOnePhaseOracleTest` (in `test`): counts the newly answered CO2 vapour and checks
    the endpoint's one-phase answers against the engine. No engine class changed.
11. **Left for the final lane.** `columnGates` whole (the column examples, `ColumnSolveTest`) and the classes
    `affected.mjs select` names (47 in `test`; the gate groups and GameTests it lists). The campaign is not reached.
12. **Follow-up.** The cold Tia Juana crude's heavy-cut second liquid looks like an artefact of the derived pairs for the
    heaviest cut; the network's engine shows it too (a tank at 25 °C). Its own item, not P3's.

## The forms on the grids

Coarse grid (4 mixtures, 280 states) and dense grid (6 mixtures, 10,602 states) of COLUMN_FEED_REVIEW.md. "Wrong
one-phase": a feed the engine splits answered as one phase. "Moved": a frozen answer no longer the same to the bit. D's
counters are F3's paths (production is not instrumented; the probe counts them on its F3 copy).

| Grid | Form | Answered | Refused | Wrong one-phase | Moved (all) | Moved outside the 181 | Refused as two liquids | Stability tests | Kernel evaluations | Engine calls |
|---|---|---|---|---|---|---|---|---|---|---|
| coarse | F1 | 258 | 22 | 0 | 5 | 0 | – | 223 | 4,479 | 0 |
| coarse | F2 | 258 | 22 | 0 | 5 | 0 | – | 223 | 4,479 | 0 |
| coarse | F3 | 280 | 0 | 0 | 5 | 0 | – | 223 | 4,479 | 22 |
| coarse | D | 280 | 0 | 0 | 5 | 0 | 0 | as F3 | as F3 | as F3 |
| dense | F1 | 9,704 | 898 | 0 | 181 | 0 | – | 9,108 | 206,969 | 0 |
| dense | F2 | 9,711 | 891 | 0 | 181 | 0 | – | 9,108 | 206,969 | 0 |
| dense | F3 | 10,602 | 0 | 0 | 181 | 0 | – | 9,108 | 206,969 | 891 |
| dense | D | 10,602 | 0 | 0 | 181 | 0 | 0 | as F3 | as F3 | as F3 |

Why F1 and F2 refuse: at the Wilson endpoint Wilson's K have no root in (0, 1), so F1's iteration loses its root at
once. F2's start from the stationary phase converges 11 of the 902 unstable feeds; the rest stall again (damped
substitution with no acceleration or Newton finish has a rate near 1 near a critical point or between two dense
phases). The engine has both.

Why D and not F3: F3 split the cold Tia Juana crude (`icheme:cdu17_tjl_acs2018`, 298.15 K at 50 to 267 kPa, 323.15 K
at 110 to 267 kPa) into two liquids: the engine's `LIQUID_LIQUID`, a heavy-cut-rich phase of 7.7 to 9 % at reduced
density 3.80 to 3.84 over the rest at 3.61 to 3.66, β independent of pressure. F3 would send that heavy liquid up the
column as vapour. D keeps those feeds one liquid (unchanged to the bit) and takes the methane/heavy-cut splits, whose
lighter phase is 99 % methane on a supercritical isotherm. The grids hold no water; `V3FeedEngine` takes no feed with
water (the column keeps free water apart), so a wet unstable feed the iteration does not split stays refused.

## The moved answers by class (dense grid)

| Class | Count | Old answer | New answer | By | Enthalpy move |
|---|---|---|---|---|---|
| Methane/heavy cut (pc03), engine `LIQUID_LIQUID`, lighter phase supercritical | 177 | one liquid, Wilson endpoint | two phases, the methane-rich one as vapour, β 0.668 to 0.777 | the engine | +45 to +583 J/mol |
| CO2/CH4 90/10, engine `VAPOR_LIQUID` | 4 | one liquid, Wilson endpoint | vapour and liquid, β 0.0044 to 0.0638 | the column's iteration from the stationary phase | +21 to +382 J/mol |
| Stable feeds D1 refused | 102 | refused | one phase by the label rule (101 liquid, 1 vapour) | the stability test | – |
| Stalled real splits | 721 | refused | two phases: 441 `LIQUID_LIQUID` and 273 `VAPOR_LIQUID` by the engine, 7 by the column's iteration | as stated | – |

Of the 181, all 181 split under D; none keeps the endpoint answer (the methane-rich phases, at reduced density 1.1 to 1.6, by rule (2) only;
CO2/CH4 by rule (1) or (2)). Of the 721, all 721 are answered; none is refused as two liquids. Off the grids, the crude (40
states of `cdu17_tjl_acs2018` and `tjl19_dwsim`, 298 to 900 K, 50 to 267 kPa) is unchanged but for two 900 K feeds the
frozen flash refused, which D answers as stable vapour by the label rule.

## Tests

All pass (one filtered run, 12 classes, 34 tests, 0 failures):

- `V3FeedFlashStabilityTest` (new, column gates): the 181 (split), the 102 (one phase by the rule), every twentieth of
  the 721 (split), each at the probe's vapour fraction to 1e-6, balance closed to 1e-12, enthalpy the phases' own; the
  states in `feed-flash-stability-states.tsv` from the probe's dense output.
- `V3FeedFlashOnePhaseTest`: the stalled methane/ethane and methane/cut feeds take the engine's split (β 0.125210,
  0.791758); on the coarse grid the frozen answers unchanged to the bit, 5 moved from one liquid to two.
- `V3FeedFlashEquivalenceTest`: a frozen refusal is the same refusal, a one-phase answer or a split.
- `ColumnFeedOnePhaseOracleTest`: 13, 2, 13, 0 one-phase answers; every one-phase answer, the endpoint's too, is one
  phase by the engine.
- `V3PengRobinsonThermoTest` (the crude feeds), `V3PengRobinsonKernelTest`, `RegroupedCrudeTest`,
  `V3ColumnFeedAndDrawLimitsTest`, `V3InteriorPhaseIdentityTextTest`, `CrystalPackageConsumersTest`,
  `V3AqueousColumnTest`, `V3FlashTruncationColumnTest`.
