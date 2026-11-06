package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.equipment.DeviceName;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Operator facts. A DCS edits these same settings on a connected local controller. */
public record ControlSettings(String name,boolean enabled,UUID sensor,long output,Sensor.Unit unit,double target,Action action) {
    public enum Action { INLET, OUTLET }
    public static final ControlSettings EMPTY=new ControlSettings("",false,null,0,Sensor.Unit.METRE,0,Action.OUTLET);
    public ControlSettings {
        name=DeviceName.clean(Objects.requireNonNull(name));Objects.requireNonNull(unit);Objects.requireNonNull(action);
        if(output<0||!Double.isFinite(target)||target<0||unit==Sensor.Unit.HEIGHT_PERCENT&&target>100
                ||enabled&&(sensor==null||output==0||(unit==Sensor.Unit.KELVIN||unit==Sensor.Unit.ABSOLUTE_PASCAL)&&target==0))throw new IllegalArgumentException("Invalid controller settings");
    }
    public CompoundTag save(){
        var t=new CompoundTag();t.putString("name",name);t.putBoolean("enabled",enabled);if(sensor!=null)t.putUUID("sensor",sensor);
        t.putLong("output",output);t.putString("unit",unit.name());t.putDouble("target",target);t.putString("action",action.name());return t;
    }
    public static ControlSettings load(CompoundTag t){
        var keys=new HashSet<>(t.getAllKeys());keys.remove("sensor");
        if(!keys.equals(Set.of("name","enabled","output","unit","target","action"))
                ||!t.contains("name",8)||!t.contains("enabled",1)||!t.contains("output",4)||!t.contains("unit",8)
                ||!t.contains("target",6)||!t.contains("action",8)||t.contains("sensor")&&!t.hasUUID("sensor"))
            throw new IllegalArgumentException("Damaged controller settings");
        if(!DeviceName.clean(t.getString("name")).equals(t.getString("name")))throw new IllegalArgumentException("Damaged controller name");
        return new ControlSettings(t.getString("name"),t.getBoolean("enabled"),t.hasUUID("sensor")?t.getUUID("sensor"):null,
                t.getLong("output"),Sensor.Unit.valueOf(t.getString("unit")),t.getDouble("target"),Action.valueOf(t.getString("action")));
    }
}
