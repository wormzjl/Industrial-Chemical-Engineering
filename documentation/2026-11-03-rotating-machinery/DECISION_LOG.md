# Rotating machinery: decision log

Batch `2026-11-03-rotating-machinery`. Owner decisions first, then decisions taken under the owner's standing instruction (reversible at the next gate). Plan: [ROTATING_MACHINERY_PLAN.md](ROTATING_MACHINERY_PLAN.md), questions in its section 13.

## Owner decisions

2026-11-03, the owner's answer to Q1 to Q10: "Q4 no backward compat, Q6 auto start, Others as recommended".

- **D1 (Q1).** Measure F1 and F2 on the 200-seed quick check; take F2 if it measures no worse than F1.
- **D2 (Q2).** Flow and pressure regulation acts between slices; the Newton sees a fixed speed.
- **D3 (Q3).** In-line movers stay utility movers: set flow up to the added pressure or ratio (the corner law under F2), an integral motor, no FE.
- **D4 (Q4).** No backward compatibility for constructed movers. Old placed pumps and compressors rebuild to the new casing layout without their drawn motor (and the centrifugal compressor without its gear); they stand closed with `NO DRIVER` until the player joins a driver. No `driver` shape field and no `INTEGRAL` mode. Their registrations, identity and settings stay.
- **D5 (Q5).** One steam turbine kind. Old condensing turbines rebuild to it and their drawn condenser goes; the owner approves the moved nozzle pipe blocks.
- **D6 (Q6).** After a power loss a motor restarts by itself when FE returns.
- **D7 (Q7), revised the same day.** First taken as recommended: 10 J per FE and 5.86 W per stress unit. Then the owner: "Create duty a bit overrated, research about actual water wheel duty". Create's water wheel is a 2 m stream wheel (paddles about 1 block from the axis, turned by water flowing past the rim), its large wheel 4 to 5 m. Real stream wheels of those sizes give 0.2 to 0.6 kW and 0.6 to 1.4 kW. Then the owner: "FE needs to match SU to prevent loopholes". Now: 2.5 J per FE (Mekanism's joule), and one FE per tick trades for 34.13 su, the rate of both Create electricity addons (16,384 su for 480 FE/t; config `create.stressPerFE`). A stress unit is `20 × 2.5 / 34.13` = 1.46 W: water wheel 0.38 kW, large wheel 0.75 kW, the middle of real stream wheels. No separate watt rate exists, so no FE and Create round trip gains energy, in either direction.
- **D8 (Q8).** Create drives industrial shafts only; industrial shafts driving Create is later work.
- **D9 (Q9).** Process simulator support after both batches merge.

2026-11-04, the owner's answer to the W2 forms review (`W2_FORMS_REVIEW.md` section 4): "Take F2b (A)".

- **D10 (D1 applied).** Form F2b is the only law of the set-flow movers: the corner as a finite rise, with the knee line past 0.999 of the displacement. `PUMP_TARGET` and `PUMP_HEAD_LIMIT` go with the switch's rules (plan 4.2): R2 to R4, the movers' `yieldPrescribed` and pocket relief, the `atShutoff` latch, the target and head-limit start flows, `carriedMoverLimit`, the start-mode cap test `suctionCapped` and the measurement switch `icheme.fluid.cornerLaw`. Movers keep `CLOSED`, `INLET_WRONG_PHASE`, `PUMP_SUCTION_LIMIT` and the reverse closure. Checkpoint format 32 (state: the mode ordinals move).

2026-11-04, the owner’s answer to the W2 corner review (`W2_CORNER_REVIEW.md` section 4): "A: F2e with A1, A2".

- **D11 (D10 restated).** Form F2e is the law of the set-flow movers in place of F2b: a slip line and a relief line joined by a rounded corner, stated as a flow row (slip 0.4 % of the displacement per relief rise, relief line 10, rounding 0.02, the lift that puts no flow on the rise limit). D10’s deletions stand. With it, two start-stage rules for D10’s gaps:
  - **A1.** A flow valve starts a solve open when its target exceeds what its line passes fully open on the movers’ laws, in place of F1’s yield rule.
  - **A2.** A closed set-flow mover reopens on the density of its supply, the stream its refusal reads: it primes itself.

2026-11-04, the owner's answer to the W2 campaign review (`W2_CAMPAIGN_REVIEW.md` section 4): "for E8 this is the typical case to apply the shutoff pressure model, E5a ok". On how to build E8: "The shutoff model should be a part of the pumping curve"; asked how a dead-headed pump reaches exactly zero flow: "Pumping curve should include zero flow".

- **D12 (D11's two new hold classes).**
  - **E8.** A dead-headed set-flow mover is the typical case of the shutoff pressure model, and the model is the pump curve itself: the rise climbs as the flow falls until the flow reaches zero at the shutoff pressure. One curve, its end point included; no shutoff mode and no closing rule.
  - **E5 (a).** A set-flow mover whose displacement exceeds a suction link's cap starts at that cap on its law: a start seed, not a mode.

2026-11-04, the owner's answer to the D12 test review (`D12_TESTS_REVIEW.md` section 3): "A: curve + check valve".

- **D13 (D12 restated).** The curve is F2e unchanged: it reaches zero flow at the shutoff rise (the lift δ) and goes on past it. The reverse closure stays as the mover's check valve. The clip of reading A11 goes: its no-flow half-line left a dead-headed pocket's pressure undetermined (singular Jacobian) and stalled Newton iterates that crossed the shutoff. E8 is the solver's: a taut vessel's rows are weighted by the pressure they imply.

2026-11-04, the owner's answer to the D13 campaign review (`W2_D13_CAMPAIGN_REVIEW.md` section 4): "Suction floor (b)".

- **D14 (E5).** A vapour-pressure suction floor for every liquid (D59 (4) and K6 widened): a pump's suction may not fall below the bubble pressure of the hydrocarbon liquid it draws, whether or not its supplier holds vapour. A starved pump goes on its suction limit and delivers what its line gives at that pressure. E5 (a), the suction-cap start seed, goes: it measured no effect.

2026-11-04, the owner’s answer to the D14 holds review (`D14_HOLDS_REVIEW.md` section 3): "A: close it".

- **D15 (D14’s two new holds).** A pump whose suction line delivers nothing at its floor (no NPSH available: its supplier stands at or below the bubble pressure the floor reads) is vapour-locked and passes nothing: it stands closed, not on its suction limit with no flow. It reopens only when its supplier stands above the floor again, as well as its rise beating the demand.

2026-11-04, the owner's answer to the D15 holds review (`D15_HOLDS_REVIEW.md` section 3): "Yes, A + C".

- **D16 (D15 restated).**
  - **A.** Whether a pump is vapour-locked is one fact per solve, read on the step's start states like its floor: a suction head at no flow at or below the band stands it closed for the whole solve, reopening included; above it, the pump is never closed for vapour lock in that solve, and a starved pump goes only to its suction limit. No reading on a point the pump's own law drained.
  - **C.** A hydrocarbon liquid's floor is its bubble pressure at its own enthalpy (the stream arriving through a throttle keeps its enthalpy), as `waterFloor` states it for water.

2026-11-04, the owner's answer to the D16 campaign review (`W2_D16_CAMPAIGN_REVIEW.md` section 2): "(a) Floor ≤ supplier".

- **D17 (bundled 896).** A pump's suction limit never stands above its supplier: the anchored suction is the lesser of the floor and the supplier's end pressure less the static lift, on the trial point. A supplier that falls to its bubble point within the step takes the pump's delivery to zero continuously, in the row, with no mode change.

2026-11-04, the owner's answer to the D17 campaign review (`W2_D17_CAMPAIGN_REVIEW.md` section 2): "Accept, close W2".

- **D18 (W2's solver work closed).** Bundled 896 stays as a known hold: three held intervals at the step where its supplier tee first reaches its bubble point, then STEADY at 52.76 kg/s. The campaign stands at 992, 994 and 1000 clean against the baseline's 991, 993 and 1000. W2's solver work ends at `d444cb424`.

2026-11-04, the owner on plan revision 3's Q13: "Q13 in-line equipment is treated the same as normal equipment".

- **D19 (Q13).** In-line pumps and compressors take request 11 whole, as constructed machines: the gas and liquid laws, the NPSH3 curve, health, wear, trips and repair. Their design point for NPSH3 is their set flow at a 2-pole speed (2950 rpm); the default 0.01 m³/s pump needs 1.7 m. Q11, Q12 and Q14 to Q17 stay open.

2026-11-04, the owner on the rest of plan revision 3's questions: "All as recommended".

- **D20 (Q11, Q12, Q14 to Q17).**
  - **Q11 A.** A pump's suction floor is `p_v + 0.85 ρ_l g NPSH3(Q, s)` on the trial point, inside D17's lesser-of; the bubble floor is its `NPSH3 = 0` end.
  - **Q12.** The gas onset reads suction pressure only, `α_T(p_s)`; no speed or impeller-type factor.
  - **Q14 (a).** A spare part crafted per machine (impeller, valve set, seal kit), used on the casing, restores full health.
  - **Q15.** Wet compressors as researched: centrifugal on the homogeneous mixture with `η_wet = η_p (1 − 0.5 w_l)` and droplet erosion; reciprocating liquid knock costs 0.5 health and trips.
  - **Q16.** The physical wear intensity (peak near 2 × NPSH3), no size factor, the lives of plan 3.7 in the server config.
  - **Q17.** W10a now; W10b to W10d after W3, W4 and W7.

2026-11-04, W10a's affected tests: at D19's 2-pole speed NPSH3 grows with the set flow, `(2950 √Q/200)^(4/3)`: 7.8 m at 0.1 m³/s, 47 m at 1.5 m³/s, so every in-line pump above about 0.17 m³/s cavitated from a full atmospheric tank (`PumpRateWarmStartTest`). Options: (A) the speed that keeps NPSH3 at 3.3 m, at most 2950 rpm; (B) keep 2-pole. The owner: "A: speed for 3.3 m".

- **D21.** An in-line pump is chosen for its duty: its NPSH3 design is its set flow at `n = min(2950, 200 · 3.3^0.75 / √Q)` rpm (`Npsh.forDuty`), so its rated NPSH3 is at most 3.3 m (plan 1.1's pump). Nothing changes up to 0.028 m³/s; a 1.5 m³/s pump turns at about 400 rpm. The speed feeds only NPSH3. Constructed pumps keep the speed of their design.

2026-11-04, the turbine connection (plan 3.4), whose governor target was to come from the shaft's demand (W3): the owner, "Decouple them for now, add control systems as individual component", then "the turbine is decouple from the pump/compressor head".

- **D22.** The steam turbine is a driver of its own, separate from the pump or compressor head it drives; a shaft train joins them (W3). Control systems (the governor, speed control) are separate components too. So the turbine connection has no built-in governor and no demanded flow: it passes steam on its own flow resistance between its inlet and exhaust pressures and takes `W = ṁ η_is Δh_is` out of the stream as shaft work (exhaust at `h_in − W/ṁ`). Its flow is set by a separate control component in line, for now the existing mass-target control valve upstream.

2026-11-04, W3's turbine control under D22 (A: a speed sensor on the shaft plus the control valve with a shaft-speed source; B: the valve set by hand only): the owner, "A: speed sensor + valve".

- **D27.** A turbine's governor is two components the player places: a speed sensor on its shaft train and the existing control valve upstream of the turbine, with a new valve source, shaft speed (saved by name). Once per slice the engine sets that valve's mass target from the train's power demand, `ṁ = P_demand/(η_is Δh_is)` (plan 5.2's feed-forward), and holds the set speed while steam suffices; short of steam the speed falls by the damped affinity step (plan 4.1). The overspeed trip acts at 110 %.

2026-11-04, W3's build plan (`W3_BUILD_PLAN.md` section 7, readings A15 to A40): the owner, "All as recommended".

- **D28 (A15 to A40).** W3 builds as its plan recommends: shaft parts saved as facts (A15); a coupling through at least one shaft block (A16); one driver per train, else `TWO DRIVERS` (A17); the defaults of A19; an automatic gearbox ratio (A20); `OVERLOAD` above exactly 1.0 × rating (A21); a latched overspeed trip at 1.10 × set (A22); an ungoverned turbine without a sensor (A23); the speed sensor on the turbine's coupling guard, bound to the nearest unbranched upstream control valve (A24, A25); the fed-forward governor target (A26); machine work as `SHAFT` boundaries (A27); trains stepped at their members' commits (A28); old casings converted when restored (A29); the readings A30 to A40 as the plan gives them.

2026-11-04, W3's review (`W3_REVIEW.md` section 4, reading A42 against A27; A: read at commit as built, B: a `SHAFT` boundary per mover): the owner, "A: keep as built".

- **D29 (A42).** A pump's or compressor's shaft power is read at commit from its end flows, heads and suction state (`law.shaftPower`, `Compressor.power`) and loads its train; it books no `SHAFT` boundary, and a closed pump island keeps no boundary transfer (`BackwardEulerStepTest`). Only the turbine's work leaves as a `SHAFT` boundary (D22). This replaces A27 for pumps and compressors.

2026-11-04, W5's build plan (`W5_BUILD_PLAN.md` section 8, readings A53 to A73). Before it the owner: "Add forced draft cooler as well", then "I mean add power usage to it" (the forced-draft cooler is today's air cooler). On the plan: "All as recommended".

- **D30 (A53 to A73).** W5 builds as its plan recommends: one electric store per powered unit in fluid facts schema 11, saved in joules (A53 to A55); ports on every face of a terminal cell, receive-only for consumers and extract-only for generators (A56, A57); terminal cells on an outside face at grade in existing cells (A58); booking at commits and on the presentation cadence (A59, A60); unpowered as a dispatch input (A61); restart at one slice of rated draw (A62); a generator loaded by its buffer's room, within ±5 % of rated speed, pushing to its neighbours (A63 to A65); the two `energy` config keys (A66); and A67 to A72 as given. A73 option A: air coolers and induced towers from older saves stay unmetered, with the notice, until their port first receives FE.

2026-11-04, W7's Q1 on the option sheets `machine-a` (motor page, pump page with a Drive group), `machine-b` (a Train tab on the pump page) and `machine-c` (a train screen); the question said readings A85 to A100 go with the pick as recommended: the owner, "A: own pages".

- **D31 (Q1, A85 to A100).** Each machine has its own page: motor, steam turbine (a Drive tab on its device page), generator, gearbox and creative motor. Using the machine opens it. A casing's page gains a Drive group (the driver in two lines and an Open driver push button). Readings A85 to A100 as `W7_BUILD_PLAN.md` gives them.

2026-11-04, W7's review (`W7_REVIEW.md` section 4: the turbine opens its own machine page from a Drive group instead of a Drive tab on its fluid page): the owner, "Keep as built".

- **D32.** A steam turbine's controls are on its own machine page, like every driver's; its fluid page carries the Drive group with Open driver, as a casing's does. This replaces D31's Drive tab.

2026-11-04, W10b's build plan (`W10B_BUILD_PLAN.md` section 7, readings A109 to A128): the owner, "All as recommended".

- **D33 (A109 to A128).** W10b builds as its plan recommends: health per pump and compressor device in fluid facts schema 12 (A109); plan 3.7's rates at commit and on the cadence (A111, A112); the health factor as a dispatch input (A113) with a 1e-3 wake threshold (A114); the knock trip (A115); two parts, impeller and valve set, without a seal kit (A116, A117, replacing Q14's list); vanilla recipes and script-drawn textures (A118, A119); and A120 to A128 as given.

2026-11-04, before W10b's build started: the owner, "Holdon, keep this simple", then on the scope question (A: cavitation and dry running only, full performance until failure, one repair kit; B: all faults, one part; C: the plan as written): "Cavitation + dry only".

- **D34 (replaces D33).** W10b in its simple form (`W10B_BUILD_PLAN.md` revision 2): pumps only carry health (compressors have no wear source left); wear from cavitation (Gülich's curve, no gas cushion) and running dry; no effect until health 0, then `FAILED`, closed; one `repair_kit`; no knock trip, no gradual head loss, no wake threshold. Readings A109 to A128 are withdrawn.

2026-11-04, W10c's scope (`W10C_BUILD_PLAN.md`; A: the crackle only, B: the crackle and the gas churn, C: plan 3.8 in full): the owner, "Crackle + gas churn".

- **D35.** W10c builds two pump loops: the cavitation crackle (volume `I_c(σ)`, W10b's curve) and the gas churn (volume `clamp(r − 0.5, 0, 1) M(r)/0.568`, `r = α/α_T`). Both are scaled by D26's `soundGain` and carried as two `MoverState` bytes. No compressor sounds.

2026-11-04, after W10c's review: the owner, "Yes, add them" (in-line pumps' sounds), then "Rotation machine sound should also scale with their rpm".

- **D36.** In-line pumps play the crackle and churn layers, with no running loop, at the smallest casing's gain; in-line compressors stay silent. Every machine loop's pitch is `clamp(n/n_loop, 0.5, 2)`, with n the machine's rpm and n_loop the rpm its loop was authored at (pump 2950, motor 1500; the machine's rated rpm where the loop states none). This replaces D26's pitch = speed share capped at 1. Volume keeps D26's law.

2026-11-04, W10d's scope (A: readings only; B: readings and the pump curve; C: plan 9 in full): the owner, "Readings + pump curve".

- **D37.** W10d builds `W10D_BUILD_PLAN.md`: on the pump page a Suction group (NPSH available, NPSH required, gas %), a Health meter, `CAVITATION`/`GAS`/`FAILED` on the display, and for a constructed centrifugal pump the head–flow and NPSH3 curves on the glass with the operating point and NPSH available. No compressor readings, head factor, rate of loss or Repair key.

2026-11-04, on W10d's prototype sheets (pump-readings-casing, pump-readings-inline): the curve read as safe while the pump cavitated (NPSH available 6.8 m above NPSH3 3.3 m, inception near 4 x NPSH3 = 13.2 m); the owner: "Add 4xNPSH3 curve" and, on the in-line page now taller than its view, "Accept the bar".

- **D38.** The pump curve also draws the inception curve, 4 x NPSH3, dashed, with the NPSH axis grown to fit it: NPSH available between NPSH3 and 4 x NPSH3 reads as cavitating, above both as quiet. The in-line pump page scrolls at 1920 x 1080 like the casing page.

2026-11-04, on W10d's reading A137 (an in-line pump's gas reading costs one suction walk per running in-line pump per solve; the alternative was a dash): the owner: "Keep the gas read".

- **D39.** In-line pumps keep their gas reading as built (`4eec41cad`): one `InletPhase.supply` walk per running set-flow pump per solve, for the page only. It feeds no churn, no `GAS` word and no dry wear.

2026-11-04, on W6b (`W6B_BUILD_PLAN.md`): asked how W6b gets W6a, the owner: "Merge W6a here" (merged `d16ecab51`); asked how the coupling takes power, the owner: "A: follow demand".

- **D40.** W6a joins this branch (one batch, one merge to main). The Create coupling is form A of `W6B_BUILD_PLAN.md` (plan section 8): a `Drive.CREATE` driver at Create's |rpm| with the creative motor's power-limited step, its power limit the network's spare stress plus its own draw at 1.465 W per su, its stress impact following the train's demand on the engine's schedule; short of power the train slows (`POWER LIMITED`) and the Create network never overstresses. Form B (a rated impact) is not built.

2026-11-04, on the coupling page prototype (create-coupling-running, -limited, -no-rotation): the owner: "Build as shown".

- **D41.** The Create coupling's machine page is the prototype of `23c634d1f`: Shaft group (Create speed, train speed, power), Supply group (stress drawn, network spare, su), a nameplate on the glass (stress rate, rated speed), no switches, Close only.

2026-11-04, on `W8_BUILD_PLAN.md`: the owner: "A: simple" and "(a) Duty readings".

- **D42.** W8 is form A: pump and compressor tiles take their design efficiency (shaft power = ideal / η, all of it heating the stream) and propose a motor with 20 % spare (electric power, FE/t); the steam turbine tile is modelled (steam in, exhaust at a set pressure, shaft power out); no shaft wires, no driver tiles. The column door's Standard / In-line keys give way to each pump's duty readings (flow, head, shaft power, NPSH required, proposed motor); the design names no pump kind and the assembly takes either, the standard one first. The door is prototyped first.

2026-11-05, on W8's build: `SizingCoverageTest` asks a size class of every kind the simulator runs (the turbine tile now runs, and the motor, generator and gearbox stand on it); the owner: "Turbine sizing". On the column door's pump duty sheets (A: meters in each pump group, 785 px; B: one table on the glass, 351 px): "B: table on glass".

- **D43.** A `TurbineSizing` class lets Auto propose the steam turbine from its rated power with the 20 % spare; the motor, generator and gearbox entries on the turbine's tile are drawing-only, so `SizingCoverageTest` checks only what runs. The column door's pump duties are one banded table on the glass (Pump, Flow m³/h, Head m, Shaft kW, NPSH3 m, Motor kW; a row per pump); the Standard / In-line keys and the Reflux pump group leave the door.

2026-11-05, on W8's shipped door (pump-duties-464, -790; reading A169): at 464 and 380 wide the Equipment glass shows about 3 of 17 connection points under the pump table and cuts the list's Heat column; the owner: "Scroll the glass".

- **D44.** The column door's Equipment glass scrolls when its tables are taller than it (its own bar, as the door column has), the pump duty table first; where the glass is too narrow, the connection list stacks its Heat value under its row. Nothing on the glass is hidden or cut.

W4's decisions, taken on its branch `claude/sad-elgamal-92e0e4` as D10 to D13 and renumbered at its merge into this branch (owner "Yes, merge W4", 2026-11-04):

- **D23 (W4's D10, 2026-11-04).** Constructor duty rows: option A (choice keys for poles, drive and steam class; steppers for flow, head, power and exhaust).
- **D24 (W4's D11, 2026-11-04).** Owner: "review the pump types used in refinery, current large pump seems to be wrong type", then "Build A" (`PUMP_TYPES_REVIEW.md`). Above 0.4 m discharge the pump is an API 610 BB2 (radially split, between bearings, top suction and top discharge) instead of the BB1. The owner approves the moved nozzle pipe blocks of the four large sizes (sides to top).
- **D25 (W4's D12, 2026-11-04).** The OH2 suction spools at 0.10 and 0.15 m stay as they are (1.2 m): shortening them would move their pipe blocks one block in and disconnect placed pumps. Owner: "Keep them".
- **D26 (W4's D13, 2026-11-04).** Owner: "make individual motor/turbine sound from pump/compressor head sound, and scale their volume based on operating speed and equipment size". A driver (motor, generator, steam turbine) has its own loop at the centre of its own cells, apart from its machine's casing loop. One law for both (`UnitDesign.soundGain(shape, speed)`): gain = (0.10 + 0.50 x size share) x speed share^2.5, the speed share clamped to 0..1 so the gain never passes 1 (above 1 Minecraft widens the range, not the loudness). A casing's size share is its size range as before; a driver's is ln(P/Pmin)/ln(Pmax/Pmin) of its rated power over its kind's powers (motor and generator 0.75 kW to 20 MW, turbine 55 kW to 45 MW): a 7.5 kW motor 0.21, a 250 kW motor 0.39, a 1 MW turbine 0.32, 20 MW 0.60. The exponent is the fan law (sound power as speed^5, the loop scales its root): half speed gives 0.18 of the full-speed gain. Form B, chosen by the batch session: the driver loop waits for the shaft trains (W3), which send the running state and speed share through `DriverSounds.accept(BlockPos origin, boolean running, double speed)` and the watch request in `DriverSounds.request`; a client walk along the shaft blocks would copy W3's topology through gearboxes and couplings and could disagree with the server's train. Until W3, casings sound at speed share 1 while running and drivers are silent. New original loops `motor_running` (the generator's `generator_running` plays it) and `turbine_running` (`tools/machine-sounds/`). Then the owner answered "yes" to the three follow-ups: (1) the pitch follows the speed share, `UnitDesign.soundPitch` = clamp(speed, 0.5, 1) on both loops (a machine's tones are its shaft speed times a fixed count; Minecraft stops at 0.5); (2) the pump and compressor loops are re-authored without the motor's hum (vane pass and wash; gas rush and pulse), at their old levels; (3) one in-game listening check, after W3 lands (before it, drivers are silent).

## Taken under the standing instruction

- **A1 (Q10, reading of "as recommended").** All three proposals join the batch: a generator (shaft power to FE, the motor's mirror), cooling-tower fans on the RF port, KubeJS made optional with Create.
- **A2 (D6 scope).** Auto restart covers `NO POWER` only. An `OVERLOAD` trip is a protection trip and waits for the player's reset. A motor restarts once its buffer holds one slice of its draw, so it does not chatter on a weak supply.
- **A3 (D4 defaults).** An old pump's shape has only its discharge. Its missing design point takes today's datasheet values: rated flow at 3 m/s in the discharge, 100 m head, 2-pole speed. This is a field default, not a compatibility mode.
- **A4 (work split).** W6 splits into W6a (Create and KubeJS optional: packaging, Catnip outline, textures, the GameTest) and W6b (the Create coupling, after the shaft trains). The generator joins W3 (train) and W5 (FE out); cooling-tower fans join W5.
- **A5 (W1, efficiencies of a design).** The plan left open where a design's efficiencies come from; the constructor asks none. Pump: Corripio's correlation of the rated flow (a chemical-engineering design correlation; 0.70 at 100 m³/h). Motor: the IE3 table by pole count, checked whole against EU 2019/1781 annex I. Steam turbine: 0.45 single stage up to 300 kW, 0.76 multistage from 3 MW, log power between (the 300 kW and 3 MW ends are a reading of API 611's single stages to about 3 MW, not a published rule). Compressor: η_p 0.78. Plan section 3.5. Reversible: each is one method in `science/machine/**`.
- **A6 (D10, owner decision D58 kept).** The sealed-side closure (a mover whose suction nothing feeds or whose discharge nothing relieves starts closed) applies to every running mover, `MOVER_LAW` and the suction limit, as it applied to F1's target, head limit and suction limit. The F2b quick check ran with `MOVER_LAW` missing from that rule; the full campaign measures the code as kept.
- **A7 (D10, the status line).** F1's head-limit status said "requested flow unavailable". Under the law the same condition is a delivery short of the set flow (the relief lifting, or a velocity cap): a set-flow mover whose delivered share rounds below 100 % shows "ERROR: pump delivers N % of its set flow; requested flow unavailable" (a compressor appends "compressor delivers N % ..."). A machine has no set flow and shows "pump running".
- **A8 (D10, rate seeds).** F1 reused a repeated rate query's flashed output states as seeds when a mover carried its head or suction limit (`carriedMoverLimit`). Under F2b that gate never opened; the reuse goes as measured. `PumpRateWarmStartTest` (repeated rate calls on a flashed pump) decides whether a law mover needs it.
- **A9 (D11, A1).** "What its line passes fully open on the movers’ laws" is the lowest set flow, at its suction stream’s density, of a set-flow pump or compressor that junction balances make one flow with the valve (`ControlConstraints.moverBounds`, the lines of "Lower target wins"). It acts where a regulating flow valve is carried into a solve; a valve that starts without a carried mode starts open anyway. Starting open is not standing open: the pass loop regulates again where the open line passes more than the target (a line pushed through its mover). A machine on its curve bounds nothing.
- **A10 (D11, A2).** Reopening alone on the supply’s density cycles, because the reopening and the law must read one rise limit: a dead-headed pump behind a junction 1e-5 lighter than its supply reopened 5 Pa early at 5 bar and cycled (`ElevatedBlockLineIslandTest`), and the cooling-tower pump, reopened with gas at its suction, reversed on its law and held. So a set-flow pump’s relief rise reads its supply’s density (`InletPhase.density`, the stream its refusal reads) in its law row, every mode test and the device status, one value per solve (`PassiveStepSolver.riseDensity`); its volume flow stays on the suction stream. The start balance keeps every mover on its suction stream: balanced on the supply’s relief, an LNG pump fed through a 1 cm line started liquid at its bubble point and every step size stalled (`HoldClassWorldsTest`); on the suction stream its start lands where it cavitates. A compressor’s ratio limit reads no density; a machine reads its suction stream.
- **A11 (D12, E8; its first clause withdrawn by D13).** First read as: the curve (`PositiveDisplacement`) ends at zero flow at the shutoff (relief) rise and gives no reverse flow above it, so a set-flow mover's reverse closure goes. The hold E8 was not in the curve: near its shutoff a dead-headed pump carries a smaller trickle each step, and a Newton that takes no iteration keeps the last one. Seed 896's pump carried 3.1e-9 kg/s into a tank full of water (6.4e-12 of its mass, inside the Newton's tolerance); booking it moved the tank's pressure 0.023 Pa where the equation gate allows 0.0057 Pa (`tmp/rotating-machinery/quick/F2e-gate-seed896`). So the fix is the solver's, for every vessel full of liquid: the Newton resolves such a vessel's pressure to the precision the gate checks.
- **A12 (D12, E5 a; deleted by D14).** "That cap" is the smallest velocity cap of the links on the mover's suction walk (`InletPhase.walk`, each link's cap on the stream its donor end draws, as F1's `suctionCapped` read it). A set-flow mover on its law starts each solve at no more than that cap, whichever start the solve takes: the carried point, the balance or the estimate.
- **A13 (D14).** "Every liquid" means every hydrocarbon liquid a liquid pump draws through a suction walk that leaves its first node: its bubble pressure at the supplier's temperature and composition, from the step's start states, once per solve (cached on the supplier's state). A two-phase supplier's bubble pressure is its own pressure, so K6's vapour test becomes one case of the evaluation, not a separate rule. A supplier holding free water keeps D59 (4)'s water curve. A pump drawing straight from its first node keeps none, because a tank's own closure holds its bubble pressure. A bubble evaluation the thermodynamics refuses gives no floor, never a hold. The cap-limited start (A12) is deleted with E5 (a).
- **A14 (W3, before W5).** Until W5 builds the FE port and buffer, a constructed motor runs as supplied: its electric draw (shaft power over its efficiency) is computed and shown, and `NO POWER` and the auto restart (D6, A2) arrive with W5. Nothing merges between W3 and W5.
