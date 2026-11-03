package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reversible passages (batch 2026-11-02-process-simulator, PORT_SIDES_REVIEW.md option B, owner 2026-11-04): a passage's two ends
 * have no fixed inlet, the stream on one decides, and the run reads the passage by its roles whichever end it enters by.
 */
final class WiringTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = FluidMaterialCatalog.NETWORK_PACKAGE;
    private static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, NETWORK).components();

    private static Flowsheet.Feed water(double temperature, double pressure, double flow) {
        return new Flowsheet.Feed(temperature, pressure, Map.of("Water", flow), List.of());
    }

    private static Flowsheet.Unit unit(String id, String type, String settings) {
        return new Flowsheet.Unit(id, type, id, 0, 0, new Params(JsonParser.parseString(settings).getAsJsonObject()));
    }

    private static Flowsheet sheet(List<Flowsheet.Unit> units, List<Flowsheet.Stream> streams) {
        return new Flowsheet(new Flowsheet.Basis(NETWORK, CATALOG.requirePackage(NETWORK).revision(), AXIS), units, streams, FlowsheetSolver.Settings.DEFAULT);
    }

    private static Flowsheet.Stream feed(String id, String unit, String end, Flowsheet.Feed feed) {
        return new Flowsheet.Stream(id, id, null, new Flowsheet.End(unit, end), feed);
    }

    private static Flowsheet.Stream product(String id, String unit, String end) {
        return new Flowsheet.Stream(id, id, new Flowsheet.End(unit, end), null, null);
    }

    private static FlowsheetRun run(Flowsheet sheet) {
        return com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(sheet, CATALOG, () -> {
        });
    }

    private static void same(FlowsheetRun a, FlowsheetRun b, String stream) {
        var x = a.streams().get(stream);
        var y = b.streams().get(stream);
        assertEquals(x.temperature(), y.temperature(), 1e-9, stream);
        assertEquals(x.pressure(), y.pressure(), 1e-6, stream);
        assertEquals(x.enthalpy(), y.enthalpy(), 1e-6 * Math.max(1, Math.abs(x.enthalpy())), stream);
    }

    @Test void aStreamOnEitherEndDecidesThePassage() {
        var valve = unit("V", "valve", "{\"pressure\":6e5}");
        var into = sheet(List.of(valve), List.of(feed("in", "V", "a", water(300, 8e5, 100)), product("out", "V", "b")));
        var wiring = Wiring.of(into);
        assertEquals("in", wiring.role(new Flowsheet.End("V", "a"), true));
        assertEquals("out", wiring.role(new Flowsheet.End("V", "b"), false));
        assertNull(wiring.role(new Flowsheet.End("V", "a"), false), "the inlet end takes no outlet");
        var back = sheet(List.of(valve), List.of(feed("in", "V", "b", water(300, 8e5, 100)), product("out", "V", "a")));
        assertEquals("b", Wiring.of(back).inlet("V", Passage.THROUGH));
        assertEquals("in", Wiring.roles(back).streams().getFirst().to().port());
        assertEquals("out", Wiring.roles(back).streams().get(1).from().port());
        var free = sheet(List.of(valve), List.of());
        assertTrue(Wiring.of(free).free("V", "a") && Wiring.of(free).free("V", "b"), "no stream, either end");
        assertTrue(Wiring.of(free).isInlet("V", "a"), "drawn where the inlet stood until a stream decides");
    }

    @Test void aValveRunsTheSameEnteredByEitherEnd() {
        var valve = unit("V", "valve", "{\"pressure\":6e5}");
        var ab = run(sheet(List.of(valve), List.of(feed("in", "V", "a", water(300, 8e5, 100)), product("out", "V", "b"))));
        var ba = run(sheet(List.of(valve), List.of(feed("in", "V", "b", water(300, 8e5, 100)), product("out", "V", "a"))));
        assertEquals(FlowsheetRun.Status.STEADY, ab.status());
        assertEquals(FlowsheetRun.Status.STEADY, ba.status());
        assertEquals(6e5, ba.streams().get("out").pressure(), 1e-6);
        same(ab, ba, "out");
    }

    /** The world builds counterflow, so which ends a sheet uses moves nothing: the model reads its roles. */
    @Test void anExchangerStaysCounterflowWhicheverEndsCarryItsSides() {
        var exchanger = unit("E", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":320}");
        var forward = run(sheet(List.of(exchanger), List.of(feed("hot", "E", "tube_a", water(360, 5e5, 100)), feed("cold", "E", "shell_a", water(300, 5e5, 200)),
                product("hot out", "E", "tube_b"), product("cold out", "E", "shell_b"))));
        var turned = run(sheet(List.of(exchanger), List.of(feed("hot", "E", "tube_b", water(360, 5e5, 100)), feed("cold", "E", "shell_b", water(300, 5e5, 200)),
                product("hot out", "E", "tube_a"), product("cold out", "E", "shell_a"))));
        assertEquals(FlowsheetRun.Status.STEADY, turned.status());
        assertEquals(320, turned.streams().get("cold out").temperature(), 1e-9);
        same(forward, turned, "hot out");
        same(forward, turned, "cold out");
        assertEquals(forward.reports().keySet(), turned.reports().keySet());
        assertTrue(forward.reports().keySet().stream().anyMatch(key -> key.startsWith("E")), () -> "reports " + forward.reports().keySet());
        for (var key : forward.reports().keySet()) assertEquals(forward.reports().get(key).readings(), turned.reports().get(key).readings(), key + ": the rating reads the same pair");
    }

    @Test void bothEndsWiredTheSameWayLeaveTheSecondOpen() {
        var valve = unit("V", "valve", "{\"pressure\":6e5}");
        var two = sheet(List.of(valve), List.of(feed("first", "V", "a", water(300, 8e5, 100)), feed("second", "V", "b", water(300, 8e5, 100))));
        var roles = Wiring.roles(two);
        assertEquals("in", roles.streams().getFirst().to().port(), "the first stream decides");
        assertEquals("b", roles.streams().get(1).to().port(), "the second plays no role: its end keeps its own name, which no model has");
        assertEquals(100, run(two).streams().get("V:out").total(), 1e-9, "only the first reaches the valve, whose free outlet carries it");
    }

    @Test void aRoleNamedOnTheSheetPlaysNone() {
        var valve = unit("V", "valve", "{\"pressure\":6e5}");
        var named = sheet(List.of(valve), List.of(feed("in", "V", "in", water(300, 8e5, 100))));
        assertNull(Wiring.of(named).role(new Flowsheet.End("V", "in"), true));
        assertEquals("", Wiring.roles(named).streams().getFirst().to().port(), "the sheet shows ends, never roles");
    }

    /** The column's process ports stay fixed; only its heat members' utility sides are passages (owner 2026-11-04). */
    @Test void theColumnsPassagesAreItsUtilitySides() throws IOException {
        var image = FlowsheetFile.read(Files.readAllBytes(Path.of("src/test/resources/compat/flowsheet/format-" + FlowsheetFile.FORMAT + ".json")));
        var column = image.units().stream().filter(u -> u.type().equals("column")).findFirst().orElseThrow();
        var ends = new HashSet<String>();
        var roles = new ArrayList<String>();
        for (var passage : new ColumnUnit().passages(column.params())) {
            ends.add(passage.a());
            ends.add(passage.b());
            roles.add(passage.in());
        }
        assertTrue(ends.containsAll(Set.of("condenser_a", "condenser_b", "pa/1/a", "pa/1/b", "pa/2/a", "pa/2/b")), () -> "ends " + ends);
        for (var role : roles) assertTrue(role.equals("condenser_in") || role.equals("reboiler_in") || role.matches("pa/\\d+/in"), role);
        var wiring = Wiring.of(image);
        for (var fixed : List.of("feed", "overhead", "distillate", "water", "bottoms", "steam/1", "draw/1")) assertNull(wiring.passage(column.id(), fixed), fixed);
        assertEquals("pa/1/in", wiring.role(new Flowsheet.End(column.id(), "pa/1/a"), true));
    }
}
