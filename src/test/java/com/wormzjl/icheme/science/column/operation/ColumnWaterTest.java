package com.wormzjl.icheme.science.column.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnProblemResolver;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotSolventTestCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The shared water rule and the running column with water on its axis (batch 2026-11-11-aqueous-solvents, W5 D3), on the test
 * network {@link PilotSolventTestCatalog}: E1's rectifier on a wash island.
 */
class ColumnWaterTest {
    private static final MaterialCatalog CATALOG = PilotSolventTestCatalog.catalog();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final FluidThermodynamics WHOLE = FluidThermodynamics.forNetwork(CATALOG, NETWORK);

    private static V3ColumnInput rectifier(List<V3SteamFeedSpec> steam) {
        return rectifier(steam, 359.84, 343.15);
    }

    private static V3ColumnInput rectifier(List<V3SteamFeedSpec> steam, double feed, double condenser) {
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, NETWORK, "icheme:none", new V3ComponentBasis(List.of("Ethanol", "Water")), new double[] {10, 90},
                feed, 10, 8, 101_325, 0, List.of(new V3ColumnSpecification.CondenserOutletTemperature(condenser),
                new V3ColumnSpecification.OrganicRefluxRatio(3), new V3ColumnSpecification.ReboilerDuty(1.77e6)), List.of(), steam, List.of(),
                V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    private static double[] wash(double ethanol, double water) {
        var world = WHOLE.components();
        double[] feed = new double[world.size()];
        feed[world.indexOf("Ethanol")] = ethanol;
        feed[world.indexOf("Water")] = water;
        return feed;
    }

    /** Choice 1 a: a compound that names a homomorph above 1e-3 of the feed's moles puts water on the axis; a hydrocarbon never does. */
    @Test void theRuleReadsTheHomomorphAndTheShare() {
        var world = WHOLE.components();
        assertTrue(ColumnWater.onAxis(CATALOG, NETWORK, world, wash(10, 90)));
        assertTrue(ColumnWater.onAxis(CATALOG, NETWORK, world, wash(1.1e-3, 1 - 1.1e-3)));
        assertFalse(ColumnWater.onAxis(CATALOG, NETWORK, world, wash(0.9e-3, 1 - 0.9e-3)), "under the share");
        double[] crude = new double[world.size()];
        crude[world.indexOf("N-hexane")] = 10;
        crude[world.indexOf("Water")] = 5;
        assertFalse(ColumnWater.onAxis(CATALOG, NETWORK, world, crude), "a hydrocarbon names no homomorph");
    }

    /** The running column on a wash island: water is one more compound of the split table, in the distillate and the bottoms. */
    @Test void aWashIslandsTableSplitsTheWaterBetweenDistillateAndBottoms() {
        var model = WHOLE.on(List.of("Ethanol"), true);
        var design = rectifier(List.of());
        var setpoints = Setpoints.of(ColumnOperatingPoint.of(design));
        var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, setpoints, new ColumnSolve.Taken(wash(10, 90), Double.NaN, List.of()), null),
                model, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, () -> {});
        var solved = assertInstanceOf(ColumnSolve.Solved.class, outcome, outcome::toString);
        var table = solved.table();
        int water = table.axis().indexOf("Water"), ethanol = table.axis().indexOf("Ethanol");
        double distillate = 0, bottoms = 0, distillateEthanol = 0;
        for (var product : table.products()) {
            if (product.port() == Port.DISTILLATE) { distillate += product.share(water); distillateEthanol += product.share(ethanol); }
            if (product.port() == Port.BOTTOMS) bottoms += product.share(water);
        }
        System.out.printf(Locale.ROOT, "W5 running wash column: water to distillate %.5f, to bottoms %.5f; ethanol to distillate %.4f; note '%s'; ports %s%n",
                distillate, bottoms, distillateEthanol, solved.note(), table.products().stream().map(p -> p.port() + " " + Arrays.toString(p.recovery())).toList());
        assertTrue(distillate > 0 && bottoms > 0, "water in the distillate and the bottoms");
        assertEquals(1, distillate + bottoms, 1e-12, "no other product takes water: no steam, no free water");
        assertTrue(distillateEthanol > 0.8, "the rectifier takes the ethanol overhead: " + distillateEthanol);
        assertTrue(solved.note().isEmpty(), "a wash is one liquid: " + solved.note());
    }

    /**
     * Ethanol 40, water 50 and n-hexane 10 mol/s, the condenser at 320 K (D5): the column solves the liquid as one, and the note
     * names the tray whose liquid would settle and the distillate, a heterogeneous overhead.
     */
    @Test void aLiquidThatWouldSettleIsANote() {
        var model = WHOLE.on(List.of("Ethanol", "N-hexane"), true);
        var design = rectifier(List.of(), 345, 320);
        double[] feed = wash(40, 50);
        feed[WHOLE.components().indexOf("N-hexane")] = 10;
        var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, Setpoints.of(ColumnOperatingPoint.of(design)), new ColumnSolve.Taken(feed, Double.NaN, List.of()), null),
                model, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, () -> {});
        var solved = assertInstanceOf(ColumnSolve.Solved.class, outcome, outcome::toString);
        assertEquals("two liquids on trays 8; the distillate would settle into two liquids", solved.note());
    }

    /** A steam row with a flow on a wash island is refused with its reason. */
    @Test void aSteamRowIsRefusedOnAWashIsland() {
        var model = WHOLE.on(List.of("Ethanol"), true);
        var design = rectifier(List.of(new V3SteamFeedSpec(11, 5, 420)));
        var setpoints = Setpoints.of(ColumnOperatingPoint.of(design));
        var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, setpoints,
                new ColumnSolve.Taken(wash(10, 90), Double.NaN, List.of(new ColumnSolve.Steam(5, 420, Double.NaN))), null), model,
                V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, () -> {});
        var failed = assertInstanceOf(ColumnSolve.Failed.class, outcome, outcome::toString);
        assertEquals(V3ColumnProblemResolver.STEAM_WITH_WATER_ON_AXIS, failed.reason());
    }
}
