package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.ShapeRepair;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * The tags of a saved column design (batch 2026-10-26-multi-machine-framework): the trays, the diameter and the
 * components, whose kinds are saved by name; and the side nozzles a player turned (finding F3), roles by name. A column
 * shell's and a constructor's components and turns are equipment facts (AGENTS.md): their fields are append-only and read
 * through {@link ShapeRepair}.
 */
public final class ColumnDesignTags {
    private ColumnDesignTags() {
    }

    public static CompoundTag write(ColumnDesign design) {
        var tag = writeComponents(design.components());
        tag.putIntArray("Trays", design.trays().encode());
        tag.putDouble("Diameter", design.diameter());
        if (design.trays().operating() != null) tag.put("Operating", writeOperating(design.trays().operating()));
        return tag;
    }

    /** @throws IllegalArgumentException for a missing, invalid or inconsistent field */
    public static ColumnDesign read(CompoundTag tag) {
        if (!tag.contains("Trays", Tag.TAG_INT_ARRAY) || !tag.contains("Diameter", Tag.TAG_DOUBLE)) throw new IllegalArgumentException("design trays or diameter missing");
        int[] trays = tag.getIntArray("Trays");
        if (trays.length > ColumnTrays.MAX_ENCODED) throw new IllegalArgumentException("design trays overlong");
        var operating = savedOperating(tag, "Operating");
        return new ColumnDesign(ColumnTrays.decode(trays).withComponents(saved(tag).resolve()).withOperating(operating == null ? null : operating.resolve()),
                tag.getDouble("Diameter"));
    }

    /**
     * The operating point's fields (batch 2026-10-28-column-operation): the package, assay and component ids, the feed
     * rates, the scalar values by name, the steam (rates and temperatures), the draw rates, the pumparound duties and their
     * splits by name.
     */
    public static CompoundTag writeOperating(ColumnOperatingPoint point) {
        var tag = new CompoundTag();
        tag.putString("Package", point.packageId());
        tag.putString("Assay", point.assayId());
        var ids = new ListTag();
        for (var id : point.components()) ids.add(StringTag.valueOf(id));
        tag.put("Components", ids);
        tag.put("Feed", doubles(point.feed().stream().mapToDouble(Double::doubleValue).toArray()));
        tag.putDouble("FeedTemperature", point.feedTemperature());
        tag.putDouble("TopPressure", point.topPressure());
        tag.putDouble("StageDrop", point.stageDrop());
        tag.putDouble("CondenserTemperature", point.condenserTemperature());
        tag.putDouble("RefluxRatio", point.refluxRatio());
        tag.putDouble("ReboilerDuty", point.reboilerDuty());
        tag.put("SteamFlows", doubles(point.steam().stream().mapToDouble(ColumnOperatingPoint.Steam::flow).toArray()));
        tag.put("SteamTemperatures", doubles(point.steam().stream().mapToDouble(ColumnOperatingPoint.Steam::temperature).toArray()));
        tag.put("Draws", doubles(point.draws().stream().mapToDouble(Double::doubleValue).toArray()));
        tag.put("PumparoundDuties", doubles(point.pumparounds().stream().mapToDouble(ColumnOperatingPoint.Pumparound::duty).toArray()));
        var splits = new ListTag();
        for (var p : point.pumparounds()) splits.add(StringTag.valueOf(p.split().name()));
        tag.put("PumparoundSplits", splits);
        return tag;
    }

    /**
     * The operating point saved under {@code key} in {@code parent}, as written: null when the key is absent (a design
     * without one); a field that is not a compound, or a missing value, reads as one the point refuses, which the shape's
     * reader names as repaired.
     */
    public static @Nullable ShapeRepair.SavedOperating savedOperating(CompoundTag parent, String key) {
        if (!parent.contains(key)) return null;
        var tag = parent.contains(key, Tag.TAG_COMPOUND) ? parent.getCompound(key) : new CompoundTag();
        int limit = com.wormzjl.icheme.science.column.v3.V3ComponentBasis.MAX_COMPONENTS + 1;
        return new ShapeRepair.SavedOperating(tag.getString("Package"), tag.getString("Assay"), strings(tag, "Components", limit), readDoubles(tag, "Feed", limit),
                number(tag, "FeedTemperature"), number(tag, "TopPressure"), number(tag, "StageDrop"), number(tag, "CondenserTemperature"),
                number(tag, "RefluxRatio"), number(tag, "ReboilerDuty"), readDoubles(tag, "SteamFlows", ColumnTrays.MAX_STEAM + 1),
                readDoubles(tag, "SteamTemperatures", ColumnTrays.MAX_STEAM + 1), readDoubles(tag, "Draws", ColumnTrays.MAX_DRAWS + 1),
                readDoubles(tag, "PumparoundDuties", ColumnTrays.MAX_PUMPAROUNDS + 1), strings(tag, "PumparoundSplits", ColumnTrays.MAX_PUMPAROUNDS + 1));
    }

    private static ListTag doubles(double[] values) {
        var list = new ListTag();
        for (double v : values) list.add(net.minecraft.nbt.DoubleTag.valueOf(v));
        return list;
    }

    private static double[] readDoubles(CompoundTag tag, String key, int limit) {
        if (!(tag.get(key) instanceof ListTag list)) return new double[] {Double.NaN};
        double[] out = new double[Math.min(list.size(), limit)];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i) instanceof net.minecraft.nbt.NumericTag n ? n.getAsDouble() : Double.NaN;
        return out;
    }

    private static List<String> strings(CompoundTag tag, String key, int limit) {
        var out = new ArrayList<String>();
        if (tag.get(key) instanceof ListTag list)
            for (int i = 0; i < Math.min(list.size(), limit); i++) out.add(list.get(i) instanceof StringTag s ? s.getAsString() : "");
        return out;
    }

    private static double number(CompoundTag tag, String key) {
        return tag.get(key) instanceof net.minecraft.nbt.NumericTag n ? n.getAsDouble() : Double.NaN;
    }

    /** The components' fields: the condenser, the reflux pump, the pumparound pumps by name, and the two flags. */
    public static CompoundTag writeComponents(ColumnComponents components) {
        var tag = new CompoundTag();
        tag.putString("Condenser", components.condenser().name());
        tag.putString("RefluxPump", components.refluxPump().name());
        var pumps = new ListTag();
        for (var pump : components.pumparoundPumps()) pumps.add(StringTag.valueOf(pump.name()));
        tag.put("PumparoundPumps", pumps);
        tag.putBoolean("Ejector", components.ejector());
        tag.putBoolean("Kettle", components.kettle());
        return tag;
    }

    /** The turned nozzles' fields: one compound per turn, its role by name, its number and the side it faces. */
    public static ListTag writeTurns(List<ColumnTrays.Turn> turns) {
        var list = new ListTag();
        for (var turn : turns) {
            var tag = new CompoundTag();
            tag.putString("Role", turn.role().name());
            tag.putInt("Index", turn.index());
            tag.putInt("Side", turn.side());
            list.add(tag);
        }
        return list;
    }

    /**
     * The turns saved under {@code key} in {@code parent}, as written: none when the key is absent (a column whose nozzles
     * all take their sides by themselves); an entry that is not a compound, or a field that is not a list, reads as a
     * turn with no role, which the reader drops and names.
     */
    public static List<ShapeRepair.SavedTurn> savedTurns(CompoundTag parent, String key) {
        if (!parent.contains(key)) return List.of();
        if (!(parent.get(key) instanceof ListTag list)) return List.of(new ShapeRepair.SavedTurn("", 0, 0));
        var turns = new ArrayList<ShapeRepair.SavedTurn>();
        for (int i = 0; i < Math.min(list.size(), ColumnTrays.MAX_TURNS + 1); i++)
            turns.add(list.get(i) instanceof CompoundTag tag ? new ShapeRepair.SavedTurn(tag.getString("Role"), tag.getInt("Index"), tag.getInt("Side"))
                    : new ShapeRepair.SavedTurn("", 0, 0));
        return turns;
    }

    /**
     * The component design saved under {@code key} in {@code parent}, as written: null when the key is absent (a column
     * without one); a field that is not a compound reads as one with no kinds, which the shape's reader names as repaired.
     */
    public static @Nullable ShapeRepair.SavedComponents saved(CompoundTag parent, String key) {
        if (!parent.contains(key)) return null;
        return saved(parent.contains(key, Tag.TAG_COMPOUND) ? parent.getCompound(key) : new CompoundTag());
    }

    /** The component fields of {@code tag} as written, before any rule reads them; a missing kind reads as an empty name. */
    private static ShapeRepair.SavedComponents saved(CompoundTag tag) {
        var pumps = new ArrayList<String>();
        if (tag.get("PumparoundPumps") instanceof ListTag list)
            for (int i = 0; i < Math.min(list.size(), ColumnTrays.MAX_PUMPAROUNDS + 1); i++)
                pumps.add(list.get(i) instanceof StringTag name ? name.getAsString() : String.valueOf(list.get(i)));
        return new ShapeRepair.SavedComponents(tag.getString("Condenser"), tag.getString("RefluxPump"), pumps, tag.getBoolean("Ejector"), tag.getBoolean("Kettle"));
    }
}
