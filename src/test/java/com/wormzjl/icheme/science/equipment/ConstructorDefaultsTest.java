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
                                        design.draft(sizes.diameter(), sizes.length())),
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
        // The air cooler's starting bay stays 2.5 m by 5 m though its range grew to 6 m by 12 m (owner, 2026-11-15).
        var bay = ConstructorDefaults.sizes(VesselPlacement.Form.AIR_COOLER);
        assertEquals(2.5, bay.diameter());
        assertEquals(5, bay.length());
    }

    /**
     * A fresh steam turbine draft starts as a condensing HP turbine (owner point T2, option A): HP steam to 10 kPa, through
     * its HP, MP and LP sections; a turbine saved before design points keeps reading HP into the LP header.
     */
    @Test
    void aFreshTurbineDraftCondensesFromHpSteam() {
        var design = UnitDesign.of(EquipmentShape.Kind.STEAM_TURBINE);
        var sizes = ConstructorDefaults.sizes(VesselPlacement.Form.STEAM_TURBINE);
        var draft = design.draft(sizes.diameter(), sizes.length());
        assertEquals(MachineDuty.Steam.HP, draft.steam());
        assertEquals(10, draft.exhaust(), 0);
        assertEquals(sizes.diameter(), design.size(draft), 1e-9);
        assertEquals(450, design.duty(sizes.diameter(), sizes.length()).exhaust(), 0, "the saved point of old turbines stands");
    }
}
