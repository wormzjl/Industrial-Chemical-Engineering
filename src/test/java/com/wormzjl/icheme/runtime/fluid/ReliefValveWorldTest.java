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
        double liquid=n.state().condensedVolume()/n.inventory().volume();
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

    /** One mole of methane on the world's list. */
    private static double[] methane() {
        var ids=new ArrayList<>(EventPathWorld.CATALOG.requirePackage(EventPathWorld.CATALOG.presets().network().packageId()).components());ids.add("Water");
        double[] n=new double[ids.size()];n[ids.indexOf("Methane")]=1;return n;
    }
    private static double[] nitrogen(){return FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition();}
    /** A drum holding {@code gas} at 1 atm, its relief set at {@link #SET}, fed {@code gas} at {@code feed} Pa through a 0.1 m line; returns the drum and the feed. */
    private static long[] plant(EventPathWorld world,double[] gas,double feed) {
        long id=world.ledger.nextIdentity();var node=at(10,64,0);
        var ports=List.of(new PhysicalFluidTopology.Port(node,Direction.WEST,PassiveNetwork.PhasePort.LIQUID),new PhysicalFluidTopology.Port(at(10,66,0),Direction.EAST,PassiveNetwork.PhasePort.VAPOR),
                new PhysicalFluidTopology.Port(at(10,66,0),Direction.UP,PassiveNetwork.PhasePort.VAPOR,PhysicalFluidTopology.ConnectionRole.RELIEF));
        var device=new PhysicalFluidTopology.Device(id,node,Kind.RESERVOIR,Direction.NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),ports);
        var spec=new FluidDeviceSpec(DRUM.capacity(),298.15,101325,gas,SlurryFeed.NONE,DRUM.height(),false,DRUM,null,0).withRelief(SET,null);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,0))),id+1,null);
        world.place(11,66,0,Kind.PIPE,Direction.EAST,.1);world.place(12,66,0,Kind.PIPE,Direction.EAST,.1);
        long source=world.place(13,66,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,298.15,feed,gas),.1);
        return new long[]{id,source};
    }
    /**
     * A flare of tip {@code tip} m at the end of a 0.15 m relief line, open (owner D6: an open tip regulates nothing; the world
     * here plans no flare, so the tip is placed open).
     */
    private static long flare(EventPathWorld world,double tip) {
        world.place(10,67,0,Kind.PIPE,Direction.UP,.15);world.place(10,68,0,Kind.PIPE,Direction.SOUTH,.15);
        long id=world.ledger.nextIdentity();
        var device=new PhysicalFluidTopology.Device(id,at(10,68,1),Kind.VOID,Direction.NORTH,new PipeResistance.Geometry(1,tip,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.flare().withMassTarget(1),
                List.of(new PhysicalFluidTopology.Port(at(10,68,1),Direction.NORTH,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.VESSEL)),true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,null);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,EventPathWorld.nitrogen(),0))),id+1,null);
        return id;
    }
    /**
     * A drum relieving into a flare: its pressure, the relief, the back pressure at the valve's outlet (the drum's less the
     * valve's throttling), the tip's exit velocity on the gas expanded to 1 atm (m/s, the flame's stability measure), the
     * valve's mode and the island's status.
     */
    private record Flared(double pressure,double relief,double back,double velocity,FlowControl.Mode valve,String status) {}
    private static Flared read(EventPathWorld world,long drum,double tip) {
        var s=island(world,drum);int e=relief(s);var gas=node(world,drum).state();var result=s.lastResult().orElseThrow();
        double moles=0;for(double n:com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(gas))moles+=n;
        double density=101325*(gas.mass()/moles)/(8.314462618*gas.temperature()),q=flow(s,e);
        return new Flared(gas.pressure(),q,gas.pressure()-result.endpointHeads()[e],Flare.exitVelocity(q,density,tip),result.endpointModes().get(e),s.status());
    }
    private static Flared flared(double[] gas,double tip) {
        var world=new EventPathWorld();long drum=plant(world,gas,5e5)[0];flare(world,tip);
        world.runTo(1600);world.assertKeptUp("flare "+tip);
        var r=read(world,drum,tip);
        System.out.println("RELIEF_FLARE tip="+tip+" "+r);
        return r;
    }
    /** Methane's stable exit velocity at a flare's tip, m/s (40 CFR 60.18 on its net heating value). */
    private static final double METHANE_STABLE=Flare.stableVelocity(802618/Flare.STANDARD_VOLUME);

    /**
     * Owner D6: "Flare flowrate should vary with backpressure, and increasing backpressure will eventually lead to dysfunctional
     * PSV". Methane and nitrogen drums on a 5 bar feed relieving into flares of 0.1, 0.15 and 0.3 m: the tip passes what the
     * pressure behind it drives. A tip that carries the feed with the valve still throttling leaves the drum at its set
     * pressure; a smaller one builds back pressure until the valve stands wide open and the drum rises above its set pressure.
     */
    @Test void aFlaresFlowFollowsItsBackPressure() {
        for(var name:List.of("methane","nitrogen")) {var gas=name.equals("methane")?methane():nitrogen();
            Flared previous=null;
            for(double tip:new double[]{.3,.15,.1}) {
                var r=flared(gas,tip);
                System.out.println(String.format(Locale.ROOT,"RELIEF_CURVE %s tip=%.2f P=%.0f relief=%.4f back=%.0f velocity=%.1f valve=%s stable=%s",name,tip,r.pressure(),r.relief(),r.back(),r.velocity(),r.valve(),String.format(Locale.ROOT,"%.1f",METHANE_STABLE)));
                assertTrue(r.pressure()>=SET*(1-1e-3),"never below its set pressure: "+r);
                assertTrue(r.back()<=r.pressure()&&(r.valve()==FlowControl.Mode.VALVE_OPEN)==(r.pressure()>SET*1.001),"throttling at its set pressure, wide open above it: "+r);
                if(previous!=null)assertTrue(r.pressure()>=previous.pressure()*(1-1e-4)&&r.back()>=previous.back()*(1-1e-3)||r.pressure()<=SET*1.001,"a smaller tip, more back pressure: "+previous+" then "+r);
                previous=r;
            }
            assertTrue(previous.pressure()>1.05*SET,"the 0.1 m tip holds the drum above its set pressure: "+previous);
        }
        var big=flared(methane(),.3);
        assertEquals(SET,big.pressure(),1e-3*SET,"the 0.3 m tip carries the methane feed at the set pressure");
    }

    /** Owner D6: the feed stepped from 5 to 7 bar at once: the drum follows its new steady state, no hold. */
    @Test void aFeedStepRaisesTheReliefWithoutAHold() {
        var world=new EventPathWorld();long[] p=plant(world,methane(),5e5);flare(world,.15);
        world.runTo(1200);var before=read(world,p[0],.15);
        charge(world,p[1],7e5);world.runTo(2400);world.assertKeptUp("feed step");
        var after=read(world,p[0],.15);
        System.out.println("RELIEF_STEP before="+before+" after="+after);
        assertTrue(after.relief()>before.relief(),"more relieved: "+before+" then "+after);
        assertTrue(after.pressure()>=before.pressure()*(1-1e-4),"no lower: "+before+" then "+after);
    }

    /** Owner D6: the feed ramped from 5 to 7 bar and back by 0.05 bar every 50 ticks: no hold, and the drum returns to where it stood. */
    @Test void aFeedRampUpAndDownReturnsTheDrumWhereItStood() {
        var world=new EventPathWorld();long[] p=plant(world,methane(),5e5);flare(world,.15);
        world.runTo(1200);var before=read(world,p[0],.15);Flared top=null;
        for(long t=1200;t<=6000;t+=50) {
            if(t<=3200)charge(world,p[1],5e5+(t-1200)/50*5e3);else if(t>=4000)charge(world,p[1],7e5-(t-4000)/50*5e3);
            world.runTo(t+50);if(t==3500)top=read(world,p[0],.15);
        }
        world.runTo(7200);world.assertKeptUp("feed ramp");
        var after=read(world,p[0],.15);
        System.out.println("RELIEF_RAMP before="+before+" top="+top+" after="+after);
        assertTrue(top.relief()>before.relief(),"more relieved at 7 bar: "+top);
        assertEquals(before.pressure(),after.pressure(),2e-3*before.pressure(),"back where it stood");
        assertEquals(before.relief(),after.relief(),.01*before.relief(),"relieving the feed again");
    }
    /**
     * A drum on its 5 bar nitrogen feed relieving into a device that regulates the flow itself (batch 2026-11-11-valve-followups,
     * item 3): the run of two regulators. Its 0.15 m relief line runs up and south from the drum's relief port; {@code sink}
     * places what it ends at. Runs 2400 ticks; returns the drum's pressure, the relief and the island's first hold or status.
     */
    private record Sunk(double pressure,double relief,String held) {}
    private static Sunk sunk(String label,java.util.function.Consumer<EventPathWorld> sink) {
        var world=new EventPathWorld();
        long drum=drum(world,0,SET);gasFeed(world,0,5e5);
        world.place(10,67,0,Kind.PIPE,Direction.UP,.15);world.place(10,68,0,Kind.PIPE,Direction.SOUTH,.15);
        sink.accept(world);
        world.runTo(2400);
        var s=island(world,drum);var n=node(world,drum);double relief=0;
        for(int e=0;e<s.graph().pipes().size();e++){var p=s.graph().pipes().get(e);if(s.graph().reservoirs().get(p.first()).id()==drum&&p.control() instanceof FlowControl.PressureValve)relief=flow(s,e);}
        var r=new Sunk(n.state().pressure(),relief,world.held.isEmpty()?"clean "+s.status():world.held.getFirst().status());
        System.out.println("RELIEF_SINK "+label+" "+r);
        for(var h:world.held)System.out.println("RELIEF_SINK_HELD "+label+" "+h);
        return r;
    }
    /** A sink valve of the run's end at (10,68,1), as a firebox's burners or a prime mover's fuel void: {@code control} its valve. */
    private static void valvedSink(EventPathWorld world,FlowControl control,HeatUnit heat) {
        long id=world.ledger.nextIdentity();
        var device=new PhysicalFluidTopology.Device(id,at(10,68,1),Kind.VOID,Direction.NORTH,new PipeResistance.Geometry(1,.15,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),control,
                List.of(new PhysicalFluidTopology.Port(at(10,68,1),Direction.NORTH,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.VESSEL)),true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,heat);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,EventPathWorld.nitrogen(),0))),id+1,null);
    }
    /** A second drum blanketed at 150 kPa, its BLANKET port the run's end, its own gas drawn off by a flow valve at {@code draw} kg/s through a line of {@code bore} m to a void. */
    private static void blanketedSink(EventPathWorld world,double draw,double bore) {
        world.place(10,68,1,Kind.PIPE,Direction.SOUTH,.15);world.place(10,68,2,Kind.PIPE,Direction.SOUTH,.15);world.place(10,67,2,Kind.PIPE,Direction.DOWN,.15);
        long id=world.ledger.nextIdentity();var node=at(10,64,2);
        var ports=List.of(new PhysicalFluidTopology.Port(at(10,66,2),Direction.UP,PassiveNetwork.PhasePort.VAPOR,PhysicalFluidTopology.ConnectionRole.BLANKET),
                new PhysicalFluidTopology.Port(at(10,66,2),Direction.EAST,PassiveNetwork.PhasePort.VAPOR));
        var device=new PhysicalFluidTopology.Device(id,node,Kind.RESERVOIR,Direction.NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),ports);
        var spec=new FluidDeviceSpec(DRUM.capacity(),298.15,101325,nitrogen(),SlurryFeed.NONE,DRUM.height(),false,DRUM,null,1.5e5);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,0))),id+1,null);
        world.place(11,66,2,Kind.PIPE,Direction.EAST,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,draw,new UUID(7,1),1),null,bore);
        world.place(12,66,2,Kind.PIPE,Direction.EAST,bore);world.place(13,66,2,Kind.VOID,Direction.EAST,EventPathWorld.nitrogen(),bore);
    }
    /** A set-flow compressor at (10,68,1) drawing {@code volumeFlow} m3/s from the run, out to a void at the ambient. */
    private static void compressorSink(EventPathWorld world,double volumeFlow) {
        world.place(10,68,1,Kind.COMPRESSOR,Direction.SOUTH,new FlowControl.Compressor(volumeFlow,3.0),null,.15);
        world.place(10,68,2,Kind.PIPE,Direction.SOUTH,.15);world.place(10,68,3,Kind.VOID,Direction.SOUTH,EventPathWorld.nitrogen(),.15);
    }

    /**
     * Item 3 of the valve follow-ups: a relief run into a firebox's fuel valve, a blanketed vessel's {@code BLANKET} port or a
     * set-flow compressor's suction (two regulators around a junction) runs without a hold, and the lower flow wins (owner
     * decision of 2026-11-02): a sink that takes less than the 1.11 kg/s feed sets the flow, the safety valve stands wide open
     * and the drum rises toward its feed; one that takes more leaves the safety valve holding the drum at its set pressure.
     */
    @Test void aReliefRunIntoASecondRegulatorRunsWithoutAHold() {
        var firebox=new HeatUnit(com.wormzjl.icheme.science.equipment.HeatSides.Unit.FIRED_HEATER,com.wormzjl.icheme.science.equipment.HeatSides.Side.BURNER,100,999);
        var low=new LinkedHashMap<String,Sunk>();var high=new LinkedHashMap<String,Sunk>();
        low.put("burners 0.5 kg/s",sunk("burners 0.5",w->valvedSink(w,FlowControl.ControlValve.burner().withMassTarget(.5),firebox)));
        high.put("burners 3 kg/s",sunk("burners 3",w->valvedSink(w,FlowControl.ControlValve.burner().withMassTarget(3),firebox)));
        low.put("blanketed drum drawn 0.3 kg/s",sunk("blanket 0.3",w->blanketedSink(w,.3,.05)));
        high.put("blanketed drum drawn 3 kg/s",sunk("blanket 3",w->blanketedSink(w,3,.15)));
        low.put("compressor 0.05 m3/s",sunk("compressor 0.05",w->compressorSink(w,.05)));
        high.put("compressor 1 m3/s",sunk("compressor 1",w->compressorSink(w,1)));
        for(var e:low.entrySet()) {
            var r=e.getValue();
            assertTrue(r.held().startsWith("clean"),e.getKey()+": "+r);
            assertTrue(r.pressure()>1.5*SET&&r.pressure()<5e5,e.getKey()+": the sink sets the flow, the drum rises toward its feed: "+r);
            assertTrue(r.relief()>.2&&r.relief()<.6,e.getKey()+": what the sink takes: "+r);
        }
        assertEquals(.5,low.get("burners 0.5 kg/s").relief(),1e-3,"the burners' fuel valve passes its target");
        for(var e:high.entrySet()) {
            var r=e.getValue();
            assertTrue(r.held().startsWith("clean"),e.getKey()+": "+r);
            assertEquals(SET,r.pressure(),.02*SET,e.getKey()+": the safety valve holds the drum at its set pressure: "+r);
            assertEquals(1.11,r.relief(),.05,e.getKey()+": relieving its feed: "+r);
        }
    }

    /**
     * Item 4 of the valve follow-ups (owner X5: a flare's tip blocks while solids arrive): a relief run straight into a flare is
     * one line with no tip valve of its own (owner D6). With solids at the drum, the flare's plan blocks it: the line closes
     * both ways, the flare reads BLOCKED, and the drum rises above its set pressure; clean again, the line opens.
     */
    @Test void aReliefRunIntoAFlareBlocksWhileSolidsArrive() {
        var world=new EventPathWorld();long drum=plant(world,methane(),5e5)[0];long flare=flare(world,.3);
        world.runTo(1200);world.assertKeptUp("flare relief");
        var s=island(world,drum);var clean=world.worldGraph(s);var model=world.model;
        var flares=new Flares(world.ledger,FluidWorldAuthority.heatingValues(world.catalog,model),model.molecularWeights(),FluidWorldAuthority.formulas(world.catalog,model));
        int line=-1,at=-1;
        for(int e=0;e<clean.pipes().size();e++)if(clean.pipes().get(e).control() instanceof FlowControl.PressureValve)line=e;
        for(int n=0;n<clean.reservoirs().size();n++)if(clean.reservoirs().get(n).id()==drum)at=n;
        assertEquals(flare,clean.reservoirs().get(clean.pipes().get(line).second()).id(),"one line from the drum into the flare");
        assertEquals(0,clean.pipes().get(line).forbiddenDirections(),"the compile leaves its fitting mask free");
        var plans=new HashMap<Long,ShaftPlan>();flares.plans(clean,plans);
        assertEquals(new ShaftPlan(ShaftPlan.Kind.FLARE,1),plans.get(flare),"clean: the tip is open");
        assertSame(clean,ShaftPlan.apply(clean,plans),"and the line stays as compiled");
        var solids=new com.wormzjl.icheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.icheme.science.fluid.state.SolidInventory.Population(
                world.catalog.solids().require("icheme:demo_particle"),com.wormzjl.icheme.science.fluid.state.ParticleSize.micrometres("100"),.01)));
        int drumAt=at;
        java.util.function.UnaryOperator<PassiveNetwork> dusty=g->{var nodes=new ArrayList<>(g.reservoirs());var node=nodes.get(drumAt);
            nodes.set(drumAt,new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state().withSolids(solids),node.kind(),node.inventory()));
            return new PassiveNetwork(nodes,g.pipes(),g.scheduledTransfers());};
        plans.clear();flares.plans(dusty.apply(clean),plans);
        assertEquals(new ShaftPlan(ShaftPlan.Kind.FLARE,0),plans.get(flare),"solids arrive: the tip blocks");
        var blocked=ShaftPlan.apply(clean,plans);
        assertEquals(3,blocked.pipes().get(line).forbiddenDirections(),"the relief line closes both ways");
        var r=new PassiveIntervalSolver(model).solve(blocked,20,PassiveIntervalSolver.Settings.defaults(),()->{});var result=r;
        flares.booked(0,400,new PassiveIntervalSolver.Result(dusty.apply(r.graph()),r.advancedSeconds(),r.averageMassFlows(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.pumpWorkJoule(),
                r.boundaries(),r.rejectionReasons(),r.endpointModes(),r.endpointHeads(),r.endpointFlows(),r.pipeTransfers(),r.observations(),r.towers()));
        assertEquals(Flares.Status.BLOCKED,flares.view(flare).status(),"the flare reads BLOCKED");
        double p0=clean.reservoirs().get(at).state().pressure(),p1=result.graph().reservoirs().get(at).state().pressure();
        System.out.println("RELIEF_FLARE_X5 relief="+result.averageMassFlows()[line]+" P="+p0+" -> "+p1+" status="+flares.view(flare).status());
        assertEquals(0,result.averageMassFlows()[line],1e-12,"nothing reaches the tip");
        assertTrue(p1>p0+1e3,"the drum rises above its set pressure: "+p0+" to "+p1);
        plans.clear();flares.plans(result.graph(),plans);
        assertEquals(0,ShaftPlan.apply(result.graph(),plans).pipes().get(line).forbiddenDirections(),"clean again: the line opens");
    }

    /** The generator {@code id} charged at {@code pressure}, as a queued edit. */
    private static void charge(EventPathWorld world,long id,double pressure) {
        var record=world.registry.registrations().get(id);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(record.device(),record.spec().withCharge(record.spec().temperature(),pressure,record.spec().composition()),record.revision()+1))),world.ledger.nextIdentity(),null);
    }
}
