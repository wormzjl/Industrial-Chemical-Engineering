import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * Shares of a JFR recording's CPU samples by what is on the stack, not by the top frame (batch
 * 2026-11-15-fluid-step-cost): for each rule, the samples whose stack holds a frame for every one of the rule's patterns.
 * A rule is {@code name=regex[&regex...]}; a pattern is searched in {@code Class.method} of each frame. Only samples with
 * a frame matching {@code -Dbase} (default: the fluid science packages) are counted; {@code -Dfrom}/{@code -Dto} (epoch
 * ms) keep a window.
 * <pre>java tools/fluid-step-cost/JfrAncestors.java recording.jfr "closure=PhaseLayout\$2\.residual&SparseNewton\.differentiate" ...</pre>
 */
public final class JfrAncestors {
    public static void main(String[] args) throws Exception {
        long from = Long.getLong("from", Long.MIN_VALUE), to = Long.getLong("to", Long.MAX_VALUE);
        var base = Pattern.compile(System.getProperty("base", "com\\.wormzjl\\.icheme\\.science\\.fluid\\."));
        List<String> names = new ArrayList<>();
        List<Pattern[]> rules = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            int cut = args[i].indexOf('=');
            names.add(args[i].substring(0, cut));
            var parts = args[i].substring(cut + 1).split("&");
            var patterns = new Pattern[parts.length];
            for (int p = 0; p < parts.length; p++) patterns[p] = Pattern.compile(parts[p]);
            rules.add(patterns);
        }
        long total = 0;
        long[] hits = new long[rules.size()];
        try (var file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample")) continue;
                long at = event.getStartTime().toEpochMilli();
                if (at < from || at > to || event.getStackTrace() == null) continue;
                List<String> frames = new ArrayList<>();
                boolean inBase = false;
                for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                    String name = frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
                    frames.add(name);
                    if (base.matcher(name).find()) inBase = true;
                }
                if (!inBase) continue;
                total++;
                for (int r = 0; r < hits.length; r++) {
                    boolean all = true;
                    for (Pattern pattern : rules.get(r)) {
                        boolean found = false;
                        for (String frame : frames) if (pattern.matcher(frame).find()) { found = true; break; }
                        if (!found) { all = false; break; }
                    }
                    if (all) hits[r]++;
                }
            }
        }
        System.out.println("samples with a base frame: " + total);
        for (int r = 0; r < hits.length; r++)
            System.out.println(String.format(Locale.ROOT, "%6.2f %%  %8d  %s", total > 0 ? 100.0 * hits[r] / total : 0, hits[r], names.get(r)));
    }
}
