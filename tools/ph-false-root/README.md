# ph-false-root

Purpose: the sweeps and the final lane of the PH and UV false root fix on wet oil. Batch [`2026-11-02-ph-false-root`](../../documentation/2026-11-02-ph-false-root/README.md), review [PH_FALSE_ROOT_REVIEW.md](../../documentation/2026-11-02-ph-false-root/PH_FALSE_ROOT_REVIEW.md).

## How to run

The sweeps need no Gradle: the science package has no Minecraft imports. From the worktree root, in PowerShell:

```powershell
.\tools\ph-false-root\build.ps1
. .\tools\ph-false-root\env.ps1
Probe tmp\ph-false-root\base trips tmp\ph-false-root\base-trips.tsv
Probe tmp\ph-false-root\base gap tmp\ph-false-root\base-gap.tsv
```

To compare a candidate, copy `FluidTpEquilibrium.java` to `tmp/ph-false-root/forms/<name>/`, change it, and run these two commands. Compile `base` from the checkout you compare against, and do not rebuild it afterwards.

```powershell
.\tools\ph-false-root\form.ps1 <name>
```

```bash
sh tools/ph-false-root/cmp.sh base form-<name>
```

The final lane runs from the branch's worktree, with no dev client and no other Gradle run, because the campaign's wall deadlines must not share the machine:

```bash
bash tools/ph-false-root/lane.sh head
perl tools/ph-false-root/compare.pl <baseline dir> tmp/ph-false-root/lane/head
perl tools/ph-false-root/ends.pl <baseline dir> tmp/ph-false-root/lane/head
perl tools/ph-false-root/values.pl <baseline dir>/printed.txt tmp/ph-false-root/lane/head/printed.txt
```

The baseline folder holds main's `campaign-*-report.txt`, `printed.txt` and `junction.txt`. This batch reused the archive of the column-operation lane at `3ffeb051`. Without one, run `lane.sh base` in a scratch worktree on main, with `BASE_WT` set to it.

`replay.sh` runs one seed alone with one worker. Use it for a new budget-class hold, and set the seed in the script.

## Entry points

- `PhProbe.java`, in the package of `FluidThermodynamics`. Its modes:
  - `trips <tsv>`: 306 TP states of each of 18 feeds, flashed back by PH and by cold UV. A round trip is the only test that shows a false root, because a balance answer meets its target by construction.
  - `gap <tsv>`: 10 targets at each of 6 pressures, spread between 12 K under water's saturation and 12 K over it.
  - `step <tsv>`: clean heavy cuts and crude at low water ratios, with traces of methane.
  - `traces <tsv>`: water holding 1/30 to 1e-6 of hydrocarbons, on crude, a middle cut, n-pentane and a heavy cut.
  - `scan <feed> <P> <from> <to> <step>`: one feed along T, printed.
  - `names`: the components.
- `env.ps1`: the class path and the `Probe` function. `build.ps1`: compiles the checkout into `tmp/ph-false-root/base`. `form.ps1`: compiles one form over it and runs `trips` and `gap`.
- `cmp.sh`: per feed, misses, failures, moved states and TP answers per solve, of two sweeps.
- `lane.sh`: from `tools/heat-exchanger-design/merge-lane/lane.sh`. `replay.sh`: one seed alone.
- `compare.pl`: per-seed holds and counters of two campaigns, from the column-operation lane's. `ends.pl`: the worlds whose end state differs. `values.pl`: printed lines that still differ with clocks, durations, object ids and heap sizes removed.

## History

- 2026-11-02: forms A (the brief's: no early close with hydrocarbons), B (a TP trial just under the saturation), C (the liquid's vapour pressure as a share of P) at 1e-6 to 1e-3, and D (C bounded by the hydrocarbons in the balance's steam). A and B lost targets of a clean heavy cut; C lost water holding a trace of oil. D was built. The numbers are in the review.
- 2026-11-02: the final lane at `be052e63` against main's baseline at `3ffeb051`, and seed 118 replayed alone. Logs: `tools/ph-false-root/logs/final-lane-logs-2026-11-02.tar.gz` in the main checkout (local, never staged).
