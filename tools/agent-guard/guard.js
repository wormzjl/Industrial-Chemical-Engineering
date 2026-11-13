// PreToolUse guard for Claude Code and Codex: blocks the git and Gradle commands AGENTS.md forbids.
// Both tools send {"tool_input":{"command":"..."}} on stdin; a PreToolUse "deny" on stdout blocks the call.
'use strict';

// Text inside heredocs, here-strings and quotes is data (commit messages, file bodies), not a command.
function stripData(cmd) {
  let s = cmd.replace(/<<-?\s*(['"]?)(\w+)\1[^\n]*\n[\s\S]*?\n\s*\2\s*(\n|$)/g, '\n');
  s = s.replace(/@'[\s\S]*?\n'@/g, ' Q ').replace(/@"[\s\S]*?\n"@/g, ' Q ');
  s = s.replace(/'[^'\n]*'/g, ' Q ').replace(/"(?:\\.|[^"\\\n])*"/g, ' Q ');
  return s;
}

const GIT = String.raw`\bgit(?:\s+-[cC]\s+\S+)*\s+`;
const RULES = [
  [new RegExp(GIT + String.raw`stash\s*(?:$|pop\b)`), 'bare `git stash` / `git stash pop`',
    'set work aside with a WIP commit, or `git stash push -u -m "<tag>"` and restore with `git stash apply <sha>`'],
  [new RegExp(GIT + String.raw`stash\s+push\b(?!.*\s(?:-m|--message)\b)`), '`git stash push` without a tag (-m)',
    'use `git stash push -u -m "<unique-tag>"`, then `git stash apply <sha>`'],
  [new RegExp(GIT + String.raw`add\b.*\s(?:--force\b|-[a-zA-Z]*f[a-zA-Z]*\b)`), '`git add -f` (staging ignored files)',
    'ignored files (logs, reports, gate output) stay unstaged; store logs as tar.gz under the main tools/<folder>/logs/'],
  [/\bgradle(?:w|w\.bat)?\b.*(?:--rerun-tasks\b|--no-build-cache\b|\bcleanTest\b)/, 'a forced Gradle rerun',
    'an up-to-date or restored task counts as run; for a replay AGENTS.md asks for, use `--rerun` on one filtered test task'],
  [/\b(?:rm\s+-[a-zA-Z]*r[a-zA-Z]*|Remove-Item\b.*-Recurse)\s+(?:\S+\s+)*\.?[\\/]?build[\\/]?(?:\s|$)/i, 'deleting build/',
    'do not force results again by deleting build/; read stored results in build/test-results'],
];

// cargo (AGENTS.md "Native code", RUST_COLUMN_PORT_PLAN.md pitfall 18): a cargo build uses at most 4 jobs. The
// workspace's src/main/rust/.cargo/config.toml sets 4, so cargo may run inside src/main/rust (the session's folder, or a
// cd earlier in the command) without -j; elsewhere it needs -j or --jobs of 1 to 4. CARGO_BUILD_JOBS counts as -j.
const CARGO = /(?:^|[\s(])cargo(?:\.exe)?\s+(?:\+\S+\s+)?(?:build|test|clippy|run|bench)\b/;
const CARGO_JOBS = /(?:^|\s)(?:-j\s*|--jobs(?:=|\s+))(-?\d+)\b|\bCARGO_BUILD_JOBS\s*=\s*(-?\d+)/g;
const CD = /^(?:cd|chdir|Set-Location|sl|pushd|Push-Location)(?=\s|$)(?:\s+-(?:Path|LiteralPath))?(?:\s+(\S+))?/i;
const RUST_DIR = /(?:^|[\\/])src[\\/]main[\\/]rust(?:[\\/]|$)/i;
const CARGO_RULE = ['a cargo build outside src/main/rust or with more than 4 jobs',
  'run cargo from src/main/rust (`cd src/main/rust && cargo test --locked --offline`), whose .cargo/config.toml sets '
    + '4 jobs, or pass `-j 4` or fewer; a cargo build counts as one of the two Gradle lanes'];

// A quoted cd target is a path, not data: keep it (spaces become _) so the folder a cargo command runs in stays known.
function unquoteCdTargets(cmd) {
  return cmd.replace(/(\b(?:cd|chdir|Set-Location|sl|pushd|Push-Location)(?:\s+-(?:Path|LiteralPath))?\s+)(['"])([^'"\n]*)\2/gi,
    (_, head, __, path) => head + path.replace(/\s/g, '_'));
}

// The folder after `cd <target>` from `inRust`: inside src/main/rust when the target names it, or when it is a relative
// path without `..` taken from inside; anywhere else (or unknown) is outside.
function cdInRust(target, inRust) {
  if (!target) return false;
  if (RUST_DIR.test(target)) return true;
  const relative = !/^(?:[\\/~$%]|[A-Za-z]:)/.test(target) && !target.split(/[\\/]/).includes('..') && target !== '-';
  return inRust && relative;
}

function cargoAllowed(segment, inRust) {
  const jobs = [...segment.matchAll(CARGO_JOBS)].map(m => Number(m[1] ?? m[2]));
  if (jobs.length) return jobs.every(n => n >= 1 && n <= 4);
  return inRust;
}

function check(command, cwd = '') {
  const text = stripData(unquoteCdTargets(String(command || '')));
  let inRust = RUST_DIR.test(String(cwd || ''));
  for (const segment of text.split(/\r?\n|&&|\|\||;|\|/)) {
    const trimmed = segment.trim().replace(/^[({\s]+/, '');
    for (const [re, what, instead] of RULES) {
      if (re.test(trimmed)) return `Blocked by tools/agent-guard (AGENTS.md): ${what}. Instead: ${instead}.`;
    }
    const cd = CD.exec(trimmed);
    if (cd) inRust = cdInRust(cd[1], inRust);
    else if (CARGO.test(trimmed) && !cargoAllowed(trimmed, inRust)) {
      return `Blocked by tools/agent-guard (AGENTS.md): ${CARGO_RULE[0]}. Instead: ${CARGO_RULE[1]}.`;
    }
  }
  return null;
}

if (require.main === module || process.env.AGENT_GUARD_RUN === '1') {
  let raw = '';
  process.stdin.on('data', c => (raw += c));
  process.stdin.on('end', () => {
    let input;
    try { input = JSON.parse(raw); } catch { process.exit(0); }
    // The session's folder decides where a cargo command without a cd runs; Claude Code sends it as cwd.
    const reason = check((input.tool_input || {}).command, input.cwd || process.cwd());
    // A JSON deny on stdout with exit 0: both tools honour it, and no shell wrapper can change it
    // (PowerShell -Command turns exit 2 into 1).
    if (reason) {
      process.stdout.write(JSON.stringify({
        hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: reason },
      }) + '\n');
    }
    process.exit(0);
  });
}

module.exports = { check, stripData };
