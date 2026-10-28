# Owner test of the review world (2026-10-28)

The owner's findings from `REVIEW_WORLD.md`: each gives the expected behaviour, what the code does, and a proposal. Reproduced with a probe on the review-world run: `-PreviewProbe=true` rebuilds the plant and applies the changes; `owner` watches a copy of the owner's save. Both write to `run/review-probe`, and the probe is scratch until the cleanup. The old pump and compressor removal and the GUI build (sections 6 and 7) run in their own sessions.

## 1. Fired heater: the convection duty

- **Expected.** The radiant box absorbs a fixed share of the fired duty (70 % of the absorbed duty), and the flue gas then gives the convection section its share (30 %). So Q_c = 0.43 · Q_r, and Q_c is zero with the burners out.
- **The model now (W6).** Each coil heats its own stream toward the set point: Q = β · reach · ṁ · (h_set − h_in), reach 1 and 0.3, each coil with its own cap and its own β. This is 0.43 · Q_r only for coils in series at β = 1; it differs when a coil is duty-limited or carries another stream.
- **Proposal A.** One firing per heater on the radiant coil: Q_r = min(ṁ (h_set − h_in), radiant duty), and Q_c = 0.43 · Q_r, zero without convection flow. Coils in two islands read each other through the W3 relay. The cap is the owner's choice: A1, the convection outlet below an 800 °C flue gas (recommended); A2, no cap.

## 2. Air cooler: overcooling at low flow

- **Expected.** Stopped fans still leave natural draught, a third of design (GPSA 25 to 40 %). Louvers or a hot bypass hold the outlet at low load. An air condenser's tubes are mostly vapour, and its condensate leaves in about one residence time.
- **Owner direction.** "AC should be able to adjust its duty to match target T as well as furnace."
- **Found, cause (i), natural draught.** The fans run from still to full; there is no actuator below natural draught. In the review cooler, stopped fans overcool 85 °C water below 11.5 kg/s (74 °C at 5 kg/s, 47 °C at 1 kg/s). The status then reads "LIMITED: the fans stand still".
- **Found, cause (ii), the outlet holdup (the owner's own case).**
  - The owner's feed, 125 °C at 120 kPa, is 0.128 kg/s of steam, condensed at 62 kPa above a 4 m condensate leg. The controller set the fans to 7.7 % for the target, with no limit.
  - The outlet still read 25 °C, rising 2 K per 100 s (the owner's save: 61 °C). The vacuum drew 25 °C void water up the leg into the outlet node. That node holds half the tubes' volume as liquid, about 260 kg; at 0.128 kg/s it flushes with τ ≈ 33 min.
- **Options.**
  - For (i): louvers (recommended), one effort from shut louvers through natural draught to full fans; or an internal hot bypass.
  - For (ii), shared with section 4: a heat side's outlet node holds only its header, about 10 % of the passage, while the passage stays quasi-steady (Q10). This settles in seconds; it risks a stiffer junction (holdup was added once for stability).

## 3. Fired heater at a 100 kPa generator

- **Expected.** The pump's +5 bar from a 1 bar feed cannot reach the 10 bar void. The pump stalls, the coils sit still and nothing is fired: no flow, no hold.
- **Reproduced.** In a fresh build, the feed generator set to 25 °C and 100 kPa holds the heater at its first slice: "Newton line search stalled at residual 1.7870486723459762", the same number as the owner's log. It retries every few seconds.
  - At 50 °C (only the pressure lowered) there is no hold: no flow, STEADY in 80 s.
  - Then the void at 100 kPa and the pump at 0.01 m³/s release it: 9.97 kg/s, 64 °C out, DUTY INSUFFICIENT, steady.
  - The owner's save ended in a different still state ("pump cannot drive forward flow", coils full of 200 °C steam at 1 bar), not reproduced by these steps.
- **Next.** Trace the stalled Newton on this two-step reproduction before proposing a fix.

## 4. Exchanger: slow to steady

- **Expected.** Fixed inputs give fixed outlets within about a residence time, and both islands then certify STEADY.
- **Found, cause (i), holdup.** The tube outlets creep with τ ≈ 50 s at 5.7 kg/s (327.0 → 329.6 K over 160 s). This is section 2's outlet node.
- **Found, cause (ii), a wake ping-pong.** After 250 s every outlet is constant to 0.01 K, yet coupling wakes run 23 → 224 in 160 s, one per side per commit. `IslandCoordinator.moved` tests the booked transit, which holds the span the side ahead booked past the other's commit. The owner's save holds −1.07 MJ, against a 4 kJ threshold. Only the settled transit (`settledTransit`) returns to 0 at steady state (W3).
- **Proposal (recommended, forced by the physics).** Wake on the settled transit: a clock offset between two islands is not a change. Recheck with the probe: the wakes stop once outlets are constant.

## 5. Water vapour pressure range

- **Found.** Water's saturation already spans 273.16 to 647.096 K: the IAPWS equation of Wagner and Pruss (`water-iapws-shomate-r2`), within 2e-4 of IF97. The notice is the ideal-gas steam's 2 % limit, `FluidThermodynamics.waterVaporPressureLimit` (125 kPa below 400 K, 250 kPa at 450 to 500 K). At 10 bar ideal steam is 7.6 % too light; at 100 bar, 33 %.
- **Options.** Both bump only `SEPARATE_FREE_WATER_FORMULATION` v2 → v3: no material revision moves, contents are restated at load, no facts change.
  - B (recommended): a second virial from IF97 Region 2, within 2 % to about 12 bar at saturation and 49 bar at 584 K.
  - A: the full Region 2 residual, a new reference model (the owner's choice per AGENTS.md).

## 6. Temperatures on the lines, and 7. GUI redesign (approved)

- No pipe screen shows a temperature (`FluidDeviceScreen.readings()`, since `f44930f5`). The furnace's nozzle-to-nozzle crossover made that visible. The fix: a pipe reads its stream from the pipe observation.
- The owner approved the mockup (`HEAT_GUI_MOCKUP.svg`): a Control tab with the flowsheet, temperatures on every line and explained modes, a stream switch on Overview, the per-port wire (fluid 15 → 16), and the duplicate `gui.icheme.fluid.ratio_input`. It is built in its own session (`HEAT_GUI_PLAN.md` there).
