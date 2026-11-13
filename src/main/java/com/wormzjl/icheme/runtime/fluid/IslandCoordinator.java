package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.runtime.ProcessSolveJobs;
import com.wormzjl.icheme.runtime.fluid.ExchangerCoupling.Coupled;
import com.wormzjl.icheme.runtime.fluid.ExchangerCoupling.SideRecord;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Server-thread authority over island clocks and atomic result publication. Workers receive only commands; a round
 * contains at most the available dispatch capacity, and the dispatcher must deliver terminal callbacks asynchronously
 * on this coordinator's owning thread. Scheduling is driven by deadlines, dependency changes and completions, never by
 * visiting every island on every tick: each island is ready (its next slice can be dispatched now), holds one deadline
 * in the {@link IslandScheduler} for the epoch tick at which time alone makes it ready, or waits for an event that
 * re-examines it (a completion, a fence change, a property resume); every change to what decides readiness re-examines
 * exactly the island it changed. An island whose solved intervals repeat is certified ({@link IslandCertificate}): never
 * ready, its solver caches released, one deadline at its certificate horizon; its committed time is materialised on
 * demand by the identity or by scaled replay of its last solved interval, never past the online clock, a fence or a
 * hold. {@link #register} restores a saved certificate while its signature holds. Every snapshot carries a payload
 * generation, a JVM-wide unique number that changes whenever anything a checkpoint payload records about the island
 * changes and never when a certified island is only materialised, so a checkpoint can keep an encoded payload while
 * its island's generation stands.
 */
public final class IslandCoordinator {
    public interface Dispatcher {
        default void demand(int eligibleOwners) {}
        /** Ticks until the shared worker allocator would apply a lower-demand shrink when observed again, or a
         * negative value when none is pending. Answered with an allocator deadline instead of per-tick polling. */
        default long demandShrinkDelay(){return -1;}
        int availableWorkers();
        long nextRequestId();
        boolean submit(Attempt attempt, ProcessSolveJobs.FluidIslandCommand command);
        void cancel(long requestId);
    }
    @FunctionalInterface public interface Publisher {void published(List<Snapshot> changed);}
    /**
     * {@code certificates} is the rest and steady-flow policy. The constructors without one keep the scheduling
     * of an island that solves every interval (certificates off); {@link #defaults()} and the world use the
     * configured policy, certificates on. {@code hardBudgetNanos} is a job's wall budget; {@code softBudgetNanos} is
     * only the adaptive cadence's load measure (three CPU-bound intervals at or over it lengthen the cadence, ten
     * under a quarter of it shorten it, {@link IslandClock#performanceSample}): no job is cut at it since owner
     * decision D37 (S2) removed the approximate fallback it used to start.
     */
    /** {@code time} is the server's time factor ({@link SimulationTime}): every island's model carries the same one. */
    public record Settings(long hardBudgetNanos, long softBudgetNanos, int maximumDispatchesPerTick, boolean adaptive,int initialCadenceTicks,CertificatePolicy certificates,SimulationTime time) {
        public Settings(long hardBudgetNanos,long softBudgetNanos,int maximumDispatchesPerTick,boolean adaptive,int initialCadenceTicks,CertificatePolicy certificates){this(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,initialCadenceTicks,certificates,SimulationTime.ONLINE);}
        public Settings(long hardBudgetNanos,long softBudgetNanos,int maximumDispatchesPerTick,boolean adaptive){this(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,100);}
        public Settings(long hardBudgetNanos,long softBudgetNanos,int maximumDispatchesPerTick,boolean adaptive,int initialCadenceTicks){this(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,initialCadenceTicks,CertificatePolicy.disabled());}
        public Settings {
            if(hardBudgetNanos<=0||softBudgetNanos<=0||softBudgetNanos>=hardBudgetNanos||maximumDispatchesPerTick<1||initialCadenceTicks<20||initialCadenceTicks>400)
                throw new IllegalArgumentException("Invalid coordinator settings");
            Objects.requireNonNull(certificates);Objects.requireNonNull(time);
        }
        public static Settings defaults(){return new Settings(2_000_000_000L,1_500_000_000L,64,true,100,CertificatePolicy.defaults());}
        public Settings withCertificates(CertificatePolicy policy){return new Settings(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,initialCadenceTicks,policy,time);}
        public Settings withTime(SimulationTime value){return new Settings(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,initialCadenceTicks,certificates,value);}
    }
    public record Attempt(long islandId,long revision,IslandClock.Slice slice) {
        public Attempt {if(islandId<=0||revision<0)throw new IllegalArgumentException("Invalid attempt identity");Objects.requireNonNull(slice);}
    }
    /** How a publication advanced its island: by a solve, or by replaying its certificate (the identity at drift 0). */
    public enum Advance {SOLVED,REPLAYED}
    /** Queue debt and publication latency remain separate from the worker's own wall/CPU time. */
    public record Metrics(long sequence,long startTick,long endTick,long dispatchedAtTick,long publishedAtTick,
                          long workerNanos,long workerCpuNanos,long dispatchToPublicationNanos,boolean accepted,Advance advance) {
        public Metrics(long sequence,long startTick,long endTick,long dispatchedAtTick,long publishedAtTick,long workerNanos,long workerCpuNanos,long dispatchToPublicationNanos,boolean accepted) {
            this(sequence,startTick,endTick,dispatchedAtTick,publishedAtTick,workerNanos,workerCpuNanos,dispatchToPublicationNanos,accepted,Advance.SOLVED);
        }
        public Metrics {Objects.requireNonNull(advance);}
    }
    /**
     * A certified island: its certificate's per-interval change d (zero at rest), the tick it first certified
     * (renewals keep it), the base of the current certificate, the last tick replay may reach, the largest flow it
     * keeps replaying, and the certificate as a
     * checkpoint keeps it. {@code saved} is empty while a property hold freezes the island: resume discards every
     * certificate, so one saved during a hold is not kept either, and the island restarts awake from the held state.
     */
    public record Certified(double drift,long sinceTick,long baseTick,long horizonTick,double largestFlow,Optional<IslandCertificate.Saved> saved) {
        public Certified {
            Objects.requireNonNull(saved);if(!(drift>=0)||drift==Double.POSITIVE_INFINITY)throw new IllegalArgumentException("Invalid certificate drift "+drift);
            if(saved.isPresent()){var s=saved.orElseThrow();if(s.sinceTick()!=sinceTick||s.baseTick()!=baseTick||s.horizonTick()!=horizonTick)throw new IllegalArgumentException("Saved certificate disagrees with its island");}
        }
        @Override public String toString(){return "Certified[drift "+drift+", since "+sinceTick+", base "+baseTick+", horizon "+horizonTick+", largest flow "+largestFlow+(saved.isPresent()?"":", held")+"]";}
        /** Equal when they say the same: the saved form is compared by its interval's ticks and its signature, since the
         * graphs and results it carries have no value equality of their own. */
        @Override public boolean equals(Object other) {
            return other instanceof Certified c&&Double.doubleToLongBits(drift)==Double.doubleToLongBits(c.drift)&&sinceTick==c.sinceTick&&baseTick==c.baseTick&&horizonTick==c.horizonTick
                    &&Double.doubleToLongBits(largestFlow)==Double.doubleToLongBits(c.largestFlow)&&saved.map(Certified::identity).equals(c.saved.map(Certified::identity));
        }
        @Override public int hashCode(){return Objects.hash(drift,sinceTick,baseTick,horizonTick,largestFlow,saved.map(Certified::identity));}
        private static List<Object> identity(IslandCertificate.Saved s){return List.of(s.interval().startTick(),s.interval().endTick(),s.signature());}
    }
    public record Snapshot(long id,long revision,PassiveNetwork graph,IslandClock.Snapshot clock,
                           Optional<PassiveIntervalSolver.Result> lastResult,String status,Map<UUID,Long> fences,Optional<Certified> certificate,long payloadGeneration) {
        public Snapshot(long id,long revision,PassiveNetwork graph,IslandClock.Snapshot clock,Optional<PassiveIntervalSolver.Result> lastResult,String status) {
            this(id,revision,graph,clock,lastResult,status,Map.of());
        }
        public Snapshot(long id,long revision,PassiveNetwork graph,IslandClock.Snapshot clock,Optional<PassiveIntervalSolver.Result> lastResult,String status,Map<UUID,Long> fences) {
            this(id,revision,graph,clock,lastResult,status,fences,Optional.empty());
        }
        /** A snapshot with no payload generation (0): a checkpoint encodes it every time and never caches it. */
        public Snapshot(long id,long revision,PassiveNetwork graph,IslandClock.Snapshot clock,Optional<PassiveIntervalSolver.Result> lastResult,String status,Map<UUID,Long> fences,Optional<Certified> certificate) {
            this(id,revision,graph,clock,lastResult,status,fences,certificate,0);
        }
        public Snapshot {
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandSnapshots);
            if(id<=0||revision<0||payloadGeneration<0)throw new IllegalArgumentException("Invalid island identity");
            Objects.requireNonNull(graph);Objects.requireNonNull(clock);Objects.requireNonNull(lastResult);Objects.requireNonNull(status);Objects.requireNonNull(certificate);
            fences=Map.copyOf(fences);
            if(fences.values().stream().anyMatch(t->t<clock.committedTick()))throw new IllegalArgumentException("Persisted fence precedes state");
            if(certificate.isPresent()&&(certificate.orElseThrow().baseTick()>clock.committedTick()||certificate.orElseThrow().horizonTick()<clock.committedTick()))
                throw new IllegalArgumentException("Certified island "+id+" committed at "+clock.committedTick()+" outside its certificate window ["+certificate.orElseThrow().baseTick()+", "+certificate.orElseThrow().horizonTick()+"]");
        }
    }
    /** Nominal online tick length; converts the wall-clock round budget into a first deadline. */
    private static final long NOMINAL_TICK_NANOS=50_000_000L;
    /** Test and GameTest runs set this to re-derive every island's readiness and deadline from scratch at each
     * pump and tick and fail on any difference from the incrementally maintained schedule. */
    private static final boolean VERIFY=Boolean.getBoolean("icheme.fluid.scheduler.verify");
    /** Payload generations are unique in the JVM, so a checkpoint cache can never mistake one island object's
     * payload for another's that happens to share its identity, revision and count of changes. */
    private static final java.util.concurrent.atomic.AtomicLong PAYLOAD_GENERATIONS=new java.util.concurrent.atomic.AtomicLong();
    /** A new payload generation, for a snapshot decoded from a checkpoint: registered, the island keeps it. */
    static long freshPayloadGeneration(){return PAYLOAD_GENERATIONS.incrementAndGet();}
    static final class Island {
        final long id;
        long revision;
        final FluidThermodynamics model;
        PassiveNetwork graph;
        final IslandClock clock;
        Optional<PassiveIntervalSolver.Result> lastResult;
        final PipePresentation.SpeedWindow pipeSpeed = new PipePresentation.SpeedWindow();
        String status;
        // The data-range notice of the last accepted interval (DataRangeNotices.line, empty for none): an accepted or certified
        // island's status line carries it after its own words, and a saved status line with it, which restores it.
        String notice="";
        Metrics metrics;
        boolean suspended;
        // Ephemeral cost hint, not material state. A restart
        // may rediscover the hint; saved inventory, cadence, debt and fences stay authoritative.
        int maximumSliceTicks=Integer.MAX_VALUE;
        // Holds in a row at the one-tick slice, for the retry wait; see {@link #hold}. Ephemeral like the slice hint.
        int holdStreak;
        // The phase hold (a phase set the network cannot carry; leaving a data range is never one, owner rule of
        // 2026-10-16) the last held attempt failed on, awaiting its fresh-solver retry, and whether that
        // retry reproduced it (the island then waits for an input change); cleared by an accepted interval or an input
        // change. With the last warned hold's key and online tick, for the rate limit on the log. Ephemeral.
        com.wormzjl.icheme.science.fluid.thermo.ThermoHold phaseHold;
        boolean phaseParked;
        // The last attempt that closed was held (not accepted): a topology event fenced on the island is accepted at its
        // committed tick instead of waiting for a solve (acceptAtFence). Cleared by an accepted interval. Ephemeral.
        boolean held;
        // A capability limit the island cannot run past (V2: more compounds than an island carries), set when it is formed:
        // it solves nothing and accepts the next topology event at its fence, which replaces it. Null for none.
        String capability;
        String warnedHoldKey;
        long warnedHoldAt=Long.MIN_VALUE;
        // Ephemeral solver caches for this island's jobs; replaced, never mutated, on a revision
        // bump, so a job still running under the old revision keeps its own handle.
        RetainedSolver retained=new RetainedSolver();
        final Map<UUID,Long> fences=new HashMap<>();
        // Scheduling state, never saved: attempts still owning this island (running, or closed but not yet
        // terminally drained), whether it is ready, and its one live deadline with that entry's generation.
        int pendingEntries;
        boolean ready;
        long deadline=Long.MAX_VALUE,generation;
        // Certificate state: the certificate while certified and the tick the island first certified (both saved by a
        // checkpoint), and in memory only the one being revalidated by the slice solved at its horizon, the last
        // qualifying interval with the length of the stationary streak it ends, and the online tick a property hold
        // caps materialisation at (a certificate saved during a hold is not kept).
        IslandCertificate certificate,revalidating;
        long certifiedSince=-1,holdTick=Long.MAX_VALUE;
        IslandCertificate.Summary lastInterval;
        int streak;
        // Why the last closed interval did not certify the island (null once it did), and what the last comparison
        // of two consecutive solved intervals measured: diagnostics only, never read by a decision.
        String refusal;
        Evidence evidence;
        // The certificate's validity signature, made once when it is issued or restored; and the payload generation.
        IslandCertificate.Signature signature;
        long payloadGeneration;
        // The connection ids of its coupled exchanger sides (plan section 5.2): fixed for the island, whose connections a
        // commit never changes (validCandidate).
        final long[] sides;
        /** An island registered from a snapshot that carries a payload generation (a loaded checkpoint's) keeps it, so
         * the checkpoint store's unit of that snapshot stays in place until something it records changes. */
        Island(Snapshot saved,FluidThermodynamics model,LongSupplier epoch) {
            id=saved.id;revision=saved.revision;this.model=Objects.requireNonNull(model);graph=saved.graph;sides=ExchangerCoupling.coupledSides(graph);
            clock=new IslandClock(saved.clock,epoch);lastResult=saved.lastResult;status=saved.status;fences.putAll(saved.fences);fencesChanged();
            notice=releaseLine(saved.lastResult,model)+com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices.suffix(saved.status);
            payloadGeneration=saved.payloadGeneration!=0?saved.payloadGeneration:PAYLOAD_GENERATIONS.incrementAndGet();
        }
        Snapshot snapshot(){return new Snapshot(id,revision,graph,clock.snapshot(),lastResult,
                !suspended&&!clock.busy()&&fence()==clock.committedTick()?"WAITING: event alignment":status,frozenFences(),
                certificate==null?Optional.empty():Optional.of(new Certified(certificate.drift(),certifiedSince,certificate.baseTick(),certificate.horizonTick(),certificate.largestFlow(),
                        suspended?Optional.empty():Optional.of(new IslandCertificate.Saved(certifiedSince,certificate.horizonTick(),certificate.interval(),signature)))),
                payloadGeneration);}
        /** Something a checkpoint payload records changed: the next checkpoint encodes this island afresh. */
        void touch(){payloadGeneration=PAYLOAD_GENERATIONS.incrementAndGet();}
        // The nearest fence and an immutable copy of the fences, kept as they change (code base review R22): the scheduler
        // reads the minimum at every readiness test, and a snapshot shares the copy until a fence moves.
        long nearestFence=Long.MAX_VALUE;
        Map<UUID,Long> frozen=Map.of();
        long fence(){return nearestFence;}
        Map<UUID,Long> frozenFences(){if(frozen==null)frozen=Map.copyOf(fences);return frozen;}
        void putFence(UUID event,long tick){fences.put(event,tick);fencesChanged();}
        void removeFence(UUID event){if(fences.remove(event)!=null)fencesChanged();}
        void fencesChanged(){long min=Long.MAX_VALUE;for(long t:fences.values())min=Math.min(min,t);nearestFence=min;frozen=null;}
    }
    static final class Pending {
        private PassiveNetwork dispatched;
        final Attempt attempt;
        private ProcessSolveJobs.FluidIslandSolveResult result;
        /** Why a terminal completion brought no result (the worker's failure, a cancellation), or null where it did not say. */
        private String failure;
        boolean terminal,closed,lateResult;
        private final long dispatchedAtTick,admittedNanos;
        // What the slice read of each coupled side's other side at its dispatch (plan section 5.2).
        private Map<Long,ExchangerRelay.Read> reads=Map.of();
        private Pending(Attempt attempt,long dispatchedAtTick,long admittedNanos){this.attempt=attempt;this.dispatchedAtTick=dispatchedAtTick;this.admittedNanos=admittedNanos;}
    }
    private final Thread owner=Thread.currentThread();
    private final Dispatcher dispatcher;
    final Publisher publisher;
    private final LongSupplier nanoClock;
    final Settings settings;
    final LongSupplier epoch;
    private final boolean ownsEpoch;
    private long ownEpoch;
    final Map<Long,Island> islands=new LinkedHashMap<>();
    final Map<Long,Pending> pending=new HashMap<>();
    final IslandScheduler.FairQueue ready=new IslandScheduler.FairQueue();
    private final IslandScheduler scheduler=new IslandScheduler();
    /** Event fences by event: which islands hold a fence for it, so an event finds its owners without a scan. */
    final Map<UUID,Set<Long>> fenceOwners=new HashMap<>();
    /** Owned finite stock by reservoir identity: which island holds each reservoir, so a topology change can refuse
     * constructed stock that another island already holds without a scan of every island. */
    final Map<Long,Long> stockOwners=new HashMap<>();
    /** Each coupled exchanger side's relay state by its connection id, which island holds each such side, and the certified
     * islands a partner's commit woke, discarded at the engine's next decision point (plan section 5.2, form C); the heat
     * each exchanger holds in transit is the coupling's ({@link ExchangerCoupling}). */
    final Map<Long,Coupled> coupled=new HashMap<>();
    /** The coupled sides by pair key, in the order they were first coupled (code base review R23): a pair's two sides
     * without a scan of every side. */
    final Map<Long,List<Coupled>> pairSides=new HashMap<>();
    final Map<Long,Long> sideOwners=new HashMap<>();
    final Set<Long> toWake=new LinkedHashSet<>();
    /** The exchanger coupling (codebase review R13): the fence, the reads at dispatch, the bookings, the heat in transit, the real valves. */
    final ExchangerCoupling coupling=new ExchangerCoupling(this);
    /** The certificates (codebase review R13): the entry evidence, issue and restore, and the materialisation of a certified island. */
    final Certificates certificates=new Certificates(this);
    /** What a topology event does to the islands (codebase review R13): fences, alignment, release and replacement. */
    final TopologyChange topologyChange=new TopologyChange(this);
    /**
     * The running columns' ports (batch 2026-10-28-column-operation, plan 4.2 and 9): whether an island holding one may start
     * a slice (the outlet fence), what each port plans for the slice, and what each gave and took over a committed or replayed
     * span. Arithmetic only, on the server thread.
     */
    public interface Ports {
        boolean ready(long island,PassiveNetwork graph,long start);
        Map<Long,com.wormzjl.icheme.science.fluid.network.PortPlan> plans(long island,PassiveNetwork graph,IslandClock.Slice slice);
        /** Records what the island's ports gave and took. A booking runs inside a materialisation: it never reads
         * {@link IslandCoordinator#through} or acts; what it makes due runs in {@link #decide}. */
        void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result);
        /** The units' step at the engine's decision points (after the due deadlines, a pump, a round's close), as the shaft
         * trains': where they may materialise the islands they wait on (batch 2026-11-11-drum-operation-holds, defect 5). */
        default void decide(){}
        /**
         * The read of coupled side {@code side} whose other side {@code partner} a running column holds (C6b), at a slice from
         * {@code start} of {@code seconds} s, the pair's heat in transit now {@code transit} (J): a column's condenser water
         * side reads the heat the column owes it, a side of a held member moves no heat; null where no column holds the partner.
         */
        default com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read heatRead(long side,long partner,long start,double seconds,double transit){return null;}
        /**
         * The slice inputs a unit sets on the island's own nodes and connections at dispatch, after the controls and the shaft
         * plans (batch 2026-11-09-batch-distiller, W3: a still's fired pot and its head, its burners' fuel valve): a read, no solve.
         */
        default PassiveNetwork dispatch(long island,PassiveNetwork graph,IslandClock.Slice slice){return graph;}
        /** Both {@code a} and {@code b}: ready when both are, their plans together, both booked; a side read by the first that answers. */
        static Ports both(Ports a,Ports b) {
            return new Ports() {
                public boolean ready(long island,PassiveNetwork graph,long start){return a.ready(island,graph,start)&&b.ready(island,graph,start);}
                public Map<Long,com.wormzjl.icheme.science.fluid.network.PortPlan> plans(long island,PassiveNetwork graph,IslandClock.Slice slice) {
                    var first=a.plans(island,graph,slice);var second=b.plans(island,graph,slice);
                    if(first.isEmpty())return second;if(second.isEmpty())return first;
                    var all=new HashMap<>(first);all.putAll(second);return all;
                }
                public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result){a.booked(island,graph,start,end,result);b.booked(island,graph,start,end,result);}
                public void decide(){a.decide();b.decide();}
                public com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read heatRead(long side,long partner,long start,double seconds,double transit) {
                    var read=a.heatRead(side,partner,start,seconds,transit);return read!=null?read:b.heatRead(side,partner,start,seconds,transit);
                }
                public PassiveNetwork dispatch(long island,PassiveNetwork graph,IslandClock.Slice slice){return b.dispatch(island,a.dispatch(island,graph,slice),slice);}
            };
        }
        Ports NONE=new Ports(){
            public boolean ready(long island,PassiveNetwork graph,long start){return true;}
            public Map<Long,com.wormzjl.icheme.science.fluid.network.PortPlan> plans(long island,PassiveNetwork graph,IslandClock.Slice slice){return Map.of();}
            public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result){}
        };
    }
    Ports ports=Ports.NONE;
    /**
     * The shaft trains (batch 2026-11-03-rotating-machinery W3, build plan section 2): each island's slice inputs at dispatch
     * (a machine's speed, a frame's displacement, a governor valve's target; reads, no solve), the power its machines took
     * at each commit and replay ({@code booked} records only), and the drivers' step at the engine's decision points
     * ({@code decide}, after commits and due deadlines; never in a presentation read). Arithmetic only, on the server thread.
     */
    public interface Shafts {
        Map<Long,com.wormzjl.icheme.science.fluid.network.ShaftPlan> plans(long island,PassiveNetwork graph);
        void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result);
        void decide();
        Shafts NONE=new Shafts(){
            public Map<Long,com.wormzjl.icheme.science.fluid.network.ShaftPlan> plans(long island,PassiveNetwork graph){return Map.of();}
            public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result){}
            public void decide(){}
        };
    }
    Shafts shafts=Shafts.NONE;
    private java.util.function.UnaryOperator<PassiveNetwork> controls=java.util.function.UnaryOperator.identity();
    public void controls(java.util.function.UnaryOperator<PassiveNetwork> value){owned();controls=Objects.requireNonNull(value);}
    public void controlsMoved(Collection<Long> changed){owned();for(long id:changed)if(islands.containsKey(id))toWake.add(id);}
    /** Installs the shaft trains. */
    public void shafts(Shafts value){owned();shafts=Objects.requireNonNull(value);}
    /** A shaft train's speed, trip or start changed (a dependency change, plan 5.2): the islands it drives end their
     * certificates at the next decision point and read the new speed at their next dispatch. */
    public void shaftsMoved(Collection<Long> moved){owned();for(long id:moved)if(islands.containsKey(id))toWake.add(id);}
    /** Installs the running columns' ports. */
    public void ports(Ports value){owned();ports=Objects.requireNonNull(value);}
    /** A running column's plan for {@code island} moved: a certified island ends its certificate at the next decision point. */
    public void portsMoved(long island){owned();if(islands.containsKey(island))toWake.add(island);}
    private boolean reconsideringPartners;
    private record Round(long id,List<Pending> entries,long startedNanos) {}
    private final List<Round> rounds=new ArrayList<>();
    long roundSequence,generations,shrinkTick=Long.MAX_VALUE;
    private final EnumMap<IslandScheduler.Kind,Runnable> externalActions=new EnumMap<>(IslandScheduler.Kind.class);
    private final EnumMap<IslandScheduler.Kind,Long> externalTicks=new EnumMap<>(IslandScheduler.Kind.class);
    /**
     * Told once per island per phase hold (the first held attempt of an episode, and again only for a different hold or
     * after {@link #PHASE_HOLD_WARNING_TICKS} online ticks): the world logs it. {@code where} is the node's label,
     * {@code parked} whether the island now waits for an input change.
     */
    @FunctionalInterface public interface PhaseHolds {
        void held(long island,com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold,String where,boolean parked);
        PhaseHolds NONE=(island,hold,where,parked)->{};
    }
    /** Repeats of the same island and hold are logged at most once per this many online ticks (five minutes). */
    static final long PHASE_HOLD_WARNING_TICKS=6000;
    private PhaseHolds phaseHolds=PhaseHolds.NONE;
    /** How a network node is named in a status line: the world names a device by kind and position. */
    private java.util.function.LongFunction<String> nodeNames=id->"node "+id;
    Publisher replayed=changed->{};
    private int dispatchedThisTick;
    boolean stopped,pumping;
    // Reasons a pump would act; each is set where it arises and all are cleared when a pump runs.
    private boolean pumpRequested,completionsSincePump,roundDue,shrinkDue;
    String suspension;

    /** A coordinator with its own epoch, advanced once per {@link #tick()}. */
    public IslandCoordinator(Dispatcher dispatcher,Publisher publisher,LongSupplier nanoClock,Settings settings) {
        this(dispatcher,publisher,nanoClock,settings,null);
    }
    /** {@code epoch} is the shared online tick (the world's), which its owner advances before calling
     * {@link #tick()}; null gives the coordinator its own epoch, advanced by {@link #tick()}. */
    public IslandCoordinator(Dispatcher dispatcher,Publisher publisher,LongSupplier nanoClock,Settings settings,LongSupplier epoch) {
        this.dispatcher=Objects.requireNonNull(dispatcher);this.publisher=Objects.requireNonNull(publisher);
        this.nanoClock=Objects.requireNonNull(nanoClock);this.settings=Objects.requireNonNull(settings);
        ownsEpoch=epoch==null;this.epoch=ownsEpoch?()->ownEpoch:epoch;
    }
    void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Island coordinator belongs to its server thread");}
    /** Process time against online time: the factor every island here integrates under, and the ports and shafts book by. */
    public SimulationTime time(){return settings.time;}
    void timed(FluidThermodynamics model){if(!model.time().equals(settings.time))throw new IllegalArgumentException("An island model on time factor "+model.time().factor()+" in a coordinator on "+settings.time.factor());}
    public void register(Snapshot saved,FluidThermodynamics model) {
        owned();timed(model);if(stopped||islands.containsKey(saved.id))throw new IllegalStateException("Stopped/duplicate island");
        if(saved.graph.reservoirs().stream().anyMatch(n->n.kind()==PassiveNetwork.NodeKind.PORT))throw new IllegalArgumentException("Internal ports cannot own world state");
        var island=new Island(saved,model,epoch);
        // A loaded island past the compound cap holds as it held when it was formed (V2, a capability limit).
        if(model.compounds().size()>IslandSets.MAXIMUM_COMPOUNDS){island.capability="an island carries at most "+IslandSets.MAXIMUM_COMPOUNDS+" compounds and water; this one carries "+model.compounds().size()+": separate its parts to run it";island.held=true;island.status="HELD: "+island.capability;}
        var persisted=saved.certificate.flatMap(Certified::saved);
        if(persisted.isPresent())certificates.restore(island,persisted.orElseThrow());
        islands.put(saved.id,island);ready.register(saved.id,false);index(island);indexStock(island);reconsider(island);
    }
    /**
     * A restored island's observation points (its pipes' flowmeter taps), by pipe identity. A checkpoint does not carry
     * them: the registry's load compiles them from the installed flowmeters and attaches them here, before the island's
     * first slice. They are no part of a pipe's identity or a certificate's signature, so the island's state, certificate
     * and schedule stay as restored.
     */
    public void attachObservationPoints(long id,Map<Long,List<PassiveNetwork.Pipe.ObservationPoint>> byPipe) {
        owned();var island=require(id);if(island.pendingEntries!=0)throw new IllegalStateException("Island "+id+" has a slice in flight");
        island.graph=observedAs(island.graph,byPipe);
    }
    static PassiveNetwork observedAs(PassiveNetwork graph,Map<Long,List<PassiveNetwork.Pipe.ObservationPoint>> byPipe) {
        return new PassiveNetwork(graph.reservoirs(),graph.pipes().stream().map(p->p.withObservationPoints(byPipe.getOrDefault(p.id(),List.of()))).toList(),graph.scheduledTransfers());
    }
    static Map<Long,List<PassiveNetwork.Pipe.ObservationPoint>> observationPoints(PassiveNetwork graph) {
        var points=new HashMap<Long,List<PassiveNetwork.Pipe.ObservationPoint>>();for(var p:graph.pipes())if(!p.observationPoints().isEmpty())points.put(p.id(),p.observationPoints());return points;
    }
    /** The model island {@code id} runs on: the network package on its own compound list (batch 2026-11-04-refinery-compounds, V2). */
    public FluidThermodynamics model(long id){owned();return require(id).model;}
    /** Snapshot {@code s} of a live island as a checkpoint entry: on the island's own compound list, named in full, so a compound added to the package later moves no island's model (V3). */
    public FluidCheckpointCodec.IslandEntry entry(String dimension,String packageId,Snapshot s){var model=model(s.id());return new FluidCheckpointCodec.IslandEntry(dimension,packageId,model.compounds(),model.wet(),s);}
    /** A certified island is materialised first, so what the engine reads (a save, an event's base) is current; a view takes {@link #presentation}. */
    public Snapshot snapshot(long id){owned();var island=require(id);certificates.materialise(island);return island.snapshot();}
    public Optional<Metrics> metrics(long id){owned();return Optional.ofNullable(require(id).metrics);}
    /** The island's last solved interval as stored: what {@link #presentation} carries too (a preview keeps it). A read that
     * builds no snapshot: an instrument reads its observations (code base review R1). */
    public Optional<PassiveIntervalSolver.Result> lastResult(long id){owned();return require(id).lastResult;}
    /**
     * A presentation read, for a view of a loaded device, an open menu or an instrument: a preview that changes nothing on
     * the island (code base review R5). A certified island behind its {@link #previewTarget} is shown as its certificate's
     * state at that tick ({@link IslandCertificate#graphAt}), with its taps and a clock that reads the target committed;
     * every other island as it is stored. Its clock, graph, accounts, bookings, metrics and payload stay as they are, so
     * the bookings and the heat in transit are the same bits whoever looks and whenever: only the engine's own
     * materialisations (deadlines, fences, {@link #through}, saves, coupling reads) book a replayed span.
     */
    public Snapshot presentation(long id) {
        owned();var island=require(id);long committed=island.clock.committedTick(),target=previewTarget(island);
        if(target<=committed)return island.snapshot();
        var stored=island.snapshot();var clock=stored.clock();
        var graph=observedAs(island.certificate.graphAt(target),observationPoints(island.graph));
        // The status a materialisation to the target would leave: the certified line, or event alignment at the fence.
        String status=suspension!=null?island.status:Certificates.certifiedStatus(island);
        if(!island.suspended&&!island.clock.busy()&&island.fence()==target)status="WAITING: event alignment";
        // Payload generation 0: a preview is never a checkpoint unit.
        return new Snapshot(stored.id(),stored.revision(),graph,new IslandClock.Snapshot(clock.onlineTick(),target,0,clock.cadenceTicks()),
                stored.lastResult(),status,stored.fences(),stored.certificate(),0);
    }
    /**
     * The tick a certified island's preview shows: {@code min(online, nearest fence, hold tick, horizon)}, one tick short
     * of a horizon not acted on yet, so a view never shows a wake; its committed tick for an island with no certificate.
     */
    private static long previewTarget(Island island) {
        var certificate=island.certificate;if(certificate==null)return island.clock.committedTick();
        long wake=certificate.horizonTick();
        long target=Math.min(Math.min(island.clock.onlineTick(),island.fence()),Math.min(island.holdTick,wake));
        return target==wake?wake-1:target;
    }
    /**
     * The pipes' bulk speeds over the last 100 online ticks, from transport the engine accepted or materialised and, for a
     * certified island, the span its preview shows past its committed tick, at the certificate's recorded rate. A read:
     * it never wakes, advances or books anything.
     */
    public Map<Long,Double> bulkVolumeRates(long id) {
        owned();var island=require(id);long online=island.clock.onlineTick();
        var accepted=island.pipeSpeed.volumeRates(online);
        long committed=island.clock.committedTick(),target=previewTarget(island);
        if(target<=committed)return accepted;
        // The recorded interval's transfers over [committed, target]: a window of that span alone reads them at
        // volume / (target - committed) per tick; scaled by (target - committed) / duration they run at the recorded rate.
        var summary=island.certificate.summary();var window=new PipePresentation.SpeedWindow();
        window.accepted(committed,target,summary.result.pipeTransfers());
        double g=(target-committed)/(double)summary.durationTicks;
        var rates=new HashMap<>(accepted);window.volumeRates(online).forEach((pipe,rate)->rates.merge(pipe,g*rate,Double::sum));
        return Map.copyOf(rates);
    }
    /**
     * The volume an actuator's controlled pipes carried, m³ gross, from the totaliser's {@code origin} through {@code tick}
     * (the tick a view of the island shows); {@code island}, {@code revision} and {@code origin} say which history it counts,
     * {@code sliceTicks} the island's cadence. A level loop takes the difference of two as its valve's mean flow between two
     * samples (LEVEL_LOOP_REVIEW.md option A2).
     */
    public record Transported(long island,long revision,long origin,long tick,double volume,int sliceTicks) {
        /** Whether {@code earlier} counts the same history, so the two differ by what flowed between their ticks. */
        public boolean continues(Transported earlier){return earlier!=null&&island==earlier.island&&revision==earlier.revision&&origin==earlier.origin&&tick>=earlier.tick;}
    }
    /**
     * Device {@code device}'s controlled pipes (those leaving its node with a law, as {@link PipePresentation#actuatorMappings}
     * finds them) as island {@code id}'s accepted intervals booked them, through the tick its {@link #presentation} shows: for
     * a certified island the preview's span past its committed tick at the certificate's recorded rate, as
     * {@link #bulkVolumeRates} adds it. Empty with no such pipe or no unbroken history. A read: it never wakes, advances,
     * materialises or books anything.
     */
    public Optional<Transported> transported(long id,long device) {
        owned();var island=require(id);var window=island.pipeSpeed;
        long committed=island.clock.committedTick(),target=previewTarget(island);
        if(window.origin()<0||window.through()!=committed)return Optional.empty();
        var pipes=new HashSet<Long>();
        for(var pipe:island.graph.pipes())if(island.graph.reservoirs().get(pipe.first()).id()==device&&!(pipe.control() instanceof FlowControl.Passive))pipes.add(pipe.id());
        if(pipes.isEmpty())return Optional.empty();
        double volume=0;for(long pipe:pipes)volume+=window.total(pipe);
        if(target>committed) {
            var summary=island.certificate.summary();double g=(target-committed)/(double)summary.durationTicks;
            for(var t:summary.result.pipeTransfers())if(pipes.contains(t.pipeId()))volume+=g*(PipePresentation.volume(t.forward())+PipePresentation.volume(t.reverse()));
        }
        return Optional.of(new Transported(island.id,island.revision,window.origin(),target,volume,island.clock.snapshot().cadenceTicks()));
    }

    /** Every island, certified ones materialised first; see {@link #snapshot(long)}. */
    public List<Snapshot> snapshots(){owned();for(var island:List.copyOf(islands.values()))certificates.materialise(island);return islands.values().stream().map(Island::snapshot).toList();}
    /** Every island as it is stored, without materialising a certified one: a diagnostic read that must not
     * itself advance anything. A certified island's committed tick here may lag the tick it would read as. */
    public List<Snapshot> observe(){owned();return islands.values().stream().map(Island::snapshot).toList();}
    /** One island as it is stored, without materialising it: a diagnostic read that must not itself advance it. */
    public Snapshot observe(long id){owned();return require(id).snapshot();}
    /** Whether the island keeps solver caches; a certified island has released them. For tests. */
    boolean retainsSolver(long id){owned();return require(id).retained!=null;}
    /** Whether a phase hold reproduced on its retry and the island now waits for an input change. For tests. */
    boolean waitsForInputs(long id){owned();return require(id).phaseParked;}
    /** Why the island's last closed interval did not certify it, or null; diagnostics only, never a decision. */
    public String certificationRefusal(long id){owned();return require(id).refusal;}
    /**
     * What the entry evidence measured at the island's last usable solved interval (ending at {@code endTick}):
     * its comparison with the interval before it, null when there was no consecutive usable one, and the
     * interval's own drift, the d of the horizon. Diagnostics only, never a decision; null when certificates
     * are off or no usable interval has closed since the island last woke or certified.
     */
    public record Evidence(long endTick,IslandCertificate.Stationarity stationarity,double drift) {}
    public Evidence certificationEvidence(long id){owned();return require(id).evidence;}
    /** Installs the listener told of phase holds; see {@link PhaseHolds}. */
    public void onPhaseHold(PhaseHolds listener){owned();phaseHolds=Objects.requireNonNull(listener);}
    /** Installs how a node is named in a status line (a device's kind and position in the world). */
    public void nodeNames(java.util.function.LongFunction<String> names){owned();nodeNames=Objects.requireNonNull(names);}
    /** Told of every certificate materialisation, with the replayed interval as the island's last result. Only
     * accounting listens here: a replay changes no dependency and needs no view refresh of its own. */
    public void onReplayed(Publisher listener){owned();replayed=Objects.requireNonNull(listener);}
    public int pendingCount(){owned();return pending.size();}
    /** The shared online epoch this coordinator's clocks read. */
    public long now(){owned();return epoch.getAsLong();}
    /**
     * Whether {@code id} has booked what it delivers through {@code tick} (batch 2026-10-28-column-operation, plan section 9):
     * committed through it, certified that far (materialised here), or held, parked or suspended (it delivers nothing past its
     * committed tick until it solves again). An island that no longer exists has nothing to book.
     */
    public boolean through(long id,long tick) {
        owned();var island=islands.get(id);if(island==null||island.clock.committedTick()>=tick)return true;
        if(island.held||island.phaseParked||island.suspended||suspension!=null)return true;
        if(island.certificate!=null){certificates.materialise(island);return island.clock.committedTick()>=tick;}
        return false;
    }
    /** O(1): the earliest tick at which a deadline is scheduled, possibly a stale one; see {@link IslandScheduler}. */
    public long nextDue(){owned();return scheduler.nextDue();}
    /** Scheduled deadline entries, live and not yet popped stale ones. */
    public int scheduledDeadlines(){owned();return scheduler.size();}
    /** Owners whose next slice can be dispatched now. */
    public int readyCount(){owned();return ready.readyCount();}
    /** The epoch tick at which an island's online time reads {@code onlineTick}. */
    public long epochTick(long island,long onlineTick){owned();return require(island).clock.epochTickAt(onlineTick);}
    public boolean hasFence(long island,UUID event){owned();return require(island).fences.containsKey(event);}
    /** Islands holding a fence for this event, from the fence index rather than a scan. */
    public Set<Long> fencedIslands(UUID event){owned();return Set.copyOf(fenceOwners.getOrDefault(event,Set.of()));}
    /**
     * Schedules the one deadline of an external kind ({@link IslandScheduler.Kind#RECOVERY_RETRY}) at an epoch tick, replacing any earlier one of that kind;
     * {@link Long#MAX_VALUE} cancels it. The action runs on this thread from {@link #tick()} once due.
     */
    public void schedule(IslandScheduler.Kind kind,long epochTick,Runnable action) {
        owned();Objects.requireNonNull(action);
        if(kind!=IslandScheduler.Kind.RECOVERY_RETRY&&kind!=IslandScheduler.Kind.COLUMN_CYCLE)throw new IllegalArgumentException("Not an external deadline kind: "+kind);
        if(stopped)return;
        externalActions.put(kind,action);
        if(Objects.equals(externalTicks.get(kind),epochTick))return;
        long generation=++generations;externalTicks.put(kind,epochTick);
        if(epochTick!=Long.MAX_VALUE)schedule(epochTick,kind,generation,generation);
    }

    /** Invalidates old proposals without changing committed stock, debt or fences.
     * Cancellation retains ownership until the actual terminal completion is drained. */
    public void suspendForPropertyChange(String reason) {
        owned();Objects.requireNonNull(reason);if(stopped||suspension!=null)return;
        suspension=reason;dispatcher.demand(0);
        for(var island:islands.values()) {
            // Committed time freezes where it stands: a certified island is materialised to now and capped there,
            // while online time keeps accruing so the debt is preserved. No evidence spans the hold.
            island.holdTick=island.clock.snapshot().onlineTick();certificates.materialise(island);
            island.lastInterval=null;island.streak=0;island.revalidating=null;
            island.revision=Math.addExact(island.revision,1);island.suspended=true;island.status=reason;island.retained=island.certificate==null?new RetainedSolver():null;
            island.touch();reconsider(island);
        }
        var closing=List.copyOf(rounds);rounds.clear();
        for(var round:closing){for(var entry:round.entries)entry.result=null;closeRound(round);}
        publisher.published(snapshots());
    }
    /** Only the world property's compatibility guard may resume this pinned model. Every certificate is discarded:
     * the island solves its debt from the materialised state and must qualify again over the confirm count. */
    public void resumeQualifiedProperties() {
        owned();if(stopped||suspension==null)return;suspension=null;
        for(var island:islands.values()) {
            island.holdTick=Long.MAX_VALUE;island.suspended=false;island.status=island.capability!=null?"HELD: "+island.capability:"WAITING: full solve after property reload";inputsChanged(island);
            if(island.certificate!=null){island.certificate=null;island.certifiedSince=-1;island.signature=null;island.retained=new RetainedSolver();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificateWakes);}
            island.lastInterval=null;island.streak=0;
            island.touch();reconsider(island);
        }
        publisher.published(snapshots());
    }

    /**
     * Called once per elapsed server tick, after the shared epoch advanced (or advancing this coordinator's own
     * epoch), never from wall time or for time spent offline. Constant cost when nothing is due: it resets the
     * per-tick dispatch budget, pops only due deadlines, checks the wall budget of the open rounds, and pumps
     * only when a pump would act.
     */
    public void tick() {
        owned();if(stopped)return;
        if(ownsEpoch)ownEpoch=Math.addExact(ownEpoch,1);
        dispatchedThisTick=0;
        runDue();
        // The ROUND_TIMEOUT deadline assumes nominal 50 ms ticks; a slow server spends the wall budget in fewer
        // ticks. Checking each open round's budget here, O(open rounds) and nothing per island, closes an
        // expired round within one tick of its wall deadline however long the ticks are.
        if(!roundDue)for(var round:rounds)if(nanoClock.getAsLong()-round.startedNanos>=settings.hardBudgetNanos){roundDue=true;break;}
        verify();
        pumpIfUseful();
    }
    /** Pops every deadline due at the current epoch tick and acts on the ones still current. */
    private void runDue() {
        long now=epoch.getAsLong();
        for(var due=scheduler.poll(now);due!=null;due=scheduler.poll(now)) {
            switch(due.kind()) {
                case SLICE_DUE,RETRY,CERTIFICATE_HORIZON->{
                    // For a certified island this is its horizon: materialise and act.
                    var island=islands.get(due.id());if(island==null||island.generation!=due.generation())continue;
                    FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.deadlinesFired);island.deadline=Long.MAX_VALUE;
                    certificates.materialise(island);reconsider(island);
                }
                case ROUND_TIMEOUT->{
                    Round round=null;for(var open:rounds)if(open.id==due.id())round=open;if(round==null)continue;
                    FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.deadlinesFired);
                    long remaining=settings.hardBudgetNanos-(nanoClock.getAsLong()-round.startedNanos);
                    if(remaining<=0)roundDue=true;else scheduleRoundTimeout(round,remaining);
                }
                case ALLOCATOR_SHRINK->{
                    // The current allocator deadline is the one at shrinkTick; any other entry was replaced.
                    if(shrinkTick!=due.tick())continue;
                    FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.deadlinesFired);shrinkTick=Long.MAX_VALUE;shrinkDue=true;
                }
                case RECOVERY_RETRY,COLUMN_CYCLE->{
                    var tick=externalTicks.get(due.kind());if(tick==null||tick!=due.tick())continue;
                    FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.deadlinesFired);externalTicks.remove(due.kind());externalActions.get(due.kind()).run();
                }
                default->throw new IllegalStateException("No handler for deadline "+due.kind());
            }
        }
        shafts.decide();ports.decide();coupling.flushWakes();
    }
    /**
     * The O(1) check the tick hooks make: pumps only when a pump would act, that is when a state change made
     * an owner ready, a completion arrived, a round or allocator deadline fell due, or ready owners meet free
     * capacity (for example after the per-tick dispatch budget was reset). Installed as the readiness pump
     * called after completion drains.
     */
    public void pumpIfUseful() {
        owned();if(stopped||pumping||suspension!=null)return;
        if(pumpRequested||completionsSincePump||roundDue||shrinkDue||ready.readyCount()>0&&capacity()>0)pump();
    }
    /** May also run after terminal draining, allowing catch-up between ordinary tick boundaries. */
    public void pump() {
        owned();if(stopped||pumping)return;pumping=true;
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.readinessPumps);
        pumpRequested=false;completionsSincePump=false;roundDue=false;shrinkDue=false;
        try {
            if(suspension!=null){dispatcher.demand(0);return;}
            for(var round:List.copyOf(rounds)) {
                if(round.entries.stream().allMatch(p->p.terminal)||nanoClock.getAsLong()-round.startedNanos>=settings.hardBudgetNanos) {
                    rounds.remove(round);closeRound(round);
                }
            }
            verify();
            int eligible=ready.readyCount();
            dispatcher.demand(eligible);scheduleShrink();
            // Completed results waiting at another group's barrier retain a bounded staging slot.
            int capacity=capacity();
            if(capacity<=0)return;
            var admitted=new ArrayList<Pending>();long roundStarted=nanoClock.getAsLong();
            for(int slot=0;slot<capacity;slot++) {
                var next=ready.nextReady(id->{FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandVisits);var i=require(id);return i.pendingEntries==0&&i.clock.nextSlice(1,i.fence(),i.maximumSliceTicks).isPresent()&&coupling.coupledThrough(i)&&ports.ready(i.id,i.graph,i.clock.committedTick());});
                if(next.isEmpty())break;
                var island=require(next.getAsLong());long request=dispatcher.nextRequestId();
                var slice=island.clock.nextSlice(request,island.fence(),island.maximumSliceTicks).orElseThrow();
                var attempt=new Attempt(island.id,island.revision,slice);
                // The job starts from the committed interval, not from the solver's history; see RetainedSolver.
                island.retained.committed(island.lastResult.orElse(null));
                var reads=coupling.reads(island,slice);
                // The shaft trains' slice inputs (W3), read here as a heat side's partner is.
                var controlled=controls.apply(island.graph);
                var dispatched=ports.dispatch(island.id,com.wormzjl.icheme.science.fluid.network.ShaftPlan.apply(controlled,shafts.plans(island.id,controlled)),slice);
                var command=new ProcessSolveJobs.FluidIslandCommand(island.model,dispatched,slice.seconds(settings.time),PassiveIntervalSolver.Settings.defaults(settings.time),settings.hardBudgetNanos,island.retained,reads,ports.plans(island.id,dispatched,slice));
                if(!dispatcher.submit(attempt,command)) {island.status="WAITING: shared worker capacity";island.touch();break;}
                island.clock.admitted(slice);island.status="SOLVING";island.touch();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.solvesDispatched);
                var entry=new Pending(attempt,island.clock.snapshot().onlineTick(),nanoClock.getAsLong());entry.dispatched=dispatched;entry.reads=reads;pending.put(request,entry);island.pendingEntries++;admitted.add(entry);dispatchedThisTick++;
                reconsider(island);
            }
            if(!admitted.isEmpty()){var round=new Round(roundSequence=Math.addExact(roundSequence,1),List.copyOf(admitted),roundStarted);rounds.add(round);scheduleRoundTimeout(round,settings.hardBudgetNanos);}
            shafts.decide();ports.decide();coupling.flushWakes();
        } finally {
            // Owners that became ready during this pump were offered to it; any left over wait for capacity,
            // which pumpIfUseful checks directly, so no request survives the pump.
            pumping=false;pumpRequested=false;
        }
    }
    private int capacity(){return Math.min(dispatcher.availableWorkers(),Math.min(settings.maximumDispatchesPerTick-dispatchedThisTick,settings.maximumDispatchesPerTick-pending.size()));}
    /** A missing result denotes cancellation, failure or abandonment. Duplicate/late results never advance clocks. */
    public void completed(Attempt attempt,Optional<ProcessSolveJobs.FluidIslandSolveResult> result){completed(attempt,result,null);}
    /** {@code failure}: why a missing result is missing (the worker's exception, a cancellation), named in the held status; null where unknown. */
    public void completed(Attempt attempt,Optional<ProcessSolveJobs.FluidIslandSolveResult> result,String failure) {
        owned();Objects.requireNonNull(result);var entry=pending.get(attempt.slice.requestId());
        if(entry==null||!entry.attempt.equals(attempt)||entry.terminal)return;
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.completionsRouted);
        entry.lateResult=nanoClock.getAsLong()-entry.admittedNanos>=settings.hardBudgetNanos;
        entry.terminal=true;entry.result=entry.lateResult?null:result.orElse(null);entry.failure=result.isEmpty()?failure:null;
        completionsSincePump=true;
        if(entry.closed) {
            pending.remove(attempt.slice.requestId());var island=islands.get(attempt.islandId);
            if(island!=null){island.pendingEntries--;reconsider(island);}
            return;
        }
        // The completion router calls pump after its entire bounded drain, avoiding recursive dispatch here.
    }
    /** Why a closed entry has no result: the round's deadline (still running, or answered past the wall budget), or what the
     * completion said of its missing result (the worker's failure: its exception and message). */
    private static String missing(Pending entry) {
        if(!entry.terminal||entry.lateResult)return "round deadline";
        return entry.failure==null?"round deadline or worker failure":entry.failure;
    }
    private void closeRound(Round round) {
        var changed=new ArrayList<Snapshot>();
        for(var entry:round.entries) {
            var attempt=entry.attempt;var island=require(attempt.islandId);
            var outcome=entry.result;var before=island.graph;
            String refusal=null;
            boolean accept=entry.terminal&&outcome!=null&&outcome.candidate().isPresent()&&attempt.revision==island.revision;
            if(accept) {
                var candidate=outcome.candidate().orElseThrow();
                accept=validCandidate(island,attempt,candidate,entry.dispatched);
                if(!accept)refusal="candidate identity or interval mismatch";
                else {
                    island.graph=candidate.graph();island.lastResult=Optional.of(candidate);
                    island.pipeSpeed.accepted(attempt.slice.startTick(),attempt.slice.endTick(),candidate.pipeTransfers());
                    island.notice=releaseLine(island.lastResult,island.model)+com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices.line(outcome.notices(),nodeNames);
                    island.status=outcome.detail()+island.notice;
                }
            }
            if(!accept)island.status=refusal!=null?"HELD: "+refusal:outcome==null?"HELD: "+missing(entry)
                    :outcome.phaseHold().isPresent()?phaseHoldStatus(outcome.phaseHold().orElseThrow(),false)
                    :outcome.detail().startsWith("HELD")?outcome.detail():"HELD: "+outcome.detail();
            island.clock.completed(attempt.slice,accept);island.held=!accept&&suspension==null&&attempt.revision==island.revision;
            if(accept)coupling.booked(island,attempt.slice.startTick(),attempt.slice.endTick(),island.lastResult.orElseThrow(),entry.reads);
            if(suspension!=null)island.status=suspension;
            if(suspension==null&&!accept&&refusal==null&&attempt.revision==island.revision)hold(island,attempt.slice,outcome);
            else if(accept) {
                island.holdStreak=0;island.phaseHold=null;island.phaseParked=false;
                if(island.maximumSliceTicks!=Integer.MAX_VALUE) {
                    int cadence=island.clock.snapshot().cadenceTicks();
                    island.maximumSliceTicks=island.maximumSliceTicks>=cadence/2?Integer.MAX_VALUE:island.maximumSliceTicks*2;
                }
            }
            island.metrics=new Metrics(island.metrics==null?1:Math.addExact(island.metrics.sequence(),1),attempt.slice.startTick(),attempt.slice.endTick(),entry.dispatchedAtTick,island.clock.snapshot().onlineTick(),outcome==null?-1:outcome.workerNanos(),outcome==null?-1:outcome.workerCpuNanos(),Math.max(0,nanoClock.getAsLong()-entry.admittedNanos),accept);
            if(settings.adaptive&&outcome!=null) {
                long cpu=outcome.workerCpuNanos(),wall=outcome.workerNanos();
                boolean cpuBound=cpu>=0&&wall>0&&cpu/(double)wall>=.75;
                island.clock.performanceSample(cpuBound&&wall>=settings.softBudgetNanos,accept&&cpu>=0&&wall<settings.softBudgetNanos/4);
            }
            entry.closed=true;
            if(entry.terminal){pending.remove(attempt.slice.requestId());island.pendingEntries--;}else dispatcher.cancel(attempt.slice.requestId());
            certificates.qualify(island,accept?outcome:null,before,attempt);
            reconsider(island);island.touch();
            changed.add(island.snapshot());
        }
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandsPublished,changed.size());
        publisher.published(List.copyOf(changed));
        shafts.decide();ports.decide();coupling.flushWakes();
    }
    /** The longest a held island waits between retries at the shortest slice, in cadences: 2^6 = 64. */
    static final int MAXIMUM_RETRY_DOUBLINGS=6;
    /**
     * The hold policy: what a held attempt (no committed candidate, no refused transaction) changes before the retry.
     * Every hold hands the retry a fresh {@link RetainedSolver}: the one the attempt used carries the step solver's last
     * flows, modes and factorizations from wherever inside the slice that attempt stopped, and a retry that warm-starts
     * from them starts the slice again from its first state with the flows of a later one (a rate solve starting from
     * 0.45 and -0.22 kg/s on tanks still at rest failed the same way at every retry). With a fresh solver a retry is the
     * same computation as the first attempt, so every hold also changes the problem: a slice longer than one tick is
     * halved (a shorter whole interval, never a partial commit; the first full acceptance doubles it back), and a hold
     * at one tick waits {@code cadence * 2^k} ticks for its retry, k the number of such holds in a row, at most
     * {@link #MAXIMUM_RETRY_DOUBLINGS}. Anything that changes the island's inputs (a fence, a released fence, a property
     * resume) clears the wait and the count. Online ticks only: no wall clock decides a delay.
     */
    private void hold(Island island,IslandClock.Slice slice,ProcessSolveJobs.FluidIslandSolveResult outcome) {
        FluidRuntimeDiagnostics.count(budgetHold(outcome)?FluidRuntimeDiagnostics.budgetHolds:FluidRuntimeDiagnostics.numericalHolds);
        var hold=outcome==null?null:outcome.phaseHold().orElse(null);
        if(hold!=null) {
            // A phase hold (a capability limit, owner decision D21 option C; leaving a data range is never one since the
            // owner rule of 2026-10-16) is retried once, on a fresh solver like every hold (and on the halved slice the
            // ladder below gives it). When that retry fails on the same phase set at the same node, the failure is the
            // model's and deterministic - the committed state, or where the island's physics takes it, holds phases the
            // network does not carry - so no retry on the ladder can pass it: the island waits, dispatching nothing, until its
            // inputs change (a fence, a released one, a property resume; a topology or control edit makes a
            // new island). Every other failure keeps the ladder.
            boolean reproduced=hold.sameAs(island.phaseHold);
            island.phaseHold=hold;
            warnPhaseHold(island,hold,reproduced);
            if(reproduced) {
                island.phaseParked=true;island.status=phaseHoldStatus(hold,true);
                island.clock.holdUntilInputsChange();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.phaseHolds);
                if(island.certificate==null)island.retained=new RetainedSolver();
                return;
            }
        }
        int ticks=(int)(slice.endTick()-slice.startTick());
        if(ticks>1)island.maximumSliceTicks=Math.max(1,ticks/2);
        else {
            int cadence=island.clock.snapshot().cadenceTicks();
            island.clock.deferRetry((long)cadence<<Math.min(island.holdStreak,MAXIMUM_RETRY_DOUBLINGS));
            island.holdStreak=Math.min(island.holdStreak+1,MAXIMUM_RETRY_DOUBLINGS);
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.retriesDeferred);
        }
        if(island.certificate==null)island.retained=new RetainedSolver();
    }
    /** A hold the worker's budget decided rather than the solve: no result by the round deadline, or the wall budget. A
     * repeat can pass with more CPU; the rest cannot. */
    static boolean budgetHold(ProcessSolveJobs.FluidIslandSolveResult outcome) {
        return outcome==null||outcome.detail().startsWith(ProcessSolveJobs.WALL_DEADLINE);
    }
    /** Something the island solves against changed: a held island is retried at once, from its first retry again - a
     * phase hold included, which starts a new episode. */
    static void inputsChanged(Island island){island.clock.inputsChanged();island.holdStreak=0;island.phaseHold=null;island.phaseParked=false;}
    /** The dedicated status line of a phase hold: a phase set the network cannot carry, see
     * {@link com.wormzjl.icheme.science.fluid.thermo.ThermoHold}. */
    private String phaseHoldStatus(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold,boolean parked) {
        return ProcessSolveJobs.PHASE_HOLD+hold.status(where(hold))
                +(parked?"; the retry failed the same way, so the island waits for a change to its network":"");
    }
    private String where(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold) {
        return hold.node()==com.wormzjl.icheme.science.fluid.thermo.ThermoHold.NO_NODE?"":nodeNames.apply(hold.node());
    }
    /** One log line per island per hold: a new hold, or the same one again after the rate limit's online ticks. */
    private void warnPhaseHold(Island island,com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold,boolean parked) {
        String key=hold.reasonKey()+"@"+hold.node();long now=island.clock.onlineTick();
        if(key.equals(island.warnedHoldKey)&&now-island.warnedHoldAt<PHASE_HOLD_WARNING_TICKS)return;
        island.warnedHoldKey=key;island.warnedHoldAt=now;
        phaseHolds.held(island.id,hold,where(hold),parked);
    }
    private static boolean sameConnections(List<PassiveNetwork.Pipe> before,List<PassiveNetwork.Pipe> after){
        if(before.size()!=after.size())return false;
        for(int i=0;i<before.size();i++){var a=before.get(i);var b=after.get(i);
            if(a.id()!=b.id()||a.first()!=b.first()||a.second()!=b.second()||!a.sections().equals(b.sections())||!sameControl(a.control(),b.control())||(a.filter()==null)!=(b.filter()==null))return false;
            if(a.filter()!=null){if(a.filter().capacity()!=b.filter().capacity()||a.filter().cleanResistance()!=b.filter().cleanResistance()||a.filter().stoppedAtCapacity()&&!b.filter().stoppedAtCapacity())return false;
                for(var p:a.filter().captured().populations())if(b.filter().captured().mass(p.key())+1e-12<p.massKg())return false;}
        }return true;
    }
    /**
     * Whether a solved connection's control is the dispatched one's: the same, or the same control valve with the mass flow
     * target its exchanger side's control planned for the slice (W8, plan section 7: a slice input, like the side's bypass).
     */
    private static boolean sameControl(FlowControl a,FlowControl b) {
        if(a.equals(b))return true;
        // A shaft train's slice input (W3): a machine's speed, a frame's displacement, a governor valve's target.
        if(com.wormzjl.icheme.science.fluid.network.ShaftPlan.planned(a,b))return true;
        // A boundary valve a running column works (batch 2026-10-28-column-operation): its planned mass flow, whatever the valve's own control.
        if(b instanceof FlowControl.ControlValve y&&y.source()==FlowControl.ValveSource.MASS_FLOW&&com.wormzjl.icheme.science.fluid.network.PortPlan.COLUMN_SENSOR.equals(y.sensor())
                &&a instanceof FlowControl.Valve x)return x.targetPressure()==y.targetPressure();
        return a instanceof FlowControl.ControlValve x&&b instanceof FlowControl.ControlValve y&&y.source()==FlowControl.ValveSource.MASS_FLOW
                &&x.targetPressure()==y.targetPressure()&&x.volumeTarget()==y.volumeTarget()&&java.util.Objects.equals(x.sensor(),y.sensor())&&x.revision()==y.revision();
    }
    private static boolean validCandidate(Island island,Attempt attempt,PassiveIntervalSolver.Result result,PassiveNetwork dispatched) {
        if(result.advancedSeconds()!=attempt.slice.seconds(island.model.time())||!sameConnections(dispatched.pipes(),result.graph().pipes())||!result.graph().scheduledTransfers().isEmpty())return false;
        var old=island.graph.reservoirs();var next=result.graph().reservoirs();if(old.size()!=next.size())return false;
        for(int i=0;i<old.size();i++) {
            var a=old.get(i);var b=next.get(i);
            if(a.id()!=b.id()||a.kind()!=b.kind()||a.elevation()!=b.elevation()||(!a.junction()&&a.inventory().volume()!=b.inventory().volume()))return false;
        }
        return true;
    }
    /** Event fences must be installed before a solver can include their tick. */
    public void fence(UUID event,long tick,Collection<Long> affected){topologyChange.fence(event,tick,affected);}
    /** A certified owner answers by materialisation and a held one by {@link TopologyChange#acceptAtFence}: each advances to the
     * fence and is then aligned like any other. */
    public boolean aligned(UUID event,Collection<Long> affected){return topologyChange.aligned(event,affected);}
    public void releaseFence(UUID event,Collection<Long> affected){topologyChange.releaseFence(event,affected);}
    /**
     * A replacement island: its identity, its graph and the model it runs on, the network package on its own compound list
     * (batch 2026-11-04-refinery-compounds, V2; null: the change's model). {@code held} is a capability limit the island
     * cannot run past (an island of more than {@link IslandSets#MAXIMUM_COMPOUNDS} compounds, owner decision 2026-11-04): it
     * holds with that status and solves nothing until a topology change replaces it; null for none.
     */
    public record Replacement(long id,PassiveNetwork graph,FluidThermodynamics model,String held) {
        public Replacement(long id,PassiveNetwork graph){this(id,graph,null,null);}
        public Replacement(long id,PassiveNetwork graph,FluidThermodynamics model){this(id,graph,model,null);}
        public Replacement {if(id<=0)throw new IllegalArgumentException("Invalid replacement identity");Objects.requireNonNull(graph);}
    }
    /**
     * A batch of events of one tick applied as one change: every affected island holds a fence of at least one of them,
     * each at its committed tick, and none of them survives into a replacement. Declared additions and removals must
     * exactly match the stock delta; metadataCommit publishes the already-prepared construction/destruction ledger and
     * registry atomically. A merge or split without placement or destruction changes no owned reservoir inventory;
     * placement, destruction and material delivery need their own explicitly balanced transactions.
     */
    public void topology(Set<UUID> events,Set<Long> affected,List<Replacement> replacements,FluidThermodynamics model,long committed,long online,Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,com.wormzjl.icheme.science.fluid.network.InlineFilter> releasedFilters,Runnable metadataCommit){topologyChange.topology(events,affected,replacements,model,committed,online,additions,removals,releasedFilters,metadataCommit);}
    /** Stops admission; unresolved proposals are held. Actual execution capacity is owned by the shared pool. */
    public void stop() {
        owned();if(stopped)return;stopped=true;
        var closing=List.copyOf(rounds);rounds.clear();
        for(var round:closing){for(var entry:round.entries)entry.result=null;closeRound(round);}
        scheduler.clear();externalTicks.clear();externalActions.clear();
    }
    Island require(long id){var value=islands.get(id);if(value==null)throw new IllegalArgumentException("Unknown island "+id);return value;}

    /**
     * Re-derives one island's readiness and its deadline after anything that decides them changed. An island
     * is ready exactly when the polled coordinator's eligibility test held: no attempt owns it and its clock
     * offers a slice. Otherwise its clock says from which online tick time alone would make it ready, and that
     * becomes its single deadline; a stopped or suspended coordinator keeps no island ready or scheduled.
     */
    void reconsider(Island island) {
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandVisits);
        boolean eligible=false;long due=Long.MAX_VALUE;var kind=IslandScheduler.Kind.SLICE_DUE;
        if(island.certificate!=null) {
            // A certified island is never ready and holds at most one entry, at its horizon. Blocked behind an earlier fence it holds none: the fence's release re-examines it. It is
            // never materialised from here, even when that tick has passed: the caller may be in the middle of a
            // decision and must not see the island move. A wake already reached fires on the next pass over due deadlines, and the
            // materialisation it makes still stops exactly at the wake tick.
            kind=IslandScheduler.Kind.CERTIFICATE_HORIZON;
            if(!stopped&&suspension==null) {
                long wake=island.certificate.horizonTick();
                if(wake!=Long.MAX_VALUE&&island.fence()>=wake)due=island.clock.epochTickAt(wake);
            }
        }
        else if(!stopped&&suspension==null&&island.pendingEntries==0&&island.capability==null) {
            long fence=island.fence();
            // Behind the coupling fence a slice waits for its exchangers' other sides, whose commits re-derive it (below).
            if(island.clock.nextSlice(1,fence,island.maximumSliceTicks).isPresent())eligible=coupling.coupledThrough(island);
            else {
                long at=island.clock.readyAtTick(fence,island.maximumSliceTicks);
                // The label only records which bound set the time: the held owner's backoff or its slice.
                if(at!=Long.MAX_VALUE){due=island.clock.epochTickAt(at);if(island.clock.retryAtTick()==at)kind=IslandScheduler.Kind.RETRY;}
            }
        }
        if(island.ready!=eligible) {
            island.ready=eligible;ready.setReady(island.id,eligible);
            if(eligible)pumpRequested=true;
        }
        if(due!=island.deadline) {
            island.deadline=due;island.generation=++generations;
            if(due!=Long.MAX_VALUE)schedule(due,kind,island.id,island.generation);
        }
        // An exchanger's other side reads this island's committed tick and state at its fence (coupledThrough): one level.
        if(!reconsideringPartners&&island.sides.length>0) {
            reconsideringPartners=true;
            try{for(var other:coupling.partners(island))reconsider(other);}
            finally{reconsideringPartners=false;}
        }
    }

    // ---- exchanger coupling (batch 2026-10-27-heat-exchanger-design, plan section 5.2; owner Q16: form C, the fence) ----

    // Its state and logic are ExchangerCoupling's (codebase review R13); these are its public reads and bookings.

    /**
     * Sets the real valves the coupled sides' controls work (W8, plan section 7), by side connection id. A side whose
     * operation changed wakes its island: a certified island's certificate ends at the next decision point, and every
     * island reads its operation at its next dispatch.
     */
    public void operations(Map<Long,ExchangerRelay.Operation> next){coupling.operations(next);}
    /** The real valve side connection {@code side}'s control works (W8), or null for none. */
    public @org.jetbrains.annotations.Nullable ExchangerRelay.Operation operation(long side){return coupling.operation(side);}
    /**
     * The temperature (K) of the stream coupled side {@code side} last recorded at or before {@code tick} (what its other side
     * reads: its inlet), NaN with no record. A read of committed state; it asks for no solve.
     */
    public double sideTemperature(long side,long tick){return coupling.sideTemperature(side,tick);}
    /** The heat each exchanger holds in transit, J (the energy owed to its streams), by pair key: the lower of its two sides' connection ids. */
    public Map<Long,Double> transits(){return coupling.transits();}
    /**
     * An exchanger's heat in transit as of the side further behind, J: the transit with the heat the side ahead booked past
     * that side's committed tick taken out again. The transit itself holds that heat until the other side books the same
     * span; this is what the exchanger holds once both have, and what a give-back returns.
     */
    public double settledTransit(long pair){return coupling.settledTransit(pair);}
    /** Books {@code joule} more into the heat the pair of {@code side} and {@code partner} holds in transit, J (a running column's condenser, C6b). */
    public void owe(long side,long partner,double joule){coupling.owe(side,partner,joule);}
    /** The pair's heat in transit as of {@code tick}, as far as {@code side}'s bookings go: now, with the heat it booked past the tick given back, J. */
    public double transitAsOf(long side,long partner,long tick){return coupling.transitAsOf(side,partner,tick);}
    /** Restores an exchanger's heat in transit, J, from the world's facts (a load). */
    public void transit(long pair,double joule){coupling.transit(pair,joule);}
    /** Takes an exchanger's heat in transit out (its unit was removed and takes it along), J. */
    public double removeTransit(long pair){return coupling.removeTransit(pair);}

    // ---- certificates (plan section 3.2 and 3.3): Certificates, and the release line of the status ----
    /**
     * The open vessels' notice of an island's last interval (batch 2026-10-21-floating-roof-tanks, owner decision FR8):
     * {@code "; venting x kg/s"} and {@code "; spilling y kg/s"}, the mass its VENT and SPILL boundary transfers took to
     * the atmosphere over the interval's seconds; empty for none. Non-blocking, like the data-range notice it precedes.
     */
    static String releaseLine(Optional<PassiveIntervalSolver.Result> last,FluidThermodynamics model) {
        var rates=TankOutlets.released(model,last.orElse(null),null);var line=new StringBuilder();
        if(rates[0]>0)line.append(String.format(Locale.ROOT,"; venting %.3g kg/s",rates[0]));
        if(rates[1]>0)line.append(String.format(Locale.ROOT,"; spilling %.3g kg/s",rates[1]));
        return line.toString();
    }
    private void schedule(long tick,IslandScheduler.Kind kind,long id,long generation) {
        scheduler.schedule(tick,kind,id,generation);
        // Stale entries are popped lazily; compact only if they come to dominate the heap.
        if(scheduler.size()>4*(islands.size()+rounds.size()+8))scheduler.compact(this::live);
    }
    private boolean live(IslandScheduler.Deadline entry) {
        return switch(entry.kind()) {
            case SLICE_DUE,RETRY,CERTIFICATE_HORIZON->{var island=islands.get(entry.id());yield island!=null&&island.generation==entry.generation();}
            case ROUND_TIMEOUT->rounds.stream().anyMatch(round->round.id==entry.id());
            case ALLOCATOR_SHRINK->shrinkTick==entry.tick();
            case RECOVERY_RETRY,COLUMN_CYCLE->Objects.equals(externalTicks.get(entry.kind()),entry.tick());
            default->false;
        };
    }
    /** A round's first check comes when its budget would expire at the nominal tick length; a round that ticks
     * outran is checked again when its measured remainder would expire. */
    private void scheduleRoundTimeout(Round round,long remainingNanos) {
        long ticks=Math.max(1,(remainingNanos+NOMINAL_TICK_NANOS-1)/NOMINAL_TICK_NANOS);
        schedule(Math.addExact(epoch.getAsLong(),ticks),IslandScheduler.Kind.ROUND_TIMEOUT,round.id,0);
    }
    /** After each demand observation: the allocator's next shrink, if one is pending, becomes a deadline. */
    private void scheduleShrink() {
        long delay=dispatcher.demandShrinkDelay();
        long tick=delay<0?Long.MAX_VALUE:Math.addExact(epoch.getAsLong(),Math.max(1,delay));
        if(tick==shrinkTick)return;
        shrinkTick=tick;if(tick!=Long.MAX_VALUE)schedule(tick,IslandScheduler.Kind.ALLOCATOR_SHRINK,0,++generations);
    }
    void index(Island island){for(var event:island.fences.keySet())fenceOwners.computeIfAbsent(event,ignored->new HashSet<>()).add(island.id);}
    /** The island's owned world things: its stock and its coupled exchanger sides (a side keeps its relay state across replacements). */
    void indexStock(Island island) {
        for(var node:island.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)stockOwners.put(node.id(),island.id);
        for(long pipe:island.sides) {
            sideOwners.put(pipe,island.id);
            var side=coupled.computeIfAbsent(pipe,p->{var made=new Coupled(p,ExchangerCoupling.partnerOf(island.graph,p),ExchangerCoupling.kindOf(island.graph,p));pairSides.computeIfAbsent(made.pair(),k->new ArrayList<>(2)).add(made);return made;});
            if(side.current==null){
                double rate=island.lastResult.map(last->last.advancedSeconds()>0?ExchangerCoupling.heat(last,pipe)/last.advancedSeconds():0.).orElse(0.);
                side.record(new SideRecord(island.clock.committedTick(),ExchangerCoupling.stream(island.graph,island.lastResult.map(PassiveIntervalSolver.Result::endpointFlows).orElse(null),pipe,island.model),rate));
                // Its last span from its last interval (a loaded island's; a certified one replays that interval's rate up to its committed
                // tick): behind the fence the only span its other side may still read past its own start.
                island.lastResult.ifPresent(last->{long end=island.clock.committedTick(),start=Math.max(0,end-Math.round(settings.time.ticks(last.advancedSeconds())));if(end>start)side.book(start,end,ExchangerCoupling.heat(last,pipe));});
            }
        }
    }
    void unindexStock(Island island) {
        for(var node:island.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)stockOwners.remove(node.id(),island.id);
        for(long pipe:island.sides)sideOwners.remove(pipe,island.id);
    }
    void unindex(Island island){for(var event:island.fences.keySet()){var owners=fenceOwners.get(event);if(owners!=null){owners.remove(island.id);if(owners.isEmpty())fenceOwners.remove(event);}}}
    void removeFence(Island island,UUID event) {
        island.removeFence(event);var owners=fenceOwners.get(event);
        if(owners!=null){owners.remove(island.id);if(owners.isEmpty())fenceOwners.remove(event);}
    }
    /** In verification runs, the incremental schedule must equal a from-scratch derivation for every island. */
    private void verify() {
        if(!VERIFY)return;
        for(var island:islands.values()) {
            boolean eligible=false;long due=Long.MAX_VALUE;
            if(island.certificate!=null) {
                if(island.pendingEntries!=0||island.retained!=null)throw new IllegalStateException("Certified island "+island.id+" owns an attempt or solver caches");
                if(!stopped&&suspension==null) {
                    long wake=island.certificate.horizonTick();
                    if(wake!=Long.MAX_VALUE&&island.fence()>=wake)due=island.clock.epochTickAt(wake);
                }
            }
            else if(!stopped&&suspension==null&&island.pendingEntries==0&&island.capability==null) {
                long fence=island.fence();
                if(island.clock.nextSlice(1,fence,island.maximumSliceTicks).isPresent())eligible=coupling.coupledThrough(island);
                else{long at=island.clock.readyAtTick(fence,island.maximumSliceTicks);if(at!=Long.MAX_VALUE)due=island.clock.epochTickAt(at);}
            }
            long owning=pending.values().stream().filter(p->p.attempt.islandId==island.id).count();
            if(island.ready!=eligible||ready.isReady(island.id)!=eligible||island.deadline!=due||owning!=island.pendingEntries)
                throw new IllegalStateException("Scheduler state diverged for island "+island.id+": ready="+island.ready+"/"+ready.isReady(island.id)+" expected "+eligible
                        +", deadline="+island.deadline+" expected "+due+", pending="+island.pendingEntries+" expected "+owning+", epoch="+epoch.getAsLong());
            for(var event:island.fences.keySet())if(!fenceOwners.getOrDefault(event,Set.of()).contains(island.id))throw new IllegalStateException("Fence index lost island "+island.id);
            // The kept minimum and copy (R22) against the fences themselves.
            if(island.fence()!=island.fences.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE)||!island.frozenFences().equals(island.fences))
                throw new IllegalStateException("Kept fences diverged for island "+island.id);
        }
        if(ready.readyCount()!=islands.values().stream().filter(i->i.ready).count())throw new IllegalStateException("Ready queue holds unknown owners");
        for(var entry:fenceOwners.entrySet())for(long id:entry.getValue()){var island=islands.get(id);if(island==null||!island.fences.containsKey(entry.getKey()))throw new IllegalStateException("Fence index holds a stale owner "+id);}
        var stock=new HashMap<Long,Long>();for(var island:islands.values())for(var node:island.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)stock.put(node.id(),island.id);
        if(!stock.equals(stockOwners))throw new IllegalStateException("Stock index diverged from the islands' reservoirs");
    }
}
