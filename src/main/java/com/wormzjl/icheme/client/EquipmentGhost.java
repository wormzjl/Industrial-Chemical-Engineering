package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.gui.screens.EquipmentConstructorScreen;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The ghosts of the vessels equipment constructors would build (owner request 2026-10-16: shown without aiming, gone
 * once built): every loaded constructor within {@link #RANGE} blocks shows its saved design until that vessel stands
 * where it would be built, and an open constructor screen shows its draft instead. The shell is drawn translucent on the
 * painted plate sprite in the design's paint (its other parts light grey); the cells the focused ghost (the screen's draft, or the aimed constructor's design) cannot take
 * are outlined in red through Catnip's outliner. Client only; the server checks again before it builds.
 * <p>The shell is drawn as translucent entity geometry on the block atlas ({@link #TYPE}), as Catnip draws its outlines:
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
    /** The painted shell sprite every ghost samples, its tint for unpainted parts, and its opacity (0-255). */
    private static final ResourceLocation PLATE = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "block/painted_shell");
    private static final int UNPAINTED = 0xC8C8C8, ALPHA = 128;
    private static final RenderType TYPE = RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);
    /** Render thread only, like everything here. */
    private static List<Ghost> shown = List.of();
    private static @Nullable EquipmentStructure.Vessel focused;
    private static List<BlockPos> blocked = List.of();
    /** The focused design's vessel already stands where it would be built, so it shows no ghost. */
    private static boolean standing;
    private static int age;
    private static final Map<EquipmentShape, ShapeRasterizer.Layout> LAYOUTS = lru();
    private static final Map<EquipmentShape, Mesh> MESHES = lru();

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
            if (screen != null && screen.vessel() != null) {
                focus = screen.vessel();
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
        shown = List.copyOf(ghosts);
        standing = focusStanding;
        if (focusStanding) focus = null;
        if (!Objects.equals(focus, focused)) {
            focused = focus;
            age = 0;
        }
        var outliner = Outliner.getInstance();
        if (focused == null) {
            blocked = List.of();
            outliner.remove(BLOCKED_SLOT);
            return;
        }
        if (age++ % RECHECK_TICKS == 0) {
            blocked = blocked(mc.level, focused);
            if (!blocked.isEmpty()) outliner.showCluster(BLOCKED_SLOT, blocked).colored(BLOCKED_COLOUR).lineWidth(1 / 16F);
        }
        if (blocked.isEmpty()) outliner.remove(BLOCKED_SLOT);
        else outliner.keep(BLOCKED_SLOT);
    }

    /** The vessel already stands there: its controller cell is a shell cell of the same vessel. */
    private static boolean built(ClientLevel level, EquipmentStructure.Vessel vessel) {
        var cell = layout(vessel.shape()).controller();
        return level.getBlockEntity(vessel.origin().offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity shell
                && vessel.shape().equals(shell.shape()) && vessel.origin().equals(shell.origin());
    }

    private static List<BlockPos> blocked(ClientLevel level, EquipmentStructure.Vessel vessel) {
        var cells = layout(vessel.shape());
        var out = new ArrayList<BlockPos>();
        for (var list : List.of(cells.shell(), cells.interior()))
            for (var cell : list) {
                var position = vessel.origin().offset(cell.x(), cell.y(), cell.z());
                if (level.isOutsideBuildHeight(position) || !level.hasChunkAt(position) || !level.getBlockState(position).canBeReplaced()) out.add(position);
            }
        return out;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || shown.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var sprite = mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(PLATE);
        var camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        var consumer = buffers.getBuffer(TYPE);
        for (var ghost : shown) {
            var vessel = ghost.vessel();
            var vertices = mesh(vessel.shape(), sprite).vertices;
            // Each channel's tint as the chunk model shows it; unpainted parts light grey.
            int[] tints = {Paint.tint(ghost.paint().body()), Paint.tint(ghost.paint().structure()), UNPAINTED};
            pose.pushPose();
            pose.translate(vessel.origin().getX() - camera.x, vessel.origin().getY() - camera.y, vessel.origin().getZ() - camera.z);
            var last = pose.last();
            for (int i = 0; i < vertices.length; i += Mesh.STRIDE) {
                int channel = (int) vertices[i + 8], tint = tints[channel < 0 ? 2 : channel];
                consumer.addVertex(last, vertices[i], vertices[i + 1], vertices[i + 2]).setColor(tint >> 16 & 0xFF, tint >> 8 & 0xFF, tint & 0xFF, ALPHA)
                        .setUv(vertices[i + 3], vertices[i + 4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(last, vertices[i + 5], vertices[i + 6], vertices[i + 7]);
            }
            pose.popPose();
        }
        buffers.endBatch(TYPE);
    }

    private static ShapeRasterizer.Layout layout(EquipmentShape shape) {
        return LAYOUTS.computeIfAbsent(shape, ShapeRasterizer::layout);
    }

    /** The shell's quads in vessel-local coordinates, rebuilt when the plate sprite changes (resource reload). */
    private static Mesh mesh(EquipmentShape shape, TextureAtlasSprite sprite) {
        var mesh = MESHES.get(shape);
        if (mesh != null && mesh.sprite == sprite) return mesh;
        var out = new ArrayList<float[]>();
        // A tower's preview is its whole facets; anything else, its cells as the chunks draw them.
        if (shape.kind().coolingTower())
            for (var polygon : ShapeRasterizer.towerPreview(shape)) quads(out, polygon, 0, 0, 0, sprite);
        else for (var cell : layout(shape).shell())
            for (var polygon : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z())))
                quads(out, polygon, cell.x(), cell.y(), cell.z(), sprite);
        var vertices = new float[out.size() * Mesh.STRIDE];
        for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, vertices, i * Mesh.STRIDE, Mesh.STRIDE);
        mesh = new Mesh(shape, sprite, vertices);
        MESHES.put(shape, mesh);
        return mesh;
    }

    /** A fan of quads, as the chunk model bakes it; a last single triangle repeats its final vertex. */
    private static void quads(List<float[]> out, com.wormzjl.icheme.science.equipment.Mesh.Polygon polygon, int x, int y, int z, TextureAtlasSprite sprite) {
        double[] p = polygon.positions(), uv = polygon.uvs(), n = polygon.normals();
        for (int first = 1; first + 1 < polygon.size(); first += 2)
            for (int corner : new int[]{0, first, first + 1, Math.min(first + 2, polygon.size() - 1)})
                out.add(new float[]{(float) (x + p[3 * corner]), (float) (y + p[3 * corner + 1]), (float) (z + p[3 * corner + 2]),
                        sprite.getU((float) uv[2 * corner]), sprite.getV((float) uv[2 * corner + 1]),
                        (float) n[3 * corner], (float) n[3 * corner + 1], (float) n[3 * corner + 2], polygon.material().channel()});
    }

    /** A ghost: the vessel a design would build, and its paint. */
    private record Ghost(EquipmentStructure.Vessel vessel, Paint paint) {
    }

    /** Position, texture coordinate, normal and paint channel ({@link com.wormzjl.icheme.science.equipment.Mesh.Material#channel}) of every quad vertex of the shell. */
    private record Mesh(EquipmentShape shape, TextureAtlasSprite sprite, float[] vertices) {
        static final int STRIDE = 9;
    }
}
