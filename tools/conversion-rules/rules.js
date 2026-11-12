// Writes the bundled fluid conversion rules (W6) under src/main/resources/data/icheme/icheme/fluid_conversion/.
// Run from the worktree root: node tools/conversion-rules/rules.js
const fs = require('fs'), path = require('path');
const root = 'src/main/resources/data/icheme/icheme/fluid_conversion';
const any = { min: 0, max: 1 };
const bar = 1e5;
const free = (...names) => Object.fromEntries(names.map(n => [n, any]));
const C5 = ['Isopentane', 'N-pentane'];
const C6AROM = ['N-hexane', 'Cyclohexane', 'Benzene', 'Toluene', 'Ethylbenzene', 'O-xylene', 'M-xylene', 'P-xylene'];
const C3C4 = ['Propane', 'Propylene', 'Isobutane', 'N-butane', 'Butene-1', 'Isobutene', 'Cis-2-butene', 'Trans-2-butene', 'Butadiene'];
const liquidP = { min: 0.8 * bar, max: 5 * bar };
const gasT = { min: 200, max: 400 }, gasP = { min: 0.5 * bar, max: 10 * bar };

// Product windows, mass basis (plan 3.3: composition windows on icheme:refinery compounds and cut ranges).
const products = {
  gasoline: { priority: 41, to_forge: { basis: 'mass', components: { ...free(...C5, ...C6AROM, 'crude_pc01', 'crude_pc02'), 'Isobutane': { min: 0, max: 0.05 }, 'N-butane': { min: 0, max: 0.08 }, 'crude_pc03': { min: 0, max: 0.15 } }, others_max: 0.02, temperature: { min: 233.15, max: 323.15 }, pressure: liquidP } },
  naphtha: { priority: 40, to_forge: { basis: 'mass', components: { ...free(...C5, ...C6AROM, 'crude_pc01', 'crude_pc02'), 'Isobutane': { min: 0, max: 0.03 }, 'N-butane': { min: 0, max: 0.05 }, 'crude_pc03': { min: 0, max: 0.4 }, 'crude_pc04': { min: 0, max: 0.05 } }, others_max: 0.02, temperature: { min: 233.15, max: 323.15 }, pressure: liquidP } },
  kerosene: { priority: 30, to_forge: { basis: 'mass', components: { ...free('crude_pc03', 'crude_pc04', 'crude_pc05'), 'crude_pc02': { min: 0, max: 0.08 }, 'crude_pc06': { min: 0, max: 0.08 } }, others_max: 0.01, temperature: { min: 233.15, max: 353.15 }, pressure: liquidP } },
  diesel: { priority: 20, to_forge: { basis: 'mass', components: { ...free('crude_pc05'), 'crude_pc03': { min: 0, max: 0.05 }, 'crude_pc04': { min: 0, max: 0.4 }, 'crude_pc06': { min: 0.15, max: 1 }, 'crude_pc07': { min: 0, max: 0.5 }, 'crude_pc08': { min: 0, max: 0.05 } }, others_max: 0.01, temperature: { min: 253.15, max: 353.15 }, pressure: liquidP } },
  lubricant: { priority: 15, to_forge: { basis: 'mass', components: { 'crude_pc07': { min: 0, max: 0.25 }, 'crude_pc08': { min: 0.4, max: 1 }, 'crude_pc09': { min: 0, max: 0.5 }, 'crude_pc10': { min: 0, max: 0.05 } }, others_max: 0.01, temperature: { min: 263.15, max: 373.15 }, pressure: liquidP } },
  heavy_oil: { priority: 10, to_forge: { basis: 'mass', components: { ...free('crude_pc07', 'crude_pc08', 'crude_pc09', 'crude_pc10', 'crude_pc11', 'crude_pc12'), 'crude_pc05': { min: 0, max: 0.05 }, 'crude_pc06': { min: 0, max: 0.15 } }, others_max: 0.01, temperature: { min: 273.15, max: 423.15 }, pressure: liquidP } },
  lpg_gas: { phase: 'gas', priority: 45, to_forge: { basis: 'mass', components: { ...free(...C3C4), 'Ethane': { min: 0, max: 0.02 } }, others_max: 0.03, temperature: gasT, pressure: gasP } },
  // Liquid LPG at the atmospheric converter: refrigerated, below its bubble point at about 1 atm (propane -42 C, n-butane -0.5 C).
  lpg_liquid: { priority: 45, to_forge: { basis: 'mass', components: { ...free(...C3C4), 'Ethane': { min: 0, max: 0.02 } }, others_max: 0.03, temperature: { min: 183.15, max: 275.15 }, pressure: { min: 0.8 * bar, max: 2 * bar } } },
};
const pure = (compound, priority, extra = {}) => ({ phase: 'gas', priority, to_forge: { basis: 'mole', components: { [compound]: { min: 0.98, max: 1 }, ...extra }, others_max: 0.02, temperature: gasT, pressure: gasP } });

const rules = {
  // Forward (plan 3.3: water and crude only).
  'minecraft/water': { fluid: 'minecraft:water', phase: 'liquid', to_icheme: { composition: { Water: 1 }, temperature: 'ambient', pressure: 'ambient' },
    to_forge: { basis: 'mole', components: { Water: { min: 0.99, max: 1 } }, others_max: 0.01, temperature: { min: 278.15, max: 333.15 }, pressure: { min: 0.9 * bar, max: 1.5 * bar } } },
  'c/crude_oil': { fluid: '#c:crude_oil', phase: 'liquid', to_icheme: { preset: 'medium_crude' } },
  'tfmg/crude_oil': { priority: 1, fluid: 'tfmg:crude_oil', phase: 'liquid', to_icheme: { preset: 'medium_crude' } },
  'pneumaticcraft/oil': { priority: 1, fluid: 'pneumaticcraft:oil', phase: 'liquid', to_icheme: { preset: 'medium_crude' } },
  'ad_astra/oil': { priority: 1, fluid: 'ad_astra:oil', phase: 'liquid', to_icheme: { preset: 'medium_crude' } },
  // Reverse, liquid products.
  'tfmg/gasoline': { fluid: 'tfmg:gasoline', ...products.gasoline },
  'tfmg/naphtha': { fluid: 'tfmg:naphtha', ...products.naphtha },
  'tfmg/kerosene': { fluid: 'tfmg:kerosene', ...products.kerosene },
  'tfmg/diesel': { fluid: 'tfmg:diesel', ...products.diesel },
  'tfmg/lubrication_oil': { fluid: 'tfmg:lubrication_oil', ...products.lubricant },
  'tfmg/heavy_oil': { fluid: 'tfmg:heavy_oil', ...products.heavy_oil },
  'pneumaticcraft/gasoline': { fluid: 'pneumaticcraft:gasoline', ...products.gasoline },
  'pneumaticcraft/kerosene': { fluid: 'pneumaticcraft:kerosene', ...products.kerosene },
  'pneumaticcraft/diesel': { fluid: 'pneumaticcraft:diesel', ...products.diesel },
  'pneumaticcraft/lubricant': { fluid: 'pneumaticcraft:lubricant', ...products.lubricant },
  'pneumaticcraft/lpg': { fluid: 'pneumaticcraft:lpg', phase: 'liquid', ...products.lpg_liquid },
  // Ad Astra's rocket fuel: a kerosene cut (RP-1), below the kerosene fluids.
  'ad_astra/fuel': { fluid: 'ad_astra:fuel', ...products.kerosene, priority: 29 },
  // Reverse, gases (gaseous Forge fluids: TFMG's GasFluidType, density -1; Mekanism's steam, density 0).
  'tfmg/lpg': { fluid: 'tfmg:lpg', ...products.lpg_gas },
  'tfmg/propane': { fluid: 'tfmg:propane', ...pure('Propane', 50) },
  'tfmg/butane': { fluid: 'tfmg:butane', phase: 'gas', priority: 50, to_forge: { basis: 'mole', components: free('Isobutane', 'N-butane'), others_max: 0.02, temperature: { min: 280, max: 400 }, pressure: gasP } },
  'tfmg/hydrogen': { fluid: 'tfmg:hydrogen', ...pure('Hydrogen', 50) },
  'tfmg/ethylene': { fluid: 'tfmg:ethylene', ...pure('Ethylene', 50) },
  'tfmg/propylene': { fluid: 'tfmg:propylene', ...pure('Propylene', 50) },
  'tfmg/carbon_dioxide': { fluid: 'tfmg:carbon_dioxide', ...pure('CarbonDioxide', 50) },
  'tfmg/air': { fluid: 'tfmg:air', phase: 'gas', priority: 50, to_forge: { basis: 'mole', components: { Nitrogen: { min: 0.76, max: 0.8 }, Oxygen: { min: 0.19, max: 0.23 } }, others_max: 0.02, temperature: gasT, pressure: gasP } },
  'mekanism/steam': { fluid: 'mekanism:steam', phase: 'gas', priority: 50, to_forge: { basis: 'mole', components: { Water: { min: 0.99, max: 1 } }, others_max: 0.01, temperature: { min: 368.15, max: 623.15 }, pressure: { min: 0.8 * bar, max: 5 * bar } } },
};

fs.rmSync(root, { recursive: true, force: true });
for (const [id, r] of Object.entries(rules)) {
  const rule = { revision: '1', ...(r.priority ? { priority: r.priority } : {}), fluid: r.fluid, phase: r.phase || 'liquid' };
  if (r.to_icheme) rule.to_icheme = r.to_icheme;
  if (r.to_forge) rule.to_forge = r.to_forge;
  const file = path.join(root, id + '.json');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(rule, null, 2) + '\n');
}
console.log(Object.keys(rules).length + ' rules');
