// Group tables and group counts for the aqueous-solvents batch (2026-11-11, plan W2a, M3).
//
// Usage: node groups.mjs <dir with DWSIM's modfac.txt and modfac_ip.txt> <out file>
//
// Writes the modified UNIFAC (Dortmund) record (record kind group_interactions, scheme modified-unifac-dortmund) from
// DWSIM 10.2.5's resources modfac.txt (subgroups: main group, R, Q) and modfac_ip.txt (main-group pairs a, b, c of
// Psi_nm = exp(-(a_nm + b_nm T + c_nm T^2)/T)), with the water-hydrocarbon block of this batch. Deterministic: the
// files are parsed as text (nothing in them runs), subgroups and pairs are written in number order, and two runs give
// the same bytes.
//
// It also exports what import.mjs uses to write a compound's group_counts sub-record from its ChemSep record:
// groupCountsRecord(c, tables, credit). ChemSep's "Modified UNIFAC" element names Dortmund subgroups by number and
// its "PPR78" GCmethod names E-PPR78 groups by number (1..40, the order of Eppr78Groups.java and of the E-PPR78
// group_interactions record); each number is mapped to the record's name, and a compound whose groups do not map is
// refused for that scheme, by name.
import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const MODFAC_SCHEME = 'modified-unifac-dortmund';
export const EPPR78_SCHEME = 'eppr78-2022';
export const MODFAC_FILE = 'modified_unifac_dortmund.json';
export const EPPR78_FILE = 'eppr78_2022.json';
const TABLE_REVISION = 'dwsim-10.2.5-modfac-r1';
const BLOCK_REVISION = 'aqueous-solvents-fitblock-r1';

/**
 * Subgroup names that are not usable as record keys in DWSIM's text, renamed: subgroup 26 repeats the name "CHO" of
 * subgroup 20 (the aldehyde; 26 is the ether CH-O of main group 13, CH2O), and subgroup 83 holds a space.
 */
const RENAMES = { 26: 'CHO_ether', 83: 'c-CH2O[1/2CH2]' };
/**
 * Lines of modfac_ip.txt left out because their fields cannot be read positionally (i j a_ij b_ij c_ij a_ji b_ji c_ji
 * [reference] [x]): 14 43 has four numbers and a reference of 0; 2 56 has seven numbers and no reference.
 */
const MALFORMED = { '14-43': 'four parameter fields and a reference of 0', '2-56': 'seven fields after the group numbers, no reference' };
/** Main group of water and the main groups of a compound with no polar group (the block applies to it). */
const WATER_MAIN = 7, HYDROCARBON_MAINS = [1, 2, 3, 4, 42];

/** The subgroups (number order) and main groups of modfac.txt. */
export function readModfac(dir) {
  const subgroups = [], mains = new Map();
  for (const line of readFileSync(join(dir, 'modfac.txt'), 'utf8').split(/\r?\n/).slice(1)) {
    if (!line.trim()) continue;
    const f = line.split(';');
    if (f.length < 6) throw new Error('modfac.txt: short line ' + line);
    const main = Number(f[0]), number = Number(f[3]), r = Number(f[4]), q = Number(f[5]);
    if (![main, number, r, q].every(Number.isFinite)) throw new Error('modfac.txt: unreadable line ' + line);
    const name = RENAMES[number] ?? f[2];
    if (!/^[A-Za-z0-9(][A-Za-z0-9()[\]{}=/._+-]{0,31}$/.test(name)) throw new Error(`modfac.txt: subgroup ${number} name "${name}" is not a record key`);
    if (mains.has(main) && mains.get(main) !== f[1]) throw new Error(`modfac.txt: main group ${main} named twice`);
    mains.set(main, f[1]);
    subgroups.push({ number, name, ...(name !== f[2] ? { label: f[2] } : {}), main, r, q });
  }
  subgroups.sort((a, b) => a.number - b.number);
  for (let i = 1; i < subgroups.length; i++) {
    if (subgroups[i].number === subgroups[i - 1].number) throw new Error('modfac.txt: subgroup number ' + subgroups[i].number + ' twice');
  }
  const names = new Set(subgroups.map((s) => s.name));
  if (names.size !== subgroups.length) throw new Error('modfac.txt: duplicate subgroup names after renames');
  return { subgroups, mains: [...mains.entries()].sort((a, b) => a[0] - b[0]).map(([number, name]) => ({ number, name })) };
}

/** The main-group pairs of modfac_ip.txt: one entry per unordered pair, with both directions. */
export function readModfacPairs(dir, mains) {
  const known = new Set(mains.map((m) => m.number)), pairs = new Map(), skipped = { placeholder: 0, malformed: [], unknown: new Set() };
  for (const line of readFileSync(join(dir, 'modfac_ip.txt'), 'utf8').split(/\r?\n/)) {
    if (!line.trim() || line.startsWith("'")) continue;
    const t = line.trim().split(/\s+/);
    const i = Number(t[0]), j = Number(t[1]), key = `${i}-${j}`;
    if (MALFORMED[key]) { skipped.malformed.push(`${key}: ${MALFORMED[key]}`); continue; }
    const v = t.slice(2, 8).map(Number);
    if (t.length < 8 || !v.every(Number.isFinite)) throw new Error('modfac_ip.txt: unreadable line ' + line);
    // After the six parameters: an optional reference number, then an optional "x" mark; some lines carry trailing zeros.
    const rest = t.slice(8), reference = rest.length && rest[0] !== 'x' ? Number(rest[0]) : null;
    // A line with all six parameters zero and no literature reference is a placeholder: no data for the pair.
    if ((reference === null || reference === 0) && v.every((x) => x === 0)) { skipped.placeholder++; continue; }
    if (reference !== null && !(Number.isInteger(reference) && reference >= 1 && reference <= 9)) throw new Error('modfac_ip.txt: reference ' + rest[0] + ' in ' + line);
    if (rest.slice(reference === null ? 0 : 1).some((x) => x !== 'x' && x !== '0')) throw new Error('modfac_ip.txt: trailing fields in ' + line);
    if (i === j) throw new Error('modfac_ip.txt: self pair ' + key);
    // A main group modfac.txt gives no subgroup for cannot occur in a compound: its pairs are left out.
    if (!known.has(i) || !known.has(j)) { skipped.unknown.add(known.has(i) ? j : i); continue; }
    const lo = Math.min(i, j), hi = Math.max(i, j), id = `${lo}-${hi}`;
    if (pairs.has(id)) throw new Error('modfac_ip.txt: pair ' + id + ' twice');
    const forward = { a: v[0], b: v[1], c: v[2] }, backward = { a: v[3], b: v[4], c: v[5] };
    pairs.set(id, { first: lo, second: hi, ...(i === lo ? { forward, backward } : { forward: backward, backward: forward }), reference });
  }
  const list = [...pairs.values()].sort((a, b) => a.first - b.first || a.second - b.second);
  return { pairs: list, skipped };
}

/** The group_interactions record of the Dortmund table. */
export function modfacRecord(dir) {
  const { subgroups, mains } = readModfac(dir), { pairs, skipped } = readModfacPairs(dir, mains);
  const sha = (name) => {
    const m = readFileSync(join(dir, 'manifest.txt'), 'utf8').split(/\r?\n/).map((l) => l.split('\t')).find((f) => f[0] === name);
    return m ? `${m[1]} bytes, SHA-256 ${m[2]}` : 'not in manifest.txt';
  };
  return {
    schema_version: 1,
    id: 'icheme:modified_unifac_dortmund',
    revision: TABLE_REVISION,
    scheme: MODFAC_SCHEME,
    source: 'Modified UNIFAC (Dortmund): subgroup volumes R and areas Q, and main-group interaction parameters a, b, c of '
      + 'Psi_nm = exp(-(a_nm + b_nm T + c_nm T^2)/T) (a in K, b dimensionless, c in 1/K; a pair entry gives a, b, c for n = first, m = second and a_reverse, b_reverse, c_reverse for n = second, m = first), as DWSIM 10.2.5 ships them '
      + '(DWSIM.Thermodynamics.dll resources modfac.txt and modfac_ip.txt), from U. Weidlich, J. Gmehling, Ind. Eng. Chem. '
      + 'Res. 26 (1987) 1372; J. Gmehling, J. Li, M. Schiller, Ind. Eng. Chem. Res. 32 (1993) 178; and the later published '
      + 'revisions modfac_ip.txt cites (Gmehling et al. 1998, 2002; Wittig et al. 2001, 2003; Lohmann et al. 2001; Jakob et '
      + 'al.). Each pair keeps the file\'s reference number. Written by tools/compound-import/groups.mjs.',
    provenance: {
      files: `modfac.txt (${sha('modfac.txt')}), modfac_ip.txt (${sha('modfac_ip.txt')})`,
      renamed: 'subgroup 26 "CHO" (ether, main group 13) to CHO_ether, as subgroup 20 is the aldehyde CHO; subgroup 83 "c-CH2O[1/2 CH2]" to c-CH2O[1/2CH2] (no space in a key); each keeps its DWSIM text as label',
      left_out: `${skipped.placeholder} placeholder lines (all six parameters 0, no reference or reference 0: no data); malformed lines ${skipped.malformed.join('; ')}; `
        + `the pairs of main groups ${[...skipped.unknown].sort((a, b) => a - b).join(', ')}, for which modfac.txt gives no subgroup`,
      references: 'modfac_ip.txt reference numbers: 1 Weidlich and Gmehling 1987; 2 Gmehling, Li and Schiller 1993; 3 Gmehling, Lohmann, Jakob, Li and Joh 1998; 4 Gmehling, Wittig, Lohmann and Joh 2002; 5 Wittig, Lohmann, Joh, Horstmann and Gmehling 2001; 6 Lohmann, Joh and Gmehling 2001; 7 Lohmann and Gmehling 2001; 8 Wittig, Lohmann and Gmehling 2003; 9 Jakob, Grensemann, Lohmann and Gmehling (in press when the file was written)',
    },
    main_groups: mains,
    subgroups,
    pairs: pairs.map((p) => ({ first: p.first, second: p.second, a: p.forward.a, b: p.forward.b, c: p.forward.c,
      a_reverse: p.backward.a, b_reverse: p.backward.b, c_reverse: p.backward.c, ...(p.reference === null ? {} : { reference: p.reference }) })),
    water_hydrocarbon_block: {
      water_main_group: WATER_MAIN,
      hydrocarbon_main_groups: HYDROCARBON_MAINS,
      pairs: [
        { first: 1, second: WATER_MAIN, a: 682.8, b: 0, c: 0, a_reverse: 516.0, b_reverse: 0, c_reverse: 0 },
        { first: 3, second: WATER_MAIN, a: 413.3, b: 0, c: 0, a_reverse: 640.4, b_reverse: 0, c_reverse: 0 },
      ],
      estimated: true,
      source: 'Fitted in batch 2026-11-11-aqueous-solvents by tools/aqueous-solvents/group-derived-pairs.mjs (fitBlock): the '
        + 'constant terms of CH2-H2O and H2O-CH2 to the mutual solubility of n-hexane and water at 25 C (hexane in water 2.5e-6, '
        + 'water in hexane 5.3e-4, mole fractions), of ACH-H2O and H2O-ACH to benzene and water (4.1e-4, 3.0e-3), each as 1/gamma '
        + 'at infinite dilution; the solubilities are quoted from memory of the standard sources and are to be checked (plan '
        + 'section 2). Rounded to 0.1 K. Replaces the table\'s pairs of these main groups with water for a mixture of water '
        + 'and compounds whose groups all lie in hydrocarbon_main_groups (no polar group).',
      revision: BLOCK_REVISION,
    },
  };
}

// ---------------------------------------------------------------------------------------------------- group counts
/** Carbons of each class a group holds, for the cross-check of the two schemes (aromatic, alkenic, ring). */
const MODFAC_CARBONS = {
  9: ['aromatic', 1], 10: ['aromatic', 1], 11: ['aromatic', 1], 12: ['aromatic', 1], 13: ['aromatic', 1],
  5: ['alkenic', 2], 6: ['alkenic', 2], 7: ['alkenic', 2], 8: ['alkenic', 2], 70: ['alkenic', 2],
  78: ['ring', 1], 79: ['ring', 1], 80: ['ring', 1],
};
const EPPR78_CARBONS = {
  7: ['aromatic', 1], 8: ['aromatic', 1], 9: ['aromatic', 1],
  17: ['alkenic', 2], 18: ['alkenic', 1], 19: ['alkenic', 1],
  10: ['ring', 1], 11: ['ring', 1],
};
const classes = (groups, table) => {
  const n = { aromatic: 0, alkenic: 0, ring: 0 };
  for (const [id, count] of groups) { const k = table[id]; if (k) n[k[0]] += k[1] * count; }
  return n;
};

/** The two group tables as import.mjs reads them from the bundled records: number to name. */
export function readTables(materialsDir) {
  const read = (file) => JSON.parse(readFileSync(join(materialsDir, 'group_interactions', file), 'utf8'));
  const modfac = read(MODFAC_FILE), eppr78 = read(EPPR78_FILE);
  if (modfac.scheme !== MODFAC_SCHEME || eppr78.scheme !== EPPR78_SCHEME) throw new Error('group_interactions: unexpected schemes');
  return {
    modfac: { revision: modfac.revision, names: new Map(modfac.subgroups.map((s) => [s.number, s.name])) },
    eppr78: { revision: eppr78.revision, names: new Map(eppr78.groups.map((g) => [g.number, g.name])) },
  };
}

/**
 * A compound's group_counts sub-record from its ChemSep record, or null when neither scheme is usable, with the reasons
 * a scheme was refused. A scheme is refused when ChemSep has no element for it, when a group number has no name in the
 * table, when a count is not a positive integer, or when the two schemes disagree on the compound's aromatic, alkenic or
 * ring carbons: neither scheme invents a ring or a double bond, so the one that counts fewer of them has put such a
 * carbon in a plain alkane group, and is refused (ChemSep's PPR78 counts do this for cyclohexane and for terminal and
 * branched alkenic carbons; its Modified UNIFAC counts for ethylene).
 */
export function groupCountsRecord(c, tables, credit, revisions = {}) {
  const refused = [], record = {};
  const schemes = [
    [MODFAC_SCHEME, c.modifiedUnifac, tables.modfac, 'Modified UNIFAC', revisions.modfac ?? 'chemsep-8.3-modfac-r1', MODFAC_CARBONS],
    [EPPR78_SCHEME, c.ppr78, tables.eppr78, 'PPR78', revisions.eppr78 ?? 'chemsep-8.3-ppr78-r1', EPPR78_CARBONS],
  ];
  const counted = Object.fromEntries(schemes.map(([s, groups, , , , carbons]) => [s, groups ? classes(groups, carbons) : null]));
  for (const [scheme, groups, table, element, revision] of schemes) {
    if (!groups || !groups.length) { refused.push(`${scheme}: ChemSep has no ${element} groups`); continue; }
    const counts = {}; let bad = null;
    for (const [id, count] of groups) {
      const name = table.names.get(id);
      if (name === undefined) { bad = `ChemSep ${element} group ${id} has no name in the ${scheme} table`; break; }
      if (!(Number.isInteger(count) && count >= 1)) { bad = `ChemSep ${element} group ${id} count ${count} is not a positive integer`; break; }
      if (name in counts) { bad = `ChemSep ${element} group ${id} listed twice`; break; }
      counts[name] = count;
    }
    const other = counted[scheme === MODFAC_SCHEME ? EPPR78_SCHEME : MODFAC_SCHEME], own = counted[scheme];
    if (!bad && other) for (const k of ['aromatic', 'alkenic', 'ring']) if (own[k] < other[k]) {
      bad = `ChemSep's ${element} groups give ${own[k]} ${k} carbons, the other scheme ${other[k]}`; break;
    }
    if (bad) { refused.push(`${scheme}: ${bad}`); continue; }
    record[scheme] = {
      counts,
      estimated: false,
      source: `${element} group counts of ${credit(c)}, group numbers mapped to the names of the ${scheme} group_interactions `
        + `record (${table.revision}). Data for the pairs derived from group counts.`,
      revision,
    };
  }
  return { record: Object.keys(record).length ? record : null, refused };
}

// ---------------------------------------------------------------------------------------------------- CLI
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [dir, out] = process.argv.slice(2);
  if (!dir || !out) { console.error('usage: node groups.mjs <dwsim data dir> <out file>'); process.exit(2); }
  const record = modfacRecord(dir);
  writeFileSync(out, JSON.stringify(record, null, 2) + '\n');
  console.log(`wrote ${out}: ${record.subgroups.length} subgroups, ${record.main_groups.length} main groups, ${record.pairs.length} pairs; left out: ${record.provenance.left_out}`);
}
