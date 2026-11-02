# Large tower frame rate and chunk loading review

Owner, 2026-11-02, after a client check of an 80 m natural tower: "extremely laggy FPS wise", and "check chunk loading issue for this kind of very large structures".

## Expected
A natural tower is a hollow shell over a basin. What a player can see is the shell, its supports, the basin floor, the underside of the fill from the air inlet and the top of the fill down the throat. It should cost about what those surfaces cost, whatever its volume, and it should stand as long as any part of it is in view. It touches 112 sections, so no single chunk can stand for it.

## What the code did
Measured on the 80 m tower (`tmp`-only probe, every cell through `chunkSurface`):

| | Before | After |
|---|---|---|
| Chunk polygons | 55,765 | 499 (nozzles) |
| Cached whole mesh | 1,512 | 2,587 |
| Total polygons | 57,277 | 3,086 |
| Most polygons in one section | 2,501 | 299 |
| Shell cells (block entities) | 48,539 | 41,371 |
| Meshing every cell | 123 ms | 49 ms |

- Only the chimney used the cached mesh. Floor, fill, distributor, eliminators, struts and rings went through the chunks, cut at every block. A disc 70 m across is about 3,800 cells on each face.
- Five disc faces could never be seen: the floor's underside, the fill's top, both faces of the distributor and the eliminators' underside. Removing them and the layers between them took 7,382 cells.
- The shell renderer was bound to the fluid node, at the tower's centre. With that chunk out of view the whole shell vanished, though cells round the rim stayed loaded. A player at the rim on a short render distance stood beside blocks with no tower drawn. A fan bank 112 m long had the same fault.
- The fill disc was wider than the shell above it and showed outside as a pale ring.

Ruled out, by reading the path: no block entity renderer and no ticker on a shell cell; ambient occlusion is off, so meshing never reaches `getCollisionShape`; `sprite()` is stable, so the mesh does not rebuild; `InstrumentHosts` lists node cells only; `MoverSounds` skips non-movers. Each frame's mod code is a handful of map reads. `EquipmentGhost` costs a whole-tower check twice a second only while a builder is focused.

## What changed
- `towerShell` takes every still part of either kind of tower. The special case for natural towers is gone.
- The fill is two decks, its underside and its top, each reaching the shell's inner wall, and the floor is its top face. The packing between them is not drawn. `TOWER_FILL` and `TOWER_FLOOR` are sprites made for a whole disc: stretched, the small ones showed an eliminator header 6.7 m wide and blocks of concrete noise.
- `LoadedTowers` counts each tower's loaded cells. The shell stands while any cell is loaded. Gate: `LoadedTowersTest`.
- Gates hold the tower to 3,000 whole polygons, 520 in the chunks and 42,000 cells.
- The whole tower takes one light level, as the fan cell does. A stiffening ring closes the shell's top (owner: "needs some thickness"): the wall stays 0.25 m, as thin as a real shell, and the ring is d/100 proud of it on both sides.

## Chunk loading: checked, sound
- Placing refuses unless every cell is loaded, with the cell named.
- Dismantling reads every cell with `getBlockState`, which loads a missing chunk, so a tower cannot be left half removed. It may stall a tick; it does not lose cells.
- `refresh` and `restate` wait for every chunk to be loaded and block-ticking, one vessel a tick.
- A chunk's block entity data stays far under the 2 MiB packet frame: about 2,000 cells of 200 bytes at the worst chunk (estimated, not measured).
- Fluid state does not depend on chunks, as the 2026-10-26 review found.

## Open, for the owner
1. **Frame rate is not measured.** No client ran: the counts fell by 20 times, but only the owner's check can say the lag has gone. If it stays, the next suspect is the 41,371 block entities.
2. **Cells.** Most of the rest are the shell's own surface, which collides. Dropping collision above reach would remove many of them (not measured), but a flying player would pass through the upper shell. Not done.
3. **Placing sends one data packet a cell**, about 41,000 in one tick: a spike at placement, not a steady cost. Not changed.
4. **Basin water and the fans' display** still come from the node, so water stays hidden while the centre chunk is out of view. The shell does not.
5. **Low simulation distance:** `refresh` waits for the tower's whole box to tick, so at a very low setting a stale tower may wait. It keeps its old layout; nothing is lost.

Code guide: updated in `cooling-towers.md`. Tool folders: `tools/cooling-towers/` (sprites).
