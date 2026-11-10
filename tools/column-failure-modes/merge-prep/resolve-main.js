// Resolves the merge of main (0.25.0, 833942033) into the column failure modes branch. One-off.
// 1. Two code guide snapshot lines: this batch's line stands, and its "earlier:" part becomes main's newer snapshot.
// 2. CHANGELOG.md: this batch's four lines leave the 0.25.0 section and go under [Unreleased].
// Usage: node tools/column-failure-modes/merge-prep/resolve-main.js   (from the worktree root).
const fs = require('fs');
const nlOf = s => (s.includes('\r\n') ? '\r\n' : '\n');

for (const path of ['documentation/reference/code-guide/fluid-science.md', 'documentation/reference/code-guide/rotating-machinery.md']) {
  const text = fs.readFileSync(path, 'utf8');
  const nl = nlOf(text);
  const lines = text.split(nl);
  const a = lines.findIndex(l => l.startsWith('<<<<<<< '));
  const b = lines.findIndex(l => l.startsWith('======='));
  const c = lines.findIndex(l => l.startsWith('>>>>>>> '));
  if (a < 0 || b < a || c < b) throw new Error(path + ': markers');
  const ours = lines.slice(a + 1, b);
  const theirs = lines.slice(b + 1, c).filter(l => l.startsWith('**Snapshot:** '));
  if (theirs.length !== 1 || !ours[0].startsWith('**Snapshot:** ')) throw new Error(path + ': snapshot lines');
  const cut = ours[0].indexOf(' · earlier: ');
  if (cut < 0) throw new Error(path + ': no earlier part');
  ours[0] = ours[0].slice(0, cut) + ' · earlier: ' + theirs[0].slice('**Snapshot:** '.length);
  fs.writeFileSync(path, [...lines.slice(0, a), ...ours, ...lines.slice(c + 1)].join(nl));
  console.log(path + ': resolved');
}

{
  const path = 'CHANGELOG.md';
  const text = fs.readFileSync(path, 'utf8');
  const nl = nlOf(text);
  const lines = text.split(nl);
  const mine = l => /^- (Running column failure modes and control:|Translations of the column failure modes batch|Tooling cleanup of the column failure modes batch|Code guide refreshed for the column failure modes batch)/.test(l);
  const moved = lines.filter(mine);
  if (moved.length !== 4) throw new Error('CHANGELOG: expected 4 lines, found ' + moved.length);
  const rest = lines.filter(l => !mine(l));
  const at = rest.indexOf('## [Unreleased]');
  if (at < 0 || rest[at + 1] !== '' || !rest[at + 2].startsWith('## [0.25.0]')) throw new Error('CHANGELOG: [Unreleased] is not empty');
  rest.splice(at + 1, 0, '', '### Added', '', moved[0], '', '### Changed', '', ...moved.slice(1));
  fs.writeFileSync(path, rest.join(nl));
  console.log('CHANGELOG.md: 4 lines moved under [Unreleased]');
}
