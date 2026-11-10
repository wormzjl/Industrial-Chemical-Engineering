# Prime movers: combustion engines, gas turbines, generators to 400 MW, steam turbines to 300 MW

Status: **Implemented 2026-11-10** (0.25.0; merged into the custom-shader-system batch at 6c597e7b3, owner: "Merge it"), after the owner's model review (13 fixes, 0cf7d5e11). **Follow-up merged 2026-11-10** (3e123f51c, owner: "Merge it"): working sound and animation (owner: "add working sound & animation to them"), the owner's sound picks installed. Branch `claude/prime-movers-4b7224`, cut from `claude/custom-shader-system-caff9e` at `841e765f2`.

## Scope

Owner, 2026-11-10: "Add combustion engine which worked on liquid fuels, and gas turbine for gas fuels; should support MW scale size (also make bigger generators if their size doesn't match)". Fixed: "To 300 MW"; exhaust "Own stack + steam coil"; fuel gas "Real supply pressure".

## Documents

- [PRIME_MOVERS_PLAN.md](PRIME_MOVERS_PLAN.md): Willans-line efficiency, fuel from LHV, air and exhaust, the radiator, GT pressure ratio and fuel supply rule, models, governor, generators to 300 MW and the FE arithmetic, the pinch-designed steam coil, saves; decisions P1 to P14, the owner's answers (12.1), the later changes P12 and P15 (12.2) and the build (12.3).

## History

- 2026-11-10: plan written (revision 1), sent to the owner.
- 2026-11-10: owner "It shall not overspeed, and keep the model simple. Others as recommended" (plan 12.1): P11 no overspeed at all (governor caps at the set speed, set speed at most rated, no trip); all others as recommended.

- 2026-11-10: build, WIP commits 845eb69d7 (law, kinds, fuel void, coil, FUEL driver, checkpoint 46), 66e644f05 (facts schema 20 and corpus image, simulator tiles and sizing, constructor Power category), 4d0595201 (smoke, sounds, machine page, en_us), 4666870de (layout rows, drawing scenes), 42fee999e (steam turbine to 300 MW as tandem sections, unit tests).
- 2026-11-10: owner changed P12 (steam turbine to 300 MW as one unit, "HP=HP+MP+LP, MP=MP+LP, LP=LP") and dropped P15 (several turbines on one shaft); plan 12.2.
- 2026-11-10: targeted tests passed: PrimeMoverTest, ExhaustCoilTest, ShaftTrainFuelTest, MotorTest, ShaftTrainTest, SteamTurbineTest, FluidFactsCompatTest, FluidCheckpointCodecTest, FluidPacketCodecTest, ShaftPlanTest, ElectricStoreTest, FlowsheetPaletteCoverageTest, SizingCoverageTest, HeatDiagramTest, MachineColumnTest, LimitTest, TurbineSizingTest, PipePresentationTest; equipmentGates EquipmentLayoutGoldenTest, SteamTurbineSectionsTest. Drawings: research copies of `tmp/equipment-drawings/20261110-114345-0c81a0` (engine, gas turbine, turbo-generator) and `20261110-115108-ddd44b` (steam turbine tandem).
- 2026-11-10: owner on the engine and gas turbine models: "engine and turbine doesn't like it at all"; asked which parts look wrong, models on hold.
- 2026-11-10: owner model review (relayed by the parent session) applied: gas turbine G1 to G5 (stack about four enclosure heights on a rectangular-to-round transition, open dark top; HD casing with diffuser, combustor cans, split flange and bolt row, inlet plenum, raised filter house on an inlet duct with silencer; weather hoods on every filter house; louvre bars and roof fans; HRSG casing above the package, expanding inlet duct, headers carrying FEEDWATER and STEAM), turbo-generator T1 (round stator, dished end shields, exciter housing, finned coolers, HV box underneath; a gas turbine's shaft at its generator's height, `MotorDesign.generatorShaft`), engine E1 to E6 (medium-speed sets taller and longer with a radiator bank beside, fans up; snail turbochargers, two from 15 MW; inclined V banks with head covers and manifolds; crankcase doors; grilled fans), steam turbine S1 (double-flow LP 1.7 Dc across). Layout table: new-kind rows only. The equipment gates' test heap set to 1 GiB (the layout table's geometry cache measured 533 MB of 512 MiB). Drawings `tmp/equipment-drawings/20261110-121817-17a3f5`.
- 2026-11-10: owner "add working sound & animation to them", "engine sound not good"; picked sound candidates, start and stop sounds, exhaust puffs, visible turning parts. Built:
  - `tools/process-sounds/prime_candidates.py`: engine candidates A drone, B beat, C knock, each a high-speed loop (1,500 rpm, V16, 200 Hz firing) and a medium-speed loop (600 rpm, V18, 90 Hz; the pitch clamp 0.5 to 2 cannot stretch one loop from 500 to 1,500 rpm); gas turbine A whine, B roar, C inlet hiss; in `tmp/prime-sounds/` and the main checkout's `research/2026-11-10-prime-movers/sounds/`. The running loops stay until the owner picks.
  - Start and stop one-shots (`engine_start_air` for 750 rpm and under, `engine_start_electric`, `engine_stop`, `gas_turbine_start`, `gas_turbine_stop`), played client-side by `DriverSounds` on the speed's crossing of zero between two publications it saw; no new server state.
  - Exhaust (physics correction: 80 to 200 Hz firing is continuous to the eye): an engine's smoke pulses with its eddies (`PlumeModel.Style.pulsing`, the puffs' phases alternate in density), runs dark for 4 s at a start and after a load rise of more than 10 % of its rated fuel (turbo lag); a gas turbine's stays a steady haze (`FxSources.HeaterSource`).
  - Turning parts: a load shaft spool with its disc pack in an open bar guard at each set's drive end (`ProcessUnit.loadShaft`), a torsional damper on the engine's free end (`ProcessUnit.damper`); new-kind layout rows only, no nozzle moved.
  - Tests: PlumeModelTest, PipePresentationTest, MotorTest, equipmentGates EquipmentLayoutGoldenTest. Drawings `tmp/equipment-drawings/20261110-123329-af0de3`.
- 2026-11-10: owner picks: gas turbine "A whine" (installed as `gas_turbine_running.ogg`), one-shots "Keep them", engine "C but needs to be cleaner": C1 (modal knock rings, clean 320 Hz slap, jitter halved, floor and grit 13 dB down) and C2 (no broadband but a faint low rumble, short modal clicks), each a high-speed and a medium-speed loop, sent for the owner's pick.
- 2026-11-10: owner picked engine **C1**: `engine_running.ogg` (high-speed sets, authored at 1500 rpm) and the new `engine_running_medium.ogg` (medium-speed sets, 750 rpm and under, authored at 600 rpm; `UnitDesign.mediumSpeedEngine`, `ENGINE_MEDIUM_LOOP_RPM`), its sound event and subtitle line (en_us only). PipePresentationTest checks each family's pitch (1500 rpm 1.0, 750 rpm 1.25, 500 rpm 0.83).

## Code areas

`science/machine/PrimeMover`, `science/fluid/network/ExhaustCoil`, `science/equipment/PrimeMoverDesign`, `SteamTurbineDesign` (sections), `runtime/fluid/PrimeMovers`, `ShaftTrains`, `ShaftTrain` (`Drive.FUEL`), `FluidWorldAuthority` (`placePrimeMover`, the flue watch), `MachineView.Fuel`, `MachineColumn` (`PRIME_MOVER`), `science/flowsheet/unit/PrimeMoverUnit`, `sizing/PrimeMoverSizing`, `tools/process-sounds` (two loops).

## Open items

- In-game listen and look (sounds, puffs, turning parts): not done; the owner may start one check.
- Translation commit for the new subtitle lines (the follow-up's merge).
- Not run: LanguageFilesTest and UnitTextTest (wait for the translation commit), the full suite and the final lane (merge preparation).
- Code guide chapter (rotating machinery) and CHANGELOG at merge preparation.
