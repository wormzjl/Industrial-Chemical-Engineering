package com.wormzjl.icheme.runtime.control;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A control valve regulates only on a sensor physically linked to it and selected on its screen (owner, 2026-11-17;
 * VALVE_SENSOR_REVIEW.md): the rule ({@link ValveSignal#linked}), the law a slice takes ({@link ValveSignal#slice}), the law
 * a selection writes ({@link ValveSignal#select}) and the law a removed sensor frees ({@link ValveSignal#released}).
 */
class ValveSignalTest {
    private static final UUID LINKED = new UUID(5, 1), ELSEWHERE = new UUID(5, 2);

    @Test
    void aLawOnALinkedSensorRegulatesAsSaved() {
        for (var source : new ValveSource[]{ValveSource.PRESSURE, ValveSource.MASS_FLOW, ValveSource.VOLUME_FLOW}) {
            var law = new FlowControl.ControlValve(source, source == ValveSource.PRESSURE ? 150_000 : 2, LINKED, 4);
            assertTrue(ValveSignal.linked(law, Set.of(LINKED, ELSEWHERE)), source.name());
            assertSame(law, ValveSignal.slice(law, null, true), "the slice keeps the law: " + source);
            assertFalse(ValveSignal.slice(law, null, true).bare());
        }
    }

    @Test
    void aLawOnASensorThatIsNotLinkedStandsOpenWithItsIdentity() {
        var law = new FlowControl.ControlValve(ValveSource.MASS_FLOW, 167.39, ELSEWHERE, 1);
        for (var linked : java.util.List.of(Set.<UUID>of(), Set.of(LINKED))) {
            assertFalse(ValveSignal.linked(law, linked), "linked " + linked);
            var taken = ValveSignal.slice(law, null, false);
            assertTrue(taken.bare(), "no signal: the valve stands open");
            assertNull(taken.sensor());
            assertEquals(law.source(), taken.source());
            assertEquals(law.revision(), taken.revision(), "its line keeps its identity from slice to slice");
            assertEquals(law.massTarget(), taken.massTarget());
            assertEquals(taken, ValveSignal.slice(law, null, false), "the same law every slice");
        }
    }

    @Test
    void aBareLawNeedsNothing() {
        var bare = FlowControl.ControlValve.unconfigured();
        assertTrue(ValveSignal.linked(bare, Set.of()));
        assertSame(bare, ValveSignal.slice(bare, null, true));
        var unselected = new FlowControl.ControlValve(ValveSource.PRESSURE, 150_000, null, 7);
        assertTrue(ValveSignal.linked(unselected, Set.of()));
        assertTrue(unselected.bare(), "no sensor selected: the valve stands open");
    }

    @Test
    void theValvesEquipmentWorksTakeNoSelection() {
        var worked = new FlowControl.ControlValve[]{FlowControl.ControlValve.burner(), FlowControl.ControlValve.flare(), FlowControl.ControlValve.still(),
                FlowControl.ControlValve.converter(), FlowControl.ControlValve.ejector(), FlowControl.ControlValve.engine(), FlowControl.ControlValve.blanket(),
                FlowControl.ControlValve.onOff(), new FlowControl.ControlValve(ValveSource.SHAFT_SPEED, 200_000, 1, .01, ELSEWHERE, 3)};
        var sources = EnumSet.noneOf(ValveSource.class);
        for (var law : worked) {
            sources.add(law.source());
            assertTrue(ValveSignal.exempt(law), law.source().name());
            assertTrue(ValveSignal.linked(law, Set.of()), law.source().name());
            assertSame(law, ValveSignal.slice(law, null, true), law.source().name());
            assertNull(ValveSignal.released(law, law.sensor()), "a removed sensor frees no equipment's valve: " + law.source());
        }
        // Every source but the four a sensor works is the equipment's own.
        assertEquals(EnumSet.complementOf(EnumSet.of(ValveSource.NONE, ValveSource.PRESSURE, ValveSource.MASS_FLOW, ValveSource.VOLUME_FLOW)), sources);
    }

    @Test
    void aRunningColumnsValveAndALoopsPlanPassUntouched() {
        var column = new FlowControl.ControlValve(ValveSource.MASS_FLOW, 3, PortPlan.COLUMN_SENSOR, 2);
        assertTrue(ValveSignal.exempt(column));
        assertTrue(ValveSignal.linked(column, Set.of()));
        // A wired loop's plan, made over its data cables, is the slice's law whatever the valve's own law names.
        var base = new FlowControl.ControlValve(ValveSource.MASS_FLOW, 3, ELSEWHERE, 2);
        var plan = new FlowControl.ControlValve(ValveSource.MASS_FLOW, 4.5, ELSEWHERE, 2);
        assertSame(plan, ValveSignal.slice(base, plan, true));
        assertSame(plan, ValveSignal.slice(base, plan, false));
    }

    @Test
    void aSelectionWritesTheSensorAndTheSourceItsKindTakes() {
        var none = FlowControl.ControlValve.unconfigured();
        var probe = ValveSignal.select(none, LINKED, Sensor.Kind.PRESSURE, ValveSource.MASS_FLOW, 180_000, 2, .01);
        assertEquals(ValveSource.PRESSURE, probe.source(), "a probe holds the upstream pressure, whatever the flow key says");
        assertEquals(180_000, probe.targetPressure());
        assertEquals(LINKED, probe.sensor());
        assertEquals(1, probe.revision());
        var mass = ValveSignal.select(probe, LINKED, Sensor.Kind.FLOW, ValveSource.MASS_FLOW, 180_000, 2.5, .01);
        assertEquals(ValveSource.MASS_FLOW, mass.source());
        assertEquals(2.5, mass.massTarget());
        var volume = ValveSignal.select(mass, LINKED, Sensor.Kind.FLOW, ValveSource.VOLUME_FLOW, 180_000, 2.5, .003);
        assertEquals(ValveSource.VOLUME_FLOW, volume.source());
        assertEquals(.003, volume.volumeTarget());
        assertEquals(3, volume.revision(), "each selection moves the revision on");
        assertThrows(IllegalArgumentException.class, () -> ValveSignal.select(mass, LINKED, Sensor.Kind.FLOW, ValveSource.PRESSURE, 180_000, 2.5, .01), "a flowmeter needs a flow");
        assertThrows(IllegalArgumentException.class, () -> ValveSignal.select(mass, LINKED, Sensor.Kind.TEMPERATURE, ValveSource.MASS_FLOW, 180_000, 2.5, .01), "not a kind a valve regulates on");
        var cleared = ValveSignal.select(mass, null, null, ValveSource.MASS_FLOW, 1, 1, 1);
        assertTrue(cleared.bare(), "None: the valve stands open");
        assertEquals(mass.massTarget(), cleared.massTarget(), "its targets kept for the next selection");
        assertEquals(mass.source(), cleared.source());
    }

    @Test
    void aRemovedSensorFreesOnlyTheLawThatSelectedIt() {
        var law = new FlowControl.ControlValve(ValveSource.MASS_FLOW, 2, LINKED, 6);
        var freed = ValveSignal.released(law, LINKED);
        assertNotNull(freed);
        assertNull(freed.sensor());
        assertEquals(7, freed.revision());
        assertEquals(law.massTarget(), freed.massTarget());
        assertNull(ValveSignal.released(law, ELSEWHERE), "another sensor's removal leaves the law");
        assertNull(ValveSignal.released(new FlowControl.ControlValve(ValveSource.MASS_FLOW, 3, PortPlan.COLUMN_SENSOR, 2), PortPlan.COLUMN_SENSOR));
    }
}
