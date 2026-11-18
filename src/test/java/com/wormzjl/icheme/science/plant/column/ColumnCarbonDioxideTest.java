package com.wormzjl.icheme.science.plant.column;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.plant.column.ColumnCycle.Account;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Carbon dioxide is a column component (batch 2026-11-18-co2-column-input). The network package {@code icheme:refinery}
 * admits the CO2-I crystal; a column whose feed holds CO2 builds on it and solves, and the crystal equilibrium of each solved
 * product on the network's thermo ({@link ColumnSolve#frozen}, owner D6) decides whether dry ice forms. Both columns run
 * through {@link ColumnSolve#run}, the solve a placed column's worker runs on its feed island's model, the feed and the
 * products on the world list.
 *
 * <ul>
 * <li>A deethaniser, 2 mol % CO2 in an ethane, propane and n-butane feed, 1 MPa at the top with a 230 K condenser, every
 * node at 230 K or warmer (the condenser the coldest, the crystal equilibrium run on its distillate): it solves, the
 * CO2 leaves with the distillate, and a cycle at the table's reference conserves every compound exactly and closes its
 * energy.</li>
 * <li>A CO2 and propane column at 0.6 MPa with a 200 K condenser, below CO2's triple point: its distillate holds dry ice, so
 * the solve is {@link ColumnSolve.Frozen} naming carbon dioxide, CO2-I and the distillate.</li>
 * <li>A tray colder than every product ({@link ColumnSolve#coldestTray}): the deethaniser's profile with one tray made a
 * CO2-rich liquid at 195 K is frozen at that tray; the deethaniser's own coldest node is its condenser, no colder than its
 * distillate, so it runs no second test.</li>
 * <li>A trace of CO2 in liquid methane at 185 K and 3 MPa (a demethaniser's top): the crystals compete, the equilibrium
 * finds none.</li>
 * </ul>
 * The counter printed is the crystal equilibria an accepted solve runs: the products the gate lets through, plus the
 * coldest tray where it is colder than all of them.
 */
class ColumnCarbonDioxideTest {
    private static final double SECONDS = 5;
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = FluidMaterialCatalog.networkPackage(CATALOG);
    private static final FluidThermodynamics WHOLE = MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK));

    @Test void aDeethaniserWithCarbonDioxideSolvesAndSendsItOverhead() {
        var ids = List.of("Ethane", "Propane", "N-butane", "CarbonDioxide");
        var design = design(ids, new double[] {25, 45, 28, 2}, 300, 10, 5, 1e6, 700, 230, 2, 1.2e6);
        var model = WHOLE.on(ids, true);
        var axis = model.whole().components();
        double[] feed = onTheWorld(design, axis);
        var setpoints = Setpoints.of(ColumnOperatingPoint.of(design));
        var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, setpoints, new ColumnSolve.Taken(feed, Double.NaN, List.of()), null),
                model, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, () -> {});
        var solved = assertInstanceOf(ColumnSolve.Solved.class, outcome, outcome::toString);
        var nodes = solved.inspection().nodes();
        double coldest = nodes.stream().mapToDouble(n -> n.temperatureKelvin()).min().orElseThrow();
        System.out.println("CO2_DEETHANISER temperatures=" + nodes.stream().map(n -> String.format(java.util.Locale.ROOT, "%.2f", n.temperatureKelvin())).toList());
        assertEquals(230, coldest, 1e-6, "the condenser is the coldest node");

        var table = solved.table();
        assertEquals(axis, table.axis());
        double[][] split = table.split(feed);
        int co2 = axis.indexOf("CarbonDioxide"), distillate = table.product(Port.DISTILLATE, 0);
        assertTrue(distillate >= 0, "a distillate: " + table.products());
        for (int i = 0; i < axis.size(); i++) {
            double out = 0;
            for (double[] row : split) out += row[i];
            assertEquals(feed[i], out, 1e-12 * Arrays.stream(feed).sum(), "what leaves of " + axis.get(i) + " is what entered");
        }
        double overhead = split[distillate][co2] / feed[co2];
        System.out.println("CO2_DEETHANISER co2ToDistillate=" + overhead + " products=" + table.products());
        assertTrue(overhead > .95, "carbon dioxide, lighter than ethane at the top, leaves overhead: " + overhead);

        // A cycle at the reference: every compound conserved, the table's condenser duty the network's closure.
        double rate = Arrays.stream(feed).sum();
        var account = new Account(scale(feed, SECONDS), table.reference().feedEnthalpy() * rate * SECONDS);
        double[] room = new double[table.products().size()];
        Arrays.fill(room, Double.MAX_VALUE);
        double[][] h = solved.enthalpy();
        var step = ColumnCycle.step(table, setpoints, SECONDS, account, List.of(), room, (k, n) -> dot(h[k], n), false);
        assertFalse(step.tripped());
        assertEquals(1, step.alpha(), 1e-12);
        assertEquals(0, step.departure(), 1e-9, "the energy closes at the reference");

        // The crystal equilibria this solve ran: one per product the gate lets through, and none for its coldest node.
        var own = model.components();
        int products = 0;
        double tested = Double.POSITIVE_INFINITY;
        for (int k = 0; k < split.length; k++) {
            double[] n = new double[own.size()];
            for (int i = 0; i < own.size(); i++) n[i] = split[k][axis.indexOf(own.get(i))];
            if (!(Arrays.stream(n).sum() > 0)) continue;
            tested = Math.min(tested, table.products().get(k).temperature());
            if (model.crystalsCompete(table.products().get(k).temperature(), n)) products++;
        }
        assertNull(ColumnSolve.coldestTray(model, solved.inspection(), tested, () -> {}), "the coldest node is the condenser");
        System.out.println("CO2_DEETHANISER crystalEquilibria before=" + products + " after=" + products + " coldestNode=" + coldest + " coldestProduct=" + tested);
        assertFalse(coldest < tested, "no node is colder than the coldest product");

        // One tray of the same profile made a CO2-rich liquid at 195 K, colder than every product: frozen there.
        var nodes2 = new java.util.ArrayList<>(nodes);
        int k = 3, c = design.componentBasis().componentIds().indexOf("CarbonDioxide"), p = design.componentBasis().componentIds().indexOf("Propane");
        var x = new java.util.ArrayList<>(java.util.Collections.nCopies(design.componentBasis().componentCount(), 0.0));
        x.set(c, .9);
        x.set(p, .1);
        var tray = nodes.get(k);
        nodes2.set(k, new com.wormzjl.icheme.science.column.v3.V3ColumnInspection.Node(195, tray.pressurePascal(), tray.liquidMolPerSecond(), tray.vaporMolPerSecond(),
                0, 0, x, tray.vaporFractions()));
        var cold = new com.wormzjl.icheme.science.column.v3.V3ColumnInspection(solved.inspection().input(), nodes2, solved.inspection().audit());
        var frozen = ColumnSolve.coldestTray(model, cold, tested, () -> {});
        assertNotNull(frozen, "a CO2-rich liquid at 195 K holds dry ice");
        assertEquals("tray " + k, frozen.node());
        assertEquals(new ColumnSolve.Solid("CarbonDioxide", "icheme:crystal_carbon_dioxide_i"), frozen.solid());
        assertEquals(195, frozen.temperature());
        System.out.println("CO2_COLD_TRAY " + frozen.text());
        assertTrue(frozen.text().contains("tray 3") && frozen.text().contains("195.0 K") && frozen.text().contains("crystal_carbon_dioxide_i"), frozen.text());
    }

    @Test void aTraceOfCarbonDioxideInLiquidMethaneAt185KelvinHoldsNoDryIce() {
        var model = WHOLE.on(List.of("Methane", "CarbonDioxide"), true);
        var ids = model.components();
        double[] n = new double[ids.size()];
        n[ids.indexOf("Methane")] = 99.5;
        n[ids.indexOf("CarbonDioxide")] = .5;
        assertTrue(model.crystalsCompete(185, n), "the crystals compete at 185 K");
        double h = model.flashTP(185, 3e6, n.clone(), () -> {}).enthalpy();
        assertNull(ColumnSolve.frozen(model, 185, 3e6, n, h, () -> {}), "0.5 mol % CO2 dissolves in liquid methane at 185 K");
    }

    @Test void aColumnWhoseDistillateHoldsDryIceIsFrozen() {
        var ids = List.of("Propane", "CarbonDioxide");
        var design = design(ids, new double[] {20, 80}, 260, 6, 3, 6e5, 700, 200, 2, 1e6);
        var model = WHOLE.on(ids, true);
        double[] feed = onTheWorld(design, model.whole().components());
        var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, Setpoints.of(ColumnOperatingPoint.of(design)),
                new ColumnSolve.Taken(feed, Double.NaN, List.of()), null), model, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, () -> {});
        System.out.println("CO2_COLD outcome=" + outcome);
        var frozen = assertInstanceOf(ColumnSolve.Frozen.class, outcome, outcome::toString);
        assertEquals(new ColumnSolve.Solid("CarbonDioxide", "icheme:crystal_carbon_dioxide_i"), frozen.solid());
        assertEquals("distillate", frozen.node());
        assertEquals(200, frozen.temperature(), 1e-6, "at the condenser's temperature");
    }

    /** A dry column on the network package's view of {@code ids}: no draws, steam or pumparounds, a prescribed drop. */
    private static V3ColumnInput design(List<String> ids, double[] flows, double feedTemperature, int stages, int feedStage, double top, double drop,
                                        double condenser, double reflux, double reboiler) {
        var view = CATALOG.restrict(NETWORK, ids);
        var basis = MaterialRuntime.with(view, NETWORK, () -> V3PengRobinsonThermo.fromRegisteredPackage(NETWORK)).componentBasis();
        double[] feed = new double[basis.componentCount()];
        for (int k = 0; k < ids.size(); k++) feed[basis.componentIds().indexOf(ids.get(k))] = flows[k];
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, NETWORK, "test:co2", basis, feed, feedTemperature, stages, feedStage, top, drop,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(condenser), new V3ColumnSpecification.OrganicRefluxRatio(reflux),
                        new V3ColumnSpecification.ReboilerDuty(reboiler)), List.of(), List.of(), List.of(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    private static double[] onTheWorld(V3ColumnInput design, List<String> axis) {
        double[] feed = new double[axis.size()];
        for (int c = 0; c < design.componentBasis().componentCount(); c++) feed[axis.indexOf(design.componentBasis().componentId(c))] += design.feedComponentMolarFlowsMolPerSecond()[c];
        return feed;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double[] scale(double[] v, double s) {
        double[] out = v.clone();
        for (int i = 0; i < out.length; i++) out[i] *= s;
        return out;
    }
}
