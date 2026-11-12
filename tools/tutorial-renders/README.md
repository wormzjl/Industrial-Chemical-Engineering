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

## History

- 2026-11-12, P0: the tool, for the film `still_inside`.
