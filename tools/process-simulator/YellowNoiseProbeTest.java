package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3TestInputs;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Yellow noise, {@code SIZING_PLAN.md} section 8: each crude tower template on the sample's layout, through the rules once. A row that
 * goes yellow on every crude is evidence for the owner: this reads the table and changes nothing. No gate runs it. Lines start with
 * {@code NOISE}; columns and their members read nothing until S3.
 *
 * <p>Copy it into {@code src/test/java/com/wormzjl/icheme/client/gui/flowsheet/}, run
 * {@code ./gradlew test --tests '*YellowNoiseProbeTest' -i} and remove the copy.
 */
final class YellowNoiseProbeTest {
    /** Every tower template but {@code pot}, which has no draws, no steam and no pumparounds to stand on the sample's ports. */
    private static final List<String> CRUDES = List.of("tia_juana", "bonga", "cold_lake_blend", "dalia", "upper_zakum", "wti_light_export");

    /** The sample's sheet, with this crude's stream and tower. */
    private static Flowsheet on(String crude) {
        V3ColumnInput input = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> V3TestInputs.crude(SheetFixture.CATALOG, crude));
        var sample = SheetFixture.sample();
        var door = ColumnDoor.from(input, ColumnComponents.Condenser.EXCHANGER);
        door.json().addProperty("condenser_drop", .3e5);
        for (var row : door.rows("pumparounds")) row.json().addProperty("drop", .3e5);
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sample.units()) units.add(unit.type().equals("column") ? new Flowsheet.Unit(unit.id(), unit.type(), unit.name(), unit.x(), unit.y(), door) : unit);
        var ids = input.componentBasis().componentIds();
        var amounts = new LinkedHashMap<String, Double>();
        double[] flows = input.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < flows.length; i++) if (flows[i] > 0) amounts.put(ids.get(i), flows[i]);
        var streams = new ArrayList<Flowsheet.Stream>();
        for (var stream : sample.streams()) {
            var feed = stream.feed();
            if (stream.id().equals("CRUDE")) feed = new Flowsheet.Feed(feed.temperature(), feed.pressure(), amounts, List.of());
            streams.add(new Flowsheet.Stream(stream.id(), stream.name(), stream.from(), stream.to(), feed));
        }
        return new Flowsheet(sample.basis(), units, streams, sample.settings());
    }

    @Test
    void readTheTemplates() {
        var thermo = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
        });
        var rows = new TreeMap<String, Integer>();
        for (var crude : CRUDES) {
            var sheet = on(crude);
            var run = FlowsheetEngine.run(sheet, SheetFixture.CATALOG, () -> {
            });
            System.out.printf(Locale.ROOT, "NOISE\trun\t%s\t%s\tpasses %d%n", crude, run.status(), run.passes().size());
            if (run.status() != FlowsheetRun.Status.STEADY) {
                for (var note : run.log()) System.out.printf(Locale.ROOT, "NOISE\tnote\t%s\t%s\t%s\t%s\t%s%n", crude, note.severity(), note.subject(), note.key(), note.arguments());
                continue;
            }
            var ratings = new LinkedHashMap<String, Rating>();
            var sized = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.size(sheet, run, thermo, SheetSizing.Entries.DEFAULT));
            sized.lines().forEach((id, line) -> ratings.put(id, line.rating()));
            sized.units().forEach((id, unit) -> ratings.put(id, unit.rating()));
            int read = 0, yellow = 0, red = 0;
            for (Map.Entry<String, Rating> item : ratings.entrySet())
                for (var reading : item.getValue().readings()) {
                    read++;
                    var lamp = reading.lamp();
                    if (lamp == Lamp.YELLOW) yellow++;
                    if (lamp == Lamp.RED) red++;
                    if (lamp != Lamp.YELLOW && lamp != Lamp.RED) continue;
                    rows.merge(lamp + " " + reading.limit(), 1, Integer::sum);
                    System.out.printf(Locale.ROOT, "NOISE\trow\t%s\t%s\t%s\t%s\t%.3f\t[%.3f, %.3f]%n", crude, item.getKey(), lamp, reading.limit(), reading.value(), reading.low(), reading.high());
                }
            System.out.printf(Locale.ROOT, "NOISE\ttotal\t%s\t%d items\t%d rows\t%d yellow\t%d red%n", crude, ratings.size(), read, yellow, red);
        }
        rows.forEach((row, count) -> System.out.printf(Locale.ROOT, "NOISE\tcount\t%s\t%d%n", row, count));
    }
}
