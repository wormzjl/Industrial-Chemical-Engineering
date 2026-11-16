# S1 engine review: band boxes 3 and 4 removed, the phase traits

Status: done 2026-11-15 on branch `claude/supercritical-fluid-support-d0ffa4` (owner decision O3 B, plan sections 2a,
2b and S1). Thermo core only; no file of `science/fluid/**` changed; `CriticalBandHold` stays.

## Expected behaviour

- A pure fluid above `Tc` has one cubic root at every pressure and cannot split. A band box there types no real
  failure. Removing boxes 3 (`Tr 1.05-1.2 x Pr 2-3`) and 4 (`Tr 1.0-1.1 x Pr 1.5-2`) should change only the band
  flag and the grade of answers whose feed is at least 95 % one compound in those boxes. It should change no phase
  amount, no classification outside that window, and no count of kernel evaluations: the band flag is read after
  the answer is formed.
- The traits gain one number, the attraction `a/(b R T)`. It is read from the `a` and `b` a phase state already
  stores, so no evaluation is added.

## What changed

- `PhaseTraits`: fifth component `attraction = a / (b R T)` (filled in `of`, `R` the kernel's gas constant);
  `supercriticalIsotherm()` = `attraction < PhaseIdentification.SUPERCRITICAL_ATTRACTION_RATIO`; `reducedDensity()` =
  `VAPOUR_BRANCH_VOLUME_RATIO / volumeRatio()`. No label site reads them yet (S2 will).
- `PhaseIdentification.SUPERCRITICAL_ATTRACTION_RATIO = 0.45724 / 0.07780 = 5.877120822622108`, the kernel's own
  `Omega_a / Omega_b` (`PengRobinsonKernel`'s constructor, `criticalA` and `coVolumes`). The brief and plan section 1
  state 5.8773599486044, the exact PR constants `0.457235528921 / 0.0777960739039`. The brief asked to check the
  digits against the kernel's constants and use those. They differ by 4.07e-5 relative:
  - With the kernel's ratio, a pure component's `a/(b R T)` at its `Tc` equals the ratio (alpha is 1 there), so
    `supercriticalIsotherm()` is exactly `T > Tc`.
  - Every isotherm below the ratio is also below the exact one, so the cubic has one root at every pressure.
  - With the exact constant, a pure component would read as supercritical from slightly below its `Tc`. For
    Soave's alpha that is about `4e-5 / m` relative to `Tc`.
- `FluidTpEquilibrium.pureBandBox`: the two lines of boxes 3 and 4 are deleted. It returns 0, 1 or 2. Its javadoc and
  the class comment's band paragraph are updated. `CriticalBand`: comment only.
- Tests:
  - `FluidTpEquilibriumP3Test`: the band test is renamed `theBandCarriesD15sWidenedBoxTwoAndNoSupercriticalBox`. Its
    box 3 and 4 assertions now assert 0. Three nitrogen states that were in box 4 moved from the "inside" list (band,
    `RESEARCH_ONLY`) to the "outside" list (no band, `ESTIMATED_DECLARED_ERROR`): 131.19 K and 5.10 MPa,
    `1.04 Tc x 1.75 Pc`, `Tc x 1.9 Pc`.
  - `G3F5CriticalRegionPathTest`: each D14-corner state must now lie outside the band (before: inside). These states
    are scored at D14's 9 % declared density error. The N2 paths at `Tc` + 0, 2 and 5 K have 34 corner states each,
    all outside; the worst is -8.02 % at 131.19 K and 5.10 MPa.
  - `PhaseTraitsTest`: on every converged digest answer, `attraction` agrees within 1e-9 relative with the
    cubic's own equation `P = R T/(v - b) - a/(v^2 + 2 b v - b^2)` at the root's `Z` and `v/b`. A supercritical
    isotherm has one root. A one-component phase has `supercriticalIsotherm() == T > Tc` away from `Tc` by more than
    1e-9 (95 such phases: 14 supercritical, 81 subcritical).
  - `G3F11SupercriticalRegionTest`: it now classifies its states by `G3Support.boxBeforeS1`, the four old boxes in the
    test, so its tables and `DECLARED` values do not move. `G3Support.bandBox` covers boxes 1-3; box 4 is added
    closed, as the engine had it. Both javadocs say these are the regions of larger declared error.
- Digest: re-recorded once (`ICHEME_ENGINE_DIGEST_RECORD=true`). The guide's `equilibrium-engine.md` and
  `thermo-kernel.md` now list the boxes and the trait fields.

## What moved (the engine digest, 429 requests)

| Line | Request | Grade (old -> new) | band (old -> new) | Classification |
|---|---|---|---|---|
| 31 | `test:methane_nitrogen tp 130.0 K 6.0 MPa x_CH4 0.0` (pure N2, Tr 1.03, Pr 1.77, old box 4) | `RESEARCH_ONLY` -> `RESEARCH_ONLY` (the research contract's own grade) | 1 -> 0 | `SUPERCRITICAL_FLUID`, unchanged |
| 83 | `pilot_cryogenic tp 131.19 K 5.10 MPa N2` (Tr 1.04, Pr 1.50, old box 4) | `RESEARCH_ONLY` -> `ESTIMATED_DECLARED_ERROR` (the contract's) | 1 -> 0 | `SUPERCRITICAL_FLUID`, unchanged |

On both lines every other field is the same, including `kernel=3`, the phase amounts and the state hashes. No line
outside the window moved. No other digest request was banded by box 3 or 4 alone, and no request changed
from `VAPOR_LIQUID` to `LIQUID_LIQUID`.

## What did not move

- Every other digest line (427 of 429). The network's calls: `NetworkPhaseEngineTest` (14) and
  `SpineNetworkPathTest` (7) are green and unchanged. `G3F1`, `G3F7`, `G3F8`, `G3F10`, `G3F11`, `FluidTpEquilibriumTest`,
  `FluidTpEquilibriumPhUvTest`, `SolidPhaseEvaluatorTest` and `EngineFailureKindTest` are green with no edit.
- `CriticalBandHold`, `DryCore`, `CarriedCrystals`, `BalanceSteps`, `SpecificationSearch` are unchanged. The tie-line and
  stationary-distance parts of the band are unchanged.
- No Rust twin: the engine is Java only.

## Verification

Run 1 (digest recorded):
`ICHEME_ENGINE_DIGEST_RECORD=true ./gradlew test --tests "*EngineDigestTest" --tests "*PhaseTraitsTest" --tests "*FluidTpEquilibriumTest" --tests "*FluidTpEquilibriumP3Test" --tests "*EngineFailureKindTest" --tests "*G3F1PureFluidOracleTest" --tests "*G3F5CriticalRegionPathTest" --tests "*G3F10QualifiedRegionTest" --tests "*G3F11SupercriticalRegionTest" --tests "*NetworkPhaseEngineTest" --tests "*SpineNetworkPathTest" --tests "*FluidTpEquilibriumPhUvTest" --tests "*SolidPhaseEvaluatorTest" --tests "*G3F7CarbonDioxideInLiquidMethaneTest" --tests "*G3F8ConservationAndDerivativeTest" --max-workers 4`.
The last four classes were added because they assert `RESEARCH_ONLY` or `criticalBand()`. Result: 75 tests, 1 failure
(G3F5, the 102 D14-corner states it asserted in the band). Run 2, after the G3F5 edit and the digest copy:
`./gradlew test --tests "*G3F5CriticalRegionPathTest" --tests "*EngineDigestTest" --max-workers 4`, 2 of 2 green.
chain-100 and the lane belong to the final lane.

## Open

- Plan section 1 and S2 quote 5.8773599486044 for the descriptor. S2 should read
  `PhaseIdentification.SUPERCRITICAL_ATTRACTION_RATIO`, the kernel's value, rather than restate it.
