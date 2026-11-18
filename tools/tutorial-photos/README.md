# tutorial-photos

Writes the Field Guide's photographs as the game ships them (batch `2026-11-18-tutorial-photos`).

`PhotoAssets.java` (plain Java, no library) takes each picked photo from the originals folder, crops it where its row in `PHOTOS` says, scales it so its longest side is 384 px (bicubic, in halving passes), reduces it to 256 colours (median cut and Floyd-Steinberg dithering, written in the tool) and writes an indexed PNG at the writer's strongest compression. It also writes the manifest `photos.json`: each PNG's size and its thumbnail's focus as shares.

## Run

From the worktree root:

```
java tools/tutorial-photos/PhotoAssets.java tmp/tutorial-photos/originals src/main/resources/assets/icheme/textures/tutorial/photo src/main/resources/assets/icheme/tutorial/photos.json
```

Optional fourth and fifth arguments: the longest side in px (default 384) and the dither's error share (default 1). The originals are the 1280 px copies from Wikimedia Commons (git-ignored, in the worktree's `tmp/tutorial-photos/originals/`); their sources and licences are in `src/main/resources/assets/icheme/tutorial/PHOTO_CREDITS.md`.

## Entry points

- `PHOTOS`: id, original file, crop (`x, y, w, h` in original pixels, or none), focus.
- `quantise`: the median cut and the dither.

## History

- 2026-11-18: written. Measured totals of the 14 PNGs: 2.38 MB at 512 px, 1.84 MB at 448 px, 1.35 MB at 384 px (budget 1.5 MB, 150 KB each); the owner chose 384 px. A damped dither (.75, .5) saved under 2 %.
