# Induced-draft tower geometry follow-up

Code: `98789eaa` on `kimi/water-cooler-fixes`; 2026-10-30. Unmerged, no version bump.

## Expected behaviour

- Every induced-draft support leg reaches the basin floor and remains visible and solid below the basin rim.
- The whole square fan deck collides, including its corners, without filling the circular fan opening or projecting beyond the cell.

## What the code did instead

- Leg boxes began at `y = 3`, the basin rim, leaving their lower 3 m absent.
- Only the circular fan cowl had collision. The rendered square deck outside that cowl let players pass through.

## Correction for owner validation

- The same leg boxes now begin at `y = 0` and retain their existing upper ends and sections.
- A 0.125 m (`Access.DECK`) annular solid follows the deck, clipped to the square cell. Its inner radius remains `0.35 × cell width`; the separate cowl and fan are unchanged.
- No nozzle or saved shape field changed. The layout table records only the new induced-tower shell counts and stamps; earlier nozzle entries were not approved or rewritten.

## Verification

- The new geometry regression failed before the correction. `CoolingTowerGeometryTest`: 5/5 passed afterward, covering the leg mesh/collision, deck sides/corners, outer clipping and open fan centre.
- `slowGates`: passed independently in 3 min 21 s.
- Quick suite: 1,834 tests ran with one failure, the known `EquipmentLayoutGoldenTest.everyLayoutIsTheRecordedOne`. Remaining differences are earlier tower nozzle/structure changes; this repair's induced shell counts and stamps match.
- Existing software renderer: inspected induced-tower `iso` and `top` PNGs under `tmp/tower-geometry/`; legs continue into the basin and the deck remains intact. No client was launched; the owner performs the in-game check.
- `git diff --check`: passed before the code commit. New tool folders: none; the existing cooling-tower and storage-render tools are unchanged.
