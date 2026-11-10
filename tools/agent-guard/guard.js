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

function check(command) {
  const text = stripData(String(command || ''));
  for (const segment of text.split(/\r?\n|&&|\|\||;|\|/)) {
    for (const [re, what, instead] of RULES) {
      if (re.test(segment.trim())) return `Blocked by tools/agent-guard (AGENTS.md): ${what}. Instead: ${instead}.`;
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
    const reason = check((input.tool_input || {}).command);
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
