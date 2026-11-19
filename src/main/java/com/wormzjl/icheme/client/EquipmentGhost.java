package com.wormzjl.icheme.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.gui.screens.EquipmentConstructorScreen;
import com.wormzjl.icheme.client.model.EquipmentShellModel;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh.Polygon;
import com.wormzjl.icheme.science.equipment.RenderDetail;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ghosts of the vessels equipment constructors would build (owner request 2026-10-16: shown without aiming, gone
 * once built): every loaded constructor within {@link #RANGE} blocks shows its saved design until that vessel stands
 * where it would be built, and an open constructor screen shows its draft instead. The shell is drawn translucent on the
 * painted plate sprite in the design's paint (its other parts light grey); the cells any visible ghost cannot take
 * stay outlined in red ({@link Outlines}), without aiming at its constructor. Client only; the server checks again before it builds.
 * <p>The shell is drawn as translucent entity geometry on the block atlas ({@link #TYPE}), as {@link Outlines} draws its outlines:
 * the chunk type {@code RenderType.translucent()} drawn from a level-stage event is lost under an Iris shader pack, whose
 * terrain programs only take chunk geometry (owner report 2026-10-17, the preview vanished with shaders).
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class EquipmentGhost {
    private static final Object BLOCKED_SLOT = "icheme:equipment_ghost_blocked";
    /** Blocks around the ghost change while it is shown: check its cells again twice a second. */
    private static final int RECHECK_TICKS = 10;
    private static final int BLOCKED_COLOUR = 0xFF5555;
    /** Constructors farther than this from the player show no ghost, blocks. */
    private static final double RANGE = 48;
    /** Layouts and meshes kept for the shapes shown; a ghost's shape changes only when the player edits it. */
    private static final int CACHE = 16;
    /** Vessel-local position, atlas UV, normal and paint channel for each quad vertex. */
    private static final int STRIDE = 9;
    /** The painted shell sprite every ghost samples, its tint for unpainted parts, and its opacity (0-255). */
    private static final ResourceLocation PLATE = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "block/painted_shell");
    private static final int UNPAINTED = 0xC8C8C8, ALPHA = 128;
    private static final RenderType TYPE = RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);
    /** Render thread only, like everything here. */
    private static List<Ghost> shown = List.of();
    /** Collision results belong to visible previews, independently of the screen/crosshair's focus. */
    private static Map<EquipmentStructure.Vessel, List<BlockPos>> collisions = Map.of();
    private static Set<BlockPos> outlined = Set.of();
    private static @Nullable ClientLevel checkedLevel;
    private static List<BlockPos> blocked = List.of();
    /** The focused design's vessel already stands where it would be built, so it shows no ghost. */
    private static boolean standing;
    private static int age;
    private static final Map<EquipmentShape, Footprint> LAYOUTS = lru();
    private static final Map<EquipmentShape, float[]> MESHES = lru();

    private static final Batch BATCH = new Batch();
    private static TextureAtlasSprite plate;
    private static RenderDetail detail;

    private EquipmentGhost() {
    }

    private static <V> Map<EquipmentShape, V> lru() {
        return new LinkedHashMap<>(CACHE, .75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<EquipmentShape, V> eldest) {
                return size() > CACHE;
            }
        };
    }

    /** Cells of the focused vessel that are occupied, unloaded or outside the world, as last checked. */
    public static int blockedCount() {
        return blocked.size();
    }

    public static boolean standing() {
        return standing;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        var ghosts = new ArrayList<Ghost>();
        EquipmentStructure.Vessel focus = null;
        boolean focusStanding = false;
        if (mc.level != null && mc.player != null) {
            var screen = mc.screen instanceof EquipmentConstructorScreen open ? open : null;
            var aimed = mc.screen == null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
            if (screen != null) focus = screen.vessel();
            if (focus != null) {
                focusStanding = built(mc.level, focus);
                if (!focusStanding) ghosts.add(new Ghost(focus, screen.design().paint()));
            }
            for (var constructor : EquipmentConstructorBlockEntity.clientLoaded()) {
                var at = constructor.getBlockPos();
                if (constructor.isRemoved() || constructor.getLevel() != mc.level || screen != null && at.equals(screen.position())) continue;
                if (at.distToCenterSqr(mc.player.position()) > RANGE * RANGE) continue;
                var vessel = constructor.vessel(constructor.design());
                boolean stands = built(mc.level, vessel);
                if (at.equals(aimed)) {
                    focus = vessel;
                    focusStanding = stands;
                }
                if (!stands) ghosts.add(new Ghost(vessel, constructor.design().paint()));
            }
        }
        if (mc.level != checkedLevel || ghosts.isEmpty()) BATCH.release();
        if (!ghosts.equals(shown)) shown = List.copyOf(ghosts);
        standing = focusStanding;
        if (focusStanding) focus = null;

        var visible = new LinkedHashSet<EquipmentStructure.Vessel>();
        for (var ghost : shown) visible.add(ghost.vessel());
        if (++age >= RECHECK_TICKS || checkedLevel != mc.level || !visible.equals(collisions.keySet())) {
            age = 0;
            checkedLevel = mc.level;
            var next = new LinkedHashMap<EquipmentStructure.Vessel, List<BlockPos>>();
            var allBlocked = new LinkedHashSet<BlockPos>();
            if (mc.level != null) for (var vessel : visible) {
                var cells = List.copyOf(blocked(mc.level, vessel));
                next.put(vessel, cells);
                allBlocked.addAll(cells);
            }
            collisions = Map.copyOf(next);
            if (!allBlocked.equals(outlined)) {
                outlined = Set.copyOf(allBlocked);
                // A cell shared by previews must be passed once: duplicate cells cancel a cluster's edges.
                if (outlined.isEmpty()) Outlines.remove(BLOCKED_SLOT);
                else Outlines.cluster(BLOCKED_SLOT, allBlocked, BLOCKED_COLOUR, 1 / 16F);
            }
        }
        blocked = focus == null ? List.of() : collisions.getOrDefault(focus, List.of());
        if (outlined.isEmpty()) Outlines.remove(BLOCKED_SLOT);
        else Outlines.keep(BLOCKED_SLOT);
    }

    /** The vessel already stands there: its controller cell is a shell cell of the same vessel. */
    private static boolean built(ClientLevel level, EquipmentStructure.Vessel vessel) {
        var cell = layout(vessel.shape()).controller();
        return level.getBlockEntity(vessel.origin().offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity shell
                && vessel.shape().equals(shell.shape()) && vessel.origin().equals(shell.origin());
    }

    private static List<BlockPos> blocked(ClientLevel level, EquipmentStructure.Vessel vessel) {
        var cells = layout(vessel.shape()).cells();
        var out = new ArrayList<BlockPos>();
        var position = new BlockPos.MutableBlockPos();
        for (var list : List.of(cells.shell(), cells.interior()))
            for (var cell : list) {
                position.setWithOffset(vessel.origin(), cell.x(), cell.y(), cell.z());
                if (level.isOutsideBuildHeight(position) || !level.hasChunkAt(position) || !level.getBlockState(position).canBeReplaced()) out.add(position.immutable());
            }
        return out;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || shown.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var sprite = mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(PLATE);
        var selected = EquipmentShellModel.detail();
        if (sprite != plate || selected != detail) {
            MESHES.clear();
            BATCH.release();
            plate = sprite;
            detail = selected;
        }
        var visible = new ArrayList<Ghost>();
        for (var ghost : shown) {
            if (event.getFrustum().isVisible(ghost.bounds())) visible.add(ghost);
        }
        if (!visible.isEmpty()) BATCH.draw(visible, event);
    }

    /** One sorted batch across all visible ghosts, including previews that overlap. Keep vertices camera-relative
     * because the entity shader also uses their positions for fog. A moving camera takes the normal re-sort path. */
    private static final class Batch {
        private VertexBuffer buffer;
        private ByteBufferBuilder scratch;
        private List<Ghost> ghosts = List.of();
        private Vec3 camera;
        private VertexSorting sorting;
        private final Matrix4f position = new Matrix4f();
        private final Matrix3f normal = new Matrix3f();
        private boolean drawable;

        void draw(List<Ghost> visible, RenderLevelStageEvent event) {
            var eye = event.getCamera().getPosition();
            var pose = event.getPoseStack();
            var sorter = RenderSystem.getVertexSorting();
            if (buffer == null) {
                buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                scratch = new ByteBufferBuilder(TYPE.bufferSize());
            }
            buffer.bind();
            try {
                if (!eye.equals(camera) || !visible.equals(ghosts) || sorter != sorting
                        || !position.equals(pose.last().pose()) || !normal.equals(pose.last().normal())) {
                    drawable = upload(visible, eye, pose, sorter);
                    ghosts = List.copyOf(visible);
                    camera = eye;
                    sorting = sorter;
                    position.set(pose.last().pose());
                    normal.set(pose.last().normal());
                }
                if (drawable) {
                    TYPE.setupRenderState();
                    try {
                        buffer.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                    } finally {
                        TYPE.clearRenderState();
                    }
                }
            } finally {
                VertexBuffer.unbind();
            }
        }

        private boolean upload(List<Ghost> visible, Vec3 eye, PoseStack pose, VertexSorting sorter) {
            var builder = new BufferBuilder(scratch, TYPE.mode(), TYPE.format());
            for (var ghost : visible) write(builder, ghost, eye, pose);
            try (var data = builder.build()) {
                if (data == null) return false;
                data.sortQuads(scratch, sorter);
                buffer.upload(data);
                return true;
            }
        }

        void release() {
            if (buffer == null) return;
            buffer.close();
            scratch.close();
            buffer = null;
            scratch = null;
            ghosts = List.of();
            camera = null;
        }
    }

    private static void write(BufferBuilder builder, Ghost ghost, Vec3 camera, PoseStack pose) {
        var vessel = ghost.vessel();
        var vertices = MESHES.computeIfAbsent(vessel.shape(), EquipmentGhost::mesh);
        int[] colours = {ALPHA << 24 | Paint.tint(ghost.paint().body()), ALPHA << 24 | Paint.tint(ghost.paint().structure()),
                ALPHA << 24 | UNPAINTED};
        pose.pushPose();
        try {
            pose.translate(vessel.origin().getX() - camera.x, vessel.origin().getY() - camera.y, vessel.origin().getZ() - camera.z);
            var last = pose.last();
            var point = new Vector3f();
            var normal = new Vector3f();
            for (int i = 0; i < vertices.length; i += STRIDE) {
                last.pose().transformPosition(vertices[i], vertices[i + 1], vertices[i + 2], point);
                last.transformNormal(vertices[i + 5], vertices[i + 6], vertices[i + 7], normal);
                int channel = (int) vertices[i + 8];
                builder.addVertex(point.x, point.y, point.z, colours[channel < 0 ? 2 : channel], vertices[i + 3], vertices[i + 4],
                        OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, normal.x, normal.y, normal.z);
            }
        } finally {
            pose.popPose();
        }
    }

    private record Footprint(ShapeRasterizer.Layout cells, ShapeRasterizer.Cell controller, AABB bounds) {}

    private static Footprint layout(EquipmentShape shape) {
        return LAYOUTS.computeIfAbsent(shape, key -> {
            var cells = ShapeRasterizer.layout(key);
            return new Footprint(cells, cells.controller(), new AABB(0, 0, 0, key.sizeX(), key.sizeY(), key.sizeZ()));
        });
    }

    private static float[] mesh(EquipmentShape shape) {
        var out = new FloatArrayList();
        if (detail == RenderDetail.LOW) {
            for (var polygon : ShapeRasterizer.renderPreview(shape, detail)) quads(out, polygon, 0, 0, 0);
        } else if (shape.kind().coolingTower()) {
            for (var polygon : ShapeRasterizer.towerPreview(shape)) quads(out, polygon, 0, 0, 0);
        } else {
            for (var cell : layout(shape).cells().shell())
                for (var polygon : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z())))
                    quads(out, polygon, cell.x(), cell.y(), cell.z());
        }
        return out.toFloatArray();
    }

    /** A fan of quads, as the chunk model bakes it; a last single triangle repeats its final vertex. */
    private static void quads(FloatArrayList out, Polygon polygon, int x, int y, int z) {
        double[] p = polygon.positions(), uv = polygon.uvs(), n = polygon.normals();
        for (int first = 1; first + 1 < polygon.size(); first += 2) {
            for (int k = 0; k < 4; k++) {
                int corner = k == 0 ? 0 : Math.min(first + k - 1, polygon.size() - 1);
                out.add((float) (x + p[3 * corner]));
                out.add((float) (y + p[3 * corner + 1]));
                out.add((float) (z + p[3 * corner + 2]));
                out.add(plate.getU((float) uv[2 * corner]));
                out.add(plate.getV((float) uv[2 * corner + 1]));
                out.add((float) n[3 * corner]);
                out.add((float) n[3 * corner + 1]);
                out.add((float) n[3 * corner + 2]);
                out.add(polygon.material().channel());
            }
        }
    }

    private record Ghost(EquipmentStructure.Vessel vessel, Paint paint, AABB bounds) {
        Ghost(EquipmentStructure.Vessel vessel, Paint paint) {
            this(vessel, paint, layout(vessel.shape()).bounds().move(vessel.origin()));
        }
    }
}
