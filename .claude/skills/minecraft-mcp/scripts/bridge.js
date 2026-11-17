#!/usr/bin/env node
// Drive the langyo/minecraft-mod-mcp in-game mod (v0.4.28) directly over its HTTP endpoint.
// No MCP tools need to be loaded in the session: the mod serves /api/status and /api/cmd itself,
// and the npm bridge only translates MCP calls into these same requests.
// Run from the worktree root (screenshots and the flatten class are placed relative to it).
//
//   node bridge.js status
//   node bridge.js wait-ready [timeoutSeconds=300]   poll /api/status until the mod answers
//   node bridge.js wait-world [timeoutSeconds=90]    poll until no screen is open (world loaded)
//   node bridge.js <cmd> ['{"json":"params"}']
//   node bridge.js shot <file.png>           screenshot_to_file + alpha flatten (needs java on PATH);
//                                            the file must be inside the worktree's tmp/ (the lane's mcp.screenshot.dir)
//   node bridge.js chat "/gamemode creative"  open_chat, type the line, press Enter
//   node bridge.js cmds <file.txt>             one slash command per line, run in order through chat
//   node bridge.js batch <file.jsonl>          one {"cmd":..., ...params} object per line, run in order
//
// MC_MCP_PORT: a client on another port (default 9876). MC_MCP_TOKEN: sent as a bearer token when the
// client was started with one (the runMcpClient lane sets none).

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const PORT = process.env.MC_MCP_PORT || '9876';
const BASE = `http://127.0.0.1:${PORT}`;
const TOKEN = process.env.MC_MCP_TOKEN || '';
const SHOT_ROOT = path.resolve('tmp');
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function status() {
  try {
    const r = await fetch(`${BASE}/api/status`, { signal: AbortSignal.timeout(2000) });
    return r.ok ? await r.json() : null;
  } catch { return null; }
}

async function cmd(name, params = {}, timeoutMs = 30000) {
  const headers = { 'Content-Type': 'application/json' };
  if (TOKEN) headers.Authorization = `Bearer ${TOKEN}`;
  const r = await fetch(`${BASE}/api/cmd`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ cmd: name, ...params }),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await r.text();
  let v; try { v = JSON.parse(text); } catch { v = text; }
  if (!r.ok) throw new Error(`bridge ${name} -> HTTP ${r.status} ${text}`);
  return v;
}

function flatten(png) {
  const src = path.join(__dirname, 'Flatten.java');
  const out = path.resolve('build', 'mcp-flatten');
  if (!fs.existsSync(path.join(out, 'Flatten.class'))) {
    fs.mkdirSync(out, { recursive: true });
    execFileSync('javac', ['-d', out, src], { stdio: 'inherit' });
  }
  execFileSync('java', ['-cp', out, 'Flatten', png], { stdio: 'inherit' });
}

async function main() {
  const [op, ...rest] = process.argv.slice(2);
  if (!op || op === 'status') { const s = await status(); console.log(JSON.stringify(s)); process.exit(s ? 0 : 1); }
  if (op === 'wait-ready') {
    const limit = Number(rest[0] || 300);
    for (let i = 0; i < limit; i++) { const s = await status(); if (s) { console.log(JSON.stringify(s)); return; } await sleep(1000); }
    throw new Error(`no bridge on ${BASE} after ${limit} s`);
  }
  if (op === 'wait-world') {
    // In the world = no screen is open. Menus and loading screens answer with a screen name.
    const limit = Number(rest[0] || 90);
    for (let i = 0; i < limit; i += 3) {
      const r = await cmd('get_screen_buttons').catch(() => null);
      if (r && r.error === 'no screen') { console.log('in world'); return; }
      await sleep(3000);
    }
    throw new Error(`still on a screen after ${limit} s`);
  }
  if (op === 'shot') {
    const file = path.resolve(rest[0] || path.join('tmp', 'mcp-shots', `shot-${Date.now()}.png`));
    if (path.relative(SHOT_ROOT, file).startsWith('..')) throw new Error(`shot path must be inside ${SHOT_ROOT} (bridge 0.4.28 refuses others)`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const reply = await cmd('screenshot_to_file', { path: file.split(path.sep).join('/') });
    console.log(JSON.stringify(reply));
    if (reply && reply.error) process.exit(1);
    flatten(file);
    return;
  }
  if (op === 'chat' || op === 'cmds') {
    // Open the chat, type the line, press Enter as its own key event.
    const lines = op === 'chat'
      ? [rest.join(' ')]
      : fs.readFileSync(rest[0], 'utf8').split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith('#'));
    for (const l of lines) {
      const line = op === 'cmds' && !l.startsWith('/') ? '/' + l : l;
      await cmd('open_chat'); await sleep(400);
      await cmd('type_text', { text: line }); await sleep(400);
      await cmd('press_key', { key: 'enter' }); await sleep(600);
      console.log('sent', line);
    }
    return;
  }
  if (op === 'batch') {
    const lines = fs.readFileSync(rest[0], 'utf8').split(/\r?\n/).filter(l => l.trim() && !l.startsWith('#'));
    for (const line of lines) {
      const { cmd: name, delay_ms, ...params } = JSON.parse(line);
      console.log(name, JSON.stringify(await cmd(name, params)));
      await sleep(delay_ms ?? 250);
    }
    return;
  }
  console.log(JSON.stringify(await cmd(op, rest[0] ? JSON.parse(rest[0]) : {})));
}

main().catch(e => { console.error(e.message); process.exit(1); });
