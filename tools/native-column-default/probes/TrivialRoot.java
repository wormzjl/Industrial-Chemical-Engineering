import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** PR78 (kernel equations, kij = 0) for the cdu17 PC03/PC10 binary of the column test fixture. */
public class TrivialRoot {
    static final double R = 8.31446261815324, S2 = Math.sqrt(2.0);
    static final double[] TC = {569.3692139045123, 869.1971815576578};
    static final double[] PC = {3246675.3539700373, 1114480.0231325733};
    static final double[] W = {0.3244879676576933, 1.039341380087733};
    static final double[] CP0 = {145.0, 374.0}, CP1 = {0.295, 0.47};

    static double kappa(double w) {
        return w <= 0.491 ? 0.37464 + 1.54226 * w - 0.26992 * w * w
                : 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w * w * w;
    }

    record Phase(double z, double[] lnPhi, double hRes, int roots) {}

    static double[] cubic(double c2, double c1, double c0) {
        double p = c1 - c2 * c2 / 3, q = 2 * c2 * c2 * c2 / 27 - c2 * c1 / 3 + c0, d = q * q / 4 + p * p * p / 27;
        double[] r;
        if (d > 0) { double s = Math.sqrt(d); r = new double[] {Math.cbrt(-q / 2 + s) + Math.cbrt(-q / 2 - s) - c2 / 3}; }
        else {
            double m = 2 * Math.sqrt(-p / 3), a = Math.acos(Math.max(-1, Math.min(1, 3 * q / (2 * p) * Math.sqrt(-3 / p)))) / 3;
            r = new double[] {m * Math.cos(a) - c2 / 3, m * Math.cos(a - 2 * Math.PI / 3) - c2 / 3, m * Math.cos(a - 4 * Math.PI / 3) - c2 / 3};
        }
        for (int i = 0; i < r.length; i++) for (int k = 0; k < 3; k++) { // Newton polish
            double z = r[i], f = ((z + c2) * z + c1) * z + c0, df = (3 * z + 2 * c2) * z + c1;
            if (df != 0) r[i] = z - f / df;
        }
        return r;
    }

    static Phase phase(double t, double p, double[] x, boolean vapor) {
        double[] a = new double[2], b = new double[2], dadt = new double[2], sa = new double[2], g = new double[2];
        for (int i = 0; i < 2; i++) {
            double ac = 0.45724 * R * R * TC[i] * TC[i] / PC[i], k = kappa(W[i]), al = 1 + k * (1 - Math.sqrt(t / TC[i]));
            a[i] = ac * al * al; dadt[i] = -ac * k * al / Math.sqrt(t * TC[i]); sa[i] = Math.sqrt(a[i]); b[i] = 0.07780 * R * TC[i] / PC[i];
        }
        double am = 0, bm = 0, damdt = 0;
        for (int i = 0; i < 2; i++) { bm += x[i] * b[i]; for (int j = 0; j < 2; j++) g[i] += x[j] * sa[j]; }
        for (int i = 0; i < 2; i++) { am += x[i] * sa[i] * g[i]; damdt += x[i] * dadt[i] / sa[i] * g[i]; }
        double A = am * p / (R * R * t * t), B = bm * p / (R * t);
        List<Double> phys = new ArrayList<>();
        for (double r : cubic(-(1 - B), A - 3 * B * B - 2 * B, -(A * B - B * B - B * B * B))) if (r > B) phys.add(r);
        double z = vapor ? Collections.max(phys) : Collections.min(phys);
        double lr = Math.log((z + (1 + S2) * B) / (z + (1 - S2) * B));
        double[] lnPhi = new double[2];
        for (int i = 0; i < 2; i++) { double br = b[i] / bm; lnPhi[i] = br * (z - 1) - Math.log(z - B) - A / (2 * S2 * B) * (2 * sa[i] * g[i] / am - br) * lr; }
        return new Phase(z, lnPhi, R * t * (z - 1) + (t * damdt - am) / (2 * S2 * bm) * lr, phys.size());
    }

    static double hIdeal(double t, double[] x) { // integral of cp from 298.15 K
        double h = 0, d = t - 298.15;
        for (int i = 0; i < 2; i++) h += x[i] * (CP0[i] * d + CP1[i] * d * d / 2);
        return h;
    }

    /** {beta, x03, y03} of a TP flash by successive substitution from Wilson K. */
    static double[] flash(double t, double p, double[] zf) {
        double[] k = new double[2];
        for (int i = 0; i < 2; i++) k[i] = PC[i] / p * Math.exp(5.373 * (1 + W[i]) * (1 - TC[i] / t));
        double beta = 0; double[] x = zf.clone(), y = zf.clone();
        for (int it = 0; it < 2000; it++) {
            double f0 = 0, f1 = 0;
            for (int i = 0; i < 2; i++) { f0 += zf[i] * (k[i] - 1); f1 += zf[i] * (k[i] - 1) / k[i]; }
            if (f0 <= 0) beta = 0; else if (f1 >= 0) beta = 1;
            else { double lo = 0, hi = 1; for (int s = 0; s < 200; s++) { double m = (lo + hi) / 2, f = 0; for (int i = 0; i < 2; i++) f += zf[i] * (k[i] - 1) / (1 + m * (k[i] - 1)); if (f > 0) lo = m; else hi = m; } beta = (lo + hi) / 2; }
            double sx = 0, sy = 0;
            for (int i = 0; i < 2; i++) { x[i] = zf[i] / (1 + beta * (k[i] - 1)); y[i] = k[i] * x[i]; sx += x[i]; sy += y[i]; }
            for (int i = 0; i < 2; i++) { x[i] /= sx; y[i] /= sy; }
            Phase l = phase(t, p, x, false), v = phase(t, p, y, true);
            double change = 0;
            for (int i = 0; i < 2; i++) { double kn = Math.exp(l.lnPhi()[i] - v.lnPhi()[i]); change = Math.max(change, Math.abs(Math.log(kn / k[i]))); k[i] = kn; }
            if (change < 1e-12) break;
        }
        return new double[] {beta, x[0], y[0], k[0], k[1]};
    }

    public static void main(String[] args) {
        double p = 250_000.0 + 750.0; // feed tray of the 2-tray fixture
        double[] z = {0.5, 0.5};
        System.out.println("TP flash of the 50/50 feed at " + p / 1000 + " kPa:");
        for (double t : new double[] {450, 500, 550, 600}) {
            double[] f = flash(t, p, z);
            System.out.printf("  %3.0f K: vapour fraction %.4f, liquid x03 %.4f, vapour y03 %.4f, K03 %.3g, K10 %.3g%n", t, f[0], f[1], f[2], f[3], f[4]);
        }
        double lo = 300, hi = 800; // bubble point: first T with vapour
        for (int s = 0; s < 60; s++) { double m = (lo + hi) / 2; if (flash(m, p, z)[0] > 0) hi = m; else lo = m; }
        double bubble = hi; lo = 300; hi = 900;
        for (int s = 0; s < 60; s++) { double m = (lo + hi) / 2; if (flash(m, p, z)[0] < 1) lo = m; else hi = m; }
        System.out.printf("  bubble point %.1f K, dew point %.1f K%n", bubble, lo);
        System.out.println("PR roots of the 50/50 mixture itself (the trivial state x = y = z) at 250 kPa:");
        for (double t : new double[] {300, 350, 400, 450, 500, 550, 600}) {
            Phase l = phase(t, 250_000, z, false), v = phase(t, 250_000, z, true);
            System.out.printf("  %3.0f K: %d physical root(s); 'liquid' Z %.5f, 'vapour' Z %.5f; ln K03 %.2e, h_V - h_L %.1f J/mol%n",
                    t, l.roots(), l.z(), v.z(), l.lnPhi()[0] - v.lnPhi()[0], v.hRes() - l.hRes());
        }
        double one = 450, three = 500; // where the 50/50 mixture gains its vapour root at 250 kPa
        for (int s = 0; s < 60; s++) { double m = (one + three) / 2; if (phase(m, 250_000, z, true).roots() > 1) three = m; else one = m; }
        System.out.printf("  single liquid root up to %.1f K; three roots above%n", one);
        Phase l450 = phase(450, 250_000, z, false), l300 = phase(300, 250_000, z, false), l382 = phase(381.9, 250_000, z, false);
        double dh = hIdeal(450, z) + l450.hRes() - hIdeal(300, z) - l300.hRes();
        System.out.printf("Liquid 50/50 from 450 K to 300 K: %.1f kJ/mol, %.2f MW for 100 mol/s (column published 5.64 MW)%n", dh / 1000, dh * 100 / 1e6);
        double dh2 = 80.845 * (hIdeal(450, z) + l450.hRes() - hIdeal(300, z) - l300.hRes()) + 19.155 * (hIdeal(450, z) + l450.hRes() - hIdeal(381.9, z) - l382.hRes());
        System.out.printf("Same, 80.845 mol/s to 300 K and 19.155 mol/s to 381.9 K: %.2f MW (column published 5.07 MW)%n", dh2 / 1e6);
    }
}
