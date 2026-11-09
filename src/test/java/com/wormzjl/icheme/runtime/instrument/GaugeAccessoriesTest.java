package com.wormzjl.icheme.runtime.instrument;

import com.mojang.serialization.JsonOps;
import com.wormzjl.icheme.world.item.GaugeSettings;
import com.wormzjl.icheme.world.item.PipelineToolItem;
import com.wormzjl.icheme.world.item.GaugeSettings.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PipeObservation;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GaugeAccessoriesTest {
    @Test void settingsRoundTripAndKeepPhysicalEndpointsAcrossEveryUnitAndReference(){
        for(var initial:List.of(GaugeSettings.PRESSURE,GaugeSettings.TEMPERATURE))for(var unit:Unit.values())for(boolean relative:List.of(false,true)){
            if((initial.kind()==Kind.PRESSURE)!=(unit.factor!=1||unit==Unit.PA)||initial.kind()==Kind.TEMPERATURE&&relative)continue;
            var s=initial.displayAs(unit,relative);
            assertEquals(initial.minimum(),s.minimum());assertEquals(initial.maximum(),s.maximum());
            var nbt=GaugeSettings.CODEC.encodeStart(NbtOps.INSTANCE,s).getOrThrow();assertEquals(s,GaugeSettings.CODEC.parse(NbtOps.INSTANCE,nbt).getOrThrow());
            var b=Unpooled.buffer();try{GaugeSettings.STREAM_CODEC.encode(b,s);assertEquals(s,GaugeSettings.STREAM_CODEC.decode(b));assertFalse(b.isReadable());}finally{b.release();}
            assertEquals(s.minimum(),s.si(s.display(s.minimum())),1e-9);
        }
        assertEquals(0,GaugeSettings.PRESSURE.display(GaugeSettings.ATMOSPHERE));
        assertEquals(0,GaugeSettings.TEMPERATURE.display(273.15));
        assertEquals(0,GaugeSettings.PRESSURE.needle(-1));assertEquals(1,GaugeSettings.PRESSURE.needle(1e8));assertTrue(GaugeSettings.PRESSURE.outside(1e8));
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1})assertThrows(IllegalArgumentException.class,()->new GaugeSettings(Kind.PRESSURE,bad,1e6,Unit.PA,false));
        assertThrows(IllegalArgumentException.class,()->new GaugeSettings(Kind.TEMPERATURE,10,10,Unit.KELVIN,false));
        assertThrows(IllegalArgumentException.class,()->new GaugeSettings(Kind.TEMPERATURE,0,10,Unit.BAR,false));
        var broken=(CompoundTag)GaugeSettings.CODEC.encodeStart(NbtOps.INSTANCE,GaugeSettings.PRESSURE).getOrThrow();broken.putDouble("minimum",-1);assertTrue(GaugeSettings.CODEC.parse(NbtOps.INSTANCE,broken).error().isPresent());broken.putString("unit","unknown");assertTrue(GaugeSettings.CODEC.parse(NbtOps.INSTANCE,broken).error().isPresent());
        var invalid=Unpooled.buffer();try{invalid.writeByte(99);invalid.writeByte(0);assertThrows(IllegalArgumentException.class,()->GaugeSettings.STREAM_CODEC.decode(invalid));}finally{invalid.release();}
    }
    /**
     * Saved kind and unit names read through their alias tables (AGENTS.md: a renamed or removed constant gets an alias).
     * Without an alias an unknown name fails the component as before, and the stack keeps its item's default settings.
     */
    @Test void gaugeSettingsNamesReadThroughTheirAliasTables(){
        var settings=new GaugeSettings(Kind.PRESSURE,0,5e5,Unit.KPA,false);
        var current=(CompoundTag)GaugeSettings.CODEC.encodeStart(NbtOps.INSTANCE,settings).getOrThrow();
        var renamed=current.copy();renamed.putString("kind","PRESSURE_GAUGE");renamed.putString("unit","KILOPASCAL");
        var aliased=GaugeSettings.codec(Map.of("PRESSURE_GAUGE","PRESSURE"),Map.of("KILOPASCAL","KPA"));
        assertEquals(settings,aliased.parse(NbtOps.INSTANCE,renamed).getOrThrow());assertEquals(settings,aliased.parse(NbtOps.INSTANCE,current).getOrThrow());
        assertTrue(GaugeSettings.CODEC.parse(NbtOps.INSTANCE,renamed).error().isPresent(),"no alias: the component fails");
        // The accessory store reads the item's encoding, through the same tables.
        assertEquals(settings,AccessoryFacts.settings(current,"item"));
    }
    /** The pipeline tool's diameter setting, the other accessory item component: the same exact round trip and rejections. */
    @Test void pipelineToolSettingsRoundTripExactDiametersAndRejectInvalidStorageAndWireValues() {
        for (double diameter : new double[]{.001, PipelineToolItem.Settings.DEFAULT.diameter(), .123456789012345, PipelineToolItem.Settings.MAX_DIAMETER}) {
            var settings = new PipelineToolItem.Settings(diameter);
            var saved = PipelineToolItem.Settings.CODEC.encodeStart(NbtOps.INSTANCE, settings).getOrThrow();
            assertEquals(settings, PipelineToolItem.Settings.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow());
            var buffer = Unpooled.buffer();
            try {
                PipelineToolItem.Settings.STREAM_CODEC.encode(buffer, settings);
                assertEquals(settings, PipelineToolItem.Settings.STREAM_CODEC.decode(buffer));
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
        for (double diameter : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, -.1, .0009, .8001, 1}) {
            assertThrows(IllegalArgumentException.class, () -> new PipelineToolItem.Settings(diameter));
            assertTrue(PipelineToolItem.Settings.CODEC.parse(NbtOps.INSTANCE, DoubleTag.valueOf(diameter)).error().isPresent());
            var buffer = Unpooled.buffer();
            try {
                buffer.writeDouble(diameter);
                assertThrows(IllegalArgumentException.class, () -> PipelineToolItem.Settings.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
        assertTrue(PipelineToolItem.Settings.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonPrimitive("invalid")).error().isPresent());
    }
    @Test void nativeReservoirSocketsStayDistinctUprightAndStableAcrossHeadingsAndSizes(){
        for(var heading:EquipmentShape.Heading.values())for(double d:new double[]{.5,2,10}){
            var drum=EquipmentShape.drum(d,4,EquipmentShape.Orientation.VERTICAL,heading,0);var mounts=InstrumentHosts.reservoirMounts(drum,BlockPos.ZERO);
            assertEquals(Set.of("pressure_top","temperature_top"),mounts.stream().map(Instrument.Mount::id).collect(java.util.stream.Collectors.toSet()));
            for(var m:mounts){assertEquals(heading.name(),m.face().name());assertEquals(0,m.right().y);assertTrue(m.bounds().contains(m.centre()));}
            for(int i=0;i<mounts.size();i++)for(int j=i+1;j<mounts.size();j++)assertFalse(mounts.get(i).bounds().intersects(mounts.get(j).bounds()));
        }
        for(var heading:EquipmentShape.Heading.values()){
            var tank=EquipmentShape.storage(EquipmentShape.Kind.TANK,4,4,heading,0);assertEquals(List.of("pressure_bottom","temperature_bottom"),InstrumentHosts.reservoirMounts(tank,BlockPos.ZERO).stream().map(Instrument.Mount::id).toList());
            var sphere=EquipmentShape.storage(EquipmentShape.Kind.SPHERE,4,1,heading,2);assertEquals(List.of("pressure_top","temperature_top"),InstrumentHosts.reservoirMounts(sphere,BlockPos.ZERO).stream().map(Instrument.Mount::id).toList());
        }
        assertEquals(2,InstrumentHosts.reservoirMounts(EquipmentShape.drum(2,4,EquipmentShape.Orientation.HORIZONTAL,EquipmentShape.Heading.NORTH,1),BlockPos.ZERO).size());
    }
    @Test void tankDigitalLevelMountIsReachableAtGradeAndSeparateFromServiceTapsAndSightColumn(){
        var origin=new BlockPos(23,64,-41);
        for(var heading:EquipmentShape.Heading.values())for(double diameter:new double[]{4,5,6,20,40})for(int height:new int[]{4,14,24}){
            var shape=EquipmentShape.storage(EquipmentShape.Kind.TANK,diameter,height,heading,0);
            var mounts=Sensor.levelMounts(shape,origin);assertEquals(1,mounts.size());var mount=mounts.getFirst();
            assertEquals("level_body",mount.id());assertEquals(heading.name(),mount.face().name());
            assertEquals(origin.getY()+1.35,mount.centre().y,1e-10);
            var eye=mount.centre().add(mount.normal().scale(2)).add(0,.27,0);
            assertTrue(mount.bounds().distanceToSqr(eye)<9,"standing two metres outside the shell is within reach");
            for(var tap:InstrumentGeometry.sockets(shape)){
                var centre=new net.minecraft.world.phys.Vec3(origin.getX()+tap.x(),origin.getY()+tap.y(),origin.getZ()+tap.z());
                assertTrue(mount.centre().distanceTo(centre)>.5,"independent P/T service taps");
            }
            var gauge=InstrumentHosts.LevelGauge.selectionBounds(shape).move(origin);
            assertFalse(mount.bounds().intersects(gauge),"the optional sight column can coexist");
        }
    }
    @Test void levelPadsRemainDistinctAcrossNativeSizesAndHeadingsAndCoversAreClosed(){
        for(var heading:EquipmentShape.Heading.values())for(double d:new double[]{4,6,10}){
            var shapes=List.of(EquipmentShape.storage(EquipmentShape.Kind.TANK,d,4,heading,0),EquipmentShape.storage(EquipmentShape.Kind.SPHERE,d,1,heading,2),EquipmentShape.drum(d,6,EquipmentShape.Orientation.VERTICAL,heading,0),EquipmentShape.drum(d,6,EquipmentShape.Orientation.HORIZONTAL,heading,EquipmentShape.minimumBootSupport(d,6),List.of(),true));
            for(var shape:shapes){var pads=InstrumentGeometry.levelSockets(shape);assertEquals(shape.boot()?2:1,pads.size());assertEquals("level_body",pads.getFirst().id());if(shape.boot())assertEquals("level_boot",pads.getLast().id());
                for(var p:pads)for(double x:new double[]{p.x(),p.y(),p.z()})assertTrue(Double.isFinite(x));
                assertEquals(shape.volume(),shape.fluidGeometry().capacity(),1e-10*shape.volume());
            }
        }
        for(boolean level:List.of(false,true)){
            var faces=InstrumentGeometry.transmitter(level);assertFalse(faces.isEmpty());
            for(var face:faces){var v=face.vertices();for(double x:v)assertTrue(Double.isFinite(x));
                for(int i=0;i<12;i+=3){assertTrue(Math.abs(v[i])<=InstrumentGeometry.RADIUS);assertTrue(Math.abs(v[i+1])<=InstrumentGeometry.RADIUS);assertTrue(Math.abs(v[i+2])<=.1,"cover stays inside the mount envelope");}
                double nx=(v[4]-v[1])*(v[8]-v[2])-(v[5]-v[2])*(v[7]-v[1]),ny=(v[5]-v[2])*(v[6]-v[0])-(v[3]-v[0])*(v[8]-v[2]),nz=(v[3]-v[0])*(v[7]-v[1])-(v[4]-v[1])*(v[6]-v[0]);
                double cx=0,cy=0,cz=0;for(int i=0;i<12;i+=3){cx+=v[i]/4;cy+=v[i+1]/4;cz+=v[i+2]/4;}assertTrue(nx*cx+ny*cy+nz*(cz-.005)>0,"outward cover winding");
            }
        }
    }
    @Test void pipeSocketsRejectBendsAndJunctionsAndShareSelectionBounds(){
        for(var axis:Direction.values())for(double bore:new double[]{.001,.05,1}){
            int mask=(1<<axis.ordinal())|(1<<axis.getOpposite().ordinal());var mounts=InstrumentHosts.pipeMounts(BlockPos.ZERO,bore,mask,axis);
            assertFalse(mounts.isEmpty());for(var m:mounts){assertNotEquals(Direction.Axis.Y,m.face().getAxis());assertTrue(m.bottomEntry());assertTrue(m.tap().y<=m.centre().y);assertTrue(m.kinds().containsAll(Set.of(Kind.PRESSURE,Kind.TEMPERATURE)));}
            assertFalse(InstrumentHosts.pipeMounts(BlockPos.ZERO,bore,1<<axis.ordinal(),axis).isEmpty());assertFalse(InstrumentHosts.pipeMounts(BlockPos.ZERO,bore,0,axis).isEmpty());
            var lateral=axis.getAxis()==Direction.Axis.X?Direction.NORTH:Direction.EAST;
            assertTrue(InstrumentHosts.pipeMounts(BlockPos.ZERO,bore,(1<<axis.ordinal())|(1<<lateral.ordinal()),axis).isEmpty());assertTrue(InstrumentHosts.pipeMounts(BlockPos.ZERO,bore,mask|(1<<lateral.ordinal()),axis).isEmpty());
        }
    }
    @Test void pressureUsesSectionLossesHydrostaticsAndAcceptedActuatorHead(){
        var horizontal=new PipeObservation(1,400000,200000,0,0,1000,310,true,List.of(1d,1d),List.of(1d,3d));
        assertEquals(350000,horizontal.pressure(.5,0),1e-9);assertEquals(250000,horizontal.pressure(.5,0,true),1e-9);assertEquals(400000,horizontal.pressure(0,0));assertEquals(200000,horizontal.pressure(1,0));
        var staticRiser=new PipeObservation(1,300000,300000-1000*9.80665*10,0,10,1000,310,false,List.of(10d),List.of(0d));
        assertEquals(300000-1000*9.80665*4,staticRiser.pressure(.4,4),1e-8);
        var downhill=new PipeObservation(1,500000,300000,10,0,850,350,true,List.of(2d,8d),List.of(7d,3d));assertEquals(500000,downhill.pressure(0,10));assertEquals(300000,downhill.pressure(1,0),1e-9);
    }
    @Test void temperatureIsUnavailableUntilAcceptedFlowThenRetainedStale(){
        assertEquals(Instrument.Availability.UNAVAILABLE,Instrument.Sample.temperature(null,null,10).availability());var flowing=Instrument.Sample.temperature(320d,null,20);var stopped=Instrument.Sample.temperature(null,flowing,120);assertEquals(320,stopped.value());assertEquals(20,stopped.tick());assertEquals(Instrument.Availability.NO_FLOW,stopped.availability());assertEquals(330,Instrument.Sample.temperature(330d,stopped,220).value());
    }
    @Test void acceptedSolverObservationsReachTheIntervalWithoutAddingNodes(){
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        var a=new com.wormzjl.icheme.science.fluid.network.PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,310,250000,()->{}));
        var b=new com.wormzjl.icheme.science.fluid.network.PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,290,150000,()->{}));
        var pipe=new com.wormzjl.icheme.science.fluid.network.PassiveNetwork.Pipe(3,0,1,new com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry(10,.05,.000045,0));
        var graph=new com.wormzjl.icheme.science.fluid.network.PassiveNetwork(List.of(a,b),List.of(pipe));
        var interval=new com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver(model).solve(graph,.05,com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(2,interval.graph().reservoirs().size());assertEquals(1,interval.observations().size());var observed=interval.observations().getFirst();
        assertTrue(observed.flowing());assertEquals(3,observed.pipe());assertEquals(interval.graph().reservoirs().get(0).state().pressure(),observed.pressure(0,0),.01);assertEquals(interval.graph().reservoirs().get(1).state().pressure(),observed.pressure(1,0),.01);assertEquals(interval.graph().reservoirs().get(0).state().temperature(),observed.temperature(),.001);
    }
    @Test void aStoppedConnectedLineStillPublishesPressureWithoutAFlowingTemperature(){
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();var state=model.initialNitrogenCharge(1,300,200000,()->{});
        var graph=new com.wormzjl.icheme.science.fluid.network.PassiveNetwork(List.of(new com.wormzjl.icheme.science.fluid.network.PassiveNetwork.Reservoir(1,0,state),new com.wormzjl.icheme.science.fluid.network.PassiveNetwork.Reservoir(2,0,state)),List.of(new com.wormzjl.icheme.science.fluid.network.PassiveNetwork.Pipe(3,0,1,new com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry(1,.05,.000045,0))));
        var interval=new com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver(model).solve(graph,.1,com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver.Settings.defaults(),()->{});var observed=interval.observations().getFirst();assertFalse(observed.flowing());assertEquals(state.pressure(),observed.pressure(.5,0),.001);
    }
    @Test void allExistingPipeAccessoriesPersistAnInstanceAndRecoverExactlyOnce(){
        for(var type:List.of(Instrument.Type.SIGHT_GLASS,Instrument.Type.SHUTOFF,Instrument.Type.CHECK,Instrument.Type.PRESSURE_VALVE,Instrument.Type.FILTER,Instrument.Type.PUMP,Instrument.Type.COMPRESSOR)){
            var store=new InstrumentService.Store();var fitting=store.installLegacy(7,type);assertEquals(fitting,store.installLegacy(7,type));
            var image=store.save(new CompoundTag());var loaded=InstrumentService.Store.load(image);assertEquals(fitting,loaded.legacy(7));assertNull(loaded.removeLegacy(7,UUID.randomUUID()));assertNotNull(loaded.removeLegacy(7,fitting.instance()));assertNull(loaded.removeLegacy(7,fitting.instance()));assertEquals(2,loaded.revision(7));
            var reinstall=loaded.installLegacy(7,type);assertNotEquals(fitting.instance(),reinstall.instance());assertNull(loaded.removeLegacy(7,fitting.instance()));
            var invalid=image.copy();invalid.getList("fittings",10).getCompound(0).putString("type","TEMPERATURE");assertThrows(IllegalArgumentException.class,()->InstrumentService.Store.load(invalid));
        }
    }
    @Test void configuredAttachmentsAndPendingTransactionsPersistAndRefundOnlyOnce(){
        var store=new InstrumentService.Store();var key=new Instrument.Key(7,"pressure_top");var instance=UUID.randomUUID();var operation=new InstrumentService.Store.Pending(UUID.randomUUID(),UUID.randomUUID(),key,instance,0,true,Instrument.Type.PRESSURE,GaugeSettings.PRESSURE,"minecraft:overworld",BlockPos.ZERO.asLong(),2,false);
        store.nextDue(1234);assertTrue(store.queue(operation));assertFalse(store.queue(operation));var installed=store.install(operation);assertNotNull(installed);assertNull(store.install(operation));store.finish(operation.request());
        var remove=new InstrumentService.Store.Pending(UUID.randomUUID(),operation.player(),key,instance,store.revision(7),false,Instrument.Type.PRESSURE,null,operation.dimension(),operation.position(),2,false);assertTrue(store.queue(remove));
        var image=store.save(new CompoundTag());var reload=InstrumentService.Store.load(image);assertEquals(store.host(7),reload.host(7));assertEquals(store.pending(),reload.pending());assertEquals(1234,reload.nextDue());assertNotNull(reload.remove(key,instance));assertNull(reload.remove(key,instance));assertTrue(reload.host(7).isEmpty());assertEquals(2,reload.revision(7));
        var invalid=image.copy();invalid.putInt("format",AccessoryFacts.SCHEMA+1);assertThrows(IllegalArgumentException.class,()->InstrumentService.Store.load(invalid));
        var malformed=image.copy();malformed.getList("installed",10).getCompound(0).putLong("revision",99);assertThrows(IllegalArgumentException.class,()->InstrumentService.Store.load(malformed));
        // Pending operations are state: one that does not validate drops the state, the installed gauge kept.
        var pending=image.copy();pending.getList("pending",10).getCompound(0).putString("type","UNKNOWN");var dropped=InstrumentService.Store.load(pending);assertTrue(dropped.pending().isEmpty());assertEquals(store.host(7),dropped.host(7));
        var missing=image.copy();missing.remove("pending");var none=InstrumentService.Store.load(missing);assertTrue(none.pending().isEmpty());assertEquals(store.host(7),none.host(7));
    }
    @Test void housingIsClosedAtObliqueAnglesAndEveryConnectorRemainsFinite(){
        // The distance housings (batch 2026-11-09-lod-rendering) are the same envelope with fewer sides and no ticks.
        for(var housing:List.of(InstrumentGeometry.housing(),InstrumentGeometry.housingPlain(),InstrumentGeometry.housingFar())){
            var edges=new HashMap<String,Integer>();
            for(var q:housing){
                if(q.colour()==0xff101517)continue;var v=q.vertices();
                for(int i=0;i<4;i++){int j=(i+1)%4;String x=vertex(v,3*i),y=vertex(v,3*j);if(!x.equals(y))edges.merge(x.compareTo(y)<0?x+"/"+y:y+"/"+x,1,Integer::sum);}
                for(int i=0;i<12;i+=3)assertTrue(Math.hypot(v[i],v[i+1])<=InstrumentGeometry.RADIUS+1e-9,"inside the full housing's radius");
            }
            assertTrue(edges.values().stream().allMatch(n->n==2),"bezel, inner wall, dial, shell and back must share every boundary");
        }
        assertEquals(261,InstrumentGeometry.housing().size(),"near housing unchanged: 48 sides of 5 faces and 21 ticks");
        assertEquals(120,InstrumentGeometry.housingPlain().size());assertEquals(60,InstrumentGeometry.housingFar().size());
        assertTrue(InstrumentGeometry.FACE<InstrumentGeometry.TICKS&&InstrumentGeometry.TICKS<InstrumentGeometry.LABEL&&InstrumentGeometry.LABEL<InstrumentGeometry.NEEDLE&&InstrumentGeometry.NEEDLE<InstrumentGeometry.RIM);
        for(var axis:Direction.values())for(double bore:new double[]{.001,.05,1})for(var m:InstrumentHosts.pipeMounts(BlockPos.ZERO,bore,0,axis))for(boolean temperature:List.of(false,true)){
            var connector=InstrumentGeometry.connection(m.localTap(),m.bottomEntry(),temperature,m.probeDepth());assertTrue(m.probeDepth()<=.12);assertFalse(connector.isEmpty());for(var q:connector)for(double coordinate:q.vertices())assertTrue(Double.isFinite(coordinate));
            if(axis.getAxis()!=Direction.Axis.Y&&bore==1)assertTrue(m.bounds().maxY>1,"full-size pipe gauge can overhang the block");
        }
    }
    private static String vertex(double[] v,int i){return Math.round(v[i]*1e9)+","+Math.round(v[i+1]*1e9)+","+Math.round(v[i+2]*1e9);}
}
