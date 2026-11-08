package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wormzjl.icheme.IChemE;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outlines drawn in the world by slot (batch 2026-11-03-rotating-machinery, W6a: our own drawing, so the mod needs no
 * Create): a box, a line or a cluster of blocks, in one colour and line width. A slot shown or kept in a client tick
 * stays; a slot left alone thins out over {@link #FADE_TICKS} ticks and goes, and {@link #remove} drops it at once. The
 * look follows Catnip's outliner, which drew these before: every edge a solid square bar, full bright, on a white
 * texture as entity geometry (which an Iris shader pack keeps, see {@link EquipmentGhost}); a box sits 1/128 block
 * outside its bounds and glides half way to a moved box each tick. Render thread only.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class Outlines {
    private static final int FADE_TICKS = 8;
    private static final double INFLATE = 1 / 128D;
    private static final RenderType TYPE = RenderType.entitySolid(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "textures/misc/outline.png"));
    private static final Map<Object, Entry> SHOWN = new LinkedHashMap<>();

    private Outlines() {
    }

    /** Outlines {@code box}; a box already in {@code slot} glides to it. */
    public static void box(Object slot, AABB box, int colour, float width) {
        var entry = SHOWN.get(slot);
        if (entry != null && entry.shape instanceof Box moving) moving.target = box;
        else entry = put(slot, new Box(box));
        entry.refresh(colour, width);
    }

    public static void line(Object slot, Vec3 from, Vec3 to, int colour, float width) {
        put(slot, new Line(from, to)).refresh(colour, width);
    }

    /** Outlines the outer edges of {@code cells}: an edge that an even number of the cells share is not drawn. */
    public static void cluster(Object slot, Iterable<BlockPos> cells, int colour, float width) {
        BlockPos anchor = null;
        var edges = new HashSet<Long>();
        for (var cell : cells) {
            if (anchor == null) anchor = cell;
            int x = cell.getX() - anchor.getX(), y = cell.getY() - anchor.getY(), z = cell.getZ() - anchor.getZ();
            for (int axis = 0; axis < 3; axis++)
                for (int a = 0; a < 2; a++)
                    for (int b = 0; b < 2; b++) {
                        // The edge along axis at the corner offset (a, b) on the two other axes.
                        int[] at = {x, y, z};
                        at[(axis + 1) % 3] += a;
                        at[(axis + 2) % 3] += b;
                        long key = Cluster.key(at[0], at[1], at[2], axis);
                        if (!edges.remove(key)) edges.add(key);
                    }
        }
        if (anchor == null) {
            remove(slot);
            return;
        }
        put(slot, new Cluster(anchor, edges.stream().mapToLong(Long::longValue).toArray())).refresh(colour, width);
    }

    /** Keeps the outline in {@code slot} for this tick as it is. */
    public static void keep(Object slot) {
        var entry = SHOWN.get(slot);
        if (entry != null) entry.ticksLeft = 1;
    }

    public static void remove(Object slot) {
        SHOWN.remove(slot);
    }

    private static Entry put(Object slot, Shape shape) {
        var entry = new Entry(shape);
        SHOWN.put(slot, entry);
        return entry;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var entries = SHOWN.values().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (--entry.ticksLeft < -FADE_TICKS) entries.remove();
            else if (entry.shape instanceof Box box) {
                box.previous = box.now;
                box.now = lerp(box.now, box.target, .5);
            }
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || SHOWN.isEmpty()) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var consumer = buffers.getBuffer(TYPE);
        for (var entry : SHOWN.values()) {
            // A fading outline thins as the cube of what is left of its fade, as Catnip's did, and goes below 1/8.
            float width = entry.width;
            if (entry.ticksLeft < 0) {
                float from = entry.ticksLeft + 1 < 0 ? 1 + (entry.ticksLeft + 1) / (float) FADE_TICKS : 1;
                float left = Mth.lerp(partial, from, 1 + entry.ticksLeft / (float) FADE_TICKS);
                float alpha = left * left * left;
                if (alpha < .125F) continue;
                width *= alpha;
            }
            var bar = new Bar(pose, consumer, entry.colour, width / 2);
            switch (entry.shape) {
                case Box box -> {
                    var at = lerp(box.previous, box.now, partial).inflate(INFLATE).move(camera.scale(-1));
                    double[] lo = {at.minX, at.minY, at.minZ}, hi = {at.maxX, at.maxY, at.maxZ};
                    for (int axis = 0; axis < 3; axis++)
                        for (int a = 0; a < 2; a++)
                            for (int b = 0; b < 2; b++) {
                                double[] from = lo.clone(), to = lo.clone();
                                from[(axis + 1) % 3] = to[(axis + 1) % 3] = a == 0 ? lo[(axis + 1) % 3] : hi[(axis + 1) % 3];
                                from[(axis + 2) % 3] = to[(axis + 2) % 3] = b == 0 ? lo[(axis + 2) % 3] : hi[(axis + 2) % 3];
                                to[axis] = hi[axis];
                                bar.draw(new Vec3(from[0], from[1], from[2]), new Vec3(to[0], to[1], to[2]));
                            }
                }
                case Line line -> bar.draw(line.from.subtract(camera), line.to.subtract(camera));
                case Cluster cluster -> {
                    var origin = Vec3.atLowerCornerOf(cluster.anchor).subtract(camera);
                    for (long key : cluster.edges) {
                        int axis = Cluster.axis(key);
                        var from = origin.add(Cluster.x(key), Cluster.y(key), Cluster.z(key));
                        bar.draw(from, from.add(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0));
                    }
                }
            }
        }
        buffers.endBatch(TYPE);
    }

    private static AABB lerp(AABB from, AABB to, double t) {
        return new AABB(Mth.lerp(t, from.minX, to.minX), Mth.lerp(t, from.minY, to.minY), Mth.lerp(t, from.minZ, to.minZ),
                Mth.lerp(t, from.maxX, to.maxX), Mth.lerp(t, from.maxY, to.maxY), Mth.lerp(t, from.maxZ, to.maxZ));
    }

    /** A slot's outline: its shape, colour (RGB), full line width, and ticks until it starts to fade. */
    private static final class Entry {
        final Shape shape;
        int colour;
        float width;
        int ticksLeft = 1;

        Entry(Shape shape) {
            this.shape = shape;
        }

        void refresh(int colour, float width) {
            this.colour = colour;
            this.width = width;
            ticksLeft = 1;
        }
    }

    private sealed interface Shape permits Box, Line, Cluster {
    }

    /** A box that glides: drawn between its previous and current bounds, which move half way to the target each tick. */
    private static final class Box implements Shape {
        AABB previous, now, target;

        Box(AABB box) {
            previous = now = target = box;
        }
    }

    private record Line(Vec3 from, Vec3 to) implements Shape {
    }

    /** Block edges as packed keys: the edge's lower end relative to {@code anchor}, and its axis. */
    private record Cluster(BlockPos anchor, long[] edges) implements Shape {
        static long key(int x, int y, int z, int axis) {
            return ((long) (x & 0xFFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | ((long) (z & 0xFFFFF) << 2) | axis;
        }

        static int x(long key) {
            return (int) (key << 2 >> 44);
        }

        static int y(long key) {
            return (int) (key << 22 >> 44);
        }

        static int z(long key) {
            return (int) (key << 42 >> 44);
        }

        static int axis(long key) {
            return (int) (key & 3);
        }
    }

    /** Draws square bars {@code 2 half} wide in one colour, each running {@code half} past both of its ends. */
    private record Bar(PoseStack pose, VertexConsumer consumer, int colour, float half) {
        void draw(Vec3 from, Vec3 to) {
            var along = to.subtract(from);
            double length = along.length();
            if (length < 1e-9) return;
            var d = along.scale(1 / length);
            // u, v and d right-handed (u x v = d), so each face below winds counter-clockwise seen from outside.
            var u = d.cross(Math.abs(d.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
            var v = d.cross(u);
            var middle = from.add(to).scale(.5);
            var hu = u.scale(half);
            var hv = v.scale(half);
            var hd = d.scale(length / 2 + half);
            face(middle.add(hu), hv, hd, u);
            face(middle.subtract(hu), hd, hv, u.scale(-1));
            face(middle.add(hv), hd, hu, v);
            face(middle.subtract(hv), hu, hd, v.scale(-1));
            face(middle.add(hd), hu, hv, d);
            face(middle.subtract(hd), hv, hu, d.scale(-1));
        }

        /** A quad centred at {@code centre} with half sides {@code a} and {@code b}, where a x b points along {@code normal}. */
        private void face(Vec3 centre, Vec3 a, Vec3 b, Vec3 normal) {
            var last = pose.last();
            for (int corner = 0; corner < 4; corner++) {
                double sa = corner == 0 || corner == 3 ? -1 : 1, sb = corner < 2 ? -1 : 1;
                consumer.addVertex(last, (float) (centre.x + sa * a.x + sb * b.x), (float) (centre.y + sa * a.y + sb * b.y), (float) (centre.z + sa * a.z + sb * b.z))
                        .setColor(colour >> 16 & 0xFF, colour >> 8 & 0xFF, colour & 0xFF, 255).setUv(corner == 0 || corner == 3 ? 0 : 1, corner < 2 ? 0 : 1)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z);
            }
        }
    }
}
