---
name: translation-commit
description: The IChemE translation commit - bring zh_cn, fr_fr, es_es, ja_jp (and any later language) up to date with en_us.json in one commit before a batch merges, with the offline checks in tools/localization/. Use when preparing a merge of a batch that changed en_us.json, or when the owner asks to translate or update the lang files.
---

# Translation commit (IChemE)

Moved from `AGENTS.md` ("Translations") on 2026-11-10. The rules are unchanged. `AGENTS.md` keeps the during-batch rule and the pointer; an agent with no skill loader reads this file as a plain file.

During a batch, only `src/main/resources/assets/icheme/lang/en_us.json` changes. This procedure runs once, before the batch merges.

## The commit

Bring every other lang file up to date with en_us in one translation commit (`zh_cn`, `fr_fr`, `es_es`, `ja_jp`, and any language added later):

- add each new key;
- rewrite the translation of each line whose English changed, also when only the wording changed;
- remove each key that en_us removes.

## Form

- Keep each file in en_us key order.
- Keep the arguments (`%s`, `%n$s`, `%%`) and the line breaks that en_us has.
- Keep the server's status words (LIMITED, LOW LIMIT, DUTY INSUFFICIENT) in English.
- Use the terms that each file already uses. `tools/localization/glossary.md` lists them for fr, es and ja.

## Checks

- Offline, with `tools/localization/`:
  - `stale-translations.js <locale>` before the translation commit, for translations whose English changed;
  - `check.js` for keys and arguments;
  - `width.js` for label widths.
- Gates: `LanguageFilesTest` (quick suite) and `UnitTextTest` (equipmentGates). They fail until the translation commit; do not run them before it. The final lane runs them.
