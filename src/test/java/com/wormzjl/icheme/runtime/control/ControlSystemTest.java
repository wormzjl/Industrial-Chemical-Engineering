package com.wormzjl.icheme.runtime.control;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.runtime.fluid.ConverterUnit;
import com.wormzjl.icheme.runtime.fluid.Converters;
import com.wormzjl.icheme.runtime.fluid.EquipmentAlarm;
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

    /** A first decision (no earlier sample, NaN) acts on the level error alone: the actuated flow it reads is not the balance
     * (the level loop's draining drum, FEED_STEP_HOLD_REVIEW.md: 0.737 m³/s out on a 0.199 m³/s feed asked 0.667 m³/s). A
     * reading that has not advanced since the earlier sample keeps the actuated flow as the balance. */
    @Test
    void aFirstDecisionActsOnTheLevelErrorAlone() {
        assertEquals(0, InventoryControl.target(.114, 1.555, .737, Double.NaN, 0, 20, true));
        assertEquals((1.555 - .114) / 20, InventoryControl.target(.114, 1.555, .737, Double.NaN, 0, 20, false), 1e-15);
        assertEquals((2 - 1.555) / 20, InventoryControl.target(2, 1.555, .737, Double.NaN, 0, 20, true), 1e-15);
        assertEquals(0, InventoryControl.target(2, 1.555, .737, Double.NaN, 0, 20, false));
        assertEquals(.2 - (3.976 - 1.555) / 20, InventoryControl.target(1.555, 3.976, .2, 1.555, 0, 20, true), 1e-15);
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
    /** W5c C2 (option Q2): a rack lane keeps its rack cell's frame from format 4; a frame in an older format is refused. */
    @Test
    void aRackLanesFrameIsAFormat4Fact() throws Exception {
        var tag =
                TagParser.parseTag(
                        Files.readString(Path.of("src/test/resources/compat/control/control-4.snbt")));
        var lane =
                ControlFacts.load(tag).cables().stream()
                        .filter(c -> c.site().lane() > 0)
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                new com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger.RackFrame(
                        com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.EAST,
                        com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger.RackShape.STRAIGHT),
                lane.frame());
        var older = tag.copy();
        older.putInt("format", 3);
        assertThrows(IllegalArgumentException.class, () -> ControlFacts.load(older));
        var three =
                ControlFacts.load(
                        TagParser.parseTag(
                                Files.readString(
                                        Path.of("src/test/resources/compat/control/control-3.snbt"))));
        assertTrue(three.cables().stream().allMatch(c -> c.frame() == null), "format 3 lanes hold no frame");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DataBus.Cable(
                                new DataBus.Site(lane.site().dimension(), lane.site().cell(), 0),
                                new ConduitGeometry.Key(48, 0, com.wormzjl.icheme.science.equipment.CableMedium.DATA),
                                0,
                                Map.of(),
                                lane.frame()),
                "a cable block lies in no rack frame");
    }

    /** W5c C2 (option Q1): a held cable leaves the buses, never the facts. */
    @Test
    void aHeldCableLeavesTheBusAndNeverTheFacts() throws Exception {
        var facts =
                ControlFacts.load(
                        TagParser.parseTag(
                                Files.readString(
                                        Path.of("src/test/resources/compat/control/control-3.snbt"))));
        var power = com.wormzjl.icheme.science.equipment.CableMedium.POWER;
        var site = facts.cables().iterator().next().site();
        long revision = facts.topologyRevision();
        assertTrue(facts.hold(site, true));
        assertFalse(facts.hold(site, true), "held once");
        assertTrue(facts.topologyRevision() > revision, "the buses are rebuilt");
        assertFalse(new DataBus(facts.standing(), facts.ports(), power).connected(new UUID(0, 1), new UUID(0, 2)));
        assertEquals(2, facts.save(new CompoundTag(), null).getList("cables", 10).size(), "the fact stays");
        assertTrue(facts.hold(site, false));
        assertTrue(new DataBus(facts.standing(), facts.ports(), power).connected(new UUID(0, 1), new UUID(0, 2)));
        assertFalse(facts.hold(new DataBus.Site(site.dimension(), site.cell().above(), 0), true), "no fact, no hold");
    }
    /** W5c C3 (option Q1): a held controller is state, never a fact, and it leaves with its facts. */
    @Test
    void aHeldControllerIsNeverAFact() throws Exception {
        var facts =
                ControlFacts.load(
                        TagParser.parseTag(
                                Files.readString(
                                        Path.of("src/test/resources/compat/control/control-2.snbt"))));
        var id = facts.panels().iterator().next().id();
        var before = facts.save(new CompoundTag(), null);
        assertTrue(facts.holdPanel(id, true));
        assertFalse(facts.holdPanel(id, true), "held once");
        assertTrue(facts.heldPanel(id));
        assertEquals(before, facts.save(new CompoundTag(), null), "a hold is never saved");
        assertFalse(facts.holdPanel(new UUID(7, 7), true), "no facts, no hold");
        facts.removePanel(id);
        assertFalse(facts.heldPanel(id), "the hold leaves with the controller");
    }

    /**
     * A DCS's column page (W6.2): a console whose bus reaches the column runs Start, the set points and Stop at the column;
     * one with no data link, or a controller box, is refused and the column sees nothing.
     */
    @Test
    void aDcsColumnCommandReachesTheColumnOnlyOverItsDataLink() {
        var calls = new ArrayList<String>();
        var door = new ColumnCommands.Door() {
            @Override public String start(long host) { calls.add("start " + host); return null; }
            @Override public void stop(long host) { calls.add("stop " + host); }
            @Override public String setpoints(long host, List<Double> values) { calls.add("apply " + host + " " + values); return values.isEmpty() ? "the set points do not fit the column's design" : null; }
        };
        var start = new ColumnCommands.Command(12, ColumnCommands.Action.START, List.of());
        assertEquals(ColumnCommands.NO_DATA_LINK, ColumnCommands.run(true, true, false, start, door));
        assertEquals(ColumnCommands.NOT_A_DCS, ColumnCommands.run(false, true, true, start, door));
        assertEquals(ColumnCommands.CHANGED, ColumnCommands.run(true, false, true, start, door));
        assertEquals(List.of(), calls, "a refused command reaches no column");
        assertEquals(ColumnCommands.APPLIED, ColumnCommands.run(true, true, true, start, door));
        assertEquals(ColumnCommands.APPLIED, ColumnCommands.run(true, true, true, new ColumnCommands.Command(12, ColumnCommands.Action.APPLY, List.of(30.0, 318.15)), door));
        assertEquals("the set points do not fit the column's design", ColumnCommands.run(true, true, true, new ColumnCommands.Command(12, ColumnCommands.Action.APPLY, List.of()), door),
                "the column's own refusal is the answer");
        assertEquals(ColumnCommands.APPLIED, ColumnCommands.run(true, true, true, new ColumnCommands.Command(12, ColumnCommands.Action.STOP, List.of()), door));
        assertEquals(List.of("start 12", "apply 12 [30.0, 318.15]", "apply 12 []", "stop 12"), calls);
        assertThrows(IllegalArgumentException.class, () -> new ColumnCommands.Command(12, ColumnCommands.Action.APPLY, Collections.nCopies(65, 1.0)));
    }

    /** A feed-rate loop's plan (W6.3): a flow target over the feed's molar mass; a level loop's volume flow at the feed's density over it. */
    @Test
    void aColumnLoopPlansTheFeedInMolesPerSecond() {
        assertEquals(100, ControlLaw.flowFeed(25, .25), 1e-12, "25 kg/s of a 250 g/mol feed is 100 mol/s");
        assertEquals(0, ControlLaw.flowFeed(-1, .25), 0, "no negative plan");
        assertEquals(80, ControlLaw.levelFeed(.025, 800, .25), 1e-12, "0.025 m³/s at 800 kg/m³ of a 250 g/mol feed is 80 mol/s");
        assertEquals(0, ControlLaw.levelFeed(-.1, 800, .25), 0);
    }

    /**
     * A level loop on a column reads its feed valve's density from the pipe whatever the pipe run's sense (batch
     * 2026-11-10-column-failure-modes, stage 5 case 12: a run compiled against the flow read -9.6 kg/s, and the loop
     * answered NO LEVEL BALANCE while the feed ran).
     */
    @Test
    void aColumnLevelLoopReadsItsFeedValveEitherWay() {
        assertEquals(800, ControlLaw.feedDensity(8, .01), 1e-9);
        assertEquals(800, ControlLaw.feedDensity(-8, -.01), 1e-9, "a run compiled against the flow");
        assertTrue(Double.isNaN(ControlLaw.feedDensity(0, 0)), "nothing flowing: no density");
    }

    /**
     * A DCS's converter page (batch 2026-11-12-fluid-conversion, W4): a console whose bus reaches the converter changes one
     * setting of its next settings and queues them; one with no data link, a controller box or a closed menu is refused and
     * the converter sees nothing.
     */
    @Test
    void aDcsConverterCommandReachesTheConverterOnlyOverItsDataLink() {
        var queued = new ArrayList<String>();
        var next = new Converters.Settings[] {new Converters.Settings(ConverterUnit.Mode.OFF, 5, .05, new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN})};
        var door = new ConverterCommands.Door() {
            @Override public Converters.Settings settings(long host) { return host == 12 ? next[0] : null; }
            @Override public String apply(long host, Converters.Settings s) { queued.add(host + " " + s.mode() + " " + s.rate() + " " + s.deadband() + " " + Arrays.toString(s.thresholds())); next[0] = s; return null; }
        };
        var balance = new ConverterCommands.Command(12, ConverterCommands.Action.SET_MODE, ConverterUnit.Mode.BALANCE, List.of());
        assertEquals(ColumnCommands.NO_DATA_LINK, ConverterCommands.run(true, true, false, balance, door));
        assertEquals(ColumnCommands.NOT_A_DCS, ConverterCommands.run(false, true, true, balance, door));
        assertEquals(ColumnCommands.CHANGED, ConverterCommands.run(true, false, true, balance, door));
        assertEquals(List.of(), queued, "a refused command reaches no converter");
        assertEquals(ConverterCommands.NO_CONVERTER, ConverterCommands.run(true, true, true, new ConverterCommands.Command(13, ConverterCommands.Action.SET_MODE, ConverterUnit.Mode.OFF, List.of()), door));
        assertEquals(ColumnCommands.APPLIED, ConverterCommands.run(true, true, true, balance, door));
        assertEquals(ColumnCommands.APPLIED, ConverterCommands.run(true, true, true, new ConverterCommands.Command(12, ConverterCommands.Action.SET_RATE, null, List.of(2.5)), door));
        assertEquals(ColumnCommands.APPLIED, ConverterCommands.run(true, true, true, new ConverterCommands.Command(12, ConverterCommands.Action.SET_DEADBAND, null, List.of(.1)), door));
        assertEquals(ColumnCommands.APPLIED, ConverterCommands.run(true, true, true, new ConverterCommands.Command(12, ConverterCommands.Action.SET_THRESHOLDS, null, List.of(.9, .1, -1.0, -1.0)), door));
        assertEquals(List.of("12 BALANCE 5.0 0.05 [NaN, NaN, NaN, NaN]", "12 BALANCE 2.5 0.05 [NaN, NaN, NaN, NaN]", "12 BALANCE 2.5 0.1 [NaN, NaN, NaN, NaN]",
                "12 BALANCE 2.5 0.1 [0.9, 0.1, -1.0, -1.0]"), queued, "each command changes its own field of the settings already queued");
        var clamped = next[0].clamped(2);
        assertEquals(2, clamped.rate(), 0, "the rate is clamped to the rated flow");
        assertTrue(Double.isNaN(clamped.thresholds()[ConverterUnit.FORGE_HIGH]), "a negative threshold is none");
        assertEquals(Converters.Settings.MIN_DEADBAND, new Converters.Settings(ConverterUnit.Mode.OFF, 1, 0, new double[4]).clamped(2).deadband(), 0);
        assertEquals(Converters.Settings.MAX_DEADBAND, new Converters.Settings(ConverterUnit.Mode.OFF, 1, 2, new double[4]).clamped(2).deadband(), 0);
        assertThrows(IllegalArgumentException.class, () -> new ConverterCommands.Command(12, ConverterCommands.Action.SET_THRESHOLDS, null, List.of(.5)));
        assertThrows(NullPointerException.class, () -> new ConverterCommands.Command(12, ConverterCommands.Action.SET_MODE, null, List.of()));
    }

    /**
     * A converter's level thresholds (W4): either tank at or over its high threshold raises LEVEL HIGH, at or under its low one
     * LEVEL LOW; each clears when the fill crosses back; a threshold of none and an unpublished vessel raise nothing.
     */
    @Test
    void aConverterThresholdRaisesAndClearsItsAlarm() {
        double none = Double.NaN;
        var t = new double[] {.8, .2, none, .1};
        assertEquals(Set.of(), Converters.alarms(Converters.Status.CONVERTING, t, .5, .5));
        assertEquals(Set.of(EquipmentAlarm.LEVEL_HIGH), Converters.alarms(Converters.Status.CONVERTING, t, .8, .5), "the process tank reaches its high threshold");
        assertEquals(Set.of(), Converters.alarms(Converters.Status.CONVERTING, t, .79, .5), "and the alarm clears below it");
        assertEquals(Set.of(EquipmentAlarm.LEVEL_LOW), Converters.alarms(Converters.Status.CONVERTING, t, .5, .1), "the Forge tank reaches its low threshold");
        assertEquals(Set.of(), Converters.alarms(Converters.Status.CONVERTING, t, .5, 1), "the Forge tank has no high threshold");
        assertEquals(Set.of(EquipmentAlarm.LEVEL_LOW, EquipmentAlarm.SOURCE_EMPTY), Converters.alarms(Converters.Status.SOURCE_EMPTY, t, .2, .5));
        assertEquals(Set.of(), Converters.alarms(Converters.Status.IDLE, t, Double.NaN, .5), "no published state raises nothing");
        assertEquals(Set.of(), Converters.alarms(Converters.Status.IDLE, new double[] {none, none, none, none}, 0, 1), "no thresholds raise nothing");
    }
}
