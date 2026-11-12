# Kernel cost probe

Purpose: time one `PengRobinsonKernel` call on the network package's list, for a sparse and a dense phase.
Batch: `documentation/2026-11-11-aqueous-solvents/` (KERNEL_COST_PLAN.md). A performance tool: run it alone.

## Run (from the worktree root)
1. `node tools/aqueous-solvents/kernel-cost/kernel-probe.js on`: adds a `PROBE_LARGE` hook to the kernel's constructor
   and copies `KernelCostProbeTest.java` into `src/test`.
2. `./gradlew test --tests "*KernelCostProbeTest" --offline --console=plain --max-workers 1 --no-configuration-cache --rerun --init-script tools/aqueous-solvents/kernel-cost/kprobe.init.gradle -i`
   and read the `KPROBE` lines (the last round). Add `-Dkprobe.dense=1` for every compound present, and
   `-Dkprobe.dry=1` for the list without water.
3. `node tools/aqueous-solvents/kernel-cost/kernel-probe.js off`: removes the hook and the test. Never commit them.

## Entry points
- `kernel-probe.js`: applies and removes the probe.
- `KernelCostProbeTest.java`: the list's sizes (compounds, pairs, cells) and the time per `evaluate` and
  `evaluateDerivatives` call, six rounds of 40,000 calls.
- `kprobe.init.gradle`: passes the two switches to the test JVM, one fork.
- `support-nan.init.gradle`: runs every test JVM with the kernel's NaN switch (`icheme.kernel.supportNaN`): NaN outside a
  workspace's support and an `AssertionError` for an amount outside it; the support audit of KERNEL_COST_PLAN.md Part A.
  Add `--init-script tools/aqueous-solvents/kernel-cost/support-nan.init.gradle --no-configuration-cache` to a filtered `test` run.

## History
- 2026-11-12: written for the kernel cost plan. HEAD `6084652e4`: water + nitrogen of 47, 3.8 / 17.4 µs; all 47,
  6.2 / 35.8 µs; all 46 without water, 4.8 / 24.6 µs.
- 2026-11-12: Part A (`d009783aa`): the sparse case carries its two compounds (`workspace.carry`). Water + nitrogen of 47,
  0.18 / 0.29 µs; all 47, 6.8 / 34.8 µs; all 46 without water, 5.7 / 28.2 µs (one run each, alone).
