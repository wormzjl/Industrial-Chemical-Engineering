# Owner test of the review world (2026-10-28)

The owner's findings from `REVIEW_WORLD.md`: each gives the expected behaviour, what the code did, and the fix or proposal. Probe: `-PreviewProbe=true` rebuilds the plant and applies the owner's changes; `owner` watches a copy of the owner's save (scratch until the cleanup, `run/review-probe`). The owner answered on 2026-10-28: "I don't think furnace convection section can heat to 800C, try to find a better model. Others as recommended". So sections 2 to 4 are fixed here, 5 to 7 on their own branches, and section 1 is a new proposal.

## 1. Fired heater: the convection duty (F1 built, replacing A)

- **Expected.** The burners' flue gas gives the radiant coil Q_r while it cools from the flame (about 1800 °C) to the bridgewall (about 800 °C), so it carries C_g = Q_r / 1000 K, about 1 kW/K per MW of radiant duty. It then crosses the convection coil, a counterflow exchanger: Q_c = ε C_min (T_bw − T_in). The 800 °C is the gas entering the convection section; the process leaving it is far colder, since the gas holds at most C_g (T_bw − T_in).
- **The model now (W6).** Each coil heats toward the set point (reach 1 and 0.3), each to its own cap; the convection duty does not depend on the radiant firing.
- **F1 (recommended).** The radiant coil as now. The convection coil a counterflow exchanger against the flue gas: T_bw 800 °C; C_g from the radiant coil's heat, planned per step (across islands, its last commit); U_c 55 W/m²K on its bare tube, which gives the 70/30 design split at a 250 °C crude inlet. Zero with the burners out or no radiant flow.
  - Design 10 MW: crude 250 → 283 °C in the convection coil, stack 371 °C, fired efficiency 80.5 %.
  - The review heater at 9.97 kg/s of 25 °C water: Q_c 701 kW (0.61 Q_r; W6 gives 492), water out at 41.8 °C, stack 190 °C. In series at 0.1 kg/s to 200 °C steam: wet steam at 180 °C leaves the convection coil.
  - Only a separately fed, starved coil nears the bridgewall: 0.1 kg/s of water beside 1148 kW of radiant duty leaves at 767 °C. Real heaters trip on low pass flow.
  - Deletes the convection reach 0.3, its duty cap, its firing plan and its DUTY INSUFFICIENT. Risks: one slice of lag across islands; a closed form with the mean heat capacity misses a pinch inside a boiling plateau by a few K (the exchanger's 8 counterflow cells against a flue-gas stream avoid it, at more work).
- **F2.** F1 with the bridgewall falling at turndown (a radiant balance in T⁴): more faithful at low firing; one more solve. Not recommended now.
- **Built: F1** (the owner: "Build F1"; plan section 10, F1 as built). Probe: the owner's case gives 701.5 kW and 41.8 °C out, as proposed; the review plant raises wet steam at 180.6 °C in the convection coil.

## 2. Air cooler: overcooling at low flow (fixed)

- **Expected.** Stopped fans still leave natural draught, a quarter of full air; louvers hold the outlet below that.
- **Fixed: louvers.** `AirCooled` has one effort, the air's share of full fans: 0 with the louvers shut (no air, no heat), 0.25 with the louvers open and the fans still, 1 at full fans. The controller holds a set point at any flow. It is LIMITED only at full fans or with the louvers shut (a stream that arrives below the set point). Manual and the power switch keep their meaning: the fans, with the louvers open.
- Gate (`HeatSideTopologyTest`): 85 °C water held at 80 °C with the louvers 0.626 open and the fans still; above the inlet the louvers shut and the outlet stays at 85 °C.
- The outlet holdup: section 4.

## 3. Fired heater at a 100 kPa generator (fixed by section 4's sizing)

- **Cause, by A/B on the probe.** The coils' nodes were sized on the coils' passes. With only that rule reverted, the hold returns at the same residual (1.7870486723459762).
- **With the fix.** Stage 1 is STEADY at no flow in 81 s with "pump cannot drive forward flow", the owner's end state. Stage 2 is steady at 9.97 kg/s. No hold anywhere in the probe.

## 4. Exchanger: slow to steady (fixed)

- **(i) Holdup.** A junction holds 0.05 s of its widest connection's flow at 100 m/s. A heat side's passage counted every tube at once, so the outlet held half a bundle. `PassiveNetwork.sizeJunctionHoldups` now skips a heat side's passage, which holds no fluid (Q10), so its ends are sized on their lines, as headers. The tube outlets stand at their final values by 70 s; before, they settled with τ ≈ 50 s.
- **(ii) Wake ping-pong.** `IslandCoordinator.moved` reads the settled transit. Probe: no wakes in the first 170 s (before: 1.25 per s with the outlets constant). After that, about one per 8 s, all at the kettle, whose gravity-fed pool really fills (258 → 2035 kg in 400 s, as before the fix) toward the feed's 1 m head.
- `ExchangerCouplingTest.certifiedSidesWakeWhenTheTransitMoves` moved the transit by −500 kJ from the settled value but wrote it as the booked one. The two differ by one slice of one side's heat (11.7 MJ), and the ledger was off by exactly that. The test now moves the booked transit.

## 5. Water vapour pressure range (built, branch `claude/water-steam-virial`)

- Option B: a second virial from IF97 Region 2, `separate-free-water-v3` (commits `3a527f1e`, `4521b65c`, `c6e7d480`; review `WATER_STEAM_VIRIAL_REVIEW.md` on that branch). The 2 % limit is 1.8 MPa below 500 K, rising to 65 MPa from 875 K, so water at 10 bar raises no notice. Gates: `test` 1709, slowGates 188, fluid GameTests 35/35, chain-100 exact.
- It reports three items for this batch: the junction-boiling test re-recorded (start a seeded junction's flows from its seeds, the owner's call); chain-100 re-captured; restatement on load is a flash at fixed pressure (0.89 % off the wall for one solve).

## 6 and 7. Line temperatures and the GUI (built, branch `claude/heat-gui-redesign-abb1db`, `e8465a72`)

- Built: the Control tab with the flowsheet, the stream switch, a pipe's temperature and pressure, and fluid-16. Owner options Q-G1 to Q-G4 are built as (a). At the merge the air cooler's actuator becomes "louvers" (section 2).
- The old pump and compressor removal landed on main as 0.16.2 and is merged here (`342c6d22`): the review world's heater pump is an In-line Pump. A save made before it opens without the one-block pump; rebuild the review world.
