---
name: models-and-sfx
description: Rules, workflow and recipes for IChemE equipment 3D models and sound effects. Models: a unit kind's drawn mesh (UnitDesign, ProcessUnit, Mesh), its moving parts, block JSON models and their facing, layout rows, and the equipment drawings in tools/equipment-drawings. Sounds: running loops, one-shots, candidates for the owner, CC0 samples, sounds.json, subtitles and client playback (FxSounds, DriverSounds, MoverSounds). Use before designing, building, fixing or reviewing an equipment model or a sound, and whenever the owner comments on how equipment looks or sounds ("looks alike", "doesn't look like it", "not good", "too dirty", "cleaner", "facing reversed").
---

# Models and sound effects (IChemE)

These are the owner's rules and the project's tools for how equipment looks and sounds. They were collected from the batches
`2026-11-03-rotating-machinery` and `2026-11-10-custom-shader-system`, including its vacuum pump, chimney and prime mover
batches. The owner judges models on rendered drawings and judges sounds by ear, never in the dev client unless they ask for that.
`AGENTS.md` still governs saves, layouts, tests and delegation.

## Models

### Where a model lives

- A unit kind's design is one `UnitDesign` subclass in `science/equipment/`, for example `PumpDesign`, `VacuumPumpDesign`
  or `PrimeMoverDesign`. It holds the sizes (`minSize`, `maxSize`, `sizeStep`, `sizes`), the constructor rows, the
  datasheet `Line`s, `moves()` and `build(ProcessUnit)`.
- `ProcessUnit` is the shared drawing kit:
  - solids, nozzles (`front`, `end`, `radial`), pipes, motors, decks, ladders, stairs and concrete tables;
  - `grilledFan`, rings and the large motor and generator bodies.
  Reuse its parts before writing new ones.
- `Mesh` holds planar convex facets with a `Material` (a paint channel, or none) and a moving-part index.
- `ShapeRasterizer` turns a shape into cells, solids and ports. `RenderDetail.NORMAL` and `RenderDetail.LOW` change only the drawing.
- Block models such as the constructor and simulator cabinets are JSON, authored in `tools/pump-sprites/`. Their blockstates
  turn them by facing. Their collision boxes are built by `HorizontalShapes.byFacing` from the north boxes.

### Workflow

1. Physics and the real machine first. Find out what the real package looks like and which three to five cues make it
   recognisable. `references/model-cues.md` lists the cues by family.
2. Build it in the kind's design, from existing `ProcessUnit` parts.
3. Add drawing scenes to `tools/equipment-drawings/EngineeringExport.java` for the smallest size, the largest size and
   each option, such as a coil or a second stage.
4. Render with `./tools/equipment-drawings/update.ps1 -Check`, then with `-Only <kinds or scenes>`. Look at every
   `sheets/views/*-iso.png`, `*-+X.png` and `*--Z.png` before you call it done.
5. Show the owner the iso views. Copy them into `<worktree>/tmp/<name>/` and send them with SendUserFile. Where the
   look is an open choice, render two to four options and let the owner pick.
6. Gate the change and commit it. The gates are under "Verify a model" below.

### Rules

- Every family looks distinct. The owner's words on one model were "looks alike with other rotating equipments": a new
  kind must not read as a generic box and motor. Give it its own silhouette, such as a separator vessel, combustor cans,
  a filter house or a cooling tower stack.
- When the owner says a model "doesn't look like it at all", review it against the real package cue by cue. Send the
  fixes as a numbered list, for example G1 or E3, ordered by how much each fix helps.
- No flat black rectangles. They read as holes. Use louvre bars, filter rows, fins or a spoked grille. Only an open
  stack top or an open fan ring is dark.
- Proportions come from the real machine. A stack is tall and slender above a transition piece, not a squat silo.
  Ducts join what they feed, and no part floats in the air.
- Nozzle pipe blocks may stand next to each other. The owner lifted the old one-free-block rule on 2026-11-10. Lay
  nozzles out as the real machine has them, and keep existing layouts as they are. Leave large columns and gasifiers
  alone; report their defects instead.
- Saved structures refresh to the current layout (`EquipmentRefresh`). A nozzle pipe block that moves on an existing
  kind or size needs the owner's approval. When you extend a range, draw the new look only above the old range, as the
  steam turbine sections above 45 MW do, so that existing sizes keep their rows.
- New kinds join the simulator and the constructor (`AGENTS.md`, "New equipment"): `FlowsheetCatalog`, the tile and lang
  line (`FlowsheetPaletteCoverageTest`), and a `ConstructorColumn.Equipment` category.

### Moving parts

- A part turns through the moving-part index on its facets. `UnitDesign.moves()` says the kind has moving parts, and
  `MachineAnimation` draws them apart from the chunk mesh with `RotationAnimation`. `VisualRotation` maps the process rpm
  to the speed you see.
- Use the existing turning parts: `grilledFan` at `ProcessUnit.FAN_SPEED`, `PUMP_SPEED` or `CRANK_SPEED`, the coupling
  hub, a load shaft in an open guard, a damper or flywheel rim.
- Moving parts use unpainted materials only, so they need no tint.

### Block models and facing

- The front of a block model, with its controls or screen, faces the player who placed it.
- If the block's facing property also means something else, turn the model in the blockstate, not the property, and
  read the collision shape of the opposite facing. For example, the constructor's `FACING` is its build direction (owner
  fix 2026-11-10). Changing the property's meaning would move saved builds.

### Verify a model

- Run the layout table, `EquipmentLayoutGoldenTest` in `equipmentGates`. A layout change updates `src/test/resources/compat/layouts/layouts.txt`.
  Check that only the rows you meant to change moved.
- Also run the shape and raster tests the change reaches: `ShapeRasterizerTest`, `ProcessUnitTest`, `EquipmentShapeCompatTest`
  and the kind's own tests. Run them with `--tests`, never the whole suite.
- A shape-field change needs a corpus image under `src/test/resources/compat/` (`AGENTS.md`, saves).
- At most one in-game check per task, and only when the owner asks for it.

## Sound effects

### Where sounds live

- Files: `src/main/resources/assets/icheme/sounds/<name>.ogg`. Each has an entry in `assets/icheme/sounds.json` with
  `"stream": false` and the subtitle key `subtitles.icheme.<name>`. Add the subtitle line to `en_us.json` only.
- Registration: `ModBlocks` (`loop("...")` and the one-shot events).
- Playback is client-only and adds no server state (`AGENTS.md`, engine schedule).
  - `FxSounds` is the single loop budget: at most 12 loops, chosen by level at the listener four times a second, with 3 dB of
    hysteresis; sources of one kind within 6 m merge.
  - The machines' loops come from their watches: `DriverSounds` for drivers and `MoverSounds` for casings. The noise
    watch covers valves, vents, fans, water and burners.
- Pitch is the rpm over the loop's authored rpm, clamped to 0.5 to 2 (`UnitDesign.soundPitch` and the `*_LOOP_RPM`
  constants). When a family's speeds span more than four times, author one loop per speed band. Engines have one at
  1500 rpm and one at 600 rpm.
- A one-shot plays on an event that the client already sees:
  - a block event with client prediction (the hand valve turn, `TURN_EVENT`);
  - the speed crossing zero in the driver watch (the prime movers' start and stop).

### Workflow

1. Physics first: take the sound's frequencies and level from the machine and the process.
   - Blade pass = rpm/60 × blades.
   - Firing = cylinders × rpm/120.
   - Gear mesh = teeth × rpm/60.
   - Motor hum = 100 Hz.
   - A valve's level follows its pressure drop: Lw = 70 + 20 log10(ΔP / 20 kPa).
2. Synthesise three candidates, A, B and C, named `<sound>_<letter>_<style>.ogg`, that differ in character. Synthesis
   is the default route (decision X8 A). Recipes are in `references/sound-recipes.md`.
3. Put them in `<worktree>/tmp/<name>/` and in the main checkout's `research/<batch>/sounds/`. Send them to the owner
   with SendUserFile (`display: attach`), with one line per candidate. Ask the owner to pick. Keep the current sound
   in place until they do.
4. Install the pick:
   - copy it into `assets/icheme/sounds/`;
   - wire it (sounds.json, registration, the watch or the event);
   - record the owner's words, bytes, RMS and peak in the tool's README history.
5. The owner judges sounds by ear. Offer an in-game check; do not start one.

### The owner's taste

- "Too dirty" and "needs to be cleaner" mean too much broadband noise. Build pulses as damped modal rings (two or three
  modes, a few ms each) rather than noise bursts. Keep the broadband floor 12 to 15 dB under the pulses, halve the
  jitter, and keep the low end, such as the exhaust thump or the body. When asked for cleaner, offer two steps: noise down,
  and no noise except a faint low rumble.
- Avoid a plain buzz. Give each pulse its own decay, add small cycle-to-cycle jitter, and add the machine's secondary sources
  (valve train, turbo whistle, inlet hiss) at a lower level.
- A recorded sample is used only when the owner chooses it.
  - Downloading needs the owner's explicit yes, with the file name, source and size.
  - Use the CC0 previews on freesound.org. Keep the downloads in git-ignored `tmp/<name>/` and never stage them.
  - Record the provenance (author, id, URL, licence) in the tool README.
  - Clean a recording with `tools/process-sounds/valve_turn_cc0.py clean`: spectral subtraction with a profile from the
    clip's own quiet part, then a downward expander.

### Tools

- `tools/process-sounds/`: the process loops (`make_loops.py`) and the candidate scripts (`valve_candidates.py`,
  `ejector_candidates.py`, `valve_turn_synth.py`, `valve_turn_cc0.py`, `prime_candidates.py`).
- `tools/machine-sounds/`: the machine loops (pump, compressor, motor, turbine).
- Run them with the authoring Python, isolated:
  `C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe -I <script> <output folder>`.
  The scripts add `tools/pump-compressor-controls/python-deps` (numpy, soundfile, which also reads MP3) to their path.
- Write output only under `<worktree>/tmp/<name>/`, with a row in `tmp/INDEX.md`.

### Verify a sound

- Loop: the script prints bytes, RMS, peak, and the seam step against the mean step between samples. The seam step
  should be of the order of the mean step or below.
- RMS: the pump loop's 0.0926 (a pilot flame a third of it).
- One-shot: silent first and last samples, peak 0.7.
- A sound-only change needs no Gradle run. Wiring code runs the tests of the classes it changed. The subtitle lines
  wait for the translation commit (`LanguageFilesTest`).
