// The open comparisons that replace the JANAF and WebBook holdouts of the reference-spine records (O2 of batch
// 2026-11-04-open-thermo-data): NASA CEA against the CoolProp alpha0 at 1000, 1100 and 1200 K; methane's CEA table
// against the bundled spine; CO2 below its triple point against the rigid-rotor harmonic-oscillator limit (100 K) and
// CEA (200 K). Prints the numbers records.mjs quotes. Run: node spine-checks.mjs
import * as L from './lib.mjs';
for (const [fluid, formula] of [['Nitrogen', 'N2'], ['Ethane', 'C2H6'], ['CarbonDioxide', 'CO2']]) {
  const a = L.coolpropIdealGas(fluid), c = L.ceaSpecies(formula);
  console.log(`${fluid.padEnd(14)} CEA / CoolProp alpha0 - 1: ${[1000, 1100, 1200].map(T => `${T} K ${L.pct(c.cp(T) / a.cp(T) - 1)}`).join(', ')}`);
}
const spine = L.spineCp('methane').cp, ch4 = L.ceaSpecies('CH4');
console.log(`Methane        CEA / spine - 1: ${[1000, 1100, 1200].map(T => `${T} K ${L.pct(ch4.cp(T) / spine(T) - 1)}`).join(', ')}`);
// Rigid rotor (Cp/R = 7/2 for a linear molecule) plus harmonic oscillators at CO2's fundamentals, cm-1.
const R = 8.314462618, c2 = 1.438776877, E = x => x * x * Math.exp(x) / Math.expm1(x) ** 2;
const rrho = T => R * (3.5 + E(1333 * c2 / T) + 2 * E(667.4 * c2 / T) + E(2349.1 * c2 / T));
const co2 = L.coolpropIdealGas('CarbonDioxide'), cea = L.ceaSpecies('CO2');
console.log(`CO2 alpha0 / RRHO - 1 at 100 K: ${L.pct(co2.cp(100) / rrho(100) - 1)}; alpha0 / CEA - 1 at 200 K: ${L.pct(co2.cp(200) / cea.cp(200) - 1)}`);
console.log(`CO2 at 200 K: h - h(298.15) alpha0 ${(co2.h(200) - co2.h(298.15)).toFixed(2)} J/mol, CEA ${(cea.h(200) - cea.h(298.15)).toFixed(2)} J/mol; CEA S(200 K, 1 bar) ${cea.s(200).toFixed(4)} J/(mol K)`);
