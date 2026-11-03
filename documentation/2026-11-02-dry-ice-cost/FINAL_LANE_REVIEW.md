# Final lane review

Lane at 803970ac1: the branch with local main (0.19.1) merged in, clean. `tools/merge-lane/lane.sh head`, alone on the machine. Main's own 0.19.1 lane is the reference. The owner asked for the lane and a stop before the merge. Code guide, CHANGELOG, version and merge wait for the owner's word.
## Results
- `test slowGates`: 2108 tests, 1 failure. Column and equipment gates came from the cache: the batch changes no code they load.
- Printed lines: 8555 (main 8556). Junction lines: 33, identical to main's except for timings and allocations. `MIXED_GAS_COST` is unchanged at 299 Newton solves.
- chain-100 exact: 0 deviation. Fluid GameTests: 39 of 39 pass.
- Campaign, 1000 seeds, 8 workers, `refused=0` everywhere:
  - Cold random: 991 clean, as the arrival recheck predicted (base lane 975, first campaign 989). Held: 85, 988 (E1); 431, 752, 803 (E5); 101, 206, 446 (E6); 118 (F, equation gate). Seed time 1378 s (base 2592).
  - Bundled random: 993 clean (base lanes 992 and 993, first campaign 994). 6 and 241 are budget holds, as in the first campaign. 301 (class B) is new.
  - Bundled defaults: 1000.
## The failing test
`SolidTransportAcceptanceTest.drainingSlurryClosesAtAReproducibleInteriorEvent` solves one slurry drain on the default steps and on steps of at most 0.5 ms. It requires the solids that reach the second tank to agree within 2e-6 kg. They differ by 8.6e-6 kg (2.033e-4 against 1.947e-4 kg).
- Expected: form G closes a deposition at the end of the step that shows it, late by at most D/v_s (owner decision of 2026-11-03). Two step grids close at different times. In that lateness the flow carries at most what a flow at the deposition velocity carries.
- Code: the test still held the bound from when a closure was searched to 1 us. G's commit (eb4d72b87) gave its sibling `SolidClosureFeasibilityTest` this bound but did not name this test.
- Change, test only: the same bound. The lateness must be at most D/v_s, and the solids may differ by up to (solids per litre) x area x deposition velocity x lateness, plus 2e-6 kg. The class passes (10 tests).
## Printed lines that moved
19 classes, all crystal, solid or dry-tank worlds, all passing. The moves come from carried crystals and G, as `CARRIED_CRYSTALS_REVIEW.md` expects:
- The closed frosted tank ends 4.7 K warmer, with 4.15 against 5.59 mol of crystal.
- `SolidChainTransportTest` takes 2 steps against 13.
- `SolidClosureFeasibilityTest` locates the deposition at 0.05 s against the reference's 2.5 ms; D/v_s bounds that.
- The 95 K, 2 MPa refusal now names `LIQUID_LIQUID`.
- The carried TP answers no vapour beside the dry ice where the competition gave 55 mol.
- The pilot grid has 56 two-phase states against 59.
- Larger moves: the H3 slush vent's first two slices (-80.0 and -104.8 kg/s against -89.6 and -100.0); the refuted proposal's tanks, 2.36 against 2.51 MPa; `MELT_GATE` 778's tank-line flow, -1.0e-4 against -1.6e-10 kg/s. The filter control closes at a flow velocity of 0.043 against 0.081 m/s, at G's step end.
- The rest move by 1e-4 relative or less.
## Seed 301
Replayed alone on one worker: 16 holds with the arrival rule, 0 with it switched off (847 ms). So the rule caused it; main's merge changed only column code.

Expected behaviour:
- A 1 m3 tank fills with water at 357 K from a 376 kPa generator (51.6 kg/s in, 10.8 kg/s out to a junction).
- After 3.9 s of the interval it is full. The excess, 0.55 kg/s, rises 2 m up a 1 cm line into a dry tank at 1 atm.
- Water at 357 K is subcooled at 1 atm (it boils at 373 K). After 0.14 s the dry tank should hold 77 g of liquid water.

What the code did:
- In the converged pass the full tank holds a gas phase of 1.2e-22 m3 of nitrogen from the junction.
- The rule prices what arrives at the line's top port as that port's leading phase at a vanishing flow (`endMoles`, `endSpecificEnthalpy`). That phase is the trace gas. So all 77 g became 2.76 mol of nitrogen, a vapour of 0.081 m3, laid out vapour-only.
- The next pass delivers what the port really draws, which is water. A vapour-only layout cannot hold liquid water's enthalpy. The tank's iterate runs to the 10 K floor, and the line search stalls at residual 18.4 on its energy row at every step size.
- With the rule off, the booking states the arrival from the booked draws, and the island runs clean.

Proposal for the owner:
- **A (recommended)**: the pass rule lays a dry vessel out from the pass's own phase draws (D11, `equations.draws`). Those are the phase rates the reconstruction books, so the layout holds what the booking will book. The start stage has no draws, so it keeps the end stream. To check A: 301, the nine worlds, 181/397/431, `HoldClassWorldsTest`, the named tests, then one campaign of the new form.
- **B**: merge as it is, with 301 as an open item. Bundled 993 equals the base lane's better count.
- Risk under both: the start stage prices a first fill the same way, at the leading phase of a port. A donor with a trace phase can mislead it too. No campaign seed has shown this.
## Tools and logs
`tools/dry-ice-cost/carried/hook-u.pl` and `traceU.sh` add a `LAID` line (what the rule lays into a dry vessel, and the donors' phases). `DryIceProbe` now describes bundled worlds. Run bundled seeds with `-DholdFuzz.cold=false`. Logs: the main checkout's `tools/dry-ice-cost/logs/final-lane-logs-2026-11-03.tar.gz`.
