package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.world.inventory.AlarmBeaconMenu;
import com.wormzjl.icheme.world.level.block.AlarmBeaconBlock;
import com.wormzjl.icheme.world.level.block.entity.AlarmBeaconBlockEntity;

import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.*;
import java.util.function.Function;

/**
 * The alarm beacons' duties at the control cadence (batch 2026-11-15-fluid-interfaces, W9, plan section 5.1): their
 * registration, the queued edits and acknowledgements, each beacon's decision ({@link AlarmLaw}) and the block state that
 * draws it, and the view their open screens replay. The active and acknowledged state lives here only (decision A6).
 */
final class ControlAlarms {
    /** The input choices a beacon's screen shows per page. */
    static final int PAGE = 64;

    private record Edit(AlarmBeaconMenu menu, UUID id, long revision, AlarmSettings settings) {}

    private final Function<String, ServerLevel> levels;
    private final ControlFacts facts;
    private final ControlHosts hosts;
    private final Map<UUID, AlarmLaw.State> states = new HashMap<>();
    private final Map<UUID, AlarmLaw.Status> status = new HashMap<>();
    /** Each beacon's last sample in its settings' unit, for its screen; absent with no signal. */
    private final Map<UUID, Double> shown = new HashMap<>();
    private final ArrayDeque<Edit> edits = new ArrayDeque<>();
    private final Set<UUID> acknowledged = new LinkedHashSet<>();
    private final Set<AlarmBeaconMenu> watchers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID, CompoundTag> published = new HashMap<>();

    ControlAlarms(Function<String, ServerLevel> levels, ControlFacts facts, ControlHosts hosts) {
        this.levels = levels;
        this.facts = facts;
        this.hosts = hosts;
    }

    /** Whether there is nothing to do at a cadence: no beacon, no screen, nothing queued. */
    boolean idle() {
        return facts.alarms().isEmpty() && watchers.isEmpty() && edits.isEmpty() && acknowledged.isEmpty();
    }

    /**
     * A beacon block entity loaded: the beacon its cell's facts name, or a new one with empty settings (a copied block never
     * takes another beacon's identity), and its socket, remounted as a controller's. A cell whose facts hold a controller
     * keeps it: the beacon there stays unregistered.
     */
    void loaded(AlarmBeaconBlockEntity beacon) {
        var level = beacon.getLevel();
        var dim = level.dimension().location().toString();
        var pos = beacon.getBlockPos();
        for (var p : facts.panels()) if (p.dimension().equals(dim) && p.position().equals(pos)) return;
        var alarm =
                facts.alarms().stream()
                        .filter(a -> a.dimension().equals(dim) && a.position().equals(pos))
                        .findFirst()
                        .orElse(null);
        if (alarm == null) {
            var id = facts.alarm(beacon.identity()) == null && facts.panel(beacon.identity()) == null ? beacon.identity() : UUID.randomUUID();
            alarm = new ControlFacts.Alarm(id, dim, pos, 0, AlarmSettings.EMPTY);
        }
        var mount = DataPorts.alarm(level, pos, beacon.getBlockState().getValue(AlarmBeaconBlock.FACING), alarm.id());
        var port = facts.port(alarm.id());
        if (port == null) port = mount;
        else port = DataPorts.remount(port, mount, facts.wired(port.id()));
        // The socket first: the facts' loader asks every beacon for its socket.
        if (!port.equals(facts.port(alarm.id()))) facts.port(port);
        facts.alarm(alarm);
        if (!alarm.id().equals(beacon.identity()) || !port.equals(beacon.dataPort())) beacon.bind(alarm.id(), port);
        draw(alarm);
    }

    /** Whether an alarm beacon's facts stand in cell {@code pos} of {@code dimension}. */
    boolean standsAt(String dimension, net.minecraft.core.BlockPos pos) {
        for (var a : facts.alarms()) if (a.dimension().equals(dimension) && a.position().equals(pos)) return true;
        return false;
    }

    void remove(UUID id) {
        facts.removeAlarm(id);
        states.remove(id);
        status.remove(id);
        shown.remove(id);
        published.remove(id);
        acknowledged.remove(id);
    }

    /** A hand's acknowledgement of beacon {@code id}: queued, applied at the next cadence (decision A5). */
    void acknowledge(UUID id) {
        if (acknowledged.size() < 4096) acknowledged.add(id);
    }

    void edit(AlarmBeaconMenu menu, UUID id, long revision, AlarmSettings settings) {
        if (edits.size() < 4096) edits.addLast(new Edit(menu, id, revision, settings));
        else menu.answer("BUSY: APPLY AGAIN");
    }

    void watch(AlarmBeaconMenu menu) {
        watchers.add(menu);
        // A replay of the last publication only: opening a screen decides nothing.
        var cached = published.get(menu.alarm());
        if (cached != null) menu.publish(cached);
    }

    void unwatch(AlarmBeaconMenu menu) {
        watchers.remove(menu);
    }

    /** The queued edits, each revision-checked as a controller's; an enabled input must be on the beacon's bus. */
    void applyEdits(Map<UUID, ControlInput> inputs) {
        while (!edits.isEmpty()) {
            var edit = edits.removeFirst();
            var alarm = facts.alarm(edit.id());
            var menu = edit.menu();
            if (alarm == null
                    || !menu.alarm().equals(edit.id())
                    || !menu.stillValid(menu.player())
                    || !menu.player().mayBuild()
                    || alarm.revision() != edit.revision()) {
                menu.answer("SETTINGS CHANGED: APPLY AGAIN");
                continue;
            }
            var after = edit.settings();
            if (after.enabled() && after.source() == AlarmSettings.Source.INPUT) {
                var input = inputs.get(after.sensor());
                if (input == null || !hosts.wired(alarm.id(), input.key().host())) {
                    menu.answer("NO DATA LINK");
                    continue;
                }
            }
            var before = alarm.settings();
            if (!Objects.equals(before.sensor(), after.sensor())
                    || before.source() != after.source()
                    || before.comparison() != after.comparison()
                    || before.enabled() != after.enabled()) states.remove(alarm.id());
            facts.alarm(alarm.edit(after));
            menu.answer("APPLIED");
        }
    }

    /** Adds the hosts whose readings the beacons and their open screens need this cadence. */
    void neededHosts(Map<UUID, ControlInput> inputs, Set<Long> needed) {
        for (var alarm : facts.alarms()) {
            var s = alarm.settings();
            if (!s.enabled()) continue;
            if (s.source() == AlarmSettings.Source.ANY_ALARM) needed.addAll(hosts.visibleHosts(alarm.id()));
            else {
                var input = inputs.get(s.sensor());
                if (input != null && hosts.wired(alarm.id(), input.key().host())) needed.add(input.key().host());
            }
        }
        for (var menu : watchers) needed.addAll(hosts.visibleHosts(menu.alarm()));
    }

    /**
     * Every registered beacon decides after the loops: its acknowledgement first, then its sample and {@link AlarmLaw#decide}.
     * A standing beacon draws the resulting state.
     */
    void run(Map<UUID, ControlInput> inputs, Map<UUID, Sensor.Reading> readings) {
        var acks = Set.copyOf(acknowledged);
        acknowledged.clear();
        for (var alarm : facts.alarms().stream().sorted(Comparator.comparing(a -> a.id().toString())).toList()) {
            var previous = states.getOrDefault(alarm.id(), AlarmLaw.State.NONE);
            if (acks.contains(alarm.id())) previous = AlarmLaw.acknowledge(previous);
            var sample = sample(alarm, inputs, readings);
            var decision = AlarmLaw.decide(alarm.settings(), sample, previous);
            if (decision.state().equals(AlarmLaw.State.NONE)) states.remove(alarm.id());
            else states.put(alarm.id(), decision.state());
            status.put(alarm.id(), decision.status());
            if (sample == null) shown.remove(alarm.id());
            else shown.put(alarm.id(), sample.value());
            draw(alarm);
        }
    }

    /** This beacon's last cadence, OFF before its first decision. */
    AlarmLaw.Status status(UUID id) {
        return status.getOrDefault(id, AlarmLaw.Status.OFF);
    }

    /** Replay a cached decision on a matching block in an already loaded chunk. */
    private void draw(ControlFacts.Alarm alarm) {
        var level = levels.apply(alarm.dimension());
        var pos = alarm.position();
        var chunk = level == null ? null : level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return;
        var state = chunk.getBlockState(pos);
        if (!state.is(ModBlocks.ALARM_BEACON.get())
                || !(chunk.getBlockEntity(pos) instanceof AlarmBeaconBlockEntity beacon)
                || !beacon.identity().equals(alarm.id())) return;
        var lamp = drawn(status(alarm.id()));
        if (state.getValue(AlarmBeaconBlock.STATE) != lamp)
            level.setBlock(pos, state.setValue(AlarmBeaconBlock.STATE, lamp), Block.UPDATE_ALL);
    }

    /** What the block shows for {@code status}. */
    static AlarmBeaconBlock.Shown drawn(AlarmLaw.Status status) {
        return switch (status) {
            case ACTIVE -> AlarmBeaconBlock.Shown.ACTIVE;
            case ACKNOWLEDGED -> AlarmBeaconBlock.Shown.ACKNOWLEDGED;
            default -> AlarmBeaconBlock.Shown.CLEAR;
        };
    }

    /**
     * Beacon {@code alarm}'s sample in its settings' unit, or null where there is no signal (decision A4): its socket on no
     * bus, its input not on the bus, a reading that is not available, a unit the reading does not give.
     */
    private AlarmLaw.Sample sample(ControlFacts.Alarm alarm, Map<UUID, ControlInput> inputs, Map<UUID, Sensor.Reading> readings) {
        var s = alarm.settings();
        if (!s.enabled()) return null;
        if (s.source() == AlarmSettings.Source.ANY_ALARM) {
            var reached = hosts.visibleHosts(alarm.id());
            var channels = new ArrayList<Double>();
            for (var input : inputs.values())
                if (input.kind() == Sensor.Kind.ALARM && input.role().startsWith("ALARM:") && reached.contains(input.key().host())) {
                    var r = readings.get(input.instance());
                    if (r != null && r.availability() == Instrument.Availability.AVAILABLE) channels.add(r.value());
                }
            return AlarmLaw.anyAlarm(facts.wired(alarm.id()), channels);
        }
        var input = inputs.get(s.sensor());
        if (input == null || !hosts.wired(alarm.id(), input.key().host())) return null;
        var r = readings.get(input.instance());
        if (r == null || r.availability() != Instrument.Availability.AVAILABLE) return null;
        if (input.kind() == Sensor.Kind.ALARM) return new AlarmLaw.Sample(r.value(), true);
        if (input.physical() == null && s.unit() == Sensor.Unit.CUBIC_METRE_PER_SECOND && !BuiltInInputs.volumeAvailable(input))
            return null;
        if (s.unit() == r.unit()) return new AlarmLaw.Sample(r.value(), false);
        if (r.secondaryUnit().filter(u -> u == s.unit()).isPresent()) return new AlarmLaw.Sample(r.secondary(), false);
        return null;
    }

    /** The open screens' views, built at the cadence only, and cached for the next opening's replay. */
    void publish(Map<UUID, ControlInput> inputs, Map<UUID, Sensor.Reading> readings) {
        watchers.removeIf(m -> !m.stillValid(m.player()) || m.player().containerMenu != m);
        for (var menu : watchers) {
            var view = view(menu, inputs, readings);
            if (view == null) continue;
            published.put(menu.alarm(), view);
            menu.publish(view);
        }
    }

    private CompoundTag view(AlarmBeaconMenu menu, Map<UUID, ControlInput> inputs, Map<UUID, Sensor.Reading> readings) {
        var alarm = facts.alarm(menu.alarm());
        if (alarm == null) return null;
        var root = alarm.save();
        var st = status.get(alarm.id());
        root.putString("status", st == null ? "WAITING" : st.name());
        var value = shown.get(alarm.id());
        if (value != null) root.putDouble("value", value);
        root.putString("beaconId", "AL-" + alarm.id().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        root.putBoolean("onBus", facts.wired(alarm.id()));
        var reached = hosts.visibleHosts(alarm.id());
        var choices =
                inputs.values().stream()
                        .filter(s -> reached.contains(s.key().host()))
                        .sorted(Comparator.comparing(s -> s.label(facts)))
                        .toList();
        int pages = Math.max(1, (choices.size() + PAGE - 1) / PAGE);
        int page = Math.clamp(menu.inputPage(), 0, pages - 1);
        root.putInt("inputPages", pages);
        root.putInt("inputPage", page);
        var rows = new ArrayList<>(choices.subList(page * PAGE, Math.min(choices.size(), page * PAGE + PAGE)));
        choices.stream().filter(s -> s.instance().equals(alarm.settings().sensor()) && !rows.contains(s)).findFirst().ifPresent(rows::add);
        var list = new ListTag();
        for (var s : rows) {
            var in = new CompoundTag();
            in.putUUID("id", s.instance());
            in.putString("label", s.label(facts));
            in.putString("kind", s.kind().name());
            in.putString("unit", Sensor.primaryUnit(s.kind()).name());
            Sensor.secondaryUnit(s.kind()).ifPresent(u -> in.putString("secondUnit", u.name()));
            if (s.physical() == null && !BuiltInInputs.volumeAvailable(s)) in.remove("secondUnit");
            var r = readings.get(s.instance());
            if (r != null) {
                in.putDouble("value", r.value());
                in.putDouble("secondary", r.secondary());
                in.putString("quality", r.availability().name());
            }
            list.add(in);
        }
        root.put("inputs", list);
        return root;
    }
}
