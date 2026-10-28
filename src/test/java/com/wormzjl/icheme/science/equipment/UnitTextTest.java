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
 * panel into the world). Widths are the vanilla default font's advances, from its ascii sheet; other glyphs count 6 and
 * the current-choice marker 8. The constructor's panel ({@code EquipmentConstructorScreen.PANEL} 196, text at 8) leaves
 * 184 for its middle line with a 4-pixel margin, 104 for a row's label before its steppers at {@code PANEL - 80} and for
 * the kind's title before Change (else its short name shows); its choice buttons ({@code GROUP_WIDTH} 64, the kinds' 112)
 * show their width less 4. The datasheet's lines wrap on the vessel screen's glass (DialogColumns.glass); the limit
 * keeps each line short, at most the 427 pixels of an 854-pixel-wide window at GUI scale 2, less 16-pixel margins.
 */
class UnitTextTest {
    private static final String C = "gui.icheme.equipment_constructor.", V = "gui.icheme.vessel.";
    private static final int MIDDLE = 184, LABEL = 104, CATEGORY = 60, KIND = 108, DATASHEET = 411;

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
                case '▶' -> 8;
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

    @Test
    void everyUnitsWordsFitTheirScreens() throws IOException {
        var lang = lang();
        var over = new ArrayList<String>();
        for (String category : new String[]{"vessels", "heat", "machines", "reactors", "solid_fuel"})
            fits(over, "category", text(lang, C + "current", text(lang, C + "category." + category)), CATEGORY);
        for (var kind : Kind.values()) {
            if (!kind.designed()) continue;
            var design = UnitDesign.of(kind);
            String name = kind.name().toLowerCase(Locale.ROOT);
            // The constructor names a form: a tank's is the floating-roof tank.
            String form = kind == Kind.TANK ? "floating_roof_tank" : name;
            String full = text(lang, C + "equipment." + form), shorter = text(lang, C + "equipment." + form + ".short");
            if (width(full) > LABEL) fits(over, kind + " short title", shorter, LABEL);
            fits(over, kind + " kind button", text(lang, C + "current", shorter), KIND);
            for (String row : new String[]{design.sizeRow(), design.lengthRow(), design.supportRow()})
                if (row != null) fits(over, kind + " row", text(lang, C + row), LABEL);
        }
        // The bottom outlet's row (owner request 2026-10-25): a standing drum's Nozzles page, a trickle-bed reactor's design page.
        fits(over, "outlet row", text(lang, C + "outlet"), LABEL);
        var shapes = new ArrayList<>(ProcessUnitTest.everySize());
        shapes.addAll(ShapeRasterizerTest.everyStorageSize());
        for (var shape : shapes) {
            var design = UnitDesign.of(shape.kind());
            String name = shape.kind().name().toLowerCase(Locale.ROOT), at = shape.kind() + " " + shape.diameter() + " x " + (int) shape.tangentLength();
            var held = design.held(shape);
            fits(over, at + " middle line", text(lang, C + held.key(), held.args()), MIDDLE);
            fits(over, at + " kind line", text(lang, V + "kind." + name + design.variant(shape)), DATASHEET);
            for (var line : design.datasheet(shape)) fits(over, at + " datasheet", text(lang, V + line.key(), line.args()), DATASHEET);
            fits(over, at + " support line", text(lang, V + "unit_support." + name, shape.support()), DATASHEET);
        }
        assertEquals(new ArrayList<String>(), over);
    }
}
