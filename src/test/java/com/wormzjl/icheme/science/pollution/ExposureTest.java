package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.pollution.Exposure.Dose;
import com.wormzjl.icheme.science.pollution.Exposure.Effect;
import com.wormzjl.icheme.science.pollution.Exposure.Level;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The exposure law (WP5): levels, damage and effects per channel; each channel stands alone. */
class ExposureTest {
    @Test
    void levelsSwitchAtEachBound() {
        assertEquals(Level.NONE, Exposure.level(Math.nextDown(0.1)));
        assertEquals(Level.LOW, Exposure.level(0.1));
        assertEquals(Level.LOW, Exposure.level(Math.nextDown(0.3)));
        assertEquals(Level.MEDIUM, Exposure.level(0.3));
        assertEquals(Level.MEDIUM, Exposure.level(Math.nextDown(1.0)));
        assertEquals(Level.HIGH, Exposure.level(1.0));
        assertEquals(Level.HIGH, Exposure.level(Math.nextDown(3.0)));
        assertEquals(Level.EXTREME, Exposure.level(3.0));
        assertEquals(Level.EXTREME, Exposure.level(Double.POSITIVE_INFINITY));
        assertEquals(Level.NONE, Exposure.level(0));
        assertEquals(Level.NONE, Exposure.level(-1));
        assertEquals(Level.NONE, Exposure.level(Double.NaN));
    }

    @Test
    void damageTable() {
        double[] toxic = {0, 0, 0, 2, 6};
        double[] corrosive = {0, 0, 0.5, 2, 6};
        double[] particulate = {0, 0, 0, 1, 3};
        for (var level : Level.values()) {
            int i = level.ordinal();
            assertEquals(toxic[i], Exposure.damagePerSecond(Channel.TOXIC, level), "toxic " + level);
            assertEquals(corrosive[i], Exposure.damagePerSecond(Channel.CORROSIVE, level), "corrosive " + level);
            assertEquals(particulate[i], Exposure.damagePerSecond(Channel.PARTICULATE, level), "particulate " + level);
            assertEquals(0, Exposure.damagePerSecond(Channel.COMBUSTIBLE, level), "combustible never damages, " + level);
        }
        // A 20 HP player dies in 10 s at the toxic IDLH and in 20 s at the particulate reference.
        assertEquals(10.0, 20.0 / Exposure.damagePerSecond(Channel.TOXIC, Exposure.level(1.0)));
        assertEquals(20.0, 20.0 / Exposure.damagePerSecond(Channel.PARTICULATE, Exposure.level(1.0)));
    }

    @Test
    void effectsPerChannelAndLevel() {
        for (var channel : Channel.values()) {
            assertEquals(List.of(), Exposure.effects(channel, Level.NONE), channel + " at NONE");
        }
        assertEquals(List.of(new Dose(Effect.POISON, 0), new Dose(Effect.WEAKNESS, 0)), Exposure.effects(Channel.TOXIC, Level.LOW));
        var toxicHeavy = List.of(new Dose(Effect.POISON, 1), new Dose(Effect.SLOWNESS, 0), new Dose(Effect.BLINDNESS, 0), new Dose(Effect.WEAKNESS, 0));
        var corrosive = List.of(new Dose(Effect.WEAKNESS, 0), new Dose(Effect.MINING_FATIGUE, 0));
        var combustibleHeavy = List.of(new Dose(Effect.NAUSEA, 0), new Dose(Effect.SLOWNESS, 0));
        var particulateHeavy = List.of(new Dose(Effect.DARKNESS, 0), new Dose(Effect.SLOWNESS, 0), new Dose(Effect.HUNGER, 0));
        assertEquals(List.of(new Dose(Effect.NAUSEA, 0)), Exposure.effects(Channel.COMBUSTIBLE, Level.LOW));
        assertEquals(List.of(new Dose(Effect.DARKNESS, 0)), Exposure.effects(Channel.PARTICULATE, Level.LOW));
        assertEquals(corrosive, Exposure.effects(Channel.CORROSIVE, Level.LOW));
        for (var level : List.of(Level.MEDIUM, Level.HIGH, Level.EXTREME)) {
            assertEquals(toxicHeavy, Exposure.effects(Channel.TOXIC, level), "toxic " + level);
            assertEquals(corrosive, Exposure.effects(Channel.CORROSIVE, level), "corrosive " + level);
            assertEquals(combustibleHeavy, Exposure.effects(Channel.COMBUSTIBLE, level), "combustible " + level);
            assertEquals(particulateHeavy, Exposure.effects(Channel.PARTICULATE, level), "particulate " + level);
        }
    }

    @Test
    void armourCorrodesFromMedium() {
        assertFalse(Exposure.corrodesArmour(Level.NONE));
        assertFalse(Exposure.corrodesArmour(Level.LOW));
        assertTrue(Exposure.corrodesArmour(Level.MEDIUM));
        assertTrue(Exposure.corrodesArmour(Level.HIGH));
        assertTrue(Exposure.corrodesArmour(Level.EXTREME));
        assertFalse(Exposure.corrodesArmour(null));
    }

    @Test
    void particulateStandsApartFromToxic() {
        // Particulate never gives a toxic effect, and its damage is its own (half the toxic one at each level).
        for (var level : Level.values()) {
            for (var dose : Exposure.effects(Channel.PARTICULATE, level)) {
                assertNotEquals(Effect.POISON, dose.effect(), "particulate gives no poison at " + level);
                assertNotEquals(Effect.BLINDNESS, dose.effect(), "particulate gives no blindness at " + level);
            }
            assertEquals(Exposure.damagePerSecond(Channel.TOXIC, level) / 2, Exposure.damagePerSecond(Channel.PARTICULATE, level), "particulate damage at " + level);
        }
        // A particulate cube below the first bound gives nothing however toxic the air beside it would be.
        assertEquals(List.of(), Exposure.effects(Channel.PARTICULATE, Exposure.level(0.09)));
    }
}
