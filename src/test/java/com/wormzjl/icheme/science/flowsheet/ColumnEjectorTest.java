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
 * Main plan W10's and W10b's gate on the column block (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.12): a
 * small vacuum sheet, the default preset's column with its top under ambient pressure. The ejector set's load is the condenser's
 * vent, the block's Overhead product. Its Motive steam port, free and wired, closes mass, energy and components across the block:
 * the steam leaves as free water, and its heat joins the condenser's duty. A slow gate: two solves.
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
        return com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(sheet, CATALOG, () -> {
        }, last);
    }

    private static double reading(FlowsheetRun run, String key) {
        var readings = run.reports().get("T").readings();
        assertTrue(readings.containsKey(key), () -> key + " in " + readings);
        return readings.get(key);
    }

    /**
     * Steady; what entered left, mass to the flash's digits; the block's own energy gap is the sheet's, as with no ejector. That gap is
     * what V3's thermo and the world's differ by, measured against V3's own condenser duty: 0.26 % at this 80 kPa top (W10b), where
     * W10's bound read it against the duty with the whole overhead's steam in it.
     */
    private static void closes(FlowsheetRun run) {
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        assertEquals(0, run.massGap(), 1e-9 * run.massIn(), "mass");
        assertEquals(reading(run, "energy_gap"), run.heatGap(), 1, "the steam's heat is booked on the condenser: what is left is what the two thermo models differ by");
        double duty = reading(run, "condenser") - reading(run, "ejector_heat");
        assertTrue(Math.abs(run.heatGap()) < 3e-3 * duty, () -> "heat gap " + run.heatGap() + " of V3's " + duty);
    }

    @Test void theMotivePortClosesFreeAndWired() {
        assertTrue(ColumnComponents.ejectorRequired(TOP));
        double waterMass = MaterialRuntime.with(CATALOG, NETWORK, () -> THERMO.waterMass());
        var free = run(sheet(null), null);
        closes(free);
        double need = reading(free, "ejector_need"), heat = reading(free, "ejector_heat");
        assertEquals(need, reading(free, "ejector_motive"), 1e-9 * need, "a free port is a utility at the need");
        assertTrue(heat / need > 2.0e6 && heat / need < 3.0e6, () -> "the steam gives up its latent heat and more: " + heat / need + " J/kg");
        double load = reading(free, "ejector_load");
        var vent = free.streams().get("overhead");
        assertTrue(load > 0, "the condenser vents gas");
        assertEquals(vent.mass(), load, 1e-9 * load, "the load is the condenser's vent, not the top tray's vapour");
        double water = vent.amounts()[WATER] * waterMass / vent.mass();
        System.out.printf("free: vent %.4f kg/s (water %.1f %%, gas %.1f %%), steam %.4f kg/s (%.2f kg/kg), heat %.3f MW, condenser %.2f MW%n",
                load, 100 * water, 100 * (1 - water), need, need / load, heat / 1e6, reading(free, "condenser") / 1e6);

        // Wired at 1.1 times the need: the same column, so the same need; the water product carries the wired steam. It runs from no
        // run, as the free one did: from the free run its column would start warm and close to its own tolerance, not to these digits.
        var steam = new LinkedHashMap<String, Double>();
        steam.put("Water", 1.1 * need / waterMass);
        var sheet = sheet(new Flowsheet.Feed(460, EjectorUnit.MOTIVE_PRESSURE, steam, List.of()));
        var wired = run(sheet, null);
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

    /**
     * A liquid ring pump on the receiver's vapour outlet in the ejector's place (batch 2026-11-10-vacuum-pump, owner V5): no Motive
     * port, no steam; the block reads the pump's model, its shaft power at the vent, and sizes it as a member. This column's vent at
     * 80 kPa, 4.6 m³/s, is past the largest ring (5,000 m³/h): the block takes that one and warns.
     */
    @Test void aVacuumPumpTakesTheVentWithNoSteam() {
        var door = door();
        door.json().addProperty(ColumnDoor.VACUUM, "liquid_ring");
        var units = List.of(new Flowsheet.Unit("T", "column", "T-101", 0, 0, door));
        var base = sheet(null);
        var sheet = new Flowsheet(base.basis(), units, base.streams(), base.settings());
        var run = run(sheet, null);
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        assertEquals(0, run.massGap(), 1e-9 * run.massIn(), "mass");
        var readings = run.reports().get("T").readings();
        assertFalse(readings.containsKey("ejector_heat"), "no ejector");
        double power = reading(run, "vacuum_power"), capacity = reading(run, "vacuum_capacity");
        assertTrue(power > 0, "it takes shaft power");
        assertEquals(4, reading(run, "vacuum_model"), "the largest ring");
        assertEquals(1, reading(run, "vacuum_stages"));
        assertEquals(5000 / 3600. * Math.pow(1 - com.wormzjl.icheme.science.machine.VacuumPump.SEAL_VAPOUR_PRESSURE / TOP, 2), capacity, 2e-2 * capacity, "its capacity at about the top's pressure");
        assertTrue(run.reports().get("T").notes().stream().anyMatch(note -> note.key().equals("vacuum_pump.capacity")), "short of the vent: a warning");
        System.out.printf("vacuum pump: model %.0f, %.1f kW, capacity %.0f m³/h%n", reading(run, "vacuum_model"), power / 1e3, 3600 * capacity);
        var sized = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.rate(sheet, run, THERMO)).units();
        org.junit.jupiter.api.Assertions.assertNull(sized.get(SheetSizing.member("T", "ejector")), "no ejector member");
        assertNotNull(sized.get(SheetSizing.member("T", "vacuum_pump")), "the pump is a member");
    }
}
