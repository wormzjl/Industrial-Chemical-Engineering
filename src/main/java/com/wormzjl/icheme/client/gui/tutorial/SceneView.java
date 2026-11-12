package com.wormzjl.icheme.client.gui.tutorial;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.model.EquipmentShellModel;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Projection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Render thread only (batch 2026-11-12-tutorial-system, P0): draws a film's {@link Frame} in a rectangle of a screen, as
 * {@link Projection} projects it (the render tool draws the same picture). Each actor's polygon list is one vertex buffer,
 * built once (sprites, shade and paint tint as {@link com.wormzjl.icheme.client.EquipmentBodyRenderer} bakes a body) and
 * drawn each frame; a cut's steps keep at most the current and the next buffer of an actor. All the dots are one draw of
 * small camera-facing discs in the same depth pass, so the shell hides the ones behind it. Drag turns the view, the wheel
 * zooms, a double click puts it back.
 *
 * <p>The scene's depth goes into the GUI's z: {@code z = Z0 - DEPTH * depth}, behind everything the screen draws at z 0
 * and over, within the GUI projection's range for any actor up to 60 m deep. The rectangle's depth is cleared first
 * (scissored), and every state changed is put back before the kit draws again.
 */
public final class SceneView implements AutoCloseable {
    /** The GUI z of the scene's target depth, and GUI z units a metre of depth. */
    static final float Z0 = -500, DEPTH = 15;
    /** The view's turn a pixel of drag, radians; its zoom a wheel notch; the zoom's range; a double click's longest gap, ms. */
    static final double TURN = .01, ZOOM = 1.1, MIN_ZOOM = .5, MAX_ZOOM = 4, DOUBLE_CLICK = 300;
    /** A dot's dark rim, its outer radius over the dot's, and the corners of its disc. */
    static final int RIM = 0xFF1A1A1A, CORNERS = 12;
    static final double RIM_SCALE = 1.3;

    private record Built(List<Mesh.Polygon> polygons, VertexBuffer buffer) {
    }

    private final Map<String, List<Built>> buffers = new HashMap<>();
    private TextureAtlasSprite marker;
    private double yaw, pitch, zoom = 1;
    private boolean dragging;
    private long lastClick;

    /** Draws {@code frame} in {@code area} (GUI coordinates of {@code g}'s pose), the camera's scale times {@code fit}. Draws nothing until the shell sprites exist. */
    public void draw(GuiGraphics g, Frame frame, Rect area, double fit) {
        var sprite = EquipmentShellModel.sprite(Mesh.Material.CONCRETE);
        if (sprite == null) return;
        if (sprite != marker) {
            close();
            marker = sprite;
        }
        var projection = projection(frame, area, fit);
        var m = projection.affine();
        var scene = new Matrix4f(
                (float) m[0][0], (float) m[1][0], (float) (-DEPTH * m[2][0]), 0,
                (float) m[0][1], (float) m[1][1], (float) (-DEPTH * m[2][1]), 0,
                (float) m[0][2], (float) m[1][2], (float) (-DEPTH * m[2][2]), 0,
                (float) m[0][3], (float) m[1][3], (float) (Z0 - DEPTH * m[2][3]), 1);
        var pose = new Matrix4f(g.pose().last().pose());
        var modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose).mul(scene);

        g.flush();
        // The scissor is in screen coordinates: the pose's offset added (as GuiCanvas.clip adds its origin).
        int ox = Math.round(pose.m30()), oy = Math.round(pose.m31());
        g.enableScissor(ox + area.x(), oy + area.y(), ox + area.right(), oy + area.bottom());
        RenderSystem.clearDepth(1);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        try {
            for (var actor : frame.actors()) {
                var buffer = buffer(actor);
                if (buffer == null) continue;
                // A cut actor shows its inside through the cut: both sides of its faces.
                if (actor.twoSided()) RenderSystem.disableCull();
                else RenderSystem.enableCull();
                boolean fading = actor.alpha() < 1;
                if (fading) {
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                }
                RenderSystem.setShaderColor(1, 1, 1, (float) actor.alpha());
                buffer.bind();
                buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), GameRenderer.getPositionTexColorShader());
                if (fading) RenderSystem.disableBlend();
            }
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.disableCull();
            dots(frame, projection, pose);
        } finally {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            g.disableScissor();
        }
    }

    /** The frame's projection in {@code area} with the player's turn and zoom. */
    public Projection projection(Frame frame, Rect area, double fit) {
        return Projection.of(frame.camera(), yaw, pitch, zoom, fit, area.x() + area.w() / 2.0, area.y() + area.h() / 2.0);
    }

    /** Every dot as a disc on a dark rim, facing the camera, in one draw. */
    private static void dots(Frame frame, Projection projection, Matrix4f pose) {
        if (frame.dots().isEmpty()) return;
        var builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        double ppm = projection.pixelsPerMetre();
        for (var d : frame.dots()) {
            var s = projection.screen(d.x(), d.y(), d.z());
            float z = (float) (Z0 - DEPTH * s[2]);
            double r = d.size() * ppm;
            disc(builder, pose, s[0], s[1], z, r * RIM_SCALE, RIM);
            // The colour a hair nearer than its rim.
            disc(builder, pose, s[0], s[1], z + .05f, r, 0xFF000000 | d.kind().rgb);
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private static void disc(BufferBuilder b, Matrix4f pose, double x, double y, float z, double r, int argb) {
        for (int i = 0; i < CORNERS; i++) {
            double a0 = 2 * Math.PI * i / CORNERS, a1 = 2 * Math.PI * (i + 1) / CORNERS;
            b.addVertex(pose, (float) x, (float) y, z).setColor(argb);
            b.addVertex(pose, (float) (x + r * Math.cos(a1)), (float) (y + r * Math.sin(a1)), z).setColor(argb);
            b.addVertex(pose, (float) (x + r * Math.cos(a0)), (float) (y + r * Math.sin(a0)), z).setColor(argb);
        }
    }

    /** The actor's buffer for its current polygon list: built once; an actor keeps its last two (a cut's current and next step). */
    private VertexBuffer buffer(Frame.ActorFrame actor) {
        var built = buffers.computeIfAbsent(actor.id(), k -> new ArrayList<>());
        for (var b : built) if (b.polygons() == actor.polygons()) return b.buffer();
        var buffer = upload(actor.polygons());
        if (buffer == null) return null;
        built.addFirst(new Built(actor.polygons(), buffer));
        while (built.size() > 2) built.removeLast().buffer().close();
        return buffer;
    }

    /** The polygons as triangles, as the shell model shades and tints them with the default paint; null while a sprite is missing. */
    private static VertexBuffer upload(List<Mesh.Polygon> polygons) {
        int vertices = 0;
        for (var polygon : polygons) vertices += 3 * (polygon.size() - 2);
        if (vertices == 0) return null;
        int[] tints = {Paint.tint(Paint.DEFAULT.body()), Paint.tint(Paint.DEFAULT.structure())};
        try (var bytes = new ByteBufferBuilder(vertices * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (var polygon : polygons) {
                var sprite = EquipmentShellModel.sprite(polygon.material());
                if (sprite == null) return null;
                int channel = polygon.material().channel(), tint = channel < 0 || channel >= tints.length ? 0xFFFFFF : tints[channel];
                var p = polygon.positions();
                var uv = polygon.uvs();
                for (int corner = 1; corner + 1 < polygon.size(); corner++)
                    for (int index : new int[]{0, corner, corner + 1}) {
                        int shade = EquipmentShellModel.shade(polygon, index);
                        int colour = 0xFF000000 | shade * (tint >> 16 & 255) / 255 << 16 | shade * (tint >> 8 & 255) / 255 << 8 | shade * (tint & 255) / 255;
                        builder.addVertex((float) p[3 * index], (float) p[3 * index + 1], (float) p[3 * index + 2])
                                .setUv(sprite.getU((float) uv[2 * index]), sprite.getV((float) uv[2 * index + 1])).setColor(colour);
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

    /** A press in {@code area}: a drag starts; a second press soon after the first puts the view back. */
    public boolean press(double x, double y, int button, Rect area) {
        if (button != 0 || !area.contains(x, y)) return false;
        long now = System.currentTimeMillis();
        if (now - lastClick <= DOUBLE_CLICK) {
            yaw = pitch = 0;
            zoom = 1;
            lastClick = 0;
        } else lastClick = now;
        dragging = true;
        return true;
    }

    /** A drag by (dx, dy) px turns the view while a press in it holds. */
    public boolean drag(double dx, double dy) {
        if (!dragging) return false;
        yaw -= dx * TURN;
        pitch = Math.max(-Projection.MAX_PITCH, Math.min(Projection.MAX_PITCH, pitch + dy * TURN));
        return true;
    }

    public void release() {
        dragging = false;
    }

    /** The wheel over {@code area} zooms. */
    public boolean wheel(double x, double y, double amount, Rect area) {
        if (!area.contains(x, y) || amount == 0) return false;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * Math.pow(ZOOM, amount)));
        return true;
    }

    /** Closes every buffer (the screen closed, or the sprites were baked again). */
    @Override
    public void close() {
        for (var built : buffers.values()) for (var b : built) b.buffer().close();
        buffers.clear();
        marker = null;
    }
}
