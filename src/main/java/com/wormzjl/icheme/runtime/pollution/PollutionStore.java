package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.PollutionField;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import com.wormzjl.icheme.science.pollution.Release;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Overworld saved data {@value #DATA_NAME} (batch 2026-11-15-pollution-system, WP3; plan section 8): every dimension's
 * pollution field, ledger and pending releases. Facts, schema {@link #SCHEMA}.
 *
 * <p>Crash-proof (owner, 2026-11-15): unlike the fluid facts, this store never refuses a world. {@link #read} never
 * throws: a newer schema or a damaged root sets the whole store aside (the service copies the file to
 * {@code icheme_pollution.damaged-<n>.dat} and starts empty); a damaged dimension or cube is dropped alone, the rest
 * kept. {@link #save} never throws into the world save. A dimension that is no loaded level is carried through every
 * save untouched.</p>
 *
 * <p>Layout: {@code schema} int; {@code dimensions} compound keyed by dimension id, each {@code keys} (long array),
 * {@code amounts} (long array of {@link Double#doubleToRawLongBits}, four per key in channel order), {@code ledger} (long
 * array of 20 double bits, channel-major), {@code pending_keys} (long array), {@code pending_channels} (list of channel
 * names), {@code pending_amounts} (long array of double bits).</p>
 */
public final class PollutionStore extends SavedData {
    public static final String DATA_NAME = "icheme_pollution";
    /** Facts schema of this store (documentation/reference/FORMATS.md). */
    public static final int SCHEMA = 1;
    /** The largest amount a saved cube channel may hold: index 10^6 in an open cube. */
    public static final double MAXIMUM_AMOUNT = 1e6 * PollutionField.CUBE_AIR_MOLES;
    private static final int CHANNELS = Channel.values().length;
    private static final int TOTALS = PollutionLedger.Total.values().length;

    /** One dimension's saved facts. */
    public record Saved(PollutionField field, PollutionLedger ledger, List<Release> pending) {
        public Saved {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(ledger, "ledger");
            pending = List.copyOf(pending);
        }

        boolean empty() {
            return field.size() == 0 && pending.isEmpty();
        }
    }

    /**
     * What {@link #read} found: the dimensions it kept, a note per thing it dropped or rebuilt, and whether the whole store
     * is set aside (a newer schema or a damaged root; then {@code dimensions} is empty).
     */
    public record Loaded(Map<String, Saved> dimensions, List<String> notes, boolean setAside) {
        public Loaded {
            dimensions = Collections.unmodifiableMap(new TreeMap<>(dimensions));
            notes = List.copyOf(notes);
        }
    }

    private final Map<String, Saved> carried;
    private final List<String> notes;
    private final boolean setAside;
    private Supplier<Map<String, Saved>> live = Map::of;
    private CompoundTag lastWritten;

    PollutionStore(Loaded loaded) {
        carried = new TreeMap<>(loaded.dimensions());
        notes = loaded.notes();
        setAside = loaded.setAside();
    }

    /** An empty store: a fresh world, or the start after a set-aside. */
    PollutionStore() {
        this(new Loaded(Map.of(), List.of(), false));
    }

    /** The dimensions read at the load, by id. */
    Map<String, Saved> carried() {
        return Collections.unmodifiableMap(carried);
    }

    List<String> notes() {
        return notes;
    }

    boolean setAside() {
        return setAside;
    }

    /** The service's current dimensions, by id; they replace the carried ones at every save. */
    void live(Supplier<Map<String, Saved>> live) {
        this.live = Objects.requireNonNull(live, "live");
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            var all = new TreeMap<>(carried);
            for (var entry : live.get().entrySet()) {
                all.remove(entry.getKey());
                if (!entry.getValue().empty()) {
                    all.put(entry.getKey(), entry.getValue());
                }
            }
            var written = write(all);
            lastWritten = written.copy();
            for (String key : written.getAllKeys()) {
                tag.put(key, Objects.requireNonNull(written.get(key)));
            }
            return tag;
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution_store status=SAVE_FAILED detail={}", String.valueOf(failure));
            var fallback = lastWritten != null ? lastWritten.copy() : schemaOnly();
            return fallback;
        }
    }

    private static CompoundTag schemaOnly() {
        var tag = new CompoundTag();
        tag.putInt("schema", SCHEMA);
        return tag;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The codec: pure, no server.

    /** The tag of {@code dimensions}, ids in order. Every value round-trips bit for bit. */
    public static CompoundTag write(Map<String, Saved> dimensions) {
        var root = new CompoundTag();
        root.putInt("schema", SCHEMA);
        var all = new CompoundTag();
        for (var entry : new TreeMap<>(dimensions).entrySet()) {
            var saved = entry.getValue();
            var d = new CompoundTag();
            d.putLongArray("keys", saved.field().keys());
            d.putLongArray("amounts", bits(saved.field().amounts()));
            d.putLongArray("ledger", bits(saved.ledger().values()));
            int n = saved.pending().size();
            long[] keys = new long[n];
            long[] amounts = new long[n];
            var channels = new ListTag();
            for (int i = 0; i < n; i++) {
                var release = saved.pending().get(i);
                keys[i] = release.key();
                amounts[i] = Double.doubleToRawLongBits(release.amount());
                channels.add(StringTag.valueOf(release.channel().name()));
            }
            d.putLongArray("pending_keys", keys);
            d.put("pending_channels", channels);
            d.putLongArray("pending_amounts", amounts);
            all.put(entry.getKey(), d);
        }
        root.put("dimensions", all);
        return root;
    }

    /**
     * Reads a saved tag; never throws. An empty tag is a fresh store. A missing, wrong-typed or newer {@code schema}, or a
     * {@code dimensions} entry that is no compound, sets the store aside. A dimension with an unparsable id or damaged
     * cube arrays is dropped; a cube with a non-finite, negative or too large amount, or a key seen before, is dropped and
     * its finite amounts booked {@code dissipated}; a ledger that does not validate is rebuilt from the kept cubes
     * ({@code released} = what they store); a pending entry with an unknown channel or a bad amount is dropped, and damaged
     * pending arrays drop the dimension's pending only.
     */
    public static Loaded read(CompoundTag tag) {
        var notes = new ArrayList<String>();
        try {
            if (tag == null || tag.isEmpty()) {
                return new Loaded(Map.of(), List.of(), false);
            }
            if (!(tag.get("schema") instanceof IntTag schemaTag)) {
                notes.add("schema missing or not an int");
                return new Loaded(Map.of(), notes, true);
            }
            int schema = schemaTag.getAsInt();
            if (schema > SCHEMA || schema < 1) {
                notes.add("schema " + schema + ": this build reads schema 1 to " + SCHEMA);
                return new Loaded(Map.of(), notes, true);
            }
            Tag dimensionsTag = tag.get("dimensions");
            if (dimensionsTag == null) {
                return new Loaded(Map.of(), notes, false);
            }
            if (!(dimensionsTag instanceof CompoundTag all)) {
                notes.add("dimensions is not a compound");
                return new Loaded(Map.of(), notes, true);
            }
            var out = new LinkedHashMap<String, Saved>();
            for (String id : new java.util.TreeSet<>(all.getAllKeys())) {
                try {
                    var saved = dimension(id, all.get(id), notes);
                    if (saved != null) {
                        out.put(id, saved);
                    }
                } catch (RuntimeException damaged) {
                    notes.add("dimension " + id + " dropped: " + damaged);
                }
            }
            return new Loaded(out, notes, false);
        } catch (RuntimeException damaged) {
            notes.add("unreadable: " + damaged);
            return new Loaded(Map.of(), notes, true);
        }
    }

    private static Saved dimension(String id, Tag value, List<String> notes) {
        if (ResourceLocation.tryParse(id) == null) {
            notes.add("dimension " + id + " dropped: not a dimension id");
            return null;
        }
        if (!(value instanceof CompoundTag d)) {
            notes.add("dimension " + id + " dropped: not a compound");
            return null;
        }
        if (!(d.get("keys") instanceof LongArrayTag keysTag) || !(d.get("amounts") instanceof LongArrayTag amountsTag)) {
            notes.add("dimension " + id + " dropped: keys or amounts missing or not long arrays");
            return null;
        }
        long[] keys = keysTag.getAsLongArray();
        long[] amountBits = amountsTag.getAsLongArray();
        if (amountBits.length != keys.length * CHANNELS) {
            notes.add("dimension " + id + " dropped: " + keys.length + " keys but " + amountBits.length + " amounts");
            return null;
        }
        // Each cube alone: bad values or a repeated key drop that cube; the rest are kept, sorted.
        double[] dissipated = new double[CHANNELS];
        Integer[] order = new Integer[keys.length];
        for (int i = 0; i < keys.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (Integer p, Integer q) -> Long.compare(keys[p], keys[q]));
        long[] keptKeys = new long[keys.length];
        double[] keptAmounts = new double[keys.length * CHANNELS];
        int kept = 0;
        int bad = 0;
        int repeated = 0;
        for (int o = 0; o < order.length; o++) {
            int i = order[o];
            double[] cube = new double[CHANNELS];
            boolean valid = true;
            for (int c = 0; c < CHANNELS; c++) {
                cube[c] = Double.longBitsToDouble(amountBits[i * CHANNELS + c]);
                if (!(cube[c] >= 0.0) || !(cube[c] <= MAXIMUM_AMOUNT)) {
                    valid = false;
                }
            }
            boolean duplicate = kept > 0 && keptKeys[kept - 1] == keys[i];
            if (!valid || duplicate) {
                if (duplicate) {
                    repeated++;
                } else {
                    bad++;
                }
                for (int c = 0; c < CHANNELS; c++) {
                    if (cube[c] >= 0.0 && cube[c] <= MAXIMUM_AMOUNT) {
                        dissipated[c] += cube[c];
                    }
                }
                continue;
            }
            keptKeys[kept] = keys[i];
            System.arraycopy(cube, 0, keptAmounts, kept * CHANNELS, CHANNELS);
            kept++;
        }
        if (bad > 0) {
            notes.add("dimension " + id + ": " + bad + " damaged cubes dropped");
        }
        if (repeated > 0) {
            notes.add("dimension " + id + ": " + repeated + " repeated cubes dropped");
        }
        var field = PollutionField.of(Arrays.copyOf(keptKeys, kept), Arrays.copyOf(keptAmounts, kept * CHANNELS));
        var ledger = ledger(id, d.get("ledger"), field, dissipated, notes);
        var pending = pending(id, d, notes);
        return new Saved(field, ledger, pending);
    }

    private static PollutionLedger ledger(String id, Tag value, PollutionField field, double[] dissipated, List<String> notes) {
        if (value instanceof LongArrayTag ledgerTag && ledgerTag.getAsLongArray().length == PollutionLedger.LENGTH) {
            long[] bits = ledgerTag.getAsLongArray();
            double[] book = new double[PollutionLedger.LENGTH];
            boolean valid = true;
            for (int i = 0; i < book.length; i++) {
                book[i] = Double.longBitsToDouble(bits[i]);
                if (!(book[i] >= 0.0) || !Double.isFinite(book[i])) {
                    valid = false;
                }
            }
            if (valid) {
                for (int c = 0; c < CHANNELS; c++) {
                    book[c * TOTALS + PollutionLedger.Total.DISSIPATED.ordinal()] += dissipated[c];
                }
                return PollutionLedger.of(book);
            }
        }
        notes.add("dimension " + id + ": ledger rebuilt from the kept cubes");
        double[] book = new double[PollutionLedger.LENGTH];
        for (Channel channel : Channel.values()) {
            book[channel.ordinal() * TOTALS + PollutionLedger.Total.RELEASED.ordinal()] = field.stored(channel);
        }
        return PollutionLedger.of(book);
    }

    private static List<Release> pending(String id, CompoundTag d, List<String> notes) {
        Tag keysValue = d.get("pending_keys");
        Tag channelsValue = d.get("pending_channels");
        Tag amountsValue = d.get("pending_amounts");
        if (keysValue == null && channelsValue == null && amountsValue == null) {
            return List.of();
        }
        if (!(keysValue instanceof LongArrayTag keysTag) || !(channelsValue instanceof ListTag channels)
                || !(amountsValue instanceof LongArrayTag amountsTag)) {
            notes.add("dimension " + id + ": pending releases dropped: damaged arrays");
            return List.of();
        }
        long[] keys = keysTag.getAsLongArray();
        long[] amounts = amountsTag.getAsLongArray();
        if (keys.length != amounts.length || keys.length != channels.size()) {
            notes.add("dimension " + id + ": pending releases dropped: " + keys.length + " keys, " + channels.size()
                    + " channels, " + amounts.length + " amounts");
            return List.of();
        }
        var out = new ArrayList<Release>(keys.length);
        int bad = 0;
        for (int i = 0; i < keys.length; i++) {
            Channel channel = channels.get(i) instanceof StringTag name ? channel(name.getAsString()) : null;
            double amount = Double.longBitsToDouble(amounts[i]);
            if (channel == null || !(amount >= 0.0) || !(amount <= MAXIMUM_AMOUNT)) {
                bad++;
                continue;
            }
            out.add(new Release(keys[i], channel, amount));
        }
        if (bad > 0) {
            notes.add("dimension " + id + ": " + bad + " pending releases dropped");
        }
        return out;
    }

    private static Channel channel(String name) {
        for (Channel channel : Channel.values()) {
            if (channel.name().equals(name)) {
                return channel;
            }
        }
        return null;
    }

    private static long[] bits(double[] values) {
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Double.doubleToRawLongBits(values[i]);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Opening on a server.

    /** The data folder file of the store. */
    static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
    }

    /**
     * Opens the overworld's store; never throws. A store {@link #read} set aside, or a file the storage could not read
     * at all (it logs the failure and answers nothing), is copied to {@code icheme_pollution.damaged-<n>.dat} first and
     * the store starts empty. If that copy fails, nothing is registered, so the file stays as it is, and the answer is
     * null: the service then runs disabled for the session.
     */
    static PollutionStore open(MinecraftServer server) {
        Path file = file(server);
        var storage = server.overworld().getDataStorage();
        PollutionStore existing;
        String reason = null;
        try {
            existing = storage.get(new SavedData.Factory<PollutionStore>(PollutionStore::new, PollutionStore::load, null), DATA_NAME);
        } catch (RuntimeException failure) {
            existing = null;
            reason = "unreadable: " + failure;
        }
        if (existing == null && reason == null && Files.exists(file)) {
            reason = "the file could not be read as NBT";
        }
        if (existing != null && existing.setAside()) {
            reason = String.join("; ", existing.notes());
        }
        if (reason != null) {
            Path copy;
            try {
                copy = setAside(file);
            } catch (IOException | RuntimeException failure) {
                IChemE.LOGGER.error("pollution_store status=SET_ASIDE_FAILED file={} detail={} copy={}", file, reason, String.valueOf(failure));
                return null;
            }
            IChemE.LOGGER.warn("pollution_store status=SET_ASIDE file={} detail={}", copy == null ? file : copy, reason);
            var fresh = new PollutionStore();
            storage.set(DATA_NAME, fresh);
            fresh.setDirty();
            return fresh;
        }
        if (existing == null) {
            existing = new PollutionStore();
            storage.set(DATA_NAME, existing);
            return existing;
        }
        for (String note : existing.notes()) {
            IChemE.LOGGER.warn("pollution_store status=DROPPED detail={}", note);
        }
        if (!existing.notes().isEmpty()) {
            existing.setDirty();
        }
        return existing;
    }

    /** The storage's deserializer: {@link #read}, never throwing. */
    private static PollutionStore load(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            return new PollutionStore(read(tag));
        } catch (RuntimeException failure) {
            return new PollutionStore(new Loaded(Map.of(), List.of("unreadable: " + failure), true));
        }
    }

    /**
     * Copies {@code file} to {@code <name>.damaged-<n>.dat} beside it, the first free {@code n} from 1, and answers the
     * copy; the original is not touched. Null when there is no file to copy.
     */
    static Path setAside(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        String name = file.getFileName().toString();
        String stem = name.endsWith(".dat") ? name.substring(0, name.length() - ".dat".length()) : name;
        for (int n = 1; n < 10_000; n++) {
            Path copy = file.resolveSibling(stem + ".damaged-" + n + ".dat");
            if (Files.exists(copy)) {
                continue;
            }
            try {
                Files.copy(file, copy);
                return copy;
            } catch (java.nio.file.FileAlreadyExistsException taken) {
                // Taken between the check and the copy: try the next number.
            }
        }
        throw new IOException("no free set-aside name for " + file);
    }
}
