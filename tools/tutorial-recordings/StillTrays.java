import com.wormzjl.icheme.science.tutorial.StillRunRecorder;
import com.wormzjl.icheme.science.tutorial.StillTraysRecorder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Chapter 3's measurement and recording (batch 2026-11-12-tutorial-system, P4b): the still of chapter 2 (same charge, pot,
 * firing and water) as a pot still and with a column of StillTraysRecorder.STAGES trays at each reflux ratio of
 * StillTraysRecorder.REFLUX. Prints, for each form, how the column answered on the boiling steps and the two cuts; with
 * "-write" it writes assets/icheme/tutorial/recordings/still_trays.json (StillTraysRecordingTest reruns it).
 * Usage, from the worktree root: ./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordStillTrays
 */
public final class StillTrays {
    public static final String PATH = "src/main/resources/assets/icheme/tutorial/recordings/still_trays.json";

    public static void main(String[] args) throws Exception {
        var root = Path.of(args.length > 0 ? args[0] : ".");
        boolean write = args.length > 1 && args[1].equals("-write");
        var forms = StillTraysRecorder.record();
        for (var form : forms) {
            var run = form.run();
            System.out.printf(Locale.ROOT, "== %s: %d trays, reflux %.1f; finish %s after %d steps; distilled %.2f %%, vented %.2f %%%n", form.name(), form.stages(), form.reflux(),
                    run.finish(), run.steps(), 100 * run.distilledKg() / run.chargeKg(), 100 * run.ventedKg() / run.chargeKg());
            if (run.refusal() != null) System.out.println("   refusal: " + run.refusal());
            var c = form.column();
            System.out.printf(Locale.ROOT, "   boiling steps %d: solved %d (%s), dry %d (%s), fallback %d (%s); after the light gas (pot from %.1f C): %d of %d solved (%.0f %%)%n",
                    c.boiling(), c.solved(), range(c.solvedRange()), c.dry(), range(c.dryRange()), c.fallback(), range(c.fallbackRange()), c.gasGone() - 273.15,
                    c.solvedAfterGas(), c.boilingAfterGas(), 100.0 * c.solvedAfterGas() / Math.max(1, c.boilingAfterGas()));
            for (var e : c.failures().entrySet()) System.out.println("     failure x" + e.getValue() + ": " + e.getKey());
            for (var e : c.notices().entrySet()) System.out.println("     notice  x" + e.getValue() + ": " + e.getKey());
            for (var cut : form.cuts()) {
                if (cut == null) {
                    System.out.println("   cut not reached");
                    continue;
                }
                var sb = new StringBuilder();
                for (int b = 0; b < cut.parts().length; b++) sb.append(String.format(Locale.ROOT, " %s %.1f", StillTraysRecorder.bandName(b), 100 * cut.parts()[b]));
                System.out.printf(Locale.ROOT, "   cut to %.0f %%: liquid %.2f kg, pot %.1f C, %.1f min of play;%s%n", 100 * cut.overhead(), cut.liquidKg(), cut.pot() - 273.15, cut.playMinutes(), sb);
            }
        }
        if (write) {
            var out = root.resolve(PATH);
            Files.createDirectories(out.getParent());
            Files.writeString(out, StillTraysRecorder.toJson(forms), StandardCharsets.UTF_8);
            System.out.println("wrote " + out);
        }
    }

    private static String range(double[] r) {
        return r == null ? "-" : String.format(Locale.ROOT, "%.1f to %.1f C", r[0] - 273.15, r[1] - 273.15);
    }
}
