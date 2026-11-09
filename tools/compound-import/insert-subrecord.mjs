// Writes a sub-record of the import output into the bundled material records, keeping each file's line ends and its
// two-space indentation, and changing nothing else in the file (not the record's own revision: a sub-record has its own).
//
// Usage: node insert-subrecord.mjs <key> <dir of wrappers> <materials dir> [--replace]
//
// <dir of wrappers> holds <file>.json wrappers as import.mjs writes them (freezing/, thermochemistry/, flammability/) and
// tools/crude-grades/thermochemistry.mjs: {id, path, component, <key>: {...}}, where path is the record's file relative to
// <materials dir> (a dataset or the water model) and component is checked when the record has one. An older wrapper
// {properties, component} names properties/<wrapper file name>. A new sub-record goes before the top-level "viscosity"
// key, so sub-records inserted one after another keep their order. A record that already has the key is refused unless
// --replace is given, which rewrites that block in place. Every write is checked: the sub-record reads back equal, every
// other key is unchanged, and the file keeps one kind of line end.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const args = process.argv.slice(2);
const replace = args.includes('--replace');
const [key, wrappers, materialsDir] = args.filter((a) => a !== '--replace');
if (!key || !wrappers || !materialsDir) {
  console.error('usage: node insert-subrecord.mjs <key> <dir of wrappers> <materials dir> [--replace]');
  process.exit(2);
}
let count = 0;
for (const name of readdirSync(wrappers).filter((n) => n.endsWith('.json')).sort()) {
  const wrapper = JSON.parse(readFileSync(join(wrappers, name), 'utf8'));
  const path = join(materialsDir, wrapper.path ?? 'properties/' + name), text = readFileSync(path, 'utf8');
  const record = JSON.parse(text);
  if (record.id !== (wrapper.id ?? wrapper.properties)) throw new Error(name + ': id mismatch');
  if (wrapper.component !== undefined && record.component !== wrapper.component) throw new Error(name + ': component mismatch');
  if (!wrapper[key]) throw new Error(name + ': the wrapper has no ' + key);
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  if (eol === '\r\n' ? /[^\r]\n/.test(text) : text.includes('\r')) throw new Error(name + ': mixed line ends');
  const block = JSON.stringify(wrapper[key], null, 2).split('\n').map((l, i) => i === 0 ? l : '  ' + l).join(eol);
  let out;
  if (key in record) {
    if (!replace) throw new Error(name + ': already has ' + key + ' (use --replace)');
    // The block ends at the first line that closes at two-space indentation (nested objects close deeper).
    const start = text.indexOf(eol + '  "' + key + '": {');
    const end = text.indexOf(eol + '  }', start + 1);
    if (start < 0 || end < 0 || text.indexOf(eol + '  "' + key + '": {', start + 1) >= 0) throw new Error(name + ': cannot find one ' + key + ' block');
    out = text.slice(0, start) + eol + '  "' + key + '": ' + block + text.slice(end + eol.length + 3);
  } else {
    const marker = eol + '  "viscosity": {';
    if (text.split(marker).length !== 2) throw new Error(name + ': expected one top-level viscosity line');
    out = text.replace(marker, eol + '  "' + key + '": ' + block + ',' + marker);
  }
  const check = JSON.parse(out);
  if (JSON.stringify(check[key]) !== JSON.stringify(wrapper[key])) throw new Error(name + ': round trip');
  const others = (o) => JSON.stringify(Object.fromEntries(Object.entries(o).filter(([k]) => k !== key)));
  if (others(check) !== others(record)) throw new Error(name + ': other content moved');
  if (eol === '\r\n' ? /[^\r]\n/.test(out) : out.includes('\r')) throw new Error(name + ': line ends changed');
  if (out !== text) { writeFileSync(path, out); count++; }
}
console.log((replace ? 'written ' : 'inserted ') + count + ' ' + key + ' sub-records');
