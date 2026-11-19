package com.wormzjl.icheme.runtime.control;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.runtime.instrument.Sensor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.UUID;

/** The alarm beacon's settings and facts (batch 2026-11-15-fluid-interfaces, W9, plan section 6). */
class AlarmSettingsTest {
    private static final AlarmSettings LEVEL_HIGH =
            new AlarmSettings("DRUM HIGH LEVEL", true, AlarmSettings.Source.INPUT, new UUID(0, 7), Sensor.Unit.HEIGHT_PERCENT, AlarmSettings.Comparison.ABOVE, 80);

    @Test
    void settingsRoundTripWithTheirEnumsByName() {
        var tag = LEVEL_HIGH.save();
        assertEquals("INPUT", tag.getString("source"));
        assertEquals("ABOVE", tag.getString("comparison"));
        assertEquals("HEIGHT_PERCENT", tag.getString("unit"));
        assertEquals(LEVEL_HIGH, AlarmSettings.load(tag));
        var any = new AlarmSettings("", true, AlarmSettings.Source.ANY_ALARM, null, Sensor.Unit.METRE, AlarmSettings.Comparison.BELOW, 0);
        assertFalse(any.save().contains("sensor"));
        assertEquals(any, AlarmSettings.load(any.save()));
        assertEquals(AlarmSettings.EMPTY, AlarmSettings.load(AlarmSettings.EMPTY.save()));
    }

    @Test
    void aDamagedTagRefuses() {
        var extra = LEVEL_HIGH.save();
        extra.putInt("latch", 1);
        assertThrows(IllegalArgumentException.class, () -> AlarmSettings.load(extra));
        var missing = LEVEL_HIGH.save();
        missing.remove("threshold");
        assertThrows(IllegalArgumentException.class, () -> AlarmSettings.load(missing));
        var unknown = LEVEL_HIGH.save();
        unknown.putString("comparison", "BETWEEN");
        assertThrows(IllegalArgumentException.class, () -> AlarmSettings.load(unknown));
        var typed = LEVEL_HIGH.save();
        typed.putString("threshold", "80");
        assertThrows(IllegalArgumentException.class, () -> AlarmSettings.load(typed));
        var infinite = LEVEL_HIGH.save();
        infinite.putDouble("threshold", Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> AlarmSettings.load(infinite));
        // An enabled input with no sensor, and Any alarm with one, are not settings.
        var noSensor = LEVEL_HIGH.save();
        noSensor.remove("sensor");
        assertThrows(IllegalArgumentException.class, () -> AlarmSettings.load(noSensor));
        var anyWithSensor = LEVEL_HIGH.save();
        anyWithSensor.putString("source", "ANY_ALARM");
        assertThrows(IllegalArgumentException.class, () -> AlarmSettings.load(anyWithSensor));
    }

    @Test
    void theFormatFiveStoreReadsItsBeaconAndOlderStoresReadWithNone() throws Exception {
        var five = ControlFacts.load(TagParser.parseTag(Files.readString(Path.of("src/test/resources/compat/control/control-5.snbt"))));
        assertEquals(1, five.alarms().size());
        var beacon = five.alarm(new UUID(0, 5));
        assertEquals(LEVEL_HIGH, beacon.settings());
        assertEquals(2, beacon.revision());
        assertEquals(DataPort.Kind.ALARM, five.port(beacon.id()).kind());
        assertTrue(five.wired(beacon.id()));
        var saved = five.save(new CompoundTag(), null);
        assertEquals(ControlFacts.FORMAT, saved.getInt("format"));
        assertEquals(saved, ControlFacts.load(saved).save(new CompoundTag(), null));
        for (var older : new String[] {"control-1", "control-2", "control-3", "control-4"})
            assertTrue(ControlFacts.load(TagParser.parseTag(Files.readString(Path.of("src/test/resources/compat/control/" + older + ".snbt")))).alarms().isEmpty(), older);
    }

    @Test
    void aFormatFiveStoreRefusesADamagedBeacon() throws Exception {
        var tag = TagParser.parseTag(Files.readString(Path.of("src/test/resources/compat/control/control-5.snbt")));
        // An older format with a beacon list is not a store of that format.
        var four = tag.copy();
        four.putInt("format", 4);
        assertThrows(IllegalArgumentException.class, () -> ControlFacts.load(four));
        // A beacon without its socket.
        var orphan = tag.copy();
        orphan.put("ports", new net.minecraft.nbt.ListTag());
        orphan.put("cables", new net.minecraft.nbt.ListTag());
        assertThrows(IllegalArgumentException.class, () -> ControlFacts.load(orphan));
        // Format 5 without its list.
        var bare = tag.copy();
        bare.remove("alarms");
        assertThrows(IllegalArgumentException.class, () -> ControlFacts.load(bare));
    }

    @Test
    void aCellHoldsOnePanelOrOneBeacon() {
        var facts = ControlFacts.load(TagParserHolder.empty());
        var at = new net.minecraft.core.BlockPos(0, 0, 1);
        facts.panel(new ControlFacts.Panel(new UUID(0, 9), "minecraft:overworld", at, false, 0, ControlSettings.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> facts.alarm(new ControlFacts.Alarm(new UUID(0, 5), "minecraft:overworld", at, 0, AlarmSettings.EMPTY)));
    }

    /** An empty current-format store. */
    private static final class TagParserHolder {
        static CompoundTag empty() {
            return new ControlFacts().save(new CompoundTag(), null);
        }
    }
}
