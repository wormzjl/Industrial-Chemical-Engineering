package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.equipment.BatchStillOptions;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.TowerBank;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A batch still's groups on its pot's door column (batch 2026-11-09-batch-distiller, W6): every heater's Firing, the
 * Reflux valve only with a column, the Fuel only for a solid fire, a controller's hold; every text they name has its
 * English line; they fit the door at every width it takes with the device column's groups after them, boxes in line; their
 * widgets keep their slots while the plant reads other values. The constructor's still options fit its door.
 */
class StillDoorTest {
    private static final Measure M = Measure.fixed(6);

    private record Case(String name, StillDoor.Input in) {
    }

    private static List<Case> cases() {
        var out = new ArrayList<Case>();
        for (var heater : StillDoor.Heater.values())
            for (boolean edit : new boolean[]{true, false})
                for (boolean column : new boolean[]{false, true})
                    for (boolean held : new boolean[]{false, true})
                        for (boolean empty : new boolean[]{false, true}) {
                            var fuel = heater != StillDoor.Heater.SOLID ? null : new StillDoor.Fuel(empty ? null : "Block of Coal", empty ? "0" : "12", empty ? "—" : "3600", "250");
                            out.add(new Case(heater + (edit ? "" : " reader") + (column ? " column" : "") + (held ? " held" : "") + (empty ? " empty" : ""),
                                    new StillDoor.Input(heater, edit, held, column, !held, held ? "63.0" : null, held && column ? "Closed" : null, fuel)));
                        }
        return out;
    }

    /** The pot's whole door column: the still's groups lead, then the tank's Phases, Level and the still's readings. */
    private static List<Column.Row> door(StillDoor.Input in, HeatControlColumn.Texts t, String temperature) {
        var meters = new ArrayList<DeviceColumn.Meter>();
        for (var key : List.of("still_pot_temperature", "still_head_temperature", "still_level", "still_heat", "still_distillate", "still_vent", "still_condensed", "still_reflux_run"))
            meters.add(new DeviceColumn.Meter(key, temperature, key.endsWith("temperature") ? "°C" : key.equals("still_level") ? "m" : key.equals("still_heat") ? "kW" : "kg/min", "a hint"));
        var device = new DeviceColumn.Input(Kind.RESERVOIR, in.canEdit(), false, true, "kPa", "°C", "/min", DeviceColumn.Valve.NONE, null, false,
                new DeviceColumn.Level(0.43, "212.0", "500.0"), null, StillDoor.rows(in, t), new DeviceColumn.Phases("volume", new double[]{1, 0, 2, 0}, false, "Phase fractions."), meters,
                null, in.heater() == StillDoor.Heater.ELECTRIC ? new DeviceColumn.Power("35.6", "711", "80") : null);
        return DeviceColumn.rows(device, t);
    }

    @Test
    void everyTextTheGroupsNameHasItsEnglishLine() {
        var texts = new HeatControlColumnTest.Recording();
        for (var c : cases()) door(c.in(), texts, "101.3");
        for (var key : List.of("still_head_temperature.hint", "still_level.hint", "still_heat.hint", "still_distillate.hint", "still_vent.hint", "still_condensed.hint",
                "still_reflux_run.hint", "still_reflux.closed", "waiting"))
            // A presence check: read as written (a no-argument line may hold a bare %, as the game shows it).
            texts.get(key);
        assertEquals(Set.of(), texts.missing);
    }

    @Test
    void theGroupsShowWhatTheStillHas() {
        var texts = new HeatControlColumnTest.Recording();
        for (var c : cases()) {
            var keys = widgets(Column.place(StillDoor.rows(c.in(), texts), 10, 74, FluidGuiLayout.side(464) - 10, M, Column.Metrics.COMPACT)).keySet();
            assertTrue(keys.containsAll(List.of("still_firing:off", "still_firing:on")), c.name());
            assertEquals(c.in().canEdit(), keys.contains("stillFiring"), c.name() + ": the firing's entry for an editor");
            assertEquals(c.in().column(), keys.contains("still_reflux:open"), c.name() + ": the reflux valve with a column only");
            assertEquals(c.in().column() && c.in().canEdit(), keys.contains("stillReflux"), c.name());
            assertEquals(c.in().heater() == StillDoor.Heater.SOLID, keys.contains("still_fuel:load"), c.name() + ": the fuel slot on a solid fire only");
            var rows = StillDoor.rows(c.in(), texts);
            boolean held = rows.stream().flatMap(r -> ((Column.Group) r).rows().stream()).anyMatch(r -> r instanceof Column.Paragraph p && p.text().startsWith("Controller"));
            assertEquals(c.in().heldFiring() != null, held, c.name() + ": a controller's hold is said");
        }
    }

    @Test
    void thePotsDoorFitsAtEveryWidthWithItsBoxesInLine() {
        var texts = new HeatControlColumnTest.Recording();
        for (var c : cases())
            for (int width = 300; width <= 790; width += 7) {
                var column = Column.place(door(c.in(), texts, "101.3"), 10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
                for (var p : column.rows()) {
                    assertTrue(p.rect().right() <= column.bounds().right(), c.name() + " a row at " + width);
                    for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), c.name() + " a text cut at " + width + ": " + t);
                    for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, c.name() + " a box at " + width + ": " + s);
                    if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), c.name() + " a plate in one line at " + width + ": " + g.title());
                    if (p.row() instanceof Column.Reading r && !r.unit().isEmpty()) assertTrue(p.texts().contains(r.unit()), c.name() + " a unit whole at " + width + ": " + r.unit());
                }
                column.slots();
                DeviceColumnTest.assertInLine(column, c.name() + " at " + width);
                // The plant's values move no widget.
                var other = Column.place(door(c.in(), texts, "—"), 10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
                assertEquals(widgets(column), widgets(other), c.name() + " at " + width);
            }
    }

    /** The constructor's still options: Heater, Condenser and Stages after the size, at its door's width and less its bar. */
    @Test
    void theConstructorsStillOptionsFitItsDoor() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var heater : BatchStillOptions.Heater.values())
            for (var condenser : BatchStillOptions.Condenser.values())
                for (int stages : condenser == BatchStillOptions.Condenser.BUILT_IN ? new int[]{0, 2, 8} : new int[]{0}) {
                    var options = new BatchStillOptions(heater, condenser, stages);
                    var in = new ConstructorColumn.Design(VesselPlacement.Form.BATCH_STILL, 500, 1, 0, 1, 0, 0, ProcessUnit.Bank.ONE, null,
                            new ConstructorColumn.Outlet(1, 0, List.of("front", "right", "back", "left")), 32, TowerBank.ONE, MachineDuty.NONE,
                            com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD, options);
                    var page = ConstructorColumn.design(in, texts);
                    String what = options.toString();
                    for (int width : new int[]{ConstructorColumn.WIDTH, ConstructorColumn.WIDTH - 8}) {
                        var column = Column.place(page.rows(), 10, 30, width, M, Column.Metrics.COMPACT);
                        for (var p : column.rows()) {
                            assertTrue(p.rect().right() <= column.bounds().right(), what + " a row at " + width);
                            for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), what + " a text cut at " + width + ": " + t);
                            for (var s : p.slots().values()) assertTrue(s.inside(p.rect()), what + " a box at " + width + ": " + s);
                        }
                        var keys = widgets(column).keySet();
                        for (var h : BatchStillOptions.Heater.values()) assertTrue(keys.contains("still_heater:" + h.name()), what);
                        assertTrue(keys.contains("still_condenser:NONE"), what);
                        assertEquals(condenser == BatchStillOptions.Condenser.BUILT_IN, keys.contains("stages+"), what + ": stages step only with the condenser");
                    }
                }
        assertEquals(Set.of(), texts.missing);
    }

    /** The widgets' slots, by key: a field's, a button's and a setting's steppers, not a meter's. */
    private static Map<String, Rect> widgets(Column column) {
        var out = new LinkedHashMap<String, Rect>();
        for (var p : column.rows())
            if (p.row() instanceof Column.Field || p.row() instanceof Column.Buttons || p.row() instanceof Column.Setting) out.putAll(p.slots());
        return out;
    }
}
