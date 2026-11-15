package com.wormzjl.icheme.network;

import com.wormzjl.icheme.science.fluid.conversion.ConversionRule;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRules;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRuntime;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The conversion rules on the client (batch 2026-11-15-fluid-interfaces, W5, D10): every bundled and fixture rule, written
 * with its names resolved, reads back on the client's names to an equal rule (a preset as its composition, so the client
 * needs no catalog); the payload round-trips in match order; the client's copy is apart from the server's and its listener
 * hears each copy.
 */
class ConversionRulesSyncTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final ConversionRule.Names NAMES = ConversionRules.names(CATALOG, FluidMaterialCatalog.networkPackage(CATALOG));

    private static ConversionRules all() throws IOException {
        var files = new TreeMap<String, String>();
        for (var folder : List.of(Path.of("src/main/resources/data/icheme/icheme/fluid_conversion"), Path.of("src/test/resources/fluid_conversion/fixture")))
            try (var walk = Files.walk(folder)) {
                for (var p : walk.filter(f -> f.toString().endsWith(".json")).toList()) {
                    var name = folder.relativize(p).toString().replace('\\', '/');
                    files.put((folder.toString().contains("fixture") ? "test:" : "icheme:") + name.substring(0, name.length() - 5), Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        return ConversionRules.parse(files, NAMES, ns -> true);
    }

    @Test
    void eachRuleReadsBackEqualOnTheClientsNames() throws IOException {
        var rules = all();
        assertTrue(rules.rules().size() >= 25, "bundled and fixture rules: " + rules.rules().size());
        for (var rule : rules.rules()) {
            var back = ConversionRule.parse(rule.id(), rule.toJson(), ConversionRule.RESOLVED);
            assertEquals(rule, back, rule.id());
        }
    }

    @Test
    void thePayloadRoundTripsInMatchOrder() throws IOException {
        var rules = all();
        var payload = FluidNetwork.ConversionRulesSync.of(rules);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        FluidNetwork.ConversionRulesSync.STREAM_CODEC.encode(buffer, payload);
        var read = FluidNetwork.ConversionRulesSync.STREAM_CODEC.decode(buffer);
        assertEquals(0, buffer.readableBytes());
        assertEquals(payload, read);
        var client = read.rules();
        assertEquals(List.of(), client.refused());
        assertEquals(rules.rules(), client.rules());
        assertEquals("icheme:c/crude_oil", client.forward("icheme:crude_oil", java.util.Set.of("c:crude_oil"), false).id());
    }

    @Test
    void theClientsCopyIsItsOwnAndItsListenerHearsIt() throws IOException {
        var heard = new ArrayList<Integer>();
        ConversionRuntime.listenClient(r -> heard.add(r.rules().size()));
        var server = ConversionRuntime.active();
        try {
            var rules = all();
            FluidNetwork.acceptConversionRules(FluidNetwork.ConversionRulesSync.of(rules));
            assertSame(server, ConversionRuntime.active(), "the server's rules are untouched");
            assertEquals(rules.rules(), ConversionRuntime.client().rules());
            ConversionRuntime.resetClient();
            assertSame(ConversionRules.NONE, ConversionRuntime.client());
            assertEquals(List.of(rules.rules().size(), 0), heard);
        } finally {
            ConversionRuntime.resetClient();
        }
    }
}
