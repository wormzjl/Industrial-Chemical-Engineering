# Merge preparation review

- Status: **Prepared for owner-approved 0.22.0; not merged.** No dev client was launched.
- Base: main `f1db3a892` (0.21.0), direct ancestor; initial task head `c45cea075`, 56 commits ahead. Main ref/source remain unchanged.
- Scope/assumptions: [IMPLEMENTATION_REVIEW.md](IMPLEMENTATION_REVIEW.md); task history and code areas: [README.md](README.md).
- Version: `gradle.properties` 0.22.0; one batch entry stays under CHANGELOG Unreleased. Translation commit `8a768d9d`: four locales, 109 new/15 revised English keys each, 2923 matching ordered keys/arguments at that commit; follow-up `f13019d1` adds the ten deconstruction keys to all locales (2933 total). Eight historical Chinese capitalization/equivalent-wording audit notices remain and read correctly. LanguageFilesTest and UnitTextTest passed in the lane.
- Gate/tooling preparation `db322f04f`: six conduit/RF GameTests join the standard namespace; two duplicate pipe wrappers/templates removed; column gate declares its new exchanger dependencies. No suite is widened to all production code.
- Final code corrections `3515b1ff`: deterministic geometry math, visible short-reducer stencils, legacy/default/protocol expectations, geometry-aware clearance probes, owner-approved tank access expectations and powered-inline/sensor-metadata GameTest fixtures. No connection face moved. Old corpus Saved payloads are unchanged; only missing appended defaults are normalised in textual expectations.
- Equipment protection/deconstruction `efa22cfe`: Creative-only direct breaking, common chunk-write protection and constructor physical-target removal, with per-cell permission/stale-target checks. Protocol `equipment-21`; no fact/shape schema or geometry changes. [Review](EQUIPMENT_PROTECTION_REVIEW.md).
- Reachability: main 583/583 and branch 641/641 unique production class names reachable after `efa22cfe`. No test-only production class detached. Investigation tools: `tools/simulator-sizing/`; current workstation authoring: `tools/pump-sprites/`. The filtered GameTest registrar invokes existing bodies with their original annotations; it is enabled only by its documented init script, never shipped.
- Artefacts: 596 figures, 10 tables and 203 historical logs retained locally; final-lane/follow-up output retained separately. No unmerged scratch output deleted. Paths are in the tools README and canonical research index.

## Verification

- The canonical `tools/merge-lane/lane.sh head` ran **once**, at `891c8acf5`, alone. `test slowGates` covered **2865 tests**, initially 17 failures. They were resolved using only affected filtered follow-ups; no second full lane. Column and hold gates passed unchanged. All initially failing gate checks now pass.
- Follow-ups: 86 quick/118 equipment checks in the first correction run; 11 quick/65 equipment checks in the second; final seven nozzle checks pass; final deterministic-math source check and normal source compilation/package pass. Superseded failures and commands are retained, not hidden.
- Junction reference: 33 lines, every Newton solve count unchanged; MIXED_GAS_COST 12 cases/299 solves. Allocation/time fields vary and are not treated as exact references.
- chain-100 exact: zero deviation in moles, temperature, phase fraction and flow.
- Standard fluid GameTests: 51 total, initial 48 pass/3 fail. Focused nine-method run passed seven; the remaining powered-inline-pump fixture and cleanup dependency then passed 2/2 in a fresh run. Combined coverage resolves all 51. The six conduit/RF checks and sensor-load check passed; they were not replayed after the final fixture-only edit.
- Equipment follow-up: seven new protection/deconstruction GameTests plus the 51 existing affected paths resolve in filtered runs (53-method regression initially 50 pass, compatibility-fixture correction/cleanup then 3/3). Constructor/codec checks 23 pass, wrapping follow-up 10/10, final language/source gates 5/5; no second full lane or campaign. Light/dark constructor renders inspected.
- Campaign: 1000 seeds/distribution, 8 workers. Cold random **992/1000**, bundled random **993/1000**, defaults **1000/1000**, identical held seed sets/classes to the archived 0.21.0 lane. No new C/F hold requires replay. Existing held worlds are not advertised as fixed.
- Campaign Newton solves, baseline → branch: cold 301070 → 299692 (−0.458%); bundled random 166713 → 166723 (+0.006%); defaults 35322 → 35322. Deadline-censored totals vary; this is no claim of a solver speedup. Baseline archive: main `tools/rotating-machinery/logs/rotating-machinery-logs-2026-11-05.tar.gz`.
- Historical cable stress (before later feed-availability changes): representative ~1 million wires, 100 powered passes 38 ms versus 776 ms earlier loop; no-source 16 ms versus 740 ms; mixed cut/rebuild 1.67 s; sampled heap 837 MB. Isolated harness results, not live-world tick guarantees. Pathological colliding UUID results are excluded from that comparison.
- Code guide: affected chapters updated to code `3515b1ff`, with equipment/client/verification/index follow-ups to `efa22cfe`, with a new wired-control-power chapter. Four final pump/compressor software views confirm the reducer stencils; rendering remains software/gate-qualified and owner performs live acceptance.
- Release jar: `build/libs/icheme-0.22.0.jar`; 8,551,991 bytes, SHA-256 `1af24443a67e460961caa5278e4957826d39d05ab672b0ca3488a6874dd8b32f`. Metadata is 0.22.0; no test/probe classes are packaged.

## Remaining boundary

Owner’s live acceptance and later merge instruction. No merge, push, release tag or dev-client launch was performed. The 0.22.0 changelog heading must be created only when the owner authorises the actual merge.
