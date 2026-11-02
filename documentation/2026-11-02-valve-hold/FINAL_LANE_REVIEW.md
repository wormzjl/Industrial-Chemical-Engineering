# Final lane: valve hold and column operation

Run 2026-11-02, replacing the lane of a89294fef: solver code changed in the review round. Head: `claude/column-operation-5c3005` at 90d3341fa, code as of 321b8d1d3, which holds the valve hold branch. Base: main 3ffeb0519; its `src/main` has not moved, so the base logs stand. Tool: `tools/valve-hold-probes/final-lane`.

## Results

| Check | Base | Head |
|---|---|---|
| `test slowGates` | 1991 tests, 0 failures | 2065 tests, 0 failures |
| chain-100 exact | not run | deviation 0 |
| Fluid GameTests | not run | 38 of 38 |
| Campaign cold, 1000 seeds | 975 clean | 970 clean; 975 once the budget holds are replayed |
| Campaign bundled random | 993 clean | 992 clean; 993 replayed |
| Campaign bundled defaults | 1000 clean | 1000 clean |

## Worse worlds

`refused=0` on every seed. Six seeds were clean on base and held on head, all class F, `HELD: wall deadline`: cold 118, 183, 555, 673, 768, bundled 707. Seeds 6, 994 (cold) and 241 (bundled) were held on base too; only their first hold's class moved to F.

The first replays did not clear them, and the cause was the machine, not the code. A job's wall budget is 2 s. The campaign and these replays ran about 18:50 to 19:15, in a slow phase with no other Gradle run; its cause was not found.

| Seed | Batch replay, 19:00 | One worker, 19:04 | One worker on main, 19:41 | One worker on head, 19:51 to 20:02 |
|---|---|---|---|---|
| 183 | 20 holds | 8 holds, 195 s | 0 holds, 147 s | 0 holds, 118 s |
| 555 | 18 holds | 19 holds, 275 s | 0 holds, 153 s | 0 holds, 150 s |
| 673 | 6 holds | 13 holds, 59 s | 0 holds, 58 s | 0 holds, 58 s |
| 768 | 18 holds | 15 holds, 227 s | 0 holds, 111 s | 0 holds, 110 s |

118 and 707 cleared in the batch replay. So no world is newly held. The batch replay proves little alone: it runs the heaviest worlds side by side.

Trace of the two smallest, 673 and 183, with the budget raised and one worker, at the commit before the review round (c34d7bf2e), 56b083702, 0ada03253 and 321b8d1d3: 25723 and 255777 Newton iterations at all four. The review round added no work on them.

## Cost

Alone, Newton iterations against main: 183 +3.8 %, 555 +6.6 %, 673 +0.9 %, 768 -4.3 %. These predate the review round. Wall time is level with main on these four.
The campaign's own counters are not comparable: held islands stop working, so cold reads -9.9 %. Bundled random reads +13.8 %, carried as before by seed 241, held on both sides, 2.6 s to 27 s.

## Cold 431 and 475

Holds on base, the lane of a89294fef and this lane: 431 15, 27, 17 (first at tick 1088, class b); 475 3, 13, 13 (first at tick 1137, wall deadline). Findings 7(a) and 7(b) did not move either first hold. All but one of these holds are wall deadlines, which the slow phase inflates, so the drop on 431 is no proof of a gain. Both were held on base.

## Printed lines

MIXED_GAS_COST: 299 Newton solves, unchanged. In the 33 junction lines the worst moles stay 5.3e-15; the worst energy is 3.8e-15, 2.8e-15 on base.
The review round moved no fluid line: against the lane of a89294fef only wall times and retained bytes differ. Against base, the largest fluid move is 1.8e-4: `HoldClassWorldsTest` STATIC_COLUMN_WORLD, gas head 45.014 to 45.006 Pa, drop line 9.6e-9 to 1.1e-6 kg/s. It was already in the lane of a89294fef, whose review gave 2.5e-6 and missed it. Next is 2.5e-6, `PumpCavitationTest`. V3 lines move with the C++ default.

## Conclusion

The gates pass on the final code, no world is newly held, and the review round added no solver work. Open: the machine's slow phase, unexplained; a wall budget near the heaviest cold seeds' jobs reads it as holds. Logs: main checkout's `tools/valve-hold-probes/logs/column-final-lane-logs-2026-11-02.tar.gz`; the base's stay in `valve-hold-lane-logs-2026-11-02.tar.gz`.
