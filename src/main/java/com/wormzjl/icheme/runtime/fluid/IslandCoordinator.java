package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.ProcessSolveServices;
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
        boolean submit(Attempt attempt, ProcessSolveServices.FluidIslandCommand command);
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
    public record Settings(long hardBudgetNanos, long softBudgetNanos, int maximumDispatchesPerTick, boolean adaptive,int initialCadenceTicks,CertificatePolicy certificates) {
        public Settings(long hardBudgetNanos,long softBudgetNanos,int maximumDispatchesPerTick,boolean adaptive){this(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,100);}
        public Settings(long hardBudgetNanos,long softBudgetNanos,int maximumDispatchesPerTick,boolean adaptive,int initialCadenceTicks){this(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,initialCadenceTicks,CertificatePolicy.disabled());}
        public Settings {
            if(hardBudgetNanos<=0||softBudgetNanos<=0||softBudgetNanos>=hardBudgetNanos||maximumDispatchesPerTick<1||initialCadenceTicks<20||initialCadenceTicks>400)
                throw new IllegalArgumentException("Invalid coordinator settings");
            Objects.requireNonNull(certificates);
        }
        public static Settings defaults(){return new Settings(2_000_000_000L,1_500_000_000L,64,true,100,CertificatePolicy.defaults());}
        public Settings withCertificates(CertificatePolicy policy){return new Settings(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,initialCadenceTicks,policy);}
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
        /** Equal when they say the same: the saved form is compared by its interval's ticks and its signature (which
         * digests the graph), since the graphs and results it carries have no value equality of their own. */
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
    private static final class Island {
        private final long id;
        private long revision;
        private final FluidThermodynamics model;
        private PassiveNetwork graph;
        private final IslandClock clock;
        private Optional<PassiveIntervalSolver.Result> lastResult;
        private final PipePresentation.SpeedWindow pipeSpeed = new PipePresentation.SpeedWindow();
        private String status;
        // The data-range notice of the last accepted interval (DataRangeNotices.line, empty for none): an accepted or certified
        // island's status line carries it after its own words, and a saved status line with it, which restores it.
        private String notice="";
        private Metrics metrics;
        private boolean suspended;
        // Ephemeral cost hint, not material state. A restart
        // may rediscover the hint; saved inventory, cadence, debt and fences stay authoritative.
        private int maximumSliceTicks=Integer.MAX_VALUE;
        // Holds in a row at the one-tick slice, for the retry wait; see {@link #hold}. Ephemeral like the slice hint.
        private int holdStreak;
        // The phase hold (a phase set the network cannot carry; leaving a data range is never one, owner rule of
        // 2026-10-16) the last held attempt failed on, awaiting its fresh-solver retry, and whether that
        // retry reproduced it (the island then waits for an input change); cleared by an accepted interval or an input
        // change. With the last warned hold's key and online tick, for the rate limit on the log. Ephemeral.
        private com.wormzjl.icheme.science.fluid.thermo.ThermoHold phaseHold;
        private boolean phaseParked;
        // The last attempt that closed was held (not accepted): a topology event fenced on the island is accepted at its
        // committed tick instead of waiting for a solve (acceptAtFence). Cleared by an accepted interval. Ephemeral.
        private boolean held;
        private String warnedHoldKey;
        private long warnedHoldAt=Long.MIN_VALUE;
        // Ephemeral solver caches for this island's jobs; replaced, never mutated, on a revision
        // bump, so a job still running under the old revision keeps its own handle.
        private RetainedSolver retained=new RetainedSolver();
        private final Map<UUID,Long> fences=new HashMap<>();
        // Scheduling state, never saved: attempts still owning this island (running, or closed but not yet
        // terminally drained), whether it is ready, and its one live deadline with that entry's generation.
        private int pendingEntries;
        private boolean ready;
        private long deadline=Long.MAX_VALUE,generation;
        // Certificate state: the certificate while certified and the tick the island first certified (both saved by a
        // checkpoint), and in memory only the one being revalidated by the slice solved at its horizon, the last
        // qualifying interval with the length of the stationary streak it ends, and the online tick a property hold
        // caps materialisation at (a certificate saved during a hold is not kept).
        private IslandCertificate certificate,revalidating;
        private long certifiedSince=-1,holdTick=Long.MAX_VALUE;
        private IslandCertificate.Summary lastInterval;
        private int streak;
        // Why the last closed interval did not certify the island (null once it did), and what the last comparison
        // of two consecutive solved intervals measured: diagnostics only, never read by a decision.
        private String refusal;
        private Evidence evidence;
        // The certificate's validity signature, made once when it is issued or restored; and the payload generation.
        private IslandCertificate.Signature signature;
        private long payloadGeneration;
        // The connection ids of its coupled exchanger sides (plan section 5.2): fixed for the island, whose connections a
        // commit never changes (validCandidate).
        private final long[] sides;
        /** An island registered from a snapshot that carries a payload generation (a loaded checkpoint's) keeps it, so
         * the checkpoint store's unit of that snapshot stays in place until something it records changes. */
        private Island(Snapshot saved,FluidThermodynamics model,LongSupplier epoch) {
            id=saved.id;revision=saved.revision;this.model=Objects.requireNonNull(model);graph=saved.graph;sides=coupledSides(graph);
            clock=new IslandClock(saved.clock,epoch);lastResult=saved.lastResult;status=saved.status;fences.putAll(saved.fences);
            notice=releaseLine(saved.lastResult,model)+com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices.suffix(saved.status);
            payloadGeneration=saved.payloadGeneration!=0?saved.payloadGeneration:PAYLOAD_GENERATIONS.incrementAndGet();
        }
        private Snapshot snapshot(){return new Snapshot(id,revision,graph,clock.snapshot(),lastResult,
                !suspended&&!clock.busy()&&fence()==clock.committedTick()?"WAITING: event alignment":status,fences,
                certificate==null?Optional.empty():Optional.of(new Certified(certificate.drift(),certifiedSince,certificate.baseTick(),certificate.horizonTick(),certificate.largestFlow(),
                        suspended?Optional.empty():Optional.of(new IslandCertificate.Saved(certifiedSince,certificate.horizonTick(),certificate.interval(),signature)))),
                payloadGeneration);}
        /** Something a checkpoint payload records changed: the next checkpoint encodes this island afresh. */
        private void touch(){payloadGeneration=PAYLOAD_GENERATIONS.incrementAndGet();}
        private long fence(){return fences.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);}
    }
    private static final class Pending {
        private final Attempt attempt;
        private ProcessSolveServices.FluidIslandSolveResult result;
        private boolean terminal,closed;
        private final long dispatchedAtTick,admittedNanos;
        // What the slice read of each coupled side's other side at its dispatch (plan section 5.2).
        private Map<Long,ExchangerRelay.Read> reads=Map.of();
        private Pending(Attempt attempt,long dispatchedAtTick,long admittedNanos){this.attempt=attempt;this.dispatchedAtTick=dispatchedAtTick;this.admittedNanos=admittedNanos;}
    }
    private final Thread owner=Thread.currentThread();
    private final Dispatcher dispatcher;
    private final Publisher publisher;
    private final LongSupplier nanoClock;
    private final Settings settings;
    private final LongSupplier epoch;
    private final boolean ownsEpoch;
    private long ownEpoch;
    private final Map<Long,Island> islands=new LinkedHashMap<>();
    private final Map<Long,Pending> pending=new HashMap<>();
    private final IslandScheduler.FairQueue ready=new IslandScheduler.FairQueue();
    private final IslandScheduler scheduler=new IslandScheduler();
    /** Event fences by event: which islands hold a fence for it, so an event finds its owners without a scan. */
    private final Map<UUID,Set<Long>> fenceOwners=new HashMap<>();
    /** Owned finite stock by reservoir identity: which island holds each reservoir, so a topology change can refuse
     * constructed stock that another island already holds without a scan of every island. */
    private final Map<Long,Long> stockOwners=new HashMap<>();
    /** Each coupled exchanger side's relay state by its connection id, which island holds each such side, the heat each
     * exchanger holds in transit by its pair key (the lower of its two sides' connection ids), and the certified islands a
     * partner's commit woke, discarded at the engine's next decision point (plan section 5.2, form C). */
    private final Map<Long,Coupled> coupled=new HashMap<>();
    private final Map<Long,Long> sideOwners=new HashMap<>();
    private final Map<Long,Double> transits=new HashMap<>();
    private final Set<Long> toWake=new LinkedHashSet<>();
    private boolean reconsideringPartners,waking;
    private record Round(long id,List<Pending> entries,long startedNanos) {}
    private final List<Round> rounds=new ArrayList<>();
    private long roundSequence,generations,shrinkTick=Long.MAX_VALUE;
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
    private Publisher replayed=changed->{};
    private int dispatchedThisTick;
    private boolean stopped,pumping;
    // Reasons a pump would act; each is set where it arises and all are cleared when a pump runs.
    private boolean pumpRequested,completionsSincePump,roundDue,shrinkDue;
    private String suspension;

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
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Island coordinator belongs to its server thread");}
    public void register(Snapshot saved,FluidThermodynamics model) {
        owned();if(stopped||islands.containsKey(saved.id))throw new IllegalStateException("Stopped/duplicate island");
        if(saved.graph.reservoirs().stream().anyMatch(n->n.kind()==PassiveNetwork.NodeKind.PORT))throw new IllegalArgumentException("Internal ports cannot own world state");
        var island=new Island(saved,model,epoch);
        var persisted=saved.certificate.flatMap(Certified::saved);
        if(persisted.isPresent())restore(island,persisted.orElseThrow());
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
    private static PassiveNetwork observedAs(PassiveNetwork graph,Map<Long,List<PassiveNetwork.Pipe.ObservationPoint>> byPipe) {
        return new PassiveNetwork(graph.reservoirs(),graph.pipes().stream().map(p->p.withObservationPoints(byPipe.getOrDefault(p.id(),List.of()))).toList(),graph.scheduledTransfers());
    }
    private static Map<Long,List<PassiveNetwork.Pipe.ObservationPoint>> observationPoints(PassiveNetwork graph) {
        var points=new HashMap<Long,List<PassiveNetwork.Pipe.ObservationPoint>>();for(var p:graph.pipes())if(!p.observationPoints().isEmpty())points.put(p.id(),p.observationPoints());return points;
    }
    /**
     * A saved certificate (plan section 3.5). The snapshot's graph is already its island at the saved committed tick,
     * materialised from the base without a solve. Restored when certificates are on and its signature is the island's
     * current one: the island is certified again with its saved since tick, keeps no solver caches, and holds only its
     * horizon deadline. Otherwise the certificate is discarded and the island starts awake from that
     * inventory, to solve its debt and qualify again.
     */
    private void restore(Island island,IslandCertificate.Saved saved) {
        var restored=IslandCertificate.restore(saved,island.model,settings.certificates());
        if(restored.certificate()!=null) {
            island.certificate=restored.certificate();island.certifiedSince=saved.sinceTick();island.signature=saved.signature();island.retained=null;
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesRestored);
        } else {
            // Awake now, with a new status: its unit records neither the certificate nor that status, so it is written afresh.
            island.status="WAITING: saved certificate discarded: "+restored.discarded();island.refusal="saved certificate discarded: "+restored.discarded();island.touch();
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesDiscarded);
        }
    }
    /** A certified island is materialised first, so what is read (a view, a save) is current. */
    public Snapshot snapshot(long id){owned();var island=require(id);materialise(island);return island.snapshot();}
    public Optional<Metrics> metrics(long id){owned();return Optional.ofNullable(require(id).metrics);}
    /**
     * A presentation read, for a view of a loaded device or an open menu: a certified island is materialised as by
     * {@link #snapshot(long)}, but never onto a wake it has not acted on yet (its horizon), so a
     * view can never move an island's schedule. The island's own deadline acts on the wake at the same tick with or
     * without viewers, and the materialised state is the certificate's state at that tick whatever the reads
     * before it: presentation cadence never drives scientific progress.
     */
    public Snapshot presentation(long id){owned();var island=require(id);materialise(island,true);return island.snapshot();}
    /** Reads only transport already accepted/materialised by the engine; never wakes or advances an island. */
    public Map<Long,Double> bulkVolumeRates(long id) {
        owned();var island=require(id);
        return island.pipeSpeed.volumeRates(island.clock.onlineTick());
    }

    /** Every island, certified ones materialised first; see {@link #snapshot(long)}. */
    public List<Snapshot> snapshots(){owned();for(var island:List.copyOf(islands.values()))materialise(island);return islands.values().stream().map(Island::snapshot).toList();}
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
        if(kind!=IslandScheduler.Kind.RECOVERY_RETRY)throw new IllegalArgumentException("Not an external deadline kind: "+kind);
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
            island.holdTick=island.clock.snapshot().onlineTick();materialise(island);
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
            island.holdTick=Long.MAX_VALUE;island.suspended=false;island.status="WAITING: full solve after property reload";inputsChanged(island);
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
                    materialise(island);reconsider(island);
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
                case RECOVERY_RETRY->{
                    var tick=externalTicks.get(due.kind());if(tick==null||tick!=due.tick())continue;
                    FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.deadlinesFired);externalTicks.remove(due.kind());externalActions.get(due.kind()).run();
                }
                default->throw new IllegalStateException("No handler for deadline "+due.kind());
            }
        }
        flushWakes();
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
                var next=ready.nextReady(id->{FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandVisits);var i=require(id);return i.pendingEntries==0&&i.clock.nextSlice(1,i.fence(),i.maximumSliceTicks).isPresent()&&coupledThrough(i);});
                if(next.isEmpty())break;
                var island=require(next.getAsLong());long request=dispatcher.nextRequestId();
                var slice=island.clock.nextSlice(request,island.fence(),island.maximumSliceTicks).orElseThrow();
                var attempt=new Attempt(island.id,island.revision,slice);
                // The job starts from the committed interval, not from the solver's history; see RetainedSolver.
                island.retained.committed(island.lastResult.orElse(null));
                var reads=reads(island,slice);
                var command=new ProcessSolveServices.FluidIslandCommand(island.model,island.graph,slice.seconds(),PassiveIntervalSolver.Settings.defaults(),settings.hardBudgetNanos,island.retained,reads);
                if(!dispatcher.submit(attempt,command)) {island.status="WAITING: shared worker capacity";island.touch();break;}
                island.clock.admitted(slice);island.status="SOLVING";island.touch();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.solvesDispatched);
                var entry=new Pending(attempt,island.clock.snapshot().onlineTick(),nanoClock.getAsLong());entry.reads=reads;pending.put(request,entry);island.pendingEntries++;admitted.add(entry);dispatchedThisTick++;
                reconsider(island);
            }
            if(!admitted.isEmpty()){var round=new Round(roundSequence=Math.addExact(roundSequence,1),List.copyOf(admitted),roundStarted);rounds.add(round);scheduleRoundTimeout(round,settings.hardBudgetNanos);}
            flushWakes();
        } finally {
            // Owners that became ready during this pump were offered to it; any left over wait for capacity,
            // which pumpIfUseful checks directly, so no request survives the pump.
            pumping=false;pumpRequested=false;
        }
    }
    private int capacity(){return Math.min(dispatcher.availableWorkers(),Math.min(settings.maximumDispatchesPerTick-dispatchedThisTick,settings.maximumDispatchesPerTick-pending.size()));}
    /** A missing result denotes cancellation, failure or abandonment. Duplicate/late results never advance clocks. */
    public void completed(Attempt attempt,Optional<ProcessSolveServices.FluidIslandSolveResult> result) {
        owned();Objects.requireNonNull(result);var entry=pending.get(attempt.slice.requestId());
        if(entry==null||!entry.attempt.equals(attempt)||entry.terminal)return;
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.completionsRouted);
        entry.terminal=true;entry.result=nanoClock.getAsLong()-entry.admittedNanos>=settings.hardBudgetNanos?null:result.orElse(null);
        completionsSincePump=true;
        if(entry.closed) {
            pending.remove(attempt.slice.requestId());var island=islands.get(attempt.islandId);
            if(island!=null){island.pendingEntries--;reconsider(island);}
            return;
        }
        // The completion router calls pump after its entire bounded drain, avoiding recursive dispatch here.
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
                accept=validCandidate(island,attempt,candidate);
                if(!accept)refusal="candidate identity or interval mismatch";
                else {
                    island.graph=candidate.graph();island.lastResult=Optional.of(candidate);
                    island.pipeSpeed.accepted(attempt.slice.startTick(),attempt.slice.endTick(),candidate.pipeTransfers());
                    island.notice=releaseLine(island.lastResult,island.model)+com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices.line(outcome.notices(),nodeNames);
                    island.status=outcome.detail()+island.notice;
                }
            }
            if(!accept)island.status=refusal!=null?"HELD: "+refusal:outcome==null?"HELD: round deadline or worker failure"
                    :outcome.phaseHold().isPresent()?phaseHoldStatus(outcome.phaseHold().orElseThrow(),false)
                    :outcome.detail().startsWith("HELD")?outcome.detail():"HELD: "+outcome.detail();
            island.clock.completed(attempt.slice,accept);island.held=!accept&&suspension==null&&attempt.revision==island.revision;
            if(accept)booked(island,attempt.slice.startTick(),attempt.slice.endTick(),island.lastResult.orElseThrow(),entry.reads);
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
            qualify(island,accept?outcome:null,before,attempt);
            reconsider(island);island.touch();
            changed.add(island.snapshot());
        }
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandsPublished,changed.size());
        publisher.published(List.copyOf(changed));
        flushWakes();
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
    private void hold(Island island,IslandClock.Slice slice,ProcessSolveServices.FluidIslandSolveResult outcome) {
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
    static boolean budgetHold(ProcessSolveServices.FluidIslandSolveResult outcome) {
        return outcome==null||outcome.detail().startsWith(ProcessSolveServices.WALL_DEADLINE);
    }
    /** Something the island solves against changed: a held island is retried at once, from its first retry again - a
     * phase hold included, which starts a new episode. */
    private static void inputsChanged(Island island){island.clock.inputsChanged();island.holdStreak=0;island.phaseHold=null;island.phaseParked=false;}
    /** The dedicated status line of a phase hold: a phase set the network cannot carry, see
     * {@link com.wormzjl.icheme.science.fluid.thermo.ThermoHold}. */
    private String phaseHoldStatus(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold,boolean parked) {
        return ProcessSolveServices.PHASE_HOLD+hold.status(where(hold))
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
            if(a.id()!=b.id()||a.first()!=b.first()||a.second()!=b.second()||!a.sections().equals(b.sections())||!a.control().equals(b.control())||(a.filter()==null)!=(b.filter()==null))return false;
            if(a.filter()!=null){if(a.filter().capacity()!=b.filter().capacity()||a.filter().cleanResistance()!=b.filter().cleanResistance()||a.filter().stoppedAtCapacity()&&!b.filter().stoppedAtCapacity())return false;
                for(var p:a.filter().captured().populations())if(b.filter().captured().mass(p.key())+1e-12<p.massKg())return false;}
        }return true;
    }
    private static boolean validCandidate(Island island,Attempt attempt,PassiveIntervalSolver.Result result) {
        if(result.advancedSeconds()!=attempt.slice.seconds()||!sameConnections(island.graph.pipes(),result.graph().pipes())||!result.graph().scheduledTransfers().isEmpty())return false;
        var old=island.graph.reservoirs();var next=result.graph().reservoirs();if(old.size()!=next.size())return false;
        for(int i=0;i<old.size();i++) {
            var a=old.get(i);var b=next.get(i);
            if(a.id()!=b.id()||a.kind()!=b.kind()||a.elevation()!=b.elevation()||(!a.junction()&&a.inventory().volume()!=b.inventory().volume()))return false;
        }
        return true;
    }
    /** Event fences must be installed before a solver can include their tick. */
    public void fence(UUID event,long tick,Collection<Long> affected) {
        owned();Objects.requireNonNull(event);
        for(long id:affected) {
            var island=require(id);
            if(tick<island.clock.committedTick()||pending.values().stream().anyMatch(p->p.attempt.islandId==id&&!p.closed&&p.attempt.slice.endTick()>tick))
                throw new IllegalStateException("Event would modify an admitted or committed interval");
            if(island.fences.containsKey(event))throw new IllegalStateException("Duplicate event fence");
        }
        for(long id:affected){var island=require(id);island.fences.put(event,tick);fenceOwners.computeIfAbsent(event,ignored->new HashSet<>()).add(id);inputsChanged(island);island.touch();reconsider(island);}
    }
    /** A certified owner answers by materialisation and a held one by {@link #acceptAtFence}: each advances to the
     * fence and is then aligned like any other. */
    public boolean aligned(UUID event,Collection<Long> affected) {
        owned();for(long id:affected){var island=require(id);materialise(island);acceptAtFence(island);}
        return affected.stream().allMatch(id->{var i=require(id);var tick=i.fences.get(event);return tick!=null&&i.clock.committedTick()==tick&&i.pendingEntries==0;});
    }
    /** A batch's owners: each affected island holds a fence of at least one of the events, every one of them at its
     * committed tick, and runs no attempt; certified ones are materialised first, as for one event. */
    private boolean aligned(Set<UUID> events,Collection<Long> affected) {
        for(long id:affected){var island=require(id);materialise(island);acceptAtFence(island);}
        for(long id:affected) {
            var island=require(id);if(island.pendingEntries!=0)return false;boolean fenced=false;
            for(var event:events){var tick=island.fences.get(event);if(tick==null)continue;if(tick!=island.clock.committedTick())return false;fenced=true;}
            if(!fenced)return false;
        }
        return true;
    }
    /**
     * A held owner accepts the event fenced on it at its committed tick (owner decision 2026-10-15): a held island
     * cannot solve its way to the fence, and a world change - a broken pipe, a new valve setting - is often the very
     * thing that frees it. Its inventory stands still over the unsolved span (no flow, so mass and energy are exact)
     * and the clock moves to the fence without a solve, as a certified island's materialisation does; the topology
     * change then replaces the island with a fresh one that starts its own full solve. Only an island whose last
     * attempt was held, with no attempt in flight, outside a suspension; anything else aligns by solving.
     */
    private void acceptAtFence(Island island) {
        if(!island.held||island.certificate!=null||island.pendingEntries!=0||island.clock.busy()||suspension!=null)return;
        long committed=island.clock.committedTick(),fence=island.fence();
        if(fence==Long.MAX_VALUE||fence<=committed||fence>island.clock.onlineTick())return;
        island.clock.rest(fence,fence);island.held=false;
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.heldEventAcceptances);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.droppedHeldTicks,fence-committed);
        island.touch();reconsider(island);
    }
    public void releaseFence(UUID event,Collection<Long> affected) {
        owned();if(!aligned(event,affected))throw new IllegalStateException("Event owners have not aligned");
        for(long id:affected){var i=require(id);removeFence(i,event);inputsChanged(i);i.touch();reconsider(i);}
    }
    public record Replacement(long id,PassiveNetwork graph) {
        public Replacement {if(id<=0)throw new IllegalArgumentException("Invalid replacement identity");Objects.requireNonNull(graph);}
    }
    /**
     * A batch of events of one tick applied as one change: every affected island holds a fence of at least one of them,
     * each at its committed tick, and none of them survives into a replacement. Declared additions and removals must
     * exactly match the stock delta; metadataCommit publishes the already-prepared construction/destruction ledger and
     * registry atomically. A merge or split without placement or destruction changes no owned reservoir inventory;
     * placement, destruction and material delivery need their own explicitly balanced transactions.
     */
    public void topology(Set<UUID> events,Set<Long> affected,List<Replacement> replacements,FluidThermodynamics model,long committed,long online,Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,com.wormzjl.icheme.science.fluid.network.InlineFilter> releasedFilters,Runnable metadataCommit) {
        owned();Objects.requireNonNull(model);Objects.requireNonNull(metadataCommit);if(events.isEmpty())throw new IllegalArgumentException("A topology change needs its events");
        if(stopped||committed<0||online<committed||!aligned(events,affected))throw new IllegalStateException("Topology event is not ready");
        var originals=affected.stream().map(this::require).toList();
        long revision=0;int cadence=originals.isEmpty()?settings.initialCadenceTicks:20;
        var stock=new HashMap<Long,PassiveNetwork.Reservoir>();var fences=new HashMap<UUID,Long>();
        for(var old:originals) {
            if(old.clock.committedTick()!=committed||old.clock.snapshot().onlineTick()!=online||!old.model.revision().equals(model.revision()))throw new IllegalStateException("Incompatible clocks or property packages");
            revision=Math.max(revision,old.revision);cadence=Math.max(cadence,old.clock.snapshot().cadenceTicks());
            for(var node:old.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&stock.putIfAbsent(node.id(),node)!=null)throw new IllegalStateException("Duplicate stock ownership");
            for(var fence:old.fences.entrySet())if(!events.contains(fence.getKey())) {
                Long prior=fences.putIfAbsent(fence.getKey(),fence.getValue());if(prior!=null&&!prior.equals(fence.getValue()))throw new IllegalStateException("Conflicting event timestamps");
            }
        }
        for(long removed:removals)if(stock.remove(removed)==null)throw new IllegalStateException("Destruction did not name an owned reservoir");
        for(var entry:additions.entrySet()) {
            var node=entry.getValue();
            if(node.id()!=entry.getKey()||node.kind()!=PassiveNetwork.NodeKind.RESERVOIR||stock.putIfAbsent(node.id(),node)!=null||removals.contains(node.id()))throw new IllegalStateException("Invalid constructed stock identity");
            Long holder=stockOwners.get(node.id());if(holder!=null&&!affected.contains(holder))throw new IllegalStateException("Constructed stock already exists");
        }
        revision=Math.addExact(revision,1);var seen=new HashSet<Long>();var ids=new HashSet<Long>();var staged=new ArrayList<Island>();
        for(var replacement:replacements) {
            if(!ids.add(replacement.id)||islands.containsKey(replacement.id)&&!affected.contains(replacement.id))throw new IllegalStateException("Replacement island identity collision");
            for(var node:replacement.graph.reservoirs()) {
                if(node.kind()==PassiveNetwork.NodeKind.PORT)throw new IllegalArgumentException("Internal port in topology");
                if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
                // What a vessel holds is conserved; where it stands and its geometry follow the registry (a layout refresh
                // rebuilds it, decision D7), and no ledger carries potential energy (D37, S1).
                var old=stock.get(node.id());
                if(!seen.add(node.id())||old==null||!old.inventory().sameContents(node.inventory()))throw new IllegalStateException("Topology event changed stock");
            }
            // Owner decision of 2026-10-19 (t46's trace of bundled 483): a replacement whose graph is one original's (the same pipe
            // identities and node ids) carries that island's committed junction states and interval endpoint, the warm carry
            // across the replacement, so nothing of it is balanced anew: a plant does not notice a filter placed on a dead-end
            // line beyond its tank. The mint's states are discarded; each carried junction's buffer is sized on its carried
            // state by the mint's rule (t48's trace of bundled 145).
            PassiveNetwork graph=replacement.graph;Optional<PassiveIntervalSolver.Result> carried=Optional.empty();
            var same=originals.stream().filter(old->old.lastResult.isPresent()&&PassiveNetwork.sameStructure(old.graph,replacement.graph)).toList();
            if(same.size()==1){var old=same.get(0);graph=PassiveNetwork.withJunctionStates(replacement.graph,old.graph,model);carried=old.lastResult;}
            var island=new Island(new Snapshot(replacement.id,revision,graph,new IslandClock.Snapshot(online,committed,0,cadence),carried,"WAITING: full solve after topology change",fences),model,epoch);
            // A topology change starts the island cold: its first slice is one tick and each accepted full interval
            // doubles it back to the cadence (as after a budget hold). Whatever the player just built - a pump
            // filling dry tanks, a valve opening - starts its transient here, and its first milliseconds can cost
            // more than the wall budget: measured in game, 100 pumped fills placed at once spent their first 100 s
            // being cut at 100, 50, 25, 12, 6 and 3 ticks before a slice fitted, 2 s of work thrown away each time.
            // A quiet island pays six short solves instead. Islands registered from a save start at their cadence.
            island.maximumSliceTicks=1;
            staged.add(island);
        }
        if(!seen.equals(stock.keySet()))throw new IllegalStateException("Topology event lost reservoir ownership");
        var oldFilters=new HashMap<Long,com.wormzjl.icheme.science.fluid.network.InlineFilter>();for(var original:originals)for(var pipe:original.graph.pipes())if(pipe.filter()!=null)oldFilters.put(pipe.id(),pipe.filter());
        for(var release:releasedFilters.entrySet())if(!release.getValue().equals(oldFilters.get(release.getKey())))throw new IllegalStateException("Stale filter recovery");
        var seenFilters=new HashSet<Long>();for(var replacement:replacements)for(var pipe:replacement.graph().pipes())if(pipe.filter()!=null){
            if(!seenFilters.add(pipe.id()))throw new IllegalStateException("Duplicate filter ownership");var old=oldFilters.remove(pipe.id());
            if(old==null||releasedFilters.containsKey(pipe.id())){if(!pipe.filter().captured().empty()||pipe.filter().energyJoule()!=0)throw new IllegalStateException("Undeclared filter inventory creation");}
            else if(!old.captured().equals(pipe.filter().captured())||old.energyJoule()!=pipe.filter().energyJoule())throw new IllegalStateException("Topology changed filter inventory");
        }
        for(var removed:oldFilters.entrySet())if(!removed.getValue().captured().empty()&&!releasedFilters.containsKey(removed.getKey()))throw new IllegalStateException("Topology lost captured solids");
        metadataCommit.run();
        for(long id:affected){var old=islands.remove(id);ready.remove(id);unindex(old);unindexStock(old);old.deadline=Long.MAX_VALUE;old.generation=++generations;}
        for(var island:staged){islands.put(island.id,island);ready.register(island.id,false);index(island);indexStock(island);reconsider(island);}
        // A side no island holds any more keeps no relay state; its unit's heat in transit stays until the unit is removed.
        coupled.keySet().removeIf(pipe->!sideOwners.containsKey(pipe));
        publisher.published(staged.stream().map(Island::snapshot).toList());
    }
    /** Stops admission; unresolved proposals are held. Actual execution capacity is owned by the shared pool. */
    public void stop() {
        owned();if(stopped)return;stopped=true;
        var closing=List.copyOf(rounds);rounds.clear();
        for(var round:closing){for(var entry:round.entries)entry.result=null;closeRound(round);}
        scheduler.clear();externalTicks.clear();externalActions.clear();
    }
    private Island require(long id){var value=islands.get(id);if(value==null)throw new IllegalArgumentException("Unknown island "+id);return value;}

    /**
     * Re-derives one island's readiness and its deadline after anything that decides them changed. An island
     * is ready exactly when the polled coordinator's eligibility test held: no attempt owns it and its clock
     * offers a slice. Otherwise its clock says from which online tick time alone would make it ready, and that
     * becomes its single deadline; a stopped or suspended coordinator keeps no island ready or scheduled.
     */
    private void reconsider(Island island) {
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
        else if(!stopped&&suspension==null&&island.pendingEntries==0) {
            long fence=island.fence();
            // Behind the coupling fence a slice waits for its exchangers' other sides, whose commits re-derive it (below).
            if(island.clock.nextSlice(1,fence,island.maximumSliceTicks).isPresent())eligible=coupledThrough(island);
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
            try{for(var other:partners(island))reconsider(other);}
            finally{reconsideringPartners=false;}
        }
    }

    // ---- exchanger coupling (batch 2026-10-27-heat-exchanger-design, plan section 5.2; owner Q16: form C, the fence) ----

    /** An exchanger's side's stream at a commit or a replay's end: what its other side reads as of a later slice start. */
    private record SideRecord(long tick,ExchangerRelay.Partner stream) {}
    /** A committed or replayed span of a side and the heat its stream took up over it, J. */
    private record Booked(long start,long end,double joule) {}
    /** A side keeps at most this many booked spans; older ones merge (the transit stays exact, only an as-of split moves). */
    private static final int MAXIMUM_BOOKED=64;
    /** Decision D4: a side's commit wakes its certified other side when its stream's temperature moved by more than this
     * (K), its flow by more than this share, or the transit grew past this share of that side's slice heat (plus 1 J). */
    static final double WAKE_TEMPERATURE=.05,WAKE_FLOW=1e-3,WAKE_TRANSIT=1e-3;
    /**
     * One coupled exchanger side's relay state, kept by its connection id across the replacements of its island: its last
     * two records (behind the fence its other side is at most one slice past a read's start, decision D1), its booked spans
     * that end past its other side's committed tick, the spans in which it counted its other side stopped, what its last
     * solved slice read of its other side, and that slice's heat rate (W).
     */
    private static final class Coupled {
        final long pipe,partner;
        SideRecord current,previous;
        final ArrayDeque<Booked> booked=new ArrayDeque<>();
        final ArrayDeque<long[]> sawStopped=new ArrayDeque<>();
        // What its last solved slice read of its other side; until a slice of this run read it (a loaded island's), the
        // first record its other side commits is taken as what it read.
        ExchangerRelay.Partner readUsed;
        boolean read;
        double heatRate;
        Coupled(long pipe,long partner){this.pipe=pipe;this.partner=partner;}
        long pair(){return Math.min(pipe,partner);}
        void record(SideRecord next){if(current!=null&&current.tick()<next.tick())previous=current;current=next;}
        /** The stream as of {@code tick}: the latest record at or before it, else the current one (after a load, decision D1). */
        ExchangerRelay.Partner asOf(long tick) {
            if(current==null)return null;
            return current.tick()<=tick||previous==null||previous.tick()>tick?current.stream():previous.stream();
        }
        /** The heat booked past {@code tick}, J: each span's share of its own beyond it. */
        double bookedPast(long tick) {
            double sum=0;for(var b:booked)if(b.end()>tick)sum+=b.joule()*(b.end()-Math.max(b.start(),tick))/(double)(b.end()-b.start());
            return sum;
        }
        /** The share of {@code [start, end]} in which this side counted its other side stopped. */
        double stoppedWithin(long start,long end) {
            long sum=0;for(var s:sawStopped)sum+=Math.max(0,Math.min(end,s[1])-Math.max(start,s[0]));
            return Math.min(1,sum/(double)(end-start));
        }
        void book(long start,long end,double joule) {
            booked.addLast(new Booked(start,end,joule));
            if(booked.size()>MAXIMUM_BOOKED){var a=booked.removeFirst();var b=booked.removeFirst();booked.addFirst(new Booked(a.start(),b.end(),a.joule()+b.joule()));}
        }
        void stopped(long start,long end){var last=sawStopped.peekLast();if(last!=null&&last[1]==start)last[1]=end;else sawStopped.addLast(new long[]{start,end});}
        /** Forgets what its other side, committed through {@code tick}, never reads again. */
        void prune(long tick) {
            while(!booked.isEmpty()&&booked.peekFirst().end()<=tick)booked.removeFirst();
            while(!sawStopped.isEmpty()&&sawStopped.peekFirst()[1]<=tick)sawStopped.removeFirst();
        }
    }
    private static long[] coupledSides(PassiveNetwork graph) {
        return graph.pipes().stream().filter(p->p.heat() instanceof HeatSide side&&side.coupled()).mapToLong(PassiveNetwork.Pipe::id).toArray();
    }
    private static long partnerOf(PassiveNetwork graph,long pipe) {
        for(var p:graph.pipes())if(p.id()==pipe&&p.heat() instanceof HeatSide side)return side.partner();
        throw new IllegalArgumentException("No coupled side "+pipe);
    }
    /** {@code pipe}'s stream in {@code graph}, drawn at {@code flows} (kg/s per connection; null where none is known: no flow). */
    private static ExchangerRelay.Partner stream(PassiveNetwork graph,double[] flows,long pipe) {
        var pipes=graph.pipes();
        for(int e=0;e<pipes.size();e++) {
            var p=pipes.get(e);if(p.id()!=pipe||!(p.heat() instanceof HeatSide law))continue;
            double flow=flows!=null&&flows.length==pipes.size()?flows[e]:0;int donor=flow>=0?p.first():p.second();
            return new ExchangerRelay.Partner(graph.reservoirs().get(donor).state(),p.portAt(donor),p.volume(),flow,law);
        }
        return null;
    }
    /** The island holding {@code side}'s other side, or null where none does. */
    private Island other(Coupled side){Long owner=sideOwners.get(side.partner);return owner==null?null:islands.get(owner);}
    /** The other islands holding the other sides of {@code island}'s exchangers. */
    private List<Island> partners(Island island) {
        var found=new ArrayList<Island>();
        for(long pipe:island.sides){var side=coupled.get(pipe);if(side==null)continue;var other=other(side);if(other!=null&&other!=island&&!found.contains(other))found.add(other);}
        return found;
    }
    /** Plan section 5.2: an explicit stopped state (held, phase-parked, suspended), never lag. */
    private static boolean stoppedSide(Island island){return island.held||island.phaseParked||island.suspended;}
    /**
     * The coupling fence (owner Q16, form C): each of the island's exchangers' other sides has committed through the
     * island's committed tick, or can be materialised there (certified), or is stopped, or has no island, or is this one.
     * A pure read: nothing is materialised here.
     */
    private boolean coupledThrough(Island island) {
        long start=island.clock.committedTick();
        for(long pipe:island.sides) {
            var side=coupled.get(pipe);if(side==null)continue;
            var other=other(side);if(other==null||other==island||stoppedSide(other)||other.clock.committedTick()>=start)continue;
            var certificate=other.certificate;
            if(certificate!=null&&Math.min(Math.min(other.clock.onlineTick(),other.fence()),Math.min(other.holdTick,certificate.horizonTick()))>=start)continue;
            return false;
        }
        return true;
    }
    /**
     * What each of the island's coupled sides reads for {@code slice} (plan section 5.2): its other side's stream as of the
     * slice start, whether that side is stopped, the heat in transit as of the start (the other side's heat booked past it
     * taken out again), that side's slice length and the share of this slice it spent counting this one stopped. A certified
     * other side behind the start is materialised first. Arithmetic only.
     */
    private Map<Long,ExchangerRelay.Read> reads(Island island,IslandClock.Slice slice) {
        if(island.sides.length==0)return Map.of();
        var reads=new HashMap<Long,ExchangerRelay.Read>();long start=slice.startTick(),end=slice.endTick();
        for(long pipe:island.sides) {
            var side=coupled.get(pipe);if(side==null)continue;
            var other=other(side);var otherSide=coupled.get(side.partner);
            if(other!=null&&other!=island&&other.certificate!=null&&other.clock.committedTick()<start)materialise(other);
            boolean stoppedOther=other==null||other!=island&&stoppedSide(other);
            double due=transits.getOrDefault(side.pair(),0.)+(otherSide==null?0:otherSide.bookedPast(start));
            double seconds=other==null?0:other==island?slice.seconds():Math.min(other.clock.snapshot().cadenceTicks(),other.maximumSliceTicks)/20.0;
            reads.put(pipe,new ExchangerRelay.Read(otherSide==null?null:otherSide.asOf(start),stoppedOther,due,seconds,otherSide==null?0:otherSide.stoppedWithin(start,end)));
        }
        return reads;
    }
    /**
     * Books {@code island}'s coupled sides over a committed or replayed span from {@code start} to {@code end} (plan section
     * 5.2): each side's heat leaves the heat in transit, the span is kept for its other side's as-of reads, a span in which
     * it read its other side stopped is kept for that side to sit out, and its record moves to the span's end. {@code reads}
     * is what a solved slice read (null for a replay). A certified other side whose read of this one moved is then woken.
     */
    private void booked(Island island,long start,long end,PassiveIntervalSolver.Result result,Map<Long,ExchangerRelay.Read> reads) {
        for(long pipe:island.sides) {
            var side=coupled.get(pipe);if(side==null)continue;
            double joule=heat(result,pipe);
            if(joule!=0)transits.merge(side.pair(),-joule,Double::sum);
            var other=other(side);side.prune(other==null?end:other.clock.committedTick());side.book(start,end,joule);
            var read=reads==null?null:reads.get(pipe);
            if(read!=null){if(read.stopped())side.stopped(start,end);side.read=true;side.readUsed=read.partner();side.heatRate=joule/((end-start)/20.0);}
            side.record(new SideRecord(end,stream(island.graph,result.endpointFlows(),pipe)));
            if(other!=null&&other!=island&&other.certificate!=null&&moved(side,coupled.get(side.partner),other))toWake.add(other.id);
        }
    }
    /** The heat (J) {@code pipe}'s stream took up over {@code result}'s span. */
    private static double heat(PassiveIntervalSolver.Result result,long pipe) {
        double joule=0;for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT&&b.nodeId()==pipe)joule+=b.totalEnergyJoule();
        return joule;
    }
    /** Decision D4: whether {@code otherSide}'s certified island must solve again after {@code side}'s commit. */
    private boolean moved(Coupled side,Coupled otherSide,Island other) {
        if(otherSide==null||side.current==null||side.current.stream()==null)return false;
        var now=side.current.stream();
        if(!otherSide.read){otherSide.read=true;otherSide.readUsed=now;return false;}
        var used=otherSide.readUsed;if(used==null)return true;
        double slice=Math.min(other.clock.snapshot().cadenceTicks(),other.maximumSliceTicks)/20.0;
        return Math.abs(now.donor().temperature()-used.donor().temperature())>WAKE_TEMPERATURE
                ||Math.abs(now.massFlow()-used.massFlow())>WAKE_FLOW*Math.abs(used.massFlow())
                ||Math.abs(transits.getOrDefault(side.pair(),0.))>WAKE_TRANSIT*Math.abs(otherSide.heatRate)*slice+1;
    }
    /** The certified islands woken by an exchanger partner's commit (decision D4) end their certificates here, at the engine's
     * decision points (a round's close, the due deadlines, a pump), never inside a presentation read. */
    private void flushWakes() {
        if(waking||toWake.isEmpty())return;waking=true;
        try {
            while(!toWake.isEmpty()) {
                var it=toWake.iterator();long id=it.next();it.remove();
                var island=islands.get(id);if(island==null||island.certificate==null||stopped||suspension!=null)continue;
                // Its replay up to now first (which books its heat); that may reach its horizon, which ends it anyway.
                materialise(island);if(island.certificate==null)continue;
                island.certificate=null;island.revalidating=null;island.certifiedSince=-1;island.signature=null;island.retained=new RetainedSolver();
                island.lastInterval=null;island.streak=0;island.status="WAITING: an exchanger's other side changed";island.touch();
                FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.couplingWakes);reconsider(island);
            }
        } finally{waking=false;}
    }
    /** The heat each exchanger holds in transit, J (the energy owed to its streams), by pair key: the lower of its two sides' connection ids. */
    public Map<Long,Double> transits(){owned();return Map.copyOf(transits);}
    /**
     * An exchanger's heat in transit as of the side further behind, J: the transit with the heat the side ahead booked past
     * that side's committed tick taken out again. The transit itself holds that heat until the other side books the same
     * span; this is what the exchanger holds once both have, and what a give-back returns.
     */
    public double settledTransit(long pair) {
        owned();double joule=transits.getOrDefault(pair,0.);
        Coupled a=null,b=null;for(var side:coupled.values())if(side.pair()==pair){if(a==null)a=side;else b=side;}
        if(a==null||b==null)return joule;
        var ia=other(b);var ib=other(a);if(ia==null||ib==null)return joule;
        long behind=Math.min(ia.clock.committedTick(),ib.clock.committedTick());
        return joule+a.bookedPast(behind)+b.bookedPast(behind);
    }
    /** Restores an exchanger's heat in transit, J, from the world's facts (a load). */
    public void transit(long pair,double joule) {
        owned();if(!Double.isFinite(joule))throw new IllegalArgumentException("Invalid heat in transit");
        if(joule==0)transits.remove(pair);else transits.put(pair,joule);
    }
    /** Takes an exchanger's heat in transit out (its unit was removed and takes it along), J. */
    public double removeTransit(long pair){owned();Double joule=transits.remove(pair);return joule==null?0:joule;}

    // ---- certificates (plan section 3.2 and 3.3) ----

    /**
     * Advances a certified island to {@code target = min(online, nearest fence, hold tick, horizon)} and acts on
     * what it reached: the horizon wakes it to solve one revalidating slice. On demand only: a fence or event
     * alignment, a read of its snapshot (a save), a view (a presentation read, which never wakes), its own deadline. Each
     * advance is reported to the replay listener with
     * the replayed span as the island's last result, so boundary and pump accounting stays exact.
     */
    private void materialise(Island island){materialise(island,false);}
    /** {@code presentation}: a view's read, which stops one tick short of a wake not yet acted on and never wakes. */
    private void materialise(Island island,boolean presentation) {
        var certificate=island.certificate;if(certificate==null)return;
        long committed=island.clock.committedTick(),online=island.clock.onlineTick();
        long wake=certificate.horizonTick();
        long target=Math.min(Math.min(online,island.fence()),Math.min(island.holdTick,wake));
        if(presentation&&target==wake)target=wake-1;
        if(target>committed) {
            // The island keeps its last solved interval as its result (what a save records and a view reads, whose
            // rates replay repeats); the replayed span with its scaled accounting goes to the replay listener only.
            var replay=certificate.replay(committed,target);
            // The replayed graph is the certificate's; the taps are the island's (a restored certificate's graph has none).
            island.clock.rest(target,island.fence());island.graph=observedAs(replay.graph(),observationPoints(island.graph));
            island.pipeSpeed.accepted(committed,target,replay.pipeTransfers());
            booked(island,committed,target,replay,null);
            island.metrics=new Metrics(island.metrics==null?1:Math.addExact(island.metrics.sequence(),1),committed,target,online,online,-1,-1,0,true,Advance.REPLAYED);
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.materialisations);
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.replayedTicks,target-committed);
            if(suspension==null)island.status=certifiedStatus(island);
            // A certified payload records the base, not the materialised state, so materialisation changes nothing a
            // checkpoint keeps - except that an island stopped at its fence reads "WAITING: event alignment".
            if(target==island.fence())island.touch();
            var stored=island.snapshot();
            replayed.published(List.of(new Snapshot(stored.id(),stored.revision(),stored.graph(),stored.clock(),Optional.of(replay),stored.status(),stored.fences(),stored.certificate(),stored.payloadGeneration())));
        }
        if(presentation||stopped||suspension!=null)return;
        if(target==wake)revalidate(island,certificate);
    }
    /** Ends a certificate at its horizon: fresh solver caches, no evidence; the island keeps the certificate its first
     * slice is compared with. */
    private void revalidate(Island island,IslandCertificate certificate) {
        island.certificate=null;island.revalidating=certificate;island.retained=new RetainedSolver();
        island.lastInterval=null;island.streak=0;
        island.status="REVALIDATING: certificate horizon at "+certificate.horizonTick()/20.0+" s";island.signature=null;island.touch();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificateWakes);
        reconsider(island);
    }
    /**
     * The entry evidence of plan section 3.3, evaluated after each round closure on the server thread. An
     * accepted interval extends the stationary streak
     * when it repeats the previous interval within the tolerance, or starts a new one; once the streak reaches the
     * confirm count, the last interval is certified. A horizon's
     * revalidating slice renews the certificate from its solved state if it repeats the certified interval, and
     * otherwise starts a fresh streak.
     */
    private void qualify(Island island,ProcessSolveServices.FluidIslandSolveResult accepted,PassiveNetwork before,Attempt attempt) {
        var revalidation=island.revalidating;island.revalidating=null;var policy=settings.certificates();
        boolean usable=policy.enabled()&&!stopped&&suspension==null&&accepted!=null;
        if(!usable) {
            island.lastInterval=null;island.streak=0;island.evidence=null;if(revalidation!=null)island.certifiedSince=-1;
            island.refusal=!policy.enabled()?null:accepted==null?"no accepted interval":"stopped or suspended";return;
        }
        var summary=new IslandCertificate.Summary(new IslandCertificate.Interval(attempt.slice.startTick(),attempt.slice.endTick(),before,accepted.candidate().orElseThrow()),island.model.molecularWeights());
        if(revalidation!=null) {
            var measured=IslandCertificate.stationarity(revalidation.summary(),summary);
            island.evidence=new Evidence(summary.endTick,measured,summary.drift());
            String refusal=measured.refusal(policy.stationaryTolerance());
            if(refusal==null) {
                var issued=IslandCertificate.issue(summary,policy);
                if(issued.certificate()!=null){certify(island,issued.certificate());FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesRenewed);return;}
                refusal=issued.refusal();
            }
            island.certifiedSince=-1;island.lastInterval=summary;island.streak=1;island.refusal="revalidation: "+refusal;return;
        }
        var previous=island.lastInterval;
        var measured=previous==null||previous.endTick!=summary.startTick?null:IslandCertificate.stationarity(previous,summary);
        island.evidence=new Evidence(summary.endTick,measured,summary.drift());
        String refusal=previous==null?"first qualifying interval":measured==null?"not consecutive with the last one":measured.refusal(policy.stationaryTolerance());
        island.streak=refusal==null?island.streak+1:1;island.lastInterval=summary;
        int needed=summary.exactZero?policy.confirmIntervals():Math.max(2,policy.confirmIntervals());
        if(island.streak<needed){island.refusal=refusal!=null?refusal:"stationary for "+island.streak+" of "+needed+" intervals";return;}
        var issued=IslandCertificate.issue(summary,policy);
        if(issued.certificate()!=null){island.certifiedSince=-1;certify(island,issued.certificate());}
        else island.refusal=issued.refusal();
    }
    private void certify(Island island,IslandCertificate certificate) {
        island.certificate=certificate;if(island.certifiedSince<0)island.certifiedSince=certificate.baseTick();
        island.signature=IslandCertificate.Signature.of(island.model,settings.certificates(),certificate.interval().result().graph());island.touch();
        island.lastInterval=null;island.streak=0;island.refusal=null;
        // Only here, after the terminal completion that produced the qualifying interval, so no worker owns the caches.
        island.retained=null;island.status=certifiedStatus(island);FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesIssued);
    }
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
    private static String certifiedStatus(Island island) {
        var certificate=island.certificate;double since=island.certifiedSince/20.0;
        // One certificate kind (BE_INTEGRATOR_PLAN.md section 4): the line names what it replays, and its recheck if it has one.
        String what=certificate.largestFlow()==0?"no flow":String.format(Locale.ROOT,"replaying %.4g kg/s",certificate.largestFlow());
        String next=certificate.horizonTick()==Long.MAX_VALUE?"":String.format(Locale.ROOT,", next check at %.1f s",certificate.horizonTick()/20.0);
        return String.format(Locale.ROOT,"STEADY: %s since %.1f s%s",what,since,next)+island.notice;
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
            case RECOVERY_RETRY->Objects.equals(externalTicks.get(entry.kind()),entry.tick());
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
    private void index(Island island){for(var event:island.fences.keySet())fenceOwners.computeIfAbsent(event,ignored->new HashSet<>()).add(island.id);}
    /** The island's owned world things: its stock and its coupled exchanger sides (a side keeps its relay state across replacements). */
    private void indexStock(Island island) {
        for(var node:island.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)stockOwners.put(node.id(),island.id);
        for(long pipe:island.sides) {
            sideOwners.put(pipe,island.id);
            var side=coupled.computeIfAbsent(pipe,p->new Coupled(p,partnerOf(island.graph,p)));
            if(side.current==null){
                side.record(new SideRecord(island.clock.committedTick(),stream(island.graph,island.lastResult.map(PassiveIntervalSolver.Result::endpointFlows).orElse(null),pipe)));
                // Its last span from its last interval (a loaded island's; a certified one replays that interval's rate up to its committed
                // tick): behind the fence the only span its other side may still read past its own start.
                island.lastResult.ifPresent(last->{long end=island.clock.committedTick(),start=Math.max(0,end-Math.round(last.advancedSeconds()*20));if(end>start)side.book(start,end,heat(last,pipe));});
            }
        }
    }
    private void unindexStock(Island island) {
        for(var node:island.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)stockOwners.remove(node.id(),island.id);
        for(long pipe:island.sides)sideOwners.remove(pipe,island.id);
    }
    private void unindex(Island island){for(var event:island.fences.keySet()){var owners=fenceOwners.get(event);if(owners!=null){owners.remove(island.id);if(owners.isEmpty())fenceOwners.remove(event);}}}
    private void removeFence(Island island,UUID event) {
        island.fences.remove(event);var owners=fenceOwners.get(event);
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
            else if(!stopped&&suspension==null&&island.pendingEntries==0) {
                long fence=island.fence();
                if(island.clock.nextSlice(1,fence,island.maximumSliceTicks).isPresent())eligible=coupledThrough(island);
                else{long at=island.clock.readyAtTick(fence,island.maximumSliceTicks);if(at!=Long.MAX_VALUE)due=island.clock.epochTickAt(at);}
            }
            long owning=pending.values().stream().filter(p->p.attempt.islandId==island.id).count();
            if(island.ready!=eligible||ready.isReady(island.id)!=eligible||island.deadline!=due||owning!=island.pendingEntries)
                throw new IllegalStateException("Scheduler state diverged for island "+island.id+": ready="+island.ready+"/"+ready.isReady(island.id)+" expected "+eligible
                        +", deadline="+island.deadline+" expected "+due+", pending="+island.pendingEntries+" expected "+owning+", epoch="+epoch.getAsLong());
            for(var event:island.fences.keySet())if(!fenceOwners.getOrDefault(event,Set.of()).contains(island.id))throw new IllegalStateException("Fence index lost island "+island.id);
        }
        if(ready.readyCount()!=islands.values().stream().filter(i->i.ready).count())throw new IllegalStateException("Ready queue holds unknown owners");
        for(var entry:fenceOwners.entrySet())for(long id:entry.getValue()){var island=islands.get(id);if(island==null||!island.fences.containsKey(entry.getKey()))throw new IllegalStateException("Fence index holds a stale owner "+id);}
        var stock=new HashMap<Long,Long>();for(var island:islands.values())for(var node:island.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)stock.put(node.id(),island.id);
        if(!stock.equals(stockOwners))throw new IllegalStateException("Stock index diverged from the islands' reservoirs");
    }
}
