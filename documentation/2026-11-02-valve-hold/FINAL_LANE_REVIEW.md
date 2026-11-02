# Final lane: valve hold and column operation

Batch `2026-11-02-valve-hold`, run 2026-11-02. Head: a89294fef, a scratch merge of `claude/column-operation-5c3005` (d5f7a3413) and `claude/valve-hold` (c27f1e189). Base: main 3ffeb0519, whose fluid code equals the point the branch left. Tool: `tools/valve-hold-probes/final-lane`.

## Results

| Check | Base | Head |
|---|---|---|
| `test slowGates` | 1991 tests, 0 failures | 2034 tests, 0 failures |
| chain-100 exact | not run | deviation 0 |
| Fluid GameTests | not run | 36 of 36 |
| Campaign cold, 1000 seeds | 975 clean | 973 clean; 975 once two budget holds are replayed |
| Campaign bundled random | 993 clean | 994 clean |
| Campaign bundled defaults | 1000 clean | 1000 clean |
| `refused=` | 0 on every seed | 0 on every seed |

## Worse worlds

Two cold seeds were new, both class F, `HELD: wall deadline` on one island:

| Seed | Campaign (8 workers) | Replayed |
|---|---|---|
| 183 | 0 holds on base, 1 on head | Alone, no other test worker before or after each run: 0 holds on both |
| 555 | 0 holds on base, 1 on head | Beside another session's test worker: 0 holds on head |

So no world is newly held. Seed 183 alone cost more on head: 168.8 s against 149.8 s, Newton iterations +3.8 %, Jacobian builds +9.0 %. These are the heaviest worlds of the campaign and they stand near the budget. Bundled seed 6 was held on base and is clean on head.

Held on both sides, hold counts moved: cold 431 from 15 to 27 (class b, trace underflow, first hold at the same tick), cold 475 from 3 to 13, cold 101 from 34 to 35, cold 833 from 20 to 17, cold 994 from 27 to 24; bundled 157 from 14 to 15, 241 from 14 to 13, 431 from 9 to 8. Not traced: none is a new held world. Cold 431 and 475 are the two to watch.

## Cost

Counters, cold: Newton iterations +0.1 %, residual evaluations -1.2 %, equation gate rejections -4.9 %. Defaults: flat.
Bundled random: Newton iterations +18 %, Jacobian builds +16 %. One seed carries it: seed 241, held on both sides, went from 2.6 s to 27.5 s and from 14 holds to 13. The summed seed time rose 12 s against that seed's 25 s, so the other seeds net lower.

## Printed lines

MIXED_GAS_COST is unchanged apart from wall time. In the 33 junction lines, 32 differ, in wall time, retained bytes and round-off: the worst mixed-gas moles stay at 5.3e-15 and the worst energy goes from 3.6e-15 to 3.8e-15.
Largest fluid move in the printed lines: 2.5e-6 relative, `PumpCavitationTest` tank and suction pressure. `StagnantJunctionTest` flows move 1.3e-7, `HoldClassWorldsTest` 1e-9. Worst ledgers are unchanged at 1.1e-13 energy and 2.1e-14 component.
The V3 lines move with the column branch (the C++ default, batch `2026-10-28-native-column-default`); `claude/valve-hold` touches no V3 code. The other differences are wall times and test order.

## Conclusion

The gates pass on the merge of both branches, with no newly held world and no fluid reference moved beyond 2.5e-6.
Open: the cost on the heaviest cold seeds, and cold 431 and 475 hold more often. Before a merge: the code guide, the version and the owner's word. Logs: main checkout's `tools/valve-hold-probes/logs/valve-hold-lane-logs-2026-11-02.tar.gz`.
