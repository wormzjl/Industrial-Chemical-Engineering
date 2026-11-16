# Rules: Physics and defects

The full rules of the `AGENTS.md` section "Physics and defects", with the same force. `AGENTS.md` keeps the core and says when to read this file.

- Reason about every issue from real-life physics first, with simplifications: what flows where, and the steady state, in numbers. Report in this order: the expected behaviour; what the code did instead, in plain words; a proposal for the owner to validate.
- Every fix improves the existing structure: it goes in the stage it belongs to and removes the special cases it makes unnecessary. No isolated patches.
- A fix that rests on an assumption the physics does not force goes to the owner as options.
- Conservation of mass, energy and components stays exact in every change.

### Thermo validity

- A freezing (triple) point is never a lower validity bound. A component stays valid below it, in mixtures and in supercooled or metastable states. Seed windows, domain floors, fluid-only ranges and range clips use the package's own domain floor.
- Leaving a data range (low or high T or P, any property model) never holds an island. Properties extrapolate with continuous value and slope at the edge, and a non-blocking notice on the island's status line reports it.
- Numerical guards stay only where the mathematics fails (the numerical box, T ≤ 0, no equation-of-state root). They act inside the solvers as a rejected trial step and a backtrack, never as a hold.
- Holds stay only for capability limits, where the model cannot represent a state.
- Keep these as lightweight game-level continuations. Add no reference models.

### Small physics simplifications

- In physics code, look for simplifications with a small game-level effect: a negligible term, a rule or mode that a constitutive statement replaces, a special case that a coarser model absorbs.
- Propose each one to the owner with its effect in numbers (at the network's usual states and at the package extremes), what it deletes and what it risks (new discontinuities, references that move). Implement it only after approval. Write a broad sweep as a review.
- A simplification never overrides an owner rule. A code comment that records a measured reason for a term blocks removing it, unless the simplification also removes the cause.

### Results that move

- A change that only moves code keeps every reference exact.
- A change that moves a reference states, in its review, the old and new values and the bound it was expected to keep, taken from the solver's own tolerances. The final lane re-records it once, with both values in its output.
