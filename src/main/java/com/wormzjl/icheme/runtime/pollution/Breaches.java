package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.MachineHealth;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.pollution.ExplosionLaw;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.Positions;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blasts breach constructed equipment (batch 2026-11-15-pollution-system, WP6b; plan section 7, proposal A): any explosion
 * (TNT, a creeper, a burning cube's blast) whose strength at a cell of a structure is at least
 * {@link ExplosionLaw#BREACH_POWER} breaches it, and no block of it is removed. Its closed vessels stand open to the air
 * ({@link FluidWorldAuthority#breach}: they vent and spill by the open-vessel law, and the releases reach the field as an
 * open tank's do), and its pumps, compressors and turbines fail ({@link MachineHealth#breach}), until a player repairs them
 * with the repair kit.
 *
 * <p>The strength at a cell is {@link ExplosionLaw#strengthAt} at the distance from the blast's centre to the cell's centre,
 * the cell's walls in between ignored. The equipment's cells resist every blast (vanilla's rays never reach them), so the
 * explosion hook ({@link #detonated}, NeoForge's {@link ExplosionEvent.Detonate}) finds the structures in reach by their
 * shell cells' block entities in the loaded chunks around the centre; a custom blast that ignores resistance also reaches a
 * cell's {@code onBlockExploded} ({@link #exploded}). Both only collect, one entry per structure and tick, at its strongest
 * cell; the pollution tick applies them ({@link #flush}): one queued edit per breached vessel, one machine failure, one burst
 * sound and one chat line to the players within {@link #TELL_RADIUS} per structure.</p>
 *
 * <p>Off with {@code pollution.enabled} or {@code pollution.equipmentBreach} false. Server thread only; all of it is state,
 * none saved (the breach itself is the vessel's fact, {@code FluidDeviceSpec.breached}). Never throws.</p>
 */
public final class Breaches {
    /** Players within this many blocks of a breached structure are told. */
    public static final double TELL_RADIUS = 32;
    /** The burst sound's volume at a breach. */
    public static final float SOUND_VOLUME = 3f;
    /** Structures one tick's blasts breach at most; more are dropped. */
    public static final int STRUCTURE_CAP = 256;

    private record Key(ResourceKey<Level> dimension, long origin) {
    }

    /** One structure a blast reached this tick: its strongest cell. */
    private static final class Hit {
        final ServerLevel level;
        final BlockPos origin;
        final EquipmentShape shape;
        BlockPos cell;
        double strength = -1;

        Hit(ServerLevel level, BlockPos origin, EquipmentShape shape) {
            this.level = level;
            this.origin = origin;
            this.shape = shape;
        }
    }

    private static final Map<MinecraftServer, LinkedHashMap<Key, Hit>> PENDING = new IdentityHashMap<>();
    private static long failures;

    private Breaches() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The rules (pure).

    /** A blast of {@code strength} at a cell breaches the structure: at least {@link ExplosionLaw#BREACH_POWER}. */
    public static boolean breaches(double strength) {
        return strength >= ExplosionLaw.BREACH_POWER;
    }

    private static boolean on() {
        return IChemE.pollutionEnabled() && IChemE.pollutionEquipmentBreach();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The hooks: they only collect.

    /**
     * NeoForge's {@link ExplosionEvent.Detonate} (game bus): the structures whose shell cells lie within the blast's reach of
     * {@link ExplosionLaw#BREACH_POWER} ({@link ExplosionLaw#reach}), in loaded chunks only. Never throws.
     */
    public static void detonated(ExplosionEvent.Detonate event) {
        try {
            if (!(event.getLevel() instanceof ServerLevel level) || !on()) {
                return;
            }
            var explosion = event.getExplosion();
            double power = explosion.radius(), reach = ExplosionLaw.reach(power, ExplosionLaw.BREACH_POWER);
            if (!(reach > 0)) {
                return;
            }
            var centre = explosion.center();
            int x0 = Mth.floor(centre.x - reach) >> 4, x1 = Mth.floor(centre.x + reach) >> 4;
            int z0 = Mth.floor(centre.z - reach) >> 4, z1 = Mth.floor(centre.z + reach) >> 4;
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    var chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
                    for (var entity : chunk.getBlockEntities().values()) {
                        if (!(entity instanceof EquipmentShellBlockEntity shell)) {
                            continue;
                        }
                        var at = entity.getBlockPos();
                        double distance = Math.sqrt(Vec3.atCenterOf(at).distanceToSqr(centre));
                        if (distance <= reach) {
                            hit(level, shell, at, ExplosionLaw.strengthAt(power, distance));
                        }
                    }
                }
            }
        } catch (RuntimeException failure) {
            failed("detonate", failure);
        }
    }

    /**
     * {@code EquipmentShellBlock.onBlockExploded} and {@code EquipmentInteriorBlock.onBlockExploded}: a blast that reached the
     * cell at {@code position} itself (a custom one that ignores resistance). The block stays. Never throws.
     */
    public static void exploded(ServerLevel level, BlockPos position, Explosion explosion) {
        try {
            if (explosion == null || !on()) {
                return;
            }
            var vessel = EquipmentStructure.find(level, position);
            if (vessel == null || !vessel.shape().fluidDevice()) {
                return;
            }
            double distance = Math.sqrt(Vec3.atCenterOf(position).distanceToSqr(explosion.center()));
            var key = new Key(level.dimension(), vessel.origin().asLong());
            add(level, key, vessel.origin(), vessel.shape(), position, ExplosionLaw.strengthAt(explosion.radius(), distance));
        } catch (RuntimeException failure) {
            failed("exploded", failure);
        }
    }

    private static void hit(ServerLevel level, EquipmentShellBlockEntity shell, BlockPos at, double strength) {
        var origin = shell.origin();
        var shape = shell.shape();
        if (origin == null || shape == null || !shape.fluidDevice()) {
            return;
        }
        add(level, new Key(level.dimension(), origin.asLong()), origin, shape, at, strength);
    }

    private static void add(ServerLevel level, Key key, BlockPos origin, EquipmentShape shape, BlockPos at, double strength) {
        if (!breaches(strength)) {
            return;
        }
        var pending = PENDING.computeIfAbsent(level.getServer(), s -> new LinkedHashMap<>());
        var hit = pending.get(key);
        if (hit == null) {
            if (pending.size() >= STRUCTURE_CAP) {
                return;
            }
            hit = new Hit(level, origin.immutable(), shape);
            pending.put(key, hit);
        }
        if (strength > hit.strength) {
            hit.strength = strength;
            hit.cell = at.immutable();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The pass.

    /** Applies this tick's breaches; from the pollution tick, after the levels ticked. Never throws. */
    public static void flush(MinecraftServer server) {
        LinkedHashMap<Key, Hit> pending;
        try {
            pending = PENDING.remove(server);
        } catch (RuntimeException failure) {
            failed("flush", failure);
            return;
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }
        FluidWorldAuthority world;
        try {
            world = FluidWorldAuthority.find(server).orElse(null);
        } catch (RuntimeException failure) {
            failed("flush", failure);
            return;
        }
        if (world == null) {
            return;
        }
        for (var hit : pending.values()) {
            try {
                breach(world, hit);
            } catch (RuntimeException failure) {
                failed("breach", failure);
            }
        }
    }

    /** One structure: each breachable vessel and each machine at its nodes; its sound and chat line when any was breached. */
    private static void breach(FluidWorldAuthority world, Hit hit) {
        var level = hit.level;
        // The structure still stands where the blast found it.
        if (!level.isLoaded(hit.cell) || EquipmentStructure.find(level, hit.cell) == null) {
            return;
        }
        boolean breached = false, wear = IChemE.wearSettings().enabled();
        for (var cell : ShapeRasterizer.fluidCells(hit.shape)) {
            var node = hit.origin.offset(cell.x(), cell.y(), cell.z());
            var record = EquipmentStructure.heldAt(world, Positions.of(level, node)).orElse(null);
            if (record == null) {
                continue;
            }
            long id = record.device().id();
            if (FluidWorldAuthority.breachable(record)) {
                breached |= world.breach(id);
            } else if (wear && MachineHealth.pump(record) && !world.health().failed(id)) {
                world.health().breach(id);
                breached = true;
            }
        }
        if (!breached) {
            return;
        }
        IChemE.LOGGER.info("pollution status=BREACHED dimension={} origin={},{},{} strength={}", level.dimension().location(),
                hit.origin.getX(), hit.origin.getY(), hit.origin.getZ(), hit.strength);
        level.playSound(null, hit.cell, ModBlocks.PIPE_BURST_GAS.get(), SoundSource.BLOCKS, SOUND_VOLUME, 1f);
        String name = EquipmentStructure.name(level, new EquipmentStructure.Vessel(hit.origin, hit.shape));
        var line = name == null || name.isBlank()
                ? Component.translatable("chat.icheme.breach.unnamed", hit.origin.getX(), hit.origin.getY(), hit.origin.getZ())
                : Component.translatable("chat.icheme.breach", name);
        var centre = Vec3.atCenterOf(hit.cell);
        for (var player : level.players()) {
            if (player.distanceToSqr(centre) <= TELL_RADIUS * TELL_RADIUS) {
                player.sendSystemMessage(line);
            }
        }
    }

    /** Forgets a stopped server's pending breaches. Never throws. */
    public static void forget(MinecraftServer server) {
        try {
            PENDING.remove(server);
        } catch (RuntimeException failure) {
            failed("forget", failure);
        }
    }

    private static void failed(String stage, RuntimeException failure) {
        if (failures++ % 1000 == 0) {
            IChemE.LOGGER.warn("pollution status=BREACH_FAILED stage={} failures={} detail={}", stage, failures, String.valueOf(failure));
        }
    }
}
