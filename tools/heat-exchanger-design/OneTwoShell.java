import java.util.Locale;

/**
 * 1-2 shell-and-tube (TEMA E shell, two tube passes) as a cell model: M axial positions, two wall cells per position
 * (one per tube pass). Tubes: pass 1 front->back over positions 0..M-1, pass 2 back->front. Shell: back->front, and at
 * each position it meets both passes' walls. Equal film coefficients: each side's UA_side = 2 UA, spread over 2M wall
 * cells. Steady state by fixed-point iteration; compared with the exact 1-2 formula and with counterflow.
 * Run: java OneTwoShell.java
 */
public class OneTwoShell {
    static double share(double w, double g) { return w > 0 ? -Math.expm1(-g / w) : 0; }

    static double exact12(double ntu, double cr) {
        double s = Math.sqrt(1 + cr * cr), e = Math.exp(-ntu * s);
        return 2 / (1 + cr + s * (1 + e) / (1 - e));
    }

    static double counter(double ntu, double cr) {
        if (Math.abs(1 - cr) < 1e-12) return ntu / (1 + ntu);
        double e = Math.exp(-ntu * (1 - cr));
        return (1 - e) / (1 - cr * e);
    }

    /** Returns the effectiveness on the side with the smaller capacity rate; tubes hot at 100, shell cold at 0. */
    static double cells(int m, double ntu, double cr, boolean tubesMin) {
        double ua = 1, wmin = ua / ntu, wmax = cr == 0 ? 1e300 : wmin / cr;
        double wt = tubesMin ? wmin : wmax, ws = tubesMin ? wmax : wmin;
        double g = 2 * ua / (2 * m);                 // per wall cell, each side
        double[] w1 = new double[m], w2 = new double[m];
        java.util.Arrays.fill(w1, 50);
        java.util.Arrays.fill(w2, 50);
        double tubeOut = 0, shellOut = 0;
        for (int it = 0; it < 400000; it++) {
            // tubes: pass 1 positions 0..m-1, pass 2 positions m-1..0
            double t = 100, ft = share(wt, g);
            double[] tin1 = new double[m], tin2 = new double[m];
            for (int k = 0; k < m; k++) { tin1[k] = t; t -= ft * (t - w1[k]); }
            for (int k = m - 1; k >= 0; k--) { tin2[k] = t; t -= ft * (t - w2[k]); }
            tubeOut = t;
            // shell: back (m-1) to front (0); at each position it meets both walls (2g total), relaxing to their mean
            double s = 0;
            double[] mean = new double[m];
            for (int k = m - 1; k >= 0; k--) {
                double tm = (w1[k] + w2[k]) / 2, a = 2 * g / ws, out = tm + (s - tm) * Math.exp(-a);
                mean[k] = ws > 1e299 ? s : tm + (s - tm) * (-Math.expm1(-a)) / a;
                s = ws > 1e299 ? s : out;
            }
            shellOut = s;
            double gt = wt > 1e299 ? g : wt * ft, change = 0;
            for (int k = 0; k < m; k++) {
                double n1 = (gt * tin1[k] + g * mean[k]) / (gt + g), n2 = (gt * tin2[k] + g * mean[k]) / (gt + g);
                change = Math.max(change, Math.max(Math.abs(n1 - w1[k]), Math.abs(n2 - w2[k])));
                w1[k] = n1;
                w2[k] = n2;
            }
            if (change < 1e-11) break;
        }
        return tubesMin ? (100 - tubeOut) / 100 : shellOut / 100;
    }

    public static void main(String[] a) {
        System.out.println("1-2 shell (two tube passes): exact formula, the cell model (M positions x 2 passes), and counterflow");
        System.out.println("  NTU  Cr    exact12  M=2     M=4     M=8     counter  counter/exact12");
        for (double ntu : new double[]{0.5, 1, 2, 5}) for (double cr : new double[]{0, 0.5, 1}) {
            StringBuilder r = new StringBuilder(String.format(Locale.ROOT, "  %-4.1f %-5.2f %.4f   ", ntu, cr, exact12(ntu, cr)));
            for (int m : new int[]{2, 4, 8}) r.append(String.format(Locale.ROOT, "%.4f  ", cells(m, ntu, cr, true)));
            r.append(String.format(Locale.ROOT, "%.4f   %+.1f%%", counter(ntu, cr), 100 * (counter(ntu, cr) / exact12(ntu, cr) - 1)));
            System.out.println(r);
        }
        System.out.println("  (tubes carry the smaller capacity rate; the same check with the shell on Cmin:)");
        for (double ntu : new double[]{1, 2, 5}) {
            double cr = .5;
            System.out.println(String.format(Locale.ROOT, "  %-4.1f %-5.2f %.4f   M=4 %.4f  M=8 %.4f", ntu, cr, exact12(ntu, cr), cells(4, ntu, cr, false), cells(8, ntu, cr, false)));
        }
    }
}
