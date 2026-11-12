package com.wormzjl.icheme.science.flowsheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The sheet's column block with water where the shared rule puts it (batch 2026-11-11-aqueous-solvents, W5 D3 and D5, section 6
 * of {@code W5_COLUMN_PLAN.md}), on the network package {@code icheme:refinery}, which holds ethanol since W6 (W5 ran it on a test
 * package of the same compounds; its numbers stand).
 * The column is E1's rectifier: 10 trays, feed on tray 8, 1 atm, condenser 70 C, reflux ratio 3, reboiler 1.77 MW.
 */
final class FlowsheetAqueousColumnTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, NETWORK).components();
    private static final double ATM = 101_325;

    private static V3ColumnInput rectifier(List<V3SteamFeedSpec> steam) {
        return rectifier(steam, 343.15);
    }

    private static V3ColumnInput rectifier(List<V3SteamFeedSpec> steam, double condenser) {
        var ids = List.of("Ethanol", "Water");
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, NETWORK, "icheme:none", new V3ComponentBasis(ids), new double[] {10, 90}, 360,
                10, 8, ATM, 0, List.of(new V3ColumnSpecification.CondenserOutletTemperature(condenser), new V3ColumnSpecification.OrganicRefluxRatio(3),
                new V3ColumnSpecification.ReboilerDuty(1.77e6)), List.of(), steam, List.of(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    private static FlowsheetRun run(V3ColumnInput design, Map<String, Double> amounts, double temperature) {
        var unit = new Flowsheet.Unit("T", "column", "T", 0, 0, ColumnDoor.from(design, ColumnComponents.Condenser.EXCHANGER));
        var streams = List.of(new Flowsheet.Stream("feed", "feed", null, new Flowsheet.End("T", "feed"), new Flowsheet.Feed(temperature, ATM, amounts, List.of())),
                new Flowsheet.Stream("overhead", "overhead", new Flowsheet.End("T", "overhead"), null, null),
                new Flowsheet.Stream("distillate", "distillate", new Flowsheet.End("T", "distillate"), null, null),
                new Flowsheet.Stream("water", "water", new Flowsheet.End("T", "water"), null, null),
                new Flowsheet.Stream("bottoms", "bottoms", new Flowsheet.End("T", "bottoms"), null, null));
        return FlowsheetRuns.run(new Flowsheet(new Flowsheet.Basis(NETWORK, "test", AXIS), List.of(unit), streams, FlowsheetSolver.Settings.DEFAULT), CATALOG, () -> {
        });
    }

    private static Map<String, Double> wash(double ethanol, double water) {
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put("Ethanol", ethanol);
        amounts.put("Water", water);
        return amounts;
    }

    private static double component(FlowsheetRun run, String stream, String id) {
        var s = run.streams().get(stream);
        return s == null ? 0 : s.amounts()[AXIS.indexOf(id)];
    }

    /** A wash (an aqueous liquid) enters whole on the axis: no {@code column.liquid_water}; each component closes over the products. */
    @Test void aWashFeedEntersTheColumnOnItsAxisAndItsProductsClose() {
        var run = run(rectifier(List.of()), wash(10, 90), 359.84);
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        for (String id : List.of("Ethanol", "Water")) {
            double out = 0;
            for (String product : List.of("overhead", "distillate", "water", "bottoms")) out += component(run, product, id);
            double in = id.equals("Ethanol") ? 10 : 90;
            assertEquals(in, out, 1e-9 * in, id);
        }
        double d = run.streams().get("distillate").total(), xd = component(run, "distillate", "Ethanol") / d;
        System.out.printf(Locale.ROOT, "W5 sheet wash: distillate %.4f ethanol at %.3f mol/s, water product %.3e mol/s, mass in %.6e out %.6e%n",
                xd, d, run.streams().get("water").total(), run.massIn(), run.massOut());
        assertEquals(0.8075, xd, 0.01, "E1's distillate");
        assertEquals(0, run.streams().get("water").total(), 1e-12, "no free water: the water is on the axis");
        assertTrue(run.reports().get("T").notes().stream().noneMatch(n -> n.key().startsWith("column.two_liquids")), "a wash is one liquid");
    }

    /** A steam row with a flow is refused where water is on the axis (owner choice 2 a). */
    @Test void aSteamRowIsRefusedWithWaterOnTheAxis() {
        var run = run(rectifier(List.of(new V3SteamFeedSpec(11, 5, 420))), wash(10, 90), 359.84);
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertEquals("column.steam_on_axis", run.log().getFirst().key(), () -> "log " + run.log());
    }

    /**
     * Ethanol 40, water 50 and n-hexane 10 mol/s at 345 K, the condenser at 320 K: hexane and water do not mix. The column solves
     * the liquid as one (it converges or fails typed); where a liquid would settle the block warns, naming the trays. On the
     * running column this feed settles on tray 8 and in the distillate ({@code ColumnWaterTest}).
     */
    @Test void aLiquidThatWouldSettleIsANotice() {
        var amounts = wash(40, 50);
        amounts.put("N-hexane", 10.);
        var run = run(rectifier(List.of(), 320), amounts, 345);
        System.out.printf(Locale.ROOT, "W5 sheet ethanol, water, hexane: %s; notes %s; log %s%n", run.status(), run.reports().get("T") == null ? "-" : run.reports().get("T").notes(), run.log());
        if (run.status() == FlowsheetRun.Status.FAILED) {
            assertEquals("column.failed", run.log().getFirst().key(), "a typed failure");
            return;
        }
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        var notes = run.reports().get("T").notes();
        assertTrue(notes.stream().anyMatch(n -> n.key().startsWith("column.two_liquids")), () -> "notes " + notes);
        notes.stream().filter(n -> n.key().equals("column.two_liquids")).forEach(n -> assertFalse(n.arguments().getFirst().isBlank(), "names the trays"));
    }

    /** A crude sheet with 1e-6 of ethanol stays off the axis (under the share): its water is steam, and free water leaves. */
    @Test void aCrudeWithATraceOfEthanolKeepsItsSteamAndFreeWater() {
        var catalog = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog();
        var base = catalog.presets().column("tia_juana").input(catalog);
        var amounts = new LinkedHashMap<String, Double>();
        double total = 0;
        for (int i = 0; i < base.feedComponentMolarFlowsMolPerSecond().length; i++) {
            double flow = base.feedComponentMolarFlowsMolPerSecond()[i];
            if (flow > 0) amounts.put(base.componentBasis().componentId(i), flow);
            total += flow;
        }
        amounts.put("Water", 50.);
        amounts.put("Ethanol", 1e-6 * (total + 50));
        double feedTray = base.topPressurePascal() + (base.feedStageNumber() - 1) * base.stagePressureDropPascal();
        var unit = new Flowsheet.Unit("T", "column", "T", 0, 0, ColumnDoor.from(base, ColumnComponents.Condenser.AIR_COOLER));
        var streams = new java.util.ArrayList<>(List.of(new Flowsheet.Stream("feed", "feed", null, new Flowsheet.End("T", "feed"),
                        new Flowsheet.Feed(base.feedTemperatureKelvin(), feedTray, amounts, List.of())),
                new Flowsheet.Stream("overhead", "overhead", new Flowsheet.End("T", "overhead"), null, null),
                new Flowsheet.Stream("distillate", "distillate", new Flowsheet.End("T", "distillate"), null, null),
                new Flowsheet.Stream("water", "water", new Flowsheet.End("T", "water"), null, null),
                new Flowsheet.Stream("bottoms", "bottoms", new Flowsheet.End("T", "bottoms"), null, null)));
        for (int row = 1; row <= base.sideDraws().size(); row++) streams.add(new Flowsheet.Stream("draw " + row, "draw " + row, new Flowsheet.End("T", "draw/" + row), null, null));
        var run = FlowsheetRuns.run(new Flowsheet(new Flowsheet.Basis(NETWORK, "test", AXIS), List.of(unit), streams, FlowsheetSolver.Settings.DEFAULT), CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        double free = run.streams().get("water").total();
        System.out.printf(Locale.ROOT, "W5 crude with 1e-6 ethanol: free water %.3f mol/s%n", free);
        assertTrue(free > 0, "the steam condenses overhead and leaves as free water");
        assertFalse(run.reports().get("T").display().inspection().orElseThrow().input().componentBasis().componentIds().contains("Water"),
                "off the axis the column runs on the list without water");
    }
}
