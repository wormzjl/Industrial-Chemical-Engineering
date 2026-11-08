package com.wormzjl.icheme.runtime.control;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.equipment.ConduitGeometry;

import net.minecraft.core.*;
import net.minecraft.nbt.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class ControlSystemTest {
    @Test
    void removingASensorSocketLeavesItsCableAndHistoricalIdRoundTrippable() throws Exception {
        var facts = corpus();
        var port =
                facts.ports().values().stream().filter(p -> p.host() > 0).findFirst().orElseThrow();
        var cables = facts.cables();
        facts.removePort(port.id());
        var read = ControlFacts.load(facts.save(new CompoundTag(), null));
        assertNull(read.port(port.id()));
        assertEquals(new HashSet<>(cables), new HashSet<>(read.cables()));
        var panel = read.panels().iterator().next();
        assertTrue(
                new DataBus(read.cables(), read.ports()).hosts(panel.id(), read::port).isEmpty());
    }

    @Test
    void operatorVisibilityContainsOnlyHostsOnItsDataBus() throws Exception {
        var facts = corpus();
        var controller = facts.panels().iterator().next();
        var sensor =
                facts.ports().values().stream().filter(p -> p.host() > 0).findFirst().orElseThrow();
        var other =
                new DataPort(
                        new UUID(0, 800),
                        DataPort.Kind.SENSOR,
                        800,
                        sensor.dimension(),
                        new BlockPos(20, 0, 0),
                        new net.minecraft.world.phys.Vec3(20.5, .5, -.1),
                        new BlockPos(20, 0, -1),
                        Direction.SOUTH,
                        0,
                        0);
        facts.port(other);
        var bus = new DataBus(facts.cables(), facts.ports());
        assertEquals(Set.of(sensor.host()), bus.hosts(controller.id(), facts::port));
        assertFalse(bus.hosts(controller.id(), facts::port).contains(other.host()));
        facts.removeCable(facts.cables().iterator().next().site());
        assertTrue(
                new DataBus(facts.cables(), facts.ports())
                        .hosts(controller.id(), facts::port)
                        .isEmpty());
        assertNotNull(
                facts.port(sensor.id()),
                "Disconnected equipment and its stable IDs remain built facts");
    }

    @Test
    void inventoryCorrectionClosesEitherBalanceInOneInterval() {
        double outlet = InventoryControl.target(110, 100, 3, 115, 5, 5, true);
        assertEquals(100, 110 + (2 - outlet) * 5, 1e-12);
        double inlet = InventoryControl.target(90, 100, 3, 85, 5, 5, false);
        assertEquals(100, 90 + (inlet - 2) * 5, 1e-12);
        assertEquals(0, InventoryControl.target(80, 100, 1, 80, 5, 5, true));
        assertEquals(0, InventoryControl.target(120, 100, 1, 120, 5, 5, false));
    }

    @Test
    void everyControlCorpusRetainsFactsAndConnectivity() throws Exception {
        try (var paths = Files.list(Path.of("src/test/resources/compat/control"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".snbt")).toList()) {
                var tag = TagParser.parseTag(Files.readString(path));
                var read = ControlFacts.load(tag);
                var saved = read.save(new CompoundTag(), null);
                for (var key : tag.getAllKeys())
                    if (!key.equals("format"))
                        assertEquals(tag.get(key), saved.get(key), path + " / " + key);
                assertEquals(saved, ControlFacts.load(saved).save(new CompoundTag(), null));
                if (!read.panels().isEmpty()) {
                    var panel = read.panels().iterator().next();
                    var sensor =
                            read.ports().values().stream()
                                    .filter(p -> p.host() > 0)
                                    .findFirst()
                                    .orElseThrow();
                    assertTrue(
                            new DataBus(read.cables(), read.ports())
                                    .connected(panel.id(), sensor.id()));
                }
            }
        }
    }

    @Test
    void sensorIdsSurviveHostChangesAndAreNeverReused() throws Exception {
        var facts =
                ControlFacts.load(
                        TagParser.parseTag(
                                Files.readString(
                                        Path.of(
                                                "src/test/resources/compat/control/control-2.snbt"))));
        var instance = new UUID(0, 3);
        var before =
                new com.wormzjl.icheme.runtime.instrument.Sensor.Installed(
                        new com.wormzjl.icheme.runtime.instrument.Instrument.Key(10, "level_body"),
                        instance,
                        1,
                        com.wormzjl.icheme.runtime.instrument.Sensor.Kind.LEVEL);
        assertEquals("LT-0012", facts.identify(before).label());
        var moved =
                new com.wormzjl.icheme.runtime.instrument.Sensor.Installed(
                        new com.wormzjl.icheme.runtime.instrument.Instrument.Key(99, "level_body"),
                        instance,
                        2,
                        before.kind());
        assertEquals(facts.identify(before), facts.identify(moved));
        var replacement =
                new com.wormzjl.icheme.runtime.instrument.Sensor.Installed(
                        before.key(), new UUID(0, 4), 3, before.kind());
        assertEquals("LT-0013", facts.identify(replacement).label());
        assertNotEquals(before.instance(), replacement.instance());
        assertEquals(
                facts.sensorId(instance),
                ControlFacts.load(facts.save(new CompoundTag(), null)).sensorId(instance));
    }

    @Test
    void removingOneCableBreaksTheBusButKeepsRegisteredPorts() throws Exception {
        var facts = corpus();
        var panel = facts.panels().iterator().next();
        var sensor =
                facts.ports().values().stream().filter(p -> p.host() > 0).findFirst().orElseThrow();
        facts.removeCable(facts.cables().iterator().next().site());
        assertFalse(new DataBus(facts.cables(), facts.ports()).connected(panel.id(), sensor.id()));
        assertEquals(2, facts.ports().size());
    }

    @Test
    void wrongTypeListsDuplicateSocketsAndDamagedNamesRefuseFacts() throws Exception {
        var saved = corpus().save(new CompoundTag(), null);
        var bad = saved.copy();
        var list = new ListTag();
        list.add(StringTag.valueOf("lost cable"));
        bad.put("cables", list);
        assertThrows(IllegalArgumentException.class, () -> ControlFacts.load(bad));
        var duplicate = saved.copy();
        duplicate.getList("ports", 10).add(duplicate.getList("ports", 10).getCompound(0).copy());
        assertThrows(IllegalArgumentException.class, () -> ControlFacts.load(duplicate));
        var name = saved.copy();
        name.getList("panels", 10)
                .getCompound(0)
                .getCompound("settings")
                .putString("name", "invalid\nname");
        assertThrows(IllegalArgumentException.class, () -> ControlFacts.load(name));
    }

    @Test
    void coincidentFacesWithDifferentOffsetsRemainSeparate() {
        var first = new UUID(0, 1);
        var second = new UUID(0, 2);
        var a =
                new DataBus.Cable(
                        new DataBus.Site("minecraft:overworld", BlockPos.ZERO, 0),
                        new ConduitGeometry.Key(0, 0).connect(4, 0, 0).connect(5, 0, 0),
                        0,
                        Map.of(Direction.WEST, first));
        var b =
                new DataBus.Cable(
                        new DataBus.Site("minecraft:overworld", new BlockPos(1, 0, 0), 0),
                        new ConduitGeometry.Key(0, 0).connect(4, 8, 0).connect(5, 8, 0),
                        0,
                        Map.of(Direction.EAST, second));
        var ports =
                Map.of(
                        first,
                        new DataPort(
                                first,
                                DataPort.Kind.SENSOR,
                                1,
                                "minecraft:overworld",
                                new BlockPos(-1, 0, 0),
                                new net.minecraft.world.phys.Vec3(-.1, .5, .5),
                                BlockPos.ZERO,
                                Direction.WEST,
                                0,
                                0),
                        second,
                        new DataPort(
                                second,
                                DataPort.Kind.ACTUATOR,
                                2,
                                "minecraft:overworld",
                                new BlockPos(2, 0, 0),
                                new net.minecraft.world.phys.Vec3(2.1, .75, .5),
                                new BlockPos(1, 0, 0),
                                Direction.EAST,
                                8,
                                0));
        assertFalse(new DataBus(List.of(a, b), ports).connected(first, second));
        var joined = new DataBus.Cable(b.site(), b.geometry().connect(4, 0, 0), 0, b.endpoints());
        assertTrue(new DataBus(List.of(a, joined), ports).connected(first, second));
    }

    private static ControlFacts corpus() throws Exception {
        return ControlFacts.load(
                TagParser.parseTag(
                        Files.readString(
                                Path.of("src/test/resources/compat/control/control-1.snbt"))));
    }

    @Test
    void wiringIndexFollowsCableEditsWithoutLosingPortFacts() throws Exception {
        var facts = corpus();
        var cable = facts.cables().iterator().next();
        var id = cable.endpoints().values().iterator().next();
        assertTrue(facts.wired(id));
        facts.removeCable(cable.site());
        assertFalse(facts.wired(id));
        assertNotNull(facts.port(id));
        facts.cable(cable);
        assertTrue(facts.wired(id));
        facts.cable(new DataBus.Cable(cable.site(), cable.geometry(), cable.arms(), Map.of()));
        assertFalse(facts.wired(id));
    }

    @Test
    void clientEndpointCopiesRetainBothSocketIdentities() {
        var ports = Map.of(Direction.WEST, new UUID(0, 11), Direction.EAST, new UUID(0, 12));
        assertEquals(ports, DataBus.loadEndpoints(DataBus.saveEndpoints(ports)));
        var invalid = new ListTag();
        invalid.add(StringTag.valueOf("not an endpoint"));
        assertThrows(IllegalArgumentException.class, () -> DataBus.loadEndpoints(invalid));
    }

    @Test
    void remountingCentersUnusedPortsAndPreservesWiredEntryFacts() {
        for (var kind : List.of(DataPort.Kind.CONTROLLER, DataPort.Kind.POWER_INPUT)) {
            var old =
                    new DataPort(
                            new UUID(0, 1),
                            kind,
                            0,
                            "minecraft:overworld",
                            BlockPos.ZERO,
                            new net.minecraft.world.phys.Vec3(.3, .3, -.1),
                            new BlockPos(0, 0, -1),
                            Direction.SOUTH,
                            -7,
                            8);
            var current =
                    new DataPort(
                            new UUID(0, 2),
                            kind,
                            0,
                            old.dimension(),
                            old.owner(),
                            new net.minecraft.world.phys.Vec3(.5, .5, -.1),
                            old.cell(),
                            old.face(),
                            0,
                            0);
            var unused = DataPorts.remount(old, current, false);
            assertEquals(old.id(), unused.id());
            assertEquals(0, unused.u());
            assertEquals(0, unused.v());
            var wired = DataPorts.remount(old, current, true);
            assertEquals(old.id(), wired.id());
            assertEquals(old.cell(), wired.cell());
            assertEquals(old.face(), wired.face());
            assertEquals(old.u(), wired.u());
            assertEquals(old.v(), wired.v());
            assertEquals(current.centre(), wired.centre());
            assertEquals(wired, DataPort.load(wired.save()));
            var otherSide =
                    new DataPort(
                            current.id(),
                            kind,
                            0,
                            current.dimension(),
                            current.owner(),
                            current.centre(),
                            current.cell(),
                            current.face().getOpposite(),
                            0,
                            0);
            assertEquals(
                    old,
                    DataPorts.remount(old, otherSide, true),
                    "A connected terminal stays on its built side until rewired");
        }
    }

    @Test
    void omnidirectionalSupplyRetainsThePrimarySocketAndSavedFaceIdentities() {
        var primary =
                new DataPort(
                        new UUID(0, 600),
                        DataPort.Kind.POWER_OUTPUT,
                        0,
                        "minecraft:overworld",
                        BlockPos.ZERO,
                        new net.minecraft.world.phys.Vec3(1.02, .5, .5),
                        new BlockPos(1, 0, 0),
                        Direction.WEST,
                        0,
                        0);
        var faces = com.wormzjl.icheme.runtime.power.PowerPorts.omnidirectional(primary);
        assertEquals(6, faces.size());
        assertEquals(6, faces.stream().map(DataPort::id).distinct().count());
        assertEquals(
                java.util.EnumSet.allOf(Direction.class),
                faces.stream().map(DataPort::face).collect(java.util.stream.Collectors.toSet()));
        assertSame(
                primary,
                faces.stream().filter(p -> p.id().equals(primary.id())).findFirst().orElseThrow());
        var facts = new ControlFacts();
        faces.forEach(facts::port);
        var read = ControlFacts.load(facts.save(new CompoundTag(), null));
        assertEquals(
                faces,
                com.wormzjl.icheme.runtime.power.PowerPorts.omnidirectional(
                        read.port(primary.id())));
        for (var port : faces) {
            assertEquals(DataPort.Kind.POWER_OUTPUT, port.kind());
            assertEquals(0, port.u());
            assertEquals(0, port.v());
        }
    }

    @Test
    void powerFactsRoundTripAndCannotJoinTheDataBus() throws Exception {
        var facts =
                ControlFacts.load(
                        TagParser.parseTag(
                                Files.readString(
                                        Path.of(
                                                "src/test/resources/compat/control/control-3.snbt"))));
        var power =
                new DataBus(
                        facts.cables(),
                        facts.ports(),
                        com.wormzjl.icheme.science.equipment.CableMedium.POWER);
        assertTrue(power.connected(new UUID(0, 1), new UUID(0, 2)));
        assertFalse(
                new DataBus(facts.cables(), facts.ports())
                        .connected(new UUID(0, 1), new UUID(0, 2)));
        assertEquals(
                Set.of(new UUID(0, 1), new UUID(0, 2)),
                power.connected(facts.cables().iterator().next().site()));
        var wires = new ArrayList<>(facts.cables());
        var second = wires.get(1);
        wires.set(
                1,
                new DataBus.Cable(
                        second.site(),
                        new ConduitGeometry.Key(
                                second.geometry().joints(), second.geometry().offsets()),
                        second.arms(),
                        second.endpoints()));
        assertFalse(
                new DataBus(
                                wires,
                                facts.ports(),
                                com.wormzjl.icheme.science.equipment.CableMedium.POWER)
                        .connected(new UUID(0, 1), new UUID(0, 2)));
        assertTrue(
                corpus().cables().stream()
                        .allMatch(
                                c ->
                                        c.geometry().medium()
                                                == com.wormzjl.icheme.science.equipment.CableMedium
                                                        .DATA));
    }
}
