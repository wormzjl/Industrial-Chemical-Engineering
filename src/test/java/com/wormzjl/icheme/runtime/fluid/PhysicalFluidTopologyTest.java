package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

class PhysicalFluidTopologyTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private PhysicalFluidTopology.Device device(long id,int x,int z,Kind kind,PhysicalFluidTopology.Direction facing) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,0,z),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),facing,new PipeResistance.Geometry(1,.05,.000045,0),
                kind==Kind.PUMP?new FlowControl.Pump(.00001,500000):kind==Kind.COMPRESSOR?new FlowControl.Compressor(.00001,3):kind==Kind.VALVE?new FlowControl.PressureValve(150000):new FlowControl.Passive());
    }
    private PassiveNetwork.Reservoir reservoir(long id){return new PassiveNetwork.Reservoir(id,0,model.initialNitrogenCharge(1,298.15,101325,()->{}));}
    @Test void valveCharacteristicKeepsTheRegisteredBoreAcrossNarrowerRuns(){
        var valve=device(3,2,0,Kind.VALVE,EAST);
        valve=new PhysicalFluidTopology.Device(valve.id(),valve.position(),valve.kind(),valve.facing(),new PipeResistance.Geometry(1,.08,.000045,0),valve.control());
        var compiled=PhysicalFluidTopology.compile(List.of(device(1,0,0,Kind.RESERVOIR,EAST),device(2,1,0,Kind.PIPE,EAST),valve,device(4,3,0,Kind.PIPE,EAST),device(5,4,0,Kind.RESERVOIR,EAST)),Map.of(1L,reservoir(1),5L,reservoir(5)));
        var pipe=compiled.islands().getFirst().graph().pipes().stream().filter(p->p.control() instanceof FlowControl.Valve).findFirst().orElseThrow();
        assertEquals(Math.PI*.08*.08/4,pipe.controlArea());assertTrue(pipe.controlArea()>pipe.minimumArea());
        assertEquals(pipe.controlArea(),pipe.withPorts(pipe.firstPort(),pipe.secondPort()).withBlockedDirections(0).withObservationPoints(List.of()).controlArea());
    }
    @Test void tenPhysicalPipeBlocksCompileToOneTenMetreRunWithEveryDebugMapping() {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();devices.add(device(1,0,0,Kind.RESERVOIR,PhysicalFluidTopology.Direction.EAST));
        for(int x=1;x<=10;x++)devices.add(device(x+1,x,0,Kind.PIPE,PhysicalFluidTopology.Direction.EAST));devices.add(device(12,11,0,Kind.RESERVOIR,PhysicalFluidTopology.Direction.EAST));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1),12L,reservoir(12)));
        assertEquals(1,compiled.islands().size());var graph=compiled.islands().getFirst().graph();assertEquals(2,graph.reservoirs().size());assertEquals(1,graph.pipes().size());
        var pipe=graph.pipes().getFirst();assertEquals(10,pipe.sections().stream().mapToDouble(PipeResistance.Geometry::length).sum());assertEquals(10,compiled.pipeViews().size());
        for(var views:compiled.pipeViews().values()){assertEquals(1,views.size());assertEquals(pipe.id(),views.getFirst().pipeId());}
        assertEquals(PipeResistance.evaluate(new PipeResistance.Geometry(10,.05,.000045,0),.1,1000,.001).pressureDrop(),pipe.loss(.1,1000,.001).pressureDrop(),1e-12);
    }
    @Test void observationalTapsSelectTheCorrectHalfBetweenOpposingActuators(){
        var devices=List.of(device(1,0,0,Kind.RESERVOIR,EAST),device(2,1,0,Kind.PIPE,EAST),device(3,2,0,Kind.COMPRESSOR,EAST),device(4,3,0,Kind.PIPE,EAST),device(5,4,0,Kind.PIPE,EAST),device(6,5,0,Kind.PIPE,EAST),device(7,6,0,Kind.COMPRESSOR,WEST),device(8,7,0,Kind.PIPE,EAST),device(9,8,0,Kind.RESERVOIR,EAST),device(10,0,-1,Kind.PIPE,NORTH),device(11,20,0,Kind.PIPE,EAST));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1),9L,reservoir(9)));var graph=compiled.islands().getFirst().graph();
        for(long id:List.of(4L,5L,6L)){var views=compiled.pipeViews().get(id);assertEquals(2,views.size());var eligible=views.stream().filter(v->Double.isFinite(v.tap())).toList();assertEquals(id==5?2:1,eligible.size());for(var view:eligible){assertTrue(view.tap()>=0&&view.tap()<=1);var pipe=graph.pipes().stream().filter(p->p.id()==view.pipeId()).findFirst().orElseThrow();if(id!=5)assertEquals(id==4?3:7,graph.reservoirs().get(pipe.first()).id());}}
        assertFalse(compiled.pipeViews().containsKey(10L));assertFalse(compiled.pipeViews().containsKey(11L));
    }
    @Test void pipeViewsDescribeGraphForwardDirectionOnEveryWorldAxisIndependentOfIdentityOrder(){
        for(var direction:PhysicalFluidTopology.Direction.values())for(boolean reversed:new boolean[]{false,true}){
            long source=reversed?2:1,sink=reversed?1:2;
            var devices=List.of(at(source,0,0,0,Kind.GENERATOR,NORTH),at(100,direction.x,direction.y,direction.z,Kind.PIPE,NORTH),
                    at(sink,2*direction.x,2*direction.y,2*direction.z,Kind.VOID,NORTH));
            var compiled=PhysicalFluidTopology.compile(devices,Map.of(source,nitrogenAt(source,0,PassiveNetwork.NodeKind.GENERATOR,150000),
                    sink,nitrogenAt(sink,2*direction.y,PassiveNetwork.NodeKind.VOID,101325)));
            var mapping=compiled.pipeViews().get(100L).getFirst();
            assertEquals(reversed?PhysicalFluidTopology.opposite(direction):direction,mapping.flowDirection(),direction+" reversed="+reversed);
            assertEquals(1,compiled.pipeViews().get(100L).size());
        }
        assertNull(new PhysicalFluidTopology.View(0,9,true).flowDirection(),"synthetic views do not invent world geometry");
    }
    @Test void endDrivenRunsSwapTheirLocalFlowDirectionWithTheGraph(){
        var devices=List.of(at(1,0,0,0,Kind.RESERVOIR,NORTH),at(3,1,0,0,Kind.PIPE,NORTH),
                at(5,2,0,0,Kind.COMPRESSOR,WEST),at(4,3,0,0,Kind.PIPE,NORTH),at(2,4,0,0,Kind.RESERVOIR,NORTH));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1),2L,reservoir(2)));
        var mapping=compiled.pipeViews().get(3L).getFirst();
        assertFalse(mapping.forward(),"the compressor discharge reverses the reduction's traversal");
        assertEquals(WEST,mapping.flowDirection());
        var graph=compiled.islands().getFirst().graph();var pipe=pipeOf(compiled,graph,3);
        assertEquals(5,graph.reservoirs().get(pipe.first()).id());assertEquals(1,graph.reservoirs().get(pipe.second()).id());
    }
    @Test void localFlowDirectionFollowsTheSectionRatherThanTheEndpointsOfABentRun(){
        var devices=List.of(at(1,0,0,0,Kind.GENERATOR,NORTH),at(100,1,0,0,Kind.PIPE,NORTH),at(101,2,0,0,Kind.PIPE,NORTH),
                at(102,2,0,1,Kind.PIPE,NORTH),at(103,2,0,2,Kind.PIPE,NORTH),at(2,2,0,3,Kind.VOID,NORTH));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,nitrogenAt(1,0,PassiveNetwork.NodeKind.GENERATOR,150000),
                2L,nitrogenAt(2,0,PassiveNetwork.NodeKind.VOID,101325)));
        assertEquals(1,compiled.islands().getFirst().graph().pipes().size());
        assertEquals(EAST,compiled.pipeViews().get(100L).getFirst().flowDirection());
        assertEquals(SOUTH,compiled.pipeViews().get(102L).getFirst().flowDirection());
        assertEquals(SOUTH,compiled.pipeViews().get(103L).getFirst().flowDirection());
        assertNull(compiled.pipeViews().get(101L).getFirst().flowDirection(),"the corner has no single local tangent and cannot carry a sight glass");
        assertEquals(1,compiled.pipeViews().get(101L).size(),"direction metadata does not duplicate inspection mappings");
    }
    @Test void aConstructedMoversDischargeUsesItsNozzleContactInsteadOfItsRemoteSuctionDatum(){
        var suction=new PhysicalFluidTopology.Position("minecraft:overworld",10,0,10);
        var discharge=new PhysicalFluidTopology.Position("minecraft:overworld",0,2,0);
        var ports=List.of(new PhysicalFluidTopology.Port(suction,WEST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.SUCTION),
                new PhysicalFluidTopology.Port(discharge,EAST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.DISCHARGE));
        var mover=new PhysicalFluidTopology.Device(10,suction,Kind.COMPRESSOR,NORTH,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Compressor(.05,3),ports);
        var devices=List.of(at(1,8,0,10,Kind.GENERATOR,NORTH),at(20,9,0,10,Kind.PIPE,NORTH),mover,
                at(30,1,2,0,Kind.PIPE,NORTH),at(2,2,2,0,Kind.VOID,NORTH));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,nitrogenAt(1,0,PassiveNetwork.NodeKind.GENERATOR,150000),
                2L,nitrogenAt(2,2,PassiveNetwork.NodeKind.VOID,101325)));
        assertEquals(EAST,compiled.pipeViews().get(30L).getFirst().flowDirection(),"discharge is locally east despite its westward and lower binding datum");
    }
    /** The mover between two nitrogen tanks is a compressor (decision D2; a liquid-only pump refuses the gas, D1), an actuator
     * that connects along its facing axis like the pump (plan 3.7); test name kept. */
    @Test void pumpControlUsesItsOutletDirectionAndItsSuctionJunctionInEitherOrientation() {
        for(var facing:List.of(PhysicalFluidTopology.Direction.EAST,PhysicalFluidTopology.Direction.WEST)) {
            var devices=List.of(device(1,0,0,Kind.RESERVOIR,facing),device(2,1,0,Kind.PIPE,facing),device(3,2,0,Kind.COMPRESSOR,facing),device(4,3,0,Kind.PIPE,facing),device(5,4,0,Kind.RESERVOIR,facing));
            var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1),5L,reservoir(5)));assertTrue(compiled.islands().getFirst().error().isEmpty());
            var graph=compiled.islands().getFirst().graph();var pump=graph.pipes().stream().filter(p->p.control() instanceof FlowControl.Compressor).findFirst().orElseThrow();
            assertEquals(3,graph.reservoirs().get(pump.first()).id());assertEquals(facing==PhysicalFluidTopology.Direction.EAST?5:1,graph.reservoirs().get(pump.second()).id());
            var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
            assertTrue(result.averageMassFlows()[graph.pipes().indexOf(pump)]>0);
        }
    }
    @Test void dimensionsDeadLegsAndUnsupportedPumpPortsCannotCreateHiddenConnections() {
        var facing=PhysicalFluidTopology.Direction.EAST;
        var devices=List.of(device(1,0,0,Kind.RESERVOIR,facing),device(2,1,0,Kind.PIPE,facing),device(3,2,0,Kind.PUMP,facing),device(4,2,1,Kind.PIPE,facing));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1)));
        assertTrue(compiled.diagnostics().get(3L).contains("ERROR"));assertTrue(compiled.diagnostics().containsKey(4L));
        var unanchored=PhysicalFluidTopology.compile(List.of(device(9,0,0,Kind.PIPE,facing),device(10,1,0,Kind.PIPE,facing)),Map.of());assertTrue(unanchored.islands().isEmpty());
        var a=device(20,0,0,Kind.RESERVOIR,facing);var b=new PhysicalFluidTopology.Device(21,new PhysicalFluidTopology.Position("minecraft:the_nether",1,0,0),Kind.RESERVOIR,facing,a.geometry(),new FlowControl.Passive());
        var separate=PhysicalFluidTopology.compile(List.of(a,b),Map.of(20L,reservoir(20),21L,reservoir(21)));assertEquals(2,separate.islands().size());
    }
    @Test void zeroStoragePumpBypassDisablesThePumpButStillAllowsPassiveEqualization() {
        var facing=PhysicalFluidTopology.Direction.EAST;
        var devices=List.of(device(1,0,0,Kind.RESERVOIR,facing),device(2,1,0,Kind.PIPE,facing),device(3,2,0,Kind.PUMP,facing),device(4,3,0,Kind.PIPE,facing),device(5,4,0,Kind.RESERVOIR,facing),
                device(6,1,1,Kind.PIPE,facing),device(7,2,1,Kind.PIPE,facing),device(8,3,1,Kind.PIPE,facing));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,150000,()->{})),5L,new PassiveNetwork.Reservoir(5,0,model.initialNitrogenCharge(1,298.15,149000,()->{}))));
        assertTrue(compiled.diagnostics().get(3L).contains("ERROR"));var graph=compiled.islands().getFirst().graph();
        var pump=graph.pipes().stream().filter(p->p.control() instanceof FlowControl.Pump).findFirst().orElseThrow();
        assertEquals(0,((FlowControl.Pump)pump.control()).targetVolumeFlow());
        var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(0,result.averageMassFlows()[graph.pipes().indexOf(pump)],1e-12);
        assertTrue(Arrays.stream(result.averageMassFlows()).anyMatch(q->Math.abs(q)>1e-6));
    }
    /** Plan 3.7: a compressor is an actuator like the pump. It connects only along its facing axis (a pipe at its side
     * stays unconnected), and a zero-storage bypass around it disables it by a zero target while passive equalisation runs. */
    @Test void aCompressorConnectsAlongItsAxisOnlyAndAZeroStorageBypassDisablesIt() {
        var facing=PhysicalFluidTopology.Direction.EAST;
        var side=PhysicalFluidTopology.compile(List.of(device(1,0,0,Kind.RESERVOIR,facing),device(2,1,0,Kind.PIPE,facing),device(3,2,0,Kind.COMPRESSOR,facing),device(4,2,1,Kind.PIPE,facing)),Map.of(1L,reservoir(1)));
        assertTrue(side.diagnostics().get(3L).contains("ERROR"),"one port only: "+side.diagnostics());assertTrue(side.diagnostics().containsKey(4L),"the side pipe is not connected");
        var devices=List.of(device(1,0,0,Kind.RESERVOIR,facing),device(2,1,0,Kind.PIPE,facing),device(3,2,0,Kind.COMPRESSOR,facing),device(4,3,0,Kind.PIPE,facing),device(5,4,0,Kind.RESERVOIR,facing),
                device(6,1,1,Kind.PIPE,facing),device(7,2,1,Kind.PIPE,facing),device(8,3,1,Kind.PIPE,facing));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,150000,()->{})),5L,new PassiveNetwork.Reservoir(5,0,model.initialNitrogenCharge(1,298.15,149000,()->{}))));
        assertTrue(compiled.diagnostics().get(3L).contains("ERROR"));var graph=compiled.islands().getFirst().graph();
        var compressor=graph.pipes().stream().filter(p->p.control() instanceof FlowControl.Compressor).findFirst().orElseThrow();
        assertEquals(0,((FlowControl.Compressor)compressor.control()).targetVolumeFlow());assertEquals(3,((FlowControl.Compressor)compressor.control()).maximumPressureRatio());
        var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(0,result.averageMassFlows()[graph.pipes().indexOf(compressor)],1e-12);
        assertTrue(Arrays.stream(result.averageMassFlows()).anyMatch(q->Math.abs(q)>1e-6));
        assertThrows(IllegalArgumentException.class,()->new PhysicalFluidTopology.Device(9,new PhysicalFluidTopology.Position("minecraft:overworld",9,0,0),Kind.COMPRESSOR,facing,
                new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Pump(.01,500000)),"a compressor without ports is an in-line mover, a pipe");
    }

    // ---------------- faces to ports (phase-ports WP5, plan 4.1, default A1) ----------------

    private static final PhysicalFluidTopology.Direction N=PhysicalFluidTopology.Direction.NORTH;
    private PhysicalFluidTopology.Device at(long id,int x,int y,int z,Kind kind,PhysicalFluidTopology.Direction facing) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),facing,new PipeResistance.Geometry(1,.05,.000045,0),
                kind==Kind.PUMP?new FlowControl.Pump(.001,500000):kind==Kind.COMPRESSOR?new FlowControl.Compressor(.00001,3):new FlowControl.Passive());
    }
    private PassiveNetwork.Reservoir nitrogenAt(long id,int y,PassiveNetwork.NodeKind kind,double pressure){return new PassiveNetwork.Reservoir(id,y,model.initialNitrogenCharge(1,298.15,pressure,()->{}),kind);}
    private int component(String name){int i=model.components().indexOf(name);assertTrue(i>=0,name);return i;}
    /** A 1 m3 vessel at 298.15 K and {@code pressure} holding {@code waterVolume} m3 of water under nitrogen (LevelHeadTest's). */
    private FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] mw=model.molecularWeights();double[] n=new double[mw.length];int water=component("Water");
        n[water]=waterVolume*997/mw[water];n[component("Nitrogen")]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,()->{});
    }
    private static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;throw new AssertionError("no node "+id);}
    /** The compiled connection a pipe block belongs to. */
    private static PassiveNetwork.Pipe pipeOf(PhysicalFluidTopology.Compiled compiled,PassiveNetwork graph,long pipeBlock) {
        long id=compiled.pipeViews().get(pipeBlock).getFirst().pipeId();return graph.pipes().stream().filter(p->p.id()==id).findFirst().orElseThrow();
    }
    private static PassiveNetwork.Pipe between(PassiveNetwork graph,int a,int b) {
        return graph.pipes().stream().filter(p->p.first()==a&&p.second()==b||p.first()==b&&p.second()==a).findFirst().orElseThrow(()->new AssertionError("no connection "+a+"-"+b));
    }
    private static double gravityHead(FluidThermodynamics model,PassiveNetwork.Reservoir tank) {
        return PassiveStepSolver.GRAVITY*model.liquidMass(tank.state())*PassiveStepSolver.LEVEL_HEAD_HEIGHT/tank.inventory().volume();
    }

    /**
     * Every face of a tank: a pipe leaving by the UP face is the tank's top outlet (VAPOR), by the DOWN face its bottom
     * outlet (LIQUID), by a horizontal face a side outlet (BULK); the far ends (a generator above, voids elsewhere) stay
     * BULK whichever face they are reached by. The island solves with those ports.
     */
    @Test void everyFaceOfATankCompilesToItsPortAndOnlyTheTankEndCarriesOne() {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();devices.add(at(1,0,0,0,Kind.RESERVOIR,N));
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();boundaries.put(1L,nitrogenAt(1,0,PassiveNetwork.NodeKind.RESERVOIR,101325));
        var expected=new LinkedHashMap<Long,PassiveNetwork.PhasePort>();var far=new HashMap<Long,Long>();var faceOf=new HashMap<Long,PhysicalFluidTopology.Direction>();long id=2;
        for(var face:PhysicalFluidTopology.Direction.values()) {
            devices.add(at(id,face.x,face.y,face.z,Kind.PIPE,N));long block=id++;
            var kind=face==PhysicalFluidTopology.Direction.UP?Kind.GENERATOR:Kind.VOID;
            devices.add(at(id,2*face.x,2*face.y,2*face.z,kind,N));
            boundaries.put(id,nitrogenAt(id,2*face.y,kind==Kind.GENERATOR?PassiveNetwork.NodeKind.GENERATOR:PassiveNetwork.NodeKind.VOID,101325));far.put(block,id++);
            faceOf.put(block,face);
            expected.put(block,face==PhysicalFluidTopology.Direction.UP?PassiveNetwork.PhasePort.VAPOR:face==PhysicalFluidTopology.Direction.DOWN?PassiveNetwork.PhasePort.LIQUID:PassiveNetwork.PhasePort.BULK);
        }
        var compiled=PhysicalFluidTopology.compile(devices,boundaries);
        assertEquals(1,compiled.islands().size(),compiled.diagnostics().toString());var graph=compiled.islands().getFirst().graph();
        assertEquals(6,graph.pipes().size());int tank=node(graph,1);
        expected.forEach((block,port)->{
            var pipe=pipeOf(compiled,graph,block);
            assertEquals(port,pipe.portAt(tank),"pipe block "+block);
            assertEquals(PassiveNetwork.PhasePort.BULK,pipe.portAt(node(graph,far.get(block))),"the far boundary of pipe block "+block);
        });
        assertEquals(1,graph.pipes().stream().filter(p->p.portAt(tank)==PassiveNetwork.PhasePort.VAPOR).count());
        assertEquals(1,graph.pipes().stream().filter(p->p.portAt(tank)==PassiveNetwork.PhasePort.LIQUID).count());
        // The compile records each connection's face (GUI fix F4): the tank page names the four side rows apart.
        var faces=compiled.tankFaces().get(1L);assertEquals(6,faces.size());
        faceOf.forEach((block,face)->assertEquals(face,faces.get(pipeOf(compiled,graph,block).id()),"pipe block "+block));
        var lines=TankOutlets.of(model,graph,tank,null,faces);
        assertEquals(EnumSet.allOf(PhysicalFluidTopology.Direction.class),lines.stream().map(FluidView.Outlet::face).collect(java.util.stream.Collectors.toCollection(()->EnumSet.noneOf(PhysicalFluidTopology.Direction.class))));
        assertEquals(4,lines.stream().filter(o->o.port()==PassiveNetwork.PhasePort.BULK).map(FluidView.Outlet::face).distinct().count(),"four side rows, four faces");
        assertEquals(List.of(),compiled.tankFaces().keySet().stream().filter(k->k!=1L).toList(),"only the tank has faces");
        assertThrows(IllegalStateException.class,()->TankOutlets.of(model,graph,tank,null,Map.of()),"a connection without its compiled face is refused");
        var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(.05,result.advancedSeconds(),0);
    }
    /** Two tanks stacked with a pipe between them: the upper tank's bottom outlet (LIQUID) feeds the lower tank's top (VAPOR). */
    @Test void aStackOfTwoTanksJoinsTheUpperTanksBottomToTheLowerTanksTop() {
        for(boolean upperFirst:new boolean[]{true,false}) {
            long upper=upperFirst?1:3,lower=upperFirst?3:1;
            var devices=List.of(at(upper,0,2,0,Kind.RESERVOIR,N),at(2,0,1,0,Kind.PIPE,N),at(lower,0,0,0,Kind.RESERVOIR,N));
            var compiled=PhysicalFluidTopology.compile(devices,Map.of(upper,nitrogenAt(upper,2,PassiveNetwork.NodeKind.RESERVOIR,101325),lower,nitrogenAt(lower,0,PassiveNetwork.NodeKind.RESERVOIR,101325)));
            var graph=compiled.islands().getFirst().graph();var pipe=pipeOf(compiled,graph,2);
            assertEquals(PassiveNetwork.PhasePort.LIQUID,pipe.portAt(node(graph,upper)),"the upper tank's end leaves by its DOWN face");
            assertEquals(PassiveNetwork.PhasePort.VAPOR,pipe.portAt(node(graph,lower)),"the lower tank's end leaves by its UP face");
        }
        // Two tanks directly on top of each other do not connect (boundaries never join directly), so no port is compiled.
        var touching=PhysicalFluidTopology.compile(List.of(at(1,0,1,0,Kind.RESERVOIR,N),at(2,0,0,0,Kind.RESERVOIR,N)),
                Map.of(1L,nitrogenAt(1,1,PassiveNetwork.NodeKind.RESERVOIR,101325),2L,nitrogenAt(2,0,PassiveNetwork.NodeKind.RESERVOIR,101325)));
        assertTrue(touching.islands().stream().allMatch(i->i.graph().pipes().isEmpty()));
    }
    /**
     * A pump one pipe block below a tank's DOWN face (only pipes connect, decision D20; the pump stood on the face itself until
     * 2026-09-28), in-line and on its side beside an elbow below that pipe (batch 2026-10-28-old-mover-removal). Facing away
     * it draws from the tank's bottom: the passive suction run has the tank's LIQUID port. Facing back it pumps into the
     * tank's bottom: the compiled pump connection runs from the pump's junction to
     * the tank, and the port follows the tank's end through the {@code fromEnd ? b : a} swap, in either identity order
     * (which decides whether the run is stated from the tank or from the pump).
     */
    @Test void aPumpOnATanksBottomFaceTakesTheBottomPortInEitherOrientationAndIdentityOrder() {
        for(boolean tankFirst:new boolean[]{true,false}) {
            long tank=tankFirst?1:2,pump=tankFirst?2:1;
            // Suction from the bottom: tank at y=2, a pipe block (only pipes connect, decision D20), an elbow, the pump beside it facing away, a pipe and a void.
            var drawing=PhysicalFluidTopology.compile(List.of(at(tank,0,2,0,Kind.RESERVOIR,N),at(5,0,1,0,Kind.PIPE,N),at(6,0,0,0,Kind.PIPE,N),at(pump,1,0,0,Kind.PUMP,PhysicalFluidTopology.Direction.EAST),at(3,2,0,0,Kind.PIPE,N),at(4,3,0,0,Kind.VOID,N)),
                    Map.of(tank,nitrogenAt(tank,2,PassiveNetwork.NodeKind.RESERVOIR,101325),4L,nitrogenAt(4,0,PassiveNetwork.NodeKind.VOID,101325)));
            assertTrue(drawing.islands().getFirst().error().isEmpty(),drawing.diagnostics().toString());
            var graph=drawing.islands().getFirst().graph();int t=node(graph,tank),u=node(graph,pump);
            var suction=between(graph,t,u);assertInstanceOf(FlowControl.Passive.class,suction.control());
            assertEquals(PassiveNetwork.PhasePort.LIQUID,suction.portAt(t));assertEquals(PassiveNetwork.PhasePort.BULK,suction.portAt(u));
            var moving=graph.pipes().stream().filter(p->p.control() instanceof FlowControl.Pump).findFirst().orElseThrow();
            assertEquals(u,moving.first());assertTrue(moving.bulk(),"the pump's own connection joins its junction and the void");
            // Discharge into the bottom: a generator and a pipe beyond the pump, the pump facing back into the tank.
            var filling=PhysicalFluidTopology.compile(List.of(at(tank,0,2,0,Kind.RESERVOIR,N),at(5,0,1,0,Kind.PIPE,N),at(6,0,0,0,Kind.PIPE,N),at(pump,1,0,0,Kind.PUMP,PhysicalFluidTopology.Direction.WEST),at(3,2,0,0,Kind.PIPE,N),at(4,3,0,0,Kind.GENERATOR,N)),
                    Map.of(tank,nitrogenAt(tank,2,PassiveNetwork.NodeKind.RESERVOIR,101325),4L,nitrogenAt(4,0,PassiveNetwork.NodeKind.GENERATOR,101325)));
            assertTrue(filling.islands().getFirst().error().isEmpty(),filling.diagnostics().toString());
            graph=filling.islands().getFirst().graph();t=node(graph,tank);u=node(graph,pump);
            var discharge=between(graph,t,u);assertInstanceOf(FlowControl.Pump.class,discharge.control());
            assertEquals(u,discharge.first(),"a mover's connection is stated from its junction");assertEquals(t,discharge.second());
            assertEquals(PassiveNetwork.PhasePort.BULK,discharge.firstPort());assertEquals(PassiveNetwork.PhasePort.LIQUID,discharge.secondPort(),"tank first="+tankFirst);
        }
    }
    /**
     * An inline filter one pipe block above a tank's UP face (decision D20; it stood on the face until 2026-09-28), in both
     * filter orientations:
     * facing UP the tank is on the filter's negative side; facing DOWN it is on the positive side, whose connection is
     * rebuilt onto the filter's positive junction and keeps its ports.
     */
    @Test void aFilterOnATanksTopFaceKeepsTheTopPortInBothOrientations() {
        for(var facing:List.of(PhysicalFluidTopology.Direction.UP,PhysicalFluidTopology.Direction.DOWN)) {
            var devices=List.of(at(1,0,0,0,Kind.RESERVOIR,N),at(5,0,1,0,Kind.PIPE,N),at(2,0,2,0,Kind.FILTER,facing),at(3,0,3,0,Kind.PIPE,N),at(4,0,4,0,Kind.VOID,N));
            var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,nitrogenAt(1,0,PassiveNetwork.NodeKind.RESERVOIR,120000),4L,nitrogenAt(4,4,PassiveNetwork.NodeKind.VOID,101325)),
                    Map.of(2L,InlineFilter.empty()),model.initialNitrogenCharge(1,298.15,101325,()->{}));
            var graph=compiled.islands().getFirst().graph();int tank=node(graph,1);
            var atTank=graph.pipes().stream().filter(p->p.first()==tank||p.second()==tank).toList();
            assertEquals(1,atTank.size());assertEquals(PassiveNetwork.PhasePort.VAPOR,atTank.getFirst().portAt(tank),"filter facing "+facing);
            assertEquals(1,graph.pipes().stream().filter(p->!p.bulk()).count(),"only the tank's end carries a port");
            var filter=graph.pipes().stream().filter(p->p.filter()!=null).findFirst().orElseThrow();assertTrue(filter.bulk());
            var result=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{});
            assertTrue(result.averageMassFlows()[graph.pipes().indexOf(atTank.getFirst())]!=0,"the tank vents through its top and the filter");
        }
    }
    /**
     * Two tanks stacked on a pipe block, the upper one holding 0.3 m3 of water under nitrogen, both at 1 atm. The upper
     * tank's bottom outlet draws water and nothing else into the lower tank's top; as the lower tank's gas is compressed
     * its top outlet draws gas back up into the upper tank's bottom (inflow is not distinguished, decision D3), so the
     * water passes down and the gas up until the lower tank holds all of it, and the two headspaces stand one gas column
     * apart. Every component is conserved. The tank lines read the first interval: the upper tank's bottom outlet
     * "drawing water", the lower tank's top "receiving".
     */
    @Test void aCompiledStackPassesTheUpperTanksWaterDownAndItsGasUp() {
        var start=new PassiveNetwork.Reservoir(1,2,waterUnderNitrogen(.3,101325));
        var devices=List.of(at(1,0,2,0,Kind.RESERVOIR,N),at(2,0,1,0,Kind.PIPE,N),at(3,0,0,0,Kind.RESERVOIR,N));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,start,3L,nitrogenAt(3,0,PassiveNetwork.NodeKind.RESERVOIR,101325)));
        var graph=compiled.islands().getFirst().graph();int upper=node(graph,1),lower=node(graph,3);var line=between(graph,upper,lower);int edge=graph.pipes().indexOf(line);
        double[] before=totals(graph);
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result first=null;
        for(int slice=0;slice<40;slice++) {
            var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});graph=result.graph();if(first==null)first=result;
        }
        double moved=(line.first()==upper?1:-1)*first.averageMassFlows()[edge];
        var out=first.pipeTransfers().stream().filter(t->t.pipeId()==line.id()).findFirst().orElseThrow();var drawn=line.first()==upper?out.forward():out.reverse();
        assertTrue(moved>0,"the water runs down: "+moved);
        assertEquals(0,Arrays.stream(drawn.phaseMoles()[2]).sum(),0,"a bottom outlet over water draws no gas");
        assertEquals(0,Arrays.stream(drawn.phaseMoles()[0]).sum(),0,"nor hydrocarbon liquid");
        var upperLines=TankOutlets.of(model,first.graph(),upper,first,compiled.tankFaces().get(1L));var lowerLines=TankOutlets.of(model,first.graph(),lower,first,compiled.tankFaces().get(3L));
        assertEquals(1,upperLines.size());assertEquals(PassiveNetwork.PhasePort.LIQUID,upperLines.getFirst().port());assertEquals(1,upperLines.getFirst().drawn(),"drawing water");
        assertEquals(moved,upperLines.getFirst().massFlow(),1e-9*Math.abs(moved));assertTrue(upperLines.getFirst().head()>0);
        assertEquals(PassiveNetwork.PhasePort.VAPOR,lowerLines.getFirst().port());assertEquals(-moved,lowerLines.getFirst().massFlow(),1e-9*Math.abs(moved),"the lower top receives");
        assertEquals(FluidView.Outlet.NONE,lowerLines.getFirst().drawn());
        double[] after=totals(graph);for(int c=0;c<before.length;c++)assertEquals(before[c],after[c],1e-10*Math.max(1,before[c]),"component "+c);
        var a=graph.reservoirs().get(upper);var b=graph.reservoirs().get(lower);
        System.out.println("STACK_PASS upperP="+a.state().pressure()+" lowerP="+b.state().pressure()+" upperLiquidKg="+model.liquidMass(a.state())+" lowerLiquidKg="+model.liquidMass(b.state()));
        assertTrue(model.liquidMass(a.state())<1e-6*model.liquidMass(start.state()),"the upper tank has passed down its water");
        assertTrue(b.state().pressure()>a.state().pressure()&&b.state().pressure()-a.state().pressure()<100,"the headspaces rest one gas column apart");
    }
    /**
     * {@code LEVEL_HEAD_REVIEW.md} fixture 6, face to port plus the level head on a compiled stack: a tank holding 0.3 m3
     * of water under nitrogen at 1 atm stands on a pipe block over a void held half the tank's level head above what the
     * two-metre water column alone would lift the tank's headspace to. Only the head (decision D9, at the tank's bottom port,
     * which its DOWN face compiles to) can drive the water down, and it does: the bottom outlet draws water only, and the
     * line comes to rest, still holding water, where the bottom-port pressure plus the column stands at the void's
     * pressure (within the reopen band of a closed one-way run).
     */
    @Test void aTankOverAVoidDrainsOnItsLevelHeadAloneAndRests() {
        var water=waterUnderNitrogen(.3,101325);var start=new PassiveNetwork.Reservoir(1,2,water);
        double rho=water.waterLiquid()*model.molecularWeights()[component("Water")]/water.waterVolume();
        double head=gravityHead(model,start),column=rho*PassiveStepSolver.GRAVITY*2,voidPressure=water.pressure()+column+head/2;
        var devices=List.of(at(1,0,2,0,Kind.RESERVOIR,N),at(2,0,1,0,Kind.PIPE,N),at(3,0,0,0,Kind.VOID,N));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,start,3L,nitrogenAt(3,0,PassiveNetwork.NodeKind.VOID,voidPressure)));
        var graph=compiled.islands().getFirst().graph();int tank=node(graph,1);var line=graph.pipes().getFirst();int edge=0;
        assertEquals(PassiveNetwork.PhasePort.LIQUID,line.portAt(tank));
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result first=null,last=null;double drained=0;
        for(int slice=0;slice<40;slice++) {
            last=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});graph=last.graph();if(first==null)first=last;
            drained+=(line.first()==tank?1:-1)*last.averageMassFlows()[edge]*5;
        }
        var out=first.pipeTransfers().getFirst();var drawn=line.first()==tank?out.forward():out.reverse();
        var a=graph.reservoirs().get(tank);double restRho=a.state().waterLiquid()*model.molecularWeights()[component("Water")]/a.state().waterVolume();
        double restHead=gravityHead(model,a),imbalance=a.state().pressure()+restHead+restRho*PassiveStepSolver.GRAVITY*2-voidPressure;
        System.out.println("STACK_HEAD head="+head+" column="+column+" void="+voidPressure+" firstFlow="+(line.first()==tank?1:-1)*first.averageMassFlows()[edge]
                +" drained="+drained+" kg restP="+a.state().pressure()+" restHead="+restHead+" imbalance="+imbalance+" lastFlow="+last.averageMassFlows()[edge]+" lastMode="+last.endpointModes().get(edge));
        assertTrue(water.pressure()+column<voidPressure,"the column alone cannot lift the headspace to the void");
        assertTrue(drained>1,"the head drains the tank: "+drained+" kg");
        assertEquals(0,Arrays.stream(drawn.phaseMoles()[2]).sum(),0,"the bottom outlet draws water only");
        assertTrue(restHead>0,"the tank still holds water at rest");
        assertEquals(0,last.averageMassFlows()[edge],1e-9,"and passes nothing");
        assertEquals(0,imbalance,Math.max(1,1e-6*voidPressure),"at rest the bottom port plus the column stands at the void");
    }
    private static double[] totals(PassiveNetwork graph) {
        double[] sum=null;
        for(var n:graph.reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR||n.junction()){var m=n.inventory().moles();if(sum==null)sum=new double[m.length];for(int c=0;c<m.length;c++)sum[c]+=m[c];}
        return sum;
    }

    // ---------------- from OnlyPipesConnectTest ----------------
    /*
     * Owner decision D20 (phase-ports batch, PHASE_PORTS_REVIEW.md "Follow-up rules"): only pipes connect. Two pieces of
     * equipment side by side - a tank, generator, void, pump, compressor, valve or filter against another - exchange nothing;
     * with a pipe block between them they are one line. Step 1 of the network reduction stage, in the compile and in the
     * registry's walks.
     */
    private static PhysicalFluidTopology.Device at(long id,int x,Kind kind,PhysicalFluidTopology.Direction facing) {
        var control=switch(kind){case PUMP->new FlowControl.Pump(.01,500000);case COMPRESSOR->new FlowControl.Compressor(.05,3);case VALVE->new FlowControl.PressureValve(200000);default->new FlowControl.Passive();};
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,0,0),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),facing,new PipeResistance.Geometry(1,.05,.000045,0),control);
    }
    private PhysicalFluidTopology.Compiled compile(String layout) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<layout.length();i++) {
            var kind=switch(layout.charAt(i)){case 'G'->Kind.GENERATOR;case 'V'->Kind.VOID;case 'R'->Kind.RESERVOIR;case 'U'->Kind.PUMP;case 'C'->Kind.COMPRESSOR;case 'Q'->Kind.VALVE;case 'F'->Kind.FILTER;default->Kind.PIPE;};
            var device=at(i+1,i,kind,EAST);devices.add(device);
            if(kind==Kind.GENERATOR)boundaries.put(device.id(),new PassiveNetwork.Reservoir(device.id(),0,model.initialNitrogenCharge(1,298.15,200000,()->{}),PassiveNetwork.NodeKind.GENERATOR));
            if(kind==Kind.VOID)boundaries.put(device.id(),new PassiveNetwork.Reservoir(device.id(),0,model.initialNitrogenCharge(1,298.15,101325,()->{}),PassiveNetwork.NodeKind.VOID));
            if(kind==Kind.RESERVOIR)boundaries.put(device.id(),new PassiveNetwork.Reservoir(device.id(),0,model.initialNitrogenCharge(1,298.15,101325,()->{})));
        }
        var filters=new HashMap<Long,InlineFilter>();for(var d:devices)if(d.kind()==Kind.FILTER)filters.put(d.id(),InlineFilter.empty());
        return PhysicalFluidTopology.compile(devices,boundaries,filters,model.initialNitrogenCharge(1,298.15,101325,()->{}));
    }
    private static Set<Long> islandOf(PhysicalFluidTopology.Compiled compiled,long device) {
        return compiled.islands().stream().filter(i->i.physicalIds().contains(device)&&!i.graph().pipes().isEmpty()&&i.graph().pipes().stream().noneMatch(p->p.blockedDirections()==3)).findFirst().map(PhysicalFluidTopology.Island::physicalIds).orElse(Set.of());
    }

    @Test void equipmentSideBySideExchangesNothing() {
        // An in-line pump or compressor is a pipe carrying its control (batch 2026-10-28-old-mover-removal, option J1): it
        // joins the equipment on its axis as a pipe does.
        for(String layout:List.of("GUPR","RCPV")){var moved=compile(layout);assertEquals(4,islandOf(moved,4).size(),layout+": one line: "+moved.diagnostics());}
        // A valve or a filter against a tank, piped on to a void: nothing reaches the tank.
        for(String layout:List.of("RQPV","RFPV"))assertTrue(islandOf(compile(layout),1).isEmpty(),layout+": the tank is joined to nothing");
        // Two filters and a pump in a row between pipes: no connection across a device-device face.
        assertTrue(islandOf(compile("GPFFPR"),6).isEmpty(),"two filters side by side do not pass fluid");
    }

    @Test void aPipeBlockBetweenTheEquipmentJoinsThem() {
        for(String layout:List.of("GPUPR","RPQPV","RPCPV","RPFPV","GPFPFPR")) {
            var compiled=compile(layout);var island=islandOf(compiled,layout.length());
            assertEquals(layout.length(),island.size(),layout+": one line: "+compiled.diagnostics());
        }
    }

    /** Owner decision 2026-10-16: a valve's half of each link takes the bore of the pipe on that side. (A pump is an
     * in-line mover, a pipe of its own bore, since batch 2026-10-28-old-mover-removal.) */
    @Test void anActuatorTakesTheBoreOfThePipeOnEachSide() {
        var facing=PhysicalFluidTopology.Direction.EAST;
        for(var kind:List.of(Kind.VALVE)) {
            var devices=List.of(device(1,0,0,Kind.RESERVOIR,facing),bore(device(2,1,0,Kind.PIPE,facing),.2),device(3,2,0,kind,facing),bore(device(4,3,0,Kind.PIPE,facing),.3),device(5,4,0,Kind.RESERVOIR,facing));
            var graph=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1),5L,reservoir(5))).islands().getFirst().graph();
            var bores=graph.pipes().stream().flatMap(p->p.sections().stream()).map(PipeResistance.Geometry::diameter).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of(.2,.3),bores,kind+": no section keeps the fixed 0.05 m bore");
            double length=graph.pipes().stream().flatMap(p->p.sections().stream()).mapToDouble(PipeResistance.Geometry::length).sum();
            assertEquals(3,length,1e-12,kind+": two pipes and the actuator's own metre");
        }
    }
    private static PhysicalFluidTopology.Device bore(PhysicalFluidTopology.Device d,double diameter) {
        return new PhysicalFluidTopology.Device(d.id(),d.position(),d.kind(),d.facing(),new PipeResistance.Geometry(1,diameter,.000045,0),d.control());
    }

    @Test void theRegistryWalksFollowTheSameRule() {
        var w=new EventPathWorld();w.runTo(100);
        // A valve: an in-line pump is a pipe and joins the tank (batch 2026-10-28-old-mover-removal, option J1).
        long tank=w.place(0,0,0,Kind.RESERVOIR,NORTH,.05);long valve=w.place(1,0,0,Kind.VALVE,EAST,.05);w.place(2,0,0,Kind.PIPE,NORTH,.05);
        w.place(3,0,0,Kind.VOID,NORTH,.05);w.runTo(102);
        assertNull(w.registry.owner(valve),"a valve against a tank is in no island");
        assertEquals(List.of(tank),w.registry.members(w.registry.owner(tank)),"the tank is an island of its own");
        // A pipe between them: the placement's walk reaches both, and one island holds the line.
        w.runTo(300);w.place(0,0,1,Kind.PIPE,NORTH,.05);w.runTo(302);
        assertNull(w.registry.owner(valve),"a pipe beside the tank but not between them changes nothing");
        w.runTo(4000);w.assertKeptUp("a valve against a tank");
    }

    // ---------------- explicit pipe joints and rack lanes (batch 2026-10-26-pipe-laying-system) ----------------
    /*
     * A laid pipe connects only through the faces its path uses, so two lines laid side by side stay two lines, while
     * hand-placed pipes (every face) join as they always did; rack lanes meet where their face points coincide.
     */
    private static final int ALONG_X=1<<WEST.ordinal()|1<<EAST.ordinal();
    private PhysicalFluidTopology.Device pipe(long id,int x,int z,int joints) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,0,z),Kind.PIPE,EAST,new PipeResistance.Geometry(1,.05,.000045,0),
                new FlowControl.Passive(),List.of(),true,PhysicalFluidTopology.Cutoff.NONE,false,joints);
    }
    private PhysicalFluidTopology.Device tank(long id,int x,int z) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,0,z),Kind.RESERVOIR,EAST,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
    }
    /** Two lines of four pipes, at z 0 and z 1, each between two tanks; {@code joints} is every pipe's joint set. */
    private PhysicalFluidTopology.Compiled twoLines(int joints,int teeAt) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>(List.of(tank(1,0,0),tank(2,5,0),tank(3,0,1),tank(4,5,1)));
        for(int x=1;x<=4;x++) {
            devices.add(pipe(10+x,x,0,x==teeAt?joints|1<<SOUTH.ordinal():joints));
            devices.add(pipe(20+x,x,1,x==teeAt?joints|1<<NORTH.ordinal():joints));
        }
        return PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1),2L,reservoir(2),3L,reservoir(3),4L,reservoir(4)));
    }

    @Test void twoLinesLaidSideBySideStayTwoLines() {
        var laid=twoLines(ALONG_X,0);
        assertEquals(2,laid.islands().size(),"each laid line is its own island");
        for(var island:laid.islands()) {
            assertEquals(1,island.graph().pipes().size(),"one run between its two tanks");
            assertEquals(4,island.graph().pipes().getFirst().sections().stream().mapToDouble(PipeResistance.Geometry::length).sum());
        }
        var placed=twoLines(PhysicalFluidTopology.ALL_FACES,0);
        assertEquals(1,placed.islands().size(),"hand-placed pipes join every neighbour, as before");
    }

    @Test void aTeeLaidBetweenTheLinesJoinsThemOnlyWhereBothHaveTheJoint() {
        assertEquals(1,twoLines(ALONG_X,2).islands().size(),"both pipes at x 2 carry the joint across: the lines meet there");
        var oneSided=new ArrayList<PhysicalFluidTopology.Device>(List.of(tank(1,0,0),tank(2,5,0),tank(3,0,1),tank(4,5,1)));
        for(int x=1;x<=4;x++){oneSided.add(pipe(10+x,x,0,x==2?ALONG_X|1<<SOUTH.ordinal():ALONG_X));oneSided.add(pipe(20+x,x,1,ALONG_X));}
        assertEquals(2,PhysicalFluidTopology.compile(oneSided,Map.of(1L,reservoir(1),2L,reservoir(2),3L,reservoir(3),4L,reservoir(4))).islands().size(),
                "a joint facing a pipe without one is an open end");
    }

    @Test void aLaidPipeBesideAHandPlacedOneDoesNotJoinIt() {
        var devices=List.of(tank(1,0,0),pipe(11,1,0,PhysicalFluidTopology.ALL_FACES),tank(2,2,0),pipe(21,1,1,ALONG_X));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1),2L,reservoir(2)));
        assertEquals(1,compiled.islands().size());assertFalse(compiled.islands().getFirst().physicalIds().contains(21L));
        assertEquals("NO FLOW: unconnected pipe",compiled.diagnostics().get(21L));
    }

    @Test void onlyALaidPipeHasExplicitJointsAndEveryCopyKeepsThem() {
        assertThrows(IllegalArgumentException.class,()->pipe(1,0,0,64),"past the six faces");
        assertThrows(IllegalArgumentException.class,()->new PhysicalFluidTopology.Device(1,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),Kind.RESERVOIR,EAST,
                new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive(),List.of(),true,PhysicalFluidTopology.Cutoff.NONE,false,ALONG_X),"a tank has every face");
        var laid=pipe(1,0,0,ALONG_X);
        assertEquals(ALONG_X,laid.withGeometry(new PipeResistance.Geometry(1,.1,.000045,0)).joints());
        assertEquals(ALONG_X,laid.withCutoff(EAST,PhysicalFluidTopology.Cutoff.OPEN).joints());
        assertEquals(ALONG_X,laid.withInlineFilter(EAST,true).joints());
        assertEquals(ALONG_X|1<<NORTH.ordinal(),laid.withJoints(ALONG_X|1<<NORTH.ordinal()).joints());
        assertFalse(laid.mechanicallyConnects(NORTH));assertTrue(laid.mechanicallyConnects(EAST));
    }

    /** A lane on option {@code lane - 1}, {@code slots} wide, whose point on each joint face stands for that place: lanes of one place meet. */
    private PhysicalFluidTopology.Device lane(long id,int x,int z,int lane,int slots,int joints) {
        long points=0;for(int face=0;face<6;face++)if((joints>>face&1)!=0)points|=(long)(lane*8+slots)<<10*face;
        return lane(id,x,z,lane,slots,joints,points);
    }
    private PhysicalFluidTopology.Device lane(long id,int x,int z,int lane,int slots,int joints,long points) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,0,z,lane),Kind.PIPE,EAST,new PipeResistance.Geometry(1,.05,.000045,0),
                new FlowControl.Passive(),List.of(),true,PhysicalFluidTopology.Cutoff.NONE,false,joints,slots,points);
    }
    private int islands(List<PhysicalFluidTopology.Device> devices,long... tanks) {
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();for(long tank:tanks)boundaries.put(tank,reservoir(tank));
        return PhysicalFluidTopology.compile(devices,boundaries).islands().size();
    }

    /** A line through two rack cells: plain laid pipes at x 0 and 3 meet the lanes across the rack faces (plan 5.2). */
    @Test void aLaneJoinsTheSameLaneNextDoorAndAPlainPipeAcrossARackFace() {
        var line=List.of(tank(1,-1,0),pipe(10,0,0,ALONG_X),lane(11,1,0,2,1,ALONG_X),lane(12,2,0,2,1,ALONG_X),pipe(13,3,0,ALONG_X),tank(2,4,0));
        assertEquals(1,islands(line,1,2),"one line from tank to tank");
        var compiled=PhysicalFluidTopology.compile(line,Map.of(1L,reservoir(1),2L,reservoir(2)));
        assertEquals(1,compiled.islands().getFirst().graph().pipes().size());
        assertEquals(4,compiled.islands().getFirst().graph().pipes().getFirst().sections().stream().mapToDouble(PipeResistance.Geometry::length).sum());
        assertEquals(2,islands(List.of(tank(1,-1,0),pipe(10,0,0,ALONG_X),lane(11,1,0,2,1,ALONG_X),lane(12,2,0,3,1,ALONG_X),pipe(13,3,0,ALONG_X),tank(2,4,0)),1,2),
                "lanes on other positions do not meet");
        assertEquals(2,islands(List.of(tank(1,-1,0),pipe(10,0,0,ALONG_X),lane(11,1,0,2,1,ALONG_X),lane(12,2,0,2,2,ALONG_X),pipe(13,3,0,ALONG_X),tank(2,4,0)),1,2),
                "lanes of other widths do not meet");
        // Racks of opposite facings in line: option 1 in one is option 2 in the other, at the same point (RACK_LAYERS_PLAN I2).
        long shared=(long)77<<10*WEST.ordinal()|(long)77<<10*EAST.ordinal();
        assertEquals(1,islands(List.of(tank(1,-1,0),pipe(10,0,0,ALONG_X),lane(11,1,0,2,1,ALONG_X,shared),lane(12,2,0,3,1,ALONG_X,shared),pipe(13,3,0,ALONG_X),tank(2,4,0)),1,2),
                "lanes of other options meet where their points coincide");
        assertEquals(2,islands(List.of(tank(1,0,0),lane(11,1,0,2,1,ALONG_X),lane(12,2,0,2,1,ALONG_X),pipe(13,3,0,ALONG_X),tank(2,4,0)),1,2),
                "a lane meets a tank only through a plain pipe");
    }

    @Test void parallelLanesInOneRackStayApart() {
        var devices=new ArrayList<PhysicalFluidTopology.Device>(List.of(tank(1,-1,0),pipe(10,0,0,ALONG_X),pipe(13,3,0,ALONG_X),tank(2,4,0)));
        // A second line on another slot of the same rack cells, open at both ends.
        for(int x=1;x<=2;x++){devices.add(lane(10+x,x,0,1,1,ALONG_X));devices.add(lane(20+x,x,0,3,2,ALONG_X));}
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1),2L,reservoir(2)));
        assertEquals(1,compiled.islands().size(),"the open lane pair reaches no tank: "+compiled.diagnostics());
        assertTrue(compiled.islands().getFirst().physicalIds().containsAll(List.of(1L,10L,11L,12L,13L,2L)));
        assertFalse(compiled.islands().getFirst().physicalIds().contains(21L));
        assertTrue(compiled.diagnostics().get(21L).startsWith("NO FLOW"),compiled.diagnostics().toString());
    }

    /**
     * A lane meets a plain cell only through a transition pipe (plan 6): a laid pipe of two joints at most and no fitting,
     * whose run can bend to the lane's off-centre point. A hand-placed pipe, a tee and a valve beside the rack meet none.
     */
    @Test void aLaneMeetsOnlyATransitionPipe() {
        var valve=new PhysicalFluidTopology.Device(10,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),Kind.PIPE,EAST,new PipeResistance.Geometry(1,.05,.000045,0),
                new FlowControl.Passive(),List.of(),true,PhysicalFluidTopology.Cutoff.OPEN,false,ALONG_X);
        for(var beside:List.of(pipe(10,0,0,PhysicalFluidTopology.ALL_FACES),pipe(10,0,0,ALONG_X|1<<UP.ordinal()),valve))
            assertEquals(2,islands(List.of(tank(1,-1,0),beside,lane(11,1,0,2,1,ALONG_X),pipe(12,2,0,ALONG_X),tank(2,3,0)),1,2),"beside the rack: "+beside);
        assertEquals(1,islands(List.of(tank(1,0,1),pipe(10,0,0,1<<SOUTH.ordinal()|1<<EAST.ordinal()),lane(11,1,0,2,1,ALONG_X),pipe(12,2,0,ALONG_X),tank(2,3,0)),1,2),
                "an elbow into the rack meets the lane");
    }

    @Test void aLaneIsAPlainPipeOfAtMostTwoJointsWithinTheRack() {
        assertThrows(IllegalArgumentException.class,()->lane(1,0,0,2,1,ALONG_X|1<<UP.ordinal()),"a junction in a rack");
        assertThrows(IllegalArgumentException.class,()->lane(1,0,0,4,2,ALONG_X),"past the fourth position");
        assertThrows(IllegalArgumentException.class,()->lane(1,0,0,13,2,ALONG_X),"a crossing wider than one position");
        assertThrows(IllegalArgumentException.class,()->lane(1,0,0,2,5,ALONG_X),"wider than the tier");
        assertThrows(IllegalArgumentException.class,()->lane(1,0,0,0,1,ALONG_X),"a width without a lane");
        assertThrows(IllegalArgumentException.class,()->lane(1,0,0,2,0,ALONG_X),"a lane without a width");
        assertThrows(IllegalArgumentException.class,()->lane(1,0,0,2,1,PhysicalFluidTopology.ALL_FACES),"automatic joints");
        assertThrows(IllegalArgumentException.class,()->lane(1,0,0,0,0,ALONG_X,1),"points without a lane");
        assertThrows(IllegalArgumentException.class,()->lane(1,0,0,2,1,ALONG_X,1L<<60),"points past the six faces");
        assertThrows(IllegalArgumentException.class,()->new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0,PhysicalFluidTopology.RACK_PLACES+1));
        var copied=lane(1,0,0,14,1,ALONG_X).withGeometry(new PipeResistance.Geometry(1,.1,.000045,0));
        assertEquals(1,copied.slots());assertEquals(14,copied.position().lane());assertEquals(14*8+1,copied.point(WEST));
    }

    /** Facts schema 2 writes the joints after the revision; the schema-1 corpus image (no joints field) reads every face. */
    @Test void jointsRoundTripThroughTheFacts() {
        for(var device:List.of(pipe(7,3,0,ALONG_X|1<<UP.ordinal()),pipe(7,3,0,PhysicalFluidTopology.ALL_FACES),pipe(7,3,0,0),lane(7,3,0,3,2,ALONG_X),lane(7,3,0,13,1,ALONG_X))) {
            var laid=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG),4);
            var bytes=FluidFacts.events(List.of(new WorldTopologyLedger.Event(UUID.randomUUID(),12,List.of(new WorldTopologyLedger.Edit(7,laid)),Set.of(7L))));
            assertEquals(laid,FluidFacts.events(bytes,0,bytes.length).getFirst().edits().getFirst().replacement());
        }
    }
}
