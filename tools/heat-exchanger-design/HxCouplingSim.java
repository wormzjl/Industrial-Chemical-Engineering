import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

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
 *   PREDICTED    CAPPED, the lagging side's unbooked heat predicted as G (theta - T) on the account (rejected: an
 *                explicit step over a whole slice, unstable)
 *   INFLIGHT     CAPPED, the lagging side's unbooked heat read at the rate of its open slice, fixed when that slice was
 *                dispatched (its profile is fixed then)
 *   JOINT        INFLIGHT, and the wall the cells head for is the exchanger's steady wall with both streams marched
 *                (the other side's inlet and flow from its last commit), not the cell-local mean of the two streams
 * Part 3: a cold-outlet temperature controller (a bypass share set at each slice start, ASOF).
 * Part 4b: one side held 600 s (a parked hold); Part 4: held 60 s; each then catches up while the other runs on.
 * Part 5 (owner answer Q2, revision 2): a film coefficient per phase (liquid 2000, condensing 5000, gas 100 W/m2K) on
 * each side, counterflow; the four schemes from CAPPED on; 5b the holds again; 5c the wall's heat capacity.
 * Part 6: a stability map (liquid/liquid across flows and walls, a condensing mixture with a glide), INFLIGHT and JOINT.
 * Note: Parts 2 to 6 plan a slice when it commits, from the other side's latest record; at equal slices that is the other
 * side's end-of-slice state, which a real island does not have at dispatch, so they are optimistic. Part 7 plans at dispatch.
 * Part 7 (owner answer Q10, revision 3): the wall holds no heat. Each slice heads for the quasi-steady wall (both streams
 * marched, the other side as of the dispatch); the account holds only the heat in transit (what the two sides booked
 * differently), and each side gives back its share, clamped so its outlet stays between the two inlets. Against the
 * quasi-steady exchanger; 7b the holds; 7c the controller; 7d three give-back splits (conductance, capacity rate, slice heat).
 * Part 8 (revision 4, W3a; the timing argument): Part 7 on the game's clock. A slice is dispatched once online time reaches
 * its end and commits 0 to 40 ticks later; stopped only while held; three forms of reading the partner (section 5.2).
 *
 * Run: java HxCouplingSim.java
 *      java HxCouplingSim.java hold1s    (Part 4b's 1 s CAPPED run, outlets through the catch-up)
 *      java HxCouplingSim.java lowflow   (liquid/liquid at 10 kg/s, 20/20 s slices, to 3000 s: JOINT's slow tail)
 *      java HxCouplingSim.java timing    (Part 8, W3a: the relay on the game's clock, forms A, B and C)
 */
public class HxCouplingSim {

    /** One stream's inputs: capacity rate W (W/K) and inlet temperature (deg C), with one step at tStep. */
    record Side(double w0, double tin0, double w1, double tin1, double tStep) {
        double w(double t) { return t < tStep ? w0 : w1; }
        double tin(double t) { return t < tStep ? tin0 : tin1; }
    }

    /** uaHot, uaCold: each side's film conductance h A (W/K); equal films are 2 UA each. */
    record Case(String name, int n, double uaHot, double uaCold, double c, Side hot, Side cold, double tEnd) {
        static Case equalFilms(String name, int n, double ua, double c, Side hot, Side cold, double tEnd) {
            return new Case(name, n, 2 * ua, 2 * ua, c, hot, cold, tEnd);
        }
        Side side(int s) { return s == 0 ? hot : cold; }
        /** One cell's share of side s's film conductance. */
        double g(int s) { return (s == 0 ? uaHot : uaCold) / n; }
    }

    enum Scheme { FROZEN, AVERAGED, ASOF, CAPPED, PREDICTED, INFLIGHT, JOINT }

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
    static double[] steadyWall(int n, double gHot, double gCold, double wh, double th, double wc, double tc) {
        double[] wall = new double[n], thh = new double[n], thc = new double[n], q = new double[n];
        Arrays.fill(wall, (th + tc) / 2);
        double gh = conductance(wh, gHot), gc = conductance(wc, gCold);
        for (int it = 0; it < 200000; it++) {
            march(wh, th, gHot, wall, true, thh, q);
            march(wc, tc, gCold, wall, false, thc, q);
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
                    double[] wall = steadyWall(n, g, g, wmin, 100, wmax, 0);
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
        double gh = c.g(0), gc = c.g(1), ck = c.c() / n, dt = .01;
        double[] wall = steadyWall(n, gh, gc, c.hot().w(0), c.hot().tin(0), c.cold().w(0), c.cold().tin(0));
        double[][] out = new double[steps + 1][2];
        double[] th = new double[n], q = new double[n];
        for (int i = 0; i <= steps; i++) {
            double t = i;
            out[i][0] = march(c.hot().w(t), c.hot().tin(t), gh, wall, true, th, q);
            out[i][1] = march(c.cold().w(t), c.cold().tin(t), gc, wall, false, th, q);
            for (int j = 0; j < 100; j++, t += dt) {
                double[] k1 = rate(c, ck, wall, t), k2 = rate(c, ck, add(wall, k1, dt / 2), t + dt / 2);
                double[] k3 = rate(c, ck, add(wall, k2, dt / 2), t + dt / 2), k4 = rate(c, ck, add(wall, k3, dt), t + dt);
                for (int k = 0; k < n; k++) wall[k] += dt / 6 * (k1[k] + 2 * k2[k] + 2 * k3[k] + k4[k]);
            }
        }
        return out;
    }

    static double[] rate(Case c, double ck, double[] wall, double t) {
        int n = wall.length;
        double[] th = new double[n], qh = new double[n], qc = new double[n], d = new double[n];
        march(c.hot().w(t), c.hot().tin(t), c.g(0), wall, true, th, qh);
        march(c.cold().w(t), c.cold().tin(t), c.g(1), wall, false, th, qc);
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
        double[] rate;                          // INFLIGHT: heat rate per cell of the open slice, fixed at its dispatch
        Island(int s, double len, int n) {
            this.s = s;
            this.len = len;
            g = new double[n];
            theta = new double[n];
            tinRecord = new double[2];          // last inlet temperature and capacity rate
            rate = new double[n];
        }
    }

    record Run(double[][] outlets, double maxWallExcursion, double energyError) {}

    /**
     * Two islands in slices on a common real clock (1 s ticks). An island commits its next slice when the clock has
     * reached the slice's end and it is not held; a held island falls behind and catches up afterwards, up to 8 slices a tick.
     */
    static Run partitioned(Case c, Scheme scheme, double lenH, double lenC, Control control, double holdFrom, double holdTo) {
        int n = c.n(), steps = (int) c.tEnd();
        double ck = c.c() / n;
        double[] gs = {c.g(0), c.g(1)};
        double[] wall0 = steadyWall(n, gs[0], gs[1], c.hot().w(0), c.hot().tin(0), c.cold().w(0), c.cold().tin(0));
        double[] u = new double[n];
        for (int k = 0; k < n; k++) u[k] = ck * wall0[k];
        double initialEnergy = sum(u), bookedTotal = 0;
        Island[] isl = {new Island(0, lenH, n), new Island(1, lenC, n)};
        double[] q = new double[n];
        if (control != null) isl[1].beta = solveBeta(c.cold().w(0), c.cold().tin(0), gs[1], wall0, control.target());
        for (Island is : isl) {
            Side side = c.side(is.s);
            double g = gs[is.s], w = (1 - is.beta) * side.w(0);
            march(w, side.tin(0), g, wall0, is.s == 0, is.theta, is.rate);
            Arrays.fill(is.g, conductance(w, g));
            is.tinRecord[0] = side.tin(0);
            is.tinRecord[1] = w;
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
                    double g = gs[is.s], t0 = is.tau, t1 = t0 + is.len;
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
                    is.tinRecord[1] = w;
                    for (int i = (int) Math.ceil(t0); i <= Math.min(steps, (int) Math.floor(t1)); i++) {
                        double[] th = new double[n];
                        double wi = (1 - is.beta) * side.w(i), through = march(wi, side.tin(i), g, wall, is.s == 0, th, q);
                        out[i][is.s] = is.beta * side.tin(i) + (1 - is.beta) * through;
                    }
                    is.tau = t1;
                    if (scheme == Scheme.INFLIGHT || scheme == Scheme.JOINT) { // dispatch the next slice: its profile, hence its heat rate, is fixed now
                        double[] next = profile(scheme, c, is, other, t1, is.len, read(scheme, u, ck, t1, other), ck, g, is.beta, tLo, tHi);
                        march((1 - is.beta) * side.w(t1), side.tin(t1), g, next, is.s == 0, new double[n], is.rate);
                    }
                }
            }
            for (int k = 0; k < n; k++) excursion = Math.max(excursion, Math.max(u[k] / ck - tHi, tLo - u[k] / ck));
        }
        return new Run(out, excursion, Math.abs(sum(u) - initialEnergy - bookedTotal));
    }

    /**
     * The account a slice starting at t reads: as booked (FROZEN, AVERAGED) or as of t (ASOF, CAPPED, PREDICTED).
     * PREDICTED adds a lagging side's unbooked heat as its last conductance against the account as read,
     * G (theta - T_k), instead of its last booked rate, so a side that books seldom still answers the wall as it is.
     */
    static double[] read(Scheme scheme, double[] u, double ck, double t, Island other) {
        int n = u.length;
        double[] e = u.clone();
        if (scheme == Scheme.ASOF || scheme == Scheme.CAPPED || scheme == Scheme.PREDICTED || scheme == Scheme.INFLIGHT || scheme == Scheme.JOINT) {
            for (Booked b : other.booked) {
                if (b.t1() <= t + 1e-9) continue;
                double beyond = (b.t1() - Math.max(b.t0(), t)) / (b.t1() - b.t0());
                for (int k = 0; k < n; k++) e[k] -= beyond * b.e()[k];
            }
            if (other.tau < t - 1e-9 && !other.booked.isEmpty()) {
                Booked last = other.booked.get(other.booked.size() - 1);
                double span = scheme == Scheme.ASOF ? t - other.tau : Math.min(t - other.tau, other.len);
                if (scheme == Scheme.PREDICTED) {
                    for (int k = 0; k < n; k++) e[k] += span * other.g[k] * (other.theta[k] - e[k] / ck);
                } else if (scheme == Scheme.INFLIGHT || scheme == Scheme.JOINT) {
                    for (int k = 0; k < n; k++) e[k] += span * other.rate[k];
                } else {
                    double gap = span / (last.t1() - last.t0());
                    for (int k = 0; k < n; k++) e[k] += gap * last.e()[k];
                }
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
        // JOINT: the wall the cells head for is the exchanger's steady wall with both streams marched (own inlet and flow
        // now, the other side's from its last commit), so only the other side's inlet and flow lag, not its profile
        double[] joint = scheme != Scheme.JOINT ? null : is.s == 0
                ? steadyWall(n, g, c.g(1), w, side.tin(t), other.tinRecord[1], other.tinRecord[0])
                : steadyWall(n, c.g(0), g, other.tinRecord[1], other.tinRecord[0], w, side.tin(t));
        for (int k = 0; k < n; k++) {
            double go = other.g[k], sumG = gs + go;
            double teq = joint != null ? joint[k] : sumG > 0 ? (gs * own[k] + go * other.theta[k]) / sumG : account[k];
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
                Case.equalFilms("A hot inlet 150->180 C, water 50 kg/s each side, steel only", n, ua, steel,
                        new Side(water50, 150, water50, 180, 60), new Side(water50, 30, water50, 30, 1e9), 900),
                Case.equalFilms("B cold flow 50->10 kg/s, steel only", n, ua, steel,
                        new Side(water50, 150, water50, 150, 1e9), new Side(water50, 30, water50 / 5, 30, 60), 900),
                Case.equalFilms("C high flow 1.5 MW/K each side (stiff), steel only", n, ua, steel,
                        new Side(1.5e6, 150, 1.5e6, 180, 60), new Side(1.5e6, 30, 1.5e6, 30, 1e9), 900),
                Case.equalFilms("D as C with the water holdup's heat capacity", n, ua, wet,
                        new Side(1.5e6, 150, 1.5e6, 180, 60), new Side(1.5e6, 30, 1.5e6, 30, 1e9), 900));
        if (args.length > 0 && args[0].equals("hold1s")) {
            hold1s(cases.get(0));
            return;
        }
        if (args.length > 0 && args[0].equals("lowflow")) {
            lowFlow(n, area, steel);
            return;
        }
        if (args.length > 0 && args[0].equals("timing")) {
            gameTiming(cases, n, area, steel, water50);
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
                for (Scheme scheme : new Scheme[]{Scheme.FROZEN, Scheme.AVERAGED, Scheme.ASOF, Scheme.CAPPED}) {
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
        perPhaseFilms(cases, n, area, steel, water50, slicing);
    }

    /**
     * Part 5: a film coefficient per side by its phase (liquid 2000, condensing 5000, gas 100 W/m2K; 1/U = 1/h_hot +
     * 1/h_cold), counterflow, the same 450 m2 exchanger and steel-only wall, CAPPED, hot inlet +30 K at 60 s.
     */
    static void perPhaseFilms(List<Case> equal, int n, double area, double steel, double water50, double[][] slicing) {
        System.out.println();
        System.out.println("PART 5  a film coefficient per phase (counterflow, 8 cells, 450 m2, steel only), inlet step at 60 s; CAPPED against PREDICTED, INFLIGHT and JOINT");
        System.out.println("  columns: max |error| hot, cold from the step on (K); over the last 100 s (K); the last time each outlet was more than");
        System.out.println("           1 K and 0.1 K off its new steady state (s after the step); account energy error (J)");
        double gas20 = 20 * 1100;                                  // 20 kg/s of gas at 1.1 kJ/kgK, W/K
        List<Case> films = List.of(
                new Case("L/L liquid 2000 / liquid 2000 W/m2K, water 50 kg/s each side", n, 2000 * area, 2000 * area, steel,
                        new Side(water50, 150, water50, 180, 60), new Side(water50, 30, water50, 30, 1e9), 900),
                new Case("G/L gas 100 / liquid 2000, gas 20 kg/s, water 10 kg/s", n, 100 * area, 2000 * area, steel,
                        new Side(gas20, 150, gas20, 180, 60), new Side(10 * 4180, 30, 10 * 4180, 30, 1e9), 900),
                new Case("G/G gas 100 / gas 100, gas 20 kg/s each side", n, 100 * area, 100 * area, steel,
                        new Side(gas20, 150, gas20, 180, 60), new Side(gas20, 30, gas20, 30, 1e9), 900),
                new Case("C/L condensing 5000 (150 -> 180 C) / liquid 2000, water 50 kg/s", n, 5000 * area, 2000 * area, steel,
                        new Side(Double.MAX_VALUE, 150, Double.MAX_VALUE, 180, 60), new Side(water50, 30, water50, 30, 1e9), 900));
        List<Case> all = new ArrayList<>(equal);
        all.addAll(films);
        for (Case c : all) {
            double[][] ref = truth(c);
            int last = (int) c.tEnd();
            double u = 1 / (area / c.uaHot() + area / c.uaCold()), wmin = Math.min(c.hot().w1(), c.cold().w1());
            System.out.println();
            System.out.println(String.format(Locale.ROOT, "%s: U %.0f W/m2K, UA %.1f kW/K, NTU %.2f, wall C/(hA_hot + hA_cold) %.1f s",
                    c.name(), u, u * area / 1e3, u * area / wmin, c.c() / (c.uaHot() + c.uaCold())));
            System.out.println(String.format(Locale.ROOT, "  reference: steady outlets %.2f / %.2f C; settles (1 K) %.0f %.0f s, (0.1 K) %.0f %.0f s",
                    ref[last][0], ref[last][1], settle(ref, ref[last], 0, 60, last, 1), settle(ref, ref[last], 1, 60, last, 1),
                    settle(ref, ref[last], 0, 60, last, .1), settle(ref, ref[last], 1, 60, last, .1)));
            for (double[] sl : slicing) {
                for (Scheme scheme : new Scheme[]{Scheme.CAPPED, Scheme.PREDICTED, Scheme.INFLIGHT, Scheme.JOINT}) {
                    Run run = partitioned(c, scheme, sl[0], sl[1], null, -1, -1);
                    double[] e = errors(run.outlets(), ref, 60, last), o[] = run.outlets();
                    System.out.println(String.format(Locale.ROOT, "  slices %4.0f/%-4.0f s %-9s max %s %s   tail %s %s   settles (1 K) %4.0f %4.0f s, (0.1 K) %4.0f %4.0f s   energy %.1e",
                            sl[0], sl[1], scheme, fmt(e[0]), fmt(e[1]), fmt(e[2]), fmt(e[3]),
                            settle(o, ref[last], 0, 60, last, 1), settle(o, ref[last], 1, 60, last, 1),
                            settle(o, ref[last], 0, 60, last, .1), settle(o, ref[last], 1, 60, last, .1), run.energyError()));
                }
            }
        }
        System.out.println();
        System.out.println("PART 5b holds, case A: hot island held 100 to 160 s and 100 to 700 s, CAPPED against PREDICTED, INFLIGHT and JOINT (as parts 4 and 4b)");
        Case a = equal.get(0);
        for (int holdTo : new int[]{160, 700}) {
            for (double[] sl : slicing) {
                for (Scheme scheme : new Scheme[]{Scheme.CAPPED, Scheme.PREDICTED, Scheme.INFLIGHT, Scheme.JOINT}) {
                    Run run = partitioned(a, scheme, sl[0], sl[1], null, 100, holdTo);
                    double[] e = errors(run.outlets(), refA(a), 60, 900);
                    System.out.println(String.format(Locale.ROOT, "  held to %3d s, slices %4.0f/%-4.0f s %-9s max %s %s   tail %s %s   excursion %s   energy %.1e",
                            holdTo, sl[0], sl[1], scheme, fmt(e[0]), fmt(e[1]), fmt(e[2]), fmt(e[3]), fmt(Math.max(0, run.maxWallExcursion())), run.energyError()));
                }
            }
        }
        System.out.println();
        System.out.println("PART 5c the wall's heat capacity against the physical lag (reference only): settles (1 K) hot, cold s after the step");
        for (Case c : List.of(films.get(0), films.get(1), films.get(2))) {
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %-4s", c.name().substring(0, 3)));
            for (double share : new double[]{1, .5, .25}) {
                Case lighter = new Case(c.name(), c.n(), c.uaHot(), c.uaCold(), c.c() * share, c.hot(), c.cold(), c.tEnd());
                double[][] ref = truth(lighter);
                int last = (int) c.tEnd();
                row.append(String.format(Locale.ROOT, "   wall x %.2f (%4.1f kJ/K per m2): %4.0f %4.0f s", share, 12 * share,
                        settle(ref, ref[last], 0, 60, last, 1), settle(ref, ref[last], 1, 60, last, 1)));
            }
            System.out.println(row);
        }
        System.out.println("  at a quarter of the wall, worst over the five slicings: max |error| hot, cold (K); settles (1 K) hot, cold (s); tail; energy");
        for (Case c : films) {
            Case lighter = new Case(c.name(), c.n(), c.uaHot(), c.uaCold(), c.c() / 4, c.hot(), c.cold(), c.tEnd());
            double[][] ref = truth(lighter);
            int last = (int) c.tEnd();
            for (Scheme scheme : new Scheme[]{Scheme.INFLIGHT, Scheme.JOINT}) {
                double[] worst = new double[7];
                for (double[] sl : slicing) {
                    Run run = partitioned(lighter, scheme, sl[0], sl[1], null, -1, -1);
                    double[] e = errors(run.outlets(), ref, 60, last), o = {e[0], e[1], settle(run.outlets(), ref[last], 0, 60, last, 1),
                            settle(run.outlets(), ref[last], 1, 60, last, 1), e[2], e[3], run.energyError()};
                    for (int i = 0; i < 7; i++) worst[i] = Math.max(worst[i], o[i]);
                }
                System.out.println(String.format(Locale.ROOT, "  %-4s %-9s reference settles %3.0f %3.0f s; max %s %s   settles %4.0f %4.0f s   tail %s %s   energy %.1e",
                        c.name().substring(0, 3), scheme, settle(ref, ref[last], 0, 60, last, 1), settle(ref, ref[last], 1, 60, last, 1),
                        fmt(worst[0]), fmt(worst[1]), worst[2], worst[3], fmt(worst[4]), fmt(worst[5]), worst[6]));
            }
        }
        stabilityMap(n, area);
        zeroWall(equal, films, n, area, slicing);
    }

    // ---------------------------------------------------------------- revision 3: a wall that holds no heat

    /** Quasi-steady reference: at every second the exchanger stands at its steady state for that second's inlets. */
    static double[][] truthQuasi(Case c) {
        int n = c.n(), steps = (int) c.tEnd();
        double[][] out = new double[steps + 1][2];
        double[] th = new double[n], q = new double[n];
        for (int i = 0; i <= steps; i++) {
            double[] wall = steadyWall(n, c.g(0), c.g(1), c.hot().w(i), c.hot().tin(i), c.cold().w(i), c.cold().tin(i));
            out[i][0] = march(c.hot().w(i), c.hot().tin(i), c.g(0), wall, true, th, q);
            out[i][1] = march(c.cold().w(i), c.cold().tin(i), c.g(1), wall, false, th, q);
        }
        return out;
    }

    /** A side's state at a commit, for the other side's reads as of a time. */
    record Rec(double t, double tin, double w, double[] g) {}

    /** What a slice fixes when it is dispatched: the wall, the heat in transit due, the other side as read, its stop. */
    record Plan(double[] wall, double[] due, double[] gOther, double wOther, double lenOther, double tinOther, boolean otherStopped, double off) {}

    /** How the two sides split the heat in transit between them (Part 7d compares the three). */
    enum Split { CONDUCTANCE, CAPACITY, SLICE_HEAT }

    /** One side of a wall without heat. Part 7 plans every slice at its dispatch, from what both sides had committed then. */
    static final class RelaySide {
        final int s;
        final double len;
        Split split = Split.SLICE_HEAT;
        double tau, beta;
        final List<Booked> booked = new ArrayList<>();
        final List<Rec> recs = new ArrayList<>();
        final List<double[]> sawStopped = new ArrayList<>();   // spans in which this side counted the other as stopped
        final double[] rate;                                   // heat rate per cell of the open slice, fixed at its dispatch
        Plan plan;
        RelaySide(int s, double len, int n) {
            this.s = s;
            this.len = len;
            rate = new double[n];
        }
        Rec asOf(double t) {
            Rec r = recs.get(0);
            for (Rec x : recs) if (x.t() <= t + 1e-9) r = x;
            return r;
        }
    }

    static double[] wallFor(Case c, RelaySide is, double t, double[] gs, double tinOther, double wOther) {
        Side side = c.side(is.s);
        double w = (1 - is.beta) * side.w(t);
        double[] wall = new double[c.n()];
        if (wOther <= 0 || w <= 0) {                          // one stream stopped: the wall stands at the other, no heat
            Arrays.fill(wall, wOther <= 0 ? side.tin(t) : tinOther);
            return wall;
        }
        return is.s == 0 ? steadyWall(c.n(), gs[0], gs[1], w, side.tin(t), wOther, tinOther)
                : steadyWall(c.n(), gs[0], gs[1], wOther, tinOther, w, side.tin(t));
    }

    /** Heat in transit as of t: the other side's heat booked past t taken out, its open slice's rate added for at most one slice. */
    static double[] dueAsOf(double[] transit, double t, RelaySide other) {
        int n = transit.length;
        double[] d = transit.clone();
        for (Booked b : other.booked) {
            if (b.t1() <= t + 1e-9) continue;
            double beyond = (b.t1() - Math.max(b.t0(), t)) / (b.t1() - b.t0());
            for (int k = 0; k < n; k++) d[k] -= beyond * b.e()[k];
        }
        if (other.tau < t - 1e-9) {
            double span = Math.min(t - other.tau, other.len);
            for (int k = 0; k < n; k++) d[k] += span * other.rate[k];
        }
        return d;
    }

    static double overlap(List<double[]> spans, double a, double b) {
        double sum = 0;
        for (double[] sp : spans) sum += Math.max(0, Math.min(b, sp[1]) - Math.max(a, sp[0]));
        return sum;
    }

    /**
     * Dispatch: the wall the slice heads for (both streams marched, the other side as of t, stopped when it lags more than
     * one of its slices), the heat in transit due as of t, and the share of the slice the other side spent counting this
     * one as stopped (no heat moved there, for either side).
     */
    static Plan plan(Case c, RelaySide is, RelaySide other, double t, double[] gs, double[] transit, Control control) {
        Side side = c.side(is.s);
        boolean stopped = other.tau < t - other.len - 1e-9;
        Rec o = other.asOf(t);
        double wOther = stopped ? 0 : o.w();
        double off = Math.min(1, overlap(other.sawStopped, t, t + is.len) / is.len);
        double[] due = dueAsOf(transit, t, other), gOther = stopped ? new double[c.n()] : o.g();
        double[] wall = wallFor(c, is, t, gs, o.tin(), wOther);
        if (control != null && is.s == 1) {
            // Measured: taking the planned give-back into the target closes a loop through the transit (each move of beta
            // books new transit at the lagging side's next commit) and settles four to five times slower at 20/5 s slices.
            double target = solveBeta(side.w(t), side.tin(t), gs[1], wall, control.target());
            is.beta += control.damping() * (target - is.beta);
            wall = wallFor(c, is, t, gs, o.tin(), wOther);
        }
        return new Plan(wall, due, gOther, wOther, other.len, o.tin(), stopped, off);
    }

    /**
     * The side's share of the heat in transit. By conductance, the split the cell's heat law would give. By capacity rate,
     * both streams shift by the same temperature at equal slices, and a condensing or boiling side (no temperature change)
     * takes it all. By slice heat (capacity rate times slice length), both outlets shift by the same temperature at any
     * slices. A side without flow takes none: the heat waits in transit.
     */
    static double share(RelaySide is, double wOwn, double gOwn, Plan p, int k) {
        if (wOwn <= 0) return 0;
        if (is.split == Split.CONDUCTANCE) return gOwn + p.gOther()[k] > 0 ? gOwn / (gOwn + p.gOther()[k]) : 1;
        if (p.wOther() <= 0) return 1;
        if (wOwn >= 1e300) return p.wOther() >= 1e300 ? .5 : 1;
        if (p.wOther() >= 1e300) return 0;
        boolean slice = is.split == Split.SLICE_HEAT;
        double own = wOwn * (slice ? is.len : 1), other = p.wOther() * (slice ? p.lenOther() : 1);
        return own / (own + other);
    }

    /**
     * Commit: the heat the side gave each cell over its slice (J). Its own inlet and flow as they were, against the planned
     * wall, none while the other side counted it stopped; then it gives back its share of the heat in transit (share above),
     * as far as its outlet stays between the two inlet temperatures (the rest stays in transit). shift[0] gets the give-back's
     * effect on its outlet (K).
     */
    static double[] commitHeat(Case c, RelaySide is, double t0, double t1, double[] gs, double[] shift) {
        Side side = c.side(is.s);
        Plan p = is.plan;
        int n = c.n();
        double g = gs[is.s], len = t1 - t0;
        double[] e = new double[n], th = new double[n], q = new double[n], shareDue = new double[n];
        double[] cuts = side.tStep() > t0 && side.tStep() < t1 ? new double[]{t0, side.tStep(), t1} : new double[]{t0, t1};
        for (int i = 0; i + 1 < cuts.length; i++) {
            double mid = (cuts[i] + cuts[i + 1]) / 2;
            march((1 - is.beta) * side.w(mid), side.tin(mid), g, p.wall(), is.s == 0, th, q);
            for (int k = 0; k < n; k++) e[k] += q[k] * (cuts[i + 1] - cuts[i]) * (1 - p.off());
        }
        double w = (1 - is.beta) * side.w(t1 - 1e-9), gOwn = conductance(w, g), back = 0;
        for (int k = 0; k < n; k++) {
            shareDue[k] = share(is, w, gOwn, p, k) * p.due()[k];
            back += shareDue[k];
        }
        double scale = 1;
        shift[0] = 0;
        if (w > 0 && w < 1e300 && back != 0) {
            double tin = side.tin(t1 - 1e-9), through = tin - sum(e) / (w * len);
            double lo = Math.min(tin, p.tinOther()), hi = Math.max(tin, p.tinOther());
            double allowed = Math.max(lo, Math.min(hi, through + back / (w * len)));
            scale = Math.max(0, Math.min(1, (allowed - through) * w * len / back));
            shift[0] = scale * back / (w * len);
        }
        for (int k = 0; k < n; k++) e[k] -= scale * shareDue[k];
        return e;
    }

    /**
     * Part 7 (revision 3, owner Q10: the wall holds no heat). Each side's slice heads straight for the quasi-steady wall.
     * The two sides' heats then differ only while their views of each other differ; the account holds that difference,
     * heat in transit between the islands, and each side gives it back (above). Slices are planned at their dispatch.
     * Run.maxWallExcursion carries the largest give-back shift of an outlet (K), Run.energyError the heat still in transit
     * at the end (J); energy is exact by construction (every joule booked is in a stream or in transit).
     */
    static Run relay(Case c, double lenH, double lenC, Control control, double holdFrom, double holdTo, Split split) {
        int n = c.n(), steps = (int) c.tEnd();
        double[] gs = {c.g(0), c.g(1)}, transit = new double[n];
        double[] wall0 = steadyWall(n, gs[0], gs[1], c.hot().w(0), c.hot().tin(0), c.cold().w(0), c.cold().tin(0));
        RelaySide[] sd = {new RelaySide(0, lenH, n), new RelaySide(1, lenC, n)};
        for (RelaySide r : sd) r.split = split;
        if (control != null) sd[1].beta = solveBeta(c.cold().w(0), c.cold().tin(0), gs[1], wall0, control.target());
        for (RelaySide r : sd) {
            Side side = c.side(r.s);
            double w = (1 - r.beta) * side.w(0), gc[] = new double[n];
            Arrays.fill(gc, conductance(w, gs[r.s]));
            r.recs.add(new Rec(0, side.tin(0), w, gc));
            march(w, side.tin(0), gs[r.s], wall0, r.s == 0, new double[n], r.rate);
        }
        for (RelaySide r : sd) r.plan = plan(c, r, sd[1 - r.s], 0, gs, transit, control);
        double[][] out = new double[steps + 1][2];
        for (double[] row : out) Arrays.fill(row, Double.NaN);
        double maxShift = 0;
        double[] th = new double[n], q = new double[n], shift = new double[1];
        for (int clock = 1; clock <= steps; clock++) {
            for (RelaySide r : sd) {
                boolean held = r.s == 0 && clock > holdFrom && clock < holdTo;
                for (int burst = 0; !held && burst < 8 && r.tau + r.len <= clock + 1e-9 && r.tau + r.len <= steps + 1e-9; burst++) {
                    RelaySide other = sd[1 - r.s];
                    Side side = c.side(r.s);
                    double g = gs[r.s], t0 = r.tau, t1 = t0 + r.len;
                    double[] e = commitHeat(c, r, t0, t1, gs, shift);
                    maxShift = Math.max(maxShift, Math.abs(shift[0]));
                    for (int k = 0; k < n; k++) transit[k] += e[k];
                    r.booked.add(new Booked(t0, t1, e));
                    if (r.plan.otherStopped()) r.sawStopped.add(new double[]{t0, t1});
                    for (int i = (int) Math.ceil(t0); i <= Math.min(steps, (int) Math.floor(t1)); i++) {
                        double wi = (1 - r.beta) * side.w(i), through = march(wi, side.tin(i), g, r.plan.wall(), r.s == 0, th, q);
                        through = side.tin(i) + (through - side.tin(i)) * (1 - r.plan.off()) + shift[0];
                        out[i][r.s] = r.beta * side.tin(i) + (1 - r.beta) * through;
                    }
                    double w = (1 - r.beta) * side.w(t1 - 1e-9), gc[] = new double[n];
                    Arrays.fill(gc, conductance(w, g));
                    r.recs.add(new Rec(t1, side.tin(t1 - 1e-9), w, gc));
                    r.tau = t1;
                    r.plan = plan(c, r, other, t1, gs, transit, control);   // dispatch the next slice
                    march((1 - r.beta) * side.w(t1), side.tin(t1), g, r.plan.wall(), r.s == 0, th, r.rate);
                    double wNext = (1 - r.beta) * side.w(t1), gNext = conductance(wNext, g);
                    for (int k = 0; k < n; k++)
                        r.rate[k] = r.rate[k] * (1 - r.plan.off()) - share(r, wNext, gNext, r.plan, k) * r.plan.due()[k] / r.len;
                }
            }
        }
        return new Run(out, maxShift, Math.abs(sum(transit)));
    }

    /** The give-back split Parts 7 to 7c run (Part 7d compares the three). */
    static final Split SPLIT = Split.SLICE_HEAT;

    static void zeroWall(List<Case> equal, List<Case> films, int n, double area, double[][] slicing) {
        System.out.println();
        System.out.println("PART 7  revision 3: the wall holds no heat (owner Q10); against the quasi-steady exchanger, inlet step at 60 s");
        System.out.println("  give-back split by " + SPLIT.name().toLowerCase(Locale.ROOT) + " (Part 7d compares the three splits)");
        System.out.println("  columns: max |error| hot, cold from the step on (K); over the last 100 s (K); settles (1 K) hot, cold (s after the step);");
        System.out.println("           largest give-back shift of an outlet (K); heat still in transit at the end (J)");
        double water50 = 50 * 4180;
        List<Case> cases = new ArrayList<>(equal.subList(0, 3));
        cases.addAll(films);
        for (double wh : new double[]{400e3, 2000e3})
            cases.add(new Case(String.format(Locale.ROOT, "glide 5000/2000, hot %4.0f kW/K", wh / 1e3), n, 5000 * area, 2000 * area, 0,
                    new Side(wh, 150, wh, 180, 60), new Side(water50, 30, water50, 30, 1e9), 900));
        for (double kgs : new double[]{10, 25, 200}) {
            double w = kgs * 4180;
            cases.add(new Case(String.format(Locale.ROOT, "L/L %4.0f kg/s", kgs), n, 2000 * area, 2000 * area, 0,
                    new Side(w, 150, w, 180, 60), new Side(w, 30, w, 30, 1e9), 900));
        }
        for (Case c : cases) {
            double[][] ref = truthQuasi(c);
            int last = (int) c.tEnd();
            System.out.println(String.format(Locale.ROOT, "%s: steady outlets %.2f / %.2f C", c.name(), ref[last][0], ref[last][1]));
            for (double[] sl : slicing) {
                Run run = relay(c, sl[0], sl[1], null, -1, -1, SPLIT);
                double[] e = errors(run.outlets(), ref, 60, last), o[] = run.outlets();
                System.out.println(String.format(Locale.ROOT, "  slices %4.0f/%-4.0f s max %s %s   tail %s %s   settles (1 K) %4.0f %4.0f s   give-back %6.2f K, in transit %.1e J",
                        sl[0], sl[1], fmt(e[0]), fmt(e[1]), fmt(e[2]), fmt(e[3]), settle(o, ref[last], 0, 60, last, 1), settle(o, ref[last], 1, 60, last, 1),
                        run.maxWallExcursion(), run.energyError()));
            }
        }
        System.out.println();
        System.out.println("PART 7b holds, case A without wall heat: hot island held 100 to 160 s and 100 to 700 s");
        Case a = equal.get(0);
        double[][] refQ = truthQuasi(a);
        for (int holdTo : new int[]{160, 700}) {
            for (double[] sl : slicing) {
                Run run = relay(a, sl[0], sl[1], null, 100, holdTo, SPLIT);
                double[] e = errors(run.outlets(), refQ, 60, 900);
                System.out.println(String.format(Locale.ROOT, "  held to %3d s, slices %4.0f/%-4.0f s max %s %s   tail %s %s   give-back %6.2f K, in transit %.1e J",
                        holdTo, sl[0], sl[1], fmt(e[0]), fmt(e[1]), fmt(e[2]), fmt(e[3]), run.maxWallExcursion(), run.energyError()));
            }
        }
        System.out.println();
        System.out.println("PART 7c cold outlet target by a cold-side bypass without wall heat, dead-beat, hot inlet 150->180 C at 60 s");
        Object[][] controlled = {{a, 90.0}, {equal.get(2), 40.0}, {films.get(0), 100.0}};
        for (Object[] pair : controlled) {
            Case c = (Case) pair[0];
            double target = (Double) pair[1];
            for (double[] sl : slicing) {
                Run run = relay(c, sl[0], sl[1], new Control(target, 1), -1, -1, SPLIT);
                double[] w = controlWorst(run, c, target);
                System.out.println(String.format(Locale.ROOT, "  %-3s target %.0f C slices %4.0f/%-4.0f s: worst |T-target| %6.2f K, last outside 0.1 K at +%4.0f s, end %.4f C",
                        c.name().substring(0, 3), target, sl[0], sl[1], w[0], w[1], run.outlets()[(int) c.tEnd()][1]));
            }
        }
        System.out.println();
        System.out.println("PART 7d the give-back split: by conductance (the heat law), by capacity rate (equal shift at equal slices), by slice heat W len (equal shift at any slices)");
        System.out.println("  worst over the five slicings: max |error| (K), settle 1 K (s), give-back shift (K); controller: worst |T-target| (K), last outside 0.1 K (s)");
        for (Case c : cases) {
            double[][] ref = truthQuasi(c);
            int last = (int) c.tEnd();
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-42s", c.name().length() > 42 ? c.name().substring(0, 42) : c.name()));
            for (Split split : Split.values()) {
                double err = 0, settle = 0, back = 0;
                for (double[] sl : slicing) {
                    Run run = relay(c, sl[0], sl[1], null, -1, -1, split);
                    double[] e = errors(run.outlets(), ref, 60, last);
                    err = Math.max(err, Math.max(e[0], e[1]));
                    settle = Math.max(settle, Math.max(settle(run.outlets(), ref[last], 0, 60, last, 1), settle(run.outlets(), ref[last], 1, 60, last, 1)));
                    back = Math.max(back, run.maxWallExcursion());
                }
                line.append(String.format(Locale.ROOT, "  %s %6.2f K %4.0f s %6.2f K", (split == Split.CONDUCTANCE ? "" : "| ") + split.name().toLowerCase(Locale.ROOT), err, settle, back));
            }
            System.out.println(line);
        }
        for (Object[] pair : controlled) {
            Case c = (Case) pair[0];
            double target = (Double) pair[1];
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-3s target %3.0f C%-27s", c.name().substring(0, 3), target, ""));
            for (Split split : Split.values()) {
                double worst = 0, late = 0;
                for (double[] sl : slicing) {
                    double[] w = controlWorst(relay(c, sl[0], sl[1], new Control(target, 1), -1, -1, split), c, target);
                    worst = Math.max(worst, w[0]);
                    late = Math.max(late, w[1]);
                }
                line.append(String.format(Locale.ROOT, "  %s %6.2f K %4.0f s %8s", (split == Split.CONDUCTANCE ? "" : "| ") + split.name().toLowerCase(Locale.ROOT), worst, late, ""));
            }
            System.out.println(line);
        }
    }

    /** The controlled cold outlet's worst |T - target| from the step on, and the last second it is outside 0.1 K. */
    static double[] controlWorst(Run run, Case c, double target) {
        double worst = 0, late = 0;
        for (int i = 60; i <= (int) c.tEnd(); i++) {
            double v = run.outlets()[i][1];
            if (Double.isNaN(v)) continue;
            worst = Math.max(worst, Math.abs(v - target));
            if (Math.abs(v - target) > .1) late = i - 60;
        }
        return new double[]{worst, late};
    }

    /**
     * Part 6: where the coupling stays stable. Two finite streams with strong films are the hard case: liquid/liquid
     * across flows and wall weights, and a condensing mixture with a temperature glide (film 5000, finite capacity rate)
     * against water. A row FAILS when an outlet is more than 0.05 K off the steady state over the last 100 s.
     */
    static void stabilityMap(int n, double area) {
        System.out.println();
        System.out.println("PART 6  stability map, INFLIGHT against JOINT: x = (G_hot + G_cold) * longer slice / C_cell; tail hot, cold (K)");
        double steel = 12e3 * area, water50 = 50 * 4180;
        List<Case> cases = new ArrayList<>();
        for (double kgs : new double[]{10, 25, 50, 200}) {
            double w = kgs * 4180;
            for (double share : new double[]{1, .8, .65, .5})
                cases.add(new Case(String.format(Locale.ROOT, "L/L %4.0f kg/s, wall x %.2f", kgs, share), n, 2000 * area, 2000 * area, steel * share,
                        new Side(w, 150, w, 180, 60), new Side(w, 30, w, 30, 1e9), 900));
        }
        for (double wh : new double[]{100e3, 400e3, 2000e3})
            for (double share : new double[]{1, .5})
                cases.add(new Case(String.format(Locale.ROOT, "glide 5000/2000, hot %4.0f kW/K, wall x %.2f", wh / 1e3, share), n, 5000 * area, 2000 * area,
                        steel * share, new Side(wh, 150, wh, 180, 60), new Side(water50, 30, water50, 30, 1e9), 900));
        for (Case c : cases) {
            double[][] ref = truth(c);
            for (double[] sl : new double[][]{{20, 20}, {5, 20}, {20, 5}}) {
                double x = (conductance(c.hot().w1(), c.g(0)) + conductance(c.cold().w1(), c.g(1))) * Math.max(sl[0], sl[1]) / (c.c() / n);
                StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %-40s slices %2.0f/%-2.0f x %5.1f", c.name(), sl[0], sl[1], x));
                for (Scheme scheme : new Scheme[]{Scheme.INFLIGHT, Scheme.JOINT}) {
                    double[] e = errors(partitioned(c, scheme, sl[0], sl[1], null, -1, -1).outlets(), ref, 60, 900);
                    row.append(String.format(Locale.ROOT, "   %-9s tail %s %s %-5s", scheme, fmt(e[2]), fmt(e[3]), Math.max(e[2], e[3]) > .05 ? "FAILS" : "ok"));
                }
                System.out.println(row);
            }
        }
    }

    /** Seconds after {@code from} at which outlet s was last more than {@code band} K off {@code target[s]}. */
    static double settle(double[][] out, double[] target, int s, int from, int last, double band) {
        int at = from;
        for (int i = from; i <= last; i++) if (Double.isNaN(out[i][s]) || Math.abs(out[i][s] - target[s]) > band) at = i;
        return at - from;
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

    /**
     * Part 6's one JOINT row off by more than 0.05 K: liquid/liquid at 10 kg/s (NTU 10.8), 20/20 s slices, run to 3000 s.
     * The hot outlet's error against the reference every 300 s: a slow tail, not a cycle.
     */
    static void lowFlow(int n, double area, double steel) {
        double w = 10 * 4180;
        Case c = new Case("L/L 10 kg/s", n, 2000 * area, 2000 * area, steel, new Side(w, 150, w, 180, 60), new Side(w, 30, w, 30, 1e9), 3000);
        double[][] ref = truth(c);
        System.out.println("liquid/liquid 10 kg/s each side, 2000/2000 W/m2K, steel only, 20/20 s slices: hot outlet minus reference (K)");
        for (Scheme scheme : new Scheme[]{Scheme.INFLIGHT, Scheme.JOINT}) {
            Run run = partitioned(c, scheme, 20, 20, null, -1, -1);
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %-9s", scheme));
            for (int i = 100; i <= 3000; i += 300) row.append(String.format(Locale.ROOT, "  %4d s %+7.3f", i, run.outlets()[i][0] - ref[i][0]));
            System.out.println(row);
        }
    }

    // ---------------------------------------------------------------- revision 4: the game's timing (W3a, part 8)

    /** Ticks per second, and the longest job: the 2 s hard budget. */
    static final int TICKS = 20, MAX_LATENCY = 40;

    /**
     * How a side reads its partner when its slice is dispatched (plan section 5.2). A: as of the slice start, a partner
     * not committed that far extrapolated at its last committed slice's rate for at most one of its slices. B: the
     * partner's records committed inside the slice, piece by piece; A for the rest. C: a coupling fence, the slice waits
     * until its partner has committed through the slice start, then reads as A without extrapolating.
     */
    enum Form { A, B, C }

    /**
     * The jobs' latency, ticks from dispatch to commit: fixed, or uniform 0 to 40 from a seed; at most half the side's
     * cadence, so a side behind (after a hold) catches up at least twice as fast as online time runs. A side whose jobs take
     * as long as its slices only keeps pace, and stays behind for good until the clock lengthens its cadence.
     */
    record Latency(int fixed, long seed) {
        int next(SplittableRandom random, int cadence) { return Math.min(cadence / 2, fixed >= 0 ? fixed : random.nextInt(MAX_LATENCY + 1)); }
    }

    /**
     * Forms A and B: a running partner more than one of its slices and the 2 s job budget behind the slice start counts as
     * stopped (it is catching up after a hold). In normal running a partner is never that far behind. false: the plan's
     * rule, stopped only while held.
     */
    static boolean LAG_STOPS = true;

    /** An adaptive cadence's load wobble (IslandClock.performanceSample: x1.25 under load, x0.9 when light), cycled per slice. */
    static final double[] WOBBLE = {1, 1.25, 1.5625, 1.25, 1, .9, .81, .9};

    /** A part of a slice with one wall: B cuts a slice at the partner's commits inside it. */
    record Piece(double a, double b, double[] wall, double off) {}

    /** What a dispatched slice fixes: its pieces, the heat in transit due as of its start, the partner as last read. */
    record GamePlan(List<Piece> pieces, double[] due, double wOther, double lenOther, double tinOther, boolean otherStopped) {}

    /** A slice in flight: what the worker computed from its command, committed at commitTick. */
    record Job(int t0, int t1, int commitTick, GamePlan plan, double[] e, double shift) {}

    /** One side on the game's clock. Time in ticks; records and booked heat in seconds. */
    static final class GameSide {
        final int s, base;
        final boolean adaptive;
        final Latency latency;
        final SplittableRandom random;
        int tau, count, lastLen, lagStops;
        double beta;
        boolean held;
        final List<Booked> booked = new ArrayList<>();
        final List<Rec> recs = new ArrayList<>();
        final List<double[]> sawStopped = new ArrayList<>();
        double[] lastRate;
        Job job;
        GameSide(int s, double seconds, boolean adaptive, Latency latency) {
            this.s = s;
            base = (int) Math.round(seconds * TICKS);
            this.adaptive = adaptive;
            this.latency = latency;
            random = new SplittableRandom(latency.seed() * 2 + s);
        }
        int cadence() {
            return adaptive ? Math.max(20, Math.min(400, (int) Math.round(base * WOBBLE[count % WOBBLE.length]))) : base;
        }
        Rec asOf(double t) {
            Rec r = recs.get(0);
            for (Rec x : recs) if (x.t() <= t + 1e-9) r = x;
            return r;
        }
    }

    /** seen[i][s]: the online time (s) at which side s committed its outlet for second i. */
    record GameRun(double[][] outlets, double[][] seen, double maxShift, double transit, double[] lag, int lagStops) {}

    static double[] gameWall(Case c, int s, double beta, double t, double[] gs, double tinOther, double wOther) {
        Side side = c.side(s);
        double w = (1 - beta) * side.w(t);
        double[] wall = new double[c.n()];
        if (wOther <= 0 || w <= 0) {
            Arrays.fill(wall, wOther <= 0 ? side.tin(t) : tinOther);
            return wall;
        }
        return s == 0 ? steadyWall(c.n(), gs[0], gs[1], w, side.tin(t), wOther, tinOther)
                : steadyWall(c.n(), gs[0], gs[1], wOther, tinOther, w, side.tin(t));
    }

    /**
     * The heat in transit due as of t: the partner's heat booked past t taken out; a partner running (not held) but not
     * committed through t added at its last committed slice's rate, for at most one of its slices.
     */
    static double[] gameDue(double[] transit, double t, GameSide other, boolean stopped) {
        int n = transit.length;
        double[] d = transit.clone();
        for (Booked b : other.booked) {
            if (b.t1() <= t + 1e-9) continue;
            double beyond = (b.t1() - Math.max(b.t0(), t)) / (b.t1() - b.t0());
            for (int k = 0; k < n; k++) d[k] -= beyond * b.e()[k];
        }
        double tauOther = other.tau / (double) TICKS;
        if (!stopped && tauOther < t - 1e-9) {
            double span = Math.min(t - tauOther, other.cadence() / (double) TICKS);
            for (int k = 0; k < n; k++) d[k] += span * other.lastRate[k];
        }
        return d;
    }

    /** Dispatch: the slice's pieces and walls, read as the form says; the controller sets beta at the slice start. */
    static GamePlan gamePlan(Case c, Form form, GameSide is, GameSide other, double t0, double t1, double[] gs, double[] transit, Control control) {
        Side side = c.side(is.s);
        boolean behind = form != Form.C && LAG_STOPS && !other.held
                && t0 - other.tau / (double) TICKS > (Math.max(other.cadence(), other.lastLen) + MAX_LATENCY) / (double) TICKS + 1e-9;
        if (behind) is.lagStops++;
        boolean stopped = other.held || behind;
        List<Double> cuts = new ArrayList<>(List.of(t0));
        if (form == Form.B && !stopped) for (Rec r : other.recs) if (r.t() > t0 + 1e-9 && r.t() < t1 - 1e-9) cuts.add(r.t());
        cuts.add(t1);
        Rec first = other.asOf(t0);
        if (control != null && is.s == 1) {
            double[] wall = gameWall(c, is.s, is.beta, t0, gs, first.tin(), stopped ? 0 : first.w());
            double target = solveBeta(side.w(t0), side.tin(t0), gs[1], wall, control.target());
            is.beta += control.damping() * (target - is.beta);
        }
        List<Piece> pieces = new ArrayList<>();
        Rec last = first;
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double a = cuts.get(i), b = cuts.get(i + 1);
            last = other.asOf(a);
            double off = Math.min(1, overlap(other.sawStopped, a, b) / (b - a));
            pieces.add(new Piece(a, b, gameWall(c, is.s, is.beta, a, gs, last.tin(), stopped ? 0 : last.w()), off));
        }
        return new GamePlan(pieces, gameDue(transit, t0, other, stopped), stopped ? 0 : last.w(), other.cadence() / (double) TICKS, last.tin(), stopped);
    }

    /** The worker: the side's heat over its slice, piece by piece, then its share of the transit given back (as Part 7, by slice heat). */
    static Job gameSolve(Case c, GameSide is, int t0Tick, int t1Tick, GamePlan p, double[] gs, int commitTick) {
        Side side = c.side(is.s);
        int n = c.n();
        double g = gs[is.s], t0 = t0Tick / (double) TICKS, t1 = t1Tick / (double) TICKS, len = t1 - t0;
        double[] e = new double[n], th = new double[n], q = new double[n];
        for (Piece pc : p.pieces()) {
            double[] cuts = side.tStep() > pc.a() && side.tStep() < pc.b() ? new double[]{pc.a(), side.tStep(), pc.b()} : new double[]{pc.a(), pc.b()};
            for (int i = 0; i + 1 < cuts.length; i++) {
                double mid = (cuts[i] + cuts[i + 1]) / 2;
                march((1 - is.beta) * side.w(mid), side.tin(mid), g, pc.wall(), is.s == 0, th, q);
                for (int k = 0; k < n; k++) e[k] += q[k] * (cuts[i + 1] - cuts[i]) * (1 - pc.off());
            }
        }
        double w = (1 - is.beta) * side.w(t1 - 1e-9), share, back = 0;
        if (w <= 0) share = 0;
        else if (p.wOther() <= 0) share = 1;
        else if (w >= 1e300) share = p.wOther() >= 1e300 ? .5 : 1;
        else if (p.wOther() >= 1e300) share = 0;
        else share = w * len / (w * len + p.wOther() * p.lenOther());
        for (int k = 0; k < n; k++) back += share * p.due()[k];
        double scale = 1, shift = 0;
        if (w > 0 && w < 1e300 && back != 0) {
            double tin = side.tin(t1 - 1e-9), through = tin - sum(e) / (w * len);
            double lo = Math.min(tin, p.tinOther()), hi = Math.max(tin, p.tinOther());
            double allowed = Math.max(lo, Math.min(hi, through + back / (w * len)));
            scale = Math.max(0, Math.min(1, (allowed - through) * w * len / back));
            shift = scale * back / (w * len);
        }
        for (int k = 0; k < n; k++) e[k] -= scale * share * p.due()[k];
        return new Job(t0Tick, t1Tick, commitTick, p, e, shift);
    }

    /** The server thread commits a finished job: books its heat, records the side, writes its outlets. */
    static void gameCommit(Case c, GameSide is, double[] transit, double[] gs, double[][] out, double[][] seen) {
        Job job = is.job;
        Side side = c.side(is.s);
        int n = c.n(), steps = (int) c.tEnd();
        double g = gs[is.s], t0 = job.t0() / (double) TICKS, t1 = job.t1() / (double) TICKS;
        for (int k = 0; k < n; k++) transit[k] += job.e()[k];
        is.booked.add(new Booked(t0, t1, job.e()));
        if (job.plan().otherStopped()) is.sawStopped.add(new double[]{t0, t1});
        double[] th = new double[n], q = new double[n];
        for (int i = (int) Math.ceil(t0 - 1e-9); i <= Math.min(steps, (int) Math.floor(t1 + 1e-9)); i++) {
            Piece pc = job.plan().pieces().get(job.plan().pieces().size() - 1);
            for (Piece x : job.plan().pieces()) if (i >= x.a() - 1e-9 && i < x.b() - 1e-9) { pc = x; break; }
            double wi = (1 - is.beta) * side.w(i), through = march(wi, side.tin(i), g, pc.wall(), is.s == 0, th, q);
            through = side.tin(i) + (through - side.tin(i)) * (1 - pc.off()) + job.shift();
            out[i][is.s] = is.beta * side.tin(i) + (1 - is.beta) * through;
            seen[i][is.s] = job.commitTick() / (double) TICKS;
        }
        double w = (1 - is.beta) * side.w(t1 - 1e-9), gc[] = new double[n];
        Arrays.fill(gc, conductance(w, g));
        is.recs.add(new Rec(t1, side.tin(t1 - 1e-9), w, gc));
        is.lastRate = job.e().clone();
        for (int k = 0; k < n; k++) is.lastRate[k] /= t1 - t0;
        is.lastLen = job.t1() - job.t0();
        is.tau = job.t1();
        is.count++;
        is.job = null;
    }

    /**
     * Part 8: the relay of Part 7 on the game's clock. A slice is offered once online time reaches its end, dispatched
     * then (form C: once its partner has committed through its start, or is held), solved by a worker from its command,
     * and committed latency ticks later; one slice in flight per side, up to 16 dispatches and commits a tick. A held side
     * drops its job and is stopped (an explicit state) for its partner; lag alone never stops a side. lag[] is each
     * running side's largest debt, online minus committed time (s), up to the end; after it both sides drain to the end
     * (the last slice shortened to it), so the outlets and the transit are read at one time.
     */
    static GameRun game(Case c, Form form, double lenH, double lenC, boolean adaptive, Latency latency, Control control, double holdFrom, double holdTo) {
        int n = c.n(), steps = (int) c.tEnd(), end = steps * TICKS;
        double[] gs = {c.g(0), c.g(1)}, transit = new double[n], lag = new double[2];
        double[] wall0 = steadyWall(n, gs[0], gs[1], c.hot().w(0), c.hot().tin(0), c.cold().w(0), c.cold().tin(0));
        GameSide[] sd = {new GameSide(0, lenH, adaptive, latency), new GameSide(1, lenC, adaptive, latency)};
        if (control != null) sd[1].beta = solveBeta(c.cold().w(0), c.cold().tin(0), gs[1], wall0, control.target());
        for (GameSide r : sd) {
            Side side = c.side(r.s);
            double w = (1 - r.beta) * side.w(0), gc[] = new double[n];
            Arrays.fill(gc, conductance(w, gs[r.s]));
            r.recs.add(new Rec(0, side.tin(0), w, gc));
            r.lastRate = new double[n];
            march(w, side.tin(0), gs[r.s], wall0, r.s == 0, new double[n], r.lastRate);
        }
        double[][] out = new double[steps + 1][2], seen = new double[steps + 1][2];
        for (double[] row : out) Arrays.fill(row, Double.NaN);
        double maxShift = 0;
        for (int tick = 1; tick <= end || sd[0].tau < end || sd[1].tau < end; tick++) {
            if (tick > end + 1_000_000) throw new IllegalStateException("the sides do not drain");
            double now = tick / (double) TICKS;
            sd[0].held = now > holdFrom && now < holdTo;
            if (sd[0].held) sd[0].job = null;                      // a held result commits nothing
            for (int round = 0; round < 16; round++) {
                boolean any = false;
                for (GameSide r : sd)
                    if (r.job != null && r.job.commitTick() <= tick) {
                        gameCommit(c, r, transit, gs, out, seen);
                        any = true;
                    }
                for (GameSide r : sd) {
                    int t1 = Math.min(end, r.tau + r.cadence());
                    if (r.job != null || r.held || r.tau >= end || t1 > tick) continue;
                    GameSide other = sd[1 - r.s];
                    if (form == Form.C && !other.held && other.tau < r.tau) continue;   // the coupling fence
                    GamePlan p = gamePlan(c, form, r, other, r.tau / (double) TICKS, t1 / (double) TICKS, gs, transit, control);
                    r.job = gameSolve(c, r, r.tau, t1, p, gs, tick + r.latency.next(r.random, r.cadence()));
                    maxShift = Math.max(maxShift, Math.abs(r.job.shift()));
                    any = true;
                }
                if (!any) break;
            }
            if (tick <= end) for (GameSide r : sd) if (!r.held) lag[r.s] = Math.max(lag[r.s], (tick - r.tau) / (double) TICKS);
        }
        return new GameRun(out, seen, maxShift, Math.abs(sum(transit)), lag, sd[0].lagStops + sd[1].lagStops);
    }

    /** The largest difference of each outlet between runs that differ only in their jobs' latencies, from {@code from} on. */
    static double[] spread(List<GameRun> runs, int from, int last) {
        double[] d = new double[2];
        for (int i = from; i <= last; i++)
            for (int s = 0; s < 2; s++) {
                double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                for (GameRun r : runs) {
                    double v = r.outlets()[i][s];
                    if (Double.isNaN(v)) continue;
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
                if (hi >= lo) d[s] = Math.max(d[s], hi - lo);
            }
        return d;
    }

    /**
     * After a hold ending at holdTo: the online time (s after holdTo) by which both outlets, as committed, are back within
     * 1 K of the reference for good: the commit time of the last second off by more, or 0.
     */
    static double recovery(GameRun r, double[][] ref, double holdTo) {
        double at = holdTo;
        for (int i = 0; i < ref.length; i++)
            for (int s = 0; s < 2; s++)
                if (Double.isNaN(r.outlets()[i][s]) || Math.abs(r.outlets()[i][s] - ref[i][s]) > 1) at = Math.max(at, r.seen()[i][s]);
        return at - holdTo;
    }

    /** One timing row's measure over the latencies 0, 40 and five seeds of 0 to 40 ticks. */
    record Timing(double[] err0, double settle0h, double settle0c, double errWorst, double tailWorst, double settleWorst, double[] spread, double shift, double transit, double[] lag, int lagStops) {}

    static Timing timing(Case c, double[][] ref, Form form, double lenH, double lenC, boolean adaptive, Control control, double holdFrom, double holdTo) {
        int last = (int) c.tEnd();
        GameRun zero = game(c, form, lenH, lenC, adaptive, new Latency(0, 0), control, holdFrom, holdTo);
        List<GameRun> runs = new ArrayList<>(List.of(zero, game(c, form, lenH, lenC, adaptive, new Latency(MAX_LATENCY, 0), control, holdFrom, holdTo)));
        List<GameRun> seeded = new ArrayList<>();
        for (long seed = 1; seed <= 5; seed++) seeded.add(game(c, form, lenH, lenC, adaptive, new Latency(-1, seed), control, holdFrom, holdTo));
        runs.addAll(seeded);
        double errWorst = 0, tailWorst = 0, settleWorst = 0, shift = 0, transit = 0;
        double[] lag = new double[2];
        int lagStops = 0;
        for (GameRun r : runs) {
            lagStops += r.lagStops();
            double[] e = errors(r.outlets(), ref, 60, last);
            errWorst = Math.max(errWorst, Math.max(e[0], e[1]));
            tailWorst = Math.max(tailWorst, Math.max(e[2], e[3]));
            if (control == null) settleWorst = Math.max(settleWorst, Math.max(settle(r.outlets(), ref[last], 0, 60, last, 1), settle(r.outlets(), ref[last], 1, 60, last, 1)));
            shift = Math.max(shift, r.maxShift());
            transit = Math.max(transit, r.transit());
            for (int s = 0; s < 2; s++) lag[s] = Math.max(lag[s], r.lag()[s]);
        }
        double[] e0 = errors(zero.outlets(), ref, 60, last);
        return new Timing(e0, settle(zero.outlets(), ref[last], 0, 60, last, 1), settle(zero.outlets(), ref[last], 1, 60, last, 1),
                errWorst, tailWorst, settleWorst, spread(seeded, 60, last), shift, transit, lag, lagStops);
    }

    static void gameTiming(List<Case> equal, int n, double area, double steel, double water50) {
        System.out.println("PART 8  W3a: the relay on the game's clock (20 ticks/s). A slice is offered once online time reaches its end and is");
        System.out.println("  dispatched then; its job commits 0 to 40 ticks later; one job per side in flight. Stopped only while held (explicit).");
        System.out.println("  Forms: A as of the slice start (a lagging partner extrapolated at its last committed rate for one of its slices);");
        System.out.println("  B the partner's commits inside the slice piece by piece, A for the rest; C a fence: wait until the partner is through t0.");
        System.out.println("  columns: latency 0: max |error| hot, cold (K), settles (1 K) hot, cold (s); then worst over latency 0, 40 and five");
        System.out.println("  seeds of 0-40 ticks: max |error|, tail (last 100 s), settle (s); spread hot, cold over the five seeds (K);");
        System.out.println("  give-back (K); heat in transit at the end (J); largest debt hot, cold (s, online minus committed)");
        double gas20 = 20 * 1100;
        List<Case> cases = new ArrayList<>(equal.subList(0, 3));
        cases.add(new Case("L/L liquid 2000 / liquid 2000 W/m2K, water 50 kg/s each side", n, 2000 * area, 2000 * area, steel,
                new Side(water50, 150, water50, 180, 60), new Side(water50, 30, water50, 30, 1e9), 900));
        cases.add(new Case("G/L gas 100 / liquid 2000, gas 20 kg/s, water 10 kg/s", n, 100 * area, 2000 * area, steel,
                new Side(gas20, 150, gas20, 180, 60), new Side(10 * 4180, 30, 10 * 4180, 30, 1e9), 900));
        cases.add(new Case("G/G gas 100 / gas 100, gas 20 kg/s each side", n, 100 * area, 100 * area, steel,
                new Side(gas20, 150, gas20, 180, 60), new Side(gas20, 30, gas20, 30, 1e9), 900));
        cases.add(new Case("C/L condensing 5000 (150 -> 180 C) / liquid 2000, water 50 kg/s", n, 5000 * area, 2000 * area, steel,
                new Side(Double.MAX_VALUE, 150, Double.MAX_VALUE, 180, 60), new Side(water50, 30, water50, 30, 1e9), 900));
        for (double wh : new double[]{400e3, 2000e3})
            cases.add(new Case(String.format(Locale.ROOT, "glide 5000/2000, hot %4.0f kW/K", wh / 1e3), n, 5000 * area, 2000 * area, 0,
                    new Side(wh, 150, wh, 180, 60), new Side(water50, 30, water50, 30, 1e9), 900));
        for (double kgs : new double[]{10, 25, 200}) {
            double w = kgs * 4180;
            cases.add(new Case(String.format(Locale.ROOT, "L/L %4.0f kg/s", kgs), n, 2000 * area, 2000 * area, 0,
                    new Side(w, 150, w, 180, 60), new Side(w, 30, w, 30, 1e9), 900));
        }
        Object[][] slicing = {{1.0, 1.0, false}, {5.0, 5.0, false}, {20.0, 20.0, false}, {5.0, 20.0, false}, {20.0, 5.0, false}, {5.0, 20.0, true}};
        double[][] worst = new double[Form.values().length][7];   // error, tail, settle, spread, shift, debt, behind
        for (Case c : cases) {
            double[][] ref = truthQuasi(c);
            int last = (int) c.tEnd();
            System.out.println(String.format(Locale.ROOT, "%s: steady outlets %.2f / %.2f C", c.name(), ref[last][0], ref[last][1]));
            for (Object[] sl : slicing)
                for (Form form : Form.values()) {
                    Timing t = timing(c, ref, form, (Double) sl[0], (Double) sl[1], (Boolean) sl[2], null, -1, -1);
                    System.out.println(row(String.format(Locale.ROOT, "%4.0f/%-4.0f%s", (Double) sl[0], (Double) sl[1], (Boolean) sl[2] ? "~" : " "), form, t, true));
                    double[] w = worst[form.ordinal()];
                    w[0] = Math.max(w[0], t.errWorst());
                    w[1] = Math.max(w[1], t.tailWorst());
                    w[2] = Math.max(w[2], t.settleWorst());
                    w[3] = Math.max(w[3], Math.max(t.spread()[0], t.spread()[1]));
                    w[4] = Math.max(w[4], t.shift());
                    w[5] = Math.max(w[5], Math.max(t.lag()[0], t.lag()[1]));
                    w[6] += t.lagStops();
                }
        }
        System.out.println();
        System.out.println("PART 8b holds, case A to 1500 s: hot island held 100 to 160 s and 100 to 700 s (its job dropped; stopped while held)");
        System.out.println("  worst over the 7 latency runs: tail (last 100 s, K); recovered: online s after the hold until both committed outlets stay");
        System.out.println("  within 1 K; heat in transit at the end (J); largest debt hot, cold (s); behind (slices that counted the partner stopped)");
        Case a = equal.get(0);
        Case longA = new Case(a.name(), a.n(), a.uaHot(), a.uaCold(), a.c(), a.hot(), a.cold(), 1500);
        double[][] refQ = truthQuasi(longA);
        for (boolean lagStops : new boolean[]{true, false}) {
            LAG_STOPS = lagStops;
            if (!lagStops) System.out.println("  the plan's rule for A and B (stopped only while held; a lagging partner extrapolated for one of its slices):");
            for (int holdTo : new int[]{160, 700})
                for (Object[] sl : lagStops ? slicing : new Object[][]{{1.0, 1.0, false}, {5.0, 20.0, false}})
                    for (Form form : lagStops ? Form.values() : new Form[]{Form.A, Form.B}) {
                        double tail = 0, recovered = 0, transit = 0;
                        double[] lag = new double[2];
                        int behind = 0;
                        List<Latency> lats = new ArrayList<>(List.of(new Latency(0, 0), new Latency(MAX_LATENCY, 0)));
                        for (long seed = 1; seed <= 5; seed++) lats.add(new Latency(-1, seed));
                        for (Latency l : lats) {
                            GameRun r = game(longA, form, (Double) sl[0], (Double) sl[1], (Boolean) sl[2], l, null, 100, holdTo);
                            double[] e = errors(r.outlets(), refQ, 60, 1500);
                            tail = Math.max(tail, Math.max(e[2], e[3]));
                            recovered = Math.max(recovered, recovery(r, refQ, holdTo));
                            transit = Math.max(transit, r.transit());
                            for (int s = 0; s < 2; s++) lag[s] = Math.max(lag[s], r.lag()[s]);
                            behind += r.lagStops();
                        }
                        System.out.println(String.format(Locale.ROOT, "  held to %3d, %4.0f/%-4.0f%s %s  tail %s   recovered %5.0f s   transit %.1e J   debt %5.1f %5.1f s   behind %d",
                                holdTo, (Double) sl[0], (Double) sl[1], (Boolean) sl[2] ? "~" : " ", form, fmt(tail), recovered, transit, lag[0], lag[1], behind));
                    }
        }
        LAG_STOPS = true;
        System.out.println();
        System.out.println("PART 8c cold outlet target by a cold-side bypass, dead-beat: worst |T - target| from the step (K), over the 7 latency runs");
        Object[][] controlled = {{a, 90.0}, {equal.get(2), 40.0}, {cases.get(3), 100.0}};
        for (Object[] pair : controlled) {
            Case c = (Case) pair[0];
            double target = (Double) pair[1];
            for (Object[] sl : slicing) {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-3s target %3.0f C slices %4.0f/%-4.0f%s", c.name().substring(0, 3), target, (Double) sl[0], (Double) sl[1], (Boolean) sl[2] ? "~" : " "));
                for (Form form : Form.values()) {
                    double worstT = 0, late = 0;
                    List<Latency> lats = new ArrayList<>(List.of(new Latency(0, 0), new Latency(MAX_LATENCY, 0)));
                    for (long seed = 1; seed <= 5; seed++) lats.add(new Latency(-1, seed));
                    for (Latency l : lats) {
                        GameRun r = game(c, form, (Double) sl[0], (Double) sl[1], (Boolean) sl[2], l, new Control(target, 1), -1, -1);
                        double[] w = controlWorst(new Run(r.outlets(), 0, 0), c, target);
                        worstT = Math.max(worstT, w[0]);
                        late = Math.max(late, w[1]);
                    }
                    line.append(String.format(Locale.ROOT, "   %s %6.2f K, last outside 0.1 K +%4.0f s", form, worstT, late));
                }
                System.out.println(line);
            }
        }
        System.out.println();
        System.out.println("PART 8d summary over the Part 8 cases and slicings (not the holds): worst max |error| (K), tail (K), settle (s), spread (K), give-back (K), debt (s);");
        System.out.println("  behind: slices that counted their partner stopped for lagging (A, B), over every run");
        for (Form form : Form.values()) {
            double[] w = worst[form.ordinal()];
            System.out.println(String.format(Locale.ROOT, "  %s  error %7.2f  tail %6.3f  settle %5.0f  spread %7.3f  give-back %6.2f  debt %5.1f  behind %.0f", form, w[0], w[1], w[2], w[3], w[4], w[5], w[6]));
        }
    }

    static String row(String label, Form form, Timing t, boolean settles) {
        return String.format(Locale.ROOT, "  %s %s  lat0 max %s %s%s | worst max %s tail %s%s | spread %7.3f %7.3f | give-back %6.2f K, transit %.1e J, debt %5.1f %5.1f s, behind %d",
                label, form, fmt(t.err0()[0]), fmt(t.err0()[1]),
                settles ? String.format(Locale.ROOT, " settles %4.0f %4.0f", t.settle0h(), t.settle0c()) : "",
                fmt(t.errWorst()), fmt(t.tailWorst()), settles ? String.format(Locale.ROOT, " settle %4.0f", t.settleWorst()) : "",
                t.spread()[0], t.spread()[1], t.shift(), t.transit(), t.lag()[0], t.lag()[1], t.lagStops());
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
