package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A flowsheet's facts (batch 2026-11-02-process-simulator, plan section 3.9): what the player drew and set. Results are state
 * and live in a {@link FlowsheetRun}; {@link FlowsheetFile} is the format. Each record's {@code unknown} holds the fields a later
 * version put there, which this one keeps and writes back.
 *
 * @param basis    the package the sheet's streams flash on
 * @param units    the tiles, in sheet order
 * @param streams  the lines between ports; an end with no port is a pill
 * @param settings the solver's numbers
 */
public record Flowsheet(Basis basis, List<Unit> units, List<Stream> streams, FlowsheetSolver.Settings settings, JsonObject unknown) {
    /** The package, by its id and declared revision, and the component ids the sheet was drawn on. */
    public record Basis(String packageId, String revision, List<String> components, JsonObject unknown) {
        public Basis {
            Objects.requireNonNull(packageId, "packageId");
            Objects.requireNonNull(revision, "revision");
            components = List.copyOf(components);
            Objects.requireNonNull(unknown, "unknown");
        }

        public Basis(String packageId, String revision, List<String> components) {
            this(packageId, revision, components, new JsonObject());
        }
    }

    /**
     * A tile.
     *
     * @param id   stable, unique in the sheet: what a stream's end names
     * @param type the stable type string, {@link UnitTypes}
     * @param name its tag, which the player may change
     */
    public record Unit(String id, String type, String name, int x, int y, Params params, JsonObject unknown) {
        public Unit {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(params, "params");
            Objects.requireNonNull(unknown, "unknown");
        }

        public Unit(String id, String type, String name, int x, int y, Params params) {
            this(id, type, name, x, y, params, new JsonObject());
        }
    }

    /** One end of a stream: a unit's port by their stable strings. */
    public record End(String unit, String port, JsonObject unknown) {
        public End {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(unknown, "unknown");
        }

        public End(String unit, String port) {
            this(unit, port, new JsonObject());
        }
    }

    /** A corner of a stream's line on the sheet. */
    public record Point(int x, int y, JsonObject unknown) {
        public Point {
            Objects.requireNonNull(unknown, "unknown");
        }

        public Point(int x, int y) {
            this(x, y, new JsonObject());
        }
    }

    /**
     * A stream. With no {@code from} it is a feed pill and needs {@code feed}; with no {@code to} it is a product pill. A line
     * between two ports may hold a {@code feed} too: its first estimate, for a loop that cannot start from no flow
     * ({@link FlowsheetCompiler#estimates}).
     *
     * @param id    unique in the sheet
     * @param route the corners the player gave its line; none is the shortest way
     */
    public record Stream(String id, String name, @Nullable End from, @Nullable End to, @Nullable Feed feed, List<Point> route, JsonObject unknown) {
        public Stream {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            route = List.copyOf(route);
            Objects.requireNonNull(unknown, "unknown");
        }

        public Stream(String id, String name, @Nullable End from, @Nullable End to, @Nullable Feed feed) {
            this(id, name, from, to, feed, List.of(), new JsonObject());
        }
    }

    /**
     * A feed's conditions, as the Fluid Generator's page sets them.
     *
     * @param temperature K
     * @param pressure    Pa
     * @param amounts     mol/s by component id, in the order the file holds them, so a sheet survives a change of the axis
     * @param solids      what it carries as a slurry
     */
    public record Feed(double temperature, double pressure, Map<String, Double> amounts, List<Solid> solids, JsonObject unknown) {
        public Feed {
            amounts = Collections.unmodifiableMap(new LinkedHashMap<>(amounts));
            solids = List.copyOf(solids);
            Objects.requireNonNull(unknown, "unknown");
        }

        public Feed(double temperature, double pressure, Map<String, Double> amounts, List<Solid> solids) {
            this(temperature, pressure, amounts, solids, new JsonObject());
        }
    }

    /**
     * One particle grade of a slurry feed.
     *
     * @param material    the solid material's id
     * @param micrometres its diameter, as the world's particle size reads it
     * @param massFlow    kg/s
     */
    public record Solid(String material, String micrometres, double massFlow, JsonObject unknown) {
        public Solid {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(micrometres, "micrometres");
            Objects.requireNonNull(unknown, "unknown");
        }

        public Solid(String material, String micrometres, double massFlow) {
            this(material, micrometres, massFlow, new JsonObject());
        }
    }

    public Flowsheet {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(settings, "settings");
        units = List.copyOf(units);
        streams = List.copyOf(streams);
        Objects.requireNonNull(unknown, "unknown");
    }

    public Flowsheet(Basis basis, List<Unit> units, List<Stream> streams, FlowsheetSolver.Settings settings) {
        this(basis, units, streams, settings, new JsonObject());
    }

    /** A copy that shares nothing mutable: a {@link Params} and every {@code unknown} wrap a {@link JsonObject}. */
    public Flowsheet copy() {
        var units = this.units.stream().map(u -> new Unit(u.id(), u.type(), u.name(), u.x(), u.y(), new Params(u.params().json().deepCopy()), u.unknown().deepCopy())).toList();
        var streams = this.streams.stream().map(s -> new Stream(s.id(), s.name(), copy(s.from()), copy(s.to()), copy(s.feed()), s.route().stream().map(p -> new Point(p.x(), p.y(), p.unknown().deepCopy())).toList(), s.unknown().deepCopy())).toList();
        return new Flowsheet(new Basis(basis.packageId(), basis.revision(), basis.components(), basis.unknown().deepCopy()), units, streams, settings, unknown.deepCopy());
    }

    private static @Nullable End copy(@Nullable End end) {
        return end == null ? null : new End(end.unit(), end.port(), end.unknown().deepCopy());
    }

    private static @Nullable Feed copy(@Nullable Feed feed) {
        return feed == null ? null : new Feed(feed.temperature(), feed.pressure(), feed.amounts(), feed.solids().stream().map(s -> new Solid(s.material(), s.micrometres(), s.massFlow(), s.unknown().deepCopy())).toList(), feed.unknown().deepCopy());
    }
}
