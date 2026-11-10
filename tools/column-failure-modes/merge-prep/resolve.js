// Resolves the four text conflicts of the merge of claude/custom-shader-system-caff9e into the column failure modes branch.
// Usage: node tmp/col-merge/resolve.js   (from the worktree root). Files are CRLF; lines are split and joined on \r\n.
const fs = require('fs');
const NL = '\r\n';
function sides(path, fn) {
  const lines = fs.readFileSync(path, 'utf8').split(NL);
  const out = [];
  let i = 0, n = 0;
  while (i < lines.length) {
    if (lines[i].startsWith('<<<<<<< ')) {
      const ours = [], theirs = [];
      i++;
      while (!lines[i].startsWith('=======')) ours.push(lines[i++]);
      i++;
      while (!lines[i].startsWith('>>>>>>> ')) theirs.push(lines[i++]);
      i++;
      out.push(...fn(ours, theirs, n++));
    } else out.push(lines[i++]);
  }
  fs.writeFileSync(path, out.join(NL));
  console.log(path + ': ' + n + ' conflicts resolved');
}
// Index rows: every row of both sides, ours on top.
sides('documentation/INDEX.md', (o, t) => [...o, ...t]);
// Alarm channels: the chimney's (merged first), then the column's.
sides('src/main/java/com/wormzjl/icheme/runtime/control/BuiltInInputs.java', (o, t) => [...t, ...o]);
sides('src/main/java/com/wormzjl/icheme/runtime/fluid/EquipmentAlarm.java', (o, t, n) => {
  if (n === 0) {
    // The enum's tail: the chimney's constant, then the column's, which keep the closing semicolon.
    const chimney = t.map(l => l.replace('NO_DRAFT("NO DRAFT");', 'NO_DRAFT("NO DRAFT"),'));
    if (chimney.join() === t.join()) throw new Error('chimney constant not found');
    return [...chimney, ...o];
  }
  return [...t, ...o];
});
// Facts schema: the shader branch's 17 to 20 stand; this batch's two steps become one schema 21.
sides('src/main/java/com/wormzjl/icheme/runtime/fluid/FluidFacts.java', (o, t) => {
  const kept = t.slice(0, t.length - 2);
  const last = kept.length - 1;
  if (!kept[last].endsWith('*/')) throw new Error('schema list tail not found: ' + kept[last]);
  kept[last] = kept[last].replace(/ \*\/$/, '');
  return [...kept,
    '     *  21 (batch 2026-11-10-column-failure-modes, W4 and W5): a column unit\'s utility sides, its reboiler\'s and each',
    '     *  pumparound cooler\'s; a kettle\'s tube side in the {@code COLUMN} mode; a column unit\'s powered members, its air',
    '     *  condenser\'s fan sides and its pumps\' movers. */',
    '    static final int SCHEMA=21;',
    '    /** Renamed or removed enum constants, per enum: the saved name to the current one. None at schema 21. */'];
});
