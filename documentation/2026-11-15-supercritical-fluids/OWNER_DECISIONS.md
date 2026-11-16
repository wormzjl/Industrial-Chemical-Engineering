# Owner decisions of batch 2026-11-15-supercritical-fluids

The main thread's note of the owner's standing instructions and answers, kept as written (from
`tmp/supercritical-briefs/OWNER_STANDING.md`, gathered at the merge preparation on 2026-11-16). The plan's section 2b
records the same decisions with their reasons.

## 2026-11-16: "auto merge when done", then "3"

The main thread asked what "auto merge when done" covers and offered: (1) this batch into `main` when it is finished
(S4, S5, the column item, one final lane, the merge preparation; a version from the owner, 0.32.0 proposed; stop and
report instead of merging if the final lane shows a failure or a hold); (2) the `tools/` cleanup branch
`claude/sad-pasteur-b47216` into `main`; (3) both. The owner answered: "3".

So:
- This batch merges to `main` without a further question once every stage, the final lane and the merge preparation
  (`.claude/skills/merge-prep/SKILL.md`) are done and the lane is clean. A failure, a hold or a moved reference that
  the reviews did not announce stops the merge and goes to the owner.
- Version: the owner did not name one. 0.32.0 is the main thread's proposal; it is stated again in the merge
  preparation report, and the owner can change it before the merge.
- The cleanup branch merges to `main` when its own session is done (documentation-only merge).
- No push in either case: the owner has not asked for one.
- Delegations still need the owner's approval, one by one (CLAUDE.md).

Open at the time of the note: whether this session can run a merge in the main checkout at all (a hook refused a write
there earlier). If a merge command is refused, it is not retried in another form: report it.

## 2026-11-16: S4b

Asked to approve the S4 correction on the live sub-thread, the owner answered: "ok but use a new thread". Started as
`supercritical-s4b` (Opus high, fresh brief, this worktree). The S4 thread stays idle. To be recorded in the plan's
section 2b with S4b's result.

## 2026-11-16: the pump's bounds

Shown DENSE_PUMP_RESEARCH_REVIEW.md (section 4, option 1: A the film's 0.5 and 1.5, B the pump's own 0.5 and 1.0,
C no share on one fluid), the owner answered: "Use 0.5 to 1.0". Sent to `supercritical-s4b` as an addition (queued
while it works). The compressor and the film keep 0.5 and 1.5. Options 2 (a minimum-density row on the sheet, S5),
3 (the rise on the average density, its own batch) and 4 (wear, S5's table) are not decided. To be recorded in the
plan's section 2b with S4b's result.

## 2026-11-16: S5 and the column item

S5's three choices (the "Dense fluid" label above reduced density 0.5; no churn and no GAS word on a pump's dense
supply; the sheet's suction-density row below 0.86): "Accept all". The two sub-threads and the merge of the column
thread's branch into the batch branch after the main thread's check: "Approve both". Started as `supercritical-s5`
(Opus medium, this worktree) and `supercritical-column` (Opus high, its own worktree, branch slug
supercritical-column-feed). To be recorded in the plan's section 2b with their results.

## 2026-11-16: S5's label without a wire field (main thread's call, for the owner to overrule)

S5 reported: the wire view carries no phase traits, and the client's catalog can differ from a dedicated server's
when a datapack overrides a compound (nothing syncs materials; the simulator already lives with this). The main
thread chose A: the "Dense fluid" label and the pump page's head-share row are evaluated on the client's catalog,
presentation only, no wire change; fallback to today's label where the client cannot evaluate. Open owner item: one
server field when a protocol number opens (icheme-2 is the pollution batch's). Told to the owner in the same turn.

## 2026-11-16: the plunger pump, and main at 0.32.0

Shown PD_PUMP_RESEARCH_REVIEW.md's options, the owner answered: "Accept all" (a plunger pump of its own; the existing
law, no clearance term; work as flow times rise; no chilled head; a sheet type, no minimum-density row). Plan:
PLUNGER_PUMP_PLAN.md (stage S8).
Main moved to f305ac6b0, "Merge the pollution system batch (0.32.0)", pushed. So 0.32.0 is taken: this batch's
proposal becomes 0.33.0. icheme-2 and facts schema 26 are used on main; icheme-3 and 27 are the next free ones.
Overlap with this branch (39 files on both sides: 34 material records, MaterialCatalog, build.gradle, two guide
chapters, FluidPacketCodecTest, the indexes); the trial merge conflicts only in documentation/INDEX.md. Pitfall: a
material record changed on both sides must end with a revision token that is neither main's nor ours.

## 2026-11-16: the order of the plunger pump and the merge of main

To the request for one sub-thread (merge main 0.32.0, then the plunger pump) the owner wrote "merge when
everything's done"; asked which of two readings, the owner answered: "Pump first, merge last". So: the pump thread is
approved for the pump only (Opus high, new thread, this worktree, after S5 is checked), on the branch as it stands;
main 0.32.0 is merged into the branch at the very end, before the final lane. That merge is waiting work (Gradle):
its own approval request when it is due. The standing merge to main covers the pump too.

## 2026-11-16: the plunger pump's model

The owner: "model the pump head here" (the main thread makes the model; read as the 3D model). Three scratch forms were
rendered (`tmp/plunger-forms/forms-30.png`, `forms-sizes.png`): A monoblock, B valve pots and bottles, C vertical. The
owner: "Form A". The main thread draws `PlungerPumpDesign.build`; the S8 thread (approved: Opus high, new thread, this
worktree, after S5) builds the law, the kind, the simulator and the tests round it.

## 2026-11-16: found before the S8 brief (open, asked of the owner)

PLUNGER_PUMP_PLAN.md said "no number is planned". Wrong: a casing's law is saved in the fluid facts by type name
(`FluidFacts.control`, the "machine" record: "centrifugal_pump", "centrifugal_compressor", "vacuum_pump"; the vacuum pump
took schema 19). A plunger pump's law needs a new type name, so facts schema 27 (26 is the pollution batch's, on main).
The existing set-flow `FlowControl.Pump` needs no number but scales its rise limit by the suction density (D11 A2): 25 MPa
would be about 8 MPa on carbon dioxide at reduced density 0.7, against the pump's purpose. Asked: merge main 0.32.0 first
(schema 26 under the pump) and reserve 27 on main by a documentation-only commit, or keep "pump first, merge last" and
reconcile 26 with 27 at the final merge.

## 2026-11-16: the order, decided

Asked "Merge main 0.32.0 into the branch before the pump, and reserve facts schema 27 on main?" the owner answered "Keep
the order", then, mid-turn: "uh merge main first". Main had moved to 0.33.0 (`d756e121a`: schema 27, `icheme-3`). So:
main is merged into the branch first (sub-thread, Opus high, new, this worktree: the delegation named in the question);
fluid facts schema 28, checkpoint state format 51 and `icheme-4` are reserved on main (`66a87f9d5`, documents only, not
pushed); then the pump thread (approved earlier: Opus high, new, this worktree, pump only). Version proposal 0.34.0.

## S8 stop: form A against three equipment gates (2026-11-16)

Asked "Keep the nozzle rule and the longer guard on the two small pumps?" the owner answered "Keep both". So: on PP-3 and
PP-10 the coupling face is one block further back (the guard about 2 m long) and PP-10's discharge riser one block nearer
the block; the plunger pump's nozzles take the largest pipe whose neck stands clear, never less than the design pipe (a
column's rule, option (C) of 2026-10-27; the design-pipe cap if that rule does not carry over). No gate is exempted.

## Merge preparation, the final lane, the version (2026-11-16)

Asked "Approve the merge-preparation and final-lane threads, with version 0.34.0?" the owner answered "Approve all". So: a
sub-thread for merge preparation (Opus medium, new, this worktree); after it a sub-thread for the final lane (Opus medium,
new, this worktree); version 0.34.0. A clean lane merges to main without a further question (the earlier standing answer).

## The final lane: four unannounced moved printed results (2026-11-16)

The lane passed every test with no hold, but four printed values moved that no stage announced; the merge stopped. Asked
"Trace the three unexplained moved values before merging?" the owner answered "Trace first". So: a sub-thread (Opus high,
new, a scratch worktree of its own for the runs, at most five filtered runs, no code change) found the stage and the
cause of each.

## The moved values accepted, the merge (2026-11-16)

Asked "Accept the three moved values and merge to main as 0.34.0?" the owner answered "Accept and merge". So: the four
printed lines stand as moved (MOVED_RESULTS_TRACE_REVIEW.md, FINAL_LANE_REVIEW.md), nothing is re-recorded, the version
is 0.34.0 and the batch merges to main. Not pushed.
