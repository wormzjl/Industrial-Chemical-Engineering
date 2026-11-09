package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.machine.ShaftNetwork.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shaft trains from their parts (batch 2026-11-03-rotating-machinery W3, build plan section 1): shafts join along their
 * axis, a machine's end joins the shaft beyond its coupling face on the same axis, a gearbox joins two lines at its
 * ratio, and a train with two drivers, none, or a loop of gears stands with its fault.
 */
class ShaftNetworkTest {
    private static Cell at(int x, int y, int z) {
        return new Cell("minecraft:overworld", x, y, z);
    }

    private static End end(int x, int dx, boolean output) {
        return new End(at(x, 0, 0), dx, 0, 0, output);
    }

    private static Part machine(long id, Kind kind, double speed, End... ends) {
        return new Part(id, kind, null, -1, List.of(ends), 0, speed);
    }

    private static Part shaft(long id, int x, int axis) {
        return Part.shaft(id, at(x, 0, 0), axis);
    }

    /** A 2-pole motor at x 0 driving east over shafts at 1 and 2 into a pump casing at 3. */
    private static List<Part> line() {
        return new ArrayList<>(List.of(machine(1, Kind.MOTOR, 2950, end(0, 1, true)), shaft(2, 1, 0), shaft(3, 2, 0), machine(4, Kind.CASING, 2950, end(3, -1, false))));
    }

    @Test
    void aMotorAndAPumpOnOneShaftLineAreOneTrain() {
        var trains = ShaftNetwork.compile(line());
        assertEquals(1, trains.size());
        var t = trains.getFirst();
        assertEquals(Fault.NONE, t.fault());
        assertEquals(1, t.key(), "the key is the lowest part");
        assertEquals(1, t.driver());
        assertEquals(List.of(new Driven(4, 1, 1)), t.driven());
        assertEquals(Map.of(2L, 1.0, 3L, 1.0), t.shafts());
    }

    @Test
    void aBrokenShaftCutsTheTrainAndThePumpHasNoDriver() {
        var parts = line();
        parts.removeIf(p -> p.id() == 2);
        var trains = ShaftNetwork.compile(parts);
        assertEquals(2, trains.size());
        assertEquals(Fault.NONE, trains.get(0).fault(), "the motor alone");
        assertTrue(trains.get(0).driven().isEmpty());
        assertEquals(Fault.NO_DRIVER, trains.get(1).fault(), "the shaft and the pump");
        assertEquals(Set.of(3L, 4L), trains.get(1).parts());
    }

    /** A 1 MW motor, a gearbox on auto and a centrifugal compressor at 12,597 rpm: one train of two lines, the ratio 4.27, 0.98. */
    @Test
    void aGearboxJoinsTwoLinesAtItsRatio() {
        var parts = List.of(machine(1, Kind.MOTOR, 2950, end(0, 1, true)), shaft(2, 1, 0),
                new Part(3, Kind.GEARBOX, null, -1, List.of(end(2, -1, false), end(2, 1, true)), 0, 0), shaft(4, 3, 0),
                machine(5, Kind.CASING, 12597, end(4, -1, false)));
        var trains = ShaftNetwork.compile(parts);
        assertEquals(1, trains.size());
        var t = trains.getFirst();
        assertEquals(Fault.NONE, t.fault());
        assertEquals(12597 / 2950.0, t.ratios().get(3L), 1e-12, "auto: the compressor at its design speed");
        assertEquals(4.27, t.ratios().get(3L), 1e-3);
        assertEquals(1, t.driven().size());
        assertEquals(12597 / 2950.0, t.driven().getFirst().ratio(), 1e-12);
        assertEquals(ShaftNetwork.GEAR_EFFICIENCY, t.driven().getFirst().efficiency(), 0);
        assertEquals(1.0, t.shafts().get(2L), 0, "the slow side at the motor's speed");
        assertEquals(12597 / 2950.0, t.shafts().get(4L), 1e-12, "the fast side at the compressor's");
        // A set ratio holds, within 1/20 to 20.
        var set = new ArrayList<>(parts);
        set.set(2, new Part(3, Kind.GEARBOX, null, -1, List.of(end(2, -1, false), end(2, 1, true)), 4.27, 0));
        assertEquals(4.27, ShaftNetwork.compile(set).getFirst().driven().getFirst().ratio(), 0);
    }

    @Test
    void twoDriversOnOneTrainStandIt() {
        var parts = line();
        parts.set(3, machine(4, Kind.CREATIVE_MOTOR, 2950, end(3, -1, true)));
        var trains = ShaftNetwork.compile(parts);
        assertEquals(1, trains.size());
        assertEquals(Fault.TWO_DRIVERS, trains.getFirst().fault());
        assertEquals(0, trains.getFirst().driver());
        assertTrue(trains.getFirst().driven().isEmpty());
    }

    /** Two gearboxes between the same two lines (along x at z 0 and at z 2) make a loop: the train stands. */
    @Test
    void aLoopOfGearsStandsTheTrain() {
        var parts = new ArrayList<Part>();
        for (int x = -2; x <= 2; x++) parts.add(Part.shaft(10 + x, at(x, 0, 0), 0));
        for (int x = -2; x <= 2; x++) parts.add(Part.shaft(20 + x, at(x, 0, 2), 0));
        // East: slow end into (2, 0, 0), fast end into (2, 0, 2).
        parts.add(new Part(30, Kind.GEARBOX, null, -1, List.of(new End(at(3, 0, 0), -1, 0, 0, false), new End(at(3, 0, 2), -1, 0, 0, true)), 2, 0));
        var one = ShaftNetwork.compile(parts);
        assertEquals(1, one.size(), "one gearbox joins the lines");
        assertEquals(Fault.NO_DRIVER, one.getFirst().fault());
        // West: a second gearbox between the same lines, slow end into (-2, 0, 0), fast end into (-2, 0, 2).
        parts.add(new Part(31, Kind.GEARBOX, null, -1, List.of(new End(at(-3, 0, 0), 1, 0, 0, false), new End(at(-3, 0, 2), 1, 0, 0, true)), 2, 0));
        var trains = ShaftNetwork.compile(parts);
        assertEquals(1, trains.size());
        assertEquals(Fault.GEAR_LOOP, trains.getFirst().fault(), "two gearboxes join the same two lines");
        assertEquals(Set.of(8L, 9L, 10L, 11L, 12L, 18L, 19L, 20L, 21L, 22L, 30L, 31L), trains.getFirst().parts());
    }

    @Test
    void aShaftOnAnotherAxisDoesNotJoin() {
        var parts = line();
        parts.set(2, shaft(3, 2, 2));
        var trains = ShaftNetwork.compile(parts);
        var pump = trains.stream().filter(t -> t.parts().contains(4L)).findFirst().orElseThrow();
        assertEquals(Fault.NO_DRIVER, pump.fault(), "the pump's end meets a shaft on z: no coupling");
        assertEquals(Set.of(4L), pump.parts());
    }
}
