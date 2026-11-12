# Final lane of the aqueous-solvents batch

2026-11-12, on `1bd0b4236` (main 0.27.1 merged; `crude_unit` written again on today's data, two lines). One run of
`tools/merge-lane/lane.sh head`, in the main thread (owner: "Do this task here"). Baseline: main's 0.27.1 lane
(`valve-followups-final-lane-logs-2026-11-11.tar.gz`). Outputs: `tmp/final-lane/head/`.

| Step | Baseline | This lane |
|---|---|---|
| Tests | 2680, none failing | 3385 (test 352 of 395 classes, holdGates 12, columnGates 80 of 83, equipmentGates 27 of 28), 2 failing |
| chain-100 exact | 0 | differs: moles 4.66e-3 relative, 1.0e-4 K, phase fraction 2.1e-4 |
| Fluid GameTests | 94 pass | 2 fail |
| Campaign clean (cold, bundled random, defaults) | 991, 990, 1000 | 990, 993, 1000; refusals 0 |
| Newton iterations | | -0.8 %, -19.5 %, +5.1 % |
| Seed time, summed | 1397 s, 244 s, 46 s | 1441 s (+3 %), 324 s (+33 %), 57 s (+22 %) |
| Junction lines | 33 | 33, every counter identical |

## Defect: a dry island does not reload certified (both GameTest failures)
1. Expected: a resting tank saved with its certificate reloads certified, with its status line and no solve.
2. What the code did: an island whose nodes hold no water runs on a model without water in its cubic (W3b,
   option A). The save names the island's model by package and compounds only (`IslandCoordinator.entry`,
   `FluidCheckpointCodec.PackageKey`), and the load builds it with water (`PackageKey.on`: `network.on(compounds)`).
   The two models have different revisions, so `IslandCertificate.Signature.difference` discards the certificate:
   "its property revision changed". The test's tank holds nitrogen. No fact is lost; the island solves again.
   The second failure (`registeredPipesAndReservoirsKeepSimulatingAcrossUnloadedChunks`) asserts an empty world
   and found the first test's tank.
3. Proposal: the saved key carries whether the model has water (`PackageKey(packageId, compounds, wet)`, the
   island row and the package table); state format 50; a unit test of a nitrogen tank saved certified. Facts are
   untouched: the facts route already forms the island's set again.

## Reference to move: chain-100
The reference was captured on 2026-11-05 (`77152ecaf`), before the batch replaced the equation of state (derived
pairs, water in the cubic). The largest moves are in interval 0. Proposal: capture it again with that cause.

## Cost findings (no hold counted)
- `HoldClassWorldsTest.aResidueIsBookedWithoutAClosure` held on a wall deadline in the lane; replayed alone it
  passes in 18.0 s. On main it takes 2.8 s: 6.5 times.
- `MIXED_GAS_COST`: 313 Newton solves on both lanes; 346 ms to 876 ms; 175 MB to 802 MB allocated.
- Bundled campaign: 19.5 % fewer Newton iterations, 33 % more seed time.
- Cold seeds 6 and 431 were class F in the lane. Replayed alone in one run: 6 is clean; 431 holds on a wall
  deadline at tick 3085 (island 42, ten crystal vessels). On main 431 held at tick 1065 on a trace underflow.

## Campaign by class
- Cold: new 65 E5, 919 E5, 743 E6; gone 85 E1, 181 E2, 118 F; 431 B to F. Holds now: B 589, 629; E5 65, 101,
  752, 803, 919; E6 743; F 431.
- Bundled random: new 575 E2 (open since W3b); gone 301 B, 896 E5, 47 F, 678 F; 431 F to E6.
- Defaults: none. Seed 358 is clean: its fix (`b77a807a4`) is measured.
- No hold class is new to the baseline as a whole.

## Expected failure
`LanguageFilesTest`: the keys of this batch are not translated yet (the translation commit). `UnitTextTest` and
`FlowsheetExamplesTest` pass.

## Not settled by this lane
- The two GameTests and chain-100 pass only after the two proposals above.
- The cost findings are measurements, not fixes: the cost work named in the README's open items.
