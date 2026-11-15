package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.*;
import com.wormzjl.icheme.science.plant.still.BatchStill;
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
    final MinecraftServer server;
    final MaterialCatalog catalog;
    final FluidThermodynamics model;
    private final FluidPropertyReloadGuard propertyReload;
    private String propertyHold;
    final IChemE.FluidOptions options;
    private final List<FluidPresetCatalog.Preset> presetCache;
    final List<String> componentNames;
    /** The menus' static part, constant for the server (its catalog and model are captured at start): the component names
     * and the boxed molecular weights, built once (code base review R4). */
    private final Map<String,MaterialName> materialNames;
    private final List<Double> molecularWeights;
    private final FluidSavedData data;
    final WorldTopologyLedger topology;
    final MinecraftFluidRuntime runtime;
    /** Island ownership, compiler diagnostics and chunk membership of the registered devices, and the event path. */
    final PhysicalRegistry registry;
    /** The running columns (batch 2026-10-28-column-operation): their ports' plans and bookings, and their cycles. */
    private final ColumnOperation columns;
    /** The shaft trains (batch 2026-11-03-rotating-machinery W3): their parts' queued events, speeds and the islands' slice inputs. */
    final ShaftTrains shafts;
    /** The powered units' electric stores (W5). */
    final ElectricStores electric;
    final FiredHeaters burners;
    final Flares flares;
    /** The chimneys (batch 2026-11-10-chimney). */
    final Chimneys chimneys;
    /** The batch stills (batch 2026-11-09-batch-distiller, W3 to W5). */
    final Stills stills;
    /** The steam ejectors' motive slice inputs (batch 2026-11-10-steam-ejector-flow). */
    private final Ejectors ejectors;
    /** The fluid converters (batch 2026-11-12-fluid-conversion, W2): their ports share the port hooks with the columns. */
    final Converters converters;
    /** The pumps' health (W10b). */
    final MachineHealth health;
    /** The pollution releases (batch 2026-11-15-pollution-system, WP4); null when their hazard standard could not be built. */
    private Releases releases;
    /**
     * Queued topology events apply as one batch at the next tick's hook, or earlier when something reads what they
     * change (a view, a capture, a filter recovery). A run of events inside one tick (a command placing thousands of
     * blocks) applies every this many, far inside the ledger's queue bound, so the queue never waits on a whole command.
     */
    static final int APPLY_BATCH=1024;
    /** How long an undeliverable recovery (offline player, full inventory, unloaded chunk) waits for its next attempt. */
    private static final int RECOVERY_RETRY_TICKS=20;
    final Set<Long> unboundBindings=new HashSet<>();
    /** The chunk binding: what a chunk load binds and queues, and the chunks each recorded structure spans (code base review R8). */
    private final ChunkBinding chunks=new ChunkBinding(this);
    /** Engine-owned presentation: loaded devices and open menus, updated on their island's bucket only. */
    final FluidPresentation<com.wormzjl.icheme.world.inventory.FluidDeviceMenu> presentation;
    /** Complete immutable snapshots from the last bucket; bounded by loaded/open devices, never player replies. */
    private final Map<Long,com.wormzjl.icheme.network.FluidNetwork.MenuData> publishedMenuData=new HashMap<>();
    /** The requested world-space displays, their watchers and what each last published (code base review R8, R9). */
    private final Displays displays=new Displays(this);
    /** What a placed, changed or removed device does to the ledger (code base review R8). */
    private final Placement placement=new Placement(this);
    /** The views and status lines the presentation host reads (code base review R8). */
    private final ViewBuilder views=new ViewBuilder(this);
    /** The far silhouettes' subscriptions (W10): registry facts of the placed structures, read as stored. */
    private final Silhouettes silhouettes=new Silhouettes(this::structuresOf);
    /** The plant's noise levels by chunk, as last published, and the players that ask for them (PROCESS_SOUNDS_REVIEW.md, S2 A). */
    private final Noise.Store noise=new Noise.Store();
    final com.wormzjl.icheme.runtime.instrument.InstrumentService instruments;
    private final com.wormzjl.icheme.runtime.control.ControlFacts controlFacts;
    private com.wormzjl.icheme.runtime.control.ControlService controls;
    public com.wormzjl.icheme.runtime.control.ControlService controls(){owned();if(controls==null)controls=new com.wormzjl.icheme.runtime.control.ControlService(this,controlFacts);return controls;}
    public com.wormzjl.icheme.runtime.instrument.InstrumentService instruments(){owned();return instruments;}
    boolean closed;
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
        model=FluidThermodynamics.forNetwork(catalog,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),options.maximumVelocity(),options.traceCutoffMoleFraction(),options.solids(),options.time());
        data=FluidSavedData.open(server,this::model,catalog);
        propertyReload=new FluidPropertyReloadGuard(catalog,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),model);
        presetCache=FluidPresetCatalog.resolve(catalog);componentNames=model.components();
        {var names=new LinkedHashMap<String,MaterialName>();for(String id:componentNames)names.put(id,catalog.name(id));materialNames=Map.copyOf(names);}
        molecularWeights=Arrays.stream(model.molecularWeights()).boxed().toList();
        var savedTopology=data.world().orElseGet(()->WorldTopologyLedger.Snapshot.empty(catalog));savedTopology.basis().requireCurrent(catalog);
        FluidPropertyReloadGuard.validateSolids(catalog.solids(),data.checkpoint(),savedTopology);observedSolidData=catalog;
        topology=new WorldTopologyLedger(savedTopology);
        topology.builds().structures().forEach((origin,structure)->chunks.indexStructure(origin,structure,true));
        presentation=new FluidPresentation<>(new PresentationHost(),topology.onlineTick());
        var settings=new IslandCoordinator.Settings(options.wallBudgetNanos(),options.wallBudgetNanos()*3/4,64,options.adaptiveCadence(),options.initialCadenceTicks(),options.certificates()).withTime(options.time());
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
            /** The job runs on its feed island's model (V4), taken here on the engine thread; a feed port in no island yet is asked again at the next decision points (R-ST4). */
            @Override public boolean submit(long unit,com.wormzjl.icheme.science.plant.column.ColumnSolve.Job job,java.util.function.Consumer<com.wormzjl.icheme.science.plant.column.ColumnSolve.Outcome> done) {
                var u=topology.column(unit);var feed=u.station(com.wormzjl.icheme.science.plant.column.Port.FEED,0);
                Long island=feed==null||feed.device()==0?null:registry.owner(feed.device());
                if(island==null)return false;
                var dimension=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(u.master().dimension()));
                var request=new com.wormzjl.icheme.runtime.ProcessSolveServices.ColumnUnitRequest(com.wormzjl.icheme.runtime.ProcessSolveServices.nextRequestId(),new com.wormzjl.icheme.runtime.ProcessSolveServices.ColumnUnitTarget(dimension,unit),0,done);
                return com.wormzjl.icheme.runtime.ProcessSolveServices.submitColumnUnit(server,request,new com.wormzjl.icheme.runtime.ProcessSolveJobs.ColumnUnitCommand(job,runtime.coordinator().model(island),IChemE.columnV3LiquidSupplyScreenRatio())).accepted();
            }
        });
        instruments=new com.wormzjl.icheme.runtime.instrument.InstrumentService(server,this,accessories);
        runtime.coordinator().controls(g->controls().apply(g));
        instruments.reconcileLegacyValves();
        shafts=new ShaftTrains(topology,registry::owner,runtime.coordinator());runtime.coordinator().shafts(shafts);shafts.host(new ShaftHost());
        electric=new ElectricStores(topology,registry::owner,runtime.coordinator(),()->new ElectricStores.Settings(IChemE.joulesPerFE(),IChemE.machinesNeedPower()));shafts.electric(electric);
        electric.supply(id->com.wormzjl.icheme.runtime.power.PowerNetwork.of(server).supplied(shafts.unit(id)));
        // A running column's members draw from their own stores and trains (W5 of batch 2026-11-10-column-failure-modes).
        columns.power(new ColumnPower(topology,electric,shafts));
        // The Create coupling (W6b): its stress rate; a world opened without Create lost its couplings' blocks, so their parts leave.
        shafts.stressPerFE(IChemE::stressPerFE);if(!net.neoforged.fml.ModList.get().isLoaded("create"))shafts.forget(com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.CREATE_COUPLING);
        // The fired heaters' burners (batch 2026-11-09-furnace-fuel-power): the network's heating values in its conserved order.
        burners=new FiredHeaters(topology,runtime.coordinator(),electric,heatingValues(catalog,model),model.molecularWeights(),formulas(catalog,model));shafts.burners(burners);electric.moved(burners::powerMoved);
        flares=new Flares(topology,heatingValues(catalog,model),model.molecularWeights(),formulas(catalog,model));shafts.flares(flares);
        chimneys=new Chimneys(topology,model.molecularWeights());shafts.chimneys(chimneys);
        // The pollution releases (batch 2026-11-15-pollution-system, WP4): the hazard standard in the network's conserved order;
        // each island's transfers on its own list are mapped onto it by name. A standard that cannot be built leaves them off.
        try {
            var hazards=hazardColumns(catalog,model);var atoms=formulas(catalog,model);
            releases=new Releases(topology,model.components(),new com.wormzjl.icheme.science.pollution.HazardStandard(hazards[0],hazards[1],hazards[2],hazards[3],hazards[4],hazards[5]),
                    atoms,Releases.so2Reference(hazards[1],atoms),flares,id->runtime.coordinator().model(id).components(),runtime.coordinator()::time,this::releasePollution);
            shafts.releases(releases);
        } catch(RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=RELEASES_OFF detail={}",String.valueOf(failure));
        }
        // The prime movers (batch 2026-11-10-prime-movers): their fuel's heat; a gas turbine's coil at its structure's FEEDWATER cell.
        shafts.primeMovers(new PrimeMovers(heatingValues(catalog,model),model.molecularWeights()),this::coilOf);
        // The batch stills (batch 2026-11-09-batch-distiller): their pots, heads and outlets share the port hooks with the columns.
        stills=new Stills(topology,registry::owner,runtime.coordinator(),runtime.coordinator()::model,electric,model.components(),model.molecularWeights(),heatingValues(catalog,model));
        ejectors=new Ejectors(runtime.coordinator()::model);
        converters=new Converters(topology,runtime.coordinator()::model,model.time(),topology::onlineTick,
                id->{var island=registry.owner(id);if(island!=null)runtime.coordinator().portsMoved(island);},com.wormzjl.icheme.science.fluid.conversion.ConversionRuntime::active);
        // A tag rule names the fluid registry's tags (W3), read without the hash.
        converters.tags(FluidWorldAuthority::fluidTags);
        stills.refresh();runtime.coordinator().ports(IslandCoordinator.Ports.both(columns,IslandCoordinator.Ports.both(stills,IslandCoordinator.Ports.both(ejectors,converters))));
        stills.solves((pot,island,job,done)->{
            var at=registrations().get(pot);if(at==null)return false;
            var dimension=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(at.device().position().dimension()));
            var request=new com.wormzjl.icheme.runtime.ProcessSolveServices.StillRequest(com.wormzjl.icheme.runtime.ProcessSolveServices.nextRequestId(),new com.wormzjl.icheme.runtime.ProcessSolveServices.StillTarget(dimension,pot),0,done);
            return com.wormzjl.icheme.runtime.ProcessSolveServices.submitStill(server,request,new com.wormzjl.icheme.runtime.ProcessSolveJobs.StillCommand(job,runtime.coordinator().model(island))).accepted();
        });
        stills.fuel(this::takeStillFuel);
        // The data network's firing and reflux setpoints (owner decision O1): the control service's last cadence.
        stills.plans(new Stills.Plans(){
            public double firing(long pot){return controls==null?Double.NaN:controls.stillFiring(pot);}
            public double reflux(long pot){return controls==null?Double.NaN:controls.stillReflux(pot);}
        });
        // A control loop's planned column feed (W6.3): the control service's last cadence; the cycle takes it below the set rate.
        columns.feedPlans(unit->controls==null?Double.NaN:controls.columnFeed(unit));
        health=new MachineHealth(topology,registry::owner,runtime.coordinator(),id->MachineHealth.pump(topology.active().get(id)),id->{var r=topology.active().get(id);return r==null?null:r.device().control();},IChemE::wearSettings);shafts.health(health);
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
        world.chunks.holdWithoutLevel();
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
    void owned(){if(!server.isSameThread())throw new IllegalStateException("Fluid authority requires the logical server thread");}
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
    static ResourceKey<Level> dimension(String name){return ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(name));}
    public List<FluidPresetCatalog.Preset> presets(){owned();return presetCache;}
    public List<String> components(){owned();return componentNames;}
    /** The world's network property model; its {@code domain()} is where a state may be evaluated. */
    public FluidThermodynamics model(){owned();return model;}
    /** The server's time factor ({@code fluid.timeScale}), captured at server start: the world model's. */
    public com.wormzjl.icheme.science.fluid.SimulationTime time(){return model.time();}
    public Map<String,MaterialName> materialNames(){owned();return materialNames;}
    /** The world model's molecular weights, boxed for a menu's static part (code base review R4). */
    public List<Double> molecularWeights(){owned();return molecularWeights;}
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
        owned();var old=topology.structure(origin);if(old!=null)chunks.indexStructure(origin,old,false);
        topology.structure(origin,structure);if(structure!=null)chunks.indexStructure(origin,structure,true);data.setDirty();
        // The far silhouettes follow the registry (W10): its subscribers see the change at the next presentation bucket.
        if(!Objects.equals(old,structure))silhouettes.changed(origin.dimension());
    }
    /** The registry's structures of {@code dimension}, as stored (W10): each origin once, its shape and paint. */
    private void structuresOf(String dimension,java.util.function.Consumer<Silhouettes.Placement> into) {
        var seen=new HashSet<PhysicalFluidTopology.Position>();
        for(var origins:chunks.structureChunks.getOrDefault(dimension,Map.of()).values())for(var origin:origins) {
            if(!seen.add(origin))continue;
            var s=topology.structure(origin);if(s!=null)into.accept(new Silhouettes.Placement(new BlockPos(origin.x(),origin.y(),origin.z()),s.shape(),s.paint()));
        }
    }
    /**
     * A client's far silhouette subscription (W10): within {@code radius} blocks ({@link Silhouettes#MAXIMUM_RADIUS} at most) of
     * where it stands ({@code on}), or none. A subscription is answered at once with the registry's records, as stored, at
     * most once per {@link Silhouettes#REQUEST_TICKS} per player; a request inside that window is answered when it ends.
     */
    public void watchSilhouettes(net.minecraft.server.level.ServerPlayer player,int radius,boolean on) {
        owned();if(closed)return;
        var batch=silhouettes.watch(player.getUUID(),stand(player),radius,on,topology.onlineTick());
        if(batch!=null)com.wormzjl.icheme.network.EquipmentNetwork.sendSilhouettes(player,batch);
    }
    private static Silhouettes.Stand stand(net.minecraft.server.level.ServerPlayer player) {
        return player.hasDisconnected()?null:new Silhouettes.Stand(player.serverLevel().dimension().location().toString(),player.getX(),player.getZ());
    }
    /** The presentation cadence's silhouette differences (W10): to each subscriber whose registry changed or who moved. */
    private void publishSilhouettes() {
        if(silhouettes.idle())return;
        silhouettes.publish(uuid->{var p=server.getPlayerList().getPlayer(uuid);return p==null?null:stand(p);},topology.onlineTick())
                .forEach((uuid,batch)->{var p=server.getPlayerList().getPlayer(uuid);if(p!=null)com.wormzjl.icheme.network.EquipmentNetwork.sendSilhouettes(p,batch);});
    }
    /**
     * A client's noise watch (batch 2026-11-10-custom-shader-system, PROCESS_SOUNDS_REVIEW.md section 2, S2 A): about once a
     * second it asks for the sources within {@link Noise#RANGE} blocks, and is answered at once with the
     * {@link Noise#MAXIMUM_SOURCES} loudest at where it stands, read from the levels last published (no solver run, no
     * process state created). An answer is at most once per {@link Noise#ANSWER_TICKS}; a player that stops asking for
     * {@link Noise#WATCH_TICKS} is no longer a watcher. Before the first publication it is answered with none.
     */
    public void watchNoise(net.minecraft.server.level.ServerPlayer player) {
        owned();if(closed||player.hasDisconnected()||!noise.ask(player.getUUID(),topology.onlineTick()))return;
        com.wormzjl.icheme.network.FluidNetwork.sendNoise(player,new com.wormzjl.icheme.network.FluidNetwork.NoiseSources(
                noise.near(player.serverLevel().dimension().location().toString(),player.getX(),player.getEyeY(),player.getZ())));
    }
    /**
     * The presentation cadence's noise levels (S2 A): only while someone asks. The islands with a device within
     * {@link Noise#RANGE} blocks of a watcher are read once each (as stored), and an island's levels are computed again only
     * when it committed a new interval since; the gearboxes near a watcher are read from their shaft trains. A knock is a
     * one-shot: it stays one cadence.
     */
    private void publishNoise() {
        long now=topology.onlineTick();var watchers=noise.watchers(now);
        if(watchers.isEmpty()){if(!noise.empty())noise.clear();return;}
        var near=new TreeSet<Long>();var stands=new ArrayList<net.minecraft.server.level.ServerPlayer>();
        int reach=(int)Math.ceil(Noise.RANGE/16);
        for(var uuid:watchers) {
            var p=server.getPlayerList().getPlayer(uuid);if(p==null||p.hasDisconnected()){noise.forget(uuid);continue;}
            stands.add(p);String dimension=p.serverLevel().dimension().location().toString();int cx=p.getBlockX()>>4,cz=p.getBlockZ()>>4;
            for(int i=-reach;i<=reach;i++)for(int j=-reach;j<=reach;j++)for(long id:registry.chunk(dimension,net.minecraft.world.level.ChunkPos.asLong(cx+i,cz+j))){Long owner=registry.owner(id);if(owner!=null)near.add(owner);}
        }
        for(long island:near) {
            // An island's levels stand until it commits a new interval (a knock stands one cadence: Store.current).
            var snapshot=storedIsland(island);long tick=snapshot.clock().committedTick();
            if(noise.current(island,tick))continue;
            var members=registry.members(island);var first=members.isEmpty()?null:registrations().get(members.get(0));
            if(first!=null)noise.put(island,first.device().position().dimension(),tick,islandNoise(island,snapshot));
        }
        noise.retain(near);
        // The gearboxes near a watcher, from their trains' last step, by dimension.
        var gears=new HashMap<String,List<Noise.Source>>();
        for(var unit:shafts.units()) {
            if(unit.kind()!=com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.GEARBOX)continue;
            var p=unit.position();var pos=new BlockPos(p.x(),p.y(),p.z());
            boolean heard=false;for(var s:stands)heard|=s.serverLevel().dimension().location().toString().equals(p.dimension())&&s.position().distanceToSqr(pos.getCenter())<=Noise.RANGE*Noise.RANGE;
            var v=heard?shafts.view(unit.id()):null;if(v==null)continue;
            double share=unit.designSpeed()>0?v.speed()/unit.designSpeed():v.share();
            Noise.add(gears.computeIfAbsent(p.dimension(),d->new ArrayList<>()),pos,Noise.Kind.GEAR,Noise.gear(unit.ratedPower(),share),v.speed()/GEAR_LOOP_RPM);
        }
        noise.machines(gears);
    }
    /** The gearbox loop's shaft speed, rpm: 30 teeth at 1,500 rpm mesh at its 750 Hz (tools/process-sounds). */
    static final double GEAR_LOOP_RPM=1500;
    /**
     * Island {@code island}'s noise sources from what it last accepted, {@code snapshot} as stored (a read; the review's
     * table 1): its valves passing flow, by their drop, by phase; its open vessels' vents; its air coolers' fans; its
     * towers' fans and falling water; its fired heaters' burners and draught fans; its kettles' boiling pools; and the knocks
     * of its liquid lines stopped since its last publication.
     */
    private List<Noise.Source> islandNoise(long island,IslandCoordinator.Snapshot snapshot) {
        var out=new ArrayList<Noise.Source>();var result=snapshot.lastResult().orElse(null);if(result==null)return out;
        var graph=snapshot.graph();var rows=new HashMap<Long,PassiveStepSolver.PipeObservation>();for(var o:result.observations())rows.put(o.pipe(),o);
        var speeds=new HashMap<Long,Double>();var densities=new HashMap<Long,Double>();
        for(var pipe:graph.pipes()) {
            var row=rows.get(pipe.id());if(row==null||!(row.density()>0))continue;
            boolean gas=row.density()<Noise.GAS_DENSITY;
            // A valve sounds only while it passes flow, its level by its drop alone; its loop by its stream's phase, a liquid's by
            // its cavitation (owner, 2026-11-10: plain, cavitating or flashing by σ, option A).
            if(pipe.control() instanceof FlowControl.PressureValve||pipe.control() instanceof FlowControl.ControlValve) {
                var r=registrations().get(graph.reservoirs().get(pipe.first()).id());
                if(r!=null&&row.flowing()&&row.massFlow()!=0&&Double.isFinite(row.valveDrop()))Noise.add(out,cell(r),gas?Noise.Kind.VALVE_GAS:switch(row.cavitation()){case CAVITATING->Noise.Kind.VALVE_CAVITATING;case FLASHING->Noise.Kind.VALVE_FLASHING;default->Noise.Kind.VALVE_LIQUID;},Noise.valve(row.valveDrop()),1);
            }
            // A steam ejector's motive jet, by the steam its nozzle passes (batch 2026-11-10-steam-ejector-flow), at its suction's cell.
            if(pipe.control() instanceof FlowControl.Ejector) {
                long id=Ejectors.physical(pipe.id());var r=registrations().get(id);var jet=rows.get(PhysicalFluidTopology.motiveIdentity(id));
                if(r!=null&&jet!=null&&jet.flowing())Noise.add(out,cell(r),Noise.Kind.EJECTOR,Noise.ejector(jet.massFlow(),jet.density(),jet.firstPressure(),jet.secondPressure()),1);
            }
            // A liquid line's speed, m/s, against which its stop at the next publication knocks.
            if(!gas&&!pipe.sections().isEmpty()) {
                var g=pipe.sections().get(0);double area=Math.max(1,g.channels())*Math.PI*g.diameter()*g.diameter()/4;
                if(area>0){speeds.put(pipe.id(),row.flowing()?row.massFlow()/(row.density()*area):0);densities.put(pipe.id(),row.density());}
            }
        }
        out.addAll(noise.knocks(island,speeds,densities,pipe->pipeCell(island,pipe)));
        // Open vessels' vents: the gas their VENT boundaries booked over the interval, a free jet through their roof.
        if(result.advancedSeconds()>0) {
            var weights=model.molecularWeights();var mass=new HashMap<Long,Double>();var moles=new HashMap<Long,Double>();
            for(var b:result.boundaries()) {
                if(b.kind()!=ConservativeTransport.BoundaryTransfer.Kind.VENT)continue;
                var n=b.moles();double m=0,mol=0;for(int c=0;c<Math.min(n.length,weights.length);c++){m+=Math.abs(n[c])*weights[c];mol+=Math.abs(n[c]);}
                mass.merge(b.nodeId(),m,Double::sum);moles.merge(b.nodeId(),mol,Double::sum);
            }
            for(var node:graph.reservoirs()) {
                Double m=mass.get(node.id());var r=m==null?null:registrations().get(node.id());var inventory=node.inventory();
                if(r==null||inventory==null||!(inventory.levelHeight()>0)||!(m>0))continue;
                double density=Noise.ventDensity(m/moles.get(node.id()),node.state().temperature());
                Noise.add(out,cell(r).above((int)Math.ceil(inventory.levelHeight())),Noise.Kind.VENT,Noise.vent(m/result.advancedSeconds(),density,inventory.volume()/inventory.levelHeight()),.8);
            }
        }
        // Chimneys' tips (batch 2026-11-10-chimney, plan 3.7): the gas they released, a free jet through their mouth.
        for(var node:graph.reservoirs()) {
            if(node.kind()!=PassiveNetwork.NodeKind.VOID)continue;
            var r=registrations().get(node.id());if(r==null||!r.device().chimney())continue;
            var c=chimneys.view(node.id());double d=r.device().geometry().diameter(),area=Math.PI*d*d/4;
            if(c==null||!(c.exitVelocity()>0)||!(c.massFlow()>0))continue;
            Noise.add(out,cell(r).above(1),Noise.Kind.VENT,Noise.vent(c.massFlow(),c.massFlow()/(c.exitVelocity()*area),area),.8);
        }
        var heats=new HashMap<Long,Double>();
        if(result.advancedSeconds()>0)for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT)heats.merge(b.nodeId(),Math.abs(b.totalEnergyJoule())/result.advancedSeconds(),Double::sum);
        for(long id:registry.members(island)) {
            var r=registrations().get(id);if(r==null)continue;var heat=r.device().heat();var pos=cell(r);
            // An air cooler's fans at the share its heat law last ran them (the fan law).
            if(heat!=null&&heat.fans()&&heat.fanCount()>0)for(var pipe:graph.pipes())
                if(pipe.id()==PhysicalFluidTopology.sideIdentity(id)&&pipe.heat() instanceof AirCooled cooled)Noise.add(out,pos,Noise.Kind.FAN,Noise.fans(heat.fanCount(),Noise.COOLER_FAN,cooled.fans()),cooled.fans());
            // A tower: an induced tower's fans at its fill's speed, at the fan deck; falling water at the basin, either draught.
            var tower=r.spec().tower();var rates=tower==null?null:result.towers().get(id);
            if(rates!=null) {
                if(tower.draft()==CoolingTower.Draft.INDUCED)Noise.add(out,pos.above((int)Math.ceil(tower.height())),Noise.Kind.FAN,Noise.fans(tower.cells(),Noise.TOWER_FAN,rates.fill().speed()),rates.fill().speed());
                Noise.add(out,pos,Noise.Kind.WATER_FALL,Noise.water(rates.inletFlow()),1);
            }
            // A fired heater's burners and draught fan, at its radiant coil, by the heat they release.
            if(radiantCoil(r)) {
                long b=burners.burnersOf(id);var flue=b==0?null:burners.flue(b);double design=b==0?0:FiredHeaters.designFired(registrations().get(b));
                if(flue!=null&&flue.fired()>0) {
                    Noise.add(out,pos,Noise.Kind.BURNER,Noise.burner(flue.fired()),1);
                    if(design>0)Noise.add(out,pos,Noise.Kind.FAN,Noise.draftFan(Math.clamp(flue.fired()/design,0,1)),1);
                }
            }
            // A kettle's pool boiling by the heat its tubes give it, muffled by its shell.
            if(heat!=null&&heat.pool()) {
                double q=Math.max(heats.getOrDefault(PhysicalFluidTopology.sideIdentity(id),0.0),heat.partner()==0?0:heats.getOrDefault(PhysicalFluidTopology.sideIdentity(heat.partner()),0.0));
                Noise.add(out,pos,Noise.Kind.KETTLE,Noise.kettle(q),.8);
            }
        }
        return out;
    }
    /** A block of island {@code island}'s line {@code pipe}: the pipe block nearest its middle, or null. */
    private BlockPos pipeCell(long island,long pipe) {
        BlockPos best=null;double gap=Double.POSITIVE_INFINITY;
        for(long id:registry.members(island))for(var view:registry.pipeViews(id))if(view.pipeId()==pipe) {
            double d=Double.isFinite(view.tap())?Math.abs(view.tap()-.5):1;var r=registrations().get(id);
            if(r!=null&&d<gap){gap=d;best=cell(r);}
        }
        return best;
    }

    /**
     * The faces the device registered at {@code position} draws joined (batch 2026-11-09-drawn-connections): its
     * as-built joins on the projected registry ({@link PhysicalFluidTopology#builtFaces}), so accepted input shows at once
     * and a block draws what the network joins. -1 where no device without ports stands. Reads no block and loads no
     * chunk: a neighbour in a chunk that is not loaded is known by its registration.
     */
    public int drawnFaces(PhysicalFluidTopology.Position position){owned();return placement.drawnFaces(position);}
    /**
     * The faces a block of {@code kind} placed at {@code position} facing {@code facing} will draw joined once registered:
     * a hand-placed block, every face a joint and no fitting, as {@link #place} registers it.
     */
    public int placedFaces(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing){owned();return placement.placedFaces(position,kind,facing);}
    /** Applied events that did nothing for the player who asked (a recovery that found no cake or a full inventory), by event, for their reply. Bounded. */
    private final LinkedHashMap<UUID,String> refusedEvents=new LinkedHashMap<>();
    private void refuseEvent(UUID event,String reason){refusedEvents.put(event,reason);if(refusedEvents.size()>WorldTopologyLedger.MAXIMUM_EVENTS)refusedEvents.remove(refusedEvents.keySet().iterator().next());}
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing) {
        return placement.place(position,kind,facing,List.of());
    }
    /** Registry reconstruction reads installed cutoff state from the placed block. */
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff cutoff) {
        return placement.place(position,kind,facing,List.of(),cutoff);
    }
    /** Rebuild installed pressure controls directly from static block state, without an intermediate passive pipe. */
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff cutoff,boolean pressureValve) {
        return placement.place(position,kind,facing,List.of(),cutoff,pressureValve,false,TopologyCompiler.Kind.PIPE,PhysicalFluidTopology.ALL_FACES);
    }
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff cutoff,boolean pressureValve,boolean filter){return placement.place(position,kind,facing,List.of(),cutoff,pressureValve,filter,TopologyCompiler.Kind.PIPE,PhysicalFluidTopology.ALL_FACES);}
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff cutoff,boolean pressureValve,boolean filter,TopologyCompiler.Kind inlineMover){return placement.place(position,kind,facing,List.of(),cutoff,pressureValve,filter,inlineMover,PhysicalFluidTopology.ALL_FACES);}
    /** A placed block registered again from what it holds (a chunk load after the D4 reset): a laid pipe keeps its joints. */
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,PhysicalFluidTopology.Cutoff cutoff,boolean pressureValve,boolean filter,TopologyCompiler.Kind inlineMover,int joints){return placement.place(position,kind,facing,List.of(),cutoff,pressureValve,filter,inlineMover,joints);}
    /** A cell of a laid line: its position (a rack lane's too), its facing (along its run), its joints, and a lane's width in positions and face points (0: a pipe block). */
    public record Laid(PhysicalFluidTopology.Position position,PhysicalFluidTopology.Direction facing,int joints,int slots,long points){}
    /**
     * Registers a laid line as one ledger event (batch 2026-10-26-pipe-laying-system, plan 4.4): a passive pipe of bore
     * {@code diameter} per cell, with its joints, and the new joints of the laid pipes it starts or ends at
     * ({@code hosts}, by identity), each new pipe painted {@code primary} and {@code secondary}. Returns the new pipes'
     * registrations in the order of {@code cells}.
     */
    public List<WorldTopologyLedger.Registration> lay(List<Laid> cells,double diameter,Map<Long,Integer> hosts,int primary,int secondary){owned();return placement.lay(cells,diameter,hosts,primary,secondary);}
    /** A rack lane registered again from its rack's copy (a chunk load after the D4 reset): its joints, width, face points and bore. */
    public WorldTopologyLedger.Registration placeLane(PhysicalFluidTopology.Position position,PhysicalFluidTopology.Direction facing,int joints,int slots,long points,double diameter){owned();return placement.placeLane(position,facing,joints,slots,points,diameter);}
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
        var device=PhysicalFluidTopology.Device.builder(id,node,TopologyCompiler.Kind.VOID,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,tip,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.flare()).ports(ports).build();
        var record=new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,com.wormzjl.icheme.science.fluid.network.Flare.AMBIENT,101325,FluidDeviceSpec.nitrogen(catalog).composition()),0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /**
     * A chimney's tip (batch 2026-11-10-chimney): a passive void at the top cell of its stack {@code node}, with its one bulk
     * port at the gas inlet; its geometry is the stack from the inlet up, {@code shape}'s exit diameter across with its entry
     * and exit losses ({@link com.wormzjl.icheme.science.fluid.network.Chimney#STACK_LOSS}), which the run into it carries.
     * Its facing names its form ({@link Chimneys#form}): UP a steel stack, DOWN a brick chimney.
     */
    public WorldTopologyLedger.Registration placeChimney(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,com.wormzjl.icheme.science.equipment.EquipmentShape shape) {
        owned();long id=topology.nextIdentity();
        // From the inlet's centre line, half a block up its port's cell, to the top of the tip's cell.
        double rise=Math.max(1,node.y()-ports.getFirst().cell().y()+.5);
        var facing=shape.kind()==com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.BRICK_CHIMNEY?PhysicalFluidTopology.Direction.DOWN:PhysicalFluidTopology.Direction.UP;
        var device=PhysicalFluidTopology.Device.builder(id,node,TopologyCompiler.Kind.VOID,facing,
                new PipeResistance.Geometry(rise,shape.diameter(),PipeResistance.DEFAULT_ROUGHNESS_METRES,com.wormzjl.icheme.science.fluid.network.Chimney.STACK_LOSS),new FlowControl.Passive()).ports(ports).build();
        var record=new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.atmosphere(node.y()),FluidDeviceSpec.nitrogen(catalog).composition()),0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /**
     * A prime mover's registrations (batch 2026-11-10-prime-movers) as one ledger event: its fuel void at {@code fuel}, a void
     * at its combustor's {@code pressure} (Pa) behind its FUEL port, its fuel valve its control ({@link FlowControl.ValveSource#ENGINE});
     * then a gas turbine's steam coil, a heat side of unit {@code PRIME_MOVER} ({@code coil}, null for none). Returns them in that order.
     */
    public List<WorldTopologyLedger.Registration> placePrimeMover(PhysicalFluidTopology.Position fuel,List<PhysicalFluidTopology.Port> ports,double pressure,@org.jetbrains.annotations.Nullable UnitSide coil) {
        owned();long first=topology.nextIdentity();var edits=new ArrayList<WorldTopologyLedger.Edit>();var placed=new ArrayList<WorldTopologyLedger.Registration>();
        var device=PhysicalFluidTopology.Device.builder(first,fuel,TopologyCompiler.Kind.VOID,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.engine()).ports(ports).build();
        var record=new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,pressure,FluidDeviceSpec.nitrogen(catalog).composition()),0);
        placed.add(record);edits.add(new WorldTopologyLedger.Edit(first,record));
        if(coil!=null) {
            var side=PhysicalFluidTopology.Device.builder(first+1,coil.node(),TopologyCompiler.Kind.HEAT_SIDE,PhysicalFluidTopology.Direction.NORTH,coil.passage(),new FlowControl.Passive())
                    .ports(coil.ports()).heat(coil.heat()).build();
            var r=new WorldTopologyLedger.Registration(side,FluidDeviceSpec.nitrogen(catalog),0);placed.add(r);edits.add(new WorldTopologyLedger.Edit(first+1,r));
        }
        submit(edits,Math.addExact(first,placed.size()));return placed;
    }
    /** Shaft unit {@code u}'s steam coil registration: a gas turbine's at its structure's FEEDWATER cell; 0 for none. */
    private long coilOf(ShaftUnit u) {
        var held=topology.structure(u.position());if(held==null||!held.shape().kind().primeMover())return 0;
        var cells=com.wormzjl.icheme.science.equipment.ShapeRasterizer.fluidCells(held.shape());if(cells.size()<2)return 0;
        var c=cells.get(1);var p=u.position();
        var r=registry.at(new PhysicalFluidTopology.Position(p.dimension(),p.x()+c.x(),p.y()+c.y(),p.z()+c.z())).orElse(null);
        return r!=null&&r.device().heat()!=null&&r.device().heat().unit()==com.wormzjl.icheme.science.equipment.HeatSides.Unit.PRIME_MOVER?r.device().id():0;
    }
    private WorldTopologyLedger.Registration burnerRecord(long id,UnitBurners burners,long radiant) {
        var device=PhysicalFluidTopology.Device.builder(id,burners.node(),TopologyCompiler.Kind.VOID,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.burner()).ports(burners.ports()).heat(burners.heat().withPartner(radiant)).build();
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
            var device=PhysicalFluidTopology.Device.builder(first+i,side.node(),TopologyCompiler.Kind.HEAT_SIDE,PhysicalFluidTopology.Direction.NORTH,side.passage(),new FlowControl.Passive())
                    .ports(side.ports()).heat(side.heat().withPartner(partner)).build();
            var record=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(catalog),0);placed.add(record);edits.add(new WorldTopologyLedger.Edit(first+i,record));
        }
        if(vessel!=null) {
            long id=first+1;
            var device=PhysicalFluidTopology.Device.builder(id,vessel.node(),TopologyCompiler.Kind.RESERVOIR,PhysicalFluidTopology.Direction.NORTH,
                    new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive()).ports(vessel.ports()).heat(vessel.heat().withPartner(first)).build();
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
    public Chimneys chimneys(){owned();return chimneys;}
    /** The batch stills (batch 2026-11-09-batch-distiller). */
    public Stills stills(){owned();return stills;}
    /** The fluid converters (W3 registers them from the equipment structure). */
    public Converters converters(){owned();return converters;}
    /**
     * Queues converter {@code id}'s settings (W4: a page edit, a DCS command), clamped to its rated flow (its vessel's
     * volume through {@code EquipmentShape.converterRate}) and its deadband's bounds; applied at the engine's next tick, its
     * acknowledgement with the next bucket. Returns null, or why not.
     */
    public @org.jetbrains.annotations.Nullable String converterSettings(long id,Converters.Settings settings) {
        owned();if(closed)return "the fluid world is closed";var r=registrations().get(id);
        if(r==null||converters.unit(id)==null)return "no converter";
        converters.queue(id,settings.clamped(com.wormzjl.icheme.science.equipment.EquipmentShape.converterRate(r.spec().volume())));return null;
    }
    /**
     * Converter {@code id}'s process vessel's fill fraction from its last published view ({@link Converters#fill}), NaN
     * while it has none. A read of cached data: no solve.
     */
    public double converterFill(long id) {
        owned();var u=converters.unit(id);var r=registrations().get(id);if(u==null||r==null)return Double.NaN;
        return converterFill(u,r,view(id));
    }
    /** {@link #converterFill(long)} on {@code view}, the vessel's published view. */
    private static double converterFill(ConverterUnit u,WorldTopologyLedger.Registration r,FluidView view) {
        var s=view.state();if(s==null||view.committedTick()<=0)return Double.NaN;
        var v=s.phaseVolumes();double gas=0;for(double n:s.phaseMoles()[2])gas+=n;
        return Converters.fill(u.closed(),s.capacity(),v[0]+v[1]+s.solids().volume(),gas,s.temperature(),r.spec().blanket());
    }
    /**
     * Converter {@code id}'s page (W5), a read of its facts, its queued settings, the engine's state and {@code view}, its
     * vessel's published view: its rule's ranges beside what the last plan drew. Null for no converter.
     */
    FluidView.Converter converterView(long id,FluidView view) {
        var u=converters.unit(id);var r=registrations().get(id);var next=converters.next(id);if(u==null||r==null||next==null)return null;
        double fill=converterFill(u,r,view);var s=view.state();
        double litres=0,kmol=0,kg=0;
        if(s!=null){var v=s.phaseVolumes();litres=Math.max(0,(v[0]+v[1])*1000);for(double n:s.phaseMoles()[2])kmol+=n/1000;kmol=Math.max(0,kmol);kg=Math.max(0,s.mass());}
        var thresholds=new ArrayList<Double>();for(double t:next.thresholds())thresholds.add(Double.isNaN(t)?null:t);
        var status=converters.status(id);var alarms=Converters.alarms(status,u.thresholds(),fill,u.forgeFill()).stream().map(a->a.text).toList();
        var rule=converters.matched(id);var ranges=new ArrayList<FluidView.Range>();boolean reverse=false;
        if(rule!=null&&rule.toForge()!=null&&(converters.direction(id)!=Converters.Direction.TO_ICHEME||rule.toIChemE()==null)) {
            reverse=true;var f=rule.toForge();var drawn=converters.drawn(id);var x=drawn==null?null:drawn.fractions(f.basis());double named=0;
            for(var e:f.components().entrySet()){Double value=x==null?null:x.getOrDefault(e.getKey(),0.);if(value!=null)named+=value;ranges.add(new FluidView.Range(e.getKey(),f.basis().name(),bound(e.getValue().min()),bound(e.getValue().max()),value));}
            ranges.add(new FluidView.Range("others","OTHERS",null,f.othersMax(),x==null?null:Math.max(0,1-named)));
            ranges.add(new FluidView.Range("temperature","TEMPERATURE",bound(f.temperature().min()),bound(f.temperature().max()),drawn==null?null:drawn.temperature()));
            ranges.add(new FluidView.Range("pressure","PRESSURE",bound(f.pressure().min()),bound(f.pressure().max()),drawn==null?null:drawn.pressure()));
        } else if(rule!=null&&rule.toIChemE()!=null) {
            var g=rule.toIChemE();
            for(var e:g.composition().entrySet())ranges.add(new FluidView.Range(e.getKey(),"MOLE",e.getValue(),e.getValue(),e.getValue()));
            ranges.add(new FluidView.Range("temperature","TEMPERATURE",g.temperature(),g.temperature(),g.temperature()));
            ranges.add(new FluidView.Range("pressure","PRESSURE",g.pressure(),g.pressure(),g.pressure()));
        }
        if(ranges.size()>FluidView.Converter.MAXIMUM_RANGES)ranges.subList(FluidView.Converter.MAXIMUM_RANGES,ranges.size()).clear();
        double booked=converters.rate(id).litres();
        return new FluidView.Converter(u.closed(),next.mode().name(),next.rate(),com.wormzjl.icheme.science.equipment.EquipmentShape.converterRate(r.spec().volume()),next.deadband(),thresholds,converters.queued(id),
                status==null?"":status.name(),converters.direction(id).name(),Double.isFinite(booked)?booked:0,alarms,u.fluid(),u.stored(),u.capacity(),u.forgeFill(),Double.isNaN(fill)?null:fill,litres,kmol,kg,
                rule==null?"":rule.id(),reverse,ranges);
    }
    /** A rule's bound, null where it is open. */
    private static Double bound(double value){return Double.isFinite(value)?value:null;}
    /** Converter {@code id}'s active alarms (W4): its status's and its level thresholds', on its published fill. */
    public java.util.Set<EquipmentAlarm> converterAlarms(long id) {
        owned();var u=converters.unit(id);if(u==null)return java.util.Set.of();
        return Converters.alarms(converters.status(id),u.thresholds(),converterFill(id),u.forgeFill());
    }
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
     * A fluid converter's devices (batch 2026-11-12-fluid-conversion, W3): its process vessel at {@code node} with its nozzles
     * {@code ports}, its volume, level height, opening and geometry, and the two inside contacts its hidden ports meet it
     * through (vessel port cells and faces, each of its phase class): {@code drain} (reverse) and {@code fill} (forward).
     * {@code rate} is its rated flow (L per process second), {@code capacity} its Forge tank's (mB).
     */
    public record ConverterDevices(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,double volume,double levelHeight,boolean closed,
                                   com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry,PhysicalFluidTopology.Port drain,PhysicalFluidTopology.Port fill,
                                   double rate,long capacity,double blanket,double relief,PhysicalFluidTopology.Direction reliefFace) {
        /** The vessel's ports: its nozzles, then its drain's and its fill's inside contacts. */
        public List<PhysicalFluidTopology.Port> all(){var all=new ArrayList<>(ports);all.add(drain);all.add(fill);return List.copyOf(all);}
    }
    /** The pressure behind a converter's drain void, Pa: under the vessel it draws from, so its valve regulates the draw. */
    public static final double CONVERTER_DRAIN_PRESSURE=80000;
    /** The pressure of a liquid and of a gas converter's fill generator, Pa: its valve regulates what it gives. */
    public static final double CONVERTER_FILL_LIQUID=500000,CONVERTER_FILL_GAS=1000000;
    /** A converter port's line velocity at its rated flow, m/s: its bore. */
    static final double CONVERTER_LINE_VELOCITY=2;
    /**
     * Registers a fluid converter as one ledger event (W3): its vessel (open, or closed for the gas variant), its drain (a
     * void) and its fill (a generator), each in the port lane of the cell its contact faces, its valve its converter's
     * ({@link FlowControl.ValveSource#CONVERTER}); then its facts, off, rated at {@code rate}. With {@code former} a registered
     * converter's vessel, the refresh of that converter onto {@code d} (decision D7): each device keeps its identity, its
     * controls and what it holds and moves to its new cell; its facts stay. Returns vessel, drain and fill.
     */
    public List<WorldTopologyLedger.Registration> placeConverter(ConverterDevices d,long former) {
        owned();var formerVessel=former>0?registrations().get(former):null;var u=formerVessel==null?null:topology.converter(former);
        var nitrogen=FluidDeviceSpec.nitrogen(catalog).composition();
        if(formerVessel!=null&&formerVessel.device().kind()==TopologyCompiler.Kind.RESERVOIR) {
            // The vessel keeps its identity and what it holds; a port the converter has moves, one it lacks (or a vessel
            // registered without its converter's facts) registers afresh, and the facts follow.
            var edits=new ArrayList<WorldTopologyLedger.Edit>();var placed=new ArrayList<WorldTopologyLedger.Registration>();
            var s=formerVessel.spec();long next=topology.nextIdentity();
            var vessel=new WorldTopologyLedger.Registration(formerVessel.device().withPlacement(d.node(),d.all()),
                    new FluidDeviceSpec(d.volume(),s.temperature(),s.pressure(),s.composition(),s.solids(),d.levelHeight(),!d.closed(),d.geometry(),null,d.blanket(),d.relief(),d.reliefFace()),Math.addExact(formerVessel.revision(),1));
            placed.add(vessel);edits.add(new WorldTopologyLedger.Edit(former,vessel));
            long[] ids={u==null?0:u.drain(),u==null?0:u.fill()};
            for(int k=0;k<2;k++) {
                var kind=k==0?TopologyCompiler.Kind.VOID:TopologyCompiler.Kind.GENERATOR;var contact=k==0?d.drain():d.fill();
                var r=ids[k]>0?registrations().get(ids[k]):null;
                if(r==null){ids[k]=next++;var fresh=converterPort(ids[k],contact,kind,d.rate(),d.closed(),nitrogen);placed.add(fresh);edits.add(new WorldTopologyLedger.Edit(ids[k],fresh));continue;}
                var fresh=converterPort(ids[k],contact,kind,d.rate(),d.closed(),nitrogen);
                var moved=new WorldTopologyLedger.Registration(r.device().withPlacement(fresh.device().position(),fresh.device().ports()).withGeometry(fresh.device().geometry()),r.spec(),Math.addExact(r.revision(),1));
                placed.add(moved);edits.add(new WorldTopologyLedger.Edit(ids[k],moved));
            }
            submit(edits,next);
            if(u==null)converters.register(ConverterUnit.built(former,ids[0],ids[0],ids[1],ids[1],d.closed(),d.capacity()).withSettings(ConverterUnit.Mode.OFF,d.rate(),ConverterUnit.DEFAULT_DEADBAND));
            else if(u.drain()!=ids[0]||u.fill()!=ids[1]){converters.remove(former);converters.register(new ConverterUnit(former,ids[0],ids[0],ids[1],ids[1],d.closed(),u.mode(),u.maxRate(),u.deadband(),u.thresholds(),u.capacity(),u.fluid(),u.stored(),u.remainder(),u.accountFluid(),u.account()));}
            return placed;
        }
        long next=topology.nextIdentity();long vessel=next++,drain=next++,fill=next++;
        var device=PhysicalFluidTopology.Device.builder(vessel,d.node(),TopologyCompiler.Kind.RESERVOIR,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive()).ports(d.all()).build();
        var spec=new FluidDeviceSpec(d.volume(),298.15,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE,nitrogen,SlurryFeed.NONE,d.levelHeight(),!d.closed(),d.geometry(),null,d.blanket(),d.relief(),d.reliefFace());
        var placed=List.of(new WorldTopologyLedger.Registration(device,spec,0),converterPort(drain,d.drain(),TopologyCompiler.Kind.VOID,d.rate(),d.closed(),nitrogen),
                converterPort(fill,d.fill(),TopologyCompiler.Kind.GENERATOR,d.rate(),d.closed(),nitrogen));
        submit(placed.stream().map(r->new WorldTopologyLedger.Edit(r.device().id(),r)).toList(),next);
        converters.register(ConverterUnit.built(vessel,drain,drain,fill,fill,d.closed(),d.capacity()).withSettings(ConverterUnit.Mode.OFF,d.rate(),ConverterUnit.DEFAULT_DEADBAND));
        return placed;
    }
    /** A converter's hidden port {@code id} meeting its vessel through {@code contact}: in the port lane of the next cell, facing back. */
    private static WorldTopologyLedger.Registration converterPort(long id,PhysicalFluidTopology.Port contact,TopologyCompiler.Kind kind,double rate,boolean closed,double[] nitrogen) {
        var back=PhysicalFluidTopology.opposite(contact.face());var cell=contact.cell().offset(contact.face()).withLane(PhysicalFluidTopology.PORT_LANE);
        double bore=Math.max(.05,Math.sqrt(4*rate/1000/(Math.PI*CONVERTER_LINE_VELOCITY)));
        var device=PhysicalFluidTopology.Device.builder(id,cell,kind,back,new PipeResistance.Geometry(1,bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.converter())
                .ports(List.of(new PhysicalFluidTopology.Port(cell,back,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.BULK))).build();
        double pressure=kind==TopologyCompiler.Kind.VOID?CONVERTER_DRAIN_PRESSURE:closed?CONVERTER_FILL_GAS:CONVERTER_FILL_LIQUID;
        return new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,pressure,nitrogen),0);
    }
    /** The identities of the converter whose vessel is {@code vessel}: its vessel, drain and fill that are registered. */
    public java.util.Set<Long> converterDevices(long vessel) {
        owned();var u=topology.converter(vessel);if(u==null)return java.util.Set.of();
        var ids=new java.util.HashSet<Long>();for(long id:new long[]{u.id(),u.drain(),u.fill()})if(id>0&&registrations().containsKey(id))ids.add(id);return ids;
    }
    /** A Forge fluid's tags in the fluid registry, without the hash; none for an id that names no fluid. */
    static Set<String> fluidTags(String fluid) {
        var id=net.minecraft.resources.ResourceLocation.tryParse(fluid);if(id==null)return Set.of();
        return net.minecraft.core.registries.BuiltInRegistries.FLUID.getHolder(id).map(h->h.tags().map(t->t.location().toString()).collect(java.util.stream.Collectors.toUnmodifiableSet())).orElse(Set.of());
    }
    /**
     * A batch still's devices to place (batch 2026-11-09-batch-distiller, W3): its pot (a vessel at its node with its phase
     * ports, volume, level height, geometry and heat unit: its heated area), its condenser's water side (null without the
     * built-in condenser), its DISTILLATE and VENT outlets (with the condenser) and its burners (a liquid-fired still), each
     * its one port at its own cell; and its design.
     */
    public record StillDevices(UnitVessel pot,@org.jetbrains.annotations.Nullable UnitSide water,
                               @org.jetbrains.annotations.Nullable PhysicalFluidTopology.Port distillate,@org.jetbrains.annotations.Nullable PhysicalFluidTopology.Port vent,
                               @org.jetbrains.annotations.Nullable PhysicalFluidTopology.Port burners,com.wormzjl.icheme.science.plant.still.BatchStill.Design design) {}
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
        if((d.burners()!=null)!=(design.heater()==com.wormzjl.icheme.science.plant.still.BatchStill.Heater.LIQUID))throw new IllegalArgumentException("A liquid-fired still has its burners, and only it");
        long next=topology.nextIdentity();var edits=new ArrayList<WorldTopologyLedger.Edit>();var placed=new ArrayList<WorldTopologyLedger.Registration>();
        long water=0,distillate=0,vent=0,fired=0;
        if(d.water()!=null)water=next++;
        long pot=next++;
        if(d.water()!=null) {
            var side=d.water();
            var device=PhysicalFluidTopology.Device.builder(water,side.node(),TopologyCompiler.Kind.HEAT_SIDE,PhysicalFluidTopology.Direction.NORTH,side.passage(),new FlowControl.Passive())
                    .ports(side.ports()).heat(side.heat().withPartner(pot)).build();
            var record=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(catalog),0);placed.add(record);edits.add(new WorldTopologyLedger.Edit(water,record));
        }
        var vessel=d.pot();
        var potDevice=PhysicalFluidTopology.Device.builder(pot,vessel.node(),TopologyCompiler.Kind.RESERVOIR,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive()).ports(vessel.ports()).heat(vessel.heat().withPartner(0)).build();
        var potSpec=new FluidDeviceSpec(vessel.volume(),298.15,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition(),SlurryFeed.NONE,vessel.levelHeight(),design.condenser(),vessel.geometry(),null);
        var potRecord=new WorldTopologyLedger.Registration(potDevice,potSpec,0);placed.add(potRecord);edits.add(new WorldTopologyLedger.Edit(pot,potRecord));
        for(var port:new PhysicalFluidTopology.Port[]{d.distillate(),d.vent()}) {
            if(port==null)continue;long id=next++;if(port==d.distillate())distillate=id;else vent=id;
            var device=PhysicalFluidTopology.Device.builder(id,port.cell(),TopologyCompiler.Kind.GENERATOR,PhysicalFluidTopology.Direction.NORTH,
                    new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.still()).ports(List.of(port)).build();
            var record=new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,STILL_OUTLET_PRESSURE,FluidDeviceSpec.nitrogen(catalog).composition()),0);placed.add(record);edits.add(new WorldTopologyLedger.Edit(id,record));
        }
        if(d.burners()!=null) {
            fired=next++;var port=d.burners();
            var device=PhysicalFluidTopology.Device.builder(fired,port.cell(),TopologyCompiler.Kind.VOID,PhysicalFluidTopology.Direction.NORTH,
                    new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.still()).ports(List.of(port)).build();
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
        if((d.burners()!=null)!=(design.heater()==com.wormzjl.icheme.science.plant.still.BatchStill.Heater.LIQUID))throw new IllegalArgumentException("A liquid-fired still has its burners, and only it");
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
                    :PhysicalFluidTopology.Device.builder(next,side.node(),TopologyCompiler.Kind.HEAT_SIDE,PhysicalFluidTopology.Direction.NORTH,side.passage(),new FlowControl.Passive())
                            .ports(side.ports()).heat(side.heat().withPartner(pot)).build();
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
                var device=PhysicalFluidTopology.Device.builder(ids[k],port.cell(),k<2?TopologyCompiler.Kind.GENERATOR:TopologyCompiler.Kind.VOID,PhysicalFluidTopology.Direction.NORTH,
                        new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.still()).ports(List.of(port)).build();
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
        var values=catalog.formulas(id);var names=catalog.conservedComponents(id);
        var out=new double[model.componentCount()][];var components=model.components();
        for(int c=0;c<out.length;c++){int i=names.indexOf(components.get(c));out[c]=i<0?new double[MaterialCatalog.FORMULA_ELEMENTS.size()]:values[i];}
        return out;
    }
    /** The network package's lower heating values (J/mol) in {@code model}'s conserved order, by component name. */
    static double[] heatingValues(MaterialCatalog catalog,FluidThermodynamics model) {
        String id=com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog);
        var values=catalog.heatingValues(id);var names=catalog.conservedComponents(id);
        var out=new double[model.componentCount()];var components=model.components();
        for(int c=0;c<out.length;c++){int i=names.indexOf(components.get(c));out[c]=i<0?0:values[i];}
        return out;
    }
    /**
     * The network package's hazard columns (batch 2026-11-15-pollution-system, WP4) in {@code model}'s conserved order, by
     * component name, in {@code HazardStandard}'s constructor order: toxic and corrosive references, lower flammability limits,
     * critical temperatures, critical pressures and acentric factors. A component the package lacks has no hazard and no
     * critical constants (NaN: its whole liquid counts as volatile).
     */
    static double[][] hazardColumns(MaterialCatalog catalog,FluidThermodynamics model) {
        String id=com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog);
        var names=catalog.conservedComponents(id);var components=model.components();
        return new double[][]{onModel(catalog.toxicReferences(id),names,components,0),onModel(catalog.corrosiveReferences(id),names,components,0),
                onModel(catalog.lowerFlammabilityLimits(id),names,components,0),onModel(catalog.criticalTemperatures(id),names,components,Double.NaN),
                onModel(catalog.criticalPressures(id),names,components,Double.NaN),onModel(catalog.acentricFactors(id),names,components,Double.NaN)};
    }
    /** {@code values} on the package's conserved list {@code names}, on {@code components} by name; {@code missing} where a component is not on it. */
    private static double[] onModel(double[] values,List<String> names,List<String> components,double missing) {
        var out=new double[components.size()];
        for(int c=0;c<out.length;c++){int i=names.indexOf(components.get(c));out[c]=i<0?missing:values[i];}
        return out;
    }
    /** A pollution release (WP4) to the pollution service of this server; nothing while the service is off. */
    private void releasePollution(PhysicalFluidTopology.Position at,com.wormzjl.icheme.science.pollution.Channel channel,double amount) {
        var service=com.wormzjl.icheme.runtime.pollution.PollutionService.find(server);
        if(service.isPresent())service.get().release(dimension(at.dimension()),new BlockPos(at.x(),at.y(),at.z()),channel,amount);
    }
    /**
     * The plant's own open flames in chunk {@code (chunkX, chunkZ)} of {@code dimension} (batch 2026-11-15-pollution-system,
     * WP6a): ignition sources of a flammable cloud, each at its registration's cell. Read from the cached views the pages and
     * the noise read; nothing solves and no island materialises: a flare whose last booked span released heat
     * ({@link Flares#view}), a fired heater's radiant coil while its burners fire ({@link FiredHeaters#flue}), a fired batch
     * still (not an electric one) while its fire heats the pot ({@link Stills#view}), and a prime mover's fuel void while its
     * set fires ({@link PrimeMovers#flue}: a combustion engine or a gas turbine).
     */
    public List<BlockPos> flames(String dimension,int chunkX,int chunkZ) {
        owned();var out=new ArrayList<BlockPos>();if(closed)return out;
        for(long id:registry.chunk(dimension,net.minecraft.world.level.ChunkPos.asLong(chunkX,chunkZ))) {
            var r=registrations().get(id);if(r==null)continue;var d=r.device();boolean lit;
            if(d.flare()){var v=flares.view(id);lit=v!=null&&v.heat()>0;}
            else if(d.engineFuel()){var primes=shafts.primeMovers();var f=primes==null?null:primes.flue(id);lit=f!=null&&f.firing()>0;}
            else if(radiantCoil(r)){long b=burners.burnersOf(id);var f=b==0?null:burners.flue(b);lit=f!=null&&f.fired()>0;}
            else{var still=topology.still(id);var v=still==null||still.heater()==com.wormzjl.icheme.science.plant.still.BatchStill.Heater.ELECTRIC?null:stills.view(id);lit=v!=null&&v.heat()>0;}
            if(lit)out.add(cell(r));
        }
        return out;
    }
    /**
     * What device {@code id} holds, let into the air because a blast destroyed it (batch 2026-11-15-pollution-system, WP6a,
     * S5); called before its removal. Read from its island as stored on the world list ({@link #storedIsland}: nothing
     * solves or materialises, a held island answers as stored): a vessel's or a junction's inventory, split by its state's
     * phases per compound; a line block, the line's holdup over the block's volume, in the phase split of what the line
     * moved over its last interval (no slip; a line that moved nothing over it releases nothing). The gas goes whole and the
     * liquid by its volatile share ({@link Releases#contents}). A generator, a void or a port holds no stock. Nothing is
     * booked here: the removal books a vessel's contents as removed, as before. Returns the masses, or {@link Releases.Spilled#NONE}.
     */
    public Releases.Spilled releaseContents(long id) {
        owned();if(closed||releases==null)return Releases.Spilled.NONE;
        var r=registrations().get(id);Long owner=r==null?null:registry.owner(id);if(owner==null)return Releases.Spilled.NONE;
        var snapshot=storedIsland(owner);int n=model.componentCount();double[] gas=new double[n],liquid=new double[n];boolean node=false;
        for(var reservoir:snapshot.graph().reservoirs()) {
            if(reservoir.id()!=id)continue;node=true;if(reservoir.fixed())return Releases.Spilled.NONE;
            var moles=reservoir.inventory().moles();var s=reservoir.state();double[] v=s.vaporAmountsView(),l=s.liquidAmountsView(),a=s.aqueousAmountsView();
            for(int c=0;c<Math.min(n,moles.length);c++){double all=v[c]+l[c]+a[c],held=Math.max(0,moles[c]),share=all>0?Math.clamp(v[c]/all,0,1):1;gas[c]=held*share;liquid[c]=held-gas[c];}
            break;
        }
        if(!node) {
            var result=snapshot.lastResult().orElse(null);var g=r.device().geometry();
            double volume=g.length()*Math.max(1,g.channels())*Math.PI*g.diameter()*g.diameter()/4;
            if(result==null||!(volume>0))return Releases.Spilled.NONE;
            for(var view:registry.pipeViews(id)) {
                PipeTransfer moved=null;for(var t:result.pipeTransfers())if(t.pipeId()==view.pipeId()){moved=t;break;}
                if(moved==null)continue;
                double passed=0;for(var stream:List.of(moved.forward(),moved.reverse()))for(double q:stream.phaseVolumes())passed+=q;
                if(!(passed>0))continue;double scale=volume/passed;
                for(var stream:List.of(moved.forward(),moved.reverse())){var p=stream.phaseMoles();
                    for(int c=0;c<Math.min(n,p[2].length);c++){gas[c]+=p[2][c]*scale;liquid[c]+=(p[0][c]+p[1][c])*scale;}}
                break;
            }
        }
        var weights=model.molecularWeights();double gasMass=0,liquidMass=0;
        for(int c=0;c<Math.min(n,weights.length);c++){gasMass+=gas[c]*weights[c];liquidMass+=liquid[c]*weights[c];}
        if(!(gasMass+liquidMass>0))return Releases.Spilled.NONE;
        releases.contents(r.device().position(),gas,liquid);
        return new Releases.Spilled(gasMass,liquidMass);
    }
    /** The pumps' health (W10b): a repair kit queues its event here. */
    public MachineHealth health(){owned();return health;}
    /**
     * A shaft-driven machine (batch 2026-11-03-rotating-machinery W3): a pump or compressor casing on its curve
     * ({@code FlowControl.Machine}), a reciprocating frame ({@code Compressor}) or a steam turbine (kind {@code TURBINE},
     * {@code FlowControl.Turbine}, reading A37), registered at its suction or steam nozzle's cell with its process pair.
     */
    public WorldTopologyLedger.Registration placeMachine(PhysicalFluidTopology.Position suction,TopologyCompiler.Kind kind,List<PhysicalFluidTopology.Port> ports,FlowControl control) {
        owned();
        if(kind!=TopologyCompiler.Kind.PUMP&&kind!=TopologyCompiler.Kind.COMPRESSOR&&kind!=TopologyCompiler.Kind.TURBINE&&kind!=TopologyCompiler.Kind.EJECTOR||ports.isEmpty())throw new IllegalArgumentException("Expected a shaft-driven machine or an ejector");
        long id=topology.nextIdentity();
        var device=PhysicalFluidTopology.Device.builder(id,suction,kind,PhysicalFluidTopology.Direction.NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),control).ports(ports).build();
        var record=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(catalog),0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /** A complete multi-cell mover uses the same controls and geometry as its one-block counterpart. */
    public WorldTopologyLedger.Registration placeMover(PhysicalFluidTopology.Position suction,TopologyCompiler.Kind kind,List<PhysicalFluidTopology.Port> ports){return placement.placeMover(suction,kind,ports);}
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
    public WorldTopologyLedger.Registration placeVessel(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,double volume,double levelHeight,boolean open,com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry,com.wormzjl.icheme.science.fluid.network.CoolingTower tower){owned();return placement.placeVessel(node,ports,volume,levelHeight,open,geometry,tower);}
    /**
     * Queues a configuration change as a ledger event and returns it, or null when nothing would change. A fired heater's
     * coils share their burners and set point (batch 2026-10-27-heat-exchanger-design): a coil's new control moves its
     * partner coil in the same event.
     */
    public WorldTopologyLedger.Event edit(long id,long expectedRevision,PhysicalFluidTopology.Device device,FluidDeviceSpec spec){owned();return placement.edit(id,expectedRevision,device,spec);}
    /**
     * Fits a closed vessel's gas blanketing valve, its gas inlet {@code port} (role {@code BLANKET}), or removes it
     * ({@code port} null), as one queued ledger event (batch 2026-11-11-gas-blanketing). A fitted valve starts at
     * {@link FluidDeviceSpec#DEFAULT_BLANKET}; a removed one leaves no set pressure. False for a device that takes no valve: one
     * that is not a closed vessel, or whose inlet meets another of its ports.
     */
    public boolean blanketValve(long id,PhysicalFluidTopology.Port port){owned();return placement.blanketValve(id,port);}
    /** Sets a fitted gas blanketing valve's set pressure, Pa absolute, as a queued ledger event; false for a vessel without one or a pressure outside the valve's range. */
    public boolean blanketPressure(long id,double pascal){owned();return placement.blanketPressure(id,pascal);}
    /**
     * Fits a closed vessel's pressure safety valve, its outlet {@code port} (role {@code RELIEF}), or removes it ({@code port}
     * null), as one queued ledger event (batch 2026-11-11-gas-blanketing, the pressure safety valve). A fitted valve starts at
     * {@link FluidDeviceSpec#DEFAULT_RELIEF}, or just above a blanketing valve's vent pressure where that is higher. False for a
     * device that takes no valve: one that is not a closed vessel, or whose outlet meets another of its ports.
     */
    public boolean reliefValve(long id,PhysicalFluidTopology.Port port){owned();return placement.reliefValve(id,port);}
    /**
     * Fits a pressure safety valve on pipe {@code id}, its outlet leaving through {@code face}, or removes it ({@code face}
     * null), as one queued ledger event. The pipe's other faces stay joined as a plain pipe's. False for a device that is no
     * plain pipe: a pipe carrying a fitting, a rack lane, or a pipe with ports.
     */
    public boolean pipeRelief(long id,PhysicalFluidTopology.Direction face){owned();return placement.pipeRelief(id,face);}
    /** Sets a fitted pressure safety valve's set pressure, Pa absolute, as a queued ledger event; false for a device without one or a pressure it does not take ({@link FluidDeviceSpec#reliefTakes}). */
    public boolean reliefPressure(long id,double pascal){owned();return placement.reliefPressure(id,pascal);}
    // ---------------- running columns (batch 2026-10-28-column-operation, C3) ----------------

    /**
     * Where a running column's port stands (plan 4.2): its kind and number, the boundary valve, the cell inside the valve
     * (the column's side of it), the face from that cell to the valve, and the port's pressure (Pa) and temperature (K).
     */
    public record PortSpot(com.wormzjl.icheme.science.plant.column.Port port,int index,long valve,PhysicalFluidTopology.Position cell,
                           PhysicalFluidTopology.Direction toward,double pressure,double temperature) {}
    /**
     * A Start (plan 4.1): the column's origin, its setpoints, the pipe blocks in front of its nozzles, its ports, and the
     * registrations of a water-cooled condenser's two sides, every shell's (empty for an air cooler): those the column does
     * not hold are its water sides (C6b, S3b); the bare tube of its air condenser's banks, m² (U2: 0 for none); and the
     * registrations of its reboiler's and each pumparound cooler's sides, every unit's (W4): those it does not hold, whose
     * partner it holds, are their utility sides; and its powered members (W5): those it holds draw power while it runs.
     */
    public record ColumnStart(PhysicalFluidTopology.Position master,com.wormzjl.icheme.science.plant.column.Setpoints setpoints,
                              List<PhysicalFluidTopology.Position> nozzles,List<PortSpot> spots,List<Long> condenser,double airSurface,List<Long> reboiler,List<List<Long>> pumparounds,ColumnUnit.Members members) {
        public ColumnStart{Objects.requireNonNull(members);condenser=List.copyOf(condenser);reboiler=List.copyOf(reboiler);pumparounds=pumparounds.stream().map(List::copyOf).toList();
            if(!(airSurface>=0)||!Double.isFinite(airSurface))throw new IllegalArgumentException("Invalid air condenser surface");}
        /** Without members: none draws power (W5). */
        public ColumnStart(PhysicalFluidTopology.Position master,com.wormzjl.icheme.science.plant.column.Setpoints setpoints,List<PhysicalFluidTopology.Position> nozzles,List<PortSpot> spots,List<Long> condenser,double airSurface,List<Long> reboiler,List<List<Long>> pumparounds){this(master,setpoints,nozzles,spots,condenser,airSurface,reboiler,pumparounds,ColumnUnit.Members.NONE);}
        public ColumnStart(PhysicalFluidTopology.Position master,com.wormzjl.icheme.science.plant.column.Setpoints setpoints,List<PhysicalFluidTopology.Position> nozzles,List<PortSpot> spots,List<Long> condenser,double airSurface){this(master,setpoints,nozzles,spots,condenser,airSurface,List.of(),List.of());}
        public ColumnStart(PhysicalFluidTopology.Position master,com.wormzjl.icheme.science.plant.column.Setpoints setpoints,List<PhysicalFluidTopology.Position> nozzles,List<PortSpot> spots,List<Long> condenser){this(master,setpoints,nozzles,spots,condenser,0);}
        public ColumnStart(PhysicalFluidTopology.Position master,com.wormzjl.icheme.science.plant.column.Setpoints setpoints,List<PhysicalFluidTopology.Position> nozzles,List<PortSpot> spots){this(master,setpoints,nozzles,spots,List.of());}
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
        owned();if(closed)return com.wormzjl.icheme.science.plant.column.Refusal.CLOSED.text();
        var existing=column(request.master());
        if(existing!=null&&existing.running())return com.wormzjl.icheme.science.plant.column.Refusal.ALREADY_RUNNING.text();
        var valves=new HashSet<Long>();for(var s:request.spots())valves.add(s.valve());
        java.util.function.Function<PhysicalFluidTopology.Position,WorldTopologyLedger.Registration> at=p->registry.at(p).orElse(null);
        var inside=new LinkedHashMap<Long,WorldTopologyLedger.Registration>();var queue=new ArrayDeque<WorldTopologyLedger.Registration>();
        for(var nozzle:request.nozzles()) {
            var r=at.apply(nozzle);if(r==null)return com.wormzjl.icheme.science.plant.column.Refusal.NOZZLE_UNREGISTERED.text();
            if(!valves.contains(r.device().id())&&inside.putIfAbsent(r.device().id(),r)==null)queue.add(r);
        }
        while(!queue.isEmpty()) {
            var r=queue.removeFirst();var d=r.device();
            if(d.fixed())return com.wormzjl.icheme.science.plant.column.Refusal.FIXED_INSIDE.text();
            if(d.suspended()||d.held()||d.port()||topology.pending(d.id()))return com.wormzjl.icheme.science.plant.column.Refusal.HELD_INSIDE.text();
            if(inside.size()>COLUMN_LIMIT)return com.wormzjl.icheme.science.plant.column.Refusal.TOO_MANY_DEVICES.text(COLUMN_LIMIT);
            for(var contact:d.contacts()) {
                var next=PhysicalFluidTopology.across(at,WorldTopologyLedger.Registration::device,d,contact.cell(),contact.direction());
                if(next==null||valves.contains(next.device().id())||!d.joins(next.device(),contact.cell(),contact.direction()))continue;
                if(inside.putIfAbsent(next.device().id(),next)==null)queue.add(next);
            }
        }
        if(sharePlace(request.spots()))return com.wormzjl.icheme.science.plant.column.Refusal.SHARED_PLACE.text();
        for(var s:request.spots()) {
            var valve=registrations().get(s.valve());
            if(valve==null||inside.containsKey(s.valve()))return com.wormzjl.icheme.science.plant.column.Refusal.VALVE_MISSING.text();
            if(at.apply(s.cell().withLane(PhysicalFluidTopology.PORT_LANE))!=null)return com.wormzjl.icheme.science.plant.column.Refusal.PLACE_TAKEN.text();
        }
        long unit=existing!=null?existing.id():topology.reserve(1);long first=topology.nextIdentity();
        var edits=new ArrayList<WorldTopologyLedger.Edit>();var stations=new ArrayList<ColumnUnit.Station>();
        int m=model.componentCount();var nitrogen=FluidDeviceSpec.nitrogen(catalog).composition();
        for(var r:inside.values())edits.add(new WorldTopologyLedger.Edit(r.device().id(),new WorldTopologyLedger.Registration(r.device().withSuspendedBy(unit),r.spec(),Math.addExact(r.revision(),1))));
        long id=first;
        for(var s:request.spots()) {
            var cell=s.cell().withLane(PhysicalFluidTopology.PORT_LANE);var valve=registrations().get(s.valve()).device();
            var kind=s.port().inlet()?TopologyCompiler.Kind.VOID:TopologyCompiler.Kind.GENERATOR;
            var device=PhysicalFluidTopology.Device.builder(id,cell,kind,s.toward(),valve.geometry(),new com.wormzjl.icheme.science.fluid.network.FlowControl.Passive())
                    .ports(List.of(new PhysicalFluidTopology.Port(cell,s.toward(),com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.BULK))).build();
            edits.add(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,s.temperature(),s.pressure(),nitrogen),0)));
            var kept=existing==null?null:existing.station(s.port(),s.index());
            stations.add(kept==null?new ColumnUnit.Station(s.port(),s.index(),id,s.valve(),com.wormzjl.icheme.science.plant.column.ColumnCycle.Account.empty(m)):new ColumnUnit.Station(s.port(),s.index(),id,s.valve(),kept.account(),kept.solids()));
            id++;
        }
        // Stations a stopped column kept and this start no longer has (the design lost a port) keep their accounts, with no port.
        if(existing!=null)stations.addAll(existing.lost(stations));
        // The condenser's water sides: of each shell's two sides, the one outside the column, partnered with one inside (C6b);
        // one a shell in parallel (S3b).
        var water=utilitySides(request.condenser(),inside);
        if(water.size()>ColumnUnit.MAXIMUM_CONDENSERS)return com.wormzjl.icheme.science.plant.column.Refusal.TOO_MANY_SHELLS.text(ColumnUnit.MAXIMUM_CONDENSERS);
        // The reboiler's and each pumparound cooler's utility sides, by the same rule (W4): a duty with none stays free.
        var reboiler=utilitySides(request.reboiler(),inside);var pumparounds=new ArrayList<List<Long>>();
        for(var sides:request.pumparounds())pumparounds.add(utilitySides(sides,inside));
        if(reboiler.size()>ColumnUnit.MAXIMUM_CONDENSERS)return com.wormzjl.icheme.science.plant.column.Refusal.TOO_MANY_UTILITY_UNITS.text("reboiler",ColumnUnit.MAXIMUM_CONDENSERS);
        for(int j=0;j<pumparounds.size();j++)if(pumparounds.get(j).size()>ColumnUnit.MAXIMUM_CONDENSERS)return com.wormzjl.icheme.science.plant.column.Refusal.TOO_MANY_UTILITY_UNITS.text("pumparound "+(j+1)+" cooler",ColumnUnit.MAXIMUM_CONDENSERS);
        var utilities=new ColumnUnit.Utilities(reboiler,pumparounds);
        // A restart keeps the set points the player applied while they fit the design's ports (C7); else the design's.
        var setpoints=existing!=null&&compatible(existing.setpoints(),request.setpoints())?existing.setpoints():request.setpoints();
        // The powered members it holds (W5): a member outside the column is not its own.
        var asked=request.members();
        var members=new ColumnUnit.Members(held(asked.fans(),inside),held(asked.reflux(),inside),asked.pumparounds().stream().map(p->held(p,inside)).toList());
        // While machines need power, a member pump with no driver at all cannot start (W5, stage 4 step 4: the owner may veto it).
        String undriven=electric.settings().needPower()?new ColumnPower(topology,electric,shafts).undriven(members):null;
        if(undriven!=null)return com.wormzjl.icheme.science.plant.column.Refusal.NO_DRIVER.text(undriven);
        var running=new ColumnUnit(unit,request.master(),true,setpoints,stations,existing!=null?existing.totals():ColumnUnit.Totals.empty(m),water,null,false,request.airSurface(),utilities,members);
        var was=existing;topology.column(running);
        try{submit(edits,id);}catch(IllegalArgumentException|IllegalStateException refused){if(was!=null)topology.column(was);else topology.removeColumn(unit);return com.wormzjl.icheme.science.plant.column.Refusal.PORTS_DO_NOT_FIT.text(refused.getMessage());}
        columns.refresh();
        IChemE.LOGGER.info("column status=STARTED master={},{},{} unit={} held={} ports={}",request.master().x(),request.master().y(),request.master().z(),unit,inside.size(),stations.size());
        return null;
    }
    /**
     * Of {@code sides} (a member's registered heat sides, every unit's), those outside the column whose heat partner it holds
     * ({@code inside}), once each: a condenser's water sides (C6b, S3b), a reboiler's or pumparound cooler's utility sides (W4).
     */
    private List<Long> utilitySides(List<Long> sides,Map<Long,WorldTopologyLedger.Registration> inside) {
        var out=new ArrayList<Long>();
        for(long side:sides){var r=registrations().get(side);if(r!=null&&!inside.containsKey(side)&&r.device().heat()!=null&&inside.containsKey(r.device().heat().partner())&&!out.contains(side))out.add(side);}
        return out;
    }
    /** Of {@code members}, those the column holds ({@code inside}), once each. */
    private static List<Long> held(List<Long> members,Map<Long,WorldTopologyLedger.Registration> inside) {
        var out=new ArrayList<Long>();for(long id:members)if(inside.containsKey(id)&&!out.contains(id))out.add(id);
        return out;
    }
    /** Whether two of {@code spots} stand inside one cell: two boundary valves on one inside cell have one place for a port. */
    static boolean sharePlace(List<PortSpot> spots) {
        var places=new HashSet<PhysicalFluidTopology.Position>();
        for(var s:spots)if(!places.add(s.cell()))return true;
        return false;
    }
    /** Whether set points {@code a} fit the design {@code b} was taken from: its rate and as many steam inlets, draws and pumparounds. */
    static boolean compatible(com.wormzjl.icheme.science.plant.column.Setpoints a,com.wormzjl.icheme.science.plant.column.Setpoints b) {
        return a.designFeedRate()==b.designFeedRate()&&a.steamPerFeed().length==b.steamPerFeed().length&&a.drawsPerFeed().length==b.drawsPerFeed().length
                &&a.pumparoundsPerFeed().length==b.pumparoundsPerFeed().length&&(a.reboilerPerFeed()==0)==(b.reboilerPerFeed()==0);
    }
    /**
     * The set points {@code value} for the column at {@code master} (C7, plan section 13): a fact of its unit, the next cycle
     * reads them and a changed ratio solves. A column that never ran gets a stopped unit with no stations on them (W7), whose
     * set points its first Start keeps while they fit. Returns null, or why not: the values do not fit its design. The feed
     * rate stays at most the design rate (owner Q8).
     */
    public @org.jetbrains.annotations.Nullable String editColumn(PhysicalFluidTopology.Position master,com.wormzjl.icheme.science.plant.column.Setpoints value) {
        owned();if(closed)return "the fluid world is closed";
        var u=column(master);
        if(u==null) {
            var stopped=new ColumnUnit(topology.reserve(1),master,false,value,List.of(),ColumnUnit.Totals.empty(model.componentCount()));
            topology.column(stopped);
            IChemE.LOGGER.info("column status=SETPOINTS master={},{},{} unit={} stopped",master.x(),master.y(),master.z(),stopped.id());
            return null;
        }
        if(!compatible(u.setpoints(),value))return "the set points do not fit the column's design";
        topology.column(u.withSetpoints(value));columns.edited(u.id());
        IChemE.LOGGER.info("column status=SETPOINTS master={},{},{} unit={}",master.x(),master.y(),master.z(),u.id());
        return null;
    }
    /** The Operation tab's view of the column at {@code master} (C7): cached cycle data, no solve; null where it has no unit. */
    public com.wormzjl.icheme.science.plant.column.OperationView columnView(PhysicalFluidTopology.Position master,java.util.function.LongToDoubleFunction opening) {
        owned();var u=column(master);return u==null?null:columns.view(u.id(),topology.onlineTick(),opening);
    }
    /**
     * The column unit a control loop with output {@code output} plans the feed of (W6.3): the unit at {@code master} (the
     * output is that column's host), else the running unit whose feed boundary valve {@code output} is; 0 for none.
     */
    public long columnLoop(long output,@org.jetbrains.annotations.Nullable PhysicalFluidTopology.Position master){
        owned();
        if(master!=null){var u=column(master);return u==null?0:u.id();}
        for(var u:topology.columns().values())if(u.running()){var s=u.station(com.wormzjl.icheme.science.plant.column.Port.FEED,0);if(s!=null&&s.valve()==output)return u.id();}
        return 0;
    }
    /** The molar mass column unit {@code unit}'s feed inlet meters by, kg/mol, or NaN (W6.3). */
    public double columnFeedMolarMass(long unit){owned();return columns.feedMolarMass(unit);}
    /** The feed boundary valve of column unit {@code unit}, or 0 (W6.3). */
    public long columnFeedValve(long unit){owned();var u=topology.column(unit);var s=u==null?null:u.station(com.wormzjl.icheme.science.plant.column.Port.FEED,0);return s==null?0:s.valve();}
    /** The alarms of the column at {@code master} (W6.1): a read of its run's last conditions; none where it does not run. */
    public java.util.Set<EquipmentAlarm> columnAlarms(PhysicalFluidTopology.Position master){owned();var u=column(master);return u==null?java.util.Set.of():EquipmentAlarm.column(columns.conditions(u.id()));}
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
    public @org.jetbrains.annotations.Nullable String divert(long id,int outlet){owned();if(closed)return "the fluid world is closed";return placement.divert(id,outlet);}
    /** The open outlet of diverter valve {@code id} (0 to 2), or -1 for none. */
    public int diverterOutlet(long id){owned();return placement.diverterOutlet(id);}
    /** Reading committed static configuration never advances or materialises process state. */
    public Optional<PhysicalFluidTopology.Device> committedDevice(long id){owned();var record=topology.active().get(id);return record==null?Optional.empty():Optional.of(record.device());}
    public void remove(long id){owned();placement.remove(id);}
    /**
     * Holds registration {@code id}, or releases it (decision S11, REGISTRY_FIRST_PLAN.md 4.1): held, it keeps what it holds
     * and joins nothing. A topology edit at its next revision, so its islands recompile on the engine's schedule. Whether an
     * edit was queued: none for an unknown identity, a column's port, or one already so.
     */
    public boolean hold(long id,boolean held){owned();return placement.hold(id,held);}
    /** Every held part (W5c C5): the held registrations, shaft parts, cables, controllers and consoles. */
    public int heldCount(){owned();return placement.heldCount();}
    /** The held registrations, queued holds included, by identity. */
    public List<WorldTopologyLedger.Registration> held(){owned();return placement.held();}
    /**
     * An operator drops held registration {@code id} (REGISTRY_FIRST_PLAN.md 4.5): it leaves as a broken device does, what
     * it holds booked as removed, but nothing drops (decision S9): its block is not in the world. Its accessories leave the
     * accessory store with it. Whether it was held.
     */
    public boolean drop(long id){owned();return placement.drop(id);}
    /**
     * Queues a filter's solid recovery as a ledger event and returns it, as an edit is queued: nothing is applied or
     * delivered here. The engine applies it with the next batch of events, takes the cake the island holds then and
     * delivers the item; an empty filter or a full inventory at that point refuses it in the menu's next reply.
     */
    public WorldTopologyLedger.Event recoverFilter(long id,long expectedRevision,net.minecraft.server.level.ServerPlayer player){owned();return placement.recoverFilter(id,expectedRevision,player);}
    WorldTopologyLedger.Event submit(List<WorldTopologyLedger.Edit> edits,long nextId){return submit(edits,nextId,null);}
    WorldTopologyLedger.Event submit(List<WorldTopologyLedger.Edit> edits,long nextId,WorldTopologyLedger.Recovery recovery) {
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
        // So does a converter's fill whose forward rule names a compound its island lacks (W3).
        converters.grow(edits->submit(edits,topology.nextIdentity()));
        // A converter's queued settings (W4), each a fact its island reads at its next slice.
        converters.applyQueued();
        runtime.tick();
        if(topology.hasPendingEvents()){applyPending();runtime.coordinator().pumpIfUseful();}
        // Shaft events apply after the devices they name (W3), then their trains recompile and wake what moved.
        if(shafts.hasEvents()&&propertyHold==null){shafts.apply();if(topology.hasPendingEvents())applyPending();runtime.coordinator().pumpIfUseful();}
        // Repair kits (W10b): queued engine events, applied after the devices they name; a repaired failed pump wakes its island.
        if(health.hasEvents()&&propertyHold==null){health.apply();runtime.coordinator().pumpIfUseful();}
        instruments.tick(topology.onlineTick());
        // Every 100 ticks each loaded motor is bound again (code base review R24, owner option A: kept). The pass does more than
        // a first bind: it puts the motor's data port back on a cell a structure refresh replaced, and refreshes the port after
        // a wiring change (ControlHosts.bindMotor, DataPorts.refresh): binding on chunk load and on unit creation would miss
        // both. It reads the ledger's shaft map (one copy, as before) without ShaftTrains.units()'s second copy, in the same
        // order; only the motors act. A motor-only index belongs with the ledger's shaft accessors (machines lane, B-M1).
        if(topology.onlineTick()%100==0) for(var motor:topology.shafts().values()) if(motor.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR) {
            var motorLevel=server.getLevel(dimension(motor.position().dimension()));
            var pos=new BlockPos(motor.position().x(),motor.position().y(),motor.position().z());
            if(motorLevel!=null&&motorLevel.hasChunkAt(pos)&&motorLevel.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity cell)controls().bindMotor(cell,motor);
        }
        controls().tick(topology.onlineTick());
        presentation.tick(topology.onlineTick());
        // The watched drivers' states at the presentation cadence (W3): a read of what the trains last decided.
        if(!displays.driverWatchers.isEmpty()&&topology.onlineTick()%100==0)displays.publishDrivers();
        if(!displays.rotationWatchers.isEmpty()&&topology.onlineTick()%100==0)displays.publishRotations();
        // The device displays publish at their device's slot of the 100-tick cadence, as presentation buckets do (code base
        // review R10): flares, stills, towers' plumes, fired heaters' flues and chimneys. Reads only.
        long displayTick=topology.onlineTick();displays.publishDue(displayTick);
        // The far silhouettes and the noise levels (batch 2026-11-10-custom-shader-system), by watcher: reads only.
        if(topology.onlineTick()%100==0){publishSilhouettes();publishNoise();}
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
    String linesStatus(long id,HeatUnit heat) {
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
    public void loadedChunk(ResourceKey<Level> dimension,long chunk){owned();if(closed)return;chunks.loadedChunk(dimension,chunk);}
    /**
     * The next registration of {@code dimension} the re-alignment checks, taken off its queue, or 0 when none is queued
     * (registry first, REGISTRY_FIRST_PLAN.md 4.2). Each registration of a loaded chunk is queued once, at the load.
     */
    public long nextRealignment(String dimension){owned();return chunks.nextRealignment(dimension);}
    /** The next shaft part of {@code dimension} the re-alignment checks, taken off its queue, or 0 when none is queued (W5c C1). */
    public long nextShaftRealignment(String dimension){owned();return chunks.nextShaftRealignment(dimension);}
    /** A device's block entity loaded or was bound: marked for its bucket, never presented at once. */
    public void deviceLoaded(long id){owned();if(!closed){presentation.deviceLoaded(id);if(instruments!=null)instruments.bind(instrumentHost(id));}}
    /** A device's block entity unloaded or was removed: publications stop marking it, and its watches end (a client asks again when it loads the device). */
    public void deviceUnloaded(long id){owned();instruments.forget(id);displays.unloaded(id);presentation.deviceUnloaded(id);publishedMenuData.remove(id);lastLines.remove(id);}
    /**
     * A client's watch request for open tanks by their node cells (owner decision FR4, R-B): each new watch of a loaded
     * open tank within {@link com.wormzjl.icheme.network.FluidNetwork#WATCH_RANGE} is answered with the tank's last published display, if it has one (a
     * read of cached data: nothing solves, nothing is marked), and the tank's next buckets send it again. Cells that are
     * no open tank, out of range or over the player's maximum are ignored.
     */
    public void watchTanks(net.minecraft.server.level.ServerPlayer player,List<BlockPos> watch,List<BlockPos> unwatch){owned();if(closed)return;displays.watchTanks(player,watch,unwatch);}
    /** Ends every watch of a player who left. */
    public void forgetWatcher(UUID player){owned();displays.forget(player);silhouettes.forget(player);noise.forget(player);}

    /** Validate a bounded subscription and replay its cached publication. Opening a watch never reads a train's speed. */
    public void watchRotations(net.minecraft.server.level.ServerPlayer player,List<BlockPos> watch,List<BlockPos> unwatch){owned();if(closed)return;displays.watchRotations(player,watch,unwatch);}

    /**
     * A driver sound subscription replays its cached publication; only the presentation cadence reads train state.
     */
    public void watchDrivers(net.minecraft.server.level.ServerPlayer player,List<BlockPos> watch,List<BlockPos> unwatch){owned();if(closed)return;displays.watchDrivers(player,watch,unwatch);}
    /** A sound/animation subscription replays published operating state only; it never reads or advances a process. */
    public void watchMovers(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch){owned();if(closed)return;displays.watchMovers(player,watch,unwatch);}
    /**
     * A flare's flame subscription (W5): it replays the state last published, or the flare's last booked span (a read), and
     * the presentation cadence keeps it current. It never reads or advances a process.
     */
    public void watchFlares(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch){owned();if(closed)return;displays.watchFlares(player,watch,unwatch);}
    /** Whether device {@code id} publishes its display at online tick {@code tick}: its slot of the
     * {@value FluidPresentation#BUCKET_TICKS}-tick cadence, by identity, as a presentation bucket (code base review R10). */
    static boolean displayDue(long id,long tick){return Math.floorMod(id,(long)FluidPresentation.BUCKET_TICKS)==Math.floorMod(tick,(long)FluidPresentation.BUCKET_TICKS);}
    /**
     * A batch still's fire subscription (W6), as a flare's: it replays the state last published, or the still's cached view
     * (a read), and the presentation cadence keeps it current. It never reads or advances a process. A still is watched at
     * its pot's cell, where its identity binds.
     */
    public void watchStills(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch){owned();if(closed)return;displays.watchStills(player,watch,unwatch);}
    /**
     * A cooling tower's plume subscription (batch 2026-11-10-custom-shader-system, W4), as a flare's: watched at its basin's
     * fluid cell within {@link com.wormzjl.icheme.network.FluidNetwork#WATCH_RANGE}; it replays the state last published, or the
     * rates its island last accepted (a read), and the presentation cadence keeps it current. It never reads or advances a process.
     */
    public void watchTowers(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch){owned();if(closed)return;displays.watchTowers(player,watch,unwatch);}
    /**
     * A fired heater's flue subscription (W5), as a flare's: watched at its fluid cell, where its radiant coil binds; it
     * replays the state last published, or the heater's last booked slices (a read), and the presentation cadence keeps it current.
     */
    public void watchHeaters(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch){owned();if(closed)return;displays.watchHeaters(player,watch,unwatch);}
    /**
     * A chimney's plume subscription (batch 2026-11-10-chimney), as a heater's: watched at its tip's cell; it replays the state
     * last published, or the chimney's last booked span (a read), and the presentation cadence keeps it current.
     */
    public void watchChimneys(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch){owned();if(closed)return;displays.watchChimneys(player,watch,unwatch);}
    static boolean radiantCoil(WorldTopologyLedger.Registration record) {
        var heat=record.device().heat();
        return heat!=null&&heat.burners()&&heat.side()==com.wormzjl.icheme.science.equipment.HeatSides.Side.RADIANT;
    }
    /**
     * The dry air a tower's plume leaves with, kg/s, of its fill's {@code dryAirFlow} (batch 2026-11-10-custom-shader-system):
     * the flow up through the fill and out of the stack; 0 below {@code TowerTransfer.NO_AIR} and for a reversed draught
     * (negative: air drawn down the stack and out of the inlets, which leaves no plume over the stack).
     */
    static double plumeAir(double dryAirFlow){return dryAirFlow>com.wormzjl.icheme.science.fluid.network.TowerTransfer.NO_AIR?dryAirFlow:0;}
    /** The mover state last published for device {@code id} (a read), or null. */
    public com.wormzjl.icheme.network.FluidNetwork.MoverState publishedMover(long id){owned();return displays.publishedMover(id);}

    /** Explicit visual requests replay cached appearance only, then keep a future engine bucket scheduled. */
    public void watchSightGlasses(net.minecraft.server.level.ServerPlayer player,List<com.wormzjl.icheme.network.FluidNetwork.Target> watch,List<com.wormzjl.icheme.network.FluidNetwork.Target> unwatch){owned();if(closed)return;displays.watchSightGlasses(player,watch,unwatch);}
    static BlockPos cell(WorldTopologyLedger.Registration record){var p=record.device().position();return new BlockPos(p.x(),p.y(),p.z());}
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
    public void instrumentObservationChanged(long id){owned();displays.refreshObserved(id);}
    public void forgetFlowmeter(long id){owned();registry.forgetFlowmeter(id);}
    /** Whether a flowmeter is installed on device {@code id} (W8). */
    public boolean flowmeterOn(long id){owned();return registry.flowmeterOn(id);}
    public void flowmeter(long id,boolean installed){owned();registry.flowmeter(id,installed);}
    public boolean removePipeAccessory(long id){owned();return registry.removePipeAccessory(id);}
    public record InstrumentReading(double pressure,double temperature,boolean flowing,double massFlow,Double volumeFlow){public InstrumentReading(double pressure,double temperature,boolean flowing){this(pressure,temperature,flowing,0,0.0);}}
    /** A pipe's instrument reading: its observations in its island's last solved interval, which carry no compound amounts, so
     * they are read as stored, with no island snapshot, preview or world-list padding (code base review R1). */
    public InstrumentReading instrumentPipeReading(long id){owned();Long owner=registry.owner(id);if(owner==null)return null;var r=registrations().get(id);if(r==null)return null;var last=runtime.coordinator().lastResult(owner);if(last.isEmpty())return null;
        for(var observed:last.get().observations())for(var sample:observed.flowSamples())if(sample.host()==id)
            return new InstrumentReading(sample.pressure(),observed.temperature(),observed.flowing(),sample.massFlow(),sample.volumeFlow());
        for(var mapping:registry.pipeViews(id)){if(!Double.isFinite(mapping.tap()))continue;for(var observed:last.get().observations())if(observed.pipe()==mapping.pipeId())return new InstrumentReading(observed.pressure(mapping.tap(),r.device().position().y(),!mapping.forward()),observed.temperature(),observed.flowing(),observed.massFlow()*(mapping.forward()?1:-1),observed.density()>0?observed.massFlow()/observed.density()*(mapping.forward()?1:-1):0);}return null;}
    /** The volume actuator {@code id}'s controlled pipes carried, as its island booked it, through the tick a view shows; null
     * where none is booked (IslandCoordinator.transported). A read of cached state: it never wakes or advances an island. */
    public IslandCoordinator.Transported transported(long id){owned();Long owner=registry.owner(id);if(owner==null)return null;return runtime.coordinator().transported(owner,id).orElse(null);}
    public InstrumentReading instrumentAt(PhysicalFluidTopology.Position position){owned();var r=at(position).orElse(null);return r==null?null:instrumentPipeReading(r.device().id());}
    /** The accepted mover inlet/outlet, using the same node-to-stream reduction as heat-unit nozzles. */
    public FluidView.Port instrumentMoverPort(long id,boolean outlet){
        owned();Long owner=registry.owner(id);if(owner==null)return null;var snapshot=presentation(owner);var result=snapshot.lastResult().orElse(null);if(result==null)return null;
        var graph=snapshot.graph();for(int i=0;i<graph.pipes().size();i++){var pipe=graph.pipes().get(i);if(graph.reservoirs().get(pipe.first()).id()!=id||!(pipe.control() instanceof FlowControl.Mover||pipe.control() instanceof FlowControl.Turbine))continue;
            var port=HeatPresentation.port(outlet?"outlet":"inlet",graph.reservoirs().get(outlet?pipe.second():pipe.first()),result.averageMassFlows()[i],heatSource(new HashMap<>()));if(outlet)for(var observation:result.observations())if(observation.pipe()==pipe.id())return new FluidView.Port(port.role(),port.temperature(),observation.firstPressure(),port.massFlow(),port.phaseMoles(),port.phaseVolumes());return port;}
        return null;
    }
    /** Accepted inlet/outlet readings for a basin; no inferred temperature or fresh property evaluation. */
    public List<FluidView.Port> instrumentVesselPorts(long id){owned();Long owner=registry.owner(id);if(owner==null)return List.of();var snapshot=presentation(owner);return snapshot.lastResult().isEmpty()?List.of():HeatPresentation.poolPorts(id,snapshot,heatSource(new HashMap<>()));}
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
    final Map<Long,FluidView.Reading> lastLines=new HashMap<>();
    /** The heat presentation's reads, over this bucket's island cache. */
    HeatPresentation.Source heatSource(Map<Long,IslandCoordinator.Snapshot> islands) {
        return new HeatPresentation.Source() {
            @Override public WorldTopologyLedger.Registration registration(long id){return registrations().get(id);}
            @Override public IslandCoordinator.Snapshot island(long device){Long owner=registry.owner(device);return owner==null?null:islands.computeIfAbsent(owner,FluidWorldAuthority.this::presentation);}
            @Override public Lines lines(long side){return lines.get(side);}
            @Override public WorldTopologyLedger.Registration at(PhysicalFluidTopology.Position position){return registry.at(position).orElse(null);}
            @Override public FluidView view(long device){return views.view(device,islands);}
            @Override public double[] weights(){return model.molecularWeights();}
            @Override public double portPressure(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port){return node.state().pressure()+com.wormzjl.icheme.science.fluid.network.PortStreams.portHead(model,node,port,node.state());}
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
    /** The status clause of a held registration beside a device's network: its position, as {@link #heldClause} writes it. */
    public static final String HELD_DEVICE="HELD DEVICE: ";
    public FluidView view(long id){owned();return views.view(id,new HashMap<>());}
    /**
     * Machine {@code control}'s status line {@code status} with its health word {@code word} ({@link MachineHealth#status}), for
     * every machine that wears ({@link MachineHealth#worn}: a liquid mover, a dry screw, owner V6 B, code base review M-02, a
     * centrifugal compressor, N2, and a steam turbine, N4): FAILED first while it stands failed, else LIQUID, LOW FLOW,
     * CAVITATION, GAS, WET GAS or WET EXHAUST last (W10b, W10d, owner decision D37); its health and
     * suction have their meters (the view's Pump). A control that does not wear keeps its line.
     */
    static String withHealth(String status,FlowControl control,String word) {
        if(!MachineHealth.worn(control)||word.isEmpty())return status;
        return word.equals("FAILED")?"FAILED: use a repair kit / "+status:status+" / "+word;
    }
    /** Island {@code island} as it is stored, materialising nothing: what a watch's replay reads (batch 2026-11-10-custom-shader-system). */
    IslandCoordinator.Snapshot storedIsland(long island){return onWorldList(island,runtime.coordinator().observe(island));}
    /** Island {@code island} as a presentation reads it, on the world list (V2 stage 5): every view, display and wire vector
     * stays on the network package's whole list whatever the island carries. */
    IslandCoordinator.Snapshot presentation(long island){return onWorldList(island,runtime.coordinator().presentation(island));}
    private IslandCoordinator.Snapshot onWorldList(long island,IslandCoordinator.Snapshot snapshot){return IslandSets.presented(snapshot,runtime.coordinator().model(island),model);}
    /** {@code islands} caches island reads, so one bucket reads each island once however many of its devices it presents. */
    /**
     * One presented island's lookups for a bucket's views (code base review R2): its nodes and pipes by identity, each node's
     * pipes in order, the interval's pipe histories by pipe and HEAT bookings by node, and the control constraint sets. Built
     * once per island per bucket; every lookup gives what the scan it replaces found (identities are unique in a graph, and
     * sums run in the same order).
     */
    static final class ViewIndex {
        final IslandCoordinator.Snapshot snapshot;
        final Map<Long,Integer> nodes=new HashMap<>(),pipes=new HashMap<>();
        /** Per node index, the pipes that touch it, in ascending order. */
        final List<List<Integer>> incident;
        final Map<Long,List<PipeTransfer>> transfers=new HashMap<>();
        private final Map<Long,Double> heat=new HashMap<>();
        private BitSet dependent,standing;
        ViewIndex(IslandCoordinator.Snapshot snapshot) {
            this.snapshot=snapshot;var graph=snapshot.graph();
            incident=new ArrayList<>(graph.reservoirs().size());
            for(int i=0;i<graph.reservoirs().size();i++){nodes.put(graph.reservoirs().get(i).id(),i);incident.add(new ArrayList<>(2));}
            for(int i=0;i<graph.pipes().size();i++){var p=graph.pipes().get(i);pipes.put(p.id(),i);incident.get(p.first()).add(i);incident.get(p.second()).add(i);}
            snapshot.lastResult().ifPresent(r->{
                for(var t:r.pipeTransfers())transfers.computeIfAbsent(t.pipeId(),k->new ArrayList<>()).add(t);
                // From 0.0, in booking order: the sum the scan made (0.0 + -0.0 is 0.0, as there).
                for(var b:r.boundaries())if(b.kind()==com.wormzjl.icheme.science.fluid.network.ConservativeTransport.BoundaryTransfer.Kind.HEAT)heat.put(b.nodeId(),heat.getOrDefault(b.nodeId(),0.0)+b.totalEnergyJoule());
            });
        }
        /** The pipes touching node {@code node}, or none for -1 (a device not in the graph). */
        List<Integer> incident(int node){return node<0?List.of():incident.get(node);}
        /** The heat node {@code node}'s HEAT boundaries booked over the interval, J (0 for none). */
        double heat(long node){return heat.getOrDefault(node,0.0);}
        BitSet dependent(){if(dependent==null)dependent=ControlConstraints.dependent(snapshot.graph());return dependent;}
        BitSet standing(){if(standing==null)standing=ControlConstraints.standing(snapshot.graph());return standing;}
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
    String deviceLabel(long id) {
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
            var view=views.view(device,islands);
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
                    if(record.spec().open())displays.publishTank(record,level,pos,presented);
                    receiver.acceptFluidView(presented);instruments.publish(id,presented);
                    displays.publishMover(record,level,pos);
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
            if(com.wormzjl.icheme.world.level.block.FluidDeviceBlock.hasSightGlass(level.getBlockState(pos)))displays.publishSightGlass(record,level,pos,presented);
            else displays.forgetSightGlass(id);
            // An in-line mover's state (W10c, owner 2026-11-04): a pump's crackle and churn for its watchers.
            if(record.device().control() instanceof FlowControl.Mover)displays.publishMover(record,level,pos);
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
            var converter=converters==null?Set.<String>of():converters.compounds(port);
            if(still.isEmpty()&&converter.isEmpty())return column;if(column.isEmpty()&&converter.isEmpty())return still;if(column.isEmpty()&&still.isEmpty())return converter;
            var all=new java.util.TreeSet<>(column);all.addAll(still);all.addAll(converter);return all;
        }
        public void refused(UUID event,String reason){refuseEvent(event,reason);}
        public void committed(Set<Long> removed,Set<Long> recompiled) {
            // A removed exchanger takes its heat in transit along (plan section 5.2): its sides leave in one event, keyed by the lower.
            for(long device:removed)topology.removedHeat(runtime.coordinator().removeTransit(PhysicalFluidTopology.sideIdentity(device)));
            unboundBindings.removeAll(removed);publishedMenuData.keySet().removeAll(removed);for(long device:removed)displays.removed(device);
            columns.committed();
            // A batch still whose pot left takes its head's accounts along, booked as destroyed (batch 2026-11-09-batch-distiller).
            for(long device:removed)if(topology.still(device)!=null){topology.removeStill(device);stills.removed(device);}
            // A converter whose vessel left leaves with it (W3); its Forge tank goes with the block. Swept against the registry,
            // not the batch's removals: a converter placed and broken before its registration applied never had an active vessel.
            if(!topology.converters().isEmpty())for(long id:List.copyOf(topology.converters().keySet()))if(!registrations().containsKey(id))converters.remove(id);
            // Every recompiled device's cached snapshot predates the change, and one left in no island (a pipe whose only
            // tank was broken) gets no publication that would mark it: each is marked for its next bucket, which presents
            // its new view (its replacement island, or "no hydraulic boundary"); an opening menu waits for that bucket.
            publishedMenuData.keySet().removeAll(recompiled);displays.recompiled(recompiled);for(long device:recompiled)presentation.markIfLoaded(device);
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
        data.setDirty();var ids=new HashSet<Long>();for(var snapshot:changed){ids.add(snapshot.id());if(snapshot.status().startsWith("HELD")&&!snapshot.status().startsWith(com.wormzjl.icheme.runtime.ProcessSolveJobs.PHASE_HOLD))IChemE.LOGGER.warn("fluid_island={} committed_tick={} status={}",snapshot.id(),snapshot.clock().committedTick(),snapshot.status());}
        // Only loaded devices are marked; their bucket presents them. A publication itself pushes nothing.
        for(long island:ids)for(long physical:registry.members(island))presentation.markIfLoaded(physical);
        if(options.debugChat()&&(lastDebugChat==Long.MIN_VALUE||topology.onlineTick()-lastDebugChat>=20)) {
            changed.stream().filter(s->s.status().startsWith("HELD")).findFirst().ifPresent(s->{
                lastDebugChat=topology.onlineTick();String message="Fluid network "+s.id()+": "+s.status();if(message.length()>180)message=message.substring(0,180)+" (see log)";
                var text=net.minecraft.network.chat.Component.literal(message);for(var player:server.getPlayerList().getPlayers())player.sendSystemMessage(text);
            });
        }
    }
    @Override public void close(){owned();if(closed)return;closed=true;chunks.clear();publishedMenuData.clear();
        // The tanks' glides and the chimneys' states are not cleared at close (as before R9); no watcher list is.
        displays.clearPublished();
        noise.clear();displays.clearWatches();runtime.close();data.setDirty();}

    /**
     * Which players watch which devices of one {@link Display} (the request path R-B of the engine-owned presentation
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
        /** Whether no device is watched ({@link #devices()} is empty), without a copy. */
        public boolean empty(){return byDevice.isEmpty();}
    }
}
