package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.Environment;
import com.wormzjl.icheme.science.pollution.GroundEnvironment;
import com.wormzjl.icheme.science.pollution.GroundField;
import com.wormzjl.icheme.science.pollution.GroundLedger;
import com.wormzjl.icheme.science.pollution.GroundSettings;
import com.wormzjl.icheme.science.pollution.GroundSnapshot;
import com.wormzjl.icheme.science.pollution.KeySets;
import com.wormzjl.icheme.science.pollution.PollutionField;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import com.wormzjl.icheme.science.pollution.PollutionSettings;
import com.wormzjl.icheme.science.pollution.Release;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The pollution of one dimension between passes (batch 2026-11-15-pollution-system, WP3; plan section 5): the installed
 * field and ledger, the releases waiting for the next pass, and the one pass in flight. No Minecraft type; the server
 * thread owns it ({@link PollutionService}), a worker runs only an immutable {@link Pass}.
 *
 * <p>Nothing is lost: a release waits in {@code pending} until a pass takes it; a pass that fails or is refused puts its
 * releases back. Pending releases are summed per cube and channel, in key order, so the list a pass takes is
 * deterministic and bounded ({@link #MAXIMUM_PENDING}).</p>
 */
public final class PollutionEngine {
    /** The most (cube, channel) entries waiting for a pass; a release of a new entry beyond it is dropped and counted. */
    public static final int MAXIMUM_PENDING = 65_536;

    /**
     * One pass: the air step, then the ground step (batch 2026-11-18-ground-pollution, G2) over immutable snapshots, on one
     * worker. The ground takes only the pending deposits: the air gives it nothing (owner, 2026-11-18: "do not let ground
     * absorb air pollution for now"). {@link #run} is what the worker calls.
     */
    public record Pass(PollutionField field, PollutionLedger ledger, List<Release> releases, Environment environment,
            double seconds, PollutionSettings settings, GroundField ground, GroundLedger groundLedger,
            List<GroundField.Deposit> deposits, GroundEnvironment groundEnvironment, GroundSettings groundSettings) {
        public Pass {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(ledger, "ledger");
            releases = List.copyOf(releases);
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(settings, "settings");
            if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
                throw new IllegalArgumentException("pass seconds must be finite and positive: " + seconds);
            }
            Objects.requireNonNull(ground, "ground");
            Objects.requireNonNull(groundLedger, "groundLedger");
            deposits = List.copyOf(deposits);
            Objects.requireNonNull(groundEnvironment, "groundEnvironment");
            Objects.requireNonNull(groundSettings, "groundSettings");
        }

        public Outcome run() {
            var air = PollutionField.step(field, ledger, releases, environment, seconds, settings);
            var groundResult = GroundField.step(ground, groundLedger, deposits, groundEnvironment, seconds, groundSettings);
            return new Outcome(air, groundResult);
        }
    }

    /** A pass's result: the air field's and the ground's. */
    public record Outcome(PollutionField.Result air, GroundField.Result ground) {
        public Outcome {
            Objects.requireNonNull(air, "air");
            Objects.requireNonNull(ground, "ground");
        }
    }

    /** The deposits of one column waiting for a pass: the amount summed, compounds summed by name. */
    private static final class PendingColumn {
        double amount;
        final TreeMap<String, Double> compounds = new TreeMap<>();

        void add(double a, List<GroundField.Compound> list) {
            amount += a;
            for (var compound : list) compounds.merge(compound.name(), compound.moles(), Double::sum);
        }

        void add(GroundField.Deposit deposit) {
            add(deposit.amount(), deposit.compounds());
        }

        GroundField.Deposit deposit(long column) {
            var list = new ArrayList<GroundField.Compound>();
            for (var e : compounds.entrySet()) if (e.getValue() > 0.0) list.add(new GroundField.Compound(e.getKey(), e.getValue()));
            return new GroundField.Deposit(column, amount, list);
        }
    }

    /** A burn of {@code amount} combustible at {@code cube}, {@code sootShare} of it to particulate (WP6a). */
    private record Burn(long cube, double amount, double sootShare) {
    }

    private record PendingKey(long cube, Channel channel) implements Comparable<PendingKey> {
        @Override
        public int compareTo(PendingKey other) {
            int byCube = Long.compare(cube, other.cube);
            return byCube != 0 ? byCube : Integer.compare(channel.ordinal(), other.channel.ordinal());
        }
    }

    private PollutionField field = PollutionField.EMPTY;
    private PollutionLedger ledger = PollutionLedger.EMPTY;
    private GroundField ground = GroundField.EMPTY;
    private GroundLedger groundLedger = GroundLedger.EMPTY;
    private GroundSettings groundSettings = GroundSettings.DEFAULT;
    private GroundSnapshot groundSnapshot = GroundSnapshot.EMPTY;
    private final TreeMap<Long, PendingColumn> pendingDeposits = new TreeMap<>();
    private final TreeMap<PendingKey, Double> pending = new TreeMap<>();
    private Pass inFlight;
    /** A {@link #clear} while a pass was in flight: its result and its releases are dropped when it ends. */
    private boolean discardInFlight;
    /** What burnt (WP6a) since the pass in flight began: applied again to its result, which was stepped from the field before them. */
    private final List<Burn> burntInFlight = new ArrayList<>();
    /** What went to the ground (a waste outfall's rest) since the pass in flight began, per channel: booked again on its result's ledger. */
    private final double[] groundInFlight = new double[Channel.values().length];
    private int failures;
    private String lastFailure = "";
    private long ignored;
    private long dropped;

    /**
     * Adds {@code amount} (air-mole equivalents) to the cube's channel for the next pass. A non-finite or negative amount
     * is ignored and counted ({@link #ignored}); a new entry beyond {@link #MAXIMUM_PENDING} is dropped and counted
     * ({@link #dropped}).
     */
    public void release(long cube, Channel channel, double amount) {
        if (channel == null || !(amount >= 0.0) || !Double.isFinite(amount)) {
            ignored++;
            return;
        }
        if (amount == 0.0) {
            return;
        }
        var key = new PendingKey(cube, channel);
        Double before = pending.get(key);
        if (before == null && pending.size() >= MAXIMUM_PENDING) {
            dropped++;
            return;
        }
        double sum = before == null ? amount : before + amount;
        if (!Double.isFinite(sum)) {
            ignored++;
            return;
        }
        pending.put(key, sum);
    }

    /** {@link #begin(Environment, GroundEnvironment, double, PollutionSettings, GroundSettings)} with every column loaded and the default ground constants. */
    public Optional<Pass> begin(Environment environment, double seconds, PollutionSettings settings) {
        return begin(environment, GroundEnvironment.open(environment.raining()), seconds, settings, GroundSettings.DEFAULT);
    }

    /**
     * Takes the pending releases and deposits into a pass over {@code environment} and {@code groundEnvironment}; empty
     * while a pass is in flight, or when nothing is stored and nothing waits.
     */
    public Optional<Pass> begin(Environment environment, GroundEnvironment groundEnvironment, double seconds,
            PollutionSettings settings, GroundSettings groundSettings) {
        if (inFlight != null || idle()) {
            return Optional.empty();
        }
        this.groundSettings = Objects.requireNonNull(groundSettings, "groundSettings");
        var pass = new Pass(field, ledger, pending(), environment, seconds, settings, ground, groundLedger,
                pendingDeposits(), groundEnvironment, groundSettings);
        pending.clear();
        pendingDeposits.clear();
        inFlight = pass;
        discardInFlight = false;
        burntInFlight.clear();
        java.util.Arrays.fill(groundInFlight, 0.0);
        return Optional.of(pass);
    }

    /**
     * Burns up to {@code amount} combustible at {@code cube} in the installed field, {@code sootShare} of what burnt to its
     * particulate ({@link PollutionField#burn}; WP6a, a blast). The installed field shows it at once, so the cube cannot
     * ignite twice. A pass in flight was begun from the field before the burn and its result replaces the installed field:
     * the burn is applied again to that result ({@link #complete}), capped by what the result holds, so it is not lost and
     * the ledger still closes. Returns what burnt; 0 for a non-finite, negative or zero amount.
     */
    public double burn(long cube, double amount, double sootShare) {
        if (!(amount > 0.0) || !Double.isFinite(amount)) {
            return 0.0;
        }
        if (!(sootShare >= 0.0 && sootShare <= 1.0)) {
            throw new IllegalArgumentException("sootShare must be in [0, 1]: " + sootShare);
        }
        double burnt = Math.min(amount, field.amount(cube, Channel.COMBUSTIBLE));
        if (!(burnt > 0.0)) {
            return 0.0;
        }
        var result = PollutionField.burn(field, ledger, cube, burnt, sootShare);
        field = result.field();
        ledger = result.ledger();
        if (inFlight != null && !discardInFlight) {
            burntInFlight.add(new Burn(cube, burnt, sootShare));
        }
        return burnt;
    }

    /**
     * Puts {@code amounts} (air-mole equivalents, by air channel) and {@code compounds} on the ground of {@code column}
     * (batch 2026-11-18-ground-pollution; what a waste outfall or a spill leaves on the ground): the ground takes their sum
     * as its one amount. The air ledger books each channel's amount {@code RELEASED} and {@code GROUND} at once
     * ({@link PollutionLedger#grounded}), and again on the result of a
     * pass in flight, which was stepped from the ledger before it; the deposit waits for the next pass, which lands it in
     * the ground field. Nothing enters the air field. A deposit with a non-finite or negative value is ignored and counted;
     * a new column beyond {@link #MAXIMUM_PENDING} is dropped and counted.
     */
    public void deposit(long column, double[] amounts, List<GroundField.Compound> compounds) {
        GroundField.Deposit deposit;
        try {
            if (amounts == null || amounts.length != Channel.values().length) throw new IllegalArgumentException("four channel amounts");
            double sum = 0.0;
            for (double a : amounts) {
                if (!(a >= 0.0) || !Double.isFinite(a)) throw new IllegalArgumentException("amount " + a);
                sum += a;
            }
            deposit = new GroundField.Deposit(column, sum, compounds);
        } catch (RuntimeException invalid) {
            ignored++;
            return;
        }
        boolean any = deposit.amount() > 0.0;
        for (var c : deposit.compounds()) any |= c.moles() > 0.0;
        if (!any) {
            return;
        }
        if (!pendingDeposits.containsKey(column) && pendingDeposits.size() >= MAXIMUM_PENDING) {
            dropped++;
            return;
        }
        pendingDeposits.computeIfAbsent(column, k -> new PendingColumn()).add(deposit);
        for (Channel channel : Channel.values()) {
            double amount = amounts[channel.ordinal()];
            if (!(amount > 0.0)) continue;
            ledger = ledger.grounded(channel, amount);
            if (inFlight != null && !discardInFlight) groundInFlight[channel.ordinal()] += amount;
        }
    }

    /** Installs the pass's result. A result with no pass in flight is ignored; one of a pass a {@link #clear} overtook is dropped. */
    public void complete(Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        var result = outcome.air();
        if (inFlight == null) {
            return;
        }
        boolean discard = discardInFlight;
        inFlight = null;
        discardInFlight = false;
        failures = 0;
        if (discard) {
            burntInFlight.clear();
            java.util.Arrays.fill(groundInFlight, 0.0);
            return;
        }
        var installed = result;
        // The burns made while the pass ran (WP6a): its result was stepped from the field before them.
        for (var burn : burntInFlight) {
            installed = PollutionField.burn(installed.field(), installed.ledger(), burn.cube(), burn.amount(), burn.sootShare());
        }
        burntInFlight.clear();
        field = installed.field();
        var book = installed.ledger();
        for (Channel channel : Channel.values()) book = book.grounded(channel, groundInFlight[channel.ordinal()]);
        java.util.Arrays.fill(groundInFlight, 0.0);
        ledger = book;
        ground = outcome.ground().field();
        groundLedger = outcome.ground().ledger();
        groundSnapshot = GroundSnapshot.of(ground, groundSettings);
    }

    /** The pass failed: its releases wait again, the field stays, and the failure counts. Also counts a failure before any pass began. */
    public void failed(String detail) {
        giveBack();
        failures++;
        lastFailure = detail == null ? "" : detail;
    }

    /** The pass was not admitted (the pool at capacity, the service stopping): its releases wait again; no failure counts. */
    public void refused() {
        giveBack();
    }

    private void giveBack() {
        if (inFlight == null) {
            return;
        }
        var pass = inFlight;
        boolean discard = discardInFlight;
        inFlight = null;
        discardInFlight = false;
        // The installed field already holds the burns made meanwhile, its ledger the ground bookings; nothing replaces them.
        burntInFlight.clear();
        java.util.Arrays.fill(groundInFlight, 0.0);
        if (discard) {
            return;
        }
        // Back past the cap: these releases were admitted once.
        for (var release : pass.releases()) {
            pending.merge(new PendingKey(release.key(), release.channel()), release.amount(), Double::sum);
        }
        for (var deposit : pass.deposits()) {
            pendingDeposits.computeIfAbsent(deposit.column(), k -> new PendingColumn()).add(deposit);
        }
    }

    /**
     * Empties the field and the pending releases. What the field stored is booked {@code dissipated}, so the ledger
     * still closes; a pass in flight is dropped when it ends.
     */
    public void clear() {
        double[] book = ledger.values();
        int totals = PollutionLedger.Total.values().length;
        for (Channel channel : Channel.values()) {
            book[channel.ordinal() * totals + PollutionLedger.Total.DISSIPATED.ordinal()] += field.stored(channel);
        }
        ledger = PollutionLedger.of(book);
        field = PollutionField.EMPTY;
        pending.clear();
        // The ground too: what it stored is booked capped (removed by a rule, not by its own decay).
        double[] groundBook = groundLedger.totals();
        int groundTotals = GroundLedger.LENGTH;
        groundBook[GroundLedger.Total.CAPPED.ordinal()] += ground.stored();
        var groundCompounds = new TreeMap<String, double[]>(groundLedger.compounds());
        for (var e : ground.entries()) {
            groundCompounds.computeIfAbsent(e.name(), k -> new double[groundTotals])[GroundLedger.Total.CAPPED.ordinal()] += e.moles();
        }
        groundLedger = GroundLedger.of(groundBook, groundCompounds);
        ground = GroundField.EMPTY;
        groundSnapshot = GroundSnapshot.EMPTY;
        pendingDeposits.clear();
        burntInFlight.clear();
        java.util.Arrays.fill(groundInFlight, 0.0);
        if (inFlight != null) {
            discardInFlight = true;
        }
    }

    /** The load: the saved air field, ledger and pending releases, with no ground. */
    public void restore(PollutionField field, PollutionLedger ledger, List<Release> pending) {
        restore(field, ledger, pending, GroundField.EMPTY, GroundLedger.EMPTY, List.of());
    }

    /** The load: the saved air field, ledger and pending releases, and the ground's; no pass in flight. */
    public void restore(PollutionField field, PollutionLedger ledger, List<Release> pending, GroundField ground,
            GroundLedger groundLedger, List<GroundField.Deposit> groundPending) {
        this.ground = Objects.requireNonNull(ground, "ground");
        this.groundLedger = Objects.requireNonNull(groundLedger, "groundLedger");
        this.groundSnapshot = GroundSnapshot.of(ground, groundSettings);
        this.pendingDeposits.clear();
        for (var deposit : groundPending) {
            pendingDeposits.computeIfAbsent(deposit.column(), k -> new PendingColumn()).add(deposit);
        }
        this.field = Objects.requireNonNull(field, "field");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.pending.clear();
        inFlight = null;
        discardInFlight = false;
        burntInFlight.clear();
        for (var release : pending) {
            this.pending.merge(new PendingKey(release.key(), release.channel()), release.amount(), Double::sum);
        }
    }

    /** The installed field (before the pass in flight, if any). */
    public PollutionField field() {
        return field;
    }

    /** The installed ground (before the pass in flight, if any). */
    public GroundField ground() {
        return ground;
    }

    public GroundLedger groundLedger() {
        return groundLedger;
    }

    /** The ground's published bytes, for the server-thread readers (map, meter, world effects). */
    public GroundSnapshot groundSnapshot() {
        return groundSnapshot;
    }

    /** The deposits waiting for the next pass, one per column, ascending. */
    public List<GroundField.Deposit> pendingDeposits() {
        var out = new ArrayList<GroundField.Deposit>(pendingDeposits.size());
        for (var e : pendingDeposits.entrySet()) out.add(e.getValue().deposit(e.getKey()));
        return Collections.unmodifiableList(out);
    }

    /** What a save writes as pending deposits: the waiting ones and those of the pass in flight. */
    public List<GroundField.Deposit> pendingDepositsForSave() {
        if (inFlight == null || discardInFlight) {
            return pendingDeposits();
        }
        var merged = new TreeMap<Long, PendingColumn>();
        for (var e : pendingDeposits.entrySet()) merged.computeIfAbsent(e.getKey(), k -> new PendingColumn()).add(e.getValue().deposit(e.getKey()));
        for (var d : inFlight.deposits()) merged.computeIfAbsent(d.column(), k -> new PendingColumn()).add(d);
        var out = new ArrayList<GroundField.Deposit>(merged.size());
        for (var e : merged.entrySet()) out.add(e.getValue().deposit(e.getKey()));
        return Collections.unmodifiableList(out);
    }

    /** The columns the next pass's ground environment must describe: the ground's and the pending deposits', and their four neighbours, ascending. */
    public long[] groundWanted() {
        long[] columns = new long[ground.size()];
        for (int i = 0; i < columns.length; i++) columns[i] = ground.column(i);
        long[] waiting = new long[pendingDeposits.size()];
        int n = 0;
        for (long column : pendingDeposits.keySet()) waiting[n++] = column;
        return KeySets.columnFaces(KeySets.union(columns, waiting));
    }

    public PollutionLedger ledger() {
        return ledger;
    }

    /** The releases waiting for the next pass, summed per cube and channel, in key order. */
    public List<Release> pending() {
        var out = new ArrayList<Release>(pending.size());
        for (var entry : pending.entrySet()) {
            out.add(new Release(entry.getKey().cube(), entry.getKey().channel(), entry.getValue()));
        }
        return Collections.unmodifiableList(out);
    }

    /** What a save writes as pending: the waiting releases and those of the pass in flight, so a save during a job loses nothing. */
    public List<Release> pendingForSave() {
        if (inFlight == null || discardInFlight) {
            return pending();
        }
        var merged = new TreeMap<PendingKey, Double>(pending);
        for (var release : inFlight.releases()) {
            merged.merge(new PendingKey(release.key(), release.channel()), release.amount(), Double::sum);
        }
        var out = new ArrayList<Release>(merged.size());
        for (Map.Entry<PendingKey, Double> entry : merged.entrySet()) {
            out.add(new Release(entry.getKey().cube(), entry.getKey().channel(), entry.getValue()));
        }
        return Collections.unmodifiableList(out);
    }

    /** Nothing stored and nothing waiting, in the air or on the ground. */
    public boolean idle() {
        return field.size() == 0 && pending.isEmpty() && ground.size() == 0 && pendingDeposits.isEmpty();
    }


    public boolean inFlight() {
        return inFlight != null;
    }

    /** Failures in a row since the last installed pass. */
    public int failures() {
        return failures;
    }

    public String lastFailure() {
        return lastFailure;
    }

    /** Releases ignored as non-finite or negative. */
    public long ignored() {
        return ignored;
    }

    /** Releases dropped at the pending cap. */
    public long dropped() {
        return dropped;
    }

    /** The cubes that hold something or wait for a release: a sorted set ({@link KeySets}). */
    public long[] core() {
        long[] cubes = new long[field.size()];
        for (int i = 0; i < cubes.length; i++) {
            cubes[i] = field.key(i);
        }
        // The pending keys are in cube order, a cube once per channel.
        long[] waiting = new long[pending.size()];
        int n = 0;
        for (var key : pending.keySet()) {
            if (n == 0 || waiting[n - 1] != key.cube()) {
                waiting[n++] = key.cube();
            }
        }
        return KeySets.union(cubes, java.util.Arrays.copyOf(waiting, n));
    }

    /** The cubes the next pass's environment must describe: {@link #core} and the six face neighbours of each, ascending. */
    public long[] wanted() {
        return KeySets.cubeFaces(core());
    }
}
