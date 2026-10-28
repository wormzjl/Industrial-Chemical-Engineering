// Compares per-design-base.txt and per-design-after.txt: for designs built both ways, each side nozzle's height move
// against the sump's growth, and side changes. Usage: node compare.js <base> <after>
const fs = require('fs');
const [base, after] = process.argv.slice(2).map(f => fs.readFileSync(f, 'utf8').trim().split(/\r?\n/));
const sump = d => { const od = Math.min(.8, Math.max(.1, Math.round(.27 * d * 20) / 20)); return Math.max(d / 2, Math.ceil(2.55 + od - 1e-9)); };
const old = d => Math.max(2, d / 2);
const moves = {}, sides = {}, examples = [];
let designs = 0, nozzles = 0;
for (let i = 0; i < base.length; i++) {
  if (/REFUSED/.test(base[i]) || /REFUSED/.test(after[i])) continue;
  designs++;
  const d = parseFloat(base[i].slice(2, 6));
  const grow = sump(d) - old(d);
  const parse = l => l.slice(33).trim().split(' ').map(t => { const [rs, y] = t.split('@'); const [r, s] = rs.split('/'); return {r, s, y: parseFloat(y)}; });
  const a = parse(base[i]), b = parse(after[i]);
  if (a.length !== b.length) { examples.push('count ' + base[i] + ' -> ' + after[i]); continue; }
  for (let k = 0; k < a.length; k++) {
    nozzles++;
    const off = Math.round((b[k].y - a[k].y - grow) * 10) / 10;
    const key = a[k].r + ' ' + off;
    moves[key] = (moves[key] || 0) + 1;
    if (a[k].s !== b[k].s) { sides[a[k].r] = (sides[a[k].r] || 0) + 1; if (examples.length < 12) examples.push(base[i].slice(0, 31) + a[k].r + ' side ' + a[k].s + '->' + b[k].s + ' y ' + a[k].y + '->' + b[k].y); }
  }
}
console.log('designs ' + designs + ', side nozzles ' + nozzles);
console.log('move minus the sump growth, by role:'); Object.keys(moves).sort().forEach(k => console.log('  ' + k + ': ' + moves[k]));
console.log('side changes: ' + JSON.stringify(sides)); examples.forEach(e => console.log('  ' + e));
