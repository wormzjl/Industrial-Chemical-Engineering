package com.wormzjl.icheme.science.equipment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConstructorDefaultsTest {
    @Test
    void everyNewChoiceUsesLegalInteriorSizesAndKeepsItsDesignPoint() {
        for (var form : VesselPlacement.Form.values()) {
            var sizes = ConstructorDefaults.sizes(form);
            if (!form.designed()) {
                double max =
                        form.column()
                                ? EquipmentShape.MAX_COLUMN_DIAMETER
                                : EquipmentShape.MAX_DIAMETER;
                assertTrue(
                        sizes.diameter() > EquipmentShape.MIN_DIAMETER && sizes.diameter() < max,
                        form.name());
                assertTrue(
                        sizes.length() > 1 && sizes.length() < EquipmentShape.MAX_TANGENT_LENGTH,
                        form.name());
                continue;
            }
            var design = UnitDesign.of(form.kind());
            assertTrue(
                    sizes.diameter() > design.minSize() && sizes.diameter() < design.maxSize(),
                    form.name());
            assertEquals(sizes.diameter(), design.snap(sizes.diameter()), 1e-9, form.name());
            assertTrue(sizes.length() >= design.minLength(sizes.diameter()), form.name());
            assertTrue(sizes.length() <= design.maxLength(sizes.diameter()), form.name());
            assertEquals(
                    0,
                    (sizes.length() - design.minLength(sizes.diameter())) % design.lengthStep(),
                    form.name());
            if (!design.hasDuty()
                    && design.lengthInMetres()
                    && design.maxLength(sizes.diameter()) - design.minLength(sizes.diameter())
                            >= 2 * design.lengthStep()) {
                assertTrue(sizes.length() > design.minLength(sizes.diameter()), form.name());
                assertTrue(sizes.length() < design.maxLength(sizes.diameter()), form.name());
            }
            if (design.hasDuty())
                assertDoesNotThrow(
                        () ->
                                design.check(
                                        sizes.diameter(),
                                        sizes.length(),
                                        design.duty(sizes.diameter(), sizes.length())),
                        form.name());
        }
    }

    @Test
    void explicitProportionsAndBedCountsRemainUseful() {
        var tank = ConstructorDefaults.sizes(VesselPlacement.Form.FLOATING_ROOF_TANK);
        assertEquals(22, tank.diameter());
        assertEquals(13, tank.length());
        assertEquals(2, ConstructorDefaults.sizes(VesselPlacement.Form.ADSORBER).length());
        var exchanger = ConstructorDefaults.sizes(VesselPlacement.Form.EXCHANGER);
        assertEquals(1.5, exchanger.diameter());
        assertEquals(9, exchanger.length());
    }
}
