# Native column default probes

Purpose: measure what the C++ column library changes when it is the default, by solving the same column with the C++ and with the Java outer pass of the inside-out trial and comparing what the solver and the calculator publish.

Batch: `documentation/2026-10-28-native-column-default/` (review `NATIVE_DEFAULT_REVIEW.md`).

## How to run

The probes are JUnit classes in the package `com.wormzjl.icheme.science.column.v3` (they reach package-private solver classes). They are not gates. Copy one into the test tree, run it, and remove it again:

```text
cp tools/native-column-default/probes/CrudeProbeTest.java src/test/java/com/wormzjl/icheme/science/column/v3/
./gradlew test --tests com.wormzjl.icheme.science.column.v3.CrudeProbeTest -i
rm src/test/java/com/wormzjl/icheme/science/column/v3/CrudeProbeTest.java
```

Each probe switches the outer pass with the system property `icheme.native.thermo.batch` (`true` = C++, `false` = Java) and prints one line per case. The C++ library must load (the build packs it when CMake is found).

## Entry points

| Probe | Solves | Prints |
|---|---|---|
| `probes/InsideOutProbeTest.java` | `V3SimultaneousColumnSolver.solveWithInsideOut` on the PC03/PC10 binary, 4/8/15 trays, feed 450/550 K | whether an attempt started from the predictor, iterations, how many state values differ |
| `probes/CalculatorProbeTest.java` | `V3ColumnCalculator` on the same binary, 4/8/15/30 trays, no reboiler duty | outcome per pass, published products identical or not |
| `probes/CalculatorDutyProbeTest.java` | the same binary with a 2/4/6 MW reboiler, 6/10/20/30 trays | outcome, distillate rate and its PC03 fraction per pass |
| `probes/CrudeProbeTest.java` | the dry Tia Juana light crude column of `V3ColdStartTest`, 4/8/15/30 trays, reflux 4.17 and 2 | outcome, distillate rate, largest relative difference of the published products |
| `probes/PilotProbeTest.java` | the 2-tray PC03/PC10 pilot of `V3ColdStartTest` | the two components' constants; the published products and the audit |
| `probes/TrivialRoot.java` | standalone PR78 on the PC03/PC10 binary, no mod classes; run with `java tools/native-column-default/probes/TrivialRoot.java` | feed flash, bubble and dew points, the mixture's roots at 250 kPa, the duties to compare with the published column |
| `probes/Check.java` | `NativeColumnSolver` from a built jar; compile it, then run it with the jar on the class path | where the library was found, `available`, its version, the batch switch and the smoke solve |

## History

- 2026-10-28: written for the native default task. Results (review): on the crude column the two passes publish the same products to 7e-13 relative and take the same time; on the PC03/PC10 binary every published success has no separation (distillate and bottoms at the feed's 50/50) with either pass, and which degenerate root the solve reaches depends on rounding.
- 2026-11-02: `TrivialRoot.java` and `Check.java` moved in from `tmp/` after the merge; the batch's cleanup had missed them. Their results are in the review (sections on the built jar and on the binary).
