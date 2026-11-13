package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;

/**
 * A kind's constant sizes, the constructor's rows and its moving parts, as its design states them to
 * {@link UnitDesign}'s constructor (batch 2026-11-13-codebase-review, E4). {@link UnitDesign}'s methods of the same
 * names return these fields. There are no defaults: every design states every field, so a missing one does not compile.
 *
 * <p>A design whose value depends on an argument or on the design's own state (a size-dependent length, a kind-dependent
 * row) overrides the method and keeps it; it states {@link #COMPUTED} for a number, {@code null} for a row or
 * {@code false} for {@link #moves}, and the field is never read. Its constructor's comment names those fields.
 *
 * <p>The record is built in each design's constructor, while {@link UnitDesign}'s registry is being filled (its static
 * block). Its arguments are literals, enum constants and compile-time constants, or an immutable list of a class whose
 * initialisation does not reach the registry ({@link MachineDuty#MOTOR_POWERS}): never a static that may not be set yet
 * (a design's own list, such as the batch still's pots, stays in its override).
 *
 * @param minSize    the least main size ({@link UnitDesign#minSize()})
 * @param maxSize    the largest main size
 * @param sizeStep   the main size's step
 * @param minLength  the least second size ({@link UnitDesign#minLength(double)}); 1 where the kind has none
 * @param maxLength  the largest second size; 1 where the kind has none
 * @param minSupport the least support, m ({@link UnitDesign#minSupport(double, int)})
 * @param maxSupport the largest support, m; {@link EquipmentShape#MAX_SUPPORT} unless the kind has its own
 * @param orientation how the unit stands
 * @param sizeRow    the constructor's row for the main size
 * @param lengthRow  the constructor's row for the second size, or {@code null} where the kind has none
 * @param supportRow the constructor's row for the support, or {@code null} where it is fixed
 * @param moves      whether units of the kind have moving parts
 */
record Sizes(double minSize, double maxSize, double sizeStep, int minLength, int maxLength, int minSupport,
             int maxSupport, Orientation orientation, String sizeRow, String lengthRow, String supportRow,
             boolean moves) {
    /** A number field whose method the design overrides: never read. */
    static final int COMPUTED = 0;
}
