package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.DeviceMeters.PhaseRatioBasis;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The door column's meters, moved out of the device screen (finding C1, batch 2026-11-13-codebase-review): for a valve, a
 * tank and a driven pump, built from fixed views, the rows, their order and their texts are those the screen's code gave
 * for the same view. Each expected value is derived from that code in the comment above it: {@code Format.number} prints
 * one decimal ("%.3g" below 0.05), a pressure unit rounds to 0.1 Pa and prints its own decimals (kPa 4, bar 6). The class
 * names no Minecraft class.
 */
class DeviceMetersTest {
    /** A lang line's key, its arguments after it in brackets: the rows say which line they name and with which values. */
    private static final HeatControlColumn.Texts T = (key, args) -> args.length == 0 ? key : key + Arrays.toString(args);
    private static final List<String> AXIS = List.of("Hexane", "Water");
    private static final List<Double> WEIGHTS = List.of(0.086, 0.018);

    private static FluidView.State state(double pressure, double temperature, double volume, double mass, double[] volumes, double[][] moles, double capacity) {
        return new FluidView.State(pressure, temperature, volume, mass, volumes, moles, SolidInventory.EMPTY, capacity);
    }

    private static DeviceColumn.Meter meter(String key, String value, String unit, String hint) {
        return new DeviceColumn.Meter(key, value, unit, hint);
    }

    @Test
    void aValveReadsItsInletAndThroughputInKelvinAndKilopascal() {
        var state = state(200000, 300, 0.002, 2.0, new double[]{0.002, 0, 0}, new double[][]{{20, 5}, {0, 0}, {0, 0}}, 0.002);
        var view = new FluidView(1, 1, 0, 0, "STEADY: flowing", state, 2.0, List.of(), -50000.0, 1, List.of(), null, null, List.of(), null);
        var m = new DeviceMeters(new DeviceMeters.Data(Kind.VALVE, view, AXIS, WEIGHTS, true, null, false, List.of()), null, PhaseRatioBasis.VOLUME,
                TemperatureUnit.KELVIN, PressureUnit.KILOPASCAL, FlowRateUnit.PER_SECOND, false, T);
        // A valve's node: its temperature, 300 K; its inlet pressure, 200 kPa; its throughput by moles, 2 kg/s of 2 kg that
        // holds 25 mol: 2 · 25 / 2 / 1000 = 0.025 kmol/s; its pressure change, -50 kPa. No tank, pipe, drive or instrument.
        assertEquals(List.of(meter("temperature", "300.0", "K", null), meter("inlet_pressure", "200.0000", "kPa", null),
                meter("throughput", "0.0250", "kmol/s", "throughput.hint"), meter("pressure_change", "-50.0000", "kPa", null)), m.meters());
        // The phases by volume, from the node's own contents; no aqueous liquid, so it is not named water.
        var phases = m.phases();
        assertEquals("volume", phases.basis());
        assertArrayEquals(new double[]{0.002, 0, 0, 0}, phases.amounts(), 1e-15);
        assertFalse(phases.customSolidsExcluded());
        assertEquals("phase_ratio.hint", phases.hint());
        assertFalse(phases.aqueousWater());
        assertNull(m.level());
        assertNull(m.suction());
        assertNull(m.drive());
        assertNull(m.power());
    }

    @Test
    void aTankReadsItsPressuresMassLevelAndMolarPhases() {
        var state = state(101325, 350, 1.0, 800, new double[]{0.6, 0.4, 0}, new double[][]{{100, 0}, {0, 50}, {0, 0}}, 4.0);
        var tank = new FluidView.Tank(2, false, 0, 0, 4.0, 101325.0, 120000.0);
        var view = new FluidView(2, 1, 0, 0, "FULL", state, 0, List.of(), null, 1, List.of(), null, null, List.of(), tank);
        var m = new DeviceMeters(new DeviceMeters.Data(Kind.RESERVOIR, view, AXIS, WEIGHTS, true, null, false, List.of()), null, PhaseRatioBasis.MOLAR,
                TemperatureUnit.KELVIN, PressureUnit.KILOPASCAL, FlowRateUnit.PER_HOUR, true, T);
        // A tank: its temperature, its top and bottom pressures (101.325 and 120 kPa), its mass, then its net flow by mass,
        // per hour: no outlets, 0.
        assertEquals(List.of(meter("temperature", "350.0", "K", null), meter("top_pressure", "101.3250", "kPa", "top_pressure.hint"),
                meter("bottom_pressure", "120.0000", "kPa", "bottom_pressure.hint"), meter("mass", "800.0", "kg", null),
                meter("net_flow", "0.0", "kg/h", "net_flow.hint")), m.meters());
        // Its Level: 1 m³ of 4 m³, 0.25; 1000 L of 4000 L.
        assertEquals(new DeviceColumn.Level(0.25, "1000.0", "4000.0"), m.level());
        // The phases by moles: 100 mol of oil, 50 mol of water; the molar hint after the tank's; the aqueous liquid is water.
        var phases = m.phases();
        assertEquals("mole", phases.basis());
        assertArrayEquals(new double[]{100, 50, 0, 0}, phases.amounts(), 1e-12);
        assertEquals("phase_ratio.tank_hint\nphase_ratio.mole_hint", phases.hint());
        assertTrue(phases.aqueousWater());
        assertNull(m.suction());
        assertNull(m.drive());
    }

    @Test
    void aDrivenPumpReadsItsHeadEfficiencyInstrumentsSuctionAndDrive() {
        var state = state(150000, 323.15, 0.5, 500, new double[]{0, 0.5, 0}, new double[][]{{0, 0}, {0, 25000}, {0, 0}}, 0.5);
        var drive = new FluidView.Drive("MOTOR", 15000, 4, "RUNNING", 1480, false, 0.8, 10000);
        var pump = new FluidView.Pump(8.0, 3.0, null, 0.95, null, null);
        var view = new FluidView(3, 1, 0, 0, "STEADY: flowing", state, 5.0, List.of(), 300000.0, 1, List.of(), null, null, List.of(), null, null, null,
                drive, null, pump, null, null, null, null);
        var sensors = List.of(new Sensor.Reading(new UUID(0, 1), Sensor.Kind.PRESSURE, "pipe_sensor", 250000, 0, 0, 0, Instrument.Availability.AVAILABLE),
                new Sensor.Reading(new UUID(0, 2), Sensor.Kind.TEMPERATURE, "pipe_sensor", 320, 0, 0, 0, Instrument.Availability.UNAVAILABLE));
        var m = new DeviceMeters(new DeviceMeters.Data(Kind.PUMP, view, AXIS, WEIGHTS, true, null, false, sensors), null, PhaseRatioBasis.MASS,
                TemperatureUnit.CELSIUS, PressureUnit.BAR, FlowRateUnit.PER_MINUTE, false, T);
        // 323.15 K is 50.0 °C; the suction 1.5 bar; the throughput 5 kg/s of 500 kg holding 25 000 mol: 0.25 kmol/s, 15 kmol/min;
        // the pressure rise 3 bar. The drive's readings: the head 300 000 Pa / (1000 kg/m³ · 9.80665) = 30.59 m; the speed share
        // 80 %; the shaft power 10 kW; the efficiency, the fluid's 5 / 1000 · 300 000 = 1500 W over the shaft's 10 000 W, 15 %.
        // Then the instruments: a pressure probe at 2.5 bar, and a temperature probe that cannot read.
        assertEquals(List.of(meter("temperature", "50.0", "°C", null), meter("suction_pressure", "1.500000", "bar", null),
                meter("throughput", "15.0", "kmol/min", "throughput.hint"), meter("pressure_change", "3.000000", "bar", null),
                meter("head", "30.6", "m", "head.hint"), meter("speed_share", "80.0", "%", null), meter("shaft_power", "10.0", "kW", null),
                meter("efficiency", "15.0", "%", "efficiency.hint"), meter("pressure_probe", "2.500000", "bar", null),
                meter("temperature_probe", "—", "", "reading.unavailable")), m.meters());
        // NPSH available 8 m and required 3 m, no gas read, 95 % health, no wear.
        assertEquals(new DeviceColumn.Suction("8.0", "3.0", "—", "95.0", "—"), m.suction());
        // The driver in one line with its rating, 15 kW, and its state with its speed.
        assertEquals(new DeviceColumn.Drive("drive_line[driver.motor, 15.0]", "drive_state[RUNNING, 1480]", true, false), m.drive());
        assertNull(m.power());
        assertNull(m.level());
        // The phases by mass: 25 000 mol of water at 0.018 kg/mol, 450 kg.
        var phases = m.phases();
        assertEquals("mass", phases.basis());
        assertArrayEquals(new double[]{0, 450, 0, 0}, phases.amounts(), 1e-9);
        assertEquals("phase_ratio.hint", phases.hint());
        assertTrue(phases.aqueousWater());
    }

    /** The meters are a builder's: no Minecraft class, and nothing of the screens. */
    @Test
    void aWallAndATrimReadAsTheirGroupsShowThem() {
        // Batch 2026-11-16-fouling-corrosion, WP4: a vessel at 72 % of its 3 mm allowance thinning at 0.4 mm/y by
        // sulfidation, 90 s of play left; a valve trim at 0.3 with 600 s of play left.
        var view = new FluidView(1, 1, 0, 0, "THIN WALL", null, 0, List.of())
                .withIntegrity(new FluidView.Integrity(.72, .4, "SULFIDATION", 90.0, "CHROME_STEEL"), new FluidView.Trim(.3, 600.0));
        var m = new DeviceMeters(new DeviceMeters.Data(Kind.RESERVOIR, view, AXIS, WEIGHTS, true, null, false, List.of()), null, PhaseRatioBasis.VOLUME,
                TemperatureUnit.KELVIN, PressureUnit.KILOPASCAL, FlowRateUnit.PER_SECOND, false, T);
        // Left: 100 (1 − 0.72) = 28.0 %; rate 0.4 mm/y; life 90 s / 60 = 1.5 min; the names from their lines under gui.icheme.
        assertEquals(new DeviceColumn.Wall("28.0", "0.4", "1.5", "mechanism.sulfidation", "metallurgy.chrome_steel"), m.wall(T));
        assertEquals(new DeviceColumn.Trim("30.0", "10.0"), m.trim());
        // While it does not thin: — for the life; a device with neither: no groups.
        var still = new FluidView(1, 1, 0, 0, "", null, 0, List.of()).withIntegrity(new FluidView.Integrity(0, 0, "", null, "CARBON_STEEL"), null);
        var s = new DeviceMeters(new DeviceMeters.Data(Kind.RESERVOIR, still, AXIS, WEIGHTS, true, null, false, List.of()), null, PhaseRatioBasis.VOLUME,
                TemperatureUnit.KELVIN, PressureUnit.KILOPASCAL, FlowRateUnit.PER_SECOND, false, T);
        assertEquals(new DeviceColumn.Wall("100.0", "0.0", "—", "—", "metallurgy.carbon_steel"), s.wall(T));
        assertNull(s.trim());
        var none = new DeviceMeters(new DeviceMeters.Data(Kind.RESERVOIR, new FluidView(1, 1, 0, 0, "", null, 0, List.of()), AXIS, WEIGHTS, true, null, false, List.of()),
                null, PhaseRatioBasis.VOLUME, TemperatureUnit.KELVIN, PressureUnit.KILOPASCAL, FlowRateUnit.PER_SECOND, false, T);
        assertNull(none.wall(T));
        assertNull(none.trim());
    }

    @Test
    void theWallAndTrimCrossInTheViewsJsonAndEveryNameHasItsLine() {
        var gson = new com.google.gson.Gson();
        var view = new FluidView(7, 2, 0, 0, "WEEPING", null, 0, List.of())
                .withIntegrity(new FluidView.Integrity(.93, 1.2, "SOUR_WATER", null, "STAINLESS_STEEL"), new FluidView.Trim(.5, 12.0));
        assertEquals(view, gson.fromJson(gson.toJson(view), FluidView.class));
        var plain = new FluidView(7, 2, 0, 0, "", null, 0, List.of());
        assertNull(gson.fromJson(gson.toJson(plain), FluidView.class).integrity());
        // Every mechanism and material has its name, and the groups their lines.
        var names = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var mechanism : com.wormzjl.icheme.science.integrity.Mechanism.values()) names.get(DeviceMeters.mechanismKey(mechanism.name()));
        for (var material : com.wormzjl.icheme.science.integrity.Metallurgy.values()) names.get("metallurgy." + material.name().toLowerCase(java.util.Locale.ROOT));
        assertEquals(java.util.Set.of(), names.missing);
        var lines = new HeatControlColumnTest.Recording();
        DeviceColumn.integrity(new DeviceColumn.Wall("28.0", "0.4", "1.5", "Sulfidation", "Chrome steel"), lines);
        DeviceColumn.trim(new DeviceColumn.Trim("30.0", "10.0"), lines);
        assertEquals(java.util.Set.of(), lines.missing);
    }

    @Test
    void theMetersNameNoMinecraftClass() throws Exception {
        var source = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/client/gui/common/DeviceMeters.java"));
        for (var word : List.of("net.minecraft", "com.mojang", "gui.screens", "FluidNetwork")) assertFalse(source.contains(word), word);
    }
}
