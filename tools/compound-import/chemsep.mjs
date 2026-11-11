// Reader for the ChemSep v8.3 pure-component XML (chemsep1.xml, chemsep2.xml) and the ChemSep temperature
// correlations. Data only: the files are parsed as text, nothing in them is executed.
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const SCALARS = {
  index: 'LibraryIndex', name: 'CompoundID', cas: 'CAS', formula: 'StructureFormula', smiles: 'Smiles',
  tc: 'CriticalTemperature', pc: 'CriticalPressure', vc: 'CriticalVolume', zc: 'CriticalCompressibility',
  omega: 'AcentricityFactor', mw: 'MolecularWeight', tb: 'NormalBoilingPointTemperature',
  tm: 'NormalMeltingPointTemperature', tt: 'TriplePointTemperature', pt: 'TriplePointPressure',
  hfus: 'HeatOfFusionAtMeltingPoint', sg: 'SpecificGravity', zra: 'RacketParameter',
  hf: 'HeatOfFormation', // ideal-gas heat of formation at 298.15 K, J/kmol
};
const CORRELATIONS = {
  cp: 'IdealGasHeatCapacityCp', rho: 'LiquidDensity', psat: 'VaporPressure',
  muL: 'LiquidViscosity', muV: 'VaporViscosity',
};

function attr(block, tag) {
  const m = block.match(new RegExp('<' + tag + '\\b[^>]*?value="([^"]*)"'));
  return m ? m[1] : null;
}

function correlation(block, tag) {
  const m = block.match(new RegExp('<' + tag + '\\b[^>]*>([\\s\\S]*?)</' + tag + '>'));
  if (!m) return null;
  const body = m[1];
  const get = (t) => { const v = body.match(new RegExp('<' + t + '\\b[^>]*?value="([^"]*)"')); return v ? Number(v[1]) : null; };
  const c = { eqno: get('eqno'), A: get('A'), B: get('B'), C: get('C'), D: get('D'), E: get('E'), tmin: get('Tmin'), tmax: get('Tmax') };
  if (c.eqno === null) return null;
  for (const k of ['A', 'B', 'C', 'D', 'E']) if (c[k] === null) c[k] = 0;
  return c;
}

function groups(block, method) {
  const m = block.match(new RegExp('<GCmethod name="' + method + '"\\s*>([\\s\\S]*?)</GCmethod>'));
  if (!m) return null;
  return [...m[1].matchAll(/<group id="(\d+)"\s+value="([^"]*)"/g)].map((g) => [Number(g[1]), Number(g[2])]);
}

/** The [id, count] pairs of a group element such as <ModifiedUnifac name="Modified UNIFAC">, or null. */
function groupElement(block, tag) {
  const m = block.match(new RegExp('<' + tag + '\\b[^>]*>([\\s\\S]*?)</' + tag + '>'));
  if (!m) return null;
  return [...m[1].matchAll(/<group id="(\d+)"\s+value="([^"]*)"/g)].map((g) => [Number(g[1]), Number(g[2])]);
}

/**
 * Every compound of both files, in file order, with its source file name. chemsep2.xml may be absent (the groups
 * extract tmp/dwsim-groups holds chemsep1.xml only); a compound only it gives is then not found by CAS, which
 * import.mjs refuses.
 */
export function readChemSep(dir) {
  const compounds = [];
  for (const file of ['chemsep1.xml', 'chemsep2.xml']) {
    if (file === 'chemsep2.xml' && !existsSync(join(dir, file))) continue;
    const text = readFileSync(join(dir, file), 'latin1');
    for (const block of text.split('<compound>').slice(1)) {
      const c = { file };
      for (const [key, tag] of Object.entries(SCALARS)) {
        const v = attr(block, tag);
        c[key] = v === null ? null : (['index', 'name', 'cas', 'formula', 'smiles'].includes(key) ? v : Number(v));
      }
      for (const [key, tag] of Object.entries(CORRELATIONS)) c[key] = correlation(block, tag);
      c.ppr78 = groups(block, 'PPR78');
      // Modified UNIFAC (Dortmund) subgroup numbers and counts (batch 2026-11-11-aqueous-solvents, plan M3).
      c.modifiedUnifac = groupElement(block, 'ModifiedUnifac');
      compounds.push(c);
    }
  }
  return compounds;
}

/** ChemSep equation number `eqno` at temperature t (K), in the record's own units. */
export function evaluate(c, t) {
  const { A, B, C, D, E } = c;
  switch (c.eqno) {
    case 1: return A;
    case 2: return A + B * t;
    case 3: return A + B * t + C * t * t;
    case 4: return A + B * t + C * t * t + D * t ** 3;
    case 10: return Math.exp(A - B / (C + t));
    case 16: return A + Math.exp(B / t + C + D * t + E * t * t);
    case 100: return A + B * t + C * t * t + D * t ** 3 + E * t ** 4;
    case 101: return Math.exp(A + B / t + C * Math.log(t) + D * t ** E);
    case 102: return A * t ** B / (1 + C / t + D / (t * t));
    case 105: return A / B ** (1 + (1 - t / C) ** D);
    case 107: {
      const x = C / t, y = E / t;
      return A + B * (x / Math.sinh(x)) ** 2 + D * (y / Math.cosh(y)) ** 2;
    }
    default: throw new Error('unsupported ChemSep equation ' + c.eqno);
  }
}
