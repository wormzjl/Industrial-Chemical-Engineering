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
    /** 4: a rack lane's frame (W5c C2); 3 and 2 read as before, their lanes with no frame. */
    public static final int FORMAT = 4;
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
    /** The cable sites by dimension and chunk: an index of the facts, never saved (registry first, W5c C2). */
    private final Map<String, Map<Long, Set<DataBus.Site>>> cableChunks = new HashMap<>();
    /**
     * The cables the re-alignment found another block standing in (W5c C2, option Q1): the buses leave
     * them out. Engine state, never saved: a chunk load checks them again.
     */
    private final Set<DataBus.Site> held = new HashSet<>();
    /** The controllers and consoles whose cell another block holds (W5c C3): a held controller is off. Never saved. */
    private final Set<UUID> heldPanels = new HashSet<>();

    private static long chunk(BlockPos cell) {
        return net.minecraft.world.level.ChunkPos.asLong(cell.getX() >> 4, cell.getZ() >> 4);
    }

    private void index(DataBus.Site site) {
        cableChunks
                .computeIfAbsent(site.dimension(), k -> new HashMap<>())
                .computeIfAbsent(chunk(site.cell()), k -> new LinkedHashSet<>())
                .add(site);
    }

    private void unindex(DataBus.Site site) {
        var chunks = cableChunks.get(site.dimension());
        var sites = chunks == null ? null : chunks.get(chunk(site.cell()));
        if (sites == null || !sites.remove(site) || !sites.isEmpty()) return;
        chunks.remove(chunk(site.cell()));
        if (chunks.isEmpty()) cableChunks.remove(site.dimension());
    }

    /** The cable sites in chunk {@code chunk} of {@code dimension}. */
    public List<DataBus.Site> cablesIn(String dimension, long chunk) {
        var chunks = cableChunks.get(dimension);
        var sites = chunks == null ? null : chunks.get(chunk);
        return sites == null ? List.of() : List.copyOf(sites);
    }

    /** Whether cable {@code site} is held. */
    public boolean held(DataBus.Site site) {
        return held.contains(site);
    }

    /** The held cable sites. */
    public Set<DataBus.Site> held() {
        return Set.copyOf(held);
    }

    /** Holds or releases cable {@code site}; its bus is rebuilt. Whether that changes it. */
    public boolean hold(DataBus.Site site, boolean on) {
        if (on ? !cables.containsKey(site) || !held.add(site) : !held.remove(site)) return false;
        topologyRevision = Math.addExact(topologyRevision, 1);
        return true;
    }

    /** The controllers and consoles whose position is in chunk {@code chunk} of {@code dimension} (a scan). */
    public List<UUID> panelsIn(String dimension, long chunk) {
        var out = new ArrayList<UUID>();
        for (var p : panels.values()) if (p.dimension().equals(dimension) && chunk(p.position()) == chunk) out.add(p.id());
        return out;
    }

    /** Whether controller or console {@code id} is held. */
    public boolean heldPanel(UUID id) {
        return heldPanels.contains(id);
    }

    /** The held controllers and consoles. */
    public Set<UUID> heldPanels() {
        return Set.copyOf(heldPanels);
    }

    /** Holds or releases controller or console {@code id}. Whether that changes it. */
    public boolean holdPanel(UUID id, boolean on) {
        return on ? panels.containsKey(id) && heldPanels.add(id) : heldPanels.remove(id);
    }

    /** The cables the buses join: all but the held ones. */
    public Collection<DataBus.Cable> standing() {
        if (held.isEmpty()) return cables();
        return cables.values().stream().filter(c -> !held.contains(c.site())).toList();
    }
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
        var old = cables.put(cable.site(), cable);
        if (cable.equals(old)) return;
        if (old == null) index(cable.site());
        wiredPorts = null;
        changed();
        lineChanges.accept(cable.site());
    }

    public void removeCable(DataBus.Site site) {
        if (cables.remove(site) != null) {
            unindex(site);
            held.remove(site);
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
            heldPanels.remove(id);
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
                || !legacy
                        && tag.getInt("format") != 2
                        && tag.getInt("format") != 3
                        && tag.getInt("format") != FORMAT
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
            if (cable.frame() != null && tag.getInt("format") < 4)
                throw new IllegalArgumentException("A cable frame in control facts of format " + tag.getInt("format"));
            facts.index(cable.site());
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
