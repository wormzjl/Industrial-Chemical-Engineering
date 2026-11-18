# 2026-11-18-tutorial-photos

The Field Guide shows real photographs, at least one per chapter, that the mod may redistribute.

## Scope and owner rules

- Every photo is redistributable: public domain or CC0, or CC BY 2.0 with its credit in the caption (one photo). Credits ship in the jar, `assets/icheme/tutorial/PHOTO_CREDITS.md`.
- No photo shows a person.
- Display option B (picked from rendered sheets): a row above a chapter's steps with a thumbnail, the caption and View; View opens a viewer in the glass with the photo whole, its caption and Back, and keys across the top where a chapter has several photos (crude: seven, one per part of the barrel).
- 384 px on the longest side, 256 colours (owner, 2026-11-18): 512 and 448 px missed the 1.5 MB budget.

## Documents

- [TUTORIAL_PHOTOS_REVIEW.md](TUTORIAL_PHOTOS_REVIEW.md): the source table, edits, sizes and what is not verified.

## Code areas

- `science/tutorial/guide/`: `Guide.Chapter.photos`, `GuideFile` (`photos`), `Photos`, `PhotoFile`.
- `client/gui/tutorial/`: `GuidePage` (`PhotoRow`, `PhotoPlace`, `Input.withPhotos`), `GuideScreen` (`photo`), `GuideClient.photos()`.
- Assets: `assets/icheme/textures/tutorial/photo/*.png`, `assets/icheme/tutorial/photos.json`, `PHOTO_CREDITS.md`, `guide.json`, `en_us.json`.
- Gates: `PhotoCoverageTest` (new), `GuideFileTest`, `GuidePageTest`, `TutorialTextTest`.
- Tools: `tools/tutorial-photos/` (new), `tools/tutorial-renders/PhotoRender.java`, `tools/heat-gui-renders/HeatDiagramRender.java` (photo sprites in colour), `GuiPrototypes.java` (a stale `PumpSizing.duty` call fixed so the render tools compile).

## Open items

- No check in a dev client: the screen is judged on software renders only (bitmap font, Java2D bilinear sampling, not the game's texture filter).
- `DrawCallTest` lays out the guide without photos, so its counts do not cover the row or the viewer.
- Translations: the new `tutorial.icheme.photo.*` and `tutorial.icheme.guide.view` lines are in `en_us.json` only, for the translation commit.

## History

- 2026-11-18: built (option B, 14 photos at 384 px, 1.35 MB); 201 selected test classes pass; renders in the worktree's `tmp/tutorial-photos/shipped/`.
