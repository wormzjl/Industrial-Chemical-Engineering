# localization

Purpose: find missing translation keys and check translated language files against `en_us.json`.

Batch: `2026-11-03-localization`, merged 2026-11-03 as 0.19.3 (fr_fr, es_es, ja_jp added; zh_cn completed and reviewed).

## How to run

Run every script from the repository root with Node.js. They read `src/main/resources/assets/icheme/lang/`. Put work files in `tmp/<name>/`.

| Script | What it does |
|---|---|
| `scan.js <repo root>` | Static scan of `src/main/java`. It resolves each literal passed to a translation call (`tr`, `lang`, `t`, `text`, `translatable`, `KEY + "…"`) against the known key prefixes, and reports literals that match no `en_us` key. Expect noise from JSON field names passed to `get(…)`. A real gap is a call whose literal fits a GUI prefix. |
| `check.js <english.json> <translated.json>` | Key parity, format specifiers (`%s`, `%1$s`, `%%`), `\n` count, § codes, empty values, duplicate keys. Prints `OK n keys` or the problems. |
| `status-words.js <locale>…` | All-caps words quoted in `en_us` that a translation drops. The server builds status words (LIMITED, LOW LIMIT, DUTY INSUFFICIENT) in English, so hints must quote them in English. |
| `stale-translations.js <locale> [out.json]` | Missing keys, stale keys, and keys whose English changed after the translation was last written. The last check uses the git history of both files. |
| `split.js <en_us.json> <n> <work dir>` | Splits `en_us` into `n` chunk files `en-<i>.json`, for parallel translators. |
| `merge.js <locale> <work dir>` | Joins `<xx>-1.json`, `<xx>-2.json`, … into `lang/<locale>.json` in `en_us` key order. |
| `width.js "<text>"…` | A line's width in GUI pixels as `UnitTextTest.width` counts it (full-width glyphs 9). |
| `glossary.md` | Process-engineering terms for fr, es and ja. zh_cn takes its terms from its own existing lines. |

Workflow for a new or updated language: `split.js` → translate each chunk → `check.js` per chunk → `merge.js` → `check.js` on the whole file → `status-words.js`.

## Entry points and limits

- Gradle gates: `LanguageFilesTest` (quick suite: every lang file has en_us's keys, the arguments Minecraft fills, the same line breaks, no key twice); `UnitTextTest` (equipmentGates: every language's unit texts fit the constructor and vessel screens); `HeatDiagramTest.noLanguageKeyIsDefinedTwice` and the builders' `everyTextThe…HasItsEnglishLine` for en_us. Other screens' widths in other languages are not measured.
- `scan.js` cannot see keys built from enum names or server data. Check those families by hand: nozzle roles (`message.icheme.pipe_laying.role.*`), vessel kinds, side words, material names from `data/icheme/materials`.

## History

- 2026-11-03: written for the localization batch. The scan found the 56 nozzle-role names without keys. `stale-translations.js` found 79 outdated zh_cn lines.
