package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A pressure safety valve in a world (batch 2026-11-11-gas-blanketing, PRESSURE_SAFETY_VALVE_PLAN.md section 3): the run
 * out of a drum's {@code RELIEF} port, or out of a pipe's outlet face, carries the upstream pressure valve law ({@link
 * FlowControl.PressureValve}) at the valve's set pressure; with nothing piped to the outlet, a stub to a void at the ambient
 * does. A 2.6 m3 drum set at 300 kPa: blocked in on a 5 bar nitrogen feed it stands at its set pressure and passes its feed;
 * on a 6 bar water feed it fills and relieves liquid; piped to a second drum it fills that drum; on a 2 bar feed it rests
 * below its set pressure. A valve on a pipe holds the pipe's junction at its set pressure.
 */
class ReliefValveWorldTest {
    private static final double SET=3e5;
    private static final VesselGeometry DRUM=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,1.2,2.4,0,0);
    private static Position at(int x,int y,int z){return new Position(EventPathWorld.DIM,x,y,z);}

    /** A drum at node (10,64,0): a liquid port west at its node, a vapour port east at (10,66,0), its relief port up there when {@code relief}. */
    private static long drum(EventPathWorld world,int z,double relief) {
        long id=world.ledger.nextIdentity();var node=at(10,64,z);
        var ports=new ArrayList<PhysicalFluidTopology.Port>();
        ports.add(new PhysicalFluidTopology.Port(node,Direction.WEST,PassiveNetwork.PhasePort.LIQUID));
        ports.add(new PhysicalFluidTopology.Port(at(10,66,z),Direction.EAST,PassiveNetwork.PhasePort.VAPOR));
        if(relief>0)ports.add(new PhysicalFluidTopology.Port(at(10,66,z),Direction.UP,PassiveNetwork.PhasePort.VAPOR,PhysicalFluidTopology.ConnectionRole.RELIEF));
        var device=new PhysicalFluidTopology.Device(id,node,Kind.RESERVOIR,Direction.NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),ports);
        var spec=new FluidDeviceSpec(DRUM.capacity(),298.15,101325,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition(),SlurryFeed.NONE,DRUM.height(),false,DRUM,null,0).withRelief(relief,null);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,0))),id+1,null);
        return id;
    }
    /** A gas feed at {@code pressure} into the drum's vapour port: two pipes and a nitrogen generator east of it. */
    private static void gasFeed(EventPathWorld world,int z,double pressure) {
        world.place(11,66,z,Kind.PIPE,Direction.EAST);world.place(12,66,z,Kind.PIPE,Direction.EAST);
        world.place(13,66,z,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,298.15,pressure,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition()),.05);
    }
    private static IslandCoordinator.Snapshot island(EventPathWorld world,long id) {
        return world.coordinator.snapshots().stream().filter(s->s.graph().reservoirs().stream().anyMatch(r->r.id()==id)).findFirst().orElseThrow();
    }
    private static PassiveNetwork.Reservoir node(EventPathWorld world,long id) {
        for(var n:island(world,id).graph().reservoirs())if(n.id()==id)return n;
        throw new AssertionError("no node "+id);
    }
    /** The relief connection of the island holding {@code id}, its index. */
    private static int relief(IslandCoordinator.Snapshot s) {
        var pipes=s.graph().pipes();for(int e=0;e<pipes.size();e++)if(pipes.get(e).control() instanceof FlowControl.PressureValve)return e;
        throw new AssertionError("no relief connection");
    }
    private static double flow(IslandCoordinator.Snapshot s,int pipe){return s.lastResult().orElseThrow().averageMassFlows()[pipe];}

    @Test void aBlockedInDrumOnAGasFeedStandsAtItsSetPressure() {
        var world=new EventPathWorld();
        long drum=drum(world,0,SET);gasFeed(world,0,5e5);
        world.runTo(1600);
        world.assertKeptUp("relieving drum");
        var s=island(world,drum);var n=node(world,drum);int e=relief(s);var pipe=s.graph().pipes().get(e);
        var sink=s.graph().reservoirs().get(pipe.second());
        double feed=0;for(int k=0;k<s.graph().pipes().size();k++)if(k!=e)feed=Math.abs(flow(s,k));
        System.out.println("RELIEF_WORLD gas: P="+n.state().pressure()+" relief="+flow(s,e)+" kg/s feed="+feed+" status="+s.status());
        assertEquals(SET,((FlowControl.PressureValve)pipe.control()).targetPressure(),0,"the stub carries the valve at its set pressure");
        assertEquals(PassiveNetwork.NodeKind.VOID,sink.kind(),"an open outlet relieves to a void");
        assertTrue(sink.id()<0,"the void is the valve's own, no device");
        assertEquals(PhysicalFluidTopology.reliefIdentity(drum),pipe.id());
        assertEquals(Direction.UP,world.registry.tankFaces(drum).get(pipe.id()),"the drum's view names the relief connection by its face");
        assertEquals(SET,n.state().pressure(),.02*SET,"at its set pressure");
        assertTrue(flow(s,e)>.1,"it relieves: "+flow(s,e));
        assertEquals(feed,flow(s,e),.02*feed,"what it is fed");
    }

    @Test void aLiquidFullDrumRelievesLiquid() {
        var world=new EventPathWorld();
        long drum=drum(world,0,SET);
        world.place(9,64,0,Kind.PIPE,Direction.WEST);world.place(8,64,0,Kind.PIPE,Direction.WEST);
        world.place(7,64,0,Kind.GENERATOR,Direction.WEST,FluidDeviceSpec.water(EventPathWorld.CATALOG).withCharge(298.15,6e5,FluidDeviceSpec.water(EventPathWorld.CATALOG).composition()),.05);
        world.runTo(4000);
        world.assertKeptUp("liquid-full relief");
        var s=island(world,drum);var n=node(world,drum);int e=relief(s);
        double liquid=(n.state().liquidVolume()+n.state().waterVolume())/n.inventory().volume();
        System.out.println("RELIEF_WORLD liquid: P="+n.state().pressure()+" level="+liquid+" relief="+flow(s,e)+" kg/s status="+s.status());
        assertTrue(liquid>.97,"it fills with water: "+liquid);
        assertTrue(flow(s,e)>1,"and relieves the feed as liquid: "+flow(s,e));
        assertTrue(n.state().pressure()>=.98*SET&&n.state().pressure()<6e5,"between its set pressure and its feed's: "+n.state().pressure());
    }

    @Test void aDrumPipedToASecondDrumFillsIt() {
        var world=new EventPathWorld();
        long drum=drum(world,0,SET);gasFeed(world,0,5e5);
        // The relief port up into a discharge line to a second drum's vapour port.
        // A 0.15 m line: a 0.05 m one carries about 0.3 kg/s at the 100 m/s cap, and the drum would rise over its set pressure (built-up back pressure).
        for(int[] c:new int[][]{{67,0},{68,0},{68,1},{68,2},{67,2}})world.place(10,c[0],c[1],Kind.PIPE,Direction.UP,.15);
        long second=world.ledger.nextIdentity();
        var ports=List.of(new PhysicalFluidTopology.Port(at(10,66,2),Direction.UP,PassiveNetwork.PhasePort.VAPOR));
        var big=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,0,0);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(second,new WorldTopologyLedger.Registration(new PhysicalFluidTopology.Device(second,at(10,64,2),Kind.RESERVOIR,Direction.NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),ports),
                new FluidDeviceSpec(big.capacity(),298.15,101325,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition(),SlurryFeed.NONE,big.height(),false,big,null),0))),second+1,null);
        world.runTo(800);
        world.assertKeptUp("piped relief");
        var s=island(world,drum);int e=relief(s);var pipe=s.graph().pipes().get(e);
        var n=node(world,drum);var m=node(world,second);
        System.out.println("RELIEF_WORLD piped: P="+n.state().pressure()+" second P="+m.state().pressure()+" m="+m.state().mass()+" relief="+flow(s,e)+" status="+s.status());
        assertTrue(s.graph().reservoirs().stream().noneMatch(r->r.kind()==PassiveNetwork.NodeKind.VOID),"no atmosphere: the outlet is piped");
        assertTrue(s.graph().reservoirs().stream().anyMatch(r->r.id()==second),"one island with the drum it relieves into");
        assertEquals(drum,s.graph().reservoirs().get(pipe.first()).id(),"stated from the drum it guards");
        assertEquals(SET,n.state().pressure(),.02*SET,"the guarded drum stands at its set pressure");
        assertTrue(m.state().pressure()>1.2e5&&m.state().pressure()<SET,"the second drum fills below it: "+m.state().pressure());
        assertEquals(1.11,flow(s,e),.05,"with what the first is fed");
    }

    @Test void aDrumBelowItsSetPressureRests() {
        var world=new EventPathWorld();
        long drum=drum(world,0,SET);gasFeed(world,0,2e5);
        world.runTo(1600);
        world.assertKeptUp("drum below its set pressure");
        var s=island(world,drum);var n=node(world,drum);int e=relief(s);
        System.out.println("RELIEF_WORLD rest: P="+n.state().pressure()+" relief="+flow(s,e)+" status="+s.status());
        assertEquals(2e5,n.state().pressure(),.01*2e5,"at its feed's pressure");
        assertEquals(0,flow(s,e),1e-9,"the valve stays shut");
        assertTrue(s.status().startsWith("STEADY: no flow"),"and the island rests: "+s.status());
    }

    @Test void aValveOnAPipeHoldsItsJunctionAtItsSetPressure() {
        var world=new EventPathWorld();
        long drum=drum(world,0,0);
        world.place(11,66,0,Kind.PIPE,Direction.EAST,EventPathWorld.nitrogen().withRelief(SET,Direction.UP),.05);
        world.place(12,66,0,Kind.PIPE,Direction.EAST);
        world.place(13,66,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,298.15,5e5,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition()),.05);
        world.runTo(1600);
        world.assertKeptUp("pipe relief");
        var s=island(world,drum);var n=node(world,drum);int e=relief(s);var pipe=s.graph().pipes().get(e);
        System.out.println("RELIEF_WORLD pipe: drum P="+n.state().pressure()+" junction P="+s.graph().reservoirs().get(pipe.first()).state().pressure()+" relief="+flow(s,e)+" status="+s.status());
        assertEquals(PassiveNetwork.NodeKind.JUNCTION,s.graph().reservoirs().get(pipe.first()).kind(),"the valve reads the pipe's junction");
        assertEquals(PassiveNetwork.NodeKind.VOID,s.graph().reservoirs().get(pipe.second()).kind(),"and relieves to the atmosphere");
        assertEquals(SET,n.state().pressure(),.03*SET,"the drum behind it stands at its set pressure");
        assertTrue(flow(s,e)>.1,"it passes the feed: "+flow(s,e));
    }

    /**
     * Ten drums on one relief header (plan decision D5, the joined island's cost): each drum blocked in on its own 5 bar
     * nitrogen feed, its relief port up a 0.15 m riser into one 0.4 m header open to a void at the ambient. One island; every
     * drum stands at its set pressure and the header carries the ten feeds. Prints the island's substeps per commit and the
     * wall time of the run (one run; counters decide, the wall time is a note).
     */
    @Test void tenDrumsOnOneReliefHeaderEachStandAtTheirSetPressure() {
        var world=new EventPathWorld();
        var drums=new long[10];
        for(int i=0;i<10;i++){int z=3*i;drums[i]=drum(world,z,SET);gasFeed(world,z,5e5);world.place(10,67,z,Kind.PIPE,Direction.UP,.15);}
        for(int z=0;z<=28;z++)world.place(10,68,z,Kind.PIPE,Direction.SOUTH,.4);
        world.place(10,68,29,Kind.VOID,Direction.SOUTH,EventPathWorld.nitrogen(),.4);
        long commits=0,accepted=0,rejected=0,last=-1;long started=System.nanoTime();
        while(world.ledger.onlineTick()<1600) {
            world.tick();
            for(var s:world.coordinator.snapshots())if(s.graph().reservoirs().stream().anyMatch(r->r.id()==drums[0])&&s.clock().committedTick()!=last&&s.lastResult().isPresent()){
                last=s.clock().committedTick();commits++;accepted+=s.lastResult().get().acceptedSubsteps();rejected+=s.lastResult().get().rejectedSubsteps();}
        }
        long ms=(System.nanoTime()-started)/1_000_000;
        world.assertKeptUp("relief header");
        var s=island(world,drums[0]);double relieved=0;
        for(int e=0;e<s.graph().pipes().size();e++)if(s.graph().pipes().get(e).control() instanceof FlowControl.PressureValve)relieved+=flow(s,e);
        double low=Double.MAX_VALUE,high=0;
        for(long d:drums){double p=node(world,d).state().pressure();low=Math.min(low,p);high=Math.max(high,p);}
        System.out.println("RELIEF_HEADER drums=10 islands="+world.coordinator.snapshots().size()+" commits="+commits+" acceptedSubsteps="+accepted+" rejectedSubsteps="+rejected
                +" perCommit="+String.format(Locale.ROOT,"%.1f",(accepted+rejected)/(double)Math.max(1,commits))+" ms="+ms+" P="+low+".."+high+" relieved="+relieved+" status="+s.status());
        for(long d:drums)assertEquals(s.id(),island(world,d).id(),"one island");
        assertEquals(SET,low,.02*SET,"every drum at its set pressure");
        assertEquals(SET,high,.02*SET,"every drum at its set pressure");
        assertEquals(11.1,relieved,.5,"the header carries the ten feeds");
    }

    /** A drum relieving through a 0.15 m line into a flare's tip whose limit the engine planned at {@code limit} kg/s (the world here plans none). */
    private static long flare(EventPathWorld world,double limit) {
        world.place(10,67,0,Kind.PIPE,Direction.UP,.15);world.place(10,68,0,Kind.PIPE,Direction.SOUTH,.15);
        long id=world.ledger.nextIdentity();
        var device=new PhysicalFluidTopology.Device(id,at(10,68,1),Kind.VOID,Direction.NORTH,new PipeResistance.Geometry(1,.3,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.flare().withMassTarget(limit),
                List.of(new PhysicalFluidTopology.Port(at(10,68,1),Direction.NORTH,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.VESSEL)),true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,null);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,EventPathWorld.nitrogen(),0))),id+1,null);
        return id;
    }
    /** Whether the island's safety valve before a flow regulator stands lifted. */
    private static boolean lifted(IslandCoordinator.Snapshot s) {
        for(var p:s.graph().pipes())if(p.control() instanceof FlowControl.PressureValve v&&v.planned())return v.lifted();
        throw new AssertionError("no planned safety valve");
    }

    /**
     * Decision D6 (form A2): a drum on a 5 bar feed (1.11 kg/s at its set pressure) relieving into a flare's tip of limit
     * {@code limit}, run for {@code ticks}. Returns the drum's pressure, the relief, whether the valve stands lifted, and
     * how often it lifted and reseated, read at every tick.
     */
    private record Flared(double pressure,double relief,boolean lifted,int lifts,int seats,String status) {}
    private static Flared flared(double limit,long ticks,java.util.function.LongConsumer at,EventPathWorld world,long drum) {
        boolean was=false;int lifts=0,seats=0;
        while(world.ledger.onlineTick()<ticks) {
            world.tick();at.accept(world.ledger.onlineTick());
            var s=world.coordinator.snapshots().stream().filter(x->x.graph().reservoirs().stream().anyMatch(r->r.id()==drum)).findFirst();
            if(s.isEmpty()||s.get().lastResult().isEmpty())continue;
            boolean now=lifted(s.get());if(now&&!was)lifts++;if(!now&&was)seats++;was=now;
        }
        world.assertKeptUp("flare limit "+limit);
        var s=island(world,drum);int e=relief(s);
        var out=new Flared(node(world,drum).state().pressure(),flow(s,e),lifted(s),lifts,seats,s.status());
        System.out.println("RELIEF_FLARE limit="+limit+" "+out);
        return out;
    }
    private static Flared flared(double limit) {
        var world=new EventPathWorld();long drum=drum(world,0,SET);gasFeed(world,0,5e5);flare(world,limit);
        return flared(limit,1600,t->{},world,drum);
    }

    /** A tip that passes less than the load: the valve lifts once; back pressure holds the drum above its set pressure and the tip passes its limit. */
    @Test void aFlareThatPassesLessThanTheLoadBacksTheDrumUpAboveItsSetPressure() {
        var r=flared(.3);
        assertTrue(r.lifted()&&r.lifts()==1&&r.seats()==0,"lifted once: "+r);
        assertTrue(r.pressure()>1.1*SET,"above its set pressure by the back pressure: "+r.pressure());
        assertEquals(.3,r.relief(),.01,"the tip passes its limit");
    }

    /** A tip that passes the load: the valve regulates, as before D6. */
    @Test void aFlareThatPassesTheLoadHoldsTheDrumAtItsSetPressure() {
        var r=flared(2);
        assertTrue(!r.lifted()&&r.lifts()==0,"never lifted: "+r);
        assertEquals(SET,r.pressure(),1e-3*SET,"at its set pressure");
        assertEquals(1.11,r.relief(),.01,"it passes the feed");
    }

    /** A load just under the tip's limit (95 %, outside the lift's margin) regulates; just over (103 %) lifts once and stands a little above the set pressure. */
    @Test void aLoadJustUnderOrJustOverTheTipsLimit() {
        var under=flared(1.11/.95);
        assertTrue(!under.lifted()&&under.lifts()==0,"under: never lifted: "+under);
        assertEquals(SET,under.pressure(),1e-3*SET,"under: at its set pressure");
        var over=flared(1.11/1.03);
        assertTrue(over.lifted()&&over.lifts()==1&&over.seats()==0,"over: lifted once: "+over);
        assertTrue(over.pressure()>SET,"over: above its set pressure: "+over.pressure());
        assertEquals(1.11/1.03,over.relief(),.01,"over: the tip passes its limit");
    }

    /** A load that crosses the tip's limit upward (the feed to 7 bar) and back down (to 5 bar): the valve lifts once and reseats once, then regulates at its set pressure. */
    @Test void aLoadCrossingTheTipsLimitLiftsOnceAndReseatsOnce() {
        var world=new EventPathWorld();long drum=drum(world,0,SET);
        world.place(11,66,0,Kind.PIPE,Direction.EAST);world.place(12,66,0,Kind.PIPE,Direction.EAST);
        long feed=world.place(13,66,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,298.15,5e5,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition()),.05);
        flare(world,1.4);
        // The feed ramps by 0.05 bar every 50 ticks: up from 5 to 7 bar from tick 1200, down again from tick 4000.
        var r=flared(1.4,7000,t->{if(t>=1200&&t<=3200&&(t-1200)%50==0)charge(world,feed,5e5+(t-1200)/50*5e3);if(t>=4000&&t<=6000&&(t-4000)%50==0)charge(world,feed,7e5-(t-4000)/50*5e3);},world,drum);
        assertTrue(r.lifts()==1&&r.seats()==1&&!r.lifted(),"lifted once, reseated once: "+r);
        assertEquals(SET,r.pressure(),1e-3*SET,"back at its set pressure");
        assertEquals(1.11,r.relief(),.01,"passing the feed");
    }
    /** The generator {@code id} charged at {@code pressure}, as a queued edit. */
    private static void charge(EventPathWorld world,long id,double pressure) {
        var record=world.registry.registrations().get(id);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(record.device(),record.spec().withCharge(record.spec().temperature(),pressure,record.spec().composition()),record.revision()+1))),world.ledger.nextIdentity(),null);
    }
}
