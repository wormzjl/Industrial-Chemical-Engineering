# ph-false-root

Purpose: the sweeps and the final lanes of two free-water fixes in `FluidTpEquilibrium`. Batch [`2026-11-02-ph-false-root`](../../documentation/2026-11-02-ph-false-root/README.md), review [PH_FALSE_ROOT_REVIEW.md](../../documentation/2026-11-02-ph-false-root/PH_FALSE_ROOT_REVIEW.md); batch [`2026-11-02-tp-steam-refusal`](../../documentation/2026-11-02-tp-steam-refusal/README.md), review [TP_STEAM_REFUSAL_REVIEW.md](../../documentation/2026-11-02-tp-steam-refusal/TP_STEAM_REFUSAL_REVIEW.md).

## How to run

The sweeps need no Gradle: the science package has no Minecraft imports. From the worktree root, in PowerShell:

```powershell
.\tools\ph-false-root\build.ps1
. .\tools\ph-false-root\env.ps1
Probe tmp\ph-false-root\base trips tmp\ph-false-root\base-trips.tsv
Probe tmp\ph-false-root\base gap tmp\ph-false-root\base-gap.tsv
```

To compare a candidate, copy `FluidTpEquilibrium.java` to `tmp/ph-false-root/forms/<name>/`, change it, and run these commands. Compile `base` from the checkout you compare against, and do not rebuild it afterwards.

```powershell
.\tools\ph-false-root\form.ps1 <name> -modes trips,gap,refusals
```

```bash
perl tools/ph-false-root/cmp.pl tmp/ph-false-root base form-<name>
perl tools/ph-false-root/moved.pl tmp/ph-false-root base form-<name>
```

The final lane runs from the branch's worktree, with no dev client. The campaign's wall deadlines must not share the machine with another campaign, so a hold of a budget class is replayed alone if they did:

```bash
bash tools/ph-false-root/lane.sh head
perl tools/ph-false-root/compare.pl <baseline dir> tmp/ph-false-root/lane/head
perl tools/ph-false-root/ends.pl <baseline dir> tmp/ph-false-root/lane/head
perl tools/ph-false-root/values.pl <baseline dir>/printed.txt tmp/ph-false-root/lane/head/printed.txt
```

`LANE_OUT` moves the lane's folder and `LANE_RUN` names a fresh GameTest run id, so that one lane can compare against another's folder.

The baseline folder holds main's `campaign-*-report.txt`, `printed.txt` and `junction.txt`. The false root batch reused the archive of the column-operation lane at `3ffeb051`. Without one, run `lane.sh base` in a scratch worktree on main, with `BASE_WT` set to it.

`replay.sh <seeds>` runs the seeds alone with one worker, after waiting for other test, client and probe JVMs to end. Use it for new budget-class holds, batched in one run.

`proof.sh` runs each gate of the TP steam batch with its rule disabled, one test at a time, and restores the source file from git after each. Start it with the source file committed.

## Entry points

- `PhProbe.java`, in the package of `FluidThermodynamics`. Its modes:
  - `trips <tsv>`: 306 TP states of each of 18 feeds, flashed back by PH and by cold UV. A round trip is the only test that shows a false root, because a balance answer meets its target by construction.
  - `gap <tsv>`: 10 targets at each of 6 pressures, spread between 12 K under water's saturation and 12 K over it.
  - `step <tsv>`: clean heavy cuts and crude at low water ratios, with traces of methane.
  - `traces <tsv>`: water holding 1/30 to 1e-6 of hydrocarbons, on crude, a middle cut, n-pentane and a heavy cut.
  - `refusals <tsv>`: every state of the `trips` grid the TP flash refuses, with its reason.
  - `nearrefusals`, `neartrips`, `neargap <tsv>`: the same on 20 near-pure feeds, a middle cut and a heavy one, with traces of the next cut from 0 to 1e-2.
  - `purestep <tsv>`: 6 hydrocarbons at 4 water ratios and 6 pressures: targets between the highest T with no gas and the lowest T with no condensed water, each PH answer flashed back by UV.
  - `tpcost`: microseconds per TP state, warmed up. Run it alone.
  - `whyfail <feed>`: one feed's failing round trips by reason. `uvat <feed> <T> <P> [near]`: UV and PH of one state.
  - `scan <feed> <P> <from> <to> <step>`: one feed along T, printed. `purescan`: the same on the `purestep` feeds.
  - `names`: the components.
- `env.ps1`: the class path and the `Probe` function. `build.ps1`: compiles the checkout into `tmp/ph-false-root/base`. `form.ps1`: compiles one form over it and runs the sweeps named.
- `cmp.pl`: per feed, refusals, misses, failures and TP answers per solve, of two sweeps, on the rows' keys. `moved.pl`: the states the two sweeps treat differently.
- `lane.sh`: from `tools/heat-exchanger-design/merge-lane/lane.sh`. `replay.sh`: seeds alone. `proof.sh`: the gates with their rules disabled.
- `compare.pl`: per-seed holds and counters of two campaigns, from the column-operation lane's. `ends.pl`: the worlds whose end state differs. `values.pl`: printed lines that still differ with clocks, durations, object ids and heap sizes removed.

## History

- 2026-11-02: forms A (the brief's: no early close with hydrocarbons), B (a TP trial just under the saturation), C (the liquid's vapour pressure as a share of P) at 1e-6 to 1e-3, and D (C bounded by the hydrocarbons in the balance's steam). A and B lost targets of a clean heavy cut; C lost water holding a trace of oil. D was built. The numbers are in the review.
- 2026-11-02: the final lane at `be052e63` against main's baseline at `3ffeb051`, and seed 118 replayed alone. Logs: `tools/ph-false-root/logs/final-lane-logs-2026-11-02.tar.gz` in the main checkout (local, never staged).
- 2026-11-02: TP steam batch: `refusals` and the near-pure modes, `purestep`, `tpcost`, `uvat`. `cmp.sh` paired rows by line number and misread refused rows, so `cmp.pl` replaces it. Forms T1 (the closure in `solveWet`) and T2 (T1 and PH at the true step). `proof.sh` added; `lane.sh` and `replay.sh` take a folder and seeds. The final lane at `60e13ca5` against the lane at `be052e63`, and seeds 183, 431, 475, 768 alone. Logs: `tools/ph-false-root/logs/tp-steam-final-lane-logs-2026-11-02.tar.gz` in the main checkout (local, never staged).
