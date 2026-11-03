package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.science.equipment.SentDesign;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The tags of the designs Send to block stores on a process simulator (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section
 * 3.7): one compound a design, its fields by name. They are equipment facts (AGENTS.md): append-only, a missing field at its default, the
 * shape read through the shape's own repair ({@link EquipmentShellBlockEntity#readShape}) and a column's design through {@link ColumnDesignTags}.
 * An entry that does not read, a kind this build no longer has above all, is kept as saved and written back, so no change of code drops it.
 * The wire carries the same tags; the server takes an upload only when it reads whole, with no repair.
 */
public final class SentDesignTags {
    private SentDesignTags() {
    }

    /**
     * What a list of saved designs reads to.
     *
     * @param designs the designs that read
     * @param unread  the entries that did not, as saved
     * @param repairs what was repaired or not read, each named
     */
    public record Read(List<SentDesign> designs, List<CompoundTag> unread, List<String> repairs) {
        public Read {
            designs = List.copyOf(designs);
            unread = List.copyOf(unread);
            repairs = List.copyOf(repairs);
        }

        /** Whether it read whole, as an upload must. */
        public boolean whole() {
            return unread.isEmpty() && repairs.isEmpty();
        }
    }

    public static CompoundTag write(SentDesign design) {
        var tag = new CompoundTag();
        tag.putString("Tag", design.tag());
        if (!design.member().isEmpty()) tag.putString("Member", design.member());
        tag.putInt("Count", design.count());
        var shape = new CompoundTag();
        EquipmentShellBlockEntity.writeShape(shape, design.shape());
        tag.put("Shape", shape);
        if (design.column() != null) tag.put("Column", ColumnDesignTags.write(design.column()));
        return tag;
    }

    /** {@code designs}, then the entries kept unread. */
    public static ListTag write(List<SentDesign> designs, List<CompoundTag> unread) {
        var list = new ListTag();
        for (var design : designs) list.add(write(design));
        for (var entry : unread) list.add(entry.copy());
        return list;
    }

    /** Never throws. */
    public static Read read(ListTag list) {
        var designs = new ArrayList<SentDesign>();
        var unread = new ArrayList<CompoundTag>();
        var repairs = new ArrayList<String>();
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            if (designs.size() == SentDesign.MAX) {
                unread.add(entry);
                repairs.add("design " + i + ": over " + SentDesign.MAX);
                continue;
            }
            var shape = EquipmentShellBlockEntity.readShape(entry.getCompound("Shape"));
            for (var repair : shape.repairs()) repairs.add("design " + i + ": " + repair);
            if (shape.shape() == null) {
                unread.add(entry);
                repairs.add("design " + i + ": kind " + shape.retired() + " retired");
                continue;
            }
            try {
                var column = entry.contains("Column", Tag.TAG_COMPOUND) ? ColumnDesignTags.read(entry.getCompound("Column")) : null;
                int count = entry.contains("Count", Tag.TAG_INT) ? entry.getInt("Count") : 1;
                designs.add(new SentDesign(entry.getString("Tag"), entry.getString("Member"), count, shape.shape(), column));
            } catch (IllegalArgumentException invalid) {
                unread.add(entry);
                repairs.add("design " + i + ": " + invalid.getMessage());
            }
        }
        return new Read(designs, unread, repairs);
    }
}
