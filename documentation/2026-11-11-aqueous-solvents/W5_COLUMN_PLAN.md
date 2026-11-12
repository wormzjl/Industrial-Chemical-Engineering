# W5: the V3 column on the one equation of state, water on its axis

Batch 2026-11-11-aqueous-solvents, work package W5 of [AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md) (M7 and
row W5). It follows [W4_STILL_PLAN.md](W4_STILL_PLAN.md). Written 2026-11-12 at 0f0577aff (owner: "Write W5
design"). A design, not built. Section 5 holds the owner's choices, section 9 the delegation request.

## 1. Expected, in numbers

Ethanol and water at 1 atm on the batch's model (measured on the engine in W4): azeotrope 0.8965 ethanol; a liquid
of 0.0332 boils at 92.574 C under a vapour of 0.2567; the still's own column gives 0.7255 with 4 trays and 0.7995
with 8 trays at a reflux ratio of 3.

- E1, a rectifier. Feed 100 mol/s, 0.10 ethanol, liquid at its bubble point (about 86.5 C; measured 86.7 C at
  0.0966 under a vapour of 0.4375). 10 trays, feed on tray 8, reflux ratio 3, all condensed at 70 C, reboiler
  1.77 MW.
  - Boil-up 1.77 MW / 40.7 kJ/mol = 43.5 mol/s. The reflux returns 8 K cold and condenses 2.5 % of itself more, so
    the top vapour is 4.075 D: distillate about 10.7 mol/s.
  - Distillate 0.80 to 0.86 ethanol, top tray 78.2 to 78.6 C; bottoms about 89 mol/s with about 0.012 ethanol, near
    96.5 C. The distillate within 0.01 of a McCabe-Thiele construction on the same K values.
- E2, the limit. 30 trays, reflux ratio 10: distillate between 0.88 and 0.8965; never past the azeotrope.
- E3, a stripper. The same feed on tray 1 of 8 trays, reflux ratio 0.1, reboiler 1.22 MW (a boil-up of 30 mol/s).
  Water's stripping factor for ethanol is K V / L: about 1.3 at the feed (K 4.4) and 3.6 in the dilute bottom
  (K about 12). Bottoms ethanol about 1e-4, under 1e-3.
- Crude columns. The same pairs as the network move the light ends: a dry crude's bubble pressure at 25 C went
  from 115.0 to 129.6 kPa in the network (W3B_NETWORK_PLAN.md section 33, G3), H-101's duty by -3.9 kW of 73 MW
  (section 47). Expected on `medium_crude`: more overhead vapour (an estimate, 5 to 15 %), tray temperatures within
  1 K, duties within 1 %.

## 2. What the code does

- Two sets of pairs. The column's kernel is `V3PengRobinsonSession.kernelFor`: a classical `PengRobinsonKernel` on
  the package's authored `interactions()`. The network's model is `CubicPhaseEvaluator.derived`
  (`DerivedPairs.derive`): authored pairs where a library gives them, derived ones elsewhere. A sheet flashes a
  column's feed on one and solves the column on the other.
- Water is outside the column's axis. Hydrocarbons are component flows; water is stripping steam
  (`V3SteamFeedSpec`), free water on wet trays (`V3WetTraySet`, `V3FreeWaterContinuation`), a condenser water
  regime and `V3WaterProperties`. The free water is pure.
- The callers map water. `ColumnBlockSolve.run` (the sheet): the feed's water becomes a steam feed at the feed
  stage; an aqueous liquid in the feed is refused (`column.liquid_water`). `ColumnSolve` (the running column, its
  input near lines 164 to 212): feed water superheated at the feed tray is a steam feed, else "it follows the
  distillate".
- The Water slot exists already. A sheet's and an island's catalog is the water view (`MaterialCatalog.withWater`):
  the package names Water last, and V3 carries it on its axis with a flow of zero.
- So an ethanol and water feed is solved wrong: ethanol on the axis with every pair zero, water as steam and pure
  free water. No azeotrope, no ethanol in the water.
- The learned start (`V3NeuralRegistry.bind`) needs a model whose compounds cover the feed. None holds ethanol, so
  such a column starts from `V3ColumnInitializer`.

## 3. The design

- **D1. One kernel.** `V3PengRobinsonSession.kernelFor` takes the kernel of
  `DerivedPairs.derive(catalog, packageId, the package's components)` at the numerical box, and the critical
  constants of that result. The session's arithmetic stays: the ideal-gas polynomial plus the kernel's residual,
  the kernel's own derivatives. A list without an activity pair runs the classical branch, at today's cost.
  - `datasetRevision()` stays the package's records, so the bundled learned models still bind; every formulation
    label of `V3ColumnCalculator.formulationRevision` gains one suffix for the derived pairs.
  - `binaryInteractions()` keeps the authored matrix for its readers (`TrainedPhysics.matches`, the lens).
- **D2. V3 has no water mode.** Water is on the axis where the feed's Water entry is above zero. Such a request is
  an ordinary column of its list: vapour and one liquid on the derived kernel, water's pairs the Huron-Vidal ones.
  It has no steam rows (refused: water would be modelled twice), no wet tray, no condenser water regime. A request
  with Water at zero is solved as today.
- **D3. The callers decide, by one shared rule** (one function, read by `ColumnBlockSolve.run` and
  `ColumnSolve`): water goes on the axis where the feed carries a water-miscible organic above a share of its
  moles (section 5, choice 1); otherwise to steam, as today.
  - On the axis: the feed is every amount, water included; an aqueous liquid in the feed is taken; a steam row
    with a flow is refused with its reason (section 5, choice 2).
  - Off the axis: as today, on the list without Water (`MaterialCatalog.restrict`), so the kernel stays on its
    classical branch. Water dissolved in the oil keeps joining the steam: V3's free water is immiscible by
    construction. The comment in `ColumnBlockSolve.run` that W5 removes this is corrected.
- **D4. Water's enthalpy on the axis is the cubic's alone** (section 5, choice 3). Equilibrium is the cubic's in
  the network too, so the K values are the network's exactly. The network adds water's share to enthalpy; without
  it the heat of vaporization is 0.23 % off at 25 C, 0.60 % at 100 C, 2.2 % at 200 C. The ports carry moles at a
  temperature, so nothing is booked twice.
- **D5. Two liquids warn.** After an accepted solve with water on the axis, the caller splits each tray liquid
  and the distillate once with the engine (`FluidThermodynamics.flashTP`), only where the liquid holds water and a
  compound without a polar group above 1e-6. A liquid that settles is a notice naming the trays
  (`column.two_liquids`), not a failure: the rule of the V3 column's free water and of the still's trays. An
  ethanol and water column makes no such split.
- **D6. No start network.** Such lists start from the classical seed; nothing is trained in this batch.
- **D7. Hydraulics and sizing** read water and the solvent like any compound (the Rackett scaling of the standard
  density, Kay's rule). The tray correlations stay the hydrocarbon ones: a known approximation, recorded.
- **D8. Saves and the wire.** No facts field changes: the axis is the catalog's and already holds Water. If any
  facts format turns out to carry what this changes, the build stops and reports.
- **D9. Cost.** Kernel evaluations and Newton iterations of `medium_crude` before and after D1; a rise above 2 %
  is reported. A sour list (hydrogen sulfide, sulfur dioxide, a mercaptan) takes the activity branch today, 5.4
  times the kernel time in the network (README, open owner item): one sour column is measured, and a rise above
  1.5 times stops the build for that owner item.

## 4. What moves

- Every V3 reference with a gas beside a crude cut, once (D1): the column tests' pinned numbers, the sizing
  samples, the sheet examples (`crude_unit` is already waiting for its re-record). Each is re-pinned with its
  cause.
- The learned starts are measured against the new pairs on the bundled presets: accepted or not, Newton
  iterations, beside the classical start. A model is retrained only where its start stops helping; that, and
  whether the authored libraries retire then, is the owner's decision at that point (README, open item).
- Literature overlays (`tjl19_dwsim`, `holland_b12`, the CDU17 fixture): if a derived pair moves a number that
  reproduces a published one, the build reports it before re-pinning.
- Not touched: the still, the network, the free-water machinery, the wire, facts.

## 5. Choices for the owner

1. Which feeds put water on the axis.
   - a. A compound that names a homomorph (ethanol today: the batch's mark of a water-miscible organic) above 1e-3
     of the feed's moles. Recommended.
   - b. Any compound with a polar group. Then a sour crude column (hydrogen sulfide, a mercaptan) loses its steam
     and its free water.
   - c. A new flag on the compound record, written in W6.
2. Stripping steam in a column with water on its axis.
   - a. Refused with its reason; the reboiler heats. Recommended: the smallest change.
   - b. Built now as a vapour feed of water on the axis (a second feed term in the tray rows). One more work
     package; a beer column on live steam is the classic use.
3. Water's enthalpy on the axis.
   - a. The cubic alone (D4). Recommended.
   - b. The network's share in the column too: exact agreement with the sheet's duties, new derivative plumbing
     in the column's rows.

## 6. Gates

| Gate | Proves |
|---|---|
| E1 against a McCabe-Thiele construction in the test, on the kernel's K at 1 atm | distillate within 0.01; D within 3 % of 10.7 mol/s; the column's own balance audits pass |
| E2 | 0.88 to 0.8965, never above |
| E3 | bottoms ethanol under 1e-3 |
| A dry column before and after D1 on a list with no gas (pentane to octane) | bit for bit: no pair moved |
| `medium_crude` before and after D1 | the moved numbers inside section 1's bands, or reported |
| The learned start on each bundled preset | accepted; iterations beside the classical start |
| Ethanol, water and n-hexane feed | converged or a typed failure; `column.two_liquids` names the trays |
| A steam row with water on the axis | refused with its reason |
| The sheet: a wash feed into a column unit | no `column.liquid_water`; products close mass to 1e-9 |
| A crude sheet with 1e-6 of ethanol | steam and free water kept (under the share) |
| The running column on a wash island | a split table with water in distillate and bottoms |
| Kernel evaluations, `medium_crude` | within 2 % of before |

## 7. Failure modes to expect

- A derived pair with no source (`DerivedPairs.Source.MISSING`) on a test overlay whose compounds carry no group
  counts: the pair must read zero, not refuse the column.
- The feed flash and the classical seed start from Wilson K values, which know no azeotrope: E1 may need more
  Newton iterations than a crude column; a failure there is reported with its stage, not patched in the seed.
- Near the azeotrope the liquid and the vapour have almost one composition. `INTERIOR_PHASE_IDENTITY` also
  compares roots and enthalpies, so it should pass; if it rejects E2, report.
- The condenser at 70 C must read all liquid (no vapour distillate, no water regime).
- A column near choice 1's share flips between the two treatments from one solve to the next; accepted, recorded.
- Support: Water at zero in a crude request must stay out of the solve as today (the list without Water).

## 8. Build order

Each step ends with a report and a commit by explicit path. Tests by `--tests`, at most 4 workers beside another
run; `columnGates` holds the column classes.

- W5-0. Baseline: `./gradlew columnGates` once on HEAD. Its inputs changed since its last pass (the kernel, the
  catalog) and it has not run. Record what fails before any change.
- W5a. D1 and D9: `kernelFor`, the label suffix. Then the dry bit-for-bit gate, `medium_crude`, the learned
  starts, the sour column, the moved references re-pinned with causes. One `columnGates` run after it.
- W5b. D2 inside V3: the steam refusal, the classical start on E1 to E3 (test package `pilot-aqueous`).
- W5c. D3 and D5 in the callers: the shared rule, `ColumnBlockSolve.run`, `ColumnSolve`, `SplitTable`, the
  two-liquid notice, the en_us lines (`column.two_liquids`, the steam refusal). Their gates.
- W5d. The code guide: `column-v3.md`, `column-operation.md`, `process-simulator.md`.
- Not in W5: the campaign and the final lane; `equipmentGates`' column classes (the final lane); a start
  network; ethanol in the main data (W6).

## 9. Delegation request

**Sub-thread** (`start_session`, 1-h cache), new, with this document as its brief.

- What it does: steps W5-0 to W5d. Done: section 6's gates pass, every moved reference is re-pinned with its
  cause, the reports are written, the commits are made.
- Why not the main thread: it runs `columnGates` twice and many filtered column tests, each minutes long.
- Model and effort: Opus high. The column's solver and its references are a hard task; it is not a
  cross-subsystem design.
- New, not a reuse: `w4-still` holds the still and the open vessel, not the column, and its context is long.
- Context: fresh, this plan.
- It waits on: Gradle runs, about 3 to 6 hours in all. Updates every 50 minutes, at once on a decision, a final
  report.
