package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.BoundedCpuSolveService;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Hold search by placement-sequence fuzz (phase-ports batch, 2026-09-27). Not a gate: attached to the test source set only
 * through tools/phase-ports-probes/hold-fuzz/hold-fuzz.init.gradle.
 *
 * <p>Each seed builds a small world on a grid (2 to 12 items: tanks, generators of water, nitrogen, methane or a two-phase
 * crude at a random temperature and pressure, voids, pipe runs of a random bore, pumps, compressors, inline filters and
 * pressure control valves, joined by pipe runs from any face, branches making junction pipes), places its blocks one by one
 * in a random order at random online ticks on the world's event path (the real {@link PhysicalRegistry} and
 * {@link IslandCoordinator} with one synchronous worker, run tick by tick as {@code FluidWorldAuthority.tick} runs), and then
 * runs {@code holdFuzz.slices} (120) slices at the 5 s cadence. Every published HELD status (seed, tick, island, committed
 * tick, reason) and every thrown exception is recorded. Everything is drawn from {@code new Random(seed)}: a seed replays
 * alone ({@code -PholdFuzzSeeds=137 -PholdFuzzVerbose=true} prints its blocks and the held island).
 *
 * <p>System properties (the init script forwards the -P switches): {@code holdFuzz.seeds} ("1-300" or "5,17,42"),
 * {@code holdFuzz.threads} (8), {@code holdFuzz.slices} (120), {@code holdFuzz.out} (report file), {@code holdFuzz.verbose},
 * {@code holdFuzz.strict} (fail on any hold or exception).
 */
class HoldFuzzTest {
    static final String DIM="minecraft:overworld";
    static final BoundedCpuSolveService.CancellationToken NEVER=new BoundedCpuSolveService.CancellationToken() {
        public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}
        public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}
    };
    static final MaterialCatalog CATALOG=MaterialCatalog.bundled();
    static final int COMPONENTS=MaterialTestBasis.NETWORK+1,WATER=MaterialTestBasis.NETWORK,NITROGEN=MaterialTestBasis.NITROGEN;
    static final int METHANE=CATALOG.requirePackage("icheme:tjl20_methane_nitrogen").components().indexOf("Methane");
    static final double[] CRUDE=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane_nitrogen").crudeFeed("icheme:tia_juana_light_methane").moleFractions(),COMPONENTS);
    static final ThreadLocal<FluidThermodynamics> MODELS=ThreadLocal.withInitial(FluidTestSupport::networkModel);

    record Block(int x,int y,int z,Kind kind,PhysicalFluidTopology.Direction facing,double bore,FlowControl control,FluidDeviceSpec spec,String fluid) {
        PhysicalFluidTopology.Position position(){return new PhysicalFluidTopology.Position(DIM,x,y,z);}
        String label() {
            String what=switch(kind){case RESERVOIR->"tank";case GENERATOR->"gen["+fluid+" "+Math.round(spec.temperature())+"K "+Math.round(spec.pressure()/1000)+"kPa]";case VOID->"void";case PIPE->"pipe"+bore;
                case PUMP->"pump["+((FlowControl.Pump)control).targetVolumeFlow()+","+Math.round(((FlowControl.Pump)control).maximumAddedPressure()/1000)+"kPa]";
                case COMPRESSOR->"comp["+((FlowControl.Compressor)control).targetVolumeFlow()+","+((FlowControl.Compressor)control).maximumPressureRatio()+"]";
                case VALVE->"valve["+Math.round(((FlowControl.PressureValve)control).targetPressure()/1000)+"kPa]";case FILTER->"filter";default->kind.name().toLowerCase(java.util.Locale.ROOT);};
            return what+"@"+x+","+y+","+z+(kind==Kind.PUMP||kind==Kind.COMPRESSOR||kind==Kind.VALVE||kind==Kind.FILTER?">"+facing:"");
        }
    }
    record Placement(long tick,Block block) {}
    record Held(long tick,long island,long committed,String status) {}
    record Outcome(long seed,List<Placement> placements,List<Held> held,List<String> exceptions,List<String> refused,long millis,long finalTick,String islands) {}

    // ---------------- world generation ----------------

    static final PhysicalFluidTopology.Direction[] DIRECTIONS=PhysicalFluidTopology.Direction.values();
    static int[] step(PhysicalFluidTopology.Direction d){return switch(d){case DOWN->new int[]{0,-1,0};case UP->new int[]{0,1,0};case NORTH->new int[]{0,0,-1};case SOUTH->new int[]{0,0,1};case WEST->new int[]{-1,0,0};case EAST->new int[]{1,0,0};};}
    static PhysicalFluidTopology.Direction opposite(PhysicalFluidTopology.Direction d){return switch(d){case DOWN->PhysicalFluidTopology.Direction.UP;case UP->PhysicalFluidTopology.Direction.DOWN;case NORTH->PhysicalFluidTopology.Direction.SOUTH;case SOUTH->PhysicalFluidTopology.Direction.NORTH;case WEST->PhysicalFluidTopology.Direction.EAST;case EAST->PhysicalFluidTopology.Direction.WEST;};}
    static boolean axial(Kind k){return k==Kind.PUMP||k==Kind.COMPRESSOR||k==Kind.VALVE||k==Kind.FILTER;}
    static boolean boundary(Kind k){return k==Kind.RESERVOIR||k==Kind.GENERATOR||k==Kind.VOID;}
    /** Whether a block at this position connects towards {@code d} (an axial device only along its facing axis). */
    static boolean connects(Block b,PhysicalFluidTopology.Direction d){if(!axial(b.kind))return true;var f=step(b.facing);var s=step(d);return f[0]*s[0]+f[1]*s[1]+f[2]*s[2]!=0;}

    static final double[] BORES={.01,.02,.05,.05,.1};
    /** {@code holdFuzz.defaults}: every block as FluidWorldAuthority.place makes it (0.05 m, default controls, water
     * generators at 298.15 K and 1 atm, nitrogen tanks and voids); the random draws are still made, so a seed's layout and
     * order are the same with and without it. */
    static final boolean DEFAULTS=Boolean.getBoolean("holdFuzz.defaults");
    /** {@code holdFuzz.pipeBetween} (default true): a device never attaches directly to a piece of equipment (see {@link #world}). */
    static final boolean PIPE_BETWEEN=!"false".equals(System.getProperty("holdFuzz.pipeBetween","true"));
    static Block device(Random r,int x,int y,int z,Kind kind,PhysicalFluidTopology.Direction facing,double bore) {
        var drawn=drawn(r,x,y,z,kind,facing,bore);
        if(!DEFAULTS)return drawn;
        FlowControl control=switch(kind){case PUMP->new FlowControl.Pump(.01,500000);case COMPRESSOR->new FlowControl.Compressor(.05,3.0);case VALVE->new FlowControl.PressureValve(200000);default->new FlowControl.Passive();};
        FluidDeviceSpec spec=null;String fluid="";
        if(kind==Kind.GENERATOR){double[] n=new double[COMPONENTS];n[WATER]=1;spec=new FluidDeviceSpec(1,298.15,101325,n);fluid="water";}
        else if(kind==Kind.RESERVOIR||kind==Kind.VOID){spec=drawn.spec;fluid="N2";}
        return new Block(x,y,z,kind,facing,.05,control,spec,fluid);
    }
    static Block drawn(Random r,int x,int y,int z,Kind kind,PhysicalFluidTopology.Direction facing,double bore) {
        FlowControl control=new FlowControl.Passive();FluidDeviceSpec spec=null;String fluid="";
        switch(kind) {
            case PUMP->control=r.nextBoolean()?new FlowControl.Pump(.01,500000):new FlowControl.Pump(new double[]{.001,.01,.05}[r.nextInt(3)],new double[]{200000,500000,1000000}[r.nextInt(3)]);
            case COMPRESSOR->control=r.nextBoolean()?new FlowControl.Compressor(.05,3.0):new FlowControl.Compressor(new double[]{.01,.05,.2}[r.nextInt(3)],new double[]{1.5,3,6}[r.nextInt(3)]);
            case VALVE->control=new FlowControl.PressureValve(r.nextBoolean()?200000:new double[]{120000,150000,400000}[r.nextInt(3)]);
            case RESERVOIR,VOID->{double[] n=new double[COMPONENTS];n[NITROGEN]=1;spec=new FluidDeviceSpec(1,298.15,101325,n);fluid="N2";}
            case GENERATOR->{
                double[] n=new double[COMPONENTS];double t,p;
                switch(r.nextInt(4)) {
                    case 0->{n[WATER]=1;fluid="water";t=280+r.nextDouble()*80;p=101325+r.nextDouble()*300000;}
                    case 1->{n[NITROGEN]=1;fluid="N2";t=250+r.nextDouble()*150;p=60000+r.nextDouble()*440000;}
                    case 2->{n[METHANE]=1;fluid="CH4";t=250+r.nextDouble()*150;p=60000+r.nextDouble()*440000;}
                    default->{n=CRUDE.clone();fluid="crude";t=290+r.nextDouble()*110;p=101325+r.nextDouble()*300000;}
                }
                spec=new FluidDeviceSpec(1,t,p,n);
            }
            default->{}
        }
        return new Block(x,y,z,kind,facing,bore,control,spec,fluid);
    }
    static Kind item(Random r) {
        int k=r.nextInt(16);
        return k<2?Kind.RESERVOIR:k<4?Kind.GENERATOR:k<6?Kind.VOID:k<9?Kind.PIPE:k<11?Kind.PUMP:k<12?Kind.COMPRESSOR:k<14?Kind.FILTER:Kind.VALVE;
    }
    /** The blocks of one seed's world: a connected growth of 2 to 12 items from the origin. */
    static List<Block> world(Random r) {
        var at=new LinkedHashMap<List<Integer>,Block>();int items=2+r.nextInt(11);
        for(int item=0;item<items;item++) {
            Kind kind=item(r);double bore=BORES[r.nextInt(BORES.length)];
            if(at.isEmpty()){var facing=DIRECTIONS[2+r.nextInt(4)];put(at,kind==Kind.PIPE?device(r,0,0,0,Kind.PIPE,PhysicalFluidTopology.Direction.NORTH,bore):device(r,0,0,0,kind,facing,bore));continue;}
            for(int attempt=0;attempt<20;attempt++) {
                var blocks=new ArrayList<>(at.values());var anchor=blocks.get(r.nextInt(blocks.size()));
                var d=DIRECTIONS[r.nextInt(6)];if(!connects(anchor,d))continue;
                int length=kind==Kind.PIPE?1+r.nextInt(4):r.nextInt(3);var s=step(d);
                // holdFuzz.pipeBetween (default on, 2026-09-28): only pipes connect (decision D20), so a device drawn directly
                // against a piece of equipment gets one pipe block between them; the draws are unchanged, so a seed keeps its
                // layout wherever its devices already hang off pipes.
                if(PIPE_BETWEEN&&kind!=Kind.PIPE&&length==0&&anchor.kind!=Kind.PIPE)length=1;
                int x=anchor.x+s[0],y=anchor.y+s[1],z=anchor.z+s[2];boolean free=true;var cells=new ArrayList<int[]>();
                for(int i=0;i<=length;i++){if(at.containsKey(List.of(x+i*s[0],y+i*s[1],z+i*s[2]))){free=false;break;}cells.add(new int[]{x+i*s[0],y+i*s[1],z+i*s[2]});}
                if(!free)continue;
                // A pipe item is `length` + 1 pipes; any other item is `length` pipes and the device at the far end.
                for(int i=0;i<cells.size();i++) {
                    var c=cells.get(i);boolean last=i==cells.size()-1;
                    if(last&&kind!=Kind.PIPE)put(at,device(r,c[0],c[1],c[2],kind,axial(kind)?(r.nextBoolean()?d:opposite(d)):PhysicalFluidTopology.Direction.NORTH,bore));
                    else put(at,device(r,c[0],c[1],c[2],Kind.PIPE,PhysicalFluidTopology.Direction.NORTH,bore));
                }
                break;
            }
        }
        return List.copyOf(at.values());
    }
    static void put(Map<List<Integer>,Block> at,Block b){at.put(List.of(b.x,b.y,b.z),b);}
    /** A random order and random online ticks: several blocks in one tick, a few ticks apart, or a slice or more apart. */
    static List<Placement> placements(Random r,List<Block> blocks) {
        var order=new ArrayList<>(blocks);Collections.shuffle(order,r);var list=new ArrayList<Placement>();long tick=100;
        for(var b:order) {
            int k=r.nextInt(10);tick+=k<2?0:k<7?1+r.nextInt(60):60+r.nextInt(340);
            list.add(new Placement(tick,b));
        }
        return list;
    }

    // ---------------- the world ----------------

    static final class World {
        final FluidThermodynamics model;
        final WorldTopologyLedger ledger=new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(CATALOG));
        final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        final Map<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        long requests;
        final List<Held> held=new ArrayList<>();
        final IslandCoordinator coordinator;
        final PhysicalRegistry registry;
        World(FluidThermodynamics model) {
            this.model=model;
            coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 1-attempts.size();}
                public long nextRequestId(){return ++requests;}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
                public void cancel(long request){}
            },changed->{for(var s:changed)if(s.status().startsWith("HELD"))held.add(new Held(ledger.onlineTick(),s.id(),s.clock().committedTick(),s.status()));},
                    ()->0L,new IslandCoordinator.Settings(Long.getLong("holdFuzz.wallNanos",2_000_000_000L),Long.getLong("holdFuzz.softNanos",1_500_000_000L),64,false,100,CertificatePolicy.defaults()),ledger::onlineTick);
            registry=new PhysicalRegistry(ledger,model,InlineFilter.empty(),new PhysicalRegistry.Host() {
                public IslandCoordinator coordinator(){return coordinator;}
                public void topology(String dimension,Set<UUID> events,Set<Long> affected,List<IslandCoordinator.Replacement> replacements,long committed,long online,
                                     Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,InlineFilter> releasedFilters,Runnable commit) {
                    coordinator.topology(events,affected,replacements,model,committed,online,additions,removals,releasedFilters,commit);
                }
                public boolean acceptsRecovery(UUID player){return true;}
                public void refused(UUID event,String reason){}
                public void committed(Set<Long> removed,Set<Long> recompiled){}
            });
            registry.load();
        }
        /**
         * As FluidWorldAuthority.place, with the block's own control, bore and spec (a player's edits made at placement).
         * The engine has no portless mover since 0.16.2 (batch 2026-10-28-old-mover-removal): a drawn pump or compressor
         * goes in line on a straight pipe joined along its facing, as the fitting item installs it, and an upright one,
         * which no in-line mover fits, is that straight pipe without its mover. The draws are unchanged.
         */
        void place(Block b) {
            long id=ledger.nextIdentity();
            var geometry=new PipeResistance.Geometry(1,b.bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
            PhysicalFluidTopology.Device device;
            if(b.kind==Kind.PUMP||b.kind==Kind.COMPRESSOR) {
                int axis=1<<b.facing.ordinal()|1<<opposite(b.facing).ordinal();
                device=new PhysicalFluidTopology.Device(id,b.position(),Kind.PIPE,b.facing,geometry,b.facing.y==0?b.control:new FlowControl.Passive()).withJoints(axis);
            }
            else device=new PhysicalFluidTopology.Device(id,b.position(),b.kind,b.facing,geometry,b.control);
            var spec=b.spec!=null?b.spec:FluidDeviceSpec.nitrogen(CATALOG);
            registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,0))),id+1,null);
        }
        void tick() {
            ledger.tick();coordinator.tick();
            if(ledger.hasPendingEvents()){registry.applyPending();coordinator.pumpIfUseful();}
            for(long request:List.copyOf(attempts.keySet())) {
                var attempt=attempts.remove(request);var command=commands.remove(request);
                var result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(NEVER);
                coordinator.completed(attempt,Optional.of(result));
            }
            coordinator.pump();
        }
        void runTo(long tick){while(ledger.onlineTick()<tick)tick();}
        String describe(long island) {
            var s=coordinator.observe(island);var b=new StringBuilder("island "+island+" committed="+s.clock().committedTick()+" status="+s.status()+"\n");
            var g=s.graph();
            for(int i=0;i<g.reservoirs().size();i++){var n=g.reservoirs().get(i);var st=n.state();
                b.append("    node ").append(i).append(" id=").append(n.id()).append(' ').append(n.kind()).append(" z=").append(n.elevation()).append(" P=").append(st.pressure()).append(" T=").append(st.temperature()).append(" m=").append(st.mass()).append(" liq=").append(st.liquidVolume()).append(" water=").append(st.waterVolume()).append(" vap=").append(st.vaporVolume()).append('\n');}
            for(int i=0;i<g.pipes().size();i++){var p=g.pipes().get(i);
                b.append("    pipe ").append(i).append(' ').append(p.first()).append("->").append(p.second()).append(' ').append(p.control()).append(" d=").append(p.minimumArea()).append(" blocked=").append(p.blockedDirections()).append(" filter=").append(p.filter()!=null).append(" ports=").append(p.firstPort()).append('/').append(p.secondPort()).append('\n');}
            s.lastResult().ifPresent(r->b.append("    last modes=").append(r.endpointModes()).append(" flows=").append(Arrays.toString(r.averageMassFlows())).append('\n'));
            return b.toString();
        }
    }

    static Outcome run(long seed,int slices,boolean verbose) {
        var r=new Random(seed);var blocks=world(r);var placements=placements(r,blocks);
        var exceptions=new ArrayList<String>();var refused=new ArrayList<String>();long started=System.nanoTime();
        World w=null;String islands="";
        try {
            w=new World(MODELS.get());
            for(var p:placements) {
                w.runTo(p.tick);
                try{w.place(p.block);}
                catch(IllegalArgumentException invalid){refused.add(p.block.label()+": "+invalid.getMessage());}
            }
            long end=placements.getLast().tick+100L*slices;
            int reported=0;
            while(w.ledger.onlineTick()<end) {
                w.tick();
                if(verbose&&w.held.size()>reported){for(var h:w.held.subList(reported,w.held.size()))System.out.println("FUZZ_VERBOSE_HELD seed="+seed+" "+h+"\n"+w.describe(h.island()));reported=w.held.size();}
            }
            var b=new StringBuilder();for(var s:w.coordinator.observe())b.append(s.id()).append(':').append(s.status().length()>60?s.status().substring(0,60):s.status()).append(';');islands=b.toString();
            if(verbose)for(var s:w.coordinator.observe())System.out.println("FUZZ_VERBOSE_END seed="+seed+" "+w.describe(s.id()));
        } catch(Throwable failure) {
            var sw=new StringWriter();failure.printStackTrace(new PrintWriter(sw));var lines=sw.toString().lines().limit(12).toList();
            exceptions.add(String.join(" | ",lines));
        }
        return new Outcome(seed,placements,w==null?List.of():List.copyOf(w.held),exceptions,refused,(System.nanoTime()-started)/1_000_000,w==null?-1:w.ledger.onlineTick(),islands);
    }

    /** The reproduction of one hold class (repro/): the seed's world, replayed alone, must commit without a hold or an
     * exception. Fails on 65583a38 for every class seed named in the review's class table. */
    static void assertSeedRunsClean(long seed) {
        var o=run(seed,Integer.getInteger("holdFuzz.slices",120),Boolean.getBoolean("holdFuzz.verbose"));
        var layout=new StringBuilder();for(var p:o.placements())layout.append(p.tick()).append(':').append(p.block().label()).append(' ');
        System.out.println("REPRO seed="+seed+" layout "+layout);
        for(var h:o.held())System.out.println("REPRO seed="+seed+" "+h);
        assertTrue(o.exceptions().isEmpty(),"seed "+seed+" threw: "+o.exceptions());
        assertTrue(o.held().isEmpty(),"seed "+seed+" held "+o.held().size()+" times, first: "+(o.held().isEmpty()?"":o.held().getFirst()));
    }

    // ---------------- classification ----------------

    static String classify(String status) {
        // A phase hold, by its prefix since 2026-10-17 or its old one: literals, so the harness compiles against either code.
        if(status.startsWith("HELD (phases)")||status.startsWith("HELD (thermo domain)"))return "c:phase-hold";
        if(status.contains("Singular Newton Jacobian")&&status.contains("active-set pass=0")&&!status.contains("Substep")&&!status.contains("Interval"))return "d:singular-pass0";
        if(status.startsWith("HELD: Interval substep limit")&&status.matches("(?s).*Backward-Euler state change #=\\d{3,}.*"))return "a:state-change-lock";
        if(status.contains("Matrix values must be finite")||status.contains("No finite Jacobian"))return "b:trace-underflow";
        return "e:new";
    }
    static List<Long> seeds(String spec) {
        var list=new ArrayList<Long>();
        for(var part:spec.split(",")){part=part.trim();if(part.isEmpty())continue;int dash=part.indexOf('-',1);if(dash>0){long a=Long.parseLong(part.substring(0,dash)),b=Long.parseLong(part.substring(dash+1));for(long s=a;s<=b;s++)list.add(s);}else list.add(Long.parseLong(part));}
        return list;
    }

    @Test void placementSequenceFuzz() throws Exception {
        var seeds=seeds(System.getProperty("holdFuzz.seeds","1-20"));int threads=Integer.getInteger("holdFuzz.threads",8);int slices=Integer.getInteger("holdFuzz.slices",120);
        boolean verbose=Boolean.getBoolean("holdFuzz.verbose");String out=System.getProperty("holdFuzz.out","build/hold-fuzz/report.txt");
        // holdFuzz.counters (2026-10-12): the solver's own counters over the whole campaign, so campaigns that ran beside
        // other load are compared by work done, not by wall time.
        boolean counters=Boolean.getBoolean("holdFuzz.counters");
        if(counters){com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.reset();com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.ENABLED=true;}
        long started=System.nanoTime();
        var pool=Executors.newFixedThreadPool(threads);var futures=new ArrayList<Future<Outcome>>();
        // With one worker the counters are attributed per seed (t12, 2026-10-13): a COUNTERS_SEED line per seed.
        var perSeed=new ConcurrentHashMap<Long,String>();boolean attribute=counters&&threads==1;
        for(long seed:seeds)futures.add(pool.submit(()->{
            if(!attribute)return run(seed,slices,verbose);
            var names=List.of("newtonSolves","newtonIterations","equationGateRejections","stepAttempts","phaseSeeds");
            var a=com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.sample();var o=run(seed,slices,verbose);
            var b=com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.sample();var line=new StringBuilder();
            for(var name:names)line.append(' ').append(name).append('=').append(b.value(name)-a.value(name));
            perSeed.put(seed,line.toString());return o;}));
        var outcomes=new ArrayList<Outcome>();for(var f:futures)outcomes.add(f.get());
        pool.shutdown();long wall=(System.nanoTime()-started)/1_000_000;
        var report=new StringBuilder();var classes=new TreeMap<String,List<Long>>();int clean=0;long cpu=0;
        for(var o:outcomes) {
            cpu+=o.millis;var seedClasses=new TreeSet<String>();
            for(var h:o.held)seedClasses.add(classify(h.status()));
            if(!o.exceptions.isEmpty())seedClasses.add("x:exception");
            if(seedClasses.isEmpty())clean++;
            for(var c:seedClasses)classes.computeIfAbsent(c,k->new ArrayList<>()).add(o.seed);
            report.append("SEED ").append(o.seed).append(" blocks=").append(o.placements.size()).append(" ms=").append(o.millis).append(" end=").append(o.finalTick)
                    .append(" holds=").append(o.held.size()).append(" classes=").append(seedClasses).append(" refused=").append(o.refused.size()).append(" islands=").append(o.islands).append('\n');
            if(perSeed.containsKey(o.seed))report.append("  COUNTERS_SEED").append(perSeed.get(o.seed)).append('\n');
            report.append("  layout ");for(var p:o.placements)report.append(p.tick).append(':').append(p.block.label()).append(' ');report.append('\n');
            for(var h:o.held)report.append("  HELD tick=").append(h.tick()).append(" island=").append(h.island()).append(" committed=").append(h.committed()).append(" class=").append(classify(h.status())).append(" status=").append(h.status()).append('\n');
            for(var e:o.exceptions)report.append("  EXCEPTION ").append(e).append('\n');
            for(var e:o.refused)report.append("  REFUSED ").append(e).append('\n');
        }
        report.append("SUMMARY seeds=").append(outcomes.size()).append(" clean=").append(clean).append(" threads=").append(threads).append(" slices=").append(slices)
                .append(" wallMs=").append(wall).append(" seedMsSum=").append(cpu).append('\n');
        if(counters) {
            com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.ENABLED=false;
            var sample=com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.sample();report.append("COUNTERS");
            for(var name:List.of("implicitSolves","activeSetPasses","newtonSolves","newtonIterations","jacobianBuilds","residualEvaluations","stepAttempts","stepAttemptsAccepted","equationGateRejections","reconstructCalls","restIntervals","restStops","phaseSeeds","phaseRefusals","balanceStarts","balanceIterations","balanceSweeps","balanceLaws","balanceUnconverged","balanceFlashes","newtonSolvesPreconditioned","newtonIterationsPreconditioned","newtonIterationsFresh","newtonIterationsStale","newtonStepEvaluationsFresh","newtonStepEvaluationsStale","newtonRefreshesStalled","newtonRefreshesAged","newtonRefreshesFailed","newtonBacktracks"))
                // restIntervals and restStops exist from t16 (2026-10-13) on; older code under test has no such counter.
                try{report.append(' ').append(name).append('=').append(sample.value(name));}catch(IllegalArgumentException absent){}
            report.append('\n');
        }
        classes.forEach((c,list)->report.append("CLASS ").append(c).append(" seeds=").append(list.size()).append(" smallest=").append(list.stream().min(Comparator.naturalOrder()).orElseThrow()).append(" list=").append(list).append('\n'));
        var path=Path.of(out);Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,report.toString(),StandardCharsets.UTF_8);
        System.out.println(report.toString().lines().filter(l->l.startsWith("SUMMARY")||l.startsWith("COUNTERS")||l.startsWith("CLASS")||seeds.size()<=5).reduce("",(a,b)->a+b+"\n"));
        if(Boolean.getBoolean("holdFuzz.strict"))assertEquals(outcomes.size(),clean,"seeds with a hold or an exception: "+classes);
    }
}
