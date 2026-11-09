package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.fluid.state.EnergyReference;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.ThermoIdentity;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzFluid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P3 WP5 gate (batch 2026-09-24-coolprop-low-temperature, plan {@code P3_PILOT_ENGINE_PLAN.md} section 5, decisions
 * D6, D7, D10): the bundled pilot package {@code icheme:pilot_cryogenic} builds a fluid network model on the spine
 * path, and that model is the P1 direct evaluation with the P3 anchors.
 *
 * <ul>
 *   <li>The model: spine variant, E-PPR78 pairs, one {@code volume_translation} anchor per species reproduced at its own
 *   state, the sensible datum (each species' ideal-gas enthalpy exactly 0 at 298.15 K) and the formation reference.</li>
 *   <li>Pure and mixed states at 0.1 to 10 MPa through the network's own flash and state: conserved and finite.</li>
 *   <li>The P1 study's direct-evaluation numbers (anchor (b), sections 2.4 and 2.5) reproduced to their printed digits
 *   (ethane within the effect of its record's critical constants; the heat capacity at the four critical points, where
 *   it diverges, is not compared).</li>
 *   <li>A pilot isotherm set of all four species, CO2 included, against the Java Helmholtz oracle, scored on the D7
 *   targets (P0 section 3.2 with the P1 amendments) outside the declared critical band; where the cubic does not meet a
 *   D7 target the test holds the declared bound and prints the D7 verdict (the qualification itself is WP9's).</li>
 * </ul>
 */
class SpineNetworkPathTest {
    static final String PILOT = PilotCryogenicTestCatalog.PACKAGE_ID;
    static final String[] SPECIES = {"Nitrogen", "Methane", "Ethane", "CarbonDioxide"};
    private static final Map<String, HelmholtzFluid> ORACLES = new HashMap<>();

    static HelmholtzFluid oracle(int species) {
        return ORACLES.computeIfAbsent(SPECIES[species], HelmholtzFluid::load);
    }

    static HydrocarbonModel pilot() { return FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT).hydrocarbon; }

    @Test
    void thePilotBuildsANetworkModelOnTheSpinePathWithItsAnchorsAndDatum() {
        var catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var model = FluidTestSupport.model(catalog, PILOT);
        var hydrocarbon = model.hydrocarbon;
        var eos = hydrocarbon.translated();
        assertTrue(hydrocarbon.spine().isPresent(), "the spine path");
        assertEquals(List.of(SPECIES), hydrocarbon.components());
        assertEquals(List.of("Nitrogen", "Methane", "Ethane", "CarbonDioxide", "Water"), model.components());
        // The network configuration can select it too (the test override of networks/default.json).
        var selected = FluidThermodynamics.forNetwork(PilotCryogenicTestCatalog.catalog(), PILOT);
        assertEquals(hydrocarbon.revision(), selected.hydrocarbon.revision());
        // Six E-PPR78 pairs, all temperature dependent.
        var data = hydrocarbon.spine().orElseThrow();
        var pairs = data.interactions();
        assertEquals(6, pairs.pairCount());
        assertTrue(pairs.temperatureDependent(), "E-PPR78 group contributions: kij(T)");
        var pilot = catalog.requirePackage(PILOT);
        // WP3's anchors (P3_PILOT_PACKAGE_AND_SPINE.md section 4), cm3/mol, and each reproduced at its own state.
        double[] wp3 = {3.518832, 3.389108, 3.451672, 1.169707};
        for (int i = 0; i < 4; i++) {
            var property = pilot.properties().get(i);
            var anchor = property.volumeTranslation();
            var resolved = data.anchors().get(i);
            assertEquals(SpinePackageData.AnchorSource.VOLUME_TRANSLATION, resolved.source());
            assertEquals(Double.doubleToRawLongBits(anchor.shift(property.pr())), Double.doubleToRawLongBits(eos.translation(i)), SPECIES[i]);
            assertEquals(wp3[i], eos.translation(i) * 1e6, 5e-7, SPECIES[i] + " translation, cm3/mol");
            double[] pure = new double[4];
            pure[i] = 1;
            var liquid = hydrocarbon.phase(anchor.temperatureKelvin(), anchor.pressurePascal(), pure, Root.LIQUID);
            assertEquals(anchor.molarVolumeCubicMetresPerMol(), liquid.molarVolume(), 1e-12 * liquid.molarVolume(),
                    SPECIES[i] + ": the Tr 0.8 saturated liquid volume at its own state");
            // D10: the formation enthalpy is subtracted per species, so the network's ideal gas is exactly 0 at 298.15 K.
            var spine = catalog.requireSpine(PILOT, SPECIES[i]);
            assertEquals(spine.formationEnthalpy().value(), data.formationEnthalpies()[i], 0.0);
            assertEquals(0.0, data.idealGas().get(i).enthalpy(298.15) - data.formationEnthalpies()[i], 0.0, SPECIES[i] + " sensible zero");
        }
        EnergyReference formation = data.formationReference();
        assertTrue(formation.formationDataQualified());
        assertEquals(hydrocarbon.components(), formation.components());
        assertEquals(SpinePackageData.FORMATION_DATUM + ":" + catalog.spineRevision(PILOT), formation.revision());
        assertEquals(-393477.0, formation.offsetJoulesPerMole(3), 0.0);
        // Free water keeps the network's datum and density: the same water record as the bundled network package.
        var bundled = FluidTestSupport.model(catalog, "icheme:tjl20_methane_nitrogen");
        assertEquals(bundled.pumpReferenceDensity(), model.pumpReferenceDensity(), 0.0);
        assertEquals(bundled.waterLiquid(300, 1e6).molarEnthalpy(), model.waterLiquid(300, 1e6).molarEnthalpy(), 0.0);
        assertTrue(bundled.hydrocarbon.spine().isEmpty(), "the coefficient path: no spine, no identity, no formation reference");
    }

    /**
     * Pure and mixed states through the network's flash and state at 0.1 to 10 MPa: conserved and finite. Since the owner
     * rule of 2026-10-16 no state is refused for leaving a data range (a species below its spine, the steam partial-pressure
     * limit, the envelope's 900 K): each is carried on its continuation. Since WP4 the wet feed below 273.16 K is evaluated
     * too: its water is ice, carried as its stand-in, beside hydrocarbons under the crystal competition (no hydrates,
     * decision 6 A). A CO2 gas below its triple point that deposits is stated as its gas, and since P5 a CO2-bearing liquid that
     * freezes as its liquid (the crystal is the vessel's inventory, moved by the engine's UV answer). P4 stage 2b held 69
     * states of this grid typed (65 SOLID_LIQUID, 4 THREE_PHASES); P5 answers all of them. Until P3 WP7 the network's initializer was a Wilson flash that did not converge once on this grid (CH4/CO2
     * 80/20 at 230 K and 2 MPa, next to its dew point); the equilibrium engine answers every state.
     */
    @Test
    void pureAndMixedStatesEvaluateAcrossTheEnvelope() {
        var model = FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
        double[][] feeds = {
                {1, 0, 0, 0, 0}, {0, 1, 0, 0, 0}, {0, 0, 1, 0, 0}, {0, 0, 0, 1, 0},
                {0.5, 0.5, 0, 0, 0}, {0.1, 0.7, 0.2, 0, 0}, {0, 0.8, 0, 0.2, 0}, {0.2, 0, 0, 0.8, 0}, {0, 0, 0.6, 0.4, 0},
                {0.05, 0.8, 0.1, 0.05, 0}, {0.1, 0.6, 0.1, 0.1, 0.1}};
        double[] temperatures = {80, 100, 120, 150, 180, 230, 260, 300, 400, 700, 1100};
        double[] pressures = {1.0e5, 3.0e5, 1.0e6, 2.0e6, 5.0e6, 1.0e7};
        int evaluated = 0, wetCold = 0, twoPhase = 0;
        var holds = new java.util.TreeMap<FluidThermodynamics.UnsupportedPhases.Kind, Integer>();
        List<String> failures = new ArrayList<>();
        for (double t : temperatures) {
            for (double p : pressures) {
                for (double[] feed : feeds) {
                    FluidThermodynamics.State state;
                    try {
                        state = model.flashTP(t, p, feed, () -> { });
                    } catch (FluidThermodynamics.UnsupportedPhases hold) {
                        // P4 stage 2b held a crystal beside a liquid (SOLID_LIQUID) and a fluid answer with a third fluid
                        // phase (THREE_PHASES) here; P5 answers both. A typed hold may only remain where the crystal
                        // competition newly admits CO2 (two liquids or three fluid phases beside the crystal), with or without
                        // free water beside it (WP4: the hydrocarbons answered as the dry feed's); none is met.
                        assertTrue(feed[3] > 0 && t < 216.592, "a typed hold only where the crystal competition newly admits CO2: "
                                + t + " K, " + p + " Pa, " + java.util.Arrays.toString(feed) + ": " + hold.getMessage());
                        holds.merge(hold.kind(), 1, Integer::sum);
                        continue;
                    } catch (IllegalArgumentException failed) {
                        assertTrue(failed.getMessage().startsWith("Fluid TP equilibrium did not converge"), failed.getMessage());
                        failures.add(t + " K, " + p + " Pa, " + java.util.Arrays.toString(feed));
                        continue;
                    }
                    evaluated++;
                    if (feed[4] > 0 && t < 273.16) {
                        // WP4: below the melting line the condensed water is ice, which a TP answer carries as its stand-in.
                        wetCold++;
                        assertTrue(state.waterLiquid() > 0, "the wet feed's ice at " + t + " K, " + p + " Pa");
                    }
                    double[] liquid = state.liquidView(), vapor = state.vaporView();
                    for (int i = 0; i < 4; i++) {
                        assertEquals(feed[i], liquid[i] + vapor[i], 1e-12, "conserved " + SPECIES[i] + " at " + t + " K, " + p + " Pa");
                    }
                    assertEquals(feed[4], state.waterLiquid() + state.waterVapor(), 1e-12);
                    assertTrue(state.volume() > 0 && Double.isFinite(state.enthalpy()) && Double.isFinite(state.internalEnergy()));
                    assertTrue(model.velocityLimit(state) > 0);
                    if (sum(liquid) > 0 && sum(vapor) > 0) twoPhase++;
                }
            }
        }
        System.out.printf("WP5 pilot flash grid: %d states evaluated (%d two-phase), %d wet states below 273.16 K, typed holds %s"
                + " (P4 stage 2b), %d non-convergence%n", evaluated, twoPhase, wetCold, holds, failures.size());
        failures.forEach(failure -> System.out.println("  did not converge: " + failure));
        assertEquals(List.of(), failures, "the equilibrium engine answers every state of the grid");
        assertTrue(evaluated >= 500 && twoPhase > 10, evaluated + " states, " + twoPhase + " two-phase");
        // P5: the 69 typed holds of P4 stage 2b are answers (551 evaluated then, 620 at P5). Since the owner rule of
        // 2026-10-16 the 106 states P5 refused for a data range are answered too (684), and since WP4 the wet feed below
        // 273.16 K (7 temperatures by 6 pressures), the ice hold before: its water is ice beside the dry feed's answer,
        // CO2-I where it forms: 726, the whole grid.
        assertTrue(holds.isEmpty(), "no typed hold on the grid since P5: " + holds);
        assertEquals(42, wetCold);
        assertEquals(726, evaluated);
    }

    /** A P1 row: species, T [K], P [Pa], and the printed density, cp (%) and h (J/mol) deviations, anchor (b). */
    record P1Row(int species, double temperature, double pressure, boolean saturated, String density, String heatCapacity, String enthalpy) {}

    /** P1_ALPHA_AND_LIQUID_PATH_STUDY.md section 2.5 (dense and supercritical states, anchor b) and 2.4 (saturated liquid). */
    static final List<P1Row> P1 = List.of(
            dense(0, 77.36, 5, "+1.53", "-6.07", "64"), dense(0, 77.36, 10, "+1.46", "-6.79", "69"), dense(0, 100, 10, "+1.79", "-1.53", "15"),
            dense(0, 126.2, 3.4, "-14.39", null, "392"), dense(0, 130, 4, "-4.03", "-27.6", "133"), dense(0, 130, 6, "-5.60", "+7.05", "139"),
            dense(0, 130, 10, "-1.55", "+4.14", "52"), dense(0, 150, 10, "-4.05", "-3.75", "68"), dense(0, 300, 10, "+0.24", "+1.00", "-51"),
            dense(1, 110, 10, "+2.14", "-1.39", "18"), dense(1, 150, 10, "+1.76", "+2.10", "20"), dense(1, 190.6, 4.6, "-2.47", null, "263"),
            dense(1, 200, 6, "-5.20", "-22.9", "225"), dense(1, 200, 10, "-4.69", "+4.95", "194"), dense(1, 250, 10, "+0.11", "-4.56", "-70"),
            dense(1, 300, 10, "+0.95", "-0.17", "-121"),
            dense(2, 200, 10, "+2.20", "-3.63", "62"), dense(2, 300, 10, "-2.91", "+6.25", "192"), dense(2, 305.4, 4.9, "-15.96", null, "1045"),
            dense(2, 320, 6, "-0.31", "-13.9", "119"), dense(2, 320, 10, "-5.92", "+4.59", "326"), dense(2, 400, 10, "+0.01", "-2.04", "-158"),
            dense(3, 280, 8, "-3.24", "+9.22", "118"), dense(3, 280, 10, "-2.38", "+6.80", "64"), dense(3, 300, 8, "-10.06", "+22.6", "518"),
            dense(3, 304.5, 7.4, "-1.36", null, "332"), dense(3, 310, 8, "+0.17", "-12.8", "142"), dense(3, 310, 10, "-10.86", "+11.5", "537"),
            dense(3, 320, 8, "+1.99", "-7.07", "-52"), dense(3, 320, 10, "-6.60", "-20.8", "312"), dense(3, 350, 10, "+0.87", "-3.61", "-171"),
            dense(3, 400, 10, "+0.58", "+0.01", "-223"),
            saturated(0, 63.151, "+0.80", "-8.24", "116"), saturated(0, 70, "+1.24", "-6.95", "87"), saturated(0, 77.36, "+1.52", "-5.08", "62"),
            saturated(0, 90, "+1.31", "-0.62", "39"), saturated(0, 100, "+0.17", "+4.48", "49"), saturated(0, 110, "-2.44", "+12.41", "96"),
            saturated(0, 120, "-8.17", "+29.36", "221"),
            saturated(1, 90.6941, "+1.79", "-1.25", "26"), saturated(1, 100, "+2.08", "-0.93", "21"), saturated(1, 111.67, "+2.26", "-0.24", "17"),
            saturated(1, 130, "+1.95", "+1.99", "24"), saturated(1, 150, "+0.33", "+6.64", "71"), saturated(1, 170, "-3.84", "+16.68", "202"),
            saturated(1, 185, "-11.35", "+39.06", "460"),
            saturated(2, 90.368, "-1.93", "-10.62", "557"), saturated(2, 120, "-0.22", "-7.73", "381"), saturated(2, 150, "+1.08", "-6.51", "232"),
            saturated(2, 184.55, "+1.86", "-3.84", "101"), saturated(2, 220, "+1.45", "+0.38", "51"), saturated(2, 250, "-0.56", "+6.03", "117"),
            saturated(2, 280, "-5.76", "+17.99", "385"), saturated(2, 295, "-11.24", "+34.25", "711"),
            saturated(3, 216.592, "+1.09", "-8.92", "204"), saturated(3, 230, "+0.75", "-5.21", "120"), saturated(3, 250, "-0.57", "+1.40", "77"),
            saturated(3, 270, "-3.40", "+10.98", "165"), saturated(3, 290, "-9.26", "+29.32", "499"), saturated(3, 300, "-15.10", "+49.09", "911"));

    static P1Row dense(int species, double t, double megapascal, String rho, String cp, String h) {
        return new P1Row(species, t, megapascal * 1e6, false, rho, cp, h);
    }

    static P1Row saturated(int species, double t, String rho, String cp, String h) {
        return new P1Row(species, t, Double.NaN, true, rho, cp, h);
    }

    /** Model-minus-oracle deviations at one pure state: density %, cp %, and the enthalpy departure h - h_ig(T) in J/mol. */
    record Deviation(double density, double heatCapacity, double enthalpy, double compressibility) {}

    static Deviation deviation(HydrocarbonModel model, int species, double t, double p, Root root, HelmholtzFluid.Root oracleRoot) {
        var eos = model.translated();
        double[] pure = new double[4];
        pure[species] = 1;
        var terms = eos.prepare(t);
        if (root == null) {
            // Supercritical: the lower-Gibbs root (the translation shifts both roots' ln phi equally).
            double liquid = eos.evaluateValues(t, p, pure, Root.LIQUID, terms).logFugacityCoefficientsView()[species];
            double vapour = eos.evaluateValues(t, p, pure, Root.VAPOR, terms).logFugacityCoefficientsView()[species];
            root = liquid < vapour ? Root.LIQUID : Root.VAPOR;
        }
        var values = eos.evaluateValues(t, p, pure, root, terms);
        var reference = oracle(species).stateAtPressure(t, p, oracleRoot);
        var data = model.spine().orElseThrow();
        double departure = values.molarEnthalpy() - (data.idealGas().get(species).enthalpy(t) - data.formationEnthalpies()[species]);
        double referenceDeparture = reference.enthalpy() - oracle(species).state(t, 1e-10).enthalpy();
        return new Deviation((1 / (values.molarVolume() * reference.density()) - 1) * 100,
                (values.heatCapacity() / reference.isobaricHeatCapacity() - 1) * 100, departure - referenceDeparture,
                reference.compressibility());
    }

    @Test
    void reproducesTheP1DirectEvaluationWithTheTr08Anchors() {
        var model = pilot();
        int compared = 0;
        for (P1Row row : P1) {
            var fluid = oracle(row.species());
            double p = row.saturated() ? fluid.saturation(row.temperature()).pressure() : row.pressure();
            Root root;
            HelmholtzFluid.Root oracleRoot;
            if (row.saturated() || row.temperature() < fluid.criticalTemperature() && p > fluid.saturation(row.temperature()).pressure()) {
                root = Root.LIQUID;
                oracleRoot = HelmholtzFluid.Root.LIQUID;
            } else if (row.temperature() < fluid.criticalTemperature() || p < fluid.criticalPressure()) {
                root = Root.VAPOR;
                oracleRoot = HelmholtzFluid.Root.VAPOUR;
            } else {
                root = null;
                oracleRoot = HelmholtzFluid.Root.VAPOUR;
            }
            var d = deviation(model, row.species(), row.temperature(), p, root, oracleRoot);
            // P1 used CoolProp's ethane constants (305.322 K, 4.8722 MPa); the record keeps the bundled 305.32 K, 4.872 MPa.
            boolean ethane = row.species() == 2;
            String at = SPECIES[row.species()] + " " + row.temperature() + " K " + (row.saturated() ? "saturated" : p + " Pa");
            System.out.printf(Locale.ROOT, "WP5 P1 %s: rho %+.3f %% (P1 %s), cp %+.2f %% (P1 %s), h %+.1f J/mol (P1 %s)%n", at, d.density(),
                    row.density(), d.heatCapacity(), row.heatCapacity(), d.enthalpy(), row.enthalpy());
            assertPrinted(row.density(), d.density(), ethane ? 0.02 : 1e-3, at + " density");
            // Inside the declared band cp is steep in (T, P): a few hundredths of a percent separate the two oracles there.
            boolean band = inBand(row.temperature() / fluid.criticalTemperature(), p / fluid.criticalPressure());
            if (row.heatCapacity() != null) assertPrinted(row.heatCapacity(), d.heatCapacity(), ethane || band ? 0.02 : 1e-3, at + " cp");
            assertPrinted(row.enthalpy(), d.enthalpy(), ethane ? 1.0 : 0.05, at + " h");
            compared++;
        }
        assertEquals(P1.size(), compared);
    }

    /** The value equals the printed one to half a unit of its last digit, plus {@code slack}. */
    static void assertPrinted(String printed, double value, double slack, String what) {
        int point = printed.indexOf('.');
        int decimals = point < 0 ? 0 : printed.length() - point - 1;
        double unit = Math.pow(10, -decimals);
        assertEquals(Double.parseDouble(printed), value, 0.5 * unit + slack, what);
    }

    /**
     * The isotherm set: the four species on 9 isobars from 0.1 to 10 MPa, at temperatures from near the triple point to
     * the reference equation's range, against the oracle; the declared critical band (plan section 3 as widened by D7:
     * Tr 0.95-1.1 x Pr 0.8-1.5, Tr 0.90-0.95 x Pr 0.58-0.74, Tr 1.05-1.2 x Pr 2-3) is not scored. Each region has its D7
     * target and, where the cubic does not meet it, a declared bound the test holds instead.
     */
    @Test
    void pilotIsothermsAgainstTheOracleAtTheD7Targets() {
        var model = pilot();
        double[][] temperatures = {
                {70, 77.36, 90, 100, 110, 115, 130, 150, 200, 300, 500, 800, 1000},
                {95, 111.67, 130, 150, 165, 175, 200, 250, 300, 450, 600},
                {100, 150, 184.55, 220, 250, 270, 290, 320, 350, 400, 500, 650},
                {220, 240, 250, 260, 270, 285, 320, 350, 400, 600, 900, 1100}};
        double[] megapascals = {0.1, 0.2, 0.5, 1, 2, 3, 5, 7, 10};
        Map<String, Worst> worst = new java.util.TreeMap<>();
        int band = 0, scored = 0;
        for (int species = 0; species < 4; species++) {
            var fluid = oracle(species);
            double tc = fluid.criticalTemperature(), pc = fluid.criticalPressure();
            for (double t : temperatures[species]) {
                assertTrue(t <= fluid.maximumTemperature(), SPECIES[species] + " " + t + " K inside the reference equation");
                for (double megapascal : megapascals) {
                    double p = megapascal * 1e6, tr = t / tc, pr = p / pc;
                    if (inBand(tr, pr)) {
                        band++;
                        continue;
                    }
                    Region region;
                    Root root;
                    HelmholtzFluid.Root oracleRoot;
                    if (t < tc && p > fluid.saturation(t).pressure()) {
                        region = tr <= 0.8 ? Region.LIQUID_COLD : tr <= 0.9 ? Region.LIQUID : Region.LIQUID_NEAR_CRITICAL;
                        root = Root.LIQUID;
                        oracleRoot = HelmholtzFluid.Root.LIQUID;
                    } else if (t < tc || p < pc) {
                        region = null;
                        root = Root.VAPOR;
                        oracleRoot = HelmholtzFluid.Root.VAPOUR;
                    } else {
                        region = tr <= 1.1 && pr < 2 ? Region.SUPERCRITICAL_BAND_CORNER : Region.SUPERCRITICAL;
                        root = null;
                        oracleRoot = HelmholtzFluid.Root.VAPOUR;
                    }
                    var d = deviation(model, species, t, p, root, oracleRoot);
                    if (region == null) {
                        region = d.compressibility() >= 0.9 ? Region.VAPOUR_DILUTE : d.compressibility() >= 0.8 ? Region.VAPOUR : Region.VAPOUR_DENSE;
                    }
                    worst.computeIfAbsent(SPECIES[species] + " " + region, key -> new Worst()).add(d, t, megapascal);
                    String at = SPECIES[species] + " " + t + " K, " + megapascal + " MPa (" + region + ")";
                    assertTrue(Math.abs(d.density()) <= region.densityBound, at + ": density " + d.density() + " % beyond " + region.densityBound);
                    // Real-gas cp: the D7 floor 0.05 R where cp itself is small does not arise on this grid (cp > 29 J/(mol K)).
                    assertTrue(Math.abs(d.heatCapacity()) <= region.heatCapacityBound, at + ": cp " + d.heatCapacity() + " % beyond " + region.heatCapacityBound);
                    scored++;
                }
            }
        }
        System.out.printf("WP5 pilot isotherms: %d states scored, %d in the declared band (not scored)%n", scored, band);
        worst.forEach((key, w) -> {
            var region = Region.valueOf(key.substring(key.indexOf(' ') + 1));
            System.out.println("  " + key + ": " + w + "; D7 density " + verdict(region.densityTarget, w.density)
                    + ", cp " + verdict(region.heatCapacityTarget, w.heatCapacity));
        });
        // Enthalpy of vaporization, Tr 0.5 to 0.9 (from the triple point): D7 3.5 % (floor 0.2 kJ/mol).
        double hvapWorst = 0;
        var eos = model.translated();
        for (int species = 0; species < 4; species++) {
            var fluid = oracle(species);
            for (double reduced : new double[] {0.5, 0.6, 0.7, 0.8, 0.85, 0.9}) {
                double t = Math.max(reduced * fluid.criticalTemperature(), fluid.tripleTemperature());
                var saturation = fluid.saturation(t);
                double[] pure = new double[4];
                pure[species] = 1;
                var terms = eos.prepare(t);
                double liquid = eos.evaluateValues(t, saturation.pressure(), pure, Root.LIQUID, terms).molarEnthalpy();
                double vapour = eos.evaluateValues(t, saturation.pressure(), pure, Root.VAPOR, terms).molarEnthalpy();
                double reference = saturation.vapour().enthalpy() - saturation.liquid().enthalpy();
                double deviation = (vapour - liquid) / reference - 1;
                hvapWorst = Math.max(hvapWorst, Math.abs(deviation));
                assertTrue(Math.abs(deviation) <= 0.035 || Math.abs(vapour - liquid - reference) <= 200,
                        SPECIES[species] + " hvap at " + t + " K: " + deviation);
            }
        }
        System.out.printf("  hvap Tr 0.5-0.9: worst %.2f %% (D7 3.5 %%)%n", hvapWorst * 100);
        assertTrue(scored > 300, "scored " + scored);
    }

    /**
     * Regions of the D7 table (P0 section 3.2 with the P1 amendments): the D7 target and the bound this test holds. They
     * differ where the cubic misses the target on this grid (vapour density at Z of at least 0.8, 1 % in D7; vapour cp
     * near saturation, 2 % in D7; liquid density at Tr 0.9 to 0.95 outside the band, 5 % in D7; the supercritical corner
     * Tr 1.0-1.1 x Pr 1.5-2 between the band's two boxes, 7 % in D7): those are findings for WP9, printed with their
     * worst state.
     */
    enum Region {
        LIQUID_COLD(3, 3, 10, 10),
        LIQUID(3, 3, 20, 20),
        LIQUID_NEAR_CRITICAL(5, 10, Double.NaN, 25),
        VAPOUR_DILUTE(1, 1.5, 2, 10),
        VAPOUR(1, 2.5, Double.NaN, 20),
        VAPOUR_DENSE(Double.NaN, 3, Double.NaN, 25),
        SUPERCRITICAL(7, 7, 10, 10),
        SUPERCRITICAL_BAND_CORNER(7, 10, 10, 10);

        final double densityTarget, densityBound, heatCapacityTarget, heatCapacityBound;

        Region(double densityTarget, double densityBound, double heatCapacityTarget, double heatCapacityBound) {
            this.densityTarget = densityTarget;
            this.densityBound = densityBound;
            this.heatCapacityTarget = heatCapacityTarget;
            this.heatCapacityBound = heatCapacityBound;
        }
    }

    static String verdict(double target, double worst) {
        if (Double.isNaN(target)) return "no target";
        return String.format(Locale.ROOT, "%.0f %% %s", target, Math.abs(worst) <= target ? "met" : "NOT met");
    }

    static boolean inBand(double tr, double pr) {
        return tr >= 0.95 && tr <= 1.1 && pr >= 0.8 && pr <= 1.5 || tr >= 0.90 && tr <= 0.95 && pr >= 0.58 && pr <= 0.74
                || tr >= 1.05 && tr <= 1.2 && pr >= 2 && pr <= 3;
    }

    static final class Worst {
        int count;
        double density, densityT, densityP, heatCapacity, heatCapacityT, heatCapacityP, enthalpy;

        void add(Deviation d, double t, double megapascal) {
            count++;
            if (Math.abs(d.density()) > Math.abs(density)) { density = d.density(); densityT = t; densityP = megapascal; }
            if (Math.abs(d.heatCapacity()) > Math.abs(heatCapacity)) { heatCapacity = d.heatCapacity(); heatCapacityT = t; heatCapacityP = megapascal; }
            if (Math.abs(d.enthalpy()) > Math.abs(enthalpy)) enthalpy = d.enthalpy();
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "n %d, density %+.2f %% (%.2f K, %.1f MPa), cp %+.2f %% (%.2f K, %.1f MPa), h departure %+.0f J/mol",
                    count, density, densityT, densityP, heatCapacity, heatCapacityT, heatCapacityP, enthalpy);
        }
    }

    @Test
    void aSpeciesBelowItsSpineIsCarriedOnItsHeldHeatCapacity() {
        var model = pilot();
        var eos = model.translated();
        // Methane's spine starts at its triple point, 90.6941 K; CO2's r2 spine at 90 K. Below them the ideal-gas cp is held
        // (owner rule of 2026-10-16): liquid methane at 85 K and an LNG with 30 % CO2 at 80 K are states, not refusals.
        var methane = eos.evaluateValues(85, 1e5, new double[] {0, 1, 0, 0}, Root.LIQUID, eos.prepare(85));
        assertTrue(Double.isFinite(methane.molarEnthalpy()) && methane.heatCapacity() > 0);
        var mixed = eos.evaluateValues(80, 1e5, new double[] {0.6, 0.1, 0, 0.3}, Root.LIQUID, eos.prepare(80));
        assertTrue(Double.isFinite(mixed.molarEnthalpy()) && mixed.heatCapacity() > 0);
        // Pure nitrogen at 70 K, inside its own spine.
        var values = eos.evaluateValues(70, 1e5, new double[] {1, 0, 0, 0}, Root.LIQUID, eos.prepare(70));
        assertTrue(Double.isFinite(values.molarEnthalpy()) && values.heatCapacity() > 0);
        // The network's own entry carries it too, with the same values.
        var phase = model.phase(85, 1e5, new double[] {0, 1, 0, 0}, Root.LIQUID);
        assertEquals(methane.molarEnthalpy(), phase.molarEnthalpy(), 1e-9 * Math.abs(methane.molarEnthalpy()));
    }

    @Test
    void theNetworkRevisionIsTheThermodynamicIdentityAndMovesWithTheSpineAndTheInteractions() {
        var catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var model = pilot();
        // The network's revision is its package's identity on the network family (main keeps the revision, not the identity).
        var identity = ThermoIdentity.of(catalog, PILOT, HydrocarbonModel.NETWORK_FAMILY, catalog.spineRevision(PILOT),
                com.wormzjl.icheme.science.thermo.phase.WaterParticipation.SEPARATE_FREE_WATER);
        assertEquals(identity.revision(), model.revision());
        assertEquals(catalog.fluidRevision(PILOT), identity.packageRevision());
        assertEquals(HydrocarbonModel.NETWORK_FAMILY, identity.evaluatorFamily());
        assertEquals("pr78_translated_v1:direct-liquid-v1:tp-engine-v1:range-continuation-v1:spine-sensible-datum-v1", identity.evaluatorFamily());
        assertEquals(catalog.spineRevision(PILOT), identity.spineRevision());
        assertEquals(ThermoIdentity.SEPARATE_FREE_WATER_FORMULATION + ":" + catalog.requirePackage(PILOT).water().revision(),
                identity.waterModelRevision());
        assertTrue(model.revision().startsWith("thermo-identity-v2;"), model.revision());
        System.out.println("WP5 pilot network revision: " + model.revision());
        String base = model.revision();
        // Each part is a declared revision (owner decision 2026-10-26: nothing hashed), so each edit below bumps the
        // revision of the record it changes.
        // A spine edit (the methane standard entropy): the spine part and so the revision move.
        var entropy = PilotCryogenicTestCatalog.parseWith("data/icheme/materials/spine/methane.json",
                o -> { o.getAsJsonObject("standard_entropy").addProperty("value_j_per_mol_kelvin", 186.3703); PilotCryogenicTestCatalog.bump(o); });
        var entropyModel = new HydrocarbonModel(entropy, PILOT);
        assertTrue(!base.equals(entropyModel.revision()) && entropy.fluidRevision(PILOT).equals(identity.packageRevision())
                && !entropy.spineRevision(PILOT).equals(identity.spineRevision()), "a spine edit moves the spine part only");
        // An interaction edit (A of the CH4/N2 group pair, declared with the group matrix's own revision, which the package's
        // identity carries): the package part moves.
        var interaction = PilotCryogenicTestCatalog.parseWith("data/icheme/materials/group_interactions/eppr78_2022.json", o -> {
                    for (var element : o.getAsJsonArray("pairs")) {
                        var pair = element.getAsJsonObject();
                        if (pair.get("first").getAsString().equals("CH4") && pair.get("second").getAsString().equals("N2")) pair.addProperty("a_mpa", 30.89);
                    }
                    PilotCryogenicTestCatalog.bump(o);
                });
        var interactionModel = new HydrocarbonModel(interaction, PILOT);
        assertTrue(!base.equals(interactionModel.revision()) && interaction.spineRevision(PILOT).equals(identity.spineRevision())
                && !interaction.fluidRevision(PILOT).equals(identity.packageRevision()), "an interaction edit moves the package part only");
        // A text edit of a spine moves nothing.
        var text = PilotCryogenicTestCatalog.parseWith("data/icheme/materials/spine/methane.json", o -> o.addProperty("source", "edited text"));
        assertEquals(base, new HydrocarbonModel(text, PILOT).revision());
        // D10: the formation enthalpy moves the identity and the formation reference, not the network's energies.
        var formation = PilotCryogenicTestCatalog.parseWith("data/icheme/materials/spine/methane.json",
                o -> { o.getAsJsonObject("formation_enthalpy").addProperty("value_j_per_mol", -73513); PilotCryogenicTestCatalog.bump(o); });
        var shifted = new HydrocarbonModel(formation, PILOT);
        assertTrue(!base.equals(shifted.revision()));
        assertEquals(-73513.0, shifted.spine().orElseThrow().formationReference().offsetJoulesPerMole(1), 0.0);
        double[] x = {0.2, 0.6, 0.1, 0.1};
        double before = model.phase(300, 2e6, x, Root.VAPOR).molarEnthalpy();
        double after = shifted.phase(300, 2e6, x, Root.VAPOR).molarEnthalpy();
        assertEquals(before, after, 1e-9 * Math.abs(before) + 1e-9, "the sensible datum does not see the formation enthalpy");
        // The coefficient path keeps its own layout (LegacyNetworkPathPinTest holds it bit for bit).
        var legacy = new HydrocarbonModel(catalog, "icheme:tjl20_methane_nitrogen");
        assertEquals(LegacyNetworkPathPinTest.REVISION, legacy.revision());
    }

    @Test
    void theColumnRefusesASpinePackageTyped() {
        // The pilot without its crystal list (P6: with it, the column refuses the crystals first, CrystalPackageConsumersTest).
        var spineOnly = PilotCryogenicTestCatalog.parseWith("data/icheme/materials/packages/pilot_cryogenic.json", o -> o.remove("crystals"));
        var refused = assertThrows(com.wormzjl.icheme.science.material.IdealGasFitUnavailable.class,
                () -> com.wormzjl.icheme.science.material.MaterialRuntime.with(spineOnly, PILOT,
                        () -> com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo.fromRegisteredPackage(PILOT)));
        assertEquals(PILOT, refused.packageId());
        assertEquals("the V3 column", refused.consumer());
        assertTrue(refused.getMessage().contains("reference spine"), refused.getMessage());
        // A column package is unaffected.
        var column = com.wormzjl.icheme.science.material.MaterialRuntime.with(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), "icheme:tjl20_methane",
                () -> com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane"));
        assertEquals("icheme:tjl20_methane", column.packageId());
    }

    private static double sum(double[] values) {
        double total = 0;
        for (double value : values) total += value;
        return total;
    }
}
