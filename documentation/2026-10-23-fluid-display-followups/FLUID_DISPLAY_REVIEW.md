# Fluid display follow-up review

2026-10-23. Source `c1e567da`, branch `codex/fluid-display-followups`, base `beb5c2fc`; owned pipeline-tool worktree. Version remains 0.11.2.

## Expected behaviour

A compact accessory should occupy most of its inventory slot. A generator's phase ratio describes its equilibrium feed at configured temperature and absolute pressure, even when isolated; volume and mass fractions can differ greatly because phase densities differ.

## Previous behaviour and diagnosis

- Both 0.25 m item meshes inherited vanilla block GUI scale 0.625: sight glass 4.607 x 5.036 px; valve 3.936 x 4.572 px in a 16 px slot. The installed diameter-scaling model itself was not the cause.
- The screenshot's WTI at 200°C and 3 bar absolute reproduces exactly: liquid 2.063323217%, vapour 97.936676783% by volume; liquid 66.473525178%, vapour 33.526474822% by mass. Densities are 747.467047460 and 7.942425207 kg/m3. One m3 contains 23.201208434 kg total.
- Isolated generator compilation preserves its configured source node/state exactly, including when switched Off. The source state's volume percentages were consistent with the existing model; no phase-law correction was justified. These are game-model results, not independent experimental WTI PVT validation.
- A separate 3 bar gauge interpretation (401325 Pa absolute) gives 3.827448925% liquid by volume and 73.679662364% by mass, confirming the screenshot matches 3 bar absolute. The GUI input is explicitly Pa absolute.

## Approved change

- Item-only display.gui scale: sight glass 1.8; valve 2.0, facing 45° with -1 px vertical translation to expose the yellow lever. Final bounds: sight glass 13.268 x 14.504 px; valve 12.594 x 13.033 px, entirely inside the slot. World geometry and other display contexts inherit as before.
- Generator rail gains a normal keyboard/mouse button, Volume % / Mass %, and matching bar heading/tooltip. Its session preference is independent of composition entry and flow units. Other devices retain volume bars.
- Mass view sums published phase moles times the menu's kg/mol molecular weights, plus solids.massKg(). Volume view uses the existing phase/solid volumes. The selector reads cached data only and survives scheduled publications without a server input or forced solve.
- The selector follows rail scrolling and is hidden when its full button is outside the clip or no state is available. Existing controls and drafts retain their behaviour.
- Checkpoint 17 / unit 10 and fluid-13 remain unchanged. No new physics, data set, save/wire format or per-tick process calculation.

## Verification and delivery

- `gradlew.bat jar --max-workers=4` passed. No tests or automated GUI checks were run while the dev client was active; no new test class was added for these display-only changes.
- Fresh standalone GeneratorPhaseAudit uses the production preset/spec initializer and checks isolated compile identity/state equality. AccessoryIconRender uses actual production meshes and JSON GUI transforms, includes vanilla-before values and rejects clipping. [Preview](../../research/2026-10-23-fluid-display-followups/renders/accessory-icons.png) reviewed; flat material rendering, not Minecraft textures/shaders.
- Offline tools/evidence: canonical `tools/fluid-display-followups/`; no tracked harness or temporary instrumentation. Code guide: client-gui and README updated from the source; runtime/constitutive science contracts unchanged.
- Integrated into claude/floating-roof-tanks-81d68b at a591404f on 2026-10-24 after the user's saved client exit and resumed tracing; see [MERGE_REVIEW.md](MERGE_REVIEW.md). Target jar passed, ten local files preserved and client relaunched. In-game selector interaction, scrolling and final textured icon review remain manual owner checks.
