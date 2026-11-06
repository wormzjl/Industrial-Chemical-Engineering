package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.instrument.Sensor;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** A human-readable instrument ID belongs to one probe instance, independent of its position and host label. */
public record SensorId(UUID instance,long number,Sensor.Kind kind) {
    public SensorId{Objects.requireNonNull(instance);Objects.requireNonNull(kind);if(number<=0)throw new IllegalArgumentException("Invalid sensor ID");}
    public String label(){return String.format(Locale.ROOT,"%s-%04d",switch(kind){case PRESSURE->"PT";case TEMPERATURE->"TT";case FLOW->"FT";case LEVEL->"LT";case SPEED->"ST";},number);}
    public CompoundTag save(){var t=new CompoundTag();t.putUUID("instance",instance);t.putLong("number",number);t.putString("kind",kind.name());return t;}
    public static SensorId load(CompoundTag t){if(!t.getAllKeys().equals(Set.of("instance","number","kind"))||!t.hasUUID("instance")||!t.contains("number",4)||!t.contains("kind",8))throw new IllegalArgumentException("Damaged sensor ID facts");return new SensorId(t.getUUID("instance"),t.getLong("number"),Sensor.Kind.valueOf(t.getString("kind")));}
}
