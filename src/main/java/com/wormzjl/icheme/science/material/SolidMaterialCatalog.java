package com.wormzjl.icheme.science.material;

import com.google.gson.JsonParser;
import java.util.*;

/** Separate solid-property identity; adding solids never changes the conserved fluid axis. */
public final class SolidMaterialCatalog {
    private final Map<String, SolidMaterial> materials;
    private SolidMaterialCatalog(Map<String, SolidMaterial> materials) { this.materials = Map.copyOf(materials); }
    public Map<String, SolidMaterial> materials() { return materials; }
    public SolidMaterial require(String id) {
        var material = materials.get(id);
        if (material == null) throw new IllegalArgumentException("Unknown solid material: " + id);
        return material;
    }
    static SolidMaterialCatalog parse(Map<String, String> resources) {
        var solids = new TreeMap<String, SolidMaterial>();
        for (var entry : resources.entrySet()) if (entry.getKey().contains("/materials/solids/")) {
            // Read through the material data's field reader, so a missing or ill-typed field is refused with the file and
            // the field it names, never a NullPointerException.
            SolidMaterial material;
            try {
                var o = JsonParser.parseString(entry.getValue()).getAsJsonObject();
                material = new SolidMaterial(JsonFields.text(o, "id"), JsonFields.text(o, "revision"),
                        JsonFields.number(o, "density_kg_per_m3"), JsonFields.number(o, "heat_capacity_j_per_kg_kelvin"));
            } catch (RuntimeException invalid) { throw new IllegalArgumentException(entry.getKey() + ": " + invalid.getMessage(), invalid); }
            if (solids.putIfAbsent(material.id(), material) != null) throw new IllegalArgumentException("Duplicate solid material");
        }
        return new SolidMaterialCatalog(solids);
    }
    /**
     * An incompressible solid of constant density and heat capacity; the sensible energy a population stores is
     * {@code c (T - 298.15)} per kg, zero at 298.15 K. Two kinds (owner decisions D22 (5) and D23): a custom solid (the
     * {@code solids} records, {@code crystal} null), and a crystal formed by a phase transition of the fluid, whose
     * {@link Crystal} record names the fluid component it is made of and its energy offset. Both share every population
     * rule of the network's {@code SolidInventory} (which also checks a saved population against this catalog, {@code
     * SolidInventory.validate}); only a vessel's refresh exchanges a crystal's mass with its component.
     */
    public record SolidMaterial(String id, String revision, double density, double heatCapacity, Crystal crystal) {
        public static final double REFERENCE_TEMPERATURE = 298.15;
        /**
         * A crystal's link to the fluid: {@code component}, the fluid basis component it is made of, and
         * {@code energyOffset} {@code u0} (J/kg), so that its true specific internal energy on the fluid network's energy
         * datum is {@code c (T - 298.15) + u0}. A population stores only the sensible part, so a vessel's true energy is its
         * stored energy plus {@code u0} times the crystal mass it holds; the offset moves with the mass, and the vessel
         * refresh books it where crystal mass forms or melts.
         */
        public record Crystal(String component, double energyOffset) {
            public Crystal {
                Objects.requireNonNull(component);
                if (component.isBlank() || !Double.isFinite(energyOffset)) throw new IllegalArgumentException("Invalid crystal record");
            }
        }
        /** A custom solid. */
        public SolidMaterial(String id, String revision, double density, double heatCapacity) { this(id, revision, density, heatCapacity, null); }
        public SolidMaterial {
            Objects.requireNonNull(id); Objects.requireNonNull(revision);
            if (!id.matches("[a-z][a-z0-9_.-]*:[a-z0-9_./-]+") || id.length()>128 || revision.isBlank() || revision.length()>128
                    || !Double.isFinite(density) || density <= 0 || !Double.isFinite(heatCapacity) || heatCapacity <= 0)
                throw new IllegalArgumentException("Invalid solid material");
        }
        public double specificInternalEnergy(double temperature) {
            if (!Double.isFinite(temperature) || temperature <= 0) throw new IllegalArgumentException("Invalid solid temperature");
            return heatCapacity * (temperature - REFERENCE_TEMPERATURE);
        }
    }
}