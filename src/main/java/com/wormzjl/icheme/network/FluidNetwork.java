package com.wormzjl.icheme.network;

import com.google.gson.Gson;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.inventory.FluidDeviceMenu;
import com.wormzjl.icheme.world.inventory.GaugeMenu;
import com.wormzjl.icheme.world.inventory.PipelineToolMenu;
import com.wormzjl.icheme.world.item.GaugeSettings;
import com.wormzjl.icheme.world.item.PipelineToolItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.*;

/**
 * Menu protocol {@value #PROTOCOL}: position/identity/revision-bound controls; clients never send inventory or
 * solver results. Opening a GUI immediately receives the last published server snapshot, when available.
 * Subsequent menu updates receive a static payload (kind, components,
 * presets, material names) and a live payload (view, controls, the engine's reply) on its island's
 * presentation bucket ({@link FluidPresentation}); the static payload again only when the device's registration
 * revision changed. An edit or recovery packet is validated as before, queued as a ledger event, and its reply -
 * {@code Queued for simulation event at tick N}, {@code Applied} or {@code Not applied: <reason>} - arrives with
 * the next bucket.
 *
 * <p>{@code fluid-10} (owner decision D23, crystals are solids): a solid population's material carries its crystal
 * record (the component it is made of and its energy offset) when it is a crystal, so a tank's dry ice reaches the
 * Solids tab and the solid share as a solid; a menu refuses a crystal made of a component off its axis.</p>
 *
 * <p>{@code fluid-11} (batch 2026-10-21-floating-roof-tanks): a vessel's view carries its {@link FluidView.Tank} (level
 * height, open flag, vent and spill rates), and a vessel's nozzle states its own port class, OVERFLOW among them, whichever
 * way it faces.</p>
 * <p>{@code fluid-13}: power also controls generators; void contents are accepted incoming transport, normalized to one second.</p>
 * <p>{@code fluid-14}: a tank publishes capacity and separate top/bottom absolute pressures, including empty capacity.</p>
 */
public final class FluidNetwork {
    public static final String PROTOCOL="fluid-15";
    private static final Gson JSON=new Gson();
    public static final int MAX_JSON=262144;
    public static final int MAX_EDIT_JSON=4096;
    private FluidNetwork() {}
    /** The compressor's pressure-ratio bounds (plan Appendix B): at least 1 % over the suction, at most tenfold. */
    public static final double MINIMUM_PRESSURE_RATIO=1.01,MAXIMUM_PRESSURE_RATIO=10,DEFAULT_PRESSURE_RATIO=3.0;
    /** Whether a compressor's maximum pressure ratio lies within its bounds: the one test {@link Controls} and the screen both apply. */
    public static boolean pressureRatioAllowed(double ratio){return ratio>=MINIMUM_PRESSURE_RATIO&&ratio<=MAXIMUM_PRESSURE_RATIO;}
    /** The two bounds as a player reads them ("1.01", "10"), for the screen's refusal of a ratio outside them. */
    public static String[] pressureRatioBoundsText(){return new String[]{plainNumber(MINIMUM_PRESSURE_RATIO),plainNumber(MAXIMUM_PRESSURE_RATIO)};}
    private static String plainNumber(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    /** Required on the wire; a missing or unknown value is rejected by Controls validation. */
    public enum Power {ON,OFF}
    public record Controls(double temperature,double pressure,double diameter,double roughness,double volumeFlow,double maximumAddedPressure,double[] composition,SlurryFeed solids,double maximumPressureRatio,Power power,FlowControl.ValveSource valveSource,double massFlow) {
        public Controls(double temperature,double pressure,double diameter,double roughness,double volumeFlow,double maximumAddedPressure,double[] composition,SlurryFeed solids,double maximumPressureRatio,Power power){this(temperature,pressure,diameter,roughness,volumeFlow,maximumAddedPressure,composition,solids,maximumPressureRatio,power,FlowControl.ValveSource.NONE,1);}
        public Controls(double temperature,double pressure,double diameter,double roughness,double volumeFlow,double maximumAddedPressure,double[] composition,SlurryFeed solids,double maximumPressureRatio){this(temperature,pressure,diameter,roughness,volumeFlow,maximumAddedPressure,composition,solids,maximumPressureRatio,Power.ON);}
        public Controls(double temperature,double pressure,double diameter,double roughness,double volumeFlow,double maximumAddedPressure,double[] composition,SlurryFeed solids){this(temperature,pressure,diameter,roughness,volumeFlow,maximumAddedPressure,composition,solids,DEFAULT_PRESSURE_RATIO);}
        public Controls(double temperature,double pressure,double diameter,double roughness,double volumeFlow,double maximumAddedPressure,double[] composition){this(temperature,pressure,diameter,roughness,volumeFlow,maximumAddedPressure,composition,SlurryFeed.NONE);}
        public Controls {
            Objects.requireNonNull(valveSource);if(!Double.isFinite(massFlow)||massFlow<0)throw new IllegalArgumentException("Invalid mass-flow target");Objects.requireNonNull(solids);if(power==null)throw new IllegalArgumentException("Missing or unknown power setting");composition=composition.clone();
            for(double value:new double[]{temperature,pressure,diameter,roughness,volumeFlow,maximumAddedPressure,maximumPressureRatio})if(!Double.isFinite(value))throw new IllegalArgumentException("All controls must be finite numbers");
            if(!pressureRatioAllowed(maximumPressureRatio))throw new IllegalArgumentException("The pressure ratio must lie between "+MINIMUM_PRESSURE_RATIO+" and "+MAXIMUM_PRESSURE_RATIO);
            // Temperature and pressure are only required to be physical here: the input box is the property package's
            // envelope, so the server checks it against the model when the edit arrives (see edit below).
            if(!(temperature>0)||!(pressure>0)||diameter<.001||diameter>1||roughness<0||roughness>=diameter||volumeFlow<0||volumeFlow>10||maximumAddedPressure<=0||maximumAddedPressure>2e6||(composition.length<1||composition.length>com.wormzjl.icheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS))throw new IllegalArgumentException("Controls are outside the supported range");
            double sum=0;for(double n:composition){if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Composition entries must be nonnegative");sum+=n;}if(!Double.isFinite(sum)||sum<=0)throw new IllegalArgumentException("Composition cannot be empty");
        }
        @Override public double[] composition(){return composition.clone();}
        public static Controls from(WorldTopologyLedger.Registration record) {
            var d=record.device();double flow=d.control() instanceof FlowControl.Mover m?m.requestedVolumeFlow():.01;
            double head=d.control() instanceof FlowControl.Pump p?p.maximumAddedPressure():500000;
            double ratio=d.control() instanceof FlowControl.Compressor c?c.maximumPressureRatio():DEFAULT_PRESSURE_RATIO;
            double pressure=d.control() instanceof FlowControl.Valve v?v.targetPressure():record.spec().pressure();
            var valve=d.control() instanceof FlowControl.ControlValve v?v:null;
            if(valve!=null)flow=valve.volumeTarget();
            // An air cooler's fans are its power (batch 2026-10-27-heat-exchanger-design): on while they turn at all.
            var power=!d.generatorEnabled()||d.control() instanceof FlowControl.Mover m&&!m.enabled()||d.heat()!=null&&d.heat().fans()&&d.heat().control().share()==0?Power.OFF:Power.ON;
            return new Controls(record.spec().temperature(),pressure,d.geometry().diameter(),d.geometry().roughness(),flow,head,record.spec().composition(),record.spec().solids(),ratio,power,valve==null?(d.control() instanceof FlowControl.PressureValve?FlowControl.ValveSource.PRESSURE:FlowControl.ValveSource.NONE):valve.source(),valve==null?1:valve.massTarget());
        }
    }
    /**
     * What a menu shows: a static part and a live part as one validated record. The server builds it at a bucket
     * and splits it for the wire; the client joins the two parts it received and validates the whole again.
     */
    public record MenuData(TopologyCompiler.Kind kind,FluidView view,Controls controls,List<String> components,List<FluidPresetCatalog.Preset> presets,String message,
            Map<String,com.wormzjl.icheme.science.material.MaterialName> materialNames,List<Double> molecularWeights,List<com.wormzjl.icheme.runtime.instrument.Sensor.Reading> sensors,ValveData valve) {
        public MenuData(TopologyCompiler.Kind kind,FluidView view,Controls controls,List<String> components,List<FluidPresetCatalog.Preset> presets,String message,Map<String,com.wormzjl.icheme.science.material.MaterialName> materialNames,List<Double> molecularWeights){this(kind,view,controls,components,presets,message,materialNames,molecularWeights,List.of(),null);}
        public MenuData {
            sensors=List.copyOf(sensors);if(sensors.size()>8)throw new IllegalArgumentException("Too many sensor readings");
            Objects.requireNonNull(kind);Objects.requireNonNull(view);Objects.requireNonNull(controls);Objects.requireNonNull(message);
            components=new com.wormzjl.icheme.science.material.MaterialAxis(components).ids();presets=List.copyOf(presets);materialNames=Map.copyOf(materialNames);molecularWeights=validatedWeights(molecularWeights,components.size());
            if(controls.composition().length!=components.size()||presets.size()>com.wormzjl.icheme.science.material.MaterialPresets.MAX_PRESETS
                    ||presets.stream().map(FluidPresetCatalog.Preset::id).distinct().count()!=presets.size()||materialNames.size()>components.size()
                    ||!components.containsAll(materialNames.keySet()))throw new IllegalArgumentException("Invalid bounded fluid menu state");
            if(kind!=TopologyCompiler.Kind.RESERVOIR&&!view.outlets().isEmpty())throw new IllegalArgumentException("Only a tank lists outlets");
            if(view.tank()!=null&&(kind!=TopologyCompiler.Kind.RESERVOIR||(view.state()==null)!=(view.tank().topPressure()==null)||view.state()!=null&&view.tank().capacity()!=view.state().capacity()))throw new IllegalArgumentException("Tank geometry differs from its state");
            if((kind==TopologyCompiler.Kind.PIPE||kind==TopologyCompiler.Kind.FILTER||kind==TopologyCompiler.Kind.VOID)&&view.pipeInfo()==null)
                throw new IllegalArgumentException("Missing accepted transport presentation");
            if(view.pipeInfo()!=null){
                requirePhaseAxis(view.pipeInfo().contents().phaseMoles(),components.size());
            }
            if(view.state()!=null)requirePhaseAxis(view.state().phaseMoles(),components.size());
            for(var transfer:view.pipeHistory()){requirePhaseAxis(transfer.forward().phaseMoles(),components.size());requirePhaseAxis(transfer.reverse().phaseMoles(),components.size());}
            // A crystal (owner decision D23) is made of a component of the axis, wherever the view shows solids.
            if(view.state()!=null)requireCrystalAxis(view.state().solids(),components);
            if(view.pipeInfo()!=null)requireCrystalAxis(view.pipeInfo().contents().solids(),components);
            if(view.filter()!=null)requireCrystalAxis(view.filter().captured(),components);
            for(var transfer:view.pipeHistory()){requireCrystalAxis(transfer.forward().solids(),components);requireCrystalAxis(transfer.reverse().solids(),components);}
            for(var p:presets)if(p.moleFractions().length!=components.size())throw new IllegalArgumentException("Fluid preset axis mismatch");
            for(var e:materialNames.entrySet())if(!e.getKey().equals(e.getValue().id()))throw new IllegalArgumentException("Fluid name identity mismatch");
        }
        /** Joins what a menu received: its last static payload and the live payload just delivered. */
        public static MenuData of(StaticData fixed,LiveData live){return new MenuData(fixed.kind(),live.view(),live.controls(),fixed.components(),fixed.presets(),live.message(),fixed.materialNames(),fixed.molecularWeights(),live.sensors(),live.valve());}
        public StaticData staticData(long revision){return new StaticData(kind,revision,components,presets,materialNames,molecularWeights);}
    }
    /** The static payload: what changes only with the device's registration revision (and never within one server run otherwise). */
    public record StaticData(TopologyCompiler.Kind kind,long revision,List<String> components,List<FluidPresetCatalog.Preset> presets,Map<String,com.wormzjl.icheme.science.material.MaterialName> materialNames,List<Double> molecularWeights) {
        public StaticData {
            Objects.requireNonNull(kind);components=new com.wormzjl.icheme.science.material.MaterialAxis(components).ids();presets=List.copyOf(presets);materialNames=Map.copyOf(materialNames);molecularWeights=validatedWeights(molecularWeights,components.size());
            if(revision<0||presets.size()>com.wormzjl.icheme.science.material.MaterialPresets.MAX_PRESETS||materialNames.size()>components.size()||!components.containsAll(materialNames.keySet()))
                throw new IllegalArgumentException("Invalid bounded fluid static state");
            for(var p:presets)if(p.moleFractions().length!=components.size())throw new IllegalArgumentException("Fluid preset axis mismatch");
        }
    }
    /** The live payload: the bucket's view, the device's current controls, and the engine's reply (empty for none). */
    public record ValveData(boolean configured,double opening,String status){public ValveData{if(!Double.isFinite(opening)||opening<0||opening>1)throw new IllegalArgumentException("Invalid opening");Objects.requireNonNull(status);}}
    public record LiveData(FluidView view,Controls controls,String message,List<com.wormzjl.icheme.runtime.instrument.Sensor.Reading> sensors,ValveData valve) {
        public LiveData(FluidView view,Controls controls,String message){this(view,controls,message,List.of(),null);}
        public LiveData {
            sensors=List.copyOf(sensors);if(sensors.size()>8)throw new IllegalArgumentException("Too many sensor readings");Objects.requireNonNull(view);Objects.requireNonNull(controls);Objects.requireNonNull(message);
            if(message.length()>1024||view.pipeHistory().size()>12||view.pipeRoutes().size()>12||view.status().length()>2048)throw new IllegalArgumentException("Fluid view exceeds display bounds");
        }
    }
    private static List<Double> validatedWeights(List<Double> values,int count){
        Objects.requireNonNull(values,"Missing molecular weights");var copy=List.copyOf(values);
        if(copy.size()!=count||copy.stream().anyMatch(w->!Double.isFinite(w)||w<=0))
            throw new IllegalArgumentException("Invalid molecular weights");
        return copy;
    }
    private static void requirePhaseAxis(double[][] phases,int components) {
        if(phases.length!=3)throw new IllegalArgumentException("Fluid phase count mismatch");
        for(var phase:phases)if(phase.length!=components)throw new IllegalArgumentException("Fluid phase axis mismatch");
    }
    private static void requireCrystalAxis(com.wormzjl.icheme.science.fluid.state.SolidInventory solids,List<String> components) {
        for(var p:solids.crystals())if(!components.contains(p.material().crystal().component()))throw new IllegalArgumentException("Crystal off the fluid axis: "+p.material().id());
    }
    public record EditPayload(int menuId,BlockPos position,long identity,long revision,String json) implements CustomPacketPayload {
        public static final Type<EditPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"fluid_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf,EditPayload> STREAM_CODEC=new StreamCodec<>() {
            public EditPayload decode(RegistryFriendlyByteBuf b){return new EditPayload(b.readVarInt(),b.readBlockPos(),b.readLong(),b.readLong(),b.readUtf(MAX_EDIT_JSON));}
            public void encode(RegistryFriendlyByteBuf b,EditPayload p){b.writeVarInt(p.menuId);b.writeBlockPos(p.position);b.writeLong(p.identity);b.writeLong(p.revision);b.writeUtf(p.json,MAX_EDIT_JSON);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record RecoverPayload(int menuId,BlockPos position,long identity,long revision) implements CustomPacketPayload {
        public static final Type<RecoverPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"fluid_recover_solids"));
        public static final StreamCodec<RegistryFriendlyByteBuf,RecoverPayload> STREAM_CODEC=new StreamCodec<>() {
            public RecoverPayload decode(RegistryFriendlyByteBuf b){return new RecoverPayload(b.readVarInt(),b.readBlockPos(),b.readLong(),b.readLong());}
            public void encode(RegistryFriendlyByteBuf b,RecoverPayload p){b.writeVarInt(p.menuId);b.writeBlockPos(p.position);b.writeLong(p.identity);b.writeLong(p.revision);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Client to server: a heat side's menu asks to open its partner side's (batch 2026-10-27-heat-exchanger-design). */
    public record PartnerPayload(int menuId,BlockPos position,long identity) implements CustomPacketPayload {
        public static final Type<PartnerPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"fluid_open_partner"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PartnerPayload> STREAM_CODEC=new StreamCodec<>() {
            public PartnerPayload decode(RegistryFriendlyByteBuf b){return new PartnerPayload(b.readVarInt(),b.readBlockPos(),b.readLong());}
            public void encode(RegistryFriendlyByteBuf b,PartnerPayload p){b.writeVarInt(p.menuId);b.writeBlockPos(p.position);b.writeLong(p.identity);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Server to client, at a bucket: the static part of a menu's state, as JSON of {@link StaticData}. */
    public record StaticPayload(int menuId,long identity,String json) implements CustomPacketPayload {
        public static final Type<StaticPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"fluid_static"));
        public static final StreamCodec<RegistryFriendlyByteBuf,StaticPayload> STREAM_CODEC=new StreamCodec<>() {
            public StaticPayload decode(RegistryFriendlyByteBuf b){return new StaticPayload(b.readVarInt(),b.readLong(),b.readUtf(MAX_JSON));}
            public void encode(RegistryFriendlyByteBuf b,StaticPayload p){b.writeVarInt(p.menuId);b.writeLong(p.identity);b.writeUtf(p.json,MAX_JSON);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Server to client, at every bucket of an open menu: the live part, as JSON of {@link LiveData}. */
    public record LivePayload(int menuId,long identity,String json) implements CustomPacketPayload {
        public static final Type<LivePayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"fluid_live"));
        public static final StreamCodec<RegistryFriendlyByteBuf,LivePayload> STREAM_CODEC=new StreamCodec<>() {
            public LivePayload decode(RegistryFriendlyByteBuf b){return new LivePayload(b.readVarInt(),b.readLong(),b.readUtf(MAX_JSON));}
            public void encode(RegistryFriendlyByteBuf b,LivePayload p){b.writeVarInt(p.menuId);b.writeLong(p.identity);b.writeUtf(p.json,MAX_JSON);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar(PROTOCOL).executesOn(HandlerThread.MAIN);
        registrar.playToServer(EditPayload.TYPE,EditPayload.STREAM_CODEC,FluidNetwork::edit);
        registrar.playToServer(RecoverPayload.TYPE,RecoverPayload.STREAM_CODEC,FluidNetwork::recover);
        registrar.playToServer(PartnerPayload.TYPE,PartnerPayload.STREAM_CODEC,(payload,context)->{if(context.player() instanceof ServerPlayer player)openPartner(payload,player);});
        registerDisplays(event,registrar);
        registrar.playToClient(StaticPayload.TYPE,StaticPayload.STREAM_CODEC,(payload,context)->{
            if(context.player().containerMenu instanceof FluidDeviceMenu menu&&menu.containerId==payload.menuId&&menu.identity()==payload.identity)menu.acceptStatic(decodeStatic(payload.json));
        });
        registrar.playToClient(LivePayload.TYPE,LivePayload.STREAM_CODEC,(payload,context)->{
            if(context.player().containerMenu instanceof FluidDeviceMenu menu&&menu.containerId==payload.menuId&&menu.identity()==payload.identity)menu.acceptLive(decodeLive(payload.json));
        });
    }
    public static StaticData decodeStatic(String json){return Objects.requireNonNull(JSON.fromJson(json,StaticData.class));}
    public static LiveData decodeLive(String json){return Objects.requireNonNull(JSON.fromJson(json,LiveData.class));}

    // ---- server: scheduled snapshots and requested cached opening delivery ----

    /** Captures one coherent GUI snapshot at a presentation bucket; no viewer-specific reply is cached. */
    public static MenuData snapshot(FluidWorldAuthority world,WorldTopologyLedger.Registration record,FluidView view){
        if(view.identity()!=record.device().id()||view.inputRevision()!=record.revision())
            throw new IllegalArgumentException("Presentation snapshot registration mismatch");
        return new MenuData(record.device().hydraulicKind(),view,Controls.from(record),world.components(),world.presets(),"",
            world.materialNames(),Arrays.stream(world.model().molecularWeights()).boxed().toList(),world.instruments().sensorReadings(record.device().id(),view),valveData(world,record,view));
    }
    private static ValveData valveData(FluidWorldAuthority world,WorldTopologyLedger.Registration record,FluidView view){
        if(!(record.device().control() instanceof FlowControl.Valve valve))return null;
        if(valve.bare())return new ValveData(false,1,"Lacking components");
        double head=view.devicePressureChange()==null?0:Math.max(0,view.devicePressureChange());
        double rho=view.state()==null?1:view.state().mass()/Math.max(1e-300,view.state().volume());
        var reading=world.instrumentPipeReading(record.device().id());double flow=reading==null?view.massFlow():reading.massFlow();if(reading!=null&&reading.volumeFlow()!=null&&reading.volumeFlow()!=0)rho=reading.massFlow()/reading.volumeFlow();
        double d=record.device().geometry().diameter(),u=view.status().contains("/ CLOSED")?0:view.status().contains("/ VALVE_OPEN")?1:FlowControl.opening(flow,head,rho,Math.PI*d*d/4);
        if(view.status().contains("CONTROL_CONFLICT")||view.status().contains("VALVE_CONFLICT"))return new ValveData(true,u,"Control conflict; holding opening");
        if(view.committedTick()==0&&view.state()==null&&reading==null)return new ValveData(true,u,"Signal unavailable");
        boolean limited;
        if(valve.pressureControl())limited=view.state()==null||Math.abs(view.state().pressure()-valve.targetPressure())>Math.max(.01,1e-6*valve.targetPressure());
        else if(valve instanceof FlowControl.ControlValve modular){double measured=modular.source()==FlowControl.ValveSource.MASS_FLOW?flow:reading!=null&&reading.volumeFlow()!=null?reading.volumeFlow():Double.NaN;limited=!Double.isFinite(measured)||Math.abs(measured-modular.target())>Math.max(1e-10,1e-6*modular.target());}
        else limited=false;
        return new ValveData(true,u,u<=1e-8?(limited?"Closed / target limited":"Closed"):u>=1-1e-8?(limited?"Fully open / target limited":"Fully open"):"Regulating");
    }
    /** Sends a published snapshot to an installed, requested menu; replay uses an empty reply. The presentation checked
     * that the menu is open ({@code FluidPresentation.Host.open}) and the snapshot is its device's. */
    public static void deliver(FluidDeviceMenu menu,boolean withStatic,MenuData data,String reply) {
        var player=menu.serverPlayer();
        if(withStatic) {
            PacketDistributor.sendToPlayer(player,new StaticPayload(menu.containerId,menu.identity(),JSON.toJson(data.staticData(data.view().inputRevision()))));
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.menuPackets);FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.staticPayloads);
        }
        PacketDistributor.sendToPlayer(player,new LivePayload(menu.containerId,menu.identity(),JSON.toJson(new LiveData(data.view(),data.controls(),reply,data.sensors(),data.valve()))));
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.menuPackets);
    }

    // ---- server: inputs are queued, never answered here ----

    public static void recoverSolids(FluidDeviceMenu menu,long revision){PacketDistributor.sendToServer(new RecoverPayload(menu.containerId,menu.position(),menu.identity(),revision));}
    private static void recover(RecoverPayload payload,IPayloadContext context){if(context.player() instanceof ServerPlayer player)recover(payload,player);}
    /** The recovery handler: the same admission as an edit; the recovery is queued as a ledger event and the handler returns without a reply. */
    public static void recover(RecoverPayload payload,ServerPlayer player) {
        if(!(player.containerMenu instanceof FluidDeviceMenu menu))return;
        if(menu.containerId!=payload.menuId||menu.identity()!=payload.identity||!menu.position().equals(payload.position)||!menu.stillValid(player)||menu.debug()||!player.mayBuild()||player.isSpectator()||!player.level().mayInteract(player,payload.position)||!menu.admitEdit(player.server.getTickCount()))return;
        FluidWorldAuthority.find(player.server).ifPresent(world->{
            WorldTopologyLedger.Event event=null;String refusal=null;
            try{event=world.recoverFilter(payload.identity,payload.revision,player);}catch(RuntimeException rejected){refusal=reason(rejected);}
            world.input(menu,event,refusal);
        });
    }
    public static void openPartner(FluidDeviceMenu menu){PacketDistributor.sendToServer(new PartnerPayload(menu.containerId,menu.position(),menu.identity()));}
    /**
     * Opens a heat side's partner: the partner's own cell opens its menu, which replays the partner's last snapshot as
     * any opening does. Nothing is queued. A partner whose cell is not loaded, or in another dimension, opens nothing.
     */
    public static void openPartner(PartnerPayload payload,ServerPlayer player) {
        if(!(player.containerMenu instanceof FluidDeviceMenu menu))return;
        if(menu.containerId!=payload.menuId||menu.identity()!=payload.identity||!menu.position().equals(payload.position)||!menu.stillValid(player)||player.isSpectator())return;
        FluidWorldAuthority.find(player.server).ifPresent(world->{
            var own=world.registrations().get(payload.identity);var heat=own==null?null:own.device().heat();
            var partner=heat==null?null:world.registrations().get(heat.partner());if(partner==null)return;
            var at=partner.device().position();var level=player.serverLevel();var cell=new BlockPos(at.x(),at.y(),at.z());
            if(at.dimension().equals(level.dimension().location().toString())&&level.isLoaded(cell)
                    &&level.getBlockEntity(cell) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell&&shell.fluidIdentity()==partner.device().id())
                shell.open(player,menu.debug());
        });
    }
    public static void sendEdit(FluidDeviceMenu menu,long revision,Controls controls){PacketDistributor.sendToServer(new EditPayload(menu.containerId,menu.position(),menu.identity(),revision,JSON.toJson(controls)));}
    private static void edit(EditPayload payload,IPayloadContext context){if(context.player() instanceof ServerPlayer player)edit(payload,player);}
    /**
     * The edit handler: validates the menu, the player and the payload's revision and bounds as before, queues the
     * change as a ledger event, and returns without a reply. The engine composes the reply with the menu's next
     * bucket. An inadmissible packet (wrong menu, position or identity, a probe, a distant, read-only or flooding
     * player) is dropped with no reply at all, as before.
     */
    public static void edit(EditPayload payload,ServerPlayer player) {
        if(!(player.containerMenu instanceof FluidDeviceMenu menu))return;
        if(menu.containerId!=payload.menuId||menu.identity()!=payload.identity||!menu.position().equals(payload.position)||!menu.stillValid(player)||menu.debug()||!player.mayBuild()||player.isSpectator()||!player.level().mayInteract(player,payload.position)||!menu.admitEdit(player.server.getTickCount()))return;
        FluidWorldAuthority.find(player.server).ifPresent(world->{
            WorldTopologyLedger.Event event=null;String refusal=null;
            try {
                var controls=Objects.requireNonNull(JSON.fromJson(payload.json,Controls.class));
                if(controls.composition().length!=world.components().size())throw new IllegalArgumentException("Composition differs from the server network axis");
                var old=Objects.requireNonNull(world.registrations().get(payload.identity));var d=old.device();
                var geometry=d.geometry();var control=d.control();var spec=old.spec();var heat=d.heat();
                switch(d.hydraulicKind()) {
                    case PIPE,FILTER->geometry=new PipeResistance.Geometry(geometry.length(),controls.diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,geometry.minorLoss());
                    case PUMP->control=new FlowControl.Pump(controls.volumeFlow,controls.maximumAddedPressure,controls.power==Power.ON);
                    case COMPRESSOR->control=new FlowControl.Compressor(controls.volumeFlow,controls.maximumPressureRatio,controls.power==Power.ON);
                    case VALVE->{
                        if(control instanceof FlowControl.ControlValve valve){
                            if(valve.bare())throw new IllegalArgumentException("Lacking components");
                            var source=valve.source();
                            if(source!=FlowControl.ValveSource.PRESSURE){
                                if(controls.valveSource!=FlowControl.ValveSource.MASS_FLOW&&controls.valveSource!=FlowControl.ValveSource.VOLUME_FLOW)throw new IllegalArgumentException("Flowmeter required for flow control");
                                source=controls.valveSource;
                            }else if(controls.valveSource!=FlowControl.ValveSource.PRESSURE)throw new IllegalArgumentException("Pressure probe controls upstream pressure");
                            control=new FlowControl.ControlValve(source,controls.pressure,controls.massFlow,controls.volumeFlow,valve.sensor(),Math.addExact(valve.revision(),1));
                        }else control=new FlowControl.PressureValve(controls.pressure);
                    }
                    case GENERATOR->spec=new FluidDeviceSpec(spec.volume(),controls.temperature,controls.pressure,controls.composition,controls.solids);
                    case VOID->spec=new FluidDeviceSpec(spec.volume(),spec.temperature(),controls.pressure,spec.composition());
                    case RESERVOIR->throw new IllegalArgumentException("Reservoir initialization is fixed; existing fluid is conserved.");
                    // A heat side's passage is its unit's datasheet; an air cooler's fans switch (batch 2026-10-27-heat-exchanger-design).
                    case HEAT_SIDE->{
                        if(!heat.fans())throw new IllegalArgumentException("A heat side's passage is its unit's datasheet.");
                        heat=heat.withControl(heat.control().withShare(controls.power==Power.ON?1:0));
                    }
                }
                // Refused here, before anything is queued, outside the package envelope (the input box, plan decision 9):
                // the reply with the next bucket reads "Not applied: ..." with the envelope named.
                switch(d.hydraulicKind()) {
                    case GENERATOR,VOID->spec.validate(world.model(),d.kind());
                    case VALVE->{if(control instanceof FlowControl.Valve valve&&valve.pressureControl())world.model().domain().requireInputPressure(controls.pressure);}
                    default->{}
                }
                boolean generatorEnabled=d.kind()==TopologyCompiler.Kind.GENERATOR?controls.power==Power.ON:d.generatorEnabled();
                // The edit changes what the menu edits and keeps every other field (a rack lane's width and face points, a side's unit).
                event=world.edit(d.id(),payload.revision,d.withGeometry(geometry).withControl(control).withGeneratorEnabled(generatorEnabled).withHeat(heat),spec);
            }catch(RuntimeException rejected){refusal=reason(rejected);}
            world.input(menu,event,refusal);
        });
    }

    /**
     * The player-facing reason of a refused input: the innermost refusal message. A control refused by its own
     * validation reaches the handler wrapped by the JSON reader, whose message names the constructor and its
     * arguments; the player reads the control's reason.
     */
    public static String reason(Throwable refused) {
        String message=null;
        for(Throwable t=refused;t!=null;t=t.getCause()==t?null:t.getCause())if(t.getMessage()!=null&&(t instanceof IllegalArgumentException||t instanceof IllegalStateException))message=t.getMessage();
        return message!=null?message:String.valueOf(refused.getMessage());
    }

    // ---- requested world-space displays and item settings ----
    // Each requested display keeps its own payload ids and protocol version string: the open tanks' display path rides on
    // PROTOCOL, the others on their own registrars (registerDisplays), so their wire registration is unchanged.

    /** How far from a watcher an open tank's node cell or a sight glass may stand, blocks. */
    public static final double WATCH_RANGE=128;
    /** How far from a watcher a constructed mover's fluid cell may stand, blocks. */
    public static final double MOVER_RANGE=96;
    /** The most open tanks, sight glasses or movers one player watches, each kind on its own. */
    public static final int MAXIMUM_WATCHED=64;

    /**
     * The open tanks' display path (batch 2026-10-21-floating-roof-tanks, plan section 3.4, owner decision FR4 R-B): a client
     * asks to watch and to stop watching tanks by their node cells ({@link TankWatch}); the server answers each new watch
     * with the tank's last published glide to its display, if it has one, and then sends one {@link TankDisplayPayload} at
     * each of the tank's presentation buckets. About 60 bytes a tank every 5 s; nothing is sent unasked.
     */
    public record TankWatch(List<BlockPos> watch,List<BlockPos> unwatch) implements CustomPacketPayload {
        public static final Type<TankWatch> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"tank_watch"));
        public static final StreamCodec<RegistryFriendlyByteBuf,TankWatch> STREAM_CODEC=new StreamCodec<>() {
            public TankWatch decode(RegistryFriendlyByteBuf b){return new TankWatch(positions(b),positions(b));}
            public void encode(RegistryFriendlyByteBuf b,TankWatch p){positions(b,p.watch);positions(b,p.unwatch);}
        };
        public TankWatch{watch=List.copyOf(watch);unwatch=List.copyOf(unwatch);
            if(watch.size()>MAXIMUM_WATCHED||unwatch.size()>MAXIMUM_WATCHED)throw new IllegalArgumentException("Too many tanks in one watch request");}
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Server to client: one tank's glide to its latest display, at its node cell (whole, so the client's roof stands where the server's collides). */
    public record TankDisplayPayload(BlockPos node,TankDisplay.Glide glide) implements CustomPacketPayload {
        public static final Type<TankDisplayPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"tank_display"));
        public static final StreamCodec<RegistryFriendlyByteBuf,TankDisplayPayload> STREAM_CODEC=new StreamCodec<>() {
            public TankDisplayPayload decode(RegistryFriendlyByteBuf b){var node=b.readBlockPos();var from=display(b);return new TankDisplayPayload(node,new TankDisplay.Glide(from,display(b),b.readLong()));}
            public void encode(RegistryFriendlyByteBuf b,TankDisplayPayload p){b.writeBlockPos(p.node);display(b,p.glide.from());display(b,p.glide.to());b.writeLong(p.glide.tick());}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static TankDisplay display(RegistryFriendlyByteBuf b){return new TankDisplay(b.readFloat(),b.readFloat(),b.readFloat(),b.readInt(),b.readInt());}
    private static void display(RegistryFriendlyByteBuf b,TankDisplay d){b.writeFloat(d.level());b.writeFloat(d.waterLevel());b.writeFloat(d.height());b.writeInt(d.oilArgb());b.writeInt(d.waterArgb());}
    private static List<BlockPos> positions(RegistryFriendlyByteBuf b) {
        int count=b.readVarInt();if(count<0||count>MAXIMUM_WATCHED)throw new IllegalArgumentException("Too many tanks in one watch request");
        var list=new ArrayList<BlockPos>(count);for(int i=0;i<count;i++)list.add(b.readBlockPos());
        return list;
    }
    private static void positions(RegistryFriendlyByteBuf b,List<BlockPos> list){b.writeVarInt(list.size());for(var pos:list)b.writeBlockPos(pos);}

    /** A watched sight glass or constructed mover: its cell and its fluid identity. */
    public record Target(BlockPos position,long identity) {
        public Target{position=position.immutable();if(identity<=0)throw new IllegalArgumentException("Invalid watched identity");}
    }
    /**
     * Client to server: explicit, bounded visual subscriptions to sight glasses ({@link #SIGHT_GLASSES}) or to movers
     * ({@link #MOVERS}): the targets to start and to stop watching. The payload type names the kind.
     */
    public record Watch(Type<Watch> type,List<Target> watch,List<Target> unwatch) implements CustomPacketPayload {
        public static final Type<Watch> SIGHT_GLASSES=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"sight_glass_watch"));
        public static final Type<Watch> MOVERS=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"mover_watch"));
        public Watch{Objects.requireNonNull(type);watch=List.copyOf(watch);unwatch=List.copyOf(unwatch);validate(watch);validate(unwatch);}
        static StreamCodec<RegistryFriendlyByteBuf,Watch> codec(Type<Watch> type){return new StreamCodec<>() {
            public Watch decode(RegistryFriendlyByteBuf b){return new Watch(type,targets(b),targets(b));}
            public void encode(RegistryFriendlyByteBuf b,Watch p){targets(b,p.watch);targets(b,p.unwatch);}
        };}
    }
    /** Server to client: a sight glass's colour, bubble strength and flow direction, never process or menu contents. */
    public record SightGlassPayload(Target target,PipePresentation.SightGlass sample) implements CustomPacketPayload {
        public static final Type<SightGlassPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"sight_glass_display"));
        public static final StreamCodec<RegistryFriendlyByteBuf,SightGlassPayload> STREAM_CODEC=new StreamCodec<>() {
            public SightGlassPayload decode(RegistryFriendlyByteBuf b){return new SightGlassPayload(readTarget(b),new PipePresentation.SightGlass(b.readInt(),b.readUnsignedByte(),direction(b.readUnsignedByte())));}
            public void encode(RegistryFriendlyByteBuf b,SightGlassPayload p){writeTarget(b,p.target);b.writeInt(p.sample.argb());b.writeByte(p.sample.bubbles());b.writeByte(direction(p.sample.flowDirection()));}
        };
        public SightGlassPayload{Objects.requireNonNull(target);Objects.requireNonNull(sample);}
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Server to client: a constructed mover's requested presentation (which loop, running or not); no process inventory or solver results. */
    public record MoverState(Target target,boolean compressor,boolean running) implements CustomPacketPayload {
        public MoverState{Objects.requireNonNull(target);}
        public static final Type<MoverState> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"mover_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,MoverState> STREAM_CODEC=new StreamCodec<>() {
            public MoverState decode(RegistryFriendlyByteBuf b){return new MoverState(readTarget(b),flag(b),flag(b));}
            public void encode(RegistryFriendlyByteBuf b,MoverState p){writeTarget(b,p.target);b.writeBoolean(p.compressor);b.writeBoolean(p.running);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static PhysicalFluidTopology.Direction direction(int code){return switch(code){case 0->null;case 1->PhysicalFluidTopology.Direction.DOWN;case 2->PhysicalFluidTopology.Direction.UP;case 3->PhysicalFluidTopology.Direction.NORTH;case 4->PhysicalFluidTopology.Direction.SOUTH;case 5->PhysicalFluidTopology.Direction.WEST;case 6->PhysicalFluidTopology.Direction.EAST;default->throw new IllegalArgumentException("Invalid sight glass flow direction");};}
    private static int direction(PhysicalFluidTopology.Direction direction){return direction==null?0:switch(direction){case DOWN->1;case UP->2;case NORTH->3;case SOUTH->4;case WEST->5;case EAST->6;};}
    private static boolean flag(RegistryFriendlyByteBuf b){int flag=b.readUnsignedByte();if(flag>1)throw new IllegalArgumentException("Invalid mover state flag");return flag==1;}
    private static void validate(List<Target> list){if(list.size()>MAXIMUM_WATCHED||new HashSet<>(list).size()!=list.size())throw new IllegalArgumentException("Invalid watch list");}
    private static Target readTarget(RegistryFriendlyByteBuf b){return new Target(b.readBlockPos(),b.readLong());}
    private static void writeTarget(RegistryFriendlyByteBuf b,Target t){b.writeBlockPos(t.position);b.writeLong(t.identity);}
    private static List<Target> targets(RegistryFriendlyByteBuf b){int count=b.readVarInt();if(count<0||count>MAXIMUM_WATCHED)throw new IllegalArgumentException("Too many watched targets");var result=new ArrayList<Target>(count);for(int i=0;i<count;i++)result.add(readTarget(b));return result;}
    private static void targets(RegistryFriendlyByteBuf b,List<Target> list){b.writeVarInt(list.size());for(var t:list)writeTarget(b,t);}

    /** An installed instrument a client watches: its host's cell, its key and its instance. Ordinary block updates carry no live measurements. */
    public record InstrumentTarget(BlockPos position,Instrument.Key key,UUID instance) {
        public InstrumentTarget{position=position.immutable();Objects.requireNonNull(key);Objects.requireNonNull(instance);}
    }
    /** Client to server: explicit bounded instrument watches. */
    public record InstrumentWatch(List<InstrumentTarget> watch,List<InstrumentTarget> unwatch) implements CustomPacketPayload {
        public static final Type<InstrumentWatch> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"instrument_watch"));
        public static final StreamCodec<RegistryFriendlyByteBuf,InstrumentWatch> CODEC=new StreamCodec<>(){
            public InstrumentWatch decode(RegistryFriendlyByteBuf b){return new InstrumentWatch(instrumentTargets(b),instrumentTargets(b));}
            public void encode(RegistryFriendlyByteBuf b,InstrumentWatch p){instrumentTargets(b,p.watch);instrumentTargets(b,p.unwatch);}
        };
        public InstrumentWatch{watch=List.copyOf(watch);unwatch=List.copyOf(unwatch);for(var l:List.of(watch,unwatch))if(l.size()>64||new HashSet<>(l).size()!=l.size())throw new IllegalArgumentException("Invalid instrument watches");}
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Server to client: one instrument's latest published sample. */
    public record InstrumentDisplay(InstrumentTarget target,Instrument.Sample sample,long publishedTick) implements CustomPacketPayload {
        public static final Type<InstrumentDisplay> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"instrument_display"));
        public static final StreamCodec<RegistryFriendlyByteBuf,InstrumentDisplay> CODEC=new StreamCodec<>(){
            public InstrumentDisplay decode(RegistryFriendlyByteBuf b){var t=instrumentTarget(b);double value=b.readDouble();long tick=b.readLong();int code=b.readUnsignedByte();if(code>=Instrument.Availability.values().length)throw new IllegalArgumentException("Invalid instrument availability");return new InstrumentDisplay(t,new Instrument.Sample(value,tick,Instrument.Availability.fromCode(code)),b.readLong());}
            public void encode(RegistryFriendlyByteBuf b,InstrumentDisplay p){instrumentTarget(b,p.target);b.writeDouble(p.sample.value());b.writeLong(p.sample.tick());b.writeByte(p.sample.availability().code());b.writeLong(p.publishedTick);}
        };
        public InstrumentDisplay{if(publishedTick<sample.tick())throw new IllegalArgumentException("Invalid publication age");}
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static InstrumentTarget instrumentTarget(RegistryFriendlyByteBuf b){return new InstrumentTarget(b.readBlockPos(),new Instrument.Key(b.readLong(),b.readUtf(64)),b.readUUID());}
    private static void instrumentTarget(RegistryFriendlyByteBuf b,InstrumentTarget t){b.writeBlockPos(t.position);b.writeLong(t.key.host());b.writeUtf(t.key.mount(),64);b.writeUUID(t.instance);}
    private static List<InstrumentTarget> instrumentTargets(RegistryFriendlyByteBuf b){int n=b.readVarInt();if(n<0||n>64)throw new IllegalArgumentException("Too many instrument targets");var l=new ArrayList<InstrumentTarget>();for(int i=0;i<n;i++)l.add(instrumentTarget(b));return l;}
    private static void instrumentTargets(RegistryFriendlyByteBuf b,List<InstrumentTarget> l){b.writeVarInt(l.size());l.forEach(t->instrumentTarget(b,t));}

    /** Client to server: a gauge's edited settings, from its open settings menu. */
    public record GaugeSave(int menu,GaugeSettings settings) implements CustomPacketPayload {
        public static final Type<GaugeSave> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"gauge_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf,GaugeSave> CODEC=new StreamCodec<>(){
            public GaugeSave decode(RegistryFriendlyByteBuf b){return new GaugeSave(b.readVarInt(),GaugeSettings.STREAM_CODEC.decode(b));}
            public void encode(RegistryFriendlyByteBuf b,GaugeSave p){b.writeVarInt(p.menu);GaugeSettings.STREAM_CODEC.encode(b,p.settings);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Client to server: the pipeline tool's target diameter, and the laying tool's primary and secondary paint. Settings only: it does not read, edit or subscribe to a fluid process. */
    public record PipelineToolSave(int menuId,double diameter,int paint,int secondary) implements CustomPacketPayload {
        public static final Type<PipelineToolSave> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"pipeline_tool_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PipelineToolSave> STREAM_CODEC=StreamCodec.composite(
                ByteBufCodecs.VAR_INT,PipelineToolSave::menuId,ByteBufCodecs.DOUBLE,PipelineToolSave::diameter,ByteBufCodecs.INT,PipelineToolSave::paint,ByteBufCodecs.INT,PipelineToolSave::secondary,PipelineToolSave::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /**
     * Client to server: the laying tool's commands (batch 2026-10-26-pipe-laying-system, plan 4.1). The client decides
     * which a use means, because only it knows whether a line has a start; the server checks each against its own world.
     */
    public record PipeLayingOpen(boolean offHand) implements CustomPacketPayload {
        public static final Type<PipeLayingOpen> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"pipe_laying_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PipeLayingOpen> STREAM_CODEC=StreamCodec.composite(ByteBufCodecs.BOOL,PipeLayingOpen::offHand,PipeLayingOpen::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Lay {@code cells}, a lane on {@code slots} where one is not -1, from the anchor clicked at {@code start} to the one clicked at {@code end}. */
    public record PipeLay(boolean offHand,com.wormzjl.icheme.world.level.block.PipeAnchor.Click start,com.wormzjl.icheme.world.level.block.PipeAnchor.Click end,List<BlockPos> cells,List<Integer> slots) implements CustomPacketPayload {
        public static final Type<PipeLay> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"pipe_lay"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PipeLay> STREAM_CODEC=StreamCodec.composite(ByteBufCodecs.BOOL,PipeLay::offHand,com.wormzjl.icheme.world.level.block.PipeAnchor.Click.STREAM_CODEC,PipeLay::start,
                com.wormzjl.icheme.world.level.block.PipeAnchor.Click.STREAM_CODEC,PipeLay::end,BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(com.wormzjl.icheme.science.equipment.PipeRoute.Limits.DEFAULT.cells())),PipeLay::cells,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(com.wormzjl.icheme.science.equipment.PipeRoute.Limits.DEFAULT.cells())),PipeLay::slots,PipeLay::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Server to client: the answer to a lay, for the action bar, and whether it was laid; the client keeps the line's start until it was. */
    public record PipeLayAnswer(net.minecraft.network.chat.Component message,boolean laid) implements CustomPacketPayload {
        public static final Type<PipeLayAnswer> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"pipe_lay_answer"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PipeLayAnswer> STREAM_CODEC=StreamCodec.composite(net.minecraft.network.chat.ComponentSerialization.TRUSTED_STREAM_CODEC,PipeLayAnswer::message,
                ByteBufCodecs.BOOL,PipeLayAnswer::laid,PipeLayAnswer::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record PipeDismantle(boolean offHand,com.wormzjl.icheme.world.level.block.PipeAnchor.Click click) implements CustomPacketPayload {
        public static final Type<PipeDismantle> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"pipe_dismantle"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PipeDismantle> STREAM_CODEC=StreamCodec.composite(ByteBufCodecs.BOOL,PipeDismantle::offHand,com.wormzjl.icheme.world.level.block.PipeAnchor.Click.STREAM_CODEC,PipeDismantle::click,PipeDismantle::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static InteractionHand hand(boolean offHand){return offHand?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;}
    /** The server's answer to a dismantle, on the player's action bar (a lay's goes back as {@link PipeLayAnswer}). */
    private static void answer(ServerPlayer player,net.minecraft.network.chat.Component refusal,net.minecraft.network.chat.Component done){player.displayClientMessage(refusal!=null?refusal:done,true);}

    /** The requested displays and the item settings saves: the tanks' on the fluid registrar, the others each on its own version string. */
    private static void registerDisplays(RegisterPayloadHandlersEvent event,PayloadRegistrar fluid) {
        fluid.playToServer(TankWatch.TYPE,TankWatch.STREAM_CODEC,(payload,context)->{
            if(context.player() instanceof ServerPlayer player)FluidWorldAuthority.find(player.server).ifPresent(world->world.watchTanks(player,payload.watch,payload.unwatch));
        });
        fluid.playToClient(TankDisplayPayload.TYPE,TankDisplayPayload.STREAM_CODEC,(payload,context)->com.wormzjl.icheme.client.TankRenderer.accept(payload.node,payload.glide));
        var sightGlass=event.registrar("sight-glass-3").executesOn(HandlerThread.MAIN);
        sightGlass.playToServer(Watch.SIGHT_GLASSES,Watch.codec(Watch.SIGHT_GLASSES),(payload,context)->{
            if(context.player() instanceof ServerPlayer player)FluidWorldAuthority.find(player.server).ifPresent(world->world.watchSightGlasses(player,payload.watch,payload.unwatch));
        });
        sightGlass.playToClient(SightGlassPayload.TYPE,SightGlassPayload.STREAM_CODEC,(payload,context)->com.wormzjl.icheme.client.SightGlassRenderer.accept(payload.target,payload.sample));
        var mover=event.registrar("mover-1").executesOn(HandlerThread.MAIN);
        mover.playToServer(Watch.MOVERS,Watch.codec(Watch.MOVERS),(p,context)->{
            if(context.player() instanceof ServerPlayer player)FluidWorldAuthority.find(player.server).ifPresent(world->world.watchMovers(player,p.watch,p.unwatch));
        });
        mover.playToClient(MoverState.TYPE,MoverState.STREAM_CODEC,(p,context)->com.wormzjl.icheme.client.MoverSounds.accept(p));
        var instruments=event.registrar("instruments-1").executesOn(HandlerThread.MAIN);
        instruments.playToServer(InstrumentWatch.TYPE,InstrumentWatch.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)FluidWorldAuthority.find(player.server).ifPresent(w->w.instruments().watch(player,p.watch,p.unwatch));});
        instruments.playToClient(InstrumentDisplay.TYPE,InstrumentDisplay.CODEC,(p,c)->com.wormzjl.icheme.client.InstrumentRenderer.accept(p));
        instruments.playToServer(GaugeSave.TYPE,GaugeSave.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player&&player.containerMenu instanceof GaugeMenu menu&&menu.containerId==p.menu&&menu.stillValid(player)&&menu.initial().kind()==p.settings.kind()){menu.save(p.settings);player.inventoryMenu.broadcastChanges();}});
        var tools=event.registrar("pipeline-tool-2").executesOn(HandlerThread.MAIN);
        tools.playToServer(PipelineToolSave.TYPE,PipelineToolSave.STREAM_CODEC,(payload,context)->{
            if(!(context.player() instanceof ServerPlayer player)||!(player.containerMenu instanceof PipelineToolMenu menu)
                    ||menu.containerId!=payload.menuId()||!menu.stillValid(player)||!PipelineToolItem.Settings.valid(payload.diameter())||!com.wormzjl.icheme.science.equipment.Paint.line(payload.paint())||!com.wormzjl.icheme.science.equipment.Paint.line(payload.secondary()))return;
            menu.save(new PipelineToolItem.Settings(payload.diameter()),payload.paint(),payload.secondary());
            // The screen closes after sending Save. Also sync the inventory menu so its cached copy carries the new target.
            player.inventoryMenu.broadcastChanges();
        });
        tools.playToServer(PipeLayingOpen.TYPE,PipeLayingOpen.STREAM_CODEC,(p,c)->{
            if(c.player() instanceof ServerPlayer player&&!player.isSpectator()&&player.getItemInHand(hand(p.offHand)).is(com.wormzjl.icheme.registry.ModItems.PIPE_LAYING_TOOL.get()))PipelineToolMenu.open(player,hand(p.offHand));
        });
        tools.playToServer(PipeLay.TYPE,PipeLay.STREAM_CODEC,(p,c)->{
            if(!(c.player() instanceof ServerPlayer player))return;
            var refusal=com.wormzjl.icheme.world.level.block.PipeLaying.lay(player,hand(p.offHand),p.start,p.end,p.cells,p.slots);
            PacketDistributor.sendToPlayer(player,new PipeLayAnswer(refusal!=null?refusal:com.wormzjl.icheme.world.level.block.PipeLaying.message("laid",p.cells.size()),refusal==null));
        });
        tools.playToClient(PipeLayAnswer.TYPE,PipeLayAnswer.STREAM_CODEC,(p,c)->com.wormzjl.icheme.client.PipeLayingClient.answered(p.message(),p.laid()));
        tools.playToServer(PipeDismantle.TYPE,PipeDismantle.STREAM_CODEC,(p,c)->{
            if(c.player() instanceof ServerPlayer player)answer(player,com.wormzjl.icheme.world.level.block.PipeLaying.dismantle(player,hand(p.offHand),p.click),
                    com.wormzjl.icheme.world.level.block.PipeLaying.message("dismantled"));
        });
    }
    /** Server: sends {@code glide} of the tank at {@code node} to {@code player}. */
    public static void sendTank(ServerPlayer player,BlockPos node,TankDisplay.Glide glide){PacketDistributor.sendToPlayer(player,new TankDisplayPayload(node,glide));}
    public static void sendSightGlass(ServerPlayer player,Target target,PipePresentation.SightGlass sample){PacketDistributor.sendToPlayer(player,new SightGlassPayload(target,sample));}
    public static void sendMover(ServerPlayer player,MoverState state){PacketDistributor.sendToPlayer(player,state);}
    public static void sendInstrument(ServerPlayer player,InstrumentTarget target,Instrument.Sample sample,long tick){PacketDistributor.sendToPlayer(player,new InstrumentDisplay(target,sample,tick));}
    /** Client: asks the server to watch and to stop watching tanks. */
    public static void requestTanks(List<BlockPos> watch,List<BlockPos> unwatch){if(!watch.isEmpty()||!unwatch.isEmpty())PacketDistributor.sendToServer(new TankWatch(watch,unwatch));}
    /** Client: asks the server to watch and to stop watching sight glasses ({@link Watch#SIGHT_GLASSES}) or movers ({@link Watch#MOVERS}). */
    public static void request(CustomPacketPayload.Type<Watch> kind,List<Target> watch,List<Target> unwatch){if(!watch.isEmpty()||!unwatch.isEmpty())PacketDistributor.sendToServer(new Watch(kind,watch,unwatch));}
    public static void requestInstruments(List<InstrumentTarget> watch,List<InstrumentTarget> unwatch){if(!watch.isEmpty()||!unwatch.isEmpty())PacketDistributor.sendToServer(new InstrumentWatch(watch,unwatch));}
    public static void saveGauge(int menu,GaugeSettings settings){PacketDistributor.sendToServer(new GaugeSave(menu,settings));}
    public static void savePipelineTool(int menuId,double diameter,int paint,int secondary){PacketDistributor.sendToServer(new PipelineToolSave(menuId,diameter,paint,secondary));}
    public static void openPipeLaying(boolean offHand){PacketDistributor.sendToServer(new PipeLayingOpen(offHand));}
    public static void layPipe(boolean offHand,com.wormzjl.icheme.world.level.block.PipeAnchor.Click start,com.wormzjl.icheme.world.level.block.PipeAnchor.Click end,List<BlockPos> cells,List<Integer> slots){PacketDistributor.sendToServer(new PipeLay(offHand,start,end,cells,slots));}
    public static void dismantlePipe(boolean offHand,com.wormzjl.icheme.world.level.block.PipeAnchor.Click click){PacketDistributor.sendToServer(new PipeDismantle(offHand,click));}

    // ---- client: the last delivered view of each device ----

    private static final int REMEMBERED_VIEWS=32;
    private static final Map<Long,MenuData> DELIVERED=new LinkedHashMap<>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long,MenuData> eldest){return size()>REMEMBERED_VIEWS;}
    };
    /** Client: remembers the last state delivered for a device, so a menu reopened on it shows that view until its next bucket. */
    public static synchronized void remember(long identity,MenuData data){DELIVERED.put(identity,Objects.requireNonNull(data));}
    /** Client: the last state delivered in this session, or null, shown until the requested server snapshot arrives. */
    public static synchronized MenuData lastDelivered(long identity){return DELIVERED.get(identity);}
    /** Client: forgets every delivered state (leaving a world; identities belong to a world). */
    public static synchronized void forgetDelivered(){DELIVERED.clear();}
}
