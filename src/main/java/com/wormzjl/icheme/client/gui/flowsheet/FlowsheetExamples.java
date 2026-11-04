package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.SheetBody;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * The example flowsheets bundled with the mod (batch 2026-11-02-process-simulator, owner 2026-11-04: "Add some example flowsheets to
 * the game"), which Open lists first. {@code assets/icheme/flowsheet_examples/index.json} names each file, as the column calculator's
 * presets are bundled; each is a sheet file of the current format. No Minecraft class: the screen passes its resource manager as
 * {@code resources}, which answers null for a missing file. Reading never throws: a file that does not read is listed with its reason.
 */
public final class FlowsheetExamples {
    public static final String FOLDER = "assets/icheme/flowsheet_examples/", INDEX = FOLDER + "index.json";
    private static final int MOST = 64;

    /**
     * A bundled example: its file's stem, which names its language line {@code gui.icheme.flowsheet.example.<stem>}, and its sheet,
     * or the failure key ({@code file.damaged}, {@code file.newer}, {@code file.older}) where it does not read.
     */
    public record Example(String stem, @Nullable Flowsheet sheet, @Nullable String refused) {
    }

    private FlowsheetExamples() {
    }

    /** The bundled examples, in the index's order. */
    public static List<Example> read(Function<String, InputStream> resources) {
        var out = new ArrayList<Example>();
        List<String> files;
        try {
            files = index(resources);
        } catch (IOException | RuntimeException unreadable) {
            out.add(new Example("index", null, "file.damaged"));
            return out;
        }
        for (var file : files) {
            String stem = file.substring(0, file.length() - ".json".length());
            try (var in = resources.apply(FOLDER + file)) {
                if (in == null) throw new IOException("missing " + file);
                out.add(new Example(stem, FlowsheetFile.read(bytes(in)), null));
            } catch (FlowsheetFailure refused) {
                out.add(new Example(stem, null, refused.key()));
            } catch (IOException | RuntimeException unreadable) {
                out.add(new Example(stem, null, "file.damaged"));
            }
        }
        return out;
    }

    /** The index's file names, each {@code [a-z0-9_-]+.json}. */
    static List<String> index(Function<String, InputStream> resources) throws IOException {
        try (var in = resources.apply(INDEX)) {
            if (in == null) throw new IOException("missing " + INDEX);
            var names = new ArrayList<String>();
            var array = JsonParser.parseString(new String(bytes(in), StandardCharsets.UTF_8)).getAsJsonArray();
            if (array.size() > MOST) throw new IOException("too many examples");
            for (var item : array) {
                String name = item.getAsString();
                if (!name.matches("[a-z0-9_-]+\\.json")) throw new IOException("invalid example name " + name);
                names.add(name);
            }
            return names;
        }
    }

    private static byte[] bytes(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(SheetBody.MAX_UNPACKED + 1);
        if (bytes.length > SheetBody.MAX_UNPACKED) throw new IOException("example too large");
        return bytes;
    }
}
