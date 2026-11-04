package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.equipment.HeatSides;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The electric store's arithmetic (batch 2026-11-03-rotating-machinery W5, build plan sections 1 and 7.1), with the plan's
 * numbers at 2.5 J per FE.
 */
class ElectricStoreTest {
    private static final double JPF = 2.5;

    /** W3's case 1: a pump demanding 24.9 kW on a 30 kW 2-pole motor draws 26.7 kW, 534 FE/t; its buffer and rate. */
    @Test
    void aThirtyKilowattMotorsDrawBufferAndRate() {
        double draw = Motor.electricPower(24.9e3, 30e3, 2, false);
        assertEquals(26.69e3, draw, .01e3);
        assertEquals(534, ElectricStore.fe(draw, JPF) / 20, 1, "FE per tick");
        double rated = ElectricStore.motorDraw(30e3, 2, false);
        assertEquals(32.15e3, rated, .01e3, "30 kW over the IE3 93.3 %");
        assertEquals(1.286e6, ElectricStore.capacity(rated), .001e6, "40 s of rated draw");
        assertEquals(514_000, ElectricStore.fe(ElectricStore.capacity(rated), JPF), 1000, "about 515,000 FE");
        assertEquals(1286, ElectricStore.fe(ElectricStore.perTick(rated), JPF), 2, "twice the rated draw per tick: about 1,288 FE/t");
        assertEquals(ElectricStore.capacity(rated) / 2, ElectricStore.restart(rated), 1e-6, "one slice of rated draw: half the buffer");
        assertTrue(ElectricStore.motorDraw(30e3, 2, true) > rated, "a variable-speed drive costs 3 %");
    }

    /** A 3 × 9 m bay at full fans: 27 kW of fan power on two 15 kW 4-pole motors (IE3 92.1 %) draws 29.3 kW, 587 FE/t. */
    @Test
    void aForcedDraftBaysFansDrawOverTheirMotors() {
        double fanPower = 27e3, eta = ElectricStore.fanEfficiency(fanPower, 2);
        assertEquals(.921, eta, 1e-9, "one 13.5 kW fan takes a 15 kW motor");
        assertEquals(29.3e3, fanPower / eta, .05e3);
        assertEquals(587, ElectricStore.fe(fanPower / eta, JPF) / 20, 1);
        assertEquals(1, ElectricStore.fanEfficiency(0, 2), 0, "no fans, no loss");
        // HeatSides' 1 kW per m² of face and the cube law below full speed.
        assertEquals(HeatSides.fanPower(100, 1) / 8, HeatSides.fanPower(100, .5), 1e-9);
    }

    /** A draw within the store books it; one past it books what is left, empties it and runs short. */
    @Test
    void aDrawPastTheContentEmptiesTheStoreAndRunsShort() {
        var within = ElectricStore.draw(1e6, 26.7e3, 20);
        assertEquals(1e6 - 26.7e3 * 20, within.content(), 1e-6);
        assertFalse(within.shortfall());
        var past = ElectricStore.draw(1e5, 26.7e3, 20);
        assertEquals(0, past.content());
        assertEquals(1e5, past.booked(), "it books what it held");
        assertTrue(past.shortfall());
        assertFalse(ElectricStore.draw(0, 0, 20).shortfall(), "a stopped unit draws nothing and never runs short");
        assertEquals(500, ElectricStore.accepted(9500, 10_000, 2000), "a store takes up to its capacity");
    }

    /** A generator's load is the room its store has left over the next span, its rating at most; full, it takes none. */
    @Test
    void aGeneratorsLoadIsItsStoresRoom() {
        double rated = 100e3, capacity = ElectricStore.capacity(rated);
        assertEquals(rated, ElectricStore.generatorLoad(0, capacity, rated, 5), "an empty store takes the rating");
        assertEquals(1e5 / 5, ElectricStore.generatorLoad(capacity - 1e5, capacity, rated, 5), 1e-9, "the room over 5 s");
        assertEquals(0, ElectricStore.generatorLoad(capacity, capacity, rated, 5), "a full store takes no load");
        assertEquals(.958, ElectricStore.generatorEfficiency(1e6, 2), 1e-9, "the motor's IE3 table");
    }

    /** A generator delivers within 5 % of its rated speed (reading A64). */
    @Test
    void aGeneratorDeliversOnlyNearItsRatedSpeed() {
        assertTrue(ElectricStore.onSpeed(1500, 1500));
        assertTrue(ElectricStore.onSpeed(1430, 1500));
        assertFalse(ElectricStore.onSpeed(1420, 1500));
        assertFalse(ElectricStore.onSpeed(1580, 1500));
        assertFalse(ElectricStore.onSpeed(0, 0));
    }

    /** A motor without power stands, NO POWER, not latched; a creative motor or a turbine needs none. */
    @Test
    void aMotorWithoutPowerStandsAndItsTripIsNotLatched() {
        for (var drive : new ShaftTrain.Drive[]{ShaftTrain.Drive.FIXED_MOTOR, ShaftTrain.Drive.VARIABLE_MOTOR}) {
            var driver = new ShaftTrain.Driver(drive, 30e3, 2950, 2, true, ShaftTrain.Mode.SPEED, 0, 0, ShaftTrain.Trip.NONE, false, false);
            var step = ShaftTrain.step(driver, 2950, ShaftTrain.Measure.of(24.9e3));
            assertEquals(0, step.speed());
            assertEquals(ShaftTrain.Trip.NO_POWER, step.trip());
            assertEquals("TRIPPED: NO POWER", step.status());
            assertEquals(0, step.electricPower(), "it draws nothing");
            assertFalse(step.trip().latched(), "it clears itself on restart");
        }
        assertTrue(ShaftTrain.Trip.OVERLOAD.latched() && ShaftTrain.Trip.OVERSPEED.latched());
        var creative = new ShaftTrain.Driver(ShaftTrain.Drive.CREATIVE, 0, ShaftTrain.CREATIVE_SPEED, 0, true, ShaftTrain.Mode.SPEED, 0, 0, ShaftTrain.Trip.NONE, false, false);
        assertEquals("RUNNING", ShaftTrain.step(creative, ShaftTrain.CREATIVE_SPEED, ShaftTrain.Measure.of(1e3)).status());
    }

    /** The store saves joules: a change of the FE rate keeps the energy (reading A55). */
    @Test
    void theStoreKeepsJoulesWhateverTheRate() {
        var facts = new ElectricStore.Facts(1.25e6, true);
        assertEquals(500_000, ElectricStore.fe(facts.joules(), 2.5), 1e-9);
        assertEquals(125_000, ElectricStore.fe(facts.joules(), 10), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new ElectricStore.Facts(-1, true));
        assertTrue(ElectricStore.Facts.EMPTY.metered());
        assertFalse(ElectricStore.Facts.UNMETERED.metered());
    }
}
