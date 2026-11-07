# Merge preparation review

- Status: preparation in progress for owner-approved **0.22.0**; **not merged**.
- Base: main `f1db3a892` (0.21.0), direct ancestor of the task branch. Initial task head `c45cea075`, 56 commits ahead, no base-side commits.
- Scope and assumptions: [IMPLEMENTATION_REVIEW.md](IMPLEMENTATION_REVIEW.md); full history in the batch README.
- Translation commit `8a768d9d`: four locales, 109 new/15 revised English keys each, 2923 matching keys and arguments. Eight pre-existing Chinese capitalization/equivalent-wording audit notices remain; their translated meanings are unchanged and correct.
- Gate/tooling preparation `db322f04f`: six conduit/RF GameTests join the standard namespace; two duplicate pipe wrappers/templates removed; column gate sees its new exchanger dependencies.
- Reachability: main 583/583 and branch 638/638 production classes reachable; no test-only production class detached. Retained tools: `tools/simulator-sizing/`, updated `tools/pump-sprites/`.
- Artefacts: 596 figures, 10 tables and 203 logs retained locally; no unmerged scratch output deleted. Research/log paths are in the tools README.
- Historical stress probe (before later feed-availability changes): representative ~1 million wires, 100 powered passes 38 ms versus 776 ms earlier loop, no-source passes 16 ms versus 740 ms; mixed cut/rebuild 1.67 s; sampled heap 837 MB. These are isolated harness results, not live-world tick guarantees. Deliberately colliding UUID results are excluded from that comparison.
- Final lane: pending. It runs once using `tools/merge-lane/lane.sh head`, then only affected filtered corrections if needed.
- Pending: final gate/campaign results, release jar, owner’s live acceptance and later merge instruction. No dev-client launch.
