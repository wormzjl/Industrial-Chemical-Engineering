# Thermodynamic kernel, water module and deterministic math

Up: [code guide](README.md)

## Read this when

- You change the PR78 equation, its mixing rule (Huron-Vidal, classical pairs, E-PPR78 k_ij(T)), its root selection or
  its composition and temperature derivatives.
- A kernel call costs too much, or a reader sees a zero (or NaN under the audit switch) for a compound it did not carry.
- The tangent-plane stability test gives a wrong verdict, or a phase label (PIP, `PhaseTraits`) is wrong.
- A water formula (saturation line, IF97 Region 1, steam's second virial, the water datum) gives a wrong value.
- A result differs between JDKs, JIT modes or machines, or `DeterministicMathSourceTest` reports a banned `Math.*` call.
- A trial is refused (`ThermoRefusal`, `NoPhysicalRoot`, `RootCoalescence`) or meets the numerical box.

The records the kernel is built from are in [materials.md](materials.md). The engine that solves on it is in
[equilibrium-engine.md](equilibrium-engine.md); the column's wrapper in [column-v3.md](column-v3.md); the network's in
[fluid-thermo-port.md](fluid-thermo-port.md).

## What it does

A cubic equation of state (Peng-Robinson 1978) turns each species' critical temperature, critical pressure and acentric
factor, and a mixing rule, into a compressibility factor Z, a log fugacity coefficient per species and a residual
enthalpy for a phase at (T, P, x), with analytic temperature and composition derivatives. The mixing rule is
Huron-Vidal's at infinite pressure: an NRTL excess Gibbs energy weighted by the co-volumes. A pair that is not NRTL is a
classical pair with a k_ij that is a constant, a held line in T, or an E-PPR78 group-contribution value; with every pair
classical the rule is exactly the quadratic one. The kernel is the one PR78 arithmetic of the column, the fluid network
and the equilibrium engine. Around it sit the residual half of the volume-translated cubic (`TranslatedCubic`), the
tangent-plane stability test, the phase identification parameter and a phase's traits, the trace cutoff policy, the
numerical box and the one refusal type. The water module (`science/thermo/water`) holds every water formula once, on
plain doubles. `science/math` gives the elementary functions that are not correctly rounded, bit for bit the same on
every JVM, and the small dense solves. None of these classes imports `science/material`, `science/thermo/phase`, the
column or the network (`ThermoLayeringTest`).

## Class map

Paths under [`src/main/java/com/wormzjl/icheme/science/`](../../../src/main/java/com/wormzjl/icheme/science/). Lines at
the snapshot.

**`thermo/`, the kernel**

| Class | Role | Lines |
|---|---|---|
| [`PengRobinsonKernel`](../../../src/main/java/com/wormzjl/icheme/science/thermo/PengRobinsonKernel.java) | The allocation-free PR78 kernel with one mixing rule: constructors on a constant k matrix, on a `PairInteractions` model, and `huronVidal` (a `HuronVidalExcess` and an optional Mathias-Copeman alpha per compound); caller-owned `Workspace`, `Evaluation`, `Derivatives`; `evaluate`, `prepareTemperature`, `evaluateDerivatives`, `differentiatePrepared`, the second temperature derivatives; `Root` (`LIQUID`, `VAPOR`), `selectRoot` (writes the root count and separation), `selectCompressibility`, `RootSelection`; the refusals `NoPhysicalRoot` and `RootCoalescence`; the cubic's pressure forms (`cubicDenominator`, `cubicPressure` and its derivatives); `wilsonK`; the carried support (`Workspace.carry`, `carryAll`, `carrySame`) | 2199 |
| [`HuronVidalExcess`](../../../src/main/java/com/wormzjl/icheme/science/thermo/HuronVidalExcess.java) | The excess Gibbs energy model of the mixing rule: per unordered pair an NRTL pair (alpha and two energies, each a held line) or a classical pair (constant, held line or a `PairInteractions` pair); `LAMBDA`, `held` (a line past its fitted ends, slope fading over `HOLD_FADE`), `FADE_END`, `Builder` | 366 |
| [`PairInteractions`](../../../src/main/java/com/wormzjl/icheme/science/thermo/PairInteractions.java) | The pair model of the quadratic part: per declared pair a constant k_ij or E-PPR78 group terms; `evaluate` and `evaluatePair` fill k, dk/dT, d²k/dT² and allocate nothing (one overload on a caller power table under a temperature stamp, each distinct exponent once per temperature); `Builder`, `temperatureDependent()`, `REFERENCE_TEMPERATURE_KELVIN` | 333 |
| [`TranslatedCubic`](../../../src/main/java/com/wormzjl/icheme/science/thermo/TranslatedCubic.java) | The residual half of the constant volume-translated PR78: the kernel on its pairs (or a prebuilt kernel), the translation, the analytic fixed-composition volumetric block (`Values`: dv/dT, dv/dP, d²v/dT², c_p, dh/dP, compressibility, partial volumes, the PIP inputs), `Workspace`, `Phase`; `residualOnly` (three forms) for the engine; `countPreparationsWith` (a diagnostics counter the network installs). The network's `TranslatedPengRobinson` extends it with the ideal-gas terms | 438 |
| [`ThermoComponent`](../../../src/main/java/com/wormzjl/icheme/science/thermo/ThermoComponent.java) | Record: id, Tc (K), Pc (Pa), omega, M (kg/mol) | 29 |
| [`TangentPlaneStability`](../../../src/main/java/com/wormzjl/icheme/science/thermo/TangentPlaneStability.java) | Michelsen's tangent-plane test of one feed at one pressure on the kernel: `test` (with or without a `TrialFilter`), `Verdict` (`STABLE`, `UNSTABLE`, `UNRESOLVED`), `Settings` (`DEFAULT`), `Result`, `Workspace`; `SEED_TRACE`; an optional water-rich trial (the constructor's `waterRichTrial`) | 729 |
| [`PhaseIdentification`](../../../src/main/java/com/wormzjl/icheme/science/thermo/PhaseIdentification.java) | The Venkatarathnam-Oellrich phase identification parameter on the untranslated cubic (`parameter`), the label rules (`liquidLike`, `vapourLike`, `denseLiquidLike`, `denserThanCriticalVolume`, `VAPOUR_BRANCH_VOLUME_RATIO`), the same rules on a root's `PhaseTraits`, `singleLabel` and the kernel's `Label` (`VAPOR`, `LIQUID`, `FLUID`) | 170 |
| [`PhaseTraits`](../../../src/main/java/com/wormzjl/icheme/science/thermo/PhaseTraits.java) | Record: physical root count, untranslated Z, v/b, the identification parameter of one evaluated root (`of`) | 41 |
| [`SubstitutionAcceleration`](../../../src/main/java/com/wormzjl/icheme/science/thermo/SubstitutionAcceleration.java) | The dominant-eigenvalue extrapolation of successive substitution: `factor` λ/(1 − λ), `admissible` (0 < λ < 1), `scale`, `MAXIMUM_EXTRAPOLATION` | 39 |
| [`TraceTruncationPolicy`](../../../src/main/java/com/wormzjl/icheme/science/thermo/TraceTruncationPolicy.java) | Record: the trace cutoff as a mole fraction (at most `MAX_CUTOFF_MOLE_FRACTION`), `OFF`, its error budgets, `REINSERTION_FACTOR` | 79 |
| [`PhaseSupport`](../../../src/main/java/com/wormzjl/icheme/science/thermo/PhaseSupport.java) | Enum: which hydrocarbon phase a component may occupy under truncation (`ABSENT`, `BOTH`, `LIQUID_ONLY`, `VAPOR_ONLY`), `of` from a reference point | 34 |
| [`NumericalBox`](../../../src/main/java/com/wormzjl/icheme/science/thermo/NumericalBox.java) | The one numerical box: `MINIMUM_TEMPERATURE`..`MAXIMUM_TEMPERATURE`, `MINIMUM_PRESSURE`..`MAXIMUM_PRESSURE`, `MINIMUM_PARTIAL_PRESSURE`; `contains`, `require`, `containsPartialPressure`, `requirePartialPressure` | 57 |
| [`ThermoRefusal`](../../../src/main/java/com/wormzjl/icheme/science/thermo/ThermoRefusal.java) | The one refusal type of the kernel, the engine and the water module: an `IllegalArgumentException` with no stack trace whose message is built when first read | 85 |
| [`IdealGasFunction`](../../../src/main/java/com/wormzjl/icheme/science/thermo/IdealGasFunction.java) | Interface: one species' ideal-gas h, s and c_p; h(298.15 K) the formation enthalpy, s(298.15 K, 1e5 Pa) the standard entropy; continues past its range with c_p held. Implemented by `ReferenceSpine` and `CoefficientIdealGas` ([materials.md](materials.md)) | 31 |

**`thermo/water/`, the water module** (constants from `WaterConstants`, which the material side's `WaterRecords.constants`
maps from the catalog's water record; no cache, hot callers hold the constants)

| Class | Role | Lines |
|---|---|---|
| [`WaterConstants`](../../../src/main/java/com/wormzjl/icheme/science/thermo/water/WaterConstants.java) | The water record's numbers as plain doubles: critical and triple point, Wagner-Pruss and Shomate coefficients, datum fields | 63 |
| [`WagnerPruss`](../../../src/main/java/com/wormzjl/icheme/science/thermo/water/WagnerPruss.java) | The saturation line, the one copy: `pressure`, `logSlope`, `temperature` (bisection), `polynomial`; below the triple point the supercooled liquid's line | 80 |
| [`Iapws97Region1`](../../../src/main/java/com/wormzjl/icheme/science/thermo/water/Iapws97Region1.java) | IF97 Region 1, the one table: `evaluate` (every sum in table order), `liquid` (the held continuation past the triple point and 623.15 K); `GAS_CONSTANT` (IF97's) | 150 |
| [`Iapws97Region2Ideal`](../../../src/main/java/com/wormzjl/icheme/science/thermo/water/Iapws97Region2Ideal.java) | Region 2's ideal-gas part, molar | 39 |
| [`SteamVirial`](../../../src/main/java/com/wormzjl/icheme/science/thermo/water/SteamVirial.java) | Region 2's residual truncated to its five terms linear in pressure: B(T), v = RT/p + B, residual h and c_p | 111 |
| [`SteamIdealGas`](../../../src/main/java/com/wormzjl/icheme/science/thermo/water/SteamIdealGas.java) | Water vapour's Shomate ideal gas, c_p held past either end of the record's data | 51 |
| [`WaterDatum`](../../../src/main/java/com/wormzjl/icheme/science/thermo/water/WaterDatum.java) | The water datum shared by the column, the network's utility water and the duty ledger; Watson's latent heat | 42 |
| [`DiluteSteamViscosity`](../../../src/main/java/com/wormzjl/icheme/science/thermo/water/DiluteSteamViscosity.java) | The IAPWS 2008 dilute-gas viscosity of water (Pa s) | 41 |

**`math/`**

| Class | Role | Lines |
|---|---|---|
| [`DeterministicMath`](../../../src/main/java/com/wormzjl/icheme/science/math/DeterministicMath.java) | The only entry of science and runtime code for log, log10, log1p, exp, expm1, cbrt, cos, sin, acos, asin, atan, atan2, tanh and pow; `StrictMath` bits except `pow` | 96 |
| [`DeterministicPow`](../../../src/main/java/com/wormzjl/icheme/science/math/DeterministicPow.java) | Table-driven `pow` in plain IEEE arithmetic, no FMA, within 1 ulp of fdlibm, no allocation | 259 |
| [`FdlibmPow`](../../../src/main/java/com/wormzjl/icheme/science/math/FdlibmPow.java) | fdlibm `e_pow.c` transliterated with its tables hoisted: bitwise `StrictMath.pow` (`DeterministicMath.strictPow`) | 322 |
| [`SmallDenseSolve`](../../../src/main/java/com/wormzjl/icheme/science/math/SmallDenseSolve.java) | Small dense solves on row-major blocks: `cholesky`, `solveCholesky`, `factorShifted`, `solve3`, `solvePivoted`; read by the stability test and the engine's flashes and balance steps | 94 |

`PengRobinson78` and `PhaseProperties` in `src/test/.../science/thermo/` are an independent PR78 oracle for tests; the
test tree's `science/thermo/reference/` is a Java Helmholtz-energy oracle on the CoolProp fluid files of
`src/test/resources/science/thermo/coolprop/` (stored `-text`). No production class uses them.

## Main flow

One phase evaluation, as every engine calls it:

1. The owner builds the kernel once per compound list: `new PengRobinsonKernel(Tc, Pc, omega, k, Tmin, Tmax, Pmin, Pmax)`,
   the `PairInteractions` constructor, or `PengRobinsonKernel.huronVidal(..., excess, mathiasCopeman, ...)`. The
   constructor copies the pairs into per-pair arrays.
2. The caller takes a `Workspace` per solve and names its support: `carry(feed)` (the feed's nonzero amounts),
   `carryAll()` or `carrySame(other)`.
3. `prepareTemperature(T, workspace)` fills the support's pure terms (a, da/dT, alpha), each pair's k_ij(T) and two
   derivatives (`PairInteractions.evaluatePair`, `HuronVidalExcess.held`) and the excess energies; a stamp per compound,
   pair and cell keeps what is already prepared at that temperature.
4. `evaluate(T, P, x, root, workspace, evaluation)` mixes, solves the cubic (`selectRoot`) and writes Z, ln phi, the
   residual enthalpy, a_mix, b_mix and the root count into the `Evaluation`.
5. `evaluateDerivatives` (or `differentiatePrepared` after an evaluation) fills d ln phi/dT, d ln phi/dn and the partial
   molar residual enthalpies into `Derivatives`.
6. Above the kernel: `TranslatedCubic.evaluateValues` adds the translation and the volumetric block;
   `TangentPlaneStability.test` runs its trials on the kernel; `PhaseTraits.of` reads the root's numbers for the label.

## Contracts

- **Units.** T in K, P in Pa, R = `PengRobinsonKernel.GAS_CONSTANT` J/(mol K). Outputs: Z dimensionless, natural-log
  fugacity coefficients, residual enthalpy J/mol.
- **Inputs.** A k matrix is finite, symmetric and has a zero diagonal; a model's or an excess's basis has the kernel's
  component count. Compositions are normalised; a negative, non-finite or all-zero composition is a `ThermoRefusal`; an
  amount outside the workspace's support is an `IllegalArgumentException` (an `AssertionError` under the audit switch).
- **Range.** The kernel checks the temperature and pressure range it was built with (`ThermoRefusal` "outside package
  range"). The column builds it on the package's range; the network's `TranslatedPengRobinson` on an open range and
  checks its states itself; every trial of the fluid and the engine meets `NumericalBox` instead.
- **Root selection** (`selectRoot`): the cubic Z³ + c2 Z² + c1 Z + c0 solved analytically. Vapour takes the largest real
  root, liquid the smallest physical one (Z > B + 1e-12); in the one-root region both get the same root. A root that is
  not backward accurate (residual above 8 ulp of the term scale) is refined by at most 4 Newton steps inside its
  derivative-monotonic interval, so it cannot jump branch; in the coalescence band (|discriminant| at most 1e-16) no
  refinement happens. No root above B is `NoPhysicalRoot`.
- **Derivatives.** `evaluateDerivatives` refuses a root whose cubic slope is below 1e-10 relative to its terms
  (`RootCoalescence`); callers fall back to differencing.
- **Refusals.** `ThermoRefusal` is a refused trial, never a hold: the solver that formed the trial backtracks, and the
  engine answers it as a refused evaluation. `NoPhysicalRoot` and `RootCoalescence` are `IllegalStateException`s, which
  callers outside the thermodynamics tell apart from it. An argument, layout or invariant failure stays a JDK exception
  (a defect).
- **Carried support.** Every output on the support keeps its bits against the whole-list call: the terms left out are
  exact zeros, summed in the same order. Outside the support every output holds `outsideSupportFill()` (0.0). Set the
  support at every entry from the feed, never from one phase: a feed compound with no amount in a phase still needs its
  ln phi there. Callers: the engine (`FluidTpEquilibrium`, `TangentPlaneStability`), the network
  (`TranslatedPengRobinson.Workspace`, `HydrocarbonModel`, `PhaseLayout`). The column carries the whole list.
- **Threads.** A kernel, a `PairInteractions` and a `HuronVidalExcess` are immutable after construction. A
  `Workspace`, `Evaluation` and `Derivatives` belong to one solve on one thread.
- **Determinism.** `+ - * /`, `Math.sqrt` and `Math.fma` are correctly rounded and used directly; every other
  elementary function goes through `DeterministicMath`, which returns `StrictMath` bits (fdlibm, pure Java bytecode),
  except `pow`, which is `DeterministicPow`: the same bits on every JVM, within 1 ulp of fdlibm. The facade wraps only
  functions with a caller (`atan` for `JaegerSpanGibbsFunction`, `tanh` for the methane spine, `sin`, `asin`, `atan2`
  for the equipment geometry); a new caller adds its function first. Code outside `science/` and `runtime/` is not
  scanned.
- **Water module.** It imports no material, column or network type; every function reads a `WaterConstants`. Its
  callers delegate to it: `V3WaterProperties` (the column), `WaterRegion1`, `FluidThermodynamics` (the network),
  `WaterShare` (the engine), `TrainedPhysics.Water` and `HenryConstant` (materials).

## Laws and constants

- **Pure terms** (`PengRobinsonKernel`): a_c = 0.45724 R² Tc²/Pc, b = 0.07780 R Tc/Pc, alpha = [1 + kappa(1 − √(T/Tc))]²,
  kappa with the 1978 split at omega = 0.491 (`kappa`). A Mathias-Copeman compound: alpha = (1 + c1 u + c2 u² + c3 u³)²,
  u = 1 − √(T/Tc), above Tc only c1 (`huronVidal`).
- **Mixing** (`HuronVidalExcess`): a_mix/b_mix = Σ x_i a_i/b_i − gE/Λ, b_mix = Σ x_i b_i, Λ = `LAMBDA`
  = ln((2 + √2)/(2 − √2))/(2√2); gE/(RT) = Σ_i x_i [Σ_j x_j b_j G_ji τ_ji]/[Σ_k x_k b_k G_ki], G = exp(−α τ). A
  classical pair: α = 0, dg_ji = g_ji − g_ii with g_ii = −Λ a_i/b_i and
  g_ji = −2√(b_i b_j)/(b_i + b_j) √(g_ii g_jj)(1 − k_ij). The kernel forms the quadratic part on its pair list,
  O(n + pairs), and the exponential sums only where it has NRTL pairs.
- **Held lines** (`HuronVidalExcess.held`): the plain line between its fitted ends; past an end the slope fades to zero
  over `HOLD_FADE` along a smoothstep, then the value stays constant. Value, slope and second derivative are
  continuous, so a heat capacity read through it is continuous.
- **E-PPR78** (`PairInteractions`): E_ij(T) = −½ Σ_k Σ_l (α_ik − α_jk)(α_il − α_jl) A_kl (298.15/T)^(B_kl/A_kl − 1),
  k_ij = [E_ij − (√a_i/b_i − √a_j/b_j)²]/[2√(a_i a_j)/(b_i b_j)] on the kernel's own a_i and b_i, evaluated in a
  cancellation-free rearrangement. A model whose pairs are all constant reproduces the constant-matrix kernel bit for
  bit.
- **Wilson K** (`wilsonK`): initialisation only, ln K clamped to [−40, 40].
- **Stability** (`TangentPlaneStability`): tm(W) = 1 + Σ W_i (ln W_i + ln phi_i(W) − d_i − 1) with
  d_i = ln z_i + ln phi_i(z); tm below the instability tolerance proves instability. Trials in a fixed order: Wilson
  vapour, Wilson liquid (water at `SEED_TRACE` of its Wilson value where water and another compound are present), each
  pure present component while at most `pureComponentTrialLimit` are present, else the water-rich trial. Substitution
  with the extrapolation, then Newton on α = 2√W. A one-component feed compares its two roots' fugacities.
- **Phase identification** (`PhaseIdentification`): PIP = v[(∂²P/∂T∂v)/(∂P/∂T)_v − (∂²P/∂v²)/(∂P/∂v)_T];
  liquid-like when PIP > max(1, Z); dense when v < `VAPOUR_BRANCH_VOLUME_RATIO` b.
- **Trace cutoff** (`TraceTruncationPolicy`): budgets 8 times the cutoff, reinsertion at `REINSERTION_FACTOR` times.
- **Water** (module): ln(p_s/p_c) = (T_c/T) Σ a_i θ^e_i, θ = 1 − T/T_c (`WagnerPruss`); Region 1 on its own datum with
  IF97's `GAS_CONSTANT`; steam's v = RT/p + B(T) (`SteamVirial`).
- **Numerical box** (`NumericalBox`): where the mathematics stop being trusted, not a data range (AGENTS.md "Thermo
  validity").

## Formats

The kernel saves nothing. Identities of the data it runs on: [materials.md](materials.md); saved and wire formats:
[FORMATS.md](../FORMATS.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| PR78 equation, mixing, roots | `PengRobinsonKernel`, `HuronVidalExcess` | `PengRobinsonKernelRootPrecisionTest` (a ninety-digit oracle for cancellation roots, backward-accurate roots keep bits, the coalescence band), `PengRobinsonKernelHuronVidalTest` (with `aCarriedSupportKeepsEveryOutputOnItBitForBit`), `PengRobinson78Test`, `V3PengRobinsonKernelTest` (column, against the test-tree oracle) |
| E-PPR78 pairs | `PairInteractions` | `PairInteractionsTest`, `PengRobinsonKernelPairInteractionsTest` (`constantModelReproducesSparsePairsBitForBit`) |
| The cubic's pressure forms, volumetric block | `PengRobinsonKernel.cubicPressure`, `TranslatedCubic` | `CubicVolumetricBlockTest` |
| Stability test | `TangentPlaneStability` | `TangentPlaneStabilityTest` (brute-force minima, bitwise repeats, the filtered call without a filter); `AqueousEquilibriumTest` |
| Labels and traits | `PhaseIdentification`, `PhaseTraits` | `PhaseTraitsTest`, `FluidTpEquilibriumP3Test` |
| Water formulas | `thermo/water/*` | `WaterModuleTest` (each function bit for bit against the formulas written out, on a grid from 1 K past 900 K), `SteamVirialTest`, `WaterShareTest`, `WaterRegion1Test`, `V3WaterPropertyDerivativesTest` |
| Layers | imports of `science/thermo/**` and `science/math` | `ThermoLayeringTest` (`theKernelNamesNeitherTheMaterialsNorTheEngine`, `nothingUnderThermoNamesTheNetworkOrTheColumn`), `LayeringTest` |
| Elementary functions | `DeterministicMath` | `DeterministicMathSourceTest` (scans `science/`, `runtime/` and the test tree's `fluid/benchmark/`, comments stripped, for a direct `Math.` or `StrictMath.` call of sin, cos, tan, asin, acos, atan2, atan, sinh, cosh, tanh, exp, expm1, log, log10, log1p, pow, cbrt, hypot, random; the facade exempt; at least 150 files scanned), `DeterministicMathTest` (`strictPow` against `StrictMath.pow`, pow bit patterns and tables, no allocation, `atanAndTanhAreStrictMathBitForBit`) |
| Trace cutoff | `TraceTruncationPolicy`, `PhaseSupport` | `TraceTruncationStepTest`, `TraceTruncationLayoutTest`, the column's truncation tests |

The determinism tests sit in package `science.fluid`, so `fluidScienceTest` runs them too. The kernel's other tests run
in `test` only.

## Traps

- The kernel has one mixing rule. "Classical", "sparse" or "rank-one" is how a list of pairs is summed, not a separate
  plan: a list without pairs is the rank-one product (`usesRankOneMixing`), a list of constants skips the derivative
  passes, NRTL cells exist only where `hasExcessPairs`.
- `CoefficientTranslations.pureLiquidVolume` (materials) evaluates the kernel's `Root.LIQUID` root for the volume
  calibration, and `SpinePackageData` calls it: a kernel change is a calibration change too.
- `TangentPlaneStability` keeps its own acceptance and cap for the extrapolation; the engine's flashes use
  `SubstitutionAcceleration.admissible`. They differ where the quotient rounds to one.
- `PhaseIdentification.singleLabel` returns the kernel's `Label`: the kernel may not name `thermo.phase`'s `PhaseKind`.
- The audit switch: the system property `icheme.kernel.supportNaN` (or `fillOutsideSupportWithNaN`, tests only) fills
  NaN outside the support and makes an amount outside it an `AssertionError`, so a reader of an uncarried compound
  fails. Its init script and probes are in `tools/aqueous-solvents/kernel-cost/`.
- `IdealGasFunction` states a formation-enthalpy datum, but `CoefficientIdealGas` of a package without a spine sits on
  the network's sensible datum (zero at 298.15 K and 1e5 Pa).
- `SteamVirial` is the column's steam and the engine's gas gap (`WaterShare`), not a free-water model.

**Snapshot:** `d6f721b94`, 2026-11-15.
