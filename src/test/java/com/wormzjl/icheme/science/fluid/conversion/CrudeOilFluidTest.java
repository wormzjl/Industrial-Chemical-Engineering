package com.wormzjl.icheme.science.fluid.conversion;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The placeholder crude oil (batch 2026-11-15-fluid-interfaces, W3, D9), without a Minecraft bootstrap: both fluids are in
 * {@code c:crude_oil} and the bucket in {@code c:buckets/crude_oil}; the bundled tag rule {@code icheme:c/crude_oil}
 * answers for {@code icheme:crude_oil} with no other mod present; the block state, models and names a liquid block and a
 * bucket need exist. The live registry and tags: {@code CrudeOilGameTests}.
 */
class CrudeOilFluidTest {
    private static final Path RESOURCES = Path.of("src/main/resources");

    private static List<String> values(String tag) throws IOException {
        var json = JsonParser.parseString(Files.readString(RESOURCES.resolve(tag), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(json.has("replace") && json.get("replace").getAsBoolean(), tag + ": a shared tag is never replaced");
        var out = new ArrayList<String>();
        for (var v : json.getAsJsonArray("values")) out.add(v.getAsString());
        return out;
    }

    @Test
    void bothFluidsAndTheBucketAreInTheSharedTags() throws IOException {
        assertEquals(List.of("icheme:crude_oil", "icheme:flowing_crude_oil"), values("data/c/tags/fluid/crude_oil.json"));
        assertEquals(List.of("icheme:crude_oil_bucket"), values("data/c/tags/item/buckets/crude_oil.json"));
    }

    @Test
    void theBundledTagRuleAnswersForThePlaceholder() throws IOException {
        var rules = ConversionRules.parse(ConversionRuleTest.bundled(), ConversionRuleTest.NAMES, ns -> ns.equals("minecraft") || ns.equals("c") || ns.equals("icheme"));
        assertEquals(List.of(), rules.refused());
        var rule = rules.forward("icheme:crude_oil", Set.of("c:crude_oil"), false);
        assertNotNull(rule, "the placeholder converts");
        assertEquals("icheme:c/crude_oil", rule.id());
        assertEquals(ConversionRule.Phase.LIQUID, rule.phase());
        assertNull(rules.forward("icheme:crude_oil", Set.of(), false), "only through its tag");
    }

    @Test
    void theBlockAndTheBucketHaveTheirAssetsAndNames() throws IOException {
        var assets = RESOURCES.resolve("assets/icheme");
        assertTrue(Files.isRegularFile(assets.resolve("blockstates/crude_oil.json")));
        assertTrue(Files.isRegularFile(assets.resolve("models/block/crude_oil.json")));
        var bucket = JsonParser.parseString(Files.readString(assets.resolve("models/item/crude_oil_bucket.json"))).getAsJsonObject();
        assertEquals("neoforge:fluid_container", bucket.get("loader").getAsString(), "the bucket draws the fluid's own tinted texture");
        assertEquals("icheme:crude_oil", bucket.get("fluid").getAsString());
        var lang = JsonParser.parseString(Files.readString(assets.resolve("lang/en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (var key : List.of("block.icheme.crude_oil", "fluid_type.icheme.crude_oil", "item.icheme.crude_oil_bucket"))
            assertTrue(lang.has(key), key);
    }
}
