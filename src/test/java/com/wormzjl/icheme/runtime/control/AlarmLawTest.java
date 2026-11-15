package com.wormzjl.icheme.runtime.control;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.runtime.control.AlarmLaw.*;
import com.wormzjl.icheme.runtime.instrument.Sensor;

import org.junit.jupiter.api.Test;

import java.util.*;

/** The alarm beacon's decision (batch 2026-11-15-fluid-interfaces, W9, plan sections 4 and 6). */
class AlarmLawTest {
    private static final UUID SENSOR = new UUID(0, 7);

    private static AlarmSettings input(AlarmSettings.Comparison comparison, double threshold) {
        return new AlarmSettings("", true, AlarmSettings.Source.INPUT, SENSOR, Sensor.Unit.HEIGHT_PERCENT, comparison, threshold);
    }

    /** The statuses of {@code values} in order, each decision's state carried to the next. */
    private static List<Status> run(AlarmSettings settings, boolean alarmChannel, double... values) {
        var out = new ArrayList<Status>();
        var state = State.NONE;
        for (double v : values) {
            var d = AlarmLaw.decide(settings, new Sample(v, alarmChannel), state);
            out.add(d.status());
            state = d.state();
        }
        return out;
    }

    @Test
    void aboveRisesAtTheThresholdAndClearsBelowItsBand() {
        // Band 2 % of 80 = 1.6: active from 80, clear below 78.4.
        assertEquals(
                List.of(Status.CLEAR, Status.ACTIVE, Status.ACTIVE, Status.CLEAR),
                run(input(AlarmSettings.Comparison.ABOVE, 80), false, 79.9, 80, 79, 78.3));
    }

    @Test
    void belowIsTheMirror() {
        // BELOW 20: active at 20 and below, clear above 20.4.
        assertEquals(
                List.of(Status.CLEAR, Status.ACTIVE, Status.ACTIVE, Status.CLEAR),
                run(input(AlarmSettings.Comparison.BELOW, 20), false, 20.1, 20, 20.3, 20.5));
    }

    @Test
    void aBandOfANegativeThresholdIsItsMagnitudes() {
        // ABOVE −50 (b = 1): active at −50, still active at −50.9, clear at −51.1.
        assertEquals(
                List.of(Status.ACTIVE, Status.ACTIVE, Status.CLEAR),
                run(input(AlarmSettings.Comparison.ABOVE, -50), false, -50, -50.9, -51.1));
    }

    @Test
    void anAlarmChannelIsActiveWhileRaisedWithNoThreshold() {
        // The threshold and the comparison play no part (decision A2).
        assertEquals(
                List.of(Status.CLEAR, Status.ACTIVE, Status.CLEAR),
                run(input(AlarmSettings.Comparison.BELOW, 1e9), true, 0, 1, 0));
    }

    @Test
    void anyAlarmReadsEveryHostOnTheBus() {
        var settings = new AlarmSettings("", true, AlarmSettings.Source.ANY_ALARM, null, Sensor.Unit.METRE, AlarmSettings.Comparison.ABOVE, 0);
        // Two hosts' alarm channels: both clear, then the second raised.
        var clear = AlarmLaw.anyAlarm(true, List.of(0.0, 0.0));
        var raised = AlarmLaw.anyAlarm(true, List.of(0.0, 1.0));
        assertEquals(Status.CLEAR, AlarmLaw.decide(settings, clear, State.NONE).status());
        assertEquals(Status.ACTIVE, AlarmLaw.decide(settings, raised, State.NONE).status());
        // A beacon on no bus has no signal.
        assertNull(AlarmLaw.anyAlarm(false, List.of(1.0)));
        assertEquals(Status.NO_SIGNAL, AlarmLaw.decide(settings, null, State.NONE).status());
    }

    @Test
    void noSignalIsNotActiveAndForgetsTheState() {
        var settings = input(AlarmSettings.Comparison.ABOVE, 80);
        var active = AlarmLaw.decide(settings, new Sample(85, false), State.NONE);
        assertEquals(Status.ACTIVE, active.status());
        var lost = AlarmLaw.decide(settings, null, active.state());
        assertEquals(Status.NO_SIGNAL, lost.status());
        assertFalse(lost.status().lit());
        assertEquals(State.NONE, lost.state());
        // The band is forgotten too: 79 after the signal returns is a new decision, clear.
        assertEquals(Status.CLEAR, AlarmLaw.decide(settings, new Sample(79, false), lost.state()).status());
        assertEquals(Status.NO_SIGNAL, AlarmLaw.decide(settings, new Sample(Double.NaN, false), active.state()).status());
    }

    @Test
    void anAcknowledgementHoldsUntilClearAndTheNextRiseSoundsAgain() {
        var settings = input(AlarmSettings.Comparison.ABOVE, 80);
        var active = AlarmLaw.decide(settings, new Sample(85, false), State.NONE);
        var acknowledged = AlarmLaw.decide(settings, new Sample(86, false), AlarmLaw.acknowledge(active.state()));
        assertEquals(Status.ACKNOWLEDGED, acknowledged.status());
        assertTrue(acknowledged.status().lit());
        // Still active within the band: still acknowledged.
        var held = AlarmLaw.decide(settings, new Sample(79, false), acknowledged.state());
        assertEquals(Status.ACKNOWLEDGED, held.status());
        var cleared = AlarmLaw.decide(settings, new Sample(78, false), held.state());
        assertEquals(Status.CLEAR, cleared.status());
        assertEquals(Status.ACTIVE, AlarmLaw.decide(settings, new Sample(81, false), cleared.state()).status());
        // A hand on a clear beacon acknowledges nothing.
        assertEquals(State.NONE, AlarmLaw.acknowledge(State.NONE));
    }

    @Test
    void aBeaconNotEnabledIsOff() {
        var off = new AlarmSettings("", false, AlarmSettings.Source.INPUT, null, Sensor.Unit.METRE, AlarmSettings.Comparison.ABOVE, 0);
        var d = AlarmLaw.decide(off, new Sample(1, true), new State(true, true));
        assertEquals(Status.OFF, d.status());
        assertEquals(State.NONE, d.state());
    }
}
