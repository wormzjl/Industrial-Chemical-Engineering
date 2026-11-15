package com.wormzjl.icheme.science.thermo.qualification;

import static com.wormzjl.icheme.science.thermo.qualification.G3Support.f;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.HydrocarbonValues;
import com.wormzjl.icheme.science.material.CoefficientTranslations;
import com.wormzjl.icheme.science.material.DerivedPairs;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.TranslatedCubic;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PackageBinding;
import com.wormzjl.icheme.science.thermo.phase.PhaseAmounts;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseKind;
import com.wormzjl.icheme.science.thermo.phase.PhaseState;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzFluid;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzState;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * G3 fixture family F11 (batch 2026-11-15-supercritical-fluids, stage S0): the supercritical region of the bundled
 * network model ({@link G3Support#bundledNetwork}, the game's catalog and network package on the derived cubic), the
 * measurements the owner decisions O3, O5, O6, O8 and O9 of {@code SUPERCRITICAL_PLAN.md} rest on. A qualification
 * record, not a gate on a target: it asserts only that every model value is finite, that no engine answer is
 * {@code INTERNAL}, and that each summary's worst value stays within the bound it prints (declared from the first run,
 * rounded up to the next whole unit: {@link #DECLARED}).
 *
 * <ul>
 *   <li>M1: pure fluids (CO2, N2, CH4, C2H6, H2, C3H8, C2H4) against the Helmholtz oracle on Tr 1.02-2 x (Pr 0.5-4 and
 *   10, 20, 30 MPa), reduced by the oracle's critical constants; the band box by the model's own (the engine's). Density,
 *   enthalpy departure (J/mol and as kelvin of the reference cp), cp and the isothermal acoustic bound
 *   {@code sqrt((dP/drho)_T / M)}. Water at 650-900 K and 22.1-30 MPa on the model's wet list (plan S6 sizing).</li>
 *   <li>M2: the cp step at Tc of each Mathias-Copeman compound (review A6, decision O6) against the algebra
 *   {@code a_c c2 / Tc^2}.</li>
 *   <li>M3: the gap of a merged split (decision O3 A) on four binaries at the first pressure of an isotherm where the
 *   tie line {@code sum z (ln K)^2} falls below {@link FluidTpEquilibrium#BAND_TIE_LINE}.</li>
 *   <li>M4: viscosity (decision O5): the model's dilute viscosity, and dilute plus the Jossi-Stiel-Thodos residual in
 *   the Lohrenz-Bray-Clark statement, against CoolProp ({@code viscosity-reference.json}).</li>
 *   <li>M5: surrogates of the crude cuts pc01 to pc04 (data review P2): PR78 on each reference fluid's own constants with
 *   the production translation rule, against the oracle; and the bundled n-hexane and toluene datasets.</li>
 *   <li>M6: the pairs of the crude cuts with five light gases (decision O8, a sensitivity) and the pairs no library names
 *   (decision O9).</li>
 *   <li>M7: three binaries against CoolProp's mixture model ({@code mixture-density-reference.json}).</li>
 *   <li>M8: compressed liquids at 2-30 MPa on the model's liquid root against the oracle, judged by G3F1's liquid
 *   bounds (follow-up of S0).</li>
 * </ul>
 *
 * <p>Enthalpies are compared as departures {@code h(T, P) - h(T, 1 Pa)} (model, vapour root) and {@code h - h(T, rho -> 0)}
 * (oracle), so the two data do not meet. Above Tc the oracle has one root and the model is asked on its vapour root.</p>
 */
class G3F11SupercriticalRegionTest {
    static final String PACKAGE = FluidMaterialCatalog.NETWORK_PACKAGE;
    static final double R = FluidThermodynamics.R;
    static final double[] TR = {1.02, 1.05, 1.1, 1.15, 1.2, 1.3, 1.5, 2.0};
    static final double[] PR = {0.5, 0.8, 1, 1.2, 1.5, 2, 3, 4};
    static final double[] P_ABSOLUTE = {10e6, 20e6, 30e6};
    /** Ambient and hot isotherms where they lie above Tr 2 (nitrogen, hydrogen, methane at the network's usual temperatures). */
    static final double[] T_ABSOLUTE = {300, 500, 700};
    static final double MAX_T = 900;
    /** The declared errors of G3 (supercritical density 7 %, cp 12 %): the bounds of the recommended pressure range. */
    static final double DENSITY_BOUND = 7, CP_BOUND = 12;

    /** Oracle file, bundled component id, short label. */
    record Fluid(String file, String id, String label) {}

    static final List<Fluid> M1 = List.of(new Fluid("CarbonDioxide", "CarbonDioxide", "CO2"), new Fluid("Nitrogen", "Nitrogen", "N2"),
            new Fluid("Methane", "Methane", "CH4"), new Fluid("Ethane", "Ethane", "C2H6"), new Fluid("Hydrogen", "Hydrogen", "H2"),
            new Fluid("n-Propane", "Propane", "C3H8"), new Fluid("Ethylene", "Ethylene", "C2H4"));

    /**
     * The bound each summary holds (percent, or the unit its key names), from the first run (2026-11-15) rounded up to the
     * next whole unit. A summary without an entry is reported and fails, so a new summary is declared before it holds.
     */
    static final Map<String, Double> DECLARED = declared();

    private static Map<String, Double> declared() {
        Map<String, Double> m = new HashMap<>();
        // Run of 2026-11-15 (S0), |worst| rounded up to the next whole unit.
        m.put("M2 Water Pr 0.5 step J/(mol K)", 1.0);
        m.put("M2 Water Pr 1 step J/(mol K)", 4.0);
        m.put("M2 Water Pr 2 step J/(mol K)", 6.0);
        m.put("M4 dilute %", 88.0);
        m.put("M4 JST reference rho_c %", 16.0);
        m.put("M4 JST cubic v_c %", 29.0);
        m.put("M5 n-Hexane density %", 6.0);
        m.put("M5 n-Hexane residual cp %", 3.0);
        m.put("M5 n-Heptane density %", 6.0);
        m.put("M5 n-Heptane residual cp %", 3.0);
        m.put("M5 Toluene density %", 6.0);
        m.put("M5 Toluene residual cp %", 5.0);
        m.put("M5 n-Decane density %", 8.0);
        m.put("M5 n-Decane residual cp %", 3.0);
        m.put("M5 n-Dodecane density %", 10.0);
        m.put("M5 n-Dodecane residual cp %", 2.0);
        m.put("M5 bundled N-hexane density %", 8.0);
        m.put("M5 bundled N-hexane cp %", 6.0);
        m.put("M5 bundled Toluene density %", 10.0);
        m.put("M5 bundled Toluene cp %", 6.0);
        m.put("M1 H2O density %", 30.0);
        m.put("M1 H2O cp %", 37.0);
        m.put("M3 one-fluid density %", 17.0);
        m.put("M3 one-fluid enthalpy J/mol", 354.0);
        m.put("M1 CO2 outside the band density %", 6.0);
        m.put("M1 CO2 outside the band cp %", 11.0);
        m.put("M1 CO2 outside the band sound %", 14.0);
        m.put("M1 CO2 outside the band h K", 4.0);
        m.put("M1 CO2 inside the band density %", 13.0);
        m.put("M1 CO2 inside the band cp %", 21.0);
        m.put("M1 CO2 inside the band sound %", 20.0);
        m.put("M1 CO2 inside the band h K", 3.0);
        m.put("M1 N2 outside the band density %", 4.0);
        m.put("M1 N2 outside the band cp %", 7.0);
        m.put("M1 N2 outside the band sound %", 11.0);
        m.put("M1 N2 outside the band h K", 2.0);
        m.put("M1 N2 inside the band density %", 11.0);
        m.put("M1 N2 inside the band cp %", 14.0);
        m.put("M1 N2 inside the band sound %", 11.0);
        m.put("M1 N2 inside the band h K", 2.0);
        m.put("M1 CH4 outside the band density %", 5.0);
        m.put("M1 CH4 outside the band cp %", 10.0);
        m.put("M1 CH4 outside the band sound %", 12.0);
        m.put("M1 CH4 outside the band h K", 3.0);
        m.put("M1 CH4 inside the band density %", 12.0);
        m.put("M1 CH4 inside the band cp %", 13.0);
        m.put("M1 CH4 inside the band sound %", 12.0);
        m.put("M1 CH4 inside the band h K", 3.0);
        m.put("M1 C2H6 outside the band density %", 6.0);
        m.put("M1 C2H6 outside the band cp %", 12.0);
        m.put("M1 C2H6 outside the band sound %", 14.0);
        m.put("M1 C2H6 outside the band h K", 3.0);
        m.put("M1 C2H6 inside the band density %", 13.0);
        m.put("M1 C2H6 inside the band cp %", 10.0);
        m.put("M1 C2H6 inside the band sound %", 10.0);
        m.put("M1 C2H6 inside the band h K", 4.0);
        m.put("M1 H2 outside the band density %", 9.0);
        m.put("M1 H2 outside the band cp %", 29.0);
        m.put("M1 H2 outside the band sound %", 31.0);
        m.put("M1 H2 outside the band h K", 5.0);
        m.put("M1 H2 inside the band density %", 11.0);
        m.put("M1 H2 inside the band cp %", 20.0);
        m.put("M1 H2 inside the band sound %", 13.0);
        m.put("M1 H2 inside the band h K", 2.0);
        m.put("M1 C3H8 outside the band density %", 6.0);
        m.put("M1 C3H8 outside the band cp %", 9.0);
        m.put("M1 C3H8 outside the band sound %", 15.0);
        m.put("M1 C3H8 outside the band h K", 3.0);
        m.put("M1 C3H8 inside the band density %", 13.0);
        m.put("M1 C3H8 inside the band cp %", 9.0);
        m.put("M1 C3H8 inside the band sound %", 12.0);
        m.put("M1 C3H8 inside the band h K", 4.0);
        m.put("M1 C2H4 outside the band density %", 9.0);
        m.put("M1 C2H4 outside the band cp %", 13.0);
        m.put("M1 C2H4 outside the band sound %", 11.0);
        m.put("M1 C2H4 outside the band h K", 3.0);
        m.put("M1 C2H4 inside the band density %", 14.0);
        m.put("M1 C2H4 inside the band cp %", 11.0);
        m.put("M1 C2H4 inside the band sound %", 11.0);
        m.put("M1 C2H4 inside the band h K", 4.0);
        m.put("M7 density %", 5.0);
        // Run of 2026-11-15 (M8, compressed liquids), |worst| rounded up to the next whole unit.
        m.put("M8 CO2 Tr <= 0.8 density %", 2.0);
        m.put("M8 CO2 Tr <= 0.8 cp %", 8.0);
        m.put("M8 CO2 Tr <= 0.8 h K", 1.0);
        m.put("M8 CO2 Tr 0.85 density %", 2.0);
        m.put("M8 CO2 Tr 0.85 cp %", 6.0);
        m.put("M8 CO2 Tr 0.85 h K", 1.0);
        m.put("M8 CO2 Tr 0.9 density %", 4.0);
        m.put("M8 CO2 Tr 0.9 cp %", 11.0);
        m.put("M8 CO2 Tr 0.9 h K", 2.0);
        m.put("M8 CO2 Tr 0.95 density %", 5.0);
        m.put("M8 CO2 Tr 0.95 cp %", 11.0);
        m.put("M8 CO2 Tr 0.95 h K", 2.0);
        m.put("M8 N2 Tr <= 0.8 density %", 1.0);
        m.put("M8 N2 Tr <= 0.8 cp %", 9.0);
        m.put("M8 N2 Tr <= 0.8 h K", 3.0);
        m.put("M8 N2 Tr 0.85 density %", 3.0);
        m.put("M8 N2 Tr 0.85 cp %", 8.0);
        m.put("M8 N2 Tr 0.85 h K", 1.0);
        m.put("M8 N2 Tr 0.9 density %", 5.0);
        m.put("M8 N2 Tr 0.9 cp %", 16.0);
        m.put("M8 N2 Tr 0.9 h K", 2.0);
        m.put("M8 N2 Tr 0.95 density %", 4.0);
        m.put("M8 N2 Tr 0.95 cp %", 10.0);
        m.put("M8 N2 Tr 0.95 h K", 2.0);
        m.put("M8 C3H8 Tr <= 0.8 density %", 3.0);
        m.put("M8 C3H8 Tr <= 0.8 cp %", 4.0);
        m.put("M8 C3H8 Tr <= 0.8 h K", 1.0);
        m.put("M8 C3H8 Tr 0.85 density %", 3.0);
        m.put("M8 C3H8 Tr 0.85 cp %", 7.0);
        m.put("M8 C3H8 Tr 0.85 h K", 2.0);
        m.put("M8 C3H8 Tr 0.9 density %", 3.0);
        m.put("M8 C3H8 Tr 0.9 cp %", 7.0);
        m.put("M8 C3H8 Tr 0.9 h K", 2.0);
        m.put("M8 C3H8 Tr 0.95 density %", 6.0);
        m.put("M8 C3H8 Tr 0.95 cp %", 11.0);
        m.put("M8 C3H8 Tr 0.95 h K", 3.0);
        m.put("M8 C2H4 Tr <= 0.8 density %", 4.0);
        m.put("M8 C2H4 Tr <= 0.8 cp %", 14.0);
        m.put("M8 C2H4 Tr <= 0.8 h K", 8.0);
        m.put("M8 C2H4 Tr 0.85 density %", 5.0);
        m.put("M8 C2H4 Tr 0.85 cp %", 9.0);
        m.put("M8 C2H4 Tr 0.85 h K", 2.0);
        m.put("M8 C2H4 Tr 0.9 density %", 5.0);
        m.put("M8 C2H4 Tr 0.9 cp %", 10.0);
        m.put("M8 C2H4 Tr 0.9 h K", 2.0);
        m.put("M8 C2H4 Tr 0.95 density %", 9.0);
        m.put("M8 C2H4 Tr 0.95 cp %", 17.0);
        m.put("M8 C2H4 Tr 0.95 h K", 4.0);
        m.put("M8 n-C6 Tr <= 0.8 density %", 3.0);
        m.put("M8 n-C6 Tr <= 0.8 cp %", 8.0);
        m.put("M8 n-C6 Tr <= 0.8 h K", 8.0);
        m.put("M8 n-C6 Tr 0.85 density %", 4.0);
        m.put("M8 n-C6 Tr 0.85 cp %", 3.0);
        m.put("M8 n-C6 Tr 0.85 h K", 1.0);
        m.put("M8 n-C6 Tr 0.9 density %", 6.0);
        m.put("M8 n-C6 Tr 0.9 cp %", 6.0);
        m.put("M8 n-C6 Tr 0.9 h K", 2.0);
        m.put("M8 n-C6 Tr 0.95 density %", 5.0);
        m.put("M8 n-C6 Tr 0.95 cp %", 4.0);
        m.put("M8 n-C6 Tr 0.95 h K", 2.0);
        m.put("M8 Toluene Tr <= 0.8 density %", 3.0);
        m.put("M8 Toluene Tr <= 0.8 cp %", 9.0);
        m.put("M8 Toluene Tr <= 0.8 h K", 8.0);
        m.put("M8 Toluene Tr 0.85 density %", 4.0);
        m.put("M8 Toluene Tr 0.85 cp %", 4.0);
        m.put("M8 Toluene Tr 0.85 h K", 3.0);
        m.put("M8 Toluene Tr 0.9 density %", 7.0);
        m.put("M8 Toluene Tr 0.9 cp %", 8.0);
        m.put("M8 Toluene Tr 0.9 h K", 3.0);
        m.put("M8 Toluene Tr 0.95 density %", 7.0);
        m.put("M8 Toluene Tr 0.95 cp %", 7.0);
        m.put("M8 Toluene Tr 0.95 h K", 3.0);
        return m;
    }

    private final List<String> undeclared = new ArrayList<>();
    private final List<String> beyond = new ArrayList<>();

    /** Holds a summary's worst value against its declared bound (see {@link #DECLARED}). */
    private void hold(String key, double worst) {
        assertTrue(Double.isFinite(worst), key + " worst is not finite");
        Double bound = DECLARED.get(key);
        String line;
        if (bound == null) {
            undeclared.add(f("\"%s\", %s", key, G3Support.number(Math.ceil(Math.abs(worst)))));
            line = "UNDECLARED";
        } else {
            line = f("declared %s: %s", G3Support.number(bound), Math.abs(worst) <= bound ? "held" : "BEYOND");
            if (Math.abs(worst) > bound) beyond.add(key + " " + f("%.3f", worst) + " beyond " + bound);
        }
        System.out.println(f("G3 F11 bound | %s | worst %.3f | %s", key, worst, line));
    }

    private void assertHeld() {
        undeclared.forEach(u -> System.out.println("G3 F11 UNDECLARED " + u));
        assertEquals(List.of(), undeclared, "undeclared summaries");
        assertEquals(List.of(), beyond, "summaries beyond their bound");
    }

    // ------------------------------------------------------------------------------------------- fluid files

    private static final Map<String, JsonObject> FILES = new HashMap<>();

    /** A bundled CoolProp fluid file, read as data (molar mass, p_max, the acentric factor). */
    static synchronized JsonObject file(String name) {
        return FILES.computeIfAbsent(name, n -> {
            String path = "/science/thermo/coolprop/" + n + ".json";
            try (InputStream stream = G3F11SupercriticalRegionTest.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalArgumentException("missing test resource " + path);
                return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    static JsonObject eos(String name) { return file(name).getAsJsonArray("EOS").get(0).getAsJsonObject(); }

    static double molarMass(String name) { return eos(name).get("molar_mass").getAsDouble(); }

    static double maximumPressure(String name) { return eos(name).get("p_max").getAsDouble(); }

    static double acentric(String name) { return eos(name).get("acentric").getAsDouble(); }

    // ------------------------------------------------------------------------------------------- statistics

    /** Absolute deviations of one summary: the worst signed value with its state, and the 95th percentile of |dev|. */
    static final class Stat {
        final List<Double> values = new ArrayList<>();
        double worst;
        String at = "-";

        void add(double deviation, String where) {
            assertTrue(Double.isFinite(deviation), "deviation not finite at " + where);
            values.add(Math.abs(deviation));
            if (Math.abs(deviation) > Math.abs(worst) || at.equals("-")) {
                worst = deviation;
                at = where;
            }
        }

        double p95() {
            if (values.isEmpty()) return 0;
            var sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            return sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
        }

        String text(String format) { return values.isEmpty() ? "-" : f(format, worst) + " / " + f(format, p95()) + " (" + at + ")"; }
    }

    /** The four quantities of a pure-fluid summary. */
    static final class Quad {
        final Stat density = new Stat(), cp = new Stat(), sound = new Stat(), kelvin = new Stat(), joules = new Stat();
    }

    // -------------------------------------------------------------------------------------------- the model

    /** One phase of the network's own equation of state at (T, P): volume, departure, cp, (dP/drho)_T molar, Z, roots. */
    record Point(double molarVolume, double departure, double heatCapacity, double pressureDensityDerivative, double z, int roots) {
        double density() { return 1 / molarVolume; }
    }

    static Point point(FluidThermodynamics model, double t, double p, double[] conserved, Root root) {
        var v = HydrocarbonValues.of(model.hydrocarbon, t, p, conserved, root);
        var zero = HydrocarbonValues.of(model.hydrocarbon, t, 1.0, conserved, Root.VAPOR);
        double volume = v.molarVolume();
        double dPdRho = -volume * volume / v.volumePressureDerivative();
        return new Point(volume, v.molarEnthalpy() - zero.molarEnthalpy(), v.heatCapacity(), dPdRho, p * volume / (R * t),
                v.physicalRootCount());
    }

    /** The conserved index of a component (water last). */
    static int index(FluidThermodynamics model, String id) {
        if (id.equals("Water")) return model.hydrocarbon.componentCount();
        int i = model.hydrocarbon.components().indexOf(id);
        if (i < 0) throw new IllegalArgumentException(id + " not in the network model");
        return i;
    }

    static double[] pureConserved(FluidThermodynamics model, String id) {
        double[] x = new double[model.hydrocarbon.componentCount() + 1];
        x[index(model, id)] = 1;
        return x;
    }

    /** The model's (the engine's derived) critical temperature and pressure of a component. */
    static double[] modelCritical(FluidThermodynamics model, String id) {
        var derivation = model.hydrocarbon.evaluator().derivation();
        int k = derivation.components().indexOf(id);
        return new double[] {derivation.criticalTemperatures()[k], derivation.criticalPressures()[k]};
    }

    /** The oracle's supercritical state: one root above Tc. */
    static HelmholtzState reference(HelmholtzFluid fluid, double t, double p) {
        return fluid.stateAtPressure(t, p, HelmholtzFluid.Root.VAPOUR);
    }

    static double referenceDeparture(HelmholtzFluid fluid, double t, double enthalpy) {
        return enthalpy - fluid.state(t, 1e-10).enthalpy();
    }

    /**
     * The grid temperatures and pressures of one oracle (the rows of source {@code supercritical} in its parity fixture),
     * bounded by its maximum T and P and by 900 K.
     */
    static List<double[]> grid(HelmholtzFluid fluid, String file) {
        List<double[]> states = new ArrayList<>();
        double tc = fluid.criticalTemperature(), pc = fluid.criticalPressure();
        double tMax = Math.min(fluid.maximumTemperature(), MAX_T), pMax = maximumPressure(file);
        List<Double> temperatures = new ArrayList<>();
        for (double tr : TR) temperatures.add(tr * tc);
        for (double t : T_ABSOLUTE) if (t > TR[TR.length - 1] * tc) temperatures.add(t);
        for (double t : temperatures) {
            if (t > tMax) continue;
            List<Double> pressures = new ArrayList<>();
            for (double pr : PR) pressures.add(pr * pc);
            for (double p : P_ABSOLUTE) pressures.add(p);
            for (double p : pressures) if (p <= pMax) states.add(new double[] {t, p});
        }
        return states;
    }

    // ------------------------------------------------------------------------------------------------- M1

    @Test
    void m1PureFluidsAgainstTheOracle() {
        var model = G3Support.bundledNetwork();
        var summary = new G3Support.Table("G3 F11 M1", "Fluid", "Region", "n", "rho % worst / p95", "cp % worst / p95",
                "sound % worst / p95", "h K worst / p95", "h J/mol worst / p95");
        var ranges = new G3Support.Table("G3 F11 M1 range", "Fluid", "Highest P (MPa) with rho <= 7 % and cp <= 12 % outside the band",
                "First state beyond (outside the band)", "Same at T >= 273.15 K", "First state beyond at T >= 273.15 K");
        for (Fluid fl : M1) {
            var fluid = G3Support.oracle(fl.file());
            double[] x = pureConserved(model, fl.id());
            int i = index(model, fl.id());
            double molar = model.hydrocarbon.molecularWeight(i), referenceMolar = molarMass(fl.file());
            double[] critical = modelCritical(model, fl.id());
            Quad outside = new Quad(), inside = new Quad();
            var beyondStates = new TreeMap<Double, String>();
            var beyondWarm = new TreeMap<Double, String>();
            List<Double> pressures = new ArrayList<>(), warmPressures = new ArrayList<>();
            var rows = new G3Support.Table("G3 F11 M1 " + fl.label(), "T K", "P MPa", "Tr", "Pr", "box", "Z ref", "rho %", "h J/mol", "h K", "cp %", "sound %");
            for (double[] state : grid(fluid, fl.file())) {
                double t = state[0], p = state[1];
                var ref = reference(fluid, t, p);
                var e = point(model, t, p, x, Root.VAPOR);
                double rho = 100 * (e.density() / ref.density() - 1);
                double cp = 100 * (e.heatCapacity() / ref.isobaricHeatCapacity() - 1);
                double dh = e.departure() - referenceDeparture(fluid, t, ref.enthalpy());
                double kelvin = dh / ref.isobaricHeatCapacity();
                double sound = 100 * (Math.sqrt(e.pressureDensityDerivative() / molar) / Math.sqrt(ref.pressureDensityDerivative() / referenceMolar) - 1);
                double tr = t / fluid.criticalTemperature(), pr = p / fluid.criticalPressure();
                int box = FluidTpEquilibrium.pureBandBox(t / critical[0], p / critical[1]);
                String at = f("%.1f K %.2f MPa", t, p / 1e6);
                Quad q = box == 0 ? outside : inside;
                q.density.add(rho, at);
                q.cp.add(cp, at);
                q.sound.add(sound, at);
                q.kelvin.add(kelvin, at);
                q.joules.add(dh, at);
                assertEquals(1, e.roots(), fl.label() + " one root above Tc at " + at);
                if (box == 0) {
                    boolean beyondBound = Math.abs(rho) > DENSITY_BOUND || Math.abs(cp) > CP_BOUND;
                    pressures.add(p);
                    if (beyondBound) beyondStates.merge(p, at + f(" rho %+.2f %% cp %+.2f %%", rho, cp), (a, b) -> a);
                    if (t >= 273.15) {
                        warmPressures.add(p);
                        if (beyondBound) beyondWarm.merge(p, at + f(" rho %+.2f %% cp %+.2f %%", rho, cp), (a, b) -> a);
                    }
                }
                rows.row(f("%.2f", t), f("%.3f", p / 1e6), f("%.2f", tr), f("%.2f", pr), box, f("%.3f", ref.compressibility()),
                        f("%+.2f", rho), f("%+.0f", dh), f("%+.2f", kelvin), f("%+.2f", cp), f("%+.2f", sound));
            }
            rows.print(fl.label() + ": bundled model against the oracle (" + fl.file() + "), supercritical grid");
            summarise(summary, fl.label(), "outside the band", outside);
            summarise(summary, fl.label(), "inside the band", inside);
            // The recommended range: the highest grid pressure below the first pressure beyond either bound.
            ranges.row(fl.label(), f("%.2f", highestBelow(pressures, beyondStates) / 1e6),
                    beyondStates.isEmpty() ? "none to the grid's top" : beyondStates.firstEntry().getValue(),
                    warmPressures.isEmpty() ? "-" : f("%.2f", highestBelow(warmPressures, beyondWarm) / 1e6),
                    warmPressures.isEmpty() ? "no state" : beyondWarm.isEmpty() ? "none to the grid's top" : beyondWarm.firstEntry().getValue());
        }
        summary.print("summaries, worst and 95th percentile (band boxes by the model's own critical constants)");
        ranges.print("recommended pressure_max_pascal per dataset from the supercritical grid");
        assertHeld();
    }

    /** The highest pressure of {@code pressures} below the lowest pressure of a state beyond the bounds. */
    static double highestBelow(List<Double> pressures, TreeMap<Double, String> beyondStates) {
        double first = beyondStates.isEmpty() ? Double.POSITIVE_INFINITY : beyondStates.firstKey(), highest = 0;
        for (double p : pressures) if (p < first && p > highest) highest = p;
        return highest;
    }

    private void summarise(G3Support.Table table, String label, String region, Quad q) {
        if (q.density.values.isEmpty()) {
            table.row(label, region, 0, "-", "-", "-", "-", "-");
            return;
        }
        table.row(label, region, q.density.values.size(), q.density.text("%+.2f"), q.cp.text("%+.2f"), q.sound.text("%+.2f"),
                q.kelvin.text("%+.2f"), q.joules.text("%+.0f"));
        String key = "M1 " + label + " " + region;
        hold(key + " density %", q.density.worst);
        hold(key + " cp %", q.cp.worst);
        hold(key + " sound %", q.sound.worst);
        hold(key + " h K", q.kelvin.worst);
    }

    /** Water above its critical point on the model's wet list (the bare cubic: water's share fades to zero at Tc), plan S6. */
    @Test
    void m1WaterAboveItsCriticalPoint() {
        var model = G3Support.bundledNetwork();
        var fluid = G3Support.oracle("Water");
        double[] x = pureConserved(model, "Water");
        double molar = model.hydrocarbon.molecularWeight(index(model, "Water")), referenceMolar = molarMass("Water");
        var rows = new G3Support.Table("G3 F11 M1 H2O", "T K", "P MPa", "Z ref", "rho %", "h J/mol", "h K", "cp %", "sound %");
        Quad q = new Quad();
        for (double t : new double[] {650, 700, 800, 900}) {
            for (double p : new double[] {22.1e6, 25e6, 30e6}) {
                var ref = reference(fluid, t, p);
                var e = point(model, t, p, x, Root.VAPOR);
                double rho = 100 * (e.density() / ref.density() - 1);
                double cp = 100 * (e.heatCapacity() / ref.isobaricHeatCapacity() - 1);
                double dh = e.departure() - referenceDeparture(fluid, t, ref.enthalpy());
                double sound = 100 * (Math.sqrt(e.pressureDensityDerivative() / molar) / Math.sqrt(ref.pressureDensityDerivative() / referenceMolar) - 1);
                String at = f("%.0f K %.1f MPa", t, p / 1e6);
                q.density.add(rho, at);
                q.cp.add(cp, at);
                q.sound.add(sound, at);
                q.kelvin.add(dh / ref.isobaricHeatCapacity(), at);
                q.joules.add(dh, at);
                rows.row(f("%.0f", t), f("%.1f", p / 1e6), f("%.3f", ref.compressibility()), f("%+.2f", rho), f("%+.0f", dh),
                        f("%+.2f", dh / ref.isobaricHeatCapacity()), f("%+.2f", cp), f("%+.2f", sound));
            }
        }
        rows.print("water on the bundled model's wet list against IAPWS-95 (oracle Water), 650-900 K, 22.1-30 MPa");
        System.out.println(f("G3 F11 M1 H2O summary | rho %s | cp %s | sound %s | h K %s", q.density.text("%+.2f"), q.cp.text("%+.2f"),
                q.sound.text("%+.2f"), q.kelvin.text("%+.2f")));
        hold("M1 H2O density %", q.density.worst);
        hold("M1 H2O cp %", q.cp.worst);
        assertHeld();
    }

    // ------------------------------------------------------------------------------------------------- M2

    @Test
    void m2MathiasCopemanCpStepAtTc() {
        var model = G3Support.bundledNetwork();
        var derivation = model.hydrocarbon.evaluator().derivation();
        double[][] alpha = derivation.mathiasCopeman();
        var table = new G3Support.Table("G3 F11 M2", "Compound", "c1, c2, c3", "Pr", "cp(Tc-1e-3) J/(mol K)", "cp(Tc+1e-3)",
                "raw step", "step extrapolated to Tc", "step %", "algebra T da''/(2 sqrt2 b) ln(..)", "a_c c2/Tc^2 Pa m6/(mol2 K2)");
        int compounds = 0;
        for (int k = 0; k < alpha.length; k++) {
            if (alpha[k] == null || alpha[k][1] == 0 && alpha[k][2] == 0) continue;
            compounds++;
            String id = derivation.components().get(k);
            double tc = derivation.criticalTemperatures()[k], pc = derivation.criticalPressures()[k];
            double ac = 0.45724 * R * R * tc * tc / pc;
            double stepA = -ac * alpha[k][1] / (tc * tc);   // d2a/dT2 above minus below at Tc
            double[] x = pureConserved(model, id);
            for (double pr : new double[] {0.5, 1, 2}) {
                double p = pr * pc;
                double below1 = cpLower(model, tc - 1e-3, p, x), below2 = cpLower(model, tc - 2e-3, p, x);
                double above1 = cpLower(model, tc + 1e-3, p, x), above2 = cpLower(model, tc + 2e-3, p, x);
                double raw = above1 - below1;
                double extrapolated = (2 * above1 - above2) - (2 * below1 - below2);
                var at = HydrocarbonValues.of(model.hydrocarbon, tc + 1e-3, p, x, Root.VAPOR);
                double b = at.coVolume(), v = at.untranslatedVolume();
                double algebra = tc * stepA / (2 * Math.sqrt(2) * b) * Math.log((v + (1 + Math.sqrt(2)) * b) / (v + (1 - Math.sqrt(2)) * b));
                table.row(id, f("%.5f, %.5f, %.5f", alpha[k][0], alpha[k][1], alpha[k][2]), G3Support.number(pr), f("%.3f", below1),
                        f("%.3f", above1), f("%+.3f", raw), f("%+.3f", extrapolated), f("%+.2f", 100 * extrapolated / below1),
                        f("%+.3f", algebra), f("%.4g", ac * alpha[k][1] / (tc * tc)));
                // At Pr 1 the state is the cubic's own critical point, where cp diverges: the extrapolation over 1e-3 K reads the
                // divergence, not the step, so the algebra's step is held there.
                hold("M2 " + id + " Pr " + G3Support.number(pr) + " step J/(mol K)", pr == 1 ? algebra : extrapolated);
            }
        }
        table.print("the cp step at Tc of each Mathias-Copeman compound of the network model (" + compounds + "), review A6");
        assertTrue(compounds > 0, "no Mathias-Copeman compound in the network model");
        assertHeld();
    }

    /** cp of a pure component on its lower-Gibbs root. */
    private static double cpLower(FluidThermodynamics model, double t, double p, double[] x) {
        int i = 0;
        while (x[i] == 0) i++;
        var liquid = HydrocarbonValues.of(model.hydrocarbon, t, p, x, Root.LIQUID);
        double liquidPhi = liquid.logFugacityCoefficientsView()[i], liquidCp = liquid.heatCapacity();
        var vapour = HydrocarbonValues.of(model.hydrocarbon, t, p, x, Root.VAPOR);
        double cp = liquidPhi <= vapour.logFugacityCoefficientsView()[i] ? liquidCp : vapour.heatCapacity();
        assertTrue(Double.isFinite(cp), "cp at " + t + " K");
        return cp;
    }

    // ------------------------------------------------------------------------------------------------- M3

    /** A classified TP answer of a binary: two fluid phases with the tie line, one phase, or a failure. */
    record Answer(EquilibriumResult result, PhaseState light, PhaseState heavy, double lightFraction, double tieLine) {
        boolean split() { return light != null; }
    }

    /**
     * The network's own engine: the model's evaluator on its derived contract, as {@code NetworkPhaseEngine.of} builds it
     * (fluid-only requests need no crystal evaluator). {@code PackageBinding.equilibrium} of the bundled catalog would take
     * the dry path: the network package lists no water; the network adds it ({@code MaterialCatalog.withWater}).
     */
    static FluidTpEquilibrium networkEngine(FluidThermodynamics model) {
        var evaluator = model.hydrocarbon.evaluator();
        return new FluidTpEquilibrium(PackageBinding.derivedContract(model.catalog(), PACKAGE, evaluator), evaluator,
                FluidTpEquilibrium.Settings.DEFAULT);
    }

    static Answer answer(FluidTpEquilibrium engine, FluidTpEquilibrium.Workspace workspace, double t, double p, double[] z) {
        var basis = engine.evaluator().components();
        var result = engine.tp(EquilibriumRequest.tp(t, p, basis, z, PhaseCompetition.FLUID_ONLY), workspace);
        assertTrue(result.failureKind() != EquilibriumResult.FailureKind.INTERNAL, "INTERNAL at " + t + " K " + p + " Pa: " + result.detail());
        if (!result.converged()) return new Answer(result, null, null, Double.NaN, Double.NaN);
        List<PhaseAmounts> fluid = result.phases().stream().filter(a -> a.kind() != PhaseKind.SOLID).toList();
        if (fluid.size() != 2) return new Answer(result, null, null, Double.NaN, Double.NaN);
        var light = fluid.get(0).state();
        var heavy = fluid.get(1).state();
        double total = 0, tie = 0;
        for (double amount : z) total += amount;
        for (int i = 0; i < z.length; i++) {
            if (z[i] <= 0) continue;
            double lnK = Math.log(light.moleFraction(i) / heavy.moleFraction(i));
            tie += z[i] / total * lnK * lnK;
        }
        double fraction = fluid.get(0).total() / (fluid.get(0).total() + fluid.get(1).total());
        return new Answer(result, light, heavy, fraction, tie);
    }

    @Test
    void m3GapOfAMergedSplit() {
        var engine = networkEngine(G3Support.bundledNetwork());
        var workspace = engine.newWorkspace();
        var evaluator = engine.evaluator();
        var basis = evaluator.components();
        var derivation = evaluator.derivation();
        String[][] binaries = {{"Methane", "Ethane"}, {"Methane", "CarbonDioxide"}, {"Nitrogen", "Methane"}, {"CarbonDioxide", "Propane"}};
        var table = new G3Support.Table("G3 F11 M3", "Binary (light/heavy)", "z light", "T K", "Outcome", "P MPa", "tie line",
                "rho light / heavy mol/m3", "h light / heavy J/mol", "light fraction", "one fluid rho %", "one fluid h J/mol");
        Stat density = new Stat(), enthalpy = new Stat();
        int requests = 0, found = 0;
        for (String[] pair : binaries) {
            int a = basis.indexOf(pair[0]), b = basis.indexOf(pair[1]);
            double ta = derivation.criticalTemperatures()[derivation.components().indexOf(pair[0])];
            double tb = derivation.criticalTemperatures()[derivation.components().indexOf(pair[1])];
            for (double zl : new double[] {0.25, 0.5, 0.75}) {
                double[] z = new double[basis.size()];
                z[a] = zl;
                z[b] = 1 - zl;
                for (double fraction : new double[] {0.25, 0.5, 0.75}) {
                    double t = ta + fraction * (tb - ta);
                    // Coarse scan in 10 % steps, then 1 % steps from the last coarse split whose tie line is still long.
                    double lastLong = Double.NaN, p = 0.5e6, end = Double.NaN;
                    Answer seen = null;
                    String outcome = "no split 0.5-40 MPa";
                    Answer at = null;
                    int failed = 0;
                    for (; p <= 40e6; p *= 1.1) {
                        var r = answer(engine, workspace, t, p, z);
                        requests++;
                        if (!r.result().converged()) failed++;
                        if (r.split()) {
                            seen = r;
                            if (r.tieLine() < FluidTpEquilibrium.BAND_TIE_LINE) { end = p; break; }
                            lastLong = p;
                        } else if (seen != null) { end = p; break; }
                    }
                    if (!Double.isNaN(lastLong) && !Double.isNaN(end)) {
                        Answer last = null;
                        for (double q = lastLong; q <= end * 1.0001; q *= 1.01) {
                            var r = answer(engine, workspace, t, q, z);
                            requests++;
                            if (r.split() && r.tieLine() < FluidTpEquilibrium.BAND_TIE_LINE) { at = r; p = q; break; }
                            if (!r.split()) {
                                double lastTie = last == null ? Double.NaN : last.tieLine();
                                outcome = r.result().converged()
                                        ? f("split ends (%s) at %.3f MPa, last tie line %.4f", r.result().classification(), q / 1e6, lastTie)
                                        : f("NOT_CONVERGED %s%s at %.3f MPa, last tie line %.4f", r.result().failureKind(),
                                                r.result().failedInCriticalBand() ? " (critical band)" : "", q / 1e6, lastTie);
                                break;
                            }
                            last = r;
                        }
                    } else if (seen != null && !Double.isNaN(end) && seen.tieLine() < FluidTpEquilibrium.BAND_TIE_LINE) {
                        at = seen;
                    } else if (seen != null) {
                        outcome = f("split to 40 MPa, last tie line %.4f", seen.tieLine());
                    }
                    if (failed > 0) outcome += f(" (%d coarse requests NOT_CONVERGED)", failed);
                    String label = pair[0] + "/" + pair[1];
                    if (at == null) {
                        table.row(label, f("%.2f", zl), f("%.2f", t), outcome, "-", "-", "-", "-", "-", "-", "-");
                        continue;
                    }
                    found++;
                    double pressure = at.light().pressure();
                    double vMix = at.lightFraction() * at.light().molarVolume() + (1 - at.lightFraction()) * at.heavy().molarVolume();
                    double hMix = at.lightFraction() * at.light().molarEnthalpy() + (1 - at.lightFraction()) * at.heavy().molarEnthalpy();
                    var one = oneFluid(evaluator, t, pressure, z);
                    double rho = 100 * (vMix / one.molarVolume() - 1);
                    double dh = one.molarEnthalpy() - hMix;
                    density.add(rho, label + " z " + zl + f(" %.1f K", t));
                    enthalpy.add(dh, label + " z " + zl + f(" %.1f K", t));
                    table.row(label, f("%.2f", zl), f("%.2f", t), "tie line below " + G3Support.number(FluidTpEquilibrium.BAND_TIE_LINE),
                            f("%.3f", pressure / 1e6), f("%.4f", at.tieLine()),
                            f("%.0f / %.0f", 1 / at.light().molarVolume(), 1 / at.heavy().molarVolume()),
                            f("%.0f / %.0f", at.light().molarEnthalpy(), at.heavy().molarEnthalpy()), f("%.3f", at.lightFraction()),
                            f("%+.2f", rho), f("%+.0f", dh));
                }
            }
        }
        table.print("the first split of each isotherm with a tie line below 0.1 and the error of answering it as one fluid ("
                + requests + " requests, " + found + " found)");
        System.out.println(f("G3 F11 M3 summary | one-fluid density %% worst / p95 %s | enthalpy J/mol worst / p95 %s",
                density.text("%+.2f"), enthalpy.text("%+.0f")));
        if (found > 0) {
            hold("M3 one-fluid density %", density.worst);
            hold("M3 one-fluid enthalpy J/mol", enthalpy.worst);
        }
        assertHeld();
    }

    /** The feed as one fluid on its lower-Gibbs root (the merged answer of decision O3 A). */
    static PhaseState oneFluid(CubicPhaseEvaluator evaluator, double t, double p, double[] z) {
        var workspace = evaluator.newWorkspace();
        var liquid = evaluator.evaluate(t, p, z, Root.LIQUID, workspace, evaluator.newState());
        var vapour = evaluator.evaluate(t, p, z, Root.VAPOR, workspace, evaluator.newState());
        return liquid.molarGibbsEnergy() <= vapour.molarGibbsEnergy() ? liquid : vapour;
    }

    // ------------------------------------------------------------------------------------------------- M4

    /**
     * The Jossi-Stiel-Thodos residual viscosity (Pa s) in the statement of Lohrenz, Bray and Clark (J. Pet. Technol. 16
     * (1964) 1171): {@code [(mu - mu0) xi + 1e-4]^(1/4) = 0.1023 + 0.023364 r + 0.058533 r^2 - 0.040758 r^3 +
     * 0.0093324 r^4}, mu in cP, {@code xi = Tc^(1/6) / (M^(1/2) Pc^(2/3))} in K, g/mol and atm. The brief's coefficients
     * (1.0230, 0.23364, ...) are these times ten, which holds with mu in micropoise and {@code + 1} on the left; with cP and
     * {@code + 1e-4} as the brief wrote them the residual would not vanish at zero density.
     */
    static double jossiStielThodos(double reducedDensity, double tc, double pc, double molarMass) {
        double r = reducedDensity;
        double xi = Math.pow(tc, 1.0 / 6) / (Math.sqrt(molarMass * 1000) * Math.pow(pc / 101325, 2.0 / 3));
        double right = 0.1023 + r * (0.023364 + r * (0.058533 + r * (-0.040758 + r * 0.0093324)));
        double centipoise = (Math.pow(right, 4) - 1e-4) / xi;
        return centipoise * 1e-3;
    }

    @Test
    void m4DenseViscosity() {
        var model = G3Support.bundledNetwork();
        var fixture = JsonParser.parseReader(new InputStreamReader(G3F11SupercriticalRegionTest.class
                .getResourceAsStream("/science/thermo/coolprop/viscosity-reference.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> ids = Map.of("CarbonDioxide", "CarbonDioxide", "Nitrogen", "Nitrogen", "Methane", "Methane", "Ethane", "Ethane",
                "n-Propane", "Propane");
        var table = new G3Support.Table("G3 F11 M4", "Fluid", "Tr", "P MPa", "rho_ref / rho_c,ref", "delta (model rho, cubic v_c)",
                "mu_ref uPa s", "dilute model / ref", "model dilute / CoolProp dilute", "JST (ref rho_c) / ref", "JST (cubic v_c) / ref");
        Stat dilute = new Stat(), referenceCritical = new Stat(), cubicCritical = new Stat();
        int states = 0;
        for (JsonElement entry : fixture.getAsJsonArray("fluids")) {
            JsonObject fluid = entry.getAsJsonObject();
            String file = fluid.get("fluid").getAsString(), id = ids.get(file);
            double[] x = pureConserved(model, id);
            int i = index(model, id);
            double[] critical = modelCritical(model, id);
            double molar = model.hydrocarbon.molecularWeight(i);
            double criticalDensity = fluid.get("rhomolar_critical").getAsDouble();
            double b = 0.07780 * R * critical[0] / critical[1];
            for (JsonElement s : fluid.getAsJsonArray("states")) {
                JsonObject state = s.getAsJsonObject();
                double t = state.get("T").getAsDouble(), p = state.get("P").getAsDouble(), mu = state.get("viscosity").getAsDouble();
                double rhoRef = state.get("Dmolar").getAsDouble();
                double mu0 = model.viscosity.vapor(t, x);
                var e = point(model, t, p, x, Root.VAPOR);
                double delta = 3.9513730355914 * b * e.density();
                double withReference = mu0 + jossiStielThodos(rhoRef / criticalDensity, critical[0], critical[1], molar);
                double withCubic = mu0 + jossiStielThodos(delta, critical[0], critical[1], molar);
                String at = file + f(" Tr %.2f %.0f MPa", state.get("Tr").getAsDouble(), p / 1e6);
                dilute.add(100 * (mu0 / mu - 1), at);
                referenceCritical.add(100 * (withReference / mu - 1), at);
                cubicCritical.add(100 * (withCubic / mu - 1), at);
                assertTrue(Double.isFinite(mu0) && mu0 > 0, "dilute viscosity at " + at);
                states++;
                table.row(file, f("%.2f", state.get("Tr").getAsDouble()), f("%.0f", p / 1e6), f("%.3f", rhoRef / criticalDensity),
                        f("%.3f", delta), f("%.2f", mu * 1e6), f("%.3f", mu0 / mu), f("%.3f", mu0 / state.get("viscosity_dilute").getAsDouble()),
                        f("%.3f", withReference / mu), f("%.3f", withCubic / mu));
            }
        }
        table.print("dense-fluid viscosity against CoolProp 8.0.0 (" + states + " states)");
        System.out.println(f("G3 F11 M4 summary | dilute %% worst / p95 %s | JST on the reference rho_c %s | JST on the cubic v_c %s",
                dilute.text("%+.1f"), referenceCritical.text("%+.1f"), cubicCritical.text("%+.1f")));
        hold("M4 dilute %", dilute.worst);
        hold("M4 JST reference rho_c %", referenceCritical.worst);
        hold("M4 JST cubic v_c %", cubicCritical.worst);
        assertHeld();
    }

    // ------------------------------------------------------------------------------------------------- M5

    @Test
    void m5SurrogatesOfTheCrudeCuts() {
        String[][] surrogates = {{"n-Hexane", "pc01"}, {"n-Heptane", "pc02"}, {"Toluene", "pc02"}, {"n-Decane", "pc03"}, {"n-Dodecane", "pc04"}};
        var summary = new G3Support.Table("G3 F11 M5", "Reference", "Cut", "omega", "Tc K", "Pc MPa", "translation cm3/mol", "n",
                "rho % worst / p95", "residual cp (% of cp) worst / p95", "h K worst / p95", "Highest P MPa (7 % / 12 %)");
        for (String[] s : surrogates) {
            var fluid = G3Support.oracle(s[0]);
            double tc = fluid.criticalTemperature(), pc = fluid.criticalPressure(), omega = acentric(s[0]), molar = molarMass(s[0]);
            var component = new ThermoComponent(s[0], tc, pc, omega, molar);
            var kernel = CoefficientTranslations.pureKernel(List.of(component), new double[][] {{0}});
            double vReference = 1 / fluid.density(288.7, 101325, HelmholtzFluid.Root.LIQUID);
            double c = vReference - CoefficientTranslations.pureLiquidVolume(kernel, 0, 288.7, 101325);
            var cubic = TranslatedCubic.residualOnly(List.of(component), new double[][] {{0}}, new double[] {c});
            Quad q = new Quad();
            var beyondStates = new TreeMap<Double, String>();
            List<Double> pressures = new ArrayList<>();
            var rows = new G3Support.Table("G3 F11 M5 " + s[0], "T K", "P MPa", "box", "rho %", "cpR ref J/(mol K)", "cpR dev % of cp", "h J/mol", "h K");
            for (double[] state : grid(fluid, s[0])) {
                double t = state[0], p = state[1];
                var ref = reference(fluid, t, p);
                var values = cubic.evaluateValues(t, p, new double[] {1}, Root.VAPOR, cubic.prepare(t));
                var zero = cubic.evaluateValues(t, 1.0, new double[] {1}, Root.VAPOR, cubic.prepare(t));
                double rho = 100 * (1 / values.molarVolume() / ref.density() - 1);
                double cpResidualRef = ref.isobaricHeatCapacity() - fluid.state(t, 1e-10).isobaricHeatCapacity();
                double cpDev = 100 * (values.heatCapacity() - cpResidualRef) / ref.isobaricHeatCapacity();
                double dh = values.molarEnthalpy() - zero.molarEnthalpy() - referenceDeparture(fluid, t, ref.enthalpy());
                int box = FluidTpEquilibrium.pureBandBox(t / tc, p / pc);
                String at = f("%.1f K %.2f MPa", t, p / 1e6);
                if (box == 0) {
                    q.density.add(rho, at);
                    q.cp.add(cpDev, at);
                    q.kelvin.add(dh / ref.isobaricHeatCapacity(), at);
                    pressures.add(p);
                    if (Math.abs(rho) > DENSITY_BOUND || Math.abs(cpDev) > CP_BOUND) beyondStates.merge(p, at, (a, b) -> a);
                }
                rows.row(f("%.2f", t), f("%.3f", p / 1e6), box, f("%+.2f", rho), f("%.2f", cpResidualRef), f("%+.2f", cpDev), f("%+.0f", dh),
                        f("%+.2f", dh / ref.isobaricHeatCapacity()));
            }
            rows.print(s[0] + " (surrogate of " + s[1] + "): PR78 on its own constants, translated at 288.7 K and 101325 Pa");
            double highest = highestBelow(pressures, beyondStates);
            summary.row(s[0], s[1], f("%.4f", omega), f("%.2f", tc), f("%.3f", pc / 1e6), f("%.2f", c * 1e6), q.density.values.size(),
                    q.density.text("%+.2f"), q.cp.text("%+.2f"), q.kelvin.text("%+.2f"), f("%.2f", highest / 1e6));
            hold("M5 " + s[0] + " density %", q.density.worst);
            hold("M5 " + s[0] + " residual cp %", q.cp.worst);
        }
        summary.print("surrogates of the cuts outside the band (the error of estimating a cut's Tc, Pc and omega is not measured)");
        // The bundled datasets of the two surrogates that are network compounds.
        var model = G3Support.bundledNetwork();
        var bundled = new G3Support.Table("G3 F11 M5 bundled", "Dataset", "n", "rho % worst / p95", "cp % worst / p95", "h K worst / p95",
                "Highest P MPa (7 % / 12 %)", "First state beyond");
        for (String[] s : new String[][] {{"n-Hexane", "N-hexane"}, {"Toluene", "Toluene"}}) {
            var fluid = G3Support.oracle(s[0]);
            double[] x = pureConserved(model, s[1]);
            double[] critical = modelCritical(model, s[1]);
            Quad q = new Quad();
            var beyondStates = new TreeMap<Double, String>();
            List<Double> pressures = new ArrayList<>();
            for (double[] state : grid(fluid, s[0])) {
                double t = state[0], p = state[1];
                if (FluidTpEquilibrium.pureBandBox(t / critical[0], p / critical[1]) != 0 || t < critical[0]) continue;
                var ref = reference(fluid, t, p);
                var e = point(model, t, p, x, Root.VAPOR);
                String at = f("%.1f K %.2f MPa", t, p / 1e6);
                double rho = 100 * (e.density() / ref.density() - 1), cp = 100 * (e.heatCapacity() / ref.isobaricHeatCapacity() - 1);
                q.density.add(rho, at);
                q.cp.add(cp, at);
                q.kelvin.add((e.departure() - referenceDeparture(fluid, t, ref.enthalpy())) / ref.isobaricHeatCapacity(), at);
                pressures.add(p);
                if (Math.abs(rho) > DENSITY_BOUND || Math.abs(cp) > CP_BOUND) beyondStates.merge(p, at + f(" rho %+.2f %%", rho), (a, b) -> a);
            }
            bundled.row(s[1], q.density.values.size(), q.density.text("%+.2f"), q.cp.text("%+.2f"), q.kelvin.text("%+.2f"),
                    f("%.2f", highestBelow(pressures, beyondStates) / 1e6), beyondStates.isEmpty() ? "none" : beyondStates.firstEntry().getValue());
            hold("M5 bundled " + s[1] + " density %", q.density.worst);
            hold("M5 bundled " + s[1] + " cp %", q.cp.worst);
        }
        bundled.print("the bundled datasets against the oracle on the same grid, outside the band and above the model's Tc");
        assertHeld();
    }

    // ------------------------------------------------------------------------------------------------- M6

    static final String[] GASES = {"CarbonDioxide", "Nitrogen", "HydrogenSulfide", "Hydrogen", "Methane"};
    static final String OVERLAY = "icheme:s0_gas_cut_pairs";

    /** The bundled catalog with one pair library, first in the method's list, setting every gas-cut pair to {@code kij}. */
    static MaterialCatalog withGasCutPairs(double kij) {
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        String methodPath = "data/icheme/materials/methods/crude_pr78.json";
        JsonObject method = JsonParser.parseString(resources.get(methodPath)).getAsJsonObject();
        JsonArray libraries = new JsonArray();
        libraries.add(OVERLAY);
        for (JsonElement library : method.getAsJsonArray("interactions")) libraries.add(library);
        method.add("interactions", libraries);
        JsonObject library = new JsonObject();
        library.addProperty("schema_version", 1);
        library.addProperty("id", OVERLAY);
        library.addProperty("model", "pr78");
        library.addProperty("revision", "s0-sensitivity-" + G3Support.number(kij * 100));
        library.addProperty("source", "G3F11 M6 sensitivity: every pair of a light gas with a crude cut at one value (not data)");
        JsonArray pairs = new JsonArray();
        for (String gas : GASES) {
            for (int cut = 1; cut <= 12; cut++) {
                JsonObject pair = new JsonObject();
                pair.addProperty("first", gas);
                pair.addProperty("second", f("crude_pc%02d", cut));
                pair.addProperty("kij", kij);
                pairs.add(pair);
            }
        }
        library.add("pairs", pairs);
        resources.put(methodPath, method.toString());
        resources.put("data/icheme/materials/interactions/s0_gas_cut_pairs.json", library.toString());
        return MaterialCatalog.parse(resources);
    }

    @Test
    void m6PairsOfTheCutsWithLightGases() {
        var catalog = MaterialCatalog.bundled();
        var model = G3Support.bundledNetwork();
        var basis = model.hydrocarbon.evaluator().components();
        var preset = FluidPresetCatalog.resolve(catalog).stream().filter(p -> p.id().equals("icheme:medium_crude")).findFirst().orElseThrow();
        double[] crude = preset.moleFractions();
        assertEquals(basis.size(), crude.length, "preset axis");
        // Today's pairs of each gas with the cuts, from the derivation.
        var derivation = model.hydrocarbon.evaluator().derivation();
        var today = new G3Support.Table("G3 F11 M6 today", "Gas", "Source of its pairs with pc01..pc12", "k_ij pc01 / pc06 / pc12");
        for (String gas : GASES) {
            var sources = new TreeMap<String, Integer>();
            String[] values = new String[13];
            for (var pair : derivation.pairs()) {
                String other = pair.first().equals(gas) ? pair.second() : pair.second().equals(gas) ? pair.first() : null;
                if (other == null || !other.startsWith("crude_pc")) continue;
                sources.merge(pair.source().name(), 1, Integer::sum);
                values[Integer.parseInt(other.substring(8))] = pair.detail();
            }
            today.row(gas, sources, values[1] + " / " + values[6] + " / " + values[12]);
        }
        today.print("today's gas-cut pairs of the network package (DerivedPairs, " + DerivedPairs.REVISION + ")");
        // The sensitivity: today, then every gas-cut pair at 0, 0.05, 0.10, 0.15.
        List<String> levels = List.of("today", "0", "0.05", "0.10", "0.15");
        List<FluidTpEquilibrium> engines = new ArrayList<>();
        engines.add(networkEngine(model));
        for (double k : new double[] {0, 0.05, 0.10, 0.15}) {
            engines.add(networkEngine(com.wormzjl.icheme.fluid.support.FluidTestSupport.model(withGasCutPairs(k), PACKAGE)));
        }
        for (var engine : engines) assertEquals(basis, engine.evaluator().components(), "engine basis");
        List<Integer> cuts = new ArrayList<>();
        for (int i = 0; i < basis.size(); i++) if (basis.get(i).startsWith("crude_pc")) cuts.add(i);
        var table = new G3Support.Table("G3 F11 M6", "Gas", "T K", "P MPa", "x_gas in liquid: today / 0 / 0.05 / 0.10 / 0.15",
                "y_oil (cuts) in gas: today / 0 / 0.05 / 0.10 / 0.15");
        int requests = 0;
        for (String gas : GASES) {
            int g = basis.indexOf(gas);
            double[] feed = crude.clone();
            feed[g] += 1.0;   // an equal amount of gas: one mole of crude and one of gas
            for (double t : new double[] {320, 450, 600}) {
                for (double p : new double[] {0.5e6, 2e6, 10e6}) {
                    List<String> xs = new ArrayList<>(), ys = new ArrayList<>();
                    for (var engine : engines) {
                        var result = engine.tp(EquilibriumRequest.tp(t, p, basis, feed, PhaseCompetition.FLUID_ONLY), engine.newWorkspace());
                        requests++;
                        assertTrue(result.failureKind() != EquilibriumResult.FailureKind.INTERNAL, "INTERNAL: " + result.detail());
                        if (!result.converged()) {
                            xs.add(result.failureKind().name());
                            ys.add(result.failureKind().name());
                            continue;
                        }
                        var fluid = result.phases().stream().filter(a -> a.kind() != PhaseKind.SOLID).toList();
                        if (fluid.size() < 2) {
                            xs.add("1 phase");
                            ys.add("1 phase");
                            continue;
                        }
                        PhaseState gasPhase = fluid.get(0).state(), liquid = null;
                        double most = -1;
                        for (var phase : fluid.subList(1, fluid.size())) {
                            double oil = 0;
                            for (int c : cuts) oil += phase.state().moleFraction(c);
                            if (oil > most) { most = oil; liquid = phase.state(); }
                        }
                        double yOil = 0;
                        for (int c : cuts) yOil += gasPhase.moleFraction(c);
                        xs.add(f("%.4f", liquid.moleFraction(g)));
                        ys.add(f("%.2e", yOil));
                    }
                    table.row(gas, G3Support.number(t), G3Support.number(p / 1e6), String.join(" / ", xs), String.join(" / ", ys));
                }
            }
        }
        table.print("medium crude (preset icheme:medium_crude) with an equal amount of one gas, TP, gas-cut pairs at " + levels
                + ": a sensitivity, not data (" + requests + " requests)");
        missingPairs(model.catalog(), derivation);
        assertHeld();
    }

    /** Decision O9: the pairs of real compounds with E-PPR78 counts that no library names and the derivation sets to zero. */
    private void missingPairs(MaterialCatalog catalog, DerivedPairs.Result derivation) {
        var counts = new TreeMap<String, Integer>();
        for (var pair : derivation.pairs()) counts.merge(pair.source().name(), 1, Integer::sum);
        System.out.println("G3 F11 M6 O9 pairs of the network package by source: " + counts);
        // The E-PPR78 matrix as data (A, B in MPa) and the group fractions of each compound.
        JsonObject matrix = JsonParser.parseString(MaterialCatalog.bundled().resources().get("data/icheme/materials/group_interactions/eppr78_2022.json")).getAsJsonObject();
        Map<String, double[]> parameters = new HashMap<>();
        for (JsonElement e : matrix.getAsJsonArray("pairs")) {
            JsonObject pair = e.getAsJsonObject();
            double[] ab = {pair.get("a_mpa").getAsDouble(), pair.get("b_mpa").getAsDouble()};
            parameters.put(pair.get("first").getAsString() + "|" + pair.get("second").getAsString(), ab);
            parameters.put(pair.get("second").getAsString() + "|" + pair.get("first").getAsString(), ab);
        }
        String scheme = matrix.get("scheme").getAsString();
        var ids = derivation.components();
        double[] tc = derivation.criticalTemperatures(), pc = derivation.criticalPressures(), omega = derivation.acentricFactors();
        // Check against the derivation's own E-PPR78 pairs (gases, at 298.15 K) before use.
        double check = 0;
        int checked = 0;
        record Zero(String first, String second, String source, double k300, double k500) {}
        List<Zero> zeros = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (var pair : derivation.pairs()) {
            int i = ids.indexOf(pair.first()), j = ids.indexOf(pair.second());
            var ci = catalog.groupCounts(PACKAGE, pair.first()).get(scheme);
            var cj = catalog.groupCounts(PACKAGE, pair.second()).get(scheme);
            if (ci == null || cj == null || pair.first().equals("Water") || pair.second().equals("Water")) continue;
            if (pair.source() == DerivedPairs.Source.EPPR78) {
                double derived = Double.parseDouble(pair.detail().substring(5, pair.detail().indexOf(' ', 5)));
                double mine = eppr78(ci.counts(), cj.counts(), parameters, 298.15, tc[i], pc[i], omega[i], tc[j], pc[j], omega[j]);
                check = Math.max(check, Math.abs(mine - derived));
                checked++;
            } else if (pair.source() == DerivedPairs.Source.NONPOLAR || pair.source() == DerivedPairs.Source.MISSING
                    || pair.source() == DerivedPairs.Source.HYDROGEN) {
                try {
                    zeros.add(new Zero(pair.first(), pair.second(), pair.source().name(),
                            eppr78(ci.counts(), cj.counts(), parameters, 300, tc[i], pc[i], omega[i], tc[j], pc[j], omega[j]),
                            eppr78(ci.counts(), cj.counts(), parameters, 500, tc[i], pc[i], omega[i], tc[j], pc[j], omega[j])));
                } catch (IllegalArgumentException absent) {
                    unresolved.add(pair.first() + " / " + pair.second());   // a group pair the 2022 table lacks: E-PPR78 has no value
                }
            }
        }
        System.out.println(f("G3 F11 M6 O9 check: this test's E-PPR78 against the derivation's %d E-PPR78 pairs at 298.15 K, worst |dk| %.2e (5 printed decimals)",
                checked, check));
        assertTrue(checked > 0 && check < 1e-5, "E-PPR78 of the test against the derivation: " + check);
        // Ranked by |k_ij(300 K)| per source; E-PPR78's power law in 298.15/T can leave its fitted range at 500 K.
        var bySource = new TreeMap<String, Integer>();
        for (var zero : zeros) bySource.merge(zero.source(), 1, Integer::sum);
        for (String source : bySource.keySet()) {
            var ranked = new ArrayList<>(zeros.stream().filter(z -> z.source().equals(source)).toList());
            ranked.sort((a, b) -> Double.compare(Math.abs(b.k300()), Math.abs(a.k300())));
            var table = new G3Support.Table("G3 F11 M6 O9 " + source, "Pair", "E-PPR78 k_ij 300 K", "E-PPR78 k_ij 500 K");
            int large = 0;
            for (var zero : ranked) if (Math.abs(zero.k300()) >= 0.05) large++;
            for (var zero : ranked.subList(0, Math.min(10, ranked.size()))) {
                assertTrue(Double.isFinite(zero.k300()) && Double.isFinite(zero.k500()), zero.toString());
                table.row(zero.first() + " / " + zero.second(), f("%+.4f", zero.k300()), f("%+.4f", zero.k500()));
            }
            table.print(ranked.size() + " pairs of source " + source + " (k_ij 0 today) whose compounds both have E-PPR78 counts, " + large
                    + " with |k_ij(300 K)| >= 0.05: the ten largest at 300 K");
        }
        System.out.println("G3 F11 M6 O9 " + zeros.size() + " pairs at zero today with E-PPR78 counts " + bySource + "; " + unresolved.size()
                + " more lack a group pair in the 2022 table (no E-PPR78 value): " + unresolved);
    }

    /**
     * E-PPR78 (Jaubert et al. 2022) k_ij(T) of two compounds on their own PR78 constants (Soave alpha with PR78's kappa):
     * {@code E = sum_(k<l) -(a_ik - a_jk)(a_il - a_jl) A_kl (298.15/T)^(B_kl/A_kl - 1)},
     * {@code k_ij = [E - (sqrt(a_i)/b_i - sqrt(a_j)/b_j)^2] / [2 sqrt(a_i a_j)/(b_i b_j)]}.
     */
    static double eppr78(Map<String, Integer> first, Map<String, Integer> second, Map<String, double[]> parameters, double t,
                         double tci, double pci, double wi, double tcj, double pcj, double wj) {
        Map<String, Double> fi = fractions(first), fj = fractions(second);
        var union = new java.util.TreeSet<String>(fi.keySet());
        union.addAll(fj.keySet());
        var groups = new ArrayList<String>(union);
        double energy = 0;
        for (int k = 0; k < groups.size(); k++) {
            for (int l = k + 1; l < groups.size(); l++) {
                String gk = groups.get(k), gl = groups.get(l);
                double weight = -(fi.getOrDefault(gk, 0.0) - fj.getOrDefault(gk, 0.0)) * (fi.getOrDefault(gl, 0.0) - fj.getOrDefault(gl, 0.0));
                if (weight == 0) continue;
                double[] ab = parameters.get(gk + "|" + gl);
                if (ab == null) throw new IllegalArgumentException("E-PPR78 group pair " + gk + "/" + gl + " absent");
                if (ab[0] == 0) continue;
                energy += weight * ab[0] * 1e6 * Math.pow(298.15 / t, ab[1] / ab[0] - 1);
            }
        }
        double ai = attraction(t, tci, pci, wi), aj = attraction(t, tcj, pcj, wj);
        double bi = 0.07780 * R * tci / pci, bj = 0.07780 * R * tcj / pcj;
        double d = Math.sqrt(ai) / bi - Math.sqrt(aj) / bj;
        return (energy - d * d) / (2 * Math.sqrt(ai * aj) / (bi * bj));
    }

    private static Map<String, Double> fractions(Map<String, Integer> counts) {
        double total = 0;
        for (int c : counts.values()) total += c;
        Map<String, Double> out = new TreeMap<>();
        for (var e : counts.entrySet()) out.put(e.getKey(), e.getValue() / total);
        return out;
    }

    private static double attraction(double t, double tc, double pc, double omega) {
        double kappa = omega <= 0.491 ? 0.37464 + 1.54226 * omega - 0.26992 * omega * omega
                : 0.379642 + 1.48503 * omega - 0.164423 * omega * omega + 0.016666 * omega * omega * omega;
        double s = 1 + kappa * (1 - Math.sqrt(t / tc));
        return 0.45724 * R * R * tc * tc / pc * s * s;
    }

    // ------------------------------------------------------------------------------------------------- M7

    @Test
    void m7MixturesAgainstCoolProp() {
        var engine = networkEngine(G3Support.bundledNetwork());
        var workspace = engine.newWorkspace();
        var basis = engine.evaluator().components();
        var fixture = JsonParser.parseReader(new InputStreamReader(G3F11SupercriticalRegionTest.class
                .getResourceAsStream("/science/thermo/coolprop/mixture-density-reference.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var table = new G3Support.Table("G3 F11 M7", "Binary", "z first", "T K", "P MPa", "CoolProp", "engine", "rho %");
        Stat density = new Stat();
        int twoReference = 0, twoEngine = 0;
        for (JsonElement entry : fixture.getAsJsonArray("states")) {
            JsonObject s = entry.getAsJsonObject();
            String first = s.get("first").getAsString(), second = s.get("second").getAsString(), phase = s.get("phase").getAsString();
            double z = s.get("z_first").getAsDouble(), t = s.get("T").getAsDouble(), p = s.get("P").getAsDouble();
            String label = first + "/" + second;
            if (phase.equals("twophase") || phase.equals("refused")) {
                twoReference++;
                table.row(label, G3Support.number(z), G3Support.number(t), G3Support.number(p / 1e6), phase, "not scored", "-");
                continue;
            }
            double[] amounts = new double[basis.size()];
            amounts[basis.indexOf(first)] = z;
            amounts[basis.indexOf(second)] = 1 - z;
            var r = answer(engine, workspace, t, p, amounts);
            if (!r.result().converged()) {
                table.row(label, G3Support.number(z), G3Support.number(t), G3Support.number(p / 1e6), phase, r.result().failureKind().name(), "-");
                continue;
            }
            var fluid = r.result().phases().stream().filter(a -> a.kind() != PhaseKind.SOLID).toList();
            if (fluid.size() != 1) {
                twoEngine++;
                table.row(label, G3Support.number(z), G3Support.number(t), G3Support.number(p / 1e6), phase,
                        fluid.size() + " phases (" + r.result().classification() + f(", light fraction %.3f)", r.lightFraction()), "-");
                continue;
            }
            double rho = 100 * (1 / fluid.get(0).state().molarVolume() / s.get("Dmolar").getAsDouble() - 1);
            density.add(rho, label + f(" z %.2f %.0f K %.0f MPa", z, t, p / 1e6));
            table.row(label, G3Support.number(z), G3Support.number(t), G3Support.number(p / 1e6), phase,
                    r.result().classification().name(), f("%+.2f", rho));
        }
        table.print(f("three binaries against CoolProp's mixture model (%d two-phase in CoolProp, %d two-phase in the engine)", twoReference, twoEngine));
        System.out.println(f("G3 F11 M7 summary | one-phase density %% worst / p95 %s over %d states", density.text("%+.2f"), density.values.size()));
        hold("M7 density %", density.worst);
        assertHeld();
    }

    // ------------------------------------------------------------------------------------------------- M8

    /** The compressed-liquid fluids of M8: the M1 fluids without hydrogen, and the bundled n-hexane and toluene datasets. */
    static final List<Fluid> M8 = List.of(new Fluid("CarbonDioxide", "CarbonDioxide", "CO2"), new Fluid("Nitrogen", "Nitrogen", "N2"),
            new Fluid("Methane", "Methane", "CH4"), new Fluid("Ethane", "Ethane", "C2H6"), new Fluid("n-Propane", "Propane", "C3H8"),
            new Fluid("Ethylene", "Ethylene", "C2H4"), new Fluid("n-Hexane", "N-hexane", "n-C6"), new Fluid("Toluene", "Toluene", "Toluene"));
    static final double[] LIQUID_TR = {0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95};
    static final double[] LIQUID_P = {2e6, 5e6, 10e6, 20e6, 30e6};
    /** The Tr bands of G3F1's liquid rules: Tr <= 0.8, 0.85, 0.9, 0.95. */
    static final String[] LIQUID_BAND = {"Tr <= 0.8", "Tr 0.85", "Tr 0.9", "Tr 0.95"};
    /** G3F1's declared bounds per band: COMPRESSED, COMPRESSED, COMPRESSED_HIGH, NEAR_TC (density %). */
    static final double[] LIQUID_DENSITY_BOUND = {3, 3, 5, 6};
    /** G3F1's declared bounds per band: LIQUID_CP_COLD, LIQUID_CP, LIQUID_CP, none above Tr 0.9 (cp %). */
    static final double[] LIQUID_CP_BOUND = {10, 20, 20, Double.NaN};

    /** A grid pressure in MPa, or "below 2" where the lowest grid pressure already misses. */
    static String megapascals(double pressure) { return pressure == 0 ? "below 2" : f("%.0f", pressure / 1e6); }

    static int liquidBand(double tr) { return tr <= 0.8 ? 0 : tr <= 0.85 ? 1 : tr <= 0.9 ? 2 : 3; }

    /** The oracle's melting pressure at T where G3Support has its melting line, else infinity (CoolProp refused no M8 state of the others). */
    static double meltingOrInfinity(String file, double t) {
        try {
            return G3Support.meltingPressure(file, t);
        } catch (IllegalArgumentException e) {
            return Double.POSITIVE_INFINITY;
        }
    }

    /**
     * M8: compressed liquids from 2 to 30 MPa on the bundled model's liquid root against the oracle, on Tr 0.5-0.95 of the
     * oracle's Tc (the grid of source {@code compressed_liquid} in the parity fixtures), judged by the liquid bounds G3F1
     * declares. A state is kept where the oracle is liquid (P above its Psat), T is at or above the oracle's triple point
     * and the dataset's fluid_domain minimum, and P is at most the oracle's p_max and below the melting line. Hydrogen is
     * left out: its liquid lies below 33 K.
     */
    @Test
    void m8CompressedLiquids() {
        var model = G3Support.bundledNetwork();
        var summary = new G3Support.Table("G3 F11 M8", "Fluid", "Band", "n", "rho % worst / p95", "cp % worst / p95", "h K worst / p95",
                "Bounds (rho / cp %)", "Met");
        var ranges = new G3Support.Table("G3 F11 M8 range", "Fluid", "Domain min K", "Tr kept", "Liquids hold to (MPa)", "First state beyond",
                "Density alone holds to (MPa)", "Pressure misses hold to (MPa)", "First pressure miss");
        for (Fluid fl : M8) {
            var fluid = G3Support.oracle(fl.file());
            double[] x = pureConserved(model, fl.id());
            double tc = fluid.criticalTemperature(), pc = fluid.criticalPressure(), pMax = maximumPressure(fl.file());
            double domainMin = model.catalog().fluidValidity(PACKAGE, fl.id()).map(MaterialCatalog.Validity::minimumTemperature).orElse(0.0);
            Quad[] bands = {new Quad(), new Quad(), new Quad(), new Quad()};
            var beyondStates = new TreeMap<Double, String>();
            var beyondDensity = new TreeMap<Double, String>();
            // A pressure miss: beyond its bound and worse than the isotherm's lowest liquid state (the error next to
            // saturation, which today's 2 MPa range already admits on the isotherms below).
            var beyondPressure = new TreeMap<Double, String>();
            List<Double> pressures = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            var rows = new G3Support.Table("G3 F11 M8 " + fl.label(), "T K", "P MPa", "Tr", "Pr", "Z ref", "Z model", "rho %", "cp %", "h K", "Met");
            for (double tr : LIQUID_TR) {
                double t = tr * tc;
                if (t < fluid.tripleTemperature() || t < domainMin) continue;
                double pSat = fluid.saturation(t).pressure();
                boolean any = false;
                double firstRho = Double.NaN, firstCp = Double.NaN;
                for (double p : LIQUID_P) {
                    if (p <= pSat || p > pMax || p >= meltingOrInfinity(fl.file(), t)) continue;
                    any = true;
                    var ref = fluid.stateAtPressure(t, p, HelmholtzFluid.Root.LIQUID);
                    var e = point(model, t, p, x, Root.LIQUID);
                    double rho = 100 * (e.density() / ref.density() - 1);
                    double cp = 100 * (e.heatCapacity() / ref.isobaricHeatCapacity() - 1);
                    double kelvin = (e.departure() - referenceDeparture(fluid, t, ref.enthalpy())) / ref.isobaricHeatCapacity();
                    assertTrue(Double.isFinite(e.z()) && e.roots() >= 1, fl.label() + " liquid root at " + t + " K " + p + " Pa");
                    int band = liquidBand(tr);
                    String at = f("%.1f K %.0f MPa", t, p / 1e6);
                    Quad q = bands[band];
                    q.density.add(rho, at);
                    q.cp.add(cp, at);
                    q.kelvin.add(kelvin, at);
                    boolean densityMet = Math.abs(rho) <= LIQUID_DENSITY_BOUND[band];
                    boolean cpMet = Double.isNaN(LIQUID_CP_BOUND[band]) || Math.abs(cp) <= LIQUID_CP_BOUND[band];
                    pressures.add(p);
                    if (!densityMet || !cpMet) beyondStates.merge(p, at + f(" Tr %.2f rho %+.2f %% cp %+.2f %%", tr, rho, cp), (a, b) -> a);
                    if (!densityMet) beyondDensity.merge(p, at + f(" Tr %.2f rho %+.2f %%", tr, rho), (a, b) -> a);
                    if (Double.isNaN(firstRho)) {
                        firstRho = rho;
                        firstCp = cp;
                    } else if ((!densityMet && Math.abs(rho) > Math.abs(firstRho)) || (!cpMet && Math.abs(cp) > Math.abs(firstCp))) {
                        beyondPressure.merge(p, at + f(" Tr %.2f rho %+.2f %% cp %+.2f %% (lowest P: %+.2f, %+.2f)", tr, rho, cp, firstRho, firstCp),
                                (a, b) -> a);
                    }
                    rows.row(f("%.2f", t), f("%.0f", p / 1e6), f("%.2f", tr), f("%.2f", p / pc), f("%.4f", ref.compressibility()), f("%.4f", e.z()),
                            f("%+.2f", rho), f("%+.2f", cp), f("%+.2f", kelvin), densityMet && cpMet ? "yes" : densityMet ? "rho only" : "no");
                }
                if (any) kept.add(f("%.2f", tr));
            }
            rows.print(fl.label() + ": bundled model's liquid root against the oracle (" + fl.file() + "), compressed liquids 2-30 MPa");
            for (int b = 0; b < bands.length; b++) {
                Quad q = bands[b];
                String bounds = G3Support.number(LIQUID_DENSITY_BOUND[b]) + " / " + (Double.isNaN(LIQUID_CP_BOUND[b]) ? "none" : G3Support.number(LIQUID_CP_BOUND[b]));
                if (q.density.values.isEmpty()) {
                    summary.row(fl.label(), LIQUID_BAND[b], 0, "-", "-", "-", bounds, "-");
                    continue;
                }
                boolean densityMet = Math.abs(q.density.worst) <= LIQUID_DENSITY_BOUND[b];
                boolean cpMet = Double.isNaN(LIQUID_CP_BOUND[b]) || Math.abs(q.cp.worst) <= LIQUID_CP_BOUND[b];
                summary.row(fl.label(), LIQUID_BAND[b], q.density.values.size(), q.density.text("%+.2f"), q.cp.text("%+.2f"), q.kelvin.text("%+.2f"),
                        bounds, densityMet && cpMet ? "yes" : densityMet ? "rho only" : cpMet ? "cp only" : "no");
                String key = "M8 " + fl.label() + " " + LIQUID_BAND[b];
                hold(key + " density %", q.density.worst);
                hold(key + " cp %", q.cp.worst);
                hold(key + " h K", q.kelvin.worst);
            }
            ranges.row(fl.label(), G3Support.number(domainMin), kept.isEmpty() ? "none" : String.join(", ", kept),
                    pressures.isEmpty() ? "no state" : megapascals(highestBelow(pressures, beyondStates)),
                    pressures.isEmpty() ? "-" : beyondStates.isEmpty() ? "none to 30 MPa" : beyondStates.firstEntry().getValue(),
                    pressures.isEmpty() ? "-" : megapascals(highestBelow(pressures, beyondDensity)),
                    pressures.isEmpty() ? "-" : megapascals(highestBelow(pressures, beyondPressure)),
                    pressures.isEmpty() ? "-" : beyondPressure.isEmpty() ? "none to 30 MPa" : beyondPressure.firstEntry().getValue());
        }
        summary.print("compressed liquids per Tr band (Tr on the oracle's Tc), with G3F1's declared liquid bounds");
        ranges.print("per dataset: the highest grid pressure up to which every liquid state meets its bound");
        assertHeld();
    }
}
