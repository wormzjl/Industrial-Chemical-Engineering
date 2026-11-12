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
