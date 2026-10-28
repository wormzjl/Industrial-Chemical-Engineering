package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.state.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** What a pipe, a sight glass and a mover show the player: inspection rates, sight-glass haze and bubbles, pump status, running sound. */
class PipePresentationTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry BORE=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    private PhysicalFluidTopology.Device device(long id,int x,int y,int z,Kind kind){
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),PhysicalFluidTopology.Direction.EAST,BORE,new FlowControl.Passive());
    }
    private PassiveNetwork.Reservoir boundary(PhysicalFluidTopology.Device d,double pressure,int component){
        double[] n=new double[model.components().size()];n[component]=1;
        var unit=model.flashTP(350,pressure,n,()->{});n[component]/=unit.volume();
        return new PassiveNetwork.Reservoir(d.id(),d.position().y(),model.flashTP(350,pressure,n,()->{}),
            d.kind()==Kind.GENERATOR?PassiveNetwork.NodeKind.GENERATOR:PassiveNetwork.NodeKind.VOID);
    }
    @Test void moverDisplayUsesOnlyItsAcceptedDischargeOnEitherFacing(){
        for(var kind:List.of(Kind.PUMP,Kind.COMPRESSOR))for(var facing:List.of(PhysicalFluidTopology.Direction.EAST,PhysicalFluidTopology.Direction.WEST)){
            var source=device(1,-2,0,0,Kind.GENERATOR);var sink=device(2,2,0,0,Kind.VOID);
            FlowControl control=kind==Kind.PUMP?new FlowControl.Pump(.01,500000):new FlowControl.Compressor(.05,3);
            var mover=new PhysicalFluidTopology.Device(100,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),facing,BORE,control);
            var compiled=PhysicalFluidTopology.compile(List.of(source,device(3,-1,0,0,Kind.PIPE),mover,device(4,1,0,0,Kind.PIPE),sink),Map.of(1L,boundary(source,150000,0),2L,boundary(sink,101325,0)));
            var graph=compiled.islands().getFirst().graph();var mappings=PipePresentation.actuatorMappings(mover,graph);assertEquals(1,mappings.size());
            var discharge=graph.pipes().stream().filter(p->p.id()==mappings.getFirst().pipeId()).findFirst().orElseThrow();
            assertEquals(mover.id(),graph.reservoirs().get(discharge.first()).id());assertEquals(control,discharge.control());
            var history=graph.pipes().stream().map(p->new PipeTransfer(p.id(),moved(p.id()==discharge.id()?2:9,.25,0),moved(0,0,0))).toList();
            var info=PipePresentation.inspect(mover,graph,mappings,history,2,model.components().size());
            assertFalse(info.junction());assertEquals(1,info.connections().size());assertEquals(2,info.contents().massKg());assertEquals(1,info.connections().getFirst().massRateKgPerSecond());
        }
    }
    /** Actual block compiler, including vertical connections, with two nitrogen feeds and 2–4 outlets. */
    @Test void fourFiveAndSixEquipmentConnectionsBalanceWithoutDuplicatingDisplayedThroughput(){
        int[][] ports={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{0,1,0},{0,-1,0}};
        for(int count=4;count<=6;count++){
            var center=device(100,0,0,0,Kind.PIPE);var devices=new ArrayList<PhysicalFluidTopology.Device>();devices.add(center);
            var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();
            for(int i=0;i<count;i++){
                int[] port=ports[i];var d=device(i+1,port[0],port[1],port[2],i<2?Kind.GENERATOR:Kind.VOID);devices.add(d);
                boundaries.put(d.id(),boundary(d,i<2?102325:101325,MaterialTestBasis.NITROGEN));
            }
            var compiled=PhysicalFluidTopology.compile(devices,boundaries);
            assertEquals(1,compiled.islands().size());var graph=compiled.islands().getFirst().graph();
            assertEquals(count,graph.pipes().size());assertEquals(count,compiled.pipeViews().get(100L).size());
            int node=-1;for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==100)node=i;
            assertTrue(node>=0);assertTrue(graph.reservoirs().get(node).junction());
            var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
            var accepted=result.graph();double outgoing=0,incoming=0;int inlets=0,outlets=0;
            double[] inComponents=new double[model.components().size()],outComponents=new double[model.components().size()];
            for(var t:result.pipeTransfers()){
                var edge=accepted.pipes().stream().filter(e->e.id()==t.pipeId()).findFirst().orElseThrow();
                var out=edge.first()==node?t.forward():t.reverse();var in=edge.first()==node?t.reverse():t.forward();
                outgoing+=out.massKg();incoming+=in.massKg();if(out.massKg()>1e-12)outlets++;if(in.massKg()>1e-12)inlets++;
                double[] a=in.componentMoles(),b=out.componentMoles();for(int c=0;c<a.length;c++){inComponents[c]+=a[c];outComponents[c]+=b[c];}
            }
            assertEquals(2,inlets,"two sources at "+count+" ports");assertEquals(count-2,outlets);
            assertEquals(incoming,outgoing,Math.max(1e-8,incoming*1e-7));
            for(int c=0;c<inComponents.length;c++)assertEquals(inComponents[c],outComponents[c],Math.max(1e-7,inComponents[c]*1e-6),"component "+c+" at "+count+" ports");
            var info=PipePresentation.inspect(center,accepted,compiled.pipeViews().get(100L),result.pipeTransfers(),result.advancedSeconds(),model.components().size());
            assertTrue(info.junction());assertEquals(count,info.connections().size());
            assertEquals(outgoing,info.contents().massKg(),1e-12,"do not add inbound and outbound twice");
            assertTrue(info.contents().componentMoles()[MaterialTestBasis.NITROGEN]>0);
            assertTrue(info.connections().stream().allMatch(c->Double.isFinite(c.velocityMetresPerSecond())&&c.velocityMetresPerSecond()>0));
        }
    }
    private PipeTransfer.Stream moved(double mass,double volume,int component){
        double[][] n=new double[3][model.components().size()];n[2][component]=mass/model.molecularWeights()[component];
        return new PipeTransfer.Stream(mass,n,new double[]{0,0,volume});
    }
    @Test void aStraightPipeAutomaticallyShowsReverseFlowAndPreservesBothDirectionsOfAReversal(){
        var a=device(1,-1,0,0,Kind.GENERATOR);var b=device(2,1,0,0,Kind.VOID);var center=device(100,0,0,0,Kind.PIPE);
        var graph=new PassiveNetwork(List.of(boundary(a,150000,0),boundary(b,101325,0)),List.of(new PassiveNetwork.Pipe(9,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        var mappings=List.of(new PhysicalFluidTopology.View(0,9,true));var zero=moved(0,0,0);
        var reverse=PipePresentation.inspect(center,graph,mappings,List.of(new PipeTransfer(9,zero,moved(2,.5,0))),2,model.components().size());
        assertFalse(reverse.junction());assertTrue(reverse.connections().getFirst().reverse());assertEquals(2,reverse.contents().massKg());
        assertEquals(.5/2/BORE.area(),reverse.connections().getFirst().velocityMetresPerSecond(),1e-12);
        assertEquals((150000-101325)/10.0,reverse.connections().getFirst().pressureDropPascalPerMetre(),1e-10);
        var mixed=PipePresentation.inspect(center,graph,mappings,List.of(new PipeTransfer(9,moved(1,.25,0),moved(2,.5,MaterialTestBasis.NITROGEN))),2,model.components().size());
        assertTrue(mixed.changedDirection());assertEquals(3,mixed.contents().massKg());assertTrue(mixed.contents().componentMoles()[0]>0);
        assertTrue(mixed.contents().componentMoles()[MaterialTestBasis.NITROGEN]>0);
    }
    @Test void anUnsolvedPipeHasZeroTransportButStillHasAPressureReading(){
        var a=device(1,-1,0,0,Kind.GENERATOR);var b=device(2,1,0,0,Kind.VOID);var center=device(100,0,0,0,Kind.PIPE);
        var graph=new PassiveNetwork(List.of(boundary(a,150000,0),boundary(b,101325,0)),List.of(new PassiveNetwork.Pipe(9,0,1,BORE)));
        var info=PipePresentation.inspect(center,graph,List.of(new PhysicalFluidTopology.View(0,9,true)),List.of(),0,model.components().size());
        assertEquals(0,info.contents().massKg());assertEquals(0,info.connections().getFirst().velocityMetresPerSecond());
        assertEquals(48675,info.connections().getFirst().pressureDropPascalPerMetre(),1e-10);
    }

    // ---- Sight glass ---------------------------------------------------------------------------------------------

    private static final MaterialCatalog CATALOG=MaterialCatalog.bundled();
    private static final String PACKAGE="icheme:tjl20_methane";
    private static final List<String> COMPONENTS=List.of("crude_pc01","crude_pc12","Water");
    private static final double[] WEIGHTS={.1,.6,.018};
    private static final SightGlassGeometry.Motion UP=new SightGlassGeometry.Motion(0,1);

    @Test void gasLiquidRatioControlsBubbleDensityAndOtherPhasesRetainHaze(){
        assertEquals(0,display(new double[]{1,0,0},SolidInventory.EMPTY,1).bubbles(),"two components in one liquid stay homogeneous");
        for(double[] volumes:List.of(new double[]{.5,0,.5},new double[]{.4,.4,.2})){
            var sample=display(volumes,SolidInventory.EMPTY,1);assertTrue(sample.bubbles()>0);assertTrue(sample.argb()>>>24>=200);
        }
        var solids=new SolidInventory(List.of(new SolidInventory.Population(CATALOG.solids().require("icheme:demo_particle"),ParticleSize.micrometres("100"),.25)));
        assertEquals(0,display(new double[]{1,0,0},solids,1).bubbles(),"suspended solids cause haze, not gas bubbles");
        assertEquals(0,display(new double[]{.5,.5,0},SolidInventory.EMPTY,1).bubbles(),"two liquids retain haze without gas bubbles");
        int previous=0,previousCount=0;
        for(double gas:new double[]{.01,.1,1,9,99}){
            var sample=display(new double[]{.4,.6,gas},SolidInventory.EMPTY,1);
            assertTrue(sample.bubbles()>previous,"gas-rich flow must not lose bubbles as haze declines");previous=sample.bubbles();
            int count=SightGlassGeometry.bubbles(100,9,sample.bubbles(),UP).size();assertTrue(count>=previousCount);previousCount=count;
            assertEquals(sample.bubbles(),display(new double[]{4,6,10*gas},SolidInventory.EMPTY,1).bubbles(),"ratio is independent of total volume");
            assertEquals(sample.bubbles(),display(new double[]{.4,.6,gas},solids,1).bubbles(),"solids do not dilute the gas/liquid ratio");
        }
        assertEquals(4,SightGlassGeometry.bubbles(100,9,display(new double[]{1,0,1},SolidInventory.EMPTY,1).bubbles(),UP).size());
        assertEquals(8,SightGlassGeometry.bubbles(100,9,display(new double[]{1,0,99},SolidInventory.EMPTY,1).bubbles(),UP).size());
        assertEquals(PipePresentation.SightGlass.CLEAR,display(new double[]{.5,0,.5},SolidInventory.EMPTY,0),"old stream with stopped speed clears");
        assertEquals(PipePresentation.SightGlass.CLEAR,display(new double[]{0,0,1},SolidInventory.EMPTY,1));
        assertEquals(PipePresentation.SightGlass.CLEAR,display(new double[3],SolidInventory.EMPTY,1));
        assertEquals(PipePresentation.SightGlass.CLEAR,PipePresentation.sightGlass(base(),COMPONENTS,WEIGHTS,CATALOG,PACKAGE,List.of()));
    }

    @Test void displayWireRoundTripsAndRejectsMotionWithoutLiquid(){
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        var target=new FluidNetwork.Target(new BlockPos(-20,64,44),9);
        try{
            var samples=new ArrayList<PipePresentation.SightGlass>();
            samples.add(PipePresentation.SightGlass.CLEAR);samples.add(new PipePresentation.SightGlass(0xC8A17A40,0,null));
            for(var direction:PhysicalFluidTopology.Direction.values())samples.add(new PipePresentation.SightGlass(0xFF9E9987,255,direction));
            for(var sample:samples){
                var payload=new FluidNetwork.SightGlassPayload(target,sample);FluidNetwork.SightGlassPayload.STREAM_CODEC.encode(buffer,payload);
                assertEquals(payload,FluidNetwork.SightGlassPayload.STREAM_CODEC.decode(buffer));
            }
            buffer.clear();buffer.writeBlockPos(target.position());buffer.writeLong(target.identity());buffer.writeInt(0);buffer.writeByte(1);buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class,()->FluidNetwork.SightGlassPayload.STREAM_CODEC.decode(buffer));
            for(int code:new int[]{7,255}){
                buffer.clear();buffer.writeBlockPos(target.position());buffer.writeLong(target.identity());buffer.writeInt(0xFF123456);buffer.writeByte(1);buffer.writeByte(code);
                assertThrows(IllegalArgumentException.class,()->FluidNetwork.SightGlassPayload.STREAM_CODEC.decode(buffer));
            }
            assertThrows(IllegalArgumentException.class,()->new PipePresentation.SightGlass(0xFF123456,-1,null));
            assertThrows(IllegalArgumentException.class,()->new PipePresentation.SightGlass(0xFF123456,256,PhysicalFluidTopology.Direction.EAST));
            assertThrows(IllegalArgumentException.class,()->new PipePresentation.SightGlass(0xFF123456,1,null));
        }finally{buffer.release();}
    }

    @Test void motionIsStableContainedAndBetweenTheLiquidAndGlass(){
        assertEquals(List.of(),SightGlassGeometry.bubbles(100,9,0,UP));
        assertEquals(SightGlassGeometry.bubbles(100,9,255,UP),SightGlassGeometry.bubbles(100,9,255,UP));
        assertNotEquals(SightGlassGeometry.bubbles(100,9,255,UP),SightGlassGeometry.bubbles(110,9,255,UP));
        for(int strength:new int[]{1,100,255})for(long seed:new long[]{0,9,Long.MAX_VALUE})for(int tick=0;tick<400;tick+=13)
            for(var bubble:SightGlassGeometry.bubbles(tick,seed,strength,UP)){
                assertTrue(bubble.radius()>=0&&Double.isFinite(bubble.radius()));
                assertTrue(Math.hypot(bubble.x(),bubble.y())+bubble.radius()<=.971,"entire ring fits inside the aperture");
            }
        for(var axis:SightGlassGeometry.Axis.values())for(double bore:new double[]{.001,.05,1}){
            int face=axis==SightGlassGeometry.Axis.X?2:5;
            var key=new SightGlassGeometry.Key(new LineGeometry.Key(Kind.PIPE,face,3<<(face&~1),PipeSizes.code(PipeSizes.outside(bore)),0,0,false),axis);
            var window=SightGlassGeometry.window(key);var geometry=SightGlassGeometry.build(key,false);int c=axis.ordinal();
            double liquid=0,glass=1;
            for(var p:geometry.liquid())for(int i=c;i<p.positions().length;i+=3)liquid=Math.max(liquid,Math.abs(p.positions()[i]-.5));
            for(var p:geometry.glass())for(int i=c;i<p.positions().length;i+=3)glass=Math.min(glass,Math.abs(p.positions()[i]-.5));
            assertTrue(window.plane()>liquid&&window.plane()<glass,"rings have distinct depth behind the window");
        }
    }

    @Test void acceptedDirectionReversesAndMissingLocalMappingsDoNotInventMotion(){
        for(var direction:PhysicalFluidTopology.Direction.values())for(boolean reverse:new boolean[]{false,true}){
            var info=new FluidView.PipeInfo(new PipeTransfer.Stream(0,new double[3][3],new double[3]),List.of(new FluidView.PipeConnection(7,1,1,0,reverse)),false,false);
            var mapping=new PhysicalFluidTopology.View(0,7,true,.5,direction);
            assertEquals(reverse?PhysicalFluidTopology.opposite(direction):direction,PipePresentation.flowDirection(info,List.of(mapping)));
            assertNull(PipePresentation.flowDirection(info,List.of(new PhysicalFluidTopology.View(0,7,true,Double.NaN,direction))));
            assertNull(PipePresentation.flowDirection(info,List.of(new PhysicalFluidTopology.View(0,7,true))));
        }
    }

    @Test void bubbleTravelFollowsEveryRunAndAgreesThroughBothWindows(){
        for(var window:SightGlassGeometry.Axis.values())for(var run:SightGlassGeometry.Axis.values())if(run!=window)
            for(boolean positive:new boolean[]{false,true}){
                var motion=SightGlassGeometry.Motion.along(window,run,positive);
                var before=SightGlassGeometry.bubbles(50,9,255,motion);var after=SightGlassGeometry.bubbles(50.001,9,255,motion);
                for(int i=0;i<before.size();i++){
                    double[] front=world(window,before.get(i),false),back=world(window,before.get(i),true),next=world(window,after.get(i),false);
                    assertArrayEquals(front,back,1e-12,"paired windows show the same physical bubble path");
                    assertTrue((next[run.ordinal()]-front[run.ordinal()])*(positive?1:-1)>0,"bubble advances in accepted flow direction");
                    assertTrue(Math.hypot(after.get(i).x(),after.get(i).y())+after.get(i).radius()<=.971);
                }
            }
    }
    private static double[] world(SightGlassGeometry.Axis window,SightGlassGeometry.Bubble bubble,boolean back){
        double x=back?-bubble.x():bubble.x(),y=bubble.y();
        // The renderer turns the back face 180 degrees about local Y and mirrors its bubble centre.
        if(back)x=-x;
        return switch(window){case X->new double[]{0,y,-x};case Y->new double[]{x,0,-y};case Z->new double[]{x,y,0};};
    }

    private static PipePresentation.SightGlass display(double[] volumes,SolidInventory solids,double speed){
        double[][] n=new double[3][3];n[0][0]=volumes[0];n[0][1]=volumes[0];n[1][2]=volumes[1];n[2][0]=volumes[2];
        var stream=new PipeTransfer.Stream(Arrays.stream(volumes).sum(),n,volumes,solids);
        var info=new FluidView.PipeInfo(stream,List.of(new FluidView.PipeConnection(7,1,speed,0,false)),false,false);
        return PipePresentation.sightGlass(base().withPipeInfo(info,1),COMPONENTS,WEIGHTS,CATALOG,PACKAGE,List.of(new PhysicalFluidTopology.View(0,7,true,.5,PhysicalFluidTopology.Direction.EAST)));
    }
    private static FluidView base(){return new FluidView(1,0,0,0,"FULL",null,0,List.of(),null,1,List.of(),null);}

    // ---- Pump status and mover sound -------------------------------------------------------------------------------

    private final FlowControl.Pump pump=new FlowControl.Pump(2,500000);

    @Test void operatingLimitsAndRefusalsAreDeviceErrorsOnAnAdvancingIsland() {
        for(var mode:new FlowControl.Mode[]{FlowControl.Mode.PUMP_HEAD_LIMIT,FlowControl.Mode.PUMP_SUCTION_LIMIT,FlowControl.Mode.INLET_WRONG_PHASE,FlowControl.Mode.CLOSED}) {
            String status=PipePresentation.pumpStatus("FULL",pump,mode,"ERROR: pump inlet is not liquid (from generator)");
            assertTrue(status.startsWith("ERROR: pump"));assertTrue(status.endsWith(" / FULL"));assertFalse(status.contains("HELD"));
        }
        assertTrue(PipePresentation.pumpStatus("FULL",pump,FlowControl.Mode.PUMP_HEAD_LIMIT,"").contains("requested flow unavailable"));
        String inlet="ERROR: pump inlet is not liquid (from generator)";
        assertEquals(inlet+" / FULL",PipePresentation.pumpStatus("FULL",pump,FlowControl.Mode.INLET_WRONG_PHASE,inlet));
    }

    @Test void offIsNormalAndPresentationDoesNotHideARealSolverFailure() {
        assertFalse(PipePresentation.pumpStatus("FULL",pump,FlowControl.Mode.PUMP_TARGET,"").contains("ERROR:"));
        assertFalse(PipePresentation.pumpStatus("FULL",new FlowControl.Pump(2,500000,false),FlowControl.Mode.CLOSED,"").contains("ERROR:"));
        assertTrue(PipePresentation.pumpStatus("HELD: numerical failure",pump,FlowControl.Mode.PUMP_HEAD_LIMIT,"").contains("HELD: numerical failure"));
    }

    private static final EquipmentShape.Kind[] MOVERS={EquipmentShape.Kind.PUMP,EquipmentShape.Kind.COMPRESSOR,EquipmentShape.Kind.CENTRIFUGAL_COMPRESSOR};
    @Test void soundGainIsBoundedAndIncreasesWithConstructorDiameterAndStageOrThrowCount(){
        for(var kind:MOVERS){
            var design=UnitDesign.of(kind);float previous=0;
            int steps=(int)Math.round((design.maxSize()-design.minSize())/design.sizeStep());
            for(int i=0;i<=steps;i++){
                double size=Math.round((design.minSize()+i*design.sizeStep())*1e9)/1e9;
                float gain=UnitDesign.soundGain(shape(kind,size,design.minLength(size),EquipmentShape.Heading.NORTH));
                assertTrue(gain>=.10F&&gain<=.60F);assertTrue(gain>previous);previous=gain;
            }
            assertEquals(.10F,UnitDesign.soundGain(shape(kind,design.minSize(),design.minLength(design.minSize()),EquipmentShape.Heading.NORTH)),1e-6);
            assertEquals(.60F,UnitDesign.soundGain(shape(kind,design.maxSize(),design.maxLength(design.maxSize()),EquipmentShape.Heading.NORTH)),1e-6);
            double middle=Math.round((design.minSize()+design.maxSize())/2/design.sizeStep())*design.sizeStep();previous=0;
            for(int count=design.minLength(middle);count<=design.maxLength(middle);count+=design.lengthStep()){
                float gain=UnitDesign.soundGain(shape(kind,middle,count,EquipmentShape.Heading.NORTH));assertTrue(gain>previous);previous=gain;
            }
        }
    }
    @Test void foundationAndHeadingDoNotMakeTheMachineLouder(){
        for(var kind:MOVERS){
            var design=UnitDesign.of(kind);double size=design.minSize();int count=design.minLength(size);
            var original=shape(kind,size,count,EquipmentShape.Heading.NORTH);
            var raised=EquipmentShape.unit(kind,size,count,EquipmentShape.Heading.SOUTH,design.maxSupport());
            assertEquals(UnitDesign.soundGain(original),UnitDesign.soundGain(raised));
        }
        assertEquals(0,UnitDesign.soundGain(EquipmentShape.drum(2,4,EquipmentShape.Orientation.VERTICAL,EquipmentShape.Heading.NORTH,4)));
        // The pump range ends at 0.6 m (its suction a pipe's largest, 0.8 m): a 0.4 m pump sits 0.35 / 0.55 along it.
        assertEquals(.10+.50*.35/.55,UnitDesign.soundGain(shape(EquipmentShape.Kind.PUMP,.4,1,EquipmentShape.Heading.NORTH)),1e-6);
    }
    private static EquipmentShape shape(EquipmentShape.Kind kind,double size,int count,EquipmentShape.Heading front){return EquipmentShape.unit(kind,size,count,front,UnitDesign.of(kind).defaultSupport(size,count));}
}
