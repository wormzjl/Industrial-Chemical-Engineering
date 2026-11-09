package com.wormzjl.icheme.science.material;

import com.google.gson.JsonParser;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Solid fuel records (kind {@code fuels}, batch 2026-11-09-batch-distiller, owner B1 B): the heat of an item burnt in a
 * firebox is its mass times its lower heating value, its mass the fuel's bulk density over the items a cubic metre holds (a
 * block of loose fuel is 9 items and 1 m³; a log is one block). Each record names one item or one item tag and declares its
 * own {@code revision}; an edit to its values bumps it. An item without a record burns at its burn ticks times a configured
 * joules per tick ({@link #energy}).
 */
public final class SolidFuelCatalog {
    private final Map<String, SolidFuel> fuels;
    private final Map<String, SolidFuel> byItem, byTag;

    private SolidFuelCatalog(Map<String, SolidFuel> fuels) {
        this.fuels = Map.copyOf(fuels);
        var items = new TreeMap<String, SolidFuel>();
        var tags = new TreeMap<String, SolidFuel>();
        for (var fuel : new TreeMap<>(fuels).values()) {
            var target = fuel.tag() ? tags : items;
            if (target.putIfAbsent(fuel.target(), fuel) != null)
                throw new IllegalArgumentException("fuels: " + fuel.id() + " names " + (fuel.tag() ? "tag " : "item ") + fuel.target() + ", which another record names");
        }
        byItem = Map.copyOf(items);
        byTag = Map.copyOf(tags);
    }

    /** The records by id. */
    public Map<String, SolidFuel> fuels() {
        return fuels;
    }

    /**
     * One fuel: its record id and revision, the item or tag it names ({@code target}, a tag when {@code tag}), its bulk
     * density (kg/m³), the items a cubic metre of it holds and its lower heating value (J/kg).
     */
    public record SolidFuel(String id, String revision, String target, boolean tag, double bulkDensity, double itemsPerCubicMetre,
                            double lowerHeatingValue) {
        public SolidFuel {
            Objects.requireNonNull(id);
            Objects.requireNonNull(revision);
            Objects.requireNonNull(target);
            if (!target.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !(bulkDensity > 0) || !Double.isFinite(bulkDensity) || !(itemsPerCubicMetre > 0)
                    || !Double.isFinite(itemsPerCubicMetre) || !(lowerHeatingValue > 0) || !Double.isFinite(lowerHeatingValue))
                throw new IllegalArgumentException("Invalid solid fuel " + id);
        }

        /** One item's mass, kg. */
        public double mass() {
            return bulkDensity / itemsPerCubicMetre;
        }

        /** One item's heat, J: its mass times the lower heating value. */
        public double energy() {
            return mass() * lowerHeatingValue;
        }
    }

    /** The record of {@code item} (an item id), else of the first of its {@code tags} in id order that has one; null for none. */
    public SolidFuel of(String item, Collection<String> tags) {
        var own = byItem.get(item);
        if (own != null) return own;
        for (var tag : new java.util.TreeSet<>(tags)) {
            var fuel = byTag.get(tag);
            if (fuel != null) return fuel;
        }
        return null;
    }

    /**
     * One item's heat, J: its record's ({@link #of}), else {@code burnTicks} (the game's fuel value) times
     * {@code joulesPerBurnTick}.
     */
    public double energy(String item, Collection<String> tags, int burnTicks, double joulesPerBurnTick) {
        var fuel = of(item, tags);
        if (fuel != null) return fuel.energy();
        return Math.max(0, burnTicks) * joulesPerBurnTick;
    }

    /** The records among {@code resources} (path to JSON text) under {@code /materials/fuels/}; a refusal names the file and the field. */
    public static SolidFuelCatalog parse(Map<String, String> resources) {
        var fuels = new TreeMap<String, SolidFuel>();
        for (var entry : new TreeMap<>(resources).entrySet()) if (entry.getKey().contains("/materials/fuels/")) {
            SolidFuel fuel;
            try {
                var o = JsonParser.parseString(entry.getValue()).getAsJsonObject();
                boolean item = o.has("item"), tag = o.has("tag");
                if (item == tag) throw new IllegalArgumentException("item, tag: exactly one is required");
                fuel = new SolidFuel(JsonFields.text(o, "id"), JsonFields.revision(o, "revision"), JsonFields.text(o, item ? "item" : "tag"), tag,
                        JsonFields.positive(o, "bulk_density_kg_per_m3"), JsonFields.positive(o, "items_per_m3"),
                        JsonFields.positive(o, "lower_heating_value_j_per_kg"));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(entry.getKey() + ": " + invalid.getMessage(), invalid);
            }
            if (fuels.putIfAbsent(fuel.id(), fuel) != null) throw new IllegalArgumentException(entry.getKey() + ": duplicate fuel " + fuel.id());
        }
        return new SolidFuelCatalog(fuels);
    }
}
