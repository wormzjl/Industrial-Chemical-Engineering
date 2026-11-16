// Before and after of S4SharesProbe, side by side: per row the fields that changed, and whether a non-dense row moved.
import fs from 'fs';
const [a, b, out] = process.argv.slice(2);
const parse = f => fs.readFileSync(f, 'utf8').split(/\r?\n/).filter(l => l.includes(' | ')).map(l => {
  const parts = l.split(' | '); const row = { name: parts[0] };
  for (const p of parts.slice(1)) { const i = p.indexOf(' '); row[p.slice(0, i)] = p.slice(i + 1); }
  return row;
});
const A = parse(a), B = parse(b); const lines = []; let nonDenseMoved = 0;
const keys = ['vapour', 'head', 'condensed', 'wet', 'drawnCondensed', 'p_v', 'E', 'limit'];
const short = v => (v === undefined ? '-' : /^-?\d/.test(v) && v.length > 10 ? Number(v).toPrecision(6) : v);
for (let i = 0; i < A.length; i++) {
  const x = A[i], y = B[i]; if (x.name !== y.name) throw new Error('rows differ ' + x.name + ' ' + y.name);
  const moved = keys.filter(k => x[k] !== y[k]);
  if (moved.length && x.dense !== 'true') nonDenseMoved++;
  lines.push(`${x.name} | slot ${x.slot} | delta ${x.delta} | w ${y.w} | ` + keys.map(k => `${k} ${short(x[k])}${x[k] !== y[k] ? ' -> ' + short(y[k]) : ''}`).join(' | '));
}
lines.push(`non-dense rows that moved: ${nonDenseMoved}`);
fs.writeFileSync(out, lines.join('\n') + '\n');
console.log(lines.join('\n'));
