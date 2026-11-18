# Prune tests review (2026-11-18)

Code guide: no change (the guide names `LineContractTest` and `LineToolSessionTest`, which stay). Tutorial: no change.
Translations: no change. Tool folders: none. Formats: none.

## Deleted

| Test | What it asserted | Why |
|---|---|---|
| `LineContractTest.domainAdaptersCannotRegainCommandOrRouteOwnership` | Call strings absent from `FluidLineBackend`, `ConduitLineBackend`, `PipeLayingClient`, `CableLayingClient`; `CableLaying.java` absent | Implementation (which helper is called) and the past (a file gone from its old path) |
| `LineToolSessionTest.nativeAdaptersCannotReintroduceIndependentInputOrTickOwners` | No `use`/`tick` method, no `armed`/`serverRefused` field in two files | Implementation (method and field names) |
| `SimulatorBlockTest.copyOnATankSendsNoPacketAndSolvesNothing` | The text of the private `copyContents` body and words absent from `SheetClipboard` | Implementation (the text of one private method) |
| `RespiratorDataTest.wornModelAndTextureAgree`, 2 assertions | The geometry names its generator in a comment; `RespiratorModel` contains `RespiratorGeometry.build(head)` | Implementation; the texture size, UV and drawn-texel checks stay |
| `PollutionDataTest`, 1 assertion | `chat.icheme.repair_kit.breach_repaired` absent from `en_us.json` | The past (a removed key) |

## Kept

- Project-rule source scans: `LayeringTest`, `DeterministicMathSourceTest`, `FormatsTableTest`, lang-key coverage
  (`CalculatorColumnTest`, `SimulatorColumnsTest`, `HelpCoverageTest`), `RespiratorDataTest.curiosTypesStayInTheirPackage`,
  `KitLayoutTest.productionScreensNeverOverrideThePlayerGuiScale`, `SimulatorBlockTest.theServersHalfNamesNoSolverAndNoProcessState`
  (engine-schedule rule), `DeviceMetersTest.theMetersNameNoMinecraftClass`, `DeviceTourTest` (every screen tab has a tour),
  `ProcessUnitTest.designsShareOnlyTheKit`.
- Old-save reading tests (`*CompatTest`, legacy stamps, legacy valves): old saves must still read.
- Reflection on private numerics against independent oracles (`V3FeedFlashEquivalenceTest`, `V3FiniteDifferenceJacobianTest`,
  `V3StructuralMatchingOracleTest`, `PengRobinsonKernelHuronVidalTest`).
- Package-private accessors `IslandCoordinator.retainsSolver` and `waitsForInputs`: they guard the cache release of a
  certified island and the hold-retry rule.
- Tests that share a name across files: each is at another layer or on another case.

## Not covered

The sweep found candidates by pattern. Tests whose expected value repeats the code's logic, and duplicates under
different names, need a read of each file; about 160k lines were not read.

## Runs

`testClasses`: compiles. `test --tests PollutionDataTest --tests RespiratorDataTest`: 17 tests, 0 failures.
