package com.wormzjl.icheme.runtime.instrument;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.item.GaugeSettings;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import java.util.*;

class LiquidLevelGaugeTest {
    private static InstrumentService.Store.Pending pending(
            InstrumentService.Store store,
            String mount,
            Instrument.Type type,
            GaugeSettings settings) {
        return new InstrumentService.Store.Pending(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new Instrument.Key(7, mount),
                UUID.randomUUID(),
                store.revision(7),
                true,
                type,
                settings,
                "minecraft:overworld",
                0,
                0,
                false);
    }

    @Test
    void levelGaugeCoexistsWithDialsAndRecoveryIsExactlyOnceAcrossPersistence() {
        var store = new InstrumentService.Store();
        var request = pending(store, "tank_level", Instrument.Type.LEVEL_GAUGE, null);
        assertTrue(store.queue(request));
        var fitting = store.installFitting(request);
        assertNotNull(fitting);
        assertEquals(request.instance(), fitting.instance());
        assertNull(store.installFitting(request));
        store.finish(request.request());
        for (var settings : List.of(GaugeSettings.PRESSURE, GaugeSettings.TEMPERATURE)) {
            var type =
                    settings.kind() == GaugeSettings.Kind.PRESSURE
                            ? Instrument.Type.PRESSURE
                            : Instrument.Type.TEMPERATURE;
            var operation =
                    pending(
                            store,
                            type == Instrument.Type.PRESSURE
                                    ? "pressure_bottom"
                                    : "temperature_bottom",
                            type,
                            settings);
            assertTrue(store.queue(operation));
            assertNotNull(store.install(operation));
            store.finish(operation.request());
        }
        var loaded = InstrumentService.Store.load(store.save(new CompoundTag()));
        assertEquals(fitting, loaded.legacy(7));
        assertEquals(2, loaded.host(7).size());
        assertEquals(3, loaded.revision(7));
        assertNull(loaded.removeLegacy(7, UUID.randomUUID()));
        assertNotNull(loaded.removeLegacy(7, fitting.instance()));
        assertNull(loaded.removeLegacy(7, fitting.instance()));
        assertEquals(
                2,
                loaded.host(7).size(),
                "wrench removal preserves the pressure/temperature accessories");
        var reinstall = pending(loaded, "tank_level", Instrument.Type.LEVEL_GAUGE, null);
        assertTrue(loaded.queue(reinstall));
        var next = loaded.installFitting(reinstall);
        assertNotNull(next);
        assertNotEquals(fitting.instance(), next.instance());
        assertNull(
                loaded.removeLegacy(7, fitting.instance()),
                "old removal/recovery cannot refund a new installation");
    }

    @Test
    void pendingInstallAndStaticMetadataRoundTripWithoutCreatingAReading() {
        var store = new InstrumentService.Store();
        var operation = pending(store, "tank_level", Instrument.Type.LEVEL_GAUGE, null);
        assertTrue(store.queue(operation));
        var loaded = InstrumentService.Store.load(store.save(new CompoundTag()));
        assertEquals(List.of(operation), loaded.pending());
        var fitting = loaded.installFitting(operation);
        assertNotNull(fitting);
        var metadata = new Instrument.Metadata();
        metadata.set(List.of(), loaded.revision(7), fitting);
        var image = new CompoundTag();
        metadata.write(image);
        var client = new Instrument.Metadata();
        client.read(image);
        assertTrue(client.levelGauge());
        assertEquals(fitting, client.fitting());
        assertTrue(client.installed().isEmpty());
        metadata.set(List.of(), loaded.revision(7), null);
        metadata.write(image);
        client.read(image);
        assertFalse(client.levelGauge());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        pending(
                                new InstrumentService.Store(),
                                "pressure_bottom",
                                Instrument.Type.LEVEL_GAUGE,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Instrument.Fitting(
                                new Instrument.Key(7, "pipe_accessory"),
                                UUID.randomUUID(),
                                1,
                                Instrument.Type.LEVEL_GAUGE));
        var bad = loaded.save(new CompoundTag());
        bad.getList("fittings", 10).getCompound(0).putString("type", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> InstrumentService.Store.load(bad));
    }

    @Test
    void onlyFloatingRoofTanksOfferTheAccessoryAndTheBareTankHasNoGlass() {
        for (var heading : EquipmentShape.Heading.values())
            for (int[] size : new int[][] {{4, 4}, {40, 24}}) {
                var shape =
                        EquipmentShape.storage(
                                EquipmentShape.Kind.TANK, size[0], size[1], heading, 0);
                var bounds = TankGaugeGeometry.bounds(shape);
                assertTrue(bounds[4] - bounds[1] > size[1] - 1);
                assertEquals(
                        bounds[1],
                        InstrumentHosts.LevelGauge.selectionBounds(shape).minY,
                        0,
                        "the foundation below grade is outside the mount clearance");
                boolean hasGlass = false;
                for (var face : ShapeRasterizer.tankGauge(shape)) {
                    hasGlass |= face.material() == Mesh.Material.GLASS;
                    var points = face.positions();
                    for (int i = 0; i < points.length; i += 3)
                        for (int axis = 0; axis < 3; axis++)
                            assertTrue(
                                    points[i + axis] >= bounds[axis] - 1e-8
                                            && points[i + axis] <= bounds[axis + 3] + 1e-8,
                                    "server selection bounds enclose the actual model");
                }
                assertTrue(hasGlass);
                for (var cell : ShapeRasterizer.layout(shape).shell())
                    for (var face :
                            ShapeRasterizer.surface(
                                    ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z())))
                        assertNotEquals(
                                Mesh.Material.GLASS,
                                face.material(),
                                "construction alone does not install the gauge");
            }
        var sphere =
                EquipmentShape.storage(
                        EquipmentShape.Kind.SPHERE, 8, 1, EquipmentShape.Heading.NORTH, 2);
        assertTrue(ShapeRasterizer.tankGauge(sphere).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TankGaugeGeometry.bounds(sphere));
    }
}
