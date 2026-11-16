package com.wormzjl.icheme.science.fluid.conversion;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JEI page's text of a conversion rule (batch 2026-11-15-fluid-interfaces, W5), on two fixture rules of
 * {@code src/test/resources/fluid_conversion/fixture/}: water both ways at ambient, and the crude tag rule on the bundled
 * {@code medium_crude} preset (more compounds than the page lists). Every key has its English line.
 */
class ConversionLinesTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final ConversionRule.Names NAMES = ConversionRules.names(CATALOG, FluidMaterialCatalog.networkPackage(CATALOG));

    private static ConversionRule fixture(String name) throws IOException {
        var text = Files.readString(Path.of("src/test/resources/fluid_conversion/fixture/" + name + ".json"), StandardCharsets.UTF_8);
        return ConversionRule.parse("test:" + name, JsonParser.parseString(text).getAsJsonObject(), NAMES);
    }

    private static List<String> render(List<ConversionLines.Line> lines) {
        var out = new ArrayList<String>();
        for (var l : lines) out.add(l.key().substring(ConversionLines.PREFIX.length()) + (l.compound() == null ? "" : " " + l.compound()) + (l.args().isEmpty() ? "" : " " + String.join(" ", l.args())));
        return out;
    }

    @Test
    void waterBothWays() throws IOException {
        assertEquals(List.of(
                "to_icheme.liquid",
                "compound Water 100.0",
                "state 25.0 101.3",
                "to_forge.mole",
                "range Water 99.0 100.0",
                "others 1.00",
                "temperature 0.0 100.0"), render(ConversionLines.of(fixture("water"))));
    }

    @Test
    void theCrudePresetListsItsSixLargestAndCountsTheRest() throws IOException {
        var rule = fixture("crude");
        var lines = ConversionLines.of(rule);
        var composition = rule.toIChemE().composition();
        long named = composition.values().stream().filter(x -> x > 0).count();
        assertTrue(named > ConversionLines.MAX_COMPOUNDS, "the preset has more compounds than the page lists: " + named);
        assertEquals("jei.icheme.fluid_conversion.to_icheme.liquid", lines.get(0).key());
        var compounds = lines.subList(1, 1 + ConversionLines.MAX_COMPOUNDS);
        double last = Double.POSITIVE_INFINITY;
        for (var l : compounds) {
            assertEquals("jei.icheme.fluid_conversion.compound", l.key());
            double x = composition.get(l.compound());
            assertTrue(x <= last, "largest first");
            last = x;
        }
        var more = lines.get(1 + ConversionLines.MAX_COMPOUNDS);
        assertEquals("jei.icheme.fluid_conversion.more", more.key());
        assertEquals(List.of(Long.toString(named - ConversionLines.MAX_COMPOUNDS)), more.args());
        assertEquals("jei.icheme.fluid_conversion.state", lines.get(2 + ConversionLines.MAX_COMPOUNDS).key());
        assertEquals(3 + ConversionLines.MAX_COMPOUNDS, lines.size(), "no reverse block");
    }

    @Test
    void aGasRuleAndOneSidedWindows() throws IOException {
        assertTrue(render(ConversionLines.of(fixture("nitrogen"))).containsAll(List.of("to_icheme.gas", "moles_per_litre 0.04090", "others 5.00")));
        var hot = render(ConversionLines.of(fixture("hot_water")));
        assertEquals(List.of("to_forge.mass", "range Water 99.0 100.0", "others 1.00", "temperature 76.9 126.9"), hot);
    }

    @Test
    void everyKeyHasItsEnglishLine() throws IOException {
        var lang = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/icheme/lang/en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(lang.has(ConversionLines.TITLE));
        var keys = new ArrayList<String>();
        for (var name : List.of("water", "crude", "nitrogen", "hot_water")) for (var l : ConversionLines.of(fixture(name))) keys.add(l.key());
        for (var suffix : List.of("temperature.min", "temperature.max", "pressure", "pressure.min", "pressure.max")) keys.add(ConversionLines.PREFIX + suffix);
        for (var key : keys) assertTrue(lang.has(key), key);
    }
}
