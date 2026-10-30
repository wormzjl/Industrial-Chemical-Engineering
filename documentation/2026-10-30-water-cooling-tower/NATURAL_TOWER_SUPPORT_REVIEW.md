# Natural tower support and mesh review

## Expected behaviour

The preferred reference has an open perimeter of concrete V-struts, a continuous ring and internal supports below the packing. Its open support band is about one tenth of the visible tower height. The final tower uses `height = 1.3 × diameter`, giving heights of 52, 78 and 104 m for diameters of 40, 60 and 80 m. The clear air inlet is `0.14 × diameter` above the 2 m basin rim, or 5.6–11.2 m; the shell base is therefore 7.6–13.2 m above grade and the throat stays at 75% of total height. Proportion research used [BHEL's published `H/D = 1.2–1.55` range](https://www.bhel.com/sites/default/files/Vol%20IE%20Technical%20Specifications%20%20Plot%20Plan%20final.pdf) and the [IJERT reference tower at 143.5 m high × 110 m base diameter](https://www.ijert.org/optimality-study-in-linear-static-analysis-of-hyperbolic-cooling-towers-with-different-shell-elements); these guide geometry, not process calibration.

## What the code did instead

The original natural tower had a fixed 8 m support zone from `y = 2` to `y = 10`, crossed steel perimeter ties and no internal grillage. Its packing and inlet stayed at fixed elevations for every diameter. Commit `0491f75d` introduced the requested structure but incorrectly raised the support zone to 15% of total height; the owner rejected that proportion against the latest photo. The tower also remained too tall at `2 × diameter`, and its supports were still too high after radial correction.

The original chimney used 4 m vertical bands and roughly 2 m circumferential chords; an 80 m model contained 23,016 triangles, including 19,152 chimney triangles. After the inclined supports were added, the owner requested a smaller chimney above them rather than a reversion or inversion of the V-struts.

## Correction for owner validation

Commits `0491f75d` and `0fd07699` derive the shell base, packing, distributor and hot-water inlet from the support height. Commit `d97ca7db` continues the same inclined V-struts through the basin rim to the floor at `y = 0`, without vertical lower extensions. Commit `108e495e` retains those V-struts and uniformly multiplies every chimney radius above them by 0.9. The support ring's outer edge meets the resized shell foot; packing fits inside its radius at packing top, and the inlet weld follows the shell while basin and external nozzle positions stay fixed. Commit `59fb5df6` replaces the former `2 × diameter` height with shared `CoolingTower.naturalHeight(double)` at `1.3 × diameter`. Commit `bd1444ee` then reduces clear support height from `0.20` to `0.14 × diameter`, exactly 70% of its former value, without changing the basin, total height, throat fraction or inclined V arrangement.

Thirty-two concrete V-strut stations join the concrete ring. Six steel grillage beams on nine vertical internal posts carry the packing; strut and beam sections scale with diameter. Packing, internal posts, distributor and inlet follow the lower shell base. Buried bar ends omit faces without removing oriented collision.

The chimney uses 8 m bands and roughly 4 m chords. Only chimney facets above the derived shell base enter the static VBO; supports and ordinary equipment retain chunk rendering. The final 80 m model contains 5,744 triangles, including 3,024 in the static chimney. This is a geometry count, not a measured FPS result.

Thirty-five focused checks passed for `0491f75d` before its later adjustments. On the final `bd1444ee` code, standalone compilation, 40/80 m inclined-V collision, ring-connection and scaled-shell-band probes passed, and the final mesh census produced the counts above. The 40 m and 80 m software renders using the committed 32 px concrete texture were inspected. Gradle tests were not run because the owner's dev client is active; no in-game check was performed and owner visual approval is not claimed.

Only natural-tower shell counts, stamps and inlet elevations changed in the layout golden. Existing natural outlet `y = 13` and induced outlet `y = 7` entries remain despite generated `y = 0`; that older unrelated mismatch is intentionally not refreshed without owner approval. Induced rows are unchanged.

No compatibility fields, migration, merge or version bump were added. The owner's remaining check is visual scale and collision in game at 40 m and 80 m diameters. Scratch census and renders stay under the owning worktree's ignored `tmp/tower-dev/`; no tool-folder change was needed.

The owner selected the native 32 × 32 pixel-art concrete texture after comparing 16 × 16, 32 × 32 and 64 × 64 mockups. It replaces the earlier coarse speckling with restrained warm-grey clusters. Only the texture asset changed; the approved swatch and tower comparisons were inspected, with no build, test or client check for that asset-only update.
