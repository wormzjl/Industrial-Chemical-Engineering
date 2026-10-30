# Cooling-tower geometry follow-up

Code: `98789eaa`, `44044ff2`, `aa8381cb` on `kimi/water-cooler-fixes`; 2026-10-30. Unmerged, no version bump.

## Expected behaviour

- Every induced support leg reaches the basin floor; the square deck and guarded fan collide over their rendered extent.
- Both basins retain their former plan area at two-thirds height: 2 m walls, 4/3 m operating depth and 2/3 m freeboard.
- Induced fill defaults to half the cell width, with one packing layer enclosed by casing through the fan deck.
- The natural shell stands on a perimeter ring of X-braced bays, with collision following the diagonal ties.

## What the code did instead

- Induced legs began at the old 3 m rim; the square deck initially lacked collision, then its fan centre remained pass-through.
- Basin walls were 3 m high with 2 m operating depth. Induced fill always defaulted to 2 m, sat outside the casing and had a second `AC_FINS` layer above the distributor.
- Thirty-two plain vertical concrete posts supported the natural shell.

## Correction for owner validation

- Legs now start at `y = 0`; a clipped annular deck solid and a full cowl cylinder cover the deck, rotor and grille.
- `CoolingTower.BASIN_HEIGHT` is 2 m and `BASIN_DEPTH` is 4/3 m. Shape geometry, level head, device specifications and inventories use the same depth.
- Default induced fill is 2/3/4 m at 4/6/8 m cell width; custom 2–6 m settings remain valid.
- One fill box and its distributor remain. Four casing walls extend from `y = 4` to the fan deck; the upper packing/eliminator box is removed.
- Thirty-two natural perimeter bays use crossed 0.15 m steel bars from `y = 2` to `y = 10`. A shared oriented-bar helper supplies matching collision without filling open air.
- No saved fields or compatibility code changed, per owner instruction. The current schema-6 fixture was regenerated; its recorded totals are unchanged.
- Six authorized layout rows changed. Default inlet elevations follow fill depth; older outlet-pipe golden entries were preserved rather than approved or refreshed.

## Verification

- Earlier geometry checks and slow gates passed at `98789eaa`; the fan assertions at `44044ff2` are included in the new runs below.
- At `aa8381cb`, 44 focused geometry, physics, runtime and fluid-facts checks passed. The current-schema corpus passes after regeneration.
- The quick suite ran 1,838 tests with only the pre-existing tower outlet-golden mismatch. Independent `slowGates` passed.
- Software `iso`, `front`, `right` and `low` renders of both revised towers were inspected. No client was launched; the owner performs in-game checks. New tracked tools: none.
