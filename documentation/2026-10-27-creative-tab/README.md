# Creative tab contents

Purpose: keep IChemE items in the mod's own creative tab instead of adding them to vanilla Functional Blocks.

Status: Implemented 2026-10-27 (`188fba66`).

Documents:
- This README records the item-tab change and its verification.

Code areas:
- `src/main/java/com/wormzjl/icheme/IChemE.java`: the mod tab lists every registered item; no vanilla-tab injection listener remains.

History:
- Removed the `FUNCTIONAL_BLOCKS` contents listener, added the sensor, pipe-laying and rack items to the mod tab, and ran the focused V3 test (`BUILD SUCCESSFUL`).

Notes:
- Existing worlds and item registrations are unchanged; only creative-tab placement changes.
