# Water cooling tower merge review

Owner, 2026-11-02: "remove the backward compat and prepare for merge". Branch `claude/codex-water-cooling-tower-review-09c170`, code as of `d5be9da6d`, which holds main `a3720726c` (0.18.1). Version 0.19.0: a new feature and a model batch. Merged as `339872bc1` (owner: "Merge as 0.19.0"), not pushed.

## Backward compatibility

The tower is unreleased, so nothing needed to keep reading an earlier tower.

Removed: eleven old-signature constructors, which only kept call sites written before the towers compiling; two `legacy` hash branches in `FluidDeviceSpec` and `PassiveNetwork.Inventory`, on which no saved or ordered value depends; and a shell's tolerance of a missing `TowerBank` tag, which only an unreleased save lacks: it now repairs to one cell with a logged repair.

Removing the overloads found one defect: `EquipmentShape.withConnections` copied a tower through the old signature and reset its bank to one cell. Copies carry the bank; gate `aCopiedShapeKeepsItsTowerBank`. `FluidFactsCompatTest`'s widening helper dropped the tower the same way.

Kept, by the saves rule: a world or a constructor draft with no tower reads its default, and the corpus images of every earlier format stay. Without these, every existing world would refuse to load.

## Formats

Main's 0.18.0 took facts schema 6 and shape fields 4 for column operation, so the towers moved up and main's images stay as written.

| Format | Main | Merged |
|---|---|---|
| Facts schema | 6 | 7, image `compat/fluid/schema-7/` |
| Shape fields | 4 | 5, image `compat/equipment/shapes-5.snbt` |
| State format | 29 | 30 |
| Protocols | `fluid-16`, `equipment-14` | `fluid-17`, `equipment-15` |

## Final lane

One lane on the final tree. An earlier lane on main 0.18.0 was replaced: main's 0.18.1 changed `FluidTpEquilibrium`, which the towers call.

| Check | Result |
|---|---|
| `test slowGates` | 2114 tests, 0 failures |
| chain-100 exact | deviation 0 |
| Fluid GameTests | 39 of 39 |
| Printed lines | 8556; the 33 junction lines hold main's values; `MIXED_GAS_COST` 299 Newton solves, unchanged |
| Campaign cold, bundled random, bundled defaults | 975, 994, 1000 clean; `refused=0` on every seed |
| Ignored files tracked | 0 |

The campaign holds no tower, so it measures what the merge does to other plants. Against main's 0.18.0 lane, on code identical to `c9e5ed099`: bundled defaults match seed by seed, 991 of 1000 cold seeds and 996 of 1000 bundled random seeds match, and no world is held here and clean there. The seeds that differ are heavy worlds already near the wall budget: cold 118, 183, 244, 431, 475, 555, 673, 768, 994; bundled 157, 431, 698, 707. They are not replayed: another thread has these holds (owner, 2026-11-02). Main's 0.18.1 merge kept no per-seed reports, so its thermo change has no seed-by-seed baseline here.

The report `main-baseline` in the false root worktree predates column operation. Compared with it, 51 cold seeds appeared to move; those moves are column operation's own. Compare with a lane on the same code.

## Code guide and tools

Code guide: `cooling-towers.md`, `README.md`, `fluid-runtime.md`, `equipment.md`, `fluid-science.md`, `client-gui.md`, `verification.md`, `column-operation.md`. The branch-update paragraphs are now facts about merged code. Tool folder: `tools/cooling-towers/`, with `poly-counts/` moved from `tmp/`. Logs: `final-lane-logs-2026-11-02.tar.gz` in the main checkout's `tools/cooling-towers/logs/`.

## Open

The frame rate of a large tower is not measured. The stiffening ring has not been seen in the client. Placing an 80 m tower sends about 41,000 data packets in one tick, and basin water stays hidden while the centre chunk is out of view. `verify.ps1` has never run: it stops if any other test worker runs.
