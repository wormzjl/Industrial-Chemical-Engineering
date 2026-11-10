package com.wormzjl.icheme.science.equipment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class MachineRpmDesignTest {
    @Test
    void explicitRpmSurvivesEveryOtherConstructorEdit() {
        var pump = MachineDuty.pump(100, 100, 4).withBaseSpeed(1800);
        var generator = MachineDuty.generator(250, 4).withBaseSpeed(1800);
        for (var kind : List.of(EquipmentShape.Kind.PUMP, EquipmentShape.Kind.GENERATOR)) {
            var design = UnitDesign.of(kind);
            var duty = kind == EquipmentShape.Kind.PUMP ? pump : generator;
            assertEquals(1800, UnitDesign.designSpeed(kind, duty));
            for (var field :
                    kind == EquipmentShape.Kind.PUMP ? List.of("flow", "head") : List.of("power")) {
                var edited = design.step(duty, field, 1);
                assertEquals(1800, edited.baseSpeed());
            }
            if (kind == EquipmentShape.Kind.PUMP) {
                assertEquals(300, design.step(duty, "base_speed", -100).baseSpeed());
                assertEquals(3000, design.step(duty, "base_speed", 100).baseSpeed());
            } else {
                // A generator's speed steps through its synchronous pole counts (2, 4, 6, 8, 12 at 50 Hz); the step
                // clears the explicit speed.
                var slow = design.step(duty, "base_speed", -100);
                var fast = design.step(duty, "base_speed", 100);
                assertEquals(0, slow.baseSpeed());
                assertEquals(12, slow.poles());
                assertEquals(500, UnitDesign.designSpeed(kind, slow));
                assertEquals(0, fast.baseSpeed());
                assertEquals(2, fast.poles());
                assertEquals(3000, UnitDesign.designSpeed(kind, fast));
            }
            var shape =
                    EquipmentShape.unit(
                                    kind,
                                    kind == EquipmentShape.Kind.PUMP ? .1 : 250,
                                    1,
                                    EquipmentShape.Heading.NORTH,
                                    0)
                            .withDuty(duty);
            assertEquals(1800, UnitDesign.designSpeed(shape));
            assertEquals(1800, UnitDesign.ratedRpm(shape));
        }
    }
}
