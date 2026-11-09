package com.wormzjl.icheme.science.thermo.qualification;

import static com.wormzjl.icheme.science.thermo.qualification.G3Support.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzFluid;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * G3 fixture family F10 (plan {@code P3_PILOT_ENGINE_PLAN.md} section 8.2; P0 sections 2.3 and 3.1): the old qualified
 * region. The bundled network package {@code icheme:tjl20_methane_nitrogen} at states inside its previous domain
 * (nitrogen 63.151-900 K, methane 293.15-900 K, both to 2 MPa before P3), re-checked at the D7 targets against the Java
 * oracle; the P0 baseline saturation states (P0 section 2.3) on both the bundled network (inside its previous domain)
 * and the pilot package; and the hot nitrogen and methane gas of the old domain in the network's vapour slot. The
 * bitwise hold of the legacy path is WP5's {@code LegacyNetworkPathPinTest}; the existing gates
 * ({@code FluidPropertyCoverageTest}, {@code FluidNitrogenCryogenicTest}, {@code LiquidCompressionQualificationTest})
 * run with this family in the G3 Gradle invocation.
 */
class G3F10QualifiedRegionTest {
    /** P0 section 2.3 saturation states, K (H2 is P7's). */
    static final double[][] P0_SATURATION = {{63.15, 77.36, 100, 110, 120}, {90.7, 111.67, 150, 170, 185}, {90.4, 120, 184.55, 250, 280},
            {216.6, 250, 270, 290}};

    @Test
    void p0BaselineSaturationStatesAtTheTargets() {
        var table = new Table("G3 F10 P0", "Package", "Species", "T K", "Tr", "Psat %", "sat. liquid rho %", "liquid cp %", "hvap %", "Band", "D7 verdicts");
        List<String> failures = new ArrayList<>();
        for (var model : List.of(network(), pilot())) {
            boolean net = model == network();
            for (int s = 0; s < 4; s++) {
                if (net && s != 0) continue; // the bundled network's liquid: nitrogen only (methane from 293.15 K, above its Tc)
                var fluid = oracle(s);
                double[] x = pure(model, SPECIES[s]);
                for (double listed : P0_SATURATION[s]) {
                    double t = Math.max(listed, fluid.tripleTemperature()); // 63.15 K is 1 mK below the N2 triple point
                    var sat = fluid.saturation(t);
                    double pRef = sat.pressure(), tr = t / fluid.criticalTemperature();
                    if (net && pRef > 2e6) {
                        table.row("net", SHORT[s], number(t), f("%.3f", tr), "-", "-", "-", "-", "-", "outside the previous 2 MPa domain (pilot row below)");
                        continue;
                    }
                    double psat = saturationPressure(model, SPECIES[s], t, pRef);
                    double dp = 100 * (psat / pRef - 1);
                    var liquid = evaluate(model, t, pRef, x, Root.LIQUID);
                    double rho = 100 * (liquid.density() / sat.liquid().density() - 1);
                    double cp = 100 * (liquid.heatCapacity() / sat.liquid().isobaricHeatCapacity() - 1);
                    var l = evaluate(model, t, psat, x, Root.LIQUID);
                    var v = evaluate(model, t, psat, x, Root.VAPOR);
                    double reference = sat.vapour().enthalpy() - sat.liquid().enthalpy();
                    double hvap = 100 * ((v.enthalpy() - l.enthalpy()) / reference - 1);
                    int box = bandBox(tr, pRef / fluid.criticalPressure());
                    // D7 targets (P0 section 3.2 with the P1 amendments): Psat 1.5 % from Tr 0.6, 5 % from 0.45 (ethane below 150 K
                    // declared, D3); saturated liquid 3 % to Tr 0.85, 10 % declared to 0.95; liquid cp 10 % to 0.8, 20 % declared to
                    // 0.9 (ethane below 150 K: D15 12 % declared, F1); hvap 3.5 % to 0.9.
                    var verdicts = new ArrayList<String>();
                    boolean ethaneCold = s == 2 && t < 150;
                    double psatTarget = ethaneCold || tr < 0.45 ? Double.NaN : tr < 0.6 ? 5 : 1.5;
                    double rhoTarget = tr <= 0.85 ? 3 : 10, cpTarget = ethaneCold ? 12 : tr <= 0.8 ? 10 : tr <= 0.9 ? 20 : Double.NaN;
                    if (box == 0) {
                        check(verdicts, failures, "Psat", dp, psatTarget, SHORT[s] + " " + t);
                        check(verdicts, failures, "rho", rho, rhoTarget, SHORT[s] + " " + t);
                        if (ethaneCold) {
                            // D7 10 % (liquid cp to Tr 0.8); ethane below 150 K held at D15's 12 % declared (F1).
                            verdicts.add(Math.abs(cp) <= 10 ? "cp met" : "cp NOT met (D7 10 %; D15 12 % declared, held)");
                            if (Math.abs(cp) > 12) failures.add(SHORT[s] + " " + t + " cp " + cp);
                        } else check(verdicts, failures, "cp", cp, cpTarget, SHORT[s] + " " + t);
                        if (tr <= 0.9) check(verdicts, failures, "hvap", hvap, 3.5, SHORT[s] + " " + t);
                    }
                    table.row(net ? "net" : "pilot", SHORT[s], number(t), f("%.3f", tr), f("%+.2f", dp), f("%+.2f", rho), f("%+.2f", cp), f("%+.2f", hvap),
                            box == 0 ? "-" : "box " + box, box == 0 ? String.join(", ", verdicts) : "research-only, not scored");
                }
            }
        }
        table.print("P0 baseline saturation states (P0 section 2.3) at the D7 targets: bundled network (anchor at 90 K, 2 MPa) and pilot (Tr 0.8)");
        failures.forEach(failure -> System.out.println("G3 F10 FAILURE: " + failure));
        assertEquals(List.of(), failures);
    }

    static void check(List<String> verdicts, List<String> failures, String what, double deviation, double target, String at) {
        if (Double.isNaN(target)) {
            verdicts.add(what + " declared");
            return;
        }
        boolean met = Math.abs(deviation) <= target;
        verdicts.add(what + (met ? " met" : " NOT met"));
        if (!met) failures.add(at + " " + what + " " + f("%+.2f", deviation) + " beyond " + target);
    }

    /**
     * The bundled network's gas inside its previous domain against the oracle: nitrogen 150-900 K and methane
     * 293.15-600 K (the Setzmann-Wagner equation ends at 625 K) at 0.1-2 MPa: density (D7 1 %, D14 2.5 % at Z >= 0.8)
     * and cp (D7 2 % at Z >= 0.9); and the ideal gas of its shifted_polynomial_5 fits against the pilot spines (N2 CEA,
     * methane GERG-2008/Setzmann-Wagner), which D6 keeps unchanged until P8.
     */
    @Test
    void bundledNetworkGasInsideItsPreviousDomain() {
        var net = network();
        var table = new Table("G3 F10 gas", "Species", "States", "Worst rho % (state)", "D7 1 % / D14 2.5 %", "Worst cp % (state)", "D7 2 %",
                "Ideal-gas Cp fit vs spine at 300 / 600 / 900 K %");
        List<String> failures = new ArrayList<>();
        for (int s : new int[] {0, 1}) {
            var fluid = oracle(s);
            double[] x = pure(net, SPECIES[s]);
            var rho = new Worst();
            var cp = new Worst();
            int n = 0;
            double[] temperatures = s == 0 ? new double[] {150, 200, 300, 450, 600, 750, 900} : new double[] {293.15, 350, 450, 600};
            for (double t : temperatures) {
                for (double p : new double[] {1e5, 5e5, 1e6, 2e6}) {
                    if (t < fluid.criticalTemperature() && p > fluid.saturation(t).pressure()) continue;
                    var e = evaluate(net, t, p, x, Root.VAPOR);
                    var reference = fluid.stateAtPressure(t, p, HelmholtzFluid.Root.VAPOUR);
                    double dRho = 100 * (e.density() / reference.density() - 1), dCp = 100 * (e.heatCapacity() / reference.isobaricHeatCapacity() - 1);
                    String at = f("%.0f K %.1f MPa", t, p / 1e6);
                    rho.add(dRho, at);
                    cp.add(dCp, at);
                    if (Math.abs(dRho) > 2.5) failures.add(SHORT[s] + " " + at + " density " + dRho);
                    // Real-gas cp (D7 2 %): the ideal part of the network is its fit (D6), so the cp deviation includes the fit's.
                    if (Math.abs(dCp) > 2) failures.add(SHORT[s] + " " + at + " cp " + dCp);
                    n++;
                }
            }
            var spine = net.hydrocarbon.propertyPackage();
            var pilotSpine = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot().requireSpine(PILOT, SPECIES[s]);
            var fits = new StringBuilder();
            for (double t : new double[] {300, 600, 900}) {
                var e = evaluate(net, t, 1.0, x, Root.VAPOR);
                fits.append(fits.isEmpty() ? "" : " / ").append(f("%+.2f", 100 * (e.heatCapacity() / pilotSpine.heatCapacity(t) - 1)));
            }
            table.row(SHORT[s], n, rho.signed("%+.2f"), verdict(1, rho.value) + " / " + verdict(2.5, rho.value), cp.signed("%+.2f"), verdict(2, cp.value), fits);
        }
        table.print("the bundled network's nitrogen and methane gas inside the previous domain (0.1-2 MPa), against the oracle");
        failures.forEach(failure -> System.out.println("G3 F10 FAILURE: " + failure));
        assertEquals(List.of(), failures);
    }

    /**
     * The hot gas of the old domain takes the network's vapour slot: nitrogen, methane and a nitrogen/methane mixture at
     * 300-900 K and 0.1-2 MPa through the bundled network's flash. The retired root heuristic put them there; since WP7
     * the engine's phase identification parameter decides, which creeps just above one above a gas's Boyle temperature;
     * at {@code 8e5274e} hot N2 (650-900 K) took the liquid slot, fixed by P3 WP7c ({@code f2d5221}: liquid-like only with
     * PIP above one and above Z).
     */
    @Test
    void hotGasOfTheOldDomainTakesTheVapourSlot() {
        FluidThermodynamics net = network();
        var components = net.hydrocarbon.components();
        List<String> wrong = new ArrayList<>();
        int states = 0;
        double[][] feeds = new double[3][net.componentCount()];
        feeds[0][components.indexOf("Nitrogen")] = 1;
        feeds[1][components.indexOf("Methane")] = 1;
        feeds[2][components.indexOf("Nitrogen")] = 0.5;
        feeds[2][components.indexOf("Methane")] = 0.5;
        String[] names = {"N2", "CH4", "N2/CH4 0.5/0.5"};
        for (int k = 0; k < 3; k++) {
            for (double t : new double[] {300, 450, 600, 650, 700, 800, 900}) {
                for (double p : new double[] {1e5, 5e5, 2e6}) {
                    var state = net.flashTP(t, p, feeds[k], () -> { });
                    double liquid = 0;
                    for (double amount : state.liquidView()) liquid += amount;
                    states++;
                    if (liquid != 0) wrong.add(f("%s %.0f K %.1f MPa", names[k], t, p / 1e6));
                }
            }
        }
        System.out.println(f("G3 F10 hot gas of the old domain in the vapour slot: %d of %d states%s", states - wrong.size(), states,
                wrong.isEmpty() ? "" : "; in the liquid slot: " + wrong));
        assertEquals(List.of(), wrong, "hot dilute gas is vapour-like");
        assertTrue(states == 63);
    }
}
