package com.wormzjl.icheme.runtime.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.runtime.pollution.MonitorSignals.Alarm;
import com.wormzjl.icheme.runtime.pollution.MonitorSignals.Lamp;
import org.junit.jupiter.api.Test;

/**
 * The fixed pollution monitor's lamp and gas alarm (batch 2026-11-15-pollution-system, WP10): green below 0.1 on every
 * index, amber (GAS) from 0.1 on any one, red (GAS HIGH) from 1 on any one, dark while pollution is off. Particulate counts
 * on its own, as each kind does; nothing is added together.
 */
class MonitorSignalsTest {
    private static double[] of(double toxic, double combustible, double corrosive, double particulate) {
        return new double[] {toxic, combustible, corrosive, particulate};
    }

    @Test
    void theLampFollowsTheWorstIndex() {
        assertEquals(Lamp.GREEN, MonitorSignals.lamp(true, of(0, 0, 0, 0)));
        assertEquals(Lamp.GREEN, MonitorSignals.lamp(true, of(.099, .099, .099, .099)), "four indices under 0.1 are not added");
        assertEquals(Lamp.AMBER, MonitorSignals.lamp(true, of(.1, 0, 0, 0)), "0.1 is the first level");
        assertEquals(Lamp.AMBER, MonitorSignals.lamp(true, of(0, 0, 0, .99)));
        assertEquals(Lamp.RED, MonitorSignals.lamp(true, of(0, 1, 0, 0)), "1 is the reference");
        assertEquals(Lamp.RED, MonitorSignals.lamp(true, of(1.2, .03, 0, .42)), "the brief's reading");
        assertEquals(Lamp.RED, MonitorSignals.lamp(true, of(0, 0, 0, 7)), "particulate alone");
        assertEquals(Lamp.DARK, MonitorSignals.lamp(false, of(5, 5, 5, 5)), "pollution off: dark, whatever it read");
        assertEquals(Lamp.GREEN, MonitorSignals.lamp(true, of(Double.NaN, -1, 0, 0)), "NaN and negative read as clear");
    }

    @Test
    void theAlarmIsGasFromATenthAndGasHighFromOne() {
        assertEquals(Alarm.CLEAR, MonitorSignals.alarm(of(0, .05, 0, .09)));
        assertEquals(Alarm.GAS, MonitorSignals.alarm(of(0, 0, .1, 0)));
        assertEquals(Alarm.GAS, MonitorSignals.alarm(of(.3, 0, 0, 0)), "MEDIUM is still GAS");
        assertEquals(Alarm.GAS_HIGH, MonitorSignals.alarm(of(0, 0, 0, 1)));
        assertEquals(Alarm.GAS_HIGH, MonitorSignals.alarm(of(3.5, 0, 0, 0)), "EXTREME is GAS HIGH");
        assertEquals(1, Alarm.GAS.ordinal(), "the channel reads the ordinal: 1 GAS");
        assertEquals(2, Alarm.GAS_HIGH.ordinal(), "2 GAS HIGH");
    }

    @Test
    void theLampsNamesAreTheBlockstatesValues() {
        assertEquals("dark", Lamp.DARK.getSerializedName());
        assertEquals("green", Lamp.GREEN.getSerializedName());
        assertEquals("amber", Lamp.AMBER.getSerializedName());
        assertEquals("red", Lamp.RED.getSerializedName());
    }

    @Test
    void noServiceReadsZeroAndDark() {
        var read = MonitorSignals.indices(null, null, null);
        assertArrayEquals(new double[4], read);
        assertFalse(MonitorSignals.enabled(null));
        assertEquals(Lamp.DARK, MonitorSignals.lamp(MonitorSignals.enabled(null), read));
    }
}
