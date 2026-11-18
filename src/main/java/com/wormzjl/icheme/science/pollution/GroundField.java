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
        // One String per compound name: a load reads a new one for every entry.
        var canonical = new java.util.HashMap<String, String>();
        for (var e : entries) {
            Objects.requireNonNull(e, "entry");
            new Compound(e.name(), e.moles());
            var col = work.columns.get(e.column());
            if (col == null) throw new IllegalArgumentException("compound " + e.name() + " in an unlisted column");
            String name = canonical.computeIfAbsent(e.name(), k -> k);
            if (col.inventory().put(name, e.moles()) != null) throw new IllegalArgumentException("compound " + e.name() + " twice in a column");
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
        var work = Flat.of(field);
        work.land(deposits, book);
        work.spread(environment, seconds, settings, book);
        work.decay(environment, seconds, settings, book);
        work.truncate(environment, settings, book);
        work.cap(environment, settings, book);
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

    // ---------------------------------------------------------------------------------------------------------------
    // The step's working copy: sorted parallel arrays. Every sum and every booking is made in the order of the
    // TreeMap-based step it replaced (columns ascending, a column's compounds by name, deposits in DEPOSIT_ORDER,
    // transfers in visiting order), so the results are the same to the bit.

    private static final class Flat {
        int n;
        long[] columns;
        double[] a;
        /** Column i's entries are {@code [start[i], start[i + 1])}, by name; within a step an entry may hold 0. */
        int[] start;
        String[] names;
        double[] moles;

        static Flat of(GroundField f) {
            var w = new Flat();
            w.n = f.columns.length;
            w.columns = f.columns.clone();
            w.a = f.amounts.clone();
            w.start = new int[w.n + 1];
            for (int i = 0, e = 0; i < w.n; i++) {
                w.start[i] = e;
                while (e < f.entryColumns.length && f.entryColumns[e] == w.columns[i]) e++;
                w.start[i + 1] = e;
            }
            w.names = f.entryNames.clone();
            w.moles = f.entryMoles.clone();
            return w;
        }

        GroundField field() {
            int m = 0;
            for (double v : moles) if (v > 0.0) m++;
            long[] entryColumns = new long[m];
            String[] entryNames = new String[m];
            double[] entryMoles = new double[m];
            int j = 0;
            for (int i = 0; i < n; i++) {
                for (int e = start[i]; e < start[i + 1]; e++) {
                    if (!(moles[e] > 0.0)) continue;
                    entryColumns[j] = columns[i];
                    entryNames[j] = names[e];
                    entryMoles[j] = moles[e];
                    j++;
                }
            }
            return new GroundField(columns.length == n ? columns : Arrays.copyOf(columns, n), a.length == n ? a : Arrays.copyOf(a, n),
                    entryColumns, entryNames, entryMoles);
        }

        // ----- land

        void land(List<Deposit> deposits, GroundLedger.Book book) {
            var sorted = deposits.toArray(new Deposit[0]);
            Arrays.sort(sorted, DEPOSIT_ORDER);
            int counting = 0;
            for (var d : sorted) {
                boolean any = d.amount() > 0.0;
                for (var c : d.compounds()) any |= c.moles() > 0.0;
                if (!any) continue;
                sorted[counting++] = d;
                book.amount(Total.DEPOSITED, d.amount());
                for (var c : d.compounds()) {
                    if (c.moles() > 0.0) book.compound(c.name(), Total.DEPOSITED, c.moles());
                }
            }
            if (counting == 0) return;
            // Merge the old columns with the deposits' columns.
            int groups = 0;
            for (int d = 0; d < counting; d++) if (d == 0 || sorted[d].column() != sorted[d - 1].column()) groups++;
            int size = n + groups;
            long[] nc = new long[size];
            double[] na = new double[size];
            int[] ns = new int[size + 1];
            int room = names.length;
            for (int d = 0; d < counting; d++) room += sorted[d].compounds().size();
            String[] nn = new String[room];
            double[] nm = new double[room];
            int e2 = 0;
            int i = 0, d = 0, o = 0;
            while (i < n || d < counting) {
                long next = i < n && (d >= counting || columns[i] <= sorted[d].column()) ? columns[i] : sorted[d].column();
                boolean old = i < n && columns[i] == next;
                ns[o] = e2;
                nc[o] = next;
                if (d < counting && sorted[d].column() == next) {
                    double amount = old ? a[i] : 0.0;
                    TreeMap<String, Double> inventory = new TreeMap<>();
                    if (old) for (int e = start[i]; e < start[i + 1]; e++) inventory.put(names[e], moles[e]);
                    for (; d < counting && sorted[d].column() == next; d++) {
                        amount += sorted[d].amount();
                        for (var c : sorted[d].compounds()) {
                            if (c.moles() > 0.0) inventory.merge(c.name(), c.moles(), Double::sum);
                        }
                    }
                    na[o] = amount;
                    for (var e : inventory.entrySet()) {
                        nn[e2] = e.getKey();
                        nm[e2] = e.getValue();
                        e2++;
                    }
                } else {
                    na[o] = a[i];
                    for (int e = start[i]; e < start[i + 1]; e++) {
                        nn[e2] = names[e];
                        nm[e2] = moles[e];
                        e2++;
                    }
                }
                if (old) i++;
                o++;
            }
            ns[o] = e2;
            n = o;
            columns = nc;
            a = na;
            start = Arrays.copyOf(ns, o + 1);
            names = Arrays.copyOf(nn, e2);
            moles = Arrays.copyOf(nm, e2);
        }

        // ----- spread

        void spread(GroundEnvironment environment, double seconds, GroundSettings settings, GroundLedger.Book book) {
            double base = Math.min(GroundSettings.MAXIMUM_FACE_SHARE, settings.spreadShare() * seconds / settings.nominalSeconds());
            if (!(base > 0.0)) return;
            double wet = environment.raining() ? settings.wetSpread() : 1.0;
            // Transfers in visiting order; a destination not in the field is -(slot + 1).
            int tn = 0;
            int room = Math.max(16, 2 * n);
            int[] tFrom = new int[room], tTo = new int[room];
            double[] tAmount = new double[room], tShare = new double[room];
            var slots = new LongIntMap(Math.max(16, n / 4));
            long[] newKeys = new long[8];
            double[] newInflow = new double[8];
            int newCount = 0;
            for (int i = 0; i < n; i++) {
                long key = columns[i];
                if (!environment.loaded(key)) continue;
                int x = MapColumns.x(key), z = MapColumns.z(key);
                for (int f = 0; f < 4; f++) {
                    long other = MapColumns.column(x + DX[f], z + DZ[f]);
                    int j = Arrays.binarySearch(columns, 0, n, other);
                    if (j >= 0 && other < key) continue;
                    if (!environment.loaded(other)) continue;
                    double k = base * wet;
                    if (environment.water(key) || environment.water(other)) k *= settings.waterSpread();
                    k = Math.min(GroundSettings.MAXIMUM_FACE_SHARE, k);
                    double ab = j < 0 ? 0.0 : a[j];
                    double flow = k * (a[i] - ab);
                    int from, to;
                    double amount, share;
                    if (flow > 0.0) {
                        from = i;
                        amount = flow;
                        share = flow / a[i];
                        if (j >= 0) {
                            to = j;
                        } else {
                            int slot = slots.get(other);
                            if (slot < 0) {
                                if (newCount == newKeys.length) {
                                    newKeys = Arrays.copyOf(newKeys, newCount * 2);
                                    newInflow = Arrays.copyOf(newInflow, newCount * 2);
                                }
                                slot = newCount++;
                                slots.put(other, slot);
                                newKeys[slot] = other;
                                newInflow[slot] = flow;
                            } else {
                                newInflow[slot] += flow;
                            }
                            to = -(slot + 1);
                        }
                    } else if (flow < 0.0) {
                        from = j;
                        to = i;
                        amount = -flow;
                        share = -flow / ab;
                    } else {
                        continue;
                    }
                    if (tn == tFrom.length) {
                        tFrom = Arrays.copyOf(tFrom, tn * 2);
                        tTo = Arrays.copyOf(tTo, tn * 2);
                        tAmount = Arrays.copyOf(tAmount, tn * 2);
                        tShare = Arrays.copyOf(tShare, tn * 2);
                    }
                    tFrom[tn] = from;
                    tTo[tn] = to;
                    tAmount[tn] = amount;
                    tShare[tn] = share;
                    tn++;
                }
            }
            if (tn == 0) return;
            // A column not in the field takes a flow only when its inflows reach the cutoff index.
            boolean[] acceptedSlot = new boolean[newCount];
            for (int s = 0; s < newCount; s++) acceptedSlot[s] = !(newInflow[s] / settings.reference() < settings.cutoff());
            double[] newA = new double[newCount];
            // Compound moves, from the donors' inventories before anything moves.
            int cn = 0;
            int moveRoom = Math.max(16, 2 * moles.length);
            int[] cFrom = new int[moveRoom], cTo = new int[moveRoom], cEntry = new int[moveRoom];
            double[] cMoles = new double[moveRoom];
            for (int t = 0; t < tn; t++) {
                if (tTo[t] < 0 && !acceptedSlot[-tTo[t] - 1]) continue;
                int from = tFrom[t];
                for (int e = start[from]; e < start[from + 1]; e++) {
                    double m = moles[e] * tShare[t];
                    if (!(m > 0.0)) continue;
                    if (cn == cFrom.length) {
                        cFrom = Arrays.copyOf(cFrom, cn * 2);
                        cTo = Arrays.copyOf(cTo, cn * 2);
                        cEntry = Arrays.copyOf(cEntry, cn * 2);
                        cMoles = Arrays.copyOf(cMoles, cn * 2);
                    }
                    cFrom[cn] = from;
                    cTo[cn] = tTo[t];
                    cEntry[cn] = e;
                    cMoles[cn] = m;
                    cn++;
                }
            }
            // Applied after every flow is known, so the step is simultaneous.
            for (int t = 0; t < tn; t++) {
                int to = tTo[t];
                if (to < 0 && !acceptedSlot[-to - 1]) continue;
                a[tFrom[t]] -= tAmount[t];
                if (to >= 0) a[to] += tAmount[t];
                else newA[-to - 1] += tAmount[t];
            }
            // Compounds: a recipient without the compound gets an extra entry, keyed by (column, name).
            int xn = 0;
            int[] xColumn = new int[8]; // column index, or n + slot for a new column
            String[] xName = new String[8];
            double[] xMoles = new double[8];
            // Extras by (column reference, name): the step's few names get small ids.
            var nameIds = new java.util.HashMap<String, Integer>();
            var extra = new LongIntMap(16);
            for (int c = 0; c < cn; c++) {
                int e = cEntry[c];
                moles[e] += -cMoles[c];
                String name = names[e];
                int to = cTo[c];
                int found = -1;
                if (to >= 0) {
                    for (int r = start[to]; r < start[to + 1]; r++) {
                        if (names[r].equals(name)) {
                            found = r;
                            break;
                        }
                    }
                }
                if (found >= 0) {
                    moles[found] += cMoles[c];
                    continue;
                }
                int ref = to >= 0 ? to : n + (-to - 1);
                Integer id = nameIds.get(name);
                if (id == null) {
                    id = nameIds.size();
                    nameIds.put(name, id);
                }
                long extraKey = (long) ref << 20 | id;
                int x = extra.get(extraKey);
                if (x < 0) {
                    if (xn == xColumn.length) {
                        xColumn = Arrays.copyOf(xColumn, xn * 2);
                        xName = Arrays.copyOf(xName, xn * 2);
                        xMoles = Arrays.copyOf(xMoles, xn * 2);
                    }
                    x = xn++;
                    extra.put(extraKey, x);
                    xColumn[x] = ref;
                    xName[x] = name;
                    xMoles[x] = 0.0;
                }
                xMoles[x] += cMoles[c];
            }
            rebuild(newKeys, newA, acceptedSlot, newCount, xColumn, xName, xMoles, xn);
            clampNegatives(book);
        }

        /** The columns with the accepted new ones in key order, each column's entries with its extras by name. */
        private void rebuild(long[] newKeys, double[] newA, boolean[] acceptedSlot, int newCount, int[] xColumn, String[] xName,
                double[] xMoles, int xn) {
            int accepted = 0;
            for (int s = 0; s < newCount; s++) if (acceptedSlot[s]) accepted++;
            if (accepted == 0 && xn == 0) return;
            // The accepted new slots in key order.
            long[] order = new long[accepted];
            int[] slotOf = new int[accepted];
            {
                int q = 0;
                for (int s = 0; s < newCount; s++) if (acceptedSlot[s]) order[q++] = newKeys[s];
                Arrays.sort(order);
                var position = new LongIntMap(Math.max(16, accepted));
                for (int s = 0; s < newCount; s++) if (acceptedSlot[s]) position.put(newKeys[s], s);
                for (int r = 0; r < accepted; r++) slotOf[r] = position.get(order[r]);
            }
            // Extras grouped by column reference, each group by name.
            // Counting sort of the extras by column reference; within one, by name.
            int refs = n + newCount;
            int[] first = new int[refs + 1];
            for (int x = 0; x < xn; x++) first[xColumn[x] + 1]++;
            for (int r0 = 0; r0 < refs; r0++) first[r0 + 1] += first[r0];
            int[] byRef = new int[xn];
            int[] fill = Arrays.copyOf(first, refs);
            for (int x = 0; x < xn; x++) byRef[fill[xColumn[x]]++] = x;
            for (int r0 = 0; r0 < refs; r0++) {
                for (int p = first[r0] + 1; p < first[r0 + 1]; p++) {
                    int v = byRef[p];
                    int q = p - 1;
                    while (q >= first[r0] && xName[byRef[q]].compareTo(xName[v]) > 0) {
                        byRef[q + 1] = byRef[q];
                        q--;
                    }
                    byRef[q + 1] = v;
                }
            }
            int size = n + accepted;
            long[] nc = new long[size];
            double[] na = new double[size];
            int[] ns = new int[size + 1];
            int entries = moles.length + xn;
            String[] nn = new String[entries];
            double[] nm = new double[entries];
            int i = 0, r = 0, o = 0, e2 = 0;
            while (i < n || r < accepted) {
                boolean old = r >= accepted || i < n && columns[i] < order[r];
                ns[o] = e2;
                int ref;
                if (old) {
                    nc[o] = columns[i];
                    na[o] = a[i];
                    ref = i;
                } else {
                    nc[o] = order[r];
                    na[o] = newA[slotOf[r]];
                    ref = n + slotOf[r];
                }
                int e = old ? start[i] : 0, end = old ? start[i + 1] : 0;
                int x = first[ref], xe = first[ref + 1];
                while (e < end || x < xe) {
                    if (x >= xe || e < end && names[e].compareTo(xName[byRef[x]]) < 0) {
                        nn[e2] = names[e];
                        nm[e2] = moles[e];
                        e++;
                    } else {
                        int xi = byRef[x++];
                        nn[e2] = xName[xi];
                        nm[e2] = xMoles[xi];
                    }
                    e2++;
                }
                if (old) i++;
                else r++;
                o++;
            }
            ns[o] = e2;
            n = o;
            columns = nc;
            a = na;
            start = ns;
            names = nn;
            moles = nm;
        }

        /**
         * A move may leave a rounding residue below 0: it is set to 0 and the residue booked {@code decayed} (negative),
         * as the air field books its own, so the ledger still closes exactly.
         */
        private void clampNegatives(GroundLedger.Book book) {
            for (int i = 0; i < n; i++) {
                if (a[i] < 0.0) {
                    book.amount(Total.DECAYED, a[i]);
                    a[i] = 0.0;
                }
                for (int e = start[i]; e < start[i + 1]; e++) {
                    if (moles[e] < 0.0) {
                        book.compound(names[e], Total.DECAYED, moles[e]);
                        moles[e] = 0.0;
                    }
                }
            }
        }

        // ----- decay, truncate, cap

        void decay(GroundEnvironment environment, double seconds, GroundSettings settings, GroundLedger.Book book) {
            double halfLife = settings.halfLifeSeconds() / (environment.raining() ? settings.washout() : 1.0);
            double keep = DeterministicMath.exp(-LN2 * seconds / halfLife);
            for (int i = 0; i < n; i++) {
                if (!environment.loaded(columns[i])) continue;
                double gone = a[i] - a[i] * keep;
                a[i] -= gone;
                book.amount(Total.DECAYED, gone);
                for (int e = start[i]; e < start[i + 1]; e++) {
                    double lost = moles[e] - moles[e] * keep;
                    moles[e] -= lost;
                    book.compound(names[e], Total.DECAYED, lost);
                }
            }
        }

        void truncate(GroundEnvironment environment, GroundSettings settings, GroundLedger.Book book) {
            boolean[] gone = null;
            for (int i = 0; i < n; i++) {
                if (!environment.loaded(columns[i])) continue;
                if (a[i] / settings.reference() >= settings.cutoff()) continue;
                book.amount(Total.DECAYED, a[i]);
                for (int e = start[i]; e < start[i + 1]; e++) book.compound(names[e], Total.DECAYED, moles[e]);
                if (gone == null) gone = new boolean[n];
                gone[i] = true;
            }
            if (gone != null) remove(gone);
        }

        void cap(GroundEnvironment environment, GroundSettings settings, GroundLedger.Book book) {
            int over = n - settings.maximumColumns();
            if (over <= 0) return;
            int loaded = 0;
            for (int i = 0; i < n; i++) if (environment.loaded(columns[i])) loaded++;
            int[] order = new int[loaded];
            for (int i = 0, q = 0; i < n; i++) if (environment.loaded(columns[i])) order[q++] = i;
            // The lowest amount first, then the smaller key (Double.compare, then Long.compare): a total order.
            sortByAmount(order);
            boolean[] gone = new boolean[n];
            for (int q = 0; q < over && q < order.length; q++) {
                int i = order[q];
                book.amount(Total.CAPPED, a[i]);
                for (int e = start[i]; e < start[i + 1]; e++) book.compound(names[e], Total.CAPPED, moles[e]);
                gone[i] = true;
            }
            remove(gone);
        }

        private void sortByAmount(int[] order) {
            int[] buffer = new int[order.length];
            for (int width = 1; width < order.length; width <<= 1) {
                for (int from = 0; from < order.length; from += 2 * width) {
                    int middle = Math.min(from + width, order.length), to = Math.min(from + 2 * width, order.length);
                    int p = from, q = middle;
                    for (int k = from; k < to; k++) {
                        boolean left = q >= to || p < middle && before(order[p], order[q]);
                        buffer[k] = left ? order[p++] : order[q++];
                    }
                }
                System.arraycopy(buffer, 0, order, 0, order.length);
            }
        }

        private boolean before(int p, int q) {
            int c = Double.compare(a[p], a[q]);
            return c < 0 || c == 0 && columns[p] <= columns[q];
        }

        private void remove(boolean[] gone) {
            int keptColumns = 0, keptEntries = 0;
            for (int i = 0; i < n; i++) {
                if (!gone[i]) {
                    keptColumns++;
                    keptEntries += start[i + 1] - start[i];
                }
            }
            long[] nc = new long[keptColumns];
            double[] na = new double[keptColumns];
            int[] ns = new int[keptColumns + 1];
            String[] nn = new String[keptEntries];
            double[] nm = new double[keptEntries];
            int o = 0, e2 = 0;
            for (int i = 0; i < n; i++) {
                if (gone[i]) continue;
                nc[o] = columns[i];
                na[o] = a[i];
                ns[o] = e2;
                int count = start[i + 1] - start[i];
                System.arraycopy(names, start[i], nn, e2, count);
                System.arraycopy(moles, start[i], nm, e2, count);
                e2 += count;
                o++;
            }
            ns[o] = e2;
            n = o;
            columns = nc;
            a = na;
            start = ns;
            names = nn;
            moles = nm;
        }
    }
}
