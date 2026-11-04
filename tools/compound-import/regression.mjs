// Regression check of the import against the bundled light-end records (properties/tjl19_*.json, DWSIM 10.2.3
// ChemSep values), the tjl20 pair library and the NIST liquid calibration points.
//
// Usage: node regression.mjs <dwsim-data dir> <selection-regression.json> <import out dir> <materials dir>
// Run import.mjs with the same selection into <import out dir> first. Prints every difference with its size.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { readChemSep, evaluate } from './chemsep.mjs';

const [dwsimDir, selectionPath, importDir, materialsDir] = process.argv.slice(2);
if (!materialsDir) { console.error('usage: node regression.mjs <dwsim-data> <selection> <import out> <materials dir>'); process.exit(2); }
const selection = JSON.parse(readFileSync(selectionPath, 'utf8'));
const read = (p) => JSON.parse(readFileSync(p, 'utf8'));
const byCas = new Map(); for (const c of readChemSep(dwsimDir)) if (!byCas.has(c.cas)) byCas.set(c.cas, c);
const rel = (a, b) => (b === 0 ? (a === 0 ? 0 : Infinity) : a / b - 1);
const fmt = (x) => (x === 0 ? '0' : x.toExponential(2));
const shifted = (c, t) => { const d = t - 298.15; return c[0] + d * (c[1] + d * (c[2] + d * (c[3] + d * (c[4] + d * c[5])))); };
let differences = 0;
const say = (line) => { console.log(line); differences++; };

for (const s of selection.compounds) {
  const ours = read(join(importDir, 'properties', 'regression_' + s.file + '.json'));
  const bundled = read(join(materialsDir, 'properties', s.bundled));
  const lines = [];
  const scalar = (label, a, b) => { if (a !== b) lines.push(`${label}: ${a} vs ${b} (${fmt(rel(a, b))})`); };
  scalar('molecular_weight', ours.molecular_weight_kg_per_mol, bundled.molecular_weight_kg_per_mol);
  scalar('normal_boiling_point', ours.normal_boiling_point_kelvin, bundled.normal_boiling_point_kelvin);
  scalar('standard_liquid_density', ours.standard_liquid_density_kg_per_m3, bundled.standard_liquid_density_kg_per_m3);
  scalar('standard_temperature', ours.standard_temperature_kelvin, bundled.standard_temperature_kelvin);
  scalar('standard_pressure', ours.standard_pressure_pascal, bundled.standard_pressure_pascal);
  scalar('temperature_min', ours.temperature_min_kelvin, bundled.temperature_min_kelvin);
  scalar('temperature_max', ours.temperature_max_kelvin, bundled.temperature_max_kelvin);
  for (const k of ['temperature_min_kelvin', 'temperature_max_kelvin', 'pressure_min_pascal', 'pressure_max_pascal'])
    scalar('fluid_domain.' + k, ours.fluid_domain[k], bundled.fluid_domain[k]);
  for (const k of ['critical_temperature_kelvin', 'critical_pressure_pascal', 'acentric_factor'])
    scalar('pr78.' + k, ours.models.pr78[k], bundled.models.pr78[k]);
  const oc = ours.ideal_gas_cp.coefficients, bc = bundled.ideal_gas_cp.coefficients;
  const coefficientGap = Math.max(...oc.map((x, i) => Math.abs(rel(x, bc[i]))));
  let curveGap = 0, chemsepGap = 0;
  const c = byCas.get(s.cas);
  for (let t = 293.15; t <= 900; t += 0.5) {
    curveGap = Math.max(curveGap, Math.abs(rel(shifted(oc, t), shifted(bc, t))));
    if (t >= 298.15) chemsepGap = Math.max(chemsepGap, Math.abs(rel(shifted(bc, t), evaluate(c.cp, t) / 1000)));
  }
  if (coefficientGap > 0) lines.push(`ideal_gas_cp: coefficients up to ${fmt(coefficientGap)}, Cp curve up to ${fmt(curveGap)} over 293.15..900 K `
    + `(bundled fit vs ChemSep eq. ${c.cp.eqno}: ${fmt(chemsepGap)})`);
  for (const phase of ['liquid', 'vapor']) {
    const o = ours.viscosity[phase], b = bundled.viscosity[phase];
    for (const k of ['type', 'temperature_min_kelvin', 'temperature_max_kelvin', 'pressure_min_pascal', 'pressure_max_pascal', 'reference_temperature_kelvin', 'estimated'])
      if (o[k] !== b[k]) lines.push(`viscosity.${phase}.${k}: ${o[k]} vs ${b[k]}`);
    const sameNodes = o.temperatures_kelvin.length === b.temperatures_kelvin.length && o.temperatures_kelvin.every((t, i) => t === b.temperatures_kelvin[i]);
    if (!sameNodes) lines.push(`viscosity.${phase}: ${o.temperatures_kelvin.length} nodes vs ${b.temperatures_kelvin.length}, node sets differ`);
    else {
      const gap = Math.max(...o.coefficients.map((x, i) => Math.abs(rel(x, b.coefficients[i]))));
      if (gap > 0) lines.push(`viscosity.${phase}: same ${o.temperatures_kelvin.length} nodes, values up to ${fmt(gap)}`);
    }
  }
  console.log(`${s.id} (${s.bundled}): ${lines.length ? lines.length + ' differences' : 'identical'}`);
  for (const l of lines) say('  ' + l);
}

// Pairs among the six: the D3 rule against the frozen tjl20 library.
const ours = read(join(importDir, 'interactions', selection.pair_library.file + '.json')).pairs;
const frozen = read(join(materialsDir, 'interactions', 'tjl20.json')).pairs;
const key = (p) => [p.first, p.second].sort().join('/');
const mine = new Map(ours.map((p) => [key(p), p])), theirs = new Map(frozen.map((p) => [key(p), p]));
console.log(`pairs: import ${ours.length}, tjl20 ${frozen.length}`);
for (const k of [...new Set([...mine.keys(), ...theirs.keys()])].sort()) {
  const a = mine.get(k), b = theirs.get(k);
  if (a && b && a.kij === b.kij) continue;
  say(`  ${k}: import ${a ? a.kij + ' [' + a.source + ']' : 'none'}, tjl20 ${b ? b.kij : 'none'}`);
}

// Liquid volume: ChemSep eq. 105 saturated liquid at each NIST calibration point's temperature.
const nist = read(join(materialsDir, 'transport', 'liquid_calibration.json')).points;
console.log('NIST calibration points (2 MPa compressed liquid) vs ChemSep eq. 105 saturated liquid at the same T:');
for (const s of selection.compounds) {
  const p = nist.find((x) => x.component === s.id), c = byCas.get(s.cas);
  if (!p) continue;
  const v = 1 / (evaluate(c.rho, p.temperatureKelvin) * 1000);
  console.log(`  ${s.id} ${p.temperatureKelvin} K: NIST ${p.molarVolumeCubicMetres}, eq. 105 ${v} (${fmt(rel(v, p.molarVolumeCubicMetres))})`);
}
console.log(`${differences} differences`);
