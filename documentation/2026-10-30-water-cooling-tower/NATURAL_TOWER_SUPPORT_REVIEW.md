# Natural tower support and mesh review

## Expected behaviour

The preferred reference has an open perimeter of concrete V-struts, a continuous ring and internal supports below the packing. The owner selected diameter-scaled proportions: a clear air inlet of `0.3 × diameter` above the 2 m basin rim, or 12–24 m for 40–80 m towers. The shell base is therefore 14–26 m above grade and its throat stays at 75% of total height.

## What the code did instead

The natural tower had a fixed 8 m support zone from `y = 2` to `y = 10`, crossed steel perimeter ties and no internal grillage. Its packing and inlet stayed at fixed elevations for every diameter. The chimney used 4 m vertical bands and roughly 2 m circumferential chords; an 80 m model contained 23,016 triangles, including 19,152 chimney triangles.

## Correction for owner validation

Commit `0491f75d` derives the shell base, packing, distributor and hot-water inlet from the scaled support height. Thirty-two concrete V-strut stations join a concrete ring. Six steel grillage beams on nine posts carry the packing; strut and beam sections scale with diameter. Buried bar ends omit faces without removing oriented collision.

The chimney uses 8 m bands and roughly 4 m chords. Only chimney facets above the derived shell base enter the static VBO; supports and ordinary equipment retain chunk rendering. The 80 m model now contains 7,004 triangles, 69.6% fewer than before, with 4,284 in the static chimney. This is a geometry-count reduction, not a measured FPS result.

Thirty-five focused checks pass: 11 geometry, 20 physics and 4 runtime. They cover scaled elevations, V-strut/ring/grillage collision, hollow chimney bands, mesh budgets, ordinary-equipment rendering and coupled natural-feed operation. Two offline software views of the 80 m tower were inspected; no game client was launched.

Only natural-tower shell counts, stamps and inlet elevations changed in the layout golden. Existing natural outlet `y = 13` and induced outlet `y = 7` entries remain despite generated `y = 0`; that older unrelated mismatch is intentionally not refreshed without owner approval. Induced rows are unchanged.

No compatibility fields, migration, merge or version bump were added. The owner's remaining check is visual scale and collision in game at 40 m and 80 m diameters. Scratch census and renders stay under the owning worktree's ignored `tmp/tower-dev/`; no tool-folder change was needed.
