# The fluid network's thermo port and state

Up: [code guide](README.md). The engine behind it: [equilibrium-engine.md](equilibrium-engine.md); the kernel and the catalog: [thermo-kernel.md](thermo-kernel.md), [materials.md](materials.md); who calls it: [fluid-step.md](fluid-step.md), [fluid-interval.md](fluid-interval.md).

<!-- j2a-packages: science/fluid/thermo/ science/fluid/state/ -->

## Read this when

- A node's state means something unexpected: its slots, its phase labels, its energy, a stand-in liquid, its solids.
- A property value is wrong (density, enthalpy, velocity limit, viscosity source, water's saturation curve), or an equilibrium answer reaches the network wrongly (`NetworkPhaseEngine`).
- An island holds with `HELD (phases): …`, or its status line carries `; outside data range: …`.
- A crystal (CO2-I, ice) is stated, anchored or melts on the wrong line (`NetworkCrystals`).
- You add a compound, a crystal or property data and the network must accept it, or the model's revision moves.
- A solid population is merged, refused or dropped (`SolidInventory`).

## What it does

`FluidThermodynamics` is the network's property model: an immutable model of the network package (`forNetwork`), viewed on an island's own compound list (`on`, `island`), with the property state `State` that every node, stream and checkpoint carries. A `State` holds T, P and three fluid slots on the model's conserved basis (the compounds, then water last): a vapour, a liquid and a second liquid. The phases are decided by the equilibrium engine through `NetworkPhaseEngine` (TP, PH, UV); residuals never flash. Each fluid phase is the derived cubic (`HydrocarbonModel` on `TranslatedPengRobinson`, the kernel's translated PR78) at the state pressure on its own root, its volume and enthalpy on the balance book (the cubic plus water's IF97 share), its `ln phi` on the equilibrium book. Water is a compound of the cubic on a list that carries it; a list without water (a dry island) has no water fugacity. The package's crystals and ice Ih are solid populations (`NetworkCrystals`, `state/SolidInventory`), exchanged with their component only at a vessel's refresh or in a warm junction ([fluid-interval.md](fluid-interval.md)). Past its data a property continues with continuous value and slope, and `DataRangeNotices` writes the island's non-blocking notice; only capability limits hold (`ThermoHold`).

## Class map

Under `src/main/java/com/wormzjl/icheme/science/fluid/`. Lines at `d6f721b94`.

| Class | Role | Lines |
|---|---|---|
| [`thermo/FluidThermodynamics`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/FluidThermodynamics.java) | The property model: `forNetwork`, `on`, `island`, `whole`, `compounds`, `wet`, `catalog`, `time`/`withTime`; `State`; `flashTP`; the engine entries (`equilibriumTP`, `equilibriumPH`, `fluidPH`, `equilibriumAtVolume`) under `stepCompetition`; the folds and shares (`folded`, `vanishing`, `nothing`, `collapsing`, `belowShare`, `rigidGas`); crystals (`crystalSolids`, `crystalsAtVolume`, `crystalsAtPressure`, `crystalsCompete`, `condensedShareOnly`); `dataRanges`; `tangents`; `prepare` and `Prepared`; `CriticalBandHold`, `UnsupportedPhases`; `revision`, `thermodynamicRevision`; `legacySplit`, `fromLegacySplit`; forwarders to `NetworkStreams` and `NetworkWater` | 1255 |
| [`thermo/NetworkStreams`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/NetworkStreams.java) | Package-private, one per model: the stream functions behind the model's forwarders (`velocityLimit`, `isothermalAcousticBound`, `heatCapacityRatio`, the slot and phase streams' masses, volumes, densities, enthalpies, viscosities and velocity limits, `phaseMoles`, `phaseVolume`) | 208 |
| [`thermo/NetworkWater`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/NetworkWater.java) | Package-private, one per model: utility water (`waterLiquid`, `vaporWaterEnthalpy`) and `saturationPressure`/`saturationTemperature` on the whole model's curve | 64 |
| [`thermo/HydrocarbonModel`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/HydrocarbonModel.java) | The derived cubic on one compound list (`PackageBinding.derivedEvaluator`): each phase at the state pressure on its own root, water's share (`WaterShare`), the ideal gas from a reference spine or from `shifted_polynomial_5` fits; `phase`, `Phase` (with the support it was evaluated on, `carried`), `tangent`, `Tangent`, `carry`, `RootAbsent`, `FORMULATION`, `revision` | 386 |
| [`thermo/TranslatedPengRobinson`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/TranslatedPengRobinson.java) | The network's adapter of the kernel's `TranslatedCubic` (translation and volumetric block inherited with `Workspace`, `Values`, `Phase`), adding the ideal-gas caloric functions (`idealGasTerms`): the coefficient path, a fit held at its end value past its range, or one `IdealGasFunction` per component with a datum offset | 204 |
| [`thermo/NetworkPhaseEngine`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/NetworkPhaseEngine.java) | Package-private: the equilibrium engine on the derived contract of the model's evaluator (`PackageBinding.derivedContract`), one evaluator per admitted crystal: `tp`, `ph`, `crystalUv`, `basis` | 109 |
| [`thermo/NetworkCrystals`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/NetworkCrystals.java) | The package's crystals as solids, ice Ih among them on a wet model: `Crystal` (`logFugacityCoefficient`, `meltingTemperature`, `water`), each the engine's anchored crystal (`anchoredCrystal`); `MELTING_PRESSURE` | 120 |
| [`thermo/WaterRegion1`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/WaterRegion1.java) | IAPWS-IF97 region 1 liquid water at the state pressure: a facade over `Iapws97Region1.liquid` and `DiluteSteamViscosity`; utility water outside the network; held continuation past its range; `METASTABLE_MARGIN_PASCAL`; `Workspace` | 91 |
| [`thermo/WaterSaturation`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/WaterSaturation.java) | Pure water's saturation curve on the model's own cubic: `ln p` on a grid uniform in `1/T` (`INTERVALS`, triple point to the critical point less `CRITICAL_MARGIN`) with the Clapeyron slope, read by Hermite | 109 |
| [`thermo/FluidDomain`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/FluidDomain.java) | The package envelope (the hull of its datasets' `fluid_domain` ranges and water's triple point) and the input box of a device setting: `requireInput`, `requireInputPressure`, `requireInputTemperature`; refuses no network state | 62 |
| [`thermo/ThermoHold`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/ThermoHold.java) | Sealed hold supertype of `CriticalBandHold` and `UnsupportedPhases` (capability limits only): `REASON_PREFIX`, `at`, `sameAs` | 57 |
| [`thermo/DataRangeNotices`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/DataRangeNotices.java) | One advisory notice per subject and side: `STATUS_MARKER`, `line` | 72 |
| [`thermo/FluidMaterialCatalog`](../../../src/main/java/com/wormzjl/icheme/science/fluid/thermo/FluidMaterialCatalog.java) | `networkPackage(catalog)` (the `networks` record's package), `NETWORK_PACKAGE` (the bundled one), `NITROGEN`, `conservedCount`, `waterIndex` | 20 |
| [`state/SolidInventory`](../../../src/main/java/com/wormzjl/icheme/science/fluid/state/SolidInventory.java) | Conserved solid populations (material × size, at most `MAXIMUM_POPULATIONS`), crystals among them: moments, merging, accumulation, `validate`, `crystals`, `withoutCrystals`, `oneSizePerSolid`, `SIZE_DIGITS` | 162 |
| [`state/ParticleSize`](../../../src/main/java/com/wormzjl/icheme/science/fluid/state/ParticleSize.java) | Exact decimal particle diameter key: a population's identity | 23 |
| [`state/EnergyReference`](../../../src/main/java/com/wormzjl/icheme/science/fluid/state/EnergyReference.java) | Energy datum per component basis: the sensible 298.15 K datum, rebasing | 49 |
| [`state/LegacySplit`](../../../src/main/java/com/wormzjl/icheme/science/fluid/state/LegacySplit.java) | A node's split in the fields a vessel's facts row carries for an older reader: the compounds' liquid and vapour, liquid water, steam | 10 |

## Main flow

A model's life:

1. `FluidThermodynamics.forNetwork(catalog, packageId, maximumVelocity, traceCutoff, solids, time)`: the whole model of the network package (the runtime builds one per world, [runtime-registry.md](runtime-registry.md)).
2. `on(compounds, water)` or `island(set)`: the package on an island's list (`MaterialCatalog.restrict`), built once per list and kept by the whole model; `whole()` returns to the world list.
3. A node's state: `flashTP` (TP initializer) or an engine answer adopted into `State` (`equilibriumTP`, `equilibriumPH`, each under `stepCompetition`), placing its liquids by the slot rule.
4. In a Newton evaluation: `prepare(T, reuse)` refills a node's `Prepared` bundle at a new temperature; `PhaseLayout.decode` builds the state on it; `tangents(state, prepared)` gives one `HydrocarbonModel.Tangent` per phase for the block Jacobian.
5. Streams and limits: `velocityLimit`, `vaporVelocityLimit`, `phaseVelocityLimit`, the phase densities and enthalpies (`NetworkStreams`).
6. At an interval's end the runtime calls `dataRanges` on each node and writes the notices ([runtime-engine.md](runtime-engine.md)).

## Contracts

- **Slots and labels.** `State` stores `vapor[]`, `liquid[]` and `aqueous[]` on the whole basis (storage `State.VAPOR`, `State.LIQUID`, `State.AQUEOUS`; labels `GAS`, `OIL`, `AQUEOUS`), volume, H, U, mass, phase volumes, one property record per held phase, solids and their moments. The slot rule (`State.slot`) is a function of the state alone: of two liquids the one with the larger water fraction is aqueous; a single liquid is aqueous when water is at least half of it by moles. Layering, heads and draws go by density; the label decides the display row and a boot drum's weir.
- **One model per compound list.** A compound at exactly zero is inert in every sum, so a state holding none of the others has the same numbers as on the whole package. Positions are not compound ids across islands: map by id (`runtime/fluid/IslandSets`). `compounds()` lists the compounds without water; the conserved basis appends water last on every list.
- **A step carries its crystals in the fluid phases** (`stepCompetition`: `FLUID_CARRYING_CRYSTALS` where crystals would compete, else fluid-only): one liquid, or a stand-in beside a vapour. Only a vessel's refresh (`crystalsAtVolume`, `crystalsAtPressure`) runs the competition, so only it meets `CRYSTAL_PHASES`; a step meets two liquids as `LIQUID_LIQUID`. `equilibriumPH` carries crystals; the fluid phases alone are `fluidPH`.
- **The prepared refill keeps the bits.** A node's `Prepared` bundle taken over at a new temperature gives the same bits and refusals as a new bundle (a refusal drops the workspace). `StepEquations` keeps a decode and a probe bundle per node across a pass's trial temperatures, so a step builds one kernel workspace per laid-out node (`Prepared.workspacesBuilt`).
- **Leaving a data range never holds.** Every property continues past its data; `dataRanges` reads a node against the declared ranges and writes `DataRangeNotices`. `FluidDomain` refuses only a device setting outside the input box, never a state the network reaches.
- **Holds are capability limits only**: `CriticalBandHold` (the engine did not converge in the declared critical band) or `UnsupportedPhases` (a phase set the network cannot carry). Their reasons start with `ThermoHold.REASON_PREFIX`. The engine's typed failure (`EquilibriumResult.FailureKind`) is read, not its text.
- **A dry model refuses water** at its state, naming the package (`NetworkPhaseEngine.basis`): a package whose ideal gas comes from reference spines has no water spine.
- **Validation.** `SolidInventory` refuses more than `MAXIMUM_POPULATIONS`; `validate(SolidMaterialCatalog)` requires a saved custom population to equal the loaded record (crystal populations skipped: `NetworkCrystals` builds them).
- **Layering.** `science/fluid/thermo` sits above the equilibrium engine and below the step solver: it imports nothing of `science/fluid/solver` (`LayeringTest`, `fluid-thermo-below-solver`), and the engine imports nothing of the network (`ThermoLayeringTest`).

## Laws and constants

**Velocity limit** (`NetworkStreams`): a bulk stream's `velocityLimit` = min(configured maximum, isothermal acoustic bound); a vapour stream's `vaporVelocityLimit` = min(maximum, V_v / sqrt(M_v C)), C the vapour's compliance (its volume times the cubic's isothermal compressibility; a subnormal product is evaluated in ratio form); a liquid stream's is the configured maximum (`phaseVelocityLimit`). The configured maximum defaults to `DEFAULT_MAXIMUM_VELOCITY` (config `fluid.maximumVelocityMetresPerSecond`).

**Share tests** (`belowShare`, read by `vanishing` and `nothing`): every phase below `VANISHING_SHARE` of the node's fluid volume; water and either gas also below it by moles; the hydrocarbon liquid on its volume alone; a rigid vessel's gas on its volume alone (`rigidGas`). The trace fold is `PHASE_TRACE_FRACTION`; `collapsing` adds any phase below `VANISHING_COLLAPSE` of its start share.

**Pump reference density** (`pumpReferenceDensity`): the model's own Region 1 water at 298.15 K and 101 325 Pa; a pump's rise limit scales by the delivered density over it ([fluid-laws.md](fluid-laws.md)).

**Crystal melting line** (`NetworkCrystals.Crystal.meltingTemperature(P)`): straight through the record's triple point and the engine's pure melting point at `MELTING_PRESSURE`; below the triple-point pressure it continues. Each crystal is anchored to the model's own pure liquid at the record's triple point, so no saturation curve is read. Ice reads its own line.

**Water's saturation** (`WaterSaturation`, built once per whole model on first use): a state built at saturation is then the phase the flash calls it. A dry model keeps Wagner-Pruss from `science/thermo/water`.

**Tangents** (`tangents`, `HydrocarbonModel.Tangent`): one kernel derivative evaluation per phase of the base state; first order in volume, enthalpy, heat capacity and ln phi, exact in T, P, amounts and water's share; `dv/dP` first order too. A column whose composition shift passes `TANGENT_COMPOSITION_STEP` takes the exact phase.

**Revision** (`revision()`): `thermodynamicRevision()` (`fluid-trbdf2-r1`, the model's `HydrocarbonModel.revision` and the viscosity revision) plus the hydraulic keys: `line-cap-saturation-v1` (the cap law, `PassiveStepSolver.saturated`), `trace-relative-v1`, `max=` the configured maximum velocity, the trace cutoff and solid settings, and `:time=` for a time factor other than 1. `HydrocarbonModel.revision` is the derived contract's identity revision plus `FORMULATION`.

| Constant | Meaning | File |
|---|---|---|
| `AMBIENT_PRESSURE` | the ambient; `PhaseLayout.AMBIENT_PRESSURE` is the same constant | `FluidThermodynamics` |
| `DEFAULT_MAXIMUM_VELOCITY` | the configured velocity maximum's default | `FluidThermodynamics` |
| `DEFAULT_TRACE_CUTOFF_MOLE_FRACTION`, `MAX_TRACE_CUTOFF_MOLE_FRACTION` | the trace cutoff's default and bound | `FluidThermodynamics` |
| `PHASE_TRACE_FRACTION`, `VANISHING_SHARE`, `VANISHING_COLLAPSE` | the fold, the vanishing share, the collapse at a failed iterate | `FluidThermodynamics` |
| `TANGENT_COMPOSITION_STEP` | where a perturbed column takes the exact phase | `FluidThermodynamics` |
| `GAS`, `OIL`, `AQUEOUS` | phase labels | `FluidThermodynamics` |
| `FORMULATION` | the model's formulation tag | `HydrocarbonModel` |
| `MELTING_PRESSURE` | second point of an admitted crystal's melting line | `NetworkCrystals` |
| `METASTABLE_MARGIN_PASCAL`, `REGION_1_MAXIMUM_TEMPERATURE`, `REGION_1_MAXIMUM_PRESSURE` | region 1's metastable margin and range | `WaterRegion1` |
| `INTERVALS`, `CRITICAL_MARGIN` | the saturation grid | `WaterSaturation` |
| `REASON_PREFIX` | a phase hold's reason key | `ThermoHold` |
| `STATUS_MARKER` | the data-range notice's marker | `DataRangeNotices` |
| `MAXIMUM_POPULATIONS`, `SIZE_DIGITS` | solid populations per stock; the size key's digits | `SolidInventory` |

## Formats

The `State` and the solid populations are written by the runtime's codecs ([runtime-saves.md](runtime-saves.md), numbers in [FORMATS.md](../FORMATS.md)). `legacySplit` writes the older fields a vessel's facts row keeps with their meaning; `fromLegacySplit` reads a row that carries no slots (it cannot say which liquid a dissolved compound is in). A row with the slots rebuilds through `state(t, p, vapour, oil, aqueous)`. A change to `FORMULATION` or the revision keys is a fresh state basis: the islands rebuild from their facts and the engine recomputes energies.

## Tests and gates

| Rule | Test |
|---|---|
| The model, slots and labels | `FluidThermodynamicsTest`, `AmbientCrudePresetTest` |
| The cubic, water and liquid volumes | `HydrocarbonModelTest`, `TranslatedPengRobinsonTest`, `WaterRegion1Test`, `FluidNitrogenCryogenicTest`, `SpineNetworkPathTest`, `LegacyNetworkPathPinTest` |
| The engine on the network's contract | `NetworkPhaseEngineTest` |
| A step's carried crystals | `CarriedCrystalsTest` |
| Continuation past the data; notices | `DataRangeContinuationTest`, `RangeContinuationGateTest`, `FluidPropertyCoverageTest`, runtime `FluidDataRangeIslandTest` |
| Direct liquid continuity and determinism | `DirectLiquidContinuityTest`, `DirectLiquidDeterminismTest` |
| The tangents | `HydrocarbonTangentTest` |
| The prepared refill | `PreparedRefillTest` |
| A subnormal solid mass is no stock | runtime `SolidRuntimeTest.aSubnormalMassIsNoStock` |

Gradle: `fluidScienceTest` (all of `science.fluid.*`). A change to the model's numbers moves references: read [verification.md](verification.md) first.

## Traps

- **`thermodynamicRevision()` starts with `fluid-trbdf2-r1`, but the integrator is backward Euler.** The string is a revision key only; checkpoints save it in each package record and compare it at load.
- **"Stand-in".** Between refreshes a crystal's condensed share is carried in a liquid slot (`condensedShareOnly`), ice's as supercooled water in the aqueous slot. The solids appear at the next refresh. The phase check never counts a stand-in as the node's own liquid.
- **`equilibriumPH` is not fluid-only**: it carries crystals where they would compete; use `fluidPH` for the fluid phases alone.
- **`FluidMaterialCatalog.conservedCount`/`waterIndex` read `MaterialRuntime.current()`**, a global, not the model you hold.
- **A dry model's water `ln phi` is not a number.** Code that reads a water fugacity must test `wet()` first.
- **The coefficient translations are the kernel's pure liquid.** `CoefficientTranslations.pureLiquidVolume` evaluates one pure component on the kernel's liquid root at its anchor's own T and P: a kernel change changes the calibration. The network's translations are its derived evaluator's anchoring (`PackageBinding.derivedEvaluator`); a spine package takes `SpinePackageData`'s.
- **A solid population below `Double.MIN_NORMAL` kg is roundoff zero** and is dropped: no double states its volume or heat capacity.
- **`initialNitrogenCharge` is a placement initializer only**; persistence never calls it again, and its first argument is a volume (m³).

**Snapshot:** `d6f721b94`, 2026-11-15.
