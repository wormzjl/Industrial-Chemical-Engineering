package com.wormzjl.icheme.science.pollution;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.pollution.Environment.Cell;
import com.wormzjl.icheme.science.pollution.PollutionLedger.Total;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * The pollution of one dimension (plan section 4): a sparse set of 16 m cubes ({@link CubeKey}), each with four
 * amounts in air-mole equivalents (mol), one per {@link Channel}. A cube's index is its amount over its air:
 * {@code amount / (airShare × CUBE_AIR_MOLES)}; index 1 is the reference concentration (IDLH or LFL). The field
 * stores amounts, not indices, so the ledger stays exact when a cube's air share changes.
 *
 * <p>Immutable. {@link #step} is pure and deterministic: cubes are visited in ascending key order, releases are summed
 * in a fixed order, and no hash order reaches a result. It holds no Minecraft type; the runtime supplies an
 * {@link Environment} snapshot and runs the step on a worker.
 */
public final class PollutionField {
    /** Molar density of air at 298.15 K and 1 atm, mol/m³. */
    public static final double AIR_MOLAR_DENSITY = 101325.0 / (8.314462618 * 298.15);
    /** Volume of one cube, m³. */
    public static final double CUBE_VOLUME = 4096.0;
    /** Moles of air in an open cube (about 167 400 mol). */
    public static final double CUBE_AIR_MOLES = CUBE_VOLUME * AIR_MOLAR_DENSITY;

    static final int CHANNELS = 4;
    private static final double LN2 = 0.6931471805599453;
    private static final int[] DX = {-1, 1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, -1, 1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, -1, 1};
    /** The face toward the cube above. */
    private static final int UP = 3;
    private static final Comparator<Release> RELEASE_ORDER = Comparator.comparingLong(Release::key)
            .thenComparingInt((Release release) -> release.channel().ordinal())
            .thenComparingLong((Release release) -> Double.doubleToLongBits(release.amount()));

    public static final PollutionField EMPTY = new PollutionField(new long[0], new double[0]);

    private final long[] keys;
    private final double[] amounts;

    /** Takes the arrays without a copy or a check. */
    private PollutionField(long[] keys, double[] amounts) {
        this.keys = keys;
        this.amounts = amounts;
    }

    /** The outcome of a step or a burn. */
    public record Result(PollutionField field, PollutionLedger ledger) {
        public Result {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(ledger, "ledger");
        }
    }

    /**
     * A field from saved arrays: {@code keys} strictly ascending, {@code amounts} four per key in channel order, each
     * finite and not negative. Throws {@link IllegalArgumentException} naming the fault.
     */
    public static PollutionField of(long[] keys, double[] amounts) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(amounts, "amounts");
        if (amounts.length != keys.length * CHANNELS) {
            throw new IllegalArgumentException("field of " + keys.length + " cubes needs " + keys.length * CHANNELS
                    + " amounts, got " + amounts.length);
        }
        for (int i = 1; i < keys.length; i++) {
            if (keys[i] <= keys[i - 1]) {
                throw new IllegalArgumentException("cube keys not strictly ascending at " + i + ": " + keys[i - 1]
                        + " then " + keys[i]);
            }
        }
        for (int i = 0; i < amounts.length; i++) {
            if (!(amounts[i] >= 0.0) || !Double.isFinite(amounts[i])) {
                throw new IllegalArgumentException("amount of cube " + keys[i / CHANNELS] + " channel "
                        + Channel.values()[i % CHANNELS] + " must be finite and not negative: " + amounts[i]);
            }
        }
        return new PollutionField(keys.clone(), amounts.clone());
    }

    /**
     * The field capped at a dimension's height (owner, 2026-11-15: "cap at world height", cubes outside dropped): every
     * cube above {@code maximumY} or below {@code minimumY} leaves, its amounts booked {@code dissipated}, so the ledger
     * still closes. The same field and ledger when every cube is inside.
     */
    public static Result capped(PollutionField field, PollutionLedger ledger, int minimumY, int maximumY) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(ledger, "ledger");
        if (minimumY > maximumY) {
            throw new IllegalArgumentException("height " + minimumY + " to " + maximumY);
        }
        int kept = 0;
        for (long key : field.keys) {
            if (CubeKey.inside(key, minimumY, maximumY)) {
                kept++;
            }
        }
        if (kept == field.keys.length) {
            return new Result(field, ledger);
        }
        long[] keptKeys = new long[kept];
        double[] keptAmounts = new double[kept * CHANNELS];
        double[] book = ledger.values();
        int o = 0;
        for (int i = 0; i < field.keys.length; i++) {
            if (CubeKey.inside(field.keys[i], minimumY, maximumY)) {
                keptKeys[o] = field.keys[i];
                System.arraycopy(field.amounts, i * CHANNELS, keptAmounts, o * CHANNELS, CHANNELS);
                o++;
            } else {
                for (int c = 0; c < CHANNELS; c++) {
                    book[PollutionLedger.slot(c, Total.DISSIPATED)] += field.amounts[i * CHANNELS + c];
                }
            }
        }
        return finish(keptKeys, keptAmounts, book);
    }

    public int size() {
        return keys.length;
    }

    public long key(int i) {
        return keys[i];
    }

    /** The keys, ascending (a copy). */
    public long[] keys() {
        return keys.clone();
    }

    /** The amounts, four per cube in channel order (a copy). */
    public double[] amounts() {
        return amounts.clone();
    }

    /** The position of a cube, or a negative number when it is absent ({@link Arrays#binarySearch} convention). */
    public int find(long key) {
        return Arrays.binarySearch(keys, key);
    }

    public double amount(int i, Channel channel) {
        return amounts[i * CHANNELS + channel.ordinal()];
    }

    /** The amount of a cube's channel; 0 when the cube is absent. */
    public double amount(long key, Channel channel) {
        int i = find(key);
        return i < 0 ? 0.0 : amounts[i * CHANNELS + channel.ordinal()];
    }

    /** The index of a cube's channel with the given air share (floored at {@code closedAirShare}). */
    public double index(long key, Channel channel, double airShare, PollutionSettings settings) {
        return index(amount(key, channel), airShare, settings);
    }

    /** The index of an amount in a cube of the given air share (floored at {@code closedAirShare}). */
    public static double index(double amount, double airShare, PollutionSettings settings) {
        return amount / (Math.max(airShare, settings.closedAirShare()) * CUBE_AIR_MOLES);
    }

    /** The sum of a channel over every cube. */
    public double stored(Channel channel) {
        double sum = 0.0;
        for (int i = channel.ordinal(); i < amounts.length; i += CHANNELS) {
            sum += amounts[i];
        }
        return sum;
    }

    /**
     * Removes {@code min(amount, stored)} of combustible at {@code key} (booked {@code burnt}) and adds
     * {@code sootShare} of what burnt to the cube's particulate (booked {@code released} for particulate), so both
     * channels' ledgers close. A cube left with four zeros leaves the field.
     */
    public static Result burn(PollutionField field, PollutionLedger ledger, long key, double amount, double sootShare) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(ledger, "ledger");
        if (!(amount >= 0.0) || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("burnt amount must be finite and not negative: " + amount);
        }
        if (!(sootShare >= 0.0 && sootShare <= 1.0)) {
            throw new IllegalArgumentException("sootShare must be in [0, 1]: " + sootShare);
        }
        int i = field.find(key);
        if (i < 0) {
            return new Result(field, ledger);
        }
        int combustible = i * CHANNELS + Channel.COMBUSTIBLE.ordinal();
        int particulate = i * CHANNELS + Channel.PARTICULATE.ordinal();
        double have = field.amounts[combustible];
        double burnt = Math.min(amount, have);
        if (!(burnt > 0.0)) {
            return new Result(field, ledger);
        }
        double soot = sootShare * burnt;
        double[] amounts = field.amounts.clone();
        amounts[combustible] = burnt == have ? 0.0 : have - burnt;
        amounts[particulate] += soot;
        double[] book = ledger.values();
        book[PollutionLedger.slot(Channel.COMBUSTIBLE.ordinal(), Total.BURNT)] += burnt;
        book[PollutionLedger.slot(Channel.PARTICULATE.ordinal(), Total.RELEASED)] += soot;
        long[] keys = field.keys;
        if (allZero(amounts, i)) {
            long[] fewerKeys = new long[keys.length - 1];
            double[] fewerAmounts = new double[amounts.length - CHANNELS];
            System.arraycopy(keys, 0, fewerKeys, 0, i);
            System.arraycopy(keys, i + 1, fewerKeys, i, keys.length - i - 1);
            System.arraycopy(amounts, 0, fewerAmounts, 0, i * CHANNELS);
            System.arraycopy(amounts, (i + 1) * CHANNELS, fewerAmounts, i * CHANNELS,
                    amounts.length - (i + 1) * CHANNELS);
            return finish(fewerKeys, fewerAmounts, book);
        }
        return finish(keys, amounts, book);
    }

    /**
     * One pass of the field over {@code seconds} of process time:
     * <ol>
     *   <li>releases land in their cubes (loaded or not) and are booked {@code released};</li>
     *   <li>loaded, open-to-air cubes exchange with their six faces, simultaneously, from the higher index to the lower;
     *       each also drifts a share of its amount to the cube above ({@code riseShare}), whatever the index there;
     *       the dimension's top and bottom are sinks ({@code dissipated}); a cube not yet in the field takes a channel
     *       only when the sum of its inflows gives it an index of at least {@code cutoff};</li>
     *   <li>particulate settles to the cube below (to {@code ground} over a closed cube or the bottom);</li>
     *   <li>loaded cubes decay ({@code decayed}), faster in rain for corrosive and particulate in open cubes;</li>
     *   <li>channels of loaded cubes below {@code cutoff} are zeroed ({@code dissipated}); empty cubes leave;</li>
     *   <li>above {@code maximumCubes}, loaded cubes with the lowest peak index leave first ({@code dissipated}).</li>
     * </ol>
     * Unloaded cubes keep their amounts. Throws {@link IllegalStateException} if any amount comes out non-finite.
     */
    public static Result step(PollutionField field, PollutionLedger ledger, List<Release> releases,
            Environment environment, double seconds, PollutionSettings settings) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(releases, "releases");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(settings, "settings");
        if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
            throw new IllegalArgumentException("seconds must be finite and positive: " + seconds);
        }
        double[] book = ledger.values();
        Work work = Work.of(field, environment);
        Release[] sorted = releases.toArray(new Release[0]);
        Arrays.sort(sorted, RELEASE_ORDER);
        land(work, sorted, environment, book);
        exchange(work, environment, seconds, settings, book);
        settle(work, environment, seconds, settings, book);
        decay(work, environment, seconds, settings, book);
        truncate(work, settings, book);
        cap(work, settings, book);
        return finish(work.keys, work.amounts, book);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The stages.

    private static void land(Work work, Release[] sorted, Environment environment, double[] book) {
        long[] addKeys = new long[sorted.length];
        Cell[] addCells = new Cell[sorted.length];
        int count = 0;
        for (int r = 0; r < sorted.length; r++) {
            long key = sorted[r].key();
            if (r > 0 && sorted[r - 1].key() == key) {
                continue;
            }
            if (Arrays.binarySearch(work.keys, key) >= 0) {
                continue;
            }
            addKeys[count] = key;
            addCells[count] = cell(environment, key);
            count++;
        }
        work.add(addKeys, new double[count * CHANNELS], addCells, count);
        for (Release release : sorted) {
            int i = Arrays.binarySearch(work.keys, release.key());
            int c = release.channel().ordinal();
            work.amounts[i * CHANNELS + c] += release.amount();
            book[PollutionLedger.slot(c, Total.RELEASED)] += release.amount();
        }
    }

    private static void exchange(Work work, Environment environment, double seconds, PollutionSettings settings,
            double[] book) {
        int n = work.keys.length;
        double closed = settings.closedAirShare();
        double k = Math.min(1.0 / 7.0, settings.faceShare() * seconds / settings.nominalSeconds());
        // The upward drift: U = r min(φa, φb) A_a / φa toward the cube above, on top of the face exchange.
        double r = Math.min(1.0 / 7.0, settings.riseShare() * seconds / settings.nominalSeconds());
        int minimumY = environment.minimumY();
        int maximumY = environment.maximumY();
        long[] keys = work.keys;
        double[] a = work.amounts;
        double[] delta = new double[n * CHANNELS];
        Front front = new Front();
        for (int i = 0; i < n; i++) {
            double phiA = work.phi[i];
            if (!work.loaded[i] || phiA < closed) {
                continue;
            }
            long key = keys[i];
            int y = CubeKey.y(key);
            int base = i * CHANNELS;
            for (int d = 0; d < 6; d++) {
                int ny = y + DY[d];
                boolean up = d == UP && r > 0.0;
                if (ny < minimumY || ny > maximumY) {
                    double share = up ? k + r : k;
                    for (int c = 0; c < CHANNELS; c++) {
                        double f = share * a[base + c];
                        delta[base + c] -= f;
                        book[PollutionLedger.slot(c, Total.DISSIPATED)] += f;
                    }
                    continue;
                }
                long neighbour = CubeKey.offset(key, DX[d], DY[d], DZ[d]);
                int j = Arrays.binarySearch(keys, neighbour);
                if (j >= 0) {
                    if (up && work.loaded[j] && work.phi[j] >= closed) {
                        double u = r * Math.min(phiA, work.phi[j]) / phiA;
                        int above = j * CHANNELS;
                        for (int c = 0; c < CHANNELS; c++) {
                            double f = u * a[base + c];
                            delta[base + c] -= f;
                            delta[above + c] += f;
                        }
                    }
                    // Each face between two present cubes is computed once, from the cube with the smaller key.
                    if (neighbour < key || !work.loaded[j] || work.phi[j] < closed) {
                        continue;
                    }
                    double phiB = work.phi[j];
                    double m = k * Math.min(phiA, phiB);
                    int other = j * CHANNELS;
                    for (int c = 0; c < CHANNELS; c++) {
                        // F = k min(φa, φb) (Ia − Ib) N, with I = A / (φ N).
                        double f = m * (a[base + c] / phiA - a[other + c] / phiB);
                        delta[base + c] -= f;
                        delta[other + c] += f;
                    }
                } else {
                    // A new cube above takes the face share and the drift as one inflow, accepted or refused together.
                    front.offer(neighbour, i, a, base, phiA, up ? k + r : k, closed, environment);
                }
            }
        }
        // The front: a new cube takes a channel only when its summed inflow reaches the cutoff.
        int slots = front.size;
        boolean[] accepted = new boolean[slots * CHANNELS];
        int newCubes = 0;
        for (int s = 0; s < slots; s++) {
            double air = Math.max(front.cells[s].airShare(), closed) * CUBE_AIR_MOLES;
            boolean any = false;
            for (int c = 0; c < CHANNELS; c++) {
                double sum = front.sums[s * CHANNELS + c];
                if (sum > 0.0 && sum / air >= settings.cutoff()) {
                    accepted[s * CHANNELS + c] = true;
                    any = true;
                }
            }
            if (any) {
                newCubes++;
            }
        }
        for (int e = 0; e < front.entries; e++) {
            int c = front.channel[e];
            if (accepted[front.slotOf[e] * CHANNELS + c]) {
                delta[front.donor[e] * CHANNELS + c] -= front.amount[e];
            }
        }
        for (int idx = 0; idx < a.length; idx++) {
            double v = a[idx] + delta[idx];
            if (v < 0.0) {
                book[PollutionLedger.slot(idx % CHANNELS, Total.DISSIPATED)] += v;
                v = 0.0;
            }
            a[idx] = v;
        }
        if (newCubes == 0) {
            return;
        }
        long[] addKeys = new long[newCubes];
        double[] addAmounts = new double[newCubes * CHANNELS];
        Cell[] addCells = new Cell[newCubes];
        int t = 0;
        for (int s = 0; s < slots; s++) {
            boolean any = false;
            for (int c = 0; c < CHANNELS; c++) {
                if (accepted[s * CHANNELS + c]) {
                    addAmounts[t * CHANNELS + c] = front.sums[s * CHANNELS + c];
                    any = true;
                }
            }
            if (any) {
                addKeys[t] = front.keys[s];
                addCells[t] = front.cells[s];
                t++;
            }
        }
        work.add(addKeys, addAmounts, addCells, newCubes);
    }

    private static void settle(Work work, Environment environment, double seconds, PollutionSettings settings,
            double[] book) {
        int n = work.keys.length;
        double closed = settings.closedAirShare();
        double share = Math.min(0.1, settings.settlingShare() * seconds / settings.nominalSeconds());
        int p = Channel.PARTICULATE.ordinal();
        int ground = PollutionLedger.slot(p, Total.GROUND);
        int minimumY = environment.minimumY();
        long[] keys = work.keys;
        double[] a = work.amounts;
        // The settled amounts are taken from the amounts after exchange, before any of them moves.
        double[] settled = new double[n];
        for (int i = 0; i < n; i++) {
            if (work.loaded[i] && work.phi[i] >= closed) {
                settled[i] = share * a[i * CHANNELS + p];
            }
        }
        long[] addKeys = null;
        double[] addAmounts = null;
        Cell[] addCells = null;
        int count = 0;
        for (int i = 0; i < n; i++) {
            double f = settled[i];
            if (!(f > 0.0)) {
                continue;
            }
            long key = keys[i];
            if (CubeKey.y(key) - 1 < minimumY) {
                a[i * CHANNELS + p] -= f;
                book[ground] += f;
                continue;
            }
            long below = CubeKey.offset(key, 0, -1, 0);
            int j = Arrays.binarySearch(keys, below);
            if (j >= 0) {
                if (!work.loaded[j]) {
                    continue;
                }
                a[i * CHANNELS + p] -= f;
                if (work.phi[j] < closed) {
                    book[ground] += f;
                } else {
                    a[j * CHANNELS + p] += f;
                }
                continue;
            }
            Cell cell = cell(environment, below);
            if (!cell.loaded()) {
                continue;
            }
            if (cell.airShare() < closed) {
                a[i * CHANNELS + p] -= f;
                book[ground] += f;
                continue;
            }
            if (f / (cell.airShare() * CUBE_AIR_MOLES) < settings.cutoff()) {
                continue;
            }
            a[i * CHANNELS + p] -= f;
            if (addKeys == null) {
                addKeys = new long[n];
                addAmounts = new double[n * CHANNELS];
                addCells = new Cell[n];
            }
            addKeys[count] = below;
            addAmounts[count * CHANNELS + p] = f;
            addCells[count] = cell;
            count++;
        }
        clampNegatives(a, book);
        if (count > 0) {
            work.add(addKeys, addAmounts, addCells, count);
        }
    }

    private static void decay(Work work, Environment environment, double seconds, PollutionSettings settings,
            double[] book) {
        double[] openFactor = new double[CHANNELS];
        for (int c = 0; c < CHANNELS; c++) {
            double halfLife = settings.halfLifeOpenSeconds();
            if (environment.raining() && (c == Channel.CORROSIVE.ordinal() || c == Channel.PARTICULATE.ordinal())) {
                halfLife /= settings.washout();
            }
            openFactor[c] = factor(seconds, halfLife);
        }
        double enclosedFactor = factor(seconds, settings.halfLifeEnclosedSeconds());
        double[] a = work.amounts;
        for (int i = 0; i < work.keys.length; i++) {
            if (!work.loaded[i]) {
                continue;
            }
            boolean open = work.open[i];
            for (int c = 0; c < CHANNELS; c++) {
                int idx = i * CHANNELS + c;
                double v = a[idx];
                if (v == 0.0) {
                    continue;
                }
                double left = v * (open ? openFactor[c] : enclosedFactor);
                book[PollutionLedger.slot(c, Total.DECAYED)] += v - left;
                a[idx] = left;
            }
        }
    }

    private static void truncate(Work work, PollutionSettings settings, double[] book) {
        double closed = settings.closedAirShare();
        double cutoff = settings.cutoff();
        double[] a = work.amounts;
        int n = work.keys.length;
        boolean[] keep = new boolean[n];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (work.loaded[i]) {
                double air = Math.max(work.phi[i], closed) * CUBE_AIR_MOLES;
                for (int c = 0; c < CHANNELS; c++) {
                    int idx = i * CHANNELS + c;
                    double v = a[idx];
                    if (v > 0.0 && v / air < cutoff) {
                        book[PollutionLedger.slot(c, Total.DISSIPATED)] += v;
                        a[idx] = 0.0;
                    }
                }
            }
            if (!allZero(a, i)) {
                keep[i] = true;
                kept++;
            }
        }
        if (kept < n) {
            work.retain(keep, kept);
        }
    }

    private static void cap(Work work, PollutionSettings settings, double[] book) {
        int n = work.keys.length;
        int maximum = settings.maximumCubes();
        if (n <= maximum) {
            return;
        }
        double closed = settings.closedAirShare();
        long[] keys = work.keys;
        double[] a = work.amounts;
        double[] peak = new double[n];
        int loaded = 0;
        for (int i = 0; i < n; i++) {
            if (!work.loaded[i]) {
                continue;
            }
            double air = Math.max(work.phi[i], closed) * CUBE_AIR_MOLES;
            double max = 0.0;
            for (int c = 0; c < CHANNELS; c++) {
                max = Math.max(max, a[i * CHANNELS + c] / air);
            }
            peak[i] = max;
            loaded++;
        }
        Integer[] order = new Integer[loaded];
        int o = 0;
        for (int i = 0; i < n; i++) {
            if (work.loaded[i]) {
                order[o++] = i;
            }
        }
        Arrays.sort(order, (Integer p, Integer q) -> {
            int byPeak = Double.compare(peak[p], peak[q]);
            return byPeak != 0 ? byPeak : Long.compare(keys[p], keys[q]);
        });
        boolean[] keep = new boolean[n];
        Arrays.fill(keep, true);
        int remaining = n;
        for (Integer i : order) {
            if (remaining <= maximum) {
                break;
            }
            keep[i] = false;
            remaining--;
            for (int c = 0; c < CHANNELS; c++) {
                book[PollutionLedger.slot(c, Total.DISSIPATED)] += a[i * CHANNELS + c];
            }
        }
        if (remaining < n) {
            work.retain(keep, remaining);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers.

    private static Result finish(long[] keys, double[] amounts, double[] book) {
        for (int i = 0; i < amounts.length; i++) {
            if (!Double.isFinite(amounts[i])) {
                throw new IllegalStateException("pollution step produced a non-finite amount at cube "
                        + keys[i / CHANNELS] + " channel " + Channel.values()[i % CHANNELS] + ": " + amounts[i]);
            }
        }
        for (int i = 0; i < book.length; i++) {
            if (!Double.isFinite(book[i])) {
                throw new IllegalStateException("pollution step produced a non-finite ledger total " + i + ": "
                        + book[i]);
            }
            if (book[i] < 0.0) {
                book[i] = 0.0; // only a rounding clamp booked against an empty total can reach here
            }
        }
        return new Result(new PollutionField(keys, amounts), new PollutionLedger(book));
    }

    private static double factor(double seconds, double halfLife) {
        return DeterministicMath.exp(-LN2 * seconds / halfLife);
    }

    private static boolean allZero(double[] amounts, int i) {
        int base = i * CHANNELS;
        for (int c = 0; c < CHANNELS; c++) {
            if (amounts[base + c] != 0.0) {
                return false;
            }
        }
        return true;
    }

    private static void clampNegatives(double[] amounts, double[] book) {
        for (int idx = 0; idx < amounts.length; idx++) {
            if (amounts[idx] < 0.0) {
                book[PollutionLedger.slot(idx % CHANNELS, Total.DISSIPATED)] += amounts[idx];
                amounts[idx] = 0.0;
            }
        }
    }

    private static Cell cell(Environment environment, long key) {
        return Objects.requireNonNull(environment.cell(key), "environment returned no cell");
    }

    /** The mutable working copy of one step: sorted keys, amounts and the cubes' cells. */
    private static final class Work {
        long[] keys;
        double[] amounts;
        double[] phi;
        boolean[] open;
        boolean[] loaded;

        static Work of(PollutionField field, Environment environment) {
            Work work = new Work();
            int n = field.keys.length;
            work.keys = field.keys.clone();
            work.amounts = field.amounts.clone();
            work.phi = new double[n];
            work.open = new boolean[n];
            work.loaded = new boolean[n];
            for (int i = 0; i < n; i++) {
                Cell cell = cell(environment, work.keys[i]);
                work.phi[i] = cell.airShare();
                work.open[i] = cell.open();
                work.loaded[i] = cell.loaded();
            }
            return work;
        }

        /** Adds {@code count} cubes not yet present, with distinct keys in any order. */
        void add(long[] addKeys, double[] addAmounts, Cell[] addCells, int count) {
            if (count == 0) {
                return;
            }
            Integer[] order = new Integer[count];
            for (int t = 0; t < count; t++) {
                order[t] = t;
            }
            Arrays.sort(order, (Integer p, Integer q) -> Long.compare(addKeys[p], addKeys[q]));
            int size = keys.length;
            int n = size + count;
            long[] mergedKeys = new long[n];
            double[] mergedAmounts = new double[n * CHANNELS];
            double[] mergedPhi = new double[n];
            boolean[] mergedOpen = new boolean[n];
            boolean[] mergedLoaded = new boolean[n];
            int i = 0;
            int t = 0;
            for (int o = 0; o < n; o++) {
                boolean old;
                if (t >= count) {
                    old = true;
                } else if (i >= size) {
                    old = false;
                } else {
                    long next = addKeys[order[t]];
                    if (keys[i] == next) {
                        throw new IllegalStateException("cube " + next + " added twice");
                    }
                    old = keys[i] < next;
                }
                if (old) {
                    mergedKeys[o] = keys[i];
                    System.arraycopy(amounts, i * CHANNELS, mergedAmounts, o * CHANNELS, CHANNELS);
                    mergedPhi[o] = phi[i];
                    mergedOpen[o] = open[i];
                    mergedLoaded[o] = loaded[i];
                    i++;
                } else {
                    int s = order[t];
                    if (o > 0 && mergedKeys[o - 1] == addKeys[s]) {
                        throw new IllegalStateException("cube " + addKeys[s] + " added twice");
                    }
                    mergedKeys[o] = addKeys[s];
                    System.arraycopy(addAmounts, s * CHANNELS, mergedAmounts, o * CHANNELS, CHANNELS);
                    mergedPhi[o] = addCells[s].airShare();
                    mergedOpen[o] = addCells[s].open();
                    mergedLoaded[o] = addCells[s].loaded();
                    t++;
                }
            }
            keys = mergedKeys;
            amounts = mergedAmounts;
            phi = mergedPhi;
            open = mergedOpen;
            loaded = mergedLoaded;
        }

        /** Keeps the cubes marked in {@code keep} ({@code kept} of them), in order. */
        void retain(boolean[] keep, int kept) {
            long[] keptKeys = new long[kept];
            double[] keptAmounts = new double[kept * CHANNELS];
            double[] keptPhi = new double[kept];
            boolean[] keptOpen = new boolean[kept];
            boolean[] keptLoaded = new boolean[kept];
            int o = 0;
            for (int i = 0; i < keys.length; i++) {
                if (!keep[i]) {
                    continue;
                }
                keptKeys[o] = keys[i];
                System.arraycopy(amounts, i * CHANNELS, keptAmounts, o * CHANNELS, CHANNELS);
                keptPhi[o] = phi[i];
                keptOpen[o] = open[i];
                keptLoaded[o] = loaded[i];
                o++;
            }
            keys = keptKeys;
            amounts = keptAmounts;
            phi = keptPhi;
            open = keptOpen;
            loaded = keptLoaded;
        }
    }

    /**
     * Tentative inflows into cubes not yet in the field, grouped per new cube. Entries are recorded in the exchange's
     * visiting order (ascending donor key, fixed face order), so the sums are deterministic; the hash map only finds a
     * slot and never orders anything.
     */
    private static final class Front {
        final HashMap<Long, Integer> slots = new HashMap<>();
        long[] keys = new long[16];
        Cell[] cells = new Cell[16];
        double[] sums = new double[16 * CHANNELS];
        int size;
        int[] slotOf = new int[64];
        int[] donor = new int[64];
        int[] channel = new int[64];
        double[] amount = new double[64];
        int entries;

        void offer(long key, int donorIndex, double[] a, int base, double phiA, double k, double closed,
                Environment environment) {
            Integer known = slots.get(key);
            Cell cell = known != null ? cells[known] : cell(environment, key);
            if (!cell.loaded() || cell.airShare() < closed) {
                return;
            }
            double m = k * Math.min(phiA, cell.airShare());
            int slot = known != null ? known : -1;
            for (int c = 0; c < CHANNELS; c++) {
                double f = m * (a[base + c] / phiA);
                if (!(f > 0.0)) {
                    continue;
                }
                if (slot < 0) {
                    slot = open(key, cell);
                }
                record(slot, donorIndex, c, f);
                sums[slot * CHANNELS + c] += f;
            }
        }

        private int open(long key, Cell cell) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size * 2);
                cells = Arrays.copyOf(cells, size * 2);
                sums = Arrays.copyOf(sums, size * 2 * CHANNELS);
            }
            keys[size] = key;
            cells[size] = cell;
            slots.put(key, size);
            return size++;
        }

        private void record(int slot, int donorIndex, int c, double f) {
            if (entries == slotOf.length) {
                int grown = entries * 2;
                slotOf = Arrays.copyOf(slotOf, grown);
                donor = Arrays.copyOf(donor, grown);
                channel = Arrays.copyOf(channel, grown);
                amount = Arrays.copyOf(amount, grown);
            }
            slotOf[entries] = slot;
            donor[entries] = donorIndex;
            channel[entries] = c;
            amount[entries] = f;
            entries++;
        }
    }
}
