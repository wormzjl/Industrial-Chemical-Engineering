package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.*;

import java.util.*;

/**
 * Stable equipment channels over accepted engine publications. Never samples or solves the process.
 */
final class BuiltInInputs {
    private BuiltInInputs() {}

    record Binding(
            long host,
            EquipmentShape shape,
            PhysicalFluidTopology.Position origin,
            List<Long> nodes) {
        Binding {
            nodes = List.copyOf(nodes);
        }

        long node(int index) {
            return index < nodes.size() ? nodes.get(index) : 0;
        }
    }

    static List<ControlInput> channels(Binding binding) {
        var out = new ArrayList<ControlInput>();
        var shape = binding.shape;
        var kind = shape.kind();
        long host = binding.host;
        if (kind == EquipmentShape.Kind.COLUMN) {
            for (String end : List.of("top", "bottom"))
                stream(out, host, host, end, "COLUMN:" + end, false);
            // The live pressure drop over the trays (batch 2026-11-10-column-failure-modes, W3.3).
            out.add(ControlInput.builtIn(host, host, "differential_pressure", Sensor.Kind.PRESSURE, com.wormzjl.icheme.runtime.fluid.ColumnOperation.DIFFERENTIAL));
            var counts = new EnumMap<EquipmentShape.Role, Integer>(EquipmentShape.Role.class);
            for (var nozzle : shape.nozzles()) counts.merge(nozzle.role(), 1, Integer::sum);
            var seen = new EnumMap<EquipmentShape.Role, Integer>(EquipmentShape.Role.class);
            for (var nozzle : shape.nozzles()) {
                int index = seen.merge(nozzle.role(), 1, Integer::sum) - 1;
                String name =
                        nozzle.role().name().toLowerCase(Locale.ROOT)
                                + (counts.get(nozzle.role()) > 1
                                                || Set.of(
                                                                EquipmentShape.Role.DRAW,
                                                                EquipmentShape.Role.STEAM,
                                                                EquipmentShape.Role.PUMPAROUND_DRAW,
                                                                EquipmentShape.Role.RETURN)
                                                        .contains(nozzle.role())
                                        ? "_" + (index + 1)
                                        : "");
                stream(
                        out,
                        host,
                        host,
                        name,
                        nozzle.role() == EquipmentShape.Role.REFLUX
                                ? "REFLUX"
                                : "COLUMN_PORT:" + nozzle.role().name() + ":" + index,
                        true);
            }
            for (var role : com.wormzjl.icheme.science.column.operation.Port.values()) {
                int count =
                        role == com.wormzjl.icheme.science.column.operation.Port.STEAM
                                ? counts.getOrDefault(EquipmentShape.Role.STEAM, 0)
                                : role == com.wormzjl.icheme.science.column.operation.Port.DRAW
                                        ? counts.getOrDefault(EquipmentShape.Role.DRAW, 0)
                                        : 1;
                for (int i = 0; i < count; i++)
                    stream(
                            out,
                            host,
                            host,
                            "boundary_"
                                    + role.name().toLowerCase(Locale.ROOT)
                                    + (count > 1 ? "_" + (i + 1) : ""),
                            "COLUMN_PORT:" + role.name() + ":" + i,
                            true);
            }
        } else if (HeatSides.unit(kind) != null) {
            var sides = HeatSides.of(shape);
            for (int i = 0; i < sides.size(); i++)
                for (var role : List.of(sides.get(i).inlet(), sides.get(i).outlet()))
                    stream(
                            out,
                            host,
                            binding.node(i),
                            role.name().toLowerCase(Locale.ROOT),
                            "HEAT:" + role.name(),
                            kind == EquipmentShape.Kind.EXCHANGER
                                    || kind == EquipmentShape.Kind.KETTLE);
            if (kind == EquipmentShape.Kind.KETTLE)
                for (var role :
                        List.of(
                                EquipmentShape.Role.FEED,
                                EquipmentShape.Role.VAPOUR_OUTLET,
                                EquipmentShape.Role.LIQUID_OUTLET))
                    stream(
                            out,
                            host,
                            binding.node(sides.size()),
                            role.name().toLowerCase(Locale.ROOT),
                            "HEAT:" + role.name(),
                            true);
        } else if (kind.coolingTower()) {
            stream(out, host, binding.node(0), "hot_water_inlet", "BASIN:FEED", false);
            stream(out, host, binding.node(0), "cold_water_outlet", "BASIN:LIQUID_OUTLET", false);
            out.add(
                    ControlInput.builtIn(
                            host, binding.node(0), "basin_level", Sensor.Kind.LEVEL, "LEVEL"));
        } else if (kind.mover() || kind == EquipmentShape.Kind.STEAM_TURBINE) {
            stream(out, host, binding.node(0), "inlet", "MOVER:INLET", false);
            stream(out, host, binding.node(0), "outlet", "MOVER:OUTLET", false);
            out.add(
                    ControlInput.builtIn(
                            host, binding.node(0), "flow_rate", Sensor.Kind.FLOW, "MOVER:OUTLET"));
        } else if (kind == EquipmentShape.Kind.BATCH_STILL) {
            // A batch still (batch 2026-11-09-batch-distiller, W5): its pot's and head's temperatures, the pot's level, the
            // distillate's flow and whether it distils (1) or not (0), read from its pot's cached view.
            long pot = binding.node(0);
            out.add(ControlInput.builtIn(host, pot, "pot_temperature", Sensor.Kind.TEMPERATURE, "STILL:POT_TEMPERATURE"));
            out.add(ControlInput.builtIn(host, pot, "head_temperature", Sensor.Kind.TEMPERATURE, "STILL:HEAD_TEMPERATURE"));
            out.add(ControlInput.builtIn(host, pot, "pot_level", Sensor.Kind.LEVEL, "STILL:LEVEL"));
            out.add(ControlInput.builtIn(host, pot, "distillate_flow_rate", Sensor.Kind.FLOW, "STILL:DISTILLATE"));
            out.add(ControlInput.builtIn(host, pot, "distilling", Sensor.Kind.ALARM, "STILL:DISTILLING"));
        } else if (kind == EquipmentShape.Kind.FLARE_STACK) {
            // A flare (batch 2026-11-09-flare-stack): the mass flow it receives, and whether it is flaring (1) or not (0).
            out.add(ControlInput.builtIn(host, binding.node(0), "flow_rate", Sensor.Kind.FLOW, "FLARE:FLOW"));
            out.add(ControlInput.builtIn(host, binding.node(0), "flaring", Sensor.Kind.ALARM, "FLARE:FLARING"));
        } else if (kind.chimney()) {
            // A chimney (batch 2026-11-10-chimney, plan 3.9): its gas flow and solids flow, and whether it vents (1) or not (0).
            out.add(ControlInput.builtIn(host, binding.node(0), "flow_rate", Sensor.Kind.FLOW, "CHIMNEY:FLOW"));
            out.add(ControlInput.builtIn(host, binding.node(0), "solids_rate", Sensor.Kind.FLOW, "CHIMNEY:SOLIDS"));
            out.add(ControlInput.builtIn(host, binding.node(0), "venting", Sensor.Kind.ALARM, "CHIMNEY:VENTING"));
        } else if (kind.converter()) {
            // A fluid converter (batch 2026-11-12-fluid-conversion, W4): its two tanks' levels (the process vessel's from its
            // published state, the Forge tank's from its facts), its booked rate (kg/s, and L/s as its volume flow, positive to
            // IChemE) and its direction (0 none, 1 to IChemE, 2 to Forge).
            long vessel = binding.node(0);
            out.add(ControlInput.builtIn(host, vessel, "level_icheme", Sensor.Kind.LEVEL, "CONVERTER:LEVEL_ICHEME"));
            out.add(ControlInput.builtIn(host, vessel, "level_forge", Sensor.Kind.LEVEL, "CONVERTER:LEVEL_FORGE"));
            out.add(ControlInput.builtIn(host, vessel, "rate", Sensor.Kind.FLOW, "CONVERTER:RATE"));
            out.add(ControlInput.builtIn(host, vessel, "direction", Sensor.Kind.ALARM, "CONVERTER:DIRECTION"));
        }
        if (kind.mover()
                || kind == EquipmentShape.Kind.STEAM_TURBINE
                || kind == EquipmentShape.Kind.MOTOR
                || kind == EquipmentShape.Kind.GENERATOR)
            out.add(
                    ControlInput.builtIn(
                            host, binding.node(0), "shaft_speed", Sensor.Kind.SPEED, "SPEED"));
        alarms(out, binding);
        return List.copyOf(out);
    }

    /**
     * An equipment's alarm channels (batch 2026-11-09-furnace-fuel-power): a fired heater's burners' (no power, no fuel, low
     * fuel, fuel that does not burn), read from its burners' registration, its last node; every other powered unit's no
     * power, read from its store (an air cooler's or induced tower's first node, a motor's shaft unit, the host).
     */
    private static void alarms(List<ControlInput> out, Binding binding) {
        var kind = binding.shape().kind();
        if (HeatSides.burners(kind)) {
            long burners = binding.node(HeatSides.of(binding.shape()).size());
            for (var alarm : com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.HEATER)
                out.add(ControlInput.builtIn(binding.host(), burners, "alarm_" + alarm.name().toLowerCase(Locale.ROOT), Sensor.Kind.ALARM, "ALARM:" + alarm.name()));
        } else if (kind == EquipmentShape.Kind.AIR_COOLER || kind == EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER)
            out.add(ControlInput.builtIn(binding.host(), binding.node(0), "alarm_no_power", Sensor.Kind.ALARM, "ALARM:NO_POWER"));
        else if (kind == EquipmentShape.Kind.MOTOR)
            out.add(ControlInput.builtIn(binding.host(), 0, "alarm_no_power", Sensor.Kind.ALARM, "ALARM:NO_POWER"));
        else if (kind == EquipmentShape.Kind.BATCH_STILL)
            for (var alarm : com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.STILL)
                out.add(ControlInput.builtIn(binding.host(), binding.node(0), "alarm_" + alarm.name().toLowerCase(Locale.ROOT), Sensor.Kind.ALARM, "ALARM:" + alarm.name()));
        else if (kind == EquipmentShape.Kind.FLARE_STACK)
            for (var alarm : com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.FLARE)
                out.add(ControlInput.builtIn(binding.host(), binding.node(0), "alarm_" + alarm.name().toLowerCase(Locale.ROOT), Sensor.Kind.ALARM, "ALARM:" + alarm.name()));
        else if (kind.converter())
            for (var alarm : com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.CONVERTER)
                out.add(ControlInput.builtIn(binding.host(), binding.node(0), "alarm_" + alarm.name().toLowerCase(Locale.ROOT), Sensor.Kind.ALARM, "ALARM:" + alarm.name()));
        else if (kind.chimney())
            for (var alarm : com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.CHIMNEY)
                out.add(ControlInput.builtIn(binding.host(), binding.node(0), "alarm_" + alarm.name().toLowerCase(Locale.ROOT), Sensor.Kind.ALARM, "ALARM:" + alarm.name()));
        // A running column's (W6.1), answered from its run's last conditions at the binding's origin.
        else if (kind == EquipmentShape.Kind.COLUMN)
            for (var alarm : com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.COLUMN)
                out.add(ControlInput.builtIn(binding.host(), binding.host(), "alarm_" + alarm.name().toLowerCase(Locale.ROOT), Sensor.Kind.ALARM, "ALARM:" + alarm.name()));
    }

    /**
     * Whether alarm channel {@code input} is raised: a column's from its run's conditions at {@code binding}'s origin, its
     * burners' alarm, or its unit's store standing for want of power.
     */
    static boolean raised(ControlInput input, FluidWorldAuthority world, Binding binding) {
        var alarm = com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.valueOf(input.role().substring(6));
        if (binding != null && binding.shape().kind() == EquipmentShape.Kind.COLUMN) return world.columnAlarms(binding.origin()).contains(alarm);
        long unit = input.source() > 0 ? input.source() : input.host();
        var heater = world.burners().alarms(unit);
        if (world.registrations().containsKey(unit) && world.registrations().get(unit).device().burner()) return heater.contains(alarm);
        if (world.registrations().containsKey(unit) && world.registrations().get(unit).device().flare()) return world.flares().alarms(unit).contains(alarm);
        if (world.registrations().containsKey(unit) && world.registrations().get(unit).device().stillPot()) return world.stills().alarms(unit).contains(alarm);
        if (world.registrations().containsKey(unit) && world.registrations().get(unit).device().chimney()) return world.chimneys().alarms(unit).contains(alarm);
        if (world.converters().unit(unit) != null) return world.converterAlarms(unit).contains(alarm);
        if (alarm != com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.NO_POWER) return false;
        var view = world.electric().view(unit);
        return view != null && view.unpowered();
    }

    static List<ControlInput> inline(long host) {
        var out = new ArrayList<ControlInput>();
        stream(out, host, host, "inlet", "MOVER:INLET", false);
        stream(out, host, host, "outlet", "MOVER:OUTLET", false);
        out.add(ControlInput.builtIn(host, host, "flow_rate", Sensor.Kind.FLOW, "MOVER:OUTLET"));
        return out;
    }

    static List<ControlInput> member(Binding binding) {
        var out = new ArrayList<>(channels(binding));
        var known = new HashSet<String>();
        for (var input : out) {
            int suffix = input.channel().lastIndexOf('_');
            if (suffix > 0) known.add(input.channel().substring(0, suffix));
        }
        var seen = new EnumMap<EquipmentShape.Role, Integer>(EquipmentShape.Role.class);
        for (var p : binding.shape().nozzles()) {
            int index = seen.merge(p.role(), 1, Integer::sum) - 1;
            String name = p.role().name().toLowerCase(Locale.ROOT);
            if (out.stream().anyMatch(i -> i.channel().equals(name + "_pressure"))) continue;
            stream(
                    out,
                    binding.host(),
                    binding.node(0),
                    name + (index > 0 ? "_" + (index + 1) : ""),
                    "PORT:" + p.role() + ":" + index,
                    true);
        }
        if (binding.shape().kind() == EquipmentShape.Kind.AIR_COOLER)
            for (String end : List.of("inlet", "outlet"))
                stream(
                        out,
                        binding.host(),
                        binding.node(0),
                        "air_utility_" + end,
                        "AIR:" + end,
                        true);
        return List.copyOf(out);
    }

    static boolean volumeAvailable(ControlInput input) {
        return !input.role().startsWith("COLUMN")
                && !input.role().startsWith("ALARM:")
                && !input.role().startsWith("FLARE:")
                && !input.role().startsWith("CHIMNEY:")
                && !input.role().startsWith("CONVERTER:")
                && !input.role().startsWith("STILL:")
                && !input.role().equals("REFLUX")
                && !input.role().startsWith("AIR:")
                && !input.role().startsWith("PORT:");
    }

    private static void stream(
            List<ControlInput> out,
            long host,
            long source,
            String name,
            String role,
            boolean flow) {
        out.add(ControlInput.builtIn(host, source, name + "_pressure", Sensor.Kind.PRESSURE, role));
        out.add(
                ControlInput.builtIn(
                        host, source, name + "_temperature", Sensor.Kind.TEMPERATURE, role));
        if (flow)
            out.add(
                    ControlInput.builtIn(
                            host, source, name + "_flow_rate", Sensor.Kind.FLOW, role));
    }

    static Sensor.Reading read(
            ControlInput input,
            FluidWorldAuthority world,
            Binding binding,
            Map<Long, FluidView> views) {
        var view = input.source() > 0 ? views.computeIfAbsent(input.source(), world::view) : null;
        long tick = view == null ? world.onlineTick() : view.committedTick();
        double value = 0, secondary = 0, span = 0;
        var quality = Instrument.Availability.UNAVAILABLE;
        if (input.role().startsWith("ALARM:")) {
            value = raised(input, world, binding) ? 1 : 0;
            tick = world.onlineTick();
            quality = Instrument.Availability.AVAILABLE;
        } else if (input.role().startsWith("STILL:")) {
            var still = world.stills().view(input.source());
            if (still != null) {
                switch (input.role()) {
                    case "STILL:POT_TEMPERATURE" -> value = still.potTemperature();
                    case "STILL:HEAD_TEMPERATURE" -> value = still.headTemperature();
                    case "STILL:LEVEL" -> {
                        value = still.level();
                        secondary = 100 * still.filled();
                        span = still.filled() > 0 ? still.level() / still.filled() : 0;
                    }
                    case "STILL:DISTILLATE" -> value = still.distillateFlow();
                    default -> value = still.status() == com.wormzjl.icheme.science.fluid.network.BatchStill.Status.DISTILLING ? 1 : 0;
                }
                // The head has no temperature until the column first answers: the channel is unavailable until then.
                if (Double.isFinite(value)) {
                    tick = world.onlineTick();
                    quality = Instrument.Availability.AVAILABLE;
                } else value = 0;
            }
        } else if (input.role().startsWith("FLARE:")) {
            var flare = world.flares().view(input.source());
            if (flare != null) {
                value = input.role().equals("FLARE:FLOW") ? flare.massFlow()
                        : flare.status() == com.wormzjl.icheme.runtime.fluid.Flares.Status.FLARING || flare.status() == com.wormzjl.icheme.runtime.fluid.Flares.Status.OVER_CAPACITY ? 1 : 0;
                tick = world.onlineTick();
                quality = Instrument.Availability.AVAILABLE;
            }
        } else if (input.role().startsWith("CONVERTER:")) {
            var converter = world.converters().unit(input.source());
            if (converter != null) {
                double height = binding != null ? binding.shape().levelHeight() : 0;
                switch (input.role()) {
                    case "CONVERTER:LEVEL_ICHEME", "CONVERTER:LEVEL_FORGE" -> {
                        // A level in metres over the tank's height, its fill in % beside it (the tanks are one size).
                        double fill = input.role().endsWith("FORGE") ? converter.forgeFill() : world.converterFill(input.source());
                        if (!Double.isNaN(fill)) {
                            value = fill * height;
                            secondary = 100 * fill;
                            span = height;
                            quality = Instrument.Availability.AVAILABLE;
                        }
                    }
                    case "CONVERTER:RATE" -> {
                        var rate = world.converters().rate(input.source());
                        value = rate.mass();
                        secondary = rate.litres() / 1000;
                        quality = Instrument.Availability.AVAILABLE;
                    }
                    default -> {
                        value = world.converters().direction(input.source()).ordinal();
                        quality = Instrument.Availability.AVAILABLE;
                    }
                }
                tick = world.onlineTick();
            }
        } else if (input.role().startsWith("CHIMNEY:")) {
            var chimney = world.chimneys().view(input.source());
            if (chimney != null) {
                value = switch (input.role()) {
                    case "CHIMNEY:FLOW" -> chimney.massFlow();
                    case "CHIMNEY:SOLIDS" -> chimney.solids();
                    default -> chimney.massFlow() >= com.wormzjl.icheme.science.fluid.network.Chimney.NO_FLOW ? 1 : 0;
                };
                tick = world.onlineTick();
                quality = Instrument.Availability.AVAILABLE;
            }
        } else if (input.role().equals("SPEED")) {
            var unit = world.shafts().unit(input.host());
            var drive = unit == null ? world.shafts().unitOfDevice(input.source()) : unit;
            var state = drive == null ? null : world.shafts().view(drive.id());
            if (state != null) {
                value = state.speed();
                tick = world.onlineTick();
                quality = Instrument.Availability.AVAILABLE;
            }
        } else if (input.role().equals("LEVEL")) {
            if (view != null && view.tank() != null && tick > 0) {
                span = view.tank().levelHeight();
                value = view.tank().level(view.state());
                secondary = span > 0 ? 100 * value / span : 0;
                quality = Instrument.Availability.AVAILABLE;
            }
        } else if ((input.role().startsWith("COLUMN") || input.role().equals("REFLUX"))
                && binding != null) {
            var sample = world.columnInstrument(binding.origin(), input.role(), input.kind());
            if (sample != null)
                return new Sensor.Reading(
                        input.instance(),
                        input.kind(),
                        input.channel(),
                        sample.value(),
                        0,
                        0,
                        sample.tick(),
                        sample.availability());
        } else if (input.role().startsWith("PORT:") && binding != null) {
            var parts = input.role().split(":");
            var role = EquipmentShape.Role.valueOf(parts[1]);
            int ordinal = Integer.parseInt(parts[2]), seen = 0;
            for (var p : ShapeRasterizer.ports(binding.shape()))
                if (binding.shape().nozzles().get(p.nozzle()).role() == role) {
                    if (seen++ != ordinal) continue;
                    var o = binding.origin();
                    var c = p.cell();
                    var observed =
                            world.instrumentAt(
                                    new PhysicalFluidTopology.Position(
                                            o.dimension(),
                                            o.x() + c.x() + p.dx(),
                                            o.y() + c.y() + p.dy(),
                                            o.z() + c.z() + p.dz()));
                    if (observed != null) {
                        switch (input.kind()) {
                            case PRESSURE -> value = observed.pressure();
                            case TEMPERATURE -> value = observed.temperature();
                            case FLOW -> {
                                value = observed.massFlow();
                                secondary =
                                        observed.volumeFlow() == null ? 0 : observed.volumeFlow();
                            }
                            default -> {}
                        }
                        quality =
                                input.kind() == Sensor.Kind.TEMPERATURE && !observed.flowing()
                                        ? Instrument.Availability.NO_FLOW
                                        : Instrument.Availability.AVAILABLE;
                    }
                    break;
                }
        } else {
            FluidView.Port port = null;
            if (input.role().startsWith("HEAT:") && view != null && view.heat() != null)
                port = view.heat().port(input.role().substring(5));
            else if (input.role().startsWith("MOVER:"))
                port = world.instrumentMoverPort(input.source(), input.role().endsWith("OUTLET"));
            else if (input.role().startsWith("BASIN:")) {
                String role = input.role().substring(6);
                port =
                        world.instrumentVesselPorts(input.source()).stream()
                                .filter(p -> p.role().equals(role))
                                .findFirst()
                                .orElse(null);
            }
            if (port != null && tick > 0)
                switch (input.kind()) {
                    case PRESSURE -> {
                        if (port.pressure() != null) {
                            value = port.pressure();
                            quality = Instrument.Availability.AVAILABLE;
                        }
                    }
                    case TEMPERATURE -> {
                        if (port.temperature() != null) {
                            value = port.temperature();
                            quality = Instrument.Availability.AVAILABLE;
                        }
                    }
                    case FLOW -> {
                        value = port.massFlow();
                        secondary = Arrays.stream(port.phaseVolumes()).sum() * Math.signum(value);
                        quality = Instrument.Availability.AVAILABLE;
                    }
                    default -> {}
                }
        }
        return new Sensor.Reading(
                input.instance(),
                input.kind(),
                input.channel(),
                value,
                secondary,
                span,
                tick,
                quality);
    }
}
