# Basin water-surface correction

Status: implemented on `kimi/water-cooler-fixes` in `f22a4f3c`, 2026-10-31. The owner will perform the in-game check.

## Expected behaviour

The water is a horizontal plane at the scheduled free-water height. At normal operation it uses the model's 4/3 m level above the equipment base, inside the 2 m wall. Its perimeter meets the actual inner wall at every diameter and rotation; structural members remain visible through and above the translucent water.

## What the code did instead

The natural basin wall uses 48 facets at 40 m diameter and 63 at 80 m, with its angular zero along local +z. The water used a fixed 64 facets, radius `diameter / 2 - 0.22`, and angular zero along local +x. It was therefore both inset and rotated relative to the wall, leaving gaps and crossed edges. The induced basin's two-triangle rectangular outline already matched its walls. The shared renderer also retained a 2.99 m upper clamp from the former 3 m basin.

## Proposal for owner validation

Implemented in `f22a4f3c`: share `naturalFacets` and `naturalFrame` between the model and water, use the inner-wall radius, and retain upward-facing winding. `CoolingTowerBasin.height` preserves the published level and the 0.205 m minimum height (5 mm above the floor), but caps the drawn surface at `CoolingTower.BASIN_HEIGHT`. The induced outline and all process physics are unchanged.

## Verification

- Standalone JDK compilation of the changed science/model classes passed.
- The standalone probe passed 1,824 checks: exact wall/water vertices at 40 m and 80 m in all four rotations, upward-facing triangles, a 6 × 4 induced bank in all rotations, and the floor/normal/rim heights. Maximum perimeter error was 0.0 m.
- Software top and close-up renders of a 40 m natural basin and a two-cell induced bank were inspected. Water meets the wall without the old gap; natural supports remain visible through it.
- `CoolingTowerGeometryTest` now pins the model-derived natural perimeter, induced bank bounds, rotations, winding and height limits.
- No Gradle suite or in-game test was run while the owner's dev client was active. No saved fields, equipment layouts, material data, compatibility code, version or merge changed.

Scratch probe/render sources and images are under the owning worktree's ignored `tmp/tower-dev/`; none is staged. The code-guide snapshot names the code commit above.
