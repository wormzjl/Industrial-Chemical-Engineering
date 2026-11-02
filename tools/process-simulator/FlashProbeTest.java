package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Locale;

final class FlashProbeTest {
    static final String PACKAGE = FluidMaterialCatalog.NETWORK_PACKAGE;
    static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    static void line(String name, FluidThermodynamics.State s) {
        System.out.printf(Locale.ROOT, "  %-10s T %.4f P %.0f H %.3f V %.5f L %.5f Wl %.5f Wv %.5f%n", name, s.temperature(), s.pressure(), s.enthalpy(), Arrays.stream(s.vaporView()).sum(), Arrays.stream(s.liquidView()).sum(), s.waterLiquid(), s.waterVapor());
    }
    static void sweep(FluidThermodynamics m, double[] n, double p, double... ts) {
        Runnable c = () -> {};
        for (double t : ts) {
            var tp = m.flashTP(t, p, n, c);
            System.out.printf(Locale.ROOT, "T %.2f P %.0f%n", t, p);
            line("TP", tp);
            var ph = m.equilibriumPH(t, p, tp.enthalpy(), n, c);
            if (ph == null) System.out.println("  PH null"); else line("PH same", ph);
            var far = m.equilibriumPH(t + 40, p, tp.enthalpy(), n, c);
            if (far == null) System.out.println("  PH null far"); else line("PH far", far);
        }
    }
    static void scan(FluidThermodynamics m, double[] n, double p, double from, double to, double step) {
        Runnable c = () -> {};
        System.out.printf(Locale.ROOT, "== scan P %.0f%n", p);
        double last = Double.NaN;
        for (double t = from; t <= to + 1e-9; t += step) {
            try {
                var s = m.flashTP(t, p, n, c);
                var ph = m.equilibriumPH(t, p, s.enthalpy(), n, c);
                System.out.printf(Locale.ROOT, "  T %.3f H %.1f dH %.1f V %.4f L %.4f Wl %.4f Wv %.4f | PH T %s%n", t, s.enthalpy(), s.enthalpy() - last, Arrays.stream(s.vaporView()).sum(), Arrays.stream(s.liquidView()).sum(), s.waterLiquid(), s.waterVapor(), ph == null ? "null" : String.format(Locale.ROOT, "%.4f H %.1f", ph.temperature(), ph.enthalpy()));
                last = s.enthalpy();
            } catch (RuntimeException e) { System.out.println("  T " + t + " threw " + e.getMessage()); }
        }
    }
    /** Round trips and offset targets across a grid, counting failures and flashes. */
    @Test void probe() {
        MaterialRuntime.with(CATALOG, PACKAGE, () -> {
            var m = FluidThermodynamics.forNetwork(CATALOG, PACKAGE);
            var axis = m.components();
            int size = axis.size();
            double[] wet = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE).crudeFeed("icheme:tia_juana_light_methane").moleFractions(), size);
            wet[size - 1] = .2;
            double[] dry = wet.clone(); dry[size - 1] = 0;
            double[] heavy = new double[size]; heavy[size - 3] = 1; heavy[size - 1] = .3;
            double[] water = new double[size]; water[size - 1] = 1;
            double[] nitrogen = new double[size]; nitrogen[axis.indexOf(FluidMaterialCatalog.NITROGEN)] = 1;
            double[] light = new double[size]; light[0] = .5; light[1] = .3; light[2] = .2;
            double[] residue = new double[size]; for (int i = size - 11; i < size - 1; i++) residue[i] = wet[i]; residue[size - 1] = .3 * Arrays.stream(residue).sum();
            String[] names = {"wet", "dry", "heavy", "residue", "water", "nitrogen", "light"};
            double[][] feeds = {wet, dry, heavy, residue, water, nitrogen, light};
            double[] ts = {90, 120, 200, 260, 280, 300, 320, 350, 373.15, 376, 379, 382, 400, 420, 450, 480, 485, 490, 500, 507, 550, 600, 650, 700};
            double[] ps = {5e3, 1e5, 2e5, 5e5, 10e5, 20e5, 30e5, 60e5, 100e5};
            double[] duties = {0, 1, -1, 50, -50, 400, -400, 3000, -3000, 30000, -30000};
            for (int k = 0; k < feeds.length; k++) {
                double[] n = feeds[k].clone();
                double sum = Arrays.stream(n).sum();
                for (int i = 0; i < size; i++) n[i] *= 100 / sum;
                var thermo = new FlowThermo(m, CATALOG, PACKAGE, () -> {});
                int tried = 0, failed = 0, noState = 0, off = 0, refused = 0; long flashes = 0; double worst = 0;
                var seen = new java.util.TreeMap<String, Integer>();
                for (double t : ts) for (double p : ps) {
                    FlowStream from;
                    try { from = thermo.atTemperature(t, p, n, com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY); } catch (RuntimeException e) { refused++; continue; }
                    for (double perMole : duties) {
                        double watts = perMole * 100;
                        double outP = perMole > 0 ? p : p * .5;
                        tried++;
                        try {
                            var to = thermo.heated(from, watts, outP);
                            double gap = Math.abs(to.enthalpy() - from.enthalpy() - watts);
                            worst = Math.max(worst, gap);
                            if (gap > .1) off++;
                        } catch (FlowsheetFailure e) {
                            noState++; seen.merge(e.key(), 1, Integer::sum);
                            if (k < 4 && noState <= 6) System.out.printf(Locale.ROOT, "  no state %s T %.1f P %.0f q %.0f%n", names[k], t, p, perMole);
                        } catch (RuntimeException e) {
                            failed++; String key = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()); key = key.substring(0, Math.min(70, key.length())); seen.merge(key, 1, Integer::sum);
                            if (k == 2 && failed % 20 == 1) { var ph = (FluidThermodynamics.State) null; String answer; try { ph = m.equilibriumPH(t, outP, from.enthalpy() + watts, n, () -> {}); answer = ph == null ? "null" : "T " + ph.temperature(); } catch (RuntimeException x) { answer = "threw"; }
                                System.out.printf(Locale.ROOT, "  heavy T %.1f P %.0f q %.0f outP %.0f PH %s | %s%n", t, p, perMole, outP, answer, String.valueOf(e.getMessage()).substring(0, Math.min(200, String.valueOf(e.getMessage()).length()))); }
                        }
                    }
                }
                System.out.printf(Locale.ROOT, "SWEEP %-9s refused TP %d tried %d noState %d thrown %d off %d worst %.4f %s%n", names[k], refused, tried, noState, failed, off, worst, seen);
            }
            return null;
        });
    }
}
