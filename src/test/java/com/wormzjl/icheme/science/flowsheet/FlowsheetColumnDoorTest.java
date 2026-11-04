package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The column block's door (batch 2026-11-02-process-simulator, plan section 3.11): what needs no solve. {@link FlowsheetColumnTest} holds what does. */
final class FlowsheetColumnDoorTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final V3ColumnInput BASE = CATALOG.presets().column(CATALOG.presets().network().defaultColumnPreset()).input(CATALOG);
    private static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, NETWORK).components();

    private static List<String> ports(Params door) {
        return UnitTypes.model("column").ports(door).stream().map(Port::id).toList();
    }

    @Test void itsPortsFollowItsDoor() {
        assertFalse(ports(ColumnDoor.from(BASE, ColumnComponents.Condenser.AIR_COOLER)).contains("condenser_in"), "an air cooler has no cooling pair");
        var exchanger = ports(ColumnDoor.from(BASE, ColumnComponents.Condenser.EXCHANGER));
        assertTrue(exchanger.containsAll(List.of("feed", "overhead", "distillate", "water", "bottoms", "condenser_in", "condenser_out", "pa/1/in", "pa/1/out", "steam/1", "draw/1")));
        assertEquals(BASE.pumparounds().size() * 2 + BASE.steamFeeds().size() + BASE.sideDraws().size() + 7, exchanger.size(), "one pair a pumparound, a port a steam or draw row");
        var boiled = ColumnDoor.from(BASE, ColumnComponents.Condenser.AIR_COOLER);
        boiled.json().addProperty("reboiler_duty", 5e6);
        assertTrue(ports(boiled).contains("reboiler_in"), "reboiler duty brings the kettle's tube side");
        assertFalse(ports(ColumnDoor.from(BASE, ColumnComponents.Condenser.AIR_COOLER)).contains("reboiler_in"), "and none brings none");
    }

    @Test void aRowKeepsItsPortWhenAnotherGoes() {
        var door = ColumnDoor.from(BASE, ColumnComponents.Condenser.EXCHANGER);
        door.json().getAsJsonArray("pumparounds").remove(0);
        var ports = ports(door);
        assertFalse(ports.contains("pa/1/in"));
        assertTrue(ports.contains("pa/2/in"), "PA 2 keeps its id");
    }

    @Test void theDoorHoldsWhatTheCalculatorHolds() {
        var door = ColumnDoor.of(ColumnDoor.from(BASE, ColumnComponents.Condenser.EXCHANGER));
        assertEquals(BASE.packageId(), door.packageId());
        assertEquals(BASE.stageCount(), door.stages());
        assertEquals(BASE.feedStageNumber(), door.feedStage());
        assertEquals(BASE.topPressurePascal(), door.topPressure(), 0);
        assertEquals(BASE.columnDiameterMetres(), door.diameter(), 0);
        assertEquals(BASE.sideDraws().size(), door.draws().size());
        assertEquals(BASE.pumparounds().getFirst().dutyWatts(), door.pumparounds().getFirst().duty(), 0);
        assertEquals(BASE.steamFeeds().getFirst().temperatureKelvin(), door.steam().getFirst().temperature(), 0);
    }

    @Test void twoRowsCannotShareAnId() {
        var door = ColumnDoor.from(BASE, ColumnComponents.Condenser.EXCHANGER);
        door.json().getAsJsonArray("draws").get(1).getAsJsonObject().addProperty("id", "1");
        assertEquals("column.row_id", assertThrows(FlowsheetFailure.class, () -> ColumnDoor.of(door)).key());
    }

    private static Flowsheet.Feed crude(double temperature, double pressure, double waterFlow) {
        var ids = BASE.componentBasis().componentIds();
        var amounts = new LinkedHashMap<String, Double>();
        double[] flows = BASE.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < flows.length; i++) if (flows[i] > 0) amounts.put(ids.get(i), flows[i]);
        if (waterFlow > 0) amounts.put("Water", waterFlow);
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    private static FlowsheetRun alone(Flowsheet.Feed feed) {
        var unit = new Flowsheet.Unit("T", "column", "T", 0, 0, ColumnDoor.from(BASE, ColumnComponents.Condenser.AIR_COOLER));
        var line = new Flowsheet.Stream("feed", "feed", null, new Flowsheet.End("T", "feed"), feed);
        return com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(new Flowsheet(new Flowsheet.Basis(NETWORK, "test", AXIS), List.of(unit), List.of(line), FlowsheetSolver.Settings.DEFAULT), CATALOG, () -> {
        });
    }

    @Test void itRefusesWhatV3CannotTake() {
        var plain = crude(638.15, 2.7e5, 0);
        var dusty = alone(new Flowsheet.Feed(plain.temperature(), plain.pressure(), plain.amounts(), List.of(new Flowsheet.Solid("icheme:demo_particle", "100", .5))));
        assertEquals(FlowsheetRun.Status.FAILED, dusty.status());
        assertEquals("column.solids", dusty.log().getFirst().key(), () -> "log " + dusty.log());
        var wet = alone(crude(330, 2.7e5, 2000));
        assertEquals(FlowsheetRun.Status.FAILED, wet.status());
        assertEquals("column.liquid_water", wet.log().getFirst().key(), () -> "liquid water has no place on V3's feed: log " + wet.log());
    }
}
