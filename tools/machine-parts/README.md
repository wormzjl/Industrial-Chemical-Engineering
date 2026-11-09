# Machine part icons

Batch: `documentation/2026-11-03-rotating-machinery/` (W10b, pump health and repair, owner decision D34, 2026-11-04).

Purpose: draw the item icons of machine parts as 16 x 16 flat silhouettes in the mod logo's direction: flat navy and gold fields with a dark outline, no lighting, no gradients. Today one icon, the pump repair kit (`icheme:repair_kit`): a navy toolbox with a gold handle, a gold clasp and a double-ended gold wrench on its front.

## How to run

From the worktree root:

```bash
java -Djava.awt.headless=true tools/machine-parts/MachineParts.java src/main/resources/assets/icheme/textures/item [preview folder]
```

It writes `repair_kit.png` into the texture folder. With a preview folder it also writes `repair_kit-preview.png` there, 16 times enlarged on a checker background (keep previews in `tmp/`).

## Entry points

- `MachineParts.REPAIR_KIT`: the icon, one string per pixel row (`.` clear, `o` outline, `n` body, `l` lid, `g` gold, `d` dark gold).
- `MachineParts.draw`: rows to a 16 x 16 ARGB image; `MachineParts.enlarge`: the preview.

## History

- 2026-11-04: written for W10b; draws `repair_kit.png`.
