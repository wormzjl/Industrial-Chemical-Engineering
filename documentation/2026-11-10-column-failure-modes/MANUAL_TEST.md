# Manual test: the column failure modes world

Stage 5 (W8) of [EXECUTION_PLAN.md](EXECUTION_PLAN.md). The world was built and scripted by
`ColumnFailureModesReviewWorld.theFailureModesPlantRunsItsCases` (batch `review-failure-modes`) at full size and 20 TPS,
then copied to `run/saves/column-failure-modes` in this worktree.

## Launch

The owner starts the client. From this worktree (no test suite may run while it is open):

```bash
./gradlew runClient
```

Singleplayer → `column-failure-modes`. The player spawns beside DCS-1, three blocks west of it. Plant B stands 360 blocks
east of plant A: `/tp @s 1601982 -45 -6590690`. Both plants were saved RUNNING at their set feed with no fluid island held.

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
  superheated steam (800 K, 20 bar into 5 bar), a water condenser. Its 400 K distillate runs down to a tank at 2 bar
  (DISTILLATE): it leaves the receiver saturated at 2.5 bar and is not let down to the atmosphere (README, open items).

Named parts (x, y, z); every shutoff and utility valve toggles with an empty-hand right-click. The draws, bottoms, off-gas
and sour water shutoffs, the cooling-water returns and the other pumps stand beside these (the run's `place` log lines).

| Plant A | at | Plant A | at |
|---|---|---|---|
| COLUMN | 1601622 -60 -6590704 | FEED SHUTOFF | 1601628 -50 -6590706 |
| AIR CONDENSER 1 | 1601603 -60 -6590735 | AIR CONDENSER 2 | 1601603 -60 -6590767 |
| STEAM SHUTOFF | 1601636 -53 -6590698 | TRIM CONDENSER | 1601594 -60 -6590721 |
| DRAW 3 SHUTOFF | 1601636 -46 -6590698 | RECEIVER | 1601585 -60 -6590720 |
| CW TRIM | 1601595 -60 -6590722 | REFLUX PUMP | 1601610 -60 -6590703 |
| REFLUX MOTOR | 1601611 -60 -6590700 | REFLUX MOTOR POWER | 1601608 -59 -6590699 |
| PA1 PUMP | 1601613 -60 -6590684 | PA1 COOLER | 1601597 -60 -6590685 |
| PA1 CW | 1601598 -60 -6590686 | FEED SURGE DRUM | 1601633 -60 -6590723 |
| SUPPLY FCV | 1601634 -53 -6590715 | SUPPLY FT | 1601634 -53 -6590717 |
| DCS-1 | 1601641 -60 -6590723 | LIC-1 | 1601641 -60 -6590720 |
| FIC-1 | 1601641 -60 -6590717 | DCS-2 (no cable) | 1601641 -60 -6590727 |
| FANS POWER | 1601616 -59 -6590726 | FANS POWER SOURCE | 1601612 -60 -6590711 |
| PUMPS POWER SOURCE | 1601607 -60 -6590700 | DATA BUS (column end) | 1601633 -59 -6590697 |

| Plant B | at | Plant B | at |
|---|---|---|---|
| COLUMN | 1601982 -60 -6590704 | KETTLE | 1601994 -60 -6590704 |
| KETTLE STEAM | 1601994 -55 -6590704 | CW CONDENSER | 1601969 -60 -6590725 |
| FEED SHUTOFF | 1601985 -51 -6590706 | DISTILLATE | 1601973 -55 -6590716 |
| DCS-B | 1601987 -60 -6590696 |  |  |

## The cases

Open the column's Operation tab (or DCS-1's column page) and watch the status, the conditions table and the alarm. Each
time is from the script's run at 20 TPS, with 10 s of holding; the restore takes 1 to 3 minutes.

| # | Action | Expected | Time |
|---|---|---|---|
| 1 | none | RUNNING, no alarm | – |
| 2 | close FEED SHUTOFF | LIMITED: feed, LOW FEED, share 0 | 15 s |
| 3 | close STEAM SHUTOFF | LIMITED: steam (STEAM_SHORT, rule B), LOW STEAM, no UPSET; the feed runs on | 65 s |
| 4 | close DRAW 3 SHUTOFF | LIMITED: outlet, BLOCKED once its buffer fills | 57 s |
| 5 | close CW TRIM | COOLING LIMITED (COOLING_SHORT), share 0 | 79 s |
| 6 | break the FANS POWER cable (any block of it) | NO POWER of the condenser; the feed at natural draught, 34 % | 120 s |
| 7 | break the REFLUX MOTOR POWER cable | NO POWER, member 1, share 0 | 5 s |
| 8 | close PA1 CW | COOLING LIMITED, pumparound 1 (PUMPAROUND_SHORT) | 55 s |
| 9 | flooding | not covered (below) | – |
| 10 | Operation tab: the third draw at 6 × its design rate, Apply | SETPOINT REFUSED in the conditions, one setpoint solve; the column runs on its last set points | 20 s |
| 11 | DCS-1: Stop, Start, Apply the design's set points; DCS-2: Stop | each APPLIED; DCS-2 answered NO DATA LINK, the column runs on | 29 s |
| 12 | FIC-1: sensor SUPPLY FT, output the column, 79.7 kg/s, on | the feed follows the flow target: share 0.50, FIC-1 CONTROLLING | 60 s |
| 13 | plant B: close KETTLE STEAM | HEATING LIMITED (HEATING_SHORT) | 35 s |

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

## The full-size run

- The script's last line: `review_failure end plantA=RUNNING plantB=RUNNING held=0` (600 ticks running at the set feed).
- Two earlier full-size runs ended with held fluid islands; both causes are corrected:
  - Plant A's off-gas after case 11 (Stop, Start): the outlet planned round-off as product (fixed in `ColumnOperation`).
  - Plant B's distillate after case 13's restore: its saturated 400 K distillate flashed into the atmosphere across the
    reopening valve and the island's Newton search stalled (a fluid-solver item, README); the rundown now stands at 2 bar.
- Three islands held for one round in case 3 ("round deadline or worker failure") and released on their own.
- After case 2 one throughput solve may fail ("Empty fluid initialization") and keep the last table (OFF TABLE) for about
  2 minutes; the next solve clears it.
