package com.wormzjl.icheme.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.fx.FxGuard;
import com.wormzjl.icheme.client.fx.FxPath;
import com.wormzjl.icheme.client.fx.FxShaderProgram;
import com.wormzjl.icheme.client.fx.FxShaders;
import com.wormzjl.icheme.client.model.EquipmentShellModel;
import com.wormzjl.icheme.client.model.ReflectionClass;
import com.wormzjl.icheme.client.model.ReflectionDepth;
import com.wormzjl.icheme.client.model.ReflectionLaw;
import com.wormzjl.icheme.client.model.ReflectionMesh;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.RenderDetail;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.LoadedEquipment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Render thread only: the equipment reflection pass (owner, 2026-11-18: "Build B"; batch 2026-11-18-equipment-reflections,
 * REFLECTION_PASS_REVIEW.md). After the solid blocks, the shiny facets of every structure within
 * {@code reflection_range} ({@link ReflectionMesh}) are drawn again through {@code icheme:equipment_reflection}, which
 * keeps {@code 1 - F} of what the chunk mesh drew there and adds a grey sheen and the sun's highlight ({@link ReflectionLaw}): depth
 * test LEQUAL with no depth write, each vertex pulled towards the eye by {@link ReflectionDepth#pull}, back faces culled
 * as the shells are. As {@link EquipmentBodyRenderer}, the structures whose origins share a {@link #REGION}-block square
 * are merged into one static mesh, merged again (one a frame) when a member or its light changes, and drawn in one call
 * per square in view; squares beyond the range are released. While the pass draws, the chunk mesh bakes no sheen
 * ({@code ShellVariant.REFLECTED}).
 *
 * <p>Only without a shader pack (the pack lights the flat variant from the sprites' {@code _s} maps), with its switch on,
 * a strength above 0 and its shader loaded ({@link #active}). A failure inside the draw opens {@link #GUARD} for the
 * session (one warning), and the chunks are meshed once more with the sheen.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class EquipmentReflectionRenderer {
    /** The side of a region, blocks (as {@link EquipmentBodyRenderer}'s, so a low-detail body and its reflection share corners). */
    static final int REGION = ReflectionMesh.REGION;
    /** A region is drawn only while its largest structure covers this many pixels (RenderLod). */
    static final double LEAST_PIXELS = 8;
    /** Each structure's light is read again once a second, the structures spread over the second. */
    private static final int LIGHT_TICKS = 20;
    /** The pass's breaker: a failure turns it off for the session. */
    public static final FxGuard GUARD = FxGuard.effect("equipment_reflection");

    private record Key(EquipmentShape shape, RenderDetail detail) {}

    private record Member(BlockPos origin, EquipmentShape shape, int light) {}

    private static final class Region {
        final BlockPos corner;
        long signature;
        boolean dirty;
        List<Member> members = List.of();
        @Nullable VertexBuffer buffer;
        @Nullable AABB box;
        double size;
        int triangles;

        Region(BlockPos corner) {
            this.corner = corner;
        }

        void close() {
            if (buffer != null) buffer.close();
            buffer = null;
        }
    }

    private static final class Light {
        int value;
        long due = Long.MIN_VALUE;
    }

    /** Meshes by shape and detail; equal structures share one. Bounded: a region merge rebuilds what it misses. */
    private static final Map<Key, ReflectionMesh> MESHES = new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, ReflectionMesh> eldest) {
            return size() > 64;
        }
    };
    private static final Map<Long, Region> REGIONS = new HashMap<>();
    private static final Map<LoadedEquipment.Structure, Light> LIGHTS = new WeakHashMap<>();
    private static @Nullable RenderDetail builtDetail;
    private static long seenHash;
    private static int seenCount = -1;
    private static boolean remeshedAfterFailure;
    /** Draw calls and triangles of the last frame (F3-free counters for the review). */
    private static int draws, triangles;
    private static @Nullable RenderLevelStageEvent drawing;
    private static final Runnable DRAW = () -> draw(drawing);

    private EquipmentReflectionRenderer() {
    }

    /**
     * Whether the pass draws: its program on the SHADER path (no shader pack, the effects mode AUTO, its switch on, its
     * shader loaded), a strength above 0 and its breaker closed. Any thread: the chunk meshers read it for the shell look.
     */
    public static boolean active() {
        return !GUARD.disabled() && ClientConfig.reflectionStrength() > 0 && FxShaders.path(FxShaderProgram.REFLECTION) == FxPath.SHADER;
    }

    /** Draw calls and triangles the pass made in the last frame it drew. */
    public static int[] lastFrame() {
        return new int[]{draws, triangles};
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        if (GUARD.disabled()) {
            // The pass broke this session: mesh the chunks once more, with the sheen, and give its buffers back.
            if (!remeshedAfterFailure) {
                remeshedAfterFailure = true;
                release();
                var renderer = Minecraft.getInstance().levelRenderer;
                if (renderer != null && EquipmentShellModel.variantChanged()) renderer.allChanged();
            }
            return;
        }
        if (!active()) {
            if (!REGIONS.isEmpty()) release();
            return;
        }
        drawing = event;
        try {
            GUARD.run(DRAW);
        } finally {
            drawing = null;
        }
    }

    private static void draw(@Nullable RenderLevelStageEvent event) {
        draws = triangles = 0;
        var mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (event == null || level == null) return;
        var shader = FxShaders.reflection();
        if (shader == null) return;
        var detail = EquipmentShellModel.detail();
        if (detail != builtDetail) {
            release();
            builtDetail = detail;
        }
        var camera = event.getCamera().getPosition();
        int range = ClientConfig.reflectionRange();
        double keep = range + 1.5 * REGION;
        long tick = level.getGameTime();

        // Members within the range (and half a region more), each with its light on its timer; a hash of what the regions
        // are built from, so a frame like the last allocates nothing.
        var structures = EquipmentShellBlockEntity.clientStructures(level);
        long hash = System.identityHashCode(level) * 31L + range;
        int count = 0;
        for (var structure : structures) {
            var shape = structure.shape();
            var origin = structure.origin();
            if (shape == null || !near(origin, shape, camera.x, camera.y, camera.z, keep)) continue;
            var light = LIGHTS.computeIfAbsent(structure, s -> new Light());
            if (tick >= light.due || light.due - tick > 2L * LIGHT_TICKS) {
                light.value = level.getBrightness(LightLayer.SKY, BlockPos.containing(origin.getX() + shape.sizeX() / 2.0, origin.getY() + shape.sizeY(), origin.getZ() + shape.sizeZ() / 2.0));
                light.due = tick + LIGHT_TICKS + Math.floorMod(origin.hashCode(), LIGHT_TICKS);
            }
            hash = ((hash * 31 + origin.asLong()) * 31 + System.identityHashCode(shape)) * 31 + light.value;
            count++;
        }
        if (hash != seenHash || count != seenCount) {
            seenHash = hash;
            seenCount = count;
            regions(structures, camera.x, camera.y, camera.z, keep);
        }

        // Merge at most one changed region this frame, the nearest in view first.
        var frustum = event.getFrustum();
        Region next = null;
        double best = Double.MAX_VALUE;
        for (var region : REGIONS.values()) {
            if (!region.dirty) continue;
            double d = distanceSqr(region.corner, camera.x, camera.z) - (region.box != null && frustum.isVisible(region.box) ? 1e9 : 0);
            if (d < best) {
                best = d;
                next = region;
            }
        }
        if (next != null) merge(next, level, detail);

        var view = ClientConfig.lodView(event.getProjectionMatrix().m11(), mc.getWindow().getHeight());
        boolean set = false;
        try {
            for (var region : REGIONS.values()) {
                var box = region.box;
                if (region.buffer == null || box == null || !frustum.isVisible(box)) continue;
                double dx = Math.max(Math.max(box.minX - camera.x, 0), camera.x - box.maxX), dy = Math.max(Math.max(box.minY - camera.y, 0), camera.y - box.maxY),
                        dz = Math.max(Math.max(box.minZ - camera.z, 0), camera.z - box.maxZ);
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > (double) range * range || view.pixels(region.size, d2) < LEAST_PIXELS) continue;
                if (!set) {
                    set = true;
                    setUp(shader, event, level, range);
                }
                var origin = shader.getUniform("Origin");
                var c = region.corner;
                if (origin != null) {
                    origin.set((float) (c.getX() - camera.x), (float) (c.getY() - camera.y), (float) (c.getZ() - camera.z));
                    origin.upload();
                }
                region.buffer.bind();
                region.buffer.draw();
                draws++;
                triangles += region.triangles;
            }
        } finally {
            if (set) {
                shader.clear();
                VertexBuffer.unbind();
                RenderSystem.depthMask(true);
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            }
        }
    }

    /**
     * The render state and the frame's uniforms: the base kept by 1 - alpha (the mirrored share) plus the reflection, the
     * framebuffer's alpha untouched; LEQUAL without depth write, culled; the sky, the light, the pull, the fade.
     */
    private static void setUp(ShaderInstance shader, RenderLevelStageEvent event, ClientLevel level, int range) {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(515);
        RenderSystem.depthMask(false);
        RenderSystem.enableCull();
        var mc = Minecraft.getInstance();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        FxShaders.defaultUniforms(shader, event.getModelViewMatrix(), event.getProjectionMatrix(), mc.getWindow());
        float[] fog = RenderSystem.getShaderFogColor();
        double[] light = ReflectionLaw.celestial(level.getSunAngle(partial), level.getRainLevel(partial));
        set(shader, "Horizon", fog[0], fog[1], fog[2]);
        set(shader, "Light", (float) light[0], (float) light[1], (float) light[2], (float) light[3]);
        set(shader, "HighlightSky", (float) ReflectionLaw.HIGHLIGHT_SKY_FROM, (float) ReflectionLaw.HIGHLIGHT_SKY_TO);
        set(shader, "Strength", (float) ClientConfig.reflectionStrength());
        set(shader, "Fade", (float) (range * (1 - ReflectionLaw.FADE)), (float) range);
        set(shader, "Pull", (float) ReflectionDepth.POSITION, (float) ReflectionDepth.SNAP, (float) ReflectionDepth.QUANTUM, (float) ReflectionDepth.TAN_CAP);
        shader.apply();
    }

    private static void set(ShaderInstance shader, String name, float... values) {
        var uniform = shader.getUniform(name);
        if (uniform == null) return;
        switch (values.length) {
            case 1 -> uniform.set(values[0]);
            case 2 -> uniform.set(values[0], values[1]);
            case 3 -> uniform.set(values[0], values[1], values[2]);
            default -> uniform.set(values[0], values[1], values[2], values[3]);
        }
    }

    private static boolean near(BlockPos origin, EquipmentShape shape, double x, double y, double z, double reach) {
        double dx = Math.max(Math.max(origin.getX() - x, 0), x - origin.getX() - shape.sizeX());
        double dy = Math.max(Math.max(origin.getY() - y, 0), y - origin.getY() - shape.sizeY());
        double dz = Math.max(Math.max(origin.getZ() - z, 0), z - origin.getZ() - shape.sizeZ());
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private static double distanceSqr(BlockPos corner, double x, double z) {
        double dx = corner.getX() + REGION / 2.0 - x, dz = corner.getZ() + REGION / 2.0 - z;
        return dx * dx + dz * dz;
    }

    /** This frame's members of each region; a region whose signature changed is marked to merge, one with none left is released. */
    private static void regions(java.util.Collection<LoadedEquipment.Structure> structures, double x, double y, double z, double keep) {
        var now = new HashMap<Long, List<Member>>();
        for (var structure : structures) {
            var shape = structure.shape();
            var origin = structure.origin();
            if (shape == null || !near(origin, shape, x, y, z, keep)) continue;
            var light = LIGHTS.get(structure);
            long id = ReflectionMesh.region(origin.getX(), origin.getZ());
            now.computeIfAbsent(id, k -> new ArrayList<>()).add(new Member(origin, shape, light == null ? 15 : light.value));
        }
        var gone = REGIONS.entrySet().iterator();
        while (gone.hasNext()) {
            var e = gone.next();
            if (!now.containsKey(e.getKey())) {
                e.getValue().close();
                gone.remove();
            }
        }
        for (var e : now.entrySet()) {
            long id = e.getKey();
            var region = REGIONS.get(id);
            if (region == null) {
                region = new Region(new BlockPos((int) (id >> 32) * REGION, 0, (int) id * REGION));
                region.dirty = true;
                REGIONS.put(id, region);
            }
            long signature = signature(e.getValue());
            if (region.dirty || signature != region.signature) {
                region.signature = signature;
                region.members = e.getValue();
                region.dirty = true;
            }
        }
    }

    private static long signature(List<Member> members) {
        long h = members.size();
        for (var m : members)
            h = ((h * 31 + m.origin().asLong()) * 31 + System.identityHashCode(m.shape())) * 31 + m.light();
        return h;
    }

    /**
     * One region's mesh from its members' meshes, offset from the region's corner: each vertex's sky light (read at the
     * block just outside the surface, 0 to 15) in Color's alpha, F0 in UV0, the highlight's power and strength x 100 in
     * UV2, the smooth normal in Normal.
     */
    private static void merge(Region region, ClientLevel level, RenderDetail detail) {
        region.dirty = false;
        var meshes = new ArrayList<ReflectionMesh>(region.members.size());
        int vertices = 0;
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE, size = 0;
        for (var m : region.members) {
            var key = new Key(m.shape(), detail);
            var mesh = MESHES.get(key);
            if (mesh == null) {
                mesh = ReflectionMesh.of(m.shape(), detail);
                MESHES.put(key, mesh);
            }
            meshes.add(mesh);
            vertices += mesh.vertices();
            var o = m.origin();
            var s = m.shape();
            x0 = Math.min(x0, o.getX());
            y0 = Math.min(y0, o.getY());
            z0 = Math.min(z0, o.getZ());
            x1 = Math.max(x1, o.getX() + s.sizeX());
            y1 = Math.max(y1, o.getY() + s.sizeY());
            z1 = Math.max(z1, o.getZ() + s.sizeZ());
            size = Math.max(size, Math.max(s.sizeX(), Math.max(s.sizeY(), s.sizeZ())));
        }
        region.close();
        region.triangles = vertices / 3;
        if (vertices == 0) return;
        var c = region.corner;
        var classes = ReflectionClass.values();
        var skyLights = new HashMap<Long, Integer>();
        var cursor = new BlockPos.MutableBlockPos();
        try (var bytes = new ByteBufferBuilder(vertices * DefaultVertexFormat.BLOCK.getVertexSize())) {
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
            for (int i = 0; i < meshes.size(); i++) {
                var mesh = meshes.get(i);
                var o = region.members.get(i).origin();
                float dx = o.getX() - c.getX(), dy = o.getY() - c.getY(), dz = o.getZ() - c.getZ();
                float[] p = mesh.positions(), n = mesh.normals();
                for (int v = 0; v < mesh.vertices(); v++) {
                    var type = classes[mesh.classes()[v]];
                    cursor.set(Math.floor(o.getX() + p[3 * v] + .5 * n[3 * v]), Math.floor(o.getY() + p[3 * v + 1] + .5 * n[3 * v + 1]), Math.floor(o.getZ() + p[3 * v + 2] + .5 * n[3 * v + 2]));
                    int sky = skyLights.computeIfAbsent(cursor.asLong(), k -> level.getBrightness(LightLayer.SKY, cursor));
                    builder.addVertex(p[3 * v] + dx, p[3 * v + 1] + dy, p[3 * v + 2] + dz)
                            .setColor(255, 255, 255, sky * 17)
                            .setUv((float) type.f0, 0)
                            .setUv2((int) type.exponent, (int) Math.round(type.strength * 100))
                            .setNormal(n[3 * v], n[3 * v + 1], n[3 * v + 2]);
                }
            }
            var built = builder.build();
            if (built == null) return;
            var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(built);
            VertexBuffer.unbind();
            region.buffer = buffer;
            region.box = new AABB(x0, y0, z0, x1, y1, z1);
            region.size = size;
        }
    }

    /** Gives every buffer back (logout, a resource reload's new detail, the pass turned off). */
    public static void release() {
        REGIONS.values().forEach(Region::close);
        REGIONS.clear();
        MESHES.clear();
        seenCount = -1;
    }
}
