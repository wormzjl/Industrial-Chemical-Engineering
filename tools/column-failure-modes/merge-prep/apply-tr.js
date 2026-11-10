// Applies tmp/col-merge/tr-<locale>.json to the lang files: adds the new keys, rewrites the changed ones, drops keys en_us
// no longer has, and writes each file in en_us key order (the format of tools/localization/merge.js).
// Usage: node tmp/col-merge/apply-tr.js   (from the worktree root).
const fs = require('fs');
const dir = 'src/main/resources/assets/icheme/lang/';
const en = JSON.parse(fs.readFileSync(dir + 'en_us.json', 'utf8'));
for (const l of ['zh_cn', 'fr_fr', 'es_es', 'ja_jp']) {
  const old = JSON.parse(fs.readFileSync(dir + l + '.json', 'utf8'));
  const add = JSON.parse(fs.readFileSync('tmp/col-merge/tr-' + l + '.json', 'utf8'));
  for (const k in add) if (!(k in en)) throw new Error(l + ': not an en_us key: ' + k);
  const all = Object.assign({}, old, add);
  const out = {};
  const missing = [];
  for (const k in en) if (k in all) out[k] = all[k]; else missing.push(k);
  if (missing.length) throw new Error(l + ': missing ' + missing.join(', '));
  fs.writeFileSync(dir + l + '.json', JSON.stringify(out, null, 2) + '\n');
  console.log(l, Object.keys(out).length, 'of', Object.keys(en).length, 'dropped', Object.keys(old).filter(k => !(k in en)));
}
