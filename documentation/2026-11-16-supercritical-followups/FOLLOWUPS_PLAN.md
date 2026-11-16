# Supercritical follow-ups: plan

Batch 2026-11-16-supercritical-followups. Main thread, 2026-11-16. Owner: "fix them" (the open items of
`2026-11-15-supercritical-fluids`, merged as 0.34.0). No code changed by this plan. Base: `main` `dfbdc583c`.

## 1. The items and what happens to each

| # | Item (source) | Disposition | Package |
|---|---|---|---|
| 1 | Plunger pump on a gas: no clearance loss (S8 review 7.1) | Fix: the clearance term and the isentropic work | P1 |
| 2 | Auto's rule proposes a plunger pump (S8 7.2) | Kept: it is the selection rule of real practice; no bundled sheet changes. Recorded, open to veto | none |
| 3 | The plunger plays the compressor's loop (S8 7.3) | Fix: its own synthesised loop | P4 |
| 4 | The run door shows a centrifugal efficiency (S8 7.5) | Fix: the run reads the sized type | P1 |
| 5 | The Model entry reads a number with "m³/h" (S8 7.6) | Fix: it shows the capacity | P2 |
| 6 | Client catalog on a datapack server (S5 7.1) | Fix: one server field; the client-side model is deleted | P2 |
| 7 | Sight glass flips at δ 1 (S5 7.2) | Fix: the liquid-like weight | P2 |
| 8 | Level transmitter 0 or full (S5 7.3) | Fix: the liquid-like weight | P2 |
| 9 | Line service flips at δ 1 (S5 7.4) | Fix: one dense fluid takes the liquid rows | P2 |
| 10 | No head share for a pump on two phases (S5 7.5) | Fix: the row is shown | P2 |
| 11 | No hover on the density row's advice (S5 7.6) | Fix | P2 |
| 12 | The "Dense" share's colour flips at δ 1 (S5 7.7) | Fix: blended by the weight | P2 |
| 13 | A dense mixture in the aqueous slot reads "Dense" (S5 7.8) | No defect: water above 647 K is one dense fluid; the word is right | none |
| 14 | Column feeds still refused as one phase, O1 (column review) | Fix: O1 B | P3 |
| 15 | 181 real splits answered as one phase, O2 | Fix: at most three forms measured, one chosen | P3 |
| 16 | The burst release books a dense fluid by its slot (merge review 6) | Fix: the weight and the volatile share | P2 |
| 17 | In-game check (S8 7.9) | Offered to the owner at the end; not started by an agent | none |

Not in the owner's list and not done: the bolts as a relief (S8 7.4), the other stages' older owner items.

`w` below is `FluidThermodynamics.pumpLiquidLikeWeight(state)`: the smoothstep of the reduced density δ from 0.5 to 1.0
(owner, "Use 0.5 to 1.0"); NaN unless the state is one fluid. "Dense" is `State.dense()`. A dense fluid sits in the vapour
slot below δ 1 and in the liquid slot from δ 1 (the slot rule, S2c). Every fix below is continuous at δ 1 and equal to
the gas reading at δ ≤ 0.5.

## 2. Common rules for every thread

- Read `AGENTS.md` first, whole. It binds. Then this plan's section for your package.
- Work only in your own worktree and branch. Never touch `main` or another worktree. Nothing is pushed. No download.
- Scratch only in `<your worktree>/tmp/<name>/` with a row in `tmp/INDEX.md`. Never the system temp folder.
- Gradle: at most two invocations on the machine (another batch may hold one: check first, wait if two run). Always
  `--tests`, `--max-workers 4`, `--offline`. Select classes with `node tools/merge-lane/affected.mjs select`, not by
  hand. No whole task, no GameTest, no chain-100, no campaign: the final lane runs those once. Do not run a test again
  that passed and whose inputs did not change. Do not run a test with its fix switched off. Report no times.
- `LanguageFilesTest` and `UnitTextTest` are not run (the translation commit comes later). Change only `en_us.json`.
- A result that moves is stated with its old value, its new value and the bound it keeps (AGENTS.md "Results that
  move"). Conservation stays exact.
- Documents: `documentation/2026-11-16-supercritical-followups/<PACKAGE>_REVIEW.md` (physics first: expected, what the
  code did, what was done; summary at most 50 lines), one history line in the batch `README.md`. The code guide chapter
  of the code you changed is updated in the same commit series.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `git status` clean at the end.
- Report to the parent only: at most once every 50 minutes (a few lines), at once on a stop, and the final report (the
  commits, the runs' table, the moved values, what is left for the final lane). Otherwise keep working.
- Stop and report at once: a needed write outside your files, a test that fails for a reason outside your package, a
  rule of AGENTS.md that blocks the plan, or a premise of this plan that the code contradicts.

## 3. P1: the plunger pump's clearance and work (items 1, 4)

Files: `science/machine/PlungerPump.java`, `PositiveDisplacement.java` (a slope helper only),
`science/fluid/network/PassiveStepSolver.java` (`kappa`, `suctionKappa`), `science/fluid/thermo/NetworkStreams.java` and
`FluidThermodynamics.java` (one new reading), `science/flowsheet/unit/MoverUnit.java`,
`science/flowsheet/sizing/PumpSizing.java`, their tests, the rotating-machinery chapter of the code guide. Not yours:
`client/**`, `runtime/**`, `network/**`, `science/column/**`, `Service.java`.

**Expected.** A plunger pump displaces a fixed suction volume. The fluid left in the clearance re-expands before the
suction valve opens, so the delivery is `η_v = 1 − c (ρ_d/ρ_s − 1)` of the displacement, `c` the clearance (0.10). The
work on a compressible fluid is the isentropic `∫ v dP`, below `Q ΔP`. Reference numbers (`PD_PUMP_RESEARCH_REVIEW.md`
section 2 of the earlier batch, CoolProp, c 0.1): water 300 K 1 → 21 MPa η_v 0.999; CO2 310 K 10 → 30 MPa 0.983, work
ratio 0.912; CO2 310 K 8.5 → 25 MPa 0.966; nitrogen 300 K 15 → 30 MPa 0.955; CO2 gas 300 K 1 → 3 MPa 0.862, work
ratio 0.619; nitrogen gas 0.1 → 0.4 MPa 0.831.

**What the code does.** `PlungerPump` delegates to `PositiveDisplacement` (slip and relief only): η_v is 0.998 on any
fluid, the work is `Q ΔP`. The law's `k` argument is 1.4 for a liquid mover and unused.

**The law (one parameter, no new model).**
1. The suction's isentropic exponent `n = K_s / P`, `K_s = −v / (∂v/∂P)_s`, `(∂v/∂P)_s = dvdp + T dvdt² / c_p`, on the
   phase the pump draws: `HydrocarbonModel.Phase` already carries `molarVolume`, `volumePressureDerivative`,
   `volumeTemperatureDerivative` and the real `heatCapacity` (see `isothermalCompressibility()`). Add
   `NetworkStreams.isentropicExponent(State)` (forwarded by `FluidThermodynamics`): the liquid slot's phase where the
   state holds liquid (the aqueous one where only that), else the one fluid slot's phase, else the vapour's. Floor it
   at 1.05; a non-finite value gives 1.4. An ideal gas gives γ; water at 300 K gives about 2,200 at 1 MPa.
2. `PassiveStepSolver.suctionKappa`: for a `FlowControl.Machine` whose law is a `PlungerPump`, return that exponent
   instead of 1.4; `kappa()` must not take its early `liquid()` return for it. It stays one value per step (the cache
   by state identity), as a compressor's k is. `suctionKappa` is the one function the solve, a driver's charge and its
   speed control read: keep it so.
3. In `PlungerPump`, with `p` the suction pressure, `ΔP` the rise, `n = k`:
   `r(ΔP) = ((p + ΔP) / p)^(1/n)` (the pressure sum floored at a small positive value);
   flow at a rise: `Q(ΔP) = capacity · (share(ΔP / reliefRise) − CLEARANCE · (r − 1))`, `share` the existing smooth
   minimum of the slip and relief lines; `residual` stays the flow form, `reliefRise · (flow − Q(head)) / capacity`.
   `rise(flow)` is the inverse: `Q` falls strictly with `ΔP`; solve by a safeguarded Newton from
   `PositiveDisplacement.rise` (an upper bound) inside `[lower, that]`, a fixed cap of 60 steps, to 1e-13 relative; no
   exception on a miss, the bracket's midpoint. `riseSlope = 1 / (dQ/dΔP)`,
   `dQ/dΔP = capacity · (share'(v) / reliefRise − CLEARANCE · r / (n (p + ΔP)))`; give `PositiveDisplacement` a
   package-private share slope for it.
4. `fluidPower = (massFlow / density) · w_s`, `w_s = p · n/(n − 1) · (((p + ΔP)/p)^((n − 1)/n) − 1)`: the isentropic
   work per suction volume. It tends to `ΔP` for large `n` (use `expm1`/`log1p` forms so water gives `ΔP` to 1e-12).
5. `shaftPower = displacement · speed · max(0, 1 − CLEARANCE (r − 1)) · w_s / MECHANICAL_EFFICIENCY` at the law's rise
   at `flow`. `maximumShaftPower()` keeps `displacement · reliefRise / 0.88` (the liquid bound, a motor's sizing duty).
6. Errors to state in the review, against the table above: at most 2.5 % of the delivery low (CO2 310 K 8.5 → 25 MPa:
   0.944 for 0.966), below 0.5 % elsewhere; the gas rows within 0.5 %.
7. Consequences to check and state: the rise at no flow now depends on the suction pressure (a gas at 0.1 MPa stops
   delivering near `(1 + 1/c)^n`, about 2.8 MPa for nitrogen): `riseLimit` already passes the pressure. A liquid's
   moves by under 1e-4 relative.

**Ruled out.** A Murnaghan law with a second parameter (needs a law field per solve or a second root); the real-gas
isentrope by integration; a clearance on the reciprocating compressor and the set-flow movers (`PositiveDisplacement`
itself does not change its results); a new facts or checkpoint field (the record keeps its three fields).

**Simulator (item 4).** `MoverUnit` computes a pump as centrifugal. Where the unit's sized type is the plunger pump
(the player's type, else `PumpSizing.type(load)`: resolve it the way the Sizing tab does), the run reads
`PlungerPump.MECHANICAL_EFFICIENCY`, the ideal work `Q · w_s` on the inlet state's exponent, and the shaft over 0.88;
its `efficiency` reading is 0.88. `PumpSizing.plungerDuty` reads the same work, and the `PUMP_RATED` row reads the flow
against the model's displacement times η_v. No bundled example has a plunger pump: no sample result may move (prove it
from the stored results, do not re-run them).

**Pitfalls.** The row's suction pressure varies inside the Newton solve and the block sweep re-assembles it: `Q` must
be smooth in `p` and `ΔP`, finite for `ΔP < 0` and at the relief rise. `PositiveDisplacement` throws on speed 0:
`PlungerPump.row` already floors the displacement. The plunger's `equals` and the facts writer must not see a new
field. `InlineMovers.ratedShaftPower` passes pressure 1e5 and k 1.4: finite, and unused for a plunger.

**Tests.** New cases in the plunger law's test and `PlungerPumpMoverTest`: the six table rows (η_v and work ratio within
the stated errors, the exponent from the model's own state); `rise(Q(ΔP)) = ΔP` round trip on a grid of 3 pressures ×
3 exponents × 20 rises to 1e-10; the slope against a central difference; water moves under 1e-4. Then
`affected.mjs select` for the changed files (the plunger's, `MoverUnit`'s, `PumpSizing`'s tests among them). Each run
proves: the law's numbers; the network row still converges on the S8 cases; the simulator's readings.

**Done.** The tests pass; every moved printed or asserted value of the S8 tests is listed old → new with the η_v it
follows from; `P1_PLUNGER_REVIEW.md`; left for the lane: the dense GameTest's plunger case and the campaign.

## 4. P2: one dense fluid in the readings (items 5 to 12, 16)

Files: `runtime/fluid/FluidView.java`, `ViewBuilder.java`, `Displays.java`, `FluidWorldAuthority.releaseContents`,
`runtime/control/**` and `runtime/instrument/**` where they read a level, `network/FluidNetwork.java`,
`NetworkProtocol.java`, `client/**`, `science/flowsheet/sizing/Service.java`, `en_us.json`,
`documentation/reference/FORMATS.md`, their tests and renders. Not yours: `science/machine/**`,
`science/fluid/network/**`, `MoverUnit.java`, `PumpSizing.java`, `science/column/**`, sounds. Before any GUI work read
`.claude/skills/gui-creation/SKILL.md`. No dev client: judge by gates and the software renders.

1. **The wire field (item 6).** `FluidView.State` gains one double, `dense`: `w` of the server's own state where
   `state.dense()`, else NaN. It is written wherever a `FluidView.State` goes to a client; the protocol becomes
   `icheme-4` (the main thread reserves the number on `main` before your first code commit; wait for its word). The
   device screen reads the label from it: "Dense" where `dense > 0`. Then delete what it replaces: the client's model
   build and root (`DenseLabel.model`, `DenseLabel.row(model, axis, …)`, `FluidDeviceScreen.denseRow`'s cache, their
   tests); the simulator keeps `DenseLabel.row(state)`. If a `FluidView.State` is also stored in a state format, bump
   that format (state formats change freely) and say so; a facts format must not change.
2. **Level (item 8).** The volume a level instrument reads becomes one function on the server's state: both liquids
   and the solids, plus `w` times the vapour slot's volume where the state is dense. Then a dense fluid reads 0 at
   δ ≤ 0.5, rises with the density and reads full from δ 1, where the liquid slot takes over. Every reader of a level
   uses it: `FluidView.Tank.level`, the `LEVEL` sensor (`ControlLaw` line 243 and its inventory sample), the level
   gauge. A level controller on a dense vessel then holds a density, which is monotone in the inventory. The tank
   page's level line keeps "No interface". The engine's own heads and ports (`PortStreams`) are not changed.
3. **Sight glass (item 7).** `Displays`' sight glass state reads the moved slot volumes. For a stream whose upstream
   node's state is dense, the liquid share shown is `w`.
4. **Release (item 16).** `releaseContents`: where the vessel's state is dense, each compound's amount is split
   `gas = held · (1 − w)`, `liquid = held · w`, whatever slot holds it; `Releases.contents` then sends the liquid by
   its volatile share as before. Carbon dioxide (volatile share 1) reaches the air whole and the burst reads gas; a
   dense heavy oil at 800 K stays mostly on the ground. A pipe takes the state of the node its moved stream left
   (`forward` from the first end, `reverse` from the second); pool its three phase amounts and split them the same way.
5. **Line service (item 9).** `Service.of`: a state with `DenseLabel.dense(state)` (δ above 0.5) skips the vapour
   tests and takes the liquid branch (`DRAW`, `SUCTION_COLD`, `WATER` or `LIQUID`): dense-phase lines are sized as
   liquid lines. The class then changes where the word does, at δ 0.5.
6. **Pump page (item 10).** A centrifugal pump on a two-phase supply (the view's α above 0, a curve present) shows a
   "Head share" meter beside "Gas": `100 · GasEntrainment.headShare(α, p)`, as `DeviceMeters.suction()` already reads
   it for a dense supply.
7. **Hover (item 11).** The limits table gets a per-row hover; the "Suction density" row's advice is its first user.
8. **Colour (item 12).** The "Dense" share's colour is the vapour colour blended to the liquid colour by `dense`
   (`w`): one colour on both sides of δ 1, the gas's at 0.
9. **Model entry (item 5).** The Sizing tab's Model field shows the model's capacity (m³/h, or the kind's own unit)
   while the steppers step the model number, for the plunger pump, the vacuum pump and the prime mover (the shared
   `dimensions` path).

**Pitfalls.** `w` is NaN for a state that is not one fluid: test `dense()` first. The view's `State` has no traits:
compute on the server. A replay is a read of cached data: add no solve to a menu open. The level change moves a
controller's reading only for a dense vessel: prove ordinary vessels equal from the stored results.

**Tests.** `DenseLabelTest` (reduced to the simulator's rule), `DeviceMetersTest`, `FluidGuiConsistencyTest`,
`SizingPageTest`, `PageFilmsTest`, the control and release tests `affected.mjs select` names; new cases: level at
δ 0.4, 0.6, 0.8, 1.0005, 1.6 (0, 0.104, 0.648, 1, 1 of full); release of dense CO2 (gas) and of a dense heavy cut;
service at δ 0.4 and 0.6; the wire round trip with NaN. Renders of the pump page and the tank page in
`tmp/followups-p2/renders/`.

**Done.** The tests pass; `P2_READINGS_REVIEW.md` with the before/after table for carbon dioxide at 310 K (as S5's
section 4); the `FORMATS.md` row for `icheme-4`.

## 5. P3: the column's feed flash (items 14, 15)

Files: `science/column/v3/V3FeedFlash.java` and what it calls in that package, their tests, the column chapter of the
code guide. Not yours: everything else. Read `documentation/2026-11-15-supercritical-fluids/COLUMN_FEED_REVIEW.md`
whole first: its grids, its harness (`tools/supercritical-probes/`) and its frozen reference `V3FeedFlashReference`.

**Expected.** A feed is one phase exactly where it is stable against a second phase. Where it is unstable it splits,
and the split is the one the equilibrium engine finds on the same PR78 kernel and derived pairs.

**What the code does.** It brackets with Wilson K-values and reads a collapse of `ln K` as one phase. 102 one-phase
feeds are refused (101 slow collapses, one diverging vapour). 181 feeds that really split are answered as one phase by
the Wilson endpoint test at iteration 0. 721 real splits stall and are refused.

**The change.** Classification by the stability test, not by the iterates' path:
- F1 (O1 B and O2): `TangentPlaneStability` on the column's kernel at the two places the flash gives up or answers
  without iterating: a lost bracket or exhausted iterations, and the Wilson endpoint. Stable: one phase by the label
  rule already there. Unstable at the endpoint: into the two-phase iteration. Unstable and stalled: refused, as now.
- F2: F1, and an unstable feed starts or restarts its iteration from the stability test's stationary point
  (`K_i = W_i / z_i`), once.
- F3: F2, and an unstable feed whose iteration still fails takes the engine's own split (`FluidTpEquilibrium` on the
  same kernel): its vapour fraction and compositions, the enthalpies on the column's own roots.
Measure the three on the review's grids (10,602 states) and choose one. Criteria in order: no feed the engine splits
is answered as one phase; no answered feed of the frozen reference moves except the 181; the fewest refusals; the
counters (stability tests and engine calls per feed, per column solve). Prefer F3 if it meets the first two; say why
if not. At most three forms; no fourth.

**Ruled out.** More iterations alone (the stalls sit at `|ln K|` 0.1 or more and do not fall); a new flash; a change
of the label rule or of D2 (a tray above its critical point stays a typed refusal).

**Pitfalls.** The stability test at the Wilson endpoint runs for every subcooled or superheated feed: count it. The
engine's water handling and the column's differ (free water on trays is a warning, owner rule): compare on dry and wet
grid states separately. No native twin exists for the feed flash (review point 8): check that this still holds.

**Tests.** `ColumnFeedOnePhaseOracleTest`, `V3PengRobinsonThermoTest`, `V3ColumnFeedAndDrawLimitsTest`, the frozen
reference's test, and what `affected.mjs select` names in `columnGates`. New: the 181 states (now split or refused,
never one phase), the 102 (answered), a sample of the 721.

**Done.** `P3_COLUMN_FEED_REVIEW.md`: the three forms' table (answered, refused, wrong one-phase, counters), the form
chosen, the 181 states' old and new answers by class, the bound; left for the lane: `columnGates` and the column
examples.

## 6. P4: the plunger pump's sound (item 3)

Read `.claude/skills/models-and-sfx/SKILL.md` first: it holds the rules, the owner's taste and the tools. The plunger
pump plays the compressor's pulsing loop at its pitch law. Synthesise its own loop with the skill's tools: a triplex
at 300 rpm (5 turns a second, 15 plunger strokes a second), valve knocks over a low crank rumble, seamless, within the
skill's level and length rules. Register it, give the plunger its own entry where the mover sounds pick a loop, keep
the pitch law's clamp. Files: the sound assets, `sounds.json`, the mover sound class and its test, the skill's sound
table if it lists loops. No `en_us.json` change unless a subtitle key is required (then one key, and say so). At most
three candidates; choose one; keep the others' recipes in `tools/`. Done: the sound tests `affected.mjs select` names
pass; `P4_SOUND_REVIEW.md` with the recipe and the levels; the owner hears it in the in-game check.

## 7. Order, threads, merge

- Wave 1: P1 and P3 in parallel (disjoint files), each a sub-thread in its own worktree, Opus high.
- Wave 2: P2 (Opus high) and P4 (Opus medium), after the `icheme-4` reservation.
- The main thread merges the package branches into the batch branch, checks each diff, then asks the owner for merge
  preparation, one final lane and the version (a patch or minor bump, the owner's choice).
