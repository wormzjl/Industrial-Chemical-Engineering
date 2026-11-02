package com.wormzjl.icheme.science.flowsheet;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A flowsheet's facts (batch 2026-11-02-process-simulator, plan section 3.9): what the player drew and set. Results are state
 * and live in a {@link FlowsheetRun}.
 *
 * @param basis    the package the sheet's streams flash on
 * @param units    the tiles, in sheet order
 * @param streams  the lines between ports; an end with no port is a pill
 * @param settings the solver's numbers
 */
public record Flowsheet(Basis basis, List<Unit> units, List<Stream> streams, FlowsheetSolver.Settings settings) {
    /** The package, by its id and declared revision, and the component ids the sheet was drawn on. */
    public record Basis(String packageId, String revision, List<String> components) {
        public Basis {
            Objects.requireNonNull(packageId, "packageId");
            Objects.requireNonNull(revision, "revision");
            components = List.copyOf(components);
        }
    }

    /**
     * A tile.
     *
     * @param id   stable, unique in the sheet: what a stream's end names
     * @param type the stable type string, {@link UnitTypes}
     * @param name its tag, which the player may change
     */
    public record Unit(String id, String type, String name, int x, int y, Params params) {
        public Unit {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(params, "params");
        }
    }

    /** One end of a stream: a unit's port by their stable strings. */
    public record End(String unit, String port) {
        public End {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(port, "port");
        }
    }

    /**
     * A stream. With no {@code from} it is a feed pill and needs {@code feed}; with no {@code to} it is a product pill.
     *
     * @param id unique in the sheet
     */
    public record Stream(String id, String name, @Nullable End from, @Nullable End to, @Nullable Feed feed) {
        public Stream {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A feed's conditions, as the Fluid Generator's page sets them.
     *
     * @param temperature K
     * @param pressure    Pa
     * @param amounts     mol/s by component id, so a sheet survives a change of the axis
     * @param solids      what it carries as a slurry
     */
    public record Feed(double temperature, double pressure, Map<String, Double> amounts, List<Solid> solids) {
        public Feed {
            amounts = Map.copyOf(amounts);
            solids = List.copyOf(solids);
        }
    }

    /**
     * One particle grade of a slurry feed.
     *
     * @param material    the solid material's id
     * @param micrometres its diameter, as the world's particle size reads it
     * @param massFlow    kg/s
     */
    public record Solid(String material, String micrometres, double massFlow) {
        public Solid {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(micrometres, "micrometres");
        }
    }

    public Flowsheet {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(settings, "settings");
        units = List.copyOf(units);
        streams = List.copyOf(streams);
    }
}
