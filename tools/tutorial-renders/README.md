# tutorial-renders

Software renders of the tutorial films (batch `2026-11-12-tutorial-system`, phase P0), so the owner reviews every
film stop on a sheet instead of in the game.

`TutorialRender.java` calls `Scene.at` at each stop of each film (`science/tutorial/films/Films`) and draws the frame
through the same `Projection` the game's `SceneView` uses, on the HMI glass of a 464 by 254 film screen
(`client/gui/tutorial/FilmLayout`, the light panel skin's well), at 3 px a GUI px, with the English caption under it.
Polygons are rasterised with a z-buffer, the mod's sprites (`models/block/equipment_shell.json`), the default paint tint
and the game's directional shade per vertex (no sheen). Back faces are culled as the chunk renderer culls them; a cut
actor is drawn two-sided, as the game's view draws it. Dots are depth-tested discs on a dark rim, in the kinds' fixed
colours (`Track.Kind`). The caption is set in a sans-serif font, not the game's: its wrap can differ, its words cannot.

## Run

From the worktree root, after `./gradlew compileJava`:

```
./gradlew --offline --init-script tools/tutorial-renders/render.init.gradle renderTutorial -PtutorialRenderOut=tmp/tutorial-renders
```

Options: `-PtutorialFilms=still_inside` (a comma list; default every film), `-PtutorialFrames=n` (passed as
`-Dframes=n`: n frames between stops). The init script adds a source set for the tool alone, compiled against the main
output; it changes no production or test source set and runs no test.

## Outputs

- `<film>-stop<n>.png`: stop n.
- `<film>-sheet.png`: every stop side by side.
- `<film>-frames.png` (with frames): one row a stop's way, its start to its stop.

Each image's footer line gives the film, the time and the stop.

## Guide, tours and cards (P1)

`GuideRender.java` (default package, compiled with `tools/heat-gui-renders` against the main and test outputs; task
`renderTutorialGuide`, run after `renderTutorial`) draws the shipped `GuidePage` and `TourLayout` in the game's bitmap
font: `guide-<state>.png` (new, step-3-current, chapter-done; light and dark at 464 by 254), `guide-small.png` (320 by
240 window), `tour-guide.png`, `tour-fluid_device-still.png` (the still page built as GuiPrototypes builds it, with
its `?` key), `card-<name>.png` for every card (P2a: chain at 1, 5, 16, 40 carbons; barrel, medium at 0, 200 and
400 °C, light and heavy at 200 °C; products in each band; road unpressed and with one box of each mark; three_parts at
heat 0, 0.5, 1; each with a dark 320 by 240 frame), `guide-crude.png` (chapter 1 open). `FieldGuideIcon.java` writes the Field Guide's placeholder
texture: `java tools/tutorial-renders/FieldGuideIcon.java src/main/resources/assets/icheme/textures/item/field_guide.png`.

## History

- 2026-11-12, P0: the tool, for the film `still_inside`.
- 2026-11-12, P1: GuideRender (guide, tours, card) and FieldGuideIcon.
- 2026-11-12, P2a: card sheets for chain, barrel, products and road; the crude chapter.
- 2026-11-12, P2b: TutorialRender draws the whole film screen in the game font (overlays, fires, liquids) and prints
  "LABELS NOT PLACED" when a label has no room; films still_build and still_run; `card-pot_temperature.png` (start,
  early, before and past the cut, end) and `guide-still.png` (chapter 2 at both sizes).
