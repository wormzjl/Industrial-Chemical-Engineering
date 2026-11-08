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
            assertEquals(300, design.step(duty, "base_speed", -100).baseSpeed());
            assertEquals(3000, design.step(duty, "base_speed", 100).baseSpeed());
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
