package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

import java.util.*;

/**
 * The operator's view: the menus that asked for it, each controller's status and the last
 * publication a menu replays on opening. A publication is built only at a cadence and only for open
 * menus.
 */
final class ControlPublication {
    private record Published(CompoundTag view, long topology) {}

    private final FluidWorldAuthority world;
    private final ControlFacts facts;
    private final ControlHosts hosts;
    private final Set<ControlPanelMenu> watchers =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID, Published> published = new HashMap<>();
    private final Map<UUID, String> status = new HashMap<>();

    ControlPublication(FluidWorldAuthority world, ControlFacts facts, ControlHosts hosts) {
        this.world = world;
        this.facts = facts;
        this.hosts = hosts;
    }

    void watch(ControlPanelMenu menu) {
        watchers.add(menu);
        var cached = published.get(menu.panel());
        if (cached != null && cached.topology() == facts.topologyRevision())
            menu.publish(cached.view());
    }

    void unwatch(ControlPanelMenu menu) {
        watchers.remove(menu);
    }

    /** Whether no menu asks for a publication. */
    boolean idle() {
        return watchers.isEmpty();
    }

    void status(UUID panel, String state) {
        status.put(panel, state);
    }

    /** Forgets a removed controller's status and cached publication. */
    void forget(UUID panel) {
        status.remove(panel);
        published.remove(panel);
    }

    /** Adds the hosts each watched menu's bus reaches: the publication shows their readings. */
    void watchedHosts(Set<Long> needed) {
        for (var menu : watchers)
            for (var id : hosts.bus().connected(menu.panel())) {
                var p = facts.port(id);
                if (p != null && p.host() > 0) needed.add(p.host());
            }
    }

    void publish(Map<UUID, ControlInput> sensors, Map<UUID, Sensor.Reading> readings) {
        // Publication is only built for active requests. Opening a menu only replays this cache.
        watchers.removeIf(m -> !m.stillValid(m.player()) || m.player().containerMenu != m);
        for (var menu : watchers) {
            var view = snapshot(menu, sensors, readings);
            published.put(menu.panel(), new Published(view, facts.topologyRevision()));
            menu.publish(view);
        }
    }

    private String label(long id) {
        var record = world.registrations().get(id);
        if (record == null) return "#" + id;
        if (record.device().control() instanceof FlowControl.ControlValve)
            return String.format(Locale.ROOT, "CV-%04d", id);
        if (record.device().kind()
                == com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.DIVERTER_VALVE)
            return String.format(Locale.ROOT, "DV-%04d", id);
        var heat = record.device().heat();
        if (heat != null)
            return String.format(
                    Locale.ROOT,
                    "%s-%04d / %s",
                    switch (heat.unit()) {
                        case EXCHANGER -> "HX";
                        case KETTLE -> "RB";
                        case AIR_COOLER -> "AC";
                        case FIRED_HEATER -> "FH";
                        case STILL -> "BS";
                    },
                    id,
                    heat.side());
        return record.device().kind().name() + " #" + id;
    }

    private static <T> List<T> page(List<T> values, int requested, int size) {
        int page = Math.clamp(requested, 0, Math.max(0, (values.size() - 1) / size)),
                from = page * size;
        return new ArrayList<>(values.subList(from, Math.min(values.size(), from + size)));
    }

    /**
     * Structural location only; a cached menu publication never loads a chunk or samples the
     * process.
     */
    private void sensorLocation(CompoundTag tag, ControlInput sensor) {
        if (sensor.physical() == null) {
            var binding = hosts.binding(sensor);
            if (binding != null) {
                tag.putLong("equipment", binding.host());
                var o = binding.origin();
                double[] point = null;
                if (sensor.role().startsWith("HEAT:")) {
                    String probe =
                            "temperature_" + sensor.role().substring(5).toLowerCase(Locale.ROOT);
                    point =
                            EquipmentInstruments.of(binding.shape()).probes().stream()
                                    .filter(p -> p.id().equals(probe))
                                    .map(EquipmentInstruments.Probe::centre)
                                    .findFirst()
                                    .orElse(null);
                }
                var position =
                        point == null
                                ? new BlockPos(o.x(), o.y(), o.z())
                                : BlockPos.containing(
                                        o.x() + point[0], o.y() + point[1], o.z() + point[2]);
                tag.putLong("position", position.asLong());
                tag.putBoolean("mountLocated", point != null);
                return;
            }
        }
        long identity = sensor.key().host();
        var host = world.instrumentHost(identity);
        var registration = world.registrations().get(identity);
        if (host != null && !host.pipe()
                || registration != null
                        && registration.device().kind()
                                != com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind
                                        .PIPE) tag.putLong("equipment", identity);
        if (host != null) {
            var mount =
                    Sensor.mounts(host, sensor.kind()).stream()
                            .filter(m -> m.id().equals(sensor.key().mount()))
                            .findFirst()
                            .orElse(null);
            var cell =
                    mount == null
                            ? host.entity().getBlockPos()
                            : BlockPos.containing(mount.centre());
            tag.putLong("position", cell.asLong());
            tag.putBoolean("mountLocated", mount != null);
            return;
        }
        var port = hosts.port(identity);
        if (port != null) {
            tag.putLong("position", port.owner().asLong());
            return;
        }
        if (registration != null) {
            var p = registration.device().position();
            tag.putLong("position", new BlockPos(p.x(), p.y(), p.z()).asLong());
        }
    }

    /**
     * The raised alarms of the equipment on {@code owner}'s data bus (batch 2026-11-09-furnace-fuel-power): each alarm channel
     * whose reading is 1, by label, with its equipment's place. Read from the readings this publication already took.
     */
    private ListTag alarms(UUID owner, Map<UUID, ControlInput> sensors, Map<UUID, Sensor.Reading> readings) {
        var reached = new HashSet<Long>();
        for (var id : hosts.bus().connected(owner)) {
            var port = facts.port(id);
            if (port != null && port.host() > 0) reached.add(port.host());
        }
        var out = new ListTag();
        sensors.values().stream()
                .filter(s -> s.kind() == Sensor.Kind.ALARM && s.role().startsWith("ALARM:") && reached.contains(s.key().host()))
                .sorted(Comparator.comparing(s -> s.label(facts)))
                .forEach(s -> {
                    var r = readings.get(s.instance());
                    if (r == null || r.value() < .5) return;
                    var tag = new CompoundTag();
                    tag.putString("label", String.format(Locale.ROOT, "EQ-%04d", s.key().host()));
                    tag.putString("alarm", s.role().substring(6));
                    sensorLocation(tag, s);
                    out.add(tag);
                });
        return out;
    }

    private CompoundTag snapshot(
            ControlPanelMenu menu,
            Map<UUID, ControlInput> sensors,
            Map<UUID, Sensor.Reading> readings) {
        UUID owner = menu.panel();
        var root = new CompoundTag();
        var controllers = new ListTag();
        var station = facts.panel(owner);
        if (station == null) return root;
        var connected = hosts.bus().connected(owner);
        var visible =
                facts.panels().stream()
                        .filter(
                                p ->
                                        !p.dcs()
                                                && (p.id().equals(owner)
                                                        || station.dcs()
                                                                && connected.contains(p.id())))
                        .sorted(Comparator.comparing(p -> p.position().toShortString()))
                        .toList();
        root.putInt("controllerPages", Math.max(1, (visible.size() + 31) / 32));
        root.putInt(
                "controllerPage",
                Math.clamp(menu.controllerPage(), 0, Math.max(0, (visible.size() - 1) / 32)));
        for (var p : page(visible, menu.controllerPage(), 32)) {
            var row = p.save();
            row.putString("status", status.getOrDefault(p.id(), "WAITING"));
            row.putString(
                    "controllerId",
                    "LC-" + p.id().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            var hostIds = hosts.visibleHosts(p.id());
            var inputChoices =
                    sensors.values().stream()
                            .filter(s -> hostIds.contains(s.key().host()))
                            .sorted(Comparator.comparing(s -> s.label(facts)))
                            .toList();
            row.putInt("inputPages", Math.max(1, (inputChoices.size() + 63) / 64));
            row.putInt(
                    "inputPage",
                    Math.clamp(menu.devicePage(), 0, Math.max(0, (inputChoices.size() - 1) / 64)));
            var inputRows = page(inputChoices, menu.devicePage(), 64);
            inputChoices.stream()
                    .filter(
                            s ->
                                    s.instance().equals(p.settings().sensor())
                                            && !inputRows.contains(s))
                    .findFirst()
                    .ifPresent(inputRows::add);
            var inputs = new ListTag();
            for (var s : inputRows) {
                var in = new CompoundTag();
                in.putUUID("id", s.instance());
                in.putString("label", s.label(facts));
                sensorLocation(in, s);
                in.putString("kind", s.kind().name());
                in.putString("unit", Sensor.primaryUnit(s.kind()).name());
                Sensor.secondaryUnit(s.kind()).ifPresent(u -> in.putString("secondUnit", u.name()));
                var r = readings.get(s.instance());
                if (r != null) {
                    in.putDouble("value", r.value());
                    in.putDouble("secondary", r.secondary());
                    in.putDouble("span", r.span());
                    in.putString("quality", r.availability().name());
                    in.putString("unit", r.unit().name());
                    if (r.secondaryUnit().isPresent())
                        in.putString("secondUnit", r.secondaryUnit().get().name());
                }
                if (s.physical() == null && !BuiltInInputs.volumeAvailable(s))
                    in.remove("secondUnit");
                inputs.add(in);
            }
            row.put("inputs", inputs);
            var outputChoices =
                    world.registrations().values().stream()
                            .filter(
                                    r ->
                                            hostIds.contains(r.device().id())
                                                    && !r.device().valvedSink()
                                                    && (r.device().control()
                                                                    instanceof
                                                                    FlowControl.ControlValve
                                                            || r.device().heat() != null
                                                                    && r.device()
                                                                            .heat()
                                                                            .controllable()
                                                            // A batch still's pot and a diverter valve (owner decisions O1, O2).
                                                            || r.device().stillPot()
                                                            || r.device().kind()
                                                                    == com.wormzjl.icheme.science
                                                                            .fluid.topology
                                                                            .TopologyCompiler.Kind
                                                                            .DIVERTER_VALVE))
                            .sorted(Comparator.comparingLong(r -> r.device().id()))
                            .toList();
            row.putInt("outputPages", Math.max(1, (outputChoices.size() + 63) / 64));
            row.putInt(
                    "outputPage",
                    Math.clamp(menu.devicePage(), 0, Math.max(0, (outputChoices.size() - 1) / 64)));
            var outputRows = page(outputChoices, menu.devicePage(), 64);
            outputChoices.stream()
                    .filter(
                            r ->
                                    r.device().id() == p.settings().output()
                                            && !outputRows.contains(r))
                    .findFirst()
                    .ifPresent(outputRows::add);
            var outputs = new ListTag();
            for (var r : outputRows) {
                var out = new CompoundTag();
                out.putLong("id", r.device().id());
                out.putString("label", label(r.device().id()));
                outputs.add(out);
            }
            row.put("outputs", outputs);
            controllers.add(row);
        }
        root.put("controllers", controllers);
        root.put("alarms", alarms(owner, sensors, readings));
        if (station.dcs()) columns(root, menu, owner);
        return root;
    }

    /**
     * A DCS's column page (W6.2): every column whose data socket its bus reaches, and where the menu chose one, that
     * column's Operation view, a read of the engine's cached view in the assembly codec's bytes.
     */
    private void columns(CompoundTag root, ControlPanelMenu menu, UUID owner) {
        var list = new ListTag();
        for (long host : hosts.columns(owner)) {
            var cell = hosts.column(host);
            var tag = new CompoundTag();
            tag.putLong("id", host);
            tag.putString("label", String.format(Locale.ROOT, "COL-%04d", host));
            tag.putLong("position", cell.origin().asLong());
            list.add(tag);
            if (host != menu.column() || !(cell.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) continue;
            var view = com.wormzjl.icheme.runtime.assembly.AssemblyService.operation(level, cell.origin(), null,
                    valve -> com.wormzjl.icheme.network.FluidNetwork.opening(world, valve));
            if (view != null) {
                root.putLong("column", host);
                root.putByteArray("operation", com.wormzjl.icheme.network.AssemblyNetwork.encodeOperation(view));
            }
        }
        root.put("columns", list);
    }
}
