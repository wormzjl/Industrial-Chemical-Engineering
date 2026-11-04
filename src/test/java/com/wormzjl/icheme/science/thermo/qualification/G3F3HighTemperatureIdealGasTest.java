package com.wormzjl.icheme.science.thermo.qualification;

import static com.wormzjl.icheme.science.thermo.qualification.G3Support.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.ReferenceSpine;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.reference.AlphaDerivatives;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzFluid;
import org.junit.jupiter.api.Test;

/**
 * G3 fixture family F3 (plan {@code P3_PILOT_ENGINE_PLAN.md} sections 5 and 8.2; D7 ideal-gas row, D13): the pilot's
 * high-temperature gas for the steam-cracking coil outlet (1033-1148 K at 0.15-0.25 MPa, P0 section 5).
 *
 * <ul>
 *   <li>Ideal gas of the bundled spine records against open holdouts: the CoolProp alpha0 of N2 (Span et al. 2000) and
 *   CO2 (Span and Wagner 1996), Cp, H - H(298.15) and S at 1000, 1100 and 1200 K through the Java oracle, above the joins
 *   where the spines use NASA CEA (S anchored at the spine's own S(298.15), CEA's); the CoolProp ethane Cp (Buecker and
 *   Wagner 2006, as in {@code ReferenceSpineTest}); until batch 2026-11-04-open-thermo-data the NIST-JANAF N-023 and C-095
 *   and the WebBook Gurvich ethane tables; methane against the ExoMol MM
 *   line-list reference of WP10 at 1000, 1073, 1100, 1143 and 1200 K ({@code methane_cp_table.json}, columns
 *   {@code cp_reference} and {@code dh_reference}; nothing derived from it is bundled). D7: Cp 0.2 %,
 *   h(T) - h(298.15) 0.1 % or 20 J/mol, S within 0.02 J/(mol K).</li>
 *   <li>The residual enthalpy of the network's gas at 1073, 1100, 1143 and 1200 K and 0.15-0.25 MPa, pure and for a
 *   frozen CH4/C2H6/N2 coil-outlet mixture at 1123 K and 0.2 MPa: below 0.1 % of the sensible change h(T) - h(298.15).</li>
 *   <li>The residual against the Java oracle at each reference equation's upper range at 0.2 MPa (CH4 625 K, C2H6
 *   675 K, N2 1000 K, CO2 1100 K), where the oracle still speaks.</li>
 * </ul>
 */
class G3F3HighTemperatureIdealGasTest {
    static final double[] HOLDOUT_TEMPERATURES = {1000, 1100, 1200};

    /**
     * Holdout rows from a CoolProp fluid's ideal part (the Java oracle at delta for 1 bar): T, Cp J/(mol K), S J/(mol K)
     * anchored at {@code s298}, H - H(298.15) J/mol.
     */
    static double[][] coolPropRows(String fluidFile, double s298) {
        var fluid = HelmholtzFluid.load(fluidFile);
        var d = new AlphaDerivatives();
        double h0 = idealEnthalpy(fluid, 298.15, d), s0 = idealEntropy(fluid, 298.15, d);
        double[][] rows = new double[HOLDOUT_TEMPERATURES.length][];
        for (int i = 0; i < rows.length; i++) {
            double t = HOLDOUT_TEMPERATURES[i], tau = fluid.reducingTemperature() / t;
            fluid.idealDerivatives(tau, 1, d);
            double cp = fluid.gasConstant() * (1 - tau * tau * d.dTau2);
            rows[i] = new double[] {t, cp, s298 + idealEntropy(fluid, t, d) - s0, idealEnthalpy(fluid, t, d) - h0};
        }
        return rows;
    }
    private static double idealEnthalpy(HelmholtzFluid fluid, double t, AlphaDerivatives d) {
        double tau = fluid.reducingTemperature() / t;
        fluid.idealDerivatives(tau, 1, d);
        return fluid.gasConstant() * t * (1 + tau * d.dTau);
    }
    private static double idealEntropy(HelmholtzFluid fluid, double t, AlphaDerivatives d) {
        double tau = fluid.reducingTemperature() / t, delta = 1e5 / (fluid.gasConstant() * t * fluid.reducingDensity());
        fluid.idealDerivatives(tau, delta, d);
        return fluid.gasConstant() * (tau * d.dTau - d.alpha);
    }
    /** ExoMol MM line-list reference (P3_REFERENCES_WP8_WP10.md B.3/B.4; WP10 output): T, Cp, h(T) - h(298.15). */
    static final double[][] LINE_LIST_CH4 = {{1000, 72.796, 38469.5}, {1073, 75.710, 43891.5}, {1100, 76.725, 45949.4}, {1143, 78.271, 49282.2},
            {1200, 80.188, 53799.0}};

    static ReferenceSpine spine(String component) { return MaterialCatalog.bundled().requireSpine(PILOT, component); }

    @Test
    void idealGasAgainstTheHoldouts() {
        var table = new Table("G3 F3 ideal", "Species", "T K", "Cp spine", "Holdout", "dCp %", "dh J/mol (holdout dh)", "dS J/(mol K)", "Source");
        double worstCp = 0, worstH = 0, worstS = 0;
        for (var set : new Object[][] {{"Nitrogen", coolPropRows("Nitrogen", spine("Nitrogen").entropy(298.15, 1e5)), "CoolProp alpha0 (Span 2000)"},
                {"CarbonDioxide", coolPropRows("CarbonDioxide", spine("CarbonDioxide").entropy(298.15, 1e5)), "CoolProp alpha0 (Span-Wagner 1996)"}}) {
            var spine = spine((String) set[0]);
            for (double[] row : (double[][]) set[1]) {
                double t = row[0], cp = spine.heatCapacity(t), dh = spine.enthalpy(t) - spine.enthalpy(298.15), s = spine.entropy(t, 1e5);
                double dCp = 100 * (cp / row[1] - 1);
                table.row(set[0], number(t), f("%.4f", cp), f("%.3f", row[1]), f("%+.3f", dCp), f("%+.1f (%.0f)", dh - row[3], row[3]),
                        f("%+.4f", s - row[2]), set[2]);
                assertTrue(Math.abs(dCp) <= 0.2, set[0] + " Cp at " + t);
                assertEquals(row[3], dh, Math.max(20, 1e-3 * row[3]), set[0] + " h at " + t);
                assertEquals(row[2], s, 0.02, set[0] + " S at " + t);
                worstCp = Math.max(worstCp, Math.abs(dCp));
                worstH = Math.max(worstH, Math.abs(dh - row[3]));
                worstS = Math.max(worstS, Math.abs(s - row[2]));
            }
        }
        var ethane = spine("Ethane");
        for (double[] row : coolPropRows("Ethane", ethane.entropy(298.15, 1e5))) {
            double dCp = 100 * (ethane.heatCapacity(row[0]) / row[1] - 1);
            table.row("Ethane", number(row[0]), f("%.4f", ethane.heatCapacity(row[0])), f("%.3f", row[1]), f("%+.3f", dCp), "-", "-", "CoolProp alpha0 (Buecker-Wagner 2006)");
            assertTrue(Math.abs(dCp) <= 0.2, "C2H6 Cp at " + row[0]);
            worstCp = Math.max(worstCp, Math.abs(dCp));
        }
        var methane = spine("Methane");
        for (double[] row : LINE_LIST_CH4) {
            double t = row[0], cp = methane.heatCapacity(t), dh = methane.enthalpy(t) - methane.enthalpy(298.15);
            double dCp = 100 * (cp / row[1] - 1);
            table.row("Methane", number(t), f("%.4f", cp), f("%.3f", row[1]), f("%+.3f", dCp), f("%+.1f (%.1f)", dh - row[2], row[2]), "-",
                    "ExoMol MM line list (WP10)");
            assertTrue(Math.abs(dCp) <= 0.2, "CH4 Cp at " + t);
            assertEquals(row[2], dh, Math.max(20, 1e-3 * row[2]), "CH4 h at " + t);
            worstCp = Math.max(worstCp, Math.abs(dCp));
            worstH = Math.max(worstH, Math.abs(dh - row[2]));
        }
        // The steam-cracking temperatures between the holdout rows, for the record.
        for (String species : SPECIES) {
            var s = spine(species);
            System.out.println(f("G3 F3 spine %s: Cp(1073) %.4f, Cp(1143) %.4f J/(mol K); h(1143) - h(298.15) %.1f J/mol; range %.3f..%.1f K",
                    species, s.heatCapacity(1073), s.heatCapacity(1143), s.enthalpy(1143) - s.enthalpy(298.15), s.minimumTemperature(), s.maximumTemperature()));
        }
        table.print("ideal gas against the holdouts (D7: Cp 0.2 %, h 0.1 % or 20 J/mol, S 0.02 J/(mol K))");
        System.out.println(f("G3 F3 ideal gas worst: Cp %.3f %%, h %.1f J/mol, S %.4f J/(mol K)", worstCp, worstH, worstS));
    }

    @Test
    void residualEnthalpyAtCoilOutletPressures() {
        var model = pilot();
        var table = new Table("G3 F3 residual", "Gas", "T K", "P MPa", "h^R J/mol", "h(T) - h(298.15) J/mol", "|h^R| / sensible %", "Z (model)", "D7 0.1 %");
        double worst = 0;
        for (String species : SPECIES) {
            double[] x = pure(model, species);
            var s = spine(species);
            for (double t : new double[] {1073, 1100, 1143, 1200}) {
                for (double p : new double[] {1.5e5, 2e5, 2.5e5}) {
                    var e = evaluate(model, t, p, x, Root.VAPOR);
                    double sensible = s.enthalpy(t) - s.enthalpy(298.15), ratio = 100 * Math.abs(e.departure()) / sensible;
                    worst = Math.max(worst, ratio);
                    if (p == 2e5 || ratio > 0.05) {
                        table.row(species, number(t), f("%.2f", p / 1e6), f("%+.2f", e.departure()), f("%.1f", sensible), f("%.4f", ratio),
                                f("%.5f", p * e.molarVolume() / (R * t)), ratio <= 0.1 ? "met" : "NOT met");
                    }
                    assertTrue(ratio <= 0.1, species + " at " + t + " K, " + p + " Pa: " + ratio + " %");
                }
            }
        }
        // The frozen coil-outlet mixture through the network's own flash and state (one vapour phase).
        double[] feed = {0.1, 0.4, 0.5, 0, 0};
        var state = model.flashTP(1123, 2e5, feed, () -> { });
        double liquid = 0;
        for (double amount : state.liquidView()) liquid += amount;
        assertEquals(0, liquid, 0.0, "one gas phase");
        double[] x = {0.1, 0.4, 0.5, 0};
        var e = evaluate(model, 1123, 2e5, x, Root.VAPOR);
        double sensible = 0;
        for (int i = 0; i < 3; i++) sensible += x[i] * (spine(SPECIES[i]).enthalpy(1123) - spine(SPECIES[i]).enthalpy(298.15));
        double ratio = 100 * Math.abs(e.departure()) / sensible;
        // The network's state enthalpy (sensible datum) is the ideal-gas sum plus the residual.
        assertEquals(sensible + e.departure(), state.enthalpy(), 1e-9 * sensible, "network enthalpy = spines + residual");
        table.row("N2/CH4/C2H6 0.1/0.4/0.5 (network flash)", "1123", "0.20", f("%+.2f", e.departure()), f("%.1f", sensible), f("%.4f", ratio),
                f("%.5f", 2e5 * e.molarVolume() / (R * 1123)), ratio <= 0.1 ? "met" : "NOT met");
        assertTrue(ratio <= 0.1, "coil-outlet mixture " + ratio);
        worst = Math.max(worst, ratio);
        table.print("residual enthalpy of the gas at the steam-cracking coil outlet (rows at 0.2 MPa; 0.15 and 0.25 MPa asserted too)");
        System.out.println(f("G3 F3 residual worst |h^R| / (h(T) - h(298.15)): %.4f %% (D7 0.1 %%)", worst));
    }

    @Test
    void residualAgainstTheOracleAtEachReferenceRange() {
        var model = pilot();
        var table = new Table("G3 F3 oracle", "Species", "T K (EOS upper range)", "P MPa", "h^R model J/mol", "h^R oracle J/mol", "diff J/mol", "Z model", "Z oracle");
        double[] ranges = {1000, 625, 675, 1100};
        for (int s = 0; s < 4; s++) {
            var fluid = oracle(s);
            double t = ranges[s], p = 2e5;
            var e = evaluate(model, t, p, pure(model, SPECIES[s]), Root.VAPOR);
            var reference = fluid.stateAtPressure(t, p, HelmholtzFluid.Root.VAPOUR);
            double oracleDeparture = oracleDeparture(fluid, t, reference.enthalpy());
            table.row(SHORT[s], number(t), "0.2", f("%+.2f", e.departure()), f("%+.2f", oracleDeparture), f("%+.2f", e.departure() - oracleDeparture),
                    f("%.5f", p * e.molarVolume() / (R * t)), f("%.5f", reference.compressibility()));
            // Both residuals are a few J/mol against tens of kJ/mol of sensible change; they agree to a few J/mol.
            assertTrue(Math.abs(e.departure() - oracleDeparture) <= 10, SHORT[s] + " residual against the oracle");
        }
        table.print("the gas residual against the Java oracle at each reference equation's published upper range, 0.2 MPa");
    }

    /**
     * The hot dilute gas is vapour-like and takes the network's vapour slot: every pilot species and the coil-outlet
     * mixture at 1073-1200 K and 0.15-0.25 MPa through the network's own flash (the engine's phase label). Above its Boyle
     * temperature a gas has a positive second virial coefficient and the phase identification parameter creeps just above
     * one (N2 1.0004 at 1073 K); the label must still be vapour. At {@code 8e5274e} N2, CH4 above about 1000 K and CO2 at
     * 1200 K took the liquid slot; P3 WP7c ({@code f2d5221}: liquid-like only with PIP above one and above Z) fixed it.
     */
    @Test
    void coilOutletGasTakesTheVapourSlot() {
        var model = pilot();
        var wrong = new java.util.ArrayList<String>();
        double[][] feeds = {{1, 0, 0, 0, 0}, {0, 1, 0, 0, 0}, {0, 0, 1, 0, 0}, {0, 0, 0, 1, 0}, {0.1, 0.4, 0.5, 0, 0}};
        String[] names = {"N2", "CH4", "C2H6", "CO2", "N2/CH4/C2H6 0.1/0.4/0.5"};
        int states = 0;
        for (int k = 0; k < feeds.length; k++) {
            for (double t : new double[] {1073, 1143, 1200}) {
                for (double p : new double[] {1.5e5, 2e5, 2.5e5}) {
                    var state = model.flashTP(t, p, feeds[k], () -> { });
                    double liquid = 0;
                    for (double amount : state.liquidView()) liquid += amount;
                    states++;
                    if (liquid != 0) wrong.add(f("%s %.0f K %.2f MPa", names[k], t, p / 1e6));
                }
            }
        }
        System.out.println(f("G3 F3 coil-outlet gas in the vapour slot: %d of %d states%s", states - wrong.size(), states,
                wrong.isEmpty() ? "" : "; in the liquid slot: " + wrong));
        assertEquals(java.util.List.of(), wrong, "hot dilute gas is vapour-like");
    }
}
