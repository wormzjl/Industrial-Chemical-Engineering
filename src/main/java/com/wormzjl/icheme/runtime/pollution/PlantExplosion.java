package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PlantTerrain;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

import java.util.ArrayList;
import java.util.Optional;

/** Vanilla's block rays over live and saved terrain. The partial-footprint blast acts only on one-block fluid devices. */
public final class PlantExplosion extends Explosion {
    private final ServerLevel level;
    private final PlantTerrain terrain;
    private final FluidWorldAuthority world;

    private PlantExplosion(ServerLevel level, Vec3 centre, float power, boolean damage, PlantTerrain terrain) {
        super(level, null, Explosion.getDefaultDamageSource(level, null), new ExplosionDamageCalculator() {
            @Override
            public Optional<Float> getBlockExplosionResistance(
                    Explosion explosion, BlockGetter reader, BlockPos at, BlockState state, FluidState fluid) {
                // A missing or unreadable palette is opaque. VOID_AIR returned by the mixin is only a placeholder.
                if (terrain.stateAt(at.getX(), at.getY(), at.getZ()) == null) {
                    return Optional.of(Float.POSITIVE_INFINITY);
                }
                return super.getBlockExplosionResistance(explosion, terrain, at, state, fluid);
            }
        }, centre.x, centre.y, centre.z, power, false, interaction(level, damage),
                ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, SoundEvents.GENERIC_EXPLODE);
        this.level = level;
        this.terrain = terrain;
        this.world = FluidWorldAuthority.find(level.getServer()).orElse(null);
    }

    private static BlockInteraction interaction(ServerLevel level, boolean damage) {
        if (!damage) return BlockInteraction.KEEP;
        return level.getGameRules().getBoolean(GameRules.RULE_BLOCK_EXPLOSION_DROP_DECAY)
                ? BlockInteraction.DESTROY_WITH_DECAY : BlockInteraction.DESTROY;
    }

    /** Called by the ray's two terrain reads. Earlier ruptures in still-absent chunks are already air. */
    public BlockState rayState(BlockPos at) {
        var state = terrain.getBlockState(at);
        if (state.getBlock() instanceof FluidDeviceBlock && world != null && !terrain.live(at.getX() >> 4, at.getZ() >> 4)
                && world.hasRuptureAt(Positions.of(level, at))) {
            var pos=Positions.of(level,at);
            var saved=world.savedBlockEntity(pos.dimension(),at);
            if(saved!=null && world.wasRuptured(pos,saved.getLong("FluidIdentity")))return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    /** Leave only the blocks this path could physically or authoritatively remove, before posting Detonate. */
    public void filterTargets() {
        if (!interactsWithBlocks()) {
            clearToBlow();
        } else {
            getToBlow().removeIf(at -> !(rayState(at).getBlock() instanceof FluidDeviceBlock));
        }
    }

    static void detonate(ServerLevel level, Vec3 centre, float power, boolean damage) {
        var blast = new PlantExplosion(level, centre, power, damage, new PlantTerrain(level));
        if (EventHooks.onExplosionStart(level, blast)) return;
        if (!damage) {
            EventHooks.onExplosionDetonate(level, blast, new ArrayList<>(), 2.0F * power);
            return;
        }
        // The normal blast march posts Detonate on the selected positions. Protection listeners can remove targets.
        blast.explode();
        blast.applyTargets();
    }

    private void applyTargets() {
        for (var at : getToBlow()) {
            var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
            if (chunk != null) {
                var state = chunk.getBlockState(at);
                if (state.getBlock() instanceof FluidDeviceBlock) {
                    state.onExplosionHit(level, at, this, (stack, position) -> Block.popResource(level, position, stack));
                }
            } else if (world != null && rayState(at).getBlock() instanceof FluidDeviceBlock device) {
                var position = Positions.of(level, at);
                world.at(position).ifPresent(record -> {
                    var part = record.device();
                    if (part.kind() != device.kind() || part.port() || !part.ports().isEmpty()) return;
                    // A stale palette cell of the same kind can represent a different device.
                    var saved = world.savedBlockEntity(position.dimension(), at);
                    if (saved == null || saved.getLong("FluidIdentity") != part.id()) return;
                    world.removeRupturedDevice(position, part.id());
                });
            }
        }
    }
}
