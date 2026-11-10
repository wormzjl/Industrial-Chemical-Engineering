#!/usr/bin/env node
// The effects bench's budgets against what the runs measured (batch 2026-11-10-custom-shader-system, FX_BENCH_PLAN.md).
// Usage: node tools/fx-bench/summarize.mjs <log dir>   (reads every <run>-summary.txt that lane.sh kept there)
// Prints one row per budget: what it compares, the measured value, the limit, PASS / MISS (or "-" when a run is missing),
// then the feed and scene checks. Reads text only; runs nothing.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2];
if (!dir) { console.error('usage: node summarize.mjs <log dir>'); process.exit(2); }

// tag -> list of { run, fields }; fields parsed from "key=value" (a value may be [ ... ] with spaces).
const lines = [];
for (const name of readdirSync(dir).filter(n => n.endsWith('-summary.txt')).sort()) {
  const run = name.slice(0, -'-summary.txt'.length);
  for (const line of readFileSync(join(dir, name), 'utf8').split(/\r?\n/)) {
    const m = line.match(/\b(FX_PROBE|FPS_PROBE|FX_JFR|FX_FEED|FX_SERVER|FX_MEMORY|FPS_AUTOPILOT)\b(.*)$/);
    if (!m) continue;
    const fields = {};
    for (const f of m[2].matchAll(/([A-Za-z][\w.\[\]/]*)=(\[[^\]]*\]|\S+)/g)) fields[f[1]] = f[2];
    lines.push({ tag: m[1], run, fields, text: m[2].trim() });
  }
}
// A window's result line, not its "FPS_PROBE start label=..." line.
const find = (tag, label) => lines.find(l => l.tag === tag && l.fields.label === label && !l.text.startsWith('start '))?.fields;
const all = (tag, run) => lines.filter(l => l.tag === tag && (!run || l.run === run));
/** Value i (0 p50, 1 p95, ...) of a "a/b/c" field, as a number; NaN when absent. */
const part = (fields, key, i = 0) => { const v = fields?.[key]; return v === undefined ? NaN : Number(v.split('/')[i]); };
const fmt = (v, d = 3) => Number.isFinite(v) ? v.toFixed(d) : '-';
const rows = [];
const row = (budget, compares, measured, limit, unit, pass) => rows.push({ budget, compares, measured: Number.isFinite(measured) ? `${fmt(measured)} ${unit}` : '-',
  limit: `${limit} ${unit}`, verdict: Number.isFinite(measured) ? (pass(measured) ? 'PASS' : 'MISS') : '-' });

// 1. Frame time: effects AUTO at most 0.3 ms p50 over OFF, same scene (lane 1; lane 3 under the pack, for the record).
for (const w of ['view', 'away']) {
  const off = find('FPS_PROBE', `1-off-${w}`), auto = find('FPS_PROBE', `1-auto-${w}`);
  row('frame time p50', `1-auto-${w} - 1-off-${w}`, part(auto, 'p50ms') - part(off, 'p50ms'), '<= 0.3', 'ms', v => v <= .3);
  for (const q of ['p95ms', 'p99ms']) row(`frame time ${q.slice(0, 3)} (record)`, `1-auto-${w} - 1-off-${w}`, part(auto, q) - part(off, q), 'n/a', 'ms', () => true);
  const poff = find('FPS_PROBE', `3-off-${w}`), pauto = find('FPS_PROBE', `3-auto-${w}`);
  row('frame time p50, pack (record)', `3-auto-${w} - 3-off-${w}`, part(pauto, 'p50ms') - part(poff, 'p50ms'), 'n/a', 'ms', () => true);
}
const compat = find('FPS_PROBE', '2-compat-view'), off1 = find('FPS_PROBE', '1-off-view');
row('frame time p50, COMPATIBLE (record)', '2-compat-view - 1-off-view', part(compat, 'p50ms') - part(off1, 'p50ms'), 'n/a', 'ms', () => true);

// 2. GPU time of our draws: the AFTER_PARTICLES stage's GPU time, AUTO over OFF (only our drawers differ there).
{
  const off = find('FX_PROBE', '1-off-view'), auto = find('FX_PROBE', '1-auto-view'), c = find('FX_PROBE', '2-compat-view');
  row('GPU of our draws p50', '1-auto-view - 1-off-view, gpuStageMs', part(auto, 'gpuStageMs[p50/p95]') - part(off, 'gpuStageMs[p50/p95]'), '<= 0.5', 'ms', v => v <= .5);
  row('GPU of our draws p50, COMPATIBLE (record)', '2-compat-view - 1-off-view', part(c, 'gpuStageMs[p50/p95]') - part(off, 'gpuStageMs[p50/p95]'), 'n/a', 'ms', () => true);
}

// 3. Render-thread CPU of our code: FxStats draw + wiring per frame; rebuilds only when a snapshot arrives.
for (const label of ['1-auto-view', '2-compat-view', '3-auto-view', '5-sound-view']) {
  const f = find('FX_PROBE', label);
  row('render-thread CPU p50', `${label}, fxCpuMs (effects draw + wiring)`, part(f, 'fxCpuMs[p50/p95]'), '<= 0.3', 'ms', v => v <= .3);
}
{
  // Each source re-aims every 5 s (FxSources.RETARGET) and on a new state; the feed's states never change, so at most one
  // rebuild per registered emitter per 5 s, plus the first build.
  const f = find('FX_PROBE', '1-auto-view'), mem = all('FX_MEMORY', '1-auto')[0]?.fields;
  const rebuilds = Number(f?.rebuilds), frames = Number(f?.frames), registered = Number(mem?.registered);
  const seconds = Number(find('FPS_PROBE', '1-auto-view')?.seconds);
  const allowed = registered * (Math.ceil(seconds / 5) + 1);
  rows.push({ budget: 'rebuilds only on a snapshot', compares: `1-auto-view: ${rebuilds} rebuilds in ${frames} frames, ${registered} emitters`,
    measured: Number.isFinite(rebuilds) ? `${rebuilds}` : '-', limit: Number.isFinite(allowed) ? `<= ${allowed} (one per emitter per 5 s)` : '-',
    verdict: Number.isFinite(rebuilds) && Number.isFinite(allowed) ? (rebuilds <= allowed ? 'PASS' : 'MISS') : '-' });
}

// 4. Sound tick: FxSounds' tick, mean per tick, with the plan's 100 valves and 20 fans in range; the budget's loops.
{
  const f = find('FX_PROBE', '5-sound-view');
  row('sound tick mean', '5-sound-view, soundMs mean', part(f, 'soundMs[mean/p50/p95/max]', 0), '<= 0.05', 'ms', v => v <= .05);
  row('sound tick p95 (record)', '5-sound-view', part(f, 'soundMs[mean/p50/p95/max]', 2), 'n/a', 'ms', () => true);
  row('loops playing, most', '5-sound-view, loops max', part(f, 'loops[mean/max]', 1), '<= 12', '', v => v <= 12);
}

// 5. Allocation: JFR samples whose stack holds client.fx or the instrument renderer, SHADER path at steady state.
for (const label of ['1-auto-view', '1-auto-away', '2-compat-view']) {
  const f = find('FX_JFR', label);
  row(`allocation per frame${label.startsWith('2') ? ' (record)' : ''}`, `${label}, allocFxPerFrame`, Number(f?.allocFxPerFrame), label.startsWith('2') ? 'n/a' : '< 1024', 'B', v => label.startsWith('2') || v < 1024);
}

// 6. Resident memory: vertex buffers, wiring uploads, the haze target, the effects' and wiring's textures, silhouette boxes.
{
  // A DH box: two corners and a colour, about 64 B with its object header (an estimate; DH's own GPU copy is not counted).
  const BOX = 64;
  let worst = NaN, at = '';
  for (const l of all('FX_MEMORY')) {
    const m = l.fields;
    const bytes = ['vertexBytes', 'wiringBytes', 'targetBytes', 'textureBytes'].reduce((s, k) => s + Number(m[k] || 0), 0) + BOX * Number(m.silhouetteBoxes || 0);
    if (!(bytes <= worst)) { worst = bytes; at = l.run; }
  }
  row('resident memory', `largest run (${at || '-'}): vertex + wiring + target + textures + ${BOX} B x boxes`, worst / 2 ** 20, '<= 32', 'MB', v => v <= 32);
}

// 7. Server: ns per publication per island (here per watched device, island or subscriber), the scene's size.
{
  const pubs = all('FX_SERVER');
  for (const name of ['towers', 'heaters', 'noise', 'noise-recomputed', 'silhouettes', 'silhouettes-ranked']) {
    // The run that served the most (the DH run for silhouettes).
    let best;
    for (const l of pubs.filter(l => l.fields.publication === name)) {
      const n = Number(l.fields.devices ?? l.fields.islands ?? l.fields.subscribers);
      if (!best || n > best.n) best = { n, l };
    }
    const p50 = best ? Number(best.l.fields.p50Us) / 1000 : NaN, per = best && best.n > 0 ? p50 / best.n : p50;
    rows.push({ budget: `server ${name}`, compares: best ? `${best.l.run}: p50 ${fmt(p50, 4)} ms over ${best.n}` : '-', measured: Number.isFinite(per) ? `${fmt(per, 4)} ms each` : '-',
      limit: '<= 0.1 ms each', verdict: Number.isFinite(per) ? (per <= .1 ? 'PASS' : 'MISS') : '-' });
  }
}

const width = k => Math.max(k.length, ...rows.map(r => String(r[k]).length));
const cols = ['budget', 'compares', 'measured', 'limit', 'verdict'];
const w = Object.fromEntries(cols.map(c => [c, width(c)]));
console.log(cols.map(c => c.padEnd(w[c])).join(' | '));
console.log(cols.map(c => '-'.repeat(w[c])).join('-|-'));
for (const r of rows) console.log(cols.map(c => String(r[c]).padEnd(w[c])).join(' | '));

// The scene and the feed: every run should hold 10 flares, 10 stills, 4 towers and 10 heaters at full load before its windows.
console.log('\nFeed (expected flares=10/10 stills=10/10 towers=4/4 heaters=10/10 before the windows; the far window holds none):');
for (const l of all('FX_FEED')) {
  const short = ['flares', 'stills', 'towers', 'heaters'].filter(k => l.fields[k] && Number(l.fields[k].split('/')[0]) < ({ flares: 10, stills: 10, towers: 4, heaters: 10 })[k]);
  console.log(`  ${l.run} ${l.text}${short.length && l.fields.when !== 'far' ? `   SHORT: ${short.join(', ')}` : ''}`);
}
for (const l of lines.filter(l => l.tag === 'FPS_AUTOPILOT' && /instruments|failed|cannot|exists/.test(l.text))) console.log(`  ${l.run} FPS_AUTOPILOT ${l.text}`);
for (const l of all('FX_JFR')) if (!l.fields.allocFxPerFrame) console.log(`  ${l.run} FX_JFR ${l.text}`);
