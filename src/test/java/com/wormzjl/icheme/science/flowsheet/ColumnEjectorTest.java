package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.flowsheet.unit.EjectorUnit;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Main plan W10's gate on the column block (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.12): a small vacuum
 * sheet, the default preset's column with its top under ambient pressure. Its Motive steam port, free and wired, closes mass, energy
 * and components across the block: the steam leaves as free water, and its heat joins the condenser's duty. A slow gate: two solves.
 */
final class ColumnEjectorTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final V3ColumnInput BASE = CATALOG.presets().column(CATALOG.presets().network().defaultColumnPreset()).input(CATALOG);
    private static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, NETWORK).components();
    private static final FlowThermo THERMO = new FlowThermo(MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK)), CATALOG, NETWORK, () -> {
    });
    private static final int WATER = AXIS.size() - 1;
    /** The vacuum top, Pa: under ambient, so the block draws its ejector. */
    private static final double TOP = .8e5;

    private static Params door() {
        var door = ColumnDoor.from(BASE, ColumnComponents.Condenser.AIR_COOLER);
        door.json().addProperty("top_pressure", TOP);
        return door;
    }

    private static Flowsheet.Feed crude() {
        var ids = BASE.componentBasis().componentIds();
        var amounts = new LinkedHashMap<String, Double>();
        double[] flows = BASE.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < flows.length; i++) if (flows[i] > 0) amounts.put(ids.get(i), flows[i]);
        double tray = TOP + (BASE.feedStageNumber() - 1) * BASE.stagePressureDropPascal();
        return new Flowsheet.Feed(BASE.feedTemperatureKelvin(), tray, amounts, List.of());
    }

    /** The column on the crude, its products free, its Motive port free or wired to {@code motive}. */
    private static Flowsheet sheet(Flowsheet.Feed motive) {
        var units = List.of(new Flowsheet.Unit("T", "column", "T-101", 0, 0, door()));
        var streams = new ArrayList<Flowsheet.Stream>();
        streams.add(new Flowsheet.Stream("feed", "feed", null, new Flowsheet.End("T", "feed"), crude()));
        if (motive != null) streams.add(new Flowsheet.Stream("steam", "steam", null, new Flowsheet.End("T", EjectorUnit.MOTIVE), motive));
        for (var port : List.of("overhead", "distillate", "water", "bottoms")) streams.add(new Flowsheet.Stream(port, port, new Flowsheet.End("T", port), null, null));
        for (int row = 1; row <= BASE.sideDraws().size(); row++) streams.add(new Flowsheet.Stream("draw " + row, "draw " + row, new Flowsheet.End("T", "draw/" + row), null, null));
        return new Flowsheet(new Flowsheet.Basis(NETWORK, "test", AXIS), units, streams, FlowsheetSolver.Settings.DEFAULT);
    }

    private static FlowsheetRun run(Flowsheet sheet, FlowsheetRun last) {
        return FlowsheetEngine.run(sheet, CATALOG, () -> {
        }, last);
    }

    private static double reading(FlowsheetRun run, String key) {
        var readings = run.reports().get("T").readings();
        assertTrue(readings.containsKey(key), () -> key + " in " + readings);
        return readings.get(key);
    }

    /** Steady; what entered left, mass to the flash's digits; the block's own energy gap is the sheet's, as with no ejector. */
    private static void closes(FlowsheetRun run) {
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        assertEquals(0, run.massGap(), 1e-9 * run.massIn(), "mass");
        assertEquals(reading(run, "energy_gap"), run.heatGap(), 1, "the steam's heat is booked on the condenser: what is left is what the two thermo models differ by");
        assertTrue(Math.abs(run.heatGap()) < 2e-3 * reading(run, "condenser"), () -> "heat gap " + run.heatGap());
    }

    @Test void theMotivePortClosesFreeAndWired() {
        assertTrue(ColumnComponents.ejectorRequired(TOP));
        double waterMass = MaterialRuntime.with(CATALOG, NETWORK, () -> EjectorUnit.waterMass(THERMO));
        var free = run(sheet(null), null);
        closes(free);
        double need = reading(free, "ejector_need"), heat = reading(free, "ejector_heat");
        assertEquals(need, reading(free, "ejector_motive"), 1e-9 * need, "a free port is a utility at the need");
        assertTrue(heat / need > 2.0e6 && heat / need < 3.0e6, () -> "the steam gives up its latent heat and more: " + heat / need + " J/kg");
        double load = reading(free, "ejector_load");
        double compression = reading(free, "ejector_compression");
        assertTrue(load > 0 && compression > 1, () -> load + " kg/s at a compression of " + compression);
        System.out.printf("free: load %.3f kg/s, compression %.2f, steam %.3f kg/s (%.2f kg/kg), heat %.2f MW, condenser %.2f MW%n", load, compression, need, need / load, heat / 1e6, reading(free, "condenser") / 1e6);

        // Wired at 1.1 times the need: the same column, so the same need; the water product carries the wired steam.
        var steam = new LinkedHashMap<String, Double>();
        steam.put("Water", 1.1 * need / waterMass);
        var sheet = sheet(new Flowsheet.Feed(460, EjectorUnit.MOTIVE_PRESSURE, steam, List.of()));
        var wired = run(sheet, free);
        closes(wired);
        assertEquals(need, reading(wired, "ejector_need"), 1e-6 * need);
        assertEquals(1.1 * need, reading(wired, "ejector_motive"), 1e-9 * need);
        assertEquals(free.streams().get("water").amounts()[WATER] + .1 * need / waterMass, wired.streams().get("water").amounts()[WATER], 1e-6 * wired.streams().get("water").total(),
                "the wired steam's 10 % more leaves as free water");
        assertFalse(wired.reports().get("T").notes().stream().anyMatch(note -> note.key().equals("ejector.motive_short")));

        var sized = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.rate(sheet, wired, THERMO)).units().get(SheetSizing.member("T", "ejector"));
        assertNotNull(sized, "the block's ejector is a member");
        assertNotNull(sized.size());
        assertEquals(Lamp.GREEN, sized.rating().reading(Limit.EJECTOR_MOTIVE).lamp());
        assertEquals(Lamp.YELLOW, sized.rating().reading(Limit.EJECTOR_MOTIVE_SPARE).lamp(), "between the need and its spare");
    }
}
