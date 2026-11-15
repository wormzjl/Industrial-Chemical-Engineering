# Supercritical fluids: plan

Status: proposed 2026-11-15, base `e9edc974b` (main 0.31.0). Nothing is built. The limits it answers:
[SUPERCRITICAL_LIMITS_REVIEW.md](SUPERCRITICAL_LIMITS_REVIEW.md) (ids A1..D4 below). Stages S1 to S5 wait for the
owner's decisions O1 to O7; S0 needs only O1.

## 1. The idea

A supercritical fluid is one phase whose properties are continuous. So the plan adds no phase, no slot and no saved
field. It does three things:

1. **One descriptor.** A node is a *dense one-fluid node* when it holds one fluid phase on one root and its isotherm
   is supercritical: `a/(b R T) < SUPERCRITICAL_ATTRACTION_RATIO` (`PhaseIdentification`, since S1: the kernel's own `0.45724 / 0.07780` = 5.87712, so that a pure component's test is exactly `T > Tc`; the exact PR ratio is 5.87736) for the phase's mixture `a`, `b` on the untranslated cubic (the
   cubic then has one root at every pressure for this composition; for a pure fluid this is exactly `T > Tc`). The
   constant is the kernel's own (`PhaseIdentification.VAPOUR_BRANCH_VOLUME_RATIO`'s comment); `Z` and `v/b` are on
   `PhaseTraits` since 0.31.0, and `a`, `b` are on the phase state it is formed from. No mixture critical point is computed, and no averaged critical constant is read.
2. **Laws read traits, not labels.** Every law that asks "gas or liquid" of such a node reads its reduced density
   `δ = 3.9513730355914 b / v` (1 at the cubic's critical volume) or a typed "no saturation". Subcritical nodes keep
   their laws bit for bit, because a subcritical one-root phase never has a supercritical isotherm.
3. **The band answers instead of holding.** A pure fluid above `Tc` and `Pc` cannot split, so its boxes go. A
   near-critical split that does not converge is answered as one fluid with a notice.

Ruled out:

- A fourth slot or a saved "supercritical" amount: a facts change with no physical content (the amounts are one
  phase's), and every layout, codec and view would grow.
- A mixture critical-locus calculation: heavy, and not needed to decide any law above.
- Pure-component or mole-averaged critical constants as the test for a mixture: wrong for stability (earlier review,
  [../2026-09-24-coolprop-low-temperature/SUPERCRITICAL_SUPPORT_REVIEW.md](../2026-09-24-coolprop-low-temperature/SUPERCRITICAL_SUPPORT_REVIEW.md)).
- A supercritical tray in the column or the still: two phases are its construction. It stays a typed refusal.

## 2. Owner decisions

| Id | Decision | Options (first is the recommendation) |
|---|---|---|
| O1 | Which fluids and uses first | **A** dense gases and carbon dioxide, propane, ethylene, ethane: lines, vessels, pumps, compressors, exchangers. **B** A and supercritical water and steam (boilers, turbines to 30 MPa): needs a water model above 647 K, see S6. **C** A and process units (solvent deasphalting, extraction): see S7 |
| O2 | Slot of a dense one-fluid node (storage and display row only, once S2 is in) | **A** by density: oil slot when `δ ≥ 1` (the critical isochore, through the critical point; the trait the laws use). **B** keep `PIP > max(1, Z)`. Both agree on every one-root state inside today's data ranges (`PhaseIdentification`'s class comment), so subcritical references stay exact either way |
| O3 | The critical band | **A** remove boxes 3 and 4; a failed in-band split is one fluid on its lower-Gibbs root, graded research-only, with a status notice; `CriticalBandHold` is deleted. **B** remove boxes 3 and 4 only; other in-band failures still hold. **C** no change. A's cost: a split with `Σ z (ln K)² < 0.1` is not separated; mass and energy stay exact, the node's density is off by at most the two phases' gap (to measure in S0) |
| O4 | Pressure ranges | **A** raise each qualified dataset's `pressure_max_pascal` to the pressure S0 qualifies (target 30 MPa for carbon dioxide and the light gases). **B** leave 2 and 10 MPa; dense states run with an "outside data range" notice and the 10 MPa input box |
| O5 | Dense viscosity | **A** dilute viscosity plus the Jossi-Stiel-Thodos residual in `δ` (needs `Tc`, `Pc`, molar mass only; no new data), for dense one-fluid nodes. **B** keep the dilute value (about half the real one for carbon dioxide at 320 K, 10 MPa; to measure) |
| O6 | Mathias-Copeman curvature step at `Tc` (A6, X34) | **A** leave it and state its size from S0. **B** a C2 blend over a few kelvin |
| O8 | Pairs of a crude cut with a light gas (today zero) | Options A to D of [SUPERCRITICAL_DATA_REVIEW.md](SUPERCRITICAL_DATA_REVIEW.md), P5; sized in S0 before the choice |
| O9 | A missing pair between real compounds | **A** the E-PPR78 table already in the data. **B** zero, as today. Moves every mixture with such a pair; sized in S0 |
| O10 | Critical volume | **A** an appended dataset field (ChemSep for real compounds, the cut's own measured density for a cut). **B** the cubic's own value |
| O7 | A dense-phase distribution in the hold-fuzz campaign | **A** none; three GameTests and the gates of S2 judge. **B** add one distribution (more campaign time on every later batch) |

## 2a. After S0 (2026-11-15): what the measurement changes

S0 is done ([SUPERCRITICAL_QUALIFICATION_REVIEW.md](SUPERCRITICAL_QUALIFICATION_REVIEW.md); the main thread checked the
test results, the Gradle logs and the diff: no file under `src/main` changed). The recommendations of section 2, revised:

| Id | Measured | Recommendation now |
|---|---|---|
| O3 | No request failed on 36 isotherms of four binaries. Answering one fluid at the first tie line below 0.1 is off by -2.7 to +17 % in density (the two products still differ up to 2.4 times); at a tie line of 0.03 or less, within 1 % (two states) | **B**: remove boxes 3 and 4 only; an in-band failure stays a hold. A is not taken as written. If S2's GameTests meet such a hold, a fallback below a tie line of 0.03 is the option to measure then |
| O4 | Outside the band, density within 7 % and `cp` within 12 % to 30 MPa for carbon dioxide, nitrogen, methane, ethane, propane, and hydrogen from 273 K; ethylene but one state (`cp` -12.1 % at the edge of box 1); bundled n-hexane to 4.6 MPa, toluene to 6.2 MPa. Mixtures (three binaries, 105 states): within 5.0 % | **A**, with the liquids measured (M8, 2 to 30 MPa): **30 MPa** for carbon dioxide, nitrogen, propane (liquids hold their bounds to 30 MPa), methane, ethane (no liquid state inside their 293.15 K domain floor) and hydrogen (from 273 K); **ethylene 20 MPa** with `cp` declared at 13 % (edge of box 1) and 14 % (cold liquid at 141 K, an error it already has at 2 MPa), or 2 MPa with nothing declared; **n-hexane 4.6 MPa, toluene 6.2 MPa** (their supercritical density limit). Pressure does not degrade a liquid: near `Tc` the density error is largest next to saturation (-3 to -8 %, present at today's 2 MPa) and is +1 to +3.6 % at 30 MPa |
| O5, O10 | Dilute viscosity is 13 to 96 % of the reference (carbon dioxide at 319 K, 10 MPa: 49 %). With the residual: -16 to +8 % on the reference critical density, -3 to +28 % on the cubic's | **O5 A with O10 A** (the critical volume as a dataset field). The published coefficients are a tenth of those in S0's brief; the test uses the published ones |
| O6 | Only water has a Mathias-Copeman alpha; its `cp` step at `Tc` is 1.5 % at `Pr` 0.5 and 4.4 % at `Pr` 2 | **A**: leave it and declare it |
| O8 | The network's cut-gas pairs exist already (E-PPR78 on paraffinic counts); hydrogen is 0 by the owner's rule | Closed. Hydrogen's rule is the owner's, and matters only for hydrotreating at pressure |
| O9 | 197 nonpolar pairs at zero; E-PPR78 gives -368 at 500 K for one of them | **B**: leave zero |
| Water (S6) | The bare cubic: -30 % density at 650 K and 25 MPa; within 5 % from 700 K; `cp` within 6 % from 800 K; enthalpy 550 to 750 J/mol low at 800 to 900 K | S6 is needed only for 647 to about 750 K; turbine inlet states (800 K and above) are already usable |
| Cuts | Surrogates: pc01 and pc02 within 5.7 % to 30 MPa, pc03 to 20 MPa, pc04 to 10 MPa; the error grows with the acentric factor | Ranges for pc01 to pc04 by surrogate; pc05 to pc12 stay at 2 MPa and `EXTRAPOLATED` above |

Section 2's table keeps the options as first written; this table is the current recommendation. S1 with O3 B is one
edit (`pureBandBox`) plus the trait fields; `CriticalBandHold` stays.

## 2b. Decisions (owner, 2026-11-15: "Accept all")

The owner accepted the recommendations of section 2a as they stand: O1 A; O2 A (a dense node's slot by density); O3 B
(band boxes 3 and 4 removed, an in-band failure stays a hold); O4 A with M8's ranges (30 MPa for carbon dioxide,
nitrogen, methane, ethane, propane, hydrogen; ethylene 20 MPa with `cp` declared at 13 and 14 %; n-hexane 4.6 MPa,
toluene 6.2 MPa); O5 A with O10 A; O6 A; O7 A; O9 B. O8 is closed.

Two findings of the main thread after the acceptance, from S0's per-state rows and the code, stated so the owner can
overrule:

1. **Boxes 3 and 4 are also where the model is least accurate above `Tc`.** In them the density error is 6.8 to 10.9 %
   and `cp` to 13 % (carbon dioxide 8.7 %, ethane 9.7 %, propane 10.2 %, ethylene 10.9 % in density), against 5.6 % and
   11 % outside every box. The network reads no grade (only `diagnostics().criticalBand()` on a failed answer), so
   removing the boxes changes no number a player sees; it changes the grade and the band flag of answers there, the
   type of a failure there (a rejected trial where it was a typed hold), and one label: a split of a feed that is 95 %
   one compound, whose two products are both dense, is `LIQUID_LIQUID` there as it is everywhere else outside the
   band (`DryCore.twoPhaseResult`, `CarriedCrystals.carriedSplit`). O3 B is carried out as accepted; the datasets'
   evidence text declares 11 % density and 13 % `cp` for `Tr` 1.0 to 1.2 at `Pr` 1.5 to 3.
2. **A "dense one-fluid node" is every one-phase node above its mixture's cubic critical temperature, so it includes
   ordinary gases** (nitrogen, methane or hydrogen at ambient temperature and any pressure). Every law S2 changes for
   such nodes therefore acts on states the references hold today (chain-100, the junction lines, the GameTests, the
   campaigns). S2 keeps them exact by making each new law identical, bit for bit, to today's below a reduced density
   (the residual viscosity is zero there, the film is the gas film, a liquid port stays dry), with a C1 blend above it.
   The threshold and the blend's width are an assumption the physics does not force: S2's review gives them as an
   option with numbers. The plan's S2 text "each only for `State.dense()`" is read with this rule.

## 3. Stages

Each stage is one package for one thread, on its own worktree, in the lane named. Line numbers are not given: the
files were split in 0.31.0, so each brief names symbols.

### S0. Measure (no production code)

Goal: the numbers the decisions and the declared errors rest on. The 2026-09-24 batch measured the supercritical
region to 10 MPa on the pilot engine (density within 5.9 %, `cp` within 6.6 %, 10.6 % next to box 1); S0 measures the
network's model and the range above 10 MPa, and does not repeat that work. Data per need, the crude cuts and the
rule for a lack: [SUPERCRITICAL_DATA_REVIEW.md](SUPERCRITICAL_DATA_REVIEW.md).

- Extend `tools/coolprop-parity/` and its tables under `src/test/resources/science/thermo/coolprop/` with a
  supercritical grid per fluid: `Tr` 1.0 to 2.0 (steps 0.02 to 1.2, then 0.1), `Pr` 0.5 to 4, for carbon dioxide,
  nitrogen, methane, ethane, hydrogen (present tables), plus propane and ethylene if O1 A names them, plus water for
  sizing S6.
- New qualification test `G3F11SupercriticalRegionTest` (`src/test/java/.../science/thermo/qualification/`, pattern of
  `G3F1PureFluidOracleTest` and `G3F10QualifiedRegionTest`): the network's model (`HydrocarbonModel` on the derived
  cubic) against the tables for density, enthalpy difference from the ideal gas, `cp`, and the isothermal acoustic
  bound; prints the worst and the 95th percentile error per fluid and per reduced box. It asserts only the declared
  errors it prints (recorded once).
- In the same test: the `cp` step at `Tc` (A6), the gap between the two products of a split at
  `Σ z (ln K)² = 0.1` on three binaries (O3 A's bound), the dilute against the reference viscosity (O5).
- Crude cuts: the model against surrogate reference fluids at equal reduced state (n-hexane, n-heptane, toluene,
  n-decane, n-dodecane for pc01 to pc04); pc05 to pc12 have no surrogate and stay `EXTRAPOLATED`.
- Pairs: the bubble pressure of a crude with 1 % of carbon dioxide, nitrogen, hydrogen sulfide, hydrogen or methane
  at 0.5, 2 and 10 MPa, with zero and with each option of O8 and O9.
- Read (no run) the campaign distributions in `tools/` and state whether any seed reaches `Pr > 1` with `Tr` in 1 to 1.3.

Commands: `./gradlew test --tests "*G3F11SupercriticalRegionTest"` (one run; it loads only the thermo core).
Done: `SUPERCRITICAL_QUALIFICATION_REVIEW.md` with one table per fluid and a recommended pressure range per dataset.

### S1. Engine: the band and the descriptor (thermo core lane)

Files: `science/thermo/phase/FluidTpEquilibrium.java` (`pureBandBox`), `CriticalBand.java`, `DryCore.java`
(`hydrocarbon`, `merged`, `failed`), `PhaseTraits` and `PhaseIdentification.java`, `EquilibriumResult.java`.

1. `science/thermo/PhaseTraits.java` (a record of root count, `Z`, `v/b`, the identification parameter): add the
   component `attraction` = `a / (b R T)`, filled in `PhaseState.traits` from the `a`, `b` that
   `CubicPhaseEvaluator.fill` already stores through `setIdentification`; `supercriticalIsotherm()` =
   `attraction < PhaseIdentification.SUPERCRITICAL_ATTRACTION_RATIO` (5.87712, the kernel's ratio; S2 reads the constant); `reducedDensity()` = `VAPOUR_BRANCH_VOLUME_RATIO / volumeRatio()`. No evaluation
   added. `PhaseTraitsTest` checks the new component on every converged digest answer.
2. `pureBandBox`: delete boxes 3 and 4 (O3). `G3F10QualifiedRegionTest` moves with them; the grade inside the old
   boxes becomes the contract's, or `EXTRAPOLATED` past the dataset's pressure.
3. O3 A: in `DryCore`, the path that today returns `failed(...)` with the `CRITICAL_BAND` detail returns the `merged`
   answer (one `FLUID` phase on the feed's lower-Gibbs root, `Diagnostics.criticalBand` true, `RESEARCH_ONLY`).
   `EquilibriumResult.failedInCriticalBand` and its constructor argument go; `FailureKind` is unchanged.
4. Pitfalls: `UNSTABLE_PRODUCT` and `UNRESOLVED_PRODUCT` are not band failures and still hold (a fourth phase). The
   three-slot path (`ThreeSlotFlash`) has its own audit: only its two-slot fallback's band failure is merged. A merged
   answer must not seed a later split (`Workspace.ownSplit` false).
5. Rust twin: none (the engine is Java only; the Rust library is the column's).

Tests: `./gradlew test --tests "*EngineDigestTest" --tests "*FluidTpEquilibriumTest" --tests "*FluidTpEquilibriumP3Test" --tests "*PhaseTraitsTest" --tests "*EngineFailureKindTest" --tests "*G3F5CriticalRegionPathTest" --tests "*G3F10QualifiedRegionTest"`.
The digest proves that only banded requests move: the review lists each moved line with its old and new status.
Done: no digest line outside the band moves; no request ends `CRITICAL_BAND`.

### S2. Network: one mapping, laws on traits (fluid step lane; after S1)

Files: `science/fluid/thermo/FluidThermodynamics.java` (`adopt`, `placed`, `State`), `HydrocarbonModel.java`,
`NetworkStreams.java`, `NetworkWater.java`, `WaterSaturation.java`, `ThermoHold.java`;
`science/fluid/network/` `HeatCurve.java`, `MoverSuction.java`, `ReleasePressure.java`, `PhaseDraw.java`,
`StepEquations.java`, `PassiveStepSolver.java` (mover row), `Pool.java`; `science/fluid/solver/PhaseLayout.java`.

1. `State.dense()`: true when one fluid slot is active, its phase has one root and a supercritical isotherm.
   `State.reducedDensity()` of that phase. Computed from the phase record the state already holds.
2. `placed`: a `FLUID` phase with a supercritical isotherm goes to the slot O2 names; every other phase as today.
   `adopt`: the `CriticalBandHold` branch goes with O3 A; a merged answer adds the notice
   `near a critical point: phases merged` through `DataRangeNotices`. `ThermoHold` permits `UnsupportedPhases` only.
3. "No saturation": `NetworkWater.saturationPressure` and `WaterSaturation.pressure` return a typed absence at and
   above `Tc` in place of `+∞`; `MoverSuction`'s release pressure returns the same absence for a dense node. Each
   caller (grep `saturationPressure`, `releasePressure`) states what absence means: no suction floor, no cavitation,
   no flashing, superheated.
4. Laws, each only for `State.dense()`:
   - `HeatCurve` films: the knot's film is the liquid film at `δ ≥ 1.5`, the gas film at `δ ≤ 0.5`, a C1 blend
     between (the bounds are an assumption: an owner option in the stage's review, with the films at three states).
   - `NetworkStreams.phaseVelocityLimit`: min(configured, acoustic) from the phase's own compliance.
   - `PhaseDraw` and the ports: a dense node has no interface, so `VAPOR`, `LIQUID` and `BOOT_HC` ports draw it;
     `BOOT_WATER` does not. Level head: the fluid's density over the vessel's height.
   - The mover row's heat capacity ratio: the phase's own (`NetworkStreams.heatCapacityRatio`) in place of 1.4.
   - `boilUp`, `Pool`: a dense pool does not boil: zero rate, no division by the latent heat.
5. `MixtureViscosity.liquidSource`: with O5 A the supercritical slot rule (`VAPOR_DATA` for the oil slot) is deleted;
   a dense node reads dilute plus residual from either slot. `MixtureViscosity.revision` moves.
6. `BalanceStart`: left (a seed). Changed only if S2's GameTests show a cold-start hold.
7. Pitfalls: a node next to the slot line changes its active slot between passes, so the layout must not treat the
   change as a phase appearing (`PhaseActiveSet`, `PhaseLayout.support`); `HydrocarbonModel.RootAbsent.liquidBesideVapour`
   must use the same rule as `placed`; the prepared refill must keep its bits (`PreparedRefillTest`); a revision key
   `dense-traits-v1` joins `FluidThermodynamics.revision()`, so saved state rebuilds once from the facts (state, not
   facts: no corpus image).

Tests: `./gradlew test --tests "*FluidThermodynamicsTest" --tests "*NetworkPhaseEngineTest" --tests "*HydrocarbonModelTest" --tests "*HeatCurveTest" --tests "*PreparedRefillTest" --tests "*DataRangeContinuationTest"`,
plus new `DenseFluidGatesTest` (a carbon dioxide node swept at 10 MPa from 280 to 400 K and at 320 K from 2 to 30 MPa:
film, velocity limit, viscosity and head continuous, each step under a stated bound; no hold) and three GameTests in
`src/fluidGameTest` (a carbon dioxide loop with a cooler across the pseudo-critical line and a pump; a nitrogen
bottle at 20 MPa let down through a valve; a full dense drum drawn from its top and its bottom nozzle; each removes
what it registers and closes mass and energy exactly).
Done: chain-100 exact, the 33 junction lines and the printed lines identical (final lane); the campaign equal to the
baseline line for line, unless O7 B adds a distribution.

### S3. Data: ranges and viscosity (materials lane; beside S1)

- O4 A: `pressure_max_pascal` and the evidence text of each dataset S0 qualifies; each record's own `revision` bumped;
  the package hull, the grading and the input box follow with no code. `FORMATS.md`: no row changes.
- O10 A: `critical_volume_m3_per_mol` appended to the datasets (absent: the cubic's value); the cuts' pressure
  ranges by surrogate (pc01 to pc04); a pair library for the cuts if O8 is not A. Each record's own revision.
- O5 A: the residual viscosity in `science/fluid/transport/MixtureViscosity.java`, its own `revision`.
- Tests: `MaterialCatalogTest`, `FluidPropertyCoverageTest`, `G3F9DataRangeBoundaryTest`, `RangeContinuationGateTest`.

### S4. Machines and the simulator (machines lane; after S2)

- `science/machine/GasEntrainment.java`, `Wear.java`: entrainment, wet gas and cavitation are laws of two phases. On a
  dense one-fluid node none applies; the pump's rise follows the delivered density as today. Running dry: a pump on a
  dense fluid below a reduced density (owner option in the stage's review; proposal `δ < 0.5`).
- NPSH3 floor: absent saturation means no floor.
- The compressor's head at `Z` 0.3 to 0.6: read the law first (`science/machine`, the mover row); if the discharge
  comes from the real enthalpy it stands; if from an ideal-gas exponent, an option with the error from S0.
- Simulator (`science/flowsheet/FlowThermo.java` `p_v`, `unit/MoverUnit`, `unit/EjectorUnit`, `sizing/`): the same
  absence; line and machine sizing by density where it reads a phase label (P3-29).
- Tests: the classes' own tests, `SheetSizingTest`, `FlowsheetExamplesTest`; no in-game check.

### S5. Presentation (client lane; after S2)

Read `.claude/skills/gui-creation/SKILL.md` first. A dense node's row reads "Dense fluid" (the label rule is a
presentation function of `State.dense()` and `δ`; no saved or wire field beyond one flag in the view), its level
reads "no interface". `en_us.json` only; the translation commit at merge preparation. Judged by software renders
(`tools/heat-gui-renders/`) and the gates; one in-game check offered to the owner at the end.

### S6. Supercritical water (only with O1 B)

Water above 647 K is the bare cubic today (A7). Options for the owner, with S0's water numbers: **A** continue
`WaterShare`'s gaps above `Tc` on IF97 Region 3 and the full Region 2 (a wider reference, against the rule "do not
add new reference models": the owner's call). **B** the bare cubic with its declared error and a notice. Then the
input box to 30 MPa, the steam turbine and the HRSG coil on "no saturation" (C4).

### S7. Process units (only with O1 C)

A dense solvent beside a heavy liquid is already two fluid phases to the engine (`LIQUID_LIQUID` or `VAPOR_LIQUID`,
A5) and settles by density in a drum. First a GameTest of propane and vacuum residue at 4 to 5 MPa across propane's
critical temperature, to see what the existing vessel gives. A new unit is planned only from that result.

### Column (beside any stage, column lane)

D1: `V3FeedFlash` answers a feed above its mixture critical point as one phase (the Rust twin in the same batch, or
the native ceiling lowered below it). D2: a tray whose two roots coincide is the typed refusal it is; its text names
the critical point.

## 4. Order, verification, merge

- Order: S0 → decisions → S1 beside S3 → S2 → S4 beside S5 → one final lane (`tools/merge-lane/lane.sh`) → merge
  preparation (`merge-prep` skill; a minor version proposed). The column item can run beside any of them.
- Before S1 starts beside another batch: `documentation/reference/COLLABORATION.md` (hot files:
  `FluidThermodynamics`, `PassiveStepSolver`, `StepEquations`, `FluidTpEquilibrium`).
- Results that move: banded engine answers (S1), dense nodes' films, limits and viscosities (S2, S3), nothing
  subcritical. Each stage's review states old and new values with their bound. Conservation stays exact.
- Formats: no facts field, no schema number. The state basis moves once (the revision key and the records' revisions).

## 5. Delegation proposed (each needs the owner's approval when its stage opens)

| Stage | Type | Model and effort | Why |
|---|---|---|---|
| S0 | Sub-thread (runs one Gradle test) | Opus medium | a harness with a clear brief |
| S1 | Subagent writes, main thread runs the tests | Opus high | engine internals, a digest to keep |
| S2 | Sub-thread in its own worktree | Opus high | the step's laws, GameTests to run |
| S3, S5 | Subagent | Opus medium | data and labels with a clear brief |
| S4 | Subagent | Opus high | machine laws |
| Final lane | Sub-thread | Opus medium | runs and reads lanes |
