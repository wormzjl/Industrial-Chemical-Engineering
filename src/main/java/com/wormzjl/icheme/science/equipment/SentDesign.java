package com.wormzjl.icheme.science.equipment;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * A unit's design as a process simulator's Send to block stores it on the block (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md}
 * sections 3.7 and 3.10), for an equipment constructor's Import. The shape is what the constructor builds, and its kind, with a drum's
 * orientation, is the unit's type: a cabin heater is sent as a cabin heater, a sphere as a sphere. A block's member is sent under its block's
 * tag with its own key, so the constructor names it in its own language.
 *
 * @param tag    the unit's tag on the sheet, as the player named it
 * @param member a block member's key ({@code condenser}, {@code pa 1 cooler}); blank for the unit itself
 * @param count  how many stand in parallel on its row (D16): the constructor builds one, the player builds this many
 * @param shape  what the constructor builds, heading north on its least support
 * @param column a column's design, with its components and member counts; null for every other kind
 */
public record SentDesign(String tag, String member, int count, EquipmentShape shape, @Nullable ColumnDesign column) {
    /** The most designs a block holds, and the longest tag and member key. */
    public static final int MAX = 64, MAX_TAG = 64;
    /** The most units one row stands in parallel. */
    public static final int MAX_COUNT = 64;

    public SentDesign {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(shape, "shape");
        if (tag.length() > MAX_TAG || member.length() > MAX_TAG) throw new IllegalArgumentException("A tag or member key over " + MAX_TAG + " characters");
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("Count " + count + " outside 1 to " + MAX_COUNT);
        if (shape.column() != (column != null)) throw new IllegalArgumentException("A column's design goes with a column's shape, and only with one");
    }

    /** A column's design on its own: its shape on the least skirt. A calculator's Save design is one of these, with no tag. */
    public static SentDesign column(String tag, ColumnDesign column) {
        return new SentDesign(tag, "", 1, EquipmentShape.column(column.diameter(), column.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(column.diameter())),
                column);
    }
}
