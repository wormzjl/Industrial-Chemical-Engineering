// O9 of batch 2026-11-04-open-thermo-data (owner decision E23, form S2): the thermochemistry and flammability
// sub-records of the twelve shared cuts, estimated. Writes thermochemistry/crude_pcNN.json and flammability/crude_pcNN.json
// wrappers ({id, path, component, <key>}) under the output folder; tools/compound-import/insert-subrecord.mjs writes them in.
//
// - Formula CHx: Cragoe 1929 (US Bureau of Standards Miscellaneous Publication 97) hydrogen content from the specific
//   gravity alone, 26 - 15 SG wt%, at the record's molar mass; the rest is carbon (sulfur ignored, E23).
// - Formation enthalpy: back-computed so that Hess's law (to CO2 and H2O gas, ChemSep's heats of formation) gives Cragoe's
//   net heat, 51.916 - 8.792 SG^2 - 0.2122 H MJ/kg.
// - Lower limit: Burgess-Wheeler, LFL x LHV = 4319 vol% kJ/mol (R3, COMBUSTION_FLAMMABILITY_REVIEW.md). No upper limit.
import fs from 'node:fs';
import path from 'node:path';
import {loadComponents, MATERIALS, OUT} from './lib/materials.mjs';
import {writeJson, isMain} from './lib/out.mjs';

export const THERMOCHEMISTRY_REVISION = 'cragoe-1929-chx-r1';
export const FLAMMABILITY_REVISION = 'burgess-wheeler-r1';
/** Water at 60 F, kg/m3 (the SG 60/60 F reference). */
export const WATER_60F = 999.016;
/** Burgess-Wheeler constant, vol% kJ/mol: the median of LFL x LHV over the 22 hydrocarbons of the world list (R3). */
export const BURGESS_WHEELER = 4319;
/** ChemSep v8.3 ideal-gas heats of formation at 298.15 K, J/mol: CO2 (index 909), H2O (index 1921). */
export const PRODUCTS = {CO2: -393510, H2O: -241814};
const CARBON = 12.011, HYDROGEN = 1.008;

/** Cragoe 1929: net heat of combustion (MJ/kg) and hydrogen (wt%) from the specific gravity 60/60 F. */
export function cragoe(sg) {
  const hydrogen = 26 - 15 * sg;
  return {net: 51.916 - 8.792 * sg * sg - 0.2122 * hydrogen, hydrogen};
}

/** The products' heats of formation agree with the bundled records' thermochemistry, where they carry one. */
function checkProducts() {
  for (const [product, file] of [['CO2', 'properties/chemsep_carbon_dioxide.json'], ['H2O', 'water/water.json']]) {
    const t = JSON.parse(fs.readFileSync(path.join(MATERIALS, file), 'utf8')).thermochemistry;
    if (t && t.formation_enthalpy_j_per_mol !== PRODUCTS[product]) throw new Error(`${file}: ${t.formation_enthalpy_j_per_mol} J/mol, the tool ${PRODUCTS[product]}`);
  }
}

/** The thermochemistry and flammability sub-records of one cut, and the Hess heat they give, J/mol. */
export function cutRecords(cut) {
  const sg = cut.rho60 / WATER_60F, {net, hydrogen} = cragoe(sg), r = cut.record;
  const lhv = net * cut.M * 1000; // MJ/kg x g/mol x 1000 = J/mol
  const h = hydrogen / 100 * cut.M / HYDROGEN, c = (1 - hydrogen / 100) * cut.M / CARBON;
  const formation = lhv + c * PRODUCTS.CO2 + h / 2 * PRODUCTS.H2O;
  const thermochemistry = {
    formation_enthalpy_j_per_mol: formation,
    elements: {C: c, H: h},
    estimated: true,
    source: `Estimated. Formula CHx: hydrogen ${hydrogen} wt% from Cragoe 1929 (US Bureau of Standards Miscellaneous Publication 97, `
      + `Thermal Properties of Petroleum Products), 26 - 15 SG, at the molar mass ${cut.M} g/mol of ${r.id} (revision ${r.revision}), `
      + `the rest carbon; SG ${sg} = its standard liquid density over ${WATER_60F} kg/m3, water at 60 F; sulfur ignored (owner decision E23). `
      + `Formation enthalpy back-computed so that Hess's law to CO2 and H2O (gas), on ChemSep v8.3's ideal-gas heats of formation `
      + `(${PRODUCTS.CO2} and ${PRODUCTS.H2O} J/mol), gives Cragoe's net heat 51.916 - 8.792 SG^2 - 0.2122 H = ${net} MJ/kg. `
      + 'Against 177 ChemSep C5+ hydrocarbons that heat has -0.3 % bias and 0.5 % mean absolute error for paraffins and naphthenes, '
      + '+2.5 % and 2.8 % for aromatics (COMBUSTION_FLAMMABILITY_REVIEW.md, batch 2026-11-04-open-thermo-data). Cragoe 1929 is a US '
      + 'Government work. Computed by tools/crude-grades/thermochemistry.mjs. Data only.',
    revision: THERMOCHEMISTRY_REVISION,
  };
  const flammability = {
    lfl_volume_fraction: BURGESS_WHEELER / (lhv / 1000) / 100,
    estimated: true,
    source: `Estimated, Burgess-Wheeler: LFL x LHV = ${BURGESS_WHEELER} vol% kJ/mol, the median over the 22 hydrocarbons of the world list `
      + '(0.2 % bias, 7.5 % mean absolute error, 23 % largest; COMBUSTION_FLAMMABILITY_REVIEW.md), on the Hess heat of this record\'s '
      + 'estimated thermochemistry. Constant with temperature (owner decision E23). No upper limit: no correlation is good enough '
      + '(Jones, 25 % mean absolute error).',
    revision: FLAMMABILITY_REVISION,
  };
  return {thermochemistry, flammability, lhv, sg, hydrogen};
}

if (isMain(import.meta.url)) {
  checkProducts();
  const cuts = loadComponents().filter(c => !c.light);
  console.log('cut          SG       H wt%   C atoms   H atoms    dHf J/mol      LHV MJ/kg  LFL');
  for (const cut of cuts) {
    const {thermochemistry, flammability, lhv, sg, hydrogen} = cutRecords(cut);
    for (const record of [thermochemistry, flammability])
      for (const [k, v] of Object.entries(record)) if (typeof v === 'string' && v.length > 2048) throw new Error(`${cut.name}: ${k} longer than 2048 characters`);
    const target = {id: cut.record.id, path: 'properties/' + cut.file + '.json', component: cut.record.component};
    writeJson(path.join(OUT, 'thermochemistry', cut.file + '.json'), {...target, thermochemistry});
    writeJson(path.join(OUT, 'flammability', cut.file + '.json'), {...target, flammability});
    console.log([cut.name.padEnd(12), sg.toFixed(4), hydrogen.toFixed(2).padStart(6), thermochemistry.elements.C.toFixed(3).padStart(9),
      thermochemistry.elements.H.toFixed(3).padStart(9), thermochemistry.formation_enthalpy_j_per_mol.toFixed(0).padStart(12),
      (lhv / cut.M / 1000).toFixed(3).padStart(10), flammability.lfl_volume_fraction.toFixed(5)].join(' '));
  }
  console.log('wrote ' + cuts.length + ' cuts to ' + OUT);
}
