# Manual test: the column failure modes world

Stage 5 (W8) of [EXECUTION_PLAN.md](EXECUTION_PLAN.md). The world was built and scripted by
`ColumnFailureModesReviewWorld.theFailureModesPlantRunsItsCases` (batch `review-failure-modes`) at full size and 20 TPS,
then copied to `run/saves/column-failure-modes` in this worktree.

## Launch

The owner starts the client. From this worktree (no test suite may run while it is open):

```bash
./gradlew runClient
```

Singleplayer → `column-failure-modes`. The plants stand far from spawn. Teleport to plant A's column:

```
/tp @s -14621096 -40 -12093690
```

Plant B stands 360 blocks east: `/tp @s -14620736 -45 -12093690`.

## The plants

- **Plant A.** The network's default crude column (`medium_crude`) at scale 1: 710.3 mol/s feed, 8 m across, 40 trays,
  three draws, three pumparounds, sump steam.
  - Condenser: air then water, two air cooler banks in parallel (each 6×4 bays of 2×6 m, 940 m²; the air holds 35.4 MW,
    the trim 0.9 MW), the trim, the receiver.
  - A reflux pump and three pumparound pumps, each on a motor fed with FE. Three pumparound coolers (13.1, 21.0 and 13.2 MW)
    on cooling water.
  - The feed: a generator (liquid, 662 K and 48 bar, above the crude's bubble point at the feed's enthalpy) through SUPPLY
    FCV and SUPPLY FT into the surge drum, then the feed valve, where it flashes.
- **Plant B.** A reboiled column: the template crude at 1/16, 30 stages fed on 24, reflux 2, an 8 MW (full-feed) kettle on
  superheated steam (800 K, 20 bar into 5 bar), a water condenser.

Named parts (x, y, z). Every shutoff and utility valve toggles with an empty-hand right-click.

| Plant A | at | Plant A | at |
|---|---|---|---|
| COLUMN | -14621096 -60 -12093702 | FEED SHUTOFF | -14621090 -50 -12093704 |
| AIR CONDENSER 1 / 2 | -14621115 -60 -12093733 / -12093765 | STEAM SHUTOFF | -14621082 -53 -12093696 |
| TRIM CONDENSER | -14621124 -60 -12093719 | DRAW 1 / 2 / 3 SHUTOFF | -14621082 -35 / -40 / -46 -12093696 |
| RECEIVER | -14621133 -60 -12093718 | BOTTOMS SHUTOFF | -14621090 -60 -12093704 |
| REFLUX PUMP / MOTOR | -14621108 -60 -12093701 / -14621107 -60 -12093698 | OFFGAS / SOUR WATER SHUTOFF | -14621132 -44 / -57 -12093717 |
| PA1 / PA2 / PA3 PUMP | -14621105 -60 -12093682 / -665 / -648 | CW TRIM (return -51) | -14621123 -60 -12093720 |
| PA1 / PA2 / PA3 COOLER | -14621121 -60 -12093683 / -666 / -649 | PA1 / PA2 / PA3 CW (return -51) | -14621120 -60 -12093684 / -667 / -650 |
| FEED SURGE DRUM | -14621085 -60 -12093721 | SUPPLY FCV / SUPPLY FT | -14621084 -53 -12093713 / -715 |
| DCS-1, LIC-1, FIC-1 | -14621077 -60 -12093721 / -718 / -715 | DCS-2 (no cable) | -14621077 -60 -12093725 |
| FANS POWER (cable) / source | -14621102 -59 -12093724 / -14621106 -60 -12093709 | REFLUX MOTOR POWER (cable) | -14621110 -59 -12093697 |
| PUMPS POWER SOURCE | -14621111 -60 -12093698 | DATA BUS (column end) | -14621085 -59 -12093695 |

| Plant B | at | Plant B | at |
|---|---|---|---|
| COLUMN | -14620736 -60 -12093702 | KETTLE | -14620724 -60 -12093702 |
| KETTLE STEAM (out -60, -12093706) | -14620724 -55 -12093702 | CW CONDENSER (return -52) | -14620749 -60 -12093723 |
| FEED SHUTOFF | -14620733 -51 -12093704 | DCS-B | -14620731 -60 -12093694 |

## The cases

Open the column's Operation tab (or DCS-1's column page) and watch the status, the conditions table and the alarm. Each
time is from the script's run at 20 TPS, with 10 s of holding; the restore takes 1 to 3 minutes.

| # | Action | Expected | Time |
|---|---|---|---|
| 1 | none | RUNNING, no alarm | – |
| 2 | close FEED SHUTOFF | LIMITED: feed, LOW FEED, share 0 | 22 s |
| 3 | close STEAM SHUTOFF | LIMITED: steam (STEAM_SHORT, rule B), LOW STEAM, no UPSET; the feed runs on | 45 s |
| 4 | close DRAW 3 SHUTOFF | LIMITED: outlet, BLOCKED once its buffer fills | 65 s |
| 5 | close CW TRIM | COOLING LIMITED (COOLING_SHORT), share 0 | 90 s |
| 6 | break the FANS POWER cable (any block of it) | NO POWER of the condenser; the feed at natural draught, 34 % | 125 s |
| 7 | break the REFLUX MOTOR POWER cable | NO POWER, member 1, share 0 | 5 s |
| 8 | close PA1 CW | COOLING LIMITED, pumparound 1 (PUMPAROUND_SHORT) | 55 s |
| 9 | flooding | not covered (below) | – |
| 10 | Operation tab: the third draw at 6 × its design rate, Apply | SETPOINT REFUSED in the conditions, one setpoint solve; the column runs on its last set points | 20 s |
| 11 | DCS-1: Stop, Start, Apply the design's set points; DCS-2: Stop | each APPLIED; DCS-2 answered NO DATA LINK, the column runs on | 28 s |
| 12 | FIC-1: sensor SUPPLY FT, output the column, 79.7 kg/s, on | the feed follows the flow target: share 0.50, FIC-1 CONTROLLING | 60 s |
| 13 | plant B: close KETTLE STEAM | HEATING LIMITED (HEATING_SHORT) | 37 s |

Restore each case by undoing its action: toggle the valve again, mend the cable (place power cable on the gap), Apply the
design's set points, FIC-1 off on SUPPLY FCV. The column returns to RUNNING at its design feed.

## Not covered

- **Case 9, flooding.** No set point reaches the flood limit at a fixed heat input. Measured:
  - Plant A at its design runs at 0.69 of flooding (tray 30).
  - Raising the reflux ratio took the worst tray from 0.69 to 0.81, then 0.82 of flooding, not to 1: a higher ratio cuts
    the distillate, and the heat input fixes the vapour.
  - Plant B's raised reboiler duty bound HEATING LIMITED first. Plant A's pumparound duty bound COOLING LIMITED first.
  - The flood limit is covered by `ColumnCycleTest` and `ColumnRunTest`.
- **Case 12, the level form** (LIC-1 on the drum's level, output the column). One measured run at 1/16, the supply liquid:
  - The level held at 81 to 89 % against 90 %.
  - The feed swung between 0 and 1 of its design every 600 ticks and ended at 0.43.
  - With the feed closed, the column's feed valve reads no flow, so LIC-1 has no density (NO LEVEL BALANCE) and the feed
    returns to its set rate.
  - The flow form above stands in; the feed-rate loop is also covered by the stage 2 tests.

## Seen in the full-size run

- After case 2, one throughput solve failed ("Empty fluid initialization") and kept the last table (OFF TABLE) for about
  2 minutes; the next solve cleared it.
- During case 13's restore two fluid islands held and the script's last restore timed out:
  - Island 1364 held from tick 21142 (Newton line search stalled at residual 1.1e-7). Plant A then read BLOCKED on its
    off-gas from tick 21157.
  - Island 1403 held from tick 18497 (singular sparse LU). Plant B's distillate buffer was full by tick 22285 (BLOCKED).
  - The world was saved in that state. A world load drops the solved state and solves the islands again; check whether
    both plants return to RUNNING before the cases.
