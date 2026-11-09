package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.UnitTypes;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** New equipment joins the simulator (plan section 3.10): each kind the catalog maps has a tile, a port rule and a lang line, and a tile runs exactly when the catalog says so. */
class FlowsheetPaletteCoverageTest {
    private static List<FlowsheetCatalog.Entry> entries() {
        var entries = new ArrayList<FlowsheetCatalog.Entry>();
        for (var kind : EquipmentShape.Kind.values()) entries.add(FlowsheetCatalog.of(kind));
        for (var equipment : ConstructorColumn.Equipment.values()) entries.add(FlowsheetCatalog.of(equipment));
        for (var kind : TopologyCompiler.Kind.values()) entries.add(FlowsheetCatalog.of(kind));
        return entries;
    }

    /**
     * A drawing-only kind that stands on a modelled kind's tile, as the drivers on the steam turbine's (batch 2026-11-03-rotating-machinery, W8, D43):
     * the tile runs the modelled kind, and this one is drawn there only.
     */
    private static boolean drawnOnARunningTile(FlowsheetCatalog.Entry entry) {
        return entry.coverage() == FlowsheetCatalog.Coverage.DRAWING_ONLY && entries().stream().anyMatch(e -> e.tile().equals(entry.tile()) && e.coverage() == FlowsheetCatalog.Coverage.MODELLED);
    }
    private static boolean stream(String type) {
        return type.equals(Tiles.FEED) || type.equals(Tiles.PRODUCT);
    }

    @Test
    void everyKindHasATileThatRunsExactlyWhenTheCatalogSaysSo() {
        for (var entry : entries()) {
            var tile = Tiles.tile(entry.tile());
            assertNotNull(tile, entry + " has a tile");
            if (!drawnOnARunningTile(entry)) assertEquals(entry.coverage() == FlowsheetCatalog.Coverage.MODELLED, tile.lit(), entry + ": lit means it has a model");
            assertTrue(SheetFixture.Texts.has("gui.icheme." + tile.name()), entry + " has a name");
        }
    }

    @Test
    void aModelledKindsTileHasAPortRule() {
        for (var entry : entries()) {
            if (entry.coverage() != FlowsheetCatalog.Coverage.MODELLED || stream(entry.tile())) continue;
            var model = UnitTypes.model(entry.tile());
            assertNotNull(model, entry + " has a model");
            var params = SheetEditor.defaults(entry.tile(), SheetFixture::column);
            var units = List.of(new Flowsheet.Unit("u", entry.tile(), "U-1", 100, 60, params));
            var box = SheetLayout.of(new Flowsheet(SheetFixture.basis(), units, List.of(), FlowsheetSolver.Settings.DEFAULT), SheetFixture.M).box("u");
            assertFalse(model.ports(params).isEmpty(), entry + " has ports");
            assertEquals(model.ports(params).size(), box.places().size(), entry + ": a place for every port");
        }
    }

    @Test
    void aDrawingOnlyKindHasNoModelAndAPlaceholderKeepsItsStreams() {
        assertTrue(SheetFixture.Texts.has("gui.icheme.flowsheet.tile.unlit"), "its hover says why it is off");
        int some = 0;
        for (var entry : entries()) {
            if (entry.coverage() != FlowsheetCatalog.Coverage.DRAWING_ONLY || drawnOnARunningTile(entry)) continue;
            some++;
            assertNull(UnitTypes.model(entry.tile()), entry + " has no model yet");
            var units = List.of(new Flowsheet.Unit("u", entry.tile(), "U-1", 100, 60, SheetEditor.defaults("valve", SheetFixture::column)));
            var streams = List.of(new Flowsheet.Stream("s", "S", new Flowsheet.End("u", "out"), null, null, List.of(new Flowsheet.Point(400, 90)), new JsonObject()));
            var box = SheetLayout.of(new Flowsheet(SheetFixture.basis(), units, streams, FlowsheetSolver.Settings.DEFAULT), SheetFixture.M).box("u");
            assertEquals(List.of("out"), box.places().stream().map(SheetLayout.Place::port).toList(), entry + ": the port its stream names stays");
        }
        assertTrue(some > 0);
    }

    @Test
    void oneTileHoldsSeveralKinds() {
        assertEquals("storage_tank", FlowsheetCatalog.of(EquipmentShape.Kind.SPHERE).tile());
        assertEquals("storage_tank", FlowsheetCatalog.of(EquipmentShape.Kind.TANK).tile());
        assertEquals("fired_heater", FlowsheetCatalog.of(ConstructorColumn.Equipment.CABIN_HEATER).tile());
        assertEquals("fired_heater", FlowsheetCatalog.of(EquipmentShape.Kind.VERTICAL_HEATER).tile());
        assertEquals("cooling_tower", FlowsheetCatalog.of(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER).tile());
    }

    @Test
    void everyTileIsOneTheCatalogOrTheMixingPiecesName() {
        var named = new HashSet<String>(List.of("mixer", "splitter"));
        for (var entry : entries()) named.add(entry.tile());
        for (var tile : Tiles.all()) assertTrue(named.contains(tile.type()), tile.type() + " stands for no kind");
    }
}
