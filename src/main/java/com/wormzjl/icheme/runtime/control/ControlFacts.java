package com.wormzjl.icheme.runtime.control;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.*;
import java.util.*;

/**
 * Registered cable, socket and controller facts persist independently of loaded chunks.
 * Connectivity is rebuilt.
 */
public final class ControlFacts extends SavedData {
    public static final int FORMAT = 3;
    public static final String NAME = "icheme_control";

    public record Panel(
            UUID id,
            String dimension,
            BlockPos position,
            boolean dcs,
            long revision,
            ControlSettings settings) {
        public Panel {
            Objects.requireNonNull(id);
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(position);
            Objects.requireNonNull(settings);
            position = position.immutable();
            if (net.minecraft.resources.ResourceLocation.tryParse(dimension) == null
                    || dimension.isBlank()
                    || dimension.length() > 128
                    || revision < 0
                    || dcs && settings.enabled())
                throw new IllegalArgumentException("Invalid control panel");
        }

        public Panel edit(ControlSettings next) {
            return new Panel(id, dimension, position, dcs, Math.addExact(revision, 1), next);
        }

        public CompoundTag save() {
            var t = new CompoundTag();
            t.putUUID("id", id);
            t.putString("dimension", dimension);
            t.putLong("position", position.asLong());
            t.putBoolean("dcs", dcs);
            t.putLong("revision", revision);
            t.put("settings", settings.save());
            return t;
        }

        public static Panel load(CompoundTag t) {
            if (!t.getAllKeys()
                            .equals(
                                    Set.of(
                                            "id",
                                            "dimension",
                                            "position",
                                            "dcs",
                                            "revision",
                                            "settings"))
                    || !t.hasUUID("id")
                    || !t.contains("dimension", 8)
                    || !t.contains("position", 4)
                    || !t.contains("dcs", 1)
                    || !t.contains("revision", 4)
                    || !t.contains("settings", 10))
                throw new IllegalArgumentException("Damaged control panel facts");
            return new Panel(
                    t.getUUID("id"),
                    t.getString("dimension"),
                    BlockPos.of(t.getLong("position")),
                    t.getBoolean("dcs"),
                    t.getLong("revision"),
                    ControlSettings.load(t.getCompound("settings")));
        }
    }

    private final Map<DataBus.Site, DataBus.Cable> cables = new HashMap<>();
    private final Map<UUID, DataPort> ports = new HashMap<>();
    private final Map<UUID, Panel> panels = new HashMap<>();
    private final Map<UUID, SensorId> sensorIds = new HashMap<>();
    private long nextSensorId = 1;

    public SensorId sensorId(UUID instance) {
        return sensorIds.get(instance);
    }

    public void requireSensorIds(
            Collection<com.wormzjl.icheme.runtime.instrument.Sensor.Installed> sensors) {
        for (var sensor : sensors) {
            var id = sensorIds.get(sensor.instance());
            if (id != null && id.kind() != sensor.kind())
                throw new IllegalArgumentException("Sensor identity differs between saved facts");
        }
    }

    public SensorId identify(com.wormzjl.icheme.runtime.instrument.Sensor.Installed sensor) {
        var existing = sensorIds.get(sensor.instance());
        if (existing != null) {
            if (existing.kind() != sensor.kind())
                throw new IllegalArgumentException("Sensor ID kind changed");
            return existing;
        }
        var id = new SensorId(sensor.instance(), nextSensorId, sensor.kind());
        nextSensorId = Math.addExact(nextSensorId, 1);
        sensorIds.put(id.instance(), id);
        setDirty();
        return id;
    }

    private long topologyRevision;

    /**
     * Disposable wiring index; moving or editing a socket does not change the cable endpoint facts.
     */
    private Set<UUID> wiredPorts;

    public boolean wired(UUID port) {
        if (wiredPorts == null) {
            var ids = new HashSet<UUID>();
            for (var cable : cables.values()) ids.addAll(cable.endpoints().values());
            wiredPorts = Set.copyOf(ids);
        }
        return wiredPorts.contains(port);
    }

    public Collection<DataBus.Cable> cables() {
        return List.copyOf(cables.values());
    }

    public Map<UUID, DataPort> ports() {
        return Map.copyOf(ports);
    }

    public Collection<Panel> panels() {
        return List.copyOf(panels.values());
    }

    public DataBus.Cable cable(DataBus.Site site) {
        return cables.get(site);
    }

    public DataPort port(UUID id) {
        return ports.get(id);
    }

    public Panel panel(UUID id) {
        return panels.get(id);
    }

    public long topologyRevision() {
        return topologyRevision;
    }

    private java.util.function.Consumer<DataBus.Site> lineChanges = site -> {};

    public void lineChanges(java.util.function.Consumer<DataBus.Site> listener) {
        lineChanges = Objects.requireNonNull(listener);
    }

    private void leadChanged(DataPort port) {
        if (port != null) lineChanges.accept(new DataBus.Site(port.dimension(), port.cell(), 0));
    }

    public void cable(DataBus.Cable cable) {
        if (cable.equals(cables.put(cable.site(), cable))) return;
        wiredPorts = null;
        changed();
        lineChanges.accept(cable.site());
    }

    public void removeCable(DataBus.Site site) {
        if (cables.remove(site) != null) {
            wiredPorts = null;
            changed();
            lineChanges.accept(site);
        }
    }

    public void port(DataPort port) {
        var old = ports.put(port.id(), port);
        if (port.equals(old)) return;
        changed();
        leadChanged(old);
        leadChanged(port);
    }

    public void removePort(UUID id) {
        var old = ports.remove(id);
        if (old != null) {
            changed();
            leadChanged(old);
        }
    }

    public void panel(Panel panel) {
        var before = panels.put(panel.id(), panel);
        if (panel.equals(before)) return;
        if (before == null) changed();
        else setDirty();
    }

    public void removePanel(UUID id) {
        if (panels.remove(id) != null) {
            removePort(id);
            changed();
        }
    }

    private void changed() {
        topologyRevision = Math.addExact(topologyRevision, 1);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("format", FORMAT);
        var wires = new ListTag();
        cables.values().stream()
                .sorted(Comparator.comparing(c -> c.site().toString()))
                .forEach(c -> wires.add(c.save()));
        tag.put("cables", wires);
        var sockets = new ListTag();
        ports.values().stream()
                .sorted(Comparator.comparing(p -> p.id().toString()))
                .forEach(p -> sockets.add(p.save()));
        tag.put("ports", sockets);
        var boxes = new ListTag();
        panels.values().stream()
                .sorted(Comparator.comparing(p -> p.id().toString()))
                .forEach(p -> boxes.add(p.save()));
        tag.put("panels", boxes);
        var ids = new ListTag();
        sensorIds.values().stream()
                .sorted(Comparator.comparingLong(SensorId::number))
                .forEach(id -> ids.add(id.save()));
        tag.put("sensorIds", ids);
        tag.putLong("nextSensorId", nextSensorId);
        return tag;
    }

    public static ControlFacts load(CompoundTag tag) {
        boolean legacy = tag.contains("format", 3) && tag.getInt("format") == 1;
        if (!tag.getAllKeys()
                        .equals(
                                legacy
                                        ? Set.of("format", "cables", "ports", "panels")
                                        : Set.of(
                                                "format",
                                                "cables",
                                                "ports",
                                                "panels",
                                                "sensorIds",
                                                "nextSensorId"))
                || !tag.contains("format", 3)
                || !legacy && tag.getInt("format") != 2 && tag.getInt("format") != FORMAT
                || !tag.contains("cables", 9)
                || !tag.contains("ports", 9)
                || !tag.contains("panels", 9))
            throw new IllegalArgumentException("Unsupported or damaged control facts");
        for (var key : List.of("cables", "ports", "panels")) {
            var list = (ListTag) tag.get(key);
            if (!list.isEmpty() && list.getElementType() != 10)
                throw new IllegalArgumentException("Invalid control facts list: " + key);
        }
        var facts = new ControlFacts();
        var wires = tag.getList("cables", 10);
        var sockets = tag.getList("ports", 10);
        var boxes = tag.getList("panels", 10);
        if (!legacy) {
            if (!tag.contains("sensorIds", 9) || !tag.contains("nextSensorId", 4))
                throw new IllegalArgumentException("Missing sensor ID facts");
            var ids = (ListTag) tag.get("sensorIds");
            if (!ids.isEmpty() && ids.getElementType() != 10 || ids.size() > 1_000_000)
                throw new IllegalArgumentException("Invalid sensor ID table");
            long highest = 0;
            var numbers = new HashSet<Long>();
            for (var t : ids) {
                var id = SensorId.load((CompoundTag) t);
                if (facts.sensorIds.put(id.instance(), id) != null || !numbers.add(id.number()))
                    throw new IllegalArgumentException("Duplicate sensor ID");
                highest = Math.max(highest, id.number());
            }
            facts.nextSensorId = tag.getLong("nextSensorId");
            if (facts.nextSensorId <= highest || facts.nextSensorId < 1)
                throw new IllegalArgumentException("Invalid sensor ID counter");
        }
        if (wires.size() > 1_000_000 || sockets.size() > 100_000 || boxes.size() > 100_000)
            throw new IllegalArgumentException("Control facts exceed capacity");
        for (var value : wires) {
            var cable = DataBus.Cable.load((CompoundTag) value);
            if (facts.cables.put(cable.site(), cable) != null)
                throw new IllegalArgumentException("Duplicate cable facts");
        }
        var hosts = new HashSet<Long>();
        for (var value : sockets) {
            var port = DataPort.load((CompoundTag) value);
            if (facts.ports.put(port.id(), port) != null
                    || port.host() > 0 && !hosts.add(port.host()))
                throw new IllegalArgumentException("Duplicate data port facts");
        }
        var positions = new HashSet<String>();
        for (var value : boxes) {
            var panel = Panel.load((CompoundTag) value);
            if (facts.panels.put(panel.id(), panel) != null
                    || !positions.add(panel.dimension() + ":" + panel.position()))
                throw new IllegalArgumentException("Duplicate controller facts");
        }
        for (var panel : facts.panels.values()) {
            var port = facts.port(panel.id());
            if (port == null
                    || port.kind() != (panel.dcs() ? DataPort.Kind.DCS : DataPort.Kind.CONTROLLER)
                    || !port.owner().equals(panel.position())
                    || !port.dimension().equals(panel.dimension()))
                throw new IllegalArgumentException("Missing or mismatched controller socket");
        }
        return facts;
    }

    /** Read directly before SavedData can replace an unreadable file with an empty one. */
    public static ControlFacts open(MinecraftServer server) {
        var file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat");
        ControlFacts facts;
        if (Files.notExists(file)) {
            facts = new ControlFacts();
            server.overworld().getDataStorage().set(NAME, facts);
            return facts;
        }
        try {
            var root = NbtIo.readCompressed(file, NbtAccounter.create(128L * 1024 * 1024));
            if (!root.contains("data", 10))
                throw new IllegalArgumentException("Missing control facts section");
            facts = load(root.getCompound("data"));
        } catch (NoSuchFileException missing) {
            facts = new ControlFacts();
        } catch (java.io.IOException | RuntimeException damaged) {
            throw new IllegalStateException(
                    "Refusing world with unreadable control facts: " + file, damaged);
        }
        server.overworld().getDataStorage().set(NAME, facts);
        return facts;
    }
}
