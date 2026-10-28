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
        double[] oversized=new double[66];oversized[0]=1;
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
        var view=new FluidView(1,0,0,0,"READY",new FluidView.State(101325,298.15,1,1,new double[]{1,2,3},n,SolidInventory.EMPTY,1),0,history);
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,view.withPipeInfo(FluidView.PipeInfo.empty(view.pipeHistory().getFirst().forward().componentMoles().length)),new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,amounts),ids,presets,"",descriptors,weights(ids.size()));
        String json=new Gson().toJson(data);assertTrue(json.length()<FluidNetwork.MAX_JSON);
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
    @Test void theProtocolIsFluidSixteenWithDistinctStaticAndLivePayloads() {
        assertEquals("fluid-16",FluidNetwork.PROTOCOL);
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
        assertEquals("Pressure 1.2E7 Pa outside the package envelope 100.0..1.0E7 Pa (package icheme:tjl20_methane_nitrogen)",FluidNetwork.reason(domain));
        assertEquals("Stale fluid controls",FluidNetwork.reason(new IllegalStateException("Stale fluid controls")));
        assertEquals("null",FluidNetwork.reason(new RuntimeException()));
    }
}
