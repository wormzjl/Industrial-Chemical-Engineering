package com.wormzjl.icheme.science.flowsheet;

import org.jetbrains.annotations.Nullable;

/**
 * The names of a block's members: the keys the sheet's sizing facts hold them by ({@code T-101/condenser}) and the names a
 * column's door matches its exchangers to. Saved names: never change one. The sizing stage ({@code SheetSizing}) and the tiles
 * ({@code ColumnDoor}) both read them here, so a tile does not name the sizing package.
 */
public final class Members {
    /** An air-then-water condenser's water exchanger, a member beside its air cooler, {@code condenser} (C2). */
    public static final String TRIM = "trim";
    /** A pumparound member's cooler part. */
    public static final String COOLER = "cooler";
    /** The first word of a pumparound member's name. */
    public static final String PUMPAROUND = "pa";

    private Members() {
    }

    /** A member's key: its block's id, a slash and its own. */
    public static String member(String block, String member) {
        return block + "/" + member;
    }

    /**
     * A pumparound's member, {@code pa 2 cooler}: its pumparound's id and its part, {@code cooler}, {@code pump}, {@code draw} or
     * {@code return}.
     */
    public record Pumparound(String pumparound, String part) {
        public static String name(String pumparound, String part) {
            return PUMPAROUND + " " + pumparound + " " + part;
        }

        /** The pumparound member {@code member} names, or null for a member of none. */
        public static @Nullable Pumparound of(String member) {
            String[] words = member.split(" ");
            return words.length == 3 && words[0].equals(PUMPAROUND) ? new Pumparound(words[1], words[2]) : null;
        }
    }
}
