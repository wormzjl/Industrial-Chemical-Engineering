# W3b: the fluid network with water in the equation of state

Batch 2026-11-11-aqueous-solvents, work package W3b of [AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md). It follows [W3A_ENGINE_PLAN.md](W3A_ENGINE_PLAN.md) (the engine) and comes before W4 (still), W5 (V3 column) and W6 (ethanol in the main data). Written 2026-11-11. Nothing here is built yet.

## 1. Expected

A vessel holds up to three fluid phases: a gas and two liquids. Every compound, water included, stands in each phase at the amount that makes its fugacity the same in all of them. All phases are at the vessel's one pressure. Numbers at the network's usual states (W3a's measured answers):

- Water with n-hexane at 25 C: the water holds 2.1e-6 mole fraction hexane, the hexane 9.5e-4 water (0.02 % by mass). They boil together at 61.1 C at 1 atm, the vapour 0.79 hexane.
- Water alone: liquid volume and enthalpy are IF97 Region 1 exactly up to 350 C; it boils within 0.02 K of 100 C at 1 atm (saturation pressure 0.07 % off); the heat of vaporization is 0.03 % off at 100 C and 0.8 % at 200 C.
- A gas over water dissolves by its Henry constant (CO2 at 25 C and 1 atm: 6e-4 mole fraction, 1.5 g/L). Today it does not dissolve.
- Ethanol with water is one liquid at every ratio, with the azeotrope at 0.896 ethanol; with n-hexane added it splits in two liquids, and the three boil at 56.8 C.
- Ice forms from the water-rich liquid at the temperature its composition sets: 0 C for water, -4.0 C at 10 % ethanol by mass. Ice may lie under oil and beside water.
- A dry node behaves as today, except that a gas with a non-polar compound, where the authored library gives no pair, takes its E-PPR78 value at 298.15 K instead of zero (plan M4 as restated in section 8; the library gives 76 of the refinery list's 990 pairs).

Steady state and conservation are unchanged: amounts and energy are booked exactly; a phase that empties leaves its components in the phases that remain.

## 2. What the code does

- `FluidThermodynamics.State` holds two phases on the hydrocarbon basis (`liquid[]`, `vapor[]`) and water as two scalars (`waterLiquid`, `waterVapor`) with two partial pressures. Free water is pure Region 1 water; steam is a Dalton second-virial gas in the hydrocarbon vapour's volume (`SteamVirial`). Nothing dissolves in water and water dissolves in nothing.
- `PhaseLayout` gives a node: one total per component, `ln(v/l)` per two-phase component, the two water amounts, `ln T`, `ln P`, and `ln P_hc` with a closure row `P_hc + p_w = P`. Water's equilibrium is one row, `p_w = p_sat(T)`.
- `NetworkPhaseEngine` builds the engine on the `SEPARATE_FREE_WATER` contract with `NetworkFreeWater`. `FluidThermodynamics.adopt` turns a liquid-liquid answer or a third fluid phase into a hold (`UnsupportedPhases`).
- Ice is the free water's own solid (`FreeWaterIce`), outside the package's crystals. A step carries it as supercooled free water until the vessel's refresh (D23).
- The step solver and the ports already draw three phase streams by id (`GAS`, `OIL`, `WATER`) and layer two liquids by density (`heavierLiquid`). About 50 lines of `PassiveStepSolver`, 19 of `PhaseDraw` and 81 of `FluidThermodynamics` read the water scalars directly; 53 test files do.
- Rules that exist only because water is a scalar: the steam column's log-read floor (`LOG_READ_SPAN`), the partial-pressure box (`requirePartialPressure`, fuzz seeds 145, 157, 164), `waterTraceSeed`, the water branches of `foldTracePhases`, `omittedTrace` and `carriedGas`, the water suction floor, the water viscosity slot.
- W3a's engine answers all of section 1 on the `IN_EQUATION_OF_STATE` contract, except a crystal beside two liquids (the `CRYSTAL_PHASES` hold). The network does not use it.

## 3. Proposal

### N1. One phase model on the whole basis

The network's basis stays the island's compounds with water last. `HydrocarbonModel` becomes the model of every fluid phase on that basis, built from `DerivedPairs` (plan M4) with water's Mathias-Copeman alpha. A phase state carries W3a's two books: `ln phi` from the cubic alone; volume, enthalpy and energy with water's share (plan M2). The equilibrium rows read the first book, the balance, wall and level rows the second.

### N2. The state: three slots, no water scalars

`State` holds `vapor[]`, `liquid[]` and `aqueous[]` on the whole basis, one property record per held phase, and the totals. Removed: `waterLiquid`, `waterVapor`, `hydrocarbonPartialPressure`, `waterPartialPressure`, `waterCompressibility` (the acoustic bound reads the phase record). The phase ids stay 0, 1, 2; `WATER` is renamed `AQUEOUS`.

Slot rule, a function of the state alone (so a reload labels it the same):
- two liquids: the one with the larger water mole fraction is `AQUEOUS`;
- one liquid: `AQUEOUS` when water is at least half of it by moles, else `OIL`.

Layering, level heads and the top, bottom and overflow draws go by density, as today. The label decides only the display row and the boot drum's weir (N7). A relabel of a single liquid is not a phase event: the phase check compares the gas and the number of liquids.

### N3. The layout: one total and one split per extra phase

Per present component: its total, and for each further slot that supports it `ln(n_ik / n_i,ref)`, the reference being its first supported slot in the order liquid, aqueous, vapour. Each split has the row `ln(n_ik / n_k) + ln phi_ik = ln(n_i,ref / n_ref) + ln phi_i,ref`, all at the state pressure. A dry node's unknowns and rows are today's.

Removed: the two water unknowns, `ln P_hc`, the Dalton closure row, the saturation row. `PhaseSupport` becomes a three-slot mask. The truncation rule is unchanged in kind: a component is left out of a slot where its mole fraction is below the cutoff (1e-6) and above it elsewhere, and returns by the reinsertion inequality. At the cutoff a wet node's aqueous slot holds water, the light gases and the compounds up to about C6; the rest fold into the other phases exactly. Size of a wet three-phase node: today's, minus one (`P_hc`), plus one per component the aqueous slot holds.

### N4. Trace, fold and seed rules: one rule per slot

Every rule with a water twin is restated once over the three slots, and the twin is deleted. Each deletion is checked against the measured reason in its comment (AGENTS.md, "Small physics simplifications"): it goes only where the new layout removes the cause. Two causes go by construction: the steam amount is no longer read through a logarithm (it is a log split), and no partial pressure is an unknown. The others (`waterTraceSeed`, the fold and carried-gas branches, D41) are decided rule by rule in step 4 and listed in the result.

### N5. Ice is water's crystal

Ice Ih joins the package's crystals (`NetworkCrystals`, `VesselCrystals`), with W3a's anchor. `FreeWaterIce` and the free-water branch of the refresh go. Between refreshes ice is carried as its stand-in, supercooled liquid in the aqueous slot, by the rule every crystal has (D23 unchanged). The engine first learns a crystal beside two liquids and beside three fluid phases (step 1).

### N6. One engine entry, no phase hold

`NetworkPhaseEngine` builds the engine on `PhaseContract`'s derived contract; `NetworkFreeWater` and `Steam` go. `adopt` places the answer's phases by N2. `UnsupportedPhases.LIQUID_LIQUID` and `THREE_PHASES` are no longer reachable for three fluid phases; a fourth fluid phase stays a capability hold.

### N7. Ports, devices, transport, displays

- Draw orders keep their ids. A boot drum's boot holds the denser liquid (today: always the water). Its hydrocarbon outlet keeps today's rule on the label: `OIL` at any level, `AQUEOUS` only above the weir.
- The pump suction limit is the bubble pressure of the liquid drawn, one function for either slot; the water floor goes.
- Viscosity: one log-mixing rule over a liquid's components, water with its own liquid viscosity as its component value; the water slot goes.
- Utility water outside the network (cooling water sides, the tower's spray density) stays on Region 1, which pure liquid water in the network equals.
- Displays: the aqueous row reads "Water" while water is at least 99 % of it by moles, else "Aqueous". `en_us.json` only; GUI work follows the gui-creation skill.

### N8. Kernel: one rule, polar sums over what is present

The kernel's classical plans collapse into the Huron-Vidal form: the quadratic part for every pair, the exponential sums only over polar compounds with an amount in the phase (owner: no backward compatibility). A phase without a polar compound then costs what the temperature-dependent classical plan costs today. This is step 1, before the network moves, because the network evaluates the kernel at every Newton residual.

Decided 2026-11-11 (step 1b's question): a classical pair enters the rule as `dg_ji = Lambda [a_i/b_i - (1 - k_ij) s_i s_j / b_j]`, not by the literature conversion (`g_ji = -2 sqrt(b_i b_j)/(b_i + b_j) sqrt(g_ii g_jj)(1 - k)`). Both are the quadratic rule exactly where no local-composition pair is active, so binaries and dry states are the same. With the literature form a water-free phase in a list that holds water pays a second dense pass (about twice the mixing work); with this form each centre's sum follows from a sparse row, O(pairs + n P) with P the polar compounds present. They differ only where a classical pair and a local-composition pair act in one phase. Measured on `pilot_aqueous`: nitrogen in water beside ethanol and hexane 2.04e-6 to 1.92e-6 (6 %), nitrogen in hexane beside ethanol 1.4 %, water in a five-compound oil 0.5 %, everything else under 0.1 %. No data separates the two at that level. Owner, 2026-11-11: "Keep it". The conversion is in one place.

### N9. Saves

- Facts: no schema change. Contents are amounts on the basis with water last and a temperature; both keep their meaning. Step 6 checks every fact field that names a water quantity; a field found is kept and read.
- State: `FluidCheckpointCodec.VERSION` 46 to 47 (three sparse phase arrays, no water scalars). A mismatch drops the state and the engine rebuilds it.
- The model's revision changes (water's record, the package record, the derivation's identity). The existing restate job keeps amounts and temperature, recomputes energy and pressure, books the energy difference on the ledger and names it on the status line. No component is removed.
- Data in main: the cubic water record (alpha, group counts), ice Ih as a crystal, the package's revision. Ethanol stays in the test overlay until W6; W3b's ethanol gates run on `pilot_aqueous`.

### N10. What is deleted at the end

From the engine: `solveWet`, `PartialPressureClosure`, `wetCoexistence`, `waterBoiling`, `waterFreezing`, `freezingAtVolume`, `waterTriplePoint`, `FreeWaterModel`, `FreeWaterIce`, `Classification.FREE_WATER`, `PhaseKind.FREE_WATER`, `SEPARATE_FREE_WATER`, the Dalton helpers of `SteamVirial`. The flowsheet's callers (`SheetCompounds`, `SimulatorRunner`, `ProcessSimulatorScreen`) build the same network model and move with it. The V3 column has its own water code and is W5's. A caller found outside these keeps the rule alive and is reported, not patched.

## 4. Steps

Each step ends in a commit and a filtered test run of the classes it can reach. Steps 3 to 5 compile at each commit; their tests run at the end of step 5.

1. Engine and kernel: a crystal beside two liquids and beside three fluid phases; N8; the bubble-point cost (783 evaluations); the UV restart on a narrow-boiling hydrocarbon binary. Gates: ice under n-hexane over water at -5 C and 1 atm answers ice, two liquids and gas; kernel evaluations per answer no higher than W3a's table; one timing pair for N8 (60 s warm-up, 60 s measured, run alone).
2. Data: the cubic water record alone goes into the main data (`properties/cubic_water.json`); the test overlay reads it. The derived contract stands beside `PhaseContract.forNetworkPackage`. Changed 2026-11-11: `icheme:refinery` is not edited here, because the network reads that record directly (`MaterialPresets` refuses a network package with a Water component, `forNetworkPackage` appends Water itself, `FluidThermodynamics` indexes crystals on the hydrocarbon list, and the package's revision is in the network's identity, so an edit restates saved worlds). A sibling package was built and dropped: it broke 14 tests that count or rename the refinery's packages.
3. `HydrocarbonModel`, `State`, `adopt`, `NetworkPhaseEngine` (N1, N2, N6); with them the refinery package's Water component, ice Ih in its crystals and its revision, and the four readers named in step 2.
4. `PhaseLayout` and the trace rules (N3, N4).
5. `PassiveStepSolver`, `PhaseDraw`, `InletPhase`, `OpenVessel`, `InventoryEquilibrium`, `VesselCrystals`, `NetworkCrystals`, `ConservativeTransport`, transport (N5, N7); the tests moved to the phase accessors.
6. Runtime: checkpoint format 47, the restate, presentation rows, labels, the flowsheet callers.
7. N10's deletions; the code guide chapters (equilibrium-engine, fluid-science, thermo-materials); the final lane, once.

## 5. Gates

- New, on `pilot_aqueous`: a closed tank of water, n-hexane and nitrogen at 25 C holds the section 1 solubilities and conserves amounts and energy to the ledger's tolerance; an ethanol-water tank heated through its bubble point stays one liquid; ethanol added to water under hexane moves between the liquids without a hold; CO2 over water dissolves to its Henry value; ice under oil over water freezes and melts at a refresh; a single liquid crossing half water relabels without a phase event.
- Existing: `NetworkPhaseEngineTest`, the fluid GameTests (fresh `fluidGameTestRunId`), chain-100, the 33 junction lines and `MIXED_GAS_COST`. Every reference that moves is re-recorded once, in the final lane, with its old and new value in the result.
- Holds: one full campaign (1000 seeds, 8 workers) on the final code is the new baseline; new holds are listed by class, at most the two smallest traced.
- Cost, by counters from the campaign and one timing run alone: a dry island's kernel evaluations within 2 % of the baseline lane; its time per evaluation within 10 % where the library gives every gas pair, and measured against section 8's estimate (1.7 times at 12 compounds with 3 gases) where it does not; a wet three-phase island's time per accepted step at most 1.5 times today's. A miss comes back as a decision with the counters, not as an accepted result.
- Saves: every corpus image under `src/test/resources/compat/` loads; a format-46 state is dropped and rebuilt; the restate books an energy difference and removes no component.

## 6. Risks and what is left out

- Size: `PassiveStepSolver` is 5,465 lines and 168 test files touch the model. The steps are ordered so that the engine and kernel (1, 2) land and are measured before the network changes.
- Dry references move only through the gas pairs of section 8's option B.
- The single-liquid label (half water) switches a boot drum's hydrocarbon outlet between "any level" and "above the weir" for a water-miscible liquid. A standpipe rule on the level, for any liquid, would remove the label from the physics; it changes how an oil-only boot drum drains today, so it is an owner option, not part of W3b.
- A fourth fluid phase, electrolytes and hydrates stay capability holds.
- Saturated steam above 250 C keeps its 2 to 13 % latent-heat error (plan M2).
- Not in W3b: the still (W4), the V3 column (W5), ethanol in the main data and its creative preset (W6), merge preparation.

## 7. Result of steps 1 and 2 (2026-11-11)

Built by a sub-thread: 1a `e203997e2`, 1c `93c98f2af`, 1b `f148d1eac`, 2 `af1b7a998`. Its report is `tmp/w3b-engine-kernel/W3B_STEP12_REPORT.md` (local).

| Gate | Measured | Bound |
|---|---|---|
| Water, n-hexane, nitrogen at -5 C, 1 atm | ice, hexane liquid, gas | ice, liquid, gas |
| Ice onset beside hexane, 10 % ethanol by mass of the water | -3.30 C (the water-rich liquid keeps 8.2 % ethanol) | -5.5 to -3.0 C |
| Below that onset | ice beside two liquids and gas | answers |
| Ethanol, water, n-hexane, nitrogen at 253 to 263 K | ice beside three fluid phases, every component conserved | answers, conserves |
| PH and UV round trips | 57 answers within 1e-6 N R T | 1e-6 N R T |
| Kernel evaluations per TP answer, W3a's five cases | 87, 117, 52, 282, 120 | 87, 117, 52, 282, 120 |
| Ternary at its bubble point | 515 (783 before) | lower |
| W3a's gate values under the new pair form | unchanged to their printed digits | W3a's bounds |

Kernel time per evaluation (ns; each run alone, 60 s warm-up, 60 s measured):

| List | Before | After |
|---|---|---|
| Network refinery, 45 compounds, constant pairs | 305 | 324 (+6 %) |
| Derived dry list, 44 compounds | 5344 | 1531 |
| Wet list, 47 compounds, a wet phase | 6125 | 5420 |
| The same list, a water-free phase | 5901 | 3963 |

- The kernel has one pair list and one rule (`classicalTau` holds the conversion); its composition derivatives are O(n^2 (1 + polar)), O(n^3) before. `Mixing` is removed.
- What N8's form moved, above 0.1 %: the three values listed under N8. No derived-pair fit moved (each fit is a binary with one pair).
- The UV restart fires 3 to 5 times in 41 states on four narrow-boiling hydrocarbon binaries; without it 16 of 164 states end in the critical band. It stays.
- Runs: 1b 185 classes, 1244 tests; `columnGates` 62 classes, 397 tests; `holdGates` 12 classes, 131 tests; all pass but `MaterialPresetsTest` (the override example's methane record lacked W2a's two sub-records; fixed after, 5 tests pass). One whole quick `test` run by a filter mistake (2381 tests, on data since dropped). The fluid GameTests and the final lane have not run.

For step 3 on: a derived kernel on the 45-compound list costs 5 to 12 times the refinery's constant-pair kernel today (1531 to 3963 ns against 324). The 2 % and 10 % dry bounds of section 5 cannot hold on a derived list as measured; step 3 starts with this cost (which pairs must be temperature-dependent, what a prepared temperature can hold) before the network moves. Also open: the derived contract flags no water chemistry (ammonia with water); the bubble point's cost is now mostly the three-product audit (297 of 515); four probes under `tools/` still name `PengRobinsonKernel.Mixing`.

## 8. Kernel cost on island-size lists, and the size of the derived pairs (2026-11-11)

Owner: "Measure first". Probes: `tools/aqueous-solvents/probes/` (`IslandTime`, `PairSizes`). Each timing run alone, 60 s warm-up, 60 s measured, 330 K and 2 bar; the sublists are evenly spaced over the refinery list's order.

| Sublist | Pairs the library gives | Today's kernel | Derived, water-free phase | Derived, wet phase |
|---|---|---|---|---|
| 12 compounds | 7 of 66 | 128 ns | 414 ns (3.2 times) | 566 ns (4.4 times) |
| 24 compounds | 38 of 276 | 284 ns | 1720 ns (6.1 times) | 1749 ns (6.2 times) |

The refinery list's 990 pairs by source: library 76, hydrogen (zero) 44, activity 20, E-PPR78 713, missing 137.

**Expected.** An interaction coefficient between two non-polar compounds is of order 0.01 to 0.2 and changes slowly with temperature. A dry island should cost what it costs today.

**What the code does.**
- Cost follows the number of pairs with a value. Today most pairs are zero and cost nothing; derived, every pair has one. The dry bounds of section 5 (2 % and 10 %) do not hold.
- The derived pairs are not small: the median of the largest |k| over 250 to 600 K is 0.029; 550 of 713 are at or above 0.01, 436 at or above 0.02. Dropping small ones removes a fifth of the work.
- The E-PPR78 temperature law (a power of 298.15/T per group pair) leaves its fitted range inside the network's. |k| is above 0.3 in 20 pairs at 150 K, none at 298 K, 4 at 450 K and 48 at 700 K; above 1 in 10 pairs at 150 K and 7 at 700 K. Ethylene with isobutene: -0.19 at 298 K, -77 at 450 K, -5.9e4 at 700 K. Oxygen with p-xylene: 0.22 at 298 K, 0.80 at 250 K, 500 at 150 K. The network runs from about 90 K to 800 K. W2b's gates read 298 to 373 K and did not see it.

**Proposal: options for the pairs between two non-polar compounds** (the owner's choice; the polar pairs, Henry pairs and homomorph pairs stay derived in all three).

- **A. Library only.** A non-polar pair the library does not give is zero, as today. Dry islands keep today's numbers and cost; no dry reference moves. Gases in hydrocarbons stay at today's accuracy (in n-hexane 1.02 to 1.20 times measured where the library has the pair, 1.17 to 2.07 where it has not).
- **B. Gas pairs as constants (recommended).** Where the library is silent, a pair of a gas (a compound with a Henry constant in water: 8 of them) with a non-polar compound takes its E-PPR78 value at 298.15 K, constant in temperature. Every other non-polar pair is zero. This keeps the gain where it was measured (gases in n-hexane 1.05 to 1.32 times measured), bounds every value, and adds about g (n - g) pairs for g gases among n compounds: about 25 on a 12-compound island with 3 gases, an estimated 1.7 times today's kernel time there from the per-pair cost above (not measured). Dry references move.
- **C. Every pair, temperature held outside a window.** All 713 pairs with the E-PPR78 law inside a window and held outside it. 3 to 6 times today's kernel time unless the kernel gets a dense-row path; the window is a new rule per table, and oxygen with the xylenes is already 0.8 at 250 K.

**Owner, 2026-11-11: B.** The gases are the 8 compounds with a Henry constant (carbon dioxide, carbon monoxide, hydrogen, hydrogen sulfide, oxygen, nitrogen, methane, ethane); hydrogen's pairs stay zero by the rule of 2026-11-04. It is built first in the network work (step 2b): `DerivedPairs` writes these pairs as constants, the `EPPR78` source no longer carries a temperature law, the gates of W2b and W3a that move are listed old and new, and the timing of section 8 is repeated on the same sublists.

With A or B the E-PPR78 temperature law leaves `DerivedPairs` (the unbounded form is not used anywhere), and the plan's M4 is restated. With B or C the kernel's pair loops (ten passes over the pair list per evaluation, about 4 to 5.5 ns a pair) are worth one pass over row sums first.

## 9. Result of step 2b, and what it decided (2026-11-11)

Commit `aaa87375c`; 40 test classes, 199 tests pass. `DerivedPairs` is `derived-pairs-r2`: library first; else a pair with a gas or a polar compound that falls through to E-PPR78 takes its value at 298.15 K as a constant; two non-polar non-gases are zero. No W2b or W3a gate value moved to its printed digits. The kernel forms its pair sums in one pass.

Kernel time per evaluation after it (ns; each run alone, 60 s and 60 s):

| Sublist | Today | Derived, no water in the list | Derived, water-free phase of a wet list | Derived, wet phase |
|---|---|---|---|---|
| 12 compounds | 130 | 139 (1.07 times) | 355 (2.7 times) | 399 |
| 24 compounds | 164 | 884 (5.4 times) | 939 | 993 |

- A list without a polar compound costs today's. The cost is in the local-composition cells: a phase of a list that holds water forms water's cells even when it holds no water, and the 24-compound sublist holds sulfur dioxide, methyl mercaptan and hydrogen sulfide, which take activity fits with every hydrocarbon the library does not give (32 such pairs).
- The two-temperature lines leave the physical range at the network's ends: at 1 atm the kernel refuses a root (negative mixture attraction) for water with n-hexane from 750 K, water with nitrogen from 700 K, water with a crude cut at 800 K; water and ethanol's energy changes sign below about 230 K.

Decided by the orchestrator (owner: "auto proceed if nothing major breaks"):
- **The lines are held past their fitted ends**, the slope fading to zero over 50 K, value and slope continuous (step 2c). With it none of the nine pairs tested refuses from 90 to 800 K, and nothing moves between 298.15 and 373.15 K.
- **An island's phase model holds water only when water can be in the island** (its mask). The conserved basis and the saved forms keep water last. N1 is read this way.
- **A baseline is recorded before the network changes**: three fixed scenarios (dry, wet three-phase, dry with a polar refinery compound), counters and time per accepted step, run again after step 5. A wet island above 1.5 times or a dry one above 1.1 comes back as a decision. Skipping the cells of absent compounds in the kernel (estimated 200 ns at 12 compounds, about a day) is decided from those numbers.
- **Open, the owner's:** which compounds take activity fits with hydrocarbons. Today every compound with a polar group does. Limiting them to compounds that name a homomorph (the water-miscible organics, ethanol first) would give hydrogen sulfide, sulfur dioxide, the mercaptans and ammonia constant pairs with hydrocarbons (library, else E-PPR78 at 298.15 K) and today's cost on sour dry islands; their pairs with water would stay as they are.
