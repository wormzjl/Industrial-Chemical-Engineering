package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Nameplate;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The nameplates of named devices (batch 2026-11-02-process-simulator, N1; owner 2026-11-03: "Give them a nameplate on
 * the model as well"): a structure's plate where {@link Nameplate#of} puts it, a one-block device's tag on the side of
 * its casing nearest the eye ({@link Nameplate#tag}). Drawn, not placed: no block, no cell. An unnamed device draws
 * nothing, as before names. Within {@link #RANGE} blocks only, on the side the plate faces, in the frustum; the plate
 * takes the light of the block before it.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class NameplateRenderer {
    /** Beyond this many blocks no plate is drawn: its text no longer reads. */
    public static final double RANGE = 32;
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "textures/misc/instrument.png");
    private static final RenderType TYPE = RenderType.entitySolid(WHITE);
    /** The look: a light plate, a dark frame and dark text (P1). */
    private static final int PLATE = 0xF2F2EC, FRAME = 0x23282C, INK = 0xFF23282C;
    /** The plates of the shapes drawn so far ({@link Nameplate#of} walks the solid; a shape's plate never changes). */
    private static final Map<EquipmentShape, Optional<Nameplate.Plate>> PLATES = new HashMap<>();

    private NameplateRenderer() {
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var camera = event.getCamera().getPosition();
        var buffers = mc.renderBuffers().bufferSource();
        var pose = event.getPoseStack();
        boolean drew = false;
        for (var cell : EquipmentShellBlockEntity.clientNamedCells()) {
            var shape = cell.shape();
            var origin = cell.origin();
            if (shape == null || origin == null || cell.isRemoved() || cell.name().isEmpty()) continue;
            if (PLATES.size() > 1024) PLATES.clear();
            var plate = PLATES.computeIfAbsent(shape, Nameplate::of);
            if (plate.isEmpty()) continue;
            var p = plate.get();
            drew |= draw(event, pose, buffers, camera, new Vec3(origin.getX() + p.x(), origin.getY() + p.y(), origin.getZ() + p.z()), p, cell.name());
        }
        for (var device : FluidDeviceBlockEntity.clientNamed()) {
            if (device.isRemoved() || device.name().isEmpty() || !device.nameable()) continue;
            var at = device.getBlockPos();
            var centre = Vec3.atCenterOf(at);
            if (centre.distanceToSqr(camera) > RANGE * RANGE) continue;
            var state = device.getBlockState();
            int axis = state.getValue(FluidDeviceBlock.FACING).getAxis().ordinal();
            var p = Nameplate.tag(axis, casing(device), camera.x - centre.x, camera.z - centre.z);
            drew |= draw(event, pose, buffers, camera, centre.add(p.x(), p.y(), p.z()), p, device.name());
        }
        if (drew) buffers.endBatch(TYPE);
    }

    /** How far a one-block device's casing reaches from its centre, m: a mover's body, a valve's or filter's, a pipe's with sensors, a whole block. */
    private static double casing(FluidDeviceBlockEntity device) {
        var state = device.getBlockState();
        if (device.kind() != TopologyCompiler.Kind.PIPE) return 0.5;
        if (FluidDeviceBlock.hasMover(state)) return 0.42;
        if (FluidDeviceBlock.hasPressureValve(state) || FluidDeviceBlock.hasFilter(state)) return 0.3;
        return Math.max(0.08, PipeSizes.outside(Math.max(device.bore(), 0.05)) / 2) + 0.05;
    }

    /** Plate {@code p} centred at world point {@code centre}, its text {@code name}; whether it drew. */
    private static boolean draw(RenderLevelStageEvent event, PoseStack pose, MultiBufferSource buffers, Vec3 camera, Vec3 centre, Nameplate.Plate p, String name) {
        var out = new Vec3(p.facing().dx, 0, p.facing().dz);
        var eye = camera.subtract(centre);
        if (eye.lengthSqr() > RANGE * RANGE || eye.dot(out) <= 0) return false;
        double hw = p.width() / 2, hh = p.height() / 2;
        if (!event.getFrustum().isVisible(new AABB(centre.x - hw, centre.y - hh, centre.z - hw, centre.x + hw, centre.y + hh, centre.z + hw))) return false;
        var mc = Minecraft.getInstance();
        int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(centre.add(out.scale(0.3))));
        var rel = centre.subtract(camera);
        pose.pushPose();
        pose.translate(rel.x, rel.y, rel.z);
        float angle = switch (p.facing()) {
            case NORTH -> 180;
            case EAST -> 90;
            case WEST -> -90;
            default -> 0;
        };
        pose.mulPose(Axis.YP.rotationDegrees(angle));
        // Local frame: x to the reader's right, y up, z out of the plate.
        var consumer = buffers.getBuffer(TYPE);
        float border = (float) Math.min(hh * 0.16, 0.012);
        quad(pose, consumer, -hw, -hh, hw, hh, 0, FRAME, light);
        quad(pose, consumer, -hw + border, -hh + border, hw - border, hh - border, 0.002, PLATE, light);
        var font = mc.font;
        int width = Math.max(1, font.width(name));
        float scale = (float) Math.min(p.height() * 0.64 / 8, p.width() * 0.8 / width);
        pose.translate(0, 0, 0.004);
        pose.scale(scale, -scale, scale);
        font.drawInBatch(name, -width / 2f, -4, INK, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
        pose.popPose();
        return true;
    }

    private static void quad(PoseStack pose, VertexConsumer consumer, double x0, double y0, double x1, double y1, double z, int colour, int light) {
        double[][] corners = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        for (var c : corners)
            consumer.addVertex(pose.last(), (float) c[0], (float) c[1], (float) z).setColor(colour >> 16 & 255, colour >> 8 & 255, colour & 255, 255).setUv(0, 0)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose.last(), 0, 0, 1);
    }
}
