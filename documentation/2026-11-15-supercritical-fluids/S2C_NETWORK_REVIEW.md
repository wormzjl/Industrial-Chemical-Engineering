# S2c network review: the slot rule by density, what still reads the slot, three GameTests

Batch [2026-11-15-supercritical-fluids](README.md), stage S2c of [SUPERCRITICAL_PLAN.md](SUPERCRITICAL_PLAN.md) ("S2c as
briefed"). Sub-thread work, 2026-11-15. Follows [S2_NETWORK_REVIEW.md](S2_NETWORK_REVIEW.md).

## Summary

- Slot rule (`ac1808ee2`, owner decision O2 A): when an engine answer holds exactly one fluid phase on a supercritical
  isotherm, the network puts it in the liquid slot when its reduced density is at least 1, otherwise in the vapour slot.
  This holds for mixtures too, whatever label the engine gave. Probe: no disagreement with O2 A. Every state that moved
  went from liquid to vapour, and 28 of them lie inside the old ranges (section 1). The engine, its digest, the column and
  the simulator are unchanged.
- What still reads the slot (section 3): the LIQUID port's level head is the one seam a player sees (20 kPa for CO2 and
  13.9 kPa for N2 in a 5 m vessel). The pump's head share and the wet compressor are conventions for S4 to judge, and so
  is the pump's suction limit E.
- Pump suction (`af2590e6a`, `DenseFluidSuctionTest`): above Tc there is no vapour pressure, so no cavitation margin and
  no vapour lock. The limit is E alone: a real flash below Pc, the slot line above it. Across neighbouring states E is
  continuous (section 4).
- "No saturation" callers (section 5): one defect fixed in one line, `EjectorLaw.steam` above water's Tc. The others are
  fine or unreachable.
- GameTests (section 6): run `s2c-gt1` passed the 98 existing tests on the S2b laws and the slot rule; the 3 new ones
  failed on their own premises and layout (no hold, ledgers closed). Rebuilt (`22be39e01`), run `s2c-gt2`: the
  two-nozzle test passed (same fluid at both nozzles, head exact). The letdown and the loop failed on two block
  placements in the tests, which registered no valve install and no pump. One existing test, the blanketed drum,
  failed at 99.57 % against 99 %; it passed in run 1 on the same production code. Fixes await a go-ahead.
- Campaigns (section 7): of the cold campaign's draws, S2b's viscosity reaches only its CO2-rich gas. The S2b review's
  "nitrogen sources at 126 to 170 K" is wrong: that harness has no cold nitrogen.
- Owner line: the engine still labels a one-root mixture by PIP (option B, the engine lane's work, not done).

## 1. The slot rule

**Expected.** Above its critical temperature a fluid is one phase. The network's two bulk slots are a convention: a
fluid at liquid-like density (at least the critical density) sits with the liquids, and a lighter one sits with the gases.

**What the code did.** `DryCore.singleLabel` marks only one compound above both Tc and Pc as SUPERCRITICAL_FLUID. The
engine labels every one-root mixture LIQUID or VAPOR by PIP, and `FluidThermodynamics.placed` took those labels as
given. So the briefed rule, added in `placed`'s default branch, reached pure fluids only. A dense nitrogen with 1 %
methane kept the PIP slot.

**What changed.** In `placed`, when the answer holds one fluid phase and its `PhaseTraits` say it lies on a supercritical
isotherm, the slot is liquid if and only if `reducedDensity() >= 1`. Every other case is as before. With no traits the
old label rule applies. `PhaseIdentification`'s class comment says the network no longer places one dense fluid by this
label. `DenseFluidGatesTest` asserts the line: below it, `flashTP(320 K)` gives VAPOR, and δ = 1 within 1e-9.

States that moved (`SlotAfterProbe`, flashTP, `tmp/supercritical-s2/probe/slot-after.txt`). No disagreement with O2 A in
any case:

| Grid | Fluid | Moved from PIP | Inside the old ranges |
|---|---|---|---|
| reduced T, P | N2 | 797 | 11 (145-177 K, 6.1-9.8 MPa) |
| reduced T, P | CH4 | 816 | 2 (219.1 K 8.279 MPa; 228.7 K 9.658 MPa) |
| reduced T, P | CO2 | 531 | 0 |
| T, P | N2 | 392 | 8 |
| T, P | N2 + 1 % CH4 | 386 | 4 |
| T, P | CO2 | 90 | 0 |
| T, P | CO2 + 2 % N2 | 95 | 0 |
| T, P | natural gas | 325 | 3 (230 K 8 MPa; 240 K 9 MPa; 250 K 10 MPa) |

Tests: the 236 classes that load `FluidThermodynamics`, plus `DenseFluidGatesTest` (run 5: `test` 231 classes and 1,497
tests; holdGates 14 classes and 136 tests). 0 failures. No test pinned a moved state, and no stream value moved.

## 2. Where a one-fluid node's slot is decided

- The slot is decided only when an engine answer is placed (`placed`, through `placedState` and `folded`, which keep the
  bulk slot).
- Between engine answers, `StepPhaseRules.phaseCheck` acts at the outer phase check. A node that lacks a phase gets a TP
  answer that proposes the missing phase. A vessel's own split (`crossing`/`vesselSplit`) or a junction's PH split then
  seeds the node. A rigid vessel with no gas whose iterate pressure falls is reseeded by `equilibriumAtVolume`
  (`PhaseActiveSet.flashes`).
- Inside a pass the decoded slots stay as they are. A node whose density crosses 1 inside a pass keeps its slot until
  the next phase check places it again.
- So a node can sit in the "wrong" slot only between two phase checks of one interval. The ledger sees the same mass and
  energy either way. Only the readers in section 3 see the difference, for that pass.

## 3. What still reads the slot

Measured with `SlotReaderProbe` on each side of δ = 1, at δ 0.9995 and 1.0005 (`probe/slot-readers.txt`):

| Reader | CO2 310 K (8.3251 / 8.3265 MPa) | N2 150 K (7.0541 / 7.0593 MPa) | Kind |
|---|---|---|---|
| LIQUID port's level head, 5 m prism | 0 → 20,206 Pa | 0 → 13,875 Pa | seam |
| pump: vapour share, head share | 1 → 0; 0.5303 → 1.0 | head share 0.4683 → 1 | convention (S4) |
| pump: zone edge E | none → 8.3178 MPa | none → 7.0535 MPa | convention (S4), section 4 |
| compressor: condensed share, wet efficiency | 0 → 1; ×1 → ×0.5 | same | convention (S4) |
| `GasBlanket.gas()` | true → false | same | seam |

Natural gas at 300 K (16.743 / 16.760 MPa): head 7,533 Pa above the line, head share 0.7129 → 1. Its p_v is 2.66 MPa
above the line: ethane and propane remain after the light gases are dropped. CO2 at 320 K, 15 MPa: δ 1.643, head
32,879 Pa, E 6.5198 MPa, wet efficiency ×0.5.

- **Head seam.** The LIQUID port's head is the dense mass times g over its level. It jumps from 0 at the line, a few
  per cent of a pump's suction margin. To make the head follow the density, the head would count every one-fluid mass
  times g, weighted by the density. Its Jacobian needs entries from the vapour amounts to the port pressure, which the
  block structure does not have now. This is an option for the owner, not done.
- **Pump and compressor.** A pump in the vapour slot counts its stream as vapour (head share 0.53). A compressor in the
  liquid slot counts it as condensed (efficiency ×0.5). On a dense fluid near δ 1 each machine is on its "wrong" side
  for half the window. These are the pump-against-compressor convention that S4 judges.
- **Pump below E.** The pump's row holds the suction at E (PUMP_SUCTION_LIMIT, the saturated mode) and delivers what its
  line passes there.
- **Gas blanket.** The blanket needs the vapour slot. A rigid vessel of dense fluid in the liquid slot has no vent, even
  above the vent pressure. When dense fluid leaves the vessel, pad gas arrives. This is a seam for the owner.
- **Other readers:**
  - `PassiveStepSolver.dryEnd`: a slack vessel with no gas has a dry VAPOR port.
  - `Pool.film`: the two-phase film when it holds vapour, otherwise the liquid film. S2b's weight covers the seam.
  - `BalanceStart:298`: the liquid-only density table.
  - `PassiveIntervalSolver:122`: the aqueous test only.
  - `StepObservations.cavitation`: observational.
  - `Converters:395`: a label.
  - Readers of amounts only: `MoverSuction.drawnLiquid`, `release`, `PhaseDraw`, `StepEquations`,
    `NetworkReduction.viscosity`, `PipeTransfer`, `StartSeeds`.
  - Ambient-only readers: `OpenVessel`, `Stills`, `TowerTransfer`.
  - Boil-up, which needs both phases: `PassiveStepSolver:1813`.

## 4. The pump's suction limit E on a dense fluid

**Expected.** A pump on CO2 above Tc has no vapour pressure, so it cannot cavitate in the classical sense. Its limit is
where the stream at its own enthalpy stops being one clean fluid.

**What the code does** (`MoverSuction.zoneEdge` → `releasePressure` → `ReleasePressure.lowest`, bisected in ln P):

- `release` (p_v) leaves out compounds above their Tc, so it is +∞ for CO2 above 304 K.
- The limit is max(p_v, E), and vapour lock needs a finite floor, so there is none.
- `ZoneEdgeProbe` (`probe/zone-edge.txt`) found two cases:
  - 320 K, 15 MPa: the isenthalp stays one liquid-slot fluid down to 7.0 MPa (δ 1.357). It is two phases at 6.8 MPa.
    The vapour share reaches the clean share (0.2933) at 6.52 MPa, below Pc. So E = 6.5198 MPa is a real flash, and it
    lies 8.5 MPa under the suction.
  - 310 K, δ 1.0005, 8.3265 MPa: the stream is in the liquid slot down to 8.3180 MPa. At 8.3178 MPa it is in the vapour
    slot, where the share is NaN and the trial fails. So E is the slot line, the critical isochore on the isenthalp. The
    vapour-slot stream stays one fluid down to 7.5 MPa and is two phases at 7.0 MPa (share 0.7557).
- The trial is not monotone: it fails across the line and passes again below it. Even so, `lowest` landed on the line for
  every neighbouring state measured: the trial passes everywhere between the suction and the line.
- For neighbouring states E moves continuously from the line (above Pc) to the dome (below Pc):
  - 330 K: 10.49, 9.47, 8.58, 7.81, 7.376, 7.331, 7.27 MPa;
  - 320 K: 9.70, 7.91, 7.33, 7.195 MPa.

  The line and the bubble curve meet at the critical point, so E has a kink but no jump.

**What changed.** No code change (owner option A). `DenseFluidSuctionTest` asserts both cases: no p_v, no vapour lock,
and limit = E. E is the pump-against-compressor convention that S4 judges: below E the pump holds its suction at E.

## 5. The "no saturation" callers

`NetworkWater.saturationPressure` is +∞ at and above water's Tc:

- `Service.vapour`: the comparison is false, giving STEAM_SUPERHEATED. Correct.
- `TrainedPhysics`: guarded by T < Tc.
- `TowerTransfer.utilityWater`: log(+∞) would give NaN. Unreachable: liquid water at tower pressure boils at 373 K, and
  the air stays at a constant temperature.
- `Ejectors.steam` and `EjectorUnit` through `EjectorLaw.steam`: NaN with no water, +∞ with water at 647 K or above.
  Fixed (`af2590e6a`): at +∞ the set lifts the water to the discharge as it does the gas. No finite value moves.
  `SteamEjectorTest` gains the case.

Tests (runs 6 and 7), all green:

- `DenseFluidSuctionTest`: 2 tests.
- The six classes that load `EjectorLaw`: 43 tests (`SteamEjectorRuntimeTest` 2, `EjectorTest` 9, `SheetSizingTest` 23,
  `SteamEjectorConnectionTest` 3, `SteamEjectorTest` 4 + 1, `ColumnEjectorTest` 2 in holdGates).

## 6. Three GameTests (`DenseFluidGameTests`)

Each test watches its island through the engine's observer. It closes the ledger per component (1e-12) and in energy
(1e-10), from the reservoir inventories against the boundary transfers plus the pump work. Each test removes what it
placed. Two runs of the whole fluid GameTest task were made, each once, with a fresh run id; nothing was rerun. The
results are in `tmp/supercritical-s2/gametest-s2c-gt1/` and `gametest-s2c-gt2/`.

**Run `s2c-gt1`** (tests as in `e5b554968`): 101 tests, 98 passed, 3 failed. All 98 existing tests passed on the S2b
laws and the slot rule. The 3 new tests failed on their own premises and layout, with no hold, and every ledger closed
(at most 6e-16):

| Test | Result | Ledger | Mechanism |
|---|---|---|---|
| N2 letdown, 20 → 0.5 MPa | failed: lowest source T 325.2 K, bound 293 K | 3 intervals, 3e-16 / 6e-16, no hold | test premise |
| CO2 two nozzles, 12 MPa tank to voids at 11.5 MPa | failed: both outlets carry 0 | 18 intervals, 2e-16 / 1e-16, no hold | test premise |
| closed CO2 loop at 9 MPa | failed while building: "No route" | none | test layout |

- **Letdown.** `TankCharge` fills an empty rigid tank from a 298.15 K generator. In adiabatic filling the tank's u
  equals the line's h, so the tank stands near 406 K, not 298 K (CoolProp, `letdown_check.py`). Mass conservation from
  the printed end states (9.43 MPa in both tanks, source 325.2 K, receiver 501.4 K) puts the source's start at 407 K.
  The isentropic letdown from there to 9.43 MPa gives 326.9 K; the engine gave 325.2 K. This is the check that the
  physics is right. The bound rested on the false premise, and the letdown mostly ran before the observer started.
- **Two nozzles.** A closed tank against voids at a fixed pressure gives up 0.7 % of its mass (about 5 kg) to fall
  0.5 MPa. That leaves within the first interval, and the steady state carries nothing (`nozzles_check.py`). The test
  waited for a flow that physics ends. The bottom outlet's 6,118 Pa is g times 623.9 kg/m3 over the tank's 1 m level
  height.
- **Loop.** The failing route was the third one, to the tank's north face. `Plant.route` refuses an end cell beside a
  device that is not allowed, and that cell touched both the tank and the suction line's second pipe. An off-line
  replay of the routes in the game's direction order (`probe/LoopLayoutProbe`, `probe/loop-layout.txt`) reproduces the
  failure at the same coordinates.

**The rebuilt tests** (`22be39e01`, on the main thread's go-ahead):

- Loop: the suction leaves the west face and the return enters the east face, with the tank allowed at that end; the
  replay routes them with no pipe beside the tank or another line. The loop is charged with all of it laid: a generator
  at 295 K and 9 MPa holds it while the pump runs and the exchangers work, then it goes. A loop charged and then heated
  or cooled would move its pressure by several MPa: a charge at 300 K settles between 6.6 and 10 MPa, through Pc
  (`loop_estimate.py`). The estimated states are a heater outlet of 340 to 355 K at 9 to 12 MPa, δ 0.4 to 0.8 (vapour
  slot), and a cooler outlet of 290 to 295 K, δ about 1.8 (liquid slot). The test waits for 30 accepted intervals, or
  for the island to certify steady. In each of the last 10 it checks: the hottest node above Tc + 5 K at δ below 1, the
  coldest below Tc − 2 K in the liquid slot, every node one fluid above Pc; the amount constant, and no matter crossing
  a boundary.
- Letdown: the source's state is recorded when the charges are done. The observer starts before any pipe. The source-side
  pipe and the valve go in first, and the receiver-side pipe last. Checks: the source never warms; it falls by at least
  half the ideal-gas isentropic drop to its end pressure; the receiver's energy closes against what the rest gave up.
- Two nozzles: a generator on the west face (310 K, 12 MPa) keeps both draws steady. The comparison is on the third
  interval after both voids receive. The bottom head is checked against `portHead`'s prism value on the view's state.
- Each wait for a count of intervals also ends when the island certifies (`CertificatePolicy` confirms in two
  intervals), because a certified island publishes no interval.

**Run `s2c-gt2`** (tests as in `22be39e01`): 101 tests, 98 passed, 3 failed.

| Test | Result | Mechanism |
|---|---|---|
| CO2 two nozzles | passed: 7 intervals, 2e-16 / 3e-16, no hold | |
| N2 letdown | failed while building: "The valve did not install" | test placement |
| closed CO2 loop | failed: hottest 298.15 K, 8.984 MPa, δ 1.80; heat 0; at rest, certified after 7 intervals | test placement |
| `LevelLoopGameTests.aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence` (existing) | failed: level 25.91 to 99.57 %, bound 1 to 99 % | not settled |

- **Two nozzles.** The tank stood at 309.49 K, 11.598 MPa, δ 1.668, in the liquid slot: one dense fluid. Both outlets
  drew it (stream row 0, the oil row): 40.1 kg/s at the bottom and 34.0 kg/s at the top, against 74.5 kg/s fed. Their
  specific enthalpies (−9,315.380 J/mol) and compositions were equal to the last digit. The bottom head was 6,673.15 Pa:
  g times 680.47 kg/m3 times the 1 m level height over the 1 m3 tank, a prism. It equalled the prism value to 1e-15. The
  top head was 0.
- **Letdown.** The valve went in as a new block whose state already carried the pressure control. `FluidDeviceBlock.onPlace`
  then registers the pipe with its valve (`world.place(…, hasPressureValve(state), …)`), so `installPipePressureValve`
  refuses: `canInstallFitting` requires a passive control. Run 1 set the state on a pipe already laid, and the same block
  only edits its facing there. Fix: lay the pipe, then set the state and install, as in run 1.
- **Loop.** The charge worked: the loop stood at 8.984 to 9.000 MPa, the generator's pressure with 16 kPa of head over
  its 2 m of height, and the ledger closed exactly. But nothing moved. The pump's in-line block state went onto a pipe the
  route had already laid, and onto the same block `onPlace` only edits the facing, so no mover was registered. With no
  flow there is no duty, and the island came to rest. Fix: install the pump as `HeatExchangerReviewWorld.pump` does (the
  state, then `installPipeMover(id, facing, PUMP)`, the geometry refresh, the power).
- **The blanketed drum** (existing). Run 1 passed it: 24.59 to 64.87 %, pressure to 1.274e5 Pa, 83 intervals, setup
  residual 4.8e-10 mol. Run 2 failed it: 25.91 to 99.57 %, pressure to 1.881e5 Pa, 75 intervals, and a setup residual of
  130 mol and −5.08e5 J. That residual means its island took in or lost matter while it was being set up. Its loads
  include `FluidThermodynamics` and the S2b laws, but run 1 passed it on the same production code, and between the runs
  only `DenseFluidGameTests` changed, which it does not load. So no code change of S2b or S2c explains the failure. Its
  structure stood 7 blocks from where the letdown test was built (same x, z + 7), inside that test's plant box. The letdown
  test had been removed 25 s before, and its finish waited until nothing it placed was registered. The run's leftovers
  are in `run/fluid-gametest-s2c-gt2/world`. A replay is not allowed in this task.

Fixes awaiting a go-ahead, with one more run: the letdown valve and the loop pump, both placements as above. The
blanketed drum stays an open item for that run or the final lane.

## 7. Campaign distributions (read, not run)

`CampaignDrawProbe`, 4,000 draws per row (`probe/campaign-draws.txt`):

| Draws | Pressure | Dense | ρr > 0.25 | Slot moved |
|---|---|---|---|---|
| cold LNG (110-170 K, ≤ 6 MPa) | as drawn, and ×6 | 0 | 0 | 0 |
| cold CO2-rich gas | as drawn | 3,397 | 935 | 0 |
| cold CO2-rich gas | ×6 | 3,183 | 3,183 | 19 |
| bundled N2/CH4 (250-400 K, 0.06-0.5 MPa) | as drawn, and ×6 | all | 0 | 0 |

So S2b's viscosity (onset ρr 0.25) moves only the cold campaign's CO2-rich gas. S2c's slot rule moves no campaign draw
at the drawn pressures. The cold harness has no pure-nitrogen generator: its nitrogen tanks and voids are at 298 K and
1 atm. This corrects [S2_NETWORK_REVIEW.md](S2_NETWORK_REVIEW.md) ("The cold campaign").

## 8. Not done, and open items

- Not done:
  - the letdown and loop placement fixes and their run (section 6); the blanketed drum's failure in run 2, not
    settled;
  - no campaign, lane or whole suite.
- For the owner:
  - The engine labels a one-root mixture by PIP (option B: label by density in the engine, the engine lane's work, not
    done). The network does not depend on it after O2 A.
  - The head seam (section 3): keep it, or make the head follow the density, with its Jacobian cost.
  - The gas blanket in the liquid slot (section 3).
  - The pump's and compressor's conventions on a dense fluid, and E, for S4.
