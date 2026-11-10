// Renumbers this batch's facts schema mentions (17 for W4, 18 for W5) to 21 in the lines that name W4, W5, the utility
// sides or the members. Lines of the shader branch's schemas 17 and 18 (ejector, rigid) are left alone.
// Usage: node tmp/col-merge/renumber.js   (from the worktree root).
const fs = require('fs');
const files = {
  'src/main/java/com/wormzjl/icheme/runtime/fluid/FluidFacts.java': /utility sides|W4|W5|no members/,
  'src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnUnit.java': /column-failure-modes|W4|W5/,
  'src/test/java/com/wormzjl/icheme/runtime/fluid/FluidFactsCompatTest.java': /W4|W5|utility sides|the members/,
};
for (const [path, mine] of Object.entries(files)) {
  const text = fs.readFileSync(path, 'utf8');
  const nl = text.includes('\r\n') ? '\r\n' : '\n';
  let n = 0;
  const lines = text.split(nl).map((line, i) => {
    if (!/[Ss]chema 1[78]\b/.test(line) || !mine.test(line)) return line;
    n++;
    const next = line.replace(/([Ss]chema) 1[78]\b/g, '$1 21');
    console.log(path.split('/').pop() + ':' + (i + 1) + ': ' + next.trim().slice(0, 150));
    return next;
  });
  fs.writeFileSync(path, lines.join(nl));
  console.log(path + ': ' + n + ' lines');
}
