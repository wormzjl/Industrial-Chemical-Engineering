# Test review criteria, second pass (2026-11-02)

Owner request: "every time a merge lands a full suite is required and it takes too long, test numbers and runtime needs to be minimized".

Base: the first review's [criteria](../2026-10-21-test-review/CRITERIA.md), K1 to K6 and A1 to A5. Its finding: under the "only guard" bar the suite was about 96 % necessary. That review saw 1435 test methods. This pass judges the 556 added since then (`f91c992d`), which nobody has judged.

## What changes in this pass

- K1 to K5 keep their doubt rule: when in doubt, keep.
- K6 reads narrowly: wrong numbers, lost material, a crash on load. A wrong look, a misplaced label or a tool that refuses a click is not K6. It is K3 only if an owner rule names it.
- The doubt rule does not apply to K6 alone: a test whose only claim is K6 and that fails the narrow reading is a candidate.

## New reasons to remove

- S1 Subsumed: a kept sweep or a wider test runs the same production path, and its assertions imply this test's. Name that test.
- S2 Instance of a sweep: the test is one more case of a rule a kept test already sweeps. Name the sweep.
- S3 Drawing or text pin: pins a drawn dimension, a label, a colour or a layout number that no owner rule and no saved format names. Software renders and the layout golden judge these. Name what judges it.

## Not a removal

- C1 Cheaper form: the same assertions at lower cost (a shared fixture, a smaller world, fewer ticks after the event, one solve shared by several tests). Give the file, the change and the estimated seconds saved. Never weaken an assertion.
- Do not merge tests only to lower the count. A merge counts only when the merged test keeps every assertion and removes real duplicate setup.

## Never a candidate

- A compatibility corpus test (`compat/`), a format round trip, a conservation closure, a determinism gate, a named hold-class world or a D-numbered decision gate.
- A printed line the lane reads (`LIQUID_JUNCTION`, `MIXED_GAS_*`, `MIXED_GAS_COST`, `PUMP_CAVITATION_LNG`, `VESSEL_SEED`, `JUNCTION_SEEDING_GATE`, `D46_GATE`, `STILL_PIPE_GATE`, `MELT_GATE`).

## Part format

One file per area under `parts/`, at most 50 lines: a table of `class | methods | verdict | methods to remove | reason and named kept guard`, only for classes with a proposal, then one line with the totals (methods removed, seconds saved). Verdicts: KEEP is the default and needs no row. TRIM lists methods. ARCHIVE takes the whole class. C1 rows give the change.
