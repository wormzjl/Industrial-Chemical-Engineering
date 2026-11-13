import com.wormzjl.icheme.science.tutorial.cards.BarrelData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records the crude barrels of the cards barrel and products (batch 2026-11-12-tutorial-system, P2a): reads the four
 * grades' assays, their components and the pure compounds' property records under data/icheme/materials, and writes
 * assets/icheme/tutorial/recordings/crude_barrel.json (BarrelData.compute, BarrelData.toJson). Prints the six band shares.
 * Usage, from the worktree root: ./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordCrudeBarrel
 */
public final class CrudeBarrel {
    public static void main(String[] args) throws Exception {
        var root = Path.of(args.length > 0 ? args[0] : ".");
        var grades = BarrelData.compute(root.resolve("src/main/resources/data/icheme/materials"));
        var out = root.resolve("src/main/resources" + BarrelData.PATH);
        Files.createDirectories(out.getParent());
        Files.writeString(out, BarrelData.toJson(grades), StandardCharsets.UTF_8);
        for (var g : grades) {
            var b = g.bands();
            var p = BarrelData.percents(b);
            var line = new StringBuilder(g.id());
            for (int i = 0; i < b.length; i++) line.append(String.format(java.util.Locale.ROOT, "  %s %.4f (%d %%)", BarrelData.BANDS.get(i), b[i], p[i]));
            System.out.println(line);
        }
        System.out.println("wrote " + out);
    }
}
