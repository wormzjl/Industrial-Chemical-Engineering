# load-hold

Scratch test of the crude island's round-deadline hold (batch `2026-11-16-review-plant-sizing`, `LOAD_HOLD_REVIEW.md`).
Run as a test, never committed under `src/test`.

- `LoadHoldScratchTest.java`: loads a copy of a world's fluid checkpoint from `tmp/load-hold/world/data` (the `data/`
  folder of a save), reloads it into `EventPathWorld` at factor 4, runs ticks and prints each job's solver counters;
  decodes island 1402 from an older pack (offsets of `fluid-3.pack` hard-coded) and runs it beside the other islands;
  solves the island's first job from the committed interval and fresh, with the pump's speed stepped, and with
  rebuilt but equal graphs. Prints, asserts nothing.

Run: copy it into `src/test/java/com/wormzjl/icheme/runtime/fluid/`, then
`./gradlew test --tests com.wormzjl.icheme.runtime.fluid.LoadHoldScratchTest --max-workers=4`, read the `SCRATCH` lines
of `build/test-results/test/TEST-com.wormzjl.icheme.runtime.fluid.LoadHoldScratchTest.xml`, and remove the copy.
