# D12 build: affected tests (2026-11-04)

Code: D12 on top of `cf110c875`, not committed. The solver weights a taut vessel's rows by the pressure they imply (E8, reading A11). The curve is clipped at zero flow (reading A11). The reverse closure goes for set-flow movers. E5 (a) is the suction-cap start seed. Run: 99 quick-suite classes and the 4 hold gates, once, one fork (`tmp/rotating-machinery/w2-tests/d12-tests.txt`). The same set passed at `cf110c875`.

Result: 619 tests, 15 failed. The hold gates pass.

## 1. Expected behaviour

A dead-headed pump runs on its curve to zero flow at its shutoff rise. In a closed pocket, for example the junction between the pump and a closed valve, nothing else sets the pressure: the pocket stands at exactly the shutoff. Against a vessel already above the shutoff, nothing flows forward. Two physical answers exist there. With a check valve (or a closed discharge), the pump holds and the pressure step sits across the valve. Without one, the pump runs backwards.

## 2. What the code did

Reading A11 clipped the curve: zero flow at the shutoff and at every rise above it. That is a vertical half-line in the rise-flow plane, which is a check valve built into the curve. On it the row pins the flow and does not read the rise.

- **Singular Jacobian (8 tests).** A pocket between a dead-headed pump and a closed valve or a generator has no row left that sets its pressure: every pressure from the shutoff upward solves it. Failing: `PumpAheadOfFlowValveTest` x2, `SealedDischargeTest`, `InlineFilterLineHoldTest` x3, `ColumnRunTest` (pump behind its valve), `PumpRateWarmStartTest`.
- **Line-search stalls (4 tests).** A Newton iterate that crosses the shutoff lands on the flat part, loses the rise derivative and cannot step back. `NetworkRegimeTest` (a 6:1 compressor on its velocity cap, residual 2.9e4; its suction walk is empty, so the E5 (a) seed is not involved), `LevelHeadTest`, `FluidDataRangeIslandTest` (a compressor), `FluidPumpedFillLineTest` (3.2e-9).
- **Exact rest lost (3 tests).** Without the closure, zero flow comes out of the Newton: −1.6e-9 kg/s (`PumpRiseScalingTest`) and 2.6e-14 kg/s (`IslandCertificateTest`), where the tests expect exactly zero. `PumpRateWarmStartTest` (the second test) gets no seed.

The stalls near the tolerance (1e-9 to 6e-9) in `FluidPumpedFillLineTest` and `LevelHeadTest` could also come from the solver weights. This run cannot separate the two causes.

## 3. Proposal for the owner

The F2e curve at `cf110c875` already includes zero flow. Its lift (δ) puts zero flow exactly on the shutoff rise, and the rise climbs continuously as the flow falls. The clip added nothing to that end point. It only replaced the curve past the shutoff with a vertical line, and that line made the problem ill-posed. Past the shutoff, physics leaves a choice:

- **(A) Recommended: the curve at `cf110c875` plus its check valve.** Keep F2e unchanged: the slope continues past the shutoff, and the reverse closure stands for a check valve on a reverse drive. This is the state before D12. E8 is then the solver's job alone (the weighted rows), as the 896 trace showed. Revert the clip and its three test edits. Keep the solver weights and E5 (a).
- **(B) The curve with no check valve.** F2e past the shutoff, reverse flow allowed, no closure: a pump pushed past its shutoff runs backwards. This is well-posed, but a dead-headed pump against a higher tank drains it backwards through the pump.
- (C) The clip, with a regularised half-line (a tiny reverse slope). Not proposed: it is a check valve with an invented leak, and its value has no physical basis.

After the owner chooses: run the 15 failing classes, then the whole affected set once (rows 1-3 above), then one full campaign. The campaign runs alone, after the drum-outlet session's campaign ends.
