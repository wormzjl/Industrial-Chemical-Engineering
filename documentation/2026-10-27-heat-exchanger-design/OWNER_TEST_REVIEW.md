# Owner test of the review world (2026-10-28)

The owner's findings from `REVIEW_WORLD.md`, each as expected behaviour, what the code does, and a proposal. Items marked "to reproduce" wait until the dev client is closed (AGENTS.md: no test run while a client runs). The removal of the old pump and compressor blocks runs in its own session.

## 1. Fired heater: the convection duty

- **Expected.** The burners release the fired duty F. The radiant box absorbs most of it (here 70 % of the absorbed duty); the flue gas then passes the convection section, which takes a fixed share (30 %). So the convection duty follows the radiant duty, Q_c = k · Q_r with k = 0.30 / 0.70 = 0.43, and it is zero when the burners are out.
- **The model now (W6).** Each coil heats its own stream toward the heater's set point: Q = β · reach · ṁ · (h_set − h_in), reach 1 for the radiant and 0.3 for the convection coil, each with its own duty cap (70 % and 30 % of the heater duty) and its own firing share β. In the review world's series arrangement at β = 1 this gives exactly Q_c = 0.43 · Q_r (481 kW against 1121 kW). It does not when a coil is duty-limited (each β is planned alone), or when the convection coil carries another stream (it then heats that stream 30 % of the way to the radiant's set point, whatever the radiant fires).
- **Proposal A (recommended).** One firing per heater, set on the radiant coil: Q_r = min(ṁ_r (h_set − h_in,r), radiant duty). The convection coil takes Q_c = k · Q_r. It is capped so that its outlet stays below the flue gas leaving the radiant box, and it is zero without convection flow (that heat goes up the stack). The two coils in one island (series) are planned in the same step; coils in two islands read each other through the W3 relay, like an exchanger's sides. Option on the cap: A1 a fixed flue temperature (800 °C); A2 no cap (simplest; at a small convection flow its outlet can run hot without bound).

## 2. Air cooler: overcooling at low flow

- **Expected.** With the fans stopped, natural draught still moves air over the bundle: about a third of the design capacity (GPSA quotes 25 to 40 %). At a low process flow that alone cools the stream to near the air temperature. Plants hold the outlet with louvers that shut the air path, or with a hot bypass around the cooler.
- **The model now (W4, W5).** The fans' effort runs from still (natural draught, films 0.29 to 0.67 of full fans) to full. At its lowest the status reads "LIMITED: the fans stand still, natural draught moves more". In the review world's cooler (natural draught UA 4.2 kW/K, inlet 85 °C, set point 80 °C), the fans stopped already overcool any flow under 11.5 kg/s: 74 °C at 5 kg/s, 47 °C at 1 kg/s. So the controller does what the model allows; the model has no actuator below natural draught. To confirm: the status the owner saw (if it did not read LIMITED, that is a separate defect, to reproduce).
- **Owner direction (2026-10-28).** "AC should be able to adjust its duty to match target T as well as furnace": like the burners, the cooler turns its duty down to whatever holds the target, at any flow.
- **Options for the mechanism.** (a) Louvers, recommended: one actuator whose effort runs from shut louvers (no air, no duty) through natural draught (louvers open, fans still) to full fans; the air films scale with the air flow. No new fact; the status names the louvers or the fans. (b) An internal hot bypass, as the exchanger's: also exact at any flow, reuses the exchanger's bypass code; less common on liquid coolers. Either way LIMITED remains only at full fans.

## 3. Fired heater: convergence at a 100 kPa generator (to reproduce)

- **Expected.** The pump adds at most 5 bar. From a 1 bar generator it reaches 6 bar, short of the 10 bar void, so no forward flow is possible: the pump stalls, the coils carry no flow and the burners have nothing to heat (zero heat at zero flow). The island should report no flow, not hold.
- **The code.** The owner's client log: island 377 HELD at tick 23754, "Newton line search stalled at residual 1.787; active-set pass 1", repeated every few seconds. Suspects: reverse flow from the 10 bar void through the coils toward the pump (the fired coil heats |ṁ|, so a reversed stream is fired too, into a liquid-only pump), or the pump at its head limit against a boiling coil. To reproduce from the owner's saved world before any proposal.

## 4. Exchanger: slow to steady (to reproduce)

- **Expected.** Fixed inlets and a fixed set point give a fixed bypass, wall and outlets. A quasi-steady exchanger (Q10) settles in about one residence time of its outlet junctions, tens of seconds, and both islands then certify STEADY and stop solving.
- **The code.** W3's gate certified both sides at 125 s without a controller. W4 added the bypass, and a side's commit wakes its certified partner when the bypass moves by more than 0.001, its stream by 0.05 K or 0.1 % of flow, or the transit by 0.1 % of a slice's heat (`IslandCoordinator.moved`). A dead-beat controller that ignores the give-back can move its bypass a little at every slice. The two sides can then keep waking each other, and the screens keep changing. In the review builds, both exchangers showed FULL, not STEADY, at 40 s while the other units were STEADY.
- **Reproduction.** A probe run (`runReviewWorldServer -PreviewProbe=true`, its own folder `run/review-probe`) watches every unit for 400 s after the build, with the coupling-wake counter on. The proposal follows from what it shows.

## 5. Water vapour pressure range

- **Expected.** Saturation from the triple point to the critical point, and steam that is non-ideal at plant pressures. Against IAPWS-IF97 Region 2, saturated steam has Z 0.985 at 1 bar, 0.930 at 10 bar and 0.669 at 100 bar.
- **The code.** Water's saturation pressure already spans 273.16 to 647.096 K (`icheme:water`, `water-iapws-shomate-r2`). That is the IAPWS equation of Wagner and Pruss (1993), within 2e-4 of IF97 from 0.01 to 200 bar. The notice comes from the steam instead: free water's vapour is an ideal gas, qualified within 2 % of NIST up to `FluidThermodynamics.waterVaporPressureLimit`. That limit is 125 kPa below 400 K, 250 kPa from 450 to 500 K and 2 MPa from 900 K. At 10 bar the ideal steam is 7.6 % too light and its latent heat about 3 % high; at 100 bar, 33 % and 28 %.
- **Options (owner).** The identity for both is the same: `SEPARATE_FREE_WATER_FORMULATION` v2 → v3. No material revision moves, so the column identity and the neural sidecar stay valid. Contents are restated at load and no facts change.
  - B (recommended, a game-level continuation): a second virial coefficient from Region 2's five first-order terms. The closure stays explicit, and steam is within 2 % to about 12 bar at saturation, 49 bar at 584 K and 225 bar at 800 K. That covers both test cases, but not a 100 bar boiler.
  - A: the full Region 2 residual (43 constants). It is valid to the critical region and makes the closure implicit. AGENTS.md rules out new reference models unless you choose one.

## 6. Temperatures on the lines (furnace case)

- **Expected.** A line carries a stream, so opening any pipe shows that stream's temperature (and pressure), as a plant's line gauge would.
- **The code.** A plain pipe's screen has no temperature row in any case: `FluidDeviceScreen.readings()` builds it only for non-pipes. That has been so since the display unification `f44930f5`, with no recorded reason. A line shows a temperature only where it meets a generator, a pump, a valve or a unit side, or through a fitted gauge or probe. The furnace's crossover (convection out to radiant in, 180.5 °C two-phase) is the only line in the world whose two ends are both nozzles. Its temperature appears only inside the two coil screens. Neither the STEADY replay nor the notice plays any part.
- **Proposal.** A pipe's screen reads the stream it carries, from the pipe observation the temperature gauge already uses (the donor node: T, P and phase split). When the pipe is still it shows the last value marked "no flow". The Control tab's flowsheet (section 7) labels every line as well.

## 7. GUI redesign (mockup for approval: `HEAT_GUI_MOCKUP.svg`)

- **Control tab** (new, every heat unit):
  - The unit drawn as a flowsheet, in the column's part colours (teal in place, yellow misplaced, red missing). It shows the lines its control asks for (bypass: tee, valve, flowmeter, tee; ratio: feed valve, flowmeter), with temperature and flow on every line.
  - Beside it, the modes as a list, each with one line saying what moves. The actuator is named per unit: bypass, louvers and fans, burners. "Never cool below" replaces "Low limit"; "Acting now" names the actuator in use (internal bypass or the real valve). The status is in words, in the unit's temperature unit, and Check lines sits on the drawing.
- **Overview** keeps the readings and the phase table, with a stream switch above it: each inlet and outlet of the unit (kettle: tubes in and out, feed, vapour, liquid).
- **What it needs.** Each port's stream (T, P, flow, phases, composition) on the wire: fluid wire 15 → 16, no compatibility work. The lines report's part states sent to the client; today only status, missing count and valve.
- **Found in passing.** `gui.icheme.fluid.ratio_input` is defined twice in `en_us.json` (lines 30 and 1241), so the Ratio field likely reads "Max pressure ratio (out/in)". Statuses show K and a raw "/ FULL".
