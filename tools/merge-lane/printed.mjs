// printed.mjs <results folder> <out file>: every test's system-out lines, class by class in file-name order, the class
// name (package dropped) in front of each line. The lane's printed lines (lane.sh; compare.mjs reads them): the same
// lines as tools/floating-roof-tanks/final-lane/printed.sh, without a process per file. Prints the line count.
import fs from 'fs';
import path from 'path';
const [dir, out] = process.argv.slice(2);
const lines = [];
for (const f of fs.readdirSync(dir).filter(f => /^TEST-.*\.xml$/.test(f)).sort()) {
  const label = f.slice(5, -4).replace(/^.*\./, '');
  for (const m of fs.readFileSync(path.join(dir, f), 'utf8').matchAll(/<system-out><!\[CDATA\[([\s\S]*?)\]\]><\/system-out>/g))
    for (const l of m[1].split(/\r?\n/)) if (l.length) lines.push(`${label} ${l}`);
}
fs.writeFileSync(out, lines.map(l => l + '\n').join(''));
console.log(lines.length);
