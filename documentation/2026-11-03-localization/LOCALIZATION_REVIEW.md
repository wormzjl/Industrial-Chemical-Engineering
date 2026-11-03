# Localization review (2026-11-03)

Owner request: "Review the localization keys, check for missing ones. Add french, spanish, and japanese localizations", then "review zh_cn as well".

## Missing keys in en_us

- Method: `tools/localization/scan.js` resolves every literal passed to a translation call against the key prefixes the screens use (`gui.icheme.`, `KEY` constants, `tr`/`lang` helpers). Registry ids were checked against `item.`/`block.` keys, and the 29 `translation_key` values in `data/icheme/materials` against `en_us`. The runtime-built families were checked by hand: side words, vessel titles, paint swatches, presets.
- Found: **56 nozzle-role names had no key.** `PipeLayingClient.portLabel` used `translatableWithFallback("message.icheme.pipe_laying.role." + role, humanize(role))`. English showed the humanized enum name, and no other language could translate it. Added `message.icheme.pipe_laying.role.<role>` for every `EquipmentShape.Role`, with text equal to the old fallback, so English does not change.
- Nothing else is missing. Every registered item and block has a name. Every scanner hit outside the role family was a JSON field name or a key that resolves under another prefix.
- Known gap, unchanged: hold reasons, data-range notices and status words (LIMITED, LOW LIMIT, DUTY INSUFFICIENT) are English text built by the server. `FluidDeviceScreen` parses some of them by regex, so they cannot move to lang keys without a protocol change.

## New languages: fr_fr, es_es, ja_jp

- 1785 keys each, the same key order as `en_us`. Six Opus translators worked on two halves per language from one glossary (`tools/localization/glossary.md`), and each half's agent was sent the other half's term choices.
- `check.js`: every file OK. Same keys, same format specifiers, `\n` counts and § codes. No positional reordering was needed.
- Hints that quote a server status word keep it in English, because the status line shows the English word. This was found in review and fixed in fr and es; ja and zh followed the rule from the start.
- Kept localized on purpose: CFB as LFC (fr, es), and VDU spelled out in fr. Spanish draw and steam icons are E%s and V%s.

## zh_cn review

| Finding | Count | Action |
|---|---|---|
| Keys in en_us with no Chinese | 749 | Translated |
| English changed after the Chinese was written (`stale-translations.js`, git history) | 79 | 70 updated; 9 kept (capitalization or wording only) |
| …of which had a wrong format-specifier set | 5 | Fixed with the update |
| Keys en_us no longer has | 124 | Removed. Each literal match in the code resolves to a different, existing key |
| Existing lines that were wrong | 16 of 957 | Fixed |

The 16 fixes were of four kinds:
- **Swapped arguments:** `message.icheme.equipment.built`, `built_column` and `built_unit` showed the build position where the cell count belongs.
- **Wrong terms:** 截止阀 (globe valve) for shutoff valve; 卧罐 for all drums.
- **Wrong meaning:** cyclone cut size; gasifier shell width.
- **Chinese unit words** (米, 立方米) instead of the symbols m and m³.

## Not verified

- **GUI fit.** `UnitTextTest` checks only English widths. A French, Spanish or Japanese label longer than its slot can still be clipped. The translators kept labels short, but no width gate or in-game check covered them.
- **Native review.** The translations were written by a model and are not reviewed by native speakers.

## Tests

- `HeatDiagramTest` (`noLanguageKeyIsDefinedTwice` reads en_us): 5/5 passed after the en_us change. No other test reads a lang file except for its own English lines, and no test reads fr, es, ja or zh.
- Code guide: `client-gui.md` lang paragraph updated; the snapshot line waits for the merge.
