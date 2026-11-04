// Worked examples for the rotating-machinery plan (batch 2026-11-03-rotating-machinery).
const g = 9.81, R = 8314.46;
const f = (x, d = 3) => Number(x).toFixed(d);
// 1. Centrifugal pump: rated 100 m3/h at 80 m, hydrocarbon 800 kg/m3, BEP efficiency 0.72, 2-pole 50 Hz.
const Qr = 100 / 3600, Hr = 80, rho = 800, etaB = 0.72, nr = 2950;
const head = (q, s) => Hr * (1.2 * s * s - 0.05 * q * s - 0.15 * q * q); // q = Q/Qr, s = n/nr
const eta = (q, s) => { const x = q / s; return etaB * Math.max(1e-9, 2 * x - x * x); };
const pw = (q, s) => q === 0 ? rho * g * Qr * Hr * (1.2 * s * s * s) / (etaB * 2) : rho * g * q * Qr * head(q, s) / eta(q, s);
console.log('PUMP rated: dp kPa', f(rho * g * Hr / 1e3, 1), 'hydraulic kW', f(rho * g * Qr * Hr / 1e3, 2), 'shaft kW', f(pw(1, 1) / 1e3, 2));
for (const q of [0, 0.5, 1, 1.5, 2]) console.log('  q', q, 'H m', f(head(q, 1), 1), 'eta', f(eta(q, 1) || 0, 3), 'P kW', f(pw(q, 1) / 1e3, 2));
// Operating point on a system with 30 m static head + friction through rated point; speed for 70 % flow.
const Hs = 30, K = (Hr - Hs); // H_sys = Hs + K q^2 (q in rated units)
const solveQ = s => { let lo = 0, hi = 3; for (let i = 0; i < 100; i++) { const m = (lo + hi) / 2; (head(m, s) - Hs - K * m * m > 0) ? lo = m : hi = m; } return lo; };
const speedFor = qt => { let lo = 0.3, hi = 1.5; for (let i = 0; i < 100; i++) { const m = (lo + hi) / 2; (solveQ(m) < qt) ? lo = m : hi = m; } return lo; };
console.log('  system Hs=30 m: q at 100% speed', f(solveQ(1)), '; speed for q=0.7:', f(speedFor(0.7)), '; q at 90% speed', f(solveQ(0.9)));
// Slip effect: rated slip 1.5 %; at 60 % load speed rises by 0.6 % -> flow change.
console.log('  slip: speed change rated->60% load %', f(1.5 * 0.4, 2), '; flow change on Hs=30 system %', f(100 * (solveQ(1.006) / solveQ(1) - 1), 2));
// Temperature rise from losses (pump): dT = dp (1-eta)/(rho eta cp)
console.log('  loss heating at BEP K', f(rho * g * Hr * (1 - etaB) / (rho * etaB * 2100), 3), '(cp 2100 J/kgK)');
// 2. Centrifugal compressor: natural gas M 18, k 1.27, T1 300 K, P1 1 bar, ratio 3, 3.5 kg/s, eta_p 0.78.
const M = 18, k = 1.27, T1 = 300, r = 3, mdot = 3.5, etap = 0.78;
const nn = 1 / (1 - (k - 1) / (k * etap)); // polytropic exponent n
const Hp = R / M * T1 * nn / (nn - 1) * (Math.pow(r, (nn - 1) / nn) - 1);
const Pgas = mdot * Hp / etap, T2 = T1 * Math.pow(r, (nn - 1) / nn);
const Wiso = mdot * R / M * T1 * Math.log(r);
const cp = k / (k - 1) * R / M;
console.log('COMPRESSOR: n', f(nn), 'Hp kJ/kg', f(Hp / 1e3, 1), 'gas power kW', f(Pgas / 1e3, 0), 'T2 K', f(T2, 1), '(dT', f(T2 - T1, 1) + ')');
console.log('  today isothermal work kW', f(Wiso / 1e3, 0), '-> booked dT K', f(Wiso / mdot / cp, 1), '; realistic dT from power K', f(Pgas / mdot / cp, 1));
const Qin = mdot / (1e5 * M / (R * T1)); console.log('  inlet m3/s', f(Qin, 2), '; stages at 40 kJ/kg', f(Hp / 40e3, 1));
// 3. Reciprocating compressor volumetric efficiency.
for (const c of [0.08, 0.12]) for (const rr of [2, 3, 4]) console.log('RECIP c', c, 'r', rr, 'eta_v', f(1 - c * (Math.pow(rr, 1 / k) - 1)), '; zero-flow ratio', f(Math.pow(1 + 1 / c, k), 1));
// 4. Back-pressure steam turbine 40 bar 400 C -> 4 bar (steam-table values).
const h1 = 3213.5, s1 = 6.769, hf = 604.66, hfg = 2133.4, sf = 1.7765, sfg = 5.1191;
const x = (s1 - sf) / sfg, h2s = hf + x * hfg, dhs = h1 - h2s;
for (const e of [0.5, 0.65, 0.8]) console.log('TURBINE eta_is', e, 'dh kJ/kg', f(e * dhs, 1), 'steam kg/kWh', f(3600 / (e * dhs), 2), '; for 30 kW pump kg/s', f(30 / (e * dhs), 4));
// 5. Shaft diameter from torque, tau_allow 40 MPa (keyed steel, torsion only).
const dShaft = (P, n) => Math.cbrt(16 * (P / (2 * Math.PI * n / 60)) / (Math.PI * 40e6));
for (const [P, n] of [[10e3, 2950], [30e3, 2950], [100e3, 2950], [1e6, 2980], [10e6, 9000], [100e3, 256], [1e6, 256], [5e6, 1490]]) console.log('SHAFT', f(P / 1e3, 0), 'kW at', n, 'rpm: torque Nm', f(P / (2 * Math.PI * n / 60), 0), ' d mm', f(1e3 * dShaft(P, n), 0), ' px', f(16 * dShaft(P, n), 2));
// 6. Energy per tick at candidate J-per-FE ratios.
for (const J of [2.5, 10, 40, 100]) console.log('FE ratio', J, 'J/FE:', [10e3, 30e3, 100e3, 1e6, 10e6].map(P => f(P / 20 / J, 0) + ' FE/t').join(', '), '(10 kW,30 kW,100 kW,1 MW,10 MW)');
