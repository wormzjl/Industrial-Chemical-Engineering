// The bundled material records the tool reads (read-only), and the game's own evaluation of a liquid viscosity.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export const TOOL = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
export const REPO = path.resolve(TOOL, '../..');
export const MATERIALS = path.join(REPO, 'src/main/resources/data/icheme/materials');
export const ASSETS = path.join(REPO, 'src/main/resources/assets/icheme');
export const OUT = process.env.CRUDE_GRADES_OUT ?? path.join(REPO, 'tmp/crude-grades/out');
export const ECCC_CSV = process.env.ECCC_CSV ?? 'D:/Minecraft/Modding/1.21/CreateChemE/research/2026-11-04-open-thermo-data/eccc-oil-database/ECCC_Petroleum_Products_Database-2021-01-22.EN.csv';

export const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'));
export const LIGHT = [['Methane', 'tjl20_methane'], ['Ethane', 'tjl19_ethane'], ['Propane', 'tjl19_propane'], ['Isobutane', 'tjl19_isobutane'],
  ['N-butane', 'tjl19_n_butane'], ['Isopentane', 'tjl19_isopentane'], ['N-pentane', 'tjl19_n_pentane']];
export const CUTS = Array.from({length: 12}, (_, i) => 'crude_pc' + String(i + 1).padStart(2, '0'));

const table = v => v ? {t: v.temperatures_kelvin, c: v.coefficients} : null;
function record(name, file, light) {
  const r = readJson(path.join(MATERIALS, 'properties', file + '.json'));
  return {name, file, light, record: r, M: r.molecular_weight_kg_per_mol * 1000, Tb: r.normal_boiling_point_kelvin,
    rho60: r.standard_liquid_density_kg_per_m3, Tstd: r.standard_temperature_kelvin ?? 288.7055555556,
    Tc: r.models.pr78.critical_temperature_kelvin, Pc: r.models.pr78.critical_pressure_pascal, omega: r.models.pr78.acentric_factor,
    liq: table(r.viscosity.liquid), vap: table(r.viscosity.vapor)};
}
/** The 19 crude components in assay order: the seven light ends, then crude_pc01..12. */
export function loadComponents() {
  return [...LIGHT.map(([n, f]) => record(n, f, true)), ...CUTS.map(c => record(c, c, false))];
}

// ViscosityCorrelation (log_table): ln mu linear in T between nodes; a liquid below its first node continues the first
// segment's slope with ln mu linear in 1/T (Andrade), above its last node holds the last value; a vapour continues
// ln mu linear in ln T. Every value is capped at MAXIMUM_VISCOSITY = 1e12 Pa s.
export const MAXIMUM_VISCOSITY = 1e12;
export function evalTable(tb, T, vapour = false) {
  const t = tb.t, c = tb.c, n = t.length;
  let v;
  if (T < t[0] || T > t[n - 1]) {
    const lo = T < t[0], e = lo ? 0 : n - 1, i = lo ? 1 : n - 2;
    if (!vapour && !lo) v = c[e];
    else {
      const s = (Math.log(c[e]) - Math.log(c[i])) / (t[e] - t[i]);
      v = Math.exp(vapour ? Math.log(c[e]) + s * t[e] * Math.log(T / t[e]) : Math.log(c[e]) - s * t[e] * t[e] * (1 / T - 1 / t[e]));
    }
  } else {
    let a = 0, b = n - 1; while (b - a > 1) { const m = (a + b) >> 1; if (t[m] <= T) a = m; else b = m; }
    v = t[a] === T ? c[a] : Math.exp(Math.log(c[a]) + (T - t[a]) / (t[b] - t[a]) * (Math.log(c[b]) - Math.log(c[a])));
  }
  return Math.min(v, MAXIMUM_VISCOSITY);
}
/** MixtureViscosity.liquidSource for one component: its liquid table up to the table's end; above it a component at or
 * above its Tc takes its vapour data; else the table's end value, held. {@code liq} overrides the record's table. */
export function gameLiquid(comp, T, liq = comp.liq) {
  if (T <= liq.t[liq.t.length - 1]) return evalTable(liq, T);
  if (T >= comp.Tc && comp.vap) return evalTable(comp.vap, T, true);
  return evalTable(liq, T);
}
