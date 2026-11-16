// The critical_volume sub-record of every bundled dataset (batch 2026-11-15-supercritical-fluids, S3, owner decision
// O10 A): {"volume_m3_per_mol", "estimated", "source", "revision"}, read by the dense-fluid residual viscosity.
//
// Usage: node critical-volume.mjs <dwsim extract dir> <coolprop fluid dir> <materials dir> <out dir> [--write]
//
// Sources, by dataset:
//   chemsep_*        ChemSep v8.3 CriticalVolume (m3/kmol) of the compound the record's own source names (ChemSep index
//                    and CAS), read from the DWSIM extract (Extract-DwsimData.ps1).
//   tjl19_*          the same, by the CAS of selection-regression.json (the six bundled light ends).
//   nitrogen,        1 / STATES.critical.rhomolar of the CoolProp fluid file (revision ae81610e, the files in
//   tjl20_methane    src/test/resources/science/thermo/coolprop/).
//   cubic_water      the record's molar mass over the IAPWS-95 critical density, 322 kg/m3 (the critical point the
//                    record cites).
//   crude_pc01..12   estimated: Vc = Z_RA R Tc / Pc, with the Rackett compressibility Z_RA that reproduces the cut's own
//                    standard liquid density at its standard temperature in the modified Rackett equation of Spencer and
//                    Danner (J. Chem. Eng. Data 17 (1972) 236): V_s = (R Tc / Pc) Z_RA^(1 + (1 - T/Tc)^(2/7)).
//
// It writes one wrapper per record to <out dir> ({id, path, component, critical_volume}) and report.json (the value, the
// cubic's own 3.9513730355914 * 0.07780 R Tc / Pc and their ratio). With --write it inserts each sub-record into its record
// before the top-level "viscosity" key (or, in a record without one, as its last key), keeping the line ends and the
// two-space indentation and changing nothing else (not the record's own revision: the sub-record has its own); every
// write reads back equal with every other key unchanged. Data only: the XML and JSON inputs are parsed, nothing in them runs.
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { readChemSep } from './chemsep.mjs';

const args = process.argv.slice(2);
const write = args.includes('--write');
const [dwsim, coolprop, materials, out] = args.filter((a) => a !== '--write');
if (!dwsim || !coolprop || !materials || !out) {
  console.error('usage: node critical-volume.mjs <dwsim extract dir> <coolprop fluid dir> <materials dir> <out dir> [--write]');
  process.exit(2);
}
const R = 8.31446261815324; // PengRobinsonKernel.GAS_CONSTANT
const CUBIC_RATIO = 3.9513730355914;
const IAPWS95_CRITICAL_DENSITY = 322; // kg/m3
const CHEMSEP_CREDIT = 'ChemSep v8.3 pure component data (Copyright (c) Harry Kooijman and Ross Taylor; Artistic License 2.0), as embedded in DWSIM 10.2.5 (DWSIM.Thermodynamics.dll resource ';
const COOLPROP = 'CoolProp 8.0.0 (github.com/CoolProp/CoolProp ae81610e, MIT)';

const chemsep = readChemSep(dwsim);
const regression = JSON.parse(readFileSync(new URL('./selection-regression.json', import.meta.url), 'utf8'));
const regressionCas = Object.fromEntries(regression.compounds.map((c) => [c.bundled, c.cas]));

function chemsepCompound(cas, index) {
  const found = chemsep.filter((c) => c.cas === cas && (index === undefined || c.index === index));
  if (found.length !== 1) throw new Error('CAS ' + cas + (index ? ' index ' + index : '') + ': ' + found.length + ' ChemSep compounds');
  const c = found[0];
  if (!(c.vc > 0)) throw new Error(c.name + ': no CriticalVolume');
  return c;
}

function chemsepRecord(c) {
  return {
    volume_m3_per_mol: c.vc / 1000, estimated: false,
    source: 'Critical volume ' + c.vc + ' m3/kmol of ' + CHEMSEP_CREDIT + c.file + '), compound "' + c.name + '" (ChemSep index '
      + c.index + ', CAS ' + c.cas + '). Read by the dense-fluid residual viscosity. Written by tools/compound-import/critical-volume.mjs.',
    revision: 'chemsep-8.3-vc-r1',
  };
}

function coolpropRecord(file) {
  const fluid = JSON.parse(readFileSync(join(coolprop, file + '.json'), 'utf8'));
  const rho = fluid.STATES.critical.rhomolar;
  return {
    volume_m3_per_mol: 1 / rho, estimated: false,
    source: 'One over the critical molar density ' + rho + ' mol/m3 of ' + COOLPROP + ' ' + file + '.json STATES.critical. Read by the dense-fluid residual viscosity. Written by tools/compound-import/critical-volume.mjs.',
    revision: 'coolprop-8.0.0-critical-r1',
  };
}

function waterRecord(record) {
  return {
    volume_m3_per_mol: record.molecular_weight_kg_per_mol / IAPWS95_CRITICAL_DENSITY, estimated: false,
    source: 'The record\'s molar mass ' + record.molecular_weight_kg_per_mol + ' kg/mol over the IAPWS-95 critical density '
      + IAPWS95_CRITICAL_DENSITY + ' kg/m3 (the critical point the record cites). Read by the dense-fluid residual viscosity. Written by tools/compound-import/critical-volume.mjs.',
    revision: 'iapws-95-critical-r1',
  };
}

function cutRecord(record) {
  const { critical_temperature_kelvin: tc, critical_pressure_pascal: pc } = record.models.pr78;
  const t = record.standard_temperature_kelvin, rho = record.standard_liquid_density_kg_per_m3, m = record.molecular_weight_kg_per_mol;
  if (!(t < tc)) throw new Error(record.id + ': standard temperature not below Tc');
  const v = m / rho, exponent = 1 + Math.pow(1 - t / tc, 2 / 7);
  const zra = Math.exp(Math.log(v * pc / (R * tc)) / exponent);
  return {
    volume_m3_per_mol: zra * R * tc / pc, estimated: true,
    source: 'Estimated: Z_RA R Tc / Pc with the Rackett compressibility Z_RA ' + zra.toPrecision(6) + ' that reproduces the record\'s standard liquid density '
      + rho + ' kg/m3 at ' + t + ' K in the modified Rackett equation of Spencer and Danner (J. Chem. Eng. Data 17 (1972) 236), '
      + 'V_s = (R Tc / Pc) Z_RA^(1 + (1 - T/Tc)^(2/7)), on the record\'s own Tc, Pc and molar mass. Read by the dense-fluid residual viscosity. Written by tools/compound-import/critical-volume.mjs.',
    revision: 'rackett-standard-density-r1',
  };
}

function subRecord(name, record) {
  if (name.startsWith('chemsep_')) {
    const m = record.source.match(/ChemSep index (\d+), CAS ([0-9-]+)/);
    if (!m) throw new Error(name + ': the source names no ChemSep index and CAS');
    return chemsepRecord(chemsepCompound(m[2], m[1]));
  }
  if (name.startsWith('tjl19_')) {
    if (!regressionCas[name]) throw new Error(name + ': not in selection-regression.json');
    return chemsepRecord(chemsepCompound(regressionCas[name]));
  }
  if (name === 'nitrogen.json') return coolpropRecord('Nitrogen');
  if (name === 'tjl20_methane.json') return coolpropRecord('Methane');
  if (name === 'cubic_water.json') return waterRecord(record);
  if (name.startsWith('crude_pc')) return cutRecord(record);
  throw new Error(name + ': no critical-volume source');
}

function insert(text, record, key, value) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  if (eol === '\r\n' ? /[^\r]\n/.test(text) : text.includes('\r')) throw new Error(record.id + ': mixed line ends');
  if (key in record) throw new Error(record.id + ': already has ' + key);
  const block = JSON.stringify(value, null, 2).split('\n').map((l, i) => i === 0 ? l : '  ' + l).join(eol);
  const marker = eol + '  "viscosity": {';
  let result;
  if (text.split(marker).length === 2) result = text.replace(marker, eol + '  "' + key + '": ' + block + ',' + marker);
  else if (!text.includes(marker)) {
    const end = text.lastIndexOf(eol + '}');
    if (end < 0 || text.slice(end + eol.length + 1).trim() !== '') throw new Error(record.id + ': cannot find the record\'s end');
    result = text.slice(0, end) + ',' + eol + '  "' + key + '": ' + block + text.slice(end);
  } else throw new Error(record.id + ': more than one top-level viscosity line');
  const check = JSON.parse(result);
  if (JSON.stringify(check[key]) !== JSON.stringify(value)) throw new Error(record.id + ': round trip');
  const others = (o) => JSON.stringify(Object.fromEntries(Object.entries(o).filter(([k]) => k !== key)));
  if (others(check) !== others(record)) throw new Error(record.id + ': other content moved');
  if (eol === '\r\n' ? /[^\r]\n/.test(result) : result.includes('\r')) throw new Error(record.id + ': line ends changed');
  return result;
}

mkdirSync(out, { recursive: true });
const report = [];
let written = 0;
for (const name of readdirSync(join(materials, 'properties')).filter((n) => n.endsWith('.json')).sort()) {
  const path = join(materials, 'properties', name), text = readFileSync(path, 'utf8'), record = JSON.parse(text);
  const value = subRecord(name, record);
  const pr = record.models.pr78;
  const cubic = CUBIC_RATIO * 0.07780 * R * pr.critical_temperature_kelvin / pr.critical_pressure_pascal;
  report.push({ id: record.id, file: name, volume_m3_per_mol: value.volume_m3_per_mol, cubic_m3_per_mol: cubic,
    ratio: value.volume_m3_per_mol / cubic, estimated: value.estimated, revision: value.revision });
  writeFileSync(join(out, name), JSON.stringify({ id: record.id, path: 'properties/' + name, component: record.component, critical_volume: value }, null, 2) + '\n');
  if (write) {
    const result = insert(text, record, 'critical_volume', value);
    if (result !== text) { writeFileSync(path, result); written++; }
  }
}
writeFileSync(join(out, 'report.json'), JSON.stringify(report, null, 2) + '\n');
for (const r of report) console.log(r.file.padEnd(30), (r.volume_m3_per_mol * 1e6).toFixed(2).padStart(9), 'cm3/mol  cubic', (r.cubic_m3_per_mol * 1e6).toFixed(2).padStart(9), ' ratio', r.ratio.toFixed(3), r.estimated ? 'estimated' : '');
console.log(report.length + ' records' + (write ? ', ' + written + ' written' : ''));
