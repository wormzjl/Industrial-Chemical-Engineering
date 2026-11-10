// Sets the batch's version at the owner's word ("Merge as 0.26.0"): mod_version, the CHANGELOG heading, the batch's
// status in documentation/INDEX.md and its README, and the code guide's "after 0.25.0" phrases. One-off.
// Usage: node tools/column-failure-modes/merge-prep/version.js   (from the worktree root).
const fs = require('fs'), path = require('path');
const V = '0.26.0', DAY = '2026-11-10';
function edit(file, fn) {
  const before = fs.readFileSync(file, 'utf8');
  const nl = before.includes('\r\n') ? '\r\n' : '\n';
  const after = fn(before, nl);
  if (after === before) throw new Error(file + ': nothing changed');
  fs.writeFileSync(file, after);
  console.log(file);
}
const once = (s, from, to) => {
  if (s.split(from).length !== 2) throw new Error('expected one: ' + from);
  return s.replace(from, to);
};
edit('gradle.properties', s => once(s, 'mod_version=0.25.0', 'mod_version=' + V));
edit('CHANGELOG.md', (s, nl) => once(s, '## [Unreleased]' + nl + nl + '### Added', '## [Unreleased]' + nl + nl + '## [' + V + '] - ' + DAY + nl + nl + '### Added'));
edit('documentation/INDEX.md', s => once(s, 'adjustable parameters, a full-size test world | In progress since 2026-11-10 |', 'adjustable parameters, a full-size test world | Implemented ' + DAY + ' (' + V + ') |'));
edit('documentation/2026-11-10-column-failure-modes/README.md', s => once(s,
  'Status: In progress since 2026-11-10 (decisions taken; execution in five stages, see `EXECUTION_PLAN.md`).',
  'Status: **Implemented ' + DAY + '** (' + V + '). Branch `claude/distillation-failure-mode-control-db2da5`.'));
const guide = 'documentation/reference/code-guide';
let n = 0;
for (const f of fs.readdirSync(guide).filter(x => x.endsWith('.md'))) {
  const file = path.join(guide, f).replace(/\\/g, '/');
  const before = fs.readFileSync(file, 'utf8');
  const after = before
    .split('to merge after 0.25.0').join('merged as ' + V)
    .split('(after 0.25.0, version not chosen)').join('(' + V + ')')
    .split('(after 0.25.0, batch 2026-11-10-column-failure-modes)').join('(' + V + ', batch 2026-11-10-column-failure-modes)');
  if (after !== before) { fs.writeFileSync(file, after); n++; console.log(file); }
}
console.log('code guide files: ' + n);
