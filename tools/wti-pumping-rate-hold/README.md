# WTI pumping-rate hold trace

Batch `2026-10-24-wti-pumping-rate-hold`. Initial source **beb5c2fc**. All code here is local ignored diagnostic tooling; it attaches or removes no tracked product source, so no tooling re-attachment/removing commit is needed. Product changes and their qualification are recorded separately in the batch reviews.

`inspect_snapshot.py` reads only the frozen format-17/unit-10 archive, validates unit SHA-256 and decodes active registrations plus island graphs/accepted endpoint metadata. It shares the older inspector's generic binary/NBT/graph readers; its topology cutoff reader and format gates are current-only. It does not restore a world or apply pending events. `snapshot.json` is the numeric copy of island 94 at committed tick 21577.

`RateHoldAudit.java` reconstructs that graph with the same bundled network package, 100 m/s velocity cap and 1e-6 trace setting. It verifies the WTI preset, substitutes only the pump rate, and runs a bounded rate/step/interval diagnostic with existing equation/conservation gates. It asserts this case has no solids or filters. The operation limit is two million checkpoint polls, not a timing measurement or game budget.

- baseline-src/: exact PassiveStepSolver, PassiveIntervalSolver and SparseNewton from beb5c2fc; get any other library sources via that commit.
- trace-src/: copies with logging of already computed residuals, modes and failed-point states. Extra read-only diagnostics do not evaluate an additional residual/property. No instrumentation enters the production jar.
- prototype-src/: initial broader phase-validity-query prototype.
- entry-prototype-src/: selected narrow rate-entry cache invalidation. proposal.patch is its diff against beb5c2fc. It preserves within-pass reuse and all physical laws.
- summary.json and logs/wti-rate-trace-logs-2026-10-24.tar.gz: provenance, proposal, numeric graph and reproduction/trace/selected-prototype logs. Do not interpret one-case results as campaign qualification.
- snapshot.json, the frozen world the audit reads, holds the WTI (ExxonMobil) composition. Since O5 of batch 2026-11-04-open-thermo-data (2026-11-05) it is in the main checkout's git-ignored `research/2026-11-04-open-thermo-data/xom-copies/tools-wti-pumping-rate-hold/` (`$taskCase` below).

Use JDK 21.0.11 and an owned checkout built at beb5c2fc (`gradlew.bat jar --max-workers=4`). Cached libraries: Gson 2.10.1 and EJML 0.44.0. The prior tool's classpath.txt contains these pinned paths; product classes/resources must precede its older helper-class directory. No dev client/world is needed.

```powershell
$taskTool = 'D:\Minecraft\Modding\1.21\CreateChemE\tools\wti-pumping-rate-hold'
$taskCase = 'D:\Minecraft\Modding\1.21\CreateChemE\research\2026-11-04-open-thermo-data\xom-copies\tools-wti-pumping-rate-hold\snapshot.json'
$taskJava = 'C:\Program Files\Java\jdk-21.0.11\bin'
$taskDeps = (Get-Content 'D:\Minecraft\Modding\1.21\CreateChemE\tools\fluid-world-inspector\classpath.txt' -Raw).Trim()
$taskClasspath = "$taskTool/classes;build/classes/java/main;build/resources/main;$taskDeps"
& "$taskJava\javac.exe" -cp $taskClasspath -d "$taskTool/classes" "$taskTool/RateHoldAudit.java"
& "$taskJava\java.exe" -Xmx1g -cp $taskClasspath com.wormzjl.icheme.science.fluid.network.RateHoldAudit $taskCase 1.5 3.2
& "$taskJava\java.exe" -Xmx1g -cp $taskClasspath com.wormzjl.icheme.science.fluid.network.RateHoldAudit $taskCase 2 3.2
& "$taskJava\java.exe" -Xmx1g '-Dwti.repeatPort=true' -cp $taskClasspath com.wormzjl.icheme.science.fluid.network.RateHoldAudit $taskCase 2 3.2
& "$taskJava\javac.exe" -cp $taskClasspath -d "$taskTool/entry-prototype-classes" "$taskTool/entry-prototype-src/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java"
& "$taskJava\java.exe" -Xmx1g -cp "$taskTool/entry-prototype-classes;$taskClasspath" com.wormzjl.icheme.science.fluid.network.RateHoldAudit $taskCase 2 3.2 94 10
```

Flags wti.singleStep and wti.rateOnly select a fresh direct step/rate solve; wti.portRate selects the port graph. Arguments after duration are island id (default 94) and additional interval count (default 0). Compile all trace-src Java files to trace-classes and put that directory first with `-Dfluid.audit.trace=true` for the logging lane. Never put prototype/trace classes on a production or broad-test classpath.

## Production fix qualification (2026-10-24)

Production source `9fa53354` / `c49f6654` / `98f3dce3`, integrated `cb89f23a` from owned `codex/wti-pumping-rate-hold` into `claude/floating-roof-tanks-81d68b` based on `a591404f`. Pump errors are pure scheduled presentation; the final solver change carries a matching cached rate point's phase basis with its flows/heads only when the existing stage carries a mover head/suction-limit mode. Passive-loop seeding is unchanged. The initial broad reset and narrower rate-only reset are rejected history (`9fa53354`, `07b105ae`); each added cold-183 E5 in the matched quick comparison. The complete-point approach was narrowed after its one full campaign exposed passive cold 555. No physical simplification, new gate switch, source-set attachment or ignored forced staging. Final inlet reasons retain their existing ERROR prefix once; the existing GameTest expectation was updated and compiled.

`validate_fix.py compile` builds the existing cold/bundled hold-fuzz harnesses, M3Run and two baseline solver overrides from `a591404f`. `quick` compares the initial form, `refined` compares the second, and `complete` compares the chosen form: 200 seeds, all three distributions, four workers, serially. Preserve the reports rather than repeating them. `quick-summary` compares the chosen form to the baseline; `full` runs the selected 1000-seed/all-distribution/eight-worker campaign, and `summary` includes it. `baseline-gates` only rechecks the five known unrelated failures using baseline solver overrides.

`trace_cold183.py` pins the rejected `9fa53354` source, adds logging of cached flags only, uses the existing seed-183 placement sequence and stops at the first hold. It localizes the affected passive LNG island to eight nodes/nine connections, without a full seed cost replay. It does not evaluate an extra residual or enter the product jar. `cold183-trace/trace.log` is the retained trace.

Run `negative_control.py` with no client or campaign active. It disables only selected phase-basis carry, runs the one repeated-rate test, expects failure and restores exact source bytes in a finally block; evidence is in `fix-validation/complete-point-negative/`. Then run the nine focused tests and jar normally. `final_lane.py` ran the repository's test/slowGates, exact chain-100, fresh fluid GameTests and complete-point campaign once, serially, on `3cb3c0ae` (source `c49f6654`). The fresh GameTest stage was bounded at 180 seconds because current fixtures omit enabling newly default-Off generators; timeout is incomplete, never a pass. The full campaign exposed passive cold 555; `trace_cold555.py` is the second/final first-hold trace, pinned to `c49f6654`, without per-seed cost measurement. Final scope `98f3dce3` leaves passive seeding alone. Required final test/slowGates/jar checks and `scope_followup.py` qualify it incrementally on matched seeds 1-200 and 401-600, all three distributions; no second full campaign/reference lane. Reports distinguish these source versions.

After qualification, `integrate.py capture` records the requested target's local-file hashes, and `integrate.py merge` validates the target is unchanged, rejects overlapping paths, fast-forwards and verifies every local file again. It does not stash/stage/commit the local files. `archive_fix.py` collects all printed references, validates the 33 junction lines, records source hashes and compresses the lane/cohort/trace/merge evidence. Final results/counters are in `fix-final-lane/`, `fix-validation-campaign/`, `scope-followup/` and the batch fix review. Transient compiled classes are regenerable tool output.

`sort_remaining_holds.py` is a read-only inventory of final-source reports. It preserves existing first-hold classes, distinguishes recovered cases from those still held at the run's end, and calls the existing classifier on explicitly labelled latest-hold extracts. It separates the earlier full campaign's 17 cases outside final coverage and records that classifier B can be triggered by a non-finite matrix in a rejection map. No solver/client/world action is performed. Output: `remaining-holds/classification-2026-10-24.json`; report: `documentation/2026-10-24-wti-pumping-rate-hold/REMAINING_HOLDS_REVIEW.md`; separate compressed audit evidence in `logs/`.

Its `physics-observations-2026-10-24.json` output extracts rounded generator conditions, pump/valve settings and whole-world end crystal totals for the eight still-held cases. `REMAINING_HOLDS_PHYSICS_REVIEW.md` groups their physical scenarios and marks causal uncertainty: solid-bearing cryogenic cases, cryogenic mixing/pressure reduction, crude/water vessel filling, and one deadline with an unassigned physical trigger. No held-state flash, flow, composition or crystal population is invented from the layout.

## Cold 430 final-code trace (2026-10-24)

`cold430-trace/` pins `cb89f23a` and reproduces the existing eight-block `HoldClassWorldsTest.aFailedIterateRaisesNoPhaseHold` world with temporary console instrumentation. The focused test passes its no-capability-hold assertion and records E6 at tick 406; it does not prove keep-up. Exact re-attachment, execution and restoration commands are in that folder's README. The instrumentation was removed, exact source restoration checked, and clean production classes rebuilt; no product-source diff is retained.

`COLD430_TRACE_REVIEW.md` gives the physical states, residual/merit trajectory and the initial fallback proposal restricted to regularized flat rows with component-level hull limiting and a merit guard. Trace evidence is preserved in `cold430-trace/` and `logs/cold430-trace-logs-2026-10-24.tar.gz`.

`cold430-validation/` contains the follow-up's three candidate patches, focused/negative-control tests, matched campaigns and isolated budget replays. Form 1 cleared the local gate but diverted cold 555 to E5; form 2 avoided that recurrence but retained cold 431's 7 -> 11 publication increase in the complete isolated replay; form 3 failed cold 430 at tick 406. All three are rejected, both Java files were restored and the baseline compiles. `COLD430_FIX_REVIEW.md` gives the evidence and limits; `cold430-validation/README.md` gives reproduction and provenance. Validation logs are in `logs/cold430-validation-logs-2026-10-24.tar.gz`. No product fix or strengthened gate was retained from those forms.

## Cold 431 matched-state and transition trace (2026-10-24)

`cold431-trace/` pins `cb89f23a` and the preserved form-2 solver hash in copied sources. Its natural prefix shows that baseline and form 2 reach different physical states. A controlled checkpoint A/B gives both the same restored input and both return `FULL`; form 2's own trajectory is therefore the regression. On that trajectory its targeted bisection never fires, while 41 transition refinements include 13 exactly-zero-flow deposition trials on edge 0.

`COLD431_TRACE_REVIEW.md` separates that upstream divergence from the independent `SolidEventIntegrator.failed` fallback. `COLD431_ZERO_FLOW_FIX_REVIEW.md` records the approved follow-up: one exact `flow == 0` guard, its signed-zero/nonzero controls, focused regressions and guarded cold-431 traces. Both guarded arms return island 42 `FULL` with no zero-velocity transitions, but their natural states are not matched and form 2 remains rejected. The quick suite has 1365 passes and four failures in unchanged baseline files, itemized in the review; no slow gates or campaign were run.

`cold431-trace/README.md` records exact reproduction and evidence limits. The default compilation still requires clean tracked Java; explicit `--zero-fix` admits only the guard and writes a separate manifest. Compressed logs are in `logs/cold431-trace-logs-2026-10-24.tar.gz` and `logs/cold431-zero-flow-logs-2026-10-24.tar.gz`. No fourth balance form or deadline change is retained; code-guide and merge qualification remain pending.

## Cold 430 Jacobian-sign repair (2026-10-24)

`cold430-jacobian-trace/` traces the surviving mint non-convergence to a positive finite-difference diagonal on a cap-flat junction. The retained `final-sign-floor.patch` contains only the non-positive-diagonal solver repair and strengthened cold-430 keep-up gate, excluding the paused cold-431 files. The focused gate and disabled-rule control behave as intended; matched seeds 1-200 have no new holds, changed first-hold classes or increased publications in any distribution. One selected 1000-seed campaign and isolated budget/diagnostic checks are preserved, with historical deltas kept as watchlist evidence.

`COLD430_JACOBIAN_TRACE_REVIEW.md` records the physical invariant, evidence and remaining owner-validation assumption about PH/table and mover states. The quick suite has four established unrelated failures. Slow gates additionally expose cold 743 from the shared solid guard: candidate solver with the original solid class passes, while baseline solver with that guard fails identically. The combined branch is therefore not merge-ready. Logs are in `logs/cold430-jacobian-trace-logs-2026-10-24.tar.gz`; no commit, merge, code-guide or version/changelog action was taken.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Strict format-17 reader, replay/traces, qualification, preserved integration, read-only numerical/physical hold inventories, cold-430 balance and cap-flat Jacobian validation, and cold-431 transition trace/signed-zero guard validation

Batch: 2026-10-24-wti-pumping-rate-hold

Status: Concluded 2026-10-26 (batch in 0.12.0). Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `inspect_snapshot.py`, `RateHoldAudit.java`, `validate_fix.py`, `trace_cold183.py`, `trace_cold555.py`, `scope_followup.py`, `integrate.py`, `archive_fix.py`, `sort_remaining_holds.py`, `remaining-holds/`, `cold430-trace/`, `cold430-validation/`, `cold430-jacobian-trace/`, `cold431-trace/`, `logs/`

### History

- In progress since 2026-10-24 (integrated `cb89f23a`
- production-fix 1200 matched cases without added holds
- eight still held
- earlier cold-430 follow-up rejected all three balance forms: form 1 added cold-555 E5, form 2 retained isolated cold-431 7 -> 11 publications, form 3 failed cold 430 at tick 406
- separate non-positive-diagonal candidate retained: focused gate/control and matched 1-200 comparisons pass, one selected 1000-seed/all-distribution campaign complete with clean counts 975/993/1000 and cold 430 at 9 -> 0 preserved-baseline holds
- quick suite has four established unrelated failures
- cold-743 slow-gate failure isolated to the paused cold-431 guard
- cold-431 signed-zero guard prototyped after tracing 13 zero-flow refinements, with its focused tests and guarded target passing
- form 2 rejected
- owner monotonicity approval, combined-branch resolution and clean-branch merge qualification pending
- client retained)
- 2026-11-05: `snapshot.json` moved to the main checkout's research/ (ExxonMobil WTI composition; O5 of batch 2026-11-04-open-thermo-data)

### Notes

- status corrected 2026-10-26: the batch reached `main`; the folder is kept as its evidence.
