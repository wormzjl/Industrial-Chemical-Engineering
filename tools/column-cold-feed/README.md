# column-cold-feed

Batch: [`2026-11-17-column-cold-feed`](../../documentation/2026-11-17-column-cold-feed/README.md). A planning probe,
no mod code: what the V3 tray solver does with a feed that makes little or no vapour.

## How to run

From the worktree root, after `./gradlew compileJava processResources`, with the class path of
`tools/column-hydraulics/README.md` (the mod's classes and resources, gson, the EJML jars):

```
java -Dfile.encoding=UTF-8 -cp "<class path>" tools/column-cold-feed/ColdFeedProbe.java
```

`-Dtemperatures=638.15,600,...` sets the feed temperatures (K), `-Dshare=0.25` the share of the design's draws and
pumparound duties of the middle form, `-DdrawShare=1` gives the draws their own share (then one solve a temperature:
the pumparound duties at the share, the draws at theirs), `-Devents=true` prints a failure's events, `-Dadvisories=true` a success's. `-Diterate=0.5` runs the duty loop
instead, at the first temperature: each pumparound's duty is what a cooler of fixed conductance (from the design
point) exchanges at the solved draw tray temperature, relaxed by the given factor; `-Dstart=0.01` the first duties
as a share of the design, `-Dsteps=8` the solves, `-Dcold=300,340,400` the coolers' cold inlets (K).

## What it prints

For each temperature, three cold solves of the network's default column at full scale: the design as it is; the draws
and pumparounds at the share; neither. Each line: the outcome (or the failure's code and summary), the condenser duty,
the top and bottom tray temperatures, every product's flow and temperature, the solver's Newton steps and polls. No time.

## Results, 2026-11-17

`COLD_FEED_REVIEW.md`, section 4. The design fails from 550 K down; a quarter share solves from 460 K up; nothing
solves at 450 K and below. Section 9: the pumparound duties alone solve from 1 % to 100 % at 575 to 638 K. The duty
loop (`COLD_FEED_PLAN.md`, section 2): from 1 % at 638 K it ends at the design duties, in about 7 solves undamped
and 4 with a relaxation of 0.5.
