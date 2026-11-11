// Reference values for ModifiedUnifacTest (batch 2026-11-11-aqueous-solvents, W2a): modified UNIFAC (Dortmund) ln gamma
// of ethanol-water, ethanol-n-hexane and water-n-hexane (table as published, and with the water-hydrocarbon block) at
// 298.15 K and 373.15 K, three compositions each, and the IAPWS G7-04 Henry constants of CO2 and N2 in water.
// The dortmund function is copied from group-derived-pairs.mjs, with the subgroups in table number order (as the Java
// model sums them); the Henry form takes water's saturation pressure from the bundled water model's Wagner-Pruss
// coefficients, as HenryConstant does. It also checks that the bundled group_interactions record carries the same
// subgroups and pairs as DWSIM's files for these compounds, and refits the block (fitBlock) to show its rounding.
// Usage: node tools/aqueous-solvents/dortmund-values.mjs <dir with DWSIM's modfac.txt and modfac_ip.txt>
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const dir = process.argv[2]; if (!dir) throw new Error('usage: node dortmund-values.mjs <dwsim data dir>');
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'main', 'resources', 'data', 'icheme', 'materials');

// Group tables, read as group-derived-pairs.mjs reads them, keyed by subgroup name (the first of a repeated name).
const SUB = {}, IP = {};
for (const line of readFileSync(`${dir}/modfac.txt`, 'utf8').split(/\r?\n/).slice(1)) {
  const f = line.split(';'); if (f.length > 5 && !(f[2] in SUB)) SUB[f[2]] = { number: +f[3], main: +f[0], R: +f[4], Q: +f[5] };
}
for (const line of readFileSync(`${dir}/modfac_ip.txt`, 'utf8').split(/\r?\n/)) {
  if (!line.trim() || line.startsWith("'")) continue;
  const f = line.trim().split(/\s+/).map(Number); IP[`${f[0]}-${f[1]}`] = f.slice(2, 5); IP[`${f[1]}-${f[0]}`] = f.slice(5, 8);
}
const COMP = { ethanol: { CH3: 1, CH2: 1, OH: 1 }, water: { H2O: 1 }, hexane: { CH3: 2, CH2: 4 }, benzene: { ACH: 6 } };
// The block as the bundled record carries it (rounded to 0.1 K).
const BLOCK = { '1-7': [682.8, 0, 0], '7-1': [516.0, 0, 0], '3-7': [413.3, 0, 0], '7-3': [640.4, 0, 0] };

// The bundled record must agree with the files on what these compounds use.
const record = JSON.parse(readFileSync(join(ROOT, 'group_interactions', 'modified_unifac_dortmund.json'), 'utf8'));
for (const name of new Set(Object.values(COMP).flatMap(Object.keys))) {
  const s = record.subgroups.find((g) => g.name === name);
  if (!s || s.main !== SUB[name].main || s.r !== SUB[name].R || s.q !== SUB[name].Q || s.number !== SUB[name].number) throw new Error('record differs at subgroup ' + name);
}
for (const [m, k] of [[1, 5], [1, 7], [5, 7], [1, 3], [3, 7], [3, 5]]) {
  const p = record.pairs.find((q) => q.first === m && q.second === k);
  if (!p || [p.a, p.b, p.c].some((v, i) => v !== IP[`${m}-${k}`][i]) || [p.a_reverse, p.b_reverse, p.c_reverse].some((v, i) => v !== IP[`${k}-${m}`][i])) throw new Error(`record differs at pair ${m}-${k}`);
}
for (const p of record.water_hydrocarbon_block.pairs) {
  if (BLOCK[`${p.first}-${p.second}`][0] !== p.a || BLOCK[`${p.second}-${p.first}`][0] !== p.a_reverse) throw new Error('record differs at block pair ' + p.first);
}

// Modified UNIFAC (Dortmund): ln gamma of each compound of `names` at composition x (from group-derived-pairs.mjs;
// subgroups sorted by number).
function dortmund(names, x, T, residualOnly = false, block = null) {
  const subs = [...new Set(names.flatMap((n) => Object.keys(COMP[n])))].sort((a, b) => SUB[a].number - SUB[b].number);
  const nu = names.map((n) => subs.map((s) => COMP[n][s] ?? 0)), Q = subs.map((s) => SUB[s].Q), main = subs.map((s) => SUB[s].main);
  const psi = (m, k) => { if (main[m] === main[k]) return 1; const p = block?.[`${main[m]}-${main[k]}`] ?? IP[`${main[m]}-${main[k]}`]; if (!p) throw new Error(`no group pair ${main[m]}-${main[k]}`); return Math.exp(-(p[0] + p[1] * T + p[2] * T * T) / T); };
  const lnGamma = (comp) => {
    const X = subs.map((_, k) => comp.reduce((s, c, i) => s + c * nu[i][k], 0)), tot = X.reduce((s, v, k) => s + Q[k] * v, 0), theta = X.map((v, k) => Q[k] * v / tot);
    const den = subs.map((_, m) => theta.reduce((s, t, n) => s + t * psi(n, m), 0));
    return subs.map((_, k) => Q[k] * (1 - Math.log(den[k]) - theta.reduce((s, t, m) => s + t * psi(k, m) / den[m], 0)));
  };
  const mix = lnGamma(x), r = nu.map((row) => row.reduce((s, v, k) => s + v * SUB[subs[k]].R, 0)), q = nu.map((row) => row.reduce((s, v, k) => s + v * Q[k], 0));
  const sr = x.reduce((s, v, i) => s + v * r[i], 0), sq = x.reduce((s, v, i) => s + v * q[i], 0), s34 = x.reduce((s, v, i) => s + v * r[i] ** 0.75, 0);
  return names.map((_, i) => {
    const pure = lnGamma(names.map((__, j) => (j === i ? 1 : 0))), V = r[i] / sr, F = q[i] / sq, Vp = r[i] ** 0.75 / s34;
    return (residualOnly ? 0 : 1 - Vp + Math.log(Vp) - 5 * q[i] * (1 - V / F + Math.log(V / F))) + nu[i].reduce((s, v, k) => s + v * (mix[k] - pure[k]), 0);
  });
}

console.log('modified UNIFAC (Dortmund) ln gamma: pair, block, T K, x1, ln gamma1, ln gamma2 (17 significant digits)');
const rows = [];
for (const [a, b, block] of [['ethanol', 'water', null], ['ethanol', 'hexane', null], ['water', 'hexane', null], ['water', 'hexane', BLOCK]]) {
  for (const T of [298.15, 373.15]) for (const x1 of [0.1, 0.5, 0.9]) {
    const g = dortmund([a, b], [x1, 1 - x1], T, false, block);
    rows.push(`{"${a}", "${b}", ${block ? 'true' : 'false'}, ${T}, ${x1}, ${g[0].toPrecision(17)}, ${g[1].toPrecision(17)}}`);
  }
}
console.log(rows.join(',\n'));

// The block refitted at full precision (fitBlock of group-derived-pairs.mjs), to show the record's 0.1 K rounding.
const dilute = (hc, block, T = 298.15) => [dortmund(['water', hc], [1, 0], T, false, block)[1], dortmund(['water', hc], [0, 1], T, false, block)[0]];
function fitBlock(hc, hcInWater, waterInHc, [m, k]) {
  const target = [-Math.log(hcInWater), -Math.log(waterInHc)]; let v = [1391.3 - 3.6156 * 298.15 + 1.144e-3 * 298.15 ** 2, -17.253 + 0.8389 * 298.15 + 9.021e-4 * 298.15 ** 2];
  const blockOf = (u) => ({ [`${m}-${k}`]: [u[0], 0, 0], [`${k}-${m}`]: [u[1], 0, 0] }), miss = (u) => dilute(hc, blockOf(u)).map((g, i) => g - target[i]);
  for (let it = 0; it < 80; it++) {
    const r = miss(v); if (Math.max(Math.abs(r[0]), Math.abs(r[1])) < 1e-10) break;
    const h = 0.01, c0 = miss([v[0] + h, v[1]]).map((g, i) => (g - r[i]) / h), c1 = miss([v[0], v[1] + h]).map((g, i) => (g - r[i]) / h), det = c0[0] * c1[1] - c1[0] * c0[1];
    let d = [-(r[0] * c1[1] - c1[0] * r[1]) / det, -(c0[0] * r[1] - r[0] * c0[1]) / det]; const big = Math.max(Math.abs(d[0]), Math.abs(d[1])); if (big > 200) d = d.map((q) => q * 200 / big);
    v = [v[0] + d[0], v[1] + d[1]];
  }
  return v;
}
const alkyl = fitBlock('hexane', 2.5e-6, 5.3e-4, [1, 7]), aromatic = fitBlock('benzene', 4.1e-4, 3.0e-3, [3, 7]);
console.log(`\nblock refitted: CH2-H2O ${alkyl[0].toFixed(3)} K, H2O-CH2 ${alkyl[1].toFixed(3)} K; ACH-H2O ${aromatic[0].toFixed(3)} K, H2O-ACH ${aromatic[1].toFixed(3)} K (record: 682.8, 516.0, 413.3, 640.4)`);
const sol = dilute('hexane', BLOCK);
console.log(`with the record's block at 25 C: hexane in water ${Math.exp(-sol[0]).toExponential(3)}, water in hexane ${Math.exp(-sol[1]).toExponential(3)} (fitted to 2.5e-6, 5.3e-4)`);

// IAPWS G7-04 Henry constants, p* from the bundled water model's Wagner-Pruss coefficients (V3WaterProperties.wagnerPruss).
const water = JSON.parse(readFileSync(join(ROOT, 'water', 'water.json'), 'utf8'));
function wagnerPruss(T) {
  const a = water.saturation_coefficients, tc = water.critical_temperature, theta = 1 - T / tc, root = Math.sqrt(theta), cube = theta * theta * theta;
  const poly = a[0] * theta + a[1] * (theta * root) + a[2] * theta * theta * theta + a[3] * (cube * root) + a[4] * theta * theta * theta * theta + a[5] * (cube * cube * theta * root);
  return water.critical_pressure * Math.exp(tc / T * poly);
}
const henry = ([A, B, C], T) => { const tr = T / 647.096, tau = 1 - tr; return wagnerPruss(T) * Math.exp(A / tr + B * tau ** 0.355 / tr + C * tr ** -0.41 * Math.exp(tau)); };
console.log('\nIAPWS G7-04 Henry constants in water, Pa (mole-fraction basis)');
for (const [gas, file] of [['CO2', 'chemsep_carbon_dioxide.json'], ['N2', 'nitrogen.json']]) {
  const k = JSON.parse(readFileSync(join(ROOT, 'properties', file), 'utf8')).henry_in_water.coefficients;
  for (const T of [298.15, 373.15]) console.log(`  ${gas} at ${T} K: ${henry(k, T).toPrecision(17)} (p* ${wagnerPruss(T).toPrecision(17)} Pa)`);
}
