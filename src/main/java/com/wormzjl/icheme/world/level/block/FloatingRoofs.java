package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.runtime.fluid.TankDisplay;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.math.DeterministicMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * The floating roofs of a level's loaded floating-roof tanks as the world collides with them (owner request 2026-10-21:
 * "let the player on the lid actually move with a rising lid"; option A of the batch's review). An open tank's interior
 * cells ({@link EquipmentInteriorBlock#OPEN}) collide up to the roof's walking surface (its deck, its pontoon ring's top
 * beyond the deck's radius) and are empty above it; the surface stands where the tank's display puts it at the game tick
 * ({@link TankDisplay.Glide}), the same on the server and on the client, which receives the server's glide whole.
 * Vanilla lifts nothing that stands on a rising surface, so while a roof rises the server lifts the entities on it
 * ({@link #carry(Level)}, players excepted) and each client its own player ({@link #carry(Player)}). The rolling ladder
 * from the gauger's platform down to the deck is climbable ({@link #ladder}).
 *
 * <p>One index per level, filled by the tanks' node cells as they load and emptied as they unload; looked up by chunk
 * section, so a collision query costs a map lookup and a box test. Thread-safe: the server and the client read their own
 * level's. Before a tank's first display the roof stands at the level its node cell saved ({@link
 * EquipmentShellBlockEntity}), so a player on a high roof does not fall when the world loads.</p>
 */
public final class FloatingRoofs {
    /** How far under the surface an entity's feet may be for a rising roof to lift it, m: far more than a roof rises in a tick. */
    static final double LIFT = .6;
    /** How near the rolling ladder's line an entity's middle must be to climb it, m. */
    static final double LADDER_REACH = .8;
    /** How far past its head, up its line, the rolling ladder still holds a climber, m; how far over its feet a body's foot point stands. */
    static final double HEAD_REACH = .5, FEET = .1;
    /**
     * Climbing the rolling ladder ({@link #climb}): the most a tick rises (a running climb) and falls (a vanilla ladder's
     * slowed fall), m per tick, and the steepest rise per metre in it follows (a ladder near upright is climbed as one).
     */
    static final double MAX_CLIMB = .3, DESCENT = .15, MAX_SLOPE = 4;
    /**
     * Half a collision voxel, m: the platform's deck collides in 1/8-block voxels solid at their centres, so its edge stands
     * up to this far proud of where it is drawn.
     */
    static final double PROUD = .5 / ShapeRasterizer.SOLID_RESOLUTION;
    /** The surface's height steps, per metre: the collision shapes of a cell are cached by step. */
    static final int STEPS = 64;
    private static final VoxelShape[] SLABS = new VoxelShape[STEPS + 1];
    static {
        SLABS[0] = Shapes.empty();
        for (int i = 1; i < STEPS; i++) SLABS[i] = Shapes.box(0, 0, 0, 1, (double) i / STEPS, 1);
        SLABS[STEPS] = Shapes.block();
    }
    private static final Map<Level, FloatingRoofs> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());
    /** The roof's shapes in shell cells by voxel columns ({@link #rim}) and their joins with the cells' own ({@link #join}), each cleared at this many. */
    private static final int RIM_CACHE = 4096;
    private static final Map<Long, VoxelShape[]> RIMS = new ConcurrentHashMap<>();
    private static final Map<Join, VoxelShape> JOINS = new ConcurrentHashMap<>();

    /** One loaded tank: its node cell, box, roof and the glide its display gives. */
    static final class Tank {
        final BlockPos node, origin;
        final int sizeX, sizeY, sizeZ;
        final ShapeRasterizer.Roof roof;
        /** The roof's axis in world coordinates. */
        final double axisX, axisZ;
        /** The level the node cell saved, used until the first display. */
        final DoubleSupplier saved;
        volatile TankDisplay.@Nullable Glide glide;

        Tank(BlockPos node, BlockPos origin, EquipmentShape shape, ShapeRasterizer.Roof roof, DoubleSupplier saved) {
            this.node = node.immutable();
            this.origin = origin.immutable();
            sizeX = shape.sizeX();
            sizeY = shape.sizeY();
            sizeZ = shape.sizeZ();
            this.roof = roof;
            axisX = origin.getX() + roof.x();
            axisZ = origin.getZ() + roof.z();
            this.saved = saved;
        }

        boolean contains(BlockPos cell) {
            int x = cell.getX() - origin.getX(), y = cell.getY() - origin.getY(), z = cell.getZ() - origin.getZ();
            return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
        }

        /** The liquid's level at game time {@code ticks}, m. */
        double level(double ticks) {
            var g = glide;
            return g == null ? saved.getAsDouble() : g.level(ticks);
        }

        /**
         * The 1/8-block voxel columns of the column of cells at {@code x}, {@code z} (world block coordinates) whose middles
         * lie inside the roof's radius, one bit each (x-th column across times 8 plus z-th): all of them for a cell wholly
         * inside it.
         */
        long under(int x, int z) {
            int s = ShapeRasterizer.SOLID_RESOLUTION;
            double r2 = roof.radius() * roof.radius();
            long bits = 0;
            for (int i = 0; i < s; i++)
                for (int k = 0; k < s; k++) {
                    double dx = x + (i + .5) / s - axisX, dz = z + (k + .5) / s - axisZ;
                    if (dx * dx + dz * dz < r2) bits |= 1L << (i * s + k);
                }
            return bits;
        }

        /**
         * The walking surface's world height over the column of cells at {@code x}, {@code z} (world block coordinates), on
         * whole {@link #STEPS}: the deck's where the column lies wholly inside the deck's radius, the pontoon ring's top
         * where any of it reaches beyond.
         */
        double surface(int x, int z, double level) {
            double x0 = x - axisX, x1 = x0 + 1, z0 = z - axisZ, z1 = z0 + 1;
            double far = Math.sqrt(Math.max(x0 * x0, x1 * x1) + Math.max(z0 * z0, z1 * z1));
            return Math.round((origin.getY() + roof.surface(far < roof.inner() ? 0 : roof.inner(), level)) * STEPS) / (double) STEPS;
        }
    }

    private final Map<BlockPos, Tank> byNode = new ConcurrentHashMap<>();
    private final Map<Long, List<Tank>> bySection = new ConcurrentHashMap<>();

    FloatingRoofs() {
    }

    public static FloatingRoofs of(Level level) {
        return LEVELS.computeIfAbsent(level, k -> new FloatingRoofs());
    }

    /** A tank's node cell loaded or took new data: its roof joins the index, keeping the glide it had. Nothing for a shape without a floating roof. */
    public void add(BlockPos node, BlockPos origin, EquipmentShape shape, DoubleSupplier saved) {
        var roof = ShapeRasterizer.roof(shape);
        if (roof == null) return;
        var old = byNode.get(node);
        remove(node);
        var tank = new Tank(node, origin, shape, roof, saved);
        if (old != null) tank.glide = old.glide;
        byNode.put(tank.node, tank);
        for (int sx = origin.getX() >> 4; sx <= (origin.getX() + tank.sizeX - 1) >> 4; sx++)
            for (int sy = origin.getY() >> 4; sy <= (origin.getY() + tank.sizeY - 1) >> 4; sy++)
                for (int sz = origin.getZ() >> 4; sz <= (origin.getZ() + tank.sizeZ - 1) >> 4; sz++)
                    bySection.compute(SectionPos.asLong(sx, sy, sz), (k, list) -> {
                        var out = list == null ? new ArrayList<Tank>() : new ArrayList<>(list);
                        out.add(tank);
                        return List.copyOf(out);
                    });
    }

    /** A tank's node cell unloaded or was removed. */
    public void remove(BlockPos node) {
        var tank = byNode.remove(node);
        if (tank == null) return;
        for (var key : List.copyOf(bySection.keySet()))
            bySection.computeIfPresent(key, (k, list) -> {
                var out = new ArrayList<>(list);
                out.remove(tank);
                return out.isEmpty() ? null : List.copyOf(out);
            });
    }

    /** The tank at {@code node} glides by {@code glide} (null: back to its saved level). */
    public void glide(BlockPos node, TankDisplay.@Nullable Glide glide) {
        var tank = byNode.get(node);
        if (tank != null) tank.glide = glide;
    }

    public TankDisplay.@Nullable Glide glide(BlockPos node) {
        var tank = byNode.get(node);
        return tank == null ? null : tank.glide;
    }

    /** The liquid's level in the tank at {@code node} at game time {@code ticks}, m; 0 for no such tank. */
    public double level(BlockPos node, double ticks) {
        double standing = standing(node, ticks);
        return Double.isNaN(standing) ? 0 : standing;
    }

    /**
     * Where the roof of the tank at {@code node} stands at game time {@code ticks}, m: on its glide, or before its first one
     * at the level its node cell saved; NaN for no such tank.
     */
    public double standing(BlockPos node, double ticks) {
        var tank = byNode.get(node);
        return tank == null ? Double.NaN : tank.level(ticks);
    }

    private @Nullable Tank at(BlockPos cell) {
        var list = bySection.get(SectionPos.asLong(cell.getX() >> 4, cell.getY() >> 4, cell.getZ() >> 4));
        if (list == null) return null;
        for (var tank : list) if (tank.contains(cell)) return tank;
        return null;
    }

    /**
     * The collision shape of the open interior cell {@code cell} at game tick {@code tick}: solid up to the roof's walking
     * surface over the cell and empty above it; null when no loaded tank holds the cell. A cell reaching into the pontoon
     * ring stands at the ring's top.
     */
    @Nullable VoxelShape collision(BlockPos cell, long tick) {
        var tank = at(cell);
        if (tank == null) return null;
        double height = tank.surface(cell.getX(), cell.getZ(), tank.level(tick)) - cell.getY();
        if (height <= 0) return SLABS[0];
        if (height >= 1) return SLABS[STEPS];
        return SLABS[(int) Math.round(height * STEPS)];
    }

    /**
     * The roof's collision in the shell cell {@code cell} at game tick {@code tick}: as an open cell's
     * ({@link #collision}), in those of the cell's 1/8-block columns inside the roof's radius (the cells the shell's wall
     * crosses, and those a part over the roof reaches); null when no loaded tank holds the cell, none of its columns lies
     * inside or the roof stands under the cell.
     */
    @Nullable VoxelShape rim(BlockPos cell, long tick) {
        var tank = at(cell);
        if (tank == null) return null;
        long columns = tank.under(cell.getX(), cell.getZ());
        double height = tank.surface(cell.getX(), cell.getZ(), tank.level(tick)) - cell.getY();
        int step = height >= 1 ? STEPS : (int) Math.round(Math.max(0, height) * STEPS);
        if (columns == 0 || step == 0) return null;
        if (columns == -1L) return SLABS[step];
        if (RIMS.size() >= RIM_CACHE) RIMS.clear();
        var shapes = RIMS.computeIfAbsent(columns, k -> new VoxelShape[STEPS + 1]);
        var shape = shapes[step];
        if (shape == null) shapes[step] = shape = columns(columns, (double) step / STEPS);
        return shape;
    }

    /** The voxel columns {@code columns} ({@link Tank#under}) from the cell's floor up to {@code height} (a fraction of the cell). */
    private static VoxelShape columns(long columns, double height) {
        int s = ShapeRasterizer.SOLID_RESOLUTION;
        VoxelShape out = Shapes.empty();
        for (int i = 0; i < s; i++)
            for (int k = 0; k < s; k++) {
                if ((columns >>> (i * s + k) & 1) == 0) continue;
                int end = k;
                while (end + 1 < s && (columns >>> (i * s + end + 1) & 1) != 0) end++;
                out = Shapes.joinUnoptimized(out, Shapes.box((double) i / s, 0, (double) k / s, (double) (i + 1) / s, height, (double) (end + 1) / s), BooleanOp.OR);
                k = end;
            }
        return out.optimize();
    }

    /** A shell cell's collision ({@code solid}, its parts) and the roof's in it ({@link #rim}), joined once per pair. */
    static VoxelShape join(VoxelShape solid, @Nullable VoxelShape rim) {
        if (rim == null) return solid;
        if (JOINS.size() >= RIM_CACHE) JOINS.clear();
        return JOINS.computeIfAbsent(new Join(solid, rim), j -> Shapes.or(j.solid(), j.rim()));
    }

    /** Two cached shapes, equal by identity. */
    private record Join(VoxelShape solid, VoxelShape rim) {
    }

    /** The level a collision query reads (an entity's collisions read a chunk, not the level); null when none is at hand. */
    static @Nullable Level level(BlockGetter getter, CollisionContext context) {
        if (getter instanceof Level level) return level;
        if (getter instanceof LevelChunk chunk) return chunk.getLevel();
        if (context instanceof EntityCollisionContext entity && entity.getEntity() != null) return entity.getEntity().level();
        return null;
    }

    /** Whether {@code entity} in the open interior cell {@code cell} is on the rolling ladder at game tick {@code tick}. */
    boolean ladder(BlockPos cell, Entity entity, long tick) {
        return ladder(cell, entity.getX(), entity.getY() + FEET, entity.getZ(), tick);
    }

    /** Whether a body whose feet are at {@code x}, {@code y}, {@code z} in the cell {@code cell} are on the rolling ladder at game tick {@code tick}. */
    boolean ladder(BlockPos cell, double x, double y, double z, long tick) {
        var tank = at(cell);
        return tank != null && onLadder(tank, x, y, z, tick);
    }

    /** Whether a body whose feet are at {@code x}, {@code y}, {@code z} are on {@code tank}'s rolling ladder at game tick {@code tick}. */
    private static boolean onLadder(Tank tank, double x, double y, double z, long tick) {
        var roof = tank.roof;
        if (roof.length() <= 0) return false;
        double pitch = roof.pitch(tank.level(tick)), c = DeterministicMath.cos(pitch), s = DeterministicMath.sin(pitch);
        double[] p = roof.pivot(), a = roof.along();
        double dx = a[0] * c, dy = -s, dz = a[2] * c;
        double ex = x - (tank.origin.getX() + p[0]), ey = y - (tank.origin.getY() + p[1]), ez = z - (tank.origin.getZ() + p[2]);
        // A little past its head too: a climber keeps hold while stepping onto the platform.
        double t = Math.max(-HEAD_REACH, Math.min(roof.length(), ex * dx + ey * dy + ez * dz));
        double rx = ex - t * dx, ry = ey - t * dy, rz = ez - t * dz;
        return rx * rx + ry * ry + rz * rz < LADDER_REACH * LADDER_REACH;
    }

    /**
     * Server, once a tick: the roofs that rise this tick lift what stands on them, players excepted (their clients move
     * them). A roof that is still or sinks lifts nothing and costs one level read: what stands on a sinking roof follows
     * it down by gravity.
     */
    public static void carry(Level level) {
        var index = LEVELS.get(level);
        if (index == null || index.byNode.isEmpty()) return;
        long tick = level.getGameTime();
        for (var tank : index.byNode.values()) {
            double now = tank.level(tick), under = tank.roof.underside(now), before = tank.roof.underside(tank.level(tick - 1));
            if (!(under > before)) continue;
            double r = tank.roof.radius(), low = tank.origin.getY() + before + Math.min(tank.roof.deck(), tank.roof.pontoon()) - LIFT,
                    high = tank.origin.getY() + under + Math.max(tank.roof.deck(), tank.roof.pontoon());
            var box = new AABB(tank.axisX - r, low, tank.axisZ - r, tank.axisX + r, high, tank.axisZ + r);
            for (var entity : level.getEntities((Entity) null, box, e -> !(e instanceof Player) && !e.noPhysics && !e.isPassenger() && !e.isSpectator()))
                lift(tank, entity, now);
        }
    }

    /** Client, once a tick: the client's own player rides the roof under it, and walks up the rolling ladder it is on. */
    public static void carry(Player player) {
        if (player.isSpectator() || player.getAbilities().flying || player.isPassenger()) return;
        var index = LEVELS.get(player.level());
        if (index == null || index.byNode.isEmpty()) return;
        long tick = player.level().getGameTime();
        for (var tank : index.byNode.values()) lift(tank, player, tank.level(tick));
        var motion = player.getDeltaMovement();
        var next = index.climb(player.getX(), player.getY(), player.getZ(), player.getBbWidth() / 2, new double[]{motion.x, motion.y, motion.z}, tick);
        if (next == null) return;
        player.setDeltaMovement(next[0], next[1], next[2]);
        player.resetFallDistance();
    }

    /**
     * The motion a rolling ladder gives a body {@code half} m wide each way, its feet at {@code x}, {@code feet}, {@code z}
     * (world), moving {@code motion}, at game tick {@code tick} ({@link #climb(Tank, double, double, double, double, double[], long)}); null on none.
     */
    double @Nullable [] climb(double x, double feet, double z, double half, double[] motion, long tick) {
        for (var tank : byNode.values()) {
            var next = climb(tank, x, feet, z, half, motion, tick);
            if (next != null) return next;
        }
        return null;
    }

    /**
     * A body on the rolling ladder stands on its treads (owner reports 2026-10-21: the ladder did not climb, then a climber
     * stuck under the platform's edge): its feet follow the treads' line ({@link #tread}), so walking along it climbs or
     * descends it as a ramp. A vanilla ladder climbs only against a wall or with a jump; this one is pitched, with no wall.
     * The next tick's rise brings the feet to the line, at most a running climb up; down, at most a ladder's slowed fall,
     * the pace in along it held to what that fall follows. The motion of a body {@code half} m wide each way, its feet at
     * {@code x}, {@code feet}, {@code z} (world), moving {@code motion}, at game tick {@code tick}; null where it is not on
     * the ladder or its box is off the ladder's run (over the deck the platform carries it, past the foot the roof).
     */
    static double @Nullable [] climb(Tank tank, double x, double feet, double z, double half, double[] motion, long tick) {
        if (!onLadder(tank, x, feet + FEET, z, tick)) return null;
        var roof = tank.roof;
        double level = tank.level(tick), slope = slope(roof, level);
        double[] p = roof.pivot(), a = roof.along();
        // How far in from its head, along its level direction, the body's middle stands.
        double in = (x - (tank.origin.getX() + p[0])) * a[0] + (z - (tank.origin.getZ() + p[2])) * a[2];
        if (in < 0 || in - lead(a, half) > roof.length() * DeterministicMath.cos(roof.pitch(level))) return null;
        double line = tank.origin.getY() + tread(roof, level, in, half);
        double inward = motion[0] * a[0] + motion[2] * a[2], held = DESCENT / slope, mx = motion[0], mz = motion[2];
        if (inward > held) {
            mx -= (inward - held) * a[0];
            mz -= (inward - held) * a[2];
        }
        return new double[]{mx, Math.max(-DESCENT, Math.min(MAX_CLIMB, line - feet)), mz};
    }

    /**
     * The treads' height, box y, under a body {@code half} m wide each way whose middle stands {@code in} m in from the
     * rolling ladder's head along its level direction, the liquid {@code level} m deep. A box on a ramp rests on its uphill
     * edge: the line is taken {@link #lead} nearer the head (owner report 2026-10-25: "I cannot reach the platform using
     * the moving ladder"; taken at the middle, the feet stood 0.3 to 0.42 m of run under the deck's height as the box met
     * the deck's edge, and the box ran into the edge). So the feet reach the deck's height as the box reaches the deck,
     * the box clears the roof as its edge leaves the ladder's foot, and on a steep ladder the box stands in front of the
     * ladder, as on a vanilla one.
     */
    static double tread(ShapeRasterizer.Roof roof, double level, double in, double half) {
        return roof.pivot()[1] - Math.max(0, in - lead(roof.along(), half)) * slope(roof, level);
    }

    /**
     * How far a body {@code half} m wide each way reaches from its middle towards the ladder's head along its level
     * direction {@code a}, m: the world-aligned box's reach (its diagonal on a ladder at an angle) and the deck's
     * {@link #PROUD} edge.
     */
    static double lead(double[] a, double half) {
        return half * (Math.abs(a[0]) + Math.abs(a[2])) + PROUD;
    }

    /** The rolling ladder's rise per metre of run, the liquid {@code level} m deep: {@link #MAX_SLOPE} at most. */
    static double slope(ShapeRasterizer.Roof roof, double level) {
        double pitch = roof.pitch(level);
        return Math.min(MAX_SLOPE, DeterministicMath.sin(pitch) / Math.max(1e-6, DeterministicMath.cos(pitch)));
    }

    /** Lifts {@code entity} onto the roof of {@code tank} at {@code level} when its feet stand just under the surface. */
    private static void lift(Tank tank, Entity entity, double level) {
        double dx = entity.getX() - tank.axisX, dz = entity.getZ() - tank.axisZ;
        if (dx * dx + dz * dz > tank.roof.radius() * tank.roof.radius()) return;
        double surface = tank.surface(entity.getBlockX(), entity.getBlockZ(), level), feet = entity.getY();
        if (feet >= surface || feet < surface - LIFT) return;
        entity.setPos(entity.getX(), surface, entity.getZ());
        var motion = entity.getDeltaMovement();
        if (motion.y < 0) entity.setDeltaMovement(motion.x, 0, motion.z);
        entity.setOnGround(true);
        entity.resetFallDistance();
    }
}
