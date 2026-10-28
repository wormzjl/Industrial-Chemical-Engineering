# old-mover-removal

Purpose: write the corpus image `src/test/resources/compat/removed-movers/`, a saved world that holds the one-block pump and compressor, which this build no longer has. `FluidFactsCompatTest.aWorldWithOneBlockMoversOpensWithoutThemAndKeepsEveryVessel` reads it.

Batch: [2026-10-28-old-mover-removal](../../documentation/2026-10-28-old-mover-removal/README.md).

## Entry points

- `image-writer/RemovedMoverImageWriter.java`: a JUnit class in the package `com.wormzjl.icheme.runtime.fluid`. It builds two lines on `EventPathWorld`, runs them to tick 600, and writes `core.dat`, `pack-1.bin` and `expected.properties` to `build/compat/removed-movers/`:
  - a 3 bar, 340 K nitrogen generator, a pipe, a one-block compressor (0.004 m3/s, ratio 2.5), a pipe and a tank;
  - a 2 bar water generator, a pipe, a one-block pump (0.0005 m3/s, 300 kPa), a pipe and a tank.

## How to run

The writer needs a build that still has the portless `PUMP`/`COMPRESSOR` form, for example main `96806b8a`:

1. Make a scratch worktree of that commit in `.claude/worktrees/mover-image-scratch`.
2. Copy `RemovedMoverImageWriter.java` to `src/test/java/com/wormzjl/icheme/runtime/fluid/` in it.
3. Run `./gradlew test --tests '*RemovedMoverImageWriter'` there.
4. Copy `build/compat/removed-movers/*` to `src/test/resources/compat/removed-movers/` on the batch branch.
5. Remove the scratch worktree with `git worktree remove`.

## History

- 2026-10-28: written and run on main `96806b8a` (facts schema 4): 10 registrations, 2 movers, 2 vessels, 740.3813439031998 mol in the vessels.
