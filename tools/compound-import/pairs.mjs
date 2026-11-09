// PR binary-interaction sources in the DWSIM data: DWSIM's own kij_pr.dat matrix and the DECHEMA PR pairs of
// ChemSep (pr_ip.dat, ChemSep index pairs; pr.ipd, the same table keyed by CAS). Data only.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// kij_pr.dat labels its rows and columns with DWSIM's Portuguese compound names. The label with every non-ASCII
// byte removed maps to the CAS number.
const KIJ_PR_LABELS = {
  'Metano (C1)': '74-82-8', 'Etano (C2)': '74-84-0', 'Propano (C3)': '74-98-6', 'Isobutano (iC4)': '75-28-5',
  'n-Butano (nC4)': '106-97-8', 'Isopentano (iC5)': '78-78-4', 'n-Pentano (nC5)': '109-66-0', 'n-Hexano (nC6)': '110-54-3',
  'n-Heptano (nC7)': '142-82-5', 'n-Octano (nC8)': '111-65-9', 'n-Nonano (nC9)': '111-84-2', 'n-Decano (nC10)': '124-18-5',
  'Oxignio (O2)': '7782-44-7', 'Nitrognio (N2)': '7727-37-9', 'gua (H2O)': '7732-18-5', 'Dixido de Carbono (CO2)': '124-38-9',
  'Sulfeto de Hidrognio (H2S)': '7783-06-4', 'Benzeno': '71-43-2', 'Tolueno': '108-88-3', 'o-Xileno': '95-47-6',
  'm-Xileno': '108-38-3', 'p-Xileno': '106-42-3', 'Etilbenzeno': '100-41-4', 'Fenol': '108-95-2', 'Glicerol': '56-81-5',
  'Metanol': '67-56-1', 'Etanol': '64-17-5', '2-Propanol': '67-63-0', '1-Pentanol': '71-41-0', '1-Hexanol': '111-27-3',
  '2-Heptanol': '543-49-7', '1-Octanol': '111-87-5', '1-Decanol': '112-30-1', '3-metil-1-butanol': '123-51-3',
  '1.2-propanodiol': '57-55-6', 'Metiletilcetona': '78-93-3', 'Etilenoglicol': '107-21-1', 'Ciclopropano': '75-19-4',
};
const ascii = (s) => s.replace(/[^\x20-\x7e]/g, '').trim();
export const pairKey = (a, b) => (a < b ? a + '|' + b : b + '|' + a);

/**
 * Decision E28 (batch 2026-11-04-open-thermo-data): kij_pr.dat's N2 row is shifted one cell to the left after the
 * diagonal, and its N2 column copies two of the shifted cells. The evidence:
 * - the N2 row's H2O cell holds -0.0193 while the H2O row's N2 cell is blank; -0.0193 is DECHEMA's N2/CO2 (p310);
 * - the row's CO2 cell holds 0.1652, DECHEMA's N2/H2S (p318), and its H2S cell 0.1641, which equals N2/benzene (the
 *   benzene row's N2 cell, DECHEMA p343); its benzene cell is blank;
 * - all six DECHEMA N2/CO2 fits (pr_ip.dat rows 55 to 60, p306 to p314) lie between -0.010 and -0.022, far from 0.1652.
 * The reader takes the N2 row after the diagonal one column to the right (the cell under column j belongs to column
 * j + 1) and checks every N2-column cell after the diagonal: it equals the corrected value (then it is the pair's
 * cell, as for benzene) or is a copy of the row's shifted cell at that column (CO2, H2S). Result: N2/CO2 -0.0193,
 * N2/H2S 0.1652, N2/benzene 0.1641, no N2/H2O pair.
 */
const SHIFTED_ROW = '7727-37-9';
const SHIFT_NOTE = 'one-cell shift of the N2 row after the diagonal undone, decision E28';
// The DECHEMA value each corrected cell equals (ChemSep pr_ip.dat, with the page its comment gives).
const SHIFT_CHECK = { '124-38-9': "DECHEMA's N2/CO2, p310", '7783-06-4': "DECHEMA's N2/H2S, p318", '71-43-2': "the benzene row's cell, DECHEMA p343" };

/** kij_pr.dat as a map pairKey(cas, cas) -> {kij, cell}; conflicts lists pairs whose two triangle cells disagree. */
export function readKijPr(dir) {
  const lines = readFileSync(join(dir, 'kij_pr.dat'), 'latin1').split(/\r?\n/).filter((l) => l.trim());
  const header = lines[0].split(';').slice(1).map(ascii);
  const cas = header.map((label) => {
    if (!(label in KIJ_PR_LABELS)) throw new Error('kij_pr.dat: unmapped label ' + label);
    return KIJ_PR_LABELS[label];
  });
  const number = (text) => {
    const kij = Number(text);
    if (!Number.isFinite(kij)) throw new Error('kij_pr.dat: bad value ' + text);
    return kij;
  };
  const blank = (text) => !text || text === '-';
  const rows = lines.slice(1).map((line) => {
    const cells = line.split(';');
    const row = KIJ_PR_LABELS[ascii(cells[0])];
    if (!row) throw new Error('kij_pr.dat: unmapped row ' + cells[0]);
    return { row, label: ascii(cells[0]), cells: cells.slice(1).map((c) => c.trim()) };
  });
  // E28: the N2 row after the diagonal, one column to the right.
  const diagonal = cas.indexOf(SHIFTED_ROW);
  const shifted = rows.find((r) => r.row === SHIFTED_ROW);
  if (diagonal < 0 || !shifted) throw new Error('kij_pr.dat: no N2 row and column');
  const corrected = new Map(); // column index -> { kij, cell }
  for (let j = diagonal + 1; j < cas.length; j++) {
    const text = shifted.cells[j] ?? '';
    if (blank(text)) continue;
    if (j + 1 >= cas.length) throw new Error('kij_pr.dat: the N2 row\'s last cell is not blank: ' + text);
    corrected.set(j + 1, { kij: number(text), cell: `${shifted.label} / ${header[j]}, read as ${header[j + 1]}: ${SHIFT_NOTE}${SHIFT_CHECK[cas[j + 1]] ? '; equals ' + SHIFT_CHECK[cas[j + 1]] : ''}` });
  }
  const values = new Map(), conflicts = [];
  for (const { row, label, cells } of rows) {
    const after = cas.indexOf(row) > diagonal;
    cells.forEach((text, j) => {
      if (blank(text) || cas[j] === row) return;
      if (row === SHIFTED_ROW && j > diagonal) return; // read above, corrected
      const kij = number(text);
      if (cas[j] === SHIFTED_ROW && after) {
        // An N2-column cell after the diagonal: the corrected value, or a copy of the row's shifted cell there.
        const i = cas.indexOf(row), fixed = corrected.get(i), copy = shifted.cells[i];
        if (fixed && fixed.kij === kij) { corrected.delete(i); values.set(pairKey(row, SHIFTED_ROW), { kij, cell: label + ' / ' + header[j] }); return; }
        if (!blank(copy) && number(copy) === kij) return;
        throw new Error(`kij_pr.dat: N2-column cell ${label} = ${kij} is neither the corrected N2 row (${fixed ? fixed.kij : 'blank'}) nor its shifted cell (${copy || 'blank'})`);
      }
      const key = pairKey(row, cas[j]);
      const seen = values.get(key);
      if (seen && seen.kij !== kij) conflicts.push(key + ' ' + seen.kij + ' vs ' + kij);
      if (!seen) values.set(key, { kij, cell: label + ' / ' + header[j] });
    });
  }
  for (const [j, value] of corrected) {
    const key = pairKey(SHIFTED_ROW, cas[j]);
    if (values.has(key)) throw new Error('kij_pr.dat: corrected N2 pair also read elsewhere: ' + key);
    values.set(key, value);
  }
  return { values, conflicts };
}

function temperatureRange(comment) {
  // "T=90-113K", "T=77.35K", "T=355.37.388.71K" (a typo for 355.37-388.71 K in row 25).
  const m = comment.match(/T=\s*(\d+(?:\.\d+)?)(?:\s*[-.]\s*(\d+(?:\.\d+)?))?\s*K?/);
  if (!m) return null;
  const lo = Number(m[1]), hi = m[2] ? Number(m[2]) : lo;
  return [Math.min(lo, hi), Math.max(lo, hi)];
}

/**
 * The DECHEMA PR pairs: pr_ip.dat rows (ChemSep index pairs, 1996 library) mapped to CAS by the ChemSep index, in
 * file order, and the rows of pr.ipd (the same library keyed by CAS, "updated/corrected by Harry Kooijman 2009"),
 * which the caller uses as a cross-check only. An index absent from the ChemSep files leaves a or b null.
 */
export function readDechema(dir, byIndex) {
  const rows = readFileSync(join(dir, 'pr_ip.dat'), 'latin1').split(/\r?\n/).slice(1).filter((l) => l.trim());
  const ipd = readFileSync(join(dir, 'pr.ipd'), 'latin1').split(/\r?\n/)
    .filter((l) => /^\s*[0-9]+-[0-9]+-[0-9]\s+[0-9]+-[0-9]+-[0-9]\s+/.test(l))
    .map((l) => { const p = l.trim().split(/\s+/); return { a: p[0], b: p[1], kij: Number(p[2]) }; });
  const entries = [];
  rows.forEach((line, i) => {
    const [ia, ib, k, ...rest] = line.split(';');
    const comment = rest.join(';').trim();
    const a = byIndex.get(ia.trim()), b = byIndex.get(ib.trim());
    entries.push({ row: i + 2, a: a ? a.cas : null, b: b ? b.cas : null, kij: Number(k), comment, range: temperatureRange(comment) });
  });
  return { entries, unmapped: entries.filter((e) => !e.a || !e.b).length, ipd };
}

/**
 * Owner decision D3, second step: of the DECHEMA entries for one pair, the one whose fitted temperature range is
 * nearest 300..600 K (gap 0 when it overlaps; ties: the longer overlap, then file order). Entries that state no
 * temperature are used only when no entry states one (first in file order).
 */
export function chooseDechema(candidates) {
  const ranged = candidates.filter((e) => e.range);
  if (ranged.length) {
    const score = (e) => {
      const [lo, hi] = e.range;
      return { gap: Math.max(0, 300 - hi, lo - 600), overlap: Math.max(0, Math.min(hi, 600) - Math.max(lo, 300)) };
    };
    return [...ranged].sort((x, y) => {
      const a = score(x), b = score(y);
      return a.gap - b.gap || b.overlap - a.overlap || x.row - y.row;
    })[0];
  }
  return candidates[0] ?? null;
}
