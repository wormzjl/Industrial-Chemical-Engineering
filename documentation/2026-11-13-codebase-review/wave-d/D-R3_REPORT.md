# D-R3 One display for the authority's ten (R9): report

Batch `2026-11-13-codebase-review`, wave D, package D-R3 (brief: [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), "D-R3"; review: [RUNTIME_REVIEW.md](../RUNTIME_REVIEW.md), row R9). Written by the D-R3 subagent on 2026-11-13. Structure only. Nothing was compiled or run: the main thread compiles and runs.

Summary:
- Commit `237a76e57`: a new file `runtime/fluid/Display.java` (168 lines), and `FluidWorldAuthority.java` 2,959 → 2,827 lines (−132; 97 added, 229 removed).
- Eight displays now use one `Display<S>`. Three are **bucket displays** (open tanks, sight glasses, movers): `present` publishes them. Five are **cadence displays** (flares, stills, towers, heaters, chimneys): each publishes at its device's slot.
- Two displays keep their own code: the rotations and the drivers. They are not the shape that `Watchers` has (section 2).
- Net lines: +36. The review expected about −300. That estimate counted all ten displays and a bare generic. Here two displays stay, and about half of `Display.java` is documentation.
- No packet type, payload, wire name, range, bound or comparison changed. Nothing new runs on each tick: a cadence display with no watchers now returns after its `removeIf`, with no copy of the watched set.
- What a client receives, and when, is unchanged. The send order within one call is unchanged (section 3). The differences that cannot be seen are listed in section 4.
- The tool `tools/custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java` (lines 1167-1168) reads `publishTowers`, `publishHeaters`, `towerWatchers` and `heaterWatchers` by reflection. It compiles, but it fails at run time (section 6, point 9). Fixing it is outside my lane.

## 1. The ten displays side by side (as read at c931cd856)

Legend:
- W: `FluidWorldAuthority.Watchers` (keyed by identity, at most `FluidNetwork.MAXIMUM_WATCHED` 64 per player; `watch` returns false for a repeat).
- R: `inRange` (`WATCH_RANGE`, 128 blocks). M: `moverRange` (`MOVER_RANGE`, 96 blocks).
- "Level" is the fan-out's level test. L: `player.serverLevel()!=level`, the level the device was presented in. D: the dimension name compared with the dimension of the device's record.

| # | Display | Watch request | Watchers | Target check (a valid watch) | State read | Cache and comparison | Sent, to whom | A new watcher gets | A watcher ends by | Where it publishes |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Open tanks | `watchTanks`, lists of BlockPos | W | chunk loaded; block entity `Receiver`; record by the block entity's identity; `spec.open`; cell = pos; dimension; R | `TankDisplay.of(view)`, then `Glide.next(cached, roof standing, display, game time)` | `publishedGlides`. No comparison: always sent. A null display removes the cache and the roof glide | `sendTank(player, pos, glide)`; level L, range R | the cached glide, if any (no replay read, no observe) | an unwatch by cell (or its record gone); the fan-out (L, R, offline); `forget(player)`; `deviceUnloaded`; a commit that removed it | `present`, before `acceptFluidView` |
| 2 | Sight glasses | `watchSightGlasses`, lists of Target | W | chunk loaded; R; block state `hasSightGlass` **before** the block entity; block entity identity; record PIPE; cell; dimension | `PipePresentation.sightGlass(view, …)` | `publishedSightGlasses`; always sent | `sendSightGlass(player, target, sample)`; L, R | `presentation.observe(id)`, then the cached sample | `unwatchSightGlass` (unwatch, then `refreshObserved`), also per device at a logout; `forgetSightGlass(id)` | `present`, after the view and the gauges; a pipe without a sight glass: `forgetSightGlass` |
| 3 | Movers | `watchMovers`, Target | W | chunk loaded; M; block entity identity; control is a `Mover`; cell; dimension | `MoverState` from the control, the shaft share, the electric supply and the pump's health | `publishedMovers`; always sent; `publishedMover(id)` is public | `sendMover`; L, M | `refreshObserved(id)`, then the cached state | `unwatchMover` (unwatch, then `refreshObserved`); `forgetMover(id)` | `present`, after the sight glass |
| 4 | Flares | `watchFlares`, Target | W | chunk loaded; M; block entity identity; `flare()`; cell; dimension | `flareState(target)` | `publishedFlares`: the due devices are removed, then each read state is put; always sent | `sendFlare`; D, M | the cached state, else `flareState(target)` (a read) | unwatch; the fan-out; the record gone or no longer a flare (removes the device); `forget(player)` | tick: `publishFlares`, at the device's slot mod 100 |
| 5 | Stills | `watchStills`, Target | W | as flares, with `stillPot()` | `stillState(target)` | `publishedStills`; as flares | `sendStill`; D, M | cached, else `stillState(target)` | as flares | tick, after the flares |
| 6 | Towers | `watchTowers`, Target | W | `watchable` (chunk loaded; R; block entity identity; record; cell; dimension); `spec.tower()!=null` | `towerPlume(target, islands, this::presentation)`: one islands memo per pass | `publishedTowers`; due devices removed, then **return if there are no watchers**, then put | `sendTower`; D, R | cached, else `towerPlume(target, new HashMap, this::storedIsland)` | as flares | tick, after the stills |
| 7 | Heaters | `watchHeaters`, Target | W | `watchable`; `flued` | `heaterFlue(…)`, memo per pass | `publishedHeaters`; as towers | `sendHeater`; D, R | cached, else `heaterFlue(target, new HashMap, this::storedIsland)` | as flares | tick, after the towers |
| 8 | Chimneys | `watchChimneys`, Target | W | `watchable`; `chimney()` | `chimneyState(target)` | `publishedChimneys`; as towers. **Not cleared at `close()`** | `sendChimney`; D, R | cached, else `chimneyState(target)` | as flares | tick, after the heaters |
| 9 | Rotations | `watchRotations`, BlockPos | its own `Map<UUID,Set<Position>>` | **first** `set.size() < RotationNetwork.MAXIMUM_WATCHED`; chunk loaded; M; a `ShaftBlock` or `shafts.unitAt`; each request drops the player's keys in other dimensions and the unwatched cells | `rotationSpeeds` over a unit map and a speed memo, both per pass; one state per key per pass | `publishedRotations`: cleared and filled again each pass | `RotationNetwork.send` for each pair of player and key, players first | the cached state at **every** watch, a repeat watch of the same key included | the pass: an offline player is removed; a key in another dimension, unloaded or out of M is removed without a send; `forgetWatcher`; `close` | tick, every `% 100`, only while someone watches |
| 10 | Drivers | `watchDrivers`, BlockPos | its own `Map<UUID,Set<BlockPos>>` (the key has no dimension) | first `set.size() < FluidNetwork.MAXIMUM_WATCHED`; chunk loaded; M; `unitAt` is a driver or a generator | `driverState(level, origin)`, a memo per Position per pass | `publishedDrivers`: cleared and filled again | `sendDriver` for each pair of player and origin, players first | the cached state at every watch | the pass: an offline player is removed; an origin unloaded, out of M or without a state is removed **and is sent a stopped `DriverState(origin, false, 0)`**; `forgetWatcher`; `close` | tick, every `% 100`, before the rotations |

Thread and ownership: the public entry points check `owned()` (`server.isSameThread()`, else they throw), and the generic keeps that check where it was:
- `watch*`, `forgetWatcher` and `deviceUnloaded` keep `owned()` and, for the watches, `if(closed)return`, in the authority.
- Every record read in a target check or a cadence pass still goes through `registrations()`, which calls `owned()`.
- `present` is reached from `FluidPresentation`, whose methods call their own `owned()`.
- The wire handlers run on `HandlerThread.MAIN` (`FluidNetwork.registerDisplays`, `RotationNetwork.register`). `FluidDeviceBlockEntity` (line 305) checks `isSameThread()` before it calls `deviceUnloaded`.
- No display uses a lock. `Display` itself has no guard; only the authority calls it.

## 2. What merged and what stayed

Merged into `Display<S>`: rows 1 to 8.
- **Bucket displays** (`Display.bucket`): tanks, sight glasses, movers. They share the cache, the replay and the fan-out (`publish`, with the level test L). They differ in:
  - the range: tanks and sight glasses R, movers M;
  - the on-watch hook: sight glass `observeWatched` (`presentation.observe`), mover `refreshObserved`, tank none;
  - the unwatch hook: sight glass and mover `refreshObserved`, tank none. With a hook, `forget(player)` unwatches device by device, as `forgetWatcher` did. Without one, it calls `Watchers.forget`, as before;
  - the sink: the tank sends by its node cell, with no Target.
- **Cadence displays** (`Display.cadence`): flares, stills, towers, heaters, chimneys. They share one pass (`publishDue`, with the level test D). They differ in:
  - the range: flares and stills M, the others R;
  - the kind predicate: one predicate per display, the same in the target check and in the pass;
  - the replay read and the pass read. Flares, stills and chimneys use one function for both. Towers and heaters replay from `storedIsland` with a fresh map, and their pass reads `presentation` through one islands memo per pass. The pass's `Supplier` builds that memo after the empty check, as the code built it before.

Kept with their own code inside the merged displays (each difference named in the code):
- **The tank's watch request**: lists of BlockPos. The unwatch walks the player's watched tanks and compares their cells. The identity comes from the block entity. It is still `watchTanks`; it uses `tankDisplay.watchers`, `unwatch` and `replay`.
- **The sight glass's target check** (`sightGlassWatchable`): the block state is read before the block entity, so the block entity is never asked for at a pipe without a sight glass. The other checks use `watchable(player, level, target, range)`.
- **The tank's publication**: the glide is computed from the cached glide and from where the roof stands, and a null display removes the cache. It is still `publishTank`.
- **`close()`** clears the sight glass, mover, flare, still, tower and heater caches. It still clears neither the tanks' glides nor the chimneys' states, and no watcher list. A comment records this; it can matter only after close, when `tick` already returns at once. The owner may want to align it later; this package does not change it.

Stayed outside `Display`, with their two ad-hoc maps: **rotations** and **drivers** (rows 9 and 10). They are not `Watchers`' behaviour:
1. They are keyed by position: a dimension Position for the rotations, a bare BlockPos for the drivers, whose dimension comes from the player's current level at each pass. `Watchers` is keyed by device identity.
2. The bound is checked against the set's size before the target check, and the rotations use their own bound (`RotationNetwork.MAXIMUM_WATCHED`).
3. A repeat watch replays again (`set.add` without a test). `Watchers.watch` returns false for a repeat, and nothing is replayed.
4. The pass goes players first, then keys. It keeps one state per key as a memo and replaces the whole cache each pass. `Watchers` displays go device by device and replace only what is due.
5. The drivers send a stopped state to a watcher they drop; the rotations drop without a send. A rotation request also drops the keys in other dimensions.
6. They run on the global `% 100` tick, not at the device's slot.

To put them in `Watchers` would change the send order (players first against devices first) and the replay of a repeat watch, so they stay.

## 3. Order of sends, before and after

Tick (`FluidWorldAuthority.tick`):
- Before: drivers (`% 100`) → rotations (`% 100`) → `publishFlares` → `publishStills` → `publishTowers` → `publishHeaters` → `publishChimneys` → silhouettes, noise (`% 100`).
- After: drivers → rotations → `for(display : cadenceDisplays) display.publishDue(now)`, with `cadenceDisplays = List.of(flare, still, tower, heater, chimney)` in that order → silhouettes, noise.
- `now` is read once (`topology.onlineTick()`) instead of once per publisher. The value is the same: nothing in the five passes advances the online tick.

Within one cadence display, unchanged:
1. `removeIf(displayDue)` on the cache;
2. then (towers, heaters and chimneys before; now all five) return when there are no watchers. For flares and stills the loop over an empty copy did nothing before;
3. then `watchers.devices()` (a `Set.copyOf`), skip the devices not due, read the record (`registrations().get`), remove a device whose record is gone or of another kind, read the state, `put`, then `watchers(id)` (a `Set.copyOf`) with the same test (offline, then dimension, then range) before each send.

`present(id)` (the presentation host, unchanged in its order):
- vessel: tank glide (open) → `acceptFluidView` → gauges → mover;
- pipe-like device: `acceptFluidView` → gauges → sight glass (or its `forget`) → mover.

Inside `publishTank`: before, `put`, then `roofs.glide`, then the sends. After, `roofs.glide`, then `put` and the sends (`tankDisplay.publish`). `FloatingRoofs.glide` does not read the authority's cache, so the swap cannot be seen.

Inside `publishSightGlass`: before, the Target was built after the `put`. After, it is built before the `put` (it is the `publish` argument). Same values; see section 6, point 8.

Watch requests: the order is unchanged. All unwatches come first. Then each target: the check, `Watchers.watch`, the hook, the cached state or the replay read, the send.

`forgetWatcher`: drivers, rotations, then tanks, sight glasses, movers, flares, stills, towers, heaters, chimneys (the `displays` list in declaration order, the order the code had), then silhouettes, noise.

`deviceUnloaded`: the order is unchanged: sight glass, mover, tank, then `presentation.deviceUnloaded`. The tank's cache now goes in `tankDisplay.forget`, before `presentation.deviceUnloaded` and `publishedMenuData.remove`. It used to go after them. Neither of those reads the glide cache.

`RegistryHost.committed`: for each removed device, tank, then sight glass, then mover `forget`, as before. The tanks' caches were removed in one `removeAll` before the loop; they now go in the loop. For the recompiled devices, the sight glass and mover caches go through `unpublish(recompiled)`.

## 4. What the generic may differ in, and why it cannot be seen

- **Lambdas read fields at call time.** No display lambda reads an instance field directly:
  - `server`, `presentation` and `registry` are blank finals assigned in the constructor, so they are reached through methods: `online(UUID)`, `observeWatched(long)`, `refreshObserved(long)`, `registrations()`, `watchable`, `towerPlume`, `heaterFlue`.
  - The method references `this::flareState` and the others bind only `this`.
  - `FluidWorldAuthority::inRange`, `::moverRange` and `::flued` are static.
  - So each part reads the same objects at call time that the inline code read.
- **The flare, still and mover checks** now test the kind after the cell and the dimension (`watchable`), not before. All three tests are pure reads of the record, so the result is the same, and the block entity is read under the same condition as before.
- **The cadence empty check** uses the new `Watchers.empty()` (`byDevice.isEmpty()`), which is equal to `devices().isEmpty()`.
- **The iteration order** of `Set.copyOf` depends on the JVM's salt, as before: the same calls on the same `Watchers` give the same order.

## 5. Tests for the main thread

Files changed: `runtime/fluid/FluidWorldAuthority.java`; new `runtime/fluid/Display.java`. No test file and no `network/**` file changed. The gate groups do not load either file: `equipmentGates` loads only `runtime/fluid/FluidView*`, and `columnGates` loads no `runtime` file. So the runs are in `test` and `compileFluidGameTestJava`.

1. `./gradlew compileJava compileTestJava compileFluidGameTestJava`. Proves that the generic inference, the method references in field initializers and the overloads compile (section 6).
2. `./gradlew test --tests com.wormzjl.icheme.LayeringTest --tests com.wormzjl.icheme.runtime.fluid.PresentationBucketTest --tests com.wormzjl.icheme.runtime.fluid.FluidPacketCodecTest --tests com.wormzjl.icheme.runtime.fluid.IslandSetsPresentedTest --tests com.wormzjl.icheme.runtime.fluid.ViewIndexTest --tests com.wormzjl.icheme.runtime.fluid.OpenTankRuntimeTest --tests com.wormzjl.icheme.runtime.fluid.DisplayCadenceTest --tests com.wormzjl.icheme.runtime.fluid.CoolingTowerRuntimeTest`. The `test` task also depends on `compileFluidGameTestJava`. What the classes prove:
   - `LayeringTest`: `Display` names `network`, not `client`.
   - `OpenTankRuntimeTest`: `FluidWorldAuthority.Watchers`, which keeps its qualifier.
   - `DisplayCadenceTest`: `displayDue`.
   - `CoolingTowerRuntimeTest`: `plumeAir`.
   - The others: the presentation and view classes in the brief's list.
   - The grep for `FluidWorldAuthority*Test` found none. `*WatchTest` found only `AssemblyToolWatchTest` and `*ViewTest` only `CardViewTest`; neither loads the authority, so both are left out.
3. Final lane, fluid GameTests (they build a real server and authority):
   - `PumpHealthGameTests.anInlinePumpPublishesItsCrackle`: reads `publishedMover`, through `moverDisplay.publish` and `published`.
   - The open-tank and basin paths through `present` → `publishTank`: `CoolingTowerGameTests.parallelCellsRegisterAsOneEmptyOpenBasin`, `ConverterGameTests.aLiquidConverterConvertsBothWaysThroughItsForgePort` and `aGasConverterRegistersClosedAndTakesItsValves`, `FluidCheckpointGameTests.aSavedRestingTankReloadsCertifiedWithItsStatusLineAndNoSolve`.
   - The sight-glass block path: `RegistryFirstGameTests.aBrokenLineIsSetBackFromTheRegistry`.
   - The flare's cadence pass with no watcher: `ReliefFlareGameTests.aDrumRelievesNitrogenToASmallFlareWithoutAHold`.
   - Every GameTest that ticks the authority runs the five `publishDue` passes and `forgetWatcher` at the end.
   - **No GameTest sends a watch request** (no `watch*` call, and no mock player watching a display). So the watch, replay and fan-out send paths are exercised by no automated test. They rest on this reading, and on the owner's in-game check if one is wanted.

## 6. Points not verified, with file and line

Nothing was compiled. These are the places where a compile or a capture could differ from what I read:

1. `FluidWorldAuthority.java` 98-135: generic inference of `Display.bucket` and `Display.cadence` from the field's declared type, with implicitly typed sink lambdas, `null` hooks, and `Display.each(this::flareState)` nested as an argument (lines 116, 120, 134). I expect it to infer, because the S of each factory is fixed by the assignment target, and `flareState`, `stillState` and `chimneyState` are not overloaded.
2. `FluidWorldAuthority.java` 98-135: field initializers that use method references and lambdas calling instance methods before the constructor body runs. None reads a blank final field by its simple name: those were replaced by `this::online` (2118) and `this::observeWatched` (2116), so javac's definite-assignment rule for lambda bodies does not apply. `Silhouettes silhouettes=new Silhouettes(this::structuresOf)` in the same initializer list already uses this form.
3. `FluidWorldAuthority.java` 125, 130: `this::presentation` inside a lambda. `presentation` is both a field and a method `presentation(long)`; a method reference finds only the method, as at the old call site.
4. `FluidWorldAuthority.java` 1808: `display.forget(player)` with a UUID on `Display<?>`, and `tankDisplay.forget(device)` with a long: two overloads, `forget(UUID)` and `forget(long)`. Also `unpublish(long)` and `unpublish(Collection<Long>)`, called with a `Set<Long>` at the commit.
5. `Display.java` 35-39: the nested interface `Sink<S>` declares its own S, which hides the class's S. This is legal; javac may warn.
6. `Display.java` 41, 149: it uses `FluidWorldAuthority.Watchers` (package-private, nested), `FluidWorldAuthority.cell` (made package-private, `FluidWorldAuthority.java` 2154) and `displayDue` (package-private already).
7. `FluidWorldAuthority.java` 2008: `watchable` gained a `Display.Range` parameter. All five callers pass a static method reference.
8. `FluidWorldAuthority.java` 2152: the sight glass's `Target` is built before the cache `put`; before, it was built after. `Target`'s constructor throws for an identity ≤ 0. Ledger identities are positive, so this cannot be seen. I did not prove that identities are positive; I read the code as giving them from `nextIdentity`.
9. `tools/custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java` 1150-1168 (outside my lane):
   - What breaks: it calls `publishTowers` and `publishHeaters` by reflection and reads `towerWatchers.byDevice` and `heaterWatchers.byDevice`. These names are gone, so the probe now fails with a `NoSuchMethodException` or `NoSuchFieldException` when it is run.
   - The fix: read `towerDisplay` → `watchers` → `byDevice`, and call `publishDue(long)` on the `Display` object with the current online tick.
   - Effect on the result: `publishDue` also runs a due-device `removeIf` that the old call ran too, so the measurement stays the same.
   - It is a compile-free tool, run only by hand in a dev client.

Not checked: whether `Display` as a simple name hides another type in `runtime/fluid` files. My grep found `Display` only in comments there (`PipePresentation`, `OpenTankRuntimeTest`). No class named `Display` exists under `src/main/java`, and a same-package type wins over on-demand imports.
