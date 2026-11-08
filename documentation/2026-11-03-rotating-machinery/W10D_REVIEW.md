# W10d: pump readings and curve, review

Commit `4eec41cad` (`W10D_BUILD_PLAN.md`, owner decisions D37 and D38). Built by an Opus medium subagent and reviewed here.

## 1. What was built

- **Readings.** Every pump page (constructed casing and in-line pump) gains two groups after Drive:
  - Suction: NPSH available (m), NPSH required (m, NPSH3 at the accepted flow), and gas (% by volume).
  - Health: remaining (%).
  - The rows come from `DeviceColumn.suction(...)`, a Minecraft-free builder.
- **Status words.** `FAILED` (shown as `FAILED: use a repair kit`, first) takes precedence. Otherwise `CAVITATION` while σ < 4 (`Wear.INCEPTION`), otherwise `GAS` while r > 0.5 (`PumpHealth.GAS_RATIO`). `HEALTH n %` leaves the status line, because the meter shows it.
- **Curve.** `client/gui/common/PumpCurve` is Minecraft-free, and its fill loops run in batches.
  - On a constructed centrifugal pump's Overview glass it draws H(Q) at the present speed, NPSH3(Q), and the dashed 4 × NPSH3 inception curve (D38).
  - It also draws the operating point and NPSH available as a solid line.
  - The NPSH axis grows to fit 4 × NPSH3 and NPSH available. Zero speed and missing reads draw no NaN.
- **Data.** `FluidView.Pump(npshAvailable, npshRequired, gasShare, health, Curve)`. A missing read is sent as null, because Gson carries no NaN.
  - It is filled by `FluidWorldAuthority.pump` from `PumpHealth.suction`, which is stored at commit from the observation, and from the casing's `CentrifugalPump` law.
  - The view is sent only to open pages, and reading it runs no solve.
- **Observation.** `PipeObservation` gains `npshRequired` (m) and `vapourShare` (α).
  - The solve is unchanged. `gasRatio` and `dry` still read α only for machines, so in-line pumps neither churn nor say `GAS`.
- **Texts.** en_us only. The health hint now names cavitation and running dry; the new key is `pump_curve.inception`.

## 2. Renders (`tmp/gui-prototypes/`, both themes, opened and scrolled)

- **`pump-readings-casing`:** cavitating. NPSH available is 6.8 m, between NPSH3 3.3 m and 4 × NPSH3 13.2 m at 100 m³/h, and the display says `CAVITATION`. The blue line now runs under the dashed curve at the operating point, so the curve no longer reads as safe.
- **`pump-readings-quiet`:** 70 % speed, NPSH available 12 m, above both curves, and no word on the display.
- **`pump-readings-inline`:** the readings, with today's composition table on the glass.
- **Column heights at 1920 × 1080:**
  - casing and quiet: 604 px in a 398 px view;
  - in-line: 458 px in 398 px.
  - All three scroll, which D38 accepts for the in-line page and which was already true for the casing page.

## 3. Tests

- **Builder, run by the subagent and all passing:**
  - `DeviceColumnTest` 9, including `aPumpsSuctionAndHealthFitTheDoor` (width sweep, `assertInLine`, every text in English);
  - `PumpCurveTest` 3: axes fit every curve, points inside the plot, no NaN at zero flow or zero speed, at most 40 draw calls (budget 44);
  - `FluidPacketCodecTest` 23, including `aPumpsReadingsRoundTripTheLivePayload`;
  - `PumpHealthTest` 2, `FluidGuiConsistencyTest` 5, `HeatDiagramTest` 5, `GaugeAccessoriesTest` 13 and `DeterministicMathSourceTest` 2.
- **Affected set (here):** every other test class that loads a changed class or a harness on it. `test` 592 and hold gates 58 pass, with 0 failures, in one run (`tmp/rotating-machinery/w10d/`).
  - `equipmentGates` was not run. Its only class that loads `FluidView` is `UnitTextTest`, which fails until the translation commit.
- **Fluid GameTests:** 44 of 44 (run `w10d-1`). `PumpHealthGameTests` now asserts the view's `Pump`, `CAVITATION`, and that `HEALTH` is gone.
- **No campaign.** The solve's inputs and outputs are unchanged. The one new cost is a suction walk (`InletPhase.supply`) per running in-line pump per solve, beside the one `liquidDensity` already makes; it does not flash. The final lane's campaign reads it.

## 4. Readings (A137 to A141)

- **A137 (owner kept it, D39):** the gas reading for in-line pumps needs that extra supply walk per solve. The alternative is "—" for an in-line pump's gas and no added work.
- **A138:** the curve's operating point is computed on the client from view fields the Head meter already reads (mass flow, pressure change, density). This is display arithmetic, not process state.
- **A139:** NPSH available is a solid blue line, and inception is dashed in its own colour, with a legend entry.
- **A140:** a constructed pump's Overview glass shows the curve instead of the composition tables, as in the prototype the owner saw. The Phases bar on the door still gives the shares.
- **A141:** `PassiveStepSolver` changed in observation code only. The final lane's references confirm that nothing moved.

Code guide: no change yet. The fluid runtime and GUI chapters change at the merge refresh.
