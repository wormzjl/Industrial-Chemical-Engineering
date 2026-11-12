package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A film on its own screen (batch 2026-11-12-tutorial-system, P0; client only, no menu, nothing sent to the server): the
 * title, the scene on the HMI glass, the current caption under it, Back, Replay and Next, one pip a stop. The film plays
 * to its next stop and waits there; Next goes on (while it plays, Next jumps to the stop), and after the last stop Next
 * closes. Back shows the stop before, Replay plays the way to this stop again. Escape closes. The player's GUI scale is
 * kept; the layout is {@link FilmLayout}'s.
 */
public final class FilmScreen extends Screen {
    private static final String KEY = "tutorial.icheme.film.";
    /** The longest step the film's clock takes between two frames, s: a stalled frame does not jump the film. */
    private static final double MAX_STEP = .1;
    private final Scene scene;
    private final SceneView view = new SceneView();
    /** The film's time, s, and the stop it plays toward. */
    private double time;
    private int target;
    private long last = -1;
    private int left, top;
    private FilmLayout layout;
    private Rect glass;
    private SkinButton back, replay, next;

    public FilmScreen(Scene scene) {
        super(Component.translatable(scene.title()));
        this.scene = scene;
    }

    private static Skin skin() {
        return ClientConfig.skin();
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private double stop(int index) {
        return scene.stops().get(index);
    }

    /** The time the way to stop {@code index} starts from. */
    private double start(int index) {
        return index == 0 ? 0 : stop(index - 1);
    }

    private boolean waiting() {
        return time >= stop(target);
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(FilmLayout.DESIGN_WIDTH, width - 8), panelHeight = Math.min(FilmLayout.DESIGN_HEIGHT, height - 8);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        var captions = new ArrayList<String>();
        for (var key : scene.captionKeys()) captions.add(tr(key));
        layout = FilmLayout.of(panelWidth, panelHeight, scene.stops().size(), FilmLayout.captionLines(captions, panelWidth, GuiCanvas.measure(font)));
        glass = FilmLayout.glass(skin(), layout.scene());
        var b = layout.buttons();
        back = button(b.get(0), tr(KEY + "back"), this::back);
        replay = button(b.get(1), tr(KEY + "replay"), this::replay);
        next = button(b.get(2), tr(KEY + "next"), this::next);
        next.primary(true);
        update();
    }

    private SkinButton button(Rect at, String text, Runnable action) {
        return addRenderableWidget(new SkinButton(left + at.x(), top + at.y(), at.w(), at.h(), Component.literal(text), p -> {
            action.run();
            update();
        }, FilmScreen::skin));
    }

    private void back() {
        if (target == 0) return;
        target--;
        time = stop(target);
    }

    private void replay() {
        time = start(target);
        last = -1;
    }

    private void next() {
        if (!waiting()) time = stop(target);
        else if (target < scene.stops().size() - 1) target++;
        else onClose();
    }

    /** The buttons as the film stands: Back from the second stop on, Next reads Close at the last stop once it is there. */
    private void update() {
        if (back == null) return;
        back.active = target > 0;
        boolean end = target == scene.stops().size() - 1 && waiting();
        next.setMessage(Component.literal(tr(KEY + (end ? "close" : "next"))));
    }

    @Override
    public void tick() {
        super.tick();
        update();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        var canvas = new GuiCanvas(g, font, left, top);
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        skin().page(canvas, new Rect(0, 0, layout.width(), layout.height()));
        glass = skin().well(canvas, layout.scene());
        g.pose().popPose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        if (last >= 0 && !waiting()) time = Math.min(stop(target), time + Math.min(MAX_STEP, (now - last) / 1e9));
        last = now;
        super.render(g, mouseX, mouseY, partialTick);
        var frame = scene.at(time);
        var skin = skin();
        var canvas = new GuiCanvas(g, font, left, top);
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        skin.title(canvas, 10, 10, title.getString());
        var pips = layout.pips();
        for (int i = 0; i < pips.size(); i++) {
            var p = pips.get(i);
            if (i <= frame.stop()) canvas.fill(p.x(), p.y(), p.right(), p.bottom(), skin.tone(Palette.ACCENT));
            else canvas.outline(p.x(), p.y(), p.w(), p.h(), skin.tone(Palette.MUTED));
        }
        view.draw(g, frame, glass, FilmLayout.fit(glass));
        var caption = layout.caption();
        List<String> lines = new ArrayList<>();
        for (var key : frame.captions()) lines.addAll(canvas.measure().wrap(tr(key), caption.w()));
        for (int i = 0; i < lines.size(); i++) skin.text(canvas, lines.get(i), caption.x(), caption.y() + i * 10, Palette.TEXT);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return super.mouseClicked(x, y, button) || view.press(x - left, y - top, button, glass);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return view.drag(dx, dy) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        view.release();
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return view.wheel(x - left, y - top, vertical, glass) || super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public void removed() {
        view.close();
        super.removed();
    }
}
