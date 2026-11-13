# Code review: commit e135ebcdc against main, batches tutorial-system and still-heat-per-distillate

Read only; nothing run. Paths from `src/main/java/com/wormzjl/icheme/`.

## Findings, most severe first

1. CONFIRMED `network/FluidNetwork.java:435`: the edit handler's `world.view(d.id())` reads through `presented` ->
   `coordinator.presentation` -> `materialise(island,true)`, so a packet handler advances a certified island (its
   committed tick and its ledger). Fire set to 0 on a resting still: its island moves off schedule. Fix: use
   `world.stored(() -> world.view(id))` or the menu's last published status.
2. CONFIRMED `runtime/fluid/Stills.java:529-530` and `science/fluid/network/StillHead.java:81`: `shares(r)` is null
   while `r.solved` is null, and `run` returns `Failed` with no one-stage fallback (main's `critical()` condensed the
   heavy ends). A column that never converges, and the first span after a world load, vent every arrived mole and give
   no distillate. The sheet falls back at `BatchStill:410`. Fix: the same one-stage solve in `StillHead.run`.
3. CONFIRMED `data/icheme/advancement/tutorial/building/site.json` and `simulator/send.json`: both use `design_sent`
   with no condition. The first Send in chapter 4 also completes `building/site`, a step of the next chapter. Fix: give
   site its own condition, or remove one of the two.
4. CONFIRMED `client/gui/tutorial/GuideScreen.java:182`: `render` runs `place()` on every
   frame, and line 195 builds `PagePicture.of` (`Films.build`) inside `render`. `GuideHud.java:46` runs translations,
   `Marked.parse` and the cut on every frame, and `HelpTour.render` resolves every anchor. Fix: lay out and build only
   when progress, size or the open page changes.
5. CONFIRMED `client/gui/tutorial/GuideScreen.java:322`: the Tour key calls `TourFile.load()` on each press. That
   reads `tours.json` again, builds each tour's film to validate it (`TourFile.java:42`), and reads `guide.json` per
   `show` (`:44`). The first `HelpTour.of` pays the same. Fix: use `HelpTour.of`, and check names, not builds.
6. PLAUSIBLE `network/TutorialNetwork.java:93-117`: `Hello` has no limit. Each one walks every server advancement
   (recipes too) and sends `Progress`, so a client that floods Hello multiplies server work. Fix: one answer per
   login, or a rate limit like `admitEdit`.
7. CONFIRMED `client/IChemEClient.java:31`: the P0 probe `/icheme-film` (`FilmCommand`) is still registered, although
   its own doc says P1 replaced it. Fix: remove it, or move it to `tools/` (merge-prep).
8. CONFIRMED, no server path today: 13 classes in `science/tutorial/cards/` and `scene/Labels.java` import
   `client.gui.common.kit.*`; `UnitMap` also imports `client.gui.flowsheet.Sheet{Layout,Painter,State,View}`.
   PLAUSIBLE, harmless now: `TutorialTriggers.java:125` makes `network/SimulatorResults` load on a dedicated server,
   which imports `client.gui.flowsheet.SimulatorHost` (no Minecraft client type). Fix: move them out.
9. Tests: `TutorialNetworkCodecTest` scans source text for "icheme.client", so it misses the load in 8;
   `EquipmentInfoCoverageTest` and `FilmChecks` assert source substrings. Fix: a loader that refuses client packages.

## Unconfirmed
- `StillColumn` partial-condenser numerics (ratio secant, `bracketed`, `dry`): read, not traced. A head below the
  outlet gives a near-zero `condenserDuty`; with a closed valve, `BatchStill:457` (used / dt / duty) is then huge.
  To confirm: a one-stage solve on a propane-rich pot with water at 290 K.
- `Balance.used` "lost its bubble point" at `high = most` (possible on main; the new bracket reaches `most` more often).

## Checked and found sound
- Server safety and network: item `use` behind `isClientSide`, client payload lambdas, JEI compileOnly; MAIN thread,
  StepDone bounded, a forged step completes only `guide_step` advancements.
- Criteria and saves: criteria fire on data already sent or stored; 51 steps each have an advancement; parents, codecs
  and enums match; loot table present; `Solved` is not saved.
- Still law: mass and energy close (step, `divide`, `give/owe`); empty pot, closed or non-boiling charge, zero firing;
  `outlet()` reads at commit. Client: `Scene.at` is pure; first and last stops; empty buffers guarded; tours take input.
- Not covered: GuidePage, TourLayout and FilmOverlay geometry; card bodies; film builders.
