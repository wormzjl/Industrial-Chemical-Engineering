package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.Integrity;
import com.wormzjl.icheme.runtime.fluid.MachineHealth;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.SavedChunkData;
import com.wormzjl.icheme.runtime.fluid.SavedEquipment;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.pollution.ExplosionLaw;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.Positions;
import com.wormzjl.icheme.world.level.block.entity.DeviceNames;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Blasts breach constructed equipment (batch 2026-11-15-pollution-system, WP6b; plan section 7, proposal A): any explosion
 * (TNT, a creeper, a burning cube's blast) whose strength at a cell of a structure is at least
 * {@link ExplosionLaw#BREACH_POWER} breaches it, and no block of it is removed. Its closed vessels stand open to the air
 * ({@link FluidWorldAuthority#breach}: they vent and spill by the open-vessel law, and the releases reach the field as an
 * open tank's do), its heat sides close and let their holdup out once, and its pumps, compressors and turbines fail
 * ({@link MachineHealth#breach}). Blast damage is final (batch 2026-11-16-fouling-corrosion, plan section 9): every device
 * of the structure is wrecked ({@code Integrity#wreck}), refuses the Equipment Constructor's repair and is removed only by
 * its deconstruction. A device breached before facts schema 29 has no {@code wrecked} and stays repairable.
 *
 * <p>The strength at a cell is {@link ExplosionLaw#strengthAt} at the distance from the blast's centre to the cell's centre,
 * the cell's walls in between ignored. The equipment's cells resist every blast (vanilla's rays never reach them), so
 * {@link #collect} finds the structures in reach from the registry's build facts and their rasterized shells. Vanilla's
 * explosion hook and a pollution blast without a vanilla explosion both use that collector. A custom blast that ignores
 * resistance can also reach a cell's {@code onBlockExploded} ({@link #exploded}). The hooks only collect, one entry per
 * structure and tick, at its strongest cell. The pollution tick applies them ({@link #flush}): one queued edit per
 * breached vessel, one machine failure, one burst
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

    private record LegacyDevice(PhysicalFluidTopology.Position position, long id) {}

    private record LegacyHit(String name, List<LegacyDevice> devices) {}

    /** One structure a blast reached this tick: its strongest cell. */
    private static final class Hit {
        final ServerLevel level;
        final BlockPos origin;
        final EquipmentShape shape;
        final boolean recorded;
        LegacyHit legacy;
        BlockPos cell;
        double strength = -1;

        Hit(ServerLevel level, BlockPos origin, EquipmentShape shape, boolean recorded) {
            this.level = level;
            this.origin = origin;
            this.shape = shape;
            this.recorded = recorded;
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
     * {@link ExplosionLaw#BREACH_POWER} ({@link ExplosionLaw#reach}). Never throws.
     */
    public static void detonated(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var explosion = event.getExplosion();
            collect(level, explosion.center(), explosion.radius());
        }
    }

    /** Collects plant damage from either blast path, using the same law and structure facts. */
    static void collect(ServerLevel level, Vec3 centre, double power) {
        try {
            if (!on()) return;
            double reach = ExplosionLaw.reach(power, ExplosionLaw.BREACH_POWER);
            if (!(reach > 0)) {
                return;
            }
            int x0 = Mth.floor(centre.x - reach) >> 4, x1 = Mth.floor(centre.x + reach) >> 4;
            int z0 = Mth.floor(centre.z - reach) >> 4, z1 = Mth.floor(centre.z + reach) >> 4;
            var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
            if (world == null) return;
            for (var at : world.structuresInChunks(level.dimension().location().toString(), x0, x1, z0, z1)) {
                var structure = world.structure(at);
                if (structure == null || !structure.shape().fluidDevice()) continue;
                var origin = new BlockPos(at.x(), at.y(), at.z());
                var closest = closestShell(origin, structure.shape(), centre, reach);
                if (closest != null) {
                    add(level, origin, structure.shape(), true, closest, strength(power, centre, closest));
                }
            }
            // Only worlds with unrecorded constructed registrations need to inspect old cells.
            var legacy = SavedEquipment.unrecordedFluidNodes(world, level.dimension().location().toString());
            if (legacy.isEmpty()) return;
            var matches = new HashMap<BlockPos, Optional<LegacyHit>>();
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    var chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        savedCells(level, world, cx, cz, centre, power, legacy, matches);
                        continue;
                    }
                    for (var entity : chunk.getBlockEntities().values()) {
                        if (!(entity instanceof EquipmentShellBlockEntity shell)) {
                            continue;
                        }
                        var at = entity.getBlockPos();
                        var origin = shell.origin();
                        var shape = shell.shape();
                        if (origin != null && shape != null && shape.fluidDevice()
                                && world.structure(Positions.of(level, origin)) == null) {
                            add(level, origin, shape, false, at, strength(power, centre, at));
                        }
                    }
                }
            }
        } catch (RuntimeException failure) {
            failed("detonate", failure);
        }
    }

    private static void savedCells(ServerLevel level, FluidWorldAuthority world, int x, int z, Vec3 centre, double power,
            List<Long> legacy, Map<BlockPos, Optional<LegacyHit>> matches) {
        try {
            var chunk = SavedChunkData.read(level, x, z);
            if (chunk == null) return;
            for (Tag entry : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
                var tag = (CompoundTag) entry;
                if (!"icheme:equipment_shell".equals(tag.getString("id"))) continue;
                if (!tag.contains("x", Tag.TAG_INT) || !tag.contains("y", Tag.TAG_INT) || !tag.contains("z", Tag.TAG_INT)) continue;
                var at = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                if ((at.getX() >> 4) != x || (at.getZ() >> 4) != z) continue;
                double hitPower = strength(power, centre, at);
                if (!breaches(hitPower)) continue;
                var vessel = SavedEquipment.shell(at, tag);
                if (vessel == null || !vessel.shape().fluidDevice() || world.structure(Positions.of(level, vessel.origin())) != null) continue;
                var damage = matches.computeIfAbsent(vessel.origin(), ignored -> legacyHit(world, vessel, tag, legacy));
                if (damage.isPresent()) {
                    add(level, vessel.origin(), vessel.shape(), false, at, hitPower, damage.get());
                }
            }
        } catch (RuntimeException failure) {
            failed("saved shell", failure);
        }
    }

    /** Verifies each candidate at its recorded node before it can be damaged as part of an old shell. */
    private static Optional<LegacyHit> legacyHit(FluidWorldAuthority world, EquipmentStructure.Vessel vessel,
            CompoundTag shell, List<Long> candidates) {
        var box = shell.getIntArray("Box");
        boolean hasBox = box.length == 3 && EquipmentShellBlockEntity.validSavedCell(box[0] - 1, box[1] - 1, box[2] - 1);
        var origin = vessel.origin();
        var devices = new ArrayList<LegacyDevice>();
        for (long id : candidates) {
            var r = world.registrations().get(id);
            if (!Integrity.constructed(r)) continue;
            var p = r.device().position();
            int x = p.x() - origin.getX(), y = p.y() - origin.getY(), z = p.z() - origin.getZ();
            if (!EquipmentShellBlockEntity.validSavedCell(x, y, z)
                    || hasBox && (x >= box[0] || y >= box[1] || z >= box[2])) continue;
            var node = new BlockPos(p.x(), p.y(), p.z());
            var record = world.savedBlockEntity(p.dimension(), node);
            var owner = SavedEquipment.shell(node, record);
            if (owner == null || !owner.equals(vessel)) continue;
            if (record.contains("FluidIdentity") && !record.contains("FluidIdentity", Tag.TAG_ANY_NUMERIC)) continue;
            long bound = record.getLong("FluidIdentity");
            if (bound == 0 || bound == id) devices.add(new LegacyDevice(p, id));
        }
        if (devices.isEmpty()) return Optional.empty();
        return Optional.of(new LegacyHit(DeviceNames.read(shell, "equipment_compat at=" + origin.toShortString()), List.copyOf(devices)));
    }

    /** The nearest shell cell in reach, using the same cells the equipment places. */
    static BlockPos closestShell(BlockPos origin, EquipmentShape shape, Vec3 centre, double reach) {
        double closestDistance = reach * reach;
        BlockPos closest = null;
        for (var cell : ShapeRasterizer.layout(shape).shell()) {
            var at = origin.offset(cell.x(), cell.y(), cell.z());
            double distance = Vec3.atCenterOf(at).distanceToSqr(centre);
            if (distance < closestDistance || closest == null && distance == closestDistance) {
                closestDistance = distance;
                closest = at;
            }
        }
        return closest;
    }

    private static double strength(double power, Vec3 centre, BlockPos at) {
        return ExplosionLaw.strengthAt(power, Math.sqrt(Vec3.atCenterOf(at).distanceToSqr(centre)));
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
            var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
            if (world == null) return;
            boolean recorded = world.structure(Positions.of(level, vessel.origin())) != null;
            add(level, vessel.origin(), vessel.shape(), recorded, position, strength(explosion.radius(), explosion.center(), position));
        } catch (RuntimeException failure) {
            failed("exploded", failure);
        }
    }

    private static void add(ServerLevel level, BlockPos origin, EquipmentShape shape, boolean recorded, BlockPos at, double strength) {
        add(level, origin, shape, recorded, at, strength, null);
    }

    private static void add(ServerLevel level, BlockPos origin, EquipmentShape shape, boolean recorded, BlockPos at, double strength,
            LegacyHit legacy) {
        if (!breaches(strength)) {
            return;
        }
        var key = new Key(level.dimension(), origin.asLong());
        var pending = PENDING.computeIfAbsent(level.getServer(), s -> new LinkedHashMap<>());
        var hit = pending.get(key);
        if (hit == null) {
            if (pending.size() >= STRUCTURE_CAP) {
                return;
            }
            hit = new Hit(level, origin.immutable(), shape, recorded);
            pending.put(key, hit);
        }
        if (strength > hit.strength) {
            hit.strength = strength;
            hit.cell = at.immutable();
        }
        if (legacy != null) hit.legacy = legacy;
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
        var structure = world.structure(Positions.of(level, hit.origin));
        String name;
        if (structure != null) {
            if (!structure.shape().equals(hit.shape)) return;
            name = structure.name();
        } else if (hit.legacy != null) {
            name = hit.legacy.name();
        } else {
            if (hit.recorded || !level.isLoaded(hit.cell)) return;
            var vessel = EquipmentStructure.find(level, hit.cell);
            if (vessel == null || !vessel.origin().equals(hit.origin) || !vessel.shape().equals(hit.shape)) return;
            name = EquipmentStructure.name(level, vessel);
        }
        boolean breached = false, wear = IChemE.wearSettings().enabled();
        for (var record : registrations(world, hit, structure != null)) {
            long id = record.device().id();
            // Blast damage is final (batch 2026-11-16-fouling-corrosion, plan section 9): every device of the structure is
            // wrecked, deconstructed and rebuilt, never repaired.
            boolean wrecked = !world.integrity().wrecked(id);
            world.integrity().wreck(id);
            breached |= wrecked;
            if (FluidWorldAuthority.breachable(record) || FluidWorldAuthority.closable(record)) {
                // A heat side so breached stands closed and lets its holdup out once, as a wall corroded through does.
                boolean side = FluidWorldAuthority.closable(record), done = world.breach(id);
                if (done && side) world.releaseContents(id);
                breached |= done;
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
        if (level.isLoaded(hit.cell)) {
            level.playSound(null, hit.cell, ModBlocks.PIPE_BURST_GAS.get(), SoundSource.BLOCKS, SOUND_VOLUME, 1f);
        }
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

    private static List<WorldTopologyLedger.Registration> registrations(FluidWorldAuthority world, Hit hit, boolean recorded) {
        var out = new ArrayList<WorldTopologyLedger.Registration>();
        if (!recorded && hit.legacy != null) {
            for (var node : hit.legacy.devices()) {
                EquipmentStructure.heldAt(world, node.position()).filter(r -> r.device().id() == node.id()).ifPresent(out::add);
            }
        } else {
            for (var cell : ShapeRasterizer.fluidCells(hit.shape)) {
                var node = hit.origin.offset(cell.x(), cell.y(), cell.z());
                EquipmentStructure.heldAt(world, Positions.of(hit.level, node)).ifPresent(out::add);
            }
        }
        return out;
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
