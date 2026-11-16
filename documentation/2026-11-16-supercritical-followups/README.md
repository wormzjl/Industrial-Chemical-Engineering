# Supercritical follow-ups

Status: **In progress** since 2026-11-16 (P1, P3 and P4 merged into the batch branch; P2 done on its own branch without the wire field, for the main thread to merge). Branch `claude/supercritical-fluid-support-d0ffa4`, base `dfbdc583c` (main 0.34.0).

Purpose: the open owner items of [2026-11-15-supercritical-fluids](../2026-11-15-supercritical-fluids/README.md), on the
owner's "fix them": the plunger pump's clearance loss and work, one dense fluid in the level, sight glass, release,
line service and device pages, the column's feed flash by a stability test, the plunger pump's own sound.

## History

- 2026-11-16: the plan, by the main thread: 17 items sorted (13 fixed in four packages, two kept as they are with the
  reason, one offered, one outside the list). No code changed. [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md).
- 2026-11-16: P3 (items 14, 15), branch `claude/hungry-nash-656c11`: the feed flash by a stability test, form D (F3 with
  the vapour rule; main thread decision, open to the owner's veto). Dense grid: 10,602 answered, 0 refused (F1 898, F2
  891), 0 wrong one-phase, moved only the 181; column vs engine splits within 9.4e-11 in ln K; crude unchanged. Filtered
  run: 12 classes, 34 tests, 0 failures. [P3_COLUMN_FEED_REVIEW.md](P3_COLUMN_FEED_REVIEW.md).
- 2026-11-16: P3 tightening (main thread): rule D reads the engine's split by the engine's own lighter phase, not on the
  forced vapour root (CO2/pc03 at 220 K was split as vapour-liquid, now one liquid as before); dense grid unchanged;
  12 classes, 35 tests, 0 failures. [P3_COLUMN_FEED_REVIEW.md](P3_COLUMN_FEED_REVIEW.md).
- 2026-11-16: P1, sub-thread (branch `claude/gallant-leavitt-f29f1e`): the plunger pump's clearance loss and isentropic work
  on the suction's isentropic exponent (`NetworkStreams.isentropicExponent`, `suctionKappa`), the run and sizing of a
  plunger-typed Pump tile (`Passages.pumpType`). Six reference rows within 0.35 % of η_v. Stop decisions: exponent by
  differences (1 A), a pump with no type runs centrifugal (2, narrowed), the view and transport read `suctionKappa` (3).
  [P1_PLUNGER_REVIEW.md](P1_PLUNGER_REVIEW.md).
- 2026-11-16: P4, sub-thread: the plunger pump's own loop `plunger_pump_running` (candidate A of three, valve knocks over a 15 Hz ripple and a crank and gearbox rumble, 300 rpm, 4 s, RMS 0.0918, floor 13.4 dB down), picked by `MoverSounds.casingLoop`; pitch law and clamp kept; subtitle reuses `pump_running`, no lang change. Filtered run: 5 classes, 27 tests, 0 failures. [P4_SOUND_REVIEW.md](P4_SOUND_REVIEW.md).
- 2026-11-16: P2, sub-thread (branch `claude/quirky-yonath-d0c746`), without the wire field (point 1, item 6 open): one dense fluid reads by its liquid-like weight `w` (smoothstep of δ 0.5 to 1) in the level (`v_vap` counts `w`; CO2 at 310 K reads 0, 0.104, 0.648, 1, 1 at δ 0.4, 0.6, 0.8, 1.0005, 1.6), the sight glass, the burst (split by `w`, the volatile share heard as gas: dense CO2 hisses, a dense heavy cut spills), the simulator's line service (liquid rows above δ 0.5), the pump's head share on a two-phase supply, a per-row hover on the limits table (suction density), the "Dense" colour blended by `w`, and the Model box's capacity. Server reads `w` from the island snapshot (`FluidWorldAuthority.denseWeight`), no solve. One assertion moved (head share 59.6 % at α 0.896). Filtered runs: 179 classes, 1,375 tests, 0 failures. [P2_READINGS_REVIEW.md](P2_READINGS_REVIEW.md).

## Documents

- [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md): the items, the common rules, packages P1 to P4, the order.
- [P3_COLUMN_FEED_REVIEW.md](P3_COLUMN_FEED_REVIEW.md): P3, the feed flash by a stability test: forms, moved answers, the two-liquid limit.
- [P1_PLUNGER_REVIEW.md](P1_PLUNGER_REVIEW.md): P1, the plunger pump's clearance and work: reference rows, moved values, runs.
- [P4_SOUND_REVIEW.md](P4_SOUND_REVIEW.md): P4, the plunger pump's own sound: the real machine, the recipe, levels against the skill, the candidates.
- [P2_READINGS_REVIEW.md](P2_READINGS_REVIEW.md): P2, one dense fluid in the level, sight glass, burst, line service and device pages: carbon dioxide at 310 K before and after, each point, runs.

## Code areas (planned)

- P1: `science/machine/PlungerPump` (and `PositiveDisplacement.share`), `NetworkStreams.isentropicExponent`, the solve's `suctionKappa` (read by `ConservativeTransport` and `ViewBuilder` too), the simulator's `MoverUnit`, `PumpSizing`, `Passages` and `SizedPassages`.
- P2: `runtime/fluid/DenseReadings` (new), `FluidWorldAuthority.denseWeight` and `releaseContents`, `ViewBuilder.denseWeight`, `FluidView.Tank.level`, `PipePresentation.sightGlass`, `Displays`, `Releases.volatileMoles`; the level readers `BuiltInInputs`, `ControlLaw`, `Sensor`, `InstrumentService`; `Service`; `client/gui/common` `DenseLabel`, `DeviceMeters`, `DeviceColumn`, `FluidDeviceScreen`; `client/gui/flowsheet` `StreamProps`, `Door`, `SizingPage`, `SizingGlass`, `SimulatorPage`; `en_us.json` (two keys); `tools/heat-gui-renders/GuiPrototypes` (the two-phase pump page). No `network` change.
- P3: `science/column/v3/thermo` (`V3FeedFlash`, new `V3FeedEngine`, the PR session and package), `build.gradle`'s column gate group.
- P4: `client/MoverSounds.casingLoop`, `ModBlocks`, `sounds.json`, `plunger_pump_running.ogg`, `tools/machine-sounds/plunger_candidates.py`.

## Open items

- The owner approved the four threads ("Approve all", 2026-11-16). The owner answered "Not now" to the reservation of protocol `icheme-4` on `main`, then "Yes, run P2" without the wire field: item 6 (the client's catalog on a datapack server) stays open.
- Kept, open to the owner's veto: Auto's rule (item 2); "Dense" in the aqueous slot (item 13).
- P3, for the owner: two hydrocarbon liquids are answered as one liquid at the Wilson endpoint (about 1.1 kJ/mol); the cold
  Tia Juana crude's heavy-cut second liquid (derived pairs) is its own item.
- An in-game check of the plunger pump (constructor, model, sound), offered at the end.
