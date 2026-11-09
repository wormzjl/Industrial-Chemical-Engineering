package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.gui.screens.EquipmentConstructorScreen;
import com.wormzjl.icheme.client.gui.screens.VesselScreen;
import com.wormzjl.icheme.client.model.EquipmentShellModel;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import net.minecraft.core.BlockPos;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

/**
 * Client side of parametric vessels: the shell model loader, the tint of painted parts, one outline for the whole vessel
 * instead of per-cell boxes (its silhouette as seen from the camera), and the screens the blocks open (the constructor
 * block calls {@link #openConstructor} on the client; the server sends {@link #openVessel} for a structure's datasheet).
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class EquipmentClient {
    private EquipmentClient() {
    }

    @SubscribeEvent
    public static void registerGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(EquipmentShellModel.LOADER_ID, EquipmentShellModel.LOADER);
        event.register(com.wormzjl.icheme.client.model.FluidDeviceModel.LOADER_ID, com.wormzjl.icheme.client.model.FluidDeviceModel.LOADER);
        event.register(com.wormzjl.icheme.client.model.ConduitModel.LOADER_ID,com.wormzjl.icheme.client.model.ConduitModel.LOADER);
        event.register(com.wormzjl.icheme.client.model.PipeRackModel.LOADER_ID, com.wormzjl.icheme.client.model.PipeRackModel.LOADER);
        event.register(com.wormzjl.icheme.client.model.ShaftModel.LOADER_ID, com.wormzjl.icheme.client.model.ShaftModel.LOADER);
    }

    @SubscribeEvent
    public static void renderConfigReloaded(net.neoforged.fml.event.config.ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != ClientConfig.SPEC || ClientConfig.renderDetail() == EquipmentShellModel.detail()) return;
        var client = net.minecraft.client.Minecraft.getInstance();
        client.execute(() -> {
            if (ClientConfig.renderDetail() != EquipmentShellModel.detail()) client.reloadResourcePacks();
        });
    }

    /**
     * A painted quad of a shell cell (tint index: its {@link Paint} channel) takes the cell's paint, scaled to show on the
     * neutral painted sprites; a cell not yet bound shows the default paint.
     */
    @SubscribeEvent
    public static void registerBlockColours(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, position, tint) -> Paint.tint((level != null && position != null
                && level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell ? shell.paint() : Paint.DEFAULT).colour(tint)), ModBlocks.EQUIPMENT_SHELL.get());
        // A painted laid line (batch 2026-10-26-pipe-laying-system): a pipe's tinted quads take the paint of their channel
        // (its pipe the primary, its flanges the secondary), a rack's those of the lane whose option is half the tint
        // index, the low bit the channel. Only painted parts bake tinted quads.
        event.register((state, level, position, tint) -> {
            if (level == null || position == null || !(level.getBlockEntity(position) instanceof FluidDeviceBlockEntity pipe)) return -1;
            int paint = tint == Paint.STRUCTURE ? pipe.secondary() : pipe.paint();
            return paint != Paint.BARE ? Paint.tint(paint) : -1;
        }, ModBlocks.FLUID_PIPE.get());
        event.register((state, level, position, tint) -> {
            if (level != null && position != null && level.getBlockEntity(position) instanceof PipeRackBlockEntity rack)
                for (var lane : rack.lanes()) {
                    if (lane.option() != tint >> 1) continue;
                    int paint = (tint & 1) == Paint.STRUCTURE ? lane.secondary() : lane.paint();
                    return paint != Paint.BARE ? Paint.tint(paint) : -1;
                }
            return -1;
        }, ModBlocks.PIPE_RACK.get());
    }

    /** Called by the constructor block on the client only: open its design screen. */
    public static void openConstructor(BlockPos position) {
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(position) instanceof EquipmentConstructorBlockEntity constructor)
            mc.setScreen(new EquipmentConstructorScreen(position, constructor.facing(), constructor.design()));
    }

    /** The server asked for the datasheet of the structure {@code position} belongs to (the assembly network's routing). */
    public static void openVessel(BlockPos position) {
        var mc = Minecraft.getInstance();
        var vessel = mc.level == null ? null : EquipmentStructure.find(mc.level, position);
        if (vessel != null) mc.setScreen(new VesselScreen(vessel));
    }

    @SubscribeEvent
    public static void highlight(RenderHighlightEvent.Block event) {
        var level = Minecraft.getInstance().level;
        var position = event.getTarget().getBlockPos();
        if (level == null) return;
        var state = level.getBlockState(position);
        if (!state.is(ModBlocks.EQUIPMENT_SHELL.get()) && !state.is(ModBlocks.EQUIPMENT_INTERIOR.get())) return;
        var vessel = EquipmentStructure.find(level, position);
        if (vessel == null) return;
        var camera = event.getCamera().getPosition();
        double ox = vessel.origin().getX() - camera.x, oy = vessel.origin().getY() - camera.y, oz = vessel.origin().getZ() - camera.z;
        var pose = event.getPoseStack().last();
        var lines = event.getMultiBufferSource().getBuffer(RenderType.lines());
        for (var s : ShapeRasterizer.outline(vessel.shape(), -ox, -oy, -oz)) {
            float x0 = (float) (ox + s[0]), y0 = (float) (oy + s[1]), z0 = (float) (oz + s[2]);
            float x1 = (float) (ox + s[3]), y1 = (float) (oy + s[4]), z1 = (float) (oz + s[5]);
            float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length == 0) continue;
            lines.addVertex(pose, x0, y0, z0).setColor(0, 0, 0, .4F).setNormal(pose, dx / length, dy / length, dz / length);
            lines.addVertex(pose, x1, y1, z1).setColor(0, 0, 0, .4F).setNormal(pose, dx / length, dy / length, dz / length);
        }
        event.setCanceled(true);
    }
}
