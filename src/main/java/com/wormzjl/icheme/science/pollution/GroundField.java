package com.wormzjl.icheme.science.pollution;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.pollution.GroundLedger.Total;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The ground pollution of one dimension (batch 2026-11-18-ground-pollution, plan G1; owner: "store ground pollution on a
 * chunk basis instead of 16block cubes", then "ground only have one type of pollution"): one entry per chunk column
 * ({@link MapColumns#column}), with one amount in air-mole equivalents and an inventory of the compounds that lie there,
 * in moles.
 *
 * <p>The amount carries the hazard; a column's index is {@code amount / reference} ({@link #GROUND_REFERENCE}: 100 open
 * air cubes, so index 1 keeps the meaning "at the reference hazard"). The inventory keeps components exact so a water
 * intake can draw them back. A compound moves and decays by the same fraction as its column's amount, so moles close per
 * compound.
 *
 * <p>Immutable. {@link #step} is pure and deterministic: columns are visited in ascending key order, deposits are summed
 * in a fixed order. It holds no Minecraft type.
 */
public final class GroundField {
    /** The amount of a column at index 1: 100 open cubes of air ({@code 100 × CUBE_AIR_MOLES}, 1.674e7). */
    public static final double GROUND_REFERENCE = 100 * PollutionField.CUBE_AIR_MOLES;
    /** The water of a chunk column 4 m deep (plan G5): 256 m² × 4 m × 55 500 mol/m³ = 5.68e7 mol. */
    public static final double REFERENCE_WATER = 256.0 * 4.0 * 55_500.0;

    private static final double LN2 = 0.6931471805599453;
    private static final int[] DX = {-1, 1, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1};

    public static final GroundField EMPTY = new GroundField(new long[0], new double[0], new long[0], new String[0], new double[0]);

    private final long[] columns;
    private final double[] amounts;
    /** The compound entries, sorted by column, then name. */
    private final long[] entryColumns;
    private final String[] entryNames;
    private final double[] entryMoles;

    private GroundField(long[] columns, double[] amounts, long[] entryColumns, String[] entryNames, double[] entryMoles) {
        this.columns = columns;
        this.amounts = amounts;
        this.entryColumns = entryColumns;
        this.entryNames = entryNames;
        this.entryMoles = entryMoles;
    }

    /**
     * Moles of one compound.
     *
     * @param name the compound's id, kept as saved even when no package holds it (a fact)
     */
    public record Compound(String name, double moles) {
        public Compound {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) throw new IllegalArgumentException("compound name is empty");
            if (!(moles >= 0.0) || !Double.isFinite(moles)) {
                throw new IllegalArgumentException("compound moles must be finite and not negative: " + moles);
            }
        }
    }

    /** One compound entry of a column, as saved. */
    public record Entry(long column, String name, double moles) {}

    /**
     * What lands in a column at the start of a step: the only way in.
     *
     * @param amount the sum of what arrives over the air channels, in air-mole equivalents, finite and not negative
     * @param compounds what lies there
     */
    public record Deposit(long column, double amount, List<Compound> compounds) {
        public Deposit {
            if (!(amount >= 0.0) || !Double.isFinite(amount)) {
                throw new IllegalArgumentException("deposit amount must be finite and not negative: " + amount);
            }
            compounds = List.copyOf(compounds);
        }

        /** A deposit with no compound. */
        public static Deposit of(long column, double amount) {
            return new Deposit(column, amount, List.of());
        }
    }

    /** The outcome of a step. */
    public record Result(GroundField field, GroundLedger ledger) {
        public Result {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(ledger, "ledger");
        }
    }

    /** The outcome of a draw: the field and ledger after it, the compounds taken (moles) and the amount taken with them. */
    public record Drawn(GroundField field, GroundLedger ledger, List<Compound> compounds, double amount) {}

    /** One compound of a column's ground water: its mole fraction {@code moles / REFERENCE_WATER}. */
    public record WaterShare(String name, double fraction) {
    }

    /**
     * A field from saved arrays: {@code columns} strictly ascending, {@code amounts} one per column, finite and not
     * negative; {@code entries} each in a listed column, no two with the same column and name. Throws
     * {@link IllegalArgumentException} naming the fault.
     */
    public static GroundField of(long[] columns, double[] amounts, List<Entry> entries) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(amounts, "amounts");
        Objects.requireNonNull(entries, "entries");
        if (amounts.length != columns.length) {
            throw new IllegalArgumentException("ground of " + columns.length + " columns needs " + columns.length + " amounts, got " + amounts.length);
        }
        for (int i = 1; i < columns.length; i++) {
            if (columns[i] <= columns[i - 1]) throw new IllegalArgumentException("ground columns not strictly ascending at " + i);
        }
        for (double a : amounts) {
            if (!(a >= 0.0) || !Double.isFinite(a)) throw new IllegalArgumentException("ground amount must be finite and not negative: " + a);
        }
        var work = new Work();
        for (int i = 0; i < columns.length; i++) work.column(columns[i]).a += amounts[i];
        for (var e : entries) {
            Objects.requireNonNull(e, "entry");
            new Compound(e.name(), e.moles());
            var col = work.columns.get(e.column());
            if (col == null) throw new IllegalArgumentException("compound " + e.name() + " in an unlisted column");
            if (col.inventory().put(e.name(), e.moles()) != null) throw new IllegalArgumentException("compound " + e.name() + " twice in a column");
        }
        return work.field();
    }

    public int size() {
        return columns.length;
    }

    public long column(int i) {
        return columns[i];
    }

    /** The columns, ascending (a copy). */
    public long[] columns() {
        return columns.clone();
    }

    /** The amounts, one per column (a copy). */
    public double[] amounts() {
        return amounts.clone();
    }

    /** The compound entries, sorted by column and name. */
    public List<Entry> entries() {
        var out = new ArrayList<Entry>(entryNames.length);
        for (int i = 0; i < entryNames.length; i++) out.add(new Entry(entryColumns[i], entryNames[i], entryMoles[i]));
        return out;
    }

    public int find(long column) {
        return Arrays.binarySearch(columns, column);
    }

    /** A column's amount; 0 when the column is absent. */
    public double amount(long column) {
        int i = find(column);
        return i < 0 ? 0.0 : amounts[i];
    }

    /** A column's index: its amount over the reference. */
    public double index(long column, GroundSettings settings) {
        return amount(column) / settings.reference();
    }

    /** Moles of a compound in a column. */
    public double moles(long column, String compound) {
        for (int i = firstEntry(column); i < entryColumns.length && entryColumns[i] == column; i++) {
            if (entryNames[i].equals(compound)) return entryMoles[i];
        }
        return 0.0;
    }

    /** The compounds of a column, by name. */
    public Map<String, Double> compounds(long column) {
        var out = new TreeMap<String, Double>();
        for (int i = firstEntry(column); i < entryColumns.length && entryColumns[i] == column; i++) out.put(entryNames[i], entryMoles[i]);
        return out;
    }

    /**
     * What a column's ground gives its water (plan G5, the intake's status line): each compound's moles over
     * {@link #REFERENCE_WATER}, the largest first (name order on a tie). Display only: nothing is drawn.
     */
    public List<WaterShare> waterShares(long column) {
        var out = new ArrayList<WaterShare>();
        for (var e : compounds(column).entrySet()) if (e.getValue() > 0.0) out.add(new WaterShare(e.getKey(), e.getValue() / REFERENCE_WATER));
        out.sort(Comparator.comparingDouble(WaterShare::fraction).reversed().thenComparing(WaterShare::name));
        return out;
    }

    private int firstEntry(long column) {
        int lo = 0, hi = entryColumns.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (entryColumns[mid] < column) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** The sum of the amount over every column. */
    public double stored() {
        double sum = 0.0;
        for (double a : amounts) sum += a;
        return sum;
    }

    /** The moles of a compound over every column. */
    public double stored(String compound) {
        double sum = 0.0;
        for (int i = 0; i < entryNames.length; i++) if (entryNames[i].equals(compound)) sum += entryMoles[i];
        return sum;
    }

    /**
     * One pass of the ground over {@code seconds} of process time, in this order:
     * <ol>
     *   <li>land: deposits land in their columns, loaded or not ({@code deposited});</li>
     *   <li>spread: across each face between a loaded column and a loaded neighbour, once, simultaneously,
     *       {@code F = k (A_a − A_b)} with {@code k = min(1/5, spreadShare × seconds / nominalSeconds)}, times
     *       {@code wetSpread} in rain and {@code waterSpread} where either column is water (then capped at 1/5 again); the
     *       compounds move by the share {@code F / A} of the donor; a column not yet in the field takes a flow only when its
     *       inflows give it an index of at least {@code cutoff};</li>
     *   <li>decay: loaded columns, half-life {@code halfLifeSeconds} (÷ {@code washout} in rain), the amount and the
     *       compounds by the same factor ({@code decayed});</li>
     *   <li>truncate: loaded columns below {@code cutoff} leave, with their compounds ({@code decayed});</li>
     *   <li>cap: above {@code maximumColumns}, loaded columns with the lowest amount leave first ({@code capped}).</li>
     * </ol>
     * Unloaded columns keep their amounts and pass nothing.
     */
    public static Result step(GroundField field, GroundLedger ledger, List<Deposit> deposits, GroundEnvironment environment,
            double seconds, GroundSettings settings) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(deposits, "deposits");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(settings, "settings");
        if (!(seconds > 0.0) || !Double.isFinite(seconds)) throw new IllegalArgumentException("seconds must be finite and positive: " + seconds);
        var book = ledger.book();
        var work = Work.of(field);
        land(work, deposits, book);
        spread(work, environment, seconds, settings, book);
        decay(work, environment, seconds, settings, book);
        truncate(work, environment, settings, book);
        cap(work, environment, settings, book);
        var next = work.field();
        next.requireFinite();
        return new Result(next, book.ledger());
    }

    /**
     * Draws water's share of a column's inventory (plan G5): {@code fraction = min(1, waterMoles / referenceWater)} of
     * every compound {@code held} lists, booked {@code drawn}, with the column's amount in proportion to the moles that
     * leave; the other compounds, and their share of the amount, stay. The same field when nothing is there.
     */
    public static Drawn draw(GroundField field, GroundLedger ledger, long column, double waterMoles, double referenceWater,
            java.util.function.Predicate<String> held) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(held, "held");
        if (!(waterMoles >= 0.0) || !Double.isFinite(waterMoles) || !(referenceWater > 0.0)) {
            throw new IllegalArgumentException("draw of " + waterMoles + " mol against " + referenceWater);
        }
        double fraction = Math.min(1.0, waterMoles / referenceWater);
        int at = field.find(column);
        if (at < 0 || !(fraction > 0.0)) return new Drawn(field, ledger, List.of(), 0.0);
        var work = Work.of(field);
        var col = work.columns.get(column);
        var book = ledger.book();
        var compounds = new ArrayList<Compound>();
        double total = 0.0, leaving = 0.0;
        if (col.inventory != null) {
            for (var e : col.inventory.entrySet()) {
                total += e.getValue();
                if (!held.test(e.getKey())) continue;
                double moles = e.getValue() * fraction;
                leaving += e.getValue();
                e.setValue(e.getValue() - moles);
                if (moles > 0.0) compounds.add(new Compound(e.getKey(), moles));
                book.compound(e.getKey(), Total.DRAWN, moles);
            }
        }
        double amount = 0.0;
        if (total > 0.0 && leaving > 0.0) {
            amount = col.a * fraction * (leaving / total);
            col.a -= amount;
            book.amount(Total.DRAWN, amount);
        }
        return new Drawn(work.field(), book.ledger(), List.copyOf(compounds), amount);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The stages.

    private static final Comparator<Deposit> DEPOSIT_ORDER = (a, b) -> {
        int c = Long.compare(a.column(), b.column());
        if (c == 0) c = Long.compare(Double.doubleToLongBits(a.amount()), Double.doubleToLongBits(b.amount()));
        if (c == 0) c = Integer.compare(a.compounds().size(), b.compounds().size());
        for (int i = 0; c == 0 && i < a.compounds().size(); i++) {
            var x = a.compounds().get(i);
            var y = b.compounds().get(i);
            c = x.name().compareTo(y.name());
            if (c == 0) c = Long.compare(Double.doubleToLongBits(x.moles()), Double.doubleToLongBits(y.moles()));
        }
        return c;
    };

    private static void land(Work work, List<Deposit> deposits, GroundLedger.Book book) {
        var sorted = deposits.toArray(new Deposit[0]);
        Arrays.sort(sorted, DEPOSIT_ORDER);
        for (var d : sorted) {
            boolean any = d.amount() > 0.0;
            for (var c : d.compounds()) any |= c.moles() > 0.0;
            if (!any) continue;
            var col = work.column(d.column());
            col.a += d.amount();
            book.amount(Total.DEPOSITED, d.amount());
            for (var c : d.compounds()) {
                if (!(c.moles() > 0.0)) continue;
                col.inventory().merge(c.name(), c.moles(), Double::sum);
                book.compound(c.name(), Total.DEPOSITED, c.moles());
            }
        }
    }

    /** One transfer of a step's spread: {@code amount}, the donor's share {@code share}. */
    private record Transfer(long from, long to, double amount, double share) {}

    /** The compound moles that move with a transfer. */
    private record CompoundMove(long from, long to, String name, double moles) {}

    private static void spread(Work work, GroundEnvironment environment, double seconds, GroundSettings settings, GroundLedger.Book book) {
        double base = Math.min(GroundSettings.MAXIMUM_FACE_SHARE, settings.spreadShare() * seconds / settings.nominalSeconds());
        if (!(base > 0.0)) return;
        double wet = environment.raining() ? settings.wetSpread() : 1.0;
        var transfers = new ArrayList<Transfer>();
        var newInflow = new TreeMap<Long, Double>();
        for (var entry : work.columns.entrySet()) {
            long key = entry.getKey();
            if (!environment.loaded(key)) continue;
            var a = entry.getValue();
            int x = MapColumns.x(key), z = MapColumns.z(key);
            for (int f = 0; f < 4; f++) {
                long other = MapColumns.column(x + DX[f], z + DZ[f]);
                var b = work.columns.get(other);
                if (b != null && other < key) continue;
                if (!environment.loaded(other)) continue;
                double k = base * wet;
                if (environment.water(key) || environment.water(other)) k *= settings.waterSpread();
                k = Math.min(GroundSettings.MAXIMUM_FACE_SHARE, k);
                double ab = b == null ? 0.0 : b.a;
                double flow = k * (a.a - ab);
                if (flow > 0.0) {
                    transfers.add(new Transfer(key, other, flow, flow / a.a));
                    if (b == null) newInflow.merge(other, flow, Double::sum);
                } else if (flow < 0.0) {
                    transfers.add(new Transfer(other, key, -flow, -flow / ab));
                }
            }
        }
        if (transfers.isEmpty()) return;
        // A column not in the field takes a flow only when its inflows reach the cutoff index.
        var moves = new ArrayList<Transfer>();
        var compoundMoves = new ArrayList<CompoundMove>();
        for (var t : transfers) {
            var inflow = newInflow.get(t.to());
            if (inflow != null && inflow / settings.reference() < settings.cutoff()) continue;
            moves.add(t);
            var inventory = work.columns.get(t.from()).inventory;
            if (inventory == null) continue;
            for (var e : inventory.entrySet()) {
                double moles = e.getValue() * t.share();
                if (moles > 0.0) compoundMoves.add(new CompoundMove(t.from(), t.to(), e.getKey(), moles));
            }
        }
        // Applied after every flow is known, so the step is simultaneous.
        for (var m : moves) {
            work.columns.get(m.from()).a -= m.amount();
            work.column(m.to()).a += m.amount();
        }
        for (var m : compoundMoves) {
            work.columns.get(m.from()).inventory().merge(m.name(), -m.moles(), Double::sum);
            work.column(m.to()).inventory().merge(m.name(), m.moles(), Double::sum);
        }
        for (var col : work.columns.values()) col.clampNegatives(book);
    }

    private static void decay(Work work, GroundEnvironment environment, double seconds, GroundSettings settings, GroundLedger.Book book) {
        double halfLife = settings.halfLifeSeconds() / (environment.raining() ? settings.washout() : 1.0);
        double keep = DeterministicMath.exp(-LN2 * seconds / halfLife);
        for (var entry : work.columns.entrySet()) {
            if (!environment.loaded(entry.getKey())) continue;
            var col = entry.getValue();
            double gone = col.a - col.a * keep;
            col.a -= gone;
            book.amount(Total.DECAYED, gone);
            if (col.inventory == null) continue;
            for (var e : col.inventory.entrySet()) {
                double lost = e.getValue() - e.getValue() * keep;
                e.setValue(e.getValue() - lost);
                book.compound(e.getKey(), Total.DECAYED, lost);
            }
        }
    }

    private static void truncate(Work work, GroundEnvironment environment, GroundSettings settings, GroundLedger.Book book) {
        var gone = new ArrayList<Long>();
        for (var entry : work.columns.entrySet()) {
            if (!environment.loaded(entry.getKey())) continue;
            var col = entry.getValue();
            if (col.a / settings.reference() >= settings.cutoff()) continue;
            book.amount(Total.DECAYED, col.a);
            if (col.inventory != null) for (var e : col.inventory.entrySet()) book.compound(e.getKey(), Total.DECAYED, e.getValue());
            gone.add(entry.getKey());
        }
        for (long key : gone) work.columns.remove(key);
    }

    private static void cap(Work work, GroundEnvironment environment, GroundSettings settings, GroundLedger.Book book) {
        int over = work.columns.size() - settings.maximumColumns();
        if (over <= 0) return;
        var loaded = new ArrayList<Long>();
        for (long key : work.columns.keySet()) if (environment.loaded(key)) loaded.add(key);
        loaded.sort(Comparator.comparingDouble((Long key) -> work.columns.get(key).a).thenComparingLong(key -> key));
        for (int i = 0; i < over && i < loaded.size(); i++) {
            var col = work.columns.remove(loaded.get(i));
            book.amount(Total.CAPPED, col.a);
            if (col.inventory != null) for (var e : col.inventory.entrySet()) book.compound(e.getKey(), Total.CAPPED, e.getValue());
        }
    }

    private void requireFinite() {
        for (double a : amounts) if (!Double.isFinite(a)) throw new IllegalStateException("ground amount " + a);
        for (double m : entryMoles) if (!Double.isFinite(m)) throw new IllegalStateException("ground moles " + m);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The working copy.

    private static final class Column {
        double a;
        TreeMap<String, Double> inventory;

        TreeMap<String, Double> inventory() {
            if (inventory == null) inventory = new TreeMap<>();
            return inventory;
        }

        /**
         * A move may leave a rounding residue below 0: it is set to 0 and the residue booked {@code decayed} (negative),
         * as the air field books its own, so the ledger still closes exactly.
         */
        void clampNegatives(GroundLedger.Book book) {
            if (a < 0.0) {
                book.amount(Total.DECAYED, a);
                a = 0.0;
            }
            if (inventory == null) return;
            for (var e : inventory.entrySet()) {
                if (e.getValue() < 0.0) {
                    book.compound(e.getKey(), Total.DECAYED, e.getValue());
                    e.setValue(0.0);
                }
            }
        }
    }

    private static final class Work {
        final TreeMap<Long, Column> columns = new TreeMap<>();

        static Work of(GroundField field) {
            var work = new Work();
            for (int i = 0; i < field.columns.length; i++) work.column(field.columns[i]).a = field.amounts[i];
            for (int i = 0; i < field.entryNames.length; i++) work.columns.get(field.entryColumns[i]).inventory().put(field.entryNames[i], field.entryMoles[i]);
            return work;
        }

        Column column(long key) {
            return columns.computeIfAbsent(key, k -> new Column());
        }

        GroundField field() {
            int n = columns.size(), m = 0;
            for (var col : columns.values()) if (col.inventory != null) for (double v : col.inventory.values()) if (v > 0.0) m++;
            long[] keys = new long[n];
            double[] amounts = new double[n];
            long[] entryColumns = new long[m];
            String[] entryNames = new String[m];
            double[] entryMoles = new double[m];
            int i = 0, j = 0;
            for (var e : columns.entrySet()) {
                keys[i] = e.getKey();
                amounts[i] = e.getValue().a;
                var inv = e.getValue().inventory;
                if (inv != null) {
                    for (var x : inv.entrySet()) {
                        if (!(x.getValue() > 0.0)) continue;
                        entryColumns[j] = e.getKey();
                        entryNames[j] = x.getKey();
                        entryMoles[j] = x.getValue();
                        j++;
                    }
                }
                i++;
            }
            return new GroundField(keys, amounts, entryColumns, entryNames, entryMoles);
        }
    }
}
