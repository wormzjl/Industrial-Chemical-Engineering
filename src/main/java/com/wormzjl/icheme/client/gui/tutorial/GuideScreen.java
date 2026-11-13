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
    protected boolean revealed;
    private boolean barDragging;
    protected int left, top;
    protected GuidePage.Page page;
    /** The page's tour, opened by the {@code ?} key. */
    private final TourHost tour = new TourHost(() -> "guide", this::anchor, () -> () -> GuideClient.report(TOURED));
    /** The step the guide's own tour completes. */
    public static final String TOURED = "welcome/help";

    /** The screen a tour's "Show me" came from, which a close returns to (null: none, the guide closes to the world). */
    private net.minecraft.client.gui.screens.@org.jetbrains.annotations.Nullable Screen parent;
    /** The step to show once the screen is laid out (P4b: a tour's "Show me"), or null. */
    private @org.jetbrains.annotations.Nullable String showing;
    /** P5: the open equipment page (an item id), or null; the page step that opened it, reported once it is read to its end. */
    protected @org.jetbrains.annotations.Nullable String pageId;
    private @org.jetbrains.annotations.Nullable String reading;
    /** P5: where Back returns to from an equipment page: the chapter, card or page the player came from. Empty: Back closes the guide. */
    private final java.util.ArrayDeque<Back> back = new java.util.ArrayDeque<>();

    /** P5: the open page's picture (its film's first stop or its body), built when the page opens, and the view that draws it. */
    private @org.jetbrains.annotations.Nullable String pictured;
    private com.wormzjl.icheme.science.tutorial.scene.@org.jetbrains.annotations.Nullable Frame picture;
    private final SceneView pictureView = new SceneView();

    private record Back(String open, @org.jetbrains.annotations.Nullable String card, @org.jetbrains.annotations.Nullable CardView cardView,
                        @org.jetbrains.annotations.Nullable String page, int offset) {
    }

    /** P5: the guide open at the equipment page {@code id} (the reference item's use, a map's symbol); its Back closes the guide. */
    public static GuideScreen page(String id) {
        var screen = new GuideScreen();
        if (GuideClient.pages().page(id) != null) {
            screen.pageId = id;
            screen.revealed = true;
        }
        return screen;
    }

    /** Opens the page {@code id}, Back returning to what is open now. */
    private void openPage(String id) {
        if (GuideClient.pages().page(id) == null) return;
        back.push(new Back(open, card, cardView, pageId, offset));
        pageId = id;
        card = null;
        cardView = null;
        offset = 0;
    }

    /** Back from a page: to where the player came from, or out of the guide where it opened on the page. */
    private void back() {
        reading = null;
        if (back.isEmpty()) {
            onClose();
            return;
        }
        var b = back.pop();
        open = b.open();
        card = b.card();
        cardView = b.cardView();
        pageId = b.page();
        offset = b.offset();
    }

    public GuideScreen() {
        super(Component.translatable(GuidePage.KEY + "title"));
    }

    /**
     * The guide open at {@code step}'s chapter with its card shown (a film's step opens its film), returning to {@code parent}
     * when it closes (batch 2026-11-12-tutorial-system, P4b: the simulator sheet tour's last stop). A step that is not in the
     * guide opens the guide as it stands.
     */
    public static GuideScreen showing(String step, net.minecraft.client.gui.screens.@org.jetbrains.annotations.Nullable Screen parent) {
        var screen = new GuideScreen();
        screen.parent = parent;
        if (guide().step(step) != null) {
            screen.open = step.substring(0, step.indexOf('/'));
            screen.showing = step;
        }
        return screen;
    }

    @Override
    public void onClose() {
        if (parent != null && minecraft != null) minecraft.setScreen(parent);
        else super.onClose();
    }

    @Override
    public void removed() {
        // The picture's buffers go with the screen; a return from a film builds them again.
        pictureView.close();
        pictured = null;
        picture = null;
        super.removed();
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
        if (showing != null) {
            String step = showing;
            showing = null;
            show(step);
        }
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
        page = GuidePage.place(new GuidePage.Input(guide(), GuideClient.progress(), open, card, com.wormzjl.icheme.science.tutorial.cards.Card.temperature(ClientConfig.temperature()))
                .withPage(GuideClient.pages(), pageId), w, h, GuiCanvas.measure(font), GuideScreen::tr, themeHint());
        offset = Math.clamp(offset, 0, page.maxOffset());
        // P5: a page step is done once its page was read to its end (at once where it needs no scrolling).
        if (reading != null && page.equipment() != null && offset >= page.maxOffset()) {
            GuideClient.report(reading);
            reading = null;
        }
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
        page.draw(canvas, ClientConfig.skin(), offset, hover, cardView == null ? Card.State.of(0, 0) : cardView.state(Card.temperature(ClientConfig.temperature()), Card.massFlow(ClientConfig.flowTime())));
        var e = page.equipment();
        if (e != null) {
            // Picture first: the page's own picture, built when the page first shows, drawn still over its frame.
            if (!e.page().id().equals(pictured)) {
                pictured = e.page().id();
                picture = com.wormzjl.icheme.science.tutorial.films.PagePicture.of(e.page());
            }
            if (picture != null) pictureView.draw(g, picture, e.picture(), FilmLayout.fit(e.picture()));
        }
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
        tour.render(g, font, width, height, mouseX, mouseY);
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
        if (tour.mouseClicked(font, width, height, mouseX, mouseY)) return true;
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        double x = mouseX - left, y = mouseY - top;
        if (page.card() != null && cardView != null && cardView.press(page.card(), x, y)) {
            // P5: a place on the card that names a page (the unit map's symbols) opens it.
            String link = cardView.hit() < 0 ? null : cardView.card().link(cardView.hit());
            if (link != null) openPage(link);
            return true;
        }
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
        if (page.equipment() != null && action.startsWith("page:")) {
            pageAction(action, page.equipment().page());
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
            pageId = null;
            reading = null;
            back.clear();
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
        if (action.equals("units:keep") || action.startsWith("read:")) {
            GuideClient.report(step);
            return;
        }
        show(step);
    }

    /** An open page's key: its film (returning here), its screen's tour's film, the next page in the unit, or Back. */
    private void pageAction(String action, com.wormzjl.icheme.science.tutorial.guide.Pages.Page p) {
        switch (action) {
            case GuidePage.PAGE_BACK -> back();
            case GuidePage.PAGE_NEXT -> {
                var next = GuideClient.pages().next(p.id());
                if (next != null) openPage(next.id());
            }
            case GuidePage.PAGE_FILM -> {
                // Watch plays on from the picture: the film opens at its first stop.
                var scene = p.film() == null ? null : Films.build(p.film());
                if (scene != null && minecraft != null) minecraft.setScreen(new FilmScreen(scene, this, () -> {
                }).fromFirstStop());
            }
            case GuidePage.PAGE_TOUR -> {
                var tour = p.tour() == null ? null : com.wormzjl.icheme.science.tutorial.guide.TourFile.load().get(p.tour());
                if (tour != null && tour.film() != null) film(tour.film());
            }
            default -> {
            }
        }
    }

    /** Plays the film {@code name}, which returns here when it closes; its body is built now, not before. */
    private void film(@org.jetbrains.annotations.Nullable String name) {
        var scene = name == null ? null : Films.build(name);
        if (scene != null && minecraft != null) minecraft.setScreen(new FilmScreen(scene, this, () -> {
        }));
    }

    /** Shows {@code step}'s film or card, as its key does. */
    private void show(String step) {
        var media = guide().step(step).media();
        switch (media) {
            case Guide.Page named -> {
                openPage(named.id());
                reading = step;
            }
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
        if (!tour.has()) return;
        card = null;
        cardView = null;
        place();
        tour.open();
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
        if (tour.keyPressed(key)) return true;
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
