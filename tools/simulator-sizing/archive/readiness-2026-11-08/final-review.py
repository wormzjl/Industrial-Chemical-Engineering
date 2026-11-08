from pathlib import Path
p=Path('documentation/2026-11-06-simulator-sizing/MERGE_REVIEW.md')
p.write_text('''# Merge preparation review

- Status: **Prepared for owner-approved 0.22.0; not merged.** Main/origin-main `f1db3a892` (0.21.0) remain ancestors. No push, tag or dev client launch.
- Final code snapshot: `@CODE@`; translations `429fe26b1`; branch history and scope: [README](README.md), [implementation](IMPLEMENTATION_REVIEW.md), [shared-line review](PIPE_FRAMEWORK_REVIEW.md).
- Version remains `gradle.properties` 0.22.0. The batch changelog stays under Unreleased until the actual merge.
- Four locales contain 2949 ordered keys with matching arguments/newlines. The last translation commit adds nine keys and revises two RPM hints. Eight historical Chinese capitalization/equivalent-wording notices were reviewed as correct; other locale stale checks are clear. LanguageFilesTest and UnitTextTest pass.
- Late work includes one line session/command/layout/admission/collision framework, a two-block DCS, preserved player GUI scale, main-door nozzles, RPM controls and stable ID/location hover. Fluid/conduit backends retain only medium-specific facts.
- Native service/probe integration `616fe7a1c` uses supported compact enclosures, consistent rotating DATA sides and opposite FE, equipment BODY paint, reinforced roots and orthogonal facet-following surface routes. Manual drum/sphere/tank mounts and factory heat/tower probes share constructor/render/world owners.
- Built-in inputs read accepted snapshots through C1, including cached column members. Bounded column probe state reloads without a solve; `fd5603249` retains total-feed mass basis including water. Unsupported V3 circulating pumparound streams and air properties stay unavailable; the column energy/duty contract is unchanged.
- Readiness fixes `@CODE@`: immutable crossed-cell positions in lead collision/preflight and instrument clearance; actual BlockItems for direct cable placement; local controller collision from the same authored part table as its model, including derived leads; deterministic geometry math. No new fact field, protocol or equipment nozzle datum.
- The 96-layout native-interface corpus records 14 cell/stamp changes, retaining equipment boxes, process-nozzle cells and shaft terminals. Normal/Low clients share physical layouts; native routing/profile sweeps and final golden checks pass.
- Owning formats: fluid facts 14/state 39; shape fields 10; control facts 3; equipment-21, fluid-19, machine-3, control-3, lines-1, pipeline-tool-5, simulator protocol 3. Earlier corpus images remain and no player-world test was used.

## Verification

- The canonical full lane ran **once**, at `891c8acf5`: 2865 checks, initially 17 failures. Affected follow-ups at `3515b1ff` resolved them; no second whole lane/campaign. Earlier protection/Low-poly/control follow-ups retain their recorded passes and evidence.
- Junction references: 33 lines, unchanged Newton counts; MIXED_GAS_COST 12 cases/299 solves. Chain-100 is exact in moles, T, phase fraction and flow.
- The 1000-seed/8-worker campaign retains the same held sets/classes as 0.21.0: cold random 992, bundled random 993, defaults 1000. No new C/F hold. Solves: cold 301070 → 299692; random 166713 → 166723; defaults 35322 → 35322. These are historical integration measurements, not a current speedup claim.
- Pre-readiness native integration passed the 48-profile service/routing sweep, Normal/Low geometry, full-width curved ribbons/UV continuity, constructor factory caps, layout/shape checks, accepted-input/control checks, 32 ColumnRun checks and 13 fluid corpus checks. Total-feed probe and saved-state reload follow-ups pass.
- Latest focused JUnit coverage: **206 distinct checks pass** across line/session, controller/input, GUI/constructor, geometry/layout, RPM, language and source guards. First run had five failures; immutable-position fixes and reviewed tower/actuator expectations resolved them through 36 selected checks. The new local-cabinet JSON/resource check passes; final source guard and package pass. No unchanged solver gate was replayed.
- Fresh dedicated-server coverage: **25 distinct methods pass** through filtered fresh-world runs: fluid/data/FE routes, rack and stub attachment, real collision/picking, obstruction/refusal checks, hand placement, power loss, socket teardown, first-cell instrument clearance, six-face RF and DCS identity/menu/halves. Initial four failures are retained; functional fixes and fixture corrections resolve all. No client was launched.
- Fixture corrections preserve assertions: shared missing-item wording, a real server menu owner (FakePlayer intentionally refuses menus), and explicit free target cells rather than template boundary barriers. Production collision/admission is not bypassed.
- Native drawings: **32 references / 62 rasterized pages**, with 18 actual-sprite Normal/Low light/dark proofs, source snapshot `@CODE@`; all routes resolved and final sheets inspected. Drawing source datums match the accepted native snapshot within numerical tolerance. Software renders do not prove live lighting/chunk delivery.
- Source reachability: 669 production / 489 test / 36 GameTest Java files; no orphan production class reported. Source archives and documented native drawing tools live under `tools/`; no investigation class is shipped. No tracked ignored file.
- Tool cleanup retains the earlier 177 sources and 102 late sources plus this readiness workflow in tracked archives, with source inventories. One-off sources moved out of tmp after byte verification; generated evidence and older jars remain local.
- Current jar: `build/libs/icheme-0.22.0.jar`, **@BYTES@ bytes**, SHA-256 `@SHA@`; metadata 0.22.0, native payload present, no test/probe classes. Local final evidence is main `research/2026-11-06-simulator-sizing/evidence/readiness-2026-11-08/`; compressed logs are main `tools/simulator-sizing/logs/simulator-sizing-readiness-logs-2026-11-08.tar.gz`.
- Historical cable stress results remain in prior reviews/evidence; later source-availability and interface changes were functionally checked, with no new CPU/RAM or live-FPS claim.
- Code guide chapters now describe shared ownership, physical interfaces, channel policies, GUI/defaults, formats and actual gate groups at `@CODE@`. A schema/geometry change must still add its corpus/update the native drawings in its own task.

## Remaining boundary

Owner's live acceptance and later merge instruction. Main is untouched; CHANGELOG receives the 0.22.0 heading only with the actual merge. The pre-existing AGENTS.md drawing-rule edit remains unstaged and outside these commits.
''',encoding='utf-8')
p=Path('documentation/2026-11-06-simulator-sizing/README.md');s=p.read_text(encoding='utf-8');mark='## Code areas and open items';s=s.replace(mark,'''- 2026-11-07–08: `417d70417`/`349099167`/`3d09e6188` consolidate line session, commands, admission and derived collision; `1edbc055f` builds the two-block DCS; `5b84b9ec6` preserves GUI scale; `c15dfcecc` adds ID/location hover, main-door nozzles and RPM controls. Initially compiled/rendered while owner tested; fresh-server/JUnit qualification completed in this readiness pass.
- 2026-11-08: `616fe7a1c` implements the reviewed native service/probe plans with matching equipment paint; `fd5603249` corrects total-feed probe mass basis. Native route/extreme/heading/Normal-Low and saved-probe gates pass. Current drawing workflow is tracked under `tools/equipment-drawings/`.
- 2026-11-08: translations `429fe26b1` bring four locales to 2949 keys. Readiness code `@CODE@` fixes crossed-cell cursor handling, direct cable items and cabinet collision. Combined 206 focused JUnit and 25 dedicated-server methods pass; native drawings, guide, archive inventories and the 0.22.0 package refreshed. See [MERGE_REVIEW](MERGE_REVIEW.md) and [PIPE_FRAMEWORK_REVIEW](PIPE_FRAMEWORK_REVIEW.md).

'''+mark)
s=s.replace('Merge preparation, final lane, filtered corrections and release jar are complete.','The once-run final lane and affected later follow-ups are recorded separately; current merge preparation and the release jar are complete.')
p.write_text(s,encoding='utf-8')
print('Prepared current batch review; final code/hash/render placeholders await artifact verification.')
