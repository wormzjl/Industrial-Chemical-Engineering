# Supercritical fluids: what limits them today

Status: review, 2026-11-15, base `e9edc974b` (main 0.31.0). Read from the code and from the readiness lists of the
2026-11-13 code base review. No build, no run, no measurement: every number below is from the code, from critical
constants or from the equation of state's algebra, and each one to be measured is marked "to measure".

## Summary

**Expected behaviour.** Above its critical temperature and pressure a fluid is one phase. Along any path that does not
cross the two-phase envelope, density, enthalpy and every transport property change continuously from gas-like to
liquid-like. There is no interface, no level, no latent heat and no saturation pressure. Heat capacity and
compressibility peak near the critical point (the pseudo-critical line), and that is a property of the state, not a
phase change. A mixture is supercritical with respect to its own envelope, not to its compounds' critical constants.
Equipment sees a dense fluid: a pump raises its pressure by its density, a compressor heats it by its real
compressibility, an exchanger's film follows its density and viscosity.

**What the code does.**

1. The kernel and the equilibrium engine already answer supercritical states. PR78 has one real root there; TP, PH and
   UV converge on it; a pure fluid above `Tc` and `Pc` is classified `SUPERCRITICAL_FLUID` with kind `FLUID`
   (`DryCore.singleClassification`). Nothing refuses. Supercritical states already occur in ordinary play: hydrogen
   above 1.3 MPa, nitrogen above 3.4 MPa and methane above 4.6 MPa at ambient temperature.
2. The limits are in what is asked and how the answer is labelled:
   - **The network has three slots (gas, oil, aqueous) and no "one fluid".** A one-root phase is put in the gas or the
     oil slot by the phase identification parameter (`FluidThermodynamics.placed`, `PIP > max(1, Z)`). The line runs
     through a continuous region. When a node crosses it, the whole inventory changes slot, and every law that reads
     the slot jumps: the film coefficient by 20 times (2000 against 100 W/m²K), the velocity limit, the level and head
     (a full drum reads 100 % or 0 %), which port draws, the pump's suction floor, the mover's heat capacity ratio.
   - **The critical band holds islands.** A pure fluid (one compound at 0.95 or more) inside four boxes of reduced
     temperature and pressure is graded research-only, and an engine failure there is the hold `HELD (phases)`. Boxes 3
     and 4 lie wholly above `Tc` and `Pc`, where a pure fluid cannot split. For carbon dioxide the boxes cover 289 to
     365 K at 5.9 to 22.1 MPa: the working window of a carbon dioxide pipeline, pump or extractor.
   - **The data and the input box stop at 10 MPa and 900 K.** 45 of 47 datasets declare 2 MPa; nitrogen and methane
     10 MPa. Above a dataset's range the network continues with a notice (no hold), but a source, reservoir or
     regulator setting above the package hull is refused, so water's critical pressure (22.06 MPa) cannot be set.
   - **Dense-phase properties are unqualified.** PR78's critical compressibility is 0.3074 for every compound, against
     0.2746 for carbon dioxide and 0.229 for water: near the critical point the cubic's volume is 12 % and 34 % too
     large before translation (to measure). Water's own share (IF97 Region 1, the steam virial) fades to zero at
     647 K, so supercritical water is the bare cubic. No dense-fluid viscosity exists: a supercritical compound in the
     oil slot gets its dilute gas viscosity (`MixtureViscosity.liquidSource`), about half the real value for carbon
     dioxide at 320 K and 10 MPa and less at higher density (to measure).
   - **"No saturation" is written as infinity or as a leave-out.** Water's saturation pressure is `+∞` at `Tc`
     (`NetworkWater`, `WaterSaturation`: a jump from the table's end 0.05 K below); the release pressure leaves out
     compounds above their `Tc` (`ReleasePressure.leaveOutGases`); the simulator's `p_v` does the same.
   - **The column and the still are two-phase by construction.** Every tray asks for a liquid root and a vapour root;
     a feed above its mixture critical point is refused as "lost its two-phase root" (`V3FeedFlash`) instead of
     answered as one phase.
3. Nothing in the saves blocks the work: a dense phase fits the existing slots (`PhaseLayout`: one active slot holds
   every component). No facts field is needed for the first stages.

**Proposal.** [SUPERCRITICAL_PLAN.md](SUPERCRITICAL_PLAN.md): keep the three slots and the save format, make every
law that reads a slot label read a continuous trait of the phase instead, replace the band's hold by an answer with a
notice, qualify carbon dioxide and the light gases against the CoolProp tables already in the tests, and leave water,
the column and new process units as owner decisions.

## Findings

Ids continue the readiness lists: THERMO_CORE_REVIEW 2.4, THERMO_ADAPTERS_REVIEW 2.9, FLUID_STEP_REVIEW 3.7,
FLUID_LAWS_REVIEW row 24 (L-F24), PLANT_COUPLING_REVIEW 3.6, FLOWSHEET_CONTROL_REVIEW P3-29, COLUMN_REVIEW SC1,
RUNTIME_REVIEW question 8, CLIENT_REVIEW G-C23, all under
[../2026-11-13-codebase-review/](../2026-11-13-codebase-review/README.md). Line numbers in those lists predate the
file splits of units 3 to 5; the symbols stand.

### A. Equilibrium engine and kernel

| Id | Site | What it does | Effect on a supercritical state | Class |
|---|---|---|---|---|
| A1 | `FluidTpEquilibrium.pureBandBox`, `CriticalBand.inBand` | Four reduced boxes: 1 `Tr` 0.95-1.1 × `Pr` 0.8-1.5; 2 `Tr` 0.90-0.97 × `Pr` 0.58-0.8; 3 `Tr` 1.05-1.2 × `Pr` 2-3; 4 `Tr` 1.0-1.1 × `Pr` 1.5-2 | Answers there are `RESEARCH_ONLY`; a failure is typed `CRITICAL_BAND` and becomes `CriticalBandHold`. Carbon dioxide: box 1 289-335 K × 5.9-11.1 MPa, box 4 304-335 K × 11.1-14.8 MPa, box 3 319-365 K × 14.8-22.1 MPa. Water: box 1 615-712 K × 17.7-33.1 MPa | Hold (owner rule: holds only for capability limits) |
| A2 | `CriticalBand.bandReason`, `BAND_TIE_LINE` 0.1, `BAND_STATIONARY_DISTANCE` 0.1 | A mixture split with a short tie line, or a failed split next to the feed, is in the band | A near-critical mixture flash that does not converge holds the island; `DryCore.merged` answers one fluid only below `mergeTieLine` 1e-8 | Hold |
| A3 | `DryCore.supercritical`, `singleClassification` | `SUPERCRITICAL_FLUID` only for one present compound above its own `Tc` and `Pc` | A mixture is `SINGLE_FLUID`, `SINGLE_VAPOR` or `SINGLE_LIQUID`; no caller reads the difference | Label only |
| A4 | `DryCore.singleLabel`, `PhaseIdentification.singleLabel` | A one-root mixture phase is `LIQUID` or `VAPOR` by `PIP > max(1, Z)` | The label flips on a line through a continuous region | Feeds B1 |
| A5 | `DryCore.twoPhaseResult` (`denseLiquidLike`) | `VAPOR_LIQUID` or `LIQUID_LIQUID` by `PIP` and `v < 3.9514 b` of both products | A dense supercritical solvent beside a liquid changes classification on those lines; the amounts do not move | Label only |
| A6 | `PengRobinsonKernel.prepareMathiasCopeman` | `c2`, `c3` dropped above `Tc` | `a(T)` has continuous value and slope; its curvature steps by `a_c c2 / Tc²`, so the residual heat capacity steps at `T = Tc` (X34; size to measure) | Physics, owner option |
| A7 | `WaterShare` (`FADE_START` 623.15 K to `Tc`), `SteamVirial` | The liquid gap fades to zero at 647.096 K; the gas gap is the second virial only | Supercritical water is the bare cubic: no density or enthalpy correction between dilute and dense | Data |
| A8 | `CubicPhaseEvaluator` (`derived`) | A volume translation needs a liquid root at its anchor | A compound supercritical at its anchor has no translation (hydrogen) | Data |
| A9 | `SpecificationSearch`, `BracketSearch` (`straddlesPureCoexistence`, `coexistenceTemperature`, `samePhases`) | A label change between bracket ends is read as a first-order jump; a secant needs equal labels | Safe today because a pure fluid above `Tc`, `Pc` is `FLUID`; a relabelled mixture end costs evaluations, no wrong answer | Cost |
| A10 | Datasets' `fluid_domain` | 45 of 47 at 2 MPa, nitrogen and methane at 10 MPa; every one to 900 K | Answers above are `EXTRAPOLATED` with a notice; no hold | Data |

### B. Fluid network

| Id | Site | What it does | Effect | Class |
|---|---|---|---|---|
| B1 | `FluidThermodynamics.placed`, `HydrocarbonModel.liquidLike` | A `FLUID` phase goes to the gas or the oil slot by `PIP > max(1, Z)` | The inventory changes slot on a line inside one phase; every row below then jumps | Structure |
| B2 | `NetworkStreams.phaseVelocityLimit` | Gas: min(configured, acoustic); liquid: configured | A dense fluid in the gas slot gets an acoustic bound of a nearly incompressible fluid (harmless: it is high); one in the oil slot loses the bound where it is still compressible | Law |
| B3 | `HeatCurve` films by vapour mass fraction; L-F24 | Liquid film below a fraction, gas film above | 2000 against 100 W/m²K across the slot line | Law |
| B4 | `MoverSuction.drawnLiquid`, the bubble floor, `ReleasePressure.leaveOutGases` | A pump's floor needs a liquid label and a bubble pressure; compounds above `Tc` are left out | A dense fluid in the gas slot has no floor; in the oil slot the bisection looks for a first vapour that never comes | Law |
| B5 | `PassiveStepSolver` mover row (`kappa` 1.4 for a liquid mover) | Heat capacity ratio by mover kind | A pump on a compressible dense fluid books the wrong heat | Law |
| B6 | `PhaseDraw`, `PassiveNetwork.PhasePort` (`VAPOR`, `LIQUID`, `BOOT_WATER`, `BOOT_HC`) | Ports select phases by kind; a `VAPOR` port is dry when a slack vessel holds no gas | A vessel full of one dense fluid feeds only the port its label names | Law |
| B7 | `PhaseLayout` ("nearly liquid-full": gas volume under 1 %), level head, spray riser (`liquidDensity`, `liquidMass`) | Level and head weigh the liquid slots | A one-phase vessel reads full or empty by label | Law, display |
| B8 | Valve cavitation and flashing by vapour pressure; `boilUp` divides by a latent heat; `Pool` | Subcritical laws | No vapour pressure above `Tc`; the latent heat tends to zero at the critical point | Law |
| B9 | `BalanceStart` tables | A junction is liquid, vapour or both; gas density scales with pressure (ideal gas) | A cold start of a dense line starts from an ideal-gas density (`Z` 0.2 to 0.5: two to five times off) | Seed only |
| B10 | `NetworkWater.saturationPressure`, `WaterSaturation.pressure` | `+∞` at and above `Tc` | A jump of the read at 647.05 K; C4 of the review is one consumer | Defect class |
| B11 | `MixtureViscosity.liquidSource` | A supercritical compound in the oil slot reads its dilute gas viscosity | Continuous across the slot line, but low for a dense fluid; friction and films follow | Data |
| B12 | `FluidDomain.requireInput`, `FluidDeviceSpec`, `FluidNetwork` settings | A source, reservoir, regulator or heater setting outside the hull (10 MPa, 900 K) is refused | Supercritical water cannot be set; carbon dioxide only to 10 MPa | Input box |
| B13 | `FluidFacts` slots (gas and two liquids), `LegacySplit` | Facts carry the slot amounts | No change needed: a dense phase is one slot's amounts. A new saved field would be an appended fact with a corpus image | Format (none) |
| B14 | `GasBlanket` (ideal-gas pad), `TowerTransfer` (saturation at the plume) | Low-pressure laws | Out of range of any supercritical state; no work | None |

### C. Machines, heat units, simulator

| Id | Site | What it does | Effect | Class |
|---|---|---|---|---|
| C1 | `GasEntrainment` (Turpin), `Wear` (`WET_GAS`, cavitation, running dry) | A pump "takes gas" and a compressor "takes liquid" by the slots' shares | A machine on one dense fluid wears or degrades by its label | Law |
| C2 | Pump NPSH3 floor, the simulator's `FlowThermo` `p_v`, `MoverUnit` bubble warning | Need a vapour pressure | "No saturation" must mean no cavitation, not an infinite or zero margin | Law |
| C3 | `CompressorSizing` (polytropic exponent from `k`), compressor laws | Ideal-gas head with the heat capacity ratio | A dense-phase compressor needs the real-gas head (`Z`, the real isentropic exponent); error to measure at `Z` 0.3 to 0.6 | Law, owner option |
| C4 | `EjectorUnit`, `Ejectors`, `PrimeMoverUnit` (HRSG knot), `SteamTurbineDesign` | Water saturation for motive steam and condensing | Subcritical steam only; a supercritical steam cycle is out of reach | Capability |
| C5 | Equipment designs | No design pressure class on pipes or vessels was found | Nothing stops 30 MPa in a drum; a rating is a separate feature | Note |

### D. Column, still, presentation

| Id | Site | What it does | Effect | Class |
|---|---|---|---|---|
| D1 | `V3FeedFlash` | A feed above its mixture critical point converges to the trivial solution and is refused | Should be answered as one phase (F14 of the review) | Defect |
| D2 | V3 trays (`V3ThermoModel`, `V3PengRobinsonSession`), `INTERIOR_PHASE_IDENTITY`, `StillColumn` | Two roots per tray | A tray above the mixture critical point cannot exist: a capability limit, to stay a typed refusal | Capability |
| D3 | `V3WaterProperties` (Wagner-Pruss refuses above `Tc`, latent heat 0), `Stills` (`LATENT_CEILING`), `SplitTable` | Subcritical water | Unchanged while the column stays subcritical | None now |
| D4 | Client phase tabs and rows (`GAS`, `OIL`, `AQUEOUS`), the hold log's text | Three labels | A dense fluid is shown as gas or oil; a label "dense fluid" can be derived at presentation with no saved field | Display |

## What already exists and is reused

- `PhaseTraits` on every fluid phase of an engine answer (root count, `Z`, `v/b`, the identification parameter; wave I
  step 1 of the review, `PhaseTraitsTest`).
- CoolProp parity tables for carbon dioxide, nitrogen, methane, ethane, hydrogen and water
  (`src/test/resources/science/thermo/coolprop/`, `tools/coolprop-parity/`), `HelmholtzReferenceParityTest`, and the
  qualification families `G3F1` (pure-fluid oracle), `G3F5` (critical-region path), `G3F10` (qualified region).
- `EngineDigestTest` (429 requests, raw bits): proves that a change moves no subcritical answer.
- `DataRangeNotices`: the non-blocking status line a dense state can report through.

## Not checked

- (Read 2026-11-15, `tools/unified-thermo-port-forms/hold-fuzz-*/src/HoldFuzzTest.java`.) The hold-fuzz campaigns do not
  cover the dense window: the bundled sets' sources are water and crude at 0.1 to 0.4 MPa; the cold set's are LNG at 110
  to 170 K up to 6 MPa (a compressed liquid above methane's `Pc`) and a carbon dioxide-rich gas at 1 to 6 MPa (below its
  `Pc`). A dense supercritical state arises there only if such a stream warms past 190.6 K at pressure.
- The size of A6, A7, B9, B11 and C3 in numbers.
- Any behaviour in the game.
