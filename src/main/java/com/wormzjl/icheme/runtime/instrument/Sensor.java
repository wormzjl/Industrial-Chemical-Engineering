package com.wormzjl.icheme.runtime.instrument;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.item.GaugeSettings;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Digital transmitters. Configuration/identity are facts; readings are accepted engine observations. */
public final class Sensor {
    private Sensor(){}
    /** Saved by name. {@code SPEED} (accessory facts schema 3, batch 2026-11-03-rotating-machinery W3): a steam turbine's
     * speed pickup on its coupling guard, which works its governor valve (owner decision D27). */
    public enum Kind {
        PRESSURE("P"), TEMPERATURE("T"), FLOW("F"), LEVEL("L"), SPEED("N"),
        /** An equipment's alarm (batch 2026-11-09-furnace-fuel-power): 1 while raised, 0 clear; an equipment channel, never an installed sensor. */
        ALARM("A");
        private final String symbol;
        Kind(String symbol){this.symbol=symbol;}
        public String symbol(){return symbol;}
    }
    public enum Unit { ABSOLUTE_PASCAL, KELVIN, KILOGRAM_PER_SECOND, CUBIC_METRE_PER_SECOND, METRE, HEIGHT_PERCENT, REVOLUTIONS_PER_MINUTE, STATE }
    public static Unit primaryUnit(Kind kind){return switch(kind){case PRESSURE->Unit.ABSOLUTE_PASCAL;case TEMPERATURE->Unit.KELVIN;case FLOW->Unit.KILOGRAM_PER_SECOND;case LEVEL->Unit.METRE;case SPEED->Unit.REVOLUTIONS_PER_MINUTE;case ALARM->Unit.STATE;};}
    public static Optional<Unit> secondaryUnit(Kind kind){return switch(kind){case FLOW->Optional.of(Unit.CUBIC_METRE_PER_SECOND);case LEVEL->Optional.of(Unit.HEIGHT_PERCENT);default->Optional.empty();};}
    /** The speed pickup's mount on a steam turbine's coupling guard. */
    public static final String SPEED_PICKUP="speed_pickup";
    public record Installed(Instrument.Key key,UUID instance,long revision,Kind kind) {
        public Installed {
            Objects.requireNonNull(key);Objects.requireNonNull(instance);Objects.requireNonNull(kind);
            if(revision<=0||!supports(kind,key.mount()))throw new IllegalArgumentException("Invalid sensor mount");
        }
    }
    public record Reading(UUID instance,Kind kind,String mount,double value,double secondary,double span,long tick,Instrument.Availability availability) {
        public Unit unit(){return primaryUnit(kind);}
        public Optional<Unit> secondaryUnit(){return Sensor.secondaryUnit(kind);}
        public Reading {
            Objects.requireNonNull(instance);Objects.requireNonNull(kind);Objects.requireNonNull(mount);Objects.requireNonNull(availability);
            if(!Double.isFinite(value)||!Double.isFinite(secondary)||!Double.isFinite(span)||span<0||tick<0)throw new IllegalArgumentException("Invalid sensor reading");
        }
    }
    public static boolean supports(Kind kind,String mount){return switch(kind){
        case PRESSURE->mount.equals("valve_sensor")||mount.startsWith("pipe_")&&!mount.equals("pipe_accessory")||mount.startsWith("pressure_");
        case TEMPERATURE->mount.startsWith("pipe_")&&!mount.equals("pipe_accessory")||mount.startsWith("temperature_");
        case FLOW->mount.equals("valve_sensor")||mount.startsWith("pipe_")&&!mount.equals("pipe_accessory");
        case LEVEL->mount.equals("level_body")||mount.equals("level_boot");
        case SPEED->mount.equals(SPEED_PICKUP);
        case ALARM->false;
    };}
    public static List<Instrument.Mount> mounts(InstrumentHosts.Host host,Kind kind){
        if(host.pipe()){
            if(FluidDeviceBlock.hasPressureValve(host.entity().getBlockState())){
                if(kind!=Kind.PRESSURE&&kind!=Kind.FLOW)return List.of();
                var centre=host.entity().getBlockPos().getCenter().add(0,.75,-.18);
                return List.of(new Instrument.Mount("valve_sensor",centre,Direction.NORTH,Set.of(GaugeSettings.Kind.PRESSURE),false,centre.add(0,-.3,0),false,.12));
            }
            if(InstrumentHosts.legacy(host.entity().getBlockState())!=null||kind==Kind.LEVEL||kind==Kind.SPEED)return List.of();
            return host.mounts();
        }
        if(kind==Kind.FLOW||kind==Kind.ALARM)return List.of();
        if(kind==Kind.SPEED)return speedPickup(host);
        if(kind!=Kind.LEVEL)return host.mounts().stream().filter(m->supports(kind,m.id())).toList();
        if(!(host.entity() instanceof EquipmentShellBlockEntity shell)||shell.shape()==null||!shell.shape().reservoir())return List.of();
        return levelMounts(shell.shape(),shell.origin());
    }
    static List<Instrument.Mount> levelMounts(EquipmentShape shape,net.minecraft.core.BlockPos origin){
        return com.wormzjl.icheme.science.equipment.EquipmentInstruments.of(shape).probes().stream().filter(p->p.manual()&&p.quantity()==com.wormzjl.icheme.science.equipment.EquipmentInstruments.Quantity.LEVEL).map(p->InstrumentHosts.equipmentMount(p,origin)).toList();
    }

    /** Rotating units expose their internal pickup through the equipment channel catalog. */
    static List<Instrument.Mount> speedPickup(InstrumentHosts.Host host){
        return List.of();
    }
    public static Reading read(Installed sensor,FluidView view,FluidWorldAuthority.InstrumentReading pipe){return read(sensor,view,pipe,null);}
    public static Reading read(Installed sensor,FluidView view,FluidWorldAuthority.InstrumentReading pipe,Reading previous){
        long tick=view.committedTick();double value=0,second=0,span=0;var quality=Instrument.Availability.UNAVAILABLE;
        if(sensor.kind()==Kind.PRESSURE&&sensor.key().mount().equals("valve_sensor")&&view.state()!=null){
            value=view.state().pressure();quality=Instrument.Availability.AVAILABLE;
        }else if(sensor.kind()==Kind.TEMPERATURE&&view.heat()!=null){
            if(pipe!=null&&pipe.flowing()&&view.heat().outletTemperature()>0){value=view.heat().outletTemperature();quality=Instrument.Availability.AVAILABLE;}
            else if(previous!=null&&previous.availability()!=Instrument.Availability.UNAVAILABLE){value=previous.value();tick=previous.tick();quality=Instrument.Availability.NO_FLOW;}
        }else if(pipe!=null){
            switch(sensor.kind()){
                case PRESSURE->{value=sensor.key().mount().equals("valve_sensor")&&view.state()!=null?view.state().pressure():pipe.pressure();quality=Instrument.Availability.AVAILABLE;}
                case TEMPERATURE->{if(pipe.flowing()){value=pipe.temperature();quality=Instrument.Availability.AVAILABLE;}else if(previous!=null&&previous.availability()!=Instrument.Availability.UNAVAILABLE){value=previous.value();tick=previous.tick();quality=Instrument.Availability.NO_FLOW;}}
                case FLOW->{value=pipe.massFlow();second=pipe.volumeFlow()==null?0:pipe.volumeFlow();quality=pipe.volumeFlow()==null?Instrument.Availability.UNAVAILABLE:Instrument.Availability.AVAILABLE;}
                default->{}
            }
        }else if(view.tank()!=null){
            var tank=view.tank();var state=view.state();
            switch(sensor.kind()){
                case PRESSURE->{var p=sensor.key().mount().equals("pressure_top")?tank.topPressure():tank.bottomPressure();if(p!=null){value=p;quality=Instrument.Availability.AVAILABLE;}}
                case TEMPERATURE->{if(state!=null){value=state.temperature();quality=Instrument.Availability.AVAILABLE;}}
                case LEVEL->{
                    var g=tank.geometry();boolean boot=sensor.key().mount().equals("level_boot");
                    span=boot?g.bootDepth():g.height();
                    if(state!=null){double v=boot?state.phaseVolumes()[1]+state.solids().volume()*state.phaseVolumes()[1]/Math.max(1e-300,state.phaseVolumes()[0]+state.phaseVolumes()[1]):state.phaseVolumes()[0]+state.phaseVolumes()[1]+state.solids().volume();value=v==0?0:g.heightAt(v)+(boot?g.bootDepth():0);}
                    second=span>0?100*value/span:0;quality=Instrument.Availability.AVAILABLE;
                }
                default->{}
            }
        }
        return new Reading(sensor.instance(),sensor.kind(),sensor.key().mount(),value,second,span,tick,quality);
    }
    static CompoundTag save(Installed value){
        var t=new CompoundTag();t.putLong("host",value.key().host());t.putString("mount",value.key().mount());t.putUUID("instance",value.instance());t.putLong("revision",value.revision());t.putString("kind",value.kind().name());return t;
    }
    static Installed load(CompoundTag tag){
        var f=new AccessoryFacts.Fields(tag,"sensor");long host=f.i64("host");String mount=f.mount("mount");var id=f.uuid("instance");long rev=f.i64("revision");String name=f.text("kind");f.end();
        return f.valid("sensor",()->new Installed(new Instrument.Key(host,mount),id,rev,Kind.valueOf(name)));
    }
}
