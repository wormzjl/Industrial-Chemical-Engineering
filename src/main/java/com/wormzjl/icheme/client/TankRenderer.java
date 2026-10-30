package com.wormzjl.icheme.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.model.EquipmentShellModel;
import com.wormzjl.icheme.runtime.fluid.TankDisplay;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.TankGaugeGeometry;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.FloatingRoofs;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A floating-roof tank's client-drawn parts (batch 2026-10-21-floating-roof-tanks, plan section 3.4, owner decisions FR4
 * and FR7): the roof floats with its underside on the liquid ({@link ShapeRasterizer.Floating#underside}), the rolling
 * ladder turns down until its foot rests on the roof's deck ({@link ShapeRasterizer.Floating#pitch}), and the sight glass
 * shows the free water and the hydrocarbon liquid over it as two bands in their colours. The level and the colours are the
 * tank's display ({@link #accept}), which the server sends on its own schedule to a client that watches the tank:
 * once a second this watches the tanks whose node cells stand within {@link #WATCH_RANGE}, nearest first, and releases the
 * others. A tank with no display yet shows its roof landed on its legs and an empty glass.
 *
 * <p>The roof and the ladder are drawn as {@link MachineAnimation} draws moving parts: uploaded to the GPU once per tank
 * shape, part, paint and light, then one matrix and one draw call each a frame. The glass's bands are drawn translucent
 * on the block atlas, as {@link EquipmentGhost} draws, so that a shader pack keeps them.</p>
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class TankRenderer {
    /** Watched within this, blocks: inside the server's {@link FluidNetwork#WATCH_RANGE}, so a watch it keeps is never one the server has dropped. */
    static final double WATCH_RANGE = FluidNetwork.WATCH_RANGE - 8;
    /** Tanks are drawn while their node cell stands within this of the camera, blocks. */
    private static final double DRAW_RANGE = 192;
    private static final int WATCH_TICKS = 20;
    private static final int MAX_BUFFERS = 64;
    /**
     * The sight glass's bands (owner report 2026-10-21: the gauge was hard to pick out): their half width round the column
     * (shared with the instrument's chamber and scale), the least opacity a clear liquid shows with,
     * and the float's slim red collar at the liquid's surface. Above the liquid the window stays clear.
     */
    private static final double BAND = TankGaugeGeometry.BAND_HALF, MARKER = TankGaugeGeometry.MARKER_HEIGHT;
    private static final int LEAST_ALPHA = 200, FLOAT = 0xFFB84432;
    private static final Map<Key, VertexBuffer> BUFFERS = new HashMap<>();
    private static @Nullable TextureAtlasSprite builtWith;
    private static int age;

    private record Key(EquipmentShape shape, boolean roof, Paint paint, int light) {
    }

    private TankRenderer() {
    }

    /** Each tick the player rides the roof under it ({@link FloatingRoofs#carry(net.minecraft.world.entity.player.Player)}); once a second: watch the tanks within range, nearest first, and release the rest. */
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) return;
        FloatingRoofs.carry(mc.player);
        if (age++ % WATCH_TICKS != 0) return;
        var player = mc.player.position();
        var near = new ArrayList<BlockPos>();
        for (var cell : EquipmentShellBlockEntity.clientTankCells())
            if (!cell.isRemoved() && cell.getLevel() == mc.level && cell.getBlockPos().distToCenterSqr(player) <= WATCH_RANGE * WATCH_RANGE) near.add(cell.getBlockPos());
        near.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(player)));
        track(near);
    }

    /** The roof and the ladder, solid, where the tank's liquid puts them. */
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        var level = Minecraft.getInstance().level;
        var marker = EquipmentShellModel.sprite(Mesh.Material.PIPE);
        if (level == null || marker == null) return;
        var cells = EquipmentShellBlockEntity.clientTankCells();
        if (cells.isEmpty()) return;
        if (marker != builtWith) {
            release();
            builtWith = marker;
        }
        var camera = event.getCamera().getPosition();
        double ticks = level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var frustum = event.getFrustum();
        var type = RenderType.solid();
        ShaderInstance shader = null;
        VertexBuffer bound = null;
        for (var cell : cells) {
            var shape = cell.shape();
            var origin = cell.origin();
            if (cell.isRemoved() || cell.getLevel() != level || shape == null || origin == null) continue;
            if (cell.getBlockPos().distToCenterSqr(camera) > DRAW_RANGE * DRAW_RANGE) continue;
            if (!frustum.isVisible(new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + shape.sizeX(), origin.getY() + shape.sizeY(), origin.getZ() + shape.sizeZ())))
                continue;
            var f = ShapeRasterizer.floating(shape);
            if (f == null) continue;
            double liquid = level(cell.getBlockPos(), ticks);
            double lift = f.underside(liquid) - f.landed(), pitch = f.pitch(liquid);
            if (shader == null) {
                type.setupRenderState();
                shader = RenderSystem.getShader();
                if (shader == null) {
                    type.clearRenderState();
                    return;
                }
                shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, event.getModelViewMatrix(), event.getProjectionMatrix(), Minecraft.getInstance().getWindow());
                if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0F, 0F, 0F);
                shader.apply();
            }
            if (shader.MODEL_VIEW_MATRIX == null) continue;
            double ox = origin.getX() - camera.x, oy = origin.getY() - camera.y, oz = origin.getZ() - camera.z;
            // The roof: lifted from where it lies landed, lit at its deck's middle.
            int roofLight = LevelRenderer.getLightColor(level, BlockPos.containing(origin.getX() + shape.sizeX() / 2.0, origin.getY() + f.underside(liquid) + f.deck() + .5, origin.getZ() + shape.sizeZ() / 2.0));
            var pose = new Matrix4f(event.getModelViewMatrix()).translate((float) ox, (float) (oy + lift), (float) oz);
            bound = draw(shader, buffer(new Key(shape, true, cell.paint(), roofLight), f), pose, bound);
            // The ladder: turned down about its axis at its pivot, its foot towards the axis going down.
            double[] p = f.pivot(), a = f.axis(), along = f.along();
            double down = a[2] * along[0] - a[0] * along[2] > 0 ? -1 : 1;
            int ladderLight = LevelRenderer.getLightColor(level, BlockPos.containing(origin.getX() + p[0], origin.getY() + p[1], origin.getZ() + p[2]));
            pose = new Matrix4f(event.getModelViewMatrix()).translate((float) (ox + p[0]), (float) (oy + p[1]), (float) (oz + p[2]))
                    .rotate((float) (down * pitch), (float) a[0], (float) a[1], (float) a[2]);
            bound = draw(shader, buffer(new Key(shape, false, cell.paint(), ladderLight), f), pose, bound);
        }
        if (shader != null) {
            shader.clear();
            VertexBuffer.unbind();
            type.clearRenderState();
        }
    }

    /** The sight glass's bands, translucent: free water from the glass's foot, the hydrocarbon liquid over it to the level. */
    @SubscribeEvent
    public static void renderGlass(RenderLevelStageEvent event) {
        // Draw the contents before the chunk's translucent sleeve writes depth in front of them.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) return;
        var cells = EquipmentShellBlockEntity.clientTankCells();
        if (cells.isEmpty()) return;
        var sprite = LiquidRenderer.sprite();
        float u = sprite.getU(.5F), v = sprite.getV(.5F);
        var camera = event.getCamera().getPosition();
        double ticks = level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = null;
        for (var cell : cells) {
            var shape = cell.shape();
            var origin = cell.origin();
            var display = get(cell.getBlockPos(), ticks);
            if (display == null || cell.isRemoved() || cell.getLevel() != level || shape == null || origin == null) continue;
            if (cell.getBlockPos().distToCenterSqr(camera) > DRAW_RANGE * DRAW_RANGE) continue;
            var f = ShapeRasterizer.floating(shape);
            if (f == null) continue;
            double low = f.glassLow(), high = f.glassHigh(), water = Math.min(display.waterLevel(), high), oil = Math.min(display.level(), high);
            double[] g = f.glass();
            if (!event.getFrustum().isVisible(new AABB(origin.getX() + g[0] - BAND, origin.getY() + low, origin.getZ() + g[1] - BAND, origin.getX() + g[0] + BAND, origin.getY() + high, origin.getZ() + g[1] + BAND)))
                continue;
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(origin.getX() + g[0], origin.getY() + (low + high) / 2, origin.getZ() + g[1]));
            if (consumer == null) consumer = buffers.getBuffer(LiquidRenderer.TYPE);
            pose.pushPose();
            pose.translate(origin.getX() + g[0] - camera.x, origin.getY() - camera.y, origin.getZ() + g[1] - camera.z);
            var last = pose.last();
            if (water > low) band(consumer, last, low, water, display.waterArgb(), light, u, v);
            double surface = Math.max(water, oil);
            if (oil > Math.max(low, water)) band(consumer, last, Math.max(low, water), oil, display.oilArgb(), light, u, v);
            double ring = Math.max(low, Math.min(high, surface));
            column(consumer, last, Math.max(low, ring - MARKER / 2), Math.min(high, ring + MARKER / 2), FLOAT, light, u, v,
                    TankGaugeGeometry.MARKER_HALF, TankGaugeGeometry.markerFaces());
            pose.popPose();
        }
        if (consumer != null) buffers.endBatch(LiquidRenderer.TYPE);
    }

    /** The liquid's depth over the tank's floor at game time {@code ticks}, m, where the level's roofs stand ({@link FloatingRoofs}): the tank's glide, or the level its node cell saved before the first one. */
    private static double level(BlockPos node, double ticks) {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : FloatingRoofs.of(level).level(node, ticks);
    }

    /** One band: a closed square column round the glass, wound outwards on every side and both ends. */
    private static void band(VertexConsumer consumer, com.mojang.blaze3d.vertex.PoseStack.Pose last, double y0, double y1, int argb, int light, float u, float v) {
        column(consumer, last, y0, y1, argb, light, u, v, BAND, TankGaugeGeometry.bandFaces());
    }

    /** One coloured column or hollow collar from cached faces, with half width {@code half}. */
    private static void column(VertexConsumer consumer, com.mojang.blaze3d.vertex.PoseStack.Pose last, double y0, double y1, int argb, int light, float u, float v,
                               double half, java.util.List<Mesh.Polygon> faces) {
        if (y1 <= y0) return;
        int tint = (argb & 0xFFFFFF) | (Math.max(argb >>> 24, LEAST_ALPHA) << 24);
        for (var face : faces) {
            var p = face.positions();var n = face.normals();
            for (int i = 0; i < p.length; i += 3)
                LiquidRenderer.vertex(consumer, last, (float) (half * (2 * p[i] - 1)), (float) (y0 + (y1 - y0) * p[i + 1]), (float) (half * (2 * p[i + 2] - 1)),
                        tint, u, v, light, (float) n[i], (float) n[i + 1], (float) n[i + 2]);
        }
    }

    private static VertexBuffer draw(ShaderInstance shader, @Nullable VertexBuffer buffer, Matrix4f pose, @Nullable VertexBuffer bound) {
        if (buffer == null || shader.MODEL_VIEW_MATRIX == null) return bound;
        shader.MODEL_VIEW_MATRIX.set(pose);
        shader.MODEL_VIEW_MATRIX.upload();
        if (buffer != bound) buffer.bind();
        buffer.draw();
        return buffer;
    }

    private static @Nullable VertexBuffer buffer(Key key, ShapeRasterizer.Floating f) {
        var buffer = BUFFERS.get(key);
        if (buffer != null) return buffer;
        if (BUFFERS.size() >= MAX_BUFFERS) release();
        buffer = build(key.roof() ? f.roof() : f.ladder(), key.paint(), key.light());
        if (buffer != null) BUFFERS.put(key, buffer);
        return buffer;
    }

    /**
     * A part's triangles as the shell model bakes them: the directional shade of their smooth normals, a painted material
     * tinted by its channel of the tank's paint as the chunk model tints it, each material's sprite, one light.
     */
    private static @Nullable VertexBuffer build(java.util.List<Mesh.Polygon> polygons, Paint paint, int light) {
        int vertices = 0;
        for (var polygon : polygons) vertices += 3 * (polygon.size() - 2);
        int[] tints = {Paint.tint(paint.body()), Paint.tint(paint.structure())};
        try (var bytes = new ByteBufferBuilder(Math.max(256, vertices * DefaultVertexFormat.BLOCK.getVertexSize()))) {
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
            for (var polygon : polygons) {
                var sprite = EquipmentShellModel.sprite(polygon.material());
                if (sprite == null) return null;
                int channel = polygon.material().channel(), tint = channel < 0 || channel >= tints.length ? 0xFFFFFF : tints[channel];
                double[] p = polygon.positions(), uv = polygon.uvs(), n = polygon.normals();
                for (int k = 1; k + 1 < polygon.size(); k++)
                    for (int c : new int[]{0, k, k + 1}) {
                        int shade = EquipmentShellModel.shade(polygon, c);
                        builder.addVertex((float) p[3 * c], (float) p[3 * c + 1], (float) p[3 * c + 2])
                                .setColor(shade * (tint >> 16 & 255) / 255, shade * (tint >> 8 & 255) / 255, shade * (tint & 255) / 255, 255)
                                .setUv(sprite.getU((float) uv[2 * c]), sprite.getV((float) uv[2 * c + 1]))
                                .setLight(light)
                                .setNormal((float) n[3 * c], (float) n[3 * c + 1], (float) n[3 * c + 2]);
                    }
            }
            var mesh = builder.build();
            if (mesh == null) return null;
            var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
            VertexBuffer.unbind();
            return buffer;
        }
    }

    private static void release() {
        BUFFERS.values().forEach(VertexBuffer::close);
        BUFFERS.clear();
    }

    // ---- the client's open-tank displays ----
    // By node cell: what the server sent for each tank this client watches (owner decision FR4 R-B). track keeps the watch
    // set to the tanks within WATCH_RANGE, nearest first, at most FluidNetwork.MAXIMUM_WATCHED, and asks the server only for
    // the difference. A new display arrives once a presentation bucket inside the glide the server gave the tank
    // (TankDisplay.Glide, owner requests 2026-10-21: the roof moves, and a player on it rides it): the roof moves from where
    // it stood to it over that time on the game clock, here as on the server, and the level's roofs (FloatingRoofs) collide
    // by it. The server sends nothing more. Plain Java on the client's main thread.
    private static final Map<BlockPos,TankDisplay.Glide> DISPLAYS=new HashMap<>();
    private static final Set<BlockPos> WATCHED=new HashSet<>();

    /** A glide the server sent; dropped if the tank is no longer watched. */
    public static void accept(BlockPos node,TankDisplay.Glide glide) {
        if(!WATCHED.contains(node))return;
        DISPLAYS.put(node.immutable(),glide);
        var level=Minecraft.getInstance().level;if(level!=null)FloatingRoofs.of(level).glide(node,glide);
    }
    /** The display of the tank at {@code node} at game time {@code ticks} (with the frame's partial tick), or null before the first one arrives. */
    static TankDisplay get(BlockPos node,double ticks){var glide=DISPLAYS.get(node);return glide==null?null:glide.at(ticks);}
    /**
     * Watches exactly {@code inRange} (node cells within range, nearest first; the tail beyond the maximum is not
     * watched): asks the server for the new ones and releases the others.
     */
    static void track(List<BlockPos> inRange) {
        var wanted=new LinkedHashSet<BlockPos>();
        for(var pos:inRange){if(wanted.size()>=FluidNetwork.MAXIMUM_WATCHED)break;wanted.add(pos.immutable());}
        var watch=new ArrayList<BlockPos>();for(var pos:wanted)if(!WATCHED.contains(pos))watch.add(pos);
        var unwatch=new ArrayList<BlockPos>();for(var pos:WATCHED)if(!wanted.contains(pos))unwatch.add(pos);
        if(watch.isEmpty()&&unwatch.isEmpty())return;
        WATCHED.removeAll(unwatch);unwatch.forEach(DISPLAYS::remove);WATCHED.addAll(watch);
        var level=Minecraft.getInstance().level;if(level!=null)for(var pos:unwatch)FloatingRoofs.of(level).glide(pos,null);
        FluidNetwork.requestTanks(watch,unwatch);
    }
    /** Forgets every display (the client left the world). */
    public static void clear(){DISPLAYS.clear();WATCHED.clear();}
}
