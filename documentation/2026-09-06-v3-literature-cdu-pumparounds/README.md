# 2026-09-06-v3-literature-cdu-pumparounds

Purpose: Literature (Ledezma 2019) CDU: TJL19 package, pumparounds as stage heat (WP1-WP4, Heat tab), wet condenser energy fix, continuation schedule, energy-shift predictor, 2026-09-08 handoff

Status: Implemented 2026-09-08. Index: [../INDEX.md](../INDEX.md).

## Documents

- [V3_LITERATURE_CDU_PA_PLAN.md](V3_LITERATURE_CDU_PA_PLAN.md)
- [V3_PUMPAROUND_HEAT_EXCHANGER_PLAN.md](V3_PUMPAROUND_HEAT_EXCHANGER_PLAN.md)
- [V3_HANDOFF_2026-09-08.md](V3_HANDOFF_2026-09-08.md)
- [V3_THESIS_PUMPAROUND_ARRANGEMENT.md](V3_THESIS_PUMPAROUND_ARRANGEMENT.md)
- [V3_RAMP_PREDICTOR_REVIEW.md](V3_RAMP_PREDICTOR_REVIEW.md)
- [sources/FULL_TEXT.pdf](../../research/2026-09-06-v3-literature-cdu-pumparounds/sources/FULL_TEXT.pdf)
- [screenshots/](screenshots/)

## Code areas

`science/column/v3/V3PumparoundSpec`, `V3Pumparounds`; `client/gui/screens/inventory/ColumnCalculatorV3Screen` (Heat page)

## History

- Implemented 2026-09-08 (`claude/v3-literature-cdu-handoff-3179dc` merged as `f4e600a`; review fixes `36dcb3e`, `ab14be5`, `e8d8937`)

## Notes

- `codex-branch/` holds copies from the suspended Codex worktree `run/codex-worktrees/v3-literature-cdu` (branch `codex/v3-literature-cdu`, abandoned 2026-09-06), including its later revision of `V3_LITERATURE_CDU_PA_PLAN.md`. Open: pumparound WP5 (DWSIM reference), a human-typed Heat row check, side strippers (the cause of the cold literature column top). `V3_CDU17_RETIREMENT_REVIEW.md` describes uncommitted 2026-09-08 edits that never landed; CDU17 was externalized in `e660097` (2026-09-15) and retired from production in the crude regrouping (2026-09-17).
