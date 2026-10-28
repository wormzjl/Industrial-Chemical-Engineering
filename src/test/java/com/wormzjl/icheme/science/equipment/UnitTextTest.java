package com.wormzjl.icheme.science.equipment;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every unit kind's words fit their places on the equipment screens (in-game check 2026-10-18, batch
 * 2026-10-15-custom-equipment-rendering, {@code NEW_UNITS_REVIEW.md}: an FCC's feed line ran out of the constructor's
 * panel into the world). Widths are the vanilla default font's advances, from its ascii sheet; other glyphs count 6.
 * The constructor's door on the GUI kit ({@code ConstructorColumn}, GUI_REVIEW.md order 3: 200 px, its column 184 px
 * from x 8, its groups' rows 176) leaves 180 for the message display's line, which shows what the unit holds or the
 * status; 68 beside a stepped row's box for its label, in two lines at most, each word whole (else its group's labels
 * stand above their boxes, taller); 122 for the kind's name on the title plate before Change (else its short name
 * shows); 168 for a group's plate; 50 for a group's key, three a row, and 170 for a kind's, one a row, each its button
 * less 6. The datasheet's lines wrap on the vessel screen's glass (DialogColumns.glass); the limit keeps each line short,
 * at most the 427 pixels of an 854-pixel-wide window at GUI scale 2, less 16-pixel margins.
 */
class UnitTextTest {
    private static final String C = "gui.icheme.equipment_constructor.", V = "gui.icheme.vessel.";
    private static final int MIDDLE = 180, LABEL = 68, TITLE = 122, PLATE = 168, CATEGORY = 50, KIND = 170, DATASHEET = 411;

    /** A line's width in GUI pixels in the vanilla default font. */
    static int width(String text) {
        int width = 0;
        for (char c : text.toCharArray())
            width += switch (c) {
                case ' ' -> 4;
                case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2;
                case '`', 'l' -> 3;
                case '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
                case '<', '>', 'f', 'k' -> 5;
                case '@', '~' -> 7;
                default -> 6;
            };
        return width;
    }

    private static JsonObject lang() throws IOException {
        try (var in = Objects.requireNonNull(UnitTextTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json"))) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static String text(JsonObject lang, String key, Object... args) {
        assertTrue(lang.has(key), "no " + key);
        return String.format(Locale.ROOT, lang.get(key).getAsString(), args);
    }

    private static void fits(ArrayList<String> over, String where, String text, int limit) {
        if (width(text) > limit) over.add(where + " " + width(text) + " > " + limit + ": " + text);
    }

    /** A label beside its box in {@code share}: every word whole, two lines at most, wrapped at spaces. */
    private static void beside(ArrayList<String> over, String where, String text, int share) {
        int lines = 1, line = -1;
        for (String word : text.split(" ")) {
            int w = width(word);
            if (w > share) over.add(where + " word " + w + " > " + share + ": " + text);
            if (line < 0) line = w;
            else if (line + 4 + w <= share) line += 4 + w;
            else {
                lines++;
                line = w;
            }
        }
        if (lines > 2) over.add(where + " " + lines + " lines in " + share + ": " + text);
    }

    @Test
    void everyUnitsWordsFitTheirScreens() throws IOException {
        var lang = lang();
        var over = new ArrayList<String>();
        for (String category : new String[]{"vessels", "heat", "machines", "reactors", "solid_fuel"})
            fits(over, "category", text(lang, C + "category." + category), CATEGORY);
        for (var kind : Kind.values()) {
            if (!kind.designed()) continue;
            var design = UnitDesign.of(kind);
            String name = kind.name().toLowerCase(Locale.ROOT);
            // The constructor names a form: a tank's is the floating-roof tank.
            String form = kind == Kind.TANK ? "floating_roof_tank" : name;
            String full = text(lang, C + "equipment." + form), shorter = text(lang, C + "equipment." + form + ".short");
            if (width(full) > TITLE) fits(over, kind + " short title", shorter, TITLE);
            fits(over, kind + " kind button", shorter, KIND);
            for (String row : new String[]{design.sizeRow(), design.lengthRow(), design.supportRow()})
                if (row != null) beside(over, kind + " row", text(lang, C + row), LABEL);
        }
        // The bottom outlet's group (owner request 2026-10-25): a standing drum's Nozzles page, a trickle-bed reactor's design page.
        fits(over, "outlet plate", text(lang, C + "outlet"), PLATE);
        // The message display's status lines, at their longest.
        for (String status : new String[]{"importing", "import_needed", "standing"}) fits(over, status, text(lang, C + status), MIDDLE);
        fits(over, "blocked", text(lang, C + "blocked", 9999), MIDDLE);
        for (String clash : new String[]{"connection", "vapour", "liquid", "saddle", "ground", "manway", "boot"}) fits(over, "clash " + clash, text(lang, C + "clash." + clash, 6, 6), MIDDLE);
        for (String role : new String[]{"reflux", "feed", "pumparound_draw", "return", "vapour_return", "draw", "steam", "bottoms"})
            fits(over, "unplaced " + role, text(lang, C + "nozzle.unplaced", text(lang, C + "nozzle.role." + role, 9)), MIDDLE);
        var shapes = new ArrayList<>(ProcessUnitTest.everySize());
        shapes.addAll(ShapeRasterizerTest.everyStorageSize());
        for (var shape : shapes) {
            var design = UnitDesign.of(shape.kind());
            String name = shape.kind().name().toLowerCase(Locale.ROOT), at = shape.kind() + " " + shape.diameter() + " x " + (int) shape.tangentLength();
            var held = design.held(shape);
            fits(over, at + " display line", text(lang, C + held.key(), held.args()), MIDDLE);
            fits(over, at + " kind line", text(lang, V + "kind." + name + design.variant(shape)), DATASHEET);
            for (var line : design.datasheet(shape)) fits(over, at + " datasheet", text(lang, V + line.key(), line.args()), DATASHEET);
            fits(over, at + " support line", text(lang, V + "unit_support." + name, shape.support()), DATASHEET);
        }
        assertEquals(new ArrayList<String>(), over);
    }
}
