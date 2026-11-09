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
    /** The running columns (batch 2026-10-28-column-operation): their ports' plans and bookings, and their cycles. */
    private final ColumnOperation columns;
    /** The shaft trains (batch 2026-11-03-rotating-machinery W3): their parts' queued events, speeds and the islands' slice inputs. */
    private final ShaftTrains shafts;
    /** The powered units' electric stores (W5). */
    private final ElectricStores electric;
    private final FiredHeaters burners;
    private final Flares flares;
    /** The batch stills (batch 2026-11-09-batch-distiller, W3 to W5). */
    private final Stills stills;
    /** The pumps' health (W10b). */
    private final PumpHealth health;
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
    /** Registrations whose chunk loaded, by dimension, each queued once until the re-alignment checks it (REGISTRY_FIRST_PLAN.md 4.2). */
    private final Map<String,ArrayDeque<Long>> realignment=new HashMap<>();
    private final Set<Long> realigning=new HashSet<>();
    /** The shaft parts whose chunk loaded, by dimension, each queued once until the re-alignment checks it (W5c C1). */
    private final Map<String,ArrayDeque<Long>> shaftRealignment=new HashMap<>();
    private final Set<Long> shaftRealigning=new HashSet<>();
    /** The origins of the structures the registry records, by dimension and by chunk of their box: a chunk load queues them for their re-alignment (plan 4.4). */
    private final Map<String,Map<Long,Set<PhysicalFluidTopology.Position>>> structureChunks=new HashMap<>();
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
    /** The flares each player watches for their flames (batch 2026-11-09-flare-stack, W5), and what was last published. */
    private final Watchers flareWatchers=new Watchers();
    private final Map<Long,com.wormzjl.icheme.network.FluidNetwork.FlareState> publishedFlares=new HashMap<>();
    /** The batch stills each player watches for their fires (batch 2026-11-09-batch-distiller, W6), and what was last published. */
    private final Watchers stillWatchers=new Watchers();
    private final Map<Long,com.wormzjl.icheme.network.FluidNetwork.StillState> publishedStills=new HashMap<>();
    private final Map<Long,com.wormzjl.icheme.network.FluidNetwork.MoverState> publishedMovers=new HashMap<>();
    private final com.wormzjl.icheme.runtime.instrument.InstrumentService instruments;
    private final com.wormzjl.icheme.runtime.control.ControlFacts controlFacts;
    private com.wormzjl.icheme.runtime.control.ControlService controls;
    public com.wormzjl.icheme.runtime.control.ControlService controls(){owned();if(controls==null)controls=new com.wormzjl.icheme.runtime.control.ControlService(this,controlFacts);return controls;}
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
        controlFacts=com.wormzjl.icheme.runtime.control.ControlFacts.open(server);
        com.wormzjl.icheme.world.level.block.LinePieces.watch(server,controlFacts);
        controlFacts.requireSensorIds(accessories.allSensors());
        // The world model (the network package's whole list) first: every island's model is a view of it (V2).
        model=FluidThermodynamics.forNetwork(catalog,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),options.maximumVelocity(),options.traceCutoffMoleFraction(),options.solids());
        data=FluidSavedData.open(server,this::model,catalog);
        propertyReload=new FluidPropertyReloadGuard(catalog,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),model);
        presetCache=FluidPresetCatalog.resolve(catalog);componentNames=model.components();
        var savedTopology=data.world().orElseGet(()->WorldTopologyLedger.Snapshot.empty(catalog));savedTopology.basis().requireCurrent(catalog);
        FluidPropertyReloadGuard.validateSolids(catalog.solids(),data.checkpoint(),savedTopology);observedSolidData=catalog;
        topology=new WorldTopologyLedger(savedTopology);
        topology.builds().structures().forEach((origin,structure)->indexStructure(origin,structure,true));
        presentation=new FluidPresentation<>(new PresentationHost(),topology.onlineTick());
        var settings=new IslandCoordinator.Settings(options.wallBudgetNanos(),options.wallBudgetNanos()*3/4,64,options.adaptiveCadence(),options.initialCadenceTicks(),options.certificates());
        // Island clocks read the world's online tick: one epoch increment per tick advances every island.
        runtime=new MinecraftFluidRuntime(server,this::published,settings,topology::onlineTick);
        runtime.coordinator().onReplayed(this::replayed);
        runtime.coordinator().onPhaseHold(this::phaseHeld);runtime.coordinator().nodeNames(this::deviceLabel);
        registry=new PhysicalRegistry(topology,model,new InlineFilter(options.solids().filterCapacity(),options.solids().filterResistance(),com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY,0),new RegistryHost());
        for(var entry:data.checkpoint().islands())runtime.register(dimension(entry.dimension()),entry.snapshot(),model(entry.key()));
        // Each exchanger's heat in transit is a fact of its sides (plan section 5.2).
        savedTopology.heatTransits().forEach(runtime.coordinator()::transit);
        logLoad();
        registry.flowmeters(accessories.allSensors().stream().filter(s->s.kind()==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.FLOW).map(s->s.key().host()).toList());
        registry.load();data.bindCapture(this::capture);
        columns=new ColumnOperation(topology,registry,runtime.coordinator(),model.components(),model.molecularWeights());runtime.coordinator().ports(columns);
        columns.solves(new ColumnOperation.Solves() {
            @Override public com.wormzjl.icheme.science.column.v3.V3ColumnInput design(ColumnUnit unit){return columnDesign(unit.master());}
            @Override public com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser condenser(ColumnUnit unit){return columnCondenser(unit.master());}
            /** The job runs on its feed island's model (V4), taken here on the engine thread; a feed port in no island yet waits for the next cycle. */
            @Override public boolean submit(long unit,com.wormzjl.icheme.science.column.operation.ColumnSolve.Job job,java.util.function.Consumer<com.wormzjl.icheme.science.column.operation.ColumnSolve.Outcome> done) {
                var u=topology.column(unit);var feed=u.station(com.wormzjl.icheme.science.column.operation.Port.FEED,0);
                Long island=feed==null||feed.device()==0?null:registry.owner(feed.device());
                if(island==null)return false;
                var dimension=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(u.master().dimension()));
                var request=new com.wormzjl.icheme.runtime.ProcessSolveServices.ColumnUnitRequest(com.wormzjl.icheme.runtime.ProcessSolveServices.nextRequestId(),new com.wormzjl.icheme.runtime.ProcessSolveServices.ColumnUnitTarget(dimension,unit),0,done);
                return com.wormzjl.icheme.runtime.ProcessSolveServices.submitColumnUnit(server,request,new com.wormzjl.icheme.runtime.ProcessSolveServices.ColumnUnitCommand(job,runtime.coordinator().model(island),IChemE.columnV3LiquidSupplyScreenRatio())).accepted();
            }
        });
        instruments=new com.wormzjl.icheme.runtime.instrument.InstrumentService(server,this,accessories);
        runtime.coordinator().controls(g->controls().apply(g));
        instruments.reconcileLegacyValves();
        shafts=new ShaftTrains(topology,registry::owner,runtime.coordinator());runtime.coordinator().shafts(shafts);shafts.host(new ShaftHost());
        electric=new ElectricStores(topology,registry::owner,runtime.coordinator(),()->new ElectricStores.Settings(IChemE.joulesPerFE(),IChemE.machinesNeedPower()));shafts.electric(electric);
        electric.supply(id->com.wormzjl.icheme.runtime.power.PowerNetwork.of(server).supplied(shafts.unit(id)));
        // The Create coupling (W6b): its stress rate; a world opened without Create lost its couplings' blocks, so their parts leave.
        shafts.stressPerFE(IChemE::stressPerFE);if(!net.neoforged.fml.ModList.get().isLoaded("create"))shafts.forget(com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.CREATE_COUPLING);
        // The fired heaters' burners (batch 2026-11-09-furnace-fuel-power): the network's heating values in its conserved order.
        burners=new FiredHeaters(topology,runtime.coordinator(),electric,heatingValues(catalog,model),model.molecularWeights());shafts.burners(burners);electric.moved(burners::powerMoved);
        flares=new Flares(topology,heatingValues(catalog,model),model.molecularWeights(),formulas(catalog,model));shafts.flares(flares);
        // The batch stills (batch 2026-11-09-batch-distiller): their pots, heads and outlets share the port hooks with the columns.
        stills=new Stills(topology,registry::owner,runtime.coordinator(),runtime.coordinator()::model,electric,model.components(),model.molecularWeights(),heatingValues(catalog,model));
        stills.refresh();runtime.coordinator().ports(IslandCoordinator.Ports.both(columns,stills));
        stills.solves((pot,island,job,done)->{
            var at=registrations().get(pot);if(at==null)return false;
            var dimension=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(at.device().position().dimension()));
            var request=new com.wormzjl.icheme.runtime.ProcessSolveServices.StillRequest(com.wormzjl.icheme.runtime.ProcessSolveServices.nextRequestId(),new com.wormzjl.icheme.runtime.ProcessSolveServices.StillTarget(dimension,pot),0,done);
            return com.wormzjl.icheme.runtime.ProcessSolveServices.submitStill(server,request,new com.wormzjl.icheme.runtime.ProcessSolveServices.StillCommand(job,runtime.coordinator().model(island))).accepted();
        });
        stills.fuel(this::takeStillFuel);
        // The data network's firing and reflux setpoints (owner decision O1): the control service's last cadence.
        stills.plans(new Stills.Plans(){
            public double firing(long pot){return controls==null?Double.NaN:controls.stillFiring(pot);}
            public double reflux(long pot){return controls==null?Double.NaN:controls.stillReflux(pot);}
        });
        health=new PumpHealth(topology,registry::owner,runtime.coordinator(),id->PumpHealth.pump(topology.active().get(id)),id->{var r=topology.active().get(id);return r==null?null:r.device().control();},IChemE::wearSettings);shafts.health(health);
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
        world.holdWithoutLevel();
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
    public static void forget(MinecraftServer server){EARLY_CHUNKS.remove(server);com.wormzjl.icheme.network.AssemblyNetwork.forget(server);com.wormzjl.icheme.network.MachineNetwork.forget(server);var world=SERVERS.remove(server);if(world!=null)world.close();}
    private void owned(){if(!server.isSameThread())throw new IllegalStateException("Fluid authority requires the logical server thread");}
    /** Every island of this world solves on a model built from the same captured options, so the
     * configured trace cutoff is one value for every worker and every retained solver: a retained
     * solver is replaced whenever its model identity changes, and the model is the cutoff's home. A saved island's key
     * names the network package it was saved on: it runs on the current network package whatever id the save names (the
     * load compares the data by compound ids and identity, batch 2026-11-04-refinery-compounds, W4), on the island's own
     * compound list (V3; {@link FluidThermodynamics#on} refuses a compound the package no longer has, and the load then
     * restates). */
    private FluidThermodynamics model(FluidCheckpointCodec.PackageKey saved) {
        return saved.on(model);
    }
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
    /** The model island {@code island} runs on, its own compound list (batch 2026-11-04-refinery-compounds, V2): the axis of
     * its {@link #diagnosticSnapshots} entry, which {@link IslandSets#amounts} maps onto the world list ({@link #model()}) by id. */
    public FluidThermodynamics diagnosticModel(long island){owned();return runtime.coordinator().model(island);}
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
    // The build facts (batch 2026-11-03-save-corruption, REGISTRY_FIRST_PLAN.md 3.3 and 3.5): what the registry holds to
    // place a build again. The blocks keep copies for drawing and take them from here when they bind.
    /** The paint the registry holds for line {@code id}, or null for a bare line. */
    public WorldTopologyLedger.LinePaint linePaint(long id){owned();return topology.paint(id);}
    /** Paints line {@code id}; a bare paint clears it. A line the registry no longer has, or is removing, keeps none. */
    public void paint(long id,int primary,int secondary) {
        owned();if(!topology.latest().containsKey(id))return;
        topology.paint(id,new WorldTopologyLedger.LinePaint(primary,secondary));data.setDirty();
    }
    /** The name the registry holds for one-block device {@code id} (N1), empty for none. */
    public String deviceName(long id){owned();return topology.name(id);}
    /** Names one-block device {@code id}; an empty name clears it. A device the registry no longer has, or is removing, keeps none. */
    public void name(long id,String name) {
        owned();if(!topology.latest().containsKey(id))return;
        topology.name(id,name);data.setDirty();
    }
    /** The frame of the rack cell lane {@code id} lies in, as the registry holds it, or null. */
    public WorldTopologyLedger.RackFrame rackFrame(long id){owned();return topology.rack(id);}
    /** Records the frame of the rack cell lane {@code id} lies in. A lane the registry no longer has, or is removing, keeps none. */
    public void rackFrame(long id,WorldTopologyLedger.RackFrame frame) {
        owned();var lane=topology.latest().get(id);if(lane==null||lane.device().position().lane()<=0||lane.device().port())return;
        topology.rack(id,frame);data.setDirty();
    }
    /** The registry's record of the structure whose origin is {@code origin}, or null. */
    public WorldTopologyLedger.Structure structure(PhysicalFluidTopology.Position origin){owned();return topology.structure(origin);}
    /** Records the structure whose origin is {@code origin}; null removes it. A load of a chunk of its box queues it for its re-alignment. */
    public void structure(PhysicalFluidTopology.Position origin,WorldTopologyLedger.Structure structure) {
        owned();var old=topology.structure(origin);if(old!=null)indexStructure(origin,old,false);
        topology.structure(origin,structure);if(structure!=null)indexStructure(origin,structure,true);data.setDirty();
    }
    /** Adds or removes the structure at {@code origin} in the index of the chunks its box spans. */
    private void indexStructure(PhysicalFluidTopology.Position origin,WorldTopologyLedger.Structure structure,boolean add) {
        var shape=structure.shape();var chunks=structureChunks.computeIfAbsent(origin.dimension(),k->new HashMap<>());
        for(int x=origin.x()>>4;x<=(origin.x()+shape.sizeX()-1)>>4;x++)for(int z=origin.z()>>4;z<=(origin.z()+shape.sizeZ()-1)>>4;z++) {
            long chunk=net.minecraft.world.level.ChunkPos.asLong(x,z);
            if(add)chunks.computeIfAbsent(chunk,k->new HashSet<>()).add(origin);
            else{var at=chunks.get(chunk);if(at!=null&&at.remove(origin)&&at.isEmpty())chunks.remove(chunk);}
        }
    }

    /**
     * The faces the device registered at {@code position} draws joined (batch 2026-11-09-drawn-connections): its
     * as-built joins on the projected registry ({@link PhysicalFluidTopology#builtFaces}), so accepted input shows at once
     * and a block draws what the network joins. -1 where no device without ports stands. Reads no block and loads no
     * chunk: a neighbour in a chunk that is not loaded is known by its registration.
     */
    public int drawnFaces(PhysicalFluidTopology.Position position){
        owned();var record=topology.latestAt(position);
        if(record==null||!record.device().position().equals(position)||!record.device().ports().isEmpty())return -1;
        return PhysicalFluidTopology.builtFaces(this::latestDevice,record.device());
    }
    /**
     * The faces a block of {@code kind} placed at {@code position} facing {@code facing} will draw joined once registered:
     * a hand-placed block, every face a joint and no fitting, as {@link #place} registers it.
     */
    public int placedFaces(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing){
        owned();
        var placed=new PhysicalFluidTopology.Device(Long.MAX_VALUE,position,kind,facing,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),List.of(),kind!=TopologyCompiler.Kind.GENERATOR);
        return PhysicalFluidTopology.builtFaces(p->p.equals(position)?placed:latestDevice(p),placed);
    }
    private PhysicalFluidTopology.Device latestDevice(PhysicalFluidTopology.Position position){var record=topology.latestAt(position);return record==null?null:record.device();}
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
     * ({@code hosts}, by identity), each new pipe painted {@code primary} and {@code secondary}. Returns the new pipes'
     * registrations in the order of {@code cells}.
     */
    public List<WorldTopologyLedger.Registration> lay(List<Laid> cells,double diameter,Map<Long,Integer> hosts,int primary,int secondary) {
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
        submit(edits,Math.addExact(first,cells.size()));
        for(var record:placed)paint(record.device().id(),primary,secondary);
        return placed;
    }
    /** A rack lane registered again from its rack's copy (a chunk load after the D4 reset): its joints, width, face points and bore. */
    public WorldTopologyLedger.Registration placeLane(PhysicalFluidTopology.Position position,PhysicalFluidTopology.Direction facing,int joints,int slots,long points,double diameter) {
        owned();long id=topology.nextIdentity();
        var device=new PhysicalFluidTopology.Device(id,position,TopologyCompiler.Kind.PIPE,facing,new PipeResistance.Geometry(1,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),
                new FlowControl.Passive(),List.of(),true,PhysicalFluidTopology.Cutoff.NONE,false,joints,slots,points);
        var record=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(catalog),0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /** One side of a heat-exchanging unit to place: its node (its first inlet's cell), its inlet and outlet ports, its
     * passage and its unit's facts ({@link #placeUnit} names the partners). */
    public record UnitSide(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,PipeResistance.Geometry passage,HeatUnit heat){}
    /** A kettle's shell to place with its tubes (W7): a closed vessel at its pool's node, with its phase ports, its volume,
     * level height and geometry, and its heat unit ({@link HeatUnit#pool}; {@link #placeUnit} names the partner). */
    public record UnitVessel(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,double volume,double levelHeight,com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry,HeatUnit heat){}
    /**
     * A fired heater's burners to place with its coils (batch 2026-11-09-furnace-fuel-power): the firebox, a void at the burner
     * tips' back pressure ({@link com.wormzjl.icheme.science.fluid.network.Burners#PRESSURE}) behind the FUEL port, its fuel
     * valve its control ({@link FlowControl.ValveSource#BURNER}); {@link #placeUnit} names the radiant coil its partner.
     */
    public record UnitBurners(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,HeatUnit heat){}
    /** {@link #placeUnit(List,UnitVessel)} of a unit without a vessel. */
    public List<WorldTopologyLedger.Registration> placeUnit(List<UnitSide> sides){return placeUnit(sides,null);}
    public List<WorldTopologyLedger.Registration> placeUnit(List<UnitSide> sides,@org.jetbrains.annotations.Nullable UnitVessel vessel){return placeUnit(sides,vessel,null);}
    /** A fired heater's burners, registered by themselves beside coils placed before they existed, partnered with {@code radiant}. */
    public WorldTopologyLedger.Registration placeBurners(long radiant,UnitBurners burners) {
        owned();var coil=registrations().get(radiant);
        if(coil==null||coil.device().heat()==null||coil.device().heat().side()!=com.wormzjl.icheme.science.equipment.HeatSides.Side.RADIANT)throw new IllegalArgumentException("Burners need their radiant coil");
        long id=topology.nextIdentity();var record=burnerRecord(id,burners,radiant);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /**
     * A flare stack's tip (batch 2026-11-09-flare-stack): a void at its gas inlet's cell with one bulk port, its geometry's
     * diameter its tip's, its tip limit its control ({@link FlowControl.ValveSource#FLARE}).
     */
    public WorldTopologyLedger.Registration placeFlare(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,double tip) {
        owned();long id=topology.nextIdentity();
        var device=new PhysicalFluidTopology.Device(id,node,TopologyCompiler.Kind.VOID,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,tip,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.flare(),ports,true,PhysicalFluidTopology.Cutoff.NONE,false,
                PhysicalFluidTopology.ALL_FACES,0,0,null);
        var record=new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,com.wormzjl.icheme.science.fluid.network.Flare.AMBIENT,101325,FluidDeviceSpec.nitrogen(catalog).composition()),0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    private WorldTopologyLedger.Registration burnerRecord(long id,UnitBurners burners,long radiant) {
        var device=new PhysicalFluidTopology.Device(id,burners.node(),TopologyCompiler.Kind.VOID,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.burner(),burners.ports(),true,PhysicalFluidTopology.Cutoff.NONE,false,
                PhysicalFluidTopology.ALL_FACES,0,0,burners.heat().withPartner(radiant));
        return new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,com.wormzjl.icheme.science.fluid.network.Burners.AIR,com.wormzjl.icheme.science.fluid.network.Burners.PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition()),0);
    }
    /**
     * Registers a heat-exchanging unit's sides as one ledger event (batch 2026-10-27-heat-exchanger-design): each a heat
     * side at its node with its ports and passage, passive; two sides name each other as partners, so the pair is whole
     * from its first event. A kettle's tubes are partnered with its shell instead, a vessel placed empty in the same event
     * after them ({@code vessel}, W7). Returns the registrations in the order of {@code sides}, then the vessel's.
     */
    public List<WorldTopologyLedger.Registration> placeUnit(List<UnitSide> sides,@org.jetbrains.annotations.Nullable UnitVessel vessel,@org.jetbrains.annotations.Nullable UnitBurners burners) {
        owned();
        if(sides.isEmpty()||sides.size()>2||vessel!=null&&sides.size()!=1)throw new IllegalArgumentException("A heat unit has one or two sides, a kettle one and its shell");
        if(burners!=null&&(vessel!=null||sides.size()!=2))throw new IllegalArgumentException("Burners belong to a fired heater's two coils");
        long first=topology.nextIdentity();var edits=new ArrayList<WorldTopologyLedger.Edit>();var placed=new ArrayList<WorldTopologyLedger.Registration>();
        int pair=sides.size()+(vessel==null?0:1),count=pair+(burners==null?0:1);
        for(int i=0;i<sides.size();i++) {
            var side=sides.get(i);long partner=pair==2?first+1-i:0;
            var device=new PhysicalFluidTopology.Device(first+i,side.node(),TopologyCompiler.Kind.HEAT_SIDE,PhysicalFluidTopology.Direction.NORTH,side.passage(),new FlowControl.Passive(),
                    side.ports(),true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,side.heat().withPartner(partner));
            var record=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(catalog),0);placed.add(record);edits.add(new WorldTopologyLedger.Edit(first+i,record));
        }
        if(vessel!=null) {
            long id=first+1;
            var device=new PhysicalFluidTopology.Device(id,vessel.node(),TopologyCompiler.Kind.RESERVOIR,PhysicalFluidTopology.Direction.NORTH,
                    new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),vessel.ports(),true,PhysicalFluidTopology.Cutoff.NONE,false,
                    PhysicalFluidTopology.ALL_FACES,0,0,vessel.heat().withPartner(first));
            var spec=new FluidDeviceSpec(vessel.volume(),298.15,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition(),SlurryFeed.NONE,vessel.levelHeight(),false,vessel.geometry(),null);
            var record=new WorldTopologyLedger.Registration(device,spec,0);placed.add(record);edits.add(new WorldTopologyLedger.Edit(id,record));
        }
        if(burners!=null) {
            long radiant=-1;for(int i=0;i<sides.size();i++)if(sides.get(i).heat().side()==com.wormzjl.icheme.science.equipment.HeatSides.Side.RADIANT)radiant=first+i;
            if(radiant<0)throw new IllegalArgumentException("Burners need their radiant coil");
            long id=first+pair;var record=burnerRecord(id,burners,radiant);placed.add(record);edits.add(new WorldTopologyLedger.Edit(id,record));
        }
        submit(edits,Math.addExact(first,count));return placed;
    }
    /** Removes a heat-exchanging unit's sides as one ledger event, so no event leaves half a pair. */
    public void removeUnit(List<Long> ids) {
        owned();var edits=new ArrayList<WorldTopologyLedger.Edit>();
        for(long id:new TreeSet<>(ids)){instruments.recoverHost(id);if(registrations().containsKey(id))edits.add(new WorldTopologyLedger.Edit(id,null));}
        if(!edits.isEmpty())submit(edits,topology.nextIdentity());
    }
    /** The shaft trains (W3): queue their parts' events here; their views are reads. */
    public ShaftTrains shafts(){owned();return shafts;}
    /** The electric stores (W5): the FE ports' side and their views are reads and the FE protocol only. */
    public ElectricStores electric(){owned();return electric;}
    /** The fired heaters' burners: their views and alarms are reads. */
    public FiredHeaters burners(){owned();return burners;}
    /** The flare stacks: their views and alarms are reads. */
    public Flares flares(){owned();return flares;}
    /** The batch stills (batch 2026-11-09-batch-distiller). */
    public Stills stills(){owned();return stills;}
    /**
     * A solid still's next item (W4): one taken from the fuel slot its pot's cell holds, its heat (J); 0 when the slot is
     * empty or the pot's chunk is not loaded (the fire then burns out: NO FUEL).
     */
    private double takeStillFuel(long pot) {
        var r=registrations().get(pot);if(r==null)return 0;
        var p=r.device().position();var level=server.getLevel(dimension(p.dimension()));
        var at=new BlockPos(p.x(),p.y(),p.z());
        if(level==null||!level.isLoaded(at)||!(level.getBlockEntity(at) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell)||shell.fluidIdentity()!=pot)return 0;
        return shell.takeFuel();
    }
    /** The pressure a still's DISTILLATE and VENT generators stand at, Pa: a short liquid head over the atmosphere (an owner option, BATCH_STILL_ENGINE.md). */
    public static final double STILL_OUTLET_PRESSURE=150000;
    /**
     * A batch still's devices to place (batch 2026-11-09-batch-distiller, W3): its pot (a vessel at its node with its phase
     * ports, volume, level height, geometry and heat unit: its heated area), its condenser's water side (null without the
     * built-in condenser), its DISTILLATE and VENT outlets (with the condenser) and its burners (a liquid-fired still), each
     * its one port at its own cell; and its design.
     */
    public record StillDevices(UnitVessel pot,@org.jetbrains.annotations.Nullable UnitSide water,
                               @org.jetbrains.annotations.Nullable PhysicalFluidTopology.Port distillate,@org.jetbrains.annotations.Nullable PhysicalFluidTopology.Port vent,
                               @org.jetbrains.annotations.Nullable PhysicalFluidTopology.Port burners,com.wormzjl.icheme.science.fluid.network.BatchStill.Design design) {}
    /**
     * Registers a batch still as one ledger event, and its facts ({@link StillUnit}): the water side first (the lower identity
     * keeps the pair's heat in transit), then the pot, the outlets and the burners. An open pot (the condenser built in) vents
     * through its head. Returns the registrations in that order.
     */
    public List<WorldTopologyLedger.Registration> placeStill(StillDevices d){return placeStill(d,0);}
    /** The identities of the still whose pot is {@code pot}: its pot, water side, outlets and burners that are registered. */
    public java.util.Set<Long> stillDevices(long pot) {
        owned();var u=topology.still(pot);if(u==null)return java.util.Set.of();
        var ids=new java.util.HashSet<Long>();
        for(long id:new long[]{u.id(),u.water(),u.distillate(),u.vent(),u.burners()})if(id>0&&registrations().containsKey(id))ids.add(id);
        return ids;
    }
    /**
     * {@link #placeStill(StillDevices)}, or with {@code former} a registered still's pot, the refresh of that still onto
     * {@code d} as one ledger event (decision D7, owner decision O9): each device the still has keeps its identity, its
     * controls and what it holds, and moves to its new cell with its new ports (the pot also takes its new volume, level
     * height, opening and geometry, the water side its new passage and surface); a device the layout adds registers, one it
     * no longer has leaves. Its facts stay on its pot. Returns the still's registrations: water side, pot, outlets, burners.
     */
    public List<WorldTopologyLedger.Registration> placeStill(StillDevices d,long former) {
        owned();var u=former>0?topology.still(former):null;var formerPot=u==null?null:registrations().get(former);
        if(u!=null&&formerPot!=null&&formerPot.device().stillPot())return moveStill(d,u,formerPot);
        var design=d.design();
        if(design.condenser()!=(d.water()!=null)||design.condenser()!=(d.distillate()!=null)||design.condenser()!=(d.vent()!=null))throw new IllegalArgumentException("A still's condenser has its water side and its outlets, and only it");
        if((d.burners()!=null)!=(design.heater()==com.wormzjl.icheme.science.fluid.network.BatchStill.Heater.LIQUID))throw new IllegalArgumentException("A liquid-fired still has its burners, and only it");
        long next=topology.nextIdentity();var edits=new ArrayList<WorldTopologyLedger.Edit>();var placed=new ArrayList<WorldTopologyLedger.Registration>();
        long water=0,distillate=0,vent=0,fired=0;
        if(d.water()!=null)water=next++;
        long pot=next++;
        if(d.water()!=null) {
            var side=d.water();
            var device=new PhysicalFluidTopology.Device(water,side.node(),TopologyCompiler.Kind.HEAT_SIDE,PhysicalFluidTopology.Direction.NORTH,side.passage(),new FlowControl.Passive(),
                    side.ports(),true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,side.heat().withPartner(pot));
            var record=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(catalog),0);placed.add(record);edits.add(new WorldTopologyLedger.Edit(water,record));
        }
        var vessel=d.pot();
        var potDevice=new PhysicalFluidTopology.Device(pot,vessel.node(),TopologyCompiler.Kind.RESERVOIR,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),vessel.ports(),true,PhysicalFluidTopology.Cutoff.NONE,false,
                PhysicalFluidTopology.ALL_FACES,0,0,vessel.heat().withPartner(0));
        var potSpec=new FluidDeviceSpec(vessel.volume(),298.15,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition(),SlurryFeed.NONE,vessel.levelHeight(),design.condenser(),vessel.geometry(),null);
        var potRecord=new WorldTopologyLedger.Registration(potDevice,potSpec,0);placed.add(potRecord);edits.add(new WorldTopologyLedger.Edit(pot,potRecord));
        for(var port:new PhysicalFluidTopology.Port[]{d.distillate(),d.vent()}) {
            if(port==null)continue;long id=next++;if(port==d.distillate())distillate=id;else vent=id;
            var device=new PhysicalFluidTopology.Device(id,port.cell(),TopologyCompiler.Kind.GENERATOR,PhysicalFluidTopology.Direction.NORTH,
                    new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.still(),List.of(port),true,PhysicalFluidTopology.Cutoff.NONE,false,
                    PhysicalFluidTopology.ALL_FACES,0,0,null);
            var record=new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,STILL_OUTLET_PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition()),0);placed.add(record);edits.add(new WorldTopologyLedger.Edit(id,record));
        }
        if(d.burners()!=null) {
            fired=next++;var port=d.burners();
            var device=new PhysicalFluidTopology.Device(fired,port.cell(),TopologyCompiler.Kind.VOID,PhysicalFluidTopology.Direction.NORTH,
                    new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.still(),List.of(port),true,PhysicalFluidTopology.Cutoff.NONE,false,
                    PhysicalFluidTopology.ALL_FACES,0,0,null);
            var record=new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,com.wormzjl.icheme.science.fluid.network.Burners.AIR,com.wormzjl.icheme.science.fluid.network.Burners.PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition()),0);
            placed.add(record);edits.add(new WorldTopologyLedger.Edit(fired,record));
        }
        submit(edits,next);
        topology.still(StillUnit.built(pot,water,distillate,vent,fired,design,model.componentCount()));
        stills.refresh();
        return placed;
    }
    /** {@link #placeStill(StillDevices,long)} for registered still {@code u}, its pot {@code potRecord}. */
    private List<WorldTopologyLedger.Registration> moveStill(StillDevices d,StillUnit u,WorldTopologyLedger.Registration potRecord) {
        var design=d.design();
        if(design.condenser()!=(d.water()!=null)||design.condenser()!=(d.distillate()!=null)||design.condenser()!=(d.vent()!=null))throw new IllegalArgumentException("A still's condenser has its water side and its outlets, and only it");
        if((d.burners()!=null)!=(design.heater()==com.wormzjl.icheme.science.fluid.network.BatchStill.Heater.LIQUID))throw new IllegalArgumentException("A liquid-fired still has its burners, and only it");
        long pot=u.id(),next=topology.nextIdentity();var edits=new ArrayList<WorldTopologyLedger.Edit>();var placed=new ArrayList<WorldTopologyLedger.Registration>();
        var old=potRecord.spec();var vessel=d.pot();
        var potDevice=potRecord.device().withPlacement(vessel.node(),vessel.ports()).withHeat(potRecord.device().heat().withArea(vessel.heat().area()));
        var potSpec=new FluidDeviceSpec(vessel.volume(),old.temperature(),old.pressure(),old.composition(),old.solids(),vessel.levelHeight(),design.condenser(),vessel.geometry(),old.tower());
        // A side, outlet or burner the still has moves; one the layout adds registers; one it no longer has leaves.
        long water=0;WorldTopologyLedger.Registration waterRecord=null;
        var held=u.water()>0?registrations().get(u.water()):null;
        if(d.water()!=null) {
            var side=d.water();
            var device=held!=null?held.device().withPlacement(side.node(),side.ports()).withGeometry(side.passage()).withHeat(held.device().heat().withArea(side.heat().area()))
                    :new PhysicalFluidTopology.Device(next,side.node(),TopologyCompiler.Kind.HEAT_SIDE,PhysicalFluidTopology.Direction.NORTH,side.passage(),new FlowControl.Passive(),
                            side.ports(),true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,side.heat().withPartner(pot));
            water=held!=null?held.device().id():next++;
            waterRecord=held!=null&&device.equals(held.device())?held:new WorldTopologyLedger.Registration(device,held!=null?held.spec():FluidDeviceSpec.nitrogen(catalog),held!=null?Math.addExact(held.revision(),1):0);
        }else if(held!=null)edits.add(new WorldTopologyLedger.Edit(held.device().id(),null));
        if(waterRecord!=null){placed.add(waterRecord);if(waterRecord!=held)edits.add(new WorldTopologyLedger.Edit(water,waterRecord));}
        var potNext=new WorldTopologyLedger.Registration(potDevice,potSpec,Math.addExact(potRecord.revision(),1));placed.add(potNext);
        if(!potDevice.equals(potRecord.device())||!potSpec.equals(old))edits.add(new WorldTopologyLedger.Edit(pot,potNext));else placed.set(placed.size()-1,potRecord);
        long[] ids=new long[3];var wanted=new PhysicalFluidTopology.Port[]{d.distillate(),d.vent(),d.burners()};long[] had={u.distillate(),u.vent(),u.burners()};
        for(int k=0;k<3;k++) {
            var port=wanted[k];var record=had[k]>0?registrations().get(had[k]):null;
            if(port==null){if(record!=null)edits.add(new WorldTopologyLedger.Edit(record.device().id(),null));continue;}
            WorldTopologyLedger.Registration moved;
            if(record!=null) {
                var device=record.device().withPlacement(port.cell(),List.of(port));ids[k]=record.device().id();
                moved=device.equals(record.device())?record:new WorldTopologyLedger.Registration(device,record.spec(),Math.addExact(record.revision(),1));
            }else {
                ids[k]=next++;
                var device=new PhysicalFluidTopology.Device(ids[k],port.cell(),k<2?TopologyCompiler.Kind.GENERATOR:TopologyCompiler.Kind.VOID,PhysicalFluidTopology.Direction.NORTH,
                        new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.still(),List.of(port),true,PhysicalFluidTopology.Cutoff.NONE,false,
                        PhysicalFluidTopology.ALL_FACES,0,0,null);
                moved=k<2?new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,STILL_OUTLET_PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition()),0)
                        :new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,com.wormzjl.icheme.science.fluid.network.Burners.AIR,com.wormzjl.icheme.science.fluid.network.Burners.PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition()),0);
            }
            placed.add(moved);if(moved!=record)edits.add(new WorldTopologyLedger.Edit(ids[k],moved));
        }
        if(!edits.isEmpty())submit(edits,next);
        topology.still(new StillUnit(pot,water,ids[0],ids[1],ids[2],design.volume(),design.heater(),design.condenser(),design.stages(),u.head(),u.venting(),u.burning(),u.firing(),u.reflux(),u.heated(),u.condensed()));
        stills.refresh();
        return placed;
    }
    /** The player's settings for the still whose pot is {@code pot} (W5): firing 0 to 1, reflux ratio (+Infinity closed). Null, or why not. */
    public @org.jetbrains.annotations.Nullable String stillSettings(long pot,double firing,double reflux){owned();if(closed)return "the fluid world is closed";return stills.settings(pot,firing,reflux);}
    /** A solid still's slot changed: its pot's island reads the fire again at its next slice (a wake, no solve). */
    public void stillFuelChanged(long pot){owned();Long island=registry.owner(pot);if(island!=null)runtime.coordinator().controlsMoved(List.of(island));}
    /** The network package's formulas (atoms of C, H, N, O, S) in {@code model}'s conserved order, by component name. */
    static double[][] formulas(MaterialCatalog catalog,FluidThermodynamics model) {
        String id=com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog);
        var values=catalog.formulas(id);var names=new ArrayList<>(catalog.requirePackage(id).components());names.add("Water");
        var out=new double[model.componentCount()][];var components=model.components();
        for(int c=0;c<out.length;c++){int i=names.indexOf(components.get(c));out[c]=i<0?new double[MaterialCatalog.FORMULA_ELEMENTS.size()]:values[i];}
        return out;
    }
    /** The network package's lower heating values (J/mol) in {@code model}'s conserved order, by component name. */
    static double[] heatingValues(MaterialCatalog catalog,FluidThermodynamics model) {
        String id=com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog);
        var values=catalog.heatingValues(id);var names=new ArrayList<>(catalog.requirePackage(id).components());names.add("Water");
        var out=new double[model.componentCount()];var components=model.components();
        for(int c=0;c<out.length;c++){int i=names.indexOf(components.get(c));out[c]=i<0?0:values[i];}
        return out;
    }
    /** The pumps' health (W10b): a repair kit queues its event here. */
    public PumpHealth health(){owned();return health;}
    /**
     * A shaft-driven machine (batch 2026-11-03-rotating-machinery W3): a pump or compressor casing on its curve
     * ({@code FlowControl.Machine}), a reciprocating frame ({@code Compressor}) or a steam turbine (kind {@code TURBINE},
     * {@code FlowControl.Turbine}, reading A37), registered at its suction or steam nozzle's cell with its process pair.
     */
    public WorldTopologyLedger.Registration placeMachine(PhysicalFluidTopology.Position suction,TopologyCompiler.Kind kind,List<PhysicalFluidTopology.Port> ports,FlowControl control) {
        owned();
        if(kind!=TopologyCompiler.Kind.PUMP&&kind!=TopologyCompiler.Kind.COMPRESSOR&&kind!=TopologyCompiler.Kind.TURBINE||ports.isEmpty())throw new IllegalArgumentException("Expected a shaft-driven machine");
        long id=topology.nextIdentity();
        var device=new PhysicalFluidTopology.Device(id,suction,kind,PhysicalFluidTopology.Direction.NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),control,ports,true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES);
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
        double diameter=kind==TopologyCompiler.Kind.PIPE||kind==TopologyCompiler.Kind.DIVERTER_VALVE?placedPipeDiameter(position):.05;
        // A diverter valve is placed open to its straight outlet (batch 2026-11-09-batch-distiller).
        if(kind==TopologyCompiler.Kind.DIVERTER_VALVE&&joints==PhysicalFluidTopology.ALL_FACES)joints=PhysicalFluidTopology.diverterJoints(facing,0);
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
        return placeVessel(node,ports,volume,levelHeight,open,geometry,null);
    }
    public WorldTopologyLedger.Registration placeVessel(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,double volume,double levelHeight,boolean open,com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry,com.wormzjl.icheme.science.fluid.network.CoolingTower tower) {
        owned();
        long id=topology.nextIdentity();
        var device=new PhysicalFluidTopology.Device(id,node,TopologyCompiler.Kind.RESERVOIR,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),ports);
        var spec=new FluidDeviceSpec(volume,298.15,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition(),SlurryFeed.NONE,levelHeight,open,geometry,tower);
        var record=new WorldTopologyLedger.Registration(device,spec,0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /**
     * Queues a configuration change as a ledger event and returns it, or null when nothing would change. A fired heater's
     * coils share their burners and set point (batch 2026-10-27-heat-exchanger-design): a coil's new control moves its
     * partner coil in the same event.
     */
    public WorldTopologyLedger.Event edit(long id,long expectedRevision,PhysicalFluidTopology.Device device,FluidDeviceSpec spec) {
        owned();var old=registrations().get(id);if(old==null||old.revision()!=expectedRevision||device.id()!=id)throw new IllegalStateException("Stale fluid controls");
        if(old.device().equals(device)&&old.spec().equals(spec))return null;
        var edits=new ArrayList<WorldTopologyLedger.Edit>();edits.add(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,Math.addExact(expectedRevision,1))));
        var heat=device.heat();var partner=heat!=null&&heat.burners()?registrations().get(heat.partner()):null;
        if(partner!=null&&partner.device().heat()!=null&&!partner.device().heat().control().equals(heat.control()))
            edits.add(new WorldTopologyLedger.Edit(partner.device().id(),new WorldTopologyLedger.Registration(partner.device().withHeat(partner.device().heat().withControl(heat.control())),partner.spec(),Math.addExact(partner.revision(),1))));
        return submit(edits,topology.nextIdentity());
    }
    // ---------------- running columns (batch 2026-10-28-column-operation, C3) ----------------

    /**
     * Where a running column's port stands (plan 4.2): its kind and number, the boundary valve, the cell inside the valve
     * (the column's side of it), the face from that cell to the valve, and the port's pressure (Pa) and temperature (K).
     */
    public record PortSpot(com.wormzjl.icheme.science.column.operation.Port port,int index,long valve,PhysicalFluidTopology.Position cell,
                           PhysicalFluidTopology.Direction toward,double pressure,double temperature) {}
    /**
     * A Start (plan 4.1): the column's origin, its setpoints, the pipe blocks in front of its nozzles, its ports, and the
     * registrations of a water-cooled condenser's two sides, every shell's (empty for an air cooler): those the column does
     * not hold are its water sides (C6b, S3b); and the bare tube of its air condenser's banks, m² (U2: 0 for none).
     */
    public record ColumnStart(PhysicalFluidTopology.Position master,com.wormzjl.icheme.science.column.operation.Setpoints setpoints,
                              List<PhysicalFluidTopology.Position> nozzles,List<PortSpot> spots,List<Long> condenser,double airSurface) {
        public ColumnStart{condenser=List.copyOf(condenser);if(!(airSurface>=0)||!Double.isFinite(airSurface))throw new IllegalArgumentException("Invalid air condenser surface");}
        public ColumnStart(PhysicalFluidTopology.Position master,com.wormzjl.icheme.science.column.operation.Setpoints setpoints,List<PhysicalFluidTopology.Position> nozzles,List<PortSpot> spots,List<Long> condenser){this(master,setpoints,nozzles,spots,condenser,0);}
        public ColumnStart(PhysicalFluidTopology.Position master,com.wormzjl.icheme.science.column.operation.Setpoints setpoints,List<PhysicalFluidTopology.Position> nozzles,List<PortSpot> spots){this(master,setpoints,nozzles,spots,List.of());}
    }
    /** The most devices a column holds: its members and the pipes between them. */
    public static final int COLUMN_LIMIT=4096;
    /** The column unit of the column at {@code master}, or null. */
    public @org.jetbrains.annotations.Nullable ColumnUnit column(PhysicalFluidTopology.Position master) {
        owned();for(var u:topology.columns().values())if(u.master().equals(master))return u;
        return null;
    }
    /**
     * Starts the column {@code request} names (plan 4.1), one queued ledger event: every device inside its boundary valves
     * (reached from its nozzles' pipe blocks without crossing a valve) is held by its column unit, and a port stands inside
     * each valve, a void at an inlet, a generator at an outlet. What the held devices hold stays with them. Returns null,
     * or why the column cannot start; nothing changes then.
     */
    public @org.jetbrains.annotations.Nullable String startColumn(ColumnStart request) {
        owned();if(closed)return "the fluid world is closed";
        var existing=column(request.master());
        if(existing!=null&&existing.running())return "already running";
        var valves=new HashSet<Long>();for(var s:request.spots())valves.add(s.valve());
        java.util.function.Function<PhysicalFluidTopology.Position,WorldTopologyLedger.Registration> at=p->registry.at(p).orElse(null);
        var inside=new LinkedHashMap<Long,WorldTopologyLedger.Registration>();var queue=new ArrayDeque<WorldTopologyLedger.Registration>();
        for(var nozzle:request.nozzles()) {
            var r=at.apply(nozzle);if(r==null)return "a nozzle's pipe block is not registered";
            if(!valves.contains(r.device().id())&&inside.putIfAbsent(r.device().id(),r)==null)queue.add(r);
        }
        while(!queue.isEmpty()) {
            var r=queue.removeFirst();var d=r.device();
            if(d.fixed())return "a generator or a void stands inside the column";
            if(d.suspended()||d.held()||d.port()||topology.pending(d.id()))return "a device inside the column is held or being edited";
            if(inside.size()>COLUMN_LIMIT)return "the column holds more than "+COLUMN_LIMIT+" devices";
            for(var contact:d.contacts()) {
                var next=PhysicalFluidTopology.across(at,WorldTopologyLedger.Registration::device,d,contact.cell(),contact.direction());
                if(next==null||valves.contains(next.device().id())||!d.joins(next.device(),contact.cell(),contact.direction()))continue;
                if(inside.putIfAbsent(next.device().id(),next)==null)queue.add(next);
            }
        }
        if(sharePlace(request.spots()))return "two ports share a place";
        for(var s:request.spots()) {
            var valve=registrations().get(s.valve());
            if(valve==null||inside.containsKey(s.valve()))return "a boundary valve is missing or inside the column";
            if(at.apply(s.cell().withLane(PhysicalFluidTopology.PORT_LANE))!=null)return "a port's place is taken";
        }
        long unit=existing!=null?existing.id():topology.reserve(1);long first=topology.nextIdentity();
        var edits=new ArrayList<WorldTopologyLedger.Edit>();var stations=new ArrayList<ColumnUnit.Station>();
        int m=model.componentCount();var nitrogen=FluidDeviceSpec.nitrogen(catalog).composition();
        for(var r:inside.values())edits.add(new WorldTopologyLedger.Edit(r.device().id(),new WorldTopologyLedger.Registration(r.device().withSuspendedBy(unit),r.spec(),Math.addExact(r.revision(),1))));
        long id=first;
        for(var s:request.spots()) {
            var cell=s.cell().withLane(PhysicalFluidTopology.PORT_LANE);var valve=registrations().get(s.valve()).device();
            var kind=s.port().inlet()?TopologyCompiler.Kind.VOID:TopologyCompiler.Kind.GENERATOR;
            var device=new PhysicalFluidTopology.Device(id,cell,kind,s.toward(),valve.geometry(),new com.wormzjl.icheme.science.fluid.network.FlowControl.Passive(),
                    List.of(new PhysicalFluidTopology.Port(cell,s.toward(),com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.BULK)),true,
                    PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,null,0);
            edits.add(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,s.temperature(),s.pressure(),nitrogen),0)));
            var kept=existing==null?null:existing.station(s.port(),s.index());
            stations.add(kept==null?new ColumnUnit.Station(s.port(),s.index(),id,s.valve(),com.wormzjl.icheme.science.column.operation.ColumnCycle.Account.empty(m)):new ColumnUnit.Station(s.port(),s.index(),id,s.valve(),kept.account(),kept.solids()));
            id++;
        }
        // Stations a stopped column kept and this start no longer has (the design lost a port) keep their accounts, with no port.
        if(existing!=null)stations.addAll(existing.lost(stations));
        // The condenser's water sides: of each shell's two sides, the one outside the column, partnered with one inside (C6b);
        // one a shell in parallel (S3b).
        var water=new ArrayList<Long>();
        for(long side:request.condenser()){var r=registrations().get(side);if(r!=null&&!inside.containsKey(side)&&r.device().heat()!=null&&inside.containsKey(r.device().heat().partner())&&!water.contains(side))water.add(side);}
        if(water.size()>ColumnUnit.MAXIMUM_CONDENSERS)return "the condenser has more than "+ColumnUnit.MAXIMUM_CONDENSERS+" shells";
        // A restart keeps the set points the player applied while they fit the design's ports (C7); else the design's.
        var setpoints=existing!=null&&compatible(existing.setpoints(),request.setpoints())?existing.setpoints():request.setpoints();
        var running=new ColumnUnit(unit,request.master(),true,setpoints,stations,existing!=null?existing.totals():ColumnUnit.Totals.empty(m),water,null,false,request.airSurface());
        var was=existing;topology.column(running);
        try{submit(edits,id);}catch(IllegalArgumentException|IllegalStateException refused){if(was!=null)topology.column(was);else topology.removeColumn(unit);return "the column's ports do not fit its lines: "+refused.getMessage();}
        columns.refresh();
        IChemE.LOGGER.info("column status=STARTED master={},{},{} unit={} held={} ports={}",request.master().x(),request.master().y(),request.master().z(),unit,inside.size(),stations.size());
        return null;
    }
    /** Whether two of {@code spots} stand inside one cell: two boundary valves on one inside cell have one place for a port. */
    static boolean sharePlace(List<PortSpot> spots) {
        var places=new HashSet<PhysicalFluidTopology.Position>();
        for(var s:spots)if(!places.add(s.cell()))return true;
        return false;
    }
    /** Whether set points {@code a} fit the design {@code b} was taken from: its rate and as many steam inlets, draws and pumparounds. */
    static boolean compatible(com.wormzjl.icheme.science.column.operation.Setpoints a,com.wormzjl.icheme.science.column.operation.Setpoints b) {
        return a.designFeedRate()==b.designFeedRate()&&a.steamPerFeed().length==b.steamPerFeed().length&&a.drawsPerFeed().length==b.drawsPerFeed().length
                &&a.pumparoundsPerFeed().length==b.pumparoundsPerFeed().length&&(a.reboilerPerFeed()==0)==(b.reboilerPerFeed()==0);
    }
    /**
     * The set points {@code value} for the column at {@code master} (C7, plan section 13): a fact of its unit, the next cycle
     * reads them and a changed ratio solves. Returns null, or why not: no unit yet (it never ran), or the values do not fit
     * its design. The feed rate stays at most the design rate (owner Q8).
     */
    public @org.jetbrains.annotations.Nullable String editColumn(PhysicalFluidTopology.Position master,com.wormzjl.icheme.science.column.operation.Setpoints value) {
        owned();if(closed)return "the fluid world is closed";
        var u=column(master);if(u==null)return "the column has not run yet";
        if(!compatible(u.setpoints(),value))return "the set points do not fit the column's design";
        topology.column(u.withSetpoints(value));
        IChemE.LOGGER.info("column status=SETPOINTS master={},{},{} unit={}",master.x(),master.y(),master.z(),u.id());
        return null;
    }
    /** The Operation tab's view of the column at {@code master} (C7): cached cycle data, no solve; null where it has no unit. */
    public com.wormzjl.icheme.science.column.operation.OperationView columnView(PhysicalFluidTopology.Position master,java.util.function.LongToDoubleFunction opening) {
        owned();var u=column(master);return u==null?null:columns.view(u.id(),topology.onlineTick(),opening);
    }
    public record ColumnInstrument(double value,long tick,com.wormzjl.icheme.runtime.instrument.Instrument.Availability availability) {}
    public ColumnInstrument columnInstrument(PhysicalFluidTopology.Position master,String role,com.wormzjl.icheme.runtime.instrument.Sensor.Kind kind){owned();var u=column(master);var sample=u==null?null:columns.instrument(u.id(),role,kind);return sample==null?null:new ColumnInstrument(sample.value(),sample.tick(),com.wormzjl.icheme.runtime.instrument.Instrument.Availability.AVAILABLE);}
    /** Stops column unit {@code unit} (plan 4.1), one queued ledger event: its held devices are released as they are and its
     * ports leave. Every account stays with the unit. Nothing when it is not running. */
    public void stopColumn(long unit) {
        owned();if(closed)return;columns.stop(unit,false,edits->submit(edits,topology.nextIdentity()));
    }
    /**
     * The calculator input of the design of the column at {@code master}, as built (its shell's diameter), or null where it
     * cannot be read: no operating point, or its chunk not loaded (a read never loads one).
     */
    private com.wormzjl.icheme.science.column.v3.V3ColumnInput columnDesign(PhysicalFluidTopology.Position master) {
        var vessel=columnVessel(master);
        if(vessel==null)return null;
        var trays=vessel.shape().trays();var point=trays.operating();
        return point==null||!point.fits(trays)?null:point.input(trays,vessel.shape().diameter());
    }
    /** The condenser of the column at {@code master}'s design: water-cooled where it has none. */
    private com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser columnCondenser(PhysicalFluidTopology.Position master) {
        var vessel=columnVessel(master);
        return vessel==null||vessel.shape().trays().components()==null?com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser.EXCHANGER:vessel.shape().trays().components().condenser();
    }
    /** The loaded column vessel at {@code master}, or null. */
    private com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel columnVessel(PhysicalFluidTopology.Position master) {
        for(var level:server.getAllLevels()) {
            if(!level.dimension().location().toString().equals(master.dimension()))continue;
            var at=new net.minecraft.core.BlockPos(master.x(),master.y(),master.z());
            if(!level.isLoaded(at))return null;
            var vessel=com.wormzjl.icheme.world.level.block.EquipmentStructure.at(level,at);
            return vessel==null||vessel.shape().trays()==null?null:vessel;
        }
        return null;
    }
    /** The column at {@code master} is gone (plan section 12): it stops, and its accounts leave the world, booked as destroyed. */
    public void columnRemoved(PhysicalFluidTopology.Position master) {
        owned();var u=column(master);if(u==null)return;
        columns.stop(u.id(),true,edits->submit(edits,topology.nextIdentity()));data.setDirty();
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
    /**
     * Sets diverter valve {@code id} to outlet {@code outlet} (0 straight through, 1 and 2 beside it; batch
     * 2026-11-09-batch-distiller): a fact, one queued edit of its joints; its island recompiles on the engine's schedule.
     * Null, or why not.
     */
    public @org.jetbrains.annotations.Nullable String divert(long id,int outlet) {
        owned();if(closed)return "the fluid world is closed";
        var r=topology.latest().get(id);
        if(r==null||r.device().kind()!=TopologyCompiler.Kind.DIVERTER_VALVE)return "no diverter valve";
        if(outlet<0||outlet>2)return "a diverter valve has three outlets";
        int joints=PhysicalFluidTopology.diverterJoints(r.device().facing(),outlet);
        if(joints==r.device().joints())return null;
        if(topology.pending(id))return "the diverter valve is being edited";
        edit(id,r.revision(),r.device().withJoints(joints),r.spec());
        return null;
    }
    /** The open outlet of diverter valve {@code id} (0 to 2), or -1 for none. */
    public int diverterOutlet(long id) {
        owned();var r=topology.latest().get(id);
        return r==null||r.device().kind()!=TopologyCompiler.Kind.DIVERTER_VALVE?-1:PhysicalFluidTopology.diverterOutlet(r.device().facing(),r.device().joints());
    }
    /** Reading committed static configuration never advances or materialises process state. */
    public Optional<PhysicalFluidTopology.Device> committedDevice(long id){owned();var record=topology.active().get(id);return record==null?Optional.empty():Optional.of(record.device());}
    public void remove(long id){owned();controls().removeHost(id);instruments.recoverHost(id);var old=registrations().get(id);if(old!=null)submit(List.of(new WorldTopologyLedger.Edit(id,null)),topology.nextIdentity(),old.device().filter()?new WorldTopologyLedger.Recovery(id,null):null);}
    /**
     * Holds registration {@code id}, or releases it (decision S11, REGISTRY_FIRST_PLAN.md 4.1): held, it keeps what it holds
     * and joins nothing. A topology edit at its next revision, so its islands recompile on the engine's schedule. Whether an
     * edit was queued: none for an unknown identity, a column's port, or one already so.
     */
    public boolean hold(long id,boolean held) {
        owned();var r=registrations().get(id);if(r==null||r.device().port()||r.device().held()==held)return false;
        submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(r.device().withHeld(held),r.spec(),Math.addExact(r.revision(),1)))),topology.nextIdentity());
        return true;
    }
    /** Every held part (W5c C5): the held registrations, shaft parts, cables, controllers and consoles. */
    public int heldCount() {
        owned();int count=held().size();for(var u:topology.shafts().values())if(u.held())count++;
        var facts=controls().facts();return count+facts.held().size()+facts.heldPanels().size();
    }
    /** The held registrations, queued holds included, by identity. */
    public List<WorldTopologyLedger.Registration> held() {
        owned();var held=new ArrayList<WorldTopologyLedger.Registration>();
        for(var r:registrations().values())if(r.device().held())held.add(r);
        held.sort(Comparator.comparingLong(r->r.device().id()));return held;
    }
    /**
     * An operator drops held registration {@code id} (REGISTRY_FIRST_PLAN.md 4.5): it leaves as a broken device does, what
     * it holds booked as removed, but nothing drops (decision S9): its block is not in the world. Its accessories leave the
     * accessory store with it. Whether it was held.
     */
    public boolean drop(long id) {
        owned();var r=registrations().get(id);if(r==null||!r.device().held())return false;
        instruments.discardHost(id);submit(List.of(new WorldTopologyLedger.Edit(id,null)),topology.nextIdentity());
        return true;
    }
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
        holdValves();
        // Sample electrical supply before this cadence's shaft decisions and presentation, including idle trains.
        if(topology.onlineTick()%ElectricStores.CADENCE==0&&propertyHold==null){electric.cadence();shafts.cadence();}
        // A running column's port whose account holds a compound its island lacks grows that island (V2): one queued edit.
        columns.grow(edits->submit(edits,topology.nextIdentity()));
        runtime.tick();
        if(topology.hasPendingEvents()){applyPending();runtime.coordinator().pumpIfUseful();}
        // Shaft events apply after the devices they name (W3), then their trains recompile and wake what moved.
        if(shafts.hasEvents()&&propertyHold==null){shafts.apply();if(topology.hasPendingEvents())applyPending();runtime.coordinator().pumpIfUseful();}
        // Repair kits (W10b): queued engine events, applied after the devices they name; a repaired failed pump wakes its island.
        if(health.hasEvents()&&propertyHold==null){health.apply();runtime.coordinator().pumpIfUseful();}
        instruments.tick(topology.onlineTick());
        if(topology.onlineTick()%100==0) for(var motor:shafts.units()) if(motor.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR) {
            var motorLevel=server.getLevel(dimension(motor.position().dimension()));
            var pos=new BlockPos(motor.position().x(),motor.position().y(),motor.position().z());
            if(motorLevel!=null&&motorLevel.hasChunkAt(pos)&&motorLevel.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity cell)controls().bindMotor(cell,motor);
        }
        controls().tick(topology.onlineTick());
        presentation.tick(topology.onlineTick());
        // The watched drivers' states at the presentation cadence (W3): a read of what the trains last decided.
        if(!driverWatchers.isEmpty()&&topology.onlineTick()%100==0)publishDrivers();
        if(!rotationWatchers.isEmpty()&&topology.onlineTick()%100==0)publishRotations();
        if(topology.onlineTick()%100==0)publishFlares();
        if(topology.onlineTick()%100==0)publishStills();
        if(topology.onlineTick()%ElectricStores.CADENCE==0&&propertyHold==null)health.cadence();
        // A generator's port pushes FE to its neighbours (the FE protocol, reading A65): stored FE only, nothing solves.
        pushGenerators();
        com.wormzjl.icheme.network.AssemblyNetwork.presentationTick(server, topology.onlineTick());
        // The machine pages' views (W7, reading A85): on the same 100-tick buckets, only to the open pages.
        com.wormzjl.icheme.network.MachineNetwork.presentationTick(server, topology.onlineTick());
        data.setDirty();
    }
    /**
     * Each charged generator's port pushes FE to the receivers on the six faces of its terminal cell (W5, reading A65): the
     * FE protocol, moving stored FE only, where the terminal cell is loaded. The terminal is found through the cell of the
     * generator's first shaft end, which is its structure's.
     */
    private void pushGenerators() {
        var charged=electric.charged();if(charged.isEmpty())return;
        for(long id:charged) {
            var u=topology.shaft(id);if(u==null||u.ends().isEmpty())continue;
            var end=u.ends().getFirst().cell();var level=server.getLevel(dimension(end.dimension()));if(level==null)continue;
            var at=new BlockPos(end.x(),end.y(),end.z());
            if(!level.isLoaded(at)||!(level.getBlockEntity(at) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell)||shell.shape()==null||shell.origin()==null)continue;
            var t=com.wormzjl.icheme.science.equipment.ShapeRasterizer.terminal(shell.shape());if(t==null)continue;
            var terminal=shell.origin().offset(t.x(),t.y(),t.z());
            for(var face:net.minecraft.core.Direction.values()) {
                var beside=terminal.relative(face);if(!level.isLoaded(beside))continue;
                var sink=level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,beside,face.getOpposite());
                if(sink==null||!sink.canReceive())continue;
                int offer=electric.extract(id,Integer.MAX_VALUE,true);if(offer<=0)break;
                int taken=sink.receiveEnergy(offer,false);if(taken>0)electric.extract(id,taken,false);
            }
        }
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
        operate();
        data.setDirty();
    }
    /**
     * A side's exchanger lines as its assembly last reported them (batch 2026-10-27-heat-exchanger-design, W8, plan section 7):
     * not validated, checked with {@code missing} parts not in place, complete but for the flowmeter its valve regulates by
     * ({@code UNMETERED}: a bare control valve stands open), or complete; {@code valve} is the registration of the valve the
     * side's control works (0 for none); {@code slots}, the report's state of each slot (empty before a check), which the
     * side's screen draws (the heat GUI, fluid-16). State: the assembly store holds the report, and a validation sets it again.
     */
    public record Lines(Status status,int missing,long valve,List<com.wormzjl.icheme.science.assembly.AssemblyReport.SlotReport> slots) {
        public enum Status { NOT_VALIDATED, CHECKED, UNMETERED, COMPLETE }
        public Lines(Status status,int missing,long valve){this(status,missing,valve,List.of());}
        public Lines {Objects.requireNonNull(status);slots=List.copyOf(slots);if(missing<0||valve<0||status==Status.COMPLETE&&valve==0)throw new IllegalArgumentException("Invalid exchanger lines");}
    }
    private final Map<Long,Lines> lines=new HashMap<>();
    /** The valves whose lines reverted, with the target each holds, applied as their settings at the next tick. */
    private final Map<Long,Double> holds=new java.util.LinkedHashMap<>();
    /**
     * Sets heat side {@code side}'s lines ({@code value} null: none) and the real valves the coordinator's sides work. Lines
     * that were complete and are no longer leave their valve holding its last target (plan section 7): the target its island
     * planned last becomes the valve's mass flow setting at the next tick.
     */
    public void lines(long side,@org.jetbrains.annotations.Nullable Lines value) {
        owned();
        var before=value==null?lines.remove(side):lines.put(side,value);
        if(before!=null&&before.status()==Lines.Status.COMPLETE&&(value==null||value.status()!=Lines.Status.COMPLETE||value.valve()!=before.valve())) {
            Double target=plannedTarget(before.valve());if(target!=null)holds.put(before.valve(),target);
        }
        operate();
    }
    /** Heat side {@code side}'s lines as last reported, or null for none. */
    public @org.jetbrains.annotations.Nullable Lines lines(long side){owned();return lines.get(side);}
    /** The mass flow target the valve at node {@code valve} drives its connection at in its island as last committed, or null. */
    private @org.jetbrains.annotations.Nullable Double plannedTarget(long valve) {
        Long owner=registry.owner(valve);if(owner==null)return null;
        var graph=runtime.coordinator().presentation(owner).graph();
        for(var pipe:graph.pipes())if(pipe.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve law&&graph.reservoirs().get(pipe.first()).id()==valve)
            return law.source()==com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.MASS_FLOW?law.massTarget():null;
        return null;
    }
    /** Applies the held valve targets: each valve's setting becomes a mass flow regulation at the target it held. */
    private void holdValves() {
        if(holds.isEmpty())return;
        var pending=new java.util.LinkedHashMap<>(holds);holds.clear();
        pending.forEach((valve,target)->{
            var r=registrations().get(valve);
            if(r==null||!(r.device().control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve law))return;
            var held=new com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve(com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.MASS_FLOW,
                    law.targetPressure(),Math.max(0,target),law.volumeTarget(),law.sensor(),Math.addExact(law.revision(),1));
            edit(valve,r.revision(),r.device().withControl(held),r.spec());
        });
    }
    /** The coordinator's operations: each complete side's valve, as its control registered asks for it. */
    private void operate() {
        var next=new HashMap<Long,com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Operation>();
        lines.forEach((side,l)->{
            if(l.status()!=Lines.Status.COMPLETE)return;
            var r=registrations().get(side);var heat=r==null?null:r.device().heat();if(heat==null)return;
            switch(heat.lines()) {
                case BYPASS->next.put(PhysicalFluidTopology.sideIdentity(side),com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Operation.bypass(l.valve()));
                case RATIO->next.put(PhysicalFluidTopology.sideIdentity(side),com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Operation.feed(l.valve(),heat.control().target()));
                // A column condenser's water (U2): the column sets the flow, the water rising its range (COOLER_MODEL_PLAN.md section 8).
                case FEED->next.put(PhysicalFluidTopology.sideIdentity(side),com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Operation.column(l.valve(),com.wormzjl.icheme.science.equipment.HeatSides.WATER_RANGE));
                case NONE->{}
            }
        });
        runtime.coordinator().operations(next);
    }
    /** The status a side's lines add (W8), ending in " / ", or nothing for a side without lines. */
    private String linesStatus(long id,HeatUnit heat) {
        var asked=heat.lines();if(asked==com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.NONE)return "";
        var l=lines.get(id);boolean ratio=asked==com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.RATIO,column=asked==com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.FEED;
        String fallback=ratio?"the ratio holds nothing":column?"the water flows as its line lets it":"the internal bypass controls";
        if(l==null||l.status()==Lines.Status.NOT_VALIDATED)return "LINES NOT CHECKED: "+fallback+" / ";
        if(l.status()==Lines.Status.CHECKED)return "LINES NOT COMPLETE: "+l.missing()+" part"+(l.missing()==1?"":"s")+" not in place; "+fallback+" / ";
        if(l.status()==Lines.Status.UNMETERED)return "LINES NOT COMPLETE: the "+(ratio||column?"feed":"bypass")+" valve needs its flowmeter to regulate; "+fallback+" / ";
        return ratio?"LINES COMPLETE: the feed valve holds the ratio / ":column?"LINES COMPLETE: the column sets the feed valve / ":"LINES COMPLETE: the bypass valve controls / ";
    }
    /**
     * Rebind placed devices, queue missing registrations, then mark their presentation buckets. This also rebuilds an
     * empty authority after a format reset, including equipment spanning chunks. The chunk's registrations, and the
     * structures recorded over it, are queued for their re-alignment (REGISTRY_FIRST_PLAN.md 4.2, 4.4). Never loads a chunk
     * or runs a solve.
     */
    public void loadedChunk(ResourceKey<Level> dimension,long chunk) {
        owned();if(closed)return;
        var level=server.getLevel(dimension);if(level==null)return;
        var at=new net.minecraft.world.level.ChunkPos(chunk);
        var loaded=level.getChunkSource().getChunkNow(at.x,at.z);if(loaded==null)return;
        String name=dimension.location().toString();
        for(var cell:List.copyOf(loaded.getBlockEntitiesPos())) {
            var block=loaded.getBlockState(cell).getBlock();
            if(!(block instanceof FluidDeviceBlock)&&!(block instanceof EquipmentShellBlock)&&!(block instanceof com.wormzjl.icheme.world.level.block.PipeRackBlock)&&!(block instanceof com.wormzjl.icheme.world.level.block.ControlPanelBlock)&&!(block instanceof com.wormzjl.icheme.world.level.block.ConduitBlock))continue;
            var entity=loaded.getBlockEntity(cell); // Includes saved cells not yet promoted from pending NBT.
            if(entity instanceof com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity box)controls().loaded(box);
            else if(entity instanceof com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity cable)controls().loaded(cable);
            else if(entity instanceof com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity rack){rack.bind(this);controls().loaded(rack);}
            else if(entity instanceof FluidDeviceBlockEntity pipe) {
                var position=new PhysicalFluidTopology.Position(name,cell.getX(),cell.getY(),cell.getZ());
                var record=registry.at(position).orElseGet(()->place(position,pipe.kind(),PhysicalFluidTopology.Direction.valueOf(pipe.getBlockState().getValue(FluidDeviceBlock.FACING).name()),FluidDeviceBlock.cutoff(pipe.getBlockState()),FluidDeviceBlock.hasPressureValve(pipe.getBlockState()),FluidDeviceBlock.hasFilter(pipe.getBlockState()),FluidDeviceBlock.hasMover(pipe.getBlockState())?pipe.getBlockState().getValue(FluidDeviceBlock.INLINE_MOVER).kind():TopologyCompiler.Kind.PIPE,pipe.kind()==TopologyCompiler.Kind.PIPE?pipe.joints():PhysicalFluidTopology.ALL_FACES));
                pipe.bind(this,record.device());
            } else if(entity instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null&&(shell.shape().fluidDevice()||com.wormzjl.icheme.science.equipment.UnitDesign.shafted(shell.shape().kind()))) {
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
        var devices=registry.chunk(name,chunk);
        if(!devices.isEmpty())FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignChunks);
        for(long id:devices){presentation.markDirty(id);if(realigning.add(id))realignment.computeIfAbsent(name,k->new ArrayDeque<>()).add(id);}
        for(long id:topology.shaftsIn(name,chunk))if(shaftRealigning.add(id))shaftRealignment.computeIfAbsent(name,k->new ArrayDeque<>()).add(id);
        controls().loadedChunk(name,chunk);
        var structures=structureChunks.getOrDefault(name,Map.of()).get(chunk);
        if(structures!=null)for(var origin:structures)EquipmentStructure.realignLater(level,new net.minecraft.core.BlockPos(origin.x(),origin.y(),origin.z()));
    }
    /**
     * The next registration of {@code dimension} the re-alignment checks, taken off its queue, or 0 when none is queued
     * (registry first, REGISTRY_FIRST_PLAN.md 4.2). Each registration of a loaded chunk is queued once, at the load.
     */
    public long nextRealignment(String dimension) {
        owned();var queue=closed?null:realignment.get(dimension);if(queue==null)return 0;
        Long id=queue.poll();if(queue.isEmpty())realignment.remove(dimension);
        if(id==null)return 0;realigning.remove(id);return id;
    }
    /** The next shaft part of {@code dimension} the re-alignment checks, taken off its queue, or 0 when none is queued (W5c C1). */
    public long nextShaftRealignment(String dimension) {
        owned();var queue=closed?null:shaftRealignment.get(dimension);if(queue==null)return 0;
        Long id=queue.poll();if(queue.isEmpty())shaftRealignment.remove(dimension);
        if(id==null)return 0;shaftRealigning.remove(id);return id;
    }
    /**
     * Holds every registration whose dimension has no level (REGISTRY_FIRST_PLAN.md 4.2): no block can stand for it, so it
     * keeps what it holds and joins nothing until a level of that dimension loads its chunk again. One queued event.
     */
    private void holdWithoutLevel() {
        var levels=new HashSet<String>();for(var level:server.getAllLevels())levels.add(level.dimension().location().toString());
        var edits=new ArrayList<WorldTopologyLedger.Edit>();
        for(var r:registrations().values()) {
            var d=r.device();
            if(!d.port()&&!d.held()&&!levels.contains(d.position().dimension()))
                edits.add(new WorldTopologyLedger.Edit(d.id(),new WorldTopologyLedger.Registration(d.withHeld(true),r.spec(),Math.addExact(r.revision(),1))));
        }
        if(edits.isEmpty())return;
        edits.sort(Comparator.comparingLong(WorldTopologyLedger.Edit::id));
        submit(edits,topology.nextIdentity());
        IChemE.LOGGER.warn("fluid_realign held={} reason=NO_LEVEL: their dimensions have no level; /icheme devices held lists them",edits.size());
    }
    /** A device's block entity loaded or was bound: marked for its bucket, never presented at once. */
    public void deviceLoaded(long id){owned();if(!closed){presentation.deviceLoaded(id);if(instruments!=null)instruments.bind(instrumentHost(id));}}
    /** A device's block entity unloaded or was removed: publications stop marking it, and its watches end (a client asks again when it loads the device). */
    public void deviceUnloaded(long id){owned();instruments.forget(id);forgetSightGlass(id);forgetMover(id);tankWatchers.remove(id);presentation.deviceUnloaded(id);publishedMenuData.remove(id);publishedGlides.remove(id);lastLines.remove(id);}
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
    /**
     * A casing's or a steam turbine's Drive group (W7, owner decision D31, option A): its train's driver (a turbine is its
     * own), its status and speed, the casing's share and shaft power; {@code NO DRIVER} for a casing with none, where it
     * stands closed; null for a device with no shaft unit. A read of what the trains last decided.
     */
    private FluidView.Drive drive(long id){
        var own=shafts.unitOfDevice(id);if(own==null)return null;
        var mine=shafts.view(own.id());double share=mine==null?0:Math.max(0,mine.share()),power=mine==null?0:mine.shaftPower();
        var driver=own.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.STEAM_TURBINE?own:shafts.driverOf(id);
        if(driver==null)return new FluidView.Drive("",0,0,"NO DRIVER",0,false,0,power);
        var v=shafts.view(driver.id());String status=v==null?"":v.status();if(status.length()>256)status=status.substring(0,256);
        return new FluidView.Drive(shafts.kindOf(driver.id()).name(),driver.ratedPower(),driver.poles(),status,v==null?0:v.speed(),
                false,share,power);
    }
    /**
     * A pump's readings (W10d, owner decision D37): its suction as its last committed slice read it and its health (W10b),
     * a constructed centrifugal pump's design for its curve; null for a device that is no pump. A read of what the engine
     * last committed: no solve.
     */
    private FluidView.Pump pump(long id,WorldTopologyLedger.Registration registration){
        if(!PumpHealth.pump(registration))return null;var h=health.health(id);if(h==null)return null;
        var s=health.suction(id);
        var curve=registration.device().control() instanceof FlowControl.Machine machine&&machine.law() instanceof com.wormzjl.icheme.science.machine.CentrifugalPump law
                ?new FluidView.Curve(law.ratedFlow(),law.ratedHead(),law.efficiency(),law.ratedSpeed()):null;
        return new FluidView.Pump(finite(s.available()),finite(s.required()),finite(s.gas()),Math.clamp(h,0,1),curve);
    }
    private static Double finite(double value){return Double.isFinite(value)?value:null;}
    public void forgetWatcher(UUID player){owned();driverWatchers.remove(player);rotationWatchers.remove(player);tankWatchers.forget(player);for(long id:sightGlassWatchers.devices(player))unwatchSightGlass(player,id);for(long id:moverWatchers.devices(player))unwatchMover(player,id);flareWatchers.forget(player);stillWatchers.forget(player);}

    /** Requested shaft/machine speeds, keyed by dimension as well as cell; published values are presentation state only. */
    private final Map<UUID,Set<PhysicalFluidTopology.Position>> rotationWatchers=new HashMap<>();
    private final Map<PhysicalFluidTopology.Position,com.wormzjl.icheme.network.RotationNetwork.State> publishedRotations=new HashMap<>();

    /** Validate a bounded subscription and replay its cached publication. Opening a watch never reads a train's speed. */
    public void watchRotations(net.minecraft.server.level.ServerPlayer player,List<BlockPos> watch,List<BlockPos> unwatch) {
        owned();if(closed)return;
        var level=player.serverLevel();String dimension=level.dimension().location().toString();
        var set=rotationWatchers.computeIfAbsent(player.getUUID(),k->new HashSet<>());
        var removed=new HashSet<>(unwatch);
        set.removeIf(p->!p.dimension().equals(dimension)||removed.contains(new BlockPos(p.x(),p.y(),p.z())));
        for(var position:watch) {
            if(set.size()>=com.wormzjl.icheme.network.RotationNetwork.MAXIMUM_WATCHED||!level.hasChunkAt(position)||!moverRange(player,position))continue;
            var key=new PhysicalFluidTopology.Position(dimension,position.getX(),position.getY(),position.getZ());
            if(!(level.getBlockState(position).getBlock() instanceof com.wormzjl.icheme.world.level.block.ShaftBlock)&&shafts.unitAt(key)==null)continue;
            set.add(key);
            var published=publishedRotations.get(key);
            if(published!=null)com.wormzjl.icheme.network.RotationNetwork.send(player,published);
        }
        if(set.isEmpty())rotationWatchers.remove(player.getUUID());
    }

    /** Cadence-only reads of the trains' already decided rpm, sent exclusively to clients watching those parts. */
    private void publishRotations() {
        var current=new HashMap<PhysicalFluidTopology.Position,com.wormzjl.icheme.network.RotationNetwork.State>();
        var units=new HashMap<PhysicalFluidTopology.Position,ShaftUnit>();
        for(var unit:shafts.units())units.put(unit.position(),unit);
        var speeds=new HashMap<Long,Double>();
        for(var it=rotationWatchers.entrySet().iterator();it.hasNext();) {
            var entry=it.next();var player=server.getPlayerList().getPlayer(entry.getKey());
            if(player==null||player.hasDisconnected()){it.remove();continue;}
            String dimension=player.serverLevel().dimension().location().toString();
            for(var key:List.copyOf(entry.getValue())) {
                var position=new BlockPos(key.x(),key.y(),key.z());
                if(!key.dimension().equals(dimension)||!player.serverLevel().hasChunkAt(position)||!moverRange(player,position)){
                    entry.getValue().remove(key);continue;
                }
                var state=current.computeIfAbsent(key,p->{
                    var pair=rotationSpeeds(units.get(p),units,speeds);
                    float rpm=(float)pair[0],output=(float)pair[1];
                    return new com.wormzjl.icheme.network.RotationNetwork.State(position,Float.isFinite(rpm)?rpm:0,Float.isFinite(output)?output:0);
                });
                com.wormzjl.icheme.network.RotationNetwork.send(player,state);
            }
            if(entry.getValue().isEmpty())it.remove();
        }
        publishedRotations.clear();publishedRotations.putAll(current);
    }

    /** A gearbox's connected shaft fixes that end's speed; its compiled ratio also fixes an unconnected other end. */
    private double[] rotationSpeeds(ShaftUnit unit,Map<PhysicalFluidTopology.Position,ShaftUnit> units,Map<Long,Double> speeds) {
        if(unit==null)return new double[]{0,0};
        if(unit.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.GEARBOX) {
            double ratio=shafts.ratio(unit.id());
            if(!(ratio>0))return new double[]{0,0};
            for(var end:unit.ends()) {
                var p=end.cell();var adjacent=units.get(new PhysicalFluidTopology.Position(p.dimension(),p.x()+end.dx(),p.y()+end.dy(),p.z()+end.dz()));
                int axis=end.dx()!=0?0:end.dy()!=0?1:2;
                if(adjacent==null||adjacent.kind()!=com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.SHAFT||adjacent.axis()!=axis)continue;
                double rpm=rotationSpeed(adjacent,speeds);
                return end.output()?new double[]{rpm/ratio,rpm}:new double[]{rpm,rpm*ratio};
            }
            return new double[]{0,0};
        }
        double rpm=rotationSpeed(unit,speeds);
        return new double[]{rpm,rpm};
    }

    private double rotationSpeed(ShaftUnit unit,Map<Long,Double> speeds) {
        return speeds.computeIfAbsent(unit.id(),id->{
            var view=shafts.view(id);double rpm=view==null?0:Math.max(0,view.speed());
            if(unit.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.SHAFT&&(view==null||view.status().equals("LOOSE")||view.status().equals("NO DRIVER"))) {
                var level=server.getLevel(dimension(unit.position().dimension()));var pos=new BlockPos(unit.position().x(),unit.position().y(),unit.position().z());
                if(level!=null&&level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.ShaftParts.Coupling nativeShaft)rpm=nativeShaft.nativeSpeed();
            }
            return rpm;
        });
    }

    /** The drivers each player watches (W3), by their structures' origins, within their dimension. */
    private final Map<UUID,Set<BlockPos>> driverWatchers=new HashMap<>();
    private final Map<PhysicalFluidTopology.Position,com.wormzjl.icheme.network.FluidNetwork.DriverState> publishedDrivers=new HashMap<>();
    /**
     * A driver sound subscription replays its cached publication; only the presentation cadence reads train state.
     */
    public void watchDrivers(net.minecraft.server.level.ServerPlayer player,List<BlockPos> watch,List<BlockPos> unwatch) {
        owned();if(closed)return;var set=driverWatchers.computeIfAbsent(player.getUUID(),k->new HashSet<>());
        unwatch.forEach(set::remove);
        for(var origin:watch) {
            if(set.size()>=com.wormzjl.icheme.network.FluidNetwork.MAXIMUM_WATCHED||!player.serverLevel().hasChunkAt(origin)||!moverRange(player,origin))continue;
            var key=new PhysicalFluidTopology.Position(player.serverLevel().dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ());
            var unit=shafts.unitAt(key);
            if(unit==null||!(unit.kind().driver()||unit.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.GENERATOR))continue;
            set.add(origin.immutable());
            var state=publishedDrivers.get(key);
            if(state!=null)com.wormzjl.icheme.network.FluidNetwork.sendDriver(player,state);
        }
        if(set.isEmpty())driverWatchers.remove(player.getUUID());
    }
    private com.wormzjl.icheme.network.FluidNetwork.DriverState driverState(net.minecraft.server.level.ServerLevel level,BlockPos origin) {
        var unit=shafts.unitAt(new PhysicalFluidTopology.Position(level.dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ()));
        if(unit==null||!(unit.kind().driver()||unit.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.GENERATOR))return null;
        var view=shafts.view(unit.id());if(view==null)return null;
        return new com.wormzjl.icheme.network.FluidNetwork.DriverState(origin,view.share()>0,(float)Math.max(0,view.share()));
    }
    /** The presentation cadence's driver states: one per watched driver, to its watchers only. */
    private void publishDrivers() {
        var current=new HashMap<PhysicalFluidTopology.Position,com.wormzjl.icheme.network.FluidNetwork.DriverState>();
        for(var it=driverWatchers.entrySet().iterator();it.hasNext();) {
            var e=it.next();var player=server.getPlayerList().getPlayer(e.getKey());
            if(player==null||player.hasDisconnected()){it.remove();continue;}
            for(var origin:List.copyOf(e.getValue())) {
                var key=new PhysicalFluidTopology.Position(player.serverLevel().dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ());
                var state=player.serverLevel().hasChunkAt(origin)&&moverRange(player,origin)
                        ?current.computeIfAbsent(key,k->driverState(player.serverLevel(),origin)):null;
                if(state==null) {
                    e.getValue().remove(origin);
                    com.wormzjl.icheme.network.FluidNetwork.sendDriver(player,new com.wormzjl.icheme.network.FluidNetwork.DriverState(origin,false,0));
                } else com.wormzjl.icheme.network.FluidNetwork.sendDriver(player,state);
            }
            if(e.getValue().isEmpty())it.remove();
        }
        publishedDrivers.clear();publishedDrivers.putAll(current);
    }
    /** A sound/animation subscription replays published operating state only; it never reads or advances a process. */
    public void watchMovers(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        owned();if(closed)return;var uuid=player.getUUID();var level=player.serverLevel();
        for(var target:unwatch)unwatchMover(uuid,target.identity());
        for(var target:watch) {
            var pos=target.position();
            if(!level.hasChunkAt(pos)||!moverRange(player,pos)||!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()!=target.identity())continue;
            var record=registrations().get(target.identity());
            // A constructed mover at its fluid cell, or (W10c, owner 2026-11-04) an in-line mover at its pipe block.
            if(record==null||!(record.device().control() instanceof FlowControl.Mover)||!cell(record).equals(pos)||!record.device().position().dimension().equals(level.dimension().location().toString()))continue;
            if(moverWatchers.watch(uuid,target.identity())) {
                refreshObserved(target.identity());var state=publishedMovers.get(target.identity());
                if(state!=null)com.wormzjl.icheme.network.FluidNetwork.sendMover(player,state);
            }
        }
    }
    /**
     * A flare's flame subscription (W5): it replays the state last published, or the flare's last booked span (a read), and
     * the presentation cadence keeps it current. It never reads or advances a process.
     */
    public void watchFlares(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        owned();if(closed)return;var uuid=player.getUUID();var level=player.serverLevel();
        for(var target:unwatch)flareWatchers.unwatch(uuid,target.identity());
        for(var target:watch) {
            var pos=target.position();
            if(!level.hasChunkAt(pos)||!moverRange(player,pos)||!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()!=target.identity())continue;
            var record=registrations().get(target.identity());
            if(record==null||!record.device().flare()||!cell(record).equals(pos)||!record.device().position().dimension().equals(level.dimension().location().toString()))continue;
            if(flareWatchers.watch(uuid,target.identity())) {
                var state=publishedFlares.get(target.identity());if(state==null)state=flareState(target);
                if(state!=null)com.wormzjl.icheme.network.FluidNetwork.sendFlare(player,state);
            }
        }
    }
    /** Flare {@code target}'s flame from its last booked span (a read): its heat over its rating, its unburnt flow over its limit. */
    private com.wormzjl.icheme.network.FluidNetwork.FlareState flareState(com.wormzjl.icheme.network.FluidNetwork.Target target) {
        var view=flares.view(target.identity());if(view==null)return null;
        boolean flame=view.status()==Flares.Status.FLARING||view.status()==Flares.Status.OVER_CAPACITY;
        float share=(float)Math.clamp(view.heat()/view.rated(),0,1),plume=view.status()==Flares.Status.FLAME_OUT&&view.limit()>0?(float)Math.clamp(view.massFlow()/view.limit(),.05,1):0;
        return new com.wormzjl.icheme.network.FluidNetwork.FlareState(target,view.status().ordinal(),flame?Math.max(share,.02f):0,plume);
    }
    /** The presentation cadence's flare states: one per watched flare, to its watchers only. */
    private void publishFlares() {
        publishedFlares.clear();
        for(long id:flareWatchers.devices()) {
            var record=registrations().get(id);
            if(record==null||!record.device().flare()){flareWatchers.remove(id);continue;}
            var pos=cell(record);var state=flareState(new com.wormzjl.icheme.network.FluidNetwork.Target(pos,id));if(state==null)continue;
            publishedFlares.put(id,state);
            for(var uuid:flareWatchers.watchers(id)) {
                var player=server.getPlayerList().getPlayer(uuid);
                if(player==null||player.hasDisconnected()||!player.serverLevel().dimension().location().toString().equals(record.device().position().dimension())||!moverRange(player,pos)){flareWatchers.unwatch(uuid,id);continue;}
                com.wormzjl.icheme.network.FluidNetwork.sendFlare(player,state);
            }
        }
    }
    /**
     * A batch still's fire subscription (W6), as a flare's: it replays the state last published, or the still's cached view
     * (a read), and the presentation cadence keeps it current. It never reads or advances a process. A still is watched at
     * its pot's cell, where its identity binds.
     */
    public void watchStills(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch) {
        owned();if(closed)return;var uuid=player.getUUID();var level=player.serverLevel();
        for(var target:unwatch)stillWatchers.unwatch(uuid,target.identity());
        for(var target:watch) {
            var pos=target.position();
            if(!level.hasChunkAt(pos)||!moverRange(player,pos)||!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()!=target.identity())continue;
            var record=registrations().get(target.identity());
            if(record==null||!record.device().stillPot()||!cell(record).equals(pos)||!record.device().position().dimension().equals(level.dimension().location().toString()))continue;
            if(stillWatchers.watch(uuid,target.identity())) {
                var state=publishedStills.get(target.identity());if(state==null)state=stillState(target);
                if(state!=null)com.wormzjl.icheme.network.FluidNetwork.sendStill(player,state);
            }
        }
    }
    /** Still {@code target}'s fire from its cached view (a read): its heat over its rating, whether it boils, whether its spout drips. */
    private com.wormzjl.icheme.network.FluidNetwork.StillState stillState(com.wormzjl.icheme.network.FluidNetwork.Target target) {
        var u=topology.still(target.identity());var v=stills.view(target.identity());if(u==null||v==null)return null;
        float fire=(float)Math.clamp(v.heat()/u.design().rating(),0,1);
        var s=v.status();
        boolean boiling=v.heat()>0&&(s==BatchStill.Status.DISTILLING||s==BatchStill.Status.BOILING||s==BatchStill.Status.FLOODING||s==BatchStill.Status.COOLING_LIMITED);
        boolean dripping=u.condenser()&&v.distillateFlow()>com.wormzjl.icheme.network.FluidNetwork.StillState.DRIP;
        return new com.wormzjl.icheme.network.FluidNetwork.StillState(target,u.heater().ordinal(),s.ordinal(),fire,boiling,dripping);
    }
    /** The presentation cadence's still states: one per watched still, to its watchers only. */
    private void publishStills() {
        publishedStills.clear();
        for(long id:stillWatchers.devices()) {
            var record=registrations().get(id);
            if(record==null||!record.device().stillPot()){stillWatchers.remove(id);continue;}
            var pos=cell(record);var state=stillState(new com.wormzjl.icheme.network.FluidNetwork.Target(pos,id));if(state==null)continue;
            publishedStills.put(id,state);
            for(var uuid:stillWatchers.watchers(id)) {
                var player=server.getPlayerList().getPlayer(uuid);
                if(player==null||player.hasDisconnected()||!player.serverLevel().dimension().location().toString().equals(record.device().position().dimension())||!moverRange(player,pos)){stillWatchers.unwatch(uuid,id);continue;}
                com.wormzjl.icheme.network.FluidNetwork.sendStill(player,state);
            }
        }
    }
    /**
     * Batch still {@code pot}'s page (W6), a read: the engine's cached view, the player's settings, what a data-network
     * controller holds instead (where {@code Stills.firing} or {@code Stills.reflux} differs from the setting), and a solid
     * still's fuel slot as its pot's cell holds it. Null for no still or none in the engine yet.
     */
    private FluidView.Still stillView(long pot) {
        var u=topology.still(pot);var v=stills.view(pot);if(u==null||v==null)return null;
        boolean column=u.condenser()&&u.stages()>0;
        double firing=stills.firing(pot),reflux=stills.reflux(pot);
        Double heldFiring=Double.isFinite(firing)&&firing!=u.firing()?Math.clamp(firing,0,1):null;
        boolean heldValve=column&&!Double.isNaN(reflux)&&reflux!=u.reflux();
        boolean heldClosed=heldValve&&reflux==Double.POSITIVE_INFINITY;
        Double heldReflux=heldValve&&!heldClosed&&reflux>=0?reflux:null;
        String fuel="";int count=0;double energy=0;
        if(u.heater()==BatchStill.Heater.SOLID) {
            var stack=stillFuel(pot);
            if(!stack.isEmpty()){fuel=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();count=Math.min(64,stack.getCount());energy=EquipmentShellBlockEntity.fuelEnergy(stack);}
        }
        double effective=v.reflux();
        var alarms=v.alarms().stream().map(a->a.text).toList();
        return new FluidView.Still(u.heater().name(),u.stages(),u.condenser(),u.design().rating(),u.firing(),u.closedValve()?StillUnit.DEFAULT_REFLUX:u.reflux(),u.closedValve(),
                heldFiring,heldReflux,heldClosed,
                positive(v.potTemperature()),positive(v.headTemperature()),Math.max(0,finiteOr(v.level())),Math.max(0,finiteOr(v.filled())),Math.max(0,finiteOr(v.heat())),
                Math.max(0,finiteOr(v.distillateFlow())),Math.max(0,finiteOr(v.ventFlow())),Math.clamp(finiteOr(v.condensed()),0,1),
                column&&Double.isFinite(effective)&&effective>=0?effective:null,v.status().name(),alarms,fuel,count,Math.max(0,finiteOr(energy)),Math.max(0,finiteOr(u.burning())));
    }
    /** A temperature, K, or null where unknown (NaN before the first slice or column answer). */
    private static Double positive(double value){return Double.isFinite(value)&&value>0?value:null;}
    private static double finiteOr(double value){return Double.isFinite(value)?value:0;}
    /** What a solid still's fuel slot holds (a read of its pot's cell), or an empty stack when the cell is not loaded. */
    private net.minecraft.world.item.ItemStack stillFuel(long pot) {
        var r=registrations().get(pot);if(r==null)return net.minecraft.world.item.ItemStack.EMPTY;
        var p=r.device().position();var level=server.getLevel(dimension(p.dimension()));
        var at=new BlockPos(p.x(),p.y(),p.z());
        if(level==null||!level.isLoaded(at)||!(level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell)||shell.fluidIdentity()!=pot)return net.minecraft.world.item.ItemStack.EMPTY;
        var slot=shell.fuel();return slot==null?net.minecraft.world.item.ItemStack.EMPTY:slot.getStackInSlot(0);
    }
    private static boolean moverRange(net.minecraft.server.level.ServerPlayer player,BlockPos pos){return pos.distToCenterSqr(player.position())<=com.wormzjl.icheme.network.FluidNetwork.MOVER_RANGE*com.wormzjl.icheme.network.FluidNetwork.MOVER_RANGE;}
    private void refreshObserved(long id){if(moverWatchers.watchers(id).isEmpty()&&sightGlassWatchers.watchers(id).isEmpty()&&(instruments==null||!instruments.observes(id)))presentation.unobserve(id);else presentation.observe(id);}
    private void unwatchMover(UUID player,long id){moverWatchers.unwatch(player,id);refreshObserved(id);}
    /** The mover state last published for device {@code id} (a read), or null. */
    public com.wormzjl.icheme.network.FluidNetwork.MoverState publishedMover(long id){owned();return publishedMovers.get(id);}
    private void forgetMover(long id){moverWatchers.remove(id);publishedMovers.remove(id);refreshObserved(id);}
    private void publishMover(WorldTopologyLedger.Registration record,net.minecraft.server.level.ServerLevel level,BlockPos pos) {
        if(!(record.device().control() instanceof FlowControl.Mover mover))return;
        long id=record.device().id();var target=new com.wormzjl.icheme.network.FluidNetwork.Target(pos,id);
        // A machine turns at its shaft train's share (W3); a set-flow mover at its whole speed while it runs.
        double share=shafts.unitOf(id)?shafts.share(id):record.device().kind()==TopologyCompiler.Kind.PIPE&&electric.generator(id)!=null?(electric.powered(id)?mover instanceof FlowControl.Machine m?m.speed():1:0):1;
        boolean running=record.device().suspendedBy()!=0||mover.enabled()&&share>0&&mover.requestedVolumeFlow()>0;
        var state=new com.wormzjl.icheme.network.FluidNetwork.MoverState(target,!mover.liquid(),
                // A column holds its pumps and fans only while it runs, and runs them (C7).
                running,(float)(running?Math.max(share,0):0),
                // A pump's crackle and churn (W10c) from its last committed suction; 0 for a compressor and a stopped mover.
                running&&mover.liquid()?health.crackle(id):0,running&&mover.liquid()?health.churn(id):0,
                // An in-line pump's rated speed (its D21 duty speed) for its pitch; a casing's comes from its shape.
                mover instanceof FlowControl.Pump pump&&pump.requestedVolumeFlow()>0?(float)com.wormzjl.icheme.science.machine.Npsh.dutySpeed(pump.requestedVolumeFlow(),com.wormzjl.icheme.science.machine.Motor.ratedSpeed(2)):0);
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
    public void controlInputsChanged(java.util.Collection<Long> devices){owned();runtime.coordinator().controlsMoved(devices.stream().map(registry::owner).filter(java.util.Objects::nonNull).distinct().toList());
        for(long d:devices)if(topology.still(d)!=null)stills.replanned(d);}
    public boolean controlCoupled(long sensor,long output){owned();var a=registry.owner(sensor);return a!=null&&a.equals(registry.owner(output));}
    public boolean controlReserved(long output){
        owned();var r=registrations().get(output);if(r==null)return false;
        if(r.device().control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve valve
                &&(valve.source().planned()||com.wormzjl.icheme.science.fluid.network.PortPlan.COLUMN_SENSOR.equals(valve.sensor())))return true;
        var heat=r.device().heat();if(heat!=null&&(heat.control().mode()==HeatUnit.Mode.COLUMN||heat.control().mode()==HeatUnit.Mode.RATIO))return true;
        for(var entry:lines.entrySet())if(entry.getValue().status()==Lines.Status.COMPLETE&&entry.getValue().valve()==output){var side=registrations().get(entry.getKey());if(side!=null&&side.device().heat()!=null&&side.device().heat().lines()!=com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.NONE)return true;}
        return false;
    }
    /** A valve separated from a vessel by a single hydraulic path has an unambiguous inlet/outlet action. */
    public com.wormzjl.icheme.runtime.control.ControlSettings.Action controlAction(long sensor,long output){
        owned();var owner=registry.owner(output);if(owner==null)return null;var graph=runtime.coordinator().observe(owner).graph();
        int source=-1;for(int n=0;n<graph.reservoirs().size();n++)if(graph.reservoirs().get(n).id()==sensor)source=n;
        if(source<0)return null;
        for(int e=0;e<graph.pipes().size();e++){var p=graph.pipes().get(e);if(!(p.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve)||graph.reservoirs().get(p.first()).id()!=output)continue;
            var seen=new java.util.BitSet();var queue=new java.util.ArrayDeque<Integer>();queue.add(p.first());seen.set(p.first());
            while(!queue.isEmpty()){int n=queue.removeFirst();for(int k=0;k<graph.pipes().size();k++)if(k!=e){var link=graph.pipes().get(k);int next=link.first()==n?link.second():link.second()==n?link.first():-1;if(next>=0&&!seen.get(next)){seen.set(next);queue.addLast(next);}}}
            if(seen.get(p.second()))return null;
            return seen.get(source)?com.wormzjl.icheme.runtime.control.ControlSettings.Action.OUTLET:com.wormzjl.icheme.runtime.control.ControlSettings.Action.INLET;
        }return null;
    }
    public void instrumentObservationChanged(long id){owned();refreshObserved(id);}
    public void forgetFlowmeter(long id){owned();registry.forgetFlowmeter(id);}
    /** Whether a flowmeter is installed on device {@code id} (W8). */
    public boolean flowmeterOn(long id){owned();return registry.flowmeterOn(id);}
    public void flowmeter(long id,boolean installed){owned();registry.flowmeter(id,installed);}
    public boolean removePipeAccessory(long id){owned();return registry.removePipeAccessory(id);}
    public record InstrumentReading(double pressure,double temperature,boolean flowing,double massFlow,Double volumeFlow){public InstrumentReading(double pressure,double temperature,boolean flowing){this(pressure,temperature,flowing,0,0.0);}}
    public InstrumentReading instrumentPipeReading(long id){owned();Long owner=registry.owner(id);if(owner==null)return null;var r=registrations().get(id);if(r==null)return null;var snapshot=presented(owner);if(snapshot.lastResult().isEmpty())return null;
        for(var observed:snapshot.lastResult().get().observations())for(var sample:observed.flowSamples())if(sample.host()==id)
            return new InstrumentReading(sample.pressure(),observed.temperature(),observed.flowing(),sample.massFlow(),sample.volumeFlow());
        for(var mapping:registry.pipeViews(id)){if(!Double.isFinite(mapping.tap()))continue;for(var observed:snapshot.lastResult().get().observations())if(observed.pipe()==mapping.pipeId())return new InstrumentReading(observed.pressure(mapping.tap(),r.device().position().y(),!mapping.forward()),observed.temperature(),observed.flowing(),observed.massFlow()*(mapping.forward()?1:-1),observed.density()>0?observed.massFlow()/observed.density()*(mapping.forward()?1:-1):0);}return null;}
    public InstrumentReading instrumentAt(PhysicalFluidTopology.Position position){owned();var r=at(position).orElse(null);return r==null?null:instrumentPipeReading(r.device().id());}
    /** The accepted mover inlet/outlet, using the same node-to-stream reduction as heat-unit nozzles. */
    public FluidView.Port instrumentMoverPort(long id,boolean outlet){
        owned();Long owner=registry.owner(id);if(owner==null)return null;var snapshot=presented(owner);var result=snapshot.lastResult().orElse(null);if(result==null)return null;
        var graph=snapshot.graph();for(int i=0;i<graph.pipes().size();i++){var pipe=graph.pipes().get(i);if(graph.reservoirs().get(pipe.first()).id()!=id||!(pipe.control() instanceof FlowControl.Mover||pipe.control() instanceof FlowControl.Turbine))continue;
            var port=HeatPresentation.port(outlet?"outlet":"inlet",graph.reservoirs().get(outlet?pipe.second():pipe.first()),result.averageMassFlows()[i],heatSource(new HashMap<>()));if(outlet)for(var observation:result.observations())if(observation.pipe()==pipe.id())return new FluidView.Port(port.role(),port.temperature(),observation.firstPressure(),port.massFlow(),port.phaseMoles(),port.phaseVolumes());return port;}
        return null;
    }
    /** Accepted inlet/outlet readings for a basin; no inferred temperature or fresh property evaluation. */
    public List<FluidView.Port> instrumentVesselPorts(long id){owned();Long owner=registry.owner(id);if(owner==null)return List.of();var snapshot=presented(owner);return snapshot.lastResult().isEmpty()?List.of():HeatPresentation.poolPorts(id,snapshot,heatSource(new HashMap<>()));}
    /** Structural ID allocation shares the world's existing identity namespace, without a process edit or solve. */
    public long reserveControlIdentity(){owned();long id=topology.reserve(1);data.setDirty();return id;}
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
        owned();flush();var islands=new ArrayList<FluidCheckpointCodec.IslandEntry>();
        // Materialised first: a certified side's replay books its heat in transit, which the facts then carry.
        var snapshots=runtime.coordinator().snapshots();electric.flush();health.flush();var world=topology.snapshot().withHeatTransits(runtime.coordinator().transits());
        for(var snapshot:snapshots) {
            if(registry.members(snapshot.id()).isEmpty())throw new IllegalStateException("Fluid island "+snapshot.id()+" holds no device; refusing to save it");
            String dimension=world.active().get(registry.members(snapshot.id()).getFirst()).device().position().dimension();
            // Each island's own compound list (V3), named in full, so a compound added to the package moves no island's model.
            islands.add(runtime.coordinator().entry(dimension,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),snapshot));
        }
        return new FluidSavedData.Capture(new FluidCheckpointCodec.Checkpoint(islands),world);
    }
    /** Each pipe's last flowing line reading, for its screen while it stands still. Presentation state: a restart starts it empty. */
    private final Map<Long,FluidView.Reading> lastLines=new HashMap<>();
    /**
     * The stream pipe {@code id} carries: a junction its own node, another pipe the observation its gauge reads (the donor's
     * temperature, the pressure at the block). A still pipe keeps the last flowing temperature seen, or, with none, what lies
     * in it at rest, both marked still. Null where the pipe has no observation yet.
     */
    private @org.jetbrains.annotations.Nullable FluidView.Reading lineReading(long id,FluidView view) {
        boolean moving=view.pipeInfo().connections().stream().anyMatch(c->c.massRateKgPerSecond()>0);
        FluidView.Reading now;
        if(view.state()!=null)now=new FluidView.Reading(view.state().temperature(),view.state().pressure(),moving);
        else {
            var reading=instrumentPipeReading(id);
            if(reading==null||!(reading.temperature()>0)||!Double.isFinite(reading.pressure()))return lastLines.get(id)==null?null:lastLines.get(id).still();
            now=new FluidView.Reading(reading.temperature(),reading.pressure(),reading.flowing());
        }
        if(now.flowing()){lastLines.put(id,now);return now;}
        var last=lastLines.get(id);
        return last==null?now:new FluidView.Reading(last.temperature(),now.pressure(),false);
    }
    /** The heat presentation's reads, over this bucket's island cache. */
    private HeatPresentation.Source heatSource(Map<Long,IslandCoordinator.Snapshot> islands) {
        return new HeatPresentation.Source() {
            @Override public WorldTopologyLedger.Registration registration(long id){return registrations().get(id);}
            @Override public IslandCoordinator.Snapshot island(long device){Long owner=registry.owner(device);return owner==null?null:islands.computeIfAbsent(owner,FluidWorldAuthority.this::presentation);}
            @Override public Lines lines(long side){return lines.get(side);}
            @Override public WorldTopologyLedger.Registration at(PhysicalFluidTopology.Position position){return registry.at(position).orElse(null);}
            @Override public FluidView view(long device){return FluidWorldAuthority.this.view(device,islands);}
            @Override public double[] weights(){return model.molecularWeights();}
            @Override public double portPressure(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port){return node.state().pressure()+com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.portHead(model,node,port,node.state());}
        };
    }
    /** The status a side's controller adds (W4, plan section 6), ending in " / ", or nothing while it meets its target. */
    static String controlStatus(com.wormzjl.icheme.science.fluid.network.HeatLaw law,HeatUnit unit) {
        var why=law instanceof com.wormzjl.icheme.science.fluid.network.HeatSide side?side.limit():law instanceof com.wormzjl.icheme.science.fluid.network.AirCooled cooled?cooled.limit():com.wormzjl.icheme.science.fluid.network.HeatControl.Limit.NONE;
        boolean fans=law instanceof com.wormzjl.icheme.science.fluid.network.AirCooled;
        return switch(why) {
            case NONE->"";
            case LOWEST->fans?"LIMITED: the louvers are shut / ":"LIMITED: the bypass is fully open / ";
            case HIGHEST->fans?((com.wormzjl.icheme.science.fluid.network.AirCooled)law).fanLimit()<1?"LIMITED: NO POWER, natural draught / ":"LIMITED: the fans run at full speed / ":"LIMITED: the bypass is closed / ";
            case LOW_LIMIT->String.format(java.util.Locale.ROOT,"LOW LIMIT: the outlet is held at %.1f K / ",unit==null?0:unit.control().lowLimit());
        };
    }
    /** A device's view, as a presentation read: queued events are not applied (they apply on the engine's tick), and a
     * certified island is materialised to now but never woken by it. */
    /** A device in no island: the column that holds it and runs it (C7, plan section 13), or why it has none. */
    /** The held registrations beside the network ({@link PhysicalRegistry#heldBeside}), derived again when the registry's facts change. */
    private PhysicalRegistry.HeldBeside heldBeside=PhysicalRegistry.HeldBeside.NONE;
    private long heldBesideVersion=-1;
    /**
     * The status clause that names the held registrations beside device {@code id}, or beside its island {@code owner}
     * (registry first, REGISTRY_FIRST_PLAN.md 4.5): the position of the one of lowest identity and how many more, ending in
     * " / "; empty when none is. Its words are the client's (FluidDeviceScreen).
     */
    private String heldClause(long id,Long owner) {
        if(heldBesideVersion!=topology.version()) {
            heldBesideVersion=topology.version();var held=new ArrayList<WorldTopologyLedger.Registration>();
            for(var r:topology.active().values())if(r.device().held())held.add(r);
            held.sort(Comparator.comparingLong(r->r.device().id()));
            heldBeside=held.isEmpty()?PhysicalRegistry.HeldBeside.NONE:registry.heldBeside(held);
        }
        var near=heldBeside.of(id,owner);if(near.isEmpty())return "";
        var first=topology.active().get(near.getFirst());if(first==null)return "";
        var p=first.device().position();
        return HELD_DEVICE+p.x()+", "+p.y()+", "+p.z()+(near.size()>1?" (and "+(near.size()-1)+" more)":"")+" / ";
    }
    /** The status clause of a held registration beside a device's network: its position, as {@link #heldClause} writes it. */
    public static final String HELD_DEVICE="HELD DEVICE: ";
    private String islandless(PhysicalFluidTopology.Device d,long id){
        var u=d.suspendedBy()==0?null:topology.column(d.suspendedBy());
        if(u!=null)return "COLUMN: part of the column at "+u.master().x()+", "+u.master().y()+", "+u.master().z()+", running";
        return !topology.active().containsKey(id)?"WAITING: topology event":Objects.requireNonNullElse(registry.diagnostic(id),"NO FLOW: no hydraulic boundary");
    }
    public FluidView view(long id){owned();return view(id,new HashMap<>());}
    /** Whether {@link #stored} is reading: the islands' stored snapshots stand in for the presentation's. */
    private boolean storedOnly;
    /** {@code read} of views and instruments from the islands as they are stored, advancing none: a column's Operation tab, which a menu open reads (a replay runs no engine work). */
    public <T> T stored(java.util.function.Supplier<T> read){owned();storedOnly=true;try{return read.get();}finally{storedOnly=false;}}
    private IslandCoordinator.Snapshot presented(long island){return storedOnly?onWorldList(island,runtime.coordinator().observe(island)):presentation(island);}
    /** Island {@code island} as a presentation reads it, on the world list (V2 stage 5): every view, display and wire vector
     * stays on the network package's whole list whatever the island carries. */
    private IslandCoordinator.Snapshot presentation(long island){return onWorldList(island,runtime.coordinator().presentation(island));}
    private IslandCoordinator.Snapshot onWorldList(long island,IslandCoordinator.Snapshot snapshot){return IslandSets.presented(snapshot,runtime.coordinator().model(island),model);}
    /** {@code islands} caches island reads, so one bucket reads each island once however many of its devices it presents. */
    private FluidView view(long id,Map<Long,IslandCoordinator.Snapshot> islands) {
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.viewBuilds);var registration=registrations().get(id);if(registration==null)return new FluidView(id,0,0,topology.onlineTick(),"REMOVED",null,0,List.of());
        Long owner=registry.owner(id);if(owner==null)return new FluidView(id,registration.revision(),0,topology.onlineTick(),heldClause(id,null)+islandless(registration.device(),id),null,0,List.of(),null,0,List.of(),null);
        var snapshot=islands.computeIfAbsent(owner,this::presented);FluidView.State state=null;int nodeIndex=-1;boolean empty=false;
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
                else if(pipe.control() instanceof FlowControl.Valve&&ControlConstraints.standing(snapshot.graph()).get(i))status+=" / STANDING_OPEN: a lower flow target on this line regulates";
                if(pipe.control() instanceof FlowControl.Mover mover) {
                    String inlet=mode==FlowControl.Mode.INLET_WRONG_PHASE?inletReason(snapshot.graph(),i,result.advancedSeconds()):"";
                    // The share of its set flow a set-flow mover moved, on its suction's density; a machine has no set flow.
                    var drawn=snapshot.graph().reservoirs().get(pipe.first());
                    double delivered=mover instanceof FlowControl.Machine||drawn.empty()||!(mover.targetVolumeFlow()>0)?Double.NaN
                            :q[i]/(drawn.state().mass()/drawn.state().volume())/mover.targetVolumeFlow();
                    if(mover.liquid())status=PipePresentation.pumpStatus(status,mover,mode,inlet,delivered);
                    // A pump's health (W10b) and suction (W10d, owner decision D37): FAILED first while it stands failed, else
                    // CAVITATION or GAS last; its health and suction have their meters (the view's Pump).
                    if(mover.liquid()){var word=health.status(id);if(word.equals("FAILED"))status="FAILED: use a repair kit / "+status;else if(!word.isEmpty())status+=" / "+word;}
                    else {status+=" / "+mode;if(!inlet.isEmpty())status+=" / "+inlet;var shortfall=PipePresentation.shortfall(mover,mode,delivered);if(!shortfall.isEmpty())status+=" / "+shortfall;}
                    // The limit the mover's law holds: a set-flow pump's setting is the rise for water and scales with its
                    // supply's density, the stream its supply walk ends at (owner decision D11, A2); a compressor's is the ratio over
                    // the suction pressure; a machine's reads the suction stream it draws (decision D4).
                    if(!snapshot.graph().reservoirs().get(pipe.first()).empty()&&mode!=FlowControl.Mode.INLET_WRONG_PHASE&&mode!=FlowControl.Mode.PUMP_SUCTION_LIMIT) {var suction=snapshot.graph().reservoirs().get(pipe.first()).state();
                        double density=mover instanceof FlowControl.Pump?InletPhase.density(model,NetworkReduction.reopened(snapshot.graph()),i,mover.targetVolumeFlow(),result.advancedSeconds()):suction.mass()/suction.volume();
                        double limit=mover.riseLimit(density,suction.pressure(),model.pumpReferenceDensity(),mover.liquid()?1.4:model.heatCapacityRatio(suction));
                        status+=String.format(java.util.Locale.ROOT,mover.liquid()?" (limit %.0f Pa on this fluid)":" (limit %.0f Pa at this suction)",limit);}
                }else status+=" / "+mode;}}
        }
        // A generator or a void in several islands (owner decision D17) reads the status of its owner (the lowest island) and
        // the net flow of all of them.
        if(registration.device().fixed())for(long other:registry.islands(id))if(other!=owner) {
            var shared=islands.computeIfAbsent(other,this::presentation);if(shared.lastResult().isEmpty())continue;var q=shared.lastResult().orElseThrow().averageMassFlows();
            int index=-1;for(int i=0;i<shared.graph().reservoirs().size();i++)if(shared.graph().reservoirs().get(i).id()==id)index=i;
            for(int i=0;i<shared.graph().pipes().size();i++){var pipe=shared.graph().pipes().get(i);if(pipe.first()==index)flow+=q[i];if(pipe.second()==index)flow-=q[i];}
        }
        // A filter's and a heat side's own pipe (batch 2026-10-27-heat-exchanger-design): the drop across the cake or the passage.
        long own=filter!=null?PhysicalFluidTopology.filterIdentity(id):registration.device().kind()==TopologyCompiler.Kind.HEAT_SIDE?PhysicalFluidTopology.sideIdentity(id):0;
        if(own!=0)for(var pipe:snapshot.graph().pipes())if(pipe.id()==own)devicePressureChange=snapshot.graph().reservoirs().get(pipe.first()).state().pressure()-snapshot.graph().reservoirs().get(pipe.second()).state().pressure();
        // A fired heater's radiant coil firing at its maximum short of what it should do (batch 2026-10-27-heat-exchanger-design,
        // owner Q11): it names its outlet's shortfall from the set point. The convection coil takes the flue gas as it comes (F1).
        if(own!=0)for(var pipe:snapshot.graph().pipes())if(pipe.id()==own&&pipe.heat() instanceof com.wormzjl.icheme.science.fluid.network.FiredCoil coil&&coil.firing()>0&&coil.firing()<1) {
            double shortfall=coil.setTemperature()-snapshot.graph().reservoirs().get(pipe.second()).state().temperature();
            status=(shortfall>0?String.format(java.util.Locale.ROOT,"DUTY INSUFFICIENT: outlet %.1f K below the set point / ",shortfall):"")+status;
        }
        // A side's controller short of its target (W4, plan section 6): its actuator at an end, or held back by its low limit.
        if(own!=0)for(var pipe:snapshot.graph().pipes())if(pipe.id()==own)status=controlStatus(pipe.heat(),registration.device().heat())+status;
        // A side's lines (W8): whether its control works the real valves.
        if(registration.device().heat()!=null)status=linesStatus(id,registration.device().heat())+status;
        if(filter!=null&&filter.clogged())status="filter clogged / "+status;
        var closures=snapshot.lastResult().map(PassiveIntervalSolver.Result::rejectionReasons).orElse(Map.of());
        for(var mapping:mappings)for(var pipe:snapshot.graph().pipes())if(pipe.id()==mapping.pipeId()&&pipe.blockedDirections()!=0&&pipe.filter()==null)status=solidClosure(mapping.pipeId(),closures)+" / "+status;
        if(registry.diagnostic(id)!=null)status=registry.diagnostic(id)+" / "+status;
        var active=topology.active().get(id);if(active==null||active.revision()!=registration.revision())status="WAITING: configuration event / "+status;
        if(unboundBindings.contains(id))status="UNBOUND: saved inventory retained / "+status;
        status=heldClause(id,owner)+status;
        var routes=new ArrayList<FluidView.PipeRoute>();
        for(var historyEntry:history)for(var pipe:snapshot.graph().pipes())if(pipe.id()==historyEntry.pipeId()) {
            routes.add(new FluidView.PipeRoute(pipe.id(),nodeLabel(snapshot.graph().reservoirs().get(pipe.first()).id()),nodeLabel(snapshot.graph().reservoirs().get(pipe.second()).id())));
        }
        double seconds=snapshot.lastResult().map(PassiveIntervalSolver.Result::advancedSeconds).orElse(0.0);
        var view=new FluidView(id,registration.revision(),snapshot.clock().committedTick(),topology.onlineTick(),status,state,flow,history,devicePressureChange,
                seconds,routes,filter);
        if(registration.device().kind()==TopologyCompiler.Kind.RESERVOIR&&nodeIndex>=0)view=view.withOutlets(TankOutlets.of(model,snapshot.graph(),nodeIndex,snapshot.lastResult().orElse(null),registry.tankFaces(id)))
                .withTank(TankOutlets.tank(model,snapshot.graph().reservoirs().get(nodeIndex),snapshot.lastResult().orElse(null)));
        if(registration.device().kind()==TopologyCompiler.Kind.PIPE||registration.device().filter()||registration.device().actuator()||registration.device().kind()==TopologyCompiler.Kind.HEAT_SIDE)
            view=view.withPipeInfo(PipePresentation.withBulkSpeed(
                PipePresentation.inspect(registration.device(),snapshot.graph(),mappings,history,seconds,componentNames.size()),
                runtime.coordinator().bulkVolumeRates(owner),registration.device().geometry().area()));
        if(registration.spec().tower()!=null) {
            var rates=snapshot.lastResult().map(result->result.towers().get(id)).orElse(null);
            view=view.withTower(new FluidView.Tower(registration.spec().tower(),rates));
            if(rates!=null&&!rates.status().equals("RUNNING"))view=view.withStatus(view.status()+" / "+rates.status());
        }
        // A fan unit's power (W5, reading A68): NO POWER, the notice UNMETERED, or its electric draw and store.
        // W7 (reading A86): the draw and the store go to the Power group; NO POWER or UNMETERED stay on the display.
        var power=electric.power(id);if(power!=null){view=view.withPower(power);if(!power.status().isEmpty())view=view.withStatus(view.status()+" / "+power.status());}
        // A casing's or turbine's driver (W7, option A's Drive group; it replaces W3's SHAFT clause, reading A43).
        var drive=drive(id);if(drive!=null)view=view.withDrive(drive);
        var pump=pump(id,registration);if(pump!=null)view=view.withPump(pump);
        // A heat side (batch 2026-10-27-heat-exchanger-design): its outlet junction's temperature, the heat its HEAT boundaries
        // booked over the interval, an air cooler's fans and an exchanger side's bypass as planned, a fired heater's burners,
        // set point and most heat.
        var heat=registration.device().heat();
        if(heat!=null)for(var pipe:snapshot.graph().pipes())if(pipe.id()==own) {
            double joule=0;
            for(var boundary:snapshot.lastResult().map(PassiveIntervalSolver.Result::boundaries).orElse(List.of()))
                if(boundary.kind()==com.wormzjl.icheme.science.fluid.network.ConservativeTransport.BoundaryTransfer.Kind.HEAT&&boundary.nodeId()==own)joule+=boundary.totalEnergyJoule();
            double share=heat.control().share(),outlet=snapshot.graph().reservoirs().get(pipe.second()).state().temperature();
            var coil=pipe.heat() instanceof com.wormzjl.icheme.science.fluid.network.FiredCoil fired?fired:null;
            if(pipe.heat() instanceof com.wormzjl.icheme.science.fluid.network.AirCooled cooled)share=cooled.fans();
            Double bypass=pipe.heat() instanceof com.wormzjl.icheme.science.fluid.network.HeatSide side?side.bypass():null;
            view=view.withHeat(new FluidView.Heat(heat.unit().name(),heat.side().name(),heat.partner()!=0,outlet,seconds>0?joule/seconds:0,
                    heat.fans()?share:null,heat.fans()?com.wormzjl.icheme.science.equipment.HeatSides.fanPower(heat.area(),share):null,
                    heat.burners()?share:null,heat.burners()?heat.control().target():null,coil!=null?coil.duty():null,bypass));
        }
        // A fired heater's burners (batch 2026-11-09-furnace-fuel-power): fuel, fired heat, the fans' power and the alarms on both coils' pages.
        if(heat!=null&&heat.burners()&&view.heat()!=null) {
            long b=burners.burnersOf(heat.side()==com.wormzjl.icheme.science.equipment.HeatSides.Side.RADIANT?id:heat.partner());
            var fired=b==0?null:burners.view(b);
            if(fired!=null) {
                var alarms=fired.alarms().stream().map(a->a.text).toList();
                view=view.withHeat(view.heat().withFuel(new FluidView.Fuel(fired.fuelFlow(),fired.heatingValue(),fired.fired(),fired.asked(),fired.fanDraw(),alarms)));
                var fans=electric.power(b);if(fans!=null)view=view.withPower(fans);
                for(var a:alarms)if(!view.status().contains(a))view=view.withStatus(view.status()+" / "+a);
            }
        }
        // A kettle's shell (W7): the heat its pool took over the interval, booked at its key, at the pool's temperature.
        if(heat!=null&&heat.pool()&&nodeIndex>=0) {
            long key=PhysicalFluidTopology.sideIdentity(id);double joule=0;
            for(var boundary:snapshot.lastResult().map(PassiveIntervalSolver.Result::boundaries).orElse(List.of()))
                if(boundary.kind()==com.wormzjl.icheme.science.fluid.network.ConservativeTransport.BoundaryTransfer.Kind.HEAT&&boundary.nodeId()==key)joule+=boundary.totalEnergyJoule();
            view=view.withHeat(new FluidView.Heat(heat.unit().name(),heat.side().name(),true,snapshot.graph().reservoirs().get(nodeIndex).state().temperature(),seconds>0?joule/seconds:0,
                    null,null,null,null,null,null));
        }
        // The heat GUI (HEAT_GUI_PLAN.md section 1): the actuator, the unit's ports and its lines.
        if(heat!=null&&view.heat()!=null) {
            com.wormzjl.icheme.science.fluid.network.HeatLaw law=null;
            for(var pipe:snapshot.graph().pipes())if(own!=0&&pipe.id()==own)law=pipe.heat();
            view=view.withHeat(HeatPresentation.complete(view.heat(),id,heat,law,heatSource(islands)));
        }
        // A batch still's pot (batch 2026-11-09-batch-distiller, W3): its status and alarms on the status line, an electric one's power.
        if(registration.device().stillPot()) {
            var line=stills.statusLine(id);if(!line.isEmpty()&&!view.status().contains(line))view=view.withStatus(view.status()+" / "+line);
            // W6: the still's page, its settings, what a controller holds and its fuel slot.
            var still=stillView(id);if(still!=null)view=view.withStill(still);
        }
        // A flare stack (batch 2026-11-09-flare-stack): its flow, heat, gas and status; its alarms on the status line.
        if(registration.device().flare()) {
            var f=flares.view(id);
            if(f!=null) {
                view=view.withFlare(new FluidView.Flare(f.massFlow(),f.heat(),f.heatingValue(),f.flameTemperature(),f.limit(),f.rated(),registration.device().geometry().diameter(),f.status().name()));
                for(var a:f.alarms())if(!view.status().contains(a.text))view=view.withStatus(view.status()+" / "+a.text);
            }
        }
        // A plain pipe's line reading (OWNER_TEST_REVIEW.md section 6): the stream it carries, as its gauge reads it.
        if(registration.device().kind()==TopologyCompiler.Kind.PIPE&&view.pipeInfo()!=null)view=view.withPipeInfo(view.pipeInfo().withReading(lineReading(id,view)));
        if(registration.device().kind()==TopologyCompiler.Kind.VOID) {
            var sources=new ArrayList<IslandCoordinator.Snapshot>();
            for(long island:registry.islands(id))sources.add(islands.computeIfAbsent(island,this::presentation));
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
            // An in-line mover's state (W10c, owner 2026-11-04): a pump's crackle and churn for its watchers.
            if(record.device().control() instanceof FlowControl.Mover)publishMover(record,level,pos);
            return true;
        }
        public void failed(long device,RuntimeException failure){IChemE.LOGGER.error("fluid_presentation device={} status=FAILED detail=The view could not be presented at its bucket",device,failure);}
        public void deliver(com.wormzjl.icheme.world.inventory.FluidDeviceMenu menu,long device,boolean withStatic,FluidView view,String reply) {
            var data=publishedMenuData.get(device);
            if(data!=null)com.wormzjl.icheme.network.FluidNetwork.deliver(menu,withStatic,data,reply);
        }
    }
    /** The world side of {@link PhysicalRegistry}: the runtime's islands, players for recoveries, and what a commit changes here. */
    /** The shaft trains' world side (W3): a governor valve's edit as a queued ledger event, a turbine's speed sensor, a shaft block's size where loaded. */
    private final class ShaftHost implements ShaftTrains.Host {
        @Override public Set<Long> linked(long motor){return controls().connectedHosts(motor);}
        @Override public void removed(long id){controls().removeHost(id);}
        @Override public void output(PhysicalFluidTopology.Position port,double rpm,double watts) {
            var level=server.getLevel(dimension(port.dimension()));if(level==null)return;
            var pos=new BlockPos(port.x(),port.y(),port.z());if(!level.hasChunkAt(pos))return;
            if(level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.ShaftParts.Coupling bridge)bridge.output(rpm,watts);
        }
        @Override public void edit(long device,FlowControl.ControlValve control) {
            var r=registrations().get(device);if(r==null)return;
            FluidWorldAuthority.this.edit(device,r.revision(),r.device().withControl(control),r.spec());
        }
        @Override public UUID speedSensor(long turbine){return instruments.speedSensor(turbine);}
        /** A coupling's block entity takes its impact where its chunk is loaded (W6b); a reloaded one reports again and is sent it again. */
        @Override public void stress(PhysicalFluidTopology.Position coupling,double impact) {
            var level=server.getLevel(dimension(coupling.dimension()));if(level==null)return;
            var pos=new BlockPos(coupling.x(),coupling.y(),coupling.z());if(!level.isLoaded(pos))return;
            if(level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.ShaftParts.Coupling c)c.stressImpact(impact);
        }
        @Override public void resize(PhysicalFluidTopology.Position shaft,int pixels) {
            var level=server.getLevel(dimension(shaft.dimension()));if(level==null)return;
            var pos=new BlockPos(shaft.x(),shaft.y(),shaft.z());if(!level.hasChunkAt(pos))return;
            var state=level.getBlockState(pos);
            if(state.getBlock() instanceof com.wormzjl.icheme.world.level.block.ShaftBlock&&state.getValue(com.wormzjl.icheme.world.level.block.ShaftBlock.SIZE)!=pixels)
                level.setBlock(pos,state.setValue(com.wormzjl.icheme.world.level.block.ShaftBlock.SIZE,pixels),net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }
    private final class RegistryHost implements PhysicalRegistry.Host {
        public IslandCoordinator coordinator(){return runtime.coordinator();}
        public void topology(String name,Set<UUID> events,Set<Long> affected,List<IslandCoordinator.Replacement> replacements,long committed,long online,
                             Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,InlineFilter> releasedFilters,Runnable commit) {
            runtime.topology(dimension(name),events,affected,replacements,model,committed,online,additions,removals,releasedFilters,commit);
        }
        public boolean acceptsRecovery(UUID player){var online=server.getPlayerList().getPlayer(player);return online!=null&&online.getInventory().getFreeSlot()>=0;}
        @Override public Set<String> portCompounds(long port){
            var column=columns==null?Set.<String>of():columns.compounds(port);var still=stills==null?Set.<String>of():stills.compounds(port);
            if(still.isEmpty())return column;if(column.isEmpty())return still;var all=new java.util.TreeSet<>(column);all.addAll(still);return all;
        }
        public void refused(UUID event,String reason){refuseEvent(event,reason);}
        public void committed(Set<Long> removed,Set<Long> recompiled) {
            // A removed exchanger takes its heat in transit along (plan section 5.2): its sides leave in one event, keyed by the lower.
            for(long device:removed)topology.removedHeat(runtime.coordinator().removeTransit(PhysicalFluidTopology.sideIdentity(device)));
            unboundBindings.removeAll(removed);publishedMenuData.keySet().removeAll(removed);publishedGlides.keySet().removeAll(removed);for(long device:removed){tankWatchers.remove(device);forgetSightGlass(device);forgetMover(device);}
            columns.committed();
            // A batch still whose pot left takes its head's accounts along, booked as destroyed (batch 2026-11-09-batch-distiller).
            for(long device:removed)if(topology.still(device)!=null){topology.removeStill(device);stills.removed(device);}
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
    @Override public void close(){owned();if(closed)return;closed=true;unboundStructures.clear();realignment.clear();realigning.clear();shaftRealignment.clear();shaftRealigning.clear();structureChunks.clear();publishedMenuData.clear();publishedSightGlasses.clear();publishedMovers.clear();publishedFlares.clear();publishedStills.clear();driverWatchers.clear();publishedDrivers.clear();rotationWatchers.clear();publishedRotations.clear();runtime.close();data.setDirty();}

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
        /** Every watched device, a copy. */
        public Set<Long> devices(){return Set.copyOf(byDevice.keySet());}
    }
}
