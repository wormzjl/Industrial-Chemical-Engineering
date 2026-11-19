package com.wormzjl.icheme.mixin;

import com.wormzjl.icheme.runtime.pollution.PlantExplosion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/** Keep Minecraft's ray march and NeoForge's target event when the plant supplies a ticket-free terrain view. */
@Mixin(Explosion.class)
public abstract class PlantExplosionMixin {
    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState ichemeRayState(Level level, BlockPos at) {
        return (Object) this instanceof PlantExplosion plant ? plant.rayState(at) : level.getBlockState(at);
    }

    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState ichemeRayFluid(Level level, BlockPos at) {
        return (Object) this instanceof PlantExplosion plant ? plant.rayState(at).getFluidState() : level.getFluidState(at);
    }

    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;gameEvent(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;)V"))
    private void ichemeExplosionGameEvent(Level level, Entity source, Holder<GameEvent> event, Vec3 centre) {
        if (!((Object) this instanceof PlantExplosion)) level.gameEvent(source, event, centre);
    }

    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<Entity> ichemeExplosionEntities(Level level, Entity source, AABB bounds) {
        // The visibility rays of entity damage may leave the loaded area. Partial blasts affect devices only.
        return (Object) this instanceof PlantExplosion ? new ArrayList<>() : level.getEntities(source, bounds);
    }

    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/event/EventHooks;onExplosionDetonate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/Explosion;Ljava/util/List;D)V"))
    private void ichemeExplosionTargets(Level level, Explosion explosion, List<Entity> entities, double diameter) {
        if (explosion instanceof PlantExplosion plant) plant.filterTargets();
        EventHooks.onExplosionDetonate(level, explosion, entities, diameter);
        if (explosion instanceof PlantExplosion) entities.clear();
    }
}
