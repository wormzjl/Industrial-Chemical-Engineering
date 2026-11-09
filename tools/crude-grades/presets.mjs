// Entry point 5: drafts of the client presets for the four grades (assets/icheme/mixturepreset and columnpreset), from
// <out>/grades.json and the bundled tia_juana templates. DRAFTS: every column needs a solver check.
// Sizing: the design feed carries the same standard liquid volume (barrels a day) as the tia_juana template. Draws keep
// the template's trays: heavy naphtha (tray 11) = crude_pc02 + pc03, light diesel (tray 19) = pc04 + pc05, heavy diesel
// (tray 29) = pc06; each draw is 80 % of its cuts' feed (owner's design excess rule: about a 20 % spare). Each draw keeps the
// template's pumparound above it (draw tray -> 2 trays up); its cooling scales with the mass of its cuts against Tia
// Juana's. Stripping steam scales with the atmospheric residue mass (crude_pc07+). Writes <out>/presets/.
// Run after grades.mjs: node tools/crude-grades/presets.mjs
import fs from 'node:fs';
import path from 'node:path';
import {loadComponents, OUT, TOOL} from './lib/materials.mjs';
import {moleFractions} from './lib/grades.mjs';
import {writeJson, isMain} from './lib/out.mjs';

export const DRAW_GROUPS = [['crude_pc02', 'crude_pc03'], ['crude_pc04', 'crude_pc05'], ['crude_pc06']];
export const DRAW_SHARE = 0.8;
const RESIDUE = ['crude_pc07', 'crude_pc08', 'crude_pc09', 'crude_pc10', 'crude_pc11', 'crude_pc12'];

if (isMain(import.meta.url)) {
  const comps = loadComponents(), byName = Object.fromEntries(comps.map(c => [c.name, c]));
  const grades = JSON.parse(fs.readFileSync(path.join(OUT, 'grades.json'), 'utf8'));
  // The Tia Juana templates the grades are sized against, as they were bundled until O3 (reference/, from 8396d8d98).
  const tjMix = JSON.parse(fs.readFileSync(path.join(TOOL, 'reference/tia_juana.mixturepreset.json'), 'utf8'));
  const tjCol = JSON.parse(fs.readFileSync(path.join(TOOL, 'reference/tia_juana.columnpreset.json'), 'utf8'));
  // Tia Juana feed: kmol/h per component, standard liquid volume, group masses
  const tjTotal = tjMix.components.reduce((s, c) => s + c.amount, 0), tjFeed = tjCol.design_feed_kmol_h;
  const tjN = Object.fromEntries(tjMix.components.map(c => [c.id, c.amount / tjTotal * tjFeed]));
  const volume = n => Object.entries(n).reduce((s, [k, v]) => s + v * byName[k].M / byName[k].rho60, 0); // m3/h
  const mass = (n, ids) => ids.reduce((s, k) => s + (n[k] || 0) * byName[k].M, 0); // kg/h
  const tjVolume = volume(tjN);
  const checks = [];
  // calibration: the rule on Tia Juana itself against the template's draws
  const tjDraws = DRAW_GROUPS.map(g => DRAW_SHARE * g.reduce((s, k) => s + tjN[k], 0));
  checks.push({grade: 'tia_juana (rule check)', draws_rule_kmol_h: tjDraws, draws_template_kmol_h: tjCol.draws.map(d => d.flow_kmol_h)});
  for (const g of grades) {
    const x = moleFractions(comps, g.w);
    const perMol = comps.reduce((s, c) => s + x[c.name] * c.M / c.rho60, 0); // m3 per kmol
    const feed = tjVolume / perMol, n = Object.fromEntries(comps.map(c => [c.name, x[c.name] * feed]));
    const draws = DRAW_GROUPS.map(gr => DRAW_SHARE * gr.reduce((s, k) => s + n[k], 0));
    const mixture = {schema_version: 1, kind: 'mixture', name: `${g.label} (generated)`, translation_key: `gui.icheme.column_preset.${g.id}`,
      temperature_kelvin: tjMix.temperature_kelvin, flow_kmol_h: feed, basis: 'mole',
      components: comps.filter(c => x[c.name] > 0).map(c => ({id: c.name, amount: x[c.name] * 100}))};
    const column = {schema_version: 3, kind: 'column', name: `Crude tower (${g.label})`, translation_key: `gui.icheme.column_template.${g.id}`,
      tray_count: tjCol.tray_count, feed_tray: tjCol.feed_tray, design_feed_kmol_h: feed, top_temperature_kelvin: tjCol.top_temperature_kelvin,
      reboiler_duty_mw: tjCol.reboiler_duty_mw, reflux_ratio: tjCol.reflux_ratio, top_pressure_bar: tjCol.top_pressure_bar, diameter_m: tjCol.diameter_m,
      draws: tjCol.draws.map((d, i) => ({tray: d.tray, flow_kmol_h: draws[i]})),
      steam: tjCol.steam.map(s => ({tray: s.tray, flow_kmol_h: s.flow_kmol_h * mass(n, RESIDUE) / mass(tjN, RESIDUE), temperature_kelvin: s.temperature_kelvin})),
      pumparounds: tjCol.pumparounds.map((p, i) => ({...p, cooling_mw: p.cooling_mw * mass(n, DRAW_GROUPS[i]) / mass(tjN, DRAW_GROUPS[i])}))};
    writeJson(path.join(OUT, 'presets/mixturepreset', g.id + '.json'), mixture);
    writeJson(path.join(OUT, 'presets/columnpreset', g.id + '.json'), column);
    checks.push({grade: g.id, design_feed_kmol_h: feed, feed_t_h: comps.reduce((s, c) => s + n[c.name] * c.M, 0) / 1000, feed_m3_h_standard: tjVolume,
      draws_kmol_h: draws, draw_cut_feed_kmol_h: DRAW_GROUPS.map(gr => gr.reduce((s, k) => s + n[k], 0)),
      steam_kmol_h: column.steam[0].flow_kmol_h, pumparound_cooling_mw: column.pumparounds.map(p => p.cooling_mw)});
  }
  writeJson(path.join(OUT, 'presets/README.json'), {status: 'DRAFT: each column needs a solver check (column calculator) before it ships', rules: fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').slice(0, 8).join(' '), checks});
  for (const c of checks) console.log(JSON.stringify(c, (k, v) => typeof v === 'number' ? +v.toFixed(2) : v));
}
