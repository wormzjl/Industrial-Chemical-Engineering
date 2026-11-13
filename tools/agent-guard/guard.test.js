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
  'cargo build -j 8',
  'cargo build --jobs=16',
  'cargo test',
  'cargo test --manifest-path src/main/rust/Cargo.toml',
  'cd src/main/rust && cargo build -j 8',
  'cd src/main/rust && CARGO_BUILD_JOBS=8 cargo test',
  'cd src/main/rust && cd .. && cargo test',
  'cd D:/elsewhere && cargo clippy --all-targets',
  ['cargo test -j 16', 'D:/repo/src/main/rust'],
  ['cd ../.. && cargo build', 'D:/repo/src/main/rust'],
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
  'cargo test -j 4',
  'cargo build --jobs 2',
  'cargo fmt --check',
  'cd src/main/rust && cargo test --locked --offline',
  'cd /d/Minecraft/Modding/1.21/CreateChemE/src/main/rust && cargo build --release --locked --offline -p icheme-jni',
  'cd "D:/My Folder/repo/src/main/rust"; cargo clippy --locked --offline --all-targets -- -D warnings',
  'Set-Location src/main/rust; cargo test --release',
  'cd src/main/rust && cd icheme-jni && cargo test',
  'git commit -m "cargo build -j 16 is blocked"',
  'sleep 1 && cargo test -j 3',
  ['cargo test --locked --offline', 'D:/repo/src/main/rust'],
  ['cargo test', 'D:\\repo\\src\\main\\rust\\icheme-math'],
];

let fail = 0;
const run = c => (Array.isArray(c) ? check(c[0], c[1]) : check(c));
for (const c of blocked) if (!run(c)) { fail++; console.log('NOT BLOCKED: ' + JSON.stringify(c)); }
for (const c of allowed) { const r = run(c); if (r) { fail++; console.log('WRONGLY BLOCKED: ' + JSON.stringify(c) + ' -> ' + r); } }
console.log(fail ? `${fail} failure(s)` : `ok: ${blocked.length} blocked, ${allowed.length} allowed`);
process.exit(fail ? 1 : 0);
