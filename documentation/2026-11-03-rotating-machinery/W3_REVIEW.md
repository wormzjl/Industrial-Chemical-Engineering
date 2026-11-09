# W3: shaft trains, review

Commit `69056145f` (`W3_BUILD_PLAN.md`, owner decisions D27 and D28). Built by an Opus medium subagent and reviewed here.

## 1. What was built

- **Trains** (plan section 1). `science/machine/ShaftNetwork` joins a driver and its loads through shaft blocks along one shaft line (A16). `ShaftTrain` holds the members, the gearbox ratio (automatic, A20) and the state. Two drivers give `TWO DRIVERS` (A17). `runtime/fluid/ShaftTrains` owns them per level. A train recompiles on a placement, restore or refresh.
- **Per-slice logic** (section 2). `ShaftPlan` is applied at dispatch on the server thread (A41). A motor runs as supplied until W5 (A14). `OVERLOAD` acts above 1.0 × rating (A21). The overspeed trip latches at 1.10 × set (A22); reset is `/icheme shaft reset` (`ShaftCommand`).
- **Governor** (section 3, D27). The speed sensor (`Sensor.SPEED_PICKUP`, item `speed_sensor`) sits on the turbine's coupling guard. It binds the nearest unbranched upstream control valve, source `SHAFT_SPEED` (A24, A25).
  - The engine sets the valve's mass target `ṁ = P(set speed)/w`, where `w` is the specific work the turbine last booked (A45).
  - Short of steam, the speed falls by the damped affinity step. A turbine without a sensor runs ungoverned (A23).
- **Saves** (section 4):
  - fluid facts schema 10 (the shaft parts, the valve source by name) and accessory facts schema 3, with corpus images `compat/fluid/schema-10/` and `compat/accessories/schema-3.snbt`;
  - checkpoint format 35, wire registrar `mover-2`;
  - old casings, shaft blocks and creative motors are adopted when restored (A29, A44).
- **Presentation** (section 5). The driver state reaches clients every 100 ticks, only for clients that watch it. `DriverSounds.accept` and `MoverSounds` now get the train speed. The casing and turbine status line reads `SHAFT: status, rpm, %` (A43).
- **Kinds.** `TopologyCompiler.Kind.TURBINE` (A37) joins `FlowsheetCatalog` (`TURBINE`).
- **Fixed on the way.**
  - W4's `science/equipment` sources called `Math.pow`, `log`, `log10` and `hypot` directly. They now call `DeterministicMath`, and `sqrt` replaces `hypot`; `DeterministicMathSourceTest` failed on them.
  - `LineGeometryTest` skips the turbine, which no line block draws, as it skips the pump and compressor.

## 2. Tests

- **New:** `ShaftNetworkTest`, `ShaftTrainTest`, `ShaftPlanTest`, `ShaftCouplingTest`, and the fluid GameTests `ShaftTrainGameTests`.
- **Affected set (before the fixes):** quick 1010 tests with 1 failure (the source test), equipment gates 147 with 1 failure (the line test), fluid GameTests 38 of 38 pass.
- **After the fixes:** quick 165, equipment gates 120 and hold gates 23 pass. `ColumnSolveTest` (column gates) reads only `ProcessUnit.bays()`, which the `hypot` line does not touch, so it did not run.
- **Translations:** `check.js` passes for zh_cn, fr_fr, es_es and ja_jp, 2663 keys each.

## 3. Campaign

1000 seeds, run alone. The held seeds are the same as W2-turbine's:
- cold random: 992 clean;
- bundled random: 994 clean;
- bundled defaults: 1000 clean.

No world places a shaft train. Bundled defaults' counters are identical. The random distributions' counters move by under 0.3 % (Newton iterations), and bundled random's backtracks fall 1.0 %.

## 4. Readings for the owner (A41 to A52, taken under D28)

- **A42 departs from D28's A27; the owner kept it as built (D29, option A).** Pump and compressor power is read at commit from the end flows, heads and suction state, not booked as a `SHAFT` boundary per mover. The reason: `BackwardEulerStepTest` asserts that a closed pump island has no boundary transfer. The turbine keeps its `SHAFT` boundary. The planned test "Σ machine SHAFT = pumpWorkJoule" was dropped.
  - Option A (as built): read at commit. The energy balance is unchanged.
  - Option B: book `SHAFT` per mover, as A27 planned, and restate that `BackwardEulerStepTest` row.
- **A48.** The GameTest "a motor at 150 % trips, reset restarts it" became a test of the creative motor's power limit, because lining up a motor structure's coupling with the pump needs the in-game layout. `ShaftTrainTest` covers the trip and its latch.
- **The other readings stand as built:**
  - A41: plans are applied at dispatch.
  - A43: `FluidView` is not extended; the status line carries the shaft state.
  - A44: adoption walks up to 256 cells.
  - A45: the governor's `w`, with k 1.3 before the first booking; steam counts as short below 0.99 × target.
  - A46: booked machine power uses k 1.4.
  - A47: `PROTOCOL` stays `fluid-17`.
  - A49: a shaft block's drawn size is set only in loaded chunks.
  - A50: a frame's design displacement is its registered flow.
  - A51: a pump shape with no duty is rated at 100 m.
  - A52: the speed pickup shows no meter until W7.

Code guide: no change yet. The fluid network, equipment and instrument chapters change at the merge refresh.
