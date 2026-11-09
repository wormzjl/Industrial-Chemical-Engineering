// N4 harness (batch 2026-11-05-neural-retrain, NEURAL_RETRAIN_PLAN.md N4): the qualification rows, and the comparison of a
// CURRENT_ONLY journal against an LNN_FIRST journal of the same rows (NeuralRetrainProbe.labels without and with NR_PAYLOAD).
//
//   node tools/neural-retrain/qualify.mjs design <out-dir> <seed>
//     -> cdu.jsonl: the four grade presets at 10 feed temperatures (615 to 643 K, the CDU heater window), 12 in-family blends
//        (each grade pair at 1/3 and 2/3, neutral operation) and a fresh CDU holdout of 300 rows (design.mjs on <seed>, never a
//        training seed); vdu.jsonl: a fresh VDU holdout of 100 rows. Label vdu.jsonl with NR_CDU_LABELS on a CDU validation
//        journal, so no VDU feed comes from a training label. A consumed holdout is not blind again: a new promotion takes a new seed.
//   node tools/neural-retrain/qualify.mjs compare <classical-journal> <learned-journal> <model-id> [<out.json>]
//     -> per group (grid, blend, holdout, vdu-holdout): rows, admitted, labels on each route, rescued, lost, the learned route's
//        acceptance and its polls against classical; then criteria (a) and (b) of N4 and the cost of a failed learned attempt.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const GRADES = ['light_crude', 'medium_crude', 'heavy_crude', 'oil_sand_dilbit'];
const read = (f) => fs.readFileSync(f, 'utf8').split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));
const write = (f, list) => fs.writeFileSync(f, list.map((r) => JSON.stringify(r)).join('\n') + '\n');
const [command, ...args] = process.argv.slice(2);

if (command === 'design') {
  const [out, seedText] = args; const seed = Number(seedText);
  fs.mkdirSync(out, { recursive: true });
  const oneHot = (i) => GRADES.map((_, k) => (k === i ? 1 : 0));
  const grid = [];
  GRADES.forEach((g, i) => { for (let k = 0; k < 10; k++) {
    const T = 615 + k * (643 - 615) / 9;
    grid.push({ id: `n4-grid-${g}-${T.toFixed(1)}`, split: 'qualify', group: 'grid', composition: { kind: 'preset', weights: oneHot(i), multipliers: Array(19).fill(1), zero: [] },
      operating: { preset: true, feedTemperatureKelvin: T } });
  } });
  const neutral = { stages: 40, feedTemperatureKelvin: 630, topPressurePascal: 250e3, refluxRatio: 4.25, condenserTemperatureKelvin: 332.5, feedRateFactor: 1,
    steamFactor: 1, steamTemperatureKelvin: 535, drawFactors: [1, 1, 1], pumparoundFactors: [1, 1, 1], dropDraw: -1, dropPumparound: -1, diameterMetres: 8 };
  const blends = [];
  for (let a = 0; a < 4; a++) for (let b = a + 1; b < 4; b++) for (const w of [1 / 3, 2 / 3]) {
    const weights = [0, 0, 0, 0]; weights[a] = w; weights[b] = 1 - w;
    blends.push({ id: `n4-blend-${GRADES[a]}-${GRADES[b]}-${w.toFixed(2)}`, split: 'qualify', group: 'blend',
      composition: { kind: 'pair', weights, multipliers: Array(19).fill(1), zero: [] }, operating: { ...neutral } });
  }
  const pool = path.join(out, 'pool');
  execFileSync(process.execPath, [path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), 'design.mjs'), pool, String(seed)], { stdio: 'ignore' });
  const holdout = read(path.join(pool, 'validation.jsonl')).slice(0, 300).map((d, i) => ({ ...d, id: `n4-holdout-${String(i).padStart(5, '0')}`, split: 'qualify', group: 'holdout' }));
  const vduHoldout = read(path.join(pool, 'vdu-validation.jsonl')).slice(0, 100).map((d, i) => ({ ...d, id: `n4-vdu-holdout-${String(i).padStart(5, '0')}`, split: 'qualify', group: 'vdu-holdout' }));
  fs.rmSync(pool, { recursive: true, force: true });
  write(path.join(out, 'cdu.jsonl'), [...grid, ...blends, ...holdout]); write(path.join(out, 'vdu.jsonl'), vduHoldout);
  fs.writeFileSync(path.join(out, 'design.json'), JSON.stringify({ seed, grid: grid.length, blends: blends.length, holdout: holdout.length, vduHoldout: vduHoldout.length }, null, 1));
  console.log(JSON.stringify({ seed, grid: grid.length, blends: blends.length, holdout: holdout.length, vduHoldout: vduHoldout.length }));
} else if (command === 'compare') {
  const [classicalPath, learnedPath, modelId, outPath] = args;
  const classical = new Map(read(classicalPath).map((r) => [r.id, r])), learned = read(learnedPath);
  const label = (r) => !!(r && (r.label ?? r.strict));
  const route = (r) => (r.route ?? '').split(';')[0].replace('initializer=', '');
  const admitted = (r) => new RegExp(`model=${modelId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(;|$)`).test(r.route ?? '');
  const neural = (r) => +((/neuralPolls=(\d+)/.exec(r.route ?? '') ?? [0, 0])[1]);
  const group = (r) => r.design.group ?? 'other';
  const median = (l) => { const s = l.slice().sort((a, b) => a - b); return s.length ? s[s.length >> 1] : null; };
  const groups = {};
  for (const l of learned) {
    const c = classical.get(l.id); if (!c) continue;
    const g = (groups[group(l)] ??= { rows: 0, admitted: 0, classicalLabels: 0, learnedLabels: 0, learnedRouteLabels: 0, rescued: 0, lost: 0, classicalFailures: 0, pollRatios: [], backupNeuralPolls: [] });
    g.rows++; if (admitted(l)) g.admitted++;
    if (label(c)) g.classicalLabels++; if (label(l)) g.learnedLabels++;
    if (label(l) && route(l) === 'LNN') g.learnedRouteLabels++;
    if (!label(c) && label(l)) g.rescued++; if (label(c) && !label(l)) g.lost++;
    if (c.status !== 'ACCEPTED' && c.status !== 'EXCLUDED_BY_DESIGN' && c.status !== 'INVALID_INPUT') g.classicalFailures++;
    if (label(c) && label(l) && route(l) === 'LNN' && c.controlPolls > 0) g.pollRatios.push(l.controlPolls / c.controlPolls);
    if (admitted(l) && route(l) === 'CURRENT_BACKUP') g.backupNeuralPolls.push(neural(l));
  }
  const report = { modelId, groups: {} };
  for (const [k, g] of Object.entries(groups)) {
    report.groups[k] = { rows: g.rows, admitted: g.admitted, classicalLabels: g.classicalLabels, learnedLabels: g.learnedLabels, learnedRouteLabels: g.learnedRouteLabels,
      rescued: g.rescued, lost: g.lost, classicalFailures: g.classicalFailures,
      learnedRouteShareOfAdmitted: g.admitted ? +(g.learnedRouteLabels / g.admitted).toFixed(3) : null,
      medianPollRatio: median(g.pollRatios), maxFailedAttemptNeuralPolls: g.backupNeuralPolls.length ? Math.max(...g.backupNeuralPolls) : 0 };
  }
  const h = report.groups.holdout ?? {};
  const all = Object.values(groups);
  const failures = all.reduce((a, g) => a + g.classicalFailures, 0), rescued = all.reduce((a, g) => a + g.rescued, 0);
  report.criteria = {
    a: { rule: 'accepted on the learned route on at least 80 % of admitted in-family holdout rows, at half the classical polls or fewer (median)',
      share: h.learnedRouteShareOfAdmitted, medianPollRatio: h.medianPollRatio, pass: h.learnedRouteShareOfAdmitted >= 0.8 && h.medianPollRatio !== null && h.medianPollRatio <= 0.5 },
    b: { rule: 'rescues at least half of the classical failures (not accepted, not screened)', rescued, classicalFailures: failures, pass: failures > 0 && rescued >= failures / 2 },
    failedAttemptCost: { rule: 'a failed learned attempt costs at most the poll budget', maxNeuralPolls: Math.max(0, ...Object.values(report.groups).map((g) => g.maxFailedAttemptNeuralPolls)) },
  };
  if (outPath) fs.writeFileSync(outPath, JSON.stringify(report, null, 1));
  console.log(JSON.stringify(report, null, 1));
} else {
  throw new Error('usage: qualify.mjs design <out-dir> <seed> | compare <classical-journal> <learned-journal> <model-id> [<out.json>]');
}
