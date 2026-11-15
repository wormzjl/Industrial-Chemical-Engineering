// node tools/fluid-step-cost/bench/holds-compare.mjs <old report> <new report>: the hold-fuzz seeds whose holds, classes or refusals differ.
import fs from 'node:fs';
const read = p => {
  const seeds = new Map();
  for (const line of fs.readFileSync(p, 'utf8').split(/\r?\n/)) {
    const m = /^SEED (\d+) blocks=(\d+) ms=\d+ end=(\d+) holds=(\d+) classes=\[([^\]]*)\] refused=(\d+)/.exec(line);
    if (m) seeds.set(+m[1], { blocks: +m[2], end: +m[3], holds: +m[4], classes: m[5], refused: +m[6] });
  }
  return seeds;
};
const a = read(process.argv[2]), b = read(process.argv[3]);
const total = s => [...s.values()].reduce((x, y) => x + y.holds, 0), held = s => [...s.values()].filter(y => y.holds > 0).length;
console.log(`old: seeds ${a.size} held seeds ${held(a)} holds ${total(a)} | new: seeds ${b.size} held seeds ${held(b)} holds ${total(b)}`);
let differ = 0, endDiffer = 0;
for (const [seed, x] of a) {
  const y = b.get(seed);
  if (!y) { console.log(`seed ${seed}: missing in new`); differ++; continue; }
  if (x.holds !== y.holds || x.classes !== y.classes || x.refused !== y.refused || x.blocks !== y.blocks) {
    differ++; console.log(`seed ${seed}: holds ${x.holds} [${x.classes}] refused ${x.refused} -> holds ${y.holds} [${y.classes}] refused ${y.refused}`);
  }
  if (x.end !== y.end) endDiffer++;
}
console.log(`seeds that differ in holds, classes, refusals or blocks: ${differ}; seeds whose end tick differs: ${endDiffer}`);
