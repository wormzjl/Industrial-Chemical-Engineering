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

    private GuideClient() {
    }

    public static Guide guide() {
        if (guide == null) guide = GuideFile.load();
        return guide;
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

    /** Opens the guide (the Field Guide item and the guide key). */
    public static void open() {
        // The guide screen comes with part C of P1.
    }
}
