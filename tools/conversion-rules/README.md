# conversion-rules

Purpose: write the bundled fluid conversion rules (`src/main/resources/data/icheme/icheme/fluid_conversion/<mod>/<fluid>.json`) from one table, so the product windows stay the same across mods. The JSON files are the source of record; the generator only keeps them consistent when a window changes.

Batch `2026-11-12-fluid-conversion` ([README](../../documentation/2026-11-12-fluid-conversion/README.md), [BUNDLED_RULES.md](../../documentation/2026-11-12-fluid-conversion/BUNDLED_RULES.md)). Status: in use since 2026-11-12.

## How to run

From the worktree root (Node.js 18 or later):

```bash
node tools/conversion-rules/rules.js
```

It deletes the folder `src/main/resources/data/icheme/icheme/fluid_conversion/` and writes every rule again (revision `1` unless the table says otherwise). Then check `git diff`, bump the `revision` of each rule whose values changed (AGENTS.md, declared revisions), and run `ConversionRuleTest`.

## Entry points

- `rules.js`:
  - `products`: the product windows (mass basis, priorities, T and P);
  - `pure`: the pure-gas windows (mole basis);
  - `rules`: one entry per file, by `<mod>/<fluid>`.

## History

- 2026-11-12: created in W6 (26 rules). Moved from the worktree's `tmp/` into `tools/` in the merge preparation.
