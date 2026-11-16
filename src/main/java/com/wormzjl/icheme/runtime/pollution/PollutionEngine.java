package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.Environment;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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

    /** One pass: the step over an immutable snapshot. {@link #run} is what the worker calls. */
    public record Pass(PollutionField field, PollutionLedger ledger, List<Release> releases, Environment environment,
            double seconds, PollutionSettings settings) {
        public Pass {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(ledger, "ledger");
            releases = List.copyOf(releases);
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(settings, "settings");
            if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
                throw new IllegalArgumentException("pass seconds must be finite and positive: " + seconds);
            }
        }

        public PollutionField.Result run() {
            return PollutionField.step(field, ledger, releases, environment, seconds, settings);
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
    private final TreeMap<PendingKey, Double> pending = new TreeMap<>();
    private Pass inFlight;
    /** A {@link #clear} while a pass was in flight: its result and its releases are dropped when it ends. */
    private boolean discardInFlight;
    /** What burnt (WP6a) since the pass in flight began: applied again to its result, which was stepped from the field before them. */
    private final List<Burn> burntInFlight = new ArrayList<>();
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

    /**
     * Takes the pending releases into a pass over {@code environment}; empty while a pass is in flight, or when nothing is
     * stored and nothing waits.
     */
    public Optional<Pass> begin(Environment environment, double seconds, PollutionSettings settings) {
        if (inFlight != null || idle()) {
            return Optional.empty();
        }
        var pass = new Pass(field, ledger, pending(), environment, seconds, settings);
        pending.clear();
        inFlight = pass;
        discardInFlight = false;
        burntInFlight.clear();
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

    /** Installs the pass's result. A result with no pass in flight is ignored; one of a pass a {@link #clear} overtook is dropped. */
    public void complete(PollutionField.Result result) {
        Objects.requireNonNull(result, "result");
        if (inFlight == null) {
            return;
        }
        boolean discard = discardInFlight;
        inFlight = null;
        discardInFlight = false;
        failures = 0;
        if (discard) {
            burntInFlight.clear();
            return;
        }
        var installed = result;
        // The burns made while the pass ran (WP6a): its result was stepped from the field before them.
        for (var burn : burntInFlight) {
            installed = PollutionField.burn(installed.field(), installed.ledger(), burn.cube(), burn.amount(), burn.sootShare());
        }
        burntInFlight.clear();
        field = installed.field();
        ledger = installed.ledger();
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
        // The installed field already holds the burns made meanwhile; nothing replaces it.
        burntInFlight.clear();
        if (discard) {
            return;
        }
        // Back past the cap: these releases were admitted once.
        for (var release : pass.releases()) {
            pending.merge(new PendingKey(release.key(), release.channel()), release.amount(), Double::sum);
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
        burntInFlight.clear();
        if (inFlight != null) {
            discardInFlight = true;
        }
    }

    /** The load: the saved field, ledger and pending releases; no pass in flight. */
    public void restore(PollutionField field, PollutionLedger ledger, List<Release> pending) {
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

    /** Nothing stored and nothing waiting. */
    public boolean idle() {
        return field.size() == 0 && pending.isEmpty();
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

    /** The cubes that hold something or wait for a release, ascending. */
    public Set<Long> core() {
        var out = new TreeSet<Long>();
        for (int i = 0; i < field.size(); i++) {
            out.add(field.key(i));
        }
        for (var key : pending.keySet()) {
            out.add(key.cube());
        }
        return out;
    }

    /** The cubes the next pass's environment must describe: {@link #core} and the six face neighbours of each, ascending. */
    public Set<Long> wanted() {
        var core = core();
        var out = new TreeSet<Long>(core);
        for (long cube : core) {
            out.add(CubeKey.offset(cube, -1, 0, 0));
            out.add(CubeKey.offset(cube, 1, 0, 0));
            out.add(CubeKey.offset(cube, 0, -1, 0));
            out.add(CubeKey.offset(cube, 0, 1, 0));
            out.add(CubeKey.offset(cube, 0, 0, -1));
            out.add(CubeKey.offset(cube, 0, 0, 1));
        }
        return out;
    }
}
