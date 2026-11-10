package com.wormzjl.icheme.runtime.control;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class BuiltInInputsTest {
    private static BuiltInInputs.Binding binding(EquipmentShape shape, long... nodes) {
        return new BuiltInInputs.Binding(
                77,
                shape,
                new PhysicalFluidTopology.Position("minecraft:overworld", 2, 3, 4),
                Arrays.stream(nodes).boxed().toList());
    }

    @Test
    void equipmentChannelsKeepIdsAcrossHeadingAndFluidSideRebinding() {
        var shape =
                EquipmentShape.unit(
                        EquipmentShape.Kind.EXCHANGER, 1, 6, EquipmentShape.Heading.NORTH, 2);
        var a = BuiltInInputs.channels(binding(shape, 10, 11));
        var b = BuiltInInputs.channels(binding(shape, 20, 21));
        assertEquals(12, a.size());
        assertEquals(
                a.stream().map(ControlInput::instance).toList(),
                b.stream().map(ControlInput::instance).toList());
        assertEquals(12, a.stream().map(ControlInput::instance).distinct().count());
        assertEquals(
                Set.of(10L, 11L),
                a.stream().map(ControlInput::source).collect(java.util.stream.Collectors.toSet()));
        assertTrue(a.stream().allMatch(i -> i.host() == 77));
        var p =
                a.stream()
                        .filter(i -> i.channel().equals("shell_outlet_temperature"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(11, p.source());
        assertEquals("HEAT:SHELL_OUTLET", p.role());
    }

    @Test
    void towersOwnLevelWhileRotatingUnitsOwnPressureTemperatureFlowAndSpeed() {
        for (var kind :
                List.of(
                        EquipmentShape.Kind.PUMP,
                        EquipmentShape.Kind.COMPRESSOR,
                        EquipmentShape.Kind.CENTRIFUGAL_COMPRESSOR)) {
            var design = UnitDesign.of(kind);
            double d = design.minSize();
            var shape =
                    EquipmentShape.unit(
                            kind,
                            d,
                            design.minLength(d),
                            EquipmentShape.Heading.NORTH,
                            design.minSupport(d, design.minLength(d)));
            var inputs = BuiltInInputs.channels(binding(shape, 10));
            assertEquals(6, inputs.size());
            assertEquals(
                    Set.of(
                            Sensor.Kind.PRESSURE,
                            Sensor.Kind.TEMPERATURE,
                            Sensor.Kind.FLOW,
                            Sensor.Kind.SPEED),
                    inputs.stream()
                            .map(ControlInput::kind)
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(EquipmentInstruments.of(shape).probes().isEmpty());
        }
        var tower =
                EquipmentShape.unit(
                        EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,
                        8,
                        2,
                        EquipmentShape.Heading.NORTH,
                        0);
        var channels = BuiltInInputs.channels(binding(tower, 10));
        assertEquals(1, channels.stream().filter(p -> p.kind() == Sensor.Kind.LEVEL).count());
    }

    @Test
    void columnChannelsHaveUniqueStableRolesForNativeAndExternalStreams() {
        var column =
                EquipmentShape.column(
                        4, ColumnTrays.simple(20, 10), EquipmentShape.Heading.NORTH, 4);
        var inputs = BuiltInInputs.channels(binding(column));
        assertEquals(inputs.size(), inputs.stream().map(ControlInput::instance).distinct().count());
        var names =
                inputs.stream()
                        .map(ControlInput::channel)
                        .collect(java.util.stream.Collectors.toSet());
        assertTrue(
                names.containsAll(
                        Set.of(
                                "top_pressure",
                                "top_temperature",
                                "bottom_pressure",
                                "bottom_temperature",
                                "reflux_pressure",
                                "reflux_temperature",
                                "reflux_flow_rate",
                                "boundary_feed_flow_rate",
                                "boundary_distillate_pressure",
                                "boundary_off_gas_temperature",
                                "boundary_sour_water_flow_rate",
                                "boundary_bottoms_flow_rate")));
        assertTrue(inputs.stream().filter(i -> i.role().equals("REFLUX")).count() == 3);
        // A running column's alarms (W6.1): one channel per column alarm, on the column's host.
        var alarms = inputs.stream().filter(i -> i.kind() == com.wormzjl.icheme.runtime.instrument.Sensor.Kind.ALARM).toList();
        assertEquals(com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.COLUMN.size(), alarms.size());
        assertTrue(names.containsAll(Set.of("alarm_blocked", "alarm_low_feed", "alarm_low_steam", "alarm_cooling_limited", "alarm_heating_limited",
                "alarm_off_table", "alarm_upset", "alarm_flooding", "alarm_no_power")), names.toString());
        assertTrue(alarms.stream().allMatch(i -> i.role().startsWith("ALARM:") && !BuiltInInputs.volumeAvailable(i)));
        // The conditions a column's cycle finds raise its alarms: a blocked product BLOCKED, a pending solve none.
        var raised = com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.column(List.of(
                new com.wormzjl.icheme.science.column.operation.Condition.Active(com.wormzjl.icheme.science.column.operation.Condition.PRODUCT_FULL, 0, 0, ""),
                new com.wormzjl.icheme.science.column.operation.Condition.Active(com.wormzjl.icheme.science.column.operation.Condition.OFF_TABLE, "composition")));
        assertEquals(Set.of(com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.BLOCKED), raised);
        for (var c : com.wormzjl.icheme.science.column.operation.Condition.values()) {
            var alarm = com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.of(c);
            assertTrue(alarm == null || com.wormzjl.icheme.runtime.fluid.EquipmentAlarm.COLUMN.contains(alarm), c + " raises a column alarm");
        }
        assertFalse(
                BuiltInInputs.volumeAvailable(
                        inputs.stream()
                                .filter(i -> i.channel().equals("reflux_flow_rate"))
                                .findFirst()
                                .orElseThrow()));
        var air =
                EquipmentShape.unit(
                        EquipmentShape.Kind.AIR_COOLER, 3, 6, EquipmentShape.Heading.NORTH, 4);
        assertEquals(
                6,
                BuiltInInputs.member(binding(air, 10)).stream()
                        .filter(i -> i.role().startsWith("AIR:"))
                        .count());
    }

    @Test
    void builtInsDoNotAllocateAccessoryFactsOrChangePhysicalSensorIdentity() {
        var physical =
                new Sensor.Installed(
                        new Instrument.Key(42, "pressure_top"),
                        new UUID(5, 8),
                        1,
                        Sensor.Kind.PRESSURE);
        var input = ControlInput.installed(physical);
        assertEquals(physical.instance(), input.instance());
        assertEquals(42, input.source());
        assertNotEquals(
                ControlInput.builtIn(42, 42, "inlet_pressure", Sensor.Kind.PRESSURE, "MOVER:INLET")
                        .instance(),
                ControlInput.builtIn(43, 42, "inlet_pressure", Sensor.Kind.PRESSURE, "MOVER:INLET")
                        .instance());
    }
}
