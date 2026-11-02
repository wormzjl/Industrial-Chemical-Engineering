# Pipeline shutoff valve review

2026-10-23. Source `4dd96fde`, changelog/delivery `beb5c2fc`, branch `codex/pipeline-shutoff-valve`, base `19117af7`; owned pipeline-tool worktree. Not merged; version remains 0.11.2.

## Expected behaviour

An ideal open cutoff has the ordinary pipe resistance. Closed, it passes zero flow in either direction and separates the two hydraulic networks unless a real bypass joins them. Equipment retains its material; the existing topology rebuild rule still remints junction buffers.

## Previous behaviour

Individual pipes connected on all six faces and had no manual cutoff accessory or committed lever state. A zero-flow actuator alone would not establish the requested independent islands.

## Implementation

- Approved before implementation: [Valtorc Series 180](https://valtorc.com/flanged-ball-valve-full-port-150-300-specs/), metal split casting, bolted flanges and quarter-turn lever. No copied branding or bitmap assets.
- Accessory stacks to 64, creative/commands, no recipe. Server permissions, either-hand installation, survival consumption/recovery, duplicate no-op and mutual sight-glass exclusion. Only straight/end/unconnected pipes qualify; projected registry neighbours also validate the axis.
- Installation retains the block/entity/id/bore/spec. Existing facing locks its axis; later side pipes cannot connect or eject it. Only pipes expose the validated NONE/OPEN/CLOSED blockstate.
- Empty both hands and right-click to queue a toggle. No GUI. Held debugger/Pipeline Tool actions keep priority. Projected registration edits compose rapid toggles and bore changes; committed static state drives the lever through the existing engine edit schedule.
- Physical registrations use explicit codes NONE=0, OPEN=1, CLOSED=2. CLOSED has no hydraulic contacts; visible axial joints remain. No new solver, worker, tick calculation, process stock packet or custom payload.
- Checkpoint 17 / unit 10 persist active and pending cutoff state and reject unknown codes or cutoffs on non-pipes. Fresh worlds only; no migration. Existing incompatible-format reset behaviour remains.
- Production mesh scales with existing PipeSizes, including its minimum drawn outside diameter of 1/16 m. Horizontal stems point up; vertical stems north. Lever parallel=open/crosswise=closed. Large handles may overhang decoratively; collision remains ordinary pipe collision.

## Verification

- `gradlew.bat test --tests '*PipelineShutoffValveTest' jar --max-workers=4`: PASS, four tests, zero failures/errors/skips; dev client closed. Test time 0.264 s; Gradle reported 4 s. No full suite, GameTests, GUI or in-game checks.
- Tests cover three axes, isolation/reopening, bypasses, unchanged charged equipment stock/id, queued toggles plus resizing, full checkpoint/unit round-trip of active/pending state, clocks/material and invalid-state rejection. Edits issue no forced solver command.
- Actual production-mesh software sheets reviewed: [preview](../../research/2026-10-23-pipeline-shutoff-valve/renders/preview.png), [axes/sizes](../../research/2026-10-23-pipeline-shutoff-valve/renders/axes-and-sizes.png), [accessory/reducer details](../../research/2026-10-23-pipeline-shutoff-valve/renders/details.png). Eighteen axis/size/state cases plus preview/details; flat material lighting, not Minecraft shaders.
- Offline harness/evidence: `tools/pipeline-shutoff-valve/`; no temporary tracked tooling. Code-guide contracts updated in fluid-runtime, equipment, client-gui, verification and README. Fluid constitutive science unchanged.

## Owner in-game checklist

- Fresh world: install from either hand on horizontal/vertical/straight end/isolated sections; reject corners, junctions, sight glass and duplicate placement without consumption; survival break returns one accessory plus pipe.
- Check open/closed lever and mechanical joints; a side pipe cannot connect; open/close separates/rejoins networks while a bypass remains effective. Check debugger and both Pipeline Tool actions, rapid edits, chunk unload/reload and saved pending toggles.
- No automatic merge or version bump. A later merge retains the repository's full required checks and owner-selected version.

## Subsequent owner-requested integration

See [MERGE_REVIEW.md](MERGE_REVIEW.md): fast-forwarded into `claude/floating-roof-tanks-81d68b` at `beb5c2fc`, ten local files preserved, required quick/slow checks run with only five previously reproduced failures, target jar passed and client launch requested. No main merge or version bump.
