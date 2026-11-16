// build/engine-digest.txt (record mode, merged code) against the reference src/test/resources/thermo/engine-digest.txt.
// Prints the request counts, the differing lines, and whether every difference is the one grade token
// EXTRAPOLATED -> ESTIMATED_DECLARED_ERROR with every other byte the same.
const fs = require('fs');
const read = f => fs.readFileSync(f, 'utf8').split(/\r?\n/).filter(l => l !== '');
const ref = read('src/test/resources/thermo/engine-digest.txt'), got = read('build/engine-digest.txt');
console.log('reference lines', ref.length, 'recorded lines', got.length);
const key = l => l.split(' | ')[0];
const refKeys = ref.map(key), gotKeys = got.map(key);
console.log('same request list', JSON.stringify(refKeys) === JSON.stringify(gotKeys));
let diff = 0, grade = 0, other = [];
const n = Math.min(ref.length, got.length);
for (let i = 0; i < n; i++) {
  if (ref[i] === got[i]) continue;
  diff++;
  if (ref[i].split('EXTRAPOLATED').join('ESTIMATED_DECLARED_ERROR') === got[i]) grade++;
  else other.push(i + 1);
}
console.log('differing lines', diff, 'grade token only', grade, 'other', other.length);
const first = [];
for (let i = 0; i < n; i++) if (ref[i] !== got[i]) first.push(i + 1);
console.log('differing line numbers', first.join(','));
for (const i of other.slice(0, 5)) { console.log('REF ' + i + ': ' + ref[i - 1].slice(0, 600)); console.log('GOT ' + i + ': ' + got[i - 1].slice(0, 600)); }
for (const i of first) console.log('  ' + i + ' ' + key(ref[i - 1]).slice(0, 160));
