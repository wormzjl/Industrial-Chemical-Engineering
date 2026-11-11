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

## 10. Step cost after steps 3 to 5, main code (2026-11-11)

Baseline `48b4fb298` (three chains: dry, wet three-phase, dry with polar refinery compounds), measured again on the main code of steps 3 to 5 (`58376e618`, tests not yet migrated). Each timing run alone, 60 s warm-up, 60 s measured.

| Scenario | ms per accepted step, before | After | Ratio | Bound |
|---|---|---|---|---|
| Dry | 0.777 | 0.831 | 1.07 | 1.1 |
| Wet | 0.923 | 2.305 | 2.50 | 1.5 |
| Polar (H2S, methyl mercaptan) | 1.021 | 1.680 | 1.64 | 1.1 |

- Steps and Newton iterations are the same in all three; end states agree to 4 digits.
- Dry and polar: the same kernel evaluation counts. The polar cost is per evaluation: the activity pairs of hydrogen sulfide and the mercaptan put the local-composition excess in every evaluation (profile: 18 % `correctExcess`, 8 % the excess's second temperature derivative, 3 % `presentOmega`).
- Wet: kernel evaluations 5769 to 11554 and state calls 2868 to 3516, with the excess on every water pair as well.

The bounds are broken for the wet and the polar scenario. Decided by the orchestrator: the tests come first; then an optimisation step 5b in the structure (the second temperature derivative only when read; sparse rows in `correctExcess`; absent centres' cells skipped; a one-component phase on a pure-compound evaluation prepared once per T and P), measured item by item on the same three scenarios. The polar scenario also depends on the owner's open choice of which compounds take activity fits with hydrocarbons (section 9).

## 11. Defect found in the test migration: cancellation in the kernel's sparse sums (2026-11-11)

**Expected.** In pure water an absent heavy crude cut has a large, finite ln phi (it does not dissolve), smooth in temperature.

**What the code does.** The kernel's sparse-row form (N8) builds each centre's local-composition sum as `b` plus corrections, `D_i = b + sum_j w_j (G_ji - 1)`. For water around the heaviest cut the derived energy is 512.8 kJ/mol at 298 K, so `G` is 9e-16; with water holding nearly all of `b` the sum is `b - b (1 - G)`, rounding noise. The cut's ln phi at 1 atm reads 103 at 298.15 K, 150 at 298.2 K, -18 at 290 K, NaN at 285 K. The ice anchor then fails, and every wet refinery list with that cut fails when its engine is built (about 20 of the 83 failures of the first filtered run). The literature form of N8 did not have this subtraction; the sparse-row form introduced it.

**Decided by the orchestrator** (numerics, no physics change): the classical part of each centre's sums is formed directly over the compounds that are not its cell partners, never as `b` minus the partners' weights; the order stays O(n + cells + n P). No cap on the derived energies: a floor on `G` leaves a relative error near 1e-3 in the same subtraction. Gates: a finite, smooth ln phi of the absent cut from 90 to 800 K; agreement to 1e-12 with the plain sums in extended precision for four phases; a dry phase bit for bit unchanged. A guard on the exponent is added only if that gate finds a nonfinite value.

## 12. Engine defects the migrated network tests exposed (2026-11-11)

Status: kernel fix of section 11 committed (`fa0a292f3`); the smooth hold over 150 K committed (`710e9ef51`, `derived-pairs-r4`; heat-capacity step at the fitted ends 7.7e-5 J/mol/K). Decided by the orchestrator; none of the fixes below is built at the time of writing.

| Case | Expected | What the code does | Decision |
|---|---|---|---|
| TP, wet crude (water 0.01 mol/mol), 393 K, 5 bar | a converged split | compositions converge to 4e-16, a nearly absent slot's fraction never settles, 1000 iterations | a slot is absent by the convex system's own presence row, not by its fraction's movement; fix now |
| PH and UV, heavy cuts with water, 1 to 2 bar | three phases on water's boiling step, the steam fraction set by the enthalpy | the step is 3e-9 K wide; the search brackets it and refuses | the binary step rule generalised: a bracketed jump between two answers that differ by one phase leaving and one entering is answered on the step by the lever rule; fix now |
| TP, nitrogen with 0.1 mol water per mol, 900 K | a near-ideal gas | refused: the mixture attraction is negative (the Henry pair's energy, 13.9 kJ/mol held, against a shrinking a/b) | each cell's energy bounded by its centre's own term, below |
| Nitrogen with ethane, 125 K, 3 MPa | two liquids and a gas | a hold, as today (a list without a polar compound takes the two-slot path) | open: hand an unstable two-slot product to the three-slot system |
| Water's share below saturation pressure | continuous slope in P | held at p_sat, dh/dP 9 % off at 350 K, 10 kPa | continued linearly in P, in step 5b |

**The energy bound.** For the cell "j around centre i": `E_ji(T) <= Lambda a_i(T)/b_i`, as a smooth minimum (width 5 % of the bound) so the enthalpy stays continuous. For a classical pair this is k_ij <= 1: the unlike attraction is never negative. A centre's local excess is a weighted mean of its cells' energies, so g^E/Lambda never exceeds the linear term and the mixture attraction is nonnegative at every composition and temperature, by construction. It replaces a guard on the result, keeps the Henry pairs' temperature dependence, and bounds the fits for water around heavy cuts (512.8 kJ/mol). Built only if a probe shows that no gate pair's cell exceeds 0.8 of the bound inside 298.15 to 373.15 K; else it comes back with the table. Gates: wet nitrogen, nitrogen with methane and flue gas from 300 K to the ceiling at 1 atm and 10 MPa.

## 13. The energy bound withdrawn; gas and water pairs become classical (2026-11-11)

The probe section 12 asked for (`tmp/w3b-network/runs/bound-probe.txt`, local): between 298.15 and 373.15 K, 5 of the pilot list's 24 cells and 34 of the refinery list's 116 exceed 0.8 of `Lambda a_i/b_i`. Water around nitrogen is 5.86 times the bound, around carbon monoxide 4.83, oxygen 3.52, methane 2.63, ethane 1.91; from 90 to 900 K 66 cells exceed it, the largest 46.9 times.

**Why.** A gas's solubility in water is carried by the cell on the gas's own centre, which must be several times the gas's cohesive term to reach its Henry constant. The same cell makes a hot gas's mixture attraction negative. No bound keeps the first and prevents the second. The bound of section 12 is withdrawn.

**Decided by the orchestrator; owner, 2026-11-11: "Accept".** A gas with water (source `HENRY`) is a classical pair with k linear in temperature, fitted at 298.15 K and at 373.15 K to the gas's Henry constant through the kernel, held past those ends like every line (`classicalLinear`, as the homomorph pairs). The solubility stays exact at both temperatures; the pair costs no local-composition cell; a k of at most 1 gives a nonnegative mixture attraction by construction (the aqueous k of these gases is negative). Known weakness, to be measured: one k serves the aqueous liquid and the gas, so the gas's water content at high pressure is less accurate. Plan M4 and section 5 of the parent plan change accordingly once the numbers are in.

Built since section 12 (uncommitted at the time of writing): the presence row (wet crude at 393 K and 5 bar converges; TP, PH and UV agree), the step rule for PH and UV, three-slot slots evaluated on their own kind's root (a cut with 30 mol water per mol at 369 K and 1 bar answered two liquids and missed the vapour), V3 ignoring water's ice. Also decided: a carried crystal's stand-in takes a liquid slot of its own (the merge of two dense products was the one-liquid-slot workaround), with CO2's single-liquid states unchanged; a network model supplies water itself for a package that does not name it, and no other package record is edited.

## 14. Decisions in force for steps 3 to 5 (2026-11-11, kept current)

The sub-thread's questions and the orchestrator's answers, in one place. Each was also sent as a message.

1. **Kernel sums (section 11):** the classical part formed directly, no data cap. Built (`fa0a292f3`).
2. **TP, a nearly absent slot:** absent by its presence row. Built, uncommitted.
3. **PH and UV on a boiling step:** the lever rule between the bracket's ends, one rule for any number of compounds. Built, uncommitted.
4. **Hot wet gas:** the energy bound is withdrawn. A gas with water is a classical pair, k linear in T, fitted to the Henry constant at 298.15 K and 373.15 K, held past the ends; the NRTL Henry fit goes; the derivation's revision is bumped. The owner accepted it. Report: the Henry ratio at 273.15, 323.15, 423.15, 473.15 K for nitrogen, methane, carbon dioxide; the gas's water content at 298.15 and 373.15 K, 1 atm and 10 MPa, old and new; carbon dioxide in water at 5 MPa; the wet scenario's kernel time. Gates: wet nitrogen, nitrogen with methane and flue gas answer as one gas from 300 K to the ceiling at 1 atm and 10 MPa; every remaining NRTL pair of the refinery-with-water list scans from 90 K to the ceiling without a kernel refusal (a refusal is reported, not patched).
5. **A carried crystal** (ice in the oil at 263 K): its stand-in takes a liquid slot of its own; the merge of two dense products goes, for every crystal. Limits: a CO2 state with one dense product today is bit for bit unchanged; every CO2 pin that moves is listed; more than ten moved pins, or a new hold, comes back first. Two liquids without water: the denser takes the `AQUEOUS` slot. `condensedShareOnly` and the vapour-only admission to a stand-in liquid are restated for either slot, not deleted. A dry list that needs three fluid slots under carrying is reported, not built.
6. **Water for a package that does not name it:** the network model supplies the catalog's cubic water and its ice itself. No package record other than the refinery's is edited to give it water, and any such edit is reverted. If the refinery's Water component changes anything outside the network (what V3, the column operation, the flowsheet or the presets see, the package identity), the refinery takes the implicit way too and its record is restored.
7. **A vapour missed just above a three-phase point:** a vapour-kind stability trial that lands on a liquid-like root is repeated on the vapour root; no slot changes root. Gates: the band from the three-phase point to 5 K above it answers a vapour; the water tank with a crude trace at 350 K still answers; W3a's evaluation counts rise by at most 10 %.
8. **Not in these steps:** nitrogen with ethane at 125 K (an unstable two-slot product handed to the three-slot system); the kernel optimisation and water's share below saturation pressure (step 5b, after the tests pass); which compounds take activity fits with hydrocarbons (the owner's).
9. **Hot steam with hydrocarbons** (15 water-hydrocarbon activity pairs refused in the kernel from 780 K: the cell on water's centre is held flat while water's own term falls with temperature). A local-composition cell's energy fades to zero between 0.75 T* and T*, T* the highest critical temperature among the pair's polar compounds (water 647.096 K), by a quintic that keeps the energy and its first two temperature derivatives continuous. Above T* the pair is classical, so the mixture attraction is nonnegative at every composition. Below 0.75 T* (485 K for water) nothing moves. Activity cells only; the classical linear pairs keep their hold. Built since item 8 was written: items 4 to 7 (the refinery record is back at r3 with water supplied by the model; Henry pairs with k(T), nitrogen -0.636 at 298 K and -0.387 at 373 K, `derived-pairs-r5`; ice under oil answers gas, oil and supercooled water; 1344 dry CO2 carried states bit for bit unchanged).
   - **Corrected the same day.** The window 0.75 T* to T* (162 K for water) put the fade's curvature into the heat capacity: -171 J/mol/K for water with ethanol at 20 to 30 MPa (refused from 599 to 620 K at 30 MPa), -326 J/mol/K in a pilot state at 630 K and 25 MPa. The window is now the fitted end (373.15 K) to 1.25 T* (808.9 K for water): the curvature is about seven times smaller (-22.5 and -35.5 J/mol/K measured), every state is valid and the scan still has no refusal; above 780 K the factor is below 0.01. Cost: liquids between 373 and 485 K move (water in n-hexane at 450 K 0.0101 to 0.0128, n-hexane in water 9.11e-5 to 8.88e-5). Nothing moves below 373.15 K. Water now dissolves in hot oil (0.26 % of the water at 600 K and 5 bar); two network pins that said "all steam" are re-recorded.

## 15. Result of steps 3 to 5 (2026-11-11)

Commits: `58376e618` (main code), `9c3a23ffb`, `fa0a292f3`, `402b1bf86`, `65828c650` (test migration, engine fixes, section 14 items 4 to 9). The sub-thread's report and lists are in `tmp/w3b-network/` (local). Checked by the orchestrator: no package record differs from main; facts schema 21 and checkpoint format 46 are unchanged; the refinery package is at its earlier revision.

**State of the tests.** Filtered runs: thermo, material and the named network classes 431 tests, 2 failing; network, runtime, flowsheet and client groups 1948 tests, 28 failing. Of the 30, 9 belong to step 6 (checkpoint decode, restate, flowsheet callers) and 21 are open engine and network defects: pumps (6), port priority (3), inlet phase (2), a bulk three-phase transfer that leaves a component behind, a solids-only vessel, a kettle's boil-up (8.14 against 0.443 kg/s), a turbine exhaust that is not wet, the melting line on a UV step (5 mK), a stand-in left after a refresh, three tolerance pins. Steps 3 to 5 are not done until these pass.

| Gate (section 5) | Measured |
|---|---|
| n-Hexane in water, water in n-hexane, 25 C | 2.095e-6, 9.48e-4 |
| CO2 over water | 1.001 of its Henry value |
| Ethanol and water heated through the bubble point | one liquid |
| Ethanol added to water under hexane | moves between the liquids, no hold |
| Ice under oil over water at 263 K | gas, oil, supercooled water; melts on the lever step |
| A dry island's layout | 42 unknowns, as today |
| A single liquid crossing half water | relabels without a phase event |

**Numbers.**
- Engine cost, kernel evaluations per TP answer, before and after: dry 87, 87; water with n-hexane 109, 119; ethanol with water 52, 52; the ternary 282, 287; its bubble point 509, 519; water with a cut 112, 117.
- Gas in water against IAPWS G7-04 at 273, 323, 423, 473 K (exact at 298 and 373 K), old form then new: nitrogen 1.26, 0.92, 1.21, 1.27 then 1.11, 0.97, 1.01, 0.85; methane 1.27, 0.92, 1.22, 1.32 then 1.16, 0.96, 1.04, 0.87; carbon dioxide 1.20, 0.94, 1.16, 1.23 then 1.11, 0.97, 1.04, 0.96. Carbon dioxide in water at 5 MPa: 17 % high before, 11 % now.
- Water content of nitrogen at 10 MPa: 1.118e-3 before, 1.180e-3 now at 298 K (ideal 3.17e-4); 1.787e-2, 1.853e-2 at 373 K. At 1 atm within 1.5 % of ideal in both forms.
- The fade: water with ethanol at 3 bar unchanged (383.63 K), at 10 bar 425.97 to 426.16 K; water in n-hexane at 450 K 0.0101 to 0.0128. Water dissolves in hot oil: 0.36 mol of 30 at 550 K, 11.9 at 700 K and 5 bar.
- 1344 dry CO2 carried states bit for bit unchanged.

**Deviations and limits.**
- Facts and checkpoints still write the earlier fields through an adapter (`legacySplit`): compounds in any liquid, water in any liquid, water in the gas. Amounts are exact; on load the two come back as two liquids, and a gas dissolved in water alone decodes as an oil of pure gas and is refused ("Liquid root absent"). Step 6 rebuilds the state from the amounts instead.
- A network on a package with a reference spine holds no water (test package `pilot_cryogenic`); its wet tests were moved to the refinery or removed. To be reported: which main packages this touches.
- A dry list with gas and two dense products under carrying still gets today's answer (two fluid slots).
- Tests removed, each with its reason in the lists file: the bit-for-bit pin of the old dry path, the partial-pressure box, two free-water rule tests, the virial steam's network state, the crude-trace fold in a water-full vessel, one pilot refresh.

Next, in the same sub-thread: the 21 defects group by group (conservation first), then step 5b; steps 6 and 7 after that.

## 16. Stage A (the 21 defects), groups 1 to 3, and the pump suction floor (2026-11-11)

Committed: `e5391b412` (a bottom port's pump left its last 6.8 g of water as vapour at 282 K: a phase the step's start holds and the seed has lost is now pinned, so the port draws it whole), `ba002f4a6` (a vessel holding only solids has no phase to add), `dc6238dc4` (the aqueous liquid's vanishing share is judged by moles as well as by volume, as on main; the volume-only form held condensate as supersaturated vapour, a 2 K sawtooth over 6 slices). Filtered run: 2039 tests, 21 failing, 7 fixed, none new.

**The pump suction floor: the owner's choice.**

- Expected: a pump cannot pull its suction below the pressure at which the liquid it draws forms vapour. For water that is its saturation pressure (3.2 kPa at 25 C). A liquid in equilibrium with its own gas (a separator's oil) is at that pressure already.
- What the code does: on main, water took its saturation pressure everywhere; a hydrocarbon liquid took its bubble pressure, or its vessel's own pressure when the vessel holds gas (owner decision D16 C). Water now dissolves nitrogen and air, so by equilibrium a blanketed water tank is at its bubble pressure too, and its pump keeps only its static head: 9.8 kPa under 1 m of water against about 17 kPa needed at 10 L/s. Real pumps use the vapour pressure, because dissolved gas comes out slowly; an equilibrium model has no rate.
- Options:
  - **Today's behaviour, by label (built provisionally, in one function).** The oil slot keeps D16 C; the aqueous liquid takes the bubble pressure of its condensable compounds (those above their critical temperature left out). No pump in an existing world changes.
  - **One rule, dissolved gases never count.** Any liquid takes the bubble pressure without the compounds above their critical temperature. Replaces D16 C: an oil under a nitrogen blanket gains suction margin; a live crude ignores its dissolved methane and may then free gas at the pump.
  - **One rule, equilibrium.** Any liquid takes its full bubble pressure. Pumps on blanketed or open water tanks keep only their static head.
- Whatever the rule, the floor is read from the supplier directly as well as through a suction walk (a pump drawing straight from its tank had no floor: NPSHA was NaN).

## 17. Pump suction rules lifted (owner, 2026-11-11)

Owner on section 16's question: "The pump cavitation system has been added, this can be lifted now"; on the list below: "All four, measured"; then: "Give pump a small bulletproof zone (without damaging the pump) with minor gas content". The options of section 16 are closed by this section.

**What stays** (the cavitation system is built on it): the suction limit row `PUMP_SUCTION_LIMIT` with the breakdown NPSH (D59 (4), D20 Q11 A); the vapour pressure of the liquid drawn at its own enthalpy (D14, first half of D16 C); limit and lock facts read once per solve (D16 A); the limit never above what the supplier gives (D17).

**What goes.**

| Rule | How |
|---|---|
| Water's own saturation curve and `BalanceNet.waterFloor` (D59 (4)) | Now: water is a component, one function for every liquid. The starved water suction of `PumpCavitationTest` must still stand at its own-enthalpy value (about 3174 Pa) with no vapour at the junction (the singular Jacobian recorded at `waterFloor`). |
| A pump drawing straight from its tank has no limit | Now: the limit is read from the pump's own first node. Such pumps get an NPSH reading, cavitation and wear. |
| Gas beside the liquid puts the limit at the vessel's pressure (second half of D16 C), and section 16's split by liquid label | Measured: forms below. |
| Vapour lock, a pump closed for the whole solve at no suction head (D15) | Measured, after the form is chosen. |

**The clean zone (owner).** A centrifugal pump whose suction holds vapour below half the Turpin onset share (1.05 % by volume at 1 bar, 2.6 % at 2.5 bar, 9 % at 10 bar; today's `PumpHealth.GAS_RATIO`, where it is already silent and shows no `GAS` word) takes no harm: no wear, no status word, no sound, and it is not counted as cavitation. Its head there is the gas law's, at least 0.99 of the gas-free head (the law is not changed). One constant states the zone for the limit, the status word and the sound.

**Two pressures, one function.** `releasePressure(liquid, share)`: the lowest pressure at which the liquid drawn, at its own enthalpy, holds a vapour volume share of at most `share` (the bisection of `bubblePressure` with the test "any vapour" replaced by the share).

- Zone edge E: `share` = half the onset at that pressure. A boiling liquid has E at its bubble pressure (its vapour share jumps there); nitrogen-saturated water at 1 atm and 25 C has E near 69 kPa (hand estimate from Henry's law).
- Vapour pressure p_v, the NPSH reference for the limit, the wear and the page: the bubble pressure of the liquid without its compounds above their critical temperature (the provisional `aqueousFloor`, for every liquid, at its own enthalpy). To report beside it: the pressure at a vapour share of one half, which needs no critical-temperature test (carbon dioxide dissolved in water at 25 C counts as vapour pressure under the first and as gas under the second).

**Forms on the 200-seed quick check** (three, vapour lock kept):

- Form 0: section 16's provisional rule (D16 C for oil, condensables for the aqueous liquid).
- Form G (expected choice): limit = the greater of E and `p_v + 0.85 rho g NPSH3(Q)`. A pump pulls freely through the clean zone and starves at its edge or at breakdown, at a steady point. Wear reads sigma against p_v, so released gas never wears: blanketed water starved at 69 kPa has 6.7 m over p_v, sigma about 4 for the default pump, at inception.
- Form T: limit = `p_v + 0.85 rho g NPSH3(Q)` alone; released gas is left to the gas law, which reads the suction once per solve (a step-to-step swing is the risk).

Then, on the chosen form: vapour lock kept against removed. The removals stand only if step 7's campaign shows no new hold class against the baseline. Cases to report under each form (limit, NPSHA, sigma): water under nitrogen at 1 atm; water under carbon dioxide at 1 atm and 25 C; a dead oil under nitrogen; a separator liquid with methane at 10 bar and 310 K; LNG at its bubble point; a propane drum.

Effect on existing worlds: a pump bolted to its tank starts to read NPSH and can cavitate and wear; a pump on a blanketed hydrocarbon tank gains suction margin; modes are state and health keeps its format.

## 18. Stage A group 4 and the kettle's port rule (2026-11-11)

Group 4 committed by the sub-thread (`ae2a457c6`; filtered run 2039 tests, 14 failing, 7 fixed, none new): section 16's provisional floor (form 0 of section 17); a junction's split no longer leaves each component's rounding remainder in the oil slot (a 1e-15 mol nitrogen "liquid" with no liquid root refused the condensate: the cooling tower's suction junction stood supersaturated, and the turbine's 342 K exhaust at 1.89 bar was a vapour 50 K below its dew point; it is now wet at 212 kPa and 395.3 K with 4.7 % liquid); the tower basin's water is accepted with its dissolved nitrogen (the loop runs at 19.94 kg/s).

Suction head available at 10 L/s under 1 m of liquid (NPSH3 1.68 m), by section 16's three rules (today's by label / equilibrium / gases left out): water under nitrogen 11.04 / 1.00 / 11.04 m; a dead oil under nitrogen 1.00 / 1.00 / 12.92 m; a separator liquid at 10 bar and 310 K 1.00 / 1.00 / 135.06 m. Pulled to the gases-left-out floor plus breakdown, the junction holds 8.8 %, 49 % and 95 % vapour by volume, and the pump's head share does not see it (it reads the supplier's stream). Consequence for section 17: form T is compared only with the head share read on the suction junction's own vapour share; otherwise it is dropped.

**The kettle (D11, the port's segment rule).**

- Expected: 1 MW boils 0.443 kg/s of water. A top nozzle cannot reach liquid while vapour space remains.
- What the code does: once the vent's hydraulic flow exceeds the gas capacity of the step (start gas plus boil-up), the gas is pinned and the liquid carries the rest; the space then flashes and the gas regrows, so about 21 kg/s of liquid leaves through the top port with 0.48 m3 of vapour above the pool (8.14 kg/s reported).
- Decided (orchestrator; no owner rule moves): a phase the end state still holds is the marginal phase; a phase drawn whole is pinned at what the step could supply, its start holdup plus what forms or arrives within the step. The boil-up was already in that capacity (heat exchanger batch); the feed joins it. Without the feed (measured) a fed tank drains its water only every few slices (86 and 676 boundary reopens). A special case for top ports was rejected.
- Pass conditions: the kettle at 0.443 kg/s; the two fed-drain tests steady; port ledgers closed at 1e-12; no new failure.

**Fed water under the port rule (2026-11-11).** Built without "remaining > capacity": the kettle passes and the port ledgers close (2e-15 to 8e-14), but the two fed-drain tests fail (24 and 104 boundary reopens against 1 and 1). Once a step draws the start's water whole, the next step starts with no liquid water; the 0.002 kg/s fed within the step could be neither pinned (the start gives no composition to draw it at) nor added as a liquid, and the accepted end states held it as vapour about 2,000 times over saturation (12.9 kPa of water at 194 K against 0.15 Pa) while the tank cooled from 298 to 187 K. Decided: a phase that arrives within the step counts as one the step started with. Pin condition: the seed no longer holds the phase, its capacity (start holdup plus arrivals and boil-up) is finite and positive, and a later phase can take the rest. A pinned phase's stream is the total the step could supply, the start's phase and the arrival's together; the arrival's alone where the start holds none. The supersaturated accepted states are a second defect, to be traced in the phase check whatever the port does.

## 19. Section 17's six cases, and the vapour pressure definition (2026-11-11)

Section 17's removals 1 and 2, the clean zone and the form switch are committed (`e3238449d`); groups 6 and 7 as `db3f14d3c` and `754732f66`. Measured by the sub-thread (10 L/s, 1 m of liquid over the pump, NPSH3 1.68 m; limit under forms 0 / G / T):

| Case | p_v, condensables | p at vapour share 1/2 | Zone edge E | Limit 0 / G / T |
|---|---|---|---|---|
| Water under nitrogen, 1 atm, 298 K | 3.18 kPa | 4.72 kPa | 69.9 kPa | 17.1 / 69.9 / 17.1 kPa |
| Water under carbon dioxide, 1 atm, 298 K | 101.3 kPa | 47.5 kPa | 100.1 kPa | 115.3 kPa, all forms (starved) |
| Dead oil under nitrogen, 1 atm | 1.7 Pa | 11.8 kPa | 94.1 kPa | 113.5 / 94.1 / 12.1 kPa |
| Separator liquid, 10 bar, 310 K | 203 kPa | 632 kPa | 940 kPa | 1008 / 940 / 212 kPa |
| LNG at its bubble point, 111.8 K | 93.7 kPa | 89.7 kPa | 93.7 kPa | 100.2 kPa, all forms (starved) |
| Propane drum, 298 K | 952 kPa | 850 kPa | 941 kPa | 959 kPa, all forms (starved) |

Sigma at the limit under form G: 4.06 (water under nitrogen), 6.59 (dead oil), 73.7 (separator liquid), so a pump starved by released gas does not wear. Vapour pressure: the condensables definition is kept (orchestrator's default, open to the owner). The half-share definition puts a boiling liquid 4 to 11 % under its bubble pressure (propane 850 against 952 kPa), which gives a boiling-liquid pump 21 m of suction head it does not have. The condensables definition treats a gas dissolved below its critical temperature (carbon dioxide in water at 25 C) as vapour pressure, so that liquid counts as at its bubble point, as a rich solvent is treated in design; it jumps at the critical temperature (carbon dioxide at 304 K).

## 20. Cooling tower: any liquid, every compound evaporates (owner, 2026-11-11; proposed, not built)

Owner: "Remove cooling tower composition requirement, allow evaporation of non-water compounds".

- Expected: air passing a warm liquid takes up every volatile compound in proportion to its partial pressure over the liquid, and the latent heat cools the liquid. Water with 10 mol % ethanol at 35 C stands at about 4.6 kPa of ethanol beside 5.2 kPa of water (rough, activity coefficient 3 to 3.5): ethanol leaves about as fast as water by moles, 2.3 times by mass.
- What the code does: a spray inlet takes a stream only if its liquid is water alone (`TowerExchange.liquidWater`); anything else closes the inlet with `WATER_ONLY`. The fill is Merkel's rating on water's saturation curve, a constant water heat capacity, and one loss, water (`Rates.waterLoss`).
- Proposal:
  - No stream is refused. Gas and carried solids pass to the basin as the gas does now; every liquid feeds the fill.
  - "Saturated air at the liquid" holds every compound at its partial pressure `x_i phi_i P` from the equation of state. Per fill call the engine is read at two temperatures (the inlet and the wet bulb); between them `ln p_i` is linear in `1/T` and the liquid's heat capacity constant, so the searches inside the fill cost what they cost today.
  - The air moves straight towards that saturated state (Lewis number one, as now), for each compound; a compound's loss is at most what the stream brings (dissolved nitrogen strips whole).
  - The loss becomes one rate per component, booked by the ledger per component; the energy each carries is its vapour enthalpy on the model's own datum, so the basin's balance closes.
  - A liquid that boils at the atmosphere has no air contact (status word); its vapour leaves by the basin's flash and vent. Near boiling the saturated content is continued, not divided by zero.
  - Water alone goes through the same path: references move by the difference between the cubic's water curve and today's (about 0.15 % in saturation pressure at 25 C).
- Composition along the fill is the inlet's (evaporation is a small share of the flow); the cap above keeps a trace solute from going negative.

**Air into the water (owner, 2026-11-11: "make cooling water saturated with air as well").** The exchange runs both ways: each compound moves towards equilibrium between the liquid and the air.

- The air's own gases that the package holds (nitrogen 0.7808 and oxygen 0.2095 of the dry air, less the humidity) dissolve: the liquid leaves the fill saturated with them at its leaving temperature, `x_i = p_i,air / (phi_i P)`. For water at 25 C that is about 9e-6 of nitrogen and 5e-6 of oxygen by moles (hand estimate from Henry's law).
- A component's rate is then signed: a loss for water and the volatile compounds, a gain for nitrogen and oxygen where the stream arrives with less than saturation, a loss where it arrives with more. A gas the air does not hold (dissolved methane) strips whole, which is the same statement with a partial pressure of zero.
- The gain carries its enthalpy at the air's temperature on the model's datum; the ledger books it as a negative removal per component.
- A basin with no spray keeps what it holds: only the fill exchanges mass, as now.

## 21. Condensate a port drains (2026-11-11; owner: "review the issue and diagnose")

Fed water pinned at the arrival's composition fixed the two fed-drain tests (1 and 1 boundary reopens). What remains, in the 5 s fed drain only: slices 2 to 32 of 60 end all gas and slightly supersaturated (the condensate would be 0.8 g in the 1 m3 tank: 8e-7 by volume, 7e-4 by moles).

- Expected: the tank's gas cools a little as it leaves, so about 0.8 g of water condenses per slice. The bottom port, which passes about 120 g per slice and is drawing gas, takes that water with it. The tank ends each slice saturated.
- What the code does: the check after a converged pass proposes the condensate (172 times). Laid out in the seed, it is the bottom port's first phase, and the port rule of section 18 makes a phase the seed holds carry the whole outflow: 120 g from a 0.8 g pool. The Newton has no root and its last iterate empties the water. Owner decision D39 B reads that as "the proposed phase does not exist", refuses it for the rest of the solve, and the all-gas layout is accepted.
- Diagnosis: one piece of evidence, a failed pass whose iterate empties a phase, has two readers. D39 B (a tank on its bubble point proposing 3.4e-6 of its moles as vapour) reads "refuted"; the port rule's `drainedSeeds` reads "drawn whole". D39 B runs first. For a phase a port draws first its reading is wrong: the phase forms and leaves. The deeper cause is that section 18 decides "carries the flow or drawn whole" from whether the seed holds the phase; that is right for a phase that can sustain the port (the kettle's gas over a pool that flashes) and wrong for one that cannot.
- Fix (orchestrator; D39 B untouched): a port already passing a lower-priority phase leaves none of a higher-priority phase behind. When a converged pass's check proposes such a phase and the proposed amount is less than what the port passed beyond its pinned phases, the amount joins that phase's capacity as formation within the step (as the boil-up and the arrivals do), the phase is pinned and drawn whole, and the pass is solved again; a larger proposal is laid out as now. This is the removed "outflow exceeds capacity" test with a measured formation in place of a guessed one. The pass key counts the formation updates.
- Rejected: accepting the state as vanishing (it is above the threshold by moles, section 16's group 3); refuting with the port in view (it spends a failed Newton per step).

**The port rule, final form (2026-11-11; supersedes section 18's rule and folds in the fix above).** Measured by the sub-thread: with the "outflow exceeds capacity" pin removed, three tests that pass at HEAD regress (`LiquidCo2LetDownTest` holds on the substep limit, 3.29 of 5 s at slice 4, a new hold class; `PressureValveContinuationTest` ends `VALVE_OPEN` for `VALVE_REGULATING`; `TracePhaseFoldTest` takes 6.2 s against 2.6 s). With the pin back and the arrivals counted, all three pass; the kettle needs the pin not to hold. One rule:

- (a) First guess, as at HEAD: a phase whose capacity (start holdup, arrivals, boil-up) the remaining outflow exceeds is pinned.
- (b) Correction at a converged pass. A port passing a lower-priority phase leaves none of a higher-priority phase behind; an end state that holds one above the vanishing share is not a solution. If the check proposes a phase that was absent (the condensate) and its volume is less than the port passed, it joins the capacity as formation and is drawn whole; a larger one is laid out. If the phase was pinned by (a) and regrew (the kettle's 0.48 m3 of vapour), it can form as fast as it is drawn, so it carries that port's flow for the rest of the solve.
- (c) If the pass under (b)'s second case has no root and its iterate empties the phase, it is pinned again with the regrown amount as formation, finally for that solve.

The pass key carries the formation updates and the overrides. To report: how often (b) and (c) fire, failed Newton passes against HEAD, any HEAD-passing test whose numbers move.

## 22. The port-rule redesign withdrawn (2026-11-11)

Sections 18 and 21 are withdrawn; the port rule stays as at `754732f66` (main's D11 rule with stage A group 1's addition).

Measured by the sub-thread on the rule of section 21's last paragraph: the three regressions pass again, but the correction for a small new phase fired 23,002 times in `PressureValveContinuationTest` (a fixed-point loop, each formation leaving 0.78 of the last; 193.9 s against 43.8 s), the fallback fired 97 times, and the kettle failed (boil-up 0.0000 for 0.443 kg/s: the pass after the correction did not converge and the vapour line was left with no flow).

Why it was the wrong work: the kettle passes on main and at the baseline (`48b4fb298`) under the same pin rule, and `boilUp` is unchanged. W3b did not break the rule; something upstream now drives the kettle into a branch it did not enter before (the duty controller reaches 8.1 MW at t = 10 s). The orchestrator ordered a redesign of the rule before asking what the batch had changed.

Now: the group 5 work is kept as a patch in `tmp/w3b-network/`; the kettle's 1 MW case is run at `48b4fb298` and at HEAD with the same print per accepted step (controller output, pool heat, boil-up, gas capacity, vent flow, pin, pool volume, pressure, temperature), and the first step and quantity that differ decide the fix. If the baseline also passes liquid through the top port, the fault is older than W3b and becomes an open item. The condensate of section 21 arose only under the withdrawn rule.

**The kettle against the baseline (2026-11-11).** The fault is older than W3b. Same start state on both sides (372.147 K, 101325 Pa, liquid only, 1.292 m3; pool heat 5.70 MW; hot side identical).

- First difference, t = 0 to 1 s: water's saturation pressure at the step's end, 101599.5 Pa at the baseline (IF97 to about 1 Pa) against 101676.5 Pa at HEAD (the cubic water, 75 Pa or 0.074 % higher at the same temperature). The vent then passes 4.36 against 6.89 kg/s (274 against 351 Pa over the atmosphere).
- The baseline also pins the gas and passes liquid through the top nozzle: 107, 96 and 82 kg/s at t = 4.1 to 5.0 s against a boil-up of 3.7 kg/s; 171, 121, 73, 12 and 12 kg/s at t = 23.3 to 26 s against 0.40 kg/s. It then settles at 0.437 kg/s from t = 28 s (pool 1.292 to 0.985 m3) and its test passes.
- HEAD bursts every 1 to 3 s from t = 25 to 50 s (123 down to 23 kg/s, the vent at 0 in between); the pool falls to 0.633 m3 and the last interval's mean is 8.14 kg/s.

So W3b's 0.07 % shift in vapour pressure only moves the timing of a fault main has: the gas pinned by the outflow test, with liquid through the vapour nozzle while vapour stands above the pool.

One last form, measured (no further port-rule forms in this batch): the outflow test pins a liquid, not a gas the seed still holds. A liquid's holdup is what a port can draw of it in a step; a gas's holdup is no such limit, since drawing it lowers the pressure and the port's own law then passes less. A gas runs out at a port only when liquid displaces it, which is the existing path (the seed no longer holds it, the start did). If any class fails or slows, the rule stays as on main and the kettle is the owner's open item.

**Result of the last form (2026-11-11).** With the outflow test not pinning a gas the seed holds: the kettle passes (1 MW: 0.4427 kg/s against 0.4430; OFF: 1.2333 against 1.2339; the largest vent after t = 5 s is 3.27 kg/s of vapour flashing as the pressure fell 4.5 kPa, no liquid through the nozzle), `TracePhaseFoldTest`, `PhasePortPriorityTest` and `InletPhaseTest` pass, and two classes fail as they did with the pin removed for every phase: `LiquidCo2LetDownTest` (5 cm let-down, slice 4 held at the substep limit: 3.29 of 5 s, 726 accepted steps, 298 state-change rejects) and `PressureValveContinuationTest` (equalSetpoint ends `VALVE_OPEN` for `VALVE_REGULATING`). The port rule is back at main's; the form is kept as `tmp/w3b-network/gas-pin-form.patch`. The kettle (two tests) stays failing and is the owner's open item: the two failures are untraced, so it is not known whether they need the gas pin for a physical reason or lean on it.

Also in the tree: form T reads a pump's head share from its suction junction's own vapour share on the start states (small, so T stays in the comparison); the hold-fuzz harnesses' trace line ported to `phaseVolume`.

## 23. The suction forms' quick check and the two traces (2026-11-11)

**Quick check** (200 seeds, 4 threads beside another worktree's test run; holds by class and seed):

| Form | Cold random | Bundled random | Defaults | Newton iterations, cold / bundled |
|---|---|---|---|---|
| 0 (section 16's provisional rule) | 2: E6 101, F 118 | 0 | 0 | 329,694 / 124,843 |
| G (clean-zone edge in the limit) | 4: E6 101 and 181, F 6 and 118 | 1: F 6 | 0 | 325,285 / 123,806 |
| T (vapour pressure alone, head share read at the suction junction) | 2: E6 101, F 118 | 1: F 6 | 0 | 346,180 / 123,810 |

Not chosen yet. The budget holds (F 6) are not replayed alone; cold 181 under G is a new hold of an existing class. Ordered before the choice: the batched replay alone of the budget holds; 181 diagnosed from the run's trace; a starved pump on nitrogen-saturated water over 30 steps under G and T (a steady point against a swing is what the hold count does not show).

**The two tests that fail when a gas the seed holds is not pinned by the outflow test** (owner: "Trace the two failures").

- `LiquidCo2LetDownTest`, 5 cm: liquid carbon dioxide enters the tank at about 89 kg/s (0.08 m3/s) and flashes about 5 % at 1.35 MPa and 241 K; the 0.16 m3 vapour space fills in about 2 s and the tank floods, the vent then passing the inflow, liquid with the flash vapour. Main's rule pins the gas at the vent (27.9 kg/s against a capacity of 27.5) and settles with 0.115 m3 of vapour standing while the vent passes 89.2 kg/s, mostly liquid: the kettle fault, here close to the truth. With the patch the step that removes the last vapour leaves the tank liquid-full, the vent passes 126 to 148 kg/s of liquid against 92 in, the pressure falls 93 kPa (a state change of 0.054 to 0.069, above the cap), the step shrinks to 1.2 ms and the node alternates between liquid with vapour and liquid alone (298 rejects, the substep limit at 3.29 s). Reading: the liquid does displace the gas there, so the patch's condition is too wide; and the flooded regime cannot be stepped, which main's standing vapour hides.
- `PressureValveContinuationTest`, equalSetpoint: a drum with 11.4 of 11.5 m3 as vapour, fed 106.5 kg/s. Nothing displaces the gas. Main's rule pins the gas at the top port and passes drum liquid through it in 84 of 171 passes, which unloads the bottom line so its valve ends regulating at 200.0 kPa. With the patch the top draws vapour only, the bottom line runs on its velocity cap and its valve stays open at 201.8 kPa; ledgers hold and all 12 intervals commit. Reading: the test's endpoint mode leans on liquid through the top port.

The physical statement that covers all three cases: a gas runs out at a port when the liquid entering within the step fills the vapour space (the kettle: none enters; the drum: far less than 11.4 m3; the let-down: 0.08 m3/s into 0.16 m3). The complete form is implicit: the flooded state is "all the gas that forms leaves, the liquid carries the rest, no vapour space", closed by the vapour volume, not by a guessed capacity.

**Owner, 2026-11-11: "Build it, measured".** The displacement test, one form: for a gas the seed holds, the port rule's "outflow exceeds capacity" test becomes "the liquid volume the node gains within the step is at least the vapour volume the step starts with"; liquids and everything else as on main. `PressureValveContinuationTest`'s endpoint assertion is rederived from the physics (the bottom line on its velocity cap, its valve open). Risk stated before the run: in the flooded let-down, main's pin leaves about one step's flash vapour standing (0.115 m3), more than the liquid gained per step, so the test may alternate between a pinned step and a gas-only step. If any class fails or slows, the rule goes back to main's and the kettle is deferred to its own batch (a flooded vessel closed by its vapour volume, implicitly).

## 24. The suction form: G chosen (2026-11-11)

Measured by the sub-thread after the quick check of section 23.

- A starved centrifugal pump (0.01 m3/s, 50 m) on nitrogen-saturated water from a 1 atm supplier. Form G: every step the same, suction at E = 69.88 kPa (p_v 3175 Pa, breakdown 7.3 kPa), 4.489 kg/s, 0.75 % vapour at the junction, head share 1. Form T: period 2 for the whole 60 s; odd steps pull to 14.87 kPa at 7.513 kg/s with 11.8 % vapour at the junction, even steps read that vapour, lose the head and pass 0.021 kg/s at 101.32 kPa.
- Budget holds replayed alone: cold 6 under G stands (wall deadline, tick 2084); bundled 6 under G stands as E6 (Newton iteration limit, island 43); bundled 6 under T was contention (clean in 26 s).
- Cold 181 under G (E6, a set-flow pump): for a liquid that is all condensable, E lies below p_v (LNG: 93.67 against 93.71 kPa) and G's limit and lock are T's. G differs only where E exceeds p_v plus the breakdown, which needs a compound above its critical temperature in the liquid; the seed's world has an LNG generator at 128 K, above nitrogen's 126.2 K. Reading, not confirmed: the limit sits just under the supplier's own pressure, where form 0 (D16 C) vapour-locks the pump and G leaves it open on a small margin.

Chosen: form G. Form T is rejected on the alternation; form 0 stays behind the switch until G's three holds (cold 6, bundled 6, cold 181) are understood. Next: a trace of the two smallest (one replay each, release pressures and the lock printed), then the lock's statement under G, then the vapour lock check.

## 25. The kettle fixed (group 5), and form G's holds (2026-11-11)

**Group 5, committed by the sub-thread as `2aebdcd3c`** (the harness port as `2f02dab60`): a gas the seed holds is pinned at a port only when the liquid entering within the step fills the vapour space (each stream's liquid at its donor's start density, injections flashed at the start state).

- Kettle: 1 MW 0.4427 kg/s against 0.4430; the largest vent after 5 s is 3.27 kg/s of flash vapour; OFF 1.2333 against 1.2339 kg/s.
- `LiquidCo2LetDownTest` 5 cm: no hold, 105 accepted steps (98 before). The risk stated in section 23 is confirmed: the flooded tank saw-tooths, gas-only steps venting about 7 kg/s while the vapour falls from 0.09 to 0.01 m3, then pinned steps venting 115 to 153 kg/s while it regrows to 0.1 m3, every 4 to 6 steps. Open item: the implicit form (a flooded vessel closed by its vapour volume).
- `PressureValveContinuationTest` equalSetpoint: every interval settles with the bottom line on its cap (82.93 kg/s, valve open, junction at 201.8 kPa) and 23.60 kg/s through the top (valve regulating at 200.0 kPa), in equal to out to 1.7e-9, in 0.35 s. Assertion changed from "bottom valve regulating" to "bottom valve open above 200 kPa, top valve regulating at 200 kPa within 1 Pa".
- Failed Newton passes on the target classes 66 against 88; filtered run: 8 failures, all present before.

**Form G's holds, two traced** (one replay each):

| | Cold 181 | Bundled 6 |
|---|---|---|
| Supplier | two-phase junction, 127.54 K, 312.23 kPa | two-phase junction, 386.32 K, 180.45 kPa |
| Liquid above its critical temperature | 0.37 % (nitrogen) | 0.61 % |
| p_v / E / breakdown at shutoff | 295.6 / 311.14 / 2.58 kPa | 143.2 / 179.39 / 0.99 kPa |
| Limit under G | E, 1.09 kPa under the supplier | E, 1.06 kPa under the supplier |
| Stall | singular LU, then the iteration limit, on the supplier junction's rows | the iteration limit, same rows; modes alternating |

Both pumps draw from a boiling junction with 0.25 m and 0.11 m of suction head; form 0 (D16 C) closes them as vapour-locked. Decided (orchestrator): E was read on the supplier's liquid alone, but a junction delivers its whole stream, vapour included. E is the pressure at which the stream the pump draws reaches the clean zone's edge: the liquid alone from a vessel's liquid port, the whole mixture from a junction or a bulk draw; where that stream already holds vapour at or past the edge, E is the supplier's own pressure and the pump is locked, as under form 0. Rejected: the lock at the supplier's bubble pressure whenever the supplier holds gas (it would lock water under a nitrogen blanket with any suction lift); the lock below the breakdown at shutoff (it misses bundled 6 by 71 Pa). To be built only if both junction streams are at or past the edge in the traces; then the vapour lock check on form G is their next run.

## 26. The vapour lock stays; the edge on the stream drawn (2026-11-11)

**E on the stream drawn**, committed by the sub-thread as `69db75057`: through a bulk end (a junction's mixture) E is read on the whole mixture at its own enthalpy, and is the supplier's own pressure where that mixture is already past the clean zone's edge; a liquid or bottom port keeps the liquid's E (water under nitrogen 69.9 kPa, unchanged). 29 pump and suction classes pass. The orchestrator's review of that commit found four points not yet in, sent as one follow-up commit: the stream must be the one `InletPhase.supply` delivers, not "bulk end, whole node"; the mixture's enthalpy must leave out carried solids; `limitFloor` drops a finite E where there is no vapour pressure, and a finite vapour pressure where there is no E (a pump with no limit at all); the mixture cache is keyed by the state alone.

**The vapour lock on form G, kept against removed** (200 seeds, 8 threads, machine idle):

| | Cold random | Bundled random | Defaults | Newton iterations, cold / bundled / defaults |
|---|---|---|---|---|
| Lock kept | 3: E6 101, F 6, F 118 | 1: F 26 (clean alone: contention) | 0 | 291,234 / 123,409 / 10,894 |
| Lock removed | 4: E5 181, E6 101, F 6, F 118 | 0 | 0 | 283,474 / 129,734 / 10,894 |

Decided, as the owner asked ("All four, measured"): the vapour lock (D15) stays. Removing it brings cold 181 back as a line search stall at tick 1086 and costs 5.1 % of the bundled iterations. With the lock kept and E on the stream drawn, cold 181 and bundled 6 are clean.

So of section 17's four rules: water's own saturation curve, the no-limit rule for a pump at its tank and D16 C's gas-beside-the-liquid rule are gone (form G); the vapour lock is kept. Forms 0 and T leave the code.

**Open under form G: cold 6**, a wall-deadline hold (tick 2028 to 2084) that form 0 does not have; replayed alone once, not traced (the two-trace allowance went to 181 and bundled 6). Reading from the code: under G a supplier state costs up to three bisections of about 24 PH equilibria each (p_v, the liquid's E, the mixture's E) where form 0 costs one, and a junction supplier changes state every step. Ordered: nothing worked out that the edge does not read (now); a counter of those equilibria per accepted step and cheaper searches in stage B. Step 7's campaign is its next run.

## 27. The cooling tower as one atmospheric contact (owner, 2026-11-11; replaces section 20's bullets where they differ)

Owner: "The cooling tower is performing heat and mass exchange with air, and any gas phase in the inlet stream gets removed, it should be coherent in design".

Section 20 had three different routes for what is not liquid water: gas in the stream went on to the basin for its vent, a liquid that boils at the atmosphere got "no air contact" and a status word, and only the rest met the air. One statement replaces them.

**The tower brings what its spray delivers to atmospheric pressure in the air stream. What is gas there leaves with the air; what is liquid exchanges heat and every compound with the air; the liquid that remains falls to the basin.**

In order, for the stream of one spray inlet:

1. Gas that arrives as gas leaves with the air: every component of the stream's gas phase, with its enthalpy. None of it reaches the basin.
2. A liquid above its bubble temperature at the atmosphere flashes on leaving the nozzle: it cools to the temperature at which its partial pressures sum to the atmosphere, and the vapour that forms (each compound in proportion to its partial pressure there) leaves with the air. This uses the same table as the fill, `ln p_i` linear in `1/T` between two engine readings, so it costs no engine call; a liquid below that temperature skips this step with nothing lost.
3. The liquid then meets the air in the fill as section 20 states: each compound moves towards equilibrium with the air (water and volatile compounds leave, limited by the fill's transfer units; a compound's loss is at most what the liquid holds; the air's own nitrogen and oxygen dissolve to saturation at the leaving temperature; a dissolved gas the air does not hold strips whole). Near the bubble temperature the saturated content is continued with value and slope.
4. The liquid, and any carried solids, reach the basin. The basin's surface exchanges sensible heat only, as now.

One signed rate per component covers 1 to 3 (gas removed, flash vapour, evaporation, less the air dissolved), with one energy rate; the ledger books them per component and the basin's balances close on them. No stream is refused and no status word names a composition: `WATER_ONLY` goes, and section 20's word for a boiling liquid is not added. The exhaust the plume reads keeps water's humidity; the other compounds' share in it is not drawn.

Consequence for the basin: water leaving the fill saturated with the air's nitrogen and oxygen stands just under its bubble pressure at the atmosphere (the air's 1 % of other gases is the margin), so the basin holds no gas phase from the spray and its vent has nothing to take from it.

## 28. The clean zone's edge reads the stream at the suction now (2026-11-12)

Expected: E is the pressure where the liquid at the pump's suction releases more gas than the pump passes cleanly. It belongs to what stands at the suction now. Gas that reaches the suction later in the step lowers the head (the head share), not E.

What the follow-up to 69db75057 did first (uncommitted, the four review points built): E read on the stream the step's slice draws. `InletPhaseTest.aPumpOnABottomPortRunsThenMeetsGasAndMovesNextToNothing` (water under nitrogen at 120 kPa, a 2 L/s pump on the liquid port, 2.99 slices of water) then failed: the last slice's stream is 0.99 slice of water with about 1 % of free nitrogen, the gas that follows the water. The clean share at 120 kPa is 1.27 %, so E came out near 95 kPa and the pump sat on its limit for the whole slice: drawn 9.96 / 9.96 / 6.28 / 3.61 kg instead of 9.96 / 9.96 / 9.88 kg, 45 steps with 22 rejected instead of 24 with none.

Decided (orchestrator, owner: "auto proceed"): E is read on the stream the end draws with no slice (the leading phase of a phase port, the bulk of a BULK end); the head share keeps the slice's stream. Where that stream holds no condensed phase E is none, as before: the dry-suction refusal owns a pump whose port stands in gas. A junction's mixture is unchanged (E = P past the edge: cold 181, bundled 6). Not chosen: E on the slice's liquid phases only (a junction's mixture would lose its gas again); keeping the slice and re-deriving the test (22 rejected steps).

Stage B item 1 (uncommitted, counters only so far): the heat capacity and the second temperature derivative of the volume are formed when read. Excess second-derivative sums: wet 10842 to 0, polar 6738 to 0; all second sums on dry 5475 to 114; end states bit-identical on the three scenarios. Timings not yet run.

Built (969c3633b): section 28's form with the four review points on 69db75057: `limit(p_v, E, breakdown)` is the larger of the edges that exist; E is worked out when first read and cached by state and stream; forms 0 and T and `icheme.suctionForm` are deleted; the vapour lock reads this solve's stream. The 29 suction classes pass (189 tests); the bottom-port drain is as before (MOVER_LAW three times, 24 steps, none rejected).

## 29. Stage B (step 5b, cost): items 1 to 4 (2026-11-12)

Timing runs alone, 60 s warm-up and 60 s measured, ms per accepted step. Bounds against main: dry 1.1, wet 1.5, polar 1.1.

| Item | What | Counters | dry | wet | polar | Outcome |
|---|---|---|---|---|---|---|
| before (ab6b90d42) | | | 0.837 | 2.467 | 1.702 | |
| 1 | The heat capacity and the volume's second temperature derivative formed only when read; the network's kernel callers ask for derivatives without them | excess second-derivative sums wet 10842 to 0, polar 6738 to 0; end states identical | 0.794 | 1.995 | 1.451 | committed 2a780f8f6; 272 tests in 49 classes pass |
| 2 | Sparse rows: centres with one partner set share one direct sum | identical to item 1 | 0.888 | 2.098 | 1.454 | no gain; reverted (variant kept in the sub-thread's scratch) |
| 3 | Skip an absent centre's cells where its ln phi is not read | 1.7 absent centres per call in wet (0.54 polar); 9.7 % of the cells visited (3.6 %) | | | | skipped (orchestrator): the network reads ln phi of absent components, so it needs a mask through three layers for at most a tenth of one function |
| 4 | Water share's pure-water evaluation in closed form, bit for bit | removes 933 of wet's 11554 kernel evaluations (8.1 %) | | | | built, not yet measured |

The dry column's 0.794 against 0.888 is machine noise (the dry kernel has no excess term; about 22 % background load during item 2's run). Wet's remaining excess over dry, about 6200 kernel evaluations, sits in the three-slot states, not in the share. Asked of the sub-thread for its next report: each scenario's ratio to main beside its bound, and wet's kernel evaluations split by caller. If wet or polar is still over its bound after item 5, the decision goes to the owner.
