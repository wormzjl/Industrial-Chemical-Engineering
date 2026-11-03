# process-simulator

Purpose: prove on the shipped science that a client can run the process simulator, and supply the real numbers its design sheets show.

Batch: `2026-11-02-process-simulator` (`PROCESS_SIMULATOR_PLAN.md`, sections 1 and 2).

## How to run

From the worktree root, in PowerShell, after `./gradlew compileJava processResources`. The class path holds the mod's classes, its resources and gson, and no Minecraft classes, as a client's plain background thread would see the science:

```powershell
$g = "<gradle cache>\com.google.code.gson\gson\2.10.1\b3add478d4382b78ea20b1671390a858002feb6c\gson-2.10.1.jar"
java "-Dfile.encoding=UTF-8" -cp "build\classes\java\main;build\resources\main;$g" "tools\process-simulator\ClientRunProbe.java"
java "-Dfile.encoding=UTF-8" -cp "build\classes\java\main;build\resources\main;$g" "tools\process-simulator\SampleFlowsheetProbe.java"
java "-Dfile.encoding=UTF-8" -cp "build\classes\java\main;build\resources\main;$g" "tools\process-simulator\LoopProbe.java"
java "-Dfile.encoding=UTF-8" -cp "build\classes\java\main;build\resources\main;$g" "tools\process-simulator\SizingProbe.java"
java "-Dfile.encoding=UTF-8" "tools\process-simulator\EjectorProbe.java"
java "-Dfile.encoding=UTF-8" -cp "build\classes\java\main;build\resources\main;$g" "tools\process-simulator\StopGapProbe.java"
```

`SizingProbe` takes `-Dshell=bank` for option B of D18: it prints the sizes and leaves the table alone. `SampleFlowsheetProbe` takes `-Ds6bar=11.2` to measure what the sized drops move. `EjectorProbe` needs no class path. `vacuum-set-law.js` runs on Node: `node tools\process-simulator\vacuum-set-law.js`.

Git Bash mangles the `;` class path; use PowerShell.

## Entry points

- `StopGapProbe.java`: W7. The longest gap between two checkpoints of the sample on a normal and a low-priority thread, each pass's record, the W3 gate's sheet for its pass count, and S-8's bubble pressure against its draw tray's steam. It reads clocks, so run it alone; the argument `bubble` runs only the last part.
- `ClientRunProbe.java`: times the catalog, the neural bind, the default column (cold, three runs), a warm column at feed +1 %, +2 K, and TP and PH flashes; re-reads the column's balance in the stream thermo and prints the gap.
- `SampleFlowsheetProbe.java`: the sheets' crude unit, the owner's example, solved by hand in the order the plan's solver would take: P-101, the crude through T-101's three pumparound coolers at their door duties, then the loop E-101, H-101, V-101, T-101 torn at the bottoms, then the condenser's cooling water, P-102, E-102. Prints each pass, the 21 streams, the units' duties, the closures, the column's preset and tray profile, the phase splits and the compositions.
- `LoopProbe.java`: the loops. A, the sample with each pumparound's hot side at an assumed circulation (option B of decision D11). B, the furnace on a fixed duty 8 MW short, plain and with Wegstein, then torn at the bottoms. C, 15 and 30 % of the residue back to the crude through a mixer ahead of P-101, cooled to 70 C first. D, all of the residue back, where no steady state exists.
- `SizingProbe.java`: runs `SampleFlowsheetProbe`, then sizes each line and unit by `SIZING_PLAN.md`: the real column at each trial diameter, the world's laws for every drop and surface, bubble pressure by bisection on the flash, the two pressure paths. Prints each choice and the rules the plan's decisions rest on, and writes `sizing-sample.tsv` for the sheets. Its limits are the plan's, in one place. It also prints Kern's drop on each exchanger, the baffle pitch at which Kern equals the law, and the storage what-if.
- `FlashProbeTest.java`: the stream thermo's sweep: TP and PH across wet crude, dry crude, a heavy cut with water, a residue with water, water, nitrogen and light ends. Copy it into `src/test/java/com/wormzjl/icheme/science/flowsheet/` and run `./gradlew test --tests "com.wormzjl.icheme.science.flowsheet.FlashProbeTest" -i`. Remove the copy afterwards; no gate runs it.
- `YellowNoiseProbeTest.java`: S2's yellow-noise pass (`SIZING_PLAN.md` section 8). The six crude tower templates on the sample's layout, each run and sized by `SheetSizing`, printing every yellow and red row (lines start `NOISE`); a run that is not steady prints its log. Copy it into `src/test/java/com/wormzjl/icheme/client/gui/flowsheet/` (it reads `SheetFixture` and `V3TestInputs`), run `./gradlew test --tests '*YellowNoiseProbeTest' -i` and remove the copy. No gate runs it.
- `EjectorProbe.java`: W10's El-Dessouky ejector law of `SIZING_PLAN.md` section 3.12, superseded by `vacuum-set-law.js` (W10b's S1), with no game classes: steam per kg by suction, molar mass and motive pressure, the vendors' load factors, a vacuum column's top.
- `vacuum-set-law.js`: W10b's steam law S1 (section 3.12), which `EjectorLaw` now holds and `EjectorTest` pins. It finds k from El-Dessouky's air figure at a stage of 4, compares S1 with one El-Dessouky stage from 80 to 2 kPa, a three-stage chain and a single booster, and works four cases with S1 and the one-term S2.
- `FeedValveAtInletProbeTest.java`: the one-time check of a control valve straight against a heat side's INLET port (owner 2026-11-03, "one time test only"), the valve placed as a real world places it, a `Kind.PIPE` carrying a `FlowControl.ControlValve` (`FluidWorldAuthority.place`), not `EventPathWorld`'s `Kind.VALVE`. Copy it into `src/test/java/com/wormzjl/icheme/runtime/fluid/` (it reads `EventPathWorld`), run `./gradlew test --tests '*.FeedValveAtInletProbeTest' -i` and remove the copy. No gate runs it.

## Results, 2026-11-02, at `79b853e0`

Catalog 220 ms, bind 187 ms, column cold 1.7 to 2.5 s, warm 0.25 to 0.31 s, TP flash 0.06 to 0.4 ms, PH flash 0.3 to 0.5 ms. The sample: 2 passes, 3.6 s from a cold JVM on one thread, mass closed, heat gap 0.001 MW of 187 MW, furnace 78.37 MW.

Loops, one warm column a pass, to 0.01 K or 0.01 % of the flow:

| Case | Plain passes | Wegstein passes |
|---|---|---|
| B, furnace on 70.37 MW | 12, 8.1 s | 5, 3.5 s on the feed temperature; 7, 4.7 s torn at the bottoms |
| C, 15 % back | 5, 2.8 s | 4, 2.3 s |
| C, 30 % back | 8, 4.2 s | 5, 2.7 s |
| D, all back | 30, not closed, 26.3 s; column feed 24026 kmol/h, every column solved | Trial step at pass 3 fails the column |

In D the heaviest cut with a real share of the crude, `crude_pc09`, gains 175.249 kmol/h on every pass: a slope of exactly 1.

Sizing at 110 % of the steady flow: 16 units and 31 lines. T-101 stays 8.0 m (design flood 71.3 %; 7.5 m gives 80.9 %), H-101 takes a cabin 11.0 × 41 m for 86.2 MW, S-7 takes the 0.80 m limit. All 44 Musts and 45 Shoulds met. P-101's path loses 3.66 of 9.00 bar, the residue's 0.47 of 0.99 bar.

Shell drops: Kern at 0.4 D is 10 times the law on all five exchangers; the law equals Kern at 0.92 to 0.93 D. With S-6 at 11.2 bar, what the path's units leave it at the run's flow, H-101 is 74.25 MW and T-101's feed 350.5 °C, in 2 passes.

The probes stand in for the plan's solver and sizing code: they are not them. `LoopProbe` lags nothing but the tear, and it has no fallback after a failed trial step.

## Flash findings, 2026-11-02

Findings 1 to 3 were taken while W2 built `FlowThermo`, which is why a stream searches along the TP flash. All on the bundled network package, with the numbers as they stood that day. All four are engine findings, reported to the owner. Findings 1 and 3 were fixed on main in 0.18.1 (batches `2026-11-02-ph-false-root`, `2026-11-02-tp-steam-refusal`). Finding 2 stands with the owner, and it is the reason `FlowThermo` keeps its TP search.

1. The PH flash has a false root on wet oil with volatile components. `FluidTpEquilibrium.solvePh` calls `closedPh` as soon as a bracket's ends read as `condensedStep`, however wide, and `waterBoiling` answers at pure water's saturation with no hydrocarbon vapour. Crude with 20 mole % water: at 2 bar every TP state from 376 to 382 K answers 393.36 K; at 30 bar, 500 K answers 507.00 K. The TP flash there holds 8 to 14 mol of hydrocarbon vapour per 100, and its three-phase zone (482.5 to 488.7 K at 30 bar) is continuous, so no step exists. The answer's enthalpy is the target, so nothing flags it.
2. Under the melting line the PH flash answers ice, and `adopt` carries that ice as the liquid stand-in (D23), so the state's enthalpy misses the target by up to the latent heat. 100 mol/s at 5 bar: 273.12 K, 400 kW off. The TP flash holds a smooth supercooled stand-in there.
3. The TP flash refuses a heavy cut with steam: "the hydrocarbon partial pressure closing the gas volume was not found", at about 1e-6 Pa. 12 of 216 grid points on one heavy pseudo-component with water, 2 of 216 on a residue with water. The PH flash answers 10 of 11 such targets.

The search answers every grid target the fluid can reach within 0.06 W on 100 mol/s; a TP flash takes about a third of a PH flash, 1.6 against 5.8 ms in a test JVM.

4. (2026-11-03, S3, logged in S4a.) V3 and the world's flash disagree at the sample's top tray. V3 gives the top tray's vapour, 74.4 kg/s, at 354.84 K and the tray's pressure. The world's TP flash of that composition at that temperature and pressure keeps only 60.4 kg/s as vapour and condenses 19 % of it, with 153.5 mol/s of liquid water. Sizing reads the energy-consistent overhead instead (the condensate heated back by the condenser's duty), so no sizing number depends on it. The gap is the engine's, between V3's thermo and the stream thermo. Not investigated further, on the orchestrator's word.

## History

- 2026-11-02: written for the batch's design phase. The numbers in `tools/heat-gui-renders/SimulatorPrototypes.java` are copied from `SampleFlowsheetProbe`'s and `LoopProbe`'s output; a change in the sample changes both.
- 2026-11-02: the owner's note on blocks and loops. `SampleFlowsheetProbe` moved to the owner's example, `LoopProbe` added.
- 2026-11-02: the owner asked for a sizing stage. `SizingProbe` added; `SampleFlowsheetProbe` keeps its column input and result for it.
- 2026-11-02: the owner's note on types, drops and the ejector. `SizingProbe` goes by surface and drops, proposes the heater's type and writes the storage what-if and the door's drops; `EjectorProbe` added.
- 2026-11-02: W2. `FlashProbeTest` added, with the flash findings above.
- 2026-11-02: W3. `FlashProbeTest` follows `FlowThermo`'s constructor.
- 2026-11-02: W5. The probes' feeds still read the bundled crude: the assays moved onto `icheme:tjl20_methane`, and the results above were taken before that move.
- 2026-11-03: W7. `StopGapProbe.java`: longest gap 38.9 ms on a normal thread; the sample's third pass is H-101's 90 MW limit; S-8's bubble pressure is 74.2 % of its own, the oil's partial pressure on its steam-stripped tray. The numbers are in the batch README.
- 2026-11-03: S2. `YellowNoiseProbeTest.java` added: Tia Juana 0 of 154 rows yellow, Upper Zakum 1 of 147, WTI Light 2 of 147, 0 red; Bonga, Cold Lake and Dalia fail in T-101 on the sample's settings. The numbers and reasons are in `SIZING_PLAN.md` section 8. `SizingProbe`'s one heat capacity along a duty gives E-101 444 and E-102 741 m²; S2's curves give 440 and 724, which moves E-102's bank (plan section 4).
- 2026-11-03: S3. `SizingProbe` is unchanged; where `ColumnSizing` and `SheetSizing` differ from it, the code is the reference. The probe flashes the overhead at the top tray's temperature and pressure, where the world's flash condenses 19 % of it; the code heats the condensate back by the duty, so the condenser goes from 3 × 1.25 × 4 m (0.18 bar) to 5 × 1.00 × 3 m (0.15 bar) and the overhead line reads GAS at 0.80 m. The probe's receiver read cylinders, the code the world's boot with its head: 7.0 to 7.7 min, same size. The probe's tray liquids flashed at 3 bar over the tray, the code's at the tray; its column is 29.8 m tall, the world's tangent length 29.0 m; its reflux head 45 m, the column's own levels give 42.5 m. T-101, the PA coolers and pumps and the ten member lines equal it.
- 2026-11-03: S4a. `SizingProbe` is unchanged; `SizeAllSampleTest` reads its PATH and PATHSUM rows. Three differences, each from the physics, and the code is the reference. H-101's coils lose 2.11 bar at design flow, not 1.95: the probe started the radiant march at the heater's inlet pressure, where in the world it starts at the convection coil's outlet, 0.46 bar lower, so more crude flashes in it (convection 0.46, radiant 1.55 from the inlet's pressure against 1.65 from the convection outlet's: 0.11 bar); the built cabin holds 87.6 MW, not the design's 86.2, on the same 11 passes, so its coils are 1.6 % longer (about 0.05 bar). P-101's path counts the transfer line S-6, 0.03 bar, which the probe's path ended before, and ends at V-101's set 2.72 bar rather than at H-101's outlet on the doors' 5.00: 11.28 bar available, 3.85 lost, 7.43 spare, which V-101 takes. The residue's path loses 0.48 of 0.99 bar, not 0.47: E-102's bank (S2). The probe's `-Ds6bar=11.2` estimate is 11.31 on the code's run on sized drops, which reads the coil at its own pressure.
- 2026-11-03: W10b proposal. `vacuum-set-law.js` added: k = 1.594 from El-Dessouky's 2.21 kg/kg at 30 to 120 kPa; S1 against one stage, a three-stage chain from 5 kPa (4.10 kg/kg, S1 5.08) and a booster from 2 to 14.7 kPa (3.99, S1 4.05); four cases from 65 kg/h to 43.7 t/h.
- 2026-11-03: W10b built. `EjectorLaw` holds S1; `EjectorTest` pins its table, its motive factor and the four cases on the world's vapour pressure. `EjectorProbe` stays as W10's record of El-Dessouky's law.
- 2026-11-03: `FeedValveAtInletProbeTest.java`, run once and detached (owner: "one time test only"). A water generator at 3 bar, the valve pipe straight against the tube side's inlet, the side's outlet through a pipe to a 2 bar void: the generator, the valve, the pipe, the void and the side in one island, no diagnostic, no hold. A mass-flow valve (0.05 kg/s, a sensor) passes 0.050000 kg/s; a bare unconfigured valve stands open and passes 24.49 kg/s. U2's dead end came from the test world's `Kind.VALVE`, a non-pipe device no real block makes.
