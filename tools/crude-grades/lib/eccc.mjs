// ECCC Crude Oil and Petroleum Product Database (2021-01-22, English CSV, ISO-8859-1), read as plain text.
// The file is untrusted data: it is decoded and split into fields; no field is ever evaluated.
// Layout: one row per property, one column per sample; columns 0..4 are labels
// (blank, property, unit, temperature, condition).
import fs from 'node:fs';

/** RFC 4180 fields: quotes, doubled quotes, commas and CR/LF inside quotes. */
export function parseCsv(text) {
  const rows = []; let row = [], field = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false; } else field += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ',') { row.push(field); field = ''; }
    else if (ch === '\n') { row.push(field.replace(/\r$/, '')); rows.push(row); row = []; field = ''; }
    else field += ch;
  }
  if (field !== '' || row.length) { row.push(field.replace(/\r$/, '')); rows.push(row); }
  return rows;
}

const norm = s => (s ?? '').replace(/\s+/g, ' ').trim();
/** A number from a cell, or null: plain decimals only ("<-5", "%", text and blanks give null; "0.00%" gives 0). */
export function num(cell) {
  const s = norm(cell).replace(/%$/, '');
  return /^-?\d+(\.\d+)?([eE]-?\d+)?$/.test(s) ? Number(s) : null;
}

/** The samples, each as {name, column, label, fields: Map(key -> raw cell)}; key = property|unit|temperature|condition. */
export function readSamples(file) {
  const rows = parseCsv(fs.readFileSync(file).toString('latin1'));
  const keys = rows.map(r => [1, 2, 3, 4].map(k => norm(r[k])).join('|'));
  const samples = [];
  for (let col = 5; col < rows[0].length; col++) {
    const name = norm(rows[0][col]); if (!name) continue;
    const at = label => { const i = keys.findIndex(k => k.startsWith(label + '|')); return i < 0 ? '' : norm(rows[i][col]); };
    samples.push({name, column: col, code: at('ESTS (Emergencies Sciences & Technologies) code'), weathering: at('Evaporative weathering fraction/Sample note'),
      massLoss: num(at('Evaporative mass loss')), rows, keys});
  }
  return samples;
}

/** The value of the first row whose label fields equal the given ones (property, unit, temperature; null = any) and whose
 * condition starts with the given prefix. Labels are compared whitespace-normalised and case-insensitive. */
export function cell(sample, property, unit = null, temperature = null, condition = null) {
  const eq = (a, b) => b === null || a.toLowerCase() === b.toLowerCase();
  const i = sample.keys.findIndex(k => { const [p, u, tt, c] = k.split('|');
    return eq(p, property) && eq(u, unit) && eq(tt, temperature) && (condition === null || c.toLowerCase().startsWith(condition.toLowerCase())); });
  return i < 0 ? '' : norm(sample.rows[i][sample.column]);
}

/** Density g/mL at 15 and 0 C, Newtonian / unknown-behaviour dynamic viscosity mPa s at 15 and 0 C, sulfur, API. */
export function bulk(sample) {
  const v = (t, cond) => num(cell(sample, 'Dynamic viscosity', 'mPa.s', t + '°C', cond));
  return {
    density15: num(cell(sample, 'Density', 'g/mL', '15°C')), density0: num(cell(sample, 'Density', 'g/mL', '0°C')),
    api: num(cell(sample, 'Calculated API Gravity')), sulfur: num(cell(sample, 'Sulfur content', '% w/w')),
    pourPoint: cell(sample, 'Pour point', '°C'),
    mu15: v(15, 'Newtonian'), mu0: v(0, 'Newtonian'),
    mu15unknown: v(15, 'Unkown'), mu0unknown: v(0, 'Unkown'),
    mu15nonNewtonian: v(15, '(Non-Newtonian)'), mu0nonNewtonian: v(0, '(Non-Newtonian)'),
  };
}

/** The boiling curve as [mass fraction, temperature C] points, from either block of the file:
 * the percent-recovered block (IBP, 5 %..95 %, FBP, 100 %) or the cumulative-weight-fraction block (40..700 C). */
export function boiling(sample) {
  const pts = [];
  const ibp = num(cell(sample, 'Initial Boiling Point', '°C')); if (ibp !== null) pts.push([0, ibp]);
  for (let p = 5; p <= 95; p += 5) { const t = num(cell(sample, `${p}%`, '°C')); if (t !== null) pts.push([p / 100, t]); }
  const t100 = num(cell(sample, '100%', '°C')); if (t100 !== null) pts.push([1, t100]);
  const fbp = num(cell(sample, 'Final Boiling Point', '°C'));
  if (pts.length >= 4) return {kind: 'percent', points: pts, finalBoilingPoint: fbp};
  const cum = [];
  for (let t = 40; t <= 700; t += t < 200 ? 20 : 50) { const f = num(cell(sample, 'Boiling Point: Cumulative Weight Fraction', '% w/w', String(t))); if (f !== null) cum.push([f / 100, t]); }
  return cum.length >= 4 ? {kind: 'cumulative', points: cum, finalBoilingPoint: fbp} : null;
}
