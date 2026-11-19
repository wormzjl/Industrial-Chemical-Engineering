package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.ExplosionLaw;
import com.wormzjl.icheme.science.pollution.PollutionField;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongToDoubleFunction;

/**
 * The blasts of flammable cubes (batch 2026-11-15-pollution-system, WP6a; plan section 7): a cube that ignites at a block
 * burns its combustible ({@link ExplosionLaw#burntIndex}, 2 % of it to soot) and explodes there with the power of its heat
 * ({@link #power}); each of its six face neighbours that is flammable ignites {@link #CHAIN_TICKS} ticks later at its centre
 * (a flame speed near 100 m/s over 16 m). Server thread only; reads the installed field and the cached cells, and never loads
 * a chunk ({@code getChunkNow} only).
 *
 * <p>The pending blasts are state, not saved: a queue per dimension, a cube at most once in it, at most
 * {@link #BLASTS_PER_TICK} served per dimension and tick, at most {@link #QUEUE_CAP} waiting. Crash-proof (owner,
 * 2026-11-15): a failed serve or ignition pass is caught and logged; after {@link #FAILURE_LIMIT} failures in a row the
 * explosions turn off for the session ({@code pollution status=EXPLOSIONS_OFF}), the field and the effects run on.</p>
 */
public final class Blasts {
    /** Ticks between a blast and its neighbours' ignition: 16 m at a flame speed near 100 m/s is about 0.15 s. */
    public static final int CHAIN_TICKS = 3;
    /** Blasts served per dimension and tick; the rest wait. */
    public static final int BLASTS_PER_TICK = 4;
    /** The most blasts waiting per dimension; a newer one beyond it is dropped. */
    public static final int QUEUE_CAP = 4096;
    /** The share of the burnt combustible that becomes soot (particulate). */
    public static final double SOOT_SHARE = 0.02;
    /** A pocket whose blast would be weaker burns without one (too small a pocket: a room of about 8 m³). */
    public static final double MINIMUM_POWER = 0.5;
    /** Failed serves or ignition passes in a row that turn the explosions off for the session. */
    public static final int FAILURE_LIMIT = 3;
    private static final int[] DX = {-1, 1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, -1, 1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, -1, 1};

    /** A test switch (GameTests share one world): blasts break no block and light no fire. */
    private static volatile boolean keepBlocks;

    /** For tests only: while {@code keep}, every blast uses {@code ExplosionInteraction.NONE} and lights no fire. */
    public static void keepBlocks(boolean keep) {
        keepBlocks = keep;
    }

    /** One waiting blast: its cube, the block it starts at, its due tick and the order it was queued in. */
    record Blast(long cube, int x, int y, int z, int due, long sequence) {
    }

    /**
     * The waiting blasts of one dimension (pure): served by due tick, then in the order queued; a cube at most once while it
     * waits; at most {@link #QUEUE_CAP}. Tick comparisons are by difference, as the service's deadlines are.
     */
    static final class Queue {
        private final PriorityQueue<Blast> waiting = new PriorityQueue<>((Blast a, Blast b) -> {
            int byDue = Integer.compare(a.due() - b.due(), 0);
            return byDue != 0 ? byDue : Long.compare(a.sequence(), b.sequence());
        });
        private final Set<Long> cubes = new HashSet<>();
        private long sequence;
        private long dropped;

        /** Queues a blast of {@code cube} at {@code (x, y, z)}, due at {@code due}; false when the cube waits already or the queue is full. */
        boolean offer(long cube, int x, int y, int z, int due) {
            if (cubes.contains(cube)) {
                return false;
            }
            if (waiting.size() >= QUEUE_CAP) {
                dropped++;
                return false;
            }
            cubes.add(cube);
            waiting.add(new Blast(cube, x, y, z, due, sequence++));
            return true;
        }

        /** Takes at most {@code most} blasts due by {@code now}, in order; their cubes may be queued again. */
        List<Blast> take(int now, int most) {
            var out = new ArrayList<Blast>(Math.min(most, waiting.size()));
            while (out.size() < most && !waiting.isEmpty() && now - waiting.peek().due() >= 0) {
                var blast = waiting.poll();
                cubes.remove(blast.cube());
                out.add(blast);
            }
            return out;
        }

        boolean waits(long cube) {
            return cubes.contains(cube);
        }

        int size() {
            return waiting.size();
        }

        long dropped() {
            return dropped;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The arithmetic (pure).

    /** The Minecraft power of a cube of combustible index {@code index} and air share {@code airShare} that burns, capped at {@code cap}. */
    public static double power(double index, double airShare, double cap) {
        return ExplosionLaw.power(ExplosionLaw.tntKilograms(ExplosionLaw.energy(index, airShare)), cap);
    }

    /** What a cube burns, air-mole equivalents: {@code burntIndex(index) * max(airShare, closedAirShare) * CUBE_AIR_MOLES}. */
    public static double burntAmount(double index, double airShare, double closedAirShare) {
        return ExplosionLaw.burntIndex(index) * Math.max(airShare, closedAirShare) * PollutionField.CUBE_AIR_MOLES;
    }

    /** The face neighbours of {@code cube} that ignite after it (index at least 1), in face order -x, +x, -y, +y, -z, +z. */
    static List<Long> chain(long cube, LongToDoubleFunction index) {
        var out = new ArrayList<Long>(6);
        for (int d = 0; d < 6; d++) {
            long neighbour = CubeKey.offset(cube, DX[d], DY[d], DZ[d]);
            if (Ignition.ignites(index.applyAsDouble(neighbour))) {
                out.add(neighbour);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The runtime.

    private final boolean enabled;
    private final boolean blockDamage;
    private final double cap;
    private final Map<ResourceKey<Level>, Queue> queues = new HashMap<>();
    private int failures;
    private boolean off;
    private boolean droppedLogged;

    Blasts(boolean enabled, boolean blockDamage, double cap) {
        this.enabled = enabled;
        this.blockDamage = blockDamage;
        this.cap = Double.isFinite(cap) && cap >= 1 ? cap : 8.0;
    }

    /** Whether no cube ignites: the config, or the session's switch-off after failures. */
    boolean off() {
        return off || !enabled;
    }

    /** Whether {@code cube} waits for its blast. */
    boolean waits(ResourceKey<Level> dimension, long cube) {
        var q = queues.get(dimension);
        return q != null && q.waits(cube);
    }

    /** Queues the ignition of {@code cube} at {@code at}, due at tick {@code due}; false when off, waiting already or the queue is full. */
    boolean ignite(ResourceKey<Level> dimension, long cube, BlockPos at, int due) {
        if (off() || dimension == null || at == null) {
            return false;
        }
        var q = queues.computeIfAbsent(dimension, k -> new Queue());
        boolean queued = q.offer(cube, at.getX(), at.getY(), at.getZ(), due);
        if (!queued && q.size() >= QUEUE_CAP && !droppedLogged) {
            droppedLogged = true;
            IChemE.LOGGER.warn("pollution status=BLASTS_DROPPED dimension={} detail=the blast queue holds {}; newer blasts are dropped", dimension.location(), QUEUE_CAP);
        }
        return queued;
    }

    /** Serves the blasts due by {@code now}, at most {@link #BLASTS_PER_TICK} per dimension; an empty queue costs a map check. Never throws. */
    void serve(MinecraftServer server, PollutionService service, int now) {
        if (queues.isEmpty() || off()) {
            return;
        }
        try {
            for (var key : List.copyOf(queues.keySet())) {
                var q = queues.get(key);
                ServerLevel level = server.getLevel(key);
                if (level == null) {
                    queues.remove(key);
                    continue;
                }
                for (var blast : q.take(now, BLASTS_PER_TICK)) {
                    detonate(level, service, blast, now);
                }
                if (q.size() == 0) {
                    queues.remove(key);
                }
            }
            failures = 0;
        } catch (RuntimeException failure) {
            failed("serve", failure);
        }
    }

    /** One failure of a serve or an ignition pass: one log line, and the switch-off at {@link #FAILURE_LIMIT} in a row. */
    void failed(String stage, RuntimeException failure) {
        failures++;
        IChemE.LOGGER.warn("pollution status=BLASTS_FAILED stage={} failures={} detail={}", stage, failures, String.valueOf(failure));
        if (failures >= FAILURE_LIMIT && !off) {
            off = true;
            queues.clear();
            IChemE.LOGGER.error("pollution status=EXPLOSIONS_OFF detail={} failed blast or ignition passes in a row, the last: {}", FAILURE_LIMIT, String.valueOf(failure));
        }
    }

    /** A pass that ran through. */
    void succeeded() {
        failures = 0;
    }

    /** One blast: burn the cube, explode at its block, queue the flammable neighbours. */
    private void detonate(ServerLevel level, PollutionService service, Blast blast, int now) {
        var key = level.dimension();
        long cube = blast.cube();
        double index = service.measuredIndex(key, cube, Channel.COMBUSTIBLE);
        // Burnt by an earlier blast, decayed or diluted since it was queued.
        if (!Ignition.ignites(index)) {
            return;
        }
        double air = service.cell(key, cube).airShare();
        double power = power(index, air, cap);
        // The burn first: the explosion's own Detonate event then finds the cube burnt and ignites nothing again.
        double burnt = service.burn(key, cube, burntAmount(index, air, service.settings().closedAirShare()), SOOT_SHARE);
        if (!(burnt > 0)) {
            return;
        }
        if (power >= MINIMUM_POWER) {
            applyDamage(level, new Vec3(blast.x() + 0.5, blast.y() + 0.5, blast.z() + 0.5), (float) power, blockDamage);
        }
        if (IChemE.calculationLoggingEnabled()) {
            IChemE.LOGGER.info("pollution status=BLAST dimension={} cube={},{},{} index={} air={} power={} burnt={}", key.location(),
                    CubeKey.x(cube), CubeKey.y(cube), CubeKey.z(cube), index, air, power, burnt);
        }
        for (long neighbour : chain(cube, k -> service.measuredIndex(key, k, Channel.COMBUSTIBLE))) {
            ignite(key, neighbour, centre(level, service, neighbour), now + CHAIN_TICKS);
        }
    }

    /** Dispatch a pollution blast with the configured block-damage permission. */
    public static void applyDamage(ServerLevel level, Vec3 centre, float power, boolean blockDamage) {
        boolean keep = keepBlocks;
        boolean damage = blockDamage && !keep;
        if (vanillaAreaLoaded(level, centre, power)) {
            level.explode(null, centre.x, centre.y, centre.z, power, !keep,
                    damage ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE);
        } else {
            PlantExplosion.detonate(level, centre, power, damage);
        }
    }

    /** Vanilla's blast and visibility rays use ordinary chunk lookups, so check their footprint before calling them. */
    private static boolean vanillaAreaLoaded(ServerLevel level, Vec3 centre, float power) {
        double radius = 2.0 * power;
        var bounds = new AABB(centre, centre).inflate(radius + 2);
        if (!areaLoaded(level, bounds)) return false;
        // Visibility is sampled across the target's whole bounding box, including unusually large entities.
        for (var entity : level.getEntities((Entity) null, bounds)) {
            if (entity.distanceToSqr(centre) <= radius * radius && !areaLoaded(level, entity.getBoundingBox().inflate(1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean areaLoaded(ServerLevel level, AABB bounds) {
        int x0 = Mth.floor(bounds.minX) >> 4, x1 = Mth.floor(bounds.maxX) >> 4;
        int z0 = Mth.floor(bounds.minZ) >> 4, z1 = Mth.floor(bounds.maxZ) >> 4;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    /**
     * Where a neighbour's flame starts: the centre block of its cube, or the nearest air block above it in the cube when the
     * centre is solid; the centre when none is. Unloaded chunks use the same search result from the terrain snapshot.
     */
    private static BlockPos centre(ServerLevel level, PollutionService service, long cube) {
        int x = CubeKey.x(cube) * 16 + 8;
        int y = CubeKey.y(cube) * 16 + 8;
        int z = CubeKey.z(cube) * 16 + 8;
        var centre = new BlockPos(x, y, z);
        try {
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                return centre.offset(0, service.blastCentreY(level.dimension(), cube) - 8, 0);
            }
            for (int dy = 0; dy < 8; dy++) {
                var at = centre.above(dy);
                var state = chunk.getBlockState(at);
                if (state.isAir() || state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                    return at;
                }
            }
        } catch (RuntimeException failure) {
            // A damaged chunk reads as no air: the centre.
        }
        return centre;
    }
}
