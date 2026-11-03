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

## Taken under the standing instruction

- **A1 (Q10, reading of "as recommended").** All three proposals join the batch: a generator (shaft power to FE, the motor's mirror), cooling-tower fans on the RF port, KubeJS made optional with Create.
- **A2 (D6 scope).** Auto restart covers `NO POWER` only. An `OVERLOAD` trip is a protection trip and waits for the player's reset. A motor restarts once its buffer holds one slice of its draw, so it does not chatter on a weak supply.
- **A3 (D4 defaults).** An old pump's shape has only its discharge. Its missing design point takes today's datasheet values: rated flow at 3 m/s in the discharge, 100 m head, 2-pole speed. This is a field default, not a compatibility mode.
- **A4 (work split).** W6 splits into W6a (Create and KubeJS optional: packaging, Catnip outline, textures, the GameTest) and W6b (the Create coupling, after the shaft trains). The generator joins W3 (train) and W5 (FE out); cooling-tower fans join W5.
- **A5 (W1, efficiencies of a design).** The plan left open where a design's efficiencies come from; the constructor asks none. Pump: Corripio's correlation of the rated flow (a chemical-engineering design correlation; 0.70 at 100 m³/h). Motor: the IE3 table by pole count, checked whole against EU 2019/1781 annex I. Steam turbine: 0.45 single stage up to 300 kW, 0.76 multistage from 3 MW, log power between (the 300 kW and 3 MW ends are a reading of API 611's single stages to about 3 MW, not a published rule). Compressor: η_p 0.78. Plan section 3.5. Reversible: each is one method in `science/machine/**`.
- **A6 (D10, owner decision D58 kept).** The sealed-side closure (a mover whose suction nothing feeds or whose discharge nothing relieves starts closed) applies to every running mover, `MOVER_LAW` and the suction limit, as it applied to F1's target, head limit and suction limit. The F2b quick check ran with `MOVER_LAW` missing from that rule; the full campaign measures the code as kept.
- **A7 (D10, the status line).** F1's head-limit status said "requested flow unavailable". Under the law the same condition is a delivery short of the set flow (the relief lifting, or a velocity cap): a set-flow mover whose delivered share rounds below 100 % shows "ERROR: pump delivers N % of its set flow; requested flow unavailable" (a compressor appends "compressor delivers N % ..."). A machine has no set flow and shows "pump running".
- **A8 (D10, rate seeds).** F1 reused a repeated rate query's flashed output states as seeds when a mover carried its head or suction limit (`carriedMoverLimit`). Under F2b that gate never opened; the reuse goes as measured. `PumpRateWarmStartTest` (repeated rate calls on a flashed pump) decides whether a law mover needs it.
