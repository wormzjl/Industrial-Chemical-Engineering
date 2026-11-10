# Rigid reservoirs once filled

Batch 2026-11-10-rigid-reservoir, on `claude/ecstatic-dubinsky-c24b8a` (after the steam ejector batch, whose facts schema is 17). Owner rule, 2026-11-10, in answer to the ejector batch's open item 1: "Resevoir should be rigid when it's filled once (except the floating roof tank)". A batch of its own: it changes a saves fact and the vessel law of every closed vessel, not the ejector.

## 1. Today (decision D54: a vessel is a bag)

- A placed reservoir is empty: `FluidWorldAuthority.place` (about line 838: only the capacity of the spec means anything), `FluidDeviceSpec` (line 71: a reservoir node is `PassiveNetwork.emptyVessel`).
- Its wall row is one equation of two branches, `PhaseLayout.wallRow`: `max(taut, slack) = 0`, `taut = (V − V_max)/V_max`, `slack = 2 (P_amb − P)/P_amb`. Contents smaller than the capacity stand at the ambient (slack, the bag shrinks); contents that fill it are taut (fixed volume, any pressure above the ambient). `Inventory.wallCapacity` is infinite for an open vessel, so its row is slack alone.
- The bag's volume moves only on the slack branch, so its work on the ambient `P_amb ΔV_bag` is booked as energy alone at the vessel (`ConservativeTransport` line 627, `VesselCrystals`, `InventoryEquilibrium`); the rest gauge reads the same row (`NetworkReduction.atRest` line 150, `WALL_RESOLUTION`), and the certificate treats that energy-only boundary as no flush (`IslandCertificate` line 95).
- A vessel that empties runs dry inside the step and stands at the ambient holding nothing (`PassiveStepSolver` lines 476 to 611, `ConservativeTransport` lines 115 to 479, `PassiveNetwork.Reservoir.empty`).
- Presentation: `FluidView.State.fill` (contents over capacity, 1 when taut); `FluidWorldAuthority.view` (about line 2077: an empty tank shows nothing).
- So a closed tank never stands below the ambient: drained, it shrinks. The ejector batch found the consequence: a set on a closed tank empties it at 1 atm.

## 2. Expected behaviour

A steel tank or drum that has once been full keeps its volume. Drawing from it lowers its pressure: the vapour space grows, its gas expands, the liquid flashes to its own vapour pressure; a pump on it meets the existing NPSH and vapour-lock rules; a gravity drain stops where the head no longer beats the vacuum. A floating-roof tank keeps its moving roof (variable volume at the ambient).

Numbers, a 100 m³ closed tank at 25 °C, a pump draws 10 m³ (10 %):

| Case | Start | After 10 m³ out | Flash | NPSH available |
|---|---|---|---|---|
| Water, liquid-full | 100 m³ water, taut | 3.17 kPa (water's P_sat) | 0.23 kg of steam fills 10 m³; cools the water 1.5 mK | the level head only, about 4.5 m: a pump needing 3 m runs |
| Water, 80 m³ under 20 m³ of N2 at 1 atm | 101.3 kPa | N2 98.2 → 65.4 kPa, total **68.6 kPa** | 0.23 kg | `(68.6 − 3.2) kPa / ρg + h` = 6.7 m + h |
| Crude (true vapour pressure 30 kPa), 80 m³ under 20 m³ at 1 atm | N2 71.3 + HC 30 kPa | N2 47.5 + HC 30 = **77.5 kPa** | about 121 mol, 6 kg of C3 to C5 to keep 30 kPa; cools 15 mK | `(77.5 − 30) kPa / ρg + h` = 5.7 m + h |
| Crude, liquid-full | taut | its bubble point, about 30 kPa | 121 mol, 6 kg | the level head only: a pump at its NPSH margin |

A sealed water tank 6.25 m tall (16 m² floor, 5 m of water under 20 m³ of N2 at 1 atm) draining by gravity through a bottom line to the atmosphere stops when `P_top + ρ g h = P_amb`: after about 13.6 m³ of its 80, the gas at 60 kPa over a 4.15 m level. Today the bag lets all 80 m³ out.

Real atmospheric storage tanks take only about 0.25 kPa of vacuum (API 650) and carry a vacuum breaker; drums and spheres take full vacuum. The game's vessels are all steel bodies: section 4.1.

## 3. The fact

- `rigid`, per vessel, in the facts' vessel contents record (`FluidFacts.Vessel`, beside `open`): append-only, default **false** (a bag: today's behaviour). A fact, not state: it records the vessel's history, which the engine cannot compute again.
- The engine sets it at commit when a booked slice ends with the vessel's wall taut (`PhaseLayout.Wall.taut` on the committed state). It never clears it (the owner's "filled once"); breaking the block and placing a new one starts a new bag. A layout refresh and a topology edit keep it with the contents.
- In the solve it is a slice input on `PassiveNetwork.Inventory` (`rigid`, default false), read at the interval start; it never changes inside a Newton solve, so it adds no switch inside the row.
- Wall law: rigid → the taut branch alone (`V = V_max`, any pressure), as an open vessel has the slack branch alone. Three walls: bag (`max`), open (slack), rigid (taut). The rest gauge, the bag's wall work (zero on a rigid wall), the crystals' refresh (fixed volume) and the certificate read the same branch.
- Old worlds (O1): **A (recommended)** the same rule: an old tank counts as filled at its first booked taut slice after loading (a tank taut now becomes rigid within one slice; a slack one stays a bag until it fills). B: old tanks never become rigid (needs a per-vessel "placed before" mark; no physics for it).
- Facts schema 17 → 18, a corpus image `src/test/resources/compat/fluid/schema-18/` with one rigid and one bag vessel; the old images load with `rigid = false`.

## 4. The limits

### 4.1 A vacuum the vessel cannot take (V1)

- **A (recommended):** none: every closed vessel is a steel body that takes full vacuum. The pressure shows in its view as it does today.
- B: as A, plus a non-blocking status notice on the island below 50 kPa ("vacuum in tank").
- C: atmospheric tanks implode below a design vacuum: rejected (a new failure mode, a mechanism).

### 4.2 No gas left to expand

The contents' own vapour pressure holds the floor: water 3.2 kPa at 25 °C, a crude its bubble point. A heavy residue with no light ends goes to its own small vapour pressure (Pa or below); the properties extrapolate (thermo validity: no hold). Numerical guards act only where the mathematics fails (no equation-of-state root, T ≤ 0), as rejected trial steps. The flows stop before the vessel empties: a pump's NPSH and vapour-lock rules stop it; a gravity drain balances its head.

Dry (R1), if a step still drains a rigid vessel to the dry resolution:

- **A (recommended):** it stands dry and rigid at no pressure (it shows "empty"); what arrives next is laid out by today's dry-vessel code at fixed volume.
- B: it returns to a bag (clears the flag): against "filled once".

### 4.3 Refill, vents, open vessels

- Refill: a rigid vessel's pressure rises as it fills; nothing else changes (it is taut above the ambient today).
- A vent line to the atmosphere (a pipe to a void) lets air in as it does now: the player's own vacuum breaker.
- Floating-roof tanks and cooling-tower basins (`open`): unchanged, never rigid. Junctions: no wall, unchanged.
- Which vessels (K1): **A (recommended)** every closed vessel node: one-block tanks, drums, spheres, kettle shells, still pots. B: one-block tanks and drums only.

## 5. What moves, and the least measurement

The flag is false on every science-level graph unless a test sets it, so the network tests (`ConservativeTransportTest`, `EmptyNetworkTest`, `OpenTankTest`, `LevelHeadTest`, the crystal tests) do not move. New tests: the four rows of section 2 at the solver level (pressure, flash, NPSH), the gravity balance, the flag set at commit and kept by a refresh, the facts round trip and corpus.

Runtime and references that can move, where a closed tank fills taut and is later drawn below the ambient:

- island tests in `runtime/fluid` that drain filled tanks (the event-path worlds, `DrumReservoirTest`, `VesselRebuildTest`, the pump and still tests);
- chain-100 exact and the fluid GameTests, if their tanks fill taut;
- the 33 junction lines and `MIXED_GAS_COST`, only if their worlds hold a taut vessel that drains;
- the hold campaign: fuzz worlds that pump closed tanks down now reach vacuum, flashing and vapour lock where they shrank a bag: new classes are possible.

Measurement: the affected test classes by the lane's load sets; one 200-seed quick check (all distributions) against the baseline to see the hold classes move; one full campaign (1000 seeds, 8 workers) for the chosen form; new budget-class holds replayed alone in one batched run; one final lane. No per-seed runs.

## 6. Decisions for the owner

| | Question | Recommended |
|---|---|---|
| O1 | Old-world tanks | A: rigid at their first booked taut slice, as a new tank |
| V1 | Vacuum limit | A: none, steel takes full vacuum; no notice |
| R1 | A rigid vessel drained dry | A: stays rigid, stands empty at no pressure |
| K1 | Which vessels | A: every closed vessel (tank, drum, sphere, kettle shell, still pot) |

Fixed by the owner's rule, not options: floating-roof tanks stay variable-volume at the ambient; the flag is never cleared; a new block starts as a bag (D54 unchanged until its first fill).

## 7. Decisions and implementation (2026-11-10)

Owner: "All A, floating roof tank stays at it is." (O1 A, V1 A, R1 A, K1 A.)

- `PassiveNetwork.Inventory.rigid` (never for an open vessel; `withRigid`). `wallCapacity` is `-V` for a rigid vessel: `PhaseLayout.wallRow` is then the taut branch alone, `taut` is always true, the bag is `V`, and the wall does no work (`emptyBag`, `wallWorkPressure`).
- The flag is set where a step is booked (`ConservativeTransport`): a closed vessel holding fluid and taut at its volume becomes rigid. This happens at the science level too. The sentence in section 5, "the flag is false on every science-level graph unless a test sets it", is wrong: every test whose closed tank fills taut and then drains can move.
- Facts schema 18: the vessel's `rigid`, read default false. Corpus `compat/fluid/schema-18/` (2 rigid vessels). State format 43. The certificate digest carries it.
- Flash below the vapour pressure (4.2): `PassiveStepSolver.flashes` seeds a rigid vessel without gas at its fixed-volume equilibrium (`FluidThermodynamics.equilibriumAtVolume`). The phase check's crossing uses UV in a rigid vessel.
- Owner decision of 2026-11-10 (first bubble, option A): in a rigid vessel, the trace fold and the vanishing rule (D34, D38 C) judge a gas phase by its volume share alone, in the form of the 2026-10-20 rule for a hydrocarbon liquid (`FluidThermodynamics.rigidGas`, `belowShare`). A liquid-full tank's first bubble is 3e-11 of its moles but 2e-6 of its volume. The mole share folded it, and the pass cycled. Bags, junctions and other phases are unchanged.
- Owner decision of 2026-11-10 (breakthrough pass order, option A, amending A272): a converged pass that shows a forbidden direction is phase-checked first. If the check changes a node's phases, the pass is solved again. Only then is the boundary closed. Case: `PhasePortPriorityTest`, the bottom-port drain at 0.1 s slices, at the gas breakthrough (108.5 kPa). The last water was folded off a failed iterate, and the next pass held it as steam at 174 K and 67 kPa, drawing from the void. The closure stood the drain still. A bag's slack wall hid the same pass at the ambient.

Results: blanketed 100 m3 tank drawn 10 m3, 69.2 kPa (plan 68.6). Gravity drain stops at a 0.778 level (plan 0.783). Liquid-full tank 9.5 m over the void drains to a 0.539 level at 3.17 kPa (plan 0.53). A bag filled taut is rigid.
