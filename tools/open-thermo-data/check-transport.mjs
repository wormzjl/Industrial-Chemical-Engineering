// Checks the two transport transcriptions of lib.mjs against the check values their sources print:
// IAPWS R12-08 table 4 (points away from the critical region, where the enhancement mu2 is 1) and
// Lemmon and Jacobsen (2004) table V (nitrogen). Run: node check-transport.mjs
import { iapws2008Viscosity, nitrogenViscosity } from './lib.mjs';
const iapws = [[298.15, 998, 889.735100], [298.15, 1200, 1437.649467], [373.15, 1000, 307.883622], [433.15, 1, 14.538324],
  [433.15, 1000, 217.685358], [873.15, 1, 32.619287], [873.15, 100, 35.802262], [873.15, 600, 77.430195],
  [1173.15, 1, 44.217245], [1173.15, 100, 47.640433], [1173.15, 400, 64.154608]];
let worst = 0;
for (const [T, rho, mu] of iapws) worst = Math.max(worst, Math.abs(iapws2008Viscosity(T, rho) * 1e6 / mu - 1));
console.log(`IAPWS R12-08 table 4: worst relative deviation ${worst.toExponential(2)}`);
const lj = [[100, 0, 6.90349], [300, 0, 17.8771], [100, 25000, 79.7418], [200, 10000, 21.0810], [300, 5000, 20.7430], [126.195, 11180, 18.2978]];
worst = 0;
for (const [T, rho, mu] of lj) { const d = nitrogenViscosity(T, rho) * 1e6 / mu - 1; console.log(`  N2 ${T} K ${rho} mol/m3: ${(nitrogenViscosity(T, rho) * 1e6).toFixed(5)} uPa s (table ${mu}, ${d.toExponential(2)})`); worst = Math.max(worst, Math.abs(d)); }
console.log(`Lemmon-Jacobsen 2004 table V: worst relative deviation ${worst.toExponential(2)}`);
if (worst > 1e-4) process.exitCode = 1;
