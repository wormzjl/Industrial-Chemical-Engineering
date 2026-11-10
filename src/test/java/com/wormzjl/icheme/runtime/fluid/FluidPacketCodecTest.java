package com.wormzjl.icheme.runtime.fluid;

import com.google.gson.Gson;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidPacketCodecTest {
    @Test void basinDisplayPacketsRetainLevelAndAcceptedFanSpeed(){
        var from=new TankDisplay(.5f,.5f,2,0,0xff77aacc,.2f);
        var to=new TankDisplay(1,1,2,0,0xff77aacc,.8f);
        var packet=new FluidNetwork.TankDisplayPayload(new BlockPos(1,64,2),new TankDisplay.Glide(from,to,100));
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            FluidNetwork.TankDisplayPayload.STREAM_CODEC.encode(buffer,packet);
            assertEquals(packet,FluidNetwork.TankDisplayPayload.STREAM_CODEC.decode(buffer));
            assertEquals(.5,packet.glide().at(100+FluidPresentation.BUCKET_TICKS/2.0).fanSpeed(),1e-6);
        }finally{buffer.release();}
    }
    /** The live payload a menu receives for {@code data} when no input reply is pending. */
    private static FluidNetwork.LiveData live(FluidNetwork.MenuData data){return new FluidNetwork.LiveData(data.view(),data.controls(),data.message());}
    private static List<Double> weights(int count){return java.util.stream.IntStream.range(0,count).mapToObj(i->0.01+i*0.002).toList();}
    @Test void editWirePreservesItsExactMenuPositionIdentityAndRevisionAndRejectsOversizedText() {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            var payload=new FluidNetwork.EditPayload(5,new BlockPos(-30,80,400),Long.MAX_VALUE-1,37,"{\"pressure\":200000}");
            FluidNetwork.EditPayload.STREAM_CODEC.encode(buffer,payload);assertEquals(payload,FluidNetwork.EditPayload.STREAM_CODEC.decode(buffer));
            assertThrows(RuntimeException.class,()->FluidNetwork.EditPayload.STREAM_CODEC.encode(buffer,new FluidNetwork.EditPayload(5,BlockPos.ZERO,1,0,"x".repeat(FluidNetwork.MAX_EDIT_JSON+1))));
        } finally {buffer.release();}
    }
    @Test void nonfiniteOutOfDomainAndMalformedCompositionControlsCannotReachAnApply() {
        double[] n=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=1;
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(Double.NaN,101325,.05,.000045,.01,500000,n));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,Double.POSITIVE_INFINITY,.05,.000045,.01,500000,n));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,-1,.05,.000045,.01,500000,n));
        // The input box is the property package's envelope (F4; plan decision 9): a structurally valid 12 MPa reaches the
        // server, whose edit handler refuses it against the envelope before anything is queued.
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        var outside=new FluidNetwork.Controls(298.15,12000000,.05,.000045,.01,500000,n);
        assertThrows(IllegalArgumentException.class,()->new FluidDeviceSpec(1,outside.temperature(),outside.pressure(),outside.composition()).validate(model,TopologyCompiler.Kind.GENERATOR));
        assertThrows(IllegalArgumentException.class,()->model.domain().requireInputPressure(outside.pressure()));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,new double[23]));
        var accepted=new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,n);n[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=0;assertEquals(1,accepted.composition()[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]);
    }
    @Test void receivedComponentAxisDoesNotDependOnTheClientCatalog() {
        double[] amounts=new double[65];amounts[64]=1;
        var controls=new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,amounts);
        var names=new ArrayList<String>();for(int i=0;i<65;i++)names.add("server_"+i);
        var descriptor=new com.wormzjl.icheme.science.material.MaterialName("server_0","server.cut","Crude oil","petroleum_fraction",800.,null,true);
        var view=new FluidView(1,0,0,0,"READY",null,0,List.of());
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.GENERATOR,view,controls,names,List.of(),"",Map.of("server_0",descriptor),weights(names.size()));
        var json=new Gson();var decoded=json.fromJson(json.toJson(data),FluidNetwork.MenuData.class);
        assertEquals(names,decoded.components());assertEquals(descriptor,decoded.materialNames().get("server_0"));assertArrayEquals(amounts,decoded.controls().composition());
        double[] oversized=new double[com.wormzjl.icheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS+1];oversized[0]=1;
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,oversized));
    }
    @Test void maximumAdmittedAxisAndPresetCountsFitThePacketBudget() {
        int count=com.wormzjl.icheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS;
        double[][] n=new double[3][count];for(var phase:n)Arrays.fill(phase,1.2345678901234567e-123);
        var history=new ArrayList<PipeTransfer>();for(int i=0;i<12;i++)history.add(new PipeTransfer(i+1,new PipeTransfer.Stream(1,n,new double[]{1,2,3}),new PipeTransfer.Stream(1,n,new double[]{1,2,3})));
        var ids=new ArrayList<String>();var descriptors=new HashMap<String,com.wormzjl.icheme.science.material.MaterialName>();
        for(int i=0;i<count;i++){String id="server_"+i;ids.add(id);descriptors.put(id,new com.wormzjl.icheme.science.material.MaterialName(id,"t".repeat(128),"l".repeat(128),"lump",null,null,false));}
        double[] amounts=new double[count];Arrays.fill(amounts,1.0/count);var presets=new ArrayList<FluidPresetCatalog.Preset>();
        for(int i=0;i<32;i++)presets.add(new FluidPresetCatalog.Preset("p".repeat(120)+i,"n".repeat(128),amounts));
        // The widest view (V2 stage 5): the state, the pipe's contents, 12 history pairs and a heat unit's 8 ports, 34 three-phase vectors.
        var ports=new ArrayList<FluidView.Port>();for(int i=0;i<FluidView.Heat.MAXIMUM_PORTS;i++)ports.add(new FluidView.Port("PORT_"+i,300.,1e5,1,n,new double[]{1,2,3}));
        var heat=new FluidView.Heat("unit","side",true,300,1,null,null,null,null,null,null).with("none",null,"NONE",ports,null);
        var view=new FluidView(1,0,0,0,"READY",new FluidView.State(101325,298.15,1,1,new double[]{1,2,3},n,SolidInventory.EMPTY,1),0,history).withHeat(heat);
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,view.withPipeInfo(new FluidView.PipeInfo(new PipeTransfer.Stream(1,n,new double[]{1,2,3}),List.of(),false,false,null)),new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,amounts),ids,presets,"",descriptors,weights(ids.size()));
        // Only the two parts travel (fluid-6); the whole menu data never goes on the wire as one message.
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            // fluid-6: the static part (axis, presets, names) and the live part (view, controls, reply) each fit on their own.
            String fixed=new Gson().toJson(data.staticData(7)),live=new Gson().toJson(live(data));
            assertTrue(fixed.length()<FluidNetwork.MAX_JSON&&live.length()<FluidNetwork.MAX_JSON,"static "+fixed.length()+", live "+live.length());
            var s=new FluidNetwork.StaticPayload(1,1,fixed);FluidNetwork.StaticPayload.STREAM_CODEC.encode(buffer,s);var l=new FluidNetwork.LivePayload(1,1,live);FluidNetwork.LivePayload.STREAM_CODEC.encode(buffer,l);
            assertTrue(buffer.readableBytes()<1048576);assertEquals(s,FluidNetwork.StaticPayload.STREAM_CODEC.decode(buffer));assertEquals(l,FluidNetwork.LivePayload.STREAM_CODEC.decode(buffer));
            var joined=FluidNetwork.MenuData.of(FluidNetwork.decodeStatic(fixed),FluidNetwork.decodeLive(live));
            assertEquals(ids,joined.components());assertEquals(32,joined.presets().size());assertEquals(count,joined.materialNames().size());
        }
        finally{buffer.release();}
    }
    @Test void aFullBranchHistoryFitsTheBoundedClientWireAndSurvivesTheActualJsonSchema() {
        double[][] amounts=new double[3][com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];for(int p=0;p<3;p++)for(int c=0;c<com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1;c++)amounts[p][c]=(p+1)*(c+1)*1e-6;
        var history=new ArrayList<PipeTransfer>();for(int i=0;i<12;i++)history.add(new PipeTransfer(i+1,new PipeTransfer.Stream(12.34,amounts,new double[]{.01,.02,.03}),new PipeTransfer.Stream(2.34,amounts,new double[]{.001,.002,.003})));
        var view=new FluidView(42,7,200,250,"FULL",new FluidView.State(101325,298.15,1,50,new double[]{.1,.2,.7},amounts,SolidInventory.EMPTY,1),.25,history);
        var names=new ArrayList<String>();for(int i=0;i<com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1;i++)names.add("component_"+i);
        var controls=new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,FluidDeviceSpec.nitrogen().composition());
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,view.withPipeInfo(FluidView.PipeInfo.empty(view.pipeHistory().getFirst().forward().componentMoles().length)),controls,names,List.of(),"",Map.of(),weights(names.size()));var gson=new Gson();String json=gson.toJson(data);assertTrue(json.length()<FluidNetwork.MAX_JSON);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            String live=gson.toJson(live(data));assertTrue(live.length()<FluidNetwork.MAX_JSON);
            var packet=new FluidNetwork.LivePayload(3,42,live);FluidNetwork.LivePayload.STREAM_CODEC.encode(buffer,packet);var decoded=FluidNetwork.LivePayload.STREAM_CODEC.decode(buffer);
            assertEquals(packet,decoded);var roundTrip=FluidNetwork.MenuData.of(data.staticData(0),FluidNetwork.decodeLive(decoded.json()));
            assertEquals(12,roundTrip.view().pipeHistory().size());assertArrayEquals(amounts[2],roundTrip.view().pipeHistory().get(11).reverse().phaseMoles()[2]);assertEquals(250,roundTrip.view().onlineTick());
        } finally {buffer.release();}
    }
    @Test void tankCapacityAndBothPressuresRoundTripAndMalformedReadingsAreRejected() {
        var base=generatorMenu("");int count=base.components().size();double[][] amounts=new double[3][count];amounts[0][0]=1000;
        var state=new FluidView.State(200000,298.15,1,10,new double[]{1,0,0},amounts,SolidInventory.EMPTY,10);
        var tank=new FluidView.Tank(5,false,0,0,10,200000.0,200049.03325);
        var view=new FluidView(9,4,1200,1300,"READY",state,0,List.of()).withTank(tank);
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.RESERVOIR,view,base.controls(),base.components(),List.of(),"",base.materialNames(),base.molecularWeights());
        var gson=new Gson();String json=gson.toJson(live(data));var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            var packet=new FluidNetwork.LivePayload(3,9,json);FluidNetwork.LivePayload.STREAM_CODEC.encode(buffer,packet);
            var decoded=FluidNetwork.LivePayload.STREAM_CODEC.decode(buffer);
            var joined=FluidNetwork.MenuData.of(data.staticData(4),FluidNetwork.decodeLive(decoded.json()));assertEquals(tank,joined.view().tank());
            var missing=com.google.gson.JsonParser.parseString(json).getAsJsonObject();missing.getAsJsonObject("view").getAsJsonObject("tank").remove("capacity");
            var rejected=assertThrows(RuntimeException.class,()->FluidNetwork.decodeLive(missing.toString()));
            assertTrue(FluidNetwork.reason(rejected).contains("Invalid tank presentation"));
            assertThrows(IllegalArgumentException.class,()->new FluidView.Tank(5,false,0,0,10,200000.0,100000.0));
            assertThrows(IllegalArgumentException.class,()->new FluidView.Tank(5,false,0,0,10,null,200000.0));
        }finally{buffer.release();}
    }

    // ---------------- distinct static and live payloads ----------------

    private static FluidNetwork.MenuData generatorMenu(String message) {
        int count=com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1;var names=new ArrayList<String>();for(int i=0;i<count;i++)names.add("component_"+i);
        double[] amounts=new double[count];amounts[0]=1;
        var presets=List.of(new FluidPresetCatalog.Preset("a","First",amounts),new FluidPresetCatalog.Preset("b","Second",amounts));
        var descriptor=new com.wormzjl.icheme.science.material.MaterialName("component_0","component.zero","Zero","lump",null,null,false);
        var view=new FluidView(9,4,1200,1300,"RESTING: no flow since 55.0 s",null,0,List.of());
        return new FluidNetwork.MenuData(TopologyCompiler.Kind.GENERATOR,view,new FluidNetwork.Controls(298.15,150000,.05,.000045,.01,500000,amounts),names,presets,message,Map.of("component_0",descriptor),weights(names.size()));
    }
    /** The protocol is fluid-11 (fluid-6 plus the compressor's ratio control and a tank's outlets, phase-ports WP5; fluid-8
     * adds each outlet's face, GUI fix F4; fluid-9 drops the unread hydraulicOwner and intervalQuality view fields; fluid-10
     * carries a crystal's record with its solid, D23; fluid-11 a vessel's tank presentation, batch
     * 2026-10-21-floating-roof-tanks) and every payload has its own wire identity. A tank's dry ice crosses
     * the live payload as a solid with its crystal record and comes back equal; a crystal made of a component off the
     * menu's axis is refused. fluid-14 adds a tank's capacity and paired pressures; fluid-16 a heat unit's ports, lines and
     * actuator and a pipe's line reading (the heat GUI). */
    @Test void theProtocolIsFluidNineteenWithDistinctStaticAndLivePayloads() {
        assertEquals("fluid-23",FluidNetwork.PROTOCOL);
        var ids=java.util.Set.of(FluidNetwork.EditPayload.TYPE.id(),FluidNetwork.RecoverPayload.TYPE.id(),FluidNetwork.StaticPayload.TYPE.id(),FluidNetwork.LivePayload.TYPE.id());
        assertEquals(4,ids.size());
        var base=generatorMenu("");int count=base.components().size();
        java.util.function.Function<String,FluidNetwork.MenuData> tank=component->{
            var dryIce=new com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial("icheme:crystal_carbon_dioxide_i","crystal-carbon_dioxide-i-r2@icheme:pilot_cryogenic",
                    1571.3607340045514,1254.99435771707,new com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial.Crystal(component,-525945.1827807827));
            var solids=new com.wormzjl.icheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.icheme.science.fluid.state.SolidInventory.Population(dryIce,
                    com.wormzjl.icheme.science.fluid.state.ParticleSize.micrometres("250"),.246103828701677)));
            var state=new FluidView.State(1.2e6,175.5,.1,6.4,new double[]{0,0,.0998},new double[3][count],solids,.1);
            var view=new FluidView(9,4,1200,1300,"",state,0,List.of());
            return new FluidNetwork.MenuData(TopologyCompiler.Kind.RESERVOIR,view,base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights());
        };
        var data=tank.apply("component_3");
        var copy=FluidNetwork.MenuData.of(data.staticData(0),FluidNetwork.decodeLive(new Gson().toJson(live(data))));
        assertEquals(data.view().state().solids(),copy.view().state().solids(),"the dry ice and its crystal record, as sent");
        assertEquals("component_3",copy.view().state().solids().crystals().getFirst().material().crystal().component());
        assertTrue(assertThrows(IllegalArgumentException.class,()->tank.apply("CarbonDioxide")).getMessage().contains("off the fluid axis"));
    }
    /** fluid-18 (W7): a casing's Drive and a fan unit's Power ride the live payload and come back as sent; a bad one is refused. */
    @Test void aCasingsDriveAndAFanUnitsPowerRoundTripTheLivePayload() {
        var base=generatorMenu("");
        var drive=new FluidView.Drive("MOTOR",30000,2,"RUNNING",2950,false,1,24900);
        var power=new FluidView.Power(26700,534,.72,"");
        var view=base.view().withDrive(drive).withPower(power);
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.GENERATOR,view,base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights());
        var copy=FluidNetwork.decodeLive(new Gson().toJson(live(data)));
        assertEquals(drive,copy.view().drive());assertEquals(power,copy.view().power());
        var none=FluidNetwork.decodeLive(new Gson().toJson(live(base)));
        assertNull(none.view().drive());assertNull(none.view().power());
        assertThrows(IllegalArgumentException.class,()->new FluidView.Drive("MOTOR",-1,2,"RUNNING",0,false,0,0));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Power(0,0,1.5,""));
    }
    /**
     * W10d: a pump's readings ride the live payload and come back as sent: a casing's with its curve's design, an in-line
     * pump's with no curve and no reads (null, not NaN); a bad one is refused.
     */
    @Test void aPumpsReadingsRoundTripTheLivePayload() {
        var base=generatorMenu("");
        var casing=new FluidView.Pump(6.8,3.3,.004,.73,new FluidView.Curve(100/3600.0,80,.70,2950));
        var inline=new FluidView.Pump(null,null,null,1,null);
        for(var pump:List.of(casing,inline)) {
            var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PUMP,base.view().withPump(pump),base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights());
            var copy=FluidNetwork.decodeLive(new Gson().toJson(live(data)));
            assertEquals(pump,copy.view().pump());
        }
        assertNull(FluidNetwork.decodeLive(new Gson().toJson(live(base))).view().pump());
        assertThrows(IllegalArgumentException.class,()->new FluidView.Pump(1.0,-1.0,0.0,1,null));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Pump(1.0,1.0,1.5,1,null));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Pump(null,null,null,1.2,null));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Curve(0,80,.7,2950));
    }
    /** W10c: a mover's state round-trips with its crackle and churn bytes; a byte outside 0 to 255 is refused. */
    @Test void aMoverStateRoundTripsItsCrackleAndChurn() {
        var target=new FluidNetwork.Target(new BlockPos(3,70,-9),42);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            // An in-line pump's state at its pipe block (W10c follow-up): the same payload, its speed 1, its crackle only.
            var inline=new FluidNetwork.MoverState(new FluidNetwork.Target(new BlockPos(-120,64,33),Long.MAX_VALUE-5),false,true,1,201,0,1549.2f);
            for(var state:List.of(new FluidNetwork.MoverState(target,false,true,.95f,255,128),new FluidNetwork.MoverState(target,false,true,1,0,7),new FluidNetwork.MoverState(target,true,false,0),inline)) {
                FluidNetwork.MoverState.STREAM_CODEC.encode(buffer,state);assertEquals(state,FluidNetwork.MoverState.STREAM_CODEC.decode(buffer));
            }
        }finally{buffer.release();}
        assertEquals(0,new FluidNetwork.MoverState(target,true,true,1).crackle());
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.MoverState(target,false,true,1,256,0));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.MoverState(target,false,true,1,0,-1));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.MoverState(target,false,true,1,0,0,-1));
    }
    /** W7: the machine page's view, edit and driver payloads round-trip; a view with a bad number is refused on read. */
    @Test void theMachinePayloadsRoundTripOnTheirOwnRegistrar() {
        assertEquals("machine-4",com.wormzjl.icheme.network.MachineNetwork.PROTOCOL);
        var settings=new ShaftUnit.Settings(true,com.wormzjl.icheme.science.machine.ShaftTrain.Mode.REGULATE_FLOW,.8,0,0);
        var view=new MachineView(7,com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR,"RUNNING",2950,24900,26700,534,settings,
                com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE,30000,2950,2,true,.72,370,"FV-101",0,2.9,40e5,4e5,null);
        var state=new com.wormzjl.icheme.network.MachineNetwork.StatePayload(3,view,2,"");
        var unread=new MachineView(8,com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.GENERATOR,"OFF SPEED",0,0,0,0,ShaftUnit.Settings.DEFAULT,
                com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE,30000,2950,2,false,Double.NaN,Double.NaN,"",0,Double.NaN,Double.NaN,Double.NaN,null);
        // W6b: a Create coupling's view carries its Create side; it round-trips, NaN impact included.
        var coupling=new MachineView(9,com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.CREATE_COUPLING,"POWER LIMITED",228.2,18000,0,0,ShaftUnit.Settings.DEFAULT,
                com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE,0,256,0,true,Double.NaN,Double.NaN,"",0,Double.NaN,Double.NaN,Double.NaN,
                new MachineView.Coupling(256,12288,12287/256.,2629,1.465));
        var unsent=new MachineView(10,com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.CREATE_COUPLING,"NO ROTATION",0,0,0,0,ShaftUnit.Settings.DEFAULT,
                com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE,0,256,0,true,Double.NaN,Double.NaN,"",0,Double.NaN,Double.NaN,Double.NaN,
                new MachineView.Coupling(0,0,Double.NaN,Double.NaN,1.465));
        assertThrows(IllegalArgumentException.class,()->new MachineView(11,com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR,"RUNNING",0,0,0,0,ShaftUnit.Settings.DEFAULT,
                com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE,0,256,0,true,Double.NaN,Double.NaN,"",0,Double.NaN,Double.NaN,Double.NaN,new MachineView.Coupling(0,0,0,0,1.465)),"a coupling view belongs to a coupling");
        assertThrows(IllegalArgumentException.class,()->new MachineView.Coupling(-1,0,0,0,1.465));
        var edit=new com.wormzjl.icheme.network.MachineNetwork.EditPayload(3,2,com.wormzjl.icheme.network.MachineNetwork.Action.APPLY,settings);
        var open=new com.wormzjl.icheme.network.MachineNetwork.OpenDriverPayload(4,new BlockPos(-3,70,12),99);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            com.wormzjl.icheme.network.MachineNetwork.StatePayload.STREAM_CODEC.encode(buffer,state);assertEquals(state,com.wormzjl.icheme.network.MachineNetwork.StatePayload.STREAM_CODEC.decode(buffer));
            var copy=new com.wormzjl.icheme.network.MachineNetwork.StatePayload(1,unread,0,"refused");
            com.wormzjl.icheme.network.MachineNetwork.StatePayload.STREAM_CODEC.encode(buffer,copy);
            var back=com.wormzjl.icheme.network.MachineNetwork.StatePayload.STREAM_CODEC.decode(buffer);
            assertTrue(Double.isNaN(back.view().storeShare())&&Double.isNaN(back.view().storeSeconds()));assertEquals("refused",back.refusal());
            for(var v:new MachineView[]{coupling,unsent}){var p=new com.wormzjl.icheme.network.MachineNetwork.StatePayload(5,v,0,"");com.wormzjl.icheme.network.MachineNetwork.StatePayload.STREAM_CODEC.encode(buffer,p);assertEquals(p,com.wormzjl.icheme.network.MachineNetwork.StatePayload.STREAM_CODEC.decode(buffer));}
            com.wormzjl.icheme.network.MachineNetwork.EditPayload.STREAM_CODEC.encode(buffer,edit);assertEquals(edit,com.wormzjl.icheme.network.MachineNetwork.EditPayload.STREAM_CODEC.decode(buffer));
            com.wormzjl.icheme.network.MachineNetwork.OpenDriverPayload.STREAM_CODEC.encode(buffer,open);assertEquals(open,com.wormzjl.icheme.network.MachineNetwork.OpenDriverPayload.STREAM_CODEC.decode(buffer));
            // A negative speed on the wire is refused at decode.
            buffer.clear();
            buffer.writeVarInt(1);buffer.writeLong(7);buffer.writeEnum(com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR);buffer.writeUtf("RUNNING");buffer.writeDouble(-1);
            for(int i=0;i<3;i++)buffer.writeDouble(0);
            buffer.writeBoolean(true);buffer.writeEnum(com.wormzjl.icheme.science.machine.ShaftTrain.Mode.SPEED);buffer.writeDouble(0);buffer.writeDouble(0);buffer.writeDouble(0);
            buffer.writeEnum(com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE);buffer.writeDouble(0);buffer.writeDouble(0);buffer.writeVarInt(0);buffer.writeBoolean(false);
            buffer.writeDouble(Double.NaN);buffer.writeDouble(Double.NaN);buffer.writeUtf("");buffer.writeDouble(0);for(int i=0;i<3;i++)buffer.writeDouble(Double.NaN);buffer.writeBoolean(false);buffer.writeVarInt(0);buffer.writeUtf("");
            assertThrows(io.netty.handler.codec.DecoderException.class,()->com.wormzjl.icheme.network.MachineNetwork.StatePayload.STREAM_CODEC.decode(buffer));
        }finally{buffer.release();}
    }
    /** The static part carries the axis, presets, names and kind only; the live part the view, controls and reply only; joined they are the menu state. */
    @Test void theStaticAndLivePayloadsSplitAndRejoinIntoTheSameMenuState() {
        var data=generatorMenu(FluidPresentation.QUEUED+"1250");var gson=new Gson();
        String fixed=gson.toJson(data.staticData(4)),live=gson.toJson(live(data));
        for(String key:new String[]{"\"view\"","\"controls\"","\"message\""})assertFalse(fixed.contains(key),"static payload carries "+key);
        for(String key:new String[]{"\"components\"","\"presets\"","\"materialNames\""})assertFalse(live.contains(key),"live payload carries "+key);
        var decodedStatic=FluidNetwork.decodeStatic(fixed);var decodedLive=FluidNetwork.decodeLive(live);
        assertEquals(4,decodedStatic.revision());assertEquals(TopologyCompiler.Kind.GENERATOR,decodedStatic.kind());
        var joined=FluidNetwork.MenuData.of(decodedStatic,decodedLive);
        assertEquals(data.molecularWeights(),joined.molecularWeights());assertEquals(data.kind(),joined.kind());assertEquals(data.components(),joined.components());assertEquals(data.materialNames(),joined.materialNames());
        assertEquals(data.presets().size(),joined.presets().size());assertEquals(data.message(),joined.message());
        assertEquals(data.view().status(),joined.view().status());assertEquals(1300,joined.view().onlineTick());
        assertArrayEquals(data.controls().composition(),joined.controls().composition());
        // The live part is small: it is what every bucket sends, the static part only on a revision change.
        assertTrue(live.length()<fixed.length(),"live "+live.length()+" static "+fixed.length());
    }
    /** A live payload joined to the static part of another axis, or with an unbounded reply, is refused. */
    @Test void aLivePayloadMustMatchItsStaticAxisAndABoundedReply() {
        var data=generatorMenu("");
        var shortAxis=new FluidNetwork.StaticData(TopologyCompiler.Kind.GENERATOR,0,List.of("only"),List.of(),Map.of(),weights(1));
        assertThrows(IllegalArgumentException.class,()->FluidNetwork.MenuData.of(shortAxis,live(data)));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.LiveData(data.view(),data.controls(),"x".repeat(1025)));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.StaticData(TopologyCompiler.Kind.GENERATOR,-1,data.components(),List.of(),Map.of(),weights(data.components().size())));
    }
    @Test void staticMolecularWeightsAreMandatoryPositiveAndOnTheServerAxis(){
        var data=generatorMenu("");var json=new Gson().toJsonTree(data.staticData(4)).getAsJsonObject();
        json.add("molecularWeights",new Gson().toJsonTree(List.of(0.01)));
        assertThrows(RuntimeException.class,()->FluidNetwork.decodeStatic(json.toString()));
        json.add("molecularWeights",new Gson().toJsonTree(Collections.nCopies(data.components().size(),0.0)));
        assertThrows(RuntimeException.class,()->FluidNetwork.decodeStatic(json.toString()));
        json.remove("molecularWeights");assertThrows(RuntimeException.class,()->FluidNetwork.decodeStatic(json.toString()));
    }
    @Test void pipeInspectionRoundTripsAndRejectsInvalidMetricValues(){
        var base=generatorMenu("");int count=base.components().size();double[][] n=new double[3][count];n[2][0]=4;
        var info=new FluidView.PipeInfo(new PipeTransfer.Stream(2,n,new double[]{0,0,.5}),
            List.of(new FluidView.PipeConnection(17,1,2,3,true)),true,true);
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,base.view().withPipeInfo(info),base.controls(),
            base.components(),base.presets(),"",base.materialNames(),base.molecularWeights());
        var gson=new Gson();var copy=FluidNetwork.MenuData.of(data.staticData(0),FluidNetwork.decodeLive(gson.toJson(live(data))));
        assertEquals(info.connections(),copy.view().pipeInfo().connections());
        assertTrue(copy.view().pipeInfo().junction());assertTrue(copy.view().pipeInfo().changedDirection());
        assertArrayEquals(info.contents().componentMoles(),copy.view().pipeInfo().contents().componentMoles());
        assertThrows(IllegalArgumentException.class,()->new FluidView.PipeConnection(1,-1,2,3,false));
        assertThrows(IllegalArgumentException.class,()->new FluidView.PipeConnection(1,1,Double.NaN,3,false));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,base.view(),base.controls(),
            base.components(),base.presets(),"",base.materialNames(),base.molecularWeights()));
    }
    /** fluid-16 (the heat GUI): a pipe's line reading crosses the live payload, still or flowing; an invalid one is refused. */
    @Test void aPipesLineReadingRoundTrips(){
        var base=generatorMenu("");int count=base.components().size();
        for(boolean flowing:new boolean[]{true,false}) {
            var info=FluidView.PipeInfo.empty(count).withReading(new FluidView.Reading(453.65,950_000,flowing));
            var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,base.view().withPipeInfo(info),base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights());
            var copy=FluidNetwork.MenuData.of(data.staticData(0),FluidNetwork.decodeLive(new Gson().toJson(live(data))));
            assertEquals(info.reading(),copy.view().pipeInfo().reading());
        }
        var plain=FluidNetwork.MenuData.of(base.staticData(0),FluidNetwork.decodeLive(new Gson().toJson(live(new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,base.view().withPipeInfo(FluidView.PipeInfo.empty(count)),base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights())))));
        assertNull(plain.view().pipeInfo().reading(),"no observation yet: no reading");
        assertThrows(IllegalArgumentException.class,()->new FluidView.Reading(0,1e5,true));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Reading(300,Double.NaN,true));
    }
    /**
     * fluid-16 (the heat GUI): a heat side's actuator, effort and limit, its unit's ports with their phases and an exchanger's
     * lines part by part cross the live payload as sent. A port off the menu's axis, an unknown actuator, an effort over one,
     * two ports of one role and too many parts are refused.
     */
    @Test void aHeatSidesPortsLinesAndActuatorRoundTrip(){
        var base=generatorMenu("");int count=base.components().size();
        double[][] n=new double[3][count];n[0][count-1]=544;n[2][count-1]=3;
        var ports=List.of(new FluidView.Port("SHELL_INLET",293.15,121_900.0,9.8,n,new double[]{.0098,0,.02}),
                new FluidView.Port("SHELL_OUTLET",310.65,118_000.0,9.8,n,new double[]{.0098,0,.02}),new FluidView.Port("TUBE_INLET",null,null,0,new double[3][count],new double[3]));
        var lines=new FluidView.Lines("UNMETERED",0,List.of(new FluidView.Part("shell_inlet_tee","OK","",Long.MIN_VALUE,293.15,null),
                new FluidView.Part("shell_bypass_valve","OK","",Long.MIN_VALUE,null,7.4),new FluidView.Part("shell_outlet_tee","MISSING","terminal",BlockPos.asLong(1,64,-3),null,null)));
        var heat=new FluidView.Heat("EXCHANGER","SHELL",true,303.15,-433_000,null,null,null,null,null,.42).with("bypass",.42,"NONE",ports,lines);
        var view=new FluidView(9,4,1200,1300,"FULL",null,0,List.of()).withPipeInfo(FluidView.PipeInfo.empty(count)).withHeat(heat);
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.HEAT_SIDE,view,base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights());
        var copy=FluidNetwork.MenuData.of(data.staticData(0),FluidNetwork.decodeLive(new Gson().toJson(live(data)))).view().heat();
        assertEquals("bypass",copy.actuator());assertEquals(.42,copy.effort());assertEquals("NONE",copy.limit());
        assertEquals(3,copy.ports().size());assertEquals(310.65,copy.port("SHELL_OUTLET").temperature());assertNull(copy.port("TUBE_INLET").temperature());
        assertArrayEquals(n[0],copy.port("SHELL_INLET").phaseMoles()[0]);assertArrayEquals(new double[]{.0098,0,.02},copy.port("SHELL_INLET").phaseVolumes());
        assertEquals(lines,copy.lines());assertEquals(7.4,copy.lines().part("shell_bypass_valve").massFlow());
        // A side of fluid-15's shape (no actuator, ports or lines) reads as acting on nothing.
        var old=new FluidView.Heat("AIR_COOLER","PROCESS",false,330,-1e5,1.0,1e4,null,null,null,null);
        assertEquals("none",old.actuator());assertEquals(List.of(),old.ports());assertNull(old.lines());
        double[][] offAxis=new double[3][count+1];
        var wrong=new FluidView(9,4,1200,1300,"FULL",null,0,List.of()).withPipeInfo(FluidView.PipeInfo.empty(count)).withHeat(heat.with("bypass",.42,"NONE",List.of(new FluidView.Port("SHELL_INLET",293.15,1e5,1,offAxis,new double[3])),null));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.MenuData(TopologyCompiler.Kind.HEAT_SIDE,wrong,base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights()));
        assertThrows(IllegalArgumentException.class,()->heat.with("valve",.4,"NONE",ports,lines));
        assertThrows(IllegalArgumentException.class,()->heat.with("bypass",1.2,"NONE",ports,lines));
        assertThrows(IllegalArgumentException.class,()->heat.with("bypass",.4,"NONE",List.of(ports.get(0),ports.get(0)),lines));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Lines("CHECKED",1,Collections.nCopies(13,new FluidView.Part("x","OK","",0,null,null))));
    }
    /** The client remembers the last delivered state of the 32 most recent devices, so a reopened menu shows it until its bucket; leaving a world forgets it. */
    @Test void theClientRemembersTheLastDeliveredStateOfRecentDevices() {
        FluidNetwork.forgetDelivered();
        try {
            assertNull(FluidNetwork.lastDelivered(1));
            var first=generatorMenu("");var second=generatorMenu(FluidPresentation.APPLIED);
            FluidNetwork.remember(1,first);assertSame(first,FluidNetwork.lastDelivered(1));
            FluidNetwork.remember(1,second);assertSame(second,FluidNetwork.lastDelivered(1),"the last delivery replaces the earlier one");
            for(long id=2;id<=32;id++)FluidNetwork.remember(id,first);
            assertNotNull(FluidNetwork.lastDelivered(1),"thirty-two devices fit");
            FluidNetwork.lastDelivered(1);FluidNetwork.remember(33,first);
            assertNotNull(FluidNetwork.lastDelivered(1),"the most recently read device stays");assertNull(FluidNetwork.lastDelivered(2),"the least recently used device goes");
            FluidNetwork.forgetDelivered();assertNull(FluidNetwork.lastDelivered(1));
        } finally {FluidNetwork.forgetDelivered();}
    }
    /** The compressor's ratio control (phase-ports WP5): bounded to 1.01-10, read back from a compressor's registration,
     * and required on the wire (a payload without it is refused, not defaulted). */
    @Test void theCompressorRatioIsBoundedReadFromItsRegistrationAndRequiredOnTheWire() {
        double[] n=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=1;
        for(double ratio:new double[]{1.01,3,10})assertEquals(ratio,new FluidNetwork.Controls(298.15,101325,.05,.000045,.05,500000,n,com.wormzjl.icheme.runtime.fluid.SlurryFeed.NONE,ratio).maximumPressureRatio());
        for(double ratio:new double[]{1.009,10.01,Double.NaN,0})
            assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,101325,.05,.000045,.05,500000,n,com.wormzjl.icheme.runtime.fluid.SlurryFeed.NONE,ratio),"ratio "+ratio);
        assertEquals(3.0,new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,n).maximumPressureRatio(),"the placement default of plan Appendix B");
        var device=new PhysicalFluidTopology.Device(7,new PhysicalFluidTopology.Position("minecraft:overworld",0,64,0),TopologyCompiler.Kind.PIPE,PhysicalFluidTopology.Direction.EAST,
                new com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry(1,.05,.000045,0),new com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor(.05,2.5));
        var controls=FluidNetwork.Controls.from(new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(),3));
        assertEquals(.05,controls.volumeFlow());assertEquals(2.5,controls.maximumPressureRatio());
        var gson=new Gson();var object=com.google.gson.JsonParser.parseString(gson.toJson(controls)).getAsJsonObject();
        assertEquals(controls.maximumPressureRatio(),gson.fromJson(object.toString(),FluidNetwork.Controls.class).maximumPressureRatio());
        object.remove("maximumPressureRatio");
        assertThrows(RuntimeException.class,()->gson.fromJson(object.toString(),FluidNetwork.Controls.class),"an absent ratio is refused");
    }
    /** A tank's outlet lines round-trip on the live payload; only a tank lists outlets, at most six, with finite values. */
    @Test void aTanksOutletsRoundTripAndAreBounded() {
        var base=generatorMenu("");
        var outlets=List.of(new FluidView.Outlet(11,PhysicalFluidTopology.Direction.UP,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.VAPOR,.002,.07,2,0),
                new FluidView.Outlet(12,PhysicalFluidTopology.Direction.WEST,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.BULK,-.5,-27,FluidView.Outlet.NONE,0),
                new FluidView.Outlet(13,PhysicalFluidTopology.Direction.DOWN,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.LIQUID,1.25,69.4,1,2931.5));
        var view=base.view().withOutlets(outlets);
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.RESERVOIR,view,base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights());
        var copy=FluidNetwork.MenuData.of(data.staticData(0),FluidNetwork.decodeLive(new Gson().toJson(live(data))));
        assertEquals(outlets,copy.view().outlets());
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.MenuData(TopologyCompiler.Kind.GENERATOR,view,base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights()));
        assertThrows(IllegalArgumentException.class,()->base.view().withOutlets(java.util.Collections.nCopies(7,outlets.getFirst())));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Outlet(1,PhysicalFluidTopology.Direction.DOWN,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.LIQUID,Double.NaN,0,1,0));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Outlet(1,PhysicalFluidTopology.Direction.DOWN,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.LIQUID,1,1,3,0));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Outlet(1,PhysicalFluidTopology.Direction.DOWN,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.LIQUID,1,1,1,-1));
        var json=com.google.gson.JsonParser.parseString(new Gson().toJson(live(data))).getAsJsonObject();json.getAsJsonObject("view").remove("outlets");
        assertThrows(RuntimeException.class,()->FluidNetwork.decodeLive(json.toString()),"a live view without its outlet list is refused");
        // The face (fluid-8, GUI fix F4): required on the wire. A vessel's nozzle states its own class whichever way it faces
        // (fluid-11, batch 2026-10-21-floating-roof-tanks: a floating-roof tank's front LIQUID and OVERFLOW nozzles).
        var faceless=com.google.gson.JsonParser.parseString(new Gson().toJson(live(data))).getAsJsonObject();
        faceless.getAsJsonObject("view").getAsJsonArray("outlets").get(1).getAsJsonObject().remove("face");
        assertThrows(RuntimeException.class,()->FluidNetwork.decodeLive(faceless.toString()),"an outlet without its face is refused");
        assertEquals(com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.OVERFLOW,
                new FluidView.Outlet(1,PhysicalFluidTopology.Direction.NORTH,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.OVERFLOW,1,1,0,0).port(),"a vessel's front overflow nozzle");
    }
    /**
     * The compressor screen refuses a ratio outside its bounds with a message naming the field and the bounds (GUI fix F5):
     * the screen applies the same predicate as {@link FluidNetwork.Controls} and states the same constants.
     */
    @Test void theRatioRefusalNamesTheFieldAndTheValidatedBounds() throws java.io.IOException {
        for(double ratio:new double[]{1.01,3,10})assertTrue(FluidNetwork.pressureRatioAllowed(ratio),"ratio "+ratio);
        for(double ratio:new double[]{1.009,10.01,10.5,Double.NaN,0})assertFalse(FluidNetwork.pressureRatioAllowed(ratio),"ratio "+ratio);
        assertArrayEquals(new String[]{"1.01","10"},FluidNetwork.pressureRatioBoundsText());
        String template;
        try(var in=Objects.requireNonNull(FluidPacketCodecTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json"))){
            var lang=com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            template=lang.get("gui.icheme.fluid.invalid_ratio").getAsString();
            for(var face:PhysicalFluidTopology.Direction.values())assertTrue(lang.has("gui.icheme.fluid.face."+face.name().toLowerCase(Locale.ROOT)),"face "+face);
            for(String key:new String[]{"top","side","bottom","bottom_head"})assertTrue(lang.get("gui.icheme.fluid.outlet."+key).getAsString().contains("(%s)"),"outlet."+key+" names its face");
        }
        String text=String.format(Locale.ROOT,template,(Object[])FluidNetwork.pressureRatioBoundsText());
        assertEquals("Max pressure ratio (out/in) must lie between 1.01 and 10.",text);
    }
    /** A refused control reaches the handler wrapped by the JSON reader; the player reads the control's own reason, not the wrapper. */
    @Test void aRefusedControlIsRepliedWithItsOwnReason() {
        double[] n=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=1;
        var gson=new Gson();var object=com.google.gson.JsonParser.parseString(gson.toJson(new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,n))).getAsJsonObject();object.addProperty("pressure",-3e6);
        var refused=assertThrows(RuntimeException.class,()->gson.fromJson(object.toString(),FluidNetwork.Controls.class));
        assertEquals("Controls are outside the supported range",FluidNetwork.reason(refused));
        // A setting outside the package envelope is replied with the input box's own text.
        var domain=assertThrows(IllegalArgumentException.class,
                ()->new FluidDeviceSpec(1,298.15,1.2e7,n).validate(com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel(),TopologyCompiler.Kind.GENERATOR));
        assertEquals("Pressure 1.2E7 Pa outside the package envelope 100.0..1.0E7 Pa (package icheme:refinery)",FluidNetwork.reason(domain));
        assertEquals("Stale fluid controls",FluidNetwork.reason(new IllegalStateException("Stale fluid controls")));
        assertEquals("null",FluidNetwork.reason(new RuntimeException()));
    }
    /**
     * The effects' payloads (batch 2026-11-10-custom-shader-system, W3 to W5): a flare's state with its soot index and exit
     * velocity, a tower's plume and a heater's flue cross whole; out-of-range values are refused; the new watches have their
     * own wire identities.
     */
    @Test void effectStatesRoundTripAndRefuseOutOfRangeValues() {
        var target=new FluidNetwork.Target(new BlockPos(5,70,-9),42);
        var flare=new FluidNetwork.FlareState(target,1,.8f,0,.45f,62.5f);
        var tower=new FluidNetwork.TowerPlume(target,1234.5f,301.6f,.0247f,20.75f);
        var heater=new FluidNetwork.HeaterFlue(target,.7f,643.2f,12.3f,.122f,com.wormzjl.icheme.science.fluid.network.Burners.Fuel.LIQUID.ordinal());
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            FluidNetwork.FlareState.STREAM_CODEC.encode(buffer,flare);assertEquals(flare,FluidNetwork.FlareState.STREAM_CODEC.decode(buffer));
            FluidNetwork.TowerPlume.STREAM_CODEC.encode(buffer,tower);assertEquals(tower,FluidNetwork.TowerPlume.STREAM_CODEC.decode(buffer));
            FluidNetwork.HeaterFlue.STREAM_CODEC.encode(buffer,heater);assertEquals(heater,FluidNetwork.HeaterFlue.STREAM_CODEC.decode(buffer));
            assertEquals(0,buffer.readableBytes());
        }finally{buffer.release();}
        assertEquals(com.wormzjl.icheme.science.fluid.network.Burners.Fuel.LIQUID,heater.fuelKind());
        assertEquals(0,new FluidNetwork.FlareState(target,1,.5f,0).soot(),"the old form carries no soot");
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.FlareState(target,1,.5f,0,1.5f,10));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.FlareState(target,1,.5f,0,0,Float.NaN));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.TowerPlume(target,-1,300,0,0));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.TowerPlume(target,1,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.HeaterFlue(target,1.2f,600,1,.1f,0));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.HeaterFlue(target,.5f,600,1,.1f,3));
        var ids=java.util.Set.of(FluidNetwork.Watch.TOWERS.id(),FluidNetwork.Watch.HEATERS.id(),FluidNetwork.Watch.FLARES.id(),FluidNetwork.Watch.STILLS.id(),
                FluidNetwork.TowerPlume.TYPE.id(),FluidNetwork.HeaterFlue.TYPE.id(),FluidNetwork.FlareState.TYPE.id());
        assertEquals(7,ids.size());
    }
    /**
     * fluid-22 (PROCESS_SOUNDS_REVIEW.md S2 A): the noise watch's answer crosses whole, its sources in their order (the
     * loudest first), at most 16; the poll carries nothing; both have their own wire identities.
     */
    @Test void theNoiseAnswerRoundTripsAndHoldsAtMostSixteenSources() {
        var sources=new java.util.ArrayList<Noise.Source>();
        for(var kind:Noise.Kind.values())sources.add(new Noise.Source(new BlockPos(kind.ordinal(),70,-9),kind,70+kind.ordinal()*4.5f,.5f+kind.ordinal()*.1f));
        var answer=new FluidNetwork.NoiseSources(sources);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            FluidNetwork.NoiseSources.STREAM_CODEC.encode(buffer,answer);assertEquals(answer,FluidNetwork.NoiseSources.STREAM_CODEC.decode(buffer));
            FluidNetwork.NoiseWatch.STREAM_CODEC.encode(buffer,new FluidNetwork.NoiseWatch());assertEquals(new FluidNetwork.NoiseWatch(),FluidNetwork.NoiseWatch.STREAM_CODEC.decode(buffer));
            assertEquals(0,buffer.readableBytes());
        }finally{buffer.release();}
        var seventeen=java.util.Collections.nCopies(Noise.MAXIMUM_SOURCES+1,sources.get(0));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.NoiseSources(seventeen));
        var bad=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            bad.writeVarInt(1);bad.writeBlockPos(BlockPos.ZERO);bad.writeByte(Noise.Kind.values().length);bad.writeFloat(80);bad.writeFloat(1);
            assertThrows(IllegalArgumentException.class,()->FluidNetwork.NoiseSources.STREAM_CODEC.decode(bad),"an unknown kind is refused");
        }finally{bad.release();}
        assertNotEquals(FluidNetwork.NoiseWatch.TYPE.id(),FluidNetwork.NoiseSources.TYPE.id());
    }
    /** fluid-23 (batch 2026-11-10-chimney): a chimney's published plume crosses whole; a bad one is refused; its watch has its own identity. */
    @Test void aChimneyStateRoundTrips() {
        var target=new FluidNetwork.Target(new BlockPos(3,110,-4),77);
        var state=new FluidNetwork.ChimneyState(target,2.5f,450,.08f,.004f,.35f,18.5f);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            FluidNetwork.ChimneyState.STREAM_CODEC.encode(buffer,state);assertEquals(state,FluidNetwork.ChimneyState.STREAM_CODEC.decode(buffer));
            assertEquals(0,buffer.readableBytes());
        }finally{buffer.release();}
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.ChimneyState(target,1,450,0,0,1.5f,10));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.ChimneyState(target,-1,450,0,0,0,10));
        assertNotEquals(FluidNetwork.Watch.CHIMNEYS.id(),FluidNetwork.ChimneyState.TYPE.id());
        assertNotEquals(FluidNetwork.Watch.FLARES.id(),FluidNetwork.Watch.CHIMNEYS.id());
    }
}
