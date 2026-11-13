# D-W1 The equipment structure in files, and its exact copies (E7's split, E8): report

Batch D-W1 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-ae14d8b091c96da4c`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `743512ec1`. **Nothing was compiled or run.** Structure only: what is placed, registered, refreshed and removed, and in which order, is unchanged.

| Step | Commit | What |
|---|---|---|
| 1 | `99be07193` | `EquipmentStructure` → `StructureRegistration`, `StructureRefresh`, `StructureRealignment` |
| 2a | `6b47b76a6` | One `fittedRelief` (converter and reregister) |
| 2b | `ba31c6ba0` | One `EquipmentStructure.heldAt(world, position)` (seven copies) |
| 2c | `affe17778` | `restoreFluidBinding`'s chunk loop is `StructureRefresh.loaded` |
| 2d | `d49c928c6` | One `Positions.of(level, at)` (thirteen copies in nine files) |
| 3 | this commit | Code guide (`equipment.md`), this report |

## 1. The split (step 1)

| File | Lines | Holds |
|---|---|---|
| `EquipmentStructure` | 1,724 → 574 | `Vessel`, the shape cache and `collision`, `place` ×2, `rename`, `heatSides`, `movers`, `heldAtNodes`, `unitRecords`, `sideIdentities`, `nodes`, `record`, `reconnect`, `refreshNozzles(At)`, `find`, `at`, `name` ×2, `FIND_STEPS`, `interior`, `shellReached`, `breaking`, `dismantle` with `dismantling`, `ours`, `contains`, the commands. Forwarders: `restoreFluidBinding`, `restateLater`, `refreshLater`, `removeRetiredLater`, `realignLater`, and `onLevelTick` (keeps `@SubscribeEvent`; the class keeps `@EventBusSubscriber`) |
| `StructureRegistration` | 660 | `register`, `restoreFluidBinding`, `registerUnit`, `rolePorts`, `sidePorts`, `registrations`, `burners`, `restateUnit`, `open`, `node`, `ports`, `unregister`, `registerConverter`, `converterDevices`, `registerStill`, `stillPorts`, `stillPort`, `restoreStill` ×2, `dropStillFuel`, `reregister`, `turbine`, `restoredControl` (+ `fittedRelief`, step 2a) |
| `StructureRefresh` | 459 | `STALE`/`Stale`, `RETIRED`/`Retired`, `RESTATE`; `restatePaint`, `restateLater`, `restate`, `restateCells`, `refreshLater`, `removeRetiredLater`, `removeRetired`, `retiredShell`, `retiredReached`, `onLevelTick`'s body, `ready`, `refresh`, `Survey`, `survey`, `write`, `settle`, `waitForLight`, `corner`, `loaded`, `min`, `max` |
| `StructureRealignment` | 174 | `REALIGN`, `realignLater`, `serveRealignment`, `Realignment`, `realign`, `stands`, `holdShaft`, `members` |
| `Positions` (step 2d) | 16 | `of(level, at)` |

- **The rule for a helper two parts use.** It goes to the part whose function the brief names (the waits for chunks and light, the walk and the rebuild: `StructureRefresh`; so `loaded` sits beside `ready`). A helper with no named part stays in `EquipmentStructure` (`nodes`, `heldAtNodes`, `unitRecords`, `ours`, `record`, `reconnect`, `interior`, `shellReached`, `setCell`, `FIND_STEPS`, `dismantling`).
- **The class held no instance state** (static helpers only). Static state moved with its owner, with the same type and initial value: `STALE`, `RETIRED`, `RESTATE` to `StructureRefresh`, `REALIGN` to `StructureRealignment`. `dismantling` and `SHAPES` stay.
- **Callers outside the class keep their calls.** The one edit is `RegistryRealignment.serve`: it calls `StructureRealignment.serveRealignment` (package-private before and after, same package), with its javadoc link. `ChunkBinding` (runtime), the block entities, the GameTests and the tools call the forwarders, unchanged.
- **Widened from private to package-private:** `EquipmentStructure.dismantling`, `setCell`, `unitRecords`, `heldAtNodes`, `nodes`, `position` (removed in 2d), `record`, `reconnect`, `FIND_STEPS`, `interior`, `shellReached`, `ours`; `StructureRegistration.register`, `unregister`, `reregister`; `StructureRefresh.STALE`, `Stale`, `Survey`, `ready`, `survey`, `write`, `settle`, `waitForLight`, `corner`, `loaded`, `max`. **Public removed** from the moved bodies of the six forwarded methods. `@SubscribeEvent` stays on the forwarder only.
- **Qualifiers added:** 91 `Owner.` prefixes (`tmp/dw1/out/qualified.tsv`). No statement was reordered, split or merged in step 1. Six javadoc links name the new owner (`#serveRealignment`, `#place`, `#interior` ×2, `#reregister`, `#restoreFluidBinding`).
- **Not moved:** the commands (the review's `EquipmentCommands` is not in the brief). The `Registration` family on the designs (E7's first half) is a redesign and is not in this batch.

## 2. E8, exact copies (step 2)

**2a, merged: the fitted safety valve.** Three statements, the same in both, in the same order:

| `converterDevices` (inside `if (gas && former != null)`) | `reregister` |
|---|---|
| `var fitted = registration.device().ports()…RELIEF…findFirst().orElse(null);` | `var fittedFace = device.ports()…RELIEF…findFirst().orElse(null);` (`device = record.device()`) |
| `var psv = former.relief() > 0 ? ReliefValve.of(level, origin, shape, fitted) : null;` | `var relief = spec.relief() > 0 ? ReliefValve.of(level, origin, vessel.shape(), fittedFace) : null;` |
| `if (former.relief() > 0 && psv == null) psv = ReliefValve.of(level, origin, shape);` | `if (spec.relief() > 0 && relief == null) relief = ReliefValve.of(level, origin, vessel.shape());` |

`former` is `registration.spec()`, `spec` is `record.spec()`, `shape` is `vessel.shape()`: record reads. Both now call `fittedRelief(level, vessel, registration)`. **Left:** the blanketing-valve half. The converter's `if (valve != null)` block also books `blanket = former.blanket()`, and its psv block books `relief` and `reliefFace`; the reregister's books nothing and reads `valve == null ? 0 : spec.blanket()` later. The converter's whole refit runs under `gas && former != null`.

**2b, merged: the registration at a node with ports.** Seven copies of `world.at(p).filter(r -> r.device().position().equals(p) && !r.device().ports().isEmpty())`, each followed by its own tail, which it keeps:

| Copy | Tail kept |
|---|---|
| `EquipmentStructure.heldAtNodes` | `.ifPresent(r -> ids.add(…))` |
| `unregister`, converter branch / default branch | `.ifPresent(record -> {… removeUnit …})` / `.ifPresent(record -> world.remove(…))` |
| `restoreStill(…, held)` | `.orElse(null)` |
| `reregister`, converter branch / default branch | `.orElse(null)` ×2 |
| `EquipmentShellBlockEntity.onLoad` | `.ifPresent(record -> { bind or deviceLoaded })` |

They now call the public `EquipmentStructure.heldAt(world, position)` (the block entity is in another package). **Left:** `restoreFluidBinding` reads `unit ? null : world.at(held).orElse(null)` unfiltered and tests position and ports inside a wider guard that starts with `level.hasChunkAt(node)`. `restoreStill`'s whole-still bind reads `world.at(p).ifPresent(…)` with no filter.

**2c, merged: one chunk loop.**

| `restoreFluidBinding` | `loaded(level, low, high)` |
|---|---|
| `for (x = origin.x>>4; x <= (origin.x+sizeX-1)>>4; x++)` | `for (x = low.x>>4; x <= high.x>>4; x++)` |
| `for (z = origin.z>>4; z <= (origin.z+sizeZ-1)>>4; z++) if (!level.hasChunk(x, z)) return false;` | `for (z = …) if (!level.hasChunk(x, z)) return false;` then `return true` |

Now `if (!StructureRefresh.loaded(level, origin, StructureRefresh.corner(origin, shape))) return false;`. `corner` is `origin.offset(size - 1)`: the same x and z, and `loaded` reads no y. **Left:** `ready` also requires `shouldTickBlocksAt`. `waitForLight` acts on every chunk; it returns nothing.

**2d, merged: a block's fluid position.** `new PhysicalFluidTopology.Position(L.dimension().location().toString(), P.getX(), P.getY(), P.getZ())`, the same four arguments in the same order in each copy:

| File | Copies |
|---|---|
| `EquipmentStructure`, `FluidDeviceBlock`, `ShaftParts` | Private helper `position(level, at)`. Removed; its 3 + 2 + 7 calls (and the 32 in the parts) name `Positions.of` |
| `StructureRealignment.holdShaft` | Inline: `(level, o)` |
| `FluidDeviceBlock.onPlace`, `onRemove` | Inline: `(level, position)` ×2 |
| `EquipmentShellBlockEntity.fluidPosition`, `forgeTank` | Inline: `(level, worldPosition)`, `(server, p)` |
| `FluidDeviceBlockEntity` (`bindIdentity`, `onLoad`) | Inline: `(level, worldPosition)` ×2 |
| `TerminalEnergy.unit`, `PipelineFittingItem`, `PipelineToolItem` | Inline: `(level, origin)`, `(level, pos)` ×2 |

**Left:** `PipeRackBlockEntity.position(option)`, `PipeAnchor` and `FluidLineBackend` ×2 take a fifth argument, the lane.

**2e, left: the two walks.** `shellReached` starts at step 0, so it tests the position itself, and breaks only for `step > 0`. It returns the shell. `retiredReached` starts at step 1 and returns a boolean. Its predicate (`retiredShell`) reads `retiredOrigin` and `retired`, not `shape`. The start step and the result differ.

## 3. Checks run (node, `tmp/dw1/`, gitignored)

- `split.mjs` built step 1 from line segments. It asserts that every segment begins after a blank line and that the segments cover lines 64 to 1723.
- `verify.mjs` checks the four files:
  - CRLF only.
  - Braces, parentheses and brackets balance, outside strings and comments.
  - Every removed line reappears after qualifiers, modifiers and links are normalised. After step 1, one line is unmatched: `import java.util.TreeMap;`, which was unused. After step 2, the lines unmatched are the eight that 2a replaced.
  - No unqualified call or field names another file's member. No `Owner.x` names a missing or private member.
- `callers.mjs`: every `EquipmentStructure.x` and `EquipmentStructure#x` in `src/**` and `tools/**` finds the same signature after step 1. The one flag is `record`, named in `Silhouettes`' javadoc only. Its access widened from private.
- `poscheck.mjs`: every file that calls `Positions.of` is in its package or imports it. No call of a removed helper is left. The only 4-argument inline copy left in `world/` is `Positions` itself.
- Every edited file has CRLF only, and `git diff --stat` matched the plan at each commit.
- `node tools/code-guide/map.mjs --readme`: the counts are unchanged. `--check` passes.

## 4. Tests for the main thread

Files changed: in `world/level/block`, `EquipmentStructure`, `RegistryRealignment`, `FluidDeviceBlock` and `ShaftParts`, and the new `StructureRegistration`, `StructureRefresh`, `StructureRealignment` and `Positions`. In `world/level/block/entity`, `EquipmentShellBlockEntity`, `FluidDeviceBlockEntity` and `TerminalEnergy`. In `world/item`, `PipelineFittingItem` and `PipelineToolItem`. No test changed.

| Task | Classes | What it proves |
|---|---|---|
| `compileJava compileTestJava compileFluidGameTestJava` | all | The split, the forwarders, the widened access, `heldAt`, `fittedRelief`, `Positions` and the imports compile |
| `test --tests` | `LayeringTest` | The new files name no `client` type |
| `test --tests` | `world.level.block.entity.PipeLineCompatTest`, `runtime.instrument.GaugeAccessoriesTest`, `runtime.fluid.PhysicalFluidTopologyTest` | The unit tests that name an edited class (`FluidDeviceBlockEntity`, `PipelineToolItem.Settings`, `FluidDeviceBlock.InlineMover`) still load it |
| `test --tests` | `world.level.block.LineContractTest`, `LineInteractionTest`, `FloatingRoofsTest` | The brief names the first. Same package; my grep finds no direct name of an edited class. Run them if `affected.mjs` selects them |
| `equipmentGates --tests` | `science.equipment.EquipmentShapeCompatTest` | `EquipmentShellBlockEntity` (in the gate's class list) still reads and writes shapes |
| `equipmentGates --tests` | `EquipmentServicesTest`, `VesselPlacementTest` | The brief names them. My grep finds no load of an edited class. Run them only if `affected.mjs` selects them |
| final lane | GameTests below | Placement, registration, refresh, realignment and removal order |

GameTests by part (the final lane): **refresh, restate, retired removal**: `EquipmentCompatGameTests`. **Realignment, held registrations, rename**: `RegistryFirstGameTests`. **`restoreFluidBinding`, `realignLater` from `ChunkBinding`**: `ChunkLoadGameTests`, `FluidCheckpointGameTests`. **Placement, registration, `sideIdentities`, dismantle**: `AssemblyGameTests`, `CoolingTowerGameTests`, `ElectricGameTests` (also `TerminalEnergy`), `EquipmentProtectionGameTests`, `ColumnOperationGameTests`, `PumpHealthGameTests`, `SolidPhaseGameTests`. **Converter and valves**: `ConverterGameTests`, `LevelLoopGameTests`, `ReliefFlareGameTests`. **`Positions` in the pipe and shaft paths**: `PipeLayingGameTests`, `ConduitLayingGameTests`, `DrawnJointGameTests`, `ShaftTrainGameTests`, `CreateCouplingGameTests`. **No GameTest refits a fitted valve:** `converterDevices` with a former registration, and `reregister` of a drum with a PSV. Step 2a rests on reading the code and on the compile.

## 5. Points not verified (compile and load risks), by file

1. `StructureRefresh.java` 48-50: `STALE` (package-private) has type `Map<ServerLevel, Map<BlockPos, Stale>>`. `StructureRealignment.java` 57 reads it into `var`. `Stale` was widened to package-private so that the type is accessible.
2. `StructureRealignment.java` 118-119: it reads `survey.far()` and `survey.world()` of `StructureRefresh.Survey`, now package-private.
3. Single-type imports of the nested `EquipmentStructure.Vessel` in its own package: `StructureRegistration` 17, `StructureRefresh` 12, `StructureRealignment` 11. This is legal.
4. `EquipmentStructure.java`: `static void record(…)` keeps its name. It is a contextual keyword, and the method was declared with that name before. `StructureRefresh` calls it as `EquipmentStructure.record(…)`.
5. `StructureRegistration.fittedRelief`: the return type `InstrumentHosts.@Nullable ReliefValve` is a type-use annotation on a nested type, as the file's `PhysicalFluidTopology.@Nullable Port`.
6. `EquipmentStructure.onLevelTick` → `StructureRefresh.onLevelTick(event)`. `LevelTickEvent` fires on both sides, so `StructureRefresh` now loads on the client at its first level tick. The body's first statement returns on a client level, as before. Its static initialiser makes three empty `WeakHashMap`s, which `EquipmentStructure`'s made on the client before. It names no client class.
7. `build.gradle` 579-580: `equipmentGates` lists `world/level/block/entity/EquipmentShellBlockEntity*`. That class now names `Positions` (`fluidPosition`, `forgeTank`) and `EquipmentStructure.heldAt` (`onLoad`). `EquipmentStructure` was already named there and not listed, and the gate's tests call only the NBT statics. If the gate fails with a missing class, add `'world/level/block/Positions*'` to that list. I did not edit `build.gradle`.
8. Unused imports are left: `PhysicalFluidTopology` in `ShaftParts`, `TerminalEnergy` and `PipelineToolItem`, and `java.util.Set` in `EquipmentStructure` (a javadoc word matched its name). These give warnings only.
9. `tools/heat-exchanger-design/review-probe` and `tools/skirt-shadow/gametest-probe` call only `place` and `sideIdentities`, which are unchanged. No build compiles them.
10. The code guide's other chapters (`fluid-runtime.md`, `machine-assemblies.md`, `heat-exchangers.md`) still name `EquipmentStructure` for `place`, `registerUnit`, `refreshLater` and the like. The public entry points are still there; `registerUnit` is now `StructureRegistration`'s. Those chapters are for the merge's guide update.
