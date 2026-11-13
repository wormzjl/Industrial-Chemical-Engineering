package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.network.TutorialNetwork;
import com.wormzjl.icheme.science.tutorial.guide.Guide;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.Progress;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Set;

/**
 * The field guide on the client (batch 2026-11-12-tutorial-system, P1): the guide read once from the mod's assets, and the
 * player's progress. The server's {@link TutorialNetwork.Progress} is the truth and replaces the local set when it arrives;
 * a CLIENT step this client reports is shown done at once ({@link #report}). Nothing here computes process state or runs
 * on the tick loop.
 */
public final class GuideClient {
    private static Guide guide;
    private static Progress progress = Progress.NONE;
    /** Counts the resource reloads: the guide's, its pages' and its tours' caches, and every laid-out page, drop at each. */
    private static int generation;

    private GuideClient() {
    }

    public static Guide guide() {
        if (guide == null) guide = GuideFile.load();
        return guide;
    }

    private static com.wormzjl.icheme.science.tutorial.guide.Pages pages;

    /** P5: the equipment pages, read once from the mod's assets. */
    public static com.wormzjl.icheme.science.tutorial.guide.Pages pages() {
        if (pages == null) pages = com.wormzjl.icheme.science.tutorial.guide.PageFile.load(name -> HelpTour.of(name) != null);
        return pages;
    }

    /** P5: opens the guide at the equipment page {@code id} (an item id: the reference item's use); its Back closes the guide. */
    public static void openPage(String id) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(GuideScreen.page(id)));
    }

    /**
     * A resource reload (a language change is one): the guide, its pages and its tours are read again when next asked, and
     * every cached layout keyed on {@link #generation} is laid out again (code review finding 4).
     */
    public static void reloaded() {
        guide = null;
        pages = null;
        HelpTour.forget();
        generation++;
    }

    /** The reload count the guide's cached layouts are keyed on. */
    public static int generation() {
        return generation;
    }

    /** The language the client shows, for the cached layouts' keys. */
    public static String language() {
        return net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected();
    }

    public static Progress progress() {
        return progress;
    }

    /** The server's answer: the steps that player has done. */
    public static void progress(Set<String> steps) {
        progress = new Progress(steps);
    }

    /** A CLIENT step done here: shown done at once and reported; the server's next progress confirms it. */
    public static void report(String stepId) {
        var step = guide().step(stepId);
        if (step == null || step.check() != Guide.Check.CLIENT || progress.done(stepId)) return;
        progress = progress.with(stepId);
        PacketDistributor.sendToServer(new TutorialNetwork.StepDone(stepId));
    }

    /** At login: asks the server for this player's progress. */
    public static void hello() {
        progress = Progress.NONE;
        PacketDistributor.sendToServer(new TutorialNetwork.Hello());
    }

    /** The guide key (default H). */
    public static final net.minecraft.client.KeyMapping KEY = new net.minecraft.client.KeyMapping("key.icheme.guide", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_H, "key.categories.icheme");

    /** Opens the guide (the Field Guide item and the guide key). */
    public static void open() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(new GuideScreen()));
    }

    /** A key press: the guide key opens the guide while no screen is open. */
    public static void key(net.neoforged.neoforge.client.event.InputEvent.Key event) {
        boolean pressed = false;
        while (KEY.consumeClick()) pressed = true;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (pressed && mc.screen == null && mc.player != null) mc.setScreen(new GuideScreen());
    }
}
