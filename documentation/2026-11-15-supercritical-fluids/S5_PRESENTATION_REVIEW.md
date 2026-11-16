# S5: what the player reads on a dense fluid

Batch 2026-11-15-supercritical-fluids, stage S5, sub-thread, 2026-11-16. Owner decisions of 2026-11-16 ("Accept all"):
(1) one dense fluid above reduced density δ 0.5 reads "Dense fluid" in either slot, "Vapor" at or below; (2) a pump on one
dense fluid makes no churn and gets no `GAS` word, and its page shows the head share; (3) the simulator's pump sheet gets a
row "suction lighter than a pump's range" below δ 0.86. The main thread chose, for the label's source, option A: the
client evaluates it on its own catalog (no wire field); and form (ii) for the pump page.

## Summary

**Expected.** One fluid on a supercritical isotherm has no interface and no bubbles. Its density runs continuously from a
gas's to a liquid's. Nothing physical happens at δ 1, where the network's slot rule moves it between the vapour slot and
the liquid slot. So no word and no reading should change there. At or below δ 0.5 the fluid is a gas and reads as one:
nitrogen at 300 K and 1 bar is at δ 0.004.

**What the code did.** Every reader named the slot. A tank of carbon dioxide at 310 K read "Vapor" up to δ 1 and
"Organic" from δ 1. A pump on it at δ 0.6 showed a gas share of 89.6 %, churned at 255/255 and said `GAS`, all from the
density's share `1 − w_pump`. The simulator called the stream "gas", then "liquid", and its vapour share went from 100 to
0 % across δ 1.

**What S5 does.**
- **Server, observation only.** A liquid pump's gas ratio reads bubbles: `SolveConstants.bubbleShares`, from
  `InletPhase.Supply.bubbleVolumeShare`. For one dense fluid above δ 0.5 that share is NaN, so there is no churn and no
  `GAS` word. The head share, the gas lock and the page's α are the density's, unchanged. No solved value can move: the
  array is read only by `StepObservations.gasRatio`.
- **Client, the device screen.** The view carries no phase traits, and S5 adds no wire field. So the screen evaluates
  the shown contents' one cubic root on the client's own catalog: `DenseLabel.row`, from `FluidDeviceScreen.denseRow`.
  It does this once per published view and stream, and keeps the result. Above δ 0.5:
  - the tab and legend of the row that holds the fluid read "Dense fluid", "Dense" short, "Dense" on the tab;
  - a tank's level line reads "No interface";
  - a pump's gas meter reads "Head share", `GasEntrainment.headShare(α, p)` on the server's α at the suction pressure.
- **Simulator.**
  - A stream of one dense fluid above δ 0.5 reads "dense fluid".
  - Its phase share and composition tab read "Dense", and its vapour share "—".
  - A pump's status reads "Solved. Dense fluid at the inlet.".
  - The pump sheet has a SHOULD row "Suction density", at least 0.86 in reduced density. It is read only on a dense
    suction.

No payload layout changed. Ordinary gases and two-phase nodes read as before (tests). The level transmitter, the sight
glass and the simulator's line service class still follow the slot (owner items).

## 1. Survey: where a player reads a phase

| Where | File, member | Reads | Before S5 on dense CO2 (310 K) | S5 |
|---|---|---|---|---|
| Device screen, phase tabs | `FluidDeviceScreen.buildContents`, `DeviceColumn.phaseKey` | the row's key | "Gas" to δ 1, "Org" from δ 1 | "Dense" on the holding row above δ 0.5 |
| Device screen, phase legend | `DeviceColumn.phases` (`phase_short.*`) | the slot's name | "Vapor", then "Organic" | "Dense" |
| Tank level line | `DeviceColumn.rows`, `DeviceMeters.level` (`volume/capacity`) | % full | "100 % full" in both slots (one fluid fills the vessel) | "No interface · V of C L" above δ 0.5 |
| Pump page gas meter | `DeviceMeters.suction` ← `FluidView.Pump.gasShare` ← `PipeObservation.vapourShare` | α | 89.6 % at δ 0.6, 35.2 % at 0.8, 0 from 1 | "Head share" 59.6 % at 0.6, 98.6 % at 0.8, 100 % from 1 |
| Pump churn | `MachineHealth` (`GasEntrainment.churn(row.gasRatio())`) | r = α/α_T | 255/255 at δ 0.6, 159 at 0.73 | 0 above δ 0.5 |
| Pump `GAS` word | `MachineHealth.status` (r > 0.5) | r | yes to about δ 0.9 | none above δ 0.5 |
| Pump dry-running fault | `StepObservations.dry` (α > α_T at no flow) | α | the density's | unchanged (a pump losing its grip) |
| Dry screw liquid share, LIQUID word | `MachineHealth` (`1 − row.vapourShare()`) | α | full LIQUID fault | unchanged, report only: a vacuum pump never meets a fluid above δ 0.5 at its pressures |
| Sight glass | `PipePresentation.sightGlass` (moved liquid volumes) | the slots' volumes | clear in the vapour slot, coloured in the liquid slot | unchanged (owner item 2) |
| Level transmitter (LT), controllers | `BuiltInInputs` LEVEL, `FluidView.Tank.level` (condensed height) | the slots' volumes | 0 in the vapour slot, full in the liquid slot | unchanged: the engine's (owner item 3) |
| Simulator stream words | `StreamDoors.phaseName`, `door.status.feed` | gas share | "gas", then "liquid" | "dense fluid" |
| Simulator phase shares | `Door.phases` (`flowsheet.phase.*`) | per slot | "Gas" 100 %, then "Organic" 100 % | "Dense" on the holding share, slot colour kept |
| Simulator composition tabs | `StreamDoors.phaseKeys` | names | "Gas", "Organic" | "Dense" on the holding tab |
| Simulator vapour share | `StreamProps.vapour` (results, selection, unit glass, CSV) | mole % gas | 100 then 0 | "—" (NaN) above δ 0.5 |
| Simulator pump status | `UnitDoors` (`door.status.mover`) | words, vapour % | "Gas at the inlet, 100 % vapour" | "Solved. Dense fluid at the inlet." |
| Simulator drum status | `UnitDoors.held` (`door.status.phases`) | slot names | "gas" or "organic" | "dense fluid" |
| Pump and compressor refusals | `MoverUnit` (`pump.phase`, `compressor.phase`), `VacuumPumpUnit` | `InletPhase.bulk` → `refuses` | a dry suction (never, held share ∞) or solids | cannot fire on a fluid-only dense stream: checked, no change |
| Simulator sizing rows | `Limit`, `PumpSizing`, `CompressorSizing`, `Service.of` | see S4 section 6 | `Service.of`'s gas or liquid line class by the slot | `PUMP_DENSITY` added; the line class unchanged (owner item 4) |

## 2. The rule and how the client evaluates it

`DenseLabel.dense(state)`: `state.dense() && FluidThermodynamics.liquidLikeWeight(state) > 0`, which holds exactly above
δ 0.5. The simulator's streams carry the model's own state, so they read it directly (`DenseLabel.row(state)`).

The device screen's view (`FluidView.State`) is P, T, volumes and phase moles: no traits. So the client does this:
- **Model.** `DenseLabel.model(MaterialRuntime.active())` builds the network model once per catalog and keeps it. One
  build per client session, again only when a catalog is published anew (a reload in single player).
- **Evaluation.** `DenseLabel.row(model, axis, T, P, volumes, moles)` takes the one row that holds fluid. It maps the
  row's amounts by compound id onto the model's axis, then evaluates the untranslated cubic's single root
  (`HydrocarbonModel.phase`, its `PhaseTraits`). One dense fluid reads "Dense" when its isotherm is supercritical and its
  reduced density is above 0.5. This is one cubic root, no flash and no solver.
- **Caching.** The screen calls it when a new view object arrives or another stream is selected, and keeps the row until
  then (`denseView`, `denseStream`). The cost is one root per published snapshot, about every 5 s, plus one per stream
  switch. Models built: 1 per catalog (`DenseLabel.models()`, `DenseLabelTest.theModelIsBuiltOncePerCatalog`).
- **Fallback.** These keep today's label and never fail:
  - a compound of the row the client's model lacks;
  - no model;
  - a refused root;
  - a non-finite temperature or pressure;
  - two or more rows holding fluid.

  Test: `DenseLabelTest.aViewTheClientCannotEvaluateKeepsTodaysLabel`.
- **Agreement.** The client's row equals the model state's at δ 0.4, 0.5, 0.8, 1.0005 and 1.6, and for nitrogen at
  300 K and 1 bar, a two-phase node and a subcritical liquid (`DenseLabelTest.theClientReadsWhatTheModelsStateReads`).
- **Hysteresis.** None is added: the label is a function of the snapshot. How often it would flip between snapshots near
  δ 0.5 on the dense GameTest loop's states was not read. The loop's states stand at δ 0.679 and 1.794 (S4 review), away
  from 0.5.

## 3. The pump (form ii)

- **Server.** `MoverSuction.readSuctions` writes `bubbleShares[edge] = supply.bubbleVolumeShare()` for every liquid
  mover. That is `vapourVolumeShare`, or NaN for a dense supply whose share is below 1, which is δ above 0.5 on the pump's
  weight. `StepObservations.gasRatio` divides it by the onset, so the ratio is NaN, `GasEntrainment.churn(NaN)` is 0, and
  `status` gives no `GAS`. The row's `vapourShare` (α, which the page shows) and `dry` keep `vapourShares`.
  `FluidView.Pump.gasShare` still carries α.
- **Client.** Where the screen's dense row is set and α is present, the meter is labelled "Head share" and reads
  `100 · GasEntrainment.headShare(α, p)`. `p` is the pump node's `FluidView.State.pressure()`, the page's own "Suction
  pressure" meter. Where the client cannot evaluate the rule, the row stays "Gas" with α.
- **Form chosen.** "Head share", not "Head": the pump page's Readings group already has a "Head" meter in metres.
- **Not added.** A head-share reading for a pump on a two-phase supply (owner item 5).

| CO2 310 K, δ | α (server) | churn, before → after | `GAS`, before → after | meter, before → after |
|---|---|---|---|---|
| 0.4 | 1 | as before (gas) | as before | "Gas" 100.0 → "Gas" 100.0 |
| 0.6 | 0.896 | 255 → 0 | yes → no | "Gas" 89.6 → "Head share" 59.6 |
| 0.8 | 0.352 | (r about 0.6) → 0 | yes → no | "Gas" 35.2 → "Head share" 98.6 |
| 1.0005, 1.6 | 0 | 0 → 0 | no → no | "Gas" 0.0 → "Head share" 100.0 |

## 4. What each screen reads, before and after (carbon dioxide at 310 K)

| δ (slot) | Device tab and legend | Level line | Simulator words / shares | Vapour % |
|---|---|---|---|---|
| 0.4 (vapour) | Gas / Vapor → same | 100 % full → same | gas / Gas → same | 100 → 100 |
| 0.8 (vapour) | Gas / Vapor → Dense / Dense | 100 % full → No interface | gas / Gas → dense fluid / Dense | 100 → — |
| 1.0005 (liquid) | Org / Organic → Dense / Dense | 100 % full → No interface | liquid / Organic → dense fluid / Dense | 0 → — |
| 1.6 (liquid) | Org / Organic → Dense / Dense | 100 % full → No interface | liquid / Organic → dense fluid / Dense | 0 → — |

Nitrogen at 300 K and 1 bar, and every node of two phases, read as before.

## 5. The simulator's pump sheet

`Limit.PUMP_DENSITY` is SHOULD, at least 0.86, QUIET, source "[BERG]": Bergamini et al. 2011, in
[DENSE_PUMP_RESEARCH_REVIEW.md](DENSE_PUMP_RESEARCH_REVIEW.md). `PumpSizing.Load.reducedDensity` is the suction's δ where
the stream is one dense fluid (`State.dense()`), NaN otherwise, so every other pump's sheet is unchanged. On the render:
- δ 0.8 reads 0.80, yellow;
- δ 1.2 reads 1.20, met.

The limits table on the glass has no per-row hover, so the advice to compress and cool first, or to use a compressor,
stands in the row's javadoc only (owner item 6). Test: `UnitSizingTest.aPumpOnADenseSuctionReadsItsDensity`.

## 6. Renders

Software renders in `tmp/supercritical-s5/renders/`. Images do not go into `documentation/`. Their place is the main
checkout's `research/2026-11-15-supercritical-fluids/renders-s5/`, which this worktree's hook refuses.
- `dense-tank-0_4.png`, `dense-tank-0_8.png`, `dense-tank-1_0005.png`, `dense-tank-1_6.png`: a CO2 tank's page, both
  themes, opened and scrolled (`GuiPrototypes -Donly=dense-`, the shipped `DeviceMeters` on views from the model's flash,
  with the client's label).
- `dense-pump-0_6.png`, `dense-pump-0_8.png`: a casing pump's Suction and Health groups.
- `dense-0_8-doors.png`, `dense-1_2-doors.png`, each with a `-dark` twin: the simulator's feed door and pump door.
- `dense-0_8-sizing.png`, `dense-1_2-sizing.png`, each with a `-dark` twin: the Sizing tab with the pump selected
  (`SimulatorRender -Donly=dense`).

Two drifts in the render tools were fixed so they run again: `V3ColumnPresets.input` in `SimulatorRender`, and the
`science.plant.column` imports in `OperationPrototypes`.

## 7. Owner items

1. **The client's catalog.** On a dedicated server whose datapack overrides a compound of the island, the label and the
   pump's head share follow the client's data. They can then differ near δ 0.5 or near the critical temperature. Single
   player and servers without a material datapack are exact. The remedy is one server field, the one fluid's δ, when a
   protocol number opens (option C). Not done: `icheme-2` is reserved by another batch.
2. **The sight glass** is clear while a dense fluid sits in the vapour slot and coloured in the liquid slot: it flips at
   δ 1. It reads moved slot volumes with no traits. A presentation fix needs the model on the server's sight-glass path.
3. **The level transmitter** (LT, `Tank.level`: the condensed height) reads 0 in the vapour slot and full in the liquid
   slot. A controller uses it, so it is the engine's, not S5's.
4. **The simulator's line service** (`Service.of`: gas or liquid by the slot's vapour mass share) still picks the gas or
   the liquid speed rows across δ 1 (S4 kept it).
5. **A head share for a pump on a two-phase supply.** The same arithmetic, `headShare(α, p)`, would show it. Not added.
6. **The density row's advice** has no hover on the glass: the limits table has no per-row hover.
7. **The share's colour** keeps its slot's colour, so the "Dense" share is white in the vapour slot and amber in the
   liquid slot: the colour still changes at δ 1. No fifth colour was added.
8. **A dense mixture's tank** whose slot rule places it in the aqueous slot (water above 647 K) would read "Dense" there
   too. Not met in S5's cases.

## 8. Tests and runs

New and changed tests:
- `DenseLabelTest`: the rule on the client against the model's state, the fallback, the model built once, the column's
  words.
- `DenseWordsTest`: the simulator's words, shares and vapour share at δ 0.4 to 1.6, nitrogen, two phases.
- `DeviceMetersTest.denseContentsNameTheirRowLevelAndPumpHead`.
- `UnitSizingTest.aPumpOnADenseSuctionReadsItsDensity`.
- `DenseMachineSharesTest.aPumpOnADenseSupplyReadsNoBubbles`: δ 0.6 no bubbles, δ 0.4 gas as before, a two-phase supply
  as before.

Runs, once each, on the final code, selected by `node tools/merge-lane/affected.mjs select --since 8a870f228`, 4 workers:

| Group | Classes | Tests | Failed |
|---|---|---|---|
| `test` (the selected classes) | 468 | 2,874 | 6, none from S5 (below) |
| `columnGates` | 62 | 379 | 0 |
| `equipmentGates` | 26 | 244 | 0 |
| `holdGates` | 14 | 136 | 0 |
| `fluidSolverRegression` (chain-100) | 1 | 1 | 0; max deviation 0 in moles, temperature, phase fraction and flow |
| Dense GameTests (`icheme_dense_test`, run id `s5-dense1`) | | 3 | 0 |

The six failures in `test`, diagnosed from their output and the code, not run again:
- `LanguageFilesTest` (1): the new `en_us.json` keys lack their translations. Expected until the translation commit.
- `FormatsTableTest` (2): two `FORMATS.md` rows reserved by batch 2026-11-15-pollution-system (fluid facts schema 26,
  protocol `icheme-2`, commits `2be307e58` and `b41f7f9c7`, from main's merge) name constants this branch lacks.
- `EngineDigestTest` (1, request 313, `tjl20_methane_nitrogen` TP at 350 K and 12 MPa), `FluidTpEquilibriumTest` (2) and
  `FluidTpEquilibriumPhUvTest` (1): a grade expected `EXTRAPOLATED` reads `ESTIMATED_DECLARED_ERROR`. The states lie
  inside the pressure ranges S3 raised (`ceefe0904`); the tests were last green in S1, before S3, and S4b's selection did
  not reach them. S5 changed no thermo, material or data file. Their pins need S3's ranges: a follow-up
  for the batch, not S5.
