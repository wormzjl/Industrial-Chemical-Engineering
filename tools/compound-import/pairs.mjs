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

/** kij_pr.dat as a map pairKey(cas, cas) -> {kij, cell}; conflicts lists pairs whose two triangle cells disagree. */
export function readKijPr(dir) {
  const lines = readFileSync(join(dir, 'kij_pr.dat'), 'latin1').split(/\r?\n/).filter((l) => l.trim());
  const header = lines[0].split(';').slice(1).map(ascii);
  const cas = header.map((label) => {
    if (!(label in KIJ_PR_LABELS)) throw new Error('kij_pr.dat: unmapped label ' + label);
    return KIJ_PR_LABELS[label];
  });
  const values = new Map(), conflicts = [];
  for (const line of lines.slice(1)) {
    const cells = line.split(';');
    const row = KIJ_PR_LABELS[ascii(cells[0])];
    if (!row) throw new Error('kij_pr.dat: unmapped row ' + cells[0]);
    cells.slice(1).forEach((cell, j) => {
      const text = cell.trim();
      if (!text || text === '-' || cas[j] === row) return;
      const kij = Number(text);
      if (!Number.isFinite(kij)) throw new Error('kij_pr.dat: bad value ' + text);
      const key = pairKey(row, cas[j]);
      const seen = values.get(key);
      if (seen && seen.kij !== kij) conflicts.push(key + ' ' + seen.kij + ' vs ' + kij);
      if (!seen) values.set(key, { kij, cell: ascii(cells[0]) + ' / ' + header[j] });
    });
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
