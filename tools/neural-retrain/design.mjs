// N1 request design of batch 2026-11-05-neural-retrain (NEURAL_RETRAIN_DATA.md section 2).
// Writes design rows only: grade weights, compound multipliers, zero mask and operating factors. The Java probe
// (NeuralRetrainProbe) realises each row on the four grade presets, applies the generation screens and solves it.
// Run: node tools/neural-retrain/design.mjs <out-dir> [seed]  -> train.jsonl, validation.jsonl, pilot.jsonl, panel.jsonl
import fs from 'node:fs';
import path from 'node:path';

const out = process.argv[2] ?? 'tmp/neural-retrain/design';
const seed = Number(process.argv[3] ?? 20261105);
export const GRADES = ['light_crude', 'medium_crude', 'heavy_crude', 'oil_sand_dilbit'];
export const COMPONENTS = 19; // tjl20_methane: C1..nC5, crude_pc01..12
const C1C2 = [0, 1];
// Off-axis compounds of icheme:refinery (N2 and the 24 refinery compounds), less CO2 (E25: the column refuses crystals) and
// oxygen (no crude column carries free oxygen: a sense rule at generation).
export const OFF_AXIS = ["Nitrogen", "Hydrogen", "HydrogenSulfide", "CarbonMonoxide", "Ammonia", "SulfurDioxide", "CarbonylSulfide",
  "Ethylene", "Propylene", "Butene-1", "Isobutene", "Cis-2-butene", "Trans-2-butene", "Butadiene", "N-hexane", "Cyclohexane", "Benzene",
  "Toluene", "Ethylbenzene", "O-xylene", "M-xylene", "P-xylene", "MethylMercaptan", "EthylMercaptan"];

function mulberry32(a) {
  return () => { a |= 0; a = (a + 0x6D2B79F5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}
const rand = mulberry32(seed);
const uniform = (a, b) => a + (b - a) * rand();
function normal() { let u = 0; while (u === 0) u = rand(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rand()); }
function gamma(alpha) { // Marsaglia-Tsang, with the alpha < 1 boost
  if (alpha < 1) return gamma(alpha + 1) * Math.pow(rand() || 1e-300, 1 / alpha);
  const d = alpha - 1 / 3, c = 1 / Math.sqrt(9 * d);
  for (;;) { let x, v; do { x = normal(); v = 1 + c * x; } while (v <= 0);
    v = v * v * v; const u = rand(); if (u < 1 - 0.0331 * x ** 4 || Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v; }
}
function pick(weights) { let r = rand() * weights.reduce((a, b) => a + b, 0); for (let i = 0; i < weights.length; i++) { r -= weights[i]; if (r <= 0) return i; } return weights.length - 1; }

// Stage layouts: the presets' 40 trays, plus the same layout scaled (trays, feed tray, draws, pumparounds).
const STAGES = [40, 32, 48, 24, 56], STAGE_WEIGHTS = [0.5, 0.15, 0.15, 0.1, 0.1];

function composition() {
  const common = rand() < 1 / 3; // pure grades and pairwise blends, unperturbed: the commonest game inputs
  let w = [0, 0, 0, 0], kind;
  if (common) {
    const a = Math.floor(rand() * 4);
    if (rand() < 0.5) { w[a] = 1; kind = 'pure'; }
    else { let b; do { b = Math.floor(rand() * 4); } while (b === a); const t = rand(); w[a] = t; w[b] = 1 - t; kind = 'pair'; }
  } else {
    const g = GRADES.map(() => gamma(0.5)); const s = g.reduce((x, y) => x + y, 0); w = g.map((x) => x / s); kind = 'dirichlet';
  }
  const multipliers = Array.from({ length: COMPONENTS }, () => (common ? 1 : Math.exp(0.25 * normal())));
  const zeroC1C2 = !common && rand() < 0.05;
  const result = { kind, weights: w, multipliers, zero: zeroC1C2 ? C1C2 : [] };
  // Up to 5 mol % of off-axis world-list compounds on about a third of all rows (NEURAL_RETRAIN_DATA.md section 5).
  if (!common && rand() < 0.5) {
    const count = 1 + Math.floor(rand() * 3), picked = new Set();
    while (picked.size < count) picked.add(OFF_AXIS[Math.floor(rand() * OFF_AXIS.length)]);
    result.injection = { compounds: [...picked], fraction: uniform(0, 0.05) };
  }
  return result;
}

function operating() {
  return {
    stages: STAGES[pick(STAGE_WEIGHTS)],
    feedTemperatureKelvin: uniform(615, 643), // CDU heater outlet below cracking (about 370 C)
    topPressurePascal: uniform(180e3, 320e3),
    refluxRatio: uniform(3.0, 5.5),
    condenserTemperatureKelvin: uniform(320, 345),
    feedRateFactor: uniform(0.5, 1.05), // turndown: steam, draws and duties are per feed (column-operation setpoints)
    steamFactor: uniform(0.6, 1.4), steamTemperatureKelvin: uniform(510, 560),
    drawFactors: [0, 1, 2].map(() => uniform(0.7, 1.15)),
    pumparoundFactors: [0, 1, 2].map(() => uniform(0.7, 1.25)),
    dropDraw: rand() < 0.1 ? Math.floor(rand() * 3) : -1,
    dropPumparound: rand() < 0.1 ? Math.floor(rand() * 3) : -1,
    diameterMetres: uniform(6.5, 9.5),
  };
}

const rows = { train: [], validation: [] };
for (const [split, count] of [['train', 6000], ['validation', 550]])
  for (let i = 0; i < count; i++) rows[split].push({ id: `${split}-${String(i).padStart(5, '0')}`, split, composition: composition(), operating: operating() });
const pilot = rows.train.filter((_, i) => i % 20 === 0); // the 5 % pilot (guide section 5): a fixed subset of TRAIN
// VDU rows (section 6, case A): each takes the bottoms of a strict CDU label, picked by sourceRank in the labels journal.
const vduRow = (split, i) => ({ id: `vdu-${split}-${String(i).padStart(5, "0")}`, split, unit: "vdu", composition: { kind: "cdu-bottoms" }, operating: {
  stages: 8 + Math.floor(rand() * 8), topPressurePascal: uniform(1.3e3, 8e3), feedTemperatureKelvin: uniform(615, 683),
  cut1Factor: uniform(0.6, 1.2), cut2Factor: uniform(0.6, 1.2), steamFactor: uniform(0.5, 1.5), pumparoundFactor: uniform(1.0, 1.6), // pilot r2: 1 label in 20 rows at 0.6x the accepted 25 MW, 10 in 16 at 1.2x
  diameterMetres: uniform(12, 15), sourceRank: rand() } });
const vdu = { train: Array.from({ length: 1500 }, (_, i) => vduRow("train", i)), validation: Array.from({ length: 150 }, (_, i) => vduRow("validation", i)) };
const vduPilot = vdu.train.filter((_, i) => i % 20 === 0);
const panel = GRADES.map((g, i) => ({ id: `panel-${g}`, split: 'panel', composition: { kind: 'preset', weights: GRADES.map((_, k) => (k === i ? 1 : 0)), multipliers: Array(COMPONENTS).fill(1), zero: [] }, operating: { preset: true } }));

fs.mkdirSync(out, { recursive: true });
const write = (name, list) => fs.writeFileSync(path.join(out, name), list.map((r) => JSON.stringify(r)).join('\n') + '\n');
write('train.jsonl', rows.train); write('validation.jsonl', rows.validation); write('pilot.jsonl', pilot); write('panel.jsonl', panel);
write("vdu-train.jsonl", vdu.train); write("vdu-validation.jsonl", vdu.validation); write("vdu-pilot.jsonl", vduPilot);
const tally = (list, f) => list.reduce((m, r) => ((m[f(r)] = (m[f(r)] ?? 0) + 1), m), {});
console.log(JSON.stringify({ seed, train: rows.train.length, validation: rows.validation.length, pilot: pilot.length,
  composition: tally(rows.train, (r) => r.composition.kind), stages: tally(rows.train, (r) => r.operating.stages),
  pilotStages: tally(pilot, (r) => r.operating.stages), pilotComposition: tally(pilot, (r) => r.composition.kind), injected: rows.train.filter((r) => r.composition.injection).length, vdu: vdu.train.length, vduPilot: vduPilot.length }));
