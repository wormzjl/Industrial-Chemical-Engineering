# Column pressure-drop publication

Status: Implemented 2026-10-27 (0.16.0, merge `d2e4ae40`).

History:
- Removed the aggressive-path shortcut that published a zero-drop nominal pressure profile beside a positive marched hydraulic summary.
- Focused `slowGates --tests com.wormzjl.icheme.science.column.v3.V3TrayHydraulicsColumnTest` passed after the change.

Documents:
- [PRESSURE_DROP_REVIEW.md](PRESSURE_DROP_REVIEW.md)

Code areas:
- `src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnCalculator.java`

Open items: none for this batch; the full verification lane retains the baseline failures recorded in the task report.

Follow-up: release 0.16.1 hides persisted stale results until a fresh accepted solve.
