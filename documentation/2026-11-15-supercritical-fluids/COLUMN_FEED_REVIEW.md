# Column feed flash above the mixture critical point (D1, D2)

Batch `2026-11-15-supercritical-fluids`, limits review rows D1 and D2. Branch `claude/supercritical-column-feed-b49e05`, cut
from `8a870f228`. Code: `science/column/v3/thermo/` (`V3FeedFlash`, `V3PengRobinsonThermo`, `V3PengRobinsonSession`). D2's
text is in `science/column/v3/V3AcceptanceAuditor` (text only). `build.gradle`: the column gate group loads
`PhaseTraits` and `PhaseIdentification`.

## Summary

1. **Expected.** A feed with no two-phase solution at its temperature and pressure is one fluid. Above its mixture critical
   point this is the only answer. The column needs its enthalpy and one label, liquid or vapour, to send it down or up from
   the feed tray. A tray whose liquid and vapour are one fluid cannot exist in a two-phase stage model: it stays a typed
   refusal (a capability limit), and the text names the cause.
2. **What the code did.** `V3FeedFlash` brackets two phases with Wilson K-values. Successive substitution on the PR78
   K-values then moves them toward 1 (the trivial solution). The Rachford-Rice root leaves (0, 1) at the vapour end, and
   the flash throws `FLASH_NONCONVERGENCE` "lost its two-phase Rachford-Rice root". A slower collapse runs out of its 64
   iterations instead. Most supercritical feeds lose the root at iteration 2 to 5, with the largest `|ln K|` still 0.3 to 2.6.
3. **The fix (in the flash's own classification).** The flash records whether the largest `|ln K|` of the feed's components
   falls at every iteration (a collapse). If the root is lost during a collapse, or if the iterations run out during a
   collapse with `|ln K| <= 1e-5`, the feed is one phase. The overall composition is evaluated on its root. One physical
   root: liquid where the reduced density `v_c / v` is at least 1, else vapour (`PhaseTraits`, owner decision O2 A). Two
   roots: the root of lower Gibbs energy (`sum z_i ln phi_i`). The flash returns `V3FlashResult.liquid` or `.vapor` with
   the feed's enthalpy on that root. Its detail is "one phase, no two-phase split", then "above" or "below the mixture's
   pseudo-critical temperature" (whether the isotherm is supercritical for the mixture's own `a` and `b`), the label and
   the reduced density. The brief's wording, "one fluid above the mixture critical point", was dropped after a
   measurement. The pseudo-critical isotherm is not the mixture's critical point: a crude still splits above it, at 900 K
   and 0.5 MPa. A dilute crude vapour at 901 K and 0.25 MPa also met the rule, at reduced density 0.044.
4. **What moved.** Nothing that was answered before. The rule acts only where the old flash threw. The collapse is only
   read during the iteration. On the review grid, every answered feed has the same phase, iterations, detail, vapour
   fraction, enthalpy, compositions and next `ln K` (the frozen flash `V3FeedFlashReference`). The one change in a refusal:
   above its critical point, a tray whose liquid and vapour collapse used to fail its audit as `UNAVAILABLE` with the flash's
   "lost its two-phase root". The audit's own flash now answers, so the tray fails `INTERIOR_PHASE_IDENTITY` with the new
   text (D2).
   One existing assertion changed. `V3PengRobinsonThermoTest` expected the Tia Juana crude at 901 K and 250 kPa to be
   refused, as a request "out of domain". The temperature 901 K is outside the package's data range, and AGENTS.md says a
   data range never refuses. The feed is stable by the tangent-plane test on the column's kernel (TPD 0), so it is one
   vapour beyond its dew point. The old refusal was this defect: the root was lost at iteration 2 with `|ln K|` 5.3. The
   test now asserts the vapour answer. It also asserts a real domain refusal: 7000 K, outside the numerical box, gives
   `DOMAIN`. The crude's neighbours confirm the rule there. At 850, 880, 900 and 901 K and 0.5 MPa the stability test finds
   the feed unstable, and the flash splits it (vapour fraction 0.95 to 0.99), unchanged. At 920 K and 0.5 MPa the feed is
   stable, and the rule answers it as vapour (`tmp/supercritical-column/crude-probe-result.xml`).
5. **The assumption.** A collapse from a Wilson start is not a stability test. The grids measure it against the equilibrium
   engine (`FluidTpEquilibrium` on the same PR78 kernel and derived pairs). On 10,602 states, no feed that the engine splits
   meets the rule. Every refused real split runs out of iterations with `|ln K|` of 0.1 or more, which does not fall at every
   step. The miss count is zero, so no option is set to the owner for the rule itself.
6. **Still refused (options below).** 101 dense-grid one-phase feeds collapse too slowly: at 64 iterations
   `1e-5 < |ln K| <= 1`. One subcritical vapour (CO2/CH4 90/10, 250 K, 2 MPa, three roots) loses the root while its K-values
   diverge. The equilibrium engine finds 721 real splits that the flash cannot converge; these stay
   `FLASH_NONCONVERGENCE`, as required.
7. **A finding outside the brief.** Feeds that the engine splits are answered as one phase by the Wilson endpoint test at
   iteration 0 (181 dense-grid states: 177 methane/heavy-cut states, 4 CO2/CH4 states). The old flash answered these. They
   stay unchanged.
8. **Native twin.** None. `src/main/rust` holds the math and the banded solver. `icheme-jni/src/layout.rs` holds only the
   layout identity string. No other copy of the feed flash exists.

## The measurement

The probe ran every state three ways. `V3PengRobinsonThermo.flashTP` gave the answer or the refusal. An instrumented copy of
the flash, on the same kernel (`DerivedPairs.derive(...).kernel` over the numerical box, the kernel
`V3PengRobinsonSession.kernelFor` builds), gave the iteration and the largest `|ln K|` where the flash leaves. The equilibrium
engine, on an open fluid-only contract over the same components (`PackageBinding.derivedEvaluator`), was the oracle. The
probe is kept outside the tree, in `tmp/supercritical-column/ColumnFeedGridProbeTest.java.txt` with its output.

The packages and components:

- Methane/ethane, the natural gas and methane with a heavy cut use `icheme:tjl20_methane_nitrogen`.
- n-decane is not in the catalog. `crude_pc03` (normal boiling point 431.8 K, M 0.132 kg/mol) stands in for it.
- Carbon dioxide is only in `icheme:refinery`, and the column refuses that package because it admits the CO2-I crystal. A
  test package `extends icheme:tjl20_methane_nitrogen` and adds `CarbonDioxide` (`chemsep_carbon_dioxide`). It has the
  same method and pairs, and no crystal.

### Coarse grid: 4 mixtures, 250 to 400 K in steps of 25 K, 10 pressures from 2 to 30 MPa (280 states)

| Mixture | Engine | Answered before | Refused before | Answered now | Still refused |
|---|---|---|---|---|---|
| CH4/C2H6 50/50 | 68 one, 2 split | 56 (1 two-phase) | 12 lost root (collapse), 1 out of iterations (collapse, `ln K` 1e-11), 1 out of iterations (split, `ln K` 0.48) | 13 | 1 real split |
| CO2/CH4 90/10 | 70 one | 68 | 1 lost root (collapse), 1 lost root (K diverging, three roots) | 1 | 1 subcritical vapour |
| Natural gas (CH4 0.90, C2H6 0.06, C3H8 0.03, N2 0.01) | 70 one | 57 | 13 lost root (collapse) | 13 | 0 |
| CH4/pc03 90/10 | 18 liquid-liquid, 52 vapour-liquid | 54 (49 two-phase) | 16 out of iterations (split, `ln K` 1.8 to 5.3) | 0 | 16 real splits |
| **Total** | | **235** | **45** | **27** | **18** |

Every lost root is at the vapour end (`beta -> 1`). Of the 26 roots lost during a collapse, 20 are lost by iteration 5,
with `|ln K|` still 0.04 to 2.6. The other 6 are lost at iteration 16 to 62, with `|ln K|` 1e-12 to 4e-2.

### Dense grid: 6 mixtures, 250 to 400 K in steps of 5 K, 2 to 30 MPa in steps of 0.5 MPa (10,602 states)

The grid also holds CH4/C2H6/C3H8 40/30/30 and CH4/C3H8 70/30, chosen for their wide envelopes near the dew points.

| Class | Count | Rule |
|---|---|---|
| Engine one phase, answered before | 6,006 | unchanged |
| Engine split, answered before (two-phase) | 1,180 | unchanged |
| Engine split, answered before as one phase (Wilson endpoint) | 181 | unchanged (finding 7) |
| Engine one phase, root lost during a collapse | 2,152 | answered now |
| Engine one phase, out of iterations during a collapse, `ln K <= 1e-5` | 260 | answered now |
| Engine one phase, out of iterations, `1e-5 < ln K <= 1` | 101 | still refused (option O1) |
| Engine one phase, root lost with K-values diverging | 1 | still refused (option O1) |
| Engine split, out of iterations (`ln K >= 0.1`) | 721 | still refused (typed) |
| **Engine split, answered as one phase by the rule** | **0** | |

At 64 iterations, the `|ln K|` of the slow one-phase collapses spreads over every decade from 1e-11 to 1. The stalled real
splits all have `|ln K| >= 0.1`. The bound, `sqrt(LOG_K_TOLERANCE) = 1e-5`, is four decades below the smallest stalled split
and five above the flash's step tolerance. Within it, the two phases differ by at most 1e-5 in `ln(y/x)`.

## Enthalpy: the column against the engine

Both models evaluate the same untranslated PR78 kernel with the same pairs. The residual enthalpies agree to rounding
(`ColumnFeedOnePhaseOracleTest`: the column's `h^R` equals the engine's `h^R + P c` less `P c`, to 1e-6 relative). They differ
in two terms:

- the engine's constant volume translation `P c` (`TranslatedCubic`): `c` is about 3.7e-6 m3/mol for these gases, so about
  110 J/mol at 30 MPa;
- the ideal-gas parts: the column's degree-five Cp fit against the engine's `CoefficientIdealGas`.

On the dense grid, over the one-phase feeds that were answered before, `H(engine) - H(column)` is 3.5 to 140 J/mol.

Three feeds answered now (the oracle test's printout):

| Feed | Label | Iterations | Reduced density | Detail |
|---|---|---|---|---|
| CH4/C2H6 50/50, 300 K, 10 MPa | vapour | 4 | 0.961 | above the pseudo-critical temperature |
| CH4/C2H6 50/50, 300 K, 12 MPa | liquid | 10 | 1.197 | above the pseudo-critical temperature |
| Natural gas, 250 K, 6 MPa | vapour | 2 | 0.453 | above the pseudo-critical temperature |

| Feed | H, column (J/mol) | h^R, column | h^R + P c, engine, less P c | Engine label (identification parameter) |
|---|---|---|---|---|
| CH4/C2H6 50/50, 300 K, 10 MPa | -4474.35 | -4556.36 | -4556.36 | liquid (column: vapour by reduced density) |
| CH4/C2H6 50/50, 300 K, 12 MPa | -5220.75 | -5302.76 | -5302.76 | liquid |
| Natural gas, 250 K, 6 MPa | -3796.82 | -1974.94 | -1974.94 | vapour |

The label is the network's slot rule, applied to the engine's own phase traits. The engine's label uses the phase
identification parameter, so it can differ near reduced density 1. For example, the natural gas at 250 K and 10 MPa has
reduced density 0.933: the engine labels it liquid, and the column and the network label it vapour (O2 A).

## D2: a tray at or above its mixture critical point

Where it is refused: `V3AcceptanceAuditor.interiorPhaseIdentity` (the `INTERIOR_PHASE_IDENTITY` audit). Its triggers are a
tray with positive liquid and vapour flows, `x = y` to the closure limit, the same compressibility and the same enthalpy,
and a flash of the combined flow that is not two-phase. `V3ThermoModel` and `V3PengRobinsonSession` hold no refusal of their
own for coinciding roots. With one physical root, both the liquid request and the vapour request return that root.
`StillColumn` is outside this column: it treats the trivial solution as one phase. The player reads the audit's detail in
the column's Solver report. It is English text built on the server, not a lang key, so `en_us.json` is unchanged.

| | Text |
|---|---|
| Before, above the critical point (the audit's flash threw) | `UNAVAILABLE`: "V3 feed flash lost its two-phase Rachford-Rice root" |
| Before, other collapses | `INTERIOR_PHASE_IDENTITY`: "tray 5 carries the same phase as both liquid and vapor; x-y=…, Z separation=…" |
| After | `INTERIOR_PHASE_IDENTITY`: "tray 5 has no liquid and vapour to separate: it is at or above its mixture's critical point (one phase, no two-phase split above the mixture's pseudo-critical temperature: liquid by reduced density 1.197); lower the pressure or change the cut; x-y=…, Z separation=…" |

There is no solver change. The parentheses hold the flash's own one-phase detail.

## Options for the owner

- **O1. The feeds still refused as one phase** (101 slow collapses with `1e-5 < |ln K| <= 1`, and the diverging subcritical
  vapour).
  - (A) Keep them refused. This is the current state.
  - (B) For a feed whose bracket is lost or whose iterations run out, take the answer of the engine's stability test
    (`TangentPlaneStability` on the column's kernel). A stable feed is one phase by the label rule above. An unstable feed
    stays refused. This answers all 102. It costs one stability test per refused flash, and it is a stability test, not a
    reading of the iterates.
- **O2. The 181 one-phase answers of real splits by the Wilson endpoint test** (finding 7). These were answered before the
  batch. Changing them moves answered results, so it needs its own decision. The same stability test (O1 B) at the Wilson
  endpoint would send them into the two-phase iteration, where most of the methane/heavy-cut states stall (as their 573
  neighbours do).

## Tests

- `V3FeedFlashOnePhaseTest` (column gates):
  - the label on both sides of reduced density 1, with the feed's own enthalpy on the labelled root, bit for bit;
  - a trivial solution on a subcritical isotherm;
  - a collapse that runs out of iterations;
  - a natural gas lost at iteration 2;
  - a real split that does not converge stays refused;
  - over the coarse grid, against the frozen flash: 167 answers bit for bit, 26 refusals answered now, 17 real splits
    refused as before.
- `V3FeedFlashEquivalenceTest`: a frozen refusal is now the same refusal, or an answer of the rule.
- `V3PengRobinsonThermoTest`: the hot crude is answered as vapour, and a state outside the numerical box is refused as
  `DOMAIN` (see "What moved").
- `V3InteriorPhaseIdentityTextTest` (column gates): the D2 text.
- `ColumnFeedOnePhaseOracleTest` (`test`): on the four coarse mixtures, every feed the rule answers is checked against the
  engine. The engine finds it one phase, the label is the slot rule on the engine's traits, and the residual enthalpy agrees
  to 1e-6 relative.
