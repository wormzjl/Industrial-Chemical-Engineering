# P2: one dense fluid in the readings

Batch 2026-11-16-supercritical-followups, package P2 (plan section 4, items 5, 7 to 12 and 16), sub-thread, 2026-11-16.
Owner: "Not now" to the `icheme-4` reservation, then "Yes, run P2" without the wire field. Point 1 (item 6) is not built: no
payload, no `NetworkProtocol` or `FluidNetwork` layout change, no `FORMATS.md` row, no saved field.

## Summary

**Expected.** One fluid on a supercritical isotherm has no interface. Its density runs from a gas's to a liquid's, and the
network's slot rule moves it from the vapour slot to a liquid slot at reduced density δ 1, where nothing physical happens.
A reading of it is the gas's at or below δ 0.5, rises with its density, and is the liquid's from δ 1, with no step between.
The plan's weight does that: `w = FluidThermodynamics.pumpLiquidLikeWeight`, the smoothstep of δ from 0.5 to 1 (0.104 at
0.6, 0.648 at 0.8).

**What the code did.** Every reader named the slot. A level transmitter on a tank of carbon dioxide at 310 K read 0 up to
δ 1 and full from 1. The sight glass was clear, then coloured. A burst booked the vapour slot as gas and the liquid slot as
liquid, so dense carbon dioxide at δ 1.6 was heard as a liquid spill, and a dense heavy cut below δ 1 went to the air whole.
The simulator sized a dense line as a gas line to δ 1. A pump on a two-phase supply had no head share. The density row's
advice was in a javadoc only. The "Dense" share was white in the vapour slot and amber in the liquid slot. The Sizing tab's
Model box read the model's number ("4") beside "m³/h".

**What P2 does.** All server readings are taken from the server's own state, and no field goes on the wire.
- **Server, `DenseReadings` (new, 141 lines).** It holds `weight(state)`: `w` where `State.dense()`, otherwise NaN.
  `FluidWorldAuthority.denseWeight(id)` reads the device's node state, or the stream its pipe moved (each side from the node
  it left). The read is a scan of the island's presented snapshot: no solve, and outside the bucket's index cache.
  - **Level.** The level reads `levelVolume = v_oil + v_aq + v_solids + w · v_vap`. This holds for every server reader:
    `BuiltInInputs` LEVEL, the digital level meter (`Sensor.read`), and both level loops in `ControlLaw` (the valve's and
    the column feed's).
  - **Sight glass.** A dense stream shows its own liquid colour, with its opacity times `w`, and no bubbles.
  - **Burst.** `releaseContents` splits each compound into a gas part `held(1 − w)` and a liquid part `held · w`, in any
    slot. A line block's side is split by the node that side left. The volatile share of the dense liquid part counts as
    gas in the masses `BurstPipes` hears (`DenseReadings.heard`).
- **Simulator, `Service.of`.** A stream that reads "Dense fluid" (δ above 0.5) skips the vapour tests and takes the liquid
  branch. A dense fluid's water share is its own.
- **Client.**
  - **Pump page.** A centrifugal pump with 0 < α < 1 shows "Head share" beside "Gas", from the same `headShare(α, p)`.
  - **Limits table.** The table has a per-row hover. Its first user is the "Suction density" row and its advice.
  - **Phase colour.** `DenseLabel.read` returns the row and `w` from the same cubic root it already evaluated. The "Dense"
    share then blends from the vapour colour to the liquid colour by `w`, on the device screen and in the simulator.
  - **Sizing tab.** The Model box reads `UnitDesign.sizeText`, the capacity, for the plunger pump, the vacuum pump and the
    prime mover. The steppers still step the model.

**Bit for bit elsewhere.** With `w` NaN or 0 every changed path keeps its old arithmetic: the level sum is the same
expression in the same order (`w > 0` gates the extra term), the release split and the sight glass take the old branch, the
masses heard add and subtract `0.0`, and `Service.of` reaches the old tests. Two tests compare raw bits
(`otherContentsReadTheirLevelAsBefore`, `anOrdinaryReleaseIsAsBefore`). The engine's heads and ports (`PortStreams`,
`TankOutlets.bottomHead`) do not change; no solved value can move.

**Moved readings.** One existing assertion moved: `DeviceMetersTest.denseContentsNameTheirRowLevelAndPumpHead`, whose
two-phase pump at α 0.896 (74.05 bar) gains a head share of 59.6 % beside its gas share of 89.6 %. No stored campaign,
printed line or chain-100 value reads these paths. The dense GameTest loop's vessel at δ 0.679 (S4 review) now counts
`w = 0.29` of its vessel in a level read, where it counted 0.

**Open.** Item 6 (the client's catalog on a datapack server: the label, its colour and the head share follow the client's
data). The boot level (`level_boot`) reads the aqueous row alone, unchanged. A non-dense liquid that flashes on release
(liquid propane) is still heard as a liquid spill: an owner option, not done. No point was stopped. Left for the final
lane: the dense GameTest loop (`icheme_dense_test`), `LanguageFilesTest`, `UnitTextTest` (section 4).

## 1. Carbon dioxide at 310 K, before and after

| δ (slot) | Level, share of full | Sight glass | Burst heard as | Line service (open / into a pump) | "Dense" share colour |
|---|---|---|---|---|---|
| 0.4 (vapour) | 0 → 0 | clear → clear | gas → gas | GAS / GAS → same | Vapor, grey → same |
| 0.6 (vapour) | 0 → 0.104 | clear → tint × 0.104, no bubbles | gas → gas | GAS / GAS → LIQUID / SUCTION_COLD | grey → 10 % of the way to amber |
| 0.8 (vapour) | 0 → 0.648 | clear → tint × 0.648, no bubbles | gas → gas | GAS / GAS → LIQUID / SUCTION_COLD | grey → 65 % of the way to amber |
| 1.0005 (liquid) | 1 → 1 | coloured → same | **liquid → gas** | LIQUID / SUCTION_COLD → same | amber → amber |
| 1.6 (liquid) | 1 → 1 | coloured → same | **liquid → gas** | LIQUID / SUCTION_COLD → same | amber → amber |

What reaches the air from dense carbon dioxide does not change: its volatile share is 1, so either split sends it whole. The
burst's sound changes, from a splash to a hiss. A dense heavy cut changes the other way. O-xylene at 800 K and δ 1.2 is in
the liquid slot, and it stays more than 95 % on the ground (test). Below δ 1 it went to the air whole; it now goes by `w`.

## 2. Each point

- **Level (item 8, point 2).** `FluidView.Tank.level(state, w)`. The one-argument form passes NaN. `TankDisplay` and the
  floating roof keep the one-argument form: an open vessel stands at the air's pressure, where no fluid reaches δ 0.5. A
  level controller on a dense vessel now holds a density, which rises with its inventory. The tank page's line keeps "No
  interface". The client's `DeviceMeters.level` is unchanged.
- **Sight glass (item 7, point 3).** `PipePresentation.sightGlass(…, dense)`, fed by `Displays.publishSightGlass`. At `w` 1
  the glass equals the liquid slot's glass bit for bit (test). At `w` 0 it is `CLEAR`, the gas's glass.
- **Release (item 16, point 4).** `DenseReadings.release`, `moved` and `heard`. The masses heard move only where a dense
  fluid's liquid-like part flashes. Every other release is heard as before.
- **Line service (item 9, point 5).** `Service.dense` repeats `DenseLabel.dense`, because science does not import the
  client package. `water` reads the dense fluid's own slot.
- **Pump page (item 10, point 6).** `Suction.headShare`, row `npsh:head`, new key `head_share.gas_hint`. It appears only with
  a curve and 0 < α < 1, so a plunger pump, a set-flow pump and a single-phase supply show none.
- **Hover (item 11, point 7).** `SizingGlass.Limits` lays out the lower table once, and both the drawing and `hover` use it.
  `advice(limit)` returns text only for `PUMP_DENSITY` (new key `flowsheet.sizing.advice.pump_density`). `SimulatorPage`
  returns the glass's hover where the door has none.
- **Colour (item 12, point 8).** It is done without a new field, because the client's own root gives δ. A fluid in the vapour
  slot blends toward the aqueous colour if it is at least half water (the slot rule's label), otherwise toward the oil colour.
- **Model field (item 5, point 9).** The renders show "100 m³/h" for PP-100, where "4" stood before.

## 3. Renders

The renders are in `tmp/followups-p2/renders/`, light and dark:
- `dense-tank-*` and `dense-pump-*`: the blended "Dense" share at δ 0.8.
- `dense-two-phase-pump`: the head share at 96.1 % beside 3 % gas at 2 bar.
- `plunger-0_7-sizing`: the Model box at 100 m³/h.

Images do not go into `documentation/`. Their place is the main checkout's `research/2026-11-16-supercritical-followups/renders-p2/`.

## 4. Runs

All runs are filtered (`--tests`, `--max-workers 4`, `--offline`). The classes come from `node tools/merge-lane/affected.mjs select --since d717df79c`. `LanguageFilesTest` and `UnitTextTest` are left out (the translation commit's). None failed, so no classifier retry.

| Run | Task | Classes (result files) | Tests | Failures |
|---|---|---|---|---|
| 1. The tests P2 adds or edits | `test` | 5 (DenseReadingsTest, DenseLabelTest, DeviceMetersTest, ServiceTest, SizingPageTest) | 61 | 0 |
| 2. The rest of the selection | `test` | 158 | 1,170 | 0 |
| 3. Hold gates in the selection | `holdGates` | 12 | 116 | 0 |
| 4. Equipment gates in the selection | `equipmentGates` | 2 (CrossflowEquipmentTest, EquipmentShapeCompatTest) | 26 | 0 |
| 5. Column gate in the selection | `columnGates` | 1 (V3FeedFlashStabilityTest) | 2 | 0 |

Not run, left for the final lane: `fluidSolverRegression` (selection NONE), the GameTests (selection "due": the dense loop `icheme_dense_test`), `LanguageFilesTest` and `UnitTextTest`, and the whole tasks. `build.gradle` needed no gate-group change.
