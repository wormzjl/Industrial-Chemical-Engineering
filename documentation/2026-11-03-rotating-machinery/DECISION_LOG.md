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
