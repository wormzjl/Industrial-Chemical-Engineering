import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Heat exchanger coupling study (batch 2026-10-27-heat-exchanger-design).
 *
 * A counterflow exchanger is N wall cells between two zero-inventory streams (the fluid network's pipes own no fluid).
 * Each stream relaxes towards the wall temperature of each cell it passes: out = Tw + (in - Tw) exp(-g / W), with
 * g = 2 UA / N per side (equal film coefficients, 1/UA = 1/UA_h + 1/UA_c). The wall cells hold the exchanger's thermal
 * mass C, and their energy is an exact account: each side books the heat it gave or took.
 *
 * Part 1: steady effectiveness of the N-cell model against the exact counterflow formula.
 * Part 2: the two streams as two islands that advance in slices (the engine's cadence). Each slice keeps one wall profile
 * for its whole length; at its end it books its heat into the account. Against a fine-step coupled reference.
 *   FROZEN       the profile is the account at the slice start (plain explicit coupling)
 *   AVERAGED     the profile is the account's predicted mean over the slice under both sides' last conductances
 *   ASOF         AVERAGED, but the account is first read as of the slice start: the other side's heat booked past that
 *                time is taken out, and its heat not yet booked up to it is added at its last rate
 *   CAPPED       ASOF, with the other side's unbooked heat added for at most one of its slices (a lagging side then
 *                counts as stopped)
 * Part 3: a cold-outlet temperature controller (a bypass share set at each slice start, ASOF).
 * Part 4b: one side held 600 s (a parked hold); Part 4: held 60 s; each then catches up while the other runs on.
 *
 * Run: java HxCouplingSim.java
 *      java HxCouplingSim.java hold1s   (Part 4b's 1 s CAPPED run, outlets through the catch-up)
 */
public class HxCouplingSim {

    /** One stream's inputs: capacity rate W (W/K) and inlet temperature (deg C), with one step at tStep. */
    record Side(double w0, double tin0, double w1, double tin1, double tStep) {
        double w(double t) { return t < tStep ? w0 : w1; }
        double tin(double t) { return t < tStep ? tin0 : tin1; }
    }

    record Case(String name, int n, double ua, double c, Side hot, Side cold, double tEnd) {
        Side side(int s) { return s == 0 ? hot : cold; }
    }

    enum Scheme { FROZEN, AVERAGED, ASOF, CAPPED }

    /** The share of a cell's driving difference a stream of capacity rate w gives up: 1 - exp(-g/w), stable for large w. */
    static double share(double w, double g) {
        return w > 0 ? -Math.expm1(-g / w) : 0;
    }

    /** March a stream through the cells in its order. theta[k] is the stream entering cell k; q[k] the heat it gives cell k. */
    static double march(double w, double tin, double g, double[] wall, boolean forward, double[] theta, double[] q) {
        int n = wall.length;
        double t = tin, f = share(w, g);
        for (int i = 0; i < n; i++) {
            int k = forward ? i : n - 1 - i;
            theta[k] = t;
            double drop = f * (t - wall[k]);
            q[k] = w > 0 ? (w * f) * (t - wall[k]) : 0;
            if (w > 0 && w < 1e300) t -= drop;
        }
        return t;
    }

    /** The conductance a stream presents to one cell's wall: heat = G (theta - Tw). */
    static double conductance(double w, double g) {
        return w > 0 ? (w < 1e300 ? w * share(w, g) : g) : 0;
    }

    /** Steady wall profile: each cell at the conductance-weighted mean of the two streams entering it. */
    static double[] steadyWall(int n, double g, double wh, double th, double wc, double tc) {
        double[] wall = new double[n], thh = new double[n], thc = new double[n], q = new double[n];
        Arrays.fill(wall, (th + tc) / 2);
        double gh = conductance(wh, g), gc = conductance(wc, g);
        for (int it = 0; it < 200000; it++) {
            march(wh, th, g, wall, true, thh, q);
            march(wc, tc, g, wall, false, thc, q);
            double change = 0;
            for (int k = 0; k < n; k++) {
                double next = (gh * thh[k] + gc * thc[k]) / (gh + gc);
                change = Math.max(change, Math.abs(next - wall[k]));
                wall[k] = next;
            }
            if (change < 1e-12) break;
        }
        return wall;
    }

    static double exactCounterflow(double ntu, double cr) {
        if (Math.abs(1 - cr) < 1e-12) return ntu / (1 + ntu);
        double e = Math.exp(-ntu * (1 - cr));
        return (1 - e) / (1 - cr * e);
    }

    // ---------------------------------------------------------------- part 1

    static void effectivenessTable() {
        System.out.println("PART 1  N-cell counterflow effectiveness vs exact (equal film coefficients)");
        System.out.println("  NTU   Cr    exact   N=1     N=2     N=4     N=8     N=16    err(N=4) err(N=8)");
        double ua = 1;
        for (double ntu : new double[]{0.5, 1, 2, 5}) {
            for (double cr : new double[]{0, 0.5, 1}) {
                double wmin = ua / ntu, wmax = cr == 0 ? Double.MAX_VALUE : wmin / cr, exact = exactCounterflow(ntu, cr);
                StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %-5.1f %-5.2f %.4f ", ntu, cr, exact));
                double e4 = 0, e8 = 0;
                for (int n : new int[]{1, 2, 4, 8, 16}) {
                    double g = 2 * ua / n;
                    double[] wall = steadyWall(n, g, wmin, 100, wmax, 0);
                    double[] th = new double[n], q = new double[n];
                    double eps = (100 - march(wmin, 100, g, wall, true, th, q)) / 100;
                    if (n == 4) e4 = eps;
                    if (n == 8) e8 = eps;
                    row.append(String.format(Locale.ROOT, "%.4f  ", eps));
                }
                row.append(String.format(Locale.ROOT, "%+5.1f%%   %+5.1f%%", 100 * (e4 / exact - 1), 100 * (e8 / exact - 1)));
                System.out.println(row);
            }
        }
        System.out.println();
    }

    // ---------------------------------------------------------------- reference

    /** Fine-step coupled reference: wall cells integrated by RK4 with both streams quasi-steady; outlets at whole seconds. */
    static double[][] truth(Case c) {
        int n = c.n(), steps = (int) c.tEnd();
        double g = 2 * c.ua() / n, ck = c.c() / n, dt = .01;
        double[] wall = steadyWall(n, g, c.hot().w(0), c.hot().tin(0), c.cold().w(0), c.cold().tin(0));
        double[][] out = new double[steps + 1][2];
        double[] th = new double[n], q = new double[n];
        for (int i = 0; i <= steps; i++) {
            double t = i;
            out[i][0] = march(c.hot().w(t), c.hot().tin(t), g, wall, true, th, q);
            out[i][1] = march(c.cold().w(t), c.cold().tin(t), g, wall, false, th, q);
            for (int j = 0; j < 100; j++, t += dt) {
                double[] k1 = rate(c, g, ck, wall, t), k2 = rate(c, g, ck, add(wall, k1, dt / 2), t + dt / 2);
                double[] k3 = rate(c, g, ck, add(wall, k2, dt / 2), t + dt / 2), k4 = rate(c, g, ck, add(wall, k3, dt), t + dt);
                for (int k = 0; k < n; k++) wall[k] += dt / 6 * (k1[k] + 2 * k2[k] + 2 * k3[k] + k4[k]);
            }
        }
        return out;
    }

    static double[] rate(Case c, double g, double ck, double[] wall, double t) {
        int n = wall.length;
        double[] th = new double[n], qh = new double[n], qc = new double[n], d = new double[n];
        march(c.hot().w(t), c.hot().tin(t), g, wall, true, th, qh);
        march(c.cold().w(t), c.cold().tin(t), g, wall, false, th, qc);
        for (int k = 0; k < n; k++) d[k] = (qh[k] + qc[k]) / ck;
        return d;
    }

    static double[] add(double[] a, double[] b, double s) {
        double[] r = a.clone();
        for (int k = 0; k < r.length; k++) r[k] += s * b[k];
        return r;
    }

    // ---------------------------------------------------------------- partitioned islands

    /** Outlet temperature controller on the cold side: a bypass share beta, set at each cold slice start. */
    record Control(double target, double damping) {}

    /** A committed slice: its span and the heat it booked into each cell. */
    record Booked(double t0, double t1, double[] e) {}

    static final class Island {
        final int s;
        final double len;
        double tau, beta;                       // committed sim time; current bypass share
        final List<Booked> booked = new ArrayList<>();
        double[] g, theta, tinRecord;           // last committed conductance and stream temperatures per cell
        Island(int s, double len, int n) {
            this.s = s;
            this.len = len;
            g = new double[n];
            theta = new double[n];
            tinRecord = new double[1];
        }
    }

    record Run(double[][] outlets, double maxWallExcursion, double energyError) {}

    /**
     * Two islands in slices on a common real clock (1 s ticks). An island commits its next slice when the clock has
     * reached the slice's end and it is not held; a held island falls behind and catches up afterwards, up to 8 slices a tick.
     */
    static Run partitioned(Case c, Scheme scheme, double lenH, double lenC, Control control, double holdFrom, double holdTo) {
        int n = c.n(), steps = (int) c.tEnd();
        double g = 2 * c.ua() / n, ck = c.c() / n;
        double[] wall0 = steadyWall(n, g, c.hot().w(0), c.hot().tin(0), c.cold().w(0), c.cold().tin(0));
        double[] u = new double[n];
        for (int k = 0; k < n; k++) u[k] = ck * wall0[k];
        double initialEnergy = sum(u), bookedTotal = 0;
        Island[] isl = {new Island(0, lenH, n), new Island(1, lenC, n)};
        double[] q = new double[n];
        if (control != null) isl[1].beta = solveBeta(c.cold().w(0), c.cold().tin(0), g, wall0, control.target());
        for (Island is : isl) {
            Side side = c.side(is.s);
            double w = (1 - is.beta) * side.w(0);
            march(w, side.tin(0), g, wall0, is.s == 0, is.theta, q);
            Arrays.fill(is.g, conductance(w, g));
            is.tinRecord[0] = side.tin(0);
        }
        double[][] out = new double[steps + 1][2];
        for (double[] row : out) Arrays.fill(row, Double.NaN);
        double excursion = 0;
        double tLo = Math.min(Math.min(c.hot().tin0(), c.hot().tin1()), Math.min(c.cold().tin0(), c.cold().tin1()));
        double tHi = Math.max(Math.max(c.hot().tin0(), c.hot().tin1()), Math.max(c.cold().tin0(), c.cold().tin1()));
        for (int clock = 1; clock <= steps; clock++) {
            for (Island is : isl) {
                boolean held = is.s == 0 && clock > holdFrom && clock < holdTo;
                for (int burst = 0; !held && burst < 8 && is.tau + is.len <= clock + 1e-9 && is.tau + is.len <= steps + 1e-9; burst++) {
                    Island other = isl[1 - is.s];
                    double t0 = is.tau, t1 = t0 + is.len;
                    double[] account = read(scheme, u, ck, t0, other);
                    double[] wall = profile(scheme, c, is, other, t0, is.len, account, ck, g, is.beta, tLo, tHi);
                    if (control != null && is.s == 1) {
                        double target = solveBeta(c.cold().w(t0), c.cold().tin(t0), g, wall, control.target());
                        is.beta += control.damping() * (target - is.beta);
                        wall = profile(scheme, c, is, other, t0, is.len, account, ck, g, is.beta, tLo, tHi);
                    }
                    double[] e = slice(c, is, t0, t1, wall, g);
                    for (int k = 0; k < n; k++) {
                        u[k] += e[k];
                        bookedTotal += e[k];
                    }
                    is.booked.add(new Booked(t0, t1, e));
                    Side side = c.side(is.s);
                    double w = (1 - is.beta) * side.w(t1 - 1e-9);
                    march(w, side.tin(t1 - 1e-9), g, wall, is.s == 0, is.theta, q);
                    Arrays.fill(is.g, conductance(w, g));
                    is.tinRecord[0] = side.tin(t1 - 1e-9);
                    for (int i = (int) Math.ceil(t0); i <= Math.min(steps, (int) Math.floor(t1)); i++) {
                        double[] th = new double[n];
                        double wi = (1 - is.beta) * side.w(i), through = march(wi, side.tin(i), g, wall, is.s == 0, th, q);
                        out[i][is.s] = is.beta * side.tin(i) + (1 - is.beta) * through;
                    }
                    is.tau = t1;
                }
            }
            for (int k = 0; k < n; k++) excursion = Math.max(excursion, Math.max(u[k] / ck - tHi, tLo - u[k] / ck));
        }
        return new Run(out, excursion, Math.abs(sum(u) - initialEnergy - bookedTotal));
    }

    /** The account a slice starting at t reads: as booked (FROZEN, AVERAGED) or as of t (ASOF). */
    static double[] read(Scheme scheme, double[] u, double ck, double t, Island other) {
        int n = u.length;
        double[] e = u.clone();
        if (scheme == Scheme.ASOF || scheme == Scheme.CAPPED) {
            for (Booked b : other.booked) {
                if (b.t1() <= t + 1e-9) continue;
                double beyond = (b.t1() - Math.max(b.t0(), t)) / (b.t1() - b.t0());
                for (int k = 0; k < n; k++) e[k] -= beyond * b.e()[k];
            }
            if (other.tau < t - 1e-9 && !other.booked.isEmpty()) {
                Booked last = other.booked.get(other.booked.size() - 1);
                double span = scheme == Scheme.CAPPED ? Math.min(t - other.tau, other.len) : t - other.tau;
                double gap = span / (last.t1() - last.t0());
                for (int k = 0; k < n; k++) e[k] += gap * last.e()[k];
            }
        }
        for (int k = 0; k < n; k++) e[k] /= ck;
        return e;
    }

    static double[] profile(Scheme scheme, Case c, Island is, Island other, double t, double len, double[] account, double ck,
                            double g, double beta, double tLo, double tHi) {
        int n = account.length;
        if (scheme == Scheme.FROZEN) return account.clone();
        Side side = c.side(is.s);
        double w = (1 - beta) * side.w(t);
        double[] own = new double[n], q = new double[n], wall = new double[n];
        march(w, side.tin(t), g, account, is.s == 0, own, q);
        double gs = conductance(w, g);
        double lo = Math.min(side.tin(t), other.tinRecord[0]), hi = Math.max(side.tin(t), other.tinRecord[0]);
        for (int k = 0; k < n; k++) {
            double go = other.g[k], sumG = gs + go;
            double teq = sumG > 0 ? (gs * own[k] + go * other.theta[k]) / sumG : account[k];
            double x = sumG * len / ck, phi = x < 1e-9 ? 1 - x / 2 : -Math.expm1(-x) / x;
            wall[k] = Math.max(lo, Math.min(hi, teq + (account[k] - teq) * phi));
        }
        return wall;
    }

    /** The bypass share that brings the mixed cold outlet to the target against a fixed wall (bisection). */
    static double solveBeta(double w, double tin, double g, double[] wall, double target) {
        int n = wall.length;
        double[] th = new double[n], q = new double[n];
        double full = march(w, tin, g, wall, false, th, q);
        if ((full - tin) * (target - tin) <= 0) return 1;
        if (Math.abs(target - tin) >= Math.abs(full - tin)) return 0;
        double lo = 0, hi = 1;
        for (int i = 0; i < 80; i++) {
            double b = (lo + hi) / 2;
            double mixed = b * tin + (1 - b) * march((1 - b) * w, tin, g, wall, false, th, q);
            if (Math.abs(mixed - tin) > Math.abs(target - tin)) lo = b; else hi = b;
        }
        return (lo + hi) / 2;
    }

    /** Heat a side gives each wall cell over its slice, its inputs piecewise constant around the step. */
    static double[] slice(Case c, Island is, double t0, double t1, double[] wall, double g) {
        Side side = c.side(is.s);
        int n = wall.length;
        double[] e = new double[n], th = new double[n], q = new double[n];
        double[] cuts = side.tStep() > t0 && side.tStep() < t1 ? new double[]{t0, side.tStep(), t1} : new double[]{t0, t1};
        for (int p = 0; p + 1 < cuts.length; p++) {
            double mid = (cuts[p] + cuts[p + 1]) / 2;
            march((1 - is.beta) * side.w(mid), side.tin(mid), g, wall, is.s == 0, th, q);
            for (int k = 0; k < n; k++) e[k] += q[k] * (cuts[p + 1] - cuts[p]);
        }
        return e;
    }

    static double sum(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s;
    }

    // ---------------------------------------------------------------- driver

    static String fmt(double v) {
        return Double.isFinite(v) && Math.abs(v) < 1e5 ? String.format(Locale.ROOT, "%8.2f", v) : String.format(Locale.ROOT, "%8.1e", v);
    }

    public static void main(String[] args) {
        double u = 500, area = 75 * 1.0 * 1.0 * 6, ua = u * area; // D 1 m shell, 6 m tubes: 450 m2, UA 225 kW/K
        double steel = 12e3 * area, wet = 48e3 * area;            // J/K: steel only; steel plus water holdup
        int n = 8;
        double water50 = 50 * 4180;                                // 50 kg/s of water, W/K
        List<Case> cases = List.of(
                new Case("A hot inlet 150->180 C, water 50 kg/s each side, steel only", n, ua, steel,
                        new Side(water50, 150, water50, 180, 60), new Side(water50, 30, water50, 30, 1e9), 900),
                new Case("B cold flow 50->10 kg/s, steel only", n, ua, steel,
                        new Side(water50, 150, water50, 150, 1e9), new Side(water50, 30, water50 / 5, 30, 60), 900),
                new Case("C high flow 1.5 MW/K each side (stiff), steel only", n, ua, steel,
                        new Side(1.5e6, 150, 1.5e6, 180, 60), new Side(1.5e6, 30, 1.5e6, 30, 1e9), 900),
                new Case("D as C with the water holdup's heat capacity", n, ua, wet,
                        new Side(1.5e6, 150, 1.5e6, 180, 60), new Side(1.5e6, 30, 1.5e6, 30, 1e9), 900));
        if (args.length > 0 && args[0].equals("hold1s")) {
            hold1s(cases.get(0));
            return;
        }
        effectivenessTable();
        double[][] slicing = {{1, 1}, {5, 5}, {20, 20}, {5, 20}, {20, 5}};
        System.out.println("PART 2  islands in slices vs the fine-step coupled reference (8 cells, U 500 W/m2K, A 450 m2, UA 225 kW/K)");
        System.out.println("  tau_min = C/(4 UA): steel only " + String.format(Locale.ROOT, "%.1f s", steel / (4 * ua))
                + ", with water holdup " + String.format(Locale.ROOT, "%.1f s", wet / (4 * ua)));
        System.out.println("  columns: max |error| of hot and cold outlet from the step on (K); |error| over the last 100 s (K);");
        System.out.println("           worst account temperature beyond the inlet range (K); account energy error (J)");
        for (Case c : cases) {
            double[][] ref = truth(c);
            int last = (int) c.tEnd();
            System.out.println();
            System.out.println(c.name() + String.format(Locale.ROOT, "   (steady outlets %.2f / %.2f C)", ref[last][0], ref[last][1]));
            for (double[] sl : slicing) {
                for (Scheme scheme : Scheme.values()) {
                    Run run = partitioned(c, scheme, sl[0], sl[1], null, -1, -1);
                    double[] e = errors(run.outlets(), ref, 60, last);
                    System.out.println(String.format(Locale.ROOT, "  slices %4.0f/%-4.0f s %-9s max %s %s   tail %s %s   excursion %s   energy %.1e",
                            sl[0], sl[1], scheme, fmt(e[0]), fmt(e[1]), fmt(e[2]), fmt(e[3]), fmt(Math.max(0, run.maxWallExcursion())), run.energyError()));
                }
            }
        }
        System.out.println();
        System.out.println("PART 3  cold outlet target by a cold-side bypass (ASOF), hot inlet 150->180 C at 60 s");
        Case a = cases.get(0), stiff = cases.get(2);
        for (Object[] pair : new Object[][]{{a, 90.0}, {stiff, 40.0}}) {
            Case c = (Case) pair[0];
            double target = (Double) pair[1];
            for (double[] sl : slicing) {
                for (double damping : new double[]{1, .5}) {
                    Run run = partitioned(c, Scheme.ASOF, sl[0], sl[1], new Control(target, damping), -1, -1);
                    int last = (int) c.tEnd();
                    double worst = 0, settled = 0;
                    for (int i = 60; i <= last; i++) {
                        double v = run.outlets()[i][1];
                        if (Double.isNaN(v)) continue;
                        worst = Math.max(worst, Math.abs(v - target));
                        if (Math.abs(v - target) > .1) settled = i - 60;
                    }
                    System.out.println(String.format(Locale.ROOT, "  %s target %.0f C slices %4.0f/%-4.0f s damping %.1f: worst |T-target| %6.2f K, last outside 0.1 K at +%4.0f s, end %.4f C",
                            c.name().substring(0, 1), target, sl[0], sl[1], damping, worst, settled, run.outlets()[last][1]));
                }
            }
        }
        System.out.println();
        System.out.println("PART 4b hot island held from 100 s to 700 s (a parked hold), case A");
        for (double[] sl : slicing) {
            for (Scheme scheme : new Scheme[]{Scheme.ASOF, Scheme.CAPPED}) {
                Run run = partitioned(a, scheme, sl[0], sl[1], null, 100, 700);
                double[] e = errors(run.outlets(), refA(a), 60, 900);
                System.out.println(String.format(Locale.ROOT, "  slices %4.0f/%-4.0f s %-9s max %s %s   tail %s %s   excursion %s   energy %.1e",
                        sl[0], sl[1], scheme, fmt(e[0]), fmt(e[1]), fmt(e[2]), fmt(e[3]), fmt(Math.max(0, run.maxWallExcursion())), run.energyError()));
            }
        }
        System.out.println("PART 4  hot island held from 100 s to 160 s (then catches up, 8 slices a tick), case A");
        double[][] ref = truth(a);
        for (double[] sl : slicing) {
            for (Scheme scheme : new Scheme[]{Scheme.AVERAGED, Scheme.ASOF, Scheme.CAPPED}) {
                Run run = partitioned(a, scheme, sl[0], sl[1], null, 100, 160);
                double[] e = errors(run.outlets(), ref, 60, 900);
                System.out.println(String.format(Locale.ROOT, "  slices %4.0f/%-4.0f s %-9s max %s %s   tail %s %s   excursion %s   energy %.1e",
                        sl[0], sl[1], scheme, fmt(e[0]), fmt(e[1]), fmt(e[2]), fmt(e[3]), fmt(Math.max(0, run.maxWallExcursion())), run.energyError()));
            }
        }
    }

    /**
     * Part 4b's 1 s CAPPED run from 680 s. The held side gains 7 s a 1 s tick (8 slices a tick), so its 600 s backlog
     * lasts until about 786 s and the tail window (800 to 900 s) still holds the recovery.
     */
    static void hold1s(Case a) {
        double[][] ref = refA(a);
        Run run = partitioned(a, Scheme.CAPPED, 1, 1, null, 100, 700);
        System.out.println("PART 4b at 1/1 s slices, CAPPED: hot island held from 100 s to 700 s, outlets against the reference (both flowing), case A");
        for (int i = 680; i <= 900; i += 10)
            System.out.println(String.format(Locale.ROOT, "  %4d s   hot %s  ref %s   cold %s  ref %s",
                    i, fmt(run.outlets()[i][0]), fmt(ref[i][0]), fmt(run.outlets()[i][1]), fmt(ref[i][1])));
    }

    static double[][] refACache;

    static double[][] refA(Case a) {
        if (refACache == null) refACache = truth(a);
        return refACache;
    }

    /** Max error of each outlet from {@code from}, and max error over the last 100 s. */
    static double[] errors(double[][] out, double[][] ref, int from, int last) {
        double[] e = new double[4];
        for (int i = from; i <= last; i++) {
            for (int s = 0; s < 2; s++) {
                double v = out[i][s];
                if (Double.isNaN(v)) continue;
                double d = Math.abs(v - ref[i][s]);
                e[s] = Math.max(e[s], d);
                if (i > last - 100) e[2 + s] = Math.max(e[2 + s], d);
            }
        }
        return e;
    }
}
