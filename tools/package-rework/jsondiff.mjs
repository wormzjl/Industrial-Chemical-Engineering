// Structural diff of two probe snapshots: prints each differing path with both values (truncated).
// Usage: node jsondiff.mjs <before.json> <after.json>
import { readFileSync } from 'node:fs';
const [a, b] = process.argv.slice(2).map((f) => JSON.parse(readFileSync(f, 'utf8')));
const out = [];
const short = (v) => { const s = JSON.stringify(v); return s === undefined ? 'undefined' : s.length > 160 ? s.slice(0, 160) + '…' : s; };
function walk(x, y, path) {
  if (JSON.stringify(x) === JSON.stringify(y)) return;
  if (x && y && typeof x === 'object' && typeof y === 'object' && Array.isArray(x) === Array.isArray(y)) {
    for (const k of new Set([...Object.keys(x), ...Object.keys(y)])) walk(x[k], y[k], path + '/' + k);
  } else out.push(path + '\n    before ' + short(x) + '\n    after  ' + short(y));
}
walk(a, b, '');
console.log(out.length + ' differing paths');
for (const line of out) console.log(line);
