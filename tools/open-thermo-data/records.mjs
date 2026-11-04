// O2 of batch 2026-11-04-open-thermo-data, the records whose NIST content is a comparison or a text, not a value the
// physics reads: the reference-spine records (the |CEA - JANAF| entropy uncertainty and the JANAF / WebBook holdout
// texts) and the package records' advisory evidence. Each changed record's own revision is bumped (AGENTS.md). The
// numbers quoted in the new texts are printed by spine-checks.mjs. Run from this folder: `node records.mjs` (dry run,
// prints the changes) or `node records.mjs --write`. Every record here round-trips JSON.stringify(_, null, 2)
// byte for byte, so the rewrite touches only the edited fields.
import fs from 'node:fs';
import * as L from './lib.mjs';

const write = process.argv.includes('--write');
const BATCH = 'batch 2026-11-04-open-thermo-data, O2';
const edits = [];
function edit(file, fn) {
  const path = `${L.MATERIALS}/${file}`;
  const text = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
  const before = JSON.parse(text);
  if (JSON.stringify(before, null, 2) + '\n' !== text) throw new Error(`${file}: does not round-trip`);
  const after = structuredClone(before);
  fn(after);
  for (const [k, v] of Object.entries(after)) if (JSON.stringify(v) !== JSON.stringify(before[k])) edits.push(`${file}: ${k}`);
  if (write) L.writeLike(path, JSON.stringify(after, null, 2) + '\n');
}
const swap = (s, from, to) => L.replaceOnce(s, from, to);

// ---- Spine records: no JANAF number, no JANAF / WebBook holdout text.
const noUncertainty = (o, formula, tag) => {
  delete o.standard_entropy.uncertainty_j_per_mol_kelvin;
  o.standard_entropy.source = o.standard_entropy.source.replace(/; CEA states no uncertainty, the uncertainty given is \|CEA - JANAF\| with JANAF [^;]*$/,
    `; no uncertainty: CEA states none (the |CEA - JANAF| estimate of the earlier revision was removed, ${BATCH})`);
  if (o.standard_entropy.source.includes('JANAF (')) throw new Error(`${formula}: entropy text`);
};
edit('spine/nitrogen.json', o => {
  o.revision = 'spine-nitrogen-r3';
  o.source += ` r3 (${BATCH}): the |CEA - JANAF| entropy uncertainty removed; the high-temperature check is CEA against CoolProp.`;
  noUncertainty(o, 'N2');
  o.coverage.evidence = swap(o.coverage.evidence, 'CEA Cp within 0.2 % of JANAF N-023 at 1000, 1100 and 1200 K (D7)',
    'CEA Cp within 0.2 % of the CoolProp alpha0 (Span et al. 2000) at 1000, 1100 and 1200 K (D7; -0.011 %; the JANAF N-023 holdout until r2)');
});
edit('spine/methane.json', o => {
  o.revision = 'spine-methane-r3';
  o.source += ` r3 (${BATCH}): the |CEA - JANAF| entropy uncertainty removed.`;
  noUncertainty(o, 'CH4');
});
edit('spine/ethane.json', o => {
  o.revision = 'spine-ethane-r3';
  o.source += ` r3 (${BATCH}): the high-temperature check is CEA against CoolProp.`;
  o.coverage.evidence = swap(o.coverage.evidence, 'CEA Cp within 0.2 % of the NIST WebBook Gurvich table (C74840) at 1000, 1100 and 1200 K (D7)',
    'CEA Cp within 0.2 % of the CoolProp alpha0 (Buecker and Wagner 2006) at 1000, 1100 and 1200 K (D7; -0.011 to -0.002 %; the NIST WebBook Gurvich table until r2)');
});
edit('spine/carbon_dioxide.json', o => {
  o.revision = 'spine-carbon_dioxide-r4';
  o.source += ` r4 (${BATCH}): the |CEA - JANAF| entropy uncertainty removed; the checks are CEA against CoolProp and, below the triple point, the rigid-rotor harmonic-oscillator limit and CEA.`;
  noUncertainty(o, 'CO2');
  o.coverage.evidence = swap(o.coverage.evidence, 'CEA Cp within 0.2 % of JANAF C-095 at 1000, 1100 and 1200 K (D7)',
    'CEA Cp within 0.2 % of the CoolProp alpha0 (Span and Wagner 1996) at 1000, 1100 and 1200 K (D7; -0.007 to +0.014 %; the JANAF C-095 holdout until r3)');
  const below = o.ideal_gas.segments[0];
  if (!below.revision.endsWith('-subtriple')) throw new Error('CO2 sub-triple segment');
  below.coverage.evidence = swap(below.coverage.evidence,
    'Held to JANAF C-095 (Chase 1998) at 100 and 200 K within 0.2 % by ReferenceSpineTest (P3: -0.008 % and +0.018 %); JANAF tabulates no 150 K value.',
    'Held within 0.2 % by ReferenceSpineTest to the rigid-rotor harmonic-oscillator Cp at 100 K (fundamentals 1333, 667.4 twice and 2349.1 cm-1; +0.004 %) and to NASA CEA (Gurvich 1991) at 200 K (+0.011 %); until r3 to JANAF C-095 at 100 and 200 K (-0.008 % and +0.018 %).');
});

// ---- Package records: advisory evidence without NIST; each package's own revision bumped.
const METHANE_OLD = 'METHANE_EXTENSION: NIST ideal-gas Cp fit; CoolProp PR constants; methane binary interactions assumed zero, not fitted';
const METHANE_NEW = 'METHANE_EXTENSION: ideal-gas Cp fit to the CoolProp alpha0 (Setzmann and Wagner 1991); CoolProp PR constants; methane binary interactions assumed zero, not fitted';
const NITROGEN_OLD = 'NITROGEN_CRYOGENIC: valid from its 63.151 K triple point (ideal-gas Cp held below 273.16 K, within 0.05 % of NIST; NIST viscosity tables); PR78 Psat at 77.355 K +1.21 %, liquid density -0.07 % against NIST (F4, P3 WP4 direct liquid path)';
const NITROGEN_NEW = 'NITROGEN_CRYOGENIC: valid from its 63.151 K triple point (ideal-gas Cp held below 273.16 K, within 0.05 % of Span et al. 2000; CoolProp viscosity tables); PR78 Psat at 77.355 K +1.21 %, liquid density -0.07 % against Span et al. 2000 (F4, P3 WP4)'; // at most 256 characters (MaterialCatalog's reporting bound)
const advisory = (o, from, to) => {
  const i = o.advisory_evidence.indexOf(from);
  if (i < 0) throw new Error(`advisory not found: ${from.slice(0, 40)}`);
  o.advisory_evidence[i] = to;
};
for (const [file, from, to, extra] of [
  ['packages/refinery.json', 'refinery-tier1-r1', 'refinery-tier1-r2', true],
  ['packages/tjl20.json', 'crude-regrouped-r2', 'crude-regrouped-r3', false],
  ['packages/tjl20_nitrogen.json', 'crude-regrouped-r2', 'crude-regrouped-r3', true],
]) edit(file, o => {
  if (o.revision !== from) throw new Error(`${file}: revision ${o.revision}`);
  o.revision = to;
  advisory(o, METHANE_OLD, METHANE_NEW);
  if (extra) advisory(o, NITROGEN_OLD, NITROGEN_NEW);
});
edit('packages/pilot_cryogenic.json', o => {
  if (o.revision !== 'pilot-cryogenic-p3-r2') throw new Error('pilot revision');
  o.revision = 'pilot-cryogenic-p3-r3';
  advisory(o, 'SPINE_IDEAL_GAS: the reference spines are the only ideal-gas source (no shifted_polynomial_5 fits, D6); methane above 375 K carries its declared CEA/JANAF conflict',
    'SPINE_IDEAL_GAS: the reference spines are the only ideal-gas source (no shifted_polynomial_5 fits, D6); methane above 375 K carries its declared conflict with the RRHO-like tables (NASA CEA 1.2 to 1.7 % above the spine at 1000 to 1200 K, MethaneSpineTest)');
});

console.log(edits.join('\n'));
console.log(write ? 'written' : 'dry run (pass --write)');
