package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.entity.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.*;

/**
 * Draws structural socket/cable metadata only. No process readings or background network
 * subscriptions.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class DataControlRenderer {
    private static final Set<ControlPanelBlockEntity> PANELS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "textures/misc/instrument.png");
    private static final RenderType SOLID = RenderType.entitySolid(WHITE),
            GHOST = RenderType.entityTranslucent(WHITE);
    private static final RenderType JACKET =
            RenderType.entityCutoutNoCull(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS);

    private record PortMesh(List<Mesh.Polygon> polygons, net.minecraft.core.Direction outward) {}

    private static final Map<DataPort, PortMesh> PORTS = new HashMap<>();
    private static RenderDetail detail = RenderDetail.NORMAL;

    private record RackCable(
            RackGeometry.Rack rack,
            RackGeometry.Lane lane,
            ConduitGeometry.Key geometry,
            boolean marked) {}

    private static final Map<RackCable, List<Mesh.Polygon>> CABLES = new HashMap<>();

    private record LeadMesh(Direction outward, Vec3 end, CableMedium medium) {}

    private static final Map<LeadMesh, List<Mesh.Polygon>> LEADS = new HashMap<>();

    private record CableMesh(
            com.wormzjl.icheme.client.model.ConduitModel.Finish finish,
            List<Mesh.Polygon> polygons) {}

    private static final Map<CableMesh, List<net.minecraft.client.renderer.block.model.BakedQuad>>
            BAKED = new HashMap<>();

    private DataControlRenderer() {}

    public static void loaded(ControlPanelBlockEntity box) {
        PANELS.add(box);
    }

    public static void unloaded(ControlPanelBlockEntity box) {
        PANELS.remove(box);
    }

    public static Map<UUID, DataPort> ports(Level level, Vec3 centre, double radius) {
        var ports = new HashMap<UUID, DataPort>();
        for (var p : InstrumentHosts.dataPorts(level, centre, radius)) ports.put(p.id(), p);
        for (var p : InstrumentHosts.powerPorts(level, centre, radius)) ports.put(p.id(), p);
        for (var panel : List.copyOf(PANELS))
            if (panel.getLevel() == level
                    && !panel.isRemoved()
                    && panel.dataPort() != null
                    && panel.dataPort().centre().distanceToSqr(centre) < radius * radius)
                ports.put(panel.dataPort().id(), panel.dataPort());
        return Map.copyOf(ports);
    }

    public static Map<UUID, DataPort> ports(
            Level level, Vec3 centre, double radius, CableMedium medium) {
        var result = new HashMap<UUID, DataPort>();
        ports(level, centre, radius)
                .forEach(
                        (id, p) -> {
                            if (p.kind().medium() == medium) result.put(id, p);
                        });
        return Map.copyOf(result);
    }

    static boolean connected(Level level, DataPort port) {
        if (!level.hasChunkAt(port.cell())) return false;
        var be = level.getBlockEntity(port.cell());
        var wires =
                be instanceof ConduitBlockEntity c
                        ? List.of(c.cable())
                        : be instanceof PipeRackBlockEntity r
                                ? r.cables()
                                : List.<DataBus.Cable>of();
        for (var c : wires) if (CableConnections.binds(c, port)) return true;
        return false;
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null
                || mc.level == null
                || mc.screen != null
                || !CableLayingClient.holding(mc.player)) return;
        var port =
                LayingToolAim.port(
                        mc,
                        ports(
                                        mc.level,
                                        mc.player.getEyePosition(),
                                        mc.player.blockInteractionRange() + 1,
                                        CableLayingClient.medium())
                                .values());
        if (port == null) return;
        String id =
                port.host() > 0
                        ? String.format(Locale.ROOT, "#%04d", port.host())
                        : "LC-" + port.id().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String text =
                port.kind().power()
                        ? net.minecraft.network.chat.Component.translatable(
                                        "gui.icheme.power."
                                                + switch (port.kind()) {
                                                    case POWER_INPUT -> "input";
                                                    case POWER_OUTPUT -> "output";
                                                    default -> "both";
                                                })
                                .getString()
                        : net.minecraft.network.chat.Component.translatable(
                                        "gui.icheme.data.port", id)
                                .getString();
        if (!port.kind().power()
                && mc.level.getBlockEntity(port.owner())
                        instanceof com.wormzjl.icheme.runtime.instrument.Instrument.Holder holder
                && !holder.instruments().sensorIds().isEmpty())
            text +=
                    " / "
                            + String.join(
                                    ", ",
                                    holder.instruments().sensorIds().values().stream()
                                            .sorted()
                                            .toList());
        var g = event.getGuiGraphics();
        var lines =
                mc.font.split(
                        net.minecraft.network.chat.Component.literal(text),
                        Math.max(32, Math.min(260, g.guiWidth() - 16)));
        int width = lines.stream().mapToInt(mc.font::width).max().orElse(0),
                x = Math.max(4, Math.min(g.guiWidth() / 2 + 14, g.guiWidth() - width - 4)),
                y = g.guiHeight() / 2 + 27;
        g.fill(x - 3, y - 2, x + width + 3, y + lines.size() * 10, 0xe0202a31);
        for (int i = 0; i < lines.size(); i++)
            g.drawString(mc.font, lines.get(i), x, y + i * 10, 0xffe9d598, false);
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        var selected = com.wormzjl.icheme.client.model.EquipmentShellModel.detail();
        if (selected != detail) {
            PORTS.clear();
            detail = selected;
        }
        var pose = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        var buffers = mc.renderBuffers().bufferSource();
        if (PORTS.size() > 2048) PORTS.clear();
        if (CABLES.size() > 2048) CABLES.clear();
        if (LEADS.size() > 2048) LEADS.clear();
        if (BAKED.size() > 2048) BAKED.clear();
        for (var port : ports(mc.level, mc.player.position(), 96).values()) {
            var mesh =
                    PORTS.computeIfAbsent(
                            port,
                            p -> {
                                var out = DataPorts.outward(mc.level, p);
                                boolean box = false;
                                if (mc.level.getBlockEntity(p.owner())
                                        instanceof EquipmentShellBlockEntity shell) {
                                    box = true;
                                    var socket =
                                            shell.shape() == null
                                                    ? null
                                                    : p.kind().power()
                                                            ? DataSocketGeometry.power(
                                                                    shell.shape())
                                                            : DataSocketGeometry.equipment(
                                                                    shell.shape());
                                    if (socket != null) {
                                        var centre =
                                                new Vec3(
                                                        shell.origin().getX() + socket.x(),
                                                        shell.origin().getY() + socket.y(),
                                                        shell.origin().getZ() + socket.z());
                                        if (centre.distanceToSqr(p.centre()) < 1e-12)
                                            box = socket.junctionBox();
                                    }
                                }
                                return new PortMesh(
                                        p.kind().power()
                                                ? PowerPortGeometry.polygons(
                                                        new double[] {0, 0, 0},
                                                        new double[] {
                                                            out.getStepX(),
                                                            out.getStepY(),
                                                            out.getStepZ()
                                                        },
                                                        box,
                                                        detail)
                                                : DataPortGeometry.polygons(
                                                        new double[] {0, 0, 0},
                                                        new double[] {
                                                            out.getStepX(),
                                                            out.getStepY(),
                                                            out.getStepZ()
                                                        },
                                                        box,
                                                        detail),
                                        out);
                            });
            draw(pose, buffers, camera, port.centre(), mesh.polygons(), 0, 255);
        }
        for (var piece : com.wormzjl.icheme.world.level.block.LinePieces.visible(mc.level))
            if (piece.port().centre().distanceToSqr(camera) < 96 * 96)
                drawLead(pose, buffers, camera, piece.port(), false);
        for (var rack : PipeRackBlockEntity.clientRacks())
            if (rack.getLevel() == mc.level
                    && rack.getBlockPos().getCenter().distanceToSqr(camera) < 96 * 96)
                for (var cable : rack.cables()) {
                    var at = rack.getBlockPos();
                    var key =
                            new RackCable(
                                    rack.rack(),
                                    rack.cableLane(cable),
                                    cable.geometry(),
                                    ConduitGeometry.markedAt(at.getX(), at.getY(), at.getZ()));
                    drawCable(
                            pose,
                            buffers,
                            camera,
                            Vec3.atLowerCornerOf(at),
                            CABLES.computeIfAbsent(
                                    key,
                                    k ->
                                            ConduitGeometry.lane(
                                                    k.rack(), k.lane(), k.geometry(), k.marked())),
                            cable.geometry().medium());
                }
        CableLayingClient.draw(pose, buffers, camera);
        buffers.endBatch(SOLID);
        buffers.endBatch(JACKET);
        buffers.endBatch(GHOST);
        buffers.endBatch(RenderType.lines());
    }

    /**
     * The ghost and the placed line include the same short connection from the socket to its route
     * entry.
     */
    public static void drawLead(
            PoseStack pose, MultiBufferSource buffers, Vec3 camera, DataPort port, boolean ghost) {
        if (port == null) return;
        var outward = port.face().getOpposite();
        var end = port.cablePoint().subtract(port.centre());
        var polygons =
                LEADS.computeIfAbsent(
                        new LeadMesh(outward, end, port.kind().medium()),
                        k ->
                                ConduitGeometry.lead(
                                        new double[] {
                                            outward.getStepX(),
                                            outward.getStepY(),
                                            outward.getStepZ()
                                        },
                                        new double[] {end.x, end.y, end.z},
                                        new double[] {
                                            outward.getStepX(),
                                            outward.getStepY(),
                                            outward.getStepZ()
                                        },
                                        k.medium()));
        if (ghost)
            draw(
                    pose,
                    buffers,
                    camera,
                    port.centre(),
                    polygons,
                    port.kind().medium().colour(),
                    135);
        else drawCable(pose, buffers, camera, port.centre(), polygons, port.kind().medium());
    }

    /**
     * Rack lanes, connector leads and chunk-baked conduit blocks use the same painted-metal finish.
     */
    private static void drawCable(
            PoseStack pose,
            MultiBufferSource buffers,
            Vec3 camera,
            Vec3 origin,
            List<Mesh.Polygon> polygons,
            CableMedium medium) {
        var finish = com.wormzjl.icheme.client.model.ConduitModel.finish(medium);
        if (finish == null) return;
        var quads =
                BAKED.computeIfAbsent(
                        new CableMesh(finish, polygons), k -> k.finish().bake(k.polygons()));
        var shift = origin.subtract(camera);
        pose.pushPose();
        pose.translate(shift.x, shift.y, shift.z);
        int light =
                LevelRenderer.getLightColor(
                        Minecraft.getInstance().level, BlockPos.containing(origin));
        var consumer = buffers.getBuffer(JACKET);
        for (var quad : quads)
            consumer.putBulkData(
                    pose.last(), quad, 1, 1, 1, 1, light, OverlayTexture.NO_OVERLAY, true);
        pose.popPose();
    }

    public static void draw(
            PoseStack pose,
            MultiBufferSource buffers,
            Vec3 camera,
            Vec3 origin,
            List<Mesh.Polygon> polygons,
            int tint,
            int alpha) {
        var consumer = buffers.getBuffer(alpha == 255 ? SOLID : GHOST);
        var shift = origin.subtract(camera);
        pose.pushPose();
        pose.translate(shift.x, shift.y, shift.z);
        int light =
                alpha == 255
                        ? LevelRenderer.getLightColor(
                                Minecraft.getInstance().level, BlockPos.containing(origin))
                        : LightTexture.FULL_BRIGHT;
        for (var q : polygons) {
            int colour =
                    tint != 0
                            ? tint
                            : switch (q.material()) {
                                case OPENING -> 0x17282d;
                                case GUARD -> CableMedium.DATA.colour();
                                case ACTUATOR -> CableMedium.POWER.colour();
                                case FLANGE -> 0x747a7d;
                                case BOLT, BOLT_FACE -> 0x555b60;
                                default -> 0xa0a6a8;
                            };
            var p = q.positions();
            var n = q.normals();
            for (int first = 1; first + 1 < q.size(); first += 2)
                for (int corner :
                        new int[] {0, first, first + 1, Math.min(first + 2, q.size() - 1)})
                    consumer.addVertex(
                                    pose.last(),
                                    (float) p[corner * 3],
                                    (float) p[corner * 3 + 1],
                                    (float) p[corner * 3 + 2])
                            .setColor(colour >> 16 & 255, colour >> 8 & 255, colour & 255, alpha)
                            .setUv(0, 0)
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(light)
                            .setNormal(
                                    pose.last(),
                                    (float) n[corner * 3],
                                    (float) n[corner * 3 + 1],
                                    (float) n[corner * 3 + 2]);
        }
        pose.popPose();
    }
}
