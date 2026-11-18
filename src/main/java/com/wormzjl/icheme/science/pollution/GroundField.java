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
 * chunk basis instead of 16block cubes"): one entry per chunk column ({@link MapColumns#column}), with four channel
 * amounts in air-mole equivalents and an inventory of the compounds that lie there, in moles.
 *
 * <p>The channel amounts carry the hazard; a column's index is {@code amount / reference} ({@link #GROUND_REFERENCE}:
 * 100 open air cubes, so index 1 keeps the meaning "at the reference hazard"). The inventory keeps components exact so
 * a water intake can draw them back. Each compound entry belongs to one channel, the one it was deposited under (its
 * largest hazard contribution), and moves with that channel: a spread that passes a share of the donor's channel passes
 * the same share of every compound under it, so moles close per compound. Settled particulate has no compound.
 *
 * <p>Immutable. {@link #step} is pure and deterministic: columns are visited in ascending key order, deposits are summed
 * in a fixed order. It holds no Minecraft type.
 */
public final class GroundField {
    /** The amount of a column's channel at index 1: 100 open cubes of air ({@code 100 × CUBE_AIR_MOLES}, 1.674e7). */
    public static final double GROUND_REFERENCE = 100 * PollutionField.CUBE_AIR_MOLES;

    static final int CHANNELS = 4;
    private static final double LN2 = 0.6931471805599453;
    private static final int[] DX = {-1, 1, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1};

    public static final GroundField EMPTY = new GroundField(new long[0], new double[0], new long[0], new int[0],
            new String[0], new double[0]);

    private final long[] columns;
    private final double[] amounts;
    /** The compound entries, sorted by column, then channel, then name. */
    private final long[] entryColumns;
    private final int[] entryChannels;
    private final String[] entryNames;
    private final double[] entryMoles;

    private GroundField(long[] columns, double[] amounts, long[] entryColumns, int[] entryChannels, String[] entryNames,
            double[] entryMoles) {
        this.columns = columns;
        this.amounts = amounts;
        this.entryColumns = entryColumns;
        this.entryChannels = entryChannels;
        this.entryNames = entryNames;
        this.entryMoles = entryMoles;
    }

    /**
     * Moles of one compound in one column, under the channel it moves with.
     *
     * @param name the compound's id, kept as saved even when no package holds it (a fact)
     */
    public record Compound(String name, Channel channel, double moles) {
        public Compound {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(channel, "channel");
            if (name.isEmpty()) throw new IllegalArgumentException("compound name is empty");
            if (!(moles >= 0.0) || !Double.isFinite(moles)) {
                throw new IllegalArgumentException("compound moles must be finite and not negative: " + moles);
            }
        }
    }

    /** One compound entry of a column, as saved. */
    public record Entry(long column, Channel channel, String name, double moles) {}

    /**
     * What lands in a column at the start of a step: the only way in.
     *
     * @param amounts four channel amounts in air-mole equivalents, each finite and not negative
     * @param compounds what lies there, each under the channel it moves with
     */
    public record Deposit(long column, double[] amounts, List<Compound> compounds) {
        public Deposit {
            Objects.requireNonNull(amounts, "amounts");
            if (amounts.length != CHANNELS) throw new IllegalArgumentException("a deposit has four amounts");
            for (double a : amounts) {
                if (!(a >= 0.0) || !Double.isFinite(a)) {
                    throw new IllegalArgumentException("deposit amount must be finite and not negative: " + a);
                }
            }
            amounts = amounts.clone();
            compounds = List.copyOf(compounds);
        }

        /** A deposit of one channel's amount with no compound (settled particulate). */
        public static Deposit of(long column, Channel channel, double amount) {
            var a = new double[CHANNELS];
            a[channel.ordinal()] = amount;
            return new Deposit(column, a, List.of());
        }

        @Override public double[] amounts() {
            return amounts.clone();
        }
    }

    /** The outcome of a step. */
    public record Result(GroundField field, GroundLedger ledger) {
        public Result {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(ledger, "ledger");
        }
    }

    /**
     * The outcome of a draw: the field and ledger after it, the compounds taken (moles) and the channel amounts taken
     * with them.
     */
    public record Drawn(GroundField field, GroundLedger ledger, List<Compound> compounds, double[] amounts) {}

    /**
     * A field from saved arrays: {@code columns} strictly ascending, {@code amounts} four per column, finite and not
     * negative; {@code entries} each in a listed column, no two with the same column, channel and name. Throws
     * {@link IllegalArgumentException} naming the fault.
     */
    public static GroundField of(long[] columns, double[] amounts, List<Entry> entries) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(amounts, "amounts");
        Objects.requireNonNull(entries, "entries");
        if (amounts.length != columns.length * CHANNELS) {
            throw new IllegalArgumentException("ground of " + columns.length + " columns needs "
                    + columns.length * CHANNELS + " amounts, got " + amounts.length);
        }
        for (int i = 1; i < columns.length; i++) {
            if (columns[i] <= columns[i - 1]) {
                throw new IllegalArgumentException("ground columns not strictly ascending at " + i);
            }
        }
        for (double a : amounts) {
            if (!(a >= 0.0) || !Double.isFinite(a)) {
                throw new IllegalArgumentException("ground amount must be finite and not negative: " + a);
            }
        }
        var work = new Work();
        for (int i = 0; i < columns.length; i++) {
            work.column(columns[i]).add(amounts, i * CHANNELS);
        }
        for (var e : entries) {
            Objects.requireNonNull(e, "entry");
            new Compound(e.name(), e.channel(), e.moles());
            var col = work.columns.get(e.column());
            if (col == null) throw new IllegalArgumentException("compound " + e.name() + " in an unlisted column");
            if (col.inventory(e.channel().ordinal()).put(e.name(), e.moles()) != null) {
                throw new IllegalArgumentException("compound " + e.name() + " twice under " + e.channel());
            }
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

    /** The amounts, four per column in channel order (a copy). */
    public double[] amounts() {
        return amounts.clone();
    }

    /** The compound entries, sorted by column, channel and name. */
    public List<Entry> entries() {
        var out = new ArrayList<Entry>(entryNames.length);
        for (int i = 0; i < entryNames.length; i++) {
            out.add(new Entry(entryColumns[i], Channel.values()[entryChannels[i]], entryNames[i], entryMoles[i]));
        }
        return out;
    }

    public int find(long column) {
        return Arrays.binarySearch(columns, column);
    }

    /** A column's channel amount; 0 when the column is absent. */
    public double amount(long column, Channel channel) {
        int i = find(column);
        return i < 0 ? 0.0 : amounts[i * CHANNELS + channel.ordinal()];
    }

    /** A column's channel index: its amount over the reference. */
    public double index(long column, Channel channel, GroundSettings settings) {
        return amount(column, channel) / settings.reference();
    }

    /** Moles of a compound in a column, over every channel it lies under. */
    public double moles(long column, String compound) {
        double sum = 0.0;
        int from = firstEntry(column);
        for (int i = from; i < entryColumns.length && entryColumns[i] == column; i++) {
            if (entryNames[i].equals(compound)) sum += entryMoles[i];
        }
        return sum;
    }

    /** The compounds of a column, summed over channels, by name. */
    public Map<String, Double> compounds(long column) {
        var out = new TreeMap<String, Double>();
        for (int i = firstEntry(column); i < entryColumns.length && entryColumns[i] == column; i++) {
            out.merge(entryNames[i], entryMoles[i], Double::sum);
        }
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

    /** The sum of a channel over every column. */
    public double stored(Channel channel) {
        double sum = 0.0;
        for (int i = channel.ordinal(); i < amounts.length; i += CHANNELS) sum += amounts[i];
        return sum;
    }

    /** The moles of a compound over every column. */
    public double stored(String compound) {
        double sum = 0.0;
        for (int i = 0; i < entryNames.length; i++) {
            if (entryNames[i].equals(compound)) sum += entryMoles[i];
        }
        return sum;
    }

    /**
     * One pass of the ground over {@code seconds} of process time, in this order:
     * <ol>
     *   <li>land: deposits land in their columns, loaded or not ({@code deposited});</li>
     *   <li>spread: across each face between a loaded column and a loaded neighbour, once, simultaneously,
     *       {@code F = k (A_a − A_b)} per channel with {@code k = min(1/5, spreadShare × seconds / nominalSeconds)}, times
     *       {@code wetSpread} in rain and {@code waterSpread} where either column is water (then capped at 1/5 again); the
     *       compounds under a channel move by the share {@code F / A} of the donor; a column not yet in the field takes a
     *       channel only when its inflows give it an index of at least {@code cutoff};</li>
     *   <li>decay: loaded columns, half-life {@code halfLifeSeconds} (÷ {@code washout} in rain), amounts and compounds by
     *       the same factor ({@code decayed});</li>
     *   <li>truncate: channels of loaded columns below {@code cutoff}, with their compounds ({@code decayed}); empty
     *       columns leave;</li>
     *   <li>cap: above {@code maximumColumns}, loaded columns with the lowest peak index leave first ({@code capped}).</li>
     * </ol>
     * Unloaded columns keep their amounts and pass nothing.
     */
    public static Result step(GroundField field, GroundLedger ledger, List<Deposit> deposits,
            GroundEnvironment environment, double seconds, GroundSettings settings) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(deposits, "deposits");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(settings, "settings");
        if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
            throw new IllegalArgumentException("seconds must be finite and positive: " + seconds);
        }
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
     * every compound and of the toxic, combustible and corrosive amounts the compounds carry (settled particulate stays),
     * booked {@code drawn}. Only compounds {@code held} lists leave; the others stay in the ground, and with them the
     * channel amount in proportion to their moles. The same field when nothing is there.
     */
    public static Drawn draw(GroundField field, GroundLedger ledger, long column, double waterMoles,
            double referenceWater, java.util.function.Predicate<String> held) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(held, "held");
        if (!(waterMoles >= 0.0) || !Double.isFinite(waterMoles) || !(referenceWater > 0.0)) {
            throw new IllegalArgumentException("draw of " + waterMoles + " mol against " + referenceWater);
        }
        double fraction = Math.min(1.0, waterMoles / referenceWater);
        int at = field.find(column);
        if (at < 0 || !(fraction > 0.0)) return new Drawn(field, ledger, List.of(), new double[CHANNELS]);
        var work = Work.of(field);
        var col = work.columns.get(column);
        var book = ledger.book();
        var taken = new TreeMap<String, double[]>();
        var takenAmounts = new double[CHANNELS];
        for (int c = 0; c < CHANNELS; c++) {
            if (c == Channel.PARTICULATE.ordinal() || col.inventories[c] == null) continue;
            double total = 0.0, leaving = 0.0;
            for (var e : col.inventories[c].entrySet()) {
                total += e.getValue();
                if (!held.test(e.getKey())) continue;
                double moles = e.getValue() * fraction;
                leaving += e.getValue();
                e.setValue(e.getValue() - moles);
                taken.computeIfAbsent(e.getKey(), k -> new double[CHANNELS])[c] += moles;
                book.compound(e.getKey(), Total.DRAWN, moles);
            }
            if (!(total > 0.0) || !(leaving > 0.0)) continue;
            double amount = col.a[c] * fraction * (leaving / total);
            col.a[c] -= amount;
            takenAmounts[c] += amount;
            book.channel(c, Total.DRAWN, amount);
        }
        var compounds = new ArrayList<Compound>();
        for (var e : taken.entrySet()) {
            for (int c = 0; c < CHANNELS; c++) {
                if (e.getValue()[c] > 0.0) compounds.add(new Compound(e.getKey(), Channel.values()[c], e.getValue()[c]));
            }
        }
        return new Drawn(work.field(), book.ledger(), List.copyOf(compounds), takenAmounts);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The stages.

    private static final Comparator<Deposit> DEPOSIT_ORDER = (a, b) -> {
        int c = Long.compare(a.column(), b.column());
        if (c != 0) return c;
        for (int i = 0; i < CHANNELS; i++) {
            c = Long.compare(Double.doubleToLongBits(a.amounts[i]), Double.doubleToLongBits(b.amounts[i]));
            if (c != 0) return c;
        }
        c = Integer.compare(a.compounds().size(), b.compounds().size());
        for (int i = 0; c == 0 && i < a.compounds().size(); i++) {
            var x = a.compounds().get(i);
            var y = b.compounds().get(i);
            c = x.name().compareTo(y.name());
            if (c == 0) c = Integer.compare(x.channel().ordinal(), y.channel().ordinal());
            if (c == 0) c = Long.compare(Double.doubleToLongBits(x.moles()), Double.doubleToLongBits(y.moles()));
        }
        return c;
    };

    private static void land(Work work, List<Deposit> deposits, GroundLedger.Book book) {
        var sorted = deposits.toArray(new Deposit[0]);
        Arrays.sort(sorted, DEPOSIT_ORDER);
        for (var d : sorted) {
            boolean any = false;
            for (double a : d.amounts) any |= a > 0.0;
            for (var c : d.compounds()) any |= c.moles() > 0.0;
            if (!any) continue;
            var col = work.column(d.column());
            for (int c = 0; c < CHANNELS; c++) {
                col.a[c] += d.amounts[c];
                book.channel(c, Total.DEPOSITED, d.amounts[c]);
            }
            for (var c : d.compounds()) {
                if (!(c.moles() > 0.0)) continue;
                col.inventory(c.channel().ordinal()).merge(c.name(), c.moles(), Double::sum);
                book.compound(c.name(), Total.DEPOSITED, c.moles());
            }
        }
    }

    /** One transfer of a step's spread: {@code amount} of channel {@code channel}, the donor's share {@code share}. */
    private record Transfer(long from, long to, int channel, double amount, double share) {}

    /** The compound moles that move with a transfer. */
    private record CompoundMove(long from, long to, int channel, String name, double moles) {}

    private static void spread(Work work, GroundEnvironment environment, double seconds, GroundSettings settings,
            GroundLedger.Book book) {
        double base = Math.min(GroundSettings.MAXIMUM_FACE_SHARE, settings.spreadShare() * seconds / settings.nominalSeconds());
        if (!(base > 0.0)) return;
        double wet = environment.raining() ? settings.wetSpread() : 1.0;
        var transfers = new ArrayList<Transfer>();
        var newInflow = new TreeMap<Long, double[]>();
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
                for (int c = 0; c < CHANNELS; c++) {
                    double ab = b == null ? 0.0 : b.a[c];
                    double flow = k * (a.a[c] - ab);
                    if (flow > 0.0) {
                        transfers.add(new Transfer(key, other, c, flow, flow / a.a[c]));
                        if (b == null) newInflow.computeIfAbsent(other, o -> new double[CHANNELS])[c] += flow;
                    } else if (flow < 0.0) {
                        transfers.add(new Transfer(other, key, c, -flow, -flow / ab));
                    }
                }
            }
        }
        if (transfers.isEmpty()) return;
        // A column not in the field takes a channel only when its inflows reach the cutoff index.
        var moves = new ArrayList<Transfer>();
        var compoundMoves = new ArrayList<CompoundMove>();
        for (var t : transfers) {
            var inflow = newInflow.get(t.to());
            if (inflow != null && inflow[t.channel()] / settings.reference() < settings.cutoff()) continue;
            moves.add(t);
            var inventory = work.columns.get(t.from()).inventories[t.channel()];
            if (inventory == null) continue;
            for (var e : inventory.entrySet()) {
                double moles = e.getValue() * t.share();
                if (moles > 0.0) compoundMoves.add(new CompoundMove(t.from(), t.to(), t.channel(), e.getKey(), moles));
            }
        }
        // Applied after every flow is known, so the step is simultaneous.
        for (var m : moves) {
            work.columns.get(m.from()).a[m.channel()] -= m.amount();
            work.column(m.to()).a[m.channel()] += m.amount();
        }
        for (var m : compoundMoves) {
            work.columns.get(m.from()).inventory(m.channel()).merge(m.name(), -m.moles(), Double::sum);
            work.column(m.to()).inventory(m.channel()).merge(m.name(), m.moles(), Double::sum);
        }
        for (var col : work.columns.values()) col.clampNegatives(book);
    }

    private static void decay(Work work, GroundEnvironment environment, double seconds, GroundSettings settings,
            GroundLedger.Book book) {
        double halfLife = settings.halfLifeSeconds() / (environment.raining() ? settings.washout() : 1.0);
        double keep = DeterministicMath.exp(-LN2 * seconds / halfLife);
        for (var entry : work.columns.entrySet()) {
            if (!environment.loaded(entry.getKey())) continue;
            var col = entry.getValue();
            for (int c = 0; c < CHANNELS; c++) {
                double gone = col.a[c] - col.a[c] * keep;
                col.a[c] -= gone;
                book.channel(c, Total.DECAYED, gone);
                if (col.inventories[c] == null) continue;
                for (var e : col.inventories[c].entrySet()) {
                    double lost = e.getValue() - e.getValue() * keep;
                    e.setValue(e.getValue() - lost);
                    book.compound(e.getKey(), Total.DECAYED, lost);
                }
            }
        }
    }

    private static void truncate(Work work, GroundEnvironment environment, GroundSettings settings,
            GroundLedger.Book book) {
        var empty = new ArrayList<Long>();
        for (var entry : work.columns.entrySet()) {
            if (!environment.loaded(entry.getKey())) continue;
            var col = entry.getValue();
            for (int c = 0; c < CHANNELS; c++) {
                if (col.a[c] / settings.reference() >= settings.cutoff()) continue;
                book.channel(c, Total.DECAYED, col.a[c]);
                col.a[c] = 0.0;
                if (col.inventories[c] == null) continue;
                for (var e : col.inventories[c].entrySet()) book.compound(e.getKey(), Total.DECAYED, e.getValue());
                col.inventories[c] = null;
            }
            if (col.empty()) empty.add(entry.getKey());
        }
        for (long key : empty) work.columns.remove(key);
    }

    private static void cap(Work work, GroundEnvironment environment, GroundSettings settings, GroundLedger.Book book) {
        int over = work.columns.size() - settings.maximumColumns();
        if (over <= 0) return;
        var loaded = new ArrayList<Long>();
        for (long key : work.columns.keySet()) if (environment.loaded(key)) loaded.add(key);
        loaded.sort(Comparator.comparingDouble((Long key) -> work.columns.get(key).peak()).thenComparingLong(key -> key));
        for (int i = 0; i < over && i < loaded.size(); i++) {
            var col = work.columns.remove(loaded.get(i));
            for (int c = 0; c < CHANNELS; c++) {
                book.channel(c, Total.CAPPED, col.a[c]);
                if (col.inventories[c] == null) continue;
                for (var e : col.inventories[c].entrySet()) book.compound(e.getKey(), Total.CAPPED, e.getValue());
            }
        }
    }

    private void requireFinite() {
        for (double a : amounts) if (!Double.isFinite(a)) throw new IllegalStateException("ground amount " + a);
        for (double m : entryMoles) if (!Double.isFinite(m)) throw new IllegalStateException("ground moles " + m);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The working copy.

    private static final class Column {
        final double[] a = new double[CHANNELS];
        @SuppressWarnings("unchecked")
        final TreeMap<String, Double>[] inventories = new TreeMap[CHANNELS];

        void add(double[] amounts, int from) {
            for (int c = 0; c < CHANNELS; c++) a[c] += amounts[from + c];
        }

        TreeMap<String, Double> inventory(int channel) {
            if (inventories[channel] == null) inventories[channel] = new TreeMap<>();
            return inventories[channel];
        }

        /**
         * A move may leave a rounding residue below 0: it is set to 0 and the residue booked {@code decayed} (negative),
         * as the air field books its own, so the ledger still closes exactly.
         */
        void clampNegatives(GroundLedger.Book book) {
            for (int c = 0; c < CHANNELS; c++) {
                if (a[c] < 0.0) {
                    book.channel(c, Total.DECAYED, a[c]);
                    a[c] = 0.0;
                }
                if (inventories[c] == null) continue;
                for (var e : inventories[c].entrySet()) {
                    if (e.getValue() < 0.0) {
                        book.compound(e.getKey(), Total.DECAYED, e.getValue());
                        e.setValue(0.0);
                    }
                }
            }
        }

        double peak() {
            double p = 0.0;
            for (double v : a) p = Math.max(p, v);
            return p;
        }

        boolean empty() {
            for (int c = 0; c < CHANNELS; c++) {
                if (a[c] > 0.0) return false;
                if (inventories[c] != null) for (double m : inventories[c].values()) if (m > 0.0) return false;
            }
            return true;
        }
    }

    private static final class Work {
        final TreeMap<Long, Column> columns = new TreeMap<>();

        static Work of(GroundField field) {
            var work = new Work();
            for (int i = 0; i < field.columns.length; i++) work.column(field.columns[i]).add(field.amounts, i * CHANNELS);
            for (int i = 0; i < field.entryNames.length; i++) {
                work.columns.get(field.entryColumns[i]).inventory(field.entryChannels[i]).put(field.entryNames[i], field.entryMoles[i]);
            }
            return work;
        }

        Column column(long key) {
            return columns.computeIfAbsent(key, k -> new Column());
        }

        GroundField field() {
            int n = columns.size(), m = 0;
            for (var col : columns.values()) {
                for (var inv : col.inventories) if (inv != null) for (double v : inv.values()) if (v > 0.0) m++;
            }
            long[] keys = new long[n];
            double[] amounts = new double[n * CHANNELS];
            long[] entryColumns = new long[m];
            int[] entryChannels = new int[m];
            String[] entryNames = new String[m];
            double[] entryMoles = new double[m];
            int i = 0, j = 0;
            for (var e : columns.entrySet()) {
                keys[i] = e.getKey();
                System.arraycopy(e.getValue().a, 0, amounts, i * CHANNELS, CHANNELS);
                for (int c = 0; c < CHANNELS; c++) {
                    var inv = e.getValue().inventories[c];
                    if (inv == null) continue;
                    for (var x : inv.entrySet()) {
                        if (!(x.getValue() > 0.0)) continue;
                        entryColumns[j] = e.getKey();
                        entryChannels[j] = c;
                        entryNames[j] = x.getKey();
                        entryMoles[j] = x.getValue();
                        j++;
                    }
                }
                i++;
            }
            return new GroundField(keys, amounts, entryColumns, entryChannels, entryNames, entryMoles);
        }
    }
}
