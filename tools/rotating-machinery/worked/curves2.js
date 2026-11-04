// Revised pump curve with a linear term (minimum slope at every speed): h = 1.21 s^2 - 0.05 q s - 0.15 q|q| - 0.01 q
const f = (x, d = 3) => Number(x).toFixed(d);
const h = (q, s) => 1.21 * s * s - 0.05 * q * s - 0.15 * q * Math.abs(q) - 0.01 * q;
const Hr = 80, Hs = 30, K = Hr - Hs;
const solveQ = s => { let lo = 0, hi = 3; for (let i = 0; i < 200; i++) { const m = (lo + hi) / 2; (h(m, s) * Hr - Hs - K * m * m > 0) ? lo = m : hi = m; } return lo; };
const speedFor = qt => { let lo = 0.2, hi = 1.5; for (let i = 0; i < 200; i++) { const m = (lo + hi) / 2; (solveQ(m) < qt) ? lo = m : hi = m; } return lo; };
console.log('h(1,1)', f(h(1, 1)), 'shutoff m', f(h(0, 1) * Hr, 1), 'at 1.5 m', f(h(1.5, 1) * Hr, 1), 'runout', f((-0.06 + Math.sqrt(0.0036 + 4 * 0.15 * 1.21)) / 0.3));
console.log('min slope at s=0.1', f(-0.05 * 0.1 - 0.01), '; q at full speed', f(solveQ(1)), '; speed for 70 %', f(speedFor(0.7)), '; q at 90 % speed', f(solveQ(0.9)));
// Damped affinity step for a power-limited driver: s' = s (Pa/PL)^(1/e); convergence factor 1 - m/e for P ~ s^m.
for (const e of [3, 6]) console.log('exponent 1/' + e + ': factor for m=3', f(1 - 3 / e, 2), 'm=5', f(1 - 5 / e, 2), 'm=8', f(1 - 8 / e, 2));
// Compressor example speed at flow coefficient 0.1, tip speed 300 m/s, 4.85 m3/s.
const Q = 4.85, D = Math.sqrt(4 * Q / (Math.PI * 0.1 * 300)); console.log('impeller m', f(D, 3), 'rpm', f(300 / (Math.PI * D) * 60, 0), '; impellers at 30 kJ/kg', f(177.5 / 30, 1));
