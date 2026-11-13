# A5 Runtime: report

Branch `claude/review-wave-a5-runtime-1a45f8`. Three commits; tests written, not run yet (the lane is held for the bench thread; see "Tests" below).

## R5, a read never books (2828ae388)

Files: `IslandCoordinator`, `FluidWorldAuthority`; tests `ExchangerCouplingTest`, `PresentationBucketTest`, a comment in `FluidPresentationGameTests`.
`presentation(id)` builds a preview: for a certified island with `target > committed` (target as before: `min(online, fence, hold, horizon)`, one tick short of the horizon) a `Snapshot` on `observedAs(certificate.graphAt(target), taps)`, clock `(online, target, 0, cadence)`, the certified or event-alignment status, payload generation 0; otherwise the stored snapshot. The `presentation` flag of `materialise` is gone. `storedOnly` is gone; `stored(read)` is now a plain `read.get()`, kept because its two callers are in `network/FluidNetwork.java` (288, 436), outside this lane (follow-up: call the read directly, delete `stored`).

What a read used to trigger, and what each consumer now reads:

| Consumer | Before | Now |
|---|---|---|
| island graph and clock (views, menus, instruments, `ColumnOperation.water`, `ShaftTrains.governorValve`, `plannedTarget`) | materialised island | the preview: the same graph and tick a materialisation would reach, nothing stored |
| `booked` (ports, shafts, heat in transit) | a replayed span per read | only at the engine's materialisations: deadlines, fences, `through`, saves, coupling reads, `flushWakes` |
| `replayed` listener (diagnostic observer, `data.setDirty`) | told at each read | told at the engine's materialisations only; a read changes no fact, so no save mark is needed |
| `pipeSpeed` (`bulkVolumeRates`, the pipe page's bulk speed) | fed the read's span | the accepted window plus, for a certified island, the preview span `[committed, target]` at the certificate's recorded rate (computed per call, stored nowhere) |
| `metrics` (`REPLAYED` advance, read by the observer's timings) | rewritten per read | written at the engine's materialisations only |
| `touch()` (payload generation at a fence) | at a read that reached the fence | at the materialisation that reaches it; the preview shows "WAITING: event alignment" itself |
| `lastResult` (instrument readings, tank outlets) | unchanged by a materialisation | unchanged |

No value of a solve moves. Bookings no longer split at read ticks, so their last bits no longer depend on viewers. The counters `materialisations` and `replayedTicks` fall by the reads that had triggered them (`PresentationBucketTest`'s menu: 100 materialisations in 10,000 ticks, now 0). `FluidServerBenchmark` with viewers will report fewer replayed spans.

New test `ExchangerCouplingTest.aPresentationReadEveryTickBooksNothing`: two certified exchanger pairs, the same events, one read (`presentation`) on both sides every tick for 2,000 ticks; transits, port bookings (a recording `Ports`), replayed heat and clocks bit-equal; each view shows `min(epoch, horizon - 1)`, and at the end equals a materialised snapshot of the other pair bit for bit; after a save on both, transits and bookings still equal. `PresentationBucketTest`: replayed counts and boundary totals with and without viewers now exactly equal; a menu materialises nothing.

## R26, one flash per generator edit, option A (fe2a79565)

Files: `PhysicalRegistry`; test `PhysicalRegistryTest`. `submit` keeps its validation flash and the reservoir it built, by event id and device id (memory only). `apply` uses it when the registration it applies equals the validated one, else flashes (an event restored from a save). Entries leave when the event applies. The registry's model is final, so the reservoir is identical. New test `aGeneratorEditIsFlashedOnceAndAppliesTheValidatedStock`: one initialisation for a placement and one for a respecification (a package-private counter), the island's node bit-equal to a fresh `initialize`. `FluidDeviceSpec` unchanged.

## M-02 second half (d2d2f727d)

Files: `FluidWorldAuthority`; new test `MoverHealthStatusTest`. The health clause moved to `withHealth(status, mover, word)`, used for every mover `PumpHealth.worn` accepts. A liquid pump's line is unchanged. A dry screw's line now carries the `FAILED: use a repair kit /` prefix, or ends with `/ LIQUID` while liquid reaches it (new text on its page). No in-world dry-screw view test exists; the unit test covers the rule, and a `PumpHealthGameTests` case (with A3's kit change) would cover it in the world.

## Tests

To run once the lane is free, `--max-workers 4`, each once: `compileJava compileTestJava`, then `test --tests` for `ExchangerCouplingTest`, `PresentationBucketTest`, `IslandCertificateTest`, `CrystalIslandRuntimeTest`, `WorkerFailureStatusTest`, `ColumnRunTest` (they read `presentation` or count materialisations), `PhysicalRegistryTest`, `MoverHealthStatusTest`; and `holdGates --tests IslandSchedulerTest` (counts materialisations; a slow gate).

GameTests for the parent's final lane: `FluidPresentationGameTests` (a certified view at its bucket), `FluidCheckpointGameTests`, `ColumnOperationGameTests`, `CreateCouplingGameTests` (read certified islands), `PumpHealthGameTests` (pump status line).
