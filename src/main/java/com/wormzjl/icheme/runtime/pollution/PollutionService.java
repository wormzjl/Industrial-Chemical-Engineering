package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.ProcessSolveJobs;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.Environment;
import com.wormzjl.icheme.science.pollution.GroundEnvironment;
import com.wormzjl.icheme.science.pollution.GroundField;
import com.wormzjl.icheme.science.pollution.GroundLedger;
import com.wormzjl.icheme.science.pollution.GroundSettings;
import com.wormzjl.icheme.science.pollution.GroundSnapshot;
import com.wormzjl.icheme.science.pollution.MapColumns;
import com.wormzjl.icheme.science.pollution.MapEnvironment;
import com.wormzjl.icheme.science.pollution.PollutionField;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import com.wormzjl.icheme.science.pollution.PollutionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The pollution runtime of one server (batch 2026-11-15-pollution-system, WP3; plan section 5): a {@link PollutionEngine}
 * per dimension, the cells read from the world, the pass every {@link #PASS_TICKS} ticks as a job on the shared pool, and
 * the saved store. Server thread only.
 *
 * <p>Nothing solves on the tick: the tick checks each dimension's deadline, reads a bounded number of cells, submits the
 * step and returns; the drain installs the result. Crash-proof (owner, 2026-11-15): every entry point catches at its
 * edge, logs one line and carries on; after {@link #FAILURE_LIMIT} failed passes in a row (snapshot, job or install)
 * pollution turns itself off for the session ({@code pollution status=DISABLED}): reads answer 0 and releases are
 * dropped. The world never loads or generates a chunk for it ({@code getChunkNow} only).</p>
 */
public final class PollutionService {
    /** Server ticks between two passes of a dimension: 5 s of play, 20 process seconds at the default time factor. */
    public static final int PASS_TICKS = 100;
    /** A cell read is used for this many ticks before it is read again (60 s of play). */
    public static final int CELL_TICKS = 1200;
    /** Fresh cell reads per dimension and pass; the rest use their older read, or count as unloaded for that pass. */
    public static final int CELL_BUDGET = 256;
    /** Failed passes in a row that turn pollution off for the session. */
    public static final int FAILURE_LIMIT = 3;
    private static final int SECTION_BLOCKS = 4096;

    private static final Map<MinecraftServer, PollutionService> SERVERS = new IdentityHashMap<>();

    private record Cached(Environment.Cell cell, int tick) {
    }

    /** A column's last surface read (G2). */
    private record CachedWater(boolean water, int tick) {
    }

    /** One dimension: its engine, its cells, its next deadline. */
    private static final class Dimension {
        final ResourceKey<Level> key;
        final PollutionEngine engine = new PollutionEngine();
        final Map<Long, Cached> cells = new HashMap<>();
        final Map<Long, CachedWater> water = new HashMap<>();
        int next;

        Dimension(ResourceKey<Level> key) {
            this.key = key;
        }
    }

    private final MinecraftServer server;
    private final PollutionSettings settings;
    private final GroundSettings groundSettings = GroundSettings.DEFAULT;
    private final double passSeconds;
    private final PollutionStore store;
    private final Map<ResourceKey<Level>, Dimension> dimensions = new LinkedHashMap<>();
    private final PollutionEffects effects;
    private int nextEffects;
    /** The ignition pass and the blast queue (WP6a), with the config captured on server start; their own guard and off switch. */
    private final Blasts blasts = new Blasts(IChemE.pollutionExplosions(), IChemE.pollutionBlockDamage(), IChemE.pollutionExplosionPowerCap());
    private final Ignition ignition = new Ignition(blasts);
    private int nextIgnition;
    private boolean disabled;
    private boolean stopping;

    private PollutionService(MinecraftServer server, PollutionSettings settings, double passSeconds, PollutionStore store,
            PollutionEffects effects) {
        this.server = server;
        this.settings = settings;
        this.passSeconds = passSeconds;
        this.store = store;
        this.effects = effects;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Lifecycle.

    /** Opens the store and restores every dimension it holds; never throws. */
    public static void start(MinecraftServer server) {
        try {
            if (SERVERS.containsKey(server)) {
                IChemE.LOGGER.warn("pollution status=START_SKIPPED detail=already started");
                return;
            }
            var settings = IChemE.pollutionSettings();
            double seconds;
            try {
                seconds = IChemE.fluidOptions().time().seconds(PASS_TICKS);
            } catch (RuntimeException unread) {
                seconds = SimulationTime.of(SimulationTime.DEFAULT_FACTOR).seconds(PASS_TICKS);
            }
            var store = PollutionStore.open(server);
            var service = new PollutionService(server, settings, seconds, store,
                    new PollutionEffects(IChemE.pollutionEffects(), IChemE.pollutionParticles()));
            SERVERS.put(server, service);
            if (store == null) {
                service.disable("the damaged store could not be set aside; its file is left as it is");
                return;
            }
            // First: a dimension made later, or a start cut short below, still saves what it holds.
            store.live(service::saved);
            for (var entry : store.carried().entrySet()) {
                var location = ResourceLocation.tryParse(entry.getKey());
                if (location == null) {
                    continue;
                }
                var key = ResourceKey.create(Registries.DIMENSION, location);
                // A dimension that is no level stays in the store as it was (carried through every save).
                if (server.getLevel(key) != null) {
                    service.dimension(key);
                }
            }
            if (!IChemE.pollutionEnabled()) {
                service.disabled = true;
                IChemE.LOGGER.info("pollution status=OFF detail=config pollution.enabled is false");
            } else if (IChemE.calculationLoggingEnabled()) {
                IChemE.LOGGER.info("pollution status=STARTED dimensions={} pass_seconds={}", service.dimensions.size(), seconds);
            }
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=START_FAILED detail={}", String.valueOf(failure), failure);
        }
    }

    /**
     * The pass deadline of each dimension, and the handheld meters' (WP8) and the map answers' (WP9), which also run when
     * pollution is off; never throws.
     */
    public static void tick(MinecraftServer server) {
        PollutionService service = null;
        try {
            service = SERVERS.get(server);
            if (service != null) {
                service.tick();
            }
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=TICK_FAILED detail={}", String.valueOf(failure));
        }
        PollutionMeters.tick(server, service);
        // The map overlay's answers to the players whose map is open (WP9): one map check with no subscriber; never throws.
        PollutionMaps.tick(server, service);
        // The sounds of the pipes blasts destroyed this tick (WP6a): one per cube; never throws.
        BurstPipes.flush(server);
        // The constructed equipment this tick's blasts breached (WP6b): one queued edit per vessel; never throws.
        Breaches.flush(server);
    }

    /** No pass starts after this; a pass the stopping pool abandons gives its releases back without counting. Never throws. */
    public static void stop(MinecraftServer server) {
        try {
            var service = SERVERS.get(server);
            if (service != null) {
                service.stopping = true;
            }
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=STOP_FAILED detail={}", String.valueOf(failure));
        }
    }

    /** Forgets a stopped server. Never throws. */
    public static void forget(MinecraftServer server) {
        try {
            SERVERS.remove(server);
            PollutionMeters.forget(server);
            PollutionMaps.forget(server);
            BurstPipes.forget(server);
            Breaches.forget(server);
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=FORGET_FAILED detail={}", String.valueOf(failure));
        }
    }

    public static Optional<PollutionService> find(MinecraftServer server) {
        return Optional.ofNullable(SERVERS.get(server));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The API for the hooks, the effects and the command. Each answers 0, nothing or EMPTY when disabled, and never throws.

    /** Adds {@code amount} air-mole equivalents to the cube's channel at the next pass. */
    public void release(ResourceKey<Level> dimension, long cube, Channel channel, double amount) {
        try {
            if (disabled || dimension == null || channel == null) {
                return;
            }
            // A release outside the world's height is dropped: it left the world (owner, 2026-11-15).
            if (!inside(dimension, cube)) {
                return;
            }
            dimension(dimension).engine.release(cube, channel, amount);
            if (store != null) {
                store.setDirty();
            }
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=RELEASE_FAILED dimension={} detail={}", dimension, String.valueOf(failure));
        }
    }

    /**
     * Puts what the spill law left at {@code position} on the ground of its chunk column (batch
     * 2026-11-18-ground-pollution): four channel amounts, which the ground adds into one, and the compounds ({@link PollutionEngine#deposit}). The air
     * ledger books the amounts {@code RELEASED} and {@code GROUND} at once; the next pass lands the deposit. A position
     * outside the world's height is dropped, as a release is.
     */
    public void deposit(ResourceKey<Level> dimension, BlockPos position, double[] amounts, List<GroundField.Compound> compounds) {
        try {
            if (disabled || dimension == null || position == null || amounts == null || compounds == null) {
                return;
            }
            if (!inside(dimension, cube(position))) {
                return;
            }
            dimension(dimension).engine.deposit(MapColumns.column(position.getX() >> 4, position.getZ() >> 4), amounts, compounds);
            if (store != null) {
                store.setDirty();
            }
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=DEPOSIT_FAILED dimension={} detail={}", dimension, String.valueOf(failure));
        }
    }

    /** {@link #release(ResourceKey, long, Channel, double)} at the cube that holds {@code position}. */
    public void release(ResourceKey<Level> dimension, BlockPos position, Channel channel, double amount) {
        if (position == null) {
            return;
        }
        release(dimension, cube(position), channel, amount);
    }

    /** The cube that holds {@code position} (its chunk section). */
    public static long cube(BlockPos position) {
        return CubeKey.of(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4);
    }

    /** The installed index of the cube's channel, with the cube's last read air share (1 when unknown). */
    public double index(ResourceKey<Level> dimension, long cube, Channel channel) {
        try {
            if (disabled || channel == null) {
                return 0;
            }
            var d = dimensions.get(dimension);
            if (d == null) {
                return 0;
            }
            return d.engine.field().index(cube, channel, airShare(d, cube), settings);
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=READ_FAILED dimension={} detail={}", dimension, String.valueOf(failure));
            return 0;
        }
    }

    public double index(ResourceKey<Level> dimension, BlockPos position, Channel channel) {
        return position == null ? 0 : index(dimension, cube(position), channel);
    }

    /** The installed field of a dimension (before a pass in flight); EMPTY when disabled or unknown. */
    public PollutionField field(ResourceKey<Level> dimension) {
        var d = disabled ? null : dimensions.get(dimension);
        return d == null ? PollutionField.EMPTY : d.engine.field();
    }

    /** The installed ground of a dimension; EMPTY when it has none. */
    public GroundField ground(ResourceKey<Level> dimension) {
        var d = dimensions.get(dimension);
        return d == null ? GroundField.EMPTY : d.engine.ground();
    }

    public GroundLedger groundLedger(ResourceKey<Level> dimension) {
        var d = dimensions.get(dimension);
        return d == null ? GroundLedger.EMPTY : d.engine.groundLedger();
    }

    /** The ground's published bytes of a dimension (G2): what the map, the meter and the world effects read. EMPTY when disabled. */
    public GroundSnapshot groundSnapshot(ResourceKey<Level> dimension) {
        if (disabled) {
            return GroundSnapshot.EMPTY;
        }
        var d = dimensions.get(dimension);
        return d == null ? GroundSnapshot.EMPTY : d.engine.groundSnapshot();
    }

    public GroundSettings groundSettings() {
        return groundSettings;
    }

    public PollutionLedger ledger(ResourceKey<Level> dimension) {
        var d = disabled ? null : dimensions.get(dimension);
        return d == null ? PollutionLedger.EMPTY : d.engine.ledger();
    }

    /** The cube's last read cell, or {@link Environment.Cell#UNLOADED} when none is kept. */
    public Environment.Cell cell(ResourceKey<Level> dimension, long cube) {
        var d = dimensions.get(dimension);
        var cached = d == null ? null : d.cells.get(cube);
        return cached == null ? Environment.Cell.UNLOADED : cached.cell();
    }

    public boolean enabled() {
        return !disabled;
    }

    public PollutionSettings settings() {
        return settings;
    }

    /** Empties a dimension's field and pending releases; the ledger books what was stored as dissipated. */
    public void clear(ResourceKey<Level> dimension) {
        try {
            var d = dimensions.get(dimension);
            if (d == null) {
                return;
            }
            d.engine.clear();
            if (store != null) {
                store.setDirty();
            }
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=CLEAR_FAILED dimension={} detail={}", dimension, String.valueOf(failure));
        }
    }

    /** Releases the amount that raises the cube's channel by {@code index} at its last read air share (1 when unknown). */
    public void add(ResourceKey<Level> dimension, long cube, Channel channel, double index) {
        try {
            if (disabled || !(index >= 0) || !Double.isFinite(index)) {
                return;
            }
            var d = dimensions.get(dimension);
            double air = d == null ? 1.0 : airShare(d, cube);
            release(dimension, cube, channel, index * Math.max(air, settings.closedAirShare()) * PollutionField.CUBE_AIR_MOLES);
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=ADD_FAILED dimension={} detail={}", dimension, String.valueOf(failure));
        }
    }

    /**
     * A blast (WP6a): burns up to {@code amount} combustible at the cube in the installed field, {@code sootShare} of it to
     * particulate ({@link PollutionEngine#burn}; a pass in flight keeps it). Returns what burnt; 0 when disabled or unknown.
     */
    public double burn(ResourceKey<Level> dimension, long cube, double amount, double sootShare) {
        try {
            var d = disabled ? null : dimensions.get(dimension);
            if (d == null) {
                return 0;
            }
            double burnt = d.engine.burn(cube, amount, sootShare);
            if (burnt > 0 && store != null) {
                store.setDirty();
            }
            return burnt;
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=BURN_FAILED dimension={} detail={}", dimension, String.valueOf(failure));
            return 0;
        }
    }

    /** The ignition pass, for the explosion hook ({@link Ignition#detonated}). */
    Ignition ignition() {
        return ignition;
    }

    /** Pending releases of a dimension, for the command. */
    int pending(ResourceKey<Level> dimension) {
        var d = dimensions.get(dimension);
        return d == null ? 0 : d.engine.pending().size();
    }

    private static double airShare(Dimension d, long cube) {
        var cached = d.cells.get(cube);
        return cached == null ? 1.0 : cached.cell().airShare();
    }

    /** The dimension's runtime, made on first use from what the store carried for it, if anything. */
    private Dimension dimension(ResourceKey<Level> key) {
        var d = dimensions.get(key);
        if (d != null) {
            return d;
        }
        d = new Dimension(key);
        var saved = store == null ? null : store.carried().get(key.location().toString());
        if (saved != null) {
            // Capped at the world's height (owner, 2026-11-15): a saved cube outside it is dropped (booked dissipated),
            // and so is a pending release there, so a world whose height changed, or a damaged key, leaves nothing behind.
            var field = saved.field();
            var ledger = saved.ledger();
            var pending = saved.pending();
            ServerLevel level = server.getLevel(key);
            if (level != null) {
                int minimumY = level.getMinSection();
                int maximumY = level.getMaxSection() - 1;
                var capped = PollutionField.capped(field, ledger, minimumY, maximumY);
                var kept = new java.util.ArrayList<com.wormzjl.icheme.science.pollution.Release>(pending.size());
                for (var release : pending) {
                    if (CubeKey.inside(release.key(), minimumY, maximumY)) {
                        kept.add(release);
                    }
                }
                int dropped = field.size() - capped.field().size() + pending.size() - kept.size();
                if (dropped > 0) {
                    IChemE.LOGGER.warn("pollution_store status=CAPPED dimension={} dropped={} detail=cubes outside the world's height", key.location(), dropped);
                    store.setDirty();
                }
                field = capped.field();
                ledger = capped.ledger();
                pending = kept;
            }
            d.engine.restore(field, ledger, pending, saved.ground(), saved.groundLedger(), saved.groundPending());
        }
        dimensions.put(key, d);
        return d;
    }

    /** Whether {@code cube} lies in the dimension's height; true when the dimension is no level. */
    private boolean inside(ResourceKey<Level> dimension, long cube) {
        ServerLevel level = server.getLevel(dimension);
        return level == null || CubeKey.inside(cube, level.getMinSection(), level.getMaxSection() - 1);
    }

    /** Every dimension's facts as a save writes them, by id. */
    private Map<String, PollutionStore.Saved> saved() {
        var out = new TreeMap<String, PollutionStore.Saved>();
        for (var d : dimensions.values()) {
            out.put(d.key.location().toString(), new PollutionStore.Saved(d.engine.field(), d.engine.ledger(), d.engine.pendingForSave(),
                    d.engine.ground(), d.engine.groundLedger(), d.engine.pendingDepositsForSave()));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The pass.

    private void tick() {
        if (disabled || stopping) {
            return;
        }
        int now = server.getTickCount();
        // The world pass (WP5): its own guard and its own off switch; a failure of it never stops the field.
        if (now - nextEffects >= 0) {
            nextEffects = now + PollutionEffects.EFFECT_TICKS;
            try {
                effects.run(server, this);
            } catch (RuntimeException failure) {
                IChemE.LOGGER.error("pollution status=EFFECTS_FAILED detail={}", String.valueOf(failure));
            }
        }
        // Ignition and blasts (WP6a): their own guard and off switch (EXPLOSIONS_OFF); the blast queue every tick (an empty
        // queue is a map check), the ignition pass every IGNITION_TICKS.
        try {
            if (now - nextIgnition >= 0) {
                nextIgnition = now + Ignition.IGNITION_TICKS;
                ignition.run(server, this, now);
            }
            blasts.serve(server, this, now);
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=BLASTS_FAILED detail={}", String.valueOf(failure));
        }
        for (var d : dimensions.values()) {
            if (disabled) {
                return;
            }
            if (now - d.next < 0) {
                continue;
            }
            try {
                pass(d, now);
            } catch (RuntimeException failure) {
                d.next = now + PASS_TICKS;
                d.engine.failed("pass: " + failure);
                failed(d, "pass", d.engine.lastFailure());
            }
        }
    }

    private void pass(Dimension d, int now) {
        var engine = d.engine;
        if (engine.inFlight()) {
            d.next = now + 1;
            return;
        }
        d.next = now + PASS_TICKS;
        if (engine.idle()) {
            d.cells.clear();
            return;
        }
        ServerLevel level = server.getLevel(d.key);
        if (level == null) {
            return;
        }
        Environment environment;
        GroundEnvironment groundEnvironment;
        try {
            environment = environment(level, d, now);
            groundEnvironment = groundEnvironment(level, d, now);
        } catch (RuntimeException failure) {
            engine.failed("snapshot: " + failure);
            failed(d, "snapshot", engine.lastFailure());
            return;
        }
        var pass = engine.begin(environment, groundEnvironment, passSeconds, settings, groundSettings);
        if (pass.isEmpty()) {
            return;
        }
        var key = d.key;
        ProcessSolveServices.AdmissionResult admission;
        try {
            var request = new ProcessSolveServices.PollutionRequest(ProcessSolveServices.nextRequestId(),
                    new ProcessSolveServices.PollutionTarget(key), 0, result -> completed(key, result));
            admission = ProcessSolveServices.submitPollution(server, request, new ProcessSolveJobs.PollutionCommand(pass.get()));
        } catch (RuntimeException failure) {
            engine.failed("submit: " + failure);
            failed(d, "submit", engine.lastFailure());
            return;
        }
        if (!admission.accepted()) {
            engine.refused();
            var refusal = admission.admission();
            // The pool at capacity: ask again at the next tick. Not started or stopping: at the next deadline.
            if (refusal == ProcessSolveServices.Admission.QUEUE_FULL || refusal == ProcessSolveServices.Admission.OWNER_BUSY) {
                d.next = now + 1;
            }
        }
    }

    /** The drain's delivery of a pass; on the server thread, never throws. */
    private void completed(ResourceKey<Level> key, ProcessSolveJobs.PollutionSolveResult result) {
        var d = dimensions.get(key);
        if (d == null) {
            return;
        }
        try {
            if (result.result().isPresent()) {
                d.engine.complete(result.result().get());
                if (IChemE.calculationLoggingEnabled()) {
                    IChemE.LOGGER.debug("pollution status=PASS dimension={} cubes={}", key.location(), d.engine.field().size());
                }
            } else if (stopping) {
                d.engine.refused();
            } else {
                d.engine.failed("job: " + result.detail());
                failed(d, "job", d.engine.lastFailure());
            }
        } catch (RuntimeException failure) {
            try {
                d.engine.failed("install: " + failure);
                failed(d, "install", d.engine.lastFailure());
            } catch (RuntimeException ignored) {
                IChemE.LOGGER.error("pollution status=INSTALL_FAILED dimension={} detail={}", key.location(), String.valueOf(failure));
            }
        } finally {
            if (store != null) {
                store.setDirty();
            }
        }
    }

    /** One failed pass: one log line, and the disable at {@link #FAILURE_LIMIT} in a row. */
    private void failed(Dimension d, String stage, String detail) {
        IChemE.LOGGER.warn("pollution status=PASS_FAILED dimension={} stage={} failures={} detail={}", d.key.location(), stage, d.engine.failures(), detail);
        if (d.engine.failures() >= FAILURE_LIMIT) {
            disable(FAILURE_LIMIT + " failed passes in a row in " + d.key.location() + ", the last: " + detail);
        }
    }

    private void disable(String detail) {
        if (disabled) {
            return;
        }
        disabled = true;
        IChemE.LOGGER.error("pollution status=DISABLED detail={}", detail);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The world snapshot, on the server thread.

    /**
     * The cells of every cube the next pass reads: the cubes that hold or wait for something first, then their
     * neighbours. A cube of a chunk that is not loaded is unloaded, every pass; a cell older than {@link #CELL_TICKS} is
     * read again, at most {@link #CELL_BUDGET} per pass; a cube over the budget uses its older read, or is unloaded for
     * this pass.
     */
    private Environment environment(ServerLevel level, Dimension d, int now) {
        var core = d.engine.core();
        var wanted = d.engine.wanted();
        int minimumY = level.getMinSection();
        int maximumY = level.getMaxSection() - 1;
        var chunks = new HashMap<Long, LevelChunk>();
        var cells = new HashMap<Long, Environment.Cell>();
        int[] budget = {CELL_BUDGET};
        for (long cube : core) {
            cells.put(cube, cell(level, d, cube, now, chunks, budget, minimumY, maximumY));
        }
        for (long cube : wanted) {
            if (!cells.containsKey(cube)) {
                cells.put(cube, cell(level, d, cube, now, chunks, budget, minimumY, maximumY));
            }
        }
        // Bounded memory: forget the cells of cubes no longer wanted.
        for (Iterator<Long> it = d.cells.keySet().iterator(); it.hasNext(); ) {
            if (!wanted.contains(it.next())) {
                it.remove();
            }
        }
        return new MapEnvironment(cells, Environment.Cell.UNLOADED, level.isRaining(), minimumY, maximumY);
    }

    /**
     * The ground's view of the world for the next pass (G2): which of the wanted columns are loaded now, and which have a
     * surface that is mostly water (at least 9 of 16 sampled surface blocks hold water), read at most {@link #CELL_BUDGET}
     * columns per pass and kept {@link #CELL_TICKS}; a column over the budget uses its older read, or counts as land.
     */
    private GroundEnvironment groundEnvironment(ServerLevel level, Dimension d, int now) {
        var wanted = d.engine.groundWanted();
        var loaded = new java.util.HashSet<Long>();
        var water = new java.util.HashSet<Long>();
        int budget = CELL_BUDGET;
        for (long column : wanted) {
            var chunk = loaded(level, MapColumns.x(column), MapColumns.z(column));
            if (chunk == null) {
                continue;
            }
            loaded.add(column);
            var cached = d.water.get(column);
            boolean wet;
            if (cached != null && now - cached.tick() < CELL_TICKS) {
                wet = cached.water();
            } else if (budget > 0) {
                budget--;
                wet = surfaceWater(chunk);
                d.water.put(column, new CachedWater(wet, now));
            } else {
                wet = cached != null && cached.water();
            }
            if (wet) {
                water.add(column);
            }
        }
        d.water.keySet().retainAll(wanted);
        return GroundEnvironment.of(loaded, water, level.isRaining());
    }

    /** Whether at least 9 of 16 surface blocks (a 4 × 4 grid) of the chunk hold water; any failure answers land. */
    static boolean surfaceWater(LevelChunk chunk) {
        try {
            int wet = 0;
            var position = new BlockPos.MutableBlockPos();
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    int x = 2 + 4 * i, z = 2 + 4 * j;
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    position.set(baseX + x, top, baseZ + z);
                    if (chunk.getFluidState(position).is(net.minecraft.tags.FluidTags.WATER)) {
                        wet++;
                    }
                }
            }
            return wet >= 9;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static Environment.Cell cell(ServerLevel level, Dimension d, long cube, int now, Map<Long, LevelChunk> chunks,
            int[] budget, int minimumY, int maximumY) {
        int y = CubeKey.y(cube);
        if (y < minimumY || y > maximumY) {
            return Environment.Cell.UNLOADED;
        }
        int x = CubeKey.x(cube);
        int z = CubeKey.z(cube);
        long column = (long) x << 32 | (z & 0xFFFFFFFFL);
        LevelChunk chunk;
        if (chunks.containsKey(column)) {
            chunk = chunks.get(column);
        } else {
            chunk = loaded(level, x, z);
            chunks.put(column, chunk);
        }
        if (chunk == null) {
            return Environment.Cell.UNLOADED;
        }
        var cached = d.cells.get(cube);
        if (cached != null && now - cached.tick() < CELL_TICKS) {
            return cached.cell();
        }
        if (budget[0] <= 0) {
            return cached != null ? cached.cell() : Environment.Cell.UNLOADED;
        }
        budget[0]--;
        var read = read(chunk, y);
        if (read.loaded()) {
            d.cells.put(cube, new Cached(read, now));
        }
        return read;
    }

    /** The chunk if it is loaded now; never loads or generates one. */
    private static LevelChunk loaded(ServerLevel level, int x, int z) {
        try {
            return level.getChunkSource().getChunkNow(x, z);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * A cube's cell from its section: the share of its blocks that hold air (air, or no collision shape and no fluid),
     * counted per palette entry; open when its top block is at or above the column's surface. Any failure (damaged chunk
     * data) answers unloaded.
     */
    static Environment.Cell read(LevelChunk chunk, int sectionY) {
        try {
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= chunk.getSectionsCount()) {
                return Environment.Cell.UNLOADED;
            }
            LevelChunkSection section = chunk.getSection(index);
            int air;
            if (section.hasOnlyAir()) {
                air = SECTION_BLOCKS;
            } else {
                int[] count = {0};
                section.getStates().count((state, n) -> {
                    if (passes(state)) {
                        count[0] += n;
                    }
                });
                air = Math.max(0, Math.min(SECTION_BLOCKS, count[0]));
            }
            boolean open = (sectionY << 4) + 15 >= chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
            return new Environment.Cell(air / (double) SECTION_BLOCKS, open, true);
        } catch (RuntimeException failure) {
            return Environment.Cell.UNLOADED;
        }
    }

    /** Air for the field: air, or a block with no collision shape and no fluid (grass, torches, open doors). */
    private static boolean passes(BlockState state) {
        try {
            return state.isAir() || state.getFluidState().isEmpty()
                    && state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
