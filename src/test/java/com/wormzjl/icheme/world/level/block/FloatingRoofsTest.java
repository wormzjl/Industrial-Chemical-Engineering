package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.runtime.fluid.FluidPresentation;
import com.wormzjl.icheme.runtime.fluid.TankDisplay;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.PlayerWalk;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A floating roof as the world collides with it (batch 2026-10-21-floating-roof-tanks, option A, owner request
 * 2026-10-21: a player on the roof rides it): the server's roof stands where the client draws it, the open cells collide
 * up to the deck inside its radius and up to the pontoon ring beyond, the roof glides between displays on the game clock,
 * and the rolling ladder is climbable along its pitched line.
 */
class FloatingRoofsTest {
    private static final EquipmentShape TANK = EquipmentShape.storage(Kind.TANK, 20, 12, Heading.NORTH, 0);
    private static final BlockPos ORIGIN = new BlockPos(100, 64, -40);

    private static TankDisplay at(double level) {
        return new TankDisplay((float) level, 0, (float) TANK.fillHeight(), 0, 0, 0);
    }

    @Test
    void theServersRoofStandsWhereTheClientDrawsIt() {
        var roof = ShapeRasterizer.roof(TANK);
        var drawn = ShapeRasterizer.floating(TANK);
        assertNotNull(roof);
        assertNotNull(drawn);
        for (double level : new double[]{0, 1, 1.8, 4, 6, 9.5, 11, 30}) {
            assertEquals(drawn.underside(level), roof.underside(level), 0, "underside at " + level);
            assertEquals(drawn.pitch(level), roof.pitch(level), 1e-12, "ladder pitch at " + level);
            assertEquals(roof.underside(level) + roof.deck(), roof.surface(0, level), 0);
            assertEquals(roof.underside(level) + roof.pontoon(), roof.surface(roof.inner(), level), 0);
        }
        assertTrue(roof.inner() < roof.radius() && roof.radius() < TANK.diameter() / 2, roof.inner() + " " + roof.radius());
        assertTrue(roof.pontoon() > roof.deck(), "the pontoon ring stands above the deck");
        assertNull(ShapeRasterizer.roof(EquipmentShape.storage(Kind.SPHERE, 14, 1, Heading.NORTH, 2)));
    }

    @Test
    void theRoofGlidesToEachDisplayOverABucketOnTheGameClock() {
        var first = TankDisplay.Glide.next(null, Double.NaN, at(4), 1000);
        assertEquals(4, first.level(1000), 0, "with no saved level the first display stands at once");
        var second = TankDisplay.Glide.next(first, Double.NaN, at(6), 1000 + FluidPresentation.BUCKET_TICKS);
        assertEquals(4, second.level(1000 + FluidPresentation.BUCKET_TICKS), 1e-6);
        assertEquals(5, second.level(1000 + 1.5 * FluidPresentation.BUCKET_TICKS), 1e-6);
        assertEquals(6, second.level(1000 + 2 * FluidPresentation.BUCKET_TICKS), 0);
        assertEquals(6, second.level(1e9), 0);
        // A display arriving mid-glide starts from where the roof stands then, so the roof never jumps.
        var early = TankDisplay.Glide.next(second, 9, at(2), 1000 + FluidPresentation.BUCKET_TICKS * 3 / 2);
        assertEquals(5, early.level(early.tick()), 1e-6, "a running glide, not the saved level, is where the roof stands");
    }

    /**
     * Review 2026-10-26-chunk-loading-review, D2: the island runs on while a tank's chunk is unloaded, so the first display
     * after a load may stand metres from the roof, which stands at the level its node cell saved. The roof glides there
     * over a bucket instead of jumping, so a rising roof lifts what stands on it and a falling one lets it follow.
     */
    @Test
    void aReloadedRoofGlidesFromTheLevelItsNodeCellSaved() {
        var roofs = new FloatingRoofs();
        BlockPos node = ORIGIN.offset(1, 0, 1);
        assertTrue(Double.isNaN(roofs.standing(node, 0)), "no tank: no standing level");
        roofs.add(node, ORIGIN, TANK, () -> 2);
        assertEquals(2, roofs.standing(node, 5000), 0, "before its first glide the roof stands at the saved level");
        var reloaded = TankDisplay.Glide.next(null, roofs.standing(node, 5000), at(4.3), 5000);
        assertEquals(2, reloaded.level(5000), 1e-6, "no jump when the display arrives");
        assertEquals(3.15, reloaded.level(5000 + FluidPresentation.BUCKET_TICKS / 2.0), 1e-6);
        assertEquals(4.3, reloaded.level(5000 + FluidPresentation.BUCKET_TICKS), 1e-6);
        // A rise of 2.3 m over a bucket lifts a rider 0.023 m a tick, far inside what the carry lifts.
        assertTrue((4.3 - 2) / FluidPresentation.BUCKET_TICKS < FloatingRoofs.LIFT);
        roofs.glide(node, reloaded);
        assertEquals(4.3, roofs.standing(node, 5000 + FluidPresentation.BUCKET_TICKS), 1e-6, "then the roof stands on its glide");
        // The free water under the oil starts no higher than the saved level.
        var wet = TankDisplay.Glide.next(null, 1, new TankDisplay(4, 1.5f, (float) TANK.fillHeight(), 0, 0, 0), 0);
        assertEquals(1, wet.from().level(), 0);
        assertEquals(1, wet.from().waterLevel(), 0);
        assertEquals(1.5, wet.at(FluidPresentation.BUCKET_TICKS).waterLevel(), 1e-6);
    }

    @Test
    void openCellsCollideUpToTheDeckAndThePontoonRing() {
        var roofs = new FloatingRoofs();
        float[] saved = {6};
        BlockPos node = ORIGIN.offset(1, 0, 1);
        roofs.add(node, ORIGIN, TANK, () -> saved[0]);
        var roof = ShapeRasterizer.roof(TANK);
        int ax = ORIGIN.getX() + (int) Math.floor(roof.x()), az = ORIGIN.getZ() + (int) Math.floor(roof.z());
        // Before any display the roof stands at the level its node cell saved: deck top at 6.15 m over the floor.
        double deck = 6 + roof.deck();
        int floor = (int) Math.floor(deck);
        assertEquals(1, top(roofs, new BlockPos(ax, ORIGIN.getY() + floor - 1, az), 0), 0, "under the deck: solid");
        assertEquals(deck - floor, top(roofs, new BlockPos(ax, ORIGIN.getY() + floor, az), 0), 1. / FloatingRoofs.STEPS);
        assertEquals(0, top(roofs, new BlockPos(ax, ORIGIN.getY() + floor + 1, az), 0), 0, "over the deck: open");
        // Out on the pontoon ring the surface stands higher.
        int rim = ax + (int) Math.floor(roof.inner()) + 1;
        double ring = 6 + roof.pontoon();
        assertEquals(ring - Math.floor(ring), top(roofs, new BlockPos(rim, ORIGIN.getY() + (int) Math.floor(ring), az), 0), 1. / FloatingRoofs.STEPS);
        // The display takes over from the saved level and the cells follow its glide.
        roofs.glide(node, TankDisplay.Glide.next(TankDisplay.Glide.still(at(6)), Double.NaN, at(8), 0));
        assertEquals(1, top(roofs, new BlockPos(ax, ORIGIN.getY() + floor, az), FluidPresentation.BUCKET_TICKS), 0, "the deck rose past the cell");
        assertNull(roofs.collision(ORIGIN.offset(-1, 0, 0), 0), "no tank there");
        roofs.remove(node);
        assertNull(roofs.collision(new BlockPos(ax, ORIGIN.getY() + floor, az), 0), "an unloaded tank collides no more");
    }

    @Test
    void theRollingLadderIsClimbableAlongItsPitchedLine() {
        var roofs = new FloatingRoofs();
        roofs.add(ORIGIN, ORIGIN, TANK, () -> 3);
        var roof = ShapeRasterizer.roof(TANK);
        double pitch = roof.pitch(3), along = roof.length() / 2;
        double[] p = roof.pivot(), a = roof.along();
        double x = ORIGIN.getX() + p[0] + a[0] * Math.cos(pitch) * along, y = ORIGIN.getY() + p[1] - Math.sin(pitch) * along,
                z = ORIGIN.getZ() + p[2] + a[2] * Math.cos(pitch) * along;
        var cell = BlockPos.containing(x, y, z);
        assertTrue(roofs.ladder(cell, x, y, z, 0), "halfway down the ladder");
        assertFalse(roofs.ladder(cell, x, y + 2, z, 0), "two metres over it");
        double cx = ORIGIN.getX() + roof.x(), cz = ORIGIN.getZ() + roof.z(), cy = ORIGIN.getY() + roof.surface(0, 3) + 1;
        assertFalse(roofs.ladder(BlockPos.containing(cx, cy, cz), cx, cy, cz, 0), "the middle of the deck");
    }

    /**
     * A player walks up the rolling ladder from the roof onto the gauger's platform and back down (owner report
     * 2026-10-25: "I cannot reach the platform using the moving ladder"; the feet followed the treads at the body's middle
     * and the box met the deck's edge under its height). The player moves as the game moves one ({@link PlayerWalk})
     * against the tank's collision, its shell cells' 1/8-block voxels and its open cells up to the roof, and the ladder
     * sets its motion before each tick as the client's tick hook does. The roof landed on its legs (the steepest pitch), at
     * mid height and full.
     */
    @Test
    void aPlayerWalksTheRollingLadderBetweenTheRoofAndThePlatform() {
        var roof = ShapeRasterizer.roof(TANK);
        var layout = ShapeRasterizer.layout(TANK);
        Set<ShapeRasterizer.Cell> shell = new HashSet<>(layout.shell()), interior = new HashSet<>(layout.interior());
        double[] pivot = roof.pivot(), a = roof.along(), across = {-a[2], 0, a[0]};
        double[] head = {ORIGIN.getX() + pivot[0], ORIGIN.getY() + pivot[1], ORIGIN.getZ() + pivot[2]};
        double lead = FloatingRoofs.lead(a, PlayerWalk.HALF);
        for (double level : new double[]{TANK.fillHeight() / 2, TANK.fillHeight(), 3, 1, 0}) {
            var roofs = new FloatingRoofs();
            roofs.add(ORIGIN, ORIGIN, TANK, () -> level);
            var walk = new PlayerWalk((i, j, k) -> {
                int x = Math.floorDiv(i, 8), y = Math.floorDiv(j, 8), z = Math.floorDiv(k, 8);
                var cell = new ShapeRasterizer.Cell(x - ORIGIN.getX(), y - ORIGIN.getY(), z - ORIGIN.getZ());
                if (shell.contains(cell))
                    return ShapeRasterizer.inside(TANK, (i + .5) / 8 - ORIGIN.getX(), (j + .5) / 8 - ORIGIN.getY(), (k + .5) / 8 - ORIGIN.getZ())
                            || in(roofs.rim(new BlockPos(x, y, z), 0), (i + .5) / 8 - x, (j + .5) / 8 - y, (k + .5) / 8 - z);
                if (interior.contains(cell)) return (j + .5) / 8 - y < top(roofs, new BlockPos(x, y, z), 0);
                return j < ORIGIN.getY() * 8;
            }, q -> roofs.ladder(BlockPos.containing(q[0], q[1], q[2]), q[0], q[1] + FloatingRoofs.FEET, q[2], 0));
            double run = roof.length() * Math.cos(roof.pitch(level)), start = run + lead + 1;
            walk.p = new double[]{head[0] + start * a[0], ORIGIN.getY() + roof.underside(level) + roof.pontoon() + .3, head[2] + start * a[2]};
            String where = "level " + level + ", run " + run;
            assertTrue(walk(roofs, walk, head, a, across, -1, w -> in(w, head, a) < -.1 && w.onGround && Math.abs(w.p[1] - head[1]) < .07),
                    where + ": up onto the platform, stopped at " + Arrays.toString(walk.p) + " by " + walk.blocker);
            assertTrue(walk(roofs, walk, head, a, across, 1, w -> in(w, head, a) > run + lead + .1 && w.onGround && w.p[1] < head[1] - .5),
                    where + ": down onto the roof, stopped at " + Arrays.toString(walk.p) + " by " + walk.blocker);
            double surface = ORIGIN.getY() + roof.underside(level);
            assertTrue(walk.p[1] >= surface + roof.deck() - .07 && walk.p[1] <= surface + roof.pontoon() + .07, where + ": on the roof, feet at " + walk.p[1]);
        }
    }

    /**
     * Walks {@code walk} along the ladder's level direction {@code a} out ({@code sign} -1) or in (+1), kept to its middle
     * line, the ladder setting the motion before each tick; whether {@code there} held within 600 ticks.
     */
    private static boolean walk(FloatingRoofs roofs, PlayerWalk walk, double[] head, double[] a, double[] across, int sign, java.util.function.Predicate<PlayerWalk> there) {
        for (int i = 0; i < 600; i++) {
            var next = roofs.climb(walk.p[0], walk.p[1], walk.p[2], PlayerWalk.HALF, walk.v, 0);
            if (next != null) walk.v = next;
            double off = (walk.p[0] - head[0]) * across[0] + (walk.p[2] - head[2]) * across[2], c = Math.max(-1, Math.min(1, -4 * off));
            double[] wish = {sign * a[0] + c * across[0], 0, sign * a[2] + c * across[2]};
            double l = Math.hypot(wish[0], wish[2]);
            walk.tick(new double[]{wish[0] / l, 0, wish[2] / l}, false);
            if (i > 10 && there.test(walk)) return true;
        }
        return false;
    }

    /** Whether the cell-local point lies in {@code shape} (null: none). */
    private static boolean in(@Nullable VoxelShape shape, double x, double y, double z) {
        return shape != null && shape.toAabbs().stream().anyMatch(b -> b.contains(x, y, z));
    }

    /** How far in from the ladder's head, along its level direction {@code a}, the player's middle stands, m. */
    private static double in(PlayerWalk walk, double[] head, double[] a) {
        return (walk.p[0] - head[0]) * a[0] + (walk.p[2] - head[2]) * a[2];
    }

    /** The top of the collision shape of {@code cell} at {@code tick}, as a fraction of the cell. */
    private static double top(FloatingRoofs roofs, BlockPos cell, long tick) {
        var shape = roofs.collision(cell, tick);
        assertNotNull(shape, cell.toString());
        return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
    }
}
