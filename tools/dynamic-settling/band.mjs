// The band law of the dynamic settling plan, run on the review plant's receiver numbers (planning check, no mod code).
const g = 9.80665;
function settling(d, drho, mu, rho) { const st = g * d * d * Math.abs(drho) / (18 * mu); let v = st; for (let i = 0; i < 5; i++) v = st / (1 + .15 * Math.pow(rho * v * d / mu, .687)); return v; }
function rz(v, d, mu, rho, phi) { const re = rho * v * d / mu; const n = re < .2 ? 4.65 : re < 1 ? 4.35 * Math.pow(re, -.03) : re < 500 ? 4.45 * Math.pow(re, -.1) : 2.39; return { n, re, factor: Math.pow(1 - phi, n) }; }
// One layer: V own liquid, A plan area, v drop speed, per slice dt: own feed F (m3/s) at dispersed ratio r, own outflow Q.
function run({ V, A, v, F, r, Q, dt, steps, T = 0, H = 0, hindered = null, log = () => {} }) {
    let carried = 0, own = 0, firstCarry = -1;
    for (let k = 0; k < steps; k++) {
        const phi = T > 0 ? H / (T + H) : 0, speed = hindered ? v * Math.pow(1 - phi, hindered) : v;
        const swept = Math.min(T, speed * A * dt); if (T > 0) H -= H * swept / T; T -= swept;
        T += F * dt; H += F * r * dt;
        const clear = V + F * dt - T, out = Q * dt, X = Math.min(T, Math.max(0, out - clear));
        const c = T > 0 ? H * X / T : 0; H -= c; T -= X; V += F * dt - out;
        if (c > 0 && firstCarry < 0) firstCarry = k * dt;
        if (k >= steps - 20) { carried += c; own += out; }
        log(k, T, H, V, c, out);
    }
    return { ratio: own > 0 ? carried / own : 0, firstCarry, T, H, V };
}
const OIL = 666.35, WATER = 983.79, MUO = 2.999e-4, MUW = 4.732e-4;
const vw = settling(5e-4, WATER - OIL, MUO, OIL), vo = settling(2e-4, WATER - OIL, MUW, WATER);
console.log('water drop in oil', vw.toFixed(4), 'oil drop in water', vo.toFixed(4));
// 1. Steady flow against today's law (1 - N) r, at slices of 5 s and 20 s.
for (const [name, V, A, v, Q, r] of [['1x6 drum at 4x, boot', .0628, .126, vo, 4 * 3.74e-4, 1], ['2x5 drum at 16x, boot', .244, .349, vo, 16 * 3.74e-4, 1], ['2x5 drum at 128x, oil layer', 7.6, 9.84, vw, 128 * .0042, .089], ['2x5 drum at design, oil layer', 7.6, 9.84, vw, .0042, .089]])
    for (const dt of [5, 20]) {
        const N = v * A / Q, s = run({ V, A, v, F: Q, r, Q, dt, steps: 4000 });
        console.log(name, 'dt', dt, 'N', N.toFixed(3), 'law', (Math.max(0, 1 - N) * r).toFixed(4), 'band', s.ratio.toFixed(4), 'first carry at s', s.firstCarry, 'clean period V/(Q-vA)', N < 1 ? (V / (Q - v * A)).toFixed(0) : '-');
    }
// 2. Batch: a vessel filled turbid, feed stopped. Time to clear against h / v.
for (const [name, h, v, d, mu, rho, phi] of [['naphtha, water drops 0.5 mm, 9 % water', 2, vw, 5e-4, MUO, OIL, .082], ['water, oil drops 0.2 mm, 9 % oil', 2, vo, 2e-4, MUW, WATER, .082],
    ['heavy crude 900 kg/m3 10 mPa s, water drops 0.5 mm, 10 %', 2, settling(5e-4, 90, .01, 900), 5e-4, .01, 900, .1], ['the same, 30 % water', 2, settling(5e-4, 90, .01, 900), 5e-4, .01, 900, .3],
    ['the same, stirred to 0.1 mm, 30 % water', 2, settling(1e-4, 90, .01, 900), 1e-4, .01, 900, .3]]) {
    const A = 1, V = h * A, r = phi / (1 - phi), k = rz(v, d, mu, rho, phi);
    let t1 = -1, t2 = -1;
    run({ V, A, v, F: 0, r: 0, Q: 0, dt: 1, steps: 400000, T: V, H: V * r, log: (i, T) => { if (T <= 0 && t1 < 0) t1 = i + 1; } });
    run({ V, A, v, F: 0, r: 0, Q: 0, dt: 1, steps: 4000000, T: V, H: V * r, hindered: k.n, log: (i, T) => { if (T <= 1e-9 && t2 < 0) t2 = i + 1; } });
    console.log(name, '| v mm/s', (v * 1e3).toFixed(3), 'h/v s', (h / v).toFixed(0), 'band s', t1, '| Re', k.re.toFixed(2), 'n', k.n.toFixed(2), 'factor', k.factor.toFixed(3), 'hindered h/v s', (h / v / k.factor).toFixed(0), 'band s', t2);
}
// 3. A feed that stops: the 1x6 drum's boot at 4x for 20 min, then no flow.
{
    const V = .0628, A = .126, Q = 4 * 3.74e-4; let s = run({ V, A, v: vo, F: Q, r: 1, Q, dt: 5, steps: 240 });
    let t = -1; run({ V, A, v: vo, F: 0, r: 0, Q: 0, dt: 5, steps: 100, T: s.T, H: s.H, log: (i, T) => { if (T <= 0 && t < 0) t = (i + 1) * 5; } });
    console.log('boot after 20 min at 4x: turbid', (s.T / V * 100).toFixed(0), '% of it; feed stopped: clear after s', t, 'h/v', (.5 / vo).toFixed(0));
}
// 4. Sub-steps: the same slice advanced in n equal parts (the arithmetic alone; the step is not re-solved).
console.log('--- sub-steps');
for (const [name, V, A, v, Q, r] of [['1x6 at 4x, boot', .0628, .126, vo, 4 * 3.74e-4, 1], ['2x5 at 128x, oil layer', 7.6, 9.84, vw, 128 * .0042, .089], ['2x5 at 512x, oil layer', 7.6, 9.84, vw, 512 * .0042, .089]])
    for (const dt of [5, 20]) for (const n of [1, 4, 16, 64]) {
        const N = v * A / Q, s = run({ V, A, v, F: Q, r, Q, dt: dt / n, steps: 4000 * n });
        console.log(name, 'slice', dt, 'parts', n, 'turnover per slice', (Q * dt / V).toFixed(2), 'law', (Math.max(0, 1 - N) * r).toFixed(4), 'band', s.ratio.toFixed(4), 'first carry s', s.firstCarry.toFixed(0), 'of', N < 1 ? (V / (Q - v * A)).toFixed(0) : '-');
    }
// 5. The plan's remaining numbers: the 16x boot's clean period with 16 parts, and a 0.2 mm bubble's rise.
{
    const s = run({ V: .244, A: .349, v: vo, F: 16 * 3.74e-4, r: 1, Q: 16 * 3.74e-4, dt: 5 / 16, steps: 4000 * 16 });
    console.log('--- 2x5 at 16x boot, 16 parts: first carry s', s.firstCarry.toFixed(0), 'ratio', s.ratio.toFixed(4));
    const bn = settling(2e-4, OIL - 5.2, MUO, OIL), bh = settling(2e-4, 900 - 5, .01, 900);
    console.log('bubble 0.2 mm: naphtha mm/s', (bn * 1e3).toFixed(1), 'heavy crude mm/s', (bh * 1e3).toFixed(2), '2 m in min', (2 / bh / 60).toFixed(0));
}
