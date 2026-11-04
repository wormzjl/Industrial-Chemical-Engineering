// Normalized machine laws proposed in ROTATING_MACHINERY_PLAN.md (process level).
const f = (x, d = 3) => Number(x).toFixed(d);
// Centrifugal pump: h = 1.2 s^2 - 0.05 q s - 0.15 q^2 ; p = 0.35 s^3 + 0.65 q s^2 ; eta = eta_r q h / p
const h = (q, s) => 1.2 * s * s - 0.05 * q * s - 0.15 * q * q, p = (q, s) => 0.35 * s ** 3 + 0.65 * q * s * s;
for (const q of [0, 0.25, 0.5, 0.75, 1, 1.25, 1.5, 2]) console.log('PUMP q', q, 'h', f(h(q, 1)), 'p', f(p(q, 1)), 'eta/eta_r', f(q * h(q, 1) / p(q, 1)), 'dh/dq', f(-0.05 - 0.3 * q));
console.log('PUMP runout q (h=0)', f((-0.05 + Math.sqrt(0.0025 + 0.72)) / 0.3));
// Centrifugal compressor: h/s^2 = a0 + a1 x + a2 x^2, x = q/s ; surge at x = 0.75, linear C1 extension below.
const a2 = -0.889, a1 = 1.244, a0 = 0.645, xs = 0.75, slope = a1 + 2 * a2 * xs, hs = a0 + a1 * xs + a2 * xs * xs;
const hc = x => x >= xs ? a0 + a1 * x + a2 * x * x : hs + slope * (x - xs);
for (const x of [0, 0.5, 0.75, 0.9, 1, 1.2, 1.4]) console.log('COMP x', x, 'h', f(hc(x)), 'slope', f(x >= xs ? a1 + 2 * a2 * x : slope));
// Ratio from polytropic head with sigma fixed (3.5) vs gas-specific sigma, at H rho/P = 1 and 2.
for (const sig of [2.75, 3.5, 4.3]) console.log('RATIO sigma', sig, 'r at H*rho/P=1:', f(Math.pow(1 + 1 / sig, sig)), ' at 2:', f(Math.pow(1 + 2 / sig, sig)));
// PD corner law: (Q/Qd)^m + (dp/dpr)^m = 1 ; flow deficit at 90 % and 95 % of relief.
for (const m of [8, 16]) console.log('PD m', m, 'Q/Qd at dp/dpr=0.5', f(Math.pow(1 - 0.5 ** m, 1 / m), 4), '0.9', f(Math.pow(1 - 0.9 ** m, 1 / m), 4), '0.95', f(Math.pow(1 - 0.95 ** m, 1 / m), 4), 'corner', f(Math.pow(0.5, 1 / m), 4));
// Turbine isentropic drop: ideal-gas form with real P/rho vs steam table (40 bar 400 C -> 4 bar: 528.2 kJ/kg).
const P1 = 4e6, v1 = 0.07341, k = 1.3, r = 0.1;
console.log('TURBINE dh_is ideal-gas form kJ/kg', f(k / (k - 1) * P1 * v1 * (1 - Math.pow(r, (k - 1) / k)) / 1e3, 1), 'table 528.2');
