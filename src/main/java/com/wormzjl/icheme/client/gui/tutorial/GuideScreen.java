package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.science.display.DisplayUnit;
import com.wormzjl.icheme.science.tutorial.cards.Card;
import com.wormzjl.icheme.science.tutorial.cards.Cards;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.guide.Guide;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;

/**
 * The field guide's screen (batch 2026-11-12-tutorial-system, P1; client only, no menu): {@link GuidePage} drawn through
 * the kit in the client's theme, at the player's GUI scale (designed at 464 by 254, checked at 320 by 240). A chapter key
 * opens its steps; Watch opens the step's film, which returns here when it closes; Open shows the step's card in place of
 * the steps, with Back; the unit choices set the client's display units. A CLIENT step reports itself with
 * {@code StepDone} and shows done at once; the server's progress replaces the local set when it arrives. A term's hover
 * shows its definition; the {@code ?} key starts the page's tour; Escape closes. Nothing here computes process state.
 */
public class GuideScreen extends Screen {
    /** The step the guide's first opening completes; the units step's; the card's; the film's; the tour's. */
    public static final String OPENED = "welcome/guide";
    protected String open;
    protected String card;
    protected CardView cardView;
    protected int offset;
    private boolean revealed, barDragging;
    protected int left, top;
    protected GuidePage.Page page;
    /** The page's tour, opened by the {@code ?} key. */
    private final HelpTour tour = new HelpTour();
    /** The step the guide's own tour completes. */
    public static final String TOURED = "welcome/help";

    public GuideScreen() {
        super(Component.translatable(GuidePage.KEY + "title"));
    }

    protected static String tr(String key) {
        return Component.translatable(key).getString();
    }

    protected static Guide guide() {
        return GuideClient.guide();
    }

    private static String themeHint() {
        return tr("gui.icheme.fluid.theme." + (ClientConfig.theme() == ClientConfig.Theme.DARK ? "dark" : "light")) + "\n" + tr("gui.icheme.fluid.theme.hint");
    }

    @Override
    protected void init() {
        GuideClient.report(OPENED);
        if (open == null) {
            var current = GuideClient.progress().current(guide());
            open = current == null ? guide().chapters().getFirst().id() : current.substring(0, current.indexOf('/'));
        }
        place();
        if (!revealed) {
            revealed = true;
            var step = page.anchors().get("step");
            if (step != null) offset = page.reveal(step, offset);
        }
    }

    /** The page as the guide, the progress and the open chapter or card stand now. */
    protected void place() {
        int w = Math.min(GuidePage.DESIGN_WIDTH, width - 8), h = Math.min(GuidePage.DESIGN_HEIGHT, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        page = GuidePage.place(new GuidePage.Input(guide(), GuideClient.progress(), open, card), w, h, GuiCanvas.measure(font), GuideScreen::tr, themeHint());
        offset = Math.clamp(offset, 0, page.maxOffset());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // The server's progress may have arrived: the page follows it.
        place();
        if (cardView != null) cardView.advance(System.nanoTime());
        double x = mouseX - left, y = mouseY - top;
        String hover = hoverAction(x, y);
        var canvas = new GuiCanvas(g, font, left, top);
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        page.draw(canvas, ClientConfig.skin(), offset, hover, cardView == null ? Card.State.of(0, 0) : cardView.state(Card.temperature(ClientConfig.temperature())));
        g.pose().popPose();
        renderOver(g, mouseX, mouseY);
        String hint = tour.isOpen() ? null : hintAt(x, y);
        if (hint != null) {
            var lines = new ArrayList<FormattedCharSequence>();
            for (var line : hint.split("\\n")) lines.addAll(font.split(Component.literal(line), Math.max(160, width / 3)));
            g.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    /** What a subclass draws over the page (the tour); here nothing. */
    protected void renderOver(GuiGraphics g, int mouseX, int mouseY) {
        if (tour.isOpen()) tour.render(g, font, width, height, mouseX, mouseY);
    }

    /** The action the pointer stands on, for the keys' hover light. */
    protected String hoverAction(double x, double y) {
        if (page.card() != null && page.card().card().control() != Card.Control.NONE && page.card().knob(cardView == null ? 0 : cardView.control()).contains(x, y)) return "card:slider";
        return page.action(x, y, offset);
    }

    /** The hover text at panel point ({@code x}, {@code y}). */
    protected String hintAt(double x, double y) {
        return page.hover(x, y, offset);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tour.isOpen() && tour.mouseClicked(font, width, height, mouseX, mouseY)) return true;
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        double x = mouseX - left, y = mouseY - top;
        if (page.card() != null && cardView != null && cardView.press(page.card(), x, y)) return true;
        if (page.bar() && page.barTrack().contains(x, y)) {
            barDragging = true;
            barTo(y);
            return true;
        }
        var action = page.action(x, y, offset);
        if (action == null) return super.mouseClicked(mouseX, mouseY, button);
        act(action);
        return true;
    }

    private void barTo(double y) {
        var track = page.barTrack();
        offset = Math.clamp((int) Math.round((y - track.y()) / Math.max(1, track.h()) * (page.content())), 0, page.maxOffset());
    }

    /** Carries out a page action. */
    protected void act(String action) {
        if (action.equals("help")) {
            help();
            return;
        }
        if (action.equals("theme")) {
            ClientConfig.toggleTheme();
            return;
        }
        if (action.equals("card:back")) {
            card = null;
            cardView = null;
            return;
        }
        if (action.startsWith(GuidePage.CardPlace.OPTION)) {
            if (cardView != null) cardView.option(Integer.parseInt(action.substring(GuidePage.CardPlace.OPTION.length())));
            return;
        }
        if (action.startsWith("chapter:")) {
            open = action.substring("chapter:".length());
            card = null;
            cardView = null;
            offset = 0;
            return;
        }
        String step = stepOf(action);
        if (step == null) return;
        if (action.equals("units:use")) {
            ClientConfig.temperature(DisplayUnit.TemperatureUnit.CELSIUS);
            ClientConfig.pressure(DisplayUnit.PressureUnit.BAR);
            ClientConfig.flowTime(DisplayUnit.FlowRateUnit.PER_HOUR);
            ClientConfig.basis(ClientConfig.Basis.MASS);
            GuideClient.report(step);
            return;
        }
        if (action.equals("units:keep")) {
            GuideClient.report(step);
            return;
        }
        var media = guide().step(step).media();
        switch (media) {
            case Guide.Film film -> {
                var scene = Films.build(film.name());
                if (scene != null && minecraft != null) minecraft.setScreen(new FilmScreen(scene, this, () -> GuideClient.report(step)));
            }
            case Guide.Card named -> {
                var built = Cards.build(named.name());
                if (built == null) return;
                card = step;
                cardView = new CardView(built, () -> GuideClient.report(step));
            }
            default -> {
            }
        }
    }

    /** The step a step key belongs to, by the row that holds it. */
    private String stepOf(String action) {
        for (var row : page.rows()) for (var key : row.keys()) if (key.key().equals(action)) return row.step();
        return null;
    }

    /** The {@code ?} key: the page's tour, over the steps (an open card closes first); finishing it completes {@link #TOURED}. */
    protected void help() {
        var guideTour = HelpTour.of("guide");
        if (guideTour == null) return;
        card = null;
        cardView = null;
        place();
        tour.open(guideTour, this::anchor, () -> GuideClient.report(TOURED));
    }

    /** An anchor of the page on the screen; a step's scrolls into view first where {@code reveal}. */
    private com.wormzjl.icheme.client.gui.common.kit.Rect anchor(String key, boolean reveal) {
        var r = page.anchors().get(key);
        if (r == null) return null;
        if (page.scrolls(key)) {
            if (reveal) offset = page.reveal(r, offset);
            r = page.onPanel(r, offset);
        }
        return r.moved(left, top);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (tour.isOpen()) return tour.keyPressed(key);
        // Left and Right step through the open card's places, or its slider.
        if (page.card() != null && cardView != null && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)) {
            cardView.step(key == GLFW.GLFW_KEY_LEFT ? -1 : 1, page.card());
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (barDragging) {
            barTo(mouseY - top);
            return true;
        }
        if (page.card() != null && cardView != null && cardView.drag(page.card(), mouseX - left)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        barDragging = false;
        if (cardView != null) cardView.release();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (tour.isOpen()) return true;
        if (page.card() == null && page.view().contains(mouseX - left, mouseY - top)) {
            offset = Math.clamp(offset - (int) Math.round(vertical * ScrollView.STEP), 0, page.maxOffset());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }
}
