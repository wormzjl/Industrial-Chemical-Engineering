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
```

`SizingProbe` takes `-Dshell=bank` for option B of D18: it prints the sizes and leaves the table alone. `SampleFlowsheetProbe` takes `-Ds6bar=11.2` to measure what the sized drops move. `EjectorProbe` needs no class path.

Git Bash mangles the `;` class path; use PowerShell.

## Entry points

- `ClientRunProbe.java`: times the catalog, the neural bind, the default column (cold, three runs), a warm column at feed +1 %, +2 K, and TP and PH flashes; re-reads the column's balance in the stream thermo and prints the gap.
- `SampleFlowsheetProbe.java`: the sheets' crude unit, the owner's example, solved by hand in the order the plan's solver would take: P-101, the crude through T-101's three pumparound coolers at their door duties, then the loop E-101, H-101, V-101, T-101 torn at the bottoms, then the condenser's cooling water, P-102, E-102. Prints each pass, the 21 streams, the units' duties, the closures, the column's preset and tray profile, the phase splits and the compositions.
- `LoopProbe.java`: the loops. A, the sample with each pumparound's hot side at an assumed circulation (option B of decision D11). B, the furnace on a fixed duty 8 MW short, plain and with Wegstein, then torn at the bottoms. C, 15 and 30 % of the residue back to the crude through a mixer ahead of P-101, cooled to 70 C first. D, all of the residue back, where no steady state exists.
- `SizingProbe.java`: runs `SampleFlowsheetProbe`, then sizes each line and unit by `SIZING_PLAN.md`: the real column at each trial diameter, the world's laws for every drop and surface, bubble pressure by bisection on the flash, the two pressure paths. Prints each choice and the rules the plan's decisions rest on, and writes `sizing-sample.tsv` for the sheets. Its limits are the plan's, in one place. It also prints Kern's drop on each exchanger, the baffle pitch at which Kern equals the law, and the storage what-if.
- `FlashProbeTest.java`: the stream thermo's sweep: TP and PH across wet crude, dry crude, a heavy cut with water, a residue with water, water, nitrogen and light ends. Copy it into `src/test/java/com/wormzjl/icheme/science/flowsheet/` and run `./gradlew test --tests "com.wormzjl.icheme.science.flowsheet.FlashProbeTest" -i`. Remove the copy afterwards; no gate runs it.
- `EjectorProbe.java`: the ejector law of `SIZING_PLAN.md` section 3.12, with no game classes: steam per kg by suction, molar mass and motive pressure, the vendors' load factors, a vacuum column's top.

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

Taken while W2 built `FlowThermo`, which is why a stream searches along the TP flash. All on the bundled network package, with the numbers as they stood that day. These three are engine findings, reported to the owner. Findings 1 and 3 were fixed on main in 0.18.1 (batches `2026-11-02-ph-false-root`, `2026-11-02-tp-steam-refusal`). Finding 2 stands with the owner, and it is the reason `FlowThermo` keeps its TP search.

1. The PH flash has a false root on wet oil with volatile components. `FluidTpEquilibrium.solvePh` calls `closedPh` as soon as a bracket's ends read as `condensedStep`, however wide, and `waterBoiling` answers at pure water's saturation with no hydrocarbon vapour. Crude with 20 mole % water: at 2 bar every TP state from 376 to 382 K answers 393.36 K; at 30 bar, 500 K answers 507.00 K. The TP flash there holds 8 to 14 mol of hydrocarbon vapour per 100, and its three-phase zone (482.5 to 488.7 K at 30 bar) is continuous, so no step exists. The answer's enthalpy is the target, so nothing flags it.
2. Under the melting line the PH flash answers ice, and `adopt` carries that ice as the liquid stand-in (D23), so the state's enthalpy misses the target by up to the latent heat. 100 mol/s at 5 bar: 273.12 K, 400 kW off. The TP flash holds a smooth supercooled stand-in there.
3. The TP flash refuses a heavy cut with steam: "the hydrocarbon partial pressure closing the gas volume was not found", at about 1e-6 Pa. 12 of 216 grid points on one heavy pseudo-component with water, 2 of 216 on a residue with water. The PH flash answers 10 of 11 such targets.

The search answers every grid target the fluid can reach within 0.06 W on 100 mol/s; a TP flash takes about a third of a PH flash, 1.6 against 5.8 ms in a test JVM.

## History

- 2026-11-02: written for the batch's design phase. The numbers in `tools/heat-gui-renders/SimulatorPrototypes.java` are copied from `SampleFlowsheetProbe`'s and `LoopProbe`'s output; a change in the sample changes both.
- 2026-11-02: the owner's note on blocks and loops. `SampleFlowsheetProbe` moved to the owner's example, `LoopProbe` added.
- 2026-11-02: the owner asked for a sizing stage. `SizingProbe` added; `SampleFlowsheetProbe` keeps its column input and result for it.
- 2026-11-02: the owner's note on types, drops and the ejector. `SizingProbe` goes by surface and drops, proposes the heater's type and writes the storage what-if and the door's drops; `EjectorProbe` added.
- 2026-11-02: W2. `FlashProbeTest` added, with the flash findings above.
- 2026-11-02: W3. `FlashProbeTest` follows `FlowThermo`'s constructor.
- 2026-11-02: W5. The probes' feeds still read the bundled crude: the assays moved onto `icheme:tjl20_methane`, and the results above were taken before that move.
