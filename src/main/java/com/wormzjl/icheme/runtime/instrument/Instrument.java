package com.wormzjl.icheme.runtime.instrument;

import com.wormzjl.icheme.world.item.GaugeSettings;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable persistent attachments and ephemeral published samples, their mounts and their hosts' static metadata. */
public final class Instrument {
    private Instrument(){}
    /** What a player attaches: the dial gauges, tank level gauge and pipe fittings. */
    public enum Type { PRESSURE, TEMPERATURE, SIGHT_GLASS, SHUTOFF, CHECK, PRESSURE_VALVE, FILTER, PUMP, COMPRESSOR, LEVEL_GAUGE }
    /** Every mount name; saved by name, like an enum constant. */
    public static final Set<String> MOUNTS=Set.of("pipe_north","pipe_south","pipe_east","pipe_west","pipe_accessory","pressure_top","pressure_bottom","temperature_top","temperature_bottom","level_body","level_boot","valve_sensor",Sensor.SPEED_PICKUP,"tank_level");
    /** One non-dial accessory. Tank instruments and pipe fittings share persistence, revisions and recovery. */
    public record Fitting(Key key,UUID instance,long revision,Type type) {
        public Fitting{Objects.requireNonNull(key);Objects.requireNonNull(instance);Objects.requireNonNull(type);if(revision<=0||type==Type.PRESSURE||type==Type.TEMPERATURE||!key.mount().equals(mount(type)))throw new IllegalArgumentException("Invalid accessory fitting");}
        public static String mount(Type type){return type==Type.LEVEL_GAUGE?"tank_level":"pipe_accessory";}
    }
    public record Key(long host,String mount) {
        public Key{if(host<=0||mount==null||!MOUNTS.contains(mount))throw new IllegalArgumentException("Invalid instrument key");}
    }
    public record Installed(Key key,UUID instance,long revision,GaugeSettings settings) {
        public Installed{Objects.requireNonNull(key);Objects.requireNonNull(instance);Objects.requireNonNull(settings);if(revision<=0||key.mount().equals("pipe_accessory")||key.mount().equals("tank_level")||key.mount().startsWith("pressure_")&&settings.kind()!=GaugeSettings.Kind.PRESSURE||key.mount().startsWith("temperature_")&&settings.kind()!=GaugeSettings.Kind.TEMPERATURE)throw new IllegalArgumentException("Invalid instrument revision or mount kind");}
    }
    public enum Availability { AVAILABLE, NO_FLOW, UNAVAILABLE; public int code(){return switch(this){case AVAILABLE->0;case NO_FLOW->1;case UNAVAILABLE->2;};} public static Availability fromCode(int code){return switch(code){case 0->AVAILABLE;case 1->NO_FLOW;case 2->UNAVAILABLE;default->throw new IllegalArgumentException("Invalid instrument availability");};} }
    public record Sample(double value,long tick,Availability availability) {
        public Sample{Objects.requireNonNull(availability);if(tick<0||!Double.isFinite(value))throw new IllegalArgumentException("Invalid instrument sample");}
        public static Sample unavailable(long tick){return new Sample(0,tick,Availability.UNAVAILABLE);}
        public static Sample temperature(Double flowing,Sample previous,long tick){return flowing!=null&&Double.isFinite(flowing)?new Sample(flowing,tick,Availability.AVAILABLE):previous!=null&&previous.availability!=Availability.UNAVAILABLE?new Sample(previous.value,previous.tick,Availability.NO_FLOW):unavailable(tick);}
    }

    /** A block entity that hosts attachments. Static metadata only; live readings use the requested instruments protocol. */
    public interface Holder {
        Metadata instruments();
    }

    /** Shared selectable extent for instrument sockets and existing model-based pipe accessories. */
    public interface Selectable {
        String id();
        AABB bounds();
    }

    /** One source of truth for the dial transform, interaction bounds and ghost placement. */
    public record Mount(String id,Vec3 centre,Direction face,Set<GaugeSettings.Kind> kinds,boolean top,Vec3 tap,boolean bottomEntry,double probeDepth) implements Selectable {
        public Mount(String id,Vec3 centre,Direction face,Set<GaugeSettings.Kind> kinds,boolean top){this(id,centre,face,kinds,top,centre.add(Vec3.atLowerCornerOf(face.getNormal()).scale(-.17)),false,.12);}
        public Mount{kinds=Set.copyOf(kinds);if(!Double.isFinite(probeDepth)||probeDepth<=0)throw new IllegalArgumentException("Invalid probe depth");if(face.getAxis()==Direction.Axis.Y)throw new IllegalArgumentException("Instrument face must stay upright");}
        public Vec3 normal(){return Vec3.atLowerCornerOf(face.getNormal());}
        public Vec3 right(){var n=normal();return new Vec3(n.z,0,-n.x);}
        public Vec3 point(double x,double y,double depth){return centre.add(right().scale(x)).add(0,y,0).add(normal().scale(depth));}
        public AABB bounds(){return new AABB(point(-.175,-.175,-.07),point(.175,.175,.07)).minmax(new AABB(tap.add(-.03,-.03,-.03),tap.add(.03,.03,.03))).inflate(.008);}
        public double[] localTap(){var delta=tap.subtract(centre);return new double[]{delta.dot(right()),delta.y,delta.dot(normal())};}
    }

    /**
     * Chunk-synchronized static attachment descriptions; the registry is the server's authority. The host's saved copy is
     * state: {@link InstrumentService#bind} restores it from the store when the device loads, so a copy this build cannot
     * read is dropped, never thrown at the block entity's load (which would drop the block entity with its other fields).
     */
    public static final class Metadata {
        private com.wormzjl.icheme.runtime.control.DataPort dataPort;
        public com.wormzjl.icheme.runtime.control.DataPort dataPort(){return dataPort;}
        public void dataPort(com.wormzjl.icheme.runtime.control.DataPort value){dataPort=value;}
        private List<Installed> installed=List.of();
        private Fitting fitting;
        public Fitting fitting(){return fitting;}
        public boolean levelGauge(){return fitting!=null&&fitting.type()==Type.LEVEL_GAUGE;}
        private long revision;
        private java.util.Map<java.util.UUID,String> sensorIds=java.util.Map.of();
        public java.util.Map<java.util.UUID,String> sensorIds(){return sensorIds;}
        public void sensorIds(java.util.Map<java.util.UUID,String> ids){sensorIds=java.util.Map.copyOf(ids);}
        private List<Sensor.Installed> sensors=List.of();
        public List<Sensor.Installed> sensors(){return sensors;}
        public void sensors(List<Sensor.Installed> value){sensors=List.copyOf(value);}
        private boolean recoverOnBreak=true;
        public boolean recoverOnBreak(){return recoverOnBreak;}
        public void recoverOnBreak(boolean value){recoverOnBreak=value;}
        public List<Installed> installed(){return installed;}
        public long revision(){return revision;}
        public void set(List<Installed> value,long revision){set(value,revision,null);}
        public void set(List<Installed> value,long revision,Fitting fitting){installed=List.copyOf(value);this.revision=revision;this.fitting=fitting;}
        public void write(CompoundTag tag){var ids=new ListTag();sensorIds.forEach((instance,id)->{var t=new CompoundTag();t.putUUID("instance",instance);t.putString("id",id);ids.add(t);});tag.put("SensorIds",ids);if(dataPort!=null)tag.put("DataPort",dataPort.save());else tag.remove("DataPort");tag.putLong("AccessoryRevision",revision);var list=new ListTag();for(var i:installed)list.add(AccessoryFacts.installed(i));tag.put("Instruments",list);var transmitters=new ListTag();sensors.forEach(s->transmitters.add(Sensor.save(s)));tag.put("Sensors",transmitters);if(fitting!=null)tag.put("Fitting",AccessoryFacts.fitting(fitting));else tag.remove("Fitting");}
        public void read(CompoundTag tag) {
            var ids=new java.util.HashMap<java.util.UUID,String>();for(var value:tag.getList("SensorIds",10)){var t=(CompoundTag)value;if(ids.size()<8&&t.hasUUID("instance")&&t.getString("id").matches("[A-Z]{2}-[0-9]{4,19}"))ids.put(t.getUUID("instance"),t.getString("id"));}sensorIds=java.util.Map.copyOf(ids);
            try{dataPort=tag.contains("DataPort",10)?com.wormzjl.icheme.runtime.control.DataPort.load(tag.getCompound("DataPort")):null;}catch(IllegalArgumentException invalid){dataPort=null;}
            try {
                long saved=tag.getLong("AccessoryRevision");if(saved<0)throw new IllegalArgumentException("Invalid accessory revision");
                var list=tag.getList("Instruments",10);if(list.size()>6)throw new IllegalArgumentException("Too many host instruments");
                var result=new java.util.ArrayList<Installed>();for(var t:list)result.add(AccessoryFacts.installed((CompoundTag)t));installed=List.copyOf(result);revision=saved;var sensorList=tag.getList("Sensors",10);if(sensorList.size()>8)throw new IllegalArgumentException("Too many sensors");var sensorCopy=new java.util.ArrayList<Sensor.Installed>();for(var t:sensorList)sensorCopy.add(Sensor.load((CompoundTag)t));sensors=List.copyOf(sensorCopy);
                var attached=tag.contains("Fitting")?AccessoryFacts.fitting(tag.getCompound("Fitting")):null;
                if(attached!=null&&(attached.revision()>saved||attached.type()!=Type.LEVEL_GAUGE&&(!result.isEmpty()||!sensors.isEmpty())))throw new IllegalArgumentException("Conflicting accessory copy");
                fitting=attached;
            } catch(IllegalArgumentException unreadable) {
                installed=List.of();sensors=List.of();fitting=null;revision=0;com.wormzjl.icheme.IChemE.LOGGER.warn("accessory_copy status=DROPPED detail={}",unreadable.getMessage());
            }
        }
    }
}
