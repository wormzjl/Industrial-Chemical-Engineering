import com.wormzjl.icheme.client.gui.flowsheet.SmallUnitRecorder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Chapter 4's recording (batch 2026-11-12-tutorial-system, P4b): the bundled "Small crude unit" run as bundled and with
 * H-101's outlet lowered (SmallUnitRecorder, test source; SmallUnitRecordingTest reruns it). Prints each run's products and
 * duties; "-tries=K,K" runs more second temperatures for the choice; "-write" writes
 * assets/icheme/tutorial/recordings/small_unit.json with SmallUnitRecorder.SECOND_KELVIN.
 * Usage, from the worktree root: ./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordSmallUnit
 */
public final class SmallUnit {
    public static final String PATH = "src/main/resources/assets/icheme/tutorial/recordings/small_unit.json";

    public static void main(String[] args) throws Exception {
        var root = Path.of(args.length > 0 ? args[0] : ".");
        boolean write = false;
        var tries = new ArrayList<Double>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-write")) write = true;
            else if (args[i].startsWith("-tries=")) for (var t : args[i].substring(7).split(",")) tries.add(Double.parseDouble(t));
        }
        var sheet = SmallUnitRecorder.bundled();
        var runs = new ArrayList<SmallUnitRecorder.Run>();
        runs.add(SmallUnitRecorder.run(sheet));
        if (tries.isEmpty()) tries.add(SmallUnitRecorder.SECOND_KELVIN);
        for (double t : tries) runs.add(SmallUnitRecorder.run(SmallUnitRecorder.withHeater(sheet, t)));
        for (var r : runs) {
            System.out.printf(Locale.ROOT, "H-101 %.2f K (%.1f C): %s, crude %.1f kg/h%n", r.heaterKelvin(), r.heaterKelvin() - 273.15, r.status(), r.crudeKgH());
            for (var e : r.shares().entrySet()) System.out.printf(Locale.ROOT, "   %-9s %8.1f kg/h  %.2f %%%n", e.getKey(), r.productKgH().get(e.getKey()), 100 * e.getValue());
            for (var e : r.duties().entrySet()) System.out.printf(Locale.ROOT, "   duty %-22s %10.1f kW%n", e.getKey(), e.getValue() / 1000);
        }
        if (write) {
            var out = root.resolve(PATH);
            Files.createDirectories(out.getParent());
            Files.writeString(out, SmallUnitRecorder.toJson(runs.subList(0, 2)), StandardCharsets.UTF_8);
            System.out.println("wrote " + out);
        }
    }
}
