// O2 of batch 2026-11-04-open-thermo-data: re-derives the bundled values that came from the NIST WebBook from open
// implementations of the same correlations, prints how far each moves, and with --write writes the records and test
// references. Run from this folder with Node 22: `node derive.mjs` (measure only) or `node derive.mjs --write`.
//
// Inputs: the CoolProp 8.0.0 fluid files (MIT), the NASA CEA extract (Apache-2.0), CoolProp master's ATcT formation
// enthalpies (MIT), all in the main checkout's research/2026-09-24-coolprop-low-temperature/sources/; the bundled pilot
// nitrogen record (CoolProp 8.0.0 viscosity tables); and tmp/open-thermo-nist/oracle.json, written by
// OpenThermoOracleProbe.java (CoolProp equations of state through the Java Helmholtz oracle). The record forms are
// unchanged, so no Java change is needed.
//
// O7 (decision E16): the methane Cp is fitted to the bundled reference spine (spine/methane.json) instead of the
// Setzmann-Wagner alpha0 alone. `node derive.mjs --methane-only [--write]` runs only that part (sections 2 and 7): the
// other sections are the O2 one-shot and stop at their replaceOnce guards on the already-written records.
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import * as L from './lib.mjs';

const write = process.argv.includes('--write');
const methaneOnly = process.argv.includes('--methane-only');
const ORACLE = `${L.WORKTREE}/tmp/open-thermo-nist/oracle.json`;
const REPORT = `${L.WORKTREE}/tmp/open-thermo-nist/derive-report.json`;
const REFERENCE = `${L.WORKTREE}/src/test/resources/fluid/reference`;
const EXAMPLE = `${L.WORKTREE}/examples/material-override/data/icheme/materials/properties/tjl20_methane.json`;
const COOLPROP = 'CoolProp 8.0.0 (github.com/CoolProp/CoolProp ae81610e, MIT)';
const COOLPROP_URL = name => `https://raw.githubusercontent.com/CoolProp/CoolProp/ae81610e7d23efc57f9d051c8e70a4d66e87537f/dev/fluids/${name}.json`;
const oracle = L.readJson(ORACLE);
const atct = L.readJson(`${L.SOURCES}/coolprop/hformation-atct-9b35f538.json`).fluids;
const report = {};
const at = (f, ts = [300, 500, 800]) => Object.fromEntries(ts.map(t => [t, +f(t).toFixed(5)]));
const stats = (oldF, newF, lo, hi) => { const c = L.compare(newF, oldF, lo, hi, 0.5); return { maxPercent: +(100 * c.max).toFixed(4), atKelvin: +c.at.toFixed(2), rmsPercent: +(100 * c.rms).toFixed(4) }; };
const text = f => fs.readFileSync(f, 'utf8').replace(/\r\n/g, '\n');
const out = (f, s) => { if (write) L.writeLike(f, s); };

// ---- 2. Methane ideal-gas Cp (O7, decision E16): the bundled reference spine as it joins its segments (spine/methane.json:
// CoolProp Methane alpha0, Setzmann and Wagner 1991, to 425 K; the GERG-2008 ideal part, Jaeschke and Schley 1995, from 425
// to 1200 K; decision D13 of 2026-09-24), fitted over 298.15..900 K as O2 did. Compared with the record before O2
// (03e7b6138, the NIST Shomate fit) and with O2's Setzmann-Wagner fit (8fcf698d6).
const CH4_FILE = `${L.MATERIALS}/properties/tjl20_methane.json`;
const CH4_REVISION = 'tjl20-methane-spine-r3';
const atCommit = (sha, file) => JSON.parse(execFileSync('git', ['-C', L.WORKTREE, 'show', `${sha}:${file.slice(L.WORKTREE.length + 1)}`], { encoding: 'utf8' }));
const ch4Spine = L.spineCp('methane');
const ch4PreO2 = L.shiftedPolynomial(atCommit('03e7b6138', CH4_FILE).ideal_gas_cp.coefficients);
const ch4O2 = L.shiftedPolynomial(atCommit('8fcf698d6', CH4_FILE).ideal_gas_cp.coefficients);
const ch4Coefficients = L.fitShiftedPolynomial5(ch4Spine.cp, 298.15, 900);
const ch4New = L.shiftedPolynomial(ch4Coefficients);
const ch4Fit = L.compare(ch4New, ch4Spine.cp, 298.15, 900, 0.1);
const ch4At = [300, 500, 800, 900];
report.methaneCp = { source: `spine ${ch4Spine.revision} (${ch4Spine.segments.map(s => `${s.revision} ${s.lo}..${s.hi} K`).join('; ')})`,
  fitWorstPercent: +(100 * ch4Fit.max).toFixed(4), fitWorstAt: +ch4Fit.at.toFixed(2),
  againstPreO2: stats(ch4PreO2, ch4New, 298.15, 900), againstO2: stats(ch4O2, ch4New, 298.15, 900),
  preO2: at(ch4PreO2, ch4At), o2: at(ch4O2, ch4At), spine: at(ch4Spine.cp, ch4At), new: at(ch4New, ch4At),
  ceaAgainstSpinePercent: stats(ch4Spine.cp, L.ceaSpecies('CH4').cp, 298.15, 900) };

// ---- 7. The methane record (and the override example, the same record with its density edited).
const EXAMPLE_REVISION = `${CH4_REVISION}-example-density`;
const coefficientsBlock = (s, coefficients, label) => {
  const m = s.match(/("ideal_gas_cp": \{[^}]*?"coefficients": \[\n)([^\]]*?)(\n\s*\])/);
  if (!m) throw new Error(`${label}: ideal_gas_cp coefficients not found`);
  const indent = m[2].match(/^(\s*)/)[1];
  return s.replace(m[0], m[1] + coefficients.map(c => indent + String(c)).join(',\n') + m[3]);
};
for (const [file, to] of [[CH4_FILE, CH4_REVISION], [EXAMPLE, EXAMPLE_REVISION]]) {
  let s = text(file);
  const from = JSON.parse(s).revision;
  if (from !== to) s = L.replaceOnce(s, `"revision": "${from}"`, `"revision": "${to}"`, file);
  s = coefficientsBlock(s, ch4Coefficients, file);
  out(file, s);
}
if (methaneOnly) {
  fs.writeFileSync(`${L.WORKTREE}/tmp/open-thermo-o7/methane-cp-report.json`, JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
  console.log(write ? 'written (methane only)' : 'measure only (pass --write to write the records)');
  process.exit(0);
}

// ---- 1. Nitrogen ideal-gas Cp: CoolProp Nitrogen alpha0 (Span et al. 2000), 273.16..900 K, held below (D37/S4).
const N2_FILE = `${L.MATERIALS}/properties/nitrogen.json`;
const n2 = JSON.parse(text(N2_FILE));
const n2Ideal = L.coolpropIdealGas('Nitrogen');
const n2Old = L.shiftedPolynomial(n2.ideal_gas_cp.coefficients);
// Pinned at 273.16 K: the held value below it is the reference Cp there (D37/S4 holds the fit's 273.16 K value).
const n2Coefficients = L.fitShiftedPolynomial5(n2Ideal.cp, 273.16, 900, 1201, { pinLow: true });
const n2New = L.shiftedPolynomial(n2Coefficients);
const n2Fit = L.compare(n2New, n2Ideal.cp, 273.16, 900, 0.1);
const held = n2New(273.16);
const heldWorst = L.compare(() => held, n2Ideal.cp, 63.151, 273.16, 0.25);
report.nitrogenCp = { fitWorstPercent: +(100 * n2Fit.max).toFixed(4), heldValue: +held.toFixed(4), heldWorstPercentBelow273: +(100 * heldWorst.max).toFixed(4),
  heldWorstAt: +heldWorst.at.toFixed(2), oldHeld: +n2Old(273.16).toFixed(4), change: stats(n2Old, n2New, 273.16, 900), old: at(n2Old), new: at(n2New) };

// ---- 2. Methane: at the top since O7 (the spine). O2 fitted the CoolProp Methane alpha0 (Setzmann and Wagner 1991) alone.

// ---- 3. Water vapour: Shomate form fitted to the IAPWS-95 ideal-gas Cp (CoolProp Water alpha0), 273.16..900 K;
// F and H from the ATcT formation enthalpy so that h(298.15 K) = 0 exactly (the record's datum).
const W_FILE = `${L.MATERIALS}/water/water.json`;
const water = JSON.parse(text(W_FILE));
const wIdeal = L.coolpropIdealGas('Water');
const wOld = L.shomateCp(water.shomate_coefficients);
const abcde = L.fitShomate(wIdeal.cp, 273.16, 900);
const hf = atct.Water.standard_state.hmolar_formation.value / 1000; // kJ/mol
const t0 = 298.15 / 1000;
const p0 = abcde[0] * t0 + abcde[1] * t0 ** 2 / 2 + abcde[2] * t0 ** 3 / 3 + abcde[3] * t0 ** 4 / 4 - abcde[4] / t0;
const shomate = [...abcde, hf - p0, -hf];
const wNew = L.shomateCp(shomate);
const wFit = L.compare(wNew, wIdeal.cp, 273.16, 900, 0.1);
const hOld = L.shomateEnthalpy(water.shomate_coefficients), hNew = L.shomateEnthalpy(shomate);
let dhWorst = 0;
for (let T = 273.16; T <= 900; T += 0.5) dhWorst = Math.max(dhWorst, Math.abs(hNew(T) - hOld(T)));
const sOld = L.shomateEntropyShape(water.shomate_coefficients), sNew = L.shomateEntropyShape(shomate);
let dsWorst = 0;
for (let T = 273.16; T <= 900; T += 0.5) dsWorst = Math.max(dsWorst, Math.abs((sNew(T) - sNew(298.15)) - (sOld(T) - sOld(298.15))));
report.waterCp = { fitWorstPercent: +(100 * wFit.max).toFixed(4), change: stats(wOld, wNew, 273.16, 900), old: at(wOld), new: at(wNew),
  hAt298Old: +hOld(298.15).toFixed(4), hAt298New: +hNew(298.15).toExponential(3), enthalpyWorstJPerMol: +dhWorst.toFixed(3), entropyWorstJPerMolK: +dsWorst.toFixed(5),
  formationEnthalpyKJPerMol: hf, iapwsAgainstCeaPercent: stats(L.ceaSpecies('H2O').cp, wIdeal.cp, 273.16, 900) };

// ---- 4. Nitrogen viscosity: the pilot record's CoolProp 8.0.0 tables, reused when they differ by < 1 % from the
// definition of the current tables (saturated liquid; 10 kPa / 100 kPa vapour isobars).
const pilot = L.readJson(`${L.MATERIALS}/properties/pilot_nitrogen.json`).viscosity;
const table = tab => T => {
  const t = tab.temperatures_kelvin, c = tab.coefficients; let i = 0;
  while (i < t.length - 2 && T > t[i + 1]) i++;
  const f = (T - t[i]) / (t[i + 1] - t[i]); return Math.exp(Math.log(c[i]) + f * (Math.log(c[i + 1]) - Math.log(c[i])));
};
const nodeAgreement = (tab, other) => tab.temperatures_kelvin.reduce((w, T, i) => Math.max(w, Math.abs(table(other)(T) / tab.coefficients[i] - 1)), 0);
report.nitrogenViscosity = {
  liquidOldNodesAgainstPilotPercent: +(100 * nodeAgreement(n2.viscosity.liquid, pilot.liquid)).toFixed(4),
  liquidTableChange: stats(table(n2.viscosity.liquid), table(pilot.liquid), 63.151, 126.192),
  liquidTableChangeBelow124K: stats(table(n2.viscosity.liquid), table(pilot.liquid), 63.151, 124),
  vapourOldNodesAgainstPilotPercent: +(100 * nodeAgreement(n2.viscosity.vapor, pilot.vapor)).toFixed(4),
  vapourTableChange: stats(table(n2.viscosity.vapor), table(pilot.vapor), 63.151, 900),
  oldRevision: n2.viscosity.liquid.revision, newRevisions: [pilot.liquid.revision, pilot.vapor.revision],
};

// ---- 5. Liquid calibration points: the CoolProp equations of state at the same states (oracle).
const CAL_FILE = `${L.MATERIALS}/transport/liquid_calibration.json`;
const cal = JSON.parse(text(CAL_FILE));
const eosReference = Object.fromEntries(['Methane', 'Ethane', 'n-Propane', 'IsoButane', 'n-Butane', 'Isopentane', 'n-Pentane', 'Nitrogen']
  .map(n => [n, L.coolpropFile(n).EOS[0].BibTeX_EOS]));
report.liquidCalibration = [];
const points = cal.points.map(p => {
  const o = oracle.liquidCalibration.find(x => x.component === p.component);
  if (!o || o.temperatureKelvin !== p.temperatureKelvin || Math.abs(o.pressurePascal / p.pressurePascal - 1) > 1e-9) throw new Error(`oracle point ${p.component}`);
  report.liquidCalibration.push({ component: p.component, temperatureKelvin: p.temperatureKelvin, old: p.molarVolumeCubicMetres, new: o.molarVolume,
    relativeChange: +(o.molarVolume / p.molarVolumeCubicMetres - 1).toExponential(3), translationShiftChangeCubicMetres: +(p.molarVolumeCubicMetres - o.molarVolume).toExponential(3) });
  return { component: p.component, temperatureKelvin: p.temperatureKelvin, pressurePascal: p.pressurePascal, molarVolumeCubicMetres: o.molarVolume,
    source: `${COOLPROP} ${o.coolpropFluid}.json EOS[0] (${eosReference[o.coolpropFluid]}), liquid root at ${p.temperatureKelvin} K and 2 MPa, through the Java Helmholtz oracle (tools/open-thermo-data/OpenThermoOracleProbe.java)` };
});
const calOut = { revision: 'coolprop-8.0.0-liquid-calibration-r1', points, schema_version: cal.schema_version, id: cal.id, type: cal.type,
  source: `${COOLPROP} reference equations of state: pure-liquid reference points at 2 MPa; each point names its equation; built by tools/open-thermo-data/derive.mjs.` };
out(CAL_FILE, JSON.stringify(calOut) + '\n');

// ---- 6. The nitrogen record: Cp fit, viscosity tables, the 90 K / 2 MPa density, texts and links.
const n2Volume = points.find(p => p.component === 'Nitrogen').molarVolumeCubicMetres;
const n2Out = { ...n2 };
n2Out.revision = 'fluid-nitrogen-coolprop-r4';
n2Out.source = `Ideal-gas Cp: a degree-5 fit in (T - 298.15 K), through its 273.16 K value, to the ideal-gas Cp of CoolProp Nitrogen.json EOS[0].alpha0 (Span et al. 2000; ${COOLPROP}), 273.16..900 K, worst ${(100 * Math.abs(n2Fit.max)).toFixed(3)} %; below 273.16 K the heat capacity is held at its 273.16 K value, ${held.toFixed(3)} J/(mol K), within ${(100 * Math.abs(heldWorst.max)).toFixed(2)} % of the same ideal-gas Cp down to the triple point (owner decision D37, simplification S4). Viscosity: the CoolProp 8.0.0 tables of properties/pilot_nitrogen.json (Lemmon and Jacobsen 2004). PR constants from CoolProp. Nitrogen cross-interactions initially estimated zero. Built by tools/open-thermo-data/derive.mjs (batch 2026-11-04-open-thermo-data, O2).`;
n2Out.standard_liquid_density_kg_per_m3 = n2.molecular_weight_kg_per_mol / n2Volume;
n2Out.fluid_domain = { ...n2.fluid_domain, evidence: L.replaceOnce(n2.fluid_domain.evidence,
  'Nitrogen triple point 63.151 K (Span et al. 2000 via NIST) to 900 K. Validated in F4 (FluidNitrogenCryogenicTest): ideal-gas Cp below 273.16 K (the fit\'s value there, held) within 0.05 % of NIST zero-pressure Cp; vapour Cp at 100 K and 200 K, 1 atm, within 1 %;',
  'Nitrogen triple point 63.151 K (Span et al. 2000) to 900 K. Validated in F4 (FluidNitrogenCryogenicTest, references from the Span et al. 2000 equation of state in CoolProp 8.0.0 since batch 2026-11-04-open-thermo-data): ideal-gas Cp below 273.16 K (the fit\'s value there, held) within 0.05 % of the zero-pressure Cp; vapour Cp at 100 K and 200 K, 1 atm, within 1 %;') };
n2Out.ideal_gas_cp = { ...n2.ideal_gas_cp, coefficients: n2Coefficients };
n2Out.viscosity = { liquid: pilot.liquid, vapor: pilot.vapor };
n2Out.provenance = { cp: COOLPROP_URL('Nitrogen'), critical: n2.provenance.critical, viscosity: COOLPROP_URL('Nitrogen'),
  liquid: `${COOLPROP_URL('Nitrogen')} (liquid volume at 90 K and 2 MPa, transport/liquid_calibration.json)` };
out(N2_FILE, JSON.stringify(n2Out, null, 2) + '\n');

// ---- 7. The methane record: written at the top (O7). O2 wrote tjl20-methane-coolprop-r2 here from the Setzmann-Wagner fit.

// ---- 8. The water record.
{
  let s = L.replaceOnce(text(W_FILE), '"revision": "water-iapws-shomate-r2"', '"revision": "water-iapws95-shomate-r3"');
  s = L.replaceOnce(s, '"source": "IAPWS Wagner-Pruss auxiliary saturation; NIST H2O(g) Shomate; Watson latent heat;',
    `"source": "IAPWS Wagner-Pruss auxiliary saturation; vapour heat capacity: the Shomate form (A..E) fitted to the IAPWS-95 ideal-gas Cp (CoolProp Water.json EOS[0].alpha0, ${COOLPROP}) over 273.16..900 K, worst ${(100 * Math.abs(wFit.max)).toFixed(3)} %, with F and H from the ATcT 1.220 formation enthalpy ${hf} kJ/mol (CoolProp master 9b35f538 INFO.STANDARD_STATE) so that h(298.15 K) = 0 (tools/open-thermo-data/derive.mjs, batch 2026-11-04-open-thermo-data); Watson latent heat;`);
  const m = s.match(/("shomate_coefficients": \[\n)([^\]]*?)(\n\s*\])/);
  const indent = m[2].match(/^(\s*)/)[1];
  s = s.replace(m[0], m[1] + shomate.map(c => indent + String(c)).join(',\n') + m[3]);
  out(W_FILE, s);
}

// ---- 9. Test references: nitrogen Cp checkpoints, water vapour volume, compressibility and viscosity.
{
  const old = L.readJson(`${REFERENCE}/nitrogen-cp-checks.json`);
  const pointsOut = old.points.map(p => ({ temperatureKelvin: p.temperatureKelvin, referenceCp: n2Ideal.cp(p.temperatureKelvin) }));
  const fitError = pointsOut.reduce((w, p) => Math.max(w, Math.abs(n2New(p.temperatureKelvin) / p.referenceCp - 1)), 0);
  out(`${REFERENCE}/nitrogen-cp-checks.json`, JSON.stringify({ maximumCpFitRelativeError: fitError, source: `Ideal-gas Cp of ${COOLPROP} Nitrogen.json EOS[0].alpha0 (Span et al. 2000); tools/open-thermo-data/derive.mjs`, points: pointsOut }, null, 2) + '\n');
  report.nitrogenCpChecks = { points: pointsOut.length, maximumCpFitRelativeError: fitError, worstOldReferenceChangePercent: +(100 * old.points.reduce((w, p, i) => Math.max(w, Math.abs(pointsOut[i].referenceCp / p.referenceCp - 1)), 0)).toFixed(4) };
}
{
  const checks = L.readJson(`${REFERENCE}/water-vapor-checks.json`), compression = L.readJson(`${REFERENCE}/water-vapor-compression.json`);
  const M = L.coolpropFile('Water').EOS[0].molar_mass;
  const SRC = `IAPWS-95 (${COOLPROP} Water.json EOS[0], through the Java Helmholtz oracle, tools/open-thermo-data/OpenThermoOracleProbe.java), vapour root at T and p`;
  let dv = 0, dk = 0, dmu = 0;
  const checksOut = checks.map((row, i) => {
    const o = oracle.waterVapour[i];
    if (o.temperatureKelvin !== row.temperatureKelvin || Math.abs(o.pressurePascal / row.partialPressurePascal - 1) > 1e-9) throw new Error('water point');
    const mu = L.iapws2008Viscosity(row.temperatureKelvin, o.molarDensity * M);
    dv = Math.max(dv, Math.abs(o.molarVolume / row.referenceVolume - 1)); dmu = Math.max(dmu, Math.abs(mu / row.referenceViscosity - 1));
    return { temperatureKelvin: row.temperatureKelvin, partialPressurePascal: row.partialPressurePascal, referenceVolume: o.molarVolume, idealVolume: row.idealVolume,
      relativeError: row.idealVolume / o.molarVolume - 1, referenceViscosity: mu,
      source: `${SRC}; viscosity IAPWS R12-08 (2008) without the critical enhancement at that density (tools/open-thermo-data/lib.mjs, checked against the release's table 4)` };
  });
  const compressionOut = compression.map((row, i) => {
    const o = oracle.waterVapour[i];
    if (o.temperatureKelvin !== row.temperatureKelvin) throw new Error('water compression point');
    dk = Math.max(dk, Math.abs(o.isothermalCompressibility / row.referenceCompressibility - 1));
    return { temperatureKelvin: row.temperatureKelvin, partialPressurePascal: row.partialPressurePascal, referenceCompressibility: o.isothermalCompressibility,
      idealCompressibility: row.idealCompressibility, source: `${SRC}: the isothermal compressibility 1/(rho dp/drho)` };
  });
  out(`${REFERENCE}/water-vapor-checks.json`, JSON.stringify(checksOut, null, 2) + '\n');
  out(`${REFERENCE}/water-vapor-compression.json`, JSON.stringify(compressionOut, null, 2) + '\n');
  report.waterVapourReferences = { worstVolumeChangePercent: +(100 * dv).toFixed(5), worstCompressibilityChangePercent: +(100 * dk).toFixed(4), worstViscosityChangePercent: +(100 * dmu).toFixed(4) };
}

// ---- 10. Values the nitrogen cryogenic test and the transient test quote (printed; the tests are edited by hand).
{
  const n = oracle.nitrogen, satLiquid = n.saturatedLiquid77355;
  report.nitrogenTestReferences = {
    saturationPressure77355: n.saturationPressure77355, saturatedLiquidDensity77355: n.saturatedLiquidMassDensity77355,
    vapourCp1atm: Object.fromEntries(n.vapour1atm.map(v => [v.temperatureKelvin, v.cp])),
    zeroPressureCp: Object.fromEntries([63.16, 78.16, 98.16, 148.16, 198.16, 248.16, 273.16].map(T => [T, n2Ideal.cp(T)])),
    saturatedLiquidViscosity77355: L.nitrogenViscosity(77.355, satLiquid.molarDensity),
    vapourViscosity350K1atm: L.nitrogenViscosity(350, n.vapour1atm.find(v => v.temperatureKelvin === 350).molarDensity),
  };
}

fs.writeFileSync(REPORT, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
console.log(write ? 'written' : 'measure only (pass --write to write the records)');
