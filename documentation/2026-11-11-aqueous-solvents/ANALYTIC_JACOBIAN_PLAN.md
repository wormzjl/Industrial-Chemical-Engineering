# Node Jacobian from phase tangents

Batch 2026-11-11-aqueous-solvents, W3b step 5b. Owner, 2026-11-12, on W3B_NETWORK_PLAN.md section 30 (wet 2.18 and polar 1.42 times main after the cost work; bounds 1.5 and 1.1): "Yes, design first". This is the design; nothing of it is built. Estimates are marked (est.).

## 1. Expected

The network's Newton needs the derivative of every row with respect to every unknown. A node's unknowns are its temperature, its pressure, each component's total amount, and each component's split between the phases. The thermodynamics of one phase (volume, enthalpy, fugacity coefficients) has exact derivatives in all of these; one derivative evaluation per phase gives them all. The cost of a Jacobian should therefore grow with the number of phases, not with the number of unknowns times the number of phases.

## 2. What the code does

`PassiveStepSolver.Equations.differentiateEntries` perturbs one node unknown at a time, decodes the node at the perturbed point (`PhaseLayout.decode`, which evaluates every phase on the equation of state), reassembles the rows that can see it (`nodeAccumulate`, `nodeRows`, `edgeRows`, `nodeTargetRows`) and differences them against the base residual. A wet node with 8 components in three phases has about 20 unknowns: 60 phase evaluations for one Jacobian of that node.

Measured (section 30): kernel evaluations per accepted step 429 dry, 861 wet, nearly all in `decode`; decode is 47 % of the wet profile. Wet doubles dry because a wet node has a third more unknowns and half again as many phases.

## 3. Design

Keep the differencing of the rows. Replace only what a perturbed decode costs: the phases of the perturbed node come from a first-order model of each phase about the node's base point, not from the equation of state. `nodeRows`, `edgeRows`, `nodeAccumulate`, `nodeTargetRows` and `transport` stay the only place each row's formula exists.

### 3.1 The tangent of one phase

For one slot at the base point `(T0, P0, n0)` (conserved amounts, water last; `N0` their sum), one derivative evaluation on the slot's root gives:

| Quantity | From |
|---|---|
| `v0`, `h0`, `ln phi_i` (kernel part: translated volume, enthalpy, coefficients) | `TranslatedPengRobinson.evaluateValues` (as today) |
| `dv/dT`, `dv/dP`, `dh/dP`, residual `c_p` | the same `Values` (`c_p` formed when read, stage B item 1) |
| ideal-gas `c_p` | the model's ideal-gas functions |
| partial molar volumes `vbar_i` | `Values.partialMolarVolumesView` |
| `d ln phi_i / d n_j`, `d ln phi_i / dT`, partial molar residual enthalpies | `PengRobinsonKernel.evaluateDerivatives` (without the heat capacity) |
| `d ln phi_i / dP` | `vbar_i / (R T) - 1 / P` |
| partial molar enthalpies `hbar_i` | ideal-gas enthalpy of `i` plus its partial molar residual enthalpy, plus the translation's share so that `sum x_i hbar_i = h0` |

`CubicPhaseEvaluator.derivatives` already assembles the same set for the flash; the network takes it through `HydrocarbonModel` on its own workspace.

The phase at `(T0 + dT, P0 + dP, n0 + dn)`, `N = N0 + sum dn`:

- `V = N0 v0 + N0 (dv/dT) dT + N0 (dv/dP) dP + sum_i vbar_i dn_i`, molar volume `V / N`;
- `H` the same with `c_p`, `dh/dP` and `hbar_i`, molar enthalpy `H / N`;
- `ln phi_i = ln phi_i0 + (d/dT) dT + (d/dP) dP + (1/N0) sum_j (d ln phi_i / d n_j) dn_j` (the kernel's composition derivative is per mole of phase);
- water's share (`WaterShare.phase`) is not linearised: it is a closed form with no kernel call, so it is evaluated exactly at the perturbed `T`, `P`, water fraction, untranslated volume and co-volume (the last is linear in composition, exactly). This needs no new derivative of the share;
- `dv/dP` of the phase and its vapour-like flag keep their base values. `dv/dP` is read only by the acoustic bound on velocity, which stands above the 100 m/s cap in nearly every pipe.

### 3.2 The state of a perturbed node

`PhaseLayout.decode` keeps its split of the amounts (exact, cheap). A second entry takes the node's base tangents and returns a `State` whose three `Phase` records come from 3.1; the totals (volume, enthalpy, internal energy, mass, phase volumes) are formed by the same arithmetic as `FluidThermodynamics.state`. `transport(node, state, prepared)`, viscosity and the solid moments run unchanged on it.

### 3.3 The Jacobian build

In `differentiateEntries`, per node: the base tangents once (one derivative evaluation per phase present), then every local column decodes through them. Edge, actuator and filter columns decode no node and are unchanged. The step sizes stay.

### 3.4 Where the tangent is not used

- **A step that is not small for a phase.** A column's step is scaled to its unknown, not to each phase. Where it changes a phase's composition by more than a set amount (a nearly empty phase: any mole fraction moving by more than 1e-3), that phase is evaluated exactly for that column. Counted.
- **Outside the numerical box.** `NumericalBox.require` stays on the perturbed `T` and `P`; a refusal hands the build to the coloured sweep, as today.
- **Weirs** (`hasWeirs`): the coloured sweep, as today.

A perturbed point can no longer lose its root or flip to another root: the tangent is on the base's root. Today such a column hands the whole build to the coloured sweep.

## 4. Interfaces

| Place | Change |
|---|---|
| `HydrocarbonModel` | `tangent(t, p, amounts, root, terms, vaporCoexists)` returns the base `Phase` and its derivative arrays; `Tangent.at(t, p, amounts)` returns a `Phase` |
| `FluidThermodynamics` | a state from three tangents and perturbed amounts (the totals arithmetic shared with `state`) |
| `PhaseLayout` | `decode(variables, offset, prepared, tangents)` |
| `PassiveStepSolver.Equations.differentiateEntries` | builds the node's tangents once; the class comment no longer says each entry is the coloured sweep's double |
| `BlockJacobianEquivalenceTest` | bit equality becomes a tolerance against central differences of the residual |
| `SolverDiagnostics` | counters: tangents built, exact phases inside a Jacobian, fallbacks |
| Saves | none. No fact, no format |

## 5. Cost (est.)

Per Jacobian of a node with `s` phases and `u` unknowns: today `u s` value evaluations; then `s` derivative evaluations (each two to three value evaluations, with the composition derivatives of the excess term) and `u s` tangent reads of order `n` each. For the wet node above: 60 against about 9. Decode's 47 % of the wet profile falls to 10 to 15 %; wet about -30 %, dry and polar a similar share (the sub-thread's estimate). That puts wet near 1.5 and polar near 1.0 times main. If wet stays over 1.5, what is left is the excess correction per evaluation (20.7 %) and the sparse LU (17 %), and the bound returns to the owner.

## 6. What moves

Every Jacobian entry differs from today's in about its sixth digit (a forward difference of exact phases against a forward difference of linear ones). Newton's iterates then differ in their last digits, and with them every pinned reference of the network: the 33 junction lines, chain-100, the fluid regression, the printed lines, the campaign's per-seed results. They are read once, in step 7's final lane and campaign, on this form. Phase answers, ledgers and conservation do not depend on the Jacobian.

## 7. Tests and measurements

1. Tangent identities at the base, to 1e-10: `sum x_i vbar_i = v`, `sum x_i hbar_i = h`, `sum_j n_j d ln phi_i / d n_j = 0`; on dry, wet (with the share), polar and near-critical phases.
2. Tangent against the exact phase at steps of 1e-4 to 1e-7 in each direction: the error falls with the square of the step.
3. Jacobian entries against central differences of the residual at two steps, on dry, wet, polar, crystal-carrying and junction fixtures: row-scaled error at most 1e-6, and no worse than today's entries against the same reference.
4. The three cost scenarios: Newton iterations per accepted step within one of today's; end states within 1e-9 relative.
5. Counters per accepted step: kernel evaluations (dry 444, wet 885 today), exact phases inside a Jacobian, fallbacks to the coloured sweep (today's count beside it).
6. Timing alone, 60 s and 60 s: against the bounds dry 1.1, wet 1.5, polar 1.1.
7. The 200-seed quick check, this form against today's: no new hold class. The full campaign is step 7's.

## 8. Predicted failure modes

- A nearly empty phase whose composition a column moves by a large share: covered by 3.4; if the counter shows more than a few per cent of columns there, the rule's threshold is wrong and is reported, not tuned.
- An inconsistent derivative (translation or share left out of one of them) shows in test 1 or 3, before any solve.
- Newton taking more iterations near a phase boundary: the tangent drops the curvature inside the step, which the forward difference also saw only to first order; test 4 decides.

## 9. Build order (one commit each)

1. `HydrocarbonModel` tangent with tests 1 and 2.
2. The state from tangents and `PhaseLayout.decode` with them.
3. `differentiateEntries`, the restated equivalence test, the counters; tests 3 to 5.
4. Timing (test 6) and the quick check (test 7).

## 10. Choices the physics does not force (orchestrator's defaults)

- The rows stay differenced; only the phases are analytic. The alternative, every row's derivative written out, removes the last differencing error but states each row twice.
- The share is evaluated exactly at the perturbed point, not linearised.
- `dv/dP` stays at its base value inside a column.
- The option of re-evaluating only the phases a column touches (section 30's idea A) is dropped: this form covers it.

## 11. Built, steps 1 to 3 (22c0368a8, 57cd8b5f0, 64239496b; 2026-11-12), and what step 4 measured

Counters (kernel value evaluations per accepted step; Newton iterations 81, 82, 78 and end states unchanged):

| | dry | wet | polar |
|---|---|---|---|
| evaluations, before | 444 | 885 | 551 |
| evaluations, tangents | 144 | 219 | 145 |
| ms per step, before | 0.807 | 2.016 | 1.449 |
| ms per step, tangents | 0.843 | 1.749 | 1.224 |
| ratio to main | 1.09 | 1.90 | 1.20 |
| bound | 1.1 | 1.5 | 1.1 |

The evaluations fell by three quarters and the time by a seventh, so section 5's estimate was wrong: the value evaluations were not where the time was. Not profiled yet; one profile each of wet and dry decides the next item. The orchestrator's guess, to be confirmed or refuted: one derivative evaluation costs about as many value evaluations as the phase has components, because the excess term's composition derivatives are a full block.

Test 3 on the six fixtures: tangents 5e-7, today's entries 1e-6 to 1.9e-6.

**Section 3.1's `dv/dP` rule was wrong.** It kept `dv/dP` at its base value in a column, on the orchestrator's reading that the acoustic bound on velocity stands above 100 m/s nearly everywhere. In a node with gas beside liquid the bound is about 32 m/s (1.5 bar, half liquid), and it is low in dense near-critical fluid. A row that reads a flow at that cap needs the derivative of `dv/dP`:

- a new fixture, a dry chain at its velocity cap: row-scaled error 1.55e-2 with the tangents, 1.05e-6 today;
- the 200-seed quick check against today's form: cold 197 to 195 clean (new E5 on 65 and 101), random 200 to 198 (145 E9 "Invalid inventory volume", 47 F), defaults 200 and 200.

Decision (orchestrator): `dv/dP` of a tangent phase to first order, from the cubic's closed form at the nearby point less the same form at the base (the base stays exact to the bit); the share's `V_P` exact with it. No kernel call. Then the capped fixture, the six fixtures and the quick check again, the third and last form compared. Not chosen: exact phases in every node whose limit is the acoustic one (it loses the saving in every two-phase node); reverting step 3.

## 12. `dv/dP` to first order (3f61f4017), the third quick check, the profile (2026-11-12)

`dv/dP` of a tangent phase is its base value plus the cubic's closed form at the nearby point less the same form at the base. The closed form at the base equals the base `Values`' own to 1e-12 on all nine test phases. Test 3, row-scaled error, tangents against today's entries: the chain at its velocity cap 6.2e-7 against 1.05e-6 (was 1.55e-2); dry 5.0e-7 against 1.03e-6; wet 5.1e-7 against 1.91e-6; polar 5.0e-7 against 1.03e-6.

Quick check, 200 seeds per set (the third and last form compared):

| | today | tangents, frozen `dv/dP` | tangents, first-order `dv/dP` |
|---|---|---|---|
| cold clean | 197 | 195 | 196 |
| cold holds | 101; 6, 118 | 65, 101; 6; 121, 183 | 65, 101; 6, 118 |
| random clean | 200 | 198 | 200 |
| defaults clean | 200 | 200 | 200 |

Random 145 and 47 went through a velocity cap and are clean now. Cold 101 holds at the same tick as today. **Cold 65 holds with the tangents and not today**: island 44 on `pilot_cryogenic` (LNG generators at 161 K, 2532 kPa and 115 K, 182 kPa, a compressor, a pump, a filter), "Newton line search stalled at residual 0.158", the first Newton direction not descending, on all 20 attempts; today that island runs steady at 71.9 kg/s. Not `dv/dP`. Open. Order (orchestrator): a probe first, the tangent against the exact phase at that island's states on that package (suspected: the ideal-gas functions below their data's lower end, where h is continued and the tangent's `c_p` may not be the continued function's slope; second, the frozen vapour-like flag near saturation at 161 K); then the seed's one replay alone, after a fix or with the Jacobian's differences printed.

Profile (one run each, tangent form, share of samples, wet and dry):

| | wet | dry |
|---|---|---|
| sparse LU with its triangular solves | 31.4 % | 29.1 % |
| the Jacobian build, whole | 32.3 % | 30.7 % |
| of it: transport (viscosity) | 11.8 % | 16.8 % |
| of it: tangents and their reads | 8.3 % | 4.1 % |
| the residual | 22.3 % | 20.3 % |
| exact phases in the residual and line search | 11.9 % | 4.3 % |
| all derivative evaluations | 3.4 % | 1.3 % |

Derivative evaluations per accepted step: 12, 18, 14 (dry, wet, polar). The orchestrator's guess (a derivative evaluation as dear as many value evaluations) is refuted. Wet costs 2.07 times dry here and 1.19 times on main; what is left of that is the linear solve and the work per unknown, so the next question is how many unknowns a wet node carries (the aqueous slot's ratio unknowns for compounds at trace). Asked for: the trace cutoff, the unknowns per node and per slot, branch against main. Dropping trace compounds from a slot's unknowns is a physics simplification and goes to the owner with numbers.

## 13. Cold 65 is not the Jacobian's form; where wet's unknowns come from (2026-11-12)

**Cold 65.** Probe (0f5817745, cases in `HydrocarbonTangentTest`): the island's LNG at 161 K and 2532 kPa (one root, liquid-like), 115 K and 140 K (three roots) passes tests 1 and 2 on both roots; the ideal-gas data's lower ends are 63 to 91 K and no state is below one. The orchestrator's two suspicions are refuted. The seed's one replay, alone, with the Jacobian's differences printed at each stall: the tangent entries agree with exact forward differences to 7.7e-7, 1.2e-6 and 7.6e-7 at the three junctions; no exact perturbed decode throws, so today's form would not have fallen back. The solve itself is near singular: junctions at 111.6 K and 83 to 104 kPa hold gas with a trace liquid, the residual 0.158 sits in one equilibrium row of one junction, the Newton direction descends only under 5e-9 of the step, and the full step leaves the numerical box. The trajectory moved in its last digits and reached a state today's form does not; the defect there is the solve at a trace-liquid junction, not this form. Decision: no further work on the seed; step 7's campaign counts the class.

**The form is settled**: tangents, `dv/dP` to first order.

**Unknowns** (branch against main ccde84295; the trace cutoff is 1e-6 on both, applied in `PhaseLayout.support`):

| scenario | node unknowns | island unknowns | Jacobian nonzeros | LU nonzeros |
|---|---|---|---|---|
| dry, branch and main | 27 | 167 | 9297 | 12551 |
| polar, branch and main | 31 | 191 | 12161 | 16383 |
| wet, main | 30 | 185 | 11645 | 15393 |
| wet, branch | 39 | 239 | 19521 | 25458 |

Wet's whole growth is nine split unknowns per node: the solutes of the aqueous phase. Benzene 1.7e-4, carbon dioxide 1e-4 and toluene 3e-5 by moles; methane, ethane, propane, butane, pentane and nitrogen between 1.1e-6 and 8.8e-6. None is under the cutoff. On main the free water held nothing.

An aqueous floor of 1e-5 would bring the node to 33 unknowns (about 1.2 times main's block against 1.7). Not built, and the orchestrator advises against it: nitrogen in water under air or a nitrogen blanket stands at about 1e-5 at one atmosphere, so the floor would switch the water's dissolved gas on and off at ambient pressure, and the pump's clean-zone edge (W3B_NETWORK_PLAN.md section 19: water under nitrogen, 69.9 kPa) and the cooling tower's saturated water read that gas.

Where this leaves the cost (ratios to main 1.09, 1.90, 1.20 against 1.1, 1.5, 1.1; a timing of the settled form is ordered): wet's remaining excess is real work, three-phase equilibrium with nine more unknowns per node, paid in the linear solve (31 % of the profile) and per Jacobian column. Routes that keep the physics: a faster linear solve for the node blocks, and less viscosity work per column (12 to 17 %); neither is measured. To the owner.

## 14. Timing of the settled form, alone (2026-11-12)

| ms per step (ratio to main) | dry | wet | polar |
|---|---|---|---|
| before the first-order `dv/dP` | 0.843 (1.09) | 1.749 (1.90) | 1.224 (1.20) |
| HEAD, a run that was not alone (not a measurement) | 0.827 (1.06) | 1.942 (2.10) | 1.436 (1.41) |
| HEAD, alone: the record | 0.915 (1.18) | 1.803 (1.95) | 1.412 (1.38) |
| bound (W3B_NETWORK_PLAN.md section 37) | 1.1 | 2.0 | 1.25 |

Runs of the same code differ by about 10 % on this machine (dry 1.06 and 1.18 an hour apart). Dry's 1.18 is inside that spread of its bound; wet is inside its bound; polar is over in both runs, back at its time before the tangents (1.449).

Open: why polar. The `dv/dP` change adds two closed forms per tangent read, too little for 15 %. Suspect (orchestrator, from the diff): `Tangent.at` now refuses a phase whose `dv/dP` is not negative, and inside the Jacobian build that refusal hands the whole build to the coloured sweep, at the old cost. A count run on HEAD decides (fallbacks and evaluations per step against the count before the change); a profile of polar if the counts are unchanged. No gate in the code holds the cost bounds; they stand in the plan.

Polar, settled (sub-thread's count and profile on HEAD): every counter equals the count before the `dv/dP` change (no fallbacks, no exact phases inside a Jacobian, 145 evaluations and 6.5 Newton iterations per step), so the orchestrator's fallback suspicion is refuted. The profile has the shape of wet and dry: linear solve 29 %, Jacobian build 32 % (viscosity 14 %), residual 24 %; the excess correction is 6 % self. Three runs on this code give 1.412 (alone), 1.436 and 1.432 ms; the single 1.224 before the change was the outlier. Polar stands at 1.38 to 1.41 times main with no defect found, over the 1.25 bound: to the owner. Ordered for the record: main's own iteration and build counts on the three scenarios, to say whether the ratio is more iterations per step or more time per iteration.
