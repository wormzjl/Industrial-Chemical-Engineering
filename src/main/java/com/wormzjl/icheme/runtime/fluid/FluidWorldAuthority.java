package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.*;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.EquipmentShellBlock;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import java.util.*;

/** Server-owned hydraulic authority. Chunk events bind placed devices; engine buckets publish process presentation. */
public final class FluidWorldAuthority implements AutoCloseable {
    private static final Map<MinecraftServer,FluidWorldAuthority> SERVERS=new IdentityHashMap<>();
    private record LoadedChunk(ResourceKey<Level> dimension,long chunk) {}
    /** Server-thread chunk notifications that arrived before ServerStarting installed the authority. */
    private static final Map<MinecraftServer,Set<LoadedChunk>> EARLY_CHUNKS=new IdentityHashMap<>();
    /** A registered device before and after a queued topology edit, null for none. */
    public record DeviceChange(PhysicalFluidTopology.Position position,PhysicalFluidTopology.Device before,PhysicalFluidTopology.Device after) {}
    /** Told of each queued topology edit (batch 2026-10-26-multi-machine-framework: assembly reports revert on it). */
    @FunctionalInterface public interface EditListener{void edited(MinecraftServer server,List<DeviceChange> changes);}
    private static final List<EditListener> LISTENERS=new java.util.concurrent.CopyOnWriteArrayList<>();
    public static void listen(EditListener listener){LISTENERS.add(Objects.requireNonNull(listener));}
    private final MinecraftServer server;
    private final MaterialCatalog catalog;
    private final Map<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models=new HashMap<>();
    private final FluidThermodynamics model;
    private final FluidPropertyReloadGuard propertyReload;
    private String propertyHold;
    private final IChemE.FluidOptions options;
    private final List<FluidPresetCatalog.Preset> presetCache;
    private final List<String> componentNames;
    private final FluidSavedData data;
    private final WorldTopologyLedger topology;
    private final MinecraftFluidRuntime runtime;
    /** Island ownership, compiler diagnostics and chunk membership of the registered devices, and the event path. */
    private final PhysicalRegistry registry;
    /**
     * Queued topology events apply as one batch at the next tick's hook, or earlier when something reads what they
     * change (a view, a capture, a filter recovery). A run of events inside one tick (a command placing thousands of
     * blocks) applies every this many, far inside the ledger's queue bound, so the queue never waits on a whole command.
     */
    static final int APPLY_BATCH=1024;
    /** How long an undeliverable recovery (offline player, full inventory, unloaded chunk) waits for its next attempt. */
    private static final int RECOVERY_RETRY_TICKS=20;
    private final Set<Long> unboundBindings=new HashSet<>();
    /** Equipment whose remaining chunks must load before its complete structure can be registered. */
    private final Map<PhysicalFluidTopology.Position,EquipmentStructure.Vessel> unboundStructures=new LinkedHashMap<>();
    /** Engine-owned presentation: loaded devices and open menus, updated on their island's bucket only. */
    private final FluidPresentation<com.wormzjl.icheme.world.inventory.FluidDeviceMenu> presentation;
    /** Complete immutable snapshots from the last bucket; bounded by loaded/open devices, never player replies. */
    private final Map<Long,com.wormzjl.icheme.network.FluidNetwork.MenuData> publishedMenuData=new HashMap<>();
    /** Open tanks' displays from their last bucket, and who watches them (owner decision FR4, request path R-B); bounded by loaded open tanks. */
    /** The open tanks' latest glides (their roofs' motion to the latest display), replayed to a new watcher. */
    private final Map<Long,TankDisplay.Glide> publishedGlides=new HashMap<>();
    private final Watchers tankWatchers=new Watchers();
    private final Watchers sightGlassWatchers=new Watchers();
    private final Map<Long,PipePresentation.SightGlass> publishedSightGlasses=new HashMap<>();
    private final Watchers moverWatchers=new Watchers();
    private final Map<Long,com.wormzjl.icheme.network.FluidNetwork.MoverState> publishedMovers=new HashMap<>();
    private final com.wormzjl.icheme.runtime.instrument.InstrumentService instruments;
    public com.wormzjl.icheme.runtime.instrument.InstrumentService instruments(){owned();return instruments;}
    private boolean closed;
    private long lastDebugChat=Long.MIN_VALUE;
    private MaterialCatalog observedSolidData;
    private String solidPropertyHold;
    private java.util.function.BiConsumer<List<IslandCoordinator.Snapshot>,Map<Long,IslandCoordinator.Metrics>> observer;

    private FluidWorldAuthority(MinecraftServer server) {
        this.server=server;owned();catalog=MaterialRuntime.active();
        options=IChemE.fluidOptions();
        // Both stores open before anything starts: one this build cannot read refuses the world with its files as they are.
        var accessories=com.wormzjl.icheme.runtime.instrument.InstrumentService.Store.open(server);
        data=FluidSavedData.open(server,this::model,catalog);
        model=model(new FluidCheckpointCodec.PackageKey(com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog)));
        propertyReload=new FluidPropertyReloadGuard(catalog,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),model);
        presetCache=FluidPresetCatalog.resolve(catalog);componentNames=model.components();
        var savedTopology=data.world().orElseGet(()->WorldTopologyLedger.Snapshot.empty(catalog));savedTopology.basis().requireCurrent(catalog);
        FluidPropertyReloadGuard.validateSolids(catalog.solids(),data.checkpoint(),savedTopology);observedSolidData=catalog;
        topology=new WorldTopologyLedger(savedTopology);
        presentation=new FluidPresentation<>(new PresentationHost(),topology.onlineTick());
        var settings=new IslandCoordinator.Settings(options.wallBudgetNanos(),options.wallBudgetNanos()*3/4,64,options.adaptiveCadence(),options.initialCadenceTicks(),options.certificates());
        // Island clocks read the world's online tick: one epoch increment per tick advances every island.
        runtime=new MinecraftFluidRuntime(server,this::published,settings,topology::onlineTick);
        runtime.coordinator().onReplayed(this::replayed);
        runtime.coordinator().onPhaseHold(this::phaseHeld);runtime.coordinator().nodeNames(this::deviceLabel);
        registry=new PhysicalRegistry(topology,model,new InlineFilter(options.solids().filterCapacity(),options.solids().filterResistance(),com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY,0),new RegistryHost());
        for(var entry:data.checkpoint().islands())runtime.register(dimension(entry.dimension()),entry.snapshot(),model(entry.key()));
        logLoad();
        registry.flowmeters(accessories.allSensors().stream().filter(s->s.kind()==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.FLOW).map(s->s.key().host()).toList());
        registry.load();data.bindCapture(this::capture);
        instruments=new com.wormzjl.icheme.runtime.instrument.InstrumentService(server,this,accessories);
        instruments.reconcileLegacyValves();
        // Block entities whose chunks loaded before this authority existed never reported themselves: every device
        // in an already loaded chunk is marked once, and its bucket binds and presents it. Nothing is pushed here.
        registry.forEachChunk((name,chunk,devices)->{var level=server.getLevel(dimension(name));var at=new net.minecraft.world.level.ChunkPos(chunk);
            if(level!=null&&level.hasChunk(at.x,at.z))for(long id:devices)presentation.markDirty(id);});
        // Saved recoveries are first attempted on the first tick, as before, then on their retry deadline.
        if(topology.hasRecoveries())scheduleRecoveryRetry(1);
    }
    /** What the load restored: the checkpoint format, islands, certificates saved and restored (a restored island is
     * materialised and holds only its horizon; it solves nothing at the load), and every discarded certificate. */
    private void logLoad() {
        var loaded=runtime.coordinator().observe();
        long saved=data.checkpoint().islands().stream().filter(e->e.snapshot().certificate().flatMap(IslandCoordinator.Certified::saved).isPresent()).count();
        long restored=loaded.stream().filter(s->s.certificate().isPresent()).count();
        IChemE.LOGGER.info("fluid_world status=LOADED format={} route={} online_tick={} islands={} certificates_saved={} certificates_restored={} awake={}{}",
                FluidCheckpointCodec.VERSION,data.route(),topology.onlineTick(),loaded.size(),saved,restored,loaded.size()-restored,data.reason().isEmpty()?"":" reason="+data.reason());
        for(var s:loaded)if(s.status().startsWith("WAITING: saved "))IChemE.LOGGER.warn("fluid_island={} status=CERTIFICATE_DISCARDED detail={}",s.id(),s.status());
    }
    public static void start(MinecraftServer server) {
        if(SERVERS.containsKey(server))throw new IllegalStateException("Duplicate fluid world authority");
        var world=new FluidWorldAuthority(server);SERVERS.put(server,world);
        var early=EARLY_CHUNKS.remove(server);
        if(early!=null)for(var loaded:early)world.loadedChunk(loaded.dimension(),loaded.chunk());
    }
    /** Retain early chunk loads as well as normal ones; called only on this server's thread. */
    public static void loadedChunk(MinecraftServer server,ResourceKey<Level> dimension,long chunk) {
        var world=find(server).orElse(null);
        if(world==null)EARLY_CHUNKS.computeIfAbsent(server,k->new LinkedHashSet<>()).add(new LoadedChunk(dimension,chunk));
        else world.loadedChunk(dimension,chunk);
    }
    public static Optional<FluidWorldAuthority> find(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Fluid world lookup requires server thread");return Optional.ofNullable(SERVERS.get(server));}
    public static void tick(MinecraftServer server){find(server).ifPresent(FluidWorldAuthority::tick);}
    /** Also called before between-tick completion routing, so an old worker cannot win a reload race. */
    public static void refreshProperties(MinecraftServer server){find(server).ifPresent(FluidWorldAuthority::refreshProperties);}
    private void refreshProperties() {
        owned();if(closed)return;
        var current=MaterialRuntime.active();String next=propertyReload.inspect(current).orElse(null);
        if(current!=observedSolidData){observedSolidData=current;try{var saved=capture();FluidPropertyReloadGuard.validateSolids(current.solids(),saved.checkpoint(),saved.world());solidPropertyHold=null;}catch(IllegalArgumentException incompatible){solidPropertyHold="HELD: solid property data changed; save and reopen the world to read its solids on the new data";}}
        if(next==null)next=solidPropertyHold;
        if(Objects.equals(next,propertyHold))return;
        propertyHold=next;
        if(next!=null)runtime.coordinator().suspendForPropertyChange(next);
        else runtime.coordinator().resumeQualifiedProperties();
        presentation.markAllLoaded();
        data.setDirty();
    }
    public static void stop(MinecraftServer server){find(server).ifPresent(FluidWorldAuthority::close);}
    public static void forget(MinecraftServer server){EARLY_CHUNKS.remove(server);com.wormzjl.icheme.network.ColumnV3Network.forgetPresentation(server);com.wormzjl.icheme.network.AssemblyNetwork.forget(server);var world=SERVERS.remove(server);if(world!=null)world.close();}
    private void owned(){if(!server.isSameThread())throw new IllegalStateException("Fluid authority requires the logical server thread");}
    /** Every island of this world solves on a model built from the same captured options, so the
     * configured trace cutoff is one value for every worker and every retained solver: a retained
     * solver is replaced whenever its model identity changes, and the model is the cutoff's home. */
    private FluidThermodynamics model(FluidCheckpointCodec.PackageKey key){return models.computeIfAbsent(key,k->FluidThermodynamics.forNetwork(catalog,k.packageId(),options.maximumVelocity(),options.traceCutoffMoleFraction(),options.solids()));}
    private static ResourceKey<Level> dimension(String name){return ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(name));}
    public List<FluidPresetCatalog.Preset> presets(){owned();return presetCache;}
    public List<String> components(){owned();return componentNames;}
    /** The world's network property model; its {@code domain()} is where a state may be evaluated. */
    public FluidThermodynamics model(){owned();return model;}
    public Map<String,MaterialName> materialNames(){owned();var result=new LinkedHashMap<String,MaterialName>();for(String id:componentNames)result.put(id,catalog.name(id));return Map.copyOf(result);}
    public long onlineTick(){owned();return topology.onlineTick();}
    /** Optional read-only diagnostics; all values are immutable and callbacks run on the server thread. */
    public void observe(java.util.function.BiConsumer<List<IslandCoordinator.Snapshot>,Map<Long,IslandCoordinator.Metrics>> observer){owned();this.observer=observer;}
    /** Every island as stored, without materialising certified ones: a diagnostic read that advances no island. The
     * events queued since the last application are applied first, so the islands they create are among them. */
    public List<IslandCoordinator.Snapshot> diagnosticSnapshots(){owned();flush();return runtime.coordinator().observe();}
    /** Materialises every certified island to now, as a save does; the observer receives the replayed accounting. */
    public void materialiseAll(){owned();flush();runtime.coordinator().snapshots();}
    /** The rest and steady-flow certificate policy captured at server start. */
    public CertificatePolicy certificates(){owned();return options.certificates();}
    /** Why an island's last closed interval did not certify it, or null; diagnostics only. */
    public String certificationRefusal(long island){owned();return runtime.coordinator().certificationRefusal(island);}
    /** What the certificate evidence measured at an island's last usable solved interval, or null; diagnostics only. */
    public IslandCoordinator.Evidence certificationEvidence(long island){owned();return runtime.coordinator().certificationEvidence(island);}
    public Map<Long,WorldTopologyLedger.Registration> registrations(){owned();return registry.registrations();}
    /** The registration at a position (every block-entity load and binding asks). */
    public Optional<WorldTopologyLedger.Registration> at(PhysicalFluidTopology.Position position){owned();return registry.at(position);}
    /** Applied events that did nothing for the player who asked (a recovery that found no cake or a full inventory), by event, for their reply. Bounded. */
    private final LinkedHashMap<UUID,String> refusedEvents=new LinkedHashMap<>();
    private void refuseEvent(UUID event,String reason){refusedEvents.put(event,reason);if(refusedEvents.size()>WorldTopologyLedger.MAXIMUM_EVENTS)refusedEvents.remove(refusedEvents.keySet().iterator().next());}
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing) {
        return place(position,kind,facing,List.of());
    }
    /** Registry reconstruction reads installed cutoff state from the placed block. */
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff cutoff) {
        return place(position,kind,facing,List.of(),cutoff);
    }
    /** Rebuild installed pressure controls directly from static block state, without an intermediate passive pipe. */
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff cutoff,boolean pressureValve) {
        return place(position,kind,facing,List.of(),cutoff,pressureValve,false,TopologyCompiler.Kind.PIPE,PhysicalFluidTopology.ALL_FACES);
    }
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff cutoff,boolean pressureValve,boolean filter){return place(position,kind,facing,List.of(),cutoff,pressureValve,filter,TopologyCompiler.Kind.PIPE,PhysicalFluidTopology.ALL_FACES);}
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff cutoff,boolean pressureValve,boolean filter,TopologyCompiler.Kind inlineMover){return place(position,kind,facing,List.of(),cutoff,pressureValve,filter,inlineMover,PhysicalFluidTopology.ALL_FACES);}
    /** A placed block registered again from what it holds (a chunk load after the D4 reset): a laid pipe keeps its joints. */
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff cutoff,boolean pressureValve,boolean filter,TopologyCompiler.Kind inlineMover,int joints){return place(position,kind,facing,List.of(),cutoff,pressureValve,filter,inlineMover,joints);}
    /** A cell of a laid line: its position (a rack lane's too), its facing (along its run), its joints, and a lane's width in positions and face points (0: a pipe block). */
    public record Laid(PhysicalFluidTopology.Position position,PhysicalFluidTopology.Direction facing,int joints,int slots,long points){}
    /**
     * Registers a laid line as one ledger event (batch 2026-10-26-pipe-laying-system, plan 4.4): a passive pipe of bore
     * {@code diameter} per cell, with its joints, and the new joints of the laid pipes it starts or ends at
     * ({@code hosts}, by identity). Returns the new pipes' registrations in the order of {@code cells}.
     */
    public List<WorldTopologyLedger.Registration> lay(List<Laid> cells,double diameter,Map<Long,Integer> hosts) {
        owned();
        if(cells.isEmpty())throw new IllegalArgumentException("An empty line");
        long first=topology.nextIdentity();var edits=new ArrayList<WorldTopologyLedger.Edit>();var placed=new ArrayList<WorldTopologyLedger.Registration>();
        for(int i=0;i<cells.size();i++) {
            var cell=cells.get(i);
            var device=new PhysicalFluidTopology.Device(first+i,cell.position(),TopologyCompiler.Kind.PIPE,cell.facing(),new PipeResistance.Geometry(1,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),
                    new FlowControl.Passive(),List.of(),true,PhysicalFluidTopology.Cutoff.NONE,false,cell.joints(),cell.slots(),cell.points());
            var record=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(catalog),0);placed.add(record);edits.add(new WorldTopologyLedger.Edit(first+i,record));
        }
        for(var host:new TreeMap<>(hosts).entrySet()) {
            var old=registrations().get(host.getKey());
            if(old==null||old.device().kind()!=TopologyCompiler.Kind.PIPE)throw new IllegalStateException("A laid line's host pipe is gone");
            edits.add(new WorldTopologyLedger.Edit(host.getKey(),new WorldTopologyLedger.Registration(old.device().withJoints(host.getValue()),old.spec(),Math.addExact(old.revision(),1))));
        }
        submit(edits,Math.addExact(first,cells.size()));return placed;
    }
    /** A rack lane registered again from its rack's copy (a chunk load after the D4 reset): its joints, width, face points and bore. */
    public WorldTopologyLedger.Registration placeLane(PhysicalFluidTopology.Position position,PhysicalFluidTopology.Direction facing,int joints,int slots,long points,double diameter) {
        owned();long id=topology.nextIdentity();
        var device=new PhysicalFluidTopology.Device(id,position,TopologyCompiler.Kind.PIPE,facing,new PipeResistance.Geometry(1,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),
                new FlowControl.Passive(),List.of(),true,PhysicalFluidTopology.Cutoff.NONE,false,joints,slots,points);
        var record=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(catalog),0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /** A complete multi-cell mover uses the same controls and geometry as its one-block counterpart. */
    public WorldTopologyLedger.Registration placeMover(PhysicalFluidTopology.Position suction,TopologyCompiler.Kind kind,List<PhysicalFluidTopology.Port> ports) {
        if(kind!=TopologyCompiler.Kind.PUMP&&kind!=TopologyCompiler.Kind.COMPRESSOR||ports.isEmpty())throw new IllegalArgumentException("Expected a multi-cell mover");
        return place(suction,kind,PhysicalFluidTopology.Direction.NORTH,ports);
    }
    private WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,List<PhysicalFluidTopology.Port> ports) {
        return place(position,kind,facing,ports,PhysicalFluidTopology.Cutoff.NONE);
    }
    private WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,List<PhysicalFluidTopology.Port> ports,PhysicalFluidTopology.Cutoff cutoff) {
        return place(position,kind,facing,ports,cutoff,false,false,TopologyCompiler.Kind.PIPE,PhysicalFluidTopology.ALL_FACES);
    }
    private WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,List<PhysicalFluidTopology.Port> ports,PhysicalFluidTopology.Cutoff cutoff,boolean pressureValve,boolean filter,TopologyCompiler.Kind inlineMover,int joints) {
        owned();
        if(pressureValve&&kind!=TopologyCompiler.Kind.PIPE)throw new IllegalArgumentException("Only a pipe hosts a pressure-control accessory");
        long id=topology.nextIdentity();FlowControl control=switch(kind){case PUMP,COMPRESSOR->PhysicalFluidTopology.defaultMover(kind);case VALVE->FlowControl.ControlValve.unconfigured();default->new FlowControl.Passive();};
        if(inlineMover!=TopologyCompiler.Kind.PIPE){if(kind!=TopologyCompiler.Kind.PIPE||pressureValve||filter)throw new IllegalArgumentException("Conflicting inline mover");control=PhysicalFluidTopology.defaultMover(inlineMover);}
        if(pressureValve)control=FlowControl.ControlValve.unconfigured();
        double diameter=kind==TopologyCompiler.Kind.PIPE?placedPipeDiameter(position):.05;
        var device=new PhysicalFluidTopology.Device(id,position,kind,facing,new PipeResistance.Geometry(1,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),control,ports,kind!=TopologyCompiler.Kind.GENERATOR,cutoff,filter,joints);
        var spec=kind==TopologyCompiler.Kind.GENERATOR?FluidDeviceSpec.water(catalog):FluidDeviceSpec.nitrogen(catalog);
        // A reservoir is placed empty (decision D54): of its settings only the capacity means anything.
        if(kind==TopologyCompiler.Kind.RESERVOIR)spec=new FluidDeviceSpec(options.volume(),298.15,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE,spec.composition());
        var record=new WorldTopologyLedger.Registration(device,spec,0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /** Inherit the largest mechanically connected adjacent pipe's latest bore, including a closed axial cutoff. */
    private double placedPipeDiameter(PhysicalFluidTopology.Position position) {
        double largest=0;
        for(var direction:PhysicalFluidTopology.Direction.values()) {
            var neighbour=registry.at(position.offset(direction)).orElse(null);
            if(neighbour!=null&&neighbour.device().kind()==TopologyCompiler.Kind.PIPE&&neighbour.device().mechanicallyConnects(PhysicalFluidTopology.opposite(direction)))
                largest=Math.max(largest,neighbour.device().geometry().diameter());
        }
        return largest>0?largest:.05;
    }
    /**
     * Registers a vessel that spans several cells (a drum, batch 2026-10-15-custom-equipment-rendering): its node at
     * {@code node} (its lowest inside cell), its ports, and its design volume and level height. It is placed empty, as a
     * one-block tank is (decision D54).
     */
    public WorldTopologyLedger.Registration placeVessel(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,double volume,double levelHeight) {
        return placeVessel(node,ports,volume,levelHeight,false);
    }
    /** {@link #placeVessel} open to the atmosphere when {@code open}: a floating-roof tank (batch 2026-10-21-floating-roof-tanks). */
    public WorldTopologyLedger.Registration placeVessel(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,double volume,double levelHeight,boolean open) {
        return placeVessel(node,ports,volume,levelHeight,open,com.wormzjl.icheme.science.fluid.network.VesselGeometry.prism(volume,levelHeight));
    }
    public WorldTopologyLedger.Registration placeVessel(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,double volume,double levelHeight,boolean open,com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry) {
        owned();
        long id=topology.nextIdentity();
        var device=new PhysicalFluidTopology.Device(id,node,TopologyCompiler.Kind.RESERVOIR,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),ports);
        var spec=new FluidDeviceSpec(volume,298.15,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition(),SlurryFeed.NONE,levelHeight,open,geometry);
        var record=new WorldTopologyLedger.Registration(device,spec,0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /** Queues a configuration change as a ledger event and returns it, or null when nothing would change. */
    public WorldTopologyLedger.Event edit(long id,long expectedRevision,PhysicalFluidTopology.Device device,FluidDeviceSpec spec) {
        owned();var old=registrations().get(id);if(old==null||old.revision()!=expectedRevision||device.id()!=id)throw new IllegalStateException("Stale fluid controls");
        if(old.device().equals(device)&&old.spec().equals(spec))return null;
        return submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,Math.addExact(expectedRevision,1)))),topology.nextIdentity());
    }
    /** The most pipes one line resize changes; a longer line is refused whole. */
    public static final int LINE_LIMIT=4096;
    /** Resizes the pipe line through pipe {@code id} as one event: see {@link PhysicalRegistry#resizeLine}. */
    public int resizeLine(long id,double diameter,java.util.function.ToDoubleFunction<PhysicalFluidTopology.Device> largest){owned();return registry.resizeLine(id,diameter,LINE_LIMIT,largest);}
    /** Installs against the projected revision, retaining pending diameter/configuration edits. */
    public boolean installPipeCutoff(long id,PhysicalFluidTopology.Direction facing) {
        owned();return registry.installPipeCutoff(id,facing);
    }
    public boolean installPipeValve(long id,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff kind) {
        owned();return registry.installPipeValve(id,facing,kind);
    }
    public boolean installPipeMover(long id,PhysicalFluidTopology.Direction facing,TopologyCompiler.Kind kind){owned();return registry.installPipeMover(id,facing,kind);}
    public boolean installPipeFilter(long id,PhysicalFluidTopology.Direction facing){owned();return registry.installPipeFilter(id,facing);}
    public boolean installPipePressureValve(long id,PhysicalFluidTopology.Direction facing) {
        owned();return registry.installPipePressureValve(id,facing);
    }
    /** Rapid clicks toggle the latest queued value, never the older drawn lever. */
    public void togglePipeCutoff(long id) {
        owned();registry.togglePipeCutoff(id);
    }
    /** Reading committed static configuration never advances or materialises process state. */
    public Optional<PhysicalFluidTopology.Device> committedDevice(long id){owned();var record=topology.active().get(id);return record==null?Optional.empty():Optional.of(record.device());}
    public void remove(long id){owned();instruments.recoverHost(id);var old=registrations().get(id);if(old!=null)submit(List.of(new WorldTopologyLedger.Edit(id,null)),topology.nextIdentity(),old.device().filter()?new WorldTopologyLedger.Recovery(id,null):null);}
    /**
     * Queues a filter's solid recovery as a ledger event and returns it, as an edit is queued: nothing is applied or
     * delivered here. The engine applies it with the next batch of events, takes the cake the island holds then and
     * delivers the item; an empty filter or a full inventory at that point refuses it in the menu's next reply.
     */
    public WorldTopologyLedger.Event recoverFilter(long id,long expectedRevision,net.minecraft.server.level.ServerPlayer player){
        owned();var old=registrations().get(id);if(old==null||old.revision()!=expectedRevision||!old.device().filter())throw new IllegalStateException("Stale filter controls");
        if(player.getInventory().getFreeSlot()<0)throw new IllegalStateException("Inventory full; filter unchanged");
        return submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(old.device(),old.spec(),Math.addExact(old.revision(),1)))),topology.nextIdentity(),new WorldTopologyLedger.Recovery(id,player.getUUID()));
    }
    private WorldTopologyLedger.Event submit(List<WorldTopologyLedger.Edit> edits,long nextId){return submit(edits,nextId,null);}
    private WorldTopologyLedger.Event submit(List<WorldTopologyLedger.Edit> edits,long nextId,WorldTopologyLedger.Recovery recovery) {
        if(closed)throw new IllegalStateException("Fluid world is closed");
        var changes=LISTENERS.isEmpty()?List.<DeviceChange>of():changes(edits);
        var event=registry.submit(edits,nextId,recovery);data.setDirty();
        if(registry.unapplied()>=APPLY_BATCH)applyPending();
        for(var listener:LISTENERS)listener.edited(server,changes);
        return event;
    }
    /** Each edit's device before and after it, at each position it stands at; a moved device stands at two. */
    private List<DeviceChange> changes(List<WorldTopologyLedger.Edit> edits) {
        var out=new ArrayList<DeviceChange>();
        for(var edit:edits) {
            var old=registry.registrations().get(edit.id());var before=old==null?null:old.device();var after=edit.replacement()==null?null:edit.replacement().device();
            if(before!=null&&after!=null&&!before.position().equals(after.position())){out.add(new DeviceChange(before.position(),before,null));out.add(new DeviceChange(after.position(),null,after));}
            else if(before!=null||after!=null)out.add(new DeviceChange(before!=null?before.position():after.position(),before,after));
        }
        return out;
    }
    /** Applies the events queued since the last application before a read that must see them. */
    private void flush(){if(!closed&&registry.unapplied()>0)applyPending();}
    /**
     * The per-tick hook does only what is due. The epoch increment advances every island clock at once; the
     * coordinator's tick resets the dispatch budget and pops due deadlines (island slices and retries, round
     * timeouts, the allocator shrink, recovery retries) and pumps only if that would act;
     * queued topology events are tried while any exist - everything queued since the last tick applies here as
     * batches, each compiling only the components it touches - and an island an event created or released is
     * offered to the pump in the same tick. No island or registry is visited otherwise. Last, the
     * presentation bucket of this tick is flushed if one is due (an O(1) check otherwise), after the tick's
     * events, so its replies and views include them.
     */
    private void tick() {
        owned();if(closed)return;refreshProperties();topology.tick();
        runtime.tick();
        if(topology.hasPendingEvents()){applyPending();runtime.coordinator().pumpIfUseful();}
        instruments.tick(topology.onlineTick());
        presentation.tick(topology.onlineTick());
        com.wormzjl.icheme.network.ColumnV3Network.presentationTick(server, topology.onlineTick());
        com.wormzjl.icheme.network.AssemblyNetwork.presentationTick(server, topology.onlineTick());
        data.setDirty();
    }
    /** Attempted when a recovery is queued and on its retry deadline, never polled; delivery stays exactly-once. */
    private void deliverRecoveries(){
        if(!topology.hasRecoveries())return;
        int delivered=0;boolean capped=false;for(var entry:topology.recoveries().entrySet()){
            if(delivered++>=64){capped=true;break;}var pending=entry.getValue();var stack=com.wormzjl.icheme.world.item.RecoveredSolidsItem.create(entry.getKey(),pending);boolean accepted=false;
            if(pending.player()!=null){var player=server.getPlayerList().getPlayer(pending.player());if(player!=null){for(int slot=0;slot<player.getInventory().getContainerSize();slot++)if(com.wormzjl.icheme.world.item.RecoveredSolidsItem.carriesTransfer(player.getInventory().getItem(slot),entry.getKey())){accepted=true;break;}if(!accepted&&player.getInventory().getFreeSlot()>=0)accepted=player.getInventory().add(stack);}}
            else {var position=pending.position();var level=server.getLevel(dimension(position.dimension()));var pos=new net.minecraft.core.BlockPos(position.x(),position.y(),position.z());
                if(level!=null&&level.hasChunkAt(pos))accepted=level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,stack));}
            if(accepted){topology.deliveredRecovery(entry.getKey());data.setDirty();}
        }
        // An offline player, a full inventory or an unloaded chunk leaves the record queued for a bounded retry;
        // more than one batch continues on the next tick.
        if(topology.hasRecoveries())scheduleRecoveryRetry(capped?1:RECOVERY_RETRY_TICKS);
    }
    private void scheduleRecoveryRetry(int ticks) {
        var coordinator=runtime.coordinator();coordinator.schedule(IslandScheduler.Kind.RECOVERY_RETRY,Math.addExact(coordinator.now(),ticks),()->{if(!closed)deliverRecoveries();});
    }
    private void applyPending() {
        if(propertyHold!=null)return;
        // A newly queued recovery is attempted at once, as the old per-tick attempt after applying events did.
        if(registry.applyPending())deliverRecoveries();
        data.setDirty();
    }
    /**
     * Rebind placed devices, queue missing registrations, then mark their presentation buckets. This also rebuilds an
     * empty authority after a format reset, including equipment spanning chunks. Never loads a chunk or runs a solve.
     */
    public void loadedChunk(ResourceKey<Level> dimension,long chunk) {
        owned();if(closed)return;
        var level=server.getLevel(dimension);if(level==null)return;
        var at=new net.minecraft.world.level.ChunkPos(chunk);
        var loaded=level.getChunkSource().getChunkNow(at.x,at.z);if(loaded==null)return;
        String name=dimension.location().toString();
        for(var cell:List.copyOf(loaded.getBlockEntitiesPos())) {
            var block=loaded.getBlockState(cell).getBlock();
            if(!(block instanceof FluidDeviceBlock)&&!(block instanceof EquipmentShellBlock)&&!(block instanceof com.wormzjl.icheme.world.level.block.PipeRackBlock))continue;
            var entity=loaded.getBlockEntity(cell); // Includes saved cells not yet promoted from pending NBT.
            if(entity instanceof com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity rack)rack.bind(this);
            else if(entity instanceof FluidDeviceBlockEntity pipe) {
                var position=new PhysicalFluidTopology.Position(name,cell.getX(),cell.getY(),cell.getZ());
                var record=registry.at(position).orElseGet(()->place(position,pipe.kind(),PhysicalFluidTopology.Direction.valueOf(pipe.getBlockState().getValue(FluidDeviceBlock.FACING).name()),FluidDeviceBlock.cutoff(pipe.getBlockState()),FluidDeviceBlock.hasPressureValve(pipe.getBlockState()),FluidDeviceBlock.hasFilter(pipe.getBlockState()),FluidDeviceBlock.hasMover(pipe.getBlockState())?pipe.getBlockState().getValue(FluidDeviceBlock.INLINE_MOVER).kind():TopologyCompiler.Kind.PIPE,pipe.kind()==TopologyCompiler.Kind.PIPE?pipe.joints():PhysicalFluidTopology.ALL_FACES));
                if(pipe.fluidIdentity()!=record.device().id())pipe.bindIdentity(record.device().id());else deviceLoaded(record.device().id());
            } else if(entity instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null&&shell.shape().fluidDevice()) {
                var origin=shell.origin();
                unboundStructures.put(new PhysicalFluidTopology.Position(name,origin.getX(),origin.getY(),origin.getZ()),new EquipmentStructure.Vessel(origin,shell.shape()));
            }
        }
        for(var it=unboundStructures.entrySet().iterator();it.hasNext();) {
            var entry=it.next();if(!entry.getKey().dimension().equals(name))continue;
            var vessel=entry.getValue();var origin=vessel.origin();
            if(at.x<(origin.getX()>>4)||at.x>((origin.getX()+vessel.shape().sizeX()-1)>>4)
                    ||at.z<(origin.getZ()>>4)||at.z>((origin.getZ()+vessel.shape().sizeZ()-1)>>4))continue;
            if(EquipmentStructure.restoreFluidBinding(level,vessel))it.remove();
        }
        for(long id:registry.chunk(name,chunk))presentation.markDirty(id);
    }
    /** A device's block entity loaded or was bound: marked for its bucket, never presented at once. */
    public void deviceLoaded(long id){owned();if(!closed){presentation.deviceLoaded(id);if(instruments!=null)instruments.bind(instrumentHost(id));}}
    /** A device's block entity unloaded or was removed: publications stop marking it, and its watches end (a client asks again when it loads the device). */
    public void deviceUnloaded(long id){owned();instruments.forget(id);forgetSightGlass(id);forgetMover(id);tankWatchers.remove(id);presentation.deviceUnloaded(id);publishedMenuData.remove(id);publishedGlides.remove(id);}
    /**
     * A client's watch request for open tanks by their node cells (owner decision FR4, R-B): each new watch of a loaded
     * open tank within {@link com.wormzjl.icheme.network.FluidNetwork#WATCH_RANGE} is answered with the tank's last published display, if it has one (a
     * read of cached data: nothing solves, nothing is marked), and the tank's next buckets send it again. Cells that are
     * no open tank, out of range or over the player's maximum are ignored.
     */
    public void watchTanks(net.minecraft.server.level.ServerPlayer player,List<BlockPos> watch,List<BlockPos> unwatch) {
        owned();if(closed)return;var uuid=player.getUUID();
        if(!unwatch.isEmpty())for(long device:tankWatchers.devices(uuid)){var record=registrations().get(device);if(record==null||unwatch.contains(cell(record)))tankWatchers.unwatch(uuid,device);}
        var level=player.serverLevel();
        for(var pos:watch) {
            if(!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver))continue;
            var record=registrations().get(receiver.fluidIdentity());
            if(record==null||!record.spec().open()||!cell(record).equals(pos)||!record.device().position().dimension().equals(level.dimension().location().toString())||!inRange(player,pos))continue;
            long device=receiver.fluidIdentity();var glide=publishedGlides.get(device);
            if(tankWatchers.watch(uuid,device)&&glide!=null)com.wormzjl.icheme.network.FluidNetwork.sendTank(player,pos,glide);
        }
    }
    /** Ends every tank watch of a player who left. */
    public void forgetWatcher(UUID player){owned();tankWatchers.forget(player);for(long id:sightGlassWatchers.devices(player))unwatchSightGlass(player,id);for(long id:moverWatchers.devices(player))unwatchMover(player,id);}

    /** A sound/animation subscription replays published operating state only; it never reads or advances a process. */
    public void watchMovers(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        owned();if(closed)return;var uuid=player.getUUID();var level=player.serverLevel();
        for(var target:unwatch)unwatchMover(uuid,target.identity());
        for(var target:watch) {
            var pos=target.position();
            if(!level.hasChunkAt(pos)||!moverRange(player,pos)||!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()!=target.identity())continue;
            var record=registrations().get(target.identity());
            if(record==null||record.device().ports().isEmpty()||!(record.device().control() instanceof FlowControl.Mover)||!cell(record).equals(pos)||!record.device().position().dimension().equals(level.dimension().location().toString()))continue;
            if(moverWatchers.watch(uuid,target.identity())) {
                refreshObserved(target.identity());var state=publishedMovers.get(target.identity());
                if(state!=null)com.wormzjl.icheme.network.FluidNetwork.sendMover(player,state);
            }
        }
    }
    private static boolean moverRange(net.minecraft.server.level.ServerPlayer player,BlockPos pos){return pos.distToCenterSqr(player.position())<=com.wormzjl.icheme.network.FluidNetwork.MOVER_RANGE*com.wormzjl.icheme.network.FluidNetwork.MOVER_RANGE;}
    private void refreshObserved(long id){if(moverWatchers.watchers(id).isEmpty()&&sightGlassWatchers.watchers(id).isEmpty()&&(instruments==null||!instruments.observes(id)))presentation.unobserve(id);else presentation.observe(id);}
    private void unwatchMover(UUID player,long id){moverWatchers.unwatch(player,id);refreshObserved(id);}
    private void forgetMover(long id){moverWatchers.remove(id);publishedMovers.remove(id);refreshObserved(id);}
    private void publishMover(WorldTopologyLedger.Registration record,net.minecraft.server.level.ServerLevel level,BlockPos pos) {
        if(record.device().ports().isEmpty()||!(record.device().control() instanceof FlowControl.Mover mover))return;
        long id=record.device().id();var target=new com.wormzjl.icheme.network.FluidNetwork.Target(pos,id);
        var state=new com.wormzjl.icheme.network.FluidNetwork.MoverState(target,mover instanceof FlowControl.Compressor,mover.enabled()&&mover.requestedVolumeFlow()>0);
        publishedMovers.put(id,state);
        for(var uuid:moverWatchers.watchers(id)) {
            var player=server.getPlayerList().getPlayer(uuid);
            if(player==null||player.hasDisconnected()||player.serverLevel()!=level||!moverRange(player,pos)){unwatchMover(uuid,id);continue;}
            com.wormzjl.icheme.network.FluidNetwork.sendMover(player,state);
        }
    }

    /** Explicit visual requests replay cached appearance only, then keep a future engine bucket scheduled. */
    public void watchSightGlasses(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        owned();if(closed)return;var uuid=player.getUUID();var level=player.serverLevel();
        for(var target:unwatch)unwatchSightGlass(uuid,target.identity());
        for(var target:watch) {
            var pos=target.position();
            if(!level.hasChunkAt(pos)||!inRange(player,pos)||!com.wormzjl.icheme.world.level.block.FluidDeviceBlock.hasSightGlass(level.getBlockState(pos)))continue;
            if(!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()!=target.identity())continue;
            var record=registrations().get(target.identity());
            if(record==null||record.device().kind()!=TopologyCompiler.Kind.PIPE||!cell(record).equals(pos)||!record.device().position().dimension().equals(level.dimension().location().toString()))continue;
            if(sightGlassWatchers.watch(uuid,target.identity())) {
                presentation.observe(target.identity());
                var sample=publishedSightGlasses.get(target.identity());
                if(sample!=null)com.wormzjl.icheme.network.FluidNetwork.sendSightGlass(player,target,sample);
            }
        }
    }
    private void unwatchSightGlass(UUID player,long id) {
        sightGlassWatchers.unwatch(player,id);
        refreshObserved(id);
    }
    private void forgetSightGlass(long id){sightGlassWatchers.remove(id);refreshObserved(id);publishedSightGlasses.remove(id);}
    private void publishSightGlass(WorldTopologyLedger.Registration record,net.minecraft.server.level.ServerLevel level,BlockPos pos,FluidView view) {
        long id=record.device().id();
        var sample=PipePresentation.sightGlass(view,componentNames,model.molecularWeights(),catalog,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),registry.pipeViews(id));
        publishedSightGlasses.put(id,sample);
        var target=new com.wormzjl.icheme.network.FluidNetwork.Target(pos,id);
        for(var uuid:sightGlassWatchers.watchers(id)) {
            var player=server.getPlayerList().getPlayer(uuid);
            if(player==null||player.hasDisconnected()||player.serverLevel()!=level||!inRange(player,pos)){unwatchSightGlass(uuid,id);continue;}
            com.wormzjl.icheme.network.FluidNetwork.sendSightGlass(player,target,sample);
        }
    }
    private static BlockPos cell(WorldTopologyLedger.Registration record){var p=record.device().position();return new BlockPos(p.x(),p.y(),p.z());}
    private static boolean inRange(net.minecraft.server.level.ServerPlayer player,BlockPos pos){return player.position().distanceToSqr(pos.getCenter())<=com.wormzjl.icheme.network.FluidNetwork.WATCH_RANGE*com.wormzjl.icheme.network.FluidNetwork.WATCH_RANGE;}
    /**
     * An open tank's bucket: its display is read off the view just presented, its roof glides to it from where it stands
     * ({@link TankDisplay.Glide}, which the level's roofs collide by) and the glide is sent to each watcher still online,
     * in its dimension and within range; the others are dropped. Nothing is sent when nobody watches. A tank with no glide
     * since it loaded glides from the level its node cell saved, so this runs before the cell takes the new view.
     */
    private void publishTank(WorldTopologyLedger.Registration record,net.minecraft.server.level.ServerLevel level,BlockPos pos,FluidView view) {
        long id=record.device().id();var display=TankDisplay.of(view,componentNames,model.molecularWeights(),catalog,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog));
        var roofs=com.wormzjl.icheme.world.level.block.FloatingRoofs.of(level);
        if(display==null){publishedGlides.remove(id);roofs.glide(pos,null);return;}
        long now=level.getGameTime();var glide=TankDisplay.Glide.next(publishedGlides.get(id),roofs.standing(pos,now),display,now);
        publishedGlides.put(id,glide);roofs.glide(pos,glide);
        for(var uuid:tankWatchers.watchers(id)) {
            var player=server.getPlayerList().getPlayer(uuid);
            if(player==null||player.hasDisconnected()||player.serverLevel()!=level||!inRange(player,pos)){tankWatchers.unwatch(uuid,id);continue;}
            com.wormzjl.icheme.network.FluidNetwork.sendTank(player,pos,glide);
        }
    }
    /** Marks a device for its next bucket (a test or tool asking for a refresh gets it on the engine's schedule). */
    public com.wormzjl.icheme.runtime.instrument.InstrumentHosts.Host instrumentHost(long id){owned();var r=registrations().get(id);if(r==null)return null;var p=r.device().position();var level=server.getLevel(dimension(p.dimension()));if(level==null)return null;var h=com.wormzjl.icheme.runtime.instrument.InstrumentHosts.at(level,new BlockPos(p.x(),p.y(),p.z()));return h!=null&&h.identity()==id?h:null;}
    public void instrumentObservationChanged(long id){owned();refreshObserved(id);}
    public void forgetFlowmeter(long id){owned();registry.forgetFlowmeter(id);}
    public void flowmeter(long id,boolean installed){owned();registry.flowmeter(id,installed);}
    public boolean removePipeAccessory(long id){owned();return registry.removePipeAccessory(id);}
    public record InstrumentReading(double pressure,double temperature,boolean flowing,double massFlow,Double volumeFlow){public InstrumentReading(double pressure,double temperature,boolean flowing){this(pressure,temperature,flowing,0,0.0);}}
    public InstrumentReading instrumentPipeReading(long id){owned();Long owner=registry.owner(id);if(owner==null)return null;var r=registrations().get(id);if(r==null)return null;var snapshot=runtime.coordinator().presentation(owner);if(snapshot.lastResult().isEmpty())return null;
        for(var observed:snapshot.lastResult().get().observations())for(var sample:observed.flowSamples())if(sample.host()==id)
            return new InstrumentReading(sample.pressure(),observed.temperature(),observed.flowing(),sample.massFlow(),sample.volumeFlow());
        for(var mapping:registry.pipeViews(id)){if(!Double.isFinite(mapping.tap()))continue;for(var observed:snapshot.lastResult().get().observations())if(observed.pipe()==mapping.pipeId())return new InstrumentReading(observed.pressure(mapping.tap(),r.device().position().y(),!mapping.forward()),observed.temperature(),observed.flowing(),observed.massFlow()*(mapping.forward()?1:-1),observed.density()>0?observed.massFlow()/observed.density()*(mapping.forward()?1:-1):0);}return null;}
    public void markViewDirty(long id){owned();presentation.markDirty(id);}
    /** An open menu becomes a consumer of its device's bucket. The first delivery comes with that bucket. */
    public void subscribe(com.wormzjl.icheme.world.inventory.FluidDeviceMenu menu){owned();if(!closed)presentation.subscribe(menu,menu.identity());}
    /** Called after openMenu has installed the requested menu and queued its opening packet. */
    public boolean replayOnOpen(com.wormzjl.icheme.world.inventory.FluidDeviceMenu menu){
        owned();return !closed&&presentation.replayOnOpen(menu);
    }
    public void unsubscribe(com.wormzjl.icheme.world.inventory.FluidDeviceMenu menu){owned();presentation.unsubscribe(menu);}
    /**
     * A player input from a menu, already validated and queued as a ledger event (or refused): recorded for the
     * reply its menu receives with its next bucket. Returns nothing to the handler, which sends nothing.
     */
    public void input(com.wormzjl.icheme.world.inventory.FluidDeviceMenu menu,WorldTopologyLedger.Event event,String refusal) {
        owned();long now=topology.onlineTick();
        presentation.input(menu,menu.identity(),refusal!=null?FluidPresentation.Input.refused(now,refusal):event==null?new FluidPresentation.Input(now,null,null,-1):FluidPresentation.Input.queued(now,event.id(),event.tick()));
    }
    /** Diagnostics: what one menu has been delivered, bucket by bucket. */
    public List<FluidPresentation.Delivery> deliveries(com.wormzjl.icheme.world.inventory.FluidDeviceMenu menu){owned();return presentation.deliveries(menu);}
    public FluidPresentation.Stats presentationStats(){owned();return presentation.stats();}
    /** Diagnostics: a device's bucket key (its island, or itself without one); its buckets fall at ticks congruent to it modulo 100. */
    public long presentationKey(long device){owned();flush();return bucketKey(device);}
    /** The bucket key as the presentation reads it, with no event applied: marking a device never forces a batch. */
    private long bucketKey(long device){Long owner=registry.owner(device);return owner!=null?owner:device;}
    public boolean viewDirty(long device){owned();return presentation.dirty(device);}
    public FluidSavedData.Capture capture() {
        owned();flush();var islands=new ArrayList<FluidCheckpointCodec.IslandEntry>();var world=topology.snapshot();
        for(var snapshot:runtime.coordinator().snapshots()) {
            if(registry.members(snapshot.id()).isEmpty())throw new IllegalStateException("Fluid island "+snapshot.id()+" holds no device; refusing to save it");
            String dimension=world.active().get(registry.members(snapshot.id()).getFirst()).device().position().dimension();
            islands.add(new FluidCheckpointCodec.IslandEntry(dimension,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),snapshot));
        }
        return new FluidSavedData.Capture(new FluidCheckpointCodec.Checkpoint(islands),world);
    }
    /** A device's view, as a presentation read: queued events are not applied (they apply on the engine's tick), and a
     * certified island is materialised to now but never woken by it. */
    public FluidView view(long id){owned();return view(id,new HashMap<>());}
    /** {@code islands} caches island reads, so one bucket reads each island once however many of its devices it presents. */
    private FluidView view(long id,Map<Long,IslandCoordinator.Snapshot> islands) {
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.viewBuilds);var registration=registrations().get(id);if(registration==null)return new FluidView(id,0,0,topology.onlineTick(),"REMOVED",null,0,List.of());
        Long owner=registry.owner(id);if(owner==null)return new FluidView(id,registration.revision(),0,topology.onlineTick(),!topology.active().containsKey(id)?"WAITING: topology event":Objects.requireNonNullElse(registry.diagnostic(id),"NO FLOW: no hydraulic boundary"),null,0,List.of(),null,0,List.of(),null);
        var snapshot=islands.computeIfAbsent(owner,runtime.coordinator()::presentation);FluidView.State state=null;int nodeIndex=-1;boolean empty=false;
        for(int i=0;i<snapshot.graph().reservoirs().size();i++)if(snapshot.graph().reservoirs().get(i).id()==id){nodeIndex=i;var node=snapshot.graph().reservoirs().get(i);empty=node.empty();if(!empty)state=FluidView.State.from(node.state(),node.kind()==PassiveNetwork.NodeKind.RESERVOIR?node.inventory().volume():node.state().volume());break;}
        InlineFilter filter=null;if(registration.device().filter()){state=null;for(var p:snapshot.graph().pipes())if(p.id()==PhysicalFluidTopology.filterIdentity(id))filter=p.filter();}
        var mappings=registration.device().actuator()?PipePresentation.actuatorMappings(registration.device(),snapshot.graph()):registry.pipeViews(id);
        var history=new ArrayList<PipeTransfer>();double flow=0;Double devicePressureChange=null;String status=snapshot.status();
        // Decision D54: a placed tank holds nothing until something fills it.
        if(empty)status="EMPTY: holds nothing / "+status;
        if(snapshot.lastResult().isPresent()) {
            var result=snapshot.lastResult().orElseThrow();var q=result.averageMassFlows();
            for(int i=0;i<snapshot.graph().pipes().size();i++){var pipe=snapshot.graph().pipes().get(i);if(pipe.first()==nodeIndex)flow+=q[i];if(pipe.second()==nodeIndex)flow-=q[i];}
            for(var mapping:mappings)for(var value:result.pipeTransfers())if(value.pipeId()==mapping.pipeId()) {
                history.add(value);
            }
            if(!history.isEmpty())flow=(history.getFirst().forward().massKg()-history.getFirst().reverse().massKg())/result.advancedSeconds();
            if(registration.device().actuator())for(int i=0;i<snapshot.graph().pipes().size();i++){var pipe=snapshot.graph().pipes().get(i);if(pipe.first()==nodeIndex&&!(pipe.control() instanceof FlowControl.Passive)){flow=q[i];devicePressureChange=result.endpointHeads()[i];var mode=result.endpointModes().get(i);
                if(pipe.control() instanceof FlowControl.Valve&&ControlConstraints.dependent(snapshot.graph()).get(i))status+=" / CONTROL_CONFLICT: dependent control constraints; holding last accepted opening";
                if(pipe.control() instanceof FlowControl.Mover mover) {
                    String inlet=mode==FlowControl.Mode.INLET_WRONG_PHASE?inletReason(snapshot.graph(),i,result.advancedSeconds()):"";
                    if(mover instanceof FlowControl.Pump pump)status=PipePresentation.pumpStatus(status,pump,mode,inlet);
                    else {status+=" / "+mode;if(!inlet.isEmpty())status+=" / "+inlet;}
                    // The limit on the suction stream the mover draws (its own junction, what arrives at its inlet; decision
                    // D4): a pump's setting is the rise for water and scales with the suction density, a compressor's is the
                    // ratio over the suction pressure.
                    if(!snapshot.graph().reservoirs().get(pipe.first()).empty()&&mode!=FlowControl.Mode.INLET_WRONG_PHASE&&mode!=FlowControl.Mode.PUMP_SUCTION_LIMIT) {var suction=snapshot.graph().reservoirs().get(pipe.first()).state();double limit=mover.riseLimit(suction.mass()/suction.volume(),suction.pressure(),model.pumpReferenceDensity());
                        status+=String.format(java.util.Locale.ROOT,mover instanceof FlowControl.Pump?" (limit %.0f Pa on this fluid)":" (limit %.0f Pa at this suction)",limit);}
                }else status+=" / "+mode;}}
        }
        // A generator or a void in several islands (owner decision D17) reads the status of its owner (the lowest island) and
        // the net flow of all of them.
        if(registration.device().fixed())for(long other:registry.islands(id))if(other!=owner) {
            var shared=islands.computeIfAbsent(other,runtime.coordinator()::presentation);if(shared.lastResult().isEmpty())continue;var q=shared.lastResult().orElseThrow().averageMassFlows();
            int index=-1;for(int i=0;i<shared.graph().reservoirs().size();i++)if(shared.graph().reservoirs().get(i).id()==id)index=i;
            for(int i=0;i<shared.graph().pipes().size();i++){var pipe=shared.graph().pipes().get(i);if(pipe.first()==index)flow+=q[i];if(pipe.second()==index)flow-=q[i];}
        }
        if(filter!=null)for(var pipe:snapshot.graph().pipes())if(pipe.id()==PhysicalFluidTopology.filterIdentity(id))devicePressureChange=snapshot.graph().reservoirs().get(pipe.first()).state().pressure()-snapshot.graph().reservoirs().get(pipe.second()).state().pressure();
        if(filter!=null&&filter.clogged())status="filter clogged / "+status;
        var closures=snapshot.lastResult().map(PassiveIntervalSolver.Result::rejectionReasons).orElse(Map.of());
        for(var mapping:mappings)for(var pipe:snapshot.graph().pipes())if(pipe.id()==mapping.pipeId()&&pipe.blockedDirections()!=0&&pipe.filter()==null)status=solidClosure(mapping.pipeId(),closures)+" / "+status;
        if(registry.diagnostic(id)!=null)status=registry.diagnostic(id)+" / "+status;
        var active=topology.active().get(id);if(active==null||active.revision()!=registration.revision())status="WAITING: configuration event / "+status;
        if(unboundBindings.contains(id))status="UNBOUND: saved inventory retained / "+status;
        var routes=new ArrayList<FluidView.PipeRoute>();
        for(var historyEntry:history)for(var pipe:snapshot.graph().pipes())if(pipe.id()==historyEntry.pipeId()) {
            routes.add(new FluidView.PipeRoute(pipe.id(),nodeLabel(snapshot.graph().reservoirs().get(pipe.first()).id()),nodeLabel(snapshot.graph().reservoirs().get(pipe.second()).id())));
        }
        double seconds=snapshot.lastResult().map(PassiveIntervalSolver.Result::advancedSeconds).orElse(0.0);
        var view=new FluidView(id,registration.revision(),snapshot.clock().committedTick(),topology.onlineTick(),status,state,flow,history,devicePressureChange,
                seconds,routes,filter);
        if(registration.device().kind()==TopologyCompiler.Kind.RESERVOIR&&nodeIndex>=0)view=view.withOutlets(TankOutlets.of(model,snapshot.graph(),nodeIndex,snapshot.lastResult().orElse(null),registry.tankFaces(id)))
                .withTank(TankOutlets.tank(model,snapshot.graph().reservoirs().get(nodeIndex),snapshot.lastResult().orElse(null)));
        if(registration.device().kind()==TopologyCompiler.Kind.PIPE||registration.device().filter()||registration.device().actuator())
            view=view.withPipeInfo(PipePresentation.withBulkSpeed(
                PipePresentation.inspect(registration.device(),snapshot.graph(),mappings,history,seconds,componentNames.size()),
                runtime.coordinator().bulkVolumeRates(owner),registration.device().geometry().area()));
        if(registration.device().kind()==TopologyCompiler.Kind.VOID) {
            var sources=new ArrayList<IslandCoordinator.Snapshot>();
            for(long island:registry.islands(id))sources.add(islands.computeIfAbsent(island,runtime.coordinator()::presentation));
            // Independent boundary islands can have different intervals: combine rates, expressed as one second of transport.
            view=view.withPipeInfo(PipePresentation.boundaryInflow(id,sources,componentNames.size()),1);
        }
        return view;
    }
    /**
     * Why this connection carries nothing, in the words the solver used, for the device status a
     * player reads. The event integrator names the connection and the two velocities in the reason
     * it records against every closure, and a closure the interval started with is recorded again
     * each interval, so the account stays true for as long as the connection is shut. Nothing else
     * carries it: the blocked mask on the pipe says only that something closed it.
     *
     * <p>The status line is bounded and wraps to three lines on the screen, so this stays to one
     * short clause. A reason with no velocity of its own - a population limit, a stopped filter -
     * is named without one rather than with two zeroes, and an unrecognised or missing record
     * falls back to the bare text the mask alone justifies.
     */
    static String solidClosure(long pipeId,Map<String,Integer> reasons) {
        for(String key:reasons.keySet()) {
            if(!key.startsWith(SOLID_CLOSURE)||!key.contains("; pipe="+pipeId+";"))continue;
            int end=key.indexOf(';',SOLID_CLOSURE.length());if(end<0)break;
            double velocity=closureField(key,"; velocity="),minimum=closureField(key,"; deposition=");
            return SOLID_CLOSURE+key.substring(SOLID_CLOSURE.length(),end)
                    +(minimum>0?" ("+speed(velocity)+" m/s, needs "+speed(minimum)+" m/s)":"");
        }
        return "blocked with solid";
    }
    private static final String SOLID_CLOSURE="blocked with solid: ";
    private static double closureField(String key,String token) {
        int at=key.indexOf(token);if(at<0)return 0;
        int end=key.indexOf(';',at+token.length());
        try{return Double.parseDouble(key.substring(at+token.length(),end<0?key.length():end));}catch(RuntimeException unreadable){return 0;}
    }
    private static String speed(double value) {
        return String.format(java.util.Locale.ROOT,Math.abs(value)>=.01?"%.2f":"%.2e",value);
    }
    /** A node as a status line names it: a device by kind and position, anything else as the pipe junction it is. */
    private String deviceLabel(long id) {
        var registration=topology.active().get(id);if(registration==null)return "pipe junction "+id;
        var p=registration.device().position();return registration.device().kind().name().toLowerCase(java.util.Locale.ROOT)+" at "+p.x()+", "+p.y()+", "+p.z();
    }
    /**
     * One WARN per island per phase hold, a phase set the network cannot carry (D21 option C; the coordinator
     * rate-limits repeats of the same island and hold): where, which package, its reason key and state, and what to do
     * about it. Leaving a data range is no hold (owner rule of 2026-10-16).
     */
    private void phaseHeld(long island,com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold,String where,boolean parked) {
        String dimension="unknown";
        var members=registry.members(island);if(!members.isEmpty()){var record=topology.active().get(members.getFirst());if(record!=null)dimension=record.device().position().dimension();}
        Object node=hold.node()==com.wormzjl.icheme.science.fluid.thermo.ThermoHold.NO_NODE?"unknown":hold.node();String device=where.isEmpty()?"unknown":where;
        IChemE.LOGGER.warn("fluid_island={} dimension={} node={} device={} status=PHASE_HOLD code={} package={} temperature={} pressure={} waits_for_inputs={} detail={} action=Change what the island mixes or the conditions it holds: the network carries one hydrocarbon liquid, one vapour, water and the package's crystals",
                island,dimension,node,device,hold.reasonKey().substring(com.wormzjl.icheme.science.fluid.thermo.ThermoHold.REASON_PREFIX.length()),hold.packageId(),hold.temperature(),hold.pressure(),parked,hold.getMessage());
    }
    /**
     * Why a mover is refused (decision D6), as the solver's inlet check states it on the committed state the next slice
     * decides on ({@link InletPhase#reason}), with the node the supply walk ended at named as a device. Pure arithmetic on
     * the committed state's stored phase amounts: no solve and no flash. Its transport closures read as open, as that
     * slice reads them (owner decision D33, {@link NetworkReduction#reopened}).
     */
    private String inletReason(PassiveNetwork graph,int edge,double seconds) {
        var mover=(FlowControl.Mover)graph.pipes().get(edge).control();
        var supply=InletPhase.supply(model,NetworkReduction.reopened(graph),edge,mover.targetVolumeFlow(),seconds);
        return InletPhase.reason(mover,supply).replace("from node "+supply.nodeId()+")","from "+deviceLabel(supply.nodeId())+")");
    }
    private String nodeLabel(long id) {
        var registration=topology.active().get(id);if(registration==null)return "Junction "+id;
        var p=registration.device().position();return p.x()+", "+p.y()+", "+p.z();
    }
    /** The world side of {@link FluidPresentation}: ownership, views, loaded block entities and menu delivery. */
    private final class PresentationHost implements FluidPresentation.Host<com.wormzjl.icheme.world.inventory.FluidDeviceMenu> {
        public long key(long device){return bucketKey(device);}
        public long revision(long device){var record=registrations().get(device);return record==null?-1:record.revision();}
        public boolean eventPending(UUID event){for(var queued:topology.events())if(queued.id().equals(event))return true;return false;}
        public String eventRefusal(UUID event){return refusedEvents.remove(event);}
        public boolean open(com.wormzjl.icheme.world.inventory.FluidDeviceMenu menu) {
            var player=menu.serverPlayer();return player!=null&&!player.hasDisconnected()&&player.containerMenu==menu&&menu.stillValid(player);
        }
        public FluidView view(long device,Map<Long,IslandCoordinator.Snapshot> islands){
            var view=FluidWorldAuthority.this.view(device,islands);
            var record=registrations().get(device);
            if(record!=null&&(record.device().kind()==TopologyCompiler.Kind.PIPE||record.device().filter()||record.device().kind()==TopologyCompiler.Kind.VOID)&&view.pipeInfo()==null)
                view=view.withPipeInfo(FluidView.PipeInfo.empty(componentNames.size()));
            if(record!=null)publishedMenuData.put(device,com.wormzjl.icheme.network.FluidNetwork.snapshot(FluidWorldAuthority.this,record,view));
            return view;
        }
        public long replay(com.wormzjl.icheme.world.inventory.FluidDeviceMenu menu,long device){
            var data=publishedMenuData.get(device);if(data==null)return -1;
            com.wormzjl.icheme.network.FluidNetwork.deliver(menu,true,data,"");
            return data.view().inputRevision();
        }
        /** Binds a loaded block entity that does not yet carry its identity, then hands it the view. Never loads a chunk. */
        public boolean present(long id,java.util.function.Supplier<FluidView> view) {
            var record=registrations().get(id);if(record==null)return false;var p=record.device().position();var level=server.getLevel(dimension(p.dimension()));if(level==null)return false;
            // A rack lane has no screen and no view receiver: its rack draws it from its own copy.
            if(p.lane()!=0)return false;
            var pos=new BlockPos(p.x(),p.y(),p.z());if(!level.hasChunkAt(pos))return false;
            // A drum's node cell carries the identity it was built with; it is never bound here.
            if(!record.device().ports().isEmpty()) {
                if(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver&&receiver.fluidIdentity()==id){unboundBindings.remove(id);var presented=view.get();
                    // The roof glides from the level the node cell saved: read before the cell takes this view (publishTank).
                    if(record.spec().open())publishTank(record,level,pos,presented);
                    receiver.acceptFluidView(presented);instruments.publish(id,presented);
                    publishMover(record,level,pos);
                    return true;}
                if(unboundBindings.add(id))IChemE.LOGGER.warn("fluid_binding={} status=UNBOUND detail=Loaded vessel cell mismatch; saved inventory and topology retained",id);
                return false;
            }
            if(!(level.getBlockState(pos).getBlock() instanceof com.wormzjl.icheme.world.level.block.FluidDeviceBlock block)||block.kind()!=record.device().kind()) {
                if(unboundBindings.add(id))IChemE.LOGGER.warn("fluid_binding={} status=UNBOUND detail=Loaded block mismatch; saved inventory and topology retained",id);
                return false;
            }
            unboundBindings.remove(id);
            if(level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity entity&&entity.fluidIdentity()!=id)entity.bindIdentity(id);
            if(!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()!=id)return false;
            var presented=view.get();receiver.acceptFluidView(presented);instruments.publish(id,presented);
            if(com.wormzjl.icheme.world.level.block.FluidDeviceBlock.hasSightGlass(level.getBlockState(pos)))publishSightGlass(record,level,pos,presented);
            else forgetSightGlass(id);
            return true;
        }
        public void failed(long device,RuntimeException failure){IChemE.LOGGER.error("fluid_presentation device={} status=FAILED detail=The view could not be presented at its bucket",device,failure);}
        public void deliver(com.wormzjl.icheme.world.inventory.FluidDeviceMenu menu,long device,boolean withStatic,FluidView view,String reply) {
            var data=publishedMenuData.get(device);
            if(data!=null)com.wormzjl.icheme.network.FluidNetwork.deliver(menu,withStatic,data,reply);
        }
    }
    /** The world side of {@link PhysicalRegistry}: the runtime's islands, players for recoveries, and what a commit changes here. */
    private final class RegistryHost implements PhysicalRegistry.Host {
        public IslandCoordinator coordinator(){return runtime.coordinator();}
        public void topology(String name,Set<UUID> events,Set<Long> affected,List<IslandCoordinator.Replacement> replacements,long committed,long online,
                             Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,InlineFilter> releasedFilters,Runnable commit) {
            runtime.topology(dimension(name),events,affected,replacements,model,committed,online,additions,removals,releasedFilters,commit);
        }
        public boolean acceptsRecovery(UUID player){var online=server.getPlayerList().getPlayer(player);return online!=null&&online.getInventory().getFreeSlot()>=0;}
        public void refused(UUID event,String reason){refuseEvent(event,reason);}
        public void committed(Set<Long> removed,Set<Long> recompiled) {
            unboundBindings.removeAll(removed);publishedMenuData.keySet().removeAll(removed);publishedGlides.keySet().removeAll(removed);for(long device:removed){tankWatchers.remove(device);forgetSightGlass(device);forgetMover(device);}
            // Every recompiled device's cached snapshot predates the change, and one left in no island (a pipe whose only
            // tank was broken) gets no publication that would mark it: each is marked for its next bucket, which presents
            // its new view (its replacement island, or "no hydraulic boundary"); an opening menu waits for that bucket.
            publishedMenuData.keySet().removeAll(recompiled);publishedSightGlasses.keySet().removeAll(recompiled);publishedMovers.keySet().removeAll(recompiled);for(long device:recompiled)presentation.markIfLoaded(device);
            // Open menus follow their devices to the replacement islands' buckets.
            presentation.rekey();
            // A committed edit is drawn now: a loaded pipe takes its registered bore, and its neighbours their faces.
            for(long device:recompiled)drawn(device);
        }
        private void drawn(long device) {
            var record=registrations().get(device);if(record==null)return;var p=record.device().position();var level=server.getLevel(dimension(p.dimension()));if(level==null)return;
            var pos=new BlockPos(p.x(),p.y(),p.z());
            if(!level.isLoaded(pos))return;
            if(level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity entity)entity.refreshGeometry(record.device().geometry().diameter(),false);
            else if(p.lane()!=0&&level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity rack)rack.refresh(true);
        }
    }
    /** A certified island's materialisation: only accounting (the diagnostic observer) and the save mark need it.
     * It queues no view refresh of its own. */
    private void replayed(List<IslandCoordinator.Snapshot> changed) {
        if(observer!=null){var timings=new HashMap<Long,IslandCoordinator.Metrics>();for(var island:changed)runtime.coordinator().metrics(island.id()).ifPresent(m->timings.put(island.id(),m));observer.accept(changed,Map.copyOf(timings));}
        data.setDirty();
    }
    private void published(List<IslandCoordinator.Snapshot> changed) {
        if(observer!=null){var timings=new HashMap<Long,IslandCoordinator.Metrics>();for(var island:changed)runtime.coordinator().metrics(island.id()).ifPresent(m->timings.put(island.id(),m));observer.accept(changed,Map.copyOf(timings));}
        // A phase hold has its own, rate-limited line with the full detail (phaseHeld); every other hold keeps this one.
        data.setDirty();var ids=new HashSet<Long>();for(var snapshot:changed){ids.add(snapshot.id());if(snapshot.status().startsWith("HELD")&&!snapshot.status().startsWith(com.wormzjl.icheme.runtime.ProcessSolveServices.PHASE_HOLD))IChemE.LOGGER.warn("fluid_island={} committed_tick={} status={}",snapshot.id(),snapshot.clock().committedTick(),snapshot.status());}
        // Only loaded devices are marked; their bucket presents them. A publication itself pushes nothing.
        for(long island:ids)for(long physical:registry.members(island))presentation.markIfLoaded(physical);
        if(options.debugChat()&&(lastDebugChat==Long.MIN_VALUE||topology.onlineTick()-lastDebugChat>=20)) {
            changed.stream().filter(s->s.status().startsWith("HELD")).findFirst().ifPresent(s->{
                lastDebugChat=topology.onlineTick();String message="Fluid network "+s.id()+": "+s.status();if(message.length()>180)message=message.substring(0,180)+" (see log)";
                var text=net.minecraft.network.chat.Component.literal(message);for(var player:server.getPlayerList().getPlayers())player.sendSystemMessage(text);
            });
        }
    }
    @Override public void close(){owned();if(closed)return;closed=true;unboundStructures.clear();publishedMenuData.clear();publishedSightGlasses.clear();publishedMovers.clear();runtime.close();data.setDirty();}

    /**
     * Which players watch which open tanks, sight glasses or movers (the request path R-B of the engine-owned presentation
     * rule, owner decision FR4): a client asks for each one it has loaded within range; the server replays its last
     * published display at once and sends it again at each of its island's presentation buckets until the client
     * unwatches it, leaves its range or disconnects. Nothing is sent to a player who did not ask. At most
     * {@link com.wormzjl.icheme.network.FluidNetwork#MAXIMUM_WATCHED} of a kind per player.
     */
    static final class Watchers {
        private final Map<Long,Set<UUID>> byDevice=new HashMap<>();
        private final Map<UUID,Set<Long>> byPlayer=new HashMap<>();

        /** Starts {@code player} watching {@code device}; false when it already does or watches its maximum. */
        public boolean watch(UUID player,long device) {
            var devices=byPlayer.computeIfAbsent(player,k->new HashSet<>());
            if(devices.contains(device)||devices.size()>=com.wormzjl.icheme.network.FluidNetwork.MAXIMUM_WATCHED)return false;
            devices.add(device);byDevice.computeIfAbsent(device,k->new HashSet<>()).add(player);
            return true;
        }
        public void unwatch(UUID player,long device) {
            var devices=byPlayer.get(player);if(devices!=null&&devices.remove(device)&&devices.isEmpty())byPlayer.remove(player);
            var players=byDevice.get(device);if(players!=null&&players.remove(player)&&players.isEmpty())byDevice.remove(device);
        }
        /** Ends every subscription of {@code player} (it disconnected). */
        public void forget(UUID player) {
            var devices=byPlayer.remove(player);if(devices==null)return;
            for(long device:devices){var players=byDevice.get(device);if(players!=null&&players.remove(player)&&players.isEmpty())byDevice.remove(device);}
        }
        /** Ends every subscription to {@code device} (it is gone). */
        public void remove(long device) {
            var players=byDevice.remove(device);if(players==null)return;
            for(var player:players){var devices=byPlayer.get(player);if(devices!=null&&devices.remove(device)&&devices.isEmpty())byPlayer.remove(player);}
        }
        /** The players watching {@code device}, a copy (empty for none). */
        public Set<UUID> watchers(long device){var players=byDevice.get(device);return players==null?Set.of():Set.copyOf(players);}
        /** The devices {@code player} watches, a copy (empty for none). */
        public Set<Long> devices(UUID player){var devices=byPlayer.get(player);return devices==null?Set.of():Set.copyOf(devices);}
        public int watched(UUID player){var devices=byPlayer.get(player);return devices==null?0:devices.size();}
    }
}
