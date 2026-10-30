# Natural tower support and mesh review

## Expected behaviour

The preferred reference has an open perimeter of concrete V-struts, a continuous ring and internal supports below the packing. Its open support band is about one tenth of the visible tower height. The final clear air inlet is `0.2 × diameter` above the 2 m basin rim, or 8–16 m for 40–80 m towers. The shell base is therefore 10–18 m above grade and its throat stays at 75% of total height.

## What the code did instead

The original natural tower had a fixed 8 m support zone from `y = 2` to `y = 10`, crossed steel perimeter ties and no internal grillage. Its packing and inlet stayed at fixed elevations for every diameter. Commit `0491f75d` introduced the requested structure but incorrectly raised the support zone to 15% of total height; the owner rejected that proportion against the latest photo.

The original chimney used 4 m vertical bands and roughly 2 m circumferential chords; an 80 m model contained 23,016 triangles, including 19,152 chimney triangles.

## Correction for owner validation

Commits `0491f75d` and `0fd07699` derive the shell base, packing, distributor and hot-water inlet from the corrected support height. Commit `d97ca7db` continues the same inclined V-struts through the basin rim to the floor at `y = 0`, without vertical lower extensions. Thirty-two concrete V-strut stations join a concrete ring. Six steel grillage beams on nine vertical internal posts carry the packing; strut and beam sections scale with diameter. Buried bar ends omit faces without removing oriented collision.

The chimney uses 8 m bands and roughly 4 m chords. Only chimney facets above the derived shell base enter the static VBO; supports and ordinary equipment retain chunk rendering. The final 80 m model contains 7,256 triangles, 68.5% fewer than before, with 4,536 in the static chimney. This is a geometry-count reduction, not a measured FPS result.

Thirty-five focused checks passed for `0491f75d` before its support-height adjustment. For `0fd07699` and `d97ca7db`, standalone compilation and layout/mesh reports completed and offline software views were inspected. Standalone collision checks confirm inclined support contact at the basin floor for 40 m and 80 m towers. Gradle tests were not run because the owner's dev client is active; no in-game check was performed.

Only natural-tower shell counts, stamps and inlet elevations changed in the layout golden. Existing natural outlet `y = 13` and induced outlet `y = 7` entries remain despite generated `y = 0`; that older unrelated mismatch is intentionally not refreshed without owner approval. Induced rows are unchanged.

No compatibility fields, migration, merge or version bump were added. The owner's remaining check is visual scale and collision in game at 40 m and 80 m diameters. Scratch census and renders stay under the owning worktree's ignored `tmp/tower-dev/`; no tool-folder change was needed.

The owner selected the native 32 × 32 pixel-art concrete texture after comparing 16 × 16, 32 × 32 and 64 × 64 mockups. It replaces the earlier coarse speckling with restrained warm-grey clusters. Only the texture asset changed; the approved swatch and tower comparisons were inspected, with no build, test or client check for this asset-only update. Code guide: no change.
