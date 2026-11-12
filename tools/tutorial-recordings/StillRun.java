import com.wormzjl.icheme.science.tutorial.StillRunRecorder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Records the still's run of chapter 2 (batch 2026-11-12-tutorial-system, P2b): one batch of the world's still law on
 * medium crude (StillRunRecorder in the test sources, which StillRunRecordingTest reruns), written to
 * assets/icheme/tutorial/recordings/still_run.json. Prints the derived lines.
 * Usage, from the worktree root: ./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordStillRun
 */
public final class StillRun {
    public static final String PATH = "src/main/resources/assets/icheme/tutorial/recordings/still_run.json";

    public static void main(String[] args) throws Exception {
        var root = Path.of(args.length > 0 ? args[0] : ".");
        var run = StillRunRecorder.run();
        var out = root.resolve(PATH);
        Files.createDirectories(out.getParent());
        Files.writeString(out, StillRunRecorder.toJson(run), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "charge %.3f kg (%.4f kg flashed off), finish %s after %d steps; left %.3f kg, distilled %.3f kg, vented %.3f kg%n", run.chargeKg(), run.flashedKg(), run.finish(), run.steps(),
                run.leftKg(), run.distilledKg(), run.ventedKg());
        for (var entry : new Object[][]{{"first", run.first()}, {"trickle", run.trickle()}, {"naphtha", run.naphtha()}, {"kerosene", run.kerosene()}, {"end", run.end()}}) {
            var line = (StillRunRecorder.Line) entry[1];
            if (line == null) {
                System.out.println(entry[0] + " not reached");
                continue;
            }
            System.out.printf(Locale.ROOT, "%-9s %9.1f s  pot %.2f K (%.1f C)  %.1f min of play%n", entry[0], line.time(), line.pot(), line.pot() - 273.15, line.playMinutes());
        }
        if (run.refusal() != null) System.out.println("the law refused the next step: " + run.refusal());
        var status = new java.util.LinkedHashMap<String, Integer>();
        for (var p : run.points()) status.merge(p.status().name(), 1, Integer::sum);
        System.out.println("statuses " + status);
        System.out.println("wrote " + out);
    }
}
