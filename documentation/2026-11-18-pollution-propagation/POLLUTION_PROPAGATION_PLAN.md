# Pollution propagation: plan

Batch `2026-11-18-pollution-propagation`, branch `claude/water-intake-outfall-0e90c8`. The field it changes: [POLLUTION_PLAN.md section 4](../2026-11-15-pollution-system/POLLUTION_PLAN.md).

## The owner's request (2026-11-18)

"change the air pollution pollution propagation so it tends to propagate more upwards. horizontal propagation rate stays the same. Also try bench and optimize its performance".

## P1: the upward drift

### The physics

- Polluted air from a plant is mostly warm (flue gas, vents, a fire) and the daytime boundary layer mixes upward. The face exchange is an eddy diffusion: the same in all six directions. The request adds a mean upward motion, an advection, on top of it.
- In finite volumes on the cube grid, an advection is an upwind flux through the top face: `U = r · min(φa, φb) · A_a / φa`, from cube a to the cube b above, per channel. It does not depend on the index of b, so it moves pollution up even into a dirtier cube, as a rising plume does.
- `r = min(1/7, riseShare · dt / 20 s)`, `riseShare = 0.12`: the same size as the face share `k`, so the upward face passes twice the face exchange. An explicit step stays positive: a cube gives at most `6k + r ≤ 1`.
- The horizontal faces are untouched, so the horizontal spread rate stays the same (owner).

### The numbers

- One 20 s step from one open cube (before decay): the cube above 0.24 of the amount, each side and below 0.12, the source 0.16.
- A drift velocity of `0.12 × 16 m / 20 s = 0.096 m/s` against an eddy diffusivity of 1.5 m²/s: a cell Péclet number of 1.0. Over the open half-life (300 s, 15 passes) the plume's centre rises 1.8 cubes (29 m).
- The plan's steady H2S plume (1 mol/s, open, `PollutionFieldTest`): the source after a pass 0.895 → 0.594, a side neighbour 0.490 → 0.368, the second ring sideways 0.142 → 0.094; the cube above 0.490 → 0.665, below 0.490 → 0.333; four cubes below 0.020 → 0.006. The centre of mass stands 2.5 cubes (40 m) above the source; the field holds 1 734 cubes (1 461 before); the top's sink takes 1.98e6 instead of 1.49e6 of 1.2e8 released over 600 passes. Ground level near a source gets cleaner; the air above it dirtier.

### The rules

- The six-face exchange is kept as it is, exactly.
- A closed or unloaded cube above takes nothing: the drift is zero.
- Above `maximumY` the drift is booked `dissipated`, like the top face's share.
- A cube above that is not in the field takes the drift through the front's offer, in the same slot as the face share: the sum decides the cutoff, and a refused slot refuses both.
- All four channels drift (owner item: A all four, built; B gases only, particulate not).
- `riseShare` is the last field of `PollutionSettings` (0 to 0.5; config `pollution.riseShare`, captured on server start). `riseShare` 0 gives the field before the drift bit for bit: `PollutionDriftTest` holds its digest, taken from the compiled field of before the change.

### Tests

- `PollutionDriftTest`: the one-step shares; the centre of mass rises with 0.12 and not with 0, the x and z spreads equal, the horizontal spread the same with and without the drift; a closed cube above takes nothing; the top books the face share and the drift; the 1/7 cap; the settings' range; the two stored digests.
- `PollutionFieldTest`: the top sink books 0.24 (moved from 0.12); the plan's plume numbers held at riseShare 0; a new test holds the plume with the drift.

## P2: bench and optimisation

- Harness `tools/pollution-bench/`: fixed fields of 512, 4 096 and 8 192 cubes (the cap), and 4 096 ground columns. 60 s warm-up and 60 s measured, run alone. Steps per second and allocated bytes per step.
- Every change is bit-identical: the default digest of `PollutionDriftTest` (`5ae9aaa2c9acc5c5`) and the rise-0 digest must not move.
- Candidates: the six `binarySearch` per cube; `Work.add`'s boxed sort; the cap's boxed sort; the front's `HashMap<Long, Integer>`; repeated cell lookups; clones.
- Counters around the runtime: cells built per pass, entity searches per pass.
