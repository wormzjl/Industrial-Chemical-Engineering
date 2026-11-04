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

/** GUI-only transmitters. Configuration/identity are facts; readings are accepted engine observations. */
public final class Sensor {
    private Sensor(){}
    /** Saved by name. {@code SPEED} (accessory facts schema 3, batch 2026-11-03-rotating-machinery W3): a steam turbine's
     * speed pickup on its coupling guard, which works its governor valve (owner decision D27). */
    public enum Kind { PRESSURE, TEMPERATURE, FLOW, LEVEL, SPEED }
    public enum Unit { ABSOLUTE_PASCAL, KELVIN, KILOGRAM_PER_SECOND, CUBIC_METRE_PER_SECOND, METRE, HEIGHT_PERCENT, REVOLUTIONS_PER_MINUTE }
    /** The speed pickup's mount on a steam turbine's coupling guard. */
    public static final String SPEED_PICKUP="speed_pickup";
    public record Installed(Instrument.Key key,UUID instance,long revision,Kind kind) {
        public Installed {
            Objects.requireNonNull(key);Objects.requireNonNull(instance);Objects.requireNonNull(kind);
            if(revision<=0||!supports(kind,key.mount()))throw new IllegalArgumentException("Invalid sensor mount");
        }
    }
    public record Reading(UUID instance,Kind kind,String mount,double value,double secondary,double span,long tick,Instrument.Availability availability) {
        public Unit unit(){return switch(kind){case PRESSURE->Unit.ABSOLUTE_PASCAL;case TEMPERATURE->Unit.KELVIN;case FLOW->Unit.KILOGRAM_PER_SECOND;case LEVEL->Unit.METRE;case SPEED->Unit.REVOLUTIONS_PER_MINUTE;};}
        public Optional<Unit> secondaryUnit(){return switch(kind){case FLOW->Optional.of(Unit.CUBIC_METRE_PER_SECOND);case LEVEL->Optional.of(Unit.HEIGHT_PERCENT);default->Optional.empty();};}
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
        if(kind==Kind.FLOW)return List.of();
        if(kind==Kind.SPEED)return speedPickup(host);
        if(kind!=Kind.LEVEL)return host.mounts().stream().filter(m->supports(kind,m.id())).toList();
        if(!(host.entity() instanceof EquipmentShellBlockEntity shell)||shell.shape()==null||!shell.shape().reservoir())return List.of();
        var shape=shell.shape();var n=shape.transform().direction(new double[]{0,0,1});var face=Direction.getNearest(n[0],n[1],n[2]);
        return com.wormzjl.icheme.science.equipment.InstrumentGeometry.levelSockets(shape).stream().map(socket->new Instrument.Mount(socket.id(),new Vec3(socket.x()+shell.origin().getX(),socket.y()+shell.origin().getY(),socket.z()+shell.origin().getZ()),face,Set.of(GaugeSettings.Kind.PRESSURE),false)).toList();
    }

    /** A steam turbine's speed pickup (W3): at its output coupling, on the face the shaft leaves by; none on another host. */
    static List<Instrument.Mount> speedPickup(InstrumentHosts.Host host){
        if(host.pipe()||!(host.entity() instanceof EquipmentShellBlockEntity shell)||shell.shape()==null||shell.shape().kind()!=com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.STEAM_TURBINE)return List.of();
        for(var s:com.wormzjl.icheme.science.equipment.ShapeRasterizer.shafts(shell.shape())){
            if(s.role()!=com.wormzjl.icheme.science.equipment.EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT)continue;
            var face=Direction.fromDelta(s.dx(),s.dy(),s.dz());if(face==null||face.getAxis()==Direction.Axis.Y)continue;
            var centre=new Vec3(shell.origin().getX()+s.cell().x()+.5+.45*s.dx(),shell.origin().getY()+s.cell().y()+.95,shell.origin().getZ()+s.cell().z()+.5+.45*s.dz());
            return List.of(new Instrument.Mount(SPEED_PICKUP,centre,face,Set.of(),true));
        }
        return List.of();
    }
    public static Reading read(Installed sensor,FluidView view,FluidWorldAuthority.InstrumentReading pipe){return read(sensor,view,pipe,null);}
    public static Reading read(Installed sensor,FluidView view,FluidWorldAuthority.InstrumentReading pipe,Reading previous){
        long tick=view.committedTick();double value=0,second=0,span=0;var quality=Instrument.Availability.UNAVAILABLE;
        if(sensor.kind()==Kind.PRESSURE&&sensor.key().mount().equals("valve_sensor")&&view.state()!=null){
            value=view.state().pressure();quality=Instrument.Availability.AVAILABLE;
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
