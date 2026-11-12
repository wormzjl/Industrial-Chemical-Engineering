# P0: scene player and the film "inside the still"

Built 2026-11-12 on `claude/tutorial-system-chem-mod-950ca9` (plan sections 2, 3.1, 8).

## Built

- `science/tutorial/scene/` (no Minecraft, AWT or client type): `Scene` (title, actors, tracks, stops, builder, pure
  `at(t)`), `Track` (sealed: `Camera` smoothstep keyframes, `Visible`, `Cut`, `Caption`, `Dots` on a `Box` or `Line`,
  `Kind` with fixed colours), `Frame`, `Actor`, `Actors.equipment`, `Clip` (Sutherland-Hodgman), `DotMotion` (SplitMix64
  hash, plain arithmetic), `Projection`. `films/StillInside`, `films/Films` (by name).
- `client/gui/tutorial/`: `FilmLayout` (Minecraft-free, shared with the tool), `SceneView`, `FilmScreen`, `FilmCommand`
  (`/icheme-film still_inside`, registered in `IChemEClient`; P1's guide replaces it). `tools/tutorial-renders/`.
- Lang (en_us only): `tutorial.icheme.film.still_inside`, `.s1` to `.s3`, `tutorial.icheme.film.back/next/close/replay`.
- Behaviour-free edits: `ProcessUnit.StillPlan` gets `SHELL_FRONT`, `SHELL_BACK`, `gooseneck()`, read by
  `stillCondenser` (same numbers); new `ShapeRasterizer.stillParts(shape)`: pot box and tangent line, vapour path,
  condenser box, condensate path to the DISTILLATE nozzle, all from that plan.

## Decisions

1. Three actors, three cuts: one plane cannot open the pot, gooseneck and cooler (the dots' way runs along x and z).
   Polygons split by centroid: `pot` (plane through its axis, normal toward the three-quarter camera), `neck` (above the
   cooler; horizontal plane through the gooseneck, normal up), `cooler` (plane through its axis, normal -x).
2. Cut actors are two-sided in the tool and the view: culled, the far half's inside is all back faces and a cut shell
   would be empty. The shade stays the outer face's (the directional shade is symmetric in x and z). Uncut actors are
   culled, counter-clockwise front (`r x u = -f`, tested).
3. GUI depth: z = -500 - 15 * depth (m), so a 60 m deep actor stays in z -950 to -50, behind the kit's z >= 0 draws and
   inside the GUI projection. The rectangle's depth is cleared under a scissor first; states are restored after.
4. Tracks act on (from, to]: a stop shows what led up to it. At a stop the frame holds (dots too).
5. A dot's drift depends on its kind and index, not its track, so it keeps its place across tracks; a leaving dot runs
   from its place in the liquid to the path and is gone at the outlet.
6. The scene sits on the HMI glass (`Skin.well`); the camera scale is px/m on the design glass (432 by 148 GUI px),
   scaled down by `FilmLayout.fit`. The caption room is the longest caption's lines at the width (at least 3).
7. Back shows the stop before; Replay replays the way to this stop; Next jumps to the stop while playing, then goes on;
   at the last stop it reads Close.

## Tests and renders

- Passed: `SceneTest` 8, `StillInsideFilmTest` 4 (the film loads in a class loader refusing net.minecraft, com.mojang,
  net.neoforged, LWJGL), `HeatDiagramTest` 5, `KitLayoutTest` 17, `DeterministicMathSourceTest` 2, `equipmentGates`
  `ProcessUnitTest` 26, `ShapeRasterizerTest` 38. One first-run failure was the test's (it compared polygon arrays of two
  built scenes); after the fix only the two tutorial classes ran. `LanguageFilesTest`, `UnitTextTest` not run (rule).
- `renderTutorial -PtutorialFrames=2` into `tmp/tutorial-renders/`: `still_inside-stop1..3.png`, `-sheet.png`,
  `-frames.png`. Stop 1 the whole textured still; stop 2 the pot open, three kinds in the liquid; stop 3 light dots in
  the neck, arm, open cooler and drop to the spout, three middle dots on the way, heavy ones in the pot.
- In-game check: no inside-out shells, cut faces two-sided, dots hidden behind the shell, nothing over caption or
  buttons, scissor at the glass edges (GUI scales 2 to 4, 320 by 240); buffers freed on close and F3+T; drag, wheel,
  double click; copper sheen (the tool leaves it out); the command opens the screen after the chat closes.
