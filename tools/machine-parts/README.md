# Machine part icons

Batch: `documentation/2026-11-03-rotating-machinery/` (W10b, pump health and repair, owner decision D34, 2026-11-04); the pollution meter icon from `documentation/2026-11-15-pollution-system/` (WP8).

Purpose: draw the item icons of machine parts and hand instruments as 16 x 16 flat silhouettes in the mod logo's direction: flat navy and gold fields with a dark outline, no lighting, no gradients. Two icons:

- the pump repair kit (`icheme:repair_kit`): a navy toolbox with a gold handle, a gold clasp and a double-ended gold wrench on its front;
- the handheld pollution meter (`icheme:pollution_meter`): a portable gas detector, a navy case with a gold sampling probe at its top left, a lit display in a dark bezel, a red alarm lamp and two gold keys, and a gold clip band at its foot.

## How to run

From the worktree root:

```bash
java -Djava.awt.headless=true tools/machine-parts/MachineParts.java src/main/resources/assets/icheme/textures/item [preview folder]
```

It writes `repair_kit.png` and `pollution_meter.png` into the texture folder. With a preview folder it also writes `<name>-preview.png` there, 16 times enlarged on a checker background (keep previews in `tmp/`). A re-run writes the same bytes for an unchanged icon.

## Entry points

- `MachineParts.REPAIR_KIT`, `MachineParts.POLLUTION_METER`: the icons, one string per pixel row (`.` clear, `o` outline, `n` body, `l` lid or lighter case edge, `g` gold, `d` dark gold, `s` display, `r` alarm lamp).
- `MachineParts.draw`: rows to a 16 x 16 ARGB image; `MachineParts.enlarge`: the preview.

## History

- 2026-11-04: written for W10b; draws `repair_kit.png`.
- 2026-11-15 (pollution system WP8): draws `pollution_meter.png` as well; `repair_kit.png` re-written byte for byte the same.
