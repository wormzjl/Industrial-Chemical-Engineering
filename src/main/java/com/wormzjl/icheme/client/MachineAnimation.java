package com.wormzjl.icheme.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.model.EquipmentShellModel;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.ShaftGeometry;
import com.wormzjl.icheme.science.equipment.VisualRotation;
import com.wormzjl.icheme.world.level.block.ShaftBlock;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Units' moving parts (owner requests 2026-10-16, "add a working animation for AC (can be switched on and off) and check
 * FPS, try to be FPS efficient", and 2026-10-18, "give compressor and pump working animations"): air cooler fans, pump
 * shafts and motor fans turn, compressor piston rods slide ({@link ProcessUnit.Motion}). The shell model leaves the moving
 * parts out of the chunk mesh ({@link EquipmentShellModel}) and this draws each part within {@link #RANGE} blocks and in
 * view once a frame, turned about its axis or slid along it, or at rest where it stands still. A part is uploaded to the GPU
 * once per unit, part and light; the shader is set up once a frame, and each part then costs a light lookup, its matrix
 * and one draw call, whatever its triangles (measured 2026-10-16 on a 6 × 4 air cooler bank in view: 48 turning fans
 * cost about 0.03 ms a frame against still ones). Shafted units follow their published operating rpm; authored fans
 * and slow machinery retain their positional phase. The switch on a moving unit's datasheet freezes every unit's parts (owner
 * report 2026-10-26: it re-meshed every chunk while it moved the parts in and out of the mesh); it is not saved.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class MachineAnimation {
    /** How far from the camera parts move, blocks: beyond it a part is a few pixels and not drawn at all. */
    public static final double RANGE = 96;
    /** Part buffers kept, by unit, part and light; all are dropped when it fills. */
    private static final int MAX_BUFFERS = 256;
    /** Client-owned clocks for fans and authored slow machinery, using the shaft animations' angle accumulator. */
    private static final Map<EquipmentShellBlockEntity, Map<Integer, VisualRotation.Clock>> CLOCKS = new WeakHashMap<>();
    private static boolean running = true;
    private static final Map<Key, VertexBuffer> BUFFERS = new HashMap<>();
    private static final Map<ShaftLook, List<Mesh.Polygon>> SHAFT_POLYGONS = new HashMap<>();
    /** A sprite the buffers hold texture coordinates of: a resource reload makes them stale. */
    private static @Nullable TextureAtlasSprite builtWith;

    private record Key(Object geometry, int part, int light) {
    }

    private record ShaftLook(int axis, int pixels) {
    }

    private MachineAnimation() {
    }

    /** Whether the visual clocks advance; off, each part retains its current pose. */
    public static boolean running() {
        return running;
    }

    /** The datasheet's switch: parts stop or move again. The chunk mesh never holds them, so no chunk is meshed again. */
    public static void toggle() {
        running = !running;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        var level = Minecraft.getInstance().level;
        var marker = EquipmentShellModel.sprite(Mesh.Material.PIPE);
        if (level == null || marker == null) return;
        var cells = EquipmentShellBlockEntity.clientMovingCells();
        if (cells.isEmpty() && RotationAnimation.shafts().isEmpty()) return;
        if (marker != builtWith) {
            release();
            builtWith = marker;
        }
        var camera = event.getCamera().getPosition();
        var frustum = event.getFrustum();
        double rotationSeconds = (level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0;
        try (var draws = new Draws(event)) {
            for (var cell : cells) {
                var shape = cell.shape();
                var origin = cell.origin();
                if (cell.isRemoved() || cell.getLevel() != level || shape == null || origin == null) continue;
                boolean liveRotation = RotationAnimation.live(shape);
                boolean moving = running && MoverSounds.running(cell);
                double speedScale = moving ? 1 : 0;
                if(shape.kind()==EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER){
                    var node=ShapeRasterizer.fluidCell(shape);
                    double ticks=level.getGameTime()+event.getPartialTick().getGameTimeDeltaPartialTick(false);
                    var display=TankRenderer.get(origin.offset(node.x(),node.y(),node.z()),ticks);
                    speedScale=moving&&display!=null?display.fanSpeed():0;
                }
                var parts = ShapeRasterizer.moving(shape);
                double unitPhase = Math.floorMod(31L * origin.getX() + 7L * origin.getY() + origin.getZ(), 97) / 97.0;
                for (int index : cell.moving()) {
                    if (index >= parts.size()) continue;
                    var part = parts.get(index);
                    var motion = part.motion();
                    double[] pivot = motion.pivot(), axis = motion.axis();
                    double px = origin.getX() + pivot[0], py = origin.getY() + pivot[1], pz = origin.getZ() + pivot[2];
                    double dx = px - camera.x, dy = py - camera.y, dz = pz - camera.z, reach = part.reach() + 1.2 * motion.crank();
                    if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) continue;
                    if (!frustum.isVisible(new AABB(px - reach, py - reach, pz - reach, px + reach, py + reach, pz + reach))) continue;
                    double turns = liveRotation ? RotationAnimation.turns(shape, origin, motion, rotationSeconds) + motion.phase()
                            : CLOCKS.computeIfAbsent(cell, c -> new HashMap<>()).computeIfAbsent(index, i -> new VisualRotation.Clock())
                                    .advance(rotationSeconds, motion.speed() * speedScale) + motion.phase() + unitPhase;
                    var pose = new Matrix4f(event.getModelViewMatrix()).translate((float) dx, (float) dy, (float) dz);
                    if (motion.slides()) {
                        double d = motion.offset(turns);
                        pose.translate((float) (axis[0] * d), (float) (axis[1] * d), (float) (axis[2] * d));
                    } else {
                        // Shaft blocks rotate about the positive world axis; heading must not reverse a coupled machine.
                        double direction = liveRotation ? Math.signum(axis[0] + axis[1] + axis[2]) : 1;
                        pose.rotate((float) (2 * Math.PI * (turns - Math.floor(turns)) * direction), (float) axis[0], (float) axis[1], (float) axis[2]);
                    }
                    draws.draw(new Key(shape, index, LevelRenderer.getLightColor(level, BlockPos.containing(px, py, pz))), part.polygons(), pose);
                }
            }
            for (var position : RotationAnimation.shafts()) {
                if (position.distToCenterSqr(camera) > RANGE * RANGE || !level.hasChunkAt(position) || !frustum.isVisible(new AABB(position))) continue;
                var state = level.getBlockState(position);
                if (!(state.getBlock() instanceof ShaftBlock)) continue;
                int axis = state.getValue(ShaftBlock.AXIS).ordinal();
                var look = new ShaftLook(axis, state.getValue(ShaftBlock.SIZE));
                var polygons = SHAFT_POLYGONS.computeIfAbsent(look, MachineAnimation::shaftPolygons);
                var pose = new Matrix4f(event.getModelViewMatrix()).translate((float) (position.getX() + .5 - camera.x),
                        (float) (position.getY() + .5 - camera.y), (float) (position.getZ() + .5 - camera.z));
                pose.rotate((float) (2 * Math.PI * RotationAnimation.turns(position, rotationSeconds)), axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
                draws.draw(new Key(look, 0, LevelRenderer.getLightColor(level, position)), polygons, pose);
            }
        }
    }

    private static List<Mesh.Polygon> shaftPolygons(ShaftLook look) {
        return ShaftGeometry.polygons(look.axis(), look.pixels()).stream().map(polygon -> {
            var positions = polygon.positions().clone();
            for (int i = 0; i < positions.length; i++) positions[i] -= .5;
            return new Mesh.Polygon(positions, polygon.uvs(), polygon.normals(), polygon.material());
        }).toList();
    }

    /** One shader setup and the same cached GPU path for machine parts and loose shafts. Render-thread owned. */
    private static final class Draws implements AutoCloseable {
        private final RenderLevelStageEvent event;
        private final RenderType type = RenderType.solid();
        private ShaderInstance shader;
        private VertexBuffer bound;
        private boolean failed;

        private Draws(RenderLevelStageEvent event) {
            this.event = event;
        }

        void draw(Key key, List<Mesh.Polygon> polygons, Matrix4f pose) {
            if (failed) return;
            if (shader == null) {
                type.setupRenderState();
                shader = RenderSystem.getShader();
                if (shader == null) {
                    type.clearRenderState();
                    failed = true;
                    return;
                }
                shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, event.getModelViewMatrix(), event.getProjectionMatrix(), Minecraft.getInstance().getWindow());
                if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0F, 0F, 0F);
                shader.apply();
            }
            int cached = BUFFERS.size();
            var buffer = buffer(key, polygons);
            if (BUFFERS.size() != cached) bound = null;
            if (buffer == null || shader.MODEL_VIEW_MATRIX == null) return;
            shader.MODEL_VIEW_MATRIX.set(pose);
            shader.MODEL_VIEW_MATRIX.upload();
            if (buffer != bound) {
                buffer.bind();
                bound = buffer;
            }
            buffer.draw();
        }

        @Override
        public void close() {
            if (shader != null) {
                shader.clear();
                VertexBuffer.unbind();
                type.clearRenderState();
            }
        }
    }

    private static @Nullable VertexBuffer buffer(Key key, List<Mesh.Polygon> polygons) {
        var buffer = BUFFERS.get(key);
        if (buffer != null) return buffer;
        if (BUFFERS.size() >= MAX_BUFFERS) release();
        buffer = build(polygons, key.light());
        if (buffer != null) BUFFERS.put(key, buffer);
        return buffer;
    }

    /**
     * A part's triangles as the shell model bakes them ({@link EquipmentShellModel#shade}: the directional shade of their
     * smooth normals and the sheen in the vertex colour), each material's sprite, one light for the whole part, its
     * pivot's cell's. Moving parts are of unpainted materials only, so no tint is needed.
     */
    private static @Nullable VertexBuffer build(List<Mesh.Polygon> polygons, int light) {
        int vertices = 0;
        for (var polygon : polygons) vertices += 3 * (polygon.size() - 2);
        try (var bytes = new ByteBufferBuilder(Math.max(256, vertices * DefaultVertexFormat.BLOCK.getVertexSize()))) {
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
            for (var polygon : polygons) {
                var sprite = EquipmentShellModel.sprite(polygon.material());
                if (sprite == null) return null;
                double[] p = polygon.positions(), uv = polygon.uvs(), n = polygon.normals();
                for (int k = 1; k + 1 < polygon.size(); k++)
                    for (int c : new int[]{0, k, k + 1}) {
                        int shade = EquipmentShellModel.shade(polygon, c);
                        builder.addVertex((float) p[3 * c], (float) p[3 * c + 1], (float) p[3 * c + 2])
                                .setColor(shade, shade, shade, 255)
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

}
