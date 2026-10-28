# Column operation measurements

Purpose: measure what the column unit's split table stands on before it is built: homogeneity in the throughput, the table's error against feed drift, warm solve success and time, and the cost of rule B's solves (plan section 7).

Batch: `documentation/2026-10-28-column-operation/` (review `C1_MEASUREMENT_REVIEW.md`).

## How to run

The bench is a JUnit class in the package `com.wormzjl.icheme.science.column.v3` (it reaches package-private test fixtures). It is not a gate. Copy it into the test tree, run it alone on the machine (it times solves), and remove it again:

```text
cp tools/column-operation/probes/ColumnOperationC1BenchTest.java src/test/java/com/wormzjl/icheme/science/column/v3/
./gradlew test --tests 'com.wormzjl.icheme.science.column.v3.ColumnOperationC1BenchTest.sweep' -i
rm src/test/java/com/wormzjl/icheme/science/column/v3/ColumnOperationC1BenchTest.java
```

It writes `tmp/column-op-c1/sweep-<native|java>.tsv` and prints one `C1` line per case. `-PnativeThermoBatch=false` gives the Java outer pass.

## Entry points

| Test | Solves | Prints |
|---|---|---|
| `sweep` | three designs on `tjl20_methane_nitrogen` (the `tia_juana_nitrogen` preset; the same with a 310 K condenser and reflux 8; a 15 MW kettle without steam): one cold base each, then warm (`AGGRESSIVE`) solves at alpha 0.5 and 1.2 with hydraulics and with a prescribed 0.7 kPa drop, feed T -10..+10 K, the feed 1/5/10 % toward WTI Light Export and Cold Lake Blend, steam short 10/25/50 %, condenser +5/+10 K | status, time, path; the base table applied to the new input against the new solve: largest product flow error, composition L1, product temperature change, the condenser duty closed on the table's products against the solved one, sump pressure and temperature change |
| `probe` | candidate designs (cold solves): the owner's cold-condenser and high-reflux cases | outcome, duties, wet trays, the water dew-point advisories, flood and drop |

`results/` holds the TSV of the C1 run (2026-10-29, C++ outer pass). The run's console log is archived in the main checkout, `tools/column-operation/logs/c1-sweep-logs-2026-10-29.tar.gz` (local, never staged).

## History

- 2026-10-29: written for C1. Sweep: 63 cases in 45 s, every warm solve accepted on the warm route; rule B median 0.33 s, 95th percentile 0.52 s. Findings in the review.
