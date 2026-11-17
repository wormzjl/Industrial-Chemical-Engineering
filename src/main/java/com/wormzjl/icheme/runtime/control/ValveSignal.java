package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * A control valve regulates only on a sensor physically linked to it and selected on its screen (owner, 2026-11-17: "The
 * control valve needs to select sensor first in order to function, like other controlling systems"; batch
 * 2026-11-16-review-plant-sizing, VALVE_SENSOR_REVIEW.md). A sensor is linked when it stands on the valve itself or on a
 * host its data cables reach ({@link ControlService#connectedHosts}); the valve's screen lists those of the kinds a valve
 * regulates on ({@link #choices}), and the player selects one, which writes the law's sensor and source ({@link #select}).
 * Nothing is selected until the player picks: fitting a sensor writes no law. A law whose sensor is not linked has no
 * signal, and the slice takes it bare, so the valve stands open ({@link #slice}); the registry's law stays as saved, and
 * the signal returns when the link does. The valves equipment works itself (a governor, a burner, a flare, a still, an
 * ejector, an engine, a blanket, a converter, an automated shutoff valve) and the valves a running column works
 * ({@link PortPlan#COLUMN_SENSOR}) take no selection ({@link #exempt}). The law holds its own flow or its upstream
 * pressure; it does not read the selected sensor's value (an approved simplification).
 */
public final class ValveSignal {
    private ValveSignal() {
    }

    /** A sensor a valve's screen lists: the installed sensor, its label as the controller box names it, and where it is. */
    public record Choice(Sensor.Installed sensor, String label, @Nullable BlockPos position, boolean mount, long equipment) {
    }

    /** Whether {@code law} takes no selection: equipment works its source, or a running column works it. */
    public static boolean exempt(FlowControl.ControlValve law) {
        return worked(law.source()) || PortPlan.COLUMN_SENSOR.equals(law.sensor());
    }

    /** Whether a valve of {@code source} is worked by its equipment, not by a sensor's signal. */
    static boolean worked(FlowControl.ValveSource source) {
        return switch (source) {
            case NONE, PRESSURE, MASS_FLOW, VOLUME_FLOW -> false;
            case SHAFT_SPEED, BURNER, FLARE, STILL, EJECTOR, ENGINE, BLANKET, CONVERTER, ON_OFF -> true;
        };
    }

    /** Whether a valve regulates on a sensor of {@code kind}: a pressure probe or a flowmeter. */
    static boolean selectable(Sensor.Kind kind) {
        return kind == Sensor.Kind.PRESSURE || kind == Sensor.Kind.FLOW;
    }

    /**
     * Whether {@code law} has its signal, {@code linked} the instances of the sensors linked to its valve: it names no
     * sensor (it is bare already), it takes no selection, or its sensor is linked.
     */
    static boolean linked(FlowControl.ControlValve law, Set<UUID> linked) {
        var sensor = law.sensor();
        return sensor == null || exempt(law) || linked.contains(sensor);
    }

    /** Whether valve {@code host}'s law {@code law} has its signal; reads the accessory store and the data cables' facts. */
    public static boolean linked(FluidWorldAuthority world, long host, FlowControl.ControlValve law) {
        var sensor = law.sensor();
        if (sensor == null || exempt(law)) return true;
        for (long h : hosts(world, host))
            for (var s : world.instruments().sensors(h))
                if (s.instance().equals(sensor)) return selectable(s.kind());
        return false;
    }

    /**
     * Whether valve {@code host} regulates, as its screen reads it: it takes no selection, a controller loop plans it, or its
     * own law names a sensor that is linked. Else it reads NO SIGNAL and stands open.
     */
    public static boolean signal(FluidWorldAuthority world, long host, FlowControl.ControlValve law) {
        return exempt(law) || world.controls().planned(host) || !law.bare() && linked(world, host, law);
    }

    /** The valve and the hosts its data cables reach, the valve first. */
    private static List<Long> hosts(FluidWorldAuthority world, long host) {
        var out = new ArrayList<Long>();
        out.add(host);
        world.controls().connectedHosts(host).stream().sorted().forEach(out::add);
        return out;
    }

    /**
     * The sensors valve {@code host}'s screen lists: the pressure probes and flowmeters on the valve and on the hosts its data
     * cables reach, by label (stable while the plant stands).
     */
    public static List<Choice> choices(FluidWorldAuthority world, long host) {
        var facts = world.controls().facts();
        var seen = new HashSet<UUID>();
        var out = new ArrayList<Choice>();
        for (long h : hosts(world, host))
            for (var s : world.instruments().sensors(h)) {
                if (!selectable(s.kind()) || !seen.add(s.instance())) continue;
                String label = facts.identify(s).label() + " / " + s.kind().name();
                out.add(locate(world, s, label));
            }
        out.sort(Comparator.comparing(Choice::label, Comparator.comparing((String l) -> l.toLowerCase(Locale.ROOT))).thenComparing(c -> c.sensor().instance()));
        return List.copyOf(out);
    }

    /** {@code s} where it stands: its mount's cell where its host is loaded, else its host's place; its equipment unless a pipe. */
    private static Choice locate(FluidWorldAuthority world, Sensor.Installed s, String label) {
        long identity = s.key().host();
        var registration = world.registrations().get(identity);
        var host = world.instrumentHost(identity);
        boolean pipe = host != null ? host.pipe() : registration != null && registration.device().kind() == com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE;
        long equipment = pipe ? 0 : identity;
        if (host != null) {
            var mount = Sensor.mounts(host, s.kind()).stream().filter(m -> m.id().equals(s.key().mount())).findFirst().orElse(null);
            return new Choice(s, label, mount == null ? host.entity().getBlockPos() : BlockPos.containing(mount.centre()), mount != null, equipment);
        }
        if (registration == null) return new Choice(s, label, null, false, equipment);
        var p = registration.device().position();
        return new Choice(s, label, new BlockPos(p.x(), p.y(), p.z()), false, equipment);
    }

    /**
     * The law a slice takes for a valve whose registry law is {@code base}: a controller loop's {@code plan} where the last
     * cadence made one (a plan is a wired loop's), else {@code base} where it has its signal, else {@code base} without its
     * sensor: bare, its revision kept so its line does not change identity from slice to slice.
     */
    static FlowControl.ControlValve slice(FlowControl.ControlValve base, @Nullable FlowControl.ControlValve plan, boolean linked) {
        if (plan != null) return plan;
        return linked ? base : base.withSource(base.source(), null, base.revision());
    }

    /** {@link #slice(FlowControl.ControlValve, FlowControl.ControlValve, boolean)} for valve {@code host}, read from the world. */
    static FlowControl.ControlValve slice(FluidWorldAuthority world, long host, FlowControl.ControlValve base, @Nullable FlowControl.ControlValve plan) {
        return slice(base, plan, plan != null || linked(world, host, base));
    }

    /**
     * The law the player's selection writes over {@code law}: none ({@code sensor} null) clears its sensor, so the valve
     * stands open with its targets kept; a pressure probe sets it to hold the upstream pressure {@code pressure}; a flowmeter
     * to hold the mass flow {@code mass} or the volume flow {@code volume}, as {@code flow} chooses. Its revision moves on.
     */
    public static FlowControl.ControlValve select(FlowControl.ControlValve law, @Nullable UUID sensor, Sensor.@Nullable Kind kind, FlowControl.ValveSource flow,
            double pressure, double mass, double volume) {
        long revision = Math.addExact(law.revision(), 1);
        if (sensor == null) return law.withSource(law.source(), null, revision);
        if (kind == null || !selectable(kind)) throw new IllegalArgumentException("A control valve regulates on a pressure probe or a flowmeter");
        FlowControl.ValveSource source;
        if (kind == Sensor.Kind.PRESSURE) source = FlowControl.ValveSource.PRESSURE;
        else if (flow == FlowControl.ValveSource.MASS_FLOW || flow == FlowControl.ValveSource.VOLUME_FLOW) source = flow;
        else throw new IllegalArgumentException("Flowmeter required for flow control");
        return new FlowControl.ControlValve(source, pressure, mass, volume, sensor, revision);
    }

    /**
     * {@code law} with its sensor cleared where it names the removed sensor {@code removed} and takes a selection; null where
     * it is to stay as it is.
     */
    public static FlowControl.@Nullable ControlValve released(FlowControl.ControlValve law, UUID removed) {
        if (exempt(law) || !removed.equals(law.sensor())) return null;
        return law.withSource(law.source(), null, Math.addExact(law.revision(), 1));
    }
}
