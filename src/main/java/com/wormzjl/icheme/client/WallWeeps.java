package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.fx.sound.SoundBudget;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.*;

/**
 * The weeping walls on the client (batch 2026-11-16-fouling-corrosion, WP4, plan section 7): a shell cell whose update tag
 * says a wall of it weeps drips slowly from the lowest cells of its structure and plays the {@code wall_weep} loop through
 * the loop budget ({@link com.wormzjl.icheme.client.fx.sound.FxSounds}). Both reach about {@link #RANGE} blocks. The index
 * is kept by the cells' updates and unloads; nothing is computed but where to drip. No fluid leaves. Client thread only.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class WallWeeps {
    /** How far a weeping wall is seen and heard, blocks. */
    public static final double RANGE = 8;
    /**
     * The weep's sound power level, dB: a pinhole's thin hiss, a little above a pot's boil (72 dB), so the budget's volume
     * (0.03) leaves it audible beside the plant. Heard only within {@link #RANGE}: the emitter is left out beyond it.
     */
    public static final double LEVEL = 80;
    /** Ticks between two drips of one structure. */
    public static final int DRIP_TICKS = 10;
    /** The most structures that drip in one tick (the drips' share of the effect budget). */
    public static final int MAX_DRIPS = 16;

    private static final Set<EquipmentShellBlockEntity> WEEPING = Collections.newSetFromMap(new WeakHashMap<>());
    /** The lowest shell cells of each shape, as offsets from its origin. */
    private static final Map<EquipmentShape, List<BlockPos>> LOWEST = new WeakHashMap<>();
    private static final Random RANDOM = new Random();

    private WallWeeps() {}

    public static void track(EquipmentShellBlockEntity cell) {
        WEEPING.add(cell);
    }

    public static void forget(EquipmentShellBlockEntity cell) {
        WEEPING.remove(cell);
    }

    /** The weep loops within {@link #RANGE} of {@code point}: one emitter per weeping cell, at its structure's centre. */
    public static void emitters(Vec3 point, List<SoundBudget.Emitter> into) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        for (var cell : List.copyOf(WEEPING)) {
            if (cell.isRemoved() || cell.getLevel() != level) continue;
            var c = centre(cell);
            if (c == null || c.distanceToSqr(point) > RANGE * RANGE) continue;
            into.add(new SoundBudget.Emitter("wall_weep", cell.getBlockPos().asLong(), c.x, c.y, c.z, LEVEL, 1));
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused() || WEEPING.isEmpty()) return;
        long now = mc.level.getGameTime();
        var point = mc.player.position();
        int drips = 0;
        for (var cell : List.copyOf(WEEPING)) {
            if (drips >= MAX_DRIPS) break;
            if (cell.isRemoved() || cell.getLevel() != mc.level) continue;
            // Each structure on its own tick of the ten, so drips do not fall in step.
            if (Math.floorMod(now + cell.getBlockPos().hashCode(), DRIP_TICKS) != 0) continue;
            var shape = cell.shape();
            var origin = cell.origin();
            var c = centre(cell);
            if (shape == null || origin == null || c == null || c.distanceToSqr(point) > RANGE * RANGE) continue;
            var cells = LOWEST.computeIfAbsent(shape, WallWeeps::lowest);
            if (cells.isEmpty()) continue;
            var at = origin.offset(cells.get(RANDOM.nextInt(cells.size())));
            mc.level.addParticle(ParticleTypes.DRIPPING_WATER, at.getX() + .2 + .6 * RANDOM.nextDouble(), at.getY() - .05, at.getZ() + .2 + .6 * RANDOM.nextDouble(), 0, 0, 0);
            drips++;
        }
    }

    /** The shell cells of {@code shape} at its lowest level. */
    static List<BlockPos> lowest(EquipmentShape shape) {
        var shell = ShapeRasterizer.layout(shape).shell();
        int low = Integer.MAX_VALUE;
        for (var c : shell) low = Math.min(low, c.y());
        var out = new ArrayList<BlockPos>();
        for (var c : shell) if (c.y() == low) out.add(new BlockPos(c.x(), c.y(), c.z()));
        return List.copyOf(out);
    }

    private static Vec3 centre(EquipmentShellBlockEntity cell) {
        var shape = cell.shape();
        var origin = cell.origin();
        if (shape == null || origin == null) return null;
        return new Vec3(origin.getX() + shape.sizeX() / 2.0, origin.getY() + shape.sizeY() / 2.0, origin.getZ() + shape.sizeZ() / 2.0);
    }
}
