package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * A restricted package view (batch 2026-11-04-refinery-compounds, V1, {@link MaterialCatalog#restrict}) is the package's
 * model on fewer compounds. PR78's mixing sums run over the compounds present: a compound at exactly zero adds exact zeros
 * to a_m, b_m, Z, h and every other compound's ln phi, and Wilke's viscosity, the ideal-gas terms and the liquid-mixture
 * correction also sum only what is present. So a state that holds none of the other compounds has the same bits on the
 * view as on the package: the phase state, the ln phi of the present compounds, the TP and PH flashes, the viscosities,
 * the crystals (pilot) and the data-range notices. An absent compound's amount stays exactly zero on the package.
 */
class RestrictedPackagePhysicsTest {
    private static final String NETWORK = "icheme:tjl20_methane_nitrogen";
    private static final String PILOT = PilotCryogenicTestCatalog.PACKAGE_ID;
    private static final List<String> CRUDE = List.of("Nitrogen", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane",
            "crude_pc01", "crude_pc02", "crude_pc03", "crude_pc04", "crude_pc05", "crude_pc06", "crude_pc07", "crude_pc08", "crude_pc09",
            "crude_pc10", "crude_pc11", "crude_pc12");
    private static final List<String> LIGHT = List.of("Nitrogen", "Methane", "Ethane", "Propane", "Isobutane", "N-butane");

    /** The package's model and the view's, with the view's conserved basis (water last) mapped onto the package's by id. */
    private record Pair(FluidThermodynamics full, FluidThermodynamics view, int[] index) {
        static Pair of(MaterialCatalog catalog, String packageId, List<String> subset) {
            var full = FluidThermodynamics.forNetwork(catalog, packageId);
            var view = FluidThermodynamics.forNetwork(catalog.restrict(packageId, subset), packageId);
            var ids = view.components();
            int[] index = new int[ids.size()];
            for (int i = 0; i < index.length; i++) index[i] = full.components().indexOf(ids.get(i));
            for (int i : index) assertTrue(i >= 0);
            return new Pair(full, view, index);
        }
        /** A view vector (hydrocarbons, or the conserved basis with water) on the package's basis. */
        double[] up(double[] v) {
            int n = v.length == index.length ? full.componentCount() : full.componentCount() - 1;
            double[] result = new double[n];
            for (int i = 0; i < v.length; i++) result[index[i]] = v[i];
            return result;
        }
    }

    private static void bits(double expected, double actual, String what) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), what + ": " + expected + " on the package, " + actual + " on the view");
    }
    /** A hydrocarbon vector of the package against the view's: equal bits where the view holds the compound, zero elsewhere. */
    private static void vector(Pair m, double[] full, double[] view, String what) {
        var covered = new boolean[full.length];
        for (int i = 0; i < view.length; i++) { bits(full[m.index()[i]], view[i], what + "[" + i + "]"); covered[m.index()[i]] = true; }
        for (int i = 0; i < full.length; i++) if (!covered[i]) assertEquals(0.0, full[i], 0.0, what + ": an absent compound stays at zero");
    }
    private static void phase(Pair m, HydrocarbonModel.Phase full, HydrocarbonModel.Phase view, String what) {
        assertEquals(full == null, view == null, what);
        if (full == null) return;
        bits(full.molarVolume(), view.molarVolume(), what + " molar volume");
        bits(full.molarEnthalpy(), view.molarEnthalpy(), what + " molar enthalpy");
        bits(full.molarInternalEnergy(), view.molarInternalEnergy(), what + " molar internal energy");
        bits(full.volumePressureDerivative(), view.volumePressureDerivative(), what + " dv/dP");
        assertEquals(full.vaporLike(), view.vaporLike(), what + " vapour-like");
        // ln phi of the present compounds; an absent compound has only its infinite-dilution value on the package.
        double[] lf = full.logFugacityView(), lv = view.logFugacityView();
        for (int i = 0; i < lv.length; i++) bits(lf[m.index()[i]], lv[i], what + " ln phi[" + i + "]");
    }
    private static void state(Pair m, FluidThermodynamics.State full, FluidThermodynamics.State view, String what) {
        assertEquals(full == null, view == null, what + ": answered on one model only");
        if (full == null) return;
        double[] f = {full.temperature(), full.pressure(), full.waterLiquid(), full.waterVapor(), full.hydrocarbonPartialPressure(),
                full.waterPartialPressure(), full.volume(), full.enthalpy(), full.internalEnergy(), full.mass(), full.liquidVolume(),
                full.waterVolume(), full.vaporVolume(), full.waterCompressibility(), full.solidMoments().volume(), full.solidMoments().mass()};
        double[] v = {view.temperature(), view.pressure(), view.waterLiquid(), view.waterVapor(), view.hydrocarbonPartialPressure(),
                view.waterPartialPressure(), view.volume(), view.enthalpy(), view.internalEnergy(), view.mass(), view.liquidVolume(),
                view.waterVolume(), view.vaporVolume(), view.waterCompressibility(), view.solidMoments().volume(), view.solidMoments().mass()};
        for (int i = 0; i < f.length; i++) bits(f[i], v[i], what + " field " + i);
        assertEquals(full.solids().populations().size(), view.solids().populations().size(), what + " solid populations");
        vector(m, full.liquidView(), view.liquidView(), what + " liquid");
        vector(m, full.vaporView(), view.vaporView(), what + " vapour");
        phase(m, full.liquidProperties(), view.liquidProperties(), what + " liquid phase");
        phase(m, full.vaporProperties(), view.vaporProperties(), what + " vapour phase");
        for (int p : new int[]{FluidThermodynamics.GAS, FluidThermodynamics.OIL, FluidThermodynamics.WATER}) {
            assertEquals(FluidThermodynamics.holdsPhase(full, p), FluidThermodynamics.holdsPhase(view, p), what + " phase " + p);
            if (!FluidThermodynamics.holdsPhase(full, p)) continue;
            bits(m.full().phaseViscosity(full, p, new MixtureViscosity.Workspace(), null),
                    m.view().phaseViscosity(view, p, new MixtureViscosity.Workspace(), null), what + " viscosity of phase " + p);
        }
        var nf = new DataRangeNotices();
        var nv = new DataRangeNotices();
        m.full().dataRanges(full, 7, nf);
        m.view().dataRanges(view, 7, nv);
        assertEquals(nf.notices(), nv.notices(), what + " data-range notices");
    }
    /** The two models' answers, or their refusals, which must be the same refusal; whether both answered. */
    private static <T> boolean both(Supplier<T> full, Supplier<T> view, java.util.function.BiConsumer<T, T> same, String what) {
        T f = null, v = null; RuntimeException ef = null, ev = null;
        try { f = full.get(); } catch (RuntimeException e) { ef = e; }
        try { v = view.get(); } catch (RuntimeException e) { ev = e; }
        if (ef != null || ev != null) {
            assertNotNull(ef, what + ": refused on the view only: " + ev);
            assertNotNull(ev, what + ": refused on the package only: " + ef);
            assertEquals(ef.getClass(), ev.getClass(), what);
            assertEquals(ef.getMessage(), ev.getMessage(), what);
            return false;
        }
        same.accept(f, v);
        return true;
    }

    /** Flashes, a direct state of each flash's phases and a PH flash back to the first temperature, at each (T, P). */
    private static int check(Pair m, double[] overall, double[][] conditions, String what) {
        int answered = 0;
        double[] full = m.up(overall);
        for (double[] tp : conditions) {
            double t = tp[0], p = tp[1];
            String at = what + " at " + t + " K, " + p + " Pa";
            var flashes = new ArrayList<FluidThermodynamics.State>();
            both(() -> m.full().flashTP(t, p, full, () -> {}), () -> m.view().flashTP(t, p, overall, () -> {}), (f, v) -> {
                state(m, f, v, at + " TP flash"); flashes.add(f); flashes.add(v);
            }, at + " TP flash");
            if (flashes.isEmpty()) continue;
            answered++;
            var f = flashes.get(0); var v = flashes.get(1);
            both(() -> m.full().state(t, p, f.liquid(), f.vapor(), f.waterLiquid(), f.waterVapor(), f.hydrocarbonPartialPressure()),
                    () -> m.view().state(t, p, v.liquid(), v.vapor(), v.waterLiquid(), v.waterVapor(), v.hydrocarbonPartialPressure()),
                    (a, b) -> state(m, a, b, at + " direct state"), at + " direct state");
            double h = f.enthalpy();
            both(() -> m.full().equilibriumPH(t * 1.05, p, h, full, () -> {}), () -> m.view().equilibriumPH(t * 1.05, p, h, overall, () -> {}),
                    (a, b) -> state(m, a, b, at + " PH flash"), at + " PH flash");
        }
        return answered;
    }

    @Test void theNetworkPackageOnCrudeIslandCompoundsLightEndsAndNitrogenAlone() {
        var catalog = MaterialCatalog.bundled();
        var crude = Pair.of(catalog, NETWORK, CRUDE);
        double[] oil = new double[crude.view().componentCount()];
        for (int i = 0; i < oil.length - 1; i++) oil[i] = 0.2 + 0.07 * ((i * 7) % 11);
        oil[0] = 0.02; oil[oil.length - 1] = 0.3;
        // A crude liquid, a two-phase crude with water, the gas of its light end and a crude below its fits' data.
        assertTrue(check(crude, oil, new double[][]{{360, 2e5}, {520, 1.5e5}, {650, 1e5}, {250, 101325}, {900, 5e4}}, "crude") >= 3);
        var light = Pair.of(catalog, NETWORK, LIGHT);
        double[] gas = {0.05, 0.6, 0.15, 0.1, 0.05, 0.05, 0.02};
        assertTrue(check(light, gas, new double[][]{{300, 3e6}, {240, 2e6}, {180, 1e5}, {120, 5e5}}, "light ends") >= 3);
        var nitrogen = Pair.of(catalog, NETWORK, List.of("Nitrogen"));
        assertTrue(check(nitrogen, new double[]{1, 0}, new double[][]{{77, 101325}, {300, 101325}, {60, 1e5}, {500, 1e7}}, "nitrogen") >= 3);
        assertTrue(check(nitrogen, new double[]{1, 0.4}, new double[][]{{300, 101325}, {380, 2e5}, {268, 101325}}, "nitrogen with water") >= 2);
    }

    @Test void thePilotPackageOnItsCarbonDioxideWithNitrogenAndItsLightEnds() {
        var catalog = PilotCryogenicTestCatalog.catalog();
        var co2 = Pair.of(catalog, PILOT, List.of("Nitrogen", "CarbonDioxide"));
        assertEquals(List.of("icheme:crystal_carbon_dioxide_i"), catalog.restrict(PILOT, List.of("Nitrogen", "CarbonDioxide")).requirePackage(PILOT).crystals());
        double[] n = {0.3, 0.7, 0};
        assertTrue(check(co2, n, new double[][]{{300, 101325}, {230, 2e6}, {220, 1e5}, {200, 1e5}}, "pilot CO2 with nitrogen") >= 3);
        // The crystal state: a refresh of a frosting gas, slack and taut, with the CO2-I crystal competing.
        double[] full = co2.up(n);
        double h = co2.full().flashTP(200, 1e5, full, () -> {}).enthalpy() - 6000;
        assertTrue(both(() -> co2.full().crystalsAtPressure(full, h, 1e5, true, () -> {}), () -> co2.view().crystalsAtPressure(n, h, 1e5, true, () -> {}),
                (a, b) -> crystal(co2, a, b, "slack crystals"), "slack crystals"), "a slack refresh answers");
        assertTrue(both(() -> co2.full().crystalsAtVolume(full, h, 2e-2, 190, 1e5, true, () -> {}), () -> co2.view().crystalsAtVolume(n, h, 2e-2, 190, 1e5, true, () -> {}),
                (a, b) -> crystal(co2, a, b, "taut crystals"), "taut crystals"), "a taut refresh answers");
        assertEquals(co2.full().crystalsCompete(195, full), co2.view().crystalsCompete(195, n));
        var light = Pair.of(catalog, PILOT, List.of("Nitrogen", "Methane", "Ethane"));
        assertTrue(catalog.restrict(PILOT, List.of("Nitrogen", "Methane", "Ethane")).requirePackage(PILOT).crystals().isEmpty(), "no CO2, no CO2-I");
        assertTrue(check(light, new double[]{0.05, 0.85, 0.1, 0}, new double[][]{{150, 1e6}, {115, 101325}, {300, 5e6}}, "pilot light ends") >= 2);
    }

    private static void crystal(Pair m, FluidThermodynamics.CrystalAnswer full, FluidThermodynamics.CrystalAnswer view, String what) {
        bits(full.temperature(), view.temperature(), what + " temperature");
        bits(full.pressure(), view.pressure(), what + " pressure");
        bits(full.volume(), view.volume(), what + " volume");
        var fa = m.full().crystalSolids().all(); var va = m.view().crystalSolids().all();
        double[] fm = full.crystalMoles(), vm = view.crystalMoles();
        for (int i = 0; i < va.size(); i++) {
            String id = va.get(i).material().id(); int k = -1;
            for (int j = 0; j < fa.size(); j++) if (fa.get(j).material().id().equals(id)) k = j;
            assertTrue(k >= 0, what + ": crystal " + id + " on the view only");
            bits(fm[k], vm[i], what + " moles of " + id);
        }
        assertTrue(full.holdsCrystal(), what + ": the CO2 frosts");
        state(m, full.fluid(), view.fluid(), what + " fluid");
    }

    /** The view keeps the package's ranges and names only its own compounds' records. */
    @Test void theViewKeepsThePackageRangesAndNamesOnlyItsRecords() {
        var catalog = MaterialCatalog.bundled();
        var parent = catalog.requirePackage(NETWORK);
        var restricted = catalog.restrict(NETWORK, List.of("crude_pc03", "Nitrogen"));
        var view = restricted.requirePackage(NETWORK);
        assertEquals(List.of("Nitrogen", "crude_pc03").stream().sorted().toList(), view.components().stream().sorted().toList());
        assertEquals(parent.components().indexOf("crude_pc03") < parent.components().indexOf("Nitrogen") ? List.of("crude_pc03", "Nitrogen")
                : List.of("Nitrogen", "crude_pc03"), view.components(), "the package's order");
        assertEquals(parent.minimumTemperature(), view.minimumTemperature());
        assertEquals(parent.maximumTemperature(), view.maximumTemperature());
        assertEquals(parent.minimumPressure(), view.minimumPressure());
        assertEquals(parent.maximumPressure(), view.maximumPressure());
        assertEquals(catalog.fluidValidity(NETWORK), restricted.fluidValidity(NETWORK));
        // The method leads both identities; the package record is in neither (option B, 2026-11-04).
        assertEquals(parent.scientificRevision().split(";")[0], view.scientificRevision().split(";")[0]);
        assertTrue(view.scientificRevision().startsWith("method=icheme:crude_pr78@"), view.scientificRevision());
        assertFalse(parent.scientificRevision().contains(NETWORK + "@") || view.scientificRevision().contains(NETWORK + "@"), parent.scientificRevision());
        assertTrue(view.scientificRevision().contains("properties=icheme:crude_pc03@") && !view.scientificRevision().contains("icheme:crude_pc04@"), view.scientificRevision());
        // The frozen pairs name no pair with nitrogen, so its view names no pair library; the crude's light ends do.
        var nitrogen = catalog.restrict(NETWORK, List.of("Nitrogen")).requirePackage(NETWORK);
        assertTrue(nitrogen.scientificRevision().contains(";interactions=;"), nitrogen.scientificRevision());
        assertTrue(catalog.restrict(NETWORK, LIGHT).requirePackage(NETWORK).scientificRevision().contains(";interactions=icheme:tjl20@"));
        assertNotEquals(catalog.fluidRevision(NETWORK), restricted.fluidRevision(NETWORK));
        assertSame(catalog, catalog.restrict(NETWORK, parent.components()), "the view of every compound is the package");
        assertThrows(IllegalArgumentException.class, () -> catalog.restrict(NETWORK, List.of("Nitrogen", "Nitrogen")));
        assertThrows(IllegalArgumentException.class, () -> catalog.restrict(NETWORK, List.of("CarbonDioxide")));
        assertThrows(IllegalArgumentException.class, () -> catalog.restrict(NETWORK, List.of()));
    }
}
