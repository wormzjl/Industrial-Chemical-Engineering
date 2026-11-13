import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Whether the constructor builds a candidate's shell (P4): the candidate's template (tmp/tutorial-p4/<id>.json) laid out with its
 * sheet's column choices (<id>-sheet.json), its shape built at each diameter of -Ddiameters, the refusal printed (a nozzle with no
 * free row by its tray). No solve: a check to run before a candidate's sheet is solved.
 */
public final class ShapeCheck {
    public static void main(String[] args) throws Exception {
        var root = Path.of(args.length > 0 ? args[0] : ".");
        var dir = root.resolve("tmp/tutorial-p4");
        for (var id : System.getProperty("candidate", "A").split(",")) {
            var layout = layout(dir, id);
            var components = layout.components();
            var draws = layout.draws();
            var steam = layout.steam();
            var pas = layout.pumparounds();
            if (Boolean.getBoolean("search")) {
                // Every pumparound placement (each PA's draw between the side draws and the feed, its return 1 to 3 trays above)
                // that a shell of the first diameter builds, the other connections as the template has them. Public numbering.
                double d0 = Double.parseDouble(System.getProperty("diameters", "1.0").split(",")[0]);
                int feed = layout.feed();
                var found = new ArrayList<String>();
                search(layout, components, draws, steam, feed, new ArrayList<>(), pas.size(), d0, found);
                System.out.println(id + " at " + d0 + " m: " + found.size() + " placements build" + (found.isEmpty() ? "" : ", e.g. " + found));
                continue;
            }
            for (var d : System.getProperty("diameters", "1.0,1.5").split(",")) {
                try {
                    var size = new ColumnSizing.Size(Double.parseDouble(d), layout);
                    var shape = size.shape();
                    System.out.println(id + " " + d + " m: built, tangent " + shape.tangentLength() + " m, skirt " + shape.support() + " m, height " + (shape.tangentLength() + shape.support()) + " m");
                } catch (IllegalArgumentException refused) {
                    System.out.println(id + " " + d + " m: refused: " + refused.getMessage());
                }
            }
        }
    }

    /** A candidate's trays (solver numbering) with its sheet's column choices, from tmp/tutorial-p4/<id>.json and <id>-sheet.json. */
    static ColumnTrays layout(Path dir, String id) throws java.io.IOException {
        var t = JsonParser.parseString(Files.readString(dir.resolve(id + ".json"))).getAsJsonObject();
        var sheet = FlowsheetFile.read(Files.readAllBytes(dir.resolve(id + "-sheet.json")));
        var door = ColumnDoor.of(sheet.units().stream().filter(u -> u.id().equals("T-101")).findFirst().orElseThrow().params());
        var draws = new ArrayList<Integer>();
        for (var e : t.getAsJsonArray("draws")) draws.add(e.getAsJsonObject().get("tray").getAsInt() - 1);
        var steam = new ArrayList<Integer>();
        for (var e : t.getAsJsonArray("steam")) steam.add(e.getAsJsonObject().get("tray").getAsInt() - 1);
        var pas = new ArrayList<ColumnTrays.Pumparound>();
        for (var e : t.getAsJsonArray("pumparounds"))
            pas.add(new ColumnTrays.Pumparound(e.getAsJsonObject().get("draw_tray").getAsInt() - 1, e.getAsJsonObject().get("return_tray").getAsInt() - 1));
        var components = ColumnComponents.resolve(door.choices(), t.get("top_pressure_bar").getAsDouble() * 1e5, t.get("reboiler_duty_mw").getAsDouble() * 1e6, pas.size());
        return new ColumnTrays(t.get("tray_count").getAsInt() - 2, t.get("feed_tray").getAsInt() - 1, draws, steam, pas, components);
    }

    private static void search(ColumnTrays base, ColumnComponents components, java.util.List<Integer> draws, java.util.List<Integer> steam, int feed,
                               java.util.List<ColumnTrays.Pumparound> chosen, int count, double diameter, java.util.List<String> found) {
        if (chosen.size() == count) {
            try {
                new ColumnSizing.Size(diameter, new ColumnTrays(base.trays(), feed, draws, steam, chosen, components)).shape();
                var b = new StringBuilder();
                for (var p : chosen) b.append("PA ").append(p.draw() + 1).append(">").append(p.ret() + 1).append(' ');
                found.add(b.toString().trim());
            } catch (IllegalArgumentException refused) {
                // not this one
            }
            return;
        }
        int from = chosen.isEmpty() ? 2 : chosen.getLast().draw() + 1;
        for (int draw = from; draw < feed; draw++)
            for (int ret = Math.max(1, draw - 3); ret < draw; ret++) {
                var next = new ArrayList<>(chosen);
                next.add(new ColumnTrays.Pumparound(draw, ret));
                search(base, components, draws, steam, feed, next, count, diameter, found);
            }
    }
}
