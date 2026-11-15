# code-guide

Purpose: the generated parts of the code guide (`documentation/reference/code-guide/`). `map.mjs` prints files and lines per package of `src/main/java/com/wormzjl/icheme` (depth 3), the import matrix between the top-level layers and the references that break the layering, and writes the file counts of the guide README's module table. Merge preparation runs `--check`.

Batch: `documentation/2026-11-13-codebase-review/` (`DOCS_AND_COLLABORATION_PLAN.md` section 2.4, wave J package J2).

## How to run

From the repository root, Node 18 or later, no dependencies:

```text
node tools/code-guide/map.mjs            # package table, layer matrices, forbidden pairs with their files
node tools/code-guide/map.mjs --readme   # rewrite the module table's file counts in the guide README
node tools/code-guide/map.mjs --check    # exit 1 if --readme would change the README
```

The comment at the top of `map.mjs` states how the README's parenthesised counts map onto packages, and which row is left alone (`client/**`). Only numbers change; check the diff after `--readme`.

## Entry points

- `FORBIDDEN` in `map.mjs`: the top-level layering rules. The ones that hold are pinned in `src/test/java/com/wormzjl/icheme/LayeringTest.java` (quick suite); keep both in step. A rule never leaves the test.
- `stripJava`, `references`: the same comment and string stripping as `LayeringTest`; a reference is an `import` line or a code line naming another layer's type by its full name.

## History

- 2026-11-13: written (J2).
