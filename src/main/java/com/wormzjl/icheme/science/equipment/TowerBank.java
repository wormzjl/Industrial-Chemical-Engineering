package com.wormzjl.icheme.science.equipment;

/** Parallel wet cooling cells. Rows describe placement, never water passes in series; air reaches every cell from an outer face, so two rows at most. */
public record TowerBank(int columns, int rows) {
    public static final TowerBank ONE = new TowerBank(1, 1);
    public static final int MAX_COLUMNS = 8, MAX_ROWS = 2;
    public TowerBank {
        if (columns < 1 || columns > MAX_COLUMNS || rows < 1 || rows > MAX_ROWS)
            throw new IllegalArgumentException("A cooling tower bank needs 1–8 columns and 1–2 rows");
    }
    public int cells() { return columns * rows; }
    public int[] encode() { return new int[]{columns, rows}; }
    public static TowerBank decode(int[] values) {
        if (values.length != 2) throw new IllegalArgumentException("A tower bank needs columns and rows");
        return new TowerBank(values[0], values[1]);
    }
}
