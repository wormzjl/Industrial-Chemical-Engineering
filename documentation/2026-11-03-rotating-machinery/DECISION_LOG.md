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
- **D7 (Q7), revised the same day.** First taken as recommended: 10 J per FE and 5.86 W per stress unit. Then the owner: "Create duty a bit overrated, research about actual water wheel duty". Create's water wheel is a 2 m stream wheel (paddles about 1 block from the axis, turned by water flowing past the rim), its large wheel 4 to 5 m. Real stream wheels of those sizes give 0.2 to 0.6 kW and 0.6 to 1.4 kW. Now: 1.5 W per stress unit (water wheel 0.38 kW, large wheel 0.77 kW); FE stays 10 J per FE. Below parity (5.86) there is no energy loop while Create only drives industrial shafts (D8); a later industrial-to-Create direction takes the parity rate.
- **D8 (Q8).** Create drives industrial shafts only; industrial shafts driving Create is later work.
- **D9 (Q9).** Process simulator support after both batches merge.

## Taken under the standing instruction

- **A1 (Q10, reading of "as recommended").** All three proposals join the batch: a generator (shaft power to FE, the motor's mirror), cooling-tower fans on the RF port, KubeJS made optional with Create.
- **A2 (D6 scope).** Auto restart covers `NO POWER` only. An `OVERLOAD` trip is a protection trip and waits for the player's reset. A motor restarts once its buffer holds one slice of its draw, so it does not chatter on a weak supply.
- **A3 (D4 defaults).** An old pump's shape has only its discharge. Its missing design point takes today's datasheet values: rated flow at 3 m/s in the discharge, 100 m head, 2-pole speed. This is a field default, not a compatibility mode.
- **A4 (work split).** W6 splits into W6a (Create and KubeJS optional: packaging, Catnip outline, textures, the GameTest) and W6b (the Create coupling, after the shaft trains). The generator joins W3 (train) and W5 (FE out); cooling-tower fans join W5.
