# 2026-11-03-localization

Purpose: find missing translation keys; add French, Spanish and Japanese; complete and review Simplified Chinese.

Status: In progress since 2026-11-03 on `claude/localization-keys-translations-c2d3c2`; not merged. Index: [../INDEX.md](../INDEX.md).

## Documents

- [LOCALIZATION_REVIEW.md](LOCALIZATION_REVIEW.md): method, findings, the zh_cn review and what is not verified.

## Code areas

- `src/main/resources/assets/icheme/lang/`: `en_us.json` (+56 role keys), new `fr_fr.json`, `es_es.json`, `ja_jp.json`, rewritten `zh_cn.json`. No Java change.
- Tool folder: [`tools/localization/`](../../tools/localization/README.md) (scan, check, status words, stale translations, split and merge, glossary).
- Code guide: `documentation/reference/code-guide/client-gui.md`, the lang paragraph.

## History

- 2026-11-03: key review; nozzle-role keys added to en_us; fr_fr, es_es and ja_jp translated (six Opus agents, two halves per language); zh_cn completed and reviewed (two Opus agents). All five files pass `check.js` at 1785 keys. `HeatDiagramTest` 5/5 passed (`cbc4bdd7f`).
- 2026-11-03: gates. New `LanguageFilesTest` (keys, arguments, line breaks per language); `UnitTextTest` measures every language. 55 too-wide fr, es and ja lines shortened. Both gates green; the lang gate proved on a planted broken file.

## Open items

- GUI fit outside the unit texts (calculator, fluid device, assembly screens) is not measured in other languages.
- Native-speaker review of fr, es, ja and zh.
- Merge preparation (version, code-guide snapshot) waits for the owner.
