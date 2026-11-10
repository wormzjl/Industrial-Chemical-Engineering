// Self-test: node tools/agent-guard/guard.test.js
'use strict';
const { check } = require('./guard.js');

const blocked = [
  'git stash',
  'git stash pop',
  'git -C D:/x stash pop',
  'git stash push -u',
  'git add -f logs/run.log',
  'git add --force build/report.txt',
  'git add -Af x',
  './gradlew test --rerun-tasks',
  './gradlew cleanTest test',
  'gradlew.bat check --no-build-cache',
  'rm -rf build',
  'rm -rf ./build/',
  'Remove-Item -Recurse -Force build',
  'cd x && git stash',
];
const allowed = [
  'git stash list',
  'git stash push -u -m "wip-tag"',
  'git stash apply abc123',
  'git stash drop stash@{0}',
  'git add AGENTS.md tools/agent-guard',
  'git add -A',
  './gradlew test --tests "*FooTest" --rerun',
  'git commit -m "Never use git stash pop or --rerun-tasks"',
  "git commit -F - <<'EOF'\nBlocks git add -f and cleanTest\nEOF",
  'rm -rf tmp/build-copy',
  'grep -n "git stash" AGENTS.md',
];

let fail = 0;
for (const c of blocked) if (!check(c)) { fail++; console.log('NOT BLOCKED: ' + JSON.stringify(c)); }
for (const c of allowed) { const r = check(c); if (r) { fail++; console.log('WRONGLY BLOCKED: ' + JSON.stringify(c) + ' -> ' + r); } }
console.log(fail ? `${fail} failure(s)` : `ok: ${blocked.length} blocked, ${allowed.length} allowed`);
process.exit(fail ? 1 : 0);
