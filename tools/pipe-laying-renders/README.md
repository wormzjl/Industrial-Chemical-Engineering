# pipe-laying-renders

Purpose: offline software renders of pipe racks and transition pipes, drawn from the same polygons the chunk models use (`RackGeometry.polygons`, `RackGeometry.lane`, `LineGeometry.polygons`), with the mod's sprites. These renders judged the drawing work of W3, W4 and the layered rack (R4) without a dev client.

Batch: `documentation/2026-10-26-pipe-laying-system/`. No code in a tracked path. The rasteriser is adapted from `tools/storage-vessel-renders/StorageRender`.

## How to run

From the worktree root, after `./gradlew compileJava processResources`:

```
bash tools/pipe-laying-renders/render.sh [out dir]
```

The default output is `build/tmp/pipe-laying-render/`. The script compiles `PipeLayingRender.java` against `build/classes/java/main` and renders the layered rack scenes `tier`, `exits`, `lowered`, `corner`, `widths` and `stacked`. Every lane passes `RackGeometry.fits` before it is drawn, or the scene stops. The `left` and `right` views look along a run (end-on): `java ... PipeLayingRender tier <prefix> left`.

## Entry points

- `PipeLayingRender.java`, arguments `scene outPrefix [views]`. Views as in StorageRender: front, back, left, right, top, iso, iso2, iso3, low.
  - `tier`: a run facing east with a 2", a 6" and a 14" line resting on the beams (support P2).
  - `exits`: a run facing east whose lines leave every way: on out of the end, up, under the tier and out sideways, flat out sideways; and a crossing over the tier. Each exit goes into a transition pipe.
  - `lowered`: a 28" line on lowered beams, and a 22" line (the largest resting one) turning down past the front bent's beam.
  - `corner`: a run facing east, a corner turning right, a run facing south; four lines in nested bends.
  - `widths`: the largest line of each width (and a 28" line) leaving a rack's front into a transition pipe that turns.
  - `stacked`: two tiers, the lower one's posts running up to the upper one.
  - `flanges`: a run facing east, x -1 to 12, on two tiers framed by `RackGeometry.steel` as in a world, lines on every position, blind-flanged at both run ends, spool pairs every 12 blocks staggered three a position (x 0, 3, 6, 9). `-Dbig=true` puts a 20" line on the upper tier.
  - Every scene draws each lane with its spool pair where `RackGeometry.spool` puts one, the cell's coordinate along its run as in the world.
  - `grid`: bare racks from `-Dlayout` (levels split by `/`, rows along z by `;`, one character per cell along x: `N`, `S`, `E`, `W` the look, lower case sneaking, or `.`), placed in reading order, levels first, each shaped once by `RackGeometry.placed` and framed by `steel` as in a world. Each cell's frame is printed. Example: `-Dlayout="NN;NN;NN"`, a 2x3 group placed looking north.
  - `junctions`: the junction cells of `RACK_JUNCTION_PLAN.md` as the mod draws them. `-Djunction=corner|tee|cross|wide`. A tee's lanes use its legs (`RUN`, `TURN_LEFT`, `TURN_RIGHT`); every lane passes `RackGeometry.fits`.
  - `proposal`: the sturdy frame of `RACK_FRAME_PLAN.md` as the mod draws it. Racks facing east, x 0 to 5, two wide, on `-Dtiers` levels (1 or 2), with a riser and a flat exit. `-Dexits=true` with `-Dtiers=1` is the clash rig: exits under and over the tier at a bent and at a run end, crossings under the tier, and a 28" line on lowered beams.
  - Every scene draws the steel with `RackGeometry.polygons(frame, lanes)`, the chunk model's mesh.
- `-Dclash=true` on `PipeLayingRender`: samples every pipe and flange surface every 0.4/32 m and tests it against every steel box and bar the mod draws (`RackGeometry.steelwork(frame, lanes).drawn()`). It prints the samples inside steel and the closest approach (0.00 = lines resting on the beam tops). `-Dclash.eps` (1/32 m, default 0.05) is the tolerance.
- `render.sh`: the build-and-render script.
- `Montage.java`: a labelled before/after sheet of two renders, optionally a crop scaled up: `java tools/pipe-laying-renders/Montage.java <out.png> <before.png> <after.png> [x0 y0 x1 y1 scale]`.
- `PaintedSprites.java`: makes the neutral painted line sprites from the bare ones, grain kept, scaled to a mean value: `java tools/pipe-laying-renders/PaintedSprites.java <in.png> <out.png> <mean>` (pipe 236, flange 200), output to `src/main/resources/assets/icheme/textures/block/painted_pipe.png` and `painted_flange.png`.
- `-Dpaint=RRGGBB` on `PipeLayingRender`: draws every line painted, as the block tint does (`Paint.tint` on the painted sprites); `-Dsecondary=RRGGBB` paints the flanges another colour (default: the paint).
- `FlangeProbe.java`: rack flange placement by line size over every position, both brace directions and six frames: the spools with no room, the open ends capped, the spool and blind-flange depths (RACK_FLANGES_AIM_PLAN section 1). Compile and run it against `build/classes/java/main`, as its header says.
- `Crop.java`: crops a render and scales it up by a whole factor: `java tools/pipe-laying-renders/Crop.java <in.png> <out.png> x0 y0 x1 y1 scale`.
- `ToolTexture.java`: draws the laying tool's 32 x 32 item texture in the accessory wrench's style (flat tones, a light streak, a dark outline): `java tools/pipe-laying-renders/ToolTexture.java <out.png> [preview.png]`, then copy `out.png` to `src/main/resources/assets/icheme/textures/item/pipe_laying_tool.png`.
- `renders-2026-10-26/`: the renders the reviews used. `layers-*.png` judged the layered rack (R4). The others are from the W4 review. `paint-bare-iso.png`, `paint-red-iso.png` (A72920) and `paint-blue-iso.png` (2271B3) judged the paint selector. `paint-two-iso.png` and `paint-two-iso2.png` (2271B3 pipe, A72920 flanges) judged the secondary paint. `frame-*.png` are the proposal renders sent to the owner (`t2-now`, `t2-all`, `t2-all-low`, `t1-all`). `junction-*.png` are the junction model renders (corner, tee, cross, wide). `sturdy-sheet-*.png` (before and after), `frame-sturdy-*.png` and `junction-sturdy-*.png` are the sturdy frame renders; `frame-sturdy-clash-rig-iso2.png` is the clash rig. `built-*.png` are the mod's own steel after the build (`t2-iso`, the tee, the two-wide turn, the crossing, a 2x3 group). `flanges-*.png` are the lane flanges (iso, the east and west run ends cropped, the 20" variant).

## History

- 2026-10-26 (W4, commit 031e855d): the renders found two faults:
  - the corner case left its block, fixed by choosing among three candidate paths;
  - the width-4 turn was lumpy (five ball corners), fixed by a direct two-corner ball path.
  They also showed that a wide lane's turn uses ball corners: OD 0.734 leaves about 0.37 m of bend radius, below 1.05 r.
- 2026-10-26 (commit 14bc86d9): `ToolTexture.java` added; the texture replaced the blaze-rod placeholder.
- 2026-10-26 (commit 9d8f1aae): `PaintedSprites.java` and the `-Dpaint` switch added for the line paint selector.
- 2026-10-26 (commit d7c5149e): the `-Dsecondary` switch added for the secondary line paint.
- 2026-10-26 (R4, layered rack): the scenes rewritten for facing, corners, levels and the lowered beam; the diagonal-slot scenes and `turn1` to `turn4` removed. The renders showed no new fault. The one clash, a 20" riser's bend sweeping the front bent's beam by about 1 mm, was found by `PipeRackGeometryTest` and fixed with the lane bend cap (`RackGeometry.BEND`, 7/16 m).
- 2026-10-26 (commit 131857a3, owner report on groups and the bare frame): the `grid` scene showed that mixed facings break a 2x3 group into corners (RACK_FRAME_PLAN F3). The `proposal` scene draws the richer frame from the owner's photos, for decision F.
- 2026-10-26 (junction model, no code change): the `junctions` scene renders the corner, tee, crossing and two-wide turn of RACK_JUNCTION_PLAN for the owner. Found: a stacked junction's inner-corner columns would come within the clearance of a wide line's bend (about 6/32 m against 6.2/32 m for a 14" line), a check for the build.
- 2026-10-26 (sturdy frame, owner: "Structure needs to look more sturdy"; no mod code change): `proposed` and `junctionFrame` made heavier (RACK_FRAME_PLAN 3a), with doubled bents (`sturdyBents`, `ends`) and junction columns at the outer corners only. `-Dclash`, `-Dexits` and `Montage.java` (before/after sheets) were added. Clash check: no sample inside steel in proposal t1, t2, the clash rig, tee, corner, cross and wide.
- 2026-10-26 (commit 527ba706, junction model and sturdy frame built in the mod): the scenes draw the mod's own steel (`RackGeometry.polygons`, `steelwork`) and tee lanes; `proposed`, `junctionFrame`, `sturdyBents`, `ends`, the harness `box`, `bar` and `column`, and `-Dparts` removed. `grid` places each cell by `RackGeometry.placed`. Clash check: no sample inside steel in 13 scenes (proposal t1, t2, the clash rig, the four junctions, `tier`, `exits`, `lowered`, `corner`, `widths`, `stacked`).
- 2026-10-26 (commits 84da49e5 and 3f6a39e6, rack flanges and slot aim): lanes are drawn with their spool pairs (`lanes` helper, `RackGeometry.spool` at the cell's coordinate along its run); new `flanges` scene, `FlangeProbe.java` and `Crop.java`. Clash check on `flanges`: no sample inside steel of 2.5 million. Renders `renders-2026-10-26/flanges-*.png` (iso, both run ends, the 20" variant).

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 63 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/pipe-laying-renders/`, at the same relative paths.
